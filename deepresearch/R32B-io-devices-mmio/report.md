# R32B - I/O Devices & MMIO Deep Dive

## 1. AXI4SlaveModule -- MMIO 设备基类与 4 状态 AXI4 FSM

### 1.1 设计概述

`AXI4SlaveModule` 位于 `src/main/scala/device/AXI4SlaveModule.scala`，是香山处理器中所有 MMIO (Memory-Mapped I/O) 设备的基础抽象类。它继承自 Rocket Chip 的 `LazyModule`，利用 Chisel diplomacy 框架将设备节点接入 AXI4 互连网络。该模块定义了两个核心部分：

- **`AXI4SlaveModule[T]`** -- 抽象基类，负责声明 AXI4SlaveNode 并配置 MMIO 地址空间属性。该基类通过类型参数 `T <: Data` 提供了泛型支持，使得不同 MMIO 设备可以向外部暴露各自特有的 IO 信号，例如 VGA 同步信号、PS/2 键盘时钟和数据线等。基类在 `LazyModule` 的构造阶段完成 diplomacy 节点的创建和参数化，使得地址空间、传输大小等属性在 elaboration 早期就确定下来。
- **`AXI4SlaveModuleImp[T]`** -- 模块实现类，包含 4 状态 FSM 和 AXI4 协议处理逻辑。该实现类继承自 `LazyModuleImp`，负责在 RTL 层面生成实际的 AXI4 从端逻辑。它自动处理地址锁存、beat 计数、数据 mask 合并、响应信号驱动等通用操作，使得子类只需关注自己的寄存器映射和功能逻辑。

### 1.2 AXI4SlaveNode 配置

```scala
val node = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
  Seq(AXI4SlaveParameters(
    address,
    regionType = RegionType.UNCACHED,
    executable = executable,
    supportsWrite = TransferSizes(1, beatBytes * burstLen),
    supportsRead  = TransferSizes(1, beatBytes * burstLen),
    interleavedId = Some(0)
  )),
  beatBytes = beatBytes
)))
```

参数说明：
- **`address`** -- 设备的 MMIO 地址空间（`Seq[AddressSet]`），支持不连续的多个地址区间
- **`executable`** -- 是否可执行。对于 Flash 设备设为 true（因为 CPU 会从 Flash 地址取指），其他 I/O 设备通常设为 false
- **`beatBytes`** -- AXI4 总线宽度，默认 8 字节（64-bit），与香山处理器的 64-bit 数据总线宽度匹配
- **`burstLen`** -- 最大突发传输长度，默认 1 beat。部分设备（如 DMA）可能需要更长的突发传输
- **`regionType = UNCACHED`** -- MMIO 区域标记为不可缓存，确保所有访问都直达设备而不经过 cache 层级
- **`interleavedId = Some(0)`** -- 表示设备响应的 ID 必须为 0，简化了 response matching 逻辑

### 1.3 四状态 AXI4 FSM

FSM 定义了 4 个状态，用于处理读写事务的完整生命周期：

```
s_idle --> s_rdata --> s_idle             (读事务)
s_idle --> s_wdata --> s_wresp --> s_idle (写事务)
```

**状态转换逻辑详解：**

1. **`s_idle`（空闲状态）**：
   - 等待读地址通道 (`in.ar.fire`) 或写地址通道 (`in.aw.fire`) 的有效请求
   - 读请求优先：`in.aw.ready := state === s_idle && !in.ar.valid`，保证读请求在同周期有更高优先级，这是为了避免 MMIO 设备读操作阻塞
   - 支持 BURST_INCR 突发类型，FSM 通过 `assert` 在 AR/AW fire 时检查 burst type 是否正确
   - 在空闲状态下，`in.ar.ready` 始终为 true，允许立即接受新的读请求

2. **`s_rdata`（读数据状态）**：
   - 在 `s_rdata` 状态下持续驱动 `in.r.valid`，表示数据通道有效
   - 使用计数器 `readBeatCnt` 追踪当前 beat，当 `in.r.bits.last` 为高时返回 `s_idle`
   - 支持 1/2/4/8/16 beat 的突发长度（`len = 0/1/3/7/15`），通过 `assert` 强制检查
   - 地址使用 `HoldUnless(in.ar.bits.addr, in.ar.fire)` 锁存，保证整个突发事务期间地址稳定递增（INCR 模式下硬件自动递增）

3. **`s_wdata`（写数据状态）**：
   - 在 `s_wdata` 状态下持续驱动 `in.w.ready`，表示数据通道就绪
   - 当接收到 `in.w.bits.last` 信号时，表明最后一个 beat 已写入，进入 `s_wresp` 状态
   - 写地址同样使用 `HoldUnless` 锁存

4. **`s_wresp`（写响应状态）**：
   - 驱动 `in.b.valid`，返回 AXI4 写响应
   - 响应中的 ID 通过 `RegEnable(in.aw.bits.id, in.aw.fire)` 锁存，确保与请求 ID 匹配
   - 响应中的 user 字段同样锁存传递
   - `in.b` 响应完成后回到 `s_idle`

### 1.4 辅助工具函数

- **`fullMask = MaskExpand(in.w.bits.strb)`** -- 将 byte-level strobe 信号展开为 bit-level mask。例如 `strb = 4'b1010` 展开为 `mask = 64'h0000FFFF00000000`，支持细粒度的字节级写入
- **`genWdata(originData)`** -- 对读出的数据进行字节级合并写入：`(originData & ~fullMask) | (w.data & fullMask)`。这实现了 read-modify-write 语义：未被写入的字节保持原值，被写入的字节取新值
- **`raddr / waddr`** -- 使用 `HoldUnless` 在 AR/AW fire 时锁存地址，保证整个突发事务期间地址稳定。这是因为 AXI4 从端需要在多个 beat 周期内持续输出对应地址的数据
- **`dontTouch(in)`** -- 防止 MMIO AXI 信号在综合时被工具优化掉。由于 MMIO 信号可能在逻辑分析中不被直接引用，综合工具可能会将其视为死代码进行优化，这会导致功能丢失
- **`readBeatCnt` 和 `writeBeatCnt`** -- 各使用一个 256 位宽度的 Counter，但实际上只用低几位，用于追踪突发传输中的 beat 位置

### 1.5 通用性与可扩展性

基类通过泛型参数 `T <: Data` 支持 `_extra` IO 接口，子类可以通过该接口向模块外部暴露额外的信号线。例如：
- `AXI4VGA` 通过 `_extra = new VGACtrlBundle` 暴露 VGA 同步信号，用于协调帧缓冲写入时序
- `AXI4Keyboard` 通过 `_extra = new KeyboardIO` 暴露 PS/2 时钟和数据线，连接到外部键盘控制器
- `AXI4DMAC` 和 `AXI4DummySD` 使用 `_extra = null`（即 `Null` 类型），无需额外 IO
- 这种设计模式遵循了面向对象中的"模板方法"模式，基类定义算法骨架（AXI4 协议处理），子类填充具体操作（寄存器读写和功能逻辑）

### 1.6 XSDebug 日志输出

基类内置了 `XSDebug` 语句，在每个 AXI4 通道 fire 时输出调试信息：
- AR 通道：打印地址、突发长度、突发大小、事务 ID
- AW 通道：打印地址、突发长度、突发大小、事务 ID
- W 通道：打印写掩码、last 标志、写数据
- B 通道：打印响应 ID
- R 通道：打印响应 ID 和读数据

这些调试信息在仿真时可以通过 XiangShan 的日志框架进行过滤和控制。

---

## 2. AXI4Flash -- 只读 Flash 仿真模块

### 2.1 Chisel 模块设计

`AXI4Flash` 位于 `src/main/scala/device/AXI4Flash.scala`，是所有 MMIO 设备中最简洁的一个，仅约 40 行代码。这种极简设计是因为 Flash 设备本质上是一个只读存储器，不需要复杂的写入逻辑或状态管理。

```scala
class AXI4Flash(address: Seq[AddressSet])(implicit p: Parameters)
  extends AXI4SlaveModule(address, executable = false)
```

关键设计点：
- **只读设备**：虽然 `executable = false` 表示该设备本身在 MMIO 空间中不可执行（不是取指目标），但 Flash 中的内容被加载到主存后可以被 CPU 执行。这里 `executable` 参数控制的是 MMIO 地址空间的属性标记，不是内容属性
- **地址偏移计算**：`getOffset(addr) = Cat(addr(15, beatBits), 0.U(beatBits.W))` -- 取地址的低 16 位并按 beatBytes 对齐，作为 Flash 内部偏移。这意味着 Flash 的可寻址空间为 64KB（2^16 字节）
- **DPI-C 桥接**：实例化 `DifftestFlash()` 对象，当 `in.ar.fire`（读地址通道握手成功）时触发 Flash 读取请求。Flash 的内容实际上存储在仿真主机的内存中，通过 DPI-C 接口访问
- **数据返回**：`in.r.bits.data := flash.data` -- 直接将 DPI-C 返回的数据作为 AXI R 通道数据。由于 AXI4 总线为 64-bit，每次读取返回 8 字节

### 2.2 DiffTest 层实现

`DifftestFlash` 位于 `difftest/src/main/scala/common/Flash.scala`，封装了 Chisel 模块和 Verilog helper：

- **`FlashHelper`**（ExtModule）：通过 DPI-C 接口调用 C++ 函数 `flash_read(addr, &data)`。C++ 端通过内存映射或文件读取获取 Flash 数据
- **FlashHelper.v**：包含两种实现路径：
  - **DPI-C 模式**（默认）：`import "DPI-C" function void flash_read(...)`，直接调用 C++ 仿真函数。该模式提供了最大的灵活性，支持动态加载不同大小的 Flash 镜像
  - **Synthesis 模式**（`DISABLE_DIFFTEST_FLASH_DPIC`）：使用 SystemVerilog 内建 4MB flash_mem 数组（`reg [7:0] flash_mem [0 : FLASH_SIZE-1]`），从 `+flash=<file>` 参数指定的文件加载。8 个并行的 always 块同时读取 8 字节，匹配 64-bit 数据总线宽度
- **默认初始化值**：如果未指定 flash 文件，自动加载 3 条 RISC-V 指令用于跳转到 `0x8000_0000`：
  ```asm
  addiw t0, zero, 1    # t0 = 1
  slli  t0, t0, 0x1f   # t0 = 0x80000000
  jr    t0              # 跳转到 0x80000000
  ```
  这是一个最小化的启动代码，用于在没有加载程序时将 PC 引导到 DDR 起始地址

### 2.3 C++ 仿真支持

`difftest/src/test/csrc/common/flash.cpp` 和 `flash.h` 提供 C++ 层面的文件 I/O 支持，包括 `init_sd` 和 `finish_sd` 函数，通过 `fread` 从文件读取 flash 镜像数据。Flash 镜像通常包含 bootloader 或固件，仿真启动时自动加载到 host 端内存中。

---

## 3. AXI4VGA -- HDMI 800x600 显示与 SDL2 输出

### 3.1 显示标准与参数

`AXI4VGA` 位于 `src/main/scala/device/AXI4VGA.scala`，支持两种显示标准：

**VGA 标准（`HasVGAConst`）：**
- 分辨率：800 x 600
- 水平时序：HFrontPorch=56, HActive=176（HFrontPorch+120）, HBackPorch=976（HActive+800）, HTotal=1040（HBackPorch+64）
- 垂直时序：VFrontPorch=37, VActive=43（VFrontPorch+6）, VBackPorch=643（VActive+600）, VTotal=666（VBackPorch+23）

**HDMI 标准（`HasHDMIConst`，当前激活使用）：**
- 分辨率：800 x 600
- 水平时序：HFrontPorch=40, HActive=168（HFrontPorch+128）, HBackPorch=968（HActive+800）, HTotal=1056（HBackPorch+88）
- 垂直时序：VFrontPorch=1, VActive=5（VFrontPorch+4）, VBackPorch=605（VActive+600）, VTotal=628（VBackPorch+23）

HDMI 模式的 blanking period 比 VGA 更短，意味着更高的有效像素率。

**帧缓冲参数（`HasVGAParameter`）：**
- `FBWidth = ScreenW / 2 = 400`，`FBHeight = ScreenH / 2 = 300`（帧缓冲分辨率为实际显示分辨率的一半）
- `FBPixels = FBWidth * FBHeight = 120,000`（400 x 300 个像素条目）
- 每像素 4 字节（RGBA），帧缓冲总大小 = 120000 x 4 = 480KB
- 通过 2x2 像素复制实现分辨率放大，每个逻辑像素对应屏幕上的 2x2 物理像素

### 3.2 模块架构

`AXI4VGA` 是一个 `LazyModule`，内部包含两个子模块：
1. **`AXI4RAM fb`** -- 帧缓冲区，作为 AXI4 RAM 存储像素数据。大小为 `FBPixels * 4 = 480KB`，在 AXI4 互连中占一个独立的地址空间
2. **`VGACtrl ctrl`** -- VGA 控制寄存器，提供帧大小和同步信号。使用 `RegMap` 工具映射两个只读寄存器：帧大小（0x0）和同步标志（0x4）

两者通过 `AXI4IdentityNode` 统一接入 AXI4 总线。CPU 通过写帧缓冲区来设置像素内容，通过读控制寄存器来获取帧同步状态。

### 3.3 时序生成

VGA 时序通过两级计数器生成：
- **`hCounter`** -- 水平计数器，模 `HTotal`（1056），每个时钟周期递增
- **`vCounter`** -- 垂直计数器，模 `VTotal`（628），仅在 `hFinish`（水平计数器溢出）时递增

同步信号生成：
- `hsync = hCounter >= HFrontPorch` -- 在水平 blanking 期间驱动水平同步脉冲
- `vsync = vCounter >= VFrontPorch` -- 在垂直 blanking 期间驱动垂直同步脉冲
- `valid = hInRange && vInRange` -- 仅在有效显示区域为高，指示当前像素数据有效

### 3.4 像素读取与帧缓冲管理

帧缓冲区采用双缓冲策略（`fbPixelAddrV0` 和 `fbPixelAddrV1`），根据 `vCounterIsOdd`（垂直计数器最低位）交替读取。这种交错读取方式可以隐藏 BRAM 的读延迟：
- 像素地址计算：`Counter(nextPixel && !vCounterIsOdd, FBPixels)` -- 两个独立的像素地址计数器
- 读请求提前 2 个周期发出（`RegNext(nextPixel) && hCounterIs2`），补偿 BRAM 读取的 2 周期流水线延迟
- 每次读取 64 位数据，包含 2 个像素的 RGBA 值：`color = Mux(hCounter(1), data(63,32), data(31,0))`
- 像素有效性判断：`io.vga.rgb := Mux(io.vga.valid, color(23, 0), 0.U)` -- 在 blanking 期间输出黑色

### 3.5 FBHelper DPI-C 模块

`FBHelper` 是一个 Verilog ExtModule，通过 DPI-C 桥接实现 SDL2 渲染：
- `put_pixel(pixel)` -- 将像素值写入 C++ 端的 `vmem[800*600]` 静态数组
- `vmem_sync()` -- 触发一帧的渲染流程：`SDL_UpdateTexture` -> `SDL_RenderClear` -> `SDL_RenderCopy` -> `SDL_RenderPresent`
- 仅在 `sim = true` 时实例化，综合时不会生成该模块

### 3.6 SDL2 仿真渲染（C++ 层）

`difftest/src/test/csrc/common/vga.cpp` 中的 SDL2 实现：
- **初始化**：`SDL_Init(SDL_INIT_VIDEO)` + `SDL_CreateWindowAndRenderer(800, 600, 0, &window, &renderer)` + 创建 ARGB8888 格式的静态纹理 `SDL_CreateTexture(renderer, SDL_PIXELFORMAT_ARGB8888, SDL_TEXTUREACCESS_STATIC, 800, 600)`
- **像素写入**：`vmem[i++] = pixel`，按行优先顺序循环写满 800x600 后归零重写
- **帧同步**：当 `vmem_sync()` 被调用时，将 vmem 数组的内容上传到 SDL 纹理并渲染到屏幕
- **窗口标题**设置为 "NOOP"（致敬清华大学 NOOP 教学 CPU 项目）
- 在非 `SHOW_SCREEN` 模式下，`put_pixel` 和 `vmem_sync` 为空函数，不消耗仿真资源

---

## 4. AXI4Keyboard -- PS/2 协议与 SDL2 输入

### 4.1 Chisel 模块设计

`AXI4Keyboard` 位于 `src/main/scala/device/AXI4Keyboard.scala`，实现 PS/2 键盘协议解码。

```scala
class AXI4Keyboard(address: Seq[AddressSet])(implicit p: Parameters)
  extends AXI4SlaveModule(address, executable = false, _extra = new KeyboardIO)
```

**IO 接口**（`KeyboardIO`）：
- `ps2Clk` -- PS/2 时钟线，由键盘设备驱动
- `ps2Data` -- PS/2 数据线，携带串行数据位

注意：该模块的源码注释标注 "this Module is not tested"，说明它在硬件验证中的使用有限，主要用于仿真环境中的键盘输入模拟。

### 4.2 PS/2 协议解码

PS/2 协议采用串行通信，每个键码包含 11 位帧：1 start bit (0) + 8 data bits (LSB first) + 1 parity bit (odd) + 1 stop bit (1)。

解码流程：
1. **时钟边沿检测**：使用两级寄存器同步 PS/2 时钟信号，检测下降沿：`negedge = RegNext(ps2ClkLatch) && ~ps2ClkLatch`。PS/2 协议在时钟下降沿采样数据
2. **数据移入**：在每个下降沿将数据位移入 10-bit 移位寄存器：`buf := Cat(io.extra.get.ps2Data, buf(9,1))`。数据从 LSB 开始移入
3. **计数器**：`cnt = Counter(negedge, 10)` -- 计数 10 个时钟周期，对应完整的 11 位帧（start + 8 data + parity，stop bit 用于校验不存入）
4. **帧校验**：当计数器到达 10 时同时检查三个条件：
   - `!buf(0)` -- start bit 必须为 0
   - `buf(9)` -- stop bit（移位后在最高位）必须为 1
   - `buf(9,1).xorR` -- odd parity 校验，数据位加上 parity 位的 XOR 应为 1
5. **数据入队**：校验通过后，将 buf[8:1]（8 位数据）写入 `Queue(UInt(8.W), 8)`，该队列可缓冲 8 个未读取的按键编码

### 4.3 读取接口

当 CPU 执行 MMIO 读操作时，`in.r.bits.data` 根据队列状态返回：
```scala
in.r.bits.data := Mux(queue.io.deq.valid, queue.io.deq.bits, 0.U)
```
如果队列中有按键数据，返回按键编码（8 位有效数据，高 56 位为零）；否则返回 0（表示无按键事件）。队列的 `deq.ready` 连接到 `in.r.ready`，使得读取操作同时出队。

### 4.4 SDL2 键盘事件处理（C++ 层）

`difftest/src/test/csrc/common/keyboard.cpp` 实现了 SDL2 scancode 到自定义按键编码的映射：

- **按键队列**：环形缓冲区 `key_queue[1024]`，FIFO 方式存储按键事件。生产者是 SDL2 事件循环（`send_key`），消费者是硬件仿真中的键盘 MMIO 读取
- **按键编码格式**：高 16 位为按下/释放标志（`KEYDOWN_MASK = 0x8000`），低 16 位为按键编号（`_KEY_NONE=0`, `_KEY_ESCAPE=1` 等）
- **SDL scancode 映射表**：`keymap[256]` 数组将 SDL scancode 映射为自定义编码。表中未映射的位置为 0（`_KEY_NONE`），无效的按键事件会被丢弃
- **SDL 事件处理**（`device.cpp` 中的 `poll_event()`）：`SDL_PollEvent` 循环处理 `SDL_KEYDOWN` 和 `SDL_KEYUP` 事件，提取 `event.key.keysym.scancode` 调用 `send_key(k, is_keydown)` 将事件入队
- 支持完整键盘布局：ESCAPE, F1-F12, 数字键 0-9, 字母键 A-Z, 方向键 UP/DOWN/LEFT/RIGHT, 功能键 INSERT/DELETE/HOME/END/PAGEUP/PAGEDOWN, 修饰键 LSHIFT/RSHIFT/LCTRL/RCTRL/LALT/RALT 等

---

## 5. AXI4DummySD -- SDHCI 寄存器仿真

### 5.1 设计概述

`AXI4DummySD` 位于 `src/main/scala/device/AXI4DummySD.scala`，模拟 SD Host Controller (SDHCI) 寄存器接口，使软件能够通过标准 SD 协议与仿真环境中的 SD 卡镜像交互。这是一个典型的"假设备"设计：在硬件层面模拟寄存器行为，在仿真层面通过 DPI-C 实际访问文件系统中的 SD 卡镜像。

```scala
class AXI4DummySD(address: Seq[AddressSet])(implicit p: Parameters)
  extends AXI4SlaveModule(address, executable = false) with HasSDConst
```

### 5.2 SD 卡容量常量（`HasSDConst`）

这些常量模拟了一张 4GB 容量的 SD 卡，通过 CSD (Card Specific Data) 寄存器向软件报告：

```scala
def MemorySize = 4L * 1024 * 1024 * 1024  // 4GB = 4,294,967,296 bytes
def READ_BL_LEN = 15                        // 最大读块长度 = 2^15 = 32KB
def BlockLen = (1 << READ_BL_LEN)           // 32768 bytes per block
def NrBlock = MemorySize / BlockLen          // 131072 blocks
def C_SIZE_MULT = 7                         // Card Capacity Multiplier
def MULT = (1 << (C_SIZE_MULT + 2))         // = 512
def C_SIZE = NrBlock / MULT - 1             // = 255
```

这些参数遵循 SD Physical Layer Simplified Specification 中 CSD 寄存器的字段定义，使得 Linux 内核的 SD/MMC 驱动能够正确识别并初始化这张"SD 卡"。

### 5.3 SDHCI 寄存器映射

共 21 个 32-bit 寄存器，使用 `RegMap.generate` 进行地址映射。每个寄存器通过 `RegMap(offset, reg, wfn)` 定义其偏移地址、存储寄存器和可选的写入函数：

| 偏移   | 寄存器名 | 读/写  | 功能描述 |
|--------|----------|--------|----------|
| 0x00   | sdcmd    | R/W    | SD 命令寄存器，写入时触发 `cmdWfn` 命令处理函数 |
| 0x04   | sdarg    | R/W    | SD 命令参数寄存器（32-bit 地址） |
| 0x10   | sdrsp0   | RO     | SD 响应寄存器 0（`RegMap.Unwritable`） |
| 0x14   | sdrsp1   | RO     | SD 响应寄存器 1（`RegMap.Unwritable`） |
| 0x18   | sdrsp2   | RO     | SD 响应寄存器 2（`RegMap.Unwritable`） |
| 0x1c   | sdrsp3   | RO     | SD 响应寄存器 3（`RegMap.Unwritable`） |
| 0x20   | sdhsts   | R/W    | SD 状态寄存器 |
| 0x34   | edmConst | RO     | EDM 常量（FIFO 深度 = 8 << 4 = 128 entries） |
| 0x38   | sdhcfg   | R/W    | SD 主配置寄存器 |
| 0x40   | sdRead   | RO     | SD 数据读取端口（触发 DPI-C 读取） |
| 0x50   | sdhblc   | R/W    | SD 块长度计数寄存器 |

### 5.4 MMC 命令仿真

`cmdWfn` 是写入 `sdcmd` 寄存器时调用的副作用函数。它解析写入的命令码（`wdata[5:0]`），模拟 MMC/SD 卡控制器对命令的响应：

- **MMC_SEND_OP_COND (CMD1)**：设置 `regs(sdrsp0) := 0x80ff8000`，其中 bit31 表示卡就绪（Power Up Status），低 20 位为 OCR 寄存器值，表示支持的电压范围
- **MMC_ALL_SEND_CID (CMD2)**：设置 4 个响应寄存器为固定 CID 值，模拟一张具有特定制造信息的 SD 卡
- **MMC_SEND_CSD (CMD9)**：设置 CSD 结构，其中嵌入了 `C_SIZE`（卡容量）和 `READ_BL_LEN`（最大块长度）字段。CSD 字段的编排遵循 SD 规范中的 CSD Version 2.0 格式
- **MMC_SEND_STATUS (CMD13)**：设置所有响应寄存器为零，表示卡状态正常（无错误标志）
- **MMC_READ_MULTIPLE_BLOCK (CMD18)**：设置 `setAddr := true` 信号，告诉 DPI-C 后端将 `sdarg` 寄存器中的地址设为后续读取操作的起始位置

### 5.5 数据读取路径

```scala
val sdHelper = DifftestSDCard()
sdHelper.ren := (getOffset(raddr) === 0x40.U && in.ar.fire)
sdHelper.setAddr := setAddr
sdHelper.addr := regs(sdarg)
```

当 CPU 读取偏移 0x40 地址时（SDHCI 数据端口），触发 DPI-C 调用 `sd_read()` 从仿真 SD 卡镜像读取 32 位数据。数据从 `sdarg` 指定的地址开始顺序读取。

**数据宽度适配**：`in.r.bits.data := Fill(2, rdata)` -- 将 32 位数据复制到高/低 32 位（`Fill(2, rdata)` 产生 `{rdata, rdata}`），适配 64-bit AXI4 数据总线。这是因为 SD 数据端口只有 32 位宽，但 AXI4 总线为 64 位。

### 5.6 DPI-C 桥接与 C++ 实现

`DifftestSDCard` / `SDCardHelper` 通过 DPI-C 调用 C++ 函数：
- `sd_setaddr(addr)` -- 调用 `fseek(fp, addr, SEEK_SET)` 设置文件偏移。在 `setAddr` 信号有效时的上升沿调用
- `sd_read(data)` -- 调用 `fread(data, 4, 1, fp)` 读取 4 字节（32 位）数据。在 `ren` 信号有效时的下降沿调用

SD 卡镜像通过 `+SDCARD_IMAGE=<path>` 编译宏指定，仿真启动时 `init_sd()` 以 "r" 模式打开文件。如果没有定义 `SDCARD_IMAGE` 宏，读取操作将不执行任何实际 I/O。

---

## 6. Memory Encryption -- XTS-SM4 加密引擎

### 6.1 架构概述

Memory Encryption 模块位于 `src/main/scala/device/MemEncrypt.scala` 和 `MemEncryptUtil.scala`，是香山处理器的内存加密子系统，基于 **XTS-SM4** 模式对内存读写数据进行透明的加密和解密。该模块通过 `AXI4AdapterNode` 插入到 CPU cache 层级和 DDR 内存控制器之间，对软件完全透明。

`AXI4MemEncrypt` 模块的主要接口：
- **`AXI4AdapterNode`**：在 AXI4 互连中作为 adapter，拦截所有内存读写请求
- **APB `ctrl_node`**：通过 APB 总线访问控制寄存器（`MemEncryptCSR`）
- **随机数接口**：`random_req` / `random_val` / `random_data` 用于从硬件真随机数生成器收集熵值

该模块要求 AXI4 数据总线宽度为 256-bit（`require(edgeIn.bundle.dataBits == 256)`），这是为了匹配 SM4 的 128-bit 分组大小和 XTS 模式下的 2x 并行加密需求。

### 6.2 密码学基础：SM4 算法

XiangShan 的内存加密引擎使用的是 **SM4**（国密分组密码算法），而非标准 AES-128。SM4 是中国国家商用密码标准，分组长度和密钥长度均为 128 位，加密轮数为 32 轮。核心组件包括：

**S-box 替换（`SboxReplace`）**：
- 256 元素的固定替换表，将 8-bit 输入映射为 8-bit 非线性输出
- 4 个 `SboxReplace` 模块并行处理 32-bit 字的 4 个字节，实现 SubBytes 操作

**数据加密线性变换（`TransformForEncDec`）**：
- 组合 S-box 替换和线性扩散操作
- 线性变换由三个循环左移异或组成：`L(B) = B XOR (B<<<2) XOR (B<<<10) XOR (B<<<18) XOR (B<<<24)`
- 这个线性变换提供了良好的扩散特性，确保单个输入比特的变化影响所有输出比特

**密钥扩展线性变换（`TransformForKeyExp`）**：
- 密钥扩展专用的线性变换，比数据变换更简化
- 线性变换为：`L'(B) = B XOR (B<<<13) XOR (B<<<23)`

### 6.3 密钥扩展（`KeyExtender`）

密钥扩展通过 `OneRoundForKeyExp` 模块在 32 轮迭代中生成轮密钥：

- **FK 参数**：系统参数 `FK0=0xa3b1bac6, FK1=0x56aa3350, FK2=0x677d9197, FK3=0xb27022dc`。第一轮将用户密钥与 FK 异或
- **CKI 参数**（`GetCKI`）：32 个 32-bit 常量（如 `0x00070e15, 0x1c232a31` 等），每轮使用不同的 CKI 值与密钥扩展中间结果混合
- **FSM 状态机**：`idle` -> `keyExpansion`（32 轮）-> `idle`。每轮迭代生成一个 32-bit 轮密钥
- 密钥表（`KeyTable`）：存储所有 KeyID 对应的 32 个轮密钥，使用寄存器数组实现（`RegInit(VecInit(...))`）。支持加密和解密方向的并发查询，解密时轮密钥逆序输出

### 6.4 Tweak 生成（XTS 模式）

XTS-AES（或 XTS-SM4）的核心是 Tweak（调整值）机制，确保相同的明文块在不同的磁盘位置产生不同的密文，防止攻击者通过模式分析破解加密。

**GF(2^128) 有限域运算（`GF128`）**：
- 对初始 Tweak 进行连续的 GF(2^128) 乘法：左移一位 + 条件异或 0x87（当最高位为 1 时）
- 生成 4 个 Tweak 值（T0, T1=T0*alpha, T2=T0*alpha^2, T3=T0*alpha^3），对应 256-bit 数据总线的 4 个 128-bit 子块

**Tweak 加密（`TweakEncrypt`）**：
- 使用 `MemencPipes` 级流水线对初始 Tweak 进行 SM4 加密
- 加密后的 Tweak 输出为字节序反转的 128-bit 值：`Cat(reg_tweak.last(31,0), reg_tweak.last(63,32), reg_tweak.last(95,64), reg_tweak.last(127,96))`

**Tweak 表（`TweakTable`）**：
- 以 AXI4 ID 为索引的 Tweak 查找表，条目包含有效标志（`v_flag`）、KeyID、长度和加密后的 Tweak
- 在 AR 通道发送请求时写入 Tweak 条目，在 R 通道接收数据时读取
- 支持 `sel_counter` 机制处理多 beat 传输：对于 len>0 的突发传输，第一个 beat 使用原始 Tweak，后续 beat 使用 GF128 递增后的 Tweak

### 6.5 写数据加密流水线（`WdataEncrptyPipe`）

写入路径的加密流程涉及多个协作模块：
1. **`WriteChanelRoute`** -- 根据 KeyID 的加密模式（查 `KeyTable`），将写请求路由到加密通道（`out1`）或非加密通道（`out0`）。使用 `IrrevocableQueue` 缓冲 AW 信号以解耦握手
2. **`TweakEncrptyQueue`** -- 生成并加密写操作的 Tweak，串联 `TweakEncrypt` 和 `GF128` 模块
3. **`AXI4WriteMachine`** -- 处理非缓存（non-cacheable，即 strb 不全为 1）写操作的 read-modify-write 序列：先发出 AR 读请求获取原始数据，再与写数据合并后加密输出
4. **`WdataEncrptyPipe`** -- 多级流水线执行 SM4 加密。将 256-bit 数据拆分为两个 128-bit 块并行加密，每个块经过 `MemencPipes` 级流水线处理。支持 `HasDelayNoencryption` 模式（延迟非加密模式）用于调试
5. **`WriteChanelArbiter`** -- 将加密和非加密写通道合并输出到 DDR 端。使用 `validMask` 仲裁策略：非加密通道有较高优先级，但加密通道在连续传输时不被饿死

### 6.6 读数据解密流水线（`RdataDecrptyPipe`）

读取路径的解密流程与加密对称：
1. **`RdataChanelRoute`** -- 根据 `dec_mode` 信号（从 Tweak 表获取）将读响应路由到解密通道或直通通道
2. **`TweakEncrptyTable`** -- 查找并生成解密 Tweak，使用 `TweakTable` + `TweakEncrypt` + `GF128` 组合
3. **`AXI4ReadMachine`** -- 两阶段流水线处理读响应：s1 阶段发起 Tweak 查询，s2 阶段将 Tweak 与数据合并
4. **`RdataDecrptyPipe`** -- 多级流水线执行 SM4 解密。解密时轮密钥顺序与加密相反（`KeyTable` 中 `dec_round_keys` 逆序读取），Tweak 异或操作的顺序也相应反转

### 6.7 MemEncryptCSR -- 控制寄存器

通过 APB 总线访问的 CSR 寄存器，管理加密引擎的配置和状态：

- **CONTROL 寄存器**（offset 0x00）：KeyID[4:0], Mode[6:5], TweakFlag[7], MemencEnable[8], RandomReady[32], KeyExpansionIdle[33], LastReqAccepted[34], CfgSuccess[35]
- **KEY0 寄存器**（offset 0x08）：密钥低 64 位
- **KEY1 寄存器**（offset 0x10）：密钥高 64 位
- **地址掩码寄存器**（offset 0x18）：`RelPaddrBitsMap`，指示物理地址中用于 KeyID 的位范围
- **版本寄存器**（offset 0x28）：`0x0001_0001_00000002`，版本号 1.1.2

**Mode 定义**：
- 0：不加密（`enc_mode = false`），该 KeyID 下的读写不经过加解密
- 1：软件写入密钥模式，软件将 128-bit 密钥通过 KEY0/KEY1 寄存器写入，然后触发密钥扩展
- 2：硬件随机数密钥模式，需要先等待 128-bit 随机数收集完成（`random_ready_flag`），然后用随机数作为密钥
- 3：保留（非法模式，`req_legal` 检查会拒绝）

**Random 收集机制**：`MemEncryptCSR` 管理 128-bit 随机数收集过程。`random_req` 信号在 `random_cnt != 128` 时持续为高，每次 `random_val` 有效时将 `random_data` 位移入 `random_vec_data`。收集满 128 位后 `random_ready_flag` 置位。

---

## 7. DMA Controller

### 7.1 设计概述

`AXI4DMAC` 位于 `src/main/scala/device/AXI4DMAC.scala`，是一个用于测试目的的简化 DMA (Direct Memory Access) 控制器。与生产级 DMA 相比，它缺少 scatter-gather、中断支持、多通道等功能，但足以验证 AXI4 DMA 传输的基本正确性。

```scala
class AXI4DMAC(address: Seq[AddressSet])(implicit p: Parameters)
  extends AXI4SlaveModule(address, executable = false)
```

### 7.2 寄存器映射

| 偏移   | 寄存器      | 读/写 | 功能描述 |
|--------|-------------|-------|----------|
| 0x00   | srcAddrReg  | R/W   | 源地址（64-bit），DMA 读取的起始地址 |
| 0x08   | dstAddrReg  | R/W   | 目标地址（64-bit），DMA 写入的起始地址 |
| 0x10   | cfg_reg     | R/W   | 配置寄存器：bit[0]=start（上升沿触发传输），bit[1]=done（传输完成标志） |

寄存器使用 `RegMap.generate` 映射到 12-bit 偏移地址空间（`raddr(11,0)`），支持 64-bit 数据总线上的字节级写入。

### 7.3 AXI4 Master 接口

DMA 作为 AXI4 Master 通过 `AXI4MasterNode` 访问系统内存，这是 DMA 控制器与普通 MMIO 设备的关键区别——它不仅能被动响应 CPU 的读写请求，还能主动发起内存访问：

- ID 范围：`IdRange(0, 1 << 14)` -- 支持最多 16384 个并发事务 ID
- 数据宽度：256-bit（`size := 5.U`，即 `log2Ceil(32) = 5`），匹配香山处理器的 L2 cache line 宽度
- 单 beat 传输：`len := 0.U`，每次传输 32 字节
- Burst 类型：`burst := 1.U`（BURST_INCR），地址自动递增

### 7.4 DMA 引擎状态机

```scala
object State_axi extends ChiselEnum {
  val sIdle, sAr, sRdata, sAw, sWdata, sB = Value
}
```

6 状态 FSM 实现完整的 DMA 传输流程：

1. **`sIdle`** -- 等待启动信号：`axi_start = cfg_reg(0) && !start_reg_dly`，检测 `cfg_reg` bit[0] 的上升沿（由 `RegNext` 延迟一拍实现边沿检测）
2. **`sAr`** -- 在 `masterBundle.ar` 通道发出读地址请求，源地址为 `srcAddrReg[47:0]`。当 `masterBundle.ar.fire` 时转入 `sRdata`
3. **`sRdata`** -- 接收读数据并写入 FIFO。`masterBundle.r.ready` 由 FIFO 的 `enq.ready` 控制，实现背压机制。当收到 `r.last` 信号时转入 `sAw`
4. **`sAw`** -- 在 `masterBundle.aw` 通道发出写地址请求，目标地址为 `dstAddrReg[47:0]`。当 `masterBundle.aw.fire` 时转入 `sWdata`
5. **`sWdata`** -- 从 FIFO 读出数据并通过 `masterBundle.w` 通道写入。`w.last` 在首次 fire 时即为 true（单 beat 传输）。当 `masterBundle.w.fire && w.last` 时转入 `sB`
6. **`sB`** -- 等待写响应。当 `masterBundle.b.fire` 时，将 `cfg_reg` 设为 `0x2`（bit[1] 置位表示传输完成），返回 `sIdle`

### 7.5 FIFO 缓冲

使用 `Queue(UInt(256.W), 128)` 作为数据缓冲：
- 容量：128 个 256-bit 条目 = 4KB 数据缓冲
- 解耦读数据接收（`sRdata` 状态）和写数据发送（`sWdata` 状态），允许两个操作在不同时序条件下运行
- 由于当前设计只支持单次 32 字节传输，FIFO 实际上只使用 1 个条目，4KB 的容量为未来扩展预留了空间

---

## 8. RegMap Utility 与 MMIO 模式

### 8.1 RegMap 工具（`utility/src/main/scala/utility/RegMap.scala`）

RegMap 是 XiangShan 中 MMIO 寄存器映射的统一工具，提供三种递进式变体，从简单到复杂满足不同设备的需求：

**基础 `RegMap`**：
```scala
object RegMap {
  def Unwritable = null
  def apply(addr: Int, reg: UInt, wfn: UInt => UInt = (x => x)) = (addr, (reg, wfn))
  def generate(mapping, raddr, rdata, waddr, wen, wdata, wmask)
}
```
- 读逻辑：`rdata := LookupTree(raddr, mapping.map { case (a, r, _) => (a, r) })` -- 使用 Mux 树实现地址译码读取
- 写逻辑：`when (wen && waddr === a) { r := w(MaskData(r, wdata, wmask)) }` -- 地址匹配时执行写入
- `wfn` 允许自定义写入转换函数，例如 `AXI4DummySD` 的 `cmdWfn` 在写入命令寄存器时同时更新响应寄存器
- `RegMap.Unwritable` 返回 `null`，在生成写逻辑时跳过该寄存器，实现只读语义

**`MaskedRegMap`**：
- 增加读写掩码（`wmask`, `rmask`），支持 64-bit 粒度的位级掩码控制
- 写入增加 `RegNext` 延迟（`GatedValidRegNext`）降低扇出，这是大规模寄存器文件综合优化的关键技术
- 提供 `isIllegalAddr` 地址合法性检查函数，返回地址是否不在映射范围内

**`ConditionalRegMap`**：
- 支持条件写入（每个寄存器条目携带 `condition: Bool`），当条件不满足时写入被忽略
- 使用 `Mux1H` 进行 OneHot 选择，适用于同一地址在不同条件下映射到不同寄存器的场景
- 支持 `RegMapEntry` 和 `ConditionalRegMapEntry` 两种条目类型，以及隐式转换简化使用

### 8.2 MMIO 设备使用模式

各 MMIO 设备对 RegMap 的典型使用展示了不同的设计模式：

**AXI4VGA（VGACtrl）-- 纯只读寄存器**：
```scala
val mapping = Map(
  RegMap(0x0, fbSizeReg, RegMap.Unwritable),  // 只读：帧大小 400x300
  RegMap(0x4, sync, RegMap.Unwritable)         // 只读：同步标志
)
RegMap.generate(mapping, raddr(3,0), in.r.bits.data, waddr(3,0), in.w.fire, ...)
```

**AXI4DummySD -- 带副作用的命令寄存器**：
```scala
val mapping = Map(
  RegMap(0x00, regs(sdcmd), cmdWfn),           // 写入触发命令处理
  RegMap(0x04, regs(sdarg)),                    // 普通读写
  RegMap(0x10, regs(sdrsp0), RegMap.Unwritable),  // 只读响应
  RegMap(0x40, sdRead, RegMap.Unwritable),     // 只读：触发 DPI-C 读取
  ...
)
```

**AXI4DMAC -- 简单读写寄存器**：
```scala
val mapping = Map(
  RegMap(0x00, srcAddrReg),   // 源地址
  RegMap(0x08, dstAddrReg),   // 目标地址
  RegMap(0x10, cfg_reg)       // 配置
)
RegMap.generate(mapping, raddr(11,0), in.r.bits.data,
  waddr(11,0), in.w.fire, in.w.bits.data, MaskExpand(in.w.bits.strb))
```

### 8.3 MMIO 数据宽度适配

由于 AXI4 总线为 64-bit（`beatBytes = 8`），而许多寄存器为 32-bit，需要处理高低 32-bit 的映射。主要技巧包括：

- **字节地址偏移判断**：`val strb = Mux(waddr(2), in.w.bits.strb(7,4), in.w.bits.strb(3,0))` -- 通过地址 bit[2] 判断访问高 32 位还是低 32 位
- **数据复制**：`in.r.bits.data := Fill(2, rdata)` -- 将 32 位数据复制到高/低 32 位，无论 CPU 读取哪个半字都能得到正确数据
- **RegMap 地址偏移**：`getOffset(waddr)` / `getOffset(raddr)` 提取有效偏移位，忽略 AXI4 总线地址中的高位

---

## 9. DiffTest 基础设施

### 9.1 设备初始化流程

`difftest/src/test/csrc/common/device.cpp` 中的 `init_device()` 按顺序初始化所有仿真外设：
1. `init_sdl()` -- SDL2 图形子系统（仅 `SHOW_SCREEN` 宏开启时）。初始化视频子系统，创建 800x600 窗口和 ARGB8888 纹理
2. `init_uart()` -- UART 串口初始化，设置串口输入输出
3. `init_sd()` -- SD 卡镜像文件打开（`fopen`），如果定义了 `SDCARD_IMAGE` 宏

`finish_device()` 按逆序清理资源：`finish_sdl()` 清零帧缓冲、`finish_uart()` 关闭串口、`finish_sd()` 关闭 SD 卡文件。

### 9.2 事件轮询

`poll_event()` 函数在仿真主循环中被周期性调用，处理所有 SDL2 事件：
- `SDL_QUIT` -- 窗口关闭事件
- `SDL_KEYDOWN` / `SDL_KEYUP` -- 键盘按下/释放事件，提取 scancode 后调用 `send_key(k, is_keydown)` 入队到键盘环形缓冲区

### 9.3 DiffTest RAM 模型

`difftest/src/main/scala/common/Mem.scala` 提供了仿真用的 RAM 模型（`DifftestMem`），是 XiangShan 仿真平台的主存实现：
- 支持多读多写端口（`nr` reads, `nw` writes），满足乱序处理器的多端口访问需求
- **DPI-C 模式**（默认）：使用 `difftest_ram_read` / `difftest_ram_write` C++ 函数操作主机内存
- **Synthesis 模式**：使用 SystemVerilog reg 数组 `memory [0 : RAM_SIZE/8-1]` 直接存储
- 支持从 `+workload=<file>` plusarg 加载二进制镜像文件到 RAM 初始状态
- 支持 GSIM（Gate-level Simulation）模式下的异步读取路径

---

## 10. 源文件索引

### Chisel 设备模块
| 文件路径 | 描述 |
|----------|------|
| `src/main/scala/device/AXI4SlaveModule.scala` | MMIO 设备基类，4 状态 AXI4 FSM |
| `src/main/scala/device/AXI4Flash.scala` | 只读 Flash 仿真（约 40 行） |
| `src/main/scala/device/AXI4VGA.scala` | VGA/HDMI 显示控制器，SDL2 渲染 |
| `src/main/scala/device/AXI4Keyboard.scala` | PS/2 键盘控制器，协议解码 |
| `src/main/scala/device/AXI4DummySD.scala` | SDHCI 寄存器仿真，SD 卡命令响应 |
| `src/main/scala/device/AXI4DMAC.scala` | DMA 控制器（测试用途，32B 传输） |
| `src/main/scala/device/MemEncrypt.scala` | XTS 内存加密引擎主模块（约 1200 行） |
| `src/main/scala/device/MemEncryptUtil.scala` | SM4 算法组件、Tweak 处理、密钥扩展（约 820 行） |

### RegMap 工具
| 文件路径 | 描述 |
|----------|------|
| `utility/src/main/scala/utility/RegMap.scala` | RegMap / MaskedRegMap / ConditionalRegMap |

### DiffTest Scala 层
| 文件路径 | 描述 |
|----------|------|
| `difftest/src/main/scala/common/Flash.scala` | Flash DPI-C 桥接，FlashHelper ExtModule |
| `difftest/src/main/scala/common/Mem.scala` | 仿真 RAM 模型，MemRWHelper |
| `difftest/src/main/scala/common/SDCard.scala` | SD 卡 DPI-C 桥接，SDCardHelper ExtModule |

### DiffTest C++ 层
| 文件路径 | 描述 |
|----------|------|
| `difftest/src/test/csrc/common/device.cpp` | 设备初始化与 SDL2 事件轮询 |
| `difftest/src/test/csrc/common/device.h` | 设备接口声明 |
| `difftest/src/test/csrc/common/vga.cpp` | SDL2 VGA 渲染（put_pixel, vmem_sync, init_sdl） |
| `difftest/src/test/csrc/common/vga.h` | VGA 接口声明 |
| `difftest/src/test/csrc/common/keyboard.cpp` | PS/2 scancode 映射表与键盘环形队列 |
| `difftest/src/test/csrc/common/sdcard.cpp` | SD 卡文件 I/O（sd_read, sd_setaddr, init_sd） |
| `difftest/src/test/csrc/common/sdcard.h` | SD 卡接口声明 |
| `difftest/src/test/csrc/common/flash.cpp` | Flash 文件 I/O |
| `difftest/src/test/csrc/common/flash.h` | Flash 接口声明 |

### 其他 MMIO 设备（参考）
| 文件路径 | 描述 |
|----------|------|
| `src/main/scala/device/AXI4UART.scala` | UART 控制器 |
| `src/main/scala/device/AXI4UART16550.scala` | 16550 兼容 UART |
| `src/main/scala/device/AXI4Timer.scala` | 定时器 |
| `src/main/scala/device/AXI4Plic.scala` | PLIC 中断控制器 |
| `src/main/scala/device/AXI4IntrGenerator.scala` | 中断生成器 |
| `src/main/scala/device/AXI4RAM.scala` | AXI4 RAM |
| `src/main/scala/device/AXI4Memory.scala` | AXI4 内存模型 |
