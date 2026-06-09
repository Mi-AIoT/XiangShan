# R16 - Store Buffer (SBuffer) 深度分析报告

> 本文基于 XiangShan 源码 `src/main/scala/xiangshan/mem/sbuffer/` 目录下的四个文件进行深度分析，涵盖 SBuffer 的整体架构、条目状态机、Store 合并逻辑、奇偶分配策略、淘汰写回机制、Load-Store 转发、Coherent Timeout 与 Replay 机制、Store Prefetch 集成、以及 microarchitectural drain 等核心设计。

---

## 1. SBuffer 整体架构

### 1.1 功能定位

SBuffer（Store Buffer）是 XiangShan 处理器访存子系统中的关键组件，位于 Store Queue（SQ）与 L1 DCache 之间。其核心功能包括：

1. **Store 合并（Store Merging）**：将多条写入同一 cache line 的 store 操作在 SBuffer 中合并，减少 DCache 压力。
2. **Store-Load Forwarding（Store-to-Load Forwarding）**：在 SBuffer 中为 Load 操作提供快速的数据转发路径。
3. **Writeback Buffer**：作为 DCache 的写入缓冲区，异步地将 SBuffer 中的数据写回 DCache。
4. **Store Prefetch 触发**：结合 SPB（Store Prefetch Bursts）机制，对连续 store 模式触发预取。

### 1.2 参数配置

根据 `Parameters.scala` 中的定义，SBuffer 的关键参数如下：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `StoreBufferSize` | 16 | SBuffer 总条目数 |
| `StoreBufferThreshold` | 9 | 触发 eviction 的活跃条目阈值 |
| `EnsbufferWidth` | 2 | SBuffer 入口宽度（每周期可接受 2 条 store 请求） |
| `StorePipelineWidth` | 2 | Store 流水线宽度（连接 DCache 的写入端口数） |
| `LoadPipelineWidth` | 2 | Load 流水线宽度（连接 forward 的查询端口数） |
| `EvictCycles` | 1048576 (2^20) | Coherence timeout 的最大计数值 |
| `SbufferReplayDelayCycles` | 16 | Miss queue replay 超时延迟周期数 |

### 1.3 地址字段划分

SBuffer 使用 physical tag（ptag）进行匹配和合并，同时维护 virtual tag（vtag）用于 Load Forward 的早期地址匹配：

- `PTagWidth = PAddrBits - OffsetWidth`：物理地址 tag 位宽，其中 `OffsetWidth = log2(CacheLineBytes)`
- `VTagWidth = VAddrBits - OffsetWidth`：虚拟地址 tag 位宽
- `OffsetWidth = log2(CacheLineBytes)`：cache line 内偏移位宽
- `CacheLineWords = CacheLineBytes / DataBytes`：每条 cache line 的 64-bit word 数
- `CacheLineVWords = CacheLineBytes / VDataBytes`：每条 cache line 的 VLEN-width word 数

### 1.4 主要子模块

`Sbuffer.scala` 中定义了以下子模块：

1. **`SbufferData`**：独立的数据存储模块，维护所有 16 个条目的 data 和 mask，支持 2 周期写入更新和 mask flush。
2. **`StorePfWrapper`**：Store Prefetch 包装模块，内部包含 `Serializer` 和 `StorePrefetchBursts` 两个子模块。
3. **`ValidPseudoLRU`**：PLRU 替换算法实现，用于选择 eviction 候选条目。

---

## 2. Entry 状态机

### 2.1 状态位定义

每个 SBuffer entry 由 `SbufferEntryState` 类描述，包含以下状态位：

```scala
class SbufferEntryState {
  val state_valid    = Bool()  // 该 entry 有效（已分配）
  val state_inflight = Bool()  // 正在向 DCache 写入
  val w_timeout      = Bool()  // 遭遇 coherence timeout，等待 replay
  val w_sameblock_inflight = Bool()  // 同一 cache block 的请求正在 DCache inflight
}
```

### 2.2 状态转换与等价关系

源码中定义了以下状态判断方法：

| 方法 | 语义 | 等价表达式 |
|------|------|-----------|
| `isInvalid()` | 条目空闲，可分配 | `!state_valid` |
| `isValid()` | 条目有效（active 或 inflight） | `state_valid` |
| `isActive()` | 条目活跃，可合并但未开始写 DCache | `state_valid && !state_inflight` |
| `isInflight()` | 正在向 DCache 写入 | `state_inflight` |
| `isDcacheReqCandidate()` | 可作为 DCache 请求候选 | `state_valid && !state_inflight && !w_sameblock_inflight` |

### 2.3 状态转换图

```
                  alloc/merge
    [INVALID] ──────────────> [ACTIVE] ──── eviction fire ────> [INFLIGHT]
                                ^   ^                              |
                                |   |           hit_resp           |
                                |   +──────────────────────────────+
                                |                                  |
                                |          replay_resp             |
                                |              |                   |
                                |              v                   |
                                |         [INFLIGHT + w_timeout]   |
                                |              |                   |
                                |       missq_replay_timeout      |
                                |              |                   |
                                +──────────────+                   |
                                |                                  |
                                +─── coh_timeout ──> eviction ─────+
```

关键转换说明：

1. **INVALID -> ACTIVE**：新 store 请求分配到空闲条目，设置 `state_valid := true`。
2. **ACTIVE -> ACTIVE**：新的 store 合并到已有条目，`cohCount` 清零。
3. **ACTIVE -> INFLIGHT**：SBuffer 发出 DCache 写入请求，设置 `state_inflight := true`。
4. **INFLIGHT -> INVALID**：DCache 返回 hit response，清除 `state_valid` 和 `state_inflight`。
5. **INFLIGHT (w_timeout) -> INFLIGHT (w_timeout)**：DCache 返回 replay response，设置 `w_timeout := true`，`missqReplayCount` 清零，等待超时后重新发送。
6. **INFLIGHT (w_timeout) -> INVALID**：missq replay 超时后重新发送并收到 hit response。

---

## 3. Store 合并逻辑

### 3.1 合并条件判断

SBuffer 的核心优势之一是能够将写入同一物理地址（同一 cache block）的多条 store 操作合并到一个条目中。合并通过 ptag 匹配实现：

```scala
for(i <- 0 until EnsbufferWidth){
  mergeMask(i) := widthMap(j =>
    inptags(i) === ptag(j) && activeMask(j)
  )
}
```

合并的条件：
1. 新请求的 ptag 与已有条目的 ptag 相同（同一 cache block）。
2. 已有条目处于 ACTIVE 状态（`state_valid && !state_inflight`），即尚未开始向 DCache 写入。
3. **注意**：处于 INFLIGHT 状态的条目不会被作为合并目标，即使 ptag 匹配。

### 3.2 合并操作

当合并发生时（`canMerge(i)` 为 true），执行以下操作：

1. **数据写入**：通过 `SbufferData` 模块的 2 周期写入流水线，将新数据和 mask 写入已有条目的对应 word 位置。
2. **cohCount 清零**：重置该条目的 coherence timeout 计数器，因为新写入会延长该条目的生命周期。
3. **vtag 检查**：如果新请求的 vtag 与已有条目的 vtag 不同，说明存在 virtual-to-physical aliasing（同一物理地址被不同虚拟地址访问），此时触发 `merge_need_uarch_drain`，进入 microarchitectural drain 流程。

### 3.3 合并优先于分配

对于每个入端口，SBuffer 优先尝试合并而非分配新条目：

```
if (canMerge(i)):
    使用 mergeVec 将数据写入已有条目
else:
    分配新条目，调用 wordReqToBufLine()
```

### 3.4 多端口合并的处理

当两个入端口的请求都命中同一个 SBuffer 条目时（`sameTag` 为 true），第二个端口使用与第一个端口相同的 insert index：

```scala
val secondInsertIdx = Mux(sameTag,
  firstInsertIdx,
  Mux(~enbufferSelReg, evenInsertIdx, oddInsertIdx)
)
```

这确保了同一 cache block 的两条 store 不会分配两个不同条目。

---

## 4. Even/Odd 分配策略

### 4.1 策略动机

XiangShan SBuffer 采用 **Even/Odd 交替分配策略**，将 16 个条目分为偶数编号（0, 2, 4, ..., 14）和奇数编号（1, 3, 5, ..., 15）两组，交替选择分配目标。

这种设计的核心动机是：

1. **避免端口竞争**：当两个 store 端口的请求写入同一 cache block 时，如果使用简单的线性分配，两个端口可能竞争同一个空闲条目。奇偶交替分配确保两个端口使用不同组的条目，除非它们确实合并到同一个条目。
2. **提升并行度**：通过物理分离两个端口的分配路径，降低仲裁逻辑的复杂度。

### 4.2 实现机制

```scala
val enbufferSelReg = RegInit(false.B)
when(io.in.req(0).valid) {
  enbufferSelReg := ~enbufferSelReg
}
```

每次端口 0 收到有效请求后，`enbufferSelReg` 翻转。选择逻辑如下：

- `enbufferSelReg = true` 时：第一个端口使用 **偶数** 组的空闲条目，第二个端口使用 **奇数** 组。
- `enbufferSelReg = false` 时：第一个端口使用 **奇数** 组的空闲条目，第二个端口使用 **偶数** 组。

### 4.3 空闲掩码提取

通过 `GetEvenBits` 和 `GetOddBits` 工具函数，从总空闲掩码中分别提取偶数和奇数位：

```scala
val invalidMask = VecInit(stateVec.map(s => s.isInvalid()))
val evenInvalidMask = GetEvenBits(invalidMask.asUInt)
val oddInvalidMask = GetOddBits(invalidMask.asUInt)
```

然后通过 `getFirstOneOH` 找到每组中第一个空闲条目的 one-hot 编码，最终转换回全局索引：

```scala
val evenInsertIdx = Cat(evenRawInsertIdx, 0.U(1.W)) // 低位补 0 -> 偶数
val oddInsertIdx = Cat(oddRawInsertIdx, 1.U(1.W))   // 低位补 1 -> 奇数
```

### 4.4 合并情况下的特殊处理

当 `sameTag` 为 true 时（两个端口写入同一 cache block），第二个端口直接使用第一个端口已选择的条目进行合并，跳过奇偶分配逻辑：

```scala
val secondCanInsert = sbuffer_state =/= x_drain_sbuffer && Mux(sameTag,
  firstCanInsert,
  Mux(~enbufferSelReg, evenCanInsert, oddCanInsert)
)
```

---

## 5. 淘汰与 DCache 写回

### 5.1 SBuffer 全局状态机

SBuffer 的全局状态由四状态 FSM 控制：

```
x_idle ──[flush]────> x_drain_all ──[buf empty]──> x_idle
       ──[buf full]──> x_replace ──[dcache resp]──> x_idle
```

| 状态 | 语义 |
|------|------|
| `x_idle` | 空闲状态，正常接收 store 请求和 eviction |
| `x_replace` | 已达到 eviction 阈值，正在向 DCache 写回 |
| `x_drain_all` | 全量排空：排空 Store Queue 和 SBuffer，响应外部 flush |
| `x_drain_sbuffer` | 仅排空 SBuffer，阻塞 Store Queue 向 SBuffer 写入，响应 vtag/ptag aliasing |

### 5.2 Eviction 触发条件

```scala
val do_eviction = GatedValidRegNext(
  ActiveCount >= forceThreshold ||
  ActiveCount === (StoreBufferSize-1).U ||
  ValidCount === (StoreBufferSize).U,
  init = false.B
)
```

三种触发 eviction 的条件（OR 关系）：
1. **ActiveCount >= threshold**：活跃条目数达到阈值（默认 9，可通过 `force_write` 降低为 `threshold - base`）。
2. **ActiveCount = StoreBufferSize - 1**：仅剩 1 个空闲条目。
3. **ValidCount = StoreBufferSize**：所有条目都被占用（包括 inflight 条目）。

### 5.3 驱逐候选选择（PLRU）

SBuffer 使用 `ValidPseudoLRU` 算法选择 eviction 目标：

```scala
val plru = new ValidPseudoLRU(StoreBufferSize)
val candidateVec = VecInit(stateVec.map(s => s.isDcacheReqCandidate()))
val replaceIdx = plru.way(candidateVec.reverse)._2
```

选择条件：
- 候选条目必须满足 `isDcacheReqCandidate()`：`state_valid && !state_inflight && !w_sameblock_inflight`。
- `candidateVec.reverse` 将最低编号条目放在最高位，与 PLRU 的位序对齐。
- 仅在 `need_replace` 且非 drain/timeout 场景下更新 PLRU 访问历史。

### 5.4 驱逐优先级

驱逐目标的选择遵循以下优先级（从高到低）：

1. **missqReplayTimeOut**：已经历 replay 超时的条目最优先，因为它们阻塞了 DCache 的 MSHR 资源。
2. **drain（排空）**：当处于 drain 状态时，按优先级从低到高排空所有活跃条目。
3. **cohTimeOut**：coherence timeout 的条目，需要定期刷新以维护一致性。
4. **replace（PLRU）**：正常 eviction 流程中使用 PLRU 选择。

```scala
val sbuffer_out_s0_evictionIdx = Mux(missqReplayHasTimeOut,
  missqReplayTimeOutIdx,
  Mux(need_drain,
    drainIdx,
    Mux(cohHasTimeOut, cohTimeOutIdx, replaceIdx)
  )
)
```

### 5.5 SBuffer-to-DCache 写入流水线

写入 DCache 的流水线分为两个阶段：

**Stage S0**：
- 读取 SBuffer 的 data 和 meta（ptag, vtag）。
- 将条目状态设置为 `inflight`。
- `RegNext` 数据和 meta。
- 检查写入冲突（`blockDcacheWrite`）。

**Stage S1**：
- 向 DCache 发送写请求（`M_XWR` 命令）。
- 请求中包含 ptag 构造的物理地址、data、mask、以及条目 ID。
- 当 DCache ready 且无写冲突时发出请求。

**Stage Extra（DCache Response）**：
- **Hit response**：清除条目的 `state_valid` 和 `state_inflight`，释放条目。同时触发 mask flush，清除该条目的所有 mask 位。
- **Replay response**：设置 `w_timeout := true`，`missqReplayCount` 清零，等待超时后重试。

### 5.6 写冲突避免

```scala
val shouldWaitWriteFinish = GatedValidRegNext(VecInit(
  (0 until EnsbufferWidth).map{i =>
    (writeReq(i).bits.wvec.asUInt & UIntToOH(sbuffer_out_s0_evictionIdx).asUInt).orR &&
    writeReq(i).valid
  }
).asUInt.orR)
```

当 SBuffer 正在向某个条目写入新数据（来自 store 端口），而该条目同时被选为 eviction 目标时，`blockDcacheWrite` 信号会阻塞 DCache 写入，等待数据写入完成。

---

## 6. Load-Store Forwarding 机制

### 6.1 三级流水线架构

SBuffer 的 Load Forward 采用三级流水线设计，接口定义为 `SbufferForward`：

| 阶段 | 信号 | 功能 |
|------|------|------|
| S0 | `s0Req` (ValidIO) | 发送虚拟地址（vaddr）进行 tag 匹配 |
| S1 | `s1Req` (paddr) | 发送物理地址（paddr）进行精确匹配 |
| S1 | `s1Kill` | kill 信号，清除 S0 的流水线状态 |
| S2 | `s2Resp` (ValidIO) | 返回转发结果（forwardMask, forwardData, matchInvalid） |

### 6.2 VTag/PTag 分阶段匹配

Forward 的核心挑战在于：在 S0 阶段只有虚拟地址可用，而物理地址要到 S1 阶段才能从 TLB 获取。因此匹配分为两阶段：

**S0 -> S1：Virtual Tag 匹配**

```scala
val vtag_matches = VecInit(widthMap(w => vtag(w) === getVTag(s1Req.vaddr)))
```

在 S1 阶段，使用 S0 的 vaddr（RegNext）与所有条目的 vtag 进行比较。

**S1 -> S2：Physical Tag 匹配**

```scala
val ptag_matches = VecInit(widthMap(w =>
  RegEnable(ptag(w), s1ReqValid) === RegEnable(getPTag(s1Paddr), s1ReqValid)
))
```

使用 TLB 返回的 paddr（在 S1 阶段可用）与 ptag 进行比较。

### 6.3 Tag Mismatch 检测

```scala
val tag_mismatch = RegNext(s1ReqValid) && VecInit(widthMap(w =>
  GatedValidRegNext(vtag_matches(w)) =/= ptag_matches(w) &&
  GatedValidRegNext((activeMask(w) || inflightMask(w)))
)).asUInt.orR && !RegEnable(s1Kill, s1ReqValid)
```

如果某个条目的 vtag 匹配但 ptag 不匹配，说明存在 **vtag/ptag aliasing**——同一虚拟地址映射到了不同的物理地址，且 SBuffer 中缓存的是旧的物理地址对应的数据。此时：
1. 设置 `forward_need_uarch_drain`，触发 microarchitectural drain。
2. `s2Resp.matchInvalid` 被置为 true，通知 Load 流水线 forward 结果不可信，需要重试。

### 6.4 Forward 数据选择

在 S2 阶段，使用 `Mux1H` 从匹配的条目中选择数据：

```scala
val selectedValidMask = Mux1H(valid_tag_match_reg, forward_mask_candidate_reg)
val selectedValidData = Mux1H(valid_tag_match_reg, forward_data_candidate_reg)
val selectedInflightMask = Mux1H(inflight_tag_match_reg, forward_mask_candidate_reg)
val selectedInflightData = Mux1H(inflight_tag_match_reg, forward_data_candidate_reg)
```

**优先级规则**：Active 条目的优先级高于 Inflight 条目。对于每个字节，先检查 Inflight 条目的 mask，如果 Active 条目也有匹配，则覆盖 Inflight 的结果：

```scala
for (j <- 0 until VDataBytes) {
  s2Resp.forwardMask(j) := false.B
  when(selectedInflightMask(j)) {
    s2Resp.forwardMask(j) := true.B
    s2Resp.forwardData(j) := selectedInflightData(j)
  }
  when(selectedValidMask(j)) {
    s2Resp.forwardMask(j) := true.B
    s2Resp.forwardData(j) := selectedValidData(j)
  }
}
```

这种设计确保了：即使某个条目正在向 DCache 写入（inflight），如果另一个 active 条目包含更新的数据，后者的数据会被优先使用。

---

## 7. Coherence Timeout 与 Replay 机制

### 7.1 Coherence Timeout（一致性超时）

SBuffer 为每个条目维护一个 `cohCount` 计数器，用于防止条目在 SBuffer 中驻留过久导致一致性问题：

```scala
val cohTimeOutMask = VecInit(widthMap(i =>
  cohCount(i) >= io.csrCtrl.sbuffer_timeout && stateVec(i).isActive()
))
```

计数器行为：
- 新分配或合并时：`cohCount := 0`
- 每个周期：`cohCount += 1`（仅对 active 条目且未超时的条目）
- 当 `cohCount >= sbuffer_timeout`（CSR 可配置，默认约 2^22 周期）：触发 coherence timeout eviction

超时后，该条目被优先发送到 DCache，确保 store 数据不会无限期驻留在 SBuffer 中，从而维护多核一致性协议的正确性。

### 7.2 Miss Queue Replay 机制

当 SBuffer 向 DCache 发送写请求时，如果 DCache 返回 replay response（而非 hit response），说明存在以下情况之一：
- DCache 的 MSHR 资源不足，需要等待。
- 存在 cache line 冲突。

Replay 处理流程：

```scala
when (io.dcache.replay_resp.fire) {
  missqReplayCount(replay_resp_id) := 0.U
  stateVec(replay_resp_id).w_timeout := true.B
}
```

1. 条目进入 `w_timeout` 状态。
2. `missqReplayCount` 从 0 开始递增。
3. 当 `missqReplayCount` 的最高位变为 1（即计数达到 2^(MissqReplayCountBits-1)，约 16 个周期），触发 replay timeout。
4. 超时后，该条目被重新发送到 DCache。

```scala
val missqReplayTimeOutMask = VecInit(widthMap(i =>
  missqReplayCount(i)(MissqReplayCountBits - 1) && stateVec(i).w_timeout
))
```

这个延迟机制避免了在 DCache 繁忙时反复重试浪费带宽，同时确保数据最终一定会被写回。

### 7.3 Replay 的 DCache 请求优先级

在驱逐选择逻辑中，`missqReplayHasTimeOut` 的优先级最高：

```scala
val sbuffer_out_s0_valid = missqReplayHasTimeOut ||
  stateVec(sbuffer_out_s0_evictionIdx).isDcacheReqCandidate() &&
  (need_drain || cohHasTimeOut || need_replace)
```

这确保了已经等待超时的 replay 请求能够立即获得处理机会。

---

## 8. Store Prefetch (SPB) 集成

### 8.1 整体架构

SBuffer 集成了 Store Prefetch 机制，由 `StorePfWrapper` 模块管理，包含两个子模块：

1. **`Serializer`**：FIFO 队列（深度 12），将来自 SBuffer 入端口的 store 请求序列化，确保按序输出给 SPB 模块。
2. **`StorePrefetchBursts`（SPB）**：检测连续 store 模式，生成 burst prefetch 请求发送到 DCache。

### 8.2 Serializer 模块

```scala
class Serializer {
  val QueueSize = SERIALIZER_SIZE  // 12
  // FIFO 队列，按序输出 store 请求给 SPB
}
```

由于 SBuffer 支持每周期 2 个 store 请求入端口（`EnsbufferWidth = 2`），但 SPB 模块每周期只能处理 1 个请求，Serializer 起到速率匹配和保序的作用。

### 8.3 SPB 突发检测

SPB 通过统计连续 store 操作的 cache block 地址差值来检测连续写入模式：

```scala
val store_count = RegInit(0.U((log2Up(N) + 1).W))  // N = SPB_N = 48
val saturate_counter = RegInit(0.S(SATURATE_COUNTER_BITS.W))  // 7-bit signed
```

检测逻辑：
1. 每次收到 store 请求，`store_count` 加 1，`saturate_counter` 加上当前地址与上一次地址的 block 级差值。
2. 当 `store_count > N`（超过 48 次 store）且 `saturate_counter / 8 == saturate_counter`（地址连续性检查）时，触发 burst。
3. 触发后，`store_count` 和 `saturate_counter` 清零。

`saturation_counter` 的机制用于检测 store 是否在地址空间中连续递增。如果 store 模式是 memset-like 的连续写入，counter 的值会与 `store_count / 8` 对齐，从而通过 `can_burst` 检查。

### 8.4 Burst Prefetch 生成器

```scala
class PrefetchBurstGenerator(is_store: Boolean) {
  val SIZE = BURST_ENGINE_SIZE  // 2
  // 维护 2 个 burst engine 条目，每个可连续发出 prefetch 请求
}
```

当 SPB 触发 burst 时，将当前 store 地址分配到 `PrefetchBurstGenerator` 的空闲条目中。每个条目可以连续发出 prefetch 请求：
- 每个周期最多发出 2 个 prefetch 请求（一个当前地址，一个下一块地址）。
- 跨页时自动失效该条目，避免跨页 prefetch。
- 通过页面过滤（`filter_by_page_addr`）避免对同一页面重复 prefetch。

### 8.5 与 SBuffer 的集成

SPB 在以下时机被训练和触发：

```scala
if (EnableStorePrefetchSPB) {
  prefetcher.io.sbuffer_enq(i).valid := io.in.req(i).fire && io.in.req(i).bits.vecValid
  prefetcher.io.sbuffer_enq(i).bits.vaddr := io.in.req(i).bits.vaddr
}
```

当 `EnableStorePrefetchAtCommit` 启用时，SBuffer 还会在 store commit 时直接触发 prefetch：

```scala
io.store_prefetch(i).valid := prefetcher.io.prefetch_req(i).valid ||
  (io.in.req(i).fire && io.in.req(i).bits.vecValid && io.in.req(i).bits.prefetch)
```

这种设计兼顾了两种预取策略：
- **At-commit prefetch**：在 store 提交时触发，准确但延迟较高。
- **SPB burst prefetch**：检测模式后批量预取，适合 memset 等连续写入场景。

---

## 9. Microarchitectural Drain（微架构排空）

### 9.1 触发条件

Microarchitectural drain 是一种保护机制，当检测到 vtag/ptag aliasing 时触发，确保 SBuffer 中的数据一致性：

```scala
val forward_need_uarch_drain = WireInit(false.B)
val merge_need_uarch_drain = WireInit(false.B)
val do_uarch_drain = GatedValidRegNext(forward_need_uarch_drain) ||
  GatedValidRegNext(GatedValidRegNext(merge_need_uarch_drain))
```

触发来源：

1. **Forward Mismatch**（`forward_need_uarch_drain`）：在 Load Forward 过程中，vtag 匹配但 ptag 不匹配，说明 SBuffer 中的条目对应的物理地址已经发生变化（TLB 重新映射），但 SBuffer 仍持有旧地址的数据。

2. **Merge vtag Mismatch**（`merge_need_uarch_drain`）：在 store 合并时，新请求的 vtag 与已有条目的 vtag 不同但 ptag 相同，说明同一物理地址被不同的虚拟地址访问，存在潜在的别名问题。

### 9.2 排空流程

当 `do_uarch_drain` 触发时，SBuffer 状态转换为 `x_drain_sbuffer`：

```scala
is(x_idle){
  when(do_uarch_drain){
    sbuffer_state := x_drain_sbuffer
  }
}
```

在 `x_drain_sbuffer` 状态下：
1. **阻塞 Store Queue**：`firstCanInsert` 和 `secondCanInsert` 均被阻塞（`sbuffer_state =/= x_drain_sbuffer` 条件不满足）。
2. **排空 SBuffer**：逐个将 active 条目发送到 DCache，直到所有条目都被清除。
3. **退出条件**：当 `sbuffer_empty`（所有条目无效且 MSHR 中无 store）时，返回 `x_idle`。

### 9.3 延迟处理

`merge_need_uarch_drain` 经过了两级 `GatedValidRegNext` 延迟，这是因为合并操作的 vtag 检查发生在 S1 阶段，需要等待流水线完成。而 `forward_need_uarch_drain` 只经过一级延迟。

### 9.4 与 SBuffer Flush 的关系

在 `x_drain_sbuffer` 状态下，如果收到外部 flush 信号，会升级为 `x_drain_all`：

```scala
is(x_drain_sbuffer){
  when(io.flush.valid){
    sbuffer_state := x_drain_all
  }
}
```

这确保了 flush 的优先级高于 uarch drain。

---

## 10. SBufferData 模块详解

### 10.1 数据存储结构

```scala
class SbufferData {
  val data = Reg(Vec(StoreBufferSize, Vec(CacheLineVWords, Vec(VDataBytes, UInt(8.W)))))
  val mask = RegInit(VecInit(Seq.fill(StoreBufferSize)(
    VecInit(Seq.fill(CacheLineVWords)(VecInit(Seq.fill(VDataBytes)(false.B))))
  )))
}
```

每个 SBuffer 条目存储一整条 cache line 的数据和写入 mask。数据组织为三维数组：`[条目][VLEN-word][字节]`。

### 10.2 2 周期写入流水线

SbufferData 的写入采用 2 周期流水线：

- **S1 阶段**：捕获写入请求的 `wvec`（写入向量）、`data`、`mask`、`vwordOffset`、`wline` 信号，存入写缓冲寄存器。
- **S2 阶段**：使用写缓冲寄存器中的值，更新对应条目对应 word 的 data 和 mask。

这种 2 周期延迟是关键的时序考量，因为它需要在 SBuffer 的元数据更新（S1 阶段完成）和实际数据写入（S2 阶段完成）之间保持一致。如果在 S1 完成元数据更新后立即有 eviction 选中同一条目，`blockDcacheWrite` 信号会阻塞 DCache 写入，等待 S2 的数据更新完成。

### 10.3 Mask Flush

当 DCache hit response 返回时，对应的 SBuffer 条目被释放，其 mask 需要被清除：

```scala
for(line <- 0 until StoreBufferSize){
  val line_mask_clean_flag = GatedValidRegNext(
    io.maskFlushReq.map(a => a.valid && a.bits.wvec(line)).reduce(_ || _)
  )
  when(line_mask_clean_flag){
    for(word <- 0 until CacheLineVWords){
      for(byte <- 0 until VDataBytes){
        mask(line)(word)(byte) := false.B
      }
    }
  }
}
```

Mask flush 经过 1 周期延迟（`GatedValidRegNext`），确保在数据被 DCache 接受后才清除 mask，避免数据丢失。

---

## 11. 关键源文件位置

| 文件路径 | 功能说明 |
|----------|----------|
| `src/main/scala/xiangshan/mem/sbuffer/Sbuffer.scala` | SBuffer 主模块，包含核心状态机、合并逻辑、eviction、forward、dcmache 写入流水线 |
| `src/main/scala/xiangshan/mem/sbuffer/FakeSbuffer.scala` | 简化版 SBuffer（过时），仅保留 1 条目，用于早期测试 |
| `src/main/scala/xiangshan/mem/sbuffer/DatamoduleResultBuffer.scala` | 通用 datamodule 结果缓冲器，FIFO 结构的 enqueue/dequeue 缓冲 |
| `src/main/scala/xiangshan/mem/sbuffer/StorePrefetchBursts.scala` | Store Prefetch 模块，包含 Serializer、StorePrefetchBursts、PrefetchBurstGenerator、StorePfWrapper |
| `src/main/scala/xiangshan/Parameters.scala` (行 164-166) | SBuffer 参数定义：`StoreBufferSize=16`, `StoreBufferThreshold=9`, `EnsbufferWidth=2` |
| `src/main/scala/xiangshan/mem/Bundles.scala` (行 211-244) | SBuffer Forward 接口定义：`SbufferForwardResp`, `SbufferForward`, `SbufferForwardReq` |
| `src/main/scala/xiangshan/Bundle.scala` (行 596-597) | CSR 控制信号：`sbuffer_timeout`, `sbuffer_threshold` |
| `src/main/scala/xiangshan/cache/dcache/DCacheWrapper.scala` | DCache 与 SBuffer 的连接，`force_write` 信号定义 |
| `src/main/scala/xiangshan/mem/lsqueue/LSQWrapper.scala` | Store Queue 控制 SBuffer 的 `force_write` 信号 |
| `src/main/scala/xiangshan/mem/MemBlock.scala` (行 1124) | MemBlock 中连接 `force_write` 信号到 DCache |

---

## 12. 总结与设计亮点

XiangShan 的 SBuffer 设计体现了以下微架构设计亮点：

1. **高效合并**：通过 ptag 匹配实现 cache-line 级别的 store 合并，显著减少 DCache 写入次数。每个 SBuffer 条目对应一整条 cache line，不同字偏移的 store 操作可以合并到同一条目。

2. **奇偶分配策略**：巧妙地通过 `enbufferSelReg` 翻转实现两个 store 端口的物理分离，降低了端口竞争概率，同时保持了硬件实现的简洁性。

3. **三级流水 Forward**：通过 vtag（S0/S1）和 ptag（S1/S2）的分阶段匹配，在 TLB 延迟不可避免的前提下实现了高效的 Load-Store Forwarding。

4. **多级排空保护**：coherence timeout、missq replay timeout、microarchitectural drain 三重机制共同确保了 SBuffer 不会因为异常情况（如 TLB 重映射、DCache 繁忙）而导致数据一致性问题。

5. **2 周期写入流水线与写冲突避免**：SbufferData 的 2 周期写入流水线通过 `blockDcacheWrite` 信号与 eviction 路径协调，确保数据一致性。

6. **SPB 模式检测**：通过 saturation counter 机制检测连续 store 模式，自动触发 burst prefetch，对 memset 等应用场景有显著性能提升。

7. **可配置的阈值控制**：通过 Constantin（可运行时修改的常量）机制，`threshold` 和 `base` 参数可在不重新综合的情况下调整，支持在功耗和性能之间动态权衡。
