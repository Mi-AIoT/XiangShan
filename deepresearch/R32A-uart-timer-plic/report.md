# R32A - UART / Timer / PLIC 外设深度分析报告

## 目录

1. [AXI4UART - 简易 UART（4 寄存器）](#1-axi4uart---简易-uart4-寄存器)
2. [AXI4UART16550 - NS16550A 兼容 UART](#2-axi4uart16550---ns16550a-兼容-uart)
3. [Timer 实现（AXI4Timer / TLTimer / TIMER-CLINT / SYSCNT）](#3-timer-实现)
4. [PLIC（AXI4Plic）- 1023 中断源](#4-plicaxi4plic---1023-中断源)
5. [IMSIC Bus 集成](#5-imsic-bus-集成)
6. [UART C++ 仿真模型](#6-uart-c-仿真模型)
7. [中断连线至处理器](#7-中断连线至处理器)
8. [源文件位置索引](#8-源文件位置索引)

---

## 1. AXI4UART - 简易 UART（4 寄存器）

### 1.1 设计概述

`AXI4UART` 是 XiangShan 中最简单的串口外设实现，仅有 4 个 32 位寄存器，通过 AXI4 Slave 总线接口接入 SoC。它本质上是一个 **UART Lite**（与 Xilinx AXI UARTLite IP 兼容），适用于基本的字符输入输出，不支持 FIFO、中断或波特率配置。

源文件：`src/main/scala/device/AXI4UART.scala`

### 1.2 寄存器映射

| 偏移地址 | 寄存器名 | 读/写 | 说明 |
|----------|---------|-------|------|
| `0x0`    | RXFIFO  | 只读  | 接收数据寄存器。读取返回仿真框架送入的字符。 |
| `0x4`    | TXFIFO  | 只写  | 发送数据寄存器。写入低 8 位触发字符输出。 |
| `0x8`    | STAT    | 读/写 | 状态寄存器（未实际使用状态位）。 |
| `0xC`    | CTRL    | 读/写 | 控制寄存器（未实际使用控制位）。 |

### 1.3 数据通路

**发送通路：** 当 CPU 写地址偏移 `0x4` 时（即 `waddr(3,0) === 4.U && in.w.fire`），模块通过 `io.extra.get.out.valid` 产生一个有效脉冲，并将 `in.w.bits.data(7:0)` 作为输出字符 `io.extra.get.out.ch`。该信号连接到仿真框架的 `UARTIO.out` 端口，最终由 C++ 侧的 `uart_getc()` 回调读取并打印到终端。

**接收通路：** 当 CPU 读地址偏移 `0x0` 时（即 `raddr(3,0) === 0.U && in.r.fire`），模块拉高 `io.extra.get.in.valid`，仿真框架在 `io.extra.get.in.ch` 上放置一个输入字符。如果无输入，通常返回 `0xFF`（表示无字符）。

### 1.4 AXI4 Slave 基类机制

`AXI4UART` 继承自 `AXI4SlaveModule`（文件：`src/main/scala/device/AXI4SlaveModule.scala`），该基类封装了完整的 AXI4 协议处理逻辑：

- **状态机**：`s_idle -> s_rdata / s_wdata -> s_wresp`，处理 burst 事务
- **地址管理**：通过 `HoldUnless` 在 `AR/AW` 握手时锁存地址，支持 INCR burst
- **数据掩码**：`MaskExpand(in.w.bits.strb)` 生成字节级写掩码，通过 `genWdata()` 实现部分写
- **只支持 INCR burst**：断言检查 `burst === AXI4Parameters.BURST_INCR`

### 1.5 SoC 集成

在 `HaveAXI4PeripheralPort` trait 中（SoC.scala），UART Lite 被注册为一个 AXI4 slave 设备：

```scala
val uartLiteDevice = new SimpleDevice("serial", Seq("xilinx,uartlite"))
val uartLiteParams = AXI4SlaveParameters(
  address = Seq(soc.UARTLiteRange),
  ...
)
```

`SimMMIO` 将 `AXI4UART` 和 `AXI4UART16550` 的输出合并为单一 `UARTIO` 端口：

```scala
io.uart.out.valid := uartLite.module.io.extra.get.out.valid | uart16550.module.io.uart.out.valid
io.uart.out.ch := Mux(uartLite.module.io.extra.get.out.valid,
  uartLite.module.io.extra.get.out.ch,
  uart16550.module.io.uart.out.ch)
```

---

## 2. AXI4UART16550 - NS16550A 兼容 UART

### 2.1 设计概述

`AXI4UART16550`（源文件：`src/main/scala/device/AXI4UART16550.scala`）是对经典 **NS16550A** UART 控制器的完整 RTL 实现。它兼容 Linux 内核的 `ns16550a` 驱动，支持：

- 可配置 FIFO（默认深度 16）
- 完整的中断产生（4 个中断源，可屏蔽）
- 可配置波特率分频器（DLL/DLM）
- Modem 控制回环（loopback）
- 字符超时中断（CTI）

### 2.2 参数化配置

```scala
case class UART16550Params(
  address:   BigInt,         // 基地址
  beatBytes: Int  = 8,       // 总线位宽
  fifoDepth: Int  = 16,      // FIFO 深度
  baudBase:  Int  = 1843200, // 波特率基数
  clockFreq: BigInt = 50000000L // 系统时钟频率（50MHz）
)
```

该模块使用 `RegisterRouter` 作为 AXI4 总线适配层，`compat = Seq("ns16550a")` 使其在设备树（Device Tree）中被识别为 NS16550A 兼容设备。

### 2.3 寄存器映射（8 个 32 位寄存器，偏移 0x00-0x1C）

| 偏移 | LCR[7]=0 读 | LCR[7]=0 写 | LCR[7]=1 读/写 | 说明 |
|------|------------|------------|----------------|------|
| `0x00` | RBR（接收缓冲） | THR（发送保持） | DLL | 低位除数锁存 |
| `0x04` | IER（中断使能） | IER | DLM | 高位除数锁存 |
| `0x08` | IIR（中断标识，只读） | FCR（FIFO 控制，只写） | IIR/FCR | FIFO 深度/触发级别 |
| `0x0C` | LCR（线路控制） | LCR | LCR | 数据位/停止位/校验 |
| `0x10` | MCR（Modem 控制） | MCR | MCR | 流控/回环 |
| `0x14` | LSR（线路状态） | LSR（只读） | LSR | TX/RX 状态标志 |
| `0x18` | MSR（Modem 状态） | MSR（只读） | MSR | Modem 握手状态 |
| `0x1C` | SCR（暂存寄存器） | SCR | SCR | 可读写暂存 |

### 2.4 FIFO 实现

模块内部实例化两个 `Queue` 作为 TX/RX FIFO：

```scala
val recvFifo = Module(new Queue(UInt(8.W), fifoDepth))
val xmitFifo = Module(new Queue(UInt(8.W), fifoDepth))
```

**RX FIFO 触发级别（Interrupt Trigger Level, ITL）** 通过 FCR 的 bit[7:6] 选择：

```scala
val rxTriggerLut = VecInit(1.U, 4.U, 8.U, 14.U) // 对应 FIFO 深度 16
```

当 FIFO 使能位（FCR[0]）为 1 时，RX 数据先进入 FIFO，读取 RBR 时从 FIFO 弹出；未使能时退化为单字符缓冲模式。

### 2.5 中断机制

NS16550A 定义了 4 级中断优先级（从高到低）：

| 优先级 | IIR[3:0] | 条件 | IER 位 |
|--------|----------|------|--------|
| 1（最高） | `0x6` | LSR 异常（OE/PE/FE/BI） | IER[2] |
| 2 | `0xC` | 字符超时（RX FIFO 非空但无新数据） | IER[0] |
| 3 | `0x4` | RX 可用（DR=1 或 FIFO 达到 ITL） | IER[0] |
| 4 | `0x2` | TX 空（THRE 上升沿） | IER[1] |
| 5（最低） | `0x0` | Modem 状态变化 | IER[3] |

```scala
val irqSel = WireInit(1.U(8.W)) // 默认无中断（IIR[0]=1 表示无挂起中断）
when (ier(2) && lsrIntAny) { irqSel := 0x06.U }
  .elsewhen (ier(0) && timeoutPending) { irqSel := 0x0c.U }
  .elsewhen (ier(0) && rdiHit) { irqSel := 0x04.U }
  .elsewhen (ier(1) && thrIPending) { irqSel := 0x02.U }
  .elsewhen (ier(3) && msiHit) { irqSel := 0x00.U }
```

中断输出通过 `interrupts.head` 连接到 Rocket Chip 的中断交叉开关，最终汇聚到 PLIC 或直接接入处理器。

### 2.6 波特率与时序

波特率分频器由 DLM:DLL（16 位）构成。模块计算方式为：

```scala
val baud = (params.baudBase.U + (divisorWide >> 1)) / divisorWide
val charCycles = 1.U // 仿真中简化为 1 cycle per char
```

在实际硬件中 `charCycles` 应为 `(clockFreq / baud) * frameBits`，但为加速仿真设为 1。帧格式由 LCR[1:0]（数据位）、LCR[2]（停止位）、LCR[3]（校验位）决定。

### 2.7 Modem 控制回环

当 MCR[4]（LOOP）置位时，TX 输出被回送到 RX 输入，实现内部回环测试。回环映射符合 NS16550A 规范：

```scala
when(mcr(4)) {
  msrNext := Cat(mcr(3), mcr(2), mcr(0), mcr(1), 0.U(4.W))
  // OUT2->DCD, OUT1->RI, RTS->CTS, DTR->DSR
}
when(txFire && mcr(4)) { pushRx(tsr) } // TX 字符送回 RX FIFO
```

---

## 3. Timer 实现

XiangShan 包含四个不同的定时器模块，各有不同的用途和总线接口。

### 3.1 AXI4Timer（AXI4 总线）

**源文件：** `src/main/scala/device/AXI4Timer.scala`

这是一个简单的 64 位 timer，通过 AXI4 Slave 接口接入。

**寄存器映射：**

| 偏移地址 | 寄存器 | 说明 |
|----------|--------|------|
| `0x4000` | mtimecmp | 比较值，触发定时器中断 |
| `0x8000` | freq | 时钟分频因子 |
| `0x8008` | inc | 每次 tick 的增量 |
| `0xBFF8` | mtime | 当前时间（只读） |

**工作原理：** 内部维护一个自由运行的计数器 `cnt`，每次达到 `freq` 时产生 tick，mtime 增加 `inc`。当 `mtime >= mtimecmp` 时产生 `mtip`（Machine Timer Interrupt Pending）信号。

```scala
val clk = if (!sim) 40 else 10000 // 非仿真: 40MHz/1000000, 仿真: 10000
val freq = RegInit(clk.U(16.W))
val inc = RegInit(1000.U(16.W))
io.extra.get.mtip := RegNext(mtime >= mtimecmp)
```

### 3.2 TLTimer（TileLink 总线）

**源文件：** `src/main/scala/device/TLTimer.scala`

基于 TileLink 总线的 CLINT（Core Local Interruptor）风格定时器，支持多核。

**关键特性：**
- 通过 `TLRegisterNode` 接入 TileLink 总线
- 支持 `numCores` 个核心，每个核心独立的 `mtip` 和 `msip`
- 寄存器映射兼容 RISC-V CLINT 规范

**寄存器映射：**

| 偏移 | 寄存器 | 说明 |
|------|--------|------|
| `0x0000 + i*4` | msip[i] | 核心 i 的软件中断挂起 |
| `0x4000 + i*8` | mtimecmp[i] | 核心 i 的比较值 |
| `0x8000` | freq | 时钟分频因子 |
| `0x8008` | inc | 递增步长 |
| `0xBFF8` | mtime | 64 位系统时间（只读） |

**中断产生：**

```scala
io.mtip(i) := RegNext(mtime >= mtimecmp(i))
io.msip(i) := RegNext(msip(i) =/= 0.U)
```

### 3.3 TIMER（CLINT，TileLink）

**源文件：** `src/main/scala/device/TIMER.scala`

这是 XiangShan 主 SoC 中实际使用的 CLINT 实现，通过 Rocket Chip 的 TileLink Diplomacy 框架接入。

**与 TLTimer 的区别：**
- 使用 `IntNexusNode` 直接产生中断并连接到处理器的中断端口
- 支持 `MaxHartIdBits` 定义的最大核数（`1 << MaxHartIdBits`）
- 通过 `CanHavePeripheryCLINT` trait 在 SoC 级别实例化
- 使用 `CLINTKey` 和 `CLINTAttachKey` CDE 配置键

**中断输出：**

```scala
intnode_out.zipWithIndex.foreach { case (int, i) =>
  int(0) := ShiftRegister(ipi(io.hartId)(0), params.intStages)  // msip
  int(1) := ShiftRegister(time.asUInt >= timecmp(io.hartId).asUInt, params.intStages) // mtip
}
```

注意 `io.hartId` 输入使得中断仅从当前 hart 的寄存器读取（多核场景），`intStages` 参数允许添加流水线级数以优化时序。

**SoC 实例化（SoC.scala 第 515 行）：**

```scala
val timer = LazyModule(new TIMER(TIMERParams(IsSelfTest = true, soc.TIMERRange.base), 8))
timer.module.io.time <> syscnt.module.io.time   // 时间源来自 SYSCNT
timer.module.io.hartId := 0.U                    // 单核模式
```

### 3.4 SYSCNT（系统计数器）

**源文件：** `src/main/scala/device/SYSCNT.scala`

SYSCNT 是一个专用的系统级时间计数器，作为 `mtime` 的硬件时钟源。它替代了传统 CLINT 中的自由运行计数器，提供更灵活的频率配置。

**核心设计：**
- **双时钟域设计**：`rtc_clock`（实时时钟域）和 `bus_clock`（总线时钟域）
- **频率可动态调整**：通过 `freqidx` 寄存器选择递增步长
- **跨时钟域同步**：使用 `AsyncResetSynchronizerShiftReg` 实现 3 级同步
- **外部可控**：支持软件更新时间值、停止计数、外部触发更新

**频率映射（freqidx bit[1:0]）：**

| freqidx | 递增步长 | 等效频率 |
|---------|---------|---------|
| 0 | 1 | 1 GHz |
| 1 | 2 | 500 MHz |
| 2 | 4 | 250 MHz |
| 3 | 8 | 125 MHz |

**寄存器映射：**

| 偏移 | 寄存器 | 说明 |
|------|--------|------|
| `0xBFF8` | mtime | 64 位时间值（跨时钟域同步读取） |
| `0xC000` | freqidx | 频率索引（3 位） |
| `0xC008` | freqidxReq | 频率更新请求 |
| `0xC010` | mtimeReq | 软件更新时间请求 |

**跨时钟域处理流程：**

```
bus_clock 域                    rtc_clock 域
  写 freqidx ──3级同步──> inccfg_vld ──> 更新 incwidth
  写 time_sw ──3级同步──> time_req_rtc_ris ──> time := time_sw
  time 产生 ──TimeAsync──> time_rpt_bus（返回 bus_clock 域）
```

`TimeAsync` 模块（源文件：`src/main/scala/device/TimeAsync.scala`）使用边沿检测方式将 `ValidIO[UInt(64)]` 从 `rtc_clock` 域同步到 `bus_clock` 域。

**SoC 中的连接关系：**

```scala
syscnt.module.rtc_clock := rtc_clock
syscnt.module.rtc_reset := rtc_reset
syscnt.module.bus_clock := bus_clock
timer.module.io.time <> syscnt.module.io.time  // TIMER 直接使用 SYSCNT 的输出
```

---

## 4. PLIC（AXI4Plic）- 1023 中断源

### 4.1 设计概述

`AXI4Plic`（源文件：`src/main/scala/device/AXI4Plic.scala`）实现了 RISC-V **Platform-Level Interrupt Controller** 规范。它是 XiangShan 中管理外部中断的核心模块。

**关键参数：**
- 最大中断源数量：**1023**（编号 1-1023，编号 0 不存在）
- 最大支持 hart 数：**15872**
- 地址空间大小：**0x4000000**（64 MB）

### 4.2 完整寄存器映射

```
base + 0x000000        保留（中断源 0 不存在）
base + 0x000004        中断源 1 优先级
base + 0x000008        中断源 2 优先级
...
base + 0x000FFC        中断源 1023 优先级

base + 0x001000        Pending 位 [0:31]
...
base + 0x00107C        Pending 位 [992:1023]

base + 0x002000        Context 0 的 Enable 位 [0:31]
...
base + 0x00207F        Context 0 的 Enable 位 [992:1023]
base + 0x002080        Context 1 的 Enable 位 [0:31]
...

base + 0x200000        Context 0 的优先级阈值
base + 0x200004        Context 0 的 Claim/Complete
...
base + 0x3FFE000       Context 15871 的优先级阈值
base + 0x3FFE004       Context 15871 的 Claim/Complete
```

### 4.3 内部数据结构

```scala
// 每个中断源一个 32 位优先级寄存器
val priority = List.fill(numExtIntrs)(Reg(UInt(32.W)))

// Pending 位向量（每个中断一个 bit，软件不可写）
val pending = List.fill(nrIntrWord)(RegInit(0.U.asTypeOf(Vec(32, Bool()))))

// 每个 hart 每个中断一组 enable 寄存器
val enable = List.fill(numCores)(List.fill(nrIntrWord)(RegInit(0.U(32.W))))

// 每个 hart 一个优先级阈值
val threshold = List.fill(numCores)(Reg(UInt(32.W)))

// Claim/Complete 寄存器
val claimCompletion = List.fill(numCores)(Reg(UInt(32.W)))
```

### 4.4 中断处理流程

**1. Pending 置位：**

外部中断输入 `io.extra.get.intrVec` 经过 3 级寄存器同步后，检测上升沿置位 pending 位：

```scala
intrVecReg := RegNext(RegNext(RegNext(io.extra.get.intrVec)))
intrVecReg.asBools.zipWithIndex.map { case (intr, i) =>
  when(intr) { pending(id / 32)(id % 32) := true.B }
  when(inHandle(id)) { pending(id / 32)(id % 32) := false.B }
}
```

**2. Claim（中断确认）：**

CPU 读取 Claim/Complete 寄存器（如 `base + 0x200004`）时：
- 计算最高优先级且已使能的 pending 中断号
- 设置 `inHandle` 标志，清除对应 pending 位
- 返回中断源编号（0 表示无中断）

```scala
val takenVec = pendingVec & Cat(enable(hart))
r := Mux(takenVec === 0.U, 0.U, PriorityEncoder(takenVec))
```

**3. Complete（中断完成）：**

CPU 写入中断源编号到 Claim/Complete 寄存器时：

```scala
def completionFn(wdata: UInt) = {
  inHandle(wdata(31, 0)) := false.B  // 清除 inHandle 标志
  0.U
}
```

**4. MEIP 产生：**

当某个 hart 的 claimCompletion 值非零时，产生 Machine External Interrupt：

```scala
io.extra.get.meip.zipWithIndex.map { case (ip, hart) =>
  ip := claimCompletion(hart) =/= 0.U
}
```

### 4.5 总线接口

`AXI4Plic` 继承自 `AXI4SlaveModule`，使用 AXI4 协议。读取数据经过窄化处理：

```scala
in.r.bits.data := Fill(2, rdata) // 将 32 位数据复制到 64 位总线的高/低 32 位
```

写掩码支持字节级写入：

```scala
MaskExpand(in.w.bits.strb >> waddr(2, 0))
```

### 4.6 StandAlone 版本

`StandAlonePLIC`（源文件：`src/main/scala/device/standalone/StandAlonePLIC.scala`）封装了基于 TileLink 的 Rocket Chip `TLPLIC`，用于独立测试或 FPGA 集成。它通过 `IntBuffer` 缓冲中断输出。

---

## 5. IMSIC Bus 集成

### 5.1 设计概述

`imsic_bus_top`（源文件：`src/main/scala/device/imsic_axi_top.scala`）是 XiangShan 中 **Incoming MSI Interrupt Controller (IMSIC)** 的总线适配层。IMSIC 是 RISC-V AIA（Advanced Interrupt Architecture）规范中的核心组件，支持 MSI（Message-Signaled Interrupts）中断投递。

### 5.2 三种总线模式

```scala
object IMSICBusType extends Enumeration {
  val NONE, TL, AXI = Value
}
```

模块根据 `soc.IMSICBusType` 配置参数选择不同的总线实现：

**TL 模式：**
```scala
val tl_reg_imsic = Option.when(soc.IMSICBusType == device.IMSICBusType.TL)(
  LazyModule(new aia.TLRegIMSIC(soc.IMSICParams, seperateBus = true))
)
```
- 使用两个 `TLClientNode`（sourceId 范围 0-65535）
- 通过 `TLWidthWidget(4)` + `TLFIFOFixer()` + `TLBuffer()` 连接到 IMSIC
- 提供 `tl_m`（master）和 `tl_s`（slave）两个 TileLink 端口

**AXI 模式：**
```scala
val axi_reg_imsic = Option.when(soc.IMSICBusType == device.IMSICBusType.AXI)(
  LazyModule(new aia.AXIRegIMSIC_WRAP(soc.IMSICParams, seperateBus = false))
)
```
- 使用单个 `AXI4MasterNode`（ID 范围 0-65535）
- 通过 `AXI4Buffer()` 连接到 IMSIC crossbar
- 内部 `imsic_xbar1to2` 将 AXI 请求分发到两个 IMSIC 实例（machine-mode 和 supervisor-mode）

**None 模式：**
```scala
val msi = Option.when(soc.IMSICBusType == device.IMSICBusType.NONE)(
  IO(new aia.MSITransBundle(soc.IMSICParams))
)
```
- 直接暴露 `MSITransBundle` IO 端口，用于外部连接

### 5.3 TEE IMSIC 支持

当 `soc.IMSICParams.HasTEEIMSIC` 为 true 时，额外提供 `teemsiio` 端口，用于 Trusted Execution Environment（如 TEE）的中断投递：

```scala
val teemsiio = Option.when(soc.IMSICParams.HasTEEIMSIC)(
  IO(Flipped(new aia.MSITransBundle(soc.IMSICParams)))
)
```

### 5.4 与 PLIC 的关系

在 RISC-V AIA 规范中，PLIC 和 IMSIC 可以共存：
- **PLIC** 负责传统的电平触发外部中断
- **IMSIC** 负责基于 MSI 的中断投递，提供更灵活的中断路由和优先级管理
- 在 XiangShan 的 SoC 配置中，两者通过不同的配置键（`PLICKey` 和 `IMSICBusType`）独立控制

---

## 6. UART C++ 仿真模型

### 6.1 设计概述

`uart.cpp` 和 `uart.h`（文件：`difftest/src/test/csrc/common/`）提供了 UART 的 C++ 仿真后端，用于在功能仿真中模拟串口交互。

### 6.2 数据结构

```cpp
#define QUEUE_SIZE 1024
static char queue[QUEUE_SIZE] = {};  // 环形缓冲区
static int f = 0, r = 0;            // front 和 rear 指针
```

使用一个 1024 字节的环形缓冲区（circular queue）存储预设输入。

### 6.3 核心 API

**`uart_getc()` - 获取输入字符：**

```cpp
uint8_t uart_getc() {
  uint32_t now = uptime();
  uint8_t ch = -1;
  // 每 60 秒打印当前时间（调试用）
  if (now - lasttime > 60 * 1000) {
    eprintf(ANSI_COLOR_RED "now = %ds\n" ANSI_COLOR_RESET, now / 1000);
    lasttime = now;
  }
  return ch; // 默认返回 -1（无字符）
}
```

**`uart_getc_legacy()` - 带预设输入的版本：**

```cpp
void uart_getc_legacy(uint8_t *ch) {
  *ch = -1;
  if (now > 4 * 3600 * 1000) { // 4 小时后开始发送预设输入
    *ch = uart_dequeue();
  }
}
```

**`uart_dequeue()` - 从缓冲区取字符：**

```cpp
static int uart_dequeue(void) {
  if (f != r) {
    k = queue[f];
    f = (f + 1) % QUEUE_SIZE;
  } else {
    // 缓冲区为空时循环输出 "root\n"（用于 Debian 启动登录）
    k = "root\n"[last++];
    if (last == 5) last = 0;
  }
  return k;
}
```

### 6.4 预设输入（Preset Input）

`preset_input()` 函数在 `init_uart()` 中调用，预填充不同场景的模拟输入：

```cpp
// Debian 场景
char debian_cmd[128] = "root\n";

// Busybox 场景
char busybox_cmd[128] =
    "ls\n"
    "echo 123\n"
    "cd /root/benchmark\n"
    "./stream\n"
    ...

// RT-Thread 场景
char rtthread_cmd[128] = "memtrace\n";

// PAL 游戏场景
char init_cmd[128] = "2jjjjjjjkkkkkk"; // 选择角色并移动
```

默认使用 `debian_cmd` 作为预设输入。

### 6.5 Difftest 集成

`UARTIO` 类（定义在 `difftest/src/main/scala/SimTop.scala` 第 51 行）是 Chisel 硬件与 C++ 仿真之间的桥梁：

```scala
class UARTIO extends Bundle {
  val out = new Bundle {
    val valid = Output(Bool())
    val ch = Output(UInt(8.W))
  }
  val in = new Bundle {
    val valid = Output(Bool())
    val ch = Input(UInt(8.W))
  }
}
```

在 SimTop 中，UARTIO 端口被标记为 difftest 专用端口，通过 DPI-C 接口与 C++ 仿真环境通信。硬件侧 UART 模块的 `io.extra.get.out` 输出连接到 `UARTIO.out`，C++ 侧通过 `uart_getc()` 回调消费输出字符；C++ 侧通过 `UARTIO.in` 向硬件注入输入字符。

---

## 7. 中断连线至处理器

### 7.1 中断类型与 DTS 编号

在 `XSDts.scala`（源文件：`src/main/scala/xiangshan/XSDts.scala`）中定义了 XiangShan 处理器的中断编号映射：

| 编号 | 中断类型 | 来源 |
|------|---------|------|
| 3 | msip | CLINT（软件中断） |
| 7 | mtip | CLINT（定时器中断） |
| 11 | meip | PLIC（外部中断） |
| 9 | seip | PLIC（Supervisor 外部中断） |
| 65535 | debug | Debug Module |
| 31 | nmi_31 | 非屏蔽中断 |
| 43 | nmi_43 | 非屏蔽中断 |

### 7.2 处理器侧中断接收

在 `Bundle.scala`（`src/main/scala/xiangshan/Bundle.scala`）中定义了处理器核心的中断输入端口：

```scala
val mtip = Input(Bool())  // Machine Timer Interrupt
val msip = Input(Bool())  // Machine Software Interrupt
val meip = Input(Bool())  // Machine External Interrupt
```

### 7.3 CSR 中断处理

**旧版 CSR（CSR.scala）：**

```scala
mipWire.t.m := csrio.externalInterrupt.mtip  // CLINT -> CSR
mipWire.s.m := csrio.externalInterrupt.msip
mipWire.e.m := csrio.externalInterrupt.meip

val intrVec = Cat(debugIntr && !debugMode, (mie(11,0) & mip.asUInt & intrVecEnable.asUInt))
val intrBitSet = intrVec.orR
csrio.interrupt := intrBitSet
```

**新版 CSR（NewCSR.scala）：**

```scala
intrMod.io.in.platform.meip := platformIRP.MEIP
intrMod.io.in.fromAIA.meip := fromAIA.meip
// AIA 和传统 PLIC 的 MEIP 信号合并处理
```

新版 CSR 引入了 `fromAIA.meip` 信号，支持 AIA 架构的中断投递。当 AIA 模式启用时，IMSIC 的中断信号直接绕过 PLIC 进入处理器。

### 7.4 SoC 级中断连接

**PLC 中断源（SoC.scala）：**

```scala
val plic = LazyModule(new TLPLIC(PLICParams(soc.PLICRange.base), 8))
val plicSource = LazyModule(new IntSourceNodeToModule(NrExtIntr))
plic.intnode := plicSource.sourceNode
```

外部中断经过 3 级同步后送入 PLIC：

```scala
for ((plic_in, interrupt) <- plicSource.module.in.zip(ext_intrs.asBools)) {
  val ext_intr_sync = RegInit(0.U(3.W))
  ext_intr_sync := Cat(ext_intr_sync(1, 0), interrupt)
  plic_in := ext_intr_sync(2)
}
```

**Timer 中断（SoC.scala）：**

```scala
clintTime := syscnt.module.io.time
timer.module.io.time <> syscnt.module.io.time
timer.module.io.hartId := 0.U
```

**SimMMIO 中断向量合并：**

```scala
io.interrupt.intrVec := intrGen.module.io.extra.get.intrVec |
  uart16550Int.elts.head.head << (uart16550IntNum - 1)
```

UART16550 的中断通过 `IntSinkNode` 连接到 `intrVec`，中断号为 `0xa`（10），与 `AXI4IntrGenerator` 的中断信号进行 OR 合并。

### 7.5 完整中断数据流

```
外部设备中断 ──3级同步──> PLIC（优先级/使能/阈值） ──meip──> 处理器 CSR.mip
                                                              │
SYSCNT (mtime) ──> TIMER (CLINT) ──mtip──────────────> 处理器 CSR.mip
                                  ──msip──────────────> 处理器 CSR.mip
                                                              │
IMSIC (MSI) ────────────────────────────────meip(AIA)──> 处理器 NewCSR
                                                              │
                                                    mie & mip & intrVecEnable
                                                              │
                                                    intrVec = OR reduction
                                                              │
                                                    csrio.interrupt = intrBitSet
                                                              │
                                                    intrNO = PriorityEncoder(intrVec)
                                                              │
                                                    陷阱处理（trap/exception handler）
```

---

## 8. 源文件位置索引

### 8.1 Chisel RTL 源文件（src/main/scala/device/）

| 文件 | 模块 | 说明 |
|------|------|------|
| `AXI4UART.scala` | `AXI4UART` | 简易 4 寄存器 UART（UART Lite） |
| `AXI4UART16550.scala` | `AXI4UART16550` | NS16550A 兼容 UART（FIFO/中断/波特率） |
| `AXI4Timer.scala` | `AXI4Timer` | AXI4 接口的简单 timer |
| `TLTimer.scala` | `TLTimer` | TileLink 接口的多核 timer |
| `TIMER.scala` | `TIMER` / `CanHavePeripheryCLINT` | 主 SoC 使用的 CLINT 实现 |
| `SYSCNT.scala` | `SYSCNT` | 系统级时间计数器（mtime 时钟源） |
| `TimeAsync.scala` | `TimeAsync` / `TimeVldGen` | 跨时钟域时间同步模块 |
| `AXI4Plic.scala` | `AXI4Plic` | 1023 源 PLIC 实现 |
| `imsic_axi_top.scala` | `imsic_bus_top` | IMSIC 总线适配层（TL/AXI/None） |
| `AXI4SlaveModule.scala` | `AXI4SlaveModule` | AXI4 Slave 基类（状态机/协议处理） |
| `AXI4IntrGenerator.scala` | `AXI4IntrGenerator` | 可编程中断发生器 |

### 8.2 Standalone 封装文件

| 文件 | 模块 | 说明 |
|------|------|------|
| `standalone/StandAloneCLINT.scala` | `StandAloneCLINT` | 独立 CLINT 封装（基于 Rocket Chip） |
| `standalone/StandAlonePLIC.scala` | `StandAlonePLIC` | 独立 PLIC 封装（基于 Rocket Chip） |
| `standalone/StandAloneSYSCNT.scala` | `StandAloneSYSCNT` | 独立 SYSCNT 封装 |

### 8.3 仿真框架文件

| 文件 | 说明 |
|------|------|
| `difftest/src/test/csrc/common/uart.cpp` | UART C++ 仿真后端（环形缓冲区/预设输入） |
| `difftest/src/test/csrc/common/uart.h` | UART C++ 头文件 |
| `difftest/src/main/scala/SimTop.scala` | UARTIO Bundle 定义（第 51 行） |
| `src/test/scala/top/SimMMIO.scala` | MMIO 仿真顶层（UART/PLIC/中断合并） |

### 8.4 SoC 集成文件

| 文件 | 关键内容 |
|------|---------|
| `src/main/scala/system/SoC.scala` | `HaveAXI4PeripheralPort`（UART 注册）, `SoCMisc`（TIMER/SYSCNT/PLIC 实例化及连线） |
| `src/main/scala/xiangshan/XSDts.scala` | 中断编号 DTS 声明（msip=3, mtip=7, meip=11） |
| `src/main/scala/xiangshan/Bundle.scala` | 处理器中断输入端口定义 |
| `src/main/scala/xiangshan/backend/fu/CSR.scala` | 旧版 CSR 中断处理逻辑 |
| `src/main/scala/xiangshan/backend/fu/NewCSR/NewCSR.scala` | 新版 CSR 中断处理（含 AIA 支持） |

---

*报告生成日期：2026-06-09*
*基于 XiangShan 源代码分析，代码版本为 Mulan PSL v2 许可证下的最新版本。*
