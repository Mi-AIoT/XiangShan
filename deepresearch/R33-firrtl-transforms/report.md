# R33 - FIRRTL Transforms：XiangShan 自定义 FIRRTL 变换通道与电路生成流程

## 1. 概述

XiangShan 处理器项目实现了一套自定义的 FIRRTL (Flexible Intermediate Representation for RTL) 变换通道和电路生成流程，涵盖了从 Chisel 高级语言描述到最终 SystemVerilog 硬件代码输出的完整链路。该流程不仅包含了对 Chisel 内部中间表示 (IR) 的操作性变换，还通过 `XiangShanStage` 实现了自定义的 elaboration（电路展开）阶段，以及在 Verilog 后处理阶段进行的 git 信息注入、`$fatal` 替换和 SystemVerilog 文件拆分等操作。

本报告将详细分析 XiangShan 中与 FIRRTL 变换相关的所有组件，包括自定义 transform passes、Chisel IR 辅助工具、elaboration 配置选项，以及从 FIRRTL 到 Verilog 的完整生成流水线。

---

## 2. Custom FIRRTL Transform Passes

### 2.1 PrintModuleName Transform

**文件位置**: `src/main/scala/xiangshan/transforms/PrintModuleName.scala`

`PrintModuleName` 是 XiangShan 中最核心的自定义 transform pass。它继承自 `firrtl.options.Phase`，运行在 Chisel elaboration 之后、Chisel-to-FIRRTL 转换之前。

#### 设计动机

在 XiangShan 中，调试日志系统 `XSLog` 使用一种延迟应用（deferred apply）策略。在 Chisel elaboration 阶段，`XSLog` 将所有 `Printf` 调用的信息（包括格式字符串、数据、模块标签等）缓存到全局缓冲区 `logInfos` 中。然而，此时模块的 `pathName`（层级路径名称）尚未确定——只有在 elaboration 完成之后才能获得。

`PrintModuleName` transform 的作用就是在 elaboration 完成后、FIRRTL 转换之前，遍历整个 Chisel IR 中的 `Printf` 命令，将 `XSLog` 缓冲区中记录的模块标签（`moduleTag`，此时是模块的 `toString` 表示）替换为实际的 `pathName`（完整的层级路径名称）。

#### 工作原理

该 transform 的关键步骤：

1. **依赖声明**: `prerequisites = Seq(Dependency[Elaborate])` 确保它只在 Chisel elaboration 完成后运行。
2. **遍历策略**: 使用 `circuit.mapComponent` 遍历所有组件（模块），对每个组件使用 `mapCommand` 递归遍历所有命令。
3. **Printf 变换**: 当遇到 `Printf` 命令时，解包其格式字符串和数据，调用 `utility.XSLog.replaceFIRStr(fmt)` 进行字符串替换，然后重新打包成新的 `Printf`。
4. **`replaceFIRStr` 机制**: 在 `XSLog` 对象中定义的 `replaceFIRStr` 方法遍历所有注册的日志模块（`logModules`），将模块的 `toString` 表示替换为 `pathName`：

```scala
def replaceFIRStr(str: String): String = {
  logModules.foldLeft(str) { case (acc, mod) =>
    acc.replace(mod.toString, mod.pathName)
  }
}
```

这个机制确保了生成的 Verilog 中的 `printf` 语句包含正确的模块层级信息，方便调试时定位日志来源。

---

## 3. ChiselCircuitHelpers

**文件位置**: `src/main/scala/xiangshan/transforms/ChiselCircuitHelpers.scala`

`ChiselCircuitHelpers` 是一个工具对象（`object`），位于 `chisel3.internal.firrtl.xiangshan` 包中。它提供了一组隐式类（`implicit class`），为 Chisel 内部 IR 类型提供便捷的遍历和变换操作。

### 3.1 CircuitHelper

```scala
implicit class CircuitHelper(circuit: Circuit) {
  def mapComponent(f: Component => Component): Circuit =
    circuit.copy(components = circuit.components.map(f))
}
```

为 `Circuit` 提供 `mapComponent` 方法，允许对电路中所有组件（Component）应用变换函数。这返回一个新的 `Circuit`，其中的组件列表已被映射。这是 `PrintModuleName` transform 用来遍历整个电路的入口方法。

### 3.2 ComponentHelper

```scala
implicit class ComponentHelper(component: Component) {
  def mapCommand(f: Command => Command): Component = component match {
    case DefModule(id, name, public, layers, ports, block) =>
      DefModule(id, name, public, layers, ports, block.mapCommand(f))
    case DefClass(id, name, ports, block) =>
      DefClass(id, name, ports, block.mapCommand(f))
    case other: Component => other
  }

  def foreachCommand(f: Command => Unit): Unit = component match {
    case DefModule(_, _, _, _, _, block) => block.foreachCommand(f)
    case DefClass(_, _, _, block) => block.foreachCommand(f)
    case _: Component =>
  }
}
```

为 `Component` 提供两种操作：
- `mapCommand`: 对组件的命令块（Block）应用变换函数，支持 `DefModule`（普通模块）和 `DefClass`（类定义）两种组件类型。
- `foreachCommand`: 对组件的所有命令执行副作用操作（仅遍历，不生成新结构）。

### 3.3 CommandHelper

这是 ChiselCircuitHelpers 中最关键的部分，它实现了对三种特殊命令结构的递归遍历：

```scala
implicit class CommandHelper(command: Command) {
  def mapCommand(f: Command => Command): Command = command match {
    case w: When =>
      val when = new When(w.sourceInfo, w.pred)
      blockMapCommand(w.ifRegion, when.ifRegion, f)
      if (w.hasElse) blockMapCommand(w.elseRegion, when.elseRegion, f)
      when
    case l: LayerBlock =>
      val layerBlock = new LayerBlock(l.sourceInfo, l.layer)
      blockMapCommand(l.region, layerBlock.region, f)
      layerBlock
    case Placeholder(sourceInfo, commands) =>
      val placeholder = new Placeholder(sourceInfo)
      commands.foreach(c => placeholder.getBuffer += f(c))
      placeholder
    case other: Command => other
  }
}
```

### 3.4 BlockHelper

```scala
implicit class BlockHelper(block: Block) {
  def mapCommand(f: Command => Command): Block = {
    val nb = new Block(block.sourceInfo)
    blockMapCommand(block, nb, f)
    nb
  }

  def foreachCommand(f: Command => Unit): Unit = {
    block.getCommands().foreach(f)
    block.getSecretCommands().foreach(f)
  }
}
```

为 `Block` 提供 `mapCommand`（创建新块并映射所有命令）和 `foreachCommand`（遍历普通命令和 secret commands）。注意，`foreachCommand` 同时遍历 `getCommands()` 和 `getSecretCommands()`，确保不会遗漏任何命令。

---

## 4. When/LayerBlock/Placeholder Traversal（命令遍历机制）

`ChiselCircuitHelpers.CommandHelper.mapCommand` 中的模式匹配实现了对三种关键命令结构的递归遍历：

### 4.1 When（条件命令）

`When` 命令代表条件执行分支（对应 Chisel 中的 `when` / `elsewhen` / `otherwise`）。遍历时：
1. 创建新的 `When` 对象，保留原始的 `sourceInfo` 和 `pred`（谓词条件）。
2. 对 `ifRegion`（if 分支）中的命令进行递归映射。
3. 如果存在 `elseRegion`（else 分支），也对其进行递归映射。

这种处理确保了嵌套在条件块内的 `Printf` 命令也能被正确变换。

### 4.2 LayerBlock（层级块命令）

`LayerBlock` 用于 Chisel 3.6+ 的 layer 特性（用于条件编译和设计分割）。遍历时创建新的 `LayerBlock` 对象，并递归映射其 `region` 中的命令。XiangShan 利用 layers 来区分不同的设计变体（如 difftest 接口）。

### 4.3 Placeholder（占位符命令）

`Placeholder` 是 Chisel IR 中的一种内部结构，用于缓存一组命令。遍历时创建新的 `Placeholder`，对每个缓存的命令应用变换函数 `f`，并将结果添加到新的 buffer 中。

### 4.4 blockMapCommand 辅助方法

```scala
def blockMapCommand(oldBlk: Block, newBlk: Block, f: Command => Command): Unit = {
  oldBlk.getCommands().foreach(c => newBlk.addCommand(f(c)))
  oldBlk.getSecretCommands().foreach(sc => newBlk.addSecretCommand(f(sc)))
  newBlk.close()
}
```

这是所有遍历操作的底层实现：
1. 遍历旧块中的所有普通命令（`getCommands()`），对每个命令应用变换 `f`，添加到新块。
2. 遍历旧块中的所有 secret commands（`getSecretCommands()`），同样变换后添加。
3. 调用 `newBlk.close()` 标记新块为完成状态。

这种全面的遍历策略确保了无论命令嵌套多深，所有 `Printf` 都能被正确找到并变换。

---

## 5. XiangShanStage - Custom Chisel Elaboration Stage

**文件位置**: `src/main/scala/top/XiangShanStage.scala`

`XiangShanStage` 是 XiangShan 项目自定义的 Chisel 编译阶段，继承自 `circt.stage.ChiselStage`。它是整个 Chisel-to-Verilog 编译流水线的入口点。

### 5.1 类结构

- 继承 `circt.stage.ChiselStage`，这意味着它天然支持 CIRCT (Circuit IR for Compilers and Tools) 后端。
- 自定义 `shell` 使用 `"xiangshan"` 作为程序名，混入了 `CLI`（Command Line Interface）和自定义 `XiangShanCli` trait。
- `includeLoggerOptions = false` 禁用了默认的 logger 选项，XiangShan 使用自己的日志系统。

### 5.2 自定义 PhaseManager 流水线

`XiangShanStage` 的核心是重写的 `run` 方法，它通过 `firrtl.options.PhaseManager` 定义了完整的编译流水线。

**targets**（按顺序执行的目标阶段）：

| 序号 | 阶段 | 作用 |
|------|------|------|
| 1 | `AddImplicitOutputFile` | 根据顶层模块名自动设置输出文件路径 |
| 2 | `AddImplicitOutputAnnotationFile` | 自动添加输出注解文件路径 |
| 3 | `AddSerializationAnnotations` | 添加序列化注解 |
| 4 | `**PrintModuleName**` | **XiangShan 自定义阶段**：替换 Printf 中的模块标签为层级路径 |
| 5 | `Convert` | 将 Chisel IR 转换为 FIRRTL IR |
| 6 | `AddDedupGroupAnnotations` | 添加模块去重组注解（用于优化重复模块） |
| 7 | `AddImplicitOutputFile` (CIRCT) | CIRCT 阶段的隐式输出文件设置 |
| 8 | `CIRCT` | 调用 CIRCT/firtool 将 FIRRTL 编译为 SystemVerilog |

**currentState**（前置阶段，假定已执行）：

| 阶段 | 作用 |
|------|------|
| `AddDefaults` | 添加默认注解和配置 |
| `Checks` | 执行必要的前置检查 |

#### 关键设计决策

`PrintModuleName` 被精确放置在 `Convert`（Chisel IR -> FIRRTL IR）之前。这意味着：
- 它操作的对象是 Chisel 内部 IR（`chisel3.internal.firrtl.ir`），而不是 FIRRTL IR。
- 它可以直接访问 `ChiselCircuitAnnotation`，从中获取 `ElaboratedCircuit`。
- 它的变换在 FIRRTL lowering 之前完成，确保 Printf 中的模块信息是正确的。

### 5.3 Generator 入口

**文件位置**: `src/main/scala/top/Generator.scala`

`Generator.execute` 是所有 Verilog 生成的统一入口：
1. 将 `firtoolOpts`（来自命令行 `--firtool-opt` 参数和 difftest 的自定义选项）转换为 `FirtoolOption` 注解。
2. 创建 `ChiselGeneratorAnnotation`，传入模块生成器 lambda。
3. 调用 `XiangShanStage.execute` 启动完整的编译流水线。

---

## 6. FIRRTL to Verilog Flow

### 6.1 完整流水线

XiangShan 的 Chisel-to-Verilog 编译流程如下：

```
TopMain.main(args)
  -> ArgParser.parse(args)  -- 解析命令行参数，构建 Configuration
  -> Generator.execute(firrtlOpts, soc.module, firtoolOpts)
       -> XiangShanStage.execute(args, annotations)
            -> PhaseManager 执行:
                 1. AddImplicitOutputFile      (设置输出路径)
                 2. AddImplicitOutputAnnotationFile
                 3. AddSerializationAnnotations
                 4. PrintModuleName            (替换 Printf 中的模块路径)
                 5. Convert                    (Chisel IR -> FIRRTL IR)
                 6. AddDedupGroupAnnotations
                 7. AddImplicitOutputFile (CIRCT)
                 8. CIRCT                      (FIRRTL -> SystemVerilog via firtool)
  -> FileRegisters.write("./build", "XSTop.")  -- 输出辅助文件
```

### 6.2 TopMain 入口逻辑

**文件位置**: `src/main/scala/top/Top.scala`（`TopMain` object）

`TopMain` 根据配置选择不同的顶层模块：
- `UseXSNoCDiffTop` -> `XSNoCDiffTop`（带 difftest 的 NoC 顶层）
- `UseXSTileDiffTop` -> `XSTileDiffTop`（带 difftest 的 Tile 顶层）
- `UseXSNoCTop` -> `XSNoCTop`（NoC 顶层，无 difftest）
- 默认 -> `XSTop`（经典顶层）

对于 difftest 变体，使用 `DifftestModule.top(soc.module, topPrefix)` 包装模块，添加 difftest 接口。

### 6.3 firtool 配置

Makefile 中定义了 CIRCT/firtool 的编译选项：

```makefile
MFC_ARGS = --target $(CHISEL_TARGET) \
           --firtool-opt "-O=release --disable-annotation-unknown \
           --lowering-options=explicitBitcast,disallowLocalVariables,disallowPortDeclSharing,locationInfoStyle=none"

ifeq ($(CHISEL_TARGET),systemverilog)
MFC_ARGS += --split-verilog --dump-fir
endif
```

关键 firtool 选项：
- `-O=release`: 使用 release 级优化（最高速度优化）
- `--disable-annotation-unknown`: 忽略未知注解而不报错
- `--lowering-options=explicitBitcast,...`: 控制 Verilog lowering 的具体行为
- `--split-verilog`: 将每个模块输出到单独的 `.sv` 文件
- `--dump-fir`: 输出中间 FIRRTL 文件

---

## 7. Post-Processing：Git 信息注入与 $fatal 替换

### 7.1 Git 信息注入

XiangShan 在多个层面注入 git 版本信息：

#### 7.1.1 Build.mill 中的版本生成

**文件位置**: `build.mill`

`publishVersion` 和 `gitStatus` 都是 `Task.Input`，每次构建时重新计算，确保版本信息最新。版本格式包含 commit hash、dirty 标记、构建用户、主机名和时间戳。对于 release tag 生成 `KunminghuV3-Release-<date>` 格式，否则生成 `KunminghuV3-dev (<user>@<host>) # <time>` 格式。

#### 7.1.2 Resources 注入

```scala
override def resources: T[Seq[PathRef]] = Task.Sources {
  os.write(T.dest / "publishVersion", publishVersion())
  os.write(T.dest / "gitStatus", gitStatus())
  os.write(T.dest / "gitModules", os.proc("git", "submodule", "status").call().out.text())
  ...
}
```

这些文件作为 JVM resources 被打包到类路径中，运行时可通过 `os.resource` 访问。

#### 7.1.3 CommitIDModule：运行时 git 信息输出

**文件位置**: `src/main/scala/xiangshan/backend/fu/NewCSR/CommitIDModule.scala`

`CommitIDModule` 从 resources 读取 `gitStatus` 文件，解析 SHA 和 dirty 状态，作为硬件端口输出。`PrintCommitIDModule` 是一个内联 Verilog BlackBox，在仿真初始阶段通过 `$fwrite` 打印核心的 commit SHA 和 dirty 状态。

#### 7.1.4 Makefile 中的 git 注释注入

```makefile
@{ git log -n 1; git diff; } | sed 's/^/\/\// ' > $(dir $@).__diff__
@cat $(dir $@).__diff__ $@ > $(dir $@).__out__ && mv $(dir $@).__out__ $@
```

在生成的 SystemVerilog 文件头部注入 `// ` 注释的 git log 和 git diff 信息，确保每个生成的 RTL 文件都包含其构建时的精确 git 状态。

#### 7.1.5 DTS (Device Tree Source) 中的版本

```scala
val model = "xiangshan," + os.read(os.resource / "publishVersion")
```

`publishVersion` 也被写入设备树（DTS），用于固件识别处理器版本。

### 7.2 $fatal 替换

**文件位置**: `Makefile`（sim-verilog 生成后的后处理步骤）

Chisel/CIRCT 生成的 SystemVerilog 中包含 `$fatal` 系统任务（来自 `assert` 和 `require`）。XiangShan 在 Makefile 中对其进行后处理替换。三种替换策略：

| 模式 | 条件 | 替换规则 | 用途 |
|------|------|----------|------|
| PLDM | `PLDM=1` | `$fatal` -> `$finish` | PLDM 模式下只终止仿真而不报告错误 |
| ENABLE_XPROP | `ENABLE_XPROP=1` | `$fatal` -> `assert(1'b0)` | X-propagation 模式下使用静默断言 |
| 默认 | - | `$fatal` -> `xs_assert_v2(\`__FILE__, \`__LINE__)` | 使用自定义断言宏，提供文件名和行号信息 |

此外，`$error(` 被替换为 `$fwrite(32'h80000002, `，将错误输出重定向到特定的文件描述符（`0x80000002` 是 XiangShan 仿真环境中的 stderr 通道）。

---

## 8. Split SystemVerilog Output

### 8.1 --split-verilog 机制

XiangShan 在默认构建配置中使用 `--split-verilog` firtool 选项：

```makefile
MFC_ARGS += --split-verilog --dump-fir
```

该选项告诉 firtool 将每个 SystemVerilog 模块（module）输出到独立的 `.sv` 文件中，而不是将所有模块放在一个文件中。这种做法的优势包括：

1. **增量编译**: 只有修改的模块对应的文件需要重新编译。
2. **并行编译**: 多个 `.sv` 文件可以被并行处理。
3. **减少内存**: 仿真器不需要一次加载整个巨型文件。
4. **版本控制友好**: Git diff 更清晰，只显示实际变化的模块。

### 8.2 git 信息注入到输出文件

git 信息只被注入到主输出文件（`XSTop.sv` 或类似的顶层文件）的头部，其他分割出去的模块文件不包含 git 信息。

---

## 9. Elaboration Configuration Options

### 9.1 DebugOptions

**文件位置**: `src/main/scala/xiangshan/Parameters.scala`

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `FPGAPlatform` | Boolean | false | FPGA 平台模式 |
| `DumpCSR` | Boolean | false | 转储 CSR 内容 |
| `ResetGen` | Boolean | false | 启用 ResetGen |
| `EnableDifftest` | Boolean | false | 启用 difftest |
| `AlwaysBasicDiff` | Boolean | true | 始终启用基础 difftest |
| `EnableDebug` | Boolean | false | 启用调试日志 |
| `EnablePerfDebug` | Boolean | true | 启用性能调试 |
| `PerfLevel` | String | "VERBOSE" | 性能日志级别 |
| `EnableXMR` | Boolean | true | 启用跨模块引用 |
| `SimMemSize` | Long | 8190GB | 仿真内存大小 |
| `UseDRAMSim` | Boolean | false | 使用 DRAMsim3 |
| `EnableConstantin` | Boolean | false | 启用 Constantin |
| `EnableChiselDB` | Boolean | false | 启用 ChiselDB |
| `AlwaysBasicDB` | Boolean | true | 始终启用基础 DB |
| `EnableRollingDB` | Boolean | false | 启用 RollingDB |
| `EnableSimFrontend` | Boolean | false | 启用仿真前端 |

### 9.2 DFTOptions

```scala
case class DFTOptions(
  EnableMbist: Boolean = true,
  EnableSramCtl: Boolean = false,
)
```

控制 DFT（Design For Test）特性：MBIST 控制器和 SRAM 控制器。

### 9.3 Config 类层次

**文件位置**: `src/main/scala/top/Configs.scala`

| 配置类 | 用途 |
|--------|------|
| `BaseConfig(n)` | 基础配置，设置 n 个核 |
| `DefaultConfig(n)` | 默认全功能配置（16MB L3, 2MB L2, 64KB L1D） |
| `MinimalConfig(n)` | 最小配置（4MB L3, 128KB L2, 32KB L1D） |
| `FpgaDefaultConfig(n)` | FPGA 默认配置 |
| `XSNoCTopConfig(n)` | 使用 NoC 拓扑的配置 |
| `XSNoCDiffTopConfig(n)` | 使用 NoC + difftest 的配置 |
| `BackendV2Config(n)` | 后端 V2 配置（6 宽度） |
| `FuzzConfig` | Fuzzing 测试配置 |
| `FrontendDebugConfig(n)` | 前端调试配置 |

### 9.4 YAML 配置支持

**文件位置**: `src/main/scala/top/YamlParser.scala`

XiangShan 支持通过 `--yaml-config <file.yaml>` 参数加载 YAML 配置文件，覆盖命令行参数。支持的配置项包括：Config、PmemRanges、L2CacheConfig、OpenLLCConfig、HartIDBits、DebugModuleParams、EnableCHIAsyncBridge、IMSICBusType、WFIClockGate、EnablePowerDown、XSTopPrefix 等。

### 9.5 命令行参数解析

**文件位置**: `src/main/scala/top/ArgParser.scala`

`ArgParser.parse` 返回三元组 `(Parameters, Array[String], Array[String])`：Chisel 配置参数、FIRRTL 编译选项、firtool 特定选项。

---

## 10. FileRegisters 与辅助文件生成

**文件位置**: `utility/src/main/scala/utility/FileRegisters.scala`

`FileRegisters` 是一个全局注册表，在 elaboration 过程中收集各种辅助文件的内容，最后统一写入磁盘。在 `TopMain` 执行完毕后调用：

```scala
FileRegisters.write(fileDir = "./build", filePrefix = "XSTop.")
```

生成的辅助文件包括：

| 文件 | 内容 | 来源 |
|------|------|------|
| `XSTop.dts` | Device Tree Source | BaseXSSoc |
| `XSTop.graphml` | 模块层级图 | BaseXSSoc |
| `XSTop.json` | 模块层级 JSON | BaseXSSoc |
| `XSTop.plusArgs` | PlusArg 参数头文件 | Rocket-Chip |
| `chisel_db.h` / `chisel_db.cpp` | ChiselDB 数据库 | ChiselDB |
| `perfCCT.h` / `perfCCT.cpp` | 性能计数器 CCT | ChiselTaggedTrace |
| `constantin.hpp` / `constantin.cpp` | Constantin 常量接口 | Constantin |

此外，`XSNoCDiffTopChecker` 还会生成 `XSDiffTopChecker.sv` 文件。

---

## 11. 关键源文件位置索引

| 文件路径 | 作用 |
|----------|------|
| `src/main/scala/xiangshan/transforms/PrintModuleName.scala` | PrintModuleName transform pass |
| `src/main/scala/xiangshan/transforms/ChiselCircuitHelpers.scala` | Chisel IR 遍历辅助工具 |
| `src/main/scala/top/XiangShanStage.scala` | 自定义 Chisel elaboration stage |
| `src/main/scala/top/Generator.scala` | 编译入口（调用 XiangShanStage） |
| `src/main/scala/top/Top.scala` | TopMain 入口和 XSTop 顶层模块 |
| `src/main/scala/top/Configs.scala` | 配置类层次结构 |
| `src/main/scala/top/ArgParser.scala` | 命令行参数解析 |
| `src/main/scala/top/YamlParser.scala` | YAML 配置解析 |
| `src/main/scala/top/XSNoCTop.scala` | XSNoCTop 顶层模块 |
| `src/main/scala/xiangshan/Parameters.scala` | DebugOptions 和 DFTOptions 定义 |
| `src/main/scala/xiangshan/backend/fu/NewCSR/CommitIDModule.scala` | Git commit ID 硬件注入 |
| `utility/src/main/scala/utility/LogUtils.scala` | XSLog 日志系统（replaceFIRStr） |
| `utility/src/main/scala/utility/FileRegisters.scala` | 辅助文件注册与输出 |
| `build.mill` | Mill 构建配置（版本生成、资源打包） |
| `Makefile` | 构建脚本（git 注入、$fatal 替换、split verilog） |

---

## 12. 总结

XiangShan 的 FIRRTL 变换和电路生成流程体现了高度定制化的硬件生成方法论：

1. **自定义 PrintModuleName Transform**: 解决了 Chisel elaboration 阶段无法获取模块完整层级路径的问题，通过在 Chisel IR 层面对 Printf 命令进行变换，确保生成的 Verilog 调试日志包含准确的模块路径信息。

2. **ChiselCircuitHelpers 工具库**: 提供了完整的 Chisel IR 遍历基础设施，支持 When、LayerBlock、Placeholder 等嵌套结构的递归变换，为自定义 transform pass 提供了坚实的基础。

3. **XiangShanStage 自定义 Stage**: 通过 PhaseManager 精确控制 elaboration 流水线的每个阶段，将自定义的 PrintModuleName 变换无缝集成到标准的 Chisel 编译流程中。

4. **多层 Git 信息注入**: 从 build.mill 的版本生成，到运行时 CommitIDModule 的硬件注入，再到 Makefile 的文件头注释，实现了构建产物的完整可追溯性。

5. **灵活的后处理机制**: 通过 Makefile 中的 sed 替换，实现了对 `$fatal` 和 `$error` 系统任务的多种替换策略，适应 PLDM、X-propagation、标准仿真等不同验证场景。

6. **Split SystemVerilog**: 利用 firtool 的 `--split-verilog` 特性，将设计分解为独立的模块文件，支持增量编译和并行处理。

这套系统展示了现代硬件设计方法学中 Chisel/FIRRTL 工具链的深度定制能力，为大规模 RISC-V 处理器的高效开发和验证提供了强有力的基础设施支撑。
