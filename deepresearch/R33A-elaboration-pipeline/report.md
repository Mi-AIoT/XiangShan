# R33A -- XiangShan Chisel Elaboration Pipeline Deep-Dive

## 1. Overview: From Chisel RTL to Verilog

XiangShan 处理器的设计使用 Chisel 3 硬件描述语言编写，但最终的 Verilog 生成并不是通过标准的 `ChiselStage` 完成的。XiangShan 构建了一套完全自定义的 **Chisel Elaboration Pipeline**，核心组件包括：

- **`XiangShanStage`** -- 自定义 ChiselStage，内含自建的 `PhaseManager`（8 个 Phase）
- **`PrintModuleName` transform** -- FIRRTL-level 的 Printf placeholder 字符串替换
- **`ChiselCircuitHelpers`** -- 对 Chisel IR（`Circuit`, `Component`, `Command`, `Block`）的递归 map/traverse 工具
- **`Generator`** -- 全局统一入口，封装 `XiangShanStage.execute`
- **`ArgParser`** -- 命令行参数解析，控制 Config 和 firtool 选项
- **`TopMain`** -- Scala App 入口，组装一切并调用 `Generator.execute`

这套 pipeline 的核心设计哲学是：**在 Chisel-to-FIRRTL 的转换过程中，插入自定义 IR transform，对 elaborated circuit 进行原地修改，再送入 CIRCT/firtool 后端生成 Verilog**。

---

## 2. XiangShanStage: Custom PhaseManager (8 Phases)

### 2.1 源文件位置

`src/main/scala/top/XiangShanStage.scala`

### 2.2 类继承结构

```scala
class XiangShanStage extends ChiselStage {
  // 继承自 circt.stage.ChiselStage
  // 而非 chisel3.stage.ChiselStage
}
```

`XiangShanStage` 继承的是 `circt.stage.ChiselStage`（来自 `circt.stage` 包），而非旧式的 `chisel3.stage.ChiselStage`。这意味着其后端默认使用 CIRCT (MLIR-based) 编译流水线，而非 FIRRTL-Scala 后端。但 XiangShan 在此基础上进一步覆盖了 `run` 方法，完全替换了默认的 PhaseManager。

### 2.3 Shell 自定义

```scala
override val shell = new firrtl.options.Shell("xiangshan") with CLI with XiangShanCli {
  override protected def includeLoggerOptions = false
}
```

Shell 被命名为 `"xiangshan"`，混入了 `CLI` trait（来自 `circt.stage`）和自定义的 `XiangShanCli` trait。后者目前只是向帮助文本添加 `"XiangShan Options"` 标题。`includeLoggerOptions = false` 禁用了默认的 logger 选项注入。

### 2.4 自建 PhaseManager 的 8 个 Phase

`run` 方法的核心是手动构建了一个 `firrtl.options.PhaseManager`，包含 **8 个目标 Phase（targets）** 和 **2 个已满足的前置依赖（currentState）**：

| 序号 | Phase Name | Package | 功能说明 |
|------|-----------|---------|---------|
| 1 | `AddImplicitOutputFile` | `chisel3.stage.phases` | 根据 Top 模块名自动添加默认输出文件名 |
| 2 | `AddImplicitOutputAnnotationFile` | `chisel3.stage.phases` | 自动添加 `.anno` 输出文件路径 |
| 3 | `AddSerializationAnnotations` | `chisel3.stage.phases` | 添加序列化 annotations |
| 4 | **`PrintModuleName`** | `chisel3.stage.phases.xiangshan` | **XiangShan 自定义 Phase**：替换 Printf 中的模块名 placeholder |
| 5 | `Convert` | `chisel3.stage.phases` | 将 Chisel IR (ElaboratedCircuit) 转换为 FIRRTL IR (FirrtlCircuit) |
| 6 | `AddDedupGroupAnnotations` | `chisel3.stage.phases` | 添加 dedup group annotations 供后续 dedup pass 使用 |
| 7 | `AddImplicitOutputFile` | `circt.stage.phases` | CIRCT 阶段的输出文件添加（注意与 Phase 1 来自不同包） |
| 8 | **`CIRCT`** | `circt.stage.phases` | **核心后端**：调用 CIRCT/firtool 编译器将 FIRRTL 转换为 Verilog |

已满足的前置条件（currentState）：
- `firrtl.stage.phases.AddDefaults` -- 添加默认 annotations
- `firrtl.stage.phases.Checks` -- 执行 annotations 合法性检查

### 2.5 为什么手动构建 PhaseManager？

这是整个 pipeline 设计中最关键的决策。标准的 `ChiselStage.execute` 会自动构建 PhaseManager 并包含完整的 FIRRTL 编译 pipeline（包括 FIRRTL 中间优化 pass 等）。XiangShan 选择手动控制，原因在于：

1. **需要在 `Convert` 之前插入 `PrintModuleName`** -- 这个 Phase 操作的是 Chisel IR（尚未转换为 FIRRTL），因此必须位于 `Convert` 之前
2. **跳过旧 FIRRTL 编译器** -- 使用 CIRCT 后端意味着不需要 FIRRTL-Scala 的优化 pass
3. **精简 pipeline** -- 只保留必要步骤，减少 elaboration 时间
4. **精确控制 Phase 顺序** -- 确保 `PrintModuleName` 在 `Elaborate`（隐式存在于currentState）之后、`Convert` 之前执行

### 2.6 PhaseManager 的执行模型

```scala
val pm = new firrtl.options.PhaseManager(
  targets = Seq(...),     // 8 个目标
  currentState = Seq(...) // 2 个已满足条件
)
pm.transform(annotations)
```

`PhaseManager` 使用 `Dependency` 图来解析 Phase 之间的拓扑排序。每个 Phase 可以声明 `prerequisites`（前置依赖）和 `invalidates`（会失效的后续 Phase）。`targets` 是用户指定的最终要执行的 Phase，`currentState` 标记哪些 Phase 已被视为完成。`PhaseManager` 会自动计算最短执行路径，添加隐式依赖，确保所有声明的 prerequisite 都被满足。

---

## 3. PrintModuleName Transform

### 3.1 源文件位置

`src/main/scala/xiangshan/transforms/PrintModuleName.scala`
Package: `chisel3.stage.phases.xiangshan`

### 3.2 功能概述

`PrintModuleName` 是一个 FIRRTL `Phase`，在 Chisel elaboration 之后、Chisel IR 转换为 FIRRTL IR 之前执行。它的核心功能是：**遍历 Chisel Circuit 中所有的 Printf 语句，将其中的模块名 placeholder 替换为实际的分层路径名称（pathName）**。

### 3.3 Prerequisites 和 Invalidation

```scala
override def prerequisites = Seq(Dependency[Elaborate])
override def invalidates(a: Phase) = false
```

- **前置依赖**: `Elaborate` -- 确保 Chisel circuit 已完成 elaboration
- **失效声明**: 不会使任何后续 Phase 失效（`false` 对所有 Phase）

### 3.4 transform 实现

```scala
def transform(annotations: AnnotationSeq): AnnotationSeq = {
  def onCommand(c: Command): Command = c match {
    case Printf(id, sourceInfo, filename, clock, pable) =>
      val (fmt, data) = pable.unpack
      val newPable = Printable.pack(utility.XSLog.replaceFIRStr(fmt), data:_*)
      Printf(id, sourceInfo, filename, clock, newPable)
    case other: Command => other.mapCommand(onCommand)
  }
  // ...
}
```

该 transform 对 annotations 列表进行 `flatMap`：
1. 检测每个 annotation 是否为 `ChiselCircuitAnnotation`
2. 如果是，获取其内部的 `ElaboratedCircuit`，提取其 `_circuit`（Chisel `Circuit` 对象）
3. 对 circuit 的每个 component 执行 `mapCommand(onCommand)`，递归替换所有 Printf
4. 将修改后的 circuit 重新包装为 `ElaboratedCircuit` 和 `ChiselCircuitAnnotation`
5. 非 `ChiselCircuitAnnotation` 的 annotation 原样传递

### 3.5 replaceFIRStr 的机制

`replaceFIRStr` 定义在 `utility/src/main/scala/utility/LogUtils.scala` 中：

```scala
def replaceFIRStr(str: String): String = {
  logModules.foldLeft(str) { case (acc, mod) =>
    acc.replace(mod.toString, mod.pathName)
  }
}
```

在 Chisel elaboration 过程中，`XSLog` 会将所有调用 `XSLog.apply` 的模块注册到 `logModules` 列表。当 elaboration 完成后，每个模块的 `toString` 返回其短名称（如 `"Frontend"`），而 `pathName` 返回完整的层次路径（如 `"XSTop.frontend"`）。

`replaceFIRStr` 在 Printf 格式字符串中执行简单的字符串替换：将短模块名替换为完整路径名。这确保了仿真时的 log 输出能够显示精确的模块层次信息，而非可能出现重名的短名称。

### 3.6 为什么不能在 FIRRTL 阶段完成？

Printf 在 Chisel IR 中是 `chisel3.internal.firrtl.ir.Printf` 对象，包含一个 `Printable`。到 FIRRTL IR 阶段后，Printf 已被扁平化为 `Verbatim` 语句，格式字符串变成了 Verilog `$write`/`$display` 的一部分，模块名 placeholder 已经是字面字符串，替换需要更复杂的文本匹配。在 Chisel IR 阶段操作更为直接和可靠。

---

## 4. ChiselCircuitHelpers: Map/Traverse Chisel IR

### 4.1 源文件位置

`src/main/scala/xiangshan/transforms/ChiselCircuitHelpers.scala`
Package: `chisel3.internal.firrtl.xiangshan`

### 4.2 设计概览

`ChiselCircuitHelpers` 是一个纯函数式工具对象，提供了对 Chisel 内部 IR 的 map 和 traverse 操作。它通过 Scala implicit class 模式扩展了四个 Chisel IR 类型：

- `Circuit` -> `CircuitHelper`
- `Component` -> `ComponentHelper`
- `Command` -> `CommandHelper`
- `Block` -> `BlockHelper`

### 4.3 CircuitHelper

```scala
implicit class CircuitHelper(circuit: Circuit) {
  def mapComponent(f: Component => Component): Circuit =
    circuit.copy(components = circuit.components.map(f))
}
```

对 circuit 中所有 `Component`（即所有 module/defmodule）应用变换函数 `f`，返回新的 `Circuit`。这是 `PrintModuleName` transform 的入口点。

### 4.4 ComponentHelper

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
    case DefClass(_, _, _, block)       => block.foreachCommand(f)
    case _: Component =>
  }
}
```

区分了三种 Component 类型：
- **`DefModule`** -- 普通硬件模块，包含 `id`、`name`、`public` 标志、`layers` 层级信息、`ports` 端口列表、`block` 命令块
- **`DefClass`** -- Scala 类定义（非硬件），包含 `id`、`name`、`ports`、`block`
- **其他 Component** -- 直接返回，不做处理

### 4.5 CommandHelper: 递归遍历的核心

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

这是整套 IR traversal 的**递归核心**，处理三种具有嵌套结构的 Command 类型：

#### 4.5.1 When (条件分支)

```scala
case w: When =>
  val when = new When(w.sourceInfo, w.pred)
  blockMapCommand(w.ifRegion, when.ifRegion, f)
  if (w.hasElse) blockMapCommand(w.elseRegion, when.elseRegion, f)
  when
```

`When` 命令有两个嵌套 Block：`ifRegion`（条件为真时的命令）和 `elseRegion`（条件为假时的命令，可能为空）。`mapCommand` 对两个 region 分别执行 `blockMapCommand`，确保变换函数 `f` 能递归触及 When 内部的每条命令。

#### 4.5.2 LayerBlock (硬件层块)

```scala
case l: LayerBlock =>
  val layerBlock = new LayerBlock(l.sourceInfo, l.layer)
  blockMapCommand(l.region, layerBlock.region, f)
  layerBlock
```

`LayerBlock` 用于 Chisel 的硬件层（Layer）特性，将一组命令封装在特定的硬件层中（如 verification layer、synthesis 约束层等）。`mapCommand` 递归处理其 `region` 中的命令。

#### 4.5.3 Placeholder (占位命令)

```scala
case Placeholder(sourceInfo, commands) =>
  val placeholder = new Placeholder(sourceInfo)
  commands.foreach(c => placeholder.getBuffer += f(c))
  placeholder
```

`Placeholder` 是 Chisel 内部用于延迟求值的占位结构，包含一个命令缓冲区。`mapCommand` 对缓冲区中每条命令应用 `f`，通过 `getBuffer +=` 将结果写回新的 Placeholder。

#### 4.5.4 其他 Command

对于叶子 Command（如 `Printf`、`Connect`、`DefWire` 等不含嵌套结构的命令），直接返回 `other`，由外层的 `f` 决定如何处理。

### 4.6 BlockHelper 和 blockMapCommand

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

def blockMapCommand(oldBlk: Block, newBlk: Block, f: Command => Command): Unit = {
  oldBlk.getCommands().foreach(c => newBlk.addCommand(f(c)))
  oldBlk.getSecretCommands().foreach(sc => newBlk.addSecretCommand(f(sc)))
  newBlk.close()
}
```

Block 的遍历需要注意两个列表：
- **`getCommands()`** -- 普通命令列表，包含显式硬件操作
- **`getSecretCommands()`** -- 秘密命令列表，包含由 Chisel 编译器生成的隐式命令（如 connections）

`blockMapCommand` 在遍历完成后调用 `close()` 方法，这是 Chisel Block 的规范用法，标记 Block 不再接受新命令。

### 4.7 递归遍历的完整路径

以 `PrintModuleName` 为例，完整的遍历路径为：

```
Circuit
  -> CircuitHelper.mapComponent(onCommand)
    -> 对每个 Component:
      -> ComponentHelper.mapCommand(onCommand)
        -> 对 DefModule 的 block:
          -> BlockHelper.mapCommand(onCommand)
            -> blockMapCommand:
              -> 对 block 中每条 Command:
                -> CommandHelper.mapCommand(onCommand) [递归]
                  -> When: 递归处理 ifRegion/elseRegion
                  -> LayerBlock: 递归处理 region
                  -> Placeholder: 递归处理 buffer
                  -> Printf: 应用替换逻辑 (命中)
                  -> 其他: 直接返回
```

---

## 5. When / LayerBlock / Placeholder Recursive Traversal Details

### 5.1 Why Recursive Traversal Matters

Chisel IR 的 Command 结构是**树状嵌套**的，而非扁平列表。一个简单的 `when (cond) { printf(...) }` 在 IR 中会表示为：

```
When(cond, ifRegion = Block(Printf(...)), elseRegion = Block())
```

如果只对顶层 Command 列表做 flatMap/map，嵌套在 `When` 内部的 Printf 会被完全忽略。这就是 `CommandHelper.mapCommand` 需要对 `When`、`LayerBlock`、`Placeholder` 做递归展开的原因。

### 5.2 When 的结构

在 Chisel IR 中，`When` 是一个 `Command` 子类，包含：
- `sourceInfo` -- 源码位置信息
- `pred` -- 条件信号（`UInt`）
- `ifRegion` -- 条件为真时执行的 `Block`
- `elseRegion` -- 条件为假时执行的 `Block`（可通过 `hasElse` 检查是否存在）

`mapCommand` 为 When 创建新的实例（避免修改原始 IR），然后对两个 region 分别执行 `blockMapCommand`，将变换后的命令填充到新 Block 中。

### 5.3 LayerBlock 的结构

`LayerBlock` 用于 Chisel 3.5+ 引入的硬件层（Layer）机制。它将一组命令封装在一个层中，用于区分不同综合/验证目标。其结构为：
- `sourceInfo` -- 源码位置
- `layer` -- 层标识
- `region` -- 包含命令的 Block

### 5.4 Placeholder 的结构

`Placeholder` 是 Chisel 内部的延迟求值占位符。它持有一个命令缓冲区（通过 `getBuffer` 访问），在 elaboration 过程中用于推迟某些命令的实际插入。`mapCommand` 通过遍历缓冲区并使用 `getBuffer +=` 来替换其中的命令。

---

## 6. TopMain Entry Point and Generator.execute

### 6.1 TopMain

`src/main/scala/top/Top.scala`（定义在文件末尾，line 408-447）

`TopMain` 是一个 `App`（Scala 应用程序入口），其执行流程如下：

```scala
object TopMain extends App {
  // 1. 解析命令行参数
  val (config, firrtlOpts, firtoolOpts) = ArgParser.parse(args)

  // 2. 初始化全局工具
  val envInFPGA = config(DebugOptionsKey).FPGAPlatform
  val enableDifftest = config(DebugOptionsKey).EnableDifftest || config(DebugOptionsKey).AlwaysBasicDiff
  Constantin.init(enableConstantin && !envInFPGA)
  ChiselDB.init(enableChiselDB && !envInFPGA)

  // 3. 根据 Config 选择 Top 模块变体
  val topPrefix = config(SoCParamsKey).XSTopPrefix
  if (config(SoCParamsKey).UseXSNoCDiffTop) {
    val soc = DisableMonitors(p => LazyModule(new XSNoCDiffTop()(p)))(config)
    Generator.execute(firrtlOpts, DifftestModule.top(soc.module, topPrefix), firtoolOpts)
  } else if (config(SoCParamsKey).UseXSTileDiffTop) {
    val soc = DisableMonitors(p => LazyModule(new XSTileDiffTop()(p)))(config)
    Generator.execute(firrtlOpts, DifftestModule.top(soc.module, topPrefix), firtoolOpts)
  } else {
    val soc = if (config(SoCParamsKey).UseXSNoCTop)
      DisableMonitors(p => LazyModule(new XSNoCTop()(p)))(config)
    else
      DisableMonitors(p => LazyModule(new XSTop()(p)))(config)
    Generator.execute(firrtlOpts, soc.module, firtoolOpts)
    if (enableDifftest) {
      DifftestModule.collect("XiangShan")
    }
  }

  // 4. 输出辅助文件
  FileRegisters.write(fileDir = "./build", filePrefix = "XSTop.")
}
```

### 6.2 Generator.execute

`src/main/scala/top/Generator.scala`

```scala
object Generator {
  def execute(args: Array[String], mod: => chisel3.RawModule, firtoolOpts: Array[String]) = {
    val annotations = firtoolOpts.map(FirtoolOption.apply).toSeq
    (new XiangShanStage).execute(args, ChiselGeneratorAnnotation(() => mod) +: annotations)
  }
}
```

`Generator.execute` 是一个薄封装：
1. 将 `firtoolOpts`（字符串数组）转换为 `FirtoolOption` annotation 序列
2. 将模块生成器 `() => mod` 包装为 `ChiselGeneratorAnnotation`
3. 将 `ChiselGeneratorAnnotation` 和 `FirtoolOption` annotations 合并
4. 创建 `XiangShanStage` 实例并调用其 `execute` 方法

注意参数 `mod` 使用了 Scala 的 **by-name parameter** (`=> chisel3.RawModule`)，这意味着模块的实际 elaboration 会延迟到 `XiangShanStage` 内部的 `Elaborate` Phase 中执行，而非在调用 `Generator.execute` 时立即 elaboration。

### 6.3 TopMain 的多路分发逻辑

TopMain 根据 `SoCParamsKey` 中的配置标志选择四种顶层模块变体之一：

| 条件 | Top 模块 | Difftest 方式 |
|------|---------|--------------|
| `UseXSNoCDiffTop = true` | `XSNoCDiffTop` | 通过 `DifftestModule.top` 包装，使用 XMR (H 模式) |
| `UseXSTileDiffTop = true` | `XSTileDiffTop` | 通过 `DifftestModule.top` 包装 |
| `UseXSNoCTop = true` | `XSNoCTop` | 独立 Difftest（U 模式） |
| 其他（默认） | `XSTop` | 独立 Difftest（U 模式） |

---

## 7. ArgParser Command-Line Options

### 7.1 源文件位置

`src/main/scala/top/ArgParser.scala`

### 7.2 参数解析流程

`ArgParser.parse` 接受 `args: Array[String]`，返回三元组 `(Parameters, Array[String], Array[String])`：
1. `Parameters` -- CDE 配置参数（经过所有命令行覆盖后）
2. `Array[String]` -- firrtl 选项（未知参数归入此类）
3. `Array[String]` -- firtool 选项

解析流程：
1. 以 `DefaultConfig(1)` 作为默认配置
2. 通过 `DifftestModule.parseArgs` 预处理 difftest 相关参数
3. 使用 `@tailrec` 递归函数 `nextOption` 逐个处理参数
4. 最后进行 `LogUtilsOptionsKey` 和 `PerfCounterOptionsKey` 的派生配置

### 7.3 主要命令行选项

#### 核心配置类选项

| 选项 | 说明 |
|------|------|
| `--config <ClassName>` | 指定顶层 Config 类名（如 `DefaultConfig`、`MinimalConfig`、`XSNoCTopConfig`） |
| `--num-cores <Int>` | 核心数量，动态调整 XSTileKey |
| `--yaml-config <File>` | 通过 YAML 文件加载配置（YamlParser） |

#### 调试与仿真选项

| 选项 | 对应 DebugOptions 字段 |
|------|----------------------|
| `--enable-difftest` | `EnableDifftest = true` |
| `--enable-log` | `EnableDebug = true` |
| `--disable-perf` | `EnablePerfDebug = false` |
| `--disable-alwaysdb` | `AlwaysBasicDB = false` |
| `--with-chiseldb` | `EnableChiselDB = true` |
| `--with-rollingdb` | `EnableRollingDB = true` |
| `--with-constantin` | `EnableConstantin = true` |
| `--enable-simfrontend` | `EnableSimFrontend = true` |
| `--dump-csr` | `DumpCSR = true` |
| `--disable-always-basic-diff` | `AlwaysBasicDiff = false` |
| `--disable-xmr` | `EnableXMR = false` |
| `--sim-mem-size <GB>` | `SimMemSize` (字节) |
| `--perf-level <Level>` | `PerfLevel` 字符串 |

#### 平台选项

| 选项 | 说明 |
|------|------|
| `--fpga-platform` | `FPGAPlatform = true`，禁用 difftest、ChiselDB、Constantin |
| `--reset-gen` | `ResetGen = true` |
| `--with-dramsim3` | `UseDRAMSim = true` |

#### 缓存配置选项

| 选项 | 说明 |
|------|------|
| `--l2-cache-size <KB>` | L2 缓存大小（KB），自动计算 sets |
| `--l3-cache-size <KB>` | L3/LLC 缓存大小（KB），自动计算 sets |

#### SoC 配置选项

| 选项 | 说明 |
|------|------|
| `--xstop-prefix <Str>` | XSTop module 名称前缀 |
| `--imsic-bus-type <Type>` | IMSIC 总线类型（AXI4/TL） |
| `--chi-addr-width <Int>` | CHI 地址宽度 |
| `--issue <Str>` | CHI 协议版本 |
| `--enable-ns` | 非安全模式 |
| `--seperate-bus <Type>` | 分离总线类型（NONE/TL/AXI） |
| `--seperate-dm` | 分离 Debug Module |
| `--private-clint` | 私有 CLINT |
| `--hartidbits <Int>` | Hart ID 位宽 |

#### DFT 选项

| 选项 | 说明 |
|------|------|
| `--dfx <Bool>` | 启用 Mbist |
| `--sram-with-ctl` | 启用 SRAM 控制器 |

#### Firtool 直传选项

| 选项 | 说明 |
|------|------|
| `--firtool-opt <Opt>` | 直接传递给 firtool 的选项（支持空格分隔多选项） |

### 7.4 未知选项的处理

```scala
case option :: tail =>
  // unknown option, maybe a firrtl option, skip
  firrtlOpts :+= option
  nextOption(config, tail)
```

未识别的选项会被收集到 `firrtlOpts` 数组中，最终作为参数传递给 `XiangShanStage.execute`。这允许用户传递标准的 FIRRTL 选项而无需修改 ArgParser。

---

## 8. Top-Level Module Variants

### 8.1 Variant Hierarchy

```
BaseXSSoc (abstract, LazyModule)
  |
  +-- XSTop                     // Traditional SoC top, uses Diplomacy TileLink
  |     |
  |     +-- XSTileDiffTop       // XSTop with DifftestInterface
  |
  +-- XSNoCTop                  // NoC-based SoC top, uses CHI protocol
        |
        +-- XSNoCDiffTop        // XSNoCTop with DifftestInterface
```

### 8.2 XSTop (Top.scala)

`src/main/scala/top/Top.scala` -- line 84

`XSTop` 是传统的 XiangShan SoC 顶层，特征如下：
- 继承自 `BaseXSSoc`，使用 `LazyModule` + `LazyRawModuleImp` 模式
- 内含 `MemMisc`（或 `SoCMisc`，取决于是否使用 CHI）
- 包含 `core_with_l2: Seq[XSTile]`，每个 tile 包含一个 XSCore 和 L2 cache
- 使用 Diplomacy TileLink 进行片上互联
- 可选 CHI 协议支持（通过 `OpenNCB` bridge）
- 实现完整的 IO 接口：`memory`（AXI4）、`peripheral`（AXI4）、`systemjtag`、`rtc_clock`、`traceCoreInterface` 等
- 使用 `HasDTSImp` trait 生成 Device Tree Source

### 8.3 XSTileDiffTop (Top.scala)

`src/main/scala/top/Top.scala` -- line 396

继承自 `XSTop`，混入 `HasDiffTestInterfaces` trait。关键区别：
- 其 `desiredName` 被覆盖为 `"XSTop"`，确保与 `XSNoCDiffTop` 生成相同名称的 Verilog 模块
- 混入 `HasDiffTestInterfaces` 以添加 difftest 验证接口
- 设置 `cpuName = Some("XiangShan")`

### 8.4 XSNoCTop (XSNoCTop.scala)

`src/main/scala/top/XSNoCTop.scala` -- line 470

`XSNoCTop` 是基于 NoC（Network-on-Chip）的 SoC 顶层，特征如下：
- 继承自 `BaseXSSoc`，混入多个 trait：`HasXSTile`, `HasSeperatedBusOpt`, `HasIMSIC`, `HasTraceIO`
- 单核心设计（仅一个 `XSTileWrap`）
- 使用 CHI（Coherent Hub Interface）协议进行核间和缓存一致性通信
- 支持分离总线（Separated Bus）：TileLink 或 AXI4
- 支持 IMSIC（Incoming MSI Controller）
- 支持异步时钟域：`noc_clock`、`soc_clock`、`clint_clock` 分离
- 支持低功耗状态：WFI clock gating、power down 序列
- `desiredName` 覆盖为 `"XSTop"`

### 8.5 XSNoCDiffTop (XSNoCTop.scala)

`src/main/scala/top/XSNoCTop.scala` -- line 500

继承自 `XSNoCTop`，混入 `HasDiffTestInterfaces`。额外功能：
- `connectTopIOs` 中收集 XSLog 输出，通过 `XSLog.collect(timer, logEnable, clean, dump)` 统一处理
- 调用 `XSNoCDiffTopChecker()` 生成一个 SystemVerilog wrapper (`XSDiffTopChecker.sv`)

### 8.6 Config Variants

`src/main/scala/top/Configs.scala` 定义了丰富的配置类层次：

| Config 类 | 特征 | 用途 |
|-----------|------|------|
| `BaseConfig(n)` | 基础配置，设置 n 个 tile 的参数 | 所有 Config 的基类 |
| `DefaultConfig(n)` | 完整配置：16MB L3, 2MB L2, 64KB L1D | 默认生产配置 |
| `MinimalConfig(n)` | 精简配置：4MB L3, 128KB L2, 32KB L1D | 面积受限场景 |
| `XSNoCTopConfig(n)` | DefaultConfig + `UseXSNoCTop=true` | NoC 拓扑 |
| `XSNoCDiffTopConfig(n)` | DefaultConfig + NoC + DiffTop | NoC + Difftest |
| `FpgaDefaultConfig(n)` | FPGA 优化配置 | FPGA 综合 |
| `FpgaDiffDefaultConfig(n)` | FPGA + Difftest | FPGA 验证 |
| `FrontendDebugConfig(n)` | 启用所有前端 trace | 前端调试 |
| `BackendV2Config(n)` | Backend V2 参数 | 后端 V2 实验 |
| `MinimalL3DebugConfig(n)` | Minimal + L3 debug | L3 调试 |
| `CVMConfig(n)` | CVM (Confidential VM) 加密配置 | 机密计算 |
| `WithFuzzer` | Fuzzer 配置（禁用性能计数器） | 模糊测试 |
| `WithL3DebugConfig` | 256KB L3 + 64KB L2 | L3 快速调试 |

---

## 9. Complete Pipeline Flow Summary

从用户执行 `make verilog` 到生成 Verilog 的完整流程：

```
1. Scala 进程启动
   |
2. TopMain.main(args)
   |-- ArgParser.parse(args)
   |   |-- 构建 Parameters (CDE config)
   |   |-- 收集 firrtlOpts
   |   |-- 收集 firtoolOpts
   |
3. 根据配置选择 Top 模块
   |-- XSTop / XSNoCTop / XSNoCDiffTop / XSTileDiffTop
   |-- DisableMonitors 包装 (可能)
   |-- DifftestModule.top 包装 (DiffTop 变体)
   |
4. Generator.execute(firrtlOpts, module, firtoolOpts)
   |-- FirtoolOption annotations
   |-- ChiselGeneratorAnnotation(() => module)
   |-- 创建 XiangShanStage 实例
   |-- 调用 XiangShanStage.execute(args, annotations)
   |
5. XiangShanStage.run(annotations)
   |-- 构建 PhaseManager with 8 target phases
   |
6. Phase 1: AddImplicitOutputFile (chisel3)
   |-- 根据模块名生成默认输出文件路径
   |
7. Phase 2: AddImplicitOutputAnnotationFile (chisel3)
   |-- 生成 .anno 输出文件路径
   |
8. Phase 3: AddSerializationAnnotations (chisel3)
   |-- 添加序列化相关 annotations
   |
9. [隐式] Elaborate (chisel3)
   |-- 执行 ChiselModule.apply，实例化 LazyModule 树
   |-- 执行 lazy val module，生成 Chisel Circuit
   |-- 此时所有 Printf 包含的是模块 toString 而非 pathName
   |
10. Phase 4: PrintModuleName (XiangShan custom)
    |-- 遍历 Chisel Circuit
    |-- 对每个 Component 的 Command 树做递归 mapCommand
    |-- When/LayerBlock/Placeholder 递归展开
    |-- Printf 中的模块名 placeholder 替换为 pathName
    |
11. Phase 5: Convert (chisel3)
    |-- 将 Chisel Circuit (ElaboratedCircuit) 转为 FIRRTL Circuit
    |-- Printf 变为 Verilog $display 语句
    |
12. Phase 6: AddDedupGroupAnnotations (chisel3)
    |-- 添加 dedup group annotations
    |
13. Phase 7: AddImplicitOutputFile (circt)
    |-- CIRCT 阶段的输出文件
    |
14. Phase 8: CIRCT (circt.stage)
    |-- 调用 firtool (CIRCT compiler)
    |-- FIRRTL -> MLIR -> Verilog
    |-- 应用 firtoolOpts 中的所有选项
    |
15. 输出 Verilog 文件
    |-- FileRegisters.write -> DTS, JSON, graphML, plusArgs
```

---

## 10. Key Design Decisions and Trade-offs

### 10.1 Chisel IR vs FIRRTL IR Transform

XiangShan 选择在 Chisel IR 阶段（而非 FIRRTL 阶段）执行 Printf 模块名替换。这是一个**有意为之的设计决策**：

- **优势**：Chisel IR 中的 `Printf` 结构保留了完整的 `Printable` 语义，模块的 `pathName` 可以直接访问
- **劣势**：需要自己实现 Chisel IR 的 traversal 函数（`ChiselCircuitHelpers`），因为 Chisel 未对外暴露标准的 IR traversal API
- **替代方案**：如果在 FIRRTL 阶段操作，Printf 已变为字符串拼接，模块名解析需要复杂的正则匹配

### 10.2 Manual PhaseManager

手动构建 PhaseManager 而非使用 `ChiselStage.execute` 默认 pipeline 的原因：

- 标准 pipeline 包含完整的 FIRRTL 优化 pass（constant propagation, dead code elimination 等），而 XiangShan 使用 CIRCT 后端，这些 pass 由 firtool 的 MLIR pass 管线替代
- 手动控制允许精确插入 `PrintModuleName` 到正确的位置
- 减少了不必要的 Phase 执行，加速 elaboration

### 10.3 Package Placement

`ChiselCircuitHelpers` 放在 `chisel3.internal.firrtl.xiangshan` package 中，而非 `xiangshan.transforms`。这是因为它需要访问 Chisel 内部 API（`chisel3.internal.firrtl.ir._` 中的 `Command`, `Block`, `Component` 等类），这些 API 在 `chisel3` 包内具有 package-private 可见性。放置在 `chisel3.internal` 子包中可以合法访问这些内部 API。

### 10.4 FIRTool Options Passing

firtool 选项通过两种途径传递：
1. `--firtool-opt <option>` 命令行参数 -> `ArgParser` 收集 -> `Generator.execute` 转换为 `FirtoolOption` annotation
2. `DifftestModule.parseArgs` 额外返回的 firtool options

这些最终作为 annotations 传递给 `XiangShanStage.execute`，并在 CIRCT Phase 中传递给 firtool 进程。

---

## 11. Source File Locations Summary

| 文件 | 路径 | 核心内容 |
|------|------|---------|
| XiangShanStage | `src/main/scala/top/XiangShanStage.scala` | 自定义 ChiselStage + 8-phase PhaseManager |
| PrintModuleName | `src/main/scala/xiangshan/transforms/PrintModuleName.scala` | Printf 模块名替换 Phase |
| ChiselCircuitHelpers | `src/main/scala/xiangshan/transforms/ChiselCircuitHelpers.scala` | Chisel IR map/traverse 工具 |
| Generator | `src/main/scala/top/Generator.scala` | Generator.execute 封装 |
| TopMain | `src/main/scala/top/Top.scala` (line 408-447) | 应用入口，Config -> Top -> Verilog |
| ArgParser | `src/main/scala/top/ArgParser.scala` | 命令行参数解析 |
| YamlParser | `src/main/scala/top/YamlParser.scala` | YAML 配置文件解析 |
| Configs | `src/main/scala/top/Configs.scala` | 所有 Config 变体定义 |
| XSTop | `src/main/scala/top/Top.scala` (line 84-394) | 传统 Diplomacy 顶层 |
| XSNoCTop | `src/main/scala/top/XSNoCTop.scala` (line 470-498) | NoC/CHI 顶层 |
| XSNoCDiffTop | `src/main/scala/top/XSNoCTop.scala` (line 500-522) | NoC + Difftest 顶层 |
| XSTileDiffTop | `src/main/scala/top/Top.scala` (line 396-406) | TileDiff 顶层 |
| LogUtils (replaceFIRStr) | `utility/src/main/scala/utility/LogUtils.scala` (line 165-168) | 模块名字符串替换 |

---

## 12. Dependency on Chisel Internal APIs

整个 elaboration pipeline 依赖于多个 Chisel 内部 API，这些 API 不保证在 Chisel 版本间稳定：

1. **`chisel3.internal.firrtl.ir`** -- `Circuit`, `Component`, `DefModule`, `DefClass`, `Command`, `Block`, `When`, `LayerBlock`, `Placeholder`, `Printf`
2. **`chisel3.stage.ChiselCircuitAnnotation`** -- 标准 ChiselStage annotation
3. **`chisel3.stage.phases.Elaborate`** -- Chisel elaboration Phase
4. **`firrtl.options.PhaseManager`** -- FIRRTL options 框架的 Phase 调度器
5. **`circt.stage.phases.CIRCT`** -- CIRCT 编译器 Phase

这些依赖在 XiangShan 代码中通过 `@scala.annotation.nowarn("msg=All APIs in package firrtl are deprecated")` 抑制了弃用警告，表明团队清楚这些 API 的生命周期风险，但为了自定义 pipeline 功能而接受了这一权衡。

---

## 13. Integration with Build System

XiangShan 的构建通过 Makefile 调用 `sbt` 或 `mill`，最终触发 `TopMain` 的 main 方法。典型的构建命令类似于：

```bash
# Default XSTop
make verilog SIM_ARGS="--config DefaultConfig"

# XSNoCTop with difftest
make verilog SIM_ARGS="--config XSNoCDiffTopConfig"

# Minimal config for FPGA
make verilog SIM_ARGS="--config FpgaDefaultConfig --fpga-platform"

# Custom firtool options
make verilog SIM_ARGS="--firtool-opt '--lowering-options=disallowLocalVariables'"
```

构建系统将 `SIM_ARGS` 传递给 JVM 作为 `args`，最终到达 `TopMain.main(args)` -> `ArgParser.parse(args)`。

---

## 14. Conclusion

XiangShan 的 Chisel Elaboration Pipeline 展示了一个高性能处理器项目如何在标准 EDA 工具链基础上构建自定义 elaboration 流程。核心创新在于：

1. **自建 PhaseManager** 精确控制 8 个编译阶段的执行顺序
2. **Chisel IR 阶段的 Printf transform** 解决了仿真日志中的模块名层次问题
3. **完整的 Chisel IR traversal 框架** 处理 When/LayerBlock/Placeholder 递归结构
4. **统一的 Generator 入口** 隐藏了复杂的 pipeline 细节
5. **丰富的 Config 体系** 支持从最小面积到完整 SoC 的灵活配置

这套 pipeline 的设计体现了硬件设计语言与编译器基础设施的深度集成，是 Chisel/CIRCT 生态中自定义 elaboration 流程的一个典型范例。
