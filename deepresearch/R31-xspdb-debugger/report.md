# R31 -- XSPdb: XiangShan 硬件调试器深度分析

## 目录

1. [概述与设计哲学](#1-概述与设计哲学)
2. [系统架构](#2-系统架构)
3. [Breakpoint 类型：硬件信号断点、表达式断点与 FSM 断点](#3-breakpoint-类型)
4. [Watchpoint 机制与信号可见性](#4-watchpoint-机制与信号可见性)
5. [寄存器检查与修改](#5-寄存器检查与修改)
6. [Trigger 表达式引擎与 FSM 触发器](#6-trigger-表达式引擎与-fsm-触发器)
7. [Waveform 波形控制](#7-waveform-波形控制)
8. [DiffTest Snapshot 集成](#8-difftest-snapshot-集成)
9. [Step/Continue 执行控制](#9-stepcontinue-执行控制)
10. [Disassembly 反汇编支持](#10-disassembly-反汇编支持)
11. [CLI Batch 批处理模式](#11-cli-batch-批处理模式)
12. [Data I/O 数据输入输出机制](#12-data-io-数据输入输出机制)
13. [关键源文件位置汇总](#13-关键源文件位置汇总)

---

## 1. 概述与设计哲学

XSPdb（XiangShan PDB Debugger）是北京开源芯片研究院（BOSC）与中国科学院计算技术研究所为香山 RISC-V 处理器开发的专用硬件调试工具。它基于 Python 标准库中的 `pdb`（Python Debugger）构建，通过继承 `pdb.Pdb` 类并大量扩展，形成了一套面向硬件仿真场景的交互式调试环境。

### 1.1 设计哲学

XSPdb 的核心设计哲学可以概括为以下几点：

**软硬件协同调试（Hardware/Software Co-verification）**：XSPdb 不仅仅是一个软件层面的调试器，它深度集成了信号级别的硬件调试能力与软件执行状态。调试器通过 DUT（Design Under Test）接口直接访问 RTL 仿真中的任意内部信号，同时结合 DiffTest 框架获取软件执行视图，实现了信号级调试与软件执行状态的关联分析。

**GDB-like 交互体验**：XSPdb 的命令行界面借鉴了 GDB 的交互范式，所有扩展命令以 `x` 前缀命名（如 `xstep`、`xwatch`、`xbreak`），既保持了与 pdb/GDB 的操作习惯一致性，又避免了与原始命令的命名冲突。

**最小开销原则（Minimal Overhead）**：在调试器未激活的场景下（如波形未开启、无活跃 watchpoint），XSPdb 追求零性能开销。波形关闭时性能影响约 0%，开启时仅约 5-10% 的性能下降。表达式求值引擎在 C++ 层实现，避免了 Python 层面的性能瓶颈。

**可复现性（Reproducibility）**：支持通过脚本（script）和回放（replay）两种方式精确复现调试会话。所有命令在执行时都会被自动记录到日志中，格式为 `@cmd{<命令内容>}`，这些日志可以直接作为回放脚本使用。

**分层架构（Layered Architecture）**：XSPdb 采用清晰的分层设计。底层是 C++ 实现的仿真引擎（pyxscore）和 DiffTest 框架（pydifftest）；中间层是 Python API 层，提供 `api_*` 系列方法；顶层是基于 pdb 的交互式命令行和 TUI（Text User Interface）。

### 1.2 与 pdb 的关系

XSPdb 直接继承自 `pdb.Pdb`，这意味着它天然具备 pdb 的所有标准调试功能：断点管理、单步执行、调用栈查看、变量检查等。在此基础上，XSPdb 通过动态加载机制（`api_load_custom_pdb_cmds`）将 `xspdb.xscmd` 包中的所有 `cmd_*` 模块注册为自定义命令。每个命令模块中的函数会被自动检测并注册为 `do_x<name>` 形式的方法，从而扩展了 pdb 的命令集。

XSPdb 的 prompt 被设置为 `(XiangShan)`，区别于标准 pdb 的 `(Pdb)`，明确标识当前处于香山处理器调试环境。

---

## 2. 系统架构

### 2.1 核心组件

XSPdb 的运行时架构包含以下核心组件：

**DUTSimTop（dut）**：通过 pyxscore 导出的 DUT 仿真顶层对象，提供对仿真器的直接控制，包括信号读写、时钟步进、回调注册等。XSPdb 在初始化时调用 `dut.InitClock("clock")` 建立时钟同步机制。

**DiffTest（df）**：通过 pydifftest 导出的差异测试框架，用于将 RTL 仿真结果与参考模型（如 Spike）进行对比。DiffTest 在指令提交边界进行状态比较，支持内存初始化（`InitRam`、`InitFlash`）和状态导出。

**xspcomm（xsp）**：DUT Python 库导出的通信层，提供回调机制（`ComUseEcho`、`ComUseStepCb`）和数据接口（`XData`、`XSignalCFG`），是连接 Python 调试逻辑与 C++ 仿真引擎的桥梁。

**命令注册系统（xscmd）**：`xspdb.xscmd` 包中包含大量 `cmd_*` 命名的模块，每个模块定义一组相关命令。注册系统通过 `pkgutil.iter_modules` 遍历包路径，自动发现并注册所有命令。

### 2.2 初始化流程

XSPdb 的初始化流程（`__init__` 方法）按以下顺序执行：

1. 调用 `super().__init__()` 初始化 pdb 基类
2. 保存 DUT、DiffTest、xspcomm 对象引用
3. 设置内存基地址（默认 `0x80000000`）和 Flash 基地址（默认 `0x10000000`）
4. 注册 SIGINT 信号处理器，支持 Ctrl+C 中断
5. 初始化 UART echo 回调（`ComUseEcho`），将 DUT 的串口输出转发到终端
6. 如果指定了 bin 文件，加载到仿真内存
7. 初始化 DiffTest 框架（`df.difftest_init`）
8. 加载所有自定义命令（`load_cmds`）
9. 初始化波形控制（`api_init_waveform`）
10. 设置命令日志格式（`@cmd{...}`）

### 2.3 运行模式

XSPdb 支持三种运行模式：

**交互模式（Interactive）**：默认模式，通过 `set_trace()` 进入 pdb 交互式调试。用户可以输入任意命令，包括 `xstep` 推进仿真、`xwatch` 设置监控点等。

**批处理模式（Batch）**：通过 `--batch` 标志激活。在该模式下，XSPdb 按照脚本或命令行参数自动执行，不进入交互式界面。支持脚本执行（`--script`）、回放（`--replay`）、周期计数（`--max-cycles`）和指令提交计数（`--pc-commits`）。

**混合模式（Mixed）**：批处理执行中可通过 `--interact-at` 参数在指定周期自动切换到交互模式。也可以通过 `--cmds` 注入预执行命令，通过 `--cmds-post` 注入后执行命令。

---

## 3. Breakpoint 类型

XSPdb 提供了三个层次的断点机制，从简单的信号比较到复杂的多步状态机条件，覆盖了从基础到高级的全部调试场景。

### 3.1 硬件信号断点（xbreak）

`xbreak` 是最基本的断点类型，基于单个 RTL 信号的值进行条件判断。其底层通过 `xclock` 回调机制实现：每次时钟上升沿时，回调函数检查目标信号的当前值是否满足比较条件。

命令格式：

    xbreak <signal_path> <operator> <value>

示例：

    xbreak SimTop_top.SimTop.timer eq 10000
    xbreak SimTop_top.SimTop.cpu.l_soc.core_with_l2.core.backend.inner_ctrlBlock.rob.difftest_commit_pc == 0x80000000

支持的比较操作符包括 `eq`（等于）、`ne`（不等于）、`gt`（大于）、`lt`（小于）、`ge`（大于等于）、`le`（小于等于）等。

`xbreak` 的关键特性包括：

- **回调机制**：通过 `callback` 参数可以注册自定义触发函数，函数签名 `(self, checker, key, clk, sig, target)` 提供完整的触发上下文。
- **一次性触发**：`callback_once=True` 参数使断点仅触发一次后自动移除。
- **断点管理**：`xbreak_list` 列出所有活跃断点，`xbreak_clear` 清除指定断点，`xbreak_update` 更新断点条件。

### 3.2 表达式断点（xbreak_expr）

`xbreak_expr` 支持基于 Python 风格布尔表达式的复杂条件断点。表达式在 C++ 层编译并在每个时钟周期评估，避免了 Python 层面的性能开销。

命令格式：

    xbreak_expr "<expression>"

示例：

    xbreak_expr "SimTop_top.SimTop.timer == 1000"
    xbreak_expr "sigA == 1 and sigB != 0"
    xbreak_expr "within(SimTop_top.SimTop.timer == 1000, 20)"

表达式引擎支持的特性包括：

- **布尔运算**：`and`/`&&`、`or`/`||`、`not`/`!`
- **位运算**：`&`、`|`、`^`、`~`、`<<`、`>>`
- **算术运算**：`+`、`-`、`*`、`/`、`%`
- **比较运算**：`==`、`!=`、`>`、`>=`、`<`、`<=`（支持链式比较如 `a < b < c`）
- **时间窗口函数**：`within(N, expr)` 和 `hold(N, expr)`
- **整数字面量**：支持十进制、`0x` 十六进制、`0b` 二进制，允许 `_` 分隔符

`within(N, expr)` 在表达式当前为真或过去 N 个周期内曾为真时返回 true。`hold(N, expr)` 在表达式连续 N 个周期为真时返回 true。这两个函数是**有状态的**：它们在求值时更新内部状态，如果位于短路分支的跳过路径上，则该周期不更新状态。

表达式编译在 C++ 层完成，信号通过 `XSignalCFG::NewXData(name)` 解析并缓存，避免重复分配。评估采用显式栈机（迭代方式），而非递归，且支持 `and`/`or` 操作数按代价重排序以优化短路效率（但包含 `within`/`hold` 的子树不重排序以保证语义正确性）。

对于宽度超过 64 位的信号，算术/位运算在 v1 中被禁止，但比较操作允许使用 `XData` 全宽度比较语义。

### 3.3 FSM 断点（xbreak_fsm）

`xbreak_fsm` 是最强大的断点类型，支持多步骤状态机触发条件。用户编写 FSM 程序文件，描述需要检测的复杂信号序列。

命令格式：

    xbreak_fsm <fsm_file>

FSM 程序语法包括：

**状态定义**：使用 `state <NAME>:` 定义状态，支持 `if/elif/else` 分支和 `goto` 跳转。

**动作（Actions）**：在状态激活期间每个周期执行，先于转换条件求值。

- `set $flag_done` / `clear $flag_done`：设置/清除 1-bit 标志
- `inc $counter_timeout` / `reset $counter_timeout`：递增/重置 64-bit 计数器

**转换（Transitions）**：支持 `if <expr> goto <state>`、`elif <expr> trigger`、`else goto <state>`、无条件 `goto <state>`、无条件 `trigger` 等形式。转换按顺序求值，第一个匹配的条件被执行。

**触发（Trigger）**：当 `trigger` 被执行时，禁用所有绑定时钟（与 `xbreak` 行为一致），并记录触发状态。

**示例 - CSR Trace FSM**（`csr_trace.fsm`）：

该 FSM 追踪完整的 CSR 读写操作序列，经历 IDLE -> WRITE -> READ_WRITE 三个状态。在 IDLE 状态等待 CSR 指令（检测 `redirect_valid` 和 `is_write`/`is_read` 信号），在 WRITE 状态等待操作完成或转入 READ_WRITE，在 READ_WRITE 状态等待 `release` 信号后触发断点。

**示例 - PC Sequence FSM**（`pc_sequence.fsm`）：

该 FSM 追踪提交指令的 PC 地址序列，当观测到 `0x10000000` -> `0x10000004` -> `0x80000000` 的序列时触发断点。中间出现的其他 PC 值被忽略，但如果在序列中间再次观测到起始 PC（`0x10000000`），序列会重新开始。

FSM 引擎（`ComUseFsmTrigger`）在 C++ 层实现，继承自 `ComUseStepCb` 并注册到 `StepRis`。每个周期执行：更新引擎周期 -> 运行当前状态动作 -> 评估转换条件 -> 如触发则禁用时钟。

---

## 4. Watchpoint 机制与信号可见性

### 4.1 信号 Watchpoint

Watchpoint 用于监控仿真过程中任意 RTL 信号的值变化。与断点不同，watchpoint 不会暂停仿真，而是在检测到值变化时自动报告。

命令格式：

    xwatch <signal_path>         # 设置 watchpoint
    xunwatch <signal_path>       # 移除 watchpoint
    xwatch_commit_pc             # 监控 commit PC 变化
    xunwatch_commit_pc           # 停止监控 commit PC

Watchpoint 的关键特性：

- **实时监控**：每个仿真步（simulation step）都会检查所有活跃 watchpoint。
- **自动报告**：值变化时自动输出变化信息。
- **持久存在**：watchpoint 保持活跃直到显式移除。
- **层次路径**：支持使用完整路径名称监控嵌套信号层次。
- **性能影响**：活跃 watchpoint 增加少量开销，移除后开销完全消除。

### 4.2 Commit PC 监控

`xwatch_commit_pc` 专门用于监控指令提交边界的 PC 变化，是追踪程序执行流程的便捷工具。该机制利用硬件 commit 信号进行优化，比通用信号 watchpoint 更高效。

### 4.3 信号检查与修改

XSPdb 提供了基础的信号检查和修改命令：

    xprint <signal_path>          # 读取并显示信号值
    xset <signal_path> <value>    # 动态修改信号值
    xpc                           # 获取当前 PC（软件同步视图）

`xprint` 和 `xset` 支持对 DUT 内部任意信号的即时访问。`xpc` 提供软件同步的 PC 视图，确保反映准确的仿真状态。信号值修改（`xset`）立即生效，可用于故障注入和条件测试。

### 4.4 DUT 信号树

XSPdb 维护一个 DUT 信号前缀树（`dut_tree`），通过 `get_dut_tree()` 方法获取。该树在首次使用时构建，使用 `build_prefix_tree` 从 DUT 的完整内部信号列表（`GetInternalSignalList()`）构建。前缀树用于支持信号名称的自动补全和模糊匹配，极大提升了交互式调试的效率。

---

## 5. 寄存器检查与修改

### 5.1 寄存器分类

XSPdb 支持对 RISC-V 处理器所有寄存器类型的全面操作：

- **整数寄存器（x0-x31）**：通用整数运算寄存器
- **浮点寄存器（f0-f31）**：浮点运算寄存器，支持 IEEE 754 格式
- **控制寄存器**：包括 MPC（程序计数器）等特殊用途寄存器
- **Flash 寄存器**：仿真启动时的寄存器初始化值

### 5.2 操作命令

**单寄存器操作**：

    xset_ireg x1 0x1234          # 设置整数寄存器 x1
    xset_freg f0 0x3f800000       # 设置浮点寄存器 f0
    xset_mpc 0x80000000           # 设置程序计数器
    xget_mpc                      # 获取当前 MPC 值

**批量寄存器操作**：

    xset_iregs <file>             # 从文件批量设置整数寄存器
    xset_fregs <file>             # 从文件批量设置浮点寄存器

**寄存器文件操作**：

    xparse_reg_file <file>        # 解析并验证寄存器文件
    xload_reg_file <file>         # 加载寄存器值

**Flash 寄存器查看**：

    xlist_flash_iregs             # 列出 Flash 整数寄存器
    xlist_flash_fregs             # 列出 Flash 浮点寄存器
    xlist_freg_map                # 列出浮点寄存器映射

### 5.3 寄存器文件格式

寄存器文件采用文本格式，支持十进制和十六进制数值，允许注释和空行以提高可读性。值的验证在应用前完成，确保范围和类型正确。Flash 寄存器在 DUT 复位期间应用，MPC 的更改在下一条指令取指时生效。

### 5.4 可重现性支持

寄存器的文件化配置是确保调试会话可重现的关键机制。通过版本控制寄存器文件，可以在不同环境中精确复现相同的初始状态。批量操作允许高效的多寄存器配置。

---

## 6. Trigger 表达式引擎与 FSM 触发器

### 6.1 表达式引擎架构

Trigger 表达式引擎是 XSPdb 中性能关键的组件，设计目标是在每个时钟周期都能高效评估复杂条件。引擎完全在 C++ 层实现，Python 层仅负责转发表达式字符串和 DUT 的 `XSignalCFG` 实例。

**数据结构**：表达式被编译为节点树，存储在扁平向量中。每个节点（`ExprNode`）包含操作符（`ExprOp` 枚举）、左右子节点索引、信号指针（`XData*`）、立即数值、信号宽度，以及用于 `within`/`hold` 的状态字段（`last_true_cycle`、`last_hold_cycle`、`hold_count`）。

**ExprEngine API** 提供以下核心方法：

- `NewConst(uint64_t v)`：创建常量节点
- `NewSignal(XData* sig)`：创建信号节点
- `NewUnary/NewBinary/NewCompare`：创建运算节点
- `NewCompareSigSig/NewCompareSigConst/NewCompareConstSig`：针对不同操作数类型的优化比较节点
- `NewWithin/NewHold`：创建时间窗口节点
- `Eval(int root)`：评估表达式树，返回 `uint64_t`

**评估流程**：`ComUseExprCheck` 在每个时钟上升沿（`StepRis`）被调用。首先清除上一周期的触发标志，然后按注册顺序评估表达式。第一个求值为 true 的表达式会触发时钟禁用（`clk->Disable()`）并停止后续评估。评估前设置引擎的当前周期为回调周期，使 `within`/`hold` 能以周期为单位度量时间窗口。

**运算符优先级**（从高到低）：一元运算（`~`、`-`、`+`）-> 乘除模（`* / %`）-> 加减（`+ -`）-> 移位（`<< >>`）-> 位与异或或（`& ^ |`）-> 比较（`== != > >= < <=`）-> 逻辑非（`not`/`!`）-> 逻辑与（`and`/`&&`）-> 逻辑或（`or`/`||`）。

**宽度规则**：v1 版本中，超过 64 位的信号不能用于算术/位运算（编译时报错 `wide arithmetic/bitwise/shift is not supported in v1`），但可以用于比较操作。比较时使用 `XData` 全宽度语义，要求两个操作数具有相同宽度。常量零扩展到信号宽度，负值使用二进制补码截断。X/Z 值在算术/位运算中不传播，但在比较中使用 XData 语义（X/Z 可能使相等性判断为 false）。

**布尔短路与优化**：`and`/`or` 支持短路求值。引擎可能按估算代价重排 `and`/`or` 操作数以优化短路效率，但包含有状态节点（`within`/`hold`）的子树不重排序，以保留其更新语义。

### 6.2 FSM 触发器设计

FSM 触发器引擎（`ComUseFsmTrigger`）同样在 C++ 层实现，继承自 `ComUseStepCb` 并注册到 `StepRis`。它拥有自己的 `ExprEngine` 实例用于编译转换条件，以及状态表（`vector<FsmState>`）、标志/计数器存储和用于表达式访问的 `XData` 镜像。

**执行语义**：

1. 动作（Actions）首先执行：更新标志和计数器。`set`/`clear` 操作 1-bit 标志，`inc`/`reset` 操作 64-bit 计数器。值立即更新并在同一周期的条件中可见。
2. 转换（Transitions）按顺序求值：第一个匹配的条件执行对应的跳转或触发。
3. 如果没有转换匹配且无 `else` 分支，FSM 保持当前状态。
4. `trigger` 立即禁用所有绑定时钟并标记触发状态。

**状态管理**：FSM 保持单一活跃状态。标志和计数器在程序加载时和 `xbreak_fsm_clear` 时重置为 0。`within`/`hold` 函数的状态随 FSM 节点独立维护。

**诊断功能**：解析错误报告行号和原因；未知状态目标产生编译错误；对计数器使用 `set/clear` 或对标志使用 `inc/reset` 会被拒绝。

**用户命令**：

- `xbreak_fsm <fsm_file>`：加载并激活 FSM 程序
- `xbreak_fsm_status`：显示当前状态和触发状态
- `xbreak_fsm_clear`：移除 FSM 触发器及其回调

### 6.3 实际应用模式

**指令提交断点**：监控特定 PC 或指令编码的提交，用于追踪程序执行流程。例如监控 `wfi` 指令（编码 `0x10500073`）的提交。

**异常捕获**：监控异常信号（`io_exception_valid_REG`），结合 `isInterrupt` 信号区分中断和异常类型。

**缓存调试**：监控 D-Cache/I-Cache 的 miss 信号和特定地址访问，用于性能分析和内存一致性调试。例如，检测 D-Cache 对特定虚拟地址 `0x80001000` 的 miss 事件。

---

## 7. Waveform 波形控制

### 7.1 波形状态管理

XSPdb 管理三种波形状态：

- **Off**：波形转储禁用，零性能开销。
- **On**：波形转储激活，记录信号变化，约 5-10% 性能开销。
- **Paused**：转储暂停但文件句柄保持打开，无开销。

波形默认使用 `.fst`（Fast Signal Trace）格式，支持信号层次和值变化，兼容 GTKWave 等标准波形查看器，并自动压缩以减少磁盘使用。

### 7.2 操作命令

    xwave_on                       # 使用默认文件恢复波形录制
    xwave_on <file>                # 切换到指定文件并恢复录制
    xwave_off                      # 暂停转储（不删除文件）
    xwave_flush                    # 强制将待写数据刷新到磁盘
    xwave_continue <src>           # 复制源文件到默认文件并恢复录制

### 7.3 文件管理

- **默认文件**：在初始化时自动生成（带时间戳），文件名在日志中打印。
- **自定义文件**：用户可通过 `xwave_on <file>` 指定自定义波形文件路径。
- **文件续接**：`xwave_continue` 将源文件复制到当前默认文件并恢复录制，保留信号层次和时序。
- **仅保留最近 2 个文件**：默认在工作目录中，自动管理文件数量。

### 7.4 批处理模式波形控制

在批处理模式下，波形可以通过命令行参数精确控制：

- `-b` / `--wave-begin`：指定开始录制的周期（`<=0` 表示从零周期开始）
- `-e` / `--wave-end`：指定停止录制的周期（`<=0` 表示在执行结束时停止）
- `--wave-path`：指定波形文件输出路径

系统使用回调机制在运行时动态启停波形。例如，通过 `xbreak` 在目标周期注册回调，自动调用 `api_waveform_on` 和 `api_waveform_off`。波形回调使用 `SimTop_top.SimTop.timer` 信号作为周期计数器进行精确控制。

---

## 8. DiffTest Snapshot 集成

### 8.1 DiffTest 框架集成

XSPdb 深度集成了 DiffTest 差异测试框架，用于将 RTL 仿真结果与参考模型（如 Spike）进行对比。集成在以下层面工作：

**初始化配置**：

- `--diff <path>`：指定参考模型共享对象（.so）的路径
- `--diff-first-inst-address <addr>`：设置 DiffTest 的第一条指令地址
- `api_load_ref_so(path)`：加载参考模型共享库
- `api_set_difftest_diff(True)`：启用差异测试比较

**状态管理**：

- `api_update_pmem_base_and_first_inst_addr(a, b)`：设置 PMEM_BASE 和 FIRST_INST_ADDRESS
- `df.InitRam(file, size)`：初始化 RAM 内容
- `df.InitFlash("")`：初始化 Flash 内容
- `df.difftest_init(False, mem_size)`：初始化 DiffTest 框架

**状态导出**：

- `xexpdiffstate`：导出当前 DiffTest 状态为 JSON 格式
- `xexportself`：导出 DUT 自身状态

### 8.2 Snapshot 快照机制

Snapshot 功能由仿真器层提供（需要在构建时启用 snapshot 支持）。XSPdb 通过以下方式集成：

**保存/恢复**：仿真器支持基于 checkpoint 的快照保存和恢复，可以快速回退到之前的仿真状态，无需从头重新运行。

**工作流程**：

1. **调试阶段**：使用 DiffTest 识别分歧点
2. **快照阶段**：在关键区域前保存仿真器状态
3. **分析阶段**：恢复快照并探索不同场景
4. **验证阶段**：从保存的状态重新运行测试

**配置参数**：

- `--fork-interval <N>`：在 emu.py 中设置快照 fork 间隔
- `--pc-commits <N>`：运行直到指定数量的指令提交

### 8.3 Fork Backup 波形

Fork Backup 是一种高效的波形捕获机制，通过维护一个"滞后"的子进程，在 xbreak 触发时自动转储 bug 周围的波形窗口，而不会在正常执行期间引入大量开销。

工作原理：子进程在后台休眠，直到父进程的 xbreak 触发唤醒。子进程启用波形录制，运行到相同的断点位置，刷新并退出。日志分别写入 `fork_backup.log` 和 `fork_backup_parent.log`，波形仅保留最近 2 个文件。

命令：

    xfork_backup_on <window_seconds> <output_dir> <log_file>
    xfork_backup_off
    xfork_backup_status

注意：Fork Backup 仅在 `xstep` 路径（交互/TUI 模式）下工作，不支持 `emu.py` 批处理循环。

---

## 9. Step/Continue 执行控制

### 9.1 周期步进（xstep）

`xstep` 是 XSPdb 中最基本的时间推进命令，推进指定数量的仿真周期。每次步进结束时，系统检查所有活跃的断点、触发器和 fork backup 回调。

命令格式：

    xstep <cycles>

例如：`xstep 1000` 推进 1000 个时钟周期。

底层实现调用 `api_step_dut(delta)` 推进 DUT 时钟，该方法返回实际推进的周期数。在批量执行循环中，`xstep` 以 10000 个周期为批次执行，支持最大时间限制（`--max-run-time`）和最大周期限制（`--max-cycles`）。

### 9.2 指令步进（xistep）

`xistep` 推进一个 ISA 级别的指令步进，当仿真器支持时使用。底层调用 `api_xistep(delta)`，该方法返回实际提交的指令数。

命令格式：

    xistep <count>

例如：`xistep 1` 执行一条指令的步进。

### 9.3 Continue 执行

在交互模式下，`continue`（或 `c`）命令恢复仿真执行，直到遇到下一个断点或触发器。XSPdb 的 `run` 方法在非批处理模式下使用一个简单的循环：`set_trace()` 后进入 pdb 交互，用户输入 `continue` 时执行 `dut.Step(1000)` 直到下次中断。

### 9.4 批量提交执行（run_commits）

`run_commits` 函数实现了基于指令提交计数的执行控制，是 `--pc-commits` 参数的底层实现。它以 100 条指令为批次推进仿真，每批次内部调用 `api_xistep` 并在每次步进后调用 `check_is_need_trace()` 检查是否需要中断。支持最大运行时间限制，超时后自动退出指令执行。

### 9.5 中断机制

XSPdb 实现了多层中断机制：

**SIGINT 处理**：通过 `_sigint_handler` 处理 Ctrl+C 信号。在非交互模式（`no_interact=True`）下直接退出；在交互模式下设置 `interrupt` 标志并累计中断计数，连续 3 次中断后强制进入 pdb。

**快速追踪中断**：`check_is_need_trace` 检查 `__xspdb_need_fast_trace__` 标志，该标志可由断点回调设置，用于在批量执行中强制进入交互模式。

**断点中断**：当 `xbreak`、`xbreak_expr` 或 `xbreak_fsm` 触发时，通过 `clk->Disable()` 禁用时钟，导致仿真步进返回 0 周期，从而中断执行流。

---

## 10. Disassembly 反汇编支持

### 10.1 双引擎架构

XSPdb 支持两个反汇编后端，采用自动降级（fallback）机制：

1. **主引擎：spike-dasm**：提供精确的 RISC-V 反汇编，支持符号信息。优先使用。
2. **备用引擎：capstone**：ISA 覆盖有限，当 spike-dasm 不可用时自动使用。

### 10.2 操作命令

**内存区域反汇编**：

    xdasm <address> <length>            # 反汇编主内存区域（PMEM_BASE 起始）
    xdasmflash <address> <length>       # 反汇编 Flash 区域（FLASH_BASE 起始）

**原始数据反汇编**：

    xdasmbytes <byte1> <byte2> ...      # 反汇编任意字节序列
    xdasmnumber <instruction_word>      # 反汇编单条指令字

**缓存与符号管理**：

    xclear_dasm_cache                   # 清除反汇编缓存
    xload_elf <elf_file>                # 加载 ELF 文件用于符号解析

### 10.3 缓存机制

反汇编结果使用缓存机制提升性能。缓存键包含地址和长度，确保正确性。缓存可通过 `xclear_dasm_cache` 手动清除以强制重新反汇编（当内存内容变化时使用）。缓存在整个会话中维护，除非手动清除，否则在重启时失效。

### 10.4 符号支持

当 ELF 文件通过 `xload_elf` 加载时，反汇编输出包含符号名称和偏移信息，将原始地址转换为函数名，极大提高了可读性。输出格式为：地址、指令字节、汇编助记符，针对终端可读性进行了优化。

---

## 11. CLI Batch 批处理模式

### 11.1 执行模式

XSPdb 的 CLI 支持三种执行模式：

**交互模式**：手动命令输入，具备完整的调试能力。通过 `set_trace()` 进入 pdb 交互环境。使用 `xcmds` 和 `xapis` 可列出所有可用命令和 API。

**批处理模式**：自动化执行，通过脚本或回放文件驱动。不进入交互式界面，按预定义流程运行。

**混合模式**：在批处理执行中结合交互式断点。通过 `--interact-at` 在指定周期自动切换到交互模式。

### 11.2 脚本与回放

**脚本模式**（`--script`）：

    python3 scripts/pdb-run.py --script /path/to/script.txt

脚本文件包含一系列 XSPdb 命令，每行一条。执行时按顺序逐条运行，支持命令间的延时控制（`--batch-interval`，默认 0.1 秒）。

**回放模式**（`--replay`）：

    python3 scripts/pdb-run.py --replay /path/to/log.txt

回放日志是 XSPdb 运行时自动记录的命令日志（格式 `@cmd{<命令>}`）。回放模式仅执行日志中的命令，忽略其他输出。

### 11.3 命令注入

XSPdb 支持在脚本/回放执行前后注入额外命令：

- `--cmds <commands>`：在脚本/回放执行**之前**运行，命令用 `\n` 分隔
- `--cmds-post <commands>`：在脚本/回放执行**之后**运行

示例：

    --cmds "xbreak SimTop_top.SimTop.timer eq 1000\nxwave_on"
    --cmds-post "xexpdiffstate\nxwave_off"

### 11.4 批处理执行引擎

批处理执行引擎（`__run_batch`）的处理流程：

1. **波形控制**：根据 `--wave-begin` 和 `--wave-end` 设置波形启停回调
2. **交互点**：根据 `--interact-at` 设置交互模式切换回调
3. **脚本/回放**：执行指定的脚本或回放文件
4. **DiffTest**：加载参考模型并启用比较
5. **命令注入**：执行预注入和后注入命令
6. **执行控制**：如果指定了 `--pc-commits`，使用 `run_commits` 按指令数执行；否则使用主循环按周期数执行（批次大小 10000 周期）

### 11.5 批处理命令队列

XSPdb 维护一个命令队列（`batch_cmds_to_exec`），支持嵌套批处理执行。`_exec_batch_cmds` 方法逐个弹出并执行命令，支持可选的延迟（`gap_time`）、回调函数（`callback`）和中断处理器（`break_handler`）。嵌套深度通过 `batch_depth` 跟踪，确保异常时正确清理。

`do_xcontinue_batch` 命令允许在交互模式下手动触发继续执行已加载的批处理命令。

### 11.6 CLI 参数完整列表

| 参数 | 说明 |
|-----|------|
| `-i` / `--image` | 要加载和运行的镜像文件 |
| `-v` / `--version` | 显示版本信息 |
| `-l` / `--log` | 启用日志输出 |
| `--log-file` | 日志文件名 |
| `--log-level` / `--debug-level` | 日志/调试级别（debug/info/warn/erro） |
| `--batch` | 启用批处理模式 |
| `-c` / `--max-cycles` | 最大仿真周期数 |
| `-t` / `--interact-at` | 在指定周期进入交互模式 |
| `-s` / `--script` | 要执行的脚本文件 |
| `-bi` / `--batch-interval` | 批命令间的时间间隔（秒） |
| `-r` / `--replay` | 回放日志文件 |
| `-b` / `--wave-begin` | 开始波形转储的周期 |
| `-e` / `--wave-end` | 停止波形转储的周期 |
| `--wave-path` | 波形文件输出路径 |
| `--max-run-time` | 最大运行时间（支持 s/m/h 后缀） |
| `-pc` / `--pc-commits` | 运行直到指定提交数（-1 无限制） |
| `--sim-args` | 额外仿真器参数（逗号分隔） |
| `-F` / `--flash` | Flash 二进制文件 |
| `--no-interact` | 禁用交互模式 |
| `--ram-size` | RAM 大小（支持 GB/MB/KB） |
| `--diff` | DiffTest REF 共享对象路径 |
| `--cmds` / `--cmds-post` | 预注入/后注入命令 |
| `--mem-base-address` | 内存基地址 |
| `--flash-base-address` | Flash 基地址 |
| `--diff-first-inst-address` | DiffTest 首条指令地址 |
| `--trace-pc-symbol-block-change` | 启用 PC 符号块变化追踪 |

### 11.7 性能开销分析

- 波形关闭：约 0% 额外开销
- 波形开启：约 5-10% 开销
- DiffTest 启用：约 10-20% 开销
- 完整调试模式：约 15-25% 开销

优化策略包括：仅在关键区域开启波形、分组相似操作、使用适当的日志级别、减少 watchpoint 数量。

---

## 12. Data I/O 数据输入输出机制

### 12.1 内存加载

XSPdb 支持多种方式将数据加载到仿真内存：

**二进制文件加载**：

    xload <binary_file>                # 加载二进制到主内存
    xflash <flash_file>                # 加载二进制到 Flash
    xreset_flash                       # 重置 Flash 到初始状态

底层调用 `df.InitRam(file, size)` 和 `api_dut_flash_load(file)`，支持自动地址对齐。

**指令列表加载**：

    xparse_instr_file <instr_file>     # 解析指令文件
    xload_instr_file <instr_file>      # 加载指令到内存

指令文件支持注释和空行，解析时可自动插入 NOP 以保证对齐。

**直接内存写入**：

    xmem_write <address> <size> <value>    # 向指定地址写入值
    xnop_insert <address> <count>          # 在指定地址插入 NOP

示例：`xmem_write 0x80000000 4 0xdeadbeef` 向地址 `0x80000000` 写入 4 字节。

### 12.2 内存导出

**区域导出**：

    xexport_bin <output_file>              # 导出主内存为二进制
    xexport_flash <output_file>            # 导出 Flash 为二进制
    xexport_ram <output_file>              # 导出 RAM 为二进制
    xexport_unified_bin <output_file>      # 导出统一内存镜像

导出操作保留精确的内存状态，包括未初始化区域。统一导出将多个区域合并到单个文件。

### 12.3 数据格式转换

XSPdb 提供了一组工具函数用于数据格式转换：

    xbytes_to_bin <byte1> <byte2> ...      # 字节序列转二进制
    xbytes2number <byte1> <byte2> ...      # 字节序列转数值
    xnumber2bytes <number> <byte_count>    # 数值转字节序列

字节转换自动处理字节序（endianness）和对齐问题。

### 12.4 内存区域管理

XSPdb 管理三个主要内存区域：

- **主内存（Main Memory）**：从 PMEM_BASE（默认 `0x80000000`）开始，默认大小 1GB
- **Flash 内存（Flash Memory）**：从 FLASH_BASE（默认 `0x10000000`）开始，默认大小 256MB
- **特殊区域**：内存映射 I/O 和控制寄存器

内存大小可通过 `--ram-size` 参数在运行时配置（支持 GB/MB/KB 后缀），基地址通过 `--mem-base-address` 和 `--flash-base-address` 配置。

### 12.5 UART Echo

XSPdb 在初始化时设置 UART echo 回调（`ComUseEcho`），将 DUT 串口输出（`difftest_uart_out_valid` 和 `difftest_uart_out_ch`）实时转发到调试终端。该回调通过 `StepRis` 注册，在每个时钟上升沿检查串口输出并回显字符，实现了仿真程序的实时终端输出。

---

## 13. 关键源文件位置汇总

### 13.1 核心源文件

| 文件路径 | 说明 |
|---------|------|
| `scripts/xspdb/xspdb.py` | XSPdb 主实现，包含 `XSPdb` 类（继承自 `pdb.Pdb`）、初始化逻辑、批处理引擎、UART echo、TUI 集成 |
| `scripts/xspdb/cli_parser.py` | CLI 参数解析器，定义所有命令行选项 |
| `scripts/pdb-run.py` | 主入口脚本，启动 XSPdb 调试会话 |

### 13.2 文档目录

| 文件路径 | 说明 |
|---------|------|
| `docs/XSPdb/README.md` | XSPdb 总览文档，包含快速入门和功能概要 |
| `docs/XSPdb/design/cli.md` | CLI 和会话控制设计文档 |
| `docs/XSPdb/design/step.md` | 步进执行设计文档 |
| `docs/XSPdb/design/breakpoints.md` | 断点和触发器设计文档 |
| `docs/XSPdb/design/trigger_expr_spec.md` | Trigger 表达式引擎规范（v1），包含完整的语法、语义和 C++ 引擎设计 |
| `docs/XSPdb/design/trigger_fsm_spec.md` | FSM 触发器规范（v1），包含 FSM 程序语法和 C++ 引擎设计 |
| `docs/XSPdb/design/waveform.md` | 波形控制设计文档 |
| `docs/XSPdb/design/fork_backup.md` | Fork Backup 波形设计文档 |
| `docs/XSPdb/design/watchpoints.md` | Watchpoint 和信号可见性设计文档 |
| `docs/XSPdb/design/disasm.md` | 反汇编设计文档 |
| `docs/XSPdb/design/registers.md` | 寄存器初始化和 Flash 工具设计文档 |
| `docs/XSPdb/design/data_io.md` | 数据加载/导出和内存工具设计文档 |
| `docs/XSPdb/design/difftest_snapshot.md` | DiffTest 和 Snapshot 设计文档 |
| `docs/XSPdb/design/cli_batch.md` | CLI 批处理执行设计文档 |

### 13.3 示例文件

| 文件路径 | 说明 |
|---------|------|
| `docs/XSPdb/examples/breakpoints.md` | 断点和触发器使用示例 |
| `docs/XSPdb/examples/watchpoints.md` | Watchpoint 和信号可见性示例 |
| `docs/XSPdb/examples/registers.md` | 寄存器操作示例 |
| `docs/XSPdb/examples/data_io.md` | 数据加载/导出示例 |
| `docs/XSPdb/examples/step.md` | 步进执行示例 |
| `docs/XSPdb/examples/waveform.md` | 波形控制示例 |
| `docs/XSPdb/examples/difftest_snapshot.md` | DiffTest 和 Snapshot 示例 |
| `docs/XSPdb/examples/disasm.md` | 反汇编示例 |
| `docs/XSPdb/examples/cli.md` | CLI 命令使用示例 |
| `docs/XSPdb/examples/fork_backup.md` | Fork Backup 使用示例 |
| `docs/XSPdb/examples/xbreak_expr_examples.md` | xbreak_expr 表达式示例集 |
| `docs/XSPdb/examples/xspdb_script_example.txt` | 完整脚本示例 |
| `docs/XSPdb/examples/pc_sequence.fsm` | PC 序列追踪 FSM 程序示例 |
| `docs/XSPdb/examples/csr_trace.fsm` | CSR 读写追踪 FSM 程序示例 |

### 13.4 命令模块目录

| 路径模式 | 说明 |
|---------|------|
| `scripts/xspdb/xscmd/` | 命令模块包目录 |
| `scripts/xspdb/xscmd/cmd_*.py` | 各功能模块的命令实现（自动注册为 `do_x*` 方法） |
| `scripts/xspdb/xscmd/util.py` | 工具函数：日志、命令注册、前缀树构建等 |
| `scripts/xspdb/ui.py` | TUI（Text User Interface）实现 |

---

## 总结

XSPdb 是一个功能全面、架构清晰的硬件调试工具，它将软件调试器的易用性与硬件仿真的深度访问能力相结合。通过三层断点机制（信号断点、表达式断点、FSM 断点）、实时信号监控（watchpoint）、全面的寄存器操作、灵活的波形控制、DiffTest 差异测试集成、多种执行控制方式、双引擎反汇编、以及完善的批处理支持，XSPdb 为香山 RISC-V 处理器的开发和验证提供了从快速调试到自动化测试的完整工具链。其基于 C++ 的高性能表达式引擎和 FSM 触发器确保了调试开销最小化，而基于 pdb 的命令行界面则保证了开发者熟悉的交互体验。
