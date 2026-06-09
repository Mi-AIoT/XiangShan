# R30B - Release & Quality Gates: 香山处理器发布管线与质量门禁深度解析

## 概述

香山(XiangShan) RISC-V 处理器项目的发布与质量保障体系由 GitHub Actions CI/CD 管线、自动化 Verilog 代码检查脚本、BoringUtils 禁用检测、CODEOWNERS 代码评审矩阵、PR 自动标签系统、日志轮转归档机制以及变更感知过滤器七大子系统协同构成。这些机制共同守护从源码提交到 RTL 产出物发布的每一道关口，确保交付到芯片设计团队手中的 Verilog 网表在结构完整性、电气时序和团队协作规范上均达到生产标准。以下按功能域逐一展开分析。

---

## 1. Release Pipeline: 发布管线 (release.yml / release-trigger.yml)

### 1.1 触发机制

发布管线采用双重触发模式，定义于 `.github/workflows/release-trigger.yml` 和 `.github/workflows/release.yml` 两个文件中：

- **自动触发 (Automatic)**: `release.yml` 在 `kunminghu-v3` 分支的 `push` 和 `pull_request` 事件上自动运行，同时支持 `workflow_call` 以便被其他管线复用。`workflow_call` 接口接受 `test_branch` 参数（默认 `kunminghu-v3`），允许从上游管线指定待测分支或 commit。
- **手动触发 (Manual)**: `release-trigger.yml` 通过 `workflow_dispatch` 提供带备注(`note`)和分支(`test_branch`)参数的手动触发入口，其 `run-name` 会将备注和分支名拼接显示，便于 CI 日志溯源。

两种触发路径最终都调用 `release.yml` 中定义的三个核心 job：`build-xsdev-image`、`build-xspdb`、`artifacts-uploading`。

### 1.2 Docker 镜像构建 (build-xsdev-image)

该 job 构建香山专用开发环境 Docker 镜像 `xsdev`，流程如下：

1. **Checkout**: 使用 `actions/checkout@v6` 拉取代码，支持通过 `inputs.test_branch` 或 `github.ref` 指定检出的 ref。
2. **Submodule 初始化**: 执行 `make init-force` 强制初始化所有 git 子模块。
3. **Docker 元数据**: 通过 `docker/metadata-action@v6` 生成镜像标签，默认标记为 `master`，维护者标签取自 `github.repository_owner`。
4. **构建与推送**: 使用 `docker/build-push-action@v7` 在 `linux/amd64` 平台构建镜像。镜像推送到 `ghcr.io/{owner}/xsdev` 的 GitHub Container Registry，但**仅当推送发生在 master 分支**时才执行 push 操作（通过 `${{ github.ref == 'refs/heads/master' }}` 条件控制），其他分支上的构建仅验证构建是否成功。

**Dockerfile 解读**（位于项目根目录 `Dockerfile`）：镜像基于 `ghcr.io/openxiangshan/xs-env:latest`（包含 Verilator 等 EDA 工具链的 xs-env 基础镜像），覆盖 `ENTRYPOINT` 为 `/bin/bash`，设置 `VERILATOR` 环境变量指向 wrapper 脚本。镜像内安装 Mill 构建工具，创建工作目录 `/work` 并挂载 `out` 和 `build` 为 volume。使用 `make deps` 预下载所有 Scala 依赖，利用 `--mount=type=bind` 将源码以只读方式挂载进构建上下文，避免将源码层缓存在镜像中。

### 1.3 XSPdb 构建 (build-xspdb)

该 job 在标记为 `builder` 和 `open` 的自托管 runner 上执行（使用开放服务器，保留 node 服务器供 emu 测试 job 使用），产出香山调试器 XSPdb 的发布包：

1. **版本命名**: 文件名格式为 `XSPdb-{YYYYMM}-{short_sha}.tar.bz2`，融合了日期和 commit hash 信息。
2. **构建**: 通过 `source $UNITYCHIP_ENV/activate` 激活统一芯片环境，执行 `make pdb` 编译调试器，`make package-pdb` 打包。
3. **冒烟测试 (PR-only)**: 仅在 `pull_request` 事件下，解压构建产物并执行 `make pdb-run` 进行验证运行。
4. **产物上传 (Push-only)**: 仅在 `push` 事件且目标分支为 `master` 或 `kunminghu-v3` 时，将压缩包通过 `actions/upload-artifact@v7` 上传为 CI artifact。

### 1.4 多变体 Verilog 产出物 (artifacts-uploading)

此 job 是发布管线中最为重量级的环节，负责生成多配置的 Verilog RTL 产出物，每次构建分配 32GB swapfile 以应对大内存需求。产出物包含四个维度：

| 产出物名称 | CHI Issue | 配置 | Difftest | 特殊参数 |
|---|---|---|---|---|
| `xs-issue-b-difftest-verilog` | Issue B | XSNoCTopConfig | 启用 | `ISSUE=B`, `XSTOP_PREFIX=bosc_` |
| `xs-issue-e-b-difftest-verilog` | Issue E.b | XSNoCTopConfig | 启用 | `ISSUE=E.b`, `XSTOP_PREFIX=bosc_` |
| `xs-issue-e-b-lowpower-difftest-verilog` | Issue E.b | XSNoCTopConfig + Poweroff.yml | 启用 | `ISSUE=E.b`, `YAML_CONFIG=...Poweroff.yml` |
| `xsgen` | N/A | 测试 jar | N/A | `make test-jar` -> `out/xiangshan/test/assembly.dest/out.jar` |

每个 Verilog 变体的生成流程均包括：
1. 清理上次构建残留
2. 生成独立设备 IP（StandAloneCLINT、StandAloneSYSCNT、StandAloneDebugModule、StandAlonePLIC），均使用 AXI4 接口（`DEVICE_TL=0`），固定基地址和位宽参数
3. 调用 `make verilog` 生成 Verilog，设置 `WITH_CONSTANTIN=0 WITH_CHISELDB=0` 禁用 Constantin 变量绑定和 ChiselDB 调试信息，限制 JVM 堆内存为 10GB
4. 删除 FIRRTL 中间文件以节省空间
5. 通过 `find . -name "*.*v" > filelist.f` 生成 verilog 文件列表
6. 上传整个 `build/` 目录作为 artifact

**低功耗变体**额外通过 `YAML_CONFIG=$NOOP_HOME/src/main/resources/config/Poweroff.yml` 注入低功耗配置，使能 WFI (Wait For Interrupt) 相关的电源管理逻辑。

---

## 2. check_verilog.py: Verilog 代码质量规则引擎

文件路径：`.github/workflows/check_verilog.py`

此脚本是香山 RTL 交付质量的核心守护者，读取 `build/rtl/filelist.f` 作为输入，逐文件扫描生成的 Verilog 网表，检测六类违规：

### 2.1 XSTile 去重检查 (XSTile Dedup)

脚本维护 `count_xstile` 计数器，遍历 `filelist.f` 中每一行。当发现以 `XSTile` 开头但不以 `XSTileWrap` 开头的条目时递增计数。若计数超过 1，则报错：

```
Found duplicated XSTile!
Please convert Map, Set to Seq and sort it to generate RTL in Scala.
And always use HartID from IO.
```

**设计意图**: 香山是多核处理器，理论上 XSTile（核心 + L1 缓存子系统的封装）应按有序列表生成。如果 Scala 代码中使用 `Map` 或 `Set` 等无序集合存储 tile 实例，可能导致非确定性的 RTL 展开，产生重复的 tile 模块定义。脚本强制要求将 `Map`/`Set` 转换为 `Seq` 并排序，确保多核配置下 tile 生成的确定性和唯一性。

### 2.2 禁止语句检查 (Forbidden Statements)

在每个 Verilog 源文件中，若检测到 `$fatal` 或 `$fwrite` 语句且其内容不包含 `"Commit SHA"`，则报错：

```
'fatal' or 'fwrite' statement was found!
```

**设计意图**: `$fatal` 和 `$fwrite` 通常是调试用途的打印语句，不应出现在最终发布的 RTL 中。唯一例外是包含 `"Commit SHA"` 的语句——这是 difftest 框架用于标记版本信息的标准格式。

### 2.3 模块级结构约束

脚本通过维护 `in_decode`、`in_dispatch`、`in_miss_entry` 三个模块进入标志，对特定模块中的信号使用施加约束：

- **Decode 模块**: 禁止出现 `_pc` 信号 -- 解码阶段不应依赖 PC 值，PC 应仅在取指阶段使用，这避免了 PC 信号的不必要前馈传播，有利于时序收敛。
- **Dispatch 模块**: 禁止出现 `_lsrc` 信号 -- 逻辑源寄存器索引应在 rename 阶段前解析完毕，不应泄漏到 dispatch 级，这确保了 rename 阶段对寄存器映射的完全控制。
- **MissEntry 模块**: 禁止出现 `refill_data_raw` -- 数据缓存缺失处理条目不应包含原始 refill 数据，这可能是面积和时序上的权衡考虑。

### 2.4 同步复位检测 (Async Reset Enforcement)

脚本检测所有以 `always @(posedge clock) begin` 开头的同步 always 块。通过跟踪 `begin`/`end` 的嵌套深度（`always_depth`），在同步块内部寻找 `if (reset) begin` 模式，若发现则报错：

```
should not use sync reset!!!
```

**设计意图**: 香山处理器强制使用**异步复位 (async reset)**。同步复位在综合时需要额外的复位信号扇出网络，可能影响时序；异步复位则直接利用寄存器的异步复位端口，在 FPGA 和 ASIC 实现中更为高效，同时符合后端团队对复位树(recovery/removal timing)的设计要求。

---

## 3. check-usage.sh: BoringUtils 禁用检测

文件路径：`.github/workflows/check-usage.sh`

这是一个仅 10 行的 shell 脚本，其功能极其直接：

```bash
grep -rn $1 $2/src/main/scala/xiangshan
if [[ $? == 0 ]]; then
    exit 1
fi
exit 0
```

在 `emu.yml` 工作流的 "Check top wiring" 步骤中被调用：

```bash
bash .github/workflows/check-usage.sh "BoringUtils" $GITHUB_WORKSPACE
```

**设计意图**: `BoringUtils` 是 Chisel 生态中一个用于跨模块信号穿透（cross-module wiring）的工具库，允许在两个无直接层级关系的模块之间直接建立连接（类似于 SystemVerilog 中的 `bind` 或 `ref`）。虽然便捷，但 `BoringUtils` 会破坏模块封装性，使信号路径在 RTL 层面不可追踪，给后续的 Lint 检查、形式验证和时序分析带来极大困扰。香山项目通过此脚本在 Scala 源码层面（`src/main/scala/xiangshan/`）严格禁止使用 `BoringUtils`，强制所有跨模块信号传递必须通过显式的 IO 端口和模块层级连接完成。

---

## 4. CODEOWNERS: 代码评审矩阵

文件路径：`.github/CODEOWNERS`

香山项目的 CODEOWNERS 定义了模块级别的强制评审责任矩阵，确保每次 PR 的相关改动都由对应的领域专家审核。该矩阵覆盖 50 个子路径，主要负责人用 `@` 标记：

### 4.1 前端 (Frontend) - 负责人: @rich-cake

| 路径 | 额外评审人 |
|---|---|
| `frontend/` (根) | @rich-cake |
| `frontend/bpu/` | @eastonman, @rich-cake |
| `frontend/ftq/` | @Yan-Muzi, @rich-cake |
| `frontend/ibuffer/` | @rich-cake |
| `frontend/icache/` | @ngc7331, @rich-cake |
| `frontend/ifu/` | @ngc7331, @my-mayfly, @rich-cake |
| `frontend/instruncache/` | @ngc7331, @rich-cake |

前端所有子模块均由 @rich-cake 负责全局审查，特定子领域有额外的专项评审人：@eastonman 负责分支预测单元(BPU)、@Yan-Muzi 负责取指目标队列(FTQ)、@ngc7331 负责指令缓存相关(icache/instruncache/ifu)、@my-mayfly 负责取指单元(ifu)。

### 4.2 后端 (Backend) - 负责人: @lewislzh

| 路径 | 额外评审人 |
|---|---|
| `backend/` (根) | @lewislzh |
| `backend/Region.scala` | @xiaofeibao-xjtu |
| `backend/CtrlBlock.scala` | @wissygh |
| `backend/ctrlblock/` | @wissygh |
| `backend/datapath/` | @xiaofeibao-xjtu |
| `backend/decode/` | @HeiHuDie |
| `backend/dispatch/` | @xiaofeibao-xjtu |
| `backend/exu/` | @sinceforYy |
| `backend/fu/` | @sinceforYy |
| `backend/fu/NewCSR/` | @huxuan0307 |
| `backend/issue/` | @xiaofeibao-xjtu |
| `backend/regcache/` | @xiaofeibao-xjtu |
| `backend/regfile/` | @xiaofeibao-xjtu |
| `backend/rename/` | @Tang-Haojin |
| `backend/rob/` | @NewPaulWalker, @xiaofeibao-xjtu |
| `backend/trace/` | @wissygh |

后端是香山代码量最大的子系统，@lewislzh 作为总负责人覆盖所有路径。@xiaofeibao-xjtu 是数据通路相关(datapath/dispatch/issue/regcache/regfile)的核心评审人，@wissygh 负责控制通路(CtrlBlock/ctrlblock/trace)，@sinceforYy 负责执行单元(exu/fu)，@HeiHuDie 负责解码，@huxuan0307 负责 CSR 新实现，@Tang-Haojin 负责重命名，@NewPaulWalker 负责 ROB。

### 4.3 缓存与存储系统 (Cache & Memory) - 负责人: @linjuanZ

| 路径 | 额外评审人 |
|---|---|
| `cache/` | @linjuanZ |
| `cache/dcache/` | @Maxpicca-Li |
| `cache/mmu/` | @good-circle, @cebarobot |
| `cache/wpu/` | @Maxpicca-Li |
| `mem/` | @linjuanZ |
| `mem/lsqueue/` | @good-circle |
| `mem/mdp/` | @weidingliu |
| `mem/pipeline/` | @good-circle |
| `mem/prefetch/` | @happy-lx, @Maxpicca-Li |
| `mem/sbuffer/` | @good-circle |
| `mem/vector/` | @weidingliu, @good-circle |
| `L2Top.scala` | @linjuanZ |
| `XSTile.scala` | @linjuanZ |
| `coupledL2/` | @linjuanZ |
| `huancun/` | @linjuanZ |

@linjuanZ 是存储子系统的总负责人，覆盖 L1/L2 缓存、TLB、存储队列和整个内存子系统。@good-circle 是缓存层次和 Load-Store 子系统的密集参与者，@Maxpicca-Li 负责 DCache 和预取策略，@weidingliu 负责内存依赖预测和向量存储。

### 4.4 其他模块

- `top/`: @Tang-Haojin 负责顶层模块
- `scripts/Makefile.pdb`, `scripts/pdb-run.py`: @yaozhicheng, @forever043, @SFangYy, @Tang-Haojin 负责 XSPdb 调试工具链
- `scripts/xspdb/`: @yaozhicheng, @SFangYy 负责

---

## 5. PR Auto-Labeling: 自动标签系统

### 5.1 触发机制 (pr-labeler.yml)

工作流 `.github/workflows/pr-labeler.yml` 使用 `pull_request_target` 事件触发（注意：这是 target 而非普通的 pull_request，意味着使用基础分支上的 labeler 配置来评估 PR），通过 `actions/labeler@v6` 自动为新 PR 添加标签。权限设定为 `contents: read` + `pull-requests: write`，仅需读取文件变更和写入标签的最小权限。

### 5.2 模块标签 (8个)

定义于 `.github/labeler.yml`，基于文件路径匹配自动生成：

| 标签 | 匹配路径 |
|---|---|
| `module: frontend` | `src/main/scala/xiangshan/frontend/**` |
| `module: backend` | `src/main/scala/xiangshan/backend/**`, `yunsuan` 子模块 |
| `module: memory` | `src/main/scala/xiangshan/cache/**`, `src/main/scala/xiangshan/mem/**`, `XSCache` 子模块 |
| `module: top` | `src/main/scala/xiangshan/*`, `src/main/scala/top/**`, `src/main/scala/system/**`, `src/main/resources/config/**` |
| `module: utility` | `src/main/scala/utils/**`, `macros/**`, `utility` 子模块 |
| `module: tool` | `src/main/resources/*`, `src/test/**`, `.github/**`, `difftest`, `ready-to-run`, `scripts/**`, 构建配置文件 (`.mill-version`, `.scalafmt.conf`, `Makefile`, `Dockerfile`, `build.mill`, `scalastyle-config.xml`, `scalastyle-test-config.xml`) |
| `module: other` | `src/main/scala/device/**`, `ChiselAIA`, `ChiselIOPMP`, `rocket-chip` |
| `module: documentation` | `images/**`, `**.md`, `LICENSE` |

### 5.3 主题标签 (6个) + 子模块标签

| 标签 | 匹配条件 |
|---|---|
| `note: submodule bump` | 更改了 `.gitmodules` 或任一子模块路径 (`ChiselAIA`, `ChiselIOPMP`, `XSCache`, `difftest`, `ready-to-run`, `rocket-chip`, `utility`, `yunsuan`) |
| `topic: functionality` | head-branch 匹配 `^feat` 或 `^fix` |
| `topic: performance` | head-branch 匹配 `^perf` |
| `topic: code quality` | head-branch 匹配 `^refactor` |
| `topic: power` | head-branch 匹配 `^power` |
| `topic: area` | head-branch 匹配 `^area` |
| `topic: timing` | head-branch 匹配 `^timing` |

主题标签采用 Git 分支命名约定——当 PR 源分支以 `feat/`、`fix/`、`perf/` 等前缀开头时，自动打上对应的功能分类标签。这种设计将分支命名规范与 CI/CD 自动化关联，无需人工打标签即可实现 PR 的自动分类，便于后续的变更日志生成和版本管理。

---

## 6. Log Rotation: 日志轮转归档

文件路径：`.github/workflows/logrotate.yml`

### 6.1 架构设计

注释明确指出：日志轮转采用 GitHub Actions 而非单机 cron job，目的是避免单点故障（single-point failure）。工作流通过 `schedule` 定时器每周日 UTC 14:00（北京时间 22:00）自动触发，同时支持 `workflow_dispatch` 手动触发（附带 `dry_run` 参数用于预演）。

使用 `concurrency` 机制（`group: logrotate`, `cancel-in-progress: true`）确保同一时间只有一个轮转任务在运行，防止资源争抢。权限设为 `permissions: {}`（空），无需任何 GitHub token 权限——所有操作都在本地文件系统上执行。

### 6.2 矩阵策略

采用两维矩阵策略，`max-parallel: 1` 确保串行执行：

| 维度 | 值 |
|---|---|
| **cluster** (runner 标签) | `open`, `node` |
| **target** (目录路径) | `/nfs/home/ci-runner/emu-basics` (EMU 基础测试), `/nfs/home/ci-runner/emu-performance` (EMU 性能测试), `/nfs/home/ci-runner/perf-report-custom` (性能回归), `/nfs/home/ci-runner/xs-wave` (EMU 波形), `/nfs/home/ci-runner/xs-perf` (EMU 性能) |

这意味着总共 10 个任务（2 cluster x 5 target），每个任务对应一个 NFS 挂载点上的 CI 结果目录。

### 6.3 两级生命周期管理

**第一级：30 天归档 (Archive)**

对超过 30 天 (`-mtime +30`) 未修改的目录执行归档：
- 空目录直接删除
- 非空目录通过 `tar -I "zstd -19 -T0" -cf` 压缩为 `.tar.zst` 文件
  - `-19`: zstd 最高压缩级别，最大化空间节省
  - `-T0`: 使用所有可用 CPU 核心并行压缩
- 使用 `.tmp` 中间文件原子性地替换原归档，避免中断导致损坏
- 归档完成后删除原始目录

**第二级：90 天清理 (Remove)**

对超过 60 天 (`-mtime +60`) 的 `.tar.zst` 文件直接删除。注释解释：这些文件在 30 天时被归档，因此检查 60 天等同于距原始结果 90 天（30 + 60 = 90）。此设计确保即使某些文件在归档前被修改过，也不会在 90 天前被过早删除。

### 6.4 Dry Run 支持

通过 `workflow_dispatch` 手动触发时可设置 `dry_run: true`，所有归档和删除操作仅打印 `[DRY_RUN]` 前缀日志而不实际执行，便于运维人员预览变更。

---

## 7. Change-Aware Filtering: 变更感知过滤

文件路径：`.github/filters.yaml`

该文件定义了一个名为 `core` 的过滤规则集，采用排除模式（`!` 前缀），用于标识哪些文件变更**不应被视为核心代码变更**：

```yaml
core:
  - "!.github/ISSUE_TEMPLATE/**"
  - "!.github/CODEOWNERS"
  - "!.github/filters.yaml"
  - "!.github/labeler.yml"
  - "!.github/workflows/logrotate.yml"
  - "!.github/workflows/nightly.yml"
  - "!.github/workflows/perf-template.yml"
  - "!.github/workflows/perf-v2.yml"
  - "!.github/workflows/perf-v3.yml"
  - "!.github/workflows/perf-trigger.yml"
  - "!.github/workflows/pr-labeler.yml"
  - "!.github/workflows/nightly_perf_diff.py"
  - "!.gitignore"
  - "!**/*.md"
  - "!LICENSE"
  - "!images/**"
```

被排除的文件类别包括：
1. **GitHub 配置**: Issue 模板、CODEOWNERS、labeler 配置、filters 配置
2. **CI/CD 辅助 workflow**: logrotate、nightly 系列、perf-template/v2/v3/trigger、pr-labeler 以及 nightly_perf_diff.py（注意：release.yml 和 emu.yml 不在此列，因为它们影响核心构建）
3. **文档与资源**: 所有 `.md` 文件、LICENSE、images 目录
4. **Git 配置**: `.gitignore`

**设计意图**: 此过滤器被下游的 perf-v2/perf-v3 等性能回归测试工作流使用，用以判断一次 push 是否包含"核心"代码变更。如果所有变更文件都匹配排除规则，则可能跳过代价高昂的性能回归测试，从而节省 CI 资源。这种变更感知的条件执行策略是大型硬件项目 CI 优化的关键手段。

---

## 8. 源文件索引

以下列出本报告分析的所有源文件及其路径：

| 文件 | 路径 | 用途 |
|---|---|---|
| release.yml | `.github/workflows/release.yml` | 发布管线主工作流：Docker 镜像、XSPdb、Verilog 产出物 |
| release-trigger.yml | `.github/workflows/release-trigger.yml` | 发布管线手动触发入口 |
| logrotate.yml | `.github/workflows/logrotate.yml` | CI 日志轮转与归档（30 天归档、90 天清理） |
| pr-labeler.yml | `.github/workflows/pr-labeler.yml` | PR 自动标签触发器 |
| check_verilog.py | `.github/workflows/check_verilog.py` | Verilog RTL 质量检查脚本（6 类违规检测） |
| check-usage.sh | `.github/workflows/check-usage.sh` | BoringUtils 使用禁令检测 |
| CODEOWNERS | `.github/CODEOWNERS` | 代码评审责任矩阵（50+ 路径条目） |
| labeler.yml | `.github/labeler.yml` | PR 自动标签规则（8 模块 + 6 主题） |
| filters.yaml | `.github/filters.yaml` | 变更感知过滤器（排除非核心文件变更） |
| Dockerfile | `Dockerfile` | XSDev Docker 镜像定义 |
| emu.yml (片段) | `.github/workflows/emu.yml` (L294-324) | check_verilog.py 和 check-usage.sh 的调用上下文 |

---

## 9. 总结与评价

香山项目的 Release & Quality Gates 体系体现了硬件开源项目在持续交付方面的成熟度：

1. **多变体发布**: 同一次提交生成 CHI Issue B、Issue E.b、低功耗三个 Verilog 变体以及测试 jar，覆盖了不同芯片配置场景的需求。
2. **RTL 结构守护**: `check_verilog.py` 不做简单的语法检查，而是深入到模块级语义约束（Decode 不应有 PC、Dispatch 不应有 lsrc）和设计规范约束（禁止同步复位），这些规则直接编码了后端团队的物理实现经验。
3. **封装性强制**: 通过 `check-usage.sh` 禁止 `BoringUtils`，从源码层面维护模块化设计的完整性。
4. **变更感知优化**: `filters.yaml` 将非核心文件变更排除在性能回归之外，体现了对 CI 资源的精细化管理。
5. **日志生命周期**: 两级归档策略（30 天压缩 + 90 天删除）在存储成本和回溯能力之间取得平衡，zstd 高压缩比进一步降低了 NFS 存储压力。
6. **评审责任矩阵**: CODEOWNERS 覆盖 50+ 路径，确保每个子模块都有明确的领域专家负责代码评审，避免了"无人审核"的盲区。

这套体系的薄弱环节主要在：check_verilog.py 的规则维护仍依赖人工添加新模式（硬编码模块名和信号名），缺乏可扩展的规则 DSL；filters.yaml 的排除列表需要手动与新增的 workflow 同步更新，存在遗漏风险。整体而言，香山的 Release & Quality Gates 在开源硬件项目中属于领先水平，为持续迭代的 RTL 开发提供了可靠的交付质量保障。
