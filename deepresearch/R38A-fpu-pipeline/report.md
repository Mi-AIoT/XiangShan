# R38A — XiangShan 标量浮点运算流水线（Scalar FPU Pipeline）深度分析报告

---

## 1. 引言

XiangShan 处理器的浮点运算单元（FPU）是其标量后端（Scalar Backend）的核心组成部分之一，负责执行 RISC-V F/D/H 扩展指令集所定义的浮点算术、比较、转换及分类指令。XiangShan 的 FPU 设计采用**两层架构**：外层为后端集成层（Backend Integration Layer），位于 `xiangshan.backend.fu.fpu` 与 `xiangshan.backend.fu.wrapper` 包中，负责与后端发射队列（Issue Queue）、寄存器堆（Register File）及重排序缓冲区（ROB）的对接；内层为核心计算层（Core Computation Layer），位于 `yunsuan.fpu` 包中（Yunsuan 是中国科学院计算技术研究所开发的高性能算术 IP 库），实现了各类浮点运算的流水线化数据通路。本报告将从功能单元集成、FMA 流水线、格式转换、Recoded Format、异常标志生成和舍入模式支持等维度展开深入分析。

---

## 2. FPU 功能单元在后端中的集成

### 2.1 标量浮点执行单元配置

XiangShan 后端定义了五个主要的标量浮点功能单元配置（FuConfig），它们分布在三个浮点执行单元（FEX0、FEX1、FEX2）中：

| FuConfig 名称 | fuType | 功能 | 流水线延迟（Cycle） | 输出目标 |
|---|---|---|---|---|
| `FaluCfg` | `FuType.falu` | 浮点加法/减法/比较/分类/FSGNJ | 1 | FP RF |
| `FmacCfg` | `FuType.fmac` | 浮点乘加融合运算 | 3 | FP RF |
| `FcvtCfg` | `FuType.fcvt` | 浮点-整数/浮点-浮点格式转换 | 2 + 1 extra | FP RF + Int RF |
| `FdivCfg` | `FuType.fDivSqrt` | 浮点除法/平方根 | 不确定延迟（UncertainLatency） | FP RF |
| `FcmpCfg` | `FuType.fcmp` | 浮点比较（FEQ/FLT/FLE） | 0 + 3 extra | Int RF |

这三个执行单元的具体配置（取自 `BackendParams.scala`）如下：

```
FEX0: Seq(FaluCfg, FmacCfg, FcvtCfg, FcmpCfg, F2vCfg)
      写端口: FpWB(port=0), IntWB(port=3), VfWB(port=5), V0WB(port=3)
      读端口: FpRD(0,0), FpRD(1,0), FpRD(2,0)

FEX1: Seq(FaluCfg, FmacCfg, FdivCfg)
      写端口: FpWB(port=1)
      读端口: FpRD(3,0), FpRD(4,0), FpRD(5,0)

FEX2: Seq(FaluCfg, FmacCfg, FdivCfg)
      写端口: FpWB(port=2)
      读端口: FpRD(6,0), FpRD(7,0), FpRD(8,0)
```

**关键设计约束**：`FcmpCfg` 和 `FcvtCfg` 必须位于同一执行单元（FEX0），因为它们都需要写入整数寄存器堆（Int RF），而后端只有有限的 IntWB 端口分配给 FEX0。

### 2.2 ExeUnit 内部的分发机制

每个 `ExeUnit`（`ExeUnitImp`）内部包含一个 `Dispatcher` 模块，根据指令的 `fuType` 字段将操作分发到对应的功能单元。对于 FPU 指令，分发逻辑通过 `fuCfg.fuSel(input)` 生成选择信号，确保每条指令只被一个功能单元接收。

分发后的数据通路连接如下（`ExeUnit.scala`，第 172-204 行）：
- 功能单元的 `io.in.bits.data.src` 直接连接到 `ExeUnit` 的输入数据
- FPU 控制信号 `ctrl.fpu` 被传递到功能单元，包含 `fmt`（格式）、`rm`（舍入模式）、`wflags`（是否需要写入 fflags）等字段
- 对于具有流水线延迟的功能单元，`ExeUnit` 还维护了 `ctrlPipe` 和 `dataPipe` 寄存器链，以跟踪流水线中每级的控制和数据信号

### 2.3 功能单元基类层次结构

XiangShan 的 FPU 功能单元继承自以下类层次：

```
FuncUnit (抽象基类)
├── FpPipedFuncUnit (浮点流水线功能单元)
│   ├── FAlu       (浮点加法/比较)
│   ├── FMA        (浮点乘加)
│   ├── FCVT       (浮点格式转换)
│   ├── FCMP       (浮点比较)
│   ├── I2F        (整数转浮点)
│   └── IntFPToVec (整数/浮点转向量)
├── FpNonPipedFuncUnit (浮点非流水线功能单元)
│   └── FDivSqrt   (浮点除法/平方根)
```

**`FpPipedFuncUnit`**（`FpPipedFuncUnit.scala`）继承自 `FuncUnit` 和 `HasPipelineReg`，提供内置的流水线寄存器支持。其关键特性：
- `latency` 从 `FuConfig` 的 `latency.latencyVal` 获取
- 提供 `FpFuncUnitAlias` trait，简化 FPU 控制信号的提取（如 `fp_fmt`、`rm`、`fuOpType`）
- `outCtrl` 和 `outData` 分别从流水线寄存器链的末端取出，代表输出级的控制和数据

**`FpNonPipedFuncUnit`**（`FpNonPipedFuncUnit.scala`）用于不确定延迟的功能单元（如 FDivSqrt），采用 `DataHoldBypass` 保持输入数据直到输出有效，不使用固定流水线延迟。

### 2.4 FPU 数据模块基类

`FPUSubModule`（`FPUSubModule.scala`）是所有 FPU 子模块的抽象基类，定义了统一的 I/O 接口：

```scala
io.in.src   : Vec[3, UInt(64.W)]  // 最多 3 个 64 位源操作数
io.in.fpCtrl: FPUCtrlSignals       // FPU 控制信号
io.in.rm    : UInt(3.W)           // 动态舍入模式
io.out.data  : UInt(64.W)          // 64 位结果
io.out.fflags: UInt(5.W)           // 5 位异常标志
```

舍入模式的最终确定逻辑为：`rm = Mux(instRm =/= "b111", instRm, frm)`，即当指令编码的 rm 字段不是动态模式（0b111）时使用指令指定的舍入模式，否则使用 CSR 中的 `frm` 寄存器值。

---

## 3. FMA（Fused Multiply-Add）流水线

### 3.1 架构概述

XiangShan 的 FMA 单元由两部分构成：
- **外层包装**：`FMA` 类（`src/main/scala/xiangshan/backend/fu/wrapper/FMA.scala`），继承自 `FpPipedFuncUnit`，延迟为 3 周期（`CertainLatency(3)`）
- **核心计算**：`FloatFMA` 类（`yunsuan/src/main/scala/yunsuan/fpu/FloatFMA.scala`），实现完整的 3 操作数融合乘加运算

`FloatFMA` 支持五种操作模式，由 `FmaOpCode` 编码：

| 操作码 | 指令 | 功能 |
|---|---|---|
| `fmul` | FMUL | result = fp_a * fp_b |
| `fmacc` | FMADD | result = fp_a * fp_b + fp_c |
| `fnmacc` | FNMADD | result = -(fp_a * fp_b + fp_c) |
| `fmsac` | FMSUB | result = fp_a * fp_b - fp_c |
| `fnmsac` | FNMSUB | result = -(fp_a * fp_b - fp_c) |

### 3.2 流水线阶段划分

`FloatFMA` 采用 **4 级流水线**（fire_reg0、fire_reg1、fire_reg2），具体处理流程如下：

**Stage 0（fire 驱动，组合逻辑）：**
1. **符号处理**：根据操作类型对 fp_a 和 fp_c 的符号位进行取反
   - `fp_a_is_sign_inv`：FNMSUB 和 FNMACC 时取反
   - `fp_c_is_sign_inv`：FMSUB 和 FNMACC 时取反
2. **指数运算**：计算乘积指数 `Eab = Ea + Eb - bias + rshiftBasic`，以及与加数指数 `Ec` 的差值 `rshift_value = Eab - Ec`
3. **Booth 编码乘法**：使用 `BoothEncoderF64F32F16` 模块对尾数进行 Radix-4 Booth 编码，生成部分积（Partial Products）
4. **CSA 压缩树**：通过 `CSA_Nto2With3to2MainPipeline` 模块（5 级 pipeline）将多个部分积压缩为 Sum/Carry 对
5. **指数对齐右移**：对加数 `fp_c` 的尾数进行指数对齐右移，生成 Guard/Round/Sticky 位（GRS 三元组）
6. **特殊值检测**：并行检测 NaN（包括 Canonical NaN 和 SNaN）、Infinity、Zero 等特殊值

**Stage 1（fire_reg0 驱动）：**
1. **部分积加法**：将 CSA 压缩后的 Sum/Carry 与对齐后的加数尾数进行三输入加法（`CSA3to2`），得到初步的乘加结果
2. **符号确定**：根据加法结果是否为负，确定最终结果的符号
3. **前导零检测（LZD）**：对加法结果进行 LZA（Leading Zero Anticipator）和 TZD（Trailing Zero Detection），为左移规格化做准备

**Stage 2（fire_reg1 驱动）：**
1. **左移规格化**：根据 LZD 结果对尾数进行左移规格化
2. **舍入处理**：基于 GRS 位和舍入模式进行舍入，计算 `round_add1` 信号
3. **指数修正**：根据规格化左移量和舍入进位修正指数
4. **结果多路选择**：根据特殊值优先级选择最终结果（NaN > Infinity > Overflow > Zero > Normal）

### 3.3 Booth 编码乘法器

`BoothEncoderF64F32F16` 是一个统一的 Booth 编码器，支持三种浮点格式：
- **FP64**：53 位有效数字，生成 27 个部分积
- **FP32**：24 位有效数字（内部扩展为 53 位宽度），实际有效部分积 13 个
- **FP16**：11 位有效数字（内部扩展为 53 位宽度），实际有效部分积 6 个

编码采用 Radix-4 Booth 方案，每个编码位产生 4 种选择之一：+0、+1x、+2x、-1x、-2x。

### 3.4 CSA 压缩树

`CSA_Nto2With3to2MainPipeline` 模块实现了可配置的 (N:2) 压缩器，结合 CSA4to2 和 CSA3to2 两种压缩单元：
- CSA4to2：4 输入压缩为 2 输出（Sum + Carry）
- CSA3to2：3 输入压缩为 2 输出
- 在第 `pipeLevel`（默认为 5）级插入流水线寄存器以控制关键路径延迟

### 3.5 特殊值处理优先级

当操作数包含特殊值时，结果按以下优先级确定：

1. **NaN**（最高优先级）：任一操作数为 NaN 时，结果为 Canonical NaN（尾数高位为 1）
   - 若存在 SNaN 或 (Inf * 0)，则设置 NV（Invalid Operation）异常标志
2. **Infinity**：乘积为 Infinity 时，结果为 Infinity
   - 若出现 Inf - Inf 或 (Inf * 0) 等矛盾情况，结果为 NaN 并设置 NV
3. **Overflow**：指数溢出时，根据舍入模式选择返回 Infinity 或最大有限数
4. **Zero**：当 fp_a 或 fp_b 为零时，结果直接为 fp_c（或其符号变体）
5. **Normal Result**（最低优先级）：正常运算结果

---

## 4. 浮点加法器（FloatAdder）流水线

### 4.1 架构概述

`FloatAdder`（`yunsuan/fpu/FloatAdder.scala`）支持 FP16/FP32/FP64 三种格式的加法/减法运算，以及 min/max/compare/FSGNJ/FCLASS 等辅助运算。其内部包含两条并行路径：

- **Far Path**（`FloatAdderF64Pipeline` / `FarPathF32WidenF16MixedPipeline`）：当两个操作数的指数差值大于 1 时使用
- **Close Path**（`ClosePathF32WidenF16MixedPipeline`）：当两个操作数的指数差值在 0 或 1 时使用，此时需要进行尾数的精确对齐和加法

### 4.2 F64 加法流水线

`FloatAdderF64Pipeline` 内部包含：
1. **Stage 1**：操作数预处理、指数比较、路径选择（Far/Close）
2. **Stage 2**（由 `fire` 寄存器控制）：执行实际的尾数加法/减法，生成中间结果和 fflags
3. 特殊值检测与处理（NaN、Infinity、Zero）在 Stage 1 并行进行

### 4.3 FP16/FP32 混合流水线

`FloatAdderF32F16MixedPipeline` 将 FP16 操作数扩展为 FP32 格式进行处理：
- FP16 的指数从 5 位扩展为 8 位（通过前补 3 个零）
- FP16 的尾数从 10 位扩展为 23 位（通过后补 13 个零）
- 结果在输出时从 FP32 格式截取回 FP16 格式

### 4.4 辅助运算

当 `hasMinMaxCompare = true` 时，加法器模块同时支持以下操作（在同一流水线中完成）：

| 操作 | 编码 | 功能 |
|---|---|---|
| `fmin` / `fmax` | `FaddOpCode.fmin/fmax` | 返回两个操作数中较小/较大者 |
| `feq` / `flt` / `fle` | `FaddOpCode.feq/flt/fle` | 浮点比较，结果写入整数寄存器 |
| `fsgnj` / `fsgnjn` / `fsgnjx` | 对应 FaddOpCode | 符号注入操作 |
| `fclass` | `FaddOpCode.fclass` | 浮点分类，返回 10 位分类掩码 |
| `fminm` / `fmaxm` | `FaddOpCode.fminm/fmaxm` | Minimum Magnitude / Maximum Magnitude |

---

## 5. 整数到浮点转换（Int-to-FP）和浮点到整数转换（FP-to-Int）

### 5.1 I2F（Integer to Float）

`I2F` 类（`src/main/scala/xiangshan/backend/fu/wrapper/I2F.scala`）封装了 `FPCVT` 模块，配置为 `isI2F = true`。其处理流程：

1. **Stage 1**：输入整数的符号扩展和宽度选择（32 位或 64 位）
2. **Stage 2**：调用 `yunsuan.scalar.IntToFP` 核心模块，该模块包含：
   - `IntToFP_prenorm`：前规格化，计算前导零数量
   - `IntToFP_postnorm`：后规格化，调整指数和尾数
   - 为 FP32、FP64、FP16 三种格式各实例化一个 `IntToFP` 子模块，通过 `typeOut` 选择输出
3. **Stage 3**：结果打包（`FPU.box`）和 fflags 输出

`IntToFP` 的核心算法：
- 对输入整数求前导零（LZD）
- 将整数左移到最高有效位
- 根据目标格式的指数偏移（bias）调整指数
- 通过 `postnorm` 模块处理舍入和规格化

### 5.2 FCVT（Float Conversion）

`FCVT` 类（`src/main/scala/xiangshan/backend/fu/wrapper/FCVT.scala`）封装了 `FPCVT` 模块，配置为 `isI2F = false`。支持以下转换：

| 转换类型 | opcode 编码 | 示例指令 |
|---|---|---|
| FP32 -> Int32 | widen=0, sew=10 | FCVT.W.S |
| FP64 -> Int64 | widen=0, sew=11 | FCVT.L.D |
| FP32 -> FP64 | widen=01, sew=10 | FCVT.D.S |
| FP64 -> FP32 | widen=10, sew=11 | FCVT.S.D |
| FP32 -> FP16 | widen=10, sew=10 | FCVT.H.S |
| FP16 -> FP64 | widen=11, sew=01 | FCVT.D.H |
| FCVTMOD.W.D | isFcvtmod=true | FCVTMOD.W.D |

FCVT 的特殊舍入模式处理：
- **RTZ**（Round Toward Zero）：`isRtz = opcode(2) & opcode(1)`，用于 FP-to-Int 转换
- **Rod**（Round to Odd）：`isRod = opcode(2) & !opcode(1) & opcode(0)`，用于 FROUND 指令
- **动态舍入**：`isFrm = !isRtz && !isRod`，使用 CSR 中的 frm 值

输出处理中的关键点：
- FP32 结果需要在高 32 位填入全 1（boxing）以表示 NaN-boxing
- FP16 结果在高 48 位填入全 1
- FP-to-Int 结果如果是 32 位，需要符号扩展到 64 位

### 5.3 F2I 跨执行单元数据通路

浮点到整数转换的结果可能需要跨执行单元传递（因为 FcvtCfg 和 FcmpCfg 写 Int RF）。`ExeUnitImp` 通过 `I2FDataIn` 和 `F2IDataIn` 端口实现跨单元数据转发（`ExeUnit.scala`，第 305-335 行），使用 valid/ready 握手协议确保数据的正确传递。

---

## 6. 浮点到浮点格式转换（FP-to-FP Conversion, F<->D）

### 6.1 架构

FP-to-FP 格式转换（如 FCVT.D.S、FCVT.S.D）统一由 `FCVT` 模块处理，内部调用 `yunsuan.vector.VectorConvert.CVT64` 的核心转换逻辑。转换方向通过 `widen` 字段区分：

- `widen = 01`：窄到宽（如 FP32->FP64、FP16->FP32），称为 "widen convert"
- `widen = 10`：宽到窄（如 FP64->FP32、FP32->FP16），称为 "narrow convert"
- `widen = 11`：特殊转换（如 FP16->FP64、FP64->FP16）

### 6.2 FP16 <-> FP32 转换

当进行 FP16 <-> FP32 转换时，`FloatAdderF32F16MixedPipeline` 中的 FP16 处理路径展示了转换的核心逻辑：

**FP16 -> FP32 扩展**：
```scala
val fp_a_16as32 = Cat(
  io.fp_a(15),              // sign bit
  Cat(0.U(3.W), io.fp_a(14,10)), // exponent: 5 bits zero-extended to 8 bits
  Cat(io.fp_a(9,0), 0.U(13.W))   // significand: 10 bits left-shifted to 23 bits
)
```

**FP32 -> FP16 截取**：
```scala
out_fp32_to_fp16_or_fp16_reg = Cat(
  0.U(16.W),
  out_fp32_reg(31),        // sign
  out_fp32_reg(27,23),     // exponent: 8 bits truncated to 5 bits
  out_fp32_reg(22,13)      // significand: 23 bits truncated to 10 bits
)
```

### 6.3 NaN-boxing 处理

XiangShan 遵循 RISC-V 的 NaN-boxing 规范：
- FP64 结果：64 位直接输出
- FP32 结果：低 32 位有效，高 32 位填充全 1
- FP16 结果：低 16 位有效，高 48 位填充全 1

在 FMA 单元的输出中可以看到：
```scala
io.fp_result := Mux(
  is_fp64_reg2,
  fp_result_f64,
  Mux(is_fp32_reg2,
    Cat(Fill(32, 1.U), fp_result_f32),
    Cat(Fill(48, 1.U), fp_result_f16)
  )
)
```

### 6.4 Canonical NaN 检测

在进入计算单元之前，XiangShan 在 wrapper 层检测 Canonical NaN：

```scala
val fp_aIsFpCanonicalNAN = fp_fmt === VSew.e32 && !src0.head(32).andR ||
                           fp_fmt === VSew.e16 && !src0.head(48).andR
```

这意味着：当 FP32 指令的源操作数高 32 位不全为 1 时，该操作数被视为 Canonical NaN；FP16 同理（高 48 位不全为 1）。这个信号被传递到 Yunsuan 核心模块，用于正确的 NaN 处理。

---

## 7. Recoded Format（Recoded 编码格式）处理

### 7.1 设计概述

XiangShan 的 FPU **不使用** IEEE 754 的 recoded encoding（即不使用 Berkeley SoftFloat 或 Rocket Chip 的 recoded format）。相反，它直接在 IEEE 754 标准编码格式下进行运算。

从 `FPU.scala` 中可以看到：
```scala
case class FType(expWidth: Int, precision: Int) {
  val sigWidth = precision - 1
  val len = expWidth + precision
}
val f16 = FType(5, 11)   // 5-bit exponent, 11-bit precision (1+10)
val f32 = FType(8, 24)   // 8-bit exponent, 24-bit precision (1+23)
val f64 = FType(11, 53)  // 11-bit exponent, 53-bit precision (1+52)
```

这些参数直接对应 IEEE 754 标准的位宽分配。

### 7.2 运算内部的非标准处理

虽然输入/输出使用 IEEE 754 编码，但 FPU 内部的运算过程中使用了一些**非 recoded 但类似 recoded 的内部表示**：

1. **指数修正（Exponent Fix）**：在 `FloatFMA` 中，指数被修正为 `E_fix = Cat(E.head(n-1), !E_is_not_zero | E(0))`，这使得非规格化数的指数与规格化数的最低非零指数对齐，简化了后续的指数差值计算
2. **尾数隐含位展开**：将隐含的 1 位显式加入尾数中，如 `fp_a_significand = Cat(Ea_is_not_zero, fp_a_tail)`，其中 `Ea_is_not_zero` 作为隐含位
3. **特殊值编码检测**：直接使用 IEEE 754 的指数全 1/全 0 模式检测 NaN、Infinity、Zero、Subnormal

### 7.3 FliTable（浮点立即数加载表）

`FliTable.scala` 中定义了 `FliHTable`、`FliSTable`、`FliDTable`，用于 `FLI`（Float Load Immediate）指令的立即数解码。这些表直接存储 IEEE 754 编码的浮点常量，覆盖从 -1.0、最小正数、2 的幂次到 +Inf 和 Canonical NaN 的所有值。

---

## 8. Exception Flag（异常标志）生成

### 8.1 fflags 结构

XiangShan 的 FPU 遵循 RISC-V 定义的 5 位 `fflags` 寄存器格式：

| 位索引 | 名称 | 含义 |
|---|---|---|
| bit[4] | NV | Invalid Operation（无效操作） |
| bit[3] | DZ | Division by Zero（除零） |
| bit[2] | OF | Overflow（溢出） |
| bit[1] | UF | Underflow（下溢） |
| bit[0] | NX | Inexact（不精确） |

### 8.2 各运算的 fflags 生成

#### 8.2.1 FMA 的异常标志生成

FMA 单元在 Stage 2 末尾并行计算所有五种异常：

**NV（Invalid Operation）**：
```scala
val has_nan_f64_is_NV_reg2 = has_snan_f64 | (fp_a_is_inf_f64 & fp_b_is_zero_f64) | (fp_a_is_zero_f64 & fp_b_is_inf_f64)
```
SNaN 参与运算、Inf * 0、或 Inf + (-Inf) 时触发。

**OF（Overflow）**：
```scala
val is_overflow_f64_reg2 = exponent_overflow_f64
// exponent_overflow = exp_head(1) | exp_tail.andR
```
当最终指数超过最大表示范围时触发，同时设置 NX。

**UF（Underflow）**：
```scala
val UF_f64 = NX_f64 & exponent_is_min_f64 & (!exponent_add_1_f64 | !(guard_f64 & round_add1_uf_f64))
```
当结果为非规格化数且丢失精度时触发。

**NX（Inexact）**：
```scala
val NX_f64 = guard_f64 | round_f64 | sticky_f64_reg2
```
当 Guard、Round 或 Sticky 位中任一位为 1 时触发。

#### 8.2.2 除法器的异常标志生成

除法器（`FloatDividerR64`）在 `POST_1` 状态生成异常标志：

```scala
val fflags_invalid_operation = op_invalid_div_q  // Inf/Inf, 0/0, SNaN
val fflags_div_by_zero = divided_by_zero_q       // 非零/0
val fflags_overflow = overflow_f64_0 & !is_special_case
val fflags_underflow = res_is_denormal & !carry & inexact & !is_special
val fflags_inexact = (overflow | inexact) & !is_special
```

#### 8.2.3 加法器的异常标志生成

加法器（`FloatAdder`）的异常标志相对简单：
- NV：SNaN 参与运算或 Inf + (-Inf)
- 其他标志在 Far/Close Path 的流水线中生成

### 8.3 fflags 的写入路径

功能单元产生的 fflags 通过以下路径最终写入 CSR：
1. `FuncUnit.io.out.bits.res.fflags` → 从功能单元输出
2. `ExeUnit` 通过 `Mux1H(fuOutValidOH, fuOutresVec.map(_.fflags))` 选择当前有效的 fflags
3. 通过 `io.out.bits.toRob.bits.fflags` 传递到 ROB
4. ROB 在指令退休时将 fflags 累加到 CSR 的 `fflags` 寄存器

---

## 9. Rounding Mode（舍入模式）支持

### 9.1 五种 RISC-V 标准舍入模式

XiangShan 完整支持 RISC-V 定义的五种舍入模式：

| 编码 | 名称 | 功能 |
|---|---|---|
| 000 | RNE | Round to Nearest, ties to Even |
| 001 | RTZ | Round toward Zero |
| 010 | RDN | Round Down (toward -Infinity) |
| 011 | RUP | Round Up (toward +Infinity) |
| 100 | RMM | Round to Nearest, ties to Max Magnitude |
| 111 | Dynamic | 使用 CSR.frm 中的值 |

### 9.2 舍入模式的获取逻辑

在 `FpFuncUnitAlias` trait 中：
```scala
protected val instRm = inCtrl.fpu.getOrElse(0.U.asTypeOf(new FPUCtrlSignals)).rm
protected val rm = Mux(instRm =/= "b111".U, instRm, frm)
```

其中 `frm` 来自 CSR 文件，`instRm` 来自指令编码。当指令编码的 rm 字段为 111 时使用动态模式，否则使用指令指定的静态模式。

### 9.3 FMA 中的舍入实现

FMA 在 Stage 2 中实现完整的舍入逻辑：

```scala
val round_add1_f64 = RNE_reg2 & (guard_f64 & (fraction_result_no_round_f64_reg2(0) | round_f64 | sticky_f64_reg2)) |
  RDN_reg2 & sign_result_temp_f64_reg2 & (guard_f64 | round_f64 | sticky_f64_reg2) |
  RUP_reg2 & !sign_result_temp_f64_reg2 & (guard_f64 | round_f64 | sticky_f64_reg2) |
  RMM_reg2 & guard_f64 |
  adder_is_negative_f64_reg2 & !guard_f64 & !round_f64 & !sticky_f64_reg2
```

**RNE**（Round to Nearest, ties to Even）：当 Guard=1 且（Round=1 或 Sticky=1 或 LSB=1）时向上舍入
**RTZ**（Round toward Zero）：永不向上舍入（round_add1 = 0）
**RDN**（Round Down）：仅当结果为负数且有非零的 GRS 位时向上舍入
**RUP**（Round Up）：仅当结果为正数且有非零的 GRS 位时向上舍入
**RMM**（Round to Max Magnitude）：当 Guard=1 时向上舍入

最后一项 `adder_is_negative & !guard & !round & !sticky` 处理负数的精确值情况。

### 9.4 除法器中的舍入实现

除法器（`FloatDividerR64`）的舍入在 `POST_0` 和 `POST_1` 阶段实现：

```scala
val quo_need_rup_f64_0 =
  ((rm_q === RM_RNE) & ((round_bit & sticky_bit) | (guard_bit & round_bit))) |
  ((rm_q === RM_RDN) & ((round_bit | sticky_bit) & out_sign_q)) |
  ((rm_q === RM_RUP) & ((round_bit | sticky_bit) & ~out_sign_q)) |
  ((rm_q === RM_RMM) & round_bit)
```

除法器使用 QDS（Quotient Digit Selection）算法，每次迭代产生 2 位商，在 FP64 下需要约 8 次迭代。

### 9.5 加法器中的舍入实现

加法器的 Far Path 和 Close Path 各自实现舍入逻辑，最终通过 `is_far_path_reg` 选择。舍入模式信号直接传入子模块。

---

## 10. 浮点除法器和平方根（FDivSqrt）

### 10.1 架构

`FDivSqrt` 类（`FDivSqrt.scala`）继承自 `FpNonPipedFuncUnit`，封装 `FloatDivider` 模块。`FloatDivider` 内部包含两个子模块：

1. `FloatDividerR64`：基于 SRT（Sweeney-Robertson-Tocher）除法算法的浮点除法器
2. `fpsqrt_r16`：基于 R16 算法的浮点平方根计算单元

两者通过 `is_sqrt_i` 信号选择使用。

### 10.2 除法器流水线

`FloatDividerR64` 的状态机包含 6 个状态：

| 状态 | 功能 |
|---|---|
| PRE_0 | 接收操作数，检测特殊情况，计算初始值 |
| PRE_1 | 分数预处理（缩放除数和被除数） |
| PRE_2 | 初始化 CSA 和 QDS 输入 |
| ITER | 迭代商生成（每周期 2 位） |
| POST_0 | 后处理：规格化、溢出/下溢检测 |
| POST_1 | 最终结果输出 |

对于 FP64 除法，迭代次数约为 8 次（`iter_num_needed = 8`），FP32 约 3 次，FP16 约 1 次。支持 early finish 机制：当结果为 NaN、Infinity 或精确零时，可跳过迭代直接进入 POST 状态。

### 10.3 UncertainLatency 唤醒机制

由于除法器的延迟不确定，`FDivSqrt` 使用 `needUncertainWakeup` 机制与发射队列交互。`io.outValidAhead3Cycle` 信号在结果实际有效前 3 个周期提前发出唤醒信号，使发射队列可以提前调度后续指令。

---

## 11. 浮点比较和分类（FCMP / FCLASS）

### 11.1 FCMP 模块

`FCMP` 类（`FCMP.scala`）封装 `FloatCompare` 模块（`yunsuan/fpu/FloatCompare.scala`），支持以下操作：

| 操作 | FcmpOpCode | 功能 |
|---|---|---|
| FEQ | `isFeq` | 浮点相等比较，结果写入 Int RF |
| FLT | `isFlt` | 浮点小于比较 |
| FLE | `isFle` | 浮点小于等于比较 |
| FCLASS | `isFclass` | 浮点分类 |

### 11.2 FCLASS 输出编码

`FCLASS` 指令输出 10 位分类掩码，对应 RISC-V 规范：

| bit | 分类 |
|---|---|
| 9 | -Infinity |
| 8 | 负规格化数 |
| 7 | 负非规格化数 |
| 6 | -0 |
| 5 | +0 |
| 4 | 正非规格化数 |
| 3 | 正规格化数 |
| 2 | +Infinity |
| 1 | Signaling NaN |
| 0 | Quiet NaN |

对于 Canonical NaN，FCLASS 直接返回 `resultQNan = (1 << 9).U`（bit 9 = Quiet NaN）。

---

## 12. IntFPToVec 与 FLI 指令

### 12.1 IntFPToVec 模块

`IntFPToVec`（`IntFPToVec.scala`）处理整数/浮点到向量寄存器的移动操作（VMV.X.S、VMV.S.X、VFUNARY0 等），同时实现 `FLI`（Float Load Immediate）指令。

### 12.2 FLI 指令实现

`FLI` 指令通过三个查找表（`FliHTable`、`FliSTable`、`FliDTable`）实现，每张表包含 32 个预定义的浮点常量值。编码器根据 `fuOpType(4,0)` 作为索引从表中选取对应的浮点常量。

例如，`FliSTable` 中包含：
- 索引 0：-1.0（0xBF80）
- 索引 16：+1.0（0x3F80）
- 索引 30：+Inf（0x7F80）
- 索引 31：Canonical NaN（0x7FC0）

---

## 13. 源文件位置汇总

### 13.1 后端集成层（XiangShan Backend）

| 文件 | 路径 | 功能 |
|---|---|---|
| FPU.scala | `src/main/scala/xiangshan/backend/fu/fpu/FPU.scala` | FPU 类型定义、NaN-boxing |
| FpPipedFuncUnit.scala | `src/main/scala/xiangshan/backend/fu/fpu/FpPipedFuncUnit.scala` | 流水线功能单元基类 |
| FpNonPipedFuncUnit.scala | `src/main/scala/xiangshan/backend/fu/fpu/FpNonPipedFuncUnit.scala` | 非流水线功能单元基类 |
| FPUSubModule.scala | `src/main/scala/xiangshan/backend/fu/fpu/FPUSubModule.scala` | FPU 子模块抽象基类 |
| Bundles.scala | `src/main/scala/xiangshan/backend/fu/fpu/Bundles.scala` | Frm、Fflags 类型定义 |
| FliTable.scala | `src/main/scala/xiangshan/backend/fu/fpu/FliTable.scala` | FLI 指令常量表 |
| IntFPToVec.scala | `src/main/scala/xiangshan/backend/fu/fpu/IntFPToVec.scala` | Int/FP 到向量寄存器移动 |
| FuConfig.scala | `src/main/scala/xiangshan/backend/fu/FuConfig.scala` | 所有 FPU FuConfig 定义 |
| ExeUnit.scala | `src/main/scala/xiangshan/backend/exu/ExeUnit.scala` | 执行单元集成框架 |

### 13.2 FPU Wrapper 层

| 文件 | 路径 | 功能 |
|---|---|---|
| FALU.scala | `src/main/scala/xiangshan/backend/fu/wrapper/FALU.scala` | 浮点加法器包装 |
| FMA.scala | `src/main/scala/xiangshan/backend/fu/wrapper/FMA.scala` | 浮点乘加包装 |
| FDivSqrt.scala | `src/main/scala/xiangshan/backend/fu/wrapper/FDivSqrt.scala` | 浮点除法/平方根包装 |
| FCMP.scala | `src/main/scala/xiangshan/backend/fu/wrapper/FCMP.scala` | 浮点比较包装 |
| FCVT.scala | `src/main/scala/xiangshan/backend/fu/wrapper/FCVT.scala` | 浮点格式转换包装 |
| I2F.scala | `src/main/scala/xiangshan/backend/fu/wrapper/I2F.scala` | 整数转浮点包装 |
| BackendParams.scala | `src/main/scala/xiangshan/backend/BackendParams.scala` | 执行单元配置 |

### 13.3 Yunsuan 核心计算层

| 文件 | 路径 | 功能 |
|---|---|---|
| FloatFMA.scala | `yunsuan/src/main/scala/yunsuan/fpu/FloatFMA.scala` | FMA 核心实现（含 Booth 编码器、CSA 压缩树） |
| FloatAdder.scala | `yunsuan/src/main/scala/yunsuan/fpu/FloatAdder.scala` | 浮点加法器核心实现（Far/Close Path） |
| FloatDivider.scala | `yunsuan/src/main/scala/yunsuan/fpu/FloatDivider.scala` | 浮点除法器核心实现（SRT 算法） |
| FloatCompare.scala | `yunsuan/src/main/scala/yunsuan/fpu/FloatCompare.scala` | 浮点比较核心实现 |
| fpsqrt_r16.scala | `yunsuan/src/main/scala/yunsuan/fpu/fqrt/fpsqrt_r16.scala` | 浮点平方根（R16 算法） |
| Convert.scala | `yunsuan/src/main/scala/yunsuan/scalar/Convert.scala` | IntToFP/FPCVT 核心实现 |
| IntToFP.scala | `yunsuan/src/main/scala/yunsuan/scalar/IntToFP.scala` | IntToFP 前/后规格化 |

---

## 14. 总结

XiangShan 的标量 FPU 流水线是一个精心设计的多格式、多操作的浮点运算系统，其主要特点包括：

1. **统一的多格式支持**：FMA、加法器、除法器均原生支持 FP16/FP32/FP64 三种格式，通过 `fp_fmt` 信号在运行时选择
2. **分层架构**：后端集成层（wrapper）与核心计算层（Yunsuan）解耦，便于独立优化和验证
3. **高效的 FMA 设计**：采用 Radix-4 Booth 编码 + CSA 压缩树 + 4 级流水线，在 3 周期内完成融合乘加运算
4. **完整的异常处理**：严格遵循 RISC-V 规范的 5 位 fflags 生成和累积机制
5. **灵活的舍入支持**：所有运算单元均支持全部 5 种 RISC-V 标准舍入模式
6. **不确定延迟处理**：除法器使用 UncertainLatency + 3 周期提前唤醒机制，与后端调度无缝集成
