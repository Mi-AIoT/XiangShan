# SPEC2006 不依赖中断的时间测量机制

> **核心问题**：SPEC2006 不依赖中断，那时间/性能怎么测量？
> **版本锚定**：kunminghu-v3 @ f46464944

---

## 结论先行

**SPEC2006 有两套完全不同的时间测量体系**：

| 场景 | 时间来源 | 中断依赖 |
|------|----------|----------|
| **真实硬件运行** | 程序自己用 `rdtime` CSR 读取时间 | 不依赖中断 |
| **SimPoint 仿真评估** | 仿真器直接数时钟周期（RTL 信号） | 完全不依赖中断 |

**关键洞察**：SimPoint 评估根本不测量"时间"，而是测量"周期数"和"指令数"，然后计算 IPC。

---

## 1. 真实硬件上的 SPEC2006

### 1.1 程序如何测量时间？

**答案**：用 `rdtime` CSR 指令，**不需要中断**。

```c
// SPEC2006 程序测量时间的方式（伪代码）
uint64_t start_time = rdtime();    // 读取当前时间（CSR 指令）
run_benchmark();                    // 运行 benchmark
uint64_t end_time = rdtime();      // 读取结束时间
uint64_t elapsed = end_time - start_time;  // 计算耗时
```

**`rdtime` 指令**：
- RISC-V 的 CSR 读取指令
- 读取 CLINT 的 `mtime` 寄存器
- `mtime` 由 RTC 时钟驱动，每个时钟周期递增
- **不需要中断使能**，只是读取寄存器值

### 1.2 mtime 寄存器

**代码位置**：`src/main/scala/device/TIMER.scala`

```
mtime 寄存器：
- 由 RTC 时钟驱动
- 每个 RTC 时钟周期递增 1
- 是一个只读计数器
- 不需要中断使能就能读取
```

**SimTop 中的 RTC 时钟**（`src/test/scala/top/SimTop.scala:136-143`）：

```scala
val rtcClockDiv = 100           // 主时钟 100 分频
val rtcTickCycle = rtcClockDiv / 2  // 50 周期翻转
val rtcCounter = RegInit(0.U(log2Ceil(rtcTickCycle + 1).W))
rtcCounter := Mux(rtcCounter === (rtcTickCycle - 1).U, 0.U, rtcCounter + 1.U)
val rtcClock = RegInit(false.B)
when (rtcCounter === 0.U) {
  rtcClock := ~rtcClock
}
soc.io.rtc_clock := rtcClock.asClock
```

**关键**：mtime 是一个**自由运行的计数器**，不需要中断使能。

### 1.3 SPEC2006 的终止机制

**SPEC2006 程序通过 `ecall` 指令终止，不是通过中断**。

```
SPEC2006 程序流程：
1. 读取 rdtime 作为起始时间
2. 运行 benchmark（固定迭代次数）
3. 读取 rdtime 作为结束时间
4. 计算耗时
5. 执行 ecall 指令 → 触发 trap → 仿真器检测到 "HIT GOOD TRAP"
```

**代码证据**（`difftest/src/test/csrc/difftest/checkers/traps.cpp:29-32`）：

```cpp
enum {
  EX_ECU,       // ecall from U-mode or VU-mode
  EX_ECS,       // ecall from HS-mode
  EX_ECVS,      // ecall from VS-mode, H-extension
  EX_ECM,       // ecall from M-mode
  // ...
};
```

---

## 2. SimPoint 仿真评估的时间测量

### 2.1 根本区别

**SimPoint 评估不测量"时间"，测量的是"周期数"和"指令数"**。

```
真实硬件：
  时间（秒）= 周期数 / 频率
  性能 = 指令数 / 时间

SimPoint 仿真：
  直接测量：周期数、指令数
  计算：IPC = 指令数 / 周期数
  不涉及：频率、时间（秒）
```

### 2.2 数据来源

**周期数和指令数来自 RTL 信号，通过 DPI-C 接口传递**。

```
RTL（Verilog/SystemVerilog）
    │
    │ DPI-C 接口
    ▼
C++ DiffTest 框架
    │
    │ get_trap_event()
    ▼
┌─────────────────────────────────────────┐
│  DifftestTrapEvent                      │
│  ├── hasTrap: bool      // 陷阱标志    │
│  ├── code: uint64       // 陷阱代码    │
│  ├── pc: uint64         // 陷阱 PC     │
│  ├── cycleCnt: uint64   // 周期计数    │  ← 来自 RTL
│  └── instrCnt: uint64   // 指令计数    │  ← 来自 RTL
└─────────────────────────────────────────┘
```

### 2.3 IPC 计算

**代码位置**：`difftest/src/test/csrc/difftest/difftest.cpp:637-642`

```cpp
void Difftest::display_stats() {
    auto trap = get_trap_event();
    uint64_t instrCnt = trap->instrCnt;   // 来自 RTL 的指令计数
    uint64_t cycleCnt = trap->cycleCnt;   // 来自 RTL 的周期计数
    double ipc = (double)instrCnt / cycleCnt;
    Info("Core-%d instrCnt = %lu, cycleCnt = %lu, IPC = %lf\n",
         state->coreid, instrCnt, cycleCnt, ipc);
}
```

### 2.4 SimPoint 加权

**代码位置**：`scripts/top-down/top_down.py:91`

```python
# 矩阵乘法计算加权平均
weight_metrics = np.matmul(
    vec_weight.values.reshape(1, -1),  # 权重向量 (1, W)
    wl_df.values                        # 性能矩阵 (W, N)
)
```

**流程**：

```
1. 运行每个 SimPoint 采样点（固定指令数，如 2000 万条）
2. 收集每个采样点的 IPC
3. 使用 SimPoint 权重计算加权平均 IPC
4. 计算 SPEC 分数
```

---

## 3. 两种测量方式的对比

### 3.1 真实硬件测量

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    真实硬件 SPEC2006 测量                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  程序代码：                                                                  │
│  uint64_t start = rdtime();        // 读取 mtime 寄存器                    │
│  for (int i = 0; i < N; i++) {     // 运行 N 次迭代                        │
│      benchmark_iteration();                                                │
│  }                                                                         │
│  uint64_t end = rdtime();          // 读取 mtime 寄存器                    │
│  printf("Time: %lu cycles\n", end - start);                                │
│  ecall();                          // 终止程序                              │
│                                                                             │
│  关键点：                                                                   │
│  - rdtime 是 CSR 读取指令，不是中断                                        │
│  - mtime 是自由运行的计数器，不需要中断使能                                │
│  - 程序自己测量时间，不依赖外部计时器                                      │
│  - ecall 终止程序，不是定时器中断                                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 SimPoint 仿真测量

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SimPoint 仿真测量                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  仿真器代码：                                                                │
│  while (instrCnt < max_instr) {    // 运行固定指令数                        │
│      dut_ptr->step();              // 推进一个时钟周期                       │
│      cycleCnt++;                   // 周期计数                              │
│  }                                                                         │
│  ipc = (double)instrCnt / cycleCnt; // 计算 IPC                            │
│                                                                             │
│  关键点：                                                                   │
│  - 不读取 mtime，直接数时钟周期                                            │
│  - 不测量时间（秒），测量周期数                                            │
│  - 运行固定指令数，不是固定时间                                            │
│  - 通过 RTL 信号获取精确计数                                               │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.3 对比总结

| 方面 | 真实硬件 | SimPoint 仿真 |
|------|----------|---------------|
| **时间来源** | rdtime CSR | RTL 周期计数 |
| **计时单位** | 时间（秒/周期） | 周期数 |
| **终止条件** | ecall 指令 | 指令数限制 |
| **中断依赖** | 不依赖 | 不依赖 |
| **频率影响** | 有（时间 = 周期/频率） | 无（直接数周期） |
| **测量精度** | 精确 | 精确 |

---

## 4. 为什么不需要中断？

### 4.1 中断 vs CSR 读取

**中断**：
- 异步事件，由外部设备触发
- 需要中断使能（mstatus.MIE = 1）
- 会打断当前程序执行
- 用于处理异步事件（定时器、外设）

**CSR 读取**：
- 同步指令，由程序主动执行
- 不需要中断使能
- 不打断程序执行
- 用于读取硬件状态（时间、计数器）

**SPEC2006 使用 CSR 读取，不使用中断**。

### 4.2 mtime 的本质

**mtime 是一个自由运行的计数器**：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    mtime 寄存器                                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  RTC 时钟 ──▶ mtime++ ──▶ rdtime 可读取                                   │
│                                                                             │
│  特性：                                                                     │
│  - 自由运行：每个 RTC 时钟周期递增                                         │
│  - 只读：程序只能读取，不能写入                                            │
│  - 不需要中断使能：读取 mtime 不需要 mstatus.MIE = 1                      │
│  - 全局可见：所有特权级都可以读取                                          │
│                                                                             │
│  用途：                                                                     │
│  - 测量时间间隔                                                             │
│  - 与 mtimecmp 比较产生定时器中断（可选）                                  │
│  - 不产生中断时，只是一个计数器                                            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 4.3 定时器中断的触发条件

**定时器中断需要两个条件**：

```
条件 1：mtime >= mtimecmp
条件 2：mstatus.MIE = 1 且 mie.MTIE = 1

SPEC2006：
- mtimecmp 未设置（默认值 0 或最大值）
- mstatus.MIE = 0（中断禁用）
→ 定时器中断永远不会触发
```

---

## 5. SPEC2006 的完整执行流程

### 5.1 真实硬件流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SPEC2006 真实硬件执行流程                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. 程序启动                                                                │
│     └── 从复位向量开始执行                                                  │
│                                                                             │
│  2. 初始化                                                                  │
│     └── 设置栈指针，初始化全局变量                                          │
│                                                                             │
│  3. 测量起始时间                                                            │
│     └── start = rdtime()     // CSR 读取，不需要中断                       │
│                                                                             │
│  4. 运行 benchmark                                                          │
│     └── for (i = 0; i < N; i++) { benchmark(); }                          │
│                                                                             │
│  5. 测量结束时间                                                            │
│     └── end = rdtime()       // CSR 读取，不需要中断                       │
│                                                                             │
│  6. 计算结果                                                                │
│     └── elapsed = end - start                                              │
│                                                                             │
│  7. 终止程序                                                                │
│     └── ecall()              // 触发 trap，仿真器检测到 GOOD TRAP          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 SimPoint 仿真流程

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SPEC2006 SimPoint 仿真流程                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. 加载 checkpoint                                                         │
│     └── 从 SimPoint 采样点恢复                                              │
│                                                                             │
│  2. 运行固定指令数                                                          │
│     └── while (instrCnt < 20_000_000) { step(); }                          │
│                                                                             │
│  3. 收集性能数据                                                            │
│     └── commitInstr, total_cycles (来自 RTL 信号)                          │
│                                                                             │
│  4. 计算 IPC                                                                │
│     └── ipc = commitInstr / total_cycles                                    │
│                                                                             │
│  5. SimPoint 加权                                                           │
│     └── weighted_ipc = Σ(weight_i × ipc_i)                                 │
│                                                                             │
│  6. 计算 SPEC 分数                                                          │
│     └── score = weighted_ipc / reference_ipc                                │
│                                                                             │
│  注意：全程不涉及 mtime、rdtime、中断                                      │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. 代码证据汇总

### 6.1 mtime 驱动

**文件**：`src/test/scala/top/SimTop.scala:136-143`

```scala
val rtcClockDiv = 100           // 主时钟 100 分频
val rtcTickCycle = rtcClockDiv / 2
val rtcCounter = RegInit(0.U(log2Ceil(rtcTickCycle + 1).W))
rtcCounter := Mux(rtcCounter === (rtcTickCycle - 1).U, 0.U, rtcCounter + 1.U)
val rtcClock = RegInit(false.B)
when (rtcCounter === 0.U) {
  rtcClock := ~rtcClock
}
soc.io.rtc_clock := rtcClock.asClock
```

### 6.2 NMI 禁用

**文件**：`src/test/scala/top/SimTop.scala:130`

```scala
l_soc.nmi.foreach(_.foreach(intr => { intr := false.B; dontTouch(intr) }))
```

### 6.3 外部中断源

**文件**：`src/test/scala/top/SimTop.scala:125`

```scala
soc.io.extIntrs := simMMIO.io.interrupt.intrVec
```

**文件**：`src/test/scala/top/SimMMIO.scala:160`

```scala
io.interrupt.intrVec := intrGen.module.io.extra.get.intrVec | uart16550Int
```

### 6.4 周期/指令计数

**文件**：`difftest/src/test/csrc/difftest/difftest.cpp:637-642`

```cpp
auto trap = get_trap_event();
uint64_t instrCnt = trap->instrCnt;   // 来自 RTL
uint64_t cycleCnt = trap->cycleCnt;   // 来自 RTL
double ipc = (double)instrCnt / cycleCnt;
```

---

## 7. 总结

### 7.1 核心结论

**SPEC2006 不依赖中断来测量时间**。

| 问题 | 答案 |
|------|------|
| 程序如何测量时间？ | 用 `rdtime` CSR 读取 mtime 寄存器 |
| mtime 需要中断吗？ | 不需要，是自由运行的计数器 |
| SimPoint 仿真如何测量？ | 直接数 RTL 时钟周期，不读 mtime |
| 程序如何终止？ | 用 `ecall` 指令，不是定时器中断 |
| 中断会影响评估吗？ | 不会，因为中断被禁用且源为零 |

### 7.2 对嵌入式工程师的建议

**理解两套时间体系**：

1. **真实硬件时间**：rdtime → mtime → RTC 时钟
2. **仿真时间**：RTL 周期计数 → DPI-C → C++

**两者独立**：
- 仿真不依赖真实时钟
- 真实硬件不依赖仿真器
- SPEC2006 在两种环境下都能正确测量

### 7.3 常见误解

**误解 1**：SPEC2006 需要定时器中断来测量时间
- **正确**：用 `rdtime` CSR 读取，不需要中断

**误解 2**：没有中断就无法测量时间
- **错误**：mtime 是自由运行的计数器，不需要中断使能

**误解 3**：SimPoint 评估需要测量真实时间
- **错误**：SimPoint 直接测量周期数和指令数，不涉及时间

---

## 总结

### 核心结论

1. **SPEC2006 不依赖中断**：程序用 `rdtime` CSR 读取时间，不需要中断使能
2. **mtime 是自由运行计数器**：不需要中断使能就能读取
3. **SimPoint 不测量时间**：直接从 RTL 信号获取周期数和指令数
4. **程序通过 ecall 终止**：不是通过定时器中断

### 对嵌入式工程师的建议

1. **区分中断和 CSR 读取**：中断是异步事件，CSR 读取是同步指令
2. **mtime 不需要中断**：它是一个自由运行的计数器
3. **SimPoint 测量周期数**：不是测量时间（秒）
4. **ecall 终止程序**：不是定时器中断

---

## 附录：关键代码位置

| 文件 | 行号 | 内容 |
|------|------|------|
| `src/test/scala/top/SimTop.scala` | 136-143 | RTC 时钟驱动 |
| `src/test/scala/top/SimTop.scala` | 130 | NMI 禁用 |
| `src/test/scala/top/SimTop.scala` | 125 | 外部中断连接 |
| `src/test/scala/top/SimMMIO.scala` | 160 | 中断源组合 |
| `src/main/scala/device/TIMER.scala` | - | CLINT 实现 |
| `src/main/scala/device/AXI4IntrGenerator.scala` | 40 | 中断生成器 |
| `difftest/src/test/csrc/difftest/difftest.cpp` | 637-642 | IPC 计算 |
| `difftest/src/test/csrc/difftest/checkers/traps.cpp` | 29-32 | ecall 检测 |
| `scripts/top-down/top_down.py` | 91 | SimPoint 加权 |
