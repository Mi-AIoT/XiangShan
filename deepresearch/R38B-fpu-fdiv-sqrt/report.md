# R38B - FPU Divider & Square Root: 香山处理器浮点除法与平方根单元深度解析

## 概述

香山(XiangShan) RISC-V 处理器的浮点除法(Division)与平方根(Square Root)单元位于 `yunsuan` 子项目中，由三个变体实现：`fpu`(标量专用)、`fpulite`(标量+向量兼容)和 `vector`(纯向量)。两者均采用 **SRT Radix-4** 余数恢复算法，通过 Quotient Digit Selection (QDS) 每周期生成一个冗余商数位(radix-4 即每周期有效 2 bit)，并以 Carry-Save Adder (CSA) 维持冗余余数表示以规避长进位链。除法器每周期迭代 3 个子步(3-digit-per-cycle)，平方根器每周期迭代 1 个子步(1-digit-per-cycle)，这一设计差异直接源于两者 QDS 阈值生成逻辑的复杂度不同。以下按功能域逐一展开分析。

---

## 1. Division Algorithm: SRT Radix-4 除法算法

### 1.1 算法基础

除法采用 **SRT (Sweeney, Robertson, Tocher) Radix-4** 算法，商数数字集(Quotient Digit Set)为 `{-2, -1, 0, +1, +2}`。每产生一个 radix-4 数字即等效输出 2 bit 有效商数位。冗余表示允许部分余数(Partial Remainder)在非唯一编码空间内存在，从而放宽了 QDS 的精度要求——仅需判断余数落在哪个区间即可，无需精确值。

核心迭代公式为：

```
w[j+1] = 4 * w[j] - q[j+1] * d
```

其中 `w[j]` 为第 j 步的部分余数，`q[j+1]` 为本步商数数字，`d` 为归一化后的除数。实际实现中除数 `d` 以 CSA 冗余形式 `(f_r_s, f_r_c)` 存储，`q[j+1] * d` 的计算通过预计算 `-2d, -d, +d, +2d` 的 CSA 形式并由 QDS 输出选择完成。

### 1.2 3-Digit-Per-Cycle 迭代

除法器的核心计算块 `fpdiv_r64_block`（定义于 `FloatDivider.scala` 约第 1015-1513 行）采用 **3 个子步(sub-stage)** 流水线化设计，每子步产生 1 个 radix-4 商数位，即每周期产出 6 bit 有效商数。

三个子步标记为 `s0`、`s1`、`s2`，结构相似但 QDS 策略不同：

- **子步 s0**（首子步）：使用完整 QDS 计算，余数已知精确值（或接近精确），可直接判断商数位。
- **子步 s1、s2**（推测子步）：由于 CSA 加法延迟，无法在当周期内获得精确余数用于 QDS。因此采用 **Speculative QDS (推测 QDS)** 策略：预先计算所有 5 种可能的商数位对应的余数更新结果，等 QDS 结果延迟到达后通过 MUX 选择正确路径。

迭代次数由浮点格式决定：

| 格式   | 有效尾数位 | 需迭代次数 | 每周期商数位 | 总迭代周期 |
|--------|-----------|-----------|-------------|-----------|
| FP64   | 52 + 1    | 8 (iter_num_needed=8) | 6 bit | ceil(53/6) = 9 cycles |
| FP32   | 23 + 1    | 3 (iter_num_needed=3) | 6 bit | ceil(24/6) = 4 cycles |
| FP16   | 10 + 1    | 1 (iter_num_needed=1) | 6 bit | 1 cycle |

**注意**：`iter_num_needed` 定义于 `FloatDivider.scala` 中的 `FloatDividerR64` 类：
```scala
val iter_num_needed = Mux(fp_format_q_is_fp16, 1.U, Mux(fp_format_q_is_fp32, 3.U, 8.U))
```

### 1.3 QDS 架构

除法器的 QDS 模块 `r4_qds_v2`（定义于 `FloatDivider.scala` 约第 1514-1571 行）支持三种可配置架构（由 `arch` 参数选择）：

**架构 0 (Comparator-Based)**：使用比较器直接判断余数与预设阈值的关系。阈值为常数 `M_POS_2, M_POS_1, M_NEG_0, M_NEG_1`。

**架构 1 (Comparator-Chain)**：使用链式比较器，适合高精度场景，延迟较大但面积较优。

**架构 2 (Sign-Detection/Adder，**默认**)**：利用加法器符号位检测。阈值常数为：
- `M_POS_2 = 12`（即 `4.5d` 在冗余表示中的近似）
- `M_POS_1 = 3`
- `M_NEG_0 = -4`
- `M_NEG_1 = -13`

该架构通过 `rem_i + M_XX` 的加法符号位快速判断余数区间，延迟仅需一个加法器延迟，适合高频设计。

**Speculative QDS (`r4_qds_v2_spec`)**：定义于 `FloatDivider.scala` 约第 1573 行之后。该模块为 s1/s2 子步预计算所有 5 种可能结果：

```
对于每种可能的 prev_rt_dig (5 种 one-hot 编码):
  预计算 rem + (-2d + threshold), rem + (-d + threshold), ...
  提取符号位形成 qds_sign_spec
```

等上游 QDS 结果 `prev_rt_dig` 到达后通过 `Mux1H` 选择正确的 `qds_sign`，再编码为 5-bit one-hot 商数位输出。

### 1.4 除数预处理

除法器在进入迭代前对除数进行预处理：

1. **归一化**：通过 LZD (Leading Zero Detector) 检测除数前导零数量，左移至规格化位置，确保 QDS 阈值的正确性。
2. **Power-of-2 优化**：当除数为 2 的幂次时（尾数全零），跳过迭代直接计算：商 = 被除数 >> 除数指数差，仅需调整指数和舍入。
3. **除数 CSA 形式预计算**：计算 `{d, 2d}` 的 CSA 形式 `(f_r_s, f_r_c)` 供后续迭代使用。

---

## 2. Square Root Algorithm: SRT Radix-4 平方根算法

### 2.1 算法基础

平方根同样采用 **SRT Radix-4** 算法，商数数字集为 `{0, 1, 2, 3, 4}`（或偏移表示 `{0, +1, +2, +3, +4}`，与除法的有符号数字集不同）。每步产生 1 个 radix-4 数字（有效 2 bit），因此迭代周期是除法的约 2 倍。

核心迭代公式：

```
w[j+1] = 4 * w[j] - 2 * q_partial[j] * q[j+1] - q[j+1]^2 * 4^(-j-1)
```

其中 `q_partial[j]` 是已产生的部分商数（Partial Root），`q[j+1]` 是本步选择的商数数字。与除法不同，平方根的 QDS 阈值不是常数，而是随部分商数动态变化——这就是 **Constant Generator QDS** 的由来。

### 2.2 Constant Generator QDS

平方根的 QDS 模块 `r4_qds_cg`（定义于 `/home/agi/workspace/gitwork/XiangShan/yunsuan/src/main/scala/yunsuan/vector/vfsqrt/r4_qds.scala`）通过纯组合 LUT 生成动态阈值：

输入为部分商数的若干位 `{a0, a2, a3, a4}`（其中 `a0` 是最高位），输出为 4 个 7-bit 阈值 `{m_neg_1, m_neg_0, m_pos_1, m_pos_2}`。

阈值生成逻辑通过 `Mux1H` 实现，共 9 个条目（`!a0_i` 时 8 种组合 + `a0_i` 时 1 种兜底）：

| a0 | a2 a3 a4 | m_neg_1  | m_neg_0  | m_pos_1  | m_pos_2  |
|----|----------|----------|----------|----------|----------|
| 0  | 000      | 0001_101 | 0000_100 | 1111_100 | 1110_100 |
| 0  | 001      | 0001_110 | 0000_101 | 1111_100 | 1110_010 |
| 0  | 010      | 0010_000 | 0000_110 | 1111_100 | 1110_000 |
| 0  | 011      | 0010_001 | 0000_110 | 1111_100 | 1110_000 |
| 0  | 100      | 0010_010 | 0000_110 | 1111_010 | 1101_110 |
| 0  | 101      | 0010_100 | 0001_000 | 1111_010 | 1101_100 |
| 0  | 110      | 0010_110 | 0001_000 | 1111_000 | 1101_100 |
| 0  | 111      | 0010_111 | 0001_000 | 1111_000 | 1101_010 |
| 1  | xxx      | 0010_111 | 0001_000 | 1111_000 | 1101_010 |

这些阈值编码为 7-bit 二进制补码形式，供 QDS 判断当前余数应产生哪个商数数字。

### 2.3 基本 QDS 比较器

`r4_qds` 模块（同样定义于 `r4_qds.scala`）将 7-bit 余数 `rem_i` 与 4 个阈值相加，检测进位/符号位（`.head(1)`），得到 `qds_sign[3:0]`，再通过位比较编码为 5-bit one-hot 商数位 `rt_dig_o`：

```scala
rt_dig_o := Cat(
  qds_sign(1,0) === "b11",  // -2
  qds_sign(1,0) === "b10",  // -1
  qds_sign(2,1) === "b10",  // +1
  qds_sign(3,2) === "b10",  // +2
  qds_sign(3,2) === "b00"   // 0 (implicit: not -2,-1,+1,+2)
)
```

### 2.4 Speculative QDS for Square Root

`r4_qds_spec`（定义于 `r4_qds.scala` 第 105-187 行）用于平方根推测子步。与除法的推测 QDS 类似，它预计算 5 种可能的 CSA 裁剪值（`sqrt_csa_val_neg_2_msbs_i` 至 `sqrt_csa_val_pos_2_msbs_i`）对应的 QDS 结果，等 `prev_rt_dig_i` 到达后通过 `Mux1H` 选择。

每种推测路径将余数 `rem_i` 与对应的 CSA 裁剪值和阈值相加：

```scala
qds_sign_spec(4) := Cat(
  (rem_i + sqrt_csa_val_neg_2_msbs_i + Cat(m_pos_2_neg_2_i, 0.U(2.W))).head(1),
  ...
)
```

注意阈值通过 `Cat(m_xx, 0.U(2.W))` 左移 2 bit 以匹配 CSA 值的定点格式。

### 2.5 1-Digit-Per-Cycle 迭代

平方根每周期仅迭代 1 个子步（而非除法的 3 个），原因在于：

1. **动态阈值**：每步的 QDS 阈值取决于当前部分商数，无法像除法那样预计算多步的常数阈值。
2. **CSA 延迟**：余数更新需要 CSA 加法 + QDS 判断，两者构成关键路径，难以在单周期内插入更多子步。

迭代次数：

| 格式   | 有效尾数位 | 需迭代次数 | 每周期商数位 | 总迭代周期 |
|--------|-----------|-----------|-------------|-----------|
| FP64   | 52 + 1    | 13 (F64_ITER_NUM) | 4 bit | 13 cycles |
| FP32   | 23 + 1    | 6 (F32_ITER_NUM)  | 4 bit | 6 cycles |
| FP16   | 10 + 1    | 3 (F16_ITER_NUM)  | 4 bit | 3 cycles |

余数宽度定义：
- `F64_REM_W = 56`（FP64 迭代余数宽度）
- `F32_REM_W = 28`
- `F16_REM_W = 16`

---

## 3. Iteration Pipeline & Convergence: 迭代流水线与收敛

### 3.1 除法 FSM

除法器由 6 状态有限状态机控制：

```
PRE_0 → PRE_1 → PRE_2 → ITER → POST_0 → POST_1
```

- **PRE_0**：操作数捕获、特殊值检测（NaN/Inf/Zero/Denorm）、除数归一化、CSA 预计算。
- **PRE_1**：被除数归一化（LZD + 左移）、初始化部分余数。
- **PRE_2**：除数 power-of-2 检测、sqrt(2) 常数加载（用于平方根）、最终迭代准备。
- **ITER**：核心迭代，每周期 3 个子步（s0, s1, s2），持续 `iter_num_needed` 个周期。
- **POST_0**：冗余余数→非冗余转换（CSA 到 CLA）、余数符号检测（判断是否需要回退）、商数调整。
- **POST_1**：舍入（Rounding）、指数修正、FFlags 生成、输出有效信号。

### 3.2 平方根 FSM

平方根器由 4 状态 FSM 控制：

```
PRE_0 → PRE_1 → ITER → POST_0
```

- **PRE_0**：操作数捕获、特殊值检测、LZD 归一化。
- **PRE_1**：初始部分余数设置、`SQRT_2_WITH_ROUND_BIT` 常数准备（用于偶数指数情况下的初始减法）。
- **ITER**：核心迭代，每周期 1 个子步，持续 `Fxx_ITER_NUM` 个周期。
- **POST_0**：余数符号检测、舍入、输出。

### 3.3 余数检测与收敛判断

迭代结束后的收敛判断逻辑：

1. **冗余→非冗余转换**：将 CSA 形式的最终余数 `(f_r_s, f_r_c)` 通过 CLA 加法器转换为标量值。
2. **余数符号检测**：若余数为负，说明上一步过度减法，需要回退：
   - 除法：商数减 1，余数加回除数。
   - 平方根：商数减 1，余数通过特定公式恢复。
3. **余数归零检测**：若最终余数为零，说明除法/平方根可整除，此时可设置 sticky bit 为 0。

### 3.4 提前有效信号

除法器和平方根器均实现了 `outValidAhead3Cycle` 信号，在 POST_0 状态开始前 3 个周期即发出有效预告，用于下游流水线的 **wake-up** 机制，减少不必要的阻塞等待。

---

## 4. Latency Characteristics: 延迟特性

### 4.1 完整延迟分解

以 FP64 除法为例的典型延迟分解：

| 阶段   | 功能                          | 周期数  |
|--------|------------------------------|---------|
| PRE_0  | 操作数捕获、特殊值检测         | 1       |
| PRE_1  | 被除数归一化                   | 1       |
| PRE_2  | 除数预处理                     | 1       |
| ITER   | 核心迭代 (ceil(53/6)=9 cycles) | 9       |
| POST_0 | 冗余→非冗余、余数符号          | 1       |
| POST_1 | 舍入、FFlags、输出             | 1       |
| **总计** |                              | **14 cycles** |

FP32 除法：PRE(3) + ITER(4) + POST(2) = **9 cycles**
FP16 除法：PRE(3) + ITER(1) + POST(2) = **6 cycles**

以 FP64 平方根为例：

| 阶段   | 功能                          | 周期数  |
|--------|------------------------------|---------|
| PRE_0  | 操作数捕获、归一化             | 1       |
| PRE_1  | 初始余数设置                   | 1       |
| ITER   | 核心迭代 (13 cycles)           | 13      |
| POST_0 | 舍入、输出                     | 1       |
| **总计** |                              | **16 cycles** |

FP32 平方根：PRE(2) + ITER(6) + POST(1) = **9 cycles**
FP16 平方根：PRE(2) + ITER(3) + POST(1) = **6 cycles**

### 4.2 流水线吞吐量

除法器和平方根器为 **非流水线(multi-cycle)** 设计——同一时刻只能处理一个操作。连续的除法/平方根操作需串行执行，前一操作完成后才能接受下一操作。这是因为核心迭代逻辑被 FSM 独占，CSA/CLA 硬件无法在多个操作间共享。

### 4.3 向量模式延迟

`fpulite` 变体支持向量模式：

- **4x FP16**：4 个 FP16 操作并行执行，但迭代次数与单个 FP16 相同（3 cycles），因为所有通道共享相同的 QDS 和余数更新逻辑（仅初始值和舍入逻辑按通道独立）。
- **2x FP32**：2 个 FP32 操作并行。
- **1x FP64**：退化为标量模式。

向量模式的延迟与标量 FP16 相同，但吞吐量提高 4 倍（同时处理 4 个 FP16 操作）。

---

## 5. Special Case Handling: 特殊值处理

### 5.1 NaN (Not a Number)

**SNaN (Signaling NaN)**：
- 检测条件：指数全 1 + 尾数最高位为 0 + 尾数非零。
- 处理：设置 `invalid_operation` FFlag，输出 QNaN（尾数最高位置 1 的默认 NaN）。

**QNaN (Quiet NaN)**：
- 检测条件：指数全 1 + 尾数最高位为 1。
- 处理：传播操作数中的 QNaN（优先选择第一个操作数的 NaN）。
- 除法：`anything / NaN = QNaN`，`NaN / anything = QNaN`。
- 平方根：`sqrt(NaN) = QNaN`。

**NaN 输入检测**在 FSM 的 PRE_0 阶段完成，检测到 NaN/Inf/Zero 后跳过迭代直接进入 POST 阶段输出结果。

### 5.2 Infinity (无穷大)

**除法**：
- 除数为 Inf：结果为 Inf（符号由操作数符号 XOR 确定），无 FFlag（正常结果）。
- 被除数为 Inf：除数非 Inf 时结果为 Inf；除数也为 Inf 时结果为 NaN + `invalid_operation`。

**平方根**：
- `sqrt(Inf) = Inf`，无 FFlag。

### 5.3 Zero (零)

**除法**：
- 被除数为零 + 除数非零：结果为 ±0（符号按 IEEE 754 规则确定）。
- 除数为零 + 被除数非零：结果为 ±Inf + `div_by_zero` FFlag。
- 0/0：结果为 NaN + `invalid_operation`。

**平方根**：
- `sqrt(0) = ±0`（保留被除数符号）。

### 5.4 Denormalized Numbers (非规格化数)

非规格化数（Denorm/Denormal）的处理流程：

1. **输入检测**：在 PRE_0 阶段检测输入操作数是否为非规格化数（指数全零 + 尾数非零）。
2. **LZD 归一化**：对非规格化尾数进行前导零检测（Leading Zero Detector），计算左移量。
3. **左移至规格化位置**：将尾数左移至规格化位置，同时调整指数（设为 1，即最小规格化指数）。
4. **迭代**：使用归一化后的操作数执行标准 SRT 迭代。
5. **输出调整**：若结果指数下溢，设置 `underflow` 和 `inexact` FFlags。

除法器支持在 PRE_1 阶段（一个额外周期）处理非规格化操作数的归一化，这是 PRE_1 阶段存在的主要原因之一。

### 5.5 Power-of-2 除数优化

当除数为 2 的幂次（尾数全零，仅指数非零）时，除法退化为简单的移位操作：

```
result = 被除数 << (被除数指数 - 除数指数)
```

无需进入迭代阶段，直接跳到 POST 阶段进行舍入。此优化在 `PRE_2` 阶段检测，可显著降低常见场景（如除以 2, 4, 8 等）的延迟。

---

## 6. IEEE 754 Compliance: IEEE 754 标准合规性

### 6.1 舍入模式 (Rounding Modes)

实现完全支持 IEEE 754 定义的 5 种舍入模式，由 `rm_i[2:0]` 输入编码：

| 编码 | 模式            | 全称                          | 行为                          |
|------|----------------|-------------------------------|-------------------------------|
| 000  | RNE            | Round to Nearest, Even        | 最近偶数舍入（默认）           |
| 001  | RTZ            | Round Toward Zero             | 截断（向零舍入）               |
| 010  | RDN            | Round Down (toward -Inf)      | 向负无穷舍入                   |
| 011  | RUP            | Round Up (toward +Inf)        | 向正无穷舍入                   |
| 100  | RMM            | Round to Nearest, Max Mag     | 最近最大幅度舍入               |

### 6.2 Guard/Round/Sticky Bits

舍入决策基于三个额外精度位：

- **Guard (G)**：最低有效位之后的第一位。
- **Round (R)**：Guard 之后的第二位。
- **Sticky (S)**：R 之后所有位的 OR 归约（表示是否有任何非零低位）。

舍入逻辑位于 `FloatDivider.scala` 约第 817-878 行，根据舍入模式和 G/R/S 组合决定是否在结果最低有效位上加 1（round up）。

关键舍入公式（以 RNE 为例）：
```
round_up = G & (R | S)   // 当恰好位于两数中间时，向偶数舍入
```

### 6.3 FFlags 输出

完全符合 IEEE 754 标准的异常标志：

- **invalid_operation**：操作数含 SNaN、0/0、Inf-Inf 等非法操作。
- **div_by_zero**：非零数除以零。
- **overflow**：结果指数超过最大可表示值（精度丢失）。
- **underflow**：结果非零但指数低于最小规格化值（精度可能丢失）。采用检测后上报(detect after rounding)策略。
- **inexact**：结果无法精确表示（需要舍入）。

### 6.4 结果符号

除法结果符号：`sign_a XOR sign_b`（被除数符号 XOR 除数符号）。
平方根结果符号：`sign_a`（平方根始终为非负，输入为负时返回 QNaN + invalid_operation）。

---

## 7. Source File Locations: 源文件清单

### 7.1 核心除法与平方根模块

| 文件路径 | 功能 | 代码行数 | 关键模块 |
|---------|------|---------|---------|
| `yunsuan/src/main/scala/yunsuan/fpu/FloatDivider.scala` | 标量 FPU 除法+平方根顶层 | 2528 | `FloatDivider`, `FloatDividerR64`, `fpdiv_r64_block`, `r4_qds_v2`, `r4_qds_v2_spec` |
| `yunsuan/src/main/scala/yunsuan/fpu/fqrt/fpsqrt_r16.scala` | 标量平方根核心 | 1720 | `fpsqrt_r16` |
| `yunsuan/src/main/scala/yunsuan/fpulite/FloatDivider.scala` | 向量兼容 FPU 除法+平方根顶层 | 2528 | `FloatDivider` (含 `vector_mode_i`) |
| `yunsuan/src/main/scala/yunsuan/fpulite/fqrt/fpsqrt_r16.scala` | 向量兼容平方根核心 | 1576 | `fpsqrt_r16` (含多通道支持) |

### 7.2 QDS 共享模块

| 文件路径 | 功能 | 代码行数 | 关键模块 |
|---------|------|---------|---------|
| `yunsuan/src/main/scala/yunsuan/vector/vfsqrt/r4_qds.scala` | QDS 核心模块库 | 188 | `r4_qds`, `r4_qds_cg`, `r4_qds_spec` |

### 7.3 向量专用模块

| 文件路径 | 功能 |
|---------|------|
| `yunsuan/src/main/scala/yunsuan/vector/VectorFloatDivider.scala` | 向量浮点除法器顶层 |
| `yunsuan/src/main/scala/yunsuan/vector/vfsqrt/fpsqrt_vector_r16.scala` | 向量平方根核心 |
| `yunsuan/src/main/scala/yunsuan/vector/vfsqrt/fpsqrt_r16_block.scala` | 平方根迭代计算块 |

### 7.4 三种变体差异

| 特性 | fpu | fpulite | vector |
|------|-----|---------|--------|
| 标量支持 | FP16/32/64 | FP16/32/64 | -- |
| 向量支持 | 无 | 4xFP16 / 2xFP32 / 1xFP64 | 4xFP16 / 2xFP32 / 1xFP64 |
| 输入端口 | 标量寄存器 | 标量+向量寄存器可选 | 纯向量寄存器 |
| 输出信号 | 标量结果 | 标量/向量结果 MUX | 向量结果 |
| QDS 模块 | 内联定义于 FloatDivider | 内联定义于 FloatDivider | 独立文件 r4_qds.scala |

---

## 总结

香山处理器的 FPU 除法与平方根单元展现了成熟的 SRT Radix-4 迭代架构设计，核心创新包括：

1. **3-digit-per-cycle 除法**：通过推测 QDS (Speculative QDS) 在 CSA 延迟约束下实现每子步 1 个 radix-4 数字的吞吐，使 FP64 除法仅需约 14 个周期。
2. **Constant Generator QDS**：平方根的动态阈值通过纯 LUT 实现，延迟可控，支持 1-digit-per-cycle 迭代。
3. **Power-of-2 快速路径**：除数为 2 的幂次时跳过迭代，显著降低延迟。
4. **多格式复用**：FP16/32/64 共享同一硬件路径，通过 FSM 参数化迭代次数，最大化硬件利用率。
5. **全面 IEEE 754 合规**：5 种舍入模式、完整 FFlags、NaN/Inf/Denorm 处理，确保软件兼容性。

该设计在面积与性能之间取得了良好平衡，适用于 RISC-V 通用处理器的浮点计算需求。
