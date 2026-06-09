# R34A - SRAM Primitives & ECC 深度研究报告

## 1. 概述

XiangShan 处理器的 SRAM（Static Random Access Memory）原语与 ECC（Error Correcting Code）子系统是整个芯片存储层次结构的基石。该子系统分布在两个主要代码仓库中：`utility/`（通用 SRAM 模板与 ECC 编解码库）和 `XSCache/`（L2 Cache 专用 SRAM 变体），另外 `ChiselIOPMP/` 中包含一个独立的 TrueDualPortSRAM 实现。整个体系从最底层的 `SramArray`（基于 `SyncReadMem` 的 Chisel RTL 原语）到上层可配置的 `SRAMTemplate`，再到 ECC 编解码层次结构，形成了一个完整、层次清晰、可复用的 SRAM 基础设施。

---

## 2. SRAMTemplate 完整参数集与所有变体

### 2.1 SRAMTemplate 核心参数

`SRAMTemplate` 是 XiangShan 中最核心的 SRAM 封装模块，位于 `utility/src/main/scala/utility/sram/SRAMTemplate.scala`。它是一个泛型参数化模块，完整参数列表如下：

| 参数名 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `gen` | `T <: Data` | (必选) | 条目数据类型，决定每条 SRAM 存储的数据宽度 |
| `set` | `Int` | (必选) | SRAM depth（地址空间大小，即 set 数量） |
| `way` | `Int` | `1` | SRAM way 数量，支持多 way 并行访问 |
| `singlePort` | `Boolean` | `false` | 是否为单端口 SRAM（单端口时不能同时读写） |
| `shouldReset` | `Boolean` | `false` | 上电后是否自动将所有条目清零 |
| `extraReset` | `Boolean` | `false` | 是否添加额外的外部 reset 端口 |
| `holdRead` | `Boolean` | `false` | 是否在无读请求时保持上一次读出数据 |
| `bypassWrite` | `Boolean` | `false` | 读写同地址时是否将写数据旁路到读输出（向后兼容，应替换为 conflictBehavior） |
| `conflictBehavior` | `SRAMConflictBehavior` | `DefaultBehavior` | 双端口 SRAM 读写冲突行为（见 2.3 节） |
| `useBitmask` | `Boolean` | `false` | 是否使用 bitmask 模式（每个数据 bit 一个 mask bit） |
| `withClockGate` | `Boolean` | `false` | 是否为读写端口分别添加 clock gate |
| `separateGateClock` | `Boolean` | `false` | 无实际效果，仅为 API 兼容性保留 |
| `hasMbist` | `Boolean` | `false` | 是否启用 MBIST（Memory Built-In Self-Test）支持 |
| `latency` | `Int` | `1` | 输出 setup 多周期参数，表示读采样时钟边沿后数据可被捕获的周期数 |
| `extraHold` | `Boolean` | `false` | 是否启用额外一个输入保持周期，启用后用户需保持输入一个周期 |
| `extClockGate` | `Boolean` | `false` | 是否暴露时钟控制信号到 IO，以支持 MBIST 时使用外部 clock gate |
| `hasSramCtl` | `Boolean` | `false` | 是否启用 SRAM 控制信号支持 |
| `suffix` | `Option[String]` | `None` | SRAM wrapper 名称后缀，便于在层次结构中识别 |

### 2.2 IO 接口定义

`SRAMTemplate` 的 IO 接口由以下部分组成：

- **`io.r`**: `SRAMReadBus`，包含 `req`（Decoupled，带 `setIdx`）和 `resp`（`Vec(way, gen)` 数据输出）
- **`io.w`**: `SRAMWriteBus`，包含 `req`（Decoupled，带 `setIdx`、`data`、`waymask`、可选 `bitmask`）
- **`io.broadcast`**: 可选 `SramBroadcastBundle`，仅在 `hasMbist=true` 时存在，用于 MBIST 广播控制信号
- **`io.mbistCgCtl`**: 可选时钟门控控制 bundle，仅在 `hasMbist && extClockGate` 时存在
- **`io.resetDone`**: `Bool`，输出信号，指示 SRAM 复位是否完成

### 2.3 SRAMConflictBehavior 枚举类型

XiangShan 定义了一个丰富的双端口 SRAM 读写冲突行为枚举 `SRAMConflictBehavior`，用于处理同一地址同时读写时的情况：

| 行为 | macroAllowsConflict | 说明 |
|------|---------------------|------|
| `CorruptRead` | true | 允许冲突，但所有读数据损坏（用随机数据替换） |
| `CorruptReadWay` | true | 允许冲突，仅被写入的 way 的读数据损坏（**默认行为**） |
| `BypassWrite` | true | 允许冲突，将写数据旁路到读数据 |
| `AssertionFail` | false | 不允许冲突，发生时触发 assertion failure |
| `BufferWrite` | false | 不允许冲突，将写数据存入单 entry buffer，下周期 stall 后写入 |
| `BufferWriteLossy` | false | 不允许冲突，使用 buffer 但可能丢失写入（buffer 有效期间新冲突会覆盖） |
| `BufferWriteLossyFast` | false | 同上但时序更优，允许更频繁地丢失 buffer 内容 |
| `StallWrite` | false | 不允许冲突，通过 de-assert ready 来 stall 写操作 |
| `StallRead` | false | 不允许冲突，通过 de-assert ready 来 stall 读操作 |

**默认值为 `CorruptReadWay`**，即允许冲突但在冲突时被写入的 way 的读取数据会被随机数据替换。这是一种在时序和正确性之间取得平衡的策略——在多数 cache 设计中，写入的 way 的读取数据在冲突周期本就不需要使用，因此损坏不会影响功能正确性。

### 2.4 SRAMTemplate 变体家族

XiangShan 中 SRAMTemplate 共有以下变体：

#### (1) SplittedSRAMTemplate

位于同一文件 `utility/src/main/scala/utility/sram/SRAMTemplate.scala` 第 507 行。支持将一个大 SRAM 按 **set 维度**、**way 维度**、**data 维度** 三个方向分割成多个小 SRAM：

```scala
class SplittedSRAMTemplate[T <: Data](
  gen: T, set: Int, way: Int = 1,
  setSplit: Int = 1, waySplit: Int = 1, dataSplit: Int = 1, ...
)
```

核心参数额外增加 `setSplit`、`waySplit`、`dataSplit`。内部创建 `setSplit x waySplit x dataSplit` 个小 SRAMTemplate 实例，通过 bank select 逻辑选择对应的 set bank，通过 Mux1H 选择读响应。该设计来源于 coupledL2 的 SplittedSRAM。

#### (2) FoldedSRAMTemplate

位于同文件第 633 行。将 set 维度折叠到 way 维度中——给定 `width`（折叠因子），将 `width * way` 个 way 合并存储到 SRAM 的 way 维度，实际行数降为 `set / width`。读出后通过 `UIntToOH(ridx)` 从折叠的 way 中选择正确的列。

```scala
class FoldedSRAMTemplate[T <: Data](
  gen: T, set: Int, width: Int = 4, way: Int = 1, ...
)
```

#### (3) SRAMTemplateWithArbiter

位于同文件第 708 行。将多个读端口（`nRead`）通过 Arbiter 复用到单端口 SRAMTemplate 上。适用于读端口多但 SRAM 本身为 singlePort 的场景（如 FTQ）。每个读端口通过 `HoldUnless` 锁存读结果。

#### (4) XSCache 中的 SRAMTemplate

位于 `XSCache/src/main/scala/coupledL2/utils/SRAMTemplate.scala` 第 113 行。这是 coupledL2 模块自带的简化版本 SRAMTemplate，参数更少（无 bitmask、无 MBIST、无 SramCtl），增加了 `clkDivBy2`（SRAM 时钟为 L2 时钟的一半分频）和 `readMCP2`（读数据为多周期路径 2）参数。它直接使用 `SyncReadMem`，不经过 `SramProto` 抽象层。

#### (5) XSCache 中的 SplittedSRAM

位于 `XSCache/src/main/scala/coupledL2/utils/SplittedSRAM.scala`。功能与 `SplittedSRAMTemplate` 类似，但基于 XSCache 自己的 SRAMTemplate 版本。支持 `clockGated`、`readMCP2` 等 XSCache 特有参数。

#### (6) XSCache 中的 BankedSRAM

位于 `XSCache/src/main/scala/coupledL2/utils/BankedSRAM.scala`。按 set 维度分 bank，每个 bank 为一个独立的 SRAMTemplate 实例。支持 bank 级并行访问（不同 bank 可同时读写）。

### 2.5 package.scala 中的类型别名

`utility/src/main/scala/utility/package.scala` 定义了从 `utility` 包到 `utility.sram` 子包的类型别名（标记为 `@deprecated`），以保持向后兼容：

```scala
package object utility {
  type SRAMTemplate[T <: Data] = _root_.utility.sram.SRAMTemplate[T]
  type FoldedSRAMTemplate[T <: Data] = _root_.utility.sram.FoldedSRAMTemplate[T]
  type SRAMTemplateWithArbiter[T <: Data] = _root_.utility.sram.SRAMTemplateWithArbiter[T]
  type SRAMBundleAW[T <: Data] = _root_.utility.sram.SRAMBundleAW[T]
  type SRAMReadBus[T <: Data] = _root_.utility.sram.SRAMReadBus[T]
  type SRAMWriteBus[T <: Data] = _root_.utility.sram.SRAMWriteBus[T]
}
```

---

## 3. SramProto 接口与 SramArray1P/2P

### 3.1 SramProto 工厂对象

`SramProto` 是一个 Scala object，位于 `utility/src/main/scala/utility/sram/SramProto.scala`，充当 SRAM array 的工厂角色。它使用 Chisel 的 `hierarchy` 实验特性（`Definition` 和 `Instance`）来实现 SRAM 定义的共享与复用。

#### 核心 API：

- **`SramProto.apply(clock, singlePort, depth, width, ...)`**: 创建一个 SramArray 实例。内部维护一个 `defMap: mutable.Map[String, Definition[SramArray]]`，相同配置的 SRAM 共享同一个 Definition，从而实现面积优化。返回 `(Instance[SramArray], sramName)`。

- **`SramProto.init(sram, singlePort, clock, writeClock)`**: 初始化 SRAM 实例的默认值（DontCare + en=false）。对单端口设置 `RW0.clk` 和 `RW0.en`，对双端口设置 `R0` 和 `W0` 的时钟和使能。

- **`SramProto.read(sram, singlePort, addr, enable)`**: 执行读操作。单端口时驱动 `RW0.addr` 和 `RW0.en`，返回 `RW0.rdata`；双端口时驱动 `R0.addr` 和 `R0.en`，返回 `R0.data`。

- **`SramProto.write(sram, singlePort, addr, data, mask)`**: 执行写操作。单端口时设置 `RW0.wmode=true` 并驱动地址/数据/mask；双端口时驱动 `W0` 的所有信号。

SRAM 命名规则：`sram_array_{1|2}p{depth}x{width}m{maskWidth}s{setup}h{hold}l{latency}{b}_{suffix}`

### 3.2 SramArray 抽象基类

`SramArray` 是所有 SRAM array 的抽象基类（`@instantiable` 标注），参数为：

| 参数 | 说明 |
|------|------|
| `depth` | SRAM 深度（地址空间大小） |
| `width` | 数据总宽度（含所有 mask segment） |
| `maskSegments` | mask 分段数，要求 `width % maskSegments == 0` |
| `hasMbist` | 是否有 MBIST 接口 |
| `hasSramCtl` | 是否有 SRAM 控制信号接口 |
| `sramName` | 可选的 SRAM 名称 |
| `singlePort` | 是否为单端口 |

**IO 定义：**

- `mbist: Option[SramMbistIO]` - 可选的 MBIST 接口（`dft_ram_bypass`, `dft_ram_bp_clken`）
- `ram_ctl: Option[UInt(64.W)]` - 可选的 SRAM 控制信号
- `RW0: Option[Bundle]` - 单端口模式下的读写端口（`clk`, `addr`, `en`, `wmode`, `wmask`, `wdata`, `rdata`）
- `R0: Option[Bundle]` - 双端口模式下的读端口（`clk`, `addr`, `en`, `data`）
- `W0: Option[Bundle]` - 双端口模式下的写端口（`clk`, `addr`, `en`, `data`, `mask`）

### 3.3 SramArray1P - 单端口 SRAM

`SramArray1P` 继承自 `SramArray`（`singlePort=true`），使用 Chisel 的 `SyncReadMem` 实现：

**带 mask 的情况** (`maskSegments > 1`)：
```scala
val dataType = Vec(maskSegments, UInt((width / maskSegments).W))
val array = SyncReadMem(depth, dataType)
// 使用 readWrite 方法实现单端口读写
RW0.get.rdata := array.readWrite(addr, wdata.asTypeOf(dataType), wmask.asBools, en, wmode, clk)
```

**不带 mask 的情况** (`maskSegments == 1`)：
```scala
val array = SyncReadMem(depth, UInt(width.W))
RW0.get.rdata := array.readWrite(addr, wdata, en, wmode, clk)
```

关键特点：单端口模式使用 `readWrite` 方法，在同一个端口上通过 `wmode` 信号切换读/写。

### 3.4 SramArray2P - 双端口 SRAM

`SramArray2P` 继承自 `SramArray`（`singlePort=false`），使用分离的读写端口：

**带 mask 的情况**：
```scala
val dataType = Vec(maskSegments, UInt((width / maskSegments).W))
val array = SyncReadMem(depth, dataType, SyncReadMem.WriteFirst)
// 写操作
when(W0.get.en) { array.write(W0.get.addr, W0.get.data.asTypeOf(dataType), W0.get.mask.asBools, W0.get.clk) }
// 读操作
R0.get.data := array.read(R0.get.addr, R0.get.en, R0.get.clk).asUInt
```

**不带 mask 的情况**：
```scala
val array = SyncReadMem(depth, UInt(width.W))
when(W0.get.en) { array.write(W0.get.addr, W0.get.data, W0.get.clk) }
R0.get.data := array.read(R0.get.addr, W0.get.en, W0.get.clk)
```

关键特点：双端口模式使用 `SyncReadMem.WriteFirst` 冲突解决策略（读写同地址时读出新写入的值），读写分离为独立端口。

### 3.5 SramInfo 与 MBIST 节点计算

`SramInfo` 是一个 case class，负责计算 SRAM 的 MBIST 接口参数：

```scala
case class SramInfo(dataBits: Int, way: Int, bist: Boolean)
```

它根据 data width 和 way 数量，自动决定 MBIST 节点的组织方式：
- **Nto1 模式**（`ew > maxMbistDataWidth`）：每个 way 拆分为多个 MBIST 节点
- **1toN 模式**（`ew <= maxMbistDataWidth`）：多个 way 共享一个 MBIST 节点

`SramHelper` object 提供了 `genRam` 方法，整合了 `SramProto` 的调用、`Ram2Mbist` 接口的创建、以及广播信号的连接，是 SRAM 创建的高层入口。

---

## 4. ECC 层次结构

### 4.1 抽象基类

XiangShan 的 ECC 实现位于 `utility/src/main/scala/utility/ECC.scala`，与 `rocket-chip` 中的 `freechips.rocketchip.util.ECC.scala` 结构几乎相同（utility 版本移除了 `eccIndices` 方法）。核心层次结构如下：

**`Decoding` 抽象类**（解码结果）：
- `uncorrected: UInt` - 未纠正的原始数据
- `corrected: UInt` - 纠正后的数据
- `correctable: Bool` - 是否可纠正
- `uncorrectable: Bool` - 是否不可纠正（若为 true，则 correctable 应被忽略）
- `error: Bool` - `correctable || uncorrectable`

**`Code` 抽象类**（编码方案）：
- `canDetect: Boolean` - 是否能检测错误
- `canCorrect: Boolean` - 是否能纠正错误
- `width(w0: Int): Int` - 给定原始数据宽度 w0，返回编码后总宽度
- `encode(x: UInt, poison: Bool = false.B): UInt` - 编码
- `decode(x: UInt): Decoding` - 解码
- `swizzle(x: UInt): UInt` - 将数据位复制到编码字的正确位置（不生成校验位）

### 4.2 IdentityCode（恒等编码）

```scala
class IdentityCode extends Code
```

- `canDetect = false`, `canCorrect = false`
- `width(w0) = w0`（不增加冗余位）
- `encode`: 直接返回输入数据，**不支持 poison**（若 poison 非零则 require 报错）
- `decode`: 返回原始数据，correctable 和 uncorrectable 均为 false
- **用途**：当不需要 ECC 保护时使用，相当于透传

### 4.3 ParityCode（奇偶校验码）

```scala
class ParityCode extends Code
```

- `canDetect = true`, `canCorrect = false`
- `width(w0) = w0 + 1`（增加 1 位校验位）
- `encode`: `Cat(x.xorR ^ poison, x)` —— 最高位为数据所有位的异或值与 poison 的异或
- `decode`: 计算 `y.xorR`（全字异或），若为 1 则 `uncorrectable = true`
- **支持 poison**: 设置 poison=true 会使校验位翻转，导致解码时检测到 uncorrectable error
- **用途**：单 bit 检测但不纠正，开销最小的错误检测方案

### 4.4 SECCode（单纠错码 / Hamming 码）

```scala
class SECCode extends Code
```

- `canDetect = true`, `canCorrect = true`
- `width(k)`: 计算 Hamming 码长度。对于 k 位数据，需要 m 个校验位（`m = log2Floor(k) + 1`），总宽度为 `k + m + 可能的填充位`
- **编码方式**：使用系统码（systematic code），数据位在前，校验位在后（`xxx...xPPPPP`），而非自然 Hamming 码的交错排列（`PPxPxxxP...`），便于直接读取 SRAM 中的数据位
- **实现细节**：`impl(n, k)` 方法计算 `hamm2sys` 和 `sys2hamm` 映射表，将 Hamming 自然排列转换为系统码排列
- **Poison 机制**：当 `poisonous(n)` 为 true（即 `n+1` 不是 2 的幂）时，设置整个 syndrome 为全 1，使纠正位偏移到码字之外，产生 uncorrectable error
- `poisonous(n) = !isPow2(n+1)`：如果码长恰好是 perfect code（`n+1` 是 2 的幂），则所有非码字都是可纠正的，无法 poison
- **decode**: 通过 syndrome 计算定位错误位（`UIntToOH(syndrome, n+1)`），使用 `sysBadBitOH` 翻转对应的系统码位

### 4.5 SECDEDCode（单纠错双检测码）

```scala
class SECDEDCode extends Code
```

- `canDetect = true`, `canCorrect = true`
- `width(k) = sec.width(k) + 1`：在 SEC 码基础上增加 1 位整体奇偶校验
- **编码**：`par.encode(sec.encode(x)) ^ toggle_hi`，其中 `toggle_hi` 在 poison 时翻转 SEC 码的最高冗余位和奇偶位，确保产生不可纠正的错误
- **解码**：分别进行 SEC 解码和 Parity 解码：
  - `correctable = pardec.uncorrectable`（奇偶校验失败 = SEC 的错误检测结果）
  - `uncorrectable = !pardec.uncorrectable && secdec.correctable`（奇偶校验通过但 SEC 报告可纠正 = 双位错误，不可纠正）
- **原理**：SEC 可纠正单错检测双错，Parity 可区分"单错"和"双错"——如果 SEC 报告有错但 Parity 校验通过，说明是双位错误（SEC 产生了一个错误的纠正，恰好使 Parity 仍然通过）

### 4.6 Code.fromString 工厂

```scala
object Code {
  def fromString(s: String): Code = s.toLowerCase match {
    case "none" | "identity" => new IdentityCode
    case "parity"            => new ParityCode
    case "sec"               => new SECCode
    case "secded"            => new SECDEDCode
  }
}
```

该工厂方法在 XSCache 的 `L2Param` 中被使用：

```scala
// L2Param.scala
tagECC: Option[String] = None,   // 默认不使用 tag ECC
dataECC: Option[String] = None,  // 默认不使用 data ECC
def tagCode: Code = Code.fromString(tagECC)
def dataCode: Code = Code.fromString(dataECC)
```

### 4.7 ErrGen 错误生成器

```scala
object ErrGen {
  def apply(width: Int, f: Int): UInt  // 生成 1-bit 错误，概率约 2^-f
  def apply(x: UInt, f: Int): UInt     // 对 x 施加 1-bit 错误
}
```

用于测试中注入错误。

---

## 5. Poison 机制

Poison 是 ECC 编码中一种"软件注入不可纠正错误"的机制。其核心思想是：在编码时通过 poison 参数**故意**破坏码字，使得解码时即使数据本身是正确的，也会报告 uncorrectable error。

### 5.1 各编码方案的 Poison 实现

| 编码方案 | Poison 实现 | 约束 |
|----------|-------------|------|
| `IdentityCode` | **不支持** | `require(poison.isLit && poison.litValue == 0)` |
| `ParityCode` | 翻转奇偶校验位：`Cat(x.xorR ^ poison, x)` | 始终支持 |
| `SECCode` | 将 syndrome 全部置 1，使纠正位偏移到码字之外 | 仅当 `poisonous(n) = !isPow2(n+1)` 时支持 |
| `SECDEDCode` | 翻转 SEC 最高位和 Parity 位：`toggle_hi = Cat(poison, poison) << (sec.width-1)` | 始终支持 |

### 5.2 Poison 的语义

`Code.encode(x, poison=true)` 的语义为：编码后的码字解码时，`decoded.uncorrected == decoded.corrected == x`（数据不变），但 `decoded.uncorrectable == true`。这意味着：

- 数据本身是正确的，不会被错误纠正修改
- 但系统会报告不可纠正错误
- 上层可以据此决定处理方式（如标记数据无效、触发中断等）

### 5.3 在 XSCache 中的 Poison 使用

在 `L2Param` 中，`enablePoison: Boolean = true` 默认启用 poison 支持。在 XSCache 的 TXDAT（数据发送）和 RXDAT（数据接收）模块中，`poison` 信号用于在 TileLink 传输层标记数据不可靠：

```scala
// TXDAT.scala
dat.poison match {
  case Some(p) => p := ...
}

// RXDAT.scala
val poison = io.out.bits.poison.getOrElse(false.B).orR
io.in.respInfo.corrupt := ... || dataCheck || poison

// MMIOBridge.scala
val poison = rxdat.bits.poison.getOrElse(false.B).orR
corrupt := corrupt || derr || nderr || dataCheck || poison
```

当 poison 信号被设置时，接收端会将 `corrupt` 标志置位，通知上层数据不可信。

---

## 6. XSCache 中的 ECC 实际应用

### 6.1 DataStorage（数据存储 ECC）

`XSCache/src/main/scala/coupledL2/DataStorage.scala` 展示了 ECC 在 L2 Cache 数据存储中的实际应用：

**写入时 ECC 编码**（第 89-94 行）：
```scala
val arrayWriteData = if (enableDataECC) {
  Cat(0.U(encDataPadBits.W), Cat(VecInit(Seq.tabulate(dataBankSplit)(i =>
    io.wdata.data(dataBankBits * (i + 1) - 1, dataBankBits * i)
  ).map(data => cacheParams.dataCode.encode(data))))
} else { io.wdata.data }
```

数据按 `dataBankBits` 宽度分段，每段独立进行 ECC 编码，然后拼接写入 SRAM。

**读出时 ECC 解码与错误检测**（第 111-117 行）：
```scala
val error = if (enableDataECC) {
  VecInit(Seq.tabulate(dataBankSplit)(i =>
    arrayRead.data(encBankBits * (i + 1) - 1, encBankBits * (i - 1))
  )).map(data => cacheParams.dataCode.decode(data).error).reduce(_ | _)
  && RegNext(RegNext(io.req.valid && !io.req.bits.wen))
} else { false.B }
```

读出的 ECC 编码数据被分段解码，任何一段的 error 信号为真则整体报告错误。错误信号经过两拍延迟以匹配 SRAM 读取流水线。

### 6.2 Directory（目录存储 ECC）

`XSCache/src/main/scala/coupledL2/Directory.scala` 中 tag 的 ECC 编码（第 224 行）和解码（第 260 行）：
```scala
// 编码：tag 按 bank 分段编码
cacheParams.dataCode.encode(tag)
// 解码：所有 bank 的 error 信号 OR 归约
map(tag => cacheParams.dataCode.decode(tag).error).reduce(_ | _)
```

### 6.3 L2CacheErrorInfo

`L2CacheErrorInfo` 定义在 `XSCache/src/main/scala/coupledL2/Common.scala` 第 509 行，是一个简洁的错误报告结构：

```scala
class L2CacheErrorInfo(implicit p: Parameters) extends L2Bundle {
  val valid = Bool()
  val address = UInt(addressBits.W)
}
```

各 slice 的错误通过 `Arbiter` 聚合后上报给上层。

---

## 7. TrueDualPortSRAM（FPGA 实现）

### 7.1 位置与用途

`TrueDualPortSRAM` 位于 `ChiselIOPMP/src/main/scala/IopmpChecker.scala` 第 144 行，是 ChiselIOPMP 项目中为 IOPMP Checker 专用的双端口 SRAM 实现。

### 7.2 接口定义

```scala
class TrueDualPortSRAM(addrWidth: Int, dataWidth: Int, readLatency: Int = 1, depth: Int = 1024) extends Module {
  val io = IO(new Bundle {
    // Port A
    val a_en, a_we: Input(Bool())
    val a_addr: Input(UInt(addrWidth.W))
    val a_wdata: Input(UInt(dataWidth.W))
    val a_rdata: Output(UInt(dataWidth.W))
    // Port B
    val b_en, b_we: Input(Bool())
    val b_addr: Input(UInt(addrWidth.W))
    val b_wdata: Input(UInt(dataWidth.W))
    val b_rdata: Output(UInt(dataWidth.W))
  })
}
```

与 `SramArray2P` 不同，`TrueDualPortSRAM` 的两个端口完全对称（A 和 B 均可独立读写），而 `SramArray2P` 是分离的 R0（读端口）和 W0（写端口）。

### 7.3 实现细节

底层同样使用 `SyncReadMem`，但行为上模拟了真正的双端口 SRAM：

- **写冲突仲裁**：Port A 优先级更高。当 A 和 B 同时写入相同地址时，Port A 的写入生效，Port B 的写入被抑制。同时通过 `assert` 在仿真中报错。
- **可配置读延迟**：`readLatency` 参数控制读出管线深度。当 `readLatency == 1` 时直接输出；当 `readLatency > 1` 时插入额外的 pipeline register。
- **写入仅在无冲突时生效**：`when(io.b_en && io.b_we && !(io.a_en && io.a_we && (io.a_addr === io.b_addr)))`

### 7.4 在 IOPMP Checker 中的使用

`TrueDualPortSRAM` 被用于 IOPMP Checker 的三个查找表：

| 查找表 | 数据宽度 | 深度 | 用途 |
|--------|----------|------|------|
| `SrcmdTable.srcmd_en` | 31 bits | 32 (rrid_num) | RRID 到 MD 的映射 |
| `MdcfgTable.mdcfg` | 16 bits | 31 (md_num) | MD 到 entry range 的映射 |
| `EntryTable.entry_addr` | 32 bits | 512 (entry_num) | Entry 低 32 位地址 |
| `EntryTable.entry_addrh` | 32 bits | 512 | Entry 高 32 位地址 |
| `EntryTable.entry_cfg` | 11 bits | 512 | Entry 属性（r/w/x/a 等权限位） |

每个表都使用 Port A 进行寄存器配置读写（regcfg），Port B 进行查找读取，完美匹配 TrueDualPortSRAM 的双端口特性。

---

## 8. SyncReadMem 使用模式

`SyncReadMem` 是 Chisel 提供的同步读取存储器原语，在 XiangShan 中有以下几种使用模式：

### 8.1 在 SramArray1P 中（单端口 readWrite）

```scala
// 无 mask
val array = SyncReadMem(depth, UInt(width.W))
array.readWrite(addr, wdata, en, wmode, clk)

// 有 mask
val dataType = Vec(maskSegments, UInt((width / maskSegments).W))
val array = SyncReadMem(depth, dataType)
array.readWrite(addr, wdata.asTypeOf(dataType), wmask.asBools, en, wmode, clk)
```

使用 `readWrite` 方法实现单端口访问，`wmode` 信号决定读或写。

### 8.2 在 SramArray2P 中（双端口分离读写）

```scala
// 写入使用 WriteFirst 冲突解决
val array = SyncReadMem(depth, dataType, SyncReadMem.WriteFirst)
array.write(addr, data, mask, clk)  // 写
array.read(addr, en, clk)           // 读
```

使用 `WriteFirst` 策略确保读写同地址时读出最新的写入值。

### 8.3 在 XSCache SRAMTemplate 中

```scala
val array = SyncReadMem(set, Vec(way, wordType))
// 写入带 way mask
array.write(setIdx, wdata, waymask.asBools)
// 读取
val raw_rdata = array.read(io.r.req.bits.setIdx, realRen)
```

直接使用 `Vec(way, wordType)` 作为存储类型，通过 `waymask` 的 bool 数组进行 per-way 写入控制。

### 8.4 在 TrueDualPortSRAM 中

```scala
val mem = SyncReadMem(depth, UInt(dataWidth.W))
// 两个端口分别调用 read/write
mem.write(io.a_addr, io.a_wdata)
mem.read(io.a_addr, io.a_en)
```

用一个 SyncReadMem 模拟两个端口的访问（Chisel 在综合时会映射到实际的双端口 SRAM macro）。

### 8.5 在 DataModuleTemplate 中

`utility/src/main/scala/utility/DataModuleTemplate.scala` 中的 `Folded1WDataModuleTemplate` 使用 `Mem`（非 SyncReadMem）存储折叠后的数据行：

```scala
val data = Mem(nRows, Vec(width, gen))
// 读取
io.rdata(i) := data(addr)(idx)
// 写入带 mask
data.write(waddr, wdata, wmask.asBools)
```

这是异步读取的实现，与 SyncReadMem 的同步读取形成对比。

---

## 9. CanHaveErrors trait

### 9.1 定义

`CanHaveErrors` trait 在 `utility/src/main/scala/utility/ECC.scala` 和 `rocket-chip/src/main/scala/util/ECC.scala` 中均有定义（内容相同）：

```scala
trait CanHaveErrors extends Bundle {
  val correctable: Option[ValidIO[UInt]]
  val uncorrectable: Option[ValidIO[UInt]]
}
```

### 9.2 设计意图

这是一个**可选的错误报告接口 trait**，混入到 Bundle 中用于标准化 ECC 错误的上报方式：

- `correctable: Option[ValidIO[UInt]]` - 可纠正错误的带有效位的地址/数据
- `uncorrectable: Option[ValidIO[UInt]]` - 不可纠正错误的带有效位的地址/数据

使用 `Option` 而非固定存在，使得：
- 不需要 ECC 的模块可以简单地不实现这些字段
- 需要 ECC 的模块可以选择只实现 correctable 或 uncorrectable 中的一个
- 提供统一的错误上报接口，便于上层模块（如中断控制器、错误记录器）以一致的方式处理所有来源的 ECC 错误

### 9.3 在 XiangShan 中的应用场景

在 `rocket-chip` 的 DCache、ICache、PTW 等模块中，`CanHaveErrors` 被广泛用于定义 cache 错误输出端口。在 XSCache 的 `L2CacheErrorInfo` 中，虽然没有直接混入 `CanHaveErrors`，但其 `valid` + `address` 的结构本质上是相同概念的具体实现。

---

## 10. ECCParams 配置

```scala
case class ECCParams(
  bytes: Int = 1,
  code: Code = new IdentityCode,
  notifyErrors: Boolean = false
)
```

- `bytes`: 每次编码的字节数
- `code`: 使用的 ECC 编码方案（默认 IdentityCode，即无 ECC）
- `notifyErrors`: 是否通知错误（控制是否启用错误上报逻辑）

在 `DescribedSRAM`（`rocket-chip/src/main/scala/util/DescribedSRAM.scala`）中，`ECCParams` 与 SRAM 宏的生成参数关联，用于在 ASIC 流程中生成带有 ECC 的 SRAM。

---

## 11. DataModuleTemplate 体系

除 SRAMTemplate 外，XiangShan 还有一套基于寄存器（Reg）而非 SyncReadMem 的数据模块体系，位于 `utility/src/main/scala/utility/DataModuleTemplate.scala`：

### 11.1 RawDataModuleTemplate

使用 `Reg(Vec(numEntries, gen))` 实现，支持同步/异步读取、多读写端口、optWrite（写后读 bypass）。这是最灵活的基类。

### 11.2 SyncDataModuleTemplate

将大条目数按 bank 分割（每 bank 最多 64 条目），通过 `bankOffset` 和 `bankIndex` 进行 bank 选择。每个 bank 内部使用 `DataModuleTemplate`（纯 combinational bypass），输入端口添加一拍延迟实现同步读取。

### 11.3 Folded1WDataModuleTemplate

单写端口的折叠数据模块，使用 `Mem(nRows, Vec(width, gen))` 存储。支持异步读取和 reset 逻辑。用于需要大容量但写端口受限的场景。

---

## 12. 源文件位置索引

### utility/ 目录（核心 SRAM 基础设施）

| 文件路径 | 核心内容 |
|----------|----------|
| `utility/src/main/scala/utility/sram/SRAMTemplate.scala` | SRAMTemplate、SplittedSRAMTemplate、FoldedSRAMTemplate、SRAMTemplateWithArbiter、SRAMConflictBehavior |
| `utility/src/main/scala/utility/sram/SramProto.scala` | SramArray 抽象基类、SramArray1P、SramArray2P、SramProto 工厂 |
| `utility/src/main/scala/utility/sram/SramHelper.scala` | SramInfo、SramHelper（genRam、SramBroadcastBundle、SRAM 缩写映射） |
| `utility/src/main/scala/utility/ECC.scala` | IdentityCode、ParityCode、SECCode、SECDEDCode、ErrGen、CanHaveErrors、ECCParams、Code.fromString |
| `utility/src/main/scala/utility/DataModuleTemplate.scala` | RawDataModuleTemplate、SyncDataModuleTemplate、DataModuleTemplate、Folded1WDataModuleTemplate |
| `utility/src/main/scala/utility/package.scala` | 向后兼容的类型别名 |
| `utility/src/main/scala/utility/Hold.scala` | ReadAndHold（SyncReadMem + HoldUnless） |
| `utility/src/test/scala/utility/TestSRAMTemplate.scala` | SRAMTemplate 全面的单元测试（所有 conflictBehavior 组合） |

### XSCache/ 目录（L2 Cache 专用 SRAM）

| 文件路径 | 核心内容 |
|----------|----------|
| `XSCache/src/main/scala/coupledL2/utils/SRAMTemplate.scala` | L2 专用简化版 SRAMTemplate（支持 clkDivBy2、readMCP2） |
| `XSCache/src/main/scala/coupledL2/utils/SplittedSRAM.scala` | L2 专用 SplittedSRAM |
| `XSCache/src/main/scala/coupledL2/utils/BankedSRAM.scala` | BankedSRAM（bank 级并行） |
| `XSCache/src/main/scala/coupledL2/utils/SRAMWrapper.scala` | SRAM 包装器 |
| `XSCache/src/main/scala/coupledL2/utils/Queue_SRAM.scala` | 基于 SRAM 的 Queue |
| `XSCache/src/main/scala/coupledL2/DataStorage.scala` | L2 数据存储 ECC 编解码应用 |
| `XSCache/src/main/scala/coupledL2/Directory.scala` | L2 目录存储 ECC 编解码应用 |
| `XSCache/src/main/scala/coupledL2/L2Param.scala` | ECC 配置参数（tagECC、dataECC、enablePoison） |
| `XSCache/src/main/scala/coupledL2/Common.scala` | L2CacheErrorInfo 定义 |
| `XSCache/src/main/scala/coupledL2/TXDAT.scala` | 数据发送端 ECC 编码与 poison |
| `XSCache/src/main/scala/coupledL2/RXDAT.scala` | 数据接收端 ECC 校验与 poison |
| `XSCache/src/main/scala/coupledL2/MMIOBridge.scala` | MMIO 桥接 ECC 校验 |

### rocket-chip/ 目录

| 文件路径 | 核心内容 |
|----------|----------|
| `rocket-chip/src/main/scala/util/ECC.scala` | ECC 完整实现（含 eccIndices 方法和 ECCTest） |
| `rocket-chip/src/main/scala/util/DescribedSRAM.scala` | 带描述的 SRAM（含 ECCParams） |

### ChiselIOPMP/ 目录

| 文件路径 | 核心内容 |
|----------|----------|
| `ChiselIOPMP/src/main/scala/IopmpChecker.scala` | TrueDualPortSRAM 实现、SrcmdTable/MdcfgTable/EntryTable |

---

## 13. 设计总结

XiangShan 的 SRAM Primitives & ECC 子系统体现了以下设计原则：

1. **层次化抽象**：从最底层的 `SramArray1P/2P`（封装 SyncReadMem）到 `SramProto`（工厂 + Definition 复用）到 `SRAMTemplate`（完整功能封装），再到 `SplittedSRAMTemplate`/`FoldedSRAMTemplate`（大型 SRAM 的分割/折叠），形成了清晰的四层抽象。

2. **可配置性优先**：`SRAMTemplate` 的 20+ 个参数覆盖了单/双端口、reset、hold、bitmask、conflict handling、clock gate、MBIST 等几乎所有 SRAM 配置需求。

3. **ECC 可插拔**：通过 `Code.fromString` 工厂和 `ECCParams` 配置，ECC 可以在不影响上层代码的情况下从 `IdentityCode` 切换到 `SECDEDCode`。

4. **Poison 机制的优雅实现**：通过在编码阶段注入 poison 信号，实现了一种不修改数据本身就能标记数据不可靠的机制，这对 TileLink 等总线协议中的 poison 语义至关重要。

5. **FPGA 与 ASIC 双平台支持**：utility 中的 SRAMTemplate 面向 ASIC（通过 SramHelper 生成 foundry SRAM），而 ChiselIOPMP 中的 TrueDualPortSRAM 面向 FPGA（使用 Chisel 原语直接推断 BRAM）。

6. **MBIST 深度集成**：SRAM 基础设施内置了完整的 MBIST 支持，包括节点管理（`SramInfo`）、广播信号分发（`SramBroadcastBundle`）、clock gate 控制等，为 DFT（Design for Test）提供了坚实基础。
