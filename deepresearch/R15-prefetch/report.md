# R15 - XiangShan Prefetch Engine 深度研究报告

## 1. 概述

XiangShan RISC-V 处理器的数据预取引擎（Prefetch Engine）是一个多层次、多算法协同的硬件预取系统，旨在通过提前将数据加载到缓存层次结构中来减少处理器的数据访问延迟。该系统包含 L1 数据缓存（L1 DCache）预取和向 L2/L3 缓存的预取两大路径，集成了多种预取算法：Stream Prefetcher（流预取器）、Stride Prefetcher（步长预取器）、SMS Prefetcher（Spatial Memory Streaming 预取器）和 Berti Prefetcher（基于历史 delta 的预取器）。每个预取器都有独立的训练（train）逻辑、过滤机制和生成（generate）逻辑，并通过统一的 `PrefetcherWrapper` 进行集成和仲裁。

XiangShan 的默认预取器配置在 `Parameters.scala` 第 152 行定义：

```scala
prefetcher: Seq[PrefetcherParams] = Seq(StreamStrideParams(), BertiParams(), SMSParams())
```

这意味着默认启用三个预取器模块：Stream+Stride（合并为一个 L1Prefetcher）、Berti 和 SMS。`PrefetcherWrapper` 根据该序列自动实例化对应的预取器硬件。

## 2. 预取系统架构

### 2.1 整体架构图

```
+-------------------------------------------------------------------------------------+
|                                  MemBlock (MemBlock.scala)                          |
|                                                                                     |
|   LoadUnit(s3_train) ----+                                                          |
|   StoreUnit(s3_train) ---+--> PrefetcherWrapper  ----+---> L1 DCache (LoadPipe)    |
|                             |  +------------------+   |     via l1_pf_to_l1          |
|                             |  | L1Prefetcher     |   |                               |
|                             |  | (Stream + Stride)|---+---> L2 Cache                 |
|                             |  +------------------+   |     via l1_pf_to_l2          |
|                             |  | BertiPrefetcher  |---+---> L3 Cache                 |
|                             |  | (History+Delta)  |   |     via l3_pf_req            |
|                             |  +------------------+   |                               |
|                             |  | SMSPrefetcher    |---+---> L2 Cache                 |
|                             |  | (AGT+PHT+Stride) |   |     via l2_pf_req            |
|                             |  +------------------+   |                               |
|                             +--- Arbiter (L1/L2/L3)  |                               |
|                             |                        |                               |
|                             +--- PrefetcherMonitor   |                               |
|                             |    (accuracy/throttle) |                               |
|                             +------------------------+                               |
+-------------------------------------------------------------------------------------+

Prefetch Target Levels:
  L1: Direct into DCache via LoadPipe (high-confidence, Stream/Stride/Berti)
  L2: Via coupledL2 PrefetchRecv interface (SMS/Stream/Stride/Berti)
  L3: Via dedicated L3 prefetch port (Stream/Stride/Berti)
```

### 2.2 核心数据结构

#### PrefetcherIO (BasePrefecher.scala)

所有硬件预取器共享统一的 IO 接口 `PrefetcherIO`：

| 信号 | 方向 | 描述 |
|------|------|------|
| `enable` | Input | 全局预取使能信号 |
| `ld_in[LdExuCnt]` | Input(Valid) | Load 单元训练信号（地址、miss 信息、PC 等） |
| `st_in[StaExuCnt]` | Input(Valid) | Store 单元训练信号 |
| `tlb_req` | Output | TLB 地址翻译请求 |
| `pmp_resp` | Input | PMP 检查响应 |
| `l1_req` | Decoupled(Valid) | L1 预取请求输出 |
| `l2_req` | Decoupled(Valid) | L2 预取请求输出 |
| `l3_req` | Decoupled(Valid) | L3 预取请求输出 |

#### L1PrefetchReq (L1PrefetchInterface.scala)

L1 预取请求的物理地址描述：

```scala
class L1PrefetchReq {
  val paddr      // 物理地址
  val alias      // 别名位（用于 VIPT cache）
  val confidence // 置信度（1-bit，高置信度可覆盖 load unit）
  val is_store   // 是否为 store 类型预取
  val pf_source  // 预取来源标识（Stream/Stride/Berti/Clear 等）
}
```

#### Prefetch Source 编码

预取来源通过 `L1PrefetchSource` 编码：

| 编码值 | 名称 | 描述 |
|--------|------|------|
| 0 | `L1_HW_PREFETCH_NULL` | 非预取（demand request） |
| 1 | `L1_HW_PREFETCH_CLEAR` | 被 demand 命中的预取标记清除 |
| 2 | `L1_HW_PREFETCH_STRIDE` | Stride 预取器生成 |
| 3 | `L1_HW_PREFETCH_STREAM` | Stream 预取器生成 |
| 4 | `L1_HW_PREFETCH_STORE` | Store 预取 |
| 5 | `L1_HW_PREFETCH_BERTI` | Berti 预取器生成 |

#### Region 基础数据结构 (HasL1PrefetchHelper)

Stream 和 Stride 预取器使用统一的 Region 概念来组织预取请求：

- **REGION_SIZE** = 1024 字节，每个 Region 包含 `1024/blockBytes` 个 cache block
- **BIT_VEC_WIDTH** = REGION_SIZE / blockBytes（对于 64B block = 16 bit）
- **REGION_TAG**: 虚拟地址中去除 block offset 和 region bits 后的高位
- **REGION_BITS**: 虚拟地址中的 region 内偏移

## 3. 预取器详细设计

### 3.1 Stream Prefetcher（流预取器）

**源文件**: `L1StreamPrefetcher.scala`

Stream Prefetcher 是 XiangShan 的主要 L1 预取器之一，基于 Spatial Memory Streaming (ISCA 2006) 论文的思想。其核心思想是：在内存访问流中检测活跃的区域，当检测到某个区域被多次访问时，预测其相邻区域也会被访问。

#### 3.1.1 StreamBitVectorArray 核心组件

Stream 预取器的核心是 `StreamBitVectorArray`，包含 16 个条目（`BIT_VEC_ARRAY_SIZE = 16`），每个条目记录一个区域的访问位图。

**StreamBitVectorBundle 数据结构**：
- `tag`: Region tag（带 hash 压缩）
- `bit_vec`: 16-bit 位向量，标记区域内每个 cache block 的访问状态
- `active`: 活跃标志，表示该条目是否已达到激活阈值
- `cnt`: 已访问 block 计数
- `decr_mode`: 递减模式标志（检测到反向访问流）

**流水线阶段**：

1. **S0（tag 查找）**: 从训练请求中提取 region tag，使用 hash tag 在 16 个条目中并行匹配。同时检查 region+1 和 region-1 是否命中（用于活跃检测）。

2. **S1（分配/更新）**: 
   - 如果 region 命中：更新 bit_vec 和 cnt，检查是否达到 `ACTIVE_THRESHOLD`（默认 12 = BIT_VEC_WIDTH - 4）
   - 如果 region 未命中但相邻 region 命中：分配新条目并设置 active
   - 更新方向检测（decr_mode）：如果 region+1 命中，则标记递减模式

3. **S2（预取地址计算）**: 如果条目 active 且有未发送的预取请求，计算预取地址：
   - L1 预取深度：默认 64 blocks（`l1_depth`）
   - L2 预取深度：默认 640 blocks（`l2_depth`，通过 `L2_DEPTH_RATIO=3` 左移）
   - L3 预取深度：默认 640 blocks（`l3_depth`）
   - 根据 `decr_mode` 决定前向或后向预取

4. **S3-S5（请求发送）**: L1 预取请求在 S3 发出，L2 在 S4 发出，L3 在 S5 发出（带独立使能开关）

#### 3.1.2 关键参数

| 参数 | 值 | 描述 |
|------|----|------|
| BIT_VEC_ARRAY_SIZE | 16 | 流表条目数 |
| ACTIVE_THRESHOLD | 12 | 活跃判定阈值 |
| L1_DEPTH | 64 blocks | L1 预取深度 |
| L2_DEPTH | 640 blocks | L2 预取深度 |
| L3_DEPTH | 640 blocks | L3 预取深度 |
| WIDTH_CACHE_BLOCKS | 8 | L1 预取宽度 |
| L2_WIDTH_CACHE_BLOCKS | 16 | L2 预取宽度 |
| L3_WIDTH_CACHE_BLOCKS | 32 | L3 预取宽度 |

#### 3.1.3 Stream 与 Stride 的协作

Stream 预取器还提供 `stream_lookup_req/resp` 接口，供 Stride 预取器查询当前区域是否已被 Stream 检测到活跃模式。当 Stream 返回 active 时，Stride 可以避免重复预取（`LOOK_UP_STREAM` 参数控制）。

### 3.2 Stride Prefetcher（步长预取器）

**源文件**: `L1StridePrefetcher.scala`

Stride Prefetcher 实现经典的步长预取算法（灵感来自 Baer & Chen, Supercomputing 1991），基于 PC 标签进行步长模式匹配。

#### 3.2.1 StrideMetaArray 核心组件

**StrideMetaBundle 数据结构**：
- `pre_vaddr`: 上次访问的虚拟地址
- `stride`: 检测到的步长值
- `decr_mode`: 递减模式标志
- `confidence`: 置信度计数器（3-bit，0-7）
- `hash_pc`: PC 的 hash 标签

**流水线阶段**：

1. **S0（PC hash 查找）**: 对训练请求的 PC 进行 hash（`pc_hash_tag`），在 16 个条目（`STRIDE_ENTRY_NUM = 16`）中并行匹配

2. **S1（步长学习）**: 
   - 新条目分配：记录当前 vaddr 和 hash_pc
   - 已有条目更新：计算 `new_stride = vaddr - pre_vaddr`
   - **步长匹配判断**：如果新步长与已知步长相同且不为 0 或 1，confidence 加 1
   - **步长不匹配**：confidence 减 1，低置信度时更新步长
   - 当 `confidence >= CONF_THRESHOLD`（3）时，标记可发送预取

3. **S2（预取地址计算）**: 使用步长计算预取目标地址：
   - L1 预取深度：`stride << l1_stride_ratio`（默认 ratio=2，即 4 倍步长）
   - L2 预取深度：`stride << l2_stride_ratio`（默认 ratio=5，即 32 倍步长）

4. **S3-S4（请求发送）**: L1 请求在 S3 发出，L2 请求在 S4 发出

#### 3.2.2 关键参数

| 参数 | 值 | 描述 |
|------|----|------|
| STRIDE_ENTRY_NUM | 16 | 步长表条目数 |
| STRIDE_CONF_BITS | 3 | 置信度位宽 |
| CONF_THRESHOLD | 3 | 发送预取的置信度阈值 |
| AGGRESSIVE_POLICY | false | 是否启用激进预取（degree > 1） |
| STRIDE_WIDTH_BLOCKS | 1 | 每次预取的 block 数（非激进模式） |
| l1_stride_ratio | 2 | L1 预取深度比例（左移位数） |
| l2_stride_ratio | 5 | L2 预取深度比例 |

#### 3.2.3 训练条件

Stride 的训练条件比 Stream 更严格（`PrefetcherWrapper.scala`）：
- 仅在 `isFirstIssue`（首次发射）时训练
- 必须 miss 或者来自 Stride 自身的预取（`isFromStride`）
- 不接受来自硬件预取的训练（`!isHwPrefetch`）
- 仅使用 load 训练，不使用 store 训练

### 3.3 SMS Prefetcher（Spatial Memory Streaming Prefetcher）

**源文件**: `SMSPrefetcher.scala`

SMS 预取器实现经典的 Spatial Memory Streaming 算法（Somogyi et al., ISCA 2006），仅用于 L2 预取。它使用三个层次的模式检测：

#### 3.3.1 核心组件

**1. Active Generation Table (AGT)**
- 16 个条目（`active_gen_table_size = 16`），PLRU 替换策略
- 记录当前活跃的 region 访问模式
- 每个 `AGTEntry` 包含：pht_index、pht_tag、region_bits（区域访问位图）、access_cnt、decr_mode

**2. Pattern History Table (PHT)**
- 64 entries, 2-way set associative（`pht_size=64, pht_ways=2`）
- 存储跨 region 的访问模式历史
- 每个 `PhtEntry` 包含：hist（2*(REGION_BLKS-1) 维历史向量）、tag、decr_mode

**3. StridePF（SMS 内部 Stride 组件）**
- 16 条目的 stride 检测器（`stride_entries = 16`），基于 PC 标签
- 用于 AGT 未命中且未活跃页面时的 fallback 预取

**4. PrefetchFilter（预取过滤器）**
- 16 条目（`pf_filter_size = 16`）
- 所有生成的预取请求都经过此过滤器进行 TLB 翻译和去重
- 使用 region_tag 进行匹配，支持跨 region 合并

#### 3.3.2 SMS 工作流程

**训练阶段**：
1. 从 Load/Store 单元接收训练请求，经过 `TrainFilter` 过滤重复
2. 提取 region_tag、region_addr、region_offset 等信息
3. 同时查找 AGT 和 StridePF

**AGT 处理流程**：
- **命中**: 更新 region_bits 和 access_cnt，判断是否活跃（access_cnt > threshold）
- **未命中但相邻 region 命中**: 分配新条目，标记方向（incr/decr）
- **完全未命中**: 溢出条目写入 PHT，交给 PHT 或 StridePF 处理

**PHT 处理流程**：
- 接收 AGT 溢出的条目，更新历史向量
- 查询时，根据 PC 标签查找历史模式
- 如果历史命中，生成 current/incr/decr 三个方向的预取请求

**StridePF 处理流程**：
- 当 AGT 判定页面不活跃（`!s1_in_active_page`）时，交给 Stride
- 基于 PC 标签的步长检测，生成跨 page 的预取请求

#### 3.3.3 预取生成优先级

```
AGT hit + active  --> AGT 生成预取（最高优先级）
AGT miss + active neighbor --> AGT 生成预取
AGT miss + not active --> StridePF 生成预取
AGT miss + Stride miss --> PHT 查询生成预取
```

#### 3.3.4 SMS 配置参数

| 参数 | 值 | 描述 |
|------|----|------|
| region_size | 1024B | 区域大小 |
| active_gen_table_size | 16 | AGT 条目数 |
| pht_size | 64 | PHT 条目数 |
| pht_ways | 2 | PHT 关联度 |
| stride_entries | 16 | SMS Stride 条目数 |
| pf_filter_size | 16 | 预取过滤器大小 |
| train_filter_size | 8 | 训练过滤器大小 |

### 3.4 Berti Prefetcher

**源文件**: `Berti.scala`

Berti 是一个基于 PC 历史的 delta 预取器，核心思想是：对于同一个 PC 的多次访问，计算连续访问之间的地址差（delta），当 delta 模式重复出现时，预测下一次访问的地址。

#### 3.4.1 核心组件

**1. HistoryTable (HT)**
- 64 组，每组 6 路（`ht_set_cnt=64, ht_way_cnt=6`）
- 支持 PLRU 和 FIFO 两种替换策略
- 每个条目记录：pcTag、baseVAddr（行虚拟地址）、tsp（时间戳）
- **写入时机**: demand miss 或 prefetch hit 时
- **读取时机**: demand refill 或 prefetch hit 时，搜索 delta

**2. DeltaTable (DT)**
- 64 路全相联（`dt_way_cnt=64`），每个条目包含最多 4 个 delta（`dt_delta_size=4`）
- 每个 DeltaInfo 包含：delta（13-bit 有符号）、coverageCnt（4-bit 计数）、status
- **Delta Status 枚举**:
  - `NO_PREF`: 无需预取
  - `L1_PREF`: 预取到 L1（coverageCnt >= thresholdOfL1PF = 4）
  - `L2_PREF`: 预取到 L2（coverageCnt > thresholdOfL2PF = 2）
  - `L2_PREF_REPL`: 预取到 L2 但可被替换（coverageCnt > thresholdOfL2PFR = 1）
- 使用周期性重置机制（`thresholdOfReset = 6`）来适应工作负载变化

**3. DeltaPrefetchBuffer**
- 16 条目（`PREFETCH_FILTER_SIZE = 16`）
- 所有 delta 预测的预取请求先进入此缓冲区
- 执行 TLB 翻译、PMP 检查
- 根据 delta status 决定目标缓存级别（L1/L2/L3）

#### 3.4.2 Berti 工作流程

```
1. Demand Miss 或 Prefetch Hit
     |
     v
2. HistoryTable.access() -- 记录 <PC, VA, timestamp>
   HistoryTable.search() -- 在同一 set 中搜索匹配的旧条目
     |                     计算 delta = current_VA - historical_VA
     v
3. DeltaTable.learn() -- 根据 delta 更新 DeltaTable 中对应 PC 的条目
     |                   更新 coverageCnt 和 bestDeltaIdx
     v
4. DeltaTable.prefetch() -- 查找当前 PC 的 best delta
     |                      根据 status 决定预取目标级别
     v
5. DeltaPrefetchBuffer -- TLB翻译 -> PMP检查 -> 发送预取请求
```

#### 3.4.3 Berti 关键参数

| 参数 | 值 | 描述 |
|------|----|------|
| ht_set_cnt | 64 | HT 组数 |
| ht_way_cnt | 6 | HT 每组路数 |
| dt_way_cnt | 64 | DT 路数 |
| dt_delta_size | 4 | 每个 DT 条目最多 delta 数 |
| thresholdOfL1PF | 4 | L1 预取阈值 |
| thresholdOfL2PF | 2 | L2 预取阈值 |
| thresholdOfReset | 6 | 计数器重置阈值 |

### 3.5 Store Prefetch Bursts

**源文件**: `StorePrefetchBursts.scala`

Store 预取突发机制检测连续的 store 操作，当检测到 store 模式时生成预取请求。包含 `PrefetchBurstGenerator`、`StorePrefetchBursts`、`Serializer` 和 `StorePfWrapper` 等组件。该机制主要在 sbuffer 中工作，独立于主 L1 预取路径。

## 4. 预取请求过滤与仲裁

### 4.1 TrainFilter（训练过滤器）

**源文件**: `L1PrefetchComponent.scala`

所有预取器的训练输入都经过 `TrainFilter` 过滤：

- 使用 `block_hash_tag` 进行去重，确保同一 cache block 地址不会重复训练
- 基于 FIFO 队列实现，大小为 6（stride）或 4（stream）
- 支持 load 和 store 训练输入
- 使用 `HwSort` 按 `robIdx` 重排序，保证训练顺序

### 4.2 MutiLevelPrefetchFilter（多级预取过滤器）

**源文件**: `L1PrefetchComponent.scala`

Stream 和 Stride 生成的预取请求统一经过 `MutiLevelPrefetchFilter`：

**内部结构**：
- L1 filter array：16 条目（`MLP_L1_SIZE = 16`）
- L2/L3 filter array：16 条目（`MLP_L2L3_SIZE = 16`）
- 总共 32 个 MLP（`MLP_SIZE = 32`）条目
- Pseudo-LRU 替换策略

**5 级流水线**：
1. **预取入队（S0-S1）**: 接收 stream/stride 的预取请求，hash tag 匹配，分配或更新条目
2. **TLB 请求（S0-S3）**: 将虚拟地址翻译为物理地址，检查 page fault / access fault / uncache
3. **L1 预取发送（S0-S1）**: 仲裁 L1 预取请求，逐块发送
4. **L2 预取发送**: 直接仲裁输出 L2 预取请求
5. **L3 预取发送**: 直接仲裁输出 L3 预取请求

### 4.3 PrefetcherWrapper 仲裁

**源文件**: `PrefetcherWrapper.scala`

`PrefetcherWrapper` 对三个预取器的输出进行仲裁：

```scala
// L1 预取仲裁
val l1_pf_arb = Module(new Arbiter(new L1PrefetchReq, prefetcherNum))

// L2 预取仲裁
val l2_pf_arb = Module(new Arbiter(new L2PrefetchReq, prefetcherNum))

// L3 预取仲裁
val l3_pf_arb = Module(new Arbiter(new L3PrefetchReq, prefetcherNum))
```

仲裁输出经过 Pipeline 寄存器（L1: 1 级, L2: 2 级, L3: 4 级）后发送。

## 5. 预取精度与覆盖率控制

### 5.1 PrefetcherMonitor（预取器监控器）

**源文件**: `PrefetcherMonitor.scala`

`PrefetcherMonitor` 是 XiangShan 预取系统的核心反馈控制模块，为每个预取器（Stream、Stride、Berti）独立维护监控逻辑。

**监控指标**：
- `total_prefetch`: 已发送的预取请求总数
- `hit_pf_in_cache`: demand 访问命中预取数据的次数（accuracy）
- `pf_late_in_cache`: 预取已发送但 demand 也 miss 的次数（lateness）
- `pf_late_in_mshr`: 预取命中已有 MSHR 的次数
- `pf_useless`: 预取的数据被替换前未被使用的次数（uselessness）
- `demand_miss`: demand miss 次数
- `pollution`: 预取造成的 cache 污染次数

**反馈控制机制**（基于论文 Feedback Directed Prefetching, HPCA 2007）：

| 触发条件 | 阈值 | 控制动作 |
|----------|------|----------|
| pf_useless 过高 | BAD_THRESHOLD = 400 | 将 depth 减半 |
| pf_useless 极高 | DISABLE_THRESHOLD = 900 | 禁用预取器，清除所有状态 |
| pf_late_in_cache 极高 | LATE_HIT_THRESHOLD = 900 | 禁用预取器 |
| pf_late_in_mshr 过多 | LATE_MISS_THRESHOLD = 200 | 将 depth 翻倍 |
| 无问题 | BACK_OFF_INTERVAL = 100K cycles | 重新使能预取器 |

**控制信号输出**（`PrefetchControlBundle`）：
- `dynamic_depth`: 动态调整预取深度
- `flush`: 清除预取器状态
- `enable`: 启用/禁用预取器
- `confidence`: 置信度（影响预取优先级）

**监控参数区分**：
- Stream Monitor: 使用 Stream 的 source type 过滤
- Stride Monitor: VALIDITY_CHECK_INTERVAL = 800, DISABLE_THRESHOLD = 700（更激进）
- Berti Monitor: 使用 Berti 的 source type 过滤

### 5.2 L1 Cache 中的预取标记

**源文件**: `DCacheWrapper.scala`, `LoadPipe.scala`

L1 DCache 维护 `PrefetchSourceArray`，每个 cache block 记录其预取来源：
- 预取 block 在 LoadPipe 中被标记 `meta_prefetch`
- 当 demand 访问命中预取 block 时，标记从 prefetch 变为 clear（`L1_HW_PREFETCH_CLEAR`）
- MainPipe 中负责写入和清除预取标记

### 5.3 CSR 控制

**源文件**: `CSR.scala`

通过 `spfctl` CSR 控制寄存器，软件可以精细控制预取行为：

| Bit | 控制内容 |
|-----|---------|
| 0 | L1D 预取全局使能 |
| 1 | L2 预取使能 |
| 2 | L1D 预取 stride 使能 |
| 3 | L1D train on hit 模式 |
| 4 | SMS AGT 使能 |
| 5 | SMS PHT 使能 |
| [9:6] | L1D active page threshold |
| [15:10] | L1D active page stride |

此外，`smblockctl` CSR 的 Bit 5 控制软件预取（soft_prefetch_enable）。

## 6. 与 Cache 层次结构的集成

### 6.1 L1 DCache 预取路径

L1 预取请求通过 LoadUnit 进入 DCache（`NewLoadUnit.scala`）：

```
L1PrefetchReq --> LoadUnit.prefetchHiConf / prefetchLoConf
                    |                       |
                    v                       v
              confidence=1:           confidence=0:
              可覆盖正常 load          仅在 load unit 空闲时发送
              unit 请求
```

- **高置信度预取**（confidence=1）：可以覆盖 LoadUnit 的正常 load 请求端口，优先级最高
- **低置信度预取**（confidence=0）：仅在 LoadUnit 有空闲端口时才发送

LoadUnit 同时提供训练信号 `prefetchTrain` 给 `PrefetcherWrapper`，包含 miss/hit 信息和 metaSource，供预取器学习。

### 6.2 L2 Cache 预取路径

L2 预取请求通过 `coupledL2.PrefetchRecv` 接口发送到 L2 缓存（`L2Top.scala`）：

```scala
io.l1_pf_to_l2.addr_valid  // 地址有效
io.l1_pf_to_l2.addr        // 预取地址
io.l1_pf_to_l2.pf_source   // 预取来源
io.l1_pf_to_l2.l2_pf_en    // L2 预取使能
```

SMS 预取器只发送 L2 请求（`io.l2_req.valid := pf_filter.io.l2_pf_addr.valid && io.enable`），不直接发送 L1 请求。

### 6.3 L3 Cache 预取路径

Stream 和 Berti 预取器支持直接向 L3 发送预取请求：
- Stream: `enableL3StreamPrefetch` 控制（默认关闭）
- Berti: 根据 delta 的 coverageCnt 判断是否 L3 预取
- 通过独立的 `l3_pf_req` 端口输出

### 6.4 TLB 翻译与 PMP 检查

所有预取请求（L1/L2/L3）都经过完整的地址翻译流程：
1. 预取器使用虚拟地址生成请求
2. 通过 `TlbRequestIO` 发送 TLB 请求
3. TLB 返回物理地址
4. PMP 检查确保预取目标在合法物理内存范围内
5. 检查 page fault、access fault、MMIO 和 uncache 区域
6. 不满足条件的预取请求被丢弃（`s3_drop`）

## 7. 软件预取指令处理

### 7.1 软件预取类型

XiangShan 支持 RISC-V 标准的软件预取指令（Zihintpause 扩展相关）：
- `M_PFR`: 软件预取读（prefetch read）
- `M_PFW`: 软件预取写（prefetch write）

在 `LoadPipe.scala` 中可以看到：
```scala
assert(s0_req.cmd === M_XRD || s0_req.cmd === M_PFR || s0_req.cmd === M_PFW)
```

### 7.2 软件预取使能

通过 `smblockctl` CSR 的 Bit 5 控制：
```scala
csrio.customCtrl.soft_prefetch_enable := smblockctl(5)
```

## 8. 关键源文件索引

| 文件路径 | 内容 |
|----------|------|
| `mem/prefetch/BasePrefecher.scala` | PrefetcherIO 定义、PrefetchCtrl、TrainReqBundle、PrefetchTarget 枚举 |
| `mem/prefetch/L1PrefetchInterface.scala` | L1PrefetchReq/Hint 定义、L1PrefetchSource 编码 |
| `mem/prefetch/L1PrefetchComponent.scala` | TrainFilter、MLPReqFilterBundle、MutiLevelPrefetchFilter、L1Prefetcher 整合 |
| `mem/prefetch/PrefetcherWrapper.scala` | PrefetcherWrapper（顶层集成）、TrainSourceIO、DCacheToPrefetchIO |
| `mem/prefetch/L1StridePrefetcher.scala` | StrideMetaArray（步长预取器核心逻辑） |
| `mem/prefetch/L1StreamPrefetcher.scala` | StreamBitVectorArray（流预取器核心逻辑） |
| `mem/prefetch/SMSPrefetcher.scala` | SMS 预取器完整实现（AGT、PHT、StridePF、PrefetchFilter） |
| `mem/prefetch/Berti.scala` | Berti 预取器（HistoryTable、DeltaTable、DeltaPrefetchBuffer） |
| `mem/prefetch/FDP.scala` | CounterFilter、BloomFilter（cache 污染检测） |
| `mem/prefetch/PrefetcherMonitor.scala` | PrefetcherMonitor 综合模块、L1PrefetchMonitor、MonitorParam |
| `mem/sbuffer/StorePrefetchBursts.scala` | Store 预取突发检测 |
| `cache/dcache/DCacheWrapper.scala` | PrefetchSourceArray 集成、预取标记管理 |
| `cache/dcache/loadpipe/LoadPipe.scala` | Load 管线中的预取命中/延迟检测和统计 |
| `Parameters.scala:152` | 默认预取器配置 `prefetcherSeq` |
| `backend/fu/CSR.scala:488` | spfctl CSR 控制位定义 |
| `mem/MemBlock.scala:720` | PrefetcherWrapper 在 MemBlock 中的实例化 |

## 9. 预取器协同工作与配置模式

### 9.1 Stride-Berti 协调模式

通过 `modeStrideBerti` Constantin 控制（`PrefetcherWrapper.scala`）：

| 模式值 | 名称 | Stride | Berti |
|--------|------|--------|-------|
| 0 | bothOff | 关闭 | 关闭 |
| 1 | strideOnBertiOff | 开启 | 关闭（默认） |
| 2 | strideOffBertiOn | 关闭 | 开启 |
| 3 | bothOn | 开启 | 开启 |

### 9.2 预取器优先级

在 `L1Prefetcher` 内部，Stream 的优先级高于 Stride：
```scala
// Stream has higher priority than stride
pf_queue_filter.io.l1_prefetch_req.bits := Mux(
  stream_bit_vec_array.io.l1_prefetch_req.valid && stream_pf_ctrl.enable,
  stream_bit_vec_array.io.l1_prefetch_req.bits,
  stride_meta_array.io.l1_prefetch_req.bits
)
```

在 `PrefetcherWrapper` 层级，三个预取器通过 `Arbiter` 进行固定优先级仲裁（index 0 最高）。

### 9.3 训练信号流

所有预取器的训练信号来自 LoadUnit 和 StoreUnit 的 S3 阶段：

```
LoadUnit.s3 --> PrefetcherWrapper.trainSource.s3_load
                  |
                  +--> SMS: io.ld_in (miss && isFirstIssue && !isHwPrefetch)
                  +--> Stride: stride_train (miss/strideHit && isFirstIssue && !isHwPrefetch)
                  +--> Berti: io.ld_in (miss/bertiHit && isFirstIssue && !isHwPrefetch)
```

## 10. 总结

XiangShan 的预取引擎设计具有以下特点：

1. **多层次覆盖**: Stream/Stride 覆盖 L1 预取，SMS 覆盖 L2 预取，Berti 覆盖 L1-L3 全层次
2. **反馈驱动**: PrefetcherMonitor 实时监控预取精度、及时性和污染率，动态调整参数
3. **灵活配置**: 通过 CSR 寄存器和 Constantin 机制，可运行时调整预取器行为
4. **完善过滤**: 多层过滤机制（TrainFilter -> MutiLevelPrefetchFilter -> PrefetchFilter）确保预取请求质量
5. **完整的 TLB/PMP 检查**: 所有预取请求都经过完整的地址翻译和权限检查，确保系统安全
