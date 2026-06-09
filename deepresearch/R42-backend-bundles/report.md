# R42 - Backend Bundles & Data Structures: 深度分析报告

## 1. 概述

XiangShan 后端 (Backend) 的核心数据结构集中在四个源文件中，它们构成了处理器执行引擎的数据流骨架。后端采用 **参数化 (parameterized)** 与 **分层 (hierarchical)** 的 Bundle 设计模式，通过 Scala 的隐式参数 (implicit parameters) 和 Option 类型实现了高度灵活的硬件生成。本报告深入分析这四个关键文件中定义的所有 Bundle 结构，揭示其字段含义、数据流路径与设计哲学。

**关键源文件：**

| 文件路径 | 行数 | 核心内容 |
|---------|------|---------|
| `src/main/scala/xiangshan/backend/Bundles.scala` | 1804 行 | 核心微操作与执行流水线 Bundle |
| `src/main/scala/xiangshan/backend/BackendParams.scala` | 701 行 | 后端全局参数配置与执行单元实例化 |
| `src/main/scala/xiangshan/backend/Region.scala` | 948 行 | 调度区域 (Scheduling Region) 架构 |
| `src/main/scala/xiangshan/Bundle.scala` | 818 行 | 顶层通用 Bundle 与 Redirect 定义 |

---

## 2. MicroOp Bundle 体系 —— 从解码到执行的微操作演进

XiangShan 的微操作 (MicroOp) 并非单一结构，而是一系列随流水线阶段演进的 Bundle 序列。每个阶段添加或移除特定字段，体现了 **"按需携带" (carry-only-what's-needed)** 的设计哲学。

### 2.1 DecodeInUop —— 前端到解码的输入

`DecodeInUop` (Bundles.scala:107-129) 是从前端 (Frontend) 经 CtrlBlock 进入解码阶段的微操作载体。它携带以下字段：

- **`foldpc`**: Memory Prediction 使用的折叠 PC 值，宽度为 `MemPredPCWidth`，用于存储依赖预测中的地址折叠。
- **`exceptionVec`**: 来自前端的异常向量 (ExceptSparseVec)，包含取指阶段可能产生的异常位。
- **`isFetchMalAddr`**: 标识该指令是否包含取指错误地址 (backendException)。
- **`trigger`**: 触发器动作 (TriggerAction)，4 位编码，支持断点异常 (BreakpointExp)、调试模式 (DebugMode)、Trace 控制等。
- **`isRVC`**: 是否为 RISC-V Compressed 16 位指令。
- **`fixedTaken` / `predTaken`**: 分支预测结果，`fixedTaken` 为修正后的跳转判定，`predTaken` 为原始预测值。
- **`crossPageIPFFix`**: 跨页指令预取错误修复标志。
- **`ftqPtr` / `ftqOffset`**: 指令在 Fetch Target Queue 中的指针与偏移，用于异常重定向时定位正确取指位置。
- **`isLastInFtqEntry`**: 标识是否为 FTQ 条目中的最后一条指令。
- **`instr`**: 32 位原始指令编码。

辅助方法 `connectCtrlFlow` 通过 `connectSamePort` 安全地从 `CtrlFlow` 映射字段，并做 `isRVC` / `isFetchMalAddr` 的语义转换。

### 2.2 DecodeOutUop —— 解码后的微操作

`DecodeOutUop` (Bundles.scala:136-205) 在 `DecodeInUop` 基础上扩展了大量解码产生的控制信号：

- **`commitType`**: 提交类型 (CommitType)，ROB 用此计算 LSQ 提交计数。
- **`srcType`**: Vec(numSrc, SrcType())，源操作数类型，区分整数/浮点/向量/立即数等。
- **`lsrc` / `ldest`**: 逻辑源/目的寄存器编号 (LogicRegsWidth 位)。
- **`fuType` / `fuOpType`**: 功能单元类型与操作类型，决定指令送往哪个执行单元。
- **`rfWen` / `fpWen` / `vecWen` / `v0Wen` / `vlWen`**: 五种寄存器文件写使能，分别对应整数/浮点/向量/V0/向量长度寄存器。
- **`waitForward` / `blockBackward`**: 原子指令与 Fence 的顺序控制信号。`waitForward` 表示不可推测执行，`blockBackward` 阻塞后续指令。
- **`flushPipe`**: 该指令提交时刷新整个流水线（类似异常但可正常提交）。
- **`canRobCompress`**: ROB 压缩使能标志。
- **`selImm` / `imm`**: 立即数选择类型与 32 位立即数。
- **`fpu` / `vpu`**: 浮点/向量处理单元控制信号子 Bundle。
- **`uopIdx` / `uopSplitType`**: 微操作索引与分裂类型，用于一条宏指令拆分为多条微操作的场景。
- **`isVset` / `firstUop` / `lastUop`**: 向量 set 配置指令与微操作边界标志。
- **`numWB`**: 写回计数，ROB 用此确定该宏指令需要多少次写回。
- **`needFrm`**: 需要浮点舍入模式信号 (NeedFrmBundle 包含 scalaNeedFrm 和 vectorNeedFrm)。

`decode` 方法通过 `ListLookup` 实现指令解码表查找，将解码结果填入 `allSignals` 序列。

### 2.3 RenameOutUop —— 重命名后的微操作

`RenameOutUop` (Bundles.scala:231-302) 在解码输出基础上增加了重命名阶段信息：

- **物理寄存器字段**: `psrc` (Vec[numSrc], PhyRegIdxWidth)、`pdest` (PhyRegIdxWidth)、`psrcVl`、`pdestVl` —— 逻辑到物理寄存器的映射。
- **`psrcIntForMove`**: Move 指令专用的整数源物理寄存器。
- **`robIdx`**: ROB 指针，标识该微操作在 ROB 中的位置。
- **`dirtyFs` / `dirtyVs`**: 浮点/向量状态脏位，用于上下文切换。
- **`traceBlockInPipe`**: 流水线内追踪信息 (TracePipe)，宽度为 IretireWidthEncoded。
- **`snapshot`**: 在该 CFI (Control Flow Instruction) 指令处拍摄 ROB 快照。
- **`numUops`**: 该宏指令拆分出的微操作总数。
- **内存依赖预测字段**: `storeSetHit`、`waitForRobIdx`、`loadWaitBit`、`loadWaitStrict`、`ssid` —— 用于存储集预测 (Store Set Prediction) 与 Load-Store 依赖处理。
- **`numLsElem`**: 向量 load/store 元素数量。
- **`crossFtqCommit` / `crossFtq`**: 跨 FTQ 条目提交与分支计算时的 FTQ 索引计算辅助字段。

辅助方法 `isLUI` 判断是否为 LUI 指令，`isAMOCAS` 判断是否为 AMO Compare-And-Swap 指令。

### 2.4 EnqRobUop —— 进入 ROB 的微操作

`EnqRobUop` (Bundles.scala:312-331) 继承自 `RenameOutUop`，增加：

- **`stdwriteNeed`**: Store 数据写入使能（由 `FuType.isStore` 决定）。
- **`isXSTrap`**: XiangShan 自定义 Trap 指令。
- **`replayInst`**: 重放标志，初始为 false。

### 2.5 DispatchOutUop / DispatchUpdateUop —— 调度输出

`DispatchOutBaseUop` (Bundles.scala:333-379) 是调度阶段输出的基础 Bundle，携带进入 Issue Queue 所需的全部信息：

- **调度扩展字段**: `srcState` (Vec[numSrc], SrcState()) 记录源操作数就绪状态；`srcLoadDependency` (Vec[numSrc], Vec[LoadPipelineWidth, LoadDependencyWidth]) 记录 Load 依赖链深度。
- **寄存器缓存**: `useRegCache` / `regCacheIdx` 用于加速整数寄存器读取。
- **`lqIdx` / `sqIdx`**: Load/Store Queue 指针，标识该指令在访存队列中的位置。
- **`rasAction`**: Return Address Stack 动作 (BranchAttribute.RasAction)。

`DispatchOutUop` 增加了 `isDropAmocasSta`（AMOCAS Store Address 丢弃标志），`DispatchUpdateUop` 进一步增加 `singleStep`（调试模块单步执行）。

### 2.6 RegionInUop —— 进入 Region 的微操作

`RegionInUop` (Bundles.scala:393-442) 采用 **Option 类型条件字段** 的设计，根据 IssueBlockParams 配置动态选择需要携带的字段：

```scala
val isRVC      = Option.when(params.needIsRVC)(Bool())
val fixedTaken = Option.when(params.needTaken)(Bool())
val rfWen      = Option.when(params.needIntWen)(Bool())
val fpWen      = Option.when(params.needFpWen)(Bool())
// ... 更多条件字段
```

这种设计使得不同调度区域（Int/FP/Vec）的微操作 Bundle 仅包含必要的字段，减少了硬件面积开销。

### 2.7 DynInst —— 动态指令完整表示

`DynInst` (Bundles.scala:542-672) 是重命名阶段输出的完整动态指令表示，也是 ROB 中存储的微操作格式。它包含从静态指令到动态执行的所有信息：

- **静态指令信息**: `instr`、`pc`、`foldpc`、`exceptionVec`、`isFetchMalAddr`、`hasException`、`trigger`、`isRVC`、`ftqPtr`、`ftqOffset` 等。
- **解码信息**: `srcType`、`ldest`、`fuType`、`fuOpType`、各种写使能 (rfWen/fpWen/vecWen/v0Wen/vlWen)、`selImm`、`imm`、`fpu`、`vpu`、`uopIdx`、`isVset`、`firstUop`、`lastUop`、`numUops`、`numWB`、`commitType`。
- **重命名信息**: `psrc`、`pdest`、`pdestVl`、`srcState`、`srcLoadDependency`、`rasAction`。
- **寄存器缓存**: `useRegCache`、`regCacheIdx`。
- **ROB 与追踪**: `robIdx`、`dirtyFs`、`dirtyVs`、`traceBlockInPipe`、`snapshot`、`perfDebugInfo`、`debug_seqNum`。
- **内存依赖**: `storeSetHit`、`waitForRobIdx`、`loadWaitBit`、`loadWaitStrict`、`ssid`、`lqIdx`、`sqIdx`。
- **特殊标志**: `nc` (Non-Cacheable)、`mmio`、`singleStep`、`replayInst`、`stdwriteNeed`。

辅助方法 `srcIsReady` 检查所有非寄存器类型的源操作数是否就绪；`isHls` 判断是否为 Hypervisor Load/Store 指令。

---

## 3. Redirect Bundle 与流水线恢复

### 3.1 FrontendRedirect —— 基类

`FrontendRedirect` (frontend/Bundles.scala:146-155) 定义了前端重定向的基础字段：

- **`ftqIdx`**: 重定向目标在 FTQ 中的索引。
- **`pc`**: 重定向源指令的 PC 地址。
- **`taken`**: 是否发生跳转。
- **`ftqOffset`**: FTQ 内偏移。
- **`isRVC`**: 是否为压缩指令。
- **`attribute`**: 分支属性 (BranchAttribute)，包含 RAS 动作等信息。
- **`target`**: 跳转目标地址。

### 3.2 Redirect —— 完整后端重定向

`Redirect` (Bundle.scala:211-255) 继承 `FrontendRedirect`，扩展了后端特有字段：

- **`level`**: 重定向级别 (Bool)，用于区分 flush 全流水线与仅 flush 自身之后的指令。
- **`backendIGPF` / `backendIPF` / `backendIAF`**: 后端指令页错误类型 (Instruction Guest Page Fault / Instruction Page Fault / Instruction Access Fault)。
- **`robIdx`**: 触发重定向的微操作在 ROB 中的索引。
- **`interrupt`**: 是否为中断引起的重定向。
- **`isMisPred`**: 是否为误预测。
- **`fullTarget`**: 完整目标地址 (XLEN 位)，用于后端 tval 存储。
- **`stFtqIdx` / `stFtqOffset` / `stIsRVC`**: Load 违反预测 (Load Violation Predict) 使用的 Store 指令 FTQ 信息。
- **调试字段**: `debug_runahead_checkpoint_id`、`debugIsCtrl`、`debugIsMemVio`。

`Redirect` 伴生对象提供两个关键静态方法：
- **`selectOldestRedirect`**: 从多个重定向源中选择最旧的（基于 robIdx 比较），返回 one-hot 向量。
- **`findOldestRedirect`**: 两路重定向源中选择最旧的一路。

辅助方法 `flushItself` 调用 `RedirectLevel.flushItself` 判断是否仅刷新自身；`getPcOffset` / `getStPcOffset` / `getNextPcOffset` 计算相对于 FTQ 条目的 PC 偏移量。

### 3.3 Resolve —— 分支解析

`Resolve` (Bundle.scala:284-293) 用于分支单元向前端报告解析结果：

- **`ftqIdx` / `ftqOffset`**: FTQ 中的位置。
- **`pc` / `target`**: PC 与解析目标 (PrunedAddr 类型)。
- **`taken` / `mispredict`**: 跳转判定与误预测标志。
- **`attribute`**: 分支属性。

---

## 4. WriteBack Bundle 体系

### 4.1 WriteBackBundle —— 统一写回格式

`WriteBackBundle` (Bundles.scala:1456-1548) 是执行单元输出与 DynInst 合并后的写回 Bundle：

- **写使能**: `rfWen`、`fpWen`、`vecWen`、`v0Wen`、`vlWen` —— 五种寄存器文件写控制。
- **`pdest`**: 物理目的寄存器索引，宽度由 `PregWB.pregIdxWidth` 参数化。
- **`data`**: 写回数据，宽度由 `PregWB.dataWidth` 参数化。
- **`robIdx`**: ROB 指针。
- **控制信号**: `flushPipe`、`replayInst`、`redirect` (ValidIO[Redirect])。
- **异常信息**: `fflags` (5 位浮点异常标志)、`vxsat` (向量饱和标志)、`exceptionVec`。
- **调试**: `debug` (DebugBundle)、`perfDebugInfo`、`debug_seqNum`。

转换方法支持将统一写回格式转为五种寄存器文件的写端口格式：
- `asIntRfWriteBundle(fire)` -> `RfWritePortBundle` (IntPregParams)
- `asFpRfWriteBundle(fire)` -> `RfWritePortBundle` (FpPregParams)
- `asVfRfWriteBundle(fire)` -> `RfWritePortBundle` (VfPregParams)
- `asV0RfWriteBundle(fire)` -> `RfWritePortBundle` (V0PregParams)
- `asVlRfWriteBundle(fire)` -> `RfWritePortBundle` (VlPregParams)

### 4.2 WriteBackRFBundle —— 精简寄存器文件写回

`WriteBackRFBundle` (Bundles.scala:1550-1624) 是写回 Bundle 的精简版本，仅保留寄存器文件写入所需的字段（rfWen/fpWen/vecWen/v0Wen/vlWen + pdest + data），去除了异常、重定向等信息。它的 `fromExuOutput` 方法从 `NewExuOutput` 中提取写回数据，支持 `wbType` 参数（"int"/"fp"/"vf"/"v0"/"vl"）选择数据源。

### 4.3 WriteBackRobBundle —— 写入 ROB 的信息

`WriteBackRobBundle` (Bundles.scala:1626-1646) 是写入 ROB 的结果 Bundle，携带执行结果的元数据：

- **`robIdx`**: 目标 ROB 条目。
- **条件字段** (Option 类型): `flushPipe`、`replay`、`redirect`、`fflags`、`wflags`、`vxsat`、`lqIdx`、`sqIdx`、`trigger`、`vls` —— 均根据 ExeUnitParams 的能力动态包含。
- **`exceptionVec`**: 异常向量。
- **`data` / `pdest`**: 结果数据与目的物理寄存器。
- **`vecWen` / `v0Wen`**: 向量写使能（仅当执行单元支持向量写回时存在）。

### 4.4 ExuOutput —— 执行单元输出

`ExuOutput` (Bundles.scala:1288-1334) 是执行单元直接产生的输出，包含完整的执行结果：

- **`data`**: Vec(wbPathNum, destDataBitsMax) 支持多路径写回。
- **`pdest` / `pdestVl`**: 物理目的寄存器。
- **五种写使能** (Option 类型): `intWen`、`fpWen`、`vecWen`、`v0Wen`、`vlWen`。
- **重定向**: `redirect` (Option[ValidIO[Redirect]])，分支/跳转执行单元使用。
- **异常**: `fflags`、`wflags`、`vxsat`、`exceptionVec`、`flushPipe`、`replay`。
- **访存**: `lqIdx`、`sqIdx`、`isFromLoadUnit`。
- **向量 Load 扩展** (`vls`): 携带 VPU 控制信号、旧 Vd 物理源寄存器、Vd 索引、是否 indexed/masked/strided/whole/mask-load。
- **`trigger`**: 触发器动作。

### 4.5 NewExuOutput —— 新一代执行单元输出

`NewExuOutput` (Bundles.scala:1362-1380) 将 `ExuOutput` 拆分为独立的写回通道：

- **`toRob`**: ValidIO[ExuOutputToRob] —— 写入 ROB 的控制与元数据。
- **独立寄存器文件端口**: `toIntRf`、`toFpRf`、`toVecRf`、`toV0Rf`、`toVlRf` —— 每个为 ValidIO(UInt)，数据宽度由 ExeUnitParams 决定。
- **`redirect`**: 分支/跳转重定向。
- **调试信息**: `debug`、`perfDebugInfo`、`debug_seqNum`。

---

## 5. Issue/Dispatch Bundle 体系

### 5.1 Og0InUop —— Issue Queue 到 DataPath 的 OG0 输入

`Og0InUop` (Bundles.scala:917-948) 是 Issue Queue 选择逻辑 (OG0, Operand Generation stage 0) 的输出：

- **`rcIdx`**: 寄存器缓存读索引 (Option 类型)。
- **`fuType` / `robIdx` / `iqIdx` / `isFirstIssue`**: 功能单元类型、ROB 索引、Issue Queue 索引、是否首次发射。
- **寄存器读使能**: `rfBankRen` (整数寄存器文件 Bank 读使能)、`fpRen`、`vecRen`、`v0Ren`、`vlRen`。
- **写使能**: `rfWen`、`fpWen`、`vecWen`、`v0Wen`、`vlWen`。
- **`pdest` / `pdestVl`**: 物理目的寄存器。
- **`dataSources`**: Vec[numRegSrc, DataSource()] —— 每个源操作数的数据来源指示（寄存器文件/Bypass/WakeUp）。
- **`exuSources`**: Option 类型，当执行单元作为 IQ WakeUp 源时存在，用于编码多个唤醒源。

### 5.2 Og1InUop —— OG1 阶段输入

`Og1InUop` (Bundles.scala:950-999) 在 Og0InUop 基础上增加了完整操作数数据：

- **`src`**: Vec[numRegSrc, srcDataBitsMax] —— 已获取的实际操作数数据。
- **`vl`**: 向量长度寄存器数据 (Option 类型)。
- **`pc` / `predTarget`**: PC 值与预测目标地址。
- **`fuOpType` / `selImm` / `imm`**: 功能操作类型与立即数。
- **`fpu` / `vpu`**: 浮点/向量控制信号。
- **访存字段**: `storeSetHit`、`waitForRobIdx`、`loadWaitBit`、`loadWaitStrict`、`ssid`、`lqIdx`、`sqIdx`。
- **`rasAction`**: RAS 动作信息。

### 5.3 ExuInput —— DataPath 到执行单元的输入

`ExuInput` (Bundles.scala:1073-1201) 是从 DataPath 发送到执行单元的最终输入格式：

- **`src`**: Vec[numRegSrc, srcDataBitsMax] —— 已经过 Bypass 网络选择的操作数数据。
- **`vl`**: 向量长度数据 (Option 类型)。
- **`is0Lat`**: 标识是否存在 0 延迟的功能单元（用于唤醒旁路）。
- **`copySrc`**: 复制源操作数，用于跨 Region 传递数据。
- **`imm` / `selImm`**: 64 位立即数与选择类型。
- **`nextPcOffset`**: 分支单元使用的下一条指令 PC 偏移。
- **控制字段**: `rfWen`、`fpWen`、`vecWen`、`v0Wen`、`vlWen`（Option 类型）、`fpu`、`vpu`、`vialuCtrl` (VIAluCtrlSignals)、`flushPipe`、`rasAction`、`predictInfo` (PredictInfo)。
- **地址字段**: `pc`、`isRVC`、`ftqIdx`、`ftqOffset`。
- **Copy 机制**: `pdestCopy`、`rfWenCopy`、`fpWenCopy`、`vecWenCopy`、`v0WenCopy`、`vlWenCopy`、`loadDependencyCopy` —— 用于跨 Region 的唤醒信号复制。

`fromIssueBundle` 方法从 Og0InUop 映射，`fromIssueOg1PayloadBundle` 从 EntryOg1Payload 映射完整操作数，`toDynInst()` 方法将 ExuInput 转换为 DynInst 用于 ROB 写回。

### 5.4 NewExuInput —— 结构化 ExuInput

`NewExuInput` (Bundles.scala:1256-1275) 将 `ExuInput` 的控制、数据、复制信号分别打包为子 Bundle：

- **`ctrl`**: ExuInputCtrlBundle —— 功能单元控制信号。
- **`data`**: ExuInputDataBundle —— 操作数数据。
- **`toRobValid`**: ROB 写入有效信号。
- **`robIdx`**: ROB 索引。
- **`toRF`**: ExuInputToRegFileBundle —— 寄存器文件写入信息。
- **`copy`**: ExuCopyBundle —— 复制唤醒信号。

### 5.5 IssueQueuePayload —— Issue Queue 条目载荷

`IssueQueuePayload` (Bundles.scala:506-526) 是 Issue Queue 中存储的完整微操作载荷：

- **`og1Payload`**: EntryOg1Payload —— OG1 阶段需要的字段子集。
- **`ftqPtr` / `ftqOffset`**: FTQ 指针与偏移。
- **`srcType` / `fuType`**: 源类型与功能单元类型。
- **`rfWen` / `fpWen` / `vecWen` / `v0Wen` / `vlWen`**: 写使能。
- **`pdest` / `pdestVl`**: 物理目的寄存器。
- **`srcLoadDependency`**: Load 依赖链。
- **调试**: `debug` (IssueQueueInDebug)。

---

## 6. WakeUp Bundle 体系

### 6.1 IssueQueueWakeUpBaseBundle —— 唤醒基类

`IssueQueueWakeUpBaseBundle` (Bundles.scala:698-772) 定义了唤醒信号的基础字段与方法：

- **字段**: `rfWen`、`fpWen`、`vecWen`、`v0Wen`、`vlWen`、`pdest`、`pdestVl`。
- **`wakeUp` 方法**: 接受 successor 的 (psrc, srcType) 列表，通过 pdest 匹配和写使能判断产生唤醒信号。
- **`wakeUpV0` / `wakeUpVl`**: V0 和向量长度寄存器的专用唤醒方法。
- **`exuIdx`**: 唤醒源执行单元索引（仅单源时可用）。

### 6.2 IssueQueueIQWakeUpBundle —— IQ 间唤醒

`IssueQueueIQWakeUpBundle` (Bundles.scala:782-811) 扩展基类，增加：

- **`loadDependency`**: Vec[LoadPipelineWidth, LoadDependencyWidth] —— Load 流水线依赖深度。
- **`is0Lat`**: 是否为 0 延迟路径。
- **`rcDest`**: 寄存器缓存写入目标索引。
- **`pdestCopy` / `rfWenCopy` / `fpWenCopy` / `vecWenCopy` / `v0WenCopy` / `vlWenCopy` / `loadDependencyCopy`**: 跨 Region 复制字段。

### 6.3 MemWakeUpBundle —— Load 单元唤醒

`MemWakeUpBundle` (Bundles.scala:685-692) 专用于 Load 单元的唤醒信号：

- `rfWen` / `fpWen` / `vecWen` / `v0Wen` / `vlWen` + `pdest`。

---

## 7. VPUCtrlSignals —— 向量处理单元控制

`VPUCtrlSignals` (Bundles.scala:813-900) 是向量指令的核心控制 Bundle，包含：

- **向量类型 (vtype)**: `vill` (illegal)、`vma` (mask agnostic)、`vta` (tail agnostic)、`vsew` (SELECTED ELEMENT WIDTH)、`vlmul` (Vector Length Multiplier)。
- **推测 vtype**: `specVill`、`specVma`、`specVta`、`specVsew`、`specVlmul` —— 用于 vsetvl 指令推测。
- **向量控制**: `vm` (mask 使用标志)、`vstart` (向量起始元素索引)、`vl` (向量长度)。
- **浮点**: `frm` (舍入模式)、`fpu` (FPU 控制)、`vxrm` (定点舍入模式)。
- **微操作**: `vuopIdx`、`lastUop`。
- **掩码**: `vmask` (V0Data 宽度的掩码向量)。
- **访存**: `nf` (nfield)、`veew` (有效元素宽度)。
- **操作属性**: `isReverse`、`isExt`、`isNarrow`、`isDstMask`、`isOpMask`、`isMove`、`isDependOldVd`、`isWritePartVd`、`isVleff`。
- **SEW 标志**: `sew8`、`sew16`、`sew32`、`sew64` —— 用于快速 SEW 判断。
- **`maskVecGen`**: 每字节掩码生成结果。

辅助方法 `vtype` / `specVType` / `vconfig` 将内部字段组装为标准 VType / VConfig Bundle。

---

## 8. BackendParams 全局配置

`BackendParams` (BackendParams.scala:36-539) 是后端的全局参数中心，持有：

### 8.1 核心参数

- **`schdParams`**: Map[SchedulerType, SchdBlockParams] —— 三种调度器（Int/FP/Vec）的参数配置。
- **`pregParams`**: Seq[PregParams] —— 五种物理寄存器文件参数（Int/FP/VF/V0/Vl）。
- **`iqWakeUpParams`**: Seq[WakeUpConfig] —— Issue Queue 间的唤醒连接配置。

### 8.2 寄存器文件配置

- **`intPregParams` / `fpPregParams` / `vfPregParams` / `v0PregParams` / `vlPregParams`**: 五种物理寄存器文件参数（从 `pregParams` 提取）。
- **`pregIdxWidth`**: 所有寄存器文件中最大的索引宽度。

### 8.3 执行单元统计

提供各类执行单元的全局计数：`AluCnt`、`StaCnt`、`StdCnt`、`LduCnt`、`HyuCnt`、`VlduCnt`、`VstuCnt`、`JmpCnt`、`BrhCnt`、`CsrCnt`、`IqCnt`。

### 8.4 寄存器端口配置

- **`getRdPortParams(dataCfg)`**: 返回指定数据配置的读端口参数，格式为 `port -> Seq[(exuIdx, priority)]`。
- **`getWbPortParams(dataCfg)`**: 返回写回端口参数。
- **`getRfReadSize` / `getRfWriteSize`**: 获取寄存器文件读写端口数。

### 8.5 BackwardWakeup (CopyPdest) 机制

`copyPdestInfo` 维护了一个 HashMap，记录需要复制物理目的寄存器的执行单元索引及其复制距离。当执行单元的 `copyWakeupOut` 为 true 时，其唤醒信号会被复制多份传递给不同的 Issue Queue，解决了跨 Region 唤醒的扇出问题。

### 8.6 后端实例化配置 (BackendV2SchdParams)

`BackendV2SchdParams` (BackendParams.scala:541-701) 定义了完整的后端配置实例：

**整数调度器** (`intSchdParams`): 11 个 Issue Block，包括：
- 4 个 ALU + BJU 组合 (ALU0-ALU3, BJU0-BJU2)，20 entries each
- 3 个 Load 单元 (LDU0-LDU2)，16 entries each
- 2 个 Store Address 单元 (STA0-STA1)，16 entries each
- 2 个 Store Data 单元 (STD0-STD1)，16 entries each

**浮点调度器** (`fpSchdParams`): 3 个 Issue Block：
- FEX0 (FaluCfg + FmacCfg + FcvtCfg + FcmpCfg + F2vCfg)，18 entries
- FEX1/FEX2 (FaluCfg + FmacCfg + FdivCfg)，18 entries each

**向量调度器** (`vecSchdParams`): 4 个 Issue Block：
- VFEX0 (多种向量 ALU/MA/PPU/IPU/FCVT/Move)，16 entries
- VFEX1 (向量 ALU/MA/FDIV/IDIV)，16 entries
- VLSU0 (向量 Load/Store/Segment)，16 entries
- VLSU1 (向量 Load/Store)，16 entries

**唤醒配置** (`iqWakeUpParams`): 定义了四组唤醒连接：
1. ALU0-3 + LDU0-2 -> ALU0-3 + LDU0-2 + STA0-1 + STD0-1 + BJU0-2
2. FEX0-2 -> FEX0-2
3. LDU0-2 -> FEX0-2
4. FEX0-2 -> STD0-1

---

## 9. Region 架构 —— 调度区域详解

### 9.1 Region 模块结构

`Region` (Region.scala:38-830) 是后端调度的核心模块，每个调度区域（Int/FP/Vec）实例化一个 Region。它由以下子模块组成：

- **`issueQueues`**: 多个 IssueQueue 实例，每个对应一个 IssueBlockParams。
- **`dataPath`**: 数据路径模块 (DataPath)，负责寄存器文件读取和操作数转发。
- **`bypassNetwork`**: 旁路网络 (BypassNetwork)，实现执行单元间的零延迟数据转发。
- **`exuBlock`**: 执行单元块 (ExuBlock)，封装该区域的所有功能单元。
- **`wbDataPath`**: 写回数据路径 (WbDataPath)，管理写回信号到寄存器文件的路由。
- **`og2ForVector`**: 仅向量调度器使用，处理向量 OG2 阶段响应。
- **`wbFuBusyTable`**: 写回功能单元繁忙表，追踪各执行单元的写回冲突。

### 9.2 WakeUp 连接逻辑

Region 内部的唤醒连接逻辑分为三层：

1. **IQ 内部唤醒** (`allWakeup`): 收集所有 Issue Queue 产生的 `wakeupToIQ` 信号，加上跨 Region 唤醒源。
   - 整数区域: IQ 内部唤醒 + FP 区域唤醒 (`wakeUpFromFp`)
   - 浮点区域: IQ 内部唤醒 + 整数区域 Load 单元唤醒 (`wakeUpFromInt.filter(hasLoadExu)`)
   - 向量区域: 仅 IQ 内部唤醒

2. **唤醒信号分发** (`iqWakeUpBundle`): 将唤醒信号按 `exuIdx` 建立 Map，分发给所有 Issue Queue。对于 `copyPdest` 执行单元，使用 `pdestCopy` 对应索引。

3. **WB 唤醒**: 根据调度器类型，从对应寄存器文件的写端口获取唤醒信号。

### 9.3 Store Data 重定向

Region 对 Store Data (STD) 指令有特殊处理逻辑 (Region.scala:261-293)：

- STD IQ 的 enq 来自对应 STA IQ 的 dispatch 源。
- `staReady && stdReady` 联合决定 dispatch 握手成功。
- STD IQ 的 `srcState(0)`、`psrc(0)` 等字段从 STA IQ 的 `srcState(1)`、`psrc(1)` 复用。

### 9.4 RegionIO —— Region 对外接口

`RegionIO` (Region.scala:832-947) 定义了 Region 的完整对外接口，关键端口包括：

- **`fromDispatch`**: MixedVec 类型，来自 dispatch 的微操作输入。
- **`flush`**: 重定向/刷新信号。
- **`ldCancel`**: Load 取消信号 (Vec[LduCnt, LoadCancelIO])。
- **跨区域唤醒**: `wakeUpFromFp` / `wakeUpFromInt` / `wakeupFromI2F` / `wakeupFromF2I`。
- **跨区域数据**: `cross` (ExuCrossRegion) —— I2F 和 F2I 的数据通道与唤醒信号。
- **寄存器文件写回**: `toIntPreg` / `toFpPreg` / `toVfPreg` / `toV0Preg` / `toVlPreg`。
- **写回数据输入**: `fromIntWb` / `fromFpWb` / `fromVfWb` / `fromV0Wb` / `fromVlWb`。
- **写回至 CtrlBlock**: `wbDataPathToCtrlBlock.writeback` / `delayedOldestExuRedirect`。
- **Mem 写回**: `memWriteback` —— 来自访存单元的写回数据。
- **性能统计**: `IQValidNumVec`、`uopTopDown`、debug 信号。

### 9.5 Oldest Redirect 选择

仅整数调度器执行 oldest redirect 选择 (Region.scala:717-731)：

```scala
val exuRedirects = wbDataPath.io.toCtrlBlock.writeback
  .filter(_.bits.redirect.nonEmpty)
  .filter(not flushed)
val oldestOneHot = Redirect.selectOldestRedirect(exuRedirects)
```

选出的最旧重定向经过一个周期延迟 (`RegNext` / `RegEnable`) 后输出到 `delayedOldestExuRedirect`。

---

## 10. 顶层通用 Bundle (Bundle.scala)

### 10.1 RSFeedback —— 访存反馈

`RSFeedback` (Bundle.scala:419-427) 用于访存单元向 Issue Queue 反馈执行状态：

- **`robIdx`**: 触发反馈的 ROB 索引。
- **`hit`**: 是否命中（TLB hit / Cache hit）。
- **`flushState`**: 是否处于刷新状态。
- **`sourceType`**: 反馈类型编码 (RSFeedbackType)，包括 lrqFull / tlbMiss / mshrFull / dataInvalid / bankConflict / ldVioCheckRedo 等 16 种。
- **`dataInvalidSqIdx` / `sqIdx` / `lqIdx`**: 相关队列索引。

### 10.2 RobCommitIO —— ROB 提交接口

`RobCommitIO` (Bundle.scala:356-369) 定义了 ROB 向 RAT 的提交接口：

- **`isCommit` / `commitValid`**: 提交使能与逐条有效位。
- **`isWalk` / `walkValid`**: 遍历/恢复使能与逐条有效位。
- **`info`**: Vec[CommitWidth, RobCommitInfo] —— 提交信息（含 ldest、pdest、rfWen/fpWen/vecWen/v0Wen/vlWen、isMove）。
- **`robIdx`**: Vec[CommitWidth, RobPtr]。

### 10.3 PerfDebugInfo —— 性能调试

`PerfDebugInfo` (Bundle.scala:186-199) 记录指令在各流水线阶段的时间戳：

- `eliminatedMove`、`renameTime`、`dispatchTime`、`enqRsTime`、`selectTime`、`issueTime`、`writebackTime`、`runahead_checkpoint_id`、`tlbFirstReqTime`、`tlbRespTime`。

---

## 11. Bundle 设计模式与命名约定

### 11.1 Option 类型条件字段

XiangShan 大量使用 `Option.when(condition)(Type)` 模式：

```scala
val rfWen   = Option.when(params.needIntWen)(Bool())
val pdestVl = Option.when(params.writeVlRf)(UInt(VlPhyRegIdxWidth.W))
```

这使得同一 Bundle 模板可以根据不同执行单元参数生成不同的硬件结构，避免了不必要的面积开销。Scala 层面的 `OptionWrapper` 也广泛用于简化 debug 信号和可选特征。

### 11.2 connectSamePort —— 反射式端口连接

`connectSamePort(sink, source)` (Bundles.scala:39-54) 通过 Chisel 的 `DataMirror` 反射，自动连接 sink 和 source 中**同名且同宽**的端口。这种模式在整个后端广泛使用，极大减少了手动连接代码。使用者仍需手动连接不匹配的端口。

### 11.3 Bundle 继承与演进

微操作 Bundle 采用继承链：
```
DecodeInUop -> DecodeOutUop -> RenameOutUop -> EnqRobUop -> DispatchOutUop -> DynInst
```

每个阶段在父类基础上增加新信息，体现了流水线逐级丰富微操作的设计思路。

### 11.4 参数化命名

- **`suggestName`**: Region 内部子模块根据调度器类型添加前缀（"int"/"fp"/"vec"）。
- **Issue Queue 命名**: `"issueQueue" + exuNames + "_" + iqFuName` —— 反映其包含的执行单元。
- **BundleSource trait**: `wakeupSource` 字段记录唤醒信号来源的字符串描述，便于调试。

### 11.5 命名约定总结

| 后缀/前缀 | 含义 |
|-----------|------|
| `Uop` | 微操作 (Micro Operation) |
| `InUop` / `OutUop` | 输入/输出微操作 |
| `Og0` / `Og1` / `Og2` | Operand Generation 第 0/1/2 阶段 |
| `IQ` / `IQWakeUp` | Issue Queue 相关 |
| `Wb` / `WriteBack` | WriteBack 写回 |
| `Rf` / `RfPort` | Register File 寄存器文件 |
| `Exu` / `ExuInput` / `ExuOutput` | Execution Unit 执行单元 |
| `Rob` | Reorder Buffer |
| `Preg` / `Pdest` / `Psrc` | Physical Register / Destination / Source |
| `Lq` / `Sq` | Load Queue / Store Queue |
| `Schd` | Scheduler 调度器 |
| `Dispatch` | 调度/分发 |
| `Ftq` | Fetch Target Queue |
| `Cancel` / `CancelSignal` | 取消/撤销 |
| `BusyTable` | 繁忙表 |

---

## 12. 源文件索引

| 文件 | 关键 Bundle | 行号范围 |
|-----|------------|---------|
| `backend/Bundles.scala` | `DecodeInUop` | 107-129 |
| `backend/Bundles.scala` | `DecodeOutUop` | 136-205 |
| `backend/Bundles.scala` | `RenameOutUop` | 231-302 |
| `backend/Bundles.scala` | `EnqRobUop` | 312-331 |
| `backend/Bundles.scala` | `DispatchOutBaseUop` | 333-379 |
| `backend/Bundles.scala` | `RegionInUop` | 393-442 |
| `backend/Bundles.scala` | `EntryOg1Payload` | 444-472 |
| `backend/Bundles.scala` | `IssueQueuePayload` | 506-526 |
| `backend/Bundles.scala` | `DynInst` | 542-672 |
| `backend/Bundles.scala` | `IssueQueueWakeUpBaseBundle` | 698-772 |
| `backend/Bundles.scala` | `IssueQueueIQWakeUpBundle` | 782-811 |
| `backend/Bundles.scala` | `VPUCtrlSignals` | 813-900 |
| `backend/Bundles.scala` | `Og0InUop` | 917-948 |
| `backend/Bundles.scala` | `Og1InUop` | 950-999 |
| `backend/Bundles.scala` | `ExuInput` | 1073-1201 |
| `backend/Bundles.scala` | `NewExuInput` | 1256-1275 |
| `backend/Bundles.scala` | `ExuCrossRegion` | 1278-1285 |
| `backend/Bundles.scala` | `ExuOutput` | 1288-1334 |
| `backend/Bundles.scala` | `NewExuOutput` | 1362-1380 |
| `backend/Bundles.scala` | `WriteBackBundle` | 1456-1548 |
| `backend/Bundles.scala` | `WriteBackRFBundle` | 1550-1624 |
| `backend/Bundles.scala` | `WriteBackRobBundle` | 1626-1646 |
| `backend/Bundles.scala` | `ExuBypassBundle` | 1651-1657 |
| `backend/Bundles.scala` | `ExceptionInfo` | 1659-1674 |
| `backend/Bundles.scala` | `ExuSource` | 1686-1723 |
| `backend/Bundles.scala` | `CancelSignal` | 1733-1740 |
| `backend/Bundles.scala` | `MemExuOutput` | 1742-1790 |
| `backend/BackendParams.scala` | `BackendParams` | 36-539 |
| `backend/BackendParams.scala` | `BackendV2SchdParams` | 541-701 |
| `backend/Region.scala` | `Region` | 38-830 |
| `backend/Region.scala` | `RegionIO` | 832-947 |
| `backend/issue/SchdBlockParams.scala` | `SchdBlockParams` | 18-150 |
| `Bundle.scala` | `CtrlFlow` | 94-118 |
| `Bundle.scala` | `CtrlSignals` | 128-179 |
| `Bundle.scala` | `Redirect` | 211-255 |
| `Bundle.scala` | `Resolve` | 284-293 |
| `Bundle.scala` | `DebugBundle` | 310-321 |
| `Bundle.scala` | `RobCommitIO` | 356-369 |
| `Bundle.scala` | `RSFeedback` | 419-427 |
| `Bundle.scala` | `MemRSFeedbackIO` | 429-434 |
| `Bundle.scala` | `FrontendToCtrlIO` | 446-458 |
| `Bundle.scala` | `PerfDebugInfo` | 186-199 |
| `frontend/Bundles.scala` | `FrontendRedirect` | 146-155 |

---

## 13. 总结

XiangShan 后端 Bundle 体系的设计体现了以下核心理念：

1. **参数化与条件生成**: 通过 `Option.when` 和 `OptionWrapper`，同一 Bundle 模板可以根据不同执行单元的能力自动裁剪字段，实现面积效率最优。

2. **流水线逐级演进**: 微操作从 DecodeInUop 到 DynInst 经历多次变换，每个阶段只添加必要的信息，体现了 RISC-V 后端的"信息按需流动"原则。

3. **多寄存器文件统一抽象**: 通过 `rfWen/fpWen/vecWen/v0Wen/vlWen` 五写使能和 `WriteBackBundle.as*RfWriteBundle` 转换方法，实现了对五种寄存器文件的统一处理。

4. **跨 Region 唤醒复制**: `copyPdest` 机制和 `ExuCopyBundle` 解决了唤醒信号扇出问题，支持多 Region 之间的零延迟唤醒。

5. **反模式检测与调试**: `connectSamePort` 通过反射自动连接，`BundleSource` trait 记录信号来源，`PerfDebugInfo` 追踪全流水线时延，构成了完整的可观测性体系。
