# R21 - DiffTest & Verification Framework 深度研究报告

## 1. 概述

XiangShan（香山）RISC-V 处理器项目采用了一套名为 **DiffTest** 的差异测试框架作为其核心功能验证基础设施。DiffTest 由中国科学院计算技术研究所（ICT）开发，采用 Mulan PSL v2 许可证，是目前 RISC-V 开源处理器生态中最为成熟和复杂的协同仿真（Co-simulation）验证框架之一。

DiffTest 的核心思想是：在 RTL 仿真运行时，同时驱动一个行为级参考模型（如 NEMU 或 Spike），在每个指令提交（commit）周期对比 DUT（Design Under Test，即 XiangShan RTL）与 REF（Reference Model）的架构状态。一旦两者出现不一致，即判定为验证失败，并报告详细的差异信息。

该框架不仅支持简单的逐周期对比，还引入了 Batch（批量传输）、Squash（状态压缩）、Replay（回放）、Delta（增量传输）等高级特性，以在大规模 RTL 仿真中实现高效的 co-simulation。

---

## 2. DiffTest 整体架构

### 2.1 架构概览图

```
+-----------------------------------------------------------------------------------+
|                           SimTop (SimTop.scala)                                   |
|  +-----------------------------------------------------------------------------+  |
|  |                          XiangShan CPU (RTL)                                |  |
|  |  DifftestModule.apply(): 在 RTL 中插入 Difftest Bundle 探针点              |  |
|  |  - DiffInstrCommit (指令提交事件)                                           |  |
|  |  - DiffCSRState (CSR 寄存器状态)                                            |  |
|  |  - DiffArchIntRegState / DiffArchFpRegState (整数/浮点寄存器状态)           |  |
|  |  - DiffArchVecRegState (向量寄存器状态)                                     |  |
|  |  - DiffStoreEvent / DiffLoadEvent (访存事件)                                |  |
|  |  - DiffTrapEvent (异常/中断事件)                                             |  |
|  +-----------------------------------------------------------------------------+  |
|                              | (DPI-C / Verilog 接口)                             |
|  +-----------------------------------------------------------------------------+  |
|  |                    Gateway 模块 (Gateway.scala)                              |  |
|  |  Pipeline: Preprocess -> Replay -> Validate -> Squash -> Delta -> Batch     |  |
|  |  最终通过 DPI-C 函数或 Batch IO 传送到 C++ 仿真主机端                       |  |
|  +-----------------------------------------------------------------------------+  |
+-----------------------------------------------------------------------------------+
                                    |
                          DPI-C / Batch IO 边界
                                    |
+-----------------------------------------------------------------------------------+
|                     C++ 仿真主机端 (difftest/src/test/csrc/)                      |
|  +------------------+  +------------------+  +------------------+                |
|  |   Emulator        |  |   Difftest       |  |   RefProxy       |                |
|  |   (emu.cpp/h)     |  |   (difftest.cpp) |  |   (refproxy.h)   |                |
|  |   主仿真循环      |  |   差异对比引擎    |  |   参考模型代理    |                |
|  +------------------+  +------------------+  +------------------+                |
|         |                      |                      |                           |
|  +------------------+  +------------------+  +------------------+                |
|  |   DUT 状态缓冲   |  |   Checker 体系   |  |   Golden Memory   |                |
|  |   (diffstate.cpp) |  |   (checkers.h)   |  |   (goldenmem.cpp) |                |
|  +------------------+  +------------------+  +------------------+                |
+-----------------------------------------------------------------------------------+
                                    |
                                    v
                     +---------------------------+
                     |   参考模型 (REF)           |
                     |   NEMU / Spike            |
                     |   通过 dlopen() 动态加载   |
                     |   (refproxy.h)             |
                     +---------------------------+
```

### 2.2 关键源文件位置

| 文件/目录 | 路径 | 说明 |
|-----------|------|------|
| DiffTest Bundle 定义 | `difftest/src/main/scala/Bundles.scala` | 定义所有 DiffTest 探针 Bundle 的数据结构 |
| DiffTest Module 接口 | `difftest/src/main/scala/Difftest.scala` | DifftestModule 对象：应用探针、生成 C++/Verilog 头文件 |
| Gateway 配置与模块 | `difftest/src/main/scala/Gateway.scala` | GatewayConfig 配置解析、GatewayEndpoint 模块 |
| DPI-C 传输层 | `difftest/src/main/scala/DPIC.scala` | DPI-C 函数生成、DPICBuffer 缓冲区管理 |
| Batch 批量传输 | `difftest/src/main/scala/Batch.scala` | BatchCollector / BatchAssembler 流水线 |
| Squash 压缩 | `difftest/src/main/scala/Squash.scala` | 多周期状态压缩、Squasher 模块 |
| Validate 验证 | `difftest/src/main/scala/Validate.scala` | valid 信号注入与 updateDependency 检查 |
| Replay 回放 | `difftest/src/main/scala/Replay.scala` | 环形缓冲区记录与回放控制 |
| Delta 增量传输 | `difftest/src/main/scala/Delta.scala` | DeltaSplitter 分割器、仅传输变化的寄存器元素 |
| Trace I/O 跟踪 | `difftest/src/main/scala/Trace.scala` | I/O Trace 的 dump 与 load 功能 |
| Preprocess 预处理 | `difftest/src/main/scala/Preprocess.scala` | 物理寄存器到架构寄存器的转换 |
| SimTop 顶层模块 | `difftest/src/main/scala/SimTop.scala` | 仿真顶层，实例化 CPU 和 Gateway |
| C++ DiffTest 核心 | `difftest/src/test/csrc/difftest/difftest.cpp` | check_all()、step() 等核心对比逻辑 |
| C++ DiffTest 头文件 | `difftest/src/test/csrc/difftest/difftest.h` | Difftest 类定义、Replay 机制声明 |
| Checker 体系 | `difftest/src/test/csrc/difftest/checkers.h` | 各种 Checker 类：InstrCommit、Store、Load 等 |
| RefProxy 代理 | `difftest/src/test/csrc/difftest/refproxy.h` | 参考模型代理：NemuProxy、SpikeProxy、LinkedProxy |
| Golden Memory | `difftest/src/test/csrc/difftest/goldenmem.cpp` | 参考内存模型，用于访存事件校验 |
| DiffState 状态 | `difftest/src/test/csrc/difftest/diffstate.cpp` | DUT 状态追踪与 commit trace 管理 |
| DiffTrace | `difftest/src/test/csrc/difftest/difftrace.h` | Trace 文件读写，支持 zstd 压缩 |
| Emulator 主循环 | `difftest/src/test/csrc/emu/emu.cpp` | Emulator 类：初始化、tick、snapshot |
| Simulator 抽象 | `difftest/src/test/csrc/emu/simulator.h` | Verilator/GSIM 模拟器抽象基类 |
| Plugin: Runahead | `difftest/src/test/csrc/plugin/runahead/` | Runahead Speculative 执行插件 |
| Plugin: SimFrontend | `difftest/src/test/csrc/plugin/simfrontend/` | 模拟前端插件（FTQ / TraceReader） |
| Plugin: Constantin | `difftest/src/test/csrc/plugin/constantin/` | 运行时常量注入插件 |
| Plugin: SpikeDasm | `difftest/src/test/csrc/plugin/spikedasm/` | Spike 反汇编工具 |
| Plugin: XSpdb | `difftest/src/test/csrc/plugin/xspdb/` | 调试器插件 |
| CI: EMU 测试 | `.github/workflows/emu.yml` | 功能测试：GSIM、SimFrontend、MC、VCS |
| CI: 性能回归 | `.github/workflows/perf-template.yml` | SPEC06 性能回归测试模板 |
| CI: Nightly | `.github/workflows/nightly.yml` | 每夜性能回归与报告 |
| Ready-to-Run | `ready-to-run/` | 预构建的测试镜像与参考模型 SO 文件 |

---

## 3. DiffTest 协同仿真流程（Co-simulation Flow）

### 3.1 完整仿真启动流程

DiffTest 的协同仿真流程可以分为以下几个阶段：

**阶段一：Chisel 编译期代码生成**

1. 在 XiangShan CPU 的 Chisel 代码中，通过 `DifftestModule.apply(gen)` 调用在关键微架构点插入探针信号（Bundles）。每个 Bundle 对应一个可观察的架构状态或微架构事件。

2. `Gateway.setConfig(config)` 解析配置字符串（如 `"BSZRE"`），其中每个字符启用一个特性：
   - `B` = Batch（批量传输）
   - `S` = Squash（状态压缩）
   - `Z` = DUT Zone（双缓冲区）
   - `R` = Replay（回放）
   - `E` = Global Enable（全局使能）
   - `D` = Delta（增量传输）
   - `I` = Internal Step（内部步进）
   - `N` = Non-Block（非阻塞 DPI-C）
   - `P` = Performance Counter（性能计数器）
   - `T` = Trace Dump（I/O 跟踪 dump）
   - `L` = Trace Load（I/O 跟踪 load）
   - `H` = Hierarchical Wiring（层次化连线）
   - `F` = FPGA 模式
   - `G` = GSIM 模式

3. 在 `DifftestModule.collect()` 阶段，系统自动收集所有已注册的 Bundles，生成以下关键文件：
   - `difftest-state.h`：C++ 结构体定义，包含 `DiffTestRegState` 和 `DiffTestState`
   - `difftest-dpic.h` / `difftest-dpic.cpp`：DPI-C 函数声明与实现
   - `DifftestMacros.svh`：Verilog 宏定义
   - 可选的 `difftest-delta.h`（Delta 模式）、`difftest-iotrace.h/cpp`（Trace 模式）

**阶段二：RTL 仿真编译**

生成的 Verilog 代码通过 Verilator 或 VCS 编译。DPI-C 模块（`DiffExt*`）在 Verilog 中作为 BlackBox 实例化，在仿真时调用对应的 C++ DPI-C 函数。

**阶段三：C++ 仿真主机初始化**

`main.cpp` -> `Emulator` 构造函数：
1. 解析命令行参数（`parse_args`）
2. 初始化 RAM（`init_ram`）
3. 可选加载 Checkpoint（`overwrite_ram`）
4. 可选加载 Snapshot（`snapshot_load`）
5. 调用 `difftest_init()` 初始化 DiffTest 框架：
   - 分配 `diffstate_buffer`（C++ 侧的 DUT 状态缓冲区）
   - 初始化 `goldenmem`（参考内存模型）
   - 为每个核心创建 `Difftest` 对象和 `RefProxy` 对象（动态加载 NEMU/Spike SO 文件）
   - 初始化各种 Checker

**阶段四：仿真运行主循环**

`Emulator::tick()` 是核心仿真循环：
```
tick():
  1. 检查 cycle/instruction 限制
  2. 检查 assertion 和 signal 状态
  3. 检查 DUT exit 信号
  4. single_cycle()：驱动时钟信号，完成一个时钟周期的 RTL 仿真
  5. 获取 difftest_step 信号（由 Batch/Squash 模块输出）
  6. 调用 difftest_nstep(step)：
     - difftest_switch_zone()：切换双缓冲区
     - difftest_set_dut()：读取 DPI-C 写入的状态缓冲区
     - difftest_step()：对每个核心执行差异对比
  7. 检查 trap 状态
  8. 可选保存 snapshot
  9. 可选 fork checkpoint（lightSSS）
```

**阶段五：DiffTest 差异对比**

`Difftest::check_all()` 是差异对比的核心：
```
check_all():
  1. 运行所有注册的 Checker（TimeoutChecker、StoreRecorder、LoadChecker 等）
  2. 处理 ArchEvent（异常/中断事件）-> 同步 REF 模型的异常状态
  3. 遍历每个 commit slot（多发射宽度）：
     a. 检查 InstrCommit 的 valid 信号
     b. 运行 InstrCommitChecker：
        - 对于 skip 指令：调用 ref_skip_one() 跳过 REF 中对应指令
        - 对于普通指令：检查 PC 一致性，同步 REF 执行（ref_exec），对比寄存器状态
     c. 运行 LoadChecker/StoreChecker 等子 Checker
  4. 处理 Delayed Writeback（延迟写回）
  5. 同步 REF 寄存器状态（proxy->sync()）
  6. 对比 DUT 与 REF 的完整架构状态（proxy->compare(dut)）
```

---

## 4. DiffTest Scala 侧基础设施详解

### 4.1 Bundle 层次结构

DiffTest 定义了一套丰富的 Bundle 类型层次结构：

```
DifftestBaseBundle (trait)
  ├── HasValid (trait)：带 valid 信号
  ├── HasAddress (trait)：带地址索引
  └── DifftestBundle (trait)：核心 trait，定义 squash、delta 等能力
      ├── DiffArchEvent          // 异常/中断事件
      ├── DiffInstrCommit        // 指令提交事件（核心对比信号）
      ├── DiffTrapEvent          // Trap 事件（good trap / bad trap）
      ├── DiffCSRState           // CSR 状态（mstatus, mepc, mcause 等）
      ├── DiffHCSRState          // Hypervisor CSR 状态
      ├── DiffDebugMode          // Debug 模式 CSR
      ├── DiffArchIntRegState    // 整数架构寄存器 x0-x31
      ├── DiffArchFpRegState     // 浮点架构寄存器 ft0-ft11
      ├── DiffArchVecRegState    // 向量架构寄存器 v0-v31
      ├── DiffPhyIntRegState     // 整数物理寄存器
      ├── DiffPhyFpRegState      // 浮点物理寄存器
      ├── DiffPhyVecRegState     // 向量物理寄存器
      ├── DiffArchIntRenameTable // 整数 RAT 映射表
      ├── DiffArchFpRenameTable  // 浮点 RAT 映射表
      ├── DiffStoreEvent         // Store 事件
      ├── DiffLoadEvent          // Load 事件
      ├── DiffSbufferEvent       // Store Buffer 刷出事件
      ├── DiffAtomicEvent        // 原子操作事件
      ├── DiffL1TLBEvent         // L1 TLB 事件
      ├── DiffL2TLBEvent         // L2 TLB 事件
      ├── DiffRefillEvent        // Cache Refill 事件
      ├── DiffLrScEvent          // LR/SC 事件
      ├── DiffVecCSRState        // 向量 CSR 状态
      ├── DiffFpCSRState         // 浮点 CSR 状态
      ├── DiffRunaheadEvent      // Runahead 事件
      ├── DiffNonRegInterruptPendingEvent  // 非寄存器中断挂起
      ├── DiffSyncAIAEvent       // AIA 中断控制器同步
      └── DiffCriticalErrorEvent // 严重错误事件
```

每个 Bundle 实现 `DifftestBundle` trait 的关键方法：
- `desiredCppName`：C++ 结构体中的字段名
- `desiredRegOffset`：可选的 C++ 结构体偏移（用于 `regs.xxx` 布局）
- `updateDependency`：更新依赖列表（如 CSR 依赖 commit 和 event）
- `supportsSquash` / `squash`：Squash 压缩能力
- `squashGroup`：Squash 分组（`"REF"` 或 `"GOLDENMEM"`）
- `supportsDelta`：是否支持增量传输

### 4.2 Gateway 流水线

Gateway 是 DiffTest Scala 侧的核心模块，管理从 DUT 探针信号到 C++ 主机端的数据传输。GatewayEndpoint 内部构建了一条可配置的流水线：

```
DUT 探针信号
    |
    v
[Delayer] -- 可选的延迟器，确保信号稳定
    |
    v
[Trace] -- 可选的 I/O Trace Dump
    |
    v
[Preprocess] -- 预处理：
    |          - 物理寄存器 -> 架构寄存器转换（软/硬件两种模式）
    |          - 单核时移除 LoadEvent（单核不需要 Load 校验）
    v
[Replay] -- 可选的环形缓冲区记录：
    |        - 记录每个有效周期的完整 DiffTest 状态
    |        - 支持从指定位置回放，用于精确定位首次分歧
    v
[Validate] -- 验证层：
    |          - 根据 updateDependency 注入 valid 信号
    |          - 全局使能控制
    v
[Squash] -- 可选的状态压缩层：
    |         - 对连续相同类型的事件进行合并（如连续的 InstrCommit）
    |         - 通过 Stamp 机制记录 commit 序号，匹配 Load/Store 与 Commit
    |         - 支持 SquashControl Verilog 模块动态开关压缩
    v
[Delta] -- 可选的增量传输层：
    |        - 仅传输发生变化的寄存器元素
    |        - 通过 DeltaSplitter 将 Bundle 拆分为多个 delta element
    |        - 支持物理寄存器的 RAT 过滤优化
    v
[Batch] -- 可选的批量传输层：
    |         - 将多个周期的 DiffTest 数据打包到一个 DPI-C 事务中
    |         - BatchCollector 收集同周期数据，BatchAssembler 跨周期组装
    |         - 通过 BatchInfo 头部信息标记每种 Bundle 类型和数量
    |         - 支持 batchSplit 模式分割大数据包
    v
[GatewaySink] -- 最终输出：
              - 非 Batch 模式：直接调用 DPI-C 函数
              - Batch 模式：通过 DiffExtBatch 模块批量传输
```

### 4.3 Batch 批量传输机制

Batch 模式是 DiffTest 的核心性能优化手段。其核心思想是将多个仿真周期的 DiffTest 数据累积到一个缓冲区中，然后通过一次 DPI-C 调用传输给 C++ 主机端，从而大幅减少 DPI-C 调用开销。

**BatchCollector**（同周期收集器）：
- 将同一周期内多个 Bundle 按 `desiredCppName` 分组
- 对每组使用 `BatchCluster` 模块压缩有效数据
- 生成 `BatchStepResult`，包含 data（数据位图）、info（类型/数量头）和 status（字节统计）

**BatchAssembler**（跨周期组装器）：
- 维护一个 `state_data` / `state_info` 累加缓冲区
- 当以下条件之一满足时触发 flush：
  - 缓冲区空间不足（data_exceed / info_exceed）
  - 达到 batch 最大步数（step_exceed）
  - 超时（200000 周期无 flush）
  - Replay 信号触发
  - Trace 缓冲区满
- Flush 时追加 `BatchFinish` 头，通过 DPI-C 发送到 C++ 端

**BatchIO 格式**：
```
BatchIO {
  data: UInt[MaxDataByteLen * 8]  -- 所有 Bundle 数据（紧凑排列）
  info: UInt[MaxInfoByteLen * 8]  -- BatchInfo 数组（标记每种类型和数量）
}
```

每个 `BatchInfo` 包含 8-bit `id`（Bundle 类型索引）和 8-bit `num`（该类型的有效实例数）。

---

## 5. C++ 侧 DiffTest 对比引擎详解

### 5.1 DiffStateBuffer 管理

DPI-C 函数在仿真时将 DUT 状态写入 `diffstate_buffer`（全局数组，每个核心一个）。`DPICBuffer` 实现了 `DiffStateBuffer` 接口，支持多 zone 和多 index 的环形缓冲区：

```cpp
class DPICBuffer : public DiffStateBuffer {
  DiffTestState buffer[CONFIG_DIFFTEST_ZONESIZE][CONFIG_DIFFTEST_BUFLEN];
  int read_ptr, zone_ptr;
};
```

- `CONFIG_DIFFTEST_ZONESIZE`：双缓冲区模式下为 2，允许 DPI-C 写入和 C++ 读取同时进行
- `CONFIG_DIFFTEST_BUFLEN`：Batch 模式下为 `batchSize`，支持多周期累积

### 5.2 RefProxy 参考模型代理

`RefProxy` 通过 `dlopen()` 动态加载参考模型的 shared library（`.so` 文件），并绑定以下关键函数：

```cpp
REF_BASE 宏定义的函数：
  ref_init      -> difftest_init       // 初始化参考模型
  ref_regcpy    -> difftest_regcpy     // 寄存器状态拷贝
  ref_csrcpy    -> difftest_csrcpy     // CSR 状态拷贝
  ref_memcpy    -> difftest_memcpy     // 内存同步
  ref_exec      -> difftest_exec       // 执行指定周期数
  ref_reg_display -> difftest_display  // 显示寄存器状态
  store_commit  -> difftest_store_commit // Store 提交确认
  raise_intr    -> difftest_raise_intr // 注入中断
```

`RefProxy::compare()` 方法是最终的架构状态对比函数，它比较 REF 和 DUT 的完整 `DiffTestRegState`（包括整数寄存器、浮点寄存器、CSR 等）。

支持三种参考模型代理：
- `NemuProxy`：基于 NEMU（NJU EMUlator）
- `SpikeProxy`：基于 Spike（RISC-V ISA Simulator）
- `LinkedProxy`：直接链接参考模型代码（非 dlopen）

### 5.3 Checker 体系

DiffTest 采用面向对象的 Checker 体系进行模块化校验：

```
DiffTestChecker (基类)
  ├── SimpleChecker -- 简单周期性检查
  ├── ProbeChecker<Probe> -- 模板化探针检查（获取 Probe 引用并校验）
  │   ├── ArchEventChecker       -- 异常/中断事件校验
  │   ├── FirstInstrCommitChecker -- 首次提交校验（确保初始状态同步）
  │   ├── InstrCommitChecker      -- 指令提交核心校验
  │   │   ├── 子 checkers：LoadChecker, StoreChecker
  │   │   └── 对每条提交指令调用 proxy->skip_one/exec
  │   ├── TimeoutChecker          -- 超时检测（XiangShan 首次提交上限 15000 周期）
  │   ├── LrScChecker             -- LR/SC 原子操作校验
  │   ├── L1TLBChecker / L2TLBChecker -- TLB 事件校验
  │   ├── RefillChecker           -- Cache Refill 校验
  │   ├── NonRegInterruptPendingChecker -- 外部中断挂起校验
  │   ├── MhpmeventOverflowChecker -- 性能计数器溢出校验
  │   ├── AiaChecker              -- AIA 中断控制器校验
  │   ├── CriticalErrorChecker    -- 严重错误校验
  │   └── GoldenMemoryInit        -- Golden Memory 初始化检查
  ├── StoreRecorder               -- Store 事件记录（写入 DiffState 队列）
  ├── StoreChecker                -- Store 最终校验（在 commit 时验证地址和数据）
  ├── LoadChecker                 -- Load 事件校验（从 goldenmem 读取对比）
  ├── LoadSquashChecker           -- Squash 模式下的 Load 校验
  └── CmoInvalRecorder            -- CMO Invalidation 记录
```

**InstrCommitChecker** 是最核心的 Checker，其工作流程为：
1. 检查 `commit.valid` 信号
2. 对于 skip 指令：调用 `proxy->skip_one()` 同步 REF 模型跳过该指令
3. 对于普通指令：
   - 调用 `proxy->guided_exec()` 或 `proxy->ref_exec(1)` 驱动 REF 执行
   - 通过子 Checker（LoadChecker, StoreChecker）校验访存事件
4. 在 `check_all()` 中，所有 checker 执行完毕后调用 `proxy->compare(dut)` 进行完整架构状态对比

### 5.4 Golden Memory 参考内存

`goldenmem` 是一个独立于 DUT 和 REF 的参考内存副本，用于校验访存事件：

- 初始化时从 DUT 的 RAM 克隆一份副本（`simMemory->clone_on_demand`）
- `update_goldenmem()`：DPI-C 函数，当 DUT 执行 store 时同步更新 goldenmem
- `read_goldenmem()`：C++ 函数，LoadChecker 从中读取预期值与 DUT 结果对比
- 支持 `pmem_flag` 标记哪些地址被 store 过，用于区分投机状态和确认状态

---

## 6. Plugin 系统设计

DiffTest 采用插件化架构，支持多种可选功能扩展。插件通过编译宏（`#ifdef`）控制启用/禁用。

### 6.1 Runahead 插件

位于 `difftest/src/test/csrc/plugin/runahead/`，实现了 **Speculative Runahead** 技术：

- **核心思想**：在 DUT 遇到长延迟事件（如 Cache Miss、Branch Misprediction）时，Fork 出子进程提前执行后续指令，预取数据并预测分支方向
- **Checkpoint 管理**：维护 `RunaheadCheckpoint` 队列，每个 checkpoint 记录 fork 时的 pid、checkpoint_id 和 PC
- **IPC 通信**：使用 System V Message Queue 进行主进程和子进程之间的通信
- **MemDep Watcher**：内存依赖预测窗口（`MemdepWatchWindow`），追踪访存指令间的依赖关系
- **集成方式**：继承 `Difftest` 类（`Runahead : public Difftest`），重写 `step()` 方法

### 6.2 SimFrontend 插件

位于 `difftest/src/test/csrc/plugin/simfrontend/`，模拟处理器前端行为：

- **FTQ (Fetch Target Queue)**：软件实现的 FTQ 模拟器（`ftq.cpp/h`），管理取指目标队列
- **TraceReader**：从 trace 文件读取指令流，替代真实前端
- **DPI-C 接口**：`SimFrontFetch`、`SimFrontUpdatePtr`、`SimFrontRedirect` 等函数与 RTL 交互
- **用途**：加速仿真，允许前端行为由软件模拟而非 RTL 计算

### 6.3 Constantin 插件

位于 `difftest/src/test/csrc/plugin/constantin/`，运行时常量注入：

- `constantin_static.cpp`：在编译期或运行时设置 RTL 中的常量参数
- 用于动态调整处理器配置参数，无需重新综合

### 6.4 SpikeDasm 插件

位于 `difftest/src/test/csrc/plugin/spikedasm/`，提供 Spike 风格的反汇编输出：

- 在 DiffState commit trace 中打印反汇编的指令信息
- 辅助调试时快速理解指令语义

### 6.5 XSpdb 插件

位于 `difftest/src/test/csrc/plugin/xspdb/`，XiangShan 调试器：

- 提供交互式调试功能
- 可以检查 DiffTest 状态和断点

---

## 7. Checkpoint / Restore 机制

DiffTest 支持多层次的 Checkpoint/Restore 机制，用于加速长周期仿真和 debug。

### 7.1 Snapshot 机制（Verilator/GSIM 层面）

`Emulator` 类通过 `Simulator` 抽象接口管理仿真快照：

```cpp
void Emulator::snapshot_save() {
  // 1. 保存 Verilator/GSIM 模拟器状态
  auto snapshot_write = dut_ptr->snapshot_take();
  // 2. 保存 RAM 内容
  snapshot_write(simMemory->as_ptr(), size);
  // 3. 保存 DiffTest cycleCnt
  snapshot_write(&cycleCnt, sizeof(cycleCnt));
  // 4. 保存 REF 模型状态（寄存器 + CSR）
  snapshot_write(&proxy->state, sizeof(proxy->state));
  // 5. 保存 REF 内存状态
  proxy->mem_init(PMEM_BASE, buf, size, REF_TO_DUT);
  proxy->ref_csrcpy(csr_buf, REF_TO_DUT);
  // 6. 保存 SD Card 偏移量
  snapshot_write(&sdcard_offset, sizeof(sdcard_offset));
}
```

Restore 过程是上述过程的逆操作，使用 `DUT_TO_REF` 方向同步寄存器和内存到 REF 模型。

Snapshot 保存策略：
- 默认每 60 秒保存一次内存 snapshot
- 每 60 次内存 snapshot 持久化一个到文件
- 非正常退出时保存所有 snapshot（用于 debug）
- 支持通过 `--snapshot-path` 加载已有 snapshot 重新开始

### 7.2 GCPT Restore（全局 Checkpoint Restore）

GCPT（Global Checkpoint）是一种 Checkpoint 恢复机制，用于 SPEC 性能测试：

```cpp
if (args.gcpt_restore) {
  overwrite_ram(args.gcpt_restore, args.overwrite_nbytes);
}
```

- `ready-to-run/` 目录中的 `copy_and_run.bin` 等是预构建的 GCPT 镜像
- GCPT 包含系统启动到特定点的完整状态，可快速跳过操作系统启动阶段
- 配合 `--gcpt-restore-bin` 参数使用

### 7.3 lightSSS（Fork-based Checkpoint）

`LightSSS` 是基于 `fork()` 的轻量级 Checkpoint 系统：

```cpp
if (args.enable_fork) {
  lightsss = new LightSSS;
}
// 主循环中
switch (lightsss->do_fork()) {
  case FORK_CHILD: fork_child_init();  // 子进程：开始 dump waveform 和调试
  default: break;
}
```

- 主进程定期 fork 子进程保存 Checkpoint
- 子进程在触发点（abort point）开始 dump waveform 并运行 Debug 模式
- 支持通过 `--fork-interval` 配置 fork 间隔

### 7.4 Replay 机制（精确错误定位）

Replay 是 DiffTest 独有的精确错误定位机制：

**Scala 侧**（`Replay.scala`）：
- `ReplayEndpoint` 维护一个大小为 `replaySize`（默认 1024）的环形缓冲区
- 每个有效周期将完整的 DiffTest 状态写入缓冲区
- 记录 `trace_head`（缓冲区头指针）和 `trace_size`（有效数据量）
- `ReplayControl` Verilog 模块通过 DPI-C 导出 `set_replay_head()` 函数

**C++ 侧**（`difftest.h/cpp`）：
- `replay_snapshot()`：保存当前 DiffState 和 REF 寄存器状态到快照缓冲区
- `do_replay()`：恢复快照状态，设置 `in_replay = true`，通知 Scala 侧从指定 head 开始回放
- 当 `check_all()` 检测到差异但 Replay 可用时，先保存快照，执行检查；若失败则从快照恢复并回放以精确定位首次分歧

```
发现差异
    |
    v
replay_snapshot() -- 保存当前完整状态
    |
    v
正常执行直到下一个 Replay 可用点
    |
    v
do_replay() -- 恢复状态，开始回放
    |
    v
回放过程中逐步前进，精确找到首次分歧的周期
```

### 7.5 Golden Memory Store Log

对于 Replay 场景，`goldenmem` 还支持 Store Log 功能：

```cpp
void goldenmem_store_log_reset();  // 重置 log 指针
void goldenmem_set_store_log(true); // 开始记录
void goldenmem_store_log_restore(); // 逆序恢复所有 store
```

这确保了 Replay 时 goldenmem 能恢复到正确的状态，避免已写入的 store 数据干扰重新检查。

---

## 8. Squash 与 Delta 优化

### 8.1 Squash 状态压缩

Squash 机制将多个周期中同类型的 DiffTest Bundle 合并为一个，减少传输数据量：

**支持 Squash 的 Bundle**：
- `DiffInstrCommit`：合并 nFused 计数，支持多条融合指令的 squash
- `DiffTraceInfo`：合并 trace_size
- `DiffLoadEvent` / `DiffStoreEvent`：通过 Stamp 机制保持顺序

**Squash 触发条件**：
- `supportsSquash()` 返回 false（新数据无法与已有数据合并）
- `supportsSquashBase()` 返回 false（已有数据无法作为合并基础）
- Group tick（同组的其他 squashable 事件触发）
- Global tick（超时 200000 周期或 Replay 信号）

**Stamp 机制**：
- `Stamper` 模块为每个 commit 事件分配一个单调递增的 stamp
- Load 和 Store 事件的 stamp 被设置为对应 commit 的 stamp
- C++ 端的 `LoadSquashChecker` 和 `StoreChecker` 使用 stamp 匹配 Load/Store 与 Commit

### 8.2 Delta 增量传输

Delta 机制（2025 年新增）进一步优化了 Batch 传输的效率：

**核心思想**：对于支持 Delta 的 Bundle（主要是寄存器状态），仅传输与上一次相比发生变化的元素（而非整个 Bundle）。

**DeltaSplitter 工作流程**：
1. 将 Bundle 的所有 data elements 与寄存器副本（`r_elems`）逐位比较
2. 生成 `updates` 掩码标记变化的元素
3. 将变化元素分组（每组 `deltaLimit = 8` 个）
4. 通过 `DiffDeltaElem` 输出仅包含变化元素的 delta 流
5. 生成 `DiffDeltaInfo` 标记一个 Bundle 的所有 delta 传输完成

**DeltaStats（C++ 侧）**：
- `DeltaState` 缓冲区保存每个核心的 delta 元素
- `sync()` 方法在 Batch 步进时将 delta 数据合并到 `DiffTestState`
- `need_pending()` 检查是否有未处理的 delta 数据

**物理寄存器优化**：
- 对于 `DiffPhyRegState`，通过 RAT（Rename Alias Table）过滤
- 仅传输被 Commit 或 RAT 引用的物理寄存器的 delta

---

## 9. Simulation Flow（仿真流程）

### 9.1 Verilator 仿真

XiangShan 主要使用 Verilator 进行功能仿真：

```bash
# 构建 EMU
python3 scripts/xiangshan.py --build --emulator verilator --threads 8

# 运行 Linux 测试
python3 scripts/xiangshan.py --ci linux-hello-opensbi
```

Verilator 仿真特点：
- `Simulator` 基类派生 `VerilatorSim`
- 支持 waveform dump（VCD/FST 格式）
- 支持 snapshot 保存/加载
- 支持 coverage 收集
- 支持 lightSSS fork-based checkpoint

### 9.2 GSIM 仿真

GSIM 是项目自研的轻量级仿真器：

```bash
# 构建 GSIM EMU
python3 scripts/xiangshan.py --build --emulator gsim
```

GSIM 仿真特点：
- `Simulator` 基类派生 `GsimSim`
- 单线程执行，但周期精度与 Verilator 一致
- 不支持 lightSSS fork（`--disable-fork`）
- 适用于 CI 快速验证

### 9.3 VCS 仿真

商业 EDA 工具 Synopsys VCS 用于高精度仿真：

```bash
# 生成 Verilog
python3 scripts/xiangshan.py --vcs-gen --xprop

# 远程构建和运行（在 EDA 服务器上）
ssh eda01 "python3 scripts/xiangshan.py --vcs-build --xprop"
ssh eda01 "python3 scripts/xiangshan.py --ci-vcs coremark-1-iteration"
```

VCS 仿真特点：
- 支持 X-propagation（`--xprop` 参数）
- 运行在专用 EDA 服务器上
- 支持 Palladium GFIFO（非阻塞 DPI-C）
- 支持更大的设计和更复杂的调试

---

## 10. CI/CD Pipeline 概览

XiangShan 使用 GitHub Actions 构建完整的 CI/CD 流水线。

### 10.1 测试分类

| Workflow | 触发条件 | 测试内容 |
|----------|---------|---------|
| `emu.yml` | push/PR to kunminghu-v3 | GSIM 功能测试、SimFrontend 测试、多核测试、VCS 基础测试、Verilog 检查、格式检查 |
| `emu-performance.yml` | push/PR | 单核性能测试（SPEC06） |
| `emu-performance-v2.yml` | push/PR | 性能测试 v2 版本 |
| `emu-performance-v3.yml` | push/PR | 性能测试 v3 版本（GSIM 默认） |
| `nightly.yml` | 每日 23:33 UTC+8 | 每夜完整 SPEC06 回归测试 + 与前次结果 diff |
| `release.yml` | release trigger | 正式发布流程 |

### 10.2 emu.yml 详细 Job 列表

1. **Changes Detection**：检测代码变更，决定是否运行测试
2. **EMU - GSIM**：使用 GSIM 后端运行 Linux 测试
3. **EMU - SimFrontend**：使用 SimFrontend 插件运行 SPEC06 子集（astar, milc, xalancbmk），报告 IPC
4. **EMU - MC**：多核（2-core）功能测试 + SMP Linux 测试
5. **SIMV - Basics**：在 EDA 服务器上使用 VCS 运行 CoreMark 基础测试
6. **Check Docker**：验证 Docker 镜像可构建
7. **Check Verilog**：Verilog 生成检查、XSNoC 接口检查、MinimalConfig 构建和 Linux 测试
8. **Check Submodules**：确保所有子模块已合并到其默认分支
9. **Check Format**：代码格式检查（scalafmt）

### 10.3 Performance Regression

性能回归测试使用 `perf-template.yml` 模板：

- 默认使用 GSIM 后端（更快）
- 默认运行 SPEC06 gcc15 基准测试
- 支持自定义 checkpoint 路径
- 支持在多台服务器上并行执行
- 输出性能分数文件，用于 Nightly diff 比较

### 10.4 Nightly Regression

每夜自动执行：
1. 运行完整 SPEC06 性能回归
2. 与前一次 Nightly 结果进行 diff
3. 生成性能变化报告（IPC 变化百分比）
4. 上传到 GitHub Step Summary

---

## 11. Ready-to-Run 资源

`ready-to-run/` 目录包含预构建的测试镜像和参考模型：

### 11.1 测试程序镜像

| 文件 | 说明 |
|------|------|
| `linux.bin` | Linux 内核镜像 |
| `coremark-2-iteration.bin` | CoreMark 2 次迭代测试 |
| `microbench.bin` | MicroBench 性能测试 |
| `copy_and_run.bin` | GCPT 恢复启动镜像 |
| `flash_recursion_test.bin` | Flash 递归测试 |

### 11.2 参考模型 SO 文件

| 文件 | 说明 |
|------|------|
| `riscv64-nemu-interpreter-so` | NEMU 单核参考模型 |
| `riscv64-nemu-interpreter-debug-so` | NEMU 单核（debug 模式） |
| `riscv64-nemu-interpreter-dual-so` | NEMU 双核参考模型 |
| `riscv64-nemu-interpreter-dual-debug-so` | NEMU 双核（debug 模式） |
| `riscv64-nemu-interpreter-bitmap-so` | NEMU Bitmap 模式 |
| `riscv64-spike-so` | Spike 单核参考模型 |
| `riscv64-nutshell-spike-so` | NutShell 兼容 Spike |

这些 SO 文件在运行时通过 `RefProxy` 的 `dlopen()` 动态加载。

### 11.3 Bump 脚本

| 脚本 | 说明 |
|------|------|
| `bump-nemu.sh` | 更新 NEMU SO 文件 |
| `bump-spike.sh` | 更新 Spike SO 文件 |
| `bump-spike-nutshell.sh` | 更新 NutShell Spike SO |
| `bump_all_from_docker.sh` | 从 Docker 镜像更新所有参考模型 |
| `auto_bump.sh` | 自动化 bump 流程 |

---

## 12. 多核支持

DiffTest 框架原生支持多核仿真：

- 每个核心独立维护 `Difftest` 对象和 `DiffStateBuffer`
- Bundle 通过 `coreid` 字段区分不同核心
- 双核参考模型（`riscv64-nemu-interpreter-dual-so`）通过 `diff` 参数指定
- 多核时 `LoadEvent` 会启用（单核时被 Preprocess 移除）
- 多核 CI 测试使用 `--num-cores 2` 参数

```bash
# 多核测试命令
python3 scripts/xiangshan.py \
  --num-cores 2 \
  --diff ./ready-to-run/riscv64-nemu-interpreter-dual-so \
  --ci linux-hello-smp-new
```

---

## 13. FPGA 支持

DiffTest 也支持 FPGA 综合验证：

- `GatewayConfig` 中 `isFPGA = true` 启用 FPGA 模式
- FPGA 模式下 Batch 传输使用较小的参数（`batchArgByteLen = (1900, 100)`）
- 禁用 batchSplit（减少逻辑门数量）
- 使用 `FpgaDiffIO`（`DecoupledIO`）接口输出 DiffTest 数据
- 通过 `HostEndpoint` 将数据传输到 PCIe 主机端
- 启用时钟门控（`hasClockGate`）节省功耗

---

## 14. 总结

XiangShan 的 DiffTest 验证框架是一套成熟、高效、高度可配置的协同仿真系统。其核心特点包括：

1. **模块化设计**：Scala 侧 Bundle 定义、Gateway 流水线、C++ 侧 Checker 体系，三者通过 DPI-C 紧密协作

2. **高性能传输**：Batch 批量传输将 DPI-C 调用频率降低数十倍；Delta 增量传输进一步减少数据传输量；Squash 状态压缩合并重复事件

3. **精确调试**：Replay 机制支持精确回放定位首次分歧；Store Log + Golden Memory 确保回放正确性

4. **可扩展插件**：Runahead（Speculative 执行）、SimFrontend（模拟前端）、Constantin（动态配置）等插件提供丰富的扩展能力

5. **完整的 CI/CD**：从 Verilator/GSIM/VCS 多后端测试，到 SPEC06 性能回归，到每日 Nightly 报告，覆盖功能验证和性能验证

6. **多核支持**：原生支持双核/多核仿真，包括 SMP Linux 测试

7. **多参考模型**：支持 NEMU、Spike、Linked 三种参考模型，通过统一的 RefProxy 接口切换

DiffTest 框架的持续演进（如 2025 年新增的 Delta 机制）表明该项目在验证效率方面不断追求更高的性能和更好的调试体验。

---

## 附录 A：仿真状态码

| 状态码 | 宏定义 | 含义 |
|--------|--------|------|
| - | `STATE_RUNNING` | 正在运行 |
| 0 | `STATE_GOODTRAP` | 正确执行到 good trap |
| 1 | `STATE_BADTRAP` | 错误 trap |
| 2 | `STATE_ABORT` | DUT 与 REF 出现差异 |
| 3 | `STATE_LIMIT_EXCEEDED` | 超出 cycle/instruction 限制 |
| 4 | `STATE_SIG` | 信号终止 |
| 5 | `STATE_SIM_EXIT` | 仿真正常退出（exit 全 1） |
| 6 | `STATE_FUZZ_COND` | Fuzzing 条件触发 |

## 附录 B：关键编译宏

| 宏 | 说明 |
|----|------|
| `CONFIG_DIFFTEST_BATCH` | 启用 Batch 批量传输 |
| `CONFIG_DIFFTEST_SQUASH` | 启用 Squash 状态压缩 |
| `CONFIG_DIFFTEST_REPLAY` | 启用 Replay 回放 |
| `CONFIG_DIFFTEST_DELTA` | 启用 Delta 增量传输 |
| `CONFIG_DIFFTEST_DPIC` | 使用 DPI-C 传输方式 |
| `CONFIG_DIFFTEST_FPGA` | FPGA 模式 |
| `CONFIG_DIFFTEST_IOTRACE` | I/O Trace dump/load |
| `CONFIG_DIFFTEST_NONBLOCK` | 非阻塞 DPI-C |
| `CONFIG_DIFFTEST_PERFCNT` | DiffTest 性能计数器 |
| `CONFIG_DIFFTEST_QUERY` | DiffTest Query 查询接口 |
| `CONFIG_NO_DIFFTEST` | 禁用 DiffTest（纯 RTL 仿真） |
| `ENABLE_RUNAHEAD` | 启用 Runahead 插件 |
| `PLUGIN_SIMFRONTEND` | 启用 SimFrontend 插件 |
| `ENABLE_CONSTANTIN` | 启用 Constantin 插件 |
| `ENABLE_STORE_LOG` | 启用 Golden Memory Store Log |
| `FUZZING` | Fuzzing 模式（发现歧义状态时报错） |
| `FUZZER_LIB` | 作为 Fuzzer 库编译 |
