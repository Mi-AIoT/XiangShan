# R06 - 指令译码与寄存器重命名 (Instruction Decode & Register Rename)

## 1. 概述

XiangShan 处理器的指令译码与寄存器重命名阶段是前端流水线与后端执行引擎之间的关键桥梁。译码阶段将 RISC-V 指令流解码为处理器内部统一的 micro-op (微操作) 表示，而重命名阶段则通过 Register Alias Table (RAT) 将逻辑寄存器映射为物理寄存器，消除 WAW (Write-After-Write) 和 WAR (Write-After-Read) 假依赖，为乱序执行提供基础。

XiangShan 的译码-重命名流水线具有以下显著特点：
- **双译码器架构**：简单译码器并行处理大部分指令，复杂译码器处理向量 (Vector) 和 AMO 指令的 micro-op 拆分
- **28 种指令融合模式 (Instruction Fusion)**：将相邻两条指令融合为一条 micro-op，提升执行效率
- **Move Elimination 机制**：整数 Move 指令无需分配新物理寄存器，直接重用源寄存器
- **双表 RAT 设计 (Dual-table RAT)**：投机表 (spec_table) 与架构表 (arch_table) 协同工作，支持基于快照的投机恢复
- **ROB 压缩 (ROB Compression)**：连续可压缩指令共享 ROB 条目，扩大有效 ROB 窗口

本文将从译码流水线架构、RISC-V 扩展支持、指令融合、RAT 设计、FreeList 管理、Move Elimination、BusyTable、ROB 压缩等方面进行深入分析。

---

## 2. 译码流水线架构 (Decode Pipeline Architecture)

### 2.1 顶层模块 DecodeStage

译码阶段的顶层模块为 `DecodeStage`（位于 `src/main/scala/xiangshan/backend/decode/DecodeStage.scala`），它采用 **简单-复杂双译码器架构 (Simple-Complex Dual Decoder Architecture)**：

- **简单译码器 (Simple Decoder)**：实例化 `DecodeWidth` 个 `DecodeUnit` 模块，每个周期可并行译码 `DecodeWidth` 条指令。这些译码器覆盖 RV64I/M/A/B (Zba/Zbb/Zbc/Zbs)、Scalar Crypto (Zknd/Zkne/Zknh/Zksed/Zksh)、FP、CBO、Svinval、Hypervisor (H)、Zicond、Zimop、Zfa 等指令集。

- **复杂译码器 (Complex Decoder)**：仅实例化 1 个 `DecodeUnitComp` 模块，用于处理向量 (Vector) 指令的 micro-op 拆分和 AMO 指令的复合操作。复杂译码器需要多个周期才能产出结果。

### 2.2 简单/复杂指令路由

DecodeStage 中的关键路由逻辑确保复杂译码器的结果正确插入到输出流中：

- `simplePrefixVec`：标记每个简单译码器输出前有多少条复杂译码器指令需要插入
- `firstComplexOH`：one-hot 向量，标记第一条复杂指令的位置
- `complexNum`：当前周期复杂译码器产出的 micro-op 数量

当复杂译码器产出结果时，简单译码器的结果会被相应右移，复杂译码器的输出被放置在输出流的最前面（编号 0 的位置）。这种设计确保了向量指令的多 micro-op 输出按正确顺序进入后续流水线。

### 2.3 Rename 与 RAT 的连接

DecodeStage 还负责连接 Register Alias Table 的读端口（`intRatReadPorts`、`fpRatReadPorts`、`vecRatReadPorts`、`v0RatReadPorts`、`vlRatReadPorts`），译码阶段读取 RAT 获取每个逻辑寄存器对应的当前物理寄存器映射，为重命名阶段提供初始映射信息。

### 2.4 VTypeGen 模块

`VTypeGen`（位于 `src/main/scala/xiangshan/backend/decode/VTypeGen.scala`）负责处理 RISC-V Vector 扩展中的 `vtype` 配置。它维护两份 vtype 状态：

- `vtypeSpec`：投机版本的 vtype，在 decode 阶段根据 `vsetvli`/`vsetivli` 指令更新，在 walk/redirect 时从快照恢复
- `vtypeArch`：架构版本的 vtype，仅在 commit 时更新

当 `vsetvli` 出现在译码输入中时，VTypeGen 通过 `VsetModule` 计算新的 vtype 值，并在下一周期将其传递给译码逻辑，使得后续的向量指令可以使用正确的 SEW/LMUL 配置进行译码。

### 2.5 UopInfoGen 模块

`UopInfoGen`（位于 `src/main/scala/xiangshan/backend/decode/UopInfoGen.scala`）为复杂指令生成 micro-op 元信息，包括：

- `numOfWB`：该指令需要的写回次数（即 micro-op 数量）
- `lmul`：向量指令的 LMUL（Load/Store Group Length）值
- `isComplex`：标记指令是否为复杂指令（向量算术、向量访存、AMO CAS）

---

## 3. RISC-V 指令集扩展支持

XiangShan 的 `DecodeUnit`（位于 `src/main/scala/xiangshan/backend/decode/DecodeUnit.scala`）通过组合多个 decode table 支持广泛的 RISC-V 扩展：

### 3.1 基础指令集

- **RV64I**：整数基础指令集，通过 `XDecode` 表实现
- **RV64M**：乘法除法扩展，包含 MUL、MULH、MULHSU、MULHU、DIV、DIVU、REM、REMU 及其 32-bit 变体
- **RV64A**：原子操作扩展，包含 LR/SC、AMO (atomic memory operations) 指令

### 3.2 Bitmanip 扩展

通过 `BitmanipDecode` 表实现：
- **Zba** (Address generation)：SH1ADD、SH2ADD、SH3ADD、ADDUW、SLLI_SRLI_SRAI (shift left add)
- **Zbb** (Basic bit-manipulation)：CLZ、CTZ、CPOP、XNOR、ORC.B、MIN/MAX/MINU/MAXU、SEXT.B/H、ZEXTH、ROL、ROR、ORC.B、CZERO.EQZ/NEZ
- **Zbc** (Carry-less multiply)：CLMUL、CLMULH、CLMULR
- **Zbs** (Single-bit operations)：BCLR、BEXT、BINV、BSET

### 3.3 Scalar Crypto 扩展

通过 `ScalarCryptoDecode` 表实现：
- **Zknd**：AES 解密指令 (AES64DS、AES64DSM、AES64IM)
- **Zkne**：AES 加密指令 (AES64ES、AES64ESM)
- **Zknh**：SHA-2 哈希指令 (SHA256SIG0/1、SHA512SIG0/1、SHA512SUM0/1)
- **Zksed**：SM4 加密指令 (SM4ED、SM4KS)
- **Zksh**：SM3 哈希指令 (SM3P0、SM3P1)

### 3.4 其他扩展

- **Zbkb/Zbkc/Zbkx**：Bitmanip 在密码学中的变体
- **Zicond**：整数条件操作 (CZERO.EQZ、CZERO.NEZ)
- **Zimop**：may-be-operations，暂时作为 Move 指令处理
- **Zfa**：额外浮点指令 (FMINM、FMAXM、FLEQ、FLTQ、FCVTMOD_W_D 等)
- **Fp**：单/双精度浮点运算指令 (通过 `FpDecode` 表)
- **FDivSqrt**：浮点除法和平方根 (通过 `FDivSqrtDecode` 表)
- **H (Hypervisor)**：虚拟化扩展 (通过 `HypervisorDecode` 表)
- **Svinval**：细粒度 TLB 管理 (通过 `SvinvalDecode` 表)
- **CBO**：Cache Block 管理操作 (通过 `CBODecode` 表)

### 3.5 Decode Table 格式

每条指令的 decode entry 包含 14 个字段：

| 字段 | 含义 |
|------|------|
| `srcType x 3` | 三个源操作数的类型（X/FP/Vec/Imm） |
| `fuType` | 功能单元类型（ALU/BRU/LSU/FPU/MDU 等） |
| `fuOpType` | 功能单元操作码 |
| `rfWen` | 整数寄存器文件写使能 |
| `fpWen` | 浮点寄存器文件写使能 |
| `vecWen` | 向量寄存器文件写使能 |
| `noSpecExec` | 非投机执行标记（如 Store） |
| `blockBackward` | 阻塞后续指令 |
| `flushPipe` | 刷新流水线 |
| `canRobCompress` | 可被 ROB 压缩 |
| `uopSplitType` | micro-op 拆分类型 |
| `selImm` | 立即数类型选择 |

### 3.6 Move 指令检测

译码器通过模式匹配识别 Move 指令：
```
isMove = ADDI rd, rs1, 0
bitpat = b000000000000_?????_000_?????_0010011
```
其中 rd 和 rs1 不为 x0。识别出的 Move 指令在重命名阶段将触发 Move Elimination 优化。

---

## 4. 指令融合 (Instruction Fusion)

### 4.1 融合机制概述

`FusionDecoder`（位于 `src/main/scala/xiangshan/backend/decode/FusionDecoder.scala`）实现了 28 种指令融合模式。融合检测在译码阶段完成（T0 检测，T1 产出结果），将两条连续指令合并为一条 micro-op，减少后端资源消耗并提升 IPC。

### 4.2 融合模式分类

28 种融合模式可归类为以下几大类：

**地址生成融合 (Address Generation Fusion)：**
- `FusedAdduw`：ADD + ADDUW -> 地址计算
- `FusedSh1add` / `FusedSh2add` / `FusedSh3add` / `FusedSh4add`：ADD + SHxADD -> 移位加法
- `FusedSr29add` / `FusedSr30add` / `FusedSr31add` / `FusedSr32add`：SRLI + ADD -> 右移加法

**位宽扩展融合 (Width Extension Fusion)：**
- `FusedZexth` / `FusedZexth1`：ADD + ZEXT.H -> 零扩展半字
- `FusedSexth`：ADD + SEXT.H -> 符号扩展半字
- `FusedSzewl1` / `FusedSzewl2` / `FusedSzewl3`：移位 + 位宽操作融合

**算术融合 (Arithmetic Fusion)：**
- `FusedOddadd` / `FusedOddaddw`：ADD + AND -> 奇数偏移加法
- `FusedMulw7`：ADD + ADDW -> 乘法近似

**逻辑融合 (Logic Fusion)：**
- `FusedOrh48`：LUI + ADD -> 高位 OR 操作
- `FusedLogiclsb`：AND/ANDN/XOR/OR + ADD -> 逻辑低位置位
- `FusedLogicZexth`：逻辑操作 + ZEXT.H

**常量加载融合 (Constant Loading Fusion)：**
- `FusedLui32`：LUI + ADDI -> 32-bit 常量加载
- `FusedLui32w`：LUI + ADDIW -> 32-bit 常量加载 (W变体)

**复合运算融合 (Compound Operation Fusion)：**
- `FusedAddwbyte`：ADDIW + ANDI -> 字节操作
- `FusedAddwbit`：ADDIW + ANDI -> 位操作
- `FusedAddwzexth`：ADDIW + ZEXT.H -> 带宽扩展的加法
- `FusedAddwsexth`：ADDIW + SEXT.H -> 带宽扩展的加法
- `FusedByte2`：两条 ADD 相邻 -> 字节地址计算

### 4.3 融合结果的处理

FusionDecoder 的输出通过 `FusionDecodeReplace` 修改原始指令的 `fuType`、`fuOpType`、`lsrc2`、`src2Type`、`selImm` 和 `imm` 字段。融合后第二条指令被 `io.clear(i+1)` 信号清除，不占用后端资源。

---

## 5. RAT 双表设计 (Dual-table RAT Design)

### 5.1 架构概述

`RenameTable`（位于 `src/main/scala/xiangshan/backend/rename/RenameTable.scala`）实现了投机表 + 架构表的双表 RAT 设计，这是现代乱序处理器支持投机执行和快速恢复的关键机制。

### 5.2 投机表 (spec_table)

- **功能**：维护投机执行路径上的最新寄存器映射
- **更新时机**：
  - 重命名阶段：新指令的 `rd` 映射到新物理寄存器时更新
  - Walk 阶段：分支误预测恢复时从快照恢复
- **读取**：同步读取，支持旁路 (bypass) 从 `specWritePorts` 获得同一周期内其他指令的最新写入

### 5.3 架构表 (arch_table)

- **功能**：维护已提交 (committed) 状态的寄存器映射，反映处理器的架构状态
- **更新时机**：仅在 commit 阶段更新
- **用途**：在没有快照可用时作为恢复的基准，以及用于 `old_pdest` 的 `need_free` 判断

### 5.4 五种寄存器类型

RenameTableWrapper 为五种寄存器类型各实例化一个 RenameTable：

| 寄存器类型 | 寄存器数量 | 说明 |
|-----------|-----------|------|
| `Reg_I` | 32 | 整数逻辑寄存器 (x0-x31) |
| `Reg_F` | 32 | 浮点逻辑寄存器 (f0-f31) |
| `Reg_V` | 31 | 向量逻辑寄存器 (v1-v31) |
| `Reg_V0` | 1 | 向量掩码寄存器 (v0) |
| `Reg_Vl` | 1 | 向量长度寄存器 (vl) |

### 5.5 时序优化

RAT 的读写采用精心设计的时序策略：
- **写入**：在 T0 发起（重命名阶段），实际在 T1 生效
- **读取**：同步读取当前映射
- **旁路逻辑 (Bypass Logic)**：当同一周期内有多条指令写入同一逻辑寄存器时，后续指令通过旁路获得最新的物理寄存器映射，避免使用过时的值

### 5.6 old_pdest 生成

当一条新指令映射 `rd` 到新的物理寄存器时，需要知道 `rd` 当前映射的物理寄存器（`old_pdest`），以便在 commit 时释放该物理寄存器回 FreeList。`old_pdest` 从 `spec_table` 的写端口读取获得，同时通过 `need_free` 检查确认该物理寄存器确实需要被释放（即在 `arch_table` 中不再映射给任何其他逻辑寄存器）。

### 5.7 快照恢复机制

每个重命名的分支指令会创建一个 RAT 快照（通过 `SnapshotGenerator`），保存当前 `headPtr` 指向的 FreeList 位置。当分支误预测发生时，通过 `lastCycleRedirect` 信号触发恢复：
1. 使用快照中的 `headPtr` 恢复 FreeList 的 head 指针
2. 使用快照中的 `spec_table` 值恢复寄存器映射
3. 在 walk 阶段重新执行被取消指令的寄存器分配

---

## 6. FreeList 设计

### 6.1 双 FreeList 架构

XiangShan 的 FreeList 管理采用双 FreeList 设计（位于 `src/main/scala/xiangshan/backend/rename/freelist/`）：

- **MEFreeList**（Move-Elimination FreeList）：用于整数物理寄存器分配，大小为 `IntPhyRegs`
- **StdFreeList**（Standard FreeList）：用于浮点/向量物理寄存器分配，包括 FP、Vec、V0、Vl 四个独立的 FreeList

### 6.2 BaseFreeList

`BaseFreeList`（位于 `src/main/scala/xiangshan/backend/rename/freelist/BaseFreeList.scala`）是两个 FreeList 实现的共同基类，定义了：

- **headPtr / headPtrOH**：分配指针及其 one-hot 编码，指向下一个待分配的物理寄存器
- **archHeadPtr**：架构 head 指针，跟踪已提交状态的分配位置
- **SnapshotGenerator**：对 headPtr 进行快照管理，支持分支恢复时的回滚
- **redirectedHeadPtr**：恢复时使用快照值或 `archHeadPtr + walkReq` 计算

FreeList 本质上是一个**循环队列 (Circular Queue)**，headPtr 向前移动表示分配新物理寄存器，tailPtr 向前移动表示释放物理寄存器。

### 6.3 MEFreeList (Move-Elimination FreeList)

`MEFreeList`（位于 `src/main/scala/xiangshan/backend/rename/freelist/MEFreeList.scala`）是整数物理寄存器的分配器，其核心特点是**支持 Move Elimination**：

**初始化：**
```
freeList = {1, 2, ..., size-1, 0}
```
寄存器 0-31 映射到逻辑寄存器 x0-x31，物理寄存器从 1 开始可用。

**分配逻辑：**
- `numAllocate = Mux(io.walk, PopCount(io.walkReq), PopCount(io.allocateReq))`
- 正常重命名：`doNormalRename = io.canAllocate && io.doAllocate && !io.redirect`
- Walk 重命名：`doWalkRename = io.walk && io.doAllocate && !io.redirect`
- **Move Elimination 的关键**：`allocateReq` 中过滤掉了 Move 指令（`!isMove`），因此 Move 指令不会消耗 FreeList 中的物理寄存器

**释放逻辑：**
- `freePtr` 计算每条释放请求的目标位置
- 当 commit 时，`old_pdest` 被写回 FreeList 对应位置

**空闲寄存器计数：**
```
freeRegCnt = Mux(doWalkRename && !lastCycleRedirect,
                 distanceBetween(tailPtrNext, headPtr) - PopCount(io.walkReq),
             Mux(doNormalRename,
                 distanceBetween(tailPtrNext, headPtr) - PopCount(io.allocateReq),
                                     distanceBetween(tailPtrNext, headPtr)))
```
`canAllocate` 基于 `freeRegCntReg`（延迟一周期），当空闲数 >= `RenameWidth` 时允许分配。

### 6.4 StdFreeList (Standard FreeList)

`StdFreeList`（位于 `src/main/scala/xiangshan/backend/rename/freelist/StdFreeList.scala`）用于 FP、Vec、V0、Vl 物理寄存器分配：

**初始化：**
```
freeList = {numLogicRegs, numLogicRegs+1, ..., numLogicRegs+freeListSize-1}
```

**与 MEFreeList 的主要差异：**
- 没有 Move Elimination 逻辑，所有指令都消耗 FreeList 条目
- 使用 `GatedValidRegNext` 延迟 `canAllocate` 信号，改善时序
- 包含 `XSError(!isFull(tailPtrNext, archHeadPtr))` 检查确保 FreeList 在架构级别始终保持完整

**双指针管理：**
- `tailPtr` / `tailPtrNext`：跟踪 commit 释放的物理寄存器
- `headPtr` / `headPtrOH`：跟踪重命名分配的物理寄存器

---

## 7. Move Elimination

### 7.1 机制原理

Move Elimination 是 XiangShan 优化整数 Move 指令（`ADDI rd, rs1, 0`）的关键技术。传统流程中，Move 指令需要分配新的物理寄存器给 rd，然后在执行阶段将 rs1 的值复制到 rd。Move Elimination 通过以下优化消除这种开销：

1. **译码阶段**：`DecodeUnit` 检测 `isMove` 模式（`ADDI rd, rs1, 0`）
2. **重命名阶段**：
   - `allocateReq(i) = !isMove && ...`：Move 指令不请求新的物理寄存器
   - `pdest = Mux(isMove, psrc, freelist_output)`：Move 指令的 pdest 直接使用 psrc（源物理寄存器），而非从 FreeList 分配新物理寄存器
3. **结果**：Move 指令的 rd 和 rs1 映射到同一个物理寄存器，无需任何执行

### 7.2 对 FreeList 的影响

由于 Move 指令不消耗 FreeList 条目，MEFreeList 中的空闲物理寄存器数量比传统设计更多，降低了整数物理寄存器不足导致 stall 的概率。`MEFreeList` 的 `allocateReq` 输入已经过滤掉了 Move 指令，因此 FreeList 的 headPtr 仅在非 Move 指令分配时前进。

### 7.3 RAT 更新

Move 指令在 RAT 中的处理与普通指令相同：`rd` 的映射被更新为 `psrc`（即 rs1 当前映射的物理寄存器）。这使得后续所有读取 rd 的指令都会获取 rs1 的值。

### 7.4 Zimop 支持

`Zimop`（May-Be-Operation）扩展的指令目前也被暂时视为 Move 指令处理，享受 Move Elimination 优化。

---

## 8. BusyTable 与 Load 依赖跟踪

### 8.1 BusyTable 架构

`BusyTable`（位于 `src/main/scala/xiangshan/backend/rename/BusyTable.scala`）跟踪每个物理寄存器的忙碌状态，分为整数、浮点和向量三种类型。

### 8.2 状态更新优先级

BusyTable 的更新遵循严格优先级：
1. **WakeUp/WB（最高优先级）**：当物理寄存器被写回 (writeback) 时，清除其 busy 状态
2. **Alloc**：分配新物理寄存器时，设置其 busy 状态
3. **Load Cancel**：取消 load 操作时，设置相关物理寄存器的 busy 状态

### 8.3 快速唤醒集成 (Fast Wakeup Integration)

BusyTable 直接接收来自执行引擎的快速唤醒信号 (`wakeUpInt`、`wakeUpFp`、`wakeUpVec`)，根据 `pregWB` 类型过滤对应的唤醒信号。这使得刚被写回的物理寄存器可以在下一周期立即被使用，减少不必要的 stall。

### 8.4 Load 依赖跟踪 (Load Dependency Tracking)

BusyTable 实现了 load 依赖跟踪机制，这是处理 load-to-use 延迟的关键：

- `loadDependency`：寄存器数组，记录每个物理寄存器是否依赖于尚未完成的 load 操作
- `shiftLoadDependency`：每个周期将 load 依赖右移，反映 load 操作的剩余延迟周期数
- 当依赖计数为零时，load 依赖被清除

**示例**：如果 load 操作需要 3 个周期，则在分配时 `loadDependency = 3'b100`，后续每个周期右移一位（`100 -> 010 -> 001 -> 000`），3 个周期后依赖清除。

### 8.5 Load Cancel 支持

当 load 操作被取消（如 TLB miss 或页面异常），通过 `ldCancel` 信号将依赖的物理寄存器标记为 busy，阻止后续指令使用不正确的值。此外，`og0Cancel` 用于零延迟执行单元的取消信号。

### 8.6 VlBusyTable

`VlBusyTable` 扩展了 `BusyTable`，增加了：
- `nonzeroTable`：标记物理寄存器是否包含非零的 vl 值
- `vlmaxTable`：记录每个物理寄存器的 vlmax（最大向量长度）信息

这些信息用于向量指令的投机执行控制。

---

## 9. ROB 压缩 (ROB Compression)

### 9.1 CompressUnit 架构

`CompressUnit`（位于 `src/main/scala/xiangshan/backend/rename/CompressUnit.scala`）实现了基于查找表 (Lookup Table) 的 ROB 压缩逻辑。该设计灵感来自论文 "Implementing a Large Instruction Window Through Compression" (CROB)。

### 9.2 压缩原理

ROB 压缩允许连续的、可压缩的指令共享同一个 ROB 条目。例如，如果 3 条连续指令都标记为 `canRobCompress = true`，它们可以被合并到 1 个 ROB 条目中，从而有效地将 ROB 容量扩大 3 倍。

### 9.3 输入信号

- `extendedCanCompress`：结合了每条指令的 `canRobCompress` 标志和 `blockBackCompress` 类型信息的扩展向量
- `extendedUopNum`：每条指令的 micro-op 数量
- `oddFtqVec`：标记是否跨越 FTQ (Fetch Target Queue) 边界

### 9.4 Espresso 最小化器

CompressUnit 使用 **EspressoMinimizer** 基于真值表 (Truth Table) 的组合逻辑优化来解码压缩模式。这种方法可以自动生成最优化的硬件实现，避免手工编写复杂的 if-else 逻辑。

### 9.5 输出信号

对于每个 RenameWidth 槽位，CompressUnit 输出：
- `needRobFlags`：是否需要新的 ROB 条目
- `instrSizes`：每个条目中包含的指令数量
- `masks`：标记哪些指令被包含在同一个 ROB 条目中

### 9.6 FTQ 边界处理

当连续的可压缩指令跨越 FTQ 边界时（通过 `oddFtqVec` 检测），压缩被限制在同一 FTQ 条目内，避免跨取指块的指令被错误合并。

---

## 10. 重命名阶段整体流程 (Rename Stage Overall Flow)

`Rename`（位于 `src/main/scala/xiangshan/backend/rename/Rename.scala`）将上述所有组件整合为完整的重命名流水线：

### 10.1 核心组件实例化

```
intFreelist: MEFreeList    // 整数 FreeList (Move Elimination 感知)
fpFreelist:  StdFreeList    // 浮点 FreeList
vecFreelist: StdFreeList    // 向量 FreeList
v0Freelist:  StdFreeList    // V0 FreeList
vlFreelist:  StdFreeList    // VL FreeList
ratWrapper:  RenameTableWrapper  // 双表 RAT
compressUnit: CompressUnit      // ROB 压缩
```

### 10.2 Psrc Bypass 逻辑

当多条指令在同一周期被重命名时，后续指令可能依赖前面指令的 pdest 作为自己的 psrc。Rename 模块通过 `bypassCond(j)(i-1)` 矩阵实现旁路：

```
for (i <- 1 until RenameWidth) {
  for (j <- 0 until i) {
    bypassCond(j)(i-1) = (io.allocatePhyReg(j) === prsrc(i))
  }
  prsrc(i) = Mux1H(bypassCond, allocatePhyReg)
}
```

这确保了同一周期内指令间的数据依赖被正确处理。

### 10.3 LUI + Load 融合

Rename 模块实现了 LUI + Load 指令融合优化：当一条 LUI 后面紧跟一条 Load 时，将 LUI 的立即数与 Load 的立即数拼接为更大的偏移量，存入 `Cat(lui_imm, ld_imm)`，消除额外的 ALU 操作。

### 10.4 快照与恢复

每条分支指令在重命名阶段创建快照：
- 保存当前 `headPtr` 到 SnapshotGenerator
- 当分支误预测时，通过 redirect 信号触发恢复
- Walk 阶段重新执行被取消指令的寄存器分配

### 10.5 输出控制

`canOut` 信号综合了以下条件：
- 所有相关 FreeList 都有足够空闲寄存器 (`canAllocate`)
- Dispatch 阶段准备就绪 (`io.out(i).ready`)
- 不在 walk 阶段 (`!io.redirect`)

---

## 11. 关键源文件索引

| 文件路径 | 功能 |
|---------|------|
| `src/main/scala/xiangshan/backend/decode/DecodeStage.scala` | 译码阶段顶层模块，双译码器架构 |
| `src/main/scala/xiangshan/backend/decode/DecodeUnit.scala` | 简单译码器，完整 decode table |
| `src/main/scala/xiangshan/backend/decode/DecodeUnitComp.scala` | 复杂译码器，向量 micro-op 拆分 |
| `src/main/scala/xiangshan/backend/decode/FusionDecoder.scala` | 28 种指令融合模式 |
| `src/main/scala/xiangshan/backend/decode/VecDecoder.scala` | 向量指令 decode table |
| `src/main/scala/xiangshan/backend/decode/VTypeGen.scala` | Vector vtype 投机/架构状态管理 |
| `src/main/scala/xiangshan/backend/decode/UopInfoGen.scala` | micro-op 信息生成 (numOfWB/lmul/isComplex) |
| `src/main/scala/xiangshan/backend/rename/Rename.scala` | 重命名阶段顶层模块 |
| `src/main/scala/xiangshan/backend/rename/RenameTable.scala` | 双表 RAT (spec + arch) |
| `src/main/scala/xiangshan/backend/rename/BusyTable.scala` | 忙碌表 + load 依赖跟踪 |
| `src/main/scala/xiangshan/backend/rename/CompressUnit.scala` | ROB 压缩逻辑 |
| `src/main/scala/xiangshan/backend/rename/Snapshot.scala` | 通用快照生成器 |
| `src/main/scala/xiangshan/backend/rename/freelist/BaseFreeList.scala` | FreeList 基类 |
| `src/main/scala/xiangshan/backend/rename/freelist/MEFreeList.scala` | Move-Elimination FreeList |
| `src/main/scala/xiangshan/backend/rename/freelist/StdFreeList.scala` | Standard FreeList |

---

## 12. 总结

XiangShan 的指令译码与寄存器重命名阶段体现了现代高性能处理器前端设计的核心理念：

1. **并行译码**：简单译码器并行处理多条指令，复杂译码器处理需要 micro-op 拆分的指令，通过巧妙的路由逻辑确保输出顺序正确。

2. **广泛的 ISA 支持**：覆盖 RV64GC + V + H + Zicond + Zimop + Zfa + Scalar Crypto 等丰富的 RISC-V 扩展，通过模块化的 decode table 设计实现灵活扩展。

3. **指令融合**：28 种融合模式覆盖地址生成、位宽扩展、算术/逻辑运算、常量加载等多种场景，在不增加前端复杂度的情况下提升执行效率。

4. **高效重命名**：双表 RAT + 快照恢复机制支持深层投机执行，Move Elimination 大幅减少整数 Move 指令的资源消耗，bypass 逻辑确保指令间依赖的正确处理。

5. **资源管理**：MEFreeList 和 StdFreeList 的双 FreeList 设计针对不同寄存器类型的特点进行优化，BusyTable 的 load 依赖跟踪机制有效处理 load-to-use 延迟。

6. **ROB 扩展**：CompressUnit 通过硬件压缩技术有效扩大 ROB 窗口，提升乱序执行的指令级并行度。

这些设计相互配合，使得 XiangShan 在前端译码和重命名阶段能够高效地将指令流转化为可执行的 micro-op 流，为后端的乱序执行引擎提供充足的指令供给。
