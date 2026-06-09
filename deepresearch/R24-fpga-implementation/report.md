# R24 - FPGA 与物理实现研究报告

## 1. 概述

XiangShan（香山）作为一款开源的高性能 RISC-V 处理器，其设计目标不仅覆盖 ASIC 流片场景，也充分考虑了 FPGA 原型验证与物理实现的需求。本报告深入研究 XiangShan 项目中与 FPGA 实现和物理设计相关的各个方面，涵盖 FPGA 适配配置、I/O 内存保护单元（IOPMP）、独立 cache 子系统（XSCache）、VexiiRscv 轻量级处理器内核、XDMA DMA 通信机制、仿真支持、工具链以及物理设计考量等内容。

---

## 2. FPGA 实现流程

### 2.1 总体架构

XiangShan 的 FPGA 实现流程基于 Chisel 硬件描述语言生成 Verilog，再通过 FPGA 综合工具（如 Vivado、Quartus）进行实现。整体流程为：

1. **Chisel 代码编写**：核心逻辑使用 Scala/Chisel 描述
2. **Verilog 生成**：通过 `ChiselStage.emitSystemVerilog` 或 firtool 生成 SystemVerilog
3. **FPGA 综合**：利用 Xilinx Vivado 或 Intel Quartus 等工具进行综合、布局布线
4. **Bitstream 生成**：生成 FPGA 配置文件并下载到目标板
5. **DiffTest 验证**：通过 XDMA 接口进行 FPGA 上的差异测试

### 2.2 FPGAPlatform 配置机制

XiangShan 通过 `FPGAPlatform` 布尔参数来区分 ASIC 和 FPGA 两种目标平台。该参数定义在：

**源文件位置**：`src/main/scala/xiangshan/Parameters.scala`

```scala
case class DebugOptions(
  FPGAPlatform: Boolean = false,
  DumpCSR: Boolean = false,
  ResetGen: Boolean = false,
  EnableDifftest: Boolean = false,
  AlwaysBasicDiff: Boolean = true,
  EnableDebug: Boolean = false,
  ...
)
```

当 `FPGAPlatform = true` 时，设计会在以下方面进行调整：

- **禁用性能计数器数据库（ChiselDB/Constantin）**：减少 FPGA 资源消耗
- **禁用 TL Log 记录**：在 `SoC.scala` 中，`TLLogger` 的使能与 `FPGAPlatform` 关联
- **禁用 Bus 性能监控**：`BusPerfMonitor` 在 FPGA 模式下关闭
- **禁用 elaborated TopDown 监控**：减少调试逻辑
- **禁用 ReqSourceKey 请求源标记**：在 SoC 的 TileLink 节点中省略
- **OpenLLC 参数适配**：LLC 的 `FPGAPlatform` 参数传递到各子模块

相关源文件：
- `src/main/scala/top/Top.scala` — 顶层模块，FPGA 平台检测与 ChiselDB/Constantin 初始化
- `src/main/scala/system/SoC.scala` — SoC 级别的 FPGA 条件逻辑
- `src/main/scala/xiangshan/L2Top.scala` — L2 缓存顶层的 FPGA 适配
- `src/main/scala/top/Configs.scala` — L2 配置中 `enablePerf` 和 `elaboratedTopDown` 与 FPGA 的关系

### 2.3 FPGA 构建流程

FPGA 主机程序的构建定义在：

**源文件位置**：`difftest/fpga.mk`

关键构建配置：

```makefile
FPGA_TARGET = $(BUILD_DIR)/fpga-host
FPGA_CSRC_DIR = $(abspath ./src/test/csrc/fpga)
DMA_CHANNELS ?= 1
USE_SERIAL_PORT ?= 1
FPGA_CXXFLAGS = ... -DFPGA_HOST
FPGA_CXXFLAGS += -std=c++11 -O3 -flto -march=native -mtune=native
```

构建选项包括：
- `DMA_CHANNELS`：DMA 通道数量，支持多通道并发数据传输
- `USE_SERIAL_PORT`：启用串口通信（通过 `/dev/ttyUSB0`）
- `USE_XDMA_DDR_LOAD`：通过 XDMA 加载 workload 到 DDR
- `USE_THREAD_MEMPOOL`：使用多线程内存池提升性能

---

## 3. ChiselIOPMP 设计与用途

### 3.1 背景与定位

ChiselIOPMP 是 RISC-V I/O 物理内存保护（I/O Physical Memory Protection, IOPMP）的开源 Chisel 实现。在物理实现中，IOPMP 用于保护 I/O 设备的内存访问安全，是 RISC-V 片上系统安全架构的关键组件。

**源文件位置**：`ChiselIOPMP/src/main/scala/`

- `Iopmp.scala` — 核心逻辑，包含参数定义、寄存器表、控制状态机等
- `IopmpChecker.scala` — AXI 总线桥，拦截并检查 AXI 事务
- `IopmpBridge.scala` — AXI4 桥接器，集成到 Rocket Chip Diplomacy 框架

### 3.2 架构设计

ChiselIOPMP 采用模块化设计，核心架构包含以下组件：

#### 3.2.1 参数体系（IopmpParams）

IOPMP 支持丰富的可配置参数：

- **RRID（Requester Request ID）数量**：默认 32 个请求者
- **Entry 条目数**：默认 512 条保护规则
- **MD（Machine Domain）数量**：最大 64 个域
- **SRCMD 表大小**：最大 65536 条
- **SoC 地址宽度**：48 位（支持高达 256 TB 地址空间）
- **AXI 数据宽度**：256 位
- **AXI outstanding 数量**：8

#### 3.2.2 寄存器体系

IOPMP 定义了完整的寄存器映射，基地址为 `0x4010_0000`：

| 寄存器偏移 | 名称 | 功能 |
|-----------|------|------|
| 0x0000 | VERSION | 规范版本号 |
| 0x0004 | IMPLEMENTATION | 实现 ID |
| 0x0008 | HWCFG0 | 硬件配置 0（enable、格式、特性开关） |
| 0x000C | HWCFG1 | 硬件配置 1（RRID 数量、Entry 数量） |
| 0x0010 | HWCFG2 | 硬件配置 2（优先级 Entry、RRID 翻译） |
| 0x0060 | ERRCFG | 错误配置（中断使能、响应抑制、MSI） |
| 0x0064 | ERRINFO | 错误信息（违规类型、事务类型） |
| 0x0068 | ERRREQADDR | 错误请求地址低 32 位 |
| 0x006C | ERRREQADDRH | 错误请求地址高 32 位 |
| 0x0070 | ERRREQID | 错误请求者 ID 和 Entry 索引 |

此外还包含三张配置表：
- **MDCFG 表**（基地址 `0x4010_0800`）：机器域配置表，定义每个 MD 的 Entry 范围
- **SRCMD 表**（基地址 `0x4010_1000`）：源请求者到 MD 的映射表
- **Entry 表**（基地址 `0x4010_2000`）：地址范围和权限保护条目

#### 3.2.3 控制状态机

`Ctrl` 模块实现了一个 9 状态的有限状态机来处理每次内存访问检查：

```
sIdle -> sSrcmd -> sMdcfg -> sMdcfgPre -> sEntry -> sPriority -> sMatching -> sErr/sDone
```

- **sIdle**：等待请求，验证 RRID 合法性
- **sSrcmd**：查询 SRCMD 表获取 MD 位图
- **sMdcfg**：轮询 MDCFG 表遍历每个 MD
- **sMdcfgPre**：预读取前一个 MD 的 Entry 起始索引
- **sEntry**：初始化 Entry 索引
- **sPriority**：逐条匹配 Entry，支持 NA4 和 NAPOT 地址编码
- **sMatching**：验证读写权限（R/W/X/A）
- **sErr**：记录错误信息到寄存器
- **sDone**：返回检查结果

#### 3.2.4 NAPOT 地址解码

IOPMP 实现了 NAPOT（Naturally Aligned Power-of-Two）地址范围解码器。该解码器通过检测地址中的 trailing ones 位模式来确定 2 的幂次方地址范围：

```
NA4:  trailing_ones(all 0) + 2 = 2字节范围
NAPOT4: trailing_ones(maybe 0) + 3
range = 2^size
```

#### 3.2.5 AXI4 总线桥（IopmpBridge）

IopmpBridge 采用 Rocket Chip Diplomacy 框架，作为 AXI4 Slave 和 AXI4 Master 之间的透明代理：

- **AR/AW 通道**：通过 FIFO 缓存请求，经过 Checker 检查后决定是否放行或丢弃
- **R 通道**：使用 FSM 控制，当检测到违规时阻塞正常响应并返回 SLVERR
- **W/B 通道**：类似的 FSM 控制逻辑，确保写数据通道不会超越已确认的写请求通道
- **公平轮询**：AR 和 AW 通道使用 round-robin 策略，避免通道饥饿

该桥支持多实例（`numBridge` 参数），通过 `ReqArb` 和 `RspArb` 模块实现多桥共享单个 Checker 的仲裁。

#### 3.2.6 存储器实现

所有配置表（SRCMD、MDCFG、Entry）使用 **TrueDualPortSRAM** 实现，这是一种真双端口 SRAM 模块：

- A/B 两端口可独立读写
- 可配置读延迟（默认 1 周期）
- 写冲突检测：A 端口优先，同地址写入时 B 端口静默丢弃
- 使用 `SyncReadMem` 作为底层存储原语

### 3.3 与 SoC 的集成

ChiselIOPMP 通过 `IopmpLazyWrapper` 模块暴露 Diplomacy AXI4 Slave/Master 节点，可直接插入 Rocket Chip/Chipyard 的 SoC 互连拓扑中。其顶层还通过 APB Slave 接口接受 CPU 的配置访问，并提供中断输出信号。

---

## 4. XSCache 独立缓存子系统

### 4.1 概述

XSCache 是一个独立的、基于 CHI（Coherent Hub Interface）协议的缓存子系统，从 XiangShan 主项目中解耦出来，可独立编译、测试和部署。

**源文件位置**：`XSCache/src/`

**核心文档**：`XSCache/README.md`

```
XSCache 是基于 CoupledL2 (tl2chi) 和 OpenLLC 构建的 CHI-only 缓存子系统。
```

### 4.2 架构组成

XSCache 包含两大核心模块：

#### 4.2.1 CoupledL2（L2 Cache）

**源文件位置**：`XSCache/src/main/scala/coupledL2/`

CoupledL2 是 L2 缓存实现，使用 TileLink 到 CHI 协议转换：

- **CoupledL2.scala** — L2 缓存主模块，配置参数包括：
  - `sets`、`ways`：缓存组数和路数
  - `blockBytes`：缓存行大小（64 字节）
  - `beatBytes`：数据通路宽度
  - 支持 ECC 校验（Tag ECC 和 Data ECC）
  - 支持多 Bank 设计
  - 支持 prefetcher（BOP、TP、NL 等策略）

- **主要子模块**：
  - `Directory.scala` — Tag 目录存储
  - `DataStorage.scala` — 数据存储
  - `MainPipe.scala` — 主处理流水线
  - `MSHR.scala` / `MSHRCtl.scala` — Miss 状态持有器及控制器
  - `RequestArb.scala` — 请求仲裁
  - `Slice.scala` — 单 Slice 实现（支持多 Bank）
  - `MMIOBridge.scala` — MMIO 桥接器，将非缓存访问转为 CHI NoSnp 事务
  - `TXREQ/TXDAT/TXRSP/RXDAT/RXSNP/RXRSP` — CHI 协议各通道模块
  - `LinkMonitor.scala` — CHI 链路监控
  - `TopDownMonitor.scala` — 性能 Top-Down 监控

- **L2 参数**（`L2Param.scala`）：
  - 通过 `FPGAPlatform` 参数在 FPGA 和 ASIC 模式间切换
  - 支持私有 CLINT、MBIST 接口
  - 可配置 ECC 启用

#### 4.2.2 OpenLLC（Last Level Cache）

**源文件位置**：`XSCache/src/main/scala/openLLC/`

OpenLLC 是基于 CHI 协议的 LLC 实现：

- **OpenLLC.scala** — LLC 主模块：
  - 支持多 RN（Request Node）端口
  - SN（Slave Node）端口连接外部内存
  - MMIO Diverger/Merger：将 MMIO 请求分离和合并
  - 多 Bank Slice 设计，每个 Slice 独立处理
  - 链路监控（RN/SN LinkMonitor）

- **主要子模块**：
  - `Directory.scala` — LLC Tag 目录
  - `DataStorage.scala` — LLC 数据存储
  - `MainPipe.scala` — 主处理管道
  - `RefillUnit.scala` — 回填单元
  - `SnoopUnit.scala` — Snoop 过滤器
  - `RequestBuffer.scala` — 请求缓冲
  - `RequestArb.scala` — 请求仲裁
  - `MemUnit.scala` — 内存接口单元
  - `ResponseUnit.scala` — 响应单元
  - `DummyLLC.scala` — 简化版 LLC（用于测试）

### 4.3 OpenNCB 桥接器

**源文件位置**：`XSCache/src/main/scala/openLLC/utils/OpenNCB.scala`

OpenNCB（Network-on-Chip Bridge）是 CHI 到 AXI4 协议的桥接器，用于将 LLC 的 CHI 接口转换为标准的 AXI4 总线接口：

- 基于 `cc.xiangshan.openncb` 库中的 `NCB200` 模块
- 支持 CHI Issue B/C/E 版本
- 通过 Diplomacy `AXI4MasterNode` 连接到 SoC 总线
- 可配置 outstanding 深度

### 4.4 独立测试支持

XSCache 支持多种独立测试配置：

```bash
make test-top-chi          # CHI 测试顶层
make test-top-l2l3-openllc # L2+L3(OpenLLC) 测试顶层
make test-top-l2l3l2-openllc # L2+L3+L2(OpenLLC) 测试顶层
```

这使得 XSCache 可以脱离 XiangShan 主项目独立进行功能验证和 FPGA 原型验证。

---

## 5. FPGA DiffTest 验证框架

### 5.1 FPGA 主机程序

**源文件位置**：`difftest/src/test/csrc/fpga/`

FPGA 主机程序（`fpga_main.cpp`）是连接 FPGA 硬件与软件 DiffTest 引擎的桥梁。

#### 5.1.1 主程序流程

```
main() -> fpga_init() -> xdma_device->start() -> fpga_finish()
```

1. **解析参数**：通过 `parse_args` 获取配置
2. **初始化**：创建 XDMA 设备、初始化 RAM、加载 workload
3. **DiffTest 运行**：通过 XDMA DMA 通道持续读取 DUT 状态并与参考模型比较
4. **结果判定**：检测 GOODTRAP（测试通过）、EXCEED（超限）、FAIL（失败）

#### 5.1.2 XDMA DMA 通信

**源文件位置**：`difftest/src/test/csrc/fpga/xdma.h` 和 `xdma.cpp`

`FpgaXdma` 类封装了 FPGA 与主机之间的 DMA 通信：

- **设备文件**：
  - `/dev/xdma0_user` — XDMA 用户空间寄存器（用于控制，4KB mmap）
  - `/dev/xdma0_bypass` — XDMA bypass 空间（用于 DDR 加载，1MB mmap）
  - `/dev/xdma0_c2h_N` — Card-to-Host DMA 通道 N（读取 DUT 状态）
  - `/dev/xdma0_h2c_0` — Host-to-Card DMA 通道（可选）

- **DiffTest 数据包格式**：
  - 每个数据包（`DmaDiffPackge`）包含 1 字节 `packge_idx` 和 `CONFIG_DIFFTEST_BATCH_BYTELEN` 字节的 DiffTest 数据
  - 8 个数据包组成一个 `FpgaPackgeHead`（`DMA_PACKGE_NUM = 8`）
  - 数据包按 64 字节对齐（`DMA_PACKGE_ALIGNED`）

- **多线程内存池模式**（`USE_THREAD_MEMPOOL`）：
  - 每个 DMA 通道一个读取线程（`read_xdma_thread`）
  - 一个处理线程（`write_difftest_thread`）
  - 使用 `MemoryIdxPool` 实现生产者-消费者模式
  - 通过 `packge_idx` 检测数据包顺序，确保不丢包

- **DDR 加载**：支持通过 XDMA bypass 空间直接将 workload 写入 FPGA DDR，或通过外部命令（`FPGA_DDR_LOAD_CMD` 环境变量）

#### 5.1.3 Host IO 控制

```cpp
#define HOST_IO_RESET           0x0   // 复位控制
#define HOST_IO_DIFFTEST_ENABLE 0x4   // DiffTest 使能
```

通过 `fpga_io()` 方法，主机可通过 XDMA user 空间寄存器控制 FPGA 端的复位和 DiffTest 使能信号。

### 5.2 串口通信

**源文件位置**：`difftest/src/test/csrc/fpga/serial_port.h` 和 `serial_port.cpp`

`SerialPort` 类提供 UART 串口通信支持：

- 默认设备：`/dev/ttyUSB0`，波特率 115200
- 双线程架构：独立的读线程和写线程
- 读线程使用 `select()` 实现非阻塞 I/O
- 支持键盘输入到 FPGA 的交互式操作

### 5.3 FPGA 仿真支持

**源文件位置**：`difftest/src/test/csrc/fpga_sim/`

当 `FPGA_SIM` 宏定义生效时，XDMA 操作被替换为共享内存仿真：

- **xdma_sim.cpp/h**：基于 POSIX 共享内存的 XDMA 仿真层
  - 使用 `/xdma_sim_c2hN` 共享内存对象
  - 使用 pthread mutex/condvar 实现同步
  - `v_xdma_write()` — DPI-C 函数，Verilog 端调用写入仿真 XDMA

**Verilog 仿真模块**：`difftest/src/test/vsrc/fpga_sim/`

- `xdma_axi.v` — XDMA AXI Stream 仿真模块
  - 接收 512 位 `axi_tdata` 数据
  - 通过 DPI-C `v_xdma_write` 函数将数据写入共享内存
  - 模拟随机 ready 延迟（20000-30000 周期）

- `xdma_clock.v` — 时钟分频模块
  - 支持 `ASYNC_CLK_2N` 参数进行可配置分频
  - 在仿真中模拟 FPGA 的异步时钟域

### 5.4 Makefile 集成

`difftest/Makefile` 中的 FPGA 相关配置：

```makefile
ifeq ($(FPGA),1)
  include fpga.mk      # 包含 FPGA 构建规则
endif

ifeq ($(FPGA_SIM), 1)
  SIM_CXXFLAGS += -DFPGA_SIM    # 启用仿真模式
  SIM_VFLAGS   += +define+FPGA_SIM
  SIM_VSRC     += fpga_sim/*.v   # 添加仿真 Verilog 源文件
endif
```

---

## 6. VexiiRscv 角色与关系

### 6.1 概述

**源文件位置**：`VexiiRiscv/`

VexiiRiscv（Vex2Riscv）是 VexRiscv 的后继者，是一款基于 SpinalHDL 的可配置 RISC-V 处理器内核。

### 6.2 主要特性

根据 `VexiiRiscv/README.md`：

- 支持 RV32/64 I[M][A][F][D][C][S][U][B] 指令集
- 性能可达 5.24 CoreMark/MHz、2.50 Dhrystone/MHz
- 顺序执行（In-order），支持 early late-ALU
- 支持单发射/双发射（可非对称）
- 分支预测：BTB、GShare、RAS
- 可选 I$/D$ 缓存
- 可选 SV32/SV39 MMU
- 支持 Linux / Buildroot / Debian 运行
- 支持 AXI4、Wishbone、TileLink 总线
- 支持 Konata 流水线可视化
- 支持 RVLS 和 Spike 的 lock-step 仿真

### 6.3 与 XiangShan 的关系

VexiiRiscv 在 XiangShan 项目中作为**参考实现和对比验证工具**：

1. **性能对比基准**：VexiiRiscv 定位从 Cortex M0 到 Cortex A53 级别，与 XiangShan 的高性能乱序超标量设计形成互补参考
2. **FPGA 可部署性**：VexiiRiscv 专为 FPGA 实现优化，生成的 Verilog 可直接用于 Quartus 等工具
3. **FPGA 上运行操作系统**：已有四核 VexiiRiscv 在 FPGA 上运行 Debian 的演示
4. **验证基础设施**：VexiiRiscv 提供的 RVLS lock-step 仿真和 Spike 集成可为 XiangShan 的验证提供方法论参考

### 6.4 源码结构

- `VexiiRiscv/src/main/scala/vexiiriscv/Param.scala` — 处理器参数定义和配置类
- `VexiiRiscv/src/main/scala/vexiiriscv/Global.scala` — 全局参数
- `VexiiRiscv/src/main/scala/vexiiriscv/VexiiRiscv.scala` — 处理器顶层
- `VexiiRiscv/src/main/scala/vexiiriscv/Generate.scala` — 代码生成
- `VexiiRiscv/ext/SpinalHDL/` — SpinalHDL 框架依赖
- `VexiiRiscv/ext/NaxSoftware/` — 测试软件（baremetal、buildroot、Debian）
- `VexiiRiscv/build.sbt` / `build.mill` — 构建配置

---

## 7. 工具集成

### 7.1 readmemh 工具

**源文件位置**：`tools/readmemh/`

该工具集用于处理 `$readmemh` 格式的存储器初始化文件，是 FPGA 实现中的关键辅助工具：

#### 7.1.1 split-readmemh.c

将单个 readmemh 文件按 4 字节（32 位）为单位拆分为 4 个独立文件（`_0`、`_1`、`_2`、`_3`）。这用于支持 FPGA 中的多 Bank SRAM 初始化——每个 Bank 使用独立的初始化文件。

工作原理：
- 读取原始 readmemh 文件（`@` 地址标记 + 数据）
- 将数据按字节顺序轮流分配到 4 个输出文件
- 地址自动除以 4（以 32 位字为单位）
- 每 16 个字（64 字节，即一个缓存行）插入换行

#### 7.1.2 groupby-4byte.c

将 readmemh 文件中的 4 个独立字节合并为 32 位字格式：

- 读取逐字节数据（每行最多 16 个字节 = 4 个 32 位字）
- 使用 union 将 4 个小端字节组合为 32 位整数
- 输出合并后的 32 位字格式

#### 7.1.3 gen-treadle-readmemh.c

将二进制文件转换为 readmemh 格式：

- 预填充 1M 行的 `00` 数据
- 逐字节读取二进制文件，转换为两位十六进制格式
- 用于 Treadle 仿真器的存储器初始化

#### 7.1.4 构建方式

```makefile
# tools/readmemh/Makefile
build/verilator-readmemh: split-readmemh.c
    gcc -O2 -Wall -Werror -o $@ $<
```

---

## 8. 物理设计考量

### 8.1 时钟域管理

XiangShan 在物理实现中需要处理多个时钟域：

- **核心时钟**：处理器核心运行时钟，频率可调
- **AXI/TileLink 总线时钟**：通过 `beatBytes` 参数配置数据通路宽度
- **RTC（Real-Time Clock）时钟**：通过 `io.rtc_clock` 输入，用于 CLINT 定时器
- **FPGA 异步时钟**：在 `xdma_clock.v` 中，`ASYNC_CLK_2N` 参数控制时钟分频比，模拟 FPGA 的异步时钟域

**关键参数**：`src/main/scala/system/SoC.scala`

```scala
WFIClockGate: Boolean = false,    // WFI 时钟门控
EnablePowerDown: Boolean = false, // 功耗管理
```

### 8.2 时钟门控

时钟门控（Clock Gating）是降低动态功耗的关键技术：

```scala
EnableClockGate: Boolean = true  // Parameters.scala
def enableClockGate = p(EnableL2ClockGate)  // CoupledL2.scala
```

在 SoC 级别，`WFIClockGate` 支持处理器进入 WFI（Wait For Interrupt）状态时关闭时钟。

### 8.3 复位策略

XiangShan 的复位策略支持两种模式：

- **标准复位**：`ResetGen = false` 时，直接使用顶层复位信号
- **生成复位**：`ResetGen = true` 时，由 MemBlock 生成各子模块的独立复位

```scala
// XSCore.scala
if (debugOpts.ResetGen) {
  backend.reset := memBlock.io.reset_backend
  frontend.reset := backend.io.frontendReset
}
```

此外，还支持 DFT（Design For Test）复位链路：

```scala
memBlock.io.dft.zip(io.dft).foreach({ case (a, b) => a := b })
memBlock.io.dft_reset.zip(io.dft_reset).foreach({ case (a, b) => a := b })
frontend.io.dft.zip(memBlock.io.dft_frnt).foreach({ case (a, b) => a := b })
backend.io.dft.zip(memBlock.io.dft_bcknd).foreach({ case (a, b) => a := b })
```

### 8.4 SRAM 管理

IOPMP 中的 `TrueDualPortSRAM` 设计体现了物理实现中的存储器设计考量：

- **真双端口**：支持同时读写，适用于 Checker 的 A 端口配置读写和 B 端口运行时查表
- **可配置读延迟**：根据工艺和频率调整 `readLatency`
- **写冲突处理**：A 端口优先策略，避免亚稳态

在 XSCache 中：
- `mbist`（Memory Built-In Self-Test）支持通过 `hasMbist` 参数控制
- `sramCtl` 支持通过 `hasSramCtl` 参数控制
- SRAM 分割策略（`tagSRAMSplit`、`dataSRAMSplit`）适应不同 SRAM 原语限制

### 8.5 DFT（Design For Test）

SoC 顶层暴露了 DFT 信号用于测试：

```scala
// Top.scala
val io = IO(new Bundle {
  val clock = Input(Clock())
  val reset = Input(AsyncReset())
  val sram_config = Input(UInt(16.W))
  ...
  val debug_reset = Output(Bool())
})
```

DFT 选项包括：
- `DFTOptionsKey`：MBIST 和 SRAM 控制器配置
- `EnableMbist`：内存内建自测
- `EnableSramCtl`：SRAM 控制器

### 8.6 地址空间规划

- **SoC 地址宽度**：48 位（IOPMP 参数中的 `soc_addr_width = 48`）
- **IOPMP 寄存器基地址**：`0x4010_0000`
- **DDR 起始地址**：`0x8000_0000`（从 PMA 默认配置可见）
- **MMIO 区域**：`0x1000_0000`，大小 `0x1000_0000`
- **SimMemSize**：默认 8190 GB（`0x80000000000`），与 PMA 一致

---

## 9. ASIC 与 FPGA 双目标策略

### 9.1 条件编译机制

XiangShan 通过以下机制实现 ASIC/FPGA 双目标：

1. **Scala 参数**：`FPGAPlatform` 参数在 Chisel 生成阶段控制条件逻辑
2. **C++ 宏定义**：`FPGA_HOST`、`FPGA_SIM`、`USE_SERIAL_PORT` 等宏控制主机程序行为
3. **Verilog 宏定义**：`FPGA_SIM`、`ASYNC_CLK_2N` 等控制 Verilog 仿真行为
4. **Makefile 条件**：`FPGA=1`、`FPGA_SIM=1` 等变量控制构建流程

### 9.2 资源优化

FPGA 模式下的主要优化：

| 特性 | ASIC 模式 | FPGA 模式 |
|------|----------|----------|
| ChiselDB/Constantin | 可启用 | 禁用 |
| TL Logger | 可启用 | 禁用 |
| Bus PerfMonitor | 启用 | 禁用 |
| TopDown Monitor | 启用 | 禁用 |
| ReqSourceKey | 启用 | 省略 |
| 性能计数器 | 完整 | 基础 |
| DPI-C 接口 | 可用 | 可选仿真 |

### 9.3 IOPMP 在物理实现中的角色

ChiselIOPMP 在 SoC 物理实现中作为 I/O 安全保护的关键组件：

- 在 FPGA 原型中，IOPMP 保护外设寄存器不被非法访问
- 在 ASIC 流片中，IOPMP 是 RISC-V 物理内存保护架构的必要补充
- AXI4 总线桥设计使其可透明插入 SoC 互连拓扑
- APB 配置接口使 CPU 可通过标准外设总线配置保护规则

---

## 10. 关键源文件索引

### 10.1 ChiselIOPMP

| 文件路径 | 说明 |
|---------|------|
| `ChiselIOPMP/src/main/scala/Iopmp.scala` | 核心逻辑、参数定义、寄存器、状态机 |
| `ChiselIOPMP/src/main/scala/IopmpChecker.scala` | Checker 模块，连接寄存器和控制器 |
| `ChiselIOPMP/src/main/scala/IopmpBridge.scala` | AXI4 总线桥，Diplomacy 集成 |

### 10.2 XSCache

| 文件路径 | 说明 |
|---------|------|
| `XSCache/src/main/scala/coupledL2/CoupledL2.scala` | L2 Cache 顶层 |
| `XSCache/src/main/scala/coupledL2/MMIOBridge.scala` | MMIO 桥接器 |
| `XSCache/src/main/scala/coupledL2/L2Param.scala` | L2 参数定义 |
| `XSCache/src/main/scala/openLLC/OpenLLC.scala` | LLC 顶层 |
| `XSCache/src/main/scala/openLLC/utils/OpenNCB.scala` | CHI-AXI 桥接器 |

### 10.3 FPGA DiffTest

| 文件路径 | 说明 |
|---------|------|
| `difftest/src/test/csrc/fpga/fpga_main.cpp` | FPGA 主机程序入口 |
| `difftest/src/test/csrc/fpga/xdma.h` / `xdma.cpp` | XDMA DMA 通信封装 |
| `difftest/src/test/csrc/fpga/serial_port.h` / `serial_port.cpp` | UART 串口通信 |
| `difftest/src/test/csrc/fpga_sim/xdma_sim.h` / `xdma_sim.cpp` | XDMA 仿真层 |
| `difftest/src/test/vsrc/fpga_sim/xdma_axi.v` | XDMA AXI Stream 仿真模块 |
| `difftest/src/test/vsrc/fpga_sim/xdma_clock.v` | 时钟分频仿真模块 |
| `difftest/fpga.mk` | FPGA 构建规则 |

### 10.4 顶层配置

| 文件路径 | 说明 |
|---------|------|
| `src/main/scala/xiangshan/Parameters.scala` | FPGAPlatform 等核心参数定义 |
| `src/main/scala/top/Configs.scala` | L2/L3 配置与 FPGA 条件逻辑 |
| `src/main/scala/top/Top.scala` | SoC 顶层，FPGA 平台检测 |
| `src/main/scala/system/SoC.scala` | SoC 级别 FPGA 适配 |
| `src/main/scala/xiangshan/L2Top.scala` | L2 顶层 FPGA 参数传递 |

### 10.5 工具与 VexiiRiscv

| 文件路径 | 说明 |
|---------|------|
| `tools/readmemh/split-readmemh.c` | readmemh 文件拆分工具 |
| `tools/readmemh/groupby-4byte.c` | readmemh 字节合并工具 |
| `tools/readmemh/gen-treadle-readmemh.c` | 二进制转 readmemh 工具 |
| `VexiiRiscv/README.md` | VexiiRiscv 项目说明 |
| `VexiiRiscv/src/main/scala/vexiiriscv/Param.scala` | VexiiRiscv 参数配置 |

---

## 11. 总结

XiangShan 的 FPGA 与物理实现体系体现了成熟的 SoC 设计方法论：

1. **模块化与可复用**：ChiselIOPMP 和 XSCache 作为独立子项目，可独立编译验证，体现了模块化设计原则
2. **双平台适配**：通过 `FPGAPlatform` 参数和条件编译，同一代码库支持 ASIC 流片和 FPGA 原型验证
3. **完整的验证链**：XDMA DMA 通信 + DiffTest + 共享内存仿真构成了完整的 FPGA 验证闭环
4. **物理设计考虑**：时钟门控、复位策略、DFT 支持、SRAM 管理等物理设计要素均有完善支持
5. **安全保护**：ChiselIOPMP 提供了 RISC-V 规范要求的 I/O 物理内存保护能力
6. **工具链支持**：readmemh 工具集为 FPGA SRAM 初始化提供了必要的数据格式转换

这些要素共同确保了 XiangShan 处理器能够高效地从 Chisel RTL 验证迁移到 FPGA 原型验证，并最终走向 ASIC 物理实现。

---

## 附录A. ChiselIOPMP 状态机详细分析

### A.1 每个状态的详细行为

ChiselIOPMP 的核心控制逻辑位于 `Iopmp.scala` 中的 `Ctrl` 模块，其状态机是理解 IOPMP 检查流程的关键。

**sIdle 状态**：
在空闲状态下，`Ctrl` 模块等待来自 AXI Bridge 的检查请求。当检测到 `io.req.fire` 且 IOPMP 使能位为 1 时，首先验证 RRID 的合法性。如果 RRID 小于配置的 `rrid_num`，则转入 sSrcmd 状态开始正常的地址匹配流程；否则直接进入 sErr 状态，记录错误类型为 `0x6`（unknown RRID）。若 IOPMP 未使能，则直接跳到 sDone 状态，不进行任何检查，实现 bypass 功能。

**sSrcmd 状态**：
在该状态下，`Ctrl` 模块通过 `io.srcmd` 接口查询 SRCMD 表。SRCMD 表将每个 RRID 映射到一组 MD（Machine Domain）位图。查询结果 `md` 是一个位宽为 31 位的掩码，其中每个为 1 的位表示对应的 MD 需要被检查。该状态使用 `srcmd_s_en_d` 延迟信号来检测 SRAM 读取完成。

**sMdcfg 状态**：
在此状态中，模块遍历 `md` 位图中所有为 1 的 MD 位。通过 `PriorityEncoder` 找到最低优先级的待检查 MD，然后查询 MDCFG 表获取该 MD 对应的 Entry 起始索引 `md_t`。当所有 MD 位都已清除（`md_hasTask` 为 false）且未找到任何匹配规则时，进入 sErr 状态，记录错误类型为 `0x5`（not hit any rule）。

**sMdcfgPre 状态**：
该状态是一个预处理状态，用于读取前一个 MD 的 Entry 结束索引 `md_t_pre`。这样可以在 sEntry 状态中确定 Entry 遍历的起始位置。如果当前 MD 是第一个 MD（索引为 0），则 `md_t_pre` 被设为 0。

**sEntry 状态**：
初始化 Entry 索引 `j_indx = md_t_pre`，准备开始遍历当前 MD 范围内的所有 Entry 条目。

**sPriority 状态**：
这是地址匹配的核心状态。模块逐个读取 Entry 表中的条目，使用 NAPOT 解码器计算每个 Entry 的地址范围，然后与请求的物理地址进行比较。匹配结果分为三种：
- **Full Match**（完全匹配）：请求地址范围完全在 Entry 地址范围内，`pri_hit = true`
- **Partial Match**（部分匹配）：请求地址范围与 Entry 地址范围有交集但不完全包含，且 Entry 索引小于 `prio_entry` 时 `pri_part_hit = true`
- **No Match**（不匹配）：请求地址范围与 Entry 地址范围无交集

如果当前 `j_indx` 达到了 `md_t`（当前 MD 的 Entry 范围上限），则返回 sMdcfg 状态继续检查下一个 MD。

**sMatching 状态**：
在确认地址匹配后，验证请求的访问权限。如果请求是写操作但 Entry 不允许写（`entry_attribute.w == 0`）或全局配置了 `no_w`，则记录错误类型 `0x2`（illegal write access/AMO）；如果请求是读操作但 Entry 不允许读（`entry_attribute.r == 0`），则记录错误类型 `0x1`（illegal read access）。权限检查通过则进入 sDone 状态。

**sErr 状态**：
记录错误信息到寄存器，包括：错误类型（etype）、事务类型（ttype）、错误地址（32 位低地址 + 32 位高地址）、请求者 RRID 和触发违规的 Entry 索引。这些信息仅在首次违规时捕获（通过 `io.reg.o_reg_errinfo_v` 判断）。

**sDone 状态**：
返回检查结果（`cf_r` 和 `cf_w` 标志），等待响应被消费后返回 sIdle 状态。`cf_r = 1` 表示读违规，`cf_w = 1` 表示写违规。

### A.2 中断生成逻辑

中断信号 `io.int` 由以下条件组合产生：

```
int = errinfo_v AND errcfg_ie AND (
    (ttype == READ  AND NOT entry_attribute.sire) OR
    (ttype == WRITE AND NOT entry_attribute.siwe)
)
```

即：存在未清除的错误标志、中断使能开启、且该 Entry 未配置为抑制中断（sire/siwe 位）。

### A.3 Stall 与 Flush 机制

当 APB 正在进行寄存器写操作时（`io.regcfg.v && io.regcfg.rw`），会产生 stall 信号，阻止 Checker 处理新的请求，避免配置和检查之间的竞态条件。当 stall 信号下降沿时（`EdgeDetect.falling(io.stall)`），产生 flush 信号。

---

## 附录B. XSCache CoupledL2 深入分析

### B.1 多 Bank 架构

CoupledL2 采用多 Bank 设计来提高缓存访问带宽。Bank 数量通过 `L2NBanks` 参数配置，每个 Bank 是一个独立的 `Slice` 实例。Bank 选择基于物理地址的低位：

```scala
def bankBits = log2Ceil(cacheParams.banks)
def bankOffset = offsetBits  // 64字节缓存行
```

### B.2 ECC 校验

XSCache 支持两级 ECC 校验：

1. **Tag ECC**：对 Tag 数据进行 ECC 编码，保护缓存标签的完整性
2. **Data ECC**：对缓存行数据进行 ECC 编码，保护缓存数据的完整性

Tag 的 ECC 宽度为 31 位（对于 1MB L2 配置），Data ECC 的 bank 分割为 4 路。SRAM 分割在 ECC 编码后进行（`tagSRAMSplit`、`dataSRAMSplit`），以满足底层 SRAM 原语的宽度限制。

### B.3 预取策略

CoupledL2 支持多种预取策略的组合：

- **BOP（Best Offset Prefetcher）**：基于偏移量匹配的预取器
- **TP（Temporal Prefetcher）**：基于时间序列模式的预取器
- **NL（Next Line）**：简单的下一行预取
- **PrefetchReceiverParams**：接收来自 L1 的预取提示

### B.4 Snoop Filter

OpenLLC 中集成了 Snoop Filter 来跟踪各 RN 的缓存行持有状态，避免不必要的 Snoop 事务。Snoop Filter 的容量根据客户端缓存参数计算：

```scala
val clientSets * clientWays * clientParam.blockBytes
```

---

## 附录C. FPGA DiffTest 数据通路详细分析

### C.1 DMA 数据包格式

FPGA DiffTest 使用批量传输模式来提高 DMA 效率：

```
+------------------+
| packge_idx (1B)  |  <- 包索引（主机端校验用）
+------------------+
| diff_packge      |  <- CONFIG_DIFFTEST_BATCH_BYTELEN 字节的 DiffTest 数据
| [...]            |
+------------------+
| padding          |  <- 64字节对齐填充（如果有）
+------------------+
```

8 个这样的数据包组成一个 `FpgaPackgeHead`，总大小按 64 字节对齐。这种批量传输设计减少了 DMA 中断次数，提高了传输效率。

### C.2 多线程内存池架构

`USE_THREAD_MEMPOOL` 模式下的线程架构：

```
DMA Channel 0 --> read_xdma_thread(0) --+
                                         |---> xdma_mempool ---> write_difftest_thread
DMA Channel 1 --> read_xdma_thread(1) --+
    ...
DMA Channel N --> read_xdma_thread(N) --+
```

- **read_xdma_thread**：每个通道一个线程，负责从 XDMA 设备读取原始数据到内存池
- **write_difftest_thread**：单个处理线程，从内存池中按序取出数据包，通过 `v_difftest_Batch` DPI-C 函数传递给 DiffTest 引擎
- **MemoryIdxPool**：基于索引的环形缓冲区，通过 `packge_idx` 实现无锁有序传递

### C.3 共享内存仿真机制

在 `FPGA_SIM` 模式下，`xdma_sim.cpp` 使用 POSIX 共享内存（`/xdma_sim_c2hN`）模拟 DMA 数据传输：

- **同步机制**：使用 PTHREAD_PROCESS_SHARED 属性的 mutex 和 condition variable，支持跨进程同步
- **读端（Host）**：`read()` 方法设置 `read_waiting = true`，然后等待写端填充足够数据后通过 `pthread_cond_signal` 唤醒
- **写端（Verilog）**：`write()` 方法通过 DPI-C `v_xdma_write` 被调用，模拟 AXI Stream 的 `tdata` 和 `tlast` 信号，当数据写满时唤醒读端

### C.4 XDMA AXI Stream 仿真时序

`xdma_axi.v` 模拟了 XDMA 硬件的行为：

1. 检测 `axi_tvalid & axi_tready` 握手成功
2. 通过 DPI-C 将 512 位数据写入共享内存
3. 在 `tlast` 有效时启动随机延迟计数器（20000-30000 周期）
4. 延迟期间 `axi_tready` 为 0，模拟硬件背压

`xdma_clock.v` 提供可配置的时钟分频，用于模拟核心时钟与 DMA 时钟之间的异步关系。

---

## 附录D. 物理实现中的安全考量

### D.1 IOPMP 的安全角色

在 RISC-V 架构中，IOPMP 是 I/O 设备访问安全的关键防线。与 CPU 端的 PMP（Physical Memory Protection）不同，IOPMP 保护的是 DMA 等非 CPU 主体发起的内存访问事务。在 XiangShan 的物理实现中：

1. **DMA 保护**：防止外设 DMA 未经授权访问受保护的内存区域
2. **多主设备隔离**：通过 RRID 机制区分不同的总线主设备
3. **细粒度权限控制**：每个 Entry 可独立配置 R/W/X 权限
4. **NAPOT 地址范围**：支持 2 的幂次方对齐的地址范围保护，减少 Entry 数量需求
5. **优先级匹配**：支持按 Entry 优先级进行匹配，允许重叠的保护规则
6. **错误处理**：完善的错误记录和中断通知机制，支持调试和安全响应

### D.2 DFT 与安全启动

物理实现中的 DFT 接口需要在安全启动后被禁用，以防止通过测试接口进行攻击。XiangShan 通过 `dft` 和 `dft_reset` 信号链路实现各子模块的 DFT 控制，这些信号在正常运行时应被保持在安全状态。

---

## 附录E. 参考与扩展阅读

### E.1 相关项目

- **RISC-V IOPMP 规范**：ChiselIOPMP 基于 RISC-V IOPMP 草案规范实现
- **Rocket Chip**：ChiselIOPMP 使用 Rocket Chip 的 Diplomacy 框架进行总线集成
- **CHI 协议**：XSCache 的核心通信协议，由 ARM 定义的 Coherent Hub Interface
- **Xilinx XDMA**：FPGA DMA 通信的核心组件，用于 PCIe 数据传输
- **SpinalHDL/VexiiRscv**：基于 SpinalHDL 的可配置 RISC-V 处理器框架

### E.2 构建与使用

FPGA 实现的完整构建流程：

```bash
# 1. 生成 Verilog（FPGA 模式）
make verilog EMU_TOP=XSNoCDiffTop CONFIG=FPGAConfig

# 2. 构建 FPGA 主机程序
cd difftest
make fpga-build DMA_CHANNELS=2 USE_THREAD_MEMPOOL=1

# 3. 运行 DiffTest
./build/fpga-host -i <workload_image>

# 4. 可选：FPGA 仿真模式
make fpga-build FPGA_SIM=1
```

### E.3 配置建议

对于 FPGA 原型验证，推荐以下配置组合：

- `FPGAPlatform = true`：启用 FPGA 优化
- `DMA_CHANNELS = 2`：双通道 DMA 提高传输带宽
- `USE_THREAD_MEMPOOL = 1`：多线程内存池提升主机端性能
- `USE_SERIAL_PORT = 1`：启用串口用于交互式调试
- `FPGA_SIM = 1`：在无硬件时使用仿真模式进行预验证

这些配置的组合使得 XiangShan 的 FPGA 验证既可以在真实硬件上运行，也可以在纯软件仿真环境中进行预验证，大大提高了开发效率。
