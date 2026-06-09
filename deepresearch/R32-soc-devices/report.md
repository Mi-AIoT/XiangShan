# R32 - XiangShan SoC Virtual Devices (片上虚拟外设) 深度研究报告

## 1. 概述 (Overview)

XiangShan 处理器的 SoC (System-on-Chip) 级虚拟外设模块位于 `src/main/scala/device/` 目录下，由两大部分组成：

1. **Chisel 硬件描述层**：基于 Chisel3 HDL 实现的 AXI4/TileLink 总线从设备（bus slave）模块，定义了寄存器映射、中断信号生成等硬件逻辑。
2. **C/C++ 仿真支撑层**：位于 `difftest/src/test/csrc/common/` 和 `difftest/src/main/scala/common/` 的 C++ 实现与 Chisel DPI-C / ExtModule 桥接，负责在仿真环境中模拟 Flash、SD 卡、VGA 显存等外部设备的真实行为。

XiangShan SoC 的外设子系统提供了完整的 RISC-V 平台运行时支持，涵盖串口通信、定时器、中断控制器、存储引导、显示输出、键盘输入、SD 卡存储、DMA 控制器、内存加密等关键组件。所有设备模块均通过 AXI4 Slave 接口或 TileLink 寄存器节点（TLRegisterNode）挂载到 SoC 总线上，采用 Memory-Mapped I/O（MMIO）方式实现 CPU 对外设的统一寻址访问。

---

## 2. 设备模块层级架构 (Device Module Hierarchy)

### 2.1 总线基础设施层

XiangShan 的所有外设模块共享一个统一的总线基础设施框架，其核心位于以下文件：

- `src/main/scala/device/AXI4SlaveModule.scala` — AXI4 Slave 基类
- `src/main/scala/device/AXI4Memory.scala` — 含 DPI-C 内存辅助函数

**`AXI4SlaveModule`** 是所有 AXI4 从设备的抽象基类，它定义了：

- **`AXI4SlaveNode`**：通过 Rocket Chip 的 Diplomacy 框架声明一个 AXI4 Slave 端口，参数包括地址集（`AddressSet`）、传输大小（`TransferSizes`）、端口位宽（`beatBytes`）等。
- **状态机**：实现了标准的 AXI4 读/写状态机，包含 `s_idle`、`s_rdata`、`s_wdata`、`s_wresp` 四个状态。
- **Burst 支持**：支持 `BURST_INCR` 类型的突发传输，支持 1/4/8/16 拍（beat）的 burst length。
- **数据掩码**：通过 `MaskExpand(in.w.bits.strb)` 实现字节粒度的写掩码支持。
- **调试信号**：内置 `XSDebug` 信号，可在仿真时打印 AXI4 各通道的地址、数据、ID 等信息。

继承 `AXI4SlaveModule` 的具体设备类通过覆写 `lazy val module` 来实现各自的寄存器映射逻辑。这些设备类包括：

| 模块类名 | 功能 | 基类参数 |
|---|---|---|
| `AXI4UART` | 简单 UART | `_extra = new UARTIO` |
| `AXI4UART16550` | NS16550A UART | `RegisterRouter + HasInterruptSources` |
| `AXI4Timer` | 简易定时器 | `_extra = new TimerIO` |
| `AXI4Plic` | PLIC 中断控制器 | `_extra = new PlicIO` |
| `AXI4Flash` | Flash 仿真 | 无额外 IO |
| `AXI4VGA` | VGA 控制器 | `VGACtrlBundle` |
| `AXI4Keyboard` | PS/2 键盘 | `_extra = new KeyboardIO` |
| `AXI4DummySD` | SD 卡仿真 | 无额外 IO |
| `AXI4RAM` | 片上 RAM | 无额外 IO |
| `AXI4DMAC` | DMA 控制器 | 无额外 IO |
| `AXI4IntrGenerator` | 中断生成器 | `_extra = new IntrGenIO` |
| `AXI4Memory` | 仿真主存 | 含 DPI-C 接口 |

此外还有基于 TileLink 总线的设备：
- `TLTimer` — TileLink CLINT 定时器
- `TIMER` — 完整 CLINT 实现（含 CanHavePeripheryCLINT trait）
- `SYSCNT` — 系统计数器（含跨时钟域支持）
- `TLPMA` — PMA (Physical Memory Attribute) 配置

以及独立封装模块（`standalone/` 目录）：
- `StandAloneCLINT`、`StandAlonePLIC`、`StandAloneSYSCNT`、`StandAloneDebugModule`

### 2.2 SimTop 集成层

在 `difftest/src/main/scala/SimTop.scala` 中，`SimTop` 模块封装了整个仿真顶层。它通过 `DifftestTopIO` 暴露以下信号：

```scala
class DifftestTopIO extends Bundle {
  val exit = Output(UInt(64.W))
  val step = Output(UInt(64.W))
  val perfCtrl = new PerfCtrlIO
  val logCtrl = new LogCtrlIO
  val uart = new UARTIO       // UART 仿真接口
}
```

`UARTIO` 是仿真顶层与外设之间的桥接接口，定义了 `out`（CPU 输出到仿真框架）和 `in`（仿真框架输入到 CPU）两个方向的 8-bit 字符通道。通过 `HasDiffTestInterfaces` trait 中的 `dutIOs` 方法，所有设备端口被自动过滤并提升到顶层模块的 IO 上，使得仿真器能够访问这些虚拟设备。

---

## 3. UART (通用异步收发传输器)

XiangShan 提供了两个 UART 实现：

### 3.1 简单 UART (`AXI4UART`)

**源文件**：`src/main/scala/device/AXI4UART.scala`

这是一个极简的 UART 实现，用于基本的字符输出/输入功能。它继承自 `AXI4SlaveModule[UARTIO]`，通过 `RegMap` 工具将四个寄存器映射到 MMIO 地址空间：

| 偏移地址 | 寄存器名 | 读写属性 | 功能 |
|---|---|---|---|
| `0x0` | `rxfifo` | 只读 | 接收 FIFO（读取输入字符） |
| `0x4` | `txfifo` | 只写 | 发送 FIFO（输出字符） |
| `0x8` | `stat` | 读写 | 状态寄存器 |
| `0xC` | `ctrl` | 读写 | 控制寄存器 |

发送逻辑：当 CPU 向偏移 `0x4` 写入数据时，`io.extra.get.out.valid` 被拉高，数据 `in.w.bits.data(7:0)` 通过 `UARTIO.out.ch` 送往仿真框架。接收逻辑：当 CPU 读取偏移 `0x0` 时，`io.extra.get.in.valid` 被拉高，仿真框架通过 `UARTIO.in.ch` 送入字符。

### 3.2 NS16550A 兼容 UART (`AXI4UART16550`)

**源文件**：`src/main/scala/device/AXI4UART16550.scala`

这是 XiangShan 中更完整的 UART 实现，完全兼容 NS16550A 工业标准。它继承自 Rocket Chip 的 `RegisterRouter`，声明了 `ns16550a` compatible string，并通过 `HasAXI4ControlRegMap` 和 `HasInterruptSources` trait 提供 AXI4 寄存器映射和中断源支持。

**关键参数**：
- `UART16550Params(address, beatBytes=8, fifoDepth=16, baudBase=1843200, clockFreq=50000000L)`
- 寄存器空间大小：`0x20` 字节

**标准 NS16550A 寄存器布局**：

| 偏移 | 寄存器 | 功能描述 |
|---|---|---|
| `0x00` | RBR/THR/DLL | 接收缓冲/发送保持/波特率除数低字节（LCR[7]=1 时切换） |
| `0x04` | IER/DLM | 中断使能/波特率除数高字节 |
| `0x08` | IIR/FCR | 中断标识/FIFO 控制（只读/只写） |
| `0x0C` | LCR | 线路控制（数据位、停止位、校验位、DLAB） |
| `0x10` | MCR | Modem 控制（RTS、DTR、OUT1/OUT2、Loopback） |
| `0x14` | LSR | 线路状态（Data Ready、Overrun、THRE、TEMT） |
| `0x18` | MSR | Modem 状态（CTS、DSR、RI、DCD 及 delta 位） |
| `0x1C` | SCR | 暂存寄存器 |

**FIFO 实现**：内置深度为 `fifoDepth`（默认 16）的 `Queue` 模块作为 RX FIFO 和 TX FIFO。FCR 的 bit0 控制 FIFO 使能，bit1/2 分别触发 RX/TX FIFO 复位。接收触发等级（Receiver Trigger Level）通过 `rxTriggerLut` 查表实现：`{1, 4, 8, 14}`。

**中断生成逻辑**：实现了 NS16550A 的完整中断优先级机制（从高到低）：
1. **LSR Error** (IRQ 0x06)：Line Status Register 有错误位（OE/PE/FE/BI）
2. **Character Timeout** (IRQ 0x0c)：FIFO 超时（字符间超时计数器）
3. **RX Data Ready** (IRQ 0x04)：接收数据就绪（FIFO 达到触发等级）
4. **THRE** (IRQ 0x02)：发送保持寄存器空（上升沿触发）
5. **Modem Status** (IRQ 0x00)：Modem 状态变化

`interrupts.head` 信号在任何非 "no interrupt" (IRQ 0x01) 条件时拉高，通知 PLIC 处理器有外设中断。

**Loopback 模式**：当 MCR[4]（Loopback）置位时，TX 数据自动回环到 RX 路径，Modem 状态映射规则为：OUT2->DCD, OUT1->RI, RTS->CTS, DTR->DSR。

**仿真侧支持**：C++ 端的 `uart.cpp` 实现了 `uart_getc()` 函数，维护一个 1024 字符的环形缓冲区，用于向 CPU 提供预设输入字符串（如 "root\n" 用于 Debian 启动）。`init_uart()` 在仿真初始化时调用 `preset_input()` 填充预设命令。

---

## 4. Timer (定时器 - CLINT/MTIMER)

XiangShan 实现了 RISC-V 标准的 CLINT (Core Local Interruptor)，有三个不同层次的实现：

### 4.1 简易定时器 (`AXI4Timer`)

**源文件**：`src/main/scala/device/AXI4Timer.scala`

面向 AXI4 总线的简化定时器，通过 `TimerIO` 暴露 `mtip`（Machine Timer Interrupt Pending）输出信号。

**寄存器映射**：

| 偏移 | 寄存器 | 功能 |
|---|---|---|
| `0x4000` | `mtimecmp` | 定时器比较值（64-bit） |
| `0x8000` | `freq` | 时钟频率配置（16-bit，默认 40MHz/1000000） |
| `0x8008` | `inc` | 每 tick 递增量（16-bit，默认 1000） |
| `0xBFF8` | `mtime` | 当前时间（64-bit，只读） |

定时器使用分频计数器产生 tick 信号：当 `cnt` 达到 `freq` 时产生 tick，`mtime` 累加 `inc`。仿真模式下 `freq=10000`（降低仿真开销）。中断判断：`mtip := RegNext(mtime >= mtimecmp)`。

### 4.2 TileLink CLINT (`TLTimer`)

**源文件**：`src/main/scala/device/TLTimer.scala`

基于 TileLink 总线的 CLINT 实现，支持多核环境。使用 Rocket Chip 的 `TLRegisterNode` 进行寄存器映射。

**特性**：
- 多核支持：通过 `numCores` 参数配置核心数量
- 每核独立的 `mtimecmp` 寄存器和 `msip`（Machine Software Interrupt Pending）寄存器
- 标准 CLINT 地址布局：msip 在 `0x0000 + hart*4`，mtimecmp 在 `0x4000 + hart*8`，mtime 在 `0xBFF8`
- 中断输出为 `Vec(numCores, Bool())` 类型

**中断生成**：
- `mtip(i) := RegNext(mtime >= mtimecmp(i))` — 定时器中断
- `msip(i) := RegNext(msip(i) =/= 0.U)` — 软件中断

### 4.3 完整 CLINT (`TIMER`)

**源文件**：`src/main/scala/device/TIMER.scala`

这是 Rocket Chip 兼容的完整 CLINT 实现，通过 `CanHavePeripheryCLINT` trait 集成到子系统中。

**架构特点**：
- 通过 `CLINTKey` 和 `CLINTAttachKey` CDE 配置参数控制系统集成
- 支持异步 `time` 输入（通过 `io.time` ValidIO 端口）
- 使用 `ShiftRegister` 支持可配置的中断延迟级数（`intStages`）
- 支持 `IsSelfTest` 模式，自测模式下所有 hart 的中断信号并行广播
- 基于 `MaxHartIdBits` 参数自动计算最大 hart 数量
- 默认基地址 `0x02000000`，地址空间大小 `0x10000`

`TIMERConsts` 定义了标准的 CLINT 常量：
```scala
def msipOffset(hart: Int) = hart * 4
def timecmpOffset(hart: Int) = 0x4000 + hart * 8
def timeOffset = 0xbff8
def size = 0x10000
```

### 4.4 系统计数器 (`SYSCNT`)

**源文件**：`src/main/scala/device/SYSCNT.scala`

面向实际 SoC 的增强定时器实现，支持跨时钟域操作。特点包括：

- **双时钟域**：`rtc_clock`（参考时钟）和 `bus_clock`（总线时钟）独立运行
- **异步同步**：通过 `AsyncResetSynchronizerShiftReg` 实现跨时钟域信号同步
- **可变频率**：通过 `freqidx` 寄存器配置时间递增宽度（bit[2:0]），实现可变频率的定时器（1GHz, 500MHz, 250MHz, 125MHz, 62.5MHz 等）
- **软件可写时间**：支持通过 `time_sw` 寄存器直接设置时间值
- **停止控制**：支持通过 `stop_en` 信号暂停计时
- 使用 `TimeAsync` 模块（`src/main/scala/device/TimeAsync.scala`）实现从 RTC 时钟域到总线时钟域的 64-bit 时间值异步传输

---

## 5. Interrupt Controller (PLIC 中断控制器)

### 5.1 AXI4 PLIC (`AXI4Plic`)

**源文件**：`src/main/scala/device/AXI4Plic.scala`

实现了 RISC-V PLIC (Platform-Level Interrupt Controller) 规范，支持最多 1023 个外部中断源和 15872 个 hart 上下文。

**地址空间布局**（PLIC 规范标准）：

| 地址范围 | 功能 |
|---|---|
| `0x000000 - 0x000FFC` | 中断源优先级（priority），每源 4 字节 |
| `0x001000 - 0x001FFC` | 中断待处理位（pending bits），32-bit 宽度 |
| `0x002000 - 0x1F1FFF` | 每个 hart 上下文的中断使能位（enable bits） |
| `0x200000 - 0x3FFE000` | 每个 hart 上下文的优先级阈值和 Claim/Complete 寄存器 |

**寄存器定义**：
- **Priority 寄存器**：每个中断源一个 32-bit 优先级寄存器
- **Pending 寄存器**：硬件根据 `io.extra.get.intrVec` 信号自动更新，不可由软件直接写入
- **Enable 寄存器**：每个 hart 上下文有独立的 enable 位图
- **Threshold 寄存器**：每个 hart 上下文独立的优先级阈值
- **Claim/Complete 寄存器**：读操作触发 claim（返回最高优先级 pending 中断 ID），写操作触发 complete（清除对应中断的 pending 位）

**中断仲裁逻辑**：
1. 外部中断信号经过 3 级流水线延迟后写入 pending 位
2. 对每个 hart，计算 `takenVec = pendingVec & enable(hart)`
3. 使用 `PriorityEncoder` 找到最高优先级的 pending 且已使能的中断
4. 当 claim 完成时，对应的 `meip`（Machine External Interrupt Pending）信号拉高
5. complete 操作清除 pending 位和 `inHandle` 标记

**PLIC 常量**：
```scala
object PLICConsts {
  def maxDevices = 1023
  def maxHarts = 15872
  def size(maxHarts: Int) = 1 << log2Ceil(hartBase(maxHarts))
}
```

### 5.2 IMSIC Bus Top (`imsic_bus_top`)

**源文件**：`src/main/scala/device/imsic_axi_top.scala`

面向 AIA (Advanced Interrupt Architecture) 的 IMSIC (Incoming MSI Controller) 总线顶层模块。支持三种总线模式：
- **NONE**：直接连接，无总线桥接
- **TL**：通过 TileLink 总线连接（`TLRegIMSIC`）
- **AXI**：通过 AXI4 总线连接（`AXIRegIMSIC_WRAP`）

通过 `IMSICBusType` 枚举和 `soc.IMSICBusType` 配置参数选择工作模式。支持 TEE (Trusted Execution Environment) 隔离的 IMSIC 通道。

### 5.3 中断生成器 (`AXI4IntrGenerator`)

**源文件**：`src/main/scala/device/AXI4IntrGenerator.scala`

用于测试的可编程中断生成器，支持 256 个中断位（64-bit 寄存器宽 * 4 个寄存器）。包含随机中断生成功能：

- `intrReg`：中断使能/控制寄存器（2 个 32-bit 寄存器）
- `randEnable`/`randMask`/`randCounter`/`randThres`：随机中断生成控制
- 使用 `LFSR64()` 产生随机位置和条件
- 写入延迟 1000 个时钟周期后生效（`delayCycles = 1000`），用于测试时序约束

---

## 6. Flash 仿真 (Flash Emulation)

### 6.1 Chisel 端 (`AXI4Flash`)

**源文件**：`src/main/scala/device/AXI4Flash.scala`

Flash 设备模块继承自 `AXI4SlaveModule`，实现只读存储功能。通过 `DifftestFlash` DPI-C 接口与 C++ 仿真模型交互：

```scala
val flash = DifftestFlash()
flash.en := in.ar.fire
flash.addr := Cat(0.U(16.W), getOffset(raddr))
in.r.bits.data := flash.data
```

当 AXI4 读通道（AR）触发时，将地址送入 `DifftestFlash`，并在同一拍返回 64-bit 数据。Flash 设备不支持写操作。

### 6.2 DifftestFlash DPI-C 桥接

**源文件**：`difftest/src/main/scala/common/Flash.scala`

定义了 `FlashHelper` ExtModule 和 `DifftestFlash` 模块。`FlashHelper` 内联了 Verilog 模块和 C++ ExtModule：

- **Verilog 侧**：当 `r_en` 为高时调用 DPI-C 函数 `flash_read(addr, data)`
- **C++ 侧**：`FlashHelper` 函数调用 `flash_read(r_addr, &r_data)`
- **降级方案**：当 `DISABLE_DIFFTEST_FLASH_DPIC` 定义时，使用纯 Verilog 实现（内建 4MB Flash 存储阵列 + 文件加载）

### 6.3 C++ 仿真实现

**源文件**：`difftest/src/test/csrc/common/flash.cpp` / `flash.h`

`flash_device_t` 结构体管理 Flash 的 mmap 内存区域：
```cpp
struct flash_device_t {
  uint64_t *base;     // mmap 内存基地址
  uint64_t size;       // Flash 大小
  char *img_path;      // 镜像文件路径
  uint64_t img_size;   // 实际镜像大小
};
```

**初始化流程** (`init_flash`)：
1. 使用 `mmap` 分配 `DEFAULT_EMU_FLASH_SIZE` 大小的匿名内存
2. 如果提供了 `flash_bin` 参数，将文件内容读入 mmap 区域
3. 如果未提供文件，加载默认的 3 条指令序列（跳转到 `0x8000_0000`）：
   - `addiw t0, zero, 1`
   - `slli t0, t0, 0x1f`
   - `jr t0`

**读操作** (`flash_read`)：8 字节对齐读取，地址越界时返回 0 并打印警告。

---

## 7. VGA/Framebuffer 显示支持

### 7.1 Chisel 端 (`AXI4VGA`)

**源文件**：`src/main/scala/device/AXI4VGA.scala`

VGA 控制器是一个复合模块，包含三个组件：

#### 7.1.1 显示参数

使用 HDMI 标准时序参数（`HasHDMIConst` trait）：
- 屏幕分辨率：800 x 600
- Framebuffer 分辨率：400 x 300（`FBWidth = ScreenW/2`, `FBHeight = ScreenH/2`）
- Framebuffer 像素数：120,000 个像素
- 每像素 4 字节（ARGB8888），总帧缓冲大小 480KB

HDMI 时序参数：
```
HFrontPorch = 40, HActive = 168, HBackPorch = 968, HTotal = 1056
VFrontPorch = 1,  VActive = 5,   VBackPorch = 605,  VTotal = 628
```

#### 7.1.2 VGA 控制器 (`VGACtrl`)

独立的控制模块，映射到单独的地址空间：
- `0x0`：帧缓冲大小寄存器（只读，返回 `FBWidth | (FBHeight << 16)`）
- `0x4`：同步信号（只读，返回 AXI4 写通道触发信号，用于通知 CPU 帧缓冲已更新）

#### 7.1.3 帧缓冲 (`AXI4RAM`)

帧缓冲区使用 `AXI4RAM` 模块实现，大小为 `FBPixels * 4` 字节。CPU 可通过 AXI4 写通道更新帧缓冲内容。

#### 7.1.4 VGA 扫描逻辑

VGA 扫描器通过水平和垂直计数器生成标准 VGA 时序信号：
- `hsync`：水平同步信号
- `vsync`：垂直同步信号
- `valid`：像素有效信号（在可视区域内为高）
- `rgb`：24-bit RGB 颜色输出

帧缓冲读取利用了双缓存策略（`fbPixelAddrV0`/`fbPixelAddrV1`），根据垂直计数器的奇偶交替读取两个像素地址计数器，以隐藏 block memory 的 2 周期读延迟。

#### 7.1.5 FBHelper 仿真输出

在仿真模式下，`FBHelper` ExtModule 通过 DPI-C 调用将像素数据送往 C++ 仿真框架：
- `put_pixel(pixel)` — 逐像素写入帧缓冲
- `vmem_sync()` — 一帧完成后刷新显示

### 7.2 C++ 仿真实现

**源文件**：`difftest/src/test/csrc/common/vga.cpp` / `vga.h`

在 `SHOW_SCREEN` 编译宏启用时，使用 SDL2 库实现屏幕显示：

- **帧缓冲区**：`static uint32_t vmem[800 * 600]` — 软件帧缓冲
- **SDL 初始化**：创建 800x600 窗口，ARGB8888 格式纹理
- **像素输出**：`put_pixel()` 将 DPI-C 传入的像素值写入 `vmem`
- **帧同步**：`vmem_sync()` 调用 `SDL_UpdateTexture` + `SDL_RenderCopy` + `SDL_RenderPresent` 更新屏幕
- 窗口标题设置为 "NOOP"（XiangShan 项目原名）

---

## 8. Keyboard 键盘输入

### 8.1 Chisel 端 (`AXI4Keyboard`)

**源文件**：`src/main/scala/device/AXI4Keyboard.scala`

实现了 PS/2 协议的键盘接口。`KeyboardIO` Bundle 包含两个信号：
- `ps2Clk`：PS/2 时钟输入
- `ps2Data`：PS/2 数据输入

**PS/2 协议解码逻辑**：
1. 通过 `RegNext` 检测 PS/2 时钟下降沿（`negedge`）
2. 在每个下降沿采样数据位，移入 10-bit 移位寄存器 `buf`
3. 使用 `Counter(negedge, 10)` 计数收到的位数
4. 当 10 位全部收到时，检查起始位（bit0=0）、停止位（bit9=1）和奇偶校验（bit[8:1] 异或为 1）
5. 验证通过后，将 8-bit 数据（bit[8:1]）推入 8 深度的 `Queue` FIFO
6. CPU 读取时从 FIFO 出队返回数据

注意：源码中标注 "this Module is not tested"，表明这是一个实验性实现。

### 8.2 C++ 仿真实现

**源文件**：`difftest/src/test/csrc/common/keyboard.cpp`

提供了完整的 SDL2 键盘事件处理：
- 使用 SDL scancode 到内部按键码的映射表 `keymap[256]`
- `send_key(scancode, is_keydown)`：将 SDL 键盘事件转换为内部按键码，推入环形缓冲区 `key_queue[1024]`
- `read_key()`：从缓冲区出队返回按键码
- 按键状态通过 `KEYDOWN_MASK (0x8000)` 标记按下/释放

支持的按键包括：ESC, F1-F12, 字母键, 数字键, 方向键, Shift/Ctrl/Alt, Insert/Delete/Home/End/PgUp/PgDn 等标准键。

`device.cpp` 中的 `poll_event()` 函数在仿真主循环中调用 `SDL_PollEvent`，捕获 `SDL_KEYDOWN`/`SDL_KEYUP` 事件并调用 `send_key()`。

---

## 9. SD Card 仿真

### 9.1 Chisel 端 (`AXI4DummySD`)

**源文件**：`src/main/scala/device/AXI4DummySD.scala`

模拟了一个 SD 卡控制器的寄存器接口，通过 `DifftestSDCard` DPI-C 接口与 C++ 文件系统交互。

**SD 卡常量**（`HasSDConst` trait）：
- 总容量：4GB
- Block 长度：32768 (2^15) 字节
- Block 数量：131072
- C_SIZE_MULT: 7, C_SIZE: 根据容量计算

**寄存器映射**（模拟 SDHCI 风格）：

| 偏移 | 寄存器 | 功能 |
|---|---|---|
| `0x00` | `sdcmd` | SD 命令寄存器（写入触发命令处理） |
| `0x04` | `sdarg` | SD 参数寄存器（地址参数） |
| `0x10-0x1C` | `sdrsp0-3` | SD 响应寄存器（只读） |
| `0x20` | `sdhsts` | 主机状态寄存器 |
| `0x34` | EDM 常量 | FIFO 数据数量（常量，只读） |
| `0x38` | `sdhcfg`/`sdhbct` | 主机配置/块计数 |
| `0x40` | 数据端口 | 读取 SD 数据（触发 `sd_read` DPI-C 调用） |
| `0x50` | `sdhblc` | 块长度配置 |

**命令处理逻辑** (`cmdWfn`)：

| 命令 | 响应 |
|---|---|
| `MMC_SEND_OP_COND (1)` | 返回 `0x80ff8000`（初始化完成标志） |
| `MMC_ALL_SEND_CID (2)` | 返回 CID 信息 |
| `MMC_SEND_CSD (9)` | 返回 CSD 寄存器（含容量信息） |
| `MMC_SEND_STATUS (13)` | 返回状态 0（就绪） |
| `MMC_READ_MULTIPLE_BLOCK (18)` | 设置 `setAddr` 标志，触发地址设置 |

### 9.2 DifftestSDCard DPI-C 桥接

**源文件**：`difftest/src/main/scala/common/SDCard.scala`

`SDCardHelper` ExtModule 提供两个 DPI-C 函数：
- `sd_setaddr(addr)` — 设置 SD 卡读取偏移地址
- `sd_read(data)` — 读取 32-bit 数据

### 9.3 C++ 仿真实现

**源文件**：`difftest/src/test/csrc/common/sdcard.cpp` / `sdcard.h`

SD 卡仿真使用文件系统镜像实现：
- `fp`：SD 卡镜像文件句柄
- `sd_setaddr()`：调用 `fseek(fp, addr, SEEK_SET)` 定位到指定地址
- `sd_read()`：调用 `fread(data, 4, 1, fp)` 读取 4 字节数据
- `init_sd()`：打开 `SDCARD_IMAGE` 宏定义的镜像文件
- `finish_sd()`：关闭文件

如果 SD 卡镜像未找到，打印警告信息，后续读操作将返回垃圾数据。

---

## 10. SPI/I2C 设备

经过对 `src/main/scala/device/` 目录的全面审查，XiangShan 当前版本中**没有独立的 SPI 或 I2C 外设模块**。SD 卡控制器（`AXI4DummySD`）虽然在硬件中通常通过 SDIO/SPI 总线连接，但在 XiangShan 的仿真模型中，它是作为 MMIO 寄存器接口直接实现的，不包含真正的 SPI 时序逻辑。

如果需要 SPI 或 I2C 支持，需要通过 SoC 级别的 IP 集成或自行添加。

---

## 11. Memory-Mapped I/O (MMIO) 处理机制

### 11.1 地址解码与路由

XiangShan 的外设通过两种总线接口挂载：

**AXI4 路径**：所有 `AXI4SlaveModule` 子类通过 `AXI4SlaveNode` 声明地址范围，在 SoC 级别的 `AXI4Xbar` 中进行地址解码和路由。每个设备的 `AddressSet` 参数定义了其 MMIO 地址范围。

**TileLink 路径**：`TLTimer`、`TIMER`、`SYSCNT` 等模块通过 `TLRegisterNode` 声明地址范围，在 TileLink 互联中进行路由。

### 11.2 AXI4 Slave 状态机

`AXI4SlaveModuleImp` 实现了统一的 AXI4 传输状态机：

```
s_idle --[ar.fire]--> s_rdata --[r.fire && last]--> s_idle
s_idle --[aw.fire]--> s_wdata --[w.fire && last]--> s_wresp --[b.fire]--> s_idle
```

关键设计点：
- 读写通道互斥：`aw.ready` 仅在 `s_idle` 且 `ar.valid` 为低时拉高
- Burst 计数器：使用 `readBeatCnt`/`writeBeatCnt` 追踪突发传输进度
- 写掩码：`fullMask = MaskExpand(in.w.bits.strb)` 实现字节级写使能
- `dontTouch(in)` 确保 MMIO AXI 信号不被综合工具优化掉

### 11.3 RegMap 工具

`RegMap.generate` 是 XiangShan 中广泛使用的寄存器映射工具，它：
- 自动处理字节对齐和字节序
- 支持读写掩码（通过 `MaskExpand`）
- 支持只读（`RegMap.Unwritable`）和只写属性
- 支持自定义读写回调函数（如 UART16550 的 `RegReadFn`/`RegWriteFn`）

### 11.4 跨时钟域处理

对于实际 SoC，外设可能工作在不同时钟域：
- `SYSCNT` 使用 `AsyncResetSynchronizerShiftReg` 进行 3 级同步
- `TimeAsync` 模块实现 64-bit 时间值的异步传输（利用 valid 信号的边沿检测）
- `TimeVldGen` 在参考时钟域产生 valid 脉冲

---

## 12. 设备集成与 SoC 互联 (Device Integration with SoC)

### 12.1 集成方式

各外设模块在 SoC 中的集成遵循以下模式：

**AXI4 设备**：通过 `AXI4Xbar` 或 `AXI4Crossbar` 进行总线互联。每个设备的 `node` 字段连接到总线 crossbar 的一个 slave 端口。地址解码由 Diplomacy 框架自动完成。

**TileLink 设备**：通过 `TLFragmenter` + `coupleTo` 方法连接到特定总线段（如 CBUS）。

**中断连接**：
- `AXI4Plic` 的 `io.extra.get.meip` 连接到各核心的外部中断线
- `TLTimer`/`TIMER` 的 `io.mtip`/`io.msip` 连接到核心的定时器中断和软件中断线
- `AXI4UART16550` 的 `interrupts.head` 连接到 PLIC 的中断输入端

### 12.2 SoC 典型地址映射

根据各设备的默认基地址和 XiangShan SoC 的设计惯例，典型的 MMIO 地址映射如下：

| 设备 | 典型基地址 | 地址空间 |
|---|---|---|
| CLINT Timer | `0x0200_0000` | 64KB |
| PLIC | `0x0C00_0000` | 64MB |
| UART | `0x1000_0000` | 8B+ |
| Flash | `0x1000_0000` 附近 | 可配 |
| VGA Framebuffer | `0x2000_0000` 附近 | 480KB |
| VGA Control | `0x4100_0000` | 8B |
| SD Card | `0x1000_0000` 附近 | 可配 |
| Keyboard | `0x1000_0000` 附近 | 可配 |
| DRAM | `0x8000_0000` | GB 级 |

### 12.3 仿真顶层集成

在 `SimTop` 顶层模块中，设备通过以下方式暴露给仿真框架：

1. UART 通过 `UARTIO` Bundle 直接连接到 `DifftestTopIO.uart`
2. 其他设备的端口通过 `HasDiffTestInterfaces.dutIOs` 自动提升到顶层
3. C++ 仿真框架通过 `init_device()` / `finish_device()` 管理设备生命周期
4. `poll_event()` 函数在每个仿真步中轮询 SDL 事件（键盘输入等）

---

## 13. 内存加密模块 (Memory Encryption)

**源文件**：`src/main/scala/device/MemEncrypt.scala`

这是一个可选的安全特性模块，实现了 AXI4 总线级别的内存加密/解密。当 SoC 需要保护内存数据时，`AXI4MemEncrypt` 模块作为 AXI4 适配器插入在 CPU 和内存之间。

**核心组件**：
- `MemEncryptCSR`：管理加密配置寄存器（Key ID、模式、使能等）
- `KeyExtender`：AES-128 密钥扩展引擎，32 轮迭代
- `KeyTable`：存储扩展后的轮密钥
- `TweakEncrptyQueue`/`TweakEncrptyTable`：XTS-AES Tweak 计算
- `WdataEncrptyPipe`/`RdataDecrptyPipe`：流水线化的数据加密/解密引擎
- `AXI4WriteMachine`/`AXI4ReadMachine`：处理 uncacheable 写操作时的 read-modify-write 序列

加密模块支持可配置的流水线深度（`MemencPipes`）和可选的"无加密延迟"模式（`HasDelayNoencryption`）。

---

## 14. DMA 控制器 (AXI4DMAC)

**源文件**：`src/main/scala/device/AXI4DMAC.scala`

一个简化版 DMA 控制器，目前仅支持 32 字节的单次传输，主要用于测试验证。

**寄存器布局**：

| 偏移 | 寄存器 | 功能 |
|---|---|---|
| `0x00` | `srcAddrReg` | 源地址（64-bit） |
| `0x08` | `dstAddrReg` | 目的地址（64-bit） |
| `0x10` | `cfg_reg` | 控制/状态寄存器 |

**工作流程**：
1. 软件配置 `srcAddrReg`、`dstAddrReg`
2. 写入 `cfg_reg[0] = 1` 触发传输（下降沿检测）
3. FSM 执行：`sIdle -> sAr -> sRdata -> sAw -> sWdata -> sB -> sIdle`
4. 内部使用 256-bit 宽、128 深度的 `Queue` FIFO 缓存数据
5. 传输完成后 `cfg_reg` 被清零并设置 bit1（完成标志）

DMA 控制器同时作为 AXI4 Slave（配置寄存器）和 AXI4 Master（执行 DMA 传输），其 master 端口支持 14-bit ID 范围。

---

## 15. 调试模块 (Debug Module)

**源文件**：`src/main/scala/device/RocketDebugWrapper.scala`

封装了 Rocket Chip 的 `TLDebugModule` 和 `DebugTransportModuleJTAG`，提供 JTAG 调试接口。

**`DebugModule`** 模块特性：
- 使用 8-bit 内部总线宽度的 `TLDebugModule`
- 通过 `DebugCustomXbar` 支持自定义调试命令
- 集成 `DebugTransportModuleJTAG` 提供标准 JTAG DTM (Debug Transport Module)
- 通过 `SimJTAG` ExtModule 在仿真中模拟 JTAG 调试主机

**`SimJTAG`** 提供：
- 标准 JTAG 四线接口（TCK、TMS、TDI、TDO、TRSTn）
- 通过 `tickDelay` 参数控制 JTAG 时钟速率
- 退出码机制：`exit == 1` 表示成功，`exit >= 2` 表示失败

---

## 16. 关键源文件索引 (Key Source File Locations)

### 16.1 Chisel 硬件描述文件 (`src/main/scala/device/`)

| 文件 | 功能 |
|---|---|
| `AXI4SlaveModule.scala` | AXI4 Slave 基类（所有 MMIO 设备的基础） |
| `AXI4UART.scala` | 简单 UART |
| `AXI4UART16550.scala` | NS16550A 兼容 UART |
| `AXI4Timer.scala` | 简易定时器 |
| `TLTimer.scala` | TileLink CLINT 定时器 |
| `TIMER.scala` | 完整 CLINT 实现（含 CanHavePeripheryCLINT） |
| `SYSCNT.scala` | 系统计数器（跨时钟域） |
| `TimeAsync.scala` | 异步时间传输模块 |
| `AXI4Plic.scala` | PLIC 中断控制器 |
| `imsic_axi_top.scala` | IMSIC AIA 中断控制器 |
| `AXI4Flash.scala` | Flash 仿真设备 |
| `AXI4VGA.scala` | VGA/Framebuffer 显示控制器 |
| `AXI4Keyboard.scala` | PS/2 键盘接口 |
| `AXI4DummySD.scala` | SD 卡仿真设备 |
| `AXI4RAM.scala` | 片上 RAM |
| `AXI4Memory.scala` | 仿真主存（含 DPI-C/DRAMsim3 支持） |
| `AXI4DMAC.scala` | DMA 控制器 |
| `AXI4IntrGenerator.scala` | 中断生成器 |
| `MemEncrypt.scala` | 内存加密模块（XTS-AES） |
| `RocketDebugWrapper.scala` | JTAG 调试模块封装 |
| `TLPMA/TLPMA.scala` | 物理内存属性配置 |
| `standalone/` | 独立设备封装（CLINT、PLIC、SYSCNT、Debug） |

### 16.2 Difftest Scala 桥接文件 (`difftest/src/main/scala/common/`)

| 文件 | 功能 |
|---|---|
| `Flash.scala` | Flash DPI-C 桥接（DifftestFlash + FlashHelper） |
| `Mem.scala` | 内存 DPI-C 桥接（DifftestMem，多读多写端口） |
| `SDCard.scala` | SD 卡 DPI-C 桥接（DifftestSDCard + SDCardHelper） |
| `WiringControl.scala` | DifftestWiring 信号互连管理 |
| `LogPerfControl.scala` | 日志与性能控制 |
| `FileControl.scala` | 生成文件写入工具 |

### 16.3 C++ 仿真源文件 (`difftest/src/test/csrc/common/`)

| 文件 | 功能 |
|---|---|
| `uart.cpp` / `uart.h` | UART 字符输入/输出，预设命令缓冲 |
| `flash.cpp` / `flash.h` | Flash 镜像加载与读取 |
| `sdcard.cpp` / `sdcard.h` | SD 卡镜像文件读取 |
| `vga.cpp` / `vga.h` | SDL2 VGA 显示输出 |
| `keyboard.cpp` | SDL2 键盘事件处理与 scancode 映射 |
| `device.cpp` / `device.h` | 设备生命周期管理（init/finish/poll） |
| `ram.cpp` / `ram.h` | 内存仿真（mmap, DRAMsim3, ELF/GZip/Zstd 加载） |
| `SimJTAG.cpp` / `SimJTAG.h` | JTAG 仿真接口 |

### 16.4 仿真顶层 (`difftest/src/main/scala/`)

| 文件 | 功能 |
|---|---|
| `SimTop.scala` | 仿真顶层模块，DifftestTopIO 和 UARTIO 定义 |

---

## 17. 总结 (Conclusion)

XiangShan 的 SoC 虚拟外设子系统是一个设计完善、层次分明的平台支撑框架：

1. **分层架构清晰**：硬件描述层（Chisel）、DPI-C 桥接层（Scala ExtModule）、C++ 仿真层三者职责分明，通过 DPI-C 和 ExtModule 机制无缝连接。

2. **工业标准兼容**：UART 实现了 NS16550A 标准，Timer/PLIC 遵循 RISC-V 规范，调试模块支持标准 JTAG 协议。

3. **可扩展性强**：`AXI4SlaveModule` 基类提供了统一的总线接口和状态机，新增 MMIO 设备只需继承基类并实现寄存器映射。

4. **仿真友好**：所有外部设备（Flash、SD 卡、VGA、键盘）在仿真中通过 C++ 模型实现，支持文件镜像加载、SDL 显示和键盘交互，便于功能验证。

5. **安全特性**：内存加密模块（MemEncrypt）提供了可选的总线级数据加密保护，支持多密钥和 AIA 架构。

6. **实际 SoC 考虑**：SYSCNT 和 TimeAsync 模块提供了跨时钟域支持，Memory 模块支持 DRAMsim3 集成进行性能评估，DMA 控制器支持批量数据传输。

当前版本中未包含独立的 SPI 和 I2C 外设模块，如果 SoC 设计需要这些接口，需要额外集成。
