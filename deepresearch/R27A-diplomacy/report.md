# R27A - Diplomacy Framework 深度研究报告

## 一、概述

Diplomacy（外交框架）是 SiFive 开发 Rocket-Chip SoC Builder 中引入的核心抽象框架，用于构建**有向无环图（DAG, Directed Acyclic Graph）**，在 elaboration（细化）阶段完成节点间参数的协商（negotiation）和硬件接口（Bundle）的自动生成。XiangShan（香山）高性能 RISC-V 处理器深度依赖 Diplomacy 框架来组织其 TileLink 总线互联、中断分发、以及 L1/L2 缓存层次结构之间的连接。

本报告基于对 Diplomacy 源码的深入分析，系统性地介绍其核心机制，包括：LazyModule 两阶段 elaboration、Node 类型体系、Edge 参数协商协议、Binding 与图构建、InModuleBody 与 LazyModule Scope，以及 XiangShan 中的实际使用模式。

---

## 二、LazyModule 两阶段 Elaboration（Two-Phase Elaboration）

### 2.1 设计动机

传统 Chisel 模块在构造时即刻生成硬件电路。对于 SoC 级别的设计，模块间的连接关系需要在硬件生成之前进行全局分析和参数协商。Diplomacy 的核心洞察在于利用 Scala 的 **lazy evaluation**（惰性求值）机制，将 Chisel Module 的生成过程拆分为两个明确的阶段：

- **Phase 1（Diplomatic Phase / 声明阶段）**：声明模块层次结构（hierarchy）、实例化 LazyModule 和 BaseNode、建立 Binding 连接关系。此阶段仅建立 DAG 图结构，不生成任何硬件。
- **Phase 2（Lazy Phase / 生成阶段）**：触发 `module` 字段的求值，执行参数协商（parameter negotiation）、创建 Bundle、自动连接 IO 端口，并最终由 `LazyModuleImpLike` 生成 `chisel3.Module`。

### 2.2 LazyModule 的核心实现

在文件 `rocket-chip/src/main/scala/diplomacy/LazyModule.scala` 中，`LazyModule` 是一个抽象类，其关键成员包括：

```scala
abstract class LazyModule()(implicit val p: Parameters) {
  protected[diplomacy] var children: List[LazyModule] = List[LazyModule]()
  protected[diplomacy] var nodes: List[BaseNode] = List[BaseNode]()
  protected[diplomacy] var info: SourceInfo = UnlocatableSourceInfo
  protected[diplomacy] val parent: Option[LazyModule] = LazyModule.scope
  protected[diplomacy] var inModuleBody: List[() => Unit] = List[() => Unit]()
  
  def module: LazyModuleImpLike  // 子类必须定义，触发 Phase 2
}
```

**Scope 机制**是两阶段 elaboration 的关键。`LazyModule` 的伴生对象维护了一个全局可变变量 `scope`，充当栈的作用：

```scala
object LazyModule {
  protected[diplomacy] var scope: Option[LazyModule] = None
}
```

当用户调用 `LazyModule(new SomeModule)` 时，Scala 代码的执行顺序如下：
1. `new SomeModule()` 的构造函数被调用，此时 `scope` 指向父 LazyModule，因此 `parent` 被设置，当前实例被添加到父的 `children` 列表中。
2. `LazyModule.apply()` 方法验证 scope 正确后，将 `scope` 弹出（设为当前实例的 parent）。

这确保了嵌套的 LazyModule 实例化遵循正确的层次顺序。

### 2.3 LazyModuleImp 与硬件生成

`LazyModuleImpLike` 是一个 sealed trait，扩展自 `RawModule`，实际的硬件模块实现通过 `LazyModuleImp` 或 `LazyRawModuleImp` 类来提供。当 `lazy val module` 被求值时，`instantiate()` 方法被调用，该方法执行以下关键操作：

1. 递归实例化所有子 LazyModule 的 module。
2. 对每个 BaseNode 调用 `instantiate()` 方法。
3. 收集所有 `Dangle`（悬挂的端口描述），将同源的 Dangle 配对连接。
4. 将未配对的 Dangle 提升为 `AutoBundle` IO 端口。
5. 执行所有通过 `InModuleBody` 注入的代码片段。

### 2.4 SimpleLazyModule 与 LazyScope

`SimpleLazyModule` 是一个无需自定义 `LazyModuleImp` 的轻量级包装器，仅用于建立模块层次并连接子模块。`LazyScope` trait 允许在运行时动态创建模块层次，而无需定义显式的 LazyModule 子类。

---

## 三、Node 类型体系

### 3.1 基础类型层次

Diplomacy 的 Node 体系基于以下类型层次构建：

```
BaseNode
  +-- InwardNode[DI, UI, BI]    -- 定义节点的入边行为
  +-- OutwardNode[DO, UO, BO]   -- 定义节点的出边行为
  +-- MixedNode[DI, UI, EI, BI, DO, UO, EO, BO]  -- 完整的双向节点
```

`BaseNode` 是所有 Node 的基类，每个 BaseNode 在创建时自动注册到当前 `LazyModule.scope` 的 `nodes` 列表中，并分配全局唯一的 `serial` 编号。

### 3.2 四个核心类型参数（D/U/E/B）

Diplomacy 使用四个类型参数来描述协议接口：

| 参数 | 含义 | 方向 |
|------|------|------|
| **D** (Down) | 向下流动的参数 | 沿边方向（从 Source 到 Sink） |
| **U** (Up) | 向上流动的参数 | 逆边方向（从 Sink 到 Source） |
| **E** (Edge) | 协商后的边参数 | 双向（包含完整连接信息） |
| **B** (Bundle) | 硬件接口类型 | 实际的 chisel3.Data |

在一个 MixedNode 中，内侧（inward）和外侧（outward）可以使用不同的类型参数，因此有 DI/UI/EI/BI 和 DO/UO/EO/BO 八个类型参数。

### 3.3 NodeImp（节点实现）

`NodeImp` 是一个核心抽象类，将 InwardNodeImp 和 OutwardNodeImp 组合在一起：

```scala
abstract class NodeImp[D, U, EO, EI, B <: Data] extends Object
  with InwardNodeImp[D, U, EI, B]
  with OutwardNodeImp[D, U, EO, B]
```

它定义了：
- `edgeI(pd, pu, p, sourceInfo)` / `edgeO(pd, pu, p, sourceInfo)`：从向上/向下参数生成 Edge 参数。
- `bundleI(ei)` / `bundleO(eo)`：从 Edge 参数生成 Bundle 硬件类型。
- `mixI(pu, node)` / `mixO(pd, node)`：在混合节点中转换参数。
- `monitor(bundle, edge)`：为输入端口附加可选的监控逻辑。
- `render(e)`：定义边在 GraphML 可视化中的渲染方式。

`SimpleNodeImp` 是 NodeImp 的简化版本，当内侧和外侧使用相同协议时（即 EI == EO 且 BI == BO），可以使用 `edge()` 和 `bundle()` 统一定义。

### 3.4 具体 Node 类型

#### SourceNode（源节点）

SourceNode 只有出边，没有入边。它是 DAG 的起点（即参数向下游流动的源头）。

```scala
class SourceNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(po: Seq[D])
```

构造时传入 `po: Seq[D]`，即向外发送的向下参数序列。SourceNode 不允许出现在 `:=` 的左侧或 `:*=` 的左侧。其 `resolveStar` 实现确保不接收任何入边连接。

#### SinkNode（汇节点）

SinkNode 只有入边，没有出边。它是 DAG 的终点。

```scala
class SinkNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(pi: Seq[U])
```

构造时传入 `pi: Seq[U]`，即期望接收的向上参数序列。SinkNode 不允许出现在 `:=` 的右侧或 `:=*` 的右侧。

#### NexusNode（聚合节点）

NexusNode 的入边和出边数量在图构建完成前未知，典型的例子是 Crossbar（交叉开关）。NexusNode 可以将多个输入聚合为一个输出参数，或将一个输入分发给多个输出。

```scala
class NexusNode[D, U, EO, EI, B <: Data](imp: NodeImp[D, U, EO, EI, B])(
  dFn: Seq[D] => D,     // 将所有入边的 D 参数聚合为一个出边 D 参数
  uFn: Seq[U] => U,     // 将所有出边的 U 参数聚合为一个入边 U 参数
  inputRequiresOutput: Boolean = true,
  outputRequiresInput: Boolean = true
)
```

NexusNode 的 `resolveStar` 实现较为简单：如果两侧都有已知连接（`iKnown` 和 `oKnown` 都非零），则 `(1, 1)`；否则 `(0, 0)`。

#### MixedNode（混合节点）

MixedNode 是最通用的节点类型，其内侧和外侧可以使用完全不同的 `NodeImp`，从而实现协议转换（如 TL-to-AXI bridge）。

```scala
sealed abstract class MixedNode[DI, UI, EI, BI <: Data, DO, UO, EO, BO <: Data](
  val inner: InwardNodeImp[DI, UI, EI, BI],
  val outer: OutwardNodeImp[DO, UO, EO, BO])
```

MixedNode 实现了完整的参数协商流程，其核心方法包括：
- `resolveStar(iKnown, oKnown, iStars, oStars)`：解析星号连接的实际数量。
- `mapParamsD(n, p)`：向下参数映射。
- `mapParamsU(n, p)`：向上参数映射。

#### 其他 Node 类型

- **AdapterNode**：修改通过的参数但不改变边的数量或协议。使用 `dFn: D => D` 和 `uFn: U => U` 转换函数。
- **IdentityNode**：不修改任何参数，自动将输入连接到输出，标记为 `circuitIdentity = true`。
- **EphemeralNode**：临时连接占位符，在最终图中消失（`omitGraphML = true`），通过 `iForward`/`oForward` 机制将连接直接转发。
- **MixedAdapterNode**：可转换协议的适配器节点，要求入边和出边数量相同。
- **MixedNexusNode**：协议转换 + 数量不确定的聚合节点。
- **JunctionNode / MixedJunctionNode**：创建固定比例的并行仲裁器（arbiters），输入和输出之间存在固定倍数关系。

---

## 四、Edge 参数协商协议

### 4.1 协商流程概览

Diplomacy 的参数协商是一个**双向传播**过程：

- **向下传播（Downward）**：参数从 SourceNode 出发，沿 DAG 边方向向 SinkNode 流动。每个节点通过 `mapParamsD` 函数接收入边的 D 参数并生成出边的 D 参数。
- **向上传播（Upward）**：参数从 SinkNode 出发，逆 DAG 边方向向 SourceNode 流动。每个节点通过 `mapParamsU` 函数接收出边的 U 参数并生成入边的 U 参数。

最终，每个连接处的边参数通过 `edgeI(pd, pu, p, sourceInfo)` / `edgeO(pd, pu, p, sourceInfo)` 从协商后的 D 和 U 参数生成。

### 4.2 参数传播的懒加载链

在 MixedNode 的实现中，参数传播通过 Scala 的 lazy val 链自动触发：

```
oPorts (输出端口)
  -> iPorts (输入端口)
    -> diParams <- iPorts.map { case (i, n, _, _) => n.doParams(i) }
      -> doParams <- mapParamsD(oPorts.size, diParams)
        -> edgesOut <- (oPorts zip doParams).map { ... outer.edgeO(...) }
          -> bundleOut <- edgesOut.map { e => Wire(outer.bundleO(e)) }
```

反方向：
```
iPorts
  -> uoParams <- oPorts.map { case (o, n, _, _) => n.uiParams(o) }
    -> uiParams <- mapParamsU(iPorts.size, uoParams)
      -> edgesIn <- (iPorts zip uiParams).map { ... inner.edgeI(...) }
        -> bundleIn <- edgesIn.map { e => Wire(inner.bundleI(e)) }
```

这种懒加载链确保了参数传播按拓扑排序进行，且能自动检测循环依赖（通过 `starCycleGuard`、`oParamsCycleGuard`、`iParamsCycleGuard` 三个守卫变量）。

### 4.3 Star 解析机制

Diplomacy 中的 `:=*`、`:*=`、`:*=*` 运算符引入了"星号连接"，其连接数量在图构建时尚未确定。`resolveStar` 方法负责解析这些未知数量：

- `oStar`：出边侧的星号解析值（用于 `:=*`）。
- `iStar`：入边侧的星号解析值（用于 `:=*`）。

不同 Node 类型的 `resolveStar` 实现不同：
- SourceNode：不允许入边星号，出边星号通过 `po.size - oKnown` 解析。
- SinkNode：不允许出边星号，入边星号通过 `pi.size - iKnown` 解析。
- NexusNode：当且仅当两侧都有已知连接时返回 `(1, 1)`，否则 `(0, 0)`。
- AdapterNode：要求入边和出边的已知数量匹配，差值由星号连接补充。

### 4.4 Flex 方向解析

`:*=*` 运算符表示两侧都可能有星号，方向通过 `flexOffset` 计算确定。算法使用深度优先搜索（DFS）找到所有通过 `:*=*` 连接的节点集合，然后检查集合中是否有 `:=` 或 `:*=` 的固定方向连接来确定整体方向。

### 4.5 Bundle 生成

参数协商完成后，边参数被用来生成实际的硬件 Bundle：

```scala
protected[diplomacy] lazy val bundleOut: Seq[BO] = edgesOut.map { e =>
  val x = Wire(outer.bundleO(e)).suggestName(s"${valName.name}Out")
  x := DontCare
  x
}
```

Bundle 的生成是懒加载的，只有在 `LazyModuleImp` 求值时才会真正创建 Wire。

---

## 五、Binding 与图构建

### 5.1 NodeHandle 代数

Binding 操作通过 `NodeHandle` 体系实现，其设计保证了三个关键性质：

1. **可链式调用（Chainable）**：`a := b := c` 等价于先绑定 c 到 b，再绑定 b 到 a。
2. **结合性（Associative）**：`(a := b) := c` 等价于 `a := (b := c)`。
3. **类型安全**：只有允许出边的节点才能出现在右侧，只有允许入边的节点才能出现在左侧。

NodeHandle 代数使用箭头表示法：

```
"---" NoHandle           -- 两侧都不能继续绑定
"---<" InwardNodeHandle  -- 只有右侧可以继续绑定（作为 sink）
"<---" OutwardNodeHandle -- 只有左侧可以继续绑定（作为 source）
"<---<" NodeHandle       -- 两侧都可以继续绑定
```

### 5.2 Binding 运算符

| 运算符 | 左侧角色 | 右侧角色 | Binding 类型 |
|--------|---------|---------|-------------|
| `:=` | BIND_ONCE (sink) | BIND_ONCE (source) | 固定一对一 |
| `:*=` | BIND_STAR (sink) | BIND_QUERY (source) | 左侧决定数量 |
| `:=*` | BIND_QUERY (sink) | BIND_STAR (source) | 右侧决定数量 |
| `:*=*` | BIND_FLEX (sink) | BIND_FLEX (source) | 自动推断方向 |

### 5.3 Binding 的执行过程

当执行 `a := b` 时（假设 a 是 InwardNode，b 是 OutwardNode）：

1. `InwardNode.bind()` 被调用，内部执行：
   ```scala
   y.oPush(i, x, binding)  // 在 b（OutwardNode）的输出列表中记录连接
   x.iPush(o, y, binding)  // 在 a（InwardNode）的输入列表中记录连接
   ```
2. 连接信息（包括端口索引、对端节点引用、Binding 类型、Parameters 和 SourceInfo）被累积到 `accPI` 和 `accPO` 列表中。
3. 当 `iBindings` 或 `oBindings` 被首次访问时（lazy val），累积阶段结束，后续的绑定操作将被拒绝。

### 5.4 Dangle 与 AutoBundle

当 LazyModule 的 `instantiate()` 被调用时，每个 BaseNode 生成 `Dangle` 描述：

```scala
case class Dangle(
  source: HalfEdge,  // 源侧（BaseNode serial + port index）
  sink: HalfEdge,    // 汇侧
  flipped: Boolean,  // Bundle 方向翻转标记
  name: String,      // 端口名称
  dataOpt: Option[Data]  // 实际硬件连接数据
)
```

所有同源的 Dangle（source 相同且恰好两个）被自动配对连接。未配对的 Dangle 被提升为 `AutoBundle` 的 IO 端口，实现跨 LazyModule 边界的自动连接。

---

## 六、InModuleBody 与 LazyModule Scope

### 6.1 InModuleBody 机制

`InModuleBody` 允许在 LazyModule 的声明阶段（Phase 1）注册代码片段，这些代码在 Phase 2 的 `LazyModuleImp.instantiate()` 中被求值。

```scala
object InModuleBody {
  def apply[T](body: => T): ModuleValue[T] = {
    val scope = LazyModule.scope.get
    val out = new ModuleValue[T] {
      var result: Option[T] = None
      def execute(): Unit = { result = Some(body) }
      def getWrappedValue: T = result.get
    }
    scope.inModuleBody = out.execute _ +: scope.inModuleBody
    out
  }
}
```

其典型应用场景包括：

1. **在 LazyModule 内创建额外的硬件逻辑**：不同于 LazyModule children 的自动实例化，InModuleBody 中的代码在 instantiate() 末尾执行，可以访问已实例化的子模块。
2. **创建非 Diplomacy 的 IO 端口**：例如中断源的 `makeIOs()` 调用。
3. **跨协议边界的手动连接**：在自动 Diplomacy 连接之外添加额外的硬件连接。

### 6.2 XiangShan 中的 InModuleBody 使用

在 `XSNoCTop.scala` 的 `HasXSTile` trait 中可以看到典型用法：

```scala
val clintIntNode = Option.when(!UsePrivateClint)(IntSourceNode(IntSourcePortSimple(1, 1, 2)))
core_with_l2.clintIntNode.map(_ := clintIntNode.get)

val clint = InModuleBody(clintIntNode.map(_.makeIOs()))
val debug = InModuleBody(debugIntNode.makeIOs())
val plic = InModuleBody(plicIntNode.makeIOs())
val nmi = InModuleBody(nmiIntNode.makeIOs())
```

这里 `IntSourceNode.makeIOs()` 创建了 `HeterogeneousBag[Bool]` 类型的 IO 端口，这些端口在 LazyModuleImp 内部才能被访问和连接。`InModuleBody` 的返回值（`ModuleValue`）通过隐式转换可以自动解包获取实际值。

### 6.3 Scope 的嵌套与作用域控制

`LazyModule.scope` 使用栈结构管理嵌套关系。关键的维护点包括：

- `LazyModule.apply()` 在构造完成后将 scope 弹出（恢复为 parent）。
- `LazyScope.apply()` 在求值 body 前将 scope 设为自身，求值后恢复。
- `LazyModuleImpLike.instantiate()` 的入口处有一个 `require(LazyModule.scope.isEmpty)` 检查，确保所有 LazyModule 在硬件实例化之前已经完成构造。

这意味着 **Phase 1 和 Phase 2 严格分离**：在 Phase 1 中完成所有的 LazyModule 和 BaseNode 构造与连接后，才能进入 Phase 2 的硬件生成。

---

## 七、XiangShan 对 Diplomacy 的使用

### 7.1 层次结构概览

XiangShan 的 SoC 层次结构如下：

```
XSNoCTop / XSTop (BaseXSSoc)
  |-- XSTileWrap (LazyModule)
  |     |-- XSTile (LazyModule)
  |           |-- XSCore (LazyModule)
  |           |     |-- Frontend (LazyModule)
  |           |     |-- Backend (LazyModule)
  |           |     |-- MemBlock (LazyModule)
  |           |-- L2Top (LazyModule)
  |                 |-- L2TopInlined (LazyModule)
  |                       |-- TLXbar, TLBuffer, TLLogger
  |                       |-- CoupledL2 (LazyModule) [可选]
  |                       |-- BusErrorUnit (LazyModule)
  |                       |-- IntIdentityNode 等中断节点
```

### 7.2 XSTile 中的 Diplomacy 连接

`XSTile`（`xiangshan/XSTile.scala`）是核心计算单元，它将 `XSCore` 和 `L2Top` 组合在一起，并建立所有 Diplomacy 连接：

**内存通路（Memory Path）**：
```scala
l2top.inner.misc_l2_pmu := l2top.inner.l1d_logger := memBlock.dcache_port :=
  memBlock.l1d_to_l2_buffer.node := memBlock.dcache.clientNode
```

这里展示了一条典型的 TileLink 连接链：
- `dcache.clientNode` 是 DCache 的 TL Client 节点（SourceNode 角色）。
- `l1d_to_l2_buffer.node` 是 TLBuffer 的 Node（AdapterNode 角色），添加缓冲。
- `l1d_logger` 是 TLLogger 的 Node，记录事务日志。
- `misc_l2_pmu` 是 BusPerfMonitor 的 Node，收集性能计数器数据。
- 最终连接到 L2 缓存或直接到 L3。

**前端通路（Frontend Path）**：
```scala
l2top.inner.misc_l2_pmu := l2top.inner.l1i_logger := memBlock.frontendBridge.icache_node
```

**PTW 通路**：
```scala
l2top.inner.misc_l2_pmu := l2top.inner.ptw_logger := l2top.inner.ptw_to_l2_buffer.node := memBlock.ptw_to_l2_buffer.node
```

**MMIO 通路**：
```scala
l2top.inner.i_mmio_port := l2top.inner.i_mmio_buffer.node := memBlock.frontendBridge.instr_uncache_node
l2top.inner.d_mmio_port := memBlock.uncache_port
```

**中断连接**：
```scala
memBlock.clint_int_sink := IntBuffer() := clint_int_node
memBlock.plic_int_sink :*= IntBuffer() :*= plic_int_node
memBlock.debug_int_sink := IntBuffer() := debug_int_node
memBlock.nmi_int_sink   := IntBuffer() := nmi_int_node
```

这里使用了 `:=` 和 `:*= ` 两种运算符。对于 `plic_int_node`，使用 `:*= ` 是因为一个 PLIC 需要连接到多个中断目标，`IntBuffer` 的星号连接允许数量自动推断。

### 7.3 L2Top 中的互联

`L2Top`（`xiangshan/L2Top.scala`）管理 L2 缓存和 MMIO 总线：

**L2 缓存连接**：
```scala
l2cache match {
  case Some(l2) =>
    l2_binder.get :*= l2.node :*= xbar_l2_buffer :*= l1_xbar :=* misc_l2_pmu
    l2.managerNode := TLXbar() :=* l2_binder.get
    l2.mmioNode := mmio_port
  case None =>
    memory_port.get := l1_xbar
}
```

`l1_xbar`（`TLXbar`）是核心的交叉开关，汇聚所有 L1 缓存通路。`BankBinder` 将 L2 的 bank 数量从协商中解出。

**MMIO 总线**：
```scala
mmio_xbar := TLBuffer.chainNode(2) := i_mmio_port
mmio_xbar := TLBuffer.chainNode(2) := d_mmio_port
beu.node := TLBuffer.chainNode(1) := mmio_xbar
mmio_port := TLFilter(TLFilter.mSubtract(mmioFilters)) := TLBuffer() := mmio_xbar
```

MMIO 端口通过 `TLFilter` 过滤掉 SoC 内部地址，只向外暴露真正的 MMIO 设备。

### 7.4 XSNoCTop 与 XSTop 的顶层组织

`XSNoCTop`（`top/XSNoCTop.scala`）使用 trait 组合模式构建 SoC：

```scala
class XSNoCTop()(implicit p: Parameters) extends BaseXSSoc
  with HasXSTile
  with HasSeperatedBusOpt
  with HasIMSIC
  with HasTraceIO
```

**中断分发**通过 Diplomacy 节点链实现：
```scala
core_with_l2.clintIntNode.map(_ := clintIntNode.get)
core_with_l2.debugIntNode := debugIntNode
core_with_l2.plicIntNode :*= plicIntNode
core_with_l2.nmiIntNode := nmiIntNode
beuIntNode := core_with_l2.beuIntNode
```

**L3 与内存接口**（XSTop 模式）：
```scala
core_with_l2(i).memory_port.foreach(port => (misc.core_to_l3_ports.get)(i) :=* port)
```

**NMI 中断**使用了 InModuleBody：
```scala
val nmiIntNode = IntSourceNode(IntSourcePortSimple(1, NumCores, (new NonmaskableInterruptIO).elements.size))
val nmi = InModuleBody(nmiIntNode.makeIOs())
```

### 7.5 XSTileWrap 的异步桥接

`XSTileWrap`（`xiangshan/XSTileWrap.scala`）为 XSTile 添加异步时钟域桥接，用于 XSNoCTop 中不同电压/时钟域的分隔：

```scala
tile.clint_int_node := IntBuffer(3, cdc = true) := clintIntNode.getOrElse(timer.get.intnode)
tile.debug_int_node := IntBuffer(3, cdc = true) := debugIntNode
tile.plic_int_node :*= IntBuffer(3, cdc = true) :*= plicIntNode
tile.nmi_int_node := IntBuffer(3, cdc = true) := nmiIntNode
```

`IntBuffer(3, cdc = true)` 创建 3 级深度的 CDC（Clock Domain Crossing）缓冲器，确保中断信号安全跨越时钟域。

### 7.6 BundleBridge 的使用

XiangShan 在多处使用 `BundleBridgeSource` / `BundleBridgeSink` 来传递非总线的控制信号：

```scala
val core_reset_sink = BundleBridgeSink(Some(() => Reset()))
// ...
core_rst_nodes.zip(core_with_l2.map(_.core_reset_sink)).foreach({
  case (source, sink) => sink := source
})
```

BundleBridge 是 Diplomacy 框架提供的辅助机制，用于通过 Diplomacy 图传递任意类型的 Bundle 信号，而无需定义完整的自定义协议。

### 7.7 MixedAdapterNode 与协议桥接

在 `XSNoCTop` 的分离总线（Seperated Bus）支持中，可以看到 TL-to-AXI 协议转换的典型用法：

```scala
axiSlaveNode :=
  AXI4Buffer() :=
  AXI4IdentityNode() :=
  AXI4UserYanker() :=
  TLToAXI4() :=
  tlXbar.get
```

这里 `TLToAXI4()` 是一个 MixedAdapterNode 实现，将 TileLink 协议转换为 AXI4 协议。

---

## 八、源文件位置

### 8.1 Diplomacy 框架核心文件

| 文件路径 | 说明 |
|---------|------|
| `rocket-chip/src/main/scala/diplomacy/LazyModule.scala` | LazyModule、LazyModuleImp、InModuleBody、AutoBundle、Dangle、LazyScope 的定义 |
| `rocket-chip/src/main/scala/diplomacy/Nodes.scala` | BaseNode、MixedNode、SourceNode、SinkNode、NexusNode、AdapterNode、IdentityNode、EphemeralNode、NodeHandle、Binding 类型等 |
| `rocket-chip/src/main/scala/diplomacy/Parameters.scala` | AddressSet、IdRange、TransferSizes、BufferParams、RegionType、RenderedEdge 等参数类型 |
| `rocket-chip/src/main/scala/diplomacy/package.scala` | 包文档（包含详尽的概念说明和 ASCII 图示）、类型别名（SimpleNodeHandle、BundleBridgeNode 等）、工具函数 |
| `rocket-chip/src/main/scala/diplomacy/BundleBridge.scala` | BundleBridge Source/Sink/Node 定义 |
| `rocket-chip/src/main/scala/diplomacy/Clone.scala` | CloneLazyModule，用于克隆模块层次进行测试 |
| `rocket-chip/src/main/scala/diplomacy/CloneModule.scala` | CloneModule 底层支持 |
| `rocket-chip/src/main/scala/diplomacy/AddressDecoder.scala` | 地址解码器，根据 AddressSet 自动分配解码逻辑 |
| `rocket-chip/src/main/scala/diplomacy/SRAM.scala` | Diplomacy SRAM 组件 |
| `rocket-chip/src/main/scala/diplomacy/Resources.scala` | 资源绑定（DTS/JSON），用于生成设备树 |
| `rocket-chip/src/main/scala/diplomacy/Main.scala` | 辅助入口 |

### 8.2 XiangShan 中的 Diplomacy 使用文件

| 文件路径 | 说明 |
|---------|------|
| `src/main/scala/xiangshan/XSTile.scala` | XSTile：核心+L2 的顶层 LazyModule，建立所有 TL 和中断连接 |
| `src/main/scala/xiangshan/XSTileWrap.scala` | XSTileWrap：为 XSTile 添加异步时钟域桥接 |
| `src/main/scala/xiangshan/XSCore.scala` | XSCore/XSCoreBase：前端-后端-访存之间的 Diplomacy 连接 |
| `src/main/scala/xiangshan/L2Top.scala` | L2Top/L2TopInlined：L2 缓存层次的 Diplomacy 拓扑 |
| `src/main/scala/xiangshan/mem/MemBlock.scala` | MemBlock：TLBuffer 节点、前端 Bridge 节点 |
| `src/main/scala/xiangshan/cache/dcache/DCacheWrapper.scala` | DCache 的 TL Client 节点定义 |
| `src/main/scala/xiangshan/frontend/icache/ICache.scala` | ICache 的 TL Client 节点定义 |
| `src/main/scala/xiangshan/cache/mmu/L2TLB.scala` | L2 TLB 的 TL Client 节点定义 |
| `src/main/scala/top/Top.scala` | XSTop：SoC 顶层（传统总线模式），使用 XSTile 列表和 SoCMisc |
| `src/main/scala/top/XSNoCTop.scala` | XSNoCTop：SoC 顶层（NoC/CHI 模式），trait 组合 + CHI 接口 |
| `src/main/scala/system/SoC.scala` | SoCParameters 定义、HasSoCParameter trait、地址范围定义 |

---

## 九、总结

Diplomacy 框架通过 Scala 的惰性求值机制实现了参数协商与硬件生成的严格分离，为 XiangShan 这样的大规模 SoC 设计提供了强大的构建基础。其核心设计思想包括：

1. **两阶段 elaboration**：Phase 1 建立图结构和参数约束，Phase 2 执行协商并生成硬件，确保全局一致性。
2. **类型安全的 Node 体系**：通过 D/U/E/B 四类型参数和 NodeImp 实现编译时的协议正确性检查。
3. **灵活的 Binding 代数**：`:?=*` 等运算符支持从固定到动态的各种连接模式，NodeHandle 代数保证链式调用的正确性。
4. **自动化的 IO 管理**：AutoBundle 和 Dangle 机制自动处理跨模块边界的端口连接。

在 XiangShan 中，Diplomacy 被用于构建完整的 SoC 互联层次：从 L1 Cache 通过 TLXbar 汇聚到 L2 Cache，经 MMIO 总线处理外设访问，通过 CHI 或 AXI 接口连接到 L3/LLC 和外部内存。中断信号同样通过 Diplomacy 的 IntNode 体系进行分发。这种基于图的构建方式使得 XiangShan 能够灵活支持多核配置、可选的 L2 Cache、不同的 SoC 顶层拓扑（传统总线 vs NoC），以及异步时钟域桥接等复杂设计场景。
