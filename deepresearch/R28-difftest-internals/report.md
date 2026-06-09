# R28 - DiffTest Internals: C++ Framework Deep Dive

## 1. DiffTest Comparison Engine Architecture

DiffTest 是 XiangShan RISC-V 处理器验证体系的核心组件，其 C++ Framework 构成了整个 differential testing 的执行引擎。整个框架的设计哲学是：将 DUT（Design Under Test，即 RTL 仿真模型）的每一个 architectural commit 事件与 Reference Model（NEMU 或 Spike）的执行结果进行逐周期、逐指令的比对。

### 1.1 核心类结构

DiffTest 框架的核心类 `Difftest` 定义在 `difftest/src/test/csrc/difftest/difftest.h` 中。每个 CPU core 对应一个 `Difftest` 实例，通过全局数组 `Difftest **difftest` 管理多核场景。其关键成员包括：

- `DiffState *state`：保存当前比较周期的 DiffTest 内部状态，包括 commit trace、store event queue 等
- `DiffTestState *dut`：指向当前 DUT 状态的指针，该状态通过 DPI-C 接口从 RTL 传递过来
- `RefProxy *proxy`：Reference Model 的代理接口，封装了与 NEMU/Spike 的所有交互
- `std::vector<DiffTestChecker *> checkers`：Checker 插件列表，每种检查逻辑都封装为一个 Checker 对象

### 1.2 初始化流程

`difftest_init()` 函数（`difftest.cpp` 第59行）是整个框架的入口点。其初始化顺序为：

1. 初始化性能计数器（`difftest_perfcnt_init`）
2. 初始化 IO trace 和 query 系统
3. 初始化 `diffstate_buffer`（DPI-C 写入缓冲区）
4. 分配多核 `difftest` 数组
5. 调用 `init_goldenmem()` 初始化 Golden Memory（必须在 `update_nemuproxy` 之前完成）
6. 对每个 core 创建 `Difftest` 实例，设置 `dut` 指针
7. 调用 `update_nemuproxy()` 加载 Reference Model 的共享库
8. 调用 `init_checkers()` 注册所有 Checker 插件
9. 注册信号处理函数（SIGINT, SIGTERM, SIGABRT, SIGSEGV, SIGBUS）

### 1.3 CheckAll：核心比较循环

`Difftest::check_all()` 方法（`difftest.cpp` 第483行）是 DiffTest 引擎的核心执行路径，每个 step 被调用一次。其逻辑流程如下：

1. **更新 cycle count**：从 trap event 中获取当前周期号
2. **执行 normal checkers**：遍历所有注册的 Checker 并调用 `step()`，任何一个 Checker 返回非零值都会立即中断
3. **处理 arch event**：如果当前周期有 architecture event（异常/中断），调用 `arch_event_checker`
4. **处理 instruction commit**：对每个 commit width 的有效 commit 指令，调用 `instr_commit_checker[i]->step()`
5. **更新 delayed writeback**：处理延迟写回寄存器的状态
6. **调用 proxy->sync()**：从 Reference Model 同步寄存器状态到 `ref_state_t`
7. **记录 commit group**：通过 `state->record_group()` 记录提交组信息
8. **应用延迟写回**：`apply_delayed_writeback()` 将 DUT 中未完成写回的寄存器值替换为 Reference 的值
9. **执行 compare**：`proxy->compare(dut)` 对所有寄存器组（xrf, frf, vrf, csr, hcsr 等）执行 `memcmp` 比较

如果在 compare 阶段发现 mismatch，则返回 `STATE_DIFF`，随后调用 `display()` 输出详细的寄存器差异信息，包括 PC、源/目的寄存器名、DUT 和 REF 的值。

### 1.4 Checker Plugin System

Checker 插件系统是 DiffTest 框架中最重要的扩展机制。所有 Checker 继承自 `DiffTestChecker` 基类（定义在 `checkers.h`），该基类提供了统一的 `step()` 接口，支持性能计时（`CONFIG_DIFFTEST_CHECKER_PERF`）。

基类定义了四种返回状态：
- `STATE_OK`（0）：检查通过
- `STATE_DIFF`（1）：发现不一致
- `STATE_ERROR`（2）：错误
- `STATE_TRAP`（3）：触发 trap

`init_checkers()` 方法（`difftest.cpp` 第261行）注册的 Checker 包括：

| Checker | 功能 | 条件编译宏 |
|---------|------|-----------|
| `TimeoutChecker` | 检测仿真超时（首次提交限制 15000 cycles） | 始终启用 |
| `FirstInstrCommitChecker` | 确保 DUT 和 REF 的首条指令一致 | 始终启用 |
| `StoreRecorder` / `StoreChecker` | 记录和检查 store 事件 | `CONFIG_DIFFTEST_STOREEVENT` |
| `LoadChecker` | 检查 load 事件与 Golden Memory 一致性 | `CONFIG_DIFFTEST_LOADEVENT` |
| `SbufferChecker` | 检查 Store Buffer 事件 | `DEBUG_GOLDENMEM` + `CONFIG_DIFFTEST_SBUFFEREVENT` |
| `AtomicChecker` | 检查原子操作事件 | `DEBUG_GOLDENMEM` + `CONFIG_DIFFTEST_ATOMICEVENT` |
| `L1TLBChecker` / `L2TLBChecker` | 检查 TLB 事件 | `DEBUG_L1TLB` / `DEBUG_L2TLB` |
| `RefillChecker` | 检查 cache refill 事件 | `DEBUG_REFILL` |
| `CmoInvalRecorder` | 检查 CMO invalidation 事件 | `CONFIG_DIFFTEST_CMOINVALEVENT` |
| `LrScChecker` | 检查 LR/SC 事件 | `CONFIG_DIFFTEST_LRSCEVENT` |
| `AiaChecker` | 检查 AIA 中断控制器同步 | `CONFIG_DIFFTEST_SYNCAIAEVENT` |
| `ArchEventChecker` | 处理异常和中断事件 | 始终启用 |
| `InstrCommitChecker` | 检查每条提交指令，集成 load/store 子 checker | 始终启用（per commit width） |

Checker 系统还支持 `Stopwatch` 性能计时，通过 `CONFIG_DIFFTEST_CHECKER_PERF` 宏控制，可在运行结束时打印每个 Checker 的耗时统计。

### 1.5 Multi-core Support

DiffTest 框架原生支持多核仿真。全局数组 `difftest[NUM_CORES]` 存储每个 core 的实例。`difftest_step()` 和 `difftest_nstep()` 函数遍历所有 core 依次执行检查。对于多核场景，每个 core 有独立的 Golden Memory proxy（通过 `ref_set_mhartid` 和 `ref_put_gmaddr` 设置），且使用 `dlmopen(LM_ID_NEWLM, ...)` 加载独立的 Reference Model shared library namespace。

## 2. Golden Memory Management

Golden Memory 是 DiffTest 框架中用于维护 Reference Model 物理内存映像的核心组件，定义在 `goldenmem.h` 和 `goldenmem.cpp` 中。

### 2.1 内存映射（mmap）

`init_goldenmem()` 函数（`goldenmem.cpp` 第32行）使用 `mmap` 分配两块与 DUT RAM 等大的匿名内存：

- `pmem`：存储 Golden Memory 的实际数据
- `pmem_flag`：存储每个字节的标记位（0 = 需同时更新和检查，1 = 仅更新但跳过检查）

关键分配标志：
```
mmap(NULL, pmem_size, PROT_READ | PROT_WRITE, MAP_ANON | MAP_PRIVATE | MAP_NORESERVE, -1, 0)
```

`MAP_NORESERVE` 标志表示不预留 swap space，这对于大型内存映射（如 XiangShan 需要数 GB 的物理内存）非常重要，避免了不必要的物理内存分配。同时通过 `simMemory->clone_on_demand()` 实现按需克隆 DUT 的 RAM 内容到 Golden Memory，利用了 Copy-on-Write 语义来优化初始化性能。

### 2.2 地址转换与访问

- `guest_to_host(addr)`：将 guest 物理地址转换为 host 虚拟地址（直接加上 pmem 基址）
- `in_pmem(addr)`：检查地址是否在物理内存范围内（`PMEM_BASE <= addr <= PMEM_BASE + pmem_size - 1`）
- `paddr_read(addr, len)` / `paddr_write(addr, data, flag, len)`：物理地址读写接口，包含范围检查
- `pmem_read` / `pmem_write`：直接内存读写，支持 1/2/4/8 字节粒度

### 2.3 Store Log 机制

Store Log 是 DiffTest 框架中一个精巧的设计，用于实现 store 事件的回滚（replay）。当 `ENABLE_STORE_LOG` 宏启用时：

- `goldenmem_store_log_buf[1024]`：记录最多 1024 条 store 操作的原始值
- `goldenmem_set_store_log(bool enable)`：开关 store log 记录
- `pmem_record_store(addr)`：在每次写入前，将原始数据记录到 store log buffer（地址对齐到 8 字节）
- `goldenmem_store_log_restore()`：按 LIFO 顺序恢复所有被修改的内存位置

这个机制在 replay 场景中至关重要。当 DiffTest 检测到 mismatch 需要回滚时，首先从 snapshot 中恢复 Reference Model 状态，然后通过 `goldenmem_store_log_restore()` 恢复 Golden Memory 到 mismatch 发生之前的状态。

### 2.4 update_goldenmem 接口

`update_goldenmem(addr, data, mask, len, flag)` 是 DPI-C 导出函数，由 RTL 的 store commit 事件驱动调用。它根据 mask 逐字节将数据写入 Golden Memory，并通过 `flag` 参数控制是否需要在后续的 load 检查中验证该位置的数据。

## 3. Reference Proxy (NEMU/Spike Dynamic Loading)

Reference Proxy 是 DiffTest 框架与 Reference Model（如 NEMU 或 Spike）之间的桥梁，定义在 `refproxy.h` 和 `refproxy.cpp` 中。

### 3.1 动态加载机制

`AbstractRefProxy` 类（`refproxy.h` 第167行）通过 `dlopen` / `dlsym` 实现运行时动态加载 Reference Model 的 shared library。加载路径通过环境变量指定：

- NEMU: `$NEMU_HOME/build/riscv64-nemu-interpreter-so`
- Spike: `$SPIKE_HOME/difftest/build/riscv64-spike-so`

加载模式为 `RTLD_LAZY | RTLD_DEEPBIND`，其中 `RTLD_DEEPBIND` 确保 Reference Model 内部的符号解析优先使用自身定义，避免与 DiffTest 框架的符号冲突。

对于多核场景（`NUM_CORES > 1`），使用 `dlmopen(LM_ID_NEWLM, ...)` 为每个 core 创建独立的链接域，确保各 core 的 Reference Model 状态完全隔离。

此外，框架还支持通过 `LINKED_REFPROXY_LIB` 宏直接静态链接 Reference Model，此时使用 weak symbol 机制处理可选 API。

### 3.2 REF API 分层

RefProxy 的 API 被分为两层：

**REF_BASE（必需 API）**：
- `ref_init`：初始化 Reference Model
- `ref_regcpy`：在 DUT 和 REF 之间拷贝寄存器状态
- `ref_csrcpy`：拷贝 CSR 状态
- `ref_memcpy`：拷贝内存数据
- `ref_exec`：执行一条指令
- `ref_reg_display`：显示寄存器状态
- `store_commit`：提交 store 事件
- `raise_intr`：触发中断

**REF_OPTIONAL（可选 API）**：
- `ref_skip_one`：跳过一条指令（用于 skip 指令的处理）
- `ref_guided_exec`：引导式执行（用于 fuzzing 场景的异常注入）
- `ref_store_log_reset` / `ref_store_log_restore`：Reference Model 端的 store log 管理
- `debug_mem_sync`：调试模式内存同步
- `ref_put_gmaddr`：传递 Golden Memory 地址给 Reference Model
- `ref_set_mhartid`：设置多核的 hart ID
- 以及 AIA、interrupt delegation、critical error 等可选功能

### 3.3 状态比较逻辑

`RefProxy::compare()` 方法（`refproxy.cpp` 第175行）对 DUT 和 REF 的寄存器状态进行逐字段的 `memcmp` 比较。比较的字段包括：

1. `xrf`（整数寄存器文件）
2. `frf`（浮点寄存器文件，如果启用）
3. `vrf`（向量寄存器文件，如果启用）
4. `vcsr`（向量 CSR）
5. `fcsr`（浮点 CSR）
6. `hcsr`（Hypervisor CSR，如果启用）
7. `triggercsr`（触发器 CSR）
8. `csr`（标准 CSR）

对于 CSR 比较，框架还实现了 `do_csr_waive()` 方法，处理 Rocket Chip 等参考实现中 CSR 值的特殊编码（如 mtval/stval 的虚拟地址编码、mtvec/stvec 的向量表对齐）。

### 3.4 skip_one 的实现

`skip_one()` 方法（`refproxy.h` 第237行）用于处理 DUT 跳过某些指令的场景。如果有 `ref_skip_one` API，则直接调用 Reference Model 的 skip 实现；否则，手动同步 REF 状态、更新 PC（RVC 指令 +2，否则 +4）、设置目标寄存器值，再将状态同步回 REF。

### 3.5 NemuProxy 和 SpikeProxy

`NemuProxy` 和 `SpikeProxy` 是 `RefProxy` 的特化子类，分别对应 NEMU 和 Spike 两个 Reference Model。它们仅在构造函数中指定不同的环境变量和 SO 文件路径，其余逻辑完全复用 `RefProxy`。

`LinkedProxy` 则用于 Reference Model 被静态链接到 DiffTest 可执行文件的场景，不支持多核。

## 4. Snapshot Save/Restore

DiffTest 框架实现了两层 snapshot 机制：Verilator 级别的 DUT state snapshot 和包含 Reference Model 状态的完整 checkpoint。

### 4.1 Verilator State Snapshot

`VerilatorSim` 类（`verilator.h`）通过 Verilator 的 `VM_SAVABLE` 特性实现 DUT 状态的序列化：

- `snapshot_init()`：分配两个 `VerilatedSaveMem` slot，用于双缓冲交替保存
- `snapshot_take()`：将当前 DUT 状态序列化到内存 buffer，返回一个回调函数用于追加额外数据
- `snapshot_save(int index)`：将内存 buffer 持久化到文件系统，index=-1 时保存所有 slot
- `snapshot_load(const char *filename)`：从文件恢复 DUT 状态

双缓冲设计（`last_slot` 交替）确保在频繁 snapshot 时不会覆盖正在进行的写入操作。

### 4.2 Emulator Level Snapshot

`Emulator` 类的 `snapshot_save()` 方法（`emu.cpp` 第666行）在 Verilator snapshot 基础上追加了：

1. **simMemory 数据**：DUT 的物理内存内容（先写 size，再写数据）
2. **cycleCnt**：当前仿真周期计数
3. **proxy->state**：Reference Model 的完整寄存器状态（`ref_state_t`）
4. **Reference Model 内存**：通过 `proxy->mem_init()` 获取的 REF 物理内存内容
5. **CSR buf**：Reference Model 的 CSR 状态（4096 个 uint64_t）
6. **sdcard offset**：SD 卡仿真文件的位置偏移

`snapshot_load()` 方法执行逆操作，恢复所有状态并将 DUT 和 REF 的内存及寄存器状态同步。

### 4.3 Snapshot 自动保存策略

在 `Emulator::tick()` 中，snapshot 以如下策略自动保存：
- 每 60 秒保存一次到内存（`SNAPSHOT_INTERVAL`）
- 每 60 次内存 snapshot 后，将一份持久化到文件系统
- 仿真异常结束时，保存所有 slot 到文件

### 4.4 Replay 机制

DiffTest 的 replay 机制（`CONFIG_DIFFTEST_REPLAY`）用于在检测到 mismatch 时精确定位错误指令。流程为：

1. `replay_snapshot()`：保存当前 DiffState 和 proxy state 到 `state_ss` / `proxy_reg_ss`，记录 REF 的 CSR 状态
2. `do_replay()`：恢复到 snapshot 状态，将 REF 和 Golden Memory 的 store log 恢复
3. 通过 `difftest_replay_head()` DPI-C 调用通知 RTL 进入 replay 模式
4. 在 replay 范围内重新执行，直到错误位置被精确定位

## 5. Waveform Dump

Waveform dump 功能通过 `VerilatorSim` 类封装，支持 VCD 和 FST 两种格式。

### 5.1 初始化

`waveform_init()` 方法（`verilator.cpp` 第62行）创建 `EmuWaveform` 实例，传入 `trace_bind` lambda 函数。该 lambda 调用 Verilator 的 `dut->trace(tfp, levels)` 方法将 DUT 的信号连接到波形追踪器。支持两种重载：
- `waveform_init(uint64_t cycles)`：自动命名波形文件
- `waveform_init(uint64_t cycles, const char *filename)`：指定文件名

### 5.2 波形控制

- `waveform_tick()`：每个时钟周期调用，将当前信号状态写入波形文件
- 在 `Emulator::single_cycle()` 中，波形记录受 `args.log_begin` 和 `args.log_end` 的范围控制
- `enable_waveform_full` 选项控制是否在 reset 阶段也记录波形
- `force_dump_wave` 标志用于 fork 模式下强制记录波形

### 5.3 文件格式

根据编译选项，波形文件格式为：
- `ENABLE_FST`：FST 格式（`.fst`），体积更小
- 默认：VCD 格式（`.vcd`），通用性更好

## 6. Emulator Main Loop

Emulator 主循环是 DiffTest 仿真系统的顶层控制逻辑，定义在 `emu.h` 和 `emu.cpp` 中。

### 6.1 Emulator 构造函数

`Emulator::Emulator()` 构造函数（`emu.cpp` 第48行）执行完整的系统初始化：

1. 设置 Verilator 栈大小（32MB，防止大设计的 stack overflow）
2. 解析命令行参数
3. 初始化 random seed（`srand`, `srand48`, `Verilated::randSeed`）
4. 初始化 JTAG remote bitbang
5. 初始化 flash
6. 初始化波形（如果启用）
7. 复位 DUT（`reset_ncycles`）
8. 初始化 RAM（支持多种 Memory 后端：`FootprintsMemory`, `MmapMemoryWithFootprints`, `LinearizedFootprintsMemory`）
9. 加载 GCheckpoint 恢复数据
10. 初始化 DiffTest（`difftest_init`）
11. 初始化 DiffTest traces
12. 初始化外设
13. 初始化 fork 模式（`LightSSS`）

### 6.2 tick 方法

`Emulator::tick()` 方法（`emu.cpp` 第382行）是主循环的核心，每个仿真周期调用一次。其逻辑流程：

1. **检查周期限制**：对比 `trap->cycleCnt` 与 `args.max_cycles`
2. **检查指令限制**：对比 `trap->instrCnt` 与 `core_max_instr[i]`
3. **检查 assertion**：`assert_count > 0` 表示有 assertion 失败
4. **检查信号**：`signal_num != 0` 表示有外部信号
5. **检查 DUT 退出信号**：`difftest_exit` 非零表示 DUT 请求退出
6. **warmup 处理**：达到 warmup 指令数后触发性能计数器 dump 和 reset
7. **执行单周期**：`single_cycle()` 推进 DUT 一个时钟周期
8. **DiffTest step**：获取 `difftest_step` 值，调用 `difftest_nstep()` 执行检查
9. **trace 处理**：写入或读取 DiffTest trace
10. **stuck 检测**：如果连续多个周期没有 commit progress，报告 stuck
11. **snapshot 自动保存**：定期保存 snapshot
12. **fork 检查**：根据 fork interval 决定是否 fork checkpoint 子进程

### 6.3 single_cycle 方法

`single_cycle()` 方法（`emu.cpp` 第327行）推进 Verilator 仿真一个时钟周期：

1. 设置 `clock = 1`，调用 `dut->eval()`
2. 在 clock 上升沿记录波形（如果在 log range 内）
3. 执行 DRAMSim3 step（如果启用）
4. 执行 UART step
5. 设置 `clock = 0`，调用 `dut->eval()`
6. 如果 `waveform_full`，在 clock 下降沿也记录波形

### 6.4 reset_ncycles

`reset_ncycles()` 方法（`emu.cpp` 第290行）执行指定周期数的复位序列。每个周期：
- 设置 `reset = 1`，执行一个完整时钟周期
- 可选地在复位期间记录波形
- 设置 `reset = 0`

### 6.5 display_stats

仿真结束时，`display_stats()` 方法输出每个 core 的 trap 信息（GOOD TRAP / BAD TRAP / ABORT / LIMIT EXCEEDED 等），以及 IPC 统计。之后触发最后一次性能计数器 dump。

## 7. DPI-C Interface and Batch Mode

DPI-C Interface 是连接 Verilog RTL 和 C++ Testbench 的桥梁，Batch Mode 则是其高性能优化版本。

### 7.1 DPI-C 基础架构

`DPICBase` 类（`DPIC.scala` 第32行）是所有 DPI-C 接口模块的基类。它继承自 `ExtModule`，支持内联 Verilog 模块生成。其核心功能包括：

- **类型映射**：将 Chisel `Data` 类型映射到 SystemVerilog/C++ 类型（1 bit -> uint8_t, <=16 bit -> uint16_t, <=32 bit -> uint32_t, <=64 bit -> uint64_t）
- **DPI-C 函数生成**：自动生成 `extern "C"` 函数原型和实现
- **Verilog 模块生成**：生成包含 `import "DPI-C"` 和 `always @(posedge clock)` 块的 wrapper 模块
- **性能计数**：每个 DPI-C 调用自动累加 `dpic_calls` 和 `dpic_bytes`

`DPIC` 类（`DPIC.scala` 第181行）是具体的 DiffTest bundle DPI-C 实现。它为每个 `DifftestBundle` 创建一个 Verilog wrapper 模块，在时钟上升沿且 `enable` 信号有效时调用对应的 DPI-C 函数。

DPI-C 函数的实现逻辑（`dpicFuncAssigns`）：
1. 获取 `diffstate_buffer` 指针
2. 通过 `DUT_BUF(coreid, zone, index)` 宏定位到目标 DiffTestState 结构体
3. 将输入参数逐字段赋值到结构体中
4. 对于有 `valid` 信号的 bundle，设置 `packet->valid = true`

### 7.2 DPICBuffer 环形缓冲区

`DPICBuffer` 类是 DPI-C 数据写入的缓冲区，实现了双层索引：

```cpp
DiffTestState buffer[CONFIG_DIFFTEST_ZONESIZE][CONFIG_DIFFTEST_BUFLEN];
```

- `zone`：区域索引，支持 `CONFIG_DIFFTEST_ZONESIZE` 个 zone
- `index`：zone 内的步骤索引
- `read_ptr`：读指针，每次 `next()` 调用后递增
- `zone_ptr`：当前 zone 指针

`switch_zone()` 方法切换到下一个 zone，同时重置 read_ptr。这种设计允许 DUT 继续写入新 zone 的同时，C++ testbench 从前一个 zone 读取数据，实现了流水化的数据传输。

### 7.3 Batch Mode

Batch Mode 是 DiffTest 的高性能优化，将多个周期的 DiffTest 数据打包成一个大的 DPI-C 调用，显著减少了 DPI-C 调用次数和仿真开销。

**数据格式**：Batch 数据由 `data`（所有 bundle 的字节对齐数据）和 `info`（元数据索引数组）组成。每个 `BatchInfo` 条目包含：

- `id`（8 bit）：bundle 类型 ID
- `num`（8 bit）：该类型的实例数量

**Batch Pipeline**：`BatchEndpoint` 模块包含两个阶段：

1. **BatchCollector**：收集同一周期内的所有 valid bundle，按类型分组。使用 `BatchCluster` 模块对同一类型的多个实例进行 compact 操作（移除 invalid 实例，压缩数据）
2. **BatchAssembler**：跨周期累积数据，当以下任一条件满足时发送一个 batch：
   - 数据量达到 `MaxDataByteLen`（信息量溢出）
   - 步数达到 `batchSize`（步数限制）
   - trace_size 达到 replay 限制
   - 超时（200000 周期无 flush）
   - 进入 replay 模式

**BatchSplit 优化**：`config.batchSplit` 启用时，Assembler 支持将单个 step 的数据拆分到两个 batch 中，避免因单个 step 过大而浪费已积累的 buffer 空间。

**Batch DPI-C 解包**：`DPICBatch` 类（`DPIC.scala` 第227行）在 C++ 端实现 batch 数据的解包。它将 batch 的二进制数据转换为 C struct，然后遍历 info 数组，对每个 bundle 类型调用对应的解包逻辑，使用 `memcpy` 批量拷贝数据到 `diffstate_buffer`。

Batch 中还定义了两个特殊 ID：
- `BatchStep`：表示一步结束，需要推进 `dut_index`
- `BatchFinish`：表示整个 batch 结束

## 8. SimTop Integration

SimTop 是 DiffTest 框架与 RTL 设计的顶层集成点。

### 8.1 DUT 接口

`VerilatorSim` 类（`verilator.h`）封装了与 `VSimTop`（Verilator 编译生成的 C++ 类）的交互接口：

- `set_clock(unsigned clock)`：设置时钟信号
- `set_reset(unsigned reset)`：设置复位信号
- `step()`：调用 `dut->eval()` 执行一个 eval step
- `get_difftest_exit()`：获取 DUT 的退出信号
- `get_difftest_step()`：获取 DUT 产生的 DiffTest step 数量
- `set_perf_clean/dump`：控制性能计数器
- `set_log_begin/end`：设置日志输出范围
- UART I/O 接口

### 8.2 Scala 端 SimTop

`DifftestModule.top()` 方法（`Difftest.scala` 第605行）创建 `SimTop` 实例，将带有 `HasDiffTestInterfaces` trait 的 CPU 生成器包装在 SimTop 中。SimTop 负责：

1. 连接 CPU 的 DiffTest IO 接口到 Gateway
2. 管理多核的 DiffTest 接口聚合
3. 生成 C++ header（`difftest-state.h`）和 Verilog header（`DifftestMacros.svh`）

### 8.3 C++ Header 自动生成

`DifftestModule.generateCppHeader()` 方法（`Difftest.scala` 第657行）自动生成 `difftest-state.h`，包含：

- 所有 DiffTest Bundle 的 C struct 定义
- `CONFIG_DIFFTEST_*` 宏定义
- `DiffTestRegState` 结构体（所有寄存器状态的聚合）
- `DiffTestState` 结构体（完整 DUT 状态）
- `DiffStateBuffer` 抽象基类声明

## 9. Plugin System

DiffTest 框架的 Plugin System 主要体现在 Checker 插件和 Gateway 两个层面。

### 9.1 Checker 插件架构

Checker 插件采用模板方法模式，基类 `DiffTestChecker` 定义了统一的 `step()` 流程（before_step -> do_step -> after_step），子类只需实现 `do_step()` 方法。

`ProbeChecker<Probe>` 模板类（`checkers.h` 第100行）进一步抽象了 Probe 数据的获取逻辑：

```cpp
template <typename Probe> class ProbeChecker : public DiffTestChecker {
  using GetProbeFn = std::function<Probe &()>;
  GetProbeFn get_probe;
  virtual int do_step() override {
    Probe &probe = get_probe();
    if (get_valid(probe)) {
      int ret = check(probe);
      clear_valid(probe);
      return ret;
    }
    return 0;
  }
};
```

这种设计使得新增 Checker 非常简单：只需继承 `ProbeChecker<SpecificEvent>` 并实现 `get_valid()`、`check()` 和 `clear_valid()` 三个方法。

### 9.2 Scala 端的 Bundle 体系

DiffTest 的 Scala 端定义了丰富的 Bundle 类型层次：

- `DifftestBundle`（trait）：所有 DiffTest Bundle 的基接口
- `DifftestWithIndex`：支持索引的 Bundle（如 per-commit-width 的 commit event）
- `DifftestWithStamp`：带时间戳的 Bundle（用于 squash 场景）
- `DiffTestIsInherited`：支持从父 Bundle 继承字段

Squash 机制允许在 Batch 模式下合并多个同类型的事件。例如，`DiffInstrCommit` 支持 squash 操作，可以将多个连续的 commit 合并为一个（累加 `nFused`），减少传输数据量。

### 9.3 Gateway 系统

Gateway（`difftest/src/main/scala/Gateway.scala`，未直接读取但通过 `DifftestModule.apply()` 引用）是 Scala 端的总线聚合器，负责：

- 收集所有 `DifftestModule(gen)` 创建的 DiffTest 接口
- 执行 squash 优化
- 选择 sink 后端（DPIC 或 Batch）
- 生成最终的 Verilog 模块和 C++ 代码

`GatewayConfig` 控制了所有 DiffTest 行为的参数，包括 batch size、是否启用 squash、delta mode、replay size 等。

### 9.4 Delta Mode

Delta Mode（`CONFIG_DIFFTEST_DELTA`）是一种优化传输模式，只传输与上一周期相比发生变化的寄存器值。`DiffDeltaElem` 类（`Difftest.scala` 第224行）是 Delta 元素的基类，`DeltaStats` 在 C++ 端维护每个寄存器的 dirty bit，只在有变化时触发 DPI-C 传输。

## 10. Key Source File Locations

以下列出 DiffTest C++ Framework 的关键源文件路径（均相对于 `difftest/src/`）：

### C++ 核心文件
| 文件路径 | 功能描述 |
|---------|---------|
| `test/csrc/difftest/difftest.h` | DiffTest 核心类声明，Checker 初始化，step 接口 |
| `test/csrc/difftest/difftest.cpp` | DiffTest 核心逻辑实现：check_all, delayed writeback, replay |
| `test/csrc/difftest/diffstate.h` | DiffState 类定义，commit trace 系统 |
| `test/csrc/difftest/diffstate.cpp` | DiffState 显示逻辑，commit data 获取 |
| `test/csrc/difftest/checkers.h` | 所有 Checker 插件的声明（Timeout, Store, Load, Atomic, TLB 等） |
| `test/csrc/difftest/goldenmem.h` | Golden Memory 接口声明 |
| `test/csrc/difftest/goldenmem.cpp` | Golden Memory 实现：mmap, store log, paddr 读写 |
| `test/csrc/difftest/refproxy.h` | Reference Proxy 类声明，REF API 定义，NemuProxy/SpikeProxy |
| `test/csrc/difftest/refproxy.cpp` | Reference Proxy 实现：动态加载，状态比较，CSR waive |
| `test/csrc/difftest/difftrace.h` | DiffTrace 模板类（trace 读写支持） |

### Emulator / Verilator 集成
| 文件路径 | 功能描述 |
|---------|---------|
| `test/csrc/emu/emu.h` | Emulator 类声明 |
| `test/csrc/emu/emu.cpp` | Emulator 实现：构造/析构、tick、snapshot save/load、display_stats |
| `test/csrc/verilator/verilator.h` | VerilatorSim 类声明，Simulator 接口实现 |
| `test/csrc/verilator/verilator.cpp` | VerilatorSim 实现：waveform、snapshot（VerilatedSaveMem） |

### Scala 框架文件
| 文件路径 | 功能描述 |
|---------|---------|
| `main/scala/Difftest.scala` | 所有 DiffTest Bundle 定义，DifftestModule，C++ header 生成 |
| `main/scala/DPIC.scala` | DPI-C 接口生成，DPICBuffer，DPICBatch 解包 |
| `main/scala/Batch.scala` | Batch 模式硬件：BatchCollector, BatchAssembler, BatchCluster |

---

## 总结

DiffTest C++ Framework 是一个精心设计的多层次验证框架。其核心架构可以概括为：

1. **数据层**：通过 DPI-C 和 DPICBuffer 实现 RTL 到 C++ 的高效数据传输，Batch Mode 进一步将多个周期的数据打包
2. **存储层**：Golden Memory 使用 mmap 实现大块内存的高效映射，Store Log 机制支持操作回滚
3. **代理层**：RefProxy 通过 dlopen 动态加载 Reference Model，支持 NEMU/Spike/静态链接三种模式
4. **比较层**：Checker Plugin 系统提供了灵活的可扩展检查框架，涵盖 timeout、commit、store、load、atomic、TLB、LR/SC 等各种微架构事件
5. **控制层**：Emulator 主循环管理整个仿真流程，包括周期控制、snapshot、waveform、fork/checkpoint 等功能

整个框架通过大量 `CONFIG_*` 宏实现了高度的可配置性，可以根据不同的验证需求（RTL debug、fuzzing、性能评估、FPGA 等）灵活裁剪功能。Scala 端的代码生成器自动完成从 Chisel Bundle 到 C struct 的映射，确保了硬件和软件之间数据结构的一致性。
