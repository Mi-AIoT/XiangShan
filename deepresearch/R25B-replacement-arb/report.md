# R25B - Replacement & Arbitration 深度研究报告

## 概述

本报告深入分析 XiangShan RISC-V 处理器中 Replacement（替换策略）与 Arbitration（仲裁）机制的实现。这些模块是处理器微架构中的关键基础设施组件，直接决定了 Cache 的命中率、数据通路的吞吐量以及系统整体性能。XiangShan 的 Replacement 与 Arbiter 实现分布在三个主要位置：`utility/src/main/scala/utility/` 提供通用基础设施；`rocket-chip/src/main/scala/util/Replacement.scala` 提供来自 SiFive rocket-chip 的经典实现；`XSCache/src/main/scala/coupledL2/utils/Replacer.scala` 则提供了面向 L2 Cache 的增强版本，支持 DRRIP 等先进替换策略。

---

## 1. PLRU (Pseudo-LRU) 实现详解

### 1.1 算法原理

PLRU (Pseudo Least Recently Used) 是一种基于二叉树的近似 LRU 替换算法。与 True LRU 需要 O(n^2) 位存储不同，PLRU 仅需 n-1 位状态来管理 n 路 Cache，以极低的硬件开销实现了接近 LRU 的替换效果。其核心思想是在一棵二叉树的每个内部节点上存储 1 bit 方向信息，指示左右子树中哪一侧更"旧"（older），替换时沿树从根到叶递归选择最旧的 way。

### 1.2 二叉树存储结构

以 4-way Cache 为例，PLRU 需要 3 bits 状态存储：

```
              bit[2]: ways 3+2 older than ways 1+0
              /                                  \
 bit[1]: way 3 older than way 2    bit[0]: way 1 older than way 0
```

对于 8-way Cache，需要 7 bits：

```
              bit[6]: ways 7-4 older than ways 3-0
              /                                  \
    bit[5]: 7+6 > 5+4                    bit[2]: 3+2 > 1+0
    /                    \                /                    \
bit[4]: 7>6      bit[3]: 5>4    bit[1]: 3>2      bit[0]: 1>0
```

每个 bit 为 1 时表示左子树更旧（应被优先替换），为 0 时表示右子树更旧。这种编码方式使得状态位数仅为 n-1，远小于 True LRU 的 n(n-1)/2 位。

### 1.3 XiangShan 中的三处 PLRU 实现

XiangShan 中存在三处 PLRU 实现，分别服务于不同的子系统：

**实现一：rocket-chip PseudoLRU**

文件：`rocket-chip/src/main/scala/util/Replacement.scala`

这是基础版本，提供了核心的 `get_next_state` 和 `get_replace_way` 方法。`get_next_state` 采用递归下降算法：在分支节点处，根据 `touch_way` 的最高有效位判断访问的是左子树还是右子树，将当前节点 bit 设为指向另一侧（标记为 older），然后递归进入被访问的子树更新状态。在叶节点（tree_nways == 2），直接设置状态位为 `!touch_way(0)`。

`get_replace_way` 同样递归下降：在每个分支节点，读取当前 bit，若指示左子树更旧则递归左子树，否则递归右子树，最终到达叶节点返回替换 way 编码。

**实现二：XSCache coupledL2 Replacer 中的 PseudoLRU**

文件：`XSCache/src/main/scala/coupledL2/utils/Replacer.scala`

这是增强版本，与 rocket-chip 版本的算法逻辑完全相同，但增加了 `get_replace_OH` 方法，返回 one-hot 格式的替换 way 信息。该方法通过递归填充替换位向量：在每个分支节点，根据 `left_subtree_older` 位，用 `Fill` 操作将对应子树的所有位置为有效/无效，然后递归进入更旧的子树。这种 one-hot 输出在需要直接驱动 Mux 选择信号时更为方便。

**实现三：BPU 的 PlruStateGen**

文件：`src/main/scala/xiangshan/frontend/bpu/replacer/PlruStateGen.scala`

这是专门为 Branch Prediction Unit (BPU) 设计的 PLRU 实现，继承自 `ReplacerStateGen` 基类。其算法与上述两处完全一致，但采用了更现代的命名风格（`getNextState` / `getVictim`），并且支持 `AccessSize` 参数以处理单周期多次访问的场景。BPU 中的 ITTAGE RegionWays、MicroBTB、AheadBTB 等结构均使用此实现。

### 1.4 ValidPseudoLRU：带有效位感知的 PLRU

文件：`rocket-chip/src/main/scala/util/Replacement.scala`

`ValidPseudoLRU` 是 `PseudoLRU` 的子类，在选择替换 way 时额外考虑每个 way 的 valid 位。它要求 way 数为偶数（`n_ways % 2 == 0`），递归函数返回 `(Bool, UInt)` 元组，其中 Bool 表示结果是否有效。

在叶节点（tree_nways == 2），如果两个 way 都有效，则按 PLRU 规则选择；如果只有一个有效，则选择有效的那个；如果都无效则返回 invalid。在分支节点，递归左右子树后根据 valid 情况合并结果。这确保了在 Cache 未填满时不会替换有效条目。

XiangShan 中 `ValidPseudoLRU` 被广泛使用：
- `Sbuffer`（Store Buffer）使用 `ValidPseudoLRU(StoreBufferSize)` 管理存储缓冲区条目
- `L1PrefetchComponent` 中 L1 和 L2/L3 预取过滤器分别使用 `ValidPseudoLRU(MLP_L1_SIZE)` 和 `ValidPseudoLRU(MLP_L2L3_SIZE)`

### 1.5 SetAssocReplacer：基于 Set 的 PLRU 封装

文件：`XSCache/src/main/scala/coupledL2/utils/Replacer.scala`

`SetAssocReplacer` 类将 PLRU（或 TrueLRU、Random）逻辑封装为 set-associative 接口。它维护一个 `state_vec`，即每个 set 对应一个独立的替换状态向量。访问时通过 `set` 索引定位对应的状态位，更新该 set 的 PLRU 树。`access` 方法支持同时更新多个 set（通过 `Seq[Valid[UInt]]`），利用 `Pipe` 进行延迟对齐后分别更新各 set 的状态。

---

## 2. DRRIP (Dynamic Re-reference Interval Prediction) 实现

### 2.1 DRRIP 算法背景

DRRIP 由 Jaleel 等人在 ISCA 2010 提出，是一种基于 Re-Reference Interval Prediction (RRIP) 的高级替换策略。其核心思想是为每个 cache line 分配一个 2-bit 的 Re-Reference Prediction Value (RRPV)，值越大表示该 line 越不可能被再次访问。替换时选择 RRPV 最大的 way。DRRIP 是 SRRIP (Static RRIP) 和 BRRIP (Bimodal RRIP) 的混合体，通过 set dueling 机制自适应选择使用哪种策略。

### 2.2 StaticRRIP (SRRIP) 实现

文件：`XSCache/src/main/scala/coupledL2/utils/Replacer.scala`

SRRIP 每 way 使用 2-bit RRPV，总状态位数为 `2 * n_ways`。其状态更新逻辑通过 `req_type` 编码精细控制：

```
req_type[3]: 0-firstuse, 1-reuse
req_type[2]: 0-acquire, 1-release
req_type[1]: 0-non-prefetch, 1-prefetch
req_type[0]: 0-not-refill, 1-refill
```

更新规则：
- 非 prefetch 的 hit 或 refill：RRPV 设为 0（最近使用）
- prefetch 的 refill：RRPV 设为 1（中等优先级）
- 非 prefetch release first-use 或 prefetch release：RRPV 设为 2（较高优先级，易被替换）

对于未被 touch 的 way，在非 hit 且非 invalid 状态下，其 RRPV 会自增（aging 机制），使得长期未被访问的 line 逐渐变得更容易被替换。

替换选择（`get_replace_OH`）通过全比较找出 RRPV 最小的 way（即 `!(isLarger.contains(true.B))`），使用 `MaskToOH` 将结果转换为 one-hot 格式。

### 2.3 BRRIP (Bimodal RRIP) 实现

BRRIP 与 SRRIP 结构相同，但在初始插入时使用不同的 RRPV 值。BRRIP 的区别在于 `req_type(2,0) === 6.U` 时将 RRPV 设为 3（最大值），而非 SRRIP 的 2。这意味着 BRRIP 对某些类型的访问会给出更高的"不被重用"预测，使得 scan 效果更明显。代码中保留了被注释掉的随机化逻辑：以一定概率将插入的 RRPV 设为 2 或 3，这正是"bimodal"（双模态）命名的来源。

### 2.4 DRRIP 混合策略实现

```scala
class DRRIP(n_ways: Int) extends ReplacementPolicy {
  private val repl_SRRIP = ReplacementPolicy.fromString("srrip", n_ways)
  private val repl_BRRIP = ReplacementPolicy.fromString("brrip", n_ways)
  // ...
  override def get_next_state(state: UInt, touch_wayOH: UInt, hit: Bool,
    invalid: Bool, chosen_type: Bool, req_type: UInt): UInt = {
    Mux(chosen_type,
      repl_BRRIP.get_next_state(state, touch_wayOH, hit, invalid, req_type),
      repl_SRRIP.get_next_state(state, touch_wayOH, hit, invalid, req_type))
  }
}
```

DRRIP 内部实例化一个 SRRIP 和一个 BRRIP 策略实例。通过 `chosen_type` 信号（由 set dueling 机制产生）选择使用哪种策略。`chosen_type` 为 true 时使用 BRRIP，为 false 时使用 SRRIP。状态位仍然只有 `2 * n_ways` bits，因为 SRRIP 和 BRRIP 的状态格式完全兼容。

替换选择逻辑与 SRRIP/BRRIP 相同——扫描所有 way 的 RRPV，找到最小值对应的 way。

---

## 3. Replacement Policy 参数化机制

### 3.1 工厂模式：ReplacementPolicy.fromString

XiangShan 在两个层面提供了 Replacement Policy 的工厂方法：

**utility 层面**（`utility/src/main/scala/utility/Replacement.scala`）：

```scala
object ReplacementPolicy {
  def fromString(s: String, n_ways: Int): ReplacementPolicy = s.toLowerCase match {
    case "random" => new RandomReplacement(n_ways)
    case "lru"    => new TrueLRU(n_ways)
    case "plru"   => new PseudoLRU(n_ways)
  }
  def fromString(s: String, n_ways: Int, n_sets: Int): SetAssocReplacementPolicy = ...
}
```

支持全相联（fully associative）和组相联（set-associative）两种映射方式。对于组相联，额外提供 `"setlru"` 和 `"setplru"` 选项。

**XSCache L2 层面**（`XSCache/src/main/scala/coupledL2/utils/Replacer.scala`）：

```scala
object ReplacementPolicy {
  def fromString(s: String, n_ways: Int): ReplacementPolicy = s.toLowerCase match {
    case "random" => new RandomReplacement(n_ways)
    case "lru"    => new TrueLRU(n_ways)
    case "plru"   => new PseudoLRU(n_ways)
    case "srrip"  => new StaticRRIP(n_ways)
    case "brrip"  => new BRRIP(n_ways)
    case "drrip"  => new DRRIP(n_ways)
  }
}
```

L2 层面额外支持 `"srrip"`、`"brrip"`、`"drrip"` 三种 RRIP 变体，以适应 L2 Cache 对扫描友好型（scan-resistant）替换策略的需求。

### 3.2 ReplacementPolicy 抽象基类

抽象基类定义了替换策略的标准接口：

- `nBits`: 状态存储位数
- `perSet`: 是否每个 set 独立维护状态
- `way`: 当前选择的替换 way
- `miss` / `hit`: 缺失/命中时的回调
- `access(touch_way)`: 记录 way 访问
- `state_read`: 读取当前状态
- `get_next_state`: 计算下一个状态（纯组合逻辑）
- `get_replace_way` / `get_replace_OH`: 选择替换 way

L2 层面的抽象基类额外定义了带 `hit`、`invalid`、`req_type` 参数的 `get_next_state` 重载，这是 RRIP 系列策略所必需的。

### 3.3 各策略的存储开销对比

| 策略 | 每 way 存储位数 | 全局/Per-set | 总位数 (n_ways=4, n_sets=64) |
|------|----------------|--------------|---------------------------|
| Random | 16 bits (LFSR) | Global | 16 |
| TrueLRU | n(n-1)/2 = 6 | Per-set | 384 |
| PLRU | n-1 = 3 | Per-set | 192 |
| SRRIP/BRRIP/DRRIP | 2n = 8 | Per-set | 512 |

### 3.4 使用场景

- **DCache**: `ReplacementPolicy.fromString(cacheParams.replacer, nWays, nSets)` -- 通过参数配置
- **TLB**: `ReplacementPolicy.fromString(q.Replacer, q.NWays)` -- 全相联 PLRU/LRU
- **PageTableCache (L2TLB)**: L0-L3 各层和 SP 分别配置不同的替换策略和参数
- **BPU (MicroBTB, ITTAGE, AheadBTB)**: 使用 `PlruStateGen` 或 `LruStateGen`
- **Prefetcher**: SMS、Stride、L1Stream、Berti 等预取器广泛使用 PLRU
- **OpenLLC Directory**: `ReplacementPolicy.fromString(replacement, ways)` -- 根据配置选择策略

---

## 4. Arbiter Patterns (仲裁器模式)

### 4.1 FastArbiter -- 快速轮询仲裁器

文件：`utility/src/main/scala/utility/FastArbiter.scala`

`FastArbiter` 是 XiangShan 中使用最广泛的仲裁器实现，提供了单周期的 Round-Robin 仲裁能力。与 Chisel 标准库的 `RRArbiter` 相比，FastArbiter 在关键路径优化和功能增强方面做了显著改进。

**核心设计要素：**

1. **pendingMask** -- 待仲裁请求掩码：记录上一周期未被选中的请求，确保在轮询中不会遗漏任何请求。使用 `RegEnable` 在每次 `fire` 时更新。

2. **rrGrantMask** -- 轮询授权掩码：基于上一周期的选择位置，生成下一个起始仲裁位置。通过 `chosenOH` 的前缀 OR 计算得到。

3. **选择逻辑**：首先尝试从 pending 请求中轮询选择（`rrSelOH`），如果轮询有效（`rrValid`）则使用轮询结果，否则回退到第一个有效请求（`firstOneOH`）。这种两级选择策略保证了公平性的同时不会丢失请求。

4. **One-Hot 输出**：`chosenOH` 以 one-hot 格式输出，方便下游 Mux 选择逻辑。同时提供 `chosen` 作为编码格式。

5. **Ready-Valid 握手**：完整支持 TileLink/AXI 风格的 ready-valid 握手协议。

### 4.2 LatchFastArbiter -- 带锁存的仲裁器

`LatchFastArbiter` 在 `FastArbiter` 基础上增加了输出锁存（latch）机制。当仲裁结果产生后，将其锁存在寄存器中，直到下游消费者完成事务（`fire`）后才释放。这在以下场景中特别有用：

- 下游接口不能接受每个周期的新请求
- 需要保证仲裁结果的稳定性以满足时序要求
- 输出端需要反压（backpressure）能力

锁存逻辑使用 `latch_result` 信号控制：当有有效请求且输出寄存器为空时，将仲裁结果锁存。`out_valid_reg` 维持输出有效状态，`chosen_reg` 记录当前授权的请求编号。

### 4.3 TwoLevelRRArbiter -- 两级轮询仲裁器

`TwoLevelRRArbiter` 是一种层次化仲裁器，用于管理大量请求源的仲裁。当请求源数量较多时，单级仲裁器的关键路径可能过长，两级仲裁器通过分治策略解决这个问题。

**实现策略：**

1. 将 n 个请求源分为两组：低半部分（mid = n/2）和高半部分（rest = n - mid）
2. 每组各使用一个 `FastArbiter` 进行内部仲裁
3. 使用 `selLow` 寄存器在两级仲裁结果之间轮询
4. 最终的 one-hot 选择信号通过 `Cat` 和 `Fill` 操作合并两组的结果

边界情况处理：
- n == 0：输出无效
- n == 1：直接连接唯一的输入源
- n >= 2：使用两级仲裁结构

两级仲裁器被广泛应用于 L2 Cache 的请求仲裁，如 `SinkC`、`SourceB`、`RequestBuffer`、`BestOffsetPrefetch` 等模块。

### 4.4 仲裁器选择策略

XiangShan 根据不同场景选择不同的仲裁器：

- **FastArbiter**: 用于请求源数量较少、对延迟敏感的场景。如 `MemUnit` 的 TX 请求/数据仲裁、`CHIXbar` 的多 bank 仲裁、`RefillUnit`/`ResponseUnit`/`SnoopUnit` 的任务选择、`RequestBuffer` 的 issue 仲裁等。
- **TwoLevelRRArbiter**: 用于请求源数量较多、需要层次化仲裁的场景。如 `CoupledL2` 的 TX 请求仲裁（slices.size + 1 个源）、L2 SinkC/SourceB 的任务仲裁、prefetch 请求仲裁等。
- **LatchFastArbiter**: 定义在代码中但使用较少，主要用于需要输出锁存的特殊场景。

### 4.5 FastArbiterIO 扩展

`FastArbiterIO` 继承自 Chisel 标准的 `ArbiterIO`，额外提供 `chosenOH` (One-Hot) 输出。这使得下游逻辑可以直接使用 one-hot 信号进行 Mux 选择，避免了 `OHToUInt` 再解码的额外延迟。

### 4.6 Arbiter 性能监控

`utility/src/main/scala/utility/PerfCounterUtils.scala` 中定义了 `ArbPerf` 工具，可以方便地为 `FastArbiterBase` 实例添加性能计数器，监控仲裁器的利用率、选择分布等指标。

---

## 5. Sort Module 实现 (HwSort)

### 5.1 设计目标

文件：`utility/src/main/scala/utility/Sort.scala`

`HwSort` 是一个纯组合逻辑的硬件排序器，专门用于对最多 4 个元素进行排序。其设计目标是为处理器中的乱序提交、内存请求排序等场景提供低延迟（20-40ps）的排序能力。

### 5.2 DataWithPtr 包装器

`HwSort` 的输入元素必须包装为 `DataWithPtr[A, B]` 类型，其中：
- `valid`: Bool -- 指示该元素是否有效
- `bits`: A -- 原始数据
- `ptr`: B -- 用于排序的指针（必须是 `CircularQueuePtr` 的子类型）

这种设计将排序逻辑与具体的数据类型解耦，通过 implicit ordering 或显式提供比较函数来灵活配置排序方向。

### 5.3 排序算法

**2 元素排序（约 20ps）：**

```scala
val swap = xVec(1).valid && (!xVec(0).valid || cmp(xVec(1).ptr, xVec(0).ptr))
```

比较逻辑：如果第二个元素有效且（第一个元素无效或第二个元素更小），则交换。这确保有效元素排在前面，无效元素排在后面。

**3 元素排序（约 40ps）：**

采用插入排序的硬件展开形式，通过三轮 2 元素排序完成：
1. 对 [0,1] 排序
2. 对 [1,2] 排序
3. 对 [0,1] 再排序

**4 元素排序（约 40ps）：**

采用优化的排序网络，通过三轮并行 2 元素比较-交换操作完成：
1. 并行比较 (0,1) 和 (2,3)
2. 并行比较 (1,2) 和 (0,3)
3. 并行比较 (0,1) 和 (2,3)

这种排序网络结构使得 4 元素排序仅需 3 轮比较，每轮可以完全并行执行，因此总延迟与 3 元素排序相当。

### 5.4 排序方向配置

默认按升序排列（最旧的在前），支持通过以下方式自定义：

```scala
// 隐式设置降序
implicit val customCmp: (RobPtr, RobPtr) => Bool = (x, y) => x > y
val sorted = HwSort(...)

// 显式提供比较函数
val sorted = HwSort(...)((a, b) => a > b)
```

### 5.5 应用场景

- **LoadQueueUncache**: `HwSort(VecInit(io.req.map { case x => DataWithPtr(x.valid, x.bits, x.bits.uop.robIdx) }))` -- 按 ROB Index 排序未完成的 load 请求，确保按程序顺序处理
- **L1PrefetchComponent**: 分别对 load 和 store 训练数据按 ROB Index 排序，确保预取请求的顺序正确性

---

## 6. PriorityMuxGen 和 PriorityMuxDefault

### 6.1 PriorityMuxDefault -- 递归优先级多路选择

文件：`utility/src/main/scala/utility/PriorityMuxDefault.scala`

`PriorityMuxDefault` 是一个简洁的递归实现，提供带默认值的优先级多路选择：

```scala
object PriorityMuxDefault {
  def apply[T <: Data](in: Seq[(Bool, T)], default: T): T = in.size match {
    case 1 => Mux(in.head._1, in.head._2, default)
    case _ => Mux(in.head._1, in.head._2, PriorityMuxDefault(in.tail, default))
  }
}
```

核心逻辑：从序列头部开始检查，第一个为 true 的 select 信号对应的数据将被输出；如果没有任何 select 为 true，则输出 default 值。与 Chisel 标准库的 `PriorityMux` 不同，它提供了一个默认值参数，避免了宽度过宽或未覆盖情况下的硬件问题。

**配套工具：**
- `PriorityEncoderDefault`: 基于 `PriorityMuxDefault` 实现带默认值的优先级编码器
- `PriorityMuxWithFlag`: 返回 `(T, Bool)` 元组，额外输出是否有任何 select 有效的标志
- `PriorityEncoderWithFlag`: 带标志的优先级编码器

**应用实例：**
- `IBuffer`: 选择第一个有效的 decode 输出
- `DecodeStage`: 选择第一个非法指令和复杂指令
- `RobDeqPtrWrapper`: 选择 commit dequeue 指针
- `Rob`: 计算 commit/walk size sum
- `CtrlBlock`: 计算 decode buffer accept 数量
- `Dispatch`: 选择 instruction queue select UOP

### 6.2 PriorityMuxGenerator -- 可累积的优先级多路选择

文件：`utility/src/main/scala/utility/PriorityMuxGen.scala`

`PriorityMuxGenerator` 解决了一个常见的设计痛点：当优先级多路选择的输入源分散在代码的不同位置时，需要一个集中式的仲裁器。它采用 builder 模式，允许在不同位置注册输入，最后统一生成 PriorityMux 模块。

**工作流程：**
1. 在不同模块位置调用 `register(sel, in, name)` 注册输入源
2. 所有注册完成后调用 `apply()` 生成 `PriorityMuxModule` 实例
3. `PriorityMuxModule` 是一个独立的 Chisel Module，拥有命名的 IO 端口

**端口命名机制：**
- 可通过 `name` 参数指定端口名称
- 自动生成的名称为 "in1"、"in2"、... 等递增编号

### 6.3 PhyPriorityMuxGenerator -- 物理感知优先级多路选择

`PhyPriorityMuxGenerator` 在 `PriorityMuxGenerator` 基础上增加了物理优先级（physical priority）的概念。每个输入源可以指定一个 `phyPrio` 参数，表示其在物理实现中的延迟优先级。

**关键特性：**

1. **逻辑优先级 vs 物理优先级**：逻辑优先级由代码中的注册顺序决定（先注册的优先级高），物理优先级由 `phyPrio` 参数决定（值大的优先级高）。

2. **选择信号重编码**：在 `apply()` 中，首先根据逻辑优先级（代码注册顺序）生成标准的 PriorityMux 选择信号（if-else 链式），然后按照物理优先级重新排列这些选择信号。

3. **典型应用**：将物理延迟最大的输入源赋予最高物理优先级，使其在仲裁器中处于最靠近输出的位置，从而减少关键路径延迟。

重编码逻辑的核心：
```scala
sorted_src = sorted_src.sortBy(_._4).reverse
```
按物理优先级降序排列后，延迟最大的源被放在多路选择器的最低层（最先被选中），从而在物理实现中离输出最近。

---

## 7. True LRU 实现分析

### 7.1 Triangular Matrix 编码

True LRU 使用三角矩阵（Triangular Matrix）跟踪每对 way 之间的最近使用关系。对于 n-way Cache，需要 n(n-1)/2 bits。以 4-way 为例（6 bits）：

```
[5] - way 3 more recent than way 2
[4] - way 3 more recent than way 1
[3] - way 2 more recent than way 1
[2] - way 3 more recent than way 0
[1] - way 2 more recent than way 0
[0] - way 1 more recent than way 0
```

### 7.2 extractMRUVec：状态解码

`extractMRUVec` 将压缩的三角矩阵状态解码为 per-way 的更近使用向量。对每个 way i，提取其在三角矩阵中对应的行，得到一个 n-bit 向量，其中 bit j（j > i）表示 way j 是否比 way i 更近被使用。

### 7.3 get_next_state：状态更新

访问 `touch_way` 时，将该 way 标记为比所有其他 way 更近被使用：将所有行中涉及该 way 的位置 1，并清空该 way 对应的行。然后将三角矩阵重新压缩为紧凑编码。

### 7.4 get_replace_way：替换选择

遍历所有 way，检查每个 way 是否比所有其他 way 更旧（即所有其他 way 都比它更近被使用）。如果是，则该 way 就是最久未使用的替换目标。使用 OHToUInt 将 one-hot 结果转换为编码格式。

---

## 8. RandomReplacement 实现

RandomReplacement 使用 LFSR (Linear Feedback Shift Register) 生成 16 位伪随机数，通过 `Random(n_ways, lfsr)` 取模选择替换 way。每次缺失时（`miss`），LFSR 递进一步。该策略简单但无法感知访问模式，仅作为基线策略或特殊情况下的备选。

---

## 9. 应用模式总结

### 9.1 Cache 层级的替换策略分布

| Cache 层级 | 模块 | 默认策略 | 配置方式 |
|-----------|------|---------|---------|
| L1 ICache | ICacheReplacer | PLRU/LRU (可配) | Replacer 参数 |
| L1 DCache | DCacheWrapper | PLRU/LRU (可配) | cacheParams.replacer |
| L2 Cache | coupledL2 Directory | PLRU | replacement 参数 |
| L2TLB L0-L3 | PageTableCache | 可配 | l2tlbParams.*Replacer |
| L2TLB SP | PageTableCache | 可配 | l2tlbParams.spReplacer |
| OpenLLC | Directory | 可配 | replacement 参数 |
| Sbuffer | Sbuffer | ValidPseudoLRU | 固定 |
| Prefetch Filter | L1PrefetchComponent | ValidPseudoLRU | 固定 |
| BPU MicroBTB | MicroBtbReplacer | PLRU | Replacer 参数 |
| BPU ITTAGE | RegionWays | PLRU | RegionReplacer 参数 |
| BPU AheadBTB | AheadBtbReplacer | SetPLRU | 固定 |

### 9.2 仲裁器使用模式分布

| 模块 | 仲裁器类型 | 输入源数 |
|------|-----------|---------|
| MemBlock | FastArbiter | 可变 |
| CoupledL2 TX | TwoLevelRRArbiter | slices.size + 1 |
| OpenLLC RefillUnit | FastArbiter | mshrs.refill |
| OpenLLC MemUnit | FastArbiter | mshrs.memory |
| OpenLLC CHIXbar | FastArbiter x 12 | numRNs 或 banks |
| L2 SinkC | TwoLevelRRArbiter | bufBlocks |
| L2 SourceB | TwoLevelRRArbiter | entries |
| L2 RequestBuffer | TwoLevelRRArbiter | entries |
| BOP Prefetch | TwoLevelRRArbiter | REQ_FILTER_SIZE |

---

## 10. 源文件位置汇总

### Replacement 相关
- `utility/src/main/scala/utility/Replacement.scala` -- 通用 replacement policy（Random, TrueLRU, PLRU, SetAssocRandom）
- `rocket-chip/src/main/scala/util/Replacement.scala` -- rocket-chip replacement policy（Random, TrueLRU, PLRU, ValidPseudoLRU, SeqPLRU, SetAssocLRU）
- `XSCache/src/main/scala/coupledL2/utils/Replacer.scala` -- L2 增强版 replacement policy（Random, TrueLRU, PLRU, StaticRRIP, BRRIP, DRRIP, SetAssocReplacer）
- `src/main/scala/xiangshan/frontend/bpu/replacer/PlruStateGen.scala` -- BPU PLRU 实现
- `src/main/scala/xiangshan/frontend/bpu/replacer/LruStateGen.scala` -- BPU True LRU 实现

### Arbiter 相关
- `utility/src/main/scala/utility/FastArbiter.scala` -- FastArbiter, LatchFastArbiter, TwoLevelRRArbiter

### PriorityMux 相关
- `utility/src/main/scala/utility/PriorityMuxGen.scala` -- PriorityMuxGenerator, PhyPriorityMuxGenerator
- `utility/src/main/scala/utility/PriorityMuxDefault.scala` -- PriorityMuxDefault, PriorityEncoderDefault, PriorityMuxWithFlag

### Sort 相关
- `utility/src/main/scala/utility/Sort.scala` -- DataWithPtr, HwSort

### 工具函数
- `utility/src/main/scala/utility/BitUtils.scala` -- MaskToOH（被 RRIP 和 FastArbiter 广泛使用）

---

## 11. 设计亮点与工程实践

### 11.1 分层抽象与策略模式

XiangShan 的 replacement 系统采用了经典的策略模式（Strategy Pattern）。抽象基类定义统一接口，具体策略实现各自的状态更新和替换选择逻辑。工厂方法 `fromString` 将字符串参数映射到具体策略类，使得 Cache 配置可以在不修改模块代码的情况下切换替换策略。

### 11.2 组合逻辑的纯函数设计

所有 replacement policy 的核心算法（`get_next_state`、`get_replace_way`/`get_replace_OH`）均实现为纯组合逻辑函数，不依赖任何内部状态。这使得：
- 状态寄存器可以独立于算法逻辑进行管理
- 同一算法可以用于计算 next_state 和读取当前状态
- 测试和验证更加简单（如 `PLRUTest` 直接穷举所有状态转换）

### 11.3 FastArbiter 的关键路径优化

FastArbiter 通过以下方式优化关键路径：
- 使用 pending mask 避免每周期重新扫描所有请求
- 使用 one-hot 编码减少解码逻辑
- 提供 `chosenOH` 输出避免额外的 `OHToUInt` 转换
- 两级仲裁器将大扇入逻辑拆分为层次化结构

### 11.4 PhyPriorityMuxGenerator 的物理设计感知

`PhyPriorityMuxGenerator` 将物理设计约束（timing closure）融入 RTL 生成流程，允许逻辑设计者声明性地指定延迟约束，由生成器自动调整多路选择器的物理结构。这种 "logic-first, physical-aware" 的设计方法在高性能处理器设计中非常有价值。

### 11.5 HwSort 的硬件排序优化

`HwSort` 针对 2-4 元素的小规模排序进行了专门优化：
- 2 元素排序仅需单个比较-交换操作（约 20ps）
- 3-4 元素使用展开的排序网络，避免循环和状态机开销（约 40ps）
- 将有效元素优先排列，减少下游逻辑对无效数据的处理开销
- 通过 implicit ordering 支持灵活的排序方向配置
