# R30 - XiangShan CI/CD Pipeline 深度研究报告

## 1. 总体 CI/CD 架构概述

XiangShan RISC-V 处理器项目采用了一套高度自动化、多层级的 CI/CD（Continuous Integration / Continuous Delivery）流水线体系，用于保障核心 RTL 代码的质量、功能正确性、性能回归以及发布可靠性。整套流水线基于 GitHub Actions 构建，结合自托管（self-hosted）runner 集群完成大规模仿真和性能测试任务，形成了覆盖「提交即验证、合入前回归、每日夜测、周期性能回归、发布打包」全生命周期的工程保障体系。

### 1.1 核心设计理念

XiangShan 的 CI/CD 系统体现了以下核心设计理念：

- **变更感知（Change-Aware）触发**：通过 `dorny/paths-filter` 和自定义的 `.github/filters.yaml` 过滤规则，仅在核心代码（排除文档、CI 配置本身、镜像等非核心文件）发生变更时才触发仿真测试，大幅节约计算资源。
- **并行化拆分（Parallelized Splitting）**：将原本单一的 EMU 测试工作流拆分为 `emu-basics.yml`（功能测试）、`emu-performance.yml`（性能测试）、`emu.yml`（杂项/不可并行测试）三个独立工作流并行运行，缩短整体反馈时间。
- **Builder-Runner 分离模式**：构建（build）阶段使用高计算资源的 `builder` 节点，运行（run）阶段使用 `runner` 节点，且确保同一 job 的 build 和 run 阶段使用同一集群（`node` 或 `open`），以避免跨集群的 NFS 拷贝开销和 PGO profile 不兼容问题。
- **多仿真器支持**：同时支持 Verilator（多线程，用于功能测试和波形生成）、GSIM（轻量级，用于性能测试）和 VCS/SIMV（EDA 仿真器，用于高精度验证）三种仿真后端。
- **SPEC CPU2006 全覆盖性能回归**：以 SPEC CPU2006 基准测试套件作为核心性能指标，覆盖 28 个 benchmark，支持多种 checkpoint 集群配置（0.3c、0.8c、1.0c），提供 IPC 和 SPEC Score/GHz 双维度性能度量。

### 1.2 工作流总览

| 工作流文件 | 名称 | 触发方式 | 主要职责 |
|---|---|---|---|
| `emu-basics.yml` | EMU Basics Test | push / PR to kunminghu-v3 | 基础功能测试（cputest, riscv-tests, Linux 启动等） |
| `emu-performance.yml` | EMU Performance Test | push / PR to kunminghu-v3 | SPEC CPU2006 快速性能测试（IPC） |
| `emu.yml` | EMU Misc Test | push / PR to kunminghu-v3 | GSIM 功能测试、SimFrontend、多核测试、SIMV、Verilog 检查、代码格式检查 |
| `emu-performance-summary.yml` | EMU Performance Summary | workflow_dispatch | 从 performance test 结果生成 IPC 对比报告并发布 PR 评论 |
| `emu-performance-comment.yml` | EMU Performance Comment Trigger | issue_comment | 监听 `/diff` 命令触发性能对比 |
| `perf-template.yml` | Performance Regression Template (Unified) | workflow_call | 性能回归测试的统一模板 |
| `perf-trigger.yml` | Performance Regression Trigger | workflow_dispatch（手动） | 交互式触发完整性能回归 |
| `perf-v2.yml` | Performance Regression V2 | workflow_dispatch | master 分支性能回归 |
| `perf-v3.yml` | Performance Regression V3 | 定时（每周五 UTC 4:00） / 手动 | kunminghu-v3 分支性能回归 |
| `nightly.yml` | Nightly Regression | 每日定时 UTC 15:33 | 夜间性能回归 + 与前日对比 |
| `nightly_perf_diff.py` | （Python 脚本） | 被 nightly.yml 调用 | 解析 SPEC score 文件并计算差异 |
| `release.yml` | Release Jobs | push / PR / workflow_call | Docker 镜像构建、XSPdb 构建、Verilog 生成归档 |
| `release-trigger.yml` | Release Jobs Trigger | workflow_dispatch（手动） | 手动触发 release 流水线 |
| `logrotate.yml` | Logrotate | 每周日 UTC 14:00 / 手动 | 清理超过 30 天的 CI 结果（压缩），超过 90 天的删除 |
| `pr-labeler.yml` | Pull Request Labeler | pull_request_target | 自动为 PR 添加模块标签 |

### 1.3 基础设施与运行环境

XiangShan CI/CD 系统的运行环境包括：

- **GitHub-hosted runner**：用于轻量级任务（changes detection、格式检查、Docker 构建、子模块检查等），运行在 `ubuntu-latest`。
- **自托管 runner 集群**：
  - `builder` 节点：高算力构建节点，配备 16+ 线程 Verilator 编译能力。
  - `runner` 节点：仿真运行节点，设计用于单线程 GSIM 运行。
  - `node` 集群（`*.bosccluster.com`）：BOSC 内部集群，网络性能更优，用于 GSIM 仿真。
  - `open` 集群：开放集群。
  - `eda` 节点：专门用于 VCS/SIMV 仿真，通过 SSH 远程连接到 `eda01` 服务器。
  - `perf` 节点：用于完整性能回归测试的专用节点。
- **NFS 共享存储**：CI 工作负载（NEMU、nexus-am、DRAMsim3、checkpoint profiles）存储在 `/nfs/home/share/ci-workloads/`，CI 结果存储在 `/nfs/home/ci-runner/`，确保不同集群节点间共享数据。

---

## 2. 仿真测试流水线（Emulation Test Pipeline）

### 2.1 基础功能测试 - EMU Basics Test (`emu-basics.yml`)

`emu-basics.yml` 是 XiangShan 最核心的快速功能验证流水线，负责确保每次提交不会破坏基本功能。其流程为：

**Changes Detection → Build → Run (矩阵并行) → Upload → Summary**

#### 2.1.1 变更检测（Changes Detection）

使用 `dorny/paths-filter@v4` 配合 `.github/filters.yaml` 判断核心代码是否变更。`filters.yaml` 定义了一个 `core` 过滤规则，采用排除法：排除 `.github/ISSUE_TEMPLATE/`、`CODEOWNERS`、`labeler.yml`、`logrotate.yml`、`nightly.yml`、性能回归模板和触发器、`.gitignore`、所有 `*.md` 文件、`LICENSE` 和 `images/` 目录。只有核心 Scala 源码、子模块、构建脚本等发生变更时，才会触发后续仿真测试。

#### 2.1.2 构建阶段（Build）

- 运行在 `builder` 自托管 runner 上，超时 180 分钟。
- 使用 Verilator 8 线程编译 EMU，启用 PGO（Profile-Guided Optimization）优化，以 `coremark-2-iteration.bin` 为 profile 训练输入。
- 启用 FST 波形转储（`--trace-fst`），便于调试。
- 构建产物通过 NFS 共享目录传递（`PERF_HOME`），而非 GitHub artifact，因为约 100MB 的 EMU 二进制文件在自托管 runner 上通过网络上传效率低下。

#### 2.1.3 运行阶段（Run）

采用矩阵策略（matrix strategy），并行运行 12 个基础测试用例：

| 测试用例 | 说明 | 特殊配置 |
|---|---|---|
| `cputest` | CPU 基础指令集测试 | 无 |
| `riscv-tests` | RISC-V 标准测试套件 | 指定外部 riscv-tests 路径 |
| `misc-tests` | 杂项功能测试 | 无 |
| `rvh-tests` | RISC-V H 扩展（Hypervisor）测试 | 无 |
| `microbench` | 微基准测试（IPC 报告） | `report: true` |
| `coremark` | CoreMark 基准测试（IPC 报告） | `report: true` |
| `linux-hello-opensbi` | Linux 内核启动测试（IPC 报告） | `report: true` |
| `iopmp-test` | IO PMP 测试 | `--no-diff` |
| `povray` | SPEC CPU2006 povray 快照恢复测试 | 500 万条指令限制 |
| `copy_and_run` | flash 加载 + workload 执行 | 使用 flash 和 workload 参数 |
| `f16_test` | 半精度浮点测试 | 无 |
| `zcb-test` | Zcb 扩展测试 | 无 |

注意：由于 Vector 重构进行中，`rvv-bench` 和 `hmmer-Vector` 等 Vector 相关测试暂时禁用。

运行时参数包括 `--numa`（NUMA 感知内存分配）和 `--threads 8`（8 线程仿真）。对于需要 IPC 报告的测试用例（`report: true`），stderr 输出被重定向到文件并排序，IPC 值通过正则提取并存储为 artifact。

#### 2.1.4 上传与汇总

Upload job 将所有 IPC 报告文件上传为 GitHub artifact（`ipc-reports`）。Summary job 在 `ubuntu-latest` 上运行，下载所有 IPC 报告并生成 Markdown 表格，写入 `$GITHUB_STEP_SUMMARY`。

### 2.2 杂项测试 - EMU Misc Test (`emu.yml`)

`emu.yml` 承载了不能简单并行化的各种杂项测试和代码质量检查，包括多个主要测试 job：

#### 2.2.1 GSIM 功能测试（emu-gsim）

使用 GSIM 仿真器构建并运行 Linux 内核启动测试。GSIM 是一种轻量级仿真后端，超时设为 900 分钟（15 小时），运行在 `node` 集群上。由于 GSIM 存在性能问题（在 open 集群上），暂时只在 node 集群运行。

#### 2.2.2 SimFrontend 测试（emu-simfrontend）

测试 XiangShan 的前端仿真（SimFrontend）功能。使用 Verilator 8 线程构建，启用 `--simfrontend` 参数。运行三个 SPEC CPU2006 benchmark（astar、milc、xalancbmk），每个运行 500 万条指令，并提取 IPC 值。结果通过 `$GITHUB_STEP_SUMMARY` 以表格形式展示。

#### 2.2.3 多核测试（emu-mc）

验证 XiangShan 的多核（Multi-Core）功能。使用 Verilator 16 线程构建双核（`--num-cores 2`）EMU。测试包含两个阶段：
- 基础多核测试（mc-tests），使用 `riscv64-nemu-interpreter-dual-so` 进行 diff 验证。
- SMP Linux 启动测试（linux-hello-smp-new），验证多核对称多处理 Linux 内核启动。

#### 2.2.4 SIMV 基础测试（simv-basics）

在 EDA 仿真器（VCS/SIMV）上运行测试。通过 SSH 远程连接到 `eda01` 服务器执行仿真。流程为：生成 Verilog → 远程构建 VCS SIMV → 远程运行 CoreMark 1 iteration 测试。多个其他测试（rvv-test、MicroBench、cputest、Full CoreMark、Linux 启动）目前被注释禁用。

#### 2.2.5 Verilog 检查（check-verilog）

这是代码质量保障的关键环节，包含多项检查：
- **BoringUtils 使用检查**：通过 `check-usage.sh` 确保核心代码中不使用 BoringUtils（一种 Chisel 调试工具，不适合生产代码）。
- **独立设备生成验证**：分别验证 TileLink 和 AXI4 接口的 StandAloneCLINT、StandAloneSYSCNT、StandAloneDebugModule、StandAlonePLIC 设备生成。
- **XSNoCTop Verilog 生成与检查**：生成 XSNoCTop 配置的 Verilog 并通过 `check_verilog.py` 进行质量检查。
- **多核 Verilog 生成与检查**：生成双核 Verilog 并检查。
- **Chirrtl 生成**：验证 Chirrtl 中间表示的正确生成。
- **MinimalConfig Release EMU**：构建最小配置的 Release EMU 并运行 Linux 启动测试。
- **NOC Difftest 接口检查**：生成 NOC 配置的 XiangShan 和 Difftest Verilog，验证两者之间的接口兼容性，使用 Verilator lint-only 模式检查。

#### 2.2.6 子模块检查（check-submodules）

确保所有子模块（rocket-chip、difftest、ready-to-run、utility、yunsuan、XSCache、ChiselAIA）的 HEAD commit 已合入其默认分支的最新状态，防止使用未合入上游的代码。

#### 2.2.7 格式检查（check-format）

安装 GraalVM Java 21 和 Mill 构建工具，运行 `make check-format` 验证代码格式。

---

## 3. 性能回归测试（Performance Regression Testing）

### 3.1 EMU 性能测试 - EMU Performance Test (`emu-performance.yml`)

这是 PR 级别的快速性能测试流水线，采用 Builder-Runner 分离架构。

#### 3.1.1 构建阶段

使用 GSIM 仿真器构建 EMU（默认配置），启用 DRAMsim3 内存仿真器、PGO 优化和 FST 波形转储。构建超时 180 分钟。

#### 3.1.2 运行阶段 - 两套并行矩阵

**Legacy 矩阵（run-legacy）**：运行 10 个经典 benchmark（mcf、xalancbmk、gcc、namd、milc、lbm、gromacs、wrf、astar 等），使用传统的 `--ci` 模式运行，每个运行 500 万条指令。并行度为 4。

**新矩阵（run）**：运行 28 个 SPEC CPU2006 benchmark，使用 checkpoint 恢复模式。每个 benchmark 有对应的 checkpoint 编号（如 `mcf: 6388`、`gcc_s04: 2772` 等），从预构建的 checkpoint 目录恢复。并行度为 12（计划在弃用 legacy 后提升到 16）。

两套矩阵都提取 IPC 值并存档日志（tar.gz 格式）。

#### 3.1.3 Summary 与 Comment 触发

Upload job 上传 IPC 报告后，dispatch-summary job 通过 `gh api` 调度 `emu-performance-summary.yml` 工作流。对于 fork PR，由于 GITHUB_TOKEN 权限限制，跳过 summary 调度。

### 3.2 性能汇总 - EMU Performance Summary (`emu-performance-summary.yml`)

此工作流接收 HEAD_SHA、BASE_SHA、PR 编号等输入，执行以下步骤：

1. **查找 Run ID**：通过 `gh run list` 查找 HEAD 和 BASE commit 对应的 EMU Performance Test 运行 ID。
2. **下载报告**：使用 `actions/download-artifact@v8` 下载 HEAD 和 BASE 的 IPC 报告。
3. **生成对比报告**：使用 shell 函数计算几何均值（geomean）和百分比差异，生成包含 IPC Report 表格和 GEOMEAN 行的 Markdown 报告。
4. **发布评论**：使用 `gh pr comment` 在 PR 上发布或更新性能对比报告。

### 3.3 性能评论触发器 (`emu-performance-comment.yml`)

监听 PR 上的 issue comment 事件，当用户发布 `/diff` 或 `/diff <BASE_SHA>` 命令时，自动触发性能对比分析。支持自动检测 PR base SHA 或手动指定 base commit。

### 3.4 统一性能回归模板 (`perf-template.yml`)

这是完整性能回归测试的核心模板，被 `perf-v2.yml`、`perf-v3.yml`、`nightly.yml`、`perf-trigger.yml` 共同调用。其输入参数高度可配置：

- **test_branch**：目标分支或 commit。
- **emulator**：仿真器类型（gsim 默认，可选 verilator）。
- **xs_config**：XiangShan 配置（DefaultConfig、BackendV2Config）。
- **benchmark_type**：基准测试类型，支持 11 种预设配置（gcc15/gcc12/xscc 的 0.3c/0.8c/1.0c 变体）和 custom/dryrun。
- **cst_file**：Constantin 动态参数文件路径。
- **servers**：运行服务器选择。
- **benchmarks**：指定特定 benchmark 子集。

#### 3.4.1 核心流程

1. **环境配置**：根据 benchmark_type 设置 checkpoint 路径和 JSON 文件路径。不同配置对应不同的 checkpoint profiles（如 gcc15 使用 `spec06_gcc15_rv64gcb_base_260122`，gcc12 使用 `spec06_rv64gcb_O3_20m_gcc12.2.0-intFpcOff-jeMalloc`）。
2. **构建 EMU**：使用 DRAMsim3、PGO 优化，根据仿真器类型选择 1 线程（gsim）或 8 线程（verilator）。支持 Constantin 编译选项。
3. **运行 SPEC CPU2006**：调用 `perf_trigger/main.py` 脚本，支持多服务器分布式运行、日志级别控制。
4. **报告 SPEC Score**：调用 `xs_autorun_multiServer.py` 生成 SPEC2006/GHz、SPECint2006/GHz、SPECfp2006/GHz 等关键性能指标。

输出包括 `SHORT_SHA`、`DATE`、`SPEC_DIR`、`SCORE_FILE` 等环境变量，供下游工作流使用。

### 3.5 性能回归触发器 (`perf-trigger.yml`)

提供手动触发界面（`workflow_dispatch`），允许用户通过 GitHub Actions UI 选择所有参数（分支、仿真器、配置、benchmark 类型、特定 benchmark 列表、Constantin 文件、服务器列表等），触发完整的性能回归测试。

### 3.6 V2/V3 分支性能回归

- **perf-v2.yml**：针对 master 分支的性能回归，报告目录为 `/nfs/home/cirunner/perf-report-master`，手动触发。
- **perf-v3.yml**：针对 kunminghu-v3 分支的性能回归，每周五 UTC 4:00（北京时间中午 12:00）自动触发，也可手动触发。报告目录为 `/nfs/home/cirunner/perf-report-kmhv3`，并额外上传 score artifact。

---

## 4. 夜间测试与性能差异分析（Nightly Testing and Performance Diff）

### 4.1 夜间回归 - Nightly Regression (`nightly.yml`)

每日 UTC 15:33（北京时间 23:33）自动运行，使用 `perf-template.yml` 模板在 kunminghu-v3 分支上运行 `gcc15-spec06-0.3c` 配置的性能回归测试（0.3c 表示 30% checkpoint 粒度，运行时间较短，适合每日运行）。

报告阶段执行以下操作：
1. **符号链接管理**：为当前日期创建 `nightly-YYYYMMDD` 符号链接指向当次测试结果目录。
2. **查找前次报告**：在报告目录中查找最近的 `nightly-*` 符号链接。
3. **差异分析**：调用 `nightly_perf_diff.py` 脚本对比当前和前次的 SPEC score 文件，将结果输出到 `$GITHUB_STEP_SUMMARY`。

### 4.2 夜间性能差异脚本 (`nightly_perf_diff.py`)

这是一个 Python 脚本，用于解析 SPEC CPU2006 的 `score.txt` 文件并生成差异报告：

- **Score 解析**：解析每行 benchmark 名称、分数和覆盖率。
- **覆盖率不匹配检测**：首先报告所有覆盖率（coverage）发生变化的 benchmark。
- **差异表格生成**：生成包含当前分数、前次分数、百分比差异和覆盖率变化标记的 Markdown 表格。使用绿色圆点（表示性能提升）、红色圆点（表示性能下降）、白色圆点（表示无变化）进行可视化。
- **SPEC 指标汇总**：额外输出 SPECint2006/GHz、SPECfp2006/GHz、SPEC2006/GHz 三个聚合指标的差异。

---

## 5. 发布流水线（Release Pipeline）

### 5.1 Release Jobs (`release.yml`)

此工作流在 push 或 PR 到 kunminghu-v3 分支时自动触发，也可通过 `release-trigger.yml` 手动触发。包含三个主要 job：

#### 5.1.1 XSDev Docker 镜像构建（build-xsdev-image）

- 在 `ubuntu-latest` 上运行，使用 Docker Buildx 构建多阶段镜像。
- 镜像推送到 GitHub Container Registry（`ghcr.io`），标签为 `master`。
- 仅在 `master` 分支上实际推送（`push: ${{ github.ref == 'refs/heads/master' }}`），PR 上仅构建不推送。
- 需要 `packages: write` 权限和 `GITHUB_TOKEN` 认证。

#### 5.1.2 XSPdb 构建（build-xspdb）

在 `builder` + `open` 集群上构建 XSPdb（XiangShan 调试器数据库）：
- 使用 UnityChip 环境激活脚本。
- 运行 `make pdb` 和 `make package-pdb` 生成打包文件。
- PR 上执行测试运行（`make pdb-run`）。
- 在 master 或 kunminghu-v3 分支的 push 事件上上传 artifact。

#### 5.1.3 Verilog 生成与归档（artifacts-uploading）

在 `ubuntu-latest` 上运行，执行三项主要的 Verilog 生成任务：

1. **CHI Issue B 配置**：生成带 difftest 的 XSNoCTop Verilog，使用 `ISSUE=B` 和 `XSTOP_PREFIX=bosc_`，归档为 `xs-issue-b-difftest-verilog`。
2. **CHI Issue E.b 配置**：生成带 difftest 的 XSNoCTop Verilog，使用 `ISSUE=E.b`，归档为 `xs-issue-e-b-difftest-verilog`。
3. **CHI Issue E.b Lowpower 配置**：使用 `Poweroff.yml` 配置生成低功耗版本，归档为 `xs-issue-e-b-lowpower-difftest-verilog`。

每次生成前都重新生成独立设备（StandAlone CLINT、SYSCNT、DebugModule、PLIC），清理临时文件（`.fir`），并生成 `filelist.f`。最后还生成 `test-jar`（测试 JAR 包）作为 `xsgen` artifact 上传。

值得注意的是，该 job 在 `ubuntu-latest`（GitHub-hosted）上运行，内存受限，因此需要配置 32GB swapfile 来处理大型 Verilog 生成任务。

### 5.2 Release 触发器 (`release-trigger.yml`)

提供简单的手动触发界面，只需输入测试备注和分支名，即可调用 `release.yml` 工作流。

---

## 6. PR 标签管理与代码所有权（PR Labeling and Code Ownership）

### 6.1 PR 自动标签（`pr-labeler.yml` + `labeler.yml`）

使用 `actions/labeler@v6` 在 PR 创建时自动添加模块标签。标签规则基于文件路径匹配：

| 标签 | 匹配路径 |
|---|---|
| `module: frontend` | `src/main/scala/xiangshan/frontend/**` |
| `module: backend` | `src/main/scala/xiangshan/backend/**`、`yunsuan` |
| `module: memory` | `src/main/scala/xiangshan/cache/**`、`src/main/scala/xiangshan/mem/**`、`XSCache` |
| `module: top` | `src/main/scala/xiangshan/*`、`src/main/scala/top/**`、`src/main/scala/system/**`、`src/main/resources/config/**` |
| `module: utility` | `src/main/scala/utils/**`、`macros/**`、`utility` |
| `module: tool` | `src/main/resources/*`、`src/test/**`、`.github/**`、`difftest`、`ready-to-run`、`scripts/**` 等 |
| `module: other` | `src/main/scala/device/**`、`ChiselAIA`、`ChiselIOPMP`、`rocket-chip` |
| `module: documentation` | `images/**`、`**.md`、`LICENSE` |
| `note: submodule bump` | `.gitmodules` 及所有子模块目录 |

此外还基于分支名前缀自动添加主题标签：`feat`/`fix` → `topic: functionality`，`perf` → `topic: performance`，`refactor` → `topic: code quality`，`power` → `topic: power`，`area` → `topic: area`，`timing` → `topic: timing`。

### 6.2 代码所有权（CODEOWNERS）

`CODEOWNERS` 文件定义了详细的代码审查责任矩阵：

| 模块 | 主要负责人 | 协作负责人 |
|---|---|---|
| `frontend/` | @rich-cake | - |
| `frontend/bpu/` | @eastonman | @rich-cake |
| `frontend/ftq/` | @Yan-Muzi | @rich-cake |
| `frontend/ibuffer/` | @rich-cake | - |
| `frontend/icache/` | @ngc7331 | @rich-cake |
| `frontend/ifu/` | @ngc7331 | @my-mayfly, @rich-cake |
| `backend/` (总体) | @lewislzh | - |
| `backend/Region.scala` | @xiaofeibao-xjtu | @lewislzh |
| `backend/CtrlBlock.scala` | @wissygh | @lewislzh |
| `backend/datapath/` | @xiaofeibao-xjtu | @lewislzh |
| `backend/decode/` | @HeiHuDie | @lewislzh |
| `backend/dispatch/` | @xiaofeibao-xjtu | @lewislzh |
| `backend/exu/` | @sinceforYy | @lewislzh |
| `backend/fu/` | @sinceforYy | @lewislzh |
| `backend/fu/NewCSR/` | @huxuan0307 | @lewislzh |
| `backend/issue/` | @xiaofeibao-xjtu | @lewislzh |
| `backend/regcache/` | @xiaofeibao-xjtu | @lewislzh |
| `backend/regfile/` | @xiaofeibao-xjtu | @lewislzh |
| `backend/rename/` | @Tang-Haojin | @lewislzh |
| `backend/rob/` | @NewPaulWalker | @xiaofeibao-xjtu, @lewislzh |
| `backend/trace/` | @wissygh | @lewislzh |
| `cache/` (总体) | @linjuanZ | - |
| `cache/dcache/` | @Maxpicca-Li | @linjuanZ |
| `cache/mmu/` | @good-circle | @cebarobot, @linjuanZ |
| `cache/wpu/` | @Maxpicca-Li | @linjuanZ |
| `mem/` (总体) | @linjuanZ | - |
| `mem/lsqueue/` | @good-circle | @linjuanZ |
| `mem/mdp/` | @weidingliu | @linjuanZ |
| `mem/pipeline/` | @good-circle | @linjuanZ |
| `mem/prefetch/` | @happy-lx | @Maxpicca-Li, @linjuanZ |
| `mem/sbuffer/` | @good-circle | @linjuanZ |
| `mem/vector/` | @weidingliu | @good-circle, @linjuanZ |
| `L2Top.scala` / `XSTile.scala` | @linjuanZ | - |
| `coupledL2/` / `huancun/` | @linjuanZ | - |
| `top/` | @Tang-Haojin | - |
| PDB 相关脚本 | @yaozhicheng | @forever043, @SFangYy, @Tang-Haojin |

---

## 7. Verilog 检查（Verilog Checking）

### 7.1 check_verilog.py - Verilog 质量检查器

位于 `.github/workflows/check_verilog.py`，是一个纯 Python 脚本，对生成的 Verilog 文件执行多维度质量检查：

#### 7.1.1 XSTile 去重检查

读取 `filelist.f`，检查 `XSTile` 模块（排除 `XSTileWrap`）是否出现多次。如果发现重复的 XSTile 定义，报错并提示："Please convert Map, Set to Seq and sort it to generate RTL in Scala. And always use HartID from IO."

这反映了 XiangShan 从多核到单核 RTL 生成过程中的一个重要工程约束：Scala 中的集合操作（Map、Set）的非确定性迭代顺序可能导致 RTL 生成重复的模块定义。

#### 7.1.2 语句级检查

遍历所有 Verilog 文件，执行以下检查：

- **禁止 `$fatal` 和 `$fwrite`**：除了包含 "Commit SHA" 的语句外，不允许在生成的 Verilog 中包含调试输出语句。
- **Decode 模块中禁止 `_pc`**：PC 信号不应出现在 Decode 阶段，这违反了 XiangShan 的前端-后端分离设计原则。
- **Dispatch 模块中禁止 `_lsrc`**：逻辑源寄存器索引不应出现在 Dispatch 阶段。
- **MissEntry 模块中禁止 `refill_data_raw`**：原始 refill 数据不应出现在 MissEntry 中，这可能导致缓存一致性问题。
- **禁止同步复位**：检查 `always @(posedge clock) begin` 块中是否包含 `if (reset) begin`，XiangShan 设计采用异步复位策略。

### 7.2 check-usage.sh - BoringUtils 使用检查

一个简单的 bash 脚本，使用 `grep -rn` 在 `src/main/scala/xiangshan` 目录下搜索指定模式（默认为 "BoringUtils"）。如果找到匹配，返回退出码 1（失败），阻止 CI 通过。BoringUtils 是 Chisel 提供的跨模块信号连接工具，适合快速原型验证，但在生产级 RTL 中应使用正式的 IO 接口。

---

## 8. 日志管理（Log Management）

### 8.1 Logrotate 工作流 (`logrotate.yml`)

这是一个自动化的 CI 结果生命周期管理工作流，类比 Linux 系统中的 logrotate 机制。

#### 8.1.1 触发与调度

- **自动触发**：每周日 UTC 14:00（北京时间 22:00）自动运行。
- **手动触发**：支持 `workflow_dispatch`，可选择 dry-run 模式仅打印操作而不实际执行。
- **并发控制**：使用 `concurrency` 组 `logrotate`，确保同一时间只有一个清理任务在运行（`cancel-in-progress: true`）。
- **权限隔离**：设置 `permissions: {}`，不需要仓库 token 权限。

#### 8.1.2 清理策略

采用矩阵策略，在 `open` 和 `node` 两个集群上分别清理五个目标目录：

| 目录 | 来源工作流 |
|---|---|
| `/nfs/home/ci-runner/emu-basics` | EMU Basics Test |
| `/nfs/home/ci-runner/emu-performance` | EMU Performance Test |
| `/nfs/home/ci-runner/perf-report-custom` | Performance Regression |
| `/nfs/home/ci-runner/xs-wave` | EMU Miscs Test (波形) |
| `/nfs/home/ci-runner/xs-perf` | EMU Miscs Test (性能) |

**归档阶段（>30 天）**：
- 使用 `find -mtime +30` 查找超过 30 天的目录。
- 空目录直接删除。
- 非空目录使用 `tar -I "zstd -19 -T0"` 压缩归档为 `.tar.zst` 文件（使用 zstd 最高压缩级别 19 和多线程压缩），然后删除原始目录。

**清理阶段（>90 天）**：
- 查找超过 60 天的 `.tar.zst` 文件（因为它们在 >30 天时被创建，所以总共 >90 天）。
- 直接删除这些过期的压缩文件。

`max-parallel: 1` 确保矩阵任务串行执行，避免资源争用。该设计选择使用 GitHub Actions 而非本地 cron job 执行清理，是为了防止单点故障——如果某台服务器宕机，清理任务仍可在其他 runner 上执行。

---

## 9. 关键工作流文件位置与总结

### 9.1 文件位置清单

```
.github/
├── CODEOWNERS                          # 代码所有权定义
├── filters.yaml                        # 路径过滤规则（变更检测用）
├── labeler.yml                         # PR 自动标签规则
└── workflows/
    ├── emu.yml                         # EMU 杂项测试（GSIM、SimFrontend、MC、SIMV、Verilog 检查、格式检查）
    ├── emu-basics.yml                  # EMU 基础功能测试
    ├── emu-performance.yml             # EMU 性能快速测试（SPEC CPU2006 IPC）
    ├── emu-performance-summary.yml     # 性能汇总报告生成与 PR 评论
    ├── emu-performance-comment.yml     # /diff 命令监听与触发
    ├── perf-template.yml               # 统一性能回归测试模板
    ├── perf-trigger.yml                # 手动性能回归触发器
    ├── perf-v2.yml                     # master 分支性能回归
    ├── perf-v3.yml                     # kunminghu-v3 分支性能回归（每周五定时）
    ├── nightly.yml                     # 每日夜间回归测试
    ├── nightly_perf_diff.py            # 夜间性能差异分析 Python 脚本
    ├── release.yml                     # 发布流水线（Docker、XSPdb、Verilog 归档）
    ├── release-trigger.yml             # 手动发布触发器
    ├── logrotate.yml                   # CI 日志轮转清理
    ├── pr-labeler.yml                  # PR 自动标签
    ├── check_verilog.py                # Verilog 质量检查脚本
    └── check-usage.sh                  # BoringUtils 使用禁止检查
```

### 9.2 整体架构图

XiangShan CI/CD 流水线的整体架构可以用以下层次来描述：

**触发层**：push/PR → 变更检测 → 根据变更范围触发不同测试集。定时任务（nightly、weekly perf-v3、logrotate）独立触发。

**构建层**：Builder 节点编译 Chisel → Verilog → EMU 二进制（支持 Verilator/GSIM/VCS 多后端），使用 PGO 优化和 DRAMsim3 内存仿真。

**测试层**：
- 快速路径（PR 级）：emu-basics（12 个功能测试） + emu-performance（28 个 SPEC benchmark IPC 测试） + emu（GSIM/SimFrontend/MC/SIMV/Verilog 检查） 并行运行。
- 完整路径（手动/定时）：perf-template（SPEC CPU2006 全量 Score 测试，支持多服务器分布式运行）。

**报告层**：IPC 报告 → 性能汇总 → PR 评论（自动或 `/diff` 命令触发）。夜间回归自动对比前日结果。

**维护层**：Logrotate 自动清理过期 CI 结果，PR Labeler 自动分类，CODEOWNERS 定义审查责任。

**发布层**：Docker 镜像构建推送、XSPdb 构建归档、多配置 Verilog 生成与 artifact 上传。

### 9.3 关键技术特点总结

1. **混合运行时架构**：GitHub-hosted（轻量级检查）+ 自托管（重仿真任务）+ NFS 共享存储（数据传递），在成本和性能之间取得平衡。
2. **多粒度性能回归**：从 PR 级快速 IPC 检测（500 万条指令）到完整 SPEC Score 测试（0.3c/0.8c/1.0c），支持不同场景需求。
3. **灵活的基准测试配置**：支持 11 种预设 benchmark 配置和自定义配置，覆盖不同编译器版本（gcc12/gcc15）、不同 checkpoint 粒度和 XSCC 变体。
4. **Constantin 动态参数支持**：通过 `--with-constantin` 编译选项和 `--cst-file` 运行时参数，支持运行时可调参数的性能测试。
5. **多 CHI 协议版本支持**：Release 流水线生成 Issue B、Issue E.b、Issue E.b Lowpower 三种 CHI 协议版本的 Verilog，支持不同芯片集成需求。
6. **完善的代码质量保障**：从 Scala 代码格式检查、Verilog 结构检查、子模块合入状态检查到 BoringUtils 使用禁止，多层次保障代码质量。
