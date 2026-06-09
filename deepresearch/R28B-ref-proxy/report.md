The Write tool is not available to this subagent. I will return the full report content as my final response instead.

---

# R28B - Reference Model Integration (Ref Proxy 深度分析报告)

## 1. 概述

XiangShan RISC-V 处理器的验证框架 DiffTest 采用了一种高度灵活的 Reference Model (REF) 集成机制。其核心思想是：DUT (Design Under Test) 的每一次指令提交，都需要与一个 golden reference model 进行状态比对，以发现 RTL 实现中的 bug。为了支持多种不同的参考模型（NEMU、Spike、以及直接链接的自定义模型），DiffTest 框架设计了一套基于 `dlopen`/`dlsym` 的动态加载代理层 (Proxy Layer)，使得参考模型可以以 shared library 的形式在运行时动态加载，也可以在编译期直接静态链接。

本报告深入分析了以下核心组件：

- `AbstractRefProxy` / `RefProxy` 类体系及其动态加载机制
- `RTLD_DEEPBIND` 和 `dlmopen` 在多核隔离场景下的应用
- `REF_BASE` 与 `REF_OPTIONAL` 两层 API 抽象设计
- `NemuProxy`、`SpikeProxy`、`LinkedProxy` 三种具体代理变体
- `DUT` 基类与 `SimStats` 覆盖率统计基础设施
- `CommonArgs` 命令行参数解析体系
- Performance Counter (perf) 性能计数器框架

---

## 2. 源文件索引

| 文件路径 | 功能描述 |
|---------|---------|
| `difftest/src/test/csrc/difftest/refproxy.h` | RefProxy 类声明、REF API 宏定义、寄存器名称表、ExecutionGuide 等结构体 |
| `difftest/src/test/csrc/difftest/refproxy.cpp` | RefProxy 类实现，dlopen/dlsym 动态加载逻辑，NEMU/Spike/Linked 代理构造 |
| `difftest/src/test/csrc/common/dut.h` | DUT 抽象基类、SimExitCode 枚举、SimStats 覆盖率统计类 |
| `difftest/src/test/csrc/common/dut.cpp` | SimStats 全局实例定义、覆盖率反馈接口 (extern "C") |
| `difftest/src/test/csrc/common/args.h` | CommonArgs 结构体定义，声明 parse_args() |
| `difftest/src/test/csrc/common/args.cpp` | 基于 getopt_long 的命令行参数解析实现 |
| `difftest/src/test/csrc/common/perf.h` | 性能计数器枚举 (DIFFTEST_PERF) 与接口声明 |
| `difftest/src/test/csrc/common/perf.cpp` | 性能计数器初始化、汇总、打印实现 |
| `difftest/src/test/csrc/common/coverage.h` | Coverage 基类、InstrCoverage / FIRRTLCoverage / LLVMSanCoverage 覆盖率子类 |

---

## 3. AbstractRefProxy 类设计：dlopen/dlsym 动态加载机制

### 3.1 架构总览

`AbstractRefProxy` 是整个参考模型代理层的根基类。它的核心职责是：**在运行时动态加载一个 shared object (.so) 文件，并从中解析出所有需要的函数指针**。该类本身是一个纯加载器 (loader)，不包含任何调用逻辑——调用逻辑由其子类 `RefProxy` 提供。

```cpp
class AbstractRefProxy {
public:
  REF_ALL(DeclRefFunc)          // 必需 API 的函数指针 (public)

  AbstractRefProxy(int coreid, size_t ram_size, const char *env, const char *file_path);
  ~AbstractRefProxy();

protected:
  REF_OPTIONAL(DeclRefFunc)     // 可选 API 的函数指针 (protected)

private:
  void *const handler;          // dlopen 返回的句柄
  void *load_handler(const char *env, const char *file_path);
  template <typename T> T load_function(const char *func_name);
};
```

### 3.2 动态加载流程

构造函数 `AbstractRefProxy` 的执行流程如下：

**Step 1 -- 加载 shared library 句柄**

通过 `load_handler()` 方法加载 .so 文件。该方法的逻辑是：
1. 如果全局变量 `difftest_ref_so` 已经被命令行 `--diff` 参数设定，直接使用用户指定的路径。
2. 否则，从环境变量（如 `NEMU_HOME` 或 `SPIKE_HOME`）拼接出默认路径，格式为 `{NEMU_HOME}/build/riscv64-nemu-interpreter-so`。
3. 选择合适的 `dlopen` 模式（见下文 4.x 节的多核隔离分析）。
4. 返回 `void*` 类型的动态库句柄。

**Step 2 -- 解析所有必需函数**

使用 `REF_ALL` 宏展开所有 `REF_BASE` + `REF_RUN_AHEAD` + `REF_STORE_LOG` + `REF_DEBUG_MODE` 中声明的函数。对于每个函数，通过模板方法 `load_function` 调用 `dlsym(handler, func_name)` 获取函数指针，并使用 `check_and_assert` 宏验证加载是否成功。

```cpp
template <typename T> T AbstractRefProxy::load_function(const char *func_name) {
  check_and_assert(handler);
  return reinterpret_cast<T>(dlsym(handler, func_name));
}
```

`check_and_assert` 宏在函数指针为 NULL 时会打印 `dlerror()` 的错误信息并 assert 终止。

**Step 3 -- 解析可选函数**

对 `REF_OPTIONAL` 中的函数进行同样的加载，但 **不要求成功**——可选函数可能不存在于旧版本的参考模型中。

**Step 4 -- 初始化参考模型**

- 如果 `NUM_CORES > 1`（多核模式），调用 `ref_set_mhartid(coreid)` 设置当前核心 ID，并调用 `ref_put_gmaddr(ref_golden_mem)` 传递共享 golden memory 的地址。
- 如果 `ram_size > 0`，调用 `ref_set_ramsize(ram_size)` 通知参考模型内存大小。
- 如果 `ref_init_v2` 可用，调用 `ref_init_v2(sizeof(ref_state_t))` 传入状态结构体大小；否则调用旧版 `ref_init()`。

### 3.3 静态链接模式 (LINKED_REFPROXY_LIB)

当定义了 `LINKED_REFPROXY_LIB` 宏时，`AbstractRefProxy` 不执行动态加载，而是直接引用外部链接的符号。此时：
- `load_handler()` 直接返回 `nullptr`，不调用 `dlopen`。
- 函数指针通过 `extern "C"` 声明直接赋值，而非通过 `dlsym`。
- 对于 `REF_OPTIONAL` 中的可选函数，使用 `__attribute__((weak))` 声明为 weak symbol，使得未定义时不会导致链接错误。

```cpp
#define DeclExtRefFunc(dummy, ref_func, ret, ...) \
  extern "C" { extern RefFunc(ref_func, ret, __VA_ARGS__); }
REF_ALL(DeclExtRefFunc)

#define DeclWeakExtRefFunc(dummy, ref_func, ret, ...) \
  extern "C" { extern RefFunc(ref_func, ret __attribute__((weak)), __VA_ARGS__); }
REF_OPTIONAL(DeclWeakExtRefFunc)
```

### 3.4 析构函数

`AbstractRefProxy` 的析构函数负责调用 `dlclose(handler)` 释放动态库句柄。子类 `RefProxy` 的析构函数则额外调用 `ref_close()`（如果可用）以执行参考模型自身的清理逻辑。

---

## 4. RTLD_DEEPBIND 与 dlmopen：多核隔离机制

### 4.1 RTLD_DEEPBIND 的作用

在动态加载参考模型的 shared library 时，`load_handler` 使用了如下模式：

```cpp
int mode = RTLD_LAZY | RTLD_DEEPBIND;
void *so_handler = (NUM_CORES > 1)
    ? dlmopen(LM_ID_NEWLM, difftest_ref_so, mode)
    : dlopen(difftest_ref_so, mode);
```

`RTLD_DEEPBIND` 的语义是：在符号解析时，**优先从被加载的 library 自身的符号表中查找**，而不是先搜索全局符号表。这解决了一个关键问题：当参考模型（如 NEMU）和 DiffTest 模拟器主程序中存在同名符号（如都定义了 `difftest_init`）时，`RTLD_DEEPBIND` 确保参考模型内部调用的是自身的函数实现，而非被主程序的符号覆盖。

### 4.2 dlmopen 与 LM_ID_NEWLM：多核隔离

当 `NUM_CORES > 1` 时，情况变得更加复杂。每个核心需要独立加载一个参考模型实例，且各个实例的全局状态（如内部寄存器、内存映射）必须相互隔离。

`dlmopen(LM_ID_NEWLM, ...)` 创建了一个**全新的 link map namespace**。与普通 `dlopen` 的区别在于：

| 特性 | `dlopen` | `dlmopen(LM_ID_NEWLM, ...)` |
|------|----------|------------------------------|
| 符号作用域 | 全局共享 | 独立 namespace |
| 多实例隔离 | 不支持 | 支持 |
| 全局变量隔离 | 共享同一份 | 每个 namespace 独立一份 |
| 适用场景 | 单核 | 多核 |

在多核模式下，每个核心的 `AbstractRefProxy` 实例调用 `dlmopen` 独立加载参考模型的 .so 文件，使得：
- Core 0 和 Core 1 各自拥有独立的 NEMU 实例状态
- 各核心的 `ref_set_mhartid(coreid)` 和 `ref_put_gmaddr(ref_golden_mem)` 配合使用，实现多核并行 DiffTest

`ref_golden_mem` 是一块所有核心共享的 golden memory 区域，用于多核间的内存一致性比对。

### 4.3 单核 vs 多核的加载路径对比

```
单核 (NUM_CORES == 1):
  dlopen(difftest_ref_so, RTLD_LAZY | RTLD_DEEPBIND)
  -> 加载 .so 到主进程的全局 namespace
  -> 所有符号在全局范围内可见

多核 (NUM_CORES > 1):
  dlmopen(LM_ID_NEWLM, difftest_ref_so, RTLD_LAZY | RTLD_DEEPBIND)
  -> 为每个核心创建独立的 link map namespace
  -> 核心间状态完全隔离
  -> 通过 ref_set_mhartid / ref_put_gmaddr 进行初始化配置
```

---

## 5. REF_BASE 与 REF_OPTIONAL：两层 API 抽象设计

### 5.1 宏驱动的 API 声明

DiffTest 采用了一种非常精巧的宏驱动设计来声明参考模型 API。每个 API 函数通过如下格式定义：

```cpp
f(ref_func_name, difftest_symbol_name, return_type, arg_types...)
```

例如：

```cpp
f(ref_init, difftest_init, void, )
f(ref_exec, difftest_exec, void, uint64_t)
f(store_commit, difftest_store_commit, int, uint64_t*, uint64_t*, uint8_t*)
```

这些宏被展开为三种用途：
1. **函数指针声明**：`DeclRefFunc` 宏将其展开为成员函数指针声明
2. **dlsym 加载**：`GetRefFunc` / `LoadRefFunc` 宏将其展开为 `dlsym` 调用或直接引用
3. **extern "C" 声明**：在 LINKED 模式下展开为外部链接声明

### 5.2 REF_BASE（必需 API）

`REF_BASE` 定义了参考模型**必须实现**的 10 个核心接口：

| 函数名 | 对应符号 | 功能 |
|--------|---------|------|
| `ref_init` | `difftest_init` | 初始化参考模型 |
| `ref_regcpy` | `difftest_regcpy` | 寄存器状态双向拷贝 (DUT->REF 或 REF->DUT) |
| `ref_csrcpy` | `difftest_csrcpy` | CSR 寄存器状态拷贝 |
| `ref_memcpy` | `difftest_memcpy` | 内存内容拷贝 |
| `ref_exec` | `difftest_exec` | 执行一条指令 |
| `ref_reg_display` | `difftest_display` | 打印当前寄存器状态 |
| `update_config` | `update_dynamic_config` | 更新动态配置 |
| `uarchstatus_sync` | `difftest_uarchstatus_sync` | 微架构状态同步 |
| `store_commit` | `difftest_store_commit` | Store 提交事件比对 |
| `raise_intr` | `difftest_raise_intr` | 触发中断 |

`REF_BASE` 之上还有三个条件编译的子集：
- **`REF_RUN_AHEAD`**：当 `ENABLE_RUNHEAD` 定义时，提供 `query` 接口用于 runahead 性能分析
- **`REF_STORE_LOG`**：当 `ENABLE_STORE_LOG` 定义时，提供 store 日志的 reset/restore 接口
- **`REF_DEBUG_MODE`**：当 `DEBUG_MODE_DIFF` 定义时，提供 debug memory sync 接口

`REF_ALL` = `REF_BASE` + `REF_RUN_AHEAD` + `REF_STORE_LOG` + `REF_DEBUG_MODE`，构成了参考模型的全部必需 API 集合。

### 5.3 REF_OPTIONAL（可选 API）

`REF_OPTIONAL` 定义了 20+ 个可选接口，包括但不限于：

| 函数名 | 功能 |
|--------|------|
| `ref_init_v2` | 带状态结构体大小的初始化（v2 接口） |
| `load_flash_bin` / `load_flash_bin_v2` | Flash 镜像加载 |
| `ref_status` | 获取参考模型运行状态 |
| `ref_close` | 关闭参考模型 |
| `ref_set_ramsize` | 设置内存大小 |
| `ref_set_mhartid` | 设置 RISC-V Hart ID |
| `ref_put_gmaddr` | 传递共享 golden memory 地址 |
| `ref_skip_one` | 跳过一条指令（微架构差异处理） |
| `ref_guided_exec` | 带引导信息的执行 |
| `raise_nmi_intr` | 触发 NMI 中断 |
| `ref_virtual_interrupt_is_hvictl_inject` | HVICTL 虚拟中断注入 |
| `ref_interrupt_delegate` | 中断委托 |
| `disambiguation_state` | 消歧状态查询 |
| `raise_mhpmevent_overflow` | HPM 事件溢出 |
| `ref_raise_critical_error` | 关键错误上报 |
| `ref_sync_aia` | AIA (Advanced Interrupt Architecture) 状态同步 |
| `ref_get_vec_load_vdNum` / `ref_get_vec_load_dual_goldenmem_reg` / `ref_update_vec_load_goldenmen` | Vector load golden memory 管理 |

### 5.4 调用端的防御性设计

`RefProxy` 类在调用可选 API 时，始终采用 "null-check then call" 的防御性模式：

```cpp
inline void trigger_nmi(bool hasNMI) {
  if (raise_nmi_intr) {
    raise_nmi_intr(hasNMI);
  } else {
    Info("No NMI interrupt is triggered.\n");
  }
}
```

这种设计使得 DiffTest 框架能够向后兼容旧版本的参考模型——即使参考模型缺少某些新接口，模拟器仍可正常运行，只是相关功能会被静默跳过并打印提示信息。

---

## 6. RefProxy 类：状态管理与比对逻辑

### 6.1 ref_state_t 状态结构体

`RefProxy` 维护一个 `ref_state_t` 类型的状态结构体，使用 `__attribute__((packed))` 确保内存布局紧凑：

```cpp
typedef struct __attribute__((packed)) {
  DifftestArchIntRegState xrf;      // 整数寄存器 (x0-x31)
  DifftestArchFpRegState frf;       // 浮点寄存器 (f0-f31) [可选]
  DifftestCSRState csr;             // CSR 寄存器
  uint64_t pc;                      // 程序计数器
  DifftestHCSRState hcsr;           // Hypervisor CSR [可选]
  DifftestArchVecRegState vrf;      // Vector 寄存器 [可选]
  DifftestVecCSRState vcsr;         // Vector CSR [可选]
  DifftestFpCSRState fcsr;          // FP CSR [可选]
  DifftestTriggerCSRState triggercsr; // Trigger CSR [可选]
} ref_state_t;
```

各个可选字段通过 `CONFIG_DIFFTEST_ARCHFPREGSTATE`、`CONFIG_DIFFTEST_HCSRSTATE`、`CONFIG_DIFFTEST_ARCHVECREGSTATE` 等编译宏控制是否包含，实现了高度的可配置性。

### 6.2 regcpy 方法：状态拷贝

`RefProxy::regcpy()` 方法从 `DiffTestRegState` 中提取各个寄存器组，填入 `ref_state_t`，然后通过 `ref_regcpy(&state, DUT_TO_REF, false)` 将状态推送到参考模型。

`REF_TO_DUT`（值 0）和 `DUT_TO_REF`（值 1）定义了拷贝方向的常量枚举。

### 6.3 compare 方法：状态比对

`RefProxy::compare()` 方法将 DUT 状态与参考模型状态逐字段进行 `memcmp` 比较。如果发现 CSR 不匹配，还会尝试 CSR waive（放宽/豁免）逻辑。

CSR waive 机制（通过 `do_csr_waive()` 方法实现）在 `CPU_ROCKET_CHIP` 定义时生效，针对 `mtval`、`stval`、`mtvec`、`stvec` 等 CSR，允许虚拟地址编码差异导致的值不同——例如 `encode_vaddr`、`sext_vaddr_40bit`、`read_stvec`、`read_mtvec` 等变换函数可以将 DUT 的值映射为参考模型的值，从而避免误报。

### 6.4 display 方法：差异报告

`RefProxy::display()` 使用 `PROXY_COMPARE_AND_DISPLAY` 宏，逐个比对寄存器值并通过 `REPORT_DIFFERENCE` 宏打印差异详情，格式为：

```
  mstatus different at pc = 0x80000000, right = 0x0000000000000000, wrong = 0x0000000000001800
```

### 6.5 skip_one 方法：指令跳过

`skip_one()` 方法处理微架构差异——当 DUT 和参考模型在某条指令的执行行为上有已知差异时（如特殊 CSR 访问），可以通过 `ref_skip_one` 让参考模型跳过该指令。如果参考模型不支持 `ref_skip_one`，则通过手动修改 `state.pc`（加 2 或 4，取决于 RVC 压缩指令）和 `state.xrf`/`state.frf` 来模拟跳过效果。

### 6.6 ExecutionGuide 结构体

`ExecutionGuide` 用于向参考模型传递执行引导信息，支持：
- `force_raise_exception`：强制触发异常（含 `exception_num`、`mtval`、`stval`、`mtval2`、`htval`、`vstval`）
- `force_set_jump_target`：强制设置跳转目标地址

当参考模型不支持 `ref_guided_exec` 时，fallback 为 `ref_exec(1)`（执行一条指令）。

---

## 7. NemuProxy / SpikeProxy / LinkedProxy：三种参考模型变体

### 7.1 NemuProxy

```cpp
#define NEMU_ENV_VARIABLE "NEMU_HOME"
#define NEMU_SO_FILENAME  "build/riscv64-nemu-interpreter-so"
NemuProxy::NemuProxy(int coreid, size_t ram_size)
    : RefProxy(coreid, ram_size, NEMU_ENV_VARIABLE, NEMU_SO_FILENAME) {}
```

NEMU (NJU EMUlator) 是最常用的参考模型。NemuProxy 将环境变量名 `NEMU_HOME` 和 .so 文件相对路径 `build/riscv64-nemu-interpreter-so` 传递给 `RefProxy` 构造函数，由后者负责完整的动态加载流程。

NEMU 作为参考模型的优势在于：逐条指令执行，行为正确性有保障；支持完整的 RISC-V ISA（包括特权级、H 扩展等）；动态加载模式支持多核隔离。

### 7.2 SpikeProxy

```cpp
#define SPIKE_ENV_VARIABLE "SPIKE_HOME"
#define SPIKE_SO_FILENAME  "difftest/build/riscv64-spike-so"
SpikeProxy::SpikeProxy(int coreid, size_t ram_size)
    : RefProxy(coreid, ram_size, SPIKE_ENV_VARIABLE, SPIKE_SO_FILENAME) {}
```

Spike 是 RISC-V 官方 ISA 模拟器。SpikeProxy 的结构与 NemuProxy 类似，但使用 `SPIKE_HOME` 环境变量和不同的 .so 路径。Spike 作为参考模型常用于验证 NEMU 自身的正确性，形成 "NEMU vs Spike vs RTL" 三重比对。

### 7.3 LinkedProxy

```cpp
LinkedProxy::LinkedProxy(int coreid, size_t ram_size)
    : RefProxy(coreid, ram_size) {  // env=nullptr, file_path=nullptr
  if (NUM_CORES > 1) {
    printf("LinkedProxy does not support NUM_CORES(%d) > 1.\n", NUM_CORES);
    assert(0);
  }
}
```

LinkedProxy 用于参考模型在编译期直接链接到模拟器二进制中的场景。它传入 `nullptr` 作为环境变量和文件路径，使得 `load_handler()` 在 `LINKED_REFPROXY_LIB` 宏下直接返回 `nullptr`，函数指针通过 `extern "C"` 符号解析直接获取。

**关键限制**：LinkedProxy 不支持多核模式（`NUM_CORES > 1`），因为静态链接无法实现多实例隔离。这是由于静态链接下所有符号共享同一份全局状态，而 `dlmopen` 的 namespace 隔离机制无法在编译期生效。

---

## 8. DUT 基类与 SimStats 覆盖率统计

### 8.1 DUT 抽象基类

```cpp
class DUT {
public:
  DUT() {};
  DUT(int argc, const char *argv[]) {};
  virtual int tick() = 0;          // 执行一个时钟周期
  virtual int is_finished() = 0;   // 是否执行完毕
  virtual int is_good() = 0;       // 是否正常结束 (good trap)
};
```

`DUT` 是一个极其精简的抽象基类，仅定义了三个纯虚函数：`tick()` 驱动仿真前进一个周期，`is_finished()` 检查是否达到终止条件，`is_good()` 判断是否以 good trap 正常退出。所有具体的 DUT 实现（如 EMU、SE 等）都继承自此类。

### 8.2 SimExitCode 枚举

```cpp
enum class SimExitCode {
  good_trap,       // 正常退出（good trap）
  exceed_limit,    // 超出指令/周期限制
  bad_trap,        // 非正常退出（bad trap）
  exception_loop,  // 异常循环
  ambiguous,       // 不确定状态
  sim_exit,        // 仿真器主动退出
  difftest,        // DiffTest 比对失败
  unknown          // 未知状态
};
```

### 8.3 SimStats 类

`SimStats` 是覆盖率统计的核心容器，管理多种覆盖率收集器：

```cpp
class SimStats {
public:
  std::vector<Coverage *> cover;   // 覆盖率收集器列表
  SimExitCode exit_code;           // 仿真退出码
};
```

在构造函数中，根据编译宏条件注册覆盖率收集器：
- `CONFIG_DIFFTEST_INSTRCOVER` -> `InstrCoverage`（指令覆盖率）
- `CONFIG_DIFFTEST_INSTRIMMCOVER` -> `InstrImmCoverage`（指令立即数覆盖率）
- `FIRRTL_COVER` -> `FIRRTLCoverage`（FIRRTL 信号覆盖率）
- `LLVM_COVER` -> `LLVMSanCoverage`（LLVM SanitizerCoverage 分支覆盖率）

每个覆盖率收集器都支持 `reset()`、`update()`、`accumulate()`、`display()`、`to_covered_bytes()` 等标准操作。

### 8.4 Coverage 基类设计

`Coverage` 基类定义了覆盖率收集器的统一接口：
- `get_total_points()` / `get_covered_points()` / `get_acc_covered_points()`：获取覆盖率数据
- `accumulate()`：累积覆盖点（用于跨 run 的累积统计）
- `update_is_feedback(name)`：设置为 fuzzer 反馈源
- `to_covered_bytes(bytes)`：将覆盖点转为 bitmap 供外部工具使用

`BASIC_COVER_FROM_DIFF` 宏批量生成了基于 DiffTest 状态的覆盖率子类，通过 `DiffTestInstrCover` / `DiffTestInstrImmCover` 等硬件暴露的覆盖点数组驱动。

### 8.5 外部 C 接口

`dut.cpp` 暴露了四个 `extern "C"` 函数供外部工具（如 fuzzer）调用：
- `get_cover_number()`：获取反馈覆盖率的总覆盖点数
- `update_stats(icover_bitmap)`：将覆盖率 bitmap 传给外部工具
- `display_uncovered_points()`：打印未覆盖的点
- `set_cover_feedback(name)`：设置反馈覆盖率名称

---

## 9. 命令行参数解析 (CommonArgs)

### 9.1 CommonArgs 结构体

`CommonArgs` 结构体包含 40+ 个字段，覆盖了仿真器的所有可配置参数。关键字段包括：

**仿真控制**：`max_cycles`（最大周期数）、`max_instr`（最大指令数）、`warmup_instr`（预热指令数）、`stat_cycles`（统计打印间隔）、`seed`（随机种子）

**镜像与内存**：`image`（可执行镜像路径，默认 `/dev/zero`）、`flash_bin`（Flash 二进制）、`ram_size`（仿真内存大小）、`gcpt_restore`（GCPT 恢复镜像）、`overwrite_nbytes`（有效字节数，默认 0xe00）

**调试与追踪**：`enable_waveform` / `enable_waveform_full`（波形转储）、`wave_path`（波形路径）、`enable_ref_trace` / `enable_commit_trace`（追踪输出）、`enable_snapshot` / `snapshot_path`（快照功能）

**DiffTest 配置**：`enable_diff`（是否启用，默认 true）、`--diff=PATH` 直接设置 `difftest_ref_so` 全局变量、`enable_runahead`（runahead 模式）、`enable_fork`（fork 调试模式）

### 9.2 解析实现

`parse_args()` 函数使用 GNU `getopt_long` 进行参数解析，支持短选项（如 `-s`、`-C`、`-I`）和长选项（如 `--seed`、`--max-cycles`、`--diff`）。

值得注意的设计细节：
- 数值参数使用 `atoll_strict()` 进行严格校验，拒绝非数字输入
- `--diff=PATH` 参数直接修改全局变量 `difftest_ref_so`，这是 `AbstractRefProxy::load_handler()` 中的优先路径
- `--fork-interval` 的值会被转换为毫秒（乘以 1000）
- IPC 相关功能在 `ENABLE_IPC` 未定义时会被静默忽略
- waveform 功能在 fork 模式下被自动禁用

---

## 10. Performance Counter 性能计数器基础设施

### 10.1 设计目标

性能计数器系统用于量化 DiffTest 框架本身的运行开销，帮助开发者识别性能瓶颈。它通过 `CONFIG_DIFFTEST_PERFCNT` 编译宏控制是否启用。

### 10.2 计数器定义

```cpp
enum DIFFTEST_PERF {
  perf_difftest_nstep,     // DiffTest 步进调用次数
  perf_difftest_ram_read,  // RAM 读操作次数/字节数
  perf_difftest_ram_write, // RAM 写操作次数/字节数
  perf_flash_read,         // Flash 读操作
  perf_sd_set_addr,        // SD 卡地址设置
  perf_sd_read,            // SD 卡读操作
  perf_jtag_tick,          // JTAG tick 操作
  perf_put_pixel,          // 像素渲染操作
  perf_vmem_sync,          // 虚拟内存同步
  perf_pte_helper,         // 页表项辅助操作
  perf_amo_helper,         // 原子内存操作辅助
  DIFFTEST_PERF_NUM        // 计数器总数
};
```

每个计数器同时追踪调用次数 (`difftest_calls[]`) 和传输字节数 (`difftest_bytes[]`)。

### 10.3 初始化与汇总

`difftest_perfcnt_init()` 使用 `clock_gettime(CLOCK_MONOTONIC)` 记录起始时间戳，重置所有计数器，并调用 `diffstate_perfcnt_init()` 初始化 DiffState 层面的计数器。

`difftest_perfcnt_finish(cycleCnt)` 在仿真结束时：
1. 计算总运行时间（毫秒级精度）
2. 计算仿真速度（KHz = cycles / milliseconds）
3. 打印每个计数器的调用次数、吞吐量（calls/s 和 bytes/s）
4. 分两组打印：DiffState 函数和其他 Difftest 函数

### 10.4 DiffState 计数器

除 `DIFFTEST_PERF` 枚举中的计数器外，系统还通过 `diffstate_perfcnt_init()` 和 `diffstate_perfcnt_finish()` 管理 DiffState 层面的独立计数器（由 `diffstate.cpp` 实现），用于追踪状态同步、比对等操作的性能开销。

---

## 11. 辅助数据结构

### 11.1 SyncState

```cpp
struct SyncState {
  uint64_t sc_fail;  // Store-Conditional 失败计数
};
```

### 11.2 NonRegInterruptPending

用于向参考模型传递非寄存器中断源状态，包括平台级中断请求 (Platform IRP)：MEIP、MTIP、MSIP、SEIP、STIP、VSEIP、VSTIP；AIA 中断源：fromAIAMeip、fromAIASeip；本地计数器溢出中断。

### 11.3 FromAIA

AIA (Advanced Interrupt Architecture) 状态同步结构体，包含 `mtopei`、`stopei`、`vstopei`、`hgeip` 字段。

### 11.4 InterruptDelegate

中断委托配置，包含 `irToHS` 和 `irToVS` 两个布尔值，控制中断是否委托到 HS-mode 或 VS-mode。

### 11.5 RefProxyConfig

```cpp
class RefProxyConfig {
public:
  bool ignore_illegal_mem_access = false;
  bool debug_difftest = false;
  bool enable_store_log = false;
};
```

通过 `update_config` 函数同步到参考模型内部，实现运行时的动态配置切换。

---

## 12. 寄存器名称表与调试输出

`refproxy.h` 中定义了一系列 `static const char*` 数组用于调试输出时的寄存器名称映射：

| 数组名 | 包含内容 |
|--------|---------|
| `regs_name_int` | x0-x31 的 ABI 名称 ($0, ra, sp, gp, tp, t0, ..., t6) |
| `regs_name_csr` | CSR 名称 (mode, mstatus, sstatus, mepc, sepc, ..., medeleg) |
| `regs_name_hcsr` | Hypervisor CSR 名称 (v, mtval2, mtinst, hstatus, ..., vsscratch) |
| `regs_name_fp` | 浮点寄存器名称 (ft0-ft11, fs0-fs11, fa0-fa7) |
| `regs_name_vec` | Vector 寄存器名称 (v0_low, v0_high, ..., v31_high) |
| `regs_name_vec_csr` | Vector CSR 名称 (vstart, vxsat, vxrm, vcsr, vl, vtype, vlenb) |
| `regs_name_fp_csr` | FP CSR 名称 (fcsr) |
| `regs_name_triggercsr` | Trigger CSR 名称 (tselect, tdata1, tinfo) |
| `debug_regs_name` | Debug 寄存器名称 (debug mode, dcsr, dpc, dscratch0, dscratch1) |

---

## 13. 架构设计总结

### 13.1 分层架构

整个参考模型集成框架采用了清晰的分层设计：

```
+---------------------------+
|   NemuProxy / SpikeProxy  |   -- 具体代理层：指定 .so 路径和环境变量
|   / LinkedProxy           |
+---------------------------+
|       RefProxy            |   -- 状态管理层：ref_state_t + 比对逻辑
|                           |         regcpy / compare / display
|                           |         skip_one / guided_exec / flash_init
+---------------------------+
|   AbstractRefProxy        |   -- 动态加载层：dlopen / dlsym / 函数指针
|                           |         REF_ALL / REF_OPTIONAL 函数指针声明
+---------------------------+
|   dlopen / dlmopen /      |   -- 系统调用层：操作系统动态链接器
|   dlsym / dlclose         |
+---------------------------+
```

### 13.2 关键设计原则

1. **运行时可切换**：通过 `--diff=PATH` 命令行参数，无需重新编译即可切换参考模型
2. **向后兼容**：REF_OPTIONAL 机制确保旧版参考模型仍可使用
3. **多核隔离**：`dlmopen(LM_ID_NEWLM)` 实现了核间状态的完全隔离
4. **宏驱动**：API 声明、加载、检查均通过宏自动展开，减少手动维护成本
5. **静态/动态双模式**：`LINKED_REFPROXY_LIB` 宏支持编译期链接和运行时加载两种模式
6. **防御性编程**：所有可选 API 调用前均进行 null-check，避免崩溃

### 13.3 扩展性分析

新增参考模型只需：(1) 继承 `RefProxy` 类；(2) 定义环境变量名和 .so 文件路径；(3) 在构造函数中调用 `RefProxy` 基类构造函数即可。这一设计使得 DiffTest 框架能够以极低的成本支持新的 ISA 模拟器作为参考模型，而不需要修改框架核心代码。

---

## 14. 关键数据流总结

```
命令行 --diff=PATH
        |
        v
  difftest_ref_so (全局变量) <-- CommonArgs::parse_args()
        |
        v
  AbstractRefProxy::load_handler()
    |
    +-- NUM_CORES > 1 ? dlmopen(LM_ID_NEWLM, ...) : dlopen(...)
    |
    v
  REF_ALL(LoadRefFunc)  -->  dlsym(handler, "difftest_xxx")
  REF_OPTIONAL(LoadRefFunc)  -->  dlsym(handler, "difftest_xxx") [可选]
        |
        v
  ref_init() / ref_init_v2()
        |
        v
  RefProxy::regcpy()  <-->  ref_regcpy(&state, DUT_TO_REF)
  RefProxy::compare()  -->  memcmp(dut_state, ref_state)
  RefProxy::display()  -->  REPORT_DIFFERENCE(...)
```