# XiangShan Dispatch 与 Issue Queue 架构深度分析

## 1. 概述

XiangShan 处理器后端采用分布式 Issue Queue (IQ) 架构，将指令发射队列按功能类型分散到多个 Scheduler 中。这种设计使得每个 IQ 仅服务特定类型的执行单元（Execution Unit, Exu），从而在发射带宽、功耗和时序之间取得平衡。Dispatch 模块负责将 Rename 阶段产出的微操作（Micro-operation, uop）分配到对应的 IQ，并通过 BusyTable、IQ 选择逻辑和负载均衡机制确保流水线高效运行。Issue Queue 内部则采用三层 Entry 结构（EnqEntry / Simple / Complex）、双层唤醒机制（Write-Back Wakeup + IQ-to-IQ Wakeup）、基于 AgeDetector 的最老指令优先选择策略，以及 FuBusyTable 冲突避免机制。

---

## 2. 分布式 Issue Queue 架构：18 个 IQ 跨 Int/Fp/Vec 三大 Scheduler

### 2.1 三大 Scheduler 划分

XiangShan 后端将所有 Issue Queue 划分到三个 Scheduler 中：

- **IntScheduler（整数调度器）**：包含 11 个 IQ，服务整数 ALU、分支跳转（BJU）、Load、Store 地址生成（STA）、Store 数据（STD）等功能单元。
- **FpScheduler（浮点调度器）**：包含 3 个 IQ，服务浮点 ALU（Falu）、浮点乘累加（Fmac）、浮点除法（Fdiv）、浮点比较/转换（Fcmp/Fcvt）等。
- **VecScheduler（向量调度器）**：包含 4 个 IQ，服务向量整数 ALU（Vialu）、向量浮点 ALU（Vfalu/Vfma）、向量 Load/Store（Vldu/Vstu）等。

### 2.2 IQ 完整参数表

根据 `BackendParams.scala` 中 `BackendV2SchdParams` 的定义，18 个 IQ 的参数如下：

| 序号 | IQ 名称 | Scheduler | 执行单元 | 功能类型 | numEntries | numEnq | numComp | numSimp | numDeq |
|------|---------|-----------|----------|----------|------------|--------|---------|---------|--------|
| 1 | ALU0/BJU0 IQ | Int | ALU0+Csr+Fence, BJU0+Brh+Jmp | Alu, Csr, Fence, Brh, Jmp | 20 | 2 | 12 | 6 | 2 |
| 2 | ALU1/BJU1 IQ | Int | ALU1+Div, BJU1+Brh+Jmp | Alu, Div, Brh, Jmp | 20 | 2 | 12 | 6 | 2 |
| 3 | ALU2/BJU2 IQ | Int | ALU2+I2f+VSet+Mul+Bku, BJU2+Brh+Jmp | Alu, I2f, VSet, Bku, Mul, Brh, Jmp | 20 | 2 | 12 | 6 | 2 |
| 4 | ALU3 IQ | Int | ALU3+Bku+Mul | Alu, Bku, Mul | 20 | 2 | 12 | 6 | 2 |
| 5 | LDU0 IQ | Int | LDU0 | Ldu | 16 | 2 | 12 | 2 | 1 |
| 6 | LDU1 IQ | Int | LDU1 | Ldu | 16 | 2 | 12 | 2 | 1 |
| 7 | LDU2 IQ | Int | LDU2 | Ldu | 16 | 2 | 12 | 2 | 1 |
| 8 | STA0 IQ | Int | STA0+Mou | Sta, Mou | 16 | 2 | 12 | 2 | 1 |
| 9 | STA1 IQ | Int | STA1+Mou | Sta, Mou | 16 | 2 | 12 | 2 | 1 |
| 10 | STD0 IQ | Int | STD0+Moud | Std, Moud | 16 | 2 | 12 | 2 | 1 |
| 11 | STD1 IQ | Int | STD1+Moud | Std, Moud | 16 | 2 | 12 | 2 | 1 |
| 12 | FEX0 IQ | Fp | FEX0+Falu+Fmac+Fcvt+Fcmp+F2v | Falu, Fmac, Fcvt, Fcmp, F2v | 18 | 2 | 14 | 2 | 1 |
| 13 | FEX1 IQ | Fp | FEX1+Falu+Fmac+Fdiv | Falu, Fmac, Fdiv | 18 | 2 | 14 | 2 | 1 |
| 14 | FEX2 IQ | Fp | FEX2+Falu+Fmac+Fdiv | Falu, Fmac, Fdiv | 18 | 2 | 14 | 2 | 1 |
| 15 | VFEX0 IQ | Vec | VFEX0 | Vialu, Vfalu, Vfma, Vimac, Vppu, Vipu, Vfcvt, VSet, Vmove | 16 | 2 | 12 | 2 | 1 |
| 16 | VFEX1 IQ | Vec | VFEX1 | Vialu, Vfalu, Vfma, Vfdiv, Vidiv | 16 | 2 | 12 | 2 | 1 |
| 17 | VLSU0 IQ | Vec | VLSU0 | Vldu, Vstu, Vsegldu, Vsegstu | 16 | 2 | 12 | 2 | 1 |
| 18 | VLSU1 IQ | Vec | VLSU1 | Vldu, Vstu | 16 | 2 | 12 | 2 | 1 |

**关键设计要点**：
- IntScheduler 的前 4 个 IQ（ALU/BJU 类）拥有 20 条目、2 发射端口，`numComp=12`，因此 `numSimp = 20 - 2 - 12 = 6`，采用 Simple+Complex 混合结构。
- Ldu/STA/STD 类 IQ 以及 Fp、Vec IQ 只有 1 个发射端口（`numDeq=1`），`numComp=12`，`numSimp=2`，Simple 条目数较少。
- 所有 IQ 的 `numEnq` 均为 2，意味着每个 IQ 支持每周期同时接收 2 条来自 Dispatch 的指令。

### 2.3 IQ 命名规则

IQ 名称由其支持的所有 FuConfig 名称拼接而成（源码 `IssueBlockParams.scala` 中的 `getIQName` 方法）：

```scala
def getIQName = {
  "IssueQueue" ++ getFuCfgs.map(_.name).distinct.map(_.capitalize).reduce(_ ++ _)
}
```

---

## 3. Dispatch 流控制：BusyTable、IQ 选择与负载均衡

### 3.1 Dispatch 总体流程

Dispatch 模块（`Dispatch.scala`）位于 Rename 和 Issue Queue 之间，主要职责包括：

1. **接收 Rename 输出**：从 `fromRename` 端口接收 `RenameWidth`（6）条宽度的微操作。
2. **BusyTable 读取**：查询 Int/FP/Vec/V0/Vl 五组 BusyTable，判断每个源操作数寄存器是否就绪（ready）。
3. **IQ 选择（IQ Selection）**：根据指令的 FuType 选择目标 IQ，并在多个可选 IQ 中实现负载均衡。
4. **Flow Control**：判断 IQ 是否有空闲条目、LSQ 是否可接受、ROB 是否可入队。
5. **指令发射**：将选中的 uop 通过 `toIssueQueues` 端口发送到对应的 IQ。

### 3.2 BusyTable 机制

BusyTable 是 Dispatch 阶段判断操作数就绪状态的核心模块。XiangShan 为每种寄存器文件维护独立的 BusyTable：

```scala
val intBusyTable = Module(new BusyTable(numRegSrcInt * renameWidth, ..., IntPhyRegs, IntWB()))
val fpBusyTable  = Module(new BusyTable(numRegSrcFp * renameWidth, ..., FpPhyRegs, FpWB()))
val vecBusyTable = Module(new BusyTable(numRegSrcVf * renameWidth, ..., VfPhyRegs, VfWB()))
val v0BusyTable  = Module(new BusyTable(numRegSrcV0 * renameWidth, ..., V0PhyRegs, V0WB()))
val vlBusyTable  = Module(new VlBusyTable(numRegSrcVl * renameWidth, ..., VlPhyRegs, VlWB()))
```

每个 BusyTable 的工作原理：
- **Allocate**：当指令 Dispatch 成功时，将目标物理寄存器（pdest）标记为 busy。
- **WakeUp（快速唤醒）**：接收来自 IQ 的 IQ WakeUp 信号和来自 WB 端口的 Write-Back 唤醒信号。当收到唤醒时，对应寄存器的 busy 状态被提前清除。
- **WriteBack（写回确认）**：当数据真正写回寄存器文件时，清除 busy 状态。
- **Read**：为 Dispatch 提供每个源操作数的 ready 状态和 loadDependency 信息。

所有源操作数的状态通过 `allSrcState` 向量聚合：

```scala
val allSrcState = Wire(Vec(renameWidth, Vec(numRegSrc, Vec(numRegType, Bool()))))
```

其中 `numRegType=4`（Int/FP/Vec/V0），每个源操作数可能来自不同类型寄存器文件，BusyTable 根据 `SrcType` 选择性读取。

### 3.3 IQ 选择与负载均衡

#### 3.3.1 基本 IQ 选择逻辑

Dispatch 需要将每条指令路由到正确的 IQ。选择逻辑通过 `fuMapIQIdx` 建立 FuType 到 IQ 索引的映射：

```scala
val fuMapIQIdx = sortedFuConfigs.map(fu => {
  val fuInIQIdx = fuConfigsInIssueParams.zipWithIndex.filter { case (f, i) => f.contains(fu) }.map(_._2)
  (fu -> fuInIQIdx)
})
```

对于只映射到单个 IQ 的 FuType（如 Div、Csr、Fdiv 等），直接选择该 IQ：
```scala
val uopSelIQSingle = Wire(Vec(needSingleIQ.size, Vec(issueQueueNum, Bool())))
uopSelIQSingle := VecInit(needSingleIQ.map(_._2).flatten.map(
  x => VecInit((1.U(issueQueueNum.W) << x)(issueQueueNum-1, 0).asBools)
))
```

#### 3.3.2 多 IQ 负载均衡

对于可映射到多个 IQ 的 FuType（如 ALU、Brh、Ldu 等），Dispatch 采用负载均衡策略选择目标 IQ。核心机制如下：

1. **计数器追踪**：`issueQueueCount` 追踪每个 IQ 当前有效条目数，`issueQueueCountAddEnq` 加入了即将入队的指令数。
2. **比较矩阵**：对多个候选 IQ 两两比较负载：
   ```scala
   compareMatrix(i)(j) := issueQueueCountAddEnq(exuidx(i)) < issueQueueCountAddEnq(exuidx(j))
   ```
3. **排序网络**：通过 `IQSort` 和 `IQSortUpdate` 对 IQ 按负载排序，选择负载最低的 IQ：
   ```scala
   IQSortWire(i) := compareMatrix.map(x => PopCount(x) === (iqNum - 1 - i).U)
   ```
4. **分段更新**：为优化时序，排序结果按 3 个一组分段更新（`updateInterval = 3`），避免过长的组合逻辑链。

`EnableDispatchIQBalanceOpt` 参数控制是否启用负载均衡优化。启用后，IQ 选择会考虑额外的 6 个周期内的预期入队数（`maxIQSize = allIssueParams.map(_.numEntries).max + 6`）。

### 3.4 流控条件

Dispatch 是否允许指令出队需要满足以下条件：

1. **ROB 可接受**：`io.enqRob.canAccept`
2. **IQ 不阻塞**：`!uopBlockByIQ`（目标 IQ 有空闲条目）
3. **LSQ 可接受**：`lsqCanAccept`（Load/Store 指令需要 LSQ 有空间）
4. **无特殊指令阻塞**：无 `blockBackward` 或 `waitForward` 指令阻塞
5. **资源就绪**：`allowDispatch` 检查 LSQ 空间是否满足流量需求

```scala
fromRename(i).ready := allowDispatch(i) && !uopBlockByIQ(i) && thisCanActualOut(i) && lsqCanAccept
```

IQ 满条件通过 `uopBlockMatrix` 计算：当目标 IQ 的累计入队数超过其容量时，阻塞对应 FuType 的指令。

### 3.5 等待前序指令的顺序性保证

Dispatch 通过以下机制保证指令顺序性：

- **`blockedByWaitForward`**：当前指令设置了 `waitForward` 且 ROB 不为空或前序指令有效时，阻塞当前指令。
- **`nextCanOut`**：前序指令设置了 `blockBackward` 时，后续指令不可出队。
- **`notBlockedByPrevious`**：所有前序指令均满足 `nextCanOut`。

---

## 4. Issue Queue 内部结构：三层 Entry 架构

### 4.1 总体结构

XiangShan Issue Queue 采用三层 Entry 结构，在面积/功耗和灵活性之间取得平衡：

- **EnqEntry（入队条目）**：数量 = `numEnq`（通常为 2），支持完整入队逻辑和延迟唤醒路径，是最"重"的 Entry。
- **OthersEntry(isComp=false) / Simple Entry**：数量 = `numSimp`，逻辑较简单，只支持基础的唤醒和发射逻辑。
- **OthersEntry(isComp=true) / Complex Entry**：数量 = `numComp`，支持完整的发射逻辑，但不支持入队。

三者关系由 `IssueBlockParams` 定义：
```scala
def numSimp: Int = numEntries - numEnq - numComp
def isAllComp: Boolean = numComp == (numEntries - numEnq)
def isAllSimp: Boolean = numComp == 0
def hasCompAndSimp: Boolean = !(isAllComp || isAllSimp)
```

### 4.2 Entries 模块的条目实例化

`Entries.scala` 模块（`Entries` class）实例化所有条目并管理条目间的转换（Transfer）：

```scala
val enqEntries          = Seq.fill(EnqEntryNum)(Module(EnqEntry(isComp = true)(p, params)))
val othersEntriesSimp   = Seq.fill(SimpEntryNum)(Module(OthersEntry(isComp = false)(p, params)))
val othersEntriesComp   = Seq.fill(CompEntryNum)(Module(OthersEntry(isComp = true)(p, params)))
```

### 4.3 条目间的 Transfer 机制

当 EnqEntry 在一周期内入队但未被选中发射时，可以将指令"Transfer"（转移）到 OthersEntry 中，从而腾出 EnqEntry 接收新指令。Transfer 流程：

1. **EnqEntry -> Simple/Complex**：通过 `simpTransSelVec` / `compTransSelVec` 选择 Simple 或 Complex 条目。
2. **Simple -> Complex**：当 Simple 条目被选中发射但仍有指令等待时，可以将指令升迁到 Complex 条目。
3. **EnqEntry -> OthersEntry（AllComp/AllSimp 模式）**：当所有 OthersEntry 均为同类型时，直接转换。

转换策略由 `EnqPolicy` 模块控制：
```scala
val othersTransPolicy = OptionWrapper(params.isAllComp || params.isAllSimp, Module(new EnqPolicy))
val simpTransPolicy   = OptionWrapper(params.hasCompAndSimp, Module(new EnqPolicy))
val compTransPolicy   = OptionWrapper(params.hasCompAndSimp, Module(new EnqPolicy))
```

### 4.4 EnqEntry 的延迟唤醒路径

EnqEntry 相比 OthersEntry 多出两个延迟唤醒输入端口（`enqDelayIn1` 和 `enqDelayIn2`），用于处理入队周期的唤醒信号：

```scala
val enqDelayOut1 = Wire(new EnqDelayOutBundle)
val enqDelayOut2 = Wire(new EnqDelayOutBundle)
EnqDelayWakeupConnect(io.enqDelayIn1, enqDelayOut1, entryReg.status, delay = 1)
EnqDelayWakeupConnect(io.enqDelayIn2, enqDelayOut2, entryReg.status, delay = 2)
```

这些延迟唤醒信号分别对应 1 周期和 2 周期延迟的 WB 和 IQ WakeUp，确保 EnqEntry 在入队后立即能感知到最新的唤醒事件，避免不必要的等待周期。

### 4.5 Status 与 SrcStatus 结构

每个 Entry 的核心状态由 `Status` Bundle 描述：

```scala
class Status {
  val robIdx: RobPtr              // ROB 索引
  val fuType: IQFuType            // 功能单元类型（one-hot 编码）
  val srcStatus: Vec[SrcStatus]   // 源操作数状态
  val srcStatusVl: VlSrcStatus    // vl 寄存器状态（可选）
  val blocked: Bool               // 是否被阻塞
  val issued: Bool                // 是否已发射
  val firstIssue: Bool            // 是否首次发射
  val issueTimer: UInt            // 发射计时器
  val deqPortIdx: UInt            // 发射端口索引
}
```

其中 `SrcStatus` 记录每个源操作数的详细状态：

```scala
class SrcStatus {
  val psrc: UInt                // 物理源寄存器索引
  val srcType: SrcType          // 源类型（Int/FP/Vec/V0/Imm）
  val srcState: SrcState        // 源状态（ready/not ready）
  val dataSources: DataSource   // 数据来源（reg/bypass/forward/imm）
  val srcLoadDependency: Vec[UInt]  // Load 依赖链
  val exuSources: ExuSource     // IQ WakeUp 来源（可选）
  val useRegCache: Bool         // 是否使用 RegCache（可选）
  val regCacheIdx: UInt         // RegCache 索引（可选）
}
```

`canIssue` 的判断条件为：
```scala
def canIssue: Bool = srcReady && !issued && !blocked
```

---

## 5. 双层唤醒机制：WB Wakeup + IQ-to-IQ Wakeup

### 5.1 唤醒机制概述

XiangShan 采用双层唤醒架构来加速指令发射：

- **第一层：Write-Back Wakeup（WB 唤醒）**：当执行单元完成计算并将数据写回寄存器文件时，广播唤醒信号给所有 IQ。这是传统的唤醒方式，延迟较高（通常在 2-4 周期）。
- **第二层：IQ-to-IQ Wakeup（IQ 唤醒）**：当指令在 Issue Queue 中被选中发射（issue）时，立即通过 `wakeupToIQ` 端口广播唤醒信号给其他 IQ 中等待该数据的指令。这是快速唤醒路径，通常只需 1 周期。

### 5.2 唤醒源配置

唤醒源通过 `WakeUpConfig` 在 `BackendParams.scala` 中配置：

```scala
def iqWakeUpParams = {
  Seq(
    // Int IQ 内部唤醒：ALU/LDU -> ALU/BJU/STA/STD
    WakeUpConfig(
      Seq("ALU0", "ALU1", "ALU2", "ALU3", "LDU0", "LDU1", "LDU2") ->
      Seq("ALU0", "ALU1", "ALU2", "ALU3", "LDU0", "LDU1", "LDU2",
          "STA0", "STA1", "STD0", "STD1", "BJU0", "BJU1", "BJU2")
    ),
    // Fp IQ 内部唤醒：FEX -> FEX
    WakeUpConfig(Seq("FEX0", "FEX1", "FEX2") -> Seq("FEX0", "FEX1", "FEX2")),
    // Int LDU -> Fp FEX 跨 Scheduler 唤醒
    WakeUpConfig(Seq("LDU0", "LDU1", "LDU2") -> Seq("FEX0", "FEX1", "FEX2")),
    // Fp FEX -> Int STD 跨 Scheduler 唤醒
    WakeUpConfig(Seq("FEX0", "FEX1", "FEX2") -> Seq("STD0", "STD1")),
  ).flatten
}
```

`WakeUpConfig`（`WakeUpConfig.scala`）定义唤醒源（source）和唤醒汇（sink）之间的连接关系：

```scala
class WakeUpConfig(val source: WakeUpSource, val sink: WakeUpSink) {
  def this(pair: (String, String)) = {
    this(new WakeUpSource(pair._1), new WakeUpSink(pair._2))
  }
}
```

### 5.3 IQ-to-IQ 唤醒信号流

每个 IQ 的 IO 包含以下唤醒端口：

```scala
val wakeupFromWB: MixedVec[ValidIO[IssueQueueWBWakeUpBundle]]   // WB 唤醒输入
val wakeupFromIQ: MixedVec[ValidIO[IssueQueueIQWakeUpBundle]]   // IQ 唤醒输入
val wakeupToIQ: MixedVec[ValidIO[IssueQueueIQWakeUpBundle]]     // IQ 唤醒输出
val wakeupFromExu: Option[DecoupledIO[IssueQueueIQWakeUpBundle]] // 不确定延迟唤醒输入
```

IQ 唤醒信号的广播路径为：
1. 指令在 IQ 中被选中发射 -> 进入 `wakeUpQueues`（MultiWakeupQueue）。
2. MultiWakeupQueue 根据功能单元延迟设置多级 Pipeline。
3. Pipeline 输出通过 `wakeupToIQ` 广播到所有订阅该唤醒源的 IQ。
4. 目标 IQ 的每个 Entry 接收唤醒信号后，更新对应源操作数的 `srcState` 为 ready。

### 5.4 跨 Scheduler 的特殊唤醒处理

当 VF Exu（Vector Floating-Point Execution Unit，具有 og2 延迟）唤醒 Int/Mem IQ（没有 og2）时，唤醒信号需要延迟 1 周期处理：

```scala
if (!params.inVfSchd && params.readVfRf && params.hasWakeupFromVf && w_src.bits.params.isVfExeUnit) {
  val noCancel = !LoadShouldCancel(Some(w_src.bits.loadDependency), io.ldCancel)
  w := RegNext(Mux(noCancel, w_src, 0.U.asTypeOf(w)))
}
```

### 5.5 Load 依赖链（Load Dependency）

对于 Load 指令产生的唤醒，XiangShan 引入了 Load Dependency 机制，防止 Load-After-Load 的数据冒险：

```scala
val loadDependency: Vec[Vec[UInt]] = entries.io.loadDependency
val finalLoadDependency: IndexedSeq[Vec[UInt]] = VecInit(
  finalDeqSelOHVec.map(oh => Mux1H(oh, loadDependency))
)
```

每个源操作数维护一个 `LoadPipelineWidth` 宽度的依赖向量，当数据来自 Load 操作时，依赖链逐周期左移（`<< 1`），直到被真正的 WB 唤醒清除。

---

## 6. 基于 AgeDetector 的最老指令优先选择

### 6.1 AgeDetector 原理

`AgeDetector.scala` 实现了一个基于年龄矩阵（Age Matrix）的最老指令选择器。核心思想是维护一个 NxN 的年龄矩阵，其中 `age(i)(j)` 表示条目 i 是否比条目 j 更老（更早入队）。

```scala
class AgeDetector(numEntries: Int, numEnq: Int, numDeq: Int) extends XSModule {
  val age = Seq.fill(numEntries)(Seq.fill(numEntries)(RegInit(false.B)))
}
```

年龄矩阵的更新规则：
1. **条目 i 入队时**：将 row(i) 清零（i 比所有已存在的条目年轻），将 col(i) 置 1（所有已存在的条目比 i 老）。
2. **多端口入队**：当多个条目同时入队时，通过端口号确定相对顺序（先入端口的条目更老）。
3. **默认状态**：矩阵值在无入队事件时保持不变。

### 6.2 最老指令选择

选择最老的可发射指令的逻辑：

```scala
def getOldestCanIssue(get: (Int, Int) => Bool, canIssue: UInt): UInt = {
  VecInit((0 until numEntries).map(i => {
    (VecInit((0 until numEntries).map(j => get(i, j))).asUInt | ~canIssue).andR & canIssue(i)
  })).asUInt
}
```

这段逻辑的含义是：条目 i 是最老的可发射指令，当且仅当 i 可发射，且所有比 i 更老的条目要么不存在，要么不可发射。

### 6.3 NewAgeDetector

`NewAgeDetector.scala` 提供了另一种年龄检测器实现，用于 EnqEntry 的选择。其与 `AgeDetector` 的区别在于处理入队端口的逻辑不同，适用于只有少量条目（如 2 个 EnqEntry）的场景。

### 6.4 多层选择架构

IQ 的出队选择采用多层 AgeDetector 级联的架构：

1. **EnqEntry 选择**：`NewAgeDetector(numEntries = params.numEnq, ...)` 从 2 个 EnqEntry 中选择最老的。
2. **Simple Entry 选择**：`AgeDetector(numEntries = params.numSimp, ...)` 从 Simple 条目中选择最老的。
3. **Complex Entry 选择**：`AgeDetector(numEntries = params.numComp, ...)` 从 Complex 条目中选择最老的。
4. **最终选择**：通过优先级合并：`Comp > Simp > Enq`：

```scala
deqSelOHVec(i) := Cat(
  compEntryOldestSel.get(i).bits,
  Fill(params.numSimp, !compEntryOldestSel.get(i).valid) & simpEntryOldestSel.get(i).bits,
  Fill(params.numEnq, !compEntryOldestSel.get(i).valid && !simpEntryOldestSel.get(i).valid) & enqEntryOldestSel(i).bits
)
```

### 6.5 双发射 IQ 的 AgeDetector 使用

对于双发射 IQ（`numDeq == 2`），选择逻辑更为复杂：

- 当两个发射端口功能类型完全相同（`deqFuSame`）时，使用 `DeqPolicy`（基于 `SelectOne` 的循环选择）从第一个 AgeDetector 选出的条目之外的可发射条目中选择第二个。
- 当两个发射端口功能类型不同时（`deqFuDiff`），每个端口独立使用一个 AgeDetector 进行选择。

`DeqPolicy`（`DeqPolicy.scala`）使用循环选择器 `SelectOne("circ", ...)` 来保证两个发射端口选择不同的条目：

```scala
class DeqPolicy extends XSModule {
  private val selVec: Seq[(Bool, Vec[Bool])] = io.deqSelOHVec.indices.map(i =>
    SelectOne("circ", requestVec, iqP.numDeq).getNthOH(i + 1)
  )
}
```

---

## 7. FuBusyTable 冲突避免机制

### 7.1 设计动机

FuBusyTable 用于避免同一条目中的指令在发射后与具有相同延迟的功能单元产生冲突。例如，如果 ALU 的延迟为 1 周期，那么在一条 ALU 指令发射后 1 周期内，另一个需要 ALU 的指令不应被选中发射（因为 ALU 硬件正忙）。

### 7.2 FuBusyTableWrite 模块

`FuBusyTableWrite.scala` 实现了 FuBusyTable 的写入逻辑。它维护一个移位寄存器（`fuBusyTable`），记录当前各延迟级的功能单元忙碌状态：

```scala
class FuBusyTableWrite(fuLatencyMap: Map[FuType.OHType, Int]) extends XSModule {
  private val fuBusyTable = RegInit(0.U(tableSize.W))

  fuBusyTableNext := fuBusyTableShift & (~og0RespClearShift).asUInt & (~og1RespClearShift).asUInt | deqRespSetShift.asUInt
}
```

- `fuBusyTableShift`：右移 1 位，表示每个延迟级的计时递减。
- `deqRespSetShift`：当发射成功时，在对应延迟级设置 busy 位。
- `og0RespClearShift` / `og1RespClearShift`：当 OG0/OG1 阶段响应失败时（如取消），清除对应的 busy 位。

状态转移公式为：
```
nextTable = (table >> 1) & ~(og0Fail >> 2) & ~(og1Fail >> 3) | (deqSuccess >> 1)
```

### 7.3 FuBusyTableRead 模块

`FuBusyTableRead.scala` 负责根据每个 Entry 的 FuType 生成屏蔽掩码：

```scala
class FuBusyTableRead(fuLatencyMap: Map[FuType.OHType, Int]) extends Module {
  val readMaskVec = fuBusyVec.zipWithIndex.map { case (busy, lat) =>
    val latencyHitVec = WireInit(0.U(numEntries.W))
    when(busy) {
      latencyHitVec := VecInit(fuTypeVec.map { fuType =>
        val latencyHitFuType = latMappedFuTypeSet.getOrElse(lat, Set()).toSeq
        val isLatencyNum = FuType.FuTypeOrR(fuType, latencyHitFuType)
        isLatencyNum
      }).asUInt
    }
    latencyHitVec
  }
  io.out.fuBusyTableMask := readMaskVec.fold(0.U(iqParams.numEntries.W))(_ | _)
}
```

对于每个延迟级，如果该延迟级的 busy 位为 1，则所有使用该延迟级功能类型的 Entry 都被屏蔽（不可发射）。

### 7.4 WB BusyTable

除了 FuBusyTable，XiangShan 还维护了 5 组 WB BusyTable（Int/FP/Vf/V0/Vl），用于避免写回端口冲突：

```scala
val intWbBusyTableWrite = params.exuBlockParams.map { x =>
  Option.when(x.intLatencyCertain)(Module(new FuBusyTableWrite(x.intFuLatencyMap)))
}
val fpWbBusyTableWrite  = params.exuBlockParams.map { x =>
  Option.when(x.fpLatencyCertain)(Module(new FuBusyTableWrite(x.fpFuLatencyMap)))
}
val vfWbBusyTableWrite  = params.exuBlockParams.map { x =>
  Option.when(x.vfLatencyCertain)(Module(new FuBusyTableWrite(x.vfFuLatencyMap)))
}
val v0WbBusyTableWrite  = params.exuBlockParams.map { x =>
  Option.when(x.v0LatencyCertain)(Module(new FuBusyTableWrite(x.v0FuLatencyMap)))
}
val vlWbBusyTableWrite  = params.exuBlockParams.map { x =>
  Option.when(x.vlLatencyCertain)(Module(new FuBusyTableWrite(x.vlFuLatencyMap)))
}
```

### 7.5 多层 BusyTable 合并

最终的可发射掩码 `canIssueMergeAllBusy` 是通过多层 BusyTable 掩码逐层 AND 合并得到的：

```scala
canIssueMergeAllBusy.zipWithIndex.foreach { case (merge, i) =>
  val mergeFuBusy    = canIssueVec.asUInt & (~fuBusyTableMask(i)).asUInt
  val mergeIntWbBusy = mergeFuBusy & (~intWbBusyTableMask(i)).asUInt
  val mergefpWbBusy  = mergeIntWbBusy & (~fpWbBusyTableMask(i)).asUInt
  val mergeVfWbBusy  = mergefpWbBusy & (~vfWbBusyTableMask(i)).asUInt
  val mergeV0WbBusy  = mergeVfWbBusy & (~v0WbBusyTableMask(i)).asUInt
  val mergeVlWbBusy  = mergeV0WbBusy & (~vlWbBusyTableMask(i)).asUInt
  merge := mergeVlWbBusy
}
```

最终的可发射请求还需结合功能类型匹配：
```scala
deqCanIssue.zipWithIndex.foreach { case (req, i) =>
  req := canIssueMergeAllBusy(i) & VecInit(deqCanAcceptVec(i)).asUInt
}
```

---

## 8. MultiWakeupQueue 延迟管理

### 8.1 设计目标

`MultiWakeupQueue.scala` 用于管理具有多种可能延迟的功能单元的唤醒信号。例如，一个执行单元可能对整数操作有 1 周期延迟，对浮点操作有 3 周期延迟。MultiWakeupQueue 为每种延迟维护独立的 Pipeline，并在输出端合并。

### 8.2 内部结构

```scala
class MultiWakeupQueue[T <: Bundle, TFlush <: Data](
  val exuParam: ExeUnitParams,
  val gen: ExuInput,
  val lastGen: ExuInput,
  val flushGen: TFlush,
  val latencySet: Set[Int],
  flushFunc: (ExuInput, TFlush, Int) => Bool,
  modificationFunc: ExuInput => ExuInput,
  lastConnectFunc: (ExuInput, ExuInput) => ExuInput,
) extends Module {
  val pipes = latencySet.map(x =>
    Module(new PipeWithFlush[T, TFlush](gen, flushGen, x, flushFunc, modificationFunc))
  ).toSeq
}
```

关键特性：
- **多级 Pipeline**：根据 `latencySet`（如 `{0, 1, 2}`）实例化多个不同深度的 `PipeWithFlush`。
- **延迟路由**：入队时根据 `io.enq.bits.lat` 选择对应的 Pipeline：
  ```scala
  pipe.io.enq.valid := io.enq.valid && io.enq.bits.lat === lat.U
  ```
- **输出合并**：使用 `Mux1H` 选择第一个有效的 Pipeline 输出。
- **lastConnect 缓存**：`lastConnect` 寄存器保存上一次的唤醒输出，当所有 Pipeline 都无有效输出时保持不变，确保唤醒信号的连续性。
- **Flush 支持**：每个 Pipeline 支持 flush 操作，当发生分支预测错误或 Load 取消时，清除错误的唤醒信号。
- **Modification 函数**：对通过 Pipeline 的唤醒信号执行 `loadDependency << 1` 操作，更新 Load 依赖链。

### 8.3 EnqAppend 机制

MultiWakeupQueue 还支持 `enqAppend` 端口，用于处理特殊唤醒场景：

- **I2F 唤醒**：整数到浮点的跨域唤醒（`needDataFromI2F`）。
- **F2I 唤醒**：浮点到整数的跨域唤醒（`needDataFromF2I`）。
- **Fdiv 唤醒**：浮点除法器的唤醒（`needUncertainWakeup`）。

`enqAppend` 仅在所有正常 Pipeline 均无有效输出时才能插入（优先级最低）：
```scala
val allValidVec = VecInit(pipesValidVec :+ (io.enqAppend.valid && !pipesValidVec.asUInt.orR))
io.enqAppend.ready := Mux(io.enqAppend.valid, !pipesValidVec.asUInt.orR, true.B)
```

### 8.4 Flush 函数

唤醒队列的 flush 由 `WakeupQueueFlush` Bundle 控制：

```scala
class WakeupQueueFlush extends Bundle {
  val redirect = ValidIO(new Redirect)      // 分支预测错误
  val ldCancel = Vec(..., new LoadCancelIO) // Load 取消
  val og0Fail = Output(Bool())              // OG0 阶段失败
  val og1Fail = Output(Bool())              // OG1 阶段失败
}
```

flush 条件包括：重定向（redirect）、Load 依赖取消（loadDependencyFlush）和 OG 阶段失败（ogFailFlush）。

---

## 9. IQ 入队就绪信号生成

Issue Queue 的 `io.enq.ready` 信号控制 Dispatch 能否向该 IQ 入队指令：

```scala
val enqReady = GatedValidRegNext(
  (!othersCanotIn || !enqHasValidRegNext) && !enqHasIssuedRegNext, false.B
)
io.enq.foreach(_.ready := enqReady)
```

其中：
- `othersCanotIn`：Simple/Complex 条目已满或仅剩一个空位（需保证至少有 2 个空位以支持双端口入队）。
- `enqHasValidRegNext`：EnqEntry 中是否有有效条目（如果 EnqEntry 有有效条目且 others 已满，则阻塞入队）。
- `enqHasIssuedRegNext`：EnqEntry 中是否有已发射的条目（已发射的条目正在转移中，不可入队）。

---

## 10. Load/Store 指令的特殊处理

### 10.1 LSQ 空间管理

Dispatch 通过 `LsqEnqCtrl` 模块管理 Load Queue（LQ）和 Store Queue（SQ）的空间：

```scala
val lsqEnqCtrl = Module(new LsqEnqCtrl)
lsqEnqCtrl.io.lcommit := io.fromMem.lcommit
lsqEnqCtrl.io.scommit := io.fromMem.scommit
```

`allowDispatch` 信号根据当前指令是 Load 还是 Store 检查对应的队列空间：
```scala
when(isStoreVec(index) || isVStoreVec(index)) {
  allowDispatch(index) := (sqFreeCount > flowTotal) && allowDispatchPrevious
}.elsewhen(isLoadVec(index) || isVLoadVec(index)) {
  allowDispatch(index) := (lqFreeCount > flowTotal) && allowDispatchPrevious
}
```

### 10.2 向量 Load/Store 的流量计算

向量 Load/Store 指令的流量（flow）需要特殊计算。标量指令的流量为 1，向量 unit-stride 指令的流量为 2，其他向量指令的流量为 16：

```scala
private val conserveFlows = VecInit(isVlsType.zip(isLSType).zipWithIndex.map {
  case ((isVlsTyepItem, isLSTypeItem), index) =>
    Mux(isVlsTyepItem,
      Mux(isUnitStride(index), VecMemUnitStrideMaxFlowNum.U, 16.U),
      Mux(isLSTypeItem, 1.U, 0.U))
})
```

### 10.3 VecMem IQ 的特殊入队逻辑

向量存储 IQ 的入队需要检查指令是否为第一条 Load（通过比较 `lqIdx` 和 `lqDeqPtr`），对非首条 Load 的 Vleff 指令设置 `blocked` 状态：

```scala
if (params.isVecMemIQ) {
  entries.io.enq.zipWithIndex.map { case(enqData, i) =>
    val isFirstLoad = s0_enqBits(i).lqIdx.get <= io.memIO.get.lqDeqPtr.get
    val isVleff = s0_enqBits(i).vpu.get.isVleff
    enqData.bits.status.blocked := !isFirstLoad && isVleff
  }
}
```

---

## 11. 发射响应与取消机制

### 11.1 OG0/OG1 响应

IQ 的发射经历多个流水级：
- **OG0（Operand Get 0）**：读取寄存器文件第一阶段，`og0Resp` 报告是否成功。
- **OG1（Operand Get 1）**：读取寄存器文件第二阶段，`og1Resp` 报告是否成功。
- **OG2/S0/S2/Sn**：后续阶段（取决于 IQ 类型），用于反馈最终结果。

### 11.2 取消信号

发射失败或分支预测错误时，需要取消已发射的指令并恢复状态：

- **`og0Cancel`**：OG0 阶段取消，影响所有 IQ 中的条目。
- **`og1Cancel`**：OG1 阶段取消。
- **`ldCancel`**：Load 取消，影响依赖该 Load 的所有条目。

取消逻辑通过 `loadDependency` 连锁传递：

```scala
def flushFunc(exuInput: ExuInput, flush: WakeupQueueFlush, stage: Int): Bool = {
  val redirectFlush = exuInput.robIdx.needFlush(flush.redirect)
  val loadDependencyFlush = LoadShouldCancel(exuInput.loadDependency, flush.ldCancel)
  val ogFailFlush = stage match {
    case 1 => flush.og0Fail
    case 2 => flush.og1Fail
    case _ => false.B
  }
  redirectFlush || loadDependencyFlush || ogFailFlush
}
```

---

## 12. RegCacheTagTable 机制

Int Scheduler 使用 `RegCacheTagTable` 实现寄存器缓存（Register Cache）的标签管理：

```scala
val rcTagTable = Module(new RegCacheTagTable(numRegSrcInt * renameWidth))
rcTagTable.io.allocPregs.zip(allocPregs(0)).map(x => x._1 := x._2)
rcTagTable.io.wakeupFromIQ := io.wakeUpAll.wakeUpInt
```

RegCacheTagTable 为整数源操作数维护物理寄存器到 RegCache 位置的映射，当 IQ 发射指令时，通过 `replaceRCIdx` 为新指令分配 RegCache 位置。这可以减少寄存器文件的读端口压力。

---

## 13. 关键源文件位置总结

| 模块 | 文件路径 |
|------|----------|
| Dispatch 主模块 | `src/main/scala/xiangshan/backend/dispatch/Dispatch.scala` |
| IssueQueue 核心实现 | `src/main/scala/xiangshan/backend/issue/IssueQueue.scala` |
| Entries 条目管理 | `src/main/scala/xiangshan/backend/issue/Entries.scala` |
| EnqEntry 入队条目 | `src/main/scala/xiangshan/backend/issue/EnqEntry.scala` |
| OthersEntry 通用条目 | `src/main/scala/xiangshan/backend/issue/OthersEntry.scala` |
| EntryBundles 条目 Bundle | `src/main/scala/xiangshan/backend/issue/EntryBundles.scala` |
| AgeDetector 年龄检测器 | `src/main/scala/xiangshan/backend/issue/AgeDetector.scala` |
| NewAgeDetector 新年龄检测器 | `src/main/scala/xiangshan/backend/issue/NewAgeDetector.scala` |
| DeqPolicy 出队策略 | `src/main/scala/xiangshan/backend/issue/DeqPolicy.scala` |
| FuBusyTableWrite 功能单元忙表写 | `src/main/scala/xiangshan/backend/issue/FuBusyTableWrite.scala` |
| FuBusyTableRead 功能单元忙表读 | `src/main/scala/xiangshan/backend/issue/FuBusyTableRead.scala` |
| MultiWakeupQueue 多延迟唤醒队列 | `src/main/scala/xiangshan/backend/issue/MultiWakeupQueue.scala` |
| IssueBlockParams IQ 参数定义 | `src/main/scala/xiangshan/backend/issue/IssueBlockParams.scala` |
| WakeUpConfig 唤醒配置 | `src/main/scala/xiangshan/backend/datapath/WakeUpConfig.scala` |
| BackendParams 后端参数 | `src/main/scala/xiangshan/backend/BackendParams.scala` |
| BusyTable 忙表 | `src/main/scala/xiangshan/backend/rename/BusyTable.scala` |

---

## 14. 总结

XiangShan 的 Dispatch 与 Issue Queue 架构体现了以下设计理念：

1. **分布式架构**：18 个 IQ 按功能类型分布在 3 个 Scheduler 中，减少选择器复杂度和功耗。Int Scheduler 承载了 11 个 IQ（4 个 ALU/BJU + 3 个 Ldu + 2 个 Sta + 2 个 Std），Fp Scheduler 3 个，Vec Scheduler 4 个。

2. **负载均衡**：Dispatch 阶段通过比较矩阵和排序网络实现多 IQ 间的负载均衡，避免热点 IQ。分段更新策略（`updateInterval=3`）在负载均衡精度和组合逻辑时序之间取得平衡。

3. **三层 Entry 结构**：EnqEntry（支持延迟唤醒路径）、Simple Entry（轻量级，支持基础唤醒和发射）、Complex Entry（完整功能，支持发射但不支持入队）三类条目在面积/功耗和灵活性间取得平衡。条目间的 Transfer 机制确保 EnqEntry 不会长时间被占用。

4. **双层唤醒**：WB 唤醒和 IQ-to-IQ 唤醒配合 MultiWakeupQueue 的多延迟 Pipeline，实现了快速且灵活的唤醒路径。MultiWakeupQueue 通过 `latencySet` 参数化支持任意延迟组合，`enqAppend` 端口支持跨域唤醒。

5. **最老优先**：基于年龄矩阵的 AgeDetector 确保指令按程序顺序发射。多层级联选择（Comp > Simp > Enq）保证了全局最优选择。

6. **精细的冲突避免**：FuBusyTable 和 WB BusyTable 共同管理功能单元和写回端口的冲突。多层 BusyTable 通过逐层 AND 合并生成最终的可发射掩码，避免功能单元冲突和写回端口竞争。

7. **Load 依赖链**：通过 loadDependency 向量追踪 Load 操作的数据依赖，依赖链逐周期左移，支持投机执行的正确取消。Load 取消信号（ldCancel）可以连锁清除依赖链中的所有条目。

8. **顺序性保证**：通过 `blockedByWaitForward` 和 `notBlockedByPrevious` 机制保证特殊指令（如 fence、wait-forward、block-backward）的顺序执行语义。
