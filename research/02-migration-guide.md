# 从 XiangShan 迁移 SimPoint 评估框架到自定义 Verilog CPU

> 本文档指导如何将 XiangShan 的 SPEC2006 评估框架迁移到你自己的 Verilog CPU 设计上。

---

## 目录

1. [迁移概述](#1-迁移概述)
2. [架构对比分析](#2-架构对比分析)
3. [需要适配的核心组件](#3-需要适配的核心组件)
4. [详细迁移步骤](#4-详细迁移步骤)
5. [关键代码修改点](#5-关键代码修改点)
6. [最小可行方案 (MVP)](#6-最小可行方案-mvp)
7. [常见问题与解决方案](#7-常见问题与解决方案)
8. [参考资源](#8-参考资源)

---

## 1. 迁移概述

### 1.1 迁移目标

将 XiangShan 的以下能力迁移到你的 CPU：
1. **Verilator 仿真**：将 RTL 编译为快速仿真器
2. **Checkpoint 支持**：从内存快照恢复执行
3. **性能计数器**：提取微架构性能数据
4. **SimPoint 评估**：加权计算 SPEC2006 分数

### 1.2 迁移难度评估

| 组件 | 难度 | 工作量 | 说明 |
|------|------|--------|------|
| Verilator 编译 | ⭐⭐ | 1-2 天 | 主要是 Makefile 适配 |
| DiffTest 接口 | ⭐⭐⭐⭐ | 1-2 周 | 需要理解接口规范 |
| Checkpoint 恢复 | ⭐⭐⭐ | 3-5 天 | 需要实现状态保存 |
| 性能计数器 | ⭐⭐ | 2-3 天 | 在 RTL 中添加计数器 |
| 分数计算脚本 | ⭐ | 1 天 | 修改正则表达式 |

**总计**：2-4 周（取决于 CPU 复杂度）

### 1.3 前置条件

- [ ] 你的 CPU 是 RISC-V 架构（或能运行 RISC-V 二进制）
- [ ] 有可工作的 RTL 代码（Verilog/SystemVerilog）
- [ ] 能通过 Verilator 编译
- [ ] 有基本的仿真测试环境

---

## 2. 架构对比分析

### 2.1 XiangShan 架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           XiangShan 架构                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        Chisel 源码                                   │   │
│  │  src/main/scala/xiangshan/                                          │   │
│  │    ├── Backend/     (后端：执行、提交)                               │   │
│  │    ├── Frontend/    (前端：取指、译码)                               │   │
│  │    ├── MemBlock/    (内存：Load/Store)                              │   │
│  │    └── ...                                                          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │                                        │
│                                    ▼ (mill + firtool)                       │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     SystemVerilog 输出                               │   │
│  │  build/rtl/                                                         │   │
│  │    ├── SimTop.sv          (仿真顶层)                                │   │
│  │    ├── XSTop.sv           (设计顶层)                                │   │
│  │    └── [子模块].sv                                                  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │                                        │
│                                    ▼ (Verilator)                            │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                      DiffTest 框架                                   │   │
│  │  difftest/                                                          │   │
│  │    ├── src/test/csrc/     (C++ 仿真代码)                            │   │
│  │    │   ├── emu/emu.cpp    (Emulator 主逻辑)                         │   │
│  │    │   ├── common/ram.cpp (内存模型)                                │   │
│  │    │   └── difftest/      (差分测试)                                │   │
│  │    └── src/test/vsrc/     (Verilog 接口)                            │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 你的 CPU 架构（目标）

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        你的 CPU 架构（待适配）                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        Verilog 源码                                  │   │
│  │  rtl/                                                               │   │
│  │    ├── cpu_top.v          (CPU 顶层)                                │   │
│  │    ├── core.v             (核心模块)                                │   │
│  │    ├── alu.v              (ALU)                                     │   │
│  │    ├── regfile.v          (寄存器文件)                              │   │
│  │    └── ...                                                          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │                                        │
│                                    ▼ (需要适配)                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                     仿真顶层 (sim_top.v)                             │   │
│  │    - 实例化你的 CPU                                                 │   │
│  │    - 添加 DiffTest 接口                                             │   │
│  │    - 添加性能计数器                                                 │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │                                        │
│                                    ▼ (Verilator)                            │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                      DiffTest 框架                                   │   │
│  │    - 复用 XiangShan 的 DiffTest 框架                                │   │
│  │    - 修改配置适配你的 CPU                                           │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.3 关键差异

| 组件 | XiangShan | 你的 CPU | 适配方案 |
|------|-----------|----------|---------|
| **RTL 语言** | Chisel → SystemVerilog | 原生 Verilog | 无需转换，直接使用 |
| **顶层模块** | `SimTop` (自动生成) | 需要手动创建 | 创建 `sim_top.v` |
| **DiffTest 接口** | Chisel 宏自动生成 | 需要手动实现 | 参考 XiangShan 实现 |
| **性能计数器** | Chisel 注解自动插入 | 需要手动添加 | 在 RTL 中添加 `$fwrite` |
| **内存接口** | TileLink | 可能是 AXI | 需要适配或桥接 |
| **启动地址** | `0x10000000`（复位向量） / `0x80000000`（内存基地址） | 可能不同 | 需要确认对齐 |

---

## 3. 需要适配的核心组件

### 3.1 组件清单

```
需要创建/修改的文件：

your-cpu/
├── sim/
│   └── sim_top.v              # [新建] 仿真顶层
├── difftest/                   # [新建] DiffTest 接口
│   ├── DifftestInstrCommit.v   # 指令提交接口
│   ├── DifftestTrapEvent.v     # 陷阱事件接口
│   └── ...
├── scripts/
│   └── configs.py              # [修改] 性能计数器配置
├── Makefile                    # [修改] 构建脚本
└── verilator.mk                # [新建] Verilator 配置
```

### 3.2 DiffTest 接口详解

DiffTest (Differential Testing) 是 XiangShan 的验证框架，通过对比 RTL 仿真和参考模型（NEMU）的执行结果来验证正确性。

**核心接口**（`difftest/src/test/vsrc/common/`）：

#### 3.2.1 指令提交接口

```verilog
// DifftestInstrCommit.v
module DifftestInstrCommit (
    input        clock,
    input        enable,
    input [63:0] io_coreid,      // 核心 ID
    input [63:0] io_index,       // 提交槽索引
    input        io_valid,       // 是否有效
    input [63:0] io_pc,          // PC 地址
    input [63:0] io_instr,       // 指令内容
    input        io_skip,        // 跳过 DiffTest
    input        io_isRVC,       // 是否压缩指令
    input        io_scFailed,    // SC 失败
    input        iowen,          // 写使能
    input [63:0] iwdata,         // 写数据
    input [7:0]  istr            // 写寄存器索引
);
```

**你的 CPU 需要提供的信号**：
- `commit_valid`: 指令提交有效
- `commit_pc`: 提交指令的 PC
- `commit_instr`: 指令内容
- `commit_wen`: 寄存器写使能
- `commit_wdata`: 写数据
- `commit_wdest`: 写目标寄存器

#### 3.2.2 陷阱事件接口

```verilog
// DifftestTrapEvent.v
module DifftestTrapEvent (
    input        clock,
    input        enable,
    input [63:0] io_coreid,
    input        io_valid,       // 陷阱有效
    input [63:0] io_pc,          // 陷阱 PC
    input [63:0] io_cause,       // 陷阱原因
    input [63:0] io_hasWen,      // 是否写寄存器
    input [63:0] io_wdata        // 写数据
);
```

**你的 CPU 需要提供的信号**：
- `trap_valid`: 陷阱发生
- `trap_pc`: 陷阱 PC
- `trap_cause`: 陷阱原因（ecall, ebreak 等）

#### 3.2.3 内存访问接口

```verilog
// DifftestLoadEvent.v / DifftestStoreEvent.v
module DifftestLoadEvent (
    input        clock,
    input        enable,
    input [63:0] io_coreid,
    input [63:0] io_index,
    input        io_valid,
    input [63:0] io_paddr,       // 物理地址
    input [63:0] io_vaddr,       // 虚拟地址
    input [63:0] io_data,        // 数据
    input [7:0]  io_mask,        // 字节掩码
    input        io_isLoad       // 是否 Load
);
```

### 3.3 性能计数器

XiangShan 的性能计数器通过 `$fwrite` 输出到 stderr：

```verilog
// XiangShan 示例（Chisel 生成的 SystemVerilog）
always @(posedge clock) begin
  if (reset) begin
    // ...
  end else begin
    if (io_perfCtrl_clean) begin
      // 清零计数器
    end
    
    // 性能计数器输出
    if (dispatch_valid) begin
      $fwrite(32'h80000002, 
        "[PERF][time=%0d].core0.backend.ctrlBlock.dispatch: NoStall, %0d\n",
        cycle_count, no_stall_count);
      $fwrite(32'h80000002,
        "[PERF][time=%0d].core0.backend.ctrlBlock.dispatch: ICacheMissBubble, %0d\n",
        cycle_count, icache_miss_count);
      // ... 更多计数器
    end
  end
end
```

---

## 4. 详细迁移步骤

### 4.1 步骤 1：环境准备

#### 4.1.1 安装依赖

```bash
# 1. 安装 Verilator 5.x
sudo apt-get install verilator
# 或从源码编译
git clone https://github.com/verilator/verilator.git
cd verilator && git checkout v5.024
autoconf && ./configure && make -j$(nproc) && sudo make install

# 2. 安装 DRAMsim3（可选，用于精确内存仿真）
git clone https://github.com/umd-memsys/DRAMsim3.git
cd DRAMsim3 && mkdir build && cd build && cmake .. && make -j$(nproc)

# 3. 克隆 XiangShan（作为参考）
git clone https://github.com/OpenXiangShan/XiangShan.git
cd XiangShan && git submodule update --init

# 4. 准备 NEMU（用于 DiffTest）
git clone https://github.com/OpenXiangShan/NEMU.git
cd NEMU && git submodule update --init
make riscv64-xs-defconfig && make -j$(nproc)
```

#### 4.1.2 目录结构

```
your-cpu/
├── rtl/                        # 你的 RTL 代码
│   ├── cpu_top.v
│   ├── core.v
│   └── ...
├── sim/                        # 仿真相关
│   ├── sim_top.v               # 仿真顶层（新建）
│   └── difftest/               # DiffTest 接口（新建）
│       ├── DifftestInstrCommit.v
│       ├── DifftestTrapEvent.v
│       └── ...
├── scripts/                    # 脚本
│   └── configs.py              # 性能计数器配置（修改）
├── difftest/                   # DiffTest 框架（复用）
│   └── ... (从 XiangShan 复制)
├── Makefile                    # 主 Makefile（修改）
├── verilator.mk                # Verilator 配置（新建）
└── ready-to-run/               # 运行时文件
    └── riscv64-nemu-interpreter-so  # NEMU 共享库
```

### 4.2 步骤 2：创建仿真顶层

#### 4.2.1 基本框架

**文件**：`sim/sim_top.v`

```verilog
`timescale 1ns / 1ps

module SimTop (
    input  wire        clock,
    input  wire        reset,
    
    // 内存接口（AXI4 示例）
    output wire        io_master_awvalid,
    input  wire        io_master_awready,
    output wire [31:0] io_master_awaddr,
    // ... 其他 AXI 信号
    
    // UART 接口
    output wire        io_uart_tx,
    input  wire        io_uart_rx
);

    // ============================================
    // 1. 实例化你的 CPU
    // ============================================
    wire [63:0] commit_pc;
    wire        commit_valid;
    wire [63:0] commit_instr;
    wire        commit_wen;
    wire [63:0] commit_wdata;
    wire [4:0]  commit_wdest;
    
    wire        trap_valid;
    wire [63:0] trap_pc;
    wire [63:0] trap_cause;
    
    your_cpu_top cpu (
        .clock(clock),
        .reset(reset),
        
        // 内存接口
        // ...
        
        // DiffTest 信号
        .commit_pc(commit_pc),
        .commit_valid(commit_valid),
        .commit_instr(commit_instr),
        .commit_wen(commit_wen),
        .commit_wdata(commit_wdata),
        .commit_wdest(commit_wdest),
        
        .trap_valid(trap_valid),
        .trap_pc(trap_pc),
        .trap_cause(trap_cause)
    );

    // ============================================
    // 2. DiffTest 接口实例化
    // ============================================
    
    // 指令提交接口
    DifftestInstrCommit difftest_commit (
        .clock(clock),
        .enable(commit_valid),
        .io_coreid(64'd0),
        .io_index(64'd0),
        .io_valid(commit_valid),
        .io_pc(commit_pc),
        .io_instr(commit_instr),
        .io_skip(1'b0),
        .io_isRVC(1'b0),
        .io_scFailed(1'b0),
        .iowen(commit_wen),
        .iwdata(commit_wdata),
        .istr({3'b000, commit_wdest})
    );
    
    // 陷阱事件接口
    DifftestTrapEvent difftest_trap (
        .clock(clock),
        .enable(trap_valid),
        .io_coreid(64'd0),
        .io_valid(trap_valid),
        .io_pc(trap_pc),
        .io_cause(trap_cause),
        .io_hasWen(64'd0),
        .io_wdata(64'd0)
    );

    // ============================================
    // 3. 性能计数器
    // ============================================
    
    reg [63:0] cycle_count;
    reg [63:0] commit_instr_count;
    reg [63:0] no_stall_count;
    reg [63:0] icache_miss_count;
    // ... 更多计数器
    
    always @(posedge clock) begin
        if (reset) begin
            cycle_count <= 64'd0;
            commit_instr_count <= 64'd0;
            // ...
        end else begin
            cycle_count <= cycle_count + 1;
            
            if (commit_valid) begin
                commit_instr_count <= commit_instr_count + 1;
            end
            
            // 输出性能计数器（每 1000000 周期输出一次）
            if (cycle_count % 1000000 == 0) begin
                $fwrite(32'h80000002,
                    "[PERF][time=%0d].core0.backend.ctrlBlock.rob: commitInstr, %0d\n",
                    cycle_count, commit_instr_count);
                $fwrite(32'h80000002,
                    "[PERF][time=%0d].core0.backend.ctrlBlock.rob: clock_cycle, %0d\n",
                    cycle_count, cycle_count);
                // ... 更多计数器输出
            end
        end
    end

endmodule
```

#### 4.2.2 关键信号映射

你需要从你的 CPU 中提取以下信号：

| 信号 | 说明 | 获取方式 |
|------|------|---------|
| `commit_valid` | 指令提交有效 | 从提交阶段获取 |
| `commit_pc` | 提交指令的 PC | 从提交阶段获取 |
| `commit_instr` | 指令内容 | 从提交阶段获取 |
| `commit_wen` | 寄存器写使能 | 从写回阶段获取 |
| `commit_wdata` | 写数据 | 从写回阶段获取 |
| `commit_wdest` | 写目标寄存器 | 从写回阶段获取 |
| `trap_valid` | 陷阱发生 | 从 CSR 模块获取 |
| `trap_pc` | 陷阱 PC | 从 CSR 模块获取 |
| `trap_cause` | 陷阱原因 | 从 CSR 模块获取 |

### 4.3 步骤 3：配置 Verilator 编译

#### 4.3.1 创建 Verilator 配置

**文件**：`verilator.mk`

```makefile
# 顶层模块名
EMU_TOP = SimTop

# RTL 目录
RTL_DIR = $(abspath ./rtl)
SIM_DIR = $(abspath ./sim)

# 构建目录
BUILD_DIR = ./build
VERILATOR_BUILD_DIR = $(BUILD_DIR)/verilator-compile
VERILATOR_TARGET = $(VERILATOR_BUILD_DIR)/emu

# RTL 文件列表
RTL_FILES = $(shell find $(RTL_DIR) -name "*.v" -o -name "*.sv")
SIM_FILES = $(shell find $(SIM_DIR) -name "*.v" -o -name "*.sv")

# DiffTest 文件
DIFFTEST_DIR = $(abspath ./difftest)
DIFFTEST_CSRC_DIR = $(DIFFTEST_DIR)/src/test/csrc
DIFFTEST_VSRC_DIR = $(DIFFTEST_DIR)/src/test/vsrc/common

# C++ 文件
SIM_CSRC_DIR = $(DIFFTEST_CSRC_DIR)/common
EMU_CSRC_DIR = $(DIFFTEST_CSRC_DIR)/emu
SIM_CXXFILES = $(shell find $(SIM_CSRC_DIR) -name "*.cpp")
SIM_CXXFILES += $(shell find $(EMU_CSRC_DIR) -name "*.cpp")
SIM_CXXFILES += $(shell find $(DIFFTEST_CSRC_DIR)/difftest -name "*.cpp")

# Verilog 文件
SIM_VSRC = $(shell find $(DIFFTEST_VSRC_DIR) -name "*.v" -o -name "*.sv")

# 编译选项
SIM_CXXFLAGS = -I$(SIM_CSRC_DIR) -I$(EMU_CSRC_DIR) -I$(DIFFTEST_CSRC_DIR)/difftest
SIM_CXXFLAGS += -DNOOP_HOME=\"$(abspath .)\"
SIM_CXXFLAGS += -DNUM_CORES=1
SIM_LDFLAGS = -lz -lzstd

# DRAMsim3（可选）
ifdef DRAMSIM3_HOME
SIM_CXXFLAGS += -I$(DRAMSIM3_HOME)/src -DWITH_DRAMSIM3
SIM_CXXFLAGS += -DDRAMSIM3_CONFIG=\"$(DRAMSIM3_HOME)/configs/XiangShan.ini\"
SIM_CXXFLAGS += -DDRAMSIM3_OUTDIR=\"$(BUILD_DIR)\"
SIM_LDFLAGS += -L$(DRAMSIM3_HOME)/build -ldramsim3
endif

# Verilator 选项
VERILATOR_FLAGS = \
    --exe -O3 \
    --cc --top-module $(EMU_TOP) \
    +define+VERILATOR=1 \
    +define+RANDOMIZE_REG_INIT \
    +define+RANDOMIZE_MEM_INIT \
    +define+RANDOMIZE_GARBAGE_ASSIGN \
    +define+RANDOMIZE_DELAY=0 \
    --max-num-width 150000 \
    --assert --x-assign unique \
    --output-split 30000 \
    --output-split-cfuncs 30000 \
    -I$(RTL_DIR) \
    -I$(SIM_DIR) \
    -CFLAGS "$(SIM_CXXFLAGS)" \
    -LDFLAGS "$(SIM_LDFLAGS)" \
    -o $(VERILATOR_TARGET)

# 目标
.PHONY: all emu clean

all: emu

# 生成 Verilator Makefile
$(VERILATOR_BUILD_DIR)/V$(EMU_TOP).mk: $(RTL_FILES) $(SIM_FILES) $(SIM_VSRC)
    @mkdir -p $(@D)
    verilator $(VERILATOR_FLAGS) --Mdir $(@D) $^ $(SIM_VSRC) $(SIM_CXXFILES)

# 编译 Emulator
emu: $(VERILATOR_BUILD_DIR)/V$(EMU_TOP).mk
    $(MAKE) -C $(VERILATOR_BUILD_DIR) -f V$(EMU_TOP).mk VM_PARALLEL_BUILDS=1 OPT_FAST="-O3"
    @ln -sf $(VERILATOR_TARGET) $(BUILD_DIR)/emu

clean:
    rm -rf $(BUILD_DIR)
```

#### 4.3.2 修改主 Makefile

**文件**：`Makefile`

```makefile
# 包含 Verilator 配置
include verilator.mk

# 默认目标
.PHONY: emu clean

emu: verilator-emu

clean:
    $(MAKE) -C difftest clean
    rm -rf build
```

### 4.4 步骤 4：添加性能计数器

#### 4.4.1 最小计数器集

只需要两个计数器就能计算 IPC：

```verilog
// 在你的 CPU 中添加
reg [63:0] commit_instr_count;
reg [63:0] cycle_count;

always @(posedge clock) begin
    if (reset) begin
        commit_instr_count <= 64'd0;
        cycle_count <= 64'd0;
    end else begin
        cycle_count <= cycle_count + 1;
        if (commit_valid) begin
            commit_instr_count <= commit_instr_count + 1;
        end
    end
end
```

#### 4.4.2 完整计数器集（可选）

```verilog
// 前端计数器
reg [63:0] icache_miss_count;
reg [63:0] itlb_miss_count;
reg [63:0] btb_miss_count;

// 后端计数器
reg [63:0] div_stall_count;
reg [63:0] rob_full_count;
reg [63:0] iq_full_count;

// 内存计数器
reg [63:0] load_l1_miss_count;
reg [63:0] load_l2_miss_count;
reg [63:0] store_stall_count;

// 在相应位置递增
always @(posedge clock) begin
    if (!reset) begin
        if (icache_miss) icache_miss_count <= icache_miss_count + 1;
        if (itlb_miss) itlb_miss_count <= itlb_miss_count + 1;
        // ...
    end
end
```

#### 4.4.3 计数器输出格式

```verilog
// 每 N 个周期输出一次（避免输出过大）
localparam OUTPUT_INTERVAL = 1000000;

always @(posedge clock) begin
    if (!reset && cycle_count % OUTPUT_INTERVAL == 0) begin
        // 基础计数器
        $fwrite(32'h80000002,
            "[PERF][time=%0d].core0.backend.ctrlBlock.rob: commitInstr, %0d\n",
            cycle_count, commit_instr_count);
        $fwrite(32'h80000002,
            "[PERF][time=%0d].core0.backend.ctrlBlock.rob: clock_cycle, %0d\n",
            cycle_count, cycle_count);
        
        // 无停顿周期
        $fwrite(32'h80000002,
            "[PERF][time=%0d].core0.backend.ctrlBlock.dispatch: NoStall, %0d\n",
            cycle_count, no_stall_count);
        
        // 前端停顿
        $fwrite(32'h80000002,
            "[PERF][time=%0d].core0.backend.ctrlBlock.dispatch: ICacheMissBubble, %0d\n",
            cycle_count, icache_miss_count);
        
        // 后端停顿
        $fwrite(32'h80000002,
            "[PERF][time=%0d].core0.backend.ctrlBlock.dispatch: DivStall, %0d\n",
            cycle_count, div_stall_count);
        
        // 内存停顿
        $fwrite(32'h80000002,
            "[PERF][time=%0d].core0.backend.ctrlBlock.dispatch: LoadL1Stall, %0d\n",
            cycle_count, load_l1_miss_count);
        
        // ... 更多计数器
    end
end
```

### 4.5 步骤 5：修改性能分析脚本

#### 4.5.1 修改 `configs.py`

**文件**：`scripts/top-down/configs.py`

```python
# 修改正则表达式以匹配你的 CPU 的输出格式

XS_CORE_PREFIX = r'\[PERF\s*\]\[time=\s*\d+\].*?\.core'

targets = {
    # 基础计数器（必须）
    "commitInstr": fr'{XS_CORE_PREFIX}.backend.*?ctrlBlock.rob: commitInstr,\s+(\d+)',
    "total_cycles": fr'{XS_CORE_PREFIX}.backend.*?ctrlBlock.rob: clock_cycle,\s+(\d+)',
    
    # 无停顿周期
    'NoStall': fr'{XS_CORE_PREFIX}.backend.*?ctrlBlock\.dispatch: NoStall,\s+(\d+)',
    
    # 前端停顿
    'ICacheMissBubble': fr'{XS_CORE_PREFIX}.backend.*?ctrlBlock\.dispatch: ICacheMissBubble,\s+(\d+)',
    'ITLBMissBubble': fr'{XS_CORE_PREFIX}.backend.*?ctrlBlock\.dispatch: ITLBMissBubble,\s+(\d+)',
    'BTBMissBubble': fr'{XS_CORE_PREFIX}.backend.*?ctrlBlock\.dispatch: BTBMissBubble,\s+(\d+)',
    
    # 后端停顿
    'DivStall': fr'{XS_CORE_PREFIX}.backend.*?ctrlBlock\.dispatch: DivStall,\s+(\d+)',
    'RobStall': fr'{XS_CORE_PREFIX}.backend.*?ctrlBlock\.dispatch: RobStall,\s+(\d+)',
    
    # 内存停顿
    'LoadL1Stall': fr'{XS_CORE_PREFIX}.backend.*?ctrlBlock\.dispatch: LoadL1Stall,\s+(\d+)',
    'LoadL2Stall': fr'{XS_CORE_PREFIX}.backend.*?ctrlBlock\.dispatch: LoadL2Stall,\s+(\d+)',
    
    # ... 根据你的计数器添加更多
}
```

#### 4.5.2 简化版本（只有 IPC）

如果你只关心 IPC，可以简化配置：

```python
targets = {
    "commitInstr": r'\[PERF.*?\]: commitInstr,\s+(\d+)',
    "total_cycles": r'\[PERF.*?\]: clock_cycle,\s+(\d+)',
}
```

### 4.6 步骤 6：准备 Checkpoint

#### 4.6.1 方法 1：使用 NEMU 生成（推荐）

```bash
# 编译 NEMU
cd NEMU
make riscv64-xs-defconfig
make -j$(nproc)

# 生成 checkpoint
./build/riscv64-nemu-interpreter-so \
    -b \
    /path/to/spec06/binary \
    --simpoint \
    --simpoint-interval 100000000 \
    --checkpoint-dir /path/to/checkpoints
```

#### 4.6.2 方法 2：使用 lightSSS（Fork-based）

XiangShan 的 lightSSS 机制可以在仿真过程中自动保存 checkpoint：

```bash
# 运行仿真，自动保存 checkpoint
./build/emu \
    -i /path/to/binary \
    --enable-fork \
    --fork-interval 100000000
```

#### 4.6.3 方法 3：简化方案（从内存镜像启动）

如果不需要精确的 checkpoint，可以跳过状态恢复：

```bash
# 直接加载内存镜像
./build/emu \
    -i /path/to/spec06/binary \
    --max-instr 20000000
```

**注意**：这种方法需要完整运行到采样点，速度较慢。

### 4.7 步骤 7：运行评估

#### 4.7.1 编译 Emulator

```bash
# 编译
make emu

# 验证
./build/emu --help
```

#### 4.7.2 运行单个 Benchmark

```bash
# 运行单个 checkpoint
./build/emu \
    -i /path/to/checkpoint.gz \
    --diff ./ready-to-run/riscv64-nemu-interpreter-so \
    --max-instr 20000000 \
    2> simulator_err.txt

# 检查输出
grep "HIT GOOD TRAP\|EXCEEDING CYCLE" simulator_err.txt
```

#### 4.7.3 运行完整评估

```bash
# 运行所有 benchmark
cd scripts/top-down
python3 top_down.py \
    -b /path/to/results \
    -j resources/spec06_rv64gcb_o2_20m.json

# 查看结果
cat results/results-weighted_base.csv
```

---

## 5. 关键代码修改点

### 5.1 DiffTest 框架配置

**文件**：`difftest/src/test/csrc/common/common.h`

```cpp
// 修改内存基地址（如果你的 CPU 使用不同地址）
#define PMEM_BASE 0x80000000ULL  // RISC-V 标准

// 修改内存大小
#define DEFAULT_EMU_RAM_SIZE (8ULL * 1024 * 1024 * 1024)  // 8GB
```

### 5.2 内存接口适配

如果你的 CPU 使用 AXI 而不是 TileLink，需要添加桥接：

**文件**：`sim/sim_top.v`

```verilog
// AXI 到 DiffTest 内存接口的桥接
axi_to_difftest_ram axi_ram_bridge (
    .axi_if(cpu_axi_if),
    .ram_read_req(ram_read_req),
    .ram_read_resp(ram_read_resp),
    .ram_write_req(ram_write_req)
);
```

### 5.3 启动地址对齐

确保你的 CPU 的启动地址与 NEMU 一致：

```verilog
// 在 cpu_top.v 中
// 注意：XiangShan 的复位向量是 0x10000000（SimTop.scala:129）
// PMEM_BASE（内存基地址）是 0x80000000（difftest/config/config.h:42）
// 两者是不同的概念
parameter BOOT_ADDR = 64'h10000000;  // 复位向量
```

---

## 6. 最小可行方案 (MVP)

如果时间有限，可以按以下顺序实现：

### 6.1 第一阶段：基本仿真（1-2 天）

1. 创建 `sim_top.v`，只实例化 CPU
2. 配置 Verilator 编译
3. 验证能编译通过

### 6.2 第二阶段：内存模型（2-3 天）

1. 复用 XiangShan 的内存模型
2. 适配你的 CPU 的内存接口
3. 验证能加载程序并运行

### 6.3 第三阶段：性能计数器（1-2 天）

1. 只添加 `commitInstr` 和 `total_cycles`
2. 修改 `configs.py` 的正则表达式
3. 验证能计算 IPC

### 6.4 第四阶段：Checkpoint 支持（3-5 天）

1. 实现 DiffTest 接口（或跳过 DiffTest）
2. 实现内存快照加载
3. 验证能从 checkpoint 恢复

### 6.5 第五阶段：完整评估（1-2 天）

1. 准备所有 benchmark 的 checkpoint
2. 运行完整评估
3. 生成 SPEC 分数

---

## 7. 常见问题与解决方案

### 7.1 Verilator 编译错误

**问题**：`Unsupported: $display with non-constant format`

**解决**：确保 `$fwrite` 的格式字符串是常量

```verilog
// 错误
$fwrite(32'h80000002, format_string, ...);

// 正确
$fwrite(32'h80000002, "[PERF][time=%0d].core.xxx: yyy, %0d\n", ...);
```

### 7.2 DiffTest 不匹配

**问题**：`Difftest failed: xxx`

**解决**：
1. 检查 `commit_pc` 是否正确
2. 检查 `commit_instr` 是否正确
3. 检查内存访问是否一致
4. 暂时禁用 DiffTest：编译时定义 `CONFIG_NO_DIFFTEST`

### 7.3 Checkpoint 加载失败

**问题**：程序从错误地址开始执行

**解决**：
1. 检查 `BOOT_ADDR` 是否为 `0x10000000`（复位向量）
2. 检查 `PMEM_BASE` 是否为 `0x80000000`（内存基地址）
2. 检查 checkpoint 文件格式是否正确
3. 检查内存大小是否足够

### 7.4 性能计数器提取失败

**问题**：`[WARN] missing keys in xxx`

**解决**：
1. 检查 `$fwrite` 输出格式是否匹配正则表达式
2. 检查输出是否被重定向到 `simulator_err.txt`
3. 检查计数器是否在 reset 后正确递增

---

## 8. 参考资源

### 8.1 XiangShan 相关

- **仓库**：https://github.com/OpenXiangShan/XiangShan
- **文档**：https://docs.xiangshan.cc/
- **DiffTest**：https://github.com/OpenXiangShan/difftest

### 8.2 工具链

- **Verilator**：https://verilator.org/
- **DRAMsim3**：https://github.com/umd-memsys/DRAMsim3
- **NEMU**：https://github.com/OpenXiangShan/NEMU

### 8.3 SPEC CPU2006

- **官方网站**：https://www.spec.org/cpu2006/
- **SimPoint**：https://www.calccc.edu/simpoint

### 8.4 关键文件索引

| 文件 | 说明 |
|------|------|
| `difftest/verilator.mk` | Verilator 编译配置参考 |
| `difftest/emu.mk` | Emulator 构建规则参考 |
| `difftest/src/test/vsrc/common/` | DiffTest Verilog 接口 |
| `difftest/src/test/csrc/emu/emu.cpp` | Emulator 主逻辑 |
| `difftest/src/test/csrc/common/ram.cpp` | 内存模型实现 |
| `scripts/top-down/configs.py` | 性能计数器配置 |
| `scripts/top-down/top_down.py` | 分数计算脚本 |

---

## 附录 A：快速检查清单

### 编译检查
- [ ] RTL 文件能被 Verilator 解析
- [ ] 能生成 C++ 文件
- [ ] 能编译出可执行文件
- [ ] 能运行 `./build/emu --help`

### 功能检查
- [ ] 能加载内存镜像
- [ ] CPU 能开始执行
- [ ] 能输出性能计数器
- [ ] 能正常终止（HIT GOOD TRAP）

### 性能检查
- [ ] 能计算 IPC
- [ ] 能加载 checkpoint
- [ ] 能运行完整评估
- [ ] 能生成 SPEC 分数

---

## 附录 B：示例 Makefile

完整的 Makefile 示例（可直接使用）：

```makefile
# your-cpu/Makefile

# 包含 Verilator 配置
include verilator.mk

# 默认目标
.PHONY: all emu clean test

all: emu

emu: verilator-emu

clean:
    rm -rf build

# 测试单个 benchmark
test: emu
    ./build/emu -i $(TEST_CHECKPOINT) \
        --diff ./ready-to-run/riscv64-nemu-interpreter-so \
        --max-instr 20000000 \
        2> simulator_err.txt
    grep "HIT GOOD TRAP\|EXCEEDING CYCLE" simulator_err.txt

# 运行完整评估
eval: emu
    cd scripts/top-down && \
    python3 top_down.py \
        -b $(RESULTS_DIR) \
        -j resources/spec06_rv64gcb_o2_20m.json
```
