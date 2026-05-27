# XiangShan SimPoint 评估框架关键代码索引

> 本文档提供 XiangShan SimPoint 评估框架的关键代码位置索引，方便快速定位。

---

## 目录

1. [Workflow 文件](#1-workflow-文件)
2. [构建系统](#2-构建系统)
3. [DiffTest 框架](#3-difftest-框架)
4. [性能分析脚本](#4-性能分析脚本)
5. [配置文件](#5-配置文件)
6. [运行时文件](#6-运行时文件)

---

## 1. Workflow 文件

### 1.1 性能测试触发器

**文件**: `.github/workflows/perf-v3.yml`

```yaml
# 定时触发：每周五 UTC 4:00
on:
  schedule:
    - cron: '0 4 * * 5'
  workflow_dispatch:
    inputs:
      test_branch:
        default: kunminghu-v3

# 调用 perf-template.yml
jobs:
  run:
    uses: ./.github/workflows/perf-template.yml
```

**用途**: 定时或手动触发性能测试

### 1.2 性能测试主流程

**文件**: `.github/workflows/perf-template.yml`

**关键步骤**:

| 行号 | 步骤 | 说明 |
|------|------|------|
| 95-199 | Set env | 设置环境变量（CKPT_HOME, CKPT_JSON_PATH） |
| 201-203 | Initialize submodules | `make init-force` |
| 208-224 | Build EMU | `python3 scripts/xiangshan.py --build` |
| 226-237 | Run SPEC CPU2006 checkpoints | `python3 main.py --gcpt-path ... --run` |
| 239-246 | Report score | `python3 xs_autorun_multiServer.py --report` |
| 248-265 | Summary | 输出到 GitHub Summary |

**Benchmark 类型配置** (行 134-189):

```yaml
"gcc15-spec06-0.3c":
  CKPT_JSON_PATH: /nfs/.../json/gcc15-spec06-0.3.json
  CKPT_HOME: /nfs/.../checkpoint-0-0-0

"gcc15-spec06-1.0c":
  CKPT_JSON_PATH: /nfs/.../cluster-0-0.json
  CKPT_HOME: /nfs/.../checkpoint-0-0-0
```

### 1.3 其他 Workflow 文件

| 文件 | 用途 |
|------|------|
| `.github/workflows/perf-trigger.yml` | 手动触发性能测试 |
| `.github/workflows/emu-performance.yml` | EMU 性能测试（PR 触发） |
| `.github/workflows/emu-performance-summary.yml` | 性能总结生成 |

---

## 2. 构建系统

### 2.1 主 Makefile

**文件**: `Makefile`

**关键目标**:

| 目标 | 行号 | 命令 | 说明 |
|------|------|------|------|
| `sim-verilog` | 302 | `mill -i xiangshan.test.runMain top.XiangShanSim` | 生成仿真 Verilog |
| `emu` | 344-345 | `$(MAKE) -C ./difftest emu` | 构建 Emulator |
| `verilog` | 277 | `mill -i xiangshan.runMain top.TopMain` | 生成综合 Verilog |
| `clean` | 304-306 | `rm -rf $(BUILD_DIR)` | 清理 |

**关键变量**:

```makefile
BUILD_DIR = ./build
RTL_DIR = $(BUILD_DIR)/rtl
TOP = $(XSTOP_PREFIX)XSTop
SIM_TOP = SimTop
CONFIG ?= DefaultConfig
NUM_CORES ?= 1
ISSUE ?= E.b
```

### 2.2 DiffTest Makefile

**文件**: `difftest/Makefile`

**关键配置** (行 18-76):

```makefile
SIM_TOP    ?= SimTop
DESIGN_DIR ?= $(NOOP_HOME)
BUILD_DIR  = $(DESIGN_DIR)/build
RTL_DIR = $(BUILD_DIR)/rtl

# DiffTest 源码目录
SIM_CSRC_DIR = $(abspath ./src/test/csrc/common)
DIFFTEST_CSRC_DIR = $(abspath ./src/test/csrc/difftest)

# Verilog 源码目录
VSRC_DIR = $(abspath ./src/test/vsrc/common)
```

**包含的子 Makefile** (行 312-319):

```makefile
include pgo.mk      # Profile Guided Optimization
include emu.mk      # Emulator 构建规则
include vcs.mk      # VCS 仿真
include galaxsim.mk # GalaxSim 仿真
include palladium.mk # Palladium 仿真
include libso.mk    # 共享库
include fpga.mk     # FPGA 综合
```

### 2.3 Verilator 配置

**文件**: `difftest/verilator.mk`

**关键配置** (行 18-100):

```makefile
# Verilator 版本检查
VERILATOR_VER_CMD = $(VERILATOR) --version 2> /dev/null | cut -f2 -d' '

# 编译选项
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

**编译流程** (行 105-119):

```makefile
# 1. 生成 Verilator Makefile
$(VERILATOR_MK): $(SIM_TOP_V) | $(SIM_VSRC) $(VERILATOR_CXXFILES)
    verilator $(VERILATOR_FLAGS_ALL) --Mdir $(@D) $^ $(SIM_VSRC) $(VERILATOR_CXXFILES)

# 2. 编译 C++ 代码
verilator-build-emu:
    $(MAKE) -s VM_PARALLEL_BUILDS=1 OPT_SLOW="-O0" OPT_FAST=$(OPT_FAST) \
        -C $(VERILATOR_BUILD_DIR) -f $(VERILATOR_MK)
```

### 2.4 Emulator 构建规则

**文件**: `difftest/emu.mk`

**关键规则** (行 17-83):

```makefile
EMU_ELF_NAME = emu
EMU_TOP      = SimTop

# C++ 源码目录
EMU_CSRC_DIR   = $(abspath ./src/test/csrc/emu)
EMU_CXXFILES  = $(SIM_CXXFILES) $(shell find $(EMU_CSRC_DIR) -name "*.cpp")
EMU_CXXFLAGS  = $(SIM_CXXFLAGS) -I$(EMU_CSRC_DIR)

# Emulator 构建目标
emu:
ifeq ($(GSIM),1)
    @$(MAKE) emu-gsim
else
    @$(MAKE) emu-verilator
endif
```

### 2.5 PGO 优化

**文件**: `difftest/pgo.mk`

**关键变量**:

```makefile
PGO_WORKLOAD ?= coremark-2-iteration.bin
PGO_MAX_CYCLE ?= 2000000
```

**PGO 流程**:

1. 用 coremark 运行仿真，收集 profile 数据
2. 用 profile 数据重新编译，优化热点代码

---

## 3. DiffTest 框架

### 3.1 Emulator 主逻辑

**文件**: `difftest/src/test/csrc/emu/emu.cpp`

**关键函数**:

| 函数 | 行号 | 说明 |
|------|------|------|
| `Emulator::Emulator()` | 48-214 | 构造函数，初始化仿真器 |
| `Emulator::~Emulator()` | 216-288 | 析构函数，清理资源 |
| `Emulator::single_cycle()` | 327-380 | 执行单个时钟周期 |
| `Emulator::tick()` | 382-400 | 主循环 tick |

**GCPT 恢复** (行 131-138):

```cpp
if (args.gcpt_restore) {
    if (args.overwrite_nbytes_autoset) {
        FILE *fp = fopen(args.gcpt_restore, "rb");
        fseek(fp, 4, SEEK_SET);
        fread(&args.overwrite_nbytes, sizeof(uint32_t), 1, fp);
        fclose(fp);
    }
    overwrite_ram(args.gcpt_restore, args.overwrite_nbytes);
}
```

**执行主循环** (行 382-400):

```cpp
int Emulator::tick() {
    // ... 显示屏幕等
    
    // 执行单个周期
    single_cycle();
    
    // 检查终止条件
    if (cycles >= max_cycles) {
        trapCode = STATE_LIMIT_EXCEEDED;
    }
    
    return trapCode;
}
```

### 3.2 Emulator 头文件

**文件**: `difftest/src/test/csrc/emu/emu.h`

**关键类定义**:

```cpp
class Emulator final : public DUT {
private:
    Simulator *dut_ptr;
    CommonArgs args;
    uint64_t cycles;
    int trapCode;
    uint64_t core_max_instr[NUM_CORES];
    
public:
    Emulator(int argc, const char *argv[]);
    ~Emulator();
    uint64_t execute(uint64_t max_cycle, uint64_t max_instr);
    int tick();
    int is_finished();
    int is_good();
    bool is_good_trap();
};
```

### 3.3 主入口

**文件**: `difftest/src/test/csrc/emu/main.cpp`

```cpp
int main(int argc, const char *argv[]) {
    common_init_without_assertion(argv[0]);
    
    auto emu = new Emulator(argc, argv);
    common_enable_assert();
    
    // 主仿真循环
    while (!emu->is_finished()) {
        emu->tick();
    }
    
    bool is_good = emu->is_good();
    delete emu;
    return !is_good;
}
```

### 3.4 命令行参数

**文件**: `difftest/src/test/csrc/common/args.h`

**关键参数**:

```cpp
struct CommonArgs {
    uint32_t reset_cycles = 50;
    uint32_t seed = 0;
    uint64_t max_cycles = -1;
    uint64_t max_instr = -1;
    uint64_t warmup_instr = -1;
    uint64_t stat_cycles = -1;
    uint64_t log_begin = 0, log_end = -1;
    uint64_t overwrite_nbytes = 0xe00;
    
    const char *image = "/dev/zero";
    const char *gcpt_restore = nullptr;
    const char *snapshot_path = nullptr;
    const char *ram_size = nullptr;
    const char *cst_file = nullptr;
    const char *flash_bin = nullptr;
    
    bool enable_diff = true;
    bool enable_fork = false;
    bool enable_runahead = false;
};
```

### 3.5 内存模型

**文件**: `difftest/src/test/csrc/common/ram.cpp`

**关键函数**:

| 函数 | 行号 | 说明 |
|------|------|------|
| `init_ram()` | 35-37 | 初始化内存 |
| `MmapMemory::MmapMemory()` | 253-296 | 使用 mmap 分配内存 |
| `overwrite_ram()` | 441-446 | 加载 checkpoint 到内存 |
| `difftest_ram_read()` | 305-321 | DiffTest 内存读 |
| `difftest_ram_write()` | 323-335 | DiffTest 内存写 |
| `pmem_read()` | 337-343 | 物理内存读 |
| `pmem_write()` | 345-351 | 物理内存写 |

**内存基地址** (在 `common.h` 中定义):

```cpp
#define PMEM_BASE 0x80000000ULL
#define DEFAULT_EMU_RAM_SIZE (8ULL * 1024 * 1024 * 1024)  // 8GB
```

### 3.6 DiffTest 接口

**目录**: `difftest/src/test/vsrc/common/`

**Verilog 接口文件**:

| 文件 | 说明 |
|------|------|
| `DifftestInstrCommit.v` | 指令提交接口 |
| `DifftestTrapEvent.v` | 陷阱事件接口 |
| `DifftestLoadEvent.v` | Load 事件接口 |
| `DifftestStoreEvent.v` | Store 事件接口 |
| `DifftestCSRState.v` | CSR 状态接口 |
| `DifftestArchIntRegState.v` | 整数寄存器状态接口 |

### 3.7 XiangShan Python 封装

**文件**: `scripts/xiangshan.py`

**关键类**:

```python
class XSArgs(object):
    noop_home = os.path.join(os.path.dirname(script_path), "..")
    nemu_home = os.path.join(noop_home, "../NEMU")
    am_home = os.path.join(noop_home, "../nexus-am")
    dramsim3_home = os.path.join(noop_home, "../DRAMsim3")

class XiangShan(object):
    def build_emu(self):
        # 构建 Emulator
        
    def run_emu(self, workload):
        # 运行 Emulator
        
    def run_ci(self, test):
        # 运行 CI 测试
```

**关键命令**:

```bash
# 构建 Emulator
python3 scripts/xiangshan.py --build \
    --config "DefaultConfig" \
    --dramsim3 /path/to/DRAMsim3 --with-dramsim3 \
    --threads 8 \
    --pgo coremark-2-iteration.bin \
    --llvm-profdata llvm-profdata \
    --trace-fst

# 运行 Emulator
python3 scripts/xiangshan.py \
    --workload /path/to/checkpoint.gz \
    --diff ./ready-to-run/riscv64-nemu-interpreter-so \
    --max-instr 20000000
```

---

## 4. 性能分析脚本

### 4.1 Top-Down 分析主脚本

**文件**: `scripts/top-down/top_down.py`

**关键函数**:

| 函数 | 行号 | 说明 |
|------|------|------|
| `batch()` | 16-59 | 批量提取性能计数器 |
| `proc_input()` | 62-95 | 单个 workload 加权计算 |
| `proc_bmk()` | 98-118 | 多输入 benchmark 加权 |
| `compute_weighted_metrics()` | 120-158 | 计算加权指标 |
| `run_one()` | 161-166 | 运行单个基准目录 |

**加权计算核心** (行 91):

```python
# 矩阵乘法计算加权平均
weight_metrics = np.matmul(
    vec_weight.values.reshape(1, -1),  # 权重向量 (1, W)
    wl_df.values                        # 性能矩阵 (W, N)
)
```

**使用方法**:

```bash
# 单个基准目录
python3 top_down.py -b /path/to/results -j resources/spec06_rv64gcb_o2_20m.json

# 对比两个目录
python3 top_down.py \
    -b /path/to/base-results \
    -r /path/to/ref-results \
    -j resources/spec06_rv64gcb_o2_20m.json \
    --base-issue 4 --ref-issue 8
```

### 4.2 性能计数器配置

**文件**: `scripts/top-down/configs.py`

**关键配置**:

**输出路径** (行 1-8):

```python
CSV_BASE = 'results/results_base.csv'
CSV_REF = 'results/results_ref.csv'
JSON_FILE = 'resources/spec06_rv64gcb_o2_20m.json'
OUT_BASE = 'results/results-weighted_base.csv'
OUT_REF = 'results/results-weighted_ref.csv'
```

**分析开关** (行 10-16):

```python
TOTAL_ANALYSE = True
BACKEND_ANALYSE = True
FRONTEND_ANALYSE = True
MEM_ANALYSE = True
CUSTOM_ANALYSE = True
```

**性能计数器正则表达式** (行 308-398):

```python
XS_CORE_PREFIX = r'\[PERF\s*\]\[time=\s*\d+\].*?\.core'

targets = {
    # 基础计数器
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
    
    # ... 更多计数器
}
```

**SPEC Benchmark 列表** (行 401-426):

```python
spec_bmks = {
    '06': {
        'int': [
            'perlbench', 'bzip2', 'gcc', 'mcf', 'gobmk',
            'hmmer', 'sjeng', 'libquantum', 'h264ref',
            'omnetpp', 'astar', 'xalancbmk',
        ],
        'float': [
            'bwaves', 'gamess', 'milc', 'zeusmp', 'gromacs',
            'cactusADM', 'leslie3d', 'namd', 'dealII', 'soplex',
            'povray', 'calculix', 'GemsFDTD', 'tonto', 'lbm',
            'wrf', 'sphinx3',
        ],
        'high_squash': ['astar', 'bzip2', 'gobmk', 'sjeng'],
    },
}
```

### 4.3 工具函数

**文件**: `scripts/top-down/utils.py`

**关键函数**:

| 函数 | 行号 | 说明 |
|------|------|------|
| `xs_get_stats()` | 13-55 | 从日志提取性能计数器 |
| `workload_point_frompath()` | 58-77 | 从路径解析 workload 和 point |
| `glob_stats()` | 89-119 | 查找所有统计文件 |
| `find_file_in_maze()` | 122-135 | 递归查找文件 |

**`xs_get_stats()` 实现** (行 13-55):

```python
def xs_get_stats(stat_file: str, targets: list) -> dict:
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

### 4.4 可视化脚本

**文件**: `scripts/top-down/draw.py`

**用途**: 生成堆叠柱状图

**使用方法**:

```python
from draw import draw

draw(
    ['results/results-weighted_base.csv', 'results/results-weighted_ref.csv'],
    ['BASE', 'REF']
)
```

---

## 5. 配置文件

### 5.1 SimPoint 配置

**文件**: `scripts/top-down/resources/spec06_rv64gcb_o2_20m.json`

**格式**:

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

**示例** (astar_biglakes):

```json
{
  "astar_biglakes": {
    "insts": "320330109413",
    "points": {
      "10080000000": "0.000500",
      "122060000000": "0.244818",
      "181920000000": "0.044143",
      "22020000000": "0.168269",
      "226920000000": "0.081793",
      "243500000000": "0.219718",
      "259480000000": "0.008367",
      "314000000000": "0.126811",
      "3180000000": "0.016359",
      "5500000000": "0.013799",
      "67320000000": "0.065684",
      "9940000000": "0.009740"
    }
  }
}
```

### 5.2 DRAMsim3 配置

**文件**: `DRAMsim3/configs/XiangShan.ini`

**关键配置**:

```ini
[MemorySystem]
; DRAM 配置
channels = 1
ranks = 1
banks = 8
rows = 65536
columns = 1024

[Timing]
; 时序参数
tCAS = 11
tRCD = 11
tRP = 11
tRAS = 28
```

---

## 6. 运行时文件

### 6.1 NEMU 参考模型

**文件**: `ready-to-run/riscv64-nemu-interpreter-so`

**用途**: DiffTest 参考模型，用于对比验证

**使用**:

```bash
./build/emu -i checkpoint.gz \
    --diff ./ready-to-run/riscv64-nemu-interpreter-so
```

### 6.2 Checkpoint 文件

**位置**: `/nfs/home/share/checkpoints_profiles/`

**格式**: `.gz` 或 `.zstd` 压缩的 GCPT 文件

**命名规则**:

```
{指令地址}.gz
例如: 122060000000.gz  (指令地址 1220.6 亿)
```

### 6.3 结果文件

**位置**: `$SPEC_DIR/`

**文件**:

| 文件 | 说明 |
|------|------|
| `emu-gsim` | Emulator 可执行文件 |
| `riscv64-nemu-interpreter-so` | NEMU 共享库 |
| `benchmark/point/simulator_err.txt` | 性能计数器输出 |
| `benchmark/point/simulator_out.txt` | 执行日志 |
| `score-gcc15-spec06-1.0c.txt` | SPEC 分数 |

---

## 附录 A：快速查找指南

### 查找 Emulator 初始化代码

```
difftest/src/test/csrc/emu/emu.cpp
  └── Emulator::Emulator() (行 48)
      ├── GCPT 恢复 (行 131)
      ├── 内存初始化 (行 107)
      ├── DiffTest 初始化 (行 169)
      └── LightSSS 初始化 (行 200)
```

### 查找性能计数器输出

```
scripts/top-down/configs.py
  └── targets 字典 (行 310)
      ├── commitInstr (行 396)
      ├── total_cycles (行 397)
      ├── NoStall (行 311)
      └── ...
```

### 查找加权计算逻辑

```
scripts/top-down/top_down.py
  └── proc_input() (行 62)
      ├── 加载权重 (行 73-79)
      ├── 矩阵乘法 (行 91)
      └── 返回结果 (行 95)
```

### 查找 Verilator 编译配置

```
difftest/verilator.mk
  └── VERILATOR_FLAGS_ALL (行 79)
      ├── --exe (行 80)
      ├── --cc (行 81)
      ├── +define+VERILATOR=1 (行 82)
      └── -o $(VERILATOR_TARGET) (行 99)
```

---

## 附录 B：环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `NOOP_HOME` | XiangShan 仓库根目录 | `.` |
| `NEMU_HOME` | NEMU 仓库目录 | `../NEMU` |
| `DRAMSIM3_HOME` | DRAMsim3 目录 | 无 |
| `CKPT_HOME` | Checkpoint 存储目录 | 无 |
| `CKPT_JSON_PATH` | JSON 配置文件路径 | 无 |
| `SCRIPTS_HOME` | 脚本目录（NFS） | `/nfs/home/share/ci-workloads/env-scripts` |
| `SPEC_DIR` | 本次运行结果目录 | 自动生成 |
| `SERVER_LIST` | 运行服务器列表 | `all` |

---

## 附录 C：常用命令

### 构建命令

```bash
# 初始化子模块
make init-force

# 生成仿真 Verilog
make sim-verilog CONFIG=DefaultConfig

# 构建 Emulator
make emu CONFIG=DefaultConfig WITH_DRAMSIM3=1

# 清理
make clean
```

### 运行命令

```bash
# 运行单个 checkpoint
./build/emu -i checkpoint.gz \
    --diff ./ready-to-run/riscv64-nemu-interpreter-so \
    --max-instr 20000000

# 运行性能测试
python3 scripts/xiangshan.py --build \
    --config "DefaultConfig" \
    --with-dramsim3

# 运行 Top-Down 分析
cd scripts/top-down
python3 top_down.py -b /path/to/results -j resources/spec06_rv64gcb_o2_20m.json
```

### 调试命令

```bash
# 查看 Emulator 帮助
./build/emu --help

# 启用波形
./build/emu -i checkpoint.gz --enable-waveform

# 启用 DiffTest 详细输出
./build/emu -i checkpoint.gz --diff ./ready-to-run/riscv64-nemu-interpreter-so
```
