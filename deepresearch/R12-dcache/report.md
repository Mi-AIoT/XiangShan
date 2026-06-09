# XiangShan DCache 子系统深度研究报告

## 目录

1. [DCache 总体架构](#1-dcache-总体架构)
2. [Cache 组织与参数](#2-cache-组织与参数)
3. [地址映射与 Banked 结构](#3-地址映射与-banked-结构)
4. [DCache 流水线](#4-dcache-流水线)
5. [Load Pipe 详解](#5-load-pipe-详解)
6. [Store Pipe 详解](#6-store-pipe-详解)
7. [Main Pipe 详解](#7-main-pipe-详解)
8. [Miss Queue (MSHR) 设计](#8-miss-queue-mshr-设计)
9. [Miss Entry 状态机](#9-miss-entry-状态机)
10. [Writeback Queue 与淘汰策略](#10-writeback-queue-与淘汰策略)
11. [一致性协议 (Coherence Protocol)](#11-一致性协议-coherence-protocol)
12. [AMO/原子操作支持](#12-amo原子操作支持)
13. [Store Buffer 集成](#13-store-buffer-集成)
14. [Probe 机制](#14-probe-机制)
15. [Uncache 子系统](#15-uncache-子系统)
16. [LR/SC 机制](#16-lrsc-机制)
17. [CMO 操作](#17-cmo-操作)
18. [WPU (Way Prediction Unit)](#18-wpu-way-prediction-unit)
19. [ECC 与可靠性](#19-ecc-与可靠性)
20. [性能优化技术](#20-性能优化技术)
21. [关键源文件索引](#21-关键源文件索引)
22. [DCache 架构图](#22-dcache-架构图)

---

## 1. DCache 总体架构

XiangShan 的 L1 Data Cache (DCache) 是一个高性能、非阻塞 (non-blocking) 的写回式 (write-back) 数据缓存子系统。它采用 Banked SRAM 设计，支持多流水线并行访问，并通过 TileLink 协议与 L2 Cache 通信。

### 1.1 设计哲学

DCache 的设计灵感来源于多个经典 Cache 架构论文，特别是 David Kroft 在 1981 年提出的 lockup-free instruction fetch/prefetch cache organization。核心设计目标包括：

- **非阻塞访问**：通过 Miss Queue (MSHR) 实现 miss 后不阻塞后续请求
- **Banked 数据组织**：将 Cache Line 划分为多个 Bank，提高并行带宽
- **多流水线并行**：Load Pipe、Store Pipe、Main Pipe 三条流水线可以并行处理请求
- **Store Buffer 集成**：通过 Sbuffer 将 store 操作解耦，减少 cache pipeline 压力
- **BtoT (Branch to Trunk) 机制**：通过权限增长策略优化一致性行为

### 1.2 核心组件

DCache 由以下核心组件构成：

| 组件 | 功能 | 文件位置 |
|------|------|----------|
| DCacheWrapper | 顶层封装模块 | `dcache/DCacheWrapper.scala` |
| LoadPipe | Load 流水线 (多个实例) | `dcache/loadpipe/LoadPipe.scala` |
| StorePipe | Store 流水线 | `dcache/storepipe/StorePipe.scala` |
| MainPipe | 主流水线 (Store/AMO/Probe/Refill) | `dcache/mainpipe/MainPipe.scala` |
| MissQueue | Miss Queue (MSHR) | `dcache/mainpipe/MissQueue.scala` |
| MissEntry | MSHR 入口条目 | `dcache/mainpipe/MissQueue.scala` |
| WritebackQueue | Writeback 队列 | `dcache/mainpipe/WritebackQueue.scala` |
| ProbeQueue | Probe 队列 | `dcache/mainpipe/Probe.scala` |
| AtomicsReplayUnit | 原子操作重放单元 | `dcache/mainpipe/AtomicsReplayUnit.scala` |
| AMOALU | 原子操作 ALU | `dcache/mainpipe/AMOALU.scala` |
| TagArray | Tag 存储阵列 | `dcache/meta/TagArray.scala` |
| BankedDataArray | Banked 数据存储阵列 | `dcache/data/BankedDataArray.scala` |
| Uncache | 非缓存访问缓冲 | `dcache/Uncache.scala` |
| CtrlUnit | DCache 控制单元 | `dcache/CtrlUnit.scala` |

---

## 2. Cache 组织与参数

### 2.1 默认 Cache 参数 (32KB 配置)

根据 `L1Cache.scala` 中的 `DCacheParameters` 定义：

```
DCacheParameters(
  nSets = 128,              // 默认 128 sets
  nWays = 8,                // 8 路组相联
  rowBits = 64,             // SRAM Row 宽度 = 64 bit (8 Bytes)
  blockBytes = 64,          // Cache Line 大小 = 64 Bytes
  nMissEntries = 1,         // 默认 MSHR 条目 (实际配置覆盖)
  nProbeEntries = 1,        // 默认 Probe 条目
  nReleaseEntries = 1,      // 默认 Release 条目
  nMaxPrefetchEntry = 1,    // 最大预取条目
  replacer = Some("setplru"), // Set-PLRU 替换策略
  tagECC = None,            // Tag ECC (默认关闭)
  dataECC = None,           // Data ECC (默认关闭)
)
```

### 2.2 实际配置参数

在 `Parameters.scala` 和 `Configs.scala` 中，生产配置为：

**默认 (XSCoreParameters) 配置**：
- `nSets = 128` (默认) 或 `nSets = 64` (某些配置)
- `nWays = 8`
- `nMissEntries = 16`
- `nProbeEntries = 8`
- `nReleaseEntries = 18`
- `nMaxPrefetchEntry = 6`
- `tagECC = "secded"` (SECDED 编码)
- `dataECC = "secded"` (SECDED 编码)

**小配置 (32KB, `WithXShrink`) 配置**：
- `nSets = 64`
- `nWays = 8`
- `nMissEntries = 4`
- `nProbeEntries = 4`
- `nReleaseEntries = 8`
- `nMaxPrefetchEntry = 2`

### 2.3 容量计算

默认配置 (128 sets)：
```
DCacheSize = nSets * nWays * blockBytes
         = 128 * 8 * 64
         = 65536 Bytes = 64 KB
```

小配置 (64 sets)：
```
DCacheSize = 64 * 8 * 64
         = 32768 Bytes = 32 KB
```

### 2.4 关键结构常量

从 `DCacheWrapper.scala` 中的 `HasDCacheParameters`：

```scala
val DCacheBanks = 8      // 数据 SRAM Bank 数 (硬编码)
val DCacheWays = nWays   // 默认 8
val DCacheSets = nSets   // 默认 128
val DCacheDupNum = 16    // Tag/Meta 复制端口数
val DCacheSRAMRowBits = 64 // SRAM Row 位宽
val DCacheWordBits = 64  // 字宽 = 64 bit
```

---

## 3. 地址映射与 Banked 结构

### 3.1 物理地址分解

```
           Physical Address
 --------------------------------------
 |   Physical Tag |  PIndex  | Offset |
 --------------------------------------
                  |
                  DCacheTagOffset
```

### 3.2 虚拟地址分解

```
           Virtual Address
 --------------------------------------
 | Above index  | Set | Bank | Offset |
 --------------------------------------
                |     |      |        |
                |     |      |        0
                |     |      DCacheBankOffset  (= log2(8) = 3)
                |     DCacheSetOffset          (= BankOffset + log2(Banks) = 3 + 3 = 6)
                DCacheAboveIndexOffset         (= SetOffset + log2(Sets) = 6 + 7 = 13 for 128 sets)
```

### 3.3 Banked 架构

DCache 使用 **8 个并行数据 Bank**，每个 Bank 宽度为 64 bit (一个 SRAM Row)：

```
                    一条 Cache Line = 64 Bytes = 512 bits
    +------+------+------+------+------+------+------+------+
    |Bank 0|Bank 1|Bank 2|Bank 3|Bank 4|Bank 5|Bank 6|Bank 7|
    | 64b  | 64b  | 64b  | 64b  | 64b  | 64b  | 64b  | 64b  |
    +------+------+------+------+------+------+------+------+
    |Row 0 (低32B)                   |Row 1 (高32B)            |
```

Bank 选择通过虚拟地址的 `[BankOffset+log2(Banks)-1 : BankOffset]` 位确定，即 `vaddr[5:3]`。

### 3.4 Cache Alias 处理

当 `nSets * blockBytes > 4KB`（页大小）时，会出现 Cache Alias 问题。XiangShan 通过在 L2 Cache 记录额外的 alias bits 来解决：

```scala
val aliasBitsOpt = if(setBytes > pageSize) Some(log2Ceil(setBytes / pageSize)) else None
```

对于 64KB DCache (128 sets)：`setBytes = 128 * 64 = 8192 > 4096`，需要 `log2(2) = 1` 位 alias bits。

---

## 4. DCache 流水线

DCache 采用 **多流水线并行** 架构，各流水线共享 TagArray 和 DataArray，通过仲裁机制避免冲突。

### 4.1 流水线总览

```
                    +------------------+
                    |   Tag Array      |<--------+  (Duplicated, read-only for loads)
                    |   (Duplicated)   |         |
                    +--------+---------+         |
                             |                    |
  LSU Load Req -> [LoadPipe S0->S1->S2] -------->|
                             |                    |
  LSU Store Req -> [StorePipe S0->S1]    -------->|----> Miss Queue
                             |                    |
  Sbuffer Store -> [MainPipe S0->S1->S2->S3] ---->|----> WritebackQueue
  Probe Req     -> [MainPipe]             -------->|
  Refill Req    -> [MainPipe]             -------->|
  AMO Req       -> [MainPipe]             -------->|
                    |                    |
                    |   Data Array      |<---------+
                    |   (Banked)        |
                    +-------------------+
```

### 4.2 Meta 数据组织

DCache 的 Meta 数据使用 Register-based 实现（非 SRAM），支持多端口并行读取：

- **Meta Array (Coherence)**: 存储一致性状态 (ClientMetadata)
- **Tag Array (Duplicated)**: Tag 信息，多份副本支持并行读
- **Error Array**: ECC 错误标志
- **Prefetch Array**: 预取来源标记
- **Access Array**: 访问位标记
- **Latency Array**: Refill 延迟记录

```scala
val metaArray = Module(new L1CohMetaArray(readPorts = LoadPipelineWidth + 1, writePorts = 1))
val tagArray = Module(new DuplicatedTagArray(readPorts = TagReadPort))
```

---

## 5. Load Pipe 详解

`LoadPipe` (位于 `dcache/loadpipe/LoadPipe.scala`) 是 DCache 的 Load 流水线，每个 Load Unit 对应一个 LoadPipe 实例。

### 5.1 三级流水线

**Stage S0 (请求发射)**：
- 接收 LSU 的 Load 请求
- 发起 Meta 和 Tag Array 读取
- 生成 Bank 选择信号
- 发起 WPU (Way Prediction Unit) 预测
- 计算 bank_oh (bank 选择 one-hot 信号)

```scala
val s0_valid = io.lsu.req.fire
val s0_bank_oh_64 = UIntToOH(addr_to_dcache_bank(s0_vaddr))
val s0_bank_oh_128 = (s0_bank_oh_64 << 1.U).asUInt | s0_bank_oh_64.asUInt
```

**Stage S1 (Tag 比较)**：
- 获取 TLB 物理地址 (`io.lsu.s1_paddr_dup_lsu`)
- 执行 Tag 比较，判断 Hit/Miss
- 检查权限 (onAccess 判断一致性状态)
- 发起数据 Bank 读取 (当 Hit 时)
- WPU 预测验证
- Tag ECC 校验

```scala
val s1_tag_match_way_dup_dc = wayMap((w: Int) => 
  s1_tag_resp(w) === get_tag(s1_paddr_dup_dcache) && meta_resp(w).coh.isValid()
).asUInt
val (s1_has_permission, s1_shrink_perm, s1_new_hit_coh) = s1_hit_coh.onAccess(s1_req.cmd)
val s1_hit = s1_tag_match_dup_dc && s1_has_permission && s1_hit_coh === s1_new_hit_coh
```

**Stage S2 (数据返回与 Miss 处理)**：
- 返回 Hit 数据给 LSU
- 对 Miss 请求，向 Miss Queue 发送 Miss 请求
- 检查 bank conflict
- 生成 miss/replay 信号

### 5.2 Kill 机制

Load Pipe 支持两级 Kill：
- `s1_kill`: 在 S1 阶段取消请求 (flush、异常等)
- `s2_kill`: 在 S2 阶段取消请求

### 5.3 BtoT 检查

Load Pipe 在 S2 阶段检查 BtoT (Branch to Trunk) 占用情况：

```scala
io.occupy_set := addr_to_dcache_set(s2_vaddr)
```

当同一 set 中 BtoT 状态的 way 数量超过 `nWays - 2` 时，新的 BtoT 请求会被阻止。

---

## 6. Store Pipe 详解

`StorePipe` (位于 `dcache/storepipe/StorePipe.scala`) 是 Non-blocking Store DCache 流水线，与 STA Pipeline 关联。

### 6.1 两级流水线

**Stage S0**：
- 接收 STA 请求，发起 Meta 和 Tag 读取

```scala
val s0_valid = io.lsu.req.valid
io.meta_read.bits.idx := get_idx(io.lsu.req.bits.vaddr)
io.tag_read.bits.idx := get_idx(io.lsu.req.bits.vaddr)
```

**Stage S1**：
- Tag 比较，判断 Hit/Miss
- 权限检查：`s1_hit = s1_has_permission && s1_new_hit_coh === s1_hit_coh && s1_tag_match.orR`

**Stage S2**：
- Hit: 更新 replacement 算法
- Miss: 发送预取 hint 到 DCache (当 `EnableStorePrefetchAtIssue` 时)
- Miss: 发送 prefetch train 请求到 SMS (当 `EnableStorePrefetchSMS` 时)

### 6.2 Store 与 Sbuffer 的关系

Store Pipe 仅处理 Store 的 **hit 情况** 和 **miss 预取 hint**。实际的 Store 数据写入由 Sbuffer 通过 MainPipe 完成。这使得 Store 操作可以延迟提交，减少对 Cache 流水线的冲击。

---

## 7. Main Pipe 详解

`MainPipe` (位于 `dcache/mainpipe/MainPipe.scala`) 是 DCache 的核心流水线，处理 Store 写入、AMO 操作、Probe 请求和 Refill 写入。

### 7.1 请求仲裁

MainPipe 通过优先级仲裁器处理四种请求源：

```scala
arbiter(
  in = Seq(
    io.probe_req,      // 最高优先级: Probe
    io.refill_req,     // 次高: Refill
    store_req,         // 次次: Store (Sbuffer)
    io.atomic_req,     // 最低: AMO
  ),
  out = req,
  name = Some("main_pipe_req")
)
```

Store 请求有一个等待机制 (`storeWaitCycles`)，当等待时间超过 `StoreWaitThreshold` 时，Store 优先级提升。

### 7.2 四级流水线

**Stage S0 (仲裁与 Tag 读取)**：
- 请求仲裁
- 发起 Meta 和 Tag Array 读取
- 生成 banked read mask
- 计算 store 数据的 bank 写 mask

```scala
val bank_write = VecInit((0 until DCacheBanks).map(i => 
  get_mask_of_bank(i, s0_req.store_mask).orR
)).asUInt
```

**Stage S1 (Tag 比较与替换)**：
- Tag 比较，判断 Hit/Miss
- Replacement way 选择 (PLRU)
- 权限检查与一致性状态计算
- 决定是否需要数据读取

```scala
val (s1_has_permission, s1_shrink_perm, s1_new_hit_coh) = s1_hit_coh.onAccess(s1_req.cmd)
val s1_hit = s1_tag_match && s1_has_permission
val s1_need_replacement = !s1_tag_match
```

**Stage S2 (数据选择与决策)**：
- 对于 Store Hit: 选择 merge 后的数据
- 对于 Miss: 检查 refill 数据可用性
- 生成 miss request 到 Miss Queue
- 处理 BtoT grow permission 失败

```scala
val s2_grow_perm = s1_shrink_perm === BtoT && !s1_has_permission
val s2_has_more_then_3_ways_BtoT = PopCount(io.btot_ways_for_set) > (nWays-2).U
val s2_grow_perm_fail = s2_has_more_then_3_ways_BtoT && s2_grow_perm
```

**Stage S3 (数据写入)**：
- 数据合并 (refill data + store data)
- Meta 和 Tag 更新
- AMO 结果计算 (通过 AMOALU)
- LR/SC 检查
- 向 WritebackQueue 发送 writeback 请求

### 7.3 数据合并

MainPipe 在 S3 阶段执行多级数据合并：

```scala
for (i <- 0 until DCacheBanks) {
  val new_data = get_data_of_bank(i, Mux(s2_req.miss, 
    io.refill_info.bits.store_data, s2_req.store_data))
  s2_merge_mask(i) := Mux(s2_amo_hit, 0.U(wordBytes.W), 
    get_mask_of_bank(i, Mux(s2_req.miss, 
      io.refill_info.bits.store_mask, s2_req.store_mask)))
  s2_store_data_merged_without_cache(i) := mergePutData(
    0.U(DCacheSRAMRowBits.W), new_data, s2_merge_mask(i))
}
```

然后在 S3 与从 SRAM 读出的数据合并：

```scala
for (i <- 0 until DCacheBanks) {
  s3_store_data_merged(i) := mergePutData(
    s3_store_data_merged_without_cache(i), s3_data(i), s3_merge_mask(i))
}
```

---

## 8. Miss Queue (MSHR) 设计

Miss Queue 是 DCache 非阻塞设计的核心，位于 `dcache/mainpipe/MissQueue.scala`。

### 8.1 Miss Queue 结构

```scala
class MissQueue(edge: TLEdgeOut, reqNum: Int) extends DCacheModule {
  val io = IO(new Bundle {
    // Client requests
    val req = Vec(reqNum, Flipped(Decoupled(new MissReq)))
    val miss_resp = Output(Vec(reqNum, new MissResp))
    // Memory bus (TileLink)
    val mem_acquire = DecoupledIO(new TLBundleA(edge.bundle))
    val mem_grant = Flipped(DecoupledIO(new TLBundleD(edge.bundle)))
    val mem_finish = DecoupledIO(new TLBundleE(edge.bundle))
    // Main pipe refill
    val refill = DecoupledIO(new L1BankedDataWriteReq)
    val refill_info = ValidIO(new MissQueueRefillInfo)
    // ...
  })
}
```

### 8.2 Miss Queue 入口数量

```
nEntries = nMissEntries + nReleaseEntries + 1 (CMO Entry)
```

其中：
- `nMissEntries` 个 MSHR 条目 (默认 16 或 4)
- `nReleaseEntries` 个 Release 条目
- 1 个 CMO (Cache Management Operation) 条目

ID 分配：
- `releaseIdBase = nMissEntries + 1`
- CMO ID = `nMissEntries + 1`

### 8.3 请求入队流程

Miss Queue 采用 **两阶段入队** 设计：

```
  +---------------------------------------------------------------------+    pipeline reg  +-------------------------+
  |         S0: enq source arbiter, judge mshr alloc or merge           |     +-------+    | S1: real alloc or merge |
  |                      +-----+          primary_fire?       ->        |     | alloc |    |                         |
  | mainpipe  -> req0 -> |     |          secondary_fire?     ->        |     | merge |    |                         |
  | loadpipe0 -> req1 -> | arb | -> req                       ->        |  -> | req   | -> |                         |
  | loadpipe1 -> req2 -> |     |          mshr id             ->        |     | id    |    |                         |
  |                      +-----+                                        |     +-------+    |                         |
  +---------------------------------------------------------------------+                  +-------------------------+
```

### 8.4 请求优先级

```
MissReqPortCount = 1 (mainpipe) + LduCnt (loadpipe) + StaCnt (storepipe) + HyuCnt (hybrid)
MainPipeMissReqPort = 0  // MainPipe 具有最高优先级
```

### 8.5 请求合并 (Merge) 策略

Miss Queue 支持 **secondary merge**：当新请求与已有 MSHR 条目命中相同 Cache Line 时，可以合并：

```scala
def should_merge(new_req: MissReqWoStoreData): Bool = {
  val block_match = get_block(req.addr) === get_block(new_req.addr)
  val alias_match = is_alias_match(req.vaddr, new_req.vaddr)
  block_match && alias_match &&
  (
    before_req_sent_can_merge(new_req) ||
    before_data_refill_can_merge(new_req)
  )
}
```

合并规则：
1. **Block match**: 地址的 block 部分完全匹配
2. **Alias match**: 虚拟地址 alias 位匹配
3. **Load 可以 merge 到 Load/Store/Prefetch**
4. **Store 只能 merge 到 Load/Prefetch** (Store-Store merge 被禁止，以保持 Sbuffer 顺序)

---

## 9. Miss Entry 状态机

每个 MissEntry (MSHR 条目) 使用基于寄存器的 FSM 控制器，而非传统 FSM 状态机。

### 9.1 状态信号

```scala
val s_acquire = RegInit(true.B)       // Acquire 是否已发送
val s_grantack = RegInit(true.B)      // GrantAck 是否已发送
val s_mainpipe_req = RegInit(true.B)  // MainPipe refill 请求是否已发送

val w_grantfirst = RegInit(true.B)    // 是否等待 first grant data
val w_grantlast = RegInit(true.B)     // 是否等待 last grant data
val w_mainpipe_resp = RegInit(true.B) // 是否等待 MainPipe 响应
val w_refill_resp = RegInit(true.B)   // 是否等待 refill 响应
val w_l2hint = RegInit(true.B)        // 是否等待 L2 hint
```

### 9.2 Release Entry 条件

```scala
val release_entry = s_grantack && w_mainpipe_resp && w_refill_resp
```

### 9.3 Miss Entry 生命周期

```
  Primary Fire:
    s_acquire=false, s_grantack=false, s_mainpipe_req=false
    w_grantfirst=false, w_grantlast=false, w_refill_resp=false/w_mainpipe_resp=false

  发送 Acquire:
    s_acquire -> true (mem_acquire.fire)

  接收 Grant Data:
    w_grantfirst -> true (mem_grant.fire, first beat)
    w_grantlast  -> true (mem_grant.fire, last beat)

  发送 GrantAck:
    s_grantack -> true (mem_finish.fire)

  向 MainPipe 发送 refill 请求:
    s_mainpipe_req -> true (main_pipe_req.fire)

  等待 MainPipe/Refill 完成:
    w_mainpipe_resp -> true (main_pipe_resp)
    w_refill_resp -> true (main_pipe_refill_resp)

  Release Entry:
    release_entry = true -> 条目释放
```

### 9.4 Refill 数据与 Store 数据合并

Miss Entry 支持在 refill 过程中将 store 数据与 refill 数据合并：

```scala
val refill_and_store_data = Reg(Vec(blockRows, UInt(rowBits.W)))

// Primary fire with store: 初始化为 store data
when (io.miss_req_pipe_reg.alloc && miss_req_pipe_reg_bits.isFromStore) {
  refill_and_store_data := VecInit(miss_req_pipe_reg_bits.store_data.grouped(rowBits))
}

// Secondary merge with store: 合并新的 store mask/data
.elsewhen (io.miss_req_pipe_reg.merge && miss_req_pipe_reg_bits.isFromStore) {
  for (i <- 0 until blockRows) {
    refill_and_store_data(i) := VecInit((0 until rowBytes).map(k =>
      Mux(store_mask_temp(k), store_data_temp(k), 
        refill_and_store_data_update(i).grouped(8)(k))
    )).asUInt
  }
}

// Grant data: 与现有 refill_and_store_data 合并
.elsewhen (io.mem_grant.fire) {
  refill_and_store_data := refill_and_store_data_update
}
```

### 9.5 Keyword 优化

DCache 支持 **Keyword 优化**，将最需要的数据（keyword）放在 refill 的第一个 beat 中传输，以减少 load miss 延迟：

```scala
val isKeyword = RegInit(false.B)

// 根据 vaddr[5] 判断 keyword
isKeyword := Mux(miss_req_pipe_reg_bits.isFromLoad, 
  miss_req_pipe_reg_bits.vaddr(5).asBool, false.B)

// Refill 时根据 isKeyword 交换高低半部分
val lowHalf_refill = refill_count === 0.U && !isKeyword || 
                     refill_count =/= 0.U && isKeyword
```

---

## 10. Writeback Queue 与淘汰策略

### 10.1 WritebackQueue

`WritebackQueue` (位于 `dcache/mainpipe/WritebackQueue.scala`) 负责将脏数据写回 L2 Cache 或响应 Probe 请求。

### 10.2 Writeback Entry 状态机

```scala
val s_invalid :: s_release_req :: s_release_resp :: Nil = Enum(3)
```

状态转换路径：
```
ProbeAck:               s_invalid ->            s_release_req
ProbeAck merge Release: s_invalid ->            s_release_req
Release:                s_invalid -> s_sleep -> s_release_req -> s_release_resp
Release merge ProbeAck: s_invalid -> s_sleep -> s_release_req
                     or: s_invalid -> s_sleep -> s_release_req -> s_release_resp -> s_release_req
```

### 10.3 Writeback 类型

- **Voluntary Release**: 主动释放脏数据 (miss eviction, replacement)
- **ProbeAck**: 响应 Probe 请求，降级或无效化
- **ProbeAck + Release**: Probe 请求与 Release 合并

```scala
val voluntaryRelease = edge.Release(
  fromSource = io.id,
  toAddress = req.addr,
  lgSize = log2Ceil(cfg.blockBytes).U,
  shrinkPermissions = TLPermissions.toN
)._2
```

### 10.4 替换策略

DCache 使用 **Set-PLRU** (Set-awared Pseudo Least Recently Used) 替换策略：

```scala
replacer = Some("setplru")
```

替换选择在 MainPipe 的 S1 阶段完成：

```scala
s1_repl_way_en := Mux(
  GatedValidRegNext(s0_fire),
  Mux(s1_req.miss_fail_cause_evict_btot, s1_req.occupy_way, UIntToOH(io.replace_way.way)),
  RegEnable(s1_repl_way_en, s1_valid)
)
```

### 10.5 数据驱逐条件

以下情况需要写回并驱逐 Cache Line：
- **Miss 时替换**: 新数据需要替换已有 Cache Line
- **Probe 降级**: Probe 请求要求降级权限
- **CMO 操作**: Cache 管理操作 (Clean/Flush/Invalidate)
- **Dirty Line 驱逐**: Dirty 数据必须先写回再驱逐

---

## 11. 一致性协议 (Coherence Protocol)

XiangShan DCache 采用基于 **MOESI 协议变体** 的目录式一致性协议，通过 TileLink 协议与 L2 Cache 通信。

### 11.1 一致性状态

使用 `ClientMetadata` 定义的一致性状态：

| 状态 | 含义 | 读权限 | 写权限 | 脏数据 |
|------|------|--------|--------|--------|
| Nothing (N) | 无效 | - | - | - |
| Branch (B) | 共享, clean | 有 | - | - |
| Trunk (T) | 独占, clean | 有 | 有 | - |
| Dirty (D) | 独占, dirty | 有 | 有 | 有 |

### 11.2 权限增长与收缩

**权限增长 (BtoT)**：

```scala
val s1_grow_perm = s1_shrink_perm === BtoT && !s1_has_permission
```

当 Load 需要从 Branch 状态增长到 Trunk 状态时 (BtoT)，如果同一 set 中已有太多 BtoT way，增长失败。

**权限收缩**：

```scala
val (_, probe_shrink_perm, probe_new_coh) = s3_coh.onProbe(s3_req.probe_param)
```

Probe 根据请求参数收缩权限：
- `TtoB`: Trunk -> Branch
- `TtoN`: Trunk -> Nothing
- `BtoN`: Branch -> Nothing

### 11.3 Refill 后的状态

```scala
def missCohGen(cmd: UInt, param: UInt, dirty: Bool) = {
  val c = categorize(cmd)
  MuxLookup(Cat(c, param, dirty), Nothing)(Seq(
    Cat(rd, toB, false.B)  -> Branch,   // Read -> B, clean
    Cat(rd, toB, true.B)   -> Branch,   // Read -> B, dirty -> Branch (downgrade)
    Cat(rd, toT, false.B)  -> Trunk,    // Read -> T, clean
    Cat(rd, toT, true.B)   -> Dirty,    // Read -> T, dirty
    Cat(wi, toT, false.B)  -> Trunk,    // Write intent -> T, clean
    Cat(wi, toT, true.B)   -> Dirty,    // Write intent -> T, dirty
    Cat(wr, toT, false.B)  -> Dirty,    // Write -> T, clean -> Dirty
    Cat(wr, toT, true.B)   -> Dirty))   // Write -> T, dirty
}
```

### 11.4 TileLink 消息映射

| TileLink 消息 | 来源/目标 | DCache 场景 |
|---------------|-----------|-------------|
| `AcquireBlock` | DCache -> L2 | Miss 时获取 Cache Line |
| `AcquirePerm` | DCache -> L2 | Miss 且 full_overwrite 时仅获取权限 |
| `Grant` / `GrantData` | L2 -> DCache | Refill 数据/权限 |
| `GrantAck` | DCache -> L2 | 确认 Grant 接收 |
| `Release` | DCache -> L2 | 写回脏数据或降级权限 |
| `Probe` | L2 -> DCache | 要求降级或无效化 |
| `ProbeAck` | DCache -> L2 | 响应 Probe |
| `CacheBlockOperation` | DCache -> L2 | CMO 操作 |

---

## 12. AMO/原子操作支持

### 12.1 支持的 AMO 操作

根据 `CacheConstants.scala` 中的定义：

| 操作 | 编码 | 功能 |
|------|------|------|
| `M_XA_SWAP` | `b00100` | 交换 |
| `M_XA_ADD` | `b01000` | 加法 |
| `M_XA_XOR` | `b01001` | 异或 |
| `M_XA_OR` | `b01010` | 或 |
| `M_XA_AND` | `b01011` | 与 |
| `M_XA_MIN` | `b01100` | 有符号最小值 |
| `M_XA_MAX` | `b01101` | 有符号最大值 |
| `M_XA_MINU` | `b01110` | 无符号最小值 |
| `M_XA_MAXU` | `b01111` | 无符号最大值 |
| `M_XA_CASW` | `b11010` | Compare-And-Swap (32-bit) |
| `M_XA_CASD` | `b11011` | Compare-And-Swap (64-bit) |
| `M_XA_CASQ` | `b11000` | Compare-And-Swap (128-bit) |
| `M_XLR` | `b00110` | Load Reserved |
| `M_XSC` | `b00111` | Store Conditional |

### 12.2 AMOALU

`AMOALU` (位于 `dcache/mainpipe/AMOALU.scala`) 是专用的原子操作 ALU，支持 32-bit 和 64-bit 操作数：

```scala
class AMOALU(operandBits: Int) extends Module with MemoryOpConstants {
  val io = IO(new Bundle {
    val mask = Input(UInt((operandBits/8).W))
    val cmd = Input(Bits(M_SZ.W))
    val lhs = Input(Bits(operandBits.W))   // Cache 中的数据
    val rhs = Input(Bits(operandBits.W))   // 寄存器中的数据
    val out = Output(Bits(operandBits.W))  // AMO 结果
    val out_unmasked = Output(Bits(operandBits.W))
  })
}
```

AMOALU 支持子字粒度操作 (sub-XLen)，通过 mask 实现部分字节的原子操作。

### 12.3 AMO 执行路径

AMO 请求通过 `AtomicsReplayUnit` 进入 MainPipe：

```
LSU AMO Req -> AtomicsReplayUnit -> MainPipe S0->S1->S2->S3
                                            |           |
                                       Tag Compare    AMOALU 执行
                                            |           |
                                       Miss -> MQ    Write Data
```

在 MainPipe S3 阶段：
1. 读取 SRAM 数据 (`s3_data_word`)
2. AMOALU 计算结果
3. 合并数据写回 SRAM
4. 更新 Meta 状态

### 12.4 AMOCAS (Compare-And-Swap) 特殊处理

```scala
val s3_cas = !s3_req.probe && s3_req.isAMO && isAMOCAS(s3_req.cmd)
val s3_cas_fail = s3_cas && (FillInterleaved(8, s3_req.amo_mask) & 
  (s3_req.amo_cmp ^ s3_data_quad_word)) =/= 0.U

// AMOCAS.Q 支持 128-bit 比较
when (isAMOCASQ(s3_req.cmd)) {
  // 选择高/低 64-bit 部分
  val l_select = !s3_cas_fail && s3_req.word_idx === i.U
  val h_select = !s3_cas_fail && s3_req.cmd === M_XA_CASQ &&
    s3_req.word_idx === (i - 1).U
}
```

---

## 13. Store Buffer 集成

### 13.1 Sbuffer 架构

XiangShan 使用 **Sbuffer (Store Buffer)** 作为 Store 操作的缓冲层，位于 `mem/sbuffer/Sbuffer.scala`。

Store 操作流程：
1. **Store Queue -> Sbuffer**: Store 指令在 ROB Commit 后进入 Sbuffer
2. **Sbuffer -> MainPipe**: Sbuffer 将数据以 Cache Line 粒度写入 DCache
3. **Sbuffer -> Miss Queue**: 如果写入 Miss，进入 Miss Queue

### 13.2 Store 数据合并

Sbuffer 使用 `doMerge` 实现 Store-Store 合并：

```scala
def doMerge(oldData: UInt, oldMask: UInt, newData: UInt, newMask: UInt): (UInt, UInt) = {
  val resData = VecInit((0 until DataBytes).map(j =>
    Mux(newMask(j), newData(8*(j+1)-1, 8*j), oldData(8*(j+1)-1, 8*j))
  )).asUInt
  val resMask = newMask | oldMask
  (resData, resMask)
}
```

### 13.3 Force Write 机制

MainPipe 支持 `force_write` 信号，允许在 Store 等待过长时间时强制写入：

```scala
val storeWaitCycles = RegInit(0.U(4.W))
val StoreWaitThreshold = ...
val storeWaitTooLong = storeWaitCycles >= StoreWaitThreshold
val loadsAreComing = io.data_read.asUInt.orR
val storeCanAccept = storeWaitTooLong || !loadsAreComing || io.force_write
```

### 13.4 Sbuffer Forwarding

Sbuffer 支持向 Load 操作 forwarding 最新的 Store 数据：

```scala
class DCacheForward(implicit p: Parameters) extends DCacheBundle {
  val s0Req = ValidIO(new DCacheForwardReqS0)
  val s1Req = Output(new DCacheForwardReqS1)
  val s1Kill = Output(Bool())
  val s2Resp = Flipped(ValidIO(new DCacheForwardResp))
}
```

---

## 14. Probe 机制

### 14.1 ProbeQueue

`ProbeQueue` (位于 `dcache/mainpipe/Probe.scala`) 处理来自 L2 Cache 的 Probe 请求：

```scala
class ProbeEntry(implicit p: Parameters) extends DCacheModule {
  val s_invalid :: s_pipe_req :: s_wait_resp :: Nil = Enum(3)
  
  when (state === s_invalid) {
    io.req.ready := true.B
    when (io.req.fire) { state := s_pipe_req }
  }
  
  when (state === s_pipe_req) {
    io.pipe_req.valid := !RegNext(lrsc_blocked)
    when (io.pipe_req.fire) { state := s_wait_resp }
  }
  
  when (state === s_wait_resp) {
    when (io.pipe_resp.valid && io.id === io.pipe_resp.bits.id) {
      state := s_invalid
    }
  }
}
```

### 14.2 Probe 参数处理

Probe 请求包含 TL Permissions 参数：
- `toB`: 要求降级到 Branch (共享)
- `toN`: 要求降级到 Nothing (无效化)

在 MainPipe S3 阶段处理：
```scala
val (_, probe_shrink_param, probe_new_coh) = s3_coh.onProbe(s3_req.probe_param)
```

### 14.3 LR/SC 对 Probe 的影响

当存在有效的 LR/SC reservation set 时，Probe 可能被阻止：

```scala
val lrsc_blocked = Mux(
  io.req.fire,
  io.lrsc_locked_block.valid && get_block(io.lrsc_locked_block.bits) === get_block(io.req.bits.addr),
  io.lrsc_locked_block.valid && get_block(io.lrsc_locked_block.bits) === get_block(req.addr)
)
io.pipe_req.valid := !RegNext(lrsc_blocked)
```

---

## 15. Uncache 子系统

### 15.1 Uncache Buffer

`Uncache` (位于 `dcache/Uncache.scala`) 处理 **Non-Cacheable** 和 **MMIO** 访问：

```scala
class UncacheEntry(implicit p: Parameters) extends UncacheBundle {
  val cmd = UInt(M_SZ.W)
  val addr = UInt(PAddrBits.W)
  val vaddr = UInt(VAddrBits.W)
  val data = UInt(XLEN.W)
  val mask = UInt(DataBytes.W)
  val nc = Bool()          // Non-Cacheable 标记
  val memBackTypeMM = Bool() // Memory Mapped 类型
}
```

### 15.2 Uncache 请求路径

```
LSU Store (MMIO) -> UncacheBuffer -> TileLink A Channel
TileLink D Channel -> UncacheBuffer -> LSU Response
```

---

## 16. LR/SC 机制

### 16.1 LR (Load Reserved)

```scala
when (s3_valid && s3_lr) {
  when (s3_can_do_amo) {
    lrsc_count := (LRSCCycles - 1).U
    lrsc_addr := get_block_addr(s3_req.addr)
  }
}
```

LR 操作设置 reservation 计数器和地址。

### 16.2 SC (Store Conditional)

```scala
s3_sc_fail := s3_sc && (!s3_lrsc_addr_match || !s3_hit)
```

SC 失败条件：
1. 地址不匹配 (reservation 被清除)
2. Cache Line 不在 Hit 状态

### 16.3 Reservation 保护

- Reservation 在 LR 成功时设置
- Reservation 在 SC 时检查
- Reservation 在 Probe 时失效 (`io.invalid_resv_set`)
- `lrsc_locked_block` 阻止 Probe 和 LR storm

---

## 17. CMO 操作

### 17.1 CMO Unit

`CMOUnit` (位于 `dcache/mainpipe/MissQueue.scala`) 处理 Cache Management Operation：

```scala
val s_idle :: s_sreq :: s_wresp :: s_lsq_resp :: Nil = Enum(4)
```

支持的 CMO 操作：
- `CBO_CLEAN` (opcode=0): Clean cache line
- `CBO_FLUSH` (opcode=1): Flush cache line
- `CBO_INVAL` (opcode=2): Invalidate cache line
- `CBO_ZERO` (opcode=3): Zero cache line

```scala
io.req_chanA.bits := edge.CacheBlockOperation(
  fromSource = (cfg.nMissEntries + 1).U,
  toAddress = req.address,
  lgSize = (log2Up(cfg.blockBytes)).U,
  opcode = req.opcode
)._2
```

---

## 18. WPU (Way Prediction Unit)

### 18.1 WPU 集成

DCache 支持可选的 Way Prediction Unit (WPU)，位于 `cache/wpu/WPUWrapper.scala`：

```scala
val bankedDataArray = if(dwpuParam.enWPU) 
  Module(new SramedDataArray) 
else 
  Module(new BankedDataArray)
```

### 18.2 WPU 预测流程

WPU 在 LoadPipe S0 阶段预测 way：

```scala
if(dwpuParam.enWPU){
  io.dwpu.req(0).bits.vaddr := s0_vaddr
  io.dwpu.req(0).bits.replayCarry := s0_replayCarry
  io.dwpu.req(0).valid := s0_valid
}
```

在 S1 阶段验证预测：
```scala
s1_wpu_pred_fail := s1_valid && s1_tag_match_way_dup_dc =/= s1_pred_tag_match_way_dup_dc
```

---

## 19. ECC 与可靠性

### 19.1 Tag ECC

支持 SECDED (Single Error Correction, Double Error Detection)：

```scala
tagECC = Some("secded"),
enableTagEcc = true,

// Tag 编码
val encTag = cacheParams.tagCode.encode(tag)

// Tag 解码校验
val s1_tag_errors = wayMap((w: Int) => 
  meta_resp(w).coh.isValid() && 
  dcacheParameters.tagCode.decode(s1_enctag_resp(w)).error
).asUInt
```

### 19.2 Data ECC

```scala
dataECC = Some("secded"),
enableDataEcc = true,

// Data 写入时编码
io.tag_write.bits.ecc := cacheParams.tagCode.encode(io.write.bits.tag)
```

### 19.3 错误处理

- **Tag ECC Error**: 在 LoadPipe/MainPipe S1 阶段检测
- **Data ECC Error**: 通过 `readline_error_delayed` 延迟检测
- **L2 Error**: 通过 `TLError` 信号传递
- **BEU (Bus Error Unit)**: 通过 `io.error` 输出

---

## 20. 性能优化技术

### 20.1 Bank Conflict 优化

LoadPipe 支持 128-bit Load 请求，跨两个 Bank：

```scala
val s0_bank_oh_128 = (s0_bank_oh_64 << 1.U).asUInt | s0_bank_oh_64.asUInt
```

### 20.2 Store Prefetch

支持多种 Store 预取策略：
- `EnableStorePrefetchAtIssue`: Store 发射时预取
- `EnableStorePrefetchAtCommit`: Store 提交时预取
- `EnableStorePrefetchSPB`: Store Prefetch Buffer

### 20.3 L2 Hint

Miss Queue 支持 L2 Hint 优化：

```scala
val w_l2hint = RegInit(true.B)
when (io.l2_hint.valid) { w_l2hint := true.B }
```

### 20.4 Keyword 优化

减少 load miss 延迟的 Keyword 传输优化 (见 9.5 节)。

### 20.5 BtoT 机制

通过 BtoT (Branch to Trunk) 权限增长机制，减少因 Probe 导致的不必要权限降级和重新获取。

### 20.6 Bloom Filter

DCache 使用 Bloom Filter 辅助 Prefetch 决策：

```scala
val bloomFilter = Module(new BloomFilter(BLOOM_FILTER_ENTRY_NUM, true))
def BLOOM_FILTER_ENTRY_NUM = 4096
```

---

## 21. 关键源文件索引

| 文件路径 | 说明 | 行数 (约) |
|----------|------|-----------|
| `src/main/scala/xiangshan/cache/dcache/DCacheWrapper.scala` | DCache 顶层封装、参数、Bundle 定义 | ~1800 |
| `src/main/scala/xiangshan/cache/dcache/mainpipe/MainPipe.scala` | 主流水线 (Store/AMO/Probe/Refill) | ~1100 |
| `src/main/scala/xiangshan/cache/dcache/mainpipe/MissQueue.scala` | Miss Queue (MSHR) 与 MissEntry | ~1000 |
| `src/main/scala/xiangshan/cache/dcache/mainpipe/WritebackQueue.scala` | Writeback 队列 | ~500 |
| `src/main/scala/xiangshan/cache/dcache/mainpipe/Probe.scala` | Probe 队列 | ~200 |
| `src/main/scala/xiangshan/cache/dcache/loadpipe/LoadPipe.scala` | Load 流水线 | ~600 |
| `src/main/scala/xiangshan/cache/dcache/storepipe/StorePipe.scala` | Store 流水线 | ~200 |
| `src/main/scala/xiangshan/cache/dcache/mainpipe/AMOALU.scala` | 原子操作 ALU | ~80 |
| `src/main/scala/xiangshan/cache/dcache/mainpipe/AtomicsReplayUnit.scala` | 原子操作重放 | ~200 |
| `src/main/scala/xiangshan/cache/dcache/meta/TagArray.scala` | Tag 存储阵列 | ~200 |
| `src/main/scala/xiangshan/cache/dcache/data/BankedDataArray.scala` | Banked 数据存储 | ~400 |
| `src/main/scala/xiangshan/cache/dcache/Uncache.scala` | Uncache 缓冲 | ~600 |
| `src/main/scala/xiangshan/cache/dcache/CtrlUnit.scala` | DCache 控制单元 | ~200 |
| `src/main/scala/xiangshan/cache/L1Cache.scala` | L1 Cache 公共参数 | ~110 |
| `src/main/scala/xiangshan/cache/CacheConstants.scala` | Cache 常量与操作定义 | ~65 |
| `src/main/scala/xiangshan/Parameters.scala` | DCache 参数配置 | ~30 (DCache 相关) |
| `src/main/scala/top/Configs.scala` | DCache 配置实例 | ~30 (DCache 相关) |

---

## 22. DCache 架构图

```
                         XiangShan DCache 整体架构
    ================================================================================

    LSU (Load/Store Unit)
         |
         | Load DCacheLoadIO    | Store DCacheStoreIO    | AMO AtomicWordIO
         |                      |                        |
         v                      v                        v
    +---------+          +-----------+          +-----------------+
    |LoadPipe |          | StorePipe |          |AtomicsReplayUnit|
    | (x N)   |          | (x M)     |          |                 |
    | S0->S1->S2|        | S0->S1    |          +--------+--------+
    +----+----+          +-----+-----+                   |
         |                    |                          |
         | meta/tag read      | meta/tag read            |
         v                    v                          v
    +================================================================+
    |                    Shared Data Structures                       |
    |  +-------------+  +------------+  +---------+  +-----------+   |
    |  | TagArray    |  | MetaArray  |  |ErrorArr |  |PrefetchArr|   |
    |  |(Duplicated) |  |(Reg-based) |  |         |  |           |   |
    |  +------+------+  +-----+------+  +----+----+  +-----+-----+  |
    |         |               |              |              |         |
    |         | tag match     | coh state    |              |         |
    |         v               v              v              v         |
    +================================================================+
         |                                                        
         | miss req                                               
         v                                                        
    +==================+      +==================+                 
    |    MainPipe      |      |   MissQueue      |                 
    |  S0->S1->S2->S3 |<---->| (MSHR Entries)   |                 
    |                  |      |                  |                 
    | - Probe handling |      | - Acquire/Grant  |--- TileLink ---> L2 Cache
    | - Store write    |      | - Refill data    |<-- TileLink --- L2 Cache
    | - AMO exec       |      | - Merge support  |                 
    | - Refill write   |      | - Keyword opt    |                 
    +---------+--------+      +--------+---------+                 
              |                       |                            
              | data_write            | refill_data               
              v                       v                            
    +========================================================+
    |            BankedDataArray (8 Banks x 64b)              |
    |  +------+------+------+------+------+------+------+------+ |
    |  |Bank 0|Bank 1|Bank 2|Bank 3|Bank 4|Bank 5|Bank 6|Bank 7| |
    |  +------+------+------+------+------+------+------+------+ |
    +========================================================+
              ^
              | store data / amo data
              |
    +------------------+         +-----------------+
    | WritebackQueue   |         |  ProbeQueue     |
    | (Release entries)|         |  (Probe entries)|
    +--------+---------+         +--------+--------+
             |                            |
             | TileLink Release           | Pipe req
             v                            v
           L2 Cache                   MainPipe

    +------------------+
    | UncacheBuffer    |------> TileLink (MMIO/NC)
    +------------------+
```

```
                         Miss Entry (MSHR) 状态机
    ================================================================================

                         +-----------+
           primary_fire  |  s_invalid|<-------- release_entry (s_grantack && w_mainpipe_resp && w_refill_resp)
            +----------->|           |                                  ^
            |            +-----+-----+                                  |
            |                  |                                        |
            |                  v                                        |
            |            +-----------+                                  |
            |            | allocated |                                  |
            |            +-----+-----+                                  |
            |                  |                                        |
            |    s_acquire     |    mem_acquire.fire                    |
            +------- false ----+---------> true -----------------------|
                               |                                       |
                               v                                       |
            +----------------------------------------+                 |
            |        Waiting for L2 Grant            |                 |
            |  w_grantfirst = false                  |                 |
            |  w_grantlast  = false                  |                 |
            +------------------+---------------------+                 |
                               | mem_grant.fire (last beat)            |
                               v                                       |
            +----------------------------------------+                 |
            |        Grant Received                  |                 |
            |  w_grantfirst = true                   |                 |
            |  w_grantlast  = true                   |                 |
            +------------------+---------------------+                 |
                               | mem_finish.fire                        |
                               v                                       |
            +----------------------------------------+                 |
            |        GrantAck Sent                   |                 |
            |  s_grantack = true                     |                 |
            +------------------+---------------------+                 |
                               | main_pipe_req.fire                     |
                               v                                       |
            +----------------------------------------+                 |
            |    Waiting for MainPipe Refill Resp     |                 |
            |  w_mainpipe_resp = false                |                 |
            |  w_refill_resp = false (AMO)            |                 |
            +------------------+---------------------+                 |
                               | main_pipe_resp && main_pipe_refill_resp|
                               +----------------------------------------+

```

---

## 总结

XiangShan DCache 子系统是一个高度优化的 L1 数据缓存，具有以下特点：

1. **非阻塞设计**：通过 MSHR (Miss Queue) 支持 multiple outstanding misses，默认 16 个 MSHR 条目
2. **Banked SRAM**：8 个并行数据 Bank，每 Bank 64 bit，支持高带宽并行访问
3. **多流水线**：Load Pipe、Store Pipe、Main Pipe 三条流水线并行工作
4. **Store 解耦**：通过 Sbuffer 将 Store 操作与 Cache 流水线解耦
5. **完整的一致性协议**：基于 MOESI 变体，支持 BtoT 权限增长优化
6. **丰富的 AMO 支持**：所有 RV64A 原子操作，包括 AMOCAS (128-bit CAS)
7. **ECC 可靠性**：支持 Tag 和 Data 的 SECDED ECC 编码
8. **多种性能优化**：WPU、Keyword 优化、Store Prefetch、L2 Hint、Bloom Filter
9. **灵活配置**：支持 32KB/64KB 等多种容量配置
10. **CMO 支持**：Cache Block Clean/Flush/Invalidate/Zero 操作
