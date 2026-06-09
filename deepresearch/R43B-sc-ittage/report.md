# R43B - SC & ITTAGE Predictors Deep Dive

## 1. Overview

XiangShan 的分支预测前端包含两个关键的修正/辅助预测器：**Statistical Corrector (SC)** 和 **Indirect Target TAGE (ITTAGE)**。SC 作为 TAGE 条件分支预测器的后处理修正层，通过多维历史特征的有符号计数器累加来纠正 TAGE 的系统性偏差；ITTAGE 则通过多历史长度的 tag 表实现间接跳转目标的高精度预测。本报告从源码层面深度剖析两者的算法设计、数据结构、流水线实现及训练机制。

---

## 2. SC: Statistical Corrector 概述

SC 的核心思想来自 Andre Seznec 的 SC 算法族（TAGE-SC-L 架构的一部分）。其目标是：在 TAGE provider 已给出 taken/not-taken 判断后，利用额外的统计特征（path history、global history、backward history、IMLI、bias）构建一个修正信号。如果修正信号的累加值足够强（超过阈值），则翻转 TAGE 的预测。

SC 的入口定义在 `src/main/scala/xiangshan/frontend/bpu/sc/Sc.scala`（917 行），参数配置在同目录下的 `Parameters.scala`，辅助逻辑和 trait 在 `Helpers.scala` 和 `Bundles.scala` 中。

### 2.1 五类子表与八表设计

SC 共实例化了 **8 张子表**（默认配置），分为 5 种类型：

| 类型 | 数量 | 说明 | 默认 Set/Way 配置 |
|------|------|------|-------------------|
| **Path Table** | 2 | 基于 Path History 的索引表 | (128,8) + (128,16) |
| **Global Table** | 2 | 基于 Global History Register (GHR) 的索引表 | (128,8) + (128,16) |
| **Backward Table (BW)** | 2 | 基于 Backward History 的索引表 | (128,4) + (128,8) |
| **IMLI Table** | 1 | 基于 Inner Most Loop Iteration count 的索引表 | (128,8) |
| **Bias Table** | 1 | 仅基于 PC 地址的 bias 表 | (128, 4 ways) |

总表数 `NumTables = NumPathTables(2) + NumGlobalTables(2) + NumBWTables(2) + NumImliTable(1) + NumBiasTable(1) = 8`。

这些参数均在 `Parameters.scala` 中通过 `ScParameters` case class 定义，每种表都可以通过 `Enable` 开关独立使能/禁用：

```scala
PathEnable:  Boolean = true
GlobalEnable: Boolean = true
BWEnable:    Boolean = true
ImliEnable:  Boolean = true
BiasEnable:  Boolean = true
```

#### 2.1.1 Path Table - 路径历史表

Path Table 利用 **folded path history** 与 PC 地址的 XOR 作为 set index。其索引计算通过 `PathTableHelper` trait 实现（`Helpers.scala` 第 127-136 行）：

```scala
def getPathTableIdx(pc: PrunedAddr, info: FoldedHistoryInfo, allFh: PhrAllFoldedHistories): UInt = {
  val idxFoldedHist = allFh.getHistWithInfo(info).foldedHist
  addrFields.extract("setIdx", pc) ^ idxFoldedHist
}
```

path history 反映了分支跳转序列的路径信息，不同历史长度（8 位和 16 位 folded）提供了不同粒度的路径区分能力。

#### 2.1.2 Global Table - 全局历史表

Global Table 利用 **GHR (Global History Register)** 的低位片段与 PC 的 XOR 作为索引。索引计算通过 `CommonTableHelper` trait 实现（`Helpers.scala` 第 138-147 行）：

```scala
def getTableIdx(pc: PrunedAddr, hist: UInt): UInt = {
  val foldedHist = computeFoldedHist(hist, SetIdxWidth)(HistoryLength)
  addrFields.extract("setIdx", pc) ^ foldedHist
}
```

Global Table 依赖 `commonHR` 有效信号 -- 当 `s0_commonHR.valid` 为 false 时，全局表的预测请求不会发出，响应会被置零。

#### 2.1.3 Backward Table (BW) - 后向历史表

BW Table 使用 **backward history**（`commonHR.bw`）作为索引输入，其计算方式与 Global Table 相同（共享 `CommonTableHelper` trait），但使用独立的历史片段。Backward history 捕获了分支的后向跳转模式，对循环等模式尤其有效。

#### 2.1.4 IMLI Table - 内循环迭代表

IMLI (Inner Most Loop Iteration) Table 使用循环迭代计数器（`io.imli`，宽度为 `ImliHistoryLength` 位）与 PC 的 XOR 作为索引。这是一张单实例表，专门捕获最内层循环的迭代行为模式。

#### 2.1.5 Bias Table - 偏置表

Bias Table 是最简单的表，其索引仅基于 PC 地址（不使用任何历史信息），通过 `BiasTableHelper` trait 实现。它的特殊之处在于 **way 数量为 `NumWays << BiasUseTageBitWidth`**（默认为 `4 << 2 = 16` ways），即在常规 way 索引之上额外使用 TAGE taken counter 的低位（`valid && isWeak`、`valid && taken`）扩展为 4 个子分区（low bits）。这使得 Bias Table 能够针对 TAGE 的不同置信度状态存储不同的 bias 值。

### 2.2 Banked SRAM 组织

每张子表内部都使用 **banked SRAM** 组织（默认 `NumBanks = 2`）。地址被分为：
- `bankIdx`：最低位，用于 bank 选择
- `setIdx`：中间位，用于 set 内寻址
- `shiftBits`：最高位（用于 fetch block 对齐）

每张子表实例化 `SRAMTemplate`，配合 `WriteBuffer` 实现读写解耦。`WriteBuffer`（默认深度 `WriteBufferSize = 4`）缓冲训练写请求，当 SRAM 读端口空闲时才将写操作推入 SRAM，避免读写冲突阻塞预测流水线。

---

## 3. Counter Accumulation and Threshold Mechanism

### 3.1 Signed Saturate Counter (ScEntry)

每个 SC 表项（`ScEntry`）仅包含一个 **signed saturating counter**（`SignedSaturateCounter`），宽度为 `CtrWidth = 6` 位。其值域为 [-32, +31]，正值偏向预测 taken，负值偏向预测 not-taken。

### 3.2 Percentile Sum (PercSum) 计算

SC 的核心决策基于所有表项 counter 的 **有符号扩展累加**。在 `Helpers.scala` 第 57 行定义了 `getPercsum`：

```scala
def getPercsum(ctr: SInt): SInt = Cat(ctr, 1.U(1.W)).asSInt
```

这是一个关键操作：将 6 位 counter 左移 1 位（末尾补 1），得到 7 位有符号值。这意味着 counter=0 映射为 +1（弱 taken），counter=-1 映射为 -1。这种映射使得中性状态略微偏向 taken，需要更强的反向证据才能翻转。

在流水线中，累加分为多个层次：

1. **分类累加**（Stage 1）：每个表类型的多个表各自累加其 counter percsum：
   ```scala
   s1_pathPercsum(w) = pathTableResp.map(entry => getPercsum(entry(w).ctr.value)).reduce(_ +& _)
   ```

2. **合并累加**（Stage 1）：除 Bias 外的所有表的 percsum 被合并：
   ```scala
   s1_mergeResp = pathResp ++ globalResp ++ bwResp ++ Seq(imliResp)
   s1_sumPercsum(j) = ParallelSingedExpandingAdd(s1_mergePercsum.map(_(j)))
   ```

3. **最终累加**（Stage 2）：加上 Bias 表的 percsum：
   ```scala
   s2_totalPercsumAll(wayIdx)(lowBits) = biasPercsum +& s2_sumPercsum(idx)
   ```

最终 `s2_totalPercsum` 是一个 **有符号扩展加法**（`ParallelSingedExpandingAdd`），使用 `+&` 操作确保位宽自动扩展，避免溢出。

### 3.3 Threshold 判决机制

SC 是否采纳修正信号取决于累加值是否超过 **自适应阈值**。判决函数在 `Helpers.scala` 第 59-60 行定义：

```scala
def aboveThreshold(scSum: SInt, threshold: UInt): Bool =
  ((scSum > threshold.zext) && pos(scSum)) || ((scSum < -threshold.zext) && neg(scSum))
```

这意味着：
- 如果累加值为正且绝对值超过阈值 -> SC 认为应该 taken
- 如果累加值为负且绝对值超过阈值 -> SC 认为应该 not-taken
- 如果累加值的绝对值不足阈值 -> SC 不干预（保持 TAGE 预测）

---

## 4. Adaptive Threshold Adjustment

SC 的一个关键创新是其 **per-way 自适应阈值**。每个 way（即 fetch block 中的每个分支槽位）维护一个独立的阈值计数器（`ThresholdCounter`），宽度为 `ThresholdWidth = 13` 位。

### 4.1 阈值范围

```scala
MinThreshold  = (NumTables + 5) << 6  // = 13 << 6 = 832
MaxThreshold  = min((NumTables * 63) << 4, (1 << ThresholdWidth) - 1)
ThresholdInit = 1130  // 初始化为中间值
```

`MinThreshold` 的设计意图：当 TAGE 置信度较低时，即使所有 counter 的贡献总和较小（约 `NumTables + 5` 个计数单位），也应该允许 SC 修正。`MaxThreshold` 对应所有 counter 饱和时的理论最大值。

### 4.2 动态调整算法

阈值的更新在训练流水线 Stage 1 中计算（`Sc.scala` 第 497-528 行）。对于每个 resolved branch：

1. **判断更新条件**：如果 SC 预测错误（`scWrong`）或者 SC 未被启用（`!sumAboveThres`），则认为需要更新阈值：
   ```scala
   val needUpdate = valid && t1_meta.tagePredValid(oldIdx) && (scWrong || !t1_meta.sumAboveThres(oldIdx))
   ```

2. **统计方向**：对于每个 way，统计需要增加和减少阈值的分支数量：
   ```scala
   inc = PopCount(writeHit.zip(writeDir).map { case (hit, dir) => hit && dir })
   dec = PopCount(writeHit.zip(writeDir).map { case (hit, dir) => hit && !dir })
   ```

3. **净调整**：
   ```scala
   updated = Mux(inc >= dec, oldEntry.getIncrease(inc - dec), oldEntry.getDecrease(dec - inc))
   ```

4. **范围约束**：更新后的值必须在 `[MinThreshold, MaxThreshold]` 范围内，否则保持原值。

### 4.3 Confidence-Dependent Threshold Scaling

SC 使用 TAGE provider counter 的置信度来 **动态缩放阈值**。在预测阶段（Stage 2），根据 TAGE counter 的状态选择不同的阈值缩放因子：

```scala
val tageConfHigh = s2_providerCtr(i).isSaturatePositive || s2_providerCtr(i).isSaturateNegative
val tageConfMid  = s2_providerCtr(i).isMid
val tageConfLow  = s2_providerCtr(i).isWeak

val conf = MuxCase(false.B, Seq(
  (predValid && tageConfHigh) -> s2_sumAboveThresholdShift1(i),  // threshold >> 4
  (predValid && tageConfMid)  -> s2_sumAboveThresholdShift2(i),  // threshold >> 5
  (predValid && tageConfLow)  -> s2_sumAboveThresholdShift3(i)   // threshold >> 6
))
```

- **TAGE 高置信**（counter 饱和）：阈值右移 4 位（除以 16）-- SC 需要非常强的证据才能翻转
- **TAGE 中置信**（counter 中间值）：阈值右移 5 位（除以 32）-- SC 较容易翻转
- **TAGE 低置信**（counter 弱值）：阈值右移 6 位（除以 64）-- SC 几乎总是可以翻转

这种设计体现了 **"越不确定越容易被修正"** 的哲学：当 TAGE 自身不自信时，降低 SC 的干预门槛。

---

## 5. ITTAGE: Indirect Target TAGE 概述

ITTAGE (Indirect Target TAGE) 是针对间接跳转（JALR 等指令）的目标地址预测器，参考了 Seznec 在 CBP-4 (2011) 上发表的 "A 64-Kbytes ITTAGE indirect branch predictor" 论文。

ITTAGE 的核心思想与 TAGE 条件预测类似：使用 **多张不同历史长度的 tag 表**，每张表存储部分目标地址偏移量。预测时选择最长匹配历史的表作为 provider，利用其存储的 offset 与 region 信息重建完整目标地址。

### 5.1 五表设计

ITTAGE 默认配置 5 张表（`Parameters.scala`）：

```scala
TableInfos: Seq[IttageTableInfo] = Seq(
  new IttageTableInfo(256, 4),   // Table 0: 256 sets, history length 4
  new IttageTableInfo(256, 8),   // Table 1: 256 sets, history length 8
  new IttageTableInfo(512, 13),  // Table 2: 512 sets, history length 13
  new IttageTableInfo(512, 16),  // Table 3: 512 sets, history length 16
  new IttageTableInfo(512, 32)   // Table 4: 512 sets, history length 32
)
```

历史长度从 4 位递增到 32 位，set 数量从 256 递增到 512。更长的历史能够捕获更远距离的地址依赖模式，但也更容易过拟合，因此配合 useful counter 进行生命周期管理。

### 5.2 IttageEntry 数据结构

每条 ITTAGE 表项（`Bundles.scala`）包含：

```scala
class IttageEntry(tagLen: Int) {
  val valid:         Bool            // 有效位
  val tag:           UInt            // tag 标识（tagLen 位，默认 9 位）
  val confidenceCnt: SaturateCounter // 置信度计数器（2 位）
  val targetOffset:  IttageOffset    // 目标偏移信息
  val usefulCnt:     SaturateCounter // 有用性计数器（1 位）
  val paddingBit:    UInt            // 填充位（对齐用）
}
```

其中 `IttageOffset` 包含：
```scala
class IttageOffset {
  val offset:      PrunedAddr  // 目标地址偏移（20 位）
  val pointer:     UInt        // 指向 RegionWays 表的指针
  val usePcRegion: Bool        // 是否使用 PC region（而非 RegionWays 存储的 region）
}
```

---

## 6. Region Compression (RegionWays)

ITTAGE 面临一个关键挑战：间接跳转目标地址通常是全宽度的虚拟地址，但表项空间有限，无法存储完整目标地址。ITTAGE 采用 **region compression** 方案来解决这一问题。

### 6.1 地址分解

目标地址被分解为两个部分：
- **Region**（高位）：`RegionBits = VAddrBits - TargetOffsetWidth`（即虚拟地址宽度减去 offset 宽度）
- **Offset**（低位）：`TargetOffsetWidth = 20` 位

例如，如果虚拟地址是 64 位，那么 region 占高 44 位，offset 占低 20 位。

### 6.2 RegionWays 表

`RegionWays`（`RegionWays.scala`）是一个 **全相联的 region 缓存**，维护最近使用的 region 值：

```scala
class RegionWays extends IttageModule {
  private val regions = RegInit(VecInit(Seq.fill(RegionNums)(0.U.asTypeOf(new RegionEntry))))
  // RegionNums = 16, 使用 PLRU 替换策略
}
```

- **RegionNums = 16**：缓存 16 个最近使用的 region
- **RegionReplacer = "plru"**：使用伪 LRU 替换策略
- **RegionPorts = 2**：支持 2 个并行读端口（用于 provider 和 alt-provider 的 meta target 更新）

### 6.3 Region 读取流程

在预测时（Stage 2），每张 ITTAGE 表返回的 `targetOffset.pointer` 用作 RegionWays 的查询地址：

```scala
rTable.io.reqPointer.zipWithIndex.foreach { case (req_pointer, i) =>
  req_pointer := regionReadTargetOffset(i).pointer
}
```

然后根据 RegionWays 的命中情况决定 region 来源：

```scala
regionTargets(i) := PrunedAddrInit(Mux(
  rTable.io.respHit(i) && !regionReadTargetOffset(i).usePcRegion,
  Cat(rTable.io.respRegion(i), regionReadTargetOffset(i).offset.toUInt),  // 从 RegionWays 获取 region
  Cat(targetGetRegion(s2_startPc), regionReadTargetOffset(i).offset.toUInt)  // 使用 PC 的 region
))
```

如果 RegionWays 命中且 `usePcRegion = false`，使用 RegionWays 缓存的 region；否则 fallback 到当前 PC 的 region。这种两级 fallback 机制确保即使 RegionWays 未命中，预测仍然有效（只是精度可能降低）。

### 6.4 Region 写入与更新

当进行训练更新且需要分配新表项时，如果目标地址的 region 与 PC region 不同，就会写入 RegionWays：

```scala
rTable.io.writeValid  := !updateRealUsePCRegion && updateAlloc.reduce(_ || _)
rTable.io.writeRegion := updateRealTargetRegion
```

RegionWays 内部的写入逻辑（`RegionWays.scala` 第 54-62 行）检查是否已存在相同 region（命中则更新指针），否则使用 PLRU 选择替换位置。新条目的 `valid` 位在首次写入时被设为 true。

---

## 7. ITTAGE Tag Generation and Matching

### 7.1 Hash 函数

每张 ITTAGE 表使用 PC 和 folded history 计算三元组 `(bankIdx, setIdx, tag)`。在 `IttageTable.scala` 第 93-109 行：

```scala
def computeTagAndHash(unhashedIdx: UInt, allFh: PhrAllFoldedHistories): (UInt, UInt, UInt) = {
  val idxFh      = allFh.getHistWithInfo(idxFhInfo).foldedHist
  val tagFh      = allFh.getHistWithInfo(tagFhInfo).foldedHist
  val altTagFh   = allFh.getHistWithInfo(altTagFhInfo).foldedHist
  val bankIdx    = unhashedIdx(BankIdxWidth - 1, 0)
  val setIdxBase = unhashedIdx(BankIdxWidth + SetIdxWidth - 1, BankIdxWidth)
  val setIdx     = (setIdxBase ^ idxFh)(SetIdxWidth - 1, 0)
  val tagBase    = (unhashedIdx >> FullIdxWidth).asUInt
  val tag        = (tagBase ^ tagFh ^ (altTagFh << 1).asUInt)(tagLen - 1, 0)
  (bankIdx, setIdx, tag)
}
```

关键设计：
- **setIdx** = PC 的 set 部分 XOR folded history（历史长度折叠到 setIdxWidth 位）
- **tag** = PC 的 tag 部分 XOR folded tag history XOR (folded alt tag history << 1)
- **altTagFh** 使用 `tagLen - 1` 位的历史长度，与 tagFh 形成双重 hash，降低 tag collision 概率
- **bankIdx** 直接取 PC 的低位

每个 folded history 信息（`FoldedHistoryInfo`）记录了原始历史长度和折叠后的宽度，确保不同表使用对应历史长度的信息。

### 7.2 Tag 匹配

在预测 Stage 1，读取 SRAM 后进行 tag 比较：

```scala
private val s1_reqReadHit = tableReadData.valid && (if (tagLen != 0) tableReadData.tag === s1_tag else true.B)
```

当 `tagLen > 0` 时，需要 tag 精确匹配；对于无 tag 的表（理论上不会出现），直接视为命中。

---

## 8. Confidence-Based Provider/Alt Selection

### 8.1 ParallelSelectTwo 机制

ITTAGE 使用 `ParallelSelectTwo` 工具函数从 5 张表中并行选出 **最长历史的两个命中表**：

```scala
private val inputRes = VecInit(s2_resps.zipWithIndex.map {
  case (r, i) =>
    val tableInfo = Wire(new IttageTableInfo)
    tableInfo.tableIdx = i.U(...)
    SelectTwoInterRes(r.valid, tableInfo)
})
private val selectedInfo = ParallelSelectTwo(inputRes.reverse)
```

注意 `inputRes.reverse` -- 反转后高历史长度的表排在前面，确保选出的是最长匹配的两个表。

- `selectedInfo.hasOne` / `selectedInfo.hasTwo`：是否找到一个/两个命中
- `selectedInfo.first` / `selectedInfo.second`：第一个/第二个命中表的信息

### 8.2 Provider Null 判断

当 provider 的 confidence counter 处于饱和负值（`isSaturateNegative`）时，provider 被认为是 "null"：

```scala
private val providerNull = providerInfo.cnt.isSaturateNegative
```

### 8.3 最终目标选择

最终的 ITTAGE 目标地址通过以下优先级选择（`Ittage.scala` 第 249-255 行）：

```scala
s2_ittageTarget := MuxCase(0.U, Seq(
  (provided && !(providerNull && altProvided)) -> providerCatTarget,
  (providerNull && altProvided)                -> altProviderCatTarget
))
```

决策逻辑：
1. **provider 有效且置信度足够** -> 使用 provider 的目标
2. **provider 有效但置信度饱和负值（null），且 alt-provider 有效** -> 使用 alt-provider 的目标
3. **provider 无效** -> 不输出 ITTAGE 预测（target = 0）

### 8.4 Confidence Counter 更新

Confidence counter（2 位 saturating counter）在训练时更新：

```scala
updateWdata.confidenceCnt := Mux(
  io.update.alloc,
  ConfidenceCounter.WeakPositive,  // 新分配时初始化为弱正值
  oldCtr.getUpdate(io.update.correct)
)
```

- **正确**：counter 向饱和正值方向递增
- **错误**：counter 向饱和负值方向递减
- **新分配**：初始化为 weak positive（中性偏正）

---

## 9. Training Pipeline Details

### 9.1 SC 训练流水线

SC 的训练流水线分为 3 个阶段：

#### Stage 0 (t0): 索引计算与冲突检测

- 接收来自 `io.train` 的训练信息
- 计算所有子表的 train read index
- 检测 bank 冲突：如果训练需要写入且与当前预测使用相同的 bank mask，设置 `io.trainReady := false`，阻止新预测进入

训练有效条件（`t0_writeValidVec`）非常精细：

```scala
t0_writeValidVec = t0_branches.zip(...).map {
  case (((b, hit), predIdx), taken) =>
    b.valid && b.bits.attribute.isConditional && hit &&
    t0_meta.tagePredValid(predIdx) &&
    (!(t0_meta.useScPred(predIdx) && t0_meta.scPred(predIdx) === taken) ||
     !(t0_meta.useScPred(predIdx) && t0_meta.tagePredValid(predIdx) &&
       t0_meta.scPred(predIdx) === t0_meta.tagePred(predIdx)))
}
```

这个条件确保：只有当 SC 的预测与实际方向不一致，或者 SC 没有改变 TAGE 的预测结果时，才需要更新。

#### Stage 1 (t1): 新条目计算

- 读取所有子表的旧条目
- 调用 `updateEntry()` helper 计算新条目
- 计算新的阈值
- Bias 表单独处理（使用 2D 索引：way + TAGE low bits）

`updateEntry`（`Helpers.scala` 第 63-96 行）的累加逻辑支持 **多分支同时更新同一 set 的不同 way**：

```scala
inc = PopCount(writeHit.zip(writeDir).map { case (hit, dir) => hit && dir })
dec = PopCount(writeHit.zip(writeDir).map { case (hit, dir) => hit && !dir })
newEntry.ctr = Mux(inc >= dec, oldEntry.ctr.getIncrease(inc - dec), oldEntry.ctr.getDecrease(dec - inc))
```

#### Stage 2 (t2): SRAM 写回

- 计算 wayMask（仅写入 counter 实际变化的 way）
- 通过 `WriteBuffer` 将新条目写入 SRAM
- 阈值寄存器在 `t1_writeValid` 有效时更新

### 9.2 ITTAGE 训练流水线

ITTAGE 的训练在 `Ittage.scala` 中实现，主要逻辑在 Stage 0-1：

#### Training Entry 选择

从训练分支中选择需要 ITTAGE 更新的分支：

```scala
val trainBranchIdxVec = VecInit(t1_train.branches.map(b =>
  b.valid && b.bits.attribute.needIttage && b.bits.taken
))
assert(PopCount(trainBranchIdxVec) <= 1.U)  // 每次最多训练一个分支
```

#### Provider 更新

当 provider 存在时：
- 如果使用了 alt-pred 且预测错误 -> 同时更新 alt-provider 的 counter
- 更新 provider 的 useful counter（仅在 provider 和 alt-provider 预测方向不同时记录）
- 更新 provider 的 confidence counter

```scala
updateUsefulCnt(provider) := Mux(
  !t1_meta.altDiffers,
  t1_meta.providerUsefulCnt,  // alt 和 provider 相同 -> 不改变 useful
  (t1_meta.providerTarget === updateRealTarget).asTypeOf(UsefulCounter())  // 不同 -> 记录 provider 是否正确
)
```

#### 新表项分配

当预测错误且不满足 "provider 正确但置信度低" 的特殊情况时，尝试分配新表项：

```scala
private val s2_allocatableSlots = VecInit(s2_resps.map(r => !r.valid && r.bits.usefulCnt.isSaturateNegative)).asUInt &
  (~(LowerMask(UIntToOH(s2_provider), NumTables) & Fill(NumTables, s2_provided.asUInt))).asUInt
```

分配条件：
1. 表项未命中（`!r.valid`）
2. useful counter 处于饱和负值（"无用" 状态）
3. 使用 LFSR 随机化选择，避免 always 选择同一位置

分配时 new entry 的 confidence counter 初始化为 weak positive，useful counter 初始化为饱和负值。

#### Tick Counter 与 Useful Reset

ITTAGE 维护一个全局 `tickCnt`（8 位），每次未分配新表项时递增。当 `tickCnt` 饱和时，所有表的所有 useful counter 被重置为饱和负值。这是一种 **useful aging** 机制，防止无用条目永久占用表空间：

```scala
when(tickCnt.isSaturatePositive) {
  tickCnt.resetZero()
  updateResetUsefulCnt := true.B
}
```

重置操作通过 `IttageTable` 中的 `needReset` 信号和逐 set 扫描的 counter 实现（`IttageTable.scala` 第 183-191 行），每周期重置一个 set，避免大范围并行写入的功耗问题。

### 9.3 ITTAGE Target Offset 更新策略

在 `IttageTable.scala` 第 257-261 行，target offset 的更新条件非常精细：

```scala
updateWdata.targetOffset := Mux(
  io.update.alloc || oldCtr.isSaturateNegative,
  io.update.targetOffset,       // 新分配或 counter 饱和负值 -> 写入新目标
  io.update.oldTargetOffset     // 否则 -> 保持旧目标（仅更新 counter）
)
```

只有在 **新分配** 或 **confidence counter 已饱和负值**（说明旧目标严重不可信）时，才更新存储的目标偏移量。这避免了 counter 还在合理范围时频繁更换目标导致的不稳定。

---

## 10. Performance Monitoring

SC 和 ITTAGE 都包含了丰富的性能计数器：

### SC 性能计数器

- `sc_correct_tage_wrong` / `sc_wrong_tage_correct`：SC 和 TAGE 的对错交叉统计
- `t1_use_sc` / `t1_not_use_sc`：SC 是否被实际用于修正
- `sc_path_correct` / `sc_global_correct` 等：各子表独立的准确率
- `threshold_try_overflow` / `threshold_try_underflow`：阈值调整边界情况
- `sc_global_table_invalid` / `sc_global_table_valid`：GHR 有效率

### ITTAGE 性能计数器

- `ittage_used` / `ittage_hit`：使用率和命中率
- `ittage_allocate`：新表项分配次数
- `ittage_reset_u`：useful counter 全局重置次数
- `ittage_table_read_write_conflict`：读写冲突统计
- `table_i_final_provided`：各表作为最终 provider 的频率

### SC Trace 机制

SC 还支持 ChiselDB trace（通过 `EnableScTrace` 开关），记录每次条件分支预测的完整状态，包括各子表的 counter 值、provider 信息、预测结果和实际结果，用于离线分析和调试。

---

## 11. Source File Locations

| 文件路径 | 行数 | 说明 |
|----------|------|------|
| `src/main/scala/xiangshan/frontend/bpu/sc/Sc.scala` | 917 | SC 主模块：预测流水线、训练流水线、性能计数器 |
| `src/main/scala/xiangshan/frontend/bpu/sc/Parameters.scala` | 101 | SC 参数定义：表配置、阈值参数、使能开关 |
| `src/main/scala/xiangshan/frontend/bpu/sc/ScTable.scala` | 129 | SC 表实现：banked SRAM、WriteBuffer、读写端口 |
| `src/main/scala/xiangshan/frontend/bpu/sc/Helpers.scala` | 154 | SC 辅助逻辑：索引计算 trait、updateEntry、阈值判决 |
| `src/main/scala/xiangshan/frontend/bpu/sc/Bundles.scala` | 125 | SC 数据结构：ScEntry、ScMeta、ThresholdCounter、Trace |
| `src/main/scala/xiangshan/frontend/bpu/ittage/Ittage.scala` | 506 | ITTAGE 主模块：预测选择、训练更新、分配逻辑 |
| `src/main/scala/xiangshan/frontend/bpu/ittage/IttageTable.scala` | 315 | ITTAGE 单表实现：hash/tag、SRAM bank、WriteBuffer |
| `src/main/scala/xiangshan/frontend/bpu/ittage/Parameters.scala` | 87 | ITTAGE 参数定义：表配置、tag/counter 宽度、region 配置 |
| `src/main/scala/xiangshan/frontend/bpu/ittage/Bundles.scala` | 88 | ITTAGE 数据结构：IttageEntry、IttageOffset、IttageMeta |
| `src/main/scala/xiangshan/frontend/bpu/ittage/RegionWays.scala` | 92 | Region 压缩缓存：全相联查找、PLRU 替换、读写端口 |

---

## 12. Design Insights and Trade-offs

### 12.1 SC 的设计哲学

SC 的 8 表设计体现了 **特征多样性** 原则：path history 捕获路径依赖，global history 捕获全局分支模式，backward history 捕获回跳模式，IMLI 捕获循环迭代，bias 捕获地址相关的静态偏好。每种特征都可能对特定分支模式提供修正信号。

**多 way 并行处理**（`NumWays = NumBtbResultEntries`）允许 SC 同时处理 fetch block 中的多个条件分支，每个 way 独立维护阈值和累加。

**自适应阈值**的核心思想是：SC 只在自己"确信"时才干预。如果阈值太低，SC 会频繁错误翻转 TAGE 的正确预测；如果阈值太高，SC 会错过修正机会。动态调整算法通过统计修正准确率来自动寻找平衡点。

### 12.2 ITTAGE 的设计哲学

ITTAGE 的 region compression 是其核心创新之一。通过将地址分解为 region + offset 并维护共享的 region 缓存，ITTAGE 在有限的表项空间中实现了对全宽度目标地址的近似存储。`usePcRegion` 标志位提供了优雅的降级路径。

**Useful counter aging** 机制确保表项空间不会被过时的条目永久占用。全局 tick counter 的饱和触发所有 useful counter 的重置，这是一个简单但有效的生命周期管理策略。

**Provider null 机制**（confidence counter 饱和负值）允许 ITTAGE 在 provider 不可信时透明地 fallback 到 alt-provider，避免了单点失败。

### 12.3 WriteBuffer 的作用

SC 和 ITTAGE 都使用 `WriteBuffer` 来解耦预测读和训练写。这是因为 SRAM 使用 single-port 模式（`singlePort = true`），同一周期只能执行一个操作。WriteBuffer 缓冲写请求，只在读端口空闲时推入写操作，确保预测流水线不会因为训练写入而停顿。

### 12.4 Bank Conflict 处理

SC 的 bank conflict 检测（`t0_bankConflict`）是一个关键的流水线控制点。当训练写入与当前预测访问相同的 bank 时，SC 通过 `io.trainReady := false` 阻止新预测进入，等待冲突消解。这保证了数据一致性，但可能引入少量流水线气泡。
