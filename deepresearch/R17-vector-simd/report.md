# XiangShan Vector & SIMD Support 深度研究报告

## 1. 概述

XiangShan (香山) 是中国科学院计算技术研究所开发的高性能开源 RISC-V 处理器。在向量扩展方面，XiangShan 实现了 RISC-V Vector Extension (RVV) 1.0 标准，提供了完整的 SIMD 向量计算能力。其向量子系统覆盖了整数运算、浮点运算、向量内存访问、向量配置以及向量感知的标量浮点操作等多个功能域。

XiangShan 的向量实现采用了**分层解耦**的架构设计：XiangShan 主仓库负责流水线控制、指令解码与分发、以及向量内存操作的 Split/Merge 逻辑；而独立的 **Yunsuan (运算) 子模块**则封装了核心的算术计算逻辑，包括向量 ALU、向量乘加器、向量浮点运算单元等。这种模块化设计使得计算核心可以独立开发和验证。

核心参数方面，默认配置下 XiangShan 的 VLEN = 128 bits，即单个向量寄存器（Vector Register）宽度为 128 位。这意味着对于 SEW=8 的操作，每个向量寄存器可以容纳 16 个元素；对于 SEW=32，可以容纳 4 个元素。XiangShan 支持的 LMUL（Length Multiplier）范围从 1/8 到 8，允许灵活的向量长度配置。

## 2. Vector Pipeline Architecture 向量流水线架构

XiangShan 的向量处理架构可分为以下几个主要功能域：

### 2.1 架构总览

```
+=========================================================================+
|                        XiangShan Vector Pipeline Architecture           |
+=========================================================================+
|                                                                         |
|  +-------------------------------------------------------------+       |
|  |                    Instruction Decode Stage                   |       |
|  |  VecDecoder (opivv/opivx/opivi/opmvv/opmvx/opfvv/opfvf)    |       |
|  |  DecodeUnitComp (uop splitting for multi-uop instructions)   |       |
|  |  VTypeGen (vtype generation) | VecExceptionGen               |       |
|  +----------------------------+----------------------------------+       |
|                               |                                         |
|  +----------------------------v----------------------------------+       |
|  |                  Rename & Dispatch Stage                       |       |
|  |  Rename: Int/FP/Vec/V0/Vl register renaming                  |       |
|  |  Dispatch: to intSchd / fpSchd / vecSchd / memSchd            |       |
|  +----------------------------+----------------------------------+       |
|                               |                                         |
|  +----------------------------v----------------------------------+       |
|  |                  Issue Queue (vecSchd)                         |       |
|  |  VlduIssueQueue / VstuIssueQueue / ArithIssueQueue           |       |
|  |  WakeUp: vlWakeUp, maskWakeUp support                        |       |
|  +--------+---------+---------+---------+---------+----+-------+       |
|           |         |         |         |         |      |               |
|  +--------v---+ +---v---+ +--v----+ +--v---+ +--v---+ +-v-----------+ |
|  | VIALUFix   | | VIPU  | | VIMAC | | VIDIV| | VPPU | | VFALU/VFMA | |
|  | (Vector    | | (Vec  | | (Vec  | | (Vec | | (Vec | | (Vec FP    | |
|  |  Integer   | |  Perm)| |  Mul- | |  Int | | Per- | |  ALU/FMA)  | |
|  |  ALU Fix)  | |       | |  AC   | |  Div)| | mute)| |            | |
|  +------------+ +-------+ +-------+ +------+ +------+ +------------+ |
|  +--------+ +-------+ +-------+ +-------+ +-------+                  |
|  | VFCVT  | | VMOVE | | VFDIV | | VDIV  | | VSET  |                  |
|  | (Vec   | | (Vec  | | (Vec  | | (Vec  | | (vec  |                  |
|  |  FP    | |  Move)| |  FP   | |  Int  | | config)|                  |
|  |  Cvt)  | |       | |  Div) | |  Div) | |       |                  |
|  +--------+ +-------+ +-------+ +-------+ +-------+                  |
|                                                                         |
|  +-------------------------------------------------------------+       |
|  |                  Vector Memory Subsystem (MemBlock)          |       |
|  |                                                               |       |
|  |  VLSplitImp (Load)          |  VSSplitImp (Store)           |       |
|  |    VLSplitPipeline           |    VSSplitPipeline            |       |
|  |    VLSplitBuffer             |    VSSplitBuffer              |       |
|  |         |                    |         |                     |       |
|  |         v                    |         v                     |       |
|  |  Scalar Load/Store Pipeline  |  Scalar Load/Store Pipeline  |       |
|  |         |                    |         |                     |       |
|  |         v                    |         v                     |       |
|  |  VLMergeBuffer               |  VSMergeBuffer               |       |
|  |  (data merge + writeback)    |  (data merge + writeback)    |       |
|  |                                                               |       |
|  |  VSegmentUnit (Segment Load/Store)                           |       |
|  |  VfofBuffer (First-Fault handling)                           |       |
|  +-------------------------------------------------------------+       |
|                                                                         |
|  +-------------------------------------------------------------+       |
|  |              Merge Result Writeback to Register File          |       |
|  |  Mgu (Mask/Tail Generation & Merge)                          |       |
|  |  WriteBack: VecRF / V0RF / VlRF                              |       |
|  +-------------------------------------------------------------+       |
+=========================================================================+
```

### 2.2 向量功能单元 (Vector Function Units)

XiangShan 定义了丰富的向量功能单元类型（`FuType`），每种类型对应特定的计算域：

| FuType 名称 | FuConfig 名称 | 功能描述 | 是否流水线化 | 延迟 |
|---|---|---|---|---|
| `vialuF` | VialuCfg | 向量整数 ALU（FixPoint） | 是 | 流水线化 |
| `vipu` | VipuCfg | 向量整数处理单元（归约、掩码操作） | 是（注释） | - |
| `vimac` | VimacCfg | 向量整数乘加器 | 是（注释） | - |
| `vidiv` | VidivCfg | 向量整数除法器 | 否 | 不确定延迟 |
| `vppu` | VppuCfg | 向量置换处理单元（gather, slide, compress） | 是 | 流水线化 |
| `vfalu` | VfaluCfg | 向量浮点 ALU | 是 | 流水线化 |
| `vfma` | VfmaCfg | 向量浮点乘加单元 | 是 | 流水线化 |
| `vfdiv` | VfdivCfg | 向量浮点除法/开方 | 否 | 不确定延迟 |
| `vfcvt` | VfcvtCfg | 向量浮点/整数类型转换 | 是 | 流水线化 |
| `vmove` | VmoveCfg | 向量移动操作 | 是 | 流水线化 |
| `vldu` | VlduCfg | 向量加载单元 | 否 | 不确定延迟 |
| `vstu` | VstuCfg | 向量存储单元 | 否 | 不确定延迟 |
| `vsetiwf` | VSetRiWvfCfg | vsetvli/vsetvl（写 vtype/vl） | - | - |
| `vsetfwf` | VSetRvfWvfCfg | vsetvli（写 vtype/vl） | - | - |
| `vsetiwi` | VSetRiWiCfg | vsetivli（写 vtype/vl） | - | - |

所有向量功能单元的源操作数配置一致：`Seq(VecData(), VecData(), VecData(), V0Data())`，分别对应 vs1、vs2、old_vd（用于 merge）和 v0（掩码寄存器）。

### 2.3 VecPipedFuncUnit 与 VecNonPipedFuncUnit

XiangShan 提供了两种向量运算单元基础类：

- **`VecPipedFuncUnit`**：流水线化的向量功能单元基类，继承自 `FuncUnit`，混合了 `HasPipelineReg` 和 `VecFuncUnitAlias` 特质。用于固定延迟的向量操作（如 ALU、FPU 等）。它通过 pipeline register 实现多拍流水，支持常规的控制信号透传。

- **`VecNonPipedFuncUnit`**：非流水线化的向量功能单元基类，用于不确定延迟的操作（如除法器）。它使用 `DataHoldBypass` 保持输入信号，并在计算完成后输出结果。

`VecFuncUnitAlias` 提供了通用的向量控制信号访问逻辑，包括 vtype（vsew/vlmul/vta/vma）、vm（掩码使能）、vstart、rm（舍入模式）、fuOpType 等关键信号的便捷访问。

## 3. RVV Extension Support Details 向量扩展支持详情

### 3.1 指令解码

XiangShan 的向量指令解码在 `VecDecoder` 中实现，覆盖了 RVV 1.0 规范的主要指令组：

#### 3.1.1 整数向量算术指令

**OPIVV（整数向量-向量操作）：** 包括 VADD_VV、VSUB_VV、VMINU_VV、VMIN_VV、VMAXU_VV、VMAX_VV 等基本算术运算，以及 VAND_VV、VOR_VV、VXOR_VV 等位运算。这些指令通过 `FuType.vialuF` 执行，对应 `VialuFixType` 中的操作码。

**OPIVX（整数向量-标量操作）：** 支持 VADD_VX、VSUB_VX、VRSUB_VX 等，将向量寄存器与标量整数寄存器进行运算。

**OPIVI（整数向量-立即数操作）：** 支持 VADD_VI、VRSUB_VI、VAND_VI 等，支持带符号/无符号立即数扩展（IMM_OPIVIS / IMM_OPIVIU）。

**饱和运算：** VSADDU_VV、VSADD_VV、VSSUBU_VV、VSSUB_VV 等饱和加减指令，标记 `vxsatWen = true`。

**移位操作：** VSLL_VV、VSRL_VV、VSRA_VV 等移位指令，支持向量-向量和向量-标量移位。

**窄化操作：** VNSRL_WV、VNSRA_WV、VNCLIPU_WV、VNCLIP_WV 等窄化指令，使用 `UopSplitType.VEC_WVV` 进行微操作分组。

**加宽操作：** VWADD_VV、VWADDU_VV、VWSUB_VV、VWSUBU_VV 等加宽运算，使用 `UopSplitType.VEC_VVW` 或 `VEC_WVW`。

#### 3.1.2 掩码与归约操作

**OPMVV（掩码与归约）：** 包括 VMAND_MM、VMOR_MM、VMXOR_MM 等掩码逻辑操作（通过 `VialuFixType`），以及 VCPOP_M、VFIRST_M、VIOTA_M、VID_V 等掩码位操作（通过 `VipuType`）。

**归约操作：** VREDSUM_VS、VREDAND_VS、VREDOR_VS、VREDXOR_VS、VREDMAX_VS、VREDMIN_VS 等，以及加宽归约 VWREDSUMU_VS、VWREDSUM_VS。

#### 3.1.3 整数乘加与除法

**OPMVV/OPMVX 乘法：** VMUL_VV、VMULH_VV、VMULHSU_VV、VMULHU_VV 等，通过 `FuType.vimac` 执行。

**乘加操作：** VMACC_VV、VMADD_VV、VNMSAC_VV、VNMSUB_VV 及其标量变体，同样通过 `vimac` 执行。

**加宽乘加：** VWMACC_VV、VWMACCSU_VV、VWMUL_VV、VWMULSU_VV、VWMULU_VV 等。

**除法/取余：** VDIV_VV、VDIVU_VV、VREM_VV、VREMU_VV 及标量变体，通过 `FuType.vidiv` 执行，延迟不确定。

#### 3.1.4 浮点向量指令

**OPFVV（浮点向量-向量操作）：** VFADD_VV、VFSUB_VV、VFMUL_VV、VFDIV_VV 等基本浮点运算。支持单精度（SEW=32）、双精度（SEW=64）和半精度（SEW=16）。

**融合乘加：** VFMACC_VV、VFNMACC_VV、VFMSAC_VV、VFMADD_VV 等，通过 `FuType.vfma` 执行。

**加宽浮点：** VFWADD_VV、VFWMUL_VV、VFWMACC_VV 等加宽浮点运算。

**浮点比较：** VMFEQ_VV、VMFNE_VV、VMFLT_VV、VMFLE_VV，结果写入掩码寄存器 v0。

**浮点归约：** VFREDOSUM_VS、VFREDUSUM_VS、VFREDMAX_VS、VFREDMIN_VS、VFWREDOSUM_VS。

**类型转换：** VFCVT_XU_F_V、VFCVT_X_F_V、VFCVT_F_X_V 等（单宽度），VFWCVT_*（加宽），VFNCVT_*（窄化），以及 VFRSQRT7_V、VFREC7_V 等估算指令。

**平方根：** VFSQRT_V，通过 `FuType.vfdiv` 执行。

#### 3.1.5 置换与移动操作

**OPFVF（浮点向量-标量操作）：** VFADD_VF、VFSUB_VF、VFRSUB_VF 等。

**置换指令（VPPU）：** VRGATHER_VV、VRGATHEREI16_VV、VRGATHER_VX 等 gather 操作；VSLIDEUP_VX、VSLIDEDOWN_VX、VSLIDE1UP_VX、VSLIDE1DOWN_VX 等 slide 操作；VCOMPRESS_VM 压缩操作。

**移动指令（VMOVE）：** VMERGE_VVM/VXM/VIM、VMV_V_V/X/I、VMV_X_S、VMV_S_X、VMV1R_V、VMV2R_V、VMV4R_V、VMV8R_V 等。

#### 3.1.6 VSET 配置指令

VSETVLI、VSETIVLI、VSETVL 三种向量配置指令，分别对应 `VSetRiWvf`、`VSetRiWi`、`VSetRvfWvf` 三种功能单元配置。这些指令负责设置 `vl`（向量长度）和 `vtype`（向量类型配置，包括 vsew、vlmul、vta、vma）。

#### 3.1.7 Zvbb 扩展

XiangShan 已支持 Zvbb（Vector Bit-manipulation）扩展的部分指令：
- VANDN_VV/VX：位与非
- VROL_VV/VX：向量循环左移
- VROR_VV/VX/VI：向量循环右移
- VWSLL_VV/VX/VI：向量加宽移位
- VBREV_V、VBREV8_V、VREV8_V：位反转操作
- VCLZ_V、VCTZ_V：前导零/尾随零计数
- VCPOP_V：位计数（popcount）

### 3.2 UopSplitType 微操作分组

XiangShan 使用 `UopSplitType` 来定义向量指令如何被拆分为多个微操作（micro-operations）：

| UopSplitType | 说明 |
|---|---|
| `VEC_VVV` | 向量-向量-向量操作 |
| `VEC_VXV` | 向量-标量-向量操作 |
| `VEC_VVM` | 向量-向量-掩码操作（结果写 v0） |
| `VEC_VXM` | 向量-标量-掩码操作 |
| `VEC_WVV` | 窄化向量操作（写宽结果） |
| `VEC_VVW` | 加宽向量操作 |
| `VEC_VXW` | 向量-标量加宽操作 |
| `VEC_WVW` | 宽操作数操作 |
| `VEC_VRED` | 归约操作 |
| `VEC_VFRED` | 浮点归约操作 |
| `VEC_RGATHER` | Gather 操作 |
| `VEC_SLIDEUP/DOWN` | Slide 操作 |
| `VEC_COMPRESS` | 压缩操作 |
| `VEC_EXT2/4/8` | 符号/零扩展操作 |
| `VEC_M0X` | 掩码到标量操作 |
| `VEC_MVV` | 掩码向量操作 |
| `VEC_MVNR` | 整寄存器移动 |
| `VEC_0XV` | 标量到向量移动 |
| `VEC_US_LDST` | 单步加载/存储 |
| `VSET` | 向量配置操作 |

### 3.3 VecExceptionGen 向量异常检测

`VecExceptionGen` 负责检测向量指令的非法性，包括：
- `vdNotAlign`：目标向量寄存器对齐检查，当使用大于 1 的 LMUL 时，目标寄存器编号必须是 LMUL 的倍数
- vtype 合法性检查
- vl 合法性检查
- vstart 非零检查（对于某些操作产生 illegal instruction 异常）

## 4. Yunsuan Submodule Role and Design 运算子模块角色与设计

### 4.1 子模块结构

Yunsuan 是 XiangShan 的独立运算逻辑子模块，封装了底层的算术逻辑实现。其源代码位于 `yunsuan/src/main/scala/yunsuan/`，结构如下：

```
yunsuan/src/main/scala/yunsuan/
  |-- vector/
  |   |-- VectorALU/
  |   |   |-- VIAluFixPoint.scala      -- 向量整数定点 ALU
  |   |   |-- VIAluMisc.scala           -- 向量整数杂项操作
  |   |   |-- VIntMisc64b.scala         -- 64 位向量整数杂项
  |   |   |-- VMaskSlice.scala          -- 掩码切片操作
  |   |   |-- VFixPoint64b.scala        -- 64 位定点操作
  |   |-- VectorFloatAdder.scala        -- 向量浮点加法器
  |   |-- VectorFloatFMA.scala          -- 向量浮点 FMA 单元
  |   |-- VectorFloatDivider.scala      -- 向量浮点除法器
  |   |-- VectorBrainFloatAdder.scala   -- BFloat16 浮点加法器
  |   |-- FloatAdderReductionF32WidenF16.scala -- 浮点归约加宽
  |   |-- VectorIntAdder.scala          -- 向量整数加法器
  |   |-- VPPU.scala                    -- 向量置换处理单元
  |   |-- vectorIMAC/
  |   |   |-- VIMac.scala               -- 向量整数乘加主模块
  |   |   |-- VIMac64b.scala            -- 64 位乘加核心
  |   |   |-- VIMac64bStage1/2/3.scala  -- 三级流水线乘加
  |   |   |-- VIMac64b_noBooth.scala    -- 无 Booth 编码乘加
  |   |-- VectorConvert/
  |   |   |-- VCVT.scala                -- 向量类型转换主模块
  |   |   |-- CVT16/32/64.scala         -- 16/32/64 位转换
  |   |   |-- Convert.scala             -- 通用转换逻辑
  |   |   |-- Estimate7.scala           -- vfrsqrt7/vfrec7 估算
  |   |-- VectorIdiv/                   -- 向量整数除法
  |   |-- VectorPerm/                   -- 向量排列
  |   |-- VectorPermFsm/                -- 排列 FSM 控制
  |   |-- VectorMove/
  |   |   |-- VMove.scala               -- 向量移动操作
  |   |-- VPERM.scala                   -- 向量置换
  |   |-- vfsqrt/                       -- 浮点平方根
  |   |   |-- fpsqrt_vector_r16.scala   -- 基于 radix-16 的向量开方
  |   |   |-- fpsqrt_r16_block.scala    -- R16 开方块
  |   |   |-- r4_qds.scala              -- Radix-4 数字选择
  |-- fpu/                              -- 标量浮点单元
  |-- fpulite/                          -- 轻量级浮点单元
  |-- scalar/                           -- 标量运算单元
  |-- encoding/                         -- 操作码编码定义
  |-- util/                             -- 工具函数
```

### 4.2 核心计算单元设计

#### 4.2.1 VIntFixpAlu -- 向量整数定点 ALU

`VIntFixpAlu` 是向量整数运算的核心，通过 `VIAluFixType.getOpcode` 解码操作码，支持以下操作类别：

- 基本算术：加法（vadd）、减法（vsub）、反向减法（vrsub）
- 比较操作：等于（vmseq）、不等于（vmsne）、小于（vmslt/vmsltu）、小于等于（vmsle/vmsleu）、大于（vmsgt/vmsgtu）
- 位运算：与（vand）、或（vor）、异或（vxor）、与非（vandn）
- 移位：逻辑左移（vsll）、逻辑右移（vsrl）、算术右移（vsra）
- 饱和运算：饱和加（vsadd/vsaddu）、饱和减（vssub/vssubu）
- 加宽运算：加宽加减（vwadd/vwsub）
- 窄化运算：窄化移位（vnsrl/vnsra）、窄化裁剪（vnclipu/vnclip）
- 掩码操作：mand、mnand、mor、mnor、mxor、mxnor
- 归约操作：vredsum、vredmax、vredmin、vredand、vredor、vredxor
- 掩码位操作：vcpop、vfirst、vmsbf、vmsif、vmsof、viota、vid
- 扩展操作：vsext、vzext
- Zvbb 扩展：vbrev、vbrev8、vrev8、vclz、vctz、vcpop

该单元根据 `vconfig.vtype.vsew` 动态配置运算宽度（8/16/32/64 bit），并使用 `MaskExtract` 从掩码寄存器中提取当前 uop 对应的掩码位。

#### 4.2.2 VIMac -- 向量整数乘加器

`VIMac` 实现了向量整数乘法和乘加操作，采用 **三级流水线** 设计（Stage1/Stage2/Stage3），核心是 64 位乘加器 `VIMac64b`。操作码包括：

- VMUL：向量乘法
- VMULH/VMULHU/VMULHSU：有符号/无符号高位乘法
- VMACC/VMADD：乘累加/乘加
- VNMSAC/VNMSUB：负乘累加/负乘加
- VSMUL：饱和乘法
- 加宽乘法：VWMUL、VWMULSU、VWMULU
- 加宽乘加：VWMACC、VWMACCSU、VWMACCU、VWMACCUS

注意：在当前版本中，VIPU、VIAluFix、VIMac 等文件中的 wrapper 代码被注释掉了，这表明这些功能单元可能已经迁移到了新的架构模式（`VecPipedFuncUnit` / `VecNonPipedFuncUnit`），但底层的 Yunsuan 计算模块仍然被新的包装器引用。

#### 4.2.3 VectorFloatFMA -- 向量浮点 FMA

`VectorFloatFMA` 实现了向量浮点融合乘加操作，支持：
- 单精度（FP32）和双精度（FP64）运算
- 浮点乘法（VFMUL）
- 融合乘加（VFMACC）、融合负乘加（VFNMACC）、融合乘减（VFMSAC/VFMSUB）等
- 加宽浮点乘加（VFWMACC 等）

#### 4.2.4 VectorFloatAdder -- 向量浮点加法器

实现了向量浮点加法/减法，支持 VFWADD/VFWSUB（加宽加减）和 VFREDOSUM/VFREDUSUM（归约加减）等操作。

#### 4.2.5 VectorFloatDivider / VFSqrt

`VectorFloatDivider` 实现了向量浮点除法，`fpsqrt_vector_r16` 实现了基于 **Radix-16** 的向量浮点平方根算法，使用 QDS（Quotient Digit Selection）进行高效的数字选择。

#### 4.2.6 VectorConvert (VCVT)

实现了丰富的向量类型转换操作：
- 浮点到整数转换（VFCVT_XU_F_V、VFCVT_X_F_V）
- 整数到浮点转换（VFCVT_F_X_V、VFCVT_F_XU_V）
- 浮点到浮点精度转换（加宽、窄化）
- 估算操作（VFRSQRT7_V、VFREC7_V）
- 支持 16/32/64 位转换路径

#### 4.2.7 VPPU -- 向量置换处理单元

`VPPU` 实现了向量数据排列操作：
- **Gather 操作**：VRGATHER_VV、VRGATHEREI16_VV、VRGATHER_VX
- **Slide 操作**：VSLIDEUP、VSLIDEDOWN、VSLIDE1UP、VSLIDE1DOWN
- **Compress 操作**：VCOMPRESS_VM

#### 4.2.8 VIMac64b 乘加核心

`VIMac64b` 是 64 位向量整数乘加的核心计算单元，采用 **三级流水线**（Stage1/Stage2/Stage3）设计：
- Stage1：操作数准备与 Booth 编码（可选无 Booth 版本 `VIMac64b_noBooth`）
- Stage2：部分积生成与累加
- Stage3：结果修正与溢出检测

## 5. Vector Memory Access Patterns 向量内存访问模式

### 5.1 访问模式概述

XiangShan 的向量内存子系统支持 RVV 规范定义的三种基本内存访问模式：

1. **Unit-Stride（单位步长）**：元素在内存中连续存储，步长为元素大小。这是最高效的模式，可以利用 128-bit 对齐进行宽位宽访问。

2. **Strided（固定步长）**：通过标量寄存器指定固定的字节步长。适用于矩阵的列访问等场景。

3. **Indexed（索引）**：使用专门的索引向量寄存器为每个元素提供独立的偏移量。适用于不规则的数据访问模式。

### 5.2 VSplit -- 向量内存操作分拆

向量内存操作的核心挑战是将宽向量操作（如 128-bit 的 VLEN）拆分为标量内存流水线可以处理的单个流（flow）。XiangShan 通过 `VSplit` 模块实现这一过程：

#### 5.2.1 VSplitPipeline -- 分拆流水线

`VSplitPipeline` 是两拍流水线（s0/s1）：

**S0 阶段（解码）：**
- 从 `ExuInput` 中提取向量配置信息（vtype、eew、emul、lmul、nf）
- 计算 `alignedType`：区分 unit-stride 和 split 指令的对齐类型
- 计算 `flowMask`：确定哪些 flow 是有效的
- 计算 `numUops`：一个向量指令被拆分成多少个 uop
- 生成 `uopOffset`：每个 uop 在向量寄存器中的偏移

**S1 阶段（计算地址）：**
- 生成 `uopAddr`：基于 baseAddr + uopOffset
- 对 unit-stride 地址进行 128-bit 对齐检查（`usAligned128`）
- 生成 `usMask`：用于 unit-stride 拆分的字节掩码
- 向 MergeBuffer 发送分配请求
- 输出到 SplitBuffer

#### 5.2.2 VSplitBuffer -- 分拆缓冲

`VSplitBuffer` 负责将一个 uop 进一步拆分为多个 flow（内存访问流）：

**Unit-Stride 拆分策略：**
- 如果地址 128-bit 对齐（`usAligned128 = true`），则只需一个 flow
- 如果未对齐，则拆分为两个 flow：低地址 flow 和高地址 flow
- 第二个 flow 可能标记为 `usSecondInv`（无效），表示不需要实际访问

**非 Unit-Stride 拆分策略：**
- 每个 flow 对应一个元素的内存访问
- 通过 `splitIdx` 递增遍历所有元素
- 计算每个 flow 的地址和掩码

**Indexed 操作：**
- 通过 `IndexAddr` 函数，使用索引向量中的值计算每个元素的实际偏移
- 支持 emul > lmul 的特殊情况（`isSpecialIndexed`）

#### 5.2.3 三种拆分实现

- **VLSplitImp**：向量加载分拆，组合了 `VLSplitPipelineImp` 和 `VLSplitBufferImp`
- **VSSplitImp**：向量存储分拆，组合了 `VSSplitPipelineImp` 和 `VSSplitBufferImp`，额外通过 `vstd` 接口将存储数据发送到 Store Queue

### 5.3 VMergeBuffer -- 向量合并缓冲

当多个 flow 从标量内存流水线返回后，需要将它们的数据合并回向量寄存器格式。`VMergeBuffer` 负责这一关键操作：

#### 5.3.1 MBufferBundle 结构

每个 MergeBuffer entry 包含：
- `data`：VLEN 宽度的数据缓冲
- `mask`：VLENB 位的掩码
- `flowNum`：尚未写回的 flow 数量（递减到 0 时表示 uop 完成）
- `elemIdx`：当前处理的元素索引
- `uop`：完整的微操作信息
- `vstart`、`vl`：异常时使用的向量参数
- `exceptionVec`：异常向量

#### 5.3.2 入队与出队

**入队（Enqueue）：** 来自 `VSplitPipeline`，为每个 uop 分配一个 MergeBuffer entry，初始化 flowNum、数据和掩码。

**流水线写回（Pipeline Writeback）：** 来自标量内存流水线的多个 port 同时写入同一个 entry。`mergePortMatrix` 矩阵用于检测多个 port 是否写入同一 entry，处理同周期多 port 合并的情况。`SelectOldest` 模块选择最老的异常端口。

**出队（Dequeue）：** 当 `flowNum` 递减到 0 时，uop 标记为完成。使用 `SelectOne` 策略选择完成的 entry 进行写回。

#### 5.3.3 数据合并

`VLMergeBufferImp` 实现了 load 的数据合并逻辑：

- **非 Unit-Stride 合并**：通过 `mergeDataWithElemIdx` 函数，按元素索引将多个 flow 的数据合并到正确的位置
- **Unit-Stride 合并**：采用两步流水线：
  1. 第一步：将 128-bit 数据展宽到 256-bit（6:1 展宽）
  2. 第二步：根据 `reg_offset` 从 256-bit 中选择正确的 128-bit 数据
  3. 通过 `mergeDataByByte` 函数完成字节级合并

`VSMergeBufferImp` 处理 store 的数据合并，逻辑相对简单。

#### 5.3.4 异常处理

MergeBuffer 还负责异常处理：
- 选择最老的异常端口（通过 `SelectOldest` 模块）
- 对于 first-fault（fof）加载，如果 element 0 异常则触发 trap
- 生成 `MemExceptionInfo` 传递给异常处理单元
- 通过 `FeedbackToLsqIO` 向 Load/Store Queue 发送 COMMIT/FLUSH/LAST 反馈

### 5.4 VSegmentUnit -- 向量段加载/存储

`VSegmentUnit` 处理 RVV 的 segment load/store 指令（带 NF 字段的指令），例如 `vld.v` 的 NF > 0 形式。这类操作需要按特定顺序访问内存：

对于 `lmul=2, sew=32, emul=2, eew=32, vl=16` 的配置，内存访问顺序为：
```
(V2,S0), (V4,S0), (V6,S0), (V8,S0),  // 同一段的不同字段
(V2,S1), (V4,S1), (V6,S1), (V8,S1),
...
(V3,S4), (V5,S4), (V7,S4), (V9,S4),  // 下一段的不同字段
...
```

VSegmentUnit 通过 `segmentIdx` 和 `fieldIdx` 控制访问顺序，确保先访问同一段的不同字段，再访问不同的段。

### 5.5 VfofBuffer -- First-Fault 处理

`VfofBuffer` 处理 `vleff.v`（first-fault）加载指令。这种特殊加载指令在遇到页面错误时不会触发异常，而是返回到发生错误之前的 vl 值。VfofBuffer 使用 `SelectOldest` 模块比较多个 port 的结果，选择最老的有效结果。

### 5.6 与标量内存流水线的交互

向量内存操作最终通过标量内存流水线（DCache Load/Store pipeline）执行：

1. VSplit 输出的 `VecPipeBundle` 通过 `toVectorLoadIn()` / `toVectorStoreIn()` 转换为标量流水线的输入格式
2. `VLSBundle` 中的 `entrance` 字段标记为 `LoadEntrance.vectorIssue` 或 `StoreEntrance.vectorIssue`
3. 标量流水线执行 TLB 查找、Cache 访问、PMP 检查等操作
4. 结果通过 `VecPipelineFeedbackIO` 返回到 MergeBuffer

## 6. Vector Register File Handling 向量寄存器文件处理

### 6.1 寄存器文件类型

XiangShan 实现了三个独立的向量相关寄存器文件：

| 寄存器文件 | 参数类型 | 宽度 | 描述 |
|---|---|---|---|
| VecRegFile | `VecData()` / `VecPregParams` | VLEN = 128 bits | 主向量寄存器文件（v0-v31） |
| V0RegFile | `V0Data()` / `V0PregParams` | 掩码宽度 | 掩码寄存器 v0（单独管理） |
| VlRegFile | `VlData()` / `VlPregParams` | XLEN | 向量长度寄存器 vl |

### 6.2 重命名与分配

在 Rename 阶段，向量寄存器通过 `numVecRegSrc` 个端口进行重命名。`VecData()`、`V0Data()` 和 `VlData()` 分别对应不同的数据通路类型，确保不同类型的操作（向量计算、掩码操作、vl 配置）使用正确的寄存器端口。

### 6.3 读写端口

从 `BackendParams` 的配置可以看出：

- **读端口**：每个 Issue Block 根据 `numVecSrc`、`numV0Src`、`numVlSrc` 配置读端口数量
- **写端口**：通过 `VfWB`（向量浮点写回）、`V0WB`（掩码写回）、`VlWB`（vl 写回）配置写回端口
- **唤醒信号**：向量 load/store 支持 `vlWakeUp` 和 `maskWakeUp`，允许后续指令在向量操作未完成时基于 vl/mask 依赖提前唤醒

### 6.4 Mgu -- 向量合并单元

`Mgu`（Mask/Tail Generation and Merge unit）是向量寄存器写回前的关键模块，负责：

1. **活跃元素/尾部元素处理**：根据 `vstart`、`vl`、`vma`（aggressive/undisturbed）、`vta`（tail agnostic/undisturbed）决定每个字节是使用新数据、旧数据还是全 1（agnostic）
2. **掩码处理**：根据 `vm` 和 v0 掩码决定每个元素是否被写入
3. **Narrow 指令处理**：处理窄化指令的 vdIdx 映射
4. **字节级合并**：逐字节根据 `activeEn` 和 `agnosticEn` 信号选择数据

`ByteMaskTailGen` 子模块负责生成每个字节的 active/agnostic 使能信号，综合考虑了 vstart、vl、sew、vma、vta 等参数。

## 7. VPU Architecture and Design 向量处理单元架构设计

### 7.1 新旧架构对比

XiangShan 的向量实现经历了架构演进。早期版本使用 `VPUSubModule` / `VPUDataModule` 架构（当前代码中已注释），新版本采用基于 `FuncUnit` 的统一架构：

**旧架构（已注释）：**
- `VPUSubModule`：包含 `dataModule` 序列和 `select` 信号，通过 FSM（s_idle/s_compute/s_finish）控制计算流程
- `VPUDataModule`：继承自 `FunctionUnit`，包含 rm、vstart、vxrm、vxsat 等向量特有信号
- 通过 `connectDataModule` 连接数据模块和输出

**新架构（当前活跃）：**
- `VecPipedFuncUnit`：直接继承 `FuncUnit`，混合 `HasPipelineReg` 和 `VecFuncUnitAlias`
- `VecNonPipedFuncUnit`：直接继承 `FuncUnit`，使用 `DataHoldBypass`
- 通过统一的 `ExuInput/ExuOutput` 接口与调度器和写回网络连接

### 7.2 Og2ForVector -- 输出第二拍

`Og2ForVector` 处理向量功能单元的输出级流水线，确保向量结果在正确的时序点写入寄存器文件。所有向量算术单元和向量内存单元都需要 `Og2` 处理。

### 7.3 VecExcpDataMergeModule

`VecExcpDataMergeModule` 负责合并向量异常数据，在向量异常处理流程中确保异常信息的正确性。

### 7.4 关键设计特点

1. **掩码驱动**：所有向量操作都支持掩码（v0 mask），未掩码的元素保持不变
2. **Uop 索引（vuopIdx）**：每条向量指令被拆分为多个 uop，通过 `vuopIdx` 区分
3. **灵活的 SEW/LMUL 配置**：通过 vtype 动态配置运算宽度和向量长度
4. **Tail 处理**：根据 vta 设置，尾部元素可以保持旧值（undisturbed）或设为 1（agnostic）
5. **vstart 支持**：支持从非零位置开始执行，用于异常恢复

## 8. Key Source File Locations 关键源文件位置

### 8.1 向量解码相关

| 文件路径 | 说明 |
|---|---|
| `src/main/scala/xiangshan/backend/decode/VecDecoder.scala` | 向量指令解码表（核心） |
| `src/main/scala/xiangshan/backend/decode/DecodeUnitComp.scala` | 复合指令解码 |
| `src/main/scala/xiangshan/backend/decode/VecExceptionGen.scala` | 向量异常检测 |
| `src/main/scala/xiangshan/backend/decode/VTypeGen.scala` | 向量类型生成 |
| `src/main/scala/xiangshan/backend/decode/isa/` | ISA 指令定义目录 |

### 8.2 向量功能单元

| 文件路径 | 说明 |
|---|---|
| `src/main/scala/xiangshan/backend/fu/vector/VecPipedFuncUnit.scala` | 流水线向量功能单元基类 |
| `src/main/scala/xiangshan/backend/fu/vector/VecNonPipedFuncUnit.scala` | 非流水线向量功能单元基类 |
| `src/main/scala/xiangshan/backend/fu/vector/Bundles.scala` | 向量功能单元接口定义 |
| `src/main/scala/xiangshan/backend/fu/vector/Mgu.scala` | 向量合并/掩码生成单元 |
| `src/main/scala/xiangshan/backend/fu/vector/ByteMaskTailGen.scala` | 字节掩码与尾部生成 |
| `src/main/scala/xiangshan/backend/fu/vector/VIPU.scala` | 向量整数处理单元（旧，已注释） |
| `src/main/scala/xiangshan/backend/fu/vector/VIAluFix.scala` | 向量整数 ALU FixPoint（旧，已注释） |
| `src/main/scala/xiangshan/backend/fu/vector/VIMacU.scala` | 向量整数乘加（旧，已注释） |
| `src/main/scala/xiangshan/backend/fu/vector/VPerm.scala` | 向量排列操作 |
| `src/main/scala/xiangshan/backend/fu/vector/VPUSubModule.scala` | VPU 子模块基础类（旧，已注释） |
| `src/main/scala/xiangshan/backend/fu/vector/Utils.scala` | 向量工具函数 |
| `src/main/scala/xiangshan/backend/fu/vector/VecSrcTypeModule.scala` | 向量源类型模块 |
| `src/main/scala/xiangshan/backend/fu/vector/NewMgu.scala` | 新版合并单元 |

### 8.3 向量内存子系统

| 文件路径 | 说明 |
|---|---|
| `src/main/scala/xiangshan/mem/vector/VSplit.scala` | 向量操作分拆（Pipeline + Buffer） |
| `src/main/scala/xiangshan/mem/vector/VMergeBuffer.scala` | 向量合并缓冲（Load + Store） |
| `src/main/scala/xiangshan/mem/vector/VecBundle.scala` | 向量内存接口定义 |
| `src/main/scala/xiangshan/mem/vector/VecCommon.scala` | 向量内存公共工具 |
| `src/main/scala/xiangshan/mem/vector/VSegmentUnit.scala` | 向量段加载/存储 |
| `src/main/scala/xiangshan/mem/vector/VfofBuffer.scala` | First-Fault 缓冲 |
| `src/main/scala/xiangshan/mem/MemBlock.scala` | 内存块（集成 VSplit/VMerge） |

### 8.4 Yunsuan 运算子模块

| 文件路径 | 说明 |
|---|---|
| `yunsuan/src/main/scala/yunsuan/vector/VectorALU/VIAluFixPoint.scala` | 向量整数定点 ALU |
| `yunsuan/src/main/scala/yunsuan/vector/VectorALU/VIAluMisc.scala` | 向量整数杂项操作 |
| `yunsuan/src/main/scala/yunsuan/vector/VectorALU/VMaskSlice.scala` | 掩码切片 |
| `yunsuan/src/main/scala/yunsuan/vector/VectorALU/VFixPoint64b.scala` | 64 位定点操作 |
| `yunsuan/src/main/scala/yunsuan/vector/VectorFloatAdder.scala` | 向量浮点加法器 |
| `yunsuan/src/main/scala/yunsuan/vector/VectorFloatFMA.scala` | 向量浮点 FMA |
| `yunsuan/src/main/scala/yunsuan/vector/VectorFloatDivider.scala` | 向量浮点除法器 |
| `yunsuan/src/main/scala/yunsuan/vector/VectorIntAdder.scala` | 向量整数加法器 |
| `yunsuan/src/main/scala/yunsuan/vector/VPPU.scala` | 向量置换处理单元 |
| `yunsuan/src/main/scala/yunsuan/vector/vectorIMAC/VIMac.scala` | 向量整数乘加主模块 |
| `yunsuan/src/main/scala/yunsuan/vector/vectorIMAC/VIMac64b.scala` | 64 位乘加核心 |
| `yunsuan/src/main/scala/yunsuan/vector/VectorConvert/VCVT.scala` | 向量类型转换 |
| `yunsuan/src/main/scala/yunsuan/vector/VectorMove/VMove.scala` | 向量移动操作 |
| `yunsuan/src/main/scala/yunsuan/vector/vfsqrt/fpsqrt_vector_r16.scala` | 浮点平方根 |

### 8.5 后端集成

| 文件路径 | 说明 |
|---|---|
| `src/main/scala/xiangshan/Parameters.scala` | 核心参数定义（VLEN 等） |
| `src/main/scala/xiangshan/backend/fu/FuConfig.scala` | 功能单元配置（含所有向量 FuConfig） |
| `src/main/scala/xiangshan/backend/Backend.scala` | 后端顶层（向量调度集成） |
| `src/main/scala/xiangshan/backend/BackendParams.scala` | 后端参数（寄存器文件端口配置） |
| `src/main/scala/xiangshan/backend/VecExcpDataMergeModule.scala` | 向量异常数据合并 |
| `src/main/scala/xiangshan/backend/datapath/Og2ForVector.scala` | 向量输出第二拍 |
| `src/main/scala/xiangshan/backend/rob/VTypeBuffer.scala` | vtype 状态缓冲 |
| `src/main/scala/xiangshan/backend/regfile/PregParams.scala` | 物理寄存器参数 |
| `src/main/scala/xiangshan/backend/dispatch/Dispatch.scala` | 指令分发（向量支持） |

## 9. 总结与分析

XiangShan 的向量子系统是一个功能完整且设计精良的 RVV 1.0 实现。其关键设计特点包括：

1. **计算与控制分离**：XiangShan 主仓库负责流水线控制和内存管理，Yunsuan 子模块负责计算逻辑，这种解耦设计有利于独立开发和验证。

2. **灵活的分拆-合并机制**：VSplit 和 VMergeBuffer 的设计使得任意宽度的向量操作可以透明地映射到固定宽度的标量内存流水线上，同时支持 Unit-Stride、Strided 和 Indexed 三种访问模式。

3. **全面的 SEW/LMUL 支持**：通过动态配置 SEW（8/16/32/64 bit）和 LMUL（1/8 到 8），XiangShan 可以高效处理不同精度的向量数据。

4. **异常处理的精细性**：VfofBuffer（First-Fault）、segment load 的顺序访问、以及 vstart 支持，确保了向量操作的异常安全性。

5. **掩码与 Tail 处理**：通过 Mgu 和 ByteMaskTailGen 的配合，实现了 RVV 规范中复杂的掩码、尾部元素和 agnostic/undisturbed 语义。

6. **扩展性**：已经支持 Zvbb 扩展的部分指令，展示了架构的可扩展性。

需要注意的是，部分旧版本的向量功能单元代码（VIPU、VIAluFix、VIMac 的 wrapper）已被注释掉，这表明架构正在向新的 `VecPipedFuncUnit` / `VecNonPipedFuncUnit` 模式迁移，但底层 Yunsuan 计算模块仍然被使用。
