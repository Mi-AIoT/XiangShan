# XiangShan 功能单元深度分析报告: ALU, BKU, Multiplier

**报告编号:** R46
**分析对象:** XiangShan RISC-V 处理器 -- 整数功能单元 (Integer Functional Units)
**分析日期:** 2026-06-09
**代码版本:** XiangShan 主线 (基于 Mulan PSL v2)

---

## 目录

1. [概述与架构总览](#1-概述与架构总览)
2. [FuncUnit 基类与流水线抽象](#2-funcunit-基类与流水线抽象)
3. [FuConfig 配置体系与 EXU 绑定](#3-fuconfig-配置体系与-exu-绑定)
4. [ALU: 算术逻辑单元深度分析](#4-alu-算术逻辑单元深度分析)
5. [BKU: 位操作与密码学单元深度分析](#5-bku-位操作与密码学单元深度分析)
6. [Multiplier: Booth Radix-4 阵列乘法器深度分析](#6-multiplier-booth-radix-4-阵列乘法器深度分析)
7. [Bypass/Forwarding 接口与数据通路](#7-bypassforwarding-接口与数据通路)
8. [源文件位置索引](#8-源文件位置索引)

---

## 1. 概述与架构总览

XiangShan 是一款高性能乱序执行 (out-of-order) RISC-V 处理器, 其后端 (backend) 采用 superscalar 架构设计, 通过功能单元 (Functional Units, FU) 实现各类指令的执行. 在 XiangShan 的整数执行路径中, 三个核心功能单元分别承担不同职责:

- **ALU (Arithmetic Logic Unit)**: 执行基础整数算术、逻辑、移位、位操作 (Zba/Zbb/Zbs) 及条件置零 (Zicond) 指令, 同时兼做分支目标地址计算.
- **BKU (Bit Manipulation & Crypto Unit)**: 执行 Zbb 扩展中的计数/位操作 (clz/ctz/cpop/clmul/xperm) 以及国密 (SM3/SM4) 和 SHA-256/SHA-512 密码学加速指令.
- **Multiplier**: 执行有符号/无符号整数乘法, 支持高位结果 (MULH/MULHSU/MULHU) 和低位结果 (MUL), 采用 Booth radix-4 编码配合 Wallace tree 压缩结构.

这三个功能单元均位于 XiangShan 后端的整数调度器 (Integer Scheduler) 管辖下, 通过 `FuConfig` 配置系统与 EXEUnit (执行单元) 模块绑定, 并通过统一的 `FuncUnit` 基类实现流水线抽象和 Bypass 接口.

---

## 2. FuncUnit 基类与流水线抽象

### 2.1 基类定义 (`FuncUnit`)

`FuncUnit` 是所有功能单元的抽象基类, 位于 `src/main/scala/xiangshan/backend/fu/FuncUnit.scala`. 它定义了:

**IO 接口 (`FuncUnitIO`)**:
- `flush`: 来自 ROB 的重定向信号, 用于冲刷流水线.
- `in`: `DecoupledIO[FuncUnitInput]` -- 标准的 valid/ready 握手输入端口, 包含控制信号 (`FuncUnitCtrlInput`) 和数据信号 (`FuncUnitDataInput`).
- `out`: `DecoupledIO[FuncUnitOutput]` -- 标准输出端口, 包含控制结果 (`FuncUnitCtrlOutput`) 和数据结果 (`FuncUnitDataOutput`).
- 特殊端口: `csrin/csrio` (CSR 接口), `toFrontendBJUResolve` (分支解析), `fenceio` (屏障控制), `frm/vxrm` (浮点/向量舍入模式), `vtype/vlIsZero/vlIsVlmax` (向量配置输出).

**控制信号直连辅助方法**:
- `connectNonPipedCtrlSingal`: 用于非流水线功能单元, 将输入控制信号通过 `RegEnable` 寄存一级后直连到输出.
- `connectNonPipedCtrlDataHoldBypass`: 使用 `DataHoldBypass` 保持数据有效, 适用于非流水线单元需要在 backpressure 场景下保持输出稳定的场景.
- `connect0LatencyCtrlSingal`: 用于零延迟功能单元, 控制信号直接穿透 (combinational pass-through).

### 2.2 流水线寄存器抽象 (`HasPipelineReg` trait)

`HasPipelineReg` 是一个关键的 trait, 为所有流水线功能单元提供通用的流水线寄存器管理:

```scala
trait HasPipelineReg { this: FuncUnit =>
  def latency: Int
  val latdiff: Int = cfg.latency.extraLatencyVal.getOrElse(0)
  val preLat: Int = latency - latdiff
```

**核心机制**:
- `pipelineReg()` 方法生成 `latency` 级流水线寄存器, 每级包含控制信号 (`ctrl`) 和数据信号 (`data`) 两组寄存器.
- `validVec` 和 `rdyVec` 构成标准 valid/ready 流水线协议: 下游就绪 (`rdyVec(i)`) 等价于当前级为空或下一级就绪; 上游有效 (`validVec(i)`) 等价于上一级有效且上一级到当前级握手成功.
- 支持 flush: 每级的 `robIdx` 通过 `needFlush` 检查, 一旦匹配重定向条件, 该级的 valid 信号被清零.
- **Fix-timing pipeline**: `latdiff` 参数允许在核心流水线之外额外插入延迟级, 用于 timing closure. `fixtiminginit` 从核心流水线最后一级取数据, 再通过 `pipelineReg(fixtiminginit, ..., latdiff, ...)` 插入额外延迟级.

**辅助方法**:
- `regEnable(i)`: 返回第 `i` 级流水线寄存器的使能信号 (`validVec(i-1) && rdyVec(i-1)`).
- `PipelineReg[TT](i)(next)`: 在第 `i` 级插入 `RegEnable`.
- `S1Reg/S2Reg/S3Reg/S4Reg/S5Reg`: 分别对应第 1-5 级流水线寄存器的快捷方式.

### 2.3 PipedFuncUnit

```scala
abstract class PipedFuncUnit(override val cfg: FuConfig)(implicit p: Parameters)
  extends FuncUnit(cfg) with HasPipelineReg {
  override def latency: Int = cfg.latency.latencyVal.get
}
```

`PipedFuncUnit` 是流水线功能单元的便捷抽象, 自动从 `FuConfig.latency.latencyVal` 获取流水线深度. ALU 即继承自此类.

---

## 3. FuConfig 配置体系与 EXU 绑定

### 3.1 FuConfig 结构

`FuConfig` 是一个 case class, 定义在 `src/main/scala/xiangshan/backend/fu/FuConfig.scala`, 完整描述了一个功能单元的所有静态特征:

| 参数 | 类型 | 含义 |
|------|------|------|
| `name` | `String` | 功能单元名称 (如 "alu", "mul", "bku") |
| `fuType` | `FuType.OHType` | 功能单元类型, one-hot 编码 |
| `fuGen` | `(Parameters, FuConfig) => FuncUnit` | 工厂方法, 用于生成具体的 `FuncUnit` 实例 |
| `srcData` | `Seq[Seq[DataConfig]]` | 源操作数的数据类型和端口配置 |
| `piped` | `Boolean` | 是否为流水线单元 |
| `writeIntRf` | `Boolean` | 是否写整数寄存器堆 |
| `writeFpRf` | `Boolean` | 是否写浮点寄存器堆 |
| `latency` | `HasFuLatency` | 延迟配置: `CertainLatency(base, extra)` 或 `UncertainLatency(opt)` |
| `hasInputBuffer` | `(Boolean, Int, Boolean)` | 输入 buffer 配置 (是否需要, 深度, 是否需要 flush) |
| `exceptionOut` | `Seq[Int]` | 可产生的异常编号列表 |
| `flushPipe` | `Boolean` | 指令执行后是否需要冲刷流水线 |
| `replayInst` | `Boolean` | 指令是否可能需要 replay |
| `immType` | `Set[Imm]` | 支持的立即数类型集合 |

**延迟类型系统**:
- `CertainLatency(value, extraValue)`: 固定延迟, `latencyVal = Some(value + extraValue)`, `extraLatencyVal = Some(extraValue)`.
- `UncertainLatency(value)`: 不确定延迟, `latencyVal = None`, 适用于除法器、CSR 等执行时间不固定的单元.

### 3.2 三个核心 FU 的配置

**AluCfg**:
```scala
val AluCfg = FuConfig(
  name = "alu",
  fuType = FuType.alu,
  fuGen = (p, cfg) => Module(new Alu(cfg)(p)),
  srcData = Seq(Seq(IntData(), IntData())),
  piped = true,
  writeIntRf = true,
  latency = CertainLatency(0),
  immType = Set(Imm_I(), Imm_J(), Imm_U(), Imm_LUI32())
)
```
ALU 配置为单周期流水线. `CertainLatency(0)` 使得 `latencyVal = Some(0)`, 而 `PipedFuncUnit` 的 `latency` 返回此值. `pipelineReg` 中 `preLat = 0 - 0 = 0`, 意味着核心流水线无额外寄存器级 (组合逻辑穿透), 外部 wrapper (`Alu` class) 通过 `io.out.valid := io.in.valid` 直接连接. 支持 I/J/U/LUI32 四种立即数类型.

**MulCfg**:
```scala
val MulCfg = FuConfig(
  name = "mul",
  fuType = FuType.mul,
  fuGen = (p, cfg) => Module(new MulUnit(cfg)(p)),
  srcData = Seq(Seq(IntData(), IntData())),
  piped = true,
  writeIntRf = true,
  latency = CertainLatency(2)
)
```
乘法器配置为 2 级流水线. `latencyVal = Some(2)`, 使得 `HasPipelineReg` 生成 2 级流水线寄存器.

**BkuCfg**:
```scala
val BkuCfg = FuConfig(
  name = "bku",
  fuType = FuType.bku,
  fuGen = (p, cfg) => Module(new Bku(cfg)(p)),
  srcData = Seq(Seq(IntData(), IntData())),
  piped = true,
  writeIntRf = true,
  latency = CertainLatency(2)
)
```
BKU 同样为 2 级流水线. 代码注释中标注 "Todo: split it to simple bitmap exu and complex bku", 表明未来可能将简单位操作拆分为独立 EXU.

### 3.3 FuType 枚举

`FuType` 定义在 `src/main/scala/xiangshan/backend/fu/FuType.scala`, 采用 `ChiselOHEnum` (one-hot 编码枚举). 与本报告相关的类型:
- `FuType.alu` -- ALU
- `FuType.mul` -- 乘法器
- `FuType.bku` -- BKU
- `FuType.div` -- 除法器
- `FuType.jmp` -- 跳转单元
- `FuType.brh` -- 分支单元

整数算术所有类型: `intArithAll = Seq(jmp, brh, i2f, i2v, csr, alu, mul, div, fence, bku)`

### 3.4 EXU 绑定机制

功能单元通过 `ExeUnitImp` 模块绑定到执行单元 (EXU). `ExeUnitImp` 遍历 `exuParams.fuConfigs`, 调用每个 `FuConfig.fuGen` 工厂方法生成对应的 `FuncUnit` 实例:

```scala
val funcUnits = fuCfgs.map(cfg => {
  val module = cfg.fuGen(p, cfg)
  module
})
```

所有功能单元共享同一个输入/输出端口 (由 `ExeUnitIO` 定义), 每个 FU 通过 `fuSel` 方法判断是否响应当前指令 (基于 `fuType` 匹配). 时钟门控 (`EnableClockGate`) 根据各 FU 的 valid 信号独立控制, 减少动态功耗.

---

## 4. ALU: 算术逻辑单元深度分析

### 4.1 架构概览

ALU 由两层模块组成:
- **`Alu`** (位于 `xiangshan/backend/fu/wrapper/Alu.scala`): `PipedFuncUnit` 的具体实现, 负责 IO 接口对接和控制信号管理.
- **`AluDataModule`** (位于 `xiangshan/backend/fu/Alu.scala`): 核心数据通路模块, 实现所有算术/逻辑运算的组合逻辑.

`Alu` 的实现极为简洁 -- 它将 `io.in` 的源操作数和 `fuOpType` 直接连接到 `AluDataModule`, 并将计算结果赋值给 `io.out.bits.res.data`. 关键设计点:
- `aluNeedPc` 参数控制 ALU 是否需要 PC 值 (用于 AUIPC/JAL/JALR 目标地址计算).
- PC 值根据 `instrAddrTransType.shouldBeSext` 决定符号扩展或零扩展.

### 4.2 操作类型体系 (ALUOpType)

操作类型定义在 `package.scala` 中的 `object ALUOpType`, 使用 8-bit `FuOpType` 编码, 按高 3 位 (func[6:4]) 分为 7 大类, 共 60+ 种操作:

#### 4.2.1 移位操作 (func[6:4] = 000)

| 编码 | 操作 | 指令/含义 | RISC-V 扩展 |
|------|------|-----------|------------|
| 000_0000 | `slliuw` | `ZEXT(src1[31:0]) << shamt` | Zba |
| 000_0001 | `sll` | `src1 << src2[5:0]` | RV64I |
| 000_0010 | `bclr` | `src1 & ~(1 << src2[5:0])` | Zbs |
| 000_0011 | `bset` | `src1 \| (1 << src2[5:0])` | Zbs |
| 000_0100 | `binv` | `src1 ^ (1 << src2[5:0])` | Zbs |
| 000_0101 | `srl` | `src1 >> src2[5:0]` (逻辑) | RV64I |
| 000_0110 | `bext` | `(src1 >> src2[5:0])[0]` | Zbs |
| 000_0111 | `sra` | `src1 >> src2[5:0]` (算术) | RV64I |
| 000_1001 | `rol` | 循环左移 | Zbb |
| 000_1011 | `ror` | 循环右移 | Zbb |

**移位实现细节**: 移位器使用 `doShiftLeft` / `doShiftRightArith` / `doShiftRotateLeft` / `doShiftRotateRight` 工具函数 (位于 `fu/util/ShiftUtils.scala`). 这些函数采用递归分治策略 -- 对于 N-bit 移位量, 依次检查最高位, 若为 1 则移动 `2^(N-1)` 位, 否则不移动, 然后对剩余位递归处理. 这种设计在综合后生成 logarithmic barrel shifter, 延迟为 O(log shamt_width).

Zbs 位操作 (bclr/bset/binv/bext) 的实现利用了 ALU 已有的移位和加法通路:
```scala
val bitShift = 1.U << src2(5, 0)
val bclr = src1 & ~bitShift
val bset = src1 | bitShift
val binv = src1 ^ bitShift
val bext = srl(0)  // 复用右移结果
```

#### 4.2.2 RV64 W-suffix 操作 (func[6:4] = 001)

| 编码 | 操作 | 指令/含义 |
|------|------|-----------|
| 001_0000 | `addw` | `SEXT((src1 + src2)[31:0])` |
| 001_0001 | `oddaddw` | `SEXT((src1[0] + src2)[31:0])` |
| 001_0010 | `subw` | `SEXT((src1 - src2)[31:0])` |
| 001_0011 | `lui32addw` | `SEXT(SEXT(src2[11:0],32) + {src2[31:12], 12'b0})` |
| 001_0100 | `addwbit` | `(src1 + src2)[0]` |
| 001_0101 | `addwbyte` | `(src1 + src2)[7:0]` |
| 001_0110 | `addwzexth` | `ZEXT((src1 + src2)[15:0])` |
| 001_0111 | `addwsexth` | `SEXT((src1 + src2)[15:0])` |
| 001_1000 | `sllw` | `SEXT((src1 << src2)[31:0])` |
| 001_1001 | `srlw` | `SEXT((src1[31:0] >> src2)[31:0])` |
| 001_1010 | `sraw` | `SEXT((src1[31:0] >> src2)[31:0])` (算术) |
| 001_1100 | `rolw` | 循环左移 word |
| 001_1101 | `rorw` | 循环右移 word |

W-suffix 操作处理 RV64 中的 32-bit 子字操作. `addwbit/addwbyte/addwzexth/addwsexth` 是 XiangShan 自定义扩展的混合操作, 将加法结果截取不同宽度后零扩展/符号扩展返回. `AddWModule` 专门处理 32-bit 加法, 其 `addw` 输出通过多路选择器根据 `func` 位提取不同位宽:

```scala
val addw = Mux1H(Seq(
  (func(2) & !func(1) & !func(0)) -> ZeroExt(addwModule.io.addw(0),     XLEN / 2),
  (func(2) & !func(1) &  func(0)) -> ZeroExt(addwModule.io.addw(7, 0),  XLEN / 2),
  (func(2) &  func(1) & !func(0)) -> ZeroExt(addwModule.io.addw(15, 0), XLEN / 2),
  (func(2) &  func(1) &  func(0)) -> SignExt(addwModule.io.addw(15, 0), XLEN / 2),
  !func(2) -> addwModule.io.addw,
))
```

#### 4.2.3 ADD 变体 (func[6:4] = 010)

| 编码 | 操作 | 含义 | 扩展 |
|------|------|------|------|
| 010_0000 | `adduw` | `ZEXT(src1[31:0]) + src2` | Zba |
| 010_0001 | `oddadd` | `src1[0] + src2` | 自定义 |
| 010_0010 | `add` | `src1 + src2` | RV64I |
| 010_0011 | `lui32add` | `SEXT(src2[11:0]) + {src2[63:12], 12'b0}` | 自定义 |
| 010_0100 | `sr29add` | `src1[63:29] ZeroExt + src2` | Zba |
| 010_0101 | `sr30add` | `src1[63:30] ZeroExt + src2` | Zba |
| 010_0110 | `sr31add` | `src1[63:31] ZeroExt + src2` | Zba |
| 010_0111 | `sr32add` | `src1[63:32] ZeroExt + src2` | Zba |
| 010_1000 | `sh1adduw` | `{src1[31:0], 1'b0} + src2` | Zba |
| 010_1001 | `sh1add` | `{src1[62:0], 1'b0} + src2` | Zba |
| 010_1010 | `sh2adduw` | `{src1[31:0], 2'b0} + src2` | Zba |
| 010_1011 | `sh2add` | `{src1[61:0], 2'b0} + src2` | Zba |
| 010_1100 | `sh3adduw` | `{src1[31:0], 3'b0} + src2` | Zba |
| 010_1101 | `sh3add` | `{src1[60:0], 3'b0} + src2` | Zba |
| 010_1111 | `sh4add` | `{src1[59:0], 4'b0} + src2` | Zba |

Zba 扩展的 `sh*Nadd` 系列操作广泛用于地址计算 (如数组元素偏移). XiangShan 使用独立的加法器模块 (`AddModule`) 并行处理不同的移位-加法变体, 通过 `Mux1H` 选择移位后的 src1. 注意 `shaddSrc` 中 `Cat(Fill(32, func(0)), Fill(32, 1.U))` 的巧妙设计: 当执行 `_uw` (unsigned word) 变体时 (func(0)=0), 高 32 位被清零, 实现零扩展语义.

#### 4.2.4 SUB/比较操作 (func[6:4] = 011)

| 编码 | 操作 | 含义 |
|------|------|------|
| 011_0000 | `sub` | `src1 - src2` |
| 011_0001 | `sltu` | `src1 < src2` (无符号) |
| 011_0010 | `slt` | `src1 < src2` (有符号) |
| 011_0100 | `maxu` | `max(src1, src2)` (无符号) |
| 011_0101 | `minu` | `min(src1, src2)` (无符号) |
| 011_0110 | `max` | `max(src1, src2)` (有符号) |
| 011_0111 | `min` | `min(src1, src2)` (有符号) |

减法使用 `SubModule`, 实现为 `src1 + (~src2) + 1` (二进制补码减法). 结果为 `XLEN+1` 位 (包含进位位), 用于同时计算多个比较结果:

```scala
val sub  = subModule.io.sub
val sltu = !sub(XLEN)         // 无符号小于: 进位为 0 表示借位, 取反后为 true
val slt  = src1(XLEN-1) ^ src2(XLEN-1) ^ sltu  // 有符号小于
val maxMin  = Mux(slt ^ func(0), src2, src1)    // func(0)=1 为 min, =0 为 max
val maxMinU = Mux(sltu ^ func(0), src2, src1)
```

这种设计使得一次减法操作同时产生 SLT/SLTU/MAX/MIN 所需的所有信号, 避免重复计算.

#### 4.2.5 杂项/逻辑操作 (func[6:4] = 100)

| 编码 | 操作 | 含义 | 扩展 |
|------|------|------|------|
| 100_0000 | `and` | `src1 & src2` | RV64I |
| 100_0001 | `andn` | `src1 & ~src2` | Zbb |
| 100_0010 | `or` | `src1 \| src2` | RV64I |
| 100_0011 | `orn` | `src1 \| ~src2` | Zbb |
| 100_0100 | `xor` | `src1 ^ src2` | RV64I |
| 100_0101 | `xnor` | `src1 ^ ~src2` | Zbb |
| 100_0110 | `orcb` | 每字节 OR-reduce | Zbb |
| 100_1000 | `sextb` | `SEXT(src1[7:0])` | Zbb |
| 100_1001 | `packh` | `Cat(src2[7:0], src1[7:0])` | Zbb |
| 100_1010 | `sexth` | `SEXT(src1[15:0])` | Zbb |
| 100_1011 | `packw` | `SEXT(Cat(src2[15:0], src1[15:0]))` | Zbb |

逻辑操作通过 `logicSrc2 = Mux(!func(5) && func(0), ~src2, src2)` 统一处理: 当 `func` 为 ANDN/ORN/XNOR 时 (func[5]=0, func[0]=1), 对 src2 取反后与 src1 进行 AND/OR/XOR 运算.

`MiscResultSelect` 模块负责最终的多路选择, 通过 3 级 MUX 树高效选择结果: `logicBase`(func[3]=0) 选择基本逻辑或打包, `logicAdv`(func[4]=1) 选择高级操作 (revb/rev8/pack/orh48/szewl/byte2), `maskedLogicRes`(func[5]=1) 对逻辑结果应用掩码.

#### 4.2.6 高级杂项操作 (func[6:4] = 101)

| 编码 | 操作 | 含义 |
|------|------|------|
| 101_0000 | `revb` | 字节内位反转: `Cat(Reverse(src1[7:0]), ..., Reverse(src1[63:56]))` |
| 101_0001 | `rev8` | 字节序反转 (bswap): `Cat(src1[7:0], src1[15:8], ..., src1[63:56])` |
| 101_0010 | `pack` | `Cat(src2[31:0], src1[31:0])` |
| 101_0011 | `orh48` | `Cat(src1[63:8], 0) \| src2` |
| 101_1000 | `szewl1` | `Cat(0[31], src1[31:0], 0[1])` -- 32-bit 左移 1 后零扩展 |
| 101_1001 | `szewl2` | `Cat(0[30], src1[31:0], 0[2])` |
| 101_1010 | `szewl3` | `Cat(0[29], src1[31:0], 0[3])` |
| 101_1011 | `byte2` | `Cat(0[56], src1[15:8])` -- 提取第 2 字节 |

#### 4.2.7 掩码/LSB 操作 (func[6:4] = 110)

这些是 XiangShan 自定义扩展, 对最低有效字节 (LSB) 或零扩展半字 (ZextH) 进行逻辑操作:

| 编码 | 操作 | 含义 |
|------|------|------|
| 110_0000 | `andlsb` | `and` 结果保留 LSB, 其余置零 |
| 110_0001 | `andzexth` | `and` 结果保留 ZextH, 其余置零 |
| 110_0010 | `orlsb` | `or` 结果保留 LSB |
| 110_0011 | `orzexth` | `or` 结果保留 ZextH |
| 110_0100 | `xorlsb` | `xor` 结果保留 LSB |
| 110_0101 | `xorzexth` | `xor` 结果保留 ZextH |
| 110_0110 | `orcblsb` | `orcb` 结果保留 LSB |
| 110_0111 | `orcbzexth` | `orcb` 结果保留 ZextH |

掩码逻辑: `mask = Cat(Fill(15, func(0)), 1.U(1.W))`, 当 func(0)=0 时 mask 仅保留 LSB (低 8 位有效), func(0)=1 时保留 ZextH (低 16 位有效, 零扩展). `maskedLogicRes = mask & logicRes`.

#### 4.2.8 Zicond 条件置零操作

| 编码 | 操作 | 含义 |
|------|------|------|
| 111_0100 | `czero_eqz` | `src2 == 0 ? 0 : src1` |
| 111_0110 | `czero_nez` | `src2 != 0 ? 0 : src1` |

Zicond (RISC-V Conditional Zero Extension) 通过 `ConditionalZeroModule` 实现:
```scala
val condition_zero = io.condition === 0.U
val use_zero = !io.isNez && condition_zero || io.isNez && !condition_zero
io.condRes := Mux(use_zero, 0.U, io.value)
```

#### 4.2.9 跳转地址计算

| 编码 | 操作 | 含义 |
|------|------|------|
| 111_1000 | `jal` | `pc + src2` (JAL 目标地址) |
| 111_1001 | `jalr` | `pc + src2` (JALR 目标地址) |
| 111_1010 | `auipc` | `pc + src2` (AUIPC 目标地址) |

当 `aluNeedPc` 为 true 时, ALU 额外接入 PC 输入, 通过一个独立的 `AddModule` (jmpModule) 计算 `pc + src2`. 这使得 ALU 可以在不占用专门的跳转单元的情况下完成 AUIPC 等 PC-relative 指令.

### 4.3 AluDataModule 内部结构

`AluDataModule` 是纯组合逻辑模块 (无内部流水线寄存器), 其内部实例化了多个子模块:

- `LeftShiftModule` / `LeftShiftWordModule`: Barrel shifter (左移)
- `RightShiftModule` / `RightShiftWordModule`: Barrel shifter (右移, 含算术移位)
- `RotateLeftShiftModule` / `RotateRightShiftModule`: 循环移位器
- `RotateLeftShiftWordModule` / `RotateRightShiftWordModule`: 循环移位器 (W-suffix)
- `AddModule`: 64-bit 加法器 (实例化多次: 通用加法, sradd, shadd, jmp)
- `AddWModule`: 32-bit 加法器
- `SubModule`: 64-bit 减法器 (含 carry-out, 1-bit 额外宽度)
- `MiscResultSelect`: 多路选择器, 组合逻辑的逻辑/位操作结果选择
- `ConditionalZeroModule`: Zicond 条件置零

结果选择使用 `Mux1H` (one-hot multiplexer), 27 个操作结果并行计算, 最终根据 `func` 选择输出. `Mux1H` 相比嵌套 `Mux` 的优势在于: 当 one-hot 编码保证只有一个 select 位有效时, 每个数据通路只有一个 MUX gate 在关键路径上.

---

## 5. BKU: 位操作与密码学单元深度分析

### 5.1 架构概览

BKU (`Bku` class, 位于 `src/main/scala/xiangshan/backend/fu/Bku.scala`) 是一个 2 级流水线功能单元, 继承 `FuncUnit` 并混入 `HasPipelineReg`. 其架构设计将不同功能类型分配到独立子模块:

```
Bku (FuncUnit + HasPipelineReg, latency=2)
  |-- CountModule         : clz/ctz/cpop (Zbb)
  |-- ClmulModule         : clmul/clmulh/clmulr (Zbc/Zbb)
  |-- MiscModule          : xpermn/xpermb (Zbk)
  |-- CryptoModule        : 密码学加速
        |-- HashModule         : SHA-256/SHA-512/SM3
        |-- BlockCipherModule  : AES-64/SM4
```

BKU 主模块本身不包含数据通路逻辑, 而是将输入分发到各子模块, 并通过 `Mux` 树选择最终结果:

```scala
val funcReg = RegEnable(func, io.in.fire)
val result = Mux(funcReg(5), cryptoModule.io.out,
                Mux(funcReg(3), countModule.io.out,
                    Mux(funcReg(2), miscModule.io.out, clmulModule.io.out)))
io.out.bits.res.data := RegEnable(result, regEnable(2))
```

结果选择基于 func 编码的高位: func[5] 区分密码学和非密码学操作, func[3] 区分计数和 clmul, func[2] 区分 xperm 和 clmul.

### 5.2 操作类型体系 (BKUOpType)

BKUOpType 使用 6-bit 编码, 按高位分为四大类:

#### 5.2.1 Carry-less Multiply (func[5:4] = 00)

| 编码 | 操作 | 含义 | 扩展 |
|------|------|------|------|
| 00_0000 | `clmul` | Carry-less multiply, 低 64 位 | Zbc/Zbb |
| 00_0001 | `clmulh` | Carry-less multiply, 高 64 位 | Zbc/Zbb |
| 00_0010 | `clmulr` | Carry-less multiply, 移位对齐 | Zbc/Zbb |

#### 5.2.2 Permutation (func[2] = 1)

| 编码 | 操作 | 含义 | 扩展 |
|------|------|------|------|
| 00_0100 | `xpermn` | 4-bit nibble crossbar permutation | Zbk |
| 00_0101 | `xpermb` | 8-bit byte crossbar permutation | Zbk |

#### 5.2.3 Bit Counting (func[5:3] = 001)

| 编码 | 操作 | 含义 | 扩展 |
|------|------|------|------|
| 00_1000 | `clz` | Count Leading Zeros (64-bit) | Zbb |
| 00_1001 | `clzw` | Count Leading Zeros (32-bit) | Zbb |
| 00_1010 | `ctz` | Count Trailing Zeros (64-bit) | Zbb |
| 00_1011 | `ctzw` | Count Trailing Zeros (32-bit) | Zbb |
| 00_1100 | `cpop` | Population Count (64-bit) | Zbb |
| 00_1101 | `cpopw` | Population Count (32-bit) | Zbb |

#### 5.2.4 AES 操作 (func[5:3] = 100)

| 编码 | 操作 | 含义 |
|------|------|------|
| 10_0000 | `aes64es` | AES-64 Forward SubBytes + ShiftRows |
| 10_0001 | `aes64esm` | AES-64 Forward SubBytes + ShiftRows + MixColumns |
| 10_0010 | `aes64ds` | AES-64 Inverse SubBytes + InvShiftRows |
| 10_0011 | `aes64dsm` | AES-64 Inverse SubBytes + InvShiftRows + InvMixColumns |
| 10_0100 | `aes64im` | AES-64 InvMixColumns (仅 Mix 部分) |
| 10_0101 | `aes64ks1i` | AES-64 Key Schedule Round 1 (RotWord + SubWord + Rcon) |
| 10_0110 | `aes64ks2` | AES-64 Key Schedule Round 2 (XOR 操作) |

#### 5.2.5 SM4 操作 (func[5:3] = 101)

| 编码 | 操作 | 含义 |
|------|------|------|
| 10_1_00xx | `sm4ed0-3` | SM4 Encrypt/Decrypt (4 种轮移位) |
| 10_1_10xx | `sm4ks0-3` | SM4 Key Schedule (4 种轮移位) |

#### 5.2.6 Hash 操作 (func[5:4] = 11)

| 编码 | 操作 | 含义 |
|------|------|------|
| 11_0000 | `sha256sum0` | SHA-256 Sigma0: ROR32(x,2) ^ ROR32(x,13) ^ ROR32(x,22) |
| 11_0001 | `sha256sum1` | SHA-256 Sigma1: ROR32(x,6) ^ ROR32(x,11) ^ ROR32(x,25) |
| 11_0010 | `sha256sig0` | SHA-256 sigma0: ROR32(x,7) ^ ROR32(x,18) ^ SHR32(x,3) |
| 11_0011 | `sha256sig1` | SHA-256 sigma1: ROR32(x,17) ^ ROR32(x,19) ^ SHR32(x,10) |
| 11_0100 | `sha512sum0` | SHA-512 Sigma0: ROR64(x,28) ^ ROR64(x,34) ^ ROR64(x,39) |
| 11_0101 | `sha512sum1` | SHA-512 Sigma1: ROR64(x,14) ^ ROR64(x,18) ^ ROR64(x,41) |
| 11_0110 | `sha512sig0` | SHA-512 sigma0: ROR64(x,1) ^ ROR64(x,8) ^ SHR64(x,7) |
| 11_0111 | `sha512sig1` | SHA-512 sigma1: ROR64(x,19) ^ ROR64(x,61) ^ SHR64(x,6) |
| 11_1000 | `sm3p0` | SM3 P0: ROR32(x,23) ^ ROR32(x,15) ^ x |
| 11_1001 | `sm3p1` | SM3 P1: ROR32(x,9) ^ ROR32(x,17) ^ x |

### 5.3 子模块详细分析

#### 5.3.1 CountModule -- 前导零/尾零/位计数

实现 64-bit CLZ/CTZ/CPOP, 采用 2 级流水线:

**Stage 0**:
- 对输入的每 2-bit 进行编码: `encode(00) = 2(二进制)`, `encode(01) = 1(二进制)`, `encode(1x) = 0(二进制)`.
- `c0[i]` = 2-bit 编码结果, 值为 0/1/2 表示该 2-bit 组中前导零的个数.
- `c1[i]` = 通过 `clzi` 函数合并两个 `c0` 结果, 产生 3-bit 计数.
- CTZ 通过 `Reverse(src)` 将尾零转为前导零, 复用 CLZ 逻辑.

```scala
def clzi(msb: Int, left: UInt, right: UInt): UInt = {
  Mux(left(msb),
    Cat(left(msb) && right(msb), !right(msb), if(msb==1) right(0) else right(msb-1, 0)),
    left)
}
```

**Pipeline Register**: `funcReg`, `c2[8]`, `cpopTmp[4]` 通过 `RegEnable` 锁存, 使能信号为 `regEnable(1)`.

**Stage 1**:
- 继续 `clzi` 合并: `c2 -> c3 -> c4 -> zeroRes`.
- CPOP 使用 `PopCount` 对 4 个 16-bit 段分别计数:
  ```scala
  val cpopLo32 = cpopTmp(0) +& cpopTmp(1)  // 低 32-bit popcount
  val cpopHi32 = cpopTmp(2) +& cpopTmp(3)  // 高 32-bit popcount
  val cpopRes = cpopLo32 +& cpopHi32       // 总 popcount
  ```

**最终选择**: `Mux(funcReg(2), Mux(funcReg(0), cpopWRes, cpopRes), Mux(funcReg(0), zeroWRes, zeroRes))` -- func[2] 区分 CPOP 和 CLZ/CTZ, func[0] 区分 W-suffix 变体.

#### 5.3.2 ClmulModule -- Carry-less Multiply

实现 Zbc/Zbb 扩展的 carry-less 乘法, 类似于 `ArrayMulDataModule` 但更精简:

**Stage 0**:
- 对 src1 的每一位, 若为 1 则生成 `src2 << i` 的部分积 (128-bit):
  ```scala
  mul0(i) := Mux(src1(i), if(i==0) src2 else Cat(src2, 0.U(i.W)), 0.U)
  ```
- 三轮两两 XOR 压缩: `mul0[64] -> mul1[32] -> mul2[16]`.

**Pipeline Register**: `funcReg`, `mul3[8]` 通过 `RegEnable` 锁存.

**Stage 1**:
- 最终 XOR 压缩: `mul2 -> mul3`, `ParallelXOR(mul3)` 得到 128-bit 结果.
- 三种输出变体:
  ```scala
  val clmul  = res(63,0)     // 低 64 位
  val clmulh = res(127,64)   // 高 64 位
  val clmulr = res(126,63)   // 移位对齐 (高 64 位左移 1 位)
  ```

#### 5.3.3 MiscModule -- Crossbar Permutation

实现 Xperm 指令 (Zbk/ICB 扩展):

- **XPERMN**: 将 src1 视为 16 个 4-bit nibble 的查找表, src2 的每个 nibble (4-bit) 作为索引, 查找 src1 中对应位置的值.
  ```scala
  (0 until 16).map(i => xpermnVec(i) := xpermLUT(src1, src2(i*4+3, i*4), 4))
  ```

- **XPERMB**: 将 src1 视为 8 个 byte 的查找表, src2 的低 3 bit 作为索引, 高 5 bit 非零时输出 0 (防止越界):
  ```scala
  xpermbVec(i) := Mux(src2(i*8+7, i*8+3).orR, 0.U, xpermLUT(src1, src2(i*8+2, i*8), 8))
  ```

#### 5.3.4 HashModule -- SHA/SM3 哈希加速

为 SHA-256/SHA-512 和 SM3 提供硬件加速的 Sigma/sigma/P 函数:

**SHA-256**:
- `sha256sum0 = ROR32(src, 2) ^ ROR32(src, 13) ^ ROR32(src, 22)` -- Sigma0 函数
- `sha256sum1 = ROR32(src, 6) ^ ROR32(src, 11) ^ ROR32(src, 25)` -- Sigma1 函数
- `sha256sig0 = ROR32(src, 7) ^ ROR32(src, 18) ^ SHR32(src, 3)` -- sigma0 函数, 注意最后一个操作是右移 (SHR) 而非循环右移
- `sha256sig1 = ROR32(src, 17) ^ ROR32(src, 19) ^ SHR32(src, 10)` -- sigma1 函数

**SHA-512**: 同理, 使用 ROR64/SHR64, 旋转量不同.

**SM3**: `sm3p0 = ROR32(src, 23) ^ ROR32(src, 15) ^ src`, `sm3p1 = ROR32(src, 9) ^ ROR32(src, 17) ^ src`

SHA 结果通过 `SignExt` 扩展到 64-bit. 最终通过 `Mux(io.func(3), sm3, sha)` 选择, func[3] 区分 SM3 和 SHA.

#### 5.3.5 BlockCipherModule -- AES/SM4 分组密码加速

这是 BKU 中最复杂的子模块, 实现了完整的 AES-64 和 SM4 加密原语.

**AES-64 S-box 流水线** (跨 Stage 0 到 Stage 1):
1. **Sbox 前处理**: `ForwardShiftRows` / `InverseShiftRows` 对 8 个字节进行行移位.
2. **Sbox 计算** (Pipeline Register): `SboxAesTop(in) -> SboxInv(mid)` -- 将 8-bit 输入转换为 18-bit 中间态 (GF(2^8) 域表示), 再逆变换回 8-bit. 使用 `Reg(Vec(8, Vec(18, Bool())))` 存储中间态.
3. **Sbox 后处理**: `SboxAesOut(mid)` -- 将中间态转回 8-bit 输出.

**AES-64 操作类型**:
- `aes64es`: SubBytes + ShiftRows (正向)
- `aes64esm`: SubBytes + ShiftRows + MixColumns (正向) -- `MixFwd` 实现正向 MixColumns
- `aes64ds`: SubBytes + ShiftRows (逆向)
- `aes64dsm`: SubBytes + ShiftRows + InvMixColumns (逆向) -- `MixInv` 实现逆向 MixColumns
- `aes64im`: 仅 InvMixColumns (Mix 部分)
- `aes64ks1i`: Key Schedule Round 1 -- 4 字节经过 Sbox 后与 Rcon 异或, 结果复制两次形成 64-bit
- `aes64ks2`: Key Schedule Round 2 -- 两轮密钥异或操作

**SM4 操作**:
- 使用专用 `SboxSm4Top` / `SboxSm4Out` S-box.
- SM4 的线性变换 `L` 和密钥扩展 `L'` 通过移位和 XOR 组合实现:
  ```scala
  val sm4ed = sm4SboxOut ^ (sm4SboxOut<<8) ^ (sm4SboxOut<<2) ^
             (sm4SboxOut<<18) ^ ((sm4SboxOut&"h3f")<<26) ^ ((sm4SboxOut&"hc0")<<10)
  val sm4ks = sm4SboxOut ^ ((sm4SboxOut&"h07")<<29) ^ ((sm4SboxOut&"hfe")<<7) ^
             ((sm4SboxOut&"h01")<<23) ^ ((sm4SboxOut&"hf8")<<13)
  ```
- 4 种轮移位通过 MUX 选择不同旋转版本 (0/8/16/24 bit rotation).

**CryptoModule 顶层**: `Mux(funcReg(4), hashModule.io.out, blockCipherModule.io.out)` -- func[4] 区分 Hash 和 BlockCipher.

### 5.4 BKU 流水线结构

BKU 整体 2 级流水线:
- **Stage 1 (preLat)**: 各子模块接收输入并开始计算. CountModule 和 ClmulModule 内部各自有 pipeline register, 在 `regEnable(1)` 使能下锁存中间结果.
- **Stage 2 (fixLat)**: `RegEnable(result, regEnable(2))` 输出最终结果. BlockCipherModule 的 S-box 计算也在此阶段完成.

---

## 6. Multiplier: Booth Radix-4 阵列乘法器深度分析

### 6.1 架构概览

XiangShan 的乘法器由两层组成:
- **`MulUnit`** (位于 `xiangshan/backend/fu/wrapper/MulUnit.scala`): `FuncUnit` 具体实现, 混入 `HasPipelineReg`, latency = 2.
- **`Mul`** (位于 `yunsuan/src/main/scala/yunsuan/scalar/Mul.scala`): 核心乘法器, 3 级流水线结构 (MulModuleS0/S1/S2).

设计灵感来源于两篇经典论文:
- Andrew D. Booth (1951): "A signed binary multiplication technique" -- Booth 编码
- Christopher S. Wallace (1964): "A suggestion for a fast multiplier" -- Wallace tree 压缩

此外, `Multiplier.scala` 中的 `ArrayMulDataModule` 提供了另一种基于递归 CSA 压缩的乘法器实现, 使用 `C22/C32/C53` 基础组件.

### 6.2 MulUnit 接口

```scala
class MulUnit(cfg: FuConfig)(implicit p: Parameters) extends FuncUnit(cfg) with HasPipelineReg {
  override def latency: Int = 2
  private val len = cfg.destDataBits  // 64
  private val mulModule = Module(new Mul(len))
  mulModule.io.in.bits.fuOpType := io.in.bits.ctrl.fuOpType
  mulModule.io.in.bits.src(0) := src0
  mulModule.io.in.bits.src(1) := io.in.bits.data.src(1)
  io.out.bits.res.data := mulModule.io.out
}
```

**MulW7 优化**: 当操作为 `mulw7` 时 (7-bit 乘法, 用于特定 DSP 场景), 取 `src0[6:0]` 作为乘数, 减少 Booth 编码器数量和部分积数量.

### 6.3 Booth Radix-4 编码

乘法器采用 Booth radix-4 编码, 每次处理被乘数 `a` 的 2-bit (加上 1 bit 重叠位), 生成 5 种编码:

| Booth Code (3-bit input) | 含义 | 编码输出 [3:2:1:0] |
|--------------------------|------|---------------------|
| 000 | +0 * M | 0100 |
| 001 | +1 * M | 1001 |
| 010 | +1 * M | 1001 |
| 011 | +2 * M | 0101 |
| 100 | -2 * M | 0110 |
| 101 | -1 * M | 1010 |
| 110 | -1 * M | 1010 |
| 111 | +0 * M | 0100 |

编码输出 4-bit: `[3]=2x` (是否需要左移 1), `[2]=shift` (是否左移), `[1]=neg` (是否取负), `[0]=pos` (是否取正).

**部分积生成** (`PPGen`): 根据 Booth 编码和乘数符号位, 生成 `len+2` 位部分积:
- `bPos=1`: 输出 `ppPos` (正部分积, `b` 或 `b<<1`)
- `bNeg=1`: 输出 `ppNeg` (负部分积, 二进制补码取反+1, 通过 `Cat(~sign, ~b, 1.U)` 实现 +1)
- 额外输出 `ppCOut = bNeg` 用于补偿负部分积的 +1.

### 6.4 Mul 模块三级流水线

#### 6.4.1 Stage 0 (MulModuleS0) -- 部分积生成与 CSA 压缩

**Booth 编码器组**:
- 对被乘数 `a` 的低位 (16-bit) 生成 8 个 Booth4 编码 -> `boothCode0[8]`
- 对被乘数 `a` 的中间位 (17-bit: `a[31:15]`) 生成 8 个 Booth4 编码 -> `boothCode1[8]`
- 对被乘数 `a` 的高位 (33-bit: `a[63:31]`, 仅 64-bit 模式有效) 生成 16 个 Booth4 编码 -> `boothCode2[16]` (分为高 8 个和低 8 个)

**部分积生成组**:
- `genPP(b0, boothCode0, signB, 8)`: 生成 `pp0[8]` (8 个 66-bit 部分积)
- `genPP(b1, boothCode1, signB, 8)`: 生成 `pp1[8]`
- `genPP(b2, boothCode2, signB, 16)`: 生成 `pp2[16]`, 分为 `pp2[0..7]` 和 `pp3[0..7]`

**CSA (Carry-Save Adder) 压缩组**:
- `CSA8to2`: 将 8 个部分积压缩为 2 个, 含进位处理. 内部使用多级压缩 (3-2 压缩 + 位对齐 + 最终压缩), 4 级逻辑深度.
- `CSA9to2`: 类似 CSA8to2, 但额外处理无符号扩展项 (`ppUnSignBMul32` 或 `ppUnSignBMul64`).

4 组 CSA 并行处理, 每组输出 2 个 80-bit 向量和 2 个 carry 信号.

**Stage 0 输出**: 通过两个并行的 `CSA4to2` 将 8 个 128-bit 向量进一步压缩为 4 个 128-bit 向量, 寄存到 Stage 1.

#### 6.4.2 Stage 1 (MulModuleS1) -- 最终压缩与低 65 位计算

- `CSA4to2` 将 4 个 128-bit 向量压缩为 2 个 (`resS`, `resC`).
- 低 65 位计算: `res = resS[64:0] +& resC[64:0]` -- 使用 `+&` (带进位加法), 产生 65-bit 结果 + 1-bit 进位. 这是乘积的完整低 64 位加上来自高位的进位.
- 高位保留: `resHigh[0] = resS[127:64]`, `resHigh[1] = resC[127:64]` -- 保留高位 sum/carry 对, 延迟到 Stage 2 组合.

#### 6.4.3 Stage 2 (MulModuleS2) -- 结果选择与高位完成

```scala
val resultTmp = res0 + res1 + resLow(64)  // 高位 sum + carry + 来自低 65 位的进位
val result = Mux(isW, SignExt(resLow(31, 0), len),     // MULW: 取低 32 位符号扩展
                 Mux(isHi, resultTmp, resLow))           // MULH: 高位结果; MUL: 低 64 位
```

这种分段计算策略 (低 65 位在 Stage 1 完成, 高位在 Stage 2 完成) 有效平衡了两级的逻辑深度.

### 6.5 支持的操作类型 (MULOpType)

| 操作 | 含义 | 说明 |
|------|------|------|
| MUL | 低位 64-bit 乘积 | 取 `resLow[63:0]` |
| MULW | 低位 32-bit 乘积 | 取 `resLow[31:0]` 符号扩展到 64-bit |
| MULH | 有符号 x 有符号 高位 | `resHigh[0] + resHigh[1] + carry` |
| MULHSU | 有符号 x 无符号 高位 | 通过 `aIsUnSigned` 和 `bIsSigned` 控制符号扩展 |
| MULHU | 无符号 x 无符号 高位 | 两个操作数均无符号扩展 |
| MULW7 | 7-bit 优化乘法 | 仅用 `src0[6:0]`, 减少部分积 |

乘法器通过 `aIsUnSigned` 和 `bIsSigned` 信号控制有符号/无符号处理.

### 6.6 ArrayMulDataModule -- 另一种乘法器实现

`Multiplier.scala` 中的 `ArrayMulDataModule` 提供了基于递归 CSA 压缩的替代实现:

- 使用 `C22/C32/C53` 基础 CSA 组件 (定义在 `fu/util/CSA.scala`)
- 递归 `addAll` 函数: 对每一列部分积反复使用 CSA 压缩, 直到所有列不超过 2 个元素
- 在 depth=4 时插入 pipeline register (`RegEnable`), 支持 2 级流水线
- 最终结果: `sum + carry` (使用普通加法器完成最后的 sum-carry 合并)

这种实现相比 `Mul` 模块的 CSA8to2/CSA9to2 更为通用, 但可能在 timing 和 area 上有不同权衡.

### 6.7 CSA (Carry-Save Adder) 工具库

位于 `xiangshan/backend/fu/util/CSA.scala`, 提供基础 CSA 组件:

- `C22` (2-to-2 compressor): 等价于半加器, 对 2 个 1-bit 输入产生 sum + carry.
- `C32` (3-to-2 compressor): 全加器, 对 3 个 1-bit 输入产生 sum + carry.
- `C53` (5-to-3 compressor): 由 2 个 C32 级联, 将 5 个输入压缩为 3 个输出.

---

## 7. Bypass/Forwarding 接口与数据通路

### 7.1 功能单元输出到 Bypass 网络

XiangShan 的数据前递 (data forwarding/bypass) 网络位于功能单元的下游. 每个流水线功能单元的输出 (`io.out.bits.res.data`) 直接连接到 Bypass 端口, 由后端的 `ExeUnitImp` 模块管理.

关键的 Bypass 信号:
- **`io.out.bits.ctrl.robIdx`**: 标识结果对应的指令 ROB 条目, 用于 Bypass 匹配和重定向检测.
- **`io.out.bits.ctrl.pdest`**: 目标物理寄存器编号, Issue Queue 和 Register Rename 模块通过匹配此字段实现数据前递.
- **`io.out.bits.ctrl.rfWen`**: 写使能, 仅当 rfWen 为 true 时该结果进入 Bypass 网络. 对于不写寄存器堆的指令 (如 store), 结果不进入 Bypass.
- **`io.out.bits.ctrl.toRobValid`**: 通知 ROB 该指令已完成, 用于 commit 处理.

### 7.2 Uncertain Wakeup 机制

`FuConfig.needUncertainWakeup` 标识具有不确定延迟的功能单元. `FuConfig.needUncertainWakeupFuConfigs` 包括 `CsrCfg, DivCfg, FdivCfg, VfdivCfg, VidivCfg`.

对于 ALU/BKU/Mul 这三个固定延迟单元, 不使用 uncertain wakeup -- 它们的延迟是确定的, Issue Queue 可以精确计算唤醒时间 (instruction_issue_cycle = dispatch_cycle + latency).

不确定延迟单元通过 `outValidAhead3Cycle` 端口提前 3 个周期发出有效信号, 通知 Issue Queue 提前唤醒依赖指令. `wakeupSuccess` 输入端口则接收下游的确认信号.

### 7.3 流水线 Bypass 路径

`HasPipelineReg` trait 中的流水线寄存器实现了内部数据转发:

- **Valid/Ready 协议**: `rdyVec(i)` 表示第 `i` 级可以接收新数据; `validVec(i)` 表示第 `i` 级有有效数据. 当下游背压 (`!rdyVec(i+1)`) 时, 流水线暂停, 各级 `validVec` 保持不变.
- **Flush 处理**: 每级流水线的 `robIdx` 通过 `needFlush(flush)` 检查, 一旦匹配重定向条件, 该级的 `validVec(i)` 被清零, 有效消除该级的无效指令.
- **Forward Advance**: 当 `io.out.ready` 为 true 时, 所有流水线级可以同时推进, 数据从 `ctrlVec(i-1)/dataVec(i-1)` 流入 `ctrlVec(i)/dataVec(i)`.

### 7.4 ALU 数据通路的并行计算优化

ALU 的数据通路设计强调组合延迟优化:

1. **全并行计算**: 所有 27+ 种操作的计算逻辑并行执行, 无共享关键路径. 例如, 加法、减法、移位、逻辑运算同时产生结果.

2. **Mux1H 选择**: 使用 one-hot multiplexer 而非优先级编码器:
   ```scala
   val aluRes = Mux1H(Seq(
     isAdd  -> add,
     isSradd -> sradd,
     isShadd -> shadd,
     ...
   ))
   ```
   当 one-hot 编码保证只有一个 select 位有效时, 每个数据通路只有一个 MUX gate 在关键路径上.

3. **复用减法器**: `sltu/slt/max/min/maxu/minu` 共享同一个减法器的结果, 通过不同提取方式获得所需信号.

4. **独立的专用加法器**: `sradd/shadd/jmp` 使用独立的 `AddModule` 实例, 避免与主加法器竞争.

---

## 8. 源文件位置索引

### 核心功能单元源文件

| 文件 | 行数 | 内容 |
|------|------|------|
| `src/main/scala/xiangshan/backend/fu/Alu.scala` | 454 | ALU 核心数据通路 (`AluDataModule`, `AddModule`, `AddWModule`, `SubModule`, 各种移位模块, `MiscResultSelect`, `ConditionalZeroModule`) |
| `src/main/scala/xiangshan/backend/fu/Bku.scala` | 363 | BKU 顶层 (`Bku`) 及子模块 (`CountModule`, `ClmulModule`, `MiscModule`, `HashModule`, `BlockCipherModule`, `CryptoModule`) |
| `src/main/scala/xiangshan/backend/fu/Multiplier.scala` | 161 | `ArrayMulDataModule` -- 基于递归 CSA 压缩的替代乘法器实现 |
| `yunsuan/src/main/scala/yunsuan/scalar/Mul.scala` | 735 | `Mul` (主乘法器), `Booth4`, `PPGen`, `CSA8to2`, `CSA9to2`, `CSA4to2`, `MulModuleS0/S1/S2` |

### Wrapper 和配置文件

| 文件 | 行数 | 内容 |
|------|------|------|
| `src/main/scala/xiangshan/backend/fu/wrapper/Alu.scala` | 31 | ALU 的 PipedFuncUnit wrapper, 连接 AluDataModule |
| `src/main/scala/xiangshan/backend/fu/wrapper/MulUnit.scala` | 30 | MulUnit 的 FuncUnit wrapper, 连接 Mul |
| `src/main/scala/xiangshan/backend/fu/FuConfig.scala` | 902 | 所有 FuConfig 定义, 包括 AluCfg/MulCfg/BkuCfg 及 30+ 其他 FU 配置 |
| `src/main/scala/xiangshan/backend/fu/FuncUnit.scala` | 343 | FuncUnit 基类, HasPipelineReg trait, PipedFuncUnit 抽象 |
| `src/main/scala/xiangshan/backend/fu/FuType.scala` | 252 | FuType one-hot 枚举定义, 各类分组集合 |

### 辅助工具文件

| 文件 | 行数 | 内容 |
|------|------|------|
| `src/main/scala/xiangshan/backend/fu/util/ShiftUtils.scala` | 80 | `doShiftLeft`, `doShiftRightArith`, `doShiftRotateLeft/Right` 及 Word 变体 |
| `src/main/scala/xiangshan/backend/fu/util/CSA.scala` | 64 | Carry-Save Adder 基础组件 (`C22`/`C32`/`C53`) |
| `src/main/scala/xiangshan/backend/fu/util/CryptoUtils.scala` | -- | 密码学工具 (SboxAesTop/SboxInv/SboxAesOut, MixFwd/MixInv, ShiftRows 等) |
| `src/main/scala/xiangshan/package.scala:464-567` | -- | `object ALUOpType` 操作类型定义及 isXxx 判断函数 |
| `src/main/scala/xiangshan/package.scala:863-908` | -- | `object BKUOpType` 操作类型定义 |

### 后端集成文件

| 文件 | 行数 | 内容 |
|------|------|------|
| `src/main/scala/xiangshan/backend/exu/ExeUnit.scala` | -- | `ExeUnitImp` -- 功能单元实例化、时钟门控与 EXU 绑定 |
| `src/main/scala/xiangshan/backend/exu/ExuBlock.scala` | -- | `ExuBlock` -- 所有 EXU 的顶层模块, 处理跨域信号和 Bypass 路由 |

---

## 总结

XiangShan 的整数功能单元 (ALU, BKU, Multiplier) 展现了高性能 RISC-V 处理器设计的典型模式:

1. **ALU** 通过 60+ 种操作的全并行计算和 Mux1H 选择, 在组合逻辑延迟内完成所有基础整数运算, 全面支持 Zba/Zbb/Zbs/Zicond 扩展. 其自定义扩展 (szewl/byte2/andlsb/orclsb 等) 为特定应用场景提供加速.

2. **BKU** 将位操作和密码学加速集成在统一的 2 级流水线中, 通过子模块化设计 (CountModule/ClmulModule/MiscModule/CryptoModule) 实现功能解耦. 支持 AES-64/SM4/SHA-256/SHA-512/SM3 等多种密码学原语, 满足国密合规和通用加密加速需求.

3. **Multiplier** 采用经典的 Booth Radix-4 + Wallace tree 架构, 通过 3 级流水线 (部分积生成+CSA 压缩 -> 最终压缩+低 65 位计算 -> 结果选择+高位完成) 在 2 周期延迟内完成 64x64 位有符号/无符号乘法, 支持 MUL/MULW/MULH/MULHSU/MULHU 等全部 RISC-V M 扩展操作.

4. **FuncUnit** 基类和 `HasPipelineReg` trait 提供了统一的流水线抽象, 包括 valid/ready 握手协议、flush 处理、fix-timing 延迟插入等关键机制, 使得不同延迟特性的功能单元可以无缝集成到 EXU 框架中.

5. **FuConfig** 配置系统通过声明式参数 (fuType, srcData, latency, writeIntRf 等) 描述功能单元的全部静态特征, 由 `ExeUnitImp` 动态实例化并绑定到执行单元, 实现了功能单元与后端调度/发射/写回框架的解耦.
