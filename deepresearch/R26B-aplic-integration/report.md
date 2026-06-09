# R26B - APLIC & AIA Integration Deep Dive Report

## 概述

本报告深入分析 XiangShan RISC-V 处理器中 APLIC (Advanced Platform-Level Interrupt Controller) 与 AIA (Advanced Interrupt Architecture) 的集成实现。APLIC 是 RISC-V AIA 规范定义的中断控制器，负责管理系统级中断源并将中断转换为 MSI (Message Signaled Interrupts) 投递至 IMSIC (Incoming MSI Controller)。本实现涵盖双域架构、中断整流 (Rectification)、MSI 投递机制、Topi 计算、AIA CSR 映射、三级中断过滤 (InterruptFilter) 以及 VS-mode 虚拟中断注入等核心功能。

---

## 1. APLIC Dual-Domain Architecture (APLIC 双域架构)

### 1.1 设计哲学

APLIC 规范定义了两个 Domain (域)：一个 Machine-level Domain (M-domain) 和一个 Supervisor-level Domain (S-domain / SG-domain)。这种双域设计使得中断可以按特权级进行分层管理与委托 (Delegation)。

在 XiangShan ChiselAIA 实现中，`APLIC` 类（位于 `ChiselAIA/src/main/scala/APLIC.scala`）通过实例化两个 `Domain` 模块来实现这一架构：

```scala
private val domains = Seq(Module(new Domain(
  params.baseAddr,
  params.mBaseAddr,
  params.mStrideWidth,
  0,  // imsicGeilen=0 for machine-level domain
)), Module(new Domain(
  params.baseAddr + pow2(params.domainMemWidth),
  params.sgBaseAddr,
  params.sgStrideWidth,
  params.geilen,  // guest interrupt files for SG domain
)))
```

### 1.2 域间委托机制

两个 Domain 之间通过 `sourcecfg.D` (Delegation) 位实现中断委托。当 M-domain 中某个中断源的 `sourcecfg.D` 位被置 1 时，该中断源将从 M-domain 剥离并传递给 SG-domain：

```scala
intSrcsDelegated := (sourcecfgs.regs zip intSrcs).map {case (r, i:Bool) => r.D&i}
```

关键数据流：物理中断源 `intSrcs` 首先送入 `domains(0)` (M-domain)，M-domain 中标记为 delegated 的中断通过 `intSrcsDelegated` 输出，再作为 `domains(1)` (SG-domain) 的输入：

```scala
domains(0).intSrcs := intSrcs
domains(1).intSrcs := domains(0).intSrcsDelegated
```

### 1.3 内存映射布局

每个 Domain 占用 16KB (14-bit) 的地址空间。`APLICParams` 定义了关键参数：

- `domainMemWidth = 14`：每个 Domain 的 memory region 为 16KB
- `intFileMemWidth = 12`：每个 interrupt file 为 4KB
- M-domain 基地址：`0x19960000`（测试中使用）
- SG-domain 基地址：M-domain 基地址 + `0x4000`

### 1.4 总线接口封装

顶层提供 TileLink (`TLAPLIC`) 和 AXI4 (`AXI4APLIC`) 两种总线封装。`TLAPLIC` 使用 `TLRegMapperNode` 连接 CPU 侧读写，使用 `TLClientNode` 连接 IMSIC 侧的 MSI 投递通道。每个 Domain 独立拥有一个 `RegMapper` 接口和一个 MSI 输出通道。

### 1.5 参数化约束

`APLICParams` 中包含了丰富的参数约束：

- `aplicIntSrcWidth <= 10`：最多支持 1023 个中断源（因为 sourcecfg 从 index 1 开始）
- `aplicIntSrcWidth < imsicIntSrcWidth`：APLIC 的中断源范围是 IMSIC 的子集
- 基地址对齐约束：`mBaseAddr` 和 `sgBaseAddr` 必须对齐到 2^(k+C) 和 2^(k+D)
- `geilen` 定义了 SG-domain 中 Guest Interrupt File 的数量（默认 7 个）

---

## 2. Interrupt Rectification (中断整流)

### 2.1 整流原理

中断整流是 APLIC 的核心机制之一，将不同类型的外部中断信号统一转换为内部的 pending 状态。APLIC 支持 4 种中断源模式 (Source Mode, SM)，通过 `sourcecfg.SM` 字段配置：

| SM 值 | 名称 | 触发条件 |
|--------|------|----------|
| 0 | inactive | 不活动（中断被禁用） |
| 1 | detached | 脱离（不产生中断） |
| 4 | edge1 | 上升沿触发 |
| 5 | edge0 | 下降沿触发 |
| 6 | level1 | 高电平有效 |
| 7 | level0 | 低电平有效 |
| 2, 3 | reserved | 保留（写入时强制为 inactive） |

### 2.2 硬件实现

在 ChiselAIA 的 `Domain` 模块中，整流逻辑分为两步：同步和极性选择：

**Step 1 - 同步**：中断源信号经过 3 级 `RegNextN` 同步器去除亚稳态：

```scala
private val intSrcsSynced = RegNextN(intSrcs, 3)
```

**Step 2 - 极性整流**：根据 SM 配置对同步后的信号进行极性变换：

```scala
intSrcsRectified(0) := false.B  // source 0 永远为 false（保留）
(1 until params.intSrcNum).map(i => {
  val (rect, sync, sm) = (intSrcsRectified(i), intSrcsSynced(i), sourcecfgs.regs(i).SM)
  when      (sm===sourcecfgs.edge1 || sm===sourcecfgs.level1) {
    rect := sync          // 正极性：直接使用
  }.elsewhen(sm===sourcecfgs.edge0 || sm===sourcecfgs.level0) {
    rect := !sync         // 负极性：取反
  }.otherwise {
    rect := false.B       // inactive/detached/reserved：输出 0
  }
})
```

### 2.3 Triggered 检测

整流后的信号通过边沿检测产生单周期脉冲 `intSrcsTriggered`：

```scala
(intSrcsTriggered zip intSrcsRectified).map { case (trigger, rect) => {
  trigger := rect && !RegNext(rect)
}}
```

这一机制确保无论是边沿触发还是电平触发模式，每个中断事件只产生一次 pending 置位。

### 2.4 IP 写入优先级

IP (Interrupt Pending) 位的写入遵循严格的优先级规则（利用 Chisel FIRRTL 的 Conditional Last Connect Semantics）：

1. **最低优先级**：regmap 映射的 setips/setipnum/in_clrips/clripnum 写操作
2. **中间优先级**：`intSrcsTriggered` 脉冲置位
3. **最高优先级**：MSI 发送后清除 IP 位

### 2.5 电平模式特殊规则

对于 level1/level0 模式，IP 位的置位有条件限制：当 `domaincfg.DM=1`（MSI 模式）时，只有在整流后信号为高时才能设置 IP 位。当整流后信号为低时，即使软件写入 setipnum 也不会置位：

```scala
when (sourcecfgs.regs(ui).SM===sourcecfgs.level1 || sourcecfgs.regs(ui).SM===sourcecfgs.level0) {
  when (domaincfg.DM) {
    when (intSrcsRectified(ui)) { bits(ui):=bit }
  }
}
```

这符合 AIA 规范第 3.1.4 节的要求：电平模式下，setip 仅在 rectified input 为高时生效。

---

## 3. MSI Delivery Mechanism (MSI 投递机制)

### 3.1 MSI 地址计算

APLIC 当前仅支持 MSI delivery mode (`domaincfg.DM=1` 为硬编码)。MSI 的目标地址由 `HartIndex`、`GuestIndex` 和 IMSIC 的内存布局决定：

```scala
def getMSIAddr(HartIndex:UInt, guestID:UInt): UInt = {
  val groupID = HartIndex(params.groupsWidth+params.membersWidth-1, params.membersWidth)
  val memberID = HartIndex(params.membersWidth-1, 0)
  imsicBaseAddr.U |
    (groupID<<params.groupStrideWidth) |
    (memberID<<imsicMemberStrideWidth) |
    (guestID<<params.intFileMemWidth)
}
```

地址计算公式为：`IMSIC_base + groupID * groupStride + memberID * memberStride + guestID * 4KB`

### 3.2 Topi 到 MSI 的映射

Domain 内部的 topi (Top of Interrupt) 指示当前最高优先级的 pending 中断。通过查表获取该中断的 Target 寄存器信息，构建 MSI 包：

```scala
val target = targets.regs(topi)
val topiBits = MSIBundle(getMSIAddr(target.HartIndex, target.GuestIndex), target.EIID)
```

Target 寄存器（`targets.regs`）包含三个字段：
- `HartIndex[31:18]`：目标 hart 的 group + member 索引
- `GuestIndex[17:12]`：目标 Guest Interrupt File 索引
- `EIID[10:0]`：External Interrupt Identity（写入 IMSIC 的中断号）

### 3.3 Extempore MSI (genmsi)

除常规 topi 驱动的 MSI 外，APLIC 还支持 Extempore MSI（临时/即时 MSI），通过 `genmsi` 寄存器触发。写入 `genmsi` 寄存器后，APLIC 会立即向指定 hart 发送一个 MSI：

```scala
val genmsiBits = MSIBundle(getMSIAddr(genmsi.HartIndex, 0.U), genmsi.EIID)
io.msi.bits := Mux(genmsi.Busy, genmsiBits, topiBits)
io.msi.valid := (state===idle) && (genmsi.Busy || (domaincfg.IE && topi=/=0.U))
```

Extempore MSI 优先于常规 topi MSI。当 `genmsi.Busy=1` 时，发送 genmsi 的 MSI；否则如果 Domain 使能 (`IE=1`) 且有 pending 中断 (`topi!=0`)，则发送 topi 驱动的 MSI。

### 3.4 状态机

MSI 投递使用简单的两状态 FSM：

- **idle**：空闲状态，可以发起 MSI 传输
- **waiting_ack**：等待 IMSIC 确认（通过 TileLink D 通道 / AXI4 B 通道）

当收到确认信号 `io.ack` 后：
- 如果是 genmsi，清除 `Busy` 标志
- 如果是常规中断，清除对应的 IP 位

```scala
is (waiting_ack) { when (io.ack) { state := idle
  when (genmsi.Busy) { genmsi.Busy := false.B }
  .otherwise { ips.wBitUI(topi, false.B) }
}}
```

---

## 4. Topi Calculation (Topi 计算)

### 4.1 APLIC 内部的 Topi

APLIC Domain 内部使用 `ParallelPriorityMux` 实现 topi 的并行优先级编码。遍历所有中断源，找到第一个 `ip & ie` 均为 1 的中断：

```scala
private val topi = Wire(UInt(params.aplicIntSrcWidth.W))
topi := ParallelPriorityMux((
  (ips.bits0:+true.B) zip (ies.bits0:+true.B)
).zipWithIndex.map {
  case ((p: Bool, e: Bool), i: Int) => (p & e, i.U)
})
```

这里 `ips.bits0(0)` 和 `ies.bits0(0)` 为 false（source 0 不使用），末尾追加 `true.B` 作为兜底条件，避免所有 ip/ie 为零时返回最大索引。

### 4.2 InterruptFilter 中的 Topi

在 XiangShan CSR 子系统中，`InterruptFilter` 模块计算三个层级的 topi：`mtopi`、`stopi` 和 `vstopi`。这是整个中断子系统中最复杂的部分，将在第 6 节详细展开。

---

## 5. AIA CSR Mapping (AIA CSR 寄存器映射)

### 5.1 核心 AIA CSRs

`CSRAIA.scala`（位于 `src/main/scala/xiangshan/backend/fu/NewCSR/CSRAIA.scala`）定义了以下 AIA 相关 CSR：

| CSR 地址 | 名称 | 功能 |
|----------|------|------|
| CSRs.mtopei | Mtopei | M-mode Top External Interrupt claim |
| CSRs.mtopi | Mtopi | M-mode Top Interrupt |
| CSRs.stopei | Stopei | S-mode Top External Interrupt claim |
| CSRs.stopi | Stopi | S-mode Top Interrupt |
| CSRs.vstopei | VStopei | VS-mode Top External Interrupt claim |
| CSRs.vstopi | VStopi | VS-mode Top Interrupt |

### 5.2 TopI (Top Interrupt) CSR 格式

`TopIBundle` 为只读寄存器，格式如下：

```
Bits [27:16] IID   - Interrupt Identity (最高优先级 pending 中断编号)
Bits [7:0]   IPRIO - Interrupt Priority (最高优先级 pending 中断优先级)
```

mtopi/stopi/vstopi 的值来源于 `InterruptFilter` 模块的输出：

```scala
val mtopi = Module(new CSRModule("Mtopi", new TopIBundle) with HasInterruptFilterSink {
  regOut.IID   := topIR.mtopi.IID
  regOut.IPRIO := topIR.mtopi.IPRIO
})
```

### 5.3 TopEI (Top External Interrupt) CSR 格式

`TopEIBundle` 为读写寄存器，用于 claim 操作（Claim/End-of-interrupt 协议）：

```
Bits [26:16] IID   - Interrupt Identity (claim 返回的中断编号)
Bits [10:0]  IPRIO - Interrupt Priority (claim 返回的优先级)
```

mtopei/stopei/vstopei 的值来源于 IMSIC 的输出：

```scala
val mtopei = Module(new CSRModule("Mtopei", new TopEIBundle) with HasAIABundle {
  regOut := aiaToCSR.mtopei
})
```

### 5.4 中断优先级 CSR (Iprio)

CSRAIA 还定义了一系列间接访问的中断优先级寄存器，通过 `miselect`/`siselect`/`vsiselect` 选择间接地址：

- `miprio0` (0x30) / `siprio0` (0x30)：标准中断优先级（SSI、VSSI、MSI、STI、VSTI、MTI）
- `miprio2` (0x32) / `siprio2` (0x32)：扩展中断优先级（SEI、VSEI、MEI、SGEI、LCOFI 等）
- `miprio4` ~ `miprio0xF`：自定义本地中断优先级

每个优先级字节仅在对应中断的 IE (Interrupt Enable) 位为 1 时才可见：

```scala
val miprio0 = Module(new CSRModule("Iprio0", new Iprio0Bundle) with HasIeBundle {
  val mask = Wire(Vec(8, UInt(8.W)))
  for (i <- 0 until 8) {
    mask(i) := Fill(8, mie.asUInt(i))
  }
  regOut := reg & mask.asUInt
})
```

### 5.5 ISelect 寄存器

`MISelectField`、`SISelectField`、`VSISelectField` 定义了间接 CSR 访问的选择器。不同特权级有不同的合法范围和保留区间：

- M-mode: 0x00-0xFF，保留 0x00-0x2F、0x40-0x6F
- S-mode: 0x000-0xFFF，保留 0x000-0x02F、0x040-0x06F、0x100-0xFFF
- VS-mode: 与 S-mode 相同

### 5.6 CSR-to-AIA / AIA-to-CSR Bundles

`CSRToAIABundle` 定义了从 CSR 子系统到 IMSIC 的接口：
- `addr`：间接 CSR 访问地址（含 privilege mode 和 virtual mode）
- `vgein`：Virtual Guest External Interrupt Number
- `wdata`：写数据和操作类型
- `mClaim/sClaim/vClaim`：Claim 脉冲信号

`AIAToCSRBundle` 定义了从 IMSIC 返回到 CSR 子系统的数据：
- `rdata`：读数据（含 illegal 标志）
- `meip/seip`：外部中断 pending
- `mtopei/stopei/vstopei`：TopEI 寄存器值

---

## 6. InterruptFilter 三级仲裁 (3-Level Arbitration)

### 6.1 模块概述

`InterruptFilter`（位于 `src/main/scala/xiangshan/backend/fu/NewCSR/InterruptFilter.scala`）是整个中断子系统的核心仲裁模块。它从各特权级的 mip/mie/mideleg 等寄存器中收集中断信息，执行优先级排序，输出三个 topi 值和最终的中断向量 (interruptVec)。

### 6.2 M-mode 中断仲裁

M-mode 中断的 gather 逻辑：

```scala
val mtopigather = mip & mie & (~mideleg).asUInt
```

只有在 `mip` (pending) 和 `mie` (enable) 均为 1 且未被 `mideleg` 委托的中断才会被考虑。

优先级排序使用两阶段流水线：

**第一阶段**：将所有中断按默认优先级分组，每组 8 个，使用 `minSelect` 进行组内两两比较，选出每组最高优先级中断。`minSelect` 的比较逻辑考虑了：
- 使能状态 (enable)
- 优先级是否为零 (isZero)
- 优先级是否大于 255 (greaterThan255)
- 优先级数值 (prioNum)

**第二阶段**：将 8 个组冠军再进行一次 `highIprio` 选择，产生最终的 M-mode 最高优先级中断。

对于 MEI (Machine External Interrupt)，优先级来源特殊：使用 `mtopei.IPRIO` 而非 `miprios` 寄存器。这是因为在 APLIC + IMSIC 架构下，MEI 的优先级由 IMSIC 的 TopEI 寄存器动态提供。

### 6.3 HS-mode 中断仲裁

HS-mode (Hypervisor + Supervisor) 中断的 gather 逻辑合并了 `hip|sip` 和 `hie|sie`：

```scala
private val hsip = hip.asUInt | sip.asUInt
private val hsie = hie.asUInt | sie.asUInt
val hstopigather = hsip & hsie & (~hideleg).asUInt
```

SEI 的优先级同样使用 `stopei.IPRIO`。仲裁流程与 M-mode 对称。

### 6.4 VS-mode 中断仲裁

VS-mode 的 gather 逻辑相对简单：

```scala
val vstopigather = vsip & vsie & NoSEIMask
```

其中 `NoSEIMask` 排除了 SEI (bit 9)，因为 VSEI 不能直接通过 vsip/vsie 置位，而是通过 Candidate 机制注入。

VS-mode 优先级使用 `hviprio1` 和 `hviprio2` 寄存器中的值，这些寄存器由 hypervisor 配置，控制虚拟中断的优先级。

### 6.5 IPRIO 值映射

topi 的 IPRIO 输出遵循 AIA 规范的映射规则：

```scala
io.out.mtopi.IPRIO := Mux(mtopiIsNotZeroReg,
  Mux1H(Seq(
    (!mipriosRegTmp.isZero && !mipriosRegTmp.greaterThan255) -> mipriosRegTmp.prioNum,
    (mipriosRegTmp.greaterThan255 || mipriosRegTmp.isZero && mIidDefaultPrioLowMEI) -> 255.U,
    (mipriosRegTmp.isZero && mIidDefaultPrioHighMEI) -> 0.U,
  )),
  0.U
)
```

- 如果 iprio 数值在 1-255 范围内，直接使用该值
- 如果 iprio > 255 或 iprio=0 且 default priority 低于 MEI/SEI，则输出 255
- 如果 iprio=0 且 default priority 高于 MEI/SEI，则输出 0

### 6.6 中断向量生成

三个 topi 的中断向量经过特权级和全局使能过滤后合并：

```scala
val mIRVecTmp = Mux(privState.isModeM && mstatusMIE || privState < PrivState.ModeM,
  io.out.mtopi.IID.asUInt, 0.U)
val hsIRVecTmp = Mux(privState.isModeHS && sstatusSIE || privState < PrivState.ModeHS,
  io.out.stopi.IID.asUInt, 0.U)
val vsIRVecTmp = Mux(privState.isModeVS && vsstatusSIE || privState < PrivState.ModeVS,
  io.out.vstopi.IID.asUInt, 0.U)
```

最终的 `normalIntrVecReg` 按 M > HS > VS 的优先级合并：

```scala
val normalIntrVecReg = mIRVec | hsIRVec | vsMapHostIRVec
```

VS-mode 中断号经过映射（VSSI->SSI, VSTI->STI, VSEI->SEI）以匹配主机中断编号空间。

### 6.7 NMI 与 Debug Interrupt

模块还处理 Non-Maskable Interrupt (NMI) 和 Debug Interrupt：
- NMI 有独立的优先级排序，不可被屏蔽
- Debug Interrupt 在 `debugMode` 或 `dcsr.STEP && !dcsr.STEPIE` 时被禁用
- 当 `mnstatusNMIE=0` 时，所有普通中断被屏蔽（SMRNMI 规范）

---

## 7. VS-level Virtual Interrupt Injection via Candidate Mechanism (VS-mode 虚拟中断注入)

### 7.1 Candidate 机制概述

VS-mode 虚拟中断注入是 AIA 规范中最复杂的部分。`InterruptFilter` 实现了 5 种 Candidate 条件，用于决定 VS-mode 最高优先级 pending 中断的来源和优先级。

### 7.2 五种 Candidate 定义

```scala
val Candidate1: Bool = vsip.SEIP && vsie.SEIE && (hstatus.VGEIN.asUInt =/= 0.U) && (vstopei.asUInt =/= 0.U)
val Candidate2: Bool = vsip.SEIP && vsie.SEIE && (hstatus.VGEIN.asUInt === 0.U) && (hvictl.IID.asUInt === 9.U) && (hvictl.IPRIO.asUInt =/= 0.U)
val Candidate3: Bool = vsip.SEIP && vsie.SEIE && !Candidate1 && !Candidate2
val Candidate4: Bool = (hvictl.VTI.asUInt === 0.U) && vstopigather.orR
val Candidate5: Bool = (hvictl.VTI.asUInt === 1.U) && (hvictl.IID.asUInt =/= 9.U)
```

**Candidate1**：VSEI 通过 IMSIC 注入。条件是 `vsip.SEIP && vsie.SEIE`，且 `hstatus.VGEIN != 0`（选择了有效的 Guest Interrupt File），且 `vstopei != 0`（IMSIC 返回了有效的 TopEI）。此时虚拟 SEI 的优先级来自 `vstopei.IPRIO`。

**Candidate2**：VSEI 通过 `hvictl` 直接注入。条件是 `vsip.SEIP && vsie.SEIE`，`VGEIN = 0`，且 `hvictl.IID = 9`（SEI 编号），`hvictl.IPRIO != 0`。优先级来自 `hvictl.IPRIO`。

**Candidate3**：VSEI 存在但 Candidate1/2 均不满足。优先级固定为 255（最高）。

**Candidate4**：VS-mode 其他中断通过 `vstopigather` 正常仲裁。当 `hvictl.VTI = 0`（Virtual Translation 不活跃）时生效。

**Candidate5**：Hypervisor 通过 `hvictl` 直接注入指定中断。当 `hvictl.VTI = 1` 且 `hvictl.IID != 9` 时生效。优先级来自 `hvictl.IPRIO`。

### 7.3 互斥约束

代码中有明确的 assertion 确保 Candidate 之间的一致性：

```scala
assert(PopCount(Cat(Candidate1, Candidate2, Candidate3)) < 2.U)
assert(PopCount(Cat(Candidate4, Candidate5)) < 2.U)
assert(PopCount(Cat(Candidate2, Candidate5)) < 2.U)
```

- Candidate1/2/3 互斥：同一时刻最多只有一个 VSEI 注入路径
- Candidate4/5 互斥：正常仲裁 vs 直接注入
- Candidate2 和 Candidate5 不可能同时为真（VGEIN=0 且 IID=9 与 VTI=1 且 IID!=9 逻辑上矛盾）

### 7.4 组合仲裁

实际运行中可能出现多个 Candidate 同时活跃（如 C1+C4、C1+C5、C2+C4、C3+C4、C3+C5），需要进行二次仲裁。每种组合都有独立的仲裁逻辑。

以 C1+C4 为例：

```scala
when(C4IsZero) {
  iidC1C4 := Mux(C4HighVSEI, iidOnlyC4, iidOnlyC1)
  iprioC1C4 := Mux(C4HighVSEI, 0.U, iprioC1GreaterThan255)
}.elsewhen(iprioC1 < iprioC4) {
  iidC1C4 := iidOnlyC1
  iprioC1C4 := iprioC1Tmp
}.elsewhen(iprioC1 === iprioC4) {
  iidC1C4 := Mux(SEIHighC4, iidOnlyC1, iidOnlyC4)
  iprioC1C4 := Mux(SEIHighC4, iprioC1Tmp, iprioC4)
}.otherwise {
  iidC1C4 := iidOnlyC4
  iprioC1C4 := iprioC4
}
```

优先级比较规则：
1. 优先级数值小的获胜
2. 优先级相同时，使用 default priority 比较（SEI 编号 9 vs vstopi 结果）
3. iprio 为零时特殊处理

### 7.5 DPR (Default Priority Reserved) 位

`hvictl.DPR` 位控制 Candidate2/Candidate5 在 iprio=0 时的行为：
- `DPR=1`：iprio 为零时，优先级变为 255（最高）
- `DPR=0`：iprio 为零时，优先级为 0（最低）

### 7.6 vstopi 输出

vstopi 的 IID 和 IPRIO 通过 `Mux1H` 从所有可能的 Candidate 组合中选择：

```scala
io.out.vstopi.IID := Mux(CandidateNoValidReg, 0.U,
  Mux1H(Seq(
    (Candidate123Reg & NoCandidate45Reg) -> iidOnlyC1,
    onlyC4EnableReg -> iidOnlyC4,
    onlyC5EnableReg -> iidOnlyC5,
    C1C4EnableReg -> iidC1C4,
    ...
  )))
```

### 7.7 hvictl 注入标记

模块还输出 `viIsHvictlInjectReg` 信号，指示当前 VS-mode 中断是否通过 `hvictl` 注入（Candidate5 路径），用于下游的 `mip`/`vip` 更新逻辑：

```scala
val viIsHvictlInjectReg = RegNext(vsIRModeCond && SelectCandidate5 && io.in.mnstatusNMIE, false.B)
```

---

## 8. Integration Test Coverage (集成测试覆盖)

### 8.1 APLIC 单元测试 (`ChiselAIA/test/aplic/main.py`)

#### 8.1.1 write_read_test

验证 APLIC 寄存器的基本读写功能：

- `domaincfg` 寄存器：写入 `0xfedcab98`，读回 `0x80000104`（只读字段保持默认值，IE 位清零）
- `sourcecfg` 寄存器：验证 WARL 行为（reserved 值自动修正为 inactive），Machine-level 不支持 ChildIndex
- `setips`/`seties` 寄存器：验证 set/clear 语义，source 0 为只读零
- `setipnum`：验证 Level 模式下 rectified 信号为低时不置位 IP
- `targets` 寄存器：Machine-level 下 GuestIndex 无效
- `readonly0`：只读零寄存器
- `mmsiaddrcfgh`：硬编码为 `0x80000000`

#### 8.1.2 set_clr_test

验证 set/clear 寄存器组的原子操作：

- `setienum`：按编号设置 IE 位（源 0 被忽略）
- `clries`：按位清除 IE 位
- `clrienum`：按编号清除 IE 位
- `setipnum_le`：按编号设置 IP 位（Little-endian 侧）
- `setipnum_be`：Big-endian 侧只读零

#### 8.1.3 triggered_int_test

验证四种中断源模式的整流行为：

- **edge1**：信号从 0->1 时触发
- **edge0**：信号从 1->0 时触发
- **level1**：信号为 1 时触发（保持高电平时持续 pending）
- **level0**：信号为 0 时触发

#### 8.1.4 in_clrips_test

验证 `in_clrips` 寄存器：读取当前 rectified 状态，同时清除对应的 IP 位。测试同时配置 4 种不同模式的中断源，验证 rectified 信号的正确性。

#### 8.1.5 msi_test

验证 MSI 投递机制：

- **setipnum MSI**：通过 setipnum 触发中断，验证 MSI 数据和地址正确
- **genmsi MSI**：通过 genmsi 寄存器触发 MSI，验证 HartIndex 寻址
- **intSrcs MSI**：通过物理中断源触发，验证中断触发到 MSI 的完整路径
- **delegation**：测试中断从 M-domain 委托到 SG-domain，验证委托后的 MSI 投递到正确的 IMSIC guest interrupt file

### 8.2 集成测试 (`ChiselAIA/test/integration/main.py`)

#### integration_simple_test

验证 APLIC + IMSIC 的完整集成：

1. 配置 M-domain：使能 IE，设置 DM 模式
2. 初始化 62 个中断源为 edge1 模式
3. 设置目标为递增的 EIID 值
4. 使能所有中断
5. 初始化 IMSIC0（选择 M/S/VS interrupt file，使能 delivery 和所有 EIE 位）
6. 触发中断源 19 和 2，验证 `toCSR0_topeis_0` 输出正确的 TopEI 值

### 8.3 Common 基础设施 (`ChiselAIA/test/common.py`)

测试框架提供完整的 APLIC/IMSIC 操作抽象：

- **TileLink 操作**：`a_put_full32`/`a_get32` 通过 TileLink 协议读写 APLIC 寄存器
- **IMSIC 操作**：`write_csr`/`read_csr`/`claim` 操作 IMSIC 的间接 CSR
- **中断模拟**：`interrupt(dut, i)` 通过物理中断源触发中断
- **IMSIC 初始化**：`init_imsic` 配置所有 interrupt file 的 delivery 和 EIE
- **地址常量**：定义了 APLIC 和 IMSIC 的内存映射偏移

---

## 9. Source File Locations (源文件位置)

| 文件 | 路径 | 描述 |
|------|------|------|
| APLIC.scala | `ChiselAIA/src/main/scala/APLIC.scala` | APLIC RTL 实现：Domain、Topi、MSI 发送、中断整流 |
| CSRAIA.scala | `src/main/scala/xiangshan/backend/fu/NewCSR/CSRAIA.scala` | AIA CSR 定义：mtopi/stopi/vstopi/mtopei/stopei/vstopei |
| InterruptFilter.scala | `src/main/scala/xiangshan/backend/fu/NewCSR/InterruptFilter.scala` | 三级中断仲裁、VS-mode Candidate 机制 |
| InterruptBundle.scala | `src/main/scala/xiangshan/backend/fu/NewCSR/InterruptBundle.scala` | 中断编号定义、默认优先级排序 |
| aplic/main.py | `ChiselAIA/test/aplic/main.py` | APLIC 单元测试 |
| integration/main.py | `ChiselAIA/test/integration/main.py` | APLIC+IMSIC 集成测试 |
| common.py | `ChiselAIA/test/common.py` | 测试基础设施：TileLink 操作、IMSIC 操作、地址常量 |

---

## 总结

XiangShan 的 APLIC & AIA 集成实现展现了完整的 RISC-V AIA 中断架构：

1. **双域架构**通过 `sourcecfg.D` 位实现中断委托，M-domain 和 SG-domain 共享物理中断源
2. **中断整流**支持 edge1/edge0/level1/level0 四种模式，使用 3 级同步器保证信号完整性
3. **MSI 投递**通过两状态 FSM 实现，支持常规 topi 驱动和 Extempore genmsi 两种路径
4. **Topi 计算**使用 ParallelPriorityMux 实现 APLIC 内部的并行优先级编码
5. **AIA CSR** 完整映射了 topi/topei 系列寄存器和间接访问的 Iprio 寄存器
6. **InterruptFilter** 采用两阶段流水线仲裁 M/HS/VS 三个层级的中断优先级
7. **VS-mode Candidate 机制**通过 5 种候选条件的组合仲裁，灵活支持 IMSIC 注入、hvictl 直接注入等多条虚拟中断路径
8. **测试覆盖**涵盖寄存器读写、中断整流、MSI 投递、域委托等关键功能路径
