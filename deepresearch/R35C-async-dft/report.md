# R35C - Async Bridges & DFT Deep Dive

## 1. Overview

XiangShan (香山) RISC-V 处理器在 SoC 顶层涉及多个异步时钟域（core clock、NOC clock、SOC clock、CLINT clock、RTC clock），需要在这些时钟域之间安全地传递数据和控制信号。整个异步跨域（Clock Domain Crossing, CDC）体系建立在 Rocket-Chip 提供的 `AsyncQueue` 原语之上，在其上封装了三类 AsyncBridge（CHI AsyncBridge、CLINT AsyncBridge、Bus AsyncBridge）以及专用的 `TimeAsync` RTC 时钟域穿越模块。同时，DFT（Design for Test）体系通过 `DFTOptions`、`SramBroadcastBundle`、`MbistClockGateCell`、scan chain 等机制为 SRAM 阵列的测试和生产调试提供完整支持。

---

## 2. AsyncQueue Source/Sink Design

### 2.1 基础结构：AsyncQueueParams

核心定义位于 `rocket-chip/src/main/scala/util/AsyncQueue.scala`：

```scala
case class AsyncQueueParams(
  depth:  Int     = 8,
  sync:   Int     = 3,
  safe:   Boolean = true,
  narrow: Boolean = false
)
```

- `depth`：FIFO 深度，必须为 2 的幂次，决定了异步 FIFO 中可以缓冲的条目数。
- `sync`：同步器级数（默认 3 级），决定信号跨时钟域需要经过的同步寄存器级数，影响 latency 和 MTBF（Mean Time Between Failures）。
- `safe`：安全模式开关，当 `safe=true` 时会额外生成 `AsyncBundleSafety` 中的握手信号，用于在任一侧复位时重新同步 crossing index，保证 reset-safe CDC。
- `narrow`：窄模式，将 read mux 移到 source 侧，减少 level shifter 数量（适用于 CDC 同时也是 voltage crossing 的场景），代价是引入 sink-to-source 的组合路径。

### 2.2 AsyncBundle 数据通道

```scala
class AsyncBundle[T <: Data](gen: T, params: AsyncQueueParams) extends Bundle {
  val mem   = Output(Vec(params.wires, gen))  // 数据通路
  val ridx  = Input(UInt((params.bits+1).W))  // 读指针（sink -> source）
  val widx  = Output(UInt((params.bits+1).W)) // 写指针（source -> sink）
  val index = params.narrow.option(Input(UInt(params.bits.W)))
  val safe  = params.safe.option(new AsyncBundleSafety)
}
```

其中 `mem` 数组宽度由 `depth` 决定（narrow 模式下仅为 1），`ridx` 和 `widx` 使用 Gray Code 编码进行跨时钟域传输。

### 2.3 GrayCounter

`GrayCounter` 是异步 FIFO 的核心指针生成器：

```scala
object GrayCounter {
  def apply(bits: Int, increment: Bool, clear: Bool, name: String): UInt = {
    val incremented = Wire(UInt(bits.W))
    val binary = RegNext(next=incremented, init=0.U).suggestName(name)
    incremented := Mux(clear, 0.U, binary + increment.asUInt)
    incremented ^ (incremented >> 1)  // binary to Gray
  }
}
```

Gray Code 编码保证相邻两个状态之间只有一位发生变化，从而在跨时钟域时不会产生亚稳态毛刺。

### 2.4 AsyncQueueSource

`AsyncQueueSource` 是异步队列的写入端（Source Domain）：

```scala
class AsyncQueueSource[T <: Data](gen: T, params: AsyncQueueParams) extends Module {
  val io = IO(new Bundle {
    val enq = Flipped(Decoupled(gen))
    val async = new AsyncBundle(gen, params)
  })
```

关键逻辑：
1. 内部维护一个 `depth` 深度的 `mem` 寄存器堆，**无需 reset**。
2. 生成写指针 `widx`（Gray Code），通过 `AsyncResetSynchronizerShiftReg` 同步读取远端的 `ridx`。
3. 比较 `widx` 和 `ridx` 生成 `ready` 信号：`ready = sink_ready && (widx =/= (ridx ^ mask))`，其中 `mask = (depth | depth>>1)` 确保至少保留一个空位。
4. 输出 `async.widx` 供 sink 端同步使用，同时将 `mem` 的全部内容直接暴露在 `async.mem` 上。
5. narrow 模式下通过 `async.index` 让 sink 端选择读取哪个条目。

### 2.5 AsyncQueueSink

`AsyncQueueSink` 是异步队列的读出端（Sink Domain）：

```scala
class AsyncQueueSink[T <: Data](gen: T, params: AsyncQueueParams) extends Module {
  val io = IO(new Bundle {
    val deq = Decoupled(gen)
    val async = Flipped(new AsyncBundle(gen, params))
  })
```

关键逻辑：
1. 生成读指针 `ridx`（Gray Code），通过 `AsyncResetSynchronizerShiftReg` 同步读取远端的 `widx`。
2. 比较 `ridx` 和 `widx` 生成 `valid` 信号：`valid = source_ready && (ridx =/= widx)`。
3. 使用 `ClockCrossingReg` 对读取的数据打一拍寄存，保证输出稳定。
4. 输出 `async.ridx` 供 source 端同步使用。

### 2.6 Safe Mode（安全模式）

当 `safe=true` 时，Source 和 Sink 两端各增加四个 `AsyncValidSync` 模块：
- `source_valid_0/1`：Source 端产生 `widx_valid` 信号告知 Sink 端 Source 已就绪。
- `sink_extend/sink_valid`：Sink 端同步 `ridx_valid`，告知 Source 端 Sink 已就绪。
- `sink_valid_0/1`：Sink 端产生 `ridx_valid` 信号告知 Source 端 Sink 已就绪。
- `source_extend/source_valid`：Source 端同步 `widx_valid`，告知 Sink 端 Source 已就绪。

这些额外信号确保当一侧复位时，另一侧能安全地检测并重新同步指针，避免出现误读或误写。

### 2.7 AsyncQueue 顶层

```scala
class AsyncQueue[T <: Data](gen: T, params: AsyncQueueParams) extends Crossing[T] {
  val io = IO(new CrossingIO(gen))
  val source = withClockAndReset(io.enq_clock, io.enq_reset) { Module(new AsyncQueueSource(gen, params)) }
  val sink   = withClockAndReset(io.deq_clock, io.deq_reset) { Module(new AsyncQueueSink(gen, params)) }
  source.io.enq <> io.enq
  io.deq <> sink.io.deq
  sink.io.async <> source.io.async
}
```

这是 Rocket-Chip 中通用的 `Crossing` trait 实现，提供完整的异步 FIFO 通道。

---

## 3. CHI AsyncBridge (depth=16, sync=3)

### 3.1 设计背景

XiangShan 的 L2 Cache（CoupledL2）使用 ARM AMBA CHI（Coherent Hub Interface）协议与外部 LLC 或 NoC 通信。L2 运行在 CPU clock domain，而外部 NoC 运行在 NOC clock domain。CHI AsyncBridge 在两者之间提供异步桥接。

### 3.2 参数配置

在 `SoC.scala` 的 `SoCParameters` 中定义：

```scala
EnableCHIAsyncBridge: Option[AsyncQueueParams] = Some(AsyncQueueParams(depth = 16, sync = 3, safe = false))
```

- `depth = 16`：异步队列深度为 16，提供足够的缓冲能力以掩盖异步延迟。
- `sync = 3`：三级同步器，提供较高的 MTBF 保证。
- `safe = false`：不启用 reset-safe 模式，因为 CHI bridge 有自己独立的 reset handling 机制。

### 3.3 Shadow Buffer 架构

CHI AsyncBridge 并非直接使用 `AsyncQueueSource/Sink`，而是在其前端增加了一个 **Shadow Buffer** 层：

```
rx: DownStream(CMN) -> [Shadow Buffer (16)] -> [AsyncQueueSink (4)] -> [AsyncQueueSource (4)] -> Upstream (L2)
                           ^
                     Instant Credit return

tx: UpStream(L2) -> [Shadow Buffer (16)] -> [AsyncQueueSource (4)] -> [AsyncQueueSink (4)] -> Downstream (CMN)
          ^                                                               ^
     CHI Credit + over Credit(4)                                       Credit manage to gen back-pressure
```

`ToAsyncBundleWithBuf.channel` 的实现：

```scala
object ToAsyncBundleWithBuf {
  def channel[T <: Data](chn: ChannelIO[T], params: AsyncQueueParams = AsyncQueueParams(depth = 4), name: Option[String] = None): (Data, Bool) = {
    val shadow_buffer = Module(new Queue(chiselTypeOf(chn.flit), 16, flow = true, pipe = false))
    shadow_buffer.io.enq.valid := chn.flitv
    shadow_buffer.io.enq.bits  := chn.flit
    val deqReady = shadow_buffer.io.deq.ready
    val source = Module(new AsyncQueueSource(chiselTypeOf(chn.flit), params))
    source.io.enq <> shadow_buffer.io.deq
    (source.io.async, deqReady)
  }
}
```

Shadow Buffer 使用 `flow = true`（即 bypass 模式）实现低延迟直通，同时提供 16 条目的缓冲深度。这种设计的关键优势是：

1. **Instant Credit Return**：对于 rx 通道（CMN -> L2），flit 进入 Shadow Buffer 后即可立即返回 lcredit，无需等待异步传输完成，显著降低 credit return latency。
2. **Back-pressure 管理**：Shadow Buffer 的 `deqReady` 信号可以感知下游是否可以接收，实现流控。
3. **溢出保护**：有 `assert(!chn.flitv || shadow_buffer.io.enq.ready)` 确保 Shadow Buffer 不会溢出。

### 3.4 CHIAsyncBridgeSource

```scala
class CHIAsyncBridgeSource(params: AsyncQueueParams)(implicit p: Parameters) extends Module {
  val io = IO(new Bundle() {
    val enq = Flipped(new PortIO)
    val async = new AsyncPortIO(params)
    val resetFinish = Output(Bool())
  })
```

Source 端实例化在 `XSTileWrap` 中（CPU clock domain）：

```scala
// 在 XSTileWrap.scala 中
EnableCHIAsyncBridge match {
  case Some(param) =>
    val source = withClockAndReset(clock, noc_reset_sync.get)(Module(new CHIAsyncBridgeSource(param)))
    source.io.enq <> tile.module.io.chi.get
    io.chi <> source.io.async
```

Source 端处理：
- **TX 通道**（L2 -> CMN）：tx.req、tx.rsp、tx.dat 三个通道的 flit 使用 `ToAsyncBundleWithBuf` 包装后发出；lcredit（`rx.lcrdv`）使用 `FromAsyncBundle.bitPulse` 接收。
- **RX 通道**（CMN -> L2）：rsp、dat、snp 三个通道的 flit 使用 `FromAsyncBundle.channel` 接收；lcredit（`tx.lcrdv`）使用 `ToAsyncBundle.bitPulse` 发出。
- **控制信号同步**：使用 `AsyncResetSynchronizerShiftReg` 同步 `rxsactive`、`linkactiveack`、`linkactivereq`、`syscoack`、`flitpend` 等信号。
- **Reset handling**：内建一个计数器 `RESET_FINISH_MAX = 100`，在 reset 后等待 100 个周期才释放 `resetFinish`，确保异步桥初始化完成后再开始工作。

### 3.5 CHIAsyncBridgeSink

Sink 端实例化在 `XSNoCTop` 中（NOC clock domain）：

```scala
// 在 XSNoCTop.scala -> HasXSTileCHIImp trait 中
socParams.EnableCHIAsyncBridge match {
  case Some(param) =>
    withClockAndReset(noc_clock.get, noc_reset_sync.get) {
      val time_sink = Module(new CHIAsyncBridgeSink(param))
      time_sink.io.async <> core_with_l2.module.io.chi
      io_chi <> time_sink.io.deq
    }
```

Sink 端的额外机制：

1. **Link State FSM**：独立复制 Link Monitor 的 tx/rx 状态 FSM（STOP -> ACTIVATE -> RUN -> DEACTIVATE -> STOP），基于同步后的 `linkactivereq` 和 `linkactiveack` 信号驱动。
2. **L-Credit Manager**：
   - **RX 通道**：使用 `LCredit2Decoupled` 将 LCredit 协议转换为 Decoupled 协议，最大 L-Credit 数为 15，支持 Instant Credit Return。
   - **TX 通道**：使用 `Decoupled2LCredit` 将 Decoupled 协议转换为 LCredit 协议，内部管理最多 4 个 L-Credit 用于反压控制。CoupledL2 端额外配置更多 L-Credit（>4）以覆盖 lcrdv 的同步延迟。

### 3.6 CHI Channel I/O

AsyncBridge 的异步接口使用专门的 `AsyncChannelIO` 定义：

```scala
class AsyncChannelIO[+T <: Data](gen: T, params: AsyncQueueParams) extends Bundle {
  val flitpend = Output(Bool())
  val flit = new AsyncBundle(UInt(gen.getWidth.W), params)
  val lcrdv = Flipped(new AsyncBundle(UInt(0.W), params))
}
```

其中 `flit` 承载实际数据，`lcrdv` 是 0-width 的脉冲信号（通过 `AsyncQueueSource` 的 valid 信号传递），`flitpend` 表示通道中有待处理的 flit。

---

## 4. CLINT AsyncBridge (depth=8)

### 4.1 参数配置

```scala
EnableClintAsyncBridge: Option[AsyncQueueParams] = Some(AsyncQueueParams(depth = 8, sync = 3, safe = false))
```

`depth = 8` 为 64 位时间值提供足够的缓冲。

### 4.2 Source 端 (SoC clock domain -> CLINT clock domain)

在 `XSNoCTop.scala` 的 `HasClintTimeImp` trait 中：

```scala
socParams.EnableClintAsyncBridge match {
  case Some(param) =>
    withClockAndReset(clint_clock, clint_reset_sync) {
      val time_source = Module(new AsyncQueueSource(UInt(64.W), param))
      time_source.io.enq.valid := io_clintTime.valid
      time_source.io.enq.bits := io_clintTime.bits
      core_with_l2.module.io.clintTime <> time_source.io.async
    }
```

直接使用 Rocket-Chip 的 `AsyncQueueSource`，将 SoC clock domain 的 64 位时间值发送到 CLINT（或 XSTile 内部）的时钟域。

### 4.3 Sink 端 (CLINT clock domain)

在 `XSTileWrap.scala` 中：

```scala
EnableClintAsyncBridge match {
  case Some(param) =>
    val time_sink = withClockAndReset(clock, soc_reset_sync)(Module(new AsyncQueueSink(UInt(64.W), param)))
    time_sink.io.async <> io.clintTime
    time_sink.io.deq.ready := true.B
    tile.module.io.clintTime.valid := time_sink.io.deq.valid
    tile.module.io.clintTime.bits := time_sink.io.deq.bits
```

Sink 端 `deq.ready` 直接接 `true.B`，表示始终可以接收时间值。这种设计合理，因为时间值是单调递增的，过时的数据可以被丢弃。

---

## 5. Bus AsyncBridge (depth=1)

### 5.1 参数配置

```scala
SeperateBusAsyncBridge: Option[AsyncQueueParams] = Some(AsyncQueueParams(depth = 1, sync = 3, safe = false))
```

`depth = 1` 是最小深度的异步桥，用于 TileLink 分离总线（Seperated Bus）场景。

### 5.2 使用场景

当 `SeperateBus != NONE` 时，XSTileWrap 中会实例化 TL Async Crossing：

```scala
// XSTileWrap.scala
val tlAsyncSourceOpt = Option.when(SeperateBus != top.SeperatedBusType.NONE)(LazyModule(new TLAsyncCrossingSource()))
tlAsyncSourceOpt.foreach(_.node := tlXbar.get)

// XSNoCTop.scala -> HasSeperatedBusOpt trait
val tlAsyncSinkOpt = Option.when(!isNONE)(LazyModule(new TLAsyncCrossingSink(SeperateBusAsyncBridge.get)))
tlAsyncSinkOpt.foreach(_.node := core_with_l2.tlAsyncSourceOpt.get.node)
```

### 5.3 深度为 1 的含义

`depth = 1` 意味着异步 FIFO 仅有一个条目，这提供了：
- **最低延迟**：一拍即可传递，适合低频或已有时序裕量的场景。
- **最小面积**：几乎不增加额外逻辑。
- **Flow Control 透传**：ready/valid 信号直接透传，适合已有 Buffer 级联的 TileLink 总线。

这种设计用于 `UsePrivateClint` 场景下 TL 分离总线的 MMIO 访问通道，数据流量不高但需要跨时钟域。

---

## 6. TimeAsync RTC Clock Domain Crossing

### 6.1 设计背景

`TimeAsync` 模块位于 `src/main/scala/device/TimeAsync.scala`，用于将 RTC（Real-Time Clock）时钟域的 64 位时间计数器值安全地传递到 bus clock domain。

### 6.2 TimeAsync 模块

```scala
class TimeAsync extends Module {  // work with destination clock
  val io = IO(new Bundle {
    val i_time = Input(ValidIO(UInt(64.W)))
    val o_time = Output(ValidIO(UInt(64.W)))
  })
  val time_vld      = AsyncResetSynchronizerShiftReg(io.i_time.valid, 3, 0)
  val time_vld_1dly = RegNext(time_vld, false.B)
  val time_vld_xor  = time_vld ^ time_vld_1dly
  val time_vld_o    = RegNext(time_vld_xor, false.B)
  val time_o        = RegEnable(io.i_time.bits, 0.U(64.W), time_vld_xor)
  io.o_time.valid := time_vld_o
  io.o_time.bits  := time_o
}
```

工作原理：
1. 使用 3 级 `AsyncResetSynchronizerShiftReg` 同步 valid 信号到目标时钟域。
2. 通过 XOR 边沿检测（`time_vld ^ time_vld_1dly`）捕捉 valid 的上升沿。
3. 在上升沿时刻使用 `RegEnable` 锁存 64 位时间值。
4. 输出 valid 信号延迟一拍以保证时序正确。

### 6.3 TimeVldGen 模块

```scala
class TimeVldGen extends Module {  // work with reference clock
  val io = IO(new Bundle {
    val i_time = Input(UInt(64.W))
    val o_time = Output(ValidIO(UInt(64.W)))
  })
  io.o_time.bits  := io.i_time
  io.o_time.valid := io.i_time(0)
}
```

这是一个配合模块，工作在参考时钟域，将时间值的最低位（bit[0]）作为 valid 信号。这意味着每当 bit[0] 翻转时（即计数器每加 1），就会产生一个 valid 脉冲。

### 6.4 SYSCNT 中的使用

在 `src/main/scala/device/SYSCNT.scala` 中：

```scala
val timeasync = withClockAndReset(bus_clock, bus_reset)(Module(new TimeAsync()))
val time_rpt_bus = timeasync.io.o_time.bits
timeasync.io.i_time := io.time
```

`SYSCNT` 模块的 `time` 计数器工作在 `rtc_clock` 域，通过 `TimeAsync` 模块传递到 `bus_clock` 域，供总线上的软件读取。此外，SYSCNT 还使用 `AsyncResetSynchronizerShiftReg` 同步 `update_sync`、`stop_sync`、`freqidx_req`、`time_sw_req` 等控制信号，实现双向的异步控制。

---

## 7. DFTOptions (EnableMbist, etc.)

### 7.1 参数定义

```scala
// xiangshan/Parameters.scala (L539-545)
case object DFTOptionsKey extends Field[DFTOptions]

case class DFTOptions(
  EnableMbist: Boolean = true,   // enable mbist default
  EnableSramCtl: Boolean = false,
)
```

### 7.2 参数传播

DFT 选项通过 CDE（Context-Dependent Environments）参数系统传递到整个设计的各个层级：

1. **顶层获取**：在 `XSNoCTop.scala` 中：
   ```scala
   private val hasMbist = p(DFTOptionsKey).EnableMbist
   private val hasSramCtl = p(DFTOptionsKey).EnableSramCtl
   private val hasDFT = hasMbist || hasSramCtl
   ```

2. **条件 IO 生成**：
   ```scala
   val io = new Bundle {
     val dft = Option.when(hasDFT)(IO(Input(new SramBroadcastBundle)))
     val dft_reset = Option.when(hasMbist)(IO(Input(new DFTResetSignals())))
   }
   ```

3. **参数传递到 SRAM**：在 `Configs.scala` 中：
   ```scala
   case DFTOptionsKey => DFTOptions()
   // ...
   hasMbist = site(DFTOptionsKey).EnableMbist,
   hasSramCtl = site(DFTOptionsKey).EnableSramCtl,
   ```

4. **命令行配置**（`ArgParser.scala`）：
   ```scala
   case DFTOptionsKey => up(DFTOptionsKey).copy(EnableMbist = value.toBoolean)
   case DFTOptionsKey => up(DFTOptionsKey).copy(EnableSramCtl = true)
   ```

5. **YAML 配置**（`YamlParser.scala`）：
   ```scala
   yamlConfig.EnableDFX.foreach { enable =>
     newConfig = newConfig.alter((site, here, up) => {
       case DFTOptionsKey => up(DFTOptionsKey).copy(EnableMbist = enable)
     })
   }
   ```

### 7.3 EnableMbist 的影响

当 `EnableMbist = true` 时：
- 每个 `SRAMTemplate` 实例会生成 `SramBroadcastBundle` 和 `Ram2Mbist` 接口。
- `MbistClockGateCell` 被实例化为每个 SRAM 提供 DFT 时钟门控。
- 设计的顶层 IO 增加 `dft`（`SramBroadcastBundle`）和 `dft_reset`（`DFTResetSignals`）端口。
- `Mbist` 顶层会收集所有 SRAM 节点并生成统一的 MBIST 控制逻辑。

### 7.4 EnableSramCtl 的影响

当 `EnableSramCtl = true` 时：
- SRAM 阵列增加 `ram_ctl` 64 位输入端口，用于运行时 SRAM 控制参数调整。
- 这与 Mbist 独立，可以单独启用。

---

## 8. SramBroadcastBundle MBIST Broadcast

### 8.1 定义

```scala
// utility/src/main/scala/utility/sram/SramProto.scala
class SramBroadcastBundle extends Bundle {
  val ram_hold     = Input(Bool())     // 保持信号，禁止写操作
  val ram_bypass   = Input(Bool())     // Bypass 信号
  val ram_bp_clken = Input(Bool())     // Bypass 时钟使能
  val ram_aux_clk  = Input(Bool())     // 辅助时钟
  val ram_aux_ckbp = Input(Bool())     // 辅助时钟 bypass 选择
  val ram_mcp_hold = Input(Bool())     // Multi-Cycle Path hold
  val ram_ctl      = Input(UInt(64.W)) // SRAM 控制参数
  val cgen         = Input(Bool())     // Clock Gate Enable
}
```

### 8.2 广播机制

`SramBroadcastBundle` 通过 BoringUtils 广播到设计中的所有 SRAM 实例：

```scala
// utility/src/main/scala/utility/sram/SramHelper.scala
object SramHelper {
  val broadCastBdQueue = new mutable.Queue[SramBroadcastBundle]

  def genBroadCastBundleTop(): SramBroadcastBundle = {
    val res = Wire(new SramBroadcastBundle)
    broadCastBdQueue.toSeq.foreach(bd => {
      BoringUtils.bore(bd) := res
    })
    broadCastBdQueue.clear()
    res
  }
}
```

工作流程：
1. 每个 `SRAMTemplate` 在实例化时，如果启用了 Mbist，会创建一个 `SramBroadcastBundle` 实例并加入 `broadCastBdQueue`。
2. 在 SoC 顶层调用 `genBroadCastBundleTop()`，通过 `BoringUtils.bore` 将队列中所有 bundle 连接到同一个源头。
3. 这样顶层 `io.dft` 输入的信号可以广播到所有 SRAM。

### 8.3 信号功能说明

| 信号 | 功能 |
|------|------|
| `ram_hold` | 在 MBIST 操作期间保持 SRAM 内容不变，禁止正常读写写入 |
| `ram_bypass` | 用于 bypass SRAM 的旁路控制 |
| `ram_bp_clken` | Bypass 旁路的时钟使能 |
| `ram_aux_clk` | DFT 辅助时钟，用于 MBIST 时的可测试性时钟 |
| `ram_aux_ckbp` | 辅助时钟选择信号，在 `ClockMux` 中选择正常时钟或辅助时钟 |
| `ram_mcp_hold` | Multi-Cycle Path hold 信号，在 MBIST 操作期间 hold 住 MCP 时序路径 |
| `ram_ctl` | 64 位 SRAM 控制总线，用于运行时参数调整 |
| `cgen` | Clock Gate Enable，直接连接到 `ClockGate` 的 TE（Test Enable）端 |

---

## 9. MbistClockGateCell with fromBroadcast

### 9.1 CgDftBundle

```scala
// utility/src/main/scala/utility/mbist/MbistClockGateCell.scala
class CgDftBundle extends Bundle {
  val ram_mcp_hold = Input(Bool())
  val ram_aux_clk = Input(Bool())
  val ram_aux_ckbp = Input(Bool())
  val cgen = Input(Bool())

  def fromBroadcast(brc: SramBroadcastBundle): Unit = {
    ram_aux_clk := brc.ram_aux_clk
    ram_aux_ckbp := brc.ram_aux_ckbp
    ram_mcp_hold := brc.ram_mcp_hold
    cgen := brc.cgen
  }
}
```

`fromBroadcast` 方法是从 `SramBroadcastBundle` 提取与 ClockGate 相关的四个信号的便捷方法，实现了 broadcast bundle 到 clock gate DFT bundle 的映射。

### 9.2 MbistClockGateCell

```scala
class MbistClockGateCell(mcpCtl: Boolean) extends Module {
  val mbist = IO(new Bundle {
    val writeen = Input(Bool())
    val readen  = Input(Bool())
    val req     = Input(Bool())
  })
  val E = IO(Input(Bool()))
  val dft = IO(new CgDftBundle)
  val out_clock = IO(Output(Clock()))

  private val CG = Module(new ClockGate)
  CG.io.TE := dft.cgen
  CG.io.CK := clock

  if (mcpCtl) {
    CG.io.E := Mux(mbist.req, mbist.readen | mbist.writeen, E) && !dft.ram_mcp_hold
    val clockMux = Module(new ClockMux)
    clockMux.clk0 := CG.io.Q
    clockMux.clk1 := dft.ram_aux_clk.asClock
    clockMux.sel := dft.ram_aux_ckbp
    out_clock := clockMux.clkout
  } else {
    CG.io.E := Mux(mbist.req, mbist.readen | mbist.writeen, E)
    out_clock := CG.io.Q
  }
}
```

工作原理：

1. **正常模式**：当 `mbist.req = false` 时，`CG.io.E = E`，即正常的功能使能信号控制时钟门控。
2. **MBIST 模式**：当 `mbist.req = true` 时，`CG.io.E = mbist.readen | mbist.writeen`，由 MBIST 控制器决定何时开启时钟。
3. **MCP 控制模式**（`mcpCtl = true`）：额外增加 `!dft.ram_mcp_hold` 条件，在 MBIST Multi-Cycle Path 测试期间 hold 住时钟，防止时序违例。同时通过 `ClockMux` 支持辅助时钟（`dft.ram_aux_clk`），用于 DFT 时的可测试性时钟切换。
4. **TE（Test Enable）**：`dft.cgen` 直接连接到 `ClockGate` 的 TE 端，作为全局 DFT 测试使能。

### 9.3 SRAMTemplate 中的使用

在 `SRAMTemplate.scala` 中，每个 SRAM 实例可以有读时钟门控（rcg）和写时钟门控（wcg）：

```scala
private val rcg = if(implCg) Some(Module(new MbistClockGateCell(extraHold))) else None
private val wcg = if(!singlePort && implCg) Some(Module(new MbistClockGateCell(extraHold))) else None
```

连接方式：

```scala
rcg.foreach(cg => {
  cg.dft.fromBroadcast(brcBd)       // 从 broadcast bundle 获取 DFT 信号
  cg.mbist.req := mbistBd.ack       // MBIST 请求
  cg.mbist.readen := rckEn          // 读时钟使能
  if(singlePort) {
    cg.mbist.writeen := wckEn       // 单端口模式下同时控制读写
    cg.E := rckEn | wckEn
  } else {
    cg.mbist.writeen := false.B     // 双端口模式下读时钟门控不控制写
    cg.E := rckEn
  }
})
```

### 9.4 GatedSplittedSRAM 中的使用

在 L2 Cache 的 `GatedSplittedSRAM` 中，每个拆分的 SRAM 都有独立的 `MbistClockGateCell`：

```scala
// XSCache/src/main/scala/coupledL2/utils/GatedSplittedSRAM.scala
if (hasMbist) {
  array.map(_.map(_.map(a => {
    val cg = Module(new MbistClockGateCell(extraHold))
    cg.E := io_en
    cg.dft.fromBroadcast(a.io.broadcast.getOrElse(0.U.asTypeOf(new SramBroadcastBundle)))
    cg.mbist.req := a.io.mbistCgCtl.map(_.en).getOrElse(false.B)
    cg.mbist.writeen := a.io.mbistCgCtl.map(_.wckEn).getOrElse(false.B)
    cg.mbist.readen := a.io.mbistCgCtl.map(_.rckEn).getOrElse(false.B)
    a.io.mbistCgCtl.foreach(_.wclk := cg.out_clock)
    a.io.mbistCgCtl.foreach(_.rclk := cg.out_clock)
    a.clock := clock
  })))
}
```

这种设计确保每个 SRAM bank 都可以被 MBIST 独立访问和测试。

---

## 10. Scan Chain Support

### 10.1 DFTResetSignals

```scala
// utility/src/main/scala/utility/ResetGen.scala
class DFTResetSignals extends Bundle {
  val lgc_rst_n  = AsyncReset()  // 逻辑复位（低有效）
  val mode       = Bool()         // 模式选择
  val scan_mode  = Bool()         // Scan chain 模式
}
```

### 10.2 ResetGen 中的 scan_mode

```scala
class ResetGen(SYNC_NUM: Int = 3) extends Module {
  val o_reset = IO(Output(AsyncReset()))
  val dft = IO(Input(new DFTResetSignals()))

  private val lgc_rst = !dft.lgc_rst_n.asBool
  private val real_reset = Mux(dft.mode, lgc_rst, reset.asBool).asAsyncReset
  private val raw_reset = Wire(AsyncReset())
  withClockAndReset(clock, real_reset) {
    val pipe_reset = RegInit(((1L << SYNC_NUM) - 1).U(SYNC_NUM.W))
    pipe_reset := Cat(pipe_reset(SYNC_NUM - 2, 0), 0.U(1.U))
    raw_reset := pipe_reset(SYNC_NUM - 1).asAsyncReset
  }
  o_reset := Mux(dft.scan_mode, lgc_rst, raw_reset.asBool).asAsyncReset
}
```

scan_mode 的作用：
- 当 `scan_mode = false` 时：正常复位流程，复位信号经过多级同步器同步后输出。
- 当 `scan_mode = true` 时：**绕过复位同步器**，直接使用 `lgc_rst_n` 的逻辑复位作为输出。这是 DFT 测试时的关键特性 -- 在 scan chain 插入和测试期间，需要绕过异步复位同步器，否则复位信号的异步行为会导致 scan chain 状态不确定。

### 10.3 ResetGen 在 SoC 中的层级

ResetGen 被多层使用：

```scala
// XSNoCTop.scala - HasAsyncClockImp
val noc_reset_sync = socParams.EnableCHIAsyncBridge.map(_ =>
  withClockAndReset(noc_clock.get, noc_reset) { ResetGen(io.dft_reset) })
val soc_reset_sync = withClockAndReset(soc_clock, soc_reset) { ResetGen(io.dft_reset) }
val clint_reset_sync = withClockAndReset(clint_clock, clint_reset) { ResetGen(io.dft_reset) }

// BaseXSSocImp
val cpuReset_sync = withClockAndReset(clock, cpuReset.asAsyncReset)(ResetGen(io.dft_reset))
```

每一层的 `ResetGen` 都接收 `dft_reset` 信号，当 scan 测试模式激活时，可以绕过同步器直接控制复位状态。

### 10.4 DFT IO 在顶层的连接

```scala
// XSTileWrap.scala
tile.module.io.dft.zip(io.dft).foreach({ case (a, b) => a := b })
tile.module.io.dft_reset.zip(io.dft_reset).foreach({ case (a, b) => a := b })
```

`SramBroadcastBundle` 和 `DFTResetSignals` 从 SoC 顶层通过 `XSTileWrap` 层层传递到每个 SRAM 实例和 `ResetGen` 实例。

---

## 11. Source File Locations

| 文件 | 路径 | 描述 |
|------|------|------|
| AsyncQueue (Rocket-Chip) | `rocket-chip/src/main/scala/util/AsyncQueue.scala` | AsyncQueueParams、AsyncQueueSource、AsyncQueueSink、AsyncBundle、GrayCounter、AsyncValidSync |
| CHI AsyncBridge | `XSCache/src/main/scala/xscache/chi/AsyncBridge.scala` | CHIAsyncBridgeSource、CHIAsyncBridgeSink、ToAsyncBundleWithBuf、ToAsyncBundle、FromAsyncBundle、AsyncChannelIO、AsyncPortIO |
| XSNoCTop | `src/main/scala/top/XSNoCTop.scala` | BaseXSSocImp、HasAsyncClockImp、HasXSTileCHIImp（CHI sink 实例化）、HasClintTimeImp（CLINT source 实例化）、HasSeperatedBusOpt（Bus async sink） |
| XSTileWrap | `src/main/scala/xiangshan/XSTileWrap.scala` | CHI source 实例化、CLINT sink 实例化、DFT IO 连接、ResetGen 层级 |
| SoC Parameters | `src/main/scala/system/SoC.scala` | SoCParameters（EnableCHIAsyncBridge、EnableClintAsyncBridge、SeperateBusAsyncBridge 参数定义） |
| DFTOptions | `src/main/scala/xiangshan/Parameters.scala` (L539-545) | DFTOptionsKey、DFTOptions case class（EnableMbist、EnableSramCtl） |
| SramBroadcastBundle | `utility/src/main/scala/utility/sram/SramProto.scala` | SramBroadcastBundle、SramMbistIO、SramArray 定义 |
| SramHelper | `utility/src/main/scala/utility/sram/SramHelper.scala` | SramInfo、genBroadCastBundleTop、genRam、BoringUtils 广播逻辑 |
| MbistClockGateCell | `utility/src/main/scala/utility/mbist/MbistClockGateCell.scala` | MbistClockGateCell、CgDftBundle、fromBroadcast 方法 |
| SRAMTemplate | `utility/src/main/scala/utility/sram/SRAMTemplate.scala` | SRAMTemplate（MbistClockGateCell 实例化、fromBroadcast 连接） |
| GatedSplittedSRAM | `XSCache/src/main/scala/coupledL2/utils/GatedSplittedSRAM.scala` | L2 Cache 的 GatedSplittedSRAM（每个 SRAM bank 独立 MbistClockGateCell） |
| TimeAsync | `src/main/scala/device/TimeAsync.scala` | TimeAsync（RTC 到 bus clock 跨域）、TimeVldGen |
| SYSCNT | `src/main/scala/device/SYSCNT.scala` | SYSCNT（系统计数器，使用 TimeAsync 进行 RTC 跨域） |
| ResetGen | `utility/src/main/scala/utility/ResetGen.scala` | DFTResetSignals（scan_mode）、ResetGen（多级复位同步器） |
| ArgParser | `src/main/scala/top/ArgParser.scala` | EnableMbist、EnableSramCtl 命令行配置 |
| YamlParser | `src/main/scala/top/YamlParser.scala` | EnableCHIAsyncBridge、EnableDFX 等 YAML 配置 |
| Configs | `src/main/scala/top/Configs.scala` | DFTOptions 默认值、L2Parameter 的 hasMbist/hasSramCtl 传递 |

---

## 12. Summary

XiangShan 的异步桥和 DFT 体系形成了一个完整的跨时钟域和可测试性框架：

**异步桥体系**以 Rocket-Chip 的 `AsyncQueue`（Gray Code CDC FIFO）为基础原语，在其上构建了三个层次的异步桥：
- **CHI AsyncBridge**（depth=16）：最复杂，包含 Shadow Buffer、L-Credit Manager、Link State FSM，支持 CHI 协议的全通道异步传输。
- **CLINT AsyncBridge**（depth=8）：中等复杂度，直接使用 AsyncQueue 传递 64 位时间值。
- **Bus AsyncBridge**（depth=1）：最简单，用于 TileLink 分离总线的低频跨域。
- **TimeAsync**：专用的 RTC 时钟域穿越模块，使用边沿检测而非 FIFO 传递时间值。

**DFT 体系**通过 `DFTOptions` 参数控制，支持 MBIST 和 SRAM 控制两种功能：
- **SramBroadcastBundle** 通过 BoringUtils 广播到所有 SRAM，提供统一的 DFT 控制信号。
- **MbistClockGateCell** 为每个 SRAM 提供独立的 DFT 时钟门控，支持 MCP 控制和辅助时钟切换。
- **scan_mode** 在 `ResetGen` 中实现复位同步器绕过，确保 scan chain 测试的确定性。
