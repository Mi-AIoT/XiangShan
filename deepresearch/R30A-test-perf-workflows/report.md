# R30A - XiangShan 测试与性能工作流深度解析

## 1. 概述 (Overview)

XiangShan RISC-V 处理器项目构建了一套高度工程化的 CI/CD 测试与性能回归体系。该体系由 11 个 GitHub Actions workflow 文件和 1 个 Python 辅助脚本组成，覆盖了从基础功能正确性验证到 SPEC CPU2006 全量性能回归的完整流程。整体架构遵循 **Builder-Runner 分离** 的设计理念，利用 change-aware 触发机制优化资源利用率，并通过统一模板实现多种 benchmark 配置的灵活切换。

核心工作流分为以下层次：

| 层次 | Workflow | 触发方式 | 主要职责 |
|------|----------|----------|----------|
| 功能测试层 | `emu-basics.yml` | push / PR | 12 项并行功能测试，涵盖 ISA、Linux、微架构 |
| 杂项测试层 | `emu.yml` | push / PR | GSIM、SimFrontend、多核、SIMV、Verilog 检查等 |
| 快速性能层 | `emu-performance.yml` | push / PR | PR 级别 28 项 SPEC06 benchmark IPC 回归 |
| 模板层 | `perf-template.yml` | workflow_call | 统一性能测试模板，支持 11 种 benchmark 配置 |
| 手动触发层 | `perf-trigger.yml` / `perf-v2.yml` | workflow_dispatch | 灵活的手动性能回归入口 |
| 定时调度层 | `perf-v3.yml` / `nightly.yml` | cron + dispatch | 周五/每日定时性能回归 |
| 汇总报告层 | `emu-performance-summary.yml` / `emu-performance-comment.yml` | workflow_dispatch / issue_comment | IPC 对比报告生成与 PR 评论推送 |

---

## 2. emu-basics.yml - 12 项并行功能测试

### 2.1 架构设计

`emu-basics.yml`（name: "EMU Basics Test"）是 XiangShan 的基础功能验证入口，专门用于 **quick functional test**，与 `emu.yml` 中的杂项测试并行执行，实现了工作流级别的并行化拆分。

**触发条件：** push 或 pull_request 到 `kunminghu-v3` 分支。

**整体流水线结构：** `changes` -> `build` -> `run` -> `upload` -> `summary`，五个阶段形成有向无环图。

### 2.2 Change Detection（变更检测）

每个测试 workflow 的第一步都是通过 `dorny/paths-filter@v4` 检测代码变更。其核心配置文件为 `.github/filters.yaml`，定义了一个名为 `core` 的过滤器，采用 **排除式 (negation) 规则**：

```yaml
core:
  - "!.github/ISSUE_TEMPLATE/**"
  - "!.github/CODEOWNERS"
  - "!.github/filters.yaml"
  - "!.github/workflows/nightly.yml"
  - "!.github/workflows/perf-template.yml"
  - "!.github/workflows/perf-v2.yml"
  - "!.github/workflows/perf-v3.yml"
  - "!.github/workflows/perf-trigger.yml"
  - "!.github/workflows/nightly_perf_diff.py"
  - "!.gitignore"
  - "!**/*.md"
  - "!LICENSE"
  - "!images/**"
```

这意味着：只要不是仅修改了 issue 模板、markdown 文档、license 等非核心文件，`core` 输出就会为 `true`，从而触发完整的构建和测试流程。这种设计确保了**任何核心代码变更都会被检测到**，而纯文档变更则可以跳过耗时的仿真构建。

### 2.3 Builder-Runner 分离

`emu-basics.yml` 采用了经典的 **Builder-Runner 架构**：

**Build 阶段（builder 节点）：**
- 运行在标记为 `builder` 的自托管 runner 上
- 超时 180 分钟
- 使用 Verilator（8 线程）编译 EMU 二进制
- 应用 PGO（Profile-Guided Optimization）：以 `coremark-2-iteration.bin` 作为 PGO 训练输入
- 生成 FST trace 格式的波形
- 编译完成后将 EMU 二进制（约 100MB）拷贝到 NFS 共享目录 `${PERF_HOME}/emu`
- 检测当前集群类型（`node` 或 `open`），通过 `outputs.cluster` 传递给后续 job

**关键设计决策：** 为什么不使用 GitHub Actions 原生的 `upload-artifact`？workflow 注释明确说明：EMU 二进制约 100MB，self-hosted runner 的网络带宽不足以高效传输，因此采用 NFS 共享文件系统直接拷贝。这是一个基于基础设施现实的务实选择。

**Run 阶段（runner 节点）：**
- 运行在与 builder **同一集群** 的 runner 上，避免跨集群网络传输和 PGO 兼容性问题
- 超时 300 分钟
- 通过 matrix strategy 并行执行测试，`max-parallel: 4`，`fail-fast: false`

### 2.4 12 项并行测试配置

每个 matrix 配置项包含 `name`（测试名称）、`extra`（额外参数）和 `report`（是否生成 IPC 报告）字段：

| # | 测试名称 | 特殊配置 | 报告 | 说明 |
|---|----------|----------|------|------|
| 1 | `cputest` | 默认 | 否 | CPU 基础指令集正确性测试 |
| 2 | `riscv-tests` | `--rvtest /nfs/home/share/ci-workloads/riscv-tests` | 否 | RISC-V 官方 ISA 测试套件 |
| 3 | `misc-tests` | 默认 | 否 | 杂项功能测试 |
| 4 | `rvh-tests` | 默认 | 否 | RISC-V Hypervisor 扩展测试 |
| 5 | `microbench` | 默认 | 是 | 微基准测试，含 IPC 采集 |
| 6 | `coremark` | 默认 | 是 | CoreMark 综合性能测试 |
| 7 | `linux-hello-opensbi` | 默认 | 是 | OpenSBI + Linux 内核启动测试 |
| 8 | `iopmp-test` | `--no-diff` | 否 | IO PMP（物理内存保护）测试，无 diff 对比 |
| 9 | `povray` | `--max-instr 5000000 --gcpt-restore-bin ...` | 是 | POV-Ray 渲染 benchmark（500 万指令快照） |
| 10 | `copy_and_run` | `--flash .../copy_and_run.bin` + workload | 是 | Flash 启动 + 多程序运行测试 |
| 11 | `f16_test` | 默认 | 否 | 半精度浮点（Float16）测试 |
| 12 | `zcb-test` | 默认 | 否 | Zcb（代码大小压缩）扩展测试 |

注意：Vector 相关工作负载（`rvv-bench`）因正在进行的 vector 重构而暂时禁用。

### 2.5 IPC 采集与报告

对于标记 `report: true` 的测试，workflow 会：
1. 将 stderr 输出到日志文件（而非丢弃）
2. 从 stdout 中用正则 `grep -oP 'IPC = \K\d+\.\d+'` 提取 IPC 值
3. 将 IPC 值保存到 NFS 文件（如 `ipc-basics-microbench`）
4. 归档 stdout 和 stderr 日志为 tar.gz

**Summary 阶段** 下载所有 `ipc-*` artifact，生成 Markdown 表格写入 `GITHUB_STEP_SUMMARY`。

---

## 3. emu.yml - GSIM、SimFrontend、多核、SIMV、Verilog 检查

### 3.1 定位与设计原则

`emu.yml`（name: "EMU Misc Test"）负责**不可并行化的杂项测试与检查**。与 `emu-basics.yml` 的注释明确说明了两者的分工原则：

> "These tests may be built and ran both on 'builder' machines. The reason is that we don't want a complex builder-runner workflow like EMU Performance Test, and these tests may consume ~16 threads verilator, while 'runner' label is designed to be used with single-thread gsim."

这解释了为什么杂项测试不采用 Builder-Runner 分离：它们需要大量 Verilator 编译线程（16 线程），而 runner 设计为单线程 GSIM 运行环境。

### 3.2 六大测试模块

#### 3.2.1 EMU-GSIM（GSIM 功能测试）

- **运行节点：** `builder` + `node`（特定节点服务器，因 GSIM 在 open 服务器上有性能问题）
- **超时：** 900 分钟（15 小时）
- **构建：** 使用 GSIM 模拟器（非 Verilator），单线程
- **特殊配置：** 启用 DRAMsim3 内存仿真、PGO 优化、FST 波形追踪
- **测试内容：** 运行 `linux-hello-opensbi`，即完整的 Linux 内核启动
- **当前限制：** 因 GSIM 尚未支持 lightSSS，使用 `--disable-fork` 禁用 fork 模式

#### 3.2.2 EMU-SimFrontend（前端仿真测试）

- **构建：** Verilator 8 线程，启用 `--simfrontend` 标志（前端仿真模式）
- **测试矩阵：** 运行 3 个 SPEC06 benchmark（astar、milc、xalancbmk），每个 500 万指令
- **特殊参数：** 使用 `--gcpt-restore-bin` 进行 checkpoint 恢复，`--instr-trace` 启用指令追踪
- **报告：** 生成 3 行 IPC 汇总表格

#### 3.2.3 EMU-MC（多核测试）

- **构建：** Verilator **16 线程**，`--num-cores 2`（双核配置），`--emu-optimize ""`（无优化），`--trace-all`（全量波形追踪）
- **PGO 训练：** 使用 `linux-hello-smp-new/bbl.bin` 作为 SMP PGO 输入
- **测试内容：**
  - MC Basics：双核基础测试，使用 `riscv64-nemu-interpreter-dual-so` 进行 diff 对比
  - SMP Linux：对称多处理 Linux 启动测试
- **特殊性：** 这是唯一一个测试多核配置的工作流

#### 3.2.4 SIMV-Basics（VCS 仿真测试）

- **运行节点：** `eda`（EDA 工具服务器）
- **流程特殊性：** 先通过 SSH 连接到 `eda01` 远程机器编译和运行
- **构建步骤：**
  1. 本地生成 Verilog（`--vcs-gen --xprop`）
  2. 远程 SSH 到 eda01 编译 VCS 仿真器（`--vcs-build --xprop`）
  3. 远程运行 CoreMark 1-iteration 测试
- **已注释测试：** Vector 测试（rvv-test）、MicroBench、cputest、CoreMark 完整版、Linux 启动均被注释掉，反映了 vector 重构期间的临时调整

#### 3.2.5 Check-Verilog（Verilog 生成检查）

这是最复杂的质量检查 job，包含多个子步骤：

1. **BoringUtils 检查：** 运行 `check-usage.sh` 脚本检查顶层 BoringUtils 使用情况
2. **Standalone 设备生成：** 分别为 TileLink 和 AXI4 接口生成独立 CLINT、SYSCNT、DebugModule、PLIC 设备
3. **XSNoCTop 检查：** 生成 NoC 拓扑配置的 Verilog 并验证
4. **标准 Verilog 生成：** 生成双核 Verilog 并运行 `check_verilog.py` 脚本验证
5. **Chirrtl 生成：** 单独生成 Chirrtl 中间表示
6. **MinimalConfig Release：** 构建最小配置 Release 版 EMU，运行 Linux 启动测试
7. **NOC Difftest 接口检查：** 生成 XSNoCDiffTop 和 Difftest Verilog，验证接口兼容性，最终通过 Verilator lint 检查

#### 3.2.6 其他检查

- **Check-Docker：** 验证 Docker 镜像构建（`continue-on-error: true`，不阻塞 CI）
- **Check-Submodules：** 确保所有子模块（rocket-chip、difftest、ready-to-run、utility、yunsuan、XSCache、ChiselAIA）已合并到其默认分支
- **Check-Format：** 使用 GraalVM 21 + Mill 构建工具执行代码格式检查

---

## 4. emu-performance.yml - PR 级别 28 项 SPEC06 性能测试

### 4.1 定位

`emu-performance.yml`（name: "EMU Performance Test"）是 PR 级别的快速性能回归工作流。它与功能测试并行执行，使用 GSIM 模拟器提供快速的 IPC 评估。

### 4.2 Builder-Runner 架构

**Build 阶段：**
- 运行在 `node` + `builder` 节点
- 使用 GSIM 模拟器编译（单线程即可运行，但编译仍需资源）
- 启用 DRAMsim3、PGO、FST trace
- 将 EMU 二进制保存到 NFS

**Run 阶段：**
- 运行在与 builder 同集群的 `runner` 节点
- `max-parallel: 12`，`fail-fast: false`
- 每个 runner 执行单个 SPEC06 benchmark checkpoint

### 4.3 28 项 SPEC06 Benchmark 配置

每项 benchmark 都指定了精确的 checkpoint 编号：

| 类型 | Benchmark 列表 |
|------|----------------|
| 整数（Integer） | perlbench_splitmail(3995), bzip2_liberty(739), gcc_s04(2772), mcf(6388), gobmk_nngs(453), hmmer_nph3(33214), sjeng(64284), libquantum(81539), h264ref_foreman.main(10053), omnetpp(14042), astar_rivers(8728), xalancbmk(9874) |
| 浮点（Floating Point） | bwaves(30350), gamess_gradient(36450), milc(7124), zeusmp(45598), gromacs(2907), cactusADM(61235), leslie3d(37552), namd(75757), dealII(13061), soplex_ref(10774), povray(8362), calculix(53456), GemsFDTD(49458), tonto(69015), lbm(31064), wrf(112496), sphinx3(141036) |

**运行参数：**
- `--numa --threads 1`：单线程 NUMA 模式
- `--max-instr 5000000`：每个 benchmark 运行 500 万条指令
- `--disable-fork`：禁用 fork（GSIM 限制）
- `--gcpt-restore-bin`：使用 checkpoint 恢复二进制

**同时保留了 legacy run 阶段**（`run-legacy`），运行 9 项旧版 benchmark（mcf, xalancbmk, gcc, namd, milc, lbm, gromacs, wrf, astar），通过 `--ci` 名称方式运行，计划在大部分开发分支迁移后移除。

### 4.4 Summary 与 Comment 机制

**Dispatch Summary：**
- 在所有 benchmark 完成后，自动 dispatch `emu-performance-summary.yml` 工作流
- 使用 `gh api` 触发 workflow_dispatch 事件
- 传递 HEAD_SHA、BASE_SHA、PR 编号等参数
- 轮询等待（最多 20 次，每次间隔 3 秒）获取 summary workflow URL
- 特殊处理：对 fork PR 跳过 dispatch（因 GITHUB_TOKEN 权限限制）

---

## 5. emu-performance-summary.yml 与 emu-performance-comment.yml - 报告生成与评论推送

### 5.1 Summary Workflow

`emu-performance-summary.yml` 是一个 `workflow_dispatch` 触发的独立工作流，接收以下参数：
- `HEAD_SHA`：当前 commit SHA
- `BASE_SHA`：基准 commit SHA（可选）
- `PR`：PR 编号（可选）
- `COMMENT_ID`：已存在的评论 ID（可选，用于更新而非新建）

**核心逻辑：**

1. **查找 Run ID：** 通过 `gh run list` 搜索 HEAD_SHA 和 BASE_SHA 对应的 EMU Performance Test 运行 ID
2. **下载报告：** 使用 `actions/download-artifact@v8` 下载 `ipc-*` pattern 的 artifact
3. **生成对比报告：**
   - 计算每个 benchmark 的 IPC 差异百分比
   - 使用 Python 计算 **几何平均值（Geomean）**
   - 生成 Markdown 表格，包含 Current、Base、Diff 列
4. **推送评论：**
   - 新建评论：`gh pr comment --edit-last --create-if-none`
   - 更新评论：通过 `gh api -X PATCH` 更新指定 ID 的评论

### 5.2 Comment Trigger

`emu-performance-comment.yml` 允许开发者在 PR 中输入 `/diff` 或 `/diff <BASE_SHA>` 命令，触发性能对比：
- 解析 `/diff` 命令格式
- 自动获取 PR 的 head_sha 和 base_sha
- Dispatch summary workflow 并传递参数

---

## 6. perf-template.yml - 统一性能测试模板

### 6.1 设计理念

`perf-template.yml`（name: "Performance Regression Template (Unified)"）是整个性能测试体系的**核心模板**，通过 `workflow_call` 被多个上层 workflow 调用。这种模板化设计实现了：

- **配置复用：** perf-v2、perf-v3、nightly、perf-trigger 均调用此模板
- **参数化：** 支持 11 种输入参数灵活配置
- **输出标准化：** 统一的输出接口（SHORT_SHA、DATE、SPEC_DIR、SCORE_FILE 等）

### 6.2 输入参数

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `test_branch` | string | 必填 | 测试分支或 commit |
| `emulator` | string | gsim | 模拟器类型 |
| `xs_config` | string | DefaultConfig | XiangShan 配置 |
| `benchmark_type` | string | gcc15-spec06-1.0c | Benchmark 类型 |
| `benchmarks` | string | 空（全部） | 指定 benchmark（逗号分隔） |
| `cst_file` | string | 空 | Constantin 配置文件路径 |
| `servers` | string | 空（自动选择） | 运行服务器 |
| `checkpoint_path` | string | 空 | 自定义 checkpoint 目录 |
| `json_path` | string | 空 | 自定义 JSON 路径 |
| `perf_report_dir` | string | 必填 | 性能报告根目录 |

### 6.3 支持的 Benchmark 类型（11 种配置）

通过 `benchmark_type` 参数选择不同的 checkpoint 和 JSON 配置：

| 类型 | 编译器 | Spec 版本 | Checkpoint 密度 | 路径标识 |
|------|--------|-----------|-----------------|----------|
| `gcc15-spec06-0.3c` | GCC 15 | SPEC06 | 30% | `spec06_gcc15_rv64gcb_base_260122` |
| `gcc15-spec06-0.8c` | GCC 15 | SPEC06 | 80% | 同上 |
| `gcc15-spec06-1.0c` | GCC 15 | SPEC06 | 100% | 同上 + `cluster-0-0.json` |
| `xscc-spec06-0.3c` | XSCC | SPEC06 | 30% | `spec06_xscc_v1_rv64gcb_base_260122` |
| `xscc-spec06-0.8c` | XSCC | SPEC06 | 80% | 同上 |
| `xscc-spec06-1.0c` | XSCC | SPEC06 | 100% | 同上 + `cluster-0-0.json` |
| `gcc12-spec06-0.3c` | GCC 12 | SPEC06 | 30% | `spec06_rv64gcb_O3_20m_gcc12.2.0-intFpcOff-jeMalloc` |
| `gcc12-spec06-0.8c` | GCC 12 | SPEC06 | 80% | 同上 |
| `gcc12-spec06-1.0c` | GCC 12 | SPEC06 | 100% | 同上 + `cluster-0-0.json` |
| `custom` | - | - | - | 用户自定义路径 |
| `dryrun` | - | - | - | dryrun.json，用于流程验证 |

**Checkpoint 密度**（0.3c/0.8c/1.0c）代表 SPEC06 轨迹的采样密度，影响测试精度与运行时间的平衡。

### 6.4 运行流程

1. **环境设置：** 根据 benchmark_type 配置对应的 JSON 和 checkpoint 路径，计算 SPEC_DIR 路径格式 `cr{DATE}-{SHORT_SHA}-{CONFIG}`
2. **EMU 构建：** 带 DRAMsim3 的 EMU 编译，支持缓存复用（如果 `$SPEC_DIR/emu-{emulator}` 已存在则跳过构建）
3. **SPEC 运行：** 调用 `perf_trigger/main.py` 脚本，支持多服务器分布式运行、Constantin 动态参数、dry-run 模式
4. **分数报告：** 调用 `perf/xs_autorun_multiServer.py` 生成 SPEC CPU2006 分数，输出到 `score-{benchmark_type}.txt`
5. **Summary 生成：** 解析分数文件，输出 SPECint2006/GHz、SPECfp2006/GHz、SPEC2006/GHz 关键指标

### 6.5 输出接口

模板通过 `outputs` 向调用方暴露标准化接口：

| 输出 | 说明 |
|------|------|
| `SHORT_SHA` | 当前 commit 的短 SHA |
| `DATE` | commit 日期（yymmdd 格式） |
| `PERF_DIR` | 性能报告根目录 |
| `SPEC_DIR` | 本次运行的报告子目录 |
| `CONFIG` | XiangShan 配置名称 |
| `SCORE_FILE` | 生成的 score.txt 路径 |

---

## 7. perf-v2.yml / perf-v3.yml - 手动与定时性能触发

### 7.1 perf-v2.yml（Performance Regression V2）

- **触发方式：** 仅 `workflow_dispatch`（手动触发）
- **默认分支：** `master`
- **报告目录：** `/nfs/home/cirunner/perf-report-master`
- **服务器：** `all`（使用所有可用服务器）
- **用途：** 面向 master 分支的全量性能回归

### 7.2 perf-v3.yml（Performance Regression V3）

- **触发方式：**
  - `schedule`：每周五 UTC 4:00（北京时间 12:00）自动执行
  - `workflow_dispatch`：手动触发
- **默认分支：** `kunminghu-v3`
- **报告目录：** `/nfs/home/cirunner/perf-report-kmhv3`
- **服务器：** `all`
- **额外步骤：** 运行完成后上传 score 文件为 artifact

### 7.3 perf-trigger.yml（Performance Regression Trigger）

这是功能最丰富的手动触发入口，提供完整的 UI 表单：

- **run-name 模板：** `{note} - {test_branch} - {benchmark_type} - {xs_config}`
- **支持的 emulator 选项：** gsim / verilator
- **支持的 xs_config：** DefaultConfig / BackendV2Config
- **支持的 benchmark_type：** 11 种（如上文 6.3 所述）
- **额外参数：** cst_file（Constantin 配置文件）、servers（服务器选择）、custom 路径等
- **报告目录：** `/nfs/home/cirunner/perf-report-custom`

---

## 8. nightly.yml - 每日性能回归

### 8.1 定时策略

`nightly.yml`（name: "Nightly Regression"）每天 UTC 15:33（北京时间 23:33）执行。

**选择 23:33 而非整点的考量：** 避免与其他定时任务（如 GitHub Actions 原生 schedule）竞争资源窗口，同时确保在晚间低负载时段运行。

### 8.2 运行配置

- **分支：** `kunminghu-v3`
- **Benchmark 类型：** `gcc15-spec06-0.3c`（30% 采样密度，平衡速度与覆盖度）
- **服务器：** `all`
- **报告目录：** `/nfs/home/cirunner/perf-report-custom`

### 8.3 报告生成与历史对比

`report` job 在 `run` 完成后执行：

1. **创建符号链接：** 将当前报告目录链接为 `nightly-{DATE}`
2. **查找历史报告：** 通过 `find ... | sort | tail -n1` 找到上一次 nightly 报告
3. **执行 diff：** 调用 `nightly_perf_diff.py` 对比当前与上一次的分数
4. **上传 artifact：** 将 score 文件上传为 `score` artifact

---

## 9. nightly_perf_diff.py - 分数差异与覆盖率检测

### 9.1 功能概述

`nightly_perf_diff.py` 是一个独立的 Python 脚本，用于对比两个 `score.txt` 文件，生成包含百分比变化和覆盖率标记的表格。

**用法：** `nightly_perf_diff.py <current_score> <previous_score>`

### 9.2 核心逻辑

**解析函数 `parse_score()`：**
- 逐行读取 score.txt 文件
- 匹配以数字开头或 "SPEC" 开头的行
- 提取 benchmark 名称、分数值和覆盖率字段
- 返回 `{bench: (score, cov)}` 字典

**差异报告生成：**

1. **覆盖率不匹配检测：** 首先输出所有覆盖率发生变化的 benchmark，格式为 `{bench}: curr {cv} prev {pv}`
2. **分数对比表格：** 生成 Markdown 代码块，包含以下列：
   - `Bench`：benchmark 名称
   - `curr_score` / `prev_score`：当前/历史分数
   - `diff_pct`：百分比变化，附带颜色标记（绿色上升、红色下降、白色持平）
   - `cov_change`：覆盖率是否变化（checkmark / cross mark）
3. **SPEC 汇总指标：** 单独输出 `SPECint2006/GHz`、`SPECfp2006/GHz`、`SPEC2006/GHz` 的变化

### 9.3 颜色编码

- 绿色圆圈（`+` 百分比）：性能提升
- 红色圆圈（`-` 百分比）：性能下降
- 白色圆圈（`0` 百分比）：无变化

---

## 10. Change-Aware 触发机制

### 10.1 过滤器定义

`.github/filters.yaml` 采用排除式定义 `core` 变更集。被排除的文件包括：

- GitHub 配置文件（ISSUE_TEMPLATE、CODEOWNERS、labeler.yml）
- Workflow 文件自身（避免 workflow 变更触发测试循环）
- 文档（*.md、LICENSE、images）
- nightly/perf 相关 workflow（避免与自身交叉触发）

### 10.2 应用方式

所有三个 push/PR 触发的 workflow（`emu-basics.yml`、`emu.yml`、`emu-performance.yml`）都在第一步执行变更检测：

```yaml
needs: changes
if: ${{ needs.changes.outputs.core == 'true' }}
```

这种设计确保了：
- 文档更新不会触发耗时的仿真编译
- Workflow 自身的修改不会触发测试循环（避免无限递归）
- 只有核心代码变更才消耗宝贵的 CI 资源

---

## 11. Builder-Runner 分离架构深度分析

### 11.1 设计动机

XiangShan 的自托管 CI 基础设施面临两个核心挑战：
1. **EMU 二进制过大（约 100MB）**：GitHub Actions 原生 artifact 传输在 self-hosted runner 上带宽不足
2. **资源异构性**：builder 节点需要大量编译线程（8-16），而 runner 节点设计为单线程 GSIM 运行

### 11.2 实现策略

1. **NFS 共享文件系统：** builder 编译后将 EMU 二进制拷贝到 NFS 路径，runner 从同一路径读取
2. **集群感知：** builder 检测自身集群类型（`node` 或 `open`），通过 outputs 传递给 runner，确保 runner 在同一集群上运行
3. **缓存复用：** perf-template 中检查 EMU 二进制是否已存在，避免重复编译
4. **孤儿进程清理：** 每个 job 开头执行 `pkill -9 -e -f ${GITHUB_WORKSPACE}/build/emu` 清理可能的僵尸进程

### 11.3 不采用 Builder-Runner 的例外

`emu.yml` 中的杂项测试**不采用** Builder-Runner 分离，原因是：
- GSIM 测试需要 900 分钟超时和特定节点服务器
- Verilator 编译需要 16 线程
- 这些测试的运行频率低于功能测试和性能测试

---

## 12. 源文件位置汇总

所有相关文件均位于仓库的 `.github/workflows/` 目录下：

| 文件 | 绝对路径 |
|------|----------|
| emu-basics.yml | `/home/agi/workspace/gitwork/XiangShan/.github/workflows/emu-basics.yml` |
| emu.yml | `/home/agi/workspace/gitwork/XiangShan/.github/workflows/emu.yml` |
| emu-performance.yml | `/home/agi/workspace/gitwork/XiangShan/.github/workflows/emu-performance.yml` |
| emu-performance-summary.yml | `/home/agi/workspace/gitwork/XiangShan/.github/workflows/emu-performance-summary.yml` |
| emu-performance-comment.yml | `/home/agi/workspace/gitwork/XiangShan/.github/workflows/emu-performance-comment.yml` |
| perf-template.yml | `/home/agi/workspace/gitwork/XiangShan/.github/workflows/perf-template.yml` |
| perf-v2.yml | `/home/agi/workspace/gitwork/XiangShan/.github/workflows/perf-v2.yml` |
| perf-v3.yml | `/home/agi/workspace/gitwork/XiangShan/.github/workflows/perf-v3.yml` |
| perf-trigger.yml | `/home/agi/workspace/gitwork/XiangShan/.github/workflows/perf-trigger.yml` |
| nightly.yml | `/home/agi/workspace/gitwork/XiangShan/.github/workflows/nightly.yml` |
| nightly_perf_diff.py | `/home/agi/workspace/gitwork/XiangShan/.github/workflows/nightly_perf_diff.py` |
| filters.yaml | `/home/agi/workspace/gitwork/XiangShan/.github/filters.yaml` |
| check-usage.sh | `/home/agi/workspace/gitwork/XiangShan/.github/workflows/check-usage.sh` |
| check_verilog.py | `/home/agi/workspace/gitwork/XiangShan/.github/workflows/check_verilog.py` |

---

## 13. 关键设计洞察与工程实践总结

### 13.1 并行化策略

XiangShan 的 CI 体系在多个层次实现了并行化：
- **Workflow 级别：** emu-basics、emu、emu-performance 三个 workflow 并行触发
- **Job 级别：** Builder-Runner 分离，编译与运行解耦
- **Matrix 级别：** 12 项功能测试和 28 项性能测试通过 matrix strategy 并行执行
- **Checkpoint 级别：** perf-template 支持多服务器分布式运行 SPEC06 checkpoint

### 13.2 资源优化

- **Change-aware 触发：** 排除非核心变更，避免无效 CI 开销
- **EMU 二进制缓存：** perf-template 中检查二进制是否已存在，跳过重复编译
- **Checkpoint 密度选择：** 0.3c/0.8c/1.0c 三级精度，nightly 使用 0.3c 平衡速度与覆盖度
- **集群感知调度：** 自动选择同一集群运行，避免跨集群网络传输

### 13.3 可观测性

- **IPC 提取：** 所有性能测试均提取 IPC 指标，支持量化对比
- **Geomean 计算：** summary workflow 计算几何平均值，提供整体性能趋势
- **PR 评论推送：** 性能变化自动推送到 PR 评论，开发者无需离开 GitHub 界面
- **历史 Nightly 对比：** 每日运行自动与上一次结果对比，检测性能退化
- **覆盖率检测：** nightly_perf_diff.py 追踪测试覆盖率变化

### 13.4 模板化与可扩展性

perf-template.yml 的设计体现了良好的工程实践：
- 通过 `workflow_call` 实现模板复用
- 11 种 benchmark_type 覆盖不同编译器、不同采样密度
- 支持 `custom` 模式允许用户指定任意 checkpoint 和 JSON 路径
- Constantin 配置文件支持动态参数注入
- dry-run 模式用于流程验证，不消耗实际 benchmark 资源
