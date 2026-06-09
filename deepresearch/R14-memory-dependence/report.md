# R14 - XiangShan Memory Dependence Prediction (MDP) 深度研究报告

## 1. 概述

Memory Dependence Prediction (MDP) 是现代高性能超标量处理器中用于解决 Load-Store 数据冒险的核心微架构机制。XiangShan 处理器的 MDP 系统基于两篇经典论文设计实现：

- **Store Set** 机制：参考 George Z. Chrysos 和 Joel S. Emer 在 ISCA 1998 发表的 *"Memory Dependence Prediction using Store Sets"*
- **Wait Table** 机制：参考 Richard Kessler 在 IEEE Micro 1999 发表的 *"The alpha 21264 microprocessor"*

MDP 的核心目标是：在乱序执行（Out-of-Order Execution）环境下，允许 Load 指令投机执行（Speculative Load Execution），同时在检测到 Store-to-Load 数据依赖冲突时，能够准确地回滚（Rollback）并重新执行受影响的 Load 指令。

XiangShan 的 MDP 系统由三个关键硬件表结构组成：**SSIT (Store Set Identifier Table)**、**LFST (Last Fetched Store Table)** 和 **WaitTable**。这三个模块协同工作，分别在 Decode、Rename/Dispatch 和 Execution 阶段提供 Memory Dependence 预测信息。

## 2. MDP 系统架构

### 2.1 整体架构图

```
                        XiangShan MDP Architecture
 ┌─────────────────────────────────────────────────────────────────┐
 │                        Frontend Pipeline                         │
 │                                                                  │
 │  ┌──────────┐   ┌──────────┐   ┌──────────┐                    │
 │  │  Decode   │   │  Rename  │   │ Dispatch │                    │
 │  │  Stage    │──>│  Stage   │──>│  Stage   │                    │
 │  └────┬─────┘   └────┬─────┘   └────┬─────┘                    │
 │       │              │              │                            │
 │  ┌────┴─────┐   ┌────┴─────┐   ┌───┴──────┐                    │
 │  │   SSIT   │   │  Rename  │   │   LFST   │                    │
 │  │ (1024)   │   │  逻辑    │   │  (32x4)  │                    │
 │  │          │   │          │   │          │                    │
 │  │ rd: decode│   │ io.ssit  │   │ rd:disp  │                    │
 │  │ wr:update│   │ ────────> │   │ wr:store │                    │
 │  └──────────┘   └──────────┘   │  issue   │                    │
 │                                 └──────────┘                    │
 │  ┌──────────┐                                                  │
 │  │WaitTable │                                                  │
 │  │ (1024)   │                                                  │
 │  │ rd: decode│                                                  │
 │  │ wr:update│                                                  │
 │  └──────────┘                                                  │
 └──────────────────────────┬──────────────────────────────────────┘
                            │
                            ▼
 ┌─────────────────────────────────────────────────────────────────┐
 │                     Memory Subsystem                             │
 │                                                                  │
 │  ┌──────────┐   ┌──────────┐   ┌──────────┐                    │
 │  │ Load Unit │   │Store Unit│   │StoreQueue│                    │
 │  │  (S0-S3)  │   │ (S0-S2)  │   │ Forward  │                    │
 │  └────┬─────┘   └────┬─────┘   └────┬─────┘                    │
 │       │              │              │                            │
 │       │    ┌─────────┴──────────┐   │                            │
 │       │    │ LoadQueueRAW       │   │                            │
 │       └───>│ LoadQueueRAR       │   │                            │
 │            │ LoadQueueReplay    │<──┘                            │
 │            └─────────┬──────────┘                                │
 │                      │                                           │
 │              ┌───────┴───────┐                                   │
 │              │ Violation     │                                   │
 │              │ Detection &   │                                   │
 │              │ Rollback      │                                   │
 │              └───────────────┘                                   │
 └──────────────────────────┬──────────────────────────────────────┘
                            │
                            ▼ mdpTrain
 ┌─────────────────────────────────────────────────────────────────┐
 │                    Backend CtrlBlock                              │
 │                                                                  │
 │  ┌──────────┐   ┌──────────┐                                    │
 │  │ MemCtrl  │   │  SSIT    │  (SSIT and LFST are               │
 │  │          │──>│  + LFST  │   instantiated in MemCtrl)         │
 │  │          │   │  + Update│                                    │
 │  └──────────┘   └──────────┘                                    │
 └─────────────────────────────────────────────────────────────────┘
```

### 2.2 关键配置参数

MDP 系统的核心参数定义在 `src/main/scala/xiangshan/Parameters.scala` 中：

| 参数名 | 值 | 说明 |
|--------|-----|------|
| `SSITSize` | 1024 | Store Set Identifier Table 条目数 |
| `LFSTSize` | 32 | Last Fetched Store Table 条目数（即 Store Set 数量） |
| `LFSTWidth` | 4 | 每个 Store Set 在 LFST 中跟踪的最大 Store 数量 |
| `SSIDWidth` | 5 (`log2Up(LFSTSize)`) | Store Set ID 位宽 |
| `WaitTableSize` | 1024 | Wait Table 条目数 |
| `MemPredPCWidth` | 10 (`log2Up(WaitTableSize)`) | Memory Predictor 使用的 PC hash 位宽 |
| `LWTUse2BitCounter` | true | WaitTable 使用 2-bit 计数器 |
| `LFSTEnable` | true | LFST 启用控制 |
| `ResetTimeMax2Pow` | 20 | SSIT/WaitTable 最大重置间隔 (2^20 cycles) |
| `ResetTimeMin2Pow` | 14 | SSIT/WaitTable 最小重置间隔 (2^14 cycles) |

### 2.3 CSR 控制信号

MDP 行为可通过 CSR 寄存器进行运行时控制，定义在 `Bundle.scala` 的 `CustomCSRCtrlIO` 中：

| 信号 | 说明 |
|------|------|
| `lvpred_disable` | 全局禁用 Load Violation Predictor（包括 SSIT 和 WaitTable） |
| `no_spec_load` | 禁用投机 Load 执行，所有 Load 都必须等待 |
| `storeset_wait_store` | 控制 Store 是否也需要在 LFST 中等待 |
| `lvpred_timeout` | SSIT/WaitTable 的自动清除超时控制 |

## 3. SSIT (Store Set Identifier Table) —— 依赖关系检测核心

### 3.1 SSIT 功能与结构

SSIT 是 MDP 系统的核心表结构，实现在 `src/main/scala/xiangshan/mem/mdp/StoreSet.scala`。它的功能是：**为每条 Load/Store 指令分配一个 Store Set ID (SSID)，将可能存在依赖关系的 Load 和 Store 指令映射到同一个 Store Set 中**。

SSIT 的条目结构（`SSITEntry`）：

```
SSITEntry:
┌───────────┬───────────┬───────────┐
│   valid   │   ssid    │  strict   │
│   (1b)    │  (5b)     │   (1b)    │
└───────────┴───────────┴───────────┘
```

- `valid`：该 PC 对应的 SSIT 条目是否有效
- `ssid`：Store Set Identifier，标识该指令所属的 Store Set（5-bit，最多 32 个 Store Set）
- `strict`：是否需要严格等待（Strict Load Wait），即 Load 必须等待对应 Store 的地址计算完成后才能发射

### 3.2 SSIT 存储组织

SSIT 使用两个独立的 SRAM 模块实现：

1. **valid_array**：存储每个条目的 valid 位
   - 容量：1024 条目，每条目 1 bit
   - 读端口数：`DecodeWidth`（用于 Decode 阶段并行读取）
   - 写端口数：2（一个用于 flush/重置，一个用于 update）

2. **data_array**：存储 ssid 和 strict 信息
   - 容量：1024 条目，每条目 `SSITDataEntry`（ssid + strict）
   - 读/写端口配置与 valid_array 相同

SSIT 的端口分配策略：

```
Read Port Allocation:
  Port 0: Decode stage read (SSIT_DECODE_READ_PORT_BASE)
  - 同时复用为: Update Load 读端口 (SSIT_UPDATE_LOAD_READ_PORT = 0)
  - 同时复用为: Update Store 读端口 (SSIT_UPDATE_STORE_READ_PORT = 1)

Write Port Allocation:
  Port 0: flush/重置 (SSIT_MISC_WRITE_PORT)
  - 同时复用为: Update Load 写端口 (SSIT_UPDATE_LOAD_WRITE_PORT = 0)
  Port 1: Update Store 写端口 (SSIT_UPDATE_STORE_WRITE_PORT = 1)
```

这种端口复用设计的关键在于：当 `io.update.valid` 为真时，会触发 redirect 信号到 Frontend，从而保证 Decode 阶段不会同时需要读取 SSIT，因此读端口可以安全地被 Update 逻辑使用。

### 3.3 SSIT 工作流程

#### 3.3.1 Decode 阶段读取

在 Decode 阶段，SSIT 通过 `foldpc`（XOR-folded PC，从 VaddrBits-1 到 1，折叠到 `MemPredPCWidth` 位）作为地址进行并行查表。每个 Decode 宽度的指令都有一个独立的读端口，SSIT 在 Decode 阶段输出读取结果，但实际生效延迟到 Rename 阶段（通过寄存器延迟一拍）。

```scala
// Decode 阶段读取 SSIT
valid_array.io.ren.get(i) := io.ren(i)    // 使能读取
valid_array.io.raddr(i) := io.raddr(i)    // XOR-folded PC 地址
data_array.io.raddr(i) := io.raddr(i)

// Rename 阶段获得结果（一拍延迟后）
io.rdata(i).valid := valid_array.io.rdata(i)
io.rdata(i).ssid := data_array.io.rdata(i).ssid
io.rdata(i).strict := data_array.io.rdata(i).strict
```

#### 3.3.2 SSIT 更新算法

当 Load Violation 被检测到时，系统生成 `MemPredUpdateReq` 信号，触发 SSIT 更新。更新逻辑在 `CtrlBlock.scala` 中完成：`mdpTrain` 信号（来自 LoadQueueRAW 的 violation detection）被用于从 FTQ（Fetch Target Queue）中读取 Load 和 Store 的 PC，然后生成 `MemPredUpdateReq` 发送到 SSIT。

SSIT 更新采用经典的 **Store Set 分配算法**（对应论文中的四条规则）：

**情况 1：Load 和 Store 都未分配 Store Set (b00)**
```
两者都分配一个新的 Store Set ID
load  <- allocSsid = min(XORFold(ldpc), XORFold(stpc))
store <- allocSsid
strict = false
```

**情况 2：Load 已分配但 Store 未分配 (b10)**
```
Store 使用 Load 已有的 Store Set ID
store <- ldSsidAllocate (Load 的 SSID)
strict = false
```

**情况 3：Store 已分配但 Load 未分配 (b01)**
```
Load 使用 Store 已有的 Store Set ID
load <- stSsidAllocate (Store 的 SSID)
strict = false
```

**情况 4：两者都已分配 (b11)**
```
选择较小的 SSID 作为 "winner"
load  <- winnerSSID = min(loadOldSSID, storeOldSSID)
store <- winnerSSID

如果两者 SSID 已经相同 (s2_ssidIsSame)：
  设置 strict = true  （Load 需要严格等待该 Store）
```

这种算法确保了存在依赖关系的 Load 和 Store 会被逐步合并到同一个 Store Set 中。当重复发生 violation 且两者已经在同一 Store Set 时，设置 `strict` 标记，使得 Load 必须严格等待。

### 3.4 SSIT 自动清除机制

SSIT 包含一个定时重置机制（`resetCounter` + `resetStepCounter`），周期性地清除所有条目：

- 当 `resetCounter` 达到由 `lvpred_timeout` 控制的阈值时，进入 `s_flush` 状态
- 在 `s_flush` 状态下，逐条清除 valid_array 中的条目（每周期清除一条）
- 清除完成后回到 `s_idle`，重新开始计数

这种机制确保了 SSIT 中过时的预测信息不会无限累积，允许系统适应程序行为的变化。

### 3.5 SSIT 性能计数器

SSIT 提供了丰富的性能监控接口：

| 计数器 | 说明 |
|--------|------|
| `ssit_update_lxsx` | Load 未分配、Store 未分配的更新次数 |
| `ssit_update_lysx` | Load 已分配、Store 未分配的更新次数 |
| `ssit_update_lxsy` | Load 未分配、Store 已分配的更新次数 |
| `ssit_update_lysy` | 两者都已分配的更新次数 |
| `ssit_update_should_strict` | 需要设置 strict 的更新次数 |
| `ssit_update_strict_failed` | strict 设置失败次数（理想情况应为 0） |
| `ssit_pred_dependence` | 预测到依赖关系的次数 |
| `ssit_pred_strict` | 预测到 strict 依赖关系的次数 |
| `reset_timeout` | SSIT 重置超时次数 |

## 4. LFST (Last Fetched Store Table) —— 运行时依赖追踪

### 4.1 LFST 功能与结构

LFST 是 Store Set 机制的运行时组件，用于追踪**每个 Store Set 中最近被 Dispatch 的 Store 指令的 RobIdx**。当 Load 指令被 Dispatch 时，LFST 会查询对应 Store Set 中是否有未完成的 Store，如果有，Load 需要等待。

LFST 的条目结构：

```
LFSTEntry:
┌───────────┬───────────┐
│   valid   │  robIdx   │
│   (1b)    │  (RegPtr) │
└───────────┴───────────┘
```

LFST 的存储组织：

```
LFST:
  validVec[LFSTSize][LFSTWidth]  -- 每个 Store Set 有 LFSTWidth=4 个 slot
  robIdxVec[LFSTSize][LFSTWidth] -- 对应的 RobIdx
  allocPtr[LFSTSize]             -- 每个 Store Set 的分配指针
```

### 4.2 LFST 工作流程

#### 4.2.1 Dispatch 阶段查询

在 Dispatch 阶段，每条指令通过其 SSID 查找 LFST：

```scala
io.dispatch.resp(i).bits.shouldWait := (
    (valid(io.dispatch.req(i).bits.ssid) || hitInDispatchBundle) &&
    io.dispatch.req(i).valid &&
    (!io.dispatch.req(i).bits.isstore || io.csrCtrl.storeset_wait_store)
  ) && !io.csrCtrl.lvpred_disable || io.csrCtrl.no_spec_load
```

判断逻辑：
1. LFST 中该 SSID 对应的 Store Set 是否有有效条目（即是否有未完成的 Store）
2. 同一个 Dispatch Bundle 中是否有更早的同 SSID Store（`hitInDispatchBundle`）
3. CSR 控制信号：`storeset_wait_store` 控制 Store 是否也需要等待
4. `lvpred_disable` 禁用或 `no_spec_load` 强制等待

响应还包含 `robIdx`：指向该 Store Set 中最近 Dispatch 的 Store 的 RobIdx，后续 Load 将用此 RobIdx 判断 Store 是否已完成。

#### 4.2.2 Store Dispatch 注册

当 Store 指令 Dispatch 时，LFST 在该 Store 的 SSID 对应的 slot 中注册：

```scala
when(io.dispatch.req(i).valid && io.dispatch.req(i).bits.isstore){
  val waddr = io.dispatch.req(i).bits.ssid
  val wptr = allocPtr(waddr)
  allocPtr(waddr) := allocPtr(waddr) + 1.U
  validVec(waddr)(wptr) := true.B
  robIdxVec(waddr)(wptr) := io.dispatch.req(i).bits.robIdx
}
```

#### 4.2.3 Store 发射清除

当 Store 指令在 Store Unit 中发射（Issue）时，LFST 清除对应的条目：

```scala
when(io.storeIssue(i).valid && io.storeIssue(i).bits.storeSetHit &&
     io.storeIssue(i).bits.robIdx.value === robIdxVec(io.storeIssue(i).bits.ssid)(j).value){
  validVec(io.storeIssue(i).bits.ssid)(j) := false.B
}
```

#### 4.2.4 Redirect 清除

当发生 pipeline redirect（如分支预测错误、violation rollback）时，LFST 清除 RobIdx 需要被 flush 的条目，并回退分配指针：

```scala
when(validVec(i)(j) && robIdxVec(i)(j).needFlush(io.redirect)){
  validVec(i)(j) := false.B
}
```

### 4.3 LFST Overflow 检测

LFST 每个 Store Set 只有 `LFSTWidth = 4` 个 slot。当同一个 SSID 中超过 4 个 Store 在同一时刻等待时，会发生溢出。LFST 通过 `overflowVec` 信号报告溢出情况：

```scala
when(validVec(waddr)(wptr)) {
  overflowVec(i) := true.B  // LFST overflow detected
}
```

## 5. WaitTable —— 21264 风格 Load 等待预测

### 5.1 WaitTable 功能

WaitTable 是一个独立于 SSIT 的简单预测器，参考 Alpha 21264 处理器的设计。它使用 **2-bit saturating counter** 来记录每个 Load 指令是否曾经发生过 store-to-load violation。实现在 `src/main/scala/xiangshan/mem/mdp/WaitTable.scala`。

```
WaitTable:
  data[1024]: 每条目 2-bit counter
  - 00, 01: 不需要等待（低计数）
  - 10, 11: 需要等待（高计数，MSB=1）
```

### 5.2 WaitTable 工作原理

**读取**：在 Decode 阶段，使用 XOR-folded PC 作为地址读取 WaitTable。如果计数器的 MSB 为 1，则 `loadWaitBit` 为 true，表示该 Load 可能需要等待之前的 Store。

```scala
io.rdata(i) := (data(io.raddr(i))(LWTUse2BitCounter.B.asUInt) ||
                io.csrCtrl.no_spec_load) && !io.csrCtrl.lvpred_disable
```

**更新**：当检测到 Load Violation 时，对应 PC 条目的计数器增加（右移并设置 MSB）：

```scala
when(io.update.valid){
  data(io.update.waddr) := Cat(data(io.update.waddr)(0), true.B)
}
```

这实现了一个 2-bit 饱和计数器：`00 -> 10 -> 11`（只增不减，直到重置）。

**重置**：与 SSIT 类似，WaitTable 也有定时重置机制，通过 `resetCounter` 和 `lvpred_timeout` 控制。

### 5.3 WaitTable 在当前实现中的状态

值得注意的是，在当前代码中，WaitTable 的输出在 `MemCtrl.scala` 中被设为 `DontCare`：

```scala
io.waitTable2Rename := DontCare
```

这意味着 **WaitTable 当前未被实际使用**，其预测结果没有传递到 Rename 阶段。MDP 系统目前完全依赖 SSIT + LFST 机制。WaitTable 的代码仍然保留在代码库中，可能作为备用方案或历史遗留。

## 6. Rename 阶段的 MDP 集成

### 6.1 从 SSIT 到 Rename 的信号传递

在 Rename 阶段，SSIT 的查询结果被打包到微操作（Micro-Op）中：

```scala
// Rename.scala
uops(i).storeSetHit := io.ssit(i).valid      // 该指令是否在 SSIT 中有记录
uops(i).loadWaitStrict := io.ssit(i).strict && io.ssit(i).valid  // 是否严格等待
uops(i).ssid := io.ssit(i).ssid               // Store Set ID
uops(i).loadWaitBit := io.waittable(i)        // WaitTable 预测（当前为 DontCare）
```

这些字段被嵌入到 `DynInst` Bundle 中，随微操作一起在整个后端流水线中传播：

```scala
// Bundle.scala
val storeSetHit = Bool()          // inst has been allocated a store set
val waitForRobIdx = new RobPtr    // store set predicted previous store robIdx
val loadWaitBit = Bool()          // load inst will not be executed until former store addr calculated
val loadWaitStrict = Bool()       // strict load wait (ALL former store addr calculated)
val ssid = UInt(SSIDWidth.W)      // Store Set ID
```

### 6.2 Dispatch 阶段 LFST 查询

在 Dispatch 阶段，带有 `storeSetHit` 的指令会触发 LFST 查询：

```scala
// Dispatch.scala
io.lfst.req(i).valid := fromRename(i).fire && updatedUop(i).storeSetHit
io.lfst.req(i).bits.isstore := isStore(i)
io.lfst.req(i).bits.ssid := updatedUop(i).ssid
io.lfst.req(i).bits.robIdx := updatedUop(i).robIdx
```

LFST 响应会更新微操作中的等待信息：

```scala
// Dispatch.scala - 对于非 Store 指令
fromRenameUpdate(i).bits.loadWaitBit := io.lfst.resp(i).bits.shouldWait
fromRenameUpdate(i).bits.waitForRobIdx := io.lfst.resp(i).bits.robIdx
fromRenameUpdate(i).bits.loadWaitStrict := fromRename(i).bits.loadWaitStrict &&
                                            io.lfst.resp(i).bits.shouldWait
```

## 7. Speculative Load Execution 和 Recovery

### 7.1 Store Queue 中的 MDP 等待逻辑

Load 指令在 Store Queue 的转发查找（Store Forward）过程中，MDP 信息被用于判断 Load 是否应该等待。

在 `NewStoreQueue.scala` 中，Store Queue 的转发查询模块（`StoreForwardQuery`）在 S0 阶段生成 MDP 掩码：

```scala
// 两种模式：LFSTEnable 和 LFSTDisable
val s0StoreSetHitVec = Mux(lfstEnable,
  // LFST 模式：比较 robIdx 与 waitForRobIdx
  WireInit(VecInit((0 until StoreQueueSize).map(j =>
    s0Req.bits.loadWaitBit &&
    io.dataEntriesIn(j).uop.robIdx === s0Req.bits.waitForRobIdx))),
  // SSIT-only 模式：比较 ssid
  WireInit(VecInit((0 until StoreQueueSize).map(j =>
    io.dataEntriesIn(j).uop.storeSetHit &&
    io.dataEntriesIn(j).uop.ssid === s0Req.bits.ssid)))
)
```

- **LFST 模式**（`LFSTEnable = true`，默认启用）：Load 等待特定 RobIdx 的 Store（精确匹配）
- **SSIT-only 模式**：Load 等待同一 SSID 的所有 Store

### 7.2 Load Pipeline 中的 Nuke 检测

Load Unit 在 S1 和 S2 阶段进行 Nuke（Store-to-Load violation）检测。

**S1 阶段**（`NewLoadUnit.scala` L586-L721）：

```scala
val isStoreSetHit = uop.storeSetHit
val waitRobIdx = uop.waitForRobIdx

// Nuke query from StoreUnit
val nukeQueryValids = io.staNukeQueryReq.map(_.valid)
val nukeQueryReqs = io.staNukeQueryReq.map(_.bits)
val nukePAddrMatches = nukeQueryReqs.map(req => nukePAddrMatch(req.paddr, req.matchType, paddr))
val nukeStoreOlders = nukeQueryReqs.map(req => isAfter(robIdx, req.robIdx))
val nukeMaskMatches = nukeQueryReqs.map(req => (req.mask & in.mask).orR)

val nuke = Cat((nukeQueryValids lazyZip nukePAddrMatches lazyZip
                nukeStoreOlders lazyZip nukeMaskMatches).map {
  case (valid, paddrMatch, storeOlder, maskMatch) =>
    valid && paddrMatch && storeOlder && maskMatch
}).orR && tlbNotMiss || prevStageNuke

// 如果 nuke 来自 storeSetHit 的 Store，允许快速重放
val fastReplayNukeFirst = isStoreSetHit && nukeQueryReqs.zip(nukeQueryValids).map{
  case (req, v) => req.robIdx === waitRobIdx && v
}.reduce(_ || _) || prevStageFastReplayNukeFirst
```

Nuke 检测的四个条件：
1. Store 的地址有效（`valid`）
2. 物理地址匹配（`paddrMatch`）：通过 partial physical address CAM 匹配
3. Store 比 Load 更旧（`storeOlder`）：`isAfter(robIdx, req.robIdx)`
4. 数据掩码有重叠（`maskMatch`）

**S2 阶段**（`NewLoadUnit.scala` L985-L1079）：

S2 阶段再次进行 Nuke 检测（更精确），并生成 Replay 原因（Cause）：

```scala
// 生成 Load Replay 原因
cause(C_MA) := troubleMaker && uop.storeSetHit && sqAddrInvalid
cause(C_NK) := troubleMaker && nuke
cause(C_FF) := troubleMaker && sqDataInvalid
// ... 其他原因
```

`C_MA`（Memory Ambiguity）：当 Load 被预测需要等待（`storeSetHit`）但 Store 地址无效时触发，表示 Load 投机执行可能读到了错误数据。

### 7.3 Fast Replay 机制

当检测到 Nuke 且该 Store 是 Load 等待的目标 Store 时，系统可以选择**快速重放（Fast Replay）**：

```scala
val fastReplayNuke = cause(C_NK) &&
  !hasHigherPriorityCauses(VecInit(cause.patch(C_MA, Seq(
    cause(C_MA) && !fastReplayNukeFirst), 1)), C_RAR)

val fastReplay = !LoadEntrance.isFastReplay(entrance) &&
  (fastReplayMSHRNack || fastReplayBankConflict || fastReplayNuke) &&
  !isUnalign && !tlbMiss
```

Fast Replay 允许 Load 在不进入 LoadQueueReplay 的情况下直接重新进入 Load Pipeline，减少了恢复延迟。

### 7.4 Load Replay Queue

当 Load 需要重放且不能 Fast Replay 时，它会被放入 `LoadQueueReplay`。LoadQueueReplay 使用多种原因分类管理重放请求：

| Cause Code | 名称 | 说明 |
|-----------|------|------|
| C_UNCACHE (0) | Uncache | 非缓存访问 |
| C_SMF (1) | Store Multi-Forward | Store Queue 多匹配 |
| C_MA (2) | Memory Ambiguity | ST-LD violation re-execute check |
| C_TM (3) | TLB Miss | TLB 未命中 |
| C_FF (4) | Forward Fail | Store Forwarding 失败 |
| C_DR (5) | DCache Replay | DCache 重放 |
| C_DM (6) | DCache Miss | DCache 未命中 |
| C_WF (7) | WPU Predict Fail | 写预测单元失败 |
| C_BC (8) | Bank Conflict | DCache Bank 冲突 |
| C_RAR (9) | RAR Queue | RAR Queue 拒绝 |
| C_RAW (10) | RAW Queue | RAW Queue 拒绝 |
| C_NK (11) | Nuke | Store-to-Load violation |

其中 `C_MA` 和 `C_NK` 直接与 MDP 相关。

LoadQueueReplay 使用 **AgeDetector** 来选择最旧的重放请求，并支持 **Cold Down** 机制防止重放风暴：

```scala
val ColdDownCycles = 16
val ColdDownThreshold = 12  // 可配置
def replayCanFire(i: Int) = coldCounter(i) >= 0.U && coldCounter(i) < ColdDownThreshold
```

## 8. Violation Detection 和 Pipeline Recovery

### 8.1 LoadQueueRAW —— Store-to-Load Violation 检测

LoadQueueRAW（Read-After-Write Queue）是检测 Store-to-Load 数据冲突的核心组件，实现在 `src/main/scala/xiangshan/mem/lsqueue/LoadQueueRAW.scala`。

**功能**：当 Store 指令写回（Writeback）时，它在 LoadQueueRAW 中搜索地址匹配且比它更年轻（Younger）的 Load 指令。如果找到，说明 Load 可能读取了过时的数据，需要重新执行。

**数据结构**：

```
LoadQueueRAW Entry:
┌───────┬────────┬─────────┬──────┬──────────┐
│ Valid │  Uop   │ PAddr   │ Mask │ Datavalid│
│ (1b)  │ (复杂) │ (24b)   │(64b) │   (1b)   │
└───────┴────────┴─────────┴──────┴──────────┘
```

**Violation 检测流程**：

```
Cycle 0: Store Writeback
  Store 地址与 LoadQueueRAW 中所有条目进行 CAM 匹配
  生成 match vector

Cycle 1: Tree Reduction
  从匹配的 Load 中选择最旧的一个
  使用多级 SelectOldestByGroup 树形选择器

Cycle x: Redirect Fire
  选择最旧的 violation Load
  生成 redirect 请求，触发 pipeline flush
```

检测算法：

```scala
// 地址和掩码匹配
val addrMaskMatch = paddrModule.io.violationMmask(i).asUInt &
                    maskModule.io.violationMmask(i).asUInt

// 条件：已分配、Store 有效、Load 比 Store 更新、数据有效、未被 flush
val entryNeedCheck = GatedValidRegNext(VecInit((0 until LoadQueueRAWSize).map(j => {
  allocated(j) && storeIn(i).valid &&
  isAfter(uop(j).robIdx, storeIn(i).bits.uop.robIdx) &&
  datavalid(j) && !uop(j).robIdx.needFlush(io.redirect) && !willRevoke(j)
})))

val lqViolationSelVec = VecInit((0 until LoadQueueRAWSize).map(j => {
  addrMaskMatch(j) && entryNeedCheck(j)
}))
```

### 8.2 LoadQueueRAR —— Load-to-Load Violation 检测

LoadQueueRAR（Read-After-Release Queue）检测 Load-to-Load 之间的数据冲突，实现在 `src/main/scala/xiangshan/mem/lsqueue/LoadQueueRAR.scala`。

**功能**：当 DCache 的 cacheline 被释放（Release）时，检查是否有 Load 正在等待使用该 cacheline 的数据。如果 Load 的数据已经失效（cacheline 被替换），需要重新执行。

**检测条件**：
1. 物理地址匹配（CAM 查询）
2. cacheline 已被释放（`released` 标记）
3. Load 比释放操作更年轻

```scala
matchMaskReg(i) := (allocated(i) &
                    paddrModule.io.releaseViolationMmask(w)(i) &
                    robIdxMask(i) &&
                    released(i))

query.resp.bits.nuke := ParallelORR(ldLdViolationMask)
```

### 8.3 Memory Violation Rollback

当 LoadQueueRAW 检测到 violation 时，它生成 `rollback` 信号：

```scala
val allRedirect = (0 until StorePipelineWidth).map(i => {
  val redirect = Wire(Valid(new Redirect))
  redirect.valid := rollbackLqWb(i).valid
  redirect.bits.robIdx := rollbackLqWb(i).bits.robIdx
  redirect.bits.ftqIdx := rollbackLqWb(i).bits.ftqPtr
  redirect.bits.level := RedirectLevel.flush
  redirect.bits.target := rollbackLqWb(i).bits.pc  // 回滚到 Load 指令的 PC
  redirect
})
```

`io.mdpTrain` 是选择最旧 violation redirect 的结果，被传递到 Backend 用于更新 SSIT：

```scala
val oldestOH = Redirect.selectOldestRedirect(allRedirect)
io.mdpTrain := Mux1H(oldestOH, allRedirect)
```

在 MemBlock.scala 中，多个 rollback 源被合并选择：

```scala
val allRedirect = newLoadUnits.map(_.io.rollback) ++ lsq.io.nack_rollback ++ lsq.io.nuke_rollback
val oldestOneHot = Redirect.selectOldestRedirect(allRedirect)
val oldestRedirect = WireDefault(Mux1H(oldestOneHot, allRedirect))
io.mem_to_ooo.memoryViolation := oldestRedirect
io.mem_to_ooo.mdpTrain := lsq.io.mdpTrain
```

### 8.4 MDP 训练信号回传

`mdpTrain` 信号从 MemBlock 传递到 Backend 的 CtrlBlock，触发 SSIT 更新：

```scala
// Backend.scala
ctrlBlock.io.fromMem.mdpTrain <> io.mem.mdpTrain

// CtrlBlock.scala - 生成 memPredUpdate
val mdpTrainValid = io.fromMem.mdpTrain.valid

// 从 FTQ 读取 Load PC
memCtrl.io.memPredUpdate.ldpc := XORFold(
  (pcMem.io.rdata(pcMemIdx).toUInt + offset)(VAddrBits - 1, 1), MemPredPCWidth)

// 从 FTQ 读取 Store PC
memCtrl.io.memPredUpdate.stpc := XORFold(
  (pcMem.io.rdata(pcMemIdx).toUInt + offset)(VAddrBits - 1, 1), MemPredPCWidth)

// PC 读取需要一拍延迟
memCtrl.io.memPredUpdate.valid := RegNext(mdpTrainValid)
```

完整的 MDP 训练闭环：

```
LoadQueueRAW 检测到 Violation
    |
    +---> io.rollback -> pipeline flush (回到 Load PC 重新执行)
    |
    +---> io.mdpTrain -> MemBlock -> Backend -> CtrlBlock
              |
              +-- 从 FTQ 读取 Load PC 和 Store PC
              |
              +-- 生成 MemPredUpdateReq -> SSIT 更新
                    (分配或合并 Store Set)
```

## 9. MDP 与 Load/Store Queue 的交互

### 9.1 交互总览

```
                    +---------------+
                    |  Store Queue  |
                    |               |
 Store Issue ----->| MDP wait      |-----> Load issue control
                    | check         |
                    |               |
 Store Forward --->| Forward lookup|-----> Data Forwarding
                    +---------------+
                           |
                     updateLFST ---------> LFST clear Store entries
                           |
                    +------+-----------+
                    |  LoadQueueRAW    |
                    |                  |
 Store Writeback -->| Violation detect |-----> Pipeline Rollback
                    |                  |
                    +---------+--------+
                              |
                       mdpTrain ------------> SSIT update
                              |
                    +---------+-----------+
                    |  LoadQueueRAR       |
                    |                     |
 DCache Release --->| Load-to-Load detect |-----> Replay
                    +---------------------+
                              |
                    +---------+-----------+
                    | LoadQueueReplay     |
                    |                     |
 Load failure ----->| Replay scheduling   |-----> Load re-execution
                    | (Cause + Block)     |
                    +---------------------+
```

### 9.2 Store Queue Forward 中的 MDP 掩码

在 Store Queue 进行数据转发查找时，MDP 信息被用于缩窄搜索范围：

```scala
// NewStoreQueue.scala
val s0StoreSetHitVec = Mux(lfstEnable,
  // LFST 模式：只关注 waitForRobIdx 对应的 Store
  WireInit(VecInit((0 until StoreQueueSize).map(j =>
    s0Req.bits.loadWaitBit &&
    io.dataEntriesIn(j).uop.robIdx === s0Req.bits.waitForRobIdx))),
  // SSIT 模式：关注同 SSID 的所有 Store
  WireInit(VecInit((0 until StoreQueueSize).map(j =>
    io.dataEntriesIn(j).uop.storeSetHit &&
    io.dataEntriesIn(j).uop.ssid === s0Req.bits.ssid)))
)
```

### 9.3 Store Unit 对 LFST 的更新

当 Store 指令在 Store Unit 中成功发射时，它广播 `updateLFST` 信号到 LFST：

```scala
// NewStoreUnit.scala
val updateLFSTValid = fire && tlbHit && isScalar && !isUnalignTail

io.updateLFST.valid := updateLFSTValid
io.updateLFST.bits.robIdx := robIdx
io.updateLFST.bits.ssid := ssid
io.updateLFST.bits.storeSetHit := storeSetHit
```

## 10. 性能影响分析

### 10.1 MDP 带来的性能收益

1. **投机 Load 执行**：通过 SSIT+LFST 机制，大部分 Load 指令可以在 Store 地址计算之前投机执行，减少了 Load 的等待时间。只有存在已知依赖关系的 Load 才会被阻塞。

2. **精确等待**：LFST 使用 RobIdx 精确匹配等待目标，而非 SSIT-only 模式的 SSID 泛匹配，减少了不必要的等待。

3. **Fast Replay**：当检测到 Nuke 且目标 Store 已知时，Load 可以快速重放而不需要进入 LoadQueueReplay 队列，减少了恢复开销。

4. **冲突检测前移**：通过在 S1 阶段就开始 Nuke 检测，尽早发现 violation，避免在 S3 阶段才发现后产生更长的恢复路径。

### 10.2 性能开销

1. **SSIT 查询延迟**：SSIT 在 Decode 阶段读取，结果在 Rename 阶段生效，增加了 1 拍的流水线延迟。

2. **LFST 查询开销**：LFST 在 Dispatch 阶段查询，增加了 Dispatch 阶段的逻辑复杂度。

3. **Store Queue Forward 延迟**：MDP 掩码的生成增加了 Store Queue Forward 路径的关键路径延迟。

4. **LoadQueueReplay 资源占用**：大量的 Load Replay 会消耗 LoadQueueReplay 的条目资源，可能导致新 Load 无法进入。

### 10.3 性能计数器

LoadQueueReplay 提供了详细的 Replay 分类计数器，用于分析 MDP 系统的效能：

| 计数器 | 对应原因 | 说明 |
|--------|----------|------|
| `replay_mem_amb` | C_MA | Memory Ambiguity 导致的 Replay |
| `replay_nuke` | C_NK | Nuke（Store-to-Load Violation）导致的 Replay |
| `replay_tlb_miss` | C_TM | TLB Miss |
| `replay_dcache_miss` | C_DM | DCache Miss |
| `replay_forward_fail` | C_FF | Store Forward 失败 |
| `replay_rar_nack` | C_RAR | RAR Queue 拒绝 |
| `replay_raw_nack` | C_RAW | RAW Queue 拒绝 |
| `stld_rollback` | - | Store-to-Load Violation 导致的 Rollback |

### 10.4 优化方向

1. **WaitTable 启用**：当前 WaitTable 的输出被设为 `DontCare`。启用 WaitTable 可以为 SSIT 提供补充预测，特别是对于 SSIT 未覆盖的 Load 指令。

2. **LFST 容量扩展**：LFST 的 `LFSTWidth = 4` 限制了每个 Store Set 能跟踪的 Store 数量。在高并发场景下可能发生溢出（`LFST_Overflow_Count`），增加此值可能改善覆盖率。

3. **多级预测**：结合 SSIT（精确 RobIdx 匹配）、LFST（运行时追踪）和 WaitTable（概率性预测），形成多级预测层次，可以进一步优化投机 Load 的准确率。

## 11. 关键源文件索引

| 文件路径 | 说明 |
|----------|------|
| `src/main/scala/xiangshan/mem/mdp/StoreSet.scala` | SSIT 和 LFST 实现（462行） |
| `src/main/scala/xiangshan/mem/mdp/WaitTable.scala` | WaitTable 实现（71行） |
| `src/main/scala/xiangshan/backend/ctrlblock/MemCtrl.scala` | MDP 控制模块，实例化 SSIT+LFST（43行） |
| `src/main/scala/xiangshan/backend/CtrlBlock.scala` | MDP 训练信号处理，SSIT 更新触发 |
| `src/main/scala/xiangshan/backend/dispatch/Dispatch.scala` | LFST Dispatch 查询 |
| `src/main/scala/xiangshan/backend/rename/Rename.scala` | SSIT/WaitTable 结果写入微操作 |
| `src/main/scala/xiangshan/mem/lsqueue/LoadQueueRAW.scala` | Store-to-Load Violation 检测与 Rollback |
| `src/main/scala/xiangshan/mem/lsqueue/LoadQueueRAR.scala` | Load-to-Load Violation 检测 |
| `src/main/scala/xiangshan/mem/lsqueue/LoadQueueReplay.scala` | Load Replay 调度管理 |
| `src/main/scala/xiangshan/mem/lsqueue/NewStoreQueue.scala` | Store Queue Forward 中的 MDP 掩码 |
| `src/main/scala/xiangshan/mem/pipeline/NewLoadUnit.scala` | Load Pipeline Nuke 检测与 Replay Cause 生成 |
| `src/main/scala/xiangshan/mem/pipeline/NewStoreUnit.scala` | Store Unit LFST 更新信号生成 |
| `src/main/scala/xiangshan/mem/MemBlock.scala` | MemBlock 顶层，连接 Violation Rollback 和 MDP Train |
| `src/main/scala/xiangshan/XSCore.scala` | XSCore 顶层连接 |
| `src/main/scala/xiangshan/Parameters.scala` | MDP 配置参数定义 |
| `src/main/scala/xiangshan/Bundle.scala` | MemPredUpdateReq, CustomCSRCtrlIO 等 Bundle |
| `src/main/scala/xiangshan/backend/Backend.scala` | Backend 顶层 MDP 连接 |

## 12. 总结

XiangShan 的 Memory Dependence Prediction 系统是一个多层次的硬件预测与恢复机制，主要包含以下核心组件：

1. **SSIT（Store Set Identifier Table）**：1024 条目的硬件表，在 Decode 阶段读取，为 Load/Store 指令分配 Store Set ID，是静态依赖关系预测的基础。通过 violation-driven 的训练算法，逐步学习程序的内存依赖模式。

2. **LFST（Last Fetched Store Table）**：32x4 的运行时表，在 Dispatch 阶段查询，追踪每个 Store Set 中最近的 Store 指令 RobIdx，为 Load 提供精确的等待目标。LFST 是 SSIT 的运行时补充，通过 RobIdx 匹配提供比 SSID 更精确的依赖追踪。

3. **WaitTable**：1024 条目的 2-bit 计数器表（参考 Alpha 21264），当前代码中未启用（输出设为 DontCare），作为备用预测机制保留。

4. **LoadQueueRAW / LoadQueueRAR**：Violation 检测硬件，在 Store Writeback 或 DCache Release 时检测冲突，生成 pipeline rollback 和 MDP training 信号。

5. **LoadQueueReplay**：管理 Load 重放的队列，支持 13 种不同的 Replay 原因分类，包括与 MDP 直接相关的 C_MA（Memory Ambiguity）和 C_NK（Nuke）。

整个系统形成了一个闭环：**预测（SSIT+LFST） -> 投机执行 -> 检测（LoadQueueRAW/RAR） -> 恢复（Rollback） -> 训练（mdpTrain -> SSIT 更新）**，使得处理器能够在保持正确性的同时最大化 Load 指令的并行度。
