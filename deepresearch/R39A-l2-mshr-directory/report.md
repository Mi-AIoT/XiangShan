# R39A - L2 MSHR 与 Directory 深度分析报告

> 本文对 XiangShan 处理器 CoupledL2 缓存子系统中的 MSHR (Miss Status Holding Register) 状态机、Directory 三级流水线、MetaData 状态机、嵌套回写 (Nested Writeback) 处理、重试与退避机制、以及 GrantBuffer 设计进行深度剖析。源码基于 XSCache 模块，使用 Chisel HDL 实现，采用 CHI (Coherent Hub Interface) 协议。

---

## 1. MSHR FSM 状态机与调度/等待信号对

### 1.1 FSMState 结构定义

MSHR 的有限状态机由 `FSMState` Bundle 驱动（定义于 `Common.scala`，第 311-342 行）。该状态机采用 **schedule/wait** 成对信号的经典设计模式，其中 `s_*` 前缀表示"调度"信号（false 表示需要调度该操作，true 表示已完成调度），`w_*` 前缀表示"等待"信号（false 表示正在等待响应，true 表示响应已收到）。

**Schedule 信号（调度类）：**

| 信号 | 功能 | 初始值 |
|------|------|--------|
| `s_acquire` | 向下级 (L3/SLC) 发送 Acquire 请求 | true |
| `s_rprobe` | 向上行 L1 发送替换导致的 Probe（replacement probe） | true |
| `s_pprobe` | 向上行 L1 发送嵌套 snoop 导致的 Probe（snoop-induced probe） | true |
| `s_release` | 向下级发送 Release（数据回写） | true |
| `s_probeack` | 向下级响应 ProbeAck（snoop 回复） | true |
| `s_refill` | 向上行发送 Grant/GrantData（refill 数据给 L1） | true |
| `s_retry` | MSHR 重试标记（替换 way 冲突时） | true |
| `s_cmoresp` | CMO 操作完成后向上行发送响应 | true |
| `s_cmometaw` | CMO 操作的 Meta 写补偿 | true |

**CHI 扩展 Schedule 信号（可选）：**

| 信号 | 功能 |
|------|------|
| `s_rcompack` | 发送 CompAck for Read 事务 |
| `s_wcompack` | 发送 CompAck for Write 事务 |
| `s_cbwrdata` | 发送 CopyBackWrData（WriteEvict 场景下的数据回写） |
| `s_reissue` | CHI Protocol Retry 后的请求重新发出 |
| `s_dct` | 发送 DCT (Data Cache Transfer) 转发数据 |

**Wait 信号（等待类）：**

| 信号 | 功能 | 初始值 |
|------|------|--------|
| `w_rprobeackfirst` | 等待替换 Probe 的第一个 ProbeAck | true |
| `w_rprobeacklast` | 等待替换 Probe 的最后一个 ProbeAck | true |
| `w_pprobeackfirst` | 等待嵌套 Probe 的第一个 ProbeAck | true |
| `w_pprobeacklast` | 等待嵌套 Probe 的最后一个 ProbeAck | true |
| `w_grantfirst` | 等待来自 L3 的第一个数据响应 | true |
| `w_grantlast` | 等待来自 L3 的最后一个数据响应 | true |
| `w_grant` | 等待来自 L3 的完整 Comp/Resp 响应 | true |
| `w_releaseack` | 等待 Release 的 ACK 响应 | true |
| `w_replResp` | 等待 Directory 的 replacer 响应 | true |

### 1.2 状态初始化

MSHR 被分配时（`io.alloc.valid`），初始状态通过 `alloc_state` 由 MainPipe 计算并传入。默认情况下所有 `s_*` 和 `w_*` 信号都被置为 true（第 82-83 行），表示初始无待处理任务。MainPipe 根据请求类型在分配时将需要的信号清除为 false，表示需要执行相应操作。

例如，对于一个 A 通道的 miss 请求需要向下级 Acquire（`MainPipe.scala`，第 928-955 行）：

```scala
// alloc_state 初始为全 true
alloc_state.s_refill := cmo_cbo_s3
alloc_state.w_replResp := cmo_cbo_s3 || dirResult_s3.hit
when (need_acquire_s3_a) {
  alloc_state.s_acquire := io.cmoAllBlock.getOrElse(false.B)
  alloc_state.s_rcompack.get := !need_compack_s3_a
  alloc_state.w_grantfirst := false.B  // 需要等待 L3 grant
  alloc_state.w_grantlast := false.B
  alloc_state.w_grant := false.B
}
```

### 1.3 MSHR 任务类型与优先级

MSHR 通过 `io.tasks.mainpipe` 向 MainPipe 发送任务。任务类型及其优先级由 `ParallelPriorityMux` 确定（`MSHR.scala`，第 972-981 行），优先级从高到低为：

1. **mp_grant** (最高优先级) - 向上行发送 Grant/GrantData
2. **mp_release** - 向下级发送 Release/WriteBack/Evict
3. **mp_cbwrdata** - 发送 CopyBackWrData
4. **mp_probeack** - 响应 snoop（SnpResp/SnpRespData）
5. **mp_dct** - DCT 数据转发
6. **mp_cmometaw** (最低优先级) - CMO Meta 写补偿

每个任务的使能条件（第 264-295 行）严格依赖 FSM 状态：

```scala
// Release: 需要 rprobeacklast + grantlast + grant + replResp
val release_valid1 = !state.s_release && state.w_rprobeacklast && 
  state.w_grantlast && state.w_grant && state.w_replResp || release_valid1_cmo

// ProbeAck: 需要 pprobeacklast
val mp_probeack_valid = !state.s_probeack && state.w_pprobeacklast

// Grant: 需要所有等待条件 + backoff 检查
val mp_grant_valid = pending_grant_valid && 
  (retryTimes < backoffThreshold.U || backoffTimer === backoffCycles.U)
```

### 1.4 释放条件（will_free）

MSHR 释放（`will_free`）需要同时满足 `no_schedule` 和 `no_wait`（`MSHR.scala`，第 1285-1295 行）：

```scala
val no_schedule = state.s_refill && state.s_probeack && state.s_release &&
  state.s_rcompack.getOrElse(true.B) && state.s_wcompack.getOrElse(true.B) &&
  state.s_cbwrdata.getOrElse(true.B) && state.s_reissue.getOrElse(true.B) &&
  state.s_dct.getOrElse(true.B) && state.s_cmoresp && state.s_cmometaw
val no_wait = state.w_rprobeacklast && state.w_pprobeacklast && 
  state.w_grantlast && state.w_grant && state.w_releaseack && state.w_replResp
val will_free = no_schedule && no_wait
```

只有当所有 schedule 操作完成且所有 wait 条件满足后，MSHR 才会释放其占用的 entry。

### 1.5 TXREQ 任务构造

MSHR 根据事务类型生成 CHI TXREQ 消息（`MSHR.scala`，第 351-427 行）。CHI opcode 的映射关系：

| TL 请求 | CHI Opcode |
|---------|------------|
| Get | ReadNotSharedDirty |
| AcquireBlock NtoB | ReadNotSharedDirty |
| AcquireBlock NtoT | ReadUnique |
| AcquirePerm | MakeUnique |
| CBOClean | CleanShared |
| CBOFlush | CleanInvalid |
| CBOInval | MakeInvalid |
| Release (WriteBack) | WriteBackFull / WriteCleanFull / WriteEvictOrEvict / Evict |

---

## 2. Directory 三级流水线 (S1/S2/S3)

Directory 模块（`Directory.scala`）实现了一个三级 SRAM 访问流水线，用于处理 Tag 和 Meta 数据的读取与比较。

### 2.1 Stage 1 - SRAM 发起读取

在 S1 阶段，`io.read.fire` 时启动 Tag Array 和 Meta Array 的读取操作（第 228-240 行）：

```scala
val tagRead = tagArray.io.r(io.read.fire, io.read.bits.set).resp.data
metaRead := metaArray.io.r(io.read.fire, io.read.bits.set).resp.data
```

同时，S1 还计算 `occWayMask_s1`（被占用 way 的掩码），用于检测替换冲突。该掩码综合了所有 MSHR 的状态信息（第 275-282 行）：

```scala
val occWayMask_s1 = VecInit(io.msInfo.map(s =>
  Mux(
    s.valid && (s.bits.set === req_s1.set) && (s.bits.blockRefill || s.bits.dirHit),
    UIntToOH(s.bits.way, ways),
    0.U(ways.W)
  )
)).reduceTree(_ | _) |
  Mux(refillReqValid_s3 || reqValid_s3 && io.resp.bits.hit, 
      UIntToOH(io.resp.bits.way, ways), 0.U(ways.W))
```

S1 阶段的读取就绪信号（`io.read.ready`）受到 Meta/Tag 写操作和 Replacer 写操作的反压控制（第 339 行）：

```scala
io.read.ready := !io.metaWReq.valid && !io.tagWReq.valid && !replacerWen
```

### 2.2 Stage 2 - SRAM 数据锁存

S2 阶段锁存 SRAM 读取数据和请求信息（第 210-218 行）：

```scala
val reqValid_s2 = RegNext(io.read.fire, false.B)
val req_s2 = RegEnable(req_s1, io.read.fire)
val occWayMask_s2 = RegEnable(occWayMask_s1, io.read.fire && io.read.bits.refill)
```

`occWayMask_s2` 只在 refill 请求时锁存，因为非 refill 请求不需要替换检测。

### 2.3 Stage 3 - Hit/Way 计算与结果输出

S3 阶段执行关键的 Tag 比较和 Hit/Way 选择逻辑（第 248-334 行）：

**Tag 比较：**
```scala
val tagMatchVec = tagAll_s3.map(_ (tagBits - 1, 0) === req_s3.tag)
val metaValidVec = metaAll_s3.map(_.state =/= MetaData.INVALID)
val hitVec = tagMatchVec.zip(metaValidVec).map(x => x._1 && x._2)
```

Hit 判定需要同时满足 Tag 匹配且 Meta 状态有效（非 INVALID）。

**替换 Way 选择：**
```scala
val (inv, invalidWay, invOH) = invalid_way_sel(metaAll_s3)
val chosenOH = Mux(inv, invOH, replaceOH)
// 如果 chosenOH 不在 freeWayMask 中，选择 freeWayMask 的第一个可用 way
val finalReplOH = Mux(
  Mux1H(chosenOH, freeWayMask_s3),
  chosenOH,
  MaskToOH(freeWayMask_s3)
)
```

优先选择 INVALID way，其次使用替换策略选择的 way。如果 chosen way 被占用，则从 freeWayMask 中选择可用 way。

**最终输出：**
```scala
val hit_s3 = Cat(hitVec).orR || req_s3.cmoAll
val wayOH_s3 = Mux(req_s3.cmoAll, cmoWayOH_s3, Mux(hit_s3, hitOH, finalReplOH))
val meta_s3 = Mux1H(wayOH_s3, metaAll_s3)
val tag_s3 = Mux1H(wayOH_s3, tagAll_s3)
```

### 2.4 Refill Retry 机制

当 Directory 在 S3 判断所有 way 都被占用时（`occWayMask_s2.andR`），触发 refill retry（第 286-288 行）：

```scala
io.retryFastFwd := occWayMask_s2.andR && refillReqValid_s2
val freeWayMask_s3 = RegEnable(~occWayMask_s2, refillReqValid_s2)
val refillRetry = RegEnable(occWayMask_s2.andR, refillReqValid_s2)
```

`refillRetry` 信号传给 MSHR，MSHR 收到 retry 后会重置 `s_refill` 和 `s_retry`，并增加 `retryTimes` 计数器（`MSHR.scala`，第 1246-1254 行）。

### 2.5 SRAM 组织

Directory 使用以下 SRAM 结构：
- **Tag Array**: 使用 `SplittedSRAM`，支持 Tag ECC 编码，way 维度拆分为 2（`waySplit = 2`）
- **Meta Array**: 使用 `SRAMTemplate`，存储 `MetaEntry` 结构
- **Replacer State SRAM**: 存储替换策略状态（PLRU/RRIP 等），使用 `singlePort` 模式
- **Origin Bit SRAM**: 用于 DRRIP/SRRIP 的 hit-Promotion/miss-Insertion 策略

所有 SRAM 均为 `singlePort = true`，意味着读写操作不能同时进行，读操作优先级通过 `io.read.ready` 信号与写操作互斥。

---

## 3. MetaData 状态 (INVALID / BRANCH / TRUNK / TIP)

### 3.1 状态定义

MetaData 状态定义于 `Consts.scala`（第 26-31 行），使用 2 bit 编码：

```scala
val stateBits = 2
def INVALID: UInt = 0.U(stateBits.W) // way 为空
def BRANCH:  UInt = 1.U(stateBits.W) // 外部 slave cache 为 trunk
def TRUNK:   UInt = 2.U(stateBits.W) // 唯一的内部 master cache 为 trunk
def TIP:     UInt = 3.U(stateBits.W) // 自己是 trunk，内部 master 为 branch
```

### 3.2 状态语义

这四种状态源自 MOESI/MESIF 一致性协议的变体，在 CoupledL2 中的含义为：

| 状态 | 编码 | 语义 |
|------|------|------|
| **INVALID** | 00 | 缓存行无效/不存在 |
| **BRANCH** | 01 | L2 持有副本，但 L1 (master) 持有 TRUNK 权限，即数据是 shared clean 的 |
| **TRUNK** | 10 | L2 持有唯一副本，L1 可能持有 BRANCH 级别的副本 |
| **TIP** | 11 | L2 持有最终副本（等价于 trunk 级别），内部 master 均为 branch |

### 3.3 状态转换辅助函数

`Consts.scala` 提供了一系列辅助函数：

- **`isT(state)`**: 检查状态是否为 T 级别（TRUNK 或 TIP），使用 `state(1)` 位判断
- **`isToN(param)`**: 检查参数是否要求转换到 INVALID
- **`isToB(param)`**: 检查参数是否要求降级到 BRANCH
- **`isToT(param)`**: 检查参数是否要求升级到 TRUNK
- **`isParamFromT(param)`**: 检查参数是否来自 T 级别
- **`growFrom(param)`**: 从 Acquire 参数推断原始状态
- **`hintMiss(state, param)`**: 判断 prefetch hint 是否 miss
- **`isValid(state)`**: 检查状态是否有效（非 INVALID）

### 3.4 MetaEntry 结构

MetaEntry（`Directory.scala`，第 31-46 行）包含完整的缓存行元数据：

```scala
class MetaEntry {
  val dirty = Bool()           // 脏位
  val state = UInt(stateBits.W) // 一致性状态 (INVALID/BRANCH/TRUNK/TIP)
  val clients = UInt(clientBits.W) // 持有副本的 client 位图
  val alias = aliasBitsOpt.map(...) // alias bits（cache alias 问题）
  val prefetch = Option[Bool()]      // 是否为预取数据
  val prefetchSrc = Option[UInt(PfSource.pfSourceBits.W)] // 预取来源
  val accessed = Bool()        // 访问位
  val tagErr = Bool()          // L1/L3 上报的 Tag ECC 错误
  val dataErr = Bool()         // Data Check 错误 (CHI)
}
```

### 3.5 MetaChi 映射

MSHR 中的 `metaChi` 将内部 Meta 状态映射为 CHI Coherence State（`MSHR.scala`，第 116-125 行）：

```scala
val metaChi = ParallelLookUp(
  Cat(meta.dirty, meta.state),
  Seq(
    Cat(false.B, INVALID) -> I,   // Invalid
    Cat(false.B, BRANCH)  -> SC,  // Shared Clean
    Cat(false.B, TRUNK)   -> UC,  // Unique Clean
    Cat(false.B, TIP)     -> UC,  // Unique Clean
    Cat( true.B, TRUNK)   -> UD,  // Unique Dirty
    Cat( true.B, TIP)     -> UD   // Unique Dirty
  ))
```

这个映射关系体现了 CoupledL2 不维护 SharedDirty 状态——dirty 数据在 TRUNK 或 TIP 状态下均映射为 UD。

---

## 4. 嵌套回写处理 (Nested Writeback)

### 4.1 NestedWriteback 结构

NestedWriteback 信号定义于 `Common.scala`（第 372-385 行）：

```scala
class NestedWriteback {
  val set = UInt(setBits.W)
  val tag = UInt(tagBits.W)
  val c_set_dirty = Bool()  // 嵌套 ReleaseData 设置 block dirty
  val c_set_tip   = Bool()  // 嵌套 Release(无数据) 设置 block TIP
  val b_inv_dirty = Bool()  // 嵌套 Snoop 使 block 失效（含脏数据）
  val b_toB = Option[Bool()]  // Snoop 降级到 BRANCH
  val b_toN = Option[Bool()]  // Snoop 降级到 INVALID
  val b_toClean = Option[Bool()] // Snoop 清除 dirty 位
}
```

### 4.2 嵌套触发条件

当 MainPipe 处理 C 通道 Release 或 B 通道 Snoop 时，会生成 `nestedwb` 信号（`MainPipe.scala`，第 678-704 行）：

**C 通道 Release 触发：**
```scala
io.nestedwb.c_set_dirty := task_s3.valid && task_s3.bits.fromC && 
  task_s3.bits.opcode === ReleaseData && task_s3.bits.param === TtoN
io.nestedwb.c_set_tip := task_s3.valid && task_s3.bits.fromC && 
  task_s3.bits.opcode === Release && task_s3.bits.param === TtoN
```

当 L1 收到 Probe 后发送 ReleaseData(TtoN) 时，MSHR 中正在处理同一地址的 entry 可以"看到"这个 Release，直接将本地 meta 设置为 dirty + TIP。

**B 通道 Snoop 触发：**
```scala
io.nestedwb.b_inv_dirty := task_s3.valid && task_s3.bits.fromB && 
  source_req_s3.snpHitReleaseToInval && 
  !(isSnpStashX(req_s3.chiOpcode.get) || isSnpQuery(req_s3.chiOpcode.get))
```

Snoop 使 dirty block 失效时（如 SnpUnique），MSHR 需要感知到 meta 被 invalidate。

### 4.3 MSHR 端嵌套处理

MSHR 接收嵌套信号后进行匹配和处理（`MSHR.scala`，第 1360-1413 行）。匹配条件非常严格：

```scala
val nestedwb_match = req_valid && meta.state =/= INVALID &&
  dirResult.set === io.nestedwb.set &&
  dirResult.tag === io.nestedwb.tag &&
  state.w_replResp &&                          // replacer 响应已收到（way 已选定）
  (state.s_cmoresp || dirResult.hit) &&        // 排除 CMO on miss 场景
  (req_mayRepl || dirResult.hit)               // 排除非替换任务 on miss
```

**嵌套处理逻辑：**

1. **c_set_dirty**: 设置 `meta.dirty = true`，`meta.state = TIP`，清除 clients，设置 `releaseDirty = true`
2. **c_set_tip**: 设置 `meta.state = TIP`，清除 clients
3. **b_inv_dirty**: 设置 `meta.dirty = false`，`meta.state = INVALID`，清除 `probeDirty`
4. **b_toClean**: 清除 `meta.dirty`，清除 `probeDirty`
5. **b_toB**: 将状态降级为 BRANCH
6. **b_toN**: 将状态设为 INVALID，清除 `dirResult.hit`，设置 `state.w_replResp = cmo_cbo`

嵌套数据（ReleaseData）会被写入该 MSHR 对应的 ReleaseBuffer entry（`io.nestedwbData` 信号）。

### 4.4 嵌套的意义

嵌套机制使得 MSHR 能够在等待下级响应的同时，"看到"上行 cache 的操作结果，避免了以下问题：
- ReleaseData 不会丢失：即使 MSHR 还没有发送 Release，L1 的 ReleaseData 数据也会被正确捕获
- Snoop 导致的失效不会被忽略：MSHR 可以根据嵌套 Snoop 的结果调整本地 meta 状态
- 减少了状态不一致的窗口期

---

## 5. 重试与退避机制 (Retry & Backoff)

### 5.1 问题背景

当 L2 发生 miss 需要替换时，Directory 可能发现所有 way 都被其他 MSHR 占用。代码注释（`MSHR.scala`，第 91-96 行）详细说明了这个问题：

> When all the ways are occupied with some mshr, other mshrs with the same set may retry to find a way to replace over and over again, which may block the entrance of main pipe and lead to potential deadlock.

### 5.2 退避参数

```scala
val backoffThreshold = 3   // 允许立即重试的最大次数
val backoffCycles = 20      // 退避周期（时钟周期数）
val retryTimes = RegInit(0.U(log2Up(backoffThreshold).W))
val backoffTimer = RegInit(0.U(log2Up(backoffCycles).W))
```

### 5.3 退避流程

**Step 1: Refill Retry 检测**

当 Directory 发现所有 way 被占用时，通过 `io.replResp.valid && replResp.retry` 通知 MSHR（`MSHR.scala`，第 1246-1254 行）：

```scala
when (io.replResp.valid && replResp.retry) {
  state.s_refill := false.B    // 重新标记 refill 未完成
  state.s_retry := false.B
  dirResult.way := replResp.way  // 更新 way
  when (retryTimes < backoffThreshold.U) {
    retryTimes := retryTimes + 1.U
  }
  backoffTimer := 0.U
}
```

**Step 2: Grant 发送条件检查**

Grant 任务的发送受到退避控制（`MSHR.scala`，第 286 行）：

```scala
val mp_grant_valid = pending_grant_valid && 
  (retryTimes < backoffThreshold.U || backoffTimer === backoffCycles.U)
```

这意味着：
- 前 3 次 retry 内（`retryTimes < 3`），Grant 可以立即发送
- 超过 3 次后，必须等到 `backoffTimer` 计数到 20 个周期

**Step 3: Backoff Timer 计数**

```scala
when (pending_grant_valid &&
  backoffTimer < backoffCycles.U &&
  retryTimes === backoffThreshold.U) {
  backoffTimer := backoffTimer + 1.U
}
```

当达到 backoffThreshold 且 Grant 仍然 pending 时，backoffTimer 开始递增。一旦到达 `backoffCycles`（20），Grant 重新变为可用。

### 5.4 退避机制的效果

这种两阶段退避策略的效果是：
1. **短冲突**（<= 3 次）：几乎不产生额外延迟，快速重试
2. **长冲突**（> 3 次）：每个 MSHR 主动等待 20 个周期，让出 MainPipe 给其他 MSHR 处理 Release 等操作，从而释放被占用的 way
3. **死锁预防**：通过 backoff 机制打破 "所有 MSHR 都在 retry 同一 set" 的活锁场景

### 5.5 快速转发信号

Directory 还输出 `retryFastFwd` 信号（`Directory.scala`，第 286 行），用于通知 CustomL1Hint 模块在 retry 发生时提前发出 Hint 信号，帮助 L1 快速唤醒：

```scala
io.retryFastFwd := occWayMask_s2.andR && refillReqValid_s2
```

---

## 6. GrantBuffer 设计

### 6.1 功能概述

GrantBuffer（`GrantBuffer.scala`）是 L2 缓存与 L1 缓存之间的数据接口缓冲区，承担以下职责（第 53-58 行注释）：

1. 与 L1 通信：发送 Grant/GrantData/ReleaseAck/AccessAckData（via D channel），接收 GrantAck（via E channel）
2. 向 Prefetcher 发送响应
3. MainPipe 入口阻塞控制
4. 生成 L1 Hint 信号用于早期唤醒

### 6.2 内部数据结构

**Grant Queue 架构：**

```scala
val grantQueue = Module(new Queue(new GrantQueueTask(), entries = mshrsAll))
val grantQueueData0 = Module(new Queue(new GrantQueueData(), entries = mshrsAll))
val grantQueueData1 = Module(new Queue(new GrantQueueData(), entries = mshrsAll))
```

数据分为三个队列：Task 元数据队列和两个数据 Beat 队列。因为 `beatSize = 2`（CHI 64B block = 2 x 32B beat），两个数据队列分别存储 beat0 和 beat1。

**Grant Buffer 临时寄存器：**

```scala
val grantBufValid = RegInit(false.B)
val grantBuf = RegInit(0.U.asTypeOf(new Bundle() {
  val task = new TaskBundle()
  val data = new DSBeat()
  val grantid = UInt(mshrBits.W)
}))
```

用于保存 GrantData 的第二个 beat，当第一个 beat 发送后，第二个 beat 暂存在 grantBuf 中。

**Inflight Grant 追踪：**

```scala
val inflightGrant = RegInit(VecInit(Seq.fill(grantBufInflightSize){
  0.U.asTypeOf(Valid(new InflightGrantEntry))
}))
```

记录已发送但尚未收到 GrantAck 的 Grant 事务，用于阻止同地址的 Probe 向上发送。

### 6.3 数据流处理

**入队（Enqueue）：**
```scala
grantQueue.io.enq.valid := io.d_task.valid && 
  (dtaskOpcode =/= HintAck || io.d_task.bits.task.mergeA)
```

GrantBuffer 始终 ready（`io.d_task.ready := true.B`），因为如果反压失败会导致严重问题。

**Keyword 优化：**

对于支持 keyword 优化的配置（L1 cache line 不对齐时），beat 顺序会被调整（第 199-206 行）：
```scala
when(deqValid && io.d.ready && !grantBufValid && deqTask.opcode(0)) {
  grantBufValid := true.B
  grantBuf.task := deqTask
  grantBuf.data := Mux(deqTask.isKeyword.getOrElse(false.B), deqData(0), deqData(1))
}
```

当 `isKeyword` 为 true 时，第一个发送的 beat 变为 beat0（而不是默认的 beat1），确保关键字优先传输。

**出队（Dequeue）与 TLBundleD 构造：**

D channel 消息由 `toTLBundleD` 函数构造（第 86-98 行），将内部 TaskBundle 转换为 TileLink D channel 格式。

### 6.4 容量冲突阻塞

GrantBuffer 实现了多级反压逻辑，防止超过 `mshrsAll` 容量：

**对 Sink Request 的阻塞（`blockSinkReqEntrance`）：**
```scala
val noSpaceForSinkReq = PopCount(VecInit(io.pipeStatusVec.tail.map { case s =>
  s.valid && (s.bits.fromA || s.bits.fromC)
}).asUInt) + grantQueueCnt >= mshrsAll.U
```

**对 MSHR Request 的阻塞（`blockMSHRReqEntrance`）：**
```scala
val noSpaceForMSHRReq = PopCount(...) + grantQueueCnt >= (mshrsAll-1).U
```

MSHR 请求多预留 1 个 entry（因为 S1 信息已跳过）。

**B channel 阻塞（防止同地址 Probe）：**

```scala
io.toReqArb.blockSinkReqEntrance.blockB_s1 := Cat(inflightGrant.map(g => g.valid &&
  g.bits.set === io.fromReqArb.status_s1.b_set && 
  g.bits.tag === io.fromReqArb.status_s1.b_tag)).orR
```

当有 inflight Grant 匹配 Probe 目标地址时，阻塞该 Probe，直到收到 GrantAck。

### 6.5 GrantAck 处理

当 L1 发送 GrantAck（E channel）时，清除对应的 inflight entry（第 285-288 行）：

```scala
when (io.e.fire) {
  assert(io.e.bits.sink < grantBufInflightSize.U)
  inflightGrant(io.e.bits.sink).valid := false.B
}
```

`sink` 字段携带 inflight entry 的索引（grant_id），实现 O(1) 清除。

### 6.6 Prefetch Response 队列

对于 L2 prefetch，GrantBuffer 维护一个独立的响应队列（第 243-261 行）：

```scala
val pftRespQueue = prefetchOpt.map(_ => Module(new Queue(pftRespEntry, entries = pftQueueLen, flow = true)))
```

队列长度为 10（`pftQueueLen`），当队列接近满时，通过 `blockMSHRReqEntrance` 阻塞所有 MainPipe 入口，这是一个保守但可靠的反压策略。

### 6.7 性能监控

GrantBuffer 包含丰富的性能计数器：
- `grant_grantack_period`: Grant 到 GrantAck 的延迟直方图
- `max_grant_grantack_period`: 最大 Grant-GrantAck 延迟
- `pftRespQueue_about_to_full`: prefetch 响应队列接近满的事件
- Inflight Grant 泄漏检测：`assert(t < 10000.U, "Inflight Grant Leak")`

---

## 7. 源文件位置索引

| 文件 | 路径 | 主要内容 |
|------|------|----------|
| MSHR.scala | `XSCache/src/main/scala/coupledL2/MSHR.scala` | MSHR 状态机、任务构造、嵌套处理（约 1463 行） |
| Directory.scala | `XSCache/src/main/scala/coupledL2/Directory.scala` | Directory 三级流水线、Tag/Meta SRAM、替换策略（约 509 行） |
| Common.scala | `XSCache/src/main/scala/coupledL2/Common.scala` | TaskBundle、FSMState、NestedWriteback、RespInfoBundle 等公共数据结构（约 522 行） |
| Consts.scala | `XSCache/src/main/scala/coupledL2/Consts.scala` | MetaData 状态定义、一致性判断辅助函数（约 76 行） |
| MainPipe.scala | `XSCache/src/main/scala/coupledL2/MainPipe.scala` | Main 流水线 S2-S5、MSHR 分配、nestedwb 生成（约 1115 行） |
| GrantBuffer.scala | `XSCache/src/main/scala/coupledL2/GrantBuffer.scala` | Grant 缓冲区、D channel 发送、E channel 接收、容量反压（约 345 行） |

---

## 8. 关键设计洞察

### 8.1 Schedule/Wait 信号的 false-as-valid 设计

CoupledL2 采用 "false means valid" 的设计哲学：`s_*` 信号为 false 时表示该操作尚未执行（需要调度），为 true 时表示已完成；`w_*` 信号为 false 时表示正在等待（valid），为 true 时表示等待完成。这种设计使得初始状态可以简单地全部设为 true（无待处理操作），然后在分配时将需要执行的操作对应的信号清除为 false。

### 8.2 MSHR 与 Directory 的协作

MSHR 和 Directory 通过 `io.msInfo` 和 `io.replResp` 接口紧密协作：
- Directory 通过 `msInfo` 读取所有 MSHR 的状态，计算 `occWayMask`（被占用 way 掩码）
- MSHR 通过 `replResp` 获得 Directory 的替换结果，包括选中的 way、meta 信息、以及是否需要 retry

### 8.3 CHI Protocol Retry 支持

MSHR 内建了 CHI 协议级别的 Retry 支持（`MSHR.scala`，第 102-110 行）：
- `gotRetryAck`: 收到 RetryAck 信号
- `gotPCrdGrant`: 收到 PCrdGrant（Protocol Credit Grant）
- `srcid_retryack`: 记录发送 RetryAck 的源节点 ID
- `pcrdtype`: 记录 Protocol Credit 类型
- 收到 PCrdGrant 后，MSHR 通过 `s_reissue` 标记重新发出被 Retry 的请求

这种两级 Retry 机制确保了 CHI 网络拥塞时的优雅降级。

### 8.4 Deadlock 检测

MSHR 包含一个 watchdog 定时器（`MSHR.scala`，第 1420-1431 行），当 MSHR entry 持续有效超过 400,000 个周期时触发断言失败：

```scala
assert(validCnt <= VALID_CNT_MAX, 
  "validCnt full!, maybe there is a deadlock! addr => 0x%x ...")
```

这为系统调试提供了重要的死锁检测能力。

---

*报告生成基于 XiangShan CoupledL2 源码分析，涵盖 MSHR 状态机的完整生命周期、Directory SRAM 流水线设计、一致性状态机、嵌套处理机制、容错重试策略、以及 GrantBuffer 的缓冲与反压架构。*
