# R31A - Breakpoint & Trigger System Deep Dive

## 1. 概述 (Overview)

XiangShan 的 XSPdb 调试器提供了一套完整且多层次的断点与触发系统，用于在 RTL 仿真过程中对硬件信号、表达式以及多步骤状态机条件进行精确的监控与暂停。该系统包含三个核心机制：

- **`xbreak`**：基于单个 RTL 信号的值比较断点，支持 callback 回调
- **`xbreak_expr`**：基于布尔表达式的断点，由 C++ ExprEngine 在每个时钟沿求值
- **`xbreak_fsm`**：多步骤有限状态机（FSM）触发器，用于检测复杂的信号序列模式

三者均通过 `pyxscore`（XSP C++ 绑定）的 `StepRis` 回调机制注册到仿真时钟沿事件上。当任一触发条件满足时，仿真时钟被 disable（`clk->Disable()`），仿真暂停，用户进入交互调试状态。

设计文档位置：
- `docs/XSPdb/design/breakpoints.md`
- `docs/XSPdb/design/trigger_expr_spec.md`
- `docs/XSPdb/design/trigger_fsm_spec.md`
- `docs/XSPdb/design/watchpoints.md`

---

## 2. xbreak — 信号级断点 (Signal-Level Breakpoints)

### 2.1 核心原理

`xbreak` 是最基础的断点形式，它对单个 RTL 内部信号进行数值比较。底层使用 `pyxscore.ComUseCondCheck` 类，该类继承自 `ComUseStepCb`，在每个时钟上升沿（StepRis）被调用，检查所有注册的条件。

Python 层的入口在 `scripts/xspdb/xscmd/cmd_break.py` 的 `CmdBreak` 类中，其核心 API 为：

```python
def api_xbreak(self, signal_name, condition, value, callback=None, callback_once=False):
```

该方法执行以下步骤：
1. 首次调用时创建 `ComUseCondCheck` 实例，并通过 `StepRis` 注册到时钟回调链中
2. 通过 `dut.GetInternalSignal(signal_name)` 查找信号
3. 根据 value 参数类型创建 `XData` 比较值或信号引用
4. 调用 `checker.SetCondition(xbreak_key, sig, val, cmp)` 注册条件

断点的 key 格式为 `xbreak-<signal_name>-<condition>-<value>`，用于唯一标识和去重。

### 2.2 条件类型 (Condition Types)

支持的比较操作符（同时支持关键字和符号两种写法）：

| 关键字 | 符号 | 底层枚举 | 含义 |
|--------|------|----------|------|
| `eq` | `==` | `ComUseCondCmp_EQ` | 等于 |
| `ne` | `!=` | `ComUseCondCmp_NE` | 不等于 |
| `gt` | `>` | `ComUseCondCmp_GT` | 大于 |
| `lt` | `<` | `ComUseCondCmp_LT` | 小于 |
| `ge` | `>=` | `ComUseCondCmp_GE` | 大于等于 |
| `le` | `<=` | `ComUseCondCmp_LE` | 小于等于 |
| `ch` | — | `ComUseCondCmp_NE` | 变化检测（change detection） |

其中 `ch`（change）条件比较特殊：它在比较时使用当前信号值与上一次保存值进行不等比较，实质上是一个"信号值是否发生变化"的检测器。该条件需要配合 `xbreak_update` 命令手动更新参考值（调用 `v["val"].value = v["sig"].value` 将信号当前值保存为目标值）。

### 2.3 比较值类型

比较的目标值（`value` 参数）支持两种类型：

1. **整数值**：直接的数值常量，会创建一个 `XData` 对象（`self.xsp.XData(sig.W(), self.xsp.XData.InOut)`）并设置对应宽度的值
2. **信号名字符串**：动态地将另一个信号的值作为比较目标（`self.dut.GetInternalSignal(value)`），实现信号与信号的实时比较

### 2.4 信号查找

信号名通过 `self.dut.GetInternalSignal(signal_name)` 在 DUT 信号树中查找。DUT 信号树在首次使用时通过 `build_prefix_tree(self.dut.GetInternalSignalList())` 构建为前缀树，支持高效的路径查找和 Tab 补全。

### 2.5 Callback 回调机制

`xbreak` 支持在触发时执行用户自定义的 Python 回调函数。回调签名如下：

```python
def cb(self, checker, k, clk, sig_value, target_value):
    """
    self:          XSPdb 实例
    checker:       ComUseCondCheck 对象
    k:             断点 key (str)
    clk:           当前时钟周期 (int)
    sig_value:     信号当前值
    target_value:  比较目标值
    """
```

`callback_once=True` 时，回调执行后该断点会自动移除（`checker.RemoveCondition(k)`）。回调的执行在 `call_break_callbacks()` 方法中，该方法在 `api_step_dut` 检测到时钟被 disable 后被调用。

回调执行流程：
1. 从 `checker.ListCondition()` 获取所有已触发的条件
2. 遍历带有 callback 的断点，调用匹配的回调
3. 对 `callback_once=True` 的断点自动移除
4. 如果所有条件都已清除，则完全移除 checker

### 2.6 典型使用场景

在 XSPdb 的 batch 模式（`__run_batch`）中，`xbreak` 被大量用于实现波形控制和交互中断：

```python
# 在指定周期开启波形录制
self.api_xbreak("SimTop_top.SimTop.timer", "eq", args.wave_begin,
                callback=cb_on_wave_begin, callback_once=True)

# 在指定周期关闭波形录制
self.api_xbreak("SimTop_top.SimTop.timer", "eq", args.wave_end,
                callback=cb_on_wave_end, callback_once=True)

# 在指定周期进入交互模式
self.api_xbreak("SimTop_top.SimTop.timer", "eq", args.interact_at,
                callback=cb_on_interact, callback_once=True)
```

### 2.7 断点生命周期管理

| 命令 | 功能 |
|------|------|
| `xbreak <signal> [condition] [value]` | 设置信号断点 |
| `xunbreak <key>` | 移除指定断点（支持前缀匹配） |
| `xunbreak all` | 清除所有断点 |
| `xbreak_list` | 列出所有断点及其当前值、条件、目标值、是否触发 |
| `xbreak_update` | 更新 `ch`（变化检测）断点的参考值 |

当所有断点都被清除时，底层的 `ComUseCondCheck` checker 也会通过 `RemoveStepRisCbByDesc` 从时钟回调链中移除，确保零额外开销。

---

## 3. xbreak_expr — 表达式断点 (Expression-Based Breakpoints)

### 3.1 设计动机

单信号断点无法满足复杂调试场景的需求。例如，"当 D-Cache 在地址 0x80001000 发生 miss"需要同时检查 `s3_valid`、`s3_req_vaddr` 和 `missReqArb._io_out_valid_T` 三个信号。`xbreak_expr` 通过支持完整的布尔表达式语法解决了这个问题。

### 3.2 Python 层接口

Python 层的入口同样在 `CmdBreak` 类中：

```python
def api_xbreak_expr(self, expr, name=""):
```

该方法执行以下步骤：
1. 首次调用时创建 `ComUseExprCheck` 实例，并注册到时钟回调链（key: `xdut_expr_break`）
2. 调用 `checker.CompileExpr(expr, self.dut.xcfg)` 在 C++ 侧编译表达式
3. 编译成功后返回 root node ID
4. 通过 `checker.SetExpr(name, root)` 注册表达式

自动生成的 key 格式为 `xexpr-<id>`，其中 `<id>` 是单调递增的整数。用户也可以通过 `name` 参数指定自定义名称。

### 3.3 C++ ExprEngine 架构

ExprEngine 是表达式断点的核心执行引擎，在 C++ 侧实现，确保每个时钟沿的求值开销最小化。

#### 3.3.1 表达式节点数据结构

每个表达式被编译为一棵由 `ExprNode` 组成的树（或 DAG），存储在平坦的 vector 中，每个节点包含：

```cpp
struct ExprNode {
    ExprOp op;                // 操作类型
    int lhs;                  // 左子节点 ID，-1 表示未使用
    int rhs;                  // 右子节点 ID，-1 表示未使用
    XData* sig;               // SIGNAL 节点的信号指针
    uint64_t imm;             // CONST 节点的立即数值
    uint32_t width;           // 信号宽度（SIGNAL 节点），否则为 64
    bool is_signal;           // 是否为信号节点
    uint64_t window;          // WITHIN/HOLD 的窗口大小
    uint64_t last_true_cycle; // WITHIN 的上次为真周期
    uint64_t last_hold_cycle; // HOLD 的上次为假周期
    uint64_t hold_count;      // HOLD 的连续为真计数
};
```

#### 3.3.2 ExprOp 枚举

```cpp
enum class ExprOp {
    CONST, SIGNAL,                           // 叶节点
    ADD, SUB, MUL, DIV, MOD,                 // 算术运算
    BAND, BOR, BXOR, BNOT,                   // 位运算
    SHL, SHR,                                // 移位运算
    LAND, LOR, LNOT,                         // 逻辑运算
    EQ, NE, GT, GE, LT, LE,                  // 比较运算
    WITHIN, HOLD                              // 时间窗口辅助函数
};
```

#### 3.3.3 ExprEngine API

```cpp
class ExprEngine {
public:
    int NewConst(uint64_t v);              // 创建常量节点
    int NewSignal(XData* sig);             // 创建信号节点
    int NewUnary(ExprOp op, int child);    // 创建一元运算节点
    int NewBinary(ExprOp op, int lhs, int rhs);  // 创建二元运算节点
    int NewCompare(ExprOp op, int lhs, int rhs); // 创建比较节点
    int NewCompareSigSig(ExprOp op, XData* lhs, XData* rhs);   // 宽信号 vs 宽信号
    int NewCompareSigConst(ExprOp op, XData* lhs, uint64_t rhs); // 宽信号 vs 常量
    int NewCompareConstSig(ExprOp op, uint64_t lhs, XData* rhs); // 常量 vs 宽信号
    int NewWithin(int child, uint64_t window);  // within 辅助函数
    int NewHold(int child, uint64_t window);    // hold 辅助函数
    uint64_t Eval(int root);                    // 求值入口
    void Clear();                               // 清空所有节点
};
```

### 3.4 求值语义 (Evaluation Semantics)

#### 3.4.1 无符号运算

所有算术、位运算、移位运算均按无符号 64 位整数执行。负数常量以无符号补码形式存储。

#### 3.4.2 宽信号处理

- 宽度 <= 64 bit 的信号：使用标准 `uint64_t` 比较
- 宽度 > 64 bit 的信号：使用 `XData` 全宽度比较，要求两个操作数宽度相同
- 宽信号的算术/位运算在 v1 中不支持，编译时直接报错（`wide arithmetic/bitwise/shift is not supported in v1`）

#### 3.4.3 短路求值 (Short-Circuit Evaluation)

逻辑 `and`（`&&`）和 `or`（`||`）支持短路求值。当左操作数已确定结果时，右操作数不会被求值。这意味着位于被短路跳过的分支中的 `within/hold` 节点状态不会被更新。

#### 3.4.4 X/Z 值处理

在算术、位运算和移位操作中，X/Z 值不传播（被忽略）。在比较操作中，X/Z 值可能使等式比较返回 false（使用 `XData` 语义）。

### 3.5 时间窗口辅助函数

#### 3.5.1 `within(N, expr)`

返回 true 当 `expr` 在当前周期或过去 N 个周期内（含）曾为真。`within(0, expr)` 等价于 `expr`。内部通过 `last_true_cycle` 字段追踪上次为真的周期。

#### 3.5.2 `hold(N, expr)`

返回 true 当 `expr` 已连续 N 个周期为真。`hold(0, expr)` 和 `hold(1, expr)` 等价于 `expr`。内部通过 `hold_count` 字段追踪连续为真的周期数，`last_hold_cycle` 记录上次为假的周期用于重置计数。

#### 3.5.3 状态性 (Statefulness)

`within` 和 `hold` 是有状态的（stateful），它们的内部状态仅在被求值时更新。如果它们位于被短路跳过的分支中，状态不会更新。这是 v1 中的一个重要语义约定。

### 3.6 C++ 解析器 (Parser)

解析和编译在 C++ 侧完成，Python 仅转发表达式字符串和 `XSignalCFG` 实例。

#### 3.6.1 Tokenization 规则

- 标识符：`[A-Za-z_][A-Za-z0-9_\.]*`
- 数字：支持 `0x` 十六进制、`0b` 二进制、十进制，允许 `_` 分隔符
- 操作符：`== != >= <= << >> && || + - * / % & | ^ ~ ! < >`
- 关键字：`and`、`or`、`not`（分别是 `&&`、`||`、`!` 的同义词）

#### 3.6.2 运算符优先级（从高到低）

1. 一元：`~`、`-`、`+`
2. 乘法：`* / %`
3. 加法：`+ -`
4. 移位：`<< >>`
5. 位运算 AND/XOR/OR：`& ^ |`
6. 比较：`== != > >= < <=`（支持链式比较）
7. 逻辑 NOT：`not` / `!`
8. 逻辑 AND：`and` / `&&`
9. 逻辑 OR：`or` / `||`

#### 3.6.3 链式比较

`a < b < c` 被编译为 `(a < b) and (b < c)`。

#### 3.6.4 信号解析

使用 `XSignalCFG::NewXData(name)` 解析信号名。引擎按名称缓存 `XData` 对象，避免重复分配。

### 3.7 触发求值流程 (Trigger Evaluation)

`ComUseExprCheck` 在每个时钟上升沿（`StepRis`）被调用，执行流程如下：

1. 清除上一次的触发标志
2. 按注册顺序依次求值所有表达式
3. 遇到第一个为真的结果时：
   - 禁用所有绑定的时钟（`clk->Disable()`）
   - 标记该表达式为已触发
   - 停止求值剩余表达式

求值前，引擎设置当前周期为回调周期（`ComUseStepCb::cycle`），以便 `within/hold` 可以正确计算时间窗口。

求值使用显式栈机器（iterative）实现，而非递归，以避免深层表达式栈溢出。引擎可以重排 `and/or` 操作数以优化短路效率（低成本表达式优先），但当子树包含有状态的 `within/hold` 节点时跳过重排，以保持语义正确性。

### 3.8 比较优化

对于宽信号（>64 bit）的比较，编译时创建临时 `XData` 常量对象（由引擎持有），使用 `XData::Comp()` 方法直接比较，避免数值转换。常量按信号宽度零扩展，截断到对应位宽（负值用补码）。

---

## 4. xbreak_fsm — 状态机触发器 (FSM Triggers)

### 4.1 设计动机

表达式断点虽然强大，但本质上仍是无状态的单周期判断。对于需要检测"信号 A 出现后，接着在 200 个周期内看到信号 B 连续出现 4 个周期"这类多步骤序列模式，需要一个有状态的有限状态机触发器。

FSM 触发器的 C++ 引擎 `ComUseFsmTrigger` 复用了 `ExprEngine` 用于编译和求值转移条件，使得条件表达式语法与 `xbreak_expr` 完全一致。

### 4.2 FSM 程序语法

FSM 程序是纯文本格式，使用类汇编语法：

#### 4.2.1 基本结构

```
start <state_name>        # 可选，指定初始状态（默认为第一个 state）

state STATE_NAME:         # 状态定义
  <action>                # 动作（在条件检查前执行）
  if <expr> goto STATE    # 条件转移
  elif <expr> trigger     # 条件触发
  else goto STATE         # 无条件兜底转移
```

#### 4.2.2 状态定义

每个状态包含若干动作语句和条件转移语句。转移按顺序评估，第一个匹配的条件被执行。如果没有转移匹配且没有 `else` 分支，FSM 停留在当前状态。

#### 4.2.3 无条件转移

```
goto STATE_NAME           # 无条件跳转
trigger                   # 无条件触发
```

#### 4.2.4 注释

`#` 开头到行尾为注释。每行末尾的 `;` 是可选的。

### 4.3 动作 (Actions)

动作在每个周期的状态激活时、条件检查之前执行：

| 动作 | 含义 |
|------|------|
| `set $flag_xxx` | 设置 flag 为 1 |
| `clear $flag_xxx` | 设置 flag 为 0 |
| `inc $counter_xxx` | counter 加 1 |
| `reset $counter_xxx` | counter 重置为 0 |

**命名规则**：flag 名称必须以 `$flag` 开头，counter 名称必须以 `$counter` 开头。对 counter 使用 `set/clear` 或对 flag 使用 `inc/reset` 会在编译时被拒绝。

**值宽度**：flag 为 1-bit，counter 为 64-bit。

**立即可见性**：动作执行后值立即更新，同周期的条件评估可以看到最新值。

### 4.4 条件 (Conditions)

转移条件使用与 `xbreak_expr` 相同的表达式语法，包括：
- 布尔逻辑：`and`/`or`/`not` 或 `&&`/`||`/`!`
- 位运算、算术运算、比较运算
- `within(N, expr)` 和 `hold(N, expr)` 时间窗口函数
- RTL 信号名（通过 `XSignalCFG` 解析）
- `$flag*` 和 `$counter*` 变量可在表达式中作为合法标识符使用

### 4.5 执行语义

FSM 的每个周期执行流程：

1. **更新引擎周期**：`SetCycle` 设置当前仿真周期
2. **执行当前状态的动作**：更新 flag 和 counter 的值
3. **依次评估转移条件**：取第一个匹配的转移
4. **触发检查**：如果执行到 `trigger` 动作，禁用所有时钟并标记为已触发

关键语义要点：
- FSM 只维护单一活跃状态（single active state），不支持并行状态
- flag/counter 在程序加载和 `xbreak_fsm_clear` 时重置为 0
- `trigger` 会禁用所有绑定的时钟，与 `xbreak` 行为一致
- `within/hold` 每个节点独立维护状态（per-node state）

### 4.6 C++ 引擎实现

`ComUseFsmTrigger` 继承自 `ComUseStepCb`，注册到 `StepRis` 事件上。它拥有：

- 一个 `ExprEngine` 实例，用于编译和求值转移条件
- 一个状态表（`vector<FsmState>`），每个状态包含有序的转移列表和动作列表
- Flag 和 counter 的存储，以及对应的 `XData` 镜像用于在表达式中访问

### 4.7 错误处理

- 解析错误报告行号和原因
- 未知状态目标产生编译错误
- 类型错误（对 counter 使用 `set/clear` 或对 flag 使用 `inc/reset`）在编译时被拒绝

### 4.8 用户命令

| 命令 | 功能 |
|------|------|
| `xbreak_fsm <fsm_file>` | 从文件加载 FSM 程序并启动 |
| `xbreak_fsm_status` | 显示当前状态、是否已触发、触发时的状态 |
| `xbreak_fsm_clear` | 清除 FSM 触发器和回调 |

---

## 5. Watchpoint 机制 (Watchpoint Mechanism)

### 5.1 信号 Watchpoint

`xwatch` 命令用于添加监控变量。底层通过 `self.dut.GetInternalSignal(key)` 获取信号对象，存入 `self.info_watch_list` 字典中。

```python
def do_xwatch(self, arg):
    # arg: signal_name [alias]
    sig = self.dut.GetInternalSignal(key[0])
    if sig:
        self.info_watch_list[arb] = sig
```

当不带参数调用时，列出所有被监控信号的当前值和位宽（格式：`alias(width): 0xvalue`）。

`xunwatch` 从 `info_watch_list` 中移除指定的监控变量。

### 5.2 Commit PC Watchpoint

`xwatch_commit_pc` 通过 `ComUseCondCheck` 监控指令提交的 PC 值变化。它使用硬件 commit 信号（`difftest_commit_pc`）实现实时的 PC 跟踪。

相关命令：
- `xwatch_commit_pc`：开启 commit PC 监控
- `xunwatch_commit_pc`：关闭 commit PC 监控

### 5.3 信号检查与修改

| 命令 | 功能 |
|------|------|
| `xprint <signal>` | 读取并显示信号的值和位宽（`hex(sig.value)` 和 `sig.W()`） |
| `xset <signal> <value>` | 动态修改信号值，立即生效（使用 `AsImmWrite()` 写入） |
| `xpc` | 获取当前 PC，带有软件同步视图 |

### 5.4 性能特点

- Watchpoint 在被监控时只增加最小的开销
- 可以同时设置多个 watchpoint
- `xunwatch` 完全移除监控开销
- Commit PC 监控针对性能进行了优化

---

## 6. 实际示例 (Practical Examples)

### 6.1 信号断点

```bash
# 当 timer 信号等于 10000 时暂停
xbreak SimTop_top.SimTop.timer eq 10000
xstep 100000
```

### 6.2 表达式断点示例

#### 指令提交断点

在特定 PC 地址的指令提交时中断：

```bash
xbreak SimTop_top.SimTop.cpu.l_soc.core_with_l2.core.backend.inner_ctrlBlock.rob.difftest_commit_pc == 0x80000000
```

在特定指令编码（如 WFI `0x10500073`）提交时中断：

```bash
xbreak SimTop_top.SimTop.cpu.l_soc.core_with_l2.core.backend.inner_ctrlBlock.rob.difftest_commit_instr == 0x10500073
```

#### 异常断点

在任何异常发生时中断：

```bash
xbreak SimTop_top.SimTop.cpu.l_soc.core_with_l2.core.backend.inner_ctrlBlock.rob.io_exception_valid_REG == 1
```

在特定类型的异常（中断）发生时中断：

```bash
xbreak_expr SimTop_top.SimTop.cpu.l_soc.core_with_l2.core.backend.inner_ctrlBlock.rob.io_exception_valid_REG == 1 && SimTop_top.SimTop.cpu.l_soc.core_with_l2.core.backend.inner_ctrlBlock.rob.io_exception_bits_isInterrupt_r == 1
```

#### Cache 和访存断点

D-Cache 在特定地址发生 Miss 时中断：

```bash
xbreak_expr SimTop_top.SimTop.cpu.l_soc.core_with_l2.core.memBlock.inner_dcache.dcache.mainPipe.s3_valid == 1 && SimTop_top.SimTop.cpu.l_soc.core_with_l2.core.memBlock.inner_dcache.dcache.mainPipe.s3_req_vaddr == 0x80001000 && SimTop_top.SimTop.cpu.l_soc.core_with_l2.core.memBlock.inner_dcache.dcache.missReqArb._io_out_valid_T == 1
```

I-Cache 访问特定地址时中断：

```bash
xbreak_expr SimTop_top.SimTop.cpu.l_soc.core_with_l2.core.frontend.inner_ifu.s2_valid == 1 && SimTop_top.SimTop.cpu.l_soc.core_with_l2.core.frontend.inner_ifu.s2_icacheMeta_0_pAddr_addr == 0x80000000
```

I-Cache 发生 Miss 时中断：

```bash
xbreak SimTop_top.SimTop.cpu.l_soc.core_with_l2.core.frontend.inner_icache.missUnit.acquireArb._io_out_valid_T == 1
```

I-Cache 在特定地址发生 Miss 时中断（三信号组合）：

```bash
xbreak_expr SimTop_top.SimTop.cpu.l_soc.core_with_l2.core.frontend.inner_ifu.s2_valid == 1 && SimTop_top.SimTop.cpu.l_soc.core_with_l2.core.frontend.inner_ifu.s2_icacheMeta_0_pAddr_addr == 0x80000000 && SimTop_top.SimTop.cpu.l_soc.core_with_l2.core.frontend.inner_icache.missUnit.acquireArb._io_out_valid_T == 1
```

#### 时间窗口表达式

使用 `within` 辅助函数：当 timer 在过去 20 个周期内曾等于 1000 时触发：

```bash
xbreak_expr within(SimTop_top.SimTop.timer == 1000, 20)
```

### 6.3 FSM 触发示例

#### PC 序列检测 (pc_sequence.fsm)

该 FSM 检测特定的 PC 执行序列：`0x10000000 -> 0x10000004 -> 0x10000008 -> 0x80000000`。中间出现的其他 PC 值被忽略。

```
start WAIT_PC_0

state WAIT_PC_0:
  if SimTop_top.SimTop.cpu...difftest_commit_pc == 0x10000004 goto WAIT_PC_4
  else goto WAIT_PC_0

state WAIT_PC_4:
  if ...difftest_commit_pc == 0x10000008 goto WAIT_PC_80
  elif ...difftest_commit_pc == 0x10000000 goto WAIT_PC_4
  else goto WAIT_PC_4

state WAIT_PC_80:
  if ...difftest_commit_pc == 0x80000000 trigger
  elif ...difftest_commit_pc == 0x10000000 goto WAIT_PC_4
  else goto WAIT_PC_80
```

关键设计模式：
- 每个状态都保留了回退到之前状态的路径（用于重新检测序列起始）
- 状态机自动跳过不相关的中间 PC 值（只在关键节点检查）
- WAIT_PC_4 中检测到 0x10000000 时回到自身而非 WAIT_PC_0，因为已经在序列的第二步

#### CSR 读写追踪 (csr_trace.fsm)

该 FSM 追踪完整的 CSR 读写操作流程。它使用多个硬件微架构信号来精确追踪 CSR 单元的行为：

```
start IDLE

state IDLE:
  if ...Exu_Csr_io_redirect_valid == 1 && ...Exu_Csr_io_redirect_bits_is_write == 1 goto WRITE
  elif ...Exu_Csr_io_redirect_valid == 1 && ...Exu_Csr_io_redirect_bits_is_read == 1 goto READ
  else goto IDLE

state READ:
  if ...Exu_Csr_release == 1 goto IDLE
  else goto READ

state WRITE:
  if ...Exu_Csr_is_read == 1 goto READ_WRITE
  elif ...Exu_Csr_release == 1 goto IDLE
  else goto WRITE

state READ_WRITE:
  if ...Exu_Csr_release == 1 trigger
  else goto READ_WRITE
```

关键设计模式：
- 使用多个内部硬件信号（redirect_valid, is_write, is_read, is_read, release）精确追踪硬件微架构行为
- 区分了纯写（csrw）、纯读（csrr）和读写（csrrw）三种操作类型
- 仅在完整的 read-write 操作完成后（release 信号）触发
- READ 状态是简单的等待释放；WRITE 状态需要进一步判断是否有读分量

### 6.4 带 counter 的 FSM 示例

```
start IDLE

state IDLE:
  reset $counter_timeout
  if sigA == 1 goto WAIT_B
  else goto IDLE

state WAIT_B:
  inc $counter_timeout
  if hold(4, sigB == 1) trigger
  elif $counter_timeout >= 200 goto IDLE
  else goto WAIT_B
```

该示例展示了 counter 与时间窗口函数的组合使用：
- 在 IDLE 状态重置超时计数器
- 当 sigA 出现后进入 WAIT_B 状态
- 在 WAIT_B 中递增超时计数器
- 如果 sigB 连续 4 个周期为真则触发
- 如果超时计数器达到 200 则回到 IDLE

---

## 7. 系统集成与交互 (System Integration)

### 7.1 仿真循环中的断点检查

断点的触发检测嵌入在 `api_step_dut` 的仿真循环中：

```python
def api_step_dut(self, cycle, batch_cycle=200):
    def check_break():
        if self.dut.xclock.IsDisable():
            # 时钟被 disable，说明有断点触发
            info("Find break point (%s), break at cycle: %d" % ...)
            self.call_break_callbacks()
            return True
        # ... 其他检查（trap, difftest exit 等）
    # 分批执行 step，每 batch_cycle 检查一次
```

所有三种断点机制（signal、expr、fsm）的触发最终都通过 `clk->Disable()` 体现为时钟禁用。仿真循环检测到时钟被禁用后，调用 `call_break_callbacks()` 执行回调，然后返回控制给用户。在 batch 模式下，`check_break()` 在每个 `batch_cycle` 后被调用，确保断点能在合理的延迟内被检测到。

### 7.2 状态显示

`cmd_info.py` 中的 `XBreaks`、`XBreak Exprs` 和 `XBreak FSM` 区域在 TUI 中实时显示所有断点和触发器的状态。已触发的断点以红色高亮显示（使用 `("error_red", ...)` 标记）。

FSM 状态显示格式：
```
name=<name> state=<current_state> triggered=<bool> trigger_state=<triggered_state>
```

`api_get_breaked_names()` 方法汇总所有触发源的名称，用于在 `check_break` 的日志中显示触发原因。

### 7.3 三种机制的对比

| 特性 | xbreak | xbreak_expr | xbreak_fsm |
|------|--------|-------------|------------|
| 状态性 | 无状态 | within/hold 有状态 | 完整状态机 |
| 多信号支持 | 单信号 | 多信号表达式 | 多信号表达式 |
| 时间窗口 | 不支持 | within(N) / hold(N) | within(N) / hold(N) |
| 变量系统 | 无 | 无 | $flag, $counter |
| C++ 引擎 | ComUseCondCheck | ComUseExprCheck | ComUseFsmTrigger |
| Callback | 支持 Python callback | 不支持 | 不支持（通过 trigger） |
| 触发行为 | Disable clock | Disable clock | Disable clock |
| 复杂度 | 低 | 中 | 高 |
| 典型用途 | 特定值检测、波形控制 | 组合条件检测 | 序列模式检测 |

---

## 8. 源文件索引 (Source File Locations)

### 核心 Python 文件

| 文件 | 说明 |
|------|------|
| `scripts/xspdb/xspdb.py` | XSPdb 主类，继承 pdb.Pdb，包含仿真循环和 batch 逻辑 |
| `scripts/xspdb/xscmd/cmd_break.py` | CmdBreak 类，包含 xbreak / xbreak_expr / xbreak_fsm 的所有 Python API 和 CLI 命令 |
| `scripts/xspdb/xscmd/cmd_dut.py` | CmdDut 类，包含 xwatch / xunwatch / xset / xprint / xstep 命令 |
| `scripts/xspdb/xscmd/cmd_difftest.py` | 包含 xwatch_commit_pc / xunwatch_commit_pc 命令 |
| `scripts/xspdb/xscmd/cmd_info.py` | 状态信息显示，包含 XBreaks / XBreak Exprs / XBreak FSM 的 TUI 渲染逻辑 |

### 设计文档

| 文件 | 说明 |
|------|------|
| `docs/XSPdb/README.md` | XSPdb 项目总览 |
| `docs/XSPdb/design/breakpoints.md` | 断点系统设计概述 |
| `docs/XSPdb/design/trigger_expr_spec.md` | ExprEngine 完整规范（v1） |
| `docs/XSPdb/design/trigger_fsm_spec.md` | FSM 触发器完整规范（v1） |
| `docs/XSPdb/design/watchpoints.md` | Watchpoint 机制设计文档 |

### 示例文件

| 文件 | 说明 |
|------|------|
| `docs/XSPdb/examples/breakpoints.md` | 基本断点用法示例 |
| `docs/XSPdb/examples/watchpoints.md` | Watchpoint 用法示例 |
| `docs/XSPdb/examples/xbreak_expr_examples.md` | xbreak_expr 丰富的实用表达式示例 |
| `docs/XSPdb/examples/pc_sequence.fsm` | PC 序列检测 FSM 程序 |
| `docs/XSPdb/examples/csr_trace.fsm` | CSR 读写追踪 FSM 程序 |

### C++ 引擎（通过 pyxscore 绑定）

C++ 引擎的源码位于 `pyxscore` 包中（不在本仓库源码树内，通过 pip 安装），暴露以下关键类：

- `pyxscore.ComUseCondCheck` — 信号条件检查器（xbreak 底层）
- `pyxscore.ComUseExprCheck` — 表达式检查器（xbreak_expr 底层），包含 `CompileExpr()` 和 `SetExpr()` 方法
- `pyxscore.ComUseFsmTrigger` — FSM 触发器（xbreak_fsm 底层），包含 `LoadProgram()` 方法
- `pyxscore.XData` — 宽信号数据类型，支持全宽度比较
- `pyxscore.XSignalCFG` — 信号配置，用于信号名到 XData 的解析和缓存
- `pyxscore.ComUseStepCb` — 回调基类，提供 `GetCb()` 和 `CSelf()` 方法用于注册到 StepRis

---

## 9. 总结

XiangShan XSPdb 的断点与触发系统是一个精心设计的三层架构：

**底层（C++）**：`ExprEngine` 提供高性能的表达式求值引擎，支持有状态的时间窗口函数（`within`/`hold`）和宽信号（>64 bit）的 `XData` 全宽度比较。三类回调类——`ComUseCondCheck`、`ComUseExprCheck`、`ComUseFsmTrigger`——分别处理信号比较、表达式求值和状态机触发三种不同层次的需求。

**中间层（Python API）**：`CmdBreak` 类封装了所有断点操作的 Python API（`api_xbreak`、`api_xbreak_expr`、`api_xbreak_fsm`），管理断点的生命周期、callback 注册和状态追踪。该层负责将 Python 命令翻译为 C++ 引擎调用。

**用户层（CLI）**：`do_xbreak`、`do_xbreak_expr`、`do_xbreak_fsm` 等命令提供 GDB 风格的交互式调试体验，支持 Tab 补全（基于 DUT 信号树和已注册断点的前缀匹配）和批量执行。

整个系统的核心设计原则是：

1. **性能优先**：表达式求值和条件检查在 C++ 侧完成，避免 Python GIL 和解释器开销；求值使用迭代栈机器而非递归
2. **渐进式复杂度**：从简单的单信号比较（xbreak）到组合条件（xbreak_expr）再到完整状态机（xbreak_fsm），用户按需选择
3. **统一的触发行为**：所有断点机制最终都通过 `clk->Disable()` 暂停仿真，保持一致的行为语义
4. **可组合性**：FSM 内部复用 ExprEngine 的表达式语法，包括时间窗口函数和变量系统，形成统一的条件表达式语言
5. **零开销清除**：当所有断点被清除时，底层 checker 也从时钟回调链中移除，确保不影响仿真性能
