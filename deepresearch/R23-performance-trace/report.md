# R23 - XiangShan 性能监控与追踪系统研究报告

## 目录

1. [概述](#1-概述)
2. [性能监控架构](#2-性能监控架构)
3. [Trace 导出机制](#3-trace-导出机制)
4. [Top-down 分析方法论](#4-top-down-分析方法论)
5. [性能计数器事件类型](#5-性能计数器事件类型)
6. [Debug 工具链](#6-debug-工具链)
7. [功能覆盖率分析](#7-功能覆盖率分析)
8. [关键源码文件索引](#8-关键源码文件索引)
9. [总结](#9-总结)

---

## 1. 概述

XiangShan 是中国科学院计算技术研究所开发的开源 RISC-V 处理器核，采用 Chisel 硬件描述语言实现。其性能监控与追踪系统是处理器可调试性（debuggability）和可观测性（observability）的核心基础设施，涵盖了硬件性能计数器（Hardware Performance Counters）、指令执行追踪（Instruction Trace）、Top-down 微架构分析、功能覆盖率统计以及多种调试工具。本报告从源码实现角度，系统性地梳理这些子系统的设计与实现。

---

## 2. 性能监控架构

### 2.1 总体架构概览

XiangShan 的性能监控架构分为三个层次：

1. **CSR 级性能计数器**（Performance Counter Registers）：符合 RISC-V Privileged Architecture 规范的标准性能计数器，包括 `cycle`、`instret`、`hpmcounter3` 至 `hpmcounter31`，以及对应的 `mhpmevent` 事件选择寄存器。这些计数器映射到 CSR 地址空间，可由软件直接读写。

2. **内部性能事件采集**（Internal Performance Events）：通过 `XSPerfAccumulate`、`XSPerfRolling`、`XSPerfHistogram` 等 Chisel utility 宏，在各模块内部嵌入的性能计数器。这些计数器在仿真时通过 `[PERF]` 格式输出到 `simulator_err.txt`，用于性能分析和调试。

3. **Top-down 分析计数器**：专门用于处理器微架构 Top-down 分析方法论的 stall/bubble 原因追踪计数器，由 `TopDownCounters` 枚举定义，通过 `TopDownGen` 模块采集。

### 2.2 CSR 级性能计数器实现

在 RISC-V 标准中，性能计数器通过 CSR 寄存器地址空间暴露给软件。XiangShan 在两个模块中实现了这些计数器：

**旧版 CSR 模块**（`src/main/scala/xiangshan/backend/fu/CSR.scala`）：

```scala
val perfCntMapping = (0 until 29).map(i => {Map(
  MaskedRegMap(addr = Mhpmevent3 + i, reg = perfEvents(i),
               wmask = "hf87fff3fcff3fcff".U(XLEN.W)),
  MaskedRegMap(addr = Mhpmcounter3 + i, reg = perfCnts(i)),
  MaskedRegMap(addr = Hpmcounter3 + i, reg = perfCnts(i))
)}).fold(Map())((a,b) => a ++ b)
```

这段代码将 29 个 HPM 计数器（hpmcounter3 到 hpmcounter31）映射到 CSR 地址空间，同时支持 M-mode（`Mhpmcounter`）和 U/S-mode（`Hpmcounter`）的读取权限。事件选择寄存器 `Mhpmevent` 的写掩码为 `hf87fff3fcff3fcff`，对应 RISC-V 规范中定义的可写位域。

**新版 CSR 模块**（`src/main/scala/xiangshan/backend/fu/NewCSR/Unprivileged.scala`）：

```scala
val hpmcounters: Seq[CSRModule[_]] = (3 to 0x1F).map(num =>
  Module(new CSRModule(s"Hpmcounter$num", new CSRBundle {
    val hpmcounter = RO(63, 0).withReset(0.U).withDescription("Counter value.")
  }) with HasMHPMSink with HasDebugStopBundle {
    when(unprivCountUpdate) {
      reg := mHPM.hpmcounters(num - 3)
    }
    regOut := Mux(debugModeStopCount, reg.asUInt, mHPM.hpmcounters(num - 3))
  }).setAddr(CSRs.cycle + num)
)
```

新版实现通过 `HasMHPMSink` trait 连接 M-mode 的 HPM 事件源，将 M-mode 下配置的计数事件映射到 unprivileged CSR 读取接口。`debugModeStopCount` 信号确保在 Debug Mode 下计数器行为可控，这与 RISC-V Debug Specification 要求一致。

关键 CSR 寄存器地址映射：
- `cycle` / `mcycle`：时钟周期计数器
- `instret` / `minstret`：指令退休计数器
- `hpmcounter3` - `hpmcounter31`：29 个通用 HPM 计数器
- `mhpmevent3` - `mhpmevent31`：对应事件选择寄存器
- `mcounteren` / `scounteren`：计数器使能控制

### 2.3 内部性能事件采集机制

XiangShan 提供了一套 Chisel utility 宏用于在硬件模块中嵌入性能计数器。这些宏定义在 utility 库中，主要包括：

- **`XSPerfAccumulate(name, condition, level)`**：最基础的累加计数器，每当 `condition` 为真时递增。支持通过 `XSPerfLevel` 设置输出优先级（如 `CRITICAL` 表示关键计数器始终输出）。

- **`XSPerfRolling(name, perfCnt, granularity, clock, reset)`**：滚动统计计数器，以指定 `granularity`（采样窗口大小）计算 IPC/CPI 等比率指标。例如 ROB 模块中的：
  ```scala
  XSPerfRolling("ipc", ifCommitReg(trueCommitCnt), 1000, clock, reset)
  XSPerfRolling("cpi", perfCnt = 1.U, eventTrigger = ifCommitReg(trueCommitCnt), granularity = 1000, clock, reset)
  ```

- **`XSPerfHistogram(name, value, enable, min, max)`**：直方图统计，用于分布分析，如 ROB 中的 walk 周期分布：
  ```scala
  XSPerfHistogram("walkRobCycleHist", walkCycle, state === s_walk && walkFinished, 0, 32)
  ```

这些计数器的输出格式为 `[PERF][time=<cycle>]<module_path>.<counter_name>, <value>`，可被仿真器捕获到 `simulator_err.txt` 文件中，进而被 Top-down 分析工具解析。

### 2.4 总线性能监控

`BusPerfMonitor`（`src/main/scala/top/BusPerfMonitor.scala`）模块用于监控 TileLink 总线上的流量与延迟：

```scala
class BusPerfMonitor(name: String, stat_latency: Boolean)(implicit p: Parameters) extends LazyModule {
  val node = TLAdapterNode()
  ...
}
```

该模块在每个 TileLink 通道（A/B/C/D/E）上插入监测点，采集以下指标：
- 每种 opcode 的 fire（发送成功）次数和 stall（阻塞）次数
- 请求延迟统计：通过记录 Channel A 发送时间戳和 Channel D 响应时间戳，计算端到端延迟
- 按 `MemReqSource` 类型分类的延迟统计

### 2.5 性能事件到 CSR 的通路

`TopDownGen` 模块（`src/main/scala/xiangshan/backend/TopDownGen.scala`）将采集到的内部性能事件汇总为标准性能事件序列，通过 `generatePerfEvent()` 连接到 M-mode 的 HPM 事件选择逻辑：

```scala
val perfEvents = Seq(
  ("EXEC_STALL_CYCLE",  fewUopsIssued),
  ("MEMSTALL_STORE",    memStallStore),
  ("MEMSTALL_L1MISS",   memStallL1Miss),
  ("MEMSTALL_L2MISS",   memStallL2Miss),
  ("MEMSTALL_L3MISS",   memStallL3Miss),
)
generatePerfEvent()
```

这些事件可以通过 `mhpmevent` CSR 寄存器选择并映射到 `hpmcounter` 计数器，使得软件可以通过标准 CSR 读取方式获取这些硬件微架构事件的计数。

---

## 3. Trace 导出机制

### 3.1 Trace 模块架构

XiangShan 的 Trace 系统位于 `src/main/scala/xiangshan/backend/trace/` 目录下，包含三个核心文件：

- **`Interface.scala`**：定义所有 Trace 相关的数据类型和接口
- **`Trace.scala`**：Trace 流水线的顶层模块
- **`TraceBuffer.scala`**：Trace 数据的压缩缓冲区

Trace 系统遵循 RISC-V Trace Encoder 规范的思路设计，但未在芯片内部集成完整的 Nexus Trace Encoder 或 eTrace Encoder IP。相反，XiangShan 将 Trace 数据通过 `TraceCoreInterface` 输出到 SoC 顶层，由外部 Trace Encoder 完成协议编码。

### 3.2 核心数据类型（Interface.scala）

**Itype（指令类型）**：4-bit 编码，定义了 16 种指令追踪类型：

| 编码 | 名称 | 来源阶段 | 说明 |
|------|------|----------|------|
| 0 | None | - | 无追踪信息（压缩时使用） |
| 1 | Exception | ROB | 异常 |
| 2 | Interrupt | ROB | 中断 |
| 3 | ExpIntReturn | Rename | 异常/中断返回 |
| 4 | NonTaken | Commit | 分支未跳转 |
| 5 | Taken | Commit | 分支跳转 |
| 8 | UninferableCall | Rename | 不可预测的 Call |
| 9 | InferableCall | Rename | 可预测的 Call |
| 10 | UninferableTailCall | Rename | 不可预测的 Tail Call |
| 11 | InferableTailCall | Rename | 可预测的 Tail Call |
| 12 | CoRoutineSwap | Rename | 协程切换（Link-Link 互跳） |
| 13 | FunctionReturn | Rename | 函数返回 |
| 14 | OtherUninferableJump | Rename | 其他不可预测跳转 |
| 15 | OtherInferableJump | Rename | 其他可预测跳转 |

Itype 的分类依据是 RISC-V Trace 规范中对可推断（Inferable）与不可推断（Uninferable）跳转的定义。可推断跳转的地址可以从指令本身推导（如直接 JAL），而不可推断跳转需要从目标地址获取（如 JALR）。

**OpRegType**：用于识别 link 寄存器（x1 ra、x5 t0），判断 Call/Return 语义：

```scala
class OpRegType extends Bundle {
  val value = UInt(6.W)
  def isX0   = this.value === 0.U
  def isX1   = this.value === 1.U
  def isX5   = this.value === 5.U
  def isLink = Seq(isX1, isX5).reduce(_ || _)
}
```

**Priv（特权级）**：3-bit 编码，支持 HU/HS/M/D/VU/VS 六种特权模式。

**TraceBlock**：单条追踪记录，包含可选的指令地址（`iaddr`）或 FTQ 索引（`ftqIdx` + `ftqOffset`），以及 TracePipe（itype + iretire + ilastsize）。

**TraceBundle**：多个 TraceBlock 的向量，用于批量传输。

### 3.3 Trace 流水线（Trace.scala）

Trace 模块实现了一个 4 级流水线：

**Stage 0 - CommitInfo from ROB**：从 ROB（Reorder Buffer）获取提交信息，包括每周期提交的指令块（`fromRob`），每个块包含指令类型、退休数量、最后指令大小等。

**Stage 1 - RegNext(robCommitInfo)**：对提交信息进行寄存器打拍，当 `blockCommit` 为真时保持不变（流水线停顿）。

**Stage 2 - Compress & TraceBuffer**：核心压缩逻辑。`TraceBuffer` 模块将连续的 NonTaken（分支未跳转）等不需要独立追踪的指令进行压缩合并，仅保留需要独立追踪的块（Exception、Interrupt、各种跳转类型等）。压缩的关键逻辑是 `needPcVec`：只有当指令需要独立追踪，或者其右侧没有需要追踪的有效指令时，才保留该块。

**Stage 3 - groups with iaddr from PCMem**：通过 FTQ 索引查询 PCMem（Program Counter Memory），将 `ftqIdx` + `ftqOffset` 解码为实际指令地址（`iaddr`），然后输出给外部 Trace Encoder。

### 3.4 TraceBuffer 压缩机制

`TraceBuffer` 使用环形队列实现，核心设计如下：

- **压缩策略**：对于 `itype == None` 的普通指令（顺序执行，无跳转/异常），将 `iretire` 计数合并到下一个需要追踪的块中，从而减少需要输出的追踪块数量。这是 Trace 压缩的核心：只有控制流变化点（分支、异常、跳转）才需要独立追踪。

- **背压控制**：当 TraceBuffer 满或外部 Encoder 发出 stall 信号时，`blockCommit` 信号为真，阻止 ROB 提交新指令，实现流水线停顿。

- **分组输出**：每周期输出 `TraceGroupNum` 个追踪块，形成一个 Trace Group。

### 3.5 Trace 数据导出路径

Trace 数据从核心内部导出到 SoC 顶层的完整路径如下：

1. **CtrlBlock**（`backend/CtrlBlock.scala`）：实例化 `Trace` 模块，连接 ROB 的提交信息和 Encoder 的控制信号。同时从 PCMem 读取指令地址，组装 `traceCoreInterface.toEncoder` 输出。

2. **Backend**（`backend/Backend.scala`）：将 `ctrlBlock.io.traceCoreInterface` 透传到 Backend 的输出端口。

3. **XSCore**（`XSCore.scala`）：将 Backend 的 trace 信号连接到 `memBlock` 的 bypass 逻辑，再输出到 Tile 层。

4. **XSTile**（`XSTile.scala`）：将 core 的 trace 输出通过 `l2top` 模块透传到 Tile 输出。

5. **L2Top**（`L2Top.scala`）：实现 trace 信号的寄存器打拍和流水线级同步：
   ```scala
   traceFromCore.fromEncoder := RegNext(traceToTile.fromEncoder)
   traceToTile.toEncoder.groups(i).valid := RegNext(traceFromCore.toEncoder.groups(i).valid)
   ```

6. **XSTileWrap**（`XSTileWrap.scala`）：使用 `AsyncResetSynchronizerShiftReg` 对 `fromEncoder.enable` 和 `fromEncoder.stall` 信号进行异步复位同步，处理跨时钟域问题。

7. **Top**（`top/Top.scala`）：将 `TraceCoreInterface` 展平为 SoC 级 IO 信号，供外部 Trace Encoder 模块使用：
   ```scala
   val traceCoreInterface = Vec(NumCores, new Bundle {
     val cause, tval, priv, mstatus ...
     val valid, iaddr, itype, iretire, ilastsize ...
   })
   ```

### 3.6 TraceCoreInterface 接口定义

`TraceCoreInterface` 是核心与外部 Encoder 之间的标准接口：

- **fromEncoder**：外部 Encoder 发出的控制信号
  - `enable`：使能追踪
  - `stall`：追踪暂停

- **toEncoder**：核心输出的追踪数据
  - `priv`：当前特权级
  - `mstatus`：机器状态寄存器（用于上下文恢复）
  - `trap.cause` / `trap.tval`：异常/中断原因和附加信息
  - `groups`：追踪块向量，每块包含 `iaddr`、`itype`、`iretire`、`ilastsize`

这种设计将追踪数据的产生（核心内部）与编码（外部 Encoder）解耦，使得 SoC 集成者可以根据需要选择不同的 Trace Encoder 实现（如 ARM CoreSight 兼容的 Nexus Trace 或 RISC-V 原生 eTrace）。

---

## 4. Top-down 分析方法论

### 4.1 方法论概述

XiangShan 实现了一套完整的 Top-down 微架构性能分析框架，参考了 Intel 的 Top-down Microarchitecture Analysis Method (TMAM) 和 ARM 的 Top-down 计数方法，但针对 XiangShan 的特定微架构进行了定制。

该框架的核心思想是：将每个时钟周期归类为以下类别之一：
- **Commit（有效提交）**：每周期提交的指令数等于 Issue Width 时为 Full Commit
- **Frontend Bound（前端瓶颈）**：前端无法提供足够指令
- **Backend Bound（后端瓶颈）**：后端无法处理已提供的指令
  - **Bad Speculation（错误推测）**：推测执行的指令被取消
  - **Memory Bound（访存瓶颈）**：访存延迟导致的停顿
  - **Core Bound（核心瓶颈）**：非访存的后端资源不足
- **Stall（流水线停顿）**：各阶段的细粒度停顿原因

### 4.2 TopDownCounters 枚举定义

`TopDownCounters`（定义在 `src/main/scala/xiangshan/package.scala`）定义了所有追踪原因，按流水线阶段分为：

**Frontend 相关**（14 个原因）：
- OverrideBubble：前端覆盖冒泡
- FtqUpdateBubble / FtqFullStall：FTQ 更新/满停顿
- TAGEMissBubble / SCMissBubble / ITTAGEMissBubble / RASMissBubble：分支预测器命中失败
- ICacheMissBubble / ITLBMissBubble / BTBMissBubble：缓存/TLB 缺失
- FetchFragBubble：取指碎片
- MemVioRedirectBubble / OtherRedirectBubble：重定向冒泡
- FrontendOtherCoreStall：前端其他停顿

**Backend 相关**（约 20 个原因）：
- DivStall / IntNotReadyStall / FPNotReadyStall / MemNotReadyStall：长延迟指令导致的停顿
- RobStall：ROB 满
- IntFlStall / FpFlStall / VecFlStall / V0FlStall / VlFlStall / MultiFlStall：Freelist 满
- FusionBubble：指令融合冒泡
- LoadDispatchPolicyStall / StoreDispatchPolicyStall / OtherDispatchPolicyStall：调度策略限制
- BalanceDispatchPolicyStall{Alu,Brh,Int,Fp,Vec,Load,Store}：平衡调度限制
- IntIQFullStall{Alu,Brh,Other} / FpIQFullStall / VecIQFullStall / LoadIQFullStall / StoreIQFullStall：Issue Queue 满

**Memory 相关**：
- LoadTLBStall / LoadL1Stall / LoadL2Stall / LoadL3Stall / LoadMemStall：Load 访存在各层级的停顿
- StoreStall / AtomicStall：Store 和原子操作停顿
- LoadVioReplayStall / LoadMSHRReplayStall：Load 重放

**Bad Speculation 相关**：
- ControlRedirectStall / MemVioRedirectStall / OtherRedirectStall：重定向停顿
- ControlRecoveryStall / MemVioRecoveryStall / OtherRecoveryStall：恢复停顿
- FlushedInsts：被冲刷的指令数
- SpecialInsts：特权指令

### 4.3 Top-down 数据采集

Top-down 信息在流水线各阶段采集，通过专门的 IO Bundle 传递：

- **FrontendTopDownBundle**（`frontend/Bundles.scala`）：前端产生 `reasons` 向量，标记每个 decode 宽度槽位的停顿原因
- **CoreDispatchTopDownIO**（`backend/dispatch/Dispatch.scala`）：Dispatch 阶段产生停顿原因
- **MemCoreTopDownIO**（`mem/MemBlock.scala`）：访存模块产生访存停顿信息
- **TopDownInfo**（`Bundle.scala`）：包含 `lqEmpty`、`sqEmpty`、`l1Miss`、`l2TopMiss` 等全局状态

`TopDownGen` 模块汇总所有来源的信息，计算 `MEMSTALL_L1MISS`、`MEMSTALL_L2MISS`、`MEMSTALL_L3MISS` 等层次化的访存瓶颈指标。

### 4.4 离线分析工具

**`scripts/top-down/`** 目录提供了完整的离线分析工具链：

**`top_down.py`**（主程序）：
1. 从仿真器输出的 `simulator_err.txt` 中解析性能计数器数值
2. 支持 baseline/ref 两个版本的对比分析（支持不同 issue width 的归一化）
3. 使用 `configs.py` 中定义的正则表达式匹配 `[PERF][time=<cycle>]<module>.<counter>` 格式
4. 通过 JSON 配置文件获取各采样点的权重
5. 计算加权 IPC 和加权 Top-down 指标

**`configs.py`**（配置文件）：
- `targets` 字典：定义了所有 Top-down 计数器的正则表达式匹配模式
- `xs_coarse_rename_map`：粗粒度分类映射（将细粒度原因合并为 Frontend/Backend/Memory 等大类）
- `xs_fine_grain_rename_map`：细粒度分类映射
- `xs_frontend/backend/mem/custom_rename_map`：各子维度的分类映射
- `spec_bmks`：SPEC 2006 benchmark 列表（整数/浮点分类）

**`draw.py`**（绘图脚本）：
生成多维度的堆叠条形图（Stacked Bar Chart）：
- `result_total.png`：总体 Top-down 分析
- `result_frontend.png`：前端子维度分析
- `result_backend.png`：后端子维度分析
- `result_mem.png`：访存子维度分析
- `result_custom.png`：自定义子维度分析

**`utils.py`**（工具函数）：
- `xs_get_stats()`：从 `simulator_err.txt` 解析计数器数值
- `glob_stats()`：递归搜索仿真输出目录
- 支持 IPC 自动计算：`ipc = commitInstr / total_cycles`

---

## 5. 性能计数器事件类型

### 5.1 ROB 级计数器

ROB（Reorder Buffer）是性能计数器最密集的模块之一，提供以下关键指标：

```scala
XSPerfAccumulate("clock_cycle", 1.U, XSPerfLevel.CRITICAL)  // 总周期数
XSPerfAccumulate("commitInstr", ifCommitReg(trueCommitCnt), XSPerfLevel.CRITICAL)  // 提交指令数
XSPerfRolling("ipc", ifCommitReg(trueCommitCnt), 1000, clock, reset)  // 实时 IPC
XSPerfRolling("cpi", perfCnt = 1.U, eventTrigger = ifCommitReg(trueCommitCnt), granularity = 1000, clock, reset)  // 实时 CPI
XSPerfAccumulate("commitInstrFused", ifCommitReg(fuseCommitCnt))  // 融合指令数
XSPerfAccumulate("commitInstrLoad", ifCommit(PopCount(commitLoadValid)))  // Load 指令提交数
XSPerfAccumulate("commitInstrBranch", ifCommit(PopCount(commitBranchValid)))  // 分支指令提交数
XSPerfAccumulate("commitInstrStore", ...)  // Store 指令提交数
```

此外还有 ROB 内部状态追踪：
- `s_idle_to_idle` / `s_idle_to_walk` / `s_walk_to_idle` / `s_walk_to_walk`：ROB 状态转换
- `walkInstr` / `walkCycleTotal`：Walk 操作指令数和周期数
- `waitAluCycle` / `waitMulCycle` / `waitDivCycle`：ROB 头指令等待各类功能单元的周期
- 各类直方图统计（`walkRobCycleHist`、`walkTotalCycleHist`）

### 5.2 前端性能事件

- ICache 缺失、ITLB 缺失、BTB 缺失等 BPU 预测器事件
- FTQ 满/更新停顿
- 取指碎片（Fetch Fragment）
- 各分支预测器（TAGE/SC/ITTAGE/RAS）的 miss 事件

### 5.3 访存子系统事件

- L1 DCache、L2 Cache、L3 Cache 缺失
- TLB 缺失（Load TLB Stall）
- Store Buffer（Sbuffer）满停顿
- Load Queue / Store Queue 满停顿
- Memory Dependency Prediction（MDP）相关事件
- Load Violation Replay / MSHR Replay

### 5.4 总线级事件

`BusPerfMonitor` 在 TileLink 总线上采集的事件：
- 每种 TileLink opcode（Get/Put/Access/Release 等）的 fire 和 stall 计数
- 请求类型分类统计（按 `MemReqSource` 类型）
- 端到端请求延迟统计

---

## 6. Debug 工具链

### 6.1 RISC-V Debug Module

XiangShan 集成了标准的 RISC-V Debug Module，位于 `src/main/scala/device/RocketDebugWrapper.scala`：

**`DebugModule`**：封装了 Rocket-Chip 的 `TLDebugModule`，提供：
- HART Reset 控制
- DMI（Debug Module Interface）连接
- JTAG DTM（Debug Transport Module）实例化

```scala
class DebugModule(numCores: Int)(implicit p: Parameters) extends LazyModule {
  val debug = LazyModule(new TLDebugModule(8)(p))
  ...
}
```

**`DebugModuleIO`**：对外暴露的调试接口，包括：
- `resetCtrl`：HART 复位控制
- `debugIO`：调试协议 IO（JTAG、DMI）
- `clock` / `reset`：调试时钟域

**`SimJTAG`**：仿真环境的 JTAG 模拟器（Verilog ExtModule），通过 `TICK_DELAY` 参数控制 JTAG 时序：
```scala
class SimJTAG(tickDelay: Int = 50)(implicit val p: Parameters) extends ExtModule(...)
```

SoC 顶层（`Top.scala`）将 `DebugModule` 连接到 JTAG 接口：
```scala
val jtag = Flipped(new JTAGIO(hasTRSTn = false))
io.debug_reset := misc.module.debug_module_io.debugIO.ndreset
```

### 6.2 XSPdb 调试工具

`docs/XSPdb/` 提供了 XSPdb（XiangShan Python debugger），一个基于 Python `pdb` 的交互式调试工具，专为 XiangShan 的 difftest 接口定制。

**核心功能**：

1. **断点与触发器（Breakpoints & Triggers）**：
   - `xbreak`：传统地址断点
   - `xbreak_expr`：表达式触发器（支持复杂条件组合）
   - `xbreak_fsm`：FSM 状态触发器（用 `.fsm` 文件描述状态机模式）

2. **波形控制（Waveform Control）**：
   - `xwave_on/off/flush`：波形记录开关
   - `xwave_continue`：条件波形继续

3. **Fork 备份波形**：
   - `xfork_backup_*`：在 xbreak 触发时捕获波形快照

4. **可见性工具（Visibility）**：
   - `xprint`：打印信号值
   - `xwatch`：监视点
   - `xset`：设置信号值
   - `xpc`：查看当前 PC

5. **文本 UI（Text UI）**：
   - `xui`：交互式文本界面
   - `xui save/load`：布局保存/加载
   - `xtheme`：主题控制

**使用方式**：

交互模式：
```
xcmds          # 列出可用命令
xpc            # 查看当前 PC
xload /abs/path/to/bin  # 加载程序
xwave_on       # 开启波形记录
xui            # 进入文本 UI
xstep 1000     # 单步执行 1000 步
xwave_off      # 关闭波形记录
```

批量模式：
```
python3 scripts/pdb-run.py --script /abs/path/to/docs/XSPdb/examples/xspdb_script_example.txt
```

**设计文档**：`docs/XSPdb/design/` 目录包含详细的功能规格说明，如触发器表达式规范（`trigger_expr_spec.md`）、触发器 FSM 规范（`trigger_fsm_spec.md`）等。示例文件 `examples/csr_trace.fsm` 和 `examples/pc_sequence.fsm` 展示了 FSM 触发器的使用方式。

### 6.3 Debug 脚本与 CI

`debug/` 目录包含调试和 CI 脚本：

- **`Makefile`**：提供多种测试目标
  - `cputest` / `bputest`：CPU 指令测试和 BPU 测试
  - `microbench` / `coremark` / `dhrystone`：标准性能基准测试
  - `linux` / `xv6` / `freertos` / `rttos`：操作系统启动测试
  - `unit-test` / `l1-test` / `tlc-test`：Chisel 单元测试

- **`local_ci.py`**：本地 CI 流程脚本，从 YAML 配置文件解析测试用例并执行
- **`cputest.sh`**：CPU 指令集测试脚本
- **`perf_sbuffer.sh`**：Sbuffer 性能测试脚本

---

## 7. 功能覆盖率分析

### 7.1 覆盖率统计工具

`scripts/coverage/statistics.py` 实现了基于 Verilog 仿真覆盖率报告的统计分析工具，支持两种覆盖率指标：

1. **Line Coverage（行覆盖率）**：统计 Verilog 代码中每行语句是否被执行
2. **Toggle Coverage（翻转覆盖率）**：统计每个信号（reg/wire/input/output）是否发生了 0->1 和 1->0 的翻转

工具的核心功能：

- **模块层级解析**：解析 Verilog 文件，构建模块层次树（module -> submodule），区分 ROOT（顶层模块）和 NODE（子模块）
- **Self Coverage**：单个模块自身的覆盖率（不含子模块）
- **Tree Coverage**：模块及其所有子模块的递归覆盖率
- **排除逻辑**：自动排除以下代码段的覆盖率数据：
  - `` `ifndef SYNTHESIS ``：非综合代码（通常是 assert 和 fwrite）
  - `` `ifdef RANDOMIZE_REG_INIT ``：寄存器随机初始化
  - `` `ifdef RANDOMIZE_MEM_INIT ``：内存随机初始化

### 7.2 覆盖率清洗工具

`scripts/coverage/coverage.py` 是覆盖率报告的预处理脚本，负责：

- 解析 Verilog 预处理指令的嵌套层级
- 移除 `SYNTHESIS` 块内的行覆盖率结果
- 移除 `RANDOMIZE_REG_INIT` 和 `RANDOMIZE_MEM_INIT` 块内的行覆盖率结果
- 输出清洗后的覆盖率文件供 `statistics.py` 分析

### 7.3 输出格式

`statistics.py` 输出以下报告：
- `LineSelfCoverage` / `LineTreeCoverage`：按覆盖率排序的行覆盖率列表
- `ToggleSelfCoverage` / `ToggleTreeCoverage`：按覆盖率排序的翻转覆盖率列表
- `AllCoverage`：完整的层次化覆盖率树

---

## 8. 关键源码文件索引

### Trace 系统
| 文件路径 | 说明 |
|----------|------|
| `src/main/scala/xiangshan/backend/trace/Interface.scala` | Trace 数据类型定义（Itype、TraceBlock、TraceCoreInterface） |
| `src/main/scala/xiangshan/backend/trace/Trace.scala` | Trace 4级流水线顶层模块 |
| `src/main/scala/xiangshan/backend/trace/TraceBuffer.scala` | Trace 压缩缓冲区（环形队列） |
| `src/main/scala/xiangshan/backend/CtrlBlock.scala` | Trace 模块实例化与 PC 地址查询 |
| `src/main/scala/xiangshan/backend/Backend.scala` | TraceCoreInterface 透传 |
| `src/main/scala/xiangshan/XSCore.scala` | 核心级 Trace 接口 |
| `src/main/scala/xiangshan/XSTile.scala` | Tile 级 Trace 接口 |
| `src/main/scala/xiangshan/XSTileWrap.scala` | 异步时钟域同步 |
| `src/main/scala/xiangshan/L2Top.scala` | L2 级 Trace 流水线 |
| `src/main/scala/top/Top.scala` | SoC 顶层 Trace IO |

### 性能计数器
| 文件路径 | 说明 |
|----------|------|
| `src/main/scala/xiangshan/backend/fu/CSR.scala` | 旧版 CSR 性能计数器映射 |
| `src/main/scala/xiangshan/backend/fu/NewCSR/Unprivileged.scala` | 新版 HPM 计数器实现 |
| `src/main/scala/xiangshan/backend/fu/NewCSR/MachineLevel.scala` | M-mode HPM 事件配置 |
| `src/main/scala/xiangshan/backend/rob/Rob.scala` | ROB 级性能计数器 |
| `src/main/scala/xiangshan/backend/TopDownGen.scala` | Top-down 事件汇总 |
| `src/main/scala/top/BusPerfMonitor.scala` | 总线性能监控 |
| `src/main/scala/xiangshan/Bundle.scala` | TopDownInfo、StallReason IO 定义 |
| `src/main/scala/xiangshan/package.scala` | TopDownCounters 枚举定义 |

### Top-down 分析工具
| 文件路径 | 说明 |
|----------|------|
| `scripts/top-down/top_down.py` | Top-down 分析主程序 |
| `scripts/top-down/configs.py` | 计数器匹配规则和分类映射 |
| `scripts/top-down/draw.py` | 堆叠条形图绘制 |
| `scripts/top-down/utils.py` | 统计数据解析工具 |
| `scripts/top-down/resources/` | Benchmark 配置 JSON |

### Debug 工具链
| 文件路径 | 说明 |
|----------|------|
| `src/main/scala/device/RocketDebugWrapper.scala` | Debug Module 和 JTAG DTM |
| `src/main/scala/system/SoC.scala` | Debug Module 实例化 |
| `src/main/scala/top/Top.scala` | JTAG/DMI 连接 |
| `src/main/scala/top/Configs.scala` | DebugModule 参数配置 |
| `docs/XSPdb/README.md` | XSPdb 使用说明 |
| `docs/XSPdb/design/` | XSPdb 设计文档 |
| `docs/XSPdb/examples/` | XSPdb 使用示例 |
| `debug/Makefile` | 测试与调试 Makefile |
| `debug/local_ci.py` | 本地 CI 脚本 |

### 覆盖率分析
| 文件路径 | 说明 |
|----------|------|
| `scripts/coverage/statistics.py` | 覆盖率统计分析 |
| `scripts/coverage/coverage.py` | 覆盖率预处理清洗 |

---

## 9. 总结

XiangShan 的性能监控与追踪系统是一个多层次、全方位的观测基础设施，体现了现代高性能处理器设计中可观测性的重要性。

**在硬件层面**，系统提供了三个层次的性能观测能力：标准 RISC-V CSR 性能计数器用于软件直接访问、内部嵌入式计数器用于微架构精细分析、以及专用的 Top-down 分析计数器用于系统级性能瓶颈定位。这种多层次设计使得同一套硬件可以同时满足软件性能分析（如 Linux perf）、微架构研究（如 Top-down 分析）和硬件调试（如特定模块的 stall 分析）等不同需求。

**在追踪层面**，Trace 系统采用 4 级流水线实现，核心创新在于 TraceBuffer 的压缩机制：通过分析指令类型（Itype），自动合并连续的 NonTaken 指令，仅保留控制流变化点的追踪信息，大幅减少了追踪带宽需求。TraceCoreInterface 接口设计将追踪数据的产生与编码解耦，为 SoC 集成提供了灵活性。

**在分析工具层面**，Top-down 分析框架提供了从原始计数器数据到可视化分析报告的完整流水线，支持 baseline/ref 对比分析和多维度子分类。覆盖率分析工具则提供了模块级和层次化的代码覆盖率统计。

**在调试工具层面**，XSPdb 提供了 GDB 风格的交互式调试体验，支持 FSM 触发器等高级调试功能，结合 RISC-V Debug Module 的标准 JTAG 接口，构成了完整的硬件/软件协同验证工具链。

总体而言，XiangShan 的性能监控与追踪系统在设计上兼顾了规范兼容性（RISC-V CSR 标准、Trace 规范）和定制灵活性（自定义 Top-down 分析、XSPdb），为处理器的设计验证和性能优化提供了强有力的基础设施支持。
