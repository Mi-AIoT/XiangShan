# R36B - XiangShan 配置变体与运行时调优（Configuration Variants & Runtime Tuning）

## 目录

1. [概述](#1-概述)
2. [配置系统架构](#2-配置系统架构)
3. [DefaultConfig 及派生配置](#3-defaultconfig-及派生配置)
4. [MinimalConfig 仿真配置](#4-minimalconfig-仿真配置)
5. [FpgaDefaultConfig FPGA 配置](#5-fpgadefaultconfig-fpga-配置)
6. [XSNoCTopConfig NoC SoC 配置](#6-xsnoctopconfig-noc-soc-配置)
7. [Debug/Validation 调试验证配置](#7-debugvalidation-调试验证配置)
8. [YAML 配置支持](#8-yaml-配置支持)
9. [Constantin 运行时常量](#9-constantin-运行时常量)
10. [ArgParser 命令行参数处理](#10-argparser-命令行参数处理)
11. [多核配置](#11-多核配置)
12. [源文件索引](#12-源文件索引)

---

## 1. 概述

XiangShan 处理器采用 **Chisel CDE (Context-Dependent Environment)** 配置框架来实现多层次、可组合的硬件参数化配置。整个配置体系围绕 `org.chipsalliance.cde.config.Config` / `Parameters` 构建，允许用户在编译期通过 Scala 配置类（Config class）选择处理器的宏观架构参数，同时在运行期通过 **Constantin** 机制动态调优微架构行为参数。

核心设计思想是 **"Configuration Composition"（配置组合）**：每个 Config 类是一个 `Config` 实例，通过 `++` 运算符（CDE 提供）将多个小型 Config 叠加成完整的参数空间。这种设计使得不同场景（FPGA 原型验证、RTL 仿真、多核 SoC 集成）可以复用基础配置并精确覆盖差异参数。

配置系统涉及三个主要入口：
- **`src/main/scala/top/Configs.scala`**：所有 Config 类定义（BaseConfig、DefaultConfig、MinimalConfig 等约 20 个配置类）
- **`src/main/scala/top/ArgParser.scala`**：命令行参数解析，将 CLI flags 映射到 Parameters 修改
- **`src/main/scala/top/YamlParser.scala`**：YAML 文件驱动的配置覆盖，支持从外部 YAML 文件导入 SoC 级别参数

---

## 2. 配置系统架构

### 2.1 CDE 配置模型

XiangShan 使用 Chisel 生态中的 CDE 库（`org.chipsalliance.cde.config`），其核心抽象为：

- **`Field[T]`**：类型安全的配置键（key），如 `XSTileKey`、`SoCParamsKey`、`DebugOptionsKey`
- **`Parameters`**：配置值的不可变映射，通过 `alter` 方法产生新的 Parameters 实例
- **`Config`**：一个从父 Parameters 产生新 Parameters 的函数 `(site, here, up) => ...`

配置的叠加使用 `++` 运算符，右侧 Config 的优先级高于左侧（类似函数组合 `f andThen g`）。

### 2.2 核心配置键

| 配置键 | 类型 | 定义位置 | 用途 |
|---|---|---|---|
| `XLen` | `Int` | BaseConfig 内联 | ISA 位宽（固定 64） |
| `XSTileKey` | `Seq[XSCoreParameters]` | `xiangshan.Parameters` | 每个核的微架构参数列表 |
| `SoCParamsKey` | `SoCParameters` | `system.SoC` | SoC 级参数（内存映射、LLC、NoC） |
| `DebugOptionsKey` | `DebugOptions` | `xiangshan.Parameters` | 调试/仿真控制开关 |
| `DFTOptionsKey` | `DFTOptions` | `xiangshan.Parameters` | DFT（Design for Test）选项 |
| `CVMParamsKey` | `CVMParameters` | `system.SoC` | CVM（Confidential VM）参数 |
| `MaxHartIdBits` | `Int` | Rocket Chip | Hart ID 位宽 |
| `ExportDebug` | `DebugAttachParams` | Rocket Chip | 调试接口协议配置 |
| `DebugModuleKey` | `Option[DebugModuleParams]` | Rocket Chip | Debug Module 参数 |

### 2.3 参数传递链

参数的组装遵循以下层次：

```
BaseConfig(n)                         // 基础参数：XLen=64, HartId, DebugModule
  + WithNKBL1D(size, ways)           // L1 DCache 大小覆盖
  + L2CacheConfig(size, ...)         // L2 Cache 参数
  + OpenLLCConfig(size, ...)         // L3/LLC 参数
  = DefaultConfig / MinimalConfig    // 完整配置
  + ArgParser (CLI flags)            // 命令行覆盖（运行时）
  + YamlParser (YAML file)           // YAML 文件覆盖（运行时）
```

---

## 3. DefaultConfig 及派生配置

### 3.1 BaseConfig — 基础配置

`BaseConfig(n: Int)` 是所有配置的根基，定义在 `src/main/scala/top/Configs.scala` 第 53-74 行：

```scala
class BaseConfig(n: Int) extends Config((site, here, up) => {
  case XLen => 64
  case DebugOptionsKey => DebugOptions()
  case SoCParamsKey => SoCParameters()
  case CVMParamsKey => CVMParameters()
  case PMParameKey => PMParameters()
  case XSTileKey => Seq.tabulate(n){ i => XSCoreParameters(HartId = i) }
  case ExportDebug => DebugAttachParams(protocols = Set(JTAG))
  case DebugModuleKey => Some(DebugModuleParams(...))
  case MaxHartIdBits => log2Up(n) max 6
  ...
})
```

核心要点：
- 接受 `n: Int` 参数指定核数，通过 `Seq.tabulate(n)` 生成 n 个 `XSCoreParameters`
- 每个核的 HartId 按索引 `i` 从 0 开始递增
- Debug Module 基地址固定为 `0x38020000`
- `MaxHartIdBits` 取 `log2Up(n) max 6`，保证至少 6 位

### 3.2 DefaultConfig — 默认全功能配置

`DefaultConfig(n: Int)` 在 BaseConfig 基础上叠加完整的缓存层次（第 533-538 行）：

```scala
class DefaultConfig(n: Int) extends Config(
  OpenLLCConfig("16MB", ways = 16, banks = 4)
    ++ L2CacheConfig("2MB", inclusive = true, banks = 4, tp = false)
    ++ WithNKBL1D(64, ways = 4)
    ++ new BaseConfig(n)
)
```

缓存层次参数：
| 层级 | 容量 | 路数 | Bank 数 | 备注 |
|---|---|---|---|---|
| L1 DCache | 64 KB | 4 ways | — | 由 `WithNKBL1D(64, 4)` 设定 |
| L2 Cache | 2 MB | 8 ways（默认） | 4 banks | inclusive, tp=false（禁用 Temporal Prefetcher） |
| LLC (OpenLLC) | 16 MB | 16 ways | 4 banks | L3 级别，非inclusive |

### 3.3 派生配置总览

Configs.scala 中定义的所有 Config 类及其状态：

| 配置类 | 基于 | 用途 | 已废弃 |
|---|---|---|---|
| `BaseConfig(n)` | — | 所有配置的根基 | 否 |
| `DefaultConfig(n)` | BaseConfig | 全功能默认配置 | 否 |
| `MinimalConfig(n)` | BaseConfig | 轻量仿真配置 | 否 |
| `MinimalL3DebugConfig(n)` | MinimalConfig + WithL3Debug | 轻量 L3 调试 | **已废弃** |
| `DefaultL3DebugConfig(n)` | DefaultConfig + WithL3Debug | 默认 L3 调试 | **已废弃** |
| `MinimalAliasDebugConfig(n)` | MinimalConfig 大缓存 | Alias debug | **已废弃** |
| `MediumConfig(n)` | DefaultConfig | 中等配置 | **已废弃** |
| `FuzzConfig(dummy)` | DefaultConfig + WithFuzzer | Fuzzing 测试 | **已废弃** |
| `FpgaDefaultConfig(n)` | BaseConfig + 缓存覆盖 | FPGA 原型 | **已废弃** |
| `FpgaDiffDefaultConfig(n)` | BaseConfig + 缓存覆盖 | FPGA 差分 | **已废弃** |
| `FpgaDiffMinimalConfig(n)` | MinimalConfig | FPGA 最小差分 | **已废弃** |
| `XSNoCTopConfig(n)` | DefaultConfig | NoC SoC | 否 |
| `XSNoCTopMinimalConfig(n)` | MinimalConfig | NoC 最小 SoC | 否 |
| `XSNoCDiffTopConfig(n)` | XSNoCTopConfig | NoC 差分 | 否 |
| `XSNoCDiffTopMinimalConfig(n)` | XSNoCTopConfig | NoC 最小差分 | 否 |
| `FrontendDebugConfig(n)` | DefaultConfig | 前端调试 | 否 |
| `BackendV2Config(n)` | DefaultConfig | 后端 V2 实验 | **已废弃** |
| `CVMConfig(n)` | DefaultConfig + CVMCompile | CVM 加密 | **已废弃** |
| `CVMTestConfig(n)` | DefaultConfig + CVMTestCompile | CVM 测试 | **已废弃** |

`DeprecatedConfigWarning` trait（第 613-622 行）会在构造时打印警告信息并 `Thread.sleep(10000)`（10 秒），明确提示开发者迁移到新配置。

---

## 4. MinimalConfig 仿真配置

`MinimalConfig(n: Int = 1)`（第 76-273 行）是专为 RTL 仿真和功能验证设计的轻量配置，将所有微架构参数大幅缩减以加速仿真运行。

### 4.1 流水线宽度

| 参数 | DefaultConfig 默认值 | MinimalConfig 值 | 说明 |
|---|---|---|---|
| DecodeWidth | 8 | 8 | 解码宽度（与默认相同） |
| RenameWidth | 10 | 8 | 重命名宽度缩减 |
| RobCommitWidth | 8 | 8 | ROB 提交宽度 |

### 4.2 关键缓冲区尺寸

| 参数 | MinimalConfig 值 | 说明 |
|---|---|---|
| VirtualLoadQueueSize | 24 | Load Queue 虚拟大小 |
| LoadQueueRARSize | 24 | Load Queue RAR（Read-After-Read） |
| LoadQueueRAWSize | 12 | Load Queue RAW（Read-After-Write） |
| LoadQueueReplaySize | 24 | Load Queue Replay 大小 |
| LoadUncacheBufferSize | 8 | 非缓存 Load Buffer |
| StoreQueueSize | 20 | Store Queue 大小 |
| RobSize | 48 | 重排序缓冲区大小（默认 256+） |
| RabSize | 96 | RAB（Register Alias Buffer）大小 |
| IssueQueueSize | 10 | Issue Queue 条目数 |
| IssueQueueCompEntrySize | 4 | Issue Queue Compact 条目数 |

### 4.3 缓存层级

| 层级 | MinimalConfig 参数 | 备注 |
|---|---|---|
| L1 DCache | 32 KB, 8 ways, 64 sets | `nSets = 64` |
| L2 Cache | 128 KB, 8 ways, inclusive, 1 bank | tp=false, 无 prefetcher |
| L3 (LLC) | 4 MB, 8 ways, 4 banks | `OpenLLCConfig("4MB", ways = 8, banks = 4)` |

L2 的 `L2NBanks = 2`，且显式设置 `prefetcher = Nil` 禁用所有预取器。

### 4.4 TLB 配置

所有 TLB（itlb, ldtlb, sttlb, hytlb, pftlb, btlb）统一设置为 `NWays = 4`。L2 TLB 参数也被缩减：`l3Size = 4, l2Size = 4, l1nSets = 4, spSize = 4`。

### 4.5 寄存器文件

| 寄存器堆 | 条目数 | Bank 数 |
|---|---|---|
| intPreg | 64 | 4 |
| fpPreg | 64 | 1 |
| vfPreg | 160 | 1 |

### 4.6 Frontend 参数

MinimalConfig 对 Frontend 有大幅精简：
- `FetchBlockSize = 32`（32 字节取指块）
- FTQ 大小 `FtqSize = 8`（默认 40）
- ICache `nSets = 64`（16 KB，默认 256 sets / 64 KB）
- IBuffer `Size = 24`
- BPU：Tage 表缩减为 1024 entries/way、2 ways

---

## 5. FpgaDefaultConfig FPGA 配置

### 5.1 FpgaDefaultConfig

`FpgaDefaultConfig(n: Int = 1)`（第 574-584 行）是面向 FPGA 原型验证的配置：

```scala
class FpgaDefaultConfig(n: Int = 1) extends Config(
  (OpenLLCConfig("3MB", banks = 1, ways = 6)
    ++ L2CacheConfig("1MB", inclusive = true, banks = 4)
    ++ WithNKBL1D(64, ways = 4)
    ++ new BaseConfig(n)).alter((site, here, up) => {
    case DebugOptionsKey => up(DebugOptionsKey).copy(
      AlwaysBasicDiff = false,
      AlwaysBasicDB = false
    )
  })
) with DeprecatedConfigWarning
```

与 DefaultConfig 的关键差异：
| 参数 | DefaultConfig | FpgaDefaultConfig | 原因 |
|---|---|---|---|
| LLC 容量 | 16 MB | 3 MB | FPGA BRAM 资源受限 |
| LLC ways | 16 | 6 | 减少 BRAM 消耗 |
| LLC banks | 4 | 1 | 减少访问冲突但节省资源 |
| L2 容量 | 2 MB | 1 MB | BRAM 限制 |
| AlwaysBasicDiff | true | false | FPGA 无需 Always Diff |
| AlwaysBasicDB | true | false | FPGA 无需 Always DB |

**注意**：`FpgaDefaultConfig` 标记为 `DeprecatedConfigWarning`，表明项目正在逐步迁移到其他配置方式。

### 5.2 FpgaDiffDefaultConfig

`FpgaDiffDefaultConfig(n: Int = 1)`（第 586-599 行）在 FpgaDefaultConfig 基础上启用差分验证：
- 设置 `AlwaysBasicDiff = true`（开启 Always Diff）
- 设置 `UseXSTileDiffTop = true`（使用差分 Top 层）

### 5.3 FpgaDiffMinimalConfig

`FpgaDiffMinimalConfig(n: Int = 1)`（第 601-611 行）基于 MinimalConfig，适合 FPGA 上的最小化差分验证场景。

---

## 6. XSNoCTopConfig NoC SoC 配置

### 6.1 XSNoCTopConfig

```scala
class XSNoCTopConfig(n: Int = 1) extends Config(
  (new DefaultConfig(n)).alter((site, here, up) => {
    case SoCParamsKey => up(SoCParamsKey).copy(UseXSNoCTop = true)
  })
)
```

基于 DefaultConfig，仅设置 `UseXSNoCTop = true`。这个标志控制 XiangShan Top 模块使用 **XSNoCTop**（基于 TileLink/CHI NoC 的 SoC 互联拓扑），而非简单的 Crossbar 互联。

### 6.2 XSNoCTopMinimalConfig

基于 MinimalConfig + `UseXSNoCTop = true`，用于 NoC 拓扑下的轻量仿真。

### 6.3 XSNoCDiffTopConfig / XSNoCDiffTopMinimalConfig

这两个配置进一步设置 `UseXSNoCDiffTop = true`，启用基于 NoC 拓扑的差分验证 Top 层，用于将 XiangShan 与参考模型进行周期级比对。

### 6.4 SoCParameters 中的 NoC 相关参数

`SoCParameters`（定义在 `system/SoC.scala`）中与 NoC/SoC 拓扑相关的关键字段：

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `UseXSNoCTop` | Boolean | false | 启用 NoC Top |
| `UseXSNoCDiffTop` | Boolean | false | 启用 NoC Diff Top |
| `UseXSTileDiffTop` | Boolean | false | 启用 Tile Diff Top |
| `NodeIDWidthList` | Map[String,Int] | B:7, C:9, E.b:11 | CHI NoC 节点 ID 宽度 |
| `EnableCHIAsyncBridge` | Option[AsyncQueueParams] | Some(...) | CHI 异步桥接 |
| `SeperateBus` | SeperatedBusType | NONE | 分离总线类型 |
| `IMSICBusType` | IMSICBusType | AXI | IMSIC 总线类型 |

---

## 7. Debug/Validation 调试验证配置

### 7.1 FrontendDebugConfig

`FrontendDebugConfig(n: Int = 1)`（第 415-444 行）在 DefaultConfig 基础上启用前端全链路 Trace：

```scala
class FrontendDebugConfig(n: Int = 1) extends Config(
  (new DefaultConfig(n)).alter((site, here, up) => {
    case XSTileKey => up(XSTileKey).map{ p => p.copy(
      frontendParameters = p.frontendParameters.copy(
        bpuParameters = p.frontendParameters.bpuParameters.copy(
          EnableBpTrace = true,
          utageParameters = ...copy(EnableTraceAndDebug = true),
          mbtbParameters = ...copy(EnableMainbtbTrace = true),
          tageParameters = ...copy(EnableTageTrace = true),
          scParameters = ...copy(EnableScTrace = true),
        ),
        icacheParameters = ...copy(EnableTrace = true),
      )
    )}
    case DebugOptionsKey => up(DebugOptionsKey).copy(
      EnableChiselDB = true,
      EnableConstantin = true
    )
  })
)
```

该配置同时开启 `EnableChiselDB` 和 `EnableConstantin`，使得前端 BPU（Tage, UTAGE, MBTB, SC）、ICache 的 Trace 数据可以通过 ChiselDB 记录并通过 Constantin 动态控制。

### 7.2 WithFuzzer

`WithFuzzer`（第 397-413 行）禁用所有性能计数器，专用于 Fuzzing 验证：
- `EnablePerfDebug = false`
- LLC `enablePerf = false`
- L2 `enablePerf = false`

### 7.3 WithL3DebugConfig

```scala
class WithL3DebugConfig extends Config(
  OpenLLCConfig("256KB") ++ L2CacheConfig("64KB")
)
```

将 L3 缩减为 256 KB、L2 缩减为 64 KB，用于快速 L3 功能验证。

### 7.4 DebugOptions 默认值

`DebugOptions`（`xiangshan/Parameters.scala` 第 519-537 行）的默认配置：

| 选项 | 默认值 | 含义 |
|---|---|---|
| `FPGAPlatform` | false | 是否 FPGA 平台 |
| `EnableDifftest` | false | 是否启用差分测试 |
| `EnableDebug` | false | 是否启用 Debug 打印 |
| `EnablePerfDebug` | true | 是否启用性能计数器 |
| `PerfLevel` | "VERBOSE" | 性能打印级别 |
| `EnableXMR` | true | 是否启用跨模块引用 |
| `SimMemSize` | ~8 GB | 仿真内存大小 |
| `UseDRAMSim` | false | 是否使用 DRAMSim3 |
| `EnableConstantin` | false | 是否启用 Constantin |
| `EnableChiselDB` | false | 是否启用 ChiselDB |
| `AlwaysBasicDB` | true | 始终启用基本 DB |
| `EnableRollingDB` | false | 是否启用滚动 DB |
| `EnableSimFrontend` | false | 是否启用仿真前端 |

### 7.5 DFTOptions

| 选项 | 默认值 | 含义 |
|---|---|---|
| `EnableMbist` | true | 启用 MBIST（Memory BIST） |
| `EnableSramCtl` | false | 启用 SRAM 控制器 |

---

## 8. YAML 配置支持

### 8.1 YamlConfig 数据模型

`src/main/scala/top/YamlParser.scala` 定义了 `YamlConfig` case class（第 35-64 行），使用 `io.circe.generic.extras` 自动派生 JSON codec，支持从 YAML 文件反序列化。

可配置的字段（全部为 `Option` 类型，未指定则保持原值）：

| 字段 | 类型 | 影响的 Key |
|---|---|---|
| `Config` | String | 调用 `ArgParser.getConfigByName` 选择基础 Config |
| `PmemRanges` | List[MemoryRange] | `SoCParamsKey.PmemRanges` |
| `PMAConfigs` | List[PMAConfigEntry] | `SoCParamsKey.PMAConfigs` |
| `EnableCHIAsyncBridge` | Boolean | `SoCParamsKey.EnableCHIAsyncBridge` |
| `L2CacheConfig` | L2CacheConfig | L2 参数 |
| `OpenLLCConfig` | OpenLLCConfig | L3/LLC 参数 |
| `HartIDBits` | Int | `MaxHartIdBits` |
| `DebugAttachProtocals` | List[String] | `ExportDebug` |
| `DebugModuleParams` | DebugModuleParams | `DebugModuleKey` |
| `WFIResume` | Boolean | `XSTileKey.wfiResume` |
| `SeperateDM` | Boolean | `SoCParamsKey.SeperateDM` |
| `SeperateBus` | String | `SoCParamsKey.SeperateBus` |
| `UsePrivateClint` | Boolean | `SoCParamsKey.UsePrivateClint` |
| `TIMERRange` | AddressSet | `SoCParamsKey.TIMERRange` |
| `IMSICBusType` | String | `SoCParamsKey.IMSICBusType` |
| `IMSICParams` | IMSICParams | `SoCParamsKey.IMSICParams` |
| `CHIIssue` | String | `xscache.chi.CHIIssue` |
| `WFIClockGate` | Boolean | `SoCParamsKey.WFIClockGate` |
| `EnablePowerDown` | Boolean | `SoCParamsKey.EnablePowerDown` |
| `XSTopPrefix` | String | `SoCParamsKey.XSTopPrefix` |
| `EnableDFX` | Boolean | `DFTOptionsKey.EnableMbist` |
| `EnableSramCtl` | Boolean | `DFTOptionsKey.EnableSramCtl` |
| `EnableCHINS` | Boolean | `xscache.chi.NonSecureKey` |
| `CHIAddrWidth` | Int | `xscache.chi.CHIAddrWidthKey` |
| `CVMParams` | CVMParameters | `CVMParamsKey` |
| `EnableBitmapCheck` | Boolean | `XSTileKey.HasBitmapCheck` |

### 8.2 解析流程

`YamlParser.parseYaml(config, yamlFile)` 的执行流程：

1. 读取 YAML 文件内容
2. 使用 `io.circe.yaml.parser.parse` 解析为 Circe JSON
3. 将 JSON 解码为 `YamlConfig` 实例
4. 逐字段遍历，对每个 `Some(value)` 调用对应的 `config.alter()` 修改 Parameters
5. 特别地，`L2CacheConfig` 和 `OpenLLCConfig` 字段直接调用对应的 Config 对象的 `alter` 方法（因为它们本身就是 Config 类）

### 8.3 使用方式

通过命令行 `--yaml-config <file.yaml>` 指定 YAML 文件，ArgParser 会调用 `YamlParser.parseYaml`：

```bash
# 使用 YAML 配置
./build/XiangShan --config DefaultConfig --yaml-config my_soc.yaml --num-cores 2
```

YAML 文件示例结构：
```yaml
Config: "DefaultConfig"
PmemRanges:
  - base: "0x80000000"
    size: "0x80000000000"
EnableCHIAsyncBridge: true
CHIIssue: "B"
WFIClockGate: true
```

### 8.4 依赖

YAML 解析依赖 `io.circe.yaml`（circe-yaml 库），通过 `io.circe.generic.extras.auto._` 提供自动 derive。`Configuration.default.withDefaults` 配置允许缺失字段使用 Scala case class 的默认值。

---

## 9. Constantin 运行时常量

### 9.1 概述

**Constantin** 是 XiangShan 集成的运行时参数调优框架，源自 `utility.Constantin` 模块。其核心 API 为：

```scala
Constantin.createRecord("常量名", 初始值)
```

该方法在硬件 elaboration 阶段创建一个可通过外部仿真接口（通常通过 DPI-C 或 Verilog 层的常量注入机制）动态修改的寄存器。与 CDE 的编译期配置不同，Constantin 允许在 **仿真运行期间** 修改硬件行为参数，无需重新综合。

启用条件：`DebugOptionsKey.EnableConstantin = true`（通过 CLI `--with-constantin` 或在 FrontendDebugConfig 中默认开启）。

### 9.2 使用 Constantin 的文件（17 个文件，44 处调用）

以下是按功能模块组织的所有 Constantin 使用点：

#### 9.2.1 DCache 模块（6 个文件，约 12 处）

| 文件 | 常量名 | 初始值 | 功能 |
|---|---|---|---|
| `MainPipe.scala` | `StoreWaitThreshold_{HartId}` | 0 | Store 等待阈值 |
| `MissQueue.scala` | `nMaxPrefetchEntry{HartId}` | 14 | Miss Queue 最大预取条目 |
| `MissQueue.scala` | `isWriteL1MissQMissTable{HartId}` | — | 控制是否写入 Miss 统计表 |
| `DCacheWrapper.scala` | `isWriteLoadMissTable{hartId}` | — | 控制是否写入 Load Miss 表 |
| `DCacheWrapper.scala` | `isFirstHitWrite{hartId}` | — | 控制首次命中的写入行为 |
| `DCacheWrapper.scala` | `isWriteLoadAccessTable{hartId}` | — | 控制是否写入 Load Access 表 |
| `BankedDataArray.scala` | `isWriteBankConflictTable{HartId}` x2 | — | 控制 Bank 冲突表写入 |

#### 9.2.2 存储子系统（3 个文件，约 8 处）

| 文件 | 常量名 | 初始值 | 功能 |
|---|---|---|---|
| `Sbuffer.scala` | `StoreBufferThreshold_{HartId}` | 9 | Store Buffer 阈值 |
| `Sbuffer.scala` | `StoreBufferBase_{HartId}` | 4 | Store Buffer 基础值 |
| `LoadQueueReplay.scala` | `ColdDownThreshold_{HartId}` | 12 | Load Replay 冷却阈值 |
| `NewStoreQueue.scala` | `LFSTEnable` | LFSTEnable | LFST（Load-Forward Status Table）开关 |
| `NewStoreQueue.scala` | `ForceWriteUpper_{HartId}` | StoreQueueForceWriteSbufferUpper | 强制写入上界 |
| `NewStoreQueue.scala` | `ForceWriteLower_{HartId}` | StoreQueueForceWriteSbufferLower | 强制写写下界 |

#### 9.2.3 预取器子系统（6 个文件，约 15 处）

这是 Constantin 使用最密集的区域：

| 文件 | 常量名 | 初始值 | 功能 |
|---|---|---|---|
| `PrefetcherWrapper.scala` | `pf_modeStrideBerti{hartId}` | strideOnBertiOff | Stride/Berti 模式切换 |
| `PrefetcherWrapper.scala` | `pf_enableSMS{hartId}` | true | SMS 预取器开关 |
| `PrefetcherWrapper.scala` | `pf_enableL1StreamPrefetcher{hartId}` | true | L1 Stream 预取器开关 |
| `PrefetcherWrapper.scala` | `pf_enableBerti{hartId}` | true | Berti 预取器开关 |
| `Berti.scala` | `{name}_thresholdOfReset` | 6 | Berti 重置阈值 |
| `Berti.scala` | `{name}_thresholdOfUpdate` | 2 | Berti 更新阈值 |
| `Berti.scala` | `{name}_thresholdOfL1PF` | 4 | Berti L1 预取阈值 |
| `Berti.scala` | `{name}_thresholdOfL2PF` | 2 | Berti L2 预取阈值 |
| `Berti.scala` | `{name}_thresholdOfL2PFR` | 1 | Berti L2 预取比阈值 |
| `L1StreamPrefetcher.scala` | `l2DepthRatio{HartId}` | L2_DEPTH_RATIO | L2 Stream 深度比 |
| `L1StreamPrefetcher.scala` | `l3DepthRatio{HartId}` | L3_DEPTH_RATIO | L3 Stream 深度比 |
| `L1StreamPrefetcher.scala` | `streamL1Depth{HartId}` | 64 | L1 Stream 深度 |
| `L1StreamPrefetcher.scala` | `streamL2Depth{HartId}` | 640 | L2 Stream 深度 |
| `L1StreamPrefetcher.scala` | `streamL3Depth{HartId}` | 640 | L3 Stream 深度 |
| `L1StreamPrefetcher.scala` | `enableL3StreamPrefetch{HartId}` | false | L3 Stream 预取开关 |
| `L1StridePrefetcher.scala` | `always_update{HartId}` | ALWAYS_UPDATE_PRE_VADDR | Stride 总是更新 |
| `L1StridePrefetcher.scala` | `l1_stride_ratio{HartId}` | 2 | L1 Stride 比率 |
| `L1StridePrefetcher.scala` | `l2_stride_ratio{HartId}` | 5 | L2 Stride 比率 |
| `PrefetcherMonitor.scala` | `{name}_depth{HartId}` | 32 | 预取监控器深度 |
| `PrefetcherMonitor.scala` | `{name}_enableDynamicPrefetcher{HartId}` | 1 | 动态预取器开关 |

#### 9.2.4 前端（2 个文件，约 3 处）

| 文件 | 常量名 | 初始值 | 功能 |
|---|---|---|---|
| `Bpu.scala` | `constCtrl` | — | BPU 总控常量 |
| `IfuPerfAnalysis.scala` | `isWriteFetchToIBufferTable{hartId}` | — | 控制 Fetch->IBuffer 表写入 |
| `IfuPerfAnalysis.scala` | `isWriteIfuWbToFtqTable{hartId}` | — | 控制 IFU->FTQ 写回表写入 |

#### 9.2.5 TLB/MMU（1 个文件，约 5 处）

| 文件 | 常量名 | 初始值 | 功能 |
|---|---|---|---|
| `L2TLB.scala` | `isWriteL2TlbPrefetchTable{hartId}` | — | L2 TLB 预取表控制 |
| `L2TLB.scala` | `isWriteL1TlbTable{hartId}` | — | L1 TLB 表控制 |
| `L2TLB.scala` | `isWritePageCacheTable{hartId}` | — | Page Cache 表控制 |
| `L2TLB.scala` | `isWritePTWTable{hartId}` | — | PTW 表控制 |
| `L2TLB.scala` | `isWriteL2TlbMissQueueTable{hartId}` | — | L2 TLB Miss Queue 表控制 |

#### 9.2.6 后端（2 个文件，约 2 处）

| 文件 | 常量名 | 初始值 | 功能 |
|---|---|---|---|
| `Backend.scala` | `EnableMdp` | true | MDP（Memory Dependence Predictor）开关 |
| `CSR.scala` | `slvpredctl` | 0x60 | S-Level 预测控制 CSR 值 |

### 9.3 Constantin 常量命名规律

从 44 处使用点可以观察到命名模式：
1. **带 HartId 后缀**：`{ConstantName}_{HartId}` 或 `{ConstantName}{HartId}` — 多核场景下每核独立的常量
2. **带模块名前缀**：`pf_`（Prefetcher）、`stream`（Stream Prefetcher）、`isWrite`（统计表控制）
3. **纯名称**：`constCtrl`、`EnableMdp`、`LFSTEnable` — 全局共享常量

### 9.4 Constantin 的价值

Constantin 填补了 CDE 编译期配置的空白：
- **CDE Config**：在 Chisel elaboration 时确定，综合后不可变
- **Constantin**：在仿真运行时可通过测试平台（TestHarness）动态修改，用于：
  1. **性能调优**：在不重新编译的情况下调整预取器深度、阈值、比率
  2. **A/B 测试**：同一 RTL binary 上快速测试不同参数组合
  3. **调试**：动态开关统计表写入、启用/禁用特定模块功能
  4. **回归测试**：在 CI 中自动搜索最优参数组合

---

## 10. ArgParser 命令行参数处理

### 10.1 解析器架构

`ArgParser`（`src/main/scala/top/ArgParser.scala`）是一个 Scala object，核心方法为 `parse(args: Array[String])`，返回三元组 `(Parameters, Array[String], Array[String])`，分别为：
1. 完整的 Parameters 配置
2. 传递给 FIRRTL 的选项
3. 传递给 Firtool（CIRCT）的选项

解析流程：
1. 以 `DefaultConfig(1)` 作为默认配置
2. 先调用 `DifftestModule.parseArgs` 分离差分测试参数
3. 使用尾递归 `nextOption` 逐个处理参数
4. 最后附加 `LogUtilsOptions` 和 `PerfCounterOptions`

### 10.2 完整命令行参数表

| 参数 | 值类型 | 说明 | 影响的 Key |
|---|---|---|---|
| `--xs-help` | — | 打印帮助 | — |
| `--version` | — | 打印版本 | — |
| `--config` | String | 选择 Config 类名 | 替换整个配置 |
| `--issue` | String | CHI Issue 版本 | `xscache.chi.CHIIssue` |
| `--num-cores` | Int | 核数 | `XSTileKey`, `MaxHartIdBits` |
| `--hartidbits` | Int | Hart ID 位宽 | `MaxHartIdBits` |
| `--with-dramsim3` | — | 启用 DRAMSim3 | `DebugOptionsKey.UseDRAMSim` |
| `--with-chiseldb` | — | 启用 ChiselDB | `DebugOptionsKey.EnableChiselDB` |
| `--with-rollingdb` | — | 启用 RollingDB | `DebugOptionsKey.EnableRollingDB` |
| `--with-constantin` | — | 启用 Constantin | `DebugOptionsKey.EnableConstantin` |
| `--fpga-platform` | — | FPGA 模式 | `DebugOptionsKey.FPGAPlatform` |
| `--reset-gen` | — | 启用 Reset Generator | `DebugOptionsKey.ResetGen` |
| `--enable-difftest` | — | 启用差分测试 | `DebugOptionsKey.EnableDifftest` |
| `--disable-always-basic-diff` | — | 禁用 Always Basic Diff | `DebugOptionsKey.AlwaysBasicDiff` |
| `--enable-log` | — | 启用 Debug 打印 | `DebugOptionsKey.EnableDebug` |
| `--disable-perf` | — | 禁用性能计数器 | `DebugOptionsKey.EnablePerfDebug` |
| `--perf-level` | String | 性能打印级别 | `DebugOptionsKey.PerfLevel` |
| `--disable-alwaysdb` | — | 禁用 Always Basic DB | `DebugOptionsKey.AlwaysBasicDB` |
| `--enable-simfrontend` | — | 启用仿真前端 | `DebugOptionsKey.EnableSimFrontend` |
| `--xstop-prefix` | String | XSTop 前缀 | `SoCParamsKey.XSTopPrefix` |
| `--imsic-bus-type` | String | IMSIC 总线类型 | `SoCParamsKey.IMSICBusType` |
| `--enable-ns` | — | 启用 Non-Secure | `xscache.chi.NonSecureKey` |
| `--l2-cache-size` | Int (KB) | L2 缓存大小 | `XSTileKey.L2CacheParamsOpt` |
| `--l3-cache-size` | Int (KB) | L3 缓存大小 | `SoCParamsKey.OpenLLCParamsOpt` |
| `--sim-mem-size` | Long (GB) | 仿真内存大小 | `DebugOptionsKey.SimMemSize` |
| `--dfx` | Boolean | DFT MBIST 开关 | `DFTOptionsKey.EnableMbist` |
| `--sram-with-ctl` | — | SRAM 控制器 | `DFTOptionsKey.EnableSramCtl` |
| `--seperate-bus` | String | 分离总线类型 | `SoCParamsKey.SeperateBus` |
| `--private-clint` | Boolean | 私有 CLINT | `SoCParamsKey.UsePrivateClint` |
| `--seperate-dm` | — | 分离 Debug Module | `SoCParamsKey.SeperateDM` |
| `--chi-addr-width` | Int | CHI 地址宽度 | `xscache.chi.CHIAddrWidthKey` |
| `--wfi-resume` | Boolean | WFI 恢复 | `XSTileKey.wfiResume` |
| `--disable-xmr` | — | 禁用 XMR | `DebugOptionsKey.EnableXMR` |
| `--yaml-config` | String (file) | YAML 配置文件 | YamlParser 处理 |
| `--dump-csr` | — | 导出 CSR | `DebugOptionsKey.DumpCSR` |
| `--firtool-opt` | String | Firtool 额外选项 | 收集到 firtoolOpts |

### 10.3 配置选择机制

`getConfigByName(confString: String)` 通过 Java 反射动态加载 Config 类：

```scala
def getConfigByName(confString: String): Parameters = {
  var prefix = "top."
  if(confString.contains('.')) prefix = ""
  val c = Class.forName(prefix + confString).getConstructor(Integer.TYPE)
  c.newInstance(1.asInstanceOf[Object]).asInstanceOf[Parameters]
}
```

这意味着用户可以传入任意 `top` 包下的 Config 类名，如 `--config MinimalConfig`、`--config XSNoCTopConfig` 等。

### 10.4 L2/L3 缓存大小的动态调整

`--l2-cache-size` 和 `--l3-cache-size` 参数实现了运行时缓存大小调整，需要根据 banks 和 ways 重新计算 sets：

- L2: `sets = value.toInt * 1024 / banks / ways / 64`
- L3: `sets = value.toInt * 1024 / banks / llc.ways / 64`

---

## 11. 多核配置

### 11.1 --num-cores 参数

ArgParser 中的 `--num-cores` 处理（第 86-93 行）：

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

关键行为：
1. **复制第一个核的参数**：使用 `up(XSTileKey).head.copy(HartId = i)` — 所有核共享相同的微架构参数，仅 HartId 不同
2. **自适应 HartId 位宽**：`MaxHartIdBits = log2Up(n) max 原值`
3. **顺序编号**：HartId 从 0 开始连续编号

### 11.2 多核配置示例

```bash
# 双核配置
./build/XiangShan --num-cores 2 --config DefaultConfig

# 四核 NoC 配置
./build/XiangShan --num-cores 4 --config XSNoCTopConfig --yaml-config quad_core.yaml

# 六核 Minimal 配置（用于快速仿真验证）
./build/XiangShan --num-cores 6 --config MinimalConfig
```

### 11.3 多核下的 Constantin 命名

Constantin 常量通过 HartId 后缀区分每核实例，如 `StoreBufferThreshold_0`、`StoreBufferThreshold_1` 等，使得每核的运行时参数可以独立调整。

### 11.4 SoCParameters 中的核数相关字段

- `NumHart: Int = 64` — SoC 最大支持的 Hart 数
- `MaxHartIdBits` — 由 `log2Up(n) max 6` 决定

---

## 12. 源文件索引

### 12.1 核心配置文件

| 文件路径 | 说明 |
|---|---|
| `src/main/scala/top/Configs.scala` | 所有 Config 类定义（~620 行） |
| `src/main/scala/top/ArgParser.scala` | 命令行参数解析（~260 行） |
| `src/main/scala/top/YamlParser.scala` | YAML 配置解析（~220 行） |

### 12.2 参数定义文件

| 文件路径 | 说明 |
|---|---|
| `src/main/scala/xiangshan/Parameters.scala` | `XSCoreParameters`（第 48 行）、`DebugOptions`（第 519 行）定义 |
| `src/main/scala/system/SoC.scala` | `SoCParameters`（第 52 行）、`CVMParameters`（第 43 行）定义 |

### 12.3 Top 模块文件

| 文件路径 | 说明 |
|---|---|
| `src/main/scala/top/Top.scala` | 顶层模块（Crossbar 互联） |
| `src/main/scala/top/XSNoCTop.scala` | NoC 互联顶层模块 |
| `src/main/scala/top/Generator.scala` | Chisel 生成器入口 |
| `src/main/scala/top/XiangShanStage.scala` | 构建阶段处理 |

### 12.4 Constantin 使用文件（17 个）

| 文件路径 | Constantin 调用数 |
|---|---|
| `src/main/scala/xiangshan/mem/prefetch/L1StreamPrefetcher.scala` | 5 |
| `src/main/scala/xiangshan/mem/prefetch/Berti.scala` | 5 |
| `src/main/scala/xiangshan/mem/prefetch/L1StridePrefetcher.scala` | 3 |
| `src/main/scala/xiangshan/mem/prefetch/PrefetcherWrapper.scala` | 4 |
| `src/main/scala/xiangshan/mem/prefetch/PrefetcherMonitor.scala` | 2 |
| `src/main/scala/xiangshan/cache/dcache/DCacheWrapper.scala` | 3 |
| `src/main/scala/xiangshan/cache/dcache/data/BankedDataArray.scala` | 2 |
| `src/main/scala/xiangshan/cache/dcache/mainpipe/MainPipe.scala` | 1 |
| `src/main/scala/xiangshan/cache/dcache/mainpipe/MissQueue.scala` | 2 |
| `src/main/scala/xiangshan/cache/mmu/L2TLB.scala` | 5 |
| `src/main/scala/xiangshan/mem/lsqueue/LoadQueueReplay.scala` | 1 |
| `src/main/scala/xiangshan/mem/lsqueue/NewStoreQueue.scala` | 3 |
| `src/main/scala/xiangshan/mem/sbuffer/Sbuffer.scala` | 2 |
| `src/main/scala/xiangshan/frontend/bpu/Bpu.scala` | 1 |
| `src/main/scala/xiangshan/frontend/ifu/IfuPerfAnalysis.scala` | 2 |
| `src/main/scala/xiangshan/backend/Backend.scala` | 1 |
| `src/main/scala/xiangshan/backend/fu/CSR.scala` | 1 |

**总计：17 个文件，44 处 Constantin.createRecord 调用**

---

## 总结

XiangShan 的配置系统展现了一个成熟的处理器配置方法论：

1. **编译期**：通过 CDE Config 组合实现宏观架构参数化（缓存大小、流水线宽度、队列深度），覆盖从 MinimalConfig（仿真优化）到 DefaultConfig（全功能）再到 FpgaDefaultConfig（资源受限）的多种场景。

2. **构建期**：通过 ArgParser 的 30+ 命令行参数和 YamlParser 的 25+ YAML 字段，在不修改源码的情况下精细调整 SoC 级参数。

3. **运行期**：通过 Constantin 框架的 44 个可调常量，在仿真过程中动态微调预取器参数、阈值、统计表控制等微架构行为。

这种三层配置体系（Config -> CLI/YAML -> Constantin）使得同一份 RTL 源码能够服务于多种使用场景——从快速功能验证（MinimalConfig + Constantin 无性能计数器），到 FPGA 原型验证（FpgaDefaultConfig + 小缓存），到多核 SoC 集成（XSNoCTopConfig + num-cores），再到深度性能调优（DefaultConfig + Constantin 动态参数搜索）。
