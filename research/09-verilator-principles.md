# Verilator 编译原理详解

> 本文档详细解释 Verilator 如何将 RTL "编译"成 C++，适合嵌入式软件工程师理解。

---

## 目录

1. [什么是 Verilator？](#1-什么是-verilator)
2. [仿真器分类](#2-仿真器分类)
3. [Verilator 的工作原理](#3-verilator-的工作原理)
4. [编译流程详解](#4-编译流程详解)
5. [与嵌入式交叉编译的对比](#5-与嵌入式交叉编译的对比)
6. [Verilator 的性能优势](#6-verilator-的性能优势)
7. [XiangShan 中的 Verilator 使用](#7-xiangshan-中的-verilator-使用)
8. [常见问题解答](#8-常见问题解答)

---

## 1. 什么是 Verilator？

### 1.1 简单定义

**Verilator**：一个开源的 Verilog/SystemVerilog 仿真器，将 RTL 代码"编译"成 C++ 代码，然后编译成原生可执行文件。

### 1.2 类比理解

**嵌入式工程师的类比**：

```
传统仿真器 (VCS/ModelSim):
  Verilog → 解释执行（逐行翻译执行）
  类似于：Python 解释器执行 Python 代码

Verilator:
  Verilog → C++ → 原生可执行文件
  类似于：GCC 编译 C 代码为可执行文件
```

### 1.3 Verilator 的特点

| 特点 | 说明 |
|------|------|
| **开源** | 免费使用，GPL 许可证 |
| **速度快** | 比传统仿真器快 10-100 倍 |
| **周期精确** | 每个时钟周期的模拟是精确的 |
| **可综合验证** | 主要用于可综合 RTL 的验证 |
| **不支持** | 不支持延迟 (#10)、事件驱动等 |

---

## 2. 仿真器分类

### 2.1 事件驱动仿真器

**代表**：VCS, ModelSim, Icarus Verilog

**原理**：
- 每个信号变化都是一个"事件"
- 事件触发其他事件，形成"事件队列"
- 按时间顺序处理所有事件

```
事件队列：
时间 0: [clk ↑, reset ↓]
时间 1: [data_out = 1]
时间 2: [result = data_out & mask]
时间 3: [output = result]
...
```

**优点**：
- 支持所有 Verilog 特性
- 支持时序仿真 (#delay)
- 精确模拟硬件行为

**缺点**：
- 速度慢（事件调度开销大）
- 内存占用大

### 2.2 周期精确仿真器

**代表**：Verilator

**原理**：
- 只关心每个时钟周期结束时的状态
- 忽略时钟周期内的信号变化
- 将 RTL 转换为 C++ 函数，每个函数对应一个时钟周期

```
周期精确：
时钟周期 0: 输入 → 计算 → 输出
时钟周期 1: 输入 → 计算 → 输出
时钟周期 2: 输入 → 计算 → 输出
...
```

**优点**：
- 速度快（没有事件调度开销）
- 内存占用小

**缺点**：
- 不支持时序仿真
- 不支持部分 Verilog 特性

### 2.3 事务级仿真器

**代表**：SystemC, gem5

**原理**：
- 以"事务"为单位进行仿真
- 一个事务可能包含多个时钟周期
- 更高层次的抽象

**优点**：
- 速度最快
- 适合系统级仿真

**缺点**：
- 精度最低
- 不适合 RTL 验证

### 2.4 对比总结

| 类型 | 代表 | 速度 | 精度 | 用途 |
|------|------|------|------|------|
| 事件驱动 | VCS, ModelSim | 慢 | 最高 | RTL 验证 |
| 周期精确 | Verilator | 快 | 高 | 功能验证 |
| 事务级 | gem5 | 最快 | 低 | 系统仿真 |

---

## 3. Verilator 的工作原理

### 3.1 核心思想

**Verilator 的核心思想**：将 RTL 代码转换为等价的 C++ 代码。

```
Verilog:
always @(posedge clk) begin
    if (reset)
        count <= 0;
    else
        count <= count + 1;
end

转换为 C++:
void SimTop::eval() {
    if (clk->posedge()) {
        if (reset) {
            count = 0;
        } else {
            count = count + 1;
        }
    }
}
```

### 3.2 转换过程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Verilator 转换过程                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  步骤 1: 解析 (Parsing)                                                     │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Verilog 代码 → AST (抽象语法树)                                     │   │
│  │                                                                     │   │
│  │ always @(posedge clk) begin                                         │   │
│  │     if (reset)                                                      │   │
│  │         count <= 0;                                                 │   │
│  │     else                                                            │   │
│  │         count <= count + 1;                                         │   │
│  │ end                                                                 │   │
│  │                                                                     │   │
│  │            ↓                                                        │   │
│  │                                                                     │   │
│  │ AST:                                                                │   │
│  │ - AlwaysBlock                                                       │   │
│  │   - SensitivityList: posedge clk                                    │   │
│  │   - IfStatement                                                     │   │
│  │     - Condition: reset                                              │   │
│  │     - Then: Assignment(count, 0)                                    │   │
│  │     - Else: Assignment(count, count + 1)                            │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  步骤 2: 优化 (Optimization)                                                │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ - 常量折叠 (Constant Folding)                                       │   │
│  │ - 死代码消除 (Dead Code Elimination)                                │   │
│  │ - 公共子表达式消除 (CSE)                                            │   │
│  │ - 循环展开 (Loop Unrolling)                                         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  步骤 3: 代码生成 (Code Generation)                                         │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ AST → C++ 代码                                                      │   │
│  │                                                                     │   │
│  │ void SimTop::eval() {                                               │   │
│  │     if (clk->posedge()) {                                           │   │
│  │         if (reset) {                                                │   │
│  │             count = 0;                                              │   │
│  │         } else {                                                    │   │
│  │             count = count + 1;                                      │   │
│  │         }                                                           │   │
│  │     }                                                               │   │
│  │ }                                                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  步骤 4: 编译 (Compilation)                                                 │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ C++ 代码 → g++ 编译 → 可执行文件                                    │   │
│  │                                                                     │   │
│  │ g++ -O3 -o VSimTop VSimTop.cpp ...                                  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.3 eval() 函数

**核心概念**：Verilator 将整个设计转换为一个 `eval()` 函数。

```cpp
// 生成的 C++ 代码
class SimTop {
public:
    // 信号定义
    IData *clk;      // 时钟
    IData *reset;    // 复位
    QData *count;    // 计数器
    
    // eval 函数：模拟一个时钟周期
    void eval() {
        // 组合逻辑（立即执行）
        // ...
        
        // 时序逻辑（时钟边沿触发）
        if (clk->posedge()) {
            if (reset) {
                count = 0;
            } else {
                count = count + 1;
            }
        }
    }
};
```

### 3.4 仿真主循环

```cpp
int main() {
    SimTop top;
    
    // 初始化
    top.reset = 1;
    top.clk = 0;
    top.eval();
    
    // 释放复位
    top.reset = 0;
    
    // 仿真循环
    for (int i = 0; i < 1000000; i++) {
        // 时钟上升沿
        top.clk = 1;
        top.eval();
        
        // 时钟下降沿
        top.clk = 0;
        top.eval();
    }
    
    return 0;
}
```

---

## 4. 编译流程详解

### 4.1 XiangShan 的 Verilator 编译流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    XiangShan Verilator 编译流程                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  步骤 1: 生成 SystemVerilog                                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ make sim-verilog                                                    │   │
│  │                                                                     │   │
│  │ mill -i xiangshan.test.runMain top.XiangShanSim \                   │   │
│  │     --target-dir build/rtl --config DefaultConfig                   │   │
│  │                                                                     │   │
│  │ 输出: build/rtl/SimTop.sv + 子模块 .sv 文件                        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  步骤 2: Verilator 编译                                                    │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ make emu                                                            │   │
│  │                                                                     │   │
│  │ verilator --exe -O3 --cc --top-module SimTop \                      │   │
│  │     +define+VERILATOR=1 \                                           │   │
│  │     +define+RANDOMIZE_REG_INIT \                                    │   │
│  │     --max-num-width 150000 \                                        │   │
│  │     --assert --x-assign unique \                                    │   │
│  │     --output-split 30000 \                                          │   │
│  │     -I build/rtl \                                                  │   │
│  │     -CFLAGS "..." -LDFLAGS "..." \                                  │   │
│  │     -o build/verilator-compile/emu \                                │   │
│  │     SimTop.sv [C++ files]                                           │   │
│  │                                                                     │   │
│  │ 输出: build/verilator-compile/VSimTop.cpp/.h                        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  步骤 3: g++ 编译                                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ make -C build/verilator-compile -f VSimTop.mk \                     │   │
│  │     VM_PARALLEL_BUILDS=1 OPT_FAST="-O3"                            │   │
│  │                                                                     │   │
│  │ 输出: build/verilator-compile/emu (可执行文件)                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  步骤 4: 运行仿真                                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ ./build/emu -i checkpoint.gz --max-instr 20000000                   │   │
│  │                                                                     │   │
│  │ 输出: simulator_err.txt (性能计数器)                               │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.2 Verilator 关键选项

**编译选项**（`difftest/verilator.mk`）：

```makefile
VERILATOR_FLAGS_ALL = \
    --exe                    \  # 生成可执行文件
    -O3                      \  # C++ 优化级别
    --cc                     \  # 生成 C++ 代码
    --top-module SimTop      \  # 顶层模块
    +define+VERILATOR=1      \  # 定义 VERILATOR 宏
    +define+RANDOMIZE_REG_INIT \  # 寄存器随机初始化
    +define+RANDOMIZE_MEM_INIT \  # 内存随机初始化
    --max-num-width 150000   \  # 最大位宽
    --assert                 \  # 启用断言
    --x-assign unique        \  # X 值赋值策略
    --output-split 30000     \  # 分割大文件
    -I$(RTL_DIR)             \  # RTL 包含路径
    -CFLAGS "..."            \  # C++ 编译选项
    -LDFLAGS "..."           \  # C++ 链接选项
    -o $(VERILATOR_TARGET)      # 输出文件
```

### 4.3 生成的文件结构

```
build/verilator-compile/
├── VSimTop.cpp              # 主模块实现
├── VSimTop.h                # 主模块头文件
├── VSimTop__Syms.cpp        # 符号表
├── VSimTop__Syms.h          # 符号表头文件
├── VSimTop___024root.cpp    # 根模块
├── VSimTop___024root.h      # 根模块头文件
├── VSimTop.mk               # Makefile
├── VSimTop__Dpi.cpp         # DPI 接口
└── ...                      # 更多文件
```

---

## 5. 与嵌入式交叉编译的对比

### 5.1 嵌入式交叉编译

```
嵌入式交叉编译：
源代码 (C) → ARM GCC → ARM 可执行文件

示例：
arm-none-eabi-gcc -O2 -o firmware.elf main.c

特点：
- 源代码在主机 (x86)
- 目标代码在目标机 (ARM)
- 需要交叉编译工具链
```

### 5.2 Verilator 编译

```
Verilator 编译：
RTL (Verilog) → Verilator → C++ → GCC → x86 可执行文件

示例：
verilator --cc --exe -o emu SimTop.v emu.cpp
make -C obj_dir -f VSimTop.mk

特点：
- 源代码是 RTL (Verilog)
- 中间代码是 C++
- 最终代码在主机 (x86) 运行
```

### 5.3 对比

| 方面 | 嵌入式交叉编译 | Verilator 编译 |
|------|---------------|----------------|
| **源代码** | C/C++ | Verilog/SystemVerilog |
| **目标代码** | ARM 可执行文件 | x86 可执行文件 |
| **编译器** | ARM GCC | Verilator + GCC |
| **用途** | 在目标硬件运行 | 在主机上仿真 |
| **速度** | 快（编译一次） | 慢（需要两步编译） |

---

## 6. Verilator 的性能优势

### 6.1 为什么 Verilator 快？

**原因 1：编译为原生代码**

```
传统仿真器：
Verilog → 解释执行（每条指令都要翻译）

Verilator：
Verilog → C++ → 原生代码（直接执行）
```

**原因 2：周期精确**

```
传统仿真器：
每个信号变化都是一个事件
需要维护事件队列
需要处理时序关系

Verilator：
只关心时钟周期结束时的状态
没有事件调度开销
```

**原因 3：编译时优化**

```
C++ 编译器可以进行：
- 常量折叠
- 死代码消除
- 循环展开
- 内联函数
- SIMD 优化
```

### 6.2 性能对比

| 仿真器 | 速度 (Hz) | 相对速度 |
|--------|-----------|----------|
| VCS | 1-10 | 1x |
| ModelSim | 1-5 | 0.5x |
| Verilator | 10-100 | 10-100x |
| Verilator + PGO | 20-200 | 20-200x |

### 6.3 PGO 优化

**PGO (Profile Guided Optimization)**：基于运行时 profile 数据的编译优化。

**XiangShan 的 PGO 流程**：

```bash
# 步骤 1: 用 coremark 运行仿真，收集 profile 数据
python3 scripts/xiangshan.py --build \
    --pgo coremark-2-iteration.bin \
    --pgo-max-cycle 400000 \
    --llvm-profdata llvm-profdata

# 步骤 2: 用 profile 数据重新编译
# Verilator 会根据 profile 数据优化热点代码
```

**效果**：性能提升 20-40%

---

## 7. XiangShan 中的 Verilator 使用

### 7.1 编译配置

**文件**：`difftest/verilator.mk`

```makefile
# Verilator 版本检查
VERILATOR_VER_CMD = $(VERILATOR) --version 2> /dev/null | cut -f2 -d' '
VERILATOR_4_210 := $(shell expr `$(VERILATOR_VER_CMD)` \>= 4210 2> /dev/null)

# 特定版本的选项
ifeq ($(VERILATOR_4_210),1)
VERILATOR_CXXFLAGS += -DVERILATOR_4_210
VERILATOR_FLAGS += --instr-count-dpi 1
endif

VERILATOR_5_000 := $(shell expr `$(VERILATOR_VER_CMD)` \>= 5000 2> /dev/null)
ifeq ($(VERILATOR_5_000),1)
VERILATOR_FLAGS += --no-timing +define+VERILATOR_5
endif
```

### 7.2 多线程支持

```makefile
# 多线程 RTL 仿真
ifneq ($(EMU_THREADS),0)
VERILATOR_FLAGS += --threads $(EMU_THREADS) --threads-dpi all
endif
```

**使用**：

```bash
# 使用 8 个线程
make emu EMU_THREADS=8
```

### 7.3 波形支持

```makefile
# VCD 波形
ifneq (,$(filter $(EMU_TRACE),1 vcd VCD))
VERILATOR_FLAGS += --trace
endif

# FST 波形（更小）
ifneq (,$(filter $(EMU_TRACE),fst FST))
VERILATOR_FLAGS += --trace-fst
VERILATOR_CXXFLAGS += -DENABLE_FST
endif
```

**使用**：

```bash
# 生成 FST 波形
./build/emu -i checkpoint.gz --enable-waveform
```

---

## 8. 常见问题解答

### 8.1 Q: Verilator 支持所有 Verilog 特性吗？

**A**: 不支持。

**不支持的特性**：
- 时序仿真 (`#10`)
- 事件驱动 (`@event`)
- `force`/`release`
- 部分 SystemVerilog 特性

**支持的特性**：
- 所有可综合 RTL
- `always` 块
- `assign` 语句
- 模块实例化
- 参数化设计

### 8.2 Q: Verilator 仿真结果和 VCS 一样吗？

**A**: 对于可综合 RTL，结果应该一样。

**差异来源**：
- X 值处理不同
- 时序行为不同
- 某些边界情况不同

### 8.3 Q: Verilator 适合什么场景？

**A**: 适合功能验证和性能评估。

**适合**：
- 功能验证
- 性能评估
- 快速迭代

**不适合**：
- 时序验证
- 门级仿真
- 混合信号仿真

### 8.4 Q: 如何提高 Verilator 仿真速度？

**A**: 多种方法。

**方法**：
1. 使用多线程 (`--threads`)
2. 使用 PGO 优化
3. 使用 `-O3` 优化
4. 减少波形输出
5. 使用 FST 格式代替 VCD

---

## 总结

### Verilator 核心概念

| 概念 | 说明 |
|------|------|
| **编译型** | 将 RTL 转换为 C++，再编译为原生代码 |
| **周期精确** | 只关心时钟周期结束时的状态 |
| **快速** | 比传统仿真器快 10-100 倍 |
| **开源** | 免费使用，GPL 许可证 |

### 嵌入式工程师的理解要点

1. **Verilator 类似于 GCC**：将源代码编译为可执行文件
2. **Verilog 是源代码**：就像 C 是源代码
3. **C++ 是中间代码**：就像汇编是中间代码
4. **可执行文件是目标**：就像固件是目标

### 实践建议

1. **先理解编译流程**：Verilog → C++ → 可执行文件
2. **尝试编译简单模块**：从计数器开始
3. **理解 eval() 函数**：这是仿真的核心
4. **使用多线程**：提高仿真速度
