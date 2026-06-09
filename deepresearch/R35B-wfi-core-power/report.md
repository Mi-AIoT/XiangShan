# R35B - WFI & Core Power Management 深度技术报告

## 1. 概述 (Overview)

XiangShan 处理器实现了一套完整的 WFI (Wait For Interrupt) 指令处理与核心电源管理机制，涵盖从软件 CSR 配置、分布式 wfiSafe 握手协议、4-state WFI 时钟门控 FSM、7-state 核心掉电 FSM，到 SoC 级 LP (Low Power) 接口的全栈低功耗方案。该机制通过两个关键 CSR 寄存器 (`mcorepwr`、`mflushpwr`) 和一个可选的时钟门控配置 (`WFIClockGate`) 协同工作，使核心在 WFI 状态下能够安全地进入低功耗模式，并在中断到来时快速恢复。

---

## 2. WFI 指令处理与 srnctl.WFI_ENABLE

### 2.1 srnctl CSR 寄存器定义

WFI 指令的使能由 Supervisor-level Custom 控制寄存器 `srnctl` (地址 `0x5C4`) 控制。其位域定义如下（见 `CSRCustom.scala`）：

```scala
class SrnctlBundle extends CSRBundle {
  val WFI_ENABLE    = RW(2).withReset(true.B).withDescription("Enable WFI execution.")
  val FUSION_ENABLE = RW(0).withReset(true.B).withDescription("Enable instruction fusion.")
}
```

- **WFI_ENABLE** (bit 2)：默认值为 `true.B`，即 reset 后 WFI 指令默认可用。
- **FUSION_ENABLE** (bit 0)：指令融合使能，与 WFI 无关但共存于同一 CSR。

### 2.2 WFI_ENABLE 的作用路径

在 `NewCSR.scala` 中，`srnctl.WFI_ENABLE` 经过逻辑组合后输出到 custom status：

```scala
io.status.custom.wfi_enable := srnctl.regOut.WFI_ENABLE.asBool && (!io.status.singleStepFlag) && !debugMode
```

这意味着 WFI 指令在以下条件下被**禁止执行**：
- `srnctl.WFI_ENABLE` 为 0（软件主动关闭）
- 处于 single-step 调试模式 (`singleStepFlag`)
- 处于 debug mode

该信号通过 `CSRBundle` 传递到 `CSRWrapper`（`CSR.scala`），再传入 `CtrlBlock`（`CtrlBlock.scala`）：

```scala
rob.io.wfi_enable := decode.io.csrCtrl.wfi_enable
```

### 2.3 Rob 中的 WFI 处理逻辑

在 `Rob.scala` 中，WFI 指令作为 `noSpecExec` 指令被特殊处理。核心逻辑如下（第 446-510 行）：

1. **WFI 进入判断**：当一条 `isWFI` 指令入队（enqueue），且无异常、非 debug mode 时，设置 `hasWFI := true.B`。
2. **WFI 退出条件**：当 `wfiEvent`（来自 CSR 层的中断检测）触发、有 flush、或 WFI 超时（`wfi_cycles` 达到 `2^20 = 1M` cycles）时，清除 `hasWFI`。
3. **WFI 安全判断**：`wfiSafe = io.wfi.safeFromMem && io.wfi.safeFromFrontend`，只有当内存子系统和前端子系统均报告 safe 后，才输出 `cpu_wfi`。
4. **wfi_enable 强制清除**：当 `wfi_enable` 为 false 时，`hasWFI` 被强制清除。

```scala
io.cpu_wfi := hasWFI && wfiSafe
```

### 2.4 WFI 超时机制

Rob 内置了 WFI 超时计数器 `wfi_cycles`（20 位），当 `wfiResume` 参数使能时：
- `hasWFI` 期间每个周期加 1
- `wfi_cycles` 全 1 时触发 `wfi_timeout`，强制退出 WFI 状态

这防止了在中断丢失的异常情况下核心永久挂起。

---

## 3. 分布式 wfiSafe 协议

wfiSafe 是一套贯穿整个处理器的分布式握手协议，确保所有内存子系统在核心进入 WFI 前处于稳定状态。核心思想是：**只有当所有组件都完成当前事务后，才允许核心进入低功耗状态**。

### 3.1 Rob 到子系统的 wfiReq 分发

在 `CtrlBlock.scala` 中，Rob 的 `wfiReq` 信号被广播到前端和内存子系统：

```scala
io.frontend.wfi.wfiReq := rob.io.wfi.wfiReq      // 广播到前端
io.toMem.wfi.wfiReq := rob.io.wfi.wfiReq          // 广播到内存子系统
```

相应的，子系统完成处理后回报 safe 信号：
```scala
rob.io.wfi.safeFromFrontend := io.frontend.wfi.wfiSafe
rob.io.wfi.safeFromMem := io.toMem.wfi.wfiSafe
```

### 3.2 Frontend 侧的 wfiSafe 聚合

在 `Frontend.scala` 中（第 210-214 行）：

```scala
private val wfiReq = DelayN(io.backend.wfi.wfiReq, WfiReqPortDelay)
icache.io.wfi.wfiReq       := wfiReq
instrUncache.io.wfi.wfiReq := wfiReq
io.backend.wfi.wfiSafe := DelayN(wfiReq && icache.io.wfi.wfiSafe && instrUncache.io.wfi.wfiSafe, WfiSafePortDealy)
```

前端的 wfiSafe 是 **ICache MissUnit** 与 **InstrUncache** 的 AND 聚合。

#### 3.2.1 ICache MissUnit wfiSafe

在 `ICacheMissUnit.scala` 中：
- 每个 MSHR entry（`ICacheMshr.scala`）在 wfiReq 时停止发起新的 acquire 请求（`io.acquire.valid := valid && !issue && !io.flush && !io.fencei && !io.wfi.wfiReq`）
- 每个 MSHR 的 safe 条件为：**无 valid 且正在 issue 的事务**（`io.wfi.wfiSafe := !(valid && issue)`）
- MissUnit 聚合：所有 MSHR 的 safe 信号 AND

#### 3.2.2 InstrUncache wfiSafe

在 `InstrUncacheImp.scala` 和 `InstrUncacheEntry.scala` 中：
- 入口在 wfiReq 时停止发起新的 MMIO acquire（`io.mmioAcquire.valid := state === State.RefillReq && !io.wfi.wfiReq`）
- 每个 entry 的 safe 条件为：**状态不等于 RefillResp**（即无活跃的 MMIO 填充事务）
- Imp 聚合：所有 entry 的 safe 信号 AND

### 3.3 Memory Subsystem 侧的 wfiSafe 聚合

在 `MemBlock.scala` 中（第 541-556 行）：

```scala
dcache.io.wfi.wfiReq   := io.wfi.wfiReq
lsq.io.wfi.wfiReq      := io.wfi.wfiReq
uncache.io.wfi.wfiReq   := io.wfi.wfiReq
ptw.io.wfi.wfiReq       := io.wfi.wfiReq
io.wfi.wfiSafe := dcache.io.wfi.wfiSafe && uncache.io.wfi.wfiSafe &&
                  lsq.io.wfi.wfiSafe && ptw.io.wfi.wfiSafe
```

内存子系统的 wfiSafe 是四个组件的 AND 聚合。

#### 3.3.1 DCache MissQueue wfiSafe

在 `MissQueue.scala` 中：
- **单个 entry**：`io.wfi.wfiSafe := GatedValidRegNext(no_pending && io.wfi.wfiReq)`，当没有 pending 事务时 safe
- **CMO unit**：有自己的 wfiSafe 信号
- **MissQueue 聚合**：`io.wfi.wfiSafe := (Seq(cmo_unit.io.wfi.wfiSafe) ++ entries.map(_.io.wfi.wfiSafe)).reduce(_&&_)`

#### 3.3.2 Uncache wfiSafe

在 `Uncache.scala` 中：
- wfiReq 时停止发起新的 mem_acquire（`mem_acquire.valid := q0_canSent && !io.wfi.wfiReq`）
- safe 条件：所有 pending 事务清空（`io.wfi.wfiSafe := GatedValidRegNext(noPending.asUInt.andR && io.wfi.wfiReq)`）

#### 3.3.3 StoreQueue wfiSafe

在 `NewStoreQueue.scala` 中：
- `io.wfi.wfiSafe := true.B` — StoreQueue 直接报告 safe（简化处理，不需要等待 store buffer 排空）

#### 3.3.4 L2 TLB (PTW) wfiSafe

在 `L2TLB.scala` 中：
- wfiReq 时暂停 TLB miss 处理和内存请求（`mem.a.valid := mem_arb.io.out.valid && !flush && !wfiReq`）
- safe 条件：无等待响应的 TLB miss（`io.wfi.wfiSafe := DelayN(wfiReq && !waiting_resp.reduce(_ || _), 1)`）

### 3.4 wfiSafe 协议总结

| 组件 | wfiSafe 条件 | 位置 |
|------|-------------|------|
| ICache MSHR | 所有 MSHR entry 无活跃事务 | `ICacheMshr.scala:144` |
| ICache MissUnit | 所有 MSHR safe AND | `ICacheMissUnit.scala:264` |
| InstrUncache | 所有 entry 不在 RefillResp 状态 | `InstrUncacheImp.scala:98` |
| Frontend | ICache && InstrUncache safe AND | `Frontend.scala:214` |
| DCache MissQueue | 所有 entry + CMO unit safe AND | `MissQueue.scala:1331` |
| Uncache | 所有 pending 事务清空 | `Uncache.scala:475` |
| StoreQueue | 直接 safe | `NewStoreQueue.scala:2035` |
| L2TLB (PTW) | 无等待响应的 TLB miss | `L2TLB.scala:392` |
| MemBlock | DCache && Uncache && LSQ && PTW safe AND | `MemBlock.scala:556` |
| Rob | safeFromMem && safeFromFrontend | `Rob.scala:450` |

---

## 4. 4-State WFI Clock-Gating FSM

当 `WFIClockGate` 参数为 `true` 时（在 `SoCParams` 中配置，默认 `false`），XiangShan 在 SoC 顶层 (`XSNoCTop.scala`) 实现了一个 4 状态的 WFI 时钟门控 FSM，由 `WfiStateNext` object 定义（位于 `utils/LowPowerState.scala`）。

### 4.1 FSM 状态定义

```scala
val sNORMAL :: sGCLOCK :: sAWAKE :: sFLITWAKE :: Nil = Enum(4)
```

| 状态 | 含义 |
|------|------|
| `sNORMAL` | 正常工作状态，核心时钟正常运行 |
| `sGCLOCK` | Gated Clock 状态，核心时钟被门控（WFI 生效） |
| `sAWAKE` | 唤醒状态，检测到中断后正在恢复 |
| `sFLITWAKE` | Flit 唤醒状态，检测到 CHI snoop/rsp/dat flit 后正在恢复 |

### 4.2 状态转移逻辑

```scala
(wfiState === sNORMAL && isWFI && isNormal && !intSrc.orR) -> sGCLOCK,
(wfiState === sGCLOCK && intSrc.orR)                       -> sAWAKE,
(wfiState === sGCLOCK && flitpend)                         -> sFLITWAKE,
(wfiState === sFLITWAKE && ~isWFI)                         -> sNORMAL,
(wfiState === sAWAKE && !intSrc.orR)                       -> sNORMAL
```

**状态转移说明**：
1. `sNORMAL -> sGCLOCK`：当 core 处于 WFI 状态（`isWFI`）、低功耗 FSM 处于 IDLE（`isNormal`）且无中断源（`!intSrc.orR`）时，进入时钟门控状态。
2. `sGCLOCK -> sAWAKE`：当检测到任何中断源有效（`intSrc.orR`）时，进入唤醒状态。
3. `sGCLOCK -> sFLITWAKE`：当检测到 CHI 总线上有 pending flit（`flitpend = rx.snp.flitpend | rx.rsp.flitpend | rx.dat.flitpend`）时，进入 flit 唤醒状态。
4. `sFLITWAKE -> sNORMAL`：当 WFI 状态退出（`~isWFI`）时，回到正常状态。
5. `sAWAKE -> sNORMAL`：当中断源全部清除时，回到正常状态。

### 4.3 时钟门控输出

```scala
wfiGateClock := (wfiState === sGCLOCK)
```

最终的核心时钟使能信号为：
```scala
val cpuClockEn = !wfiGateClock && !(cpuReset_sync.asBool)
```

即：**当处于 sGCLOCK 状态或 SoC 复位 CPU 时，核心时钟被门控**。最终通过 `ClockGate` 模块产生门控时钟供 core+L2 使用。

### 4.4 前提条件

WFI clock-gating FSM 仅在 `lpState === sIDLE` 时有效（`isNormal = lpState === sIDLE`），即低功耗掉电 FSM 处于空闲状态时，WFI 时钟门控才能生效。这保证了两套 FSM 之间不会产生冲突。

---

## 5. 8 路异步中断唤醒源

在 `XSNoCTop.scala` 的 `buildLowPower` 方法中，WFI 时钟门控 FSM 需要监听 8 个异步中断源来决定是否唤醒核心。所有中断源均通过 `AsyncResetSynchronizerShiftReg` 进行 3 级异步同步：

```scala
val msip  = AsyncResetSynchronizerShiftReg(msip_mux, 3, 0)   // Machine Software Interrupt Pending
val mtip  = AsyncResetSynchronizerShiftReg(mtip_mux, 3, 0)   // Machine Timer Interrupt Pending
val meip  = AsyncResetSynchronizerShiftReg(plic.head(0), 3, 0)  // Machine External Interrupt
val seip  = AsyncResetSynchronizerShiftReg(plic.last(0), 3, 0)  // Supervisor External Interrupt
val nmi_31 = AsyncResetSynchronizerShiftReg(nmi.head(0), 3, 0) // NMI source 31
val nmi_43 = AsyncResetSynchronizerShiftReg(nmi.head(1), 3, 0) // NMI source 43
val debugIntr = AsyncResetSynchronizerShiftReg(debug.head(0), 3, 0) // Debug Interrupt
val msi_info_vld = AsyncResetSynchronizerShiftReg(core_with_l2.module.io.msiInfo.valid, 3, 0) // IMSIC MSI valid
```

最终汇聚为：
```scala
val intSrc = Cat(msip, mtip, meip, seip, nmi_31, nmi_43, debugIntr, msi_info_vld)
```

其中 `intSrc.orR` 用于 WFI FSM 的唤醒判定。

### 5.1 中断源详细说明

| 序号 | 信号 | 来源 | 说明 |
|------|------|------|------|
| 0 | `msi_info_vld` | IMSIC bus | IMSIC MSI 消息中断有效 |
| 1 | `debugIntr` | debug module | 调试中断 |
| 2 | `nmi_43` | NMI controller | 不可屏蔽中断 #43 |
| 3 | `nmi_31` | NMI controller | 不可屏蔽中断 #31 |
| 4 | `seip` | PLIC | Supervisor 外部中断 |
| 5 | `meip` | PLIC | Machine 外部中断 |
| 6 | `mtip` | CLINT/Private Timer | Machine 定时器中断 |
| 7 | `msip` | CLINT | Machine 软件中断 |

### 5.2 msip/mtip 源选择

`msip_mux` 和 `mtip_mux` 的来源取决于 `UsePrivateClint` 配置：
- `false`：来自 SoC 级 CLINT（`clintIntNode`）
- `true`：来自 XSTile 内部私有 Timer（`timer.get.intnode`）

---

## 6. mcorepwr CSR (POWER_DOWN_ENABLE)

### 6.1 寄存器定义

`mcorepwr` 是一个 Machine-level Custom CSR，地址为 `0xBC0`（见 `CSRCustom.scala`）：

```scala
class McorepwrBundle extends CSRBundle {
  val POWER_DOWN_ENABLE = RW(0).withReset(false.B).withDescription("Enable core power-down requests.")
}
```

- **POWER_DOWN_ENABLE** (bit 0)：默认 `false.B`（reset 后核心掉电功能关闭）
- 读写属性：RW（软件可读写）

### 6.2 信号传递路径

从 CSR 到物理层的信号传递链：

1. **NewCSR** (`NewCSR.scala:1437`)：
   ```scala
   io.status.custom.power_down_enable := mcorepwr.regOut.POWER_DOWN_ENABLE.asBool
   ```

2. **CSRBundle** (`CSRBundles.scala:215`)：
   ```scala
   val power_down_enable = Output(Bool())
   ```

3. **CSRWrapper** (`CSR.scala:381`)：
   ```scala
   custom.power_down_enable := csrMod.io.status.custom.power_down_enable
   ```

4. **MemBlock** (`MemBlock.scala:1432`)：
   ```scala
   io.outer_power_down_en := io.ooo_to_mem.csrCtrl.power_down_enable
   ```

5. **XSCore** (`XSCore.scala:245`)：
   ```scala
   io.power_down_en := memBlock.io.outer_power_down_en
   ```

该信号最终用于控制 SoC 级的电源域开关。

---

## 7. mflushpwr CSR (FLUSH_L2_ENABLE, L2_FLUSH_DONE)

### 7.1 寄存器定义

`mflushpwr` 是另一个 Machine-level Custom CSR，地址为 `0xBC1`（见 `CSRCustom.scala`）：

```scala
class MflushpwrBundle extends CSRBundle {
  val FLUSH_L2_ENABLE = RW(0).withReset(false.B).withDescription("Enable L2 cache flush requests.")
  val L2_FLUSH_DONE   = RO(1).withReset(false.B).withDescription("Indicates that the L2 cache flush has completed.")
}
```

- **FLUSH_L2_ENABLE** (bit 0)：RW，软件写 1 启动 L2 cache flush
- **L2_FLUSH_DONE** (bit 1)：RO，硬件只读，指示 L2 flush 已完成

### 7.2 L2 Flush Done 的硬件反馈

`mflushpwr` CSR 混入了 `HasMachineFlushL2Bundle` trait：

```scala
val mflushpwr = Module(new CSRModule("Mflushpwr", new MflushpwrBundle)
  with HasMachineFlushL2Bundle
{
  regOut.L2_FLUSH_DONE := l2FlushDone
})
```

`l2FlushDone` 信号来自 SoC 顶层，通过 `io.fromTop.l2FlushDone` 传入 NewCSR：

```scala
case m: HasMachineFlushL2Bundle =>
  m.l2FlushDone := io.fromTop.l2FlushDone
```

### 7.3 L2 Flush 信号传递

从 CSR 到 L2 cache 的 flush 信号传递链：

1. **NewCSR** (`NewCSR.scala:1439`)：
   ```scala
   io.status.custom.flush_l2_enable := mflushpwr.regOut.FLUSH_L2_ENABLE.asBool
   ```

2. 该信号经 CSRBundle -> CSRWrapper -> XSTile -> L2Top 传递：
   - `L2Top.scala:313`：`l2.io.l2Flush.foreach { _ := io.l2_flush_en.getOrElse(false.B) }`
   - `L2Top.scala:312`：`io.l2_flush_done.foreach { _ := l2.io.l2FlushDone.getOrElse(false.B) }`

3. L2 flush 完成后，`l2FlushDone` 信号反向传递回 CSR，使 `L2_FLUSH_DONE` 位变为 1。

### 7.4 Difftest 支持

`mflushpwr` 的状态变化还通过 Difftest 框架追踪：

```scala
diffCustomMflushpwr.valid := RegNext(io.fromTop.l2FlushDone) =/= io.fromTop.l2FlushDone
diffCustomMflushpwr.l2FlushDone := io.fromTop.l2FlushDone
```

---

## 8. 7-State Core Low-Power FSM (lpStateNext)

当 `EnablePowerDown` 为 `true` 时，XiangShan 在 SoC 顶层实现了一个 7 状态的核心掉电 FSM。该 FSM 负责协调 L2 flush、WFI 确认、CHI 协议退出和物理断电请求的完整流程。

### 8.1 FSM 状态定义

由 `lpStateNext` object 定义（位于 `utils/LowPowerState.scala`）：

```scala
val sIDLE :: sL2FLUSH :: sWAITWFI :: sEXITCO :: sWAITQ :: sQREQ :: sPOFFREQ :: Nil = Enum(7)
```

| 状态 | 含义 |
|------|------|
| `sIDLE` | 空闲状态，核心正常工作 |
| `sL2FLUSH` | L2 cache 正在 flush |
| `sWAITWFI` | L2 flush 完成，等待核心进入 WFI |
| `sEXITCO` | 核心已 WFI，正在退出 CHI coherence domain |
| `sWAITQ` | 等待 CHI QACTIVE 清除 |
| `sQREQ` | 发出 CHI 退出请求 (QREQ) |
| `sPOFFREQ` | 发出物理断电请求 (power-off request) |

### 8.2 状态转移逻辑

```scala
(lpState === sIDLE && l2flush)          -> sL2FLUSH,
(lpState === sL2FLUSH && l2FlushDone)   -> sWAITWFI,
(lpState === sWAITWFI && isWFI)         -> sEXITCO,
(lpState === sEXITCO && exitco)         -> sWAITQ,
(lpState === sWAITQ && !QACTIVE)        -> sQREQ,
(lpState === sQREQ && !QACCEPTn)        -> sPOFFREQ
```

### 8.3 状态转移详细说明

1. **sIDLE -> sL2FLUSH**：软件通过 `mflushpwr.FLUSH_L2_ENABLE` 启动 L2 flush（`l2flush` 信号经 `AsyncResetSynchronizerShiftReg` 同步后送入 FSM）。
2. **sL2FLUSH -> sWAITWFI**：L2 flush 完成（`l2FlushDone` 反馈为 true）。
3. **sWAITWFI -> sEXITCO**：核心执行 WFI 指令并进入安全状态（`isWFI` 由 Rob 输出的 `cpu_wfi` 经同步后提供）。
4. **sEXITCO -> sWAITQ**：CHI coherence 退出完成（`exitco = !syscoreq && !sync_chi_syscoack`）。
5. **sWAITQ -> sQREQ**：QACTIVE 清除（当前硬连线为 false）。
6. **sQREQ -> sPOFFREQ**：SoC 接受退出请求（`!QACCEPTn`）。

### 8.4 物理断电请求输出

```scala
cpu_no_op := lpState === sPOFFREQ
io.lp.foreach { lp => lp.o_cpu_no_op := cpu_no_op }
```

当 FSM 到达 `sPOFFREQ` 状态时，向 SoC 发出 `o_cpu_no_op` 信号，通知 SoC 核心已准备好物理断电。

### 8.5 电源门控时钟

在 `sPOFFREQ` 状态期间，核心时钟也被门控：

```scala
val pwrdownGateClock = RegInit(false.B)
pwrdownGateClock := cpuReset && lpState === sPOFFREQ
```

---

## 9. PowerSwitchBuffer PD Flow Placeholder

### 9.1 模块定义

`PowerSwitchBuffer` 是一个占位模块（位于 `utils/PowerSwitchBuffer.scala`），用于物理断电握手：

```scala
class PowerSwitchBuffer extends RawModule {
  val sleep = dontTouch(IO(Input(Bool())))
  val ack = dontTouch(IO(Output(Bool())))
  ack := sleep
}
```

注释明确指出："An empty module. Should be replaced by PD flow."（空模块，应被实际 PD flow 替换）。

### 9.2 在 XSTileWrap 中的实例化

在 `XSTileWrap.scala`（第 167-170 行）：

```scala
io.pwrdown_ack_n zip io.pwrdown_req_n foreach { case (ack, req) =>
  val powerSwitchBuffer = Module(new PowerSwitchBuffer)
  ack := powerSwitchBuffer.ack
  powerSwitchBuffer.sleep := req
}
```

- `sleep` 输入连接到 SoC 的 `pwrdown_req_n`（低有效断电请求）
- `ack` 输出连接到 `pwrdown_ack_n`（低有效断电确认）

在 placeholder 实现中，`ack` 直接跟随 `sleep`，即 SoC 发出断电请求后立即收到确认。在实际生产实现中，这里应该包含电源开关控制逻辑，包括：
- 电源域的上电/断电序列
- Isolation cell 的使能控制
- 电源开关延迟的处理
- 断电后的保持状态管理

### 9.3 物理断电握手接口

断电握手在 `XSNoCTop.scala` 中完成连接：

```scala
val soc_pwrdown_n = io.lp.map(_.i_cpu_pwrdown_req_n).getOrElse(true.B)
io.lp.foreach { lp => lp.o_cpu_pwrdown_ack_n := core.io.pwrdown_ack_n.getOrElse(true.B) }
```

---

## 10. SoC-Level LP (Low Power) Interface

### 10.1 LowPowerIO Bundle 定义

SoC 与 CPU 之间的低功耗接口定义在 `Bundle.scala`（第 808-817 行）：

```scala
class LowPowerIO(implicit p: Parameters) extends Bundle {
  /* i_*: SoC -> CPU   o_*: CPU -> SoC */
  val o_cpu_no_op = Output(Bool())          // CPU -> SoC: 核心无操作，可断电
  // physical power down
  val i_cpu_pwrdown_req_n = Input(Bool())   // SoC -> CPU: 物理断电请求（低有效）
  val o_cpu_pwrdown_ack_n = Output(Bool())  // CPU -> SoC: 物理断电确认（低有效）
  // power on/off sequence control for Core iso/rst
  val i_cpu_iso_en = Input(Bool())          // SoC -> CPU: 隔离使能
  val i_cpu_sw_rst_n = Input(Bool())        // SoC -> CPU: 软件复位（低有效）
}
```

### 10.2 LP 接口的条件化

LP 接口仅在 `EnablePowerDown = true` 时存在：

```scala
val lp = Option.when(socParams.EnablePowerDown)(IO(new LowPowerIO))
```

所有与 LP 相关的信号都通过 `Option.map/getOrElse` 进行条件化处理：
- 不存在时使用默认值（如 `i_cpu_pwrdown_req_n` 默认为 `true.B`，即无断电请求）
- `iso_en` 和 `pwrdown_req_n` 在 `XSTileWrap` 中也有对应的 Optional IO

### 10.3 SoC 控制序列

SoC 通过以下信号序列控制核心的上电/下电流程：

1. **下电序列**：
   - `o_cpu_no_op` (CPU->SoC)：核心已安全，可下电
   - `i_cpu_pwrdown_req_n` (SoC->CPU)：SoC 发出物理断电请求
   - `o_cpu_pwrdown_ack_n` (CPU->SoC)：核心确认断电

2. **上电序列**：
   - `i_cpu_iso_en` (SoC->CPU)：使能隔离
   - `i_cpu_sw_rst_n` (SoC->CPU)：释放复位

3. **核心复位**：
   ```scala
   val soc_rst_n = io.lp.map(_.i_cpu_sw_rst_n).getOrElse(true.B)
   val soc_iso_en = io.lp.map(_.i_cpu_iso_en).getOrElse(false.B)
   val cpuReset = reset.asBool || !soc_rst_n
   ```

### 10.4 iso_en 与 pwrdown_req_n 在核心内的连接

在 `XSNoCTop.scala` 中：
```scala
core.io.iso_en.foreach { _ := io.lp.map(_.i_cpu_iso_en).getOrElse(false.B) }
core.io.pwrdown_req_n.foreach { _ := io.lp.map(_.i_cpu_pwrdown_req_n).getOrElse(true.B) }
```

这些信号最终传入 `XSTileWrap`，用于控制 PowerSwitchBuffer 和核心的隔离/电源开关逻辑。

---

## 11. 配置参数总结

### 11.1 SoCParams 中的低功耗参数

```scala
case class SoCParams(
  WFIClockGate: Boolean = false,     // WFI 时钟门控使能
  EnablePowerDown: Boolean = false   // 核心掉电功能使能
)
```

### 11.2 XSTile 参数

```scala
case class XSTileParams(
  wfiResume: Boolean = true          // WFI 超时恢复使能
)
```

### 11.3 YAML 配置支持

在 `YamlParser.scala` 中，这些参数可通过 YAML 配置文件设置：
```yaml
WFIClockGate: true
EnablePowerDown: true
```

---

## 12. 源文件索引

### 12.1 WFI 核心逻辑

| 文件 | 功能 |
|------|------|
| `src/main/scala/xiangshan/backend/fu/NewCSR/CSRCustom.scala` | srnctl/mcorepwr/mflushpwr CSR 定义 |
| `src/main/scala/xiangshan/backend/fu/NewCSR/NewCSR.scala` | CSR 读写逻辑、custom status 输出 |
| `src/main/scala/xiangshan/backend/rob/Rob.scala` | WFI 指令检测、wfiSafe 聚合、cpu_wfi 输出 |
| `src/main/scala/xiangshan/backend/CtrlBlock.scala` | wfiReq/wfiSafe 信号分发 |
| `src/main/scala/xiangshan/backend/fu/CSR.scala` | WFI event 信号、CSR wrapper |
| `src/main/scala/xiangshan/Bundle.scala` | LowPowerIO、CsrCtrlBundle 定义 |

### 12.2 分布式 wfiSafe 协议

| 文件 | 功能 |
|------|------|
| `src/main/scala/xiangshan/frontend/Frontend.scala` | 前端 wfiSafe 聚合 |
| `src/main/scala/xiangshan/frontend/icache/ICacheMissUnit.scala` | ICache miss 单元 wfiSafe |
| `src/main/scala/xiangshan/frontend/icache/ICacheMshr.scala` | ICache MSHR wfiSafe |
| `src/main/scala/xiangshan/frontend/instruncache/InstrUncacheImp.scala` | 指令 uncache wfiSafe 聚合 |
| `src/main/scala/xiangshan/frontend/instruncache/InstrUncacheEntry.scala` | 指令 uncache entry wfiSafe |
| `src/main/scala/xiangshan/mem/MemBlock.scala` | 内存子系统 wfiSafe 聚合 |
| `src/main/scala/xiangshan/cache/dcache/mainpipe/MissQueue.scala` | DCache MissQueue wfiSafe |
| `src/main/scala/xiangshan/cache/dcache/Uncache.scala` | 数据 Uncache wfiSafe |
| `src/main/scala/xiangshan/mem/lsqueue/NewStoreQueue.scala` | StoreQueue wfiSafe |
| `src/main/scala/xiangshan/cache/mmu/L2TLB.scala` | L2TLB/PTW wfiSafe |

### 12.3 低功耗 FSM 与时钟门控

| 文件 | 功能 |
|------|------|
| `src/main/scala/utils/LowPowerState.scala` | WfiStateNext (4-state) + lpStateNext (7-state) FSM |
| `src/main/scala/utils/PowerSwitchBuffer.scala` | 物理断电开关占位模块 |
| `src/main/scala/top/XSNoCTop.scala` | SoC 顶层 FSM 实例化、时钟门控、中断源收集 |
| `src/main/scala/system/SoC.scala` | WFIClockGate/EnablePowerDown 参数定义 |
| `src/main/scala/xiangshan/XSTileWrap.scala` | XSTile 封装、PowerSwitchBuffer 实例化 |
| `src/main/scala/xiangshan/L2Top.scala` | L2 flush 信号传递 |
| `src/main/scala/xiangshan/XSTile.scala` | Core tile 低功耗 IO 定义 |
| `src/main/scala/xiangshan/XSCore.scala` | Core power_down_en 输出 |
| `src/main/scala/top/YamlParser.scala` | YAML 配置解析 |
| `src/main/scala/top/ArgParser.scala` | 命令行参数解析 |

---

## 13. 完整低功耗流程总结

### 13.1 WFI 时钟门控流程（轻量级）

```
软件写 srnctl.WFI_ENABLE=1
  -> 核心执行 WFI 指令
  -> Rob 检测到 isWFI，设置 hasWFI
  -> 广播 wfiReq 到所有子系统
  -> 等待所有子系统回报 wfiSafe
  -> cpu_wfi = hasWFI && wfiSafe
  -> WFI FSM: sNORMAL -> sGCLOCK
  -> wfiGateClock = true
  -> cpuClockEn = false，核心时钟门控
  -> 中断/flit 到达
  -> WFI FSM: sGCLOCK -> sAWAKE/sFLITWAKE -> sNORMAL
  -> 时钟恢复
```

### 13.2 核心掉电流程（重量级）

```
软件写 mflushpwr.FLUSH_L2_ENABLE=1
  -> LP FSM: sIDLE -> sL2FLUSH
  -> L2 cache 开始 flush
  -> L2 flush 完成，mflushpwr.L2_FLUSH_DONE=1
  -> LP FSM: sL2FLUSH -> sWAITWFI
  -> 核心执行 WFI 指令
  -> LP FSM: sWAITWFI -> sEXITCO
  -> 退出 CHI coherence domain
  -> LP FSM: sEXITCO -> sWAITQ -> sQREQ
  -> SoC 接受退出请求
  -> LP FSM: sQREQ -> sPOFFREQ
  -> o_cpu_no_op = true，通知 SoC
  -> pwrdownGateClock = true，核心时钟门控
  -> SoC 发出 i_cpu_pwrdown_req_n
  -> PowerSwitchBuffer 处理（当前为直通）
  -> 返回 o_cpu_pwrdown_ack_n
  -> SoC 断电/上电序列
  -> i_cpu_iso_en / i_cpu_sw_rst_n 控制隔离与复位
```

---

## 14. 设计特点与注意事项

1. **两级低功耗机制**：WFI clock-gating（轻量级，仅门控时钟）与 power-down（重量级，涉及 L2 flush、CHI 退出、物理断电）可独立或组合使用。

2. **分布式 wfiSafe 协议**：确保所有异步事务在进入低功耗前安全完成，避免数据丢失或协议违规。

3. **CHI 协议集成**：低功耗 FSM 与 CHI 协议紧密集成，通过 `syscoreq/syscoack`、`QACTIVE/QACCEPTn` 等信号完成 coherence domain 退出。

4. **异步时钟域处理**：所有跨时钟域信号均使用 `AsyncResetSynchronizerShiftReg`（3 级同步）处理，确保信号稳定性。

5. **可选配置**：`WFIClockGate` 和 `EnablePowerDown` 均为可选功能，可根据 SoC 需求灵活启停。

6. **PowerSwitchBuffer 占位**：当前 `PowerSwitchBuffer` 为直通实现，实际部署需替换为包含电源开关控制逻辑的完整 PD flow。

7. **WFI 超时保护**：Rob 内置 1M cycle 的 WFI 超时计数器，防止中断丢失导致核心永久挂起。
