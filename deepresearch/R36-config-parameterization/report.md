# R36 - XiangShan 配置与参数化系统 (Configuration & Parameterization)

## 概述

XiangShan RISC-V 处理器的配置与参数化系统是整个设计的"神经中枢"，它决定了处理器的微架构规模、功能模块的启用/禁用、缓存层次结构、SoC 集成方式以及面向不同目标平台（仿真、FPGA、ASIC）的差异化行为。该系统建立在 CDE（Context-Dependent Environments）框架之上，辅以 YAML 外部配置支持和 Constantin 运行时常量机制，形成了一套多层次、可组合、可运行时调优的参数化体系。

本报告将从 CDE 框架基础出发，逐步深入到 XiangShan 的参数层次、配置变体、YAML 支持、多核配置、运行时常量、参数传播模式、关键配置标志以及面向不同目标平台的配置策略。

---

## 1. CDE 框架基础 (Context-Dependent Environments)

CDE 是 Chisel 生态中广泛使用的参数化框架，源自 Rocket-Chip 项目，目前由 CHIPS Alliance 维护（包名 `org.chipsalliance.cde.config`）。XiangShan 完全依赖 CDE 来管理其庞大的参数空间。

### 1.1 核心概念

CDE 框架由以下几个核心抽象组成：

- **Field[T]**：类型安全的参数键（Key）。每个 `Field` 定义了一个参数槽位及其类型。在 XiangShan 中，所有的顶层参数键（如 `XSTileKey`、`SoCParamsKey`、`DebugOptionsKey`）都通过 `case object ... extends Field[T]` 的形式定义。

- **Parameters**：不可变的参数上下文（context）。它是所有 `Field -> Value` 映射的容器，类似于一个类型安全的 Map。在 Chisel 模块中通过隐式参数 `implicit val p: Parameters` 传递。

- **Config**：配置类，定义了一组参数映射规则。Config 类的构造函数接收一个函数 `(site, here, up) => Seq[PartialFunction[Field[_], Any]]`，通过模式匹配来设置参数值。Config 可以通过 `++` 操作符组合（后定义的优先级更高）。

- **Site**：在 `Config` 定义中用于查询当前参数上下文中任意 Field 的值（即"从这个配置点出发，某个 Field 最终会被解析为什么值"）。

- **Here**：用于查询在当前 Config 层级中正在被定义的 Field 的值（即"在这个 Config 定义点，某个 Field 当前值是什么"）。

- **Up**：用于查询上一层 Config（父 Config）中某个 Field 的值（即"从上一层继承过来的值是什么"）。这在需要基于已有配置进行修改时至关重要。

### 1.2 参数组合机制

CDE 的配置组合通过 `++` 操作符实现。当两个 Config 组合时，右侧 Config 的参数定义覆盖左侧的同名参数。例如：

```scala
class DefaultConfig(n: Int) extends Config(
  OpenLLCConfig("16MB", ways = 16, banks = 4)  // LLC 配置
    ++ L2CacheConfig("2MB", inclusive = true)    // L2 配置
    ++ WithNKBL1D(64, ways = 4)                 // L1D 配置
    ++ new BaseConfig(n)                         // 基础配置
)
```

这里配置的应用顺序是：先应用 `BaseConfig`，然后 `WithNKBL1D` 覆盖 L1D 参数，`L2CacheConfig` 覆盖 L2 参数，`OpenLLCConfig` 覆盖 LLC 参数。

### 1.3 参数动态修改（p.alter）

除了静态组合 Config 类之外，CDE 还提供了 `Parameters.alter` 方法，允许在运行时动态修改参数：

```scala
config.alter((site, here, up) => {
  case XSTileKey => up(XSTileKey).map(_.copy(DecodeWidth = 8))
  case DebugOptionsKey => up(DebugOptionsKey).copy(FPGAPlatform = true)
})
```

`alter` 的回调函数同样接收 `(site, here, up)` 三个参数，但此时它们在 Parameters 实例上操作，而非在 Config 定义中。XiangShan 中共有约 82 处使用了 `p.alter` 或 `.alter` 模式，广泛分布于 `Configs.scala`、`YamlParser.scala`、`ArgParser.scala` 等文件中。

---

## 2. Parameters.scala - 完整参数层次

XiangShan 的核心参数定义集中在 `src/main/scala/xiangshan/Parameters.scala` 中，该文件约 840 行，定义了整个处理器的核心参数体系。

### 2.1 顶层参数键 (Top-Level Keys)

XiangShan 定义了以下核心参数键：

| 参数键 | 类型 | 定义位置 | 用途 |
|--------|------|----------|------|
| `XSTileKey` | `Seq[XSCoreParameters]` | Parameters.scala:44 | 多核 Tile 参数序列 |
| `XSCoreParamsKey` | `XSCoreParameters` | Parameters.scala:46 | 单核参数（备用） |
| `DebugOptionsKey` | `DebugOptions` | Parameters.scala:517 | 调试与平台选项 |
| `DFTOptionsKey` | `DFTOptions` | Parameters.scala:539 | DFT（Design for Test）选项 |
| `SoCParamsKey` | `SoCParameters` | SoC.scala:40 | SoC 级别参数 |
| `CVMParamsKey` | `CVMParameters` | SoC.scala:41 | CVM（Confidential VM）参数 |
| `PMParameKey` | `PMParameters` | PMParameters.scala:25 | PMP/PMA 保护参数 |

### 2.2 XSCoreParameters 核心微架构参数

`XSCoreParameters` 是最庞大的参数类（约 250+ 字段），涵盖了处理器核心的所有微架构配置：

**ISA 扩展控制：**
- `XLEN: Int = 64` - 整数寄存器宽度
- `VLEN: Int = 128` - 向量寄存器宽度
- `ELEN: Int = 64` - 向量元素最大长度
- `HasMExtension: Boolean = true` - 乘除法扩展
- `HasCExtension: Boolean = true` - 压缩指令扩展
- `HasHExtension: Boolean = true` - Hypervisor 扩展
- `HasFPU: Boolean = true` - 浮点单元
- `HasVPU: Boolean = true` - 向量处理单元
- `HasDiv: Boolean = true` - 除法器
- `HasDCache: Boolean = true` - 数据缓存

**流水线宽度参数：**
- `DecodeWidth: Int = 8` - 译码宽度
- `RenameWidth: Int = 8` - 重命名宽度
- `CommitWidth: Int = 8` - 提交宽度
- `RobCommitWidth: Int = 8` - ROB 提交宽度
- `LoadPipelineWidth: Int = 3` - Load 流水线宽度
- `StorePipelineWidth: Int = 2` - Store 流水线宽度

**队列大小参数：**
- `RobSize: Int = 352` - ROB 大小
- `RabSize: Int = 352` - RAB（Retirement Alias Buffer）大小
- `VirtualLoadQueueSize: Int = 72` - Load 队列大小
- `StoreQueueSize: Int = 56` - Store 队列大小
- `IssueQueueSize: Int = 20` - Issue 队列大小
- `LoadUncacheBufferSize: Int = 16` - 非缓存 Load 缓冲区大小

**物理寄存器文件参数：**
- `intPreg: PregParams` - 整数物理寄存器（224 entries, 4 banks）
- `fpPreg: PregParams` - 浮点物理寄存器（256 entries, 1 bank）
- `vfPreg: PregParams` - 向量物理寄存器（128 entries, 1 bank）
- `v0Preg: PregParams` - V0 寄存器（22 entries）
- `vlPreg: PregParams` - VL 寄存器（32 entries）

**TLB 参数：**
- `itlbParameters` - 指令 TLB（48 ways）
- `ldtlbParameters` - Load TLB（48 ways）
- `sttlbParameters` - Store TLB（48 ways）
- `hytlbParameters` - Hypervisor TLB（48 ways）
- `pftlbParameters` - 预取 TLB（48 ways）
- `btlbParameters` - 背部 TLB（48 ways）
- `l2tlbParameters` - L2 TLB

**缓存参数：**
- `dcacheParametersOpt: Option[DCacheParameters]` - DCache 配置（secded ECC, setplru 替换策略）
- `L2CacheParamsOpt: Option[L2Param]` - L2 缓存配置
- `L2NBanks: Int = 1` - L2 bank 数量

**前端参数：**
- `frontendParameters: FrontendParameters` - 前端参数集合（包含 BPU、FTQ、ICache、IBuffer 等子参数）

**功能特性开关：**
- `EnableLoadFastWakeUp: Boolean = true`
- `EnableRenameSnapshot: Boolean = true`
- `EnableStorePrefetchAtIssue/AtCommit: Boolean = false`
- `EnableLoadToLoadForward: Boolean = false`
- `HasCMO: Boolean = true` - Cache Management Operations
- `EnableBackendV2Config: Boolean = false` - 后端 V2 配置切换

### 2.3 DebugOptions 调试与平台选项

```scala
case class DebugOptions(
  FPGAPlatform: Boolean = false,       // FPGA 平台标志
  DumpCSR: Boolean = false,            // CSR 转储
  ResetGen: Boolean = false,           // 复位生成器
  EnableDifftest: Boolean = false,     // 差分测试
  AlwaysBasicDiff: Boolean = true,     // 始终基础差分
  EnableDebug: Boolean = false,        // 调试模式
  EnablePerfDebug: Boolean = true,     // 性能调试
  PerfLevel: String = "VERBOSE",       // 性能级别
  EnableXMR: Boolean = true,           // 跨模块引用
  SimMemSize: Long = 8190L * 1024 * 1024 * 1024,  // 仿真内存大小（约8GB）
  UseDRAMSim: Boolean = false,         // 使用 DRAMSim
  EnableConstantin: Boolean = false,   // Constantin 运行时控制
  EnableChiselDB: Boolean = false,     // ChiselDB 调试数据库
  AlwaysBasicDB: Boolean = true,       // 始终基础数据库
  EnableRollingDB: Boolean = false,    // 滚动数据库
  EnableSimFrontend: Boolean = false   // 仿真前端
)
```

### 2.4 DFTOptions 测试选项

```scala
case class DFTOptions(
  EnableMbist: Boolean = true,    // MBIST（Memory Built-In Self-Test）
  EnableSramCtl: Boolean = false  // SRAM 控制器
)
```

### 2.5 HasXSParameter Trait - 参数访问接口

`HasXSParameter` trait（约 290 行）提供了统一的参数访问接口。所有需要访问处理器参数的模块都会混入此 trait。它将 `XSCoreParameters` 中的字段解包为直接可访问的 def/val：

```scala
trait HasXSParameter {
  implicit val p: Parameters
  def coreParams = p(XSCoreParamsKey)
  def env = p(DebugOptionsKey)
  def XLEN = coreParams.XLEN
  def VLEN = coreParams.VLEN
  def DecodeWidth = coreParams.DecodeWidth
  def RobSize = coreParams.RobSize
  // ... 100+ 个参数访问器
}
```

这种设计使得下游模块可以直接使用 `DecodeWidth`、`RobSize` 等简短名称，而无需穿透多层参数嵌套。

---

## 3. 配置变体 (Configuration Variants)

XiangShan 定义了丰富的配置类层次，针对不同的使用场景和目标平台。

### 3.1 BaseConfig - 基础配置

`BaseConfig(n: Int)` 是所有配置的根基，设置最基本的参数：

```scala
class BaseConfig(n: Int) extends Config((site, here, up) => {
  case XLen => 64
  case DebugOptionsKey => DebugOptions()          // 默认调试选项
  case SoCParamsKey => SoCParameters()            // 默认 SoC 参数
  case CVMParamsKey => CVMParameters()            // 默认 CVM 参数
  case PMParameKey => PMParameters()              // 默认 PMP/PMA 参数
  case XSTileKey => Seq.tabulate(n){ i => XSCoreParameters(HartId = i) }
  case ExportDebug => DebugAttachParams(protocols = Set(JTAG))
  case DebugModuleKey => Some(DebugModuleParams(...))
  case MaxHartIdBits => log2Up(n) max 6
  case EnableJtag => true.B
})
```

`n` 参数控制核心数量。每个核心的 `HartId` 按序列号递增。

### 3.2 DefaultConfig - 默认配置

`DefaultConfig(n)` 在 BaseConfig 基础上添加完整的缓存层次：

```scala
class DefaultConfig(n: Int = 1) extends Config(
  OpenLLCConfig("16MB", ways = 16, banks = 4)   // 16MB L3/LLC
    ++ L2CacheConfig("2MB", inclusive = true, banks = 4, tp = false)
    ++ WithNKBL1D(64, ways = 4)                  // 64KB L1D
    ++ new BaseConfig(n)
)
```

这是最常用的配置，适用于全功能仿真和验证。

### 3.3 MinimalConfig - 最小化配置

`MinimalConfig(n)` 大幅缩减各模块规模，用于快速编译和资源受限的场景：

```scala
class MinimalConfig(n: Int = 1) extends Config(
  OpenLLCConfig("4MB", ways = 8, banks = 4)
    ++ L2CacheConfig("128KB", inclusive = true, banks = 1, tp = false)
    ++ WithNKBL1D(32, ways = 4)           // 32KB L1D（DefaultConfig 为 64KB）
    ++ new BaseConfig(n).alter((site, here, up) => {
      case XSTileKey => up(XSTileKey).map(p => p.copy(
        DecodeWidth = 8,                    // 保持不变
        VirtualLoadQueueSize = 24,          // 从 72 降至 24
        StoreQueueSize = 20,                // 从 56 降至 20
        RobSize = 48,                       // 从 352 降至 48
        RabSize = 96,                       // 从 352 降至 96
        IssueQueueSize = 10,                // 从 20 降至 10
        // ... TLB ways 从 48 降至 4
      ))
    })
)
```

MinimalConfig 将 RobSize 从 352 降至 48，L2 从 2MB 降至 128KB，L3 从 16MB 降至 4MB，大幅减少硬件资源需求。

### 3.4 FPGA 配置变体

- **FpgaDefaultConfig**：面向 FPGA 平台的默认配置，使用较小的缓存（L2 1MB, L3 3MB），并禁用部分调试功能。
- **FpgaDiffDefaultConfig**：在 FpgaDefaultConfig 基础上启用差分测试（`AlwaysBasicDiff = true`）。
- **FpgaDiffMinimalConfig**：最小化 FPGA 差分测试配置。

### 3.5 NoC 配置变体

- **XSNoCTopConfig**：启用 NoC（Network-on-Chip）拓扑（`UseXSNoCTop = true`）。
- **XSNoCTopMinimalConfig**：最小化 NoC 配置。
- **XSNoCDiffTopConfig** / **XSNoCDiffTopMinimalConfig**：NoC + 差分测试。

### 3.6 专用配置变体

- **FrontendDebugConfig**：启用前端 BPU trace 功能和 Constantin 支持，用于前端调试。
- **BackendV2Config**：启用后端 V2 配置，使用 `BackendV2SchdParams` 替代默认调度参数。
- **CVMConfig** / **CVMTestConfig**：启用 CVM（Confidential VM）内存加密功能。
- **WithFuzzer**：禁用性能调试，用于 Fuzzer 测试。
- **WithL3DebugConfig**：最小 L3（256KB）配置用于 L3 调试。

### 3.7 DeprecatedConfigWarning Trait

标记为已弃用的配置类会混入 `DeprecatedConfigWarning` trait，在实例化时打印警告并等待 10 秒。这确保开发者逐步迁移到新的配置方式。

---

## 4. YAML 配置支持

XiangShan 支持通过 YAML 文件进行外部配置，这是一个重要的工程化特性，允许硬件设计者在不修改 Scala 源代码的情况下调整配置。

### 4.1 YamlParser 实现

YAML 配置解析器位于 `src/main/scala/top/YamlParser.scala`，基于 circe-yaml 库实现：

```scala
object YamlParser {
  implicit val customParserConfig: Configuration = Configuration.default.withDefaults
  def parseYaml(config: Parameters, yamlFile: String): Parameters = {
    val yaml = scala.io.Source.fromFile(yamlFile).mkString
    val json = io.circe.yaml.parser.parse(yaml) match {
      case Left(value) => throw value
      case Right(value) => value
    }
    val yamlConfig = json.as[YamlConfig] match {
      case Left(value) => throw value
      case Right(value) => value
    }
    // 逐一应用 YAML 配置项...
  }
}
```

### 4.2 YamlConfig 数据模型

YAML 配置支持以下参数（均为 `Option` 类型，未指定的保持默认值）：

```scala
case class YamlConfig(
  Config: Option[String],                  // 基础配置类名
  PmemRanges: Option[List[MemoryRange]],   // 物理内存范围
  PMAConfigs: Option[List[PMAConfigEntry]], // PMA 配置
  EnableCHIAsyncBridge: Option[Boolean],   // CHI 异步桥
  L2CacheConfig: Option[L2CacheConfig],    // L2 缓存配置
  OpenLLCConfig: Option[OpenLLCConfig],    // L3/LLC 配置
  HartIDBits: Option[Int],                 // Hart ID 位宽
  DebugAttachProtocals: Option[List[String]], // 调试协议
  DebugModuleParams: Option[DebugModuleParams], // 调试模块参数
  WFIResume: Option[Boolean],              // WFI 恢复
  SeperateDM: Option[Boolean],            // 独立调试模块
  SeperateBus: Option[String],            // 独立总线
  UsePrivateClint: Option[Boolean],       // 私有 CLINT
  IMSICBusType: Option[String],           // IMSIC 总线类型
  IMSICParams: Option[IMSICParams],       // IMSIC 参数
  CHIIssue: Option[String],               // CHI Issue 版本
  WFIClockGate: Option[Boolean],          // WFI 时钟门控
  EnablePowerDown: Option[Boolean],       // 电源关断
  XSTopPrefix: Option[String],           // Top 模块前缀
  EnableDFX: Option[Boolean],            // DFX 使能
  EnableSramCtl: Option[Boolean],        // SRAM 控制器
  CVMParams: Option[CVMParameters],       // CVM 参数
  // ...
)
```

### 4.3 使用方式

通过命令行参数 `--yaml-config <yaml-file>` 指定 YAML 配置文件：

```bash
./build/XiangShan --config DefaultConfig --yaml-config myconfig.yaml
```

YAML 解析器会将 YAML 配置转换为 circe JSON，然后反序列化为 `YamlConfig` 对象，逐一通过 `p.alter` 应用到当前 Parameters 上。对于缓存配置（`L2CacheConfig`、`OpenLLCConfig`），直接使用 Config 对象的 `alter` 方法组合。

### 4.4 依赖库

YAML 支持依赖 `io.circe:circe-yaml` 库，在 `ChiselIOPMP/build.sc` 中有相关依赖声明。

---

## 5. 多核配置 (Multi-Core Configuration)

XiangShan 支持灵活的多核配置，核心数量通过多种方式控制。

### 5.1 XSTileKey 序列参数

核心数量通过 `XSTileKey`（类型 `Seq[XSCoreParameters]`）控制。`BaseConfig(n)` 使用 `Seq.tabulate(n)` 生成 n 个核心参数：

```scala
case XSTileKey => Seq.tabulate(n){ i => XSCoreParameters(HartId = i) }
```

每个核心通过 `HartId` 区分（从 0 到 n-1）。

### 5.2 命令行参数 --num-cores

`ArgParser` 支持 `--num-cores <N>` 命令行参数动态设置核心数：

```scala
case "--num-cores" :: value :: tail =>
  nextOption(config.alter((site, here, up) => {
    case XSTileKey => (0 until value.toInt) map { i =>
      up(XSTileKey).head.copy(HartId = i)
    }
    case MaxHartIdBits =>
      log2Up(value.toInt) max up(MaxHartIdBits)
  }), tail)
```

这会复制第一个核心的配置模板，生成指定数量的核心，并相应调整 `MaxHartIdBits`。

### 5.3 NumCores 派生参数

在 SoC 级别，`NumCores` 从 `XSTileKey` 的序列长度自动派生：

```scala
trait HasSoCParameter {
  val tiles = p(XSTileKey)
  val NumCores = tiles.size
}
```

`NumCores` 被广泛用于 SoC 级别的参数化，包括：
- MMIO 桥接器数量：`Seq.fill(NumCores)(Option.when(enableCHI)(...))`
- NMI 中断源：`IntSourceNode(IntSourcePortSimple(1, NumCores, ...))`
- Core 到 L3 端口：`Array.fill(NumCores) { TLTempNode() }`
- 调试模块：`new DebugModule(NumCores)(p)`

### 5.4 MaxHartIdBits

Hart ID 位宽自动计算：`log2Up(n) max 6`，确保至少 6 位（支持 64 核）。命令行可通过 `--hartidbits` 手动覆盖。

---

## 6. 运行时可配置常量 (Constantin)

Constantin 是 XiangShan 使用的运行时常量（runtime-configurable constant）机制，允许在仿真或 FPGA 运行时动态调整硬件参数，而无需重新综合。

### 6.1 工作原理

Constantin 在 Chisel 生成 RTL 时创建特殊的数据结构（IO 端口或寄存器），使得外部控制逻辑可以在运行时修改这些值。这不同于 CDE 的编译时参数化——CDE 参数在 Chisel elaboration 时固定，而 Constantin 值可以在 RTL 运行时变化。

### 6.2 使用模式

```scala
// 在 Chisel Module 中：
val threshold = Constantin.createRecord("StoreBufferThreshold_0", initValue = 9)
```

`createRecord` 接收一个字符串名称和初始值，返回一个可以作为硬件信号使用的常量。

### 6.3 使用场景分布

XiangShan 中约有 17 个文件使用了 Constantin，主要分布于以下领域：

**缓存与内存子系统：**
- `MainPipe.scala`：`StoreWaitThreshold` - Store 等待阈值
- `DCacheWrapper.scala`：`isWriteLoadMissTable`、`isFirstHitWrite`、`isWriteLoadAccessTable` - 调试控制
- `BankedDataArray.scala`：`isWriteBankConflictTable` - Bank 冲突表写控制
- `MissQueue.scala`：`nMaxPrefetchEntry` - 最大预取条目数（默认 14）
- `L2TLB.scala`：`isWriteL2TlbPrefetchTable`、`isWriteL1TlbTable` - TLB 调试

**预取器：**
- `L1StreamPrefetcher.scala`：`l2DepthRatio`、`l3DepthRatio`、`streamL1Depth`（64）、`streamL2Depth`（640）、`streamL3Depth`（640）、`enableL3StreamPrefetch`
- `L1StridePrefetcher.scala`：`always_update`、`l1_stride_ratio`（2）、`l2_stride_ratio`（5）
- `Berti.scala`：多个阈值参数（`thresholdOfReset`、`thresholdOfUpdate` 等）
- `PrefetcherMonitor.scala`：`depth`（32）、`enableDynamicPrefetcher`

**后端与执行：**
- `Backend.scala`：`EnableMdp` - Memory Dependence Prediction 开关
- `CSR.scala`：`slvpredctl` - Load 预测控制寄存器（默认 0x60）

**Store Buffer：**
- `Sbuffer.scala`：`StoreBufferThreshold`（默认 9）、`StoreBufferBase`（默认 4）

### 6.4 启用条件

Constantin 默认禁用（`EnableConstantin: Boolean = false`），需通过以下方式启用：
- 命令行参数：`--with-constantin`
- YAML 配置：在 FrontendDebugConfig 中自动启用
- DebugOptions 修改：`EnableConstantin = true`

---

## 7. 参数传播模式 (Parameter Propagation Patterns)

XiangShan 中参数传播遵循几种固定模式。

### 7.1 隐式参数传递（Implicit Parameter）

最基础的模式是通过 Scala 隐式参数传递 `Parameters`：

```scala
trait HasXSParameter {
  implicit val p: Parameters
  def coreParams = p(XSCoreParamsKey)
}
```

所有混入 `HasXSParameter` 或 `HasSoCParameter` 的模块自动获得参数访问能力。

### 7.2 Config ++ 组合模式

用于构建配置层次：

```scala
class DefaultConfig(n: Int) extends Config(
  OpenLLCConfig("16MB", ways = 16, banks = 4)
    ++ L2CacheConfig("2MB", inclusive = true, banks = 4)
    ++ WithNKBL1D(64, ways = 4)
    ++ new BaseConfig(n)
)
```

这是一种"自底向上"的组合：基础配置最先应用，上层配置逐层覆盖。

### 7.3 Config.alter 动态修改模式

用于在已有配置基础上进行选择性修改：

```scala
class FrontendDebugConfig(n: Int = 1) extends Config(
  (new DefaultConfig(n)).alter((site, here, up) => {
    case XSTileKey => up(XSTileKey).map{ p => p.copy(
      frontendParameters = p.frontendParameters.copy(
        bpuParameters = p.frontendParameters.bpuParameters.copy(
          EnableBpTrace = true,
        )
      )
    )}
  })
)
```

这里使用 `up(XSTileKey)` 获取基础配置中的核心参数，然后通过 `.copy()` 创建修改后的副本。

### 7.4 深层嵌套修改模式

由于参数结构较深（如 `XSCoreParameters.frontendParameters.bpuParameters.tageParameters`），修改深层参数需要多层 `.copy()`：

```scala
case XSTileKey => up(XSTileKey).map{ p => p.copy(
  frontendParameters = p.frontendParameters.copy(
    bpuParameters = p.frontendParameters.bpuParameters.copy(
      tageParameters = p.frontendParameters.bpuParameters.tageParameters.copy(
        EnableTageTrace = true,
      ),
    ),
  ),
)}
```

### 7.5 ArgParser 命令行覆盖模式

命令行参数通过尾递归 `nextOption` 函数逐一应用：

```scala
@tailrec
def nextOption(config: Parameters, list: List[String]): Parameters = {
  list match {
    case "--fpga-platform" :: tail =>
      nextOption(config.alter((site, here, up) => {
        case DebugOptionsKey => up(DebugOptionsKey).copy(FPGAPlatform = true)
      }), tail)
    case "--num-cores" :: value :: tail =>
      nextOption(config.alter((site, here, up) => { ... }), tail)
    // ...
  }
}
```

每个命令行选项对应一个 `p.alter` 调用，通过 `up()` 获取当前值并 `.copy()` 修改。

### 7.6 缓存配置封装模式

缓存配置（L2CacheConfig、OpenLLCConfig、WithNKBL1D）被封装为独立的 Config 类，可以像"插件"一样组合：

```scala
case class L2CacheConfig(
  size: String,
  ways: Int = 8,
  inclusive: Boolean = true,
  banks: Int = 1,
  tp: Boolean = true,
) extends Config((site, here, up) => {
  case XSTileKey =>
    val nKB = size.toUpperCase() match {
      case s"${k}KB" => k.trim().toInt
      case s"${m}MB" => (m.trim().toDouble * 1024).toInt
    }
    upParams.map(p => p.copy(
      L2CacheParamsOpt = Some(L2Param(...)),
      L2NBanks = banks
    ))
})
```

`L2CacheConfig` 接收人类可读的大小字符串（如 "2MB"、"128KB"），自动计算 sets 数量，并配置 ECC、预取器等关联参数。

---

## 8. 关键配置标志 (Key Configuration Flags)

### 8.1 FPGAPlatform

最重要的平台选择标志，影响几乎所有硬件行为：

- **仿真模式**（`FPGAPlatform = false`）：启用 Difftest、ChiselDB、性能计数器、Top-Down 分析等调试基础设施。
- **FPGA 模式**（`FPGAPlatform = true`）：禁用大部分调试逻辑，减少资源占用和时序压力。

使用 `FPGAPlatform` 的典型位置：
- L2 配置：`enablePerf = !site(DebugOptionsKey).FPGAPlatform && site(DebugOptionsKey).EnablePerfDebug`
- L2 配置：`elaboratedTopDown = !site(DebugOptionsKey).FPGAPlatform`
- LogUtils 和 PerfCounter：根据 `FPGAPlatform` 控制日志输出和性能计数器

### 8.2 Enable* 系列标志

**调试与验证：**
- `EnableDifftest` - 差分测试框架
- `EnableDebug` - 通用调试开关
- `EnablePerfDebug` - 性能调试
- `EnableChiselDB` - ChiselDB 调试数据库
- `EnableRollingDB` - 滚动数据库
- `EnableConstantin` - Constantin 运行时常量
- `EnableXMR` - 跨模块引用（Cross-Module Reference）
- `EnableSimFrontend` - 仿真前端模式

**微架构特性：**
- `EnableClockGate` - 时钟门控
- `EnableSv48` - Sv48 虚拟内存
- `EnableCommitGHistDiff` - 提交全局历史差分
- `EnableRenameSnapshot` - 重命名快照
- `EnableLoadFastWakeUp` - Load 快速唤醒
- `EnableBackendV2Config` - 后端 V2 调度配置
- `EnableDispatchIQBalanceOpt` - 调度 Issue Queue 均衡优化
- `EnableLoadToLoadForward` - Load-to-Load 转发
- `EnableFastForward` - 快速转发

**缓存与内存：**
- `EnableAccurateLoadError` - 精确 Load 错误
- `EnableUncacheWriteOutstanding` - 非缓存写 Outstanding
- `EnableHardwareStoreMisalign` / `EnableHardwareLoadMisalign` - 硬件非对齐处理
- `EnableStorePrefetchAtIssue` / `EnableStorePrefetchAtCommit` - Store 预取
- `EnableStorePrefetchSMS` / `EnableStorePrefetchSPB` - SMS/SPB Store 预取

**DFT 与 FPGA：**
- `EnableMbist` - Memory BIST
- `EnableSramCtl` - SRAM 控制器
- `EnableJtag` - JTAG 调试接口

**前端追踪（仅 FrontendDebugConfig）：**
- `EnableBpTrace` - BPU 追踪
- `EnableTageTrace` - TAGE 追踪
- `EnableScTrace` - SC 追踪
- `EnableMainbtbTrace` - MainBTB 追踪
- `EnableTraceAndDebug` - MicroTage 追踪
- `EnableTrace` - ICache 追踪

### 8.3 Use* 系列标志

- `UseXSNoCTop` - 使用 NoC 拓扑
- `UseXSNoCDiffTop` - 使用 NoC 差分测试拓扑
- `UseXSTileDiffTop` - 使用 Tile 差分测试拓扑
- `UsePrivateClint` - 使用私有 CLINT
- `UseDRAMSim` - 使用 DRAMSim 内存模型

---

## 9. 面向不同目标平台的配置

### 9.1 仿真平台 (Simulation)

默认配置 `DefaultConfig` 面向仿真，具有以下特点：
- 完整的 Difftest 支持（`EnableDifftest = true` 通过命令行）
- 性能计数器和 Top-Down 分析
- 大容量仿真内存（`SimMemSize = ~8GB`）
- 完整的缓存层次（64KB L1D, 2MB L2, 16MB L3）
- 所有调试数据库可用

命令行典型用法：
```bash
./build/XiangShan --config DefaultConfig --enable-difftest
```

### 9.2 FPGA 平台

FPGA 配置的关键差异：
- `FPGAPlatform = true` 禁用调试基础设施
- 缓存容量减半或更小（L2 1MB, L3 3MB vs. 默认 2MB/16MB）
- 禁用 AlwaysBasicDiff 和 AlwaysBasicDB
- 不启用性能数据库和 Top-Down 分析
- 需要综合友好的存储器实现

```bash
./build/XiangShan --config FpgaDefaultConfig --fpga-platform
```

### 9.3 ASIC 平台

ASIC 配置通常基于 DefaultConfig 但有以下调整：
- 启用 DFT 选项（`EnableMbist = true`、`EnableSramCtl = true`）
- 通过 `--sram-with-ctl` 启用 SRAM 控制器
- 可能调整缓存大小以满足面积/功耗目标
- 启用 CHI 异步桥接器（`EnableCHIAsyncBridge`）
- 可能启用 WFI 时钟门控（`WFIClockGate = true`）和电源管理（`EnablePowerDown = true`）

### 9.4 调试/验证专用配置

- **FrontendDebugConfig**：启用所有前端 BPU trace 功能
- **WithL3DebugConfig**：最小 L3 用于快速 L3 验证
- **WithFuzzer**：禁用性能调试的 Fuzzer 专用配置
- **BackendV2Config**：后端 V2 微架构配置，用于验证新调度器

### 9.5 YAML 配置驱动

生产环境中更推荐使用 YAML 配置文件，避免修改命令行参数的复杂性：

```yaml
Config: "DefaultConfig"
L2CacheConfig:
  size: "1MB"
  ways: 8
  banks: 2
OpenLLCConfig:
  size: "4MB"
  ways: 8
  banks: 2
EnableDFX: true
EnableSramCtl: true
```

---

## 10. 关键源文件位置

| 文件路径 | 内容 |
|----------|------|
| `src/main/scala/xiangshan/Parameters.scala` | 核心参数定义（XSCoreParameters, DebugOptions, DFTOptions, HasXSParameter） |
| `src/main/scala/top/Configs.scala` | 所有配置变体类（BaseConfig, DefaultConfig, MinimalConfig, FpgaDefaultConfig 等） |
| `src/main/scala/top/ArgParser.scala` | 命令行参数解析器，所有 --fpga-platform、--num-cores 等选项 |
| `src/main/scala/top/YamlParser.scala` | YAML 配置解析器（YamlConfig case class + parseYaml 方法） |
| `src/main/scala/system/SoC.scala` | SoC 级别参数（SoCParameters, CVMParameters, HasSoCParameter） |
| `src/main/scala/xiangshan/PMParameters.scala` | PMP/PMA 保护参数 |
| `src/main/scala/xiangshan/XSCore.scala` | XSCore 模块，参数消费者 |
| `src/main/scala/xiangshan/L2Top.scala` | L2 顶层，使用 FPGAPlatform 控制 Top-Down |
| `src/main/scala/top/Top.scala` | SoC 顶层，使用 NumCores 进行多核实例化 |
| `src/main/scala/top/XSNoCTop.scala` | NoC 拓扑顶层 |

**Constantin 使用文件（部分）：**
- `src/main/scala/xiangshan/cache/dcache/mainpipe/MainPipe.scala`
- `src/main/scala/xiangshan/mem/sbuffer/Sbuffer.scala`
- `src/main/scala/xiangshan/mem/prefetch/L1StreamPrefetcher.scala`
- `src/main/scala/xiangshan/mem/prefetch/L1StridePrefetcher.scala`
- `src/main/scala/xiangshan/mem/prefetch/Berti.scala`
- `src/main/scala/xiangshan/cache/dcache/DCacheWrapper.scala`
- `src/main/scala/xiangshan/cache/dcache/data/BankedDataArray.scala`
- `src/main/scala/xiangshan/cache/dcache/mainpipe/MissQueue.scala`
- `src/main/scala/xiangshan/cache/mmu/L2TLB.scala`
- `src/main/scala/xiangshan/backend/Backend.scala`
- `src/main/scala/xiangshan/backend/fu/CSR.scala`
- `src/main/scala/xiangshan/frontend/bpu/Bpu.scala`
- `src/main/scala/xiangshan/frontend/ifu/IfuPerfAnalysis.scala`
- `src/main/scala/xiangshan/mem/prefetch/PrefetcherMonitor.scala`

---

## 总结

XiangShan 的配置与参数化系统展现了一个成熟的高性能处理器设计项目的工程实践。它建立在 CDE 框架之上，通过 `Field -> Parameters -> Config` 三层抽象实现了类型安全的参数管理。参数体系分为七个核心 Key（XSTileKey、XSCoreParamsKey、DebugOptionsKey、DFTOptionsKey、SoCParamsKey、CVMParamsKey、PMParameKey），其中 XSCoreParameters 单个 case class 就包含 250+ 字段。

配置通过 `++` 操作符组合、`.alter()` 动态修改、命令行参数覆盖、YAML 文件外部配置四种机制层层叠加。Constantin 机制进一步提供了运行时参数调优能力，主要服务于缓存和预取器的动态调整。

整个系统支持从 1 核到 64 核的灵活配置，面向仿真、FPGA、ASIC 三种目标平台提供了差异化的配置变体，同时通过 FrontendDebugConfig、BackendV2Config 等专用配置支持各个子系统的独立调试与验证。这套配置体系确保了 XiangShan 既能满足科研探索的灵活性需求，也能适应工程交付的确定性要求。
