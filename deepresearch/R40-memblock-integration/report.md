# R40 -- MemBlock Integration 深度分析

## 1. 概述 (Overview)

MemBlock 是香山 (XiangShan) 处理器中负责**所有访存操作**的顶层模块，包含约 1644 行 Scala/Chisel 代码。它不仅是数据缓存 (L1 DCache) 的宿主，还集成了 Load Unit、Store Unit、Load/Store Queue (LSQ)、Store Buffer (Sbuffer)、预取引擎 (Prefetcher)、地址翻译 (DTLB)、页表遍历 (PTW)、原子操作单元 (AtomicsUnit) 以及向量访存通路等所有访存子系统。

在物理层次结构中，MemBlock 位于 Top (L2Top) 与 Backend 之间，同时承载前端 (Frontend) 的 ITLB 请求转发。所有从外部进入核心的中断信号、L2 hint、以及 MSI 消息也经过 MemBlock 路由到 Backend。

**源文件**: `src/main/scala/xiangshan/mem/MemBlock.scala`

---

## 2. MemBlock 顶层架构 (Top-Level Architecture)

MemBlock 采用 `LazyModule` 嵌套结构，外层 `MemBlock` (不可内联) 包裹内层 `MemBlockInlined` (标记为 `shouldBeInlined = true`):

```scala
class MemBlock()(implicit p: Parameters) extends LazyModule {
  val inner = LazyModule(new MemBlockInlined())
  lazy val module = new MemBlockImp(this)
}
```

`MemBlockInlined` 负责实例化所有子模块，其内部层级关系为:

```
MemBlockInlined
  +-- dcache (DCacheWrapper)          -- L1 Data Cache
  +-- uncache (Uncache)                -- 非缓存访问通路
  +-- ptw (L2TLBWrapper)              -- Page Table Walker
  +-- ptw_to_l2_buffer (TLBuffer)     -- PTW 到 L2 的 TileLink 缓冲
  +-- l1d_to_l2_buffer (TLBuffer)     -- DCache 到 L2 的 TileLink 缓冲
  +-- frontendBridge (FrontendBridge) -- 前端 ICache/ITLB 总线桥
  +-- 5 个中断 sink node               -- 外部中断接入
  +-- 内部 RTL 实例化 (通过 Module()):
       +-- newLoadUnits (3 个)         -- 整数 Load 流水线
       +-- storeUnits (2 个)           -- 整数 Store 地址流水线
       +-- stdExeUnits (2 个)          -- Store 数据执行单元
       +-- atomicsUnit (1 个)          -- LR/SC/AMO 原子操作
       +-- lsq (LsqWrapper)            -- Load/Store Queue 封装
       +-- sbuffer (Sbuffer)           -- Store Buffer
       +-- prefetcher (PrefetcherWrapper) -- 硬件预取引擎
       +-- dtlb_ld / dtlb_st / dtlb_prefetch -- DTLB 三组实例
       +-- pmp + pmp_checkers          -- 物理内存保护
       +-- dtlbRepeater                -- DTLB 到 PTW 的请求过滤
       +-- exceptionInfoGen            -- 异常信息生成器
       +-- vSegmentUnit / vlSplit / vsSplit / vlMergeBuffer / vsMergeBuffer / vfofBuffer -- 向量访存通路
```

### 2.1 TileLink 总线拓扑

MemBlock 通过以下 TileLink 端口与 L2/SoC 交互:

| 端口 | 方向 | 说明 |
|------|------|------|
| `dcache_port` (dcache_client) | 出 | L1 DCache 请求，经 `l1d_to_l2_buffer` 连接 |
| `uncache_port` | 出 | 非缓存请求 (MMIO/NC)，经 `uncache_xbar` 仲裁 |
| `ptw_to_l2_buffer` | 出 | Page Table Walk 请求 |
| `icache` / `icachectrl` / `instr_uncache` | 出/入 | 前端 ICache 的 TileLink 缓冲 (三级 triple buffer) |
| `l2_pf_sender_opt` | 出 | L2 预取请求发送器 (BundleBridgeSource) |

uncache 内部拓扑:
```
uncache.clientNode --> TLBuffer --> uncache_xbar --> TLBuffer.chainNode(2) --> uncache_port
                                                      |
                                        dcache.uncacheNode (MMIO 通路)
```

---

## 3. LoadUnit / StoreUnit 实例化与连接 (Load & Store Unit Instantiation)

### 3.1 核心参数

从 `HasMemBlockParameters` trait 中提取关键计数:

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `LduCnt` | 3 | 整数 Load 流水线数量 |
| `StaCnt` | 2 | 整数 Store 地址流水线数量 |
| `StdCnt` | 2 | Store 数据执行单元数量 |
| `HyuCnt` | 0 | Hybrid 单元数量 (当前不支持) |
| `VlduCnt` | 2 | 向量 Load 流水线数量 |
| `VstuCnt` | 2 | 向量 Store 流水线数量 |

### 3.2 整数 LoadUnit (NewLoadUnit) 实例化

```scala
val newLoadUnits = Seq.tabulate(LduCnt)(i => Module(new NewLoadUnit(ldaParams(i))))
```

每个 `NewLoadUnit` 内部包含 **5 级流水线** (S0-S4) 加一个 DataPath 模块:

- **S0**: 接收 replay/prefetch/vecldin/ldin 输入，发起 TLB 请求和 DCache 访问
- **S1**: 接收 TLB 响应，生成物理地址，发起 store forward 请求
- **S2**: DCache 响应到达，完成 PMP 检查，执行 RAR/RAW 冲突检测
- **S3**: 生成最终 load 结果，写回 (writeback) 到 Backend，写入 LSQ
- **S4**: 处理 uncache bypass 和对齐操作

**关键连接 (LoadUnit <-> MemBlock)**:

| 连接目标 | 方向 | 信号 |
|----------|------|------|
| Issue Queue | 出 | `io.ldin <> issueLda(i)` -- 从 Backend 接收发射指令 |
| DCache | 出/入 | `dcache.io.lsu.load(i) <> newLoadUnits(i).io.dcache` |
| DTLB | 出/入 | `newLoadUnits(i).io.tlb <> dtlb_reqs.take(LduCnt)(i)` |
| PMP | 入 | `newLoadUnits(i).io.pmp <> pmp_check(i).resp` |
| LSQ (forward) | 入 | `lsq.io.forward(i) <> newLoadUnits(i).io.sqForward` |
| SBuffer (forward) | 入 | `sbuffer.io.forward(i) <> newLoadUnits(i).io.sbufferForward` |
| Uncache (forward) | 入 | `uncache.io.forward(i) <> newLoadUnits(i).io.uncacheForward` |
| LSQ (RAW/RAR) | 出 | `lsq.io.ldu.rawNukeQuery(i) / rarNukeQuery(i)` |
| LSQ (replay) | 入 | `newLoadUnits(i).io.replay <> lsq.io.replay(i)` |
| LSQ (ldin) | 出 | `lsq.io.ldu.ldin(i) <> newLoadUnits(i).io.lqWrite` |
| Prefetcher | 出 | `io.prefetchTrainHintS1/S2`, `io.prefetchTrain` |
| CSR Control | 入 | `newLoadUnits(i).io.csrCtrl <> csrCtrl` |
| ST-Nuke Query | 入 | `newLoadUnits(i).io.staNukeQueryReq <> storeUnits.map(_.io.staNukeQueryReq)` |
| Writeback | 出 | `writebackLda(i)` -- 写回 Rob/PRF |
| Cancel | 出 | `io.mem_to_ooo.ldCancel(i)` |
| Wakeup | 出 | `io.mem_to_ooo.wakeup(i)` |

**LoadUnit 0 特殊处理**: `dcache.io.lsu.load(0)` 被 `vSegmentUnit` 和 `newLoadUnits(0)` 共享。当 `vSegmentFlag` 为 true 时，DCache 端口被 vSegmentUnit 占用，LoadUnit 0 的 DCache 请求被阻塞 (`req.ready := false.B`)。LoadUnit 0 的 TLB 端口同样被 vSegmentUnit 分时复用。

### 3.3 整数 StoreUnit (NewStoreUnit) 实例化

```scala
val storeUnits = Seq.tabulate(StaCnt)(i => Module(new NewStoreUnit(staParams(i))))
val stdExeUnits = Seq.tabulate(StdCnt)(i => Module(new StdExeUnit(stdParams(i))))
```

`NewStoreUnit` 同样采用 **5 级流水线** (S0-S4):

- **S0**: 发起 TLB 请求和 DCache 地址查询
- **S1**: TLB 响应到达，完成地址翻译，触发 LFST 更新
- **S2**: DCache 响应 (确认是否 hit)，执行 PMP 检查
- **S3**: 生成最终 store 结果，写回到 Rob
- **S4**: 处理 unalign 和对齐操作

**关键连接 (StoreUnit <-> MemBlock)**:

| 连接目标 | 方向 | 信号 |
|----------|------|------|
| Issue Queue | 出 | `stu.io.stin <> issueSta(i)` |
| DCache | 出/入 | `stu.io.dcache <> dcache.io.lsu.sta(i)` |
| DTLB | 出/入 | `stu.io.tlb <> dtlb_st.head.requestor(i)` |
| PMP | 入 | `pmp_check(TlbStartVec(dtlb_st_idx) + i).resp` |
| LSQ (store addr) | 出 | `stu.io.toSqAddr <> lsq.io.sta.storeAddrIn(i)` |
| LSQ (store addr re) | 出 | `stu.io.toSqAddrRe <> lsq.io.sta.storeAddrInRe(i)` |
| SBuffer (prefetch) | 出 | `stu.io.prefetchReq <> sbuffer.io.store_prefetch(i)` |
| Feedback | 出 | `io.mem_to_ooo.staIqFeedback(i).feedbackSlow` |
| LFST | 出 | `io.mem_to_ooo.updateLFST(i) := stu.io.updateLFST` |
| Trigger | 入 | `tdata`, `tEnable` 等 trigger 控制信号 |
| Writeback | 出 | `writebackSta(i)` |

### 3.4 Store Data 单元 (StdExeUnit)

```scala
stdExeUnits(i).io.in <> issueStd(i)  // 接收 std issue
lsq.io.std.storeDataIn(i) := stdExeUnits(i).io.sqData  // 数据写入 SQ
```

`Std` 是一个简单的直通模块，将 `issue.data.src(0)` 传递到 `out.bits.res.data`，延迟极低。

### 3.5 Writeback 分配策略

整数 Load 写回端口的分配逻辑:
- **Port 0 (AtomicWBPort)**: 优先输出 `atomicsUnit.io.out`，仅当 atomics 无输出时才输出 `newLoadUnits(0).io.ldout`
- **Port 1 (MisalignWBPort)**: 无特殊用途，正常连接 LoadUnit
- **Port 2 (UncacheWBPort)**: 无特殊用途，正常连接 LoadUnit

整数 Store 写回端口:
- **Port 0**: 通过 `sqStoutLatch` 在 `storeUnits(0).io.stout` 和 `lsq.io.mmioStout` 之间选择 (NewPipelineConnect 缓冲)
- **Port 1**: 直接连接 `storeUnits(1).io.stout`

### 3.6 原子操作单元 (AtomicsUnit)

```scala
val atomicsUnit = Module(new AtomicsUnit(mouParam))
```

AtomicsUnit 采用 **11 状态 FSM** 处理 LR/SC/AMO 指令，是整个 MemBlock 中最复杂的单个状态机:

```
s_invalid -> s_tlb_and_flush_sbuffer_req -> s_pm -> s_wait_flush_sbuffer_resp
-> s_cache_req -> s_cache_resp -> s_cache_resp_latch -> s_finish -> s_finish2
-> s_extra_wb2 -> s_extra_wb
```

**特殊行为**:
- AtomicsUnit 在执行期间会**接管** Issue Queue 中 StorePort 0 的指令 (`issueSta(i).ready := atomicsUnit.io.in.ready`)
- 复用 **LoadUnit 0 的 TLB 端口** (`amoTlb = dtlb_ld(0).requestor(0)`)
- 原子执行期间**禁用所有硬件预取**
- 通过 `sbuffer.io.flush` 在执行前排空 SBuffer
- 写回占用 Load 写回端口 0 (AtomicWBPort)
- 支持 AMOCAS.Q 需要最多 4 个 std uop 和 2 个 sta uop

---

## 4. LSQ 集成 (Load Queue + Store Queue)

### 4.1 LsqWrapper 封装

```scala
val lsq = Module(new LsqWrapper)
```

`LsqWrapper` 内部实例化两个独立子模块:
- `loadQueue = Module(new LoadQueue)` -- 负责 Load 指令的生命周期管理
- `storeQueue = Module(new NewStoreQueue)` -- 负责 Store 指令的地址/数据管理

### 4.2 入队 (Enqueue) 逻辑

```scala
io.enq <> io.ooo_to_mem.enqLsq  // 来自 Backend 的 dispatch 信号

// canAccept 由 LQ 和 SQ 共同决定
io.enq.canAccept := loadQueue.io.enq.canAccept && storeQueue.io.enq.canAccept

// LQ/SQ 相互感知对方的状态
loadQueue.io.enq.sqCanAccept := storeQueue.io.enq.canAccept
storeQueue.io.enq.lqCanAccept := loadQueue.io.enq.canAccept
```

每条指令同时向 LQ 和 SQ 请求分配 slot:
```scala
loadQueue.io.enq.req(i).bits.sqIdx := storeQueue.io.enq.resp(i).sqIdx  // LQ 记录对应的 SQ 指针
```

### 4.3 Load Queue 与 Store Queue 的交互

LQ 通过 `loadQueue.io.sq` 接口获取 SQ 的关键状态:
- `stAddrReadySqPtr` / `stAddrReadyVec` -- Store 地址已就绪的指针和位图
- `stDataReadySqPtr` / `stDataReadyVec` -- Store 数据已就绪的指针和位图
- `stIssuePtr` -- Store 发射指针
- `sqEmpty` / `sqDeqPtr` -- SQ 空状态和出队指针

### 4.4 Load Queue 关键接口

| 接口 | 方向 | 说明 |
|------|------|------|
| `ldu.rawNukeQuery` | 入 | Load S2 阶段发起的 RAW 冲突查询 |
| `ldu.rarNukeQuery` | 入 | Load S2 阶段发起的 RAR 冲突查询 |
| `ldu.ldin` | 入 | Load S3 阶段写入 LQ (来自 `newLoadUnits.io.lqWrite`) |
| `replay` | 出 | 重放请求，连接到 LoadUnit 的 replay 端口 |
| `forward` | 入 | Load forward 查询 |
| `bypass` | 入 | Uncache bypass 信息 |
| `nuke_rollback` | 出 | 内存违反导致的回滚 redirect |
| `nack_rollback` | 出 | NCache nack 导致的回滚 redirect |
| `mdpTrain` | 出 | MDP (Memory Dependence Prediction) 训练信号 |

### 4.5 Store Queue 关键接口

| 接口 | 方向 | 说明 |
|------|------|------|
| `storeAddrIn` | 入 | 来自 StoreUnit S1 的地址写入 |
| `storeAddrInRe` | 入 | 来自 StoreUnit S2 的地址确认 |
| `storeDataIn` | 入 | 来自 StdExeUnit 的数据写入 |
| `writeToSbuffer` | 出 | 提交到 SBuffer 的数据 |
| `forward` | 入 | Load forward 查询 (SQ 部分) |
| `mmioStout` | 出 | MMIO Store 的写回 |
| `toUncacheBuffer` | 出 | 非缓存写请求 |
| `unalignQueueReq` | 入/出 | 非对齐地址的处理 |

### 4.6 Uncache 请求仲裁

LsqWrapper 内部实现了一个简单的仲裁器，在 Load 和 Store 的 uncache 请求之间选择:

```scala
val selectLq = loadQueue.io.uncache.req.valid && !storeQueue.io.toUncacheBuffer.req.valid ||
               (loadQueue.io.uncache.req.valid && storeQueue.io.toUncacheBuffer.req.valid &&
                loadQueue.io.uncache.req.bits.robIdx < storeQueue.io.toUncacheBuffer.req.bits.robIdx)
```

遵循**程序顺序** (robIdx 较小者优先) 保证正确性。

### 4.7 Violation Rollback 集成

MemBlock 将所有潜在的内存违反来源汇总，选择最老的 redirect:
```scala
val allRedirect = newLoadUnits.map(_.io.rollback) ++ lsq.io.nack_rollback ++ lsq.io.nuke_rollback
val oldestOneHot = Redirect.selectOldestRedirect(allRedirect)
val oldestRedirect = WireDefault(Mux1H(oldestOneHot, allRedirect))
// 禁用 IAF/IPF/IGPF (内存违反不会触发这些异常)
oldestRedirect.bits.backendIAF := false.B
oldestRedirect.bits.backendIPF := false.B
oldestRedirect.bits.backendIGPF := false.B
io.mem_to_ooo.memoryViolation := oldestRedirect
```

---

## 5. SBuffer 与 UncacheBuffer 布局

### 5.1 SBuffer (Store Buffer)

```scala
val sbuffer = Module(new Sbuffer)
```

SBuffer 是一个 **Write Buffer**，在 Store Queue 提交和 L1 DCache 之间提供缓冲。其主要特性:

- **存储结构**: `StoreBufferSize` 条目，每条目包含 ptag、vtag、data、mask
- **替换策略**: Pseudo-LRU (`ValidPseudoLRU`)
- **4 状态 FSM**: `x_idle`, `x_replace`, `x_drain_all`, `x_drain_sbuffer`
- **3 阶段入队**: s0 (从 SQ 读取) -> s1 (更新 SBuffer 元数据) -> s2 (准备 cacheline 写入)
- **超时驱逐**: 基于 `cohCount` 和 `csrCtrl.sbuffer_timeout` 的一致性超时机制

**SBuffer <-> MemBlock 连接**:

| 接口 | 方向 | 说明 |
|------|------|------|
| `io.in` | 入 | `lsq.io.sbuffer <> sbuffer.io.in` -- SQ 提交数据 |
| `io.dcache` | 出/入 | `sbuffer.io.dcache <> dcache.io.lsu.store` -- 写入 DCache |
| `io.forward` | 入 | LoadUnit 的 sbuffer forward 查询 |
| `io.store_prefetch` | 出 | Store 预取请求到 DCache |
| `io.flush` | 入 | 来自 fence/atomics/CMO 的刷写信号 |
| `io.sqempty` | 入 | SQ 空状态 |
| `io.force_write` | 入 | 来自 `lsq.io.force_write` |
| `io.csrCtrl` | 入 | CSR 控制信号 (超时阈值等) |

### 5.2 SBuffer 刷写控制

```scala
val cmoFlush = lsq.io.flushSbuffer.valid
val fenceFlush = io.ooo_to_mem.flushSb
val atomicsFlush = atomicsUnit.io.flush_sbuffer.valid || vSegmentUnit.io.flush_sbuffer.valid
val stIsEmpty = sbuffer.io.flush.empty && uncache.io.flush.empty
io.mem_to_ooo.sbIsEmpty := RegNext(stIsEmpty)
```

三种刷写来源:
1. **Fence 指令**: `io.ooo_to_mem.flushSb`
2. **Atomics 操作**: AtomicsUnit 或 vSegmentUnit 发起
3. **CMO 操作**: `lsq.io.flushSbuffer.valid`

### 5.3 Uncache 访问通路

Uncache (MMIO/Non-Cacheable) 路径的处理涉及 MemBlock 中一个三状态 FSM:

```
s_idle -> s_scalar_uncache -> (完成)
       -> s_vector_uncache -> (完成)
```

- `uncache.io.enableOutstanding`: 控制是否允许 outstanding 写请求
- Uncache 请求通过 `AddPipelineReg` 增加一级流水 (用于时序优化)
- Uncache forward 由 LoadUnit 的 `io.uncacheForward` 处理
- DCache 通过 `dcache.uncacheNode` 连接到 `uncache_xbar`

### 5.4 vSegmentUnit 与 SBuffer 的共享

vSegmentUnit 可以直接写入 SBuffer:
```scala
sbuffer.io.in.req(0).valid := lsq.io.sbuffer.req(0).valid || vSegmentUnit.io.sbuffer.valid
sbuffer.io.in.req(0).bits  := Mux1H(Seq(
  vSegmentUnit.io.sbuffer.valid -> vSegmentUnit.io.sbuffer.bits,
  lsq.io.sbuffer.req(0).valid  -> lsq.io.sbuffer.req(0).bits
))
```

---

## 6. Prefetch Engine 集成

### 6.1 PrefetcherWrapper 实例化

```scala
val prefetcher = Module(new PrefetcherWrapper)
```

`PrefetcherWrapper` 内部管理多个预取器实例，通过仲裁器合并输出:

| 预取器 | TLB 位置 | 目标级别 |
|--------|----------|----------|
| SMS (SMSPrefetcher) | dtlb_pf | L2 |
| Stream/Stride (L1StreamPrefetcher) | dtlb_ld | L1 |
| Berti | dtlb_ld/pf | L1/L2 |

### 6.2 Prefetcher 数据流

**训练源 (Training Sources)**:
```
LoadUnit S1/S2 Fire Hint --> Prefetcher (延迟的训练信号)
LoadUnit S3 Prefetch Train --> Prefetcher (训练数据)
StoreUnit S1/S2/S3 同理
DCache refillTrain --> Prefetcher
DCache sms_agt_evict_req --> SMS 预取器
```

**输出通路**:
```
Prefetcher l1_pf_to_l1 --> LoadUnit.io.prefetchReq (通过仲裁器)
Prefetcher l1_pf_to_l2 --> outer.l2_pf_sender_opt (L2 预取接口)
```

### 6.3 L1 Prefetch 到 LoadUnit 的分配

```scala
val canAcceptPrefetch = newLoadUnits.map(_.io.prefetchReq.ready)
val toPrefetchValidVec = (0 until LduCnt + HyuCnt).map{ case i =>
  if(i==0) l1_pf_req.valid
  else l1_pf_req.valid && !canAcceptPrefetch.take(i).reduce(_ || _)
}
l1_pf_req.ready := Cat(canAcceptPrefetch).orR
```

预取请求优先送入 LoadUnit 0，若其忙则依次尝试 LoadUnit 1、LoadUnit 2。这是一个 **优先级递减分配** 策略。

### 6.4 L2 Prefetch TLB 端口

L2 预取器的 TLB 请求占用 DTLB 的一个专用端口:
```scala
dtlb_reqs(L2toL1DTLBPortIndex) <> io.l2_tlb_req
io.l2_pmp_resp := pmp_check(L2toL1DTLBPortIndex).resp
```

### 6.5 CSR 控制

```scala
prefetcher.io.pfCtrlFromTile.l2PfqBusy := io.l2PfqBusy
prefetcher.io.pfCtrlFromCSR := io.ooo_to_mem.csrCtrl.pf_ctrl
prefetcher.io.pfCtrlFromDCache <> dcache.io.pf_ctrl
dcache.io.l2_pf_store_only := RegNext(io.ooo_to_mem.csrCtrl.pf_ctrl.l2_pf_store_only, false.B)
io.outer_l2PfCtrl := DelayN(io.ooo_to_mem.csrCtrl.pf_ctrl.toL2PrefetchCtrl(), 2)
```

---

## 7. MMU/TLB 实例化

### 7.1 DTLB 三组实例

MemBlock 内实例化 **3 个独立的 TLBNonBlock** 实例:

```scala
val dtlb_ld_tlb_ld       = Module(new TLBNonBlock(TlbSubSizeVec(dtlb_ld_idx), 2, ldtlbParams))
val dtlb_st_tlb_st       = Module(new TLBNonBlock(TlbSubSizeVec(dtlb_st_idx), 1, sttlbParams))
val dtlb_prefetch_tlb_prefetch = Module(new TLBNonBlock(TlbSubSizeVec(dtlb_pf_idx), 2, pftlbParams))
```

| TLB 组 | 条目数 | 深度 | 连接对象 |
|--------|--------|------|----------|
| `dtlb_ld` | LduCnt + PfNumInDtlbLD | 2 级 | LoadUnit 0-2, 流式预取器 |
| `dtlb_st` | StaCnt | 1 级 | StoreUnit 0-1 |
| `dtlb_prefetch` | PfNumInDtlbPF | 2 级 | SMS/Stride/Berti 预取器, L2 prefetch TLB |

### 7.2 TLB 端口分配 (DTlbSize = 各组之和)

```
TLB Index 映射:
[0, LduCnt)                           --> LoadUnit 0-2
[LduCnt, LduCnt+PfNumInDtlbLD)       --> L1 预取器 (Stream 等)
[LduCnt+PfNumInDtlbLD, +StaCnt)      --> StoreUnit 0-1
[+StaCnt, +PfNumInDtlbPF)            --> SMS/Berti 预取器
[PfNumInDtlbPF start index)          --> L2 prefetcher
```

### 7.3 TLB Replace 策略

支持两种替换策略配置:
- `refillBothTlb = true`: 所有 TLB 共享一个替换模块 (`TlbReplace(DTlbSize, ...)`)
- `refillBothTlb = false`: 各组独立替换 (ld/st/pf 分别有各自的 `TlbReplace`)

### 7.4 PTW (Page Table Walker) 集成

```scala
val ptw = outer.ptw.module  // L2TLBWrapper

ptw.io.hartId := io.hartId
ptw.io.sfence <> sfence
ptw.io.csr.tlb <> tlbcsr
ptw.io.csr.distribute_csr <> csrCtrl.distribute_csr
ptw.io.wfi.wfiReq := io.wfi.wfiReq
```

PTW 通过 `dtlbRepeater` (PTWNewFilter) 与 DTLB 连接:
```scala
val dtlbRepeater = PTWNewFilter(ldtlbParams.fenceDelay, ptwio, ptw.io.tlb(1), sfence, tlbcsr, l2tlbParams.dfilterSize)
```

ITLB 通过 `itlbRepeater3` (PTWRepeaterNB) 与 PTW 连接:
```scala
val itlbRepeater3 = PTWRepeaterNB(passReady = false, itlbParams.fenceDelay, io.fetch_to_mem.itlb, ptw.io.tlb(0), sfence, tlbcsr)
```

PTW 端口分配: `tlb(0)` = ITLB, `tlb(1)` = DTLB

### 7.5 TLB Replay 机制

```scala
tlbreplay(i) := dtlb_ld(0).ptw.req(i).valid && ptw_resp_next.vector(0) && ptw_resp_v &&
  ptw_resp_next.data.hit(dtlb_ld(0).ptw.req(i).bits.vpn, ...)
```

当 PTW 正在响应且命中当前 TLB miss 的地址时，触发 replay 以避免重复发送 PTW 请求。

### 7.6 PMP (Physical Memory Protection)

```scala
val pmp = Module(new PMP())
val pmp_checkers = Seq.fill(DTlbSize)(Module(new PMPChecker(4, leaveHitMux = true)))
```

每个 TLB 端口对应一个独立的 `PMPChecker`，支持:
- 标准 PMP/PMA 检查
- Bitmap 检查 (可选, `HasBitmapCheck`)
- KeyID 检查 (可选, `KeyIDBits > 0`)

### 7.7 Sfence 和 TLB CSR 延迟

```scala
val sfence = RegNext(RegNext(io.ooo_to_mem.sfence))     // 2 周期延迟
val tlbcsr = RegNext(RegNext(io.ooo_to_mem.tlbCsr))     // 2 周期延迟
```

---

## 8. MemBlock-to-Backend 接口

### 8.1 输入接口 (ooo_to_mem)

`class ooo_to_mem` 定义了从 Backend/Top 到 MemBlock 的所有信号:

```scala
class ooo_to_mem extends MemBlockBundle {
  val backendToTopBypass = Flipped(new BackendToTopBundle)
  val sfence = Input(new SfenceBundle)
  val tlbCsr = Input(new TlbCsrBundle)
  val lsqio = new Bundle {
    val lcommit, scommit, commit    // 提交计数
    val pendingPtr, pendingPtrNext   // ROB 指针
  }
  val isStoreException, isVlsException  // 异常信号
  val csrCtrl = Flipped(new CustomCSRCtrlIO)  // CSR 控制
  val enqLsq = new LsqEnqIO          // LSQ 入队
  val flushSb = Input(Bool())        // SBuffer 刷写
  val storePc, hybridPc             // PC 信息 (用于预取训练)
  val intIssue / vecIssue           -- Issue Queue 出口
}
```

### 8.2 输出接口 (mem_to_ooo)

`class mem_to_ooo` 定义了从 MemBlock 到 Backend/Top 的所有信号:

```scala
class mem_to_ooo extends MemBlockBundle {
  val topToBackendBypass = new TopToBackendBundle

  // LSQ 状态
  val lqCancelCnt, sqCancelCnt, sqDeq, lqDeq
  val sqDeqPtr, lqDeqPtr, stIssuePtr

  // 内存违反
  val memoryViolation = ValidIO(new Redirect)

  // SBuffer 状态
  val sbIsEmpty = Output(Bool())

  // MDP 训练
  val mdpTrain = ValidIO(new Redirect)

  // Top-down 调试
  val lsTopdownInfo = Vec(LdExuCnt, Output(new LsTopdownInfo))

  // 异常信息
  val lsqio = new Bundle {
    val vaddr, vstart, vl, gpaddr, isForVSnonLeafPTE, mmioBusy
    val lqCanAccept, sqCanAccept
  }

  // 写回通路
  val intWriteback: MixedVec[MixedVec[MemWriteBack]]
  val vecWriteback: MixedVec[MixedVec[DecoupledIO[ExuOutput]]]

  // 反馈信号
  val staIqFeedback, hyuIqFeedback, vstuIqFeedback, vlduIqFeedback

  // Load 取消与唤醒
  val ldCancel = Vec(LdExuCnt, new LoadCancelIO)
  val wakeup = Vec(LdExuCnt, Valid(new MemWakeUpBundle))
}
```

### 8.3 Writeback (写回) 通路

写回信号是 MemBlock 到 Backend 最关键的数据通路:

**整数写回 (intWriteback)**:
- 从 `io.mem_to_ooo.intWriteback.flatten` 提取为 `Seq[MemWriteBack]`
- 每个 `MemWriteBack` 包含 `toRob` (写回 ROB) 和参数 `params`
- Load 写回端口连接 `newLoadUnits(i).io.ldout`
- Store 写回端口连接 `storeUnits(i).io.stout` 或 `lsq.io.mmioStout`
- Std 写回端口连接 `stdExeUnits(i).io.out`

**向量写回 (vecWriteback)**:
- 通过仲裁器合并 `vlMergeBuffer`、`vsMergeBuffer`、`vSegmentUnit`、`vfofBuffer` 的输出
- Port 0 仲裁: vSegmentUnit + vlMergeBuffer(0) + vsMergeBuffer(0)
- Port 1 仲裁: vfofBuffer + vlMergeBuffer(1) + vsMergeBuffer(1)

### 8.4 Wakeup (唤醒) 通路

```scala
io.mem_to_ooo.wakeup(i) := newLoadUnits(i).io.wakeup
```

LoadUnit 在 S0 阶段生成唤醒信号，用于提前唤醒 Backend 中依赖该 Load 结果的指令。

### 8.5 Feedback (反馈) 通路

- **staIqFeedback**: StoreUnit 的 `feedbackSlow` 信号 (S1 延迟一拍)
- **ldCancel**: LoadUnit 的 `io.cancel` 信号
- **vlduIqFeedback / vstuIqFeedback**: 向量 MergeBuffer 的反馈

### 8.6 Redirect (重定向) 通路

```scala
val redirect = RegNextWithEnable(io.redirect)  // 从 Backend 接收 redirect，延迟一拍
```

这个 redirect 信号扇出到:
- 所有 `newLoadUnits(i).io.redirect`
- 所有 `storeUnits(i).io.redirect`
- `atomicsUnit.io.redirect`
- `lsq.io.brqRedirect`
- 向量通路 (`vlSplit`, `vsSplit`, `vSegmentUnit`, `vlMergeBuffer`, `vsMergeBuffer`, `vfofBuffer`)

### 8.7 LFST 更新

```scala
io.mem_to_ooo.updateLFST(i) := stu.io.updateLFST  // StoreUnit S1 触发
```

当 Store 地址在 S1 阶段确定后，通知 Backend 更新 Load Forward Status Table。

---

## 9. 中断与异常路由 (Interrupt and Exception Routing)

### 9.1 中断 Sink Node

MemBlock 通过 `IntSinkNode` 接收来自 SoC 的中断信号:

```scala
val clint_int_sink = IntSinkNode(IntSinkPortSimple(1, 2))  // CLINT: MSIP + MTIP
val debug_int_sink = IntSinkNode(IntSinkPortSimple(1, 1))  // Debug interrupt
val plic_int_sink  = IntSinkNode(IntSinkPortSimple(2, 1))  // PLIC: MEIP + SEIP
val nmi_int_sink   = IntSinkNode(IntSinkPortSimple(1, (new NonmaskableInterruptIO).elements.size))  // NMI
val beu_local_int_sink = IntSinkNode(IntSinkPortSimple(1, 1))  // BEU local interrupt
```

### 9.2 中断信号路由

所有中断信号经过 `topToBackendBypass` 路由到 Backend:

```scala
io.mem_to_ooo.topToBackendBypass match { case x =>
  x.hartId := io.hartId
  x.l2FlushDone := RegNext(io.l2_flush_done)
  x.externalInterrupt.msip  := RegNext(outer.clint_int_sink.in.head._1(0))
  x.externalInterrupt.mtip  := RegNext(outer.clint_int_sink.in.head._1(1))
  x.externalInterrupt.meip  := RegNext(outer.plic_int_sink.in.head._1(0))
  x.externalInterrupt.seip  := RegNext(outer.plic_int_sink.in.last._1(0))
  x.externalInterrupt.debug := RegNext(outer.debug_int_sink.in.head._1(0))
  x.externalInterrupt.nmi.nmi_31 := RegNext(outer.nmi_int_sink.in.head._1(0) | outer.beu_local_int_sink.in.head._1(0))
  x.externalInterrupt.nmi.nmi_43 := RegNext(outer.nmi_int_sink.in.head._1(1))
  x.msiInfo    := DelayNWithValid(io.fromTopToBackend.msiInfo, 1)
  x.teemsiInfo := ...  // TEE MSI (可选)
  x.clintTime  := DelayNWithValid(io.fromTopToBackend.clintTime, 1)
}
```

注意: 所有中断信号都经过 **RegNext** 延迟一拍，MSI 和 clintTime 经过 **DelayNWithValid**。

### 9.3 异常信息生成

```scala
val exceptionInfoGen = Module(new ExceptionInfoGen)
```

`ExceptionInfoGen` 收集所有访存通路的异常信息:
```scala
val exceptionInfo = newLoadUnits.map(_.io.exceptionInfo) ++   // LduCnt 个
  storeUnits.map(_.io.exceptionInfo) ++                       // StaCnt 个
  vlMergeBuffer.io.exceptionInfo ++                           // VlduCnt 个
  vsMergeBuffer.map(_.io.exceptionInfo.head) ++               // VstuCnt 个
  Seq(lsq.io.stExceptionInfo) ++                              // Store Queue
  Seq(lsq.io.ldExceptionInfo) ++                              // Load Queue
  Seq(vSegmentUnit.io.exceptionInfo) ++                       // Vector Segment
  Seq(atomicsUnit.io.exceptionInfo)                           // AtomicsUnit
```

输出的异常信息 (vaddr, vstart, vl, gpaddr, isForVSnonLeafPTE) 通过 `io.mem_to_ooo.lsqio` 传递到 Backend:
```scala
io.mem_to_ooo.lsqio.vaddr := RegNext(exceptionInfoGen.io.exceptionInfo.vaddr)
io.mem_to_ooo.lsqio.vl    := RegNext(exceptionInfoGen.io.exceptionInfo.vl)
io.mem_to_ooo.lsqio.vstart := RegNext(exceptionInfoGen.io.exceptionInfo.vstart)
io.mem_to_ooo.lsqio.gpaddr := RegNext(exceptionInfoGen.io.exceptionInfo.gpaddr)
io.mem_to_ooo.lsqio.isForVSnonLeafPTE := RegNext(exceptionInfoGen.io.exceptionInfo.isForVSnonLeafPTE)
```

### 9.4 WFI (Wait For Interrupt) 安全门控

```scala
io.wfi.wfiSafe := dcache.io.wfi.wfiSafe &&
                   uncache.io.wfi.wfiSafe &&
                   lsq.io.wfi.wfiSafe &&
                   ptw.io.wfi.wfiSafe
```

只有当 DCache、Uncache、LSQ 和 PTW 都处于安全状态时，核心才能进入 WFI 状态。

---

## 10. 其他重要机制

### 10.1 Trigger 机制

MemBlock 实现了硬件触发器 (trigger)，用于调试和断点:
```scala
val tdata = RegInit(VecInit(Seq.fill(TriggerNum)(0.U.asTypeOf(new MatchTriggerIO))))
val tEnable = RegInit(VecInit(Seq.fill(TriggerNum)(false.B)))
```

触发器控制信号通过 CSR 控制接口更新，并扇出到 LoadUnit、StoreUnit 和 vSegmentUnit。

### 10.2 Reset 树

```scala
val leftResetTree = ResetGenNode(Seq(ModuleNode(ptw), ModuleNode(ptw_to_l2_buffer),
  ModuleNode(lsq), ModuleNode(dtlb_st_tlb_st), ModuleNode(dtlb_prefetch_tlb_prefetch), ModuleNode(pmp))
  ++ pmp_checkers.map(ModuleNode(_)) ++ Seq(ModuleNode(prefetcher)))
val rightResetTree = ResetGenNode(Seq(ModuleNode(sbuffer), ModuleNode(dtlb_ld_tlb_ld),
  ModuleNode(dcache), ModuleNode(l1d_to_l2_buffer), CellNode(io.reset_backend)))
```

Reset 树分为左右两支，避免 Reset 路径过深。`io.reset_backend` 作为 leaf node 生成 Backend 的复位信号。

### 10.3 Performance 监控

```scala
val perfFromUnits = (newLoadUnits ++ Seq(sbuffer, lsq, dcache)).flatMap(_.getPerfEvents)
val perfFromTLB = perfEventsDTLBld ++ perfEventsDTLBst
val perfFromPTW = perfEventsPTW.map(x => ("PTW_" + x._1, x._2))
val perfBlock = Seq(("ldDeqCount", ldDeqCount), ("stDeqCount", stDeqCount))
```

通过 `HPerfMonitor` 管理器收集所有子模块的性能事件，支持通过 CSR 访问。

### 10.4 Top-Down 分析

```scala
dcache.io.debugTopDown.robHeadVaddr := io.debugTopDown.robHeadVaddr
dtlbRepeater.io.debugTopDown.robHeadVaddr := io.debugTopDown.robHeadVaddr
lsq.io.debugTopDown.robHeadVaddr := io.debugTopDown.robHeadVaddr

io.debugTopDown.toCore.robHeadMissInDCache := dcache.io.debugTopDown.robHeadMissInDCache
io.debugTopDown.toCore.robHeadTlbReplay := lsq.io.debugTopDown.robHeadTlbReplay
io.debugTopDown.toCore.robHeadTlbMiss := lsq.io.debugTopDown.robHeadTlbMiss
io.debugTopDown.toCore.robHeadLoadVio := lsq.io.debugTopDown.robHeadLoadVio
io.debugTopDown.toCore.robHeadLoadMSHR := lsq.io.debugTopDown.robHeadLoadMSHR
```

这些信号用于 Top-Down 微架构分析方法论，帮助识别性能瓶颈。

---

## 11. 源文件位置汇总

| 模块 | 源文件路径 |
|------|-----------|
| **MemBlock (顶层)** | `src/main/scala/xiangshan/mem/MemBlock.scala` |
| **NewLoadUnit** | `src/main/scala/xiangshan/mem/pipeline/NewLoadUnit.scala` (L1874) |
| **NewStoreUnit** | `src/main/scala/xiangshan/mem/pipeline/NewStoreUnit.scala` (L919) |
| **StdExeUnit** | `src/main/scala/xiangshan/mem/pipeline/StdExeUnit.scala` |
| **AtomicsUnit** | `src/main/scala/xiangshan/mem/pipeline/AtomicsUnit.scala` (L39) |
| **LoadUnit (旧版)** | `src/main/scala/xiangshan/mem/pipeline/LoadUnit.scala` |
| **LsqWrapper** | `src/main/scala/xiangshan/mem/lsqueue/LSQWrapper.scala` (L67) |
| **LoadQueue** | `src/main/scala/xiangshan/mem/lsqueue/LoadQueue.scala` |
| **NewStoreQueue** | `src/main/scala/xiangshan/mem/lsqueue/NewStoreQueue.scala` |
| **Sbuffer** | `src/main/scala/xiangshan/mem/sbuffer/Sbuffer.scala` (L190) |
| **StorePrefetchBursts** | `src/main/scala/xiangshan/mem/sbuffer/StorePrefetchBursts.scala` |
| **PrefetcherWrapper** | `src/main/scala/xiangshan/mem/prefetch/PrefetcherWrapper.scala` (L80) |
| **SMSPrefetcher** | `src/main/scala/xiangshan/mem/prefetch/SMSPrefetcher.scala` |
| **L1StreamPrefetcher** | `src/main/scala/xiangshan/mem/prefetch/L1StreamPrefetcher.scala` |
| **L1StridePrefetcher** | `src/main/scala/xiangshan/mem/prefetch/L1StridePrefetcher.scala` |
| **Berti** | `src/main/scala/xiangshan/mem/prefetch/Berti.scala` |
| **PrefetcherMonitor** | `src/main/scala/xiangshan/mem/prefetch/PrefetcherMonitor.scala` |
| **ExceptionInfoGen** | `src/main/scala/xiangshan/mem/lsqueue/ExceptionInfoGen.scala` |
| **WaitTable (MDP)** | `src/main/scala/xiangshan/mem/mdp/WaitTable.scala` |
| **StoreSet (MDP)** | `src/main/scala/xiangshan/mem/mdp/StoreSet.scala` |
| **VLSplitImp** | `src/main/scala/xiangshan/mem/vector/VSplit.scala` |
| **VLMergeBufferImp** | `src/main/scala/xiangshan/mem/vector/VMergeBuffer.scala` |
| **VSMergeBufferImp** | `src/main/scala/xiangshan/mem/vector/VMergeBuffer.scala` |
| **VSegmentUnit** | `src/main/scala/xiangshan/mem/vector/VSegmentUnit.scala` |
| **VfofBuffer** | `src/main/scala/xiangshan/mem/vector/VfofBuffer.scala` |
| **DCacheWrapper** | `src/main/scala/xiangshan/cache/` |
| **Uncache** | `src/main/scala/xiangshan/cache/Uncache.scala` |
| **PTW (L2TLBWrapper)** | `src/main/scala/xiangshan/cache/mmu/` |
| **TLBNonBlock** | `src/main/scala/xiangshan/cache/mmu/` |
| **PMP / PMPChecker** | `src/main/scala/xiangshan/cache/mmu/` |
| **Backend (Bundle 定义)** | `src/main/scala/xiangshan/backend/Backend.scala` (L703-749) |
| **Parameters (默认配置)** | `src/main/scala/xiangshan/Parameters.scala` (L154-156) |

---

## 12. 总结

MemBlock 是香山处理器中最庞大、最复杂的模块之一，承担着以下核心职责:

1. **数据通路仲裁**: 3 个 LoadUnit + 2 个 StoreUnit + 向量单元共享 DCache 端口、TLB 端口、PMP 检查器
2. **内存序维护**: 通过 LSQ (LoadQueue + NewStoreQueue) 维护 Load/Store 程序序，实现 memory ordering
3. **一致性保证**: SBuffer 在 Store 提交前提供缓冲，通过 forward 机制支持 load-store forwarding
4. **地址翻译**: 三组独立 DTLB + PTW + PMP 实现完整的虚拟地址到物理地址翻译链
5. **性能优化**: PrefetcherWrapper 集成多级预取器，LoadUnit 提前 wakeup，TLB hint 避免不必要 replay
6. **中断路由**: 作为 SoC 到 Backend 的中断信号中转站 (CLINT/PLIC/Debug/NMI/BEU)
7. **异常处理**: ExceptionInfoGen 汇总所有访存通路的异常信息，确保正确性
8. **调试支持**: Trigger 机制、Top-Down 分析、Difftest 信息输出

整个模块的设计体现了 **高带宽** (3L+2S)、**低延迟** (5 级流水线)、**高灵活性** (向量/标量共享端口) 的现代高性能处理器访存子系统特征。
