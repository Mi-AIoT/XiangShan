# R35 - Power & Clock Management 深度研究报告

> 本报告对 XiangShan RISC-V 处理器中的电源与时钟管理机制进行系统性的分析与阐述，涵盖时钟门控（Clock Gating）、门控寄存器、时钟多路复用器、复位生成、WFI 低功耗模式、核心电源管理、L2 缓存刷新与下电、异步时钟域跨越以及 DFT/MBIST 支持等关键技术领域。

---

## 1. Clock Gating Strategy（时钟门控策略）

### 1.1 基础时钟门控单元 (ClockGate)

XiangShan 的时钟门控基础设施建立在 `utility/src/main/scala/utility/ClockGate.scala` 中定义的 `ClockGate` 类之上。该模块以 BlackBox 形式封装，内嵌了标准的基于 latch 的门控时钟 (Integrated Clock Gating Cell, ICG) SystemVerilog 实现：

```verilog
module ClockGate (
  input  wire TE,   // Test Enable
  input  wire E,    // Enable
  input  wire CK,   // Clock input
  output wire Q     // Gated clock output
);
  reg EN;
  always_latch begin
    if(!CK) EN = TE | E;
  end
  assign Q = CK & EN;
endmodule
```

其工作原理为：在时钟低电平期间，通过 latch 捕获 `TE | E` 的值；在时钟高电平期间，门控信号 `EN` 保持不变，最终输出为 `CK & EN`。这种设计确保了门控时钟不会产生毛刺（glitch），因为 latch 仅在时钟低电平期间采样，而与逻辑仅在时钟高电平期间生效。

`ClockGate` 的 Scala 伴生对象 (companion object) 提供了工厂方法 `apply(TE, E, CK)` 用于快速创建门控时钟实例，其中：
- `TE` (Test Enable)：测试使能信号，用于 DFT/MBIST 模式下绕过门控逻辑，强制时钟开启
- `E` (Enable)：正常功能模式下的时钟使能信号
- `CK`：原始输入时钟

### 1.2 集中式 TE 信号管理 (ClockGate.genTeSrc / genTeSink)

为了高效管理分散在芯片各处的时钟门控单元的 Test Enable 信号，XiangShan 设计了一套全局化的 TE 信号广播机制。`ClockGate` 伴生对象维护了一个 `teQueue`（Scala mutable queue），用于收集所有需要 TE 信号的门控单元：

- **`genTeSink`**：在各子模块中调用，创建一个 `ClockGateTeBundle`（包含 `cgen` 信号），并将其入队到全局 `teQueue` 中。每个调用点产生一个 sink 端口。
- **`genTeSrc`**：在顶层（如 `Backend.scala` 和 `MemBlock.scala`）调用，遍历 `teQueue` 中所有已注册的 sink，通过 `BoringUtils.bore()` 将所有 sink 连接到同一个全局 TE 源，然后清空队列。

这种设计实现了一次声明、全局广播的 TE 信号管理范式，使得 DFT 工程师可以在顶层统一控制所有门控时钟单元的使能状态。

### 1.3 功能执行单元级门控 (ExeUnit Clock Gating)

在 `xiangshan/backend/exu/ExeUnit.scala` 中，当 `EnableClockGate` 参数为 true 时，每个功能单元 (FU) 都会获得独立的门控时钟。其门控逻辑基于 FU 的流水线状态精确计算：

- **lat0（零延迟 FU）**：当输入握手成功 (`fu.io.in.fire`) 时开启时钟
- **latN（N 周期延迟 FU）**：构建 `fuVldVec`（有效信号向量）追踪流水线各级状态，当流水线中有任何有效数据时开启时钟
- **uncerLat（不确定延迟 FU）**：在输入 fire 到输出 fire 期间保持时钟开启
- **ckAlwaysEn（始终开启）**：对于需要持续运行的 FU（如某些计时器或调试单元），强制 `clk_en := true.B`

最终通过 `fu.clock := ClockGate(ClockGate.genTeSink.cgen, clk_en, clock)` 将门控时钟连接到 FU 的时钟端口。

### 1.4 EnableClockGate 全局配置

`EnableClockGate` 是 `XSCoreParams` 中的全局配置参数，默认值为 `true`（定义于 `xiangshan/Parameters.scala:75`）。该参数控制整个处理器核心级别的门控时钟策略是否启用，是一个 compile-time 常量，在设计实例化时确定。

---

## 2. Gated Register Implementation（门控寄存器实现）

`utility/src/main/scala/utility/ClockGatedReg.scala` 定义了一系列门控寄存器工具，这些是时钟门控策略在寄存器级的延伸应用。其核心思想是：**当寄存器的值不会发生变化时，不翻转其时钟，从而节省动态功耗**。

### 2.1 GatedValidRegNext

最基础的门控寄存器单元。代码注释指出 EDA 工具插入的门控时钟单元最小宽度通常为 3-bit，因此对于单 bit 信号使用门控时钟并无实际收益，直接回退为普通 `RegNext`。

对于多 bit 的 `Vec[Bool]`，使用 `RegEnable` 配合 `last.asUInt =/= next.asUInt` 条件：**仅当新值与旧值不同时才使能寄存器更新**。这意味着当信号保持不变时，寄存器的时钟被有效地"门控"掉了。

### 2.2 GatedRegNext

扩展版门控寄存器，支持任意宽度的 `Data` 类型。关键特性：

- 对 `Vec` 类型：逐元素独立判断是否需要更新，每个元素有独立的使能条件
- 对标量类型：通过 `asUInt =/= next.asUInt` 比较实现整体门控
- 支持可选的初始值 (`initOpt`)
- 代码中有明确的时序警告：`"The larger Data width, the longer time of =/= operations, which may lead to timing violations."` — 宽数据的比较操作本身可能成为关键路径

### 2.3 GatedRegEnable

在 GatedRegNext 的基础上增加了外部使能端口 `enable`。最终使能条件为 `enable && last.asUInt =/= next.asUInt`，即同时满足外部使能和值发生变化两个条件。

还提供了 `dupRegs` 方法用于复制寄存器场景，其中所有复制副本共享同一个更新使能条件（基于第一个元素的比较结果）。

### 2.4 SegmentedAddr 系列

文件中还包含 `SegmentedAddr`、`SegmentedAddrInit` 和 `SegmentedAddrNext` 等辅助类，用于将地址分段管理。每个地址段独立判断是否发生变化，未变化的段不更新寄存器，从而在地址追踪场景中节省功耗。这在 TLB、FTQ 等需要追踪地址变化的模块中被广泛使用。

---

## 3. Clock Multiplexer（时钟多路复用器）

`utility/src/main/scala/utility/ClockMux.scala` 定义了一个简洁的时钟多路复用器 `ClockMux`：

```scala
class ClockMux extends RawModule {
  val clk0   = IO(Input(Clock()))
  val clk1   = IO(Input(Clock()))
  val sel    = IO(Input(Bool()))
  val clkout = IO(Output(Clock()))
  clkout := Mux(sel, clk1, clk0)
}
```

这是一个 RTL 级别的基本时钟切换器，使用 Chisel 的 `RawModule`（不插入自动复位）。当前实现直接使用 `Mux` 进行选择，在综合阶段会映射到工艺库中的 glitch-free clock multiplexer cell。

**需要注意的是**，此处的 RTL 实现本身并不保证 glitch-free 切换——这一特性依赖于后端综合工具对 `Mux` 在时钟路径上的特殊处理，或在物理实现阶段替换为带有同步器的 glitch-free clock mux cell。在实际 SoC 集成中，通常需要在 sel 信号切换前确保两个时钟源处于已知相位关系，或使用专门的时钟切换控制器。

---

## 4. Reset Generation and Management（复位生成与管理）

### 4.1 DFTResetSignals 与 ResetGen

`utility/src/main/scala/utility/ResetGen.scala` 实现了 XiangShan 的复位同步化基础设施。

**DFTResetSignals** 接口包含三个信号：
- `lgc_rst_n` (AsyncReset)：来自 SoC 级别的低有效复位
- `mode` (Bool)：DFT 模式选择，为 true 时使用 `lgc_rst_n` 作为复位源
- `scan_mode` (Bool)：扫描链模式，在 scan 模式下直接使用 `lgc_rst_n` 跳过同步

**ResetGen** 模块的核心逻辑：
1. 根据 `dft.mode` 选择复位源：DFT 模式用 `lgc_rst_n`，正常模式用系统 `reset`
2. 通过移位寄存器链 (`SYNC_NUM` 默认为 3) 实现异步复位的同步释放（reset synchronizer）
3. `scan_mode` 下旁路同步逻辑，直接输出原始复位

```scala
private val real_reset = Mux(dft.mode, lgc_rst, reset.asBool).asAsyncReset
// ...
o_reset := Mux(dft.scan_mode, lgc_rst, raw_reset.asBool).asAsyncReset
```

### 4.2 树形复位分发 (Reset Tree)

`ResetGen` 伴生对象提供了多种复位分发模式：

1. **简单模式** `apply(SYNC_NUM, dft)`：生成一个复位同步器实例，返回同步化后的复位
2. **树形模式** `apply(resetTree, reset, sim, dft)`：递归遍历 `ResetGenNode` 树结构，每一层插入一个新的 `ResetGen` 实例，实现多级复位同步。这允许不同模块组之间有独立的复位相位
3. **链式模式** `apply(resetChain, reset, sim, dft)`：按 `Seq[Seq[Module]]` 的层级结构，逐级生成复位同步器。每一级的输出作为下一级的输入，实现级联复位

### 4.3 SoC 级复位集成

在 `XSNoCTop.scala` 和 `XSTileWrap.scala` 中，复位管理与电源管理紧密耦合：

```scala
val cpuReset = reset.asBool || !soc_rst_n  // SoC复位 或 外部硬件复位
val cpuReset_sync = withClockAndReset(clock, cpuReset.asAsyncReset)(ResetGen(io.dft_reset))
```

多个时钟域有各自独立的复位同步器：
- `cpuReset_sync`：CPU+L2 时钟域复位
- `noc_reset_sync`：NoC 时钟域复位（通过 CHI Async Bridge）
- `soc_reset_sync`：SoC 总线时钟域复位
- `clint_reset_sync`：CLINT 时钟域复位

---

## 5. WFI (Wait For Interrupt) Power Saving

### 5.1 WFI 指令的前端处理

当处理器执行 WFI 指令时（通过 CSR 模块识别 `CSROpType.wfi`），信号沿以下路径传播：

1. **CSR 层面**：`srnctl.WFI_ENABLE` 控制 WFI 指令是否可用（默认开启），且仅在非 single-step 且非 debug mode 时生效
2. **Backend**：通过 `CsrMod.io.status.custom.wfi_enable` 将控制信号传递到前端
3. **MemBlock**：`io.wfi.wfiReq` 信号分发到所有内存子模块（dcache miss queue、LSQ、PTW、uncache）
4. **XSCore**：`io.cpu_wfi` 汇总来自 MemBlock 的 WFI 状态

### 5.2 WFI 安全性检查 (wfiSafe)

进入 WFI 状态前，必须确保所有内存操作已完成。这是一个分布式安全协议：

- **MissQueue**：`wfi.wfiSafe := GatedValidRegNext(no_pending && wfiReq)` — 无未完成的缓存缺失请求
- **StoreQueue**：始终为 safe（`wfiSafe := true.B`），因为没有其他未提交指令可能先于 WFI
- **InstrUncacheEntry**：`wfi.wfiSafe := state =/= RefillResp` — 无未完成的 MMIO 取指
- **PTW/DCache/LSQ**：各自检查无悬挂请求

所有子模块的 `wfiSafe` 通过 AND 逻辑汇聚：`io.wfi.wfiSafe := dcache.io.wfi.wfiSafe && uncache.io.wfi.wfiSafe && lsq.io.wfi.wfiSafe && ptw.io.wfi.wfiSafe`

当所有子模块都返回 safe 时，处理器才能真正进入 WFI 状态。

### 5.3 WFI 时钟门控状态机 (WfiStateNext)

`utils/LowPowerState.scala` 中定义了 WFI 时钟门控的 4 状态 FSM：

```
sNORMAL --[isWFI && isNormal && !intSrc]--> sGCLOCK
sGCLOCK --[intSrc]--> sAWAKE
sGCLOCK --[flitpend]--> sFLITWAKE
sFLITWAKE --[!isWFI]--> sNORMAL
sAWAKE --[!intSrc]--> sNORMAL
```

- **sNORMAL**：正常运行状态
- **sGCLOCK**：门控时钟状态，Core+L2 时钟被关闭，仅中断/复位/snoop 可唤醒
- **sAWAKE**：检测到中断后临时唤醒状态
- **sFLITWAKE**：检测到 CHI snoop 请求后临时唤醒状态（`flitpend = rx.snp.flitpend | rx.rsp.flitpend | rx.dat.flitpend`）

### 5.4 WFI 时钟门控的中断源采集

当 `WFIClockGate` 启用时，系统从多个异步中断源采集信号（均通过 3 级异步同步器 `AsyncResetSynchronizerShiftReg`）：

- `msip`：软件中断（Machine Software Interrupt Pending）
- `mtip`：定时器中断（Machine Timer Interrupt Pending）
- `meip`：外部中断（Machine External Interrupt Pending）
- `seip`：Supervisor 外部中断
- `nmi_31`、`nmi_43`：不可屏蔽中断
- `debugIntr`：调试中断
- `msi_info_vld`：MSI 信息有效信号

所有中断源通过 Cat 打包为 `intSrc` 向量，用于驱动 WFI 状态机的转换。

---

## 6. Core Power Management (mcorepwr CSR)

### 6.1 mcorepwr 寄存器 (0xBC0)

定义于 `CSRCustom.scala`，`McorepwrBundle` 包含单个控制位：
- `POWER_DOWN_ENABLE` (RW bit 0，默认 false)：启用核心下电请求

软件写入此 CSR 后，`NewCSR.scala` 将其输出：
```scala
io.status.custom.power_down_enable := mcorepwr.regOut.POWER_DOWN_ENABLE.asBool
```

### 6.2 mflushpwr 寄存器 (0xBC1)

`MflushpwrBundle` 包含：
- `FLUSH_L2_ENABLE` (RW bit 0，默认 false)：启用 L2 缓存刷新请求
- `L2_FLUSH_DONE` (RO bit 1)：只读状态位，指示 L2 刷新完成

### 6.3 信号传播路径

CSR 控制信号通过以下路径传播到硬件执行：

1. `NewCSR` -> `CSR wrapper` -> `Backend.csrCtrl`
2. `csrCtrl.power_down_enable` -> `MemBlock.outer_power_down_en` -> `XSCore.power_down_en`
3. `csrCtrl.flush_l2_enable` -> `MemBlock.outer_l2_flush_en` -> `L2Top.l2_flush_en` -> L2 缓存控制器

`EnablePowerDown` 参数控制这些低功耗相关 IO 端口是否被实例化（通过 `Option.when(EnablePowerDown)` 模式）。

---

## 7. L2 Flush and Power Down

### 7.1 低功耗状态机 (lpStateNext)

`utils/LowPowerState.scala` 中定义了核心级别的完整下电状态机，包含 7 个状态：

```
sIDLE --[l2flush]--> sL2FLUSH
sL2FLUSH --[l2FlushDone]--> sWAITWFI
sWAITWFI --[isWFI]--> sEXITCO
sEXITCO --[exitco]--> sWAITQ
sWAITQ --[!QACTIVE]--> sQREQ
sQREQ --[!QACCEPTn]--> sPOFFREQ
```

流程说明：
1. **sIDLE**：空闲等待，检测到 L2 刷新请求后进入 sL2FLUSH
2. **sL2FLUSH**：等待 L2 缓存刷新完成（`l2FlushDone`）
3. **sWAITWFI**：等待处理器进入 WFI 状态
4. **sEXITCO**：等待 CHI 协议退出 Collaborative Snoop（`exitco = !syscoreq & !syscoack`）
5. **sWAITQ**：等待 NoC 队列清空（`!QACTIVE`）
6. **sQREQ**：发送 QREQ 信号请求关闭
7. **sPOFFREQ**：当 `QACCEPTn` 无效时，设置 `cpu_no_op` 通知 SoC 可以安全下电

### 7.2 Power Switch Buffer

`utils/PowerSwitchBuffer.scala` 定义了一个占位模块 `PowerSwitchBuffer`：

```scala
class PowerSwitchBuffer extends RawModule {
  val sleep = dontTouch(IO(Input(Bool())))
  val ack = dontTouch(IO(Output(Bool())))
  ack := sleep
}
```

这是一个 **设计占位符**（注释明确指出 "Should be replaced by PD flow"），在物理设计流程中会被替换为实际的电源开关控制器。它在 `XSTileWrap.scala` 中被实例化，用于 tile 级别的电源域开关控制。

### 7.3 SoC 级电源管理接口

在 `XSNoCTop.scala` 的 `HasCoreLowPowerImp` trait 中：

- `io.lp.i_cpu_sw_rst_n`：SoC 控制的软件复位（低有效）
- `io.lp.i_cpu_iso_en`：隔离使能信号，用于电源域隔离
- `io.lp.i_cpu_pwrdown_req_n`：SoC 发出的下电请求（低有效）
- `io.lp.o_cpu_pwrdown_ack_n`：Core+L2 确认可以安全下电（低有效）
- `io.lp.o_cpu_no_op`：Core+L2 已无操作，SoC 可以安全断电

### 7.4 下电时钟门控

```scala
val pwrdownGateClock = withClockAndReset(clock, cpuReset_sync.asAsyncReset) {RegInit(false.B)}
pwrdownGateClock := cpuReset && lpState === sPOFFREQ
```

当下电状态机到达 `sPOFFREQ` 且 SoC 复位有效时，`pwrdownGateClock` 被拉高，配合 `cpuReset_sync` 一起关闭核心时钟：

```scala
val cpuClockEn = !wfiGateClock && !(cpuReset_sync.asBool)
ClockGate(false.B, cpuClockEn, clock)
```

最终的门控时钟在两个条件满足时关闭：(1) WFI 门控有效，或 (2) CPU 复位有效（下电流程中）。

---

## 8. Async Clock Domain Crossing（异步时钟域跨越）

### 8.1 CHI Async Bridge

XiangShan 支持通过 CHI (Coherent Hub Interface) 协议的异步桥接器连接到 NoC。配置参数（`SoC.scala`）：

```scala
EnableCHIAsyncBridge: Option[AsyncQueueParams] = Some(AsyncQueueParams(depth = 16, sync = 3, safe = false))
```

- `depth = 16`：异步 FIFO 深度
- `sync = 3`：3 级同步器（与 `AsyncResetSynchronizerShiftReg` 的 SYNC_NUM 一致）
- `safe = false`：非安全模式（不额外插入安全检查逻辑）

在 `XSTileWrap.scala` 中，CHI Async Bridge 的 Source 端位于 tile 侧：
```scala
val source = withClockAndReset(clock, noc_reset_sync.get)(Module(new CHIAsyncBridgeSource(param)))
io.chi <> source.io.async
```

Sink 端位于 `XSNoCTop.scala`：
```scala
val time_sink = Module(new CHIAsyncBridgeSink(param))
time_sink.io.async <> core_with_l2.module.io.chi
```

### 8.2 CLINT Async Bridge

CLINT (Core Local Interruptor) 的定时器信号通过异步桥从总线时钟域跨越到 CPU 时钟域：

```scala
EnableClintAsyncBridge: Option[AsyncQueueParams] = Some(AsyncQueueParams(depth = 8, sync = 3, safe = false))
```

Source 端在 `XSNoCTop` 中：
```scala
val time_source = Module(new AsyncQueueSource(UInt(64.W), param))
time_source.io.async <> core_with_l2.module.io.clintTime
```

Sink 端在 `XSTileWrap` 中：
```scala
val time_sink = withClockAndReset(clock, soc_reset_sync)(Module(new AsyncQueueSink(UInt(64.W), param)))
time_sink.io.async <> io.clintTime
```

### 8.3 Bus Async Bridge

对于 TileLink 总线也有独立的异步桥配置：
```scala
SeperateBusAsyncBridge: Option[AsyncQueueParams] = Some(AsyncQueueParams(depth = 1, sync = 3, safe = false))
```

深度仅为 1，因为总线桥接对延迟更敏感。

### 8.4 异步复位同步器

在跨时钟域场景中，异步复位也经过专门的同步处理。`AsyncResetSynchronizerShiftReg` 被广泛用于同步跨时钟域的控制信号（如 `l2_flush_en`、`isWFI`、中断信号等），确保信号在目标时钟域中稳定。

### 8.5 TimeAsync（设备级异步处理）

`device/TimeAsync.scala` 和 `device/SYSCNT.scala` 实现了系统计数器的异步处理，将 RTC 时钟域的计数器值安全地同步到总线时钟域。

---

## 9. DFT/MBIST Support

### 9.1 DFT 配置参数

`DFTOptions` 类（定义于 `Parameters.scala:541`）：
```scala
case class DFTOptions(
  EnableSramCtl: Boolean = false,
  EnableMbist: Boolean = true,  // 默认启用 MBIST
)
```

通过 `hasDFT = hasMbist || hasSramCtl` 判断是否需要 DFT 信号。

### 9.2 SramBroadcastBundle

`utility/src/main/scala/utility/sram/SramProto.scala` 中定义的 `SramBroadcastBundle` 是 MBIST 系统的核心广播接口，用于从 MBIST 控制器向所有 SRAM 实例广播控制信号。

### 9.3 MbistClockGateCell

`utility/src/main/scala/utility/mbist/MbistClockGateCell.scala` 实现了专用于 MBIST 的时钟门控单元。当 MBIST 测试模式激活时，它可以从 `SramBroadcastBundle` 中提取时钟控制信号 (`fromBroadcast`)，覆盖正常的门控逻辑。

### 9.4 SRAMTemplate 中的 DFT/MBIST 集成

`SRAMTemplate` 是 XiangShan 中所有 SRAM 实例的统一包装器，支持丰富的 DFT 特性：

- `hasMbist`：启用 MBIST 节点，创建 `broadcast` IO
- `extClockGate`：暴露时钟控制信号到 IO，用于外部 MBIST 控制器直接控制门控时钟
- `hasSramCtl`：启用 SRAM 控制器支持
- `withClockGate`：在读写端口分别添加 `MbistClockGateCell`

当 `withClockGate = true` 且非 `extClockGate` 模式时：
```scala
private val implCg = !extClockGate && (extraHold || withClockGate)
private val rcg = if(implCg) Some(Module(new MbistClockGateCell(extraHold))) else None
private val wcg = if(!singlePort && implCg) Some(Module(new MbistClockGateCell(extraHold))) else None
```

读端口和写端口使用独立的时钟门控单元，实现读写分离的功耗管理。

### 9.5 DFT 信号的层级传播

DFT 信号从顶层向下传播的路径：

```
XSNoCTop.io.dft / io.dft_reset
  -> core_with_l2.io.dft / io.dft_reset (L2Top)
    -> l2.io.dft / io.dft_reset (L2 Cache)
    -> core.io.dft / io.dft_reset (XSCore)
      -> memBlock.io.dft (MemBlock)
      -> 各 SRAM 实例的 broadcast 端口
```

在 `ResetGen` 中，`dft.reset` 和 `dft.scan_mode` 信号用于在 DFT 模式下控制复位行为，确保扫描链测试可以正确进行。

### 9.6 Bitmap Check 中的门控时钟与 MBIST

Page Table Cache 的 Bitmap Check 模块展示了门控时钟与 MBIST 的协同工作：

```scala
val te = ClockGate.genTeSink
val bc_masked_clock = ClockGate(te.cgen,
  stageReq.fire | (!flush && io.refill.valid) | mbistBC.map(_.mbist.req).getOrElse(false.B),
  clock)
```

门控条件包含三部分：正常功能请求（`stageReq.fire`）、refill 有效、以及 MBIST 请求——确保 MBIST 测试时不受正常门控逻辑影响。

---

## 10. Key Source File Locations（关键源文件索引）

| 文件路径 | 功能描述 |
|---------|---------|
| `utility/src/main/scala/utility/ClockGate.scala` | 门控时钟单元 BlackBox 与 TE 信号管理 |
| `utility/src/main/scala/utility/ClockGatedReg.scala` | 门控寄存器工具集 (GatedRegNext, GatedValidRegNext 等) |
| `utility/src/main/scala/utility/ClockMux.scala` | 时钟多路复用器 |
| `utility/src/main/scala/utility/ResetGen.scala` | 复位生成器与树形复位分发 |
| `utility/src/main/scala/utility/sram/SRAMTemplate.scala` | SRAM 模板，含 DFT/MBIST/门控时钟集成 |
| `utility/src/main/scala/utility/sram/SramProto.scala` | SramBroadcastBundle 定义 |
| `utility/src/main/scala/utility/mbist/MbistClockGateCell.scala` | MBIST 专用时钟门控单元 |
| `src/main/scala/utils/LowPowerState.scala` | WFI 状态机与低功耗状态机定义 |
| `src/main/scala/utils/PowerSwitchBuffer.scala` | 电源开关占位模块 |
| `src/main/scala/top/XSNoCTop.scala` | SoC 顶层，含完整低功耗管理实现 |
| `src/main/scala/xiangshan/XSTileWrap.scala` | Tile 封装，含电源开关与异步桥集成 |
| `src/main/scala/xiangshan/L2Top.scala` | L2 顶层，含 L2 flush 与 DFT 信号传播 |
| `src/main/scala/xiangshan/XSCore.scala` | 核心顶层，含 WFI/power_down IO |
| `src/main/scala/xiangshan/XSTile.scala` | Tile 定义，含 EnablePowerDown 条件端口 |
| `src/main/scala/xiangshan/backend/exu/ExeUnit.scala` | 执行单元级门控时钟实现 |
| `src/main/scala/xiangshan/backend/fu/NewCSR/CSRCustom.scala` | 自定义 CSR (mcorepwr, mflushpwr, srnctl) |
| `src/main/scala/xiangshan/backend/fu/NewCSR/NewCSR.scala` | CSR 模块主体，含 power_down_enable 输出 |
| `src/main/scala/xiangshan/mem/MemBlock.scala` | 内存块，含 WFI 安全检查与 L2 flush 信号 |
| `src/main/scala/xiangshan/cache/dcache/mainpipe/MissQueue.scala` | Miss 队列中的 WFI 安全检查 |
| `src/main/scala/xiangshan/mem/lsqueue/NewStoreQueue.scala` | Store 队列中的 WFI 安全检查 |
| `src/main/scala/xiangshan/Parameters.scala` | 全局参数定义 (EnableClockGate, DFTOptions 等) |
| `src/main/scala/system/SoC.scala` | SoC 参数定义 (WFIClockGate, AsyncBridge 配置) |
| `src/main/scala/device/SYSCNT.scala` | 系统计数器异步处理 |
| `src/main/scala/device/TimeAsync.scala` | 时钟域跨越的异步处理 |

---

## 总结

XiangShan 的电源与时钟管理架构是一个多层次、精细化的系统设计，从最底层的 ICG cell 到 SoC 级的下电状态机，形成了完整的低功耗管理体系：

1. **门控时钟分层**：全局 `EnableClockGate` -> 功能单元级（ExeUnit）-> SRAM 级（SRAMTemplate withClockGate），实现粒度由粗到细的时钟门控
2. **WFI 深度节能**：通过 4 状态 FSM 控制核心时钟门控，配合 8 路异步中断源同步，实现精确的唤醒控制
3. **SoC 级下电**：7 状态下电状态机覆盖 L2 flush -> WFI -> CHI 退出 -> QREQ 的完整下电流程
4. **异步域隔离**：CHI、CLINT、Bus 三组异步桥独立配置，均使用 3 级同步器
5. **DFT 可测试性**：MBIST 广播网络与门控时钟无缝集成，支持 scan_mode 下的完全旁路
6. **物理设计就绪**：PowerSwitchBuffer 占位符为后端 PD 流程预留了电源开关接口
