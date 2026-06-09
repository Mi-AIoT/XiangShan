# R43A - TAGE Predictor Algorithm Deep Dive (XiangShan)

## 概述

TAGE (TAgged GEometric history length predictor) 是 XiangShan 处理器分支预测单元 (BPU) 中的核心条件分支预测器。XiangShan 的实现基于经典 TAGE 论文的思想，同时针对高性能流水线做了大量工程优化。本报告深入分析 `src/main/scala/xiangshan/frontend/bpu/tage/` 目录下的全部实现代码，涵盖 8 张几何历史表的组织结构、folded history 计算、tag 生成与比对、provider/alt 选择状态机、UseAltOnNa 机制、useful counter 管理、misprediction 时的 entry 分配、训练流水线 (T0-T3) 的 bank conflict 处理，以及与 SC (Statistical Corrector) 统计校正器的交互。

源码文件:
- `Tage.scala` (788 lines) - 主预测器模块
- `TageTable.scala` (239 lines) - 单张 TAGE 表的 SRAM 读写实现
- `Parameters.scala` (84 lines) - 参数定义与 trait
- `Bundles.scala` (218 lines) - 所有 Bundle 数据结构定义
- `Helpers.scala` (72 lines) - 地址解析、folded history 组装、tag 计算辅助函数

---

## 1. TAGE 表组织结构 (8 Tables, Geometric History)

### 1.1 表数量与几何历史长度

XiangShan TAGE 共有 **8 张表** (`NumTables = 8`)，每张表使用不同的历史长度，形成几何级数增长的序列。参数定义在 `Parameters.scala` 的 `TageParameters` 中:

```
TableInfos = Seq(
  TageTableInfo(4096, 2, 4),    // Table 0: history length = 4
  TageTableInfo(4096, 2, 9),    // Table 1: history length = 9
  TageTableInfo(4096, 2, 17),   // Table 2: history length = 17
  TageTableInfo(4096, 2, 29),   // Table 3: history length = 29
  TageTableInfo(4096, 2, 56),   // Table 4: history length = 56
  TageTableInfo(4096, 2, 109),  // Table 5: history length = 109
  TageTableInfo(4096, 2, 211),  // Table 6: history length = 211
  TageTableInfo(4096, 2, 397)   // Table 7: history length = 397
)
```

每张表的参数含义为 `TageTableInfo(Size, NumWays, HistoryLength)`:
- **Size = 4096**: 每张表的总 entry 数量 (含所有 bank 和 way)
- **NumWays = 2**: 每个 bank 中的 way 数量 (2-way set associative)
- **HistoryLength**: 该表使用的全局路径历史 (path history) 长度

历史长度序列 4, 9, 17, 29, 56, 109, 211, 397 近似满足几何比值约 1.95x，这是 TAGE 论文推荐的最优几何比值。这种设计确保短历史表能够快速捕获近期的分支模式变化，而长历史表能够学习深度嵌套循环中的复杂分支行为。

### 1.2 Bank 架构

所有表共享 **4 个 bank** (`NumBanks = 4`)，bank 索引由 PC 的低位 bit 提取:

```scala
// Helpers.scala - TableHelper trait
def getBankIndex(pc: PrunedAddr): UInt =
  addrFields.extract("bankIdx", pc)
```

`addrFields` 的定义为:
```
Seq(
  ("instOffset", instOffsetBits),  // 指令偏移 (指令粒度内)
  ("bankIdx", BankIdxWidth),       // bank 索引 (log2Ceil(NumBanks) = 2 bits)
  ("setIdx", SetIdxWidth),         // set 索引 (因表而异)
  ("tag", TagWidth)                // tag (13 bits)
)
```

每张表的实际 set 数量 = Size / NumWays / NumBanks = 4096 / 2 / 4 = **512 sets**。所有 8 张表的 set 数量一致 (`MaxNumSets = 512`)。Bank 化设计的核心目的是在 single-port SRAM 上实现 predict 读端口和 train 读端口的 bank-level interleaving，只要两者的 bank index 不同即可同时访问。

### 1.3 SRAM 存储结构

每张 `TageTable` 内部实例化了两套 SRAM 阵列:

**Entry SRAM** (`entrySram`): 存储 TageEntry (valid + tag + takenCtr)，每个 bank 的每个 way 有一个 SRAM 实例:
```scala
private val entrySram =
  Seq.tabulate(NumBanks, NumWays) { (bankIdx, wayIdx) =>
    Module(new SRAMTemplate(
      new TageEntry,
      set = NumSets,  // 512
      way = 1,        // 每个 SRAM 实例只含 1 way
      singlePort = true,
      shouldReset = true,
      withClockGate = true
    ))
  }
```

**Useful Counter SRAM** (`usefulCtrSram`): 使用 `FoldedSRAMTemplate` 存储 useful counter，通过 folded 技术节省面积。Fold factor 为 `NumUsefulCtrSramFolds = 8`:
```scala
private val usefulCtrSram =
  Seq.tabulate(NumBanks, NumWays) { (bankIdx, wayIdx) =>
    Module(new FoldedSRAMTemplate(
      UsefulCounter(),
      set = NumSets,
      width = NumUsefulCtrSramFolds,  // 8: fold factor
      way = 1,
      singlePort = true,
      shouldReset = true,
      withClockGate = true
    ))
  }
```

total SRAM 实例数: 8 tables x 4 banks x 2 ways x 2 types = **128 个 SRAM 实例**。

### 1.4 Write Buffer 机制

由于 SRAM 为 single-port，读写不能同时进行。TageTable 使用两级缓冲:

1. **Entry WriteBuffer** (`entryWriteBuffers`): 每个 bank 一个 `WriteBuffer` 实例，深度为 `WriteBufferSize = 4`，支持多端口写入 (对应多个 way)
2. **Useful Counter Write Buffer** (`usefulCtrWriteBuffers`): 每个 bank 的每个 way 一个 `Queue` 实例，深度为 4

写入 SRAM 时的仲裁逻辑: 只有在没有读请求时才将缓冲区内容写入 SRAM:
```scala
way.io.w.apply(
  bufferOut.valid && !way.io.r.req.valid,  // 读优先于写
  bufferOut.bits.entry,
  bufferOut.bits.setIdx,
  1.U(1.W)
)
bufferOut.ready := way.io.w.req.ready && !way.io.r.req.valid
```

---

## 2. Folded History 计算与 XOR Hashing

### 2.1 Folded History 的概念

全局历史寄存器 (GHR) 可能很长 (最长 397 bits)，直接用于 index 计算和 tag 计算会导致过宽的 XOR tree 和过大的面积。XiangShan 使用 **folded history** 技术将长历史折叠为较短的 hash 值。折叠方法是将长历史按折叠宽度分段，然后对各段做 XOR。

每张 TAGE 表需要 **3 个 folded history 值**，定义在 `Types.scala` 的 `FoldedHistoryInfo` 中:

```scala
// 对于每张表，生成 3 种 folded history:
new FoldedHistoryInfo(HistoryLength, min(HistoryLength, log2Ceil(NumSets)))     // 折叠到 9 bits
new FoldedHistoryInfo(HistoryLength, min(HistoryLength, TagWidth))              // 折叠到 13 bits
new FoldedHistoryInfo(HistoryLength, min(HistoryLength, TagWidth - 1))          // 折叠到 12 bits
```

- **foldedHist[0]**: 用于计算 set index, 折叠到 `log2Ceil(NumSets) = 9 bits`
- **foldedHist[1]**: 用于计算 tag 的第一部分, 折叠到 `TagWidth = 13 bits`
- **foldedHist[2]**: 用于计算 tag 的第二部分, 折叠到 `TagWidth - 1 = 12 bits`

对于 historyLength < foldedLength 的短表 (如 table 0, history=4)，foldedLength 直接等于 historyLength，不需要折叠。

### 2.2 Folded History 的组装

在 `Helpers.scala` 的 `TopHelper` trait 中，`getFoldedHist` 函数将外部 PHR (Path History Register) 提供的全局 folded history 按表索引提取并封装为 `TageFoldedHist`:

```scala
def getFoldedHist(allFoldedPathHist: PhrAllFoldedHistories): Vec[TageFoldedHist] =
  VecInit(TableInfos.map { implicit tableInfo =>
    val tageFoldedHist = tableInfo.getTageFoldedHistoryInfo(NumBanks, TagWidth).map { histInfo =>
      allFoldedPathHist.getHistWithInfo(histInfo).foldedHist
    }
    val foldedHist = Wire(new TageFoldedHist)
    foldedHist.forIdx    := tageFoldedHist.head                    // 9 bits, for set index
    foldedHist.forTag(0) := tageFoldedHist(1)                     // 13 bits, tag hash part 0
    foldedHist.forTag(1) := Cat(tageFoldedHist(2), 0.U(1.W))      // 13 bits, tag hash part 1 (左移 1 bit)
    foldedHist
  })
```

关键设计: `forTag(1)` 通过 `Cat(tageFoldedHist(2), 0.U(1.W))` 将 12-bit folded history 左移 1 bit 后拼接为 13 bits。这种偏移确保两个 tag hash 部分的位域不完全重叠，从而提高 hash 的分散性，减少不同历史产生相同 tag 的 aliasing 概率。

### 2.3 XOR Hashing 用于 Set Index 和 Tag

在 `Helpers.scala` 的 `TableHelper` trait 中定义了地址字段的提取和 hash 方法:

```scala
// Set index: PC 的 setIdx 字段 XOR folded history for index
def getSetIndex(pc: PrunedAddr, hist: UInt): UInt =
  addrFields.extract("setIdx", pc) ^ hist

// Raw tag (不含 position): PC 的 tag 字段 XOR 两段 folded history
def getRawTag(pc: PrunedAddr, hist: Vec[UInt]): UInt =
  addrFields.extract("tag", pc) ^ hist(0) ^ hist(1)

// Final tag (含 position): rawTag XOR cfiPosition
def getTag(pc: PrunedAddr, hist: Vec[UInt], position: UInt): UInt =
  addrFields.extract("tag", pc) ^ hist(0) ^ hist(1) ^ position
```

这种三层 XOR hash 设计 (PC bits ^ foldedHist(0) ^ foldedHist(1) ^ position) 的优势:
- 引入 PC 地址信息: 不同 PC 即使历史相同也会产生不同的 index/tag
- 引入多段 folded history: 使用两种不同折叠宽度的历史分段，增加 hash 的独立性
- 引入 cfiPosition: 同一 fetch block 中不同分支得到不同的 tag

---

## 3. Tag 生成与比对逻辑

### 3.1 Tag 生成的两阶段设计

XiangShan 将 tag 计算分为 **predict 路径** (s0-s1-s2) 和 **train 路径** (t0-t1-t2)，这种设计是为了时序优化。

**Predict 路径** (在 s1 阶段一次性计算完整 tag):
```scala
// Tage.scala s1 阶段: 直接使用 cfiPosition 计算完整 tag
private val s1_tag = VecInit(io.fromMainBtb.s1_positions.map { position =>
  VecInit((tables zip s1_foldedHist).map { case (table, hist) =>
    table.getTag(s1_startPc, hist.forTag, position)
  })
})
```

**Train 路径** (分两步计算: t1 计算 rawTag, t2 再 XOR position):
```scala
// t1 阶段: 计算不含 position 的 rawTag (减轻 t2 关键路径)
private val t1_rawTag = VecInit((tables zip t1_foldedHist).map { case (table, hist) =>
  table.getRawTag(t1_startPc, hist.forTag)
})

// t2 阶段: rawTag XOR position 作为最终 tag
val tag = t2_rawTag(tableIdx) ^ position
```

train 路径分离 rawTag 和 final tag 的原因是: t2 阶段已经是一个逻辑密集的阶段 (需要对 8 张表的读结果做 tag 比对、选择 provider/alt、计算各种训练信号)。将 13-bit XOR operation 从 t1 提前可以缓解 t2 的时序压力。而且 SRAM 读取本身就在 t1 阶段完成，t1 有额外的时序余量。

### 3.2 Tag 比对逻辑

Tag 比对在 **s2 阶段** (predict) 和 **t2 阶段** (train) 进行。比对逻辑结构一致:

```scala
// s2 阶段的 tag 比对 (predict path)
val allTableTagMatchResults = s2_readResp.zipWithIndex.map { case (tableReadResp, tableIdx) =>
  val tag          = s2_tag(i)(tableIdx)
  val hitWayMask   = tableReadResp.entries.map(entry => entry.tag === tag)  // 对每个 way 做 tag 比较
  val hitWayMaskOH = PriorityEncoderOH(hitWayMask)                          // 优先选择第一个 hit way

  val result = Wire(new PredictTagMatchResult)
  result.hit          := hitWayMask.reduce(_ || _)                    // 任一 way hit 则为 true
  result.hitWayMaskOH := hitWayMaskOH.asUInt                          // one-hot hit way mask
  result.takenCtr     := Mux1H(hitWayMaskOH, tableReadResp.entries.map(_.takenCtr))
  result.usefulCtr    := Mux1H(hitWayMaskOH, tableReadResp.usefulCtrs)
  result.hitWayMask   := hitWayMask.asUInt                            // 用于性能监控
  result
}
```

关键设计点:
- 每张表有 2 ways，tag 比较后通过 `PriorityEncoderOH` 在命中的 ways 中选择优先级最高的一路
- 使用 `Mux1H` (one-hot MUX) 提取命中 entry 的 takenCtr 和 usefulCtr
- SRAM 读结果通过 `DataHoldBypass` 在 s1->s2 跨越时保持稳定
- 存在多 way 同时 hit 的可能性，代码中有对应的性能监控: `multihit_on_same_table`

### 3.3 SRAM 读冲突断言

在 TageTable 中有一个重要的断言，确保同一 bank 不会同时被两个读端口访问:

```scala
assert(!(readValid(0) && readValid(1)),
  s"read conflict in tage_table_${tableIdx}_bank_${bankIdx}")
```

这保证了 predict 端口 (index 0) 和 train 端口 (index 1) 不会在同一 bank 上产生冲突，这是 bank 化设计正确性的基础。

---

## 4. Provider/Alt 选择与状态机

### 4.1 Provider 选择 (最长历史匹配)

Provider 是所有命中表中 **历史长度最长** 的那张表。这是 TAGE 算法的核心原则: 长历史表通常能捕获更复杂的相关性，因此赋予最高优先级。选择逻辑使用 `getLongestHistTableOH`:

```scala
// Helpers.scala
def getLongestHistTableOH(hitTableMask: Seq[Bool]): Seq[Bool] =
  PriorityEncoderOH(hitTableMask.reverse).reverse
```

该函数将 hitTableMask 反转后做 PriorityEncoder (选择最低位的 1)，再反转回来。由于表按历史长度递增排列 (table 0 最短, table 7 最长)，反转后优先编码就等价于选择历史最长的命中表。

```scala
val hitTableMask    = allTableTagMatchResults.map(_.hit)
val hasProvider     = hitTableMask.reduce(_ || _)                    // 是否有任何表命中
val providerTableOH = getLongestHistTableOH(hitTableMask)           // 最长历史命中表 (one-hot)
val provider        = Mux1H(providerTableOH, allTableTagMatchResults)
```

### 4.2 Alt 选择 (次长历史匹配)

Alt (alternate prediction) 是除 provider 之外、命中表中历史长度最长的那张表:

```scala
val hitTableMaskNoProvider = hitTableMask.zip(providerTableOH).map { case (a, b) => a && !b }
val hasAlt                 = hasProvider && hitTableMaskNoProvider.reduce(_ || _)
val altTableOH             = getLongestHistTableOH(hitTableMaskNoProvider)
val alt                    = Mux1H(altTableOH, allTableTagMatchResults)
```

逻辑: 先从 hit 表集合中去掉 provider 表 (通过 `a && !b`)，再对剩余 hit 表做最长历史选择。`hasAlt` 要求首先有 provider 存在，且除 provider 外还有其他表命中。

### 4.3 Provider 使用决策

```scala
val useProvider = hasProvider && !(useAltOnNa && provider.takenCtr.isWeak)
```

这个决策是 TAGE 的核心控制逻辑:
- 如果有 provider 且 (没有启用 UseAltOnNa，或 provider 的 takenCtr 不是 weak)，则使用 provider 预测
- 如果启用了 UseAltOnNa 且 provider 的 takenCtr 是 weak (刚刚跨越 taken/not-taken 边界)，则 **不使用** provider，而是回退到 alt 或 base 预测

预测优先级链:
1. **provider** (最长历史命中表): `provider.takenCtr.isPositive` (counter MSB 为 1 则预测 taken)
2. **alt** (次长历史命中表): `alt.takenCtr.isPositive`
3. **base prediction** (来自 MainBTB 的默认 taken 预测)

最终预测输出:
```scala
io.prediction(i).useProvider  := useProvider
io.prediction(i).providerPred := provider.takenCtr.isPositive
io.prediction(i).hasAlt       := hasAlt
io.prediction(i).altPred      := alt.takenCtr.isPositive
```

### 4.4 Meta 信息保存

Predict 阶段保存的 meta 信息 (`TageMetaEntry`) 供 train 阶段使用:

```scala
class TageMetaEntry extends TageBundle {
  val useProvider:       Bool            = Bool()
  val providerTableIdx:  UInt            = UInt(TableIdxWidth.W)
  val providerWayIdx:    UInt            = UInt(MaxNumWays.W)
  val providerTakenCtr:  SaturateCounter = TakenCounter()
  val providerUsefulCtr: SaturateCounter = UsefulCounter()
  val altOrBasePred:     Bool            = Bool()  // alt 的预测 (如果有 alt), 否则 base 预测
}
```

`altOrBasePred` 存储的是 alt 的预测方向，如果没有 alt 则存储 base prediction。这在 train 阶段计算 useful counter 更新时需要使用。

---

## 5. UseAltOnNa 机制实现

### 5.1 设计动机

UseAltOnNa (Use Alternate on Not-Agree) 解决的问题是: 当 provider 的 takenCtr 处于 weak 状态 (刚从 not-taken 翻转到 taken，或反之) 时，这种翻转可能是由训练噪声引起的。此时如果 alt (或 base) 的预测与实际执行结果一致，就说明 provider 的这次翻转不可靠，应该继续信任 alt。

### 5.2 硬件实现

XiangShan 维护一个 **全局 UseAltOnNa 向量寄存器** (`useAltOnNaVec`)，共有 `NumUseAltOnNa = 128` 个 entry，每个 entry 是一个 7-bit `SaturateCounter`:

```scala
private val useAltOnNaVec = RegInit(VecInit.fill(NumUseAltOnNa)(UseAltOnNaCounter.Zero))
```

每个 conditional branch PC 的低位 bit 用于索引这个向量:

```scala
// Helpers.scala
def getUseAltOnNaIdx(pc: PrunedAddr): UInt = {
  val useAltOnNaIdxWidth = log2Ceil(NumUseAltOnNa)  // log2Ceil(128) = 7
  pc(useAltOnNaIdxWidth - 1 + instOffsetBits, instOffsetBits)
}
```

索引使用 PC 的 `[6+instOffsetBits : instOffsetBits]` 这 7 位 bit，实现了基于 PC 地址的 aliasing hash。不同 PC 的分支倾向于使用不同的 counter，但由于 128 个 counter 要服务所有条件分支，多个 PC 会 alias 到同一个 counter 上。

### 5.3 UseAltOnNa 的判断逻辑

在 predict 阶段 (s2), 每个 branch 使用自己的 PC 索引 `useAltOnNaVec`:

```scala
val useAltOnNaIdx = getUseAltOnNaIdx(cfiPc)
val useAltOnNa    = useAltOnNaVec(useAltOnNaIdx).isPositive
```

当 `useAltOnNa` 为 true (counter > 0) 且 provider 的 takenCtr 为 weak 时，provider 的预测被放弃:

```scala
val useProvider = hasProvider && !(useAltOnNa && provider.takenCtr.isWeak)
```

等价逻辑: `useProvider` 为 true 仅当以下任一条件成立:
- 没有 provider (此时 `hasProvider = false`)
- 没有启用 UseAltOnNa (`useAltOnNa = false`, 即 counter <= 0)
- provider counter 不是 weak (已稳定在 taken 或 not-taken 方向)

### 5.4 UseAltOnNa Counter 的更新

在训练阶段 (t2), 为每个 branch 计算 increase/decrease 信号:

```scala
val incUseAltOnNa = hasProvider && provider.takenCtr.isWeak && altOrBasePred === actualTaken
val decUseAltOnNa = hasProvider && provider.takenCtr.isWeak && altOrBasePred =/= actualTaken
```

- **Increase (自增)**: provider counter 是 weak **且** alt/base 预测与实际结果一致 -> 说明在这种情况下 alt 更可靠，应该更多地使用 alt
- **Decrease (自减)**: provider counter 是 weak **且** alt/base 预测与实际结果不一致 -> 说明在这种情况下 provider 仍然比 alt 更好

在 t3 阶段实际更新 counter:

```scala
useAltOnNaVec.zipWithIndex.map { case (ctr, i) =>
  val idxMatchMask = t3_cfiUseAltOnNaIdxVec.map(_ === i.U)
  val increaseMask = idxMatchMask.zip(t3_trainInfoVec).map { case (idxMatch, updateInfo) =>
    idxMatch && updateInfo.valid && updateInfo.incUseAltOnNa
  }
  val decreaseMask = idxMatchMask.zip(t3_trainInfoVec).map { case (idxMatch, updateInfo) =>
    idxMatch && updateInfo.valid && updateInfo.decUseAltOnNa
  }
  val increase = increaseMask.reduce(_ || _)
  val decrease = decreaseMask.reduce(_ || _)

  when(t3_fire) {
    assert(PopCount(increaseMask) <= 1.U)     // 同一 counter 不可能同时被多个 branch increase
    assert(PopCount(decreaseMask) <= 1.U)
    assert(!(increase && decrease))            // 不可能同时 increase 和 decrease

    when(increase) { ctr.selfIncrease() }
    .elsewhen(decrease) { ctr.selfDecrease() }
  }
}
```

代码中的断言确保了更新的原子性: 每个时钟周期，每个 UseAltOnNa counter 最多被一个 branch 更新，且不会同时增减。

---

## 6. Useful Counter 管理与重置

### 6.1 Useful Counter 的作用

每个 TAGE entry 附带一个 `UsefulCtrWidth = 2` bits 的 useful counter，用于衡量该 entry 是否对预测精度有实质贡献。只有当 entry 的 useful counter 降为 0 (saturate negative) 时，该 entry 才可以被新分配的 entry 覆盖。这避免了淘汰仍有价值的 long-history entry。

### 6.2 Useful Counter 的初始值

```scala
object UsefulCounter extends SaturateCounterFactory {
  def Init(implicit p: Parameters): SaturateCounter =
    SaturateCounterInit(width, UsefulCtrInitValue)  // UsefulCtrInitValue = 0
}
```

新分配的 entry 的 useful counter 初始化为 0，这意味着新 entry 会被优先替换，除非它在后续训练中证明了自己的价值 (useful counter 增加)。

### 6.3 Useful Counter 的更新规则

Provider 的 useful counter 在训练时更新:

```scala
val incProviderUsefulCtr = hasProvider && providerPred === actualTaken && providerPred =/= altOrBasePred
val providerNewUsefulCtr = provider.usefulCtr.getIncrease(en = incProviderUsefulCtr)
```

**递增条件**: 满足全部三个条件:
1. `hasProvider`: 存在 provider
2. `providerPred === actualTaken`: provider 的预测与实际结果一致 (provider 预测正确)
3. `providerPred =/= altOrBasePred`: provider 的预测与 alt/base 不同 (provider 提供了额外信息)

**重要特性**: useful counter **只增不减** (在正常训练流程中)。这与传统 TAGE 论文中 useful counter 会递减的设计不同。XiangShan 的简化设计降低了硬件复杂度，通过周期性全局重置来管理 entry 的生命周期。

### 6.4 Useful Counter 的周期性全局重置

当所有表都没有可分配的 entry 时 (需要分配但无法分配)，`usefulResetCtr` 递增:

```scala
when(t3_usefulResetStart) {
  usefulResetCtr.resetZero()
}.elsewhen(t3_fire && t3_needAllocate && !t3_canAllocate && !usefulResetInFlight) {
  usefulResetCtr.selfIncrease()
}
```

`usefulResetCtr` 宽度为 8 bits，最大值为 255。当其饱和 (`isSaturatePositive`) 且当前没有正在进行的重置时，触发全局重置:

```scala
private val t3_usefulResetStart = t3_fire && usefulResetCtr.isSaturatePositive && !usefulResetInFlight
```

重置过程由每个 `TageTable` 执行，逐 bank 逐 set 地将所有 useful counter 写为零:

```scala
// TageTable.scala - 逐 bank 重置
when(usefulResetInFlightMask(bankIdx) && bank.head.io.w.req.fire) {
  when(usefulResetSetIdx(bankIdx) === (NumSets - 1).U) {
    usefulResetInFlightMask(bankIdx) := false.B   // 该 bank 重置完成
    usefulResetSetIdx(bankIdx)       := 0.U
  }.otherwise {
    usefulResetSetIdx(bankIdx) := usefulResetSetIdx(bankIdx) + 1.U
  }
}
```

重置时每个 bank 独立执行，每次 clock cycle 重置一个 set，4 个 bank 并行工作。重置完成后 `usefulResetInFlight` 信号返回 false。

### 6.5 Useful Reset 期间的读操作保护

当某个 bank 正在进行 useful reset 时，该 bank 的 useful SRAM 读请求被禁止，读结果返回全零:

```scala
// 读请求禁止
usefulBank.foreach { way =>
  way.io.r.req.valid := readValid.reduce(_ || _) && !usefulResetInFlightMask(bankIdx)
}

// 读响应保护
resp.usefulCtrs := Mux(
  readDuringUsefulResetNext,
  VecInit.fill(NumWays)(UsefulCounter.Zero),   // 重置期间读到零
  Mux1H(readBankMaskNext, usefulCtrSram.map(...))
)
```

这确保了 predict 和 train 路径在 useful reset 期间不会读到不一致的数据。

---

## 7. Misprediction 时的 Entry 分配

### 7.1 分配触发条件

```scala
val needAllocate = branch.bits.mispredict && (finalPred =/= actualTaken) &&
  !(hasProvider && providerTableOH(NumTables - 1)) &&  // 不在最高表
  !(hasProvider && !useProvider && providerPred === actualTaken && provider.takenCtr.isWeak)
```

需要分配的前提条件 (全部满足):
1. **分支确实 mispredicted**: `branch.bits.mispredict`
2. **TAGE 的最终预测是错的**: `finalPred =/= actualTaken`
3. **provider 不在最高表 (Table 7)**: 因为没有更长历史的表可以分配新 entry
4. **排除特殊情况**: 当 provider 没有被使用 (因为 UseAltOnNa) 但 provider 预测实际上是对的且 counter 是 weak 时，不需要分配

第 4 个条件的逻辑: 如果 provider 预测是对的，只是因为 UseAltOnNa 机制导致没使用它，说明 provider 本身是正确的，问题出在 UseAltOnNa 的决策上，不应该分配新 entry。

### 7.2 可分配的 Table 筛选

```scala
private val t3_longerHistoryTableMask = {
  val hasProvider     = t3_allocateBranchTrainInfo.hasProvider
  val providerTableOH = t3_allocateBranchTrainInfo.providerTableOH
  Mux(
    hasProvider,
    (~((providerTableOH - 1.U) | providerTableOH)).asUInt,  // 位运算技巧
    Fill(NumTables, true.B)   // 无 provider 时所有表都可分配
  )
}
```

位运算技巧详解 (以 providerTableOH = 8'b00000100 即 table 2 为例):
- `providerTableOH - 1` = `8'b00000011` (table 0 和 table 1)
- `(providerTableOH - 1) | providerTableOH` = `8'b00000111` (table 0, 1, 2)
- 取反: `~8'b00000111` = `8'b11111000` (table 3-7，即比 table 2 历史更长的表)

### 7.3 Entry 的替换策略 (三优先级)

可分配的 way 筛选有三个优先级:

```scala
private val t3_allTableCanAllocateWayMask = t3_readResp.map { tableReadResp =>
  val notValidMask  = tableReadResp.entries.map(!_.valid).asUInt            // 优先级 1: 空 entry
  val notUsefulMask = tableReadResp.usefulCtrs.map(_.isSaturateNegative).asUInt  // 优先级 3: useful=0
  val ctrWeakAndNotUsefulMask = tableReadResp.entries.zip(tableReadResp.usefulCtrs).map {
    case (entry, usefulCtr) =>
      entry.takenCtr.isWeak && usefulCtr.isSaturateNegative                 // 优先级 2: weak + useful=0
  }.asUInt
  MuxCase(
    notUsefulMask,     // 默认 (优先级 3): 选择 useful=0 的 entry
    Seq(
      notValidMask.orR            -> notValidMask,            // 优先级 1: 如果有空 entry，优先
      ctrWeakAndNotUsefulMask.orR -> ctrWeakAndNotUsefulMask  // 优先级 2: weak counter + useful=0
    )
  )
}
```

优先级从高到低:
1. **Not valid entry** (空 entry，从未被使用过)
2. **Weak counter + zero useful** (counter 在 weak 状态且 useful = 0)
3. **Zero useful** (useful = 0 的任何 entry)

注意: `MuxCase` 中靠后的条件优先级更高 (如果条件都为 true，执行最后一个匹配的)。所以实际上 `notValidMask` 是最高优先级 (靠后)，`notUsefulMask` 是最低优先级 (默认值)。

最终的可分配判断:
```scala
private val t3_canAllocateTableMask = t3_longerHistoryTableMask & t3_allTableCanAllocateWayMask.map(_.orR).asUInt
private val t3_canAllocate          = t3_canAllocateTableMask.orR
private val t3_allocate             = t3_needAllocate && t3_canAllocate
```

使用 `PriorityEncoderOH` 选择最佳表和 way:
```scala
private val t3_allocateTableOH = PriorityEncoderOH(t3_canAllocateTableMask)
private val t3_allocateWayMask = Mux1H(t3_allocateTableOH, t3_allTableCanAllocateWayMask)
private val t3_allocateWayOH   = PriorityEncoderOH(t3_allocateWayMask)
```

### 7.4 新 Entry 的初始化

```scala
private val t3_allocateEntry = {
  val rawTag      = Mux1H(t3_allocateTableOH, t3_rawTag)
  val position    = t3_allocateBranch.bits.cfiPosition
  val actualTaken = t3_allocateBranch.bits.taken
  val entry       = Wire(new TageEntry)
  entry.valid    := true.B
  entry.tag      := rawTag ^ position
  entry.takenCtr := Mux(
    actualTaken,
    TakenCounter.WeakPositive,   // taken 时: 4/8 (正向 weak)
    TakenCounter.WeakNegative    // not-taken 时: 3/8 (负向 weak)
  )
  entry
}
```

新 entry 的 takenCtr 初始化为 weak 状态 (4 或 3)，这比饱和值 (7 或 0) 更容易在后续训练中调整。useful counter 初始化为 0 (`UsefulCounter.Init`)。这种组合使得新 entry 既可以快速适应新的分支模式，又可以在被证明无效时被轻易替换。

### 7.5 分配约束断言

```scala
when(t3_fire) {
  assert(PopCount(t3_needAllocateBranchOH) <= 1.U)  // 每周期最多分配一个 branch
}
```

这是流水线设计的约束: 虽然一个 fetch block 中可能有多个 branch，但每个 train 周期最多只分配一个新 entry，这简化了分配逻辑和 SRAM 写入仲裁。

---

## 8. 训练流水线 (T0-T3) 与 Bank Conflict 处理

### 8.1 四阶段训练流水线概览

| 阶段 | 主要操作 | 关键数据 |
|------|---------|---------|
| T0 | 接收 train 输入, 判断是否需要读 SRAM, bank conflict 检测 | `t0_branches`, `t0_useMeta`, `t0_bankIdx` |
| T1 | SRAM 读响应, 计算 rawTag | `t1_readResp`, `t1_rawTag` |
| T2 | Tag 比对, provider/alt 选择, 训练决策 | `t2_trainInfoVec` |
| T3 | SRAM 写入, counter 更新, entry 分配, useful 重置 | `t3_allocate`, `t3_usefulResetStart` |

### 8.2 T0 阶段: 冲突检测与 Read Skip 优化

T0 阶段的关键决策是判断是否需要发起 SRAM 读请求:

```scala
private val t0_useMeta = t0_branches.zipWithIndex.map { case (branch, i) =>
  val mbtbHit      = t0_mbtbHitMask(i)
  val isCond       = t0_condMask(i)
  val useProvider  = t0_meta(i).useProvider
  val mispredicted = branch.bits.mispredict
  !(mbtbHit && isCond) || (useProvider && !mispredicted)
}.reduce(_ && _)
private val t0_needRead = !t0_useMeta
```

`t0_useMeta` 为 true 的条件: 所有条件分支要么不在 MBTB 结果中 (非条件分支)，要么使用了 provider 且没有 mispredict。此时 predict 阶段保存的 meta 信息就足够进行训练，无需重新读取 SRAM。

`t0_needRead` 为 true 时，需要读 SRAM 来获取最新的 counter 值进行更新计算。

Bank conflict 检测:
```scala
private val t0_readBankConflict = t0_hasCond && t0_needRead && s0_fire && t0_bankIdx === s0_bankIdx
io.trainReady := !t0_readBankConflict
```

当 train 路径需要读 SRAM 且 predict 路径同时访问同一 bank 时，train 必须 stall。`io.trainReady` 信号传递给上游，指示当前 train 端口是否可以接收新的 train 请求。

### 8.3 Bank Conflict 监控

代码包含详细的 bank conflict 性能监控:

```scala
// 基本的 bank conflict 计数
XSPerfAccumulate("read_conflict", debug_readBankConflict)

// 连续 bank conflict 的分布直方图 (bubble distance)
XSPerfHistogram("read_conflict_bubble_dist", debug_readBankConflictDistCnt, ...)

// 在 64B 对齐窗口内的短循环 bank conflict
// 用于分析紧密循环中 predict/train 冲突的模式
XSPerfHistogram("read_conflict_loop_dist", debug_readBankConflictShortLoopDistCnt, ...)
```

`read_conflict_loop_dist` 特别用于分析短循环 (如 while/for 循环体在 64B 以内) 中的 bank conflict，这种场景下 predict 路径和 train 路径更容易产生周期性的 bank 冲突。

### 8.4 T1 阶段: SRAM 读取与 Raw Tag 计算

```scala
private val t1_fire     = RegNext(t0_fire, init = false.B)
private val t1_readResp = VecInit(tables.map(_.io.readResp(1)))  // 获取 train port 的读响应
private val t1_rawTag   = VecInit((tables zip t1_foldedHist).map { case (table, hist) =>
  table.getRawTag(t1_startPc, hist.forTag)
})
```

T1 阶段同时完成两件事: 接收 SRAM 读结果，计算不含 position 的 raw tag。

### 8.5 T2 阶段: 训练决策生成

T2 阶段是训练流水线的核心，为每个 branch 生成完整的 `TrainInfo` 结构。包含以下决策:

1. **Meta 路径 vs SRAM 路径**: 当 `t2_useMeta` 为 true 时，使用 predict 阶段保存的 meta 信息重建 provider/alt 状态，跳过 SRAM 读结果的 tag 比对
2. **Provider/Alt 重选择**: 与 predict 阶段相同的逻辑，但在 train 上下文中执行
3. **TakenCtr 更新方向**: `providerNewTakenCtr = provider.takenCtr.getUpdate(actualTaken)`
4. **UsefulCtr 更新**: 递增条件 = provider 预测正确且不同于 alt/base
5. **分配判断**: `needAllocate` 信号
6. **UseAltOnNa 更新**: `incUseAltOnNa` / `decUseAltOnNa` 信号

### 8.6 T3 阶段: SRAM 写回

T3 阶段负责实际的 SRAM 写入操作。写入分为几个类别:

**1. Provider takenCtr 更新**:
```scala
val providerNeedUpdateCtr =
  info.valid && info.needUpdateProviderCtr && info.providerTableOH(tableIdx) && info.providerWayOH(wayIdx)
```

**2. Provider usefulCtr 更新**:
```scala
val providerNeedUpdateUseful =
  info.valid && info.needUpdateProviderUseful && info.providerTableOH(tableIdx) && info.providerWayOH(wayIdx)
```

**3. Alt takenCtr 更新**:
```scala
val altNeedUpdateCtr =
  info.valid && info.needUpdateAltCtr && info.altTableOH(tableIdx) && info.altWayOH(wayIdx)
```

**4. 新 Entry 分配**:
```scala
val allocateEn = t3_allocate && t3_allocateTableOH(tableIdx) && t3_allocateWayOH(wayIdx)
```

写入仲裁:
```scala
writeWayMask(wayIdx)    := updateEn || allocateEn              // 有更新或分配则写
writeEntryEn(wayIdx)    := providerWriteCtr || hitAlt || allocateEn  // entry 数据写使能
writeUsefulEn(wayIdx)   := providerWriteUseful || allocateEn   // useful counter 写使能
writeEntries(wayIdx)    := Mux(allocateEn, t3_allocateEntry, updateEntry)  // 数据选择
writeUsefulCtrs(wayIdx) := Mux(allocateEn, UsefulCounter.Init, updateUsefulCtr)
```

断言确保不会在同一 entry 上同时发生 provider 更新和 alt 更新:
```scala
when(t3_fire) {
  assert(PopCount(hitProviderMask) <= 1.U)     // 同一 way 最多一个 provider 更新
  assert(PopCount(altWriteCtr) <= 1.U)          // 同一 way 最多一个 alt 更新
  assert(!(hitProvider && hitAlt))               // provider 和 alt 不能同时更新同一 way
}
```

### 8.7 读写优先级总结

在 TageTable 中，读写优先级从高到低为:
1. **Predict 读** (s0): 最高优先级，不受任何操作阻塞
2. **Useful reset 写**: 在 useful reset 进行时，读取 useful SRAM 时返回零，读取 entry SRAM 不受影响
3. **Train 读** (t0): 与 predict 冲突时 stall
4. **Train 写** (t3): 从 WriteBuffer 到 SRAM，只在没有读请求时执行

---

## 9. 与 SC (Statistical Corrector) 的交互

### 9.1 数据流架构

XiangShan 的 BPU 采用 TAGE + SC 的经典组合架构。数据流方向:

```
TAGE (s2) --> providerTakenCtrVec --> SC (s2)
SC (s2)  --> scTakenMask, scUsed --> BPU Top (最终决策)
```

TAGE -> SC 的数据通过 `TageToScIO` 接口传递:

```scala
class TageToScIO extends TageBundle {
  val providerTakenCtrVec: Vec[Valid[SaturateCounter]] = Output(Vec(NumBtbResultEntries, Valid(TakenCounter())))
}
```

在 s2 阶段，TAGE 将每个 branch 的 provider takenCtr 发送给 SC:

```scala
io.toSc.providerTakenCtrVec(i).valid := hasProvider && branch.valid
io.toSc.providerTakenCtrVec(i).bits  := provider.takenCtr
```

### 9.2 SC 模块的结构

SC (`Sc.scala`) 包含多种 table 类型:
- **PathTable**: 基于路径历史 (path history) 的统计计数表
- **GlobalTable**: 基于全局历史 (GHR) 的统计计数表
- **BackwardTable** (bwTable): 基于后向历史的统计计数表
- **ImliTable**: 基于内部循环迭代计数 (IMLI) 的统计计数表
- **BiasTable**: 基于 PC 和 TAGE 预测偏向的 bias 表

每种 table 存储 signed 的统计计数器 (`ScEntry`)，通过加权累加产生一个 signed correction sum。

### 9.3 SC 如何使用 TAGE 的信息

**1. 获取 TAGE 预测方向**:
```scala
private val s2_providerTakenMask = VecInit(io.providerTakenCtrs.map(_.bits.isPositive))
private val s2_providerValid     = VecInit(io.providerTakenCtrs.map(_.valid))
private val s2_providerCtr       = VecInit(io.providerTakenCtrs.map(_.bits))
```

**2. 获取 TAGE 预测置信度** (用于动态调整 SC 覆盖阈值):
```scala
val tageConfHigh = s2_providerCtr(i).isSaturatePositive || s2_providerCtr(i).isSaturateNegative  // counter = 0 或 7
val tageConfMid  = s2_providerCtr(i).isMid     // counter = 1,2,5,6
val tageConfLow  = s2_providerCtr(i).isWeak    // counter = 3 或 4
```

**3. 动态阈值调整**: SC 使用 `scThreshold` 向量 (每个 way 一个 8-bit counter) 进行动态阈值管理。最终使用的阈值根据 TAGE counter 置信度进行右移:

```scala
private val s2_thresholds = VecInit(scThreshold.map(_.value >> 3))  // 基础阈值: threshold >> 3

val conf = MuxCase(
  false.B,
  Seq(
    (predValid && tageConfHigh) -> s2_sumAboveThresholdShift1(i),  // TAGE 高置信: threshold >> (3+1) = >>4
    (predValid && tageConfMid)  -> s2_sumAboveThresholdShift2(i),  // TAGE 中置信: threshold >> (3+2) = >>5
    (predValid && tageConfLow)  -> s2_sumAboveThresholdShift3(i)   // TAGE 低置信: threshold >> (3+3) = >>6
  )
)
```

设计逻辑: TAGE counter 越饱和 (置信度越高)，SC 需要更强的统计证据才能覆盖 TAGE 预测。当 TAGE 弱置信时，SC 的覆盖门槛降低，更积极地修正 TAGE。

**4. SC 预测的最终决策**:
```scala
s2_useScPred(i) := conf  // 只有 conf=true 时 SC 才覆盖 TAGE
io.scTakenMask := s2_scPred  // SC 的预测方向
io.scUsed      := s2_useScPred  // 是否使用 SC 覆盖
```

### 9.4 SC 的阈值自适应更新

SC 的阈值 (`scThreshold`) 也在训练阶段动态更新:

```scala
val needUpdate = valid && t1_meta.tagePredValid(oldIdx) &&
  (scWrong || !t1_meta.sumAboveThres(oldIdx))
thresholdWayMask(i)(writeIdx) := needUpdate
thresholdDirMask(i)(writeIdx) := scWrong
```

当 SC 预测错误 (`scWrong`) 时，增加对应 way 的阈值 (使 SC 更保守)。当 SC 统计和未超过阈值 (`!sumAboveThres`) 时，也调整阈值。

阈值被限制在 `[MinThreshold, MaxThreshold]` 范围内，防止过度调整。

### 9.5 SC 训练中的 TAGE 反馈

SC 训练时记录 TAGE 的原始预测和 counter 值，用于判断是否需要更新 SC table:

```scala
io.meta.scPred        := RegEnable(s2_scPred, s2_fire)
io.meta.tagePred      := RegEnable(s2_providerTakenMask, s2_fire)
io.meta.tageCtr       := RegEnable(VecInit(s2_providerCtr.map(_.value)), s2_fire)
io.meta.tagePredValid := RegEnable(s2_providerValid, s2_fire)
io.meta.useScPred     := RegEnable(s2_useScPred, s2_fire)
io.meta.sumAboveThres := RegEnable(s2_sumAboveThres, s2_fire)
```

SC 只更新那些 "需要纠正" 的统计计数器:
```scala
private val t0_writeValidVec =
  VecInit(t0_branches.zip(t0_branchesScIdxHitVec).zip(t0_branchesScIdxVec).zip(t0_writeTakenVec).map {
    case (((b, hit), predIdx), taken) =>
      b.valid && b.bits.attribute.isConditional && hit && t0_meta.tagePredValid(predIdx) &&
      (!(t0_meta.useScPred(predIdx) && t0_meta.scPred(predIdx) === taken) ||
       !(t0_meta.useScPred(predIdx) && t0_meta.tagePredValid(predIdx) &&
         t0_meta.scPred(predIdx) === t0_meta.tagePred(predIdx)))
  })
```

这个条件的含义: 更新 SC 当以下任一条件成立:
- SC 被使用但预测错了 (`useSc && scPred =/= taken`)
- SC 被使用但 SC 预测与 TAGE 预测相同 (此时 SC 没有提供额外价值)

### 9.6 SC 的 Bank Conflict 处理

与 TAGE 类似，SC 也使用 bank 化设计，并在 trainReady 中检测 bank conflict:

```scala
private val t0_bankConflict = t0_needWrite && s0_fire && t0_bankMask === s0_bankMask
io.trainReady := !t0_bankConflict
```

---

## 10. SaturateCounter 编码设计详解

XiangShan 的 SaturateCounter 采用 **无符号整数** 编码，但通过位域位置隐式表示正负:

```
3-bit counter 编码:
  值: 0   1   2   3   | 4   5   6   7
  含义: Negative (not-taken 偏向) | Positive (taken 偏向)
  状态: sat mid weak | weak mid sat
```

关键方法:
- `isPositive = value(width-1)`: MSB 为 1 则为 taken 偏向，只需 1 个 gate
- `isSaturatePositive = value.andR`: 所有位为 1
- `isSaturateNegative = !value.orR`: 所有位为 0
- `isWeakPositive = isPositive && !value(width-2,0).orR`: 恰好等于 `1 << (width-1)` = 4/8
- `isWeakNegative = isNegative && value(width-2,0).andR`: 恰好等于 `(1 << (width-1)) - 1` = 3/8
- `isWeak = isWeakPositive || isWeakNegative`: weak 状态用于 UseAltOnNa 判断
- `isMid = !isSaturate && !isWeak`: 中间状态 (1, 2, 5, 6)
- `shouldHold(increase)`: 当 counter 已经在目标方向饱和时返回 true，防止溢出

update 方法 `getUpdate(increase, en)`:
```scala
Mux(!en || shouldHold(increase), value, Mux(increase, value + 1.U, value - 1.U))
```

这种编码的优点: taken/not-taken 的判断只需要检查 MSB，是单个 gate 的操作，非常适合放在预测关键路径上。

---

## 11. 总结与关键设计特点

### 架构层面

1. **8 表几何历史**: 历史长度从 4 到 397，覆盖短程到长程分支模式，几何比约 1.95x
2. **2-way set-associative**: 每个 set 有 2 个 way 可供选择，减少 tag 冲突
3. **4-bank 单端口 SRAM**: 通过 bank 化解 predict/train 端口冲突

### Hash 与 Addressing

4. **Folded history + XOR hashing**: 高效压缩长历史为 9-bit index 和 13-bit tag，减少硬件开销
5. **Position-aware tag**: tag 计算中 XOR cfiPosition，使同一 fetch block 中不同分支有不同 tag
6. **PC-based banking**: Bank index 直接从 PC 提取，确保同一 PC 的 predict 和 train 访问同一 bank (正确性)，不同 PC 的访问分散到不同 bank (吞吐量)

### 预测决策

7. **Provider/Alt 优先级链**: 最长历史匹配表优先，次长历史匹配表次之，base prediction 兜底
8. **UseAltOnNa 机制**: 128 entry counter 向量，PC hash 索引，在 provider weak 时动态选择 alt，减少 weak provider 的误预测
9. **与 SC 协同**: TAGE 提供 providerTakenCtr 给 SC，SC 根据 TAGE 置信度动态调整覆盖阈值 (高置信度 -> 高门槛，低置信度 -> 低门槛)

### 训练与维护

10. **四级训练流水线**: T0 冲突检测 + read skip -> T1 SRAM 读取 + tag 计算 -> T2 训练决策 -> T3 写回
11. **useMeta 优化**: predict 决策正确时跳过 SRAM 读取，减少训练路径端口压力
12. **Useful counter 只增不减 + 周期性全局重置**: 简化硬件，通过 8-bit reset counter 控制重置时机
13. **Write Buffer 解耦读写**: SRAM 写入经过 WriteBuffer 延迟，在无读请求时执行

### 面积与功耗

14. **Folded SRAM**: useful counter 使用 FoldedSRAMTemplate (fold factor = 8)，大幅减少小宽度 SRAM 的面积
15. **Clock gating**: SRAM 实例支持 clock gating，在不访问时节省动态功耗
16. **SRAM reset**: 支持硬件 reset，上电后自动清零

这些设计共同构成了一个高吞吐量 (4-bank 并行)、低延迟 (3 级 predict pipeline)、面积高效 (folded SRAM) 的条件分支预测器，是 XiangShan 高性能前端的关键组件。
