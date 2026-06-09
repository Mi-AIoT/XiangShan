# R45 - DCache Data Array 深度分析报告

## 1. 概述 (Overview)

XiangShan 处理器的 L1 Data Cache (DCache) Data Array 是整个存储层次结构中最关键的数据存储组件之一，负责存储和提供 CPU Load/Store 操作所需的数据。该模块位于 `src/main/scala/xiangshan/cache/dcache/data/` 目录下，主要由三个源文件构成：

- `AbstractDataArray.scala` — 抽象数据阵列接口定义
- `BankedDataArray.scala` — 分 Bank 数据阵列的两种实现（BankedDataArray 与 SramedDataArray）
- `DuplicatedDataArray.scala` — 冗余副本式数据阵列（历史设计，当前未实例化）

DCache Data Array 的默认配置为 **64 sets x 8 ways x 8 banks x 8 Bytes = 32 KB**，采用 8-bank 交织组织方式，支持最多 `LoadPipelineWidth` 个并行读端口（通常为 2-3 个）和 1 个写端口。其核心设计目标是在满足高带宽多端口访问的同时，通过 bank 交织（bank interleaving）降低 bank conflict 概率，并通过 ECC 保护实现数据可靠性。

---

## 2. AbstractDataArray 接口与参数 (Abstract Interface)

### 2.1 文件位置

`src/main/scala/xiangshan/cache/dcache/data/AbstractDataArray.scala` (95 行)

### 2.2 接口定义

`AbstractDataArray` 是 `DuplicatedDataArray` 的父类抽象类，定义了 DCache Data Array 的基本 IO 接口：

```scala
abstract class AbstractDataArray(implicit p: Parameters) extends DCacheModule {
  val io = IO(new DCacheBundle {
    val read  = Vec(3, Flipped(DecoupledIO(new L1DataReadReq)))
    val write = Flipped(DecoupledIO(new L1DataWriteReq))
    val resp  = Output(Vec(3, Vec(blockRows, Bits(encRowBits.W))))
    val nacks = Output(Vec(3, Bool()))
    val errors = Output(Vec(3, ValidIO(new L1CacheErrorInfo)))
  })
}
```

关键设计要点：
- **3 个读端口**：固定为 3 个读端口（对应 `LoadPipelineWidth`），每个端口使用 `DecoupledIO` 握手协议
- **1 个写端口**：单写端口设计，写操作与主流水线的 refill/writeback 共享
- **blockRows**：一个 cache block 中的行数 = `blockBytes / rowBytes` = 64 / 8 = 8 行
- **encRowBits**：编码后的行位宽 = `encWordBits * rowWords`，包含 ECC 校验位

### 2.3 读请求格式 (L1DataReadReq)

```scala
class L1DataReadReq extends DCacheBundle {
  val rmask  = Bits(blockRows.W)     // 行掩码，选择读取 block 中哪些行
  val way_en = Bits(nWays.W)         // way 选择掩码（one-hot 编码）
  val addr   = Bits(untagBits.W)     // 未标记的地址（index + offset）
}
```

### 2.4 写请求格式 (L1DataWriteReq)

`L1DataWriteReq` 继承自 `L1DataReadReq`，扩展了写相关信号：

```scala
class L1DataWriteReq extends L1DataReadReq {
  val wmask = Bits(blockRows.W)                           // 行写掩码
  val data  = Vec(blockRows, Bits(rowBits.W))             // 每行的数据
}
```

这种设计允许在单个周期内以行粒度写入一整个 cache block 的所有数据。

### 2.5 辅助方法

`AbstractDataArray` 定义了 `pipeMap` 辅助函数用于对 3 个端口进行统一操作，以及 `dumpRead`/`dumpWrite`/`dumpResp`/`dumpNack` 等调试打印方法。

---

## 3. AbstractBankedDataArray — Banked 版本的抽象层 (Banked Abstract Layer)

### 3.1 文件位置

`src/main/scala/xiangshan/cache/dcache/data/BankedDataArray.scala` 第 245-363 行

### 3.2 接口定义

`AbstractBankedDataArray` 是 `BankedDataArray` 和 `SramedDataArray` 的共同父类，提供了比 `AbstractDataArray` 更精细的 banked 读写接口：

```scala
abstract class AbstractBankedDataArray(implicit p: Parameters) extends DCacheModule {
  val io = IO(new DCacheBundle {
    val read = Vec(LoadPipelineWidth, Flipped(DecoupledIO(new L1BankedDataReadReqWithMask)))
    val is128Req = Input(Vec(LoadPipelineWidth, Bool()))
    val readline_intend = Input(Bool())
    val readline = Flipped(DecoupledIO(new L1BankedDataReadLineReq))
    val readline_can_go = Input(Bool())
    val readline_stall = Input(Bool())
    val readline_can_resp = Input(Bool())
    val write = Flipped(DecoupledIO(new L1BankedDataWriteReq))
    val write_dup = Vec(DCacheBanks, Flipped(Decoupled(new L1BankedDataWriteReqCtrl)))
    val readline_resp = Output(Vec(DCacheBanks, new L1BankedDataReadResult()))
    val readline_error = Output(Bool())
    val readline_error_delayed = Output(Bool())
    val read_resp = Output(Vec(LoadPipelineWidth, Vec(VLEN/DCacheSRAMRowBits, new L1BankedDataReadResult())))
    val read_error_delayed = Output(Vec(LoadPipelineWidth, Vec(VLEN/DCacheSRAMRowBits, Bool())))
    val bank_conflict_slow = Output(Vec(LoadPipelineWidth, Bool()))
    val disable_ld_fast_wakeup = Output(Vec(LoadPipelineWidth, Bool()))
    val pseudo_error = Flipped(DecoupledIO(Vec(DCacheBanks, new CtrlUnitSignalingBundle)))
  })
}
```

### 3.3 关键 IO 信号说明

**读端口 (Load Pipeline)**：
- `read`: `LoadPipelineWidth` 个读请求端口，使用 `L1BankedDataReadReqWithMask`（含 `bankMask` 和 `lqIdx`）
- `is128Req`: 标识 128-bit 宽请求（需读两个连续 bank）
- `read_resp`: 读响应，每个端口返回 `VLEN/DCacheSRAMRowBits` 个 bank 数据

**Readline 端口 (Main Pipeline)**：
- `readline`: Main Pipeline 的整行读请求（用于 refill 读取）
- `readline_can_go`/`readline_stall`/`readline_can_resp`: 时序控制信号
- `readline_resp`: 所有 bank 的整行读响应

**写端口**：
- `write`: 完整的写请求（含数据和掩码）
- `write_dup`: 每个 bank 独立的写控制信号副本（解决大扇出问题）

**冲突信号**：
- `bank_conflict_slow`: 延迟的 bank conflict 信号（s2 阶段使用）
- `disable_ld_fast_wakeup`: 禁用 load 快速唤醒信号（s1 阶段使用）

### 3.4 ECC 辅助方法

```scala
def getECCFromEncWord(encWord: UInt) = {
  if (EnableDataEcc) {
    encWord(encDataBits-1, DCacheSRAMRowBits)  // 高位为 ECC
  } else { 0.U }
}

def getDataFromEncWord(encWord: UInt) = {
  encWord(DCacheSRAMRowBits-1, 0)  // 低位为原始数据
}

def asECCData(ecc: UInt, data: UInt) = {
  if (EnableDataEcc) Cat(ecc, data) else data  // 拼接 ECC 和数据
}
```

### 3.5 仲裁辅助方法

`selcetOldestPort` 函数使用 `ParallelOperation` 实现基于 Load Queue Index 的 oldest-first 仲裁：

```scala
def selcetOldestPort(valid: Seq[Bool], bits: Seq[LqPtr], index: Seq[UInt]): ((Bool, LqPtr), UInt) = {
  ParallelOperation(valid zip bits zip index,
    (a, b) => {
      val bSel = a._1._2 > b._1._2  // 比较 lqIdx，更小的更早
      // ... 选择 valid 且最早的请求
    }
  )
}
```

---

## 4. BankedDataArray 8-Bank 组织结构 (8-Bank Organization)

### 4.1 文件位置

`src/main/scala/xiangshan/cache/dcache/data/BankedDataArray.scala` 第 660-994 行

### 4.2 地址位域划分

DCache 的物理/虚拟地址按以下方式划分为 banked 数据阵列的寻址信号：

```
           Virtual Address
 ------------------------------------------
 | Above index  | Set | Bank | Offset |
 ------------------------------------------
                |     |      |        |
                |     |      |        0
                |     |      DCacheBankOffset (log2(8) = 3)
                |     DCacheSetOffset (3 + log2(8) = 6)
                DCacheAboveIndexOffset (6 + log2(64) = 12)
```

具体位宽计算（基于默认配置）：
- `DCacheBankOffset` = log2(DCacheSRAMRowBytes) = log2(8) = 3 bits
- `DCacheSetOffset` = DCacheBankOffset + log2(DCacheBanks) = 3 + 3 = 6 bits
- `DCacheAboveIndexOffset` = DCacheSetOffset + log2(DCacheSets) = 6 + 6 = 12 bits

### 4.3 8-Bank 交织物理布局

文件中明确注释了 bank 交织的物理组织：

```
                     Banked DCache Data
 -----------------------------------------------------------------
 | Bank0 | Bank1 | Bank2 | Bank3 | Bank4 | Bank5 | Bank6 | Bank7 |
 -----------------------------------------------------------------
 | Way0  | Way0  | Way0  | Way0  | Way0  | Way0  | Way0  | Way0  |
 | Way1  | Way1  | Way1  | Way1  | Way1  | Way1  | Way1  | Way1  |
 | ....  | ....  | ....  | ....  | ....  | ....  | ....  | ....  |
 -----------------------------------------------------------------
```

每个 bank 包含所有 8 个 way 的对应行数据，通过 bank index（地址的低 3 位）选择特定 bank，通过 way_en（one-hot 编码）选择特定 way。

### 4.4 DataSRAMBank 层次结构

`BankedDataArray` 使用 `DataSRAMBank` 作为 bank 粒度的存储单元，每个 bank 内部包含 `DCacheWays` (8) 个独立的 `SRAMTemplate` 实例：

```scala
class DataSRAMBank(index: Int) extends DCacheModule {
  val data_bank = Seq.fill(DCacheWays) {
    Module(new SRAMTemplate(
      Bits(encDataBits.W),
      set = DCacheSets / DCacheSetDiv,  // = 64
      way = 1,
      shouldReset = false,
      holdRead = false,
      singlePort = true,    // 单端口 SRAM
      withClockGate = true, // 支持 clock gating 省功耗
      hasMbist = hasMbist,
      hasSramCtl = hasSramCtl,
      suffix = Some("dcsh_dat")
    ))
  }
}
```

`DataSRAMBank` 的读端口返回 `Vec(DCacheWays, UInt(encDataBits.W))`，即一次读出所有 8 个 way 的数据，然后由外部的 way_en 选择器选出目标 way。

整体存储层次为：

```
BankedDataArray
  +-- DCacheSetDiv (1) x DCacheBanks (8) = 8 个 DataSRAMBank
       +-- 每个 DataSRAMBank 包含 DCacheWays (8) 个 SRAMTemplate
            +-- 每个 SRAMTemplate: encDataBits.W, set=64, way=1, singlePort
```

### 4.5 两种实现的切换

在 `DCacheWrapper.scala` (line 932) 中，根据 WPU 配置选择具体实现：

```scala
val bankedDataArray = if(dwpuParam.enWPU) Module(new SramedDataArray)
                      else Module(new BankedDataArray)
```

- **WPU 未启用**：使用 `BankedDataArray`，以 bank 粒度组织 SRAM
- **WPU 启用**：使用 `SramedDataArray`，以 (div, bank, way) 三元组粒度组织

---

## 5. 读写端口分配与仲裁 (Port Allocation & Arbitration)

### 5.1 读端口来源

`BankedDataArray` 的 IO 接口定义了两类读端口：

**Load Pipeline 读端口** (`io.read`)：
- 数量：`LoadPipelineWidth` 个（通常为 2 或 3）
- 类型：`DecoupledIO(L1BankedDataReadReqWithMask)`
- 特点：每个请求携带 `bankMask` 掩码和 `lqIdx`（Load Queue 索引）用于冲突仲裁
- 支持 128-bit 请求 (`is128Req`)，此时读取连续两个 bank

**Main Pipeline Readline 端口** (`io.readline`)：
- 数量：1 个
- 类型：`DecoupledIO(L1BankedDataReadLineReq)`
- 用途：refill/writeback 时读取整行数据，携带 `rmask` 和 `way` 字段

### 5.2 写端口来源

**Main Pipeline 写端口** (`io.write`)：
- 类型：`DecoupledIO(L1BankedDataWriteReq)`
- 来源：通过 `dataWriteArb` 仲裁器连接 MainPipe 的 `data_write`

**重复控制写端口** (`io.write_dup`)：
- 数量：`DCacheBanks` (8) 个
- 类型：`Decoupled(L1BankedDataWriteReqCtrl)`
- 用途：为每个 bank 提供独立的写控制信号，解决大扇出（fanout）时序问题

### 5.3 地址解码逻辑

读地址经过三级解码提取寻址信号：

```scala
div_addrs(rport_index)     := addr_to_dcache_div(io.read(rport_index).bits.addr)
set_addrs(rport_index)     := addr_to_dcache_div_set(io.read(rport_index).bits.addr)
bank_addrs(rport_index)(0) := addr_to_dcache_bank(io.read(rport_index).bits.addr)
bank_addrs(rport_index)(1) := bank_addrs(rport_index)(0) + 1.U  // 连续 bank（128-bit 请求）
```

### 5.4 读使能控制

每个 SRAM bank 的读使能由以下逻辑决定：

```scala
val bank_addr_matchs = WireInit(VecInit(List.tabulate(LoadPipelineWidth)(i => {
  io.read(i).valid && div_addrs(i) === div_index.U &&
  (bank_addrs(i)(0) === bank_index.U ||
   bank_addrs(i)(1) === bank_index.U && io.is128Req(i)) &&
  !rr_bank_conflict_oldest(i)  // 排除被仲裁掉的冲突请求
})))
val readline_match = io.readline.valid && line_div_addr === div_index.U
val read_enable = bank_addr_matchs.asUInt.orR || readline_match
```

当多个端口同时匹配同一 bank 时，通过 `PriorityMux` 选择 set 地址：

```scala
val bank_set_addr = Mux(readline_match,
  line_set_addr,
  PriorityMux(Seq.tabulate(LoadPipelineWidth)(i =>
    bank_addr_matchs(i) -> set_addrs(i)))
)
```

### 5.5 写操作路径

写数据经过寄存器流水化后到达 SRAM：

```scala
val write_bank_mask_reg = RegEnable(io.write.bits.wmask, io.write.valid)
val write_data_reg = RegEnable(io.write.bits.data, io.write.valid)
val write_valid_reg = RegNext(io.write.valid)
```

每个 bank 的写使能综合了 bank mask、valid、div 地址和 way_en：

```scala
val wen_reg = write_bank_mask_reg(bank_index) &&
  write_valid_dup_reg(bank_index) &&
  write_div_addr_dup_reg(bank_index) === div_index.U &&
  RegNext(io.write.valid)
```

写数据还包含 ECC 编码：

```scala
val write_ecc_reg = RegEnable(
  getECCFromEncWord(cacheParams.dataCode.encode(io.write.bits.data(bank_index))),
  io.write.valid
)
data_bank.io.w.data := asECCData(write_ecc_reg, write_data_reg(bank_index))
```

---

## 6. Bank Conflict 检测与处理 (Bank Conflict Detection & Handling)

### 6.1 三类 Bank Conflict

BankedDataArray 实现了三种 bank conflict 检测机制：

#### 6.1.1 Read-Read Bank Conflict (`rr_bank_conflict`)

当两个 Load Pipeline 端口同时访问同一 div 下不同 set 但重叠的 bank 时发生：

```scala
val rr_bank_conflict = Seq.tabulate(LoadPipelineWidth)(x =>
  Seq.tabulate(LoadPipelineWidth)(y => {
    if (x == y) false.B
    else {
      io.read(x).valid && io.read(y).valid &&
      div_addrs(x) === div_addrs(y) &&                    // 同一 div
      (io.read(x).bits.bankMask & io.read(y).bits.bankMask) =/= 0.U &&  // bank 重叠
      set_addrs(x) =/= set_addrs(y)                       // 不同 set
    }
  }))
```

关键设计：**同 set 不同 bank 可以并行读取**，只有不同 set 访问相同 bank 才算冲突。

#### 6.1.2 Read-Write Bank Conflict (`wr_bank_conflict`)

读请求与上一周期写操作的 bank 重叠时发生（SRAM 单端口限制）：

```scala
val wr_bank_conflict = Seq.tabulate(LoadPipelineWidth)(x =>
  io.read(x).valid &&
  write_valid_reg &&                          // 上一周期有写操作
  div_addrs(x) === write_div_addr_dup_reg.head &&
  (write_bank_mask_reg(bank_addrs(x)(0)) ||
   write_bank_mask_reg(bank_addrs(x)(1)) && io.is128Req(x))
)
```

#### 6.1.3 Read-Line Bank Conflict (`rrl_bank_conflict`)

Load 读请求与 Main Pipeline 的 readline（refill 读整行）冲突：

```scala
val judge = io.read(i).valid && div_addrs(i) === line_div_addr
rrl_bank_conflict(i) := judge && io.readline.valid
rrl_bank_conflict_intend(i) := judge && io.readline_intend  // 更早版本冲突检测
```

注意：`ReduceReadlineConflict = false`，即当 readline 和 load 读同一 div 时即算冲突，不要求 bank mask 匹配。

### 6.2 冲突仲裁 — Oldest-First 策略

当多个 Load 端口发生 read-read conflict 时，系统采用 **Oldest-First** 仲裁策略：

```scala
val load_req_bank_conflict_selcet =
  selcetOldestPort(load_req_with_bank_conflict, load_req_lqIdx, load_req_index)
val load_req_bank_select_port =
  UIntToOH(load_req_bank_conflict_selcet._2).asBools
val rr_bank_conflict_oldest = (0 until LoadPipelineWidth).map(i =>
  !load_req_bank_select_port(i) && load_req_with_bank_conflict(i)
)
```

`selcetOldestPort` 函数使用 `ParallelOperation` 比较 Load Queue Index (`lqIdx`)，选择最小（最早）的请求作为胜者，抑制较晚的请求。

### 6.3 冲突信号输出

冲突检测结果通过两个信号传递给 Load Pipeline：

- **`bank_conflict_slow`**：延迟 1 拍的有效冲突信号，用于 load pipe s2 阶段判断数据是否有效
- **`disable_ld_fast_wakeup`**：在 s1 阶段即可判断的冲突信号，用于禁用快速唤醒（因为冲突会导致读数据延迟）

### 6.4 "Fake" Bank Conflict

当两个请求访问**同一 set、同一 div** 但不同 bank 时，由于 SRAM 的并行读能力，实际上不会产生真正的 bank conflict。代码中专门追踪这种 "fake" 冲突用于性能调试：

```scala
bankConflictData.fake_rr_bank_conflict :=
  set_addrs(0) === set_addrs(1) && div_addrs(0) === div_addrs(1)
```

### 6.5 Readline Stall 与 Ready

readline 端口在与写操作冲突时不发 ready：

```scala
io.readline.ready := !(wrl_bank_conflict)
io.read.zipWithIndex.map { case(x, i) =>
  x.ready := !(wr_bank_conflict(i) || rrhazard)
}
```

### 6.6 性能计数器

BankedDataArray 内置了丰富的性能计数器用于监控冲突频率：

- `data_array_multi_read` — 多端口同时读的次数
- `data_array_rr_bank_conflict_X_Y` — 端口 X 与 Y 之间的 read-read 冲突次数
- `data_array_rrl_bank_conflict_X` — 端口 X 与 readline 的冲突次数
- `data_array_rw_bank_conflict_X` — 端口 X 与写操作的冲突次数
- `data_array_read_X` — 端口 X 的有效读次数
- `data_array_read_line` — readline 有效次数
- `data_array_write` — 写操作有效次数
- `data_array_fake_rr_bank_conflict_X_Y` — 假冲突次数
- `data_read_counter` — SRAM 实际读使能计数

此外，还通过 `ChiselDB` 实现了 `BankConflictDB` 日志，记录详细的冲突地址、set index、bank index 和 way index。

---

## 7. DuplicatedDataArray 设计 (Duplicated Data Array)

### 7.1 文件位置

`src/main/scala/xiangshan/cache/dcache/data/DuplicatedDataArray.scala` (172 行)

### 7.2 设计特点

`DuplicatedDataArray` 是 DCache Data Array 的早期设计实现，继承自 `AbstractDataArray`。其核心设计特点是：

- **单端口 SRAM**（`singlePort = true`）：读写不能在同一周期进行
- **固定 3 个读端口**：与 `AbstractDataArray` 的接口一致
- **独立的 Data 和 ECC 存储**：数据使用 `DataSRAMGroup`（每 way 一个 SRAM），ECC 使用独立的 `SRAMTemplate`（nWays way, rowWords wide）
- **way 选择在读出后**：所有 way 同时读出，通过 `Mux1H` 和 way_en 选择目标 way

### 7.3 DataSRAMGroup 内部结构

```scala
class DataSRAMGroup extends Module {
  val data_array = Seq.fill(nWays) {
    Module(new SRAMTemplate(
      Bits(rowBits.W), set = nSets, way = 1,
      shouldReset = false, holdRead = false, singlePort = singlePort
    ))
  }
  // 读出后通过 Mux1H 选择 way
  val data_left  = Mux1H(r_way_en_reg.tail(half), data_read.take(half))
  val data_right = Mux1H(r_way_en_reg.head(half), data_read.drop(half))
  io.rdata := Mux(sel_low, data_left, data_right)
}
```

读选择采用两级 Mux1H：先分半选择（低 half 和高 half），再最终选择。这是一种折中方案，相比完全并行的 Mux1H 节省了面积。

### 7.4 读写仲裁

```scala
// 当 readHighPriority = false 时
val rwhazard = if (singlePort) io.write.valid
               else io.write.valid && waddr === raddr
io.read(j).ready := !rwhazard  // 读写冲突时，读被 stall
io.write.ready := true.B       // 写总是 ready
```

对于单端口 SRAM，任何读写同时发生都会产生 hazard，写操作优先。

### 7.5 ECC 存储结构

DuplicatedDataArray 使用独立的 ECC SRAM 存储校验位：

```scala
val ecc_array = Module(new SRAMTemplate(
  Vec(rowWords, Bits(eccBits.W)),
  set = nSets, way = nWays,
  shouldReset = false, holdRead = false, singlePort = singlePort
))
```

ECC 的编码和解码与 BankedDataArray 不同：

```scala
// 写入时：从 row 数据中计算 ECC
def getECCFromRow(row: UInt) = {
  VecInit((0 until rowWords).map { w =>
    val word = row(wordBits * (w + 1) - 1, wordBits * w)
    getECCFromEncWord(cacheParams.dataCode.encode(word))
  })
}

// 读出时：合并 data 和 ecc 并解码
val data = Cat(ecc_resp_chosen(k), data_resp_chosen(k))
row_error(r)(k) := dcacheParameters.dataCode.decode(data).error && RegNext(rmask(r))
```

### 7.6 与 BankedDataArray 的对比

| 特性 | DuplicatedDataArray | BankedDataArray |
|------|---------------------|-----------------|
| SRAM 类型 | 单端口 / 双端口 | 单端口 |
| Bank 组织 | 无 bank 交织 | 8-bank 交织 |
| ECC 存储 | 独立 SRAM | 与数据合并存储 (encDataBits) |
| way 选择 | 读后 Mux1H | 读后 index 选择 |
| 读端口数 | 3 (固定) | LoadPipelineWidth (可配置) |
| 写粒度 | 整行 (rowBits) | 整行 (DCacheSRAMRowBits) |
| 当前使用 | 未实例化 | WPU 未启用时使用 |

### 7.7 当前状态

`DuplicatedDataArray` 在当前 XiangShan 代码中**未被实例化**。`DCacheWrapper.scala` 中仅使用 `BankedDataArray` 或 `SramedDataArray`。该文件保留是为了历史兼容和参考目的。代码中的注释 `def encRowBits = encWordBits * rowWords // for DuplicatedDataArray only` 进一步证实了这一点。

---

## 8. SramedDataArray — WPU 模式下的实现 (WPU-Mode Implementation)

### 8.1 文件位置

`src/main/scala/xiangshan/cache/dcache/data/BankedDataArray.scala` 第 366-657 行

### 8.2 设计概述

`SramedDataArray` 与 `BankedDataArray` 继承自同一个抽象类 `AbstractBankedDataArray`，但存储粒度不同。其核心差异在于每个 SRAM 实例对应一个 `(div_index, bank_index, way_index)` 三元组，而非 `BankedDataArray` 的每个 bank 包含所有 way。

### 8.3 存储结构

```scala
val data_banks = List.tabulate(DCacheSetDiv)(k => {
  val banks = List.tabulate(DCacheBanks)(i =>
    List.tabulate(DCacheWays)(j => Module(new DataSRAM(i, j)))
  )
  banks
})
```

每个 `DataSRAM` 是一个独立的 `SRAMTemplate` 实例：

```scala
class DataSRAM(bankIdx: Int, wayIdx: Int) extends DCacheModule {
  val data_sram = Module(new SRAMTemplate(
    Bits(encDataBits.W),
    set = DCacheSets / DCacheSetDiv,
    way = 1,
    shouldReset = false, holdRead = false,
    singlePort = true,
    hasMbist = hasMbist, hasSramCtl = hasSramCtl
  ))
}
```

总 SRAM 实例数：`DCacheSetDiv(1) * DCacheBanks(8) * DCacheWays(8) = 64` 个（对比 `BankedDataArray` 的 `1 * 8 = 8` 个 `DataSRAMBank`，但每个 `DataSRAMBank` 内部也有 8 个 SRAM，所以实际上 SRAM 数量相同，但组织方式不同）。

### 8.4 WPU 下 way 使能的差异

在 `SramedDataArray` 中，readline 需要读出所有 way：

```scala
val line_way_en = Fill(DCacheWays, 1.U) // readline 需要读所有 way
```

而 Load Pipeline 的读使能直接与 way_en 关联：

```scala
val loadpipe_en = WireInit(VecInit(List.tabulate(LoadPipelineWidth)(i => {
  io.read(i).valid && div_addrs(i) === div_index.U &&
  (bank_addrs(i)(0) === bank_index.U ||
   bank_addrs(i)(1) === bank_index.U && io.is128Req(i)) &&
  way_en(i)(way_index) &&     // WPU 预测的 way 才使能
  !rr_bank_conflict_oldest(i)
})))
```

### 8.5 冲突检测差异

`SramedDataArray` 额外检查 way_en 一致性：

```scala
// SramedDataArray: 需要 way_en 相同才算 bank conflict
rr_bank_conflict(x)(y) = ... && io.read(x).bits.way_en === io.read(y).bits.way_en

// BankedDataArray: 不检查 way_en（因为同一 bank 内所有 way 同时读出）
rr_bank_conflict(x)(y) = ... // 不含 way_en 比较
```

这是因为 `SramedDataArray` 的 way 使能直接控制 SRAM 读使能，不同 way_en 意味着读不同的 SRAM，不会产生 bank conflict。

---

## 9. Data ECC 实现 (Data ECC Implementation)

### 9.1 ECC 编码配置

ECC 保护通过 `DCacheParameters` 中的 `dataECC` 参数启用：

```scala
case class DCacheParameters(
  ...
  dataECC: Option[String] = None,  // 默认关闭
  ...
)
```

当启用时（`EnableDataEcc = true`），ECC 编码由 `cacheParams.dataCode`（类型为 `Code`）实现。

### 9.2 编码位宽计算

```scala
val encDataBits = if (EnableDataEcc) cacheParams.dataCode.width(DCacheSRAMRowBits)
                  else DCacheSRAMRowBits
val dataECCBits = encDataBits - DCacheSRAMRowBits
```

当 `DCacheSRAMRowBits = 64` 时，如果使用 SECDED（Single Error Correction, Double Error Detection），`encDataBits` 约为 72 bits（64 data + 8 ECC）。ECC bits 存储在 SRAM 数据的高位。

### 9.3 BankedDataArray 中的 ECC 编码与存储

**写入时编码**：

```scala
val write_ecc_reg = RegEnable(
  getECCFromEncWord(cacheParams.dataCode.encode(io.write.bits.data(bank_index))),
  io.write.valid
)
data_bank.io.w.data := asECCData(write_ecc_reg, write_data_reg(bank_index))
```

ECC 编码过程：
1. 在写请求有效时，对每个 bank 的数据执行 `dataCode.encode()` 编码
2. 通过 `getECCFromEncWord` 提取 ECC 部分（高位）
3. 通过 `asECCData` 将 ECC 和原始数据拼接：`Cat(ecc, data)`

**读取时解码**：

```scala
if (EnableDataEcc) {
  val ecc_data = bank_result(...).asECCData()
  val ecc_data_delayed = RegEnable(ecc_data, RegNext(read_enable))
  bank_result(...).error_delayed :=
    dcacheParameters.dataCode.decode(ecc_data_delayed).error
}
```

ECC 解码过程：
1. 从 SRAM 读出的 `encDataBits` 位数据中分离 ECC 和 raw_data
2. 拼接为 `asECCData()` 格式
3. 延迟一拍后执行 `dataCode.decode()` 解码
4. 检查 `.error` 标志位判断是否有数据损坏

### 9.4 ECC 错误上报

ECC 错误检测结果通过多个路径上报：

**Load Pipeline 路径**：

```scala
io.read_error_delayed(i)(j) := rr_read_fire &&
  read_bank_error_delayed(rr_div_addr)(rr_bank_addr(j))(rr_way_addr) &&
  !RegNext(io.bank_conflict_slow(i))  // 冲突时不上报错误
```

**Readline 路径**：

```scala
io.readline_error := readline_error.asUInt.orR
io.readline_error_delayed := readline_error_delayed.asUInt.orR
```

注意：`error_delayed` 比 `read_resp` 晚 1-2 拍（因为额外经过了 ECC 解码流水线寄存器），这在 `L1BankedDataReadResult` 中有明确注释：`val error_delayed = Bool() // 1 cycle later than data resp`。

### 9.5 Pseudo Error 注入

系统支持通过 L1 Cache Controller 注入伪 ECC 错误进行测试：

```scala
if (outer.cacheCtrlOpt.nonEmpty && EnableDataEcc) {
  val ctrlUnit = outer.cacheCtrlOpt.head.module
  bankedDataArray.io.pseudo_error <> ctrlUnit.io_pseudoError(1)
}
```

注入通过 `pseudo_data_toggle_mask` 实现，在读数据上异或掩码来模拟数据翻转：

```scala
val pseudo_data_toggle_mask = io.pseudo_error.bits.map {
  case bank => Mux(io.pseudo_error.valid && bank.valid, bank.mask, 0.U)
}
bank_result(...).raw_data :=
  getDataFromEncWord(data_bank.io.r.data(way_index)) ^
  Mux(mbistAck, 0.U, pseudo_data_toggle_mask(bank_index))
```

---

## 10. WPU (Way Prediction Unit) 集成 (WPU Integration)

### 10.1 WPU 概述

Way Prediction Unit (WPU) 是一种通过预测目标 way 来减少 SRAM 读取功耗的技术。传统方式需要读出所有 way 的数据再选择，WPU 则先预测 way，只读取预测的 way（或少数几个 way），从而大幅降低动态功耗。

### 10.2 WPU 对 Data Array 的影响

当 WPU 启用时（`dwpuParam.enWPU = true`），DCache Data Array 切换为 `SramedDataArray`：

```scala
val bankedDataArray = if(dwpuParam.enWPU) Module(new SramedDataArray)
                      else Module(new BankedDataArray)
```

### 10.3 WPU 与 DCacheWrapper 集成

WPU 模块在 `DCacheWrapper.scala` 中实例化并连接：

```scala
if (dwpuParam.enWPU) {
  val dwpu = Module(new DCacheWpuWrapper(LoadPipelineWidth))
  for(i <- 0 until LoadPipelineWidth){
    dwpu.io.req(i) <> ldu(i).io.dwpu.req(0)
    dwpu.io.resp(i) <> ldu(i).io.dwpu.resp(0)
    dwpu.io.lookup_upd(i) <> ldu(i).io.dwpu.lookup_upd(0)
    dwpu.io.cfpred(i) <> ldu(i).io.dwpu.cfpred(0)
  }
  dwpu.io.tagwrite_upd.valid := tagArray.io.write.valid
  dwpu.io.tagwrite_upd.bits.vaddr := tagArray.io.write.bits.vaddr
  dwpu.io.tagwrite_upd.bits.s1_real_way_en := tagArray.io.write.bits.way_en
}
```

WPU 使用 Tag Array 的写入事件进行训练更新，使用 Load Pipeline 的请求进行预测和 lookup。

### 10.4 WPU 配置参数

```scala
def dwpuParam = coreParams.dwpuParameters  // 来自 Parameters.scala

case class WPUParameters(
  enWPU: Boolean = false,
  enCfPred: Boolean = false,
  algoName: String = ...
)
```

### 10.5 Way 使能在 SRAM 粒度的控制

`SramedDataArray` 的核心优势在于 way_en 可以直接控制每个 SRAM 的读使能：

```scala
// 只有被 WPU 预测选中的 way 的 SRAM 才会被使能
val loadpipe_en = WireInit(VecInit(List.tabulate(LoadPipelineWidth)(i => {
  io.read(i).valid && div_addrs(i) === div_index.U &&
  (bank_addrs(i)(0) === bank_index.U ||
   bank_addrs(i)(1) === bank_index.U && io.is128Req(i)) &&
  way_en(i)(way_index) &&     // WPU 预测的 way 才使能读
  !rr_bank_conflict_oldest(i)
})))
```

而在 `BankedDataArray` 中，每个 `DataSRAMBank` 的所有 8 个 way 的 SRAM 会被同时读出，way 选择在数据输出端通过多路选择器完成。

---

## 11. ReadMCP2 时序优化 (ReadMCP2 Timing Optimization)

### 11.1 MCP2 概述

MCP2（Multi-Cycle Path 2）是一种时序优化技术，允许信号跨越多个时钟周期传播。在 DCache Data Array 中，ReadMCP2 主要用于将读地址和控制信号的关键路径拆分为多拍，以满足时序约束。

### 11.2 Duplicated Address 路径

BankedDataArray 引入了 `addr_dup`（重复地址）信号，为 bank 0-3 提供独立的地址路径：

```scala
// L1BankedDataReadReqWithMask 中包含 addr_dup
class L1BankedDataReadReqWithMask extends DCacheBundle {
  val way_en    = Bits(DCacheWays.W)
  val addr      = Bits(PAddrBits.W)
  val addr_dup  = Bits(PAddrBits.W)  // 重复地址
  val bankMask  = Bits(DCacheBanks.W)
  val lqIdx     = new LqPtr
}
```

在 SRAM 读取时，部分 bank 使用 duplicated 地址：

```scala
def DuplicatedQueryBankSeq = Seq(0, 1, 2, 3)

if (DuplicatedQueryBankSeq.contains(bank_index)) {
  data_bank.io.r.addr := bank_set_addr_dup  // bank 0-3 使用 dup 地址
} else {
  data_bank.io.r.addr := bank_set_addr      // bank 4-7 使用原始地址
}
```

这种设计的原理是：
- 原始地址路径（`addr`）经过较复杂的优先级选择逻辑（PriorityMux），用于冲突检测
- Duplicated 地址路径（`addr_dup`）经过较短的路径，直接驱动 SRAM 地址端口
- Bank 0-3 和 Bank 4-7 使用不同的地址路径，分散了时序压力

### 11.3 分离的 Bank 地址计算

```scala
bank_addrs(rport_index)(0) := addr_to_dcache_bank(io.read(rport_index).bits.addr)
bank_addrs_dup(rport_index)(0) := addr_to_dcache_bank(io.read(rport_index).bits.addr_dup)
```

bank 地址分别从原始地址和重复地址计算，确保 SRAM 使能和地址信号的时序独立。

### 11.4 Write-Dup 控制路径

写操作同样采用重复控制信号解决大扇出问题：

```scala
val write_valid_dup_reg = io.write_dup.map(x => RegNext(x.valid))
val write_wayen_dup_reg = io.write_dup.map(x =>
  RegEnable(x.bits.way_en, x.valid))
val write_set_addr_dup_reg = io.write_dup.map(x =>
  RegEnable(addr_to_dcache_div_set(x.bits.addr), x.valid))
val write_div_addr_dup_reg = io.write_dup.map(x =>
  RegEnable(addr_to_dcache_div(x.bits.addr), x.valid))
```

每个 bank 有独立的控制信号副本，避免单个信号驱动 8 个 bank 造成的扇出过大。

### 11.5 流水线寄存器

读结果经过多级寄存器进行流水：

```scala
// r_read_fire: 读请求后 1 拍
val r_read_fire = RegNext(io.read(i).fire)
// rr_read_fire: 读请求后 2 拍
val rr_read_fire = RegNext(r_read_fire)
```

读响应的时序关系：
- **S1（读请求发出）**：SRAM 读使能和地址
- **S2（读数据返回）**：SRAM 数据输出，通过 bank_result 捕获
- **S3（结果选择）**：通过 `r_div_addr`、`r_bank_addr`、`r_way_addr` 选择正确的 bank 和 way 结果

```scala
io.read_resp(i)(j) := bank_result(r_div_addr)(r_bank_addr(j))(r_way_addr)
```

### 11.6 readline 端口的 Stall 机制

readline 支持 stall 以处理时序问题：

```scala
readline_resp(i) := Mux(
  io.readline_can_go | mbist_ack,
  bank_result(...),           // 正常路径
  RegEnable(readline_resp(i), io.readline_stall | mbist_ack)
)
io.readline_resp := RegEnable(readline_resp, io.readline_can_resp | mbist_ack)
```

### 11.7 Way 地址延迟选择

way 的 one-hot 到二进制转换（`OHToUInt`）在 SRAM 读使能之前完成，确保 SRAM 使能和地址路径不依赖于 way 选择：

```scala
// way_en 在 S0 就确定
way_en(rport_index) := io.read(rport_index).bits.way_en
// OHToUInt 在 S1 使用
val r_way_addr = RegEnable(OHToUInt(way_en(i)), io.read(i).fire)
```

---

## 12. MBIST 集成 (MBIST Integration)

### 12.1 MBIST Pipeline

BankedDataArray 集成了 MBIST（Memory Built-In Self-Test）支持：

```scala
val mbistPl = MbistPipeline.PlaceMbistPipeline(1, s"MbistPipeDCacheData", hasMbist)
val mbistSramPorts = mbistPl.map(pl => Seq.tabulate(DCacheSetDiv, DCacheBanks, DCacheWays)(
  (i, j, k) => pl.toSRAM(i * DCacheBanks * DCacheWays + j * DCacheWays + k)
))
```

每个 SRAM 端口有独立的 MBIST 控制信号（`re`、`ack`、`rdata`），在 MBIST 模式下可以绕过正常读写路径直接访问 SRAM。

### 12.2 MBIST 读结果处理

```scala
private val mbist_r_way = OHToUInt(mbistSramPorts.map(
  _.flatMap(_.map(w => Cat(w.map(_.re).reverse))).reduce(_ | _)
).getOrElse(0.U(DCacheWays.W)))
private val mbist_r_div = OHToUInt(mbistSramPorts.map(
  _.map(d => Cat(d.flatMap(w => w.map(_.re))).orR)
).getOrElse(Seq.fill(DCacheSetDiv)(false.B)))
```

MBIST 访问可以旁路正常的数据选择逻辑，直接读取 SRAM 数据。

---

## 13. 源文件位置汇总 (Source File Locations)

| 文件 | 路径 | 行数 | 说明 |
|------|------|------|------|
| AbstractDataArray.scala | `src/main/scala/xiangshan/cache/dcache/data/AbstractDataArray.scala` | 95 | 旧接口抽象类（DuplicatedDataArray 父类） |
| BankedDataArray.scala | `src/main/scala/xiangshan/cache/dcache/data/BankedDataArray.scala` | 994 | 核心文件：AbstractBankedDataArray、SramedDataArray、BankedDataArray |
| DuplicatedDataArray.scala | `src/main/scala/xiangshan/cache/dcache/data/DuplicatedDataArray.scala` | 172 | 冗余副本式数据阵列（历史设计） |
| DCacheWrapper.scala | `src/main/scala/xiangshan/cache/dcache/DCacheWrapper.scala` | ~1700 | DCache 顶层，data array 集成在 line 930-1300 |
| L1Cache.scala | `src/main/scala/xiangshan/cache/L1Cache.scala` | ~110 | L1 Cache 参数定义（wordBits、rowBits、blockRows 等） |
| Parameters.scala | `src/main/scala/xiangshan/Parameters.scala` | - | 全局参数定义（LoadPipelineWidth、dwpuParam 等） |
| WPU.scala | `src/main/scala/xiangshan/cache/wpu/WPU.scala` | - | WPU 算法实现 |
| WPUWrapper.scala | `src/main/scala/xiangshan/cache/wpu/WPUWrapper.scala` | - | DCacheWpuWrapper 封装 |

---

## 14. 关键设计参数汇总 (Key Parameters Summary)

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `DCacheSets` | 64 (nSets) | Set 数量 |
| `DCacheWays` | 8 (nWays) | 相联度 |
| `DCacheBanks` | 8 | Bank 数量（硬编码） |
| `DCacheSetDiv` | 1 | Set 分组数 |
| `DCacheSRAMRowBits` | 64 | SRAM 行位宽（硬编码） |
| `DCacheWordBits` | 64 | 字位宽（硬编码） |
| `LoadPipelineWidth` | 2 或 3 | Load 流水线宽度（取决于核心配置） |
| `blockBytes` | 64 | Cache Block 大小 |
| `rowBits` | 64 | 基本行位宽 |
| `blockRows` | 8 | 每 block 的行数 (64B / 8B) |
| `rowWords` | 1 | 每行字数 (64b / 64b)，用于 DuplicatedDataArray |
| `EnableDataEcc` | false | Data ECC 启用标志 |
| `encDataBits` | 64 或 ~72 | 编码后 SRAM 数据位宽 |
| `dataECCBits` | 0 或 ~8 | ECC 校验位宽 |

---

## 15. 总结 (Summary)

XiangShan DCache Data Array 是一个高度优化的多 bank、多 way 数据存储系统，其设计体现了以下核心理念：

1. **Bank 交织降低冲突**：8-bank 交织组织使得连续地址访问自然分布在不同 bank，大幅降低 bank conflict 概率。

2. **灵活的实现切换**：通过 `BankedDataArray`（标准方案）和 `SramedDataArray`（WPU 方案）的切换，适应不同的功耗和面积需求。

3. **Oldest-First 仲裁**：基于 Load Queue Index 的冲突仲裁策略确保最早发出的请求优先完成，避免 starvation。

4. **全面的 ECC 保护**：可选的 SECDED ECC 编码保护 SRAM 中的数据完整性，支持运行时错误检测和注入测试。

5. **精细的时序优化**：通过 duplicated address 路径、write_dup 控制信号、多级流水线寄存器等手段，在高频设计中满足时序约束。

6. **丰富的调试支持**：内置性能计数器、ChiselDB 日志、pseudo error 注入等机制，为性能分析和可靠性测试提供完备支持。

7. **WPU 功耗优化**：通过 Way Prediction Unit 和 SRAM 粒度的 way 使能控制，在 WPU 模式下显著降低数据读取功耗。
