# R04 - Fetch Target Queue (FTQ) 深度研究报告

## 1. 概述

Fetch Target Queue（FTQ）是 XiangShan 处理器前端（Frontend）的核心组件，位于 BPU（Branch Prediction Unit）和 IFU（Instruction Fetch Unit）之间。其主要职责是缓存 BPU 产生的预测目标地址，为 IFU 提供稳定的取指目标，同时协调后端（Backend）的分支解析（branch resolve）结果，完成预测训练和错误恢复。

FTQ 的设计灵感来源于经典论文 *"A Scalable Front-End Architecture for Fast Instruction Delivery"*（Reinman et al., ISCA 1999）。在 XiangShan 中，FTQ 不仅是一个简单的地址队列，还承担着以下关键功能：

- **预测存储与转发**：存储每个 Fetch Block 的起始 PC 和 taken CFI offset
- **前端流水线同步**：通过多个指针管理 BPU、Prefetch、IFU、Commit 等不同阶段的处理进度
- **预测训练中转**：缓存后端解析结果，形成 BPU Training 信息发送给 BPU
- **异常处理**：记录后端异常信息（Instruction Page Fault、Instruction Access Fault），确保异常不会因 redirect 而被错误清除
- **性能监控**：统计分支预测准确率、mispredict 类型等性能数据

FTQ 主要实现位于 `src/main/scala/xiangshan/frontend/ftq/Ftq.scala`，配合多个辅助模块协同工作。

---

## 2. FTQ 整体架构

### 2.1 架构图

```
                         +---------------------------------+
                         |          Branch Prediction      |
                         |              Unit (BPU)          |
                         +--------+------------+-----------+
                                  |            ^
                      BpuToFtqIO  |            |  FtqToBpuIO
                                  v            |
                   +------------------------------+
                   |            FTQ               |
                   |                              |
                   |  +-------+  +----------+    |
                   |  | Entry |  |  Meta     |    |
                   |  | Queue |  |  Queue    |    |
                   |  +-------+  +----------+    |
                   |                              |
                   |  +------------+ +----------+ |
                   |  |  Resolve   | |  Commit  | |
                   |  |  Queue     | |  Queue   | |
                   |  +------------+ +----------+ |
                   |                              |
                   |  +---------+ +----------+    |
                   |  |  CFI    | |  Spec     |    |
                   |  |  Queue  | |  Queue    |    |
                   |  +---------+ +----------+    |
                   +------+-------+-------+-------+
                          |       |       |
              FtqToICache |  Ftq  | Ftq   |
              + ICache    |  To   | To    |
                          |  Ifu  | Back  |
                          v       v  end  v
               +----------+  +---------+  +---------+
               |  ICache  |  |   IFU   |  | Backend |
               |          |  |         |  | (Ctrl)  |
               +----------+  +---------+  +---------+
```

### 2.2 源文件清单

| 文件 | 行数 | 职责 |
|------|------|------|
| `Ftq.scala` | 632 | FTQ 主模块，包含全部交互逻辑 |
| `Bundles.scala` | 153 | FTQ 相关数据结构定义（Entry, MetaEntry, ResolveEntry 等） |
| `FtqParameters.scala` | 45 | FTQ 配置参数（队列深度、流水线距离等） |
| `FtqPtr.scala` | 37 | FTQ 指针类型定义（CircularQueuePtr） |
| `FtqPtrVec.scala` | 58 | 多位指针向量，支持流水线多拍读取 |
| `FtqBundle.scala` | 21 | FTQ Bundle 基类 |
| `FtqModule.scala` | 21 | FTQ Module 基类 |
| `ResolveQueue.scala` | 195 | 分支解析结果缓存队列 |
| `CommitQueue.scala` | 71 | Call/Ret 提交队列，用于 RAS 训练 |
| `CommitQueuePtr.scala` | 19 | CommitQueue 指针类型 |
| `ResolveQueuePtr.scala` | 19 | ResolveQueue 指针类型 |
| `BackendRedirectReceiver.scala` | 60 | 后端 Redirect 接收逻辑（含提前发送优化） |
| `IfuRedirectReceiver.scala` | 42 | IFU Redirect 接收逻辑 |
| `CfiQueue.scala` | 62 | CFI offset 读写队列（SyncDataModuleTemplate 实现） |
| `EntryQueue.scala` | 65 | Entry 起始 PC 读写队列（SyncDataModuleTemplate 实现） |
| `MetaQueue.scala` | 54 | BPU Meta SRAM 存储（SplittedSRAMTemplate） |
| `SpeculationQueue.scala` | 53 | BPU Redirect 投机状态存储（SyncDataModuleTemplate） |

---

## 3. FTQ 参数配置

FTQ 的关键参数定义在 `FtqParameters.scala` 中：

```scala
case class FtqParameters(
    FtqSize:            Int = 64,          // FTQ 条目深度（必须为 2 的幂）
    ResolveQueueSize:   Int = 16,          // 分支解析队列深度
    BpRunAheadDistance: Int = 8,           // BPU 相对 IFU 的最大超前距离
    BpTrainStallLimit:  Int = 8,           // BPU 训练通道最大连续 stall 周期数
    CommitQueueSize:    Int = 64,          // Call/Ret 提交队列深度
    DropResolveCounterWidth: Int = 10      // 丢弃 resolve 计数器位宽
)
```

**关键设计约束**：

- **FtqSize = 64**：FTQ 共有 64 个条目，使用环形队列管理。2 的幂要求支持 `CircularQueuePtr` 的 flag 比较逻辑。
- **BpRunAheadDistance = 8**：限制 BPU 指针领先 IFU 指针不超过 8 个条目，防止 BPU 预测过远、占用过多 FTQ 资源，同时确保 redirect 信号能在合理时间内传播。
- **BpTrainStallLimit = 8**：限制 BPU 训练端口的连续 stall 周期，防止训练信息积压。

---

## 4. FTQ 条目格式与核心数据结构

### 4.1 FtqEntry - 预测存储条目

```scala
class FtqEntry extends FtqBundle {
  val startPc:        PrunedAddr  = PrunedAddr(VAddrBits)   // Fetch Block 起始虚拟地址
  val takenCfiOffset: Valid[UInt] = Valid(UInt(CfiPositionWidth.W))  // taken 分支的 CFI 偏移
}
```

这是 FTQ 最核心的数据结构。每个条目记录一个 Fetch Block 的：
- **startPc**：该 Fetch Block 的起始虚拟地址，由 BPU 提供
- **takenCfiOffset**：如果该 Fetch Block 内存在 taken 的控制流指令（CFI），记录其在 block 内的偏移量。`valid` 为 false 表示该 block 内无 taken CFI（即顺序取指）

该条目存储在 `entryQueue`（`Reg(Vec(FtqSize, new FtqEntry))`）中，由 BPU 预测时写入，由 IFU 和 ICache 读取。

### 4.2 MetaEntry - BPU 元数据条目

```scala
class MetaEntry extends FtqBundle {
  val meta        = new BpuMeta         // BPU 训练元数据
  val paddingBits = ...                 // 4-bit 对齐填充
}
```

BpuMeta 包含三个子结构：
- **BpuRedirectMeta**（`redirectMeta`）：包含 PHR（Path History Register）状态、RAS 投机指针等，用于 redirect 时恢复 BPU 内部状态
- **BpuResolveMeta**（`resolveMeta`）：包含 TAGE、SC、Main BTB、ITTAGE 等预测器的元数据，用于训练时更新
- **BpuCommitMeta**（`commitMeta`）：包含 RAS commit 信息，用于 commit 时训练 RAS

### 4.3 ResolveEntry - 分支解析条目

```scala
class ResolveEntry extends FtqBundle {
  val ftqIdx:   FtqPtr = new FtqPtr                         // 对应 FTQ 条目索引
  val flushed:  Bool   = Bool()                             // 是否已被 flush
  val startPc:  PrunedAddr = PrunedAddr(VAddrBits)          // 起始 PC
  val branches: Vec[Valid[BranchInfo]] = Vec(ResolveEntryBranchNumber, Valid(new BranchInfo))
}
```

ResolveEntry 在 `ResolveQueue` 中使用，将同一 Fetch Block 内多个分支的解析结果打包在一起。`BranchInfo` 包含：
- `target`：实际跳转目标地址
- `taken`：实际是否 taken
- `cfiPosition`：CFI 在 block 中的位置
- `attribute`：分支属性（条件分支/直接跳转/间接跳转/Call/Return 等）
- `mispredict`：是否预测错误

### 4.4 Resolve - 后端解析信号

```scala
class Resolve extends XSBundle {
  val ftqIdx:    FtqPtr          // 对应 FTQ 条目索引
  val ftqOffset: UInt            // 指令在 Fetch Block 内的偏移
  val pc:        PrunedAddr      // 分支指令的 PC
  val target:    PrunedAddr      // 实际跳转目标
  val taken:     Bool            // 实际是否 taken
  val mispredict: Bool           // 是否与预测不符
  val attribute: BranchAttribute // 分支属性
}
```

这是后端（Backend）发给 FTQ 的单条分支解析结果。每周期后端可以发送多条 `Resolve`（宽度为 `backendParams.BrhCnt`），FTQ 将其收集到 `ResolveQueue` 中。

### 4.5 BranchAttribute - 分支属性

```scala
class BranchAttribute extends Bundle {
  val isConditional: Bool   // 条件分支
  val isDirect:      Bool   // 直接跳转
  val isIndirect:    Bool   // 间接跳转
  val isCall:        Bool   // Call 指令
  val isReturn:      Bool   // Return 指令
  val isReturnAndCall: Bool // 既是 Return 又是 Call（嵌套）
  val needIttage:    Bool   // 需要 ITTAGE 预测
  // ...
}
```

---

## 5. FTQ 指针管理

FTQ 使用多个指针来追踪不同处理阶段的进度。所有指针均为 `FtqPtrVec` 类型（基于 `CircularQueuePtr`），指针指向的条目表示"**即将被处理**"（而非已处理完毕）。

### 5.1 指针定义

```scala
private val bpuPtr    = RegInit(FtqPtrVec())       // BPU 写入指针
private val pfPtr     = RegInit(FtqPtrVec(2))      // Prefetch 指针（2 位宽，支持 2-prefetch）
private val ifuPtr    = RegInit(FtqPtrVec(3))      // IFU 读取指针（3 位宽，支持提前读取）
private val ifuWbPtr  = RegInit(FtqPtrVec())       // IFU 回写指针
private val commitPtr = RegInit(FtqPtrVec(2))      // Commit 指针（2 位宽）
```

**指针之间的大小关系（正常运行时）**：

```
commitPtr <= ifuWbPtr <= ifuPtr <= pfPtr <= bpuPtr
  (旧)                                         (新)
```

### 5.2 FtqPtrVec 设计

`FtqPtrVec` 是一个指针向量，其核心设计意图是支持流水线多拍提前读取：

```scala
class FtqPtrVec(val num: Int = 1) extends FtqBundle {
  val ptrs: Vec[FtqPtr] = Vec(num, new FtqPtr)
  def :=(basePtr: FtqPtr): Unit = ptrs.zipWithIndex.foreach { case (ptr, offset) =>
    ptr := basePtr + offset.U
  }
}
```

例如，`ifuPtr = FtqPtrVec(3)` 表示 `ifuPtr(0)`、`ifuPtr(1)`、`ifuPtr(2)` 分别指向连续的 3 个 FTQ 条目。这使得 FTQ 可以在同一周期同时读取多个连续条目的信息（如构建 `nextStartVAddr`）。

### 5.3 各指针详细说明

#### bpuPtr - BPU 写入指针

- **含义**：BPU 即将写入的 FTQ 条目
- **推进条件**：BPU 预测有效（`prediction.fire`）且无 redirect
- **特殊行为**：当 BPU S3 Override 发生时（`bpuS3Redirect`），bpuPtr 会被重设为 `s3FtqPtr + 1.U`，因为 S3 阶段的覆盖会改变之前的预测
- **流量控制**：BPU 的 `prediction.ready` 受以下条件限制：
  - `distanceBetween(bpuPtr(0), commitPtr(0)) < FtqSize.U`（FTQ 未满）
  - `distanceBetween(bpuPtr(0), ifuPtr(0)) < BpRunAheadDistance.U`（BPU 不能超前 IFU 太远）
  - `bpTrainStallCnt < BpTrainStallLimit.U`（训练通道未长时间 stall）

#### pfPtr - Prefetch 指针

- **含义**：FTQ 即将向 ICache 发送 prefetch 请求的条目
- **推进条件**：`toICache.toPrefetch.fire`
- **推进步长**：通常 +1，若支持 2-prefetch 则 +2
- **宽度为 2**：支持同时读取两个条目用于 2-prefetch 判断
- **特殊行为**：BPU S3 Override 发生时，pfPtr 会被回退到 `s3FtqPtr` 位置

#### ifuPtr - IFU 取指指针

- **含义**：FTQ 即将发给 IFU 的 fetch 请求条目
- **推进条件**：`toIfu.req.fire`
- **宽度为 3**：支持提前 2 拍读取 next startPc，用于构建 `nextStartVAddr` 信号
- **特殊行为**：BPU S3 Override 或 redirect 时回退

#### ifuWbPtr - IFU 回写指针

- **含义**：IFU 即将回写的 FTQ 条目（IFU 完成该条目的取指后回写）
- **用途**：用于后端异常处理时判断异常指令是否已被 IFU 正确回写

#### commitPtr - 提交指针

- **含义**：FTQ 即将被 commit 的条目
- **推进条件**：`robCommitPtr >= commitPtr`（后端 ROB 提交指针已超过 commitPtr）
- **推进方式**：每周期 commit 一个条目（逐条 commit）
- **特殊行为**：commitPtr 使用 `DataHoldBypass` 锁存后端 commit 指针值，确保不丢失 commit 信号

### 5.4 指针距离校验

```scala
XSError(bpuPtr < ifuPtr && !isFull(bpuPtr(0), ifuPtr(0)),
  "ifuPtr runs ahead of bpuPtr")
```

该断言确保在正常运行中，IFU 指针不会超前于 BPU 指针（除非是环绕的情况）。

---

## 6. FTQ 与 BPU 的交互

### 6.1 接口定义

**BpuToFtqIO**（BPU -> FTQ）：

```scala
class BpuToFtqIO {
  val prediction: DecoupledIO[BpuPrediction]  // 预测结果
  val meta:       DecoupledIO[BpuMeta]        // BPU 元数据
  val s3FtqPtr:   FtqPtr                      // S3 阶段的 FTQ 指针
  val perfMeta:   BpuPerfMeta                 // 性能分析元数据
  val topdownReasons: FrontendTopDownBundle   // Top-down 分析原因
}
```

`BpuPrediction` 的内容：

```scala
class BpuPrediction {
  val startPc:        PrunedAddr     // Fetch Block 起始 PC
  val target:         PrunedAddr     // 预测跳转目标
  val takenCfiOffset: Valid[UInt]    // taken CFI 的 block 内偏移
  val s3Override:     Bool           // S3 阶段是否覆盖 S1 预测
}
```

**FtqToBpuIO**（FTQ -> BPU）：

```scala
class FtqToBpuIO {
  val redirect:  Valid[BpuRedirect]           // Redirect 信号
  val train:     DecoupledIO[BpuTrain]        // 训练信号
  val commit:    Valid[BpuCommit]             // Commit 信号
  val bpuPtr:    FtqPtr                       // 当前 bpuPtr 值
  val redirectFromIFU: Bool                   // 是否为来自 IFU 的 redirect
}
```

`BpuRedirect` 的内容：

```scala
class BpuRedirect {
  val cfiPc:     PrunedAddr      // CFI 虚拟地址
  val target:    PrunedAddr      // 正确跳转目标
  val taken:     Bool            // 实际 taken 情况
  val attribute: BranchAttribute // 分支属性
  val meta:      BpuRedirectMeta // PHR/RAS 等投机状态
}
```

`BpuTrain` 的内容：

```scala
class BpuTrain {
  val startPc:  PrunedAddr             // 起始 PC
  val branches: Vec[Valid[BranchInfo]] // 分支解析结果数组
  val meta:     BpuResolveMeta         // TAGE/BTB 等预测器元数据
  val perfMeta: BpuPerfMeta            // 性能分析数据
}
```

### 6.2 预测写入流程

1. BPU 产生预测，通过 `prediction` Decoupled 接口发送到 FTQ
2. 当 `prediction.fire` 且无 redirect 时，`bpuEnqueue` 为 true
3. FTQ 将预测信息写入 `entryQueue` 对应条目：
   ```scala
   entryQueue(predictionPtr.value).startPc := prediction.bits.startPc
   entryQueue(predictionPtr.value).takenCfiOffset := prediction.bits.takenCfiOffset
   ```
4. 同时将三类 meta 写入 `metaQueueRedirect`、`metaQueueResolve`、`metaQueueCommit`
5. bpuPtr 递增 +1

### 6.3 BPU S3 Override 机制

BPU 有三级流水线（S1/S2/S3），S3 阶段可能产生 Override（覆盖 S1 的预测）。当 S3 Override 发生时：

1. `prediction.bits.s3Override` 为 true
2. `predictionPtr` 指向 `io.fromBpu.s3FtqPtr`（而非 bpuPtr）
3. bpuPtr 被重设为 `s3FtqPtr + 1`
4. 同时 flush ICache 和 IFU 流水线中对应条目（通过 `flushFromBpu.stage(3)`）
5. pfPtr 和 ifuPtr 被回退到 s3FtqPtr 位置

S3 Override 的流水线位置：

```
bpu -> | fb4 | fb3 | fb2 | fb1 | fb0 | -> prefetch
       bpuPtr                   pfPtr
     bpu s1    s2    s3
```

fb0 已经过了 BPU S3 阶段（最后一个 Override 机会），因此其后的 2-prefetch 是安全的。

### 6.4 预测训练流程 - Resolve 训练

BPU 训练的核心路径是 Resolve 训练（来自后端分支解析）：

1. 后端通过 `io.fromBackend.resolve` 发送解析结果（每周期最多 `BrhCnt` 条）
2. `ResolveQueue` 收集并缓存这些结果
3. ResolveQueue 按 FTQ 索引聚合同一 Fetch Block 的多个分支到同一个 `ResolveEntry.branches` 数组中
4. 对比 `dropResolveCounter` 判断是否可以丢弃非 mispredict 的更新
5. 满足出队条件时（所有相关 resolve 都已到达且不在 redirect 范围内），通过 `bpuTrain` Decoupled 接口输出
6. FTQ 通过 `trainCache` 寄存器缓存一拍，避免 BPU 端 backpressure 问题
7. 最终通过 `io.toBpu.train` 发送给 BPU

```scala
resolveQueue.io.bpuTrain.ready := !trainCache.valid || io.toBpu.train.fire
when(resolveQueue.io.bpuTrain.fire) {
  trainCache.bits.meta     := metaQueueResolve(resolveQueue.io.bpuTrain.bits.ftqIdx.value)
  trainCache.bits.startPc  := resolveQueue.io.bpuTrain.bits.startPc
  trainCache.bits.branches := resolveQueue.io.bpuTrain.bits.branches
  trainCache.bits.perfMeta := perfQueue(resolveQueue.io.bpuTrain.bits.ftqIdx.value).bpuPerf
  trainCache.valid         := true.B
}
```

### 6.5 预测训练流程 - Commit 训练

Commit 训练用于 RAS（Return Address Stack）训练：

1. 后端通过 `io.fromBackend.callRetCommit` 发送 Call/Ret 提交信息
2. `CommitQueue` 缓存这些信息（只缓存 `rasAction != None` 的条目）
3. CommitQueue 按顺序出队
4. FTQ 通过 `io.toBpu.commit` 发送给 BPU，携带 RAS 操作信息

```scala
io.toBpu.commit.valid := commitQueue.io.bpuTrain.valid
io.toBpu.commit.bits.meta := metaQueueCommit(commitQueue.io.bpuTrain.bits.ftqPtr.value)
io.toBpu.commit.bits.attribute.rasAction := commitQueue.io.bpuTrain.bits.rasAction
```

### 6.6 BPU Redirect 流程

当后端或 IFU 发生 redirect 时：

1. FTQ 从 `metaQueueRedirect` 中读取对应条目的 `BpuRedirectMeta`（含 PHR、RAS 状态等投机信息）
2. 通过 `io.toBpu.redirect` 发送给 BPU
3. BPU 使用这些信息恢复其投机状态（如回退 PHR、RAS 指针等）

```scala
io.toBpu.redirect.valid       := redirect.valid
io.toBpu.redirect.bits.cfiPc  := getCfiPcFromOffset(PrunedAddrInit(redirect.bits.pc), redirect.bits.ftqOffset)
io.toBpu.redirect.bits.target := redirect.bits.target
io.toBpu.redirect.bits.taken  := redirect.bits.taken
io.toBpu.redirect.bits.attribute := redirect.bits.attribute
io.toBpu.redirect.bits.meta   := metaQueueRedirect(redirect.bits.ftqIdx.value)
io.toBpu.redirectFromIFU      := ifuRedirect.valid
```

---

## 7. FTQ 与 IFU/ICache 的交互

### 7.1 接口定义

**FtqToICacheIO**（FTQ -> ICache）：

```scala
class FtqToICacheIO {
  val toPrefetch:    DecoupledIO[FtqToPrefetchBundle]  // Prefetch 请求
  val fetchReq:      DecoupledIO[FtqFetchRequest]       // 取指请求
  val flushFromBpu:  BpuFlushInfo                       // BPU flush 信息
  val redirectFlush: Bool                               // redirect flush 标志
}
```

**FtqToIfuIO**（FTQ -> IFU）：

```scala
class FtqToIfuIO {
  val req:          DecoupledIO[FtqToIfuReq]  // 取指请求（含 FetchRequestBundle[] 和 topdownInfo）
  val redirect:     Valid[Redirect]           // Backend redirect
  val flushFromBpu: BpuFlushInfo             // BPU flush 信息
}
```

### 7.2 2-Prefetch 机制

FTQ 支持同时向 ICache 发送两个 prefetch 请求（2-prefetch），显著提升 ICache 命中率。判断条件为：

1. **BPU 领先距离充足**：`distanceBetween(bpuPtr(0), pfPtr(0)) > 3`。需要至少 4 个条目的间距，因为 BPU 有 3 级流水线，第 4 个条目已经通过了 S3 Override 的最后阶段，因此 prefetch 不会因 S3 Override 而失效
2. **同页限制**：两个 prefetch 请求的虚拟页号必须相同（`vPageNumber` 相等），以避免 ITLB 端口冲突
3. **无异常**：待 prefetch 的条目不能位于后端异常条目处

当 2-prefetch 可行时，FTQ 发送包含两个请求的 `FtqToPrefetchBundle`，两个请求的 `startVAddr` 分别来自 `entryQueue(pfPtr(0).value)` 和 `entryQueue(pfPtr(1).value)`。否则发送 `TwoPrefetchCase.Conflict` 标记。

### 7.3 Fetch 请求构建

FTQ 为 IFU 和 ICache 构建 fetch 请求时：

**有效条件**：
```scala
val ifuReqValid = bpuPtr(0) > ifuPtr(0) && !redirect.valid &&
  distanceBetween(ifuPtr(0), commitPtr(0)) < (FtqSize - 1).U
```

**IFU 请求内容**：
- `fetch(0).startVAddr`：从 `entryQueue(ifuPtr(0).value).startPc` 读取
- `fetch(0).nextStartVAddr`：使用 `MuxCase` 选择下一个块的起始地址
- `fetch(0).takenCfiOffset`：从 entryQueue 读取
- `fetch(0).ftqIdx`：当前 ifuPtr 值

**nextStartVAddr 选择逻辑**（关键的时序优化）：
```scala
io.toIfu.req.bits.fetch(0).nextStartVAddr := MuxCase(
  entryQueue(ifuPtr(1).value).startPc,              // 正常情况：读取下一条目
  Seq(
    (bpuPtr(0) === ifuPtr(0)) -> prediction.bits.target,   // BPU 刚好在当前条目，用 target
    (bpuPtr(0) === ifuPtr(1)) -> prediction.bits.startPc   // BPU 刚好在下一条目，用 startPc
  )
)
```

这种设计确保 IFU 可以提前获得下一块的起始地址，减少流水线气泡。

**ICache 请求内容**：
- `startVAddr`：从 entryQueue 读取
- `nextCachelineVAddr`：startVAddr + CacheLineSize
- `ftqIdx`：当前 ifuPtr 值
- `takenCfiOffset`：从 entryQueue 读取
- `isBackendException`：标记是否为异常条目

### 7.4 BPU Flush 传播

当 BPU S3 Override 发生时，FTQ 需要同时 flush ICache 和 IFU 流水线中对应条目：

```scala
io.toICache.flushFromBpu.stage(3).valid := redirect
io.toICache.flushFromBpu.stage(3).bits  := ftqIdx     // s3FtqPtr
io.toIfu.flushFromBpu.stage(3).valid    := redirect
io.toIfu.flushFromBpu.stage(3).bits     := ftqIdx
```

### 7.5 Fetch 请求与 ICache 交互

当 fetch 请求发给 ICache 时，`twoFetchInfoVec` 中的 prefetch 信息（wayMask, isMmio）被传递给 ICache main pipe，辅助 cache 命中判断：

```scala
when(io.fromICache.fromPrefetch.valid) {
  val ftqIdx = io.fromICache.fromPrefetch.bits.ftqIdx
  twoFetchInfoVec(ftqIdx.value) := twoFetchInfo(0).bits
}
```

---

## 8. FTQ 与后端的交互

### 8.1 接口定义

**CtrlToFtqIO**（Backend CtrlBlock -> FTQ）：

```scala
class CtrlToFtqIO {
  val redirect:    Valid[Redirect]                            // Redirect 信号
  val ftqIdxAhead: Vec[BackendRedirectNum, Valid[FtqPtr]]    // 提前发送的 FTQ 索引
  val ftqIdxSelOH: Valid[UInt]                                // 索引选择 one-hot
  val resolve:     Vec[BrhCnt, Valid[Resolve]]               // 分支解析结果（多端口）
  val commit:      Valid[FtqPtr]                              // ROB 提交指针
  val callRetCommit: Vec[CommitWidth, Valid[CallRetCommit]]  // Call/Ret 提交信息
}
```

**FtqToCtrlIO**（FTQ -> Backend）：

```scala
class FtqToCtrlIO {
  val wen:     Bool       // 写使能
  val ftqIdx:  UInt       // FTQ 索引
  val startPc: PrunedAddr // 起始 PC
}
```

### 8.2 FTQ 写入后端 PC Mem

每次 BPU 产生新预测时，FTQ 将起始 PC 信息写入后端的 PC 存储器，供后端 debug 和异常处理时重建完整 PC：

```scala
io.toBackend.wen     := (prediction.fire || bpuS3Redirect) && !redirect.valid
io.toBackend.ftqIdx  := predictionPtr.value
io.toBackend.startPc := prediction.bits.startPc
```

### 8.3 Backend Redirect 接收 - 提前发送优化

`BackendRedirectReceiver` trait 实现了后端 redirect 的接收逻辑，支持**提前发送 FTQ 索引**的时序优化机制：

1. 后端在 redirect 发生前一周期，通过 `ftqIdxAhead(0)` 提前发送 redirect 对应的 FTQ 索引
2. FTQ 使用该索引提前读取 `metaQueueRedirect` 等队列数据
3. 当真正 redirect 到达时，FTQ 已经准备好所需数据，可以直接处理
4. 如果 `ftqIdxAhead` 已发送，redirect 直接使用；否则 redirect 需延迟一拍（通过 `RegNext`）

```scala
val ftqIdxInAdvance = Wire(Valid(new FtqPtr))
ftqIdxInAdvance.valid := fromBackend.ftqIdxAhead(0).valid && !fromBackend.redirect.valid
ftqIdxInAdvance.bits  := fromBackend.ftqIdxAhead(0).bits

val ftqIdxInAdvanceValidNext = RegNext(ftqIdxInAdvance.valid)
val redirectReg = RegNext(redirect)
redirectReg.valid := redirect.valid && !ftqIdxInAdvanceValidNext
```

### 8.4 Backend Redirect 处理

当 redirect 有效时，FTQ 需要进行以下操作：

**步骤 1 - 确定新条目指针**：
```scala
val newEntryPtr = Mux(
  RedirectLevel.flushItself(redirect.bits.level) &&
    (redirect.bits.ftqOffset === 0.U ||
     redirect.bits.ftqOffset === 1.U && !redirect.bits.isRVC),
  redirect.bits.ftqIdx,           // flush itself: 回到 redirect 条目
  redirect.bits.ftqIdx + 1.U      // flushAfter: 从 redirect+1 开始
)
```

- `flushAfter` 级别：从 `ftqIdx + 1` 开始（跳过 redirect 条目，因为该条目已被处理）
- `flushItself` 级别：从 `ftqIdx` 开始（重新处理 redirect 条目，因为该条目的第一条指令就是 redirect 源）
- 特殊判断：当 `ftqOffset` 为 0（第一条指令），或 `ftqOffset` 为 1 且第一条不是 RVC（说明第二条指令是 redirect 源），则使用 flushItself

**步骤 2 - 重置流水线指针**：
```scala
Seq(bpuPtr, ifuPtr, pfPtr).foreach(_ := newEntryPtr)
```

三个主要指针同时重置，确保前端流水线从正确位置重新开始。

**步骤 3 - 通知下游模块**：
```scala
io.toICache.redirectFlush := redirect.valid    // 通知 ICache
io.toIfu.redirect.valid := backendRedirect.valid  // 通知 IFU
io.toBpu.redirect.valid := redirect.valid         // 通知 BPU
```

### 8.5 Branch Resolve 处理

后端通过 `io.fromBackend.resolve` 发送分支解析结果。每周期可发送 `backendParams.BrhCnt` 条解析结果。

FTQ 对每条解析结果进行性能监控记录：

```scala
io.fromBackend.resolve.foreach { branch =>
  val ftqIdx      = branch.bits.ftqIdx.value
  val cfiPosition = getAlignedPosition(branch.bits.pc, branch.bits.ftqOffset)._1
  when(branch.valid) {
    perfQueue(ftqIdx).isCfi(cfiPosition) := true.B
    when(branch.bits.mispredict) {
      perfQueue(ftqIdx).mispredict := true.B
      perfQueue(ftqIdx).mispredictBranchInfo.fromResolve(branch.bits)
      // 清除 mispredict 之后位置的 CFI 标记
      val mask = UIntToMask(cfiPosition + 1.U, FetchBlockInstNum)
      perfQueue(ftqIdx).isCfi := (perfQueue(ftqIdx).isCfi.asUInt & mask).asBools
    }
  }
}
```

同时将解析结果发送到 `ResolveQueue` 进行聚合和训练：

```scala
resolveQueue.io.backendResolve := io.fromBackend.resolve
resolveQueue.io.backendRedirect    := backendRedirect.valid
resolveQueue.io.backendRedirectPtr := backendRedirect.bits.ftqIdx
```

### 8.6 Commit 处理

```scala
private val robCommitPtr = DataHoldBypass(
  io.fromBackend.commit.bits,
  FtqPtr(true.B, (FtqSize - 1).U),
  io.fromBackend.commit.valid
)
private val commit = commitPtr <= robCommitPtr
when(commit) { commitPtr := commitPtr + 1.U }
```

**设计要点**：
- 后端可能对同一 FTQ 条目发送多次 commit（例如多个功能单元的 commit 到达），但实际只在第一次 commit 时推进 `commitPtr`
- `DataHoldBypass` 确保即使在非 commit 周期，`robCommitPtr` 仍保持最新值
- 默认值 `FtqPtr(true.B, (FtqSize - 1).U)` 表示初始状态下 commitPtr 不被推进

---

## 9. IFU Redirect 处理

`IfuRedirectReceiver` trait 处理 IFU 发出的前端 redirect（通常是 RAS 修正或 Early Exit）：

```scala
def receiveIfuRedirect(
    wbRedirect:  Valid[FrontendRedirect],
    specTopAddr: UInt
): Valid[Redirect] = {
  val redirect = WireInit(0.U.asTypeOf(Valid(new Redirect)))
  redirect.valid          := wbRedirect.valid
  redirect.bits.ftqIdx    := wbRedirect.bits.ftqIdx
  redirect.bits.ftqOffset := wbRedirect.bits.ftqOffset
  redirect.bits.level     := RedirectLevel.flushAfter   // IFU redirect 总是 flushAfter
  redirect.bits.target    := Mux(wbRedirect.bits.attribute.isReturn,
                                  specTopAddr,           // Return 指令使用 RAS specTop 地址
                                  wbRedirect.bits.target)
  redirect.bits.taken     := wbRedirect.bits.taken
  redirect
}
```

**关键设计**：对于 Return 类型指令，redirect target 使用 `specTopAddr`（即 `metaQueueRedirect(ftqIdx).ras.topRetAddr`），而非 IFU 直接计算的 target。这是因为在 redirect 时刻，FTQ 中的 RAS 投机状态比 IFU 更准确，`specTopAddr` 反映了更接近真实执行路径的 RAS 状态。

---

## 10. Redirect 统一与优先级

FTQ 中将后端 redirect 和 IFU redirect 统一处理：

```scala
private val redirect     = RegNext(Mux(backendRedirect.valid, backendRedirect, ifuRedirect))
private val redirectNext = RegNext(redirect)
```

**优先级**：后端 redirect 优先于 IFU redirect（`Mux(backendRedirect.valid, ...)`）。这是因为后端 redirect 通常反映更严重的错误（如 misprediction、memory ordering violation），而 IFU redirect 通常是 RAS 修正等较轻量的事件。

**延迟一拍**：`redirect` 被延迟一拍（`RegNext`），这是出于时序考虑——所有指针重置、flush 信号等均使用延迟后的 `redirect` 信号。

**redirectNext** 再延迟一拍，用于 prefetch 阶段的地址修正，确保在 redirect 后的下一周期能给出正确的 prefetch 目标地址：

```scala
req.startVAddr := Mux(redirectNext.valid, PrunedAddrInit(redirectNext.bits.target), prefetchReq(i).startVAddr)
```

---

## 11. ResolveQueue 深度解析

### 11.1 设计目标

`ResolveQueue` 是 FTQ 中最复杂的子模块，负责：
- 缓存后端分支解析结果
- 按 FTQ 索引聚合同一 Fetch Block 内的多个分支
- 过滤已被 redirect flush 的解析结果
- 优化 BPU 训练：通过 DropResolve 机制减少不必要的更新

### 11.2 核心逻辑

**容量管理**：队列深度为 `ResolveQueueSize`（默认 16），满阈值为 `ResolveQueueSize - 4`。使用顺序队列（sequential queue）实现，需要 4 个条目的余量来处理已 flushed 但尚未清除的条目。

**Redirect 过滤**：后端 redirect 会传播 3 个周期（`RedirectDelay = 3`），在此期间，FTQ idx 大于 `backendRedirectPtr` 的解析结果会被标记为无效：

```scala
val filteredResolve = io.backendResolve.map { backendResolve =>
  val filteredResolve = Wire(Valid(new Resolve))
  filteredResolve.valid := backendResolve.valid &&
    !(backendRedirect.reduce(_ || _) && backendResolve.bits.ftqIdx > backendRedirectPtr)
  filteredResolve.bits := backendResolve.bits
  filteredResolve
}
```

这是因为已进入功能单元的分支不会被 redirect 清除，但其 meta 可能已被新分支覆盖。

**Hit 检测与聚合**：新到达的 resolve 结果会先检查队列中是否已有同一 FTQ 索引的条目：

```scala
private val hit = resolve.map { branch =>
  mem.map(entry =>
    branch.valid && entry.valid && !entry.bits.flushed && entry.bits.ftqIdx === branch.bits.ftqIdx
  ).reduce(_ || _) || ...
}
```

三种情况：
- **Hit**（队列中已有同 FTQ 索引条目）：新分支解析结果追加到已有条目的 `branches` 数组的空闲槽位
- **HitPrevious**（同一批次内前一个 resolve 已创建条目）：追加到该新条目
- **Miss**：创建新条目

### 11.3 DropResolve 功耗优化

```scala
private val dropResolveCounter = RegInit(0.U.asTypeOf(new SaturateCounter(DropResolveCounterWidth)))
when(hasResolve) {
  when(hasMispredict) {
    dropResolveCounter.selfDecrease(step = DropResolveCounterDecreaseValue.U)  // mispredict 时大幅重置
  }.otherwise {
    dropResolveCounter.selfIncrease(step = 1.U)  // 正常 resolve 时递增
  }
}
```

当 `dropResolveCounter` 饱和时（长时间无 mispredict），ResolveQueue 进入"丢弃模式"：

```scala
val shouldDrop = !hasMispredict && (dropResolveCounter.isSaturatePositive || isDirect)
```

被丢弃的解析结果包括：
- 所有非 mispredict 的 direct 类型解析（因为 direct 跳转的目标地址训练收益较低）
- 所有非 mispredict 且 DropResolveCounter 饱和时的解析

一旦出现 mispredict，计数器通过 `selfDecrease(step = 512)` 大幅降低，立即恢复正常模式。

**优化效果**：
- 减少 BPU 训练次数，降低功耗
- 减少 TAGE、SC 等预测器的 SRAM 读端口冲突

### 11.4 Flush 逻辑

ResolveQueue 中的条目在以下情况会被标记为 flushed：

```scala
mem.foreach { entry =>
  when(entry.valid &&
    (backendRedirect && entry.bits.ftqIdx > backendRedirectPtr ||
      io.bpuEnqueue && entry.bits.ftqIdx.value === io.bpuEnqueuePtr.value)) {
    entry.bits.flushed := true.B
  }
}
```

两种触发条件：
1. **后端 redirect**：条目在 redirect 位置之后（已预测错误的路径上的分支解析无意义）
2. **BPU 写入同一索引**：BPU 的 S3 Override 可能覆盖了该索引的旧预测，旧的 resolve 不再有效

出队时，已 flushed 的条目会被直接清除：

```scala
when(io.bpuTrain.fire || mem(deqPtr.value).valid && mem(deqPtr.value).bits.flushed) {
  deqPtr := deqPtr + 1.U
  mem(deqPtr.value).valid        := false.B
  mem(deqPtr.value).bits.flushed := false.B
  mem(deqPtr.value).bits.branches.foreach(_.valid := false.B)
}
```

---

## 12. CommitQueue 设计

### 12.1 功能定位

`CommitQueue` 专门处理 Call/Ret 类型指令的提交，为 RAS（Return Address Stack）训练提供准确的提交顺序信息。

### 12.2 入队逻辑

```scala
private val isCallRet = io.backendCommit.map(instr =>
  instr.valid && instr.bits.rasAction =/= BranchAttribute.RasAction.None
)
```

只有 `rasAction` 非 None 的提交（即 Call 或 Ret 指令）才会入队。入队索引需要考虑前面有多少个 Call/Ret 条目：

```scala
private val enqIndex = VecInit((0 until CommitWidth).map(i =>
  (enqPtr + PopCount(isCallRet.take(i))).value
))
```

### 12.3 出队逻辑

```scala
when(mem(deqPtr.value).valid) {
  deqPtr := deqPtr + 1.U
  mem(deqPtr.value).valid := false.B
}
io.bpuTrain.valid := mem(deqPtr.value).valid
io.bpuTrain.bits  := mem(deqPtr.value).bits
```

CommitQueue 按顺序出队，通过 `io.toBpu.commit` 将 RAS commit 信息发送给 BPU。这保证了 RAS 的 train 信息严格按程序顺序提交，避免 RAS 状态混乱。

### 12.4 容量管理

```scala
private val full = distanceBetween(enqPtr, deqPtr) >= (CommitQueueSize - 8).U
```

满阈值为 `CommitQueueSize - 8`（默认 64 - 8 = 56），预留 8 个条目的余量以应对突发的大量 Call/Ret 提交。

---

## 13. 异常处理机制

### 13.1 后端异常记录

```scala
private val backendException    = RegInit(ExceptionType.None)
private val backendExceptionPtr = RegInit(FtqPtr(false.B, 0.U))
when(backendRedirect.valid) {
  val exception = ExceptionType.fromBackend(backendRedirect.bits)
  backendException := exception
  when(exception.hasException) {
    backendExceptionPtr := ifuWbPtr(0)
  }
}.elsewhen(ifuWbPtr(0) =/= backendExceptionPtr) {
  backendException := ExceptionType.None
}
```

**关键设计**：异常指针指向 `ifuWbPtr(0)`（而非 redirect 目标），因为异常信息需要等 IFU 回写完成后才能被安全清除。

异常类型支持：Page Fault (Pf)、Guest Page Fault (Gpf)、Access Fault (Af)、Illegal Instruction (Ill)、Hardware Error (Hwe)。

### 13.2 异常安全保障

当 IPF（Instruction Page Fault）或 IAF（Instruction Access Fault）与 redirect 同时发生时：

1. 后端 redirect 指向正确的恢复路径
2. 但 `backendException` 和 `backendExceptionPtr` 仍保持不变
3. 直到 IFU 回写完成该异常条目后（`ifuWbPtr(0) =/= backendExceptionPtr`），异常信息才被清除
4. 在此期间，FTQ 通过 `isBackendException` 标记通知 ICache 和 IFU 存在异常

### 13.3 Fetch 请求中的异常标记

```scala
io.toICache.fetchReq.bits.isBackendException :=
  backendException.hasException && backendExceptionPtr === ifuPtr(0)
io.toIfu.req.bits.fetch(0).isBackendException :=
  backendException.hasException && backendExceptionPtr === ifuPtr(0)
```

当 fetch 请求的目标条目存在后端异常时，异常标记会传递给 ICache 和 IFU。ICache 可以据此跳过无效的 cache 操作，IFU 可以据此触发正确的异常处理流程。

### 13.4 Redirect 期间的异常保护

```scala
io.toICache.toPrefetch.bits.req.backendException :=
  Mux(backendExceptionPtr === pfPtr(i), backendException, ExceptionType.None)
```

Prefetch 请求也会携带异常信息，确保在异常路径上不进行无意义的 cache line 预取。

---

## 14. 性能监控与 Top-Down 分析

### 14.1 PerfQueue 性能数据收集

FTQ 通过 `perfQueue`（`Reg(Vec(FtqSize, new PerfMeta))`）记录每条目的性能数据：

```scala
class PerfMeta extends FtqBundle {
  val bpuPerf:    BpuPerfMeta        // BPU 性能数据
  val isCfi:      Vec[Bool]          // 每个位置是否为 CFI
  val mispredict: Bool               // 该 block 是否发生 mispredict
  val mispredictBranchInfo: BranchInfo  // mispredict 分支的详细信息
}
```

### 14.2 重定向分类统计

FTQ 对后端 redirect 进行详细分类统计：

| 统计项 | 说明 |
|--------|------|
| `wrong_taken` | taken 判断错误（预测 taken 但实际未 taken，或反之） |
| `wrong_position` | CFI 位置判断错误（预测在某位置有 CFI，实际在另一位置） |
| `wrong_attribute` | 分支属性判断错误（如将间接跳转预测为条件分支） |
| `wrong_target` | 目标地址错误（taken 判断和位置都正确，但目标地址错误） |

错误类型的细分统计（`wrong_target` 场景下）：

| 统计项 | 说明 |
|--------|------|
| `conditional` | 条件分支目标错误 |
| `direct` | 直接跳转目标错误 |
| `indirect` | 间接跳转目标错误 |
| `indirect_ret_call` | Return/Call 类型的间接跳转目标错误 |

### 14.3 BPU 来源统计

FTQ 统计 mispredict 时 BPU 预测的来源（S1 阶段或 S3 Override）：

```scala
XSPerfSeqAccumulate("resolve_branch_mispredicts_s1_source", ...)
XSPerfSeqAccumulate("resolve_branch_mispredicts_s3_source", ...)
```

### 14.4 Commit 时统计

在 FTQ 条目 commit 时（正确路径上），统计：
- 每个 block 中的分支数量（`PopCount(commitPerfMeta.isCfi)`）
- 是否包含 mispredict
- mispredict 分支的类型（conditional/direct/indirect/call/ret）
- mispredict 的 blame 分析（`BlameBpuSource`）

### 14.5 Top-Down 分析

```scala
io.backendRedirectTopdown.backendRedirect         := backendRedirect.valid
io.backendRedirectTopdown.controlFlowRedirect     := backendRedirect.bits.debugIsCtrl
io.backendRedirectTopdown.memoryViolationRedirect := backendRedirect.bits.debugIsMemVio
io.backendRedirectTopdown.tageMissBubble    := backendRedirect.bits.attribute.isConditional
io.backendRedirectTopdown.ittageMissBubble  := backendRedirect.bits.attribute.needIttage
io.backendRedirectTopdown.rasMissBubble     := backendRedirect.bits.attribute.isReturn
```

后端 redirect 的 Top-Down 分类：
- **controlFlowRedirect**：控制流预测错误（分支 misprediction）
- **memoryViolationRedirect**：内存排序违规（如 store-to-load forwarding 失败）
- **tageMissBubble**：条件分支预测失败（TAGE 未命中）
- **ittageMissBubble**：间接跳转预测失败（ITTAGE 未命中）
- **rasMissBubble**：Return 地址预测失败（RAS 未命中）

### 14.6 FTQ 停顿监控

```scala
when(!(distanceBetween(bpuPtr(0), commitPtr(0)) < FtqSize.U)) {
  topdownStage.reasons(TopDownCounters.FtqFullStall.id) := true.B
}.elsewhen(!(distanceBetween(bpuPtr(0), ifuPtr(0)) < BpRunAheadDistance.U &&
            bpTrainStallCnt < BpTrainStallLimit.U)) {
  topdownStage.reasons(TopDownCounters.FtqUpdateBubble.id) := true.B
}
```

两种 Top-Down 损失原因：
- **FtqFullStall**：FTQ 已满（BPU 和 commit 指针间距 >= FtqSize），BPU 无法写入新预测
- **FtqUpdateBubble**：BPU 超前距离达到上限或训练通道长时间 stall

### 14.7 指针距离直方图

```scala
XSPerfHistogram("distance_between_bpu_commit", distanceBetween(bpuPtr(0), commitPtr(0)), ...)
XSPerfHistogram("distance_between_ifu_commit", distanceBetween(ifuPtr(0), commitPtr(0)), ...)
XSPerfHistogram("distance_between_bpu_ifu",     distanceBetween(bpuPtr(0), ifuPtr(0)), ...)
```

---

## 15. 辅助存储模块

### 15.1 EntryQueue - 起始 PC 存储

`EntryQueue` 使用 `SyncDataModuleTemplate` 实现多端口同步读取，支持：
- 2 个 prefetch 读端口
- 3 个 IFU 读端口
- 2 个 commit 读端口
- 1 个写端口

在当前的 `Ftq.scala` 主模块中，起始 PC 直接存储在 `entryQueue`（寄存器数组）中。`EntryQueue` 模块作为 SRAM 替代方案存在，可在面积优化时启用。

### 15.2 CfiQueue - CFI Offset 存储

`CfiQueue` 同样使用 `SyncDataModuleTemplate` 实现，提供 CFI offset 的多端口读取。当前实现中 CFI offset 信息直接存储在 `entryQueue` 的 `takenCfiOffset` 字段中。

### 15.3 MetaQueue - BPU Meta 存储

`MetaQueue` 使用 `SplittedSRAMTemplate` 实现，相比寄存器数组可节省大量面积：
- 数据分 2 片存储（`dataSplit = 2`），降低 SRAM 位宽
- 支持 clock gating（`withClockGate = true`）
- 支持 MBIST（`hasMbist`）

### 15.4 SpeculationQueue - 投机状态存储

`SpeculationQueue` 使用 `SyncDataModuleTemplate` 实现，存储 `BpuRedirectMeta`（PHR 路径历史状态、RAS 投机指针等），用于 redirect 时恢复 BPU 的投机状态。这是 FTQ 与 BPU 之间状态同步的关键桥梁。

---

## 16. 控制流总结

### 16.1 正常取指流程

```
BPU Prediction
    |
    v
FTQ entryQueue 写入  --->  bpuPtr++
    |
    +----> ICache Prefetch  --->  pfPtr++ (1 or 2)
    |
    +----> IFU Fetch Request  --->  ifuPtr++
              |
              +--> Backend PC Mem write
```

1. BPU 产生预测，FTQ 写入 entryQueue 和 metaQueues，bpuPtr++
2. FTQ 读取 entry，构建 prefetch 请求发送给 ICache（支持 2-prefetch），pfPtr++
3. FTQ 读取 entry，构建 fetch 请求发送给 IFU，ifuPtr++
4. 后端 ROB 提交，commitPtr++
5. 提交信息发送到 CommitQueue，触发 RAS commit training

### 16.2 分支解析与训练流程

```
Backend Resolve (multi-port)
    |
    v
ResolveQueue (aggregate per FTQ idx, filter by redirect)
    |
    v
BpuTrain (Decoupled)  --->  trainCache  --->  BPU Training
    |                            |
    +-- metaQueueResolve         +-- perfQueue
```

### 16.3 Redirect 恢复流程

```
Backend/IFU Redirect
    |
    v
FTQ redirect logic:
    1. 计算 newEntryPtr (flushItself vs flushAfter)
    2. 重置 bpuPtr, ifuPtr, pfPtr -> newEntryPtr
    3. Flush ICache (redirectFlush)
    4. Flush IFU (redirect.valid)
    5. Send redirect to BPU (with BpuRedirectMeta)
    6. Update ResolveQueue (mark entries with ftqIdx > redirectPtr as flushed)
    7. Update metaQueueCommit for exception tracking
```

### 16.4 Commit 与 RAS 训练流程

```
Backend CallRetCommit  --->  CommitQueue (enqueue)
                                  |
                                  v
Backend ROB commit ptr  --->  FTQ commitPtr++
                                  |
CommitQueue (dequeue)  --->  io.toBpu.commit  --->  BPU RAS Training
```

---

## 17. 关键设计总结

1. **流水线指针分级管理**：通过 5 组指针（bpuPtr, pfPtr, ifuPtr, ifuWbPtr, commitPtr）精确追踪不同处理阶段的进度，实现 BPU 预测、Prefetch、IFU 取指、IFU 回写、后端提交的完全解耦和并行处理。

2. **提前发送优化**：`BackendRedirectReceiver` 支持后端提前一周期发送 FTQ 索引（`ftqIdxAhead`），使 FTQ 可以提前读取 `metaQueueRedirect` 等数据，显著改善 redirect 路径的时序。

3. **S3 Override 全局 flush**：BPU S3 阶段的 override 不仅影响 FTQ，还通过 `flushFromBpu.stage(3)` 信号同时 flush ICache 和 IFU 流水线，并回退 pfPtr 和 ifuPtr 到正确位置。

4. **训练功耗优化**：通过 `DropResolveCounter` 饱和机制，在 BPU 预测准确率较高时自动减少训练更新次数，降低功耗并减少 TAGE/SC 等预测器的 SRAM 读端口冲突。

5. **异常安全保障**：后端异常信息与 `ifuWbPtr` 绑定，确保 IPF/IAF 不会在 IFU 回写前被 redirect 错误清除。所有 prefetch 和 fetch 请求都携带异常标记。

6. **2-Prefetch 优化**：在条件允许时（BPU 领先距离 > 3、同页、无异常），同时 prefetch 两个 cache line，提升 ICache 命中率，减少 fetch 停顿。

7. **统一 Redirect 优先级**：后端 redirect 优先于 IFU redirect，通过 `RegNext` 延迟一拍后统一处理所有指针重置和 flush 操作。`redirectNext` 再延迟一拍用于 prefetch 目标修正。

8. **RAS 投机状态恢复**：IFU redirect 中 Return 类型使用 `specTopAddr`（FTQ 中 RAS 投机状态）而非 IFU 计算值，确保 RAS 恢复准确性。

---

## 18. 参考文献

1. Glenn Reinman, Todd Austin, Brad Calder. "A Scalable Front-End Architecture for Fast Instruction Delivery." 26th International Symposium on Computer Architecture (ISCA), 1999. https://doi.org/10.1109/ISCA.1999.765954

2. XiangShan 源代码: `src/main/scala/xiangshan/frontend/ftq/` 目录下所有文件
