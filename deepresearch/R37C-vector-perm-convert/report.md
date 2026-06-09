# R37C - Vector Permutation & Convert 深度分析报告

## 1. 概述

本文对 XiangShan RISC-V 处理器中 Yunsuan 算力单元的 **向量置换 (Vector Permutation)** 和 **向量类型转换 (Vector Convert)** 模块进行深度分析。这些模块实现了 RISC-V V Extension 规范中的向量置换指令族（vslide、vrgather、vcompress）以及向量浮点/整数类型转换指令族（vcvt），并包含独立的标量浮点转换通路。

核心源文件位于：
- `yunsuan/src/main/scala/yunsuan/vector/VectorPerm/` -- 组合逻辑置换实现（旧版/共享通路）
- `yunsuan/src/main/scala/yunsuan/vector/VPERM/` -- 新版 VPERM 顶层及子模块
- `yunsuan/src/main/scala/yunsuan/vector/VectorPermFsm/` -- 置换 FSM（有限状态机控制器）
- `yunsuan/src/main/scala/yunsuan/vector/VectorConvert/` -- 向量类型转换管线
- `yunsuan/src/main/scala/yunsuan/vector/VectorMove/` -- 向量 Move 操作
- `yunsuan/src/main/scala/yunsuan/scalar/` -- 标量浮点转换单元

---

## 2. Vector Permutation Unit（向量置换单元）

### 2.1 整体架构

XiangShan 的向量置换单元存在两套实现体系：

**旧版通路 (VectorPerm/Permutation.scala)**：位于 `yunsuan/vector/perm` 包中，实现了组合逻辑风格的置换运算。该模块 (`class Permutation extends Module`) 接受 128 位 VLEN 宽度的输入，包含完整的 vslideup、vslidedown、vslide1up、vslide1down、vrgather、vcompress 和 vmvnr 操作的组合逻辑实现。

**新版通路 (VPERM/)**：位于 `yunsuan/vector` 包中，采用模块化设计，将不同操作拆分为独立子模块，由 `VPermTop` 统一调度。该通路被集成到 FSM 控制器中用于处理复杂的多周期置换操作。

两套通路通过 `VPermOpcode` 进行操作码解码，其定义位于 `VPermDecode.scala` 中：

```scala
object VPermOpcode {
  val vslideup    = 0.U(6.W)
  val vslidedown  = 1.U(6.W)
  val vslide1up   = 2.U(6.W)
  val vslide1down = 3.U(6.W)
  val vrgather    = 4.U(6.W)
  val vrgather_vx = 5.U(6.W)
  val vcompress   = 6.U(6.W)
  val vmvnr       = 7.U(6.W)
}
```

### 2.2 VPermTop 顶层模块

`VPermTop`（位于 `VPERM/VPermTop.scala`）是新版置换通路的顶层封装，实例化了六个独立的功能子模块：

| 子模块 | 类名 | 功能 |
|--------|------|------|
| vslideup | `SlideUpLookupModule` | Slide Up 操作 |
| vslidedown | `SlideDownLookupModule` | Slide Down 操作 |
| vslide1up | `Slide1UpModule` | Slide 1 Up 操作 |
| vslide1down | `Slide1DownModule` | Slide 1 Down 操作 |
| vrgather | `VRGatherLookupModule` | Register Gather 操作 |
| vcompress | `CompressModule` | Compress 操作 |

顶层模块将所有公共输入（vs1, vs2, old_vd, mask, opcode, uop_idx, vm, ta, ma, vstart, vl, vlmul）广播至所有子模块，然后通过 `LookupTreeDefault` 根据 `opcode(3,0)` 选择对应模块的输出作为最终结果 `res_vd`。

### 2.3 VSlide 置换操作

#### 2.3.1 SlideUp 实现

`SlideUpLookup` 模块（位于 `VPERM/VSlide.scala`）实现了 vslideup.vx/vi 指令。其核心逻辑为两阶段流水线：

**Stage-0（地址计算与控制信号生成）**：
- 对每个数据元素 `i`（共 `n` 个，`n` 由 SEW 决定），计算索引偏移：`index(i) = slide_base + i - slide`
- 生成三个关键控制信号：
  - `res_keep_old_vd`：保持旧 vd 值（当 mask 未命中或 vstart 之前或 vl 之后且 ta=0）
  - `res_agnostic`：输出 agnostic 值（全 1，当 vl 之后且 ta=1 或 mask 未命中且 ma=1）
  - `res_update`：需要从源数据更新（当 `slide <= slide_base + i < slide + n`）
- 所有控制信号通过 `RegNext` 打一拍寄存

**Stage-1（数据选择）**：
- 根据 stage-0 的控制信号选择输出：
  - keep_old -> prev_data（旧 vd）
  - agnostic -> Fill 全 1
  - update -> src_data(index(i))（源数据查找）
  - first_gather -> 0（首次 gather 时默认值）
- 最终通过 `reduce(Cat(b, a))` 拼接为 VLEN 位宽输出

`SlideDownLookup` 采用类似架构，但索引计算方向相反：`index(i) = i + slide`。

#### 2.3.2 Slide1Up/Slide1Down

`Slide1UpModule` 和 `Slide1DownModule` 是 slide 操作的简化版本（slide amount 固定为 1），不需要额外的位移量输入。

#### 2.3.3 UOP 与 LD/VD 映射

在 `Permutation.scala` 中，`slideupVs2VdTable` 和 `slidednVs2VdTable` 两个查找表模块负责将 micro-operation 索引（uop_idx）映射为实际的源寄存器（vs2）和目的寄存器（vd）偏移。这是因为当 LMUL > 1 时，一条 vslide 指令需要被拆分为多个 uop，每个 uop 操作特定的寄存器组分片。

`slideupVs2VdTable` 的计算逻辑为：对于给定的 lmul 和 uopIdx，遍历三角形索引模式 `(lmul*(lmul+1)/2)` 个 uop，通过组合逻辑生成 `outOffsetVs2` 和 `outOffsetVd`。该表使用 QMCMinimizer 进行逻辑优化。

### 2.4 VRGather 置换操作

`VRGatherLookupModule`（位于 `VPERM/VRGather.scala`）实现了 vrgather.vv/vi/vx 三种变体。

#### 2.4.1 VRGatherLookup (VV/VI 变体)

核心 `VRGatherLookup(n: Int)` 模块参数化为 `n` 个元素（n=16/8/4/2 对应 sew=8/16/32/64）：

**Stage-0**：
- 将 128 位 index_data（来自 vs1）拆分为 `n` 个索引元素
- 对每个索引计算相对于当前 table 分片的偏移：`index(i) = index_data_vec(i) + ~table_range_min + 1`（等价于 `index_data_vec(i) - table_range_min`）
- 判断索引是否在当前 table 范围内：`res_update = (table_range_min <= index_data_vec(i)) && (index_data_vec(i) < table_range_max)`
- 所有信号通过 `RegNext` 打一拍

**Stage-1**：
- 根据 res_update 从 table_data 中查找对应元素
- 通过 `table_data_vec(index(i))` 实现按索引查找（Chisel 会生成 MUX 树）

#### 2.4.2 VRGatherLookupVX (VX 变体)

与 VV 变体的区别在于：index 来自标量寄存器 rs1 的低 64 位（`io.vs1(XLEN-1, 0)`），而非向量寄存器。因此所有 `n` 个元素使用相同的标量索引进行查找。

#### 2.4.3 GatherLookupModule 顶层

`VRGatherLookupModule` 顶层负责：
1. 根据 `uop_idx` 和 `vlmul` 计算 `vd_idx`（目标寄存器索引）和 `mask_start_idx`（掩码起始位置）
2. 判断 `first_gather`（是否为该目标寄存器的首次 gather，影响初始值为 0 还是保留旧值）
3. 计算 `table_range_min`/`table_range_max`（当前 uop 需要查找的 vs2 表范围）
4. 根据 SEW 选择对应的 `VRGatherLookup(n)` 模块输出
5. 对于 VX 变体，选择 `VRGatherLookupVX(n)` 的输出
6. 最终通过 `Mux(io.vstart >= io.vl, io.old_vd, ...)` 处理 vstart >= vl 的特殊情况

### 2.5 VCompress 置换操作

`CompressModule`（位于 `VPERM/VCompress.scala`）实现了 vcompress.vm 指令，该指令将源寄存器中被掩码置位的元素压缩到目标寄存器的低地址部分。

#### 2.5.1 Compress 核心逻辑

`Compress(n: Int)` 模块采用两阶段设计：

**Stage-0**：
- 计算每个元素的累积 ones 计数：`cmos_vec(i) = pmos + sum(mask(0..i-1))`
- pmos 是前一次 uop 的累积 ones 计数（`previous_mask_ones_sum`）
- 判断 agnostic 条件（超出 vl 或 elem_vld 且 ta=1）

**Stage-1**：
- 核心压缩逻辑：对于每个 mask 为 1 的源元素，计算其在目标 vd 中的目标位置：`res_idx = cmos_vec(i) - os_base`
- 通过 `compressed_en_vec(i) = ZeroExt(compress_en << res_idx, n)` 生成选择掩码
- 通过 `compressed_data_vec(i) = Fill(VLEN, compress_en) & ZeroExt(src_data << (res_idx << log2Up(VLEN/n)), VLEN)` 生成移位后的数据
- 使用 `ParallelOR` 合并所有元素的压缩数据

#### 2.5.2 CompressModule 顶层

CompressModule 负责：
1. 根据 `uop_idx` 计算 `vs1_idx`（掩码分片索引）和 `vd_idx`（目标分片索引），两者的关系由三角形 uop 模式决定
2. 使用 `SelectMaskN` 从 vs1 中提取当前分片的 16 位掩码
3. 处理 `ones_sum_base` 的传递（当一个 uop 的 ones 计数超过 vlenb 时，需要在下一个 uop 中继续）
4. 选择 `output_mask_ones_sum` 信号在某些特殊 uop 中输出累积 ones 计数而非压缩数据

### 2.6 Permutation.scala 旧版实现

`Permutation.scala`（位于 `VectorPerm/Permutation.scala`）是更早期的实现，直接在一个模块中包含所有置换操作的完整组合逻辑。其特点包括：

- 直接在单个时钟周期内完成所有计算（依赖上游提供寄存的输入）
- 对 vcompress 使用了详细的注释标注了 3 个 cycle 的计算步骤
- `vlRemain_vcompress` 的计算使用了 Mux1H 对 uopIdx 的 8 种情况进行选择
- 包含了完整的 mask 处理、tail 处理、agnostic 处理逻辑

### 2.7 IO 与 Bundle 定义

`VPermIO`（位于 `VPERM/VPermUtil.scala`）定义了置换单元的标准接口：

| 信号 | 方向 | 位宽 | 说明 |
|------|------|------|------|
| vs1 | Input | VLEN(128) | 源向量寄存器 1 |
| vs1_type | Input | 4 | vs1 元素类型 |
| vs2 | Input | VLEN(128) | 源向量寄存器 2 |
| vs2_type | Input | 4 | vs2 元素类型 |
| old_vd | Input | VLEN(128) | 旧目标向量寄存器 |
| vd_type | Input | 4 | 目标元素类型 |
| opcode | Input | 6 | 操作码 |
| uop_idx | Input | 6 | 微操作索引 |
| mask | Input | VLEN(128) | 掩码 |
| vm | Input | 1 | 向量掩码模式（0=masked, 1=unmasked） |
| ta | Input | 1 | tail agnostic 模式 |
| ma | Input | 1 | mask agnostic 模式 |
| vstart | Input | 7 | 向量起始索引（0-127） |
| vl | Input | 8 | 向量长度（0-128） |
| vlmul | Input | 3 | 向量 LMUL 配置 |
| res_vd | Output | VLEN(128) | 结果 |

`VPermType` 对象定义了各操作的 4 位编码：

```
vslideup     = b0000    vslidedown   = b0001
vslide1up    = b0010    vslide1down  = b0011
vrgather     = b0100    vrgatherrs1  = b0101
vcompress    = b0110    vwregmov     = b0111
```

`SelectMaskN` 工具函数实现了从 128 位掩码中按指定起始位置选取 N 位掩码的功能，支持 sew=8/16/32/64 四种粒度。

---

## 3. Permutation FSM 设计（置换有限状态机）

### 3.1 架构概述

`PermFsm`（位于 `VectorPermFsm/PermFsm.scala`，1280 行）是 XiangShan 向量置换单元的多周期 FSM 控制器，用于管理需要多次寄存器读写和计算的复杂置换操作。与组合逻辑通路不同，FSM 通过状态机控制读取源寄存器、执行置换计算、写回结果的完整流程。

FSM 支持的指令类型（`VPermFsmOpcode`）：
- `vslideup` (0) -- Slide Up
- `vslidedn` (1) -- Slide Down  
- `vrgather` (2) -- Register Gather (VV)
- `vrgather_vx` (3) -- Register Gather (VX)
- `vrgather16` (4) -- 16-bit Gather（支持跨 SEW 的 gather）
- `vcompress` (5) -- Compress
- `dummy` (7) -- 空操作

### 3.2 四状态 FSM

FSM 使用 4 个状态进行调度：

```
idle -> rec_idx -> rd_vs -> calc_vd -> idle
```

**idle 状态**：等待有效 uop 到达。当 `uop_valid` 为真时跳转到 `rec_idx`。

**rec_idx 状态**：记录索引信息，收集所有 uop 的寄存器索引。FSM 支持双发射（viq0 + viq1），同时接收两个 uop 的信息。当所有 uop 计数完成（`rec_done`）后跳转到 `rd_vs`。

**rd_vs 状态**：执行寄存器读取。FSM 通过 4 个读端口（`fsm_rd_vld_0/1/2/3`）发起寄存器读取请求：
- Port 0：读取 vs1 数据（gather 的索引）
- Port 1：读取 vs2 低位分片（源数据 lo）
- Port 2：读取 vs2 高位分片（源数据 hi）
- Port 3：读取 old_vd 数据

通过流水线寄存器链（5 级延迟寄存器）跟踪读取状态，当所有读取完成（`rd_done`）后跳转到 `calc_vd`。

**calc_vd 状态**：根据读取的数据计算置换结果并写回。当所有 vd 分片计算完成（`calc_done`）后返回 idle。

### 3.3 Access Table 机制

FSM 的一个核心创新是 **Access Table** 机制，用于优化 vrgather 操作的寄存器访问模式：

1. 在 `rec_idx` 阶段，根据 vs1 中的索引值生成 `access_table(vs_idx)`，标记哪些 vs2 分片被当前 uop 访问
2. 在 `rd_vs` 阶段，使用 `sof`（Set of First）函数找到 `access_table` 中第一个置位的 bit，确定下一个需要读取的 vs2 分片
3. 读取完成后，更新 access_table 移除已处理的分片（`table_hi_lo` = 未被 lo 或 hi 读取覆盖且当前需要的分片）
4. 只有当 access_table 中还有未处理的分片时才继续读取，避免不必要的寄存器访问

`access_table_gen` 函数根据不同的 gather 变体生成 access_table：
- 对于 `vrgather_vv`，逐字节分析 vs1 中的索引
- 对于 `vrgather_vx`，所有字节使用相同的标量索引
- 对于 `vrgather16`，根据 SEW 特殊处理跨分片索引

### 3.4 流水线与控制逻辑

FSM 使用大量的 5 级流水线寄存器来跟踪各阶段的状态：

```
update_table_reg[0..4]        -- table 更新状态
vrgather_table_sent_reg[0..4] -- table 读取发送状态
vrgather_wb_vld_reg[0..4]     -- gather 写回有效状态
rd_sent_reg[0..4]             -- 读取发送状态
rd_wb_reg[0..4]               -- 读取写回状态
cmprs_wb_vld_reg[0..4]        -- compress 写回有效状态
src_lo_valid_reg[0..4]        -- vs2_lo 有效状态
src_hi_valid_reg[0..4]        -- vs2_hi 有效状态
```

**分支刷新处理**：所有状态寄存器都在 `br_flush_vld` 时清零，确保分支预测错误时正确恢复。

**vcompress 特殊处理**：
- 使用 `one_sum` 寄存器跟踪当前 uop 中 mask 的 ones 计数
- 当 `rd_one_sum + current_rd_vs_ones_sum >= vlenb` 时，触发 `rd_wb` 信号将压缩结果写入 vd
- 之后进入 `cmprs_rd_vd` 模式，读取旧 vd 并合并剩余未压缩元素
- `cmprs_rd_vd_idx` 自增直到覆盖所有需要更新的 vd 分片

### 3.5 结果生成与尾部处理

FSM 的结果生成逻辑（`calc_vd` 阶段）：

**vrgather 结果**：
```scala
for (i <- 0 until vlenb) {
  vrgather_vd(i) := Mux(first_gather, old_vd(i), vd_reg(i))
  when(vmask_byte_strb(i)) {
    when(vrgather_byte_sel(i) in [lo_min, lo_max))
      vrgather_vd(i) := vs2_lo_bytes(vrgather_byte_sel(i) - lo_min)
    when(vrgather_byte_sel(i) in [hi_min, hi_max))
      vrgather_vd(i) := vs2_hi_bytes(vrgather_byte_sel(i) - hi_min)
    when(first_gather && no_match)
      vrgather_vd(i) := 0
  }
}
```

**Slide 结果**：
- vslideup：前 `vslide_offset` 字节保持 old_vd，后续字节从 vs2_lo/hi 中按偏移提取
- vslidedn：从 vs2_lo/hi 中按正偏移提取，越界部分填 0

**Tail/Mask 处理**：
```scala
perm_tail_mask_vd := (vd_reg & vmask_tail_bits & vmask_vstart_bits) | tail_vd | vstart_old_vd
```
- `vmask_tail_bits`：尾部掩码（vl 之后的部分）
- `tail_vd`：根据 ta 选择 agnostic（全 1）或 old_vd
- `vstart_old_vd`：vstart 之前的部分保持 old_vd

### 3.6 输入输出接口

FSM 通过 `VPermFsmInput` 接收来自两个发射队列（viq0/viq1）的 uop 信息，每个包含：
- opcode, sew, vs1(128b), old_vd(128b), mask(128b)
- 各寄存器的物理寄存器索引（preg_idx）
- uop_idx, vm, ta, ma, vstart, vl, lmul_4
- uop_vld, uop_flush_vld, rob_idx

FSM 通过 `VPermFsmOutput` 输出：
- 4 个读端口（fsm_rd_vld + fsm_rd_preg_idx）
- 写回端口（fsm_wb_vld, fsm_wb_preg_idx, fsm_wb_data）
- 状态信号（fsm_busy, fsm_rob_idx, fsm_lmul_4）

---

## 4. VectorConvert 类型转换单元

### 4.1 整体架构

向量类型转换单元（`VectorConvert`）实现了 RISC-V V Extension 中 vcvt 相关指令，支持：
- FP32 -> INT32/INT64
- FP16 -> FP32 (widen)
- FP32 -> FP16 (narrow)
- INT32/INT64 -> FP32
- FP <-> FP 跨精度转换
- vfrec7/vfrsqrt7 估计指令

顶层模块 `VectorCvt`（位于 `VectorConvert/Convert.scala`）根据 SEW 和 widen 模式确定输入输出宽度，然后实例化多个不同位宽的 VCVT 子模块进行并行处理。

### 4.2 VectorCvt 顶层

`VectorCvt` 通过 `input1H` 和 `output1H` 解码确定数据路径：

| widen_sew | input1H | output1H | 说明 |
|-----------|---------|----------|------|
| 00_01 | 0010 (16) | 0010 (16) | FP16 -> FP16 |
| 00_10 | 0100 (32) | 0100 (32) | FP32 -> FP32 |
| 00_11 | 1000 (64) | 1000 (64) | FP64 -> FP64 |
| 01_00 | 0001 (8) | 0010 (16) | INT8 -> FP16 (widen) |
| 01_01 | 0010 (16) | 0100 (32) | FP16 -> FP32 (widen) |
| 01_10 | 0100 (32) | 1000 (64) | FP32 -> FP64 (widen) |
| 10_00 | 0010 (16) | 0001 (8) | FP16 -> INT8 (narrow) |
| 10_01 | 0100 (32) | 0010 (16) | FP32 -> FP16 (narrow) |
| 10_10 | 1000 (64) | 0100 (32) | FP64 -> FP32 (narrow) |

数据拆分方式：
- 128 位输入被拆分为 element8[8], element16[4], element32[2], element64[1]
- 使用 4 个 VCVT 实例并行处理：VCVT(64) 处理 in0，VCVT(32) 处理 in1，VCVT(16) 处理 in2 和 in3
- 结果通过 `Mux1H(outputWidth1H, ...)` 按目标宽度重新拼接

### 4.3 VCVT 子模块

`VCVT(width)` 根据 width 参数选择不同的实现：
- width=16 -> `CVT16`
- width=32 -> `CVT32`
- width=64 -> `CVT64`

#### 4.3.1 CVT16（16 位转换器）

位于 `VectorConvert/CVT16.scala`，采用 3 级流水线：

| Cycle | FP2INT | INT2FP | VFR (估计) |
|-------|--------|--------|------------|
| 0 | in(16) -> raw_in(17), left/right | in(16) -> in_abs(16), lzc, in_shift | in(16) -> lzc, exp_nor, sig_nor |
| 1 | ShiftRightJam(37) | RoundingUnit(10) + adder | clz, out_exp, adder |
| 2 | RoundingUnit(11) + adder | -> result & fflags | Table 查找 |

控制信号通过 `RegEnable` 在 fire 信号控制下逐级传递。支持的操作类型包括：
- `fp2int`：浮点转整数
- `int2fp`：整数转浮点
- `vfrsqrt7`：浮点倒数平方根估计
- `vfrec7`：浮点倒数估计

#### 4.3.2 CVT32（32 位转换器）

位于 `VectorConvert/CVT32.scala`，同样采用 3 级流水线，但结构更复杂：

| Cycle | FP2INT/FP2FP | INT2FP | VFR |
|-------|-------------|--------|-----|
| 0 | in(32), lzc, left, adder | in(32), in_abs, lzc, adder | in(32), lzc, adder |
| 1 | ShiftRightJam(33) | left, RoundingUnit(32), adder | Table |
| 2 | RoundingUnit(32), adder | -> result & fflags | -> result |

CVT32 被拆分为三个子模块：
- `CVT32ModuleS0`：Stage-0，处理输入解码、特殊值检测、前导零计数
- `CVT32ModuleS1`：Stage-1，执行移位、舍入、加法
- `CVT32ModuleS2`：Stage-2，后处理、异常标志生成

中间 Bundle 定义（`CVT32BundleOutputS0`）包含大量控制信号：exp, expPlus1Enable, shiftLeft, fracSrcLeft, inRounder, sticky, Special 标志集, 以及 int2fp 和 estimate 相关的信号。

#### 4.3.3 CVT64（64 位转换器）

位于 `VectorConvert/CVT64.scala`，是最复杂的转换器（40921 字节），支持：
- 向量转换模式（`isVectorCvt=true`）：实例化 `FP_INCVT`（浮点输入转换）、`INT2FP`（整数转浮点）、`Estimate7`（估计）三个子模块
- 标量转换模式（`isVectorCvt=false`）：直接使用 `FP_INCVT`
- 跨精度转换：`isCrossHigh`（f16->f64）和 `isCrossLow`（f64->f16）
- FP Canonical NaN 处理：检测输入是否为 NaN 并生成相应的 canonical NaN 输出

### 4.4 CVTparameter 参数定义

位于 `VectorConvert/CVTparameter.scala`，定义了浮点格式参数：

```scala
trait FloatFormat {
  def signWidth: Int
  def expWidth: Int
  def fracWidth: Int
  def bias: Int
  def maxExp = (BigInt(1) << expWidth) - 2
  def minExp = 1
  def precision = fracWidth + 1
}

object f16 extends FloatFormat { expWidth=5, fracWidth=10, bias=15 }
object f32 extends FloatFormat { expWidth=8, fracWidth=23, bias=127 }
object f64 extends FloatFormat { expWidth=11, fracWidth=52, bias=1023 }
```

舍入模式（`RoundingModle`）：
- RNE (0) -- Round to Nearest, ties to Even
- RTZ (1) -- Round Toward Zero
- RDN (2) -- Round Down (toward -inf)
- RUP (3) -- Round Up (toward +inf)
- RMM (4) -- Round to Nearest, ties to Max Magnitude
- RTO (6) -- Round to Odd

### 4.5 Estimate7 估计查找表

`Estimate7`（位于 `VectorConvert/Estimate7.scala`）实现了 7 位精度的倒数和倒数平方根查找表：

- `Rec7Table`：128 条目的倒数查找表，输入 7 位、输出 7 位，值范围 0-127
- `Rsqrt7Table`：128 条目的倒数平方根查找表，同样 7 位精度

这些查找表通过 `chisel3.util.experimental.decode.decoder` 和 `TruthTable` 实现，支持 vfrec7 和 vfrsqrt7 指令的快速估计。

### 4.6 工具函数

**utils.scala**：
- `intExtend(x, signed)`：扩展整数到 65 位
- `floatExtend(x, fp)`：扩展浮点数到 65 位（统一内部表示）
- `int32Extend(x, signed)`：扩展整数到 33 位
- `float32Extend(x, fp)`：扩展浮点数到 33 位

**util/ 子目录**：
- `CLZ.scala`：前导零计数器
- `FpFloat.scala`：浮点格式定义（FloatFormat trait）
- `Rounding.scala`：舍入逻辑
- `ShiftRightJam.scala`：Jam 右移（sticky bit 保持）

---

## 5. VectorMove 向量 Move 操作

### 5.1 模块设计

`VectorMove`（位于 `VectorMove/VMove.scala`）实现了向量-标量和向量-向量的 Move/Merge 操作，VLEN=128 位。

支持的操作类型（通过 `VmoveType` 判断）：

| 操作 | 说明 | 数据通路 |
|------|------|----------|
| vmv.x.s / vfmv.f.s | 标量 -> 向量第 0 元素 | 从 vs2 提取低 64 位，按 SEW 符号扩展到 64 位 |
| vmv.s.x / vfmv.s.f | 向量第 0 元素 -> 标量 | vs1 广播到所有字节 |
| vmerge | 按掩码合并 | 根据 mask 选择 vs1 或 vs2 的各字节 |
| vmv<nr>r.v | Whole Register Move | 直接复制 vs2 到 vd |

### 5.2 核心逻辑

**Merge 操作**：
```scala
vmaskAdjust = Mux1H(eewVd.oneHot, Seq(1, 2, 4, 8).map(k =>
  Cat(Seq.tabulate(numBytes/k)(i => Fill(k, mask(i))).reverse)
))
vmergeResult(i) = Mux(vmaskAdjust(i), vs1(8*i+7, 8*i), vs2(8*i+7, 8*i))
```
根据元素宽度（eewVd）调整掩码粒度，对每个字节独立选择源。

**vmv.x.s / vfmv.f.s**：
```scala
vmvResult = Mux1H(Seq(
  vsew===e8  -> BitsExtend(vs2(7,0), 64),
  vsew===e16 -> BitsExtend(vs2(15,0), 64),
  vsew===e32 -> BitsExtend(vs2(31,0), 64),
  vsew===e64 -> vs2(63,0)
))
```

当 `vm=1`（unmasked）或 `isVmvsx`/`isVfmvsf` 时，直接使用 vs1 广播结果，跳过 mask 选择。

---

## 6. Scalar Unit 标量单元

### 6.1 FPU 浮点类型定义

`FPU`（位于 `scalar/FPU.scala`）定义了标量浮点类型：

```scala
case class FType(expWidth: Int, precision: Int)
val f16 = FType(5, 11)   // half precision
val f32 = FType(8, 24)   // single precision  
val f64 = FType(11, 53)  // double precision
```

`box` 函数处理 NaN boxing：将窄精度结果嵌入 64 位值的低部，高位填充全 1。

### 6.2 INT2FP 标量整数转浮点

`INT2FP`（位于 `scalar/Convert.scala`）是一个 3 级流水线的整数转浮点模块：

**Stage-1**：输入处理
- 根据 `typeIn`（32 位或 64 位整数）和 `signIn` 进行符号扩展
- 通过 `RegEnable` 打拍

**Stage-2**：转换计算
- 实例化所有 3 种浮点格式的 `IntToFP` 模块（f16/f32/f64）
- 根据 `typeOutReg` 选择目标格式的结果

**Stage-3**：结果格式化
- 通过 `FPU.box` 进行 NaN boxing
- 输出最终结果和 fflags

### 6.3 IntToFP 整数到浮点转换器

`IntToFP`（位于 `scalar/IntToFP.scala`）分为两个子阶段：

**IntToFP_prenorm**：
- 计算绝对值（负数取补码加一）
- 使用 LZA（Leading Zero Anticipator）确定前导零数量
- 生成归一化整数 `in_norm`（将最高位 1 移到隐含位位置）
- 输出 lzc、is_zero、sign

**IntToFP_postnorm**：
- 计算原始指数：`exp_raw = 63 + bias - lzc`
- 提取有效数的尾数部分、round bit、sticky bit
- 使用 `RoundingUnit` 执行舍入
- 处理 overflow/underflow
- 生成最终 IEEE 754 格式结果和异常标志

### 6.4 FPCVT 标量浮点转换器

`FPCVT`（位于 `scalar/Convert.scala`）是标量浮点-浮点和浮点-整数转换的顶层模块。与向量版本类似，它使用 `input1H`/`output1H` 解码确定数据路径，但直接实例化单个 `CVT64` 模块处理完整的 64 位数据。

特殊处理了 `f16->f64`（isCrossHigh）和 `f64->f16`（isCrossLow）两种跨精度转换路径。

### 6.5 RoundingUnit 舍入单元

`RoundingUnit`（位于 `scalar/RoundingUnit.scala`）实现了符合 IEEE 754 的多模式舍入逻辑：

输入信号：`in`（尾数）、`roundIn`（舍入位 G/R/S 中的 R）、`stickyIn`（S 位）、`signIn`、`rm`（舍入模式）。

舍入判断：
```scala
r_up = MuxLookup(rm, false.B)(
  RNE -> ((r && s) || (r && !s && g)),  // ties to even
  RTZ -> false.B,                         // toward zero
  RUP -> (inexact && !signIn),           // toward +inf
  RDN -> (inexact && signIn),            // toward -inf
  RMM -> r                                // ties to max magnitude
)
```

### 6.6 LZA 与 CLZ

**LZA (Leading Zero Anticipator)**：使用组合逻辑预测减法结果的前导零，避免先做减法再计算 CLZ 的时序开销：
```scala
p(i) = a(i) ^ b(i)          // propagate
k(i) = !a(i) && !b(i)       // kill
f(i) = p(i) ^ !k(i-1)       // flag
```

**CLZ (Count Leading Zeros)**：使用 `PriorityEncoder` 实现，将输入反转后编码。

---

## 7. 源文件位置索引

### 7.1 VectorPerm 目录

| 文件 | 路径 | 大小 | 说明 |
|------|------|------|------|
| Permutation.scala | `vector/VectorPerm/Permutation.scala` | 36KB | 旧版组合逻辑置换实现 |
| VPermBundles.scala | `vector/VectorPerm/VPermBundles.scala` | 739B | VPermInput/VPermOpcode 定义 |
| VPermDecode.scala | `vector/VectorPerm/VPermDecode.scala` | 367B | 操作码常量 |

### 7.2 VPERM 目录

| 文件 | 路径 | 大小 | 说明 |
|------|------|------|------|
| VPermTop.scala | `vector/VPERM/VPermTop.scala` | 2KB | 新版置换顶层 |
| VPermUtil.scala | `vector/VPERM/VPermUtil.scala` | 3KB | IO、VPermType、VFormat、SelectMaskN |
| VRGather.scala | `vector/VPERM/VRGather.scala` | 13KB | VRGatherLookup/VX 模块 |
| VSlide.scala | `vector/VPERM/VSlide.scala` | 27KB | SlideUp/Down/1Up/1Down 模块 |
| VCompress.scala | `vector/VPERM/VCompress.scala` | 8KB | Compress/CompressModule 模块 |

### 7.3 VectorPermFsm 目录

| 文件 | 路径 | 大小 | 说明 |
|------|------|------|------|
| PermFsm.scala | `vector/VectorPermFsm/PermFsm.scala` | 49KB | 置换 FSM 控制器（1280 行） |
| VPermFsmBundles.scala | `vector/VectorPermFsm/VPermFsmBundles.scala` | 2KB | FSM IO Bundle 和 VPermFsmOpcode |
| VPermFsmDecode.scala | `vector/VectorPermFsm/VPermFsmDecode.scala` | 346B | FSM 操作码常量 |

### 7.4 VectorConvert 目录

| 文件 | 路径 | 大小 | 说明 |
|------|------|------|------|
| VCVT.scala | `vector/VectorConvert/VCVT.scala` | 2KB | VCVT 顶层包装 |
| Convert.scala | `vector/VectorConvert/Convert.scala` | 4KB | VectorCvt 主模块 |
| CVT16.scala | `vector/VectorConvert/CVT16.scala` | 19KB | 16 位转换器 |
| CVT32.scala | `vector/VectorConvert/CVT32.scala` | 26KB | 32 位转换器 |
| CVT64.scala | `vector/VectorConvert/CVT64.scala` | 41KB | 64 位转换器 |
| CVTparameter.scala | `vector/VectorConvert/CVTparameter.scala` | 1KB | 浮点格式参数 |
| Estimate7.scala | `vector/VectorConvert/Estimate7.scala` | 2KB | Rec7/Rsqrt7 查找表 |
| utils.scala | `vector/VectorConvert/utils.scala` | 2KB | 扩展工具函数 |
| util/CLZ.scala | `vector/VectorConvert/util/CLZ.scala` | - | 前导零计数 |
| util/FpFloat.scala | `vector/VectorConvert/util/FpFloat.scala` | - | 浮点格式定义 |
| util/Rounding.scala | `vector/VectorConvert/util/Rounding.scala` | - | 舍入逻辑 |
| util/ShiftRightJam.scala | `vector/VectorConvert/util/ShiftRightJam.scala` | - | Jam 右移 |

### 7.5 VectorMove 目录

| 文件 | 路径 | 大小 | 说明 |
|------|------|------|------|
| VMove.scala | `vector/VectorMove/VMove.scala` | 2KB | 向量 Move/Merge 模块 |

### 7.6 Scalar 目录

| 文件 | 路径 | 大小 | 说明 |
|------|------|------|------|
| FPU.scala | `scalar/FPU.scala` | 1KB | 浮点类型定义和 NaN boxing |
| Convert.scala | `scalar/Convert.scala` | 5KB | INT2FP/FPCVT 标量转换器 |
| IntToFP.scala | `scalar/IntToFP.scala` | 4KB | 整数转浮点 pre/post norm |
| Mul.scala | `scalar/Mul.scala` | 26KB | 标量乘法 |
| RoundingUnit.scala | `scalar/RoundingUnit.scala` | 2KB | IEEE 754 舍入单元 |
| utils.scala | `scalar/utils.scala` | 2KB | LZA、CLZ、SignExt、ZeroExt |

---

## 8. 关键设计总结

### 8.1 置换单元设计特点

1. **双实现体系**：组合逻辑通路（Permutation.scala）和 FSM 控制通路（PermFsm.scala + VPERM/）并存，前者用于简单/低延迟路径，后者用于需要多次寄存器访问的复杂操作
2. **参数化设计**：通过 SEW 参数化元素数量（n=16/8/4/2），所有子模块支持 4 种元素宽度
3. **Access Table 优化**：FSM 通过分析索引值提前确定需要访问的寄存器分片，减少不必要的读取
4. **双发射支持**：FSM 同时接收两个 uop（viq0/viq1），通过优先级选择和并行表生成提高吞吐

### 8.2 类型转换单元设计特点

1. **3 级流水线**：所有 CVT 实现采用 3 级流水线（cycle0 解码/预处理、cycle1 核心计算、cycle2 舍入/后处理）
2. **并行多精度处理**：VectorCvt 通过实例化多个不同位宽的 VCVT 模块并行处理，支持 8/16/32/64 位元素
3. **估计表加速**：vfrec7/vfrsqrt7 使用 7 位查找表实现单周期估计
4. **标量/向量复用**：CVT64 通过 `isVectorCvt` 参数在标量和向量模式间切换，共享大部分逻辑

### 8.3 关键参数

| 参数 | 值 | 说明 |
|------|-----|------|
| VLEN | 128 | 向量寄存器长度 |
| XLEN | 64 | 标量寄存器长度 |
| LaneWidth | 64 | Lane 宽度 |
| NLanes | 2 | Lane 数量 |
| vlenb | 16 | VLEN/8，字节数 |
| Max LMUL | 8 | 最大 LMUL（通过 vlmul=011 编码） |
| Max UOPs (compress) | 43 | vcompress 的最大 uop 数 |
| Max UOPs (gather) | 64 | vrgather 的最大 uop 数 |
