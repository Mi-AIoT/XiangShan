# XiangShan SimPoint SPEC2006 评估完整流程详解

> 本文档详细介绍 XiangShan 如何使用 SimPoint 技术快速评估 SPEC2006 CPU 性能分数。

---

## 目录

1. [背景与动机](#1-背景与动机)
2. [SimPoint 技术原理](#2-simpoint-技术原理)
3. [整体架构概览](#3-整体架构概览)
4. [Checkpoint 生成流程](#4-checkpoint-生成流程)
5. [Emulator 构建流程](#5-emulator-构建流程)
6. [Checkpoint 恢复与执行](#6-checkpoint-恢复与执行)
7. [性能计数器提取](#7-性能计数器提取)
8. [加权计算与分数生成](#8-加权计算与分数生成)
9. [GitHub Actions 自动化流程](#9-github-actions-自动化流程)
10. [Top-Down 分析方法](#10-top-down-分析方法)

---

## 1. 背景与动机

### 1.1 为什么需要 SimPoint？

SPEC CPU2006 是业界标准的 CPU 性能评估基准测试，包含 12 个整数程序和 17 个浮点程序。完整运行一个 benchmark 通常需要：

- **真实硬件**：数小时到数天
- **RTL 仿真**：数周到数月（因为 RTL 仿真速度极慢，通常只有 1-10 Hz）

SimPoint 技术通过统计采样，将仿真时间从**数周缩短到数小时**，同时保持**97%+ 的精度**。

### 1.2 核心思想

```
完整程序执行（数十亿指令）      SimPoint 采样（数百万指令）
[====|====|====|====|====]  →   [    |  ✓ |    | ✓  |    ]
                              只仿真代表性片段，加权求和
```

**关键洞察**：程序的行为可以分成若干"阶段"(phase)，每个阶段内的行为高度相似。通过找到最具代表性的采样点，可以用少量仿真估算全程序性能。

---

## 2. SimPoint 技术原理

### 2.1 基本流程

```
┌─────────────────────────────────────────────────────────────────────┐
│                        SimPoint 工作流程                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  1. 程序分析阶段（离线，一次性）                                      │
│     ┌──────────────┐    ┌──────────────┐    ┌──────────────┐       │
│     │ SPEC2006     │───▶│ 基本块向量   │───▶│ K-Means     │       │
│     │ Benchmark    │    │ (BBV) 提取   │    │ 聚类分析     │       │
│     └──────────────┘    └──────────────┘    └──────────────┘       │
│                                                      │              │
│                                                      ▼              │
│                                              ┌──────────────┐       │
│                                              │ 采样点列表   │       │
│                                              │ + 权重       │       │
│                                              └──────────────┘       │
│                                                                     │
│  2. 仿真阶段（在线，每次评估）                                        │
│     ┌──────────────┐    ┌──────────────┐    ┌──────────────┐       │
│     │ 加载         │───▶│ RTL 仿真     │───▶│ 加权计算     │       │
│     │ Checkpoint   │    │ (Verilator)  │    │ 性能指标     │       │
│     └──────────────┘    └──────────────┘    └──────────────┘       │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 SimPoint 采样点

以 `astar_biglakes` benchmark 为例（来自 `scripts/top-down/resources/spec06_rv64gcb_o2_20m.json`）：

```json
{
  "astar_biglakes": {
    "insts": "320330109413",        // 总指令数：3203 亿
    "points": {
      "10080000000": "0.000500",    // 采样点 1：指令地址 100.8 亿，权重 0.05%
      "122060000000": "0.244818",   // 采样点 2：指令地址 1220.6 亿，权重 24.48%
      "181920000000": "0.044143",   // 采样点 3：指令地址 1819.2 亿，权重 4.41%
      "22020000000": "0.168269",    // 采样点 4：指令地址 220.2 亿，权重 16.83%
      ...
    }
  }
}
```

**解读**：
- 该 benchmark 总共执行 3203 亿条指令
- 被分成 12 个采样点，每个点代表程序的一个"阶段"
- 权重表示该阶段在全程序中的占比
- 所有权重之和 ≈ 1.0

### 2.3 为什么这样有效？

**理论基础**：程序的执行可以建模为一系列"阶段"(phase)：
- 同一阶段内，IPC、缓存命中率等指标相对稳定
- 不同阶段之间，行为差异显著
- 通过聚类分析找到阶段边界，选择代表性采样点

**精度**：研究表明，10-20 个 SimPoint 采样点可以达到 97%+ 的精度。

---

## 3. 整体架构概览

### 3.1 组件关系图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           XiangShan 评估框架                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐   ┌─────────────┐   │
│  │  Chisel     │   │  Verilator  │   │  DiffTest   │   │  NEMU       │   │
│  │  RTL 源码   │──▶│  编译器     │──▶│  框架       │◀──▶│  参考模型   │   │
│  └─────────────┘   └─────────────┘   └─────────────┘   └─────────────┘   │
│         │                │                  │                  │            │
│         ▼                ▼                  ▼                  ▼            │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        Emulator (emu)                               │   │
│  │  - 加载 checkpoint                                                  │   │
│  │  - 执行指定指令数                                                   │   │
│  │  - 输出性能计数器                                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │                                        │
│                                    ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        分析脚本                                      │   │
│  │  - top_down.py: Top-Down 分析                                       │   │
│  │  - xs_autorun_multiServer.py: 分数计算                              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 数据流

```
Chisel 源码
    │
    ▼ (mill + firtool)
SystemVerilog (SimTop.sv)
    │
    ▼ (Verilator)
C++ 源码 (VSimTop.cpp)
    │
    ▼ (g++ 链接 DiffTest)
Emulator 可执行文件 (emu)
    │
    ▼ (加载 checkpoint.gz)
仿真执行 → simulator_err.txt (性能计数器)
    │
    ▼ (Python 脚本)
SPEC2006 分数 (score.txt)
```

---

## 4. Checkpoint 生成流程

### 4.1 工具链

Checkpoint 生成使用 **NEMU**（NJU EMUlator），这是一个 RISC-V ISA 模拟器，属于 XiangShan 生态系统的一部分。

**关键路径**：
- NEMU 仓库：`../NEMU`（相对于 XiangShan）
- 配置文件：`NEMU/src/isa/riscv64/`

### 4.2 Checkpoint 格式 (GCPT)

GCPT (Golden Checkpoint) 格式包含：

```
┌────────────────────────────────────────┐
│            GCPT 文件格式                │
├────────────────────────────────────────┤
│ Header (固定大小)                       │
│  - magic number                        │
│  - 版本信息                             │
│  - CPU 状态大小                         │
├────────────────────────────────────────┤
│ CPU 状态                               │
│  - 寄存器文件 (x0-x31, f0-f31)         │
│  - PC (程序计数器)                      │
│  - CSR (控制状态寄存器)                 │
│  - Privilege Level                     │
├────────────────────────────────────────┤
│ 内存快照                               │
│  - 物理内存内容                         │
│  - 页表内容                             │
└────────────────────────────────────────┘
```

### 4.3 存储位置

XiangShan 的 checkpoint 存储在 NFS 共享目录：

```
/nfs/home/share/checkpoints_profiles/
├── spec06_gcc15_rv64gcb_base_260122/
│   └── checkpoint-0-0-0/
│       ├── cluster-0-0.json           # 采样点配置
│       ├── perlbench/
│       │   ├── 39720000000.gz         # 采样点 checkpoint
│       │   ├── 100000000000.gz
│       │   └── ...
│       ├── bzip2/
│       └── ...
├── spec06_xscc_v1_rv64gcb_base_260122/
└── spec06_rv64gcb_O3_20m_gcc12.2.0-intFpcOff-jeMalloc/
```

### 4.4 JSON 配置文件格式

**文件位置**：`scripts/top-down/resources/spec06_rv64gcb_o2_20m.json`

```json
{
  "benchmark_name": {
    "insts": "总指令数（字符串）",
    "points": {
      "指令地址1": "权重1",
      "指令地址2": "权重2",
      ...
    }
  }
}
```

**示例**（`bzip2_chicken`）：
```json
{
  "bzip2_chicken": {
    "insts": "180889503776",          // 1808 亿条指令
    "points": {
      "100620000000": "0.040579",     // 4.06%
      "11700000000": "0.006081",      // 0.61%
      "120340000000": "0.032176",     // 3.22%
      "146020000000": "0.196816",     // 19.68% (最大权重)
      "36640000000": "0.169062",      // 16.91%
      ...
    }
  }
}
```

---

## 5. Emulator 构建流程

### 5.1 构建步骤概览

```
┌─────────────────────────────────────────────────────────────────────┐
│                      Emulator 构建流程                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  步骤 1: Chisel → SystemVerilog                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ $ make sim-verilog                                           │   │
│  │   → mill -i xiangshan.test.runMain top.XiangShanSim        │   │
│  │     --target-dir build/rtl --config DefaultConfig           │   │
│  │   → 输出: build/rtl/SimTop.sv + 子模块 .sv 文件            │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                              │                                      │
│                              ▼                                      │
│  步骤 2: SystemVerilog → C++ (Verilator)                            │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ $ verilator --exe -O3 --cc --top-module SimTop              │   │
│  │   +define+VERILATOR=1 +define+RANDOMIZE_REG_INIT           │   │
│  │   --max-num-width 150000 --assert --x-assign unique         │   │
│  │   --output-split 30000 -I build/rtl                         │   │
│  │   SimTop.sv [其他 .sv/.v 文件] [C++ 文件]                   │   │
│  │   → 输出: build/verilator-compile/VSimTop.cpp/.h            │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                              │                                      │
│                              ▼                                      │
│  步骤 3: C++ 编译链接                                                │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │ $ make -C build/verilator-compile -f VSimTop.mk             │   │
│  │   OPT_FAST="-O3" VM_PARALLEL_BUILDS=1                      │   │
│  │   → 输出: build/verilator-compile/emu (可执行文件)          │   │
│  │   → 软链接: build/emu                                       │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 5.2 关键 Makefile 文件

**主 Makefile**（`Makefile`）：
```makefile
# 生成仿真 Verilog
sim-verilog: $(SIM_TOP_V)

$(SIM_TOP_V): $(SCALA_FILE) $(TEST_FILE)
    mill -i xiangshan.test.runMain $(SIMTOP) \
        --target-dir $(RTL_DIR) --config $(CONFIG) ...

# 构建 Emulator
emu: emu-mk
    $(MAKE) -C ./difftest emu NUM_CORES=$(NUM_CORES)
```

**Verilator 配置**（`difftest/verilator.mk`）：
```makefile
VERILATOR_FLAGS_ALL = \
    --exe $(EMU_OPTIMIZE)             \  # -O3 优化
    --cc --top-module $(EMU_TOP)      \  # 顶层模块: SimTop
    +define+VERILATOR=1               \  # Verilator 宏
    +define+RANDOMIZE_REG_INIT        \  # 寄存器随机初始化
    +define+RANDOMIZE_MEM_INIT        \  # 内存随机初始化
    --max-num-width 150000            \  # 最大位宽
    --assert --x-assign unique        \  # 断言和赋值策略
    --output-split 30000              \  # 分割大文件
    -I$(RTL_DIR)                      \  # RTL 包含路径
    $(VERILATOR_FLAGS)
```

### 5.3 DiffTest 框架

DiffTest 框架是 XiangShan 仿真的核心基础设施，位于 `difftest/` 目录。

**目录结构**：
```
difftest/
├── Makefile                    # 主 Makefile
├── verilator.mk               # Verilator 编译配置
├── emu.mk                     # Emulator 构建规则
├── src/test/
│   ├── csrc/                  # C++ 源码
│   │   ├── common/            # 公共组件
│   │   │   ├── ram.cpp/h      # 内存模型
│   │   │   ├── args.h         # 命令行参数
│   │   │   └── ...
│   │   ├── difftest/          # 差分测试
│   │   │   ├── difftest.cpp/h
│   │   │   └── ...
│   │   └── emu/               # Emulator 主逻辑
│   │       ├── emu.cpp/h      # Emulator 类
│   │       ├── main.cpp       # 入口点
│   │       └── simulator.h    # 仿真器抽象
│   └── vsrc/                  # Verilog 源码
│       └── common/            # DiffTest 接口定义
```

### 5.4 PGO 优化

XiangShan 使用 **Profile Guided Optimization (PGO)** 加速仿真：

```bash
# 在 perf-template.yml 中
python3 $GITHUB_WORKSPACE/scripts/xiangshan.py --build \
    --pgo $GITHUB_WORKSPACE/ready-to-run/coremark-2-iteration.bin \
    --llvm-profdata llvm-profdata
```

**PGO 流程**：
1. 用 coremark 运行一次仿真，收集 profile 数据
2. 用 profile 数据重新编译，优化热点代码
3. 最终 emu 性能提升 20-40%

---

## 6. Checkpoint 恢复与执行

### 6.1 执行命令

```bash
./build/emu \
    -i /path/to/checkpoint.gz \                    # Checkpoint 文件
    --diff ./ready-to-run/riscv64-nemu-interpreter-so \  # DiffTest 参考模型
    --max-instr 20000000 \                         # 最大指令数
    --seed 42 \                                    # 随机种子
    --ram-size 8GB                                 # 内存大小
```

### 6.2 GCPT 恢复机制

**代码位置**：`difftest/src/test/csrc/emu/emu.cpp:131-138`

```cpp
Emulator::Emulator(int argc, const char *argv[])
    : dut_ptr(new SIMULATOR), cycles(0), ... {
    
    // ... 初始化代码 ...
    
    // GCPT 恢复
    if (args.gcpt_restore) {
        if (args.overwrite_nbytes_autoset) {
            // 自动检测 checkpoint 大小
            FILE *fp = fopen(args.gcpt_restore, "rb");
            fseek(fp, 4, SEEK_SET);
            fread(&args.overwrite_nbytes, sizeof(uint32_t), 1, fp);
            fclose(fp);
        }
        // 将 checkpoint 内容写入模拟内存
        overwrite_ram(args.gcpt_restore, args.overwrite_nbytes);
    }
}
```

**`overwrite_ram` 函数**（`difftest/src/test/csrc/common/ram.cpp:441-446`）：
```cpp
void overwrite_ram(const char *gcpt_restore, uint64_t overwrite_nbytes) {
    InputReader *reader = new FileReader(gcpt_restore);
    int overwrite_size = reader->read_all(simMemory->as_ptr(), overwrite_nbytes);
    Info("Overwrite %d bytes from file %s.\n", overwrite_size, gcpt_restore);
    delete reader;
}
```

### 6.3 执行主循环

**代码位置**：`difftest/src/test/csrc/emu/main.cpp`

```cpp
int main(int argc, const char *argv[]) {
    // 初始化 Emulator
    auto emu = new Emulator(argc, argv);
    
    // 主仿真循环
    while (!emu->is_finished()) {
        emu->tick();  // 执行一个时钟周期
    }
    
    bool is_good = emu->is_good();
    delete emu;
    return !is_good;
}
```

### 6.4 执行终止条件

**代码位置**：`difftest/src/test/csrc/emu/emu.h:89-94`

```cpp
bool is_good_trap() {
    return trapCode == STATE_GOODTRAP      // 程序正常结束
        || trapCode == STATE_LIMIT_EXCEEDED  // 达到指令/周期限制
        || trapCode == STATE_SIM_EXIT;       // 仿真主动退出
}
```

**输出示例**：
```
# 正常结束
HIT GOOD TRAP at pc = 0x80001234
Total instructions: 20000000
Total cycles: 5000000
IPC: 4.0

# 达到限制
EXCEEDING CYCLE/INSTR LIMIT
Total instructions: 20000000
Total cycles: 6000000
IPC: 3.33
```

---

## 7. 性能计数器提取

### 7.1 计数器输出格式

XiangShan 的性能计数器通过 `$fwrite` 输出到 stderr（重定向到 `simulator_err.txt`）：

```
[PERF][time=1000000].core0.backend.ctrlBlock.dispatch: NoStall, 1234567
[PERF][time=1000000].core0.backend.ctrlBlock.dispatch: ICacheMissBubble, 89012
[PERF][time=1000000].core0.backend.ctrlBlock.dispatch: DivStall, 34567
[PERF][time=1000000].core0.backend.ctrlBlock.rob: commitInstr, 20000000
[PERF][time=1000000].core0.backend.ctrlBlock.rob: clock_cycle, 5000000
```

### 7.2 性能计数器定义

**代码位置**：`scripts/top-down/configs.py:310-398`

```python
XS_CORE_PREFIX = r'\[PERF\s*\]\[time=\s*\d+\].*?\.core'

targets = {
    # 提交指令数
    "commitInstr": fr'{XS_CORE_PREFIX}.backend.*?ctrlBlock.rob: commitInstr,\s+(\d+)',
    
    # 总周期数
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
    
    # ... 更多计数器
}
```

### 7.3 性能计数器分类

| 类别 | 计数器 | 含义 |
|------|--------|------|
| **基础** | `commitInstr` | 提交指令数 |
| **基础** | `total_cycles` | 总周期数 |
| **基础** | `NoStall` | 无停顿周期 |
| **前端** | `ICacheMissBubble` | I-Cache 缺失导致的气泡 |
| **前端** | `ITLBMissBubble` | I-TLB 缺失 |
| **前端** | `BTBMissBubble` | BTB 缺失 |
| **前端** | `FetchFragBubble` | 取指碎片化 |
| **后端** | `DivStall` | 除法器停顿 |
| **后端** | `RobStall` | ROB 满 |
| **后端** | `IntFlStall` | 整数 freelist 满 |
| **内存** | `LoadL1Stall` | L1 缺失 |
| **内存** | `LoadL2Stall` | L2 缺失 |
| **内存** | `LoadL3Stall` | L3 缺失 |
| **内存** | `StoreStall` | 存储停顿 |
| **特殊** | `AtomicStall` | 原子操作停顿 |
| **特殊** | `SpecialInsts` | 特权指令 |

### 7.4 提取脚本

**代码位置**：`scripts/top-down/utils.py:13-55`

```python
def xs_get_stats(stat_file: str, targets: list) -> dict:
    """从 simulator_err.txt 提取性能计数器"""
    with open(stat_file, encoding='utf-8') as f:
        lines = f.read().splitlines()
    
    patterns = {}
    for k, p in targets.items():
        patterns[k] = re.compile(p)
    
    stats = {}
    for line in lines:
        for k, pattern in patterns.items():
            m = pattern.search(line)
            if m is not None:
                stats[k] = to_num(m.group(1))
                break
    
    # 计算 IPC
    stats['ipc'] = stats['commitInstr'] / stats['total_cycles']
    return stats
```

---

## 8. 加权计算与分数生成

### 8.1 单个 Benchmark 加权

**代码位置**：`scripts/top-down/top_down.py:62-95`

```python
def proc_input(wl_df: pd.DataFrame, js: dict, workload: str):
    """
    单个 workload 的加权计算
    
    参数:
        wl_df: 该 workload 所有采样点的性能数据 (DataFrame)
        js: JSON 配置（采样点权重）
        workload: workload 名称
    
    返回:
        加权后的性能指标
    """
    # 按采样点排序
    wl_df = wl_df.sort_values(by=['point'])
    
    # 获取权重
    wl_js = dict(js[workload])
    vec_weight = pd.DataFrame.from_dict(wl_js['points'], orient='index')
    vec_weight.index = vec_weight.index.astype(np.int64)
    
    # 只保留实际运行的采样点
    vec_weight = vec_weight.loc[wl_df['point']]
    vec_weight.columns = ['weight']
    
    # 归一化权重（处理部分采样点未完成的情况）
    coverage = np.sum(vec_weight.values)
    vec_weight = vec_weight / coverage
    
    # 计算 IPC
    wl_df['cpi'] = 1.0 / wl_df['ipc']
    
    # 矩阵乘法计算加权平均
    # weight_metrics = (1, W) × (W, N) = (1, N)
    weight_metrics = np.matmul(
        vec_weight.values.reshape(1, -1),  # 权重向量 (1, W)
        wl_df.values                        # 性能矩阵 (W, N)
    )
    
    return weight_metrics, wl_df.columns, coverage
```

### 8.2 多输入 Benchmark 加权

某些 benchmark 有多个输入（如 bzip2 有 chicken, combined, liberty 等），需要二次加权：

```python
def proc_bmk(bmk_df: pd.DataFrame, js: dict):
    """多输入 benchmark 的加权"""
    workloads = bmk_df['workload'].unique()
    metric_list = []
    
    # 第一次加权：按采样点加权
    for wl in workloads:
        metrics, cols = proc_input(bmk_df[bmk_df['workload'] == wl], js, wl)
        metric_list.append(metrics)
    
    metrics = np.concatenate(metric_list, axis=0)
    
    # 第二次加权：按指令数加权
    input_dict = {}
    for workload in workloads:
        input_dict[workload] = int(js[workload]['insts'])
    
    input_insts = pd.DataFrame.from_dict(input_dict, orient='index', columns=['insts'])
    vec_weight = input_insts / np.sum(input_insts.values)
    
    weight_metric = np.matmul(vec_weight.values.reshape(1, -1), metrics.values)
    return weight_metric, metrics.columns
```

### 8.3 SPEC 分数计算

SPEC2006 分数计算遵循 SPEC 官方规则：

```
SPECint2006 = geometric_mean(INT_base_copy_times / INT_test_copy_times)
SPECfp2006  = geometric_mean(FP_base_copy_times / FP_test_copy_times)
SPEC2006    = geometric_mean(SPECint2006, SPECfp2006)
```

**简化版本**（XiangShan 使用）：
```
SPEC2006/GHz = geometric_mean(IPC_benchmark / IPC_reference) × 频率因子
```

### 8.4 分数计算脚本

**脚本位置**：`/nfs/home/share/ci-workloads/env-scripts/perf/xs_autorun_multiServer.py`（外部）

```bash
cd $SCRIPTS_HOME/perf
python3 xs_autorun_multiServer.py $CKPT_HOME $CKPT_JSON_PATH \
    --benchmarks "${{ inputs.benchmarks }}" \
    --xs $GITHUB_WORKSPACE --threads 16 --dir $SPEC_DIR --report \
    > "$SCORE_FILE"
```

**输出示例**（`score.txt`）：
```
SPEC CPU2006 Benchmark Result
=============================

Integer Benchmarks:
  perlbench:  IPC=1.23, Score=24.5
  bzip2:      IPC=2.45, Score=18.2
  gcc:        IPC=1.89, Score=21.3
  ...

Floating-Point Benchmarks:
  bwaves:     IPC=3.21, Score=32.1
  gamess:     IPC=2.11, Score=25.4
  ...

SPECint2006/GHz: 22.5
SPECfp2006/GHz: 28.3
SPEC2006/GHz: 25.1
```

---

## 9. GitHub Actions 自动化流程

### 9.1 触发条件

**文件位置**：`.github/workflows/perf-v3.yml`

```yaml
name: Performance Regression V3

on:
  schedule:
    # 每周五 UTC 4:00（北京时间 12:00）运行
    - cron: '0 4 * * 5'
  workflow_dispatch:
    inputs:
      test_branch:
        description: Branch or commit to test
        default: kunminghu-v3
```

### 9.2 完整工作流程

**文件位置**：`.github/workflows/perf-template.yml`

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     GitHub Actions 性能测试流程                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Step 1: 设置环境变量                                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ case "${benchmark_type}" in                                         │   │
│  │   "gcc15-spec06-1.0c")                                              │   │
│  │     CKPT_JSON_PATH=/nfs/.../cluster-0-0.json                       │   │
│  │     CKPT_HOME=/nfs/.../checkpoint-0-0-0                            │   │
│  │     ;;                                                              │   │
│  │ esac                                                                │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  Step 2: 初始化子模块                                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ make init-force                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  Step 3: 构建 EMU                                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ python3 scripts/xiangshan.py --build \                              │   │
│  │   --config "DefaultConfig" \                                        │   │
│  │   --dramsim3 /nfs/.../DRAMsim3 --with-dramsim3 \                   │   │
│  │   --threads 8 \                                                     │   │
│  │   --pgo coremark-2-iteration.bin \                                  │   │
│  │   --llvm-profdata llvm-profdata \                                   │   │
│  │   --trace-fst                                                       │   │
│  │                                                                     │   │
│  │ # 复制 emu 到报告目录                                               │   │
│  │ cp build/emu "$SPEC_DIR/emu-gsim"                                  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  Step 4: 运行 SPEC CPU2006 Checkpoints                                     │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ cd $SCRIPTS_HOME/perf_trigger                                       │   │
│  │ python3 main.py \                                                   │   │
│  │   --gcpt-path $CKPT_HOME \                                          │   │
│  │   --json-path $CKPT_JSON_PATH \                                     │   │
│  │   --emu-path "$SPEC_DIR/emu-gsim" \                                │   │
│  │   --result-path $SPEC_DIR \                                         │   │
│  │   --threads 8 \                                                     │   │
│  │   --server-list "$SERVER_LIST" \                                    │   │
│  │   --run                                                             │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  Step 5: 生成报告                                                           │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ cd $SCRIPTS_HOME/perf                                               │   │
│  │ python3 xs_autorun_multiServer.py $CKPT_HOME $CKPT_JSON_PATH \     │   │
│  │   --xs $GITHUB_WORKSPACE --threads 16 --dir $SPEC_DIR --report \   │   │
│  │   > "$SCORE_FILE"                                                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  Step 6: 输出结果                                                           │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ echo "### :rocket: Performance Test Result" >> $GITHUB_STEP_SUMMARY│   │
│  │ cat "$SCORE_FILE" >> $GITHUB_STEP_SUMMARY                          │   │
│  │                                                                     │   │
│  │ # 提取关键指标                                                      │   │
│  │ INT_SCORE=$(grep "SPECint2006/GHz" "$SCORE_FILE" | awk ... )       │   │
│  │ FP_SCORE=$(grep "SPECfp2006/GHz" "$SCORE_FILE" | awk ... )         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 9.3 Benchmark 类型配置

| 类型 | 覆盖率 | 编译器 | 说明 |
|------|--------|--------|------|
| `gcc15-spec06-0.3c` | 30% | GCC 15 | 快速回归测试 |
| `gcc15-spec06-0.8c` | 80% | GCC 15 | 中等精度 |
| `gcc15-spec06-1.0c` | 100% | GCC 15 | 完整评估 |
| `xscc-spec06-0.3c` | 30% | XSCC | 自定义编译器 |
| `gcc12-spec06-1.0c` | 100% | GCC 12 | 历史版本对比 |

---

## 10. Top-Down 分析方法

### 10.1 概述

Top-Down 分析是一种微架构性能分析方法，将 CPU 停顿分为四层：

```
                     总周期数
                         │
         ┌───────────────┼───────────────┐
         │               │               │
         ▼               ▼               ▼
      Frontend         Backend         Bad Spec
     (取指/译码)      (执行/提交)      (错误推测)
         │               │
    ┌────┼────┐     ┌────┼────┐
    │    │    │     │    │    │
    ▼    ▼    ▼     ▼    ▼    ▼
  ICache ITLB BTB  Div  ROB  Mem
```

### 10.2 分析维度

**代码位置**：`scripts/top-down/configs.py`

1. **Frontend 分析**（前端）
   - `ICacheMissBubble`: I-Cache 缺失
   - `ITLBMissBubble`: I-TLB 缺失
   - `BTBMissBubble`: BTB 缺失
   - `FetchFragBubble`: 取指碎片化

2. **Backend 分析**（后端）
   - `DivStall`: 除法器停顿
   - `RobStall`: ROB 满
   - `IntFlStall`: 整数 freelist 满
   - `LoadDispatchPolicyStall`: Load 发射策略停顿

3. **Memory 分析**（内存）
   - `LoadL1Stall`: L1 缺失
   - `LoadL2Stall`: L2 缺失
   - `LoadL3Stall`: L3 缺失
   - `StoreStall`: 存储停顿

4. **Custom 分析**（自定义）
   - `IntIQFullStallAlu`: 整数队列满
   - `FpIQFullStall`: 浮点队列满
   - `AtomicStall`: 原子操作停顿

### 10.3 运行 Top-Down 分析

```bash
cd scripts/top-down

# 单个基准目录
python3 top_down.py -b /path/to/results -j resources/spec06_rv64gcb_o2_20m.json

# 对比两个目录（base vs ref）
python3 top_down.py \
    -b /path/to/base-results \
    -r /path/to/ref-results \
    -j resources/spec06_rv64gcb_o2_20m.json \
    --base-issue 4 --ref-issue 8
```

### 10.4 输出示例

分析结果以 CSV 文件保存，并生成可视化图表：

```csv
bmk,commitInstr,total_cycles,ipc,NoStall,ICacheMissBubble,DivStall,...
perlbench,20000000,5000000,4.0,0.65,0.12,0.03,...
bzip2,20000000,6000000,3.33,0.58,0.08,0.05,...
```

**可视化**（`draw.py` 生成堆叠柱状图）：
```
perlbench  ████████████████████░░░░░░░░░░  65% NoStall
           ░░░░░░░░░░░░████░░░░░░░░░░░░░░  12% ICache
           ░░░░░░░░░░░░░░░░██░░░░░░░░░░░░   3% Div
           ...

bzip2      ██████████████████░░░░░░░░░░░░  58% NoStall
           ░░░░░░░░░░░░░██░░░░░░░░░░░░░░░   8% ICache
           ░░░░░░░░░░░░░░░░█░░░░░░░░░░░░░   5% Div
           ...
```

---

## 附录 A：关键文件路径索引

| 文件 | 用途 |
|------|------|
| `.github/workflows/perf-template.yml` | 性能测试主流程 |
| `.github/workflows/perf-v3.yml` | 定时触发器 |
| `Makefile` | 顶层构建规则 |
| `difftest/verilator.mk` | Verilator 编译配置 |
| `difftest/emu.mk` | Emulator 构建规则 |
| `difftest/src/test/csrc/emu/emu.cpp` | Emulator 主逻辑 |
| `difftest/src/test/csrc/common/ram.cpp` | 内存模型 |
| `scripts/xiangshan.py` | XiangShan Python 封装 |
| `scripts/top-down/top_down.py` | Top-Down 分析主脚本 |
| `scripts/top-down/configs.py` | 性能计数器配置 |
| `scripts/top-down/utils.py` | 工具函数 |
| `scripts/top-down/resources/spec06_rv64gcb_o2_20m.json` | SimPoint 配置 |

## 附录 B：环境变量

| 变量 | 说明 |
|------|------|
| `NOOP_HOME` | XiangShan 仓库根目录 |
| `NEMU_HOME` | NEMU 仓库目录 |
| `DRAMSIM3_HOME` | DRAMsim3 目录 |
| `CKPT_HOME` | Checkpoint 存储目录 |
| `CKPT_JSON_PATH` | JSON 配置文件路径 |
| `SCRIPTS_HOME` | 脚本目录（NFS） |
| `SPEC_DIR` | 本次运行结果目录 |
