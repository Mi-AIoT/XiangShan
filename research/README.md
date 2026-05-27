# XiangShan SimPoint SPEC2006 评估框架研究报告

> 本目录包含 XiangShan SimPoint 技术的完整研究报告，适合嵌入式软件工程师理解。

---

## 报告清单

### 基础研究（已完成）

| 文件 | 大小 | 内容 |
|------|------|------|
| `01-simpoint-flow-analysis.md` | 41KB | SimPoint 完整流程详解 |
| `02-migration-guide.md` | 33KB | 迁移到 Verilog CPU 指南 |
| `03-architecture-diagram.md` | 81KB | 架构图和数据流图 |
| `04-code-reference.md` | 19KB | 关键代码位置索引 |

### 深入研究（本次新增）

| 文件 | 大小 | 内容 |
|------|------|------|
| `05-questions-for-embedded-engineers.md` | 15KB | 针对嵌入式工程师的 10 个高价值问题 |
| `06-simpoint-sampling-deep-dive.md` | 25KB | SimPoint 采样点产生原理详解 |
| `07-vexiiriscv-simpoint-analysis.md` | 20KB | VexiiRiscv 与 SimPoint 对接可行性分析 |
| `08-checkpoint-deep-dive.md` | 22KB | Checkpoint 机制详解 |
| `09-verilator-principles.md` | 20KB | Verilator 编译原理详解 |
| `10-performance-counters-deep-dive.md` | 18KB | 性能计数器实现详解 |
| `11-spec-score-calculation.md` | 16KB | SPEC2006 分数计算方法详解 |

### DiffTest 与深度研究

| 文件 | 大小 | 内容 |
|------|------|------|
| `12-difftest-deep-analysis.md` | 22KB | DiffTest 框架在 SPEC2006 评估中的真实角色 |
| `13-xiangshan-deep-report.md` | 45KB | 香山处理器嵌入式视角技术深度报告（7 章） |
| `14-interrupt-handling-in-simpoint.md` | 18KB | SimPoint/Checkpoint 恢复后的中断处理问题分析 |
| `15-time-measurement-without-interrupts.md` | 22KB | SPEC2006 不依赖中断的时间测量机制 |
| `16-spec2006-threading-and-scheduling.md` | 24KB | SPEC2006 线程模型与任务调度分析 |
| `17-simpoint-interrupt-accuracy.md` | 27KB | SimPoint 评估中中断处理与分数准确性分析 |
| `18-deep-questions-and-findings.md` | 25KB | 灵魂拷问 10 个问题：深度调研报告 |

---

## 学习路径建议

### 阶段 1：理解整体流程（1-2 天）

1. 阅读 `01-simpoint-flow-analysis.md`，了解整体架构
2. 阅读 `03-architecture-diagram.md`，查看架构图
3. 阅读 `04-code-reference.md`，了解关键代码位置

### 阶段 2：深入理解原理（3-5 天）

1. 阅读 `05-questions-for-embedded-engineers.md`，了解关键问题
2. 阅读 `06-simpoint-sampling-deep-dive.md`，理解 SimPoint 原理
3. 阅读 `08-checkpoint-deep-dive.md`，理解 Checkpoint 机制
4. 阅读 `09-verilator-principles.md`，理解 Verilator 编译
5. 阅读 `10-performance-counters-deep-dive.md`，理解性能计数器
6. 阅读 `11-spec-score-calculation.md`，理解分数计算

### 阶段 3：实践应用（1-2 周）

1. 阅读 `02-migration-guide.md`，学习如何迁移框架
2. 阅读 `07-vexiiriscv-simpoint-analysis.md`，了解 VexiiRiscv 可行性
3. 尝试按照迁移指南，在自己的 CPU 上运行评估

---

## 核心概念速查

### SimPoint

**是什么**：一种统计采样技术，通过找到程序的代表性阶段，用少量仿真估算全程序性能。

**原理**：
1. 提取基本块向量 (BBV)
2. 使用 K-Means 聚类
3. 选择代表性采样点
4. 计算权重

**效果**：将仿真时间从数周缩短到数小时，精度 97%+。

### Checkpoint

**是什么**：程序在某个时刻的完整状态快照，包括内存和 CPU 状态。

**格式**：GCPT (Golden Checkpoint)，包含：
- Header (16 bytes)
- CPU 状态 (寄存器 + CSR)
- 内存快照 (几 GB)

**用途**：从 Checkpoint 恢复执行，避免重复运行程序的初始化阶段。

### Verilator

**是什么**：一个开源的 Verilog 仿真器，将 RTL 编译为 C++，再编译为原生可执行文件。

**原理**：
1. 解析 Verilog 为 AST
2. 优化 AST
3. 生成 C++ 代码
4. 编译为可执行文件

**优势**：比传统仿真器快 10-100 倍。

### 性能计数器

**是什么**：用于统计硬件事件的计数器，帮助分析性能瓶颈。

**分类**：
- 基础计数器：commitInstr, total_cycles
- 前端计数器：ICacheMiss, ITLBMiss, BTBMiss
- 后端计数器：DivStall, RobStall, IntFlStall
- 内存计数器：LoadL1Stall, LoadL2Stall, StoreStall

**输出**：通过 `$fwrite` 输出到 `simulator_err.txt`。

### SPEC 分数

**是什么**：业界标准的 CPU 性能评估基准测试分数。

**计算**：
1. 计算每个 benchmark 的 IPC
2. 使用 SimPoint 权重计算加权平均 IPC
3. 计算每个 benchmark 的分数 = IPC / 参考机 IPC
4. 使用几何平均计算综合分数
5. GHz 归一化 = 分数 / 频率

---

## 关键代码位置

### Workflow 文件

- `.github/workflows/perf-template.yml` - 性能测试主流程
- `.github/workflows/perf-v3.yml` - 定时触发器

### 构建系统

- `Makefile` - 主 Makefile
- `difftest/verilator.mk` - Verilator 编译配置
- `difftest/emu.mk` - Emulator 构建规则

### DiffTest 框架

- `difftest/src/test/csrc/emu/emu.cpp` - Emulator 主逻辑
- `difftest/src/test/csrc/common/ram.cpp` - 内存模型

### 性能分析

- `scripts/top-down/top_down.py` - Top-Down 分析主脚本
- `scripts/top-down/configs.py` - 性能计数器配置
- `scripts/top-down/utils.py` - 工具函数

---

## 环境变量

| 变量 | 说明 |
|------|------|
| `NOOP_HOME` | XiangShan 仓库根目录 |
| `NEMU_HOME` | NEMU 仓库目录 |
| `DRAMSIM3_HOME` | DRAMsim3 目录 |
| `CKPT_HOME` | Checkpoint 存储目录 |
| `CKPT_JSON_PATH` | JSON 配置文件路径 |

---

## 常用命令

### 构建

```bash
# 初始化子模块
make init-force

# 生成仿真 Verilog
make sim-verilog CONFIG=DefaultConfig

# 构建 Emulator
make emu CONFIG=DefaultConfig WITH_DRAMSIM3=1
```

### 运行

```bash
# 运行单个 checkpoint
./build/emu -i checkpoint.gz \
    --diff ./ready-to-run/riscv64-nemu-interpreter-so \
    --max-instr 20000000

# 运行 Top-Down 分析
cd scripts/top-down
python3 top_down.py -b /path/to/results -j resources/spec06_rv64gcb_o2_20m.json
```

### 调试

```bash
# 查看 Emulator 帮助
./build/emu --help

# 启用波形
./build/emu -i checkpoint.gz --enable-waveform
```

---

## VexiiRiscv 对接 SimPoint 可行性

### 结论

**可行**，但需要开发工作。

### 推荐方案

基于 XiangShan 框架适配，工作量约 2-3 周。

### 关键步骤

1. 生成 VexiiRiscv Verilog
2. 创建仿真顶层，添加 DiffTest 接口
3. 配置 Verilator 编译
4. 添加性能计数器
5. 实现 Checkpoint 机制
6. 运行评估

### 详细分析

见 `07-vexiiriscv-simpoint-analysis.md`。

---

## 嵌入式工程师的建议

### 1. 先理解原理

不要急于动手，先理解：
- SimPoint 的核心思想
- Checkpoint 的作用
- Verilator 的工作原理
- 性能计数器的分类

### 2. 从简单开始

先实现最小方案：
- 只计算 IPC
- 只运行一个 benchmark
- 跳过 DiffTest

### 3. 逐步扩展

验证可行后，逐步添加：
- 更多性能计数器
- 更多 benchmark
- DiffTest 验证

### 4. 参考 XiangShan

XiangShan 是最好的参考：
- 代码结构清晰
- 文档完善
- 社区活跃

---

## 相关资源

### XiangShan

- **仓库**：https://github.com/OpenXiangShan/XiangShan
- **文档**：https://docs.xiangshan.cc/

### VexiiRiscv

- **仓库**：https://github.com/SpinalHDL/VexiiRiscv
- **文档**：https://spinalhdl.github.io/VexiiRiscv-RTD/

### 工具链

- **Verilator**：https://verilator.org/
- **NEMU**：https://github.com/OpenXiangShan/NEMU
- **SimPoint**：https://github.com/SimulatorWorks/SimPoint
- **DRAMsim3**：https://github.com/umd-memsys/DRAMsim3

---

## 联系方式

如有问题，请通过 GitHub Issues 反馈。

---

*报告生成时间：2026-05-27*
