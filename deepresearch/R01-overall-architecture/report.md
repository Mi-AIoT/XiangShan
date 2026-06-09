# XiangShan RISC-V 处理器总体架构研究报告

> 研究主题: R01 - Overall Architecture & SoC Integration
> 代码版本: Kunminghu (昆明湖)
> 报告日期: 2026-06-09

---

## 目录

1. [项目概述](#1-项目概述)
2. [顶层模块层次结构](#2-顶层模块层次结构)
3. [配置参数系统](#3-配置参数系统)
4. [SoC集成与总线架构](#4-soc集成与总线架构)
5. [处理器核心架构: XSTile与XSCore](#5-处理器核心架构-xstile与xscore)
6. [流水线子系统: Frontend、Backend、MemBlock](#6-流水线子系统)
7. [L2缓存与L3(LLC)层次](#7-l2缓存与l3llc层次)
8. [多核支持机制](#8-多核支持机制)
9. [虚拟设备与外设](#9-虚拟设备与外设)
10. [Rocket Chip依赖关系](#10-rocket-chip依赖关系)
11. [关键设计决策与模式](#11-关键设计决策与模式)
12. [总结](#12-总结)

---

## 1. 项目概述

XiangShan（香山）是由中国科学院计算技术研究所和北京开源芯片联盟（BOSC）开发的开源高性能RISC-V处理器，采用Chisel（Scala）硬件描述语言实现。当前版本代号为"昆明湖"（Kunminghu），支持RV64GCXH指令集，包括完整的整数、乘除法、原子、浮点、向量（RVV 1.0）和Hypervisor扩展。

XiangShan采用超标量、乱序执行（Out-of-Order）微架构，支持宽阔的前端和后端流水线（默认8-wide decode/rename/commit），配合复杂的分支预测、多级缓存层次（L1I/L1D + L2 + L3/LLC）和CHI（Coherent Hub Interface）协议的片上网络接口。

源代码组织结构如下：

```
src/main/scala/
  top/         -- 顶层SoC模块、配置定义、命令行参数解析、生成器入口
  system/      -- SoC系统级集成（MemMisc、SoCMisc）、总线互连、参数定义
  xiangshan/   -- 处理器核心逻辑
    frontend/  -- 前端（BPU、ICache、IFU、FTQ、IBuffer）
    backend/   -- 后端（CtrlBlock、IssueQueue、ExeUnit、ROB、RegisterFile）
    mem/       -- 访存单元（MemBlock、Load/Store Queue、SBuffer、Prefetcher）
    cache/     -- 缓存子系统（DCache、MMU/TLB、WPU）
    transforms/-- Chisel变换工具
  device/      -- 虚拟外设（UART、Timer、PLIC、RAM、VGA等）
  utils/       -- 通用Chisel工具函数
```

---

## 2. 顶层模块层次结构

### 2.1 模块层次总览

XiangShan的顶层模块层次采用三层封装结构，从外到内分别为：

```
+------------------------------------------------------------------+
|  XSTop / XSNoCTop                                                 |
|  (src/main/scala/top/Top.scala, XSNoCTop.scala)                   |
|                                                                    |
|  包含: MemMisc/SoCMisc (外设互连) + N个 core_with_l2               |
|        + OpenLLC (L3) + DebugModule + PLIC + Timer                 |
+------------------------------------------------------------------+
        |                        |                        |
        v                        v                        v
+----------------+    +----------------+    +------------------+
| XSTileWrap     |    | XSTileWrap     |    | XSTileWrap       |
| (XSNoCTop模式) |    |                |    | (可选)           |
+----------------+    +----------------+    +------------------+
        |                        |
        v                        v
+----------------+    +----------------+
| XSTile         |    | XSTile         |
|  XSCore        |    |  XSCore        |
|  L2Top(L2Cache)|    |  L2Top(L2Cache)|
+----------------+    +----------------+
        |
        v
+------------------------------------------------------------------+
|  XSCore (src/main/scala/xiangshan/XSCore.scala)                  |
|                                                                    |
|  +-----------+    +-----------+    +------------+                  |
|  | Frontend  |--->|  Backend  |--->|  MemBlock  |                  |
|  | (BPU,     |    | (Ctrl,    |    | (LQ, SQ,   |                  |
|  |  IFU,     |    |  Issue,   |    |  DCache,   |                  |
|  |  ICache,  |    |  Exe,     |    |  TLB,      |                  |
|  |  FTQ,     |    |  ROB)     |    |  Prefetch) |                  |
|  |  IBuffer) |    |           |    |            |                  |
|  +-----------+    +-----------+    +------------+                  |
+------------------------------------------------------------------+
```

### 2.2 顶层模块类型

XiangShan提供多种顶层封装，适应不同使用场景（参见 `src/main/scala/top/Top.scala` 第408-447行的 `TopMain` 对象）：

| 顶层类型 | 文件 | 描述 |
|---------|------|------|
| `XSTop` | Top.scala:84 | 使用TileLink内部总线的传统SoC封装，含L3/LLC和完整外设 |
| `XSNoCTop` | XSNoCTop.scala:470 | 单核NoC封装，使用CHI协议对外接口，支持异步时钟域，适用于SoC集成 |
| `XSNoCDiffTop` | XSNoCDiffTop (XSNoCTop.scala:500) | 在XSNoCTop基础上增加DiffTest验证接口 |
| `XSTileDiffTop` | Top.scala:396 | 在XSTop基础上增加DiffTest验证接口 |

**TopMain入口**（Top.scala:408-447）根据配置中的 `UseXSNoCTop`、`UseXSNoCDiffTop`、`UseXSTileDiffTop` 标志位选择对应的顶层模块，并通过 `Generator.execute` 完成Verilog生成。

### 2.3 BaseXSSoc抽象基类

所有SoC顶层继承自 `BaseXSSoc`（Top.scala:48-74），该抽象类混入了 `HasSoCParameter` 和 `BindingScope` trait，负责：

- 收集 TileLink manager 节点信息用于 DTS（Device Tree Source）生成
- 绑定 `ResourceBinding` 信息以生成设备树
- 声明 `tlManagers` 列表供子类实现

---

## 3. 配置参数系统

XiangShan使用Chipsalliance CDE（Context-Dependent Environments）配置系统，核心概念为 `Field[T]` + `Config` + `Parameters`。

### 3.1 关键配置Key

定义在 `src/main/scala/system/SoC.scala` 和 `src/main/scala/xiangshan/Parameters.scala` 中：

| Key | 类型 | 定义位置 | 描述 |
|-----|------|---------|------|
| `SoCParamsKey` | `SoCParameters` | SoC.scala:40 | SoC级参数（地址映射、总线宽度、外设范围等） |
| `CVMParamsKey` | `CVMParameters` | SoC.scala:41 | 内存加密（CVM）参数 |
| `XSTileKey` | `Seq[XSCoreParameters]` | Parameters.scala:44 | 每个核的参数序列 |
| `XSCoreParamsKey` | `XSCoreParameters` | Parameters.scala:46 | 单核参数（在XSTile内部override） |
| `DebugOptionsKey` | `DebugOptions` | xiangshan/package.scala | 调试选项（FPGA平台、difftest使能等） |
| `DFTOptionsKey` | `DFTOptions` | xiangshan/package.scala | DFT（MBIST、SRAM控制）选项 |
| `PMParameKey` | `PMParameters` | xiangshan/PMParameters.scala | 电源管理参数 |

### 3.2 Config层次继承

配置通过 `++` 操作符组合（CDE标准模式），形成了清晰的继承链：

```
BaseConfig(n)  -- 基础参数（XLen=64, DebugOptions, SoCParams, XSTileKey含n个核）
    |
    +-- MinimalConfig(n)  -- 最小配置（小L1D=32KB, 小L2=128KB, 小L3=4MB, 窄流水线8-wide）
    |     |
    |     +-- XSNoCTopMinimalConfig / XSNoCDiffTopMinimalConfig
    |
    +-- DefaultConfig(n)  -- 默认/标准配置（L1D=64KB, L2=2MB, L3=16MB, 宽流水线8-wide）
          |
          +-- XSNoCTopConfig / XSNoCDiffTopConfig
          +-- FpgaDefaultConfig / FpgaDiffDefaultConfig
          +-- BackendV2Config  -- 后端V2变体（6-wide decode/rename）
```

**配置组合示例**（Configs.scala:533-538）：

```scala
class DefaultConfig(n: Int = 1) extends Config(
  OpenLLCConfig("16MB", ways = 16, banks = 4)   // L3 LLC配置
    ++ L2CacheConfig("2MB", inclusive = true, banks = 4, tp = false)  // L2配置
    ++ WithNKBL1D(64, ways = 4)                   // L1D数据缓存配置
    ++ new BaseConfig(n)                           // 基础配置
)
```

### 3.3 SoCParameters关键字段

定义在 `src/main/scala/system/SoC.scala:52-133`：

```scala
case class SoCParameters(
  PAddrBits: Int = 48,                           // 物理地址位宽
  PmemRanges: Seq[MemoryRange] = ...,           // 物理内存范围
  PMAConfigs: Seq[PMAConfigEntry] = ...,        // Physical Memory Attribute配置
  L3NBanks: Int = 4,                             // L3 bank数量
  OpenLLCParamsOpt: Option[OpenLLCParam] = None, // L3 LLC参数
  NumHart: Int = 64,                             // 最大支持Hart数量
  UseXSNoCTop: Boolean = false,                  // 是否使用NoC顶层
  EnableCHIAsyncBridge: Option[AsyncQueueParams] = Some(...),  // CHI异步桥
  EnableClintAsyncBridge: Option[AsyncQueueParams] = Some(...), // CLINT异步桥
  WFIClockGate: Boolean = false,                 // WFI时钟门控
  EnablePowerDown: Boolean = false,              // 低功耗关断支持
  // ... 更多外设地址范围定义
)
```

### 3.4 XSCoreParameters关键字段

定义在 `src/main/scala/xiangshan/Parameters.scala:48-290`，这是一个极为庞大的参数类，涵盖：

- **ISA配置**: XLEN=64, VLEN=128, ELEN=64, 扩展支持(M/A/F/D/C/V/H)
- **流水线宽度**: DecodeWidth=8, RenameWidth=8, CommitWidth=8
- **ROB/RAB**: RobSize=352, RabSize=352
- **Issue Queue**: IssueQueueSize=20, IssueQueueCompEntrySize=12
- **Load/Store Queue**: VirtualLoadQueueSize=72, StoreQueueSize=56
- **物理寄存器**: IntPreg=224, FpPreg=256, VfPreg=128
- **TLB配置**: itlb/ldtlb/sttlb/hytlb/pftlb/btlb (NWays=48)
- **L1D Cache**: DCacheParameters (通过 WithNKBL1D 可配置size)
- **L2 Cache**: L2CacheParamsOpt, L2NBanks
- **前端参数**: frontendParameters (BPU/FTQ/ICache/IBuffer参数)
- **执行单元配置**: intSchdParams, fpSchdParams, vecSchdParams (IssueBlockParams详细定义)

---

## 4. SoC集成与总线架构

### 4.1 SoC总线拓扑

XiangShan支持两种SoC集成模式：

#### 模式一: XSTop (TileLink内部总线)

```
+-------+   +-------+   +-------+
| Core0 |   | Core1 |   | CoreN |
| +L2   |   | +L2   |   | +L2   |
+---+---+   +---+---+   +---+---+
    |           |           |
    v           v           v
+---------------------------------+
|    l3_banked_xbar (TLXbar)      |
+---------------------------------+
            |
    +-------+-------+
    v               v
+--------+   +------------+
| l3_in  |   | peripheral |
| (TL)   |   | Xbar (TL)  |
+--------+   +------------+
    |              |
    v              v
+--------+   +------------+
| L3/LLC |   | DebugModule|
| (Open  |   | PLIC       |
|  LLC)  |   | Timer      |
+--------+   | UART       |
    |         | SYSCNT     |
    v         | PMA        |
+--------+   +------------+
| MemAXI4|       ^
| Slave  |       |
+--------+   (AXI4转换)
```

在非CHI模式下（`enableCHI=false`），Core通过TileLink连接到 `l3_banked_xbar`，经过bank绑定（BankBinder）、Cork协议转换后连接到AXI4 Memory端口。Peripherals通过独立的 `peripheralXbar` 连接到AXI4 Peripheral端口。

#### 模式二: XSNoCTop (CHI片上网络)

```
+-------+   +-------+   +-------+
| Core0 |   | Core1 |   | CoreN |
| +L2   |   | +L2   |   | +L2   |
+---+---+   +---+---+   +---+---+
    |           |           |
    v           v           v
  CHI Protocol (PortIO)
    |           |           |
    v           v           v
+---------------------------------+
|     CHI Async Bridge (可选)      |
+---------------------------------+
            |
    +-------+-------+
    v               v
+--------+   +-------------+
| OpenLLC|   | soc_xbar    |
| (L3)   |   | (AXI4Xbar)  |
+--------+   +-------------+
    |               |
    v               v
+--------+   +------------+
| AXI4   |   | Peripheral |
| Memory |   | AXI4       |
+--------+   +------------+
```

XSNoCTop模式下（XSNoCTop.scala:470-498），每个Core的L2通过CHI协议连接到OpenLLC（L3），再通过 `OpenNCB`（CHI-to-AXI桥接）连接到SoC级的 `soc_xbar`（AXI4Xbar）。这种模式更适合多核SoC集成。

### 4.2 MemMisc与SoCMisc

`MemMisc`（SoC.scala:435-637）是SoC外设管理的核心LazyModule，混入了：

- `HaveAXI4MemPort` -- AXI4 Memory端口（SoC.scala:279-358）
- `HaveAXI4PeripheralPort` -- AXI4 Peripheral端口（SoC.scala:360-433）
- `PMAConst` -- Physical Memory Attribute常量

`SoCMisc`（SoC.scala:639-640）在 `MemMisc` 基础上额外混入 `HaveSlaveAXI4Port`，提供DMA从端口。

MemMisc实例化的关键组件包括：

| 组件 | 类型 | 地址范围 | 功能 |
|------|------|---------|------|
| `plic` | TLPLIC | 0x3C000000 | 平台级中断控制器 |
| `timer` | TIMER | 0x38000000 | 机器模式定时器 |
| `syscnt` | SYSCNT | 0x38040000 | 系统计数器 |
| `debugModule` | DebugModule | 0x38020000 | JTAG调试模块 |
| `pma` | TLPMA | - | Physical Memory Attribute控制 |
| `pll_node` | TLRegisterNode | 0x3A000000 | PLL控制寄存器 |

### 4.3 外设地址映射

根据 `HasPeripheralRanges` trait（SoC.scala:194-225）和 `SoCParameters`（SoC.scala:52-133）：

```
地址空间映射 (48-bit physical):
0x800000000000  +---------------------------+
                |   Main Memory (DRAM)      |
0x80000000      +---------------------------+
0x40600000      | UARTLite                   |
0x3B000000      | IMSIC (SG)                 |
0x3A800000      | IMSIC (M)                  |
0x3A000000      | PLL Control                |
0x39002000      | Reserved                   |
0x39000000      | Reserved                   |
0x38040000      | SYSCNT                     |
0x38030000      | MEMENC (可选)              |
0x38022000      | DCache Control             |
0x38021000      | ICache Control (executable)|
0x38020000      | Debug Module (DM)          |
0x38010000      | BEU (Bus Error Unit)       |
0x38000000      | Timer                      |
0x30050000      | GPU Space (可选)           |
0x30010000      | Reserved                   |
0x20000000      | ROM (executable)           |
0x10000000      | MMIO                       |
0x00000000      | Unmapped / Error           |
```

### 4.4 AXI4 Memory端口

`HaveAXI4MemPort` trait（SoC.scala:279-358）定义了Memory接口：

- 支持48位物理地址，排除低1GB的MMIO空间
- 默认256-bit数据总线宽度（`L3OuterBusWidth = 256`）
- 在CHI模式下，通过 `soc_xbar` (AXI4Xbar) 直接连接
- 在TL模式下，经过 `TLCacheCork` -> `TLToAXI4` -> `TLSourceShrinker` -> `TLWidthWidget` 转换链
- 支持可选的 `AXI4MemEncrypt` 内存加密模块

### 4.5 AXI4 Peripheral端口

`HaveAXI4PeripheralPort` trait（SoC.scala:360-433）定义了Peripheral接口：

- 覆盖所有外设地址空间，不包含memory和MMIO地址
- 额外包含 UARTLite (0x40600000) 和 UART16550 (0x310b0000) 两个串口设备
- 通过 `TLToAXI4` 转换器连接内部TileLink总线到外部AXI4

---

## 5. 处理器核心架构: XSTile与XSCore

### 5.1 XSTile -- Tile级封装

`XSTile`（src/main/scala/xiangshan/XSTile.scala:34-229）是包含单个处理器核心及其私有L2缓存的Tile级封装，包含两个LazyModule：

1. **core** -- `LazyModule(new XSCore())` -- 处理器核心
2. **l2top** -- `LazyModule(new L2Top())` -- L2缓存及相关互连

XSTile内部的L1到L2连接拓扑（XSTile.scala:61-80）：

```
XSCore.MemBlock.dcache ---[l1d_to_l2_buffer]--->
    [l1d_logger] --- [misc_l2_pmu] ---> L2Top.l1_xbar

XSCore.Frontend.icache ---[l1i_logger]--->
    [misc_l2_pmu] ---> L2Top.l1_xbar

XSCore.MemBlock.ptw ---[ptw_to_l2_buffer]--->
    [ptw_logger] --- [misc_l2_pmu] ---> L2Top.l1_xbar
```

三路L1请求（DCache、ICache、PTW）在L2Top内部的 `l1_xbar` (TLXbar) 汇聚，经过 `xbar_l2_buffer` 缓冲后送入L2 Cache。

### 5.2 XSCore -- 处理器核心

`XSCore`（src/main/scala/xiangshan/XSCore.scala:77-81）继承自 `XSCoreBase`，实例化三大子系统：

```scala
class XSCoreBase extends LazyModule with HasXSParameter {
  val frontend = LazyModule(new Frontend())     // 前端子系统
  val backend  = LazyModule(new Backend(...))   // 后端子系统
  val memBlock = LazyModule(new MemBlock)        // 访存子系统
}
```

三个子系统之间的连接关系（XSCore.scala:132-275）极为复杂，主要数据流：

**Frontend -> Backend:**
- `frontend.io.backend <> backend.io.frontend` -- 取指结果送入后端
- `frontend.io.sfence` / `tlbCsr` / `csrCtrl` / `fencei` -- 控制信号

**Backend <-> MemBlock:**
- `backend.io.mem.lsqEnqIO <> memBlock.io.ooo_to_mem.enqLsq` -- ROB提交到LSQ
- `backend.io.mem.memoryViolation` -- 内存违例（store-load forwarding错误）
- `backend.io.mem.wakeup` / `intWriteback` / `vecWriteback` -- 写回数据
- `memBlock.io.ooo_to_mem.intIssue` / `vecIssue` -- 执行发射到访存单元

**Frontend -> MemBlock:**
- `memBlock.io.fetch_to_mem.itlb <> frontend.io.ptw` -- ITLB PTW请求

**MemBlock -> Backend:**
- `memBlock.io.mem_to_ooo.memoryViolation` -- 内存违例反馈给ROB
- `memBlock.io.mem_to_ooo.staIqFeedback` / `lduIqFeedback` 等 -- Issue Queue反馈

### 5.3 XSTileWrap -- 时钟域隔离封装

`XSTileWrap`（src/main/scala/xiangshan/XSTileWrap.scala:37-210）在XSNoCTop模式下使用，提供：

- **异步时钟域隔离**: Core时钟域与SoC/CHI时钟域分离
- **中断同步**: 通过 `IntBuffer(3, cdc=true)` 进行跨时钟域中断同步
- **CHI异步桥**: `CHIAsyncBridgeSource` 将CHI信号从Core时钟域同步到NoC时钟域
- **CLINT异步桥**: `AsyncQueueSink` 跨时钟域传递timer值
- **MSI信息同步**: 使用 `AsyncResetSynchronizerShiftReg` 进行3级同步
- **低功耗接口**: `PowerSwitchBuffer` 处理power down握手信号
- **本地Timer支持**: 当 `UsePrivateClint=true` 时实例化私有TIMER

---

## 6. 流水线子系统

### 6.1 Frontend（前端）

前端子系统定义在 `src/main/scala/xiangshan/frontend/` 目录下，核心组件：

| 组件 | 文件 | 功能 |
|------|------|------|
| BPU | bpu/Bpu.scala | 分支预测单元，含TAGE/SC/ITTAGE/uTAGE/MBTB/RAS等 |
| IFU | ifu/Ifu.scala | 指令取指单元 |
| ICache | icache/ICache.scala | 指令缓存 |
| FTQ | ftq/Ftq.scala | Fetch Target Queue |
| IBuffer | ibuffer/IBuffer.scala | 指令缓冲 |
| InstrUncache | instruncache/ | 非缓存指令取指 |

前端流水线（Frontend.scala:132-199）：

```
PC/ResetVector --> [BPU 预测] --> [FTQ 填充] --> [IFU 取指]
                                                    |
                                              [ICache 查找]
                                                    |
                                              [ITLB 地址翻译]
                                                    |
                                              [IBuffer 缓冲]
                                                    |
                                              --> Backend (解码)
```

默认配置下前端宽度：
- FetchBlockSize = 64 bytes
- FTQ Size = 64 entries
- ICache = 64KB (256-set, 4-way, 64B block)
- IBuffer Size = 32 entries

### 6.2 Backend（后端）

后端子系统定义在 `src/main/scala/xiangshan/backend/` 目录下，采用基于Tomasulo算法的乱序执行架构。

后端核心模块层次（Backend.scala:178-249）：

```
Backend
  +-- CtrlBlock (src/main/scala/xiangshan/backend/ctrlblock/)
  |     +-- Decode (decode/)
  |     +-- Rename (rename/)
  |     +-- Dispatch (dispatch/)
  |     +-- ROB (rob/)
  |     +-- RAT (Register Alias Table)
  |
  +-- Region(intSchdParams)  -- 整数调度区域
  |     +-- IssueBlock x 14  -- 14个整数Issue Queue
  |     +-- ExeUnit (ALU0-5, BJU0-2, LDU0-2, STA0-1, STD0-1)
  |
  +-- Region(fpSchdParams)   -- 浮点调度区域
  |     +-- IssueBlock x 3   -- 3个浮点Issue Queue
  |     +-- ExeUnit (FEX0-2)
  |
  +-- Region(vecSchdParams)  -- 向量调度区域
  |     +-- IssueBlock       -- 向量Issue Queue
  |     +-- ExeUnit (VEX)
  |
  +-- RegisterFile (regfile/)
  |     +-- IntPreg (224 entries, 4-bank)
  |     +-- FpPreg  (256 entries)
  |     +-- VfPreg  (128 entries)
  |
  +-- RegCache (regcache/)
```

**默认配置下的执行单元详细配置**（Parameters.scala:327-386）：

| 执行单元 | 功能单元 | 写回端口 | 读端口 | Issue Queue大小 |
|----------|---------|---------|--------|----------------|
| ALU0 | ALU, CSR, Fence | IntWB(0) | 2xIntRD | 18 (10 comp) |
| BJU0 | Branch, Jump | - | 2xIntRD | (共享) |
| ALU1 | ALU, Div | IntWB(1) | 2xIntRD | 18 (10 comp) |
| BJU1 | Branch, Jump | - | 2xIntRD | (共享) |
| ALU2 | ALU, I2F, VSet | IntWB(2)+VfWB+V0WB+FpWB | 2xIntRD | 18 (10 comp) |
| BJU2 | Branch, Jump | - | 2xIntRD | (共享) |
| ALU3 | ALU, Bku | IntWB(3) | 2xIntRD | 20 (12 comp) |
| ALU4 | ALU, Mul | IntWB(4) | 2xIntRD | 20 (12 comp) |
| ALU5 | ALU, Mul | IntWB(5) | 2xIntRD | 20 (12 comp) |
| LDU0-2 | Load | IntWB+FpWB | 1xIntRD | 20 (12 comp) |
| STA0-1 | StoreAddr, MOU | FakeIntWB | 1xIntRD | 16 (12 comp) |
| STD0-1 | StoreData, MOUD | - | 1xIntRD+FpRD | 16 (12 comp) |

**后端V2配置**（Configs.scala:446-495）提供了6-wide的备选方案，调整decode/rename/commit宽度为6，并修改部分寄存器文件大小。

### 6.3 MemBlock（访存子系统）

`MemBlock`（src/main/scala/xiangshan/mem/MemBlock.scala）是处理所有访存操作的子系统，包含：

- **Load/Store Queue** (`lsqueue/`) -- 精确异常所需的加载/存储队列
- **SBuffer** (`sbuffer/`) -- 存储缓冲，合并写入
- **DCache** (`cache/dcache/`) -- L1数据缓存
- **TLB** (`cache/mmu/`) -- 多个独立TLB实例（ldtlb, sttlb, hytlb, pftlb）
- **Prefetcher** (`mem/prefetch/`) -- L1预取器（StreamStride, Berti, SMS）
- **MDP** (`mdp/`) -- Memory Dependence Prediction

MemBlock参数（MemBlock.scala:48-79）：

```
LoadPipelineWidth  = 3    -- 3路load流水线
StorePipelineWidth = 2    -- 2路store流水线
VecLoadPipelineWidth = 2  -- 2路向量load流水线
VecStorePipelineWidth = 2 -- 2路向量store流水线
LduCnt = 3, StaCnt = 2, StdCnt = 2, HyuCnt = 2
VlduCnt = 2, VstuCnt = 2
```

---

## 7. L2缓存与L3(LLC)层次

### 7.1 L2缓存

L2缓存由 `L2Top`（src/main/scala/xiangshan/L2Top.scala:62-400）封装，使用 `CoupledL2`（来自 `xscache.coupledL2` 库）实现。

L2 Top内部拓扑（L2Top.scala:78-159）：

```
l1_xbar (TLXbar)
    |
    v
xbar_l2_buffer (TLBuffer)
    |
    v
L2 Cache (CoupledL2) -- 通过 L2Param 配置
    |
    +--> l2_binder (BankBinder) --> memory_port (TLIdentityNode)  [到SoC L3]
    |
    +--> mmioNode --> mmio_port (TLIdentityNode)  [到MMIO]
```

默认L2配置：
- 大小: 2MB (DefaultConfig) / 128KB (MinimalConfig)
- 组相联: 8-way
- Banks: 4 (DefaultConfig) / 2 (MinimalConfig)
- 包含: Inclusive L2 (必须包含L1D数据)
- ECC: tagECC = "secded", dataECC = "secded"
- 预取: BOP (Best Offset Prefetcher), 可选TP/NL

L2还支持TLB协同（L2 TLB请求），以及top-down分析用的miss匹配信号。

### 7.2 L3 / OpenLLC

L3缓存使用 `OpenLLC`（来自 `xscache.openLLC` 库），在 `XSTop` 模式下作为共享末级缓存：

- 大小: 16MB (DefaultConfig) / 4MB (MinimalConfig)
- 组相联: 16-way (DefaultConfig) / 8-way (MinimalConfig)
- Banks: 4 (DefaultConfig) / 1 (MinimalConfig)
- 通过 `OpenNCB` (CHI-to-AXI桥) 连接到AXI4 Memory端口

在 `XSNoCTop` 模式下（Top.scala:240-349），OpenLLC在 `XSTopImp` 的Module级别实例化，接收所有Core的CHI请求，通过 `chi_llcBridge_opt` 转换为AXI4访问外部存储器。

---

## 8. 多核支持机制

### 8.1 多核参数

多核通过 `BaseConfig(n)` 的参数 `n` 控制（Configs.scala:53-74）：

```scala
class BaseConfig(n: Int) extends Config((site, here, up) => {
  case XSTileKey => Seq.tabulate(n){ i => XSCoreParameters(HartId = i) }
  case MaxHartIdBits => log2Up(n) max 6
  // ...
})
```

每个核获得唯一的 `HartId`（0到n-1），`MaxHartIdBits` 自动计算。

### 8.2 XSTop多核实例化

在 `XSTop`（Top.scala:84-394）中，多核实例化逻辑：

```scala
val core_with_l2 = tiles.map(coreParams =>
  LazyModule(new XSTile()(p.alter((site, here, up) => {
    case XSCoreParamsKey => coreParams
  })))
)
```

每个核的中断和总线连接（Top.scala:138-158）：

```scala
for (i <- 0 until NumCores) {
  core_with_l2(i).clint_int_node := misc.timer.intnode      // Timer中断
  core_with_l2(i).plic_int_node :*= misc.plic.intnode       // 外部中断
  core_with_l2(i).debug_int_node := misc.debugModule...     // 调试中断
  core_with_l2(i).nmi_int_node := nmiIntNode                // NMI
  misc.plic.intnode := IntBuffer() := core_with_l2(i).beu_int_source  // BEU错误中断
  // ...
  core_with_l2(i).memory_port.foreach(port =>
    misc.core_to_l3_ports.get(i) :=* port)  // Memory端口连接到L3
}
```

### 8.3 XSNoCTop多核连接

在 `XSNoCTop` 模式下，多核通过CHI协议连接（XSNoCTop.scala:470-498）：

每个核独立拥有 `HasXSTile` trait混入的中断节点（clintIntNode, debugIntNode, plicIntNode, nmiIntNode），通过 `IntBuffer(3, cdc=true)` 进行跨时钟域同步后传入 `XSTileWrap`。

CHI协议下每个Core被分配独立的Node ID（Top.scala:354-359）：

```scala
core_with_l2.zipWithIndex.foreach { case (tile, i) =>
  tile.module.io.nodeID.foreach(_ := i.U)
}
```

---

## 9. 虚拟设备与外设

### 9.1 设备目录结构

`src/main/scala/device/` 包含以下虚拟设备：

| 设备 | 文件 | 协议 | 功能 |
|------|------|------|------|
| AXI4Memory | AXI4Memory.scala | AXI4 Slave | 仿真主存（DPI-C调用外部模型） |
| AXI4RAM | AXI4RAM.scala | AXI4 Slave | 片上RAM |
| AXI4UART | AXI4UART.scala | AXI4 Slave | 轻量级UART（Xilinx兼容） |
| AXI4UART16550 | AXI4UART16550.scala | AXI4 Slave | NS16550A标准UART |
| AXI4Timer | AXI4Timer.scala | AXI4 Slave | 机器模式定时器 |
| AXI4Flash | AXI4Flash.scala | AXI4 Slave | Flash存储仿真 |
| AXI4Plic | AXI4Plic.scala | AXI4 Slave | 平台级中断控制器 |
| AXI4Keyboard | AXI4Keyboard.scala | AXI4 Slave | PS/2键盘 |
| AXI4VGA | AXI4VGA.scala | AXI4 Slave | VGA显示输出 |
| AXI4DummySD | AXI4DummySD.scala | AXI4 Slave | SD卡仿真 |
| AXI4DMAC | AXI4DMAC.scala | AXI4 Slave | DMA控制器 |
| AXI4IntrGenerator | AXI4IntrGenerator.scala | AXI4 Slave | 中断生成器 |
| TLPMA | TLPMA/TLPMA.scala | TileLink | Physical Memory Attribute控制 |
| TLTimer | TLTimer.scala | TileLink | TileLink定时器 |
| SYSCNT | SYSCNT.scala | TileLink | 系统计数器（替代CLINT） |
| TIMER | TIMER.scala | TileLink | 机器模式定时器 |
| MemEncrypt | MemEncrypt.scala | AXI4 | 内存加密模块 |

### 9.2 Standalone设备

`device/standalone/` 目录提供可独立使用的设备封装：

- `StandAloneCLINT.scala` -- 独立CLINT
- `StandAlonePLIC.scala` -- 独立PLIC
- `StandAloneDebugModule.scala` -- 独立调试模块
- `StandAloneSYSCNT.scala` -- 独立系统计数器

### 9.3 AXI4SlaveModule基础设施

`AXI4SlaveModule`（device/AXI4SlaveModule.scala）是所有AXI4外设的基类，提供统一的AXI4 Slave接口封装，支持burst传输处理、读写响应管道等。

### 9.4 仿真Memory模型

`AXI4Memory`（AXI4Memory.scala:160-381）使用DPI-C调用外部C++内存模型（`memory_request()`/`memory_response()`），支持：
- 独立的读写请求/响应通道
- outstanding读写请求追踪
- burst传输处理
- 通过 `DifftestMem`（Chisel SRAM）实现本地RAM访问

---

## 10. Rocket Chip依赖关系

XiangShan大量使用了 `freechips.rocketchip` 库提供的基础设施，主要依赖领域：

### 10.1 Diplomacy框架

`freechips.rocketchip.diplomacy` 提供的节点和互连框架是XiangShan SoC集成的基础：

- `LazyModule` / `LazyModuleImp` -- 所有SoC组件的基类
- `TLXbar` -- TileLink crossbar互连节点
- `TLBuffer` / `TLBuffer.chainNode` -- TileLink缓冲
- `TLIdentityNode` -- TileLink透传节点
- `TLTempNode` -- TileLink临时节点
- `BankBinder` -- Bank划分节点
- `TLFilter` -- 地址过滤
- `IntSourceNode` / `IntSinkNode` -- 中断源/汇节点
- `BundleBridgeSource` / `BundleBridgeSink` -- 信号桥接
- `ResourceBinding` / `DTS` / `JSON` -- 设备树生成

### 10.2 TileLink协议

`freechips.rocketchip.tilelink` 提供TL协议实现：

- `TLClientParameters` / `TLManagerParameters` -- 协议参数
- `TLToAXI4` / `AXI4ToTL` -- 协议转换
- `TLCacheCork` -- TileLink-to-Cache一致性适配
- `TLError` -- 错误设备（处理无效地址访问）
- `TLFIFOFixer` / `TLWidthWidget` / `TLSourceShrinker` -- 协议适配器

### 10.3 AXI4协议

`freechips.rocketchip.amba.axi4` 提供AXI4接口：

- `AXI4Xbar` -- AXI4 crossbar
- `AXI4SlaveNode` / `AXI4MasterNode` -- 主从节点定义
- `AXI4Buffer` / `AXI4Fragmenter` -- AXI4缓冲和分片
- `AXI4IdIndexer` / `AXI4UserYanker` / `AXI4Deinterleaver` -- 协议适配

### 10.4 Tile基础设施

`freechips.rocketchip.tile` 提供处理器Tile级抽象：

- `XLen` -- XLEN配置Key
- `MaxHartIdBits` -- Hart ID位宽
- `BusErrorUnit` -- 总线错误单元
- `HasFPUParameters` -- FPU参数
- `DebugModuleParams` -- 调试模块参数

### 10.5 调试支持

`freechips.rocketchip.devices.debug` 提供：

- `DebugModuleKey` -- 调试模块配置Key
- `DebugModuleParams` -- 调试模块参数（抽象数据字数、SB访问宽度等）

### 10.6 中断与设备

- `freechips.rocketchip.interrupts` -- 中断节点和端口
- `freechips.rocketchip.regmapper` -- 寄存器映射（RegField, RegFieldGroup）
- `freechips.rocketchip.device.tilelink` -- TL外设（PLIC, CLINT等）

### 10.7 工具库

- `freechips.rocketchip.util.AsyncQueueParams` -- 异步队列参数
- `freechips.rocketchip.util.PlusArgArtefacts` -- PlusArg参数

---

## 11. 关键设计决策与模式

### 11.1 LazyModule两阶段设计

XiangShan严格遵循Rocket Chip的LazyModule两阶段设计模式：

1. **Elaboration阶段**（LazyModule）：构建Diplomacy图，定义节点连接，进行参数推断和协商
2. **Implementation阶段**（LazyModuleImp/`module`）：生成实际RTL逻辑

几乎所有主要组件都采用此模式。例如 `XSTile` 包含 `XSTileImp`，`Backend` 包含 `BackendImp` 包装 `BackendInlinedImp`。

### 11.2 Inlined设计模式

为优化编译性能，部分LazyModule被标记为 `shouldBeInlined = true`：

- `L2TopInlined`（L2Top.scala:66）-- L2Top的内部实现
- `FrontendInlined`（Frontend.scala:123）-- 前端内部实现
- `BackendInlined`（Backend.scala:66）-- 后端内部实现

外层LazyModule（`L2Top`, `Frontend`, `Backend`）通过 `shouldBeInlined = false` 保持为独立模块，内部的 `*Inlined` 版本通过 `shouldBeInlined = true` 允许内联展开，减少模块层次深度。

### 11.3 参数传递与override模式

参数通过CDE的 `p.alter()` 机制在模块层次间传递和覆盖：

```scala
// XSTile.scala:98-101 -- 在XSTop中为每个核传递独立参数
LazyModule(new XSTile()(p.alter((site, here, up) => {
  case XSCoreParamsKey => coreParams
  case PerfCounterOptionsKey => up(PerfCounterOptionsKey).copy(perfDBHartID = coreParams.HartId)
})))
```

这种模式使得同一个模块模板可以根据上下文获得不同的参数配置。

### 11.4 CHI协议作为首选互连

`enableCHI` 在 `HasSoCParameter` trait 中硬编码为 `true`（SoC.scala:143），表明当前版本优先使用CHI协议作为核间一致性互连。这通过 `CoupledL2`（L2）+ `CHIAsyncBridge`（异步跨时钟域）+ `OpenLLC`（L3）+ `OpenNCB`（CHI-to-AXI桥接）实现完整的CHI一致性层次。

### 11.5 多时钟域设计

XSNoCTop模式支持最多4个独立时钟域：

| 时钟域 | 信号 | 描述 |
|--------|------|------|
| CPU Clock | `clock` | Core+L2时钟域 |
| NoC Clock | `noc_clock` | CHI网络时钟域 |
| SoC Clock | `soc_clock` | SoC外设时钟域 |
| CLINT Clock | `clint_clock` | 定时器参考时钟 |

跨时钟域信号通过 `AsyncResetSynchronizerShiftReg`（3级同步）和 `AsyncQueue` 进行安全传递。

### 11.6 低功耗设计

XSNoCTop支持完善的低功耗流程（XSNoCTop.scala:89-191, `HasCoreLowPowerImp` trait）：

1. **L2 Flush**: Core CSR触发L2 flush请求
2. **WFI等待**: 等待Core进入WFI状态
3. **CHI Exit**: 退出CHI一致性域（syscoreq/syscoack握手）
4. **Power Off Request**: 发出 `o_cpu_no_op` 通知SoC

WFI时钟门控（`WFIClockGate`选项）：
- 在IDLE状态下检测WFI
- 当Core执行WFI时门控Core+L2时钟
- 通过中断（msip/mtip/meip/seip/nmi/debug）或CHI snoop请求唤醒

### 11.7 Reset链管理

XiangShan实现了精细的复位链管理（Top.scala:378-389）：

```
外部reset --> ResetGen --> {SoCMisc, Cores}
                |
                +--> Core Reset (可由DebugModule独立控制)
```

每个Core支持独立的 `hartResetReq` 信号，允许Debug Module单独复位特定Core，而无需复位整个SoC。

### 11.8 Separate Bus支持

XSNoCTop支持Separate Bus（分离总线）特性（XSNoCTop.scala:282-375），允许某些MMIO地址空间通过独立的总线端口（TileLink或AXI4）访问，而不经过主SoC互连。这主要用于：

- Debug Module的独立访问通道
- 私有CLINT timer的分离总线连接

### 11.9 DFT支持

设计支持完善的DFT（Design For Test）特性：

- **MBIST**: 内存内建自测试（`DFTResetSignals`, `MbistInterface`）
- **SRAM Control**: SRAM控制接口（`SramBroadcastBundle`）
- **SRAM Helper**: 统一的SRAM实例化和管理（`utility.sram.SramHelper`）

---

## 12. 总结

XiangShan（昆明湖版本）展现了成熟的高性能RISC-V处理器架构设计，其关键特征包括：

1. **清晰的模块层次**: XSTop -> XSTile -> {XSCore, L2Top} -> {Frontend, Backend, MemBlock}，层次分明，职责清晰。

2. **灵活的配置系统**: 基于CDE的配置层次，支持从最小配置（MinimalConfig，适合FPGA验证）到全配置（DefaultConfig）的无缝切换，参数覆盖范围从ISA配置到微架构细节。

3. **双SoC模式**: XSTop（TileLink传统模式）和 XSNoCTop（CHI NoC模式）两套顶层满足不同集成需求。XSNoCTop作为首选，支持异步时钟域、低功耗管理和更灵活的SoC集成。

4. **丰富的外设生态**: device/目录提供完整的仿真外设支持，从基础的UART/Timer到VGA/Keyboard等复杂外设。

5. **深度依赖Rocket Chip生态**: 充分利用Diplomacy框架、TileLink/AXI4协议库、Debug模块等成熟组件，专注于核心微架构创新。

6. **宽阔乱序执行**: 默认8-wide decode/rename/commit，14个整数Issue Queue，3个浮点Issue Queue，支持完整的乱序执行和推测执行。

7. **完善的验证基础设施**: DiffTest接口支持、ChiselDB性能数据库、Constantin常量追踪、DPI-C仿真模型等。

8. **可扩展的多核设计**: 通过参数化N核实例化，支持CHI一致性协议和独立中断/调试连接。

---

## 关键源文件索引

| 文件路径 | 行号范围 | 核心内容 |
|---------|---------|---------|
| `src/main/scala/top/Top.scala` | 84-394 | XSTop顶层及XSTopImp |
| `src/main/scala/top/XSNoCTop.scala` | 470-522 | XSNoCTop顶层及低功耗 |
| `src/main/scala/top/Configs.scala` | 53-622 | 所有配置类定义 |
| `src/main/scala/system/SoC.scala` | 40-640 | SoC参数、MemMisc、SoCMisc |
| `src/main/scala/xiangshan/XSTile.scala` | 34-229 | Tile封装与L1-L2连接 |
| `src/main/scala/xiangshan/XSTileWrap.scala` | 37-210 | 时钟域隔离与异步桥 |
| `src/main/scala/xiangshan/XSCore.scala` | 59-283 | 核心封装与三子系统互连 |
| `src/main/scala/xiangshan/L2Top.scala` | 62-400 | L2缓存封装 |
| `src/main/scala/xiangshan/Parameters.scala` | 48-400+ | XSCoreParameters与执行单元配置 |
| `src/main/scala/xiangshan/frontend/Frontend.scala` | 97-200+ | 前端子系统 |
| `src/main/scala/xiangshan/backend/Backend.scala` | 50-250+ | 后端子系统 |
| `src/main/scala/xiangshan/mem/MemBlock.scala` | 17-80+ | 访存子系统 |
