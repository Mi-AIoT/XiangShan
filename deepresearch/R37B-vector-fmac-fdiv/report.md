# R37B - Vector Float MAC & Float Divider 深度解析

## 1. 概述

香山(XiangShan)处理器的浮点运算子系统(Yunsuan)是其向量扩展功能的核心计算引擎。本文聚焦于**向量浮点乘加单元(Vector Float MAC)**和**向量浮点除法/开方单元(Float Divider/Sqrt)**的设计与实现。整个浮点子系统分布在两个主要包(package)中：`yunsuan.fpu` 提供标量(scalar)浮点运算能力，而 `yunsuan.fpulite` 则在此基础上构建了支持多通道(multi-lane)并行处理的向量浮点运算单元。此外，`yunsuan.vector.vfsqrt` 提供了独立的向量开方计算模块。

该子系统支持 IEEE 754 标准定义的三种浮点格式：FP16（5位指数、10位尾数）、FP32（8位指数、23位尾数）和 FP64（11位指数、52位尾数）。在向量模式下，通过宽度为107-bit的统一 CSA(Carry-Save Adder)树，实现 F16 四通道、F32 双通道或 F64 单通道的并行处理，最大化硬件利用率。

---

## 2. 向量浮点乘加单元(Vector FMA Pipeline)

### 2.1 顶层架构

向量FMA的核心实现位于 `yunsuan/fpulite/FloatFMA.scala`（约1795行）。相比标量版本，向量FMA在功能和通道管理上都有显著扩展。

向量FMA支持**9种运算操作**，而标量版本仅支持5种：

| 操作码 | 指令 | 运算表达式 |
|--------|------|-----------|
| 0 | vfmul | A x C |
| 1 | vfmacc | A x C + B |
| 2 | vfnmacc | -(A x C) - B |
| 3 | vfmsac | -(A x C) + B |
| 4 | vfnmsac | (A x C) - B |
| 5 | vfmadd | A x B + C |
| 6 | vfnmadd | -(A x B) - C |
| 7 | vfmsub | A x B - C |
| 8 | vfnmsub | -(A x B) + C |

操作码5-8是向量版本独有的，通过 `swap_fp_a_fp_c` 信号控制操作数A和C的角色交换，使得同一硬件能够同时处理 `a*c+b` 和 `a*b+c` 两种乘加模式。这种设计避免了为两种乘加模式各设一套通路的资源浪费。

### 2.2 三级流水线结构

向量FMA采用**三级流水线**架构：

- **Stage 0 (Fire)**：操作数输入与预处理。接收 `fp_a_i`、`fp_b_i`、`fp_c_i` 三个操作数以及 `fp_format_i` 格式信号。执行特殊值检测(NaN、Inf、Zero)、尾数提取、指数计算。通过 `shiftLeftWithMux` 和 `shiftRightWithMuxSticky` 完成操作数的对齐。

- **Stage 1 (Fire_reg0)**：Booth编码与部分积生成。使用 `BoothEncoderF64F32F16` 执行Radix-4 Booth编码，生成乘法部分积。通过 `CSA_Nto2With3to2MainPipeline` 实现5级流水化的部分积归约树。

- **Stage 2 (Fire_reg1)**：最终加法与格式化。使用 `CSA3to2` 完成最终合并，执行规格化(normalization)、舍入(rounding)和特殊值处理。生成最终的浮点结果和异常标志。

### 2.3 Radix-4 Booth编码与CSA归约树

乘法部分积生成采用经典的Radix-4 Booth编码方案。`BoothEncoderF64F32F16` 将尾数乘法转换为约一半的部分积项数，每项通过编码器生成 {-2, -1, 0, +1, +2} 倍的被乘数。

部分积归约采用 `CSA_Nto2With3to2MainPipeline` 模块，这是一种5级流水化的Carry-Save Adder树。CSA(进位保存加法器)的核心优势在于每一级只产生和(Sum)与进位(Carry)两个中间结果，避免了进位传播延迟，从而在保持高吞吐量的同时控制关键路径延迟。

107-bit宽度的CSA树是为F16四通道并行设计的：每个F16通道需要约24-bit的有效尾数宽度（10-bit尾数 + 隐含位 + 扩展位），4个通道合计约96-bit，加上Booth编码的额外扩展位，总计约107-bit。当运行F32模式时，该CSA树被分为2个53-bit通道；F64模式时则整体作为一个53-bit通道使用。

### 2.4 舍入与异常标志生成

向量FMA实现了完整的IEEE 754舍入支持，包括5种舍入模式：
- **RNE (Round to Nearest, ties to Even)**：向最近偶数舍入
- **RTZ (Round Toward Zero)**：向零截断
- **RDN (Round Down)**：向负无穷舍入
- **RUP (Round Up)**：向正无穷舍入
- **RMM (Round to Nearest, ties to Max Magnitude)**：向最近值舍入，平局时远离零

GRS(Guard, Round, Sticky)三位舍入位在流水线中精确计算。Sticky位通过OR归约树从低位尾数中累积，确保舍入精度。

异常标志包括5个标准位：
- **NV (Invalid)**：操作数为SNaN、Inf x 0等非法运算
- **DZ (Divide by Zero)**：除以零（FMA中不产生）
- **OF (Overflow)**：结果指数超出最大表示范围
- **UF (Underflow)**：结果非规格化或指数下溢
- **NX (Inexact)**：结果经过舍入或指数调整

---

## 3. 向量浮点除法与开方单元

### 3.1 除法器顶层(FloatDivider)

向量除法器位于 `yunsuan/fpulite/FloatDivider.scala`（约2528行）。该模块同时封装了除法和开方两种运算功能。

顶层模块 `FloatDivider` 包含两个核心子模块：
1. **`FloatDividerR64`**：基于R64算法的除法迭代核心
2. **`fpsqrt_r16`**：基于非恢复法(Non-Restoring)的开方迭代核心

顶层通过 `is_sqrt_i` 信号选择运算类型，并通过 `fp_format_i` 选择浮点格式。除法器具有 `outValidAhead3Cycle` 提前输出有效信号，用于唤醒下游流水线级，这是性能优化的关键设计。

### 3.2 R64除法算法

R64(Radix-64)除法算法是该除法器的核心。相比传统的Radix-2或Radix-4除法，R64每周期产生约6位商位(quotient bits)，大幅减少迭代次数：

- **F64**：约10-11次迭代
- **F32**：约5-6次迭代  
- **F16**：约3次迭代

R64算法的关键组件包括：

**预缩放(Prescaling)**：在迭代开始前，通过预缩放技术将除数归一化到特定范围，提高初始商估计精度。`scale_adder` 模块生成初始商近似值。

**迭代核心(fpdiv_r64_block)**：每周期执行一次迭代，使用CSA进行部分余数更新。商位通过 `r4_qds` 模块的变体进行选择。

**商位选择(QDS, Quotient Digit Selection)**：R64使用特殊的商位选择逻辑，基于当前余数的高位和除数的预计算常数，快速确定6位商位。QDS常数通过查找表(ROM或逻辑)预先计算并存储。

### 3.3 非恢复开方算法(Non-Restoring Square Root)

开方运算基于非恢复法，每周期产生2位商位(Radix-4)。核心算法流程如下：

**预处理阶段(Pre-computation)**：
- **PRE_0**：检测操作数特殊值(NaN、Inf、负数)、计算初始余数
- **PRE_1**：通过LZD(Leading Zero Detection)计算前导零数量，确定结果指数偏移

**迭代阶段(Iteration)**：
- 根据浮点格式执行固定次数的迭代（F64=13次、F32=6次、F16=3次）
- 每次迭代使用Radix-4 QDS选择2位商位
- CSA执行余数更新：`rem_next = rem_current - q * d` 的CSA实现

**后处理阶段(Post-computation)**：
- **POST_0**：最终余数检查、舍入处理、结果格式化
- 通过 `sqrt(2)` 优化处理精确结果情况

### 3.4 Radix-4 QDS (Quotient Digit Selection)

QDS模块定义在 `yunsuan/vector/vfsqrt/r4_qds.scala` 中，提供三种变体：

**r4_qds（基础版）**：
- 输入：当前余数 `rem_i`(7-bit) 和4个预计算常数 `m_neg_1/m_neg_0/m_pos_1/m_pos_2`(各7-bit)
- 输出：5-bit one-hot编码的商位 `rt_dig_o`（对应 -2, -1, 0, +1, +2）
- 原理：将余数与各常数相加，通过结果的符号位判断商位

**r4_qds_cg（常数生成器）**：
- 根据部分根(partial root)的位 `a0, a2, a3, a4` 生成QDS所需的4个常数
- 使用Mux1H多路选择器，9个case覆盖所有可能的位组合
- 常数值经过精心设计，确保QDS精度

**r4_qds_spec（推测版）**：
- 预先计算5组QDS常数（分别对应前一步商位为 -2, -1, 0, +1, +2 的情况）
- 使用 `prev_rt_dig_i` 选择正确的常数组
- 通过并行计算消除QDS关键路径延迟，将常数选择与余数计算重叠

### 3.5 向量多通道处理

向量除法器的多通道处理策略与FMA不同。由于除法和开方是迭代运算，无法像FMA那样在一个CSA树上同时处理多个通道：

- **F64模式**：单通道，使用完整的56-bit余数宽度(F64_REM_W)
- **F32模式**：双通道处理，主通道处理lane 0，辅助通道处理lane 1
- **F16模式**：四通道处理，依次处理lane 0-3

通道调度通过 `fpsqrt_vector_r16` 中的状态机控制。除法器的 `frs2_i` 和 `frs1_i` 输入提供了标量寄存器文件的直接转发路径，减少向量-标量混合操作的数据搬运开销。

---

## 4. FPU核心架构（标量版本）

### 4.1 标量FMA (fpu/FloatFMA)

标量FMA位于 `yunsuan/src/main/scala/yunsuan/fpu/FloatFMA.scala`（约1139行）。与向量版本共享核心算法，但有以下关键差异：

- 仅支持5种操作：fmul、fmacc、fnmacc、fmsac、fnmsac
- 无 `swap_fp_a_fp_c` 机制（因为只有 `a*c+b` 模式）
- 单通道设计，CSA树宽度优化为单个F64/F32/F16通道
- 信号命名使用 `fp_a_i`/`fp_b_i`/`fp_c_i`，无向量通道选择信号

标量FMA的辅助函数 `shiftLeftWithMux` 和 `shiftRightWithMuxSticky` 在操作数对齐阶段至关重要。当两个操作数的指数差较大时，较小数的尾数需要右移对齐，右移移出的非零位被累积到Sticky位中。

### 4.2 标量除法器 (fpu/FloatDivider)

标量除法器位于 `yunsuan/src/main/scala/yunsuan/fpu/FloatDivider.scala`（约1720行）。架构与向量版本类似，但：

- 无 `is_vec_i` 信号
- 无 `frs2_i`/`frs1_i` 寄存器转发输入
- 单通道设计，无多lane调度
- `frac_divisor_q` 采用60-bit统一寄存器存储所有格式的除数尾数

### 4.3 标量开方单元 (fpu/fqrt/fpsqrt_r16)

标量开方位于 `yunsuan/src/main/scala/yunsuan/fpu/fqrt/fpsqrt_r16.scala`（约785行）。这是最精简的开方实现：

- 单通道设计，无向量模式
- 相同的Radix-4非恢复算法
- 具有 `outValidAhead3Cycle` 和 `wakeupSuccess` 端口，与除法单元共享唤醒逻辑
- 无多lane调度开销

### 4.4 标量加法器 (fpu/FloatAdder)

标量加法器位于 `yunsuan/src/main/scala/yunsuan/fpu/FloatAdder.scala`（约603行），采用经典的**远路径(Far Path)/近路径(Close Path)**架构：

- **远路径**：当两个操作数符号相反且指数差较大时使用，需要完整的尾数对齐和减法
- **近路径**：当两个操作数符号相同且指数差较小时使用，仅需前导1检测和简单移位

标量加法器支持 `fadd/fsub/fmin/fmax/feq/flt/fle/fsgnj/fclass` 以及扩展的 `minm/maxm/fleq/fltq` 操作。通过 `FloatAdderF32F16MixedPipeline` 处理F16（内部扩展到F32精度），通过 `FloatAdderF64Pipeline` 处理F64。

### 4.5 标量比较器 (fpu/FloatCompare)

独立的浮点比较器位于 `yunsuan/src/main/scala/yunsuan/fpu/FloatCompare.scala`（约111行），实现 `feq/flt/fle/fclass` 操作。比较逻辑直接比较绝对值大小，对NaN情况特殊处理（NaN不等于任何值，包括自身）。

---

## 5. FPU Lite变体差异分析

### 5.1 FMA差异总结

| 特性 | 标量FMA (fpu/) | 向量FMA (fpulite/) |
|------|---------------|-------------------|
| 操作数 | 5种 | 9种（新增fmadd系列） |
| 操作数交换 | 无 | `swap_fp_a_fp_c` 控制 |
| 通道数 | 1 | F16x4 / F32x2 / F64x1 |
| CSA宽度 | 优化为单通道 | 107-bit统一宽度 |
| 接口 | 标量寄存器 | 向量寄存器+通道选择 |

### 5.2 除法器差异总结

| 特性 | 标量Divider (fpu/) | 向量Divider (fpulite/) |
|------|--------------------|-----------------------|
| `is_vec_i` | 无 | 有 |
| 寄存器转发 | 无 | `frs2_i/frs1_i` |
| 通道调度 | 无 | 多lane FSM调度 |
| 除数寄存器 | 60-bit | 60-bit（同） |
| 提前唤醒 | `outValidAhead3Cycle` | 同 |

### 5.3 加法器差异总结

| 特性 | 标量Adder (fpu/) | 向量Adder (fpulite/) |
|------|------------------|---------------------|
| 子模块 | F32F16Mixed + F64 | F32WidenF16x2 + F64Widen + F16x2 |
| Widen操作 | 不支持 | Vfwcvt支持 |
| 通道架构 | 单F32或单F64 | 5个子模块实例并行 |
| F16处理 | 扩展到F32 | 独立F16流水线 |

### 5.4 向量Adder的独特设计

向量加法器(`yunsuan/fpulite/FloatAdder.scala`，约188行)的通道架构最为独特。它实例化了5个加法子模块：

- 2个 `FloatAdderF32WidenF16MixedPipeline`：处理lane 0和lane 1的F32/F16运算
- 1个 `FloatAdderF64WidenPipeline`：处理F64运算
- 2个 `FloatAdderF16Pipeline`：处理lane 1和lane 3的F16运算

这种设计允许在F16模式下同时处理4个独立的浮点加法，F32模式下同时处理2个，F64模式下处理1个，最大化硬件利用率。

---

## 6. 开方运算的详细实现

### 6.1 向量开方顶层 (vfsqrt/fpsqrt_vector_r16)

位于 `yunsuan/vector/vfsqrt/fpsqrt_vector_r16.scala`（约1604行）的顶层控制器管理整个开方运算流程。

**FSM状态机**：
- `PRE_0`：接收输入、检测特殊值、初始化
- `PRE_1`：LZD计算、指数预处理、初始化余数和商寄存器
- `ITER`：迭代计算，通过掩码(mask)控制多lane的迭代进度
- `POST_0`：最终舍入、结果格式化、输出

**多lane掩码机制**：
- F16模式：4个独立掩码，每个lane独立迭代3次
- F32模式：2个掩码，每个lane独立迭代6次
- F64模式：1个掩码，迭代13次

掩码机制允许不同lane在不同周期完成迭代，无需等待所有lane同步。

### 6.2 计算核心 (vfsqrt/fpsqrt_r16_block)

位于 `yunsuan/vector/vfsqrt/fpsqrt_r16_block.scala`（约2886行）的计算核心是最复杂的模块。

**余数更新逻辑**：
每次迭代中，余数通过CSA更新：
```
rem_next = rem_current + q_digit * (2 * partial_root + q_digit * d)
```
其中 `q_digit` 是QDS选择的商位（-2, -1, 0, +1, +2），`partial_root` 是当前部分根，`d` 是被开方数的尾数。

CSA将加法转换为Sum和Carry两个向量，避免进位传播。在迭代过程中，余数以CSA格式(Sum + Carry)保存，仅在需要与QDS常数比较时临时合并。

**推测QDS(Speculative QDS)**：
由于QDS常数依赖于前一步的商位，而前一步商位需要当前迭代的余数才能确定，这形成了循环依赖。`r4_qds_spec` 模块通过并行计算5种可能的QDS结果，然后用前一步商位作为选择信号来打破这个循环。这将QDS的关键路径从"余数计算->商位选择->常数确定"缩短为"余数计算->Mux选择"。

### 6.3 特殊值处理

开方运算的特殊值处理规则：

| 输入 | 结果 | 异常 |
|------|------|------|
| NaN | quiet NaN | NV |
| +Inf | +Inf | 无 |
| 负数 | NaN | NV |
| +0 | +0 | 无 |
| 负零 | -0 | 无 |
| 正非规格化数 | 正常结果 | UF/NX |

当操作数为特殊值时，FSM跳过迭代直接进入POST阶段输出预计算结果。

---

## 7. IEEE 754合规性详细分析

### 7.1 舍入模式支持

香山浮点子系统完整支持IEEE 754-2008定义的全部5种舍入模式，通过 `rm_i` (Rounding Mode)输入信号选择。舍入逻辑在FMA和除法/开方的后处理阶段实现。

GRS三位舍入位的计算是精度保证的关键：
- **G (Guard)**：结果最低有效位之后的第一位
- **R (Round)**：Guard位之后的一位
- **Sticky**：Round位之后所有位的逻辑或(OR-reduction)

在FMA中，Sticky位通过 `shiftRightWithMuxSticky` 在操作数对齐时累积，确保右移移出的所有位都被正确记录。

### 7.2 NaN处理

系统区分两种NaN类型：
- **SNaN (Signaling NaN)**：尾数最高位为0（quiet bit = 0），任何涉及SNaN的运算都产生NV异常
- **QNaN (Quiet NaN)**：尾数最高位为1（quiet bit = 1），传播但不产生异常（除非是无效运算）

**NaN传播规则**：当运算结果为NaN时（如Inf - Inf、0 x Inf），输出为该格式的默认quiet NaN（尾数最高位置1，其余位为0）。

**Canonical NaN**：对于FP16/FP32/FP64，canonical NaN定义为符号位为0、指数全1、尾数最高位为1、其余位为0的值。

### 7.3 非规格化数(Denormal Number)支持

系统支持非规格化数(Denormalized Number / Subnormal Number)的输入和输出：

- **输入处理**：非规格化数的指数被视为最小正规数的指数（exp=1），隐含位为0（而非正常的1）。通过 `is_den_i` 信号检测。
- **输出处理**：当运算结果指数下溢到非规格化范围时，尾数相应右移，同时设置UF和NX异常标志。
- **Flush-to-Zero模式**：在某些配置下，非规格化输入可被刷为零以加速处理。

### 7.4 溢出与下溢

- **Overflow (OF)**：结果指数超过最大可表示值。根据舍入模式，结果可能饱和到最大有限数或Inf。
- **Underflow (UF)**：结果非规格化或在非规格化前检测到精度损失。UF标志与NX标志配合使用。

### 7.5 除零与无效运算

- **DZ (Divide by Zero)**：仅在除法运算中除数为零时产生，结果为+-Inf。
- **NV (Invalid)**：Inf x 0、0 x Inf、Inf - Inf、Inf + (-Inf)、SNaN参与运算、负数开方等。

---

## 8. 性能优化技术

### 8.1 提前唤醒(Early Wakeup)

除法器和开方单元都实现了 `outValidAhead3Cycle` 信号，在实际结果输出前3个周期发出有效信号。这允许下游流水线级提前开始数据准备，减少整体冒险惩罚。

### 8.2 推测计算(Speculative Computation)

在QDS中，通过并行计算5种可能的商位选择结果，用前一步结果作为选择信号。这消除了商位选择的关键路径依赖，允许更高的时钟频率。

### 8.3 掩码驱动迭代

向量开方中，不同lane可以独立完成各自的迭代次数。F16模式的lane不需要等待F64模式的lane完成13次迭代，这种异步完成机制显著提高了多格式混合场景的吞吐量。

### 8.4 根号2精确优化

当被开方数恰好为2的幂或接近特定值时，开方结果可以通过直接查表或简单计算得到，跳过完整的迭代过程。`sqrt(2)` 优化确保这些常见情况的快速路径。

---

## 9. 源文件位置索引

### 9.1 向量浮点单元 (fpulite/)

| 文件 | 行数 | 功能 |
|------|------|------|
| `yunsuan/fpulite/FloatFMA.scala` | ~1795 | 向量FMA（9操作、多lane） |
| `yunsuan/fpulite/FloatDivider.scala` | ~2528 | 向量除法器/开方器 |
| `yunsuan/fpulite/FloatAdder.scala` | ~188 | 向量加法器（5子模块实例） |
| `yunsuan/fpulite/fqrt/fpsqrt_r16.scala` | ~1576 | 向量开方核心 |

### 9.2 标量浮点单元 (fpu/)

| 文件 | 行数 | 功能 |
|------|------|------|
| `yunsuan/fpu/FloatFMA.scala` | ~1139 | 标量FMA（5操作、单通道） |
| `yunsuan/fpu/FloatDivider.scala` | ~1720 | 标量除法器/开方器 |
| `yunsuan/fpu/FloatAdder.scala` | ~603 | 标量加法器（Far/Close Path） |
| `yunsuan/fpu/FloatCompare.scala` | ~111 | 标量比较器 |
| `yunsuan/fpu/fqrt/fpsqrt_r16.scala` | ~785 | 标量开方单元 |

### 9.3 向量开方独立实现 (vector/vfsqrt/)

| 文件 | 行数 | 功能 |
|------|------|------|
| `yunsuan/vector/vfsqrt/fpsqrt_vector_r16.scala` | ~1604 | 向量开方顶层FSM |
| `yunsuan/vector/vfsqrt/fpsqrt_r16_block.scala` | ~2886 | 开方计算核心（CSA+QDS） |
| `yunsuan/vector/vfsqrt/r4_qds.scala` | ~188 | Radix-4 QDS三种变体 |

---

## 10. 总结

香山处理器的向量浮点MAC与除法/开方子系统展现了高度优化的微架构设计。通过统一的CSA树宽度(107-bit)适配三种浮点格式，通过Radix-4 Booth编码和流水化CSA归约树实现高吞吐量FMA，通过R64除法算法和推测QDS实现高效除法运算，通过非恢复法和掩码驱动的多lane调度实现灵活的向量开方。标量版本(fpu/)与向量版本(fpulite/)之间的模块化设计使得代码复用和独立优化成为可能，同时保持了完整的IEEE 754合规性。

该子系统在面积与性能之间的平衡策略值得特别关注：107-bit CSA树的统一宽度设计虽然在F64模式下存在部分硬件浪费，但消除了格式切换的复杂多路选择逻辑，简化了控制路径，这种trade-off在实际芯片面积评估中被证明是合理的。推测QDS和提前唤醒等优化技术则从时序角度提升了整体流水线效率，使得该浮点子系统能够满足香山处理器高性能向量运算的需求。
