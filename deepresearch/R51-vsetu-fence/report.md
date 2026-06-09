# R51 - Vsetu & Fence Functional Units 深度分析报告

## 目录

1. [概述](#1-概述)
2. [Vsetu (Vector Set Up) 功能单元](#2-vsetu-vector-set-up-功能单元)
3. [VsetModule 核心计算逻辑](#3-vsetmodule-核心计算逻辑)
4. [vtype/vl 寄存器计算与管理](#4-vtypevl-寄存器计算与管理)
5. [VLMUL/VSEW/VLMAX 计算](#5-vlmulvsewvlmax-计算)
6. [VSet Wrapper 三种变体设计](#6-vset-wrapper-三种变体设计)
7. [VSETOpType 操作码编码](#7-vsetoptype-操作码编码)
8. [Fence 功能单元 FSM 设计](#8-fence-功能单元-fsm-设计)
9. [fence.i 指令流水线刷新](#9-fencei-指令流水线刷新)
10. [sfence.vma TLB 失效处理](#10-sfencevma-tlb-失效处理)
11. [Jump/Branch 单元设计](#11-jumpbranch-单元设计)
12. [源文件位置汇总](#12-源文件位置汇总)

---

## 1. 概述

XiangShan 处理器后端的 Functional Unit (FU) 架构中，Vsetu 和 Fence 是两个具有特殊地位的功能单元。它们与传统的算术逻辑单元不同，不执行数据计算，而是负责处理器状态的管理：

- **Vsetu (Vector Set Up)**：负责 RISC-V Vector 扩展指令 vsetvl/vsetvli/vsetivli 的执行，管理向量配置寄存器（vtype 和 vl），计算 VLMAX 等关键参数。
- **Fence**：负责内存屏障指令的执行，包括 fence（内存屏障）、fence.i（指令缓存失效）、sfence.vma（TLB 失效）以及 H 扩展的 hfence_v/hfence_g。

这两个单元在处理器流水线中具有特殊的行为特性：Vsetu 是零延迟路径（0-latency），直接将结果传递给后续阶段；Fence 则需要多周期状态机来完成复杂的同步操作。本报告将深入分析这两个单元的内部架构、计算逻辑和微架构设计。

---

## 2. Vsetu (Vector Set Up) 功能单元

### 2.1 核心定位

Vsetu 单元位于 `src/main/scala/xiangshan/backend/fu/Vsetu.scala`（133 行），是 XiangShan 处理器中 RISC-V Vector 扩展的配置管理核心。它负责将用户程序通过 vsetvl/vsetvli/vsetivli 指令传入的配置参数（vtype、vl）转换为处理器内部向量单元实际使用的 VConfig 数据结构。

### 2.2 接口设计

VsetModule 的 IO 接口分为输入和输出两部分：

**输入接口 (VsetModuleIO.in)**：
- `avl: UInt(XLEN.W)`：Avail Vector Length，即用户请求的向量长度。对于 vsetvl/vsetvli 来自寄存器 rs1，对于 vsetivli 来自立即数字段。
- `vtype: VsetVType`：向量类型配置，包含 vma（mask agnostic）、vta（tail agnostic）、vsew（Standard Element Width）、vlmul（Vector Length Multiplier）等字段。
- `func: UInt(FuOpType())`：操作码类型，用于区分不同的 vset 指令变体。
- `oldVt: Option[VType]`：可选的旧 vtype 值，用于 keepVl 检查（仅在某些配置下启用）。

**输出接口 (VsetModuleIO.out)**：
- `vconfig: VConfig`：完整的向量配置，包含 vtype 和 vl 两个字段。
- `vlmax: UInt(vlWidth.W)`：当前配置下的最大向量长度，用于后续向量操作的范围计算。

### 2.3 功能标志位

VsetModule 内部通过 VSETOpType 工具函数提取操作类型标志：

```scala
private val isSetVlmax = VSETOpType.isSetVlmax(func)  // bit 0: 设置 vl 为 vlmax
private val isVsetivli = VSETOpType.isVsetivli(func)  // bit 7,6 == 00: vsetivli 指令
private val isKeepVl   = VSETOpType.isKeepVl(func)    // bit 1: 保持 vl 不变
```

这些标志位控制着 vl 计算的不同路径，使得一个统一的 VsetModule 能够处理所有三种 vset 指令变体。

---

## 3. VsetModule 核心计算逻辑

### 3.1 VLMAX 计算原理

VLMAX（Maximum Vector Length）的计算是 VsetModule 最核心的功能。根据 RISC-V Vector 规范：

```
VLMAX = VLEN x LMUL / SEW
```

其中：
- VLEN：处理器的向量寄存器位宽（由硬件参数 XSCoreParamsKey.vlWidth 决定）
- LMUL：向量长度乘数（由 vlmul 字段编码）
- SEW：标准元素宽度（由 vsew 字段编码）

XiangShan 采用对数域计算来避免除法：

```scala
// log2(VLMAX) = log2(VLEN) + log(LMUL) - log(SEW)
// => VLMAX = 1 << log2(VLMAX)
private val log2Vlen = log2Up(VLEN)  // 例如 VLEN=128 时 log2Vlen=7
private val log2Vlmul = vlmul         // 直接使用编码值
private val log2Vsew = vsew + "b011".U  // log(SEW) = encoded + 3
private val log2Vlmax: UInt = log2Vlen.U + log2Vlmul - log2Vsew
private val vlmax = (1.U(vlWidth.W) << log2Vlmax).asUInt
```

以 VLEN=128、LMUL=8、SEW=8 为例：
- log2Vlen = 7
- vlmul = b011（编码 3，对应 LMUL=8）
- vsew = b000（编码 0，对应 SEW=8）
- log2Vsew = 0 + 3 = 3
- log2Vlmax = 7 + 3 - 3 = 7
- vlmax = 1 << 7 = 128

以 VLEN=128、LMUL=2、SEW=16 为例：
- vlmul = b001（编码 1，对应 LMUL=2）
- vsew = b001（编码 1，对应 SEW=16）
- log2Vsew = 1 + 3 = 4
- log2Vlmax = 7 + 1 - 4 = 4
- vlmax = 1 << 4 = 16

### 3.2 VL 值的确定

vl 的最终值取决于指令类型和 avl 输入：

```scala
private val normalVL = Mux(avl > vlmax, vlmax, avl)
vl := Mux(isVsetivli, normalVL, 
       Mux(isSetVlmax, vlmax, normalVL))
```

三条规则：
1. **vsetivli**：`vl = min(avl, vlmax)`（直接使用 normalVL）
2. **vsetvl/vsetvli 且 rs1=x0**（isSetVlmax=true）：`vl = vlmax`（设置为最大值）
3. **其他情况**：`vl = min(avl, vlmax)`（使用 normalVL）

### 3.3 非法指令检测

VsetModule 实现了多层合法性检查：

```scala
private val sewIllegal = VSew.isReserved(vsew) || (log2Vsew > log2VsewMax)
private val lmulIllegal = VLmul.isReserved(vlmul)
private val vtypeIllegal = vtype.reserved.orR
private val keepVlIllegal: Bool = io.in.oldVt match {
    case Some(_) => isKeepVl && (oldVt.illegal || log2NewRatio =/= log2OldRatio)
    case None => 0.B
}
private val illegal = lmulIllegal | sewIllegal | vtypeIllegal | vtype.illegal | keepVlIllegal
```

非法情况包括：
- **sewIllegal**：SEW 编码保留值（大于 e64）或 SEW 与 LMUL 组合不兼容
- **lmulIllegal**：LMUL 编码保留值
- **vtypeIllegal**：vtype 的 reserved 字段非零
- **keepVlIllegal**：keepVl 操作时旧 vtype 非法，或新旧 LMUL/SEW 比率不一致

当检测到非法时，输出 vl=0 且 vtype.illegal=1，符合 RISC-V 规范要求。

### 3.4 VLMAX 输出保护

输出的 vlmax 经过保护处理，确保至少为 VLEN/SEW（即一个向量寄存器能容纳的最少元素数）：

```scala
private val log2VlenDivVsew = log2Vlen.U - log2Vsew
private val vlenDivVsew = 1.U(vlWidth.W) << log2VlenDivVsew
io.out.vlmax := Mux(vlmax >= vlenDivVsew, vlmax, vlenDivVsew)
```

这保证了即使在极端配置下，vlmax 也不会小于基本单位。

---

## 4. vtype/vl 寄存器计算与管理

### 4.1 VtypeStruct 结构

在 CSR 层面，vtype 寄存器使用 VtypeStruct 结构（定义在 `src/main/scala/xiangshan/backend/fu/CSR.scala`）：

```scala
class VtypeStruct(implicit p: Parameters) extends XSBundle {
  val vill = UInt(1.W)          // Vector Illegal bit
  val reserved = UInt((XLEN - 9).W)  // 保留位
  val vma = UInt(1.W)           // Vector Mask Agnostic
  val vta = UInt(1.W)           // Vector Tail Agnostic
  val vsew = UInt(3.W)          // Standard Element Width
  val vlmul = UInt(3.W)         // Vector Length Multiplier
}
```

总计 XLEN 位（64 位），其中有效字段仅占 9 位，其余为保留位。

### 4.2 VsetVType 结构

在 VsetModule 内部使用 VsetVType 结构（定义在 `src/main/scala/xiangshan/backend/fu/vector/Bundles.scala`）：

```scala
class VsetVType(implicit p: Parameters) extends XSBundle {
  val illegal  = Bool()
  val reserved = UInt((XLEN - 9).W)
  val vma      = Bool()
  val vta      = Bool()
  val vsew     = VtypeVSew()    // 3-bit 编码
  val vlmul    = VLmul()        // 3-bit 编码
}
```

VsetVType 相比 VType 多了 reserved 和 illegal 字段，用于检测非法编码。

### 4.3 VConfig 结构

VConfig 是 VsetModule 的最终输出，由 VType 和 vl 组成：

```scala
class VConfig(implicit p: Parameters) extends Bundle {
  val vtype = new VType
  val vl    = Vl()
}
```

VType 包含：illegal、vma、vta、vsew（2-bit 编码）、vlmul（3-bit 编码）。

### 4.4 结构转换

代码提供了多种结构之间的转换函数：

- `VType.fromInstVType(instVType)`：从指令立即数字段提取 VType
- `VType.fromVtypeStruct(vtypeStruct)`：从 CSR 结构转换为内部 VType
- `VType.toVtypeStruct(vtype)`：从内部 VType 转换为 CSR 结构
- `VsetVType.fromInstVType(instVType)`：用于 vsetvli/vsetivli 的立即数解析
- `VsetVType.fromVtypeStruct(vtypeStruct)`：用于 vsetvl 的寄存器值解析

### 4.5 非法 vtype 初始化

当 keepVl 操作需要参考旧 vtype 时，若无旧值可用，使用 initVtype() 初始化：

```scala
def initVtype()(implicit p: Parameters) : VType = {
  val res = Wire(VType())
  res.illegal := true.B  // 标记为非法
  res.vma := false.B
  res.vta := false.B
  res.vsew := 0.U
  res.vlmul := 0.U
  res
}
```

---

## 5. VLMUL/VSEW/VLMAX 计算

### 5.1 VLmul 编码

VLmul（Vector Length Multiplier）使用 3 位编码，定义在 `src/main/scala/xiangshan/backend/fu/vector/Bundles.scala`：

```scala
object VLmul extends NamedUInt(3) {
  def mf8: UInt = "b101".U  // 1/8
  def mf4: UInt = "b110".U  // 1/4
  def mf2: UInt = "b111".U  // 1/2
  def m1:  UInt = "b000".U  // 1
  def m2:  UInt = "b001".U  // 2
  def m4:  UInt = "b010".U  // 4
  def m8:  UInt = "b011".U  // 8
}
```

保留值：b100 为非法编码，isReserved 函数会检测此值。

### 5.2 VSew 编码

VSew（Standard Element Width）使用 2 位编码（3 位用于合法性检查）：

```scala
object VSew extends NamedUInt(2) {
  def e8  : UInt = "b000".U  // 8-bit
  def e16 : UInt = "b001".U  // 16-bit
  def e32 : UInt = "b010".U  // 32-bit
  def e64 : UInt = "b011".U  // 64-bit
  def reserved: BitPat = BitPat("b1??")  // 任何 >= 4 的值非法
}
```

### 5.3 LMUL/SEW 组合合法性表

VsetModule 通过以下矩阵验证 LMUL/SEW 组合的合法性（注释中的表格）：

```
     | mf8 | mf4 | mf2 | m1  | m2  | m4  | m8  |
e8   | 101 | 110 | 111 | 000 | 001 | 010 | 011 |
e16  |  R  | 101 | 110 | 111 | 000 | 001 | 010 |
e32  |  R  |  R  | 101 | 110 | 111 | 000 | 001 |
e64  |  R  |  R  |  R  | 101 | 110 | 111 | 000 |
```

注意 vlmul 不进行符号扩展，因为各组合的编码天然不重叠。这种设计利用了 RISC-V Vector 规范中 LMUL 和 SEW 必须满足 LMUL*SEW >= VLEN/ELEN 的约束。

### 5.4 LMUL/SEW 比率计算

VsetModule 还计算了 vlmul 与 vsew 的差值（log2 域），用于 keepVl 合法性检查：

```scala
private val log2NewRatio = vlmul - vsew
private val log2OldRatio = oldVt.vlmul - oldVt.vsew
// keepVl 合法条件：新旧 LMUL/SEW 比率相同
```

这个检查确保了在 rd=x0, rs1=x0（keep vl）的情况下，新的 LMUL/SEW 比率与旧值一致，避免 vl 值在新配置下失去意义。

### 5.5 SEW 最大值约束

当 LMUL < 1（fractional）时，SEW 不能超过 ELEN：

```scala
private val log2Elen = log2Up(ELEN)
private val log2VsewMax = Mux(log2Vlmul(2), log2Elen.U + log2Vlmul, log2Elen.U)
```

其中 log2Vlmul(2) 检测 LMUL < 1 的情况（编码值的最高位为 1）。当 LMUL < 1 时，允许的最大 SEW 为 ELEN * LMUL。

---

## 6. VSet Wrapper 三种变体设计

### 6.1 VSetBase 基类

VSetBase（`src/main/scala/xiangshan/backend/fu/wrapper/VSet.scala`）继承自 PipedFuncUnit，实现零延迟路径。它处理所有 vset 指令共有的输入解析逻辑：

```scala
// vsetivli: avl 来自立即数; vsetvl/vsetvli: avl 来自 rs1
protected val avl = Mux(VSETOpType.isVsetivli(in.ctrl.fuOpType), avlImm, in.data.src(0))

// vtype 解析：vsetvli/vsetivli 从立即数, vsetvl 从 rs2
protected val vtype: VsetVType = Mux(VSETOpType.isVsetvl(in.ctrl.fuOpType), 
    VsetVType.fromVtypeStruct(in.data.src(1).asTypeOf(new VtypeStruct())), vtypeImm)
```

### 6.2 VSetRiWi（读整数寄存器写整数寄存器）

用于 vsetvl 指令的第二个 uop（写 rd 寄存器）：

```scala
class VSetRiWi(cfg: FuConfig)(implicit p: Parameters) extends VSetBase(cfg) {
  vsetModule.io.in.avl := avl
  vsetModule.io.in.vtype := vtype
  out.res.data := vsetModule.io.out.vconfig.vl  // 写入 rd = vl
}
```

### 6.3 VSetRiWvf（读整数寄存器写向量配置寄存器）

用于 vsetvl/vsetvli/vsetivli 的第一个 uop（更新 vtype+vl）：

```scala
class VSetRiWvf(cfg: FuConfig)(implicit p: Parameters) extends VSetBase(cfg) {
  vsetModule.io.in.avl := avl
  vsetModule.io.in.vtype := vtype
  val vl = vsetModule.io.out.vconfig.vl
  val vlmax = vsetModule.io.out.vlmax
  val isVsetvl = VSETOpType.isVsetvl(in.ctrl.fuOpType)

  out.res.data := vl
  out.ctrl.pdestVl.get := in.ctrl.pdestVl.get
  // 信号传递给向量执行单元
  if (cfg.writeVlRf) io.vtype.get.bits := vsetModule.io.out.vconfig.vtype
  if (cfg.writeVlRf) io.vtype.get.valid := io.out.valid && isVsetvl
  if (cfg.writeVlRf) io.vlIsZero.get := io.out.valid && vl === 0.U
  if (cfg.writeVlRf) io.vlIsVlmax.get := io.out.valid && vl === vlmax
}
```

关键信号 vlIsVlmax 允许后续向量执行单元在 vl=vlmax 时使用优化路径。

### 6.4 VSetRvfWvf（读向量配置寄存器写向量配置寄存器）

用于 keepVl 场景（rd=x0, rs1=x0），以及 csrr vl（读取当前 vl 值）：

```scala
class VSetRvfWvf(cfg: FuConfig)(implicit p: Parameters) extends VSetBase(cfg) {
  val oldVL = in.data.vl.get  // 从向量配置寄存器读取旧 vl
  vsetModule.io.in.avl := oldVL  // 使用旧 vl 作为输入
  vsetModule.io.in.vtype := vtype
  // 传递旧 vtype 用于 keepVl 检查
  protected val oldVt = in.ctrl.vpu.get.specVType
  vsetModule.io.in.oldVt.get := oldVt

  // csrr vl 指令返回旧 vl，其他返回新计算的 vl
  out.res.data := Mux(isCSRReadVl, oldVL, vl)
}
```

---

## 7. VSETOpType 操作码编码

### 7.1 编码格式

VSETOpType 使用 8 位编码（`src/main/scala/xiangshan/package.scala`）：

```
位域分配：
[7]   - isVsetvli 标志
[6]   - isVsetvl 标志
[5]   - destTypeBit (0: 写整数 rd, 1: 写向量配置寄存器)
[4]   - readVecRG (0: 读整数寄存器, 1: 读向量配置寄存器)
[3]   - 保留
[2]   - 保留
[1]   - keepVlBit (保持 vl 不变)
[0]   - setVlmaxBit (设置 vl 为 vlmax)
```

### 7.2 主要操作码

**vsetvli 变体**（bit[7]=1, bit[6]=0）：
- `uvsetvcfg_xi` (b1010_0000)：正常情况，写 vconfig
- `uvsetrd_xi` (b1000_0000)：正常情况，写 rd
- `uvsetvcfg_vlmax_i` (b1010_0001)：rs1=x0, 写 vconfig, vl=vlmax
- `uvsetrd_vlmax_i` (b1000_0001)：rs1=x0, 写 rd, rd=vlmax
- `uvsetvcfg_keep_v` (b1010_0010)：rd=x0, rs1=x0, 保持 vl, 写 vconfig

**vsetvl 变体**（bit[6]=1, bit[7]=0）：
- `uvsetvcfg_xx` (b0110_0000)：正常情况，写 vconfig
- `uvsetrd_xx` (b0100_0000)：正常情况，写 rd
- `uvsetvcfg_vlmax_x` (b0110_0001)：rs1=x0, 写 vconfig, vl=vlmax
- `uvsetrd_vlmax_x` (b0100_0001)：rs1=x0, 写 rd, rd=vlmax
- `uvmv_v_x` (b0110_0010)：先将 x[rs2] 写入 vtmp
- `uvsetvcfg_vv` (b0111_0010)：读取 vtmp 和 vconfig，更新 vconfig

**vsetivli 变体**（bit[7]=0, bit[6]=0）：
- `uvsetvcfg_ii` (b0010_0000)：写 vconfig
- `uvsetrd_ii` (b0000_0000)：写 rd

**特殊操作码**：
- `csrrvl` (b0001_0110)：读取当前 vl 值到整数寄存器

### 7.3 解码与 uop 拆分

在 `src/main/scala/xiangshan/backend/decode/VecDecoder.scala` 中，vset 指令的解码规则：

```scala
VSETVLI   -> VSET(vli = F, vtypei = T, VSETOpType.uvsetvcfg_xi, flushPipe = F, blockBack = F, SelImm.IMM_VSETVLI)
VSETIVLI  -> VSET(vli = T, vtypei = T, VSETOpType.uvsetvcfg_ii, flushPipe = F, blockBack = F, SelImm.IMM_VSETIVLI)
VSETVL    -> VSET(vli = F, vtypei = F, VSETOpType.uvsetvcfg_xx, flushPipe = T, blockBack = T, SelImm.X)
```

注意 vsetvl 设置了 flushPipe=T 和 blockBack=T，因为 vsetvl 会修改整个向量执行状态，需要更严格的同步。

在 `src/main/scala/xiangshan/backend/decode/DecodeUnitComp.scala` 中，vset 指令被拆分为两个 uop：

1. uop0：计算并更新 vtype+vl 配置（VSetRiWvf/VSetRvfWvf）
2. uop1：将 vl 值写入整数寄存器 rd（VSetRiWi）

---

## 8. Fence 功能单元 FSM 设计

### 8.1 整体架构

Fence 功能单元位于 `src/main/scala/xiangshan/backend/fu/Fence.scala`（97 行），实现了一个 6 状态有限状态机（FSM），用于处理所有类型的屏障指令。与大多数单周期或流水线化的 FU 不同，Fence 单元需要多周期来完成复杂的同步操作。

### 8.2 FSM 状态定义

```scala
val s_idle :: s_wait :: s_tlb :: s_icache :: s_fence :: s_nofence :: Nil = Enum(6)
```

各状态含义：

| 状态 | 名称 | 功能 |
|------|------|------|
| s_idle | 空闲态 | 初始状态，接收新的 fence 指令，发送 sbuffer flush 请求 |
| s_wait | 等待态 | 持续发送 sbuffer flush，等待 sbuffer 变为空 |
| s_tlb | TLB 刷新态 | 执行 sfence.vma，刷新 TLB（保持一个周期） |
| s_icache | I-Cache 刷新态 | 执行 fence.i，刷新指令缓存（保持一个周期） |
| s_fence | 屏障态 | 普通 fence 指令的最终状态（时序优化） |
| s_nofence | 无屏障态 | Svinval 扩展指令的最终状态 |

### 8.3 状态转移逻辑

```scala
// 进入等待态
when (state === s_idle && io.in.valid) { state := s_wait }

// fence.i: 等待 sbuffer 清空后进入 icache 刷新
when (state === s_wait && func === FenceOpType.fencei && sbEmpty) { state := s_icache }

// sfence/hfence: 等待 sbuffer 清空后进入 TLB 刷新
when (state === s_wait && ((func === FenceOpType.sfence || 
     func === FenceOpType.hfence_g || func === FenceOpType.hfence_v) && sbEmpty)) { state := s_tlb }

// 普通 fence: 等待 sbuffer 清空后进入 fence 状态
when (state === s_wait && func === FenceOpType.fence && sbEmpty) { state := s_fence }

// nofence (Svinval): 等待 sbuffer 清空后进入 nofence 状态
when (state === s_wait && func === FenceOpType.nofence && sbEmpty) { state := s_nofence }

// 所有非 idle/wait 状态最终返回 idle
when (state =/= s_idle && state =/= s_wait) { state := s_idle }
```

### 8.4 关键信号生成

```scala
// sbuffer flush: 在 s_wait 状态持续有效
sbuffer := state === s_wait

// fencei: 仅在 s_icache 状态有效
fencei := state === s_icache

// sfence: 仅在 s_tlb 状态且操作码为 sfence/hfence_v/hfence_g 时有效
sfence.valid := state === s_tlb && (func === FenceOpType.sfence || 
                   func === FenceOpType.hfence_v || func === FenceOpType.hfence_g)
```

### 8.5 Handshake 信号

```scala
io.in.ready := state === s_idle  // 仅在空闲态接收新指令
io.out.valid := state =/= s_idle && state =/= s_wait  // 非 idle/wait 时输出有效
```

断言保证：`assert(!io.out.valid || io.out.ready)`，即输出有效时 ready 必须为 true。

---

## 9. fence.i 指令流水线刷新

### 9.1 执行流程

fence.i 指令的执行路径：s_idle -> s_wait -> s_icache -> s_idle

1. **s_idle**：接收 fence.i 指令，设置 sbuffer flush 信号。
2. **s_wait**：持续发送 sbuffer flush，等待 sbuffer 变为空（sbEmpty 信号由 FenceToSbuffer 接口传入）。
3. **s_icache**：当 sbuffer 清空后，发出 fencei 信号（`fencei := state === s_icache`），通知 I-Cache 执行失效操作。此状态仅保持一个周期。
4. 返回 s_idle。

### 9.2 流水线刷新机制

fence.i 指令在解码阶段设置了 flushPipe = T 和 blockBack = T：

```scala
FENCE_I -> XSDecode(SrcType.pc, SrcType.imm, SrcType.X, FuType.fence, FenceOpType.fencei, 
                     SelImm.X, noSpec = T, blockBack = T, flushPipe = T)
```

- `noSpec = T`：不可推测执行，必须严格按程序顺序执行
- `blockBack = T`：阻塞后续指令进入
- `flushPipe = T`：执行完成后刷新流水线

### 9.3 Sbuffer 交互

Fence 单元通过 FenceToSbuffer 接口与 Sbuffer 交互：

```scala
class FenceToSbuffer extends Bundle {
  val flushSb = Output(Bool())   // 请求 flush sbuffer
  val sbIsEmpty = Input(Bool())  // sbuffer 是否已空
}
```

在 fence.i 执行前，必须确保 sbuffer 中的所有 store 操作已刷出到 L1 DCache，否则可能导致 store-to-load ordering 违反。

---

## 10. sfence.vma TLB 失效处理

### 10.1 执行流程

sfence.vma 指令的执行路径：s_idle -> s_wait -> s_tlb -> s_idle

1. **s_idle**：接收 sfence.vma 指令，设置 sbuffer flush。
2. **s_wait**：等待 sbuffer 清空。
3. **s_tlb**：发出 sfence.valid 信号，执行 TLB 失效。此状态仅保持一个周期。

### 10.2 SfenceBundle 结构

sfence.vma 的执行通过 SfenceBundle 接口（定义在 `src/main/scala/xiangshan/Bundle.scala`）：

```scala
class SfenceBundle(implicit p: Parameters) extends XSBundle {
  val valid = Bool()
  val bits = new Bundle {
    val rs1 = Bool()           // 是否指定虚拟地址（rs1 != x0）
    val rs2 = Bool()           // 是否指定 ASID（rs2 != x0）
    val addr = UInt(VAddrBits.W)  // 虚拟地址（来自 src(0)）
    val id = UInt(AsidLength.W)   // ASID 或 VMID（来自 src(1)）
    val flushPipe = Bool()        // 是否需要刷新流水线
    val hv = Bool()               // H 扩展: hfence.vvma
    val hg = Bool()               // H 扩展: hfence.gvma
  }
}
```

### 10.3 rs1/rs2 判断逻辑

```scala
sfence.bits.rs1 := uop.data.imm(4, 0) === 0.U  // imm[4:0]==0 表示 rs1=x0
sfence.bits.rs2 := uop.data.imm(9, 5) === 0.U  // imm[9:5]==0 表示 rs2=x0
```

RISC-V 规范中 sfence.vma 的语义：
- rs1=x0, rs2=x0：失效所有 TLB 条目
- rs1!=x0, rs2=x0：失效所有包含该虚拟地址的 TLB 条目
- rs1=x0, rs2!=x0：失效所有包含该 ASID 的 TLB 条目
- rs1!=x0, rs2!=x0：失效包含该虚拟地址且 ASID 匹配的 TLB 条目

### 10.4 H 扩展支持

Fence 单元支持 H 扩展的 hfence.vvma 和 hfence.gvma：

```scala
sfence.bits.hv := func === FenceOpType.hfence_v  // 虚拟机-虚拟地址空间
sfence.bits.hg := func === FenceOpType.hfence_g  // 虚拟机-主机地址空间
```

在 DecodeUnit 中的解码：

```scala
HFENCE_GVMA -> XSDecode(SrcType.reg, SrcType.reg, SrcType.X, FuType.fence, FenceOpType.hfence_g, 
                         SelImm.X, noSpec = T, blockBack = T, flushPipe = T)
HFENCE_VVMA -> XSDecode(SrcType.reg, SrcType.reg, SrcType.X, FuType.fence, FenceOpType.hfence_v, 
                         SelImm.X, noSpec = T, blockBack = T, flushPipe = T)
```

### 10.5 Svinval 扩展

XiangShan 支持 Svinval 扩展，将 sfence.vma 分解为两个独立操作：

```scala
SINVAL_VMA -> XSDecode(SrcType.reg, SrcType.reg, SrcType.X, FuType.fence, FenceOpType.sfence, ...)
SFENCE_W_INVAL -> XSDecode(SrcType.DC, SrcType.DC, SrcType.X, FuType.fence, FenceOpType.nofence, ...)
SFENCE_INVAL_IR -> XSDecode(SrcType.DC, SrcType.DC, SrcType.X, FuType.fence, FenceOpType.nofence, ..., flushPipe = T)
```

其中 nofence 操作码对应 Svinval 扩展中的非阻塞阶段，使用 s_nofence 状态处理。

---

## 11. Jump/Branch 单元设计

### 11.1 JumpDataModule

JumpDataModule（`src/main/scala/xiangshan/backend/fu/Jump.scala`，60 行）负责处理 JAL、JALR、AUIPC 三条跳转指令的目标地址计算：

```scala
class JumpDataModule(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    val src = Input(UInt(XLEN.W))       // rs1 值
    val pc = Input(UInt(XLEN.W))        // 当前 PC
    val imm = Input(UInt(33.W))         // 立即数（33 位，支持 U-type）
    val nextPcOffset = Input(...)        // 下一条指令偏移
    val func = Input(FuOpType())        // 操作类型
    val isRVC = Input(Bool())           // 是否为压缩指令
    val result, target = Output(UInt(XLEN.W))
    val isAuipc = Output(Bool())
  })
```

**目标地址计算**：

```scala
val target = Mux(JumpOpType.jumpOpisJalr(func), src1 + offset, pc + offset)
io.target := Cat(target(XLEN - 1, 1), false.B)  // 最低位置零（RISC-V 规范）
```

对于 JALR：target = (rs1 + imm) & ~1
对于 JAL/AUIPC：target = pc + imm

**返回值计算**：

```scala
val snpc = pc + io.nextPcOffset  // 下一条顺序指令地址
io.result := Mux(JumpOpType.jumpOpisAuipc(func), target, snpc)
```

对于 AUIPC：result = target（即 pc + imm）
对于 JAL/JALR：result = snpc（即跳转后下一条指令地址，作为 link 寄存器的返回地址）

### 11.2 BranchModule

BranchModule（`src/main/scala/xiangshan/backend/fu/Branch.scala`，51 行）负责处理所有条件分支指令：

```scala
class BranchModule(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle() {
    val src = Vec(2, Input(UInt(XLEN.W)))  // rs1, rs2
    val func = Input(FuOpType())           // 操作类型
    val fixedTaken = Input(Bool())         // 推测是否跳转
    val taken, mispredict = Output(Bool()) // 实际跳转结果, 是否预测错误
  })
```

**比较逻辑**：

```scala
val subModule = Module(new SubModule)
subModule.io.src(0) := src1
subModule.io.src(1) := src2
val sub = subModule.io.sub

val sltu = !sub(XLEN)          // 无符号小于：sub 最高位为 0
val slt = src1(XLEN-1) ^ src2(XLEN-1) ^ sltu  // 有符号小于：考虑符号位
val xor = src1 ^ src2          // 相等判断：异或结果全零
```

**分支条件表**：

```scala
val branchOpTable = List(
  BRUOpType.getBranchType(BRUOpType.beq)  -> !xor.orR,   // BEQ: 相等
  BRUOpType.getBranchType(BRUOpType.blt)  -> slt,         // BLT: 有符号小于
  BRUOpType.getBranchType(BRUOpType.bltu) -> sltu         // BLTU: 无符号小于
)
val taken = LookupTree(BRUOpType.getBranchType(func), branchOpTable) ^ BRUOpType.isBranchInvert(func)
```

BNE/BGE/BGEU 通过 isBranchInvert(func) 标志取反实现，避免重复比较逻辑。

**预测错误检测**：

```scala
io.mispredict := io.fixedTaken ^ taken  // 固定预测与实际不同则预测错误
```

### 11.3 BRUOpType 编码

```scala
object BRUOpType {
  def beq  = "b000_000".U
  def bne  = "b000_001".U
  def blt  = "b000_100".U
  def bge  = "b000_101".U
  def bltu = "b001_000".U
  def bgeu = "b001_001".U
  
  def getBranchType(func: UInt) = func(3, 1)  // 提取类型位
  def isBranchInvert(func: UInt) = func(0)    // 提取反转位
}
```

位分配：`[5:3] 保留, [3:1] 分支类型, [0] 是否取反`

### 11.4 JumpOpType 编码

```scala
object JumpOpType {
  def isJalr(op: UInt) = op(0)
  def isAuipc(op: UInt) = op(1)
}
```

通过 op 的低位区分 JAL、JALR、AUIPC 三种跳转类型。

---

## 12. 源文件位置汇总

### 12.1 核心源文件

| 文件 | 行数 | 功能 |
|------|------|------|
| `src/main/scala/xiangshan/backend/fu/Vsetu.scala` | 133 | VsetModule 核心计算逻辑 |
| `src/main/scala/xiangshan/backend/fu/Fence.scala` | 97 | Fence FSM 状态机实现 |
| `src/main/scala/xiangshan/backend/fu/Jump.scala` | 60 | JumpDataModule 跳转地址计算 |
| `src/main/scala/xiangshan/backend/fu/Branch.scala` | 51 | BranchModule 分支条件计算 |

### 12.2 Wrapper 与集成文件

| 文件 | 功能 |
|------|------|
| `src/main/scala/xiangshan/backend/fu/wrapper/VSet.scala` | VSet 三种变体 Wrapper (VSetRiWi, VSetRiWvf, VSetRvfWvf) |
| `src/main/scala/xiangshan/backend/fu/vector/Bundles.scala` | VType, VsetVType, VConfig, VSew, VLmul 定义 |
| `src/main/scala/xiangshan/backend/fu/CSR.scala` | VtypeStruct 定义（CSR 层面 vtype 结构） |

### 12.3 解码与操作码文件

| 文件 | 功能 |
|------|------|
| `src/main/scala/xiangshan/package.scala` | VSETOpType, FenceOpType, BRUOpType, JumpOpType 操作码定义 |
| `src/main/scala/xiangshan/backend/decode/VecDecoder.scala` | vsetvli/vsetivli/vsetvl 指令解码规则 |
| `src/main/scala/xiangshan/backend/decode/DecodeUnit.scala` | sfence.vma/fence.i/fence/hfence 等指令解码规则 |
| `src/main/scala/xiangshan/backend/decode/DecodeUnitComp.scala` | vset 指令的 uop 拆分逻辑 |

### 12.4 接口定义文件

| 文件 | 功能 |
|------|------|
| `src/main/scala/xiangshan/Bundle.scala` (L553-568) | SfenceBundle 接口定义 |
| `src/main/scala/xiangshan/backend/fu/Fence.scala` (L31-34) | FenceToSbuffer 接口定义 |

### 12.5 TLB 相关文件

| 文件 | 功能 |
|------|------|
| `src/main/scala/xiangshan/cache/mmu/MMUBundle.scala` | TLB bundles，包含 sfence 连接信号 |
| `src/main/scala/xiangshan/cache/mmu/PageTableCache.scala` | 页表缓存，使用 sfence_dup 进行 TLB 失效 |
| `src/main/scala/xiangshan/cache/mmu/Repeater.scala` | TLB Repeater，接收 sfence 信号 |

---

## 总结

XiangShan 的 Vsetu 和 Fence 功能单元展示了处理器后端中非数据计算类 FU 的设计范式：

**Vsetu 的设计特点**：
1. 零延迟路径设计，不占用执行流水线周期
2. 三种 Wrapper 变体适应不同的读写端口组合
3. 基于对数域的 VLMAX 计算避免除法硬件
4. 完善的非法检测覆盖保留编码和配置约束
5. 8 位操作码编码紧凑地覆盖所有 vset 指令变体

**Fence 的设计特点**：
1. 6 状态 FSM 覆盖所有屏障指令类型
2. Sbuffer 同步确保 store ordering 正确性
3. 统一处理 sfence.vma、fence.i、普通 fence 及 H 扩展指令
4. 支持 Svinval 扩展的分阶段执行
5. 通过 noSpec/blockBack/flushPipe 保证执行顺序正确

**Jump/Branch 的设计特点**：
1. JumpDataModule 采用组合逻辑直接计算，延迟低
2. BranchModule 通过减法器复用实现所有比较操作
3. 利用位域编码巧妙实现分支反转，减少硬件开销
4. mispredict 信号直接由固定预测与实际结果异或产生

这两个单元虽然代码量不大（合计约 340 行核心逻辑），但承载了处理器状态管理的关键功能，是理解 XiangShan 微架构中向量扩展和内存屏障机制的核心切入点。
