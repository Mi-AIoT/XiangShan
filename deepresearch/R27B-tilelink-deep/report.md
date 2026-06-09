# R27B - TileLink Protocol Deep Dive

## 1. 概述

TileLink 是由 SiFive 开发的片上互连 (on-chip interconnect) 协议，用于连接 SoC 中的 master（client）和 slave（manager）设备。它以 Chisel/Diplomacy 框架为基础，在 Rocket-Chip 处理器中被广泛采用，同时也是 XiangShan 处理器 L1-L2 cache 接口的核心通信协议。本报告将深入分析 Rocket-Chip 中 `rocket-chip/src/main/scala/tilelink/` 目录下的 TileLink 实现，涵盖通道定义、能力级别、缓存一致性协议、节点类型与边缘参数、各种 Adapter、Crossbar 实现，以及 XiangShan 如何实际使用 TileLink 构建其片上总线拓扑。

---

## 2. TileLink Channel A-E 消息类型

TileLink 定义了五个通道（Channel A/B/C/D/E），使用 `DecoupledIO`（valid/ready 握手协议）进行通信。各通道的职责由源文件 `Bundles.scala` 定义。

### 2.1 Channel A - Client 请求通道

Channel A 是客户端向服务器端发送请求的主通道，由 `TLBundleA` 定义：

```
val opcode  = UInt(4.W)     // 消息操作码
val param   = UInt(...)     // 权限/原子操作类型
val size    = UInt(sizeBits.W)   // 传输大小（log2）
val source  = UInt(sourceBits.W) // 源标识符（from client）
val address = UInt(addressBits.W) // 目标地址
val mask    = UInt((dataBits/8).W) // 字节掩码
val data    = UInt(dataBits.W)     // 传输数据
val corrupt = Bool()               // 数据完整性标志
```

Channel A 支持的操作码（`TLMessages`）：

| 操作码 | 值 | 说明 | 对应的响应通道 |
|--------|-----|------|---------------|
| PutFullData | 0 | 全量写入 | Channel D: AccessAck |
| PutPartialData | 1 | 部分写入 | Channel D: AccessAck |
| ArithmeticData | 2 | 原子算术操作（MIN/MAX/ADD） | Channel D: AccessAckData |
| LogicalData | 3 | 原子逻辑操作（XOR/OR/AND/SWAP） | Channel D: AccessAckData |
| Get | 4 | 读取请求 | Channel D: AccessAckData |
| Hint | 5 | 预取提示（PREFETCH_READ/WRITE） | Channel D: HintAck |
| AcquireBlock | 6 | 获取缓存块（含数据） | Channel D: Grant[Data] |
| AcquirePerm | 7 | 获取缓存权限（不含数据） | Channel D: Grant[Data] |

XiangShan 还在 V2 版本后扩展了 A channel 操作码，增加了 CBOClean(12)、CBOFlush(13)、CBOInval(14) 用于 Cache Block Operation 指令（cbo.clean/cbo.flush/cbo.inval）。

### 2.2 Channel B - Manager 广播通道

Channel B 用于服务器端向客户端广播请求（主要用于缓存一致性中的 Probe 操作），由 `TLBundleB` 定义。其字段与 Channel A 类似，但 opcode 只有 3 位：

| 操作码 | 值 | 说明 |
|--------|-----|------|
| PutFullData | 0 | 广播写入 |
| PutPartialData | 1 | 广播部分写入 |
| ArithmeticData | 2 | 广播原子算术操作 |
| LogicalData | 3 | 广播原子逻辑操作 |
| Get | 4 | 广播读取 |
| Hint | 5 | 广播预取提示 |
| Probe | 6 | 缓存一致性探测请求 |

Channel B 只在 `hasBCE = true`（即支持 Acquire/Probe/Release 协议）时存在。

### 2.3 Channel C - Client 响应/回写通道

Channel C 用于客户端响应 Probe 请求（ProbeAck/ProbeAckData）或主动发起 Release（Release/ReleaseData），由 `TLBundleC` 定义。没有 mask 字段（C 通道传输以完整 cache line 为单位）：

| 操作码 | 值 | 说明 |
|--------|-----|------|
| AccessAck | 0 | 响应无数据操作 |
| AccessAckData | 1 | 响应有数据操作 |
| HintAck | 2 | 响应 Hint |
| ProbeAck | 4 | Probe 确认（无数据） |
| ProbeAckData | 5 | Probe 确认（含数据） |
| Release | 6 | 主动释放缓存块（无数据） |
| ReleaseData | 7 | 主动释放缓存块（含数据） |

Channel C 的 param 字段携带权限报告信息（Shrink/Report），如 TtoB（从 Trunk 降级到 Branch）、TtoN（完全释放）等。

### 2.4 Channel D - Manager 响应通道

Channel D 是服务器端对 Channel A/Channel B 请求的响应通道，由 `TLBundleD` 定义：

```
val opcode  = UInt(4.W)
val param   = UInt(bdWidth.W)    // 权限授予信息（Cap type）
val size    = UInt(sizeBits.W)
val source  = UInt(sourceBits.W) // 对应回 A/B 的 source
val sink    = UInt(sinkBits.W)   // 唯一标识 Grant 来源（用于 GrantAck）
val denied  = Bool()             // 请求被拒绝标志
val user    = BundleMap(...)     // 扩展字段
val data    = UInt(dataBits.W)
val corrupt = Bool()
```

| 操作码 | 值 | 说明 |
|--------|-----|------|
| AccessAck | 0 | 确认无数据写入 |
| AccessAckData | 1 | 返回读取数据 |
| HintAck | 2 | 确认 Hint |
| Grant | 4 | 权限授予（Acquire 响应） |
| GrantData | 5 | 权限授予+数据（Acquire 响应） |
| ReleaseAck | 6 | 确认 Release |
| CBOAck | 8 | 确认 Cache Block Operation（XiangShan V2扩展） |

Channel D 的 param 字段携带 Cap type 信息（toT/toB/toN），指示授予客户端的缓存权限。

### 2.5 Channel E - Client Grant 确认通道

Channel E 是客户端对 Channel D 中 Grant/GrantData 消息的最终确认，由 `TLBundleE` 定义：

```
val sink = UInt(sinkBits.W)  // 对应 D channel 的 sink
```

Channel E 是最简单的通道，只包含 sink 字段，用于通知 server 该 Grant 已被 client 接受，server 可以释放追踪状态。

### 2.6 通道存在性控制

在 `TLBundle` 中，通道的存在性由 `hasBCE` 参数控制：

```scala
private val optA = Some(Decoupled(new TLBundleA(params)))
private val optB = params.hasBCE.option(Flipped(Decoupled(new TLBundleB(params))))
private val optC = params.hasBCE.option(Decoupled(new TLBundleC(params)))
private val optD = Some(Flipped(Decoupled(new TLBundleD(params))))
private val optE = params.hasBCE.option(Decoupled(new TLBundleE(params)))
```

当 `hasBCE = false` 时（即 TL-UL），只保留 A 和 D 两个通道，大幅减少硬件开销。

---

## 3. TileLink 能力级别：TL-UL / TL-UH / TL-C

TileLink 协议分为三个能力级别，由通道的组合和支持的操作决定：

### 3.1 TL-UL (TileLink Uncached Lightweight)

- **通道**: 仅 Channel A + Channel D（无 B/C/E）
- **功能**: 基本的读写操作（Get/PutFullData/PutPartialData）、原子操作、Hint
- **适用场景**: 连接低带宽的 MMIO 设备（UART、PLIC、CLINT 等）
- **特点**: 不支持缓存一致性，不支持 Acquire 操作
- **实现判断**: 当没有任何 slave 支持 `AcquireB`，且没有任何 client 支持 `Probe` 时，自动降级为 TL-UL

### 3.2 TL-UH (TileLink Uncached Heavyweight)

- **通道**: Channel A + Channel B + Channel C + Channel D + Channel E
- **功能**: 包含 TL-UL 的全部功能，额外支持 Broadcast（B channel）和 Release（C channel）
- **适用场景**: 连接 DMA 控制器等需要参与一致性但自身不缓存的设备
- **特点**: 可以接收 Probe 请求但不提供缓存一致性保护

### 3.3 TL-C (TileLink Cached)

- **通道**: 全部五个通道 A/B/C/D/E
- **功能**: 完整的缓存一致性协议支持
- **核心操作**: Acquire（获取缓存块权限）、Probe（探测其他缓存状态）、Release（释放缓存块）
- **适用场景**: L1/L2/L3 Cache 之间的互连

### 3.4 能力级别的代码判定

在 `Parameters.scala` 中，能力级别的判定与 `RegionType` 和 `supports`/`emits` 参数直接相关：

```scala
// RegionType 定义（在 RegionType 对象中）:
object RegionType {
  sealed trait T
  case object CACHED       extends T  // 有缓存的数据存储
  case object TRACKED      extends T  // 被跟踪但不在一致域中
  case object UNCACHED     extends T  // 未缓存，支持 Get
  case object PUT_EFFECTS  extends T  // 写入即生效的设备
  case object GET_EFFECTS  extends T  // 读取即生效的设备
  case object IDEMPOTENT   extends T  // 幂等设备
  case object VOLATILE     extends T  // 易失性设备
}
```

能力级别的核心判定条件：
- TL-C: `regionType >= TRACKED` 且 `supportsAcquireB` 非空
- TL-UH: `regionType = UNCACHED` 但支持 Get
- TL-UL: `regionType >= PUT_EFFECTS` 的简单设备

---

## 4. 缓存一致性协议 (Cache Coherence in TileLink)

### 4.1 MESI 变种状态模型

TileLink 的缓存一致性基于类 MESI 协议，使用三种基本权限级别（定义在 `TLPermissions` 中）：

```
T (Trunk)  - 独占修改权，是全局序列化点
B (Branch) - 共享只读权
N (None)   - 无任何权限
```

客户端的本地状态（`ClientStates`）扩展为四种：

```scala
object ClientStates {
  def Nothing = 0.U  // 无数据
  def Branch  = 1.U  // 共享（B）状态
  def Trunk   = 2.U  // 独占（T）状态
  def Dirty   = 3.U  // 脏数据（T + 已修改）
}
```

### 4.2 权限转换操作

**Grow（权限提升）- Acquire 消息**:
- NtoB: 从 None 提升到 Branch（只读共享）
- NtoT: 从 None 提升到 Trunk（独占）
- BtoT: 从 Branch 提升到 Trunk（升级为独占）

**Cap（权限限制）- Grant/Probe 响应**:
- toT: 授予 Trunk 权限
- toB: 授予 Branch 权限
- toN: 剥夺所有权限

**Shrink（权限降级）- ProbeAck/Release 消息**:
- TtoB: Trunk 降级到 Branch
- TtoN: Trunk 降级到 None
- BtoN: Branch 降级到 None

**Report（状态报告）- ProbeAck/Release 消息**:
- TtoT: 报告当前持有 Trunk
- BtoB: 报告当前持有 Branch
- NtoN: 报告当前无权限

### 4.3 一致性事务流程

一个典型的 L1 Cache miss 触发的一致性事务：

1. **Client A** 在 Channel A 发送 `AcquireBlock(NtoT)` 请求获取缓存块
2. **Manager** 在 Channel B 向持有该缓存块的 **Client B** 发送 `Probe(toT)` 请求
3. **Client B** 在 Channel C 回复 `ProbeAckData(TtoT)` 并附带数据
4. **Manager** 在 Channel D 向 **Client A** 发送 `GrantData(toT, data)` 授予权限并返回数据
5. **Client A** 在 Channel E 发送 `GrantAck` 确认

`Metadata.scala` 中的 `ClientMetadata` 类实现了状态机的转换逻辑：

```scala
// onAccess: 判断是否命中及需要的权限提升参数
def onAccess(cmd: UInt): (Bool, UInt, ClientMetadata)

// onGrant: 根据 Grant 参数更新本地状态
def onGrant(cmd: UInt, param: UInt): ClientMetadata

// onProbe: 响应 Probe 请求，决定降级后的状态和报告参数
def onProbe(param: UInt): (Bool, UInt, ClientMetadata)
```

### 4.4 TLBroadcast - 一致性管理器

`Broadcast.scala` 实现了一个基于软件广播的一致性管理器（`TLBroadcast`），用于在没有硬件一致性互连的场景下实现缓存一致性。其工作原理：

- 维护 `numTrackers` 个请求追踪器（`TLBroadcastTracker`）
- 当收到 Acquire 请求时，向所有缓存客户端发送 Probe
- 使用 ProbeFilter 追踪哪些缓存持有特定地址的数据
- 将 Client 的 ReleaseData 转换为 PutFullData 发送到下级存储

---

## 5. TL 节点类型与边缘参数 (Nodes & Edge Parameters)

### 5.1 节点类型定义

节点类型在 `Nodes.scala` 中定义，基于 Diplomacy 框架的 `NodeImp`：

**TLImp**: TileLink 的核心 NodeImp，定义了边缘参数和 Bundle 的创建方式：

```scala
object TLImp extends NodeImp[
  TLMasterPortParameters,    // pd: 客户端端口参数
  TLSlavePortParameters,     // pu: 服务器端口参数
  TLEdgeOut,                 // 向外边缘参数
  TLEdgeIn,                  // 向内边缘参数
  TLBundle                   // Bundle 类型
]
```

**节点类型层次**：

| 节点类型 | 类名 | 说明 |
|---------|------|------|
| Source 节点 | `TLClientNode` | 客户端发起请求的源头节点 |
| Sink 节点 | `TLManagerNode` | 服务器端接收请求的汇聚节点 |
| Adapter 节点 | `TLAdapterNode` | 单进单出的协议转换节点 |
| Junction 节点 | `TLJunctionNode` | 多进多出的拓扑节点 |
| Identity 节点 | `TLIdentityNode` | 透传节点，不修改协议参数 |
| Nexus 节点 | `TLNexusNode` | 多进单出或单进多出的合并/分叉节点 |
| Ephemeral 节点 | `TLEphemeralNode` | 临时节点，不参与 Diplomacy 协商 |
| Custom 节点 | `TLCustomNode` | 自定义节点基类 |

### 5.2 Edge Parameters

边缘参数（Edge）在 `Edges.scala` 中定义，封装了连接两端的完整协议信息：

```scala
class TLEdge(
  client:  TLClientPortParameters,    // 客户端能力参数
  manager: TLManagerPortParameters,   // 服务器端能力参数
  params:  Parameters,
  sourceInfo: SourceInfo
) extends TLEdgeParameters(client, manager, params, sourceInfo)
```

`TLEdge` 提供的核心方法：
- `numBeats(x)`: 计算多拍传输的拍数
- `firstlast(x)`: 判断多拍传输的首拍和末拍
- `hasData(x)`: 判断消息是否携带数据
- `isRequest(x)` / `isResponse(x)`: 判断消息方向
- `needT(a)`: 判断请求是否需要 Trunk 权限
- `inFlight(x)`: 计算在途事务数

**TLEdgeOut** 提供消息构造工厂方法：
- `Get()`, `Put()`, `Arithmetic()`, `Logical()`：构造 A channel 消息
- `AcquireBlock()`, `AcquirePerm()`：构造一致性获取消息
- `Grant()`, `GrantData()`, `GrantAck()`：构造 Grant 消息
- `Release()`, `ProbeAck()`：构造 C channel 响应消息

### 5.3 TLMasterPortParameters / TLSlavePortParameters

**TLMasterPortParameters**（客户端端口参数）包含：
- `masters: Seq[TLMasterParameters]`：客户端列表，每个客户端有 name、sourceId 范围、支持的操作类型
- `channelBytes: TLChannelBeatBytes`：各通道的字节宽度
- `echoFields / requestFields / responseKeys`：扩展字段

**TLSlavePortParameters**（服务器端口参数）包含：
- `slaves: Seq[TLSlaveParameters]`：服务器列表，每个服务器有地址范围、regionType、支持的操作类型
- `endSinkId: Int`：sink ID 上限（用于 GrantAck 路由）
- `minLatency: Int`：最小延迟

### 5.4 TransferSizes

传输能力通过 `TransferSizes(min, max)` 精确描述，支持的每种操作都有独立的传输大小范围：

```scala
case class TLMasterToSlaveTransferSizes(
  acquireT:   TransferSizes,  // Acquire to-T 支持范围
  acquireB:   TransferSizes,  // Acquire to-B 支持范围
  arithmetic: TransferSizes,  // 原子算术操作范围
  logical:    TransferSizes,  // 原子逻辑操作范围
  get:        TransferSizes,  // Get 操作范围
  putFull:    TransferSizes,  // PutFull 操作范围
  putPartial: TransferSizes,  // PutPartial 操作范围
  hint:       TransferSizes   // Hint 操作范围
)
```

---

## 6. Adapters 详解

Adapter 是 TileLink 协议栈中最关键的组件，用于在不同协议特性之间进行转换。

### 6.1 TLBuffer - 协议缓冲器

**文件**: `Buffer.scala`

TLBuffer 在通道间插入流水线寄存器，增加延迟但提高时序性能：

```scala
class TLBuffer(a, b, c, d, e)(implicit p: Parameters) extends LazyModule {
  val node = new TLBufferNode(a, b, c, d, e)
  // 每个通道独立配置 buffer depth
  // 例如 BufferParams(depth=1) 插入一级寄存器
}
```

关键特性：
- 每个通道（A/B/C/D/E）可以独立配置 buffer 深度
- `minLatency` 会被正确传播：`p.v1copy(minLatency = p.minLatency + b.latency + c.latency)`
- 当使用 `chainNode(depth)` 时，可以级联多级 buffer
- `circuitIdentity` 检查：当所有通道均为 `BufferParams.none` 时，优化为直连

### 6.2 TLWidthWidget - 数据宽度转换器

**文件**: `WidthWidget.scala`

TLWidthWidget 在不同数据宽度的总线段之间进行转换：

```scala
class TLWidthWidget(innerBeatBytes: Int)(implicit p: Parameters) extends LazyModule {
  val node = new TLAdapterNode(
    clientFn = { case c => c },
    managerFn = { case m => m.v1copy(beatBytes = innerBeatBytes) }
  )
}
```

工作原理：
- **merge（窄到宽）**: 将多个窄拍数据合并为一个宽拍，使用计数器和移位寄存器
- **split（宽到窄）**: 将一个宽拍数据拆分为多个窄拍
- **splice**: 统一处理 merge/split 逻辑
- 关键挑战：D 通道没有地址信息，需要通过 `sourceMap` 记录 A 通道请求的地址位，用于在 D 通道响应时正确提取数据片段

### 6.3 TLFragmenter - 传输分片器

**文件**: `Fragmenter.scala`

TLFragmenter 将大块传输拆分为多个小块传输：

```scala
class TLFragmenter(minSize: Int, maxSize: Int, ...) extends LazyModule
```

- `minSize`: 分片后的最小传输大小
- `maxSize`: 允许的最大传输大小
- 通过 source ID 编码 fragment 信息：高位表示 fragment 序号
- **限制**: 不能修改 Acquire 消息（会导致活锁），因此不能放在两个缓存之间
- 所有 manager 必须共享同一个 FIFO 域

### 6.4 TLFilter - 协议过滤器

**文件**: `Filter.scala`

TLFilter 在 Diplomacy 层面过滤客户端和服务器端的能力：

```scala
class TLFilter(
  mfilter: TLFilter.ManagerFilter = TLFilter.mIdentity,
  cfilter: TLFilter.ClientFilter  = TLFilter.cIdentity
) extends LazyModule
```

预定义的过滤器：
- `mSelectIntersect(select)`: 只保留与指定地址集相交的 server
- `mSubtract(excepts)`: 从地址集中排除指定范围
- `mHideCacheable`: 隐藏所有支持 Acquire 的 cacheable server
- `mMaskCacheable`: 保留 cacheable server 但屏蔽其 Acquire 能力
- `cHideCaching`: 隐藏所有支持 Probe 的 caching client

TLFilter **只移除能力，不添加能力**，这是其安全保证。

### 6.5 TLCacheCork - Cache Cork 适配器

**文件**: `CacheCork.scala`

TLCacheCork 将缓存一致性协议转换为非缓存协议，放置在缓存和非缓存设备之间：

```scala
class TLCacheCork(params: TLCacheCorkParams)(implicit p: Parameters) extends LazyModule
```

核心转换逻辑：
- `Acquire => Get`：将一致性获取转换为普通读取
- `ReleaseData => PutFullData`：将释放操作转换为写入
- `AccessAckData => GrantData`：将普通读响应转换为权限授予响应
- 使用 sink ID 池（`IDPool`）追踪在途的 Grant 事务
- BtoT Acquire 和 AcquirePerm 可以立即成功响应（不需要实际数据传输）

### 6.6 TLSourceShrinker - Source ID 压缩器

**文件**: `SourceShrinker.scala`

TLSourceShrinker 将宽范围的 source ID 映射到较小的范围：

```scala
class TLSourceShrinker(maxInFlight: Int)(implicit p: Parameters) extends LazyModule
```

- 维护 `sourceIdMap` 寄存器组进行 ID 映射
- 使用位图（`allocated`）追踪已分配的 ID
- 在 A first 拍分配新 ID，在 D last 拍释放 ID
- **破坏 FIFO 保证**：不保留原始请求的 FIFO 顺序

---

## 7. Xbar (Crossbar) 实现

### 7.1 TLXbar 核心实现

**文件**: `Xbar.scala`

TLXbar 是 TileLink 最核心的互连组件，实现 M:N 全交叉开关：

```scala
class TLXbar(policy: TLArbiter.Policy = TLArbiter.roundRobin)(implicit p: Parameters) extends LazyModule
{
  val node = new TLNexusNode(
    clientFn  = { seq => ... },   // 合并多个客户端参数
    managerFn = { seq => ... }    // 合并多个服务器端参数
  )
}
```

**ID 空间管理**：

TLXbar 使用 `assignRanges` 函数为每个输入端口分配不重叠的 source ID 范围：

```scala
def assignRanges(sizes: Seq[Int]) = {
  val pow2Sizes = sizes.map(z => if (z == 0) 0 else 1 << log2Ceil(z))
  // 按大小排序后分配后缀和位置
  // 保证每个端口的 ID 范围是 2 的幂对齐的
}
```

**路由逻辑**：

TLXbar 使用地址解码器（`AddressDecoder`）生成路由表：

```scala
val outputPortFns: Map[Vector[Boolean], Seq[UInt => Bool]] = requiredAC.map { connectO =>
  val routingMask = AddressDecoder(filter(port_addrs, connectO))
  val route_addrs = port_addrs.map(seq => 
    AddressSet.unify(seq.map(_.widen(~routingMask)).distinct))
  (connectO, route_addrs.map(seq => (addr: UInt) => 
    seq.map(_.contains(addr)).reduce(_ || _)))
}
```

关键优化：
- **选择性连接**: 只有存在地址重叠的 client-manager 对才会建立实际的物理连接
- **Probe/Release 路由**: 基于 source ID 范围进行路由（而非地址），因为 B/C 通道的 source 表示目标 client
- **Fanout 机制**: `TLXbar.fanout` 将一个输入广播到多个输出端口

### 7.2 TLArbiter - 仲裁器

**文件**: `Arbiter.scala`

TLArbiter 实现 DecoupledIO 信号的仲裁，支持三种策略：

```scala
object TLArbiter {
  val lowestIndexFirst: Policy  // 低索引优先
  val highestIndexFirst: Policy // 高索引优先
  val roundRobin: Policy        // 轮询仲裁
}
```

核心仲裁逻辑：
1. 当空闲时（`beatsLeft === 0`），根据仲裁策略选择获胜者
2. 获胜者锁定通道，直到多拍传输完成
3. 使用 `Mux1H` 进行 one-hot 选择，保证组合逻辑高效
4. 支持 burst 传输：`beatsIn` 参数指示剩余拍数

### 7.3 TLJbar - Junction Bar

**文件**: `Jbar.scala`

TLJbar 使用 `TLJunctionNode` 实现不对称的交叉开关（输入输出端口数可以不同），内部复用 `TLXbar.circuit` 的实现。

---

## 8. XiangShan 如何使用 TileLink

### 8.1 L1 Cache TileLink Client 定义

**DCache**（`DCacheWrapper.scala`）：

```scala
val clientParameters = TLMasterPortParameters.v1(
  Seq(TLMasterParameters.v1(
    name = "dcache",
    sourceId = IdRange(0, nEntries + 1),
    supportsProbe = TransferSizes(cfg.blockBytes)
  )),
  requestFields = reqFields,  // 包含 ReqSourceField, VaddrField, AliasField 等
  echoFields = echoFields      // 包含 IsKeywordField
)
val clientNode = TLClientNode(Seq(clientParameters))
```

**ICache**（`ICache.scala`）：

```scala
val clientParameters: TLMasterPortParameters = TLMasterPortParameters.v1(
  Seq(TLMasterParameters.v1(
    name = "icache",
    sourceId = IdRange(0, NumFetchMshr + NumPrefetchMshr + 1)
  )),
  requestFields = Seq(ReqSourceField(), MemBackTypeMMField()) ++
    AliasTagBits.map(AliasField).toSeq
)
val clientNode: TLClientNode = TLClientNode(Seq(clientParameters))
```

两个 L1 Cache 都使用 `requestFields` 和 `echoFields` 扩展标准 TileLink 协议，传递虚拟地址、别名标签、请求来源等自定义信息。

### 8.2 L1-L2 总线拓扑 (L2Top.scala)

`L2Top` 是连接 Core 和 XSTile-IO 之间的中间层，构建了 L1 到 L2 的总线拓扑：

```
ICache --+
PTW -----+--> l1_xbar --> xbar_l2_buffer --> L2 Cache (CoupledL2)
DCache -+                         |
                                v
                         l2_binder --> memory_port (TLIdentityNode)
```

关键连接：
```scala
val l1_xbar = TLXbar()        // L1 互连交叉开关
val mmio_xbar = TLXbar()      // MMIO 设备交叉开关
val xbar_l2_buffer = TLBuffer() // l1_xbar 到 L2 之间的流水线寄存器

// L2 缓存连接
l2_binder.get :*= l2.node :*= xbar_l2_buffer :*= l1_xbar :=* misc_l2_pmu

// MMIO 路径
mmio_xbar := TLBuffer.chainNode(2) := i_mmio_port
mmio_xbar := TLBuffer.chainNode(2) := d_mmio_port
beu.node := TLBuffer.chainNode(1) := mmio_xbar
```

XiangShan 使用 `TLLogger` 对 L1D、L1I、PTW 三个路径分别进行 TileLink 事务日志记录，用于调试和性能分析。

### 8.3 SoC 级总线拓扑 (SoC.scala)

SoC 级别定义了 L2 到 DDR 以及外设的完整互连：

**TL-CHI 模式（启用CHI时）**：
- 使用 AXI4Xbar 代替 TLXbar
- L2/L3 通过 CHI 协议连接

**TL 模式（非CHI）**：
```scala
// L3 互连
val l3_xbar = TLXbar()
val l3_banked_xbar = TLXbar()
val peripheralXbar = TLXbar()
val mem_xbar = TLXbar()

// L3 到 DDR 的路径
mem_xbar :=*
  TLBuffer.chainNode(2) :=
  TLCacheCork() :=             // 将 TL-C 转换为 TL-UH
  TLClientsMerger() :=         // 合并多个 client 的 source ID
  TLXbar() :=*
  bankedNode.get               // L3 bank 分配

// 外设总线
axi4mem_node :=
  TLToAXI4() :=                // TileLink 到 AXI4 桥接
  TLSourceShrinker(64) :=      // 压缩 source ID 到 64 个
  TLWidthWidget(L3OuterBusWidth / 8) :=  // 宽度转换
  TLBuffer.chainNode(2) :=
  mem_xbar
```

### 8.4 XSTile 内部连接 (XSTile.scala)

`XSTile` 将 Core、L2Top 组合在一起，定义了完整的单核 Tile 连接：

```scala
// L1D -> l1_xbar
l2top.inner.misc_l2_pmu := l2top.inner.l1d_logger := memBlock.dcache_port :=
  memBlock.l1d_to_l2_buffer.node := memBlock.dcache.clientNode

// L1I -> l1_xbar
l2top.inner.misc_l2_pmu := l2top.inner.l1i_logger := memBlock.frontendBridge.icache_node

// PTW -> l1_xbar
l2top.inner.misc_l2_pmu := l2top.inner.ptw_logger := 
  l2top.inner.ptw_to_l2_buffer.node := memBlock.ptw_to_l2_buffer.node

// MMIO 路径
l2top.inner.i_mmio_port := l2top.inner.i_mmio_buffer.node := 
  memBlock.frontendBridge.instr_uncache_node
l2top.inner.d_mmio_port := memBlock.uncache_port
```

### 8.5 使用的 TileLink 组件总结

XiangShan SoC 中使用的 TileLink 组件：

| 组件 | 使用位置 | 用途 |
|------|---------|------|
| TLXbar | l1_xbar, mmio_xbar, l3_xbar, mem_xbar, peripheralXbar | 交叉开关互连 |
| TLBuffer | l1_xbar 与 L2 之间、各级总线段之间 | 流水线插入，改善时序 |
| TLWidthWidget | 外设总线段、mem 路径 | 数据宽度转换（8B <-> 64B） |
| TLCacheCork | L3 到 mem 路径 | 缓存一致性到非一致性转换 |
| TLFilter | mmio_port 输出端 | 过滤内部地址范围 |
| TLSourceShrinker | AXI4 桥接前 | source ID 压缩 |
| TLFragmenter | 外设访问路径 | 大块传输分片 |
| TLLogger | L1D/L1I/PTW 路径 | TileLink 事务日志记录 |
| TLIdentityNode | memory_port, mmio_port | 透传端口定义 |
| TLTempNode | i_mmio_port, d_mmio_port | 临时连接节点 |

---

## 9. 源文件位置

### 9.1 Rocket-Chip TileLink 核心文件

所有文件位于 `rocket-chip/src/main/scala/tilelink/` 目录下：

| 文件 | 内容 | 行数 |
|------|------|------|
| `Bundles.scala` | A-E 通道 Bundle 定义、消息操作码、权限类型 | ~332 |
| `Parameters.scala` | TLSlaveParameters、TLMasterParameters、TransferSizes、BundleParameters | ~1100+ |
| `Edges.scala` | TLEdge、TLEdgeOut、TLEdgeIn，消息构造工厂方法 | ~500+ |
| `Nodes.scala` | TL 节点类型定义（TLClientNode、TLManagerNode、TLAdapterNode 等） | ~192 |
| `Xbar.scala` | TLXbar 交叉开关核心实现 | ~410 |
| `Arbiter.scala` | TLArbiter 仲裁器（roundRobin/lowestIndex/highestIndex） | ~193 |
| `Buffer.scala` | TLBuffer 流水线缓冲器 | ~85 |
| `Filter.scala` | TLFilter 协议过滤器 | ~178 |
| `CacheCork.scala` | TLCacheCork 缓存一致性到非一致性适配器 | ~187 |
| `Broadcast.scala` | TLBroadcast 软件广播一致性管理器 | ~500+ |
| `WidthWidget.scala` | TLWidthWidget 数据宽度转换器 | ~254 |
| `Fragmenter.scala` | TLFragmenter 传输分片器 | ~400+ |
| `SourceShrinker.scala` | TLSourceShrinker Source ID 压缩器 | ~94 |
| `Jbar.scala` | TLJbar 不对称交叉开关 | ~81 |
| `Metadata.scala` | ClientMetadata 缓存一致性元数据状态机 | ~167 |
| `package.scala` | 类型别名定义（TLNode、TLManagerParameters 等） | ~32 |
| `Monitor.scala` | TileLink 协议监控器 | ~1000+ |
| `SRAM.scala` | TileLink SRAM 端点 | ~400+ |
| `RegisterRouter.scala` | TileLink 寄存器路由器 | ~250+ |
| `AddressAdjuster.scala` | 地址调整适配器 | ~500+ |
| `BusWrapper.scala` | 总线包装器 | ~350+ |
| `FIFOFixer.scala` | FIFO 顺序修复器 | ~160+ |
| `HintHandler.scala` | Hint 处理器 | ~180+ |

### 9.2 XiangShan TileLink 使用文件

| 文件 | 关键使用 |
|------|---------|
| `src/main/scala/xiangshan/L2Top.scala` | L1_xbar, mmio_xbar, TLBuffer 链, L2 连接拓扑 |
| `src/main/scala/xiangshan/XSTile.scala` | 单核 Tile 的 L1D/L1I/PTW 连接 |
| `src/main/scala/system/SoC.scala` | SoC 级 TLXbar, TLCacheCork, TLWidthWidget 拓扑 |
| `src/main/scala/xiangshan/cache/dcache/DCacheWrapper.scala` | DCache TLClientNode 定义 |
| `src/main/scala/xiangshan/frontend/icache/ICache.scala` | ICache TLClientNode 定义 |
| `src/main/scala/xiangshan/cache/mmu/PageTableWalker.scala` | PTW TileLink 客户端 |
| `src/main/scala/xiangshan/cache/dcache/mainpipe/WritebackQueue.scala` | Writeback 使用 TLPermissions |
| `src/main/scala/xiangshan/cache/dcache/mainpipe/MissQueue.scala` | Miss 处理使用 TL 消息 |
| `src/main/scala/top/Top.scala` | TLNexusNode 管理器绑定 |
| `src/main/scala/top/BusPerfMonitor.scala` | TLMessages 使用与性能监控 |

---

## 10. 总结

TileLink 协议通过精心设计的五通道架构实现了从简单 MMIO 访问到复杂缓存一致性的完整支持：

1. **协议分层**: TL-UL / TL-UH / TL-C 三个级别允许按需选择，平衡功能与硬件开销
2. **Diplomacy 集成**: 通过 Node/Edge/Parameters 在 elaboration 阶段完成协议协商和 ID 空间分配，避免运行时冲突
3. **丰富的 Adapter 生态**: Buffer、WidthWidget、Fragmenter、CacheCork、Filter 等适配器覆盖了常见的总线转换需求
4. **可扩展性**: 通过 `requestFields`/`responseFields`/`echoFields` 机制，XiangShan 能够在不破坏协议兼容性的前提下扩展自定义信息（如虚拟地址、请求来源等）

XiangShan 采用 Rocket-Chip 的 TileLink 基础设施构建了完整的多级缓存互连拓扑，通过 L1_xbar 汇聚 ICache/DCache/PTW 请求，经过 L2 Cache 后通过 TLCacheCork 将一致性协议转换为非一致性，最终通过 TLToAXI4 桥接器连接到 DDR 控制器。MMIO 设备通过独立的 mmio_xbar 路径访问，与 cache 路径完全隔离。
