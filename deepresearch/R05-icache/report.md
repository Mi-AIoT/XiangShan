# XiangShan ICache & Instruction Fetch 深度研究报告

## 1. 概述

XiangShan 的 ICache（Instruction Cache）是前端取指单元的核心部件，负责为处理器提供快速的指令访问。ICache 位于处理器前端（Frontend）流水线中，上接 FTQ（Fetch Target Queue）发出的取指请求，下连 IFU（Instruction Fetch Unit）将取到的指令传递到后端执行。ICache 通过 TileLink 总线协议与 L2 Cache 进行数据交互，处理 cache miss 时的 refill 操作。

XiangShan 的 ICache 实现采用了现代化的设计理念，具有以下主要特点：
- **双端口（Dual-Port）**取指支持：通过 `PortNumber = 2` 支持同一周期内对两条相邻 cache line 的并发访问
- **三流水线结构**：MainPipe（主取指流水线）、PrefetchPipe（预取流水线）、WayLookup（地址转换与路预测结果缓冲）
- **MSHR（Miss Status Holding Register）**机制：支持多个未完成的 miss 请求
- **Fetch Directed Instruction Prefetching (FDIP)**：基于论文 [Reinman et al., MICRO 1999] 的指令预取技术
- **可配置的 ECC 保护**：支持 parity、SEC、SECDED 等多种错误检测/纠正方案

## 2. Cache 组织架构与参数

### 2.1 基本参数

```
ICache 容量 = nSets × nWays × blockBytes
           = 256 × 4 × 64B
           = 64 KB
```

| 参数 | 值 | 说明 |
|------|-----|------|
| `nSets` | 256 | 组数 |
| `nWays` | 4 | 路数（4-way set-associative） |
| `blockBytes` | 64 | Cache line 大小（64B = 512b） |
| `rowBits` | 64 | 每个 bank 的数据宽度（8B） |
| `PortNumber` | 2 | 双端口取指 |
| `idxBits` | 8 | 组索引位宽（log2(256)） |
| `blockOffBits` | 6 | Cache line 内偏移位宽（log2(64)） |
| `tagBits` | 由物理地址宽度决定 | 物理地址 tag 位宽 |
| `instBytes` | 4 | 指令字节宽度（RV64） |

### 2.2 地址字段划分

XiangShan 使用**物理索引物理标签（PIPT）**的 ICache 组织方式。虚拟地址和物理地址的字段划分如下：

```
虚拟地址 (VAddrBits):
┌────────────────────────────────────────────┐
│ virtual tag  │  blockOffset  │  rowOffset  │
│  (unused)    │  (6 bits)     │  (3 bits)   │
│  vtagBits    │  blockOffBits │  rowOffBits │
└────────────────────────────────────────────┘

虚拟地址字段:
┌───────────────────┬────────────┬──────────┬──────────┐
│  virtual tag      │  setIdx    │ blockOff │ rowOff   │
│  vtagBits         │  idxBits   │          │          │
└───────────────────┴────────────┴──────────┴──────────┘
  ↑ idxBits = 8, blockOffBits = 6
  ↑ untagBits = idxBits + blockOffBits = 14

物理地址字段:
┌───────────────────┬─────────────────────────────────┐
│  physical tag     │          index + offset          │
│  tagBits          │         untagBits (14 bits)      │
└───────────────────┴─────────────────────────────────┘
```

由于 ICache 的 `untagBits`（14）与页内偏移（12 位）的关系，当 `untagBits > pgIdxBits` 时存在 alias 问题。默认配置下 `untagBits = 14 > 12 = pgIdxBits`，因此 `AliasTagBits = 14 - 12 = 2 bits`。这 2 位 alias tag 通过 TileLink 总线传递给 L2 Cache，用于解决虚拟地址 aliasing 问题。

代码注释指出：ICache aliasing 可能并非功能性问题，因为 ICache 的数据从不被标记为 dirty，且当前使用软件 fence.i 保证一致性。但为了与 DCache 保持一致，仍然实现了 alias tag 机制。

### 2.3 Data Bank 组织

每条 64B 的 cache line 被划分为 **8 个 data bank**（`DataBanks = blockBits / rowBits = 512 / 64 = 8`），每个 bank 存储 8 字节（64 位）的数据。

每个 data bank 的 SRAM 条目宽度（`DataSramWidth`）包含三部分：
```
DataSramWidth = ICacheDataBits + DataEccBits + DataPaddingBits
              = 64 + DataEccBits + 1
```
其中 `DataPaddingBits = 1`，用于物理设计优化（相比直接使用 65 位更有利于 SRAM 面积）。

### 2.4 Meta Bank 组织（Interleaved Banking）

ICache 的 Meta Array 采用**交错 bank（Interleaved Banking）**设计，`NumInterleavedBank = 2`。这意味着相邻的组（set）分布在不同的 bank 中：

```
Meta Array - 2 Interleaved Banks:
┌─────────────────┐  ┌─────────────────┐
│    Bank 0        │  │    Bank 1        │
│  (even sets)     │  │  (odd sets)      │
│  set 0, 2, 4, ...│  │  set 1, 3, 5, ...│
│  128 sets/way    │  │  128 sets/way    │
└─────────────────┘  └─────────────────┘
```

交错 bank 的关键约束：
- `NumInterleavedBank` 必须是 2 的幂
- `NumInterleavedBank <= nSets`
- `NumInterleavedBank >= PortNumber`（至少为 2，以支持双端口并发访问）

这种设计确保了当双端口同时请求两条相邻 cache line 时，它们的 meta 信息分布在不同的 bank 中，可以同时被读取，避免 bank conflict。

Meta SRAM 使用 `SplittedSRAMTemplate`，支持通过 `MetaWaySplit`（默认 2）和 `MetaDataSplit`（默认 1）参数进行路分割和数据分割（PPA 优化）。

### 2.5 Meta 与 Data SRAM Entry 结构

**Meta Entry（`ICacheMetaEntry`）**:
```
ICacheMetaEntry {
  meta: ICacheMetadata {
    phyTag:      UInt(tagBits.W)    // 物理 tag
    maybeRvcMap: UInt(16.W)         // RVC 指令映射（当支持 C 扩展时）
  }
  code: UInt(MetaEccBits.W)         // ECC/parity 校验位
}
```

**Data Entry（`ICacheDataEntry`）**:
```
ICacheDataEntry {
  data:    UInt(ICacheDataBits.W)   // 64 位数据
  code:    UInt(DataEccBits.W)      // ECC/parity 校验位
  padding: UInt(DataPaddingBits.W)  // 1 位填充
}
```

`maybeRvcMap` 记录了 cache line 中每个 2 字节位置是否可能是 RVC（压缩）指令（即低 2 位不全为 `11`），这对于后端指令解码的正确性至关重要。

## 3. ICache 流水线架构

XiangShan ICache 的整体架构由以下核心模块组成：

```
                        ┌─────────────────────────────────────────────────────┐
                        │                    ICache Top                       │
                        │                                                     │
  FTQ ──fetchReq──>     │   ┌──────────────┐     ┌──────────────────┐        │
  FTQ <──toPrefetch     │   │  PrefetchPipe │     │    MainPipe       │   ──> IFU
  FTQ <──fromPrefetch   │   │  (3-stage)    │     │    (2-stage)      │   <── IFU (stall)
                        │   └───────┬──────┘     └────────┬─────────┘        │
                        │           │                      │                   │
                        │           ▼                      ▼                   │
                        │   ┌──────────────┐     ┌──────────────────┐        │
  iTLB <─────────────   │   │  WayLookup   │ <── │  DataArray        │        │
  PMP(1) <────────────  │   │  (32-entry   │     │  (8 banks x 4way) │        │
  PMP(0) <────────────  │   │   circular   │     └──────────────────┘        │
                        │   │   queue)     │     ┌──────────────────┐        │
                        │   └──────────────┘     │  MetaArray        │        │
                        │           │             │  (2 interleaved   │        │
                        │           ▼             │   banks)          │        │
                        │   ┌──────────────┐     └──────────────────┘        │
                        │   │  MissUnit     │                                  │
                        │   │  (14 MSHRs)  │ ──── TileLink Acquire ──> L2    │
                        │   │              │ <── TileLink Grant ────── L2    │
                        │   └──────────────┘                                  │
                        │           │                                          │
                        │           ▼                                          │
                        │   ┌──────────────┐                                  │
                        │   │  Replacer     │                                  │
                        │   │  (setPLRU)   │                                  │
                        │   └──────────────┘                                  │
                        │           │                                          │
                        │   ┌──────────────┐                                  │
                        │   │  CtrlUnit     │ (ECC 控制/注入)                   │
                        │   └──────────────┘                                  │
                        └─────────────────────────────────────────────────────┘
```

### 3.1 PrefetchPipe（预取流水线，3 级）

PrefetchPipe 是 ICache 中的关键模块，负责**地址转换（TLB lookup）**、**meta 读取与 hit 判断**、以及**向 WayLookup 写入预取结果**。它是一个 3 级流水线（s0 -> s1 -> s2），其设计灵感来源于 Fetch Directed Instruction Prefetching（FDIP）技术。

**Stage 0 (s0)**:
- 接收来自 FTQ 的预取请求（`fromFtq`）或后端软件预取请求（`softPrefetchReq`）
- 软件预取优先级高于硬件预取
- 发送请求到 iTLB 和 Meta SRAM

**Stage 1 (s1)**:
- 接收 iTLB 响应，获取物理地址
- 接收 Meta SRAM 读响应，进行 tag 比较
- 处理 iTLB miss 的重试（通过内部状态机 `S1FsmState`）
- 状态机包含 5 个状态：`Idle`, `ItlbResend`, `MetaResend`, `EnqWay`, `EnterS2`
- 监控 MissUnit 的写入，确保 WayLookup 中的数据是最新的
- 将 WayMask、physical tag、TLB 结果等写入 WayLookup 缓冲区

**Stage 2 (s2)**:
- 进一步监控 MissUnit 的 SRAM 写入
- 对于 cache miss 的请求，向 MissUnit 发送 prefetch miss 请求
- 该阶段受 `csrPfEnable` 控制，可由软件禁用

PrefetchPipe 通过 `metaRead` 端口读取 MetaArray（与 MissUnit 的 `metaWrite` 互斥），并通过 `missReq` 端口向 MissUnit 发送未命中的预取请求。

### 3.2 WayLookup 缓冲区

WayLookup 是一个 32 深度的 circular queue（`WayLookupSize = 32`），用于在 PrefetchPipe 和 MainPipe 之间**解耦地址转换结果与取指流水线**。

**核心功能**：
- **写入**：PrefetchPipe 完成地址转换和 meta 检查后，将结果写入 WayLookup
- **读取**：MainPipe 在 stage 0 从 WayLookup 读取 waymask、pTag、TLB 异常等信息
- **更新**：当 MissUnit 返回 refill 数据时，通过 `update` 端口更新 WayLookup 中匹配的条目（`ICacheMissUpdateHelper.updateMetaInfo`）
- **Bypass**：当 WayLookup 为空但有有效写入时，可直接 bypass 到读端口，减少延迟
- **Flush**：支持全局 flush 和 BPU stage3 redirect flush

WayLookup 的条目结构：
```
WayLookupEntry {
  vSetIdx:     Vec[UInt]    // 两个端口的虚拟 set 索引
  waymask:     Vec[UInt]    // 两个端口的 way mask
  maybeRvcMap: Vec[UInt]    // RVC 映射
  metaCodes:   Vec[UInt]    // meta ECC 校验码
  pTag:        UInt         // 物理 tag
  itlbPbmt:    UInt         // ITLB PBMT（Page-Based Memory Type）
}
```

WayLookup 的 **update 机制**（`ICacheMissUpdateHelper.updateMetaInfo`）是确保数据一致性的关键：
```scala
def updateMetaInfo(update, vSetIdx, pTag, info): (Bool, MetaInfo) = {
  when(valid && vSetSame) {
    when(pTagSame) {
      // vSetIdx & pTag 匹配 => update 拥有更新的数据
      newInfo.waymask := update.bits.waymask
      newInfo.maybeRvcMap := update.bits.maybeRvcMap
      newInfo.metaCodes := encodeMetaEccByPort(...)
    }.elsewhen(waySame) {
      // vSetIdx & way 匹配但 pTag 不匹配 => 旧数据已被替换，视为 miss
      newInfo.waymask := 0.U
    }
  }
}
```

### 3.3 MainPipe（主取指流水线，2 级）

MainPipe 是从 FTQ 接收取指请求并返回指令数据给 IFU 的关键流水线。

**Stage 0 (s0)**:
- 接收 FTQ 的 `FtqFetchRequest`
- 从 WayLookup 读取 waymask、pTag、TLB 信息
- 发起 Data SRAM 读请求
- 判断是否需要 double-line 访问（跨越 cache line 边界）

**Stage 1 (s1)**:
- 接收 Data SRAM 读响应
- 监控 MissUnit 的 refill 响应（`fromMiss`）
- 通过 `DataHoldBypass` 选择正确的数据来源（SRAM 直读 vs MSHR refill）
- 进行 ECC 校验（meta ECC 和 data ECC）
- 进行 PMP（Physical Memory Protection）检查
- 合并 iTLB 异常、PMP 异常、L2 TileLink 错误和 ECC 错误
- 对于 cache miss 的请求，向 MissUnit 发送 miss 请求
- 将结果发送给 IFU（`toIfu`）

MainPipe 的双数据源选择机制：
```
s1_hits(i) = s1_mshrValid(i) || s1_sramHits(i)

s1_datas(bank) = Mux(s1_bankMshrValid(bank),
                     s1_mshrDatas(bank),   // 来自 MissUnit 的 refill 数据
                     s1_sramDatas(bank))   // 来自 SRAM 的直读数据
```

这种设计使得 MainPipe 可以在同一个周期内同时从 SRAM 和 MissUnit 获取数据，无需 stall。`DataHoldBypass` 确保无论数据来源何时就绪，都能正确传递给下游。

### 3.4 两级流水线的时序配合

PrefetchPipe 和 MainPipe 通过 WayLookup 解耦：
- PrefetchPipe 负责地址转换（TLB）和 meta 检查，将结果存入 WayLookup
- MainPipe 从 WayLookup 获取预取好的地址信息，直接进行 data SRAM 读取

这种流水线划分将时序关键的 TLB lookup 和 SRAM 读取分离到不同的周期，降低了单周期的关键路径延迟。

## 4. Miss Handling 机制

### 4.1 MissUnit 架构

MissUnit 是 ICache 中处理 cache miss 的核心模块，管理所有未完成的 miss 请求。

```
                    ┌───────────────────────────────────────────────────┐
                    │              ICacheMissUnit                        │
  fetchReq ───>    │                                                    │
  (from MainPipe)  │   ┌─────────┐  ┌──────────────────────┐           │
                    │   │ Demux   │  │  4 x Fetch MSHR      │           │
  prefetchReq ──>  │   │ (fetch) │  │  (low idx = high pri)│──> acquireArb
  (from Prefetch   │   └─────────┘  └──────────────────────┘       │    │
   Pipe)           │                                          │    │    │
                    │   ┌─────────┐  ┌──────────────────────┐   │    │    │
                    │   │ Demux   │  │  10 x Prefetch MSHR  │   │    │    │
                    │   │(prefet) │  │  (FIFO order pri)    │──>─┘    │    │
                    │   └─────────┘  └──────────────────────┘        │    │
                    │                                    │            │    │
                    │   ┌──────────────────────────┐    │            │    │
                    │   │  Priority FIFO            │    │            │    │
                    │   │  (prefetch order record)  │    │            │    │
                    │   └──────────────────────────┘    │            │    │
                    │                                    ▼            │    │
                    │                              ┌───────────┐      │    │
                    │                              │  Arbiter   │──────┘    │
                    │                              │  (fetch >  │           │
                    │                              │   prefetch)│           │
                    │                              └─────┬─────┘           │
                    │                                    │                 │
                    │                          TileLink Acquire ──> L2     │
                    │                          TileLink Grant  <── L2     │
                    │                                    │                 │
                    │                              write Meta/Data SRAM    │
                    │                                    │                 │
                    │                              resp ──> MainPipe       │
                    │                                    ──> PrefetchPipe  │
                    │                                    ──> WayLookup     │
                    └───────────────────────────────────────────────────┘
```

### 4.2 MSHR 设计

ICache 共有 **14 个 MSHR**（`NumAllMshr = NumFetchMshr + NumPrefetchMshr = 4 + 10`），分为两类：

**Fetch MSHR（4 个）**：
- 处理来自 MainPipe 的 miss 请求
- 低索引的 MSHR 具有更高优先级（通过 priority encoder 实现）
- 不受 `flush` 信号影响（一旦发起，即使 flush 也会完成）
- 通过 `fencei` 信号可以标记为无效（但仍在进行中的请求会完成）

**Prefetch MSHR（10 个）**：
- 处理来自 PrefetchPipe 的 prefetch miss 请求
- 通过 FIFO 顺序确定优先级（先进入的 MSHR 先发送 Acquire）
- 受 `flush` 信号影响（redirect 时可以取消）
- 具有更低的 TileLink 总线优先级

**MSHR 状态**（每个 `ICacheMshr`）：
```
ICacheMshr 状态机:
  ┌──────────┐     req.fire     ┌──────────┐   acquire.fire   ┌──────────┐
  │  IDLE    │ ──────────────>  │ VALID    │ ──────────────>  │ ISSUED   │
  │ (free)   │                   │ (pending) │                  │ (waiting │
  └──────────┘                   └──────────┘                  │  for L2) │
       ^                             │                         └────┬─────┘
       │                             │ invalid                      │
       │                             ▼                              │
       │                      ┌──────────┐                         │
       └──────────────────────│ INVALID  │ <───────────────────────┘
                              │ (done)   │    invalid (from MissUnit)
                              └──────────┘
```

每个 MSHR 的核心状态寄存器：
- `valid`：是否有效
- `flush`：是否被 flush（标记为不响应）
- `fencei`：是否被 fence.i 无效
- `issue`：是否已发送 Acquire 请求

### 4.3 MSHR 去重机制

MissUnit 通过以下机制避免重复请求：

1. **MSHR 查找**：每个新请求会并行查找所有 14 个 MSHR，检查是否存在相同的 `blkPAddr` 和 `vSetIdx`
2. **Prefetch 与 Fetch 交叉去重**：`prefetchHitFetchReq` 检测 prefetch 请求是否与正在进行的 fetch 请求匹配
3. **命中处理**：如果已在 MSHR 中存在（`fetchHit` 或 `prefetchHit` 为 true），请求被直接吸收（不占用 MSHR 资源）

```scala
fetchHit    = allMshr.map(_.io.lookUps(0).resp.hit).reduce(_ || _)
prefetchHit = allMshr.map(_.io.lookUps(1).resp.hit).reduce(_ || _) || prefetchHitFetchReq
```

### 4.4 TileLink 交互（Refill 机制）

MissUnit 通过 TileLink **Get** 事务从 L2 Cache 获取缺失的 cache line：

**Acquire（请求）**：
```
TileLink Get Request:
  - source: MSHR ID (0 ~ NumAllMshr-1)
  - address: Cat(blkPAddr, 0.U(blockOffBits.W))
  - lgSize: log2Up(blockBytes) (即 log2(64) = 6)
  - user[ReqSourceKey]: MemReqSource.CPUInst  // 标识为指令请求
  - user[MemBackTypeMM]: true                  // 标识为主存请求
  - user[AliasKey]: aliasTag                    // 解决 aliasing（可选）
```

**Grant（响应）**：
- MissUnit 通过 `readBeatCnt` 逐 beat 接收 64B 的 refill 数据
- 每个 beat 为 `beatBits` 宽度（通常 64 位）
- 当 `lastFire`（最后一个 beat 且有效）时，refill 完成
- 数据存储在 `respDataReg` 中（`Vec(refillCycles, UInt(beatBits.W))`）

**写入 SRAM**：
- 当 `writeSramValid`（即 refill 完成且无 corrupt/flush/fencei）时，同时写入 MetaArray 和 DataArray
- WayMask 从 Replacer 获取的 victim way 决定
- 写入完成后通过 `resp` 端口通知 MainPipe/PrefetchPipe 和 WayLookup

**Corrupt/Denied 处理**：
- `corruptReg` 和 `deniedReg` 在任何 beat 出现 corrupt/denied 时被设置
- `writeSramValid` 会排除 corrupt 情况（不写入 SRAM）
- 对于 MainPipe，corrupt 数据仍会返回（`s1_tlCorrupt`），触发 Hardware Error Exception
- 对于 PrefetchPipe，corrupt 数据不会触发 miss 请求（设置 `s2_mshrHits` 为 false），会重新预取

### 4.5 Prefetch MSHR 优先级

Prefetch MSHR 使用 **FIFO 优先级**机制（而非固定优先级），通过 `FIFOReg` 实现：

```
prefetchDemux 分配 MSHR
     │
     ▼
priorityFIFO 记录分配顺序
     │
     ▼
MuxBundle (prefetchArb) 根据 FIFO dequeue 选择最高优先级 MSHR
     │
     ▼
acquireArbiter 将 fetch MSHR(4) 和 prefetch MSHR(1) 进行仲裁
     │
     ▼
发送到 TileLink 总线
```

当 FIFO 满时，enq 和 deq 不会同时发生，因此深度设置为 `NumPrefetchMshr`（10）即可保证正确性。

## 5. 替换策略（Replacement Policy）

ICache 使用可配置的替换策略，默认为 **set-PLRU**（Set-Protected LRU）：

```scala
Replacer: String = "setplru"  // 可选: "random", "setlru", "setplru"
```

**ICacheReplacer** 模块管理替换决策：
- 实例化 `PortNumber`（2 个）独立的替换器，每个管理 `nSets / PortNumber` 组
- `touch` 端口：MainPipe 在 cache hit 时通知替换器更新访问状态
- `victim` 端口：MissUnit 在 miss 时查询替换器获取 victim way

替换器的 set 索引使用交错 bank 索引映射：
```
touchSets(i)(0) = Mux(vSetIdx(0), vSetIdx(idxBits-1, 1), vSetIdx(idxBits-1, 1))
```
即使用 `vSetIdx(idxBits-1, 1)`（去掉最低位）作为替换器的 set 索引，因为最低位用于交错 bank 选择。

当 MissUnit 发出 Acquire 请求时（`acquireArb.io.out.fire`），替换器立即返回 victim way。在下一周期，该 victim way 被 touch（标记为最近使用）。

## 6. ECC 保护机制

### 6.1 配置选项

```scala
MetaEcc: String = "parity"   // 可选: "none", "identity", "parity", "sec", "secded"
DataEcc: String = "parity"   // 同上
```

默认使用 **parity** 校验，每条 meta entry 增加 1 位奇偶校验位，每个 data bank 根据 `DataEccUnit`（默认 `blockBytes = 64` 位）计算校验码。

### 6.2 ECC 编码与检查

**Meta ECC**（`ICacheEccHelper` trait）：
```scala
encodeMetaEccByPort(meta, poison): UInt
  // 对 ICacheMetadata (phyTag + maybeRvcMap) 进行编码
  // poison 用于 ECC 注入测试

checkMetaEccByPort(meta, code, waymask, enable): Bool
  // 重新编码 meta 并与存储的 code 比较
  // corrupt = (重新编码结果 != 存储 code) && (hitNum == 1)
  // multiHit = (hitNum > 1)  // 多路命中必然是 ECC 错误
  // result = enable && (corrupt || multiHit)
```

**Data ECC**（每个 bank 独立）：
```scala
encodeDataEccByBank(data, poison): UInt
  // 将 64 位数据按 DataEccSegments 分段编码

checkDataEccByBank(data, code, enable): Bool
  // 重新编码并比较
```

### 6.3 ECC 错误处理

- **Meta ECC 错误**：当检测到 meta corruption 时，MainPipe 可以触发 meta flush（如果 `EnableCorruptRefetch` 启用），准备从 L2 重新获取数据
- **Data ECC 错误**：同上，触发 data corruption 检测
- **TileLink 错误**：`corrupt` 和 `denied` 信号表示 L2 返回的数据有误，同样触发错误
- 所有错误通过 `io.error` 端口报告给 BEU（Bus Error Unit）

`EnableCorruptRefetch` 当前默认为 `false`（由于 timing issue，即 dataArray -> parity check -> response valid 路径过长），因此 ECC 错误会直接触发 Hardware Error Exception，而非自动重新获取。

### 6.4 ECC 控制单元（CtrlUnit）

`ICacheCtrlUnit` 是一个可选模块（`EnableCtrlUnit = true`），通过 TileLink MMIO 寄存器接口提供 ECC 控制功能。基地址为 `0x38022080`。

**寄存器映射**：
- `eccctrl`（偏移 0x00）：ECC 使能控制 + 错误注入控制
- `ecciaddr`（偏移 0x08）：错误注入的物理地址

**错误注入 FSM**：
```
Idle -> ReadMetaReq -> ReadMetaResp -> WriteMeta / WriteData -> Idle
```

注入过程：
1. 读取指定物理地址对应的 meta（获取 waymask）
2. 如果地址在 ICache 中不存在，拒绝注入并设置 `iError = NotFound`
3. 根据 `iTarget`（MetaArray 或 DataArray）写入 poison 数据
4. 使用 `poison = true.B` 使 ECC 编码生成错误的校验位

## 7. ICache Prefetch 机制

### 7.1 硬件预取（FDIP）

XiangShan ICache 实现了 **Fetch Directed Instruction Prefetching (FDIP)**，这是基于论文 [Reinman, Calder, Austin, MICRO 1999] 的经典技术。

**触发方式**：
- FTQ 根据分支预测结果向 PrefetchPipe 发送预取请求
- PrefetchPipe 在 stage 2 检测到 cache miss 时，向 MissUnit 发送 prefetch miss 请求
- PrefetchPipe 通过 `toFtq` 端口将预取结果（`wayMask`、`isMmio`）反馈给 FTQ

**请求来源**：
1. **FTQ 硬件预取**：FTQ 根据 BPU 预测的分支目标提前预取下一条 cache line
2. **后端软件预取**（`softPrefetchReq`）：后端的 `prefetch.i` 指令触发，优先级高于硬件预取

**软件预取处理**：
```scala
when(io.softPrefetchReq.map(_.valid).reduce(_ || _)) {
  softPrefetchValid := true.B
  softPrefetch.fromSoftPrefetch(MuxCase(...))  // 选择第一个有效的软件预取请求
}
// 软件预取优先
prefetcher.io.fromFtq.bits.req(0) := Mux(softPrefetchValid, softPrefetch, io.fromFtq.toPrefetch.bits.req(0))
```

### 7.2 预取限制

- `csrPfEnable` 信号可以由软件（CSR）禁用整个预取功能
- 软件预取不会写入 WayLookup（`!s1_isSoftPrefetch`），因为 soft prefetch 不影响控制流
- 软件预取仅预取单条 cache line（`isCrossLine = false.B`）
- 当已有 pending 的 softPrefetch 时，新的请求会被覆盖（注释标注为 FIXME，建议实现 softPrefetchQueue）

## 8. 一致性协议与 L2 交互

### 8.1 TileLink 协议

ICache 作为 TileLink master 与 L2 Cache 交互，使用 **Get** 事务获取缺失的 cache line：

```
ICache ──── TLBundleA (Get) ────> L2 Cache
ICache <── TLBundleD (Grant) ──── L2 Cache
```

**Client Parameters**：
```scala
TLMasterPortParameters.v1(
  name = "icache",
  sourceId = IdRange(0, NumFetchMshr + NumPrefetchMshr + 1)  // 0 ~ 14
)
```

**Request Fields**：
- `ReqSourceField`：区分 ICache 和 DCache 的请求（`MemReqSource.CPUInst`）
- `MemBackTypeMM`：标识为主存类型请求（ICache 总是请求主存）
- `AliasField`（可选）：解决 aliasing 问题的 alias tag（当 `AliasTagBits` 非 None 时存在）

### 8.2 一致性模型

ICache 采用**软一致性**模型：
- ICache 数据**从不被标记为 dirty**（所有写入来自 L2 refill）
- 通过 **fence.i 指令**保证一致性（`io.fencei` 信号触发 `flushAll`，清除所有 valid bits）
- 在当前实现中，不支持硬件一致性协议（coherence protocol）的 probe/invalidate 操作
- 代码注释指出：未来即使支持硬件一致性，也可以通过 flush 所有 aliasing set 来简单实现

### 8.3 ICache 与 DCache 的差异

与 DCache 不同，ICache 不需要：
- 处理 store 操作（所有数据来自 L2 refill）
- 实现 MESI/MOESI 等复杂的 coherence 协议
- 处理 write-back 操作
- 维护 dirty bit

这大大简化了 ICache 的设计，但也意味着一致性依赖于软件（fence.i）。

## 9. 双端口取指机制

XiangShan ICache 支持每个周期处理两个取指端口（`PortNumber = 2`），支持**跨 cache line 访问**：

```
双端口取指场景:

场景 1: 同一 cache line 内
┌────────────────────────────────────────────┐
│  Cache Line 0                              │
│  [startVAddr ──────> takenCfiOffset]       │
│           Port 0 & Port 1 使用同一 line     │
└────────────────────────────────────────────┘

场景 2: 跨 cache line
┌────────────────────────────────────────────┐
│  Cache Line 0        │  Cache Line 1       │
│  [startVAddr ──────> │ ──> nextCachelineVA]│
│       Port 0         │       Port 1        │
└────────────────────────────────────────────┘
```

`doubleline` 信号标识是否需要访问两条 cache line。当 `startVAddr` 的 block offset 加上 `takenCfiOffset` 超过 cache line 边界时，`doubleline = true`，需要同时从两条 cache line 获取数据。

**Data Bank 选择逻辑**（`ICacheDataHelper`）：
- `getBankSel(blkOffset, blkEndOffset, crossLine)` 返回每个端口需要的 bank 选择
- `getLineSel(blkOffset)` 确定哪些 bank 来自第一条/第二条 cache line
- Bank 选择确保只读取需要的数据，避免不必要的 SRAM 访问（省电设计）

**DataBank 读使能优化**：
```scala
// 每个 way 独立的 SRAM，通过 waymask 控制读使能
ways.zipWithIndex.foreach { case (w, i) =>
  w.io.r.req.valid := io.read.req.valid && io.read.req.bits.waymask(i)
}
```
每个 way 有独立的 SRAM，通过 `waymask` 选择性地激活，避免所有 way 同时读取，显著降低功耗。

## 10. Flush 与异常处理

### 10.1 Flush 机制

ICache 支持多种 flush 操作：

1. **fence.i**（`io.fencei`）：
   - 触发 MetaArray 的 `flushAll`，清除所有 valid bits
   - 影响所有 MSHR（`fencei` 信号）
   - 清空 PrefetchPipe 状态

2. **Redirect Flush**（`io.fromFtq.redirectFlush`）：
   - 来自 BPU 的分支预测纠正
   - 影响 MainPipe、PrefetchPipe、WayLookup 和 Prefetch MSHR

3. **BPU Stage3 Flush**（`io.fromFtq.flushFromBpu`）：
   - 当 BPU 在 stage3 发现预测错误时的延迟 flush
   - 通过 `shouldFlushByStage3(ftqIdx, valid)` 判断是否需要 flush
   - WayLookup 需要特殊处理（回退 writePtr，因为 bp3 == pf2，WayLookup 在 pf1 写入）

4. **单条 Meta Flush**（`mainPipe.io.metaFlush`）：
   - 当 MainPipe 检测到 ECC 错误时，flush 对应的 meta entry
   - 为 `EnableCorruptRefetch` 场景准备

### 10.2 异常处理

MainPipe 检测并合并多种异常源：

```
异常优先级: iTLB > PMP > L2 TileLink > ECC

s1_exception    = s1_itlbException || s1_pmpException
s1_tlException  = L2 corrupt / denied
s1_eccException = meta / data ECC 错误 (当 EnableCorruptRefetch=false 时)

s1_exceptionOut = s1_exception || s1_tlException || s1_eccException
```

特殊异常类型：
- **Guest Page Fault**：`gpAddr` 和 `isForVSnonLeafPTE` 用于处理 H 扩展下的嵌套页表异常
- **MMIO**：`pmpMmio || Pbmt.isUncache(itlbPbmt)` 时跳过 ICache 访问
- **Backend Exception**：后端传递的 64 位 vaddr 溢出检查异常（`isBackendException`）

## 11. 性能监控

ICache 内置丰富的性能计数器：

### MainPipe 性能指标
- `ftq2icache_fire`：FTQ 到 ICache 的请求频率直方图
- `stallCycles_fetch_icacheMain`：MainPipe 总 stall 周期
- `stallCycles_fetch_icacheMain_prefetch`：WayLookup 未就绪导致的 stall
- `stallCycles_fetch_icacheMain_dataArray`：DataArray 未就绪导致的 stall
- `stallCycles_fetch_icacheMain_missUnit`：等待 MissUnit refill 导致的 stall
- `data_corrupt_0/1`、`meta_corrupt_0/1`：ECC 错误计数

### PrefetchPipe 性能指标
- `stallCycles_fetch_icachePrefetch_missUnit`：MissUnit 未就绪导致的 prefetch stall
- `stallCycles_fetch_icachePrefetch_metaArray`：MetaArray 未就绪导致的 stall
- `stallCycles_fetch_icachePrefetch_itlbNotReady`：ITLB 未就绪导致的 stall
- `stallCycles_fetch_icachePrefetch_itlbMiss`：ITLB miss 导致的 stall
- `stallCycles_fetch_icachePrefetch_metaResend`：meta 重发导致的 stall
- `hwReq`/`swReq`：硬件/软件预取请求计数
- `hwMiss`/`swMiss`：实际发送给 MissUnit 的预取请求计数

### MissUnit 性能指标
- `enqFetchReq`/`enqPrefetchReq`：入队的 fetch/prefetch 请求计数
- `duplicateFetchReq`/`duplicatePrefetchReq`：重复请求计数（已在 MSHR 中）
- `prefetchHitFetchReq`：prefetch 命中正在进行的 fetch 请求
- `fetchMshrEmptyCnt`/`prefetchMshrEmptyCnt`：MSHR 使用率直方图

### WayLookup 性能指标
- `occupiedEntryCnt`：WayLookup 占用率直方图
- `emptyHasBypass`/`emptyNoBypass`：空状态 bypass 计数
- `waitingForExceptionRead`/`waitingForExceptionFlush`：异常等待周期

### Top-Down 性能分析
```
io.toIfu.topdown.iCacheMissBubble  = mainPipe.pendingMiss
  // MainPipe 正在处理 miss，导致 IFU 流水线出现 bubble

io.toIfu.topdown.itlbMissBubble    = prefetcher.pendingItlbMiss && wayLookup.empty
  // PrefetchPipe 在处理 ITLB miss 且 WayLookup 为空，导致 MainPipe 无数据可用
```

## 12. 关键源文件索引

| 文件路径 | 行数 | 功能说明 |
|----------|------|---------|
| `icache/Parameters.scala` | 183 | ICache 参数定义（sets, ways, ECC, MSHR 等） |
| `icache/ICache.scala` | 66 | ICache LazyModule 顶层，TileLink 节点定义 |
| `icache/ICacheImp.scala` | 268 | ICache 实现顶层，模块互联与集成 |
| `icache/ICacheMainPipe.scala` | 513 | 主取指流水线（2-stage），hit/miss 判定，ECC 检查 |
| `icache/ICachePrefetchPipe.scala` | 542 | 预取流水线（3-stage），FDIP 实现，ITLB 交互 |
| `icache/ICacheWayLookup.scala` | 191 | WayLookup 缓冲区（32-entry circular queue） |
| `icache/ICacheMissUnit.scala` | 378 | Miss 处理单元，MSHR 管理，TileLink 交互 |
| `icache/ICacheMshr.scala` | 157 | 单个 MSHR 状态机实现 |
| `icache/ICacheMetaArray.scala` | 120 | Meta Array 顶层，交错 bank 管理 |
| `icache/ICacheMetaInterleavedBank.scala` | 119 | 单个交错 Meta Bank（SRAM + valid array） |
| `icache/ICacheDataArray.scala` | 71 | Data Array 顶层，bank 选择与读写控制 |
| `icache/ICacheDataBank.scala` | 97 | 单个 Data Bank（4-way 独立 SRAM） |
| `icache/ICacheReplacer.scala` | 65 | 替换策略实现（setPLRU） |
| `icache/ICacheCtrlUnit.scala` | 301 | ECC 控制单元，错误注入，MMIO 寄存器 |
| `icache/Bundles.scala` | 390 | 所有 ICache 内部/外部 Bundle 定义 |
| `icache/Helpers.scala` | 250 | ECC 编解码、地址转换、MSHR 更新等 helper trait |
| `icache/Abstracts.scala` | 25 | ICacheBundle / ICacheModule 抽象基类 |
| `icache/Utils.scala` | 125 | DeMultiplexer、MuxBundle、FIFOReg 工具模块 |

前端相关文件：

| 文件路径 | 功能说明 |
|----------|---------|
| `frontend/FrontendParameters.scala` | Frontend 参数（FetchBlockSize=64, FetchPorts=2） |
| `frontend/Bundles.scala` | FtqToICacheIO, ICacheToIfuIO, IfuToICacheIO 接口定义 |

（以上路径相对于 `src/main/scala/xiangshan/`）

## 13. 关键设计决策总结

1. **PIPT 组织**：物理索引物理标签，简化一致性处理，通过 alias tag 机制解决虚拟地址 aliasing
2. **WayLookup 解耦**：将 TLB lookup（长延迟）与 Data SRAM 读取分离到不同时钟周期，降低关键路径延迟
3. **双端口 + 跨 line 支持**：每次取指可获取最多 128B（两条 cache line），满足 64B FetchBlockSize 要求
4. **Fetch MSHR 与 Prefetch MSHR 分离**：确保高优先级的 fetch 请求不被 prefetch 阻塞
5. **Prefetch MSHR FIFO 优先级**：按入队顺序发送请求，避免 prefetch 饥饿
6. **Soft Prefetch 优先**：后端 `prefetch.i` 指令触发的预取优先级高于 FDIP
7. **可配置 ECC**：支持从无校验到 SECDED 的完整谱系，便于不同可靠性需求
8. **数据 bank 读使能优化**：按 way 独立控制 SRAM 读使能，显著降低动态功耗
9. **错误注入支持**：通过 MMIO 寄存器可注入 ECC 错误，便于可靠性验证
10. **WayLookup update bypass**：MSHR refill 后直接更新 WayLookup 中的条目，确保 MainPipe 取到最新数据
