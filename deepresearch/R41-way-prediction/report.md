# XiangShan Way Prediction Unit (WPU) 深度研究报告

## 1. WPU Architecture and Design Goals / WPU 架构与设计目标

### 1.1 背景与动机 / Background and Motivation

在现代高性能处理器中，cache 的 way 数通常为 4~8-way set-associative。在传统的 cache 访问流水线中，tag 比较需要在 S1 阶段完成后才能确定命中的 way，然后在 S2 阶段才能读出对应 way 的数据。这意味着即使 cache 命中，数据读取也需要至少两个时钟周期。

Way Prediction Unit (WPU) 的设计目标是在 tag 比较之前（即 S0 阶段），就通过历史信息预测数据所在的 way，从而将数据读取的使能提前到 S0 阶段，实现数据读取与 tag 比较的并行化。当预测命中时，可以节省一个流水级的延迟，显著降低 load-to-use 延迟。

### 1.2 整体架构 / Overall Architecture

XiangShan 的 WPU 实现位于 `src/main/scala/xiangshan/cache/wpu/` 目录下，包含三个核心源文件：

- **WPU.scala** — 核心预测算法实现（MruWPU、MmruWPU、UtagWPU）
- **WPUWrapper.scala** — DCache/ICache WPU 的 Wrapper 层，负责与 LoadPipe 交互
- **VictimList.scala** — WayConflictPredictor 与 VictimList，用于 selective direct mapping

WPU 的配置参数定义在 `src/main/scala/xiangshan/Parameters.scala` 中：

```scala
// ICache WPU 参数
iwpuParameters: WPUParameters = WPUParameters(
  enWPU = false,        // 默认关闭
  algoName = "mmru",    // 使用 MMRU 算法
  isICache = true,
),
// DCache WPU 参数
dwpuParameters: WPUParameters = WPUParameters(
  enWPU = false,        // 默认关闭
  algoName = "mmru",    // 使用 MMRU 算法
  enCfPred = false,     // 默认关闭 Conflict Predictor
  isICache = false,
),
```

可以看到，WPU 在默认配置下是**禁用的**（`enWPU = false`）。但其完整的算法实现和流水线集成已就绪，可通过参数配置启用。

### 1.3 WPUParameters 定义 / WPU Parameters Definition

```scala
case class WPUParameters(
  enWPU: Boolean = true,       // 是否启用 WPU
  algoName: String = "mru",    // 预测算法名称
  enCfPred: Boolean = false,   // 是否启用 Conflict Predictor (selective direct mapping)
  isICache: Boolean = false,   // 是否用于 ICache
)
```

`HasWPUParameters` trait 中的 `AlgoWPUMap` 工厂方法根据 `algoName` 选择实例化的算法模块：

```scala
def AlgoWPUMap(wpuParam: WPUParameters, nPorts: Int): BaseWPU = {
  wpuParam.algoName.toLowerCase match {
    case "mru"  => Module(new MruWPU(wpuParam, nPorts))
    case "mmru" => Module(new MmruWPU(wpuParam, nPorts))
    case "utag" => Module(new UtagWPU(wpuParam, nPorts))
    case t      => throw new IllegalArgumentException(s"unknown WPU Algorithm $t")
  }
}
```

---

## 2. Prediction Algorithms / 预测算法

### 2.1 基类 BaseWPU

所有 WPU 算法都继承自 `BaseWPU`，该抽象类提供了通用的 IO 接口和索引计算逻辑：

```scala
abstract class BaseWPU(wpuParam: WPUParameters, nPorts: Int) extends WPUModule {
  val setSize = nSets                    // cache set 数量
  val nTagIdx = nWays                    // way 数量
  val auxWayBits = wayBits + 1           // 辅助 1 bit 用于判断 cache miss
  val TagIdxBits = log2Up(nTagIdx)       // way 索引位宽
  val utagBits = 8                       // utag 预测的 hash tag 位宽

  def get_wpu_idx(addr: UInt): UInt = {
    addr(untagBits - 1, blockOffBits)    // 从虚拟地址中提取 set index
  }
}
```

WPU 的 IO 接口（`WPUBaseIO`）包含：
- `predVec` — 多端口预测请求，输入虚拟地址，输出 `way_en`（one-hot 编码）
- `updLookup` — lookup 结束后的更新（含预测结果 `pred_way_en` 和真实结果 `way_en`）
- `updReplaycarry` — replay carry 的更新
- `updTagwrite` — tag 写入时的更新

### 2.2 MruWPU — Most Recently Used

**MruWPU** 是最简单的预测算法，每个 set 维护一个 wayBits 宽度的寄存器，记录最近一次访问的 way 编号。

```scala
class MruWPU(wpuParam, nPorts) extends BaseWPU(wpuParam, nPorts) {
  val predict_regs = RegInit(VecInit(Seq.fill(setSize)(0.U(wayBits.W))))
}
```

- **预测（predict）**：根据虚拟地址的 set index 索引 `predict_regs`，将存储的 way 编号通过 `UIntToOH` 转换为 one-hot 编码输出。
- **更新（write）**：当有 lookup 结果、replay carry 或 tag 写入时，用 `OHToUInt` 将 one-hot `way_en` 转换为编码后存入 `predict_regs`。

MruWPU 的优势是极低的存储开销（`nSets * wayBits` 位），但精度有限——它假设每次访问的 way 都与上次相同，无法处理同一个 set 中不同虚拟地址对应不同 way 的情况。

### 2.3 MmruWPU — Multi-MRU (Multi-Tag MRU)

**MmruWPU** 是当前默认配置的算法，在 MruWPU 的基础上增加了虚拟地址 tag 的维度。

```scala
class MmruWPU(wpuParam, nPorts) extends BaseWPU(wpuParam, nPorts) {
  val predict_regs = RegInit(VecInit(Seq.fill(setSize)(
    VecInit(Seq.fill(nTagIdx)(0.U(auxWayBits.W)))
  )))
}
```

- **预测**：同时使用 `set index` 和 `virtual tag` 作为索引，在 `predict_regs[setIdx][tagIdx]` 中查找对应的 way 编号。
- **更新**：同样基于 set index 和 virtual tag 进行更新。

MmruWPU 的存储开销为 `nSets * nWays * auxWayBits` 位。通过引入 tag 维度，同一个 set 中不同虚拟地址对应的 way 可以被独立追踪，大幅提升了预测精度。

虚拟地址 tag 的计算通过 `get_vir_tag(addr)` 从虚拟地址中提取高位 bits 实现。

### 2.4 UtagWPU — Unphysical Tag

**UtagWPU** 是最复杂但最具鲁棒性的算法，它不记录"最近使用的 way 编号"，而是为每个 way 存储一个哈希后的虚拟地址 tag（utag），在预测时通过比对 utag 来确定 way。

```scala
class UtagWPU(wpuParam, nPorts) extends BaseWPU(wpuParam, nPorts) {
  val utag_regs = RegInit(VecInit(Seq.fill(setSize)(
    VecInit(Seq.fill(nWays)(0.U(utagBits.W)))
  )))
  val valid_regs = RegInit(VecInit(Seq.fill(setSize)(
    VecInit(Seq.fill(nWays)(false.B)))
  ))
}
```

**哈希函数**：

```scala
def get_hash_utag(addr: UInt): UInt = {
  val vtag = get_vir_tag(addr)
  vtag(utagBits * 2 - 1, utagBits) ^ vtag(utagBits - 1, 0)
}
```

将 virtual tag 的高低两个 `utagBits` 段进行 XOR，压缩为 8 位的 utag。

**预测逻辑**：将请求地址的 utag 与该 set 中所有 way 的存储 utag 进行并行比较，找出匹配的 way：

```scala
pred_way_en := VecInit((0 until nWays).map(i =>
  req_utag === utag_regs(req_setIdx)(i) && valid_regs(req_setIdx)(i)
)).asUInt
// 避免 hash 冲突导致多路匹配
pred.way_en := UIntToOH(OHToUInt(pred_way_en))
```

**更新策略**（处理各种命中/未命中情况）：

1. **vtag miss but tag hit**（预测未命中但实际命中）：将正确的 utag 和 way 写入，下次可预测到
2. **vtag hit but way mismatch**（预测命中但 way 错误，hash 冲突）：invalidate 错误的 utag 条目，写入正确的 utag
3. **vtag hit but way mismatch 且真实命中**（hash 冲突导致两个不同地址 hash 到相同 utag 但映射到不同 way）：同时 invalidate 旧条目和写入新条目
4. **replay carry 和 tag write**：直接写入正确的 utag

UtagWPU 的优势在于不依赖 MRU 替换策略的假设，即使 cache 被其他事件（如 prefetch、coherence invalidation）影响，也能通过 tag 比较正确预测。

---

## 3. Training and Update Mechanism / 训练与更新机制

### 3.1 三个更新触发点

WPU 的更新来自三个事件源，通过 `WPUBaseIO` 的三个 update 端口输入：

| 更新源 | 端口 | 触发时机 | 用途 |
|--------|------|----------|------|
| `updLookup` | S1 阶段 lookup 完成后 | S1 结束 | 根据 tag 比较结果更新预测表 |
| `updReplaycarry` | replay carry 有效时 | S0 阶段 | 用已知的正确 way 更新预测表 |
| `updTagwrite` | tag 写入（cache line 填充）时 | tag array 写入时 | 新数据填入 cache 时更新预测表 |

### 3.2 Lookup 更新流程

在 `DCacheWpuWrapper` 中，S1 阶段执行 lookup 更新：

```scala
wpu.io.updLookup(i).en := io.lookup_upd(i).valid
wpu.io.updLookup(i).vaddr := io.lookup_upd(i).bits.vaddr
wpu.io.updLookup(i).way_en := io.lookup_upd(i).bits.s1_real_way_en   // 真实匹配的 way
wpu.io.updLookup(i).pred_way_en := io.lookup_upd(i).bits.s1_pred_way_en  // 预测的 way
```

在 `LoadPipe` 中，这个信息被组织后传递给 DCacheWrapper：

```scala
io.dwpu.lookup_upd(0).valid := s1_valid
io.dwpu.lookup_upd(0).bits.vaddr := s1_vaddr
io.dwpu.lookup_upd(0).bits.s1_real_way_en := s1_tag_match_way_dup_dc   // S1 tag match 结果
io.dwpu.lookup_upd(0).bits.s1_pred_way_en := s1_wpu_pred_way_en       // S0 预测结果（S1 捕获）
```

### 3.3 Replay Carry 更新

当 WPU 预测失败时，LoadPipe 通过 `ReplayCarry` 将正确的 way 信息传递给 replay 请求：

```scala
// LoadPipe S2 阶段
resp.bits.replayCarry.valid := ... || s2_wpu_pred_fail || ...
resp.bits.replayCarry.real_way_en := s2_real_way_en   // 真实命中的 way
```

在 `DCacheWpuWrapper` 中，当 replay 请求携带有效 `replayCarry` 时，直接使用其中的 `real_way_en`，并同时更新 WPU 表：

```scala
val s0_replay_upd = Wire(new BaseWpuUpdateBundle(nWays))
s0_replay_upd.en := io.req(i).valid && io.req(i).bits.replayCarry.valid
s0_replay_upd.vaddr := io.req(i).bits.vaddr
s0_replay_upd.way_en := io.req(i).bits.replayCarry.real_way_en
```

这保证了 replay 请求第一次经过 WPU 时就能获得正确结果，同时也训练了 WPU 表以避免未来的预测失败。

### 3.4 Tag Write 更新

当 cache line 被填充时（从下级存储返回数据），tag 写入事件触发 WPU 更新：

```scala
dwpu.io.tagwrite_upd.valid := tagArray.io.write.valid
dwpu.io.tagwrite_upd.bits.vaddr := tagArray.io.write.bits.vaddr
dwpu.io.tagwrite_upd.bits.s1_real_way_en := tagArray.io.write.bits.way_en
```

---

## 4. Integration with DCache Pipeline / 与 DCache 流水线的集成

### 4.1 DCacheWrapper 中的实例化

在 `DCacheWrapper` 中，WPU 的实例化和连接位于 `/** dwpu */` 注释段：

```scala
if (dwpuParam.enWPU) {
  val dwpu = Module(new DCacheWpuWrapper(LoadPipelineWidth))
  for (i <- 0 until LoadPipelineWidth) {
    dwpu.io.req(i) <> ldu(i).io.dwpu.req(0)
    dwpu.io.resp(i) <> ldu(i).io.dwpu.resp(0)
    dwpu.io.lookup_upd(i) <> ldu(i).io.dwpu.lookup_upd(0)
    dwpu.io.cfpred(i) <> ldu(i).io.dwpu.cfpred(0)
  }
  dwpu.io.tagwrite_upd.valid := tagArray.io.write.valid
  dwpu.io.tagwrite_upd.bits.vaddr := tagArray.io.write.bits.vaddr
  dwpu.io.tagwrite_upd.bits.s1_real_way_en := tagArray.io.write.bits.way_en
}
```

当 WPU 禁用时，DCacheWrapper 连接一个空的 stub：
```scala
ldu(i).io.dwpu.req(0).ready := true.B
ldu(i).io.dwpu.resp(0).valid := false.B
ldu(i).io.dwpu.resp(0).bits := DontCare
```

### 4.2 LoadPipe 三阶段流水线交互

WPU 与 LoadPipe 的交互贯穿三个流水阶段：

**S0 阶段 — Prediction（预测）：**
```
LoadPipe S0:  发送 vaddr + replayCarry → DCacheWpuWrapper
              DCacheWpuWrapper: 检查 replayCarry.valid
                → 有效: 使用 replayCarry.real_way_en（绕过 WPU 表）
                → 无效: 查询 WPU 表获得 way_en
              输出: s0_pred_way_en (one-hot, 最多 1 bit)
```

**S1 阶段 — Verification（验证）：**
```
LoadPipe S1:  从 S0 捕获 s1_wpu_pred_way_en
              同时 tag 比较获得 s1_tag_match_way_dup_dc (真实匹配结果)
              计算预测失败:
                s1_wpu_pred_fail = s1_valid && (s1_tag_match_way_dup_dc =/= s1_pred_tag_match_way_dup_dc)
                s1_wpu_pred_fail_and_real_hit = s1_wpu_pred_fail && s1_tag_match_way_dup_dc.orR
              将预测结果用于 data read 的 way_en:
                io.banked_data_read.bits.way_en := s1_pred_tag_match_way_dup_dc
```

**S2 阶段 — Recovery（恢复）：**
```
LoadPipe S2:  如果 s2_wpu_pred_fail 为真:
              → s2_hit = false（即使 tag 真实命中，也视为 miss 以触发 replay）
              → resp.bits.replay = true
              → resp.bits.replayCarry.valid = true
              → resp.bits.replayCarry.real_way_en = s2_real_way_en
              → io.lsu.s2_wpu_pred_fail = s2_wpu_pred_fail_and_real_hit
```

### 4.3 ReplayCarry 机制详解

`ReplayCarry` 是 WPU 与 LoadQueueReplay 之间的关键接口：

```scala
class ReplayCarry(nWays: Int) extends XSBundle {
  val real_way_en = UInt(nWays.W)  // 真实匹配的 way (one-hot)
  val valid = Bool()               // replayCarry 是否有效
}
```

当 WPU 预测失败时：
1. LoadPipe 在 S2 产生 `replay` 信号，同时携带 `replayCarry`（包含真实 way）
2. LoadQueueReplay 记录 replay 原因为 `C_WF`（WPU predict fail，cause 编码 7）
3. 由于 `C_WF` 与 `C_BC`（bank conflict）、`C_DR`（dcache replay）同属"非阻塞"类别，`blocking` 位被设为 false，允许下一周期立即 replay
4. Replay 请求携带 `replayCarry`，在下一次经过 DCacheWpuWrapper 时直接使用 `real_way_en`，绕过 WPU 表预测，同时更新 WPU 表

```scala
// LoadQueueReplay.scala
when (replayInfo.cause(LoadReplayCauses.C_BC) ||
      replayInfo.cause(LoadReplayCauses.C_NK) ||
      replayInfo.cause(LoadReplayCauses.C_DR) ||
      replayInfo.cause(LoadReplayCauses.C_WF)) {
  blocking(enqIndex) := false.B  // WPU 失败允许立即 replay
}
```

### 4.4 对 Data Array 的影响

当 WPU 启用时，DCache 使用 `SramedDataArray` 而非普通的 `BankedDataArray`：

```scala
val bankedDataArray = if(dwpuParam.enWPU) Module(new SramedDataArray) else Module(new BankedDataArray)
```

`SramedDataArray` 在读数据时将 `line_way_en` 强制设为全 1（`Fill(DCacheWays, 1.U)`），读出所有 way 的数据，然后在后级根据 WPU 预测的 `way_en` 选择正确的 way 数据。这与普通 `BankedDataArray` 仅读取特定 way 的方式不同，是 WPU 能够在 S0 阶段启动数据读取的关键设计。

### 4.5 对 Hit 信号的影响

WPU 预测失败会修改多个 hit 相关信号：

```scala
// 即使 tag 真实命中，如果 WPU 预测失败，也报告为 miss
io.lsu.s2_hit := s2_hit_dup_lsu && !s2_wpu_pred_fail
val s2_hit = s2_tag_match && s2_has_permission && ... && !s2_wpu_pred_fail
```

这意味着 WPU 预测失败等价于一个"逻辑 miss"，迫使该 load 请求通过 replay 重新执行。这是一种保守但正确的策略——即使数据实际在 cache 中，由于没有从正确的 way 读出数据，必须重新执行。

---

## 5. Selective Direct Mapping (Way Conflict Predictor) / 选择性直接映射

### 5.1 设计思想

当 `enCfPred = true` 时，WPU 进一步集成了 **WayConflictPredictor**，实现"选择性直接映射"（Selective Direct Mapping）优化。

核心思想：对于大部分 cache set，其数据访问模式接近 direct-mapped（即每次访问都固定在某一个 way），只有少数 set 会出现 set-associative conflict。对这些"无冲突"的 set，直接使用 virtual address 的低位 bits 计算 way（类似 direct-mapped cache），可以避免 WPU 表的存储开销。

### 5.2 WayConflictPredictor 实现

```scala
class WayConflictPredictor(nPorts: Int) extends WayConflictPredictorModule {
  val PredTable = RegInit(VecInit(Seq.fill(WCPSize)(0.U(CounterSize.W))))
}
```

- 每个 set 维护一个 `CounterSize = 2` bit 的饱和计数器
- 当 SA hit（set-associative hit，即 WPU 预测命中且数据在非 direct-map way 中发生冲突）时，计数器递增
- 当 DM hit（direct-map hit，即数据恰好在 direct-map 对应的 way 中）时，计数器递减
- 预测时，当计数器的最高位为 1（即 >= 2）时，判定为存在 way conflict，使用 WPU 预测；否则使用 direct mapping

```scala
io.pred(i).way_conflict := io.pred(i).en & PredTable(get_addr_idx(io.pred(i).vaddr))(CounterSize-1)
```

### 5.3 VictimList 与替换策略联动

```scala
case class VictimList(nSets: Int, width: Int = 2) {
  val victim_vec = RegInit(VecInit(Seq.fill(nSets)(0.U(width.W))))
  def replace(set: UInt) = {
    when(victim_vec(set) =/= Fill(width, 1.U)) {
      victim_vec(set) := victim_vec(set) + 1.U
    }
  }
  def whether_sa(set: UInt) = victim_vec(set)(width-1)
}
```

在 DCacheWrapper 的替换策略中，`VictimList` 决定替换行为：
- 如果 `whether_sa(set)` 为 true（set 经历过 set-associative conflict），使用标准的 set-associative 替换算法（如 PLRU）
- 否则，使用 direct-mapped 方式选择替换 way

---

## 6. Accuracy and Performance Impact / 准确率与性能影响

### 6.1 性能计数器

WPU 实现了丰富的性能监控接口（`XSPerfAccumulate`），分为几组：

**预测统计：**
- `wpu_pred_total` — 总预测次数
- `wpu_pred_succ` — 预测成功次数（预测 way = 真实 way）
- `wpu_pred_fail` — 预测失败次数
- `wpu_pred_miss` — 预测未命中（预测 way_en 为 0，即认为数据不在 cache 中）
- `wpu_real_miss` — 真实未命中次数

**预测来源统计（仅 enCfPred 时）：**
- `wpu_pred_replayCarry` — 由 replay carry 提供的预测次数
- `wpu_pred_wayPrediction` — 由 WPU 表提供的预测次数
- `wpu_pred_from_prediction` — 使用 WPU 表预测（存在 conflict）
- `wpu_pred_from_directMap` — 使用 direct mapping 预测（无 conflict）
- `direct_map_all` — 所有直接映射查询
- `direct_map_ok` — direct mapping 命中的次数

**Replay 统计：**
- `load_replay_for_dcache_wpu_pred_fail` — 因 WPU 预测失败导致的 replay 次数
- `utag_hash_conflict` — UtagWPU hash 冲突次数

### 6.2 理论准确率分析

**MruWPU 理论准确率**：受限于 MRU 假设。如果一个 set 中只有 1 个活跃地址（single-threaded working set），准确率可达 100%；当 set 中有 N 个不同地址竞争时，准确率与 MRU 保留率相关。对于典型的程序行为（时间局部性），MruWPU 的准确率通常在 85%~95% 之间。

**MmruWPU 理论准确率**：通过 tag 维度消除了不同虚拟地址之间的干扰。只要同一个 `(set, virtual tag)` 对的访问 pattern 稳定（大部分情况如此），准确率接近 100%。这也是其作为默认算法的原因。

**UtagWPU 理论准确率**：理论上最高，因为它是基于内容匹配而非时间局部性假设。只要 utag 不发生 hash collision，就能准确预测。其准确率受 8-bit utag 的 hash collision 概率限制，对于 4/8-way cache，hash collision 概率约为 `nWays / 2^8` 约 1.5%~3%。

### 6.3 预测失败的代价

WPU 预测失败的代价包括：
1. **一个额外的 replay 延迟**：需要重新进入 LoadPipe 执行
2. **ReplayQueue 槽位占用**：C_WF cause 占用 LoadQueueReplay 中的一个条目
3. **流水线 stall 窗口**：从 S2 发现失败到 replay 请求重新发出的周期数

但由于 C_WF 被设计为非阻塞（`blocking = false`），replay 可以在下一周期立即发出，最小化了延迟开销。

### 6.4 WPU 的净性能收益

WPU 的性能收益来自命中时节省的一个流水级延迟。在 4GHz 处理器中，一个周期约为 0.25ns，而 L1 DCache 的访问通常需要 2~3 个周期。如果 WPU 准确率超过 90%，则平均每次 load 访问节省约 `0.9 * 1 cycle = 0.9 cycle`，代价是 `0.1 * 1 cycle = 0.1 cycle`（replay penalty），净收益约 `0.8 cycle per load`。考虑到现代处理器每周期可发出 2~4 个 load，这个收益非常可观。

---

## 7. ICache WPU / ICache 的 Way Prediction

`ICacheWpuWrapper` 位于 `WPUWrapper.scala` 中，结构与 `DCacheWpuWrapper` 类似但有以下区别：

1. **没有 ReplayCarry 机制**：ICache 的 `IwpuBaseIO` 中没有 `replayCarry` 输入端口，`req` 使用 `WPUBaseReq`（不含 replayCarry）
2. **没有 ConflictPredictor**：ICache WPU 没有 `cfpred` 端口
3. **没有 ReplayQueue 集成**：ICache miss 通过 ICache 自己的 MSHR 机制处理，不经过 LoadQueueReplay

ICache WPU 的更新逻辑更简单——只在 lookup 完成后更新，依赖 MmruWPU 算法的时间局部性假设。

---

## 8. IdealWPU — 参考实现

`IdealWPU` 是一个"理想化"的 WPU 实现，直接使用真实的 tag match 结果作为预测输出：

```scala
class IdealWPU extends WPUModule with HasDCacheParameters {
  val s1_pred_way_en = io.req.s1_real_way_en  // 直接用真实结果
  io.resp.s1_pred_way_en := s1_real_way_en
}
```

它用于性能评估基准——展示了 WPU 在 100% 准确率下的理论最优性能上限。

---

## 9. Source File Locations / 源文件位置

| 文件路径 | 说明 |
|----------|------|
| `src/main/scala/xiangshan/cache/wpu/WPU.scala` | 核心算法实现（MruWPU、MmruWPU、UtagWPU） |
| `src/main/scala/xiangshan/cache/wpu/WPUWrapper.scala` | DCacheWpuWrapper、ICacheWpuWrapper、IdealWPU、ReplayCarry |
| `src/main/scala/xiangshan/cache/wpu/VictimList.scala` | WayConflictPredictor、VictimList |
| `src/main/scala/xiangshan/Parameters.scala` (L195-205) | iwpuParameters/dwpuParameters 配置 |
| `src/main/scala/xiangshan/cache/dcache/DCacheWrapper.scala` (L907-909, L932, L1331-1347, L1646-1658) | WPU 实例化、SramedDataArray 选择、替换策略联动 |
| `src/main/scala/xiangshan/cache/dcache/loadpipe/LoadPipe.scala` (L135-145, L213-258, L323-327, L357, L403, L458-461, L515) | LoadPipe 与 WPU 的三阶段交互 |
| `src/main/scala/xiangshan/cache/dcache/data/BankedDataArray.scala` (L366-389) | SramedDataArray（WPU 使能时使用） |
| `src/main/scala/xiangshan/mem/Bundles.scala` (L28, L107) | ReplayCarry 在 LsPipelineBundle 中的定义 |
| `src/main/scala/xiangshan/mem/pipeline/LoadUnit.scala` (L39, L55, L72) | LoadReplayInfo 中的 rep_carry 和 wpu_fail 定义 |
| `src/main/scala/xiangshan/mem/lsqueue/LoadQueueReplay.scala` (L27, L59-60, L270, L699-706, L733) | ReplayCarry 存储、C_WF 处理、non-blocking 逻辑 |

---

## 10. 总结 / Summary

XiangShan 的 Way Prediction Unit 是一个精心设计的低延迟 cache 访问优化模块。其核心设计特点包括：

1. **模块化的算法架构**：三种可选算法（MRU/MMRU/UTag）通过参数化选择，适应不同场景需求
2. **ReplayCarry 优雅恢复机制**：预测失败时携带正确 way 信息的 replay 机制，避免二次失败
3. **Selective Direct Mapping 优化**：通过 Conflict Predictor 动态选择 direct-mapped 或 set-associative 访问
4. **与流水线的无缝集成**：从 S0 预测到 S1 验证到 S2 恢复的完整流水线支持
5. **丰富的可观测性**：完善的性能计数器用于准确率监控和调优

虽然 WPU 在默认配置下是禁用的，但其完整的实现表明 XiangShan 设计团队已经为其在量产版本中的启用做好了充分准备。在高频率、高负载场景下，WPU 可以显著降低 load-to-use 延迟，提升处理器的 IPC 性能。
