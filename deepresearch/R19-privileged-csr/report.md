# XiangShan RISC-V 处理器 -- 特权架构与 CSR 深度研究报告

> 研究范围: `src/main/scala/xiangshan/backend/fu/NewCSR/` 目录下的 CSR 子系统实现

---

## 目录

1. [CSR 架构总览：Trait Mixin 模式与 NewCSR 顶层模块](#1-csr-架构总览)
2. [特权模式（M/HS/HU/VS/VU + Debug）](#2-特权模式)
3. [Trap 处理（TrapHandleModule 优先级与委派链）](#3-trap-处理)
4. [中断过滤（InterruptFilter 与 AIA 支持）](#4-中断过滤)
5. [性能计数器（29 HPM、Smcntrpmf、Sstc）](#5-性能计数器)
6. [虚拟内存 CSR（satp/vsatp/hgatp）](#6-虚拟内存-csr)
7. [H-Extension 支持（Hypervisor CSR 与 VS 虚拟中断注入）](#7-h-extension-支持)
8. [自定义 CSR（sbpctl、spfctl、smblockctl 等）](#8-自定义-csr)
9. [CSR 流水线流程与状态机](#9-csr-流水线流程与状态机)
10. [完整 CSR 地址映射表](#10-完整-csr-地址映射表)
11. [关键源文件位置索引](#11-关键源文件位置索引)

---

## 1. CSR 架构总览：Trait Mixin 模式与 NewCSR 顶层模块

### 1.1 设计哲学

XiangShan 的 NewCSR 子系统是整个处理器特权架构的核心，采用 Chisel 语言实现。其设计遵循**高内聚、松耦合**的原则，将 RISC-V 特权架构的各个层级分解为独立的 Scala trait（特质），通过 **mixin 组合模式**拼装成完整的 CSR 模块。这种设计使得每个特权层级可以独立开发和验证，同时保证了最终组合后的一致性。

### 1.2 NewCSR 顶层模块

`NewCSR`（定义于 `NewCSR.scala`）是整个 CSR 子系统的顶层 Module，它通过 `with` 关键字混入（mixin）了所有特权层级的 trait：

```scala
class NewCSR(implicit val p: Parameters) extends Module
  with HasXSParameter
  with MachineLevel            // M 模式
  with SupervisorLevel         // S 模式（含 HS 模式）
  with HypervisorLevel         // H 扩展
  with VirtualSupervisorLevel  // VS 模式
  with Unprivileged            // U/VU 模式（非特权寄存器）
  with CSRAIA                  // AIA（高级中断架构）支持
  with CSRIND                  // 间接 CSR 访问
  with HasExternalInterruptBundle
  with HasNonMaskableIRPBundle
  with CSREvents               // 事件系统（trap/ret 等）
  with DebugLevel              // Debug 模式
  with CSRCustom               // 自定义 CSR
  with CSRPMP                  // PMP（物理内存保护）
  with CSRPMA                  // PMA（物理内存属性）
  with CSRDocDump              // CSR 文档导出
  with HasCriticalErrors
  with IpIeAliasConnect        // Ip/Ie 别名连接
  with DebugMMIO               // Debug MMIO 地址
```

每个 trait 都有一个自类型约束（self-type annotation），例如 `trait MachineLevel { self: NewCSR => }`，表明该 trait 只能被混入到 `NewCSR` 中。这种设计允许 trait 之间通过 `self` 引用互相访问对方的成员。

### 1.3 CSR 寄存器映射聚合

NewCSR 的核心工作之一是将所有层级的 CSR 映射聚合为统一的查找表。三个关键的 `SeqMap` 完成此工作：

- **`csrRwMap`**: 地址 -> (写 Bundle, 读数据) 的映射，用于 CSR 读写操作
- **`csrMods`**: 所有 CSRModule 实例的列表，用于统一连接各种 Bundle 信号
- **`csrOutMap`**: 地址 -> 寄存器输出原始值的映射，用于 Debug 和状态输出

聚合顺序为:
```
machineLevelCSRMap ++ supervisorLevelCSRMap ++ hypervisorCSRMap ++
virtualSupervisorCSRMap ++ unprivilegedCSRMap ++ debugCSRMap ++
aiaCSRMap ++ customCSRMap ++ indCSRMap ++ pmpCSRMap ++ pmaCSRMap
```

### 1.4 CSRModule 基础模式

每个 CSR 寄存器都封装在 `CSRModule` 中。`CSRModule` 提供了统一的读写接口（`w: CSRAddrWriteBundle`、`rdata: UInt`、`regOut`），以及一个可编程的内部寄存器 `reg`。开发者只需定义 Bundle 类型并实现逻辑，Chisel 会自动生成寄存器和写使能逻辑。CSR 字段使用类型安全的枚举系统（`CSREnumType`），支持 RO（只读）、RW（读写）、WARL（写任意读合法值）、WLRL（写合法读合法值）等属性。

### 1.5 VS/S 地址映射机制

在 VS 模式下，软件使用 S 模式的 CSR 地址访问 VS 模式的 CSR 寄存器（如访问 `sstatus` 实际访问 `vsstatus`）。XiangShan 通过 `vsMapS` 和 `sMapVS` 两个 `SeqMap` 实现地址重映射：

- `sMapVS`: S 地址 -> VS 地址（如 `sstatus -> vsstatus`、`satp -> vsatp`）
- `vsMapS`: VS 地址 -> S 地址（反向映射）

在 CSR 读写逻辑中，当处于 VS 模式时，地址会被自动替换为对应的 VS 地址。

---

## 2. 特权模式（M/HS/HU/VS/VU + Debug）

### 2.1 特权状态表示

XiangShan 使用 `PrivState` Bundle 表示当前特权状态：

```scala
class PrivState extends Bundle {
  val PRVM = PrivMode()  // 2-bit: M(11)/HS(01)/U(00)
  val V    = VirtMode()  // 1-bit: 0=非虚拟, 1=虚拟
}
```

由此产生 5 种有效模式：
| PRVM | V | 实际模式 | 说明 |
|------|---|---------|------|
| M(11) | 0 | M-Mode | 机器模式 |
| HS(01) | 0 | HS-Mode | Hypervisor 服务模式 |
| U(00) | 0 | HU-Mode | Hypervisor 用户模式（极少使用） |
| HS(01) | 1 | VS-Mode | 虚拟服务模式 |
| U(00) | 1 | VU-Mode | 虚拟用户模式 |

此外，`debugMode` 寄存器独立于 PRVM/V，表示处理器处于 Debug 模式。

### 2.2 M 模式（MachineLevel trait）

M 模式是最高的特权级别，实现了全部 Machine Level CSR。核心寄存器包括：

- **`mstatus`**: 全局机器状态寄存器，包含 MIE（全局中断使能）、MPRV（内存特权重定位）、TVM/TSR/TW（陷阱控制位）、MDT/SDT（双重陷阱禁用位）等。MstatusModule 是一个复杂模块，混入了多个事件接收 trait（TrapEntryMEventSinkBundle、TrapEntryHSEventSinkBundle、MretEventSinkBundle、DretEventSinkBundle、MNretEventSinkBundle、SretEventSinkBundle、HasRobCommitBundle、HasMachineEnvBundle），用于在各种 trap/ret 事件发生时更新相关字段（如 MIE/MPIE/MPP/SPP/MPV 等）。
- **`misa`**: ISA 扩展支持标识，XiangShan 支持 A/B/C/D/F/H/I/M/S/U/V 等扩展，Architecture ID = 25
- **`medeleg`/`mideleg`**: 异常/中断委派寄存器。其中 MCALL 和 DBLTRP 位为只读零（不可委派），VS 中断（VSSI/VSTI/VSEI）和 SGEI 在 mideleg 中为只读一
- **`mie`/`mip`**: 中断使能/待处理寄存器，支持 64 位宽度，涵盖所有中断源
- **`mtvec`**: 机器陷阱向量基地址，支持 Direct 和 Vectored 模式
- **`mepc`/`mcause`/`mtval`/`mtinst`/`mtval2`**: 陷阱现场保存寄存器
- **`mvien`/`mvip`**: 中断过滤/虚拟中断寄存器（AIA），控制 SSIE/SEIE/local interrupt 的独立可写性
- **`menvcfg`**: 机器环境配置（STCE/PBMTE/CDE/DTE 等位）
- **`mseccfg`**: 机器安全内存保护配置（PMM/MLPE/SSEED/USEED/RLB/MMWP/MML）
- **`mnepc`/`mncause`/`mnstatus`/`mnscratch`**: Smrnmi 扩展（可恢复 NMI）寄存器
- **`mcontext`**: 机器触发上下文（14-bit HCONTEXT）
- **`mbmc`**: 可选的 Bitmap Check 寄存器（BMA/KEYIDEN/BME/CMODE/BCLEAR）

### 2.3 S/HS 模式（SupervisorLevel trait）

Supervisor Level trait 实现了标准 Supervisor Mode CSR，以及 Hypervisor Extension 中 HS 模式使用的寄存器：

- **`sstatus`**: 通过 `mstatus` 的别名视图访问（SIE/SPIE/SPP/SUM/MXR/FS/VS/SDT 等字段），写入 sstatus 实际上写入 mstatus 的对应位
- **`sie`/`sip`**: 中断使能/待处理寄存器，通过 alias 机制与 `mie`/`mip` 关联。当 `mideleg[n]=1` 时，`sip[n]` 是 `mip[n]` 的别名；当 `mideleg[n]=0` 且 `mvien[n]=1` 时，`sip[n]` 使用独立寄存器
- **`stvec`**: S 模式陷阱向量基地址
- **`sepc`/`scause`/`stval`**: 陷阱现场保存
- **`satp`**: Supervisor 地址转换与保护（Sv39/Sv48），写入不支持的 MODE 时整个写操作无效
- **`scounteren`/`mcounteren`**: 计数器访问使能
- **`stimecmp`**: Sstc 扩展，Supervisor 定时器比较值（默认 reset 为全 1）
- **`scountinhibit`**: 计数器禁止寄存器，受 mcounteren 掩码控制
- **`scountovf`**: 计数器溢出状态（Supervisor 快速检测），根据当前特权级和计数器使能过滤
- **`sstateen0~3`**: 状态使能寄存器（Sm/Ssstateen 扩展），受 mstateen/hstateen 控制
- **`scontext`**: S 模式触发上下文（32-bit）
- **`senvcfg`**: S 级环境配置

### 2.4 HU 模式（Hypervisor User）

HU 模式是 Hypervisor 扩展中引入的特权级别，对应 `PRVM=U, V=0`。在 XiangShan 实现中，HU 模式是一个功能受限的模式，许多操作（如 sfence.vma、hfence 等）在 HU 模式下会触发异常。`hstatus.HU` 位控制是否允许 HU 模式下的虚拟 load/store 指令（即 hlsv 指令）。

### 2.5 Debug 模式（DebugLevel trait）

Debug 模式通过 `debugMode` 寄存器独立管理，与特权模式正交。Debug Level trait 实现了：

- **`dcsr`**: Debug 控制与状态寄存器（DEBUGVER=4/CAUSE/V/PRV/STEP/STOPCOUNT/STOPTIME/MPRVEN/EBREAKM/EBREAKS/EBREAKU/EBREAKVS/EBREAKVU/CETRIG/NMIP 等字段）
- **`dpc`**: Debug 程序计数器
- **`dscratch0`/`dscratch1`**: Debug 暂存寄存器
- **`tselect`/`tdata1`/`tdata2`/`tinfo`**: 触发器（Trigger）配置寄存器

XiangShan 实现了 `mcontrol6` 触发器格式，支持地址匹配（EQ/GE/LT，不支持 NAPOT）和动作（断点异常/进入 Debug 模式）。触发器可以链式连接（chain），支持跨模式控制（M/HS/VS/HU/VU）。`tdata1.TYPE` 字段只接受 Mcontrol6 格式，写入其他值会被合法化为 Disabled。

Debug 模式的进入条件包括：EBREAK 指令、触发器命中、单步执行、外部 Halt 请求、Resethaltreq、CriticalError 等。`Debug` 模块（`Debug.scala`）负责综合判断是否进入 Debug 模式。XiangShan 还实现了 Critical Error 机制（`criticalErrorState`），当在 NMIE=0 时发生 trap，会触发 Critical Error 并可能导致重入 Debug 模式。

---

## 3. Trap 处理（TrapHandleModule 优先级与委派链）

### 3.1 TrapHandleModule 概述

`TrapHandleModule`（定义于 `TrapHandleModule.scala`）是处理异常和中断的核心模块。它接收来自 ROB 的 trap 信号和来自 `InterruptFilter` 的中断向量，决定 trap 应该被路由到哪个特权级别，并计算 trap 目标地址。

### 3.2 异常优先级编码

对于多个同时发生的异常，`TrapHandleModule` 按照 RISC-V 特权规范的优先级进行裁决。该优先级列表定义在 `ExceptionNO.priorities` 中，通过树状优先级编码选出最高优先级的异常：

```scala
highestPrioEXVec.zipWithIndex.foreach { case (excp, i) =>
  if (ExceptionNO.priorities.contains(i)) {
    val higherEXSeq = ExceptionNO.getHigherExcpThan(i)
    excp := (higherEXSeq.nonEmpty.B && Cat(higherEXSeq.map(num => !hasEXVec(num))).andR ||
             higherEXSeq.isEmpty.B) && hasEXVec(i)
  }
}
```

逻辑为：如果某个异常的所有更高优先级异常都未发生，则该异常被选中。

### 3.3 中断优先级

中断直接使用 `InterruptFilter` 模块输出的 `intrVec`（已排序的最高优先级中断向量），不需要额外的优先级裁决。中断向量最终编码为 8 位宽的中断号。

### 3.4 委派链（Delegation Chain）

Trap 路由的决策过程遵循三级委派链：

**异常委派**:
- M 模式异常: `mEXVec = highestPrioEX`（未被委派的全部异常）
- HS 模式异常: `hsEXVec = highestPrioEX & medeleg`（被 medeleg 委派的异常）
- VS 模式异常: `vsEXVec = highestPrioEX & medeleg & hedeleg`（被两级委派的异常）

**中断委派**:
- M 模式中断: 直接由 `InterruptFilter` 输出的 M 级中断向量
- HS 模式中断: `InterruptFilter` 输出的 `irToHS` 标志
- VS 模式中断: `InterruptFilter` 输出的 `irToVS` 标志

**最终路由决策**:

```scala
val handleTrapUnderHS = !privState.isModeM && hsHasTrap
val handleTrapUnderVS = privState.isVirtual && vsHasTrap
val handleTrapUnderM  = !handleTrapUnderVS && !handleTrapUnderHS
```

优先级为: **VS > HS > M**（在非 M 模式下，优先尝试路由到更低特权级）。

### 3.5 双重陷阱处理（Sm/Ssdbltrp）

XiangShan 实现了 Smdbltrp 和 Ssdbltrp 扩展，防止 M/HS/VS 模式下的双重陷阱：

- 当 M 模式下发生 trap 且 `mstatus.MDT=1` 时，产生双重陷阱异常，路由到 MN-mode（通过 mnepc/mncause/mnstatus），`dbltrpToMN` 信号触发 `trapEntryMNEvent`
- 当 HS 模式下发生 trap 且 `mstatus.SDT=1` 时，`hasDTExcp` 信号被置位，该异常不会被委派到 HS
- 当 VS 模式下发生 trap 且 `vsstatus.SDT=1` 时，同上不委派到 VS

MDT 位的写入会清零 MIE，SDT 位的写入会清零 SIE，确保在处理双重陷阱期间中断被正确禁用。

### 3.6 Trap 目标地址计算

陷阱目标地址通过 `xtvec` 寄存器计算：

```scala
val xtvec = MuxCase(io.in.mtvec, Seq(
  traptoVS -> io.in.vstvec,
  trapToHS -> io.in.stvec
))
val pcFromXtvec = Cat(xtvec.addr + Mux(xtvec.mode === XtvecMode.Vectored && hasIR,
                   adjustinterruptNO(5, 0), 0.U), 0.U(2.W))
```

对于向量模式（Vectored）的中断，目标地址在基础地址上加上中断号偏移。VS 中断号（VSSIP=2, VSTIP=6, VSEIP=10）会被映射到 S 模式中断号（SSIP=1, STIP=5, SEIP=9）。

### 3.7 Trap 事件系统

NewCSR 使用事件系统（`CSREvents`）来管理状态转换。每种 trap/ret 类型都有对应的 Event 模块（如 `trapEntryMEvent`、`trapEntryMNEvent`、`trapEntryHSEvent`、`trapEntryVSEvent`、`trapEntryDEvent`、`mretEvent`、`mnretEvent`、`sretEvent`、`dretEvent`）。

这些事件模块在 `NewCSR.scala` 中根据条件赋值 `valid` 信号：

```scala
trapEntryMNEvent.valid := ((hasTrap && nmi) || dbltrpToMN) && !entryDebugMode && !debugMode && mnstatus.NMIE
trapEntryMEvent.valid  := hasTrap && entryPrivState.isModeM && !dbltrpToMN && !entryDebugMode && !debugMode && !nmi && mnstatus.NMIE
trapEntryHSEvent.valid := hasTrap && entryPrivState.isModeHS && !entryDebugMode && !debugMode && mnstatus.NMIE
trapEntryVSEvent.valid := hasTrap && entryPrivState.isModeVS && !entryDebugMode && !debugMode && mnstatus.NMIE
```

事件通过 `EventUpdatePrivStateOutput` 输出新的特权状态（PRVM 和 V），由 `MuxCase` 更新寄存器。

---

## 4. 中断过滤（InterruptFilter 与 AIA 支持）

### 4.1 InterruptFilter 模块概述

`InterruptFilter`（定义于 `InterruptFilter.scala`，约 630 行）是 XiangShan 中断处理的关键模块，负责：

1. 按优先级筛选每个特权级别的最高优先级中断
2. 生成 mtopi / stopi / vstopi（Top Interrupt）信息
3. 处理 AIA 中断优先级排序
4. 管理 NMI（不可屏蔽中断）
5. 处理 hvictl 虚拟中断注入
6. 生成 debug 中断

### 4.2 三级中断过滤

InterruptFilter 为三个特权级别分别生成 Top Interrupt 信息：

**M 级中断过滤**:
```scala
val mtopigather = mip & mie & (~mideleg).asUInt  // 未委派的中断
val mtopiIsNotZero = (mip & mie & (~mideleg).asUInt) =/= 0.U
```
使能条件: 当前在 M 模式且 MIE=1，或当前特权级低于 M。

**HS 级中断过滤**:
```scala
val hsip = hip.asUInt | sip.asUInt
val hsie = hie.asUInt | sie.asUInt
val hstopigather = hsip & hsie & (~hideleg).asUInt
```
使能条件: 当前在 HS 模式且 SIE=1，或当前特权级低于 HS。

**VS 级中断过滤**:
```scala
val vstopigather = vsip & vsie & NoSEIMask  // VS 级中断（排除 SEI，由 AIA 处理）
```
VS 级中断的过滤更为复杂，需要考虑 hvictl 注入的虚拟中断和 Guest External Interrupt。

### 4.3 AIA 中断优先级排序

XiangShan 实现了完整的 AIA（Advanced Interrupt Architecture）中断优先级排序。排序算法采用**分治选择**（`minSelect`）策略：

1. 将中断按默认优先级顺序排列（`InterruptNO.interruptDefaultPrio`）
2. 为每个中断分配优先级数值（来自 `miprios`/`hsiprios`/`hviprios`）
3. 使用 `minSelect` 函数进行两两比较，选出最高优先级中断
4. 分两轮排序：先在 8 个一组内排序，再对 8 个组的胜者排序

对于每个中断，优先级判断逻辑为：
- 如果两个中断都使用默认优先级（`isZero=true`），则按默认顺序排序
- 如果一个有显式优先级（`isZero=false`），则优先选择有显式优先级的
- 如果都有显式优先级，数值越小优先级越高
- 如果优先级值 > 255，则与 255 比较
- 对于 MEI/SEI，如果平台中断控制器有有效信号或 AIA 有 pending，其优先级由 AIA topei 寄存器决定

### 4.4 hvictl 虚拟中断注入

`hvictl` 是 Hypervisor 扩展引入的虚拟中断控制寄存器，支持向 VS 模式注入虚拟中断：

- `VTI`（Virtual Trap Interrupt，bit 30）: 启用虚拟中断注入模式
- `IID`（bits 27:16）: 注入的虚拟中断号
- `DPR`（bit 9）: 与 SEI 的默认优先级关系（0=高于 SEI，1=低于 SEI）
- `IPRIO`（bits 7:0）: 注入中断的优先级值
- `IPRIOM`（bit 8）: 是否使用机器定义的优先级

VS 级中断有 5 种候选情况（Candidate 1-5），通过组合逻辑生成最终的 vstopi：
- **Candidate 1**: VS SEIP 有效，VGEIN 非零，vstopei 非零（外部中断控制器提供 SEI）
- **Candidate 2**: VS SEIP 有效，VGEIN=0，hvictl.IID=9，hvictl.IPRIO 非零
- **Candidate 3**: VS SEIP 有效但候选 1/2 都不满足
- **Candidate 4**: hvictl.VTI=0，有普通 VS 中断待处理
- **Candidate 5**: hvictl.VTI=1，hvictl.IID!=9（虚拟中断注入模式）

这些候选的组合情况通过 `Mux1H` 选择器和 GatedValidRegNext 时序逻辑生成最终的 vstopi 输出。Candidate5 被选中时，`virtualInterruptIsHvictlInject` 信号被置位，表示该中断来自 hvictl 注入而非真实硬件中断源。

### 4.5 NMI 处理

XiangShan 支持 Smrnmi 扩展的不可屏蔽中断：

```scala
val nmiVecTmp = Wire(Vec(64, Bool()))
// NMI 中断也按优先级裁决，取最高优先级
```

NMI 的优先级高于所有可屏蔽中断。当 `mnstatus.NMIE=0` 时，所有中断（包括可屏蔽中断）被禁止。NMI 只能在 M 模式下处理，默认使用 mtvec 作为入口地址。

### 4.6 调试中断

调试中断的使能受 `dcsr.STEP`、`dcsr.STEPIE` 和 `debugMode` 控制：

```scala
val disableDebugIntr = io.in.debugMode || (io.in.dcsr.STEP && !io.in.dcsr.STEPIE)
val enableDebugIntr = io.in.debugIntr && !disableDebugIntr
```

最终中断向量的合成:
```scala
val normalIntrVecReg = mIRVec | hsIRVec | vsMapHostIRVec
val intrVecReg = Mux(disableAllIntrReg, 0.U, Mux(nmiReg, nmiVecReg, normalIntrVecReg))
```

VS 中断号到主机中断号的映射（vsMapHostIRVec）将 VSSIP(2)->SSIP(1)、VSTIP(6)->STIP(5)、VSEIP(10)->SEIP(9)，其余位直接映射。

---

## 5. 性能计数器（29 HPM、Smcntrpmf、Sstc）

### 5.1 性能计数器架构

XiangShan 实现了 **29 个硬件性能监控计数器**（hpmcounter3 ~ hpmcounter31），加上 mcycle/minstret，共 31 个机器级计数器。Unprivileged 层也提供了对应的用户可读计数器（cycle/time/instret/hpmcounter3~31）。常量 `perfCntNum = 29` 定义在 `CSRConfig` 中。

### 5.2 计数器值寄存器

**mcycle**: 机器模式周期计数器，由 `mcountinhibit.CY` 控制禁止，由 Smcntrpmf 的 `countingEn` 控制按特权级计数。支持 AIA 间接读取（`HasSiregCounterBundle`）。

**minstret**: 机器模式退休指令计数器，基于 `robCommit.instNum` 递增（每次提交的指令数）。

**mhpmcounter3~31**: 硬件性能监控计数器，每个计数器的值来自 `perf.value`（由事件选择逻辑产生）。溢出检测通过 `toMhpmeventOF` 信号传递给对应的 `mhpmevent` 寄存器。

### 5.3 计数器配置寄存器

**mhpmevent3~31**: 性能事件选择寄存器，每 10 位一个事件选择字段：
```scala
class MhpmeventBundle extends EventInhibitBundle {
  val OF      = RW(63)          // 溢出锁存
  val OPTYPE2 = OPTYPE(54, 50)  // EVENT3 与低级事件组的归约操作符（OR/AND/XOR/ADD）
  val OPTYPE1 = OPTYPE(49, 45)  // EVENT2 与低级事件组的归约操作符
  val OPTYPE0 = OPTYPE(44, 40)  // EVENT1 与 EVENT0 的归约操作符
  val EVENT3  = RW(39, 30)      // 高事件选择位
  val EVENT2  = RW(29, 20)      // 事件选择位
  val EVENT1  = RW(19, 10)      // 事件选择位
  val EVENT0  = RW(9, 0)        // 低事件选择位
}
```

事件抑制字段（EventInhibitBundle）:
- `MINH`(bit 62): M 模式抑制计数
- `SINH`(bit 61): HS 模式抑制计数
- `UINH`(bit 60): HU 模式抑制计数
- `VSINH`(bit 59): VS 模式抑制计数
- `VUINH`(bit 58): VU 模式抑制计数

### 5.4 Smcntrpmf 扩展

XiangShan 实现了 Smcntrpmf（Supervisor mode counter and timer privilege mode filtering），允许 S/HS 模式控制 mcycle 和 minstret 的计数使能：

```scala
val mcyclecfg = Module(new CSRModule("Mcyclecfg", new EventInhibitBundle) { ... })
val minstretcfg = Module(new CSRModule("Minstretcfg", new EventInhibitBundle) { ... })
```

`countingEn` 信号根据当前特权级和对应的 MINH/SINH/UINH/VSINH/VUINH 位计算：

```scala
countingEn(i) := (privState.isModeM  && !mhpmevent.MINH) ||
                 (privState.isModeHS && !mhpmevent.SINH)  ||
                 (privState.isModeHU && !mhpmevent.UINH)  ||
                 (privState.isModeVS && !mhpmevent.VSINH) ||
                 (privState.isModeVU && !mhpmevent.VUINH)
```

### 5.5 Sstc 扩展（Supervisor Timer Compare）

`SstcInterruptGen` 模块（`SstcInterruptGen.scala`）实现了 Sstc 扩展，为 S/VS 模式提供直接定时器比较功能，无需陷入 M 模式：

```scala
val sstcIRGen = Module(new SstcInterruptGen)
sstcIRGen.i.stime.bits  := time.stime       // S 模式时间
sstcIRGen.i.vstime.bits := time.vstime      // VS 模式时间（含 htimedelta 偏移）
sstcIRGen.i.stimecmp.rdata  := stimecmp.rdata
sstcIRGen.i.vstimecmp.rdata := vstimecmp.rdata
sstcIRGen.i.menvcfg.STCE    := menvcfg.STCE  // Sstc 使能位
sstcIRGen.i.henvcfg.STCE    := henvcfg.STCE
```

当 `time >= stimecmp` 时，生成 STIP 中断；当 `vstime >= vstimecmp` 时，生成 VSTIP 中断。menvcfg.STCE 和 henvcfg.STCE 分别控制 S 级和 VS 级的 Sstc 使能。

### 5.6 计数器值的传递

Unprivileged 层的用户可读计数器通过 `HasMHPMSink` trait 从 M 层获取值。在 Debug 模式下，如果 `dcsr.STOPCOUNT=1`，则计数器值在 Debug Mode 期间保持不变（使用寄存器中的缓存值），`unprivCountUpdate` 信号控制何时更新用户可见的计数器快照。

---

## 6. 虚拟内存 CSR（satp/vsatp/hgatp）

### 6.1 satp（Supervisor Address Translation and Protection）

`satp` 控制 S/HS 模式的地址翻译模式：

```scala
class SatpBundle extends CSRBundle {
  val MODE = SatpMode(63, 60)  // Bare/Sv39/Sv48
  val ASID = RW(59, 44)       // 16-bit ASID（XiangShan 实现完整 16 位）
  val PPN  = RW(43, 0)        // 页表根物理页号（44 位）
}
```

XiangShan 支持 **Sv39** 和 **Sv48** 两种地址翻译模式。写入不支持的 MODE 值时，整个写操作被忽略（MODE、ASID、PPN 均不修改），这符合 RISC-V 特权规范对 satp 的 WARL 行为规定。PPN 的有效位宽受 `PAddrBits` 限制（默认 48 位物理地址）。

### 6.2 vsatp（Virtual Supervisor Address Translation）

`vsatp` 控制 VS 模式的两级地址翻译中的第一级（VS-stage）。它与 satp 结构相同，但 PPN 的有效位宽取决于 `hgatp.MODE`：

```scala
val effectivePPNMask = Mux1H(Seq(
  (hgatp.MODE === HgatpMode.Bare)   -> ppnMaskHgatpIsBare,    // 全 PPN 有效
  (hgatp.MODE === HgatpMode.Sv39x4) -> ppnMaskHgatpIsSv39x4,  // 39+2 位有效
  (hgatp.MODE === HgatpMode.Sv48x4) -> ppnMaskHgatpIsSv48x4,  // 48+2 位有效
))
```

XiangShan 对 vsatp 采用较为保守的写策略：当 V=1 时写入不支持的 MODE 值，整个写操作被忽略；当 V=0 时写入不支持的 MODE 值，ASID 和 PPN 仍可写入但 MODE 不变。

### 6.3 hgatp（Hypervisor Guest Address Translation and Protection）

`hgatp` 控制 G-stage 地址翻译（Guest Physical -> Host Physical）：

```scala
class HgatpBundle extends CSRBundle {
  val MODE = HgatpMode(63, 60)  // Bare/Sv39x4/Sv48x4
  val VMID = RW(57, 44)         // 14-bit VMID（XiangShan 实现完整 14 位）
  val PPN  = RW(43, 0)          // G-stage 页表根 PPN（低 2 位强制为零）
}
```

与 satp 不同，写入不支持的 MODE 值时，各字段仍按 WARL 方式处理（MODE 不变，VMID 和 PPN 正常写入）。PPN 的低 2 位被掩码强制为只读零，因为 G-stage 翻译的最小粒度为 4KB 页。

### 6.4 地址翻译类型判断

NewCSR 根据当前特权模式和 CSR 值输出 `instrAddrTransType`，用于指导前端的地址翻译。该输出包含 bare/sv39/sv48/sv39x4/sv48x4 五个一位信号，任何时刻恰好一个有效：

```scala
io.status.instrAddrTransType.bare  := isModeM || (!isVirtual && satp.MODE === Bare) ||
  (isVirtual && vsatp.MODE === Bare && hgatp.MODE === Bare)
io.status.instrAddrTransType.sv39  := !isModeM && !isVirtual && satp.MODE === Sv39 ||
  isVirtual && vsatp.MODE === Sv39
io.status.instrAddrTransType.sv39x4 := isVirtual && vsatp.MODE === Bare && hgatp.MODE === Sv39x4
```

### 6.5 TLB 刷新

写入 satp/vsatp/hgatp（以及 mbmc，如果启用 BitmapCheck）会触发流水线冲刷（`flushPipe`），以确保 TLB 一致性。这是一个高延迟操作，但对正确性至关重要。

### 6.6 ASID/VMID 变更检测

ASID/VMID 的变更会通知 TLB 进行针对性刷新：

```scala
io.tlb.satpASIDChanged  := GatedValidRegNext(satp.w.wen  && satp.ASID  =/= satp.w.wdataFields.ASID)
io.tlb.vsatpASIDChanged := GatedValidRegNext(vsatp.w.wen && vsatp.ASID =/= vsatp.w.wdataFields.ASID)
io.tlb.hgatpVMIDChanged := GatedValidRegNext(hgatp.w.wen && hgatp.VMID =/= hgatp.w.wdataFields.VMID)
```

---

## 7. H-Extension 支持（Hypervisor CSR 与 VS 虚拟中断注入）

### 7.1 Hypervisor Level Trait

`HypervisorLevel` trait（定义于 `HypervisorLevel.scala`）实现了完整的 RISC-V H 扩展 CSR 集合，共 23 个 CSR 寄存器。

### 7.2 核心 Hypervisor CSR

**hstatus**: Hypervisor 状态寄存器
- `SPV`(bit 7): 保存进入 HS-mode 前的虚拟化模式
- `SPVP`(bit 8): 保存进入 HS-mode 前的虚拟特权级
- `VGEIN`(bits 17:12): Guest External Interrupt 选择索引，用于选择 hgeip 中的中断源
- `VTVM`(bit 20): VS 模式下 sfence.vma/hfence.gvma/hfence.vvma 陷入 HS
- `VTW`(bit 21): VS 模式下 WFI 陷入 HS
- `VTSR`(bit 22): VS 模式下 SRET 陷入 HS
- `VSXL`(bits 33:32): VS 模式的有效 XLEN（默认 XLEN64）
- `HU`(bit 9): HU 模式下允许虚拟 load/store
- `GVA`(bit 6): 陷阱信息关联 Guest Virtual Address 标志
- `HUPMM`(bits 49:48): Ssnpm 扩展的 Hypervisor 用户模式内存权限

**hedeleg**: 将异常从 HS 模式委派到 VS 模式。注意 HSCALL(9)/VSCALL(10)/MCALL(11)/IGPF(20)/LGPF(21)/VI(22)/SGPF(23)/DBLTRP(24) 位为只读零（不可委派）。

**hideleg**: 将中断从 HS 模式委派到 VS 模式。SSI/MSI/STI/MTI/SEI/MEI/SGEI 位为只读零，只有 VSSI(2)/VSTI(6)/VSEI(10)/LCOFI(13) 等 VS 级中断位可委派。读取时还受 `mideleg` 和 `mvien` 联合过滤。

**hgatp**: 如第 6.3 节所述，控制 G-stage 地址翻译，支持 Bare/Sv39x4/Sv48x4。

**hvip/hip**: 虚拟中断 pending 寄存器
- `hvip`: 可写的虚拟中断 pending 位（VSSIP/VSTIP/VSEIP + local interrupt），支持从 mip/hip/vsip 三个来源写入
- `hip`: 读取中断状态，大部分位是 mip 的只读别名

**hviprio1/hviprio2**: 虚拟中断优先级寄存器，控制 VS 级各中断的优先级值。

**htimedelta**: 虚拟时间偏移，VS 模式的 time 值 = stime + htimedelta。写入 htimedelta 会触发 STIP/VSTIP 的重新评估。

**htval/htinst**: 陷阱信息，用于传递 Guest Physical Address 相关的异常信息。

**hstateen0~3**: Hypervisor 状态使能，受 mstateen 控制。hstateen0 的每个为零的位在 sstateen0 中也为只读零。

**hcontext**: Hypervisor 触发上下文（14-bit HCONTEXT），与 mcontext 互联。

**hgeie/hgeip**: Guest External Interrupt 使能/待处理。hgeip 是只读的，值来自 AIA 的 vseip 信号。

**hcounteren**: H 级计数器使能，控制 VS/VU 模式下的计数器访问。

**henvcfg**: H 级环境配置（STCE/PBMTE/DTE），受 menvcfg 的对应位掩码控制。

### 7.3 VS 虚拟中断注入机制

VS 模式的中断注入通过多层 alias 链实现：

```
vsip.SSIP -> hvip.VSSIP (可写)
vsip.STIP -> hvip.VSTIP (由 stimecmp 或 hvip 控制)
vsip.SEIP -> hvip.VSEIP | hgeip[VGEIN] | 平台 VSEIP
```

`vsie/vsip` 的读取经过移位变换（VS 中断号 2/6/10 映射到 S 中断号 1/5/9），这是因为 VS 模式的中断视图与 S 模式一致。

`vsip` 的写操作根据委派链路由到不同的目标寄存器：
- `mideleg & hideleg`: 写入 mip（直接委派到 M 层管理的中断）
- `~mideleg & hideleg & mvien`: 写入 mvip（通过 M 层过滤的中断）
- `~hideleg & hvien`: 写入 hvip（由 Hypervisor 层管理的中断）

### 7.4 Ip/Ie Alias 连接

`IpIeAliasConnect` trait 负责连接各层级中断 pending/enable 寄存器之间的 alias 关系。这些连接形成了一个复杂的有向图：

```
mip <-- mvip (SEIP)
mip <-- sip (SSIP)
mip <-- vsip (LCOFIP)
mvip <-- mip (SEIP)
mvip <-- sip (SSIP)
mvip <-- vsip (local IP)
hvip <-- mip (VSSIP)
hvip <-- hip (VSSIP)
hvip <-- vsip (VSSIP + local IP)
mie <-- hie (VSSIE/VSTIE/VSEIE/SGEIE)
mie <-- sie (SSI/STI/SEI/local IE)
mie <-- vsie (VS IE)
sie <-- vsie (VS IE)
```

这些 alias 连接确保了当中断在不同层级之间委派时，相关的 pending/enable 位能正确传递和同步。

---

## 8. 自定义 CSR（sbpctl、spfctl、smblockctl 等）

### 8.1 自定义 CSR 列表

XiangShan 定义了一组自定义 CSR，用于微架构级别的配置控制：

| 地址 | 名称 | 层级 | 说明 |
|------|------|------|------|
| 0x5C0 | sbpctl | Supervisor | 分支预测器控制 |
| 0x5C1 | spfctl | Supervisor | 预取器控制 |
| 0x5C2 | slvpredctl | Supervisor | Load 违规预测控制 |
| 0x5C3 | smblockctl | Supervisor | 内存块配置 |
| 0x5C4 | srnctl | Supervisor | 运行时控制 |
| 0xBC0 | mcorepwr | Machine | 核心电源管理 |
| 0xBC1 | mflushpwr | Machine | L2 缓存刷新控制 |

### 8.2 sbpctl（分支预测器控制，0x5C0）

```scala
class SbpctlBundle extends CSRBundle {
  val RAS_ENABLE    = RW(6)  // 返回地址栈预测器（默认启用）
  val ITTAGE_ENABLE = RW(5)  // 间接目标 TAGE 预测器（默认启用）
  val SC_ENABLE     = RW(4)  // 统计校正器（默认启用）
  val TAGE_ENABLE   = RW(3)  // TAGE 预测器（默认启用）
  val MBTB_ENABLE   = RW(2)  // 宏 BTB 预测器（默认启用）
  val ABTB_ENABLE   = RW(1)  // 替代 BTB（默认启用）
  val UBTB_ENABLE   = RW(0)  // 微 BTB 预测器（默认启用）
}
```

所有分支预测器组件默认启用（reset 时为 true），允许在运行时禁用特定组件进行性能分析或调试。

### 8.3 spfctl（预取器控制，0x5C1）

控制 L1I/L1D/L2 缓存预取器的行为，共 17 个控制位：
- `L1I_PF_ENABLE`(bit 0): L1I 预取器使能
- `L2_PF_ENABLE`(bit 1): L2 预取器总使能
- `L1D_PF_ENABLE`(bit 2): L1D 预取器使能
- `L1D_PF_TRAIN_ON_HIT`(bit 3): 在 cache hit 时训练 L1D 预取器
- `L1D_PF_ENABLE_AGT`(bit 4): L1D AGT 预取器使能
- `L1D_PF_ENABLE_PHT`(bit 5): L1D PHT 预取器使能
- `L1D_PF_ACTIVE_THRESHOLD`(bits 9:6): L1D 活跃页置信度阈值
- `L1D_PF_ACTIVE_STRIDE`(bits 15:10): L1D 活跃页步幅阈值
- `L1D_PF_ENABLE_STRIDE`(bit 16): L1D 步幅预取器使能
- `L2_PF_STORE_ONLY`(bit 17): 限制 L2 预取仅由 store 触发
- `L2_PF_RECV_ENABLE`(bit 18): 接收来自 SMS/L1 训练路径的 L2 预取请求
- `L2_PF_PBOP_ENABLE`(bit 19): L2 PBOP 预取器使能
- `L2_PF_VBOP_ENABLE`(bit 20): L2 VBOP 预取器使能
- `L2_PF_TP_ENABLE`(bit 21): L2 TP 预取器使能
- `L2_PF_DELAY_LATENCY`(bits 31:22): L2 预取器训练延迟
- `BERTI_ENABLE`(bit 32): Berti 预取器使能

### 8.4 slvpredctl（Load 违规预测控制，0x5C2）

控制 Load 违规预测器的行为：
- `LVPRED_DISABLE`(bit 0): 禁用 Load 违规预测器
- `NO_SPEC_LOAD`(bit 1): 禁用推测性 Load
- `STORESET_WAIT_STORE`(bit 2): Reset StoreSet 状态前需要 store
- `STORESET_NO_FAST_WAKEUP`(bit 3): 禁用 StoreSet 快速唤醒
- `LVPRED_TIMEOUT`(bits 8:4): Load 违规预测器超时周期

### 8.5 smblockctl（内存块配置，0x5C3）

控制 Store Buffer 行为和内存子系统配置：
- `SBUFFER_THRESHOLD`(bits 3:0): Store Buffer 刷新阈值
- `LDLD_VIO_CHECK_ENABLE`(bit 4): Load-Load 违规检查使能
- `SOFT_PREFETCH_ENABLE`(bit 5): 软件预取使能
- `CACHE_ERROR_ENABLE`(bit 6): 缓存错误处理使能
- `UNCACHE_WRITE_OUTSTANDING_ENABLE`(bit 7): 非缓存写 outstanding 使能
- `HD_MISALIGN_ST_ENABLE`(bit 8): 硬件未对齐 store 使能
- `HD_MISALIGN_LD_ENABLE`(bit 9): 硬件未对齐 load 使能
- `SBUFFER_TIMEOUT`(bits 31:10): Store Buffer 超时值

### 8.6 srnctl（运行时控制，0x5C4）

- `FUSION_ENABLE`(bit 0): 指令融合使能（默认启用）
- `WFI_ENABLE`(bit 2): WFI 指令使能（默认启用，受 singleStep 和 debugMode 限制）

### 8.7 M 模式自定义 CSR

- **mcorepwr** (0xBC0): `POWER_DOWN_ENABLE`(bit 0) - 核心电源下电请求使能
- **mflushpwr** (0xBC1): `FLUSH_L2_ENABLE`(bit 0) - L2 缓存刷新使能；`L2_FLUSH_DONE`(bit 1) - 只读，L2 刷新完成状态

---

## 9. CSR 流水线流程与状态机

### 9.1 CSR 访问流水线

CSR 的读写操作需要经过多级流水线处理：

1. **输入阶段**: 接收来自执行单元的 CSR 请求（`io.in: DecoupledIO[NewCSRInput]`），包含地址（12-bit）、写数据（64-bit）、读写使能、操作码（2-bit）等。还包含 mnret/mret/sret/dret 信号和 redirectFlush 信号。

2. **权限检查阶段**: `CSRPermitModule` 检查当前特权级是否有权访问目标 CSR，同时检查 xRet 指令的合法性。输出 EX_II（非法指令异常）、EX_VI（虚拟指令异常）、hasLegalWen 等信号。

3. **写使能延迟**: `wenLegal`（当周期的合法写使能）延迟一周期变为 `wenLegalReg`，作为实际的写使能信号。这个延迟确保了读写操作在正确的时钟周期执行。

4. **写操作执行**: 通过 `for ((id, (wBundle, _)) <- csrRwMap)` 循环，将写数据分发到对应的 CSRModule。对于 VS 模式的地址重映射，逻辑会检查 `vsMapS` 和 `sMapVS` 映射表。

5. **读数据选择**: 使用 `Mux1H` 从所有 CSR 的读输出中选择目标地址的数据。VS 模式下的读取同样经过地址重映射。

6. **输出阶段**: 通过 `io.out: DecoupledIO[NewCSROutput]` 输出读数据（rData）、寄存器原始值（regOut）、异常信息（EX_II/EX_VI）和 flushPipe 信号。

### 9.2 状态机

NewCSR 使用三状态有限状态机处理 AIA 异步访问：

```scala
private val s_idle :: s_waitIMSIC :: s_finish :: Nil = Enum(3)
```

**s_idle（空闲态）**:
- 接收新的 CSR 请求（`io.in.ready = true`）
- 如果请求是普通 CSR 访问（非 IMSIC 间接寄存器），直接跳到 `s_finish`
- 如果请求是 IMSIC 异步访问（mireg/sireg/vsireg 且 select 指向 IMSIC 范围），跳到 `s_waitIMSIC`
- 如果收到 redirectFlush 且 valid，保持在 s_idle

**s_waitIMSIC（等待 IMSIC 响应）**:
- 等待外部 AIA 控制器返回数据（`fromAIA.rdata.valid`）
- 如果收到响应且输出就绪（`io.out.ready`），返回 `s_idle`
- 如果收到响应但输出未就绪，进入 `s_finish`
- 如果收到 redirect flush，返回 `s_idle`

**s_finish（完成态）**:
- 保持输出数据，等待下游就绪（`io.out.ready`）
- 如果 redirect flush，返回 `s_idle`

状态转移的核心逻辑保证了 IMSIC 异步访问不会丢失数据，同时在 redirect flush 时能正确取消操作。

### 9.3 异步访问检测

IMSIC 寄存器通过 `miselect/siselect/vsiselect` 间接访问，属于异步操作。异步访问的检测条件为：

```scala
private val asyncAccess = (wen || ren) && !(EX_II || EX_VI) && (
  mireg.addr.U === addr && miselect.inIMSICRange ||
  sireg.addr.U === addr && ((!V.asUInt.asBool && siselect.inIMSICRange) ||
                             (V.asUInt.asBool && vsiselect.inIMSICRange)) ||
  vsireg.addr.U === addr && vsiselect.inIMSICRange
)
```

### 9.4 流水线冲刷

以下条件会触发 CSR 写入后的流水线冲刷（`flushPipe = true`）：
- 写入 satp/vsatp/hgatp/mbmc（TLB 一致性，`resetSatp`）
- 触发器配置变更（`triggerFrontendChange`，前端需要重新加载触发器配置）
- FP 状态切换（FS 从 Off 到 Active/Dirty 或反向，`floatStatusOnOff`）
- Vec 状态切换（VS 从 Off 到 Active/Dirty 或反向，`vectorStatusOnOff`）
- vstart 从非零变为零或反向（`vstartChange`）
- frm 保留值状态变更（`frmChange`）

### 9.5 CSRPermitModule 权限检查

`CSRPermitModule`（定义于 `CSRPermitModule.scala`）由 6 个子模块组成：

1. **XRetPermitModule**: 检查 MNRET/MRET/SRET/DRET 指令的合法性
   - mnret: 只在 M 模式合法
   - mret: 只在 M 模式合法
   - sret: 在 HU/VU 模式非法，在 HS 模式下受 TSR 控制，在 VS 模式下受 VTSR 控制
   - dret: 只在 Debug 模式合法

2. **MLevelPermitModule**: M 模式级别的 CSR 访问权限检查
   - 检查 RO 寄存器写入、FP/Vec Off 状态访问、stimecmp 访问、HPM 访问、satp/hgatp 访问（受 TVM 控制）、stopei 访问（受 mvien.SEIE 控制）、scountinhibit 访问（受 menvcfg.CDE 控制）
   - Sm/Ssstateen 扩展：检查 stateen/envcfg/ind/AIA/IMSIC/context/custom 等位的访问控制

3. **SLevelPermitModule**: S 模式级别的 CSR 访问权限检查（HU 模式下的 HPM 和 custom 访问）

4. **PrivilegePermitModule**: 基于地址[9:8]的特权级权限检查，使用 TruthTable 解码器实现 V/PRVM/ADDR 的组合权限判断

5. **VirtualLevelPermitModule**: VS/VU 模式的额外权限检查（vtvm 控制 satp 访问、vgein 合法性、VSI/SSIP 访问、vstimecmp、scountovf/scountinhibit、HPM、stateen/envcfg/custom 等）

6. **IndirectCSRPermitModule**: 间接 CSR 访问权限检查（Sscsrind 扩展的 siselect/sireg 等）

最终输出两种异常类型：
- `EX_II`: 非法指令异常（非虚拟模式下特权级不足或 CSR 不可访问等）
- `EX_VI`: 虚拟指令异常（VS/VU 模式下访问不被允许的 CSR）

优先级关系: `EX_II` 优先于 `EX_VI`。

---

## 10. 完整 CSR 地址映射表

### 10.1 Machine Level CSR

| 地址 | 名称 | 读写 | 说明 |
|------|------|------|------|
| 0x300 | mstatus | RW | 机器状态寄存器 |
| 0x301 | misa | RO | ISA 扩展标识 |
| 0x302 | medeleg | RW | 机器异常委派 |
| 0x303 | mideleg | RW | 机器中断委派 |
| 0x304 | mie | RW | 机器中断使能 |
| 0x305 | mtvec | RW | 机器陷阱向量 |
| 0x306 | mcounteren | RW | 机器计数器使能 |
| 0x307 | mvien | RW | 机器虚拟中断使能（AIA） |
| 0x308 | mvip | RW | 机器虚拟中断待处理（AIA） |
| 0x30A | menvcfg | RW | 机器环境配置 |
| 0x320 | mcountinhibit | RW | 计数器禁止 |
| 0x321 | mcyclecfg | RW | 周期计数器配置（Smcntrpmf） |
| 0x322 | minstretcfg | RW | 指令计数器配置（Smcntrpmf） |
| 0x323~33F | mhpmevent3~31 | RW | 性能事件选择 |
| 0x340 | mscratch | RW | 机器暂存 |
| 0x341 | mepc | RW | 机器异常 PC |
| 0x342 | mcause | RW | 机器异常原因 |
| 0x343 | mtval | RW | 机器异常值 |
| 0x344 | mip | RW | 机器中断待处理 |
| 0x345 | mtinst | RW | 机器陷阱指令信息 |
| 0x346 | mtopei | RW | M 级中断 claim（AIA） |
| 0x347 | mtopi | RO | M 级 Top Interrupt（AIA） |
| 0x34A | mtval2 | RW | 机器第二异常值 |
| 0x350 | mseccfg | RO | 机器安全配置 |
| 0x3A0~3EF | pmpcfg0~15 | RW | PMP 配置 |
| 0x3B0~3EF | pmpaddr0~63 | RW | PMP 地址 |
| 0xB00 | mcycle | RW | 机器周期计数 |
| 0xB02 | minstret | RW | 机器退休指令计数 |
| 0xB03~B1F | mhpmcounter3~31 | RW | 硬件性能计数器 |
| 0xF11 | mvendorid | RO | 厂商 ID (JEDEC Bank 17, Offset 0x6F) |
| 0xF12 | marchid | RO | 架构 ID (=25) |
| 0xF13 | mimpid | RO | 实现 ID (=0) |
| 0xF14 | mhartid | RO | 硬件线程 ID |
| 0xF15 | mconfigptr | RO | 配置指针 |
| 0x30C~30F | mstateen0~3 | RW | M 级状态使能 |
| 0x740 | mnepc | RW | NMI 异常 PC（Smrnmi） |
| 0x741 | mncause | RW | NMI 异常原因（Smrnmi） |
| 0x742 | mnstatus | RW | NMI 状态（Smrnmi） |
| 0x744 | mnscratch | RW | NMI 暂存（Smrnmi） |
| 0x7A0 | mcontext | RW | 机器触发上下文（14-bit） |
| 0x5C0+ | mbmc | RW | Bitmap Check 控制（可选） |

### 10.2 Supervisor Level CSR

| 地址 | 名称 | 读写 | 说明 |
|------|------|------|------|
| 0x100 | sstatus | RW | S 级状态（mstatus 别名） |
| 0x104 | sie | RW | S 级中断使能 |
| 0x105 | stvec | RW | S 级陷阱向量 |
| 0x106 | scounteren | RW | S 级计数器使能 |
| 0x10A | senvcfg | RW | S 级环境配置 |
| 0x140 | sscratch | RW | S 级暂存 |
| 0x141 | sepc | RW | S 级异常 PC |
| 0x142 | scause | RW | S 级异常原因 |
| 0x143 | stval | RW | S 级异常值 |
| 0x144 | sip | RW | S 级中断待处理 |
| 0x146 | scountinhibit | RW | S 级计数器禁止 |
| 0x14D | stimecmp | RW | S 级定时器比较（Sstc） |
| 0x150 | siselect | RW | AIA S 级中断选择 |
| 0x151 | sireg | RW | AIA S 级间接寄存器 |
| 0x152~156 | sireg2~6 | RW | AIA S 级间接寄存器 2-6 |
| 0x15C | stopei | RW | S 级中断 claim（AIA） |
| 0x15D | stopi | RO | S 级 Top Interrupt（AIA） |
| 0x180 | satp | RW | 地址转换与保护 |
| 0xDA0 | scountovf | RO | 计数器溢出状态 |
| 0x10C~10F | sstateen0~3 | RW | S 级状态使能 |
| 0x680 | scontext | RW | S 级触发上下文（32-bit） |

### 10.3 Hypervisor Level CSR

| 地址 | 名称 | 读写 | 说明 |
|------|------|------|------|
| 0x600 | hstatus | RW | Hypervisor 状态 |
| 0x602 | hedeleg | RW | 异常委派到 VS |
| 0x603 | hideleg | RW | 中断委派到 VS |
| 0x604 | hie | RO | Hypervisor 中断使能 |
| 0x605 | htimedelta | RW | VS 时间偏移 |
| 0x606 | hcounteren | RW | H 级计数器使能 |
| 0x607 | hgeie | RW | Guest 外部中断使能 |
| 0x60A | henvcfg | RW | H 级环境配置 |
| 0x643 | htval | RW | H 级异常值 |
| 0x644 | hip | RO | H 级中断待处理 |
| 0x645 | hvip | RW | VS 虚拟中断待处理 |
| 0x645 | hvien | RW | VS 虚拟中断使能（AIA） |
| 0x646 | hvictl | RW | 虚拟中断控制（AIA） |
| 0x648 | hviprio1 | RW | 虚拟中断优先级 1 |
| 0x649 | hviprio2 | RW | 虚拟中断优先级 2 |
| 0x64A | htinst | RW | H 级陷阱指令 |
| 0x680 | hgatp | RW | G-stage 地址翻译 |
| 0xE12 | hgeip | RO | Guest 外部中断待处理 |
| 0x60C~60F | hstateen0~3 | RW | H 级状态使能 |
| 0x680 | hcontext | RW | H 级触发上下文（14-bit） |

### 10.4 Virtual Supervisor Level CSR

| 地址 | 名称 | 读写 | 说明 |
|------|------|------|------|
| 0x200 | vsstatus | RW | VS 级状态 |
| 0x204 | vsie | RW | VS 级中断使能 |
| 0x205 | vstvec | RW | VS 级陷阱向量 |
| 0x240 | vsscratch | RW | VS 级暂存 |
| 0x241 | vsepc | RW | VS 级异常 PC |
| 0x242 | vscause | RW | VS 级异常原因 |
| 0x243 | vstval | RW | VS 级异常值 |
| 0x244 | vsip | RW | VS 级中断待处理 |
| 0x24D | vstimecmp | RW | VS 级定时器比较（Sstc） |
| 0x250 | vsiselect | RW | AIA VS 级中断选择 |
| 0x251 | vsireg | RW | AIA VS 级间接寄存器 |
| 0x252~256 | vsireg2~6 | RW | AIA VS 级间接寄存器 2-6 |
| 0x25C | vstopei | RW | VS 级中断 claim（AIA） |
| 0x25D | vstopi | RO | VS 级 Top Interrupt（AIA） |
| 0x280 | vsatp | RW | VS 地址转换与保护 |

### 10.5 Unprivileged CSR

| 地址 | 名称 | 读写 | 说明 |
|------|------|------|------|
| 0x001 | fflags | RW | FP 异常标志 |
| 0x002 | frm | RW | FP 舍入模式 |
| 0x003 | fcsr | RW | FP 控制与状态 |
| 0x008 | vstart | RW | 向量起始索引 |
| 0x009 | vxsat | RW | 向量饱和标志 |
| 0x00A | vxrm | RW | 向量舍入模式 |
| 0x00F | vcsr | RW | 向量控制与状态 |
| 0xC20 | vl | RO | 向量长度 |
| 0xC21 | vtype | RO | 向量类型 |
| 0xC22 | vlenb | RO | 向量寄存器字节长度 |
| 0xC00 | cycle | RO | 周期计数 |
| 0xC01 | time | RO | 时间 |
| 0xC02 | instret | RO | 退休指令计数 |
| 0xC03~C1F | hpmcounter3~31 | RO | 性能计数器 |

### 10.6 Debug Level CSR

| 地址 | 名称 | 读写 | 说明 |
|------|------|------|------|
| 0x7A0 | tselect | WARL | 触发器选择 |
| 0x7A1 | tdata1 | RW | 触发器数据 1（mcontrol6） |
| 0x7A2 | tdata2 | RW | 触发器数据 2 |
| 0x7A4 | tinfo | RO | 触发器信息（VERSION=1, MCONTROL6EN） |
| 0x7B0 | dcsr | RW | Debug 控制与状态 |
| 0x7B1 | dpc | RW | Debug PC |
| 0x7B2 | dscratch0 | RW | Debug 暂存 0 |
| 0x7B3 | dscratch1 | RW | Debug 暂存 1 |

### 10.7 自定义 CSR

| 地址 | 名称 | 读写 | 说明 |
|------|------|------|------|
| 0x5C0 | sbpctl | RW | 分支预测器控制 |
| 0x5C1 | spfctl | RW | 预取器控制 |
| 0x5C2 | slvpredctl | RW | Load 违规预测控制 |
| 0x5C3 | smblockctl | RW | 内存块配置 |
| 0x5C4 | srnctl | RW | 运行时控制 |
| 0xBC0 | mcorepwr | RW | 核心电源管理 |
| 0xBC1 | mflushpwr | RW | L2 缓存刷新控制 |

### 10.8 AIA 间接访问寄存器（通过 miselect/siselect/vsiselect 选择）

| 范围 | 名称 | 说明 |
|------|------|------|
| 0x30~0x3F | miprio0~15 | M 级中断优先级寄存器 |
| 0x30~0x3F | siprio0~15 | S 级中断优先级寄存器 |

---

## 11. 关键源文件位置索引

所有源文件位于 `src/main/scala/xiangshan/backend/fu/NewCSR/` 目录下。

| 文件 | 大小 | 核心内容 |
|------|------|---------|
| **NewCSR.scala** | 73KB | 顶层模块、CSR 映射聚合、事件连接、状态机、TLB 输出、Difftest |
| **MachineLevel.scala** | 37KB | M 模式全部 CSR 定义（mstatus/mie/mip/mhpmevent 等）、MstatusModule、EventInhibitBundle |
| **SupervisorLevel.scala** | 12KB | S 模式 CSR 定义（sie/sip/satp/stimecmp/scountovf 等）、SatpBundle |
| **HypervisorLevel.scala** | 15KB | H 扩展 CSR 定义（hstatus/hedeleg/hideleg/hgatp/hvip/hviprio 等） |
| **VirtualSupervisorLevel.scala** | 11KB | VS 模式 CSR 定义（vsstatus/vsie/vsip/vsatp 等）、VS/S 地址映射 sMapVS |
| **Unprivileged.scala** | 11KB | 非特权 CSR（fcsr/vstart/vcsr/cycle/time/hpmcounter 等）、HasMHPMSink |
| **TrapHandleModule.scala** | 5KB | Trap 路由、优先级裁决、委派链、双重陷阱处理、xtvec 地址计算 |
| **InterruptFilter.scala** | 22KB | 三级中断过滤、AIA 优先级排序（minSelect）、hvictl 注入、NMI/Debug 中断 |
| **CSRPermitModule.scala** | 22KB | CSR 权限检查（6 个子模块）、EX_II/EX_VI 生成、Sm/Ssstateen 检查 |
| **CSRAIA.scala** | 10KB | AIA 相关 CSR（mtopei/stopei/vstopei/mtopi/stopi）、ISelect、TopIBundle/TopEIBundle |
| **DebugLevel.scala** | 16KB | Debug CSR（dcsr/dpc/dscratch）、Trigger 定义（mcontrol6/Tdata1Type/TrigAction）、DebugMMIO |
| **CSRCustom.scala** | 8KB | 自定义 CSR（sbpctl/spfctl/smblockctl/srnctl/mcorepwr/mflushpwr） |
| **CSRDefines.scala** | 9KB | CSR 字段类型定义（RO/RW/WARL/WLRL/PrivMode/SatpMode/HgatpMode/OPTYPE 等） |
| **CSRBundle.scala** | 4KB | CSRBundle 基类和隐式转换（CSRBundleImplicitCast） |
| **CSRBundles.scala** | 9KB | 共享 Bundle 定义（PrivState/CauseBundle/XtvecBundle/Counteren/ContextStatus 等） |
| **CSRFields.scala** | 20KB | CSR 枚举类型实现（CSREnumType/ROApply/RWApply/WARLApply/WLRLApply） |
| **InterruptBundle.scala** | 24KB | 中断向量 Bundle 定义（InterruptNO/NonMaskableIRNO/默认优先级/IprioBundle） |
| **CSREvents/** | 目录 | Trap/Ret 事件模块（trapEntryEvent/mretEvent/sretEvent/dretEvent 等） |
| **SstcInterruptGen.scala** | 1KB | Sstc 扩展定时器中断生成 |
| **CSRIND.scala** | 9KB | 间接 CSR 访问（Sscsrind 扩展的 siselect/sireg 选择逻辑） |
| **CSRPMP.scala** | 5KB | 物理内存保护（PMP）配置处理（PMPEntryHandleModule） |
| **CSRPMA.scala** | 3KB | 物理内存属性（PMA）配置处理（PMAEntryHandleModule） |
| **PFEvent.scala** | 1KB | 性能事件选择器（HPerfMonitor） |
| **Debug.scala** | 13KB | Debug 模式入口逻辑（Debug 模块，综合判断进入 Debug 模式的条件） |
| **CSRModule.scala** | 3KB | CSRModule 基础模块定义（CSRAddrWriteBundle/reg/regOut） |
| **CSRDocDump.scala** | 11KB | CSR 文档导出功能 |
| **ExceptionBundle.scala** | 3KB | 异常编号定义（EXCEPTION_CODE） |
| **CSRAnnotation.scala** | <1KB | CSR 注解支持 |
| **CSRNamedConstant.scala** | <1KB | CSR 命名常量 |
| **CSROoORead.scala** | 1KB | CSR 乱序读支持 |
| **TrapInstMod.scala** | 3KB | Trap 指令处理模块 |
| **TrapTvalMod.scala** | 2KB | Trap tval 值处理模块 |
| **CommitIDModule.scala** | 2KB | Git Commit ID 模块（用于 CSR 文档标识） |

---

## 总结

XiangShan 的 NewCSR 子系统是一个高度模块化的特权架构实现，其核心特点包括：

1. **Trait Mixin 架构**: 通过 Scala 特质系统将 7 个特权层级（Machine/Supervisor/Hypervisor/VirtualSupervisor/Unprivileged/AIA/Debug）解耦为独立模块，通过 NewCSR 顶层组合。每个层级通过自类型约束访问其他层级的成员，形成清晰的依赖关系。

2. **完整的 H-Extension 支持**: 实现了完整的 Hypervisor 扩展，包括两级地址翻译（VS-stage + G-stage，支持 Sv39/Sv48/Sv39x4/Sv48x4）、hvictl 虚拟中断注入、hideleg/hedeleg 委派机制、hvip/hip 中断管理、hviprio1/2 优先级控制。

3. **AIA 深度集成**: 支持 Advanced Interrupt Architecture 的完整中断优先级排序（分治选择算法）、IMSIC 异步访问（三状态机）、ISelect 间接寻址（miselect/siselect/vsiselect）、mtopi/stopi/vstopi 信息报告、mtopei/stopei/vstopei claim 操作。

4. **完善的异常处理**: 三级委派链（medeleg -> hedeleg -> VS）、双重陷阱检测（Smdbltrp/Ssdbltrp，MDT/SDT 位）、Smrnmi 可恢复 NMI（mnepc/mncause/mnstatus）、Debug 中断和 Critical Error 机制。

5. **丰富的性能监控**: 29 个 HPM 计数器、Smcntrpmf 特权级过滤（mcyclecfg/minstretcfg）、Sstc 直接定时器比较（stimecmp/vstimecmp）、溢出中断（LCOFI）、scountovf 快速溢出检测。

6. **微架构可配置性**: 通过自定义 CSR（sbpctl/spfctl/slvpredctl/smblockctl/srnctl/mcorepwr/mflushpwr）实现分支预测器、预取器、内存子系统、电源管理等微架构组件的运行时配置。

7. **严格的安全检查**: CSRPermitModule 由 6 个子模块组成，覆盖所有特权级、虚拟化、AIA、stateen、envcfg 等维度的权限检查，正确区分 EX_II 和 EX_VI 异常类型。

