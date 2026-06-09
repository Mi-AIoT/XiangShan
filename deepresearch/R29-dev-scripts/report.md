# XiangShan Development Scripts 深度研究报告 (R29)

## 1. 概述

XiangShan 项目配备了一套完整的开发脚本工具链，覆盖从 RTL 生成、仿真构建、性能分析、参数优化到调试验证的全流程。这些脚本主要位于 `scripts/` 和 `debug/` 目录下，以 Python 和 Shell 语言实现，构成处理器开发的核心基础设施。本报告对每个关键脚本进行深入分析，涵盖架构设计、核心功能、使用方式和相互关系。

---

## 2. xiangshan.py -- 主工作流编排脚本

**文件路径**: `scripts/xiangshan.py`

### 2.1 架构概览

`xiangshan.py` 是 XiangShan 项目的主入口脚本，承担整个开发流程的编排角色。它采用面向对象设计，核心包含三个主要类：

- **`XSArgs`** -- 环境参数管理器，负责统一管理所有路径变量（NOOP_HOME、NEMU_HOME、AM_HOME、DRAMSIM3_HOME、RVTEST_HOME）以及 Chisel 编译参数、Makefile 参数和仿真器参数。
- **`XiangShan`** -- 工作流执行引擎，封装了 Verilog 生成、仿真器构建、CI 测试执行等全部操作。
- **`get_free_cores()`** -- NUMA-aware 的空闲核心检测函数，用于自动寻找可用 CPU 核心进行并行仿真。

### 2.2 参数体系

脚本通过 `argparse` 提供了丰富的命令行参数，分为四大类：

**动作参数**（Action Arguments）:
- `--generate`: 生成 Verilog RTL
- `--vcs-gen`: 生成用于 Synopsys VCS 的 sim-verilog
- `--build`: 构建 Verilator 或 gsim 仿真器 (emu)
- `--vcs-build`: 构建 VCS simv
- `--ci` / `--ci-vcs`: 运行 CI 测试套件
- `--clean`: 清理工作目录

**Chisel 编译参数**:
- `--enable-log`: 启用日志输出
- `--num-cores`: 指定核心数量
- `--config` / `--yaml-config`: 配置文件选择
- `--xprop`: 启用 VCS X-propagation

**Makefile 参数**:
- `--release`: Release 模式编译
- `--threads` / `--make-threads`: 并行线程数控制
- `--trace` / `--trace-fst`: 波形转储使能
- `--with-constantin` / `--with-dramsim3`: 外围模块使能
- `--emulator`: 选择仿真后端 (`verilator` 或 `gsim`)

**仿真运行参数**:
- `--max-instr`: 最大指令数限制
- `--seed`: 随机种子
- `--diff`: 差分测试参考（NEMU 或 Spike）
- `--numa`: NUMA 绑核运行
- `--dump-db`: 启用 ChiselDB 性能数据导出
- `--pgo` / `--llvm-profdata`: Profile-Guided Optimization 支持
- `--gcpt-restore-bin`: Checkpoint 恢复二进制
- `--flash`: Flash 镜像路径

### 2.3 CI 测试框架

`XiangShan` 类实现了完整的 CI 测试分发机制。`run_ci()` 和 `run_ci_vcs()` 方法根据测试名称从预定义的 workload 映射表中查找对应的测试二进制文件，然后逐一执行：

- **cputest**: 基础 CPU 功能测试（来自 nexus-am）
- **riscv-tests**: RISC-V 标准 ISA 测试（rv64ui/rv64um/rv64ua/rv64uf/rv64ud/rv64mi）
- **misc-tests**: 杂项测试，包含 bitmanip、crypto、PMP、ASID、Svinval、Svpbmt、Svnapot、Zawrs、Zcb 等扩展
- **rvh-tests**: H-Extension（Hypervisor）测试，含 pointer masking 测试
- **rvv-test**: V-Extension（向量）测试（当前因向量重构暂时禁用）
- **f16_test**: Half-precision 浮点测试
- **iopmp-test**: IO-PMP 测试
- **mc-tests**: 多核一致性测试
- **nodiff-tests**: 无差分测试模式（如 cacheop 测试）
- **性能测试**: microbench、coremark
- **SPEC 工作负载**: Linux 启动、SPEC CPU2006/2017 checkpoint

CI 测试失败时，脚本会自动将波形文件（VCD/FST）、仿真器二进制、RTL 源文件和 ChiselDB 数据复制到指定波形目录。

### 2.4 NUMA 核心调度

`get_free_cores()` 实现了一套智能 CPU 核心检测机制：通过 `psutil` 采集每核利用率，滑动窗口扫描连续空闲核心组，要求窗口内平均利用率低于 30%、无单核利用率超过 80% 的热点核心、且无其他进程绑定。检测到候选核心后，随机等待 1-30 秒后重新验证以避免竞争冲突，同时根据核心位置自动推断 NUMA node。

---

## 3. parser.py -- RTL 解析与 SRAM 处理

**文件路径**: `scripts/parser.py`

### 3.1 核心设计

`parser.py` 是 XiangShan 的 RTL 分析和后处理引擎，提供模块级 Verilog 解析、子模块层次遍历、SRAM 配置生成与替换、filelist 生成等功能。该脚本依赖 `xlsxwriter` 库生成 Excel 报表。

### 3.2 Verilog 模型体系

脚本定义了三个核心类：

**`VIO`** -- I/O 端口模型：
解析 `input`/`output` 声明，提取方向（direction）、位宽（width）和信号名称（name）。支持位宽解析如 `[63:0]`。

**`VModule`** -- 模块模型：
- 使用正则表达式匹配 `module` 声明、I/O 端口、子模块实例化
- 支持多行子模块实例化（`submodule_re_multiline`）
- 实现 `replace_with_macro()` 方法，在模块体中插入 `` `ifdef MACRO`` / `` `else`` / `` `endif`` 条件编译块
- 自动识别 Difftest 模块，用 `` `ifndef SYNTHESIS`` 包裹非综合代码
- 对 RenameTable 和 SynRegfileSlice 中的 debug 信号进行特殊处理（综合时置零）

**`VCollection`** -- 模块集合管理器：
- `load_modules()`: 从 Verilog 文件中递归解析所有模块定义
- `get_module()`: 按名称查找模块，支持 `with_submodule` 参数递归获取所有子模块（用于层次化导出）
- `dump_to_file()`: 将模块及其子模块树导出为独立 Verilog 文件
- `dump_negedge_modules_to_file()`: 导出下降沿数据模块列表（`NegedgeDataModule_*`），用于综合约束
- `count_instances()`: 递归计算子模块在顶层中的实例化数量

### 3.3 SRAM 配置与替换

**`SRAMConfiguration`** 类实现了完整的 SRAM 生命周期管理：

**命名规范解析**: SRAM 模块名遵循 `sram_array_(\d)p(\d+)x(\d+)m(\d+)(_multicycle|)(_repair|)` 格式，其中：
- `\d`p`: 端口数（1=单端口，2=双端口）
- `(\d+)x(\d+)`: depth x width
- `m(\d+)`: mask granularity
- `_multicycle`: 多周期访问支持
- `_repair`: repair 功能支持

**端口类型映射**:
- SINGLE_PORT (0): rw
- SINGLE_PORT_MASK (1): mrw
- DUAL_PORT (2): write,read
- DUAL_PORT_MASK (3): mwrite,read

**Foundry SRAM Wrapper 生成**: `get_foundry_sram_wrapper()` 方法生成面向特定 Foundry（如 SouthLake）的 SRAM 包装器 Verilog 代码，包含：
- MBIST 测试端口（IP_RESET_B、TRIM_FUSE、SLEEP_FUSE、FSCAN 系列）
- 时钟门控端口（WRAPPER_CLK_EN / WRAPPER_WR_CLK_EN + WRAPPER_RD_CLK_EN）
- Repair 端口（ROW_REPAIR、COL_REPAIR、BISR 接口）
- 功能端口映射（CK/WCK、A/RA/WA、WEN/REN、D/Q、WM）

**`replace_sram()` 函数**: 读取 SRAM 配置文件，对每个 SRAM 模块使用 `` `ifdef FOUNDRY_MEM`` 宏替换为 Foundry wrapper 实例化代码。

### 3.4 主流程

脚本主流程 (`__main__`) 为：
1. 加载 build 目录下所有 `.v`/`.sv` 文件
2. 创建 VCollection 并解析模块层次
3. 导出顶层模块及其子模块到独立文件
4. 生成 filelist（`.f` 文件）
5. 生成 SRAM 配置文件和 Excel 报表
6. 可选：执行 SRAM 替换和 MBIST/Scan 控制器替换

命令行参数包括：`--top`（顶层模块）、`--prefix`（模块前缀，如 `bosc_`）、`--ignore`（忽略的模块）、`--sram-replace`、`--mbist-scan-replace`、`--no-filelist` 等。

---

## 4. constantHelper.py -- 遗传算法参数优化

**文件路径**: `scripts/constantHelper.py`

### 4.1 设计目标

`constantHelper.py` 实现了基于遗传算法（Genetic Algorithm, GA）的硬件参数自动优化工具。它通过反复编译运行仿真器，根据性能指标自动搜索最优的 ConstantIn 参数配置，无需手动逐一调参。

### 4.2 配置体系

工具通过 JSON 配置文件定义优化参数，包含：

**待优化常量（constants）**: 每个常量定义了：
- `name`: 常量名称（如 `block_cycles_cache_0`）
- `width`: 位宽
- `guide`: 搜索空间上界
- `init`: 初始值（保留在第一代种群中）

**优化目标（opt_target）**: 定义了性能指标及其优化方向：
- `policy: "max"`: 最大化（如 `successfully_forward_channel_D`）
- `policy: "min"`: 最小化（如 `load_miss_penalty_to_use`）
- `baseline`: 基准值，用于计算 fitness 差值

**GA 超参数**:
- `population_num`: 种群大小（必须为偶数）
- `iteration_num`: 迭代代数
- `crossover_rate`: 交叉率
- `mutation_rate`: 变异率
- `concurrent_emu`: 并发仿真数量
- `emu_threads`: 每个仿真器使用的线程数

### 4.3 遗传算法实现

**`Solution` 类**实现了完整的 GA 流程：

1. **初始种群生成** (`genFirstPopulation`): 随机生成 `population_num` 个候选解，每个解为常量名-值对列表。初始值个体被强制包含在第一代中作为锚点。

2. **适应度评估** (`profilling_fitness`): 运行仿真器，解析 stderr 输出中的性能计数器，根据 opt_target 的 max/min 策略计算加权 fitness 值。

3. **选择** (`genNextPop`): 按 fitness 降序排序，保留前半部分作为下一代精英。

4. **交叉** (`crossover`): 对后半部分个体进行位段交叉（single-point crossover with mask），随机选择交叉对象、掩码长度和偏移位置。

5. **变异** (`mutation`): 对每个常量的值执行随机位翻转（bit flip），变异概率由 `mutation_rate` 控制。

### 4.4 并行执行

`RunContext` 类管理仿真器执行：
- 通过 `echo` 管道将参数列表传入 emu 的 `--cst-file stdin` 接口
- 支持 NUMA 绑核运行（`numactl -m node -C start-end`）
- 输出/错误日志按迭代-个体编号命名，存储在标记目录中
- 通过 `psutil` 监测 CPU 利用率以判断空闲核心

最终输出全局最优解及其 fitness 值。

---

## 5. Top-Down 分析框架

**文件路径**: `scripts/top-down/` (top_down.py, draw.py, configs.py, utils.py)

### 5.1 框架概述

Top-Down 分析是处理器微架构性能分析的核心方法论，源自 CHIPS Alliance 的 TAGE 方法。该框架将 IPC（Instructions Per Cycle）分解为各种 stall/bubble 因素，以堆叠条形图（stacked bar chart）的形式展示每个 benchmark 的性能瓶颈分布。

### 5.2 configs.py -- 配置中心

定义了全局配置和性能指标映射：

**性能计数器提取正则** (`targets`): 从 `simulator_err.txt` 中匹配 `[PERF]` 日志行，提取约 50+ 个微架构事件计数器，涵盖：
- Frontend: OverrideBubble, FtqFullStall, ICacheMissBubble, ITLBMissBubble, BTBMissBubble, TAGEMissBubble, SCMissBubble 等
- Backend: NoStall, DivStall, IntNotReadyStall, RobStall, LqStall, SqStall 等
- Memory: LoadTLBStall, LoadL1Stall, LoadL2Stall, LoadL3Stall, LoadMemStall, StoreStall 等
- Recovery: ControlRecoveryStall, MemVioRecoveryStall 等

**五级重命名映射** (Rename Maps):
1. **xs_coarse_rename_map** -- 粗粒度分析：合并为 Frontend、Backend、Load、Store、BadSpec、Core 等大类
2. **xs_frontend_rename_map** -- 前端细粒度：ICacheBubble、ITLBTAGEMiss、OverrideBubble 等
3. **xs_backend_rename_map** -- 后端细粒度：ExecStall、RobStall、IQFullStall、DispatchPolicy、FreelistStall 等
4. **xs_mem_rename_map** -- 内存细粒度：TLB/Stall/L2/L3/Mem 绑定、Store/Sq/Lq Stall、MSHR Replay 等
5. **xs_custom_rename_map** -- 自定义分析：将 IQFullStall 按功能单元分组，DispatchPolicy 按带宽/均衡分组

映射机制支持 `Merge` 前缀实现自动合并（如 `MergeFrontend` 将多个前端事件求和）。

**Benchmark 列表**: 内置 SPEC CPU2006 整数和浮点 benchmark 分类，支持 `INT_ONLY`/`FP_ONLY` 过滤和自定义 benchmark_list。

### 5.3 utils.py -- 统计数据提取

`xs_get_stats()` 函数从 `simulator_err.txt` 中提取性能计数器：
- 支持正则匹配和累加（如取最后 N 个采样的平均值）
- 自动计算 `ipc = commitInstr / total_cycles`
- 对缺失的键值发出警告并补零

`glob_stats()` 实现递归目录搜索，自动探测 workload/point 目录布局（支持一层和两层目录结构），处理 checkpoint 冲突。

### 5.4 top_down.py -- 数据处理引擎

**`batch()` 函数**: 并行处理所有 benchmark 的统计数据：
- 使用 `multiprocessing.Process` 实现并行提取
- 通过信号量（`threading.Semaphore`）限制并发数为 CPU 核心数
- 跳过未完成的任务（检查 `HIT GOOD TRAP` 或 `EXCEEDING CYCLE/INSTR LIMIT`）

**加权指标计算**:
- `proc_input()`: 使用矩阵乘法 `weight = vec_weight @ matrix_perf` 计算加权性能指标
- `proc_bmk()`: 以指令数为权重，在多个 input 间进行加权
- 支持 issue width 缩放（`--base-issue` / `--ref-issue`），当两个配置的发射宽度不同时，自动缩放使比较公平

### 5.5 draw.py -- 可视化

`draw()` 函数生成堆叠条形图：
- 支持 1-2 个配置的对比显示
- 按 CPI 降序排列 benchmark
- 使用 `tab10` colormap 和 hatching 区分多个配置
- 支持细粒度重命名模式（fine-grain rename）
- 输出为高分辨率 PNG（200 DPI）
- 按 FRONTEND_ANALYSE/BACKEND_ANALYSE/MEM_ANALYSE/CUSTOM_ANALYSE 分别生成多张分析图

### 5.6 典型使用

```bash
cd scripts/top-down
python3 top_down.py -b /path/to/base-stats -r /path/to/ref-stats \
    -j resources/spec06_rv64gcb_o2_20m.json \
    --base-issue 6 --ref-issue 8
```

---

## 6. Cache 分析工具

**文件路径**: `scripts/cache/`

### 6.1 parseAddr.py -- 地址解析

地址转换工具，支持三种缓存层次间的地址映射：
- **tl_test**: 3-bit tag, 7-bit set, 0-bit bank（测试用 L1）
- **sys_l2**: 19-bit tag, 9-bit set, 2-bit bank（XiangShan L2 Cache）
- **sys_l3**: 16-bit tag, 12-bit set, 2-bit bank（XiangShan L3 Cache）

统一 block offset 为 6 bits（64B cache line）。使用示例：
```bash
# 全地址转换为 L2 tag/set/bank
python3 parseAddr.py 02 0xc5170cc0
# 输出: ('0x628b', '0x10c', '0x3')
```

### 6.2 l2DB_helper.py -- L2 Cache 数据库查询

从 ChiselDB 导出的 SQLite 数据库中查询 L2 Cache 事务日志：
- **`log` 命令**: 查询 TLLOG 表，通过 `convert_tllog.sh` 格式化显示 TileLink 事务
- **`mp` 命令**: 查询 L2MP 表，通过 `convert_mp.sh` 格式化显示 MainPipe 事务
- 支持 SQL WHERE 条件过滤（如 `STAMP > 10000`）
- 支持 `--last` 显示最近 N 条记录
- 自动定位 build 目录下最新的 `.db` 文件

### 6.3 convert_tllog.sh -- TileLink 日志格式化

使用 `awk` 将 TileLink 原始日志转换为可读格式：
- **通道解码**: A (PutFullData/PutPartialData/ArithmeticData/LogicalData/Get/Hint/AcquireBlock/AcquirePerm)、B (Probe)、C (AccessAck/ProbeAck/Release)、D (Grant/GrantData/ReleaseAck)、E (GrantAck)
- **参数解码**: Grow (NtoB/NtoT/BtoT)、Cap (toT/toB/toN)、Report (TtoB/TtoN/BtoN)
- 格式化输出：时间戳、通道名、操作名、地址、数据、source/sink

### 6.4 convert_mp.sh -- MainPipe 日志格式化

格式化 L2 MainPipe 事务日志：
- 支持 MSHR 任务与 Channel 任务区分
- 解码目录状态（INVALID/BRANCH/TRUNK/TIP）和 dirty 位
- 显示 self_dir/self_tag/client_dir/client_tag 类型
- 格式化 tag/set 地址、way 信息

### 6.5 convert_dir.sh -- 目录状态解码

解码 L2 Cache 目录条目的状态信息：
- 客户端状态：INVALID (0)、BRANCH (1)、TRUNK (2)、TIP (3)
- 自身状态和 dirty 位提取
- 使用位运算提取编码在整数中的目录信息

---

## 7. Coverage 覆盖率工具

**文件路径**: `scripts/coverage/`

### 7.1 statistics.py -- 覆盖率预处理

对 Verilog 仿真覆盖率报告进行预处理，移除非综合代码和随机初始化代码的覆盖率数据：
- **SYNTHESIS 过滤**: 追踪 `` `ifndef SYNTHESIS`` 嵌套层级，将块内覆盖率数据替换为空白
- **RANDOMIZE_REG_INIT 过滤**: 移除寄存器随机初始化代码的覆盖率
- **RANDOMIZE_MEM_INIT 过滤**: 移除内存随机初始化代码的覆盖率
- 使用嵌套层级计数器处理宏嵌套

### 7.2 coverage.py -- 覆盖率分析

完整的覆盖率层次分析工具：

**注解分类**:
- `LINE_COVERRED` / `NOT_LINE_COVERRED`: 行覆盖状态
- `TOGGLE_COVERRED` / `NOT_TOGGLE_COVERRED`: 翻转覆盖状态

**模块层次构建** (`get_modules()`): 解析 `module/endmodule` 声明和子模块实例化，构建树形模块层次，标记 ROOT/NODE/BLACKBOX 类型。

**覆盖率计算**:
- `self_coverage`: 模块自身（不含子模块）的覆盖率
- `tree_coverage`: 模块及其所有子模块的聚合覆盖率
- 使用 DFS 递归聚合子模块覆盖率

**输出格式**: 按覆盖率排序打印 Line/Toggle 的 SelfCoverage 和 TreeCoverage，支持树形结构展示。

---

## 8. Performance Counter 性能计数工具

### 8.1 perfcct.py -- 指令生命周期可视化

**文件路径**: `scripts/perfcct.py`

从 ChiselDB SQLite 数据库中提取 `LifeTimeCommitTrace` 表数据，可视化每条指令的流水线生命周期：

**流水线阶段映射**:
| 列名 | 符号 | 含义 |
|------|------|------|
| AtFetch | f | 取指 |
| AtDecode | d | 译码 |
| AtRename | r | 重命名 |
| AtDispQue | D | 分发队列 |
| AtIssueQue | i | 发射队列 |
| AtIssueArb | a | 发射仲裁 |
| AtIssueReadReg | g | 读寄存器 |
| AtFU | e | 功能单元执行 |
| AtBypassVal | b | 旁路转发 |
| AtWriteVal | w | 写值 |
| AtCommit | c | 提交 |

**可视化模式**:
- **Visual 模式** (`-v`): 以字符时间线显示每条指令的流水线阶段分布，支持单行和多行显示
- **Text 模式**: 以键值对形式显示各阶段 tick 值
- 支持反汇编（`-d` 参数，支持 `spike-dasm` 和 `riscv64-linux-gnu-objdump`）
- 支持投机执行显示（`-s`）
- 可调节显示缩放比例（`-z`）和采样周期（`-p`）

### 8.2 sram_size_collect.py -- SRAM 面积统计

**文件路径**: `scripts/sram_size_collect.py`

扫描指定目录下的 SRAM 仿真模型文件（`array_*_ext.v`），提取每个 SRAM 的深度（Y）、宽度（Z）、mask 参数（M/N）和类型（T），按参数排序后输出到时间戳命名的文件中。用于芯片面积评估。

---

## 9. Rolling 性能分析工具

**文件路径**: `scripts/rolling.py`

### 9.1 设计概述

`rolling.py` 是一个基于 SQLite 数据库的 rolling counter 分析框架，提供四种操作模式：plot（绘图）、diff（对比）、list（列表）、corr（相关性分析）。

### 9.2 数据模型

数据存储在 ChiselDB 的 SQLite 文件中，表名格式为 `{perf_name}_rolling_{hart}`，包含 XAXISPT（X 轴位置）、YAXISPT（增量值）和 STAMP（时间戳）列。

### 9.3 操作模式

**plot**: 从单个数据库绘制一条或多条 rolling counter 的时间序列图。支持聚合（`-A`）和 interval 模式（`-I`）。

**diff**: 在多个数据库中绘制相同 counter 的对比图，每个数据库的曲线自动添加 `@db_name` 后缀。

**list**: 列出数据库中所有 rolling table 的统计信息（样本数、范围、时间戳范围）。

**corr**: 计算 counter 与参考 counter（默认 IPC）的相关性排名。支持三种对齐方式：
- `index`: 按索引对齐
- `xaxis`: 按 X 轴交集对齐
- `progress`: 按进度百分比插值对齐

输出相关系数排名，支持 CSV 导出。

---

## 10. XSPdb -- 交互式仿真调试器

**文件路径**: `scripts/xspdb/`

### 10.1 架构设计

XSPdb 是基于 Python `pdb` 框架构建的 XiangShan 交互式调试器，集成了仿真控制、差分测试、波形管理和 TUI 界面。

**`XSPdb` 类**继承自 `pdb.Pdb`，核心功能包括：

**内存和差分测试初始化**:
- 加载 ELF/BIN 到仿真内存（通过 `pydifftest.InitRam`）
- 配置 PMEM_BASE 和 FIRST_INST_ADDRESS
- 初始化 UART echo 回环回调
- 加载 difftest shared object（NEMU/Spike 参考模型）

**批量执行引擎**:
- 按指令数执行（`run_commits`）：分批执行（每批 100 条），支持最大运行时间限制
- 按周期数执行：分批执行（每批 10000 周期），支持时间限制
- 脚本回放：加载并执行 XSPdb log 文件中的命令序列
- Script 执行：执行预定义的调试脚本

**波形控制**:
- 支持按周期精确控制波形开启/关闭（`-b` / `-e`）
- 使用 `xbreak` 回调机制在指定硬件周期触发波形控制

### 10.2 CLI 参数

**`cli_parser.py`** 定义了完整的命令行参数集：
- `-i/--image`: 要加载的二进制镜像
- `--mem-base-address`: 内存基地址（默认 0x80000000）
- `--flash` / `--flash-base-address`: Flash 加载
- `-l/--log` / `--log-file` / `--log-level`: 日志控制
- `--batch`: 批处理模式
- `-c/--max-cycles`: 最大仿真周期
- `-t/--interact-at`: 在指定周期进入交互模式
- `-s/--script`: 调试脚本
- `-r/--replay`: 回放日志
- `--diff`: 差分测试共享库
- `--ram-size`: 仿真内存大小
- `--max-run-time`: 最大运行时间（支持 s/m/h 格式）
- `-pc/--pc-commits`: 执行到指定提交数
- `--cmds` / `--cmds-post`: 预/后置命令
- `--no-interact`: 无交互模式

### 10.3 TUI 界面

通过 `xui` 命令进入 Text UI 界面，支持：
- 实时信号观察
- 调试配置保存/加载
- 地址跳转（`xui goto`）
- 主题切换（`xtheme`）

### 10.4 命令扩展机制

支持通过 Python 模块动态加载自定义 PDB 命令：
- `load_package_from_dir()`: 从目录加载 `cmd_*.py` 命令模块
- `register_commands()`: 将模块中的函数注册为 pdb 子命令
- `xcmds` 命令列出所有已注册的扩展命令

---

## 11. Bug 报告与生成脚本

### 11.1 bug-report.sh

**文件路径**: `scripts/bug-report.sh`

自动收集 Bug 报告所需的环境信息：

**基础信息**（`--basic` 模式也可用）:
- 硬件：CPU 型号、内存大小、磁盘空间
- 软件：OS 版本、uname、Python/GCC/Clang/glibc/Java/Mill 版本
- 仓库：最新 commit、git status、submodule 状态、diff

**运行时信息**:
- 交互式收集用户使用的编译命令和运行命令
- 支持中英文双语界面（检测 `LANG` 环境变量）
- 鼓励用户将日志和测试用例复制到报告目录

最终打包为 `.tar.gz` 文件，并提示用户提交到 GitHub Issues。

### 11.2 generate_all.sh

**文件路径**: `scripts/generate_all.sh`

一键生成所有模块的 Verilog 释放版本，遍历 Frontend、Backend、MemBlock、L2Top、XSTile、XSTop 六个模块，依次调用 `parser.py` 执行 SRAM 替换和文件列表生成，生成日志命名为 `generate_{module}.log`。

---

## 12. 调试与 CI 脚本

**文件路径**: `debug/`

### 12.1 local_ci.py -- 本地 CI 执行器

从 `.github/workflows/emu.yml` 解析 GitHub Actions 工作流定义，本地执行 CI 测试：

**核心流程**:
1. 解析 YAML 配置获取测试定义
2. 替换环境变量（`$GITHUB_WORKSPACE` -> `$NOOP_HOME`，`$HEAD_SHA` -> 日期等）
3. 拆分多行命令为独立命令序列
4. 执行模式：直接运行（`--run`）或生成 shell 脚本（`--sh-path`）

**支持功能**:
- `--show-test`: 显示所有测试名称
- `--pick-test`: 只运行包含指定关键词的测试
- `--numa`: 启用 NUMA 绑核
- 自动创建 wave 和 perf 输出目录

### 12.2 cputest.sh

批量运行 `$AM_HOME/tests/cputest` 下的所有 CPU 测试，逐个 make 运行并检测 `HIT GOOD TRAP` 输出，支持彩色终端输出（ANSI 转义码）。

### 12.3 perf_sbuffer.sh

Store Buffer 性能分析脚本，统计：
- Store 请求接受数（`accept req`）
- DCache 请求发送数（`send buf`）
- 各端口阻塞次数（`blocked by sbuffer`）
- SBuffer 满（15/16 entries）的出现次数

### 12.4 sc_stat.sh

TAGE-SC（Statistical Corrector）分支预测器分析脚本：
- 统计 TAGE 和 SC 预测一致/不一致的次数
- 分析 SC 修正 TAGE 错误预测的频率
- 评估 SC 误纠正 TAGE 正确预测的情况

### 12.5 env.sh

简单的环境变量设置脚本，将 `NOOP_HOME` 设为上级目录。

---

## 13. 关键文件位置速查表

| 工具 | 路径 | 用途 |
|------|------|------|
| xiangshan.py | `scripts/xiangshan.py` | 主工作流编排 |
| parser.py | `scripts/parser.py` | RTL 解析/SRAM 处理 |
| constantHelper.py | `scripts/constantHelper.py` | 遗传算法参数优化 |
| rolling.py | `scripts/rolling.py` | Rolling counter 分析 |
| perfcct.py | `scripts/perfcct.py` | 指令生命周期可视化 |
| statistics.py | `scripts/statistics.py` | Verilog/日志统计 |
| sram_size_collect.py | `scripts/sram_size_collect.py` | SRAM 面积统计 |
| bug-report.sh | `scripts/bug-report.sh` | Bug 报告生成 |
| generate_all.sh | `scripts/generate_all.sh` | 全模块生成 |
| top_down.py | `scripts/top-down/top_down.py` | Top-Down 分析主入口 |
| draw.py | `scripts/top-down/draw.py` | Top-Down 可视化 |
| configs.py | `scripts/top-down/configs.py` | Top-Down 配置 |
| utils.py | `scripts/top-down/utils.py` | 统计数据提取 |
| parseAddr.py | `scripts/cache/parseAddr.py` | Cache 地址解析 |
| l2DB_helper.py | `scripts/cache/l2DB_helper.py` | L2 DB 查询 |
| convert_tllog.sh | `scripts/cache/convert_tllog.sh` | TileLink 日志格式化 |
| convert_mp.sh | `scripts/cache/convert_mp.sh` | MainPipe 日志格式化 |
| convert_dir.sh | `scripts/cache/convert_dir.sh` | 目录状态解码 |
| coverage.py | `scripts/coverage/coverage.py` | 覆盖率分析 |
| statistics.py | `scripts/coverage/statistics.py` | 覆盖率预处理 |
| xspdb.py | `scripts/xspdb/xspdb.py` | 交互式调试器 |
| cli_parser.py | `scripts/xspdb/cli_parser.py` | XSPdb CLI 参数 |
| local_ci.py | `debug/local_ci.py` | 本地 CI 执行 |
| cputest.sh | `debug/cputest.sh` | CPU 测试批量运行 |
| perf_sbuffer.sh | `debug/perf_sbuffer.sh` | Store Buffer 分析 |
| sc_stat.sh | `debug/sc_stat.sh` | TAGE-SC 统计 |

---

## 14. 工具间协作关系

XiangShan 的开发脚本形成了一个完整的工具链生态：

1. **开发阶段**: `xiangshan.py` 编排 Verilog 生成和仿真器构建，`parser.py` 后处理 RTL 生成 release 版本和 SRAM 配置
2. **参数调优**: `constantHelper.py` 利用遗传算法搜索最优硬件参数
3. **仿真运行**: `xiangshan.py` 或 `XSPdb` 驱动仿真，`perfcct.py` 可视化指令流水线
4. **性能分析**: `top_down/` 框架生成 Top-Down 分析报告，`rolling.py` 分析趋势指标
5. **Cache 调试**: `cache/` 工具链配合 ChiselDB 进行缓存子系统深度分析
6. **覆盖率验证**: `coverage/` 工具分析代码覆盖率
7. **问题诊断**: `debug/` 脚本和 `bug-report.sh` 辅助问题定位和报告
8. **CI 验证**: `local_ci.py` 和 `xiangshan.py --ci` 保证回归质量

这套工具链从 Chisel/Scala 源码到最终的性能/面积/功耗分析，覆盖了处理器开发的完整生命周期，是 XiangShan 项目高效迭代的重要基础设施。
