# R29A - XiangShan.py 与 CI 脚本深度分析报告

> 源文件路径：
> - `/home/agi/workspace/gitwork/XiangShan/scripts/xiangshan.py`
> - `/home/agi/workspace/gitwork/XiangShan/scripts/bug-report.sh`
> - `/home/agi/workspace/gitwork/XiangShan/scripts/generate_all.sh`
> - `/home/agi/workspace/gitwork/XiangShan/debug/local_ci.py`
> - `/home/agi/workspace/gitwork/XiangShan/debug/cputest.sh`
> - `/home/agi/workspace/gitwork/XiangShan/scripts/Makefile.docker`
> - `/home/agi/workspace/gitwork/XiangShan/scripts/requirements.txt`

---

## 1. XSArgs 类 - 全配置参数体系

`XSArgs` 类（位于 `scripts/xiangshan.py` 第 47-216 行）是 XiangShan 测试框架的核心配置容器，负责管理所有路径环境变量、Chisel 编译参数、Makefile 构建参数和 EMU 运行时参数。其设计采用"Python 命令行参数 - 内部属性 - 环境变量/Makefile 变量"的三层转换模式。

### 1.1 仓库路径配置

XSArgs 在类级别定义了默认仓库路径，这些路径相对于脚本自身位置推导：

| 参数 | 环境变量 | 默认值 | 说明 |
|------|----------|--------|------|
| `noop_home` | `NOOP_HOME` | `scripts/..`（即 XiangShan 根目录） | XiangShan 主仓库 |
| `nemu_home` | `NEMU_HOME` | `noop_home/../NEMU` | NEMU 仿真器（用于 difftest） |
| `am_home` | `AM_HOME` | `noop_home/../nexus-am` | Nexus-AM 测试框架 |
| `dramsim3_home` | `DRAMSIM3_HOME` | `noop_home/../DRAMsim3` | DRAMsim3 内存仿真器 |
| `rvtest_home` | `RVTEST_HOME` | `noop_home/../riscv-tests` | RISC-V 标准测试集 |
| `wave_home` | `WAVE_HOME` | `noop_home/build` | 波形文件输出目录 |

路径解析采用 `__extract_path` 方法（第 190-196 行），其优先级为：命令行参数 > 环境变量 > 默认值，最终通过 `os.path.realpath()` 规范化。每个路径都有对应的 setter 方法（`set_noop_home`、`set_nemu_home` 等），允许在运行时动态调整。

### 1.2 Chisel 编译参数

Chisel 参数通过 `get_chisel_args()` 方法（第 126-133 行）暴露给 Makefile：

- **`enable_log`**（`--enable-log`）：启用处理器运行日志输出，用于调试指令执行流
- **`num_cores`**（`--num-cores`）：配置多核数量，影响 `NUM_CORES` Makefile 变量
- **`xprop`**（`--xprop`）：启用 VCS 的 X-propagation 分析，用于检测未初始化信号传播
- **`simfrontend`**（`--simfrontend`）：启用仿真前端（`ENABLE_SIMFRONTEND`），用于 simfrontend 模式

### 1.3 Makefile 构建参数

`get_makefile_args()` 方法（第 135-160 行）将 Python 属性转换为 `KEY=VALUE` 格式的 Makefile 变量。所有值经过 `shlex.quote()` shell 转义处理以防止注入。完整参数映射如下：

| Python 属性 | Makefile 变量 | 用途 |
|-------------|---------------|------|
| `threads` | `EMU_THREADS` | EMU 运行时的并行线程数 |
| `with_dramsim3` | `WITH_DRAMSIM3` | 集成 DRAMsim3 内存仿真 |
| `with_constantin` | `WITH_CONSTANTIN` | 启用 Constantin 运行时参数注入框架 |
| `is_release` | `RELEASE` | Release 模式编译（优化、无 debug） |
| `is_spike` | `REF` | 参考模型切换为 Spike（默认为 NEMU） |
| `trace` | `EMU_TRACE` | 启用 VCD 波形追踪 |
| `trace_fst` | `EMU_TRACE=fst` | 启用 FST 格式波形（比 VCD 更紧凑） |
| `config` | `CONFIG` | Chisel 配置类名（如 `DefaultConfig`） |
| `yaml_config` | `YAML_CONFIG` | YAML 格式的配置覆盖文件 |
| `emu_optimize` | `EMU_OPTIMIZE` | Verilator 优化等级字母参数 |
| `with_chiseldb` | `WITH_CHISELDB` | 启用 ChiselDB 数据库转储 |
| `pgo` | `PGO_WORKLOAD` | PGO 编译的工作负载路径 |
| `pgo_max_cycle` | `PGO_MAX_CYCLE` | PGO 训练最大周期数（默认 400000） |
| `pgo_emu_args` | `PGO_EMU_ARGS` | PGO 训练阶段的 EMU 参数（默认 `--no-diff`） |
| `llvm_profdata` | `LLVM_PROFDATA` | LLVM profdata 命令路径（用于 PGO） |
| `issue` | `ISSUE` | CHI Issue 编号（关联 GitHub issue） |
| `simfrontend` | `ENABLE_SIMFRONTEND` | 启用仿真前端模块 |
| `emu_trace_all` | `EMU_TRACE_ALL` | 追踪所有信号（而非仅指令） |

### 1.4 EMU 运行时参数

`get_emu_args()` 方法（第 162-170 行）生成传给 EMU 可执行文件的命令行参数：

- **`max_instr`**（`--max-instr`）：单次仿真最大指令数
- **`diff`**（`--diff`）：difftest 动态库路径（默认 `./ready-to-run/riscv64-nemu-interpreter-so`）
- **`seed`**（`--seed`）：随机种子（未指定时从 0-9999 随机生成）
- **`ram_size`**（`--ram-size`）：仿真内存大小（默认 8GB）

### 1.5 其他运行时控制

| 属性 | 说明 |
|------|------|
| `fork` | LightSSS fork 机制开关（默认启用，`--disable-fork` 关闭） |
| `disable_diff` | 完全禁用 difftest（`--no-diff`） |
| `dump_db` | 启用 ChiselDB 数据库导出 |
| `gcpt_restore_bin` | GCPT checkpoint 恢复二进制文件路径 |
| `instr_trace` | simfrontend 指令追踪文件 |
| `flash` | Flash 镜像路径（用于 `copy_and_run`） |
| `numa` | NUMA 感知调度开关 |
| `pgo` | PGO 工作负载路径（`null` 禁用） |
| `emulator` | 仿真后端选择：`verilator` 或 `gsim`（默认 `verilator`） |

### 1.6 环境变量汇总

`get_env_variables()` 方法（第 115-124 行）返回完整的环境变量字典，额外包含 `MODULEPATH` 指向系统模块路径 `/usr/share/Modules/modulefiles:/etc/modulefiles`，用于加载 Synopsys VCS 等 EDA 工具的 module。

---

## 2. XiangShan 类 - 主测试编排

`XiangShan` 类（第 218-670 行）是测试框架的执行引擎，封装了从 Verilog 生成到 EMU 构建再到测试执行的完整流水线。

### 2.1 命令执行基础设施

`__exec_cmd()` 方法（第 332-346 行）是所有命令执行的底层入口，具有以下关键特性：

- **进程隔离**：使用 `os.setsid` 创建新 session，确保子进程组可以被整体终止
- **环境注入**：通过 `env.update()` 将 XSArgs 管理的环境变量注入子进程环境
- **超时控制**：通过 `proc.wait(self.timeout)` 实现超时检测
- **优雅终止**：超时或键盘中断时，使用 `os.killpg(os.getpgid(proc.pid), signal.SIGINT)` 向整个进程组发送 SIGINT，而非 SIGTERM，以允许子进程执行清理操作

### 2.2 构建流水线

#### generate_verilog()
调用 `make -C $NOOP_HOME verilog`，将 Chisel 代码编译为 Verilog RTL。传递 SIM_ARGS（Chisel 参数）和 Makefile 变量。

#### generate_sim_verilog()
调用 `make -C $NOOP_HOME sim-verilog`，生成用于 VCS 仿真器的 Verilog（与 Verilator 用的 Verilog 有差异，主要在于 VCS 特定的仿真支持）。

#### build_emu()
根据 `emulator` 参数选择不同的构建路径：

- **Verilator 后端**：`make -C $NOOP_HOME emu -j{threads}`
- **gsim 后端**：`make -C $NOOP_HOME gsim GSIM=1 -j{threads}`
- 其他值抛出 `ValueError`

使用 `make_threads`（默认 200）控制 make 并行度，体现了大型 Chisel 项目编译的计算密集特性。

#### build_simv()
构建 VCS simv，需要加载 Synopsys 工具链模块：`license`、`synopsys/vcs/Q-2020.03-SP2`、`synopsys/verdi/S-2021.09-SP1`，并设置 `VERDI_HOME`。添加 `CONSIDER_FSDB=1` 以支持 FSDB 波形格式。

### 2.3 运行方法

#### run_emu()（第 276-297 行）
EMU 运行是测试执行的核心，组装命令的流程如下：

1. 设置栈大小 `ulimit -s 32*1024`（32MB）
2. 如果启用 NUMA，调用 `get_free_cores()` 获取空闲核心并生成 `numactl` 命令
3. 组装 fork 参数（`--enable-fork`，基于 LightSSS）
4. 组装 diff 参数（`--no-diff` 或默认 difftest）
5. 组装 ChiselDB 转储参数
6. 组装 GCPT 恢复参数
7. 组装指令追踪参数
8. 组装 Flash 参数
9. 执行 `$NOOP_HOME/build/emu -i {workload} {所有参数}`

#### run_simv()（第 299-309 行）
VCS simv 运行后会解析 `simv.log`，检查是否出现 `Offending`（断言失败）或缺少 `HIT GOOD TRAP`（测试未通过）来判定结果。

### 2.4 run() 方法 - 动作调度

`run()` 方法（第 311-330 行）根据命令行参数决定执行哪些动作，按顺序为：

1. `--ci` / `--ci-vcs` 直接进入 CI 测试模式
2. `--generate` 调用 generate_verilog()
3. `--vcs-gen` 调用 generate_sim_verilog()
4. `--build` 调用 build_emu()
5. `--vcs-build` 调用 build_simv()
6. `--workload` 调用 run_emu(workload)
7. `--clean` 调用 make_clean()

任意步骤返回非零值即终止流水线，实现 fail-fast 语义。

---

## 3. 测试框架 - 多层次测试体系

XiangShan 的 CI 测试体系通过 `run_ci()` 和 `run_ci_vcs()` 方法（第 609-670 行）组织，测试目标通过字典映射到对应的 workload 获取函数。

### 3.1 cputest

`__get_ci_cputest()`（第 348-354 行）从 NFS 共享路径 `/nfs/home/share/ci-workloads/nexus-am-workloads/tests/cputest` 加载所有 `.bin` 文件。这些是 Nexus-AM 框架下的基础 CPU 功能测试，覆盖基本的算术、逻辑、控制流等指令。

调试脚本 `debug/cputest.sh` 提供了手动运行 cputest 的方式：遍历 `$AM_HOME/tests/cputest/tests` 下的所有 `.c` 文件，逐个编译并运行，通过 `grep` 检查输出中是否包含 `HIT GOOD TRAP` 和 `IPC`。

### 3.2 riscv-tests

`__get_ci_rvtest()`（第 356-363 行）从 `$RVTEST_HOME/isa/build` 加载标准 RISC-V ISA 测试，限定测试子集为：

- `rv64ui` - RV64 整数未对齐指令
- `rv64um` - RV64 乘除法
- `rv64ua` - RV64 原子操作
- `rv64uf` - RV64 单精度浮点
- `rv64ud` - RV64 双精度浮点
- `rv64mi` - RV64 机器模式

这些测试验证处理器对 RISC-V 基础 ISA 的正确实现。

### 3.3 misc-tests

`__get_ci_misc()`（第 365-390 行）是一组杂项功能测试，涵盖多个扩展和特殊功能：

| 测试模块 | 路径 | 验证内容 |
|----------|------|----------|
| `bitmanip` | `bitMisc.bin` | 位操作扩展（Zba/Zbb/Zbc/Zbs） |
| `crypto` | `crypto-riscv64-noop.bin` | 加密扩展（Zbkb/Zbkc/Zbkx） |
| `external_intr` | `external_intr-riscv64-xs.bin` | 外部中断处理 |
| `aliastest` | `aliastest-riscv64-xs.bin` | 寄存器别名测试 |
| `Svinval` | `rv64mi-p-svinval.bin` | Svinval 扩展（非法地址无效化） |
| `pmp` | `pmp.riscv.bin` / `pmp_test-riscv64-xs.bin` | 物理内存保护 |
| `sv39_hp_atom_test` | `sv39_hp_atom_test-riscv64-xs.bin` | Sv39 页表 + 原子操作 |
| `asid` | `asid.bin` | 地址空间标识符 |
| `isa_misc` | `xret_clear_mprv.bin` / `satp_ppn.bin` | ISA 杂项（xret MPRV 清除、satp PPN） |
| `cache-management` | `softprefetchtest-riscv64-xs.bin` | 软件预取 |
| `smstateen` | `rvh_test.bin` | Smstateen 扩展 |
| `zacas` | `zacas-riscv64-xs.bin` | Zacas 原子比较交换 |
| `Svpbmt` | `rvh_test.bin` | Svpbmt 页表属性 |
| `Svnapot` | `svnapot-test.bin` | Svnapot 自然对齐页 |
| `Zawrs` | `Zawrs-zawrs.bin` | Zawrs 等待保留集 |

### 3.4 rvh-tests (H-Extension)

`__get_ci_rvhtest()`（第 392-402 行）测试 RISC-V Hypervisor 扩展（H-extension），路径为 `/nfs/home/share/ci-workloads/H-extension-tests`：

- `rvh_test.bin` - 标准 H 扩展测试
- `xvisor_wboxtest/checkpoint.gz` - XVisor 虚拟机监控器的 write-box 测试
- `pointer-masking-test/M_HS_test` - M/HS 模式指针掩码
- `pointer-mapping-test/U_test/hint_UMode_hupmm2` - U 模式 HUPMM2 指针掩码
- `pointer-mapping-test/U_test/vu_senvcfgpmm2` - VU 模式 senvcfgpmm2 指针掩码

### 3.5 rvv-test (V-Extension)

`__get_ci_rvvtest()`（第 413-434 行）测试 RISC-V Vector 扩展，包含 16 个测试用例：

- **加载指令**：`vluxei32.v`、`vsuxei32.v`、`vle16.v`、`vle32.v`、`vlse32.v`
- **分段加载/存储**：`vlsseg4e32.v`、`vlseg4e32.v`
- **配置指令**：`vsetvl`、`vsetvli`、`vsetivli`
- **存储指令**：`vse16.v`、`vsse16.v`
- **浮点向量**：`vfsgnj.vv`、`vfadd.vf`、`vfsub.vf`
- **滑动操作**：`vslide1down.vx`

注意：注释中标明 "Temporarily disabled in CI due to the ongoing vector refactor"，说明 V 扩展测试在向量重构期间被暂时禁用。

### 3.6 F16 测试

`__get_ci_F16test()`（第 436-523 行）测试半精度浮点（Zfhmin/Zfh）扩展，包含 12 个标量测试和大量被注释掉的向量浮点测试（`rv64uzvfh` 系列），同样因向量重构而禁用。

### 3.7 SPEC Checkpoint 测试

`__get_ci_workloads()`（第 560-597 行）支持命名工作负载和随机 SPEC checkpoint：

**命名工作负载**包括 Linux 内核启动（`linux-hello`、`linux-hello-opensbi` 及其 SMP 变体）和 SPEC CPU 基准测试（`povray`、`mcf`、`xalancbmk`、`gcc`、`namd`、`milc`、`lbm`、`gromacs`、`wrf`、`astar`、`hmmer-Vector`）。

**随机 checkpoint**（`name == "random"`）从 10 个不同的 SPEC06/SPEC17 checkpoint 目录中随机选择一个 `.zstd` 或 `.gz` 文件，覆盖不同的编译选项（O2/O3）和优化配置。

### 3.8 其他测试

- **mc-tests**：多核测试（`ldvio-riscv64-xs.bin`）
- **nodiff-tests**：无 difftest 的缓存操作测试（`cacheoptest`）
- **zcb-test**：Zcb 压缩指令扩展
- **iopmp-test**：I/O PMP 测试
- **microbench/coremark**：通过 `__am_apps_path()` 从 Nexus-AM apps 加载

### 3.9 测试失败处理

当某个测试失败（`ret != 0`）且 wave_home 被修改时，`run_ci()` 会将以下文件复制到 wave_home：
- `*.vcd` / `*.fst` 波形文件
- `emu` / `simv` 可执行文件
- `SimTop.v` RTL 源码
- `*.db` ChiselDB 数据库

---

## 4. NUMA 感知调度与进程管理

`get_free_cores()` 函数（第 672-752 行）实现了 NUMA 感知的核心分配算法，确保多核 EMU 运行时不会与其他进程产生资源争用。

### 4.1 核心检测算法

算法分三个层次进行核心可用性评估：

**第一层 - NUMA 节点检测**（`numa_count()`）：扫描 `/sys/devices/system/node/` 目录计算 NUMA 节点数。

**第二层 - CPU 使用率检查**（`get_unset_cores()`）：
- 使用 `psutil.cpu_percent(interval=0.5, percpu=True)` 获取每核使用率
- 遍历所有进程的 `cpu_affinity`，统计每个核心上的进程亲和度计数
- 只考虑状态为 running、disk-sleep、waking、waiting 的进程
- 返回没有任何活跃进程绑定的核心列表

**第三层 - 窗口检测**（`detect()` 函数）：
- 将所有物理核心划分为大小为 `n`（EMU 线程数）的窗口
- 滑动窗口随机遍历（`random.sample` 打乱顺序）
- 每个窗口需同时满足三个条件：
  1. **平均使用率条件**：窗口内核心平均使用率 < 30%（`percpu_use_thres`）
  2. **高负载核心条件**：窗口内使用率 > 80% 的核心数 < 1
  3. **亲和度条件**：窗口内所有核心均在"未占用"列表中

### 4.2 双重验证机制

初次检测成功后，算法会 `time.sleep(random.uniform(1, 30))` 随机等待 1-30 秒后再次验证，防止并发分配冲突。如果验证失败则继续搜索下一个窗口。

### 4.3 无限重试

如果所有窗口都不满足条件，函数会等待 60 秒后重新扫描，形成无限重试循环，直到找到可用核心。这种设计适合 CI 服务器上的长时间运行场景。

### 4.4 NUMA 节点映射

返回值包含 NUMA 节点编号，计算公式为 `(start_core % num_core) // (num_core // numa_node)`，确保 EMU 运行在与分配核心对应的本地内存节点上，减少跨节点内存访问延迟。

---

## 5. Verilator/GSIM 双后端支持

XiangShan 支持两种仿真后端，通过 `--emulator` 参数选择：

### 5.1 Verilator 后端（默认）

Verilator 是开源的 Verilog/SystemVerilog 到 C++ 编译器，XiangShan 长期使用的主要仿真后端。

构建命令：`make -C $NOOP_HOME emu -j{threads}`
- 支持 VCD/FST 波形追踪（`--trace` / `--trace-fst`）
- 支持 Verilator 优化等级（`--emu-optimize`）
- 支持 `EMU_TRACE_ALL` 追踪所有信号

### 5.2 GSIM 后端

GSIM 是较新的仿真后端，构建命令为 `make -C $NOOP_HOME gsim GSIM=1`。GSIM=1 标志作为 Makefile 变量传递以启用 GSIM 特定的构建路径。GSIM 可能提供比 Verilator 更快的仿真速度或更好的调试支持。

### 5.3 VCS 后端（商业工具）

除上述开源后端外，还支持 Synopsys VCS 商业仿真器：

- `generate_sim_verilog()` 生成 VCS 兼容的 Verilog
- `build_simv()` 加载 VCS/Verdi 模块并构建 simv
- `run_simv()` 执行仿真并检查 FSDB 波形和断言结果
- 运行时参数包括 `+dump-wave=fsdb` 和断言限制 `-assert finish_maxfail=30 -assert global_finish_maxfail=10000`

### 5.4 run_ci vs run_ci_vcs

`run_ci()` 和 `run_ci_vcs()`（第 609-670 行）共享相同的测试用例字典和 workload 获取函数，区别仅在于调用 `run_emu()` 还是 `run_simv()`，以及失败时复制的波形文件格式（VCD/FST vs FSDB）。

---

## 6. PGO (Profile-Guided Optimization) 编译

PGO 是一种编译优化技术，通过实际运行数据指导编译器优化决策。XiangShan 在 EMU 构建中集成了完整的 PGO 流程。

### 6.1 PGO 参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `--pgo` | None | PGO 训练工作负载路径（`null` 禁用 PGO） |
| `--pgo-max-cycle` | 400000 | PGO 训练最大仿真周期数 |
| `--pgo-emu-args` | `--no-diff` | PGO 训练阶段 EMU 参数 |
| `--llvm-profdata` | None | LLVM profdata 命令路径 |

### 6.2 PGO 工作流程

1. 使用指定工作负载（`PGO_WORKLOAD`）运行 EMU，最多 `PGO_MAX_CYCLE` 个周期
2. EMU 执行过程中收集性能 profile 数据
3. 使用 `llvm-profdata` 工具处理 profile 数据
4. 将处理后的 profile 数据传递给编译器用于优化重编译

### 6.3 Makefile 集成

PGO 参数通过 Makefile 变量传递：
- `PGO_WORKLOAD`：训练工作负载路径
- `PGO_MAX_CYCLE`：最大周期数
- `PGO_EMU_ARGS`：训练参数
- `LLVM_PROFDATA`：profdata 工具路径

这些变量由 `get_makefile_args()` 方法统一管理，非 None 值才会传递给 Make。

---

## 7. Docker 集成 (Makefile.docker)

`scripts/Makefile.docker` 提供了完整的 Docker 容器化开发环境，使开发者无需手动安装复杂的工具链依赖。

### 7.1 镜像管理

**镜像命名规则**：`ghcr.io/openxiangshan/xsdev:{branch}`，分支名中的特殊字符被替换为连字符。使用 `git rev-parse --abbrev-ref HEAD` 动态获取当前分支。

**镜像构建**（`make image`）：
- 基于同目录下的 Dockerfile（`scripts/Dockerfile`）
- 支持 HTTP 代理配置：`make image HTTP_PROXY="http://proxy:port"`
- 代理配置写入 `.mill-jvm-opts` 文件供 JVM（Mill 构建工具）使用

**镜像拉取**（`make pull-image`）：从 GHCR 拉取预构建镜像。

### 7.2 容器运行机制

`DOCKER_RUN` 变量定义了 `docker run` 命令模板，关键配置：

```docker
docker run --init --rm -i [-t] \
  -e IN_XSDEV_DOCKER=y \
  -e NOOP_HOME=/work \
  -v .:/work:ro \                    # 源码只读挂载
  -v ./.docker-mill-out:/work/out:rw \ # Mill 输出可写
  -v $(BUILD_DIR):/work/$(BUILD_DIR):rw \ # 构建目录可写
  xsdev_image command
```

- **`--init`**：使用 tini 作为 PID 1，正确处理信号传播
- **`-i -t`**：交互模式（检测到 TTY 时附加 `-t`）
- **源码只读挂载**：`:/work:ro` 确保容器内不修改源码
- **输出目录可写**：Mill 编译输出和构建目录可写

### 7.3 环境切换机制

`__switch_docker_env` 目标实现了"透明 Docker 化"：

- 检测 `IN_XSDEV_DOCKER` 环境变量判断是否已在容器内
- 如果不在容器内，自动启动容器并将当前 make target 传递给容器内执行
- 如果已在容器内，直接执行（避免递归）

`docker-deps` 函数（第 97-98 行）作为依赖声明工具，自动将目标包装为 Docker 容器内执行。开发者在使用时只需在 Makefile 中添加 `$(call docker-deps, target)` 即可。

### 7.4 交互式 Shell

`make sh` 启动一个交互式容器 shell，进入 `/work` 目录（即源码根目录），开发者可在完整工具链环境中工作。

---

## 8. Bug 报告生成 (bug-report.sh)

`scripts/bug-report.sh` 是一个交互式 bug 报告收集工具，自动生成包含完整环境信息的压缩包。

### 8.1 报告结构

报告生成在 `${XS_ROOT}/bug-report/` 目录下，包含以下文件：

**date 文件**：生成时间戳。

**hardware 文件**：
- CPU 型号（`lscpu | grep 'Model name'`）
- 内存信息（`free -h`）
- 磁盘空间（`df -h`）

**sw 文件**：
- 操作系统信息
- 内核版本
- Python、GCC、Clang、glibc、Java、Mill 版本

**repo 文件**：
- 最新 commit（`git log -1 --oneline --no-abbrev-commit`）
- 工作区状态（`git status --short`）
- 子模块状态
- 未提交的 diff

### 8.2 交互式信息收集

`runtime()` 函数交互式收集用户信息：
- 编译命令（如 `make CONFIG=MinimalConfig -j8`）
- 运行命令（如 `./build/emu -i test.bin --diff ref.so`）
- 允许用户将日志和测试用例复制到报告目录

### 8.3 双语支持

脚本检测 `LANG` 环境变量，如果为 `zh_CN.UTF-8` 则显示中文提示，否则显示英文提示。

### 8.4 基本模式

`--basic` 参数仅收集硬件、软件和仓库信息，跳过交互式的编译/运行命令收集，适用于自动化环境。

---

## 9. Local CI 脚本 (local_ci.py)

`debug/local_ci.py` 是一个本地 CI 仿真工具，允许开发者在本地重现 GitHub Actions CI 流程。

### 9.1 核心设计理念

该脚本读取 `.github/workflows/emu.yml`（GitHub Actions 工作流定义文件），解析其中的 job 定义，在本地重新执行相同的测试步骤。这是一种"CI 即本地可复现"的设计哲学。

### 9.2 YAML 解析与变量替换

`parse_yaml()` 使用 `yaml.CLoader`（C 语言实现，性能更高）解析工作流文件。

`run_test()` 函数执行以下变量替换（第 67-74 行）：
- `--numa` 根据本地 NUMA 参数决定是否保留
- `$GITHUB_WORKSPACE` 替换为本地 workspace 路径
- `$HEAD_SHA` 替换为本地 head_sha（默认为当天日期 `YYYYMMDD`）
- `$PERF_HOME`、`$WAVE_HOME`、`$AM_HOME` 替换为本地路径

### 9.3 运行模式

**执行模式**（`--run`）：
- 创建 `wave_home` 和 `perf_home` 目录
- 拼接环境变量前缀后逐条执行命令
- 使用 `os.system()` 执行

**生成模式**（默认）：
- 将测试命令写入独立的 `.sh` 文件
- 文件名由测试名称转换而来（去除特殊字符，空格转下划线）
- 输出到 `--sh-path` 指定的目录（默认 `{workspace}/ci-sh/`）

### 9.4 测试选择

- `--show-test`：仅打印所有测试名称
- `--pick-test MC`：仅运行名称包含 "MC" 的测试
- 无过滤参数时运行所有测试

### 9.5 环境变量

```
NEMU_HOME={nemu_home}  NOOP_HOME={workspace}  WAVE_HOME={wave_home}  PERF_HOME={perf_home}  AM_HOME={am_home}
```

注意 `head_sha` 默认使用当天日期（`date.today().strftime("%Y%m%d")`），wave 和 perf 目录按日期组织。

---

## 10. 源文件位置汇总

### 核心 Python 脚本

| 文件 | 行数 | 职责 |
|------|------|------|
| `/home/agi/workspace/gitwork/XiangShan/scripts/xiangshan.py` | 818 行 | 主编排脚本，包含 XSArgs、XiangShan 类和 NUMA 调度 |
| `/home/agi/workspace/gitwork/XiangShan/debug/local_ci.py` | 180 行 | 本地 CI 仿真，解析 GitHub Actions YAML |
| `/home/agi/workspace/gitwork/XiangShan/scripts/requirements.txt` | 4 行 | Python 依赖：matplotlib, numpy, pandas, psutil |

### Shell 脚本

| 文件 | 职责 |
|------|------|
| `/home/agi/workspace/gitwork/XiangShan/scripts/bug-report.sh` | 交互式 bug 报告收集 |
| `/home/agi/workspace/gitwork/XiangShan/scripts/generate_all.sh` | 批量生成各模块的 BOSC release（Frontend, Backend, MemBlock, L2Top, XSTile, XSTop） |
| `/home/agi/workspace/gitwork/XiangShan/debug/cputest.sh` | 手动运行 cputest 并检查 HIT GOOD TRAP |

### 构建系统

| 文件 | 职责 |
|------|------|
| `/home/agi/workspace/gitwork/XiangShan/scripts/Makefile.docker` | Docker 容器化开发环境构建和运行 |

### generate_all.sh 详解

`scripts/generate_all.sh` 遍历 6 个核心模块（Frontend、Backend、MemBlock、L2Top、XSTile、XSTop），使用 `scripts/parser.py` 为每个模块生成 BOSC 发布版本的 Verilog，带 `bosc_` 前缀和 `--sram-replace` 参数（SRAM 替换为行为模型），配置为 `DefaultConfig`。

---

## 11. 设计模式与架构特征

### 11.1 关注点分离

xiangshan.py 将配置管理（XSArgs）与执行逻辑（XiangShan）清晰分离。XSArgs 纯粹负责参数的存储、转换和展示，不涉及任何 I/O 操作。XiangShan 负责构建和执行流水线。

### 11.2 NFS 共享工作负载

所有 CI 工作负载存储在 NFS 共享路径 `/nfs/home/share/ci-workloads/` 下，而非仓库内部。这种设计：
- 避免大型二进制文件污染 git 仓库
- 允许多台 CI 服务器共享同一份工作负载
- 支持独立于代码版本更新测试用例

### 11.3 CI 与本地的一致性

`local_ci.py` 通过直接解析 GitHub Actions YAML 确保本地和 CI 环境执行相同的测试流程，消除"CI 过了但本地不行"的问题。

### 11.4 资源感知调度

NUMA 调度算法综合考虑了 CPU 使用率、进程亲和度、NUMA 拓扑三个维度，避免在高负载核心上运行 EMU，减少内存访问延迟，提升仿真性能。

### 11.5 容器化可选

Docker 集成采用"可选增强"模式 - 开发者可以选择直接在宿主机运行，也可以通过 `make docker-deps` 透明地使用容器环境。容器内通过 `IN_XSDEV_DOCKER` 环境变量防止递归容器化。

---

## 12. 技术依赖分析

### Python 依赖（requirements.txt）

- **matplotlib**：性能图表绘制
- **numpy**：数值计算
- **pandas**：性能数据处理和分析
- **psutil**：进程和 CPU 监控（NUMA 调度的核心依赖）

### 外部工具依赖

- **Verilator**：开源 Verilog 仿真器
- **VCS/Verdi**（Synopsys）：商业仿真和调试工具
- **GSIM**：新一代仿真后端
- **LLVM profdata**：PGO profile 数据处理
- **numactl**：NUMA 亲和性控制
- **Mill**：Scala/Chisel 构建工具
- **Docker**：容器化环境

### 外部仓库依赖

- **NEMU**：参考模型（difftest）
- **Nexus-AM**：裸机测试框架和 workload 生成
- **DRAMsim3**：DRAM 时序仿真
- **riscv-tests**：RISC-V 标准 ISA 测试集

---

## 13. 总结

XiangShan 的 CI 和测试框架体现了大型开源硬件项目在软件工程方面的成熟度：

1. **配置管理**：XSArgs 将 Chisel 编译、Makefile 构建、EMU 运行三层参数统一管理，通过 `shlex.quote()` 确保 shell 安全性
2. **测试覆盖**：从基础 cputest 到完整的 SPEC benchmark，从标量 ISA 到 H/V 扩展，构建了多层次的验证体系
3. **资源调度**：NUMA 感知的核心分配算法在 CI 服务器上实现高效资源利用
4. **多后端支持**：Verilator、GSIM、VCS 三种后端满足不同场景需求
5. **本地可复现**：通过解析 GitHub Actions YAML 实现 CI 流程的本地复现
6. **容器化支持**：透明的 Docker 集成降低环境配置门槛
7. **PGO 优化**：集成 Profile-Guided Optimization 提升 EMU 运行性能
8. **运维支持**：自动化的 bug 报告生成和批量 Verilog 生成工具

这套框架支撑着 XiangShan 处理器的持续集成和验证，确保每次代码变更都能在完整的测试矩阵上得到验证。
