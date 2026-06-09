# XiangShan 执行单元 (EXU/FU) 深度研究报告

## 1. 概述

XiangShan 处理器的执行后端采用层次化的 Execution Unit (EXU) 与 Functional Unit (FU) 架构。每个 EXU 是一个执行单元外壳（wrapper），内部封装一个或多个 FU；FU 才是真正执行计算的逻辑模块。这种分层设计使得调度器（Issue Queue）只需关心 EXU 粒度的发射与唤醒，而 FU 粒度的多路复用与流水线细节被封装在 EXU 内部。

**关键设计特征：**
- EXU 由 `ExeUnitParams` 描述，FU 由 `FuConfig` 描述
- 一个 EXU 可包含多个不同类型的 FU，通过 Dispatcher 按 `fuType` 路由指令
- 支持多种写回端口（Int RF / FP RF / Vec RF / V0 RF / Vl RF）
- 支持不确定延迟（Uncertain Latency）和确定延迟（Certain Latency）两种模式
- 内置 bypass 网络从 EXU 输出端口直接获取前递数据

---

## 2. EXU/FU 层次结构

```
Backend (后端)
├── IntScheduler (整数调度器)
│   └── IssueBlock (发射块)
│       ├── ExuBlock
│       │   ├── ExeUnit (ALU0)         ← AluCfg (FuType.alu)
│       │   ├── ExeUnit (BJU0)         ← BrhCfg + JmpCfg (FuType.brh, jmp)
│       │   ├── ExeUnit (ALU1)         ← AluCfg
│       │   ├── ExeUnit (BJU1)         ← BrhCfg + JmpCfg
│       │   ├── ExeUnit (MUL/DIV)      ← MulCfg + DivCfg (FuType.mul, div)
│       │   ├── ExeUnit (CSR)          ← CsrCfg (FuType.csr)
│       │   ├── ExeUnit (FENCE)        ← FenceCfg (FuType.fence)
│       │   └── ExeUnit (BKU)          ← BkuCfg (FuType.bku)
│       └── ...
├── FpScheduler (浮点调度器)
│   └── IssueBlock
│       ├── ExuBlock
│       │   ├── ExeUnit (FALU)         ← FaluCfg (FuType.falu)
│       │   ├── ExeUnit (FMA)          ← FmacCfg (FuType.fmac)
│       │   ├── ExeUnit (FCVT)         ← FcvtCfg (FuType.fcvt)
│       │   ├── ExeUnit (FCMP/I2F)     ← FcmpCfg + I2fCfg
│       │   └── ExeUnit (FDIV)         ← FdivCfg (FuType.fDivSqrt)
│       └── ...
├── VecScheduler (向量调度器)
│   └── IssueBlock
│       ├── ExuBlock
│       │   ├── ExeUnit (VFEX0)        ← VialuCfg/VimacCfg/VfaluCfg/...
│       │   ├── ExeUnit (VFEX1)        ← 类似
│       │   └── ...
│       └── ...
└── MemScheduler (访存调度器)
    └── Load/Store 执行单元 (独立于 ExuBlock)
```

**层次关系说明：**

1. **ExuBlock** (`ExuBlock.scala`)：整个非访存执行块的顶层容器，实例化所有非访存 EXU，连接 CSR IO、Fence IO、跨域数据通路（I2F/F2I）以及前端 BJU Resolve 信号。
2. **ExeUnit** (`ExeUnit.scala`)：单个执行单元，包含 Dispatcher（1-to-N 分发器）和多个 FuncUnit 实例。接收来自 Issue Queue 的指令，分发给合适的 FU，收集结果并输出。
3. **FuncUnit** (`FuncUnit.scala`)：抽象功能单元基类，具体实现为 Alu、MulUnit、DivUnit、BranchUnit、JumpUnit、CSR、Fence、FAlu、FMA、FDivSqrt、FCVT、FCMP 等。

---

## 3. 功能单元类型与延迟

### 3.1 整数功能单元

| FU 名称 | FuType | 实现文件 | 延迟模式 | 延迟 (cycles) | 输出寄存器文件 | 备注 |
|---------|--------|---------|---------|-------------|-------------|------|
| **ALU** | `alu` | `wrapper/Alu.scala` + `Alu.scala` (DataModule) | CertainLatency(0) | 0 (组合逻辑) | Int RF | 包含加法、减法、移位、逻辑运算、位操作、Zicond、JALR link 地址计算等；支持 RISC-V Zba/Zbb/Zbs/Zbc/Zbkb 扩展 |
| **MUL** | `mul` | `wrapper/MulUnit.scala` + `Multiplier.scala` | CertainLatency(2) | 2 | Int RF | 基于 Booth 编码的阵列乘法器（ArrayMulDataModule），使用 Wallace/CSA 树压缩，支持 MUL/MULH/MULHSU/MULHU/MULW |
| **DIV** | `div` | `wrapper/DivUnit.scala` + `SRT16Divider.scala` | UncertainLatency() | 不确定 (可变) | Int RF | SRT radix-4 除法器，支持符号/无符号除法、DIV/DIVU/DIVW/DIVUW/REM/REMU/REMW/REMUW，具有 `outValidAhead3Cycle` 提前唤醒信号 |
| **Branch** | `brh` | `wrapper/BranchUnit.scala` + `Branch.scala` | CertainLatency(0) | 0 | 无数据写回 | 执行条件分支 BEQ/BNE/BLT/BGE/BLTU/BGEU，计算 taken/mispredict，产生 redirect |
| **Jump** | `jmp` | `wrapper/JumpUnit.scala` + `Jump.scala` | CertainLatency(0) | 0 | Int RF | 执行 JAL/JALR/AUIPC，计算 target 地址，产生 redirect |
| **CSR** | `csr` | `wrapper/CSR.scala` + `NewCSR/` | UncertainLatency() | 不确定 (可变) | Int RF | CSR 读写、特权指令（ECALL/EBREAK/MRET/SRET/DRET/WFI）、中断处理；具有 `outValidAhead3Cycle` 提前唤醒 |
| **Fence** | `fence` | `Fence.scala` | UncertainLatency() | 不确定 (FSM) | 无数据写回 | 处理 FENCE/FENCE.I/SFENCE.VMA 等，等待 SBuffer 排空后刷新 TLB/ICache |
| **BKU** | `bku` | `Bku.scala` | CertainLatency(2) | 2 | Int RF | 位计数/位操作单元，实现 CLZ/CTZ/CPOP/BREV8/ORC.B/ZEXT.H 等 Zbb/Zbkb 扩展指令 |

**ALU 延迟说明：** `AluCfg` 的默认延迟为 `CertainLatency(0)`，意味着 ALU 的数据路径是纯组合逻辑，一个时钟周期内完成计算。但 ALU wrapper 仍然使用 `PipedFuncUnit` 框架，`latency = 0` 表示没有中间流水线寄存器。

**MUL 延迟说明：** `MulCfg` 延迟为 `CertainLatency(2)`，意味着乘法器内部有 2 级流水线寄存器。`ArrayMulDataModule` 使用多层 CSA 压缩树，通过 `regEnable` 在流水线级间锁存部分积和压缩中间结果。

**DIV 延迟说明：** `DivCfg` 使用 `UncertainLatency()`，延迟取决于除数和被除数的值。`SRT16DividerDataModule` 实现了 radix-4 的 SRT 除法算法，通过前导零检测（LZC）进行规范化，每周期产生 2 位商。具有 7 个状态：`s_idle -> s_pre_0 -> s_pre_1 -> s_iter -> s_post_0 -> s_post_1 -> s_finish`。

### 3.2 浮点功能单元

| FU 名称 | FuType | 实现文件 | 延迟模式 | 延迟 (cycles) | 输出寄存器文件 | 备注 |
|---------|--------|---------|---------|-------------|-------------|------|
| **FALU** | `falu` | `wrapper/FALU.scala` + `fpu/FpPipedFuncUnit.scala` | CertainLatency(1) | 1 | FP RF | 浮点加减法、比较、符号注入等，基于 `yunsuan.fpu.FloatAdder` |
| **FMA** | `fmac` | `wrapper/FMA.scala` | CertainLatency(3) | 3 | FP RF | 浮点融合乘加（FMA/FMS/FNMA/FNMS/FMUL），基于 `yunsuan.fpu.FloatFMA` |
| **FDIV/FSQRT** | `fDivSqrt` | `wrapper/FDivSqrt.scala` | UncertainLatency() | 不确定 | FP RF | 浮点除法/开方，具有 `outValidAhead3Cycle` 提前唤醒 |
| **FCVT** | `fcvt` | `wrapper/FCVT.scala` | CertainLatency(2+1=3) | 3 (2 base + 1 extra) | FP RF + Int RF | 浮点-整数转换（FCVT.W.S/D、FCVT.S.W/D 等） |
| **FCMP** | `fcmp` | `wrapper/FCMP.scala` | CertainLatency(0+3=3) | 3 (0 base + 3 extra) | Int RF | 浮点比较（FEQ/FLT/FLE），结果写入整数寄存器 |
| **I2F** | `i2f` | `wrapper/I2F.scala` | CertainLatency(2+1=3) | 3 (2 base + 1 extra) | FP RF | 整数到浮点转换（FCVT.S.W/D 等） |

**extraLatencyVal 说明：** 部分 FU 使用 `CertainLatency(base, extraValue)` 的两段延迟描述。`extraValue` 代表在 FU 计算完成后的额外流水线级数，用于时序优化（fix-timing）。在 `FuncUnit.scala` 的 `HasPipelineReg` trait 中，总延迟被分为 `preLat = latency - latdiff` 和 `latdiff` 两段，前段是计算流水线，后段是时序修复流水线。

### 3.3 向量功能单元

| FU 名称 | FuType | 延迟 | 输出 RF | 备注 |
|---------|--------|------|---------|------|
| **VIAluFix** | `vialuF` | CertainLatency(1) | Vec RF, V0 RF | 向量整数 ALU |
| **VIMacU** | `vimac` | CertainLatency(2) | Vec RF, V0 RF | 向量整数乘加 |
| **VPPU** | `vppu` | CertainLatency(2) | Vec RF, V0 RF | 向量打包/排列 |
| **VIPU** | `vipu` | CertainLatency(2) | Int RF, Vec RF, V0 RF | 向量整数处理 |
| **VFAlu** | `vfalu` | CertainLatency(1) | Vec RF, V0 RF | 向量浮点 ALU |
| **VFMA** | `vfma` | CertainLatency(3) | Vec RF, V0 RF | 向量浮点融合乘加 |
| **VFDivSqrt** | `vfdiv` | UncertainLatency() | Vec RF, V0 RF | 向量浮点除法/开方 |
| **VCVT** | `vfcvt` | CertainLatency(2) | Vec RF, V0 RF | 向量浮点转换 |
| **VMove** | `vmove` | CertainLatency(0+3=3) | Int RF, FP RF, Vec RF, V0 RF | 向量移动/类型转换 |
| **VIDiv** | `vidiv` | UncertainLatency() | Vec RF, V0 RF | 向量整数除法 |
| **VSet** | `vsetiwi/vsetiwf/vsetfwf` | CertainLatency(0) | Int RF, Vl RF | vsetvl/vsetvli/vsetivli |

### 3.4 FuConfig 与 FuType 完整列表

FuType 枚举定义了所有功能单元类型（见 `FuType.scala`），共计 40+ 种类型，分为四大类：

- **整数运算 (intArithAll):** `jmp, brh, i2f, i2v, csr, alu, mul, div, fence, bku`
- **浮点运算 (fpArithAll):** `falu, fcvt, fmac, fDivSqrt, f2v, fcmp`
- **访存 (scalaMemAll):** `ldu, stu, mou`
- **向量运算 (vecArith/vecMem/vecVSET/vecMove):** `vipu, vialuF, vppu, vimac, vidiv, vfalu, vmove, vfma, vfdiv, vfcvt, vldu, vstu, vsegldu, vsegstu, vsetiwi, vsetiwf, vsetfwf`

---

## 4. ExeUnit 内部工作原理

### 4.1 ExeUnit 结构 (`ExeUnit.scala`)

`ExeUnitImp` 类是执行单元的核心实现，关键组件如下：

```
ExeUnitImp
├── Dispatcher (in1ToN)          -- 1-to-N 指令分发器
├── funcUnits: Seq[FuncUnit]     -- 功能单元实例数组
├── pipelineReg                  -- 输入流水线寄存器（用于带延迟的 FU）
├── fuOutValidOH                  -- FU 输出 valid 选择信号（one-hot）
├── outIntData / outFpData / ...  -- 多写回端口数据 MUX
├── uncertainWakeupOut           -- 不确定延迟 FU 的提前唤醒信号
└── toFrontendBJUResolve         -- 分支/跳转解析信号送前端
```

### 4.2 指令分发 (Dispatcher)

`Dispatcher` 模块根据指令的 `fuType` 字段，将输入指令路由到对应的功能单元：

```scala
def acceptCond(input: NewExuInput): Seq[Bool] = {
  input.params.fuConfigs.map(_.fuSel(input))
}
```

每个 FU 的 `fuSel` 方法检查 `uop.ctrl.fuType === this.fuType.U`。Dispatcher 确保同一时刻只激活一个 FU（`PopCount(acceptVec) <= 1`），并要求所有 FU 都准备好（`io.in.ready := Cat(io.out.map(_.ready)).andR`）。

### 4.3 流水线控制

**带延迟的 FU (CertainLatency)：** ExeUnit 在入口处维护一个 pipeline register 阵列，深度为所有 FU 中最大的延迟值。控制信号（robIdx、pdest、rfWen 等）在 pipelineReg 中逐级传递，最终送到对应 FU 的 `ctrlPipe`/`dataPipe`/`validPipe` 端口。这样每个 FU 都能在正确的流水级取到对应的控制信息。

**不确定延迟的 FU (UncertainLatency)：** 如 DIV、CSR、FDIV 等，它们不经过 pipelineReg，直接使用 `connectNonPipedCtrlSingal`（通过 `RegEnable` 在 `in.fire` 时锁存控制信号）或 `connectNonPipedCtrlDataHoldBypass`（通过 `DataHoldBypass` 持锁存值直到新数据到来）。

### 4.4 Clock Gating

`ExeUnitImp` 为每个 FU 实现了精细的时钟门控（Clock Gating）逻辑：

- **lat0（零延迟 FU）：** 仅在 `in.fire` 时开启时钟一个周期
- **latN（多级流水 FU）：** 跟踪 valid 信号在流水线中的传播，只要有任何一级有 valid 数据就保持时钟开启
- **不确定延迟 FU：** 在 `in.fire` 到 `out.fire` 期间保持时钟开启
- **特殊 FU（CSR/Fence）：** `ckAlwaysEn = true`，时钟始终开启

---

## 5. 功能单元详细设计

### 5.1 ALU (`Alu.scala` + `wrapper/Alu.scala`)

ALU 是最基础也是使用最频繁的功能单元，延迟为 0（组合逻辑一个周期内完成）。

**支持的操作：**
- **算术运算：** ADD、ADDW、SUB、SUBW、ADDUW、ADDIW（Zba）
- **移位加法：** SH1ADD、SH2ADD、SH3ADD、SH4ADD（Zba）；SR29ADD、SR30ADD、SR31ADD、SR32ADD
- **移位运算：** SLL、SRL、SRA、SLLW、SRLW、SRAW、SLLIUW（Zba）
- **循环移位：** ROL、ROR、ROLW、RORW（Zbb/Zbkb）
- **位操作：** BCLR、BSET、BINV、BEXT（Zbs）；CPOP、CLZ、CTZ（Zbb）
- **逻辑运算：** AND、OR、XOR、ANDN、ORN、XNOR（Zbb/Zbkb）
- **位宽操作：** SEXT.B、SEXT.H、ZEXT.H、PACK、PACKH、PACKW、BREV8、ORC.B（Zbb/Zbkb）
- **比较运算：** SLT、SLTU、MAX、MAXU、MIN、MINU（Zbb）
- **条件零：** CZERO.EQZ、CZERO.NEZ（Zicond）
- **跳转链接：** JALR 的 link address = PC + 4（当 `aluNeedPc = true` 时）

ALU 内部通过子模块实现各类运算（`AddModule`、`SubModule`、`LeftShiftModule`、`RightShiftModule`、`RotateLeftShiftModule`、`RotateRightShiftModule`、`MiscResultSelect`、`ConditionalZeroModule`），最终通过 `Mux1H` 选择结果。

### 5.2 乘法器 (`Multiplier.scala` + `wrapper/MulUnit.scala`)

乘法器采用 **Booth 编码阵列乘法器**（`ArrayMulDataModule`），延迟 2 个周期。

**架构：**
1. 对操作数 B 进行 Booth 编码（Radix-4），生成部分积（partial products）
2. 使用 CSA（Carry-Save Adder）树压缩部分积：`C22`、`C32`、`C53` 压缩器
3. 递归压缩直到每列最多 2 个 bit，最后用常规加法器求和
4. 第 4 层压缩后插入流水线寄存器（`depth == 4` 时 `needReg = true`）

**延迟优化：** `MulCfg` 的 `latency = CertainLatency(2)`，但实际数据路径在 `ArrayMulDataModule` 中通过 2 个 `regEnable` 阶段实现：阶段 0 锁存部分积，阶段 1 锁存中间压缩结果。

### 5.3 除法器 (`SRT16Divider.scala` + `wrapper/DivUnit.scala`)

除法器采用 **SRT Radix-4** 算法，延迟不确定，取决于操作数。

**状态机：**
```
s_idle -> s_pre_0 -> s_pre_1 -> s_iter (循环) -> s_post_0 -> s_post_1 -> s_finish
```

**关键特性：**
- 前导零检测（LZC）进行规范化
- 每次迭代产生 2 位商（quotient digit ∈ {-2, -1, 0, 1, 2}）
- 支持 kill_w（写入时取消）和 kill_r（读取时取消）两种 flush 机制
- 提供 `outValidAhead3Cycle` 信号用于提前 3 周期唤醒后续依赖指令
- 输入缓冲区大小为 4（`hasInputBuffer = (true, 4, true)`）

### 5.4 分支单元 (`BranchUnit.scala` + `Branch.scala`)

分支单元延迟为 0（组合逻辑），核心操作包括：
1. **条件判断：** 使用 `SubModule` 计算 rs1 - rs2，根据 BEQ/BNE/BLT/BGE/BLTU/BGEU 产生 taken 信号
2. **目标地址计算：** `AddrAddModule` 计算 `pcExtend + SignExt(imm)` (taken) 或 `pcExtend + nextPcOffset` (not taken)
3. **误预测检测：** 比较实际 taken/target 与预测结果，产生 `mispredict` 信号
4. **Redirect 生成：** 设置 `RedirectLevel.flushAfter`，包含完整目标地址、FTQ 指针等信息

### 5.5 跳转单元 (`JumpUnit.scala` + `Jump.scala`)

跳转单元延迟为 0，处理 JAL、JALR、AUIPC：
- **JAL：** target = PC + SignExt(imm)
- **JALR：** target = (rs1 + SignExt(imm)) & ~1
- **AUIPC：** target = PC + SignExt(imm)，result = target（而非 link address）
- 产生 redirect 和前端 BJU Resolve 信号

### 5.6 CSR 功能单元 (`wrapper/CSR.scala` + `NewCSR/`)

CSR 单元是最复杂的功能单元之一，属于不确定延迟。

**主要功能：**
1. **CSR 读写：** 支持 CSRRW/CSRRS/CSRRC/CSRRWI/CSRRSI/CSRRCI
2. **特权指令：** ECALL、EBREAK、MRET、SRET、DRET、MNRET、WFI
3. **异常处理：** 生成 EX_BP、EX_MCALL、EX_HSCALL、EX_VSCALL、EX_UCALL、EX_II、EX_VI 等异常
4. **TLB 配置：** 输出 satp/vsatp/hgatp 等页表配置信号
5. **中断处理：** 连接 AIA（Advanced Interrupt Architecture）的 IMSIC 模块
6. **自定义控制：** 输出各种自定义 CSR 位（分支预测控制、内存预测控制等）

CSR 单元内部实例化了 `NewCSR` 模块（一个大型状态机），处理异常/中断的进入和返回逻辑。输出延迟为 3 个周期（`DelayN`），除非是 XRET 指令则立即输出。

### 5.7 Fence 单元 (`Fence.scala`)

Fence 单元使用 FSM 实现，状态机为：
```
s_idle -> s_wait -> s_tlb / s_icache / s_fence / s_nofence -> s_idle
```

1. **s_idle：** 等待指令到达
2. **s_wait：** 发送 SBuffer flush 信号，等待 SBuffer 排空
3. **s_tlb：** 刷新 TLB（SFENCE/HFENCE）
4. **s_icache：** 刷新 ICache（FENCE.I）
5. **s_fence：** 普通 FENCE，等待后完成
6. **s_nofence：** Svinval 扩展的 NOFENCE

### 5.8 BKU (`Bku.scala`)

BKU（Bit-counting/Knowledge Unit）延迟 2 个周期，实现位计数相关操作：
- CLZ（Count Leading Zeros）
- CTZ（Count Trailing Zeros）
- CPOP（Count Population / Popcount）
- 及其字节/半字变体

使用流水线化的 `CountModule` 实现，通过 `regEnable` 在流水线级间锁存数据。

---

## 6. 写回机制 (Writeback)

### 6.1 多端口写回架构

XiangShan 支持多种寄存器文件的写回端口：

- **Int RF (整数寄存器文件):** `toRf` 中的整数端口
- **FP RF (浮点寄存器文件):** `toRf` 中的浮点端口
- **Vec RF (向量寄存器文件):** 向量寄存器文件
- **V0 RF (向量掩码寄存器):** v0 寄存器
- **Vl RF (向量长度寄存器):** vl 寄存器

在 `ExeUnitImp` 中，每个可写 RF 的类型都有独立的 valid/data 信号组：

```scala
io.out.bits.toIntRf.valid := Mux1H(fuOutValidOH, fuIntWenVec) || F2IIntWen
io.out.bits.toIntRf.bits  := outIntData.get
io.out.bits.toFpRf.valid  := Mux1H(fuOutValidOH, fuFpWenVec)
io.out.bits.toFpRf.bits   := outFpData.get
// ... 类似 for Vec/V0/Vl
```

### 6.2 ExeUnit 输出 MUX

当一个 EXU 包含多个可写同一 RF 的 FU 时（如 ExeUnit 内的 FcmpCfg 和 FcvtCfg 都写 Int RF），使用 `Mux1H` 进行 one-hot 选择：

```scala
val vld = funcUnits.zip(fuOutValidOH).filter { case (fu, _) => fu.cfg.writeIntRf }
  .map { case (fu, fuoutOH) => fuoutOH && fu.io.out.bits.ctrl.rfWen.getOrElse(false.B) }
val data = funcUnits.zip(fuOutresVec).filter { case (fu, _) => fu.cfg.writeIntRf }
  .map { case (_, fuout) => fuout.data }
outIntData.foreach(_ := Mux1H(vld, data))
```

### 6.3 跨域数据通路 (I2F/F2I)

对于需要跨整数/浮点寄存器文件写回的场景（如 I2F 写 FP RF，FCMP/F2I 写 Int RF），`ExuBlock` 通过 `ExuCrossRegion` 传递数据：

- `I2FDataIn` / `I2FDataOut`：整数到浮点的数据通路
- `F2IDataIn` / `F2IDataOut`：浮点到整数的数据通路

这些数据通过 `ExuBlock` 的 `cross` 接口路由到目标 EXU 的写回端口。

### 6.4 WakeUp 与寄存器缓存 (Register Cache)

**唤醒机制 (WakeUp)：**
- `iqWakeUpSourcePairs` / `iqWakeUpSinkPairs`：Issue Queue 之间的唤醒连接
- `copyWakeupOut`：某些 EXU（如 ALU）需要复制唤醒信号到多个 Issue Queue
- `uncertainWakeupOut`：不确定延迟 FU（DIV/CSR/FDIV 等）使用 `outValidAhead3Cycle` 提前 3 周期发送唤醒信号

**寄存器缓存 (Register Cache)：**
当 `regCacheEn = true` 时，整数和访存 EXU 支持 RegCache 读写：
- `needReadRegCache`：Int EXU 和读 Int RF 的 Mem EXU
- `needWriteRegCache`：作为 IQ WakeUp Source 的 Int/Mem EXU

BypassNetwork 在写入 RegCache 时维护 `forwardTagVec`/`bypassTagVec` 用于 tag 比较。

---

## 7. Bypass/Forwarding 网络

### 7.1 BypassNetwork 概述

Bypass Network (`datapath/BypassNetwork.scala`) 是 XiangShan 后端数据通路的核心组件，位于 Issue Queue 输出（Og1 Stage）和 EXU 输入之间。它负责：

1. 从寄存器文件读取数据（RegOH/RegCache）
2. 从 EXU 输出端口直接前递数据（Forward/Bypass）
3. 处理立即数提取
4. 处理向量掩码生成

### 7.2 三级数据源

BypassNetwork 支持三种数据来源，由 `dataSource` 信号控制：

```
┌─────────────────────────────────────────────────────────────┐
│                    BypassNetwork                            │
│                                                             │
│  dataSources(srcIdx) 控制多路选择：                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐   │
│  │readForward│  │readBypass│  │readBypass2│  │readRegOH │   │
│  │  (1 cycle │  │ (2 cycle │  │ (3 cycle  │  │(RegFile) │   │
│  │   delay)  │  │  delay)  │  │  delay)   │  │          │   │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘   │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                  │
│  │readRegCache│ │readImm   │  │readZero  │                  │
│  │(RegCache) │  │(立即数)  │  │ (置零)   │                  │
│  └──────────┘  └──────────┘  └──────────┘                  │
└─────────────────────────────────────────────────────────────┘
```

### 7.3 Bypass 拓扑

**Forward (1-cycle bypass):**
```scala
readForward -> Mux1H(forwardOrBypassValidVec3(exuIdx)(srcIdx), forwardDataVec)
```
`forwardDataVec` 直接来自 `fromExus` 的 EXU 输出数据，无额外寄存器延迟。

**Bypass (2-cycle bypass):**
```scala
readBypass -> Mux1H(forwardOrBypassValidVec3(exuIdx)(srcIdx), bypassDataVec)
```
`bypassDataVec` 是 `fromExus` 数据经过一级 `RegEnable` 或 `RegNext` 后的版本，多一拍延迟。

**Bypass2 (3-cycle bypass):**
```scala
readBypass2 -> Mux1H(bypass2ValidVec3(bypass2ExuIdx)(srcIdx), bypass2DataVec)
```
`bypass2DataVec` 是 `bypassDataVec` 再经过一级 `RegNext`，共两拍延迟。用于 VFEX/Mem EXU 之间读取向量寄存器文件的场景。

### 7.4 选择逻辑

BypassNetwork 通过 `forwardOrBypassValidVec3` 建立了完整的 EXU-to-EXU 前递矩阵：

```scala
private val forwardOrBypassValidVec3: MixedVec[Vec[Vec[Bool]]] = MixedVecInit(
  fromDPs.map { (x: DecoupledIO[Og1InUop]) =>
    VecInit(x.bits.exuSources.map(_.map(_.toExuOH(x.bits.exuParams))).getOrElse(
      VecInit(Seq.fill(x.bits.exuParams.numRegSrc max 1)(VecInit(0.U(params.numExu.W).asBools)))
    ))
  }
)
```

每个 `(exuIdx, srcIdx, bypassExuIdx)` 三元组表示：exuIdx 号 EXU 的 srcIdx 号源操作数，可以从 bypassExuIdx 号 EXU 的输出获取数据。

### 7.5 RegCache 写入

BypassNetwork 还负责将 EXU 输出数据写入 RegCache（寄存器缓存）：

```scala
io.toDataPath.zipWithIndex.foreach { case (x, i) =>
  x.wen  := bypassIntWenVec(i)      // 写使能延迟一拍
  x.data := bypassRCDataVec(i)       // 写数据
  x.tag  := bypassTagVec(i)          // 写标签（用于 tag match）
}
```

---

## 8. 异常处理

### 8.1 EXU 级异常产生

每个 FU 可以配置它能产生的异常类型（`exceptionOut` 字段），由 `FuncUnitCtrlOutput.exceptionVec` 输出：

| FU | 可产生的异常 |
|----|------------|
| Load (ldu) | loadAddrMisaligned, loadAccessFault, loadPageFault, loadGuestPageFault, breakPoint, hardwareError, storeAddrMisaligned, storeAccessFault, storePageFault, storeGuestPageFault |
| Store (stu) | storeAddrMisaligned, storeAccessFault, storePageFault, storeGuestPageFault, breakPoint, hardwareError |
| CSR | illegalInstr, virtualInstr, breakPoint, ecallU, ecallS, ecallVS, ecallM |
| ALU/MUL/BKU | 无异常 |
| 向量 FU | illegalInstr（当 vstart != 0 时） |

### 8.2 异常在 ExeUnit 中的汇聚

`ExeUnitImp` 通过 `ExceptSparseVec.mux1h` 从所有 FU 的异常输出中选择一个：

```scala
io.out.bits.toRob.bits.exceptionVec := ExceptSparseVec.mux1h(fuOutValidOH, fuOutBitsVec.map(_.ctrl.exceptionVec))
```

### 8.3 Flush 与 Replay

- **flushPipe：** CSR 和 Fence 单元设置 `flushPipe = true`，表示该指令需要冲刷后续流水线
- **replayInst：** Load/Store 单元在特定条件下设置 `replayInst`，指令需要重放
- **redirect：** Branch、Jump 和 CSR（XRET）单元产生 redirect，触发前端重定向和后端 flush

### 8.4 CSR 异常处理详细流程

CSR 单元的异常处理路径：
1. 接收 ROB 提交的 exception（来自 `csrIn.exception`）
2. 接收 Memory 的异常 VA/GPA（来自 `csrIn.memExceptionVAddr`）
3. `TrapHandleModule` 决定 trap 目标 PC
4. `TrapInstMod` 跟踪 trap 指令信息
5. `TrapTvalMod` 生成 tval 值
6. 通过 `csrMod.io.trapTargetPc` 输出目标地址
7. 设置 redirect 信号，flush 整个流水线

---

## 9. 关键源文件索引

### 9.1 EXU 层

| 文件 | 说明 |
|------|------|
| `src/main/scala/xiangshan/backend/exu/ExeUnit.scala` | ExeUnit 定义和 ExeUnitImp 实现（指令分发、FU 实例化、输出 MUX、时钟门控） |
| `src/main/scala/xiangshan/backend/exu/ExeUnitParams.scala` | ExeUnit 参数定义（FU 配置、写回端口、延迟映射、唤醒配置） |
| `src/main/scala/xiangshan/backend/exu/ExuBlock.scala` | ExuBlock 顶层容器（连接所有非访存 EXU、CSR/Fence IO、跨域数据通路） |

### 9.2 FU 基础设施层

| 文件 | 说明 |
|------|------|
| `src/main/scala/xiangshan/backend/fu/FuType.scala` | FuType 枚举定义（所有功能单元类型） |
| `src/main/scala/xiangshan/backend/fu/FuConfig.scala` | FuConfig case class 和所有预定义配置（AluCfg, MulCfg, DivCfg 等） |
| `src/main/scala/xiangshan/backend/fu/FuncUnit.scala` | FuncUnit 抽象基类、FuncUnitIO、HasPipelineReg trait、PipedFuncUnit |

### 9.3 整数 FU 实现层

| 文件 | 说明 |
|------|------|
| `src/main/scala/xiangshan/backend/fu/Alu.scala` | ALU DataModule（运算逻辑实现） |
| `src/main/scala/xiangshan/backend/fu/wrapper/Alu.scala` | ALU Wrapper（连接 DataModule 到 FuncUnit 接口） |
| `src/main/scala/xiangshan/backend/fu/Multiplier.scala` | ArrayMulDataModule（Booth 编码阵列乘法器） |
| `src/main/scala/xiangshan/backend/fu/wrapper/MulUnit.scala` | 乘法器 Wrapper |
| `src/main/scala/xiangshan/backend/fu/SRT16Divider.scala` | SRT Radix-4 除法器 |
| `src/main/scala/xiangshan/backend/fu/wrapper/DivUnit.scala` | 除法器 Wrapper |
| `src/main/scala/xiangshan/backend/fu/Branch.scala` | BranchModule（条件分支判断逻辑） |
| `src/main/scala/xiangshan/backend/fu/wrapper/BranchUnit.scala` | 分支单元 Wrapper（含地址计算和 redirect 生成） |
| `src/main/scala/xiangshan/backend/fu/Jump.scala` | JumpDataModule（跳转目标计算） |
| `src/main/scala/xiangshan/backend/fu/wrapper/JumpUnit.scala` | 跳转单元 Wrapper |
| `src/main/scala/xiangshan/backend/fu/Bku.scala` | BKU（位计数/位操作） |
| `src/main/scala/xiangshan/backend/fu/Fence.scala` | Fence 单元（FSM 实现） |

### 9.4 CSR 实现层

| 文件 | 说明 |
|------|------|
| `src/main/scala/xiangshan/backend/fu/wrapper/CSR.scala` | CSR Wrapper（连接 NewCSR 到 FuncUnit 接口） |
| `src/main/scala/xiangshan/backend/fu/NewCSR/NewCSR.scala` | NewCSR 主模块 |
| `src/main/scala/xiangshan/backend/fu/NewCSR/CSRBundle.scala` | CSR Bundle 定义 |
| `src/main/scala/xiangshan/backend/fu/NewCSR/CSRDefines.scala` | CSR 字段定义 |
| `src/main/scala/xiangshan/backend/fu/NewCSR/MachineLevel.scala` | M 模式 CSR |
| `src/main/scala/xiangshan/backend/fu/NewCSR/SupervisorLevel.scala` | S 模式 CSR |
| `src/main/scala/xiangshan/backend/fu/NewCSR/HypervisorLevel.scala` | HS 模式 CSR |
| `src/main/scala/xiangshan/backend/fu/NewCSR/Unprivileged.scala` | 非特权 CSR |
| `src/main/scala/xiangshan/backend/fu/NewCSR/CSREvents/` | 各种 trap/ret 事件处理 |

### 9.5 浮点 FU 实现层

| 文件 | 说明 |
|------|------|
| `src/main/scala/xiangshan/backend/fu/fpu/FPU.scala` | FPU 类型定义（f16/f32/f64） |
| `src/main/scala/xiangshan/backend/fu/fpu/FpPipedFuncUnit.scala` | 流水线浮点 FU 基类 |
| `src/main/scala/xiangshan/backend/fu/fpu/FpNonPipedFuncUnit.scala` | 非流水线浮点 FU 基类 |
| `src/main/scala/xiangshan/backend/fu/wrapper/FALU.scala` | 浮点 ALU Wrapper |
| `src/main/scala/xiangshan/backend/fu/wrapper/FMA.scala` | 浮点 FMA Wrapper |
| `src/main/scala/xiangshan/backend/fu/wrapper/FCVT.scala` | 浮点转换 Wrapper |
| `src/main/scala/xiangshan/backend/fu/wrapper/FCMP.scala` | 浮点比较 Wrapper |
| `src/main/scala/xiangshan/backend/fu/wrapper/FDivSqrt.scala` | 浮点除法/开方 Wrapper |
| `src/main/scala/xiangshan/backend/fu/wrapper/I2F.scala` | 整数到浮点转换 Wrapper |

### 9.6 向量 FU 实现层

| 文件 | 说明 |
|------|------|
| `src/main/scala/xiangshan/backend/fu/wrapper/VIPU.scala` | 向量整数处理单元 |
| `src/main/scala/xiangshan/backend/fu/wrapper/VIAluFix.scala` | 向量整数 ALU |
| `src/main/scala/xiangshan/backend/fu/wrapper/VIMacU.scala` | 向量整数乘加 |
| `src/main/scala/xiangshan/backend/fu/wrapper/VIDiv.scala` | 向量整数除法 |
| `src/main/scala/xiangshan/backend/fu/wrapper/VPPU.scala` | 向量打包单元 |
| `src/main/scala/xiangshan/backend/fu/wrapper/VFALU.scala` | 向量浮点 ALU |
| `src/main/scala/xiangshan/backend/fu/wrapper/VFMA.scala` | 向量浮点 FMA |
| `src/main/scala/xiangshan/backend/fu/wrapper/VFDivSqrt.scala` | 向量浮点除法/开方 |
| `src/main/scala/xiangshan/backend/fu/wrapper/VCVT.scala` | 向量浮点转换 |
| `src/main/scala/xiangshan/backend/fu/wrapper/VMove.scala` | 向量移动 |
| `src/main/scala/xiangshan/backend/fu/wrapper/VSet.scala` | vset 指令 |

### 9.7 数据通路层

| 文件 | 说明 |
|------|------|
| `src/main/scala/xiangshan/backend/datapath/BypassNetwork.scala` | Bypass 网络（前递/旁路选择、立即数提取、向量掩码生成） |
| `src/main/scala/xiangshan/backend/datapath/DataPath.scala` | 数据通路顶层 |
| `src/main/scala/xiangshan/backend/Bundles.scala` | EXU 输入输出 Bundle（NewExuInput/ExuBypassBundle 等） |

---

## 10. 总结

XiangShan 的执行单元设计体现了现代高性能乱序处理器的典型特征：

1. **层次化封装：** ExuBlock -> ExeUnit -> FuncUnit 三级层次，职责分明
2. **灵活的延迟模型：** CertainLatency 和 UncertainLatency 两种模式，支持确定性和可变延迟 FU
3. **多写回端口：** Int/FP/Vec/V0/Vl 五种寄存器文件的独立写回端口
4. **精细的时钟门控：** 按 FU 粒度进行 clock gating，节省功耗
5. **高效的 Bypass 网络：** 支持 forward(1-cycle)、bypass(2-cycle)、bypass2(3-cycle) 三级前递
6. **完整的异常处理：** 从 FU 产生到汇聚到 ROB 提交的完整异常处理链路
7. **RISC-V 扩展支持：** 原生支持 Zba/Zbb/Zbs/Zbc/Zbkb/Zicond 以及向量扩展
