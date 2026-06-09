# R48 Store Queue (NewStoreQueue) Deep Dive

## 1. NewStoreQueue Complete Architecture

### 1.1 Overview

XiangShan 的 Store Queue (`NewStoreQueue`) 是乱序处理器 Load-Store Unit (LSU) 的核心组成部分，负责管理所有 in-flight store 指令的地址计算、数据写入、forwarding 查询、以及最终向 Sbuffer 或 uncache 接口的提交（commit）与出队（dequeue）。其设计规模默认为 **56 entries** (`StoreQueueSize = 56`)，可同时出队 **2 条** store 指令 (`EnsbufferWidth = 2`)。

NewStoreQueue 位于文件 `/home/agi/workspace/gitwork/XiangShan/src/main/scala/xiangshan/mem/lsqueue/NewStoreQueue.scala`，总计约 **2107 行**，包含三个私有子模块：
- `ForwardModule`：处理 Load 进行 Store-to-Load Forwarding (STLF) 查询
- `DeqModule`：管理 Store Queue 的出队（包括 cacheable 写入 Sbuffer、MMIO/NC 走 uncache 路径、CBO 操作）
- `EnterSbufferQueue`：Sbuffer 写入流水线缓冲队列

此外还有一个独立的内部类 `UnalignQueue`，用于保存跨 4K 页非对齐 store 请求的第二段物理地址。

### 1.2 Dual-Entry Storage Structure

NewStoreQueue 采用 **数据/控制分离** 的双存储体（Dual-Entry Storage）架构：

- **`dataEntries`** (`Vec(StoreQueueSize, SQDataEntryBundle)`)：存储完整的 store 数据信息，包括 uop 元数据、虚拟地址 `vaddr`、物理高位 `paddrHigh`（低 12 位与 vaddr 页内偏移相同）、字节掩码 `byteMask`、数据 `data`、size、memory type、CBO type、prefetch 标记等。该存储体 **无需 reset**。

- **`ctrlEntries`** (`Vec(StoreQueueSize, SQCtrlEntryBundle)`)：存储控制状态信息，包括 `dataValid`、`addrValid`、`allocated`、`committed`、`handleFinish`、`hasException`、`cross16Byte`、`isCbo`、`isVec`、`vecInactive`、`vecMbCommit`、`waitStoreS2` 等。该存储体 **需要 reset**（初始化为零）。

### 1.3 Enqueue Path

Store Queue 的 enqueue 发生在 dispatch 阶段。dispatch 单元通过 `io.enq.req` 提交分配请求，每周期最多 `LSQEnqWidth`（等于 `RenameWidth`）条指令。分配条件为：

```
allowEnqueue = validCount <= (StoreQueueSize - LSQStEnqWidth)
```

其中 `validCount = distanceBetween(enqPtrExt(0), deqPtrExt(0))`。

每条 store 指令可以携带 `numLsElem` 个 vector 元素，每个元素占用一个 SQ entry。分配逻辑支持 **跨环形队列边界** 的分配：

```scala
val enqCrossLoop = enqLowBound.zip(enqUpBound).map { case (low, up) => low.flag =/= up.flag }
```

对于每个 entry `i`，通过 `entryHitBound` 判断其是否落在某条 dispatch 请求的分配区间内，然后通过 `ParallelPriorityMux` 选择对应的 uop 信息写入 `dataEntries(i)`，同时置 `ctrlEntries(i).allocated := true`。

### 1.4 Entry Lifecycle

一个 store entry 的典型生命周期：

```
Dispatch (enq) --> Store Addr S1 (addrValid) --> Store Addr S2 (waitStoreS2=false, memoryType set)
     |                                              |
     |                                     Store Data (dataValid)
     |                                              |
     |                                     Commit (committed)
     |                                              |
     |                               +---------------+---------------+
     |                               |                               |
     |                     Cacheable path                   Uncacheable/CBO path
     |                     (writeToSbuffer)                 (toUncacheBuffer/toDCache)
     |                               |                               |
     |                               |                    handleFinish + writeBack
     |                               |
     |                               |
     +----------------------------- deqPtr moves ------- entry freed
```

---

## 2. Six-Pointer System

NewStoreQueue 维护了 **六组** 环形队列指针，分别负责不同阶段的流水线控制：

### 2.1 `enqPtrExt` (Enqueue Pointer)

```scala
val enqPtrExt = RegInit(VecInit((0 until io.enq.req.length).map(_.U.asTypeOf(new SqPtr))))
```

- 位宽：`LSQEnqWidth` 个指针（多端口同时分配）
- 功能：指向下一个可分配的 entry 位置
- 更新时机：
  - 正常情况：`enqPtrExt := enqPtrExt.map(_ + enqNumber)`，其中 `enqNumber` 是本周期实际入队的元素总数
  - Redirect 后 2 个周期：回退已分配但被 cancel 的 entry 数量 `enqPtrExt := enqPtrExt.map(_ - redirectCancelCount)`

### 2.2 `deqPtrExt` (Dequeue Pointer)

```scala
val deqPtrExt = RegInit(VecInit((0 until EnsbufferWidth).map(_.U.asTypeOf(new SqPtr))))
```

- 位宽：`EnsbufferWidth`（=2）个指针
- 功能：指向 store queue 尾部将要释放的位置
- 移动条件（三者取并集）：
  - `deqPtrMoveFromSbuffer`：Sbuffer 接受了数据写入
  - `deqPtrVectorInactiveMove`：vector inactive 元素跳过
  - `uncacheMove`：MMIO/NC/CBO 请求 `handleFinish` 后
- 核心语义：deqPtr 移动表示一个 store request 的 **生命周期终结**（SQ 释放该 entry credit）

### 2.3 `rdataPtrExt` (Read Data Pointer)

```scala
val rdataPtrExt = RegInit(VecInit((0 until EnsbufferWidth).map(_.U.asTypeOf(new SqPtr))))
```

- 位宽：`EnsbufferWidth`（=2）个指针
- 功能：指向需要读取 dataEntries/ctrlEntries 送给 DeqModule 处理的 entry 位置
- 移动条件：
  - `pipelineConnectFireNum`：数据通过 `EnterSbufferQueue` pipeline 被消费
  - `rdataPtrVectorInactiveMove`：inactive entry 直接跳过
  - `otherMove`：NC 请求收到 idResp 确认，或 MMIO/CBO writeBack 发射
- **关键区别**：`rdataPtr` 比 `deqPtr` 更"激进"——rdataPtr 在数据进入 pipeline 时就移动，而 deqPtr 在数据真正写入 Sbuffer 后才移动。对于 MMIO/CBO，`rdataPtr === deqPtr`，因为它们必须严格按序处理。

### 2.4 `cmtPtrExt` (Commit Pointer)

```scala
val cmtPtrExt = RegInit(VecInit((0 until CommitWidth).map(_.U.asTypeOf(new SqPtr))))
```

- 位宽：`CommitWidth` 个指针
- 功能：指向等待 commit 的 entry，由 ROB 的 `pendingPtr` 驱动
- 移动条件：`commitVec` 中对应 bit 为 true，通过 `PopCount(commitVec)` 计算移动量
- Commit 条件：
  - `allocated && noException && !waitStoreS2 && allValid`
  - 且 robIdx 不超过 `GatedRegNext(io.fromRob.pendingPtr)`（即 ROB 已提交）
  - Vector store 额外条件：`vecMbCommit` 或 `vecInactive`

### 2.5 `addrReadyPtrExt` (Address Ready Pointer)

```scala
val addrReadyPtrExt = RegInit(0.U.asTypeOf(new SqPtr))
```

- 功能：指向第一个 **地址未就绪** 的 entry（即 `!addrValid` 且 `allocated` 的首个位置）
- 更新：每周期扫描 `IssuePtrMoveStride`（=4）个 entry，通过 `PriorityEncoder` 找到第一个不满足 `addrValid || vecInactive || vecMbCommit` 的 entry
- 输出给 Load Queue：`io.toLoadQueue.stAddrReadySqPtr` 和 `stAddrReadyVec`，用于 Load 指令的 Memory Dependency Prediction (MDP) 等待判断
- Redirect 时：回退到 `max(cmtPtr, deqPtrNext)`

### 2.6 `dataReadyPtrExt` (Data Ready Pointer)

```scala
val dataReadyPtrExt = RegInit(0.U.asTypeOf(new SqPtr))
```

- 功能：指向第一个 **数据未就绪** 的 entry（即 `!dataValid` 且 `addrValid && !waitStoreS2` 的首个位置）
- 更新逻辑与 `addrReadyPtrExt` 类似，但条件更严格：需要 `addrValid && !waitStoreS2 && (isMmio || dataValid)`
- 输出给 Load Queue：`io.toLoadQueue.stDataReadySqPtr` 和 `stDataReadyVec`
- Redirect 时：同 `addrReadyPtrExt`

### 指针关系总结

```
enqPtr (入口)  ---------------------------------------------------------->  (新分配)
                  addrReadyPtr -----> (地址已就绪)
                                      dataReadyPtr --> (数据已就绪)
                                                      cmtPtr --> (ROB 已提交)
                                                                   rdataPtr --> (读取处理)
                                                                                 deqPtr (出口)  -->  (释放)
```

---

## 3. ForwardModule 3-Stage STLF Pipeline

ForwardModule 是 Store Queue 中最复杂的子模块，实现 Store-to-Load Forwarding (STLF)，使 Load 指令可以直接从 Store Queue 中获取尚未写入 Cache 但已在 Store Queue 中的数据。

**文件位置**：`NewStoreQueue.scala`，第 227-629 行，内部类 `ForwardModule`。

### 3.1 Pipeline Overview

```
+----------+     +----------+     +----------+
| Stage 0  | --> | Stage 1  | --> | Stage 2  |
| (Cycle 0)|     | (Cycle 1)|     | (Cycle 2)|
+----------+     +----------+     +----------+
```

- Stage 0：准备 mask 和地址范围
- Stage 1：地址匹配 + 字节重叠检查 + 选择 youngest valid candidate
- Stage 2：提取正确的字节数据，生成 forwarded data 和 mask

### 3.2 Stage 0: Mask Preparation

在 Stage 0，对于每个 Load pipeline 宽度的查询端口 `i`，执行以下操作：

1. **生成 deqMask**：`UIntToMask(io.ctrlInfo.deqPtr.value, StoreQueueSize)`，标记 deqPtr 以下的所有 entry
2. **计算 differentFlag**：判断 `deqPtr.flag =/= s0Req.bits.sqIdx.flag`，即 load 的 sqIdx 是否绕过了队列边界
3. **生成 forwardMask**：`UIntToMask(s0Req.bits.sqIdx.value, StoreQueueSize)`，标记 sqIdx 以上的所有 entry
4. **计算 byte 范围**：
   - `s0LoadStart = s0Req.bits.vaddr(VWordOffset-1, 0)`
   - `s0ByteOffset = MemorySize.ByteOffset(s0Req.bits.size)`
   - `s0LoadEnd = s0LoadStart + s0ByteOffset`
5. **生成 ageMask**（处理环形队列分段）：
   ```scala
   val s0AgeMaskLow  = s0DeqMask & s0ForwardMask & differentFlag
   val s0AgeMaskHigh = ~s0DeqMask & (differentFlag | s0ForwardMask)
   ```
   - `AgeMaskLow`：在 deqPtr 到 forwardMask 的低位段
   - `AgeMaskHigh`：在高位段（跨越队列边界时有效）

6. **MDP storeSetHitVec**：根据 LFST 模式或 SSID 模式生成 store set hit 向量

所有 Stage 0 的信号通过 `RegEnable` 打一拍送入 Stage 1。

### 3.3 Stage 1: Address Match & Youngest Selection

1. **Virtual Address Match**（仅比较高位）：
   ```scala
   val s1Same16BMatchVec = dataEntriesIn.map(_.vaddr(high bits) === s1LoadVaddr)
   val s1Next16BMatchVec = ctrlEntry.cross16Byte && (dataEntry.vaddr + 1) === s1LoadVaddr
   val s1SameLineMatchVec = dataEntry.vaddr(cacheLine bits) === s1LoadVaddr(cacheLine bits)
   ```
   三种匹配：same 16B、next 16B（跨 16 字节边界）、same cache line（CBO zero 情况）

2. **Byte Overlap Check**（在 store-relative 16B 坐标空间）：
   ```scala
   storeRangeStart <= loadRangeEnd && storeRangeEnd >= loadRangeStart
   ```

3. **Two-Step Selection**（处理环形队列）：
   ```scala
   val s1CanForwardLow  = s1AgeMaskLow  & s1OverlapMask & s1VaddrMatchVec
   val s1CanForwardHigh = s1AgeMaskHigh & s1OverlapMask & s1VaddrMatchVec
   ```

4. **Youngest Selection**（通过 `findYoungest` 二叉树选择器）：
   ```scala
   val (s1SelectLowOH, _)  = findYoungest(Reverse(s1CanForwardLow))
   val (s1ForwardHighOH, _) = findYoungest(Reverse(s1CanForwardHigh))
   val s1SelectHighOH = s1ForwardHighOH & !s1CanForwardLow.orR
   val s1SelectOH = Reverse(s1SelectLowOH | s1SelectHighOH)
   ```

5. **MDP Address Invalid Detection**：检测 age mask 范围内是否有未计算地址的 store，用于生成 `loadWait` 信号

### 3.4 Stage 2: Data Extraction

1. **Physical Address Re-match**（二级验证）：
   ```scala
   val s2PaddrMatchVec = dataEntriesIn.map { dataEntry =>
     same16BMatch || next16BMatch || (cboZero && sameLineMatch)
   }
   ```
   检测 vaddr match 但 paddr 不 match 的情况（TLB 重映射），触发 `s2PaddrNoMatch`

2. **Data Rotation & Extraction**：
   ```scala
   val s2ByteSelectOffset = s2LoadStart - s2SelectDataEntry.byteStart
   val s2SelectData = (0 until VLENB).map(j => j.U -> rotateByteRight(data, j*8))
   val s2OutData = ParallelLookUp(s2ByteSelectOffset, s2SelectData)
   ```
   通过 `rotateByteRight`（循环右移）和 `ParallelLookUp` 实现任意字节偏移的数据提取

3. **Forwarding Safety Check**：
   - `s2SafeForward`：单匹配或完全重叠（full overlap）才安全
   - `s2Cross4KPage`：跨 4K 页的 forwarding 不支持
   - `s2MultiMatch`：多匹配时只在 full overlap 情况下安全

4. **Response Generation**：
   - `forwardData`/`forwardMask`：字节粒度的 forwarded 数据
   - `dataInvalid`：地址匹配但数据尚未就绪
   - `addrInvalid`：MDP 等待的 store 地址未就绪
   - `forwardInvalid`：不安全的 forwarding 情况
   - `matchInvalid`：paddr 不匹配

---

## 4. findYoungest Binary Tree Selector

`findYoungest` 是 ForwardModule 中用于从 one-hot 向量中选择"最年轻"（最高索引）元素的递归二叉树选择器。

**代码位置**：`NewStoreQueue.scala`，第 252-282 行。

### 4.1 Algorithm

```scala
def findYoungest(in: UInt): (UInt, Bool) = {
  def onehotWithMulti(x: UInt): (UInt, Bool) = {
    if (w == 1) (x, false.B)
    else {
      val high = x(w-1, w/2)
      val low  = x(w/2-1, 0)
      val (highOnehot, highMulti) = onehotWithMulti(high)
      val (lowOnehot,  lowMulti)  = onehotWithMulti(low)
      val multi = highMulti || lowMulti || (highHasOne && lowHasOne)
      val onehot = Mux(lowHasOne, Cat(0, lowOnehot), Cat(highOnehot, 0))
      (onehot, multi)
    }
  }
  onehotWithMulti(in)
}
```

### 4.2 Recursive Binary Tree

该函数递归地将输入向量对半分：
- 如果只有一位（`w == 1`），直接返回
- 否则，递归处理高半部分和低半部分
- `Mux(lowHasOne, ...)` 优先选择低位部分（因为在 Reverse 之后，低位对应原始向量的高索引，即更"年轻"的 store）
- 返回 `(onehot, multi)` 其中 `multi` 标识是否存在多命中

### 4.3 Usage Pattern

在 STLF pipeline 中，输入向量首先被 `Reverse`，使得最高索引（最年轻的 store）位于 bit 0：
```scala
val (s1SelectLowOH, _) = findYoungest(Reverse(s1CanForwardLow))
```
结果再 `Reverse` 回来得到原始索引空间的 one-hot 向量。

这种设计使得递归二叉树总是优先选择"低位有值"的路径，等价于在原始空间中选择最高索引。

### 4.4 Multi-Hit Detection

返回的第二个值 `multi` 在 STLF 中被用于：
```scala
val (_, s1MultiMatch) = findYoungest(s1CanForwardLow | s1CanForwardHigh)
```
当存在多匹配时（`s2MultiMatch = true`），只有 `s2FullOverlap`（store 完全覆盖 load 的字节范围）才被认为是 safe forward，否则需要让 load replay。

---

## 5. DeqModule Uncache/CBO FSM

DeqModule 是 Store Queue 的出队核心，位于 `NewStoreQueue.scala`，第 775-1346 行。它管理三种出队路径：cacheable（写入 Sbuffer）、MMIO/NC（走 uncache buffer）、CBO（cache block operation）。

### 5.1 CBO FSM

CBO 操作有 6 个状态：

```
                      + ----------------------------------- +
                      |                                     |
  clean/flush/inval   |                                     v
idle ------------> flushSb --> sendReq --> waitResp --> writeback
  |                   ^
  |   zero            |
  + -------> writeZero -- +
```

- **idle**：等待 head entry 是 valid 的 CBO 指令
- **writeZero**（仅 CBO zero）：向 Sbuffer 写入全零数据
- **flushSb**：清空 Sbuffer（`io.sbufferCtrl.req.flush`），等待 `sbufferCtrl.resp.empty && dataQueue.io.empty`
- **sendReq**：发送 CMO 请求到 DCache（`io.toDCache.req`）
- **waitResp**：等待 DCache 响应
- **writeback**：向 ROB writeback，标记完成

CBO zero 路径特殊：先写零到 Sbuffer，再 flush Sbuffer，然后直接 writeback（不需要发 CMO 请求到 DCache）。

### 5.2 Uncache (MMIO/NC) FSM

MMIO/NC 操作有 5 个状态：

```
        + --------- +
        |    isNC   |
        v           |
      idle --> sendReq --> waitReqAck --> writeback
                      |                    ^
                      +-- waitResp --------+
```

- **idle**：等待 head entry 是 uncacheable 且 committed 的请求
- **sendReq**：发送请求到 `toUncacheBuffer`
- **waitReqAck**（仅 NC）：等待 `idResp` 确认 uncache buffer 已接受（NC 不需要等 response，fire-and-forget）
- **waitResp**（仅 MMIO）：等待 `resp` 返回数据
- **writeback**：向 ROB 写回结果，包括 `hardwareError` 和 `accessFault` 检测

### 5.3 Uncache Request Generation

```scala
io.toUncacheBuffer.req.bits.addr   := headDataEntry.paddr
io.toUncacheBuffer.req.bits.data   := Mux(headDataEntry.vaddr(3), outData.high(64), outData.low(64))
io.toUncacheBuffer.req.bits.mask   := Mux(headDataEntry.vaddr(3), outMask.high(8), outMask.low(8))
```

根据 vaddr 的 bit 3 选择高低 64 位数据——因为 uncache 操作只处理 8 字节粒度。

### 5.4 Writeback Generation

```scala
writeBackToRob.exceptionVec(hardwareError) := hasHardwareError
writeBackToRob.exceptionVec(storeAccessFault) := hasAccessFault
```

MMIO/CBO 操作的异常信息通过 `io.writeBack` 和 `io.exceptionInfo` 传回 ROB。

---

## 6. EnterSbufferQueue Pipeline

EnterSbufferQueue 是位于 Store Queue 和 Sbuffer 之间的 **顺序写入数据缓冲队列**，用于消除两者之间的时序路径。

**代码位置**：`NewStoreQueue.scala`，第 656-773 行，内部类 `EnterSbufferQueue`。

### 6.1 Architecture

```
+------------+                        +-------------------+
| StoreQueue |                        |                   |
+------------+                        |                   |
|      .     |                        | EnterSbufferQueue |
|      .     |                        |                   |
|   head n   | ----[n=EnsbufferWidth]-->  Entry n       | ---->  Sbuffer
+------------+                        +-------------------+
|   head 0   | ---------------------->  Entry 0         | ---->  Sbuffer
+------------+                        +-------------------+
```

- 队列深度：`EnsbufferWidth`（=2），恰好等于出队宽度
- 使用独立的 `DataQueuePtr` 指针管理

### 6.2 Key Properties

1. **顺序保证**：port `i` 的 entry 进入队列要求 port `i-1` 已经 ready（`io.fromDeqModule(i).ready` 依赖 `io.fromDeqModule(i-1).ready`）
2. **Same-cycle Reuse**：当 Sbuffer 在同周期消费了一个 entry（`deqSameCycle`），该 entry 槽位可以立即被新 entry 占用
3. **deqPtrMove**：输出信号 `deqPtrMove` 告诉 DeqModule 是否允许移动外层的 deqPtr。对于 cross16B 请求，port 0 不移动 deqPtr，只有 port 1 移动

### 6.3 Connection to DeqModule

DeqModule 中的 `writeSbufferWire` 通过 `dataQueue.io.fromDeqModule` 连接到 EnterSbufferQueue，然后通过 `dataQueue.io.toSbuffer` 连接到最终的 Sbuffer 写入接口。

---

## 7. Data Alignment and Rotation

### 7.1 rotateByteRight Function

`rotateByteRight` 定义在 `NewStoreQueueBase` 中：

```scala
def rotateByteRight(in: UInt, step: Int): UInt = {
  val maxLen = in.getWidth
  if(step == 0) in
  else Cat(in(step - 1, 0), in(maxLen - 1, step))
}
```

这是一个 **循环右移** 操作，将 `in` 按字节粒度循环右移 `step` 字节。

### 7.2 Usage in ForwardModule (STLF)

在 Stage 2 数据提取中：

```scala
val s2SelectData = (0 until VLENB).map(j => j.U -> rotateByteRight(s2SelectDataEntry.data, j * 8))
val s2OutData = ParallelLookUp(s2ByteSelectOffset, s2SelectData)
```

原理：Store 的数据在内存中按其 `byteStart` 对齐存储。当 Load 需要从该 Store 的数据中提取部分字节时，通过 `s2ByteSelectOffset = s2LoadStart - s2SelectDataEntry.byteStart` 计算偏移量，然后用循环右移将目标字节移到最低位，再通过掩码提取。

### 7.3 Usage in DeqModule (Sbuffer Write Path)

在 DeqModule 中，同样使用 `rotateByteRight` 为 Sbuffer 写入生成对齐数据：

```scala
for (i <- 0 until EnsbufferWidth) {
  val selectOffset = 0.U - dataEntries(i).vaddr(3, 0)
  val selectData = (0 until VLENB).map(j => j.U -> rotateByteRight(dataEntries(i).data, j * 8))
  outData(i) := ParallelLookUp(selectOffset, selectData)
  outMask(i) := ParallelLookUp(selectOffset, selectMsk)
}
```

这里的 `selectOffset = 0 - vaddr(3,0)` 实际上是计算将数据对齐到 16 字节（128-bit）边界所需的移位量。Sbuffer 以 16 字节对齐粒度写入，因此需要将 store 数据的起始位置对齐到最近的 16B 边界。

### 7.4 Unalign Split for Sbuffer

当 store 请求跨越 16 字节边界时（`headCross16Byte = true`），DeqModule 会将一条请求 **拆分为两条** Sbuffer 写入请求：

- **Port 0**：低 16 字节部分，使用 `paddrLow`/`vaddrLow`
- **Port 1**：高 16 字节部分，使用 `paddrHigh`/`vaddrHigh`，掩码取 `outMask & ~unalignMask`（即未被 port 0 覆盖的字节）

Mask 生成：
```scala
unalignMask(i) = Fill(VLENB, true.B) << dataEntries(i).vaddr(3, 0)
writeSbufferMask(0) = outMask(0) & unalignMask(0)          // 低地址部分
writeSbufferMask(1) = outMask(0) & (~unalignMask(0))       // 高地址部分
```

### 7.5 MemorySize Size Encoding

| Size | Encoding | ByteOffset | Bytes |
|------|----------|-----------|-------|
| B    | 000      | 0         | 1     |
| H    | 001      | 1         | 2     |
| W    | 010      | 3         | 4     |
| D    | 011      | 7         | 8     |
| Q    | 100      | 15        | 16    |

`ByteOffset` 用于计算 `byteEnd = byteStart + ByteOffset(size)`，表示 store 访问的字节范围 `[byteStart, byteEnd]`。

---

## 8. Force Write Threshold Mechanism

### 8.1 Problem Statement

当 Store Queue 接近满载时，如果 Sbuffer 也接近满载，会导致 Store Queue 无法 dequeue 新请求，进而阻塞整个 store 流水线，甚至引发 Dispatch 停顿。

### 8.2 Implementation

Force Write 机制通过两个阈值实现 **迟滞控制**（hysteresis control）：

```scala
val ForceWriteUpper = Constantin.createRecord("ForceWriteUpper", initValue = StoreQueueSize - 4)  // = 52
val ForceWriteLower = Constantin.createRecord("ForceWriteLower", initValue = StoreQueueSize - 9)  // = 47

io.sbufferCtrl.req.forceWrite := RegNext(
  Mux(valid_cnt >= ForceWriteUpper, true.B,
    valid_cnt >= ForceWriteLower && io.sbufferCtrl.req.forceWrite),
  init = false.B
)
```

### 8.3 Threshold Values

- **`ForceWriteUpper`** = `StoreQueueSize - 4` = 52：当 Store Queue 中有效 entry 数达到此值时，**强制** 向 Sbuffer 发出 force write 信号
- **`ForceWriteLower`** = `StoreQueueSize - 9` = 47：当 Store Queue 中有效 entry 数降至此值以下时，停止 force write

### 8.4 Hysteresis Behavior

```
valid_cnt >= 52  --> forceWrite = true  (set)
valid_cnt <  47  --> forceWrite = false (clear)
47 <= valid_cnt < 52  --> 保持当前状态 (hysteresis band)
```

这种迟滞设计避免了 forceWrite 信号在阈值附近的频繁抖动（chattering）。

### 8.5 Runtime Configurability

两个阈值通过 `Constantin.createRecord` 注册为运行时可调参数，支持通过配置接口动态修改，无需重新综合。

### 8.6 Sbuffer Response

当 force write 生效时，Sbuffer 会尽快驱逐（evict）其已有条目到 DCache，释放空间给 Store Queue 的 dequeue 路径。Sbuffer 通过 `io.sbufferCtrl.resp.empty` 反馈其状态（CBO flush 路径使用）。

---

## 9. Source File Locations

| File | Path | Lines | Description |
|------|------|-------|-------------|
| **NewStoreQueue.scala** | `/home/agi/workspace/gitwork/XiangShan/src/main/scala/xiangshan/mem/lsqueue/NewStoreQueue.scala` | 2107 | 主模块，包含 ForwardModule、DeqModule、EnterSbufferQueue、UnalignQueue |
| **LSQBundle.scala** | `/home/agi/workspace/gitwork/XiangShan/src/main/scala/xiangshan/mem/lsqueue/LSQBundle.scala` | 225 | Store Queue IO bundles 定义（StoreQueueIO、StoreQueueEnqIO、StoreAddrIO 等） |
| **LSQWrapper.scala** | `/home/agi/workspace/gitwork/XiangShan/src/main/scala/xiangshan/mem/lsqueue/LSQWrapper.scala` | 418 | Load Queue + Store Queue wrapper，包含 uncache arbiter |
| **Bundles.scala** | `/home/agi/workspace/gitwork/XiangShan/src/main/scala/xiangshan/mem/Bundles.scala` | -- | SQForward、SQForwardRespS1/S2 等 forward 相关 bundle 定义 |
| **MemCommon.scala** | `/home/agi/workspace/gitwork/XiangShan/src/main/scala/xiangshan/mem/MemCommon.scala` | -- | MemorySize 对象定义（B/H/W/D/Q size encoding） |
| **Parameters.scala** | `/home/agi/workspace/gitwork/XiangShan/src/main/scala/xiangshan/Parameters.scala` | -- | StoreQueueSize=56, EnsbufferWidth=2, ForceWrite thresholds 等参数定义 |

---

## 10. Key Design Highlights

### 10.1 Separate Read/Dequeue Pointers

`rdataPtr` 和 `deqPtr` 的分离是本设计的核心创新点之一。`rdataPtr` 在数据进入 EnterSbufferQueue 时就前进，允许下一周期提前准备后续 entry 的数据；`deqPtr` 在数据真正被 Sbuffer 消费后才前进，保证 credit-based flow control 的正确性。对于 MMIO/CBO，两者同步前进，保证严格有序。

### 10.2 Two-Step Circular Queue Forwarding

ForwardModule 通过 `AgeMaskLow` 和 `AgeMaskHigh` 将环形队列分成两段处理，避免了复杂的模运算，使得 forwarding 逻辑可以自然处理队列绕回（wrap-around）的情况。

### 10.3 Vector Store Integration

Store Queue 深度集成了 vector store 支持：每个 vector 元素分配独立的 SQ entry，通过 `vecMbCommit`（VMergeBuffer commit）和 `vecInactive`（inactive element）标记处理 vector store 的特殊生命周期。注意：代码注释表明 `vecMbCommit` 机制将在未来被移除。

### 10.4 MDP (Memory Dependency Prediction)

Store Queue 通过 `loadWaitBit`、`loadWaitStrict`、`ssid`、`storeSetHit` 等信号与 Load Queue 协作实现 Memory Dependency Prediction。当 Load 检测到 younger store 的地址尚未计算时，可以 stall 自己以避免不必要的 replay。`addrReadyPtr` 和 `dataReadyPtr` 提供了快速查询接口。
