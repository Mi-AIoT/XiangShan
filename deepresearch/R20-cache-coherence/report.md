# R20 - Cache Coherence & NoC (Network on Chip)

## 1. 概述

XiangShan 处理器实现了完整的多级缓存层次结构，从 L1 到 L3 采用不同的总线协议进行互连。与常见假设不同，XiangShan 的 L2-L3 互连并非使用 TileLink 协议，而是采用了 ARM CHI (Coherent Hub Interface) 协议。整个缓存层次结构如下：

```
  ┌──────────┐   ┌──────────┐
  │ Core 0   │   │ Core 1   │   ...
  │ L1I + L1D│   │ L1I + L1D│
  └────┬─────┘   └────┬─────┘
       │ TileLink     │ TileLink
       ▼              ▼
  ┌─────────────────────────────┐
  │         L2 Cache            │
  │    (CoupledL2, CHI IF)      │
  │  ┌──────┐ ┌──────┐         │
  │  │Slice0│ │Slice1│ ...     │
  │  └──┬───┘ └──┬───┘         │
  └─────┼────────┼─────────────┘
        │  ARM CHI Protocol
        ▼
  ┌─────────────────────────────┐
  │       L3 Cache (OpenLLC)    │
  │    (CHI-CHI Architecture)   │
  │  ┌──────┐ ┌──────┐         │
  │  │Slice0│ │Slice1│ ...     │
  │  └──┬───┘ └──┬───┘         │
  └─────┼────────┼─────────────┘
        │  AXI4 Protocol
        ▼
  ┌─────────────────────────────┐
  │      Memory Controller      │
  └─────────────────────────────┘
```

## 2. L2 Cache 实现 (CoupledL2)

### 2.1 架构概述

L2 Cache 位于 `XSCache/src/main/scala/coupledL2/` 目录下，核心类为 `CoupledL2`。它是一个 LazyModule，上方通过 TileLink TLAdapterNode 连接 L1 缓存，下方通过 CHI PortIO 连接 L3。

关键源文件：
- `CoupledL2.scala` - L2 顶层模块，实例化所有 Slice 并连接 CHI 通道
- `L2Param.scala` - L2 参数定义（默认 sets=128, ways=4, blockBytes=64, mshrs=16, replacement="drrip"）
- `Slice.scala` - 单 bank 处理单元
- `Directory.scala` - 3 级流水线目录
- `MSHR.scala` - MSHR 状态机（约 1463 行）
- `MainPipe.scala` - L2 主流水线
- `Common.scala` - 公共数据结构（TaskBundle, FSMState 等）
- `Consts.scala` - MetaData 状态定义

### 2.2 Banked 架构

L2 采用多 bank 设计，每个 bank 是一个独立的 `Slice`。每个 Slice 内部包含完整的处理流水线：

```
Slice 内部结构:
┌─────────────────────────────────────────────────────┐
│  Upward (TileLink)          Downward (CHI)          │
│  ┌──────┐  ┌──────┐   ┌──────┐ ┌──────┐ ┌──────┐ │
│  │SinkA │  │SinkC │   │TXREQ │ │TXRSP │ │TXDAT │ │
│  └──┬───┘  └──┬───┘   └──┬───┘ └──┬───┘ └──┬───┘ │
│     └────┬────┘          └───┬────┘         │      │
│          ▼                   ▼              │      │
│     ┌─────────┐       ┌──────────┐         │      │
│     │Request  │       │ RXSNP    │         │      │
│     │Arb      │       │ RXDAT    │         │      │
│     └────┬────┘       │ RXRSP    │         │      │
│          ▼            └────┬─────┘         │      │
│     ┌─────────┐            │               │      │
│     │MainPipe │◄───────────┘               │      │
│     └────┬────┘                            │      │
│          ▼                                 │      │
│  ┌──────────┐  ┌────────────┐  ┌────────┐ │      │
│  │Directory │  │DataStorage │  │MSHRCtl │◄┘      │
│  └──────────┘  └────────────┘  └────────┘        │
│  ┌──────────┐  ┌──────────┐                       │
│  │RefillBuf │  │ReleaseBuf│                       │
│  └──────────┘  └──────────┘                       │
└─────────────────────────────────────────────────────┘
```

### 2.3 Directory 3 级流水线

L2 Directory 实现为 3 级流水线：

1. **Stage 1 (S1)**: 当 `io.read.fire` 时，发起 SRAM 读操作（tagArray + metaArray）
2. **Stage 2 (S2)**: 锁存读出结果（`reqValid_s2`, `req_s2`）
3. **Stage 3 (S3)**: 计算命中/路选择，生成最终结果

```
S3 阶段关键逻辑:
tagMatchVec = tagAll_s3.map(_ === req_s3.tag)    // Tag 比较
metaValidVec = metaAll_s3.map(_.valid)             // 有效位检查
hitVec = tagMatchVec & metaValidVec                // 命中向量
hitWay = OHToUInt(hitVec)                          // 命中路
replaceWay = repl.get_replace_way(repl_state)      // 替换路
chosenWay = Mux(inv, invalidWay, replaceWay)       // 优先选无效路
```

MetaEntry 包含以下字段：`valid`, `dirty`, `state` (2-bit), `clients` (位向量), `alias`, `prefetch`, `accessed`, `tagErr`, `dataErr`。

### 2.4 替换策略

L2 默认使用 DRRIP (Dynamic Re-reference Interval Prediction) 替换策略，配合 Set Dueling 机制：

- PSEL 计数器根据 Set Dueling 结果选择 BRRIP 或 SRRIP
- 当 Set 0 命中时 PSEL++，Set 1 命中时 PSEL--
- PSEL > 阈值时选择 BRRIP，否则选择 SRRIP

## 3. Cache Coherence 协议

### 3.1 双层协议架构

XiangShan 采用双层一致性协议：

| 层级 | 协议 | 角色 |
|------|------|------|
| L1-L2 | TileLink | L1 作为 Manager/Client，L2 作为 Manager |
| L2-L3 | ARM CHI | L2 作为 RN (Request Node)，L3 作为 HN (Home Node) |

### 3.2 L2 MetaData 状态机

L2 内部使用 4 状态的 TileLink 一致性模型（定义在 `Consts.scala`）：

```
MetaData 状态:
┌─────────┐
│ INVALID │ (0) - way 为空
└────┬────┘
     │ Acquire(NtoB)
     ▼
┌─────────┐
│ BRANCH  │ (1) - outer slave cache is trunk
└────┬────┘
     │ Acquire(BtoT)
     ▼
┌─────────┐
│  TRUNK  │ (2) - unique inner master cache is trunk
└────┬────┘
     │ no inner clients
     ▼
┌─────────┐
│   TIP   │ (3) - we are trunk, inner masters are branch
└─────────┘

关键判断函数:
- needB: Get, Acquire(NtoB), PrefetchRead
- needT: !opcode(2), PrefetchWrite, Acquire(*toT)
- isT:   state(1) -- TRUNK 或 TIP
- growFrom: NtoB→INVALID, NtoT→INVALID, BtoT→BRANCH
```

### 3.3 CHI 一致性状态

L2 对外（对 L3）使用 CHI 一致性状态（定义在 `xscache/chi/Message.scala`）：

```scala
object CHICohStates {
  def I  = "b000"  // Invalid
  def SC = "b001"  // Shared Clean
  def UC = "b010"  // Unique Clean (与 UD 编码相同)
  def UD = "b010"  // Unique Dirty
  def SD = "b011"  // Shared Dirty
  def PassDirty = "b100"  // 传递脏标记
}
```

### 3.4 MetaData 到 CHI 状态映射

MSHR 中定义了 L2 内部状态到 CHI 状态的映射关系：

```
Meta(dirty, state)  →  CHI State
─────────────────────────────────
(false, INVALID)    →  I
(false, BRANCH)     →  SC
(false, TRUNK)      →  UC
(false, TIP)        →  UC
(true,  TRUNK)      →  UD
(true,  TIP)        →  UD
```

### 3.5 Snoop 处理

L2 通过 RXSNP 通道接收来自 L3 的 Snoop 请求，处理逻辑在 MSHR 中实现。主要 Snoop 类型及响应：

```
Snoop 类型        →  目标状态    →  响应
────────────────────────────────────────
SnpShared/SnpClean/SnpNotSharedDirty (Non-Fwd)  →  BRANCH  →  SnpResp(SC)
SnpSharedFwd/SnpCleanFwd/SnpNotSharedDirtyFwd   →  BRANCH  →  SnpRespFwded(SC)
SnpUnique/SnpUniqueStash/SnpPreferUnique        →  INVALID →  SnpResp(I)
SnpUniqueFwd/SnpPreferUniqueFwd                 →  INVALID →  SnpRespFwded(I)
SnpMakeInvalid/SnpMakeInvalidStash              →  INVALID →  SnpResp(I)
SnpOnce                                         →  不变    →  SnpResp(metaChi)
SnpOnceFwd                                      →  不变    →  SnpRespFwded(metaChi)
```

对于 Dirty 数据的 Snoop 响应，会返回 `SnpRespData` 而非 `SnpResp`，具体条件由 `doRespData_dirty`、`doRespData_retToSrc_fwd` 等信号控制。

### 3.6 DCT (Direct Cache Transfer) 机制

当 L2 收到带 Fwd 的 Snoop（如 SnpSharedFwd）时，可以将数据直接转发给请求方 L2，而不必经过 L3。这由 `doFwd` 和 `doFwdHitRelease` 信号控制，对应的 task 通过 `mp_dct_valid` 发送到 MainPipe。

## 4. MSHR 状态机

### 4.1 FSM 状态信号

MSHR 状态机使用 Schedule + Wait 信号对控制流程（定义在 `Common.scala` 的 `FSMState`）：

**Schedule 信号**（主动执行）：
- `s_acquire` - 发送 CHI Acquire 请求
- `s_rprobe` / `s_pprobe` - 发送 Snoop 到内层
- `s_release` - 发送 Release 到外层
- `s_probeack` - 发送 ProbeAck 到内层
- `s_refill` - 发送 Refill Grant 到内层
- `s_rcompack` - 发送 ReadCompAck
- `s_wcompack` - 发送 WriteCompAck
- `s_cbwrdata` - 发送 CopyBackWrData
- `s_reissue` - 重新发送请求（Retry 后）
- `s_dct` - 执行 Direct Cache Transfer

**Wait 信号**（等待响应）：
- `w_rprobeackfirst/last` - 等待 Release ProbeAck
- `w_pprobeackfirst/last` - 等待 Probe 响应
- `w_grantfirst/last` / `w_grant` - 等待 L3 数据
- `w_releaseack` - 等待 Release 确认
- `w_replResp` - 等待替换策略响应

### 4.2 状态转移流程

典型的 L2 Miss 处理流程：

```
1. 分配 MSHR, 发送 Acquire (s_acquire↓)
   │
   ▼ 等待 L3 响应 (w_grantfirst, w_grantlast, w_grant)
   │ 同时: 发送 CompAck (s_rcompack↓)
   │
   ▼ 等待 Probe 响应 (w_rprobeackfirst, w_rprobeacklast)
   │ 同时: 发送 Snoop 到 L1 (s_rprobe↓, s_pprobe↓)
   │ 等待: w_pprobeackfirst, w_pprobeacklast
   │
   ▼ 发送 Refill 数据到 L1 (s_refill↓)
   │ 等待替换策略响应 (w_replResp)
   │
   ▼ [可选] 发送 Release 到 L3 (s_release↓)
   等待 Release Ack (w_releaseack)
   │
   ▼ MSHR 完成
```

### 4.3 Backoff 重试机制

MSHR 实现了 backoff 机制防止死锁：

- `backoffThreshold = 3`: 允许立即重试 3 次
- `backoffCycles = 20`: 超过 3 次后需等待 20 个周期
- `mp_grant_valid` 条件：`retryTimes < backoffThreshold || backoffTimer === backoffCycles`

### 4.4 CHI Retry 机制

当 L3 返回 `RetryAck` 时：
1. MSHR 记录 `gotRetryAck` 和 `srcid_retryack`
2. 通过 `pCrd.query` 请求 PCrdGrant
3. 收到 `PCrdGrant` 后（`gotPCrdGrant`），通过 `s_reissue` 重新发送请求

### 4.5 死锁检测

MSHR 包含 400K 周期超时检测机制，如果 MSHR 分配后长时间未完成，会触发断言报警。

### 4.6 Nested Writeback

当 L2 MSHR 正在处理一个 Miss 时，如果收到针对同一 cacheline 的 Snoop，MSHR 需要处理 Nested Writeback 场景。通过 `nestedwb` 输入信号和 `nestedwbData` 输出信号协调。关键场景包括：

- `hitDirty`: Snoop 命中 MSHR 正在处理的 dirty 数据
- `hitWriteBack/WriteClean/WriteEvict`: Snoop 命中 MSHR 正在发送的 Release 数据

## 5. TileLink 总线协议使用

### 5.1 L1-L2 接口

TileLink 仅在 L1 到 L2 之间使用。L2 通过以下 TileLink 消息与 L1 交互：

**L1 → L2 (请求)**:
- `AcquireBlock` - L1 请求缓存块（NtoB 或 *toT）
- `AcquirePerm` - L1 请求权限（不带数据）
- `Get` - 读取
- `PutPartialData` / `PutData` - 写入
- `Hint` - Prefetch 提示
- `CBOClean/Flush/Inval` - Cache 管理操作

**L2 → L1 (响应)**:
- `Grant` / `GrantData` - Acquire 响应
- `HintAck` - Prefetch 响应
- `Release` - L1 主动释放
- `Probe` / `ProbeAck` / `ProbeAckData` - L2 发起的 Snoop

### 5.2 TileLink 到 CHI 的协议转换

L2 内部的 TaskBundle 同时包含 TileLink 和 CHI 字段，实现了协议桥接：

```scala
class TaskBundle {
  // TileLink 字段
  val channel: UInt   // A/B/C/D/E
  val opcode: UInt    // TL message opcode
  val param: UInt     // TL permission
  
  // CHI 字段
  val tgtID: UInt     // Target Node ID
  val srcID: UInt     // Source Node ID
  val txnID: UInt     // Transaction ID
  val chiOpcode: UInt // CHI opcode
  val resp: UInt      // CHI response
  val fwdState: UInt  // CHI forward state
}
```

### 5.3 MMIO 处理

非缓存访问通过 MMIO Bridge 处理：
- `SinkA` 检测 MMIO 请求
- 发送到 `MMIOBridge`（CHI NoSnp 端口）
- 响应通过 MMIO 路径返回 L1

## 6. L3 Cache 实现 (OpenLLC)

### 6.1 架构概述

L3 Cache 位于 `XSCache/src/main/scala/openLLC/` 目录下，采用 CHI-CHI 架构。上方通过 CHI RN 端口连接多个 L2，下方通过 CHI SN 端口连接内存控制器。

关键源文件：
- `OpenLLC.scala` - L3 顶层模块
- `Directory.scala` - 双目录（SelfDir + Snoop Filter）
- `MainPipe.scala` - L3 主流水线（Stage 2-6）
- `Slice.scala` - L3 bank 处理单元
- `LLCParam.scala` - L3 参数（sets=128, ways=4, banks=4, replacement="plru"）

### 6.2 CHI-CHI 架构

```
OpenLLC 顶层结构:
┌───────────────────────────────────────────────┐
│                  OpenLLC                       │
│                                               │
│  io.rn[0]  io.rn[1]  ...    io.sn            │
│    │          │              │                │
│    ▼          ▼              ▼                │
│  ┌─────────────────────┐  ┌──────────┐       │
│  │    MMIO Diverger    │  │ MMIO     │       │
│  └─────────┬───────────┘  │ Merger   │       │
│            ▼               └────┬─────┘       │
│  ┌─────────────────────┐       │              │
│  │   RN Link Monitor   │  ┌────┴─────┐       │
│  └─────────┬───────────┘  │ SN Link  │       │
│            ▼               │ Monitor  │       │
│  ┌─────────────────────┐  └──────────┘       │
│  │     RN Xbar         │                      │
│  └──┬──┬──┬────────────┘                      │
│     │  │  │                                   │
│     ▼  ▼  ▼                                   │
│  ┌────┐┌────┐┌────┐                           │
│  │ S0 ││ S1 ││ S2 │ ... Slices               │
│  └────┘└────┘└────┘                           │
│     │  │  │                                   │
│     ▼  ▼  ▼                                   │
│  ┌─────────────────────┐                      │
│  │     SN Xbar         │                      │
│  └─────────────────────┘                      │
└───────────────────────────────────────────────┘
```

### 6.3 双目录架构

L3 实现了双目录设计：

**Self Directory** (数据目录):
- `SelfMetaEntry`: `valid` + `dirty`
- 记录 L3 自身缓存的数据状态

**Client Directory** (Snoop Filter):
- `ClientMetaEntry`: `valid` (per RN)
- `Vec[ClientMetaEntry]`：每个 way 存储所有 RN 的持有状态
- 用于判断哪些 RN 持有某 cacheline 的副本，避免不必要的 Snoop

```
Client Directory 结构示意:
Set X, Way Y:
┌─────────────────────────────────┐
│ RN0_valid │ RN1_valid │ ...    │
│  (1 bit)  │  (1 bit)  │        │
└─────────────────────────────────┘
```

### 6.4 包含策略

L3 的包含策略根据 RN 数量动态选择：

```scala
inclusion = if (numRNs == 1) "Exclusive" else "Non-inclusive"
```

- 单核系统：Exclusive（不包含 L2 数据）
- 多核系统：Non-inclusive（不强制包含，也不排除）

### 6.5 Hit 判断逻辑

L3 MainPipe 的命中判断综合考虑多个来源：

```
self_hit:     L3 Self Directory 命中
clients_hit:  Client Directory 中有 RN 持有副本
originalRN_hit: 请求来源 RN 持有副本
peerRNs_hit:  其他 RN 持有副本
```

根据命中位置不同，L3 可以：
- 直接返回数据（self_hit）
- 向持有数据的 RN 发送 Snoop 获取数据（clients_hit / peerRNs_hit）
- 向内存请求（miss）

## 7. NoC 拓扑与多核一致性

### 7.1 系统级拓扑

XSNoCTop（定义在 `src/main/scala/top/XSNoCTop.scala`）实现了完整的 NoC 拓扑：

```
┌──────────┐ ┌──────────┐ ┌──────────┐
│  Core 0  │ │  Core 1  │ │  Core N  │
│ + L1 +   │ │ + L1 +   │ │ + L1 +   │
│   L2     │ │   L2     │ │   L2     │
└────┬─────┘ └────┬─────┘ └────┬─────┘
     │ CHI        │ CHI        │ CHI
     │ (via       │ (via       │ (via
     │AsyncBridge)│AsyncBridge)│AsyncBridge)
     ▼            ▼            ▼
  ┌──────────────────────────────────────┐
  │          CHI Async Bridge            │
  │     (Clock Domain Crossing)          │
  └──────────────────┬───────────────────┘
                     │ CHI
                     ▼
  ┌──────────────────────────────────────┐
  │           OpenLLC (L3)               │
  │     ┌───────┐┌───────┐┌───────┐     │
  │     │Slice 0││Slice 1││Slice N│     │
  │     └───────┘└───────┘└───────┘     │
  └──────────────────┬───────────────────┘
                     │ AXI4
                     ▼
  ┌──────────────────────────────────────┐
  │       Memory Controller              │
  └──────────────────────────────────────┘
```

### 7.2 CHI Async Bridge

由于 L2 和 L3 可能运行在不同时钟域，之间通过 CHI Async Bridge 进行跨时钟域转换：

```scala
// SoC.scala
case object EnableCHIAsyncBridge extends Field[Boolean](true)

// XSNoCTop.scala
val chiAsync = l2cache.get.io_chi.get
val chiSink = Module(new CHIAsyncBridgeSink(...))
```

每个 CHI 通道（REQ, RSP, SNP, DAT）都有独立的异步桥接。

### 7.3 RN Xbar 与 SN Xbar

- **RN Xbar** (Request Node Crossbar): 将多个 L2 的 CHI 请求路由到正确的 L3 Slice
- **SN Xbar** (Slave Node Crossbar): 将 L3 Slice 的请求路由到内存端口
- 通过 SAM (System Address Map) 进行地址路由

SAM 实现（定义在 `xscache/chi/NetworkLayer.scala`）：

```scala
class SAM(sam: Seq[(AddressSet, Int)]) {
  def check(x: UInt): Bool = Cat(sam.map(_._1.contains(x))).orR
  def lookup(x: UInt): UInt = {
    ParallelPriorityMux(sam.map(m => (m._1.contains(x), m._2.U)))
  }
}
```

### 7.4 Link Monitor

每个 CHI 连接都有 Link Monitor 模块：
- `RNLinkMonitor`: 监控每个 RN（L2）的 CHI 流量
- `SNLinkMonitor`: 监控 SN（内存）端口的 CHI 流量

这些模块用于性能统计和调试。

### 7.5 MMIO 路由

MMIO (Memory Mapped I/O) 访问通过专用路径路由：

```
L1 MMIO 请求 → L2 SinkA 检测 → L2 MMIO Bridge → 
→ L3 MMIODiverger 分离 → L3 RN Xbar → SN → Memory
```

MMIO 请求不经过缓存一致性协议，通过 CHI NoSnp 操作类型直接访问。

## 8. CHI 协议详解

### 8.1 CHI Flit 类型

XiangShan 支持 CHI Issue B/C/E.b 版本（通过 `CHIIssue` 配置）。主要 Flit 类型：

| Flit 类型 | 方向 | 用途 |
|-----------|------|------|
| CHIREQ    | TX (输出) | 发送请求到 L3 |
| CHIRSP    | TX (输出) | 发送响应到 L3 |
| CHIDAT    | TX (输出) | 发送数据到 L3 |
| CHISNP    | RX (输入) | 接收 Snoop 从 L3 |
| CHIDAT    | RX (输入) | 接收数据从 L3 |
| CHIRSP    | RX (输入) | 接收响应从 L3 |

### 8.2 REQ 操作码

关键 CHI REQ 操作码（定义在 `xscache/chi/Opcode.scala`）：

| 操作码 | 编码 | 用途 |
|--------|------|------|
| ReadShared | 0x01 | 读取共享副本 |
| ReadUnique | 0x07 | 读取独占副本 |
| MakeUnique | 0x0C | 获取独占权限 |
| WriteBackFull | 0x1B | 写回脏数据 |
| WriteEvictFull | 0x15 | 驱逐（不写回） |
| ReadNoSnp | 0x04 | 非 Snoop 读 |

### 8.3 SNP 操作码

| 操作码 | 编码 | 用途 |
|--------|------|------|
| SnpShared | 0x01 | 降级到共享 |
| SnpClean | 0x02 | 降级到 Clean |
| SnpUnique | 0x07 | 要求独占 |
| SnpCleanInvalid | 0x09 | 清除无效 |
| SnpMakeInvalid | 0x0A | 使无效 |
| SnpSharedFwd | 0x11 | 共享转发 |
| SnpCleanFwd | 0x12 | Clean 转发 |
| SnpUniqueFwd | 0x17 | Unique 转发 |

### 8.4 RSP 操作码

| 操作码 | 编码 | 用途 |
|--------|------|------|
| Comp | 0x04 | 完成响应 |
| CompAck | 0x02 | 完成确认 |
| CompDBIDResp | 0x05 | 完成 + DBID |
| DBIDResp | 0x06 | 数据 Buffer ID |
| RetryAck | 0x03 | 重试请求 |
| PCrdGrant | 0x07 | Protocol Credit 授予 |

## 9. 写策略

### 9.1 Write-Back 策略

XiangShan 在所有缓存层级（L1、L2、L3）均采用 **Write-Back** 策略：

- **L1 → L2**: L1 写入时只更新 L1，标记为 Dirty。当 L1 驱逐时，通过 `PutData`/`PutPartialData` 写入 L2。
- **L2 → L3**: L2 写回时通过 CHI `WriteBackFull` 操作发送脏数据到 L3。
- **L3 → Memory**: L3 驱逐脏行时通过 AXI4 写入内存。

### 9.2 Write-Clean 支持

L2 支持 `CBOClean`/`CBOFlush` Cache 管理操作：
- `CBOClean`: 清除脏标记，将数据写回 L3 但 L2 保留副本
- `CBOFlush`: 清除脏标记并写回
- `CBOInval`: 使缓存行无效

### 9.3 L2 的脏数据处理

L2 在以下场景写回脏数据：
1. **驱逐时 WriteBackFull**: 当替换策略选择驱逐 dirty 行时
2. **Snoop 响应**: 当 L3 发来 Snoop 且 L2 持有 dirty 数据时，返回 `SnpRespData` 并在后续发送 `CopyBackWrData`
3. **CMO 操作**: Cache Clean/Flush 操作触发写回

## 10. 关键源文件索引

### L2 Cache (CoupledL2)
| 文件 | 路径 | 功能 |
|------|------|------|
| CoupledL2.scala | `XSCache/src/main/scala/coupledL2/` | L2 顶层模块 |
| L2Param.scala | 同上 | L2 参数定义 |
| Slice.scala | 同上 | 单 bank 处理单元 |
| Directory.scala | 同上 | 3 级流水线目录 |
| MSHR.scala | 同上 | MSHR 状态机 |
| MainPipe.scala | 同上 | 主流水线 |
| Common.scala | 同上 | 公共数据结构 |
| Consts.scala | 同上 | MetaData 状态定义 |
| RequestArb.scala | 同上 | 请求仲裁 |
| GrantBuffer.scala | 同上 | Grant 缓冲 |
| DataStorage.scala | 同上 | 数据存储 |
| SinkA.scala / SinkC.scala | 同上 | TileLink 接收 |
| TXREQ.scala / TXRSP.scala / TXDAT.scala | 同上 | CHI 发送通道 |
| RXSNP.scala / RXDAT.scala / RXRSP.scala | 同上 | CHI 接收通道 |

### L3 Cache (OpenLLC)
| 文件 | 路径 | 功能 |
|------|------|------|
| OpenLLC.scala | `XSCache/src/main/scala/openLLC/` | L3 顶层模块 |
| Directory.scala | 同上 | 双目录（Self + Snoop Filter） |
| MainPipe.scala | 同上 | 主流水线 (Stage 2-6) |
| Slice.scala | 同上 | L3 bank 处理单元 |
| LLCParam.scala | 同上 | L3 参数定义 |

### CHI 协议层
| 文件 | 路径 | 功能 |
|------|------|------|
| Message.scala | `XSCache/src/main/scala/xscache/chi/` | CHI 消息定义、一致性状态 |
| Opcode.scala | 同上 | CHI 操作码定义 |
| NetworkLayer.scala | 同上 | SAM 地址路由 |

### SoC 集成
| 文件 | 路径 | 功能 |
|------|------|------|
| L2Top.scala | `src/main/scala/xiangshan/` | L2 顶层集成 |
| SoC.scala | `src/main/scala/system/` | SoC 参数与结构 |
| XSNoCTop.scala | `src/main/scala/top/` | NoC 顶层模块 |
| XSTile.scala | `src/main/scala/xiangshan/` | 单核 Tile 定义 |

## 11. 总结

XiangShan 的 Cache Coherence & NoC 子系统体现了现代高性能多核处理器的典型设计：

1. **协议分层**: L1-L2 使用轻量级 TileLink 协议，L2-L3 使用功能更丰富的 ARM CHI 协议，实现了性能与功能的平衡。

2. **目录式一致性**: 采用目录（Directory）而非监听（Snooping）的一致性协议，通过 Snoop Filter 减少不必要的 Snoop 流量。

3. **Banked 设计**: L2 和 L3 均采用多 bank 设计，通过 Crossbar 进行路由，支持并行访问。

4. **MSHR 流水线**: MSHR 状态机设计精细，支持 nested writeback、retry/backoff 机制、deadlock 检测等高级特性。

5. **DCT 优化**: 支持 Direct Cache Transfer，允许 L2 之间直接传输数据，减少 L3 访问延迟。

6. **跨时钟域设计**: 通过 CHI Async Bridge 支持 L2 和 L3 运行在不同时钟域。