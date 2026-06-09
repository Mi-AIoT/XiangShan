# R26A - IMSIC Design Deep Dive

## 概述

IMSIC（Incoming MSI Controller）是 RISC-V Advanced Interrupt Architecture (AIA) 规范中定义的中断控制器核心模块，负责管理来自外部中断源的 Message Signaled Interrupt (MSI) 请求，并将其转化为对标中断文件（interrupt file）中 pending 位的置位操作。ChiselAIA 项目中的 IMSIC 实现位于 `ChiselAIA/src/main/scala/IMSIC.scala`，采用 Chisel HDL 构建，支持 M-mode、S-mode 及 VS-mode（virtualized supervisor）三级特权级别的中断文件，并可选地支持 TEE（Trusted Execution Environment）双实例扩展。

本文将对 IMSIC 的 MSI 握手协议、中断文件寄存器模型、优先级仲裁、Claim 机制、TEE 扩展、异步跨时钟域桥、多 hart 寻址以及测试场景进行逐一深入分析。

---

## 1. IMSICGateWay MSI 握手协议

### 1.1 模块定义与端口

`IMSICGateWay` 是 `IMSIC` 的内部子模块，定义在 `IMSIC.scala` 第 169-203 行。其端口包括：

- **`msiio`**：类型为 `MSITransBundle`，包含三个信号：
  - `vld_req` (Input, Bool)：来自 AXI reg 侧的有效请求信号。
  - `data` (Input, UInt)：MSI 信息位宽，编码了中断 ID 和中断文件索引。
  - `vld_ack` (Output, Bool)：IMSIC 侧返回的应答信号，表示 IMSIC 可以主动处理请求。

- **`msi_data_o`** (Output, UInt)：提取出的中断源编号，宽度为 `imsicIntSrcWidth` 位。
- **`msi_valid_o`** (Output, UInt)：多位中断文件选择向量，宽度为 `intFilesNum` 位，每一位对应一个中断文件。

### 1.2 数据编码格式

MSI data 的总宽度为 `MSI_INFO_WIDTH = imsicIntSrcWidth + INTP_FILE_WIDTH`，其中 `INTP_FILE_WIDTH = log2Ceil(intFilesNum)`。编码方式为：

- 高 `INTP_FILE_WIDTH` 位（`MSI_INFO_WIDTH-1 : imsicIntSrcWidth`）：中断文件索引，用于选择目标中断文件（M=0, S=1, VS0=2, VS1=3, ...）。
- 低 `imsicIntSrcWidth` 位：中断源编号，即要 set 的 interrupt pending bit 的偏移。

### 1.3 握手时序

握手采用 valid-ack 两阶段协议：

1. **异步同步**（可选）：当 `EnableImsicAsyncBridge` 为 true 时，`vld_req` 经过三级 `AsyncResetSynchronizerShiftReg` 同步到 CPU 时钟域；否则直连。

2. **ack 生成**：`msi_vld_ack_cpu` 寄存器在 `vld_req_cpu` 为高时置 1，否则清 0。即 ack 是 req 的单周期延迟跟随。

3. **上升沿检测**：`msi_vld_ris_cpu = msi_vld_req_cpu & (~msi_vld_ack_cpu)`，只在 req 的上升沿触发一次数据捕获。

4. **数据捕获与分发**：在上升沿时：
   - `msi_data_catch` 锁存中断源编号（低 `imsicIntSrcWidth` 位）。
   - `msi_intf_valids` 通过 `1.U << intFileIndex` 生成 one-hot 选择向量。

5. **应答通知**：`msiio.vld_ack` 直接连接到 `msi_vld_ack_cpu`，通知上游 AXI reg 模块请求已被接受。

这种协议确保每次 MSI 写入只触发一次中断文件的 pending 位设置，且通过 ack 信号实现了背压控制。

---

## 2. IntFile（中断文件）寄存器模型

### 2.1 模块结构

`IntFile` 是 `IMSIC` 的内部子模块，定义在第 204-335 行，每个特权级别各有一个独立实例（M、S、以及 geilen 个 VS）。

### 2.2 间接 CSR 寄存器

IMSIC 使用 indirect CSR 机制访问寄存器，CPU 通过写 `iselect` 寄存器选择目标 CSR，然后对 `xireg` 寄存器执行读写操作。在 ChiselAIA 实现中，这些寄存器通过 `RegMapDV` 映射到 memory-mapped 地址空间。

#### 2.2.1 eidelivery（地址 0x70）

- 初始化值：0（中断传递禁用）。
- 写保护函数 `fixEIDelivery`：仅允许 bit[0] 写入，高位写入无效。
- 含义：bit[0]=1 表示中断传递启用，bit[0]=0 表示禁用。当 eidelivery 为 0 时，即使有 pending & enabled 的中断，topei 也返回 0。

#### 2.2.2 eithreshold（地址 0x72）

- 初始化值：0。
- 无特殊写保护。
- 含义：中断优先级阈值。只有中断号 i < eithreshold 的中断才会被报告。当 eithreshold=0 时，所有中断被抑制（topei 返回 0）。当 eithreshold=1 时，只有 interrupt 0 被允许。当 eithreshold > 最大中断号时，所有中断被允许。

#### 2.2.3 eips（地址 0x80 - 0xBF）

- 数量：`eixNum = 2^imsicIntSrcWidth / 64` 个寄存器。
- 每个寄存器 64 位宽。
- **eips[0]（地址 0x80）的 bit[0] 为只读零**：写入无效，读取永远返回 0。这是因为 interrupt 0 是保留的软件中断（MSI），不应有 pending bit。
- 写保护函数 `bit0ReadOnlyZero`：`x & ~1.U`。
- 其余 eips 寄存器（0x82, 0x84, ...）无特殊保护。

#### 2.2.4 eies（地址 0xC0 - 0xFF）

- 数量和布局与 eips 相同。
- eies[0] 的 bit[0] 同样为只读零。
- 含义：中断使能位。当 eies[i][j]=1 且 eips[i][j]=1 时，中断号 `i*64+j` 处于 pending 且 enabled 状态。

#### 2.2.5 CSR 操作类型

`OpType` 枚举（第 71-76 行）定义了四种 CSR 操作：

| 枚举值 | 含义 | wdata/wmask 行为 |
|--------|------|-----------------|
| ILLEGAL (0) | 非法操作 | 不写入 |
| CSRRW (1) | 直接写入 | wdata=原始数据, wmask=全1 |
| CSRRS (2) | 置位 | wdata=全1, wmask=原始数据（mask=1 的位被置1） |
| CSRRC (3) | 清零 | wdata=全0, wmask=原始数据（mask=1 的位被清0） |

### 2.3 seteipnum（MSI 中断置位机制）

当 `fromCSR.seteipnum.valid` 为高时，IMSICGateWay 将中断源编号转换为分组索引和位偏移：

```scala
val index  = seteipnum.bits(imsicIntSrcWidth-1, xlenWidth)  // 高位：eip 寄存器索引
val offset = seteipnum.bits(xlenWidth-1, 0)                  // 低位：寄存器内 bit 偏移
eips(index) := eips(index) | UIntToOH(offset)                 // 原子或操作置位
```

这保证了通过 MSI 通道设置 pending 位不会覆盖其他同时 pending 的中断。

---

## 3. 优先级仲裁 - ParallelPriorityMux

### 3.1 topei 计算

IMSIC 的核心功能之一是从所有 pending & enabled 的中断中选出最高优先级的中断号（即 topei，Top External Interrupt）。实现位于第 299-326 行。

#### 3.1.1 位展平

```scala
val eipBools = Cat(eips.reverse).asBools :+ true.B
val eieBools = Cat(eies.reverse).asBools :+ true.B
```

将所有 eips 寄存器拼接成一个位向量，并在末尾追加一个恒为 true 的哨兵位。这个哨兵位的作用是：当所有中断都未 pending/enabled 时，`ParallelPriorityMux` 仍然能返回一个有效索引（最大值），然后被后续的 `xtopei_filter` 检查过滤掉，避免返回错误的最大中断号。

#### 3.1.2 ParallelPriorityMux 仲裁

```scala
ParallelPriorityMux(
  (eipBools zip eieBools).zipWithIndex.map {
    case ((p: Bool, e: Bool), i: Int) => (p & e, i.U)
  }
)
```

`ParallelPriorityMux` 接受一组 `(condition, value)` 对，返回第一个 condition 为 true 的 value。由于 `eipBools` 和 `eieBools` 按中断号从低到高排列，索引 0（最低中断号）拥有最高优先级。这符合 AIA 规范：中断号越小，优先级越高。

对于中断号 i，条件为 `eip[i] & eie[i]`，即 pending 且 enabled。

#### 3.1.3 xtopei_filter 过滤

```scala
def xtopei_filter(xeidelivery, xeithreshold, xtopei): UInt = {
  val tmp_xtopei = Mux(xeidelivery(63,1) === 0.U, Mux(xeidelivery(0), xtopei, 0.U), 0.U)
  Mux(tmp_xtopei <= (xeithreshold - 1.U), tmp_xtopei, 0.U)
}
```

过滤逻辑：
1. 如果 eidelivery 高 63 位非零，返回 0（eidelivery 只有 bit[0] 有效）。
2. 如果 eidelivery[0]=0（中断传递禁用），返回 0。
3. 如果 topei <= eithreshold-1（即 i < eithreshold），返回原始 topei；否则返回 0。

这确保了只有在中断传递启用且中断优先级满足阈值条件时，topei 才报告有效中断号。

---

## 4. Claim 机制

### 4.1 机制原理

Claim 操作是 AIA 规范中处理器"认领"中断的标准操作。当处理器读取 `xtopei` 寄存器时，实际上执行了 claim 操作，将该中断的 pending 位自动清除。

### 4.2 实现代码

```scala
when(fromCSR.claim) {
  val index  = toCSR.topei(imsicIntSrcWidth-1, xlenWidth)
  val offset = toCSR.topei(xlenWidth-1, 0)
  eips(index) := RegNext(eips(index) & ~UIntToOH(offset))
}
```

当 `claim` 信号为高时：
1. 从当前 topei 中提取 eip 寄存器索引和位偏移。
2. 在下一周期，将对应 eip 寄存器中的该位清零。
3. 使用 `RegNext` 延迟一周期写入，确保在 claim 信号有效时仍能读取到当前 topei 值。

### 4.3 Claim 信号来源

在 `IMSIC` 顶层（第 427 行），`intFile.fromCSR.claim` 直接连接到 `fromCSR.claims(pi)`，其中 `pi` 根据特权级别映射：
- M-mode：`claims(0)`
- S-mode：`claims(1)`
- VS-mode：`claims(2)`

### 4.4 topei 输出格式

topei 的输出格式遵循 AIA 规范第 3.9 节：

```scala
def wrap(topei: UInt): UInt = {
  val zeros = 0.U((16 - imsicIntSrcWidth).W)
  Cat(zeros, topei, zeros, topei)  // 32位: [31:16]=0...0|topei, [15:0]=0...0|topei
}
```

bit[10:0] = 中断优先级/身份号，bit[26:16] = 中断身份号，其余位为零。

---

## 5. TEE 扩展（IMSIC_WRAP 双实例与 cmode 切换）

### 5.1 IMSIC_WRAP 模块

`IMSIC_WRAP`（第 468-528 行）是 IMSIC 的封装模块，当 `HasTEEIMSIC=true` 时实例化两个 `IMSIC` 模块：

1. **REE IMSIC**（`imsic`）：运行在 Normal World / Rich Execution Environment。
2. **TEE IMSIC**（`teeimsic`）：运行在 Trusted Execution Environment。

### 5.2 cmode 切换

`ForCVMBundle`（第 88-92 行）定义了 TEE 扩展端口：
- `cmode` (Input, Bool)：当前 CPU 模式。0 = REE，1 = TEE。
- `notice_pending` (Output, Bool)：对端的中断 pending 通知。

切换逻辑（第 487-527 行）：

```
当 cmode=1 (TEE 模式)：
  - toCSR 数据来自 teeimsic
  - topei 除了 M-mode 外都来自 teeimsic
  - addr.valid / wdata.valid / claims 只传递给 teeimsic
  - imsic 的 addr/wdata/claims 被屏蔽

当 cmode=0 (REE 模式)：
  - toCSR 数据来自 imsic (REE)
  - topei 除了 M-mode 外都来自 imsic
  - addr.valid / wdata.valid / claims 只传递给 imsic
  - teeimsic 的 addr/wdata/claims 被屏蔽
```

### 5.3 M-mode 中断特殊处理

M-mode 的 pending 和 topei 始终来自 REE IMSIC，不随 cmode 切换：

```scala
toCSR.topeis(0) := imsic.toCSR.topeis(0)  // M-mode 始终 REE
m_pendings := imsic.toCSR.pendings(0)       // M-mode pending 始终 REE
```

teeimsic 的 claims(0) 始终为 false，即 TEE IMSIC 不处理 M-mode 中断。

### 5.4 notice_pending 生成

```scala
val s_orpend_ree = imsic.toCSR.pendings(intFilesNum-1, 1)
val s_orpend_tee = teeimsic.toCSR.pendings(intFilesNum-1, 1)
notice_pending := Mux(cmode, s_orpend_ree.orR, s_orpend_tee.orR)
```

当在 TEE 模式下，通知 REE 有 pending 中断；反之通知 TEE。

### 5.5 地址空间分离

TEE IMSIC 使用独立的地址空间：
```scala
lazy val tee_mAddr  = mAddr + (1L << tee_mshift)   // bit(HartIDBits + intFileMemWidth) = 1
lazy val tee_sgAddr = sgAddr + (1L << tee_sshift)   // 更高位偏移
```

在 `TLRegIMSIC_WRAP` 和 `AXIRegIMSIC_WRAP` 中，REE 和 TEE 各有独立的 `TLRegIMSIC` / `AXIRegIMSIC` 实例，通过 Xbar 连接到各自的地址段。

---

## 6. 异步桥（Async Bridge）跨时钟域

### 6.1 双时钟域架构

在 `TLIMSIC` 和 `AXI4IMSIC` 模块中，存在两个时钟域：

- **CPU clock**：IMSIC 核心逻辑运行的时钟。
- **soc_clock**：AXI reg 侧和总线逻辑运行的时钟。

```scala
axireg.module.clock := soc_clock
axireg.module.reset := soc_reset
imsic.clock         := clock  // CPU clock
imsic.reset         := reset
```

### 6.2 MSI 请求/应答跨时钟域

#### 6.2.1 SoC -> CPU 方向（vld_req 同步）

在 `IMSICGateWay`（第 179-183 行）：
```scala
when(EnableImsicAsyncBridge.B) {
  msi_vld_req_cpu := AsyncResetSynchronizerShiftReg(msiio.vld_req, 3, 0)
}
```
使用 3 级同步器将 SoC 时钟域的 vld_req 同步到 CPU 时钟域。`AsyncResetSynchronizerShiftReg` 是 Rocket Chip 提供的异步复位同步移位寄存器。

#### 6.2.2 CPU -> SoC 方向（vld_ack 同步）

在 `TLRegIMSIC`（第 778-781 行）：
```scala
when(EnableImsicAsyncBridge.B) {
  msi_vld_ack_soc := AsyncResetSynchronizerShiftReg(msi_vld_ack_cpu, 3, 0)
}
```
将 CPU 时钟域的 ack 信号同步回 SoC 时钟域。

### 6.3 FIFO 缓冲

`TLRegIMSIC` 和 `AXIRegIMSIC` 中包含一个深度为 8 的同步 FIFO：

```scala
private val fifo_sync = Module(new Queue(UInt(FifoDataWidth.W), 8))
```

写入端（SoC clock domain）：`RegGen` 模块解析总线写操作，生成 `seteipnum` 信息，写入 FIFO。

读取端（CPU clock domain）：FIFO 的 deq.ready 受 `~msi_vld_req` 控制，即只有当前请求被 ack 后才能读取下一个。

```scala
fifo_sync.io.deq.ready := ~msi_vld_req
msi_vld_req := Mux(msi_vld_ack_soc_ris, false.B,
                   Mux(fifo_sync.io.deq.valid, true.B, msi_vld_req))
```

`msi_vld_ack_soc_ris` 是 ack 信号的上升沿检测，用于在应答后清零 vld_req，使 FIFO 可以继续读取下一个中断请求。

### 6.4 RegGen 模块

`RegGen`（第 895-944 行）是 AXI/TL 总线接口到 IMSIC 核心之间的转换层：

1. 接收来自 `RegMapper` 的 `seteipnum`（中断文件索引 + 中断源编号）。
2. 通过 `RegField` 的 `RegWriteFn` 捕获写入数据。
3. 输出 `io.seteipnum`（拼接后的 MSI 信息）和 `io.valid`（至少一个中断文件被写入）。

### 6.5 异步延迟补偿

测试中的 `delay_fifo` 函数根据 `EnableImsicAsyncBridge` 配置等待不同的时钟周期数：
- 同步模式（=0）：等待 10 个周期。
- 异步模式（=1）：等待 5 个周期。

这补偿了同步器引入的延迟。

---

## 7. 多 Hart Group/Member 寻址

### 7.1 中断文件索引编码

`IMSICParams` 中定义了关键参数：

```scala
lazy val intFilesNum: Int = 2 + geilen  // M, S, VS0, VS1, ..., VS(geilen-1)
lazy val INTP_FILE_WIDTH = log2Ceil(intFilesNum)
lazy val MSI_INFO_WIDTH  = imsicIntSrcWidth + INTP_FILE_WIDTH
```

例如 `geilen=7` 时，`intFilesNum=9`，`INTP_FILE_WIDTH=4`，MSI data 中高 4 位为中断文件索引。

### 7.2 特权级别到中断文件映射

在 `IMSIC` 顶层（第 344-379 行），根据 `{priv, virt}` 组合生成 one-hot 选择向量：

| priv | virt | vgein | 中断文件索引 | 说明 |
|------|------|-------|------------|------|
| M(3) | 0 | - | 0 (OH) | M-mode interrupt file |
| S(1) | 0 | - | 1 (OH) | S-mode interrupt file |
| S(1) | 1 | 1 | 2 (OH) | VS-mode guest 0 |
| S(1) | 1 | 2 | 3 (OH) | VS-mode guest 1 |
| S(1) | 1 | N | N+1 (OH) | VS-mode guest N-1 |

`intFilesSelOH_r` 和 `intFilesSelOH_w` 分别控制读和写的中断文件选择，通过 `UIntToOH` 将索引转换为 one-hot 向量。

### 7.3 权限校验

```scala
when(priv === M && !virt) => 合法
when(priv === S && !virt) => 合法
when(priv === S && virt && vgein >= 1 && vgein <= geilen) => 合法
其他 => illegal_priv = true
```

非法的特权级别或 vgein 值会导致 `illegal_priv` 信号置高，阻止对该中断文件的访问。

### 7.4 vgein 选择 VS-mode topei

```scala
toCSR.topeis(2) := RegNext(wrap(ParallelMux(
  UIntToOH(fromCSR.vgein - 1.U, geilen).asBools,
  topeis_forEachIntFiles.drop(2)
)), init=0.U(32.W))
```

使用 `ParallelMux` 根据 `vgein` 值从所有 VS-mode interrupt files 中选择对应的 topei 输出。

### 7.5 地址空间布局

每个中断文件占用 4KB（`intFileMemWidth=12`）的 memory-mapped 区域：

- M-mode 中断文件：`mAddr` 起始，每个 hart 占 `0x1000`。
- S/VS 中断文件：`sgAddr` 起始，每个 interrupt file 占 `0x1000`，共 `(1+geilen)` 个。

在 `RegGen` 中，M 和 S/VS 各有一个 `regmapIO`，通过 `RegMapper` 将 AXI/TL 总线写操作转换为 `seteipnum` 信号。S/VS 地址空间内部通过偏移量区分不同 interrupt file：

```scala
j * pow2(intFileMemWidth).toInt -> RegField(...)
```

其中 `j` 是 interrupt file 在 S/VS 组内的索引。

---

## 8. 测试场景覆盖

测试文件位于 `ChiselAIA/test/imsic/main.py`，使用 cocotb 框架驱动仿真。

### 8.1 测试流程总览

测试函数 `imsic_1_test` 按顺序执行以下场景：

#### 8.1.1 初始化

`init_imsic` 对所有 interrupt files（M、S、所有 VS）执行初始化：
- 选择对应 interrupt file。
- 写 eidelivery = 1 启用中断传递。
- 写所有 eie 寄存器为全 1（使能所有中断）。

#### 8.1.2 M-mode seteipnum 测试

通过 `m_int(1996%256)` 向 M-mode interrupt file 发送 MSI，验证 `topeis_0` 输出正确的中断号。1996%256 = 196，验证 topei 格式正确。

#### 8.1.3 M-mode Claim 测试

调用 `claim()` 后验证 `topeis_0` 返回 0，确认 pending 位被清除。

#### 8.1.4 多中断 seteipnum + claim 测试

先设置中断 12，再设置中断 8，验证 topei 优先输出较小的中断号（8），claim 后输出 12。验证了优先级仲裁逻辑。

#### 8.1.5 CSR 操作测试

- **CSRRS 操作**：对 eidelivery 执行 CSRRS 操作（set 0xC0），验证无非法标志。
- **CSRRC 操作**：对 eidelivery 执行 CSRRC 操作。
- **直接写入**：测试 eidelivery 的直接写入/清除。

#### 8.1.6 eithreshold 阈值测试

- 写入 eithreshold 为当前 topei 的低 11 位，验证 topei 被抑制（不等于原始 topei）。
- 写入 eithreshold 为 topei+1，验证 topei 恢复输出。
- 恢复 eithreshold=0。

#### 8.1.7 eip 直接写入测试

通过 CSR 写操作直接设置 eip0 = 0xC（bit[3:2] 置位），验证 topei 报告 interrupt 2。

#### 8.1.8 eie 使能/禁用测试

对当前最高优先级中断的 eie bit 执行 CSRRC（清除），验证 topei 变化；再执行 CSRRS（置位），验证 topei 恢复。

#### 8.1.9 eie 读取测试

执行 `read_csr(eie0)`，验证读取操作正常完成。

#### 8.1.10 S-mode 基本测试

切换到 S-mode interrupt file，发送中断 1234%256，验证 `topeis_1` 输出正确。

#### 8.1.11 VS-mode 基本测试（vgein=2）

切换到 VS-mode interrupt file（guest ID=2），发送中断 137，验证 `topeis_2` 输出正确。切换回 M-mode 后验证 `topeis_2` 仍保持不变（VS-mode topei 独立于 M-mode 视角）。

#### 8.1.12 非法 iselect 测试

写入地址 0x71（奇数地址，在 0x80-0xFF 范围内），验证 `illegal` 标志置高。

#### 8.1.13 非法 vgein 测试

设置 vgein=5（超出 geilen 范围），验证写操作触发非法标志。

#### 8.1.14 非法 wdata_op 测试

使用 `op_illegal`（值为 0）操作类型写入，验证非法标志。

#### 8.1.15 非法特权级别测试

设置 priv=3 (M-mode) + virt=1（M-mode 不支持虚拟化），验证写操作触发非法标志。

#### 8.1.16 eip0[0] 只读零测试

- 通过 CSR 写入尝试设置 eip0[0]，读回验证为 0。
- 通过 `m_int(0)` 尝试通过 MSI 设置 interrupt 0 的 pending bit，读回验证 eip0[0] 仍为 0。

### 8.2 测试基础设施

`ChiselAIA/test/common.py` 提供了通用的测试辅助函数：

| 函数 | 功能 |
|------|------|
| `a_put_full32` | 通过 TileLink 总线执行 32 位写操作 |
| `a_get32` | 通过 TileLink 总线执行 32 位读操作 |
| `m_int` | 向 M-mode interrupt file 发送 MSI |
| `s_int` | 向 S-mode interrupt file 发送 MSI |
| `v_int_vgein` | 向 VS-mode interrupt file 发送 MSI |
| `claim` | 执行 claim 操作 |
| `write_csr` | 执行 CSR 写操作（CSRRW） |
| `write_csr_op` | 执行指定操作类型的 CSR 写操作 |
| `read_csr` | 执行 CSR 读操作 |
| `select_m_intfile` | 选择 M-mode interrupt file |
| `select_s_intfile` | 选择 S-mode interrupt file |
| `select_vs_intfile` | 选择 VS-mode interrupt file |
| `wrap_topei` | 将中断号转换为 topei 输出格式 |
| `delay_fifo` | 等待 FIFO/异步桥延迟 |

### 8.3 地址配置

```python
imsic_m_base_addr  = 0x61000000   # M-mode MSI 写入基地址
imsic_sg_base_addr = 0x82900000   # S/VS-mode MSI 写入基地址
```

每个 interrupt file 在其基地址后偏移 `0x1000 * imsicID`（M-mode）或 `0x8000 * imsicID + 0x1000 * (1+guestID)`（S/VS-mode）。

---

## 9. 源文件位置

| 文件路径 | 内容说明 |
|---------|---------|
| `ChiselAIA/src/main/scala/IMSIC.scala` | IMSIC 核心实现：IMSICGateWay、IntFile、IMSIC、IMSIC_WRAP、TLIMSIC、AXI4IMSIC、RegGen 等全部模块 |
| `ChiselAIA/src/main/scala/IMSICParameters.scala` | IMSICParams 参数定义、HasIMSICParameters trait |
| `ChiselAIA/src/main/scala/common.scala` | AXI4ToLite、AXI4Map、AXI4ToTLNoTLError、TLRegMapperNode、AXI4RegMapperNode 等总线转换模块 |
| `ChiselAIA/test/imsic/main.py` | IMSIC cocotb 仿真测试主文件 |
| `ChiselAIA/test/common.py` | 通用测试辅助函数（TileLink 操作、CSR 操作、MSI 注入等） |

---

## 10. 关键设计要点总结

### 10.1 优先级模型

IMSIC 严格遵循 AIA 规范的中断优先级模型：中断号越小，优先级越高。`ParallelPriorityMux` 实现了并行优先级编码，所有 interrupt file 的 eip/eie 被展平为一个位向量，最低有效位（即最小中断号）具有最高优先级。

### 10.2 间接 CSR 访问

IMSIC 不使用传统的 CSR 读写接口，而是通过 memory-mapped 的 `iselect/xireg` 间接访问机制。这使得 IMSIC 可以通过标准总线（AXI4 或 TileLink）访问，而不需要专用的 CSR 总线。`RegMapDV` 模块实现了可配置的寄存器映射和写保护逻辑。

### 10.3 跨时钟域安全

通过 `AsyncResetSynchronizerShiftReg`（3 级同步）和同步 FIFO 实现了 SoC 总线时钟域到 CPU 时钟域的安全数据传输。FIFO 深度为 8，提供了足够的缓冲以应对总线延迟。

### 10.4 TEE 隔离

双实例 IMSIC 通过 cmode 信号实现了 REE 和 TEE 之间中断状态的完全隔离。M-mode 中断由 REE IMSIC 统一管理，确保安全监控器对所有中断的最终控制权。

### 10.5 总线接口灵活性

通过 `TLIMSIC`（TileLink）和 `AXI4IMSIC`（AXI4）两种顶层封装，IMSIC 可以灵活集成到不同的 SoC 总线架构中。内部使用 `AXI4ToLite` 将 AXI4 转换为简单的单拍读写接口，再通过 `AXI4RegMapperNode` 或 `TLRegMapperNode` 映射到寄存器空间。
