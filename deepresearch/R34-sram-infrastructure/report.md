# R34 - SRAM & Memory Infrastructure 深度研究报告

## 1. 概述

XiangShan 处理器作为一款高性能开源 RISC-V 处理器，其 SRAM 与 Memory Infrastructure 层承担了片上存储子系统的核心功能。该基础设施涵盖了从底层 SRAM 宏原型（SRAM Macro Prototype）到上层可配置模板（Configurable Template）的完整层次结构，为处理器的 L1/L2 Cache、分支预测器（Branch Predictor）、MMU、FTQ（Fetch Target Queue）以及后端 Issue Queue 等关键模块提供统一的存储抽象。本报告从十个维度对 XiangShan 的 SRAM 基础设施进行深入分析。

---

## 2. SRAM Template 设计（SRAMTemplate 变体）

XiangShan 拥有两套 SRAMTemplate 体系，分别服务于 XiangShan Core 和 XSCache (L2 Cache) 两个子系统。

### 2.1 Core 侧 SRAMTemplate（utility.sram）

位于 `utility/src/main/scala/utility/sram/SRAMTemplate.scala`，这是 XiangShan 最核心的 SRAM 模板，支持丰富的参数配置：

- **gen: T** - 存储条目数据类型
- **set: Int** - 数组深度（行数）
- **way: Int** - 多 way 支持（默认 1）
- **singlePort: Boolean** - 单端口/真双端口模式切换
- **shouldReset / extraReset** - 支持 reset 后清零
- **holdRead** - 保持读出数据直到下一次读操作
- **conflictBehavior** - 读写冲突策略（多达 10 种策略）
- **useBitmask** - 逐比特掩码支持
- **withClockGate / extClockGate** - 门控时钟支持
- **hasMbist** - MBIST（Memory Built-In Self-Test）接口
- **latency** - 输出延迟配置（setup multicycle）
- **extraHold** - 额外输入保持周期
- **hasSramCtl** - SRAM 控制信号支持

该模板通过 `SramHelper.genRam` 生成底层 SRAM 宏，通过 `SramProto` 进行实例化，底层实际使用 Chisel 的 `SyncReadMem` 生成 Verilog SRAM 宏。

### 2.2 L2 Cache 侧 SRAMTemplate（xscache.coupledL2.utils）

位于 `XSCache/src/main/scala/coupledL2/utils/SRAMTemplate.scala`，这是一个相对简化但功能完整的版本，直接基于 `SyncReadMem` 构建：

- 支持 `shouldReset`、`holdRead`、`singlePort`、`bypassWrite`
- 额外支持 `clkDivBy2`（时钟二分频，用于降低 SRAM 频率）和 `readMCP2`（多周期路径 2）
- 使用 `CustomAnnotations` 进行特殊注解（如 `annotateClkDivBy2`、`annotateSpecialDepth`）
- bypass 逻辑使用 LFSR64 生成随机数据填充

### 2.3 SRAMTemplateWithArbiter

在两套体系中均存在，封装单端口 SRAM 并添加读仲裁器（Arbiter），支持多个读端口共享一个单端口 SRAM，通过 `HoldUnless` 锁存读结果，避免读冲突。

---

## 3. DataModuleTemplate 模式

位于 `utility/src/main/scala/utility/DataModuleTemplate.scala`，DataModuleTemplate 是一套独立于 SRAMTemplate 的轻量级寄存器堆（Register File）模板体系，主要用于对时序要求较高的小容量存储。

### 3.1 RawDataModuleTemplate

最底层的原始数据模块，核心参数包括 `numEntries`、`numRead`、`numWrite`、`isSync`、`optWrite`、`hasRen`。特点：

- 使用 `Reg(Vec(numEntries, gen))` 实现存储（寄存器阵列，非 SRAM）
- 读端口通过 `Mux1H` one-hot 解码，确保 `PopCount(rvec(i)) <= 1`（单读端口独热码约束）
- 写端口支持多写端口，同样要求写地址 one-hot 互斥
- `optWrite` 机制支持写优先（write-first）bypass：对指定写端口延迟一拍后在读路径上进行 bypass

### 3.2 SyncDataModuleTemplate

对 `RawDataModuleTemplate` 的重要包装层：

- 将 `setIdx` 地址自动分 bank（`bankOffset` + `bankIndex`），每 bank 最多 64 条目
- 自动处理读写延迟：读地址延迟一拍（`RegNext`），写使能使用 `GatedValidRegNext` 延迟一拍，写地址使用 `RegEnable`
- 通过 `UIntToOH(bankIndex, numBanks)` 进行 bank 选择输出
- 支持 `concatData` 模式将 gen 拼接为 UInt
- 支持 `perReadPortBypassEnable` 控制逐读端口的 bypass

### 3.3 AsyncRawDataModuleTemplate

异步版本，读地址不经过寄存器，直接组合逻辑输出，适用于异步时钟域或需要零延迟读取的场景。后端 Issue Queue 中的 `DataArray` 即使用此模板。

### 3.4 Folded1WDataModuleTemplate

折叠式单写端口数据模块：

- 将 `numEntries` 按 `width` 折叠为 `nRows = numEntries / width` 行
- 每行存储 `width` 个条目，使用 `Mem(nRows, Vec(width, gen))`（Block RAM 推断）
- 内建 reset 逻辑，通过 `doing_reset` 和 `resetRow` 计数器逐行清零
- 读取时将地址拆分为行地址和列地址（`addr >> log2Ceil(width)` 和 `addr(log2Ceil(width)-1, 0)`）

### 3.5 实际应用

- **FTQ (Fetch Target Queue)**：`EntryQueue`、`CfiQueue`、`SpeculationQueue` 均使用 `SyncDataModuleTemplate`
- **后端 PC 存储**：`CtrlBlock` 中的 `pcMem` 使用 `SyncDataModuleTemplate`
- **GPAMem**：使用 `SyncDataModuleTemplate` 存储页表信息
- **Issue Queue**：`DataArray` 使用 `AsyncRawDataModuleTemplate`
- **Store Set**：`valid_array` 和 `data_array` 使用 `SyncDataModuleTemplate`
- **MaskedDataModule**：内存子系统中的 `MaskedSyncDataModuleTemplate` 和 `MaskedBankedSyncDataModuleTemplate` 在 `DataModuleTemplate` 基础上增加了掩码逻辑

---

## 4. TrueDualPortSRAM（FPGA 专用）

位于 `ChiselIOPMP/src/main/scala/IopmpChecker.scala`（第 144-209 行），`TrueDualPortSRAM` 是为 FPGA 目标（尤其是 IO-PMP 模块）设计的真正双端口 SRAM：

### 4.1 设计特点

- **真正的双端口**：A/B 两个端口均可独立进行读写操作
- **冲突检测**：检测两端口同时写同一地址的情况，通过断言（assertion）报错
- **优先级仲裁**：当两端口同时写不同地址时，Port A 拥有写优先级，Port B 的写入被抑制
- **可配置读延迟**：`readLatency` 参数可设置为 1 或更高，通过流水线寄存器（`readPipe`）实现
- **基于 SyncReadMem**：底层使用 Chisel 的 `SyncReadMem`，综合工具会将其推断为 Block RAM

### 4.2 应用场景

在 IO-PMP (I/O Physical Memory Protection) 模块中广泛使用：

- `srcmd_en`：Source Mode 使能表（31-bit 宽度）
- `mdcfg`：Mode Configuration 寄存器（16-bit 宽度）
- `entry_addr` / `entry_addrh`：PMP Entry 地址表（32-bit 宽度）
- `entry_cfg`：PMP Entry 配置表（11-bit 宽度）

---

## 5. SyncReadMem 使用模式

XiangShan 中 `SyncReadMem` 的使用遵循以下几种模式：

### 5.1 通过 SramProto 封装使用（主流模式）

在 `utility/src/main/scala/utility/sram/SramProto.scala` 中：

- **SramArray1P（单端口）**：
  - 有 mask 时：`SyncReadMem(depth, Vec(maskSegments, UInt((width/maskSegments).W)))`，使用 `readWrite` 接口
  - 无 mask 时：`SyncReadMem(depth, UInt(width.W))`，使用 `readWrite` 接口
  - `readWrite` 是 Chisel 的同步读写方法，根据 `wmode` 控制读/写方向

- **SramArray2P（双端口）**：
  - 有 mask 时：`SyncReadMem(depth, dataType, SyncReadMem.WriteFirst)`，写优先策略
  - 读写分离：`array.write(addr, data, mask, clk)` + `array.read(addr, en, clk)`

### 5.2 直接使用

- **Queue_SRAM**（`XSCache` 子系统）：直接使用 `SyncReadMem(entries, genType)` 构建基于 SRAM 的 Queue，支持 `useSyncReadMem` 参数切换
- **L2 SRAMTemplate**：直接使用 `SyncReadMem(set, Vec(way, wordType))` 构建存储阵列
- **DescribedSRAM**（Rocket Chip）：`SyncReadMem(size, data)` + `suggestName(name)` 生成带描述的 SRAM

### 5.3 Folded1WDataModuleTemplate

使用 `Mem(nRows, Vec(width, gen))` 而非 `SyncReadMem`，更适合推断为 FPGA 中的分布式 RAM 或小容量 Block RAM。

---

## 6. ECC/Parity 保护机制（SECDED）

位于 `utility/src/main/scala/utility/ECC.scala`，XiangShan 实现了完整的 ECC 编解码体系：

### 6.1 Code 层次结构

- **IdentityCode**：无保护，编码宽度不变，仅作透传
- **ParityCode**：奇偶校验，`width = w0 + 1`，可检测但不可纠正错误（`canDetect=true, canCorrect=false`）。编码方式：`Cat(x.xorR ^ poison, x)`
- **SECCode**：单错误纠正码（Hamming Code），`width(k) = k + m + (extra bit if needed)`，可检测可纠正单比特错误。编码采用 systematic 形式（数据位在前，校验位在后）
- **SECDEDCode**：单错误纠正双错误检测码，在 SECCode 基础上增加一位整体奇偶校验，`width(k) = sec.width(k) + 1`。解码时先检查整体奇偶性判断是否可纠正，再通过 SEC 纠正

### 6.2 Poison 机制

所有 Code 类型支持 `poison` 参数。设置 `poison=true` 时，编码结果将被标记为不可纠正错误（uncorrectable），即使原始数据正确。这在 cache line 失效等场景中非常有用。

### 6.3 接口定义

- `CanHaveErrors` trait：定义了 `correctable: Option[ValidIO[UInt]]` 和 `uncorrectable: Option[ValidIO[UInt]]` 接口
- `ECCParams`：配置参数，包含 `bytes`（字节数）、`code`（编码类型）、`notifyErrors`（错误通知）

### 6.4 Code.fromString 工厂方法

支持通过字符串参数化：`"none"/"identity"` -> IdentityCode，`"parity"` -> ParityCode，`"sec"` -> SECCode，`"secded"` -> SECDEDCode。

### 6.5 ErrGen 工具

`ErrGen.apply(width, f)` 生成单比特错误，概率约为 2^-f，用于 ECC 功能验证。

---

## 7. SRAM Banking 策略

XiangShan 在多个层次实现了 SRAM Banking（分体/分 Bank）策略，主要体现在以下几个组件中：

### 7.1 SplittedSRAMTemplate（Core 侧）

位于 `utility/src/main/scala/utility/sram/SRAMTemplate.scala`（第 507-631 行），支持三维分裂：

- **setSplit**：沿 set 维度分裂，使用 `setIdx` 的低位选择 bank，实现不同 bank 独立访问
- **waySplit**：沿 way 维度分裂，不同 way 分组可以并行访问
- **dataSplit**：沿 data 宽度维度分裂，宽数据拆分为多片窄 SRAM

三维分裂形成 `setSplit x waySplit x dataSplit` 个小 SRAM 矩阵。读出数据按 `waySplit -> innerWays -> dataSplit` 层次重组成 `Vec(way, gen)`。

### 7.2 FoldedSRAMTemplate（Core 侧）

将 set 按 `width` 参数折叠：

- `nRows = set / width`，实际 SRAM 深度为 `nRows`，way 数变为 `width * way`
- 写入时将 `width` 个条目打包成一个 SRAM word 写入
- 读取时通过 `ridx` 从 `width` 个条目中选择目标数据
- 底层实际委托给 `SplittedSRAMTemplate`

### 7.3 SplittedSRAM（L2 Cache 侧）

位于 `XSCache/src/main/scala/coupledL2/utils/SplittedSRAM.scala`，与 Core 侧 SplittedSRAMTemplate 逻辑类似，但适配 L2 侧的 SRAMTemplate 接口和参数体系。

### 7.4 BankedSRAM（L2 Cache 侧）

位于 `XSCache/src/main/scala/coupledL2/utils/BankedSRAM.scala`：

- 仅按 set 维度分裂为 n 个 bank
- 使用低位地址选择 bank
- 每个 bank 是独立的 `SRAMTemplate` 实例
- 约束：同一时刻只能读/写一个 bank（`PopCount(ren_vec) <= 1`）
- 写忙时阻塞读（`io.r.req.ready` 考虑写冲突）

### 7.5 SRAMWrapper（L2 Cache 侧）

位于 `XSCache/src/main/scala/coupledL2/utils/SRAMWrapper.scala`：

- 仅按 set 维度分裂，每个 bank 只有 1 个 way
- 支持 `clkDivBy2`
- 与 BankedSRAM 类似但更简单

### 7.6 GatedSplittedSRAM（L2 Cache 侧）

位于 `XSCache/src/main/scala/coupledL2/utils/GatedSplittedSRAM.scala`：

- 继承 `SplittedSRAM`，为每个分裂后的 SRAM 添加独立的 ClockGate
- 用于 DFT（Design for Test）MBIST 场景，MBIST 需要独立访问每个 SRAM
- 支持 `MbistClockGateCell`（含 DFT bypass 控制）
- 通过统一的 `io_en` 信号控制所有 SRAM 的门控时钟

---

## 8. SRAM 冲突处理（ConflictBehavior）

`SRAMTemplate` 中定义了丰富的双端口 SRAM 读写冲突处理策略（`SRAMConflictBehavior` 枚举）：

| 策略名称 | 行为描述 | 宏允许冲突 |
|---------|---------|-----------|
| **CorruptRead** | 所有读数据损坏（随机数据） | 是 |
| **CorruptReadWay** | 被写 way 的读数据损坏，其他 way 正常 | 是 |
| **BypassWrite** | 将写数据 bypass 到读数据 | 是 |
| **AssertionFail** | 冲突时触发断言失败 | 否 |
| **BufferWrite** | 冲突时将写数据暂存到 buffer，下一周期 stall 后写入 | 否 |
| **BufferWriteLossy** | 类似 BufferWrite，但允许丢失（新冲突覆盖旧 buffer） | 否 |
| **BufferWriteLossyFast** | 更激进的 Lossy 版本，改善时序 | 否 |
| **StallWrite** | 冲突时 stall 写操作 | 否 |
| **StallRead** | 冲突时 stall 读操作 | 否 |

默认策略为 `CorruptReadWay`。实现中使用两级流水线检测冲突（S0 检测，S1 处理），通过 `conflictBuffer` 和各种 mux 选择实现不同的冲突处理逻辑。

---

## 9. 内存初始化工具（readmemh 工具）

位于 `tools/readmemh/` 目录，提供了一组 C 语言工具用于处理 `$readmemh` 格式的内存初始化文件。

### 9.1 split-readmemh.c

**功能**：将一个 readmemh 文件拆分为 4 个独立的 bank 文件。

- 输入：一个 readmemh 格式的文件
- 输出：`{input}_0`、`{input}_1`、`{input}_2`、`{input}_3` 四个文件
- 拆分逻辑：将原始 byte 数据按顺序轮询分配到 4 个输出文件中，地址被除以 4
- 每行 16 个 byte 后插入换行
- 编译命令：`gcc -O2 -Wall -Werror -o build/verilator-readmemh split-readmemh.c`
- **应用场景**：Verilator 仿真中模拟 4-bank 存储器的初始化

### 9.2 gen-treadle-readmemh.c

**功能**：将标准 readmemh 格式转换为 Treadle 仿真器使用的格式。

- 输入：标准 readmemh 文件（byte 格式）
- 输出：Treadle 兼容的 readmemh 文件
- 转换：将 4 个 byte 打包成一个 32-bit word，地址除以 4
- 处理 4/8/12/16 byte 对齐的数据行
- **应用场景**：Treadle 仿真器的内存初始化

### 9.3 groupby-4byte.c

**功能**：将二进制文件转换为 4-byte 分组的 hex 格式。

- 输入：原始二进制文件
- 输出：每 byte 以 hex 字符表示（`b>>4` 和 `b&0xf`），先输出 1M 个 `00` 作为前缀
- **应用场景**：创建特定格式的内存镜像文件

### 9.4 Makefile

仅编译 `split-readmemh.c` 为 `build/verilator-readmemh`，其他工具需要手动编译。

---

## 10. SRAM Size 分析工具

### 10.1 sram_size_collect.py

位于 `scripts/sram_size_collect.py`，用于从 Verilog 文件中提取 SRAM 宏的规格信息。

**工作原理**：

1. 扫描指定目录下所有以 `array_` 开头、`_ext.v` 结尾的 Verilog 文件
2. 使用正则表达式提取注释中的 SRAM 信息：`array_{X}_ext` 中的 `depth`、`width`、`masked`、`maskGran`、`maskSeg`
3. 解析出参数：`X`（实例编号）、`Y`（depth/深度）、`Z`（width/宽度）、`M`（maskGran/掩码粒度）、`N`（maskSeg/掩码段数）、`T`（masked/是否带掩码）
4. 按 `(Y, Z, M, N, T)` 排序后输出到带时间戳的 txt 文件

**输出格式**：`Y:{depth} Z:{width} M:{maskGran} N:{maskSeg} T:{masked}`

**应用场景**：分析 SoC 集成后的 SRAM 宏规格，辅助 SRAM 选型和面积估算。

### 10.2 SramInfo 与 SramHelper

在 `utility/src/main/scala/utility/sram/SramHelper.scala` 中：

- **SramInfo** 类：计算 MBIST 节点数、mask 宽度、data 宽度等参数
- **SramHelper.getSramSuffix**：将 Scala 类名路径缩写为短 SRAM 后缀（如 `coupledL2.DataStorage` -> `l2_dat`）
- **SramHelper.shortMap**：预定义的缩写映射表，覆盖 L2 Cache、ICache、BP（分支预测器）、FTQ、DCache、MMU、Prefetch 等模块
- **SramHelper.genRam**：生成 SRAM 宏的核心函数，创建 `SramArray` 实例并配置 MBIST 接口

---

## 11. FPGA vs ASIC SRAM 映射

### 11.1 ASIC 路径（主路径）

**核心路径**：`SRAMTemplate` -> `SramHelper.genRam` -> `SramProto` -> `SramArray1P` / `SramArray2P` -> `SyncReadMem`

- 使用 Chisel 的 `SyncReadMem`，综合工具将其推断为 SRAM 宏
- 支持 `SramBroadcastBundle` 中的 DFT 信号（`ram_bypass`、`ram_bp_clken`、`ram_hold`、`ram_mcp_hold`、`ram_ctl` 等）
- 通过 `MbistClockGateCell` 实现 MBIST 门控时钟
- 支持 configurable SRAM 参数：setup time、hold time、latency
- SRAM 命名规则：`sram_array_{1|2}p{depth}x{width}m{maskWidth}s{setup}h{hold}l{latency}[b]_{suffix}`

### 11.2 FPGA 路径

**TrueDualPortSRAM**（IO-PMP 专用）：

- 基于 `SyncReadMem`，综合工具推断为 FPGA Block RAM
- 支持真正的双端口读写
- 可配置读延迟流水线
- 写冲突通过软件断言检测

**Folded1WDataModuleTemplate**：

- 使用 `Mem(nRows, Vec(width, gen))`，在 FPGA 上可推断为分布式 RAM（小容量）或 Block RAM（大容量）
- 支持 reset 初始化
- 适合小容量寄存器堆实现

**Queue_SRAM**：

- `SyncReadMem` 实现 Queue，在 FPGA 上推断为 Block RAM
- 处理读写地址冲突（当 `do_enq && r_addr === enq_ptr.value` 时暂停读取）

### 11.3 关键差异

| 特性 | ASIC 路径 | FPGA 路径 |
|------|----------|----------|
| SRAM 类型 | 工艺库 SRAM 宏 | Block RAM / Distributed RAM |
| MBIST | 完整支持 | 不适用 |
| DFT 控制 | 丰富的 broadcast 信号 | 基本不需要 |
| Clock Gate | MbistClockGateCell | FPGA 内建时钟使能 |
| 冲突处理 | 精细策略配置 | 基于 SyncReadMem WriteFirst |
| SRAM 命名 | 带 foundry/sramInst 后缀 | 标准 Chisel 命名 |

---

## 12. 关键源文件位置汇总

### 12.1 Core SRAM 基础设施（utility.sram）

| 文件路径 | 功能描述 |
|---------|---------|
| `utility/src/main/scala/utility/sram/SRAMTemplate.scala` | SRAMTemplate、SplittedSRAMTemplate、FoldedSRAMTemplate、SRAMTemplateWithArbiter |
| `utility/src/main/scala/utility/sram/SramProto.scala` | SramArray（1P/2P）、SramMbistIO、SramBroadcastBundle、SRAM 原型层 |
| `utility/src/main/scala/utility/sram/SramHelper.scala` | SramInfo、SramHelper（genRam、shortMap、getSramSuffix） |
| `utility/src/main/scala/utility/DataModuleTemplate.scala` | RawDataModuleTemplate、SyncDataModuleTemplate、AsyncRawDataModuleTemplate、Folded1WDataModuleTemplate、DataModuleTemplate |
| `utility/src/main/scala/utility/package.scala` | SRAMTemplate 类型别名（已 deprecated） |

### 12.2 L2 Cache SRAM 基础设施（XSCache）

| 文件路径 | 功能描述 |
|---------|---------|
| `XSCache/src/main/scala/coupledL2/utils/SRAMTemplate.scala` | L2 侧 SRAMTemplate、SRAMTemplateWithArbiter |
| `XSCache/src/main/scala/coupledL2/utils/SplittedSRAM.scala` | L2 侧 SplittedSRAM（三维分裂） |
| `XSCache/src/main/scala/coupledL2/utils/BankedSRAM.scala` | BankedSRAM（set 维度 Bank 化） |
| `XSCache/src/main/scala/coupledL2/utils/SRAMWrapper.scala` | SRAMWrapper（简单 Bank 封装） |
| `XSCache/src/main/scala/coupledL2/utils/GatedSplittedSRAM.scala` | GatedSplittedSRAM（含 ClockGate 的分裂 SRAM） |
| `XSCache/src/main/scala/coupledL2/utils/Queue_SRAM.scala` | 基于 SyncReadMem 的 Queue 实现 |
| `XSCache/src/main/scala/coupledL2/DataStorage.scala` | L2 数据存储（使用 SplittedSRAM） |
| `XSCache/src/main/scala/coupledL2/Directory.scala` | L2 目录存储（使用 SRAMTemplate） |

### 12.3 ECC 保护

| 文件路径 | 功能描述 |
|---------|---------|
| `utility/src/main/scala/utility/ECC.scala` | IdentityCode、ParityCode、SECCode、SECDEDCode、ErrGen、CanHaveErrors |
| `rocket-chip/src/main/scala/util/ECC.scala` | Rocket Chip ECC 实现（功能类似） |

### 12.4 FPGA 专用组件

| 文件路径 | 功能描述 |
|---------|---------|
| `ChiselIOPMP/src/main/scala/IopmpChecker.scala` | TrueDualPortSRAM（第 144-209 行） |

### 12.5 工具脚本

| 文件路径 | 功能描述 |
|---------|---------|
| `tools/readmemh/split-readmemh.c` | readmemh 拆分为 4-bank 工具 |
| `tools/readmemh/gen-treadle-readmemh.c` | 转换为 Treadle 格式 |
| `tools/readmemh/groupby-4byte.c` | 二进制转 4-byte hex 格式 |
| `tools/readmemh/Makefile` | 编译配置 |
| `scripts/sram_size_collect.py` | 从 Verilog 提取 SRAM 规格信息 |

### 12.6 测试文件

| 文件路径 | 功能描述 |
|---------|---------|
| `utility/src/test/scala/utility/TestSRAMTemplate.scala` | SRAMTemplate 全面测试（单端口、双端口、folded、冲突行为） |
| `XSCache/src/test/scala/TestSplittedSRAM.scala` | SplittedSRAM 测试 |

### 12.7 SRAM 使用示例（核心模块）

| 文件路径 | 使用的 SRAM 模板 |
|---------|----------------|
| `src/main/scala/xiangshan/frontend/bpu/tage/TageTable.scala` | FoldedSRAMTemplate |
| `src/main/scala/xiangshan/frontend/bpu/ittage/IttageTable.scala` | FoldedSRAMTemplate |
| `src/main/scala/xiangshan/frontend/ftq/EntryQueue.scala` | SyncDataModuleTemplate |
| `src/main/scala/xiangshan/frontend/ftq/CfiQueue.scala` | SyncDataModuleTemplate |
| `src/main/scala/xiangshan/backend/CtrlBlock.scala` | SyncDataModuleTemplate |
| `src/main/scala/xiangshan/backend/issue/DataArray.scala` | AsyncRawDataModuleTemplate |
| `src/main/scala/xiangshan/mem/mdp/StoreSet.scala` | SyncDataModuleTemplate |
| `src/main/scala/xiangshan/cache/dcache/data/BankedDataArray.scala` | SRAMTemplate |

---

## 13. 架构总结

XiangShan 的 SRAM & Memory Infrastructure 呈现出清晰的分层架构：

```
应用层:  TAGE/ITTAGE/FTQ/DCache/ICache/PMP 等功能模块
   |
模板层:  SRAMTemplate / FoldedSRAMTemplate / SplittedSRAMTemplate
         DataModuleTemplate / SyncDataModuleTemplate / Folded1WDataModuleTemplate
         BankedSRAM / SRAMWrapper / GatedSplittedSRAM
   |
协议层:  SRAMReadBus / SRAMWriteBus / SRAMBundleA / SRAMBundleAW / SRAMBundleR
   |
原型层:  SramProto / SramArray1P / SramArray2P / SramHelper / SramInfo
   |
存储层:  SyncReadMem (Chisel primitive)
   |
保护层:  ECC (SECDED/SEC/Parity) / CanHaveErrors
```

该架构实现了高度的可配置性、可测试性（MBIST 支持）、多目标适配性（ASIC/FPGA）以及性能与功耗的灵活权衡（时钟门控、Bank 化、冲突处理策略）。

---

## 14. 统计摘要

| 维度 | 数量/规模 |
|------|----------|
| SRAMTemplate 变体 | 6 种（SRAMTemplate、SplittedSRAMTemplate、FoldedSRAMTemplate、SRAMTemplateWithArbiter + L2 侧对应版本） |
| DataModuleTemplate 变体 | 5 种（Raw、Sync、Async、Folded1W、标准 DataModuleTemplate） |
| 冲突处理策略 | 9 种（CorruptRead、CorruptReadWay、BypassWrite、AssertionFail、BufferWrite、BufferWriteLossy、BufferWriteLossyFast、StallWrite、StallRead） |
| ECC 编码类型 | 4 种（IdentityCode、ParityCode、SECCode、SECDEDCode） |
| readmemh 工具 | 3 个（split-readmemh、gen-treadle-readmemh、groupby-4byte） |
| Banking 策略 | 三维（setSplit x waySplit x dataSplit）+ 维度折叠 |
| 使用 SRAMTemplate 的模块 | ICache、DCache、FTQ、TAGE、ITTAGE、L2 Cache Directory/DataStorage 等 |
| 使用 DataModuleTemplate 的模块 | FTQ 各 Queue、PC 存储、GPAMem、Issue Queue、Store Set 等 |
