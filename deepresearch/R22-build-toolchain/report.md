# XiangShan 构建系统与工具链研究报告

## 1. 概述

XiangShan 是由中科院计算所和北京开源芯片研究院（BOSC）主导开发的高性能 RISC-V 处理器核，采用 Chisel (Scala) 语言编写硬件描述，并通过 FIRRTL 编译器流水线生成 SystemVerilog。其构建系统是整个项目的核心基础设施，负责从 Scala/Chisel 源码到可综合 RTL 的完整转换流程，并集成了仿真、调试、代码风格检查、Docker 容器化开发等多个环节。

本报告从构建系统架构、依赖关系、FIRRTL 变换流水线、Makefile 目标体系、开发工作流、脚本工具、Docker 支持、代码规范检查、Scala 宏机制等方面对 XiangShan 的构建系统进行全面深入的研究分析。

---

## 2. 构建系统架构

### 2.1 构建工具演进

XiangShan 项目历史上使用 SBT (Scala Build Tool) 作为构建系统，但当前版本已完全迁移到 **Mill** 构建工具。Mill 是一个基于 Scala 的现代化构建工具，以其简洁的 DSL 语法和增量构建能力著称。当前使用的 Mill 版本为 **0.12.15**（由 `.mill-version` 文件指定）。

项目根目录下不再保留 `build.sc` 文件，所有构建配置集中于 `build.mill`。虽然 `project/build.properties` 中仍保留了 `sbt.version=1.2.6`，但这仅作为历史遗留文件，实际构建流程完全由 Mill 驱动。

### 2.2 build.mill 核心结构

`build.mill` 文件定义了整个项目的模块化构建结构，其核心是一个名为 `ChiselModule` 的 trait，作为所有 Chisel 硬件模块的构建基础：

```scala
trait ChiselModule extends SbtModule with ScalafmtModule {
  def scalaVersion: T[String] = "2.13.17"
  def scalacOptions = Seq("-language:reflectiveCalls", "-Ymacro-annotations", "-Ytasty-reader")
  def ivyDeps: T[Agg[Dep]] = Agg(ivy"org.chipsalliance::chisel:7.3.0")
  def scalacPluginIvyDeps: T[Agg[Dep]] = Agg(ivy"org.chipsalliance:::chisel-plugin:7.3.0")
}
```

该 trait 继承自 `SbtModule`（兼容 SBT 源码目录结构）和 `ScalafmtModule`（集成 Scalafmt 格式化工具），并配置了：

- **Scala 版本**：2.13.17
- **Chisel 版本**：7.3.0（Chisel 是基于 Scala 的硬件构造语言）
- **Scala 编译器插件**：chisel-plugin:7.3.0（提供宏展开支持）
- **编译选项**：`-language:reflectiveCalls`（启用反射调用）、`-Ymacro-annotations`（启用宏注解）、`-Ytasty-reader`（启用 Tasty 格式读取）

### 2.3 模块依赖结构

`build.mill` 定义了多个 Mill 模块，形成清晰的层次化依赖关系：

| 模块名 | 类型 | 说明 |
|--------|------|------|
| `rocket-chip` | ChiselModule | RISC-V Rocket 芯片基础设施（含 macros、hardfloat、cde 子模块） |
| `utility` | ChiselModule | 工具库，依赖 rocket-chip |
| `yunsuan` | ChiselModule | 运算单元库 |
| `XSCache` | ChiselModule | 缓存子系统（路径: ./XSCache），依赖 rocket-chip、utility、openNCB |
| `openNCB` | ChiselModule | NCB（Network Connection Bridge）接口（路径: ./XSCache/OpenNCB），依赖 rocket-chip |
| `difftest` | ChiselModule | 差分测试框架 |
| `ChiselAIA` | ChiselModule | 高级中断架构（AIA）实现，依赖 rocket-chip、utility |
| `ChiselIOPMP` | ChiselModule | IO 物理内存保护（PMP）实现，依赖 rocket-chip、utility |
| `macros` | ScalaModule | Scala 宏定义库（仅依赖 scala-reflect） |
| `xiangshan` | ChiselModule | XiangShan 主模块（路径: 项目根目录） |

`xiangshan` 主模块依赖所有其他模块，构成完整的处理器 SoC：

```scala
object xiangshan extends ChiselModule {
  def moduleDeps = Seq(
    `rocket-chip`, difftest, XSCache, yunsuan,
    utility, ChiselAIA, ChiselIOPMP, macros
  )
}
```

### 2.4 JVM 资源配置

主模块配置了较大的 JVM 内存参数以应对大型 SoC 的 elaboration 开销：

```scala
def forkArgs = Seq(
  s"-Xmx${sys.props.getOrElse("jvm-xmx", "40G")}",
  s"-Xss${sys.props.getOrElse("jvm-xss", "256m")}"
)
```

默认 JVM 堆内存为 40GB，线程栈大小为 256MB，可通过系统属性 `jvm-xmx` 和 `jvm-xss` 自定义。

### 2.5 版本管理

项目使用 `de.tobiasroeser.mill.vcs.version` 插件（版本 0.4.0）基于 Git 状态自动生成版本号：

- **Release 版本**：匹配 `[Rr]elease.*` 标签时，生成 `KunminghuV3-Release-<date>` 格式
- **开发版本**：`KunminghuV3-dev (<user>@<host>) # <datetime>`
- **Git 状态信息**：SHA 和 dirty 标志通过 `Task.Input` 动态获取

版本信息、Git 状态和 gitmodules 状态被打包为 Scala 资源（resources），供运行时读取。此外，difftest 源码和 ready-to-run 二进制工具也被打包为资源：

```scala
val ready_to_run = Seq(
  "riscv64-nemu-interpreter-dual-so",
  "riscv64-nemu-interpreter-so",
  "riscv64-spike-so"
)
```

---

## 3. 依赖关系图

### 3.1 Git Submodule 依赖

XiangShan 通过 Git submodule 机制管理外部依赖，共包含 8 个子模块：

| 子模块 | 路径 | 来源仓库 | 说明 |
|--------|------|----------|------|
| rocket-chip | `rocket-chip/` | OpenXiangShan/rocket-chip | RISC-V Rocket 芯片基础设施 |
| difftest | `difftest/` | OpenXiangShan/difftest | 差分测试框架 |
| ready-to-run | `ready-to-run/` | OpenXiangShan/ready-to-run | 预编译的测试参考二进制 |
| utility | `utility/` | OpenXiangShan/Utility | 通用工具库 |
| yunsuan | `yunsuan/` | OpenXiangShan/YunSuan | 运算单元库 |
| ChiselAIA | `ChiselAIA/` | OpenXiangShan/ChiselAIA | RISC-V 高级中断架构 |
| ChiselIOPMP | `ChiselIOPMP/` | OpenXiangShan/ChiselIOPMP | IO 物理内存保护 |
| XSCache | `XSCache/` | OpenXiangShan/XSCache | 缓存子系统（含 OpenNCB） |

`rocket-chip` 自身还包含嵌套子模块 `cde`（Context-Dependent Environments）和 `hardfloat`（浮点单元）。

### 3.2 模块间依赖关系图

```
                          xiangshan (主模块)
                        /   |    |    \   \    \       \
                       /    |    |     \   \    \       \
              rocket-chip  difftest yunsuan utility ChiselAIA ChiselIOPMP macros
              /   |   \
             /    |    \
         macros hardfloat cde
                        |
                       XSCache
                      /      \
              rocket-chip  utility
                           |
                      openNCB (-> rocket-chip)
```

简化后的依赖链路：

```
macros --> (无外部依赖)
hardfloat --> (无外部依赖)
cde --> (无外部依赖)
rocket-chip --> macros, hardfloat, cde
utility --> rocket-chip
yunsuan --> (无外部依赖)
openNCB --> rocket-chip
XSCache --> rocket-chip, utility, openNCB
ChiselAIA --> rocket-chip, utility
ChiselIOPMP --> rocket-chip, utility
difftest --> (无外部依赖)
xiangshan --> rocket-chip, difftest, XSCache, yunsuan, utility, ChiselAIA, ChiselIOPMP, macros
```

### 3.3 Maven/Ivy 外部依赖

除了 Git submodule，项目还通过 Ivy 仓库引入外部依赖：

| 依赖包 | 版本 | 用途 |
|--------|------|------|
| `org.chipsalliance::chisel` | 7.3.0 | Chisel 硬件构造语言核心 |
| `org.chipsalliance:::chisel-plugin` | 7.3.0 | Chisel 编译器插件 |
| `org.chipsalliance::firtool-resolver` | 2.0.1 | FIRRTL 工具链自动解析下载 |
| `com.lihaoyi::mainargs` | 0.7.7 | 命令行参数解析 |
| `org.json4s::json4s-jackson` | 4.0.7 | JSON 解析 |
| `com.lihaoyi::sourcecode` | 0.4.4 | 源码位置信息 |
| `org.scalatest::scalatest` | 3.2.19 | Scala 测试框架 |
| `io.circe::circe-yaml` | 0.16.1 | YAML 配置解析 |
| `io.circe::circe-generic-extras` | 0.14.4 | Circe 泛型序列化扩展 |
| `de.tobiasroeser.mill.vcs.version` | 0.4.0 | 基于 VCS 的版本号生成 |

---

## 4. Chisel-to-Verilog 转换流水线

### 4.1 流水线架构

XiangShan 的硬件描述代码经过以下多阶段流水线转换为可综合的 SystemVerilog：

```
Scala/Chisel 源码
       |
       v
[Scala Compiler + Chisel Plugin] -- 宏展开、类型检查
       |
       v
[Chisel Elaboration] -- ChiselCircuit 生成
       |
       v
[XiangShanStage (自定义 PhaseManager)]
  |-- AddImplicitOutputFile
  |-- AddImplicitOutputAnnotationFile
  |-- AddSerializationAnnotations
  |-- PrintModuleName (自定义 FIRRTL transform)
  |-- Convert (Chisel IR -> FIRRTL IR)
  |-- AddDedupGroupAnnotations
  |-- AddImplicitOutputFile (CIRCT)
  |-- CIRCT (CIRCT compiler, 调用 firtool)
       |
       v
[FIRRTL IR] -- FIR 高级中间表示
       |
       v
[firtool] -- CIRCT/FIRRTL 工具链
  |-- -O=release (优化级别)
  |-- --disable-annotation-unknown
  |-- --lowering-options=explicitBitcast,disallowLocalVariables,...
  |-- --split-verilog (输出分割为多个文件)
  |-- --dump-fir (输出 FIR 文件)
       |
       v
SystemVerilog RTL (build/rtl/ 目录)
```

### 4.2 XiangShanStage -- 自定义编译流水线

`XiangShanStage`（位于 `src/main/scala/top/XiangShanStage.scala`）是项目自定义的 Chisel 编译阶段，继承自 `circt.stage.ChiselStage`，通过 `firrtl.options.PhaseManager` 编排多个转换阶段：

```scala
class XiangShanStage extends ChiselStage {
  override def run(annotations: AnnotationSeq): AnnotationSeq = {
    val pm = new firrtl.options.PhaseManager(
      targets = Seq(
        Dependency[chisel3.stage.phases.AddImplicitOutputFile],
        Dependency[chisel3.stage.phases.AddImplicitOutputAnnotationFile],
        Dependency[chisel3.stage.phases.AddSerializationAnnotations],
        Dependency[chisel3.stage.phases.xiangshan.PrintModuleName],  // 自定义
        Dependency[chisel3.stage.phases.Convert],
        Dependency[chisel3.stage.phases.AddDedupGroupAnnotations],
        Dependency[circt.stage.phases.AddImplicitOutputFile],
        Dependency[circt.stage.phases.CIRCT]
      ),
      ...
    )
    pm.transform(annotations)
  }
}
```

关键阶段说明：

1. **AddImplicitOutputFile**：根据顶层模块名自动添加输出文件名
2. **AddImplicitOutputAnnotationFile**：自动设置注解输出文件
3. **AddSerializationAnnotations**：添加序列化注解
4. **PrintModuleName**：**XiangShan 自定义阶段**，处理 Printf 中的模块名替换
5. **Convert**：将 Chisel IR 转换为 FIRRTL IR
6. **AddDedupGroupAnnotations**：添加去重组注解，优化生成的 Verilog 体积
7. **CIRCT**：调用 CIRCT/firtool 编译器，将 FIRRTL IR 转换为 SystemVerilog

### 4.3 Generator -- 入口封装

`Generator`（`src/main/scala/top/Generator.scala`）是顶层生成器的封装，统一处理 firtool 选项并调用 `XiangShanStage`：

```scala
object Generator {
  def execute(args: Array[String], mod: => chisel3.RawModule, firtoolOpts: Array[String]) = {
    val annotations = firtoolOpts.map(FirtoolOption.apply).toSeq
    (new XiangShanStage).execute(args, ChiselGeneratorAnnotation(() => mod) +: annotations)
  }
}
```

### 4.4 TopMain -- 顶层入口

`TopMain`（位于 `src/main/scala/top/Top.scala`）是 Verilog 生成的最终入口，根据配置选择不同的顶层模块：

- **XSNoCDiffTop**：带 NOC（Network-on-Chip）接口和差分测试的顶层
- **XSTileDiffTop**：带差分测试的 Tile 顶层
- **XSNoCTop**：带 NOC 接口的顶层
- **XSTop**：标准 AXI4 接口的顶层

最终 RTL 输出到 `build/rtl/` 目录，并附加 Git commit 信息和 diff 到生成的 SystemVerilog 文件头部。

### 4.5 Firtool 参数配置

Makefile 中定义了 firtool 的编译参数（`MFC_ARGS`）：

```makefile
MFC_ARGS = --target $(CHISEL_TARGET) \
           --firtool-opt "-O=release --disable-annotation-unknown \
           --lowering-options=explicitBitcast,disallowLocalVariables,\
           disallowPortDeclSharing,locationInfoStyle=none"
```

关键选项：
- `--target systemverilog`：目标语言为 SystemVerilog
- `-O=release`：优化级别为 release（最大优化）
- `--split-verilog`：将每个模块输出为单独的 Verilog 文件
- `--dump-fir`：同时输出 FIR 中间表示文件
- `--disable-annotation-unknown`：忽略未知注解而不报错
- `--lowering-options`：控制 FIRRTL 到 Verilog 的 lowering 行为

---

## 5. FIRRTL Transform 详解

### 5.1 PrintModuleName Transform

`PrintModuleName`（位于 `src/main/scala/xiangshan/transforms/PrintModuleName.scala`）是 XiangShan 自定义的 FIRRTL 变换，处于 Chisel elaboration 之后、FIRRTL IR 转换之前的阶段。

其核心功能是遍历 Chisel 内部 IR 中的所有 `Printf` 命令，使用 `utility.XSLog.replaceFIRStr` 替换格式化字符串中的模块名占位符，使得仿真时的 printf 输出包含正确的模块层级信息。

```scala
class PrintModuleName extends Phase {
  override def prerequisites = Seq(Dependency[Elaborate])
  
  def transform(annotations: AnnotationSeq): AnnotationSeq = {
    def onCommand(c: Command): Command = c match {
      case Printf(id, sourceInfo, filename, clock, pable) =>
        val (fmt, data) = pable.unpack
        val newPable = Printable.pack(utility.XSLog.replaceFIRStr(fmt), data:_*)
        Printf(id, sourceInfo, filename, clock, newPable)
      case other: Command => other.mapCommand(onCommand)
    }
    annotations.flatMap {
      case a: ChiselCircuitAnnotation =>
        Some(ChiselCircuitAnnotation(ElaboratedCircuit(
          a.elaboratedCircuit._circuit.mapComponent(c => c.mapCommand(onCommand)), Seq()
        )))
      case a => Some(a)
    }
  }
}
```

该变换的依赖关系：必须在 `Elaborate` 阶段之后执行（`prerequisites = Seq(Dependency[Elaborate])`），在 `Convert` 阶段（Chisel IR -> FIRRTL IR）之前执行。

### 5.2 ChiselCircuitHelpers

`ChiselCircuitHelpers`（位于 `src/main/scala/xiangshan/transforms/ChiselCircuitHelpers.scala`）提供了对 Chisel 内部 IR 的隐式类扩展，支持对 `Circuit`、`Component`、`Command`、`Block` 等数据结构进行递归的 `mapCommand` 和 `foreachCommand` 操作。

这些辅助方法使得 `PrintModuleName` 能够深入遍历包含 `When`（条件分支）、`LayerBlock`（分层块）、`Placeholder` 等复杂控制结构的命令树，确保所有 `Printf` 语句都能被正确处理。

具体支持的命令类型包括：
- `DefModule`：标准模块定义
- `DefClass`：Scala class 定义
- `When`：条件分支（包含 ifRegion 和 elseRegion）
- `LayerBlock`：分层块（Chisel 3.6+ 的 layer 机制）
- `Placeholder`：占位符命令序列

### 5.3 FIRRTL Transform 在流水线中的位置

完整的 XiangShan 编译流水线中，自定义 transform 的执行顺序为：

1. `AddImplicitOutputFile` -- 自动设置输出文件路径
2. `AddImplicitOutputAnnotationFile` -- 自动设置注解输出文件
3. `AddSerializationAnnotations` -- 添加序列化注解
4. **`PrintModuleName`** -- 替换 Printf 中的模块名（XiangShan 自定义）
5. `Convert` -- Chisel IR -> FIRRTL IR
6. `AddDedupGroupAnnotations` -- 添加模块去重注解
7. `CIRCT` -- FIRRTL IR -> SystemVerilog（通过 firtool）

---

## 6. Makefile 目标体系

### 6.1 顶层 Makefile 架构

顶层 `Makefile` 通过 `include` 指令整合了多个 Makefile 片段：

```makefile
include scripts/Makefile.docker     # Docker 容器化支持
include scripts/Makefile.pdb        # PDB (Post-silicon Debug) 支持
include Makefile.test               # 测试目标
include src/main/scala/device/standalone/standalone_device.mk  # 独立设备生成
```

### 6.2 核心 Makefile 变量

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `BUILD_DIR` | `./build` | 构建输出根目录 |
| `RTL_DIR` | `$(BUILD_DIR)/rtl` | RTL 输出目录 |
| `CONFIG` | `DefaultConfig` | Chisel 配置类名 |
| `NUM_CORES` | `1` | 处理器核数量 |
| `ISSUE` | `E.b` | CHI 协议版本 |
| `CHISEL_TARGET` | `systemverilog` | 目标硬件语言 |
| `JVM_XMX` | `40G` | JVM 最大堆内存 |
| `JVM_XSS` | `256m` | JVM 线程栈大小 |
| `RTL_SUFFIX` | `sv` | RTL 文件后缀 |

### 6.3 主要 Makefile 目标

#### 编译与生成类目标

| 目标 | 说明 |
|------|------|
| `verilog` (默认目标) | 生成顶层 XSTop 的 Verilog RTL |
| `sim-verilog` | 生成仿真顶层 SimTop 的 Verilog RTL |
| `comp` | 仅编译 Scala 代码（不生成 Verilog） |
| `jar` | 生成 xiangshan 的 assembly jar |
| `test-jar` | 生成测试用 jar |
| `help` | 打印 TopMain 帮助信息 |
| `version` | 打印版本信息 |

#### 仿真类目标

| 目标 | 说明 |
|------|------|
| `emu-mk` | 使用 Verilator 编译仿真器 Makefile |
| `emu` | 编译 Verilator 仿真器 |
| `gsim` | 编译 Gate-level 仿真器 |
| `simv` | 编译 VCS 仿真器 |
| `simv-run` | 运行 VCS 仿真 |
| `xsim` | 编译 GalaxSim/XSIM 仿真器 |
| `xsim-run` | 运行 GalaxSim 仿真 |
| `pldm-build` | 编译 Palladium 仿真器 |
| `pldm-run` | 运行 Palladium 仿真 |
| `pldm-debug` | Palladium 调试模式 |

#### 开发工具类目标

| 目标 | 说明 |
|------|------|
| `init` | 初始化所有 Git submodule |
| `init-force` | 强制重新初始化 submodule（CI 用） |
| `bump` | 更新所有 submodule 到 master 最新 |
| `deps` | 下载并缓存所有依赖（含 firtool） |
| `bsp` | 生成 BSP (Build Server Protocol) 配置，支持 IDE 集成 |
| `idea` | 生成 IntelliJ IDEA 项目文件 |
| `check-format` | 检查代码格式 |
| `reformat` | 自动格式化代码 |
| `clean` | 清理构建产物 |
| `image` | 构建 Docker 开发镜像 |
| `pull-image` | 拉取 Docker 镜像 |
| `sh` | 在 Docker 容器中启动交互式 shell |

### 6.4 测试目标 (Makefile.test)

`Makefile.test` 提供了额外的测试相关目标：

| 目标 | 说明 |
|------|------|
| `test` | 运行所有 chiselTest 测试用例 |
| `test-DecodeUnit` | 仅运行 DecodeUnitTest |
| `verilog-decode` | 仅生成 DecodeUnit 模块的 Verilog |

### 6.5 配置参数化

Makefile 通过命令行参数支持高度灵活的配置：

**核心配置参数：**
- `CONFIG=<ConfigClass>`：选择 Chisel 配置类（如 DefaultConfig、MinimalConfig）
- `NUM_CORES=<N>`：处理器核数量
- `ISSUE=<version>`：CHI 协议版本（B、C、E.b）
- `XSTOP_PREFIX=<prefix>`：模块名前缀

**功能开关：**
- `FPGA=1`：FPGA 平台模式（关闭 difftest，优化综合）
- `RELEASE=1`：Release 模式
- `DFX=0/1`：启用/禁用 Design-for-Test
- `ENABLE_NS=1`：启用 Non-Secure 访问
- `DISABLE_XMR=1`：禁用 XMR（Cross-Module Reference）
- `WITH_DRAMSIM3=1`：启用 DRAMsim3 协同仿真
- `WITH_CHISELDB=1`：启用 Chisel 数据库
- `WITH_ROLLINGDB=1`：启用 Rolling 数据库
- `WITH_CONSTANTIN=1`：启用 Constantin 动态参数调整
- `ENABLE_SIMFRONTEND=1`：启用仿真前端
- `GSIM=1`：Gate-level 仿真模式

**缓存配置：**
- `L2_CACHE_SIZE=<KB>`：L2 缓存大小
- `L3_CACHE_SIZE=<KB>`：L3 缓存大小

### 6.6 Release 与 Debug 模式的差异

```makefile
# Release 模式
RELEASE_ARGS += --fpga-platform --reset-gen \
  --firtool-opt --ignore-read-enable-mem \
  --firtool-opt "--default-layer-specialization=disable"

# Debug 模式
DEBUG_ARGS += --enable-difftest \
  --firtool-opt "--default-layer-specialization=enable"
```

Release 模式关闭 difftest、启用 reset-gen、禁用 layer 优化；Debug 模式启用 difftest 用于功能验证。

### 6.7 生成后处理

Verilog 生成后会进行一系列后处理：

1. **Git 信息注入**：将 git log 和 git diff 以注释形式附加到 RTL 文件头部
2. **仿真适配**：
   - PLDM 模式：`$fatal` 替换为 `$finish`，移除 `ifndef SYNTHESIS` 条件编译
   - XPROP 模式：`$fatal` 替换为 `assert(1'b0)`
   - Debug 模式：`$fatal` 替换为 `xs_assert_v2` 宏
   - 通用：`$error(` 替换为 `$fwrite(32'h80000002, `

---

## 7. 配置系统

### 7.1 CDE 配置框架

XiangShan 使用 Rocket-Chip 的 CDE（Context-Dependent Environments）配置框架。所有配置通过 Scala 类继承体系组织，核心配置类层次：

```
BaseConfig (基础配置)
  |
  +-- MinimalConfig (最小配置, 32KB L1D, 128KB L2, 4MB L3)
  |     |
  |     +-- XSNoCTopMinimalConfig
  |     +-- XSNoCDiffTopMinimalConfig
  |     +-- FpgaDiffMinimalConfig
  |
  +-- DefaultConfig (默认配置, 64KB L1D, 2MB L2, 16MB L3)
        |
        +-- XSNoCTopConfig
        +-- FpgaDefaultConfig
        +-- FpgaDiffDefaultConfig
        +-- FrontendDebugConfig
        +-- BackendV2Config
        +-- CVMConfig
        +-- CVMTestConfig
```

### 7.2 YAML 配置支持

除命令行参数外，XiangShan 支持通过 YAML 文件进行更精细的配置（通过 `--yaml-config` 参数）。`YamlParser`（`src/main/scala/top/YamlParser.scala`）使用 Circe 库解析 YAML 配置，支持的配置项包括：

- 基础配置类名
- 物理内存范围（PmemRanges）
- PMA 配置
- CHI 异步桥配置
- L2/L3 缓存参数
- 调试模块参数
- 总线分离配置
- IMSIC 总线类型
- CHI 协议版本
- DFX/SRAM CTL 选项
- 模块前缀等

### 7.3 命令行参数解析

`ArgParser`（`src/main/scala/top/ArgParser.scala`）支持丰富的命令行参数，采用尾递归函数实现参数解析，最终输出 `(Parameters, firrtlOpts, firtoolOpts)` 三元组。

---

## 8. Docker 支持

### 8.1 Docker 镜像架构

XiangShan 的 Docker 支持采用分层镜像策略：

1. **基础镜像**：`ghcr.io/openxiangshan/xs-env:latest` -- 包含完整的开发环境（Scala、FIRRTL 工具链、Verilator 等）
2. **应用镜像**：`ghcr.io/openxiangshan/xsdev:<branch>` -- 基于分支名标签化，包含 XiangShan 项目和预下载的依赖

### 8.2 Dockerfile 分析

```dockerfile
FROM ghcr.io/openxiangshan/xs-env:latest
ENTRYPOINT [ "/bin/bash" ]
ENV VERILATOR=/usr/local/bin/verilator-wrap.sh
ENV LC_ALL=C.UTF-8

# 安装 Mill
COPY .mill-version /tmp
RUN cd /tmp && mill -i --version && rm -rf .mill-version out

# 设置工作目录
WORKDIR /work
VOLUME /work/out
VOLUME /work/build

# 预下载依赖
RUN --mount=type=bind,source=.,target=/work,readonly \
    --mount=type=tmpfs,destination=/tmp/.mill-out,rw <<EOF
make deps MILL_OUTPUT_DIR=/tmp/.mill-out
EOF
```

关键设计：
- 使用 `--mount=type=bind` 将源码以只读方式挂载，避免 Docker 构建时的文件复制开销
- 使用 `--mount=type=tmpfs` 提供临时的 Mill 输出目录，构建完成后自动清理
- 预执行 `make deps` 下载所有依赖（含 firtool），实现依赖预热

### 8.3 Docker 环境切换机制

`Makefile.docker` 实现了智能的环境切换：当检测到 Docker 镜像可用但当前不在 Docker 环境中时，自动将构建任务转发到 Docker 容器中执行：

```makefile
docker-deps = $(if $(NO_XSDEV_IMAGE)$(IN_XSDEV_DOCKER),$(1),__$(1) ...)
```

所有需要 Docker 支持的 Makefile 目标（如 `verilog`、`sim-verilog`）都通过 `$(call docker-deps,...)` 包装，实现透明的环境切换。容器以只读方式挂载源码目录，仅 `out/` 和 `build/` 目录可写。

---

## 9. 开发脚本工具

### 9.1 脚本工具概览

`scripts/` 目录包含了一系列开发辅助脚本：

| 脚本 | 语言 | 功能 |
|------|------|------|
| `parser.py` | Python | RTL 解析器，支持模块提取、SRAM 替换、filelist 生成 |
| `xiangshan.py` | Python | XiangShan 的 Python 封装，简化构建和仿真操作 |
| `constantHelper.py` | Python | Constantin 动态常量调整助手，基于遗传算法优化硬件参数 |
| `statistics.py` | Python | 构建/仿真统计数据收集 |
| `perfcct.py` | Python | 性能计数器 CCT（Cycle Count Trace）分析 |
| `rolling.py` | Python | Rolling 数据库辅助脚本 |
| `sram_size_collect.py` | Python | SRAM 容量收集工具 |
| `pdb-run.py` | Python | PDB (Post-silicon Debug) 运行脚本 |
| `bug-report.sh` | Bash | 自动生成 Bug 报告（含系统信息、仓库状态） |
| `generate_all.sh` | Bash | 批量生成所有模块的 Verilog |

### 9.2 parser.py -- RTL 解析与发布工具

`parser.py` 是功能最丰富的脚本，实现了完整的 RTL 后处理流水线：

1. **Verilog 模块解析**：使用正则表达式解析 SystemVerilog 文件，提取模块定义、IO 端口、子模块实例化信息
2. **模块提取**：支持从全量 RTL 中提取指定顶层模块及其子模块层次
3. **SRAM 配置生成**：自动识别 SRAM 黑盒模块，生成 SRAM 配置文件和 Excel 报表
4. **SRAM 替换**：将仿真用 SRAM 黑盒替换为 foundry 提供的 SRAM wrapper
5. **Filelist 生成**：为综合工具自动生成文件列表
6. **前缀处理**：支持模块名前缀的自动添加和处理
7. **Difftest 模块处理**：将 difftest 模块包裹在 `` `ifndef SYNTHESIS `` 条件编译中

### 9.3 constantHelper.py -- 动态参数优化

`constantHelper.py` 实现了基于遗传算法的硬件参数自动优化，通过 Constantin 框架在仿真运行时动态调整硬件常量：

- 输入 JSON 配置文件定义待优化参数及其范围
- 支持多种优化目标（最大化/最小化性能指标）
- 使用种群进化策略（交叉率、变异率、种群大小等可配）

### 9.4 bug-report.sh -- Bug 报告生成

自动收集系统环境信息（CPU、内存、磁盘、OS、工具链版本）、仓库状态（commit、status、submodule、diff），并打包为 tar.gz 文件，支持中英文界面。

### 9.5 脚本依赖

Python 脚本依赖（`scripts/requirements.txt`）：
- matplotlib
- numpy
- pandas
- psutil

PDB 相关脚本还有额外的依赖（`scripts/xspdb/requirements.txt`）。

---

## 10. PDB (Post-silicon Debug) 支持

### 10.1 PDB 构建流程

`Makefile.pdb` 定义了 PDB 调试工具的构建流程：

1. 编译 `pydifftest`（Python 绑定的差分测试库）
2. 使用 `picker` 工具将 RTL 和 difftest 库打包为 `libUTSimTop.so` 共享库
3. 支持 FST 波形生成、多线程仿真

### 10.2 PDB 运行

```makefile
pdb-run: check-deps
    LD_PRELOAD="$(PDB_LD_PRELOAD)" \
    PYTHONPATH="$(PDB_PYTHONPATH)" \
    python3 scripts/pdb-run.py $(PDB_ARGS) $(RUN_ARGS)
```

运行时通过 `LD_PRELOAD` 注入 `libxspcomm.so` 共享库，通过 Python 脚本控制仿真流程。

---

## 11. Scalastyle 代码规范

### 11.1 配置概览

`scalastyle-config.xml` 定义了全面的 Scala 代码规范检查规则：

**文件级规则：**
- 许可证头检查：必须包含 Mulan PSL v2 许可证头（正则匹配）
- 文件长度限制：最大 800 行
- 类型数量限制：每个文件最多 20 个类型定义
- 行长度限制：最大 120 列（与 `.scalafmt.conf` 的 `maxColumn = 120` 一致）

**类与方法规则：**
- 方法数量限制：每个类最多 30 个方法
- 参数数量限制：每个方法最多 8 个参数
- 方法长度限制：最大 50 行（不计注释）

**命名规范：**
- 类名：UpperCamelCase
- 对象名：UpperCamelCase
- 字段名：支持 pipeline 信号前缀（`sx_`、`perf_`、`debug_`）
- 方法参数：lowerCamelCase
- 方法名：允许 UpperCamelCase（用于常量定义）和运算符重载
- 包名：纯小写

**代码风格：**
- 禁止使用 Tab 缩进
- 文件末尾必须有换行
- 行末不允许多余空格
- 注释 `//` 或 `/*` 后必须有空格
- 禁止使用行末分号
- 禁止 block import（`import pkg.{abc, def}`）
- 禁止 wildcard import（`import pkg._`），但允许 `chisel3._` 和 `chisel3.util._`
- 公开方法必须有返回类型注解
- 警告 TODO 和 FIXME 注释

### 11.2 Scalafmt 配置

`.scalafmt.conf` 使用 Scalafmt 3.8.1 版本，Scala 2.13 方言：

```
version = 3.8.1
runner.dialect = scala213
maxColumn = 120
preset = defaultWithAlign
rewrite.rules = [RedundantBraces, RedundantParens, SortModifiers, Imports]
rewrite.imports.sort = scalastyle
```

仅对部分源码文件启用 Scalafmt 格式化（通过 `project.includePaths` 指定 frontend 和部分 utils 文件）。

---

## 12. Scala 宏系统

### 12.1 macros 模块

`macros` 模块（位于 `macros/` 目录）是一个纯 Scala 模块（非 Chisel），仅依赖 `scala-reflect` 库，提供编译时宏支持。

### 12.2 CSRMacros

`CSRMacros.scala` 定义了一组用于 CSR（Control and Status Register）字段定义的 Scala 宏。这些宏在编译时展开，根据 MSB/LSB 位域范围自动生成对应宽度的 CSR 字段定义：

**宏分类：**

| 宏名 | 功能 |
|------|------|
| `CSRROFieldRange` / `CSRROFieldBit` | 只读（RO）字段，支持读回调函数 |
| `CSRROFieldRangeNoFn` | 只读字段，无回调函数 |
| `CSRROFieldRangeWithReset` | 只读字段，带复位值 |
| `CSRWARLFieldRange` / `CSRWARLFieldBit` | WARL（Write Any, Read Legal）字段 |
| `CSRRWFieldRange` / `CSRRWFieldBit` | RW（Read/Write）字段 |
| `CSRRWFieldRangeWithReset` | RW 字段，带复位值 |
| `CSRRefWARLFieldRange` | 带引用的 WARL 字段 |

**工作原理：**

所有宏都标记为 `@compileTimeOnly`，在编译时由 Scala 宏系统展开。以 `CSRROFieldRange` 为例：

```scala
def CSRROFieldRange(c: Context)(msb: c.Expr[Int], lsb: c.Expr[Int], rfn: c.Tree): c.Tree = {
  CSRROFieldRangeWithReset(c)(msb, lsb, rfn, null)
}

def CSRROFieldRangeWithReset(c: Context)(msb: c.Expr[Int], lsb: c.Expr[Int], 
    rfn: c.Tree, resetVal: c.Tree): c.Tree = {
  c.parse(s"CSRDefines.CSRField${calcuWidth(c)(msb, lsb)}Bits.RO(${c.eval(msb)}, ${c.eval(lsb)}, $rfn, $resetVal)")
}
```

宏在编译时计算位域宽度（`msb - lsb + 1`），然后动态生成 `CSRDefines.CSRField<N>Bits.RO(msb, lsb, rfn, resetVal)` 形式的代码。这种设计使得 CSR 寄存器定义既简洁又类型安全。

---

## 13. 独立设备生成

### 13.1 standalone_device.mk

`standalone_device.mk` 支持独立生成 SoC 外设模块的 Verilog：

- **支持的设备**：StandAlonePLIC、StandAloneDebugModule、StandAloneSYSCNT、StandAloneCLINT
- **总线接口**：支持 TileLink (`DEVICE_TL=1`) 和 AXI4 (`DEVICE_AXI4=1`)
- **参数化**：支持 hart 数量、基地址、地址宽度、数据宽度配置

生成命令示例：
```bash
make StandAlonePLIC DEVICE_PREFIX=bosc_ DEVICE_AXI4=1 NUM_CORES=4
```

---

## 14. 脚本工具详解

### 14.1 coverage/ -- 覆盖率支持

覆盖率相关脚本位于 `scripts/coverage/` 目录，支持 FIRRTL 级别的覆盖率收集。

通过 Makefile 参数 `FIRRTL_COVER` 指定覆盖率类型（逗号分隔），自动生成对应的 `--extract-*-cover` firtool 选项。

### 14.2 top-down/ -- 性能分析

`scripts/top-down/` 提供 Top-down 微架构性能分析框架：

- `top_down.py`：Top-down 分析主程序
- `configs.py`：分析配置定义
- `draw.py`：可视化绘制
- `utils.py`：工具函数

---

## 15. 构建流程完整图示

```
+--------------------------------------------------------------------------+
|                        XiangShan 构建流程                                 |
+--------------------------------------------------------------------------+
|                                                                          |
|  [环境准备]                                                              |
|     |                                                                    |
|     +-- make init          初始化 git submodule                           |
|     +-- make deps          下载 Scala 依赖 + firtool                      |
|     +-- make image         构建 Docker 开发镜像                           |
|     +-- make bsp / idea    生成 IDE 配置                                 |
|                                                                          |
|  [编译阶段]                                                              |
|     |                                                                    |
|     +-- make comp          仅编译 Scala -> 检查类型错误                    |
|     |                                                                    |
|  [RTL 生成]                                                              |
|     |                                                                    |
|     +-- make verilog       生成 XSTop RTL (FPGA/Release)                 |
|     |   +-- mill xiangshan.runMain top.TopMain                          |
|     |       --config DefaultConfig --num-cores 1                        |
|     |       [RELEASE_ARGS + firtool options]                            |
|     |                                                                    |
|     +-- make sim-verilog   生成 SimTop RTL (仿真用)                       |
|     |   +-- mill xiangshan.test.runMain top.XiangShanSim                |
|     |       --config DefaultConfig --enable-difftest                    |
|     |       [DEBUG_ARGS + firtool options]                              |
|     |                                                                    |
|  [Scala Compiler + Chisel Plugin]                                        |
|     |                                                                    |
|  [XiangShanStage: PrintModuleName -> Convert -> CIRCT]                   |
|     |                                                                    |
|  [firtool: FIRRTL -> SystemVerilog]                                       |
|     |                                                                    |
|  [Post-processing: git info, $fatal replacement]                         |
|     |                                                                    |
|  [仿真构建]                                                              |
|     |                                                                    |
|     +-- make emu            编译 Verilator 仿真器                         |
|     +-- make simv           编译 VCS 仿真器                              |
|     +-- make xsim           编译 XSIM 仿真器                             |
|     +-- make pldm-build     编译 Palladium 仿真器                        |
|                                                                          |
|  [RTL 后处理]                                                            |
|     |                                                                    |
|     +-- parser.py           模块提取、SRAM 替换、filelist 生成            |
|                                                                          |
|  [质量保障]                                                              |
|     |                                                                    |
|     +-- make check-format   Scalastyle + Scalafmt 检查                   |
|     +-- make test           运行 chiselTest 测试                         |
|     +-- make pdb            构建 PDB 调试工具                             |
|                                                                          |
+--------------------------------------------------------------------------+
```

---

## 16. 开发工作流

### 16.1 标准开发流程

1. **环境初始化**
   ```bash
   make init           # 初始化 submodule
   make deps           # 下载依赖
   ```

2. **日常开发**
   ```bash
   make comp           # 快速编译检查
   make reformat       # 格式化代码
   ```

3. **RTL 生成与仿真**
   ```bash
   make sim-verilog    # 生成仿真 RTL
   make emu            # 编译 Verilator 仿真器
   ./build/emu -i test.bin --diff ref.so  # 运行仿真
   ```

4. **配置切换**
   ```bash
   make sim-verilog CONFIG=MinimalConfig NUM_CORES=2
   make sim-verilog YAML_CONFIG=custom.yaml
   ```

5. **RTL 发布**
   ```bash
   make verilog
   python3 scripts/parser.py XSTop --prefix bosc_ --sram-replace
   ```

### 16.2 Docker 开发流程

```bash
make image            # 构建开发镜像
make sh               # 进入 Docker 环境
# 在 Docker 容器内执行构建命令
```

或直接使用 Docker 自动切换：
```bash
# 即使不在 Docker 中，make 也会自动转发到容器内执行
make verilog          # 自动检测并切换到 Docker 环境
```

### 16.3 CI/CD 集成

- `make init-force`：强制初始化所有 submodule（确保 CI 环境一致性）
- `make test`：运行单元测试
- `make verilog`：验证 RTL 生成
- `scripts/bug-report.sh --basic`：收集基本环境信息用于 CI 失败诊断

---

## 17. 关键源文件位置索引

| 文件路径 | 说明 |
|----------|------|
| `build.mill` | Mill 构建定义（项目核心构建配置） |
| `.mill-version` | Mill 版本号 (0.12.15) |
| `.scalafmt.conf` | Scalafmt 格式化配置 |
| `scalastyle-config.xml` | Scalastyle 代码规范配置 |
| `Makefile` | 顶层 Makefile |
| `Makefile.test` | 测试相关 Makefile |
| `Dockerfile` | Docker 镜像定义 |
| `.gitmodules` | Git submodule 定义 |
| `project/build.properties` | SBT 版本信息（遗留） |
| `src/main/scala/top/Top.scala` | 顶层模块和 TopMain 入口 |
| `src/main/scala/top/Generator.scala` | Verilog 生成器封装 |
| `src/main/scala/top/XiangShanStage.scala` | 自定义 Chisel 编译阶段 |
| `src/main/scala/top/ArgParser.scala` | 命令行参数解析器 |
| `src/main/scala/top/YamlParser.scala` | YAML 配置解析器 |
| `src/main/scala/top/Configs.scala` | Chisel 配置类定义 |
| `src/main/scala/top/XSNoCTop.scala` | NOC 接口顶层模块 |
| `src/main/scala/xiangshan/transforms/PrintModuleName.scala` | Printf 模块名替换 transform |
| `src/main/scala/xiangshan/transforms/ChiselCircuitHelpers.scala` | Chisel IR 遍历辅助工具 |
| `macros/src/main/scala/CSRMacros.scala` | CSR 字段定义 Scala 宏 |
| `scripts/Makefile.docker` | Docker 支持 Makefile |
| `scripts/Makefile.pdb` | PDB 构建 Makefile |
| `scripts/parser.py` | RTL 解析与后处理工具 |
| `scripts/xiangshan.py` | Python 构建封装脚本 |
| `scripts/constantHelper.py` | Constantin 动态参数优化 |
| `scripts/bug-report.sh` | Bug 报告生成脚本 |
| `scripts/generate_all.sh` | 批量模块生成脚本 |
| `scripts/statistics.py` | 构建统计脚本 |
| `scripts/perfcct.py` | 性能 CCT 分析脚本 |
| `scripts/requirements.txt` | Python 依赖清单 |
| `scripts/coverage/coverage.py` | 覆盖率收集脚本 |
| `scripts/top-down/top_down.py` | Top-down 性能分析 |
| `src/main/scala/device/standalone/standalone_device.mk` | 独立设备生成 Makefile |

---

## 18. 总结

XiangShan 的构建系统展现了现代大型开源硬件项目的典型特征和最佳实践：

1. **现代化构建工具**：从 SBT 迁移到 Mill，利用增量构建和简洁 DSL 提升开发效率。Scala 2.13 + Chisel 7.3.0 的技术栈代表了当前 RISC-V 开源硬件生态的最新工具链水平。

2. **模块化依赖管理**：通过 Git submodule 管理 8 个外部模块依赖，形成清晰的依赖层次。ChiselModule trait 统一了所有硬件模块的构建配置，避免了重复配置。

3. **自定义 FIRRTL 流水线**：通过 XiangShanStage 和 PrintModuleName transform，在标准 Chisel 编译流水线中插入项目特定的优化步骤，展示了 Chisel 编译框架的可扩展性。

4. **高度参数化的配置系统**：结合 CDE 框架、命令行参数和 YAML 配置，支持从最小配置到完整 SoC 的灵活切换，满足 FPGA 综合、RTL 仿真、性能分析等不同场景需求。

5. **完整的容器化支持**：Docker 集成实现了开发环境的一致性，智能的环境切换机制对开发者透明，依赖预热策略优化了 Docker 构建效率。

6. **丰富的后处理工具链**：parser.py 等脚本提供了从 RTL 生成到 foundry 发布的完整后处理流程，包括模块提取、SRAM 替换、覆盖率收集等。

7. **全面的代码质量保障**：Scalastyle 规范检查 + Scalafmt 格式化 + chiselTest 单元测试构成了多层次的质量保障体系。

8. **Scala 宏系统的巧妙应用**：CSRMacros 利用编译时宏在不增加运行时开销的前提下，实现了类型安全且简洁的 CSR 寄存器定义 DSL。

这些设计共同构成了一个高效、可靠、可扩展的构建系统，支撑着 XiangShan 这一包含数百万门级逻辑的高性能 RISC-V 处理器核的持续开发和迭代。
