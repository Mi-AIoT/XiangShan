# R25A - SRAM Templates & Data Modules 深度分析报告

## 1. 概述

XiangShan 处理器的存储基础设施位于 `utility` 库中，提供了一套分层的 SRAM 模板与数据模块体系。这套体系从底层的 `SramArray` 原语到高层的 `SRAMTemplate`、`FoldedSRAMTemplate`，再到寄存器级的 `DataModuleTemplate` 系列，覆盖了从芯片级 SRAM 编译器集成到纯 RTL 寄存器堆实现的各种存储场景。同时，`IndexableCAMTemplate` 提供了可索引的内容寻址存储器（CAM）功能。

**核心源文件位置：**
- `utility/src/main/scala/utility/sram/SRAMTemplate.scala` -- SRAM 模板主文件，包含 SRAMTemplate、SplittedSRAMTemplate、FoldedSRAMTemplate、SRAMTemplateWithArbiter，以及 SRAMConflictBehavior 枚举
- `utility/src/main/scala/utility/sram/SramProto.scala` -- SRAM 原语协议层，包含 SramArray、SramArray1P、SramArray2P、SramProto
- `utility/src/main/scala/utility/sram/SramHelper.scala` -- SRAM 辅助工具，包含 SramInfo、SramHelper（MBIST 节点管理、命名等）
- `utility/src/main/scala/utility/DataModuleTemplate.scala` -- 寄存器级数据模块，包含 RawDataModuleTemplate、SyncRawDataModuleTemplate、AsyncRawDataModuleTemplate、SyncDataModuleTemplate、DataModuleTemplate、Folded1WDataModuleTemplate
- `utility/src/main/scala/utility/IndexableCAMTemplate.scala` -- 可索引 CAM 模板
- `utility/src/main/scala/utility/package.scala` -- 类型别名定义，将 `utility.sram.SRAMTemplate` 等导出为 `utility.SRAMTemplate`

---

## 2. SRAMTemplate 完整接口与所有参数

`SRAMTemplate` 是 XiangShan 中最核心的 SRAM 封装类，定义在 `utility/src/main/scala/utility/sram/SRAMTemplate.scala` 中。它封装了底层 `SramArray` 原语，提供了统一的、可配置的 SRAM 访问接口。

### 2.1 类签名与构造参数

```scala
class SRAMTemplate[T <: Data](
  gen: T,                                     // 条目数据类型
  set: Int,                                   // 数组深度（set 行数）
  way: Int = 1,                               // 数组路数（默认单路）
  singlePort: Boolean = false,                // 是否为单端口 SRAM（不能同时读写）
  shouldReset: Boolean = false,               // 是否支持复位清零
  extraReset: Boolean = false,                // 是否添加额外复位端口
  holdRead: Boolean = false,                  // 是否保持上一次读结果
  bypassWrite: Boolean = false,               // 读写冲突时是否旁路写数据（向后兼容，推荐用 conflictBehavior）
  conflictBehavior: SRAMConflictBehavior = DefaultBehavior,  // 读写冲突处理策略
  useBitmask: Boolean = false,                // 是否启用按比特掩码写入
  withClockGate: Boolean = false,             // 是否添加读/写端口时钟门控
  separateGateClock: Boolean = false,         // 无实际效果，仅 API 兼容
  hasMbist: Boolean = false,                  // 是否启用 MBIST 支持
  latency: Int = 1,                           // 输出建立多周期数（数据在读采样沿后多少周期可用）
  extraHold: Boolean = false,                 // 是否启用额外输入保持周期
  extClockGate: Boolean = false,              // 是否使用外部时钟门控
  hasSramCtl: Boolean = false,                // 是否启用 SRAM 控制信号
  suffix: Option[String] = None               // SRAM wrapper 名称后缀
)(implicit valName: sourcecode.FullName)
```

### 2.2 IO 端口定义

```scala
val io = IO(new Bundle {
  val r = Flipped(new SRAMReadBus(gen, set, way))     // 读端口
  val w = Flipped(new SRAMWriteBus(gen, set, way, useBitmask))  // 写端口
  val broadcast = if(hasMbist) Some(new SramBroadcastBundle) else None  // MBIST 广播信号
  val mbistCgCtl = if(hasMbist && extClockGate) Some(...) else None     // MBIST 时钟门控控制
  val resetDone = Output(Bool())                          // 复位完成信号
})
```

### 2.3 参数约束与内部处理

- `require(latency >= 1)` -- 延迟至少为 1 周期
- 单端口模式下不允许设置 `conflictBehavior`（因为单端口 SRAM 天然不存在读写冲突）
- `bypassWrite` 与 `conflictBehavior` 互斥，二者不能同时设置
- `finalConflictBehavior = if (bypassWrite) BypassWrite else conflictBehavior` -- 向后兼容处理

### 2.4 SRAM 类型层次

底层通过 `SramInfo` 计算 MBIST 参数：

```scala
val sp = SramInfo(arrayWidth, arrayPortSize, hasMbist)
```

其中 `arrayWidth` 在 `useBitmask` 模式下为 1（每个比特独立掩码），否则为 `gen.getWidth`。`arrayPortSize` 在 `useBitmask` 模式下为 `way * gen.getWidth`，否则为 `way`。

---

## 3. 读写端口配置与时序

### 3.1 SRAMBundleA（地址 Bundle）

```scala
class SRAMBundleA(val set: Int) extends Bundle {
  val setIdx = Output(UInt(log2Up(set).W))
}
```

### 3.2 SRAMBundleAW（写地址+数据 Bundle）

继承自 `SRAMBundleA`，增加了写数据和掩码：

```scala
class SRAMBundleAW[T <: Data](gen: T, set: Int, way: Int = 1, useBitmask: Boolean = false)
  extends SRAMBundleA(set) {
  val data: Vec[T] = Output(Vec(way, gen))
  val waymask: Option[UInt] = if (way > 1) Some(Output(UInt(way.W))) else None
  val flattened_bitmask: Option[UInt] = if (useBitmask) Some(Output(UInt((way * dataWidth).W))) else None
  val bitmask: Option[UInt] = if (useBitmask) Some(Output(UInt(dataWidth.W))) else None
}
```

`SRAMBundleAW` 提供了多个 `apply` 方法重载，支持以下写入模式：
- `apply(data: Vec[T], setIdx: UInt, waymask: UInt)` -- 标准多路写入
- `apply(data: Vec[T], setIdx: UInt, waymask: UInt, bitmask: UInt)` -- 带比特掩码的写入，`flattened_bitmask` 通过 `waymask(n / dataWidth) && bitmask(n % dataWidth)` 计算
- `apply(data: T, setIdx: UInt, waymask: UInt)` -- 单路写入，自动扩展为 `VecInit(Seq.fill(way)(data))`

### 3.3 SRAMBundleR（读返回 Bundle）

```scala
class SRAMBundleR[T <: Data](gen: T, way: Int = 1) extends Bundle {
  val data = Output(Vec(way, gen))
}
```

### 3.4 SRAMReadBus

使用 Chisel `Decoupled` 接口：

```scala
class SRAMReadBus[T <: Data](gen: T, set: Int, way: Int = 1) extends Bundle {
  val req = Decoupled(new SRAMBundleA(set))
  val resp = Flipped(new SRAMBundleR(gen, way))
}
```

读端口的 `ready` 信号由 SRAMTemplate 内部控制：
- 单端口模式：当写操作进行时 `ready` 为 false（`singleHold`）
- 复位期间：`ready` 为 false（`resetHold`）
- 冲突处理：根据 `conflictBehavior` 可能拉低 `ready`（`conflictStallRead`）

### 3.5 SRAMWriteBus

```scala
class SRAMWriteBus[T <: Data](gen: T, set: Int, way: Int = 1, useBitmask: Boolean = false)
  extends Bundle {
  val req = Decoupled(new SRAMBundleAW(gen, set, way, useBitmask))
}
```

写端口的 `ready` 信号受以下因素影响：
- 复位期间：`ready` 为 false
- 冲突处理：根据 `conflictBehavior` 可能拉低 `ready`（`conflictStallWrite`）

### 3.6 时序模型

SRAMTemplate 的读取时序由以下参数控制：

1. **latency 参数** -- 控制读数据输出延迟。当 `latency = 1` 时，读请求后下一个周期数据可用。`respReg` 是一个移位寄存器，用于跟踪数据何时可供捕获。

2. **holdRead 参数** -- 当启用时，读端口输出通过 `HoldUnless` 保持上一次的有效读取结果，直到新的读取完成。

3. **extraHold 参数** -- 启用时要求外部逻辑在输入端保持信号一个周期。内部通过检测 `rckEn` 从高变低的边沿来控制时钟门控。

4. **时钟门控**（`withClockGate` / `extClockGate`）：
   - 内部门控：使用 `MbistClockGateCell` 模块，分别控制读端口时钟 `rcg` 和写端口时钟 `wcg`
   - 外部门控：将时钟门控信号暴露到 IO（`mbistCgCtl`），供外部 MBIST 控制器使用
   - 单端口模式：读写共用一个时钟门控
   - 双端口模式：读写各有独立的时钟门控

---

## 4. ConflictBehavior 冲突处理策略

`SRAMConflictBehavior` 是一个 Scala 枚举，定义了双端口 SRAM 读写同地址冲突时的处理策略。它通过 `macroAllowsConflict` 字段标识底层 SRAM 编译器是否支持冲突。

```scala
object SRAMConflictBehavior extends Enumeration {
  protected case class BehaviorVal(macroAllowsConflict: Boolean) extends super.Val
  type SRAMConflictBehavior = Value
  val CorruptRead       = BehaviorVal(true)
  val CorruptReadWay    = BehaviorVal(true)
  val BypassWrite       = BehaviorVal(true)
  val AssertionFail     = BehaviorVal(false)
  val BufferWrite       = BehaviorVal(false)
  val BufferWriteLossy  = BehaviorVal(false)
  val BufferWriteLossyFast = BehaviorVal(false)
  val StallWrite        = BehaviorVal(false)
  val StallRead         = BehaviorVal(false)
  val DefaultBehavior   = CorruptReadWay  // 默认策略
}
```

### 4.1 各策略详解

#### CorruptRead
- **macroAllowsConflict = true**
- 允许读写冲突，所有路的读数据被污染
- 实现：`bypassMask := Fill(way, 1.U(1.W))`，`bypassData := randomData`
- 读到的全部是 LFSR 生成的随机数据，保证不会泄漏真实数据（安全设计）

#### CorruptReadWay（默认策略）
- **macroAllowsConflict = true**
- 允许读写冲突，仅被写入的路（way）读数据被污染
- 实现：`bypassData := randomData`，但 `bypassMask` 保持为冲突写掩码
- 未被写入的路仍能返回正确数据，精度高于 CorruptRead

#### BypassWrite
- **macroAllowsConflict = true**
- 允许读写冲突，将写数据旁路到读端口
- 前提是底层 SRAM 宏支持冲突且行为类似 CorruptReadWay（否则无法恢复未被写入路的数据）
- 在 SRAMTemplate 中，旁路逻辑直接将 `conflictWdataS1` 和 `conflictWmaskS1` 传递给输出

#### AssertionFail
- **macroAllowsConflict = false**
- 不允许读写冲突，一旦发生触发 Chisel `assert` 失败
- 实现：`bypassEnable := false.B`，冲突时触发断言

#### BufferWrite
- **macroAllowsConflict = false**
- 允许读写冲突，底层宏不支持冲突
- 冲突时：当前周期将写数据保存到单周期缓冲区（`conflictValidS1`）
- 下一周期：stall 外部读写，将缓冲区内容写入 RAM（`conflictBufferCanWrite := true.B`）
- 要求外部逻辑支持读写 stall

#### BufferWriteLossy
- **macroAllowsConflict = false**
- 与 BufferWrite 类似，但不需要 stall
- 缓冲区内容保持有效，直到能无冲突地写入 RAM
- 如果缓冲区仍有效且发生第二次冲突，新数据会覆盖缓冲区内容（丢失第一次写入！）
- 时序比 BufferWrite 差，但不需要外部 stall 支持

#### BufferWriteLossyFast
- **macroAllowsConflict = false**
- 与 BufferWriteLossy 相同的语义，但通过允许更频繁地丢失缓冲区内容来改善时序
- 实现差异：更早触发 `conflictInhibitWrite`（在 S0 阶段而非 S1），并将更多写操作重定向到缓冲区

#### StallWrite
- **macroAllowsConflict = false**
- 不允许冲突，冲突时 stall 写端口
- 实现：`conflictInhibitWrite := conflictValidS0`（阻止冲突周期的写入），`conflictStallWrite := conflictValidS0`
- 注意：信号选择对时序不太友好

#### StallRead
- **macroAllowsConflict = false**
- 不允许冲突，冲突时 stall 读端口
- 实现：`conflictInhibitWrite := conflictValidS0`，`conflictStallRead := conflictValidS0`

### 4.2 冲突检测流水线

SRAMTemplate 内部实现了两级冲突检测流水线：

- **S0 阶段**：`conflictEarlyS0 = r.req.valid && w.req.valid`，检测读写是否同时发起
- **S1 阶段**：`conflictValidS1 = conflictEarlyS1 && conflictWmaskS1.orR && conflictRaddrS1 === conflictWaddrS1`，检测是否同地址冲突
- 冲突缓冲区：`conflictBufferValid`、`conflictBufferCanWrite`、`conflictBufferWrite` 协调缓冲区读写

### 4.3 旁路输出合并

最终读数据通过旁路掩码合并：

```scala
val finalBypassMask = Fill(way, bypassEnable) & bypassMask.asTypeOf(UInt(way.W))
val mem_rdata = VecInit(finalBypassMask.asBools.zip(raw_rdata).zip(bypassData).map {
  case ((m, r), w) => Mux(m, w, r)
})
```

对于 `macroAllowsConflict = false` 的策略（BufferWrite、StallWrite、StallRead），当冲突确实发生时会触发 Chisel assert。

---

## 5. DataModuleTemplate 变体

`DataModuleTemplate.scala` 定义了六个模块，形成从原始（Raw）到同步（Sync）、从单周期到多周期的完整变体谱系。

### 5.1 RawDataModuleTemplate -- 原始数据模块（热编码寻址）

```scala
class RawDataModuleTemplate[T <: Data](
  gen: T,
  numEntries: Int,
  numRead: Int,       // 读端口数
  numWrite: Int,      // 写端口数
  isSync: Boolean,    // 是否同步（输入地址延迟一拍）
  optWrite: Seq[Int] = Seq(),  // 哪些写端口启用优化（延迟一拍写入 + 旁路）
  hasRen: Boolean = false      // 是否有读使能信号
)
```

**关键特性：**
- 使用**热编码**（one-hot）地址而非二进制地址（`rvec`/`wvec` 为 `numEntries.W` 宽）
- 通过 `Mux1H` 实现多路读取，通过 `PopCount(rvec(i)) <= 1.U` 断言确保同一读端口只有一个热位
- 写冲突同样通过 `PopCount(w) <= 1.U` 断言保证
- `optWrite` 机制：被索引的写端口通过 `GatedValidRegNext` 延迟一拍，并在下一周期为对应读端口提供旁路（write-first 语义）
- `isSync = true` 时，读地址通过 `RegNext` 延迟一拍（同步读）；`isSync = false` 时，读地址直接使用（异步读/组合逻辑读）

### 5.2 SyncRawDataModuleTemplate -- 同步原始数据模块

```scala
class SyncRawDataModuleTemplate[T <: Data](
  gen: T, numEntries: Int, numRead: Int, numWrite: Int, optWrite: Seq[Int] = Seq()
) extends RawDataModuleTemplate(gen, numEntries, numRead, numWrite, true, optWrite)
```

`RawDataModuleTemplate` 的 `isSync = true` 特化版本。读地址延迟一拍后才采样，适用于需要同步读时序的场景。

### 5.3 AsyncRawDataModuleTemplate -- 异步原始数据模块

```scala
class AsyncRawDataModuleTemplate[T <: Data](
  gen: T, numEntries: Int, numRead: Int, numWrite: Int, optWrite: Seq[Int] = Seq()
) extends RawDataModuleTemplate(gen, numEntries, numRead, numWrite, false, optWrite)
```

`RawDataModuleTemplate` 的 `isSync = false` 特化版本。读地址不经过寄存器，读数据通过组合逻辑立即输出，适用于需要零延迟读取的场景（如 Issue 队列的数据读取）。

### 5.4 DataModuleTemplate -- 基础数据模块（二进制编码寻址）

```scala
class DataModuleTemplate[T <: Data](
  gen: T,
  numEntries: Int,
  numRead: Int,
  numWrite: Int,
  parentModule: String,   // 用于生成模块名
  perReadPortBypassEnable: Option[Seq[Boolean]] = None  // 每个读端口是否启用旁路
)
```

**与 RawDataModuleTemplate 的区别：**
- 使用**二进制编码**地址（`raddr`/`waddr` 为 `log2Ceil(numEntries).W` 宽），而非热编码
- 通过 `UIntToOH` 和 `Mux1H` 实现读写
- 内置 read-after-write 旁路逻辑：如果某个写端口与读端口同地址且写使能，直接旁路写数据
- 通过 `perReadPortBypassEnable` 可精细控制每个读端口的旁路行为
- 模块命名为 `DataModule_${parentModule}_${numEntries}entry`

### 5.5 SyncDataModuleTemplate -- 同步分块数据模块

```scala
class SyncDataModuleTemplate[T <: Data](
  gen: T,
  numEntries: Int,
  numRead: Int,
  numWrite: Int,
  parentModule: String = "",
  concatData: Boolean = false,              // 是否将数据类型展平为 UInt
  perReadPortBypassEnable: Option[Seq[Boolean]] = None,
  hasRen: Boolean = false
)
```

**核心设计 -- 分块（Banking）机制：**
- 当 `numEntries >= 128` 时，每块最大 64 条目；否则每块 16 条目
- 总块数 `numBanks = ceil(numEntries / maxBankEntries)`
- 地址分为 bank offset（低 `log2Ceil(maxBankEntries)` 位）和 bank index（高位）
- 每个 bank 是一个 `DataModuleTemplate` 实例，只在其 `bankIndex` 匹配时响应写入
- 读取时通过 `UIntToOH` 解码 bank index，`Mux1H` 选择对应 bank 的输出
- 所有输入信号都通过寄存器延迟一拍（`RegNext`/`RegEnable`），确保同步时序
- 当 `hasRen = true` 时，只有读使能为真才采样读地址

**使用示例（StoreSet）：**
```scala
val valid_array = Module(new SyncDataModuleTemplate(
  Bool(), SSITSize, SSIT_READ_PORT_NUM, SSIT_WRITE_PORT_NUM, hasRen = true))
```

### 5.6 Folded1WDataModuleTemplate -- 折叠式单写端口数据模块

```scala
class Folded1WDataModuleTemplate[T <: Data](
  gen: T, numEntries: Int, numRead: Int,
  isSync: Boolean,           // 是否同步
  width: Int,                // 折叠宽度（必须为 2 的幂，且 numEntries % width == 0）
  hasResetEn: Boolean = true, // 是否有复位使能
  hasRen: Boolean = false     // 是否有读使能
)
```

**设计原理：**
- 将 `numEntries` 条目折叠为 `nRows = numEntries / width` 行，每行存储 `width` 个条目
- 使用 Chisel `Mem`（而非 `Reg Vec`）作为存储介质，写入通过掩码按字节/字写入
- 复位期间逐行清零（`resetRow` 从 0 计数到 `nRows-1`）
- 读取时：`addr = raddr >> log2Ceil(width)` 选行，`idx = raddr(log2Ceil(width)-1, 0)` 选列
- 写入时：`waddr >> log2Ceil(width)` 选行，`UIntToOH(waddr(log2Ceil(width)-1, 0))` 生成写掩码

**Folded 1W 的典型应用：** 需要少量写端口但较大存储深度的场景，如分支预测表的计数器存储。

---

## 6. SplittedSRAMTemplate 与 FoldedSRAMTemplate

### 6.1 SplittedSRAMTemplate -- 分片 SRAM 模板

```scala
class SplittedSRAMTemplate[T <: Data](
  gen: T, set: Int, way: Int = 1,
  setSplit: Int = 1,   // set 维度分片数
  waySplit: Int = 1,   // way 维度分片数
  dataSplit: Int = 1,  // data 维度分片数
  // ... 其余参数与 SRAMTemplate 相同
)
```

**设计思想：**
将大型 SRAM 拆分为多个小型 SRAM 实例（`setSplit x waySplit x dataSplit`），每个实例是独立的 `SRAMTemplate`。

**地址分解：**
- `r_setIdx` / `w_setIdx`：高位部分作为 bank 内偏移
- `r_bankSel` / `w_bankSel`：低位部分用于选择 set 分片
- way 分片：waymask 按分片宽度切割
- data 分片：数据按位宽切割

**数据重组：**
```scala
val allData = (0 until setSplit).map(i =>
  VecInit((0 until waySplit).flatMap(j =>
    (0 until innerWays).map(w =>
      Cat((0 until dataSplit).map(k => array(i)(j)(k).io.r.resp.data(w).asUInt).reverse)
    )
  ))
)
io.r.resp.data := Mux1H(ren_vec, allData).asTypeOf(Vec(way, gen))
```

**使用示例（MetaQueue、AheadBtbBank、ICacheMetaInterleavedBank）：**
```scala
private val sram = Module(new SplittedSRAMTemplate(
  gen = new MetaEntry, set = FtqSize, way = 1,
  dataSplit = 2, singlePort = false, withClockGate = true))
```

### 6.2 FoldedSRAMTemplate -- 折叠 SRAM 模板

```scala
class FoldedSRAMTemplate[T <: Data](
  gen: T, set: Int, width: Int = 4, way: Int = 1,
  setSplit: Int = 1, waySplit: Int = 1, dataSplit: Int = 1,
  // ... 其余参数
)
```

**设计原理：**
- 将 `set` 维度折叠为 `nRows = set / width` 行
- 内部使用 `SplittedSRAMTemplate`，其 way 数为 `width * way`
- 读取时：高 `log2Ceil(width)` 位用于从折叠的行中选择正确的 way 切片
- 写入时：生成合并掩码，控制 `width * way` 维度中的写入

**地址分解（`setIdx` 位域）：**
```
|<----- setIdx ----->|
| ridx | width | way |
```

**使用示例（TageTable、IttageTable）：**
```scala
Module(new FoldedSRAMTemplate(
  UsefulCounter(), set = NumSets,
  width = NumUsefulCtrSramFolds,
  way = 1, singlePort = true))
```

FoldedSRAMTemplate 在分支预测器中广泛使用，因为预测表通常需要大 set 数但只有少量 way，折叠可以显著减少 SRAM 实例数和面积。

### 6.3 SRAMTemplateWithArbiter -- 带仲裁器的 SRAM 模板

```scala
class SRAMTemplateWithArbiter[T <: Data](
  nRead: Int,    // 读端口数
  gen: T, set: Int, way: Int = 1,
  shouldReset: Boolean = false,
  hasMbist: Boolean = false,
  latency: Int = 1,
  hasSramCtl: Boolean = false
)
```

**设计原理：**
- 内部使用**单端口** `SRAMTemplate`
- 多个读端口通过 `Arbiter` 仲裁到唯一的物理读端口
- 每个读端口通过 `HoldUnless` 保持自己的读结果
- 适用于不需要同时读取多个端口、但需要多端口接口的场景

---

## 7. IndexableCAMTemplate 设计

`IndexableCAMTemplate` 定义在 `utility/src/main/scala/utility/IndexableCAMTemplate.scala` 中，实现了可索引的内容寻址存储器。

### 7.1 接口定义

```scala
class IndexableCAMTemplate[T <: Data](
  val gen: T,              // 条目数据类型
  val set: Int,            // CAM 条目数
  val readWidth: Int,      // 并行读端口数
  val isIndexable: Boolean = false  // 是否支持索引读取
)(implicit p: Parameters)
```

**IO 端口：**
```scala
val io = IO(new Bundle {
  val r = new Bundle {
    val req = Input(Vec(readWidth, gen))           // 每个读端口的比较输入
    val resp = Output(Vec(readWidth, Vec(set, Bool())))  // 每个读端口的匹配结果（热编码）
  }
  val w = Input(new Bundle {
    val valid = Bool()
    val bits = new Bundle {
      val index = UInt(log2Up(set).W)  // 写入索引
      val data = gen                    // 写入数据
    }
  })
  val rdata = if(isIndexable) Some(Output(gen)) else None     // 索引读取数据
  val ridx = if(isIndexable) Some(Input(UInt(log2Up(set).W))) else None  // 索引读取地址
})
```

### 7.2 实现机制

- 内部使用 `Reg(Vec(set, wordType))` 存储所有条目
- **读操作（比较）：** 对每个读端口，将输入与所有条目进行并行比较：
  ```scala
  a := array.map(io.r.req(i).asUInt === _)
  ```
- **写操作：** 按索引写入：
  ```scala
  when(io.w.valid) { array(io.w.bits.index) := io.w.bits.data.asUInt }
  ```
- **索引读取（可选）：** 当 `isIndexable = true` 时，可通过 `ridx` 直接按索引读取数据

### 7.3 与 MMU 中 CAMTemplate 的区别

XiangShan 的 MMU（`src/main/scala/xiangshan/cache/mmu/MMUBundle.scala`）中也定义了一个 `CAMTemplate`，它与 `IndexableCAMTemplate` 的核心比较逻辑相同，但没有 `isIndexable` 功能。`IndexableCAMTemplate` 是其增强版本，额外支持按索引直接读取条目数据。

### 7.4 应用场景

CAM 模板在 TLB（Translation Lookaside Buffer）和各种关联查找表中使用，支持在单周期内完成全表并行匹配。

---

## 8. SramArray 与 SramProto 底层实现

### 8.1 SramArray 抽象基类

`SramArray` 是所有 SRAM 实现的抽象基类，定义在 `utility/src/main/scala/utility/sram/SramProto.scala` 中。

**抽象方法（参数）：**
```scala
abstract class SramArray(
  depth: Int,              // 深度
  width: Int,              // 位宽
  maskSegments: Int,       // 掩码段数
  hasMbist: Boolean,
  hasSramCtl: Boolean,
  sramName: Option[String] = None,
  singlePort: Boolean      // 单/双端口
) extends RawModule
```

**IO 端口定义：**
- **单端口模式（`singlePort = true`）：** `RW0` -- 包含 `clk`、`addr`、`en`、`wmode`、`wmask`、`wdata`、`rdata`
- **双端口模式（`singlePort = false`）：** `R0`（读端口）+ `W0`（写端口），各自独立时钟
- MBIST 信号：`mbist: SramMbistIO`（`dft_ram_bypass`、`dft_ram_bp_clken`）
- SRAM 控制信号：`ram_ctl: UInt(64.W)`

### 8.2 SramArray1P -- 单端口 SRAM 实现

使用 Chisel `SyncReadMem` + `readWrite` 方法：
- 有掩码时：`array.readWrite(addr, wdata, wmask.asBools, en, wmode, clk)`
- 无掩码时：`array.readWrite(addr, wdata, en, wmode, clk)`
- 读写在同一时钟沿，通过 `wmode` 区分读/写操作

### 8.3 SramArray2P -- 双端口 SRAM 实现

- 写端口：`array.write(addr, data, mask.asBools, clk)`
- 读端口：`array.read(addr, en, clk)`
- 使用 `SyncReadMem.WriteFirst` 策略（有掩码时），即同地址读写时读取写入的数据

### 8.4 SramProto 工厂对象

```scala
object SramProto {
  def apply(clock, singlePort, depth, width, maskSegments, setup, hold, latency,
            writeClock, hasMbist, hasSramCtl, suffix): (Instance[SramArray], String)
}
```

**核心功能：**
- 通过 `Definition` + `Instance`（Chisel hierarchy）实现 SRAM 定义复用
- SRAM 命名格式：`sram_array_{numPort}p{depth}x{width}m{maskWidth}s{setup}h{hold}l{latency}{mbist}_{suffix}`
- 使用 `defMap` 缓存相同参数的 SRAM 定义，避免重复实例化

### 8.5 SramInfo -- MBIST 节点参数计算

`SramInfo` 负责计算 MBIST 接口的节点数、数据宽度和掩码宽度：
- **Nto1 模式：** 当 `dataBits > maxMbistDataWidth` 时，将一个 way 拆分为多个 MBIST 节点
- **1toN 模式：** 当 `dataBits <= maxMbistDataWidth` 时，将多个 way 合并到一个 MBIST 节点
- `mbistMaskConverse` 和 `funcMaskConverse` 负责 MBIST 掩码格式与功能掩码格式之间的转换

---

## 9. SRAMTemplate 在代码库中的使用模式

### 9.1 Cache 子系统

**Dcache 数据阵列（BankedDataArray）：**
```scala
val data_sram = Module(new SRAMTemplate(
  Bits(encDataBits.W), set = DCacheSets / DCacheSetDiv, way = 1,
  shouldReset = false, holdRead = false, singlePort = false,
  hasMbist = hasMbist, hasSramCtl = hasSramCtl))
```
- 使用 way=1 但多实例化，手动管理 way 以优化功耗
- 分 bank 设计，每个 bank 独立 SRAM

**Dcache 标签约阵列（TagArray）：**
```scala
val tag_array = Module(new SRAMTemplate(UInt(encTagBits.W), set = nSets, way = DCacheWayDiv,
  shouldReset = false, holdRead = false, singlePort = true, withClockGate = true,
  hasMbist = hasMbist, hasSramCtl = hasSramCtl, suffix = Some("dcsh_tag")))
```
- 单端口模式，带时钟门控
- 使用 suffix 指定 SRAM 名称

**Dcache 复制数据阵列（DuplicatedDataArray）：**
- 使用 `SRAMTemplateWithArbiter` 实现多读端口仲裁
- 每个 Bank 有独立的 tag 和 data SRAM

### 9.2 前端分支预测

**Tage 表（TageTable）：**
```scala
Module(new SRAMTemplate(new TageEntry, set = NumSets, way = 1,
  singlePort = true, shouldReset = true, holdRead = true))
```
- 单端口、复位清零、保持读结果
- 每个表一个 SRAM

**Tage 有用性计数器（TageTable）：**
```scala
Module(new FoldedSRAMTemplate(UsefulCounter(), set = NumSets,
  width = NumUsefulCtrSramFolds, way = 1, singlePort = true))
```
- 使用 FoldedSRAMTemplate 折叠 set 维度
- 大 set 数、单 way、单端口

**IT-TAGE 表（IttageTable）：**
```scala
Module(new FoldedSRAMTemplate(
  new IttageEntry(tagLen), setSplit = 1, waySplit = 1, dataSplit = dataSplit,
  set = NumSetsPerBank, width = foldedWidth,
  shouldReset = true, holdRead = true, singlePort = true, useBitmask = true))
```
- 启用 `useBitmask` 按比特掩码写入
- Bank 化设计，每个 bank 独立 FoldedSRAMTemplate

**MBTB（MainBtbInternalBank）：**
```scala
// 条目 SRAM
private val entrySrams = Seq.tabulate(NumWay) { i =>
  Module(new SRAMTemplate(new MainBtbEntry, set = NumSets, way = 1,
    singlePort = true, shouldReset = true, holdRead = true,
    withClockGate = true, hasMbist = hasMbist, suffix = Option("bpu_mbtb_entry")))
}
// 计数器 SRAM（单独存储以优化功耗）
private val counterSram = Module(new SRAMTemplate(
  TakenCounter(), set = NumSets, way = NumWay,
  singlePort = true, shouldReset = true, holdRead = true,
  withClockGate = true, hasMbist = hasMbist, suffix = Option("bpu_mbtb_counter")))
```
- 条目和计数器分开存储，更新计数器时不需要读写整个条目（功耗优化）

**Ahead BTB（AheadBtbBank）：**
```scala
private val sram = Module(new SplittedSRAMTemplate(
  new AheadBtbEntry, set = NumSets, way = NumWays,
  waySplit = NumWays / 2, dataSplit = 1))
```
- 使用 SplittedSRAMTemplate 按 way 分片

### 9.3 ICache

```scala
private val ways = Seq.tabulate(nWays) { i =>
  Module(new SRAMTemplate(new ICacheDataEntry, set = nSets, way = 1,
    shouldReset = true, singlePort = true, withClockGate = false,
    hasMbist = hasMbist, hasSramCtl = hasSramCtl, suffix = Option("icache_data")))
}
```
- way=1 且手动管理 way，以控制读使能的时序

### 9.4 Prefetch

```scala
val pht_ram = Module(new SRAMTemplate[PhtEntry](new PhtEntry,
  set = smsParams.pht_size / smsParams.pht_ways, way = smsParams.pht_ways,
  singlePort = true, withClockGate = true, hasMbist = hasMbist, hasSramCtl = hasSramCtl))
```
- PHT（Pattern History Table）使用标准 SRAMTemplate
- 带时钟门控以降低功耗

### 9.5 后端 DataModule 使用模式

**StoreSet（StoreSet.scala）：**
```scala
val valid_array = Module(new SyncDataModuleTemplate(
  Bool(), SSITSize, SSIT_READ_PORT_NUM, SSIT_WRITE_PORT_NUM, hasRen = true))
val data_array = Module(new SyncDataModuleTemplate(
  new SSITDataEntry, SSITSize, SSIT_READ_PORT_NUM, SSIT_WRITE_PORT_NUM, hasRen = true))
```
- 使用 `hasRen` 启用读使能控制
- 分离 valid 和 data 数组以优化功耗

**CfiQueue（CfiQueue.scala）：**
```scala
private val mem = Module(new SyncDataModuleTemplate(
  gen = Valid(UInt(CfiPositionWidth.W)),
  numEntries = FtqSize, numRead = readChannelNum, numWrite = 1))
```
- 多读端口单写端口

**Issue 队列数据阵列（DataArray.scala）：**
```scala
private val dataModule = Module(new AsyncRawDataModuleTemplate(
  gen, numEntries, io.read.length, io.write.length))
```
- 使用异步读（零延迟），适合需要组合逻辑立即输出的场景

**GPAMem（GPAMem.scala）：**
```scala
private val mem = Module(new SyncDataModuleTemplate(
  new GPAMemEntry, FtqSize, numRead = 1, numWrite = 1, hasRen = true))
```

**CtrlBlock（CtrlBlock.scala）：**
```scala
private val pcMem = Module(new SyncDataModuleTemplate(
  PrunedAddr(VAddrBits), FtqSize, numPcMemRead, 1, "BackendPC", hasRen = hasRen))
```
- 通过 `parentModule` 参数命名模块

---

## 10. 设计层次总结

XiangShan 的存储基础设施形成清晰的层次结构：

```
应用层:  Cache (TagArray/DataArray), BPU (TageTable/FTB/MBTB), ICache, TLB
          | 实例化
模板层:  SRAMTemplate, SplittedSRAMTemplate, FoldedSRAMTemplate, SRAMTemplateWithArbiter
          | 使用
协议层:  SramProto (init/read/write/apply)
          | 实例化
原语层:  SramArray (abstract), SramArray1P (单端口), SramArray2P (双端口)
          | 综合
硬件层:  SRAM 编译器生成的 SRAM 宏
```

寄存器级数据模块（DataModuleTemplate 系列）则提供了一个独立的层次，适用于不需要 SRAM 编译器、用寄存器实现的场景：

```
寄存器级:  DataModuleTemplate (基础，二进制地址)
           SyncDataModuleTemplate (同步，分块)
           RawDataModuleTemplate (原始，热编码)
             |- SyncRawDataModuleTemplate (同步)
             '- AsyncRawDataModuleTemplate (异步)
           Folded1WDataModuleTemplate (折叠，单写)
```

**关键设计决策：**
1. **way=1 手动管理**：多个模块（ICache、MBTB）选择 way=1 但手动实例化多个 SRAM 以精确控制功耗
2. **单端口 vs 双端口**：单端口 SRAM 面积更小，但需要冲突处理逻辑；双端口 SRAM 面积更大但更灵活
3. **Folded 设计**：用于 set 数很大但 way 数很少的场景（如分支预测表），将多个 set 折叠到同一 SRAM 行
4. **分片设计**：用于超大 SRAM，通过 bank 化降低关键路径延迟和功耗
5. **冲突处理**：默认 CorruptReadWay 策略平衡了安全性（不会泄漏真实数据）和性能（未写入的路仍可读取）

---

## 源文件索引

| 模块 | 文件路径 |
|------|----------|
| SRAMTemplate, SplittedSRAMTemplate, FoldedSRAMTemplate, SRAMTemplateWithArbiter | `utility/src/main/scala/utility/sram/SRAMTemplate.scala` |
| SRAMConflictBehavior | `utility/src/main/scala/utility/sram/SRAMTemplate.scala` (line 127) |
| SRAMBundleA, SRAMBundleAW, SRAMBundleR, SRAMReadBus, SRAMWriteBus | `utility/src/main/scala/utility/sram/SRAMTemplate.scala` (line 26-124) |
| SramArray, SramArray1P, SramArray2P, SramProto | `utility/src/main/scala/utility/sram/SramProto.scala` |
| SramInfo, SramHelper | `utility/src/main/scala/utility/sram/SramHelper.scala` |
| SramBroadcastBundle, SramMbistIO | `utility/src/main/scala/utility/sram/SramProto.scala` (line 26-40) |
| RawDataModuleTemplate, SyncRawDataModuleTemplate, AsyncRawDataModuleTemplate | `utility/src/main/scala/utility/DataModuleTemplate.scala` |
| DataModuleTemplate | `utility/src/main/scala/utility/DataModuleTemplate.scala` (line 163) |
| SyncDataModuleTemplate | `utility/src/main/scala/utility/DataModuleTemplate.scala` (line 89) |
| Folded1WDataModuleTemplate | `utility/src/main/scala/utility/DataModuleTemplate.scala` (line 207) |
| IndexableCAMTemplate | `utility/src/main/scala/utility/IndexableCAMTemplate.scala` |
| CAMTemplate (MMU) | `src/main/scala/xiangshan/cache/mmu/MMUBundle.scala` (line 154) |
| 类型别名导出 | `utility/src/main/scala/utility/package.scala` |
