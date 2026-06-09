# R35A - Clock Gating & Reset 深度研究报告

## 概述

XiangShan RISC-V 处理器的 Clock Gating 与 Reset 基础设施是一套完整的低功耗与可测试性设计 (DFT) 体系。该体系涵盖了从门级时钟门控 (Clock Gating)、时钟选择 (Clock Mux)、多级同步复位 (Reset Synchronizer)，到分段地址级门控 (Segmented Address Gating) 和集中式 TE (Test Enable) 管理的全部层次。所有核心实现集中在 `utility` 子项目中，并通过 `BoringUtils` 跨模块信号穿透技术实现了全局统一的 DFT 控制。

---

## 1. ClockGate BlackBox 接口：TE / E / CK / Q

### 1.1 接口定义

`ClockGate` 定义在 `utility/src/main/scala/utility/ClockGate.scala` 中，是一个带有内联 SystemVerilog 实现的 Chisel BlackBox：

```scala
class ClockGate extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val TE = Input(Bool())   // Test Enable，DFT 测试模式下强制打开时钟
    val E  = Input(Bool())   // Enable，正常功能模式下的时钟使能
    val CK = Input(Clock())  // Clock，原始时钟输入
    val Q  = Output(Clock()) // Gated Clock，门控后的时钟输出
  })
}
```

四个端口的设计遵循经典的 Integrated Clock Gating (ICG) cell 接口规范：

- **CK (Clock)**: 输入的原始时钟信号。
- **E (Enable)**: 功能级时钟使能信号，由逻辑电路根据数据有效性生成。
- **TE (Test Enable)**: 测试使能信号，在 DFT scan chain 插入和测试模式下，TE=1 强制时钟通过，不受 E 信号影响。
- **Q (Gated Clock)**: 经过门控后的时钟输出。

### 1.2 SystemVerilog 实现

内联的 SystemVerilog 实现使用了 `always_latch` 结构，这是典型的 ICG cell 实现方式：

```systemverilog
module ClockGate (
  input  wire TE,
  input  wire E,
  input  wire CK,
  output wire Q
);
  reg EN;
  always_latch begin
    if(!CK) EN = TE | E;
  end
  assign Q = CK & EN;
endmodule
```

该实现的工作原理如下：

1. 在 CK 为低电平时，锁存使能信号 `EN = TE | E`。TE 为 OR 关系意味着只要 TE 或 E 任意一个为高，EN 就被置位。
2. 在 CK 为高电平时，`EN` 保持不变（latch 透明模式关闭）。
3. 输出 `Q = CK & EN`：只有当 `EN=1` 时，CK 才能传递到 Q。

这种 "negative-level latch + AND gate" 的结构确保了门控时钟在切换时不会产生毛刺 (glitch-free)，因为 EN 信号只在 CK 低电平期间变化，而 Q 的上升沿完全由 CK 的上升沿决定。

### 1.3 ClockGate.apply 工厂方法

`ClockGate` companion object 提供了便捷的工厂方法：

```scala
object ClockGate {
  def apply(TE: Bool, E: Bool, CK: Clock): Clock = {
    val clock_gate = Module(new ClockGate).io
    clock_gate.TE := TE
    clock_gate.E  := E
    clock_gate.CK := CK
    clock_gate.Q
  }
}
```

该方法实例化一个 ClockGate BlackBox 并直接返回门控后的时钟 `Q`，调用者无需手动连接端口。典型使用场景包括：

- **ExeUnit 中的 FU 时钟门控**: `fu.clock := ClockGate(ClockGate.genTeSink.cgen, clk_en, clock)` (位于 `src/main/scala/xiangshan/backend/exu/ExeUnit.scala:121`)
- **SRAM 模板中的读写端口门控**: 通过 `MbistClockGateCell` 封装使用
- **MBIST 时钟门控**: 在 `MbistClockGateCell` 中直接实例化

---

## 2. genTeSrc / genTeSink 集中式 TE 管理与 BoringUtils

### 2.1 设计动机

在大规模 SoC 设计中，DFT 测试模式需要一个全局的 Test Enable (TE) 信号来控制所有 Clock Gate cell 在测试期间强制打开时钟。如果每个 ClockGate 的 TE 端口都通过模块 IO 层层传递，会导致 IO 接口膨胀和布线困难。XiangShan 采用了基于 `BoringUtils` 的集中式 TE 管理机制来解决这个问题。

### 2.2 ClockGateTeBundle

```scala
class ClockGateTeBundle extends Bundle {
  val cgen = Input(Bool())
}
```

这是一个极简的 Bundle，仅包含一个 `cgen` (Clock Gate Enable) 信号。注意它是 `Input(Bool())` 类型，所有 sink 端的 `cgen` 都是输入端口。

### 2.3 genTeSink — TE 信号消费者

```scala
def genTeSink: ClockGateTeBundle = {
  val te = Wire(new ClockGateTeBundle)
  te := 0.U.asTypeOf(te)   // 默认值为 0（不启用测试模式）
  dontTouch(te)             // 防止优化掉
  teQueue.enqueue(te)       // 加入全局队列
  te
}
```

每个需要时钟门控的子模块在初始化时调用 `genTeSink`，该方法：
1. 创建一个 `ClockGateTeBundle` wire
2. 将其默认值设为 0（正常功能模式，测试未启用）
3. 用 `dontTouch` 防止综合工具优化掉这个 wire
4. 将其加入到 `ClockGate` object 内部的 `mutable.Queue` 中

典型 sink 调用者：
- `src/main/scala/xiangshan/cache/mmu/PageTableCache.scala:515` — PTC 的 TLB 时钟门控
- `src/main/scala/xiangshan/cache/mmu/BitmapCheck.scala:457` — Bitmap TLB 检查的时钟门控
- `src/main/scala/xiangshan/backend/exu/ExeUnit.scala:121` — 各个执行单元 FU 的时钟门控

### 2.4 genTeSrc — TE 信号生产者

```scala
def genTeSrc: ClockGateTeBundle = {
  val res = Wire(new ClockGateTeBundle)
  teQueue.toSeq.foreach(bd => {
    BoringUtils.bore(bd) := res   // 穿透：将 res 的值广播到所有 sink
  })
  teQueue.clear()
  res
}
```

该方法是整个 TE 机制的核心：
1. 创建一个 `ClockGateTeBundle` wire `res`
2. 遍历队列中所有已注册的 sink Bundle，使用 `BoringUtils.bore(bd)` 创建跨模块的 wire 穿透连接
3. 将 `res` 的值广播赋值给所有 sink 端
4. 清空队列，确保 `genTeSrc` 只能被调用一次（单生产者模式）

### 2.5 BoringUtils 的工作原理

`chisel3.util.experimental.BoringUtils` 是 Chisel 提供的跨模块 wire 穿透工具。`BoringUtils.bore(bd)` 在后端生成时会在层级结构中创建一条穿越模块边界的 wire，使得源端 (`genTeSrc` 返回的 `res`) 的信号值能够直接驱动目标端 (`genTeSink` 返回的 `te`)，而无需通过模块 IO 传递。

### 2.6 genTeSrc 的调用位置与使用方式

`genTeSrc` 在四个主要模块中被调用，每个调用点都负责将来自 MBIST 控制器的 `cgen` 信号分发给本模块内所有的 ClockGate cell：

**Backend** (`src/main/scala/xiangshan/backend/Backend.scala:561`):
```scala
private val cg = ClockGate.genTeSrc
dontTouch(cg)
if (hasMbist) {
  cg.cgen := io.dft.get.cgen    // MBIST 控制器提供的 cgen
} else {
  cg.cgen := false.B            // 无 MBIST 时禁用测试模式
}
```

**Frontend** (`src/main/scala/xiangshan/frontend/Frontend.scala:313`):
```scala
private val cg = ClockGate.genTeSrc
dontTouch(cg)
if (hasMbist) {
  cg.cgen := io.dft.get.cgen
} else {
  cg.cgen := false.B
}
```

**MemBlock** (`src/main/scala/xiangshan/mem/MemBlock.scala:1591`):
```scala
private val cg = ClockGate.genTeSrc
```

**CoupledL2** (`XSCache/src/main/scala/coupledL2/CoupledL2.scala:797`):
```scala
private val cg = Option.when(cacheParams.hasMbist)(utility.ClockGate.genTeSrc)
```

这种设计的精妙之处在于：所有 `genTeSink` 调用者无需知道 TE 信号的来源，它们只需声明 "我需要一个 TE"，而所有 `genTeSrc` 调用者也无需知道有多少个 sink，它们只需 "提供 TE"。Chisel 编译期间的全局队列机制和 BoringUtils 的 wire 穿透共同完成了自动化的连接。

### 2.7 集中式 TE 信号流图

```
MBIST Controller (io.dft.get.cgen)
         |
         v
  genTeSrc (Backend)     genTeSink (PageTableCache)
         |                     ^
         |-- BoringUtils.bore  |
         v                     |
  genTeSink (ExeUnit FU 1)     |
  genTeSink (ExeUnit FU 2)     |
         |                     |
         v                     |
  genTeSink (BitmapCheck) -----+
         |
         v
  genTeSrc (Frontend)     genTeSink (ICache banks)
         |
         v
  genTeSrc (MemBlock)     genTeSink (DTLB, SBuffer)
         |
         v
  genTeSrc (CoupledL2)    genTeSink (L2 SRAM arrays)
```

---

## 3. GatedValidRegNext / GatedRegNext / GatedRegEnable

这三个 utility 对象位于 `utility/src/main/scala/utility/ClockGatedReg.scala`，提供了基于数据变化检测的寄存器时钟门控能力。它们的核心思想是：只有当数据发生变化时才使能寄存器更新，从而在不改变功能的前提下节省动态功耗。

### 3.1 GatedValidRegNext

```scala
object GatedValidRegNext {
  // 单 bit 版本：退化为普通 RegNext（因为单 bit 门控不值得）
  def apply(next: Bool, init: Bool = false.B): Bool = {
    val last = WireInit(false.B)
    last := RegNext(next, init)
    last
  }

  // Vec 版本：仅在数据变化时才更新寄存器
  def apply(last: Vec[Bool]): Vec[Bool] = {
    val next = VecInit(Seq.fill(last.size)(false.B))
    next := RegEnable(last, VecInit(Seq.fill(last.size)(false.B)),
                      last.asUInt =/= next.asUInt)
    next
  }
}
```

**设计要点**：
- 对于单 bit `Bool` 类型，门控没有意义（EDA 工具的最小 clock gate cell 通常需要至少 3-bit），因此退化为普通 `RegNext`。代码注释明确指出："3 is the default minimal width of EDA inserted clock gating cells"。
- 对于 `Vec[Bool]` 类型，使用 `RegEnable` 加 `last.asUInt =/= next.asUInt` 条件，只有当 Vec 中任意 bit 变化时才更新。
- `GatedValidRegNextN` 提供了 N 级延迟链版本，内部通过 `foldLeft` 串联。

### 3.2 GatedRegNext

```scala
object GatedRegNext {
  def apply[T <: Data](last: T, initOpt: Option[T] = None): T = {
    val next = WireInit(0.U.asTypeOf(last))
    last match {
      case v: Vec[_] =>
        next := regEnableVec(v.asInstanceOf[Vec[T]],
                             initOpt.map(_.asInstanceOf[Vec[T]]))
      case _ =>
        initOpt match {
          case Some(init) =>
            next := RegEnable(last, init, last.asUInt =/= next.asUInt)
          case None =>
            next := RegEnable(last, 0.U.asTypeOf(last), last.asUInt =/= next.asUInt)
        }
    }
    next
  }
}
```

与 `GatedValidRegNext` 的区别：
- 支持任意 `Data` 类型，不仅限于 `Bool` 和 `Vec[Bool]`。
- 对 `Vec` 类型采用逐元素 (element-wise) 的 `RegEnable`，每个元素独立判断是否变化。
- 提供可选的 `initOpt` 初始值参数。
- 代码注释警告："The larger Data width, the longer time of =/= operations, which may lead to timing violations." — 对于宽位宽数据，不等比较器的延迟可能引发时序问题，调用者需要自行评估。

**实际使用场景**：
- `utility/src/main/scala/utility/DataModuleTemplate.scala:42` — 写使能信号的门控延迟
- `utility/src/main/scala/utility/sram/SRAMTemplate.scala:486` — SRAM 读数据保持
- `utility/src/main/scala/utility/PipelineConnect.scala` — 流水线级间信号门控

### 3.3 GatedRegEnable

```scala
object GatedRegEnable {
  def apply[T <: Data](last: T, initOpt: Option[T] = None, enable: Bool): T = {
    // 使能条件 = 外部 enable AND 数据变化
    enable && last.asUInt =/= next.asUInt
  }
}
```

在 `GatedRegNext` 基础上增加外部 `enable` 控制信号。实际使能条件是 `enable && (last =/= next)`，即同时需要外部使能和数据变化两个条件都满足。

额外提供 `dupRegs` 方法，用于复制寄存器场景：多个副本共享同一个变化判断条件 (`last(0) =/= next(0)`)，以最小化比较器开销。

### 3.4 GatedRegNextN

级联 N 级 `GatedRegNext`，通过 `foldLeft` 实现：

```scala
object GatedRegNextN {
  def apply[T <: Data](in: T, n: Int, initOpt: Option[T] = None): T = {
    (0 until n).foldLeft(in) { (prev, _) => GatedRegNext(prev, initOpt) }
  }
}
```

### 3.5 全局使用统计

这些工具在整个 XiangShan 设计中被广泛使用，覆盖超过 40 个源文件，包括但不限于：
- Cache 子系统 (DCache, ICache, TLB, L2 TLB, MissQueue, SBuffer)
- 前端 (Frontend, BPU tables)
- 后端 (DataModuleTemplate, PipelineConnect)
- 内存子系统 (MemBlock, Prefetcher, Load/Store units)
- 向量计算 (Yunsuan 库中的各类 vector FU)

---

## 4. SegmentedAddr 分段地址级门控

### 4.1 设计理念

`SegmentedAddr` 定义在 `utility/src/main/scala/utility/ClockGatedReg.scala:138-265`，是一种将地址信号分段并按段独立进行时钟门控的技术。其核心思想是：当地址的高位段未变化时，只更新低位段的寄存器，从而减少不必要的寄存器翻转。

### 4.2 数据结构

```scala
class SegmentedAddr(_segments: Seq[Int]) extends Bundle {
  val segments = _segments   // (High, Lower ...) 分段宽度列表
  val addr: UInt = UInt(segments.sum.W)  // 完整地址
}
```

`segments` 参数定义了地址的分段方式。例如 `Seq(8, 4, 4)` 将 16-bit 地址分为三段：高 8 位、中 4 位、低 4 位。

### 4.3 分段逻辑

```scala
private def segment(addrIn: UInt): Seq[UInt] = {
  segments.foldLeft((Seq[UInt](), addrIn.asBools)) { (acc, segment_length) =>
    ((acc._1 :+ VecInit(acc._2.takeRight(segment_length)).asUInt),
     acc._2.dropRight(segment_length))
  }._1
}
```

`segment` 方法从低位到高位逐段提取，将完整地址切割为多个子地址段。`compare` 方法则按段比较两个 `SegmentedAddr` 是否相等。

### 4.4 SegmentedAddrNext — 分段门控寄存器

这是 `SegmentedAddr` 最核心的应用：

```scala
def apply(addr: UInt, segments: Seq[Int], fire: Bool,
          parentName: Option[String]): SegmentedAddr = {
  val segmented = SegmentedAddrInit(segments, addr).getAddrSegments()
  val modified = Wire(Vec(segmented.length, Bool()))
  val segmentedNext = segments zip segmented zip modified.zipWithIndex map {
    case ((segLength, seg), (modified, idx)) =>
      RegEnable(seg, 0.U(segLength.W), modified && fire)
        .suggestName(s"${parentName.getOrElse("")}_seg_${idx}_value")
  }
  modified zip segmentedNext zip segmented map {
    case ((m, next), now) => m := next =/= now
  }
  modified.last := true.B  // 最低位段总是被更新（假设低位段变化频繁）
}
```

工作原理：
1. 将输入地址按 `segments` 分割为多个子段
2. 对每个子段创建 `RegEnable`，使能条件为 `modified && fire`
3. `modified` 信号由 `next =/= now` 比较产生：只有当某段的寄存器值与当前输入值不同时，该段的 `modified` 为真
4. 最低位段 (`modified.last`) 始终设为 `true.B`，假设低位段变化最频繁

这种设计形成了一个级联的门控链：高位段不变时，不会触发寄存器更新，进而不会触发下一段的变化检测，形成天然的功耗优化层次。

### 4.5 dupAddrs — 多地址复制分段门控

`SegmentedAddrNext.dupAddrs` 扩展了分段门控以支持多个地址并行处理：

```scala
def dupAddrs(addrs: Seq[UInt], segments: Seq[Int], fire: Bool,
             parentName: Option[String]): Seq[SegmentedAddr]
```

该方法将多个地址按段分组，然后对每段的所有复制地址共享一个 `modified` 判断条件（以 `dupSegmented(segIdx)(0)` 为基准），进一步减少比较器数量。

---

## 5. ClockMux 无毛刺时钟切换

### 5.1 接口定义

`ClockMux` 定义在 `utility/src/main/scala/utility/ClockMux.scala`，是一个极简的 RawModule：

```scala
class ClockMux extends RawModule {
  val clk0   = IO(Input(Clock()))   // 时钟源 0
  val clk1   = IO(Input(Clock()))   // 时钟源 1
  val sel    = IO(Input(Bool()))    // 选择信号
  val clkout = IO(Output(Clock()))  // 输出时钟
  clkout := Mux(sel, clk1, clk0)
}
```

### 5.2 工作原理

当前的实现使用了 Chisel 的 `Mux` 操作符直接选择时钟源。虽然 `RawModule` 不插入复位和时钟域，但使用 `Mux` 进行时钟选择在 Chisel 语义层面是安全的。真正的无毛刺 (glitch-free) 保证依赖于后端综合工具在生成时钟切换电路时的实现。

在实际使用中，`ClockMux` 主要在 MBIST 子系统中被使用：

```scala
// MbistClockGateCell.scala
class MbistClockGateCell(mcpCtl: Boolean) extends Module {
  if (mcpCtl) {
    val clockMux = Module(new ClockMux)
    clockMux.clk0 := CG.io.Q          // 门控时钟
    clockMux.clk1 := dft.ram_aux_clk.asClock  // 辅助测试时钟
    clockMux.sel  := dft.ram_aux_ckbp         // 时钟切换选择
    out_clock := clockMux.clkout
  } else {
    out_clock := CG.io.Q
  }
}
```

### 5.3 MbistClockGateCell 集成

`MbistClockGateCell`（位于 `utility/src/main/scala/utility/mbist/MbistClockGateCell.scala`）是 ClockGate 和 ClockMux 的集成封装，专门用于 SRAM 的时钟管理：

- **TE 端口**: 连接到 `dft.cgen`（来自 `SramBroadcastBundle`）
- **E 端口**: 正常模式下使用功能使能 `E`；MBIST 模式下使用 `mbist.readen | mbist.writeen`；还考虑了 `ram_mcp_hold` 信号
- **输出时钟**: 无 MCP 控制时直接输出门控时钟；有 MCP 控制时通过 `ClockMux` 在门控时钟和辅助时钟之间切换

这种设计支持了 SRAM Memory Cell Repair (MCP) 的功能：在测试/修复模式下，可以通过 `ram_aux_ckbp` 切换到独立的辅助时钟，而不受正常功能时钟门控的影响。

---

## 6. ResetGen 3 级同步器

### 6.1 核心实现

`ResetGen` 定义在 `utility/src/main/scala/utility/ResetGen.scala`，是 XiangShan 的复位同步器核心模块：

```scala
class ResetGen(SYNC_NUM: Int = 3) extends Module {
  val o_reset = IO(Output(AsyncReset()))
  val dft = IO(Input(new DFTResetSignals()))

  private val lgc_rst = !dft.lgc_rst_n.asBool
  private val real_reset = Mux(dft.mode, lgc_rst, reset.asBool).asAsyncReset
  private val raw_reset = Wire(AsyncReset())

  withClockAndReset(clock, real_reset) {
    val pipe_reset = RegInit(((1L << SYNC_NUM) - 1).U(SYNC_NUM.W))
    pipe_reset := Cat(pipe_reset(SYNC_NUM - 2, 0), 0.U(1.W))
    raw_reset := pipe_reset(SYNC_NUM - 1).asAsyncReset
  }

  o_reset := Mux(dft.scan_mode, lgc_rst, raw_reset.asBool).asAsyncReset
}
```

### 6.2 3 级同步器工作原理

默认 `SYNC_NUM = 3`，使用 3-bit 移位寄存器实现异步复位释放的同步化：

1. **初始化**: `pipe_reset` 被初始化为 `3'b111`（复位有效状态）
2. **移位**: 每个时钟周期，`pipe_reset` 右移一位，低位补 0
3. **输出**: 取 `pipe_reset[2]`（最高位）作为同步后的复位信号

时序行为：
- 复位有效期间：`pipe_reset = 3'b111`，输出复位有效
- 复位释放后：`pipe_reset` 经过 3 个时钟周期依次变为 `3'b011 -> 3'b001 -> 3'b000`，最终释放复位

3 级同步器提供了足够的 MTBF (Mean Time Between Failures) 保证，消除了亚稳态 (metastability) 的风险。

### 6.3 复位源选择

在同步器之前，`real_reset` 通过 MUX 选择复位源：

```scala
private val real_reset = Mux(dft.mode, lgc_rst, reset.asBool).asAsyncReset
```

- `dft.mode = 0`（正常模式）：使用 `reset`（系统复位）
- `dft.mode = 1`（测试模式）：使用 `lgc_rst`（来自 DFT 控制器的逻辑复位）

---

## 7. 多域复位隔离（CPU / NoC / SoC / CLINT）

### 7.1 DFTResetSignals Bundle

```scala
class DFTResetSignals extends Bundle {
  val lgc_rst_n = AsyncReset()   // 逻辑复位（低有效）
  val mode = Bool()              // DFT 模式选择
  val scan_mode = Bool()         // Scan chain 模式
}
```

### 7.2 SoC 层多域复位架构

在 `src/main/scala/top/XSNoCTop.scala` 中，XiangShan 实现了多时钟域的独立复位同步：

```scala
// BaseXSSocImp trait
val cpuReset_sync = withClockAndReset(clock, cpuReset.asAsyncReset)(
  ResetGen(io.dft_reset))

// HasAsyncClockImp trait
val noc_reset_sync = socParams.EnableCHIAsyncBridge.map(_ =>
  withClockAndReset(noc_clock, noc_reset) { ResetGen(io.dft_reset) })
val soc_reset_sync = withClockAndReset(soc_clock, soc_reset) {
  ResetGen(io.dft_reset) }
val clint_reset_sync = withClockAndReset(clint_clock, clint_reset) {
  ResetGen(io.dft_reset) }
```

每个时钟域都有独立的 `ResetGen` 实例：
- **cpuReset_sync**: CPU 核心时钟域的同步复位，复位源为 `reset || !soc_rst_n`（系统复位或 SoC 软件复位）
- **noc_reset_sync**: NoC (Network-on-Chip) 时钟域的同步复位，可选启用（`EnableCHIAsyncBridge`）
- **soc_reset_sync**: SoC 总线时钟域的同步复位
- **clint_reset_sync**: CLINT (Core-Local Interruptor) 时钟域的同步复位

### 7.3 低功耗复位集成

```scala
val soc_rst_n = io.lp.map(_.i_cpu_sw_rst_n).getOrElse(true.B)
val cpuReset = reset.asBool || !soc_rst_n
```

CPU 复位不仅来自全局复位，还包含 SoC 低功耗控制器的软件复位请求。当 SoC 进入低功耗状态时，可以通过 `i_cpu_sw_rst_n` 信号触发 CPU 复位。

### 7.4 XSTileWrap 层级复位

```scala
// src/main/scala/xiangshan/XSTileWrap.scala:108-110
val reset_sync = withClockAndReset(clock,
  (reset.asBool || io.hartResetReq).asAsyncReset)(ResetGen(io.dft_reset))
val noc_reset_sync = EnableCHIAsyncBridge.map(_ =>
  withClockAndReset(clock, noc_reset.get)(ResetGen(io.dft_reset)))
val soc_reset_sync = withClockAndReset(clock, soc_reset)(
  ResetGen(io.dft_reset))
```

`XSTileWrap` 层增加了 `hartResetReq`（来自 Debug Module 的单核复位请求），使每个 hart 可以被独立复位。

### 7.5 ResetGen 工具方法 -- resetChain 与 resetTree

`ResetGen` companion object 提供了两种复位传播机制：

**resetChain（链式复位）**:
```scala
def apply(resetChain: Seq[Seq[Module]], reset: Reset, sim: Boolean,
          dft: Option[DFTResetSignals]): Seq[Reset]
```

按层级依次传递复位。每一层模块收到上一层传递来的同步复位后，再次通过 `ResetGen` 同步，产生下一层的复位。

**resetTree（树形复位）**:
```scala
case class ModuleNode(mod: Module) extends ResetNode
case class CellNode(reset: Reset) extends ResetNode
case class ResetGenNode(children: Seq[ResetNode]) extends ResetNode
```

使用代数数据类型 (ADT) 描述复位传播树结构。`ResetGenNode` 递归地对子节点传递复位。

### 7.6 实际复位树示例

**Backend 复位树** (`src/main/scala/xiangshan/backend/Backend.scala:569-586`):
```scala
val rightResetTree = ResetGenNode(Seq(
  ModuleNode(intRegion),
  ModuleNode(fpRegion),
  ModuleNode(topDownMod)
))
val leftResetTree = ResetGenNode(Seq(
  ModuleNode(vecRegion),
  ModuleNode(vecExcpMod),
  ResetGenNode(Seq(
    ModuleNode(ctrlBlock),
    CellNode(io.frontendReset)
  ))
))
ResetGen(leftResetTree, reset, sim = false, io.dft_reset)
ResetGen(rightResetTree, reset, sim = false, io.dft_reset)
```

这将 Backend 划分为左右两个复位域：右侧包含整数/浮点/监控模块，左侧包含向量/控制模块，前端复位作为叶子节点。

**Frontend 复位树** (`src/main/scala/xiangshan/frontend/Frontend.scala:1464-1487`):
```scala
val leftResetTree = ResetGenNode(
  Seq(ModuleNode(icache_wrapper), ModuleNode(predGen), ...))
val rightResetTree = ResetGenNode(
  Seq(ModuleNode(ftq), ModuleNode(bpu), ...))
ResetGen(leftResetTree, reset, sim = false, io.dft_reset)
ResetGen(rightResetTree, reset, sim = false, io.dft_reset)
```

### 7.7 复位域隔离的意义

多域复位隔离的设计实现了以下目标：
1. **独立复位**: 不同时钟域的模块可以独立复位，避免复位信号跨时钟域产生亚稳态
2. **DFT 支持**: 每个 `ResetGen` 都接收 `DFTResetSignals`，测试模式下可以独立控制每个域的复位行为
3. **仿真支持**: `sim` 参数控制是否在仿真中跳过 `ResetGen`，因为仿真环境通常不需要复位同步
4. **低功耗**: 可以只复位部分模块而保持其他模块运行，支持部分唤醒/休眠

---

## 8. DFT 复位信号（lgc_rst_n / mode / scan_mode）

### 8.1 三个信号的语义

`DFTResetSignals` Bundle 包含三个信号，每个在复位同步器中有明确的作用：

**lgc_rst_n (Logic Reset, active low)**:
- 来自 DFT 控制器的异步逻辑复位
- 低有效（`_n` 后缀表示 active low）
- 在 `ResetGen` 中转换为正逻辑：`private val lgc_rst = !dft.lgc_rst_n.asBool`

**mode**:
- DFT 模式选择信号
- `mode = 0`: 正常运行模式，复位源为系统 `reset`
- `mode = 1`: 测试模式，复位源切换为 `lgc_rst_n` 反转后的逻辑复位
- 实现：`private val real_reset = Mux(dft.mode, lgc_rst, reset.asBool).asAsyncReset`

**scan_mode**:
- Scan chain 测试模式信号
- `scan_mode = 0`: 正常模式，输出经过 3 级同步的 `raw_reset`
- `scan_mode = 1`: 测试模式，旁路 (bypass) 同步器，直接输出 `lgc_rst`
- 实现：`o_reset := Mux(dft.scan_mode, lgc_rst, raw_reset.asBool).asAsyncReset`

### 8.2 scan_mode 旁路的设计意义

当 `scan_mode` 有效时，复位信号直接绕过同步器输出。这是因为：
1. 在 scan chain 插入和测试模式下，需要精确控制复位时序
2. 同步器会引入额外的时钟延迟，不利于测试向量的精确施加
3. 测试模式下时钟通常由 ATE (Automatic Test Equipment) 外部控制，不存在跨时钟域的亚稳态问题

### 8.3 DFT 信号传播路径

```
ATE / MBIST Controller
        |
        v
  DFTResetSignals (lgc_rst_n, mode, scan_mode)
        |
        v
  XSNoCTop.io.dft_reset
        |
        +--> cpuReset_sync = ResetGen(io.dft_reset)     [CPU 域]
        +--> noc_reset_sync = ResetGen(io.dft_reset)     [NoC 域]
        +--> soc_reset_sync = ResetGen(io.dft_reset)     [SoC 域]
        +--> clint_reset_sync = ResetGen(io.dft_reset)   [CLINT 域]
        |
        v (传入 XSTileWrap)
  XSTileWrap.io.dft_reset
        |
        +--> XSTileWrap.reset_sync = ResetGen(io.dft_reset)
        |
        v (传入 XSTile)
  XSTile.io.dft_reset
        |
        +--> XSCore, Frontend, Backend, MemBlock
              各模块内部 ResetGen 树形传播
```

### 8.4 条件化 DFT 信号

DFTResetSignals 的 IO 是条件化创建的：

```scala
// XSNoCTop.scala
private val hasMbist = p(DFTOptionsKey).EnableMbist
val dft_reset = Option.when(hasMbist)(IO(Input(new DFTResetSignals())))
```

只有当 MBIST 功能启用时（`EnableMbist = true`），DFTResetSignals 才会作为模块 IO 暴露。如果未启用 MBIST，`ResetGen` 会使用默认值：

```scala
resetSync.dft := dft.getOrElse(0.U.asTypeOf(new DFTResetSignals))
```

默认的全零值意味着：`lgc_rst_n = 0`（复位无效），`mode = 0`（正常模式），`scan_mode = 0`（正常模式）。

---

## 9. 源文件位置索引

### 核心 Clock Gating 源文件

| 文件 | 路径 | 说明 |
|------|------|------|
| ClockGate.scala | `utility/src/main/scala/utility/ClockGate.scala` | ClockGate BlackBox、genTeSrc/genTeSink |
| ClockGatedReg.scala | `utility/src/main/scala/utility/ClockGatedReg.scala` | GatedValidRegNext、GatedRegNext、GatedRegEnable、SegmentedAddr |
| ClockMux.scala | `utility/src/main/scala/utility/ClockMux.scala` | 无毛刺时钟 MUX |
| MbistClockGateCell.scala | `utility/src/main/scala/utility/mbist/MbistClockGateCell.scala` | MBIST 集成时钟门控单元 |
| ResetGen.scala | `utility/src/main/scala/utility/ResetGen.scala` | ResetGen 同步器、ResetNode ADT |

### TE 信号源/汇调用位置

| 调用类型 | 文件 | 行号 |
|----------|------|------|
| genTeSrc | `src/main/scala/xiangshan/backend/Backend.scala` | 561 |
| genTeSrc | `src/main/scala/xiangshan/frontend/Frontend.scala` | 313 |
| genTeSrc | `src/main/scala/xiangshan/mem/MemBlock.scala` | 1591 |
| genTeSrc | `XSCache/src/main/scala/coupledL2/CoupledL2.scala` | 797 |
| genTeSink | `src/main/scala/xiangshan/cache/mmu/PageTableCache.scala` | 515 |
| genTeSink | `src/main/scala/xiangshan/cache/mmu/BitmapCheck.scala` | 457 |
| genTeSink | `src/main/scala/xiangshan/backend/exu/ExeUnit.scala` | 121 |

### ResetGen 调用位置

| 文件 | 说明 |
|------|------|
| `src/main/scala/top/XSNoCTop.scala` | SoC 层多域复位同步 (cpu/noc/soc/clint) |
| `src/main/scala/xiangshan/XSTileWrap.scala` | Tile 级复位链 |
| `src/main/scala/xiangshan/XSTile.scala` | XSTile 复位树 |
| `src/main/scala/xiangshan/backend/Backend.scala` | Backend 复位树 (left/right) |
| `src/main/scala/xiangshan/frontend/Frontend.scala` | Frontend 复位树 (left/right) |
| `src/main/scala/xiangshan/mem/MemBlock.scala` | MemBlock 复位 |
| `src/main/scala/top/Top.scala` | 顶层复位链 |
| `src/main/scala/device/standalone/StandAloneDebugModule.scala` | Debug Module JTAG 复位 |

### GatedValidRegNext / GatedRegNext / GatedRegEnable 主要使用者

| 文件 | 用途 |
|------|------|
| `utility/src/main/scala/utility/DataModuleTemplate.scala` | 数据模块写使能门控 |
| `utility/src/main/scala/utility/sram/SRAMTemplate.scala` | SRAM 读有效寄存器门控 |
| `utility/src/main/scala/utility/PipelineConnect.scala` | 流水线级间门控 |
| `utility/src/main/scala/utility/Hold.scala` | 数据保持逻辑 |
| `yunsuan/src/main/scala/yunsuan/util/ClockGatedReg.scala` | 运算单元专用门控寄存器 |
| `XSCache/src/main/scala/coupledL2/utils/SplittedSRAM.scala` | L2 Cache SRAM 门控 |

---

## 总结

XiangShan 的 Clock Gating 与 Reset 体系体现了以下几个关键设计原则：

1. **层级化**: 从顶层 SoC 的多域复位，到 Tile 级的复位链，再到模块内部的复位树，形成了清晰的层级复位架构。

2. **集中化 TE 管理**: 通过 `ClockGate.genTeSrc/genTeSink` + `BoringUtils` 的机制，将分散在各模块中的 DFT 控制信号集中管理，无需手动 IO 传递。

3. **数据驱动门控**: `GatedRegNext` 系列工具通过 `last =/= next` 的变化检测，实现了自适应的时钟门控——数据不变时寄存器不翻转，自动节省功耗。

4. **地址分段优化**: `SegmentedAddr` 将宽地址分段独立门控，利用地址高位变化频率低于低位的特性，减少了大量无意义的寄存器更新。

5. **DFT 可测试性**: 通过 `DFTResetSignals` 的三个控制信号 (`lgc_rst_n`、`mode`、`scan_mode`)，实现了正常模式/测试模式/scan 模式下的完整复位控制灵活性。

6. **仿真友好**: `sim` 参数和 `ResetGen` 的条件化编译确保了仿真效率，避免了不必要的同步器延迟。
