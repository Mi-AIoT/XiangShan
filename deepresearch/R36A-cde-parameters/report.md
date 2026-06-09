# R36A - CDE 与 Parameters 深度剖析 (Deep Dive)

## 1. 概述 (Overview)

CDE（Context-Dependent Environments，上下文相关环境）是 RISC-V 生态系统中由 CHIPS Alliance 维护的核心参数化框架，最初由 SiFive 开发并集成在 Rocket Chip 中。CDE 为硬件设计提供了一种**类型安全、可组合、基于链式查找**的参数管理系统，使得同一套 RTL 代码可以根据不同的配置组合生成不同微架构参数的处理器。

在香山（XiangShan）项目中，CDE 是整个参数体系的基石。所有的微架构决策——从 ALU 数量到 Cache 大小、从 ROB 深度到 TLB 配置——都通过 CDE 的 `Field`、`Parameters`、`Config` 三件套进行管理。理解 CDE 的工作原理，是理解香山如何实现灵活配置和变体生成的关键。

---

## 2. CDE 库源码分析

CDE 库仅包含一个核心源文件，位于 `chipsalliance/cde` 仓库的 `cde/src/chipsalliance/rocketchip/config.scala`，包名为 `org.chipsalliance.cde.config`。在 XiangShan 构建系统中，CDE 作为 rocket-chip 的子模块依赖引入（`rocket-chip/cde/`），在 `rocket-chip/build.sc` 中通过 `import $file.cde.common` 引用。

以下逐层剖析 CDE 的核心类。

### 2.1 Field 类 — 类型安全的参数键 (Type-Safe Parameter Keys)

```scala
abstract class Field[T] private (val default: Option[T]) {
  def this() = this(None)
  def this(default: T) = this(Some(default))
}
```

`Field[T]` 是所有参数键的超类，其核心设计要点：

- **泛型参数 T**：每个 `Field` 都携带一个泛型类型参数 `T`，表示该参数值的类型。例如 `Field[Int]` 表示整数参数，`Field[XSCoreParameters]` 表示核心参数对象。这使得参数查询在编译期即具备类型安全性。
- **可选默认值**：`Field` 的构造函数接受一个 `Option[T]`。无参构造 `this()` 创建无默认值的 Field（查询未定义的 key 会抛出异常），带参构造 `this(default)` 则提供默认回退值。
- **case object 继承模式**：在实际使用中，`Field` 通过 Scala 的 `case object` 进行继承，每个参数键是一个全局唯一的单例对象。例如：

```scala
case object XSTileKey extends Field[Seq[XSCoreParameters]]
case object XSCoreParamsKey extends Field[XSCoreParameters]
case object DebugOptionsKey extends Field[DebugOptions]
case object SoCParamsKey extends Field[SoCParameters]
case object CVMParamsKey extends Field[CVMParameters]
case object DFTOptionsKey extends Field[DFTOptions]
case object PMParameKey extends Field[PMParameters]
```

这种设计确保了：（1）每个参数键是全局唯一的 singleton；（2）类型在编译期完全确定；（3）可以利用 `equals`/`hashCode` 作为 Map 的 key。

### 2.2 View 类 — 参数查询接口

```scala
abstract class View {
  final def apply[T](pname: Field[T]): T = {
    val out = find(pname)
    require(out.isDefined, s"Key ${pname} is not defined in Parameters")
    out.get
  }

  final def lift[T](pname: Field[T]): Option[T] = find(pname)

  protected[config] def find[T](pname: Field[T]): Option[T]
}
```

`View` 是参数查询的基础抽象：

- **`apply[T](pname: Field[T]): T`**：核心查询方法。给定一个 `Field[T]` 键，返回对应的 `T` 类型值。如果键未找到，抛出运行时异常。这是使用最广泛的 API——即 `p(XSTileKey)` 的底层实现。
- **`lift[T](pname: Field[T]): Option[T]`**：安全查询方法，返回 `Option[T]` 而非直接抛出异常。用于可选参数场景。
- **`find[T]`**：抽象方法，由子类实现具体的查找逻辑。这是整个 CDE 链式查找机制的入口点。
- **已废弃的 `apply(pname, ignore)` 签名**：旧版 API 需要额外传入 `site` 参数（`up(XYZ, site)`），CDE 0.1 后已不再需要。

### 2.3 Parameters 类 — 链式参数视图

```scala
abstract class Parameters extends View {
  final def alter(rhs: Parameters): Parameters = new ChainParameters(rhs, this)
  final def alter(f: (View, View, View) => PartialFunction[Any, Any]): Parameters = alter(Parameters(f))
  final def alterPartial(f: PartialFunction[Any, Any]): Parameters = alter(Parameters((_, _, _) => f))
  final def alterMap(m: Map[Any, Any]): Parameters = alter(new MapParameters(m))
  final def orElse(x: Parameters): Parameters = x.alter(this)
  final def ++(x: Parameters): Parameters = orElse(x)

  protected[config] def chain[T](site: View, here: View, up: View, pname: Field[T]): Option[T]
  protected[config] def find[T](pname: Field[T]): Option[T] = chain(this, this, new TerminalView, pname)
}
```

`Parameters` 是 CDE 的核心类，实现了**链式视图（Chain of Views）**模式：

- **链式查找机制**：每个 `Parameters` 对象内部维护一条查找链。当查询某个 `Field` 时，先在当前层查找，如果未找到则沿着 `up`（父级）继续查找，直到到达 `TerminalView`（链尾），此时使用 `Field` 的默认值或抛出异常。
- **`chain[T](site, here, up, pname)`**：抽象方法，每个 Parameters 子类必须实现。`site` 是全局视图，`here` 是当前视图，`up` 是父级视图。`pname` 是要查询的键。
- **`find[T]`**：模板方法，将 `site` 设为 `this`，`up` 设为 `TerminalView`，然后调用 `chain`。

#### site、here、up 三个视图的含义

这是 CDE 最精妙的设计。在 `alter` 创建的链式 Parameters 中：

| 参数 | 含义 | 作用域 |
|------|------|--------|
| `site` | 全局视图（始终指向最外层 Parameters） | 可以在任意层级访问全局参数 |
| `here` | 当前视图（指向当前正在被查询的 Parameters 层） | 访问当前层的参数 |
| `up` | 父级视图（指向链中的下一层） | 访问被覆盖前的原始值 |

这种三视图设计使得参数函数 `(site, here, up) => PF` 能够：

- `site(XSTileKey)` —— 读取全局的核心参数
- `here(XSTileKey)` —— 读取当前层的值（如果当前层定义了该键）
- `up(XSTileKey)` —— 读取上一层（即被 alter 覆盖前）的值

#### 组合操作

| 操作 | 语义 | 创建的新类型 |
|------|------|------------|
| `a.alter(b)` | b 覆盖 a：先查 b，再查 a | `ChainParameters(b, a)` |
| `a.alterPartial(pf)` | 用偏函数覆盖 a | `PartialParameters` |
| `a.alterMap(m)` | 用 Map 覆盖 a | `MapParameters` |
| `a ++ b` / `a.orElse(b)` | b 覆盖 a（等同于 `b.alter(a)`） | `ChainParameters(b, a)` |

**关键注意**：`++` 操作符的语义是**右侧优先**——`a ++ b` 表示 b 的设置覆盖 a 的设置。实际上 `++` 被标记为 deprecated，推荐使用 `orElse`。

### 2.4 Config 类 — 用户友好的参数配置

```scala
class Config(p: Parameters) extends Parameters {
  def this(f: (View, View, View) => PartialFunction[Any, Any]) = this(Parameters(f))
  protected[config] def chain[T](site: View, here: View, up: View, pname: Field[T]) =
    p.chain(site, here, up, pname)
  override def toString = this.getClass.getSimpleName
  def toInstance = this
}
```

`Config` 是 `Parameters` 的具体子类，是用户创建配置的标准入口：

- **构造方式**：可以直接包装一个 `Parameters`，也可以直接传入 `(site, here, up) => PF` 函数。
- **toString 重写**：Config 的 `toString` 返回类名，便于调试时识别配置来源。
- **toInstance**：返回自身（在旧版 Rocket Chip 中用于将 Config 转换为 Parameters 实例）。

在 XiangShan 中，所有顶层配置都继承自 `Config`：

```scala
class BaseConfig(n: Int) extends Config((site, here, up) => {
  case XLen => 64
  case XSTileKey => Seq.tabulate(n){ i => XSCoreParameters(HartId = i) }
  case SoCParamsKey => SoCParameters()
  case DebugOptionsKey => DebugOptions()
  // ...
})
```

### 2.5 内部实现类

CDE 库还包含以下内部实现类：

- **`TerminalView`**：链尾视图，`find` 方法直接返回 `pname.default`（Field 的默认值）。
- **`ChainView`**：包装了一个 head Parameters 和 site/up 视图，在 `find` 中委托给 `head.chain`。
- **`ChainParameters(x, y)`**：链式 Parameters，查询时先查 x（覆盖层），y 作为 x 的 up 层。这是 `alter` 操作的返回类型。
- **`EmptyParameters`**：空 Parameters，`chain` 直接委托给 `up.find(pname)`。
- **`PartialParameters(f)`**：从 `(site, here, up) => PF` 函数创建的 Parameters，`chain` 中先检查 PF 是否定义了该键，若未定义则委托给 up。
- **`MapParameters(map)`**：从 `Map[Any, Any]` 创建的 Parameters，`chain` 中先查 Map，未找到则委托给 up。

---

## 3. Config 组合模式 (Config Composition)

### 3.1 ++ 操作符 — 层叠配置

XiangShan 大量使用 `++` 操作符来层叠配置。例如 `DefaultConfig` 的定义：

```scala
class DefaultConfig(n: Int) extends Config(
  OpenLLCConfig("16MB", ways = 16, banks = 4)
    ++ L2CacheConfig("2MB", inclusive = true, banks = 4, tp = false)
    ++ WithNKBL1D(64, ways = 4)
    ++ new BaseConfig(n)
)
```

从右到左的执行顺序为：

1. `BaseConfig(n)` —— 基础配置，定义所有参数的默认值
2. `WithNKBL1D(64, ways = 4)` —— 覆盖 L1D Cache 配置
3. `L2CacheConfig(...)` —— 覆盖 L2 Cache 配置
4. `OpenLLCConfig(...)` —— 覆盖 L3/LLC 配置

在查找链中，查询顺序是**从左到右**（左侧优先）：先查 `OpenLLCConfig`，未找到再查 `L2CacheConfig`，再查 `WithNKBL1D`，最后到 `BaseConfig`。

### 3.2 alter 与 alterPartial — 精确覆盖

`alter` 操作用于在已有配置基础上精确修改某些参数：

```scala
class XSNoCTopConfig(n: Int = 1) extends Config(
  (new DefaultConfig(n)).alter((site, here, up) => {
    case SoCParamsKey => up(SoCParamsKey).copy(UseXSNoCTop = true)
  })
)
```

这里 `up(SoCParamsKey)` 获取了 DefaultConfig 中的 SoCParameters 值，然后用 `.copy()` 修改 `UseXSNoCTop` 字段。`site` 参数可以访问全局参数（如 `site(XSTileKey)` 可以读取当前的 tile 参数）。

`alterPartial` 是更简化的写法，忽略 site/here/up 参数：

```scala
val defaultConfig = config.alterPartial({
  case XSCoreParamsKey => config(XSTileKey).head
})
```

### 3.3 Config 继承树

XiangShan 定义了丰富的配置层次：

```
BaseConfig(n)                          -- 基础默认值
  |
  +-- DefaultConfig(n)                 -- 默认完整配置（L1 64KB + L2 2MB + L3 16MB）
  |     |
  |     +-- XSNoCTopConfig(n)          -- NoC 拓扑变体
  |     +-- XSNoCDiffTopConfig(n)      -- 差分测试拓扑
  |     +-- BackendV2Config(n)         -- 后端 V2 配置
  |     +-- FrontendDebugConfig(n)     -- 前端调试配置
  |     +-- FpgaDefaultConfig(n)       -- FPGA 综合配置
  |
  +-- MinimalConfig(n)                 -- 最小配置（L1 32KB + L2 128KB + L3 4MB）
  |     |
  |     +-- XSNoCTopMinimalConfig(n)
  |     +-- FpgaDiffMinimalConfig(n)
  |
  +-- CVMConfig(n)                     -- 内存加密配置
  +-- CVMTestConfig(n)                 -- 内存加密测试配置
```

---

## 4. XSCoreParameters 完整字段目录

`XSCoreParameters` 是香山处理器核心的所有微架构参数的聚合体。以下按功能域分类列出所有字段（共约 90+ 个直接构造参数，加上嵌套参数对象的数百个子字段）。

### 4.1 ISA 与基础架构参数

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `HasPrefetch` | Boolean | false | 是否启用预取 |
| `HartId` | Int | 0 | 硬件线程 ID |
| `XLEN` | Int | 64 | 整数寄存器宽度 |
| `VLEN` | Int | 128 | 向量寄存器宽度 |
| `ELEN` | Int | 64 | 向量元素最大长度 |
| `HSXLEN` | Int | 64 | Hypervisor 整数宽度 |
| `HasMExtension` | Boolean | true | M 乘除法扩展 |
| `HasCExtension` | Boolean | true | C 压缩指令扩展 |
| `HasHExtension` | Boolean | true | H Hypervisor 扩展 |
| `HasDiv` | Boolean | true | 是否有除法器 |

### 4.2 地址空间与 MMU 参数

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `AddrBits` | Int | 64 | 地址位宽 |
| `PAddrBitsMax` | Int | 56 | 物理地址最大位宽（Sv39/48/57） |
| `VAddrBitsSv39` | Int | 39 | Sv39 虚拟地址位宽 |
| `GPAddrBitsSv39x4` | Int | 41 | Sv39x4 Guest 物理地址位宽 |
| `VAddrBitsSv48` | Int | 48 | Sv48 虚拟地址位宽 |
| `GPAddrBitsSv48x4` | Int | 50 | Sv48x4 Guest 物理地址位宽 |
| `EnableSv48` | Boolean | true | 是否启用 Sv48 |
| `AsidLength` | Int | 16 | ASID 长度 |
| `VmidLength` | Int | 14 | VMID 长度 |
| `MMUAsidLen` | Int | 16 | MMU ASID 长度（最大 16） |
| `MMUVmidLen` | Int | 14 | MMU VMID 长度 |

### 4.3 功能单元与 FPU/VPU 参数

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `HasFPU` | Boolean | true | 是否有浮点单元 |
| `HasVPU` | Boolean | true | 是否有向量处理单元 |
| `HasCustomCSRCacheOp` | Boolean | true | 自定义 Cache 操作 CSR |
| `HasDCache` | Boolean | true | 是否有 DCache |
| `HasBitmapCheck` | Boolean | true | 位图检查（CVM 相关） |
| `HasBitmapCheckDefault` | Boolean | false | 位图检查默认值 |
| `HasCMO` | Boolean | true | Cache 管理操作 |

### 4.4 流水线宽度参数

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `DecodeWidth` | Int | 8 | 解码宽度 |
| `RenameWidth` | Int | 8 | 重命名宽度 |
| `CommitWidth` | Int | 8 | 提交宽度 |
| `RobCommitWidth` | Int | 8 | ROB 提交宽度 |
| `RabCommitWidth` | Int | 8 | RAB 提交宽度 |
| `MaxUopSize` | Int | 65 | 最大微操作大小 |

### 4.5 重命名与物理寄存器参数

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `EnableRenameSnapshot` | Boolean | true | 重命名快照 |
| `RenameSnapshotNum` | Int | 4 | 重命名快照数量 |
| `IntLogicRegs` | Int | 32 | 整数逻辑寄存器数 |
| `FpLogicRegs` | Int | 34 | 浮点逻辑寄存器数（32+I2F+stride） |
| `VecLogicRegs` | Int | 47 | 向量逻辑寄存器数（32+15 tmp） |
| `V0LogicRegs` | Int | 1 | V0 逻辑寄存器 |
| `VlLogicRegs` | Int | 1 | Vl 逻辑寄存器 |
| `V0_IDX` | Int | 0 | V0 索引 |
| `Vl_IDX` | Int | 0 | Vl 索引 |
| `NRPhyRegs` | Int | 192 | 物理寄存器总数 |
| `IntRegCacheSize` | Int | 24 | 整数寄存器缓存大小 |
| `MemRegCacheSize` | Int | 12 | 内存寄存器缓存大小 |
| `intPreg` | PregParams | IntPregParams(224, 4) | 整数物理寄存器配置 |
| `fpPreg` | PregParams | FpPregParams(256, 1) | 浮点物理寄存器配置 |
| `vfPreg` | PregParams | VfPregParams(128, 1) | 向量浮点物理寄存器配置 |
| `v0Preg` | PregParams | V0PregParams(22, 1) | V0 物理寄存器配置 |
| `vlPreg` | PregParams | VlPregParams(32, 1) | Vl 物理寄存器配置 |

### 4.6 ROB 与调度参数

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `RobSize` | Int | 352 | ROB 大小 |
| `RabSize` | Int | 352 | RAB 大小 |
| `VTypeBufferSize` | Int | 64 | VType 重排序缓冲区 |
| `IssueQueueSize` | Int | 20 | Issue Queue 大小 |
| `IssueQueueCompEntrySize` | Int | 12 | Issue Queue 压缩项大小 |
| `EnableBackendV2Config` | Boolean | false | 后端 V2 配置开关 |
| `EnableDispatchIQBalanceOpt` | Boolean | true | 分发 IQ 均衡优化 |

### 4.7 Load/Store 队列参数

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `VirtualLoadQueueSize` | Int | 72 | Load 队列大小 |
| `LoadQueueRARSize` | Int | 72 | Load 队列 RAR（Read-After-Read）大小 |
| `LoadQueueRAWSize` | Int | 32 | Load 队列 RAW（Read-After-Write）大小 |
| `RollbackGroupSize` | Int | 8 | 回滚组大小 |
| `LoadQueueReplaySize` | Int | 72 | Load 队列重放大小 |
| `LoadUncacheBufferSize` | Int | 16 | Load 未缓存缓冲区 |
| `LoadQueueNWriteBanks` | Int | 8 | Load 队列写入 bank 数 |
| `StoreQueueSize` | Int | 56 | Store 队列大小 |
| `SQUnalignQueueSize` | Int | 2 | Store 队列非对齐队列大小 |
| `StoreQueueNWriteBanks` | Int | 8 | Store 队列写入 bank 数 |
| `StoreQueueForwardWithMask` | Boolean | true | Store 队列带 mask 转发 |
| `VlsQueueSize` | Int | 8 | 向量 LS 队列大小 |

### 4.8 Store Buffer 与内存子系统参数

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `StoreBufferSize` | Int | 16 | Store Buffer 大小 |
| `StoreBufferThreshold` | Int | 9 | Store Buffer 阈值 |
| `EnsbufferWidth` | Int | 2 | 进入 SBuffer 宽度 |
| `LoadDependencyWidth` | Int | 2 | Load 依赖宽度 |
| `UncacheBufferSize` | Int | 16 | 未缓存缓冲区大小 |
| `CacheLineSize` | Int | 512 | Cache Line 大小（bit） |

### 4.9 内存流水线宽度参数

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `LoadPipelineWidth` | Int | 3 | Load 流水线宽度 |
| `StorePipelineWidth` | Int | 2 | Store 流水线宽度 |
| `VecLoadPipelineWidth` | Int | 2 | 向量 Load 流水线宽度 |
| `VecStorePipelineWidth` | Int | 2 | 向量 Store 流水线宽度 |
| `VecMemSrcInWidth` | Int | 2 | 向量内存源输入宽度 |
| `VecMemInstWbWidth` | Int | 1 | 向量内存指令写回宽度 |
| `VecMemDispatchWidth` | Int | 1 | 向量内存分发宽度 |
| `VecMemDispatchMaxNumber` | Int | 16 | 向量内存分发最大数量 |
| `VecMemUnitStrideMaxFlowNum` | Int | 2 | 向量 Unit-Stride 最大流数 |
| `VecMemLSQEnqIteratorNumberSeq` | Seq[Int] | Seq(16x6) | 向量 LSQ 入队迭代器数 |

### 4.10 VLSU（向量 Load/Store Unit）参数

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `VlMergeBufferSize` | Int | 16 | Vl 合并缓冲区大小 |
| `VsMergeBufferSize` | Int | 16 | Vs 合并缓冲区大小 |
| `UopWritebackWidth` | Int | 2 | 微操作写回宽度 |
| `VLUopWritebackWidth` | Int | 2 | VL 微操作写回宽度 |
| `VSUopWritebackWidth` | Int | 1 | VS 微操作写回宽度 |
| `VSegmentBufferSize` | Int | 8 | 向量段缓冲区大小 |

### 4.11 Cache 与 TLB 配置参数

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `dcacheParametersOpt` | Option[DCacheParameters] | Some(DCacheParameters(...)) | DCache 参数（含 ECC、替换策略等） |
| `L2CacheParamsOpt` | Option[L2Param] | Some(L2Param(...)) | L2 Cache 参数 |
| `L2NBanks` | Int | 1 | L2 Bank 数量 |
| `itlbParameters` | TLBParameters | TLBParameters(name="itlb", NWays=48) | ITLB 配置 |
| `ldtlbParameters` | TLBParameters | TLBParameters(name="ldtlb", NWays=48) | Load TLB 配置 |
| `sttlbParameters` | TLBParameters | TLBParameters(name="sttlb", NWays=48) | Store TLB 配置 |
| `hytlbParameters` | TLBParameters | TLBParameters(name="hytlb", NWays=48) | Hypervisor TLB 配置 |
| `pftlbParameters` | TLBParameters | TLBParameters(name="pftlb", NWays=48) | Prefetch TLB 配置 |
| `btlbParameters` | TLBParameters | TLBParameters(name="btlb", NWays=48) | Bridge TLB 配置 |
| `l2ToL1tlbParameters` | TLBParameters | TLBParameters(name="l2tlb", NWays=48) | L2-to-L1 TLB 配置 |
| `l2tlbParameters` | L2TLBParameters | L2TLBParameters() | L2 TLB 配置 |
| `itlbPortNum` | Int | 1 | ITLB 端口数 |
| `ipmpPortNum` | Int | 2 | IPMP 端口数 |
| `refillBothTlb` | Boolean | false | 是否同时 refill TLB |
| `iwpuParameters` | WPUParameters | WPUParameters(enWPU=false) | 指令 WPU 配置 |
| `dwpuParameters` | WPUParameters | WPUParameters(enWPU=false) | 数据 WPU 配置 |

### 4.12 预取器参数

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `prefetcher` | Seq[PrefetcherParams] | Seq(StreamStrideParams(), BertiParams(), SMSParams()) | 预取器参数序列 |

### 4.13 功能特性开关参数

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `EnableLoadFastWakeUp` | Boolean | true | Load 快速唤醒（当前未支持） |
| `EnableLoadToLoadForward` | Boolean | false | Load-to-Load 转发 |
| `EnableFastForward` | Boolean | true | 快速转发 |
| `EnableLdVioCheckAfterReset` | Boolean | true | 重启后 Load 冲突检查 |
| `EnableSoftPrefetchAfterReset` | Boolean | true | 重启后软预取 |
| `EnableCacheErrorAfterReset` | Boolean | true | 重启后 Cache 错误检查 |
| `EnableAccurateLoadError` | Boolean | false | 精确 Load 错误 |
| `EnableUncacheWriteOutstanding` | Boolean | false | 未缓存写 Outstanding |
| `EnableHardwareStoreMisalign` | Boolean | true | 硬件 Store 非对齐处理 |
| `EnableHardwareLoadMisalign` | Boolean | true | 硬件 Load 非对齐处理 |
| `EnableStorePrefetchAtIssue` | Boolean | false | 发射时 Store 预取 |
| `EnableStorePrefetchAtCommit` | Boolean | false | 提交时 Store 预取 |
| `EnableAtCommitMissTrigger` | Boolean | true | 提交时 Miss 触发 |
| `EnableStorePrefetchSMS` | Boolean | false | SMS Store 预取 |
| `EnableStorePrefetchSPB` | Boolean | false | SPB Store 预取 |
| `EnableClockGate` | Boolean | true | 时钟门控 |
| `EnbaleTlbDebug` | Boolean | false | TLB 调试（注意拼写错误） |
| `EnableCommitGHistDiff` | Boolean | true | 提交 GHist 差异检查 |
| `EnableJal` | Boolean | false | 启用 JAL |

### 4.14 调试与验证参数

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `usePTWRepeater` | Boolean | false | 使用 PTW Repeater |
| `softTLB` | Boolean | false | DPI-C L1TLB 调试 |
| `softPTW` | Boolean | false | DPI-C L2TLB 调试 |
| `softPTWDelay` | Int | 1 | Soft PTW 延迟 |
| `wfiResume` | Boolean | true | WFI 恢复 |
| `NumPerfCounters` | Int | 16 | 性能计数器数量 |
| `ReSelectLen` | Int | 7 | Load 重放选择计数器长度 |
| `IfuRedirectNum` | Int | 1 | IFU 重定向数量 |

### 4.15 前端参数（嵌套对象）

| 字段 | 类型 | 说明 |
|------|------|------|
| `frontendParameters` | FrontendParameters | 完整的前端参数对象，包含 FetchBlockSize、BPU、FTQ、ICache、IBuffer 等所有子模块参数 |

### 4.16 派生值与方法

`XSCoreParameters` 类体中还定义了大量派生方法和计算值：

- **`ISABase`**：返回 `"rv64i"` 基础 ISA 字符串
- **`ISAExtensions`**：返回完整的 ISA 扩展列表（约 80+ 个扩展）
- **`vlWidth`**：向量指令长度字段宽度 `log2Up(VLEN) + 1`
- **`minVecElen`**：向量元素最小长度 8 bit
- **`maxElemPerVreg`**：每个向量寄存器的最大元素数 `VLEN / minVecElen`
- **`RegCacheSize` / `RegCacheIdxWidth`**：寄存器缓存大小及索引宽度
- **`intSchdParams` / `fpSchdParams` / `vecSchdParams`**：三个调度器的完整参数定义（包含 IssueBlockParams、ExeUnitParams 等）
- **`iqWakeUpParams`**：Issue Queue 唤醒配置
- **`PregIdxWidthMax`**：物理寄存器索引最大宽度
- **`backendParams`**：后端参数对象
- **`traceParams`**：Trace 扩展参数

### 4.17 intSchdParams 调度器配置详情

intSchdParams 定义了 13 个 Issue Block，涵盖：

| Issue Block | 执行单元 | 功能 |
|-------------|---------|------|
| IB0 | ALU0, BJU0 | 整数运算+分支跳转 |
| IB1 | ALU1, BJU1 | 整数运算+分支跳转（含除法） |
| IB2 | ALU2, BJU2 | 整数运算+分支跳转（含 I2F、VSet） |
| IB3 | ALU3 | 整数运算（含 Bitmanip） |
| IB4 | ALU4 | 整数运算（含乘法） |
| IB5 | ALU5 | 整数运算（含乘法） |
| IB6 | LDU0 | Load 单元 0 |
| IB7 | LDU1 | Load 单元 1 |
| IB8 | LDU2 | Load 单元 2 |
| IB9 | STA0 | Store 地址计算 0 |
| IB10 | STA1 | Store 地址计算 1 |
| IB11 | STD0 | Store 数据 0 |
| IB12 | STD1 | Store 数据 1 |

---

## 5. 参数键层次结构 (Parameter Key Hierarchy)

### 5.1 核心参数键

| Key | 类型 | 定义位置 | 说明 |
|-----|------|---------|------|
| `XSTileKey` | `Field[Seq[XSCoreParameters]]` | `xiangshan/Parameters.scala` | 所有 Tile 的核心参数序列 |
| `XSCoreParamsKey` | `Field[XSCoreParameters]` | `xiangshan/Parameters.scala` | 单个核心参数 |
| `SoCParamsKey` | `Field[SoCParameters]` | `system/SoC.scala` | SoC 级参数 |
| `CVMParamsKey` | `Field[CVMParameters]` | `system/SoC.scala` | 内存加密参数 |
| `DebugOptionsKey` | `Field[DebugOptions]` | `xiangshan/Parameters.scala` | 调试选项 |
| `DFTOptionsKey` | `Field[DFTOptions]` | `xiangshan/Parameters.scala` | DFT 选项 |
| `PMParameKey` | `Field[PMParameters]` | `xiangshan/PMParameters.scala` | PMP/PMA 参数 |

### 5.2 辅助参数键

| Key | 类型 | 定义位置 | 说明 |
|-----|------|---------|------|
| `WPUParamsKey` | `Field[WPUParameters]` | `xiangshan/cache/wpu/WPU.scala` | WPU 参数 |
| `EnableJtag` | `Field[Bool]` | `device/RocketDebugWrapper.scala` | JTAG 启用 |
| `CLINTKey` | `Field[Option[TIMERParams]]` | `device/TIMER.scala` | CLINT 参数 |
| `CLINTAttachKey` | `Field[CLINTAttachParams]` | `device/TIMER.scala` | CLINT 附着参数 |
| `MemcEdgeInKey` | `Field[AXI4EdgeParameters]` | `device/MemEncrypt.scala` | 内存加密入口边参数 |
| `MemcEdgeOutKey` | `Field[AXI4EdgeParameters]` | `device/MemEncrypt.scala` | 内存加密出口边参数 |
| `SYSCNTKey` | `Field[Option[SYSCNTParams]]` | `device/SYSCNT.scala` | 系统计数器参数 |
| `SYSCNTAttachKey` | `Field[SYSCNTAttachParams]` | `device/SYSCNT.scala` | 系统计数器附着参数 |

### 5.3 SoCParameters 关键字段

```scala
case class SoCParameters(
  EnableILA: Boolean = false,
  PAddrBits: Int = 48,
  PmemRanges: Seq[MemoryRange] = Seq(MemoryRange(0x80000000L, 0x80000000000L)),
  PMAConfigs: Seq[PMAConfigEntry] = Seq(...),
  TIMERRange: AddressSet = ...,
  PLICRange: AddressSet = ...,
  L3NBanks: Int = 4,
  OpenLLCParamsOpt: Option[OpenLLCParam] = None,
  NumHart: Int = 64,
  NodeIDWidthList: Map[String, Int] = Map("B" -> 7, "C" -> 9, "E.b" -> 11),
  UseXSNoCTop: Boolean = false,
  IMSICParams: aia.IMSICParams = aia.IMSICParams(...),
  // ... 等约 30 个字段
)
```

### 5.4 CVMParameters 字段

```scala
case class CVMParameters(
  MEMENCRange: AddressSet = AddressSet(0x38030000L, 0xfff),
  KeyIDBits: Int = 0,
  MemencPipes: Int = 4,
  HasMEMencryption: Boolean = false,
  HasDelayNoencryption: Boolean = false,
)
```

### 5.5 DebugOptions 字段

```scala
case class DebugOptions(
  FPGAPlatform: Boolean = false,
  DumpCSR: Boolean = false,
  ResetGen: Boolean = false,
  EnableDifftest: Boolean = false,
  AlwaysBasicDiff: Boolean = true,
  EnableDebug: Boolean = false,
  EnablePerfDebug: Boolean = true,
  PerfLevel: String = "VERBOSE",
  EnableXMR: Boolean = true,
  SimMemSize: Long = 8190L * 1024 * 1024 * 1024,
  UseDRAMSim: Boolean = false,
  EnableConstantin: Boolean = false,
  EnableChiselDB: Boolean = false,
  AlwaysBasicDB: Boolean = true,
  EnableRollingDB: Boolean = false,
  EnableSimFrontend: Boolean = false,
)
```

### 5.6 DFTOptions 字段

```scala
case class DFTOptions(
  EnableMbist: Boolean = true,
  EnableSramCtl: Boolean = false,
)
```

### 5.7 PMParameters 字段

```scala
case class PMParameters(
  NumPMP: Int = 64,
  NumPMA: Int = 64,
  NumPMPReal: Int = 32,
  NumPMAReal: Int = 32,
  PlatformGrain: Int = log2Ceil(4*1024),
  mmpma: MMPMAConfig = MMPMAConfig(...)
)
```

---

## 6. 参数如何在设计中流动 (Parameter Flow)

### 6.1 implicit val p 模式

XiangShan 使用 Scala 的隐式参数机制来传递 `Parameters` 对象。核心模式是 trait 中声明 `implicit val p: Parameters`，然后通过 `p(SomeKey)` 获取参数：

```scala
trait HasXSParameter {
  implicit val p: Parameters

  def coreParams = p(XSCoreParamsKey)
  def env = p(DebugOptionsKey)
  def XLEN = coreParams.XLEN
  def RobSize = coreParams.RobSize
  // ... 几十个 def/val 代理到 coreParams 的字段
}
```

类似的 trait 还有：

- **`HasSoCParameter`**：访问 `p(SoCParamsKey)`、`p(CVMParamsKey)`、`p(XSTileKey)` 等 SoC 级参数
- **`HasPMParameters`**：访问 `p(PMParameKey)`、`p(SoCParamsKey).PMAConfigs` 等 PMP/PMA 参数

### 6.2 参数流动路径

参数的完整流动路径如下：

```
Config 定义（如 BaseConfig）
  |
  v
Top-level Parameters（site）
  |
  +-- XSTileKey --> Seq[XSCoreParameters]
  |     |
  |     +-- XSCoreParameters 包含所有核心参数
  |     +-- frontendParameters --> FrontendParameters（嵌套参数对象）
  |     +-- dcacheParametersOpt --> DCacheParameters
  |     +-- L2CacheParamsOpt --> L2Param
  |     +-- itlbParameters, ldtlbParameters, ... --> TLBParameters
  |     +-- intSchdParams --> SchdBlockParams（包含 ExeUnitParams 等）
  |     +-- backendParams --> BackendParams
  |     +-- traceParams --> TraceParams
  |
  +-- SoCParamsKey --> SoCParameters
  +-- DebugOptionsKey --> DebugOptions
  +-- DFTOptionsKey --> DFTOptions
  +-- PMParameKey --> PMParameters
  +-- CVMParamsKey --> CVMParameters
```

### 6.3 XSTileKey 到 XSCoreParamsKey 的关系

注意 `XSTileKey` 的值类型是 `Seq[XSCoreParameters]`，而 `XSCoreParamsKey` 的值类型是 `XSCoreParameters`。这种设计支持多核配置——`XSTileKey` 持有所有核心的参数列表，而 `XSCoreParamsKey` 通常在特定核心的上下文中使用。在 `BaseConfig` 中：

```scala
case XSTileKey => Seq.tabulate(n){ i => XSCoreParameters(HartId = i) }
```

每个核心通过索引 `i` 获得不同的 `HartId`。

### 6.4 HasXSParameter 代理层

`HasXSParameter` trait 提供了便捷的代理方法，将 `XSCoreParameters` 的嵌套字段扁平化为直接可访问的方法。例如：

- `p(XSTileKey).head.backendParams` 被简化为 `backendParams`
- `p(XSCoreParamsKey).RobSize` 被简化为 `RobSize`
- `p(SoCParamsKey).PAddrBits` 被简化为 `PAddrBits`

这种代理层使得下游 RTL 代码无需关心参数的层级结构，直接使用简短的名称即可。

### 6.5 Config 组合中的参数覆盖示例

以 `MinimalConfig` 为例，展示参数如何被逐层覆盖：

```scala
class MinimalConfig(n: Int = 1) extends Config(
  OpenLLCConfig("4MB", ways = 8, banks = 4)     // Step 4: 覆盖 L3
    ++ L2CacheConfig("128KB", ...)                // Step 3: 覆盖 L2
    ++ WithNKBL1D(32, ways = 4)                   // Step 2: 覆盖 L1D
    ++ new BaseConfig(n).alter((site, here, up) => { // Step 1: 基础 + 覆盖核心参数
      case XSTileKey => up(XSTileKey).map(
        p => p.copy(
          RobSize = 48,          // 从默认 352 降至 48
          StoreQueueSize = 20,   // 从默认 56 降至 20
          // ... 其他参数缩减
        )
      )
    })
)
```

在运行时，当代码执行 `p(XSTileKey)` 时：

1. 首先在 `OpenLLCConfig` 中查找 `XSTileKey`——未定义
2. 在 `L2CacheConfig` 中查找——定义了，但只修改 `L2CacheParamsOpt` 和 `L2NBanks`，通过 `up(XSTileKey)` 获取基值再 `.copy()` 修改
3. 实际上最终到达 BaseConfig 的 `.alter` 层，那里修改了 RobSize 等核心参数

---

## 7. 源文件位置汇总 (Source File Locations)

| 文件 | 路径 | 说明 |
|------|------|------|
| CDE 核心库 | `chipsalliance/cde` (GitHub) | `cde/src/chipsalliance/rocketchip/config.scala`（submodule 未初始化） |
| CDE 构建 | `rocket-chip/build.sc` | 定义 CDE 模块构建 |
| XSCoreParameters | `src/main/scala/xiangshan/Parameters.scala` | 核心参数定义（第 48-515 行） |
| HasXSParameter | `src/main/scala/xiangshan/Parameters.scala` | 参数代理 trait（第 547-840 行） |
| SoCParameters | `src/main/scala/system/SoC.scala` | SoC 参数定义（第 52-133 行） |
| CVMParameters | `src/main/scala/system/SoC.scala` | 内存加密参数（第 43-50 行） |
| HasSoCParameter | `src/main/scala/system/SoC.scala` | SoC 参数代理 trait（第 135-192 行） |
| DebugOptions | `src/main/scala/xiangshan/Parameters.scala` | 调试选项（第 517-537 行） |
| DFTOptions | `src/main/scala/xiangshan/Parameters.scala` | DFT 选项（第 539-545 行） |
| PMParameters | `src/main/scala/xiangshan/PMParameters.scala` | PMP/PMA 参数（第 27-42 行） |
| HasPMParameters | `src/main/scala/xiangshan/PMParameters.scala` | PMP 参数代理 trait |
| Configs 层次 | `src/main/scala/top/Configs.scala` | 所有 Config 定义（第 53-622 行） |
| Field 键定义 | 多个文件 | 见第 5 节参数键层次结构表 |

---

## 8. 总结

CDE 为 XiangShan 提供了一个优雅而强大的参数化框架。其核心设计模式可以概括为：

1. **类型安全的键**：通过 `Field[T]` + `case object` 模式，每个参数键具有唯一的身份和确定的类型。
2. **链式查找**：通过 `Parameters` 链和 `site/here/up` 三视图，实现了灵活的参数覆盖和继承。
3. **函数式组合**：`Config` 可以通过 `++`、`alter`、`alterPartial` 进行组合，形成从基础到变体的配置层次。
4. **Trait 代理层**：`HasXSParameter` 等 trait 将嵌套的参数结构扁平化，为 RTL 代码提供简洁的访问接口。
5. **多核支持**：`XSTileKey` 持有 `Seq[XSCoreParameters]`，天然支持多核配置。

整个参数体系从底层 CDE 库（约 160 行 Scala 代码）到 XSCoreParameters（约 90+ 直接字段 + 数百嵌套字段）到 Config 层次（15+ 配置类），构建了一个从抽象到具体的完整参数化设计流程，使得香山能够灵活支持从最小面积到最大性能的多种处理器变体。
