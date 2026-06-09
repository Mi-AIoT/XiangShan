# R13 - Load/Store Queue 与 Memory Pipeline 深度研究报告

## 1. 概述

XiangShan 的 Load/Store Queue (LSQ) 与 Memory Pipeline 是处理器访存子系统的核心组成部分，负责管理所有 Load/Store 指令的生命周期，包括从 Dispatch（发射）到 Commit（提交）的全过程。该系统实现了一套高度模块化、乱序执行且符合 RISC-V Weak Memory Ordering (RVWMO) 一致性模型的访存流水线。

核心设计特点：
- **Load Queue 模块化拆分**：分为 5 个独立子模块，各司其职
- **Store Queue 统一管理**：通过 ForwardModule、DeqModule、EnterSbufferQueue 实现 Store-to-Load Forwarding 和写入 SBuffer
- **多级 Store-to-Load Forwarding**：支持 SQ、SBuffer、UncacheBuffer、MSHR、TileLink-D 共 5 个 Forward 源
- **Fast Replay 机制**：S3 阶段可直接回退至 S0，减少 replay 延迟
- **RVWMO 一致性保障**：通过 RAR/RAW 双违规检测和 Nuke 机制确保弱内存序正确性

## 2. 内存流水线架构

### 2.1 Load 流水线 (NewLoadUnit)

XiangShan 的 Load 流水线共 5 级 (S0-S4)，采用深度流水化设计：

```
+--------+     +--------+     +--------+     +--------+     +--------+
|  S0    | --> |  S1    | --> |  S2    | --> |  S3    | --> |  S4    |
| Source |     | TLB    |     | Forward|     | Write  |     | Unalign|
| Arbit  |     | & Nuke |     | Resp   |     | Back   |     | Merge  |
+--------+     +--------+     +--------+     +--------+     +--------+
    |                                                    |           |
    |  <-- Fast Replay (S3 -> S0)                        |           |
    |  <-- unalignTail (S1 -> S0)                        |           |
    +----------------------------------------------------+           |
                                                                  SBuffer
```

**S0 (LoadUnitS0)** - 源仲裁与请求发送：

8 个请求源按优先级仲裁：
```
优先级从高到低:
0. unalignTail        - 非对齐访问的尾部注入（来自 S1）
1. replayHiPrio       - 高优先级 Replay（NC/MMIO）
2. fastReplay         - 快速 Replay（来自 S3）
3. replayLoPrio       - 低优先级 Replay
4. prefetchHiConf     - 高置信度预取
5. vectorIssue        - 向量 Load（来自 VSplit）
6. scalarIssue        - 标量 Load（来自 Issue Queue）
7. prefetchLoConf     - 低置信度预取
```

S0 同时发送 TLB 请求、DCache 请求，以及 Store Forwarding 请求（SQ/SBuffer、UncacheBuffer、MSHR、TileLink-D）。

**S1 (LoadUnitS1)** - TLB 响应与 Nuke 检测：

- 接收 TLB 响应，获取物理地址
- 进行 Nuke 检测（与 StoreUnit 进行地址匹配）
- 准备 Store Forwarding 请求
- 处理非对齐访问尾部注入
- 发送 Software Instruction Prefetch
- 检测 TLB miss / Page Fault / Access Fault / Guest Page Fault

**S2 (LoadUnitS2)** - Forwarding 响应处理：

- 处理 STLF (Store-to-Load Forwarding) 响应
- 分类出口路径：
  - `troubleMaker`：需要 replay 的情况（TLB miss、DCache miss 等）
  - `alwaysWriteback`：必须写回的情况（异常、MMIO 等）
  - `prefetch`：预取请求
- 编码 Replay 原因
- 检测 DCache bank conflict

**S3 (LoadUnitS3)** - 写回与 Fast Replay：

- **Fast Replay**：当 S3 检测到可以快速重试的情况时，直接将请求回送至 S0，无需经过完整 Replay 路径
- RAR/RAW 撤销（当 load 被 kill 时，释放 RAR/RAW 队列中的对应条目）
- 写回 Backend（写入 ROB）
- 写入 Load Queue（VirtualLoadQueue 标记完成）
- 生成 Rollback 信号

**S4 (LoadUnitS4)** - 非对齐合并：

- 处理跨 16 字节边界的非对齐 Load
- 将 S3 输出的 head 和 tail 部分拼接为完整数据
- 输出最终结果至 SBuffer 和 Backend

### 2.2 Store 流水线 (NewStoreUnit)

Store 流水线同样为 5 级 (S0-S4)：

```
+--------+     +--------+     +--------+     +--------+     +--------+
|  S0    | --> |  S1    | --> |  S2    | --> |  S3    | --> |  S4    |
| Source |     | TLB    |     | PMP/   |     | Data   |     | Write  |
| Arbit  |     | & Addr |     | PMA    |     | Merge  |     | Back   |
+--------+     +--------+     +--------+     +--------+     +--------+
```

**S0 (StoreUnitS0)** - 4 源优先级仲裁：
```
优先级从高到低:
0. unalignTail    - 非对齐访问尾部注入
1. vectorIssue    - 向量 Store
2. scalarIssue    - 标量 Store
3. prefetchReq    - Store 预取
```

**S1 (StoreUnitS1)** - TLB 响应与地址写入：

- TLB 响应处理
- Nuke 查询（与 LoadUnit 进行地址匹配，检测 Store-Load 冲突）
- Store 地址写入 Store Queue (SQ)
- 非对齐 Tail 注入
- LFST (Load Failure Status Table) 更新，用于 Memory Dependence Prediction

**S2 (StoreUnitS2)** - PMP/PMA 检查：

- PMP (Physical Memory Protection) 检查
- PMA (Physical Memory Attributes) 检查
- 异常处理
- `storeAddrInRe` 写入 SQ（用于 RAW 违规检测）

### 2.3 流水线定义

所有流水线阶段在 `package.scala` 中统一定义：
```scala
object LoadStage extends Enumeration {
  val s0, s1, s2, s3, s4 = Value
}

object StoreStage extends Enumeration {
  val s0, s1, s2, s3, s4 = Value
}
```

## 3. Load Queue (LQ) 架构

XiangShan 的 Load Queue 被拆分为 5 个独立子模块，各自处理不同的功能职责：

```
                          +-------------------+
                          |    LoadQueue      |
                          |   (Top Module)    |
                          +-------------------+
                                 |
        +----------+------+------+------+----------+
        |          |      |      |      |          |
   +----+----+ +---+--+ +--+---+ +--+---+ +----+----+
   |Virtual  | |Load  | |Load  | |Load  | |Load     |
   |Load     | |Queue | |Queue | |Queue | |Queue    |
   |Queue    | |RAR   | |RAW   | |Replay| |Uncache  |
   +---------+ +------+ +------+ +------+ +---------+
    Control     RAR      RAW      Replay    MMIO/NC
    State       Viol.    Viol.    Mgmt      Buffer
```

### 3.1 VirtualLoadQueue

**文件**: `src/main/scala/xiangshan/mem/lsqueue/VirtualLoadQueue.scala`

VirtualLoadQueue 是 Load Queue 的控制状态队列，存储每条 load 指令的基本控制信息：

```
条目格式:
+-----------+---------+--------+--------+-----------+
| Allocated | robIdx  | uopIdx | isvec  | committed |
+-----------+---------+--------+--------+-----------+
```

- **Allocated**: 条目是否已分配
- **robIdx**: ROB 索引，用于 commit 顺序判断
- **uopIdx**: 微操作索引，支持向量操作
- **isvec**: 是否为向量 load 操作
- **committed**: 是否已提交

关键功能：
- **入队控制**: 从 Dispatch 接收新 load 指令
- **出队逻辑**: 以 `CommitWidth` 步长进行 dequeue
- **取消逻辑**: 支持 `redirectCancelCount` 进行 branch mispredict 恢复
- **向量 Commit**: 通过 `vecCommit` 接口支持向量 load 的按元素提交

### 3.2 LoadQueueRAR (Read-After-Read)

**文件**: `src/main/scala/xiangshan/mem/lsqueue/LoadQueueRAR.scala`

RAR 违规检测队列用于检测 load-load 之间的顺序违规。在 RVWMO 模型中，如果一个较早的 load 读取的 cacheline 被 cache 释放（release），则较晚的 load 需要重新执行。

**地址压缩机制**：

使用 XOR-based 压缩将物理地址压缩为 16-bit 用于 CAM 匹配：

```
PartialPAddrWidth = 16 bits
PartialPAddrStride = 6 bits
PartialPAddrLowBits = 5 bits (通过 XOR 映射)
PartialPAddrHighBits = 11 bits (通过 XOR 映射)
```

低 5 位通过 XOR 从物理地址的不同位提取，高位同样通过多路 XOR 生成，避免直接存储完整物理地址。这种设计大幅减少了 CAM 比较所需的存储空间。

**Release 跟踪**：

当 DCache 发出 Release 信号（cacheline 被替换），RAR 队列检查是否有正在等待的 load 依赖于该 cacheline。如果检测到依赖，则标记对应条目为违规，触发后续的 Nuke 处理。

**FreeList 分配**：

使用 FreeList 管理空闲条目的分配，支持多端口同时分配。

### 3.3 LoadQueueRAW (Read-After-Write)

**文件**: `src/main/scala/xiangshan/mem/lsqueue/LoadQueueRAW.scala`

RAW 违规检测队列用于检测 load-store 之间的数据依赖违规。当一个 store 的地址在 load 之后计算出来，且存在 RAW 依赖时，需要 rollback 重新执行。

**条目格式**：
```
+-------+--------+-------+-------+-----------+
| Valid |  uop   | PAddr | Mask  | Datavalid |
+-------+--------+-------+-------+-----------+
```

**MDP (Memory Dependence Prediction) 训练**：

RAW 队列同时作为 MDP 的训练输入。当检测到 RAW 违规时，通过 `mdpTrain` 接口向 MDP 模块报告，用于更新 StoreSet 和 LFST 表，优化后续 load 的调度。

**入队条件**：

当 load 在 Issue Queue 中发现前面有一个 store 尚未计算地址时，分配 RAW 条目。Store 地址到达后，通过 CAM 匹配检测是否存在 RAW 违规。

**LqPAddrModule 与 LqMaskModule**：

使用专用的物理地址匹配模块和掩码匹配模块进行并行比较：
- `LqPAddrModule`: 比较物理地址是否命中
- `LqMaskModule`: 检测数据掩码是否有重叠

### 3.4 LoadQueueReplay

**文件**: `src/main/scala/xiangshan/mem/lsqueue/LoadQueueReplay.scala`

Replay 管理队列负责管理所有需要重新执行的 load 请求，支持 13 种 Replay 原因：

```
优先级从高到低:
 C_UNCACHE (0)  - Uncache 访问需要等待
 C_SMF     (1)  - Store Queue 多重 Forward 无效
 C_MA      (2)  - Store-Load 违规重执行检查
 C_TM      (3)  - TLB Miss 检查
 C_FF      (4)  - Store-to-Load Forwarding 检查
 C_DR      (5)  - DCache Replay 检查
 C_DM      (6)  - DCache Miss 检查
 C_WF      (7)  - WPU (Way Prediction Unit) 预测失败
 C_BC      (8)  - DCache Bank Conflict / 非对齐尾部分割失败
 C_RAR     (9)  - RAR 队列接受检查
 C_RAW     (10) - RAW 队列接受检查
 C_NK      (11) - Store-Load 违规（Nuke）
 C_MF      (12) - MisalignBuffer 满
```

**AgeDetector 模块**：

使用 NxN 年龄矩阵实现 oldest-first 调度，确保最早需要 replay 的 load 优先获得执行机会：

```scala
class AgeDetector(numEntries: Int, numEnq: Int, regOut: Boolean = true) {
  // age(i)(j): entry i enters queue before entry j
  // 通过年龄矩阵确定最老的待 replay 条目
}
```

**多源唤醒**：

支持多种唤醒信号，减少不必要的 replay 等待：
- `loadWakeup`: DCache 返回数据时唤醒
- `l2_hint`: L2 Cache 预取提示
- `tlb_hint`: TLB 预取提示
- `mmioWakeup`: MMIO 访问完成唤醒
- `ncWakeup`: Non-Cacheable 访问完成唤醒

### 3.5 LoadQueueUncache

**文件**: `src/main/scala/xiangshan/mem/lsqueue/LoadQueueUncache.scala`

处理 MMIO (Memory-Mapped I/O) 和 NC (Non-Cacheable) 访问的专用队列。

**UncacheEntry FSM**：

```
s_idle --> s_req --> s_resp --> s_wakeup --> s_wait
  |          |          |           |          |
  |    发送请求   接收响应   唤醒等待   等待 commit
  |          |          |           |          |
  +<---------+<---------+<----------+<---------+
  (任何状态均可被 redirect flush 回到 s_idle)
```

**MMIO 与 NC 的区别处理**：

- **MMIO**: 必须等待 ROB head 指向该指令后才能执行，保证 MMIO 访问的严格顺序
- **NC**: 支持 Out-of-Order 模式，可以在 ROB head 之前执行，但需确保数据一致性

## 4. Store Queue (SQ) 架构

**文件**: `src/main/scala/xiangshan/mem/lsqueue/NewStoreQueue.scala` (2108 行)

Store Queue 是一个功能复杂的统一管理模块，包含数据存储、控制逻辑、Forwarding 和 Dequeue 功能。

### 4.1 条目格式

**SQDataEntryBundle** - 数据条目：
```
+----------+-------+--------+---------+----------+------+--------+------+
| loadWait | ssid  | store  | robIdx  | uopIdx   | size | vaddr  | data |
| Bit      |       | SetHit |         |          |      |        |      |
+----------+-------+--------+---------+----------+------+--------+------+
+----------+--------+----------+-------+
| byteMask | memory | cboType  | prefe |
|          | Type   |          | tch   |
+----------+--------+----------+-------+
```

**SQCtrlEntryBundle** - 控制条目：
```
+------+------+--------+-----+--------+-------+-----+----------+
|dataV |addrV | wait   | isV |vecInac |cross16|hasE |committed |
|alid  |alid  |StoreS2 | ec  |tive    |Byte   |xcep |          |
+------+------+--------+-----+--------+-------+-----+----------+
+----------+---------+---------+
|allocated|handleFin| isCbo   |
|          |ish      |         |
+----------+---------+---------+
```

### 4.2 六指针系统

Store Queue 使用 6 个指针管理不同阶段的条目：

```
enqPtrExt      -> 新指令入队位置
deqPtrExt      -> Dequeue 位置（进入 EnterSbufferQueue）
rdataPtrExt    -> 读数据位置（ForwardModule 读取）
cmtPtrExt      -> Commit 位置
addrReadyPtrExt -> 地址就绪位置
dataReadyPtrExt -> 数据就绪位置
```

### 4.3 ForwardModule - Store-to-Load Forwarding

ForwardModule 实现 3 级 STLF (Store-to-Load Forwarding) 流水线：

```
+----------+     +----------+     +----------+
| Stage 0  | --> | Stage 1  | --> | Stage 2  |
| (Cycle 0)|     | (Cycle 1)|     | (Cycle 2)|
+----------+     +----------+     +----------+
 Prepare masks    Match stores      Generate
 & address        & select          forwarded
 ranges           youngest          data & mask
```

**Stage 0** - 准备阶段：
- 生成 Load 的 SQ 索引掩码
- 计算 Load 的字节起始/结束偏移

**Stage 1** - 匹配与选择阶段：
- 物理地址/虚拟地址匹配
- 使用 `findYoungest` 二叉树选择器找到最年轻的匹配 Store
- `findYoungest` 实现：递归二分查找，返回 one-hot 选择向量和是否有多重匹配标志

```scala
def findYoungest(in: UInt): (UInt, Bool) = {
  // 递归实现：将输入分为高低两半
  // 优先选择低位（更年轻的条目）
  // 返回 (one-hot 选择向量, 是否多重匹配)
}
```

**Stage 2** - 数据生成阶段：
- 生成最终的 forwarded data 和 mask
- 处理跨字节边界的 partial forwarding
- 检测地址无效（`AddrInvalid`）情况

**5 个 Forwarding 源**：

Load 流水线 S0 同时向 5 个源发送 Forwarding 请求：
1. **SQ (Store Queue)**: 标准 STLF
2. **SBuffer**: 已写入 SBuffer 但尚未提交的 Store 数据
3. **UncacheBuffer**: 正在处理的 MMIO/NC 访问
4. **MSHR**: DCache Miss 时的 Miss Status Holding Register
5. **TileLink-D**: 来自 L2 Cache 的 snoop 数据

### 4.4 DeqModule - 出队管理

DeqModule 处理 SQ 到 SBuffer 的数据传输，支持多种出口类型：

**状态机**：
```
UncacheState:
  idle -> sendReq -> waitReqAck -> waitResp -> writeback

CboState:
  idle -> writeZero -> flushSb -> sendReq -> waitResp -> writeback
```

**Force Write 机制**：

当 SQ 接近满时，通过 `ForceWriteUpper` 和 `ForceWriteLower` 阈值强制将数据写入 SBuffer，防止 SQ 溢出：

```scala
ForceWriteUpper := Constantin.createRecord(initValue = StoreQueueForceWriteSbufferUpper)
ForceWriteLower := Constantin.createRecord(initValue = StoreQueueForceWriteSbufferLower)
```

**数据对齐处理**：

通过 `rotateByteRight` 函数对数据和掩码进行循环右移对齐，支持不同 `vaddr` 偏移的 Store：

```scala
def rotateByteRight(in: UInt, step: Int): UInt = {
  val maxLen = in.getWidth
  Cat(in(step - 1, 0), in(maxLen - 1, step))
}
```

### 4.5 EnterSbufferQueue - 缓冲队列

EnterSbufferQueue 是 SQ 与 SBuffer 之间的流水线缓冲，大小为 `EnsbufferWidth`：

```
+------------------+     +-------------------+     +---------+
| EnterSbufferQueue| --> | (Pipeline Stage)  | --> | SBuffer |
| (EnsbufferWidth) |     |                   |     |         |
+------------------+     +-------------------+     +---------+
```

**关键设计**：
- 支持同周期 enqueue 和 dequeue（允许同时写入和读出）
- `allocated` 状态跟踪每个条目
- 使用 `DataQueuePtr` 进行指针管理
- 入队优先级高于出队（支持同周期条目复用）

### 4.6 UnalignQueue

存储跨 16 字节边界的非对齐 Store 的第二个物理地址：

```scala
class UnalignBufferEntry {
  val paddrHigh: UInt  // 第二个物理地址高位
  val robIdx: RobPtr   // ROB 索引
  val sqIdx: SqPtr     // SQ 索引
}
```

## 5. Store-to-Load Forwarding (STLF) 完整路径

STLF 是性能关键路径，从 Load S0 到数据可用共需 3 个周期：

```
S0: Load 发送 Forwarding 请求
    |
    |--> SQ ForwardModule (3-cycle pipeline)
    |--> SBuffer Forward
    |--> UncacheBuffer Forward
    |--> MSHR Forward
    |--> TileLink-D Forward
    |
S1: 接收 S1 Forwarding 响应（early response）
    |
S2: 处理 Forwarding 最终响应
    |--> 全命中：直接使用 forwarded data
    |--> 部分命中：组合 forwarded + DCache data
    |--> 未命中：使用 DCache data
    |
S3: Fast Replay 判断
    |--> 可快速重试：回送至 S0
    |--> 否：进入 Replay 队列
    |
S4: 非对齐合并（head + tail）
```

**延迟分析**：
- Best case (STLF hit): S0 -> S3 = 4 cycles
- Fast Replay: S3 -> S0 + 4 cycles = 8 cycles
- Full Replay: S3 -> Replay Queue -> S0 -> S3 = 13+ cycles

## 6. 内存一致性模型 (RVWMO)

XiangShan 实现 RISC-V Weak Memory Ordering (RVWMO) 一致性模型，通过以下机制保证：

### 6.1 RAR (Read-After-Read) 违规检测

检测 Load-Load 顺序违规：
- 当 cacheline 被 DCache 释放（Release）时，检查是否有后续 load 依赖该 cacheline
- 如果检测到依赖，触发 Nuke，强制重新执行受影响的 load

### 6.2 RAW (Read-After-Write) 违规检测

检测 Load-Store 数据依赖违规：
- 当 Store 地址计算完成后，与 RAW 队列中的 Load 进行 CAM 匹配
- 如果发现 Load 在 Store 之前发射但存在数据依赖，触发 rollback

### 6.3 Nuke 机制

Nuke 是 XiangShan 处理一致性违规的核心机制：

```
Store Unit S1    Load Unit S1
     |                |
     |   Nuke Query   |
     +------>  <------+
     |                |
     |   Nuke Match   |
     +------>  <------+
     |                |
     | Rollback / Kill|
     +------>  <------+
```

**Nuke 检测时机**：
- Store S1: Store 地址可用时查询是否有 Load 已经使用了该地址
- Load S1: Load 地址可用时查询是否有 Store 写入了该地址

**Nuke 处理流程**：
1. 检测到违规
2. 生成 Kill 信号，取消后续流水线阶段
3. 生成 Rollback 信号，通知 ROB 恢复
4. 触发 Replay，重新执行被取消的指令

### 6.4 Memory Dependence Prediction (MDP)

通过 StoreSet 和 LFST 实现预测：

**StoreSet**：预测哪些 Store-Load 之间存在依赖关系
**LFST (Load Failure Status Table)**：跟踪 load 的失败历史，优化调度

当 RAW 违规被检测到时，通过 `mdpTrain` 接口更新 MDP 预测表，减少后续的不必要 replay。

## 7. 向量 Load/Store 支持

### 7.1 VSplit

**文件**: `src/main/scala/xiangshan/mem/vector/VSplit.scala`

`VSplitPipeline` 将向量 load/store 操作拆分为多个标量微操作：

**S0 解码**：确定 `alignedType`、`numUops`、`flowMask`

**支持的访问模式**：
- Unit-stride：连续内存访问
- Strided：固定步长访问
- Indexed：索引访问
- Whole register：整个寄存器加载
- Mask access：掩码访问

### 7.2 VMergeBuffer

**文件**: `src/main/scala/xiangshan/mem/vector/VMergeBuffer.scala`

`BaseVMergeBuffer` 管理向量操作的结果合并：

```
MBufferBundle:
+------+--------+---------+----------+------+------+
| data | mask   | flowNum | elemIdx  | uop  | ...  |
+------+--------+---------+----------+------+------+
```

提供三个辅助连接函数：
- `EnqConnect`: 入队连接
- `DeqConnect`: 出队连接
- `ToLsqConnect`: 与 LSQ 通信连接

### 7.3 VSegmentUnit

**文件**: `src/main/scala/xiangshan/mem/vector/VSegmentUnit.scala`

处理 Segment Load/Store 操作（结构体数组访问）：

**访问模式**：
- 先访问同一 Segment 的不同字段
- 再访问不同 Segment

这种模式需要特殊的地址计算和数据重组逻辑。

## 8. 关键设计细节

### 8.1 非对齐访问处理

XiangShan 通过 `unalignHead` 和 `unalignTail` 机制处理跨 16 字节边界的非对齐访问：

**Load 非对齐处理**：
- S1 检测到非对齐时，将 Tail 部分注入 S0 重新执行
- S4 将 Head (S3 输出) 和 Tail (S4 输入) 拼接为完整数据

**Store 非对齐处理**：
- S0 检测到非对齐时，将请求拆分为 Head 和 Tail
- 使用 UnalignQueue 存储 Tail 的物理地址信息

### 8.2 CBO (Cache Block Operations) 处理

Store Queue 的 DeqModule 通过专用 FSM 处理 CBO 操作：

```
CBO Types:
- cbo.zero  : Cache Block Zero
- cbo.clean : Cache Block Clean
- cbo.flush : Cache Block Flush
- cbo.inval : Cache Block Invalidate
```

CBO 操作需要先 flush SBuffer（确保所有先前的 Store 数据写入 DCache），然后执行 CBO 命令。

### 8.3 Uncache Buffer 与 SBuffer 的交互

Uncache Buffer 处理 MMIO 和 NC 请求，与 SBuffer 有专门的 bypass 路径：

```
Load S0 --> UncacheBuffer Forward Request
    |
Load S1 <-- UncacheBuffer Forward Response
    |
Load S2 <-- UncacheBuffer Early Response
```

### 8.4 LSQ 入队控制

**文件**: `src/main/scala/xiangshan/mem/lsqueue/LSQWrapper.scala`

`LsqEnqCtrl` 模块管理 LQ 和 SQ 的入队：

```scala
io.enq.canAccept := loadQueue.io.enq.canAccept && storeQueue.io.enq.canAccept
```

只有当 LQ 和 SQ 都有足够空间时，才能接受新的 Dispatch 请求。

## 9. 性能监控与调试

### 9.1 LoadQueueTopDownIO

提供详细的性能分析接口：

- `noUopsIssued`: 无微操作发射周期计数
- `lqRepFull`: Replay 队列满计数
- `lqFull`: Load Queue 满计数

### 9.2 PerfEvents

各模块均集成 `HasPerfEvents` trait，支持性能事件统计：
- Replay 次数统计
- Forwarding 命中/未命中统计
- Queue 使用率统计

### 9.3 Debug 信号

通过 `Option.when(debugEn)` 条件编译包含调试信号：
```scala
val debugPaddr = Option.when(debugEn)(UInt(PAddrBits.W))
val debugVaddr = Option.when(debugEn)(UInt(VAddrBits.W))
val debugData  = Option.when(debugEn)(UInt(XLEN.W))
```

## 10. 关键源文件位置

| 文件 | 功能 |
|------|------|
| `src/main/scala/xiangshan/mem/lsqueue/LSQWrapper.scala` | LSQ 顶层模块，LQ/SQ 例化与仲裁 |
| `src/main/scala/xiangshan/mem/lsqueue/LoadQueue.scala` | LoadQueue 顶层，例化 5 个子模块 |
| `src/main/scala/xiangshan/mem/lsqueue/VirtualLoadQueue.scala` | Load 控制状态队列 |
| `src/main/scala/xiangshan/mem/lsqueue/LoadQueueRAR.scala` | RAR 违规检测（XOR 压缩 CAM） |
| `src/main/scala/xiangshan/mem/lsqueue/LoadQueueRAW.scala` | RAW 违规检测（MDP 训练） |
| `src/main/scala/xiangshan/mem/lsqueue/LoadQueueReplay.scala` | Replay 管理（13 原因，AgeDetector） |
| `src/main/scala/xiangshan/mem/lsqueue/LoadQueueUncache.scala` | MMIO/NC 访问 FSM |
| `src/main/scala/xiangshan/mem/lsqueue/LoadQueueData.scala` | LQ 数据存储（CAM 操作） |
| `src/main/scala/xiangshan/mem/lsqueue/NewStoreQueue.scala` | SQ 完整实现（ForwardModule, DeqModule, EnterSbufferQueue） |
| `src/main/scala/xiangshan/mem/lsqueue/LSQBundle.scala` | 所有 LSQ 相关 Bundle 定义 |
| `src/main/scala/xiangshan/mem/pipeline/NewLoadUnit.scala` | Load 流水线（5 级，8 源仲裁） |
| `src/main/scala/xiangshan/mem/pipeline/NewStoreUnit.scala` | Store 流水线（5 级，4 源仲裁） |
| `src/main/scala/xiangshan/mem/pipeline/package.scala` | 流水线阶段与入口枚举定义 |
| `src/main/scala/xiangshan/mem/MemBlock.scala` | MemBlock 顶层（例化所有 Load/Store Unit） |
| `src/main/scala/xiangshan/mem/vector/VSplit.scala` | 向量 Load/Store 拆分 |
| `src/main/scala/xiangshan/mem/vector/VMergeBuffer.scala` | 向量结果合并缓冲 |
| `src/main/scala/xiangshan/mem/vector/VSegmentUnit.scala` | Segment Load/Store 处理 |

## 11. 总结

XiangShan 的 Load/Store Queue 与 Memory Pipeline 展现了一套精心设计的高性能访存子系统：

1. **模块化设计**：Load Queue 拆分为 5 个独立模块，各司其职，降低了设计复杂度
2. **深度流水化**：5 级 Load/Store 流水线，配合 Fast Replay 机制，最小化 load-to-use 延迟
3. **多级 Forwarding**：5 个 Forward 源的 3 级 STLF 流水线，最大化数据复用
4. **一致性保障**：RAR/RAW 双违规检测 + Nuke 机制，正确实现 RVWMO
5. **智能调度**：AgeDetector oldest-first + MDP 预测 + 13 种 Replay 原因优先级编码
6. **向量支持**：VSplit/VMergeBuffer/VSegmentUnit 完整支持向量访存操作
7. **异常处理**：完善的非对齐访问、CBO、MMIO/NC 处理路径

该设计在保证功能正确性的同时，通过多种优化手段（Fast Replay、多源唤醒、STLF）实现了高性能的访存操作，是 XiangShan 处理器性能的关键支撑。