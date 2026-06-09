# R25 - XiangShan Utility Library 深度研究报告

## 1. 概述

XiangShan Utility Library 是香山 RISC-V 处理器项目的基础硬件工具库，位于 `utility/src/main/scala/utility/` 目录下。该库提供了超过 50 个 Scala 源文件，涵盖 SRAM/CAM 模板设计、替换算法、时钟门控、复位生成、性能监控、日志记录、寄存器映射、数据流水线、位操作工具等核心功能。这些组件被广泛应用于 XiangShan 处理器的各个子模块（IFU、DCache、TLB、LSQ、ROB 等），是整个处理器设计的基础设施层。

该库的设计哲学体现了高度的参数化和可复用性。几乎每个组件都通过 Chisel 的参数化机制（Generics）支持灵活配置，同时保持了与 ASIC 综合工具链的兼容性。以下按照功能类别对各模块进行深入分析。

---

## 2. SRAM/CAM 模板设计

### 2.1 SRAMTemplate（SRAM 模板）

**文件位置：** `utility/src/main/scala/utility/sram/SRAMTemplate.scala`

SRAMTemplate 是 XiangShan 中最核心的存储模板组件，提供了高度参数化的 SRAM 封装。其设计参数包括：

- `gen: T` — 条目数据类型
- `set: Int` — 数组深度（条目数）
- `way: Int` — 数组路数（默认 1）
- `singlePort: Boolean` — 单端口模式（单端口 SRAM 不允许同时读写）
- `shouldReset: Boolean` — 是否支持复位清零
- `extraReset: Boolean` — 是否提供额外复位端口
- `holdRead: Boolean` — 是否保持读数据（输出寄存器保持上一次读取值）
- `conflictBehavior: SRAMConflictBehavior` — 双端口 SRAM 读写冲突处理策略
- `useBitmask: Boolean` — 是否使用逐位掩码
- `withClockGate: Boolean` — 是否添加时钟门控
- `hasMbist: Boolean` — 是否启用 MBIST（Memory Built-In Self-Test）支持
- `latency: Int` — 输出建立多周期（数据在读采样时钟沿后多少周期就绪）
- `extraHold: Boolean` — 是否启用额外输入保持周期

**IO Bundle 设计：** SRAMTemplate 使用 `SRAMReadBus` 和 `SRAMWriteBus` 作为读写接口。读端口采用 `Decoupled` 握手协议（`req` 为 `Decoupled(SRAMBundleA)`），写端口同样采用 `Decoupled` 协议（`req` 为 `Decoupled(SRAMBundleAW)`）。`SRAMBundleAW` 支持 `waymask`（路掩码）和可选的 `bitmask`（逐位掩码），`flattened_bitmask` 提供了 `[waymask, bitmask]` 的展平形式，可直接用于存储掩码控制。

**冲突处理机制（SRAMConflictBehavior）：** 这是该设计的一个突出特点。`SRAMConflictBehavior` 定义了双端口 SRAM 在读写地址相同时的多种行为策略：

- `CorruptRead` — 允许冲突，所有读数据损坏
- `CorruptReadWay` — 允许冲突，仅被写入的路的读数据损坏（默认行为）
- `BypassWrite` — 允许冲突，将写数据旁路到读数据
- `AssertionFail` — 不允许冲突，发生时触发断言失败
- `BufferWrite` — 使用单条目缓冲区保存冲突写数据，后续周期写入（需要上下游支持 stall）
- `BufferWriteLossy` — 类似 BufferWrite，但不支持 stall，可能丢失写数据
- `BufferWriteLossyFast` — 改进时序的 Lossy 版本
- `StallWrite` — 通过 stall 写操作来避免冲突
- `StallRead` — 通过 stall 读操作来避免冲突

**存储阵列实现：** 实际的存储阵列通过 `SramHelper.genRam` 生成，底层使用 `SramProto` 中的 `SramArray1P`（单端口）或 `SramArray2P`（双端口）模块。这些模块基于 Chisel 的 `SyncReadMem` 实现，支持可选的写掩码（`maskSegments`）。`SramProto` 使用 Chisel 的 hierarchy API（`Definition` 和 `Instance`）来实现 SRAM 实例的复用。

**MBIST 支持：** `SramInfo` 类负责管理 MBIST 节点的编号和掩码转换。根据 SRAM 的数据宽度与最大 MBIST 数据宽度的关系，自动选择 N-to-1（多个节点服务一个路）或 1-to-N（一个节点服务多个路）的映射策略。`SramHelper` 维护全局的节点 ID 和域 ID 计数器，确保每个 SRAM 节点具有唯一标识。

**SplittedSRAMTemplate 和 FoldedSRAMTemplate：**

`SplittedSRAMTemplate` 支持将大 SRAM 分割为多个小 SRAM，参数包括 `setSplit`、`waySplit` 和 `dataSplit`。分割后的 SRAM 通过 bank 选择逻辑连接，读响应通过 `Mux1H` 根据 bank 选择信号选择正确数据。

`FoldedSRAMTemplate` 在 `SplittedSRAMTemplate` 基础上实现了 SRAM 折叠（folding）技术，将 `width` 个逻辑行折叠到同一物理行中，通过地址的低位选择具体的数据行。这种方式可以减小 SRAM 的面积开销，同时保持逻辑接口不变。

**SRAMTemplateWithArbiter：** 封装了带仲裁器的 SRAM，支持多个读端口竞争访问同一个单端口 SRAM。内部使用 Chisel 的 `Arbiter` 模块进行读端口仲裁，并通过 `HoldUnless` 锁存每个读端口的结果。

### 2.2 DataModuleTemplate（数据模块模板）

**文件位置：** `utility/src/main/scala/utility/DataModuleTemplate.scala`

DataModuleTemplate 是面向寄存器堆（Register File）场景的存储模板，与 SRAMTemplate 的主要区别在于它使用 Chisel 的 `Reg(Vec(...))` 而非 `SyncReadMem` 来实现存储，因此支持更多的读写端口组合。

**RawDataModuleTemplate：** 提供原始（无 bypass）的多读多写数据模块。核心设计要点：
- 支持 `optWrite` 参数，对指定写端口延迟一个周期（使用 `GatedValidRegNext`），以优化写时序
- 读端口支持同步（`isSync`，使用 `RegNext` 延迟地址）和异步模式
- 可选的读使能端口（`hasRen`）
- 对 `optWrite` 端口实现了读旁路（read bypass），确保读取最新写入值

**SyncDataModuleTemplate：** 在 `DataModuleTemplate` 基础上增加了 bank 分割支持（`maxBankEntries = 64`），通过 `bankOffset` 和 `bankIndex` 将地址映射到不同的 bank。支持 `perReadPortBypassEnable` 参数，可以为每个读端口独立控制旁路使能。

**DataModuleTemplate：** 核心数据模块，支持即时读写旁路。当读地址与某个写端口地址匹配时，通过 `Mux1H` 直接旁路写数据到读输出，避免了寄存器读取延迟。

**Folded1WDataModuleTemplate：** 专为单写端口场景设计的折叠数据模块，支持复位清零（`hasResetEn`），使用 `Mem` 存储数据以减小面积。

### 2.3 IndexableCAMTemplate（可索引 CAM 模板）

**文件位置：** `utility/src/main/scala/utility/IndexableCAMTemplate.scala`

IndexableCAMTemplate 实现了可索引的内容寻址存储器（CAM），支持多读单写操作。与标准 CAM 不同，它可以通过索引直接读取特定条目的数据（通过 `isIndexable` 参数启用 `rdata/ridx` 端口）。

核心设计：使用 `Reg(Vec(set, UInt(gen.getWidth.W)))` 存储数据。读操作通过比较器实现——将每个读请求数据与所有条目进行并行比较，返回每位一个匹配结果的热编码。写操作直接按索引更新。该组件常用于 TLB（Translation Lookaside Buffer）等需要内容匹配的场景。

---

## 3. 替换算法（Replacement）

**文件位置：** `utility/src/main/scala/utility/Replacement.scala`

Replacement 模块封装了多种缓存替换策略，分为全相联（fully associative）和组相联（set-associative）两大类。

**ReplacementPolicy（全相联替换策略）：** 通过 `fromString` 工厂方法创建：
- `"random"` → `RandomReplacement`：随机替换，使用 LFSR 生成随机数
- `"lru"` → `TrueLRU`：真正的 LRU（最近最少使用）策略
- `"plru"` → `PseudoLRU`：伪 LRU 策略，使用树状结构实现，在大面积情况下更实用

**SetAssocReplacementPolicy（组相联替换策略）：** 同样通过 `fromString` 工厂方法创建：
- `"random"` → `SetAssocRandom`：为每个 set 独立维护随机替换逻辑
- `"setlru"` → `SetAssocLRU(..., "lru")`：组级 LRU
- `"setplru"` → `SetAssocLRU(..., "plru")`：组级伪 LRU

`SetAssocRandom` 内部使用 `RandomReplacement` 实例，并将 `set` 参数忽略（每个 set 共享同一个随机状态）。这些替换策略主要用于 ICache、DCache、TLB 等缓存结构中。

---

## 4. 时钟门控工具（Clock Gating）

### 4.1 ClockGate

**文件位置：** `utility/src/main/scala/utility/ClockGate.scala`

ClockGate 实现了经典的 ICG（Integrated Clock Gating）单元，以 BlackBox 形式提供。其 Verilog 实现采用 `always_latch` 在时钟低电平锁存使能信号，然后用 `AND` 门控制时钟输出：

```verilog
reg EN;
always_latch begin
  if(!CK) EN = TE | E;
end
assign Q = CK & EN;
```

其中 `TE`（Test Enable）用于 DFT 测试模式下强制开启时钟，`E`（Enable）为正常工作使能信号。

**ClockGateTeBundle 管理：** `ClockGate` 对象维护了一个全局的 `teQueue`（可测试性使能信号队列）。`genTeSink` 方法在每个需要时钟门控的位置创建一个 TE 信号并加入队列，`genTeSrc` 方法使用 `BoringUtils.bore` 将所有 TE 信号统一连接到一个源。这种设计允许 DFT 控制器在扫描测试时一次性使能所有时钟门控单元。

### 4.2 ClockGatedReg（门控寄存器）

**文件位置：** `utility/src/main/scala/utility/ClockGatedReg.scala`

ClockGatedReg 提供了一系列在时钟门控下工作的寄存器辅助对象，核心思想是：当寄存器值不变时，通过 `RegEnable` 的门控信号减少时钟翻转，从而降低动态功耗。

**GatedValidRegNext：** 当输入信号的值与当前值不同时才更新寄存器。这是整个库中使用最广泛的门控寄存器之一，被 SRAMTemplate、DataModuleTemplate 等大量组件引用。注释指出 EDA 工具插入的时钟门控单元的最小宽度通常为 3 位，因此对单 bit 信号使用此函数可能没有面积收益。

**GatedRegNext：** 泛型版本的门控寄存器，支持 `Data` 类型。对于 `Vec` 类型，逐元素进行门控判断；对于标量类型，直接比较 `asUInt`。源码注释提醒：数据宽度越大，比较运算 `=/=` 的时序路径越长，可能违反时序约束。

**GatedRegEnable：** 在 `GatedRegNext` 基础上增加了使能端口（`enable`），只有使能有效且数据变化时才更新。还提供了 `dupRegs` 方法用于多个重复寄存器共享同一个门控判断信号。

**SegmentedAddr：** 门控寄存器文件中还包含了一个 `SegmentedAddr` 地址分段类，用于将地址按可配置的段宽分割，并支持分段级别的比较和增量更新（`SegmentedAddrNext`），可以显著减少地址寄存器的功耗。

### 4.3 ClockMux（时钟选择器）

**文件位置：** `utility/src/main/scala/utility/ClockMux.scala`

ClockMux 是一个简洁的两输入时钟选择器（2:1 Clock MUX），基于 `RawModule` 实现，使用 Chisel 的 `Mux` 原语直接选择输出时钟。用于在不同时钟域之间切换，例如在正常模式和低功耗模式之间切换时钟源。

---

## 5. 复位生成（ResetGen）

**文件位置：** `utility/src/main/scala/utility/ResetGen.scala`

ResetGen 提供了异步复位同步释放（asynchronous reset, synchronous deassertion）的标准化实现。

**核心实现：** `ResetGen` 模块接收 `DFTResetSignals`（包含 `lgc_rst_n` 逻辑复位、`mode` 模式选择、`scan_mode` 扫描模式），通过 `SYNC_NUM`（默认 3）级移位寄存器实现复位信号的同步化。移位寄存器初始值为全 1，每个周期左移一位填入 0，当 `SYNC_NUM` 级全部为 0 时表示复位释放完成。

**ResetNode 树结构：** ResetGen 定义了树状复位网络结构：
- `ModuleNode` — 直接连接到模块的 `reset` 端口
- `CellNode` — 连接到特定的 `Reset` 信号
- `ResetGenNode` — 包含子节点的中间节点，会生成一个新的 `ResetGen` 实例

`ResetGen.apply` 的树状重载版本递归遍历 `ResetNode` 树，在每个 `ResetGenNode` 处插入同步复位生成器。链式版本（`resetChain`）支持分层复位——每一层的模块接收上一层经过同步后的复位信号，实现异步复位的逐级同步释放。在仿真模式（`sim = true`）下，跳过复位同步逻辑以加速仿真。

---

## 6. 性能监控（Performance Monitoring）

### 6.1 HardwarePerfMonitor

**文件位置：** `utility/src/main/scala/utility/HardwarePerfMonitor.scala`

HardwarePerfMonitor 实现了硬件性能计数器的事件选择和组合逻辑，对应 RISC-V 的 HPM（Hardware Performance Monitor）机制。

**PerfEvent Bundle：** 6 位宽度的性能事件信号，最多可选择 64 个事件源。

**HPerfCounter：** 单个高性能计数器，接受 64 位的 `hpm_event` 配置字，从中提取 4 个事件选择码（各 10 位）和 3 个操作码（各 5 位）。支持四种组合操作：AND、XOR、加法、OR。通过两级组合和流水线化寄存器，实现 4 个事件源的灵活组合。

**HPerfMonitor：** 管理多个 `HPerfCounter` 实例（数量由 `numCSRPCnt` 指定），每个对应一个 CSR 事件配置寄存器。所有计数器共享相同的事件输入集合（`events_sets`）。

**HasPerfEvents Trait：** 混入到 `RawModule` 中，提供标准化的性能事件 IO（`io_perf: Vec[PerfEvent]`）和事件生成方法 `generatePerfEvent()`。默认对事件信号进行两级 `RegNext` 延迟以优化时序。

### 6.2 PerfCounterUtils

**文件位置：** `utility/src/main/scala/utility/PerfCounterUtils.scala`

PerfCounterUtils 提供了一套完整的仿真性能统计工具集，基于延迟应用（deferred apply）模式。

**XSPerfLevel 枚举：** 定义了三个性能统计级别——`VERBOSE`（最详细）、`NORMAL`、`CRITICAL`（仅关键指标）。

**XSPerfAccumulate：** 核心累加计数器。在 Chisel elaboration 阶段收集所有 `XSPerfAccumulate` 调用信息（模块名、计数器名、计数信号），在 `LogPerfEndpoint` 收集阶段统一生成 64 位累加器寄存器和打印逻辑。

**XSPerfHistogram：** 直方图统计工具，支持配置 `start`、`stop`、`step` 参数定义桶范围，并支持 `left_strict` 和 `right_strict` 控制超出范围的元素是否归入首尾桶。同时统计 sum、mean、sampled、underflow、overflow 等汇总指标。

**XSPerfMax：** 最大值追踪器，在使能期间跟踪并输出最大值。

**XSPerfSeqAccumulate：** 批量创建累加计数器的便捷方法，支持公共前缀和公共使能信号，支持 `withPriority` 模式（仅累加第一个有效事件）。

**XSPerfRolling：** 滚动统计工具，将性能计数器的值按时钟周期或事件触发分段记录到 ChiselDB 表中。支持三种模式：时钟周期触发、事件触发、双轴事件触发。

**QueuePerf：** 队列性能统计的标准封装，自动统计 utilization（利用率直方图）、full（满次数）、exHalf（超过半满次数）、empty（空次数）。

**TransactionLatencyCounter：** 事务延迟计数器，在 start 和 stop 信号之间计数。

**ArbPerf：** 仲裁器性能监控，统计仲裁冲突分布和每个端口的等待时间。

### 6.3 HasCriticalErrors

**文件位置：** `utility/src/main/scala/utility/CriticalErrorUtils.scala`

HasCriticalErrors trait 定义了关键错误监控接口，结构与 `HasPerfEvents` 类似。提供 `io_error: Vec[Bool]` 输出和 `generateCriticalErrors()` 方法，对错误信号进行两级 `RegNext` 延迟后输出。用于报告处理器中的严重错误事件。

---

## 7. ChiselDB — 仿真数据录制

**文件位置：** `utility/src/main/scala/utility/ChiselDB.scala`

ChiselDB 是一个基于 SQLite 的仿真数据录制框架，允许在 RTL 仿真期间将硬件信号数据写入数据库。

**Table 类：** 每个 `Table` 实例对应一个 SQLite 表。`get_columns` 方法递归展开 Chisel `Record` 和 `Vec` 类型，为每个叶节点字段生成一列。对于超过 64 位的信号，自动分割为多个列。`log` 方法创建 `TableWriteHelper` BlackBox，在仿真时通过 DPI-C 调用将数据写入内存数据库。

**TableWriteHelper BlackBox：** 自动生成包含 DPI-C 函数调用的 Verilog 代码。每个写操作在时钟上升沿、使能有效且非复位时触发。FPGA 平台下生成空操作的 dummy 实现。

**ChiselDB 对象：** 全局单例，维护表注册表。`createTable` 方法创建或获取已有表。`getCpp` 方法生成完整的 C++ 实现代码，包括：
- SQLite 内存数据库初始化
- 所有表的建表和插入函数
- 选择性启用机制（通过 `select_db` 参数）
- `save_db` 函数将内存数据库保存到磁盘

**FileRegisters：** 辅助类，收集所有需要生成的文件（`chisel_db.h`、`chisel_db.cpp` 等），在构建流程结束时统一写出。

---

## 8. Constantin — 运行时可配置常量

**文件位置：** `utility/src/main/scala/utility/Constantin.scala`

Constantin 提供了一种在仿真运行时动态修改硬件常量值的机制，而不需要重新综合。

**工作原理：** 每个 `createRecord` 调用创建一个 `SignalReadHelper` BlackBox，在 Verilog 中通过 DPI-C 函数读取初始值。仿真启动时，C++ 侧的 `constantinMap`（`map<string, uint64_t>`）保存所有常量的当前值。每次 DPI-C 调用返回 `constantinMap` 中对应键的值。

**代码生成：** `Constantin.getInitCpp` 生成包含默认值的初始化代码，`getCpp` 为每个常量生成独立的 DPI-C 读取函数。当值发生变化时，自动打印变化信息。`getTXT` 生成纯文本格式的常量列表。

**使用场景：** Constantin 主要用于在不重新编译的情况下调整处理器配置参数（如缓存大小、队列深度等），极大加速了设计空间探索（Design Space Exploration）。

---

## 9. RegMap — CSR 寄存器映射

**文件位置：** `utility/src/main/scala/utility/RegMap.scala`

RegMap 提供了三种层次的 CSR（Control and Status Register）映射工具。

### 9.1 基础 RegMap

最简单的寄存器映射，支持读写和写函数变换（`wfn`）。`Unwritable` 表示不可写寄存器。`generate` 方法使用 `LookupTree` 生成读多路选择器，使用 `MaskData` 实现掩码写入。

### 9.2 MaskedRegMap

增加了写掩码（`wmask`）和读掩码（`rmask`）支持。写入时使用 `RegEnable` + `GatedValidRegNext` 延迟一个周期，减少写信号的扇出。`isIllegalAddr` 方法检查地址是否在映射范围内。

### 9.3 ConditionalRegMap

最高级的寄存器映射工具，支持条件寄存器——同一个地址可以映射到多个寄存器，通过 `condition`（One-Hot 编码）选择实际访问哪个。读取时使用 `Mux1H` 根据条件选择；写入时仅更新条件匹配的寄存器。支持隐式转换，可以直接使用 `RegMapEntry` 或 `UInt` 类型简化 API。这在实现具有模式切换功能的 CSR（如不同模式下同一地址映射到不同寄存器）时非常有用。

---

## 10. 日志工具（LogUtils / GTimer）

### 10.1 LogUtils

**文件位置：** `utility/src/main/scala/utility/LogUtils.scala`

LogUtils 实现了 XiangShan 的分层日志系统。

**XSLogLevel 枚举：** 定义了 7 个日志级别：ALL、DEBUG、INFO、PERF、WARN、ERROR、OFF。

**LogUtilsOptions：** 通过 CDE（Context-Dependent Environments）配置，控制 `enableDebug`、`enablePerf`、`fpgaPlatform`、`enableXMR` 选项。

**XSLog 核心类：** 采用延迟应用（deferred apply）模式。在 elaboration 阶段，`XSLog.apply` 不直接生成 printf，而是将日志参数（`LogPerfParam`）缓冲到 `logInfos` 列表中。在 `collect` 阶段，创建 `LogPerfEndpoint` 模块统一处理所有日志输出。

**LogPerfEndpoint：** 核心收集模块，接收 `LogPerfIO` 控制信号（`timer`、`logEnable`、`clean`、`dump`）。将相同条件的日志分组，减少 SystemVerilog `printf` 语句数量（有利于线程调度）。ERROR 级别的日志不受 `logEnable` 控制，始终输出。

**XSLogTap trait：** 提供 `tapOrGet` 方法处理跨模块信号访问——如果信号在当前模块可见则直接访问，否则通过 `BoringUtils` 的 `tapAndRead` 或 `bore` 进行跨层级访问。

**日志辅助对象：** `XSDebug`、`XSInfo`、`XSWarn`、`XSError` 分别对应各日志级别的便捷调用接口。

### 10.2 GTimer

**文件位置：** `utility/src/main/scala/utility/GTimer.scala`

GTimer 提供全局 64 位仿真计数器，每次调用 `apply()` 返回当前计数值并自动递增。该计数器在仿真期间作为日志系统的时间戳使用。

---

## 11. LookupTree、Sort、LFSR64、SubVec 杂项工具

### 11.1 LookupTree（查找树）

**文件位置：** `utility/src/main/scala/utility/LookupTree.scala`

LookupTree 提供基于 `Mux1H` 的组合逻辑查找表实现。`apply` 方法将 key 与 mapping 中的每个键进行比较，使用 One-Hot 编码驱动 `Mux1H`，实现 O(log n) 延迟的并行查找。支持 `UInt` 和 `BitPat` 两种键类型。

`LookupTreeDefault` 使用 `MuxLookup` 实现带默认值的查找。`MuxT` 和 `MuxTLookup` 提供元组级别的 MUX 操作，允许同时选择多个信号。

### 11.2 Sort（硬件排序）

**文件位置：** `utility/src/main/scala/utility/Sort.scala`

`HwSort` 实现了面向硬件的小规模组合排序器，最多支持 4 个元素的排序。设计特点：
- 有效元素排在无效元素之前
- 基于 `CircularQueuePtr` 的比较器（默认升序，支持自定义比较函数）
- 2 元素排序约 20ps，3-4 元素排序约 40ps（注释中的时序估算）
- 使用奇偶归并网络（Odd-Even Merging Network）的变体

`DataWithPtr` Bundle 将数据与指针打包，用于排序时的比较。典型应用场景包括指令发射队列中的年龄排序。

### 11.3 LFSR64

**文件位置：** `utility/src/main/scala/utility/LFSR64.scala`

LFSR64 实现了 64 位线性反馈移位寄存器（Linear Feedback Shift Register），使用特征多项式 `x^64 + x^4 + x^3 + x + 1`（tap 位置为 0, 1, 3, 4）。支持可选种子值（默认 `0x1234567887654321`）。当 LFSR 值为 0 时自动跳转到 1 以避免死锁。主要用于 SRAMTemplate 中生成随机冲突数据，以及随机替换策略等场景。

### 11.4 SubVec

**文件位置：** `utility/src/main/scala/utility/SubVec.scala`

SubVec 提供向量分片工具：
- `getRem` — 按取模方式从 `Seq` 中提取子序列（要求长度可整除）
- `getRemWithExpansion` — 支持不整除情况，不足部分补零
- `getMaskRem` — 对 `Vec[Bool]` 生成取模掩码

这些工具在内存 bank 交错、数据分割等场景中频繁使用。

---

## 12. 其他重要组件

### 12.1 BitUtils（位操作工具）

**文件位置：** `utility/src/main/scala/utility/BitUtils.scala`

BitUtils 是最庞大的工具文件之一（约 420 行），提供了极其丰富的位操作原语：

- **MaskGen** — 根据地址和大小生成字节掩码
- **MaskExpand / MaskData** — 掩码扩展和按掩码数据合并
- **SignExt / ZeroExt** — 符号扩展和零扩展
- **Or.leftOR / Or.rightOR** — 从低位/高位开始的填充 OR
- **OneHot** — One-Hot 编码的全套转换工具（OH1ToOH、UIntToOH1、OH1ToUInt 等）
- **LowerMask / HigherMask / GenMask** — 各种掩码生成
- **GetEvenBits / GetOddBits / GetRemBits** — 位提取和交错
- **XORFold** — XOR 折叠压缩
- **SelectOne** — 多种选择策略（Naive、Circ、OddEven、Center）
- **SelectFirstN** — 选择前 N 个 One-Hot 位
- **FastAdderComparator** — 快速加法器比较器
- **MaskToOH** — 掩码到 One-Hot 转换
- **OneHot.CheckOneHot** — One-Hot 校验断言

### 12.2 RegisterSlice（寄存器切片）

**文件位置：** `utility/src/main/scala/utility/RegisterSlice.scala`

RegisterSlice 提供三种流水线寄存器切片实现，用于断开 Decoupled 接口的关键时序路径：

- **ForwardRegistered** — 前向寄存器，输出侧优先消费，支持 flush
- **BackwardRegistered** — 后向寄存器，内部缓冲，当输出未就绪时暂存数据
- **FullyRegistered** — 完全寄存器，三状态 FSM（empty/busy/full），最大时序优化

### 12.3 FastArbiter（快速仲裁器）

**文件位置：** `utility/src/main/scala/utility/FastArbiter.scala`

FastArbiter 提供了三种高性能仲裁器：
- **FastArbiter** — 基于 Round-Robin 的快速仲裁器，使用 pending mask 和 One-Hot 编码
- **LatchFastArbiter** — 带锁存的版本，在输出未被消费时保持仲裁结果
- **TwoLevelRRArbiter** — 两级树状 Round-Robin 仲裁器，将输入分组仲裁后再进行组间仲裁

### 12.4 Pipeline 和 PipelineConnect

**文件位置：** `utility/src/main/scala/utility/Pipeline.scala` 和 `PipelineConnect.scala`

`Pipeline` 类提供基于 `Queue(1, pipe=true)` 的流水线插入工具。`PipelineConnect` 是 XiangShan 中使用最广泛的流水线连接组件之一，提供了：
- 简单的一级流水线寄存器（`connect` 方法）
- 带 flush 功能的双缓冲流水线（`PipelineConnectBuffer`）
- 支持额外数据伴随传输的扩展版本（`PipelineConnectBufferWithExtraData`）

### 12.5 Hold（数据保持）

**文件位置：** `utility/src/main/scala/utility/Hold.scala`

提供多种数据保持和延迟工具：
- **HoldUnless** — 当使能无效时保持数据不变
- **ReadAndHold** — 读内存时保持数据
- **ValidHold** — 保持 fire 信号直到下一次 fire 或 flush
- **DataHoldBypass** — 保持数据并旁路最新值
- **DataChanged** — 检测数据变化
- **DelayN / DelayNWithValid** — N 级流水线延迟

### 12.6 ECC（纠错码）

**文件位置：** `utility/src/main/scala/utility/ECC.scala`

ECC 模块实现了多种纠错编码：
- **IdentityCode** — 无纠错（直通）
- **ParityCode** — 奇偶校验（仅检错）
- **SECCode** — SEC（Single Error Correction）汉明码
- **SECDEDCode** — SECDED（Single Error Correction, Double Error Detection）编码

### 12.7 ChiselTaggedTrace（指令生命周期追踪）

**文件位置：** `utility/src/main/scala/utility/ChiselTaggedTrace.scala`

ChiselTaggedTrace 实现了指令级别的生命周期追踪系统（PerfCCT - Performance Commit Trace），通过 DPI-C 接口在仿真中追踪每条指令从取指到提交的完整时间线。

定义了指令位置枚举（AtFetch、AtDecode、AtRename、AtDispQue、AtIssueQue、AtIssueArb、AtIssueReadReg、AtFU、AtBypassVal、AtWriteVal、AtCommit），以及加载指令的详情（虚拟地址、物理地址、重放原因等）。

### 12.8 CircularQueuePtr（循环队列指针）

**文件位置：** `utility/src/main/scala/utility/CircularQueuePtr.scala`

`CircularQueuePtr` 是 XiangShan 中广泛使用的循环队列指针实现，使用 flag-value 编码（1 位标志位 + log2Up(entries) 位值）实现无溢出的队列指针运算。支持加法、减法、比较（`>`、`<`、`>=`、`<=`）和 One-Hot 转换。`HasCircularQueuePtrHelper` trait 提供了 `isEmpty`、`isFull`、`distanceBetween`、`hasFreeEntries` 等常用辅助方法。

### 12.9 MIMOQueue（多输入多输出队列）

**文件位置：** `utility/src/main/scala/utility/MIMOQueue.scala`

MIMOQueue 实现了支持多入多出的队列结构，使用幂次 2 的 entries 大小。支持 `Mem` 或 `Reg(Vec)` 存储后端、可选初始化值和性能计数模式。通过指针数组管理多个读写端口的并发操作。

### 12.10 DiplomacyWidget 和 IntBuffer

**文件位置：** `utility/src/main/scala/utility/DiplomacyWidget.scala` 和 `IntBuffer.scala`

`ValidIOBroadcast` 是基于 Diplomacy 框架的广播组件，将一个输入 ValidIO 广播到多个输出。`IntBuffer` 提供中断信号的缓冲，支持同步（`RegNextN`）和异步（`AsyncResetSynchronizerShiftReg`）两种模式。

### 12.11 BinaryArbiterNode

**文件位置：** `utility/src/main/scala/utility/BinaryArbiterNode.scala`

BinaryArbiter 实现了 TileLink 二叉仲裁器，将输入节点分组后通过两棵 TLXbar 树进行仲裁。当输入少于 4 个时使用单输出，否则分裂为两组。

### 12.12 SelectByFn

**文件位置：** `utility/src/main/scala/utility/Select.scala`

`SelectByFn` 实现了基于自定义比较函数的树形选择器，使用归约树结构在 O(log n) 延迟内从多个有效输入中选择最优元素。支持自定义选择准则。

### 12.13 ExtractVerilogModules

**文件位置：** `utility/src/main/scala/utility/ExtractVerilogModules.scala`

`VerilogModuleExtractor` 是一个 Verilog 模块提取工具，可以从生成的单一 Verilog 文件中解析并提取指定模块及其依赖子模块的定义，分别保存为独立文件。支持命令行参数配置。

### 12.14 ParallelMux 与并行操作

**文件位置：** `utility/src/main/scala/utility/ParallelMux.scala`

提供完整的并行操作工具集：
- **ParallelOperation** — 通用的并行归约框架（树状结构，O(log n) 延迟）
- **ParallelOR / ParallelAND / ParallelXOR** — 并行逻辑运算
- **ParallelMux** — 并行多路选择
- **ParallelLookUp** — 并行查找
- **ParallelMax / ParallelMin** — 并行极值选择
- **ParallelPriorityMux / ParallelPosteriorityMux** — 并行优先级/后序选择
- **ParallelSelectTwo** — 并行选择前两个元素

### 12.15 PriorityMuxGenerator 与 PhyPriorityMuxGenerator

**文件位置：** `utility/src/main/scala/utility/PriorityMuxGen.scala`

`PriorityMuxGenerator` 支持在代码不同位置分散注册优先级 MUX 的输入源，在最终 `apply()` 时统一生成硬件。`PhyPriorityMuxGenerator` 进一步支持物理优先级重排——逻辑优先级保持代码顺序不变，但物理实现上按指定的物理优先级排列，通常将延迟最大的条件赋予最高物理优先级。

### 12.16 UIntUtils（UInt 压缩/提取）

**文件位置：** `utility/src/main/scala/utility/UIntUtils.scala`

- **UIntCompressor** — 根据 filter 位索引序列从宽 UInt 中提取指定位，压缩为窄 UInt
- **UIntExtractor** — 将窄 UInt 的各位展开到宽 UInt 的指定位置

### 12.17 LatencyPipe（延迟流水线）

**文件位置：** `utility/src/main/scala/utility/LatencyPipe.scala`

LatencyPipe 通过级联多个 `Queue(1, pipe=true)` 实现可配置延迟的 Decoupled 流水线。

### 12.18 StopWatch

**文件位置：** `utility/src/main/scala/utility/StopWatch.scala`

`BoolStopWatch` 实现了简单的启停控制——在 `start` 时置位，`stop` 时清零。支持 `startHighPriority`（stop 优先）和 `bypass`（直接输出 start 信号）选项。

### 12.19 Misc 工具

**文件位置：** `utility/src/main/scala/utility/Misc.scala`

- **MaskGen** — 根据地址和访问大小生成字节掩码
- **Random** — 基于 LFSR 的随机数生成，支持模运算和 One-Hot 输出
- **Transpose** — 矩阵转置（Vec of Vecs）
- **TimeOutAssert** — 超时断言（信号持续为 true 超过阈值时断言）
- **RRArbiterInit** — 带初始值的 Round-Robin 仲裁器

### 12.20 Compatibility 和 Package

**文件位置：** `utility/src/main/scala/utility/Compatibility.scala` 和 `package.scala`

`XSCompatibility` 提供了对 Chisel3 内部 API 的兼容访问（`currentModule`、`currentWhen`），用于日志系统获取当前模块信息。`package.scala` 定义了包级别的类型别名，将 SRAMTemplate 等类从 `utility.sram` 包重新导出到 `utility` 包，维持向后兼容性。同时定义了 `PerfCCT = TaggedTrace` 的别名。

---

## 13. 关键源文件位置索引

| 功能模块 | 文件路径 |
|---------|---------|
| SRAMTemplate | `utility/src/main/scala/utility/sram/SRAMTemplate.scala` |
| SramHelper | `utility/src/main/scala/utility/sram/SramHelper.scala` |
| SramProto | `utility/src/main/scala/utility/sram/SramProto.scala` |
| DataModuleTemplate | `utility/src/main/scala/utility/DataModuleTemplate.scala` |
| IndexableCAMTemplate | `utility/src/main/scala/utility/IndexableCAMTemplate.scala` |
| Replacement | `utility/src/main/scala/utility/Replacement.scala` |
| ClockGate | `utility/src/main/scala/utility/ClockGate.scala` |
| ClockGatedReg | `utility/src/main/scala/utility/ClockGatedReg.scala` |
| ClockMux | `utility/src/main/scala/utility/ClockMux.scala` |
| ResetGen | `utility/src/main/scala/utility/ResetGen.scala` |
| HardwarePerfMonitor | `utility/src/main/scala/utility/HardwarePerfMonitor.scala` |
| PerfCounterUtils | `utility/src/main/scala/utility/PerfCounterUtils.scala` |
| CriticalErrorUtils | `utility/src/main/scala/utility/CriticalErrorUtils.scala` |
| ChiselDB | `utility/src/main/scala/utility/ChiselDB.scala` |
| Constantin | `utility/src/main/scala/utility/Constantin.scala` |
| RegMap | `utility/src/main/scala/utility/RegMap.scala` |
| LogUtils | `utility/src/main/scala/utility/LogUtils.scala` |
| GTimer | `utility/src/main/scala/utility/GTimer.scala` |
| LookupTree | `utility/src/main/scala/utility/LookupTree.scala` |
| Sort | `utility/src/main/scala/utility/Sort.scala` |
| LFSR64 | `utility/src/main/scala/utility/LFSR64.scala` |
| SubVec | `utility/src/main/scala/utility/SubVec.scala` |
| BitUtils | `utility/src/main/scala/utility/BitUtils.scala` |
| RegisterSlice | `utility/src/main/scala/utility/RegisterSlice.scala` |
| FastArbiter | `utility/src/main/scala/utility/FastArbiter.scala` |
| Pipeline | `utility/src/main/scala/utility/Pipeline.scala` |
| PipelineConnect | `utility/src/main/scala/utility/PipelineConnect.scala` |
| Hold | `utility/src/main/scala/utility/Hold.scala` |
| ECC | `utility/src/main/scala/utility/ECC.scala` |
| ChiselTaggedTrace | `utility/src/main/scala/utility/ChiselTaggedTrace.scala` |
| CircularQueuePtr | `utility/src/main/scala/utility/CircularQueuePtr.scala` |
| MIMOQueue | `utility/src/main/scala/utility/MIMOQueue.scala` |
| DiplomacyWidget | `utility/src/main/scala/utility/DiplomacyWidget.scala` |
| IntBuffer | `utility/src/main/scala/utility/IntBuffer.scala` |
| BinaryArbiterNode | `utility/src/main/scala/utility/BinaryArbiterNode.scala` |
| SelectByFn | `utility/src/main/scala/utility/Select.scala` |
| ParallelMux | `utility/src/main/scala/utility/ParallelMux.scala` |
| PriorityMuxGen | `utility/src/main/scala/utility/PriorityMuxGen.scala` |
| PriorityMuxDefault | `utility/src/main/scala/utility/PriorityMuxDefault.scala` |
| UIntUtils | `utility/src/main/scala/utility/UIntUtils.scala` |
| FileRegisters | `utility/src/main/scala/utility/FileRegisters.scala` |
| StopWatch | `utility/src/main/scala/utility/StopWatch.scala` |
| LatencyPipe | `utility/src/main/scala/utility/LatencyPipe.scala` |
| Misc | `utility/src/main/scala/utility/Misc.scala` |
| Compatibility | `utility/src/main/scala/utility/Compatibility.scala` |
| ExtractVerilogModules | `utility/src/main/scala/utility/ExtractVerilogModules.scala` |
| Package | `utility/src/main/scala/utility/package.scala` |

---

## 14. 设计模式与架构特点总结

**延迟应用模式（Deferred Apply）：** ChiselDB、PerfCounterUtils 和 LogUtils 均采用延迟应用模式——在 elaboration 阶段仅收集配置信息，在统一的收集阶段生成实际硬件逻辑。这使得性能监控和日志系统的代码可以分散在各个子模块中，而实际的 IO 和逻辑集中在顶层处理。

**BlackBox + DPI-C 模式：** ClockGate、Constantin、ChiselDB、ChiselTaggedTrace 均使用 BlackBox 封装 SystemVerilog 代码，通过 DPI-C 与 C++ 仿真环境交互。`FileRegisters` 负责收集和写出所有生成的 C/C++ 文件。

**全局状态管理：** 多个组件维护全局状态（如 `ChiselDB.table_map`、`Constantin.initMap`、`SramHelper.nodeId`、`ClockGate.teQueue`），通过 Scala 的 mutable 集合实现 elaboration 期间的状态管理。

**参数化与工厂模式：** 大量使用字符串到类型的工厂方法（如 `ReplacementPolicy.fromString`、`RegisterSlice.fromString`、`Code.fromString`、`SelectOne.apply`），使得用户可以通过配置文件灵活选择实现策略。

**分层抽象：** SRAM 设计从 `SramArray`（底层阵列）→ `SramProto`（阵列操作）→ `SramHelper`（MBIST 集成）→ `SRAMTemplate`（完整 SRAM 封装）逐层抽象，每层职责清晰。

**延迟优化意识：** 门控寄存器（`GatedValidRegNext`、`GatedRegNext`）在全库范围内广泛使用，通过减少不必要的时钟翻转降低动态功耗。流水线化寄存器（如 `HPerfCounter` 中的事件组合流水线、`MaskedRegMap` 的写入延迟）体现了对 ASIC 时序的深度关注。

**仿真与综合双轨：** 许多组件（如 Constantin、ChiselDB、ResetGen）提供仿真和综合两种模式的实现，通过条件编译或参数控制切换，确保在不影响综合质量的前提下提供丰富的仿真调试能力。
