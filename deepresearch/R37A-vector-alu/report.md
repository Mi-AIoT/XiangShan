The Write tool is unavailable for subagents. The report follows as my final text output.

---

# R37A - Vector ALU 子系统深度解析

## 1. 概述与架构总览

香山处理器的向量算术逻辑单元（Vector ALU）是 RVV（RISC-V Vector Extension）向量定点整数运算通路的核心执行单元，位于 YunSuan（运算器）库的 `yunsuan.vector` 包下。该子系统在 VLEN=128 位（`VIFuParam.VLEN = 128`）的向量宽度约束下工作，通过 XLEN=64 位的双 Lane 架构将 128 位向量数据切分为两个 64 位处理通道。

核心参数定义于 `VIFuParam` 对象：
- `VLEN = 128`：向量寄存器总宽度
- `XLEN = 64`：标量处理宽度
- `VLENB = 16`：向量字节数
- `wVL = 8`：vl 字段宽度
- `wVSTART = 7`：vstart 字段宽度
- `wUOPIDX = 6`：微操作索引宽度
- `wOPCODE = 6`：操作码宽度

整个 Vector ALU 子系统由以下三大计算单元和一个共享的 Opcode 编码层构成：

1. **VIAlu**（整数/定点 ALU）：处理加减、逻辑、移位、比较、饱和、平均、扩展、缩减、掩码等操作
2. **VIMac**（乘法累加器）：处理整数乘法、乘加、定点缩放乘法
3. **VectorIdiv**（向量整数除法器）：处理整数除法和取模
4. **Opcode 编码层**：定义各运算单元的操作码和解码逻辑

所有计算单元共享统一的 `VIFuInput` / `VIFuOutput` 接口，由上层模块根据指令类型选择路由。

---

## 2. VectorALU 架构与操作详解

### 2.1 顶层接口 VIFuInput / VIFuOutput

所有向量 ALU 子模块的输入输出通过统一的 Bundle 传输：

```scala
class VIFuInput extends Bundle {
  val opcode = new VAluOpcode       // 6-bit 操作码
  val info = new VIFuInfo           // 控制信息
  val srcType = Vec(2, UInt(4.W))   // 源操作数类型（0:vs2, 1:vs1）
  val vdType  = UInt(4.W)           // 目标向量寄存器类型
  val vs1 = UInt(128.W)             // 源操作数 1
  val vs2 = UInt(128.W)             // 源操作数 2
  val old_vd = UInt(128.W)          // 原始目标寄存器值
  val mask = UInt(128.W)            // 向量掩码
}
```

`VIFuInfo` 携带了 RVV 标量控制信息：
- `vm`：向量掩码使能位
- `ma`：掩码聚合（mask agnostic）
- `ta`：尾部聚合（tail agnostic）
- `vlmul`：向量长度乘数（3-bit）
- `vl`：向量长度（8-bit）
- `vstart`：向量起始索引（7-bit）
- `uopIdx`：微操作索引（6-bit），用于标识当前处理的 128 位切片
- `vxrm`：定点舍入模式（2-bit）

输出接口极为简洁：

```scala
class VIFuOutput extends Bundle {
  val vd = UInt(128.W)    // 128-bit 向量结果
  val vxsat = Bool()      // 定点饱和标志
}
```

### 2.2 VAluOpcode 操作码体系

VIAlu 定义了 **56 种操作码**（6-bit 编码），涵盖以下类别：

| 操作码值 | 指令 | 类别 |
|---------|------|------|
| 0-1 | vadd / vsub | 整数加/减 |
| 2 | vext | 向量扩展 |
| 3-4 | vadc / vmadc | 带进位加/掩码进位加 |
| 5-6 | vsbc / vmsbc | 带借位减/掩码借位减 |
| 7-14 | vand/vnand/vandn/vxor/vor/vnor/vorn/vxnor | 位逻辑运算 |
| 15-17 | vsll/vsrl/vsra | 移位运算 |
| 18-22 | vmseq/vmsne/vmslt/vmsle/vmsgt | 比较运算 |
| 23-24 | vmin/vmax | 最小/最大值 |
| 25-26 | vmerge/vmv | 合并/移动 |
| 27-28 | vsadd/vssub | 饱和加/减 |
| 29-30 | vaadd/vasub | 平均加/减 |
| 31-32 | vssrl/vssra | 缩放右移 |
| 33-38 | vredsum/vredmax/vredmin/vredand/vredor/vredxor | 归约操作 |
| 39-45 | vcpop/vfirst/vmsbf/vmsif/vmsof/viota/vid | 掩码/计数操作 |
| 46-47 | vmvsx/vmvxs | 标量-向量移动 |
| 48-55 | vbrev/vbrev8/vrev8/vclz/vctz/vrol/vror/vwsll | Zvbb 扩展 |

`VAluOpcode` 类还定义了大量组合判断方法（如 `isBitLogical`、`isShift`、`isReduction` 等），这些方法在硬件解码时被综合为组合逻辑。

### 2.3 VIntFixpTable 解码表

`VIntFixpTable` 使用 QMC（Quine-McCluskey）方法将操作码解码为三路控制信号：`sub`（减法/比较）、`misc`（杂项）、`cmp`（比较）。例如：
- `vadd`：sub=0, misc=0, cmp=0（纯加法）
- `vsub`：sub=1, misc=0, cmp=0（减法）
- `vmseq`：sub=?, misc=0, cmp=1（比较输出掩码）
- `vmadc`：sub=0, misc=0, cmp=1（带进位加，输出 carry 位）

### 2.4 VIAlu 顶层模块

`VIAlu` 模块实现 1-cycle 基础延迟（通过 RegNext 链实现），包含三个子模块并行工作：

1. **Reduction**：归约运算单元
2. **VMask**：掩码运算单元
3. **综合输出选择**：根据 `opcodeS2` 选择 vmvxs（标量提取）、reduction 或 mask 结果

```scala
val vdFinal = Mux(opcodeS2.isVmvxs, vs2ExtS2,
  Mux(opcodeS2.isReduction, vReduAlu.io.out.vd, vMaskAlu.io.out.vd))
```

### 2.5 VIAluFixPoint 新架构

新版定点整数 ALU 采用 **2 级流水线** 架构（`VIAluFixPoint`），将计算分为并行 Stage 0 和统一 Stage 1：

**Stage 0（并行计算）**：
- `VIAluAdder`：64-bit 向量加法器，由 8 个 `Adder_8b` 链式构成，支持加/减/比较/min/max/饱和/平均运算。核心设计特点：
  - 三输入加法器结构（srca + srcb + carry），通过 CSA（Carry-Save Adder）方式减少关键路径
  - 并行 addPlus2 路径处理舍入加 1（用于平均运算的四种舍入模式：RNU/RNE/RDN/ROD）
  - 宽度感知的 carry-in 传播，支持 8/16/32/64-bit SEW

- `VIAluMisc`：64-bit 杂项单元，处理：
  - 符号/零扩展（vf2/vf4/vf8）
  - 位逻辑运算（8 种：and/nand/andn/xor/or/nor/orn/xnor）
  - 动态移位（通过 `foldLeft` 递归构建 barrel shifter）
  - Zvbb 扩展指令：位反转（vbrev/vbrev8/vrev8）、前导零计数（vclz/vctz）、循环移位（vrol/vror）、宽左移（vwsll）
  - PopCount（通过 `CSA3to1` 压缩树实现 8-bit 到 4-bit 计数）
  - Narrow/Clip 操作

**Stage 1（统一输出）**：
- `VIAluFixPointS1` 模块根据操作类型从 Stage 0 的并行结果中选择最终输出
- 对于 Sat 运算，根据有符号/无符号进行溢出检测和饱和截断
- 对于 NClip 运算，使用 `signedMin/signedMax/unsignedMin/unsignedMax` 常量进行窄化截断

### 2.6 Reduction 归约单元

`Reduction` 是 3 级流水线的归约运算单元（VLEN=128, xLen=64），支持：

- `vredsum/vwredsum`：整数归约求和（含宽度扩展变体）
- `vredmax/vredmaxu`：有符号/无符号最大值归约
- `vredmin/vredminu`：有符号/无符号最小值归约
- `vredand/vredor/vredxor`：逻辑归约

**流水线结构**：
- Stage 0：初始化掩码和身份元素，处理 mask/vstart/vl 控制
- Stage 1：逻辑运算 + CSA 压缩（compare_3to1/compare_2to1），将多层归约压缩为少量中间结果
- Stage 2：最终求和/求最大值，输出结果

### 2.7 VMask 掩码运算单元

`VMask` 也是 3 级流水线设计（VLEN=128, LaneWidth=64），处理 vcpop、vfirst、vmsbf/vmsif/vmsof、viota、vid。核心算法特点：`vmsbf` 通过递归方式计算，利用 `UIntToCont0s`/`UIntToCont1s` 工具将数值转换为连续 0/1 位模式；`viota` 通过 PopCount 累加实现。

### 2.8 VAluUtils 工具函数集

`VAluUtils` 提供了 Vector ALU 的基础构建块：

- **UIntSplit**：将宽位向量按 SEW 分割为元素序列
- **BitsExtend**：支持有符号/无符号扩展的位扩展工具
- **MaskExtract**：从 128-bit v0 掩码中提取 16-bit 当前微操作掩码
- **UIntToCont0s / UIntToCont1s**：将数值编码转换为连续 0/1 位模式（递归实现）
- **TailGen**：生成 16-bit 尾部掩码
- **PrestartGen**：生成 16-bit 预启动掩码
- **MaskReorg.splash**：将 16-bit 控制信号按 SEW 膨胀

---

## 3. VIMAC 流水线阶段详解

### 3.1 顶层架构

`VIMac` 模块实现 2-cycle 基础延迟，采用双 64-bit Lane 架构。每个 `VIMac64b` 处理一个 64-bit Lane，结果通过 Cat 拼接为 128-bit 输出。输入数据根据是否 widen 进行重排。

Opcode 解码通过低 3 位实现：highHalf（取高半）、isMacc（乘加系列）、isSub（减法变体）、isFixP（定点缩放乘法 vsmul）、overWriteMultiplicand（vmadd/vnmsub，用 old_vd 覆盖乘数）。

### 3.2 输出级 Tail/Mask 处理

VIMac 输出级实现了完整的 RVV 元素级控制，采用 `updateType` 2-bit 编码：
- `00`：保持计算结果
- `10`：使用 old_vd（预启动/掩码禁用）
- `11`：写入全 1（尾部且 ta=1）

最终结果通过位掩码合成：`io.out.bits.vd := vdResult & bitsKeep | bitsReplace`

### 3.3 Stage 1：Booth 编码与 Wallace 树压缩

`VIMac64bStage1` 执行 6 个子步骤：

**步骤 1 - vs1 Booth 编码**：将 vs1 的 64-bit 数据分为 32 组 3-bit 重叠（Modified Booth Radix-4 Encoding），输出 positive/negative/doubleOrZero/nonZero 四种信号，实现五值编码 {0, +1, +2, -1, -2}，将非零位减少约 50%。

**步骤 2 - vs2 符号位生成**：根据 SEW 和有符号标志生成 8 个符号位。

**步骤 3 - 部分积符号和进位位生成**：结合 Booth 编码和 vs2 符号位生成补码进位位和符号位。

**步骤 4 - 部分积生成**：生成 32 个 68-bit 部分积。

**步骤 5 - Wallace 树生成**：将 32 个部分积排列为 **33 行 152-bit Wallace 树**，额外生成第 34 行分别用于非定点和定点操作。

**步骤 6 - Stage 1 Wallace 压缩**：通过递归的 `wallaceCompress` 函数将 33 行压缩为 **7 行**。压缩方法：每 3 行通过 `compressor3to2`（全加器）压缩为 sum + carry 两行。

### 3.4 Stage 2：最终压缩与 152-bit 全加

`VIMac64bStage2` 执行两步压缩，**双路径并行**：非定点路径和定点路径分别使用独立的压缩器和 152-bit 全加器。设计意图：两条路径并行工作，隐藏定点舍入增量的计算延迟。

### 3.5 Stage 3：结果生成与定点处理

`VIMac64bStage3` 执行 7 个子模块的级联处理：vdNonFixPGenerator（SEW 感知提取）、vxsatGenerator（溢出检测+饱和）、rndIncVecGenerator（4 种舍入模式）、vdRndGenerator、vdFixPGenerator（舍入/饱和选择）、outputSelect。

---

## 4. 向量整数除法器设计

### 4.1 顶层架构 VectorIdiv

`VectorIdiv` 采用**全并行实例化**策略：8 个 8-bit 除法器（I8DivNr4）+ 4 个 16-bit + 2 个 32-bit + 2 个 64-bit 除法器（SRT16Divint）。FSM 为 3 状态设计（idle/divide/output），输出 MUX 根据 SEW 选择对应除法结果。

### 4.2 SRT Radix-16 除法器（主引擎）

`SRT16Divint` 支持 8/16/32/64-bit 位宽，FSM 为 6 状态（idle/pre_0/pre_1/iter/post/output）。

**核心算法**：
- **pre_0**：LZC 计算、归一化、存储 lzc_pre0_all
- **pre_1**：特殊情况检测（x_small/d_zero/d_one）、初始商位选择（查找表）、QDS 常量计算、初始化 iter_cons 和 sel_cons/spec_cons 矩阵
- **iter**：每次迭代产生 4-bit 商。核心模块 `IterBlock_v4` 包含：
  - 双 CSA 余数更新（CSA3_2 将 sum+carry+(-q_j*d) 压缩为新的 sum+carry）
  - 推测并行商位选择（`SelBlock_v4`：4 路并行 CSA 比较 + `SignDec` 符号解码）
  - 推测块（`SpecBlock_v4`：5 路预计算所有可能路径，通过 Mux1H 选择）
  - On-the-fly 商转换（`Conversion`：维护 q_A/q_B 双寄存器避免补码转换）
- **post/output**：符号调整、余数校正、右对齐

### 4.3 I8DivNr4：8 位非恢复除法

`I8DivNr4` 采用 Non-Restoring 4-bit 算法，5 状态 FSM，每次迭代产生 2-bit 商，4 次迭代完成。

### 4.4 CSA3_2 Carry-Save Adder

整个除法器系统大量使用 `CSA3_2`：逐位全加器压缩，关键路径仅 2 个门延迟，即使在 152-bit 宽度下也能保持高时钟频率。

---

## 5. Opcode 编码方案

### 5.1 新旧双编码体系

**旧方案**（VAluDecode.scala）：顺序编码（vadd=0 到 vbrev8=55），解码需等值比较。

**新方案**（VialuOpcode.scala）：**位域编码**，利用 bit position 进行类别快速解码。`op(4)=1` 表示减法类，`op(3)=0` 表示位逻辑类，`op(3)=1` 表示移位/最大最小类。类别判断仅需 1-2 个 AND/NOT 门。

### 5.2 FixedPointRoundingMode

4 种定点舍入模式：RNU（0）、RNE（1）、RDN（2）、ROD（3）。

### 5.3 VimacOpcode（3-bit）

vmul(000)/vmulh(001)/vmacc(010)/vnmsac(011)/vmadd(100)/vnmsub(101)/vsmul(110)。辅助方法：highHalf/isMacc/isSub/isFixP/overWriteMultiplicand。

### 5.4 VipuOpcode（6-bit，值域 33-45）

归约（vredsum~vredxor）、掩码计数（vcpop）、首位搜索（vfirst）、掩码生成（vmsbf/vmsif/vmsof）、索引生成（viota/vid）。

### 5.5 VmoveOpcode（4-bit）

vmerge_vvm/vmv_v_v/vmv_x_s/vmv_s_x/vfmv_f_s/vfmv_s_f/vmv1r/2r/4r/8r。

### 5.6 VfCvtOpcode（8-bit）

高 2 位标识方向（10=浮点到整数、01=整数到浮点、11=浮点到浮点）。支持 vfcvt/vfwcvt/vfncvt 系列及 vfrsqrt7/vfrec7 近似运算。

---

## 6. 后端接口设计

### 6.1 统一接口协议

所有向量 ALU 子模块通过统一的 `VIFuInput`/`VIFuOutput` Bundle 连接。`ValidIO` 包装确保标准握手协议。

### 6.2 微操作分割

向量指令分解为多个 128-bit 微操作，通过 `uopIdx`（6-bit）标识。`TailGen`/`PrestartGen` 根据 uopIdx 和 vl/vstart 计算每个 uop 的有效元素范围。

### 6.3 元素级控制

**tail 处理**：`TailGen` 计算 16-bit 尾部掩码。**prestart 处理**：`PrestartGen` 计算 16-bit 预启动掩码。**mask 处理**：`MaskExtract` 从 128-bit v0 提取 16-bit 子掩码，`MaskReorg.splash` 按 SEW 膨胀。**updateType 编码**：00=保持结果、10=old_vd、11=全 1。

### 6.4 流水线延迟

- VIAlu：1 cycle+
- VIMac：2 cycles+
- VectorIdiv：多 cycle（FSM 驱动，取决于操作数）

### 6.5 特殊数据通路

vmvxs（标量提取）通过 sign-extended 的 vs2 元素直接输出。vxsat 仅在定点运算时有效。

---

## 7. 源文件位置索引

### VectorALU 目录
`yunsuan/src/main/scala/yunsuan/vector/VectorALU/`
- `VIAlu.scala` - ALU 顶层（1-cycle+）
- `VAluDecode.scala` - 56 种操作码定义 + 解码表
- `VAluBundles.scala` - VIFuInput/VIFuOutput/SewOH/VAluOpcode
- `VIFuParameters.scala` - VLEN/XLEN/宽域参数
- `VIAluFixPoint.scala` - 新版定点 ALU 顶层（2-stage）
- `VIAluFixPointS1.scala` - 新版定点 ALU Stage 1
- `VIAluAdder.scala` - 64-bit 向量加法器（8x8-bit 链式）
- `VIAluMisc.scala` - 64-bit 杂项单元
- `VIntFixpAlu.scala` - 旧版定点 ALU（双 Lane）
- `Reduction.scala` - 3-stage 归约单元
- `VMask.scala` - 3-stage 掩码单元
- `VAluUtils.scala` - 工具函数

### vectorIMAC 目录
`yunsuan/src/main/scala/yunsuan/vector/vectorIMAC/`
- `VIMac.scala` - MAC 顶层（双 Lane，2-cycle+）
- `VIMac64b.scala` - 64b MAC 编排器
- `VIMac64bStage1.scala` - Stage 1：Booth + Wallace 树 + 压缩
- `VIMac64bStage2.scala` - Stage 2：压缩 + 双路 152-bit 全加
- `VIMac64bStage3.scala` - Stage 3：结果提取 + 定点处理

### VectorIdiv 目录
`yunsuan/src/main/scala/yunsuan/vector/VectorIdiv/`
- `VectorIdiv.scala` - 除法器顶层（全并行实例化）
- `SRT16Divint.scala` - SRT Radix-16 主引擎 + IterBlock_v4/SelBlock_v4/SpecBlock_v4
- `SRT4Divint8.scala` - 8-bit SRT Radix-4 备选
- `I8DivNr4.scala` - 8-bit Non-Restoring 除法器

### Opcode 编码目录
`yunsuan/src/main/scala/yunsuan/encoding/Opcode/`
- `VialuOpcode.scala` - 新版 VIAlu 位域编码 + FixedPointRoundingMode
- `VimacOpcode.scala` - MAC 3-bit 操作码
- `VipuOpcode.scala` - 掩码/归约 6-bit 操作码
- `VmoveOpcode.scala` - 向量移动 4-bit 操作码
- `VfCvtOpcode.scala` - 浮点转换 8-bit 操作码

---

## 8. 关键设计特点总结

1. **双 Lane 架构**：128-bit 向量通过两个 64-bit Lane 并行处理。

2. **CSA 压缩树贯穿**：从 VIAluAdder 的三输入加法器到 VIMac 的 Wallace 树再到 VectorIdiv 的余数更新，CSA 作为核心加速手段无处不在。

3. **Speculative Parallel 执行**：除法器的 SpecBlock_v4 预计算所有可能路径，通过 Mux1H 在结果确定后选择，有效隐藏选择延迟。

4. **On-the-fly 商转换**：除法器维护 q_A/q_B 双寄存器避免补码转换，乘法器的 Booth 编码天然支持正负部分积混合。

5. **统一的 Tail/Mask/Prestart 处理**：所有计算单元共享 TailGen/PrestartGen/MaskReorg 工具函数，确保 RVV 元素级控制语义一致。

6. **新旧编码共存**：VialuOpcode 的位域编码显著优于旧的顺序编码，但两套方案保持指令语义兼容。