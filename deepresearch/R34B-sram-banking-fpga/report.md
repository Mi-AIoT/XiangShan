# R34B - SRAM Banking & FPGA Mapping 深度研究报告

## 1. 概述

XiangShan 处理器的 SRAM 子系统是整个芯片设计中最为关键的存储层次之一。与面向 ASIC 流片的定制 SRAM compiler 生成的 macro 不同，XiangShan 在 RTL 层面构建了一套完整的 SRAM 抽象框架，涵盖了 banking（bank 划分）、splitting（多维度切分）、folding（维度折叠）、clock gating（时钟门控）、MBIST（内建自测试）支持，以及面向 FPGA 平台的同步读写内存映射。本报告深入分析这些机制的实现细节、设计理念与工程约束。

---

## 2. SplittedSRAMTemplate — setSplit x waySplit x dataSplit 三维切分

### 2.1 设计动机

大型 SRAM macro（如 L2 cache 的 data array 或 tag array）在实际 ASIC 实现中往往无法以单一 macro 的形式满足时序、面积和功耗约束。SplittedSRAMTemplate 的核心思想是将一个大 SRAM 从三个正交维度进行切分：

- **setSplit（set 维度切分）**：将 depth（set 数量）拆分为多个 bank，使用 set index 的低位作为 bank selector，同一时刻只有一个 bank 被激活。
- **waySplit（way 维度切分）**：将 way 数量拆分为多个组，每组的 way 可以独立并行访问。
- **dataSplit（data width 维度切分）**：将数据位宽拆分为多段，每段独立存储和读出，最终通过 Cat 拼接恢复原始宽度。

### 2.2 实现结构

源文件位于 `utility/src/main/scala/utility/sram/SRAMTemplate.scala`（第 507-631 行）。

```scala
class SplittedSRAMTemplate[T <: Data](
  gen: T, set: Int, way: Int = 1,
  setSplit: Int = 1, waySplit: Int = 1, dataSplit: Int = 1,
  ...
)
```

其内部实例化了一个三维数组：

```scala
val array = Seq.fill(setSplit)(Seq.fill(waySplit)(Seq.fill(dataSplit)(
  Module(new SRAMTemplate(UInt(innerWidth.W), innerSets, innerWays, ...))
)))
```

总 SRAM 数量为 `setSplit * waySplit * dataSplit`，每个子 SRAM 的参数为：
- `innerSets = set / setSplit`
- `innerWays = way / waySplit`
- `innerWidth = gen.getWidth / dataSplit`

### 2.3 地址分解与 Bank 选择

对于 set 维度，set index 的低位 `bankBits = log2Ceil(setSplit)` 位用作 bank selector，高位 `innerSetBits` 位用作子 SRAM 内部的 set 地址：

```scala
val r_bankSel = io.r.req.bits.setIdx(bankBits - 1, 0)
val r_setIdx  = io.r.req.bits.setIdx.head(innerSetBits)
```

读响应通过 `Mux1H` 选择对应 bank 的数据。读写 ready 信号也只取被选中 bank 的 ready 状态。

### 2.4 读数据重组

读响应的重组逻辑按以下步骤执行：
1. 对同一行（同一 setSplit bank）内的 dataSplit 段进行 Cat 拼接，恢复完整数据宽度。
2. 对同一 waySplit 组内的所有 way 进行收集。
3. 通过 flatMap 将所有 waySplit 组展开为一个完整的 way 向量。
4. 使用 `Mux1H(ren_vec, allData)` 根据读 bank 选择输出。

源码中的 ASCII 图示（以 setSplit=2, waySplit=2, dataSplit=4 为例）清晰展示了这一三维结构：

```
                               / way 0  -- [data 3] | [data 2] | [data 1] | [data 0]
  set[0] == 0.U -> waySplit 0 |- way 1  -- [data 3] | [data 2] | [data 1] | [data 0]
                   waySplit 1 |- way 0  -- [data 3] | [data 2] | [data 1] | [data 0]
                               \ way 1  -- [data 3] | [data 2] | [data 1] | [data 0]
  ... (set bank 1 重复相同结构)
```

### 2.5 约束条件

- `set % setSplit == 0`：set 数量必须被 setSplit 整除。
- `way % waySplit == 0`：way 数量必须被 waySplit 整除。
- `gen.getWidth % dataSplit == 0`：数据位宽必须被 dataSplit 整除。

### 2.6 与 XSCache 中 SplittedSRAM 的关系

在 XSCache（L2 cache）子项目中，存在一个独立但逻辑相似的 `SplittedSRAM` 类，位于 `XSCache/src/main/scala/coupledL2/utils/SplittedSRAM.scala`。其参数略有差异（例如默认 `singlePort = true`，支持 `readMCP2` 参数控制 Multi-Cycle Path 2），但三维切分的核心逻辑完全一致。该类被 `GatedSplittedSRAM` 继承，并在 L2 Directory 的 tagArray/metaArray 中被直接使用。

---

## 3. FoldedSRAMTemplate — 维度折叠

### 3.1 设计思想

`FoldedSRAMTemplate` 位于 `utility/src/main/scala/utility/sram/SRAMTemplate.scala`（第 633-707 行）。其核心创新在于"折叠"：将 way 和 set 两个维度进行交叉映射，使得多个 way 的数据存储在同一个 set 行中，从而将 SRAM 的深度降低为 `nRows = set / width`，同时将 way 数量扩展为 `width * way`。

```scala
class FoldedSRAMTemplate[T <: Data](
  gen: T, set: Int, width: Int = 4, way: Int = 1,
  setSplit: Int = 1, waySplit: Int = 1, dataSplit: Int = 1, ...
)
```

### 3.2 地址映射

set index 被分为两部分：
- **高位**（`log2Ceil(width)` 位以上）：作为 SRAM 的 row 地址。
- **低位**（`log2Ceil(width)` 位）：用于在同一 row 内的 `width` 个折叠位置中选择目标。

```scala
val nRows = set / width
val raddr = io.r.req.bits.setIdx >> log2Ceil(width)
val ridx  = io.r.req.bits.setIdx(log2Ceil(width)-1, 0)
```

### 3.3 读写路径

**读路径**：内部 SRAM 以 `set=nRows, way=width*way` 的配置实例化了一个 `SplittedSRAMTemplate`。读出后，按 way 进行过滤，使用 `Mux1H(UIntToOH(realRidx, width), wayData)` 从折叠的 `width` 个位置中选择正确的数据。

**写路径**：写数据被复制 `width` 份后拼接为 `Vec(width*way, UInt)`，同时生成 waymask：只有对应 `widthIdx` 的位置才真正写入。

```scala
val wmask = VecInit(Seq.tabulate(width*way)(n =>
  (n / way).U === widthIdx && io.w.req.bits.waymask.get(n % way)
)).asUInt
```

### 3.4 应用场景

FoldedSRAMTemplate 在前端分支预测模块中被广泛使用：
- **TageTable**（`src/main/scala/xiangshan/frontend/bpu/tage/TageTable.scala`）：TAGE 预测器的表项存储。
- **IttageTable**（`src/main/scala/xiangshan/frontend/bpu/ittage/IttageTable.scala`）：间接跳转预测表。
- **MicroTageTable**（`src/main/scala/xiangshan/frontend/bpu/utage/MicroTageTable.scala`）：micro-TAGE 的表项。

这些场景的特点是 way 数较多但每个 way 的数据较窄，folded 方式可以减少 SRAM 实例数量，同时保持合理的深度。

---

## 4. GatedSplittedSRAM — 带 Clock Gating 的切分 SRAM

### 4.1 设计背景

`GatedSplittedSRAM` 位于 `XSCache/src/main/scala/coupledL2/utils/GatedSplittedSRAM.scala`，继承自 L2 cache 中的 `SplittedSRAM`。其关键增强是为每个被切分的子 SRAM 添加独立的 clock gating，这是 DFT（Design for Test）的需求——MBIST 需要单独访问每个子 SRAM，而 clock gate 的分裂使得这种独立访问成为可能。

```scala
class GatedSplittedSRAM[T <: Data](
  gen: T, set: Int, way: Int,
  setSplit: Int = 1, waySplit: Int = 1, dataSplit: Int = 1,
  ...,
  withClockGate: Boolean = true,
) extends SplittedSRAM[T](..., extClockGate = withClockGate, ...)
```

### 4.2 Clock Gating 实现

对于 `hasMbist = true` 的情况，每个子 SRAM 实例化一个 `MbistClockGateCell`：

```scala
if (withClockGate) {
  if (hasMbist) {
    array.map(_.map(_.map(a => {
      val cg = Module(new MbistClockGateCell(extraHold))
      cg.E := io_en
      cg.dft.fromBroadcast(a.io.broadcast.getOrElse(...))
      cg.mbist.req := a.io.mbistCgCtl.map(_.en).getOrElse(false.B)
      cg.mbist.writeen := a.io.mbistCgCtl.map(_.wckEn).getOrElse(false.B)
      cg.mbist.readen := a.io.mbistCgCtl.map(_.rckEn).getOrElse(false.B)
      a.io.mbistCgCtl.foreach(_.wclk := cg.out_clock)
      a.io.mbistCgCtl.foreach(_.rclk := cg.out_clock)
      a.clock := clock
    })))
  } else {
    array.map(_.map(_.map(_.clock := ClockGate(false.B, io_en, clock))))
  }
}
```

- **MBIST 模式**：clock gate 受 MBIST 控制信号（req/readen/writeen）驱动，MBIST 引擎可以独立控制每个 SRAM 的时钟。
- **功能模式**：统一使用 `io_en` 信号进行门控，该信号来自 L2 DataStorage 的实际读写有效信号。

### 4.3 io_en 信号的含义

`io_en` 是实际的读/写 valid 信号（持续一个周期），用于生成每个 SRAM 的 gated clock。源码注释指出：当前所有子 SRAM 使用统一的 `io_en` 进行门控，理论上可以根据 bank 选择进一步细分以降低功耗，但由于 L2 DataStorage 当前对所有子 SRAM 同时读写，统一信号更为简洁。

### 4.4 在 L2 DataStorage 中的应用

`DataStorage` 位于 `XSCache/src/main/scala/coupledL2/DataStorage.scala`，是 `GatedSplittedSRAM` 的主要使用者：

```scala
val array = Module(new GatedSplittedSRAM(
  gen = new DSECCBankBlock,
  set = blocks,
  way = 1,
  dataSplit = dataSRAMSplit,
  singlePort = true,
  readMCP2 = true,
  hasMbist = ...,
  extraHold = true,
  withClockGate = true
))
```

关键特征：
- 使用 `readMCP2 = true`：读操作为 Multi-Cycle Path 2，数据在采样时钟沿后 2 个周期才有效。
- 使用 `singlePort = true`：单端口 SRAM，读写不能同时进行。
- 使用 `extraHold = true`：输入信号需要额外保持一个周期。
- 使用 `dataSplit` 进行数据宽度切分，以降低单个 SRAM 的位宽。

DataStorage 对输入信号的保持有严格要求，通过 assertion 验证：

```scala
assert(!io.en || !RegNext(io.en, false.B),
  "Continuous SRAM req prohibited under MCP2!")
assert(!(RegNext(io.en) && (io.req.asUInt =/= RegNext(io.req.asUInt))),
  s"DataStorage req fails to hold for 2 cycles!")
assert(!(RegNext(io.en && io.req.bits.wen) && (io.wdata.asUInt =/= RegNext(io.wdata.asUInt))),
  s"DataStorage wdata fails to hold for 2 cycles!")
```

---

## 5. L2 BankedSRAM 与 SRAMWrapper

### 5.1 BankedSRAM

`BankedSRAM` 位于 `XSCache/src/main/scala/coupledL2/utils/BankedSRAM.scala`，实现了一种简洁的 bank 划分方案：

```scala
class BankedSRAM[T <: Data](
  gen: T, sets: Int, ways: Int, n: Int = 1,
  shouldReset: Boolean = false, holdRead: Boolean = false,
  singlePort: Boolean = false, bypassWrite: Boolean = false,
  clkDivBy2: Boolean = false, readMCP2: Boolean = false
)
```

其设计特点：
- 使用 set index 的低位 `bankBits = log2Ceil(n)` 选择 bank。
- 每个 bank 是一个独立的 `SRAMTemplate` 实例，支持 `clkDivBy2` 和 `readMCP2`。
- 所有 bank 统一使用 `singlePort = true`（虽然参数允许 `false`，但内部实例化时硬编码为 `true`）。
- 通过 `Mux1H` 根据 bank 选择读响应数据。
- 读 ready 信号会考虑当前 bank 是否有写请求：`!s.io.w.req.valid`。
- assert 确保同一时刻只有一个 bank 被读或写。

### 5.2 SRAMWrapper

`SRAMWrapper` 位于 `XSCache/src/main/scala/coupledL2/utils/SRAMWrapper.scala`，提供了另一种 SRAM bank 化封装：

```scala
class SRAMWrapper[T <: Data](
  gen: T, set: Int, n: Int = 1,
  clkDivBy2: Boolean = false
)
```

与 BankedSRAM 的区别：
- SRAMWrapper 更加轻量，没有 `hasMbist`、`hasSramCtl`、`readMCP2` 等高级参数。
- 每个 bank 的 SRAM 使用 `singlePort = true`，way 数固定为 1。
- 写 ready 信号使用所有 bank 的 ready 进行 `andR`（而 BankedSRAM 只取选中 bank 的 ready）。
- 读响应同样通过 `Mux1H` 选择。

### 5.3 使用场景对比

| 特性 | BankedSRAM | SRAMWrapper |
|------|-----------|-------------|
| MBIST 支持 | 通过 SRAMTemplate 间接支持 | 不支持 |
| readMCP2 | 支持 | 不支持 |
| clkDivBy2 | 支持 | 支持 |
| 单端口 | 强制 true | 强制 true |
| ready 逻辑 | 仅选中 bank 的 ready | 所有 bank AND |
| 适用场景 | 需要多路并行 bank 访问 | 简单 bank 划分 |

---

## 6. SRAMTemplate — 核心 SRAM 基础模块

### 6.1 架构概览

`SRAMTemplate` 是整个 SRAM 子系统的基础构建块，位于 `utility/src/main/scala/utility/sram/SRAMTemplate.scala`（第 203-500 行）。它封装了底层 `SramArray`（由 `SramProto` 创建的 `SyncReadMem`），提供了完整的读写接口、时钟门控、MBIST 集成、冲突处理和复位逻辑。

### 6.2 关键参数

| 参数 | 含义 |
|------|------|
| `gen` | 数据类型 |
| `set` | depth（行数） |
| `way` | way 数 |
| `singlePort` | 单端口模式（读写互斥） |
| `shouldReset` | 上电后清零 |
| `holdRead` | 读数据保持（HoldUnless） |
| `bypassWrite` / `conflictBehavior` | 读写冲突处理策略 |
| `useBitmask` | 逐 bit 写掩码模式 |
| `withClockGate` | 读写端口时钟门控 |
| `hasMbist` | MBIST 支持 |
| `latency` | 读延迟（multicycle path 设置） |
| `extraHold` | 额外一拍输入保持 |
| `extClockGate` | 外部时钟门控（用于 MBIST） |
| `hasSramCtl` | SRAM 控制信号支持 |

### 6.3 冲突处理策略

SRAMTemplate 实现了丰富的双端口读写冲突处理策略（`SRAMConflictBehavior` 枚举）：

- **CorruptRead**：允许冲突，所有读数据损坏（填充随机数据）。
- **CorruptReadWay**：允许冲突，被写 way 的读数据损坏（默认值）。
- **BypassWrite**：允许冲突，将写数据旁路到读数据。
- **AssertionFail**：不允许冲突，触发断言失败。
- **BufferWrite**：不允许冲突，将写数据缓存到单条目 buffer，下周期写入；同时 stall 读和写。
- **BufferWriteLossy**：类似 BufferWrite，但允许 buffer 内容在第二次冲突时丢失，不 stall 外部请求。
- **BufferWriteLossyFast**：BufferWriteLossy 的时序优化版本。
- **StallWrite**：通过 deassert 写 ready 来避免冲突。
- **StallRead**：通过 deassert 读 ready 来避免冲突。

### 6.4 SramProto 底层实现

`SramProto`（`utility/src/main/scala/utility/sram/SramProto.scala`）负责创建实际的 SRAM 实例。它使用 Chisel 的 `SyncReadMem` 作为底层存储原语，并通过 `Definition` / `Instance` 的层级实例化机制实现复用。

- **SramArray1P**：单端口 SRAM，使用 `readWrite` 操作（读写复用同一端口，通过 `wmode` 区分）。
- **SramArray2P**：双端口 SRAM，分离读写端口，写优先策略（`SyncReadMem.WriteFirst`）。

SRAM 命名规则为：`sram_array_{1|2}p{depth}x{width}m{maskWidth}s{setup}h{hold}l{latency}{mbist}_{suffix}`

`SramProto` 维护一个 `defMap` 缓存相同名称的 SRAM Definition，确保参数完全相同的 SRAM 实例复用同一个 Definition，减少 Chisel 编译开销。

### 6.5 SramInfo 与 MBIST Node 映射

`SramInfo`（`utility/src/main/scala/utility/sram/SramHelper.scala`，第 30-77 行）负责计算 MBIST 节点参数：

- **Nto1 模式**：当数据宽度 `ew > maxMbistDataWidth` 时，一个 way 被拆分为多个 MBIST node（Nto1 = N 个数据宽度对应 1 个 MBIST 节点）。
- **1toN 模式**：当数据宽度较窄时，多个 way 合并到一个 MBIST node（1toN = 1 个 MBIST 节点对应 N 个 way）。

同时提供 `mbistMaskConverse` 和 `funcMaskConverse` 方法在 MBIST 掩码和功能掩码之间进行转换。

### 6.6 SRAM 名称缩写映射

`SramHelper` 维护了一套模块名到缩写的映射表（`shortMap`），用于生成简短且有意义的 SRAM 后缀：

| 模块路径 | 缩写 |
|---------|------|
| coupledL2 / DataStorage | l2_dat |
| tagArray | tag |
| metaArray | meta |
| icache / SRAMTemplateWithFixedWidth | icsh_dat |
| TageTable | bp_tage |
| FTB | bp_ftb |
| DataSRAMBank | dcsh_dat |
| PtwCache | ptw |
| prefetch / pht_ram | pftch_pht |

---

## 7. readmemh 工具集

### 7.1 工具概览

`tools/readmemh/` 目录包含三个 C 语言工具和一个 Makefile，用于处理 Verilog `$readmemh` 格式的内存初始化文件。

### 7.2 split-readmemh.c

**功能**：将一个 128-bit（16 字节）宽度的 readmemh 文件拆分为 4 个 32-bit 宽度的文件。

**工作原理**：
1. 读取输入文件，逐行解析。
2. 遇到 `@` 地址行时，在所有 4 个输出文件中写入 `@{addr/4}`（地址除以 4，因为 4 个 32-bit 文件拼接为 128-bit）。
3. 遇到数据行时，解析 16 个字节（128-bit），按 4 个 32-bit 组分别写入对应的输出文件。
4. 输出文件命名为 `{input}_0`, `{input}_1`, `{input}_2`, `{input}_3`。

这对应于 `dataSplit = 4` 的 SRAM 切分：一个 128-bit 宽的 SRAM 被拆分为 4 个 32-bit SRAM，每个需要独立的初始化文件。

### 7.3 gen-treadle-readmemh.c

**功能**：将二进制文件转换为 Treadle 模拟器使用的 readmemh 格式。

**工作原理**：
1. 预先输出 `0x100000`（约 1M）行全零数据，作为地址空间的预填充。
2. 从二进制输入文件逐字节读取，每字节转换为 2 位十六进制输出。
3. 格式为每行一个字节的十六进制表示（如 `0f`）。

这种工具主要用于生成 Treadle（Chisel 内建模拟器）所需的特殊内存格式。

### 7.4 groupby-4byte.c

**功能**：将字节粒度的 readmemh 文件重组为 4 字节（32-bit）粒度。

**工作原理**：
1. 解析输入文件中的 `@` 地址和字节数据。
2. 将字节地址转换为字地址（`addr / 4`）。
3. 每 4 个字节合并为一行 32-bit 十六进制输出。
4. 支持部分行（4、8、12 或 16 个十六进制数字）。

### 7.5 Makefile

```makefile
build/verilator-readmemh: split-readmemh.c
	mkdir -p $(@D)
	gcc -O2 -Wall -Werror -o $@ $<
```

Makefile 仅构建 `split-readmemh` 工具，输出为 `build/verilator-readmemh`，用于 Verilator 仿真环境。`gen-treadle-readmemh` 和 `groupby-4byte` 需要手动编译。

---

## 8. SRAM Size 分析工具

### 8.1 sram_size_collect.py

`scripts/sram_size_collect.py` 是一个 SRAM 面积分析脚本，用于从 Verilog 后端生成文件中提取 SRAM macro 信息。

**工作原理**：
1. 扫描指定目录中所有以 `array_` 开头、以 `_ext.v` 结尾的文件。
2. 使用正则表达式从文件注释中提取信息：`array_{id}_ext depth:{depth} width:{width} masked:{masked} maskGran:{gran} maskSeg:{seg}`。
3. 将提取的信息（ID, depth, width, maskGranularity, maskSegments, masked）按 depth/width/mask 参数排序（忽略 ID）。
4. 输出到以 `{目录名}_{时间戳}.txt` 命名的文件。

**输出格式**：
```
Y:{depth} Z:{width} M:{maskGran} N:{maskSeg} T:{masked}
```

**应用场景**：用于 ASIC 综合和后端流程中，分析 SRAM compiler 生成的 macro 分布，帮助评估面积、确定 SRAM bank 数量和配置。此脚本在项目的 Makefile 中未发现直接引用，通常在后端流程中手动调用。

---

## 9. FPGA vs ASIC SRAM 映射

### 9.1 FPGAPlatform 参数

`FPGAPlatform` 定义在 `src/main/scala/xiangshan/Parameters.scala`（第 519-521 行）的 `DebugOptions` 中：

```scala
case class DebugOptions(
  FPGAPlatform: Boolean = false,
  DumpCSR: Boolean = false,
  ResetGen: Boolean = false,
  EnableDifftest: Boolean = false,
  ...
)
```

通过命令行参数 `--fpga` 在 `ArgParser` 中设置为 `true`。

### 9.2 FPGA 模式下的 SRAM 映射策略

在 FPGA 平台上，SRAM 的映射策略与 ASIC 有本质区别：

**ASIC 模式**：
- SRAM 通过 `SramProto` 实例化为 `SyncReadMem`，最终由综合工具映射为 SRAM compiler 生成的 macro（如 `sram_array_1p256x128m128s1h1l1_...`）。
- 支持逐 bit 写掩码（bitmask）、单端口/双端口、MBIST 测试接口等。
- 在流片前，`sram_size_collect.py` 从后端生成的 Verilog 中提取 SRAM macro 信息进行面积分析。

**FPGA 模式**：
- FPGA 平台使用 BRAM（Block RAM）或 URAM（UltraRAM）来实现 SRAM，这些是 FPGA 芯片上固有的存储资源。
- `FPGAPlatform` 标志主要影响**调试和性能监控**相关逻辑的使能，而非直接改变 SRAM 实现方式：
  - `!debugOpts.FPGAPlatform` 控制 `EnablePerfDebug`、`EnableDebug`、`TLLogger`、`BusPerfMonitor` 等模块的实例化。
  - FPGA 模式下禁用 difftest request keys、performance database、transaction log 等。
- SRAM 本身（`SRAMTemplate` 及其子类）的实现不随 `FPGAPlatform` 变化——`SyncReadMem` 在 FPGA 综合时由 Vivado/Quartus 自动映射到 BRAM。

### 9.3 FPGA 特有的 SRAM 约束

虽然 SRAM RTL 代码本身不做平台区分，但 FPGA 实现面临以下额外约束：

1. **BRAM 宽度限制**：FPGA BRAM（如 Xilinx BRAM36）通常最大 36Kb（如 1024x36 或 4096x9），超出宽度的 SRAM 需要多个 BRAM 并联。这与 `dataSplit` 切分天然契合。
2. **单端口限制**：许多 FPGA BRAM 只支持真正的单端口或伪双端口模式，与 `singlePort = true` 模式对应。
3. **时序差异**：FPGA BRAM 的读延迟通常为 1-2 个周期，需要考虑 `latency` 和 `holdRead` 参数的适配。
4. **MBIST 不适用**：FPGA 不需要 MBIST（由 JTAG 和 ILA 替代），因此 `hasMbist = false`，整个 MBIST 链路被编译排除。
5. **时钟门控**：FPGA BRAM 本身支持使能端口（EN），但 ASIC 风格的 ClockGate cell 在 FPGA 中综合为 LUT-based MUX，可能增加逻辑延迟。

### 9.4 FPGA 模式在 LoadPipe 中的特殊处理

在 `src/main/scala/xiangshan/cache/dcache/loadpipe/LoadPipe.scala` 中：

```scala
if(dwpuParam.enCfPred || !env.FPGAPlatform) { ... }
```

当 FPGA 模式下，某些 DWPU（Data Way Prediction Unit）的预测逻辑被条件编译排除，这是为了简化 FPGA 实现中不需要的复杂预测路径。

---

## 10. MBIST 与 SramBroadcastBundle

### 10.1 SramBroadcastBundle

`SramBroadcastBundle` 定义在 `utility/src/main/scala/utility/sram/SramProto.scala`（第 31-40 行）：

```scala
class SramBroadcastBundle extends Bundle {
  val ram_hold     = Input(Bool())     // SRAM 保持信号，阻止写操作
  val ram_bypass   = Input(Bool())     // 读写旁路控制
  val ram_bp_clken = Input(Bool())     // 旁路时钟使能
  val ram_aux_clk  = Input(Bool())     // 辅助时钟输入
  val ram_aux_ckbp = Input(Bool())     // 辅助时钟旁路选择（ClockMux sel）
  val ram_mcp_hold = Input(Bool())     // Multi-Cycle Path 保持信号
  val ram_ctl      = Input(UInt(64.W)) // SRAM 控制总线（64-bit）
  val cgen         = Input(Bool())     // 时钟生成使能（CG test enable）
}
```

这是一个全局广播信号，从顶层（通过 `genBroadCastBundleTop()`）通过 `BoringUtils.bore()` 跨层级连接到每个 SRAM 实例。它携带 DFT（Design for Test）和 MBIST 测试所需的全局控制信号。

### 10.2 广播机制

`SramHelper` 维护一个 `broadCastBdQueue` 队列：

```scala
val broadCastBdQueue = new mutable.Queue[SramBroadcastBundle]
```

每个启用了 MBIST 的 SRAM 实例在 `genRam()` 创建时将其 `broadcast` 信号入队：

```scala
SramHelper.broadCastBdQueue.enqueue(broadcast.get)
```

顶层通过 `genBroadCastBundleTop()` 一次性将所有队列中的信号通过 `BoringUtils.bore()` 连接到统一的广播源，然后清空队列。

### 10.3 MbistClockGateCell

`MbistClockGateCell` 位于 `utility/src/main/scala/utility/mbist/MbistClockGateCell.scala`，实现了 MBIST 场景下的时钟门控：

```scala
class MbistClockGateCell(mcpCtl: Boolean) extends Module {
  val mbist = IO(new Bundle {
    val writeen = Input(Bool())
    val readen = Input(Bool())
    val req = Input(Bool())
  })
  val E = IO(Input(Bool()))          // 功能使能
  val dft = IO(new CgDftBundle)      // DFT 控制信号
  val out_clock = IO(Output(Clock())) // 门控后的时钟
}
```

**时钟门控逻辑**：
- 当 `mbist.req = true`（MBIST 模式）时：`CG.E = mbist.readen | mbist.writeen`。
- 当 `mbist.req = false`（功能模式）时：`CG.E = E`（功能使能信号）。
- 当 `mcpCtl = true`（支持 Multi-Cycle Path 控制）时：额外受 `ram_mcp_hold` 信号控制，`CG.E = (mbist.req ? (readen|writeen) : E) && !ram_mcp_hold`。并通过 `ClockMux` 支持在正常时钟和辅助时钟之间切换。

`CgDftBundle` 封装了 `SramBroadcastBundle` 中与时钟门控相关的信号，通过 `fromBroadcast` 方法进行映射：

```scala
def fromBroadcast(brc: SramBroadcastBundle): Unit = {
  ram_aux_clk := brc.ram_aux_clk
  ram_aux_ckbp := brc.ram_aux_ckbp
  ram_mcp_hold := brc.ram_mcp_hold
  cgen := brc.cgen
}
```

### 10.4 MBIST 在层次结构中的传播

`SramBroadcastBundle` 在整个 XiangShan 层次结构中逐层传播：

```
XSNoCTop / Top
  └── XSTile
       └── XSTileWrap
            ├── XSCore
            │    ├── Frontend (icache, BPU SRAMs)
            │    ├── Backend (RAT, PRF SRAMs)
            │    └── MemBlock (dcache SRAMs)
            └── L2Top
                 └── CoupledL2
                      ├── Directory (tagArray, metaArray via SplittedSRAM)
                      └── DataStorage (via GatedSplittedSRAM)
```

每个层级的 `hasDFT` 参数控制是否实例化 `SramBroadcastBundle` IO。当 `hasDFT = false` 时，整个 MBIST/DFT 链路被编译排除，减少了逻辑开销。在顶层，`SramHelper.genBroadCastBundleTop()` 完成所有广播信号的物理连接。

### 10.5 SramMbistIO

每个 `SramArray` 实例在启用 MBIST 时会暴露 `SramMbistIO` 接口：

```scala
class SramMbistIO extends Bundle {
  val dft_ram_bypass = Input(Bool())
  val dft_ram_bp_clken = Input(Bool())
}
```

这两个信号直接来自 `SramBroadcastBundle`，用于控制 SRAM 的 bypass 功能——在测试模式下旁路正常时序路径。

---

## 11. 源文件位置汇总

| 文件 | 路径 | 功能 |
|------|------|------|
| SRAMTemplate.scala | `utility/src/main/scala/utility/sram/SRAMTemplate.scala` | SRAMTemplate, SplittedSRAMTemplate, FoldedSRAMTemplate, SRAMConflictBehavior |
| SramProto.scala | `utility/src/main/scala/utility/sram/SramProto.scala` | SramBroadcastBundle, SramMbistIO, SramArray, SramArray1P, SramArray2P, SramProto |
| SramHelper.scala | `utility/src/main/scala/utility/sram/SramHelper.scala` | SramInfo, SramHelper, SRAM naming/abbreviation |
| MbistClockGateCell.scala | `utility/src/main/scala/utility/mbist/MbistClockGateCell.scala` | MbistClockGateCell, CgDftBundle |
| SplittedSRAM.scala | `XSCache/src/main/scala/coupledL2/utils/SplittedSRAM.scala` | SplittedSRAM (L2 版本，支持 readMCP2) |
| GatedSplittedSRAM.scala | `XSCache/src/main/scala/coupledL2/utils/GatedSplittedSRAM.scala` | GatedSplittedSRAM |
| BankedSRAM.scala | `XSCache/src/main/scala/coupledL2/utils/BankedSRAM.scala` | BankedSRAM |
| SRAMWrapper.scala | `XSCache/src/main/scala/coupledL2/utils/SRAMWrapper.scala` | SRAMWrapper |
| DataStorage.scala | `XSCache/src/main/scala/coupledL2/DataStorage.scala` | L2 DataStorage (GatedSplittedSRAM 的主要使用者) |
| Parameters.scala | `src/main/scala/xiangshan/Parameters.scala` | DebugOptions (FPGAPlatform 定义) |
| ArgParser.scala | `src/main/scala/top/ArgParser.scala` | FPGAPlatform 命令行设置 |
| split-readmemh.c | `tools/readmemh/split-readmemh.c` | 128-bit 到 4x32-bit readmemh 拆分 |
| gen-treadle-readmemh.c | `tools/readmemh/gen-treadle-readmemh.c` | 二进制到 Treadle readmemh 格式转换 |
| groupby-4byte.c | `tools/readmemh/groupby-4byte.c` | 字节粒度到 4-byte 粒度 readmemh 合并 |
| Makefile | `tools/readmemh/Makefile` | readmemh 工具编译构建 |
| sram_size_collect.py | `scripts/sram_size_collect.py` | SRAM macro 面积信息提取与分析 |

---

## 12. 总结

XiangShan 的 SRAM 子系统展示了高性能处理器存储层次设计的工程复杂性：

1. **三维切分**（SplittedSRAMTemplate）将大 SRAM 分解为 set/way/data 三个维度的小 SRAM，是面积、时序和功耗优化的核心手段。setSplit 降低单个 SRAM 的 depth，waySplit 降低单个 SRAM 的 way 数，dataSplit 降低单个 SRAM 的位宽——三者独立可调，灵活应对不同约束。

2. **维度折叠**（FoldedSRAMTemplate）在分支预测等宽表场景下，通过交叉映射将 `set` 维度的部分容量转换为 `way` 维度的存储，减少 SRAM 实例数量。其内部复用 SplittedSRAMTemplate，形成两层抽象。

3. **Clock Gating**（GatedSplittedSRAM）为每个子 SRAM 添加独立门控，是 DFT/MBIST 的前提条件。功能模式下统一使用 io_en 门控，MBIST 模式下由 MbistClockGateCell 独立控制。

4. **Bank 化**（BankedSRAM/SRAMWrapper）支持 L2 cache 的 bank 级并行访问，使用 set index 低位选择 bank，通过 Mux1H 选择读响应。BankedSRAM 功能更完整，SRAMWrapper 更轻量。

5. **readmemh 工具链**提供了从宏观 128-bit 到微观 32-bit 的内存初始化文件转换能力，与 dataSplit 参数直接对应。split-readmemh 处理 dataSplit=4 的场景，groupby-4byte 处理字节到字的重组。

6. **SRAM size 分析**工具从后端 Verilog 文件中提取 SRAM macro 参数（depth/width/mask），支持 ASIC 面积评估和优化迭代。

7. **FPGA 映射**主要通过 `SyncReadMem` 到 BRAM 的自动映射实现，`FPGAPlatform` 标志控制调试/性能逻辑的编译排除，而非改变 SRAM 实现本身。

8. **MBIST/SramBroadcastBundle** 提供了从顶层到最底层 SRAM 的统一测试控制通道：SramBroadcastBundle 通过 BoringUtils 广播全局 DFT 信号，MbistClockGateCell 实现 MBIST 模式下的独立时钟控制，SramInfo 计算 MBIST 节点映射——三者协同构成完整的 DFT 架构。
