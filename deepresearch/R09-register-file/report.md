# R09 - 寄存器文件 (Register File) 与 RegCache 深度研究报告

> 研究对象：XiangShan 高性能 RISC-V 处理器后端寄存器文件系统
> 研究日期：2026-06-09

---

## 目录

1. [概述](#1-概述)
2. [寄存器文件架构总览](#2-寄存器文件架构总览)
3. [物理寄存器文件参数](#3-物理寄存器文件参数)
4. [寄存器文件实现细节](#4-寄存器文件实现细节)
5. [读/写端口分配策略](#5-读写端口分配策略)
6. [INT vs FP 寄存器文件分离](#6-int-vs-fp-寄存器文件分离)
7. [Bypass Network 与寄存器数据转发](#7-bypass-network-与寄存器数据转发)
8. [RegCache 设计详解](#8-regcache-设计详解)
9. [RegCache 年龄检测与替换策略](#9-regcache-年龄检测与替换策略)
10. [RegCache Tag Table 与 Tag 查找](#10-regcache-tag-table-与-tag-查找)
11. [RegCache 写入与唤醒机制](#11-regcache-写入与唤醒机制)
12. [RegCache 取消与失效逻辑](#12-regcache-取消与失效逻辑)
13. [性能影响分析](#13-性能影响分析)
14. [关键源文件索引](#14-关键源文件索引)
15. [总结](#15-总结)

---

## 1. 概述

XiangShan 是一款高性能乱序执行 (Out-of-Order, OoO) RISC-V 处理器，其后端采用重命名物理寄存器文件 (Physical Register File) 架构。在现代超标量处理器中，寄存器文件是关键的微架构资源，其端口数量和访问延迟直接影响处理器的 IPC (Instructions Per Cycle) 性能。

XiangShan 的寄存器文件系统由以下核心组件构成：

- **Physical Register File (PRF)**：存储所有重命名后的物理寄存器值，按 INT / FP / Vec 类型分离
- **RegCache**：作为整数寄存器文件的缓存层，减少对大容量 PRF 的频繁访问
- **Bypass Network**：处理执行单元间的快速数据转发，消除写后读 (RAW) 延迟

本报告深入分析各组件的微架构设计、参数配置、数据通路连接方式以及对整体性能的影响。

---

## 2. 寄存器文件架构总览

XiangShan 采用分离式物理寄存器文件 (Split Physical Register File) 架构，不同类型的数据有各自独立的寄存器文件：

```
┌─────────────────────────────────────────────────────────────────────┐
│                        后端数据通路 (Backend DataPath)               │
│                                                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────┐  ┌──────────┐  │
│  │IntRegFile│  │FpRegFile │  │VfRegFile │  │V0Reg│  │VlRegFile │  │
│  │(224条目)  │  │(256条目)  │  │(128条目)  │  │File │  │(32条目)   │  │
│  │64-bit    │  │64-bit    │  │128-bit   │  │(22条)│  │ 8-bit    │  │
│  │4-bank    │  │1-bank    │  │split     │  │split│  │1-bank    │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └──┬──┘  └────┬─────┘  │
│       │              │              │            │          │         │
│  ┌────▼──────────────▼──────────────▼────────────▼──────────▼─────┐  │
│  │                    RF Read Arbiter (读端口仲裁)                 │  │
│  └────┬───────────────────────────────────────────────────────────┘  │
│       │                                                              │
│  ┌────▼──────────────────────────────────────────────────────────┐   │
│  │                  Bypass Network (旁路网络)                     │   │
│  │  数据来源选择: reg / regcache / forward / bypass / bypass2    │   │
│  │  / zero / v0 / imm                                            │   │
│  └────┬──────────────────────────────────────────────────────────┘   │
│       │                                                              │
│  ┌────▼──────────────────────────────────────────────────────────┐   │
│  │           Execution Units (执行单元: ALU/BJU/LDU/STA/STD等)   │   │
│  └───────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐  │
│  │         RegCache (整数寄存器缓存, 36 entries)                   │  │
│  │  ┌──────────────┐  ┌──────────────┐                           │  │
│  │  │IntRegCache   │  │MemRegCache   │                           │  │
│  │  │(24 entries)  │  │(12 entries)  │                           │  │
│  │  └──────────────┘  └──────────────┘                           │  │
│  │  + TagTable + AgeTimer + AgeDetector                          │  │
│  └────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

**核心设计哲学**：INT、FP、Vec 寄存器文件在物理上完全分离，每种类型有独立的读/写端口和数据宽度。整数寄存器文件支持 4-bank 分 bank 访问，FP 和 Vec 支持数据宽度 split（将宽数据拆分到多个窄存储中并行访问）。RegCache 仅服务于整数寄存器的读取加速。

---

## 3. 物理寄存器文件参数

物理寄存器文件的参数定义在 `Parameters.scala` 中，通过 `PregParams` 抽象类及其子类实例化：

### 3.1 各类型寄存器文件规格

| 寄存器类型 | 参数类 | 条目数 | Bank数 | 数据宽度 | 有零寄存器 | 备注 |
|-----------|--------|--------|--------|---------|-----------|------|
| IntPreg   | `IntPregParams` | 224 | 4 | 64-bit (XLEN) | 是 (x0=0) | 整数，支持banked读取 |
| FpPreg    | `FpPregParams`  | 256 | 1 | 64-bit | 否 | 浮点，split模式=4份 |
| VfPreg    | `VfPregParams`  | 128 | 1 | 128-bit (VLEN) | 否 | 向量浮点，split模式 |
| V0Preg    | `V0PregParams`  | 22  | 1 | 128-bit | 否 | V0向量寄存器 |
| VlPreg    | `VlPregParams`  | 32  | 1 | 8-bit | 否 | 向量长度寄存器 |

相关源代码位置：

```scala
// Parameters.scala (第118-149行)
intPreg: PregParams = IntPregParams(numEntries = 224, numBank = 4, ...)
fpPreg: PregParams  = FpPregParams(numEntries = 256, numBank = 1, ...)
vfPreg: VfPregParams = VfPregParams(numEntries = 128, numBank = 1, ...)
v0Preg: V0PregParams = V0PregParams(numEntries = 22,  numBank = 1, ...)
vlPreg: VlPregParams = VlPregParams(numEntries = 32,  numBank = 1, ...)
```

### 3.2 BackendParams 中的默认值

```scala
// BackendParams.scala (第547-552行)
def numPregsInt  = 224    // 整数物理寄存器数
def rfDataWidthInt = 64   // 整数数据宽度
def numPregsFp   = 256    // 浮点物理寄存器数
def rfDataWidthFp = 64    // 浮点数据宽度
def numPregsVec  = 128    // 向量物理寄存器数
def rfDataWidthVec = 128  // 向量数据宽度
```

### 3.3 RegCache 参数

```scala
// Parameters.scala (第148-149行)
IntRegCacheSize: Int = 24   // 整数执行单元 RegCache 条目数
MemRegCacheSize: Int = 12   // 访存执行单元 RegCache 条目数
// RegCacheSize = 24 + 12 = 36
// RegCacheIdxWidth = log2Up(36) = 6 bits
```

---

## 4. 寄存器文件实现细节

### 4.1 基础 Regfile 模块

XiangShan 实现了三种寄存器文件变体：

#### (a) 标准 Regfile

```scala
// Regfile.scala (第67-143行)
class Regfile(name, numPregs, numReadPorts, numWritePorts, hasZero, len, width, bankNum, isVlRegfile)
```

存储结构为 `Reg(Vec(numPregs, UInt(len.W)))`，使用寄存器堆 (Register-based) 而非 SRAM 实现，适合条目数较少但端口数较多的场景。

**读操作**：使用一拍延迟读取 `memForRead(RegNext(r.addr))`，即地址输入后下一周期获得数据。

**写操作**：采用 `Mux1H` 多路选择器实现多端口同时写入，硬件检查保证不会出现同一地址同时写入冲突（通过 `assert(!hasSameWrite)` 硬件断言）。

**零寄存器处理**：当 `hasZero=true` 时，地址 0 的寄存器 (`x0`) 始终返回硬连线的 0 值，任何写入地址 0 的操作都被静默忽略。

#### (b) 分 Bank RegfileBank

```scala
// Regfile.scala (第145-213行)
class RegfileBank(name, numPregs, numBank, numReadPorts, numWritePorts, hasZero, len, width, isVlRegfile)
```

支持 1、2 或 4 个 bank。每个 bank 独立存储部分寄存器条目，读取时根据地址低位选择对应的 bank，实现 bank 级并行读取。整数寄存器文件使用此变体（4 banks）。

Bank 地址计算：
- `bankRaddrWidth = log2Ceil(numBank)` -- bank 选择位宽
- `arbiterAddrWidth = addrWidth - bankRaddrWidth` -- bank 内寻址位宽
- `bankEntryNum = 1 << (width - bankRaddrWidth)` -- 每个 bank 的条目数

对于 224 条目、4 bank 的整数寄存器文件，每个 bank 存储 56 个条目。

#### (c) Split 模式 (宽度拆分)

`IntRegFileSplit` 和 `FpRegFileSplit` 将宽数据拆分为多个窄数据并行存储。例如 FP 寄存器在非 debug 模式下 split 为 4 份，每份存储 16-bit 数据，4 个子寄存器并行读写，结果通过 `Cat` 拼接恢复完整数据。

### 4.2 实例化方式

在 `DataPath.scala` 中，各寄存器文件的实例化如下：

```scala
// DataPath.scala (第285-289行) - 整数寄存器文件 (banked)
IntRegFileBank("IntRegFile", intSchdParams.numPregs, intPregNumBank, ...)

// DataPath.scala (第353-356行) - 浮点寄存器文件 (split)
FpRegFileSplit("FpRegFile", fpSchdParams.numPregs, splitNum, ...)

// DataPath.scala (第386-397行) - 向量寄存器文件 (split)
VfRegFile("VfRegFile", vecSchdParams.numPregs, splitNum, ...)
VfRegFile("V0RegFile", V0PhyRegs, v0RfSplitNum, ...)
FpRegFile("VlRegFile", VlPhyRegs, ..., isVlRegfile = true)
```

其中 `splitNum = if (backendParams.debugEn) 1 else 4`，即在正常工作模式下 split 为 4 份，在 debug 模式下保持完整宽度以便调试。

---

## 5. 读/写端口分配策略

### 5.1 读端口分配

寄存器文件的读端口数量由所有执行单元 (ExeUnit) 所需的源操作数 (source operand) 数量之和决定。读端口通过 `RFReadArbiter` 进行动态仲裁分配。

```scala
// SchdBlockParams.scala (第112-120行)
def numIntRfReadByExu: Int = issueBlockParams.map(_.exuBlockParams.map(_.numIntSrc).sum).sum
def numFpRfReadByExu: Int  = issueBlockParams.map(_.exuBlockParams.map(_.numFpSrc).sum).sum
def numVfRfReadByExu: Int  = issueBlockParams.map(_.exuBlockParams.map(_.numVecSrc).sum).sum
```

整数调度器 (IntScheduler) 的执行单元配置：

| 执行单元 | 类型 | Int 源操作数 | FP 源操作数 | IntWB端口 | 备注 |
|---------|------|-------------|------------|----------|------|
| ALU0    | Int  | 2           | 0          | port 0   | 含 CSR/Fence |
| BJU0    | Int  | 2           | 0          | 无       | 分支预测 |
| ALU1    | Int  | 2           | 0          | port 1   | 含 Div |
| BJU1    | Int  | 2           | 0          | 无       | 分支预测 |
| ALU2    | Int  | 2           | 0          | port 2   | 含 I2f/Mul |
| BJU2    | Int  | 2           | 0          | 无       | 分支预测 |
| ALU3    | Int  | 2           | 0          | port 3   | 含 Mul/Bku |
| LDU0    | Mem  | 1           | 0          | port 4   | 加载单元 |
| LDU1    | Mem  | 1           | 0          | port 5   | 加载单元 |
| LDU2    | Mem  | 1           | 0          | port 6   | 加载单元 |
| STA0    | Mem  | 1           | 0          | FakeInt  | Store地址 |
| STA1    | Mem  | 1           | 0          | FakeInt  | Store地址 |
| STD0    | Mem  | 1           | 1(FpRD)    | 无       | Store数据 |
| STD1    | Mem  | 1           | 1(FpRD)    | 无       | Store数据 |

整数寄存器文件的总读端口数 = 所有 Int 执行单元的 `numIntSrc` 之和 + 所有读 Int 寄存器的 Mem 执行单元的 `numIntSrc` 之和。

### 5.2 写端口分配

写端口数量由执行单元的写回 (Writeback) 路径数量决定：

```scala
// BackendParams.scala (第88-94行)
def numWriteIntRf: Int  // 写整数寄存器文件的端口数
def numWriteFpRf: Int   // 写浮点寄存器文件的端口数
def numWriteVecRf: Int  // 写向量寄存器文件的端口数
```

写回冲突由 `WbBusyArbiter` 处理，当多个执行单元同时尝试写入同一寄存器文件时，仲裁器选择一个通过，其余延迟。

### 5.3 写操作时序

写操作经过一拍延迟：

```scala
// DataPath.scala (第290-292行)
intRfWaddr := io.fromIntWb.get.map(x => RegEnable(x.pdest, x.wen)).toSeq
intRfWdata := io.fromIntWb.get.map(x => RegEnable(x.data, x.wen)).toSeq
intRfWen   := RegNext(VecInit(io.fromIntWb.get.map(_.wen).toSeq))
```

写地址和写数据通过 `RegEnable` 在写使能有效时锁存，写使能通过 `RegNext` 延迟一拍，确保数据和地址稳定后再执行写入。

---

## 6. INT vs FP 寄存器文件分离

### 6.1 分离设计的原因

XiangShan 采用 INT/FP/Vec 完全分离的物理寄存器文件架构，主要原因：

1. **端口需求不同**：整数执行单元数量远多于浮点执行单元，需要更多的读写端口
2. **数据宽度不同**：整数 64-bit，向量 128-bit，分离后可各自优化
3. **零寄存器语义**：整数有硬连线的 x0=0，浮点无此需求
4. **功耗优化**：访问一种类型时，其他类型的寄存器文件不需翻转，减少动态功耗

### 6.2 关键差异对比

| 特性 | IntRegFile | FpRegFile | VfRegFile |
|------|-----------|-----------|-----------|
| 条目数 | 224 | 256 | 128 |
| 数据宽度 | 64-bit | 64-bit | 128-bit |
| Bank数 | 4 | 1 | 1 (split 4) |
| hasZero | true | false | false |
| RegCache | 有 (36条目) | 无 | 无 |
| 读源操作数 | `numIntSrc` | `numFpSrc` | `numVecSrc` |

### 6.3 跨域写回

某些执行单元需要同时写入多种类型的寄存器文件。例如 `FEX0`（FP 执行单元 0）同时具有 `FpWB(port=0, 0)` 和 `IntWB(port=3, 1)` 写端口，用于 F2I 指令的结果写回整数寄存器文件。同理，`ALU2` 具有 `FpWB(port=0, 1)` 写端口，用于 I2F 指令的浮点结果写回。

这种跨域写回通过 `toFpRf` / `toIntRf` 等独立的写回通路实现，避免在寄存器文件级别引入复杂的多类型仲裁逻辑。

---

## 7. Bypass Network 与寄存器数据转发

### 7.1 DataSource 选择机制

Bypass Network 是 XiangShan 后端数据通路的核心组件，负责根据每条指令的源操作数来源状态 (`DataSource`)，从不同路径选择正确的数据。

`DataSource` 是一个 4-bit 编码字段，定义在 `DataSource.scala` 中：

```
DataSource 编码表:
┌──────────┬─────────┬─────────────────────────────────────┐
│ 编码(二进制)│ 常量名   │ 含义                                 │
├──────────┼─────────┼─────────────────────────────────────┤
│ b1000    │ reg     │ 从寄存器文件读取                      │
│ b0110    │ regcache│ 从 RegCache 读取                     │
│ b0101    │ v0      │ 从 V0 寄存器读取                     │
│ b0000    │ zero    │ 读取零 (Int x0)                      │
│ b0001    │ forward │ 前递数据 (同一周期)                    │
│ b0010    │ bypass  │ 旁路数据 (下一周期)                    │
│ b0011    │ bypass2 │ 二级旁路数据 (两周期后)                │
│ b0100    │ imm     │ 立即数                                │
└──────────┴─────────┴─────────────────────────────────────┘
```

### 7.2 数据选择 MUX

在 Bypass Network 中，每个执行单元的每个源操作数通过 `Mux1H` 选择器从以下来源中选择：

```scala
// BypassNetwork.scala (第208-219行)
val originSrc = Mux1H(
  Seq(
    readForward    -> Mux1H(forwardOrBypassValidVec3(...), forwardDataVec),
    readBypass     -> Mux1H(forwardOrBypassValidVec3(...), bypassDataVec),
    readBypass2    -> Mux1H(bypass2ValidVec3(...), bypass2DataVec),
    readZero       -> 0.U,
    readV0         -> exuInput.bits.src(3),
    readRegOH      -> fromDPs(exuIdx).bits.src(srcIdx),  // 寄存器文件输出
    readRegCache   -> fromDPsRCData(exuIdx)(srcIdx),      // RegCache 输出
    readImm        -> imm
  )
)
```

### 7.3 Forward 与 Bypass 的区别

- **Forward（前递）**：执行单元在同一周期内将结果直接前递给下游指令，无需寄存器锁存，延迟最低
- **Bypass（旁路）**：执行单元的结果经过一拍寄存器锁存后前递，适用于无法在同一周期完成的路径
- **Bypass2**：二级旁路，数据经过两拍延迟，专门用于 VF 执行单元到 MEM 执行单元的路径

### 7.4 RegCache 与 Bypass Network 的连接

RegCache 的读数据通过 `rcData` 通路进入 Bypass Network：

```scala
// BypassNetwork.scala (第33-36行)
val rcData: MixedVec[MixedVec[Vec[UInt]]] = MixedVec(
  Seq(intSchdParams, fpSchdParams, vecSchdParams).map(schd =>
    schd.issueBlockParams.map(iq =>
      MixedVec(iq.exuBlockParams.map(exu => Input(Vec(exu.numRegSrc, UInt(...)))))
    )).flatten
)
```

当 `readRegCache` 有效时，Bypass Network 选择 RegCache 输出的数据而非寄存器文件的数据：

```scala
// BypassNetwork.scala (第216行)
readRegCache -> fromDPsRCData(exuIdx)(srcIdx),
```

---

## 8. RegCache 设计详解

### 8.1 RegCache 的动机

在现代超标量处理器中，整数物理寄存器文件（224 条目）是后端功耗和面积的主要来源之一。由于寄存器文件需要大量的读端口（每个执行单元的每个源操作数都需要一个读端口），端口数和容量的乘积导致寄存器文件访问功耗很高。

RegCache (Register Cache) 通过缓存最近使用的整数寄存器值，将部分读操作从大容量 PRF 转移到小容量的 RegCache，从而：

1. **降低功耗**：RegCache 容量远小于 PRF（36 vs 224），访问功耗显著降低
2. **减少关键路径延迟**：小容量存储的读取延迟可能低于大容量多 bank PRF
3. **减轻 PRF 读端口压力**：部分读操作由 RegCache 承担

### 8.2 RegCache 架构

```
                    RegCache 整体架构
┌─────────────────────────────────────────────────────────┐
│                                                          │
│  ┌───────────────────┐    ┌───────────────────┐         │
│  │  IntRegCache       │    │  MemRegCache       │         │
│  │  (24 entries)      │    │  (12 entries)      │         │
│  │  RegCacheDataModule│    │  RegCacheDataModule│         │
│  └────────┬──────────┘    └────────┬──────────┘         │
│           │                        │                      │
│  ┌────────▼──────────┐    ┌────────▼──────────┐         │
│  │IntRegCacheAgeTimer│    │MemRegCacheAgeTimer│         │
│  │  (2-bit计时器)     │    │  (2-bit计时器)     │         │
│  └────────┬──────────┘    └────────┬──────────┘         │
│           │                        │                      │
│  ┌────────▼────────────────────────▼──────────┐         │
│  │          RegCacheAgeDetector                 │         │
│  │    (基于年龄矩阵的替换候选选择)                │         │
│  └────────────────────────────────────────────┘         │
│                                                          │
│  ┌────────────────────────────────────────────────────┐  │
│  │           RegCacheTagTable                          │  │
│  │  ┌────────────────┐  ┌────────────────┐            │  │
│  │  │IntRCTagTable   │  │MemRCTagTable   │            │  │
│  │  │(24 entries)    │  │(12 entries)    │            │  │
│  │  │tag: pregIdx    │  │tag: pregIdx    │            │  │
│  │  │+loadDependency │  │+loadDependency │            │  │
│  │  └────────────────┘  └────────────────┘            │  │
│  └────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### 8.3 IntRegCache 与 MemRegCache 分离

RegCache 被分为两个独立的部分：

- **IntRegCache（24 条目）**：服务于整数执行单元 (ALU、BJU 等) 的读取
- **MemRegCache（12 条目）**：服务于访存执行单元 (LDU、STA 等) 的整数寄存器读取

地址编码使用 6-bit (`RegCacheIdxWidth = log2Up(36) = 6`)，最高位作为选择位：
- 最高位 = 0：访问 IntRegCache
- 最高位 = 1：访问 MemRegCache

```scala
// RegCache.scala (第68-74行)
val int_ren = GatedValidRegNext(r_in.ren & ~r_in.addr(RegCacheIdxWidth - 1))
val mem_ren = GatedValidRegNext(r_in.ren & r_in.addr(RegCacheIdxWidth - 1))
r_int.ren  := int_ren
r_mem.ren  := mem_ren
r_int.addr := in_addr(RegCacheIdxWidth - 2, 0)
r_mem.addr := in_addr(RegCacheIdxWidth - 2, 0)
r_in.data  := Mux(in_addr(RegCacheIdxWidth - 1), r_mem.data, r_int.data)
```

### 8.4 RegCacheDataModule 详解

`RegCacheDataModule` 是 RegCache 的核心存储模块：

```scala
// RegCacheDataModule.scala (第39-91行)
class RegCacheDataModule(name, numEntries, numReadPorts, numWritePorts, dataWidth, addrWidth, tagWidth)
```

**存储结构**：
- `v`: `RegInit(VecInit(Seq.fill(numEntries)(false.B)))` -- 有效位
- `mem`: `Reg(Vec(numEntries, UInt(dataWidth.W)))` -- 数据存储
- `tag`: 可选的调试标签存储

**读操作**：直接组合逻辑读取 `r.data := mem(r.addr)`，无额外延迟（因为 `RegCache` 模块本身在外层已对地址进行了寄存）。

**写操作**：使用 `Mux1H` 实现多端口并行写入，当写入时同时置有效位为 true。

**有效性断言**：读取时断言读取的条目必须有效 `assert(v(r.addr))`，保证不会读取到无效数据。

### 8.5 RegCache 读延迟分析

RegCache 的完整读取路径延迟为 **2 个时钟周期**：

1. **周期 S0**：Issue Queue 发出读请求，提供 RegCache 地址 (`rcIdx`)
2. **周期 S1**：RegCache 地址寄存 (`RegEnable(r_in.addr, r_in.ren)`)，同时读使能通过 `GatedValidRegNext` 延迟
3. **周期 S1**（同一周期）：数据从 `mem(r.addr)` 读出，通过 `Mux1H` 选择 Int 或 Mem 数据

在 DataPath 中：

```scala
// DataPath.scala (第301-312行)
def IssueBundle2RCReadPort(issue: DecoupledIO[Og0InUop]): Vec[RCReadPort] = {
  readPorts.zipWithIndex.foreach { case (r, idx) =>
    r.ren := issue.valid && issue.bits.dataSources(idx).readRegCache
    r.addr := issue.bits.rcIdx.get(idx)
  }
}
```

### 8.6 RegCache 被哪些执行单元使用

根据 `ExeUnitParams.scala` 中的定义：

```scala
// ExeUnitParams.scala (第125-126行)
def needReadRegCache: Boolean  = backendParam.regCacheEn && (isIntExeUnit || isMemExeUnit && readIntRf)
def needWriteRegCache: Boolean = isIntExeUnit && isIQWakeUpSource || isMemExeUnit && isIQWakeUpSource && readIntRf
```

- **读 RegCache**：所有整数执行单元 + 读整数寄存器的访存执行单元
- **写 RegCache**：作为 IQ WakeUp Source 的整数执行单元 (ALU) + 作为 IQ WakeUp Source 且读 Int 寄存器的访存执行单元 (Load)

---

## 9. RegCache 年龄检测与替换策略

### 9.1 RegCacheAgeTimer

每个 RegCache 条目都有一个 2-bit 年龄计时器，用于追踪条目的新鲜度：

```scala
// RegCacheAgeTimer.scala (第51行)
val ageTimer = RegInit(VecInit((0 until numEntries).map(i => (i / (numEntries / 4)).U(2.W))))
```

初始化时，24 个条目被分为 4 组（每组 6 个），分别初始化为 0、1、2、3，形成一个均匀分布的初始年龄。

**计时器更新规则**：
1. **写入时**：计时器重置为 0（最年轻）
2. **被读取时**：计时器保持不变（不老化）
3. **达到最大值 (3) 且有效**：保持为 3（不再增长）
4. **其他情况**：计时器加 1（逐渐老化）

```scala
when(hasWriteReq(i)) {
  atNext := 0.U           // 写入 -> 重置
}.elsewhen(hasReadReq(i)) {
  atNext := ageTimer(i)   // 读取 -> 不变
}.elsewhen(ageTimer(i) === 3.U && io.validInfo(i)) {
  atNext := 3.U           // 已满且有效 -> 保持
}.otherwise {
  atNext := ageTimer(i) + 1.U  // 正常老化
}
```

### 9.2 年龄比较函数

年龄比较基于 2-bit 计时器和额外的组内排序信号 (`ageTimerExtra`)：

```scala
// RegCacheAgeTimer.scala (第87行)
res := Cat(ageTimerNext(row), ageTimerExtra(row / (numEntries / 4))) >= 
       Cat(ageTimerNext(col), ageTimerExtra(col / (numEntries / 4)))
```

比较逻辑将 2-bit 计时器与组内排序信号拼接为 4-bit 进行比较，其中 `ageTimerExtra` 每周期循环递增 (0->1->2->3->0)，提供组内细粒度的年龄排序。

此外，有效位也参与比较：有效条目比无效条目更"老"（优先替换无效条目）。

### 9.3 RegCacheAgeDetector

`RegCacheAgeDetector` 使用全连接年龄矩阵确定替换候选：

```scala
// AgeDetector.scala (第26-71行)
class RegCacheAgeDetector(numEntries, numReplace)
```

**年龄矩阵** `age(i)(j)`：entry i 比 entry j 更老时为 true。矩阵只存储上三角部分以节省寄存器。

**替换候选选择**：通过统计每行的 `PopCount`（即每个 entry 比多少个其他 entry 更老），选择 `rowOnesSum` 值最大的条目作为最老的条目进行替换。

```scala
val rowOnesSum = (0 until numEntries).map(i => 
  PopCount((0 until numEntries).map(j => get_age(i, j)))
)
io.out.zipWithIndex.foreach { case (out, idx) =>
  out := PriorityMux(rowOnesSum.map(_ === (numEntries - idx).U).zip(...))
}
```

每个写入端口需要一个替换候选，因此 `numReplace` 等于写入端口数。

### 9.4 替换延迟

替换候选的计算有 3 个周期的延迟：

```scala
// RegCache.scala (第116-120行)
val delayToWakeupQueueRCIdx = RegNextN(io.toWakeupQueueRCIdx, 3)
writePorts := io.writePorts
writePorts.zip(delayToWakeupQueueRCIdx).foreach{ case (w, rcIdx) => 
  w.addr := rcIdx  // 使用3拍前计算的替换地址
}
```

这 3 个周期的延迟对应于 Wakeup Queue 的流水线深度，确保替换地址与唤醒信号同步到达 Issue Queue。

---

## 10. RegCache Tag Table 与 Tag 查找

### 10.1 Tag Table 结构

RegCache Tag Table 负责将物理寄存器索引 (pregIdx) 映射到 RegCache 条目地址：

```scala
// RegCacheTagModule.scala (第41-105行)
class RegCacheTagModule(name, numEntries, numReadPorts, numWritePorts, addrWidth, tagWidth)
```

每个 Tag Table 条目包含：
- `v`: 有效位 (1-bit)
- `tag`: 物理寄存器索引 (pregIdxWidth-bit，即 log2Up(224) = 8-bit)
- `loadDependency`: Load 依赖向量 (LoadPipelineWidth x LoadDependencyWidth-bit)

### 10.2 Tag 查找（读操作）

当执行单元需要读取一个物理寄存器时，先在 Tag Table 中查找该寄存器是否存在于 RegCache 中：

```scala
// RegCacheTagModule.scala (第67-73行)
for ((r, i) <- io.readPorts.zipWithIndex) {
  val matchOH = v.zip(tag).map(x => x._1 && x._2 === r.tag)
  r.valid := Mux(r.ren, matchOH.orR, false.B)
  r.addr  := OHToUInt(matchOH)
}
```

查找过程：
1. 将输入的物理寄存器索引 (`tag`) 与所有有效条目的 tag 进行比较
2. 产生 one-hot 匹配向量 (`matchOH`)
3. `valid` 表示命中（至少一个条目匹配）
4. `addr` 通过 `OHToUInt` 转换为 RegCache 数据模块的地址

**性能断言**：`assert(PopCount(matchOH) <= 1.U)` 确保不会出现重复缓存同一寄存器的情况。

### 10.3 RegCacheTagTable 整合

`RegCacheTagTable` 整合了 Int 和 Mem 两个 Tag Table：

```scala
// RegCacheTagTable.scala (第30-110行)
class RegCacheTagTable(numReadPorts)

val IntRCTagTable = Module(new RegCacheTagModule("IntRCTagTable", IntRegCacheSize, ...))
val MemRCTagTable = Module(new RegCacheTagModule("MemRCTagTable", MemRegCacheSize, ...))
```

读取时同时查询两个 Tag Table，选择命中的那个：

```scala
// RegCacheTagTable.scala (第59-61行)
r_in.valid := (r_int.valid || r_mem.valid) && !matchAlloc
r_in.addr  := Mux(r_int.valid, Cat("b0".U, r_int.addr), Cat("b1".U, r_mem.addr))
```

注意：如果物理寄存器正在被重命名分配 (`matchAlloc`)，即使 Tag Table 命中也视为不命中，因为该寄存器值即将无效。

---

## 11. RegCache 写入与唤醒机制

### 11.1 写入来源

RegCache 的数据写入来自 Issue Queue 的唤醒信号 (Wakeup)。当一个执行单元产生结果并准备唤醒下游指令时，其结果同时写入 RegCache：

```scala
// RegCacheTagTable.scala (第64-65行)
val wakeupFromIQNeedWriteRC = io.wakeupFromIQ.filter(_.bits.params.needWriteRegCache)
```

只有作为 IQ WakeUp Source 的执行单元才会触发 RegCache 写入：
- **ALU 单元**（整数调度器中）：`isIntExeUnit && isIQWakeUpSource`
- **Load 单元**（访存调度器中，且读 Int 寄存器）：`isMemExeUnit && isIQWakeUpSource && readIntRf`

### 11.2 写入数据来源

RegCache 写入数据来自 Bypass Network 的输出：

```scala
// DataPath.scala (第335行)
regCache.io.writePorts := io.fromBypassNetwork
```

Bypass Network 将执行单元的输出经过延迟和选择后，连接到 RegCache 的写端口：

```scala
// BypassNetwork.scala (第301-326行)
private val forwardIntWenVec = VecInit(
  fromExus.filter(_.bits.params.needWriteRegCache).map(x => x.valid && x.bits.intWen)
)
private val bypassRCDataVec = VecInit(
  fromExus.zip(bypassDataVec).filter(_._1.bits.params.needWriteRegCache).map(_._2)
)
io.toDataPath.zipWithIndex.foreach{ case (x, i) => 
  x.wen  := bypassIntWenVec(i)
  x.data := bypassRCDataVec(i)
  x.tag  := bypassTagVec(i)
}
```

### 11.3 Wakeup Queue 与 RCIdx

RegCache 为每个写端口分配一个 `RCIdx`（RegCache 索引），通过 `toWakeupQueueRCIdx` 输出到 Wakeup Queue。Wakeup Queue 在唤醒 Issue Queue 中的条目时，将对应的 `RCIdx` 传递给 Issue Queue Entry，使其知道该寄存器值在 RegCache 中的位置。

```scala
// RegCache.scala (第107-114行)
io.toWakeupQueueRCIdx.zipWithIndex.foreach{ case (rcIdx, i) => 
  if (i < IntRegCacheWriteSize) {
    rcIdx := Cat("b0".U, IntRegCacheRepRCIdx(i))
  } else {
    rcIdx := Cat("b1".U, MemRegCacheRepRCIdx(i - IntRegCacheWriteSize))
  }
}
```

### 11.4 Issue Queue Entry 中的 RegCache 状态

每个 Issue Queue Entry 的源操作数状态中维护 `useRegCache` 和 `regCacheIdx` 字段：

```scala
// EntryBundles.scala (第57-58行)
val useRegCache = Option.when(params.needReadRegCache)(Bool())
val regCacheIdx = Option.when(params.needReadRegCache)(UInt(RegCacheIdxWidth.W))
```

当 Issue Queue Entry 收到唤醒信号时，根据唤醒来源是否写入 RegCache，更新 `useRegCache` 为 true，并保存对应的 `regCacheIdx`。当条目被发射时，`useRegCache` 决定数据来源是 RegCache 还是寄存器文件。

```scala
// EntryBundles.scala (第472-487行)
val useRegCache = status.srcStatus(srcIdx).useRegCache.getOrElse(false.B) && 
                  status.srcStatus(srcIdx).dataSources.readReg
// ...
useRegCache -> DataSource.regcache,
// ...
useRegCache -> DataSource.regcache,
```

---

## 12. RegCache 取消与失效逻辑

### 12.1 三种取消场景

RegCache 条目可能在以下情况下被取消（失效）：

```scala
// RegCacheTagTable.scala (第88-109行)
val cancelVec = allocVec.lazyZip(replaceVec).lazyZip(ldCancelVec).lazyZip(validVec)
  .map{ case (alloc, rep, ldCancel, v) => 
    (alloc || rep || ldCancel) && v
  }
```

#### (a) 重命名分配取消 (`allocVec`)

当 Rename 阶段分配一个新的物理寄存器时，如果该寄存器的旧值存在于 RegCache 中，则取消对应的 RegCache 条目：

```scala
val allocVec = (IntRCTagTable.io.tagVec ++ MemRCTagTable.io.tagVec).map{ t => 
  io.allocPregs.map(a => a.valid && a.bits === t).asUInt.orR
}
```

`allocPregs` 来自 Rename 模块，表示每个 Rename 槽分配的新物理寄存器索引。如果 RegCache 中缓存的旧物理寄存器被重新分配，则该缓存条目必须失效。

#### (b) 替换取消 (`replaceVec`)

当同一物理寄存器被新的唤醒信号写入 RegCache 时（即 RegCache 条目被更新），旧的缓存值被替换：

```scala
val replaceVec = IntRCTagTable.io.tagVec.map{ t => 
  IntRCTagTable.io.writePorts.map(w => w.wen && w.tag === t).asUInt.orR
} ++ MemRCTagTable.io.tagVec.map{ t => 
  MemRCTagTable.io.writePorts.map(w => w.wen && w.tag === t).asUInt.orR
}
```

#### (c) Load 取消 (`ldCancelVec`)

当 Load 操作因取消信号 (`ldCancel`) 而被取消时，依赖该 Load 的 RegCache 条目也需要取消：

```scala
val ldCancelVec = (IntRCTagTable.io.loadDependencyVec ++ MemRCTagTable.io.loadDependencyVec)
  .map{ ldDp => LoadShouldCancel(Some(ldDp), io.ldCancel) }
```

这通过 `loadDependency` 向量追踪每个 RegCache 条目对 Load 操作的依赖关系。每个周期，`loadDependency` 中的值左移一位（老化），直到被取消或自然衰减为零。

### 12.2 Load 依赖管理

Tag Table 中的每个条目维护 `loadDependency` 向量：

```scala
// RegCacheTagModule.scala (第65行)
val loadDependency = Reg(Vec(numEntries, Vec(LoadPipelineWidth, UInt(LoadDependencyWidth.W))))
```

当写入 Tag Table 时，同时写入唤醒来源的 `loadDependency`（经过移位处理）：

```scala
// RegCacheTagTable.scala (第69-77行)
shiftLoadDependency.zip(wakeupFromIQNeedWriteRC.map(_.bits.loadDependency)).foreach {
  case ((deps, originalDeps), name) => deps.zip(originalDeps).zipWithIndex.foreach {
    case ((dep, originalDep), deqPortIdx) =>
      if (backendParams.getLdExuIdx(...) == deqPortIdx)
        dep := 1.U                    // 本 Load 通道：依赖计数为 1
      else
        dep := originalDep << 1      // 其他通道：依赖左移（老化）
  }
}
```

写入后，每个周期 `loadDependency` 自然衰减（左移一位），表示该条目与 Load 的距离越来越远，直到依赖完全消失。

---

## 13. 性能影响分析

### 13.1 RegCache 的性能收益

**命中率**：RegCache 只缓存整数寄存器值，对于频繁使用的临时变量（如循环计数器、数组索引等），命中率可达较高水平。由于 IntRegCache 有 24 个条目，MemRegCache 有 12 个条目，总共 36 个条目可以覆盖常见的活跃寄存器集。

**功耗降低**：每次 RegCache 命中可以避免访问 224 条目的整数寄存器文件。考虑到寄存器文件的功耗与条目数和端口数成正比，RegCache 的 36 条目相比 224 条目可以将每次读操作的功耗降低约 5-6 倍。

**延迟影响**：RegCache 读取需要 2 个周期（地址寄存 + 数据读取），而寄存器文件读取需要 1 个周期。但 RegCache 在 S0 阶段就发出读请求，与 Issue Queue 的流水线重叠，因此在实际执行中，RegCache 命中的读取延迟不会额外增加执行延迟。

### 13.2 寄存器文件 Bank 的性能影响

整数寄存器文件使用 4-bank 设计，可以支持 4 个独立的并行读操作（只要读地址落在不同的 bank 中）。当多个执行单元同时读取同一 bank 时，会产生 bank 冲突，需要仲裁。

Bank 冲突概率分析：
- 224 条目分配到 4 个 bank，每个 bank 56 条目
- 地址的最低 2 位决定 bank 选择
- 均匀分布下，两个随机地址落在同一 bank 的概率为 1/4

### 13.3 Split 模式对 FP/Vec 寄存器文件的影响

FP 和 Vec 寄存器文件使用 split 模式（正常模式下 split 为 4 份），将宽数据拆分到多个窄存储中：

- FP 寄存器文件：64-bit 数据拆分为 4 x 16-bit
- V0 寄存器文件：128-bit 数据拆分为 (VLEN/XLEN) = 2 x 64-bit

Split 模式的优势：
1. 每个子存储的位宽更窄，访问更快
2. 多个子存储可以并行读写，提高吞吐量
3. 降低了单个存储模块的面积和功耗

### 13.4 Bypass Network 的性能影响

Bypass Network 的多级转发机制 (forward/bypass/bypass2) 可以消除大部分 RAW (Read After Write) 数据冒险：

- **forward**：0 周期延迟（同周期转发），消除紧邻的 RAW 冒险
- **bypass**：1 周期延迟，消除间隔 1 条指令的 RAW 冒险
- **bypass2**：2 周期延迟，消除 VF->MEM 路径的 RAW 冒险

这些转发机制显著减少了因数据依赖导致的流水线停顿，是 XiangShan 实现高 IPC 的关键。

### 13.5 综合性能考量

XiangShan 的寄存器文件系统设计体现了以下权衡：

1. **容量 vs 端口数**：224 个整数物理寄存器足够支持较深的乱序窗口（ROB=352），同时 4-bank 设计平衡了并行读取需求和 bank 冲突概率

2. **功耗 vs 性能**：RegCache 以少量额外硬件（36 条目数据存储 + Tag Table + AgeTimer）为代价，显著降低了寄存器文件的访问功耗，同时不影响性能

3. **延迟 vs 吞吐量**：寄存器文件的 1 周期读取延迟与 RegCache 的 2 周期读取延迟通过流水线重叠被有效隐藏，不影响指令发射速率

4. **面积 vs 灵活性**：分离式寄存器文件 (INT/FP/Vec) 虽然增加了总面积（需要独立的控制逻辑），但大幅提升了各类型寄存器文件的访问灵活性和功耗效率

---

## 14. 关键源文件索引

| 文件路径 | 说明 |
|---------|------|
| `src/main/scala/xiangshan/backend/regfile/PregParams.scala` | 物理寄存器文件参数定义（IntPregParams, FpPregParams, VfPregParams 等） |
| `src/main/scala/xiangshan/backend/regfile/Regfile.scala` | 寄存器文件核心实现（Regfile, RegfileBank, IntRegFile, FpRegFile, VfRegFile 等） |
| `src/main/scala/xiangshan/backend/regcache/RegCache.scala` | RegCache 顶层模块，整合 IntRegCache 和 MemRegCache |
| `src/main/scala/xiangshan/backend/regcache/RegCacheDataModule.scala` | RegCache 数据存储模块（RCReadPort, RCWritePort） |
| `src/main/scala/xiangshan/backend/regcache/RegCacheTagModule.scala` | RegCache Tag 存储模块（RCTagTableReadPort, RCTagTableWritePort） |
| `src/main/scala/xiangshan/backend/regcache/RegCacheTagTable.scala` | RegCache Tag 查找表（整合 IntRCTagTable 和 MemRCTagTable） |
| `src/main/scala/xiangshan/backend/regcache/RegCacheAgeTimer.scala` | RegCache 年龄计时器（2-bit 计时器 + 组内排序） |
| `src/main/scala/xiangshan/backend/regcache/AgeDetector.scala` | RegCache 年龄检测器（全连接年龄矩阵 + 替换候选选择） |
| `src/main/scala/xiangshan/backend/datapath/BypassNetwork.scala` | Bypass Network（数据来源选择、RegCache 数据通路连接） |
| `src/main/scala/xiangshan/backend/datapath/DataSource.scala` | DataSource 编码定义（reg, regcache, forward, bypass 等） |
| `src/main/scala/xiangshan/backend/datapath/DataPath.scala` | 后端数据通路（寄存器文件实例化、RegCache 集成、RFReadArbiter） |
| `src/main/scala/xiangshan/backend/BackendParams.scala` | 后端参数定义（RegCache 大小、读写端口数计算） |
| `src/main/scala/xiangshan/Parameters.scala` | 全局参数定义（物理寄存器条目数、RegCache 大小） |
| `src/main/scala/xiangshan/backend/exu/ExeUnitParams.scala` | 执行单元参数（needReadRegCache, needWriteRegCache） |
| `src/main/scala/xiangshan/backend/issue/EntryBundles.scala` | Issue Queue Entry 数据结构（useRegCache, regCacheIdx） |
| `src/main/scala/xiangshan/backend/issue/SchdBlockParams.scala` | 调度器参数（numPregs, numIntRfReadByExu 等） |

---

## 15. 总结

XiangShan 的寄存器文件与 RegCache 系统体现了现代高性能乱序处理器后端设计的核心思想：

**寄存器文件层面**：
- INT/FP/Vec 完全分离的物理寄存器文件，各有独立的参数和优化策略
- INT 使用 4-bank 设计支持并行读取，FP/Vec 使用 split 模式支持宽数据高效存取
- 224 个整数物理寄存器 + 256 个浮点物理寄存器 + 128 个向量物理寄存器，为深度乱序执行提供充足的寄存器资源

**RegCache 层面**：
- 仅缓存整数寄存器值（IntRegCache 24 条目 + MemRegCache 12 条目），显著降低寄存器文件访问功耗
- 基于 Tag Table 的全相联查找，实现高效的寄存器值定位
- 基于 2-bit 年龄计时器和全连接年龄矩阵的 LRU-like 替换策略
- 与 Wakeup Queue 紧密集成，通过 3 拍延迟流水线实现替换地址与唤醒信号同步
- 完善的取消逻辑覆盖重命名分配、替换更新和 Load 取消三种场景

**Bypass Network 层面**：
- 8 种数据来源（reg/regcache/forward/bypass/bypass2/zero/v0/imm）覆盖所有操作数获取场景
- RegCache 作为独立数据来源无缝集成到 Bypass Network 中
- 多级转发机制（forward/bypass/bypass2）有效消除 RAW 数据冒险

整个寄存器文件系统的设计目标是在保证高性能的同时，通过 RegCache、banking、split 等技术手段优化功耗、面积和时序，体现了 XiangShan 在微架构层面的精细工程设计。
