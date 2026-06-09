# R39B - L2 Prefetch & Probe 深度研究报告

## 目录

1. [L2 Prefetcher 设计与算法](#1-l2-prefetcher-设计与算法)
2. [Probe Queue 处理机制](#2-probe-queue-处理机制)
3. [Source B（Snoop 生成）](#3-source-b)
4. [Sink C（Release/ProbeAck 处理）](#4-sink-c)
5. [Sink D（Grant 数据处理）](#5-sink-d)
6. [Request Buffer 与仲裁](#6-request-buffer-与仲裁)
7. [Debug 工具](#7-debug-工具)
8. [源文件位置汇总](#8-源文件位置汇总)

---

## 1. L2 Prefetcher 设计与算法

### 1.1 总体架构

XiangShan L2 Cache 的 Prefetch 子系统位于 `XSCache/src/main/scala/coupledL2/prefetch/` 目录下，实现了多源异构预取（Multi-Source Heterogeneous Prefetching）架构。顶层 `Prefetcher` 模块（`Prefetcher.scala`）通过可配置参数决定启用哪些子预取器，并通过统一的仲裁逻辑将所有预取请求汇聚输出。

整体数据流如下：

```
L1 Miss / TLB Miss → PrefetchTrain → 各子 Prefetcher → PrefetchReq Queue → Pipeline → L2 SinkA
```

### 1.2 PrefetchParameters 与全局配置

每个子预取器通过 `PrefetchParameters` trait 声明自身特征：

```scala
trait PrefetchParameters {
  val hasPrefetchBit:  Boolean  // 是否使用 prefetch 位标记
  val hasPrefetchSrc:  Boolean  // 是否携带预取源信息
  val inflightEntries: Int      // 最大 in-flight 预取请求数
}
```

全局 `PfSource` 枚举定义了所有可能的预取源标识：

| 枚举值 | 来源 |
|--------|------|
| `Prefetch2L2BOP` | Best-Offset Prefetcher |
| `Prefetch2L2PBOP` | Physical BOP |
| `Prefetch2L2SMS` | Spatial Memory Streaming |
| `Prefetch2L2TP` | Temporal Prefetcher |
| `Prefetch2L2Stream` | Stream Prefetcher |
| `Prefetch2L2Stride` | Stride Prefetcher |
| `Prefetch2L2Berti` | Berti Prefetcher |
| `Prefetch2L2NL` | Next-Line Prefetcher |

### 1.3 Prefetcher 顶层仲裁

`Prefetcher` 模块内部实例化最多 5 个子预取器源，通过 **5 选 1 Round-Robin Arbiter** 仲裁：

- **优先级**：`Rcv > NL > VBOP > PBOP > TP`（接收器 > NextLine > Virtual BOP > Physical BOP > Temporal）
- **仲裁器**：`pftQueueEnqArb` 使用 `Arbiter(PrefetchReq, 5)`
- **中间存储**：`OverwriteQueue`（可覆盖写队列），深度为 `inflightEntries`，当队列满时，新请求可覆盖旧条目
- **流水线**：`Pipeline(PrefetchReq, 1)` 为输出增加 1 级流水

启用控制由 CSR（Constantin 寄存器）驱动：

- `l2_pf_master_en`：总开关
- `l2_pbop_en` / `l2_vbop_en` / `l2_tp_en`：各子预取器独立开关
- `l2_pf_recv_en`：上层接收器开关
- `l2_pf_delay_latency`：延迟队列延迟控制

### 1.4 Best-Offset Prefetcher（BOP）

#### 1.4.1 核心思想

BOP 基于 Pierre Michaud 的论文实现，核心思想是：**利用 Recent Request Table（RR Table）来发现当前程序的最佳步长偏移（Best Offset）**，然后在后续访问中按该偏移发出预取请求。

算法分两个阶段：
1. **Learning Phase**：在每个 Learning Phase 开始时清零 Score Table。对每个 L2 读访问，逐个测试 offset list 中的候选偏移 d_i，检查 `(X - d_i)` 是否命中 RR Table，命中则 +1 分。Learning Phase 在以下条件结束：
   - 任一 offset 得分达到 `SCOREMAX`（高置信度）
   - 达到 `ROUNDMAX` 轮（默认 50 轮）
2. **Prefetch Phase**：使用 Learning Phase 选出的 best offset 发出预取。若 best score 低于 `badScore`（默认 2），则禁用该 BOP。

#### 1.4.2 Recent Request Table（RR Table）

RR Table 是一个 direct-mapped SRAM，通过哈希函数访问。地址分解为：

```
paddr: | ...... | 8-bit hash2 | 8-bit hash1 | 6-bit cache offset |
                         ↓                ↓
idx = hash1 ⊕ hash2     tag = lineAddr[rrTagBits+rrIdxBits-1 : rrIdxBits]
```

- 读操作 3 级流水：s0 发请求，s1 读出结果，s2 返回 hit/miss
- 读端口为 single-port，写操作与读互斥（`assert(!RegNext(io.w.fire && io.r.req.fire))`）

#### 1.4.3 Offset Score Table

Score Table 维护 `offsetList.size` 个候选偏移的得分，通过有限状态机驱动：

- **s_idle**：清零所有得分，更新 `prefetchOffset` 为上一轮最佳偏移
- **s_learn**：每次 `test.req.fire` 时推进 ptr，若 RR Table 命中则 +1 分

#### 1.4.4 两种 BOP 变体

- **PBestOffsetPrefetch（PBOP）**：Physical BOP，直接使用物理地址训练，跨页时不预取（`crossPage` 检测）
- **VBestOffsetPrefetch（VBOP）**：Virtual BOP，使用虚拟地址训练，需通过 TLB 翻译

VBOP 内部使用 `PrefetchReqBuffer`（BOP 请求缓冲），将虚拟地址请求送入 TLB 翻译，翻译成功后转换为 `PrefetchReq` 输出。该缓冲支持：
- TLB miss replay 机制（`replayCnt` / `replayEn`）
- 异常过滤（page fault、PMP、MMIO、UNCACHE）
- 重复请求过滤（相同 vaddr + needT + source 合并）

#### 1.4.5 Delay Queue

BOP 中的 `DelayQueue` 延迟将训练地址写入 RR Table 的时机，确保 RR Table 记录的是"过去"的地址而非当前访问。延迟默认 `dQLatency = 300` 周期，可由 CSR 动态调整。队列满时丢弃新请求。

### 1.5 Temporal Prefetcher（TP）

TP 基于 MICROI 2019 论文（"Temporal Prefetching Without the Off-Chip Metadata"）和 Triangel 2024 论文实现，属于 **VIVT（Virtually Indexed, Virtually Tagged）** 元数据表、物理数据的时序预取器。

核心组件：
- **tpMetaTable**：SRAM，16384 条目 × 16-way，存储 trigger tag
- **tpDataQueue**：深度 8 的队列，暂存从外部存储读回的预取序列数据
- **triggerQueue**：深度 4，记录待触发的元数据写请求
- **dataReadQueue / dataWriteQueue**：异步读写队列，连接 `tpmeta_port`

工作流程（3 级流水）：
1. **s0**：用训练地址的 set index 查询 tpMetaTable
2. **s1**：判断 hit/miss，选择替换 way
3. **s2**：若 hit，发送数据读请求；若 miss，将当前触发器入队等待记录

**录制逻辑**：当 `dorecord_s2` 有效时，将训练地址依次写入 `recorder_data`，达到 `recordThres` 后触发写回 tpMetaTable 并通过 `dataWriteQueue` 写入外部存储。

**发送逻辑**：从 `tpDataQueue` 读取预取序列，逐地址发出 `PrefetchReq`，使用 `tpThrottleCycles`（默认 4）控制发送间隔。

**Constantin 参数**：
- `tp_enable`：总开关
- `tp_throttleCycles`：发送节流周期
- `tp_hitAsTrigger`：meta hit 时是否作为触发器
- `tp_triggerThres` / `tp_recordThres`：队列深度和记录阈值
- `tp_trainOnVaddr` / `tp_trainOnL1PF`：训练过滤条件

### 1.6 Next-Line Prefetcher（NL）

NL 是一种基于 PC 的两级采样预测预取器，包含：

- **NextLineSample**：Sample Table（4-way set-associative），记录块地址、PC hash、采样时间戳和 touched 标志
- **NextLinePattern**：Pattern Table（64-way，1 set），使用饱和计数器（2-bit SAT）预测 next-line 是否有效

**工作流程**：
1. 每 `timeSampleRate`（默认 256）次训练时，在 Sample Table 中插入当前地址
2. 对前一地址（`blockAddr - 1`）在 Sample Table 中查找，若命中且时间戳在范围内，标记 `touched = true`，输出到 Pattern Table 训练
3. Pattern Table 收到训练信号后，增加对应 PC hash 的饱和计数器；当计数器达到 `ptMaxSat`（3）时，后续查询该 PC 将发出预取

Prefetch Queue 使用 `OverwriteQueue`（深度 8）缓冲预取地址，最终生成 `PrefetchReq` 输出。

### 1.7 PrefetchReceiver

`PrefetchReceiver` 是一个简单的地址转发模块，接收来自上层（L1 或其他组件）的预取地址（`recv_addr`），直接转换为 `PrefetchReq` 发出，不需要训练过程。用于 SMS、Stream、Stride、Berti 等不在 L2 内部实现的预取算法。

### 1.8 PrefetchIO 接口

```scala
class PrefetchIO {
  val train    : DecoupledIO[PrefetchTrain]  // L2 给 Prefetcher 的训练信号
  val tlb_req  : L2ToL1TlbIO                 // TLB 请求（VBOP 使用）
  val req      : DecoupledIO[PrefetchReq]    // Prefetcher 发出的预取请求
  val resp     : DecoupledIO[PrefetchResp]   // L2 返回的预取响应
  val recv_addr: ValidIO                      // 外部预取地址接收
}
```

---

## 2. Probe Queue 处理机制

Probe（探测）是 MOESI/MESI 协议中，上级 cache 对下级 cache 行状态的查询操作。在 XiangShan CoupledL2 中，Probe 请求通过 **SourceB** 生成，并通过 **MSHR** 中的 Probe 状态机处理。

### 2.1 Probe 在 MSHR 中的状态跟踪

当 L2 MainPipe 处理 A channel Acquire 请求需要替换某一行时，若该行状态为 TRUNK 或 TIP（即可能有下级 cache 持有该行），需要向上级 cache 发出 Probe。该 Probe 状态通过 MSHR 的以下字段跟踪：

- `w_probeack`：等待 ProbeAck 的掩码，每一位对应一个可能持有该行的下级 cache
- `w_probeacklast`：等待最后一个 ProbeAck
- `w_refill`：等待 refil 数据

Probe 的优先级链（MSHR 请求输出优先级）：
```
Release (w_release) → GrantAck (w_grantack) → ProbeAck (w_probeacklast) → 重试/MSHR请求
```

### 2.2 Probe 的来源

Probe 在以下情况下被触发：
1. **A channel Acquire with needRelease**：MainPipe 发现目标行需要先让下级 cache 释放
2. **Replacement**：当某个 way 被替换时，需要 Probe 持有该行的下级 cache
3. **B channel Probe 响应**：上级 cache 对 L2 的 Probe

### 2.3 ProbeAck 处理

ProbeAck 通过 C channel 返回（`SinkC.scala`），被识别为非 Release 类型的 C channel 消息（`isRelease = io.c.bits.opcode(1)` 为 false）。SinkC 处理 ProbeAck 的流程：

1. 检查 `first` 或 `last` beat
2. 生成 `RespBundle` 传递给 MainPipe
3. 若为 `ProbeAckData`，将数据写入 `releaseBufWrite`

---

## 3. Source B（Snoop 生成）

`SourceB` 模块位于 `XSCache/src/main/scala/coupledL2/SourceB.scala`，负责将内部的 Probe 请求转换为 TileLink B channel 消息发送给 L1 DCache。

### 3.1 数据结构

```scala
class SourceBReq extends L2Bundle {
  val tag    : UInt  // cache 行 tag
  val set    : UInt  // cache 行 set index
  val off    : UInt  // block offset
  val opcode : UInt  // B channel 操作码
  val param  : UInt  // 权限参数
  val alias  : Option[UInt]  // 可选的别名位
}
```

### 3.2 内部结构

SourceB 维护一个 **4 深度的 Probe 缓冲区**（`probes`），每个条目为 `ProbeEntry`：

```scala
class ProbeEntry extends L2Bundle {
  val valid : Bool           // 条目有效
  val rdy   : Bool           // 条目就绪（可发送）
  val waitG : UInt(sourceIdBits)  // 等待的 Grant 对应的 sourceId
  val task  : SourceBReq     // Probe 任务
}
```

### 3.3 关键机制：与 Grant 的冲突避免

SourceB 的核心设计要点：**当存在同地址 Grant 且尚未收到 GrantAck 时，必须阻止 Probe 的发送**。

这是因为：
1. Grant 发送后，L1 会获得该行的副本
2. 若此时立即发出 Probe，会导致 L1 释放刚获得的行，违反协议语义
3. 因此 SourceB 通过查询 `grantStatus`（来自 `GrantBuffer`）判断是否有冲突

**分配流程**：
- 入队时检查 `conflictMask`：当前所有 in-flight Grant 中，是否有同地址（set + tag 相同）的条目
- 若冲突，`p.rdy := false`，`p.waitG` 记录冲突 Grant 的 sourceId
- 当对应 Grant 收到 GrantAck（`io.grantStatus(waitG).valid` 为 false）时，`p.rdy` 变为 true（延迟 1 周期，因为 GrantData 有 2 beats）

**发送流程**：
- 使用 `TwoLevelRRArbiter` 从 4 个条目中轮询选择就绪条目
- 将 `SourceBReq` 转换为 `TLBundleB`：source 设为 dcache 的 sourceId 起始值

### 3.4 TLBundleB 构造

```scala
def toTLBundleB(task: SourceBReq) = {
  b.opcode  := task.opcode
  b.param   := task.param
  b.size    := offsetBits.U     // block 大小
  b.source  := dcacheSourceIdStart  // dcache 的 source 起始 ID
  b.address := Cat(task.tag, task.set, 0.U(offsetBits.W))
  b.mask    := Fill(beatBytes, 1.U(1.W))
  b.data    := Cat(task.alias.getOrElse(0.U), 0.U(1.W))
}
```

---

## 4. Sink C（Release/ProbeAck 处理）

`SinkC` 位于 `XSCache/src/main/scala/coupledL2/SinkC.scala`，接收来自下级 cache（L1）的 C channel 消息，处理两种类型：

### 4.1 Release/ReleaseData

- **用途**：下级 cache 主动释放行，可能携带脏数据
- **处理**：
  1. 将 TLBundleC 转换为内部 `TaskBundle`
  2. 存入 `taskBuf`（`bufBlocks` 个缓冲区）
  3. 数据（若带数据）存入 `dataBuf`
  4. 通过 `taskArb`（TwoLevelRRArbiter）仲裁后发送给 `RequestArb`

### 4.2 ProbeAck/ProbeAckData（探测响应）

- **用途**：响应 L2 发出的 Probe
- **处理**：
  1. 生成 `RespBundle` 输出，包含 opcode、param、dirty、denied、corrupt 等信息
  2. 若为 `ProbeAckData`，将数据写入 `releaseBufWrite`（2 beats 拼接为完整 block）

### 4.3 数据缓冲机制

SinkC 使用双缓冲结构：

```scala
val dataBuf  : Vec[Vec[UInt]]  // bufBlocks × beatSize × beatBytes*8 bits
val taskBuf  : Vec[TaskBundle]  // bufBlocks 个任务缓冲
val beatValids : Vec[Vec[Bool]] // beat 有效位
```

- **flow 控制**：`io.c.ready := !isRelease || !first || !full`
- 当 `first` beat 到来且缓冲区满时，阻塞 C channel 接收
- Release 的两个 beats 通过 `nextPtrReg` 关联（first beat 记录的缓冲区指针）

### 4.4 Nested C-Release 处理

当 C channel Release 携带新数据，且此时有 MSHR 正在进行 refil 时（`newdataMask.orR`），需要将 Release 新数据写入 `refillBufWrite`，避免数据覆盖问题：

```scala
io.refillBufWrite.valid := RegNext(io.task.fire && io.task.bits.opcode === ReleaseData && newdataMask.orR)
io.refillBufWrite.bits.data.data := dataBuf(RegNext(io.task.bits.bufIdx)).asUInt
```

---

## 5. Sink D（Grant 数据处理）

在 XiangShan 的 CoupledL2 中，**Sink D 的功能由 `GrantBuffer`** 承担，位于 `XSCache/src/main/scala/coupledL2/GrantBuffer.scala`，而非独立的 `SinkD.scala` 文件。GrantBuffer 是 L2 与 L1 DCache 之间 D channel 和 E channel 的核心接口。

### 5.1 核心功能

GrantBuffer 承担以下四大职责：

1. **接收 MainPipe 输出**：通过 `io.d_task` 接收带数据的任务
2. **发送 D channel 消息**：将 Grant/GrantData/ReleaseAck/AccessAckData 发送给 L1
3. **接收 E channel GrantAck**：记录并释放 inflight Grant 状态
4. **阻塞 MainPipe 入口**：防止 GrantBuffer 溢出

### 5.2 内部结构

```scala
val grantQueue : Queue[GrantQueueTask]   // 任务队列，深度 mshrsAll
val grantQueueData0/1 : Queue[GrantQueueData]  // 双数据队列（2 beats 分开存储）
val grantBuf   : Reg                     // 暂存第二 beat 的缓冲寄存器
val inflightGrant : Vec[Valid[InflightGrantEntry]]  // 记录已发 Grant 但未收 GrantAck 的条目
```

### 5.3 两拍 GrantData 发送

对于 GrantData（带数据的响应），需要发送 2 beats：

1. **dequeue**：从 grantQueue 取出任务和 2 beats 数据
2. **First Beat**：直接通过 `io.d` 发送 `deqData(0)`（或根据 isKeyword 选择）
3. **Second Beat**：存入 `grantBuf`，下一周期发送
4. **关键字优先处理**：当 `isKeyword` 标志有效时，first beat 发送 keyword beat（beat 0），second beat 发送 beat 1，优化 L1 流水线

### 5.4 Inflight Grant 追踪

- Grant 发送时，在 `inflightGrant` 中分配条目，记录 set 和 tag
- 收到 GrantAck（`io.e.fire`）时，清除对应条目
- `grantStatus` 输出提供给 SourceB 用于冲突检测

### 5.5 Prefetch Response 生成

GrantBuffer 还负责生成预取响应（`PrefetchResp`）：

```scala
pftRespQueue.io.enq.valid := io.d_task.valid && dtaskOpcode === HintAck &&
  io.d_task.bits.task.fromL2pft.getOrElse(false.B)
```

当 MainPipe 返回 HintAck（预取请求响应）时，将 tag、set、vaddr、pfSource 信息送入 `pftRespQueue`，转发给 `Prefetcher.io.resp`。

### 5.6 反压与阻塞

GrantBuffer 通过以下机制防止溢出：

- **blockSinkReqEntrance.blockA_s1**：A channel 入口被阻塞（in-flight Grant + pipeline 使用数 >= mshrsAll）
- **blockSinkReqEntrance.blockB_s1**：B channel 入口被阻塞（inflightGrant 中有同地址条目）
- **blockMSHRReqEntrance**：MSHR 请求入口被阻塞

---

## 6. Request Buffer 与仲裁

`RequestBuffer` 位于 `XSCache/src/main/scala/coupledL2/RequestBuffer.scala`，是 MainPipe 入口前的关键缓冲和仲裁模块，确保请求在合适的时机进入 MainPipe，避免冲突和死锁。

### 6.1 核心设计目标

RequestBuffer 的设计目标是：
1. **地址冲突过滤**：确保同一 MSHR 地址的请求按序处理
2. **MainPipe 阻塞感知**：感知 MainPipe 各阶段的占用情况
3. **Prefetch 重复消除**：移除与 in-flight MSHR 重复的 Prefetch 请求
4. **A-Task 合并**：将新的 Acquire 与已有的 Prefetch MSHR 合并

### 6.2 内部结构

```scala
class ReqEntry extends L2Bundle {
  val valid : Bool
  val rdy   : Bool
  val task  : TaskBundle
  val waitMP : UInt(4.W)  // 等待 MainPipe 的掩码 [3]:s1 [2]:s2 [1]:s3 [0]:release
  val waitMS : UInt(mshrsAll.W)  // 等待 MSHR 释放的掩码
}
```

- `buffer`：深度为 `entries`（默认 4）的 `ReqEntry` 数组
- `chosenQ`：深度 1 的 Queue，暂存已仲裁通过但尚未发送的任务
- `issueArb`：TwoLevelRRArbiter，从 buffer 中选择就绪条目

### 6.3 地址冲突检测

```scala
def addrConflict(a: TaskBundle, s: MSHRInfo): Bool = {
  a.set === s.set && (a.tag === s.reqTag || a.tag === s.metaTag && s.needRelease)
}
def conflictMask(a: TaskBundle): UInt = VecInit(io.mshrInfo.map(s =>
  s.valid && addrConflict(a, s.bits) && !s.bits.willFree)).asUInt
```

冲突条件：同 set 且（同 reqTag 或同 metaTag 且需要 release）。

### 6.4 条目就绪条件

条目就绪需要同时满足：
1. **无 MSHR 地址冲突**：`!conflict(in)`
2. **MainPipe 无阻塞**：`!mpBlock`
3. **无同 set 冲突**：`!s1Block`（s1 刚发送了同 set 请求或有 s1 entrance）
4. **同 set 有空闲 way**：`!noFreeWay(in)`（s2 + s3 + MSHR 中同 set 请求数 < ways）

### 6.5 Flow 优化

当满足以下条件时，新请求可以直接 flow through（绕过 buffer）：
```scala
val canFlow = flow.B && !full && !conflict(in) && !chosenQValid && 
              !Cat(io.mainPipeBlock).orR && !noFreeWay(in)
```

### 6.6 Prefetch 重复消除

```scala
val dupMask = VecInit(
  io.mshrInfo.map(s => s.valid && s.bits.isAcqOrPrefetch && sameAddr(in, s.bits)) ++
  buffer.map(e => e.valid && sameAddr(in, e.task))
)
val dup = isPrefetch && dupMask.asUInt.orR
```

当 Prefetch 请求（Hint opcode）与 in-flight MSHR 或 buffer 中已有请求地址相同时，直接丢弃。

### 6.7 A-Task 合并（mergeA）

当新的 Acquire 请求与已有的 Prefetch MSHR 地址相同，且该 MSHR 的 dirHit 为 false、仍在进行中时，可以将 Acquire 合并到该 MSHR：

```scala
val mergeAMask = VecInit(io.mshrInfo.map { case s =>
  s.valid && s.bits.isPrefetch && sameAddr(in, s.bits) && !s.bits.dirHit && mshrInflight &&
    in.fromA && (in.opcode === AcquireBlock || in.opcode === AcquirePerm) && !s.bits.mergeA
}).asUInt
```

### 6.8 动态等待更新

`waitMS` 和 `waitMP` 每周期动态更新：
- `waitMP` 右移一位，实现倒计时
- `waitMS` 中，当 MSHR `willFree` 时清除对应位
- 当 `waitMP` 倒计时结束时，重新计算 `waitMS`（考虑新分配的 MSHR）

---

## 7. Debug 工具

### 7.1 Monitor 模块

`Monitor` 位于 `XSCache/src/main/scala/coupledL2/debug/Monitor.scala`，用于 MainPipe 的运行时监测。

**IO 接口**：
```scala
class MainpipeMoni extends L2Bundle {
  val task_s2      : ValidIO[TaskBundle]
  val task_s3      : ValidIO[TaskBundle]
  val task_s4      : ValidIO[TaskBundle]
  val task_s5      : ValidIO[TaskBundle]
  val dirResult_s3 : DirResult
  val allocMSHR_s3 : ValidIO[UInt]
  val metaW_s3     : ValidIO[MetaWrite]
}
```

**断言检查**：
1. **Trunk 状态一致性**：若 s3 阶段 meta state 为 TRUNK 但无任何 client hit，则断言失败
   ```scala
   assert(RegNext(!(s3_valid && !mshr_req_s3 && dirResult_s3.hit &&
     meta_s3.state === TRUNK && !meta_s3.clients.orR)))
   ```
2. **无效 Client 不发 Release**：若 s3 阶段 C channel 任务命中但无 client 持有，则断言失败
   ```scala
   assert(RegNext(!(s3_valid && req_s3.fromC && dirResult_s3.hit &&
     !meta_s3.clients.orR)))
   ```

**ChiselDB 日志记录**：
当 `enableMonitor` 启用且非 FPGA 平台时，记录以下信息到 `L2MP` 表：

```scala
class CPL2S3Info extends L2Bundle {
  val mshrTask   : Bool
  val channel    : UInt(3.W)
  val opcode     : UInt(3.W)
  val tag        : UInt(tagBits.W)
  val sset       : UInt(setBits.W)
  val dirHit     : Bool
  val dirWay     : UInt(wayBits.W)
  val allocValid : Bool
  val allocPtr   : UInt(mshrBits.W)
  val mshrId     : UInt(mshrBits.W)
  val metaWvalid : Bool
  val metaWway   : UInt(wayBits.W)
}
```

日志以 `s3_valid` 为使能，每个有效 s3 期记录一条，便于离线分析 MainPipe 行为。

### 7.2 各模块性能计数器

各模块广泛使用 `XSPerfAccumulate` 收集性能数据：

**Prefetcher**：
- `prefetch_train_valid`：训练信号有效次数
- `prefetch_req_fromL1/VBOP/PBOP/TP/NL`：各来源请求产生次数
- `prefetch_req_select*`：各来源请求被仲裁选中次数
- `prefetch_req_SMS_other_overlapped`：SMS 与其他源重叠次数

**BOP**：
- `best_offset_pos/neg_*`：各偏移被选为最佳偏移的次数
- `bop_req/bop_train/bop_resp`：请求/训练/响应次数
- `bop_train_stall_for_st_not_ready`：Score Table 未就绪导致的训练阻塞
- `bop_cross_page`：跨页导致的预取取消
- `bop_drop_for_disable`：因禁用导致的预取丢弃

**NextLine**：
- `nlSampleTrainTimes`：采样训练次数
- `nlPatternTrainTimes`：Pattern 训练次数
- `nlPrefetchReqTimes`：预取请求产生次数
- `nlTransmitPrefetchReqTimes`：请求实际发送次数

**SinkC**：
- `sinkC_c_stall`：C channel 阻塞次数
- `sinkC_buf_full`：缓冲区满次数
- `NewDataNestC`：Nested C-Release 新数据写入次数

**GrantBuffer**：
- `grant_grantack_period`：Grant 到 GrantAck 的延迟直方图
- `max_grant_grantack_period`：最大延迟
- `pftRespQueue_about_to_full`：预取响应队列即将满

**RequestBuffer**：
- `drop_prefetch`：Prefetch 重复丢弃次数
- `req_buffer_flow`：Flow through 次数
- `req_buffer_mergeA`：A-Task 合并次数
- `reqBuf_timer`：请求在 Buffer 中停留时间直方图
- `max_reqBuf_timer`：最大停留时间

---

## 8. 源文件位置汇总

| 模块 | 文件路径 |
|------|----------|
| Prefetcher 顶层 | `XSCache/src/main/scala/coupledL2/prefetch/Prefetcher.scala` |
| PrefetchParameters | `XSCache/src/main/scala/coupledL2/prefetch/PrefetchParameters.scala` |
| Best-Offset Prefetcher | `XSCache/src/main/scala/coupledL2/prefetch/BestOffsetPrefetch.scala` |
| Next-Line Prefetcher | `XSCache/src/main/scala/coupledL2/prefetch/NextLinePrefetch.scala` |
| Temporal Prefetcher | `XSCache/src/main/scala/coupledL2/prefetch/TemporalPrefetch.scala` |
| PrefetchReceiver | `XSCache/src/main/scala/coupledL2/prefetch/PrefetchReceiver.scala` |
| SourceB（Snoop 生成） | `XSCache/src/main/scala/coupledL2/SourceB.scala` |
| SinkC（Release/ProbeAck） | `XSCache/src/main/scala/coupledL2/SinkC.scala` |
| GrantBuffer（Sink D） | `XSCache/src/main/scala/coupledL2/GrantBuffer.scala` |
| RequestBuffer | `XSCache/src/main/scala/coupledL2/RequestBuffer.scala` |
| Monitor（Debug） | `XSCache/src/main/scala/coupledL2/debug/Monitor.scala` |
| SourceBReq 定义 | `XSCache/src/main/scala/coupledL2/Common.scala`（第 355 行） |
| ProbeEntry 定义 | `XSCache/src/main/scala/coupledL2/SourceB.scala`（第 34 行） |
| GrantStatus 定义 | `XSCache/src/main/scala/coupledL2/SourceB.scala`（第 28 行） |
| MSHR（Probe 状态机） | `XSCache/src/main/scala/coupledL2/MSHR.scala` |

**注**：XiangShan 中没有独立的 `Probe.scala` 和 `SinkD.scala` 文件。Probe 的处理逻辑分散在 `SourceB.scala`（Probe 生成与发送）和 `MSHR.scala`（Probe 状态跟踪）中；Sink D 的功能由 `GrantBuffer.scala` 承担。

---

## 附录：关键设计模式总结

1. **Constantin 动态参数**：所有关键阈值（延迟、使能、深度等）都通过 Constantin 寄存器实现运行时可调，便于调试和优化
2. **ChiselDB 追踪**：关键路径通过 ChiselDB 记录详细运行时信息，支持离线分析
3. **OverwriteQueue 策略**：Prefetch Queue 和 NL Queue 使用可覆盖写队列，确保新数据能替换旧数据，避免队列阻塞
4. **多级流水与反压**：所有模块均采用 Decoupled（ready/valid）接口，支持完整的反压机制
5. **Prefetch 优先级**：Rcv > NL > VBOP > PBOP > TP 的固定优先级，确保外部预取源具有最高优先权
