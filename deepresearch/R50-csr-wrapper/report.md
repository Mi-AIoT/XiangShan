# R50 - CSR Wrapper & Pipeline Integration 深度分析

## 1. CSR.scala Wrapper 架构：从单体到分层

### 1.1 旧架构：单体 CSR.scala（已废弃）

XiangShan 项目的 CSR 实现经历了一次重大架构重构。在 `src/main/scala/xiangshan/backend/fu/CSR.scala`（1664 行）中，旧的 CSR 实现已完全被注释掉（第 129 行至第 1664 行被 `/* */` 包裹）。旧架构将所有 CSR 寄存器定义、权限检查、异常处理、性能计数器逻辑全部塞进一个 `class CSR extends FuncUnit` 类中，导致该文件成为整个后端最大的单体模块之一。

旧实现的核心结构如下：

```scala
class CSR(cfg: FuConfig)(implicit p: Parameters) extends FuncUnit(cfg)
  with HasCSRConst with PMPMethod with PMAMethod with HasXSParameter with SdtrigExt with DebugCSR
```

它直接使用 `MaskedRegMap` 机制管理所有 CSR 寄存器映射，包括：
- 基本特权级寄存器（`basicPrivMapping`）
- Hypervisor CSR（`hcsrMapping`）
- 性能计数器映射（`perfCntMapping`）
- PMP/PMA 映射（`pmpMapping`、`pmaMapping`）
- 浮点 CSR（`fcsrMapping`）
- 向量 CSR（`vcsrMapping`）
- Cache 操作自定义 CSR（`cacheopMapping`）

所有映射在最后通过 `mapping = basicPrivMapping ++ perfCntMapping ++ ...` 合并为一个大的 `Map[Int, (UInt, UInt)]`。旧文件中还保留了完整的 `CSRFileIO` bundle 定义、`PerfCounterIO` bundle 定义、`FpuCsrIO` 和 `VpuCsrIO` 接口定义等，这些在新架构中仍然被保留和复用。

### 1.2 新架构：三层分离设计

当前活跃的实现采用了三层分离架构：

**第一层：Wrapper（Pipeline Interface）**
- 文件：`src/main/scala/xiangshan/backend/fu/wrapper/CSR.scala`（约 500 行）
- 职责：作为 `FuncUnit` 的子类，连接流水线 IO 与 CSR 核心模块

**第二层：NewCSR Core（Register Logic）**
- 文件：`src/main/scala/xiangshan/backend/fu/NewCSR/NewCSR.scala`（约 1800+ 行）
- 职责：实现所有 CSR 寄存器的读写逻辑、权限检查、中断过滤
- 通过 Scala trait 混合实现各特权级的 CSR 逻辑：
  - `MachineLevel` — M 模式 CSR
  - `SupervisorLevel` — S 模式 CSR
  - `HypervisorLevel` — H 模式扩展 CSR
  - `VirtualSupervisorLevel` — VS 模式 CSR
  - `Unprivileged` — 非特权级 CSR（fflags、frm、vl 等）
  - `CSRAIA` — Advanced Interrupt Architecture
  - `CSRIND` — 中断委托寄存器
  - `DebugLevel` — Debug 模式 CSR
  - `CSRCustom` — 香山自定义 CSR
  - `CSRPMP` — Physical Memory Protection
  - `CSRPMA` — Physical Memory Attributes

**第三层：辅助模块**
- `TrapInstMod`：Trap 指令追踪模块（`NewCSR/TrapInstMod.scala`）
- `TrapTvalMod`：Trap tval 值管理模块（`NewCSR/TrapTvalMod.scala`）
- `CSRPermitModule`：CSR 访问权限检查模块（`NewCSR/CSRPermitModule.scala`，22KB）
- `InterruptFilter`：中断过滤与优先级仲裁（`NewCSR/InterruptFilter.scala`，22KB）
- `IMSIC_WRAP`：AIA/IMSIC 中断控制器封装
- `TrapHandleModule`：Trap 处理模块（`NewCSR/TrapHandleModule.scala`）
- `SstcInterruptGen`：Sstc 扩展定时器比较中断生成
- `CommitIDModule`：Git Commit SHA 信息模块
- `PFEvent`：性能事件分发模块（`NewCSR/PFEvent.scala`）

这种分离使得 CSR 的流水线接口逻辑（Wrapper）与 CSR 的寄存器级实现（NewCSR）解耦，极大提升了可维护性和可测试性。

---

## 2. CSR-to-NewCSR Bridge：Wrapper 层的桥接机制

### 2.1 Wrapper 类定义

Wrapper 类位于 `wrapper/CSR.scala`，其定义为：

```scala
class CSR(cfg: FuConfig)(implicit p: Parameters) extends FuncUnit(cfg)
  with HasCircularQueuePtrHelper with HasCriticalErrors with HasSoCParameter
```

它通过 `FuncUnit` 的标准 IO 接口（`io.in`、`io.out`、`io.flush`、`io.csrio`）与流水线交互。核心桥接逻辑如下。

### 2.2 输入信号桥接

Wrapper 从流水线 IO（`io.csrio`，类型为 `CSRFileIO`）接收所有来自后端各模块的 CSR 相关信号，然后转发给 `NewCSR` 模块。关键的信号映射包括：

**ROB 异常信号**：Wrapper 将 `CSRFileIO` 中的 `exception` 信号（`ValidIO[ExceptionInfo]`）解包后分别传入 NewCSR 的 `fromRob.trap` 和 `fromRob.commit` 两个接口：
- `csrMod.io.fromRob.trap.valid` — `csrIn.exception.valid`
- `csrMod.io.fromRob.trap.bits.pc` — `csrIn.exception.bits.pc`
- `csrMod.io.fromRob.trap.bits.trapVec` — `csrIn.exception.bits.exceptionVec.asUInt`
- `csrMod.io.fromRob.trap.bits.isInterrupt` — `csrIn.exception.bits.isInterrupt`
- `csrMod.io.fromRob.trap.bits.trigger` — `csrIn.exception.bits.trigger`

**FPU/VPU Commit 信号**：浮点和向量状态的 dirty 标记直接从 `CSRFileIO` 转发：
- `csrMod.io.fromRob.commit.fflags` — `setFflags`（来自 `csrIn.fpu.fflags`）
- `csrMod.io.fromRob.commit.fsDirty` — `setFsDirty`（来自 `csrIn.fpu.dirty_fs`）
- `csrMod.io.fromRob.commit.vsDirty` — `setVsDirty`（来自 `csrIn.vpu.dirty_vs`）
- `csrMod.io.fromRob.commit.vstart` — `setVstart`（来自 `csrIn.vpu.set_vstart`）

**外部中断信号**：直接映射到 NewCSR 的平台中断接口：
- `csrMod.platformIRP.MEIP` — `csrIn.externalInterrupt.meip`
- `csrMod.platformIRP.MTIP` — `csrIn.externalInterrupt.mtip`
- `csrMod.platformIRP.MSIP` — `csrIn.externalInterrupt.msip`
- `csrMod.platformIRP.SEIP` — `csrIn.externalInterrupt.seip`
- `csrMod.nonMaskableIRP.NMI_43` — `csrIn.externalInterrupt.nmi.nmi_43`

**内存异常地址**：从内存子系统获取的异常地址被传入 NewCSR 的 `fromMem` 接口：
- `csrMod.io.fromMem.excpVA` — `csrIn.memExceptionVAddr`
- `csrMod.io.fromMem.excpGPA` — `csrIn.memExceptionGPAddr`
- `csrMod.io.fromMem.excpIsForVSnonLeafPTE` — `csrIn.memExceptionIsForVSnonLeafPTE`

### 2.3 输出信号桥接

NewCSR 模块的输出通过 Wrapper 转发回流水线的 `CSRFileIO`：

- `csrOut.trapTarget` — `csrMod.io.trapTargetPc.bits`（Trap 重定向目标 PC）
- `csrOut.interrupt` — `csrMod.io.status.interrupt`（中断信号）
- `csrOut.wfi_event` — `csrMod.io.status.wfiEvent`（WFI 事件）
- `csrOut.tlb` — Wrapper 内部构造的 `TlbCsrBundle`（TLB 控制信号）
- `csrOut.debugMode` — `csrMod.io.status.debugMode`（调试模式标志）
- `csrOut.fpu.frm` — `csrMod.io.status.fpState.frm`（浮点舍入模式）
- `csrOut.vpu.vstart` — `csrMod.io.status.vecState.vstart`（向量起始索引）
- `csrOut.customCtrl` — `csrMod.io.status.custom`（自定义微架构控制信号）

### 2.4 CSR 读写数据桥接

Wrapper 负责计算 CSR 操作的写数据（`wdata`），这是一个经典的 LookupTree 结构：

```scala
private val wdata = LookupTree(func, Seq(
  CSROpType.wrt  -> src1,
  CSROpType.set  -> (regOut | src1),
  CSROpType.clr  -> (regOut & (~src1).asUInt),
  CSROpType.wrti -> csri,
  CSROpType.seti -> (regOut | csri),
  CSROpType.clri -> (regOut & (~csri).asUInt),
))
```

其中 `regOut` 是 NewCSR 输出的真实寄存器值（无读掩码），`src1` 来自通用寄存器文件，`csri` 是立即数扩展后的值。这种设计将 CSR 操作的语义拆解放在 Wrapper 层，NewCSR 只需处理纯写入。

### 2.5 IMSIC/AIA 桥接

Wrapper 还负责将 NewCSR 与 IMSIC（Incoming MSI Controller）中断控制器连接：

```scala
val imsic = Module(new aia.IMSIC_WRAP(soc.IMSICParams))
imsic.fromCSR.addr.valid := csrMod.toAIA.addr.valid
imsic.fromCSR.addr.bits.addr := csrMod.toAIA.addr.bits.addr
imsic.fromCSR.addr.bits.virt := csrMod.toAIA.addr.bits.v.asUInt.asBool
```

IMSIC 的读数据和中断挂起状态被反馈回 NewCSR，形成完整的 AIA 中断处理回路。

---

## 3. CSR Pipeline Stage（Decode/Execute/Writeback）

### 3.1 CSR 在流水线中的位置

CSR 指令在 XiangShan 的乱序流水线中被分配到整数执行单元（Integer Region）中的一个专用 CSR 功能单元。通过 `FuncUnit` 的配置参数 `cfg.isCsr`，该功能单元被标记为 CSR 专用。在 `FuncUnit.scala` 中，CSR 功能单元拥有额外的 IO 端口：

```scala
val outRFWenAhead3Cycle = OptionWrapper(cfg.isCsr, Output(Bool()))
val outPdestAhead3Cycle = OptionWrapper(cfg.isCsr, Output(UInt(PhyRegIdxWidth.W)))
val csrin = OptionWrapper(cfg.isCsr, new CSRInput)
val csrio = OptionWrapper(cfg.isCsr, new CSRFileIO)
val csrToDecode = OptionWrapper(cfg.isCsr, Output(new CSRToDecode))
```

CSR 指令的执行路径如下：

**Decode 阶段**：指令解码后，CSR 操作码通过 `CSROpType` 编码传递。`CSROpType.isSystemOp(func)` 用于识别 ecall/ebreak/mret/sret/dret 等系统指令，`CSROpType.isCsrAccess(func)` 用于识别 CSR 读写指令。

**Issue 阶段**：CSR 指令从 Issue Queue 被选中后，通过 `io.in`（`DecoupledIO`）发送到 CSR 功能单元。值得注意的是，CSR 功能单元的 `io.in.ready` 直接连接到 `csrMod.io.in.ready`，这意味着如果 NewCSR 内部的 IMSIC 读取被阻塞，整个流水线的 CSR 指令发送会被暂停。

**Execute 阶段**：CSR 指令在 Wrapper 层进行初步解码（提取 CSR 地址、rs1、rd、立即数），然后将操作信息发送到 NewCSR 模块。NewCSR 内部的 `CSRPermitModule` 在同一周期内完成权限检查，判断是否产生 `EX_II`（非法指令）或 `EX_VI`（虚拟指令）。

**Writeback 阶段**：NewCSR 的输出通过 `csrMod.io.out`（`DecoupledIO[NewCSROutput]`）返回。对于普通的 CSR 读写指令，输出需要经过 3 个周期的延迟（`DelayN(csrModOutValid, 3)`），以满足时序要求。但对于 XRet 指令（mret/sret/dret），输出不经过延迟，以最小化重定向延迟。

### 3.2 XRet 指令的特殊处理

XRet 指令（mret/sret/dret/mnret）是 CSR 指令中最关键的，因为它们触发流水线重定向。Wrapper 对 XRet 进行了特殊处理：

```scala
val isXRet = valid && func === CSROpType.jmp && !isEcall && !isEbreak
io.out.valid := Mux(isXRetReg, csrModOutValid, DelayN(csrModOutValid, 3))
```

XRet 指令使用 `RegEnable` 缓存一个周期的延迟（`isXRetReg`），然后直接使用 NewCSR 的输出有效信号，避免额外的 3 周期延迟。重定向目标 PC 通过 `io.out.bits.res.redirect.get.bits.fullTarget` 发送到 ROB 和前端。

### 3.3 CSR 读写的 Ahead 信号

为了支持乱序执行中的 WAW（Write-After-Write）冲突检测，Wrapper 提供了 3 个周期提前的有效信号：

- `io.outRFWenAhead3Cycle`：提前 3 周期的寄存器写使能
- `io.outPdestAhead3Cycle`：提前 3 周期的目标物理寄存器索引
- `io.outValidAhead3Cycle`：提前 3 周期的输出有效信号

这些信号允许 rename 和 dispatch 阶段提前感知 CSR 指令的结果，减少不必要的 stall。

### 3.4 CSRToDecode 输出

Wrapper 将 NewCSR 的 `toDecode` 信号转发给解码阶段：

```scala
csrToDecode := RegNext(csrMod.io.toDecode)
```

`CSRToDecode` Bundle 包含了大量与特权级相关的非法指令检测信号，例如：
- `sfenceVMA`：sfence.vma/sinval.vma 在 TVM=1 时非法
- `fsIsOff`：当 mstatus.FS=Off 时浮点指令非法
- `vsIsOff`：当 mstatus.VS=Off 时向量指令非法
- `wfi`：WFI 在 TW=1 时非法
- `frm`：舍入模式保留值检测
- `cboZ`/`cboCF`：Cache Block 操作的 Envcfg 检查

这些信号在解码阶段就阻止非法指令的执行，避免浪费执行资源。

---

## 4. CSR Bypass and Forwarding

### 4.1 CSR Read Bypass

XiangShan 的 CSR 读操作通过 `DataHoldBypass` 实现 bypass。当 CSR 指令进入执行阶段时，如果后续指令需要读取同一个 CSR 寄存器，Wrapper 使用 `DataHoldBypass` 将最新的数据直接前递给后续指令，而不需要等待 CSR 寄存器文件的写回：

```scala
exceptionVec(EX_BP)    := DataHoldBypass(isEbreak, false.B, io.in.fire)
exceptionVec(EX_MCALL) := DataHoldBypass(isEcall && privState.isModeM, false.B, io.in.fire)
```

### 4.2 RegOut 机制

NewCSR 模块输出的 `regOut` 是未经读掩码的原始寄存器值。Wrapper 在构造 `wdata` 时使用 `regOut`（而非经过读掩码的 `rData`），这确保了 CSR 的 set/clr 操作基于正确的当前值：

```scala
private val regOut = csrMod.io.out.bits.regOut
CSROpType.set  -> (regOut | src1),
CSROpType.clr  -> (regOut & (~src1).asUInt),
```

### 4.3 WriteData 延迟寄存

Wrapper 将写入数据和地址寄存一个周期：

```scala
private val waddrReg = RegEnable(addr, 0.U(12.W), io.in.fire)
private val wdataReg = RegEnable(wdata, 0.U(64.W), io.in.fire)
```

这个延迟寄存器不仅用于 NewCSR 的写入数据，还用于 distributed CSR write signal（分发给前端和内存子系统的 CSR 写信号），确保它们看到的地址和数据是稳定的。

### 4.4 RobIdx Bypass

CSR 指令的 ROB 索引也实现了 bypass 逻辑，确保 redirect flush 判断的正确性：

```scala
when (io.in.valid) {
  thisRobIdx := io.in.bits.ctrl.robIdx
}.otherwise {
  thisRobIdx := robIdxReg
}
private val redirectFlush = thisRobIdx.needFlush(io.flush)
```

### 4.5 输出数据延迟策略

对于普通 CSR 指令，Wrapper 使用 `DelayNWithValid` 对所有输出信号进行统一的 3 周期延迟：

```scala
io.out.bits.res.data := DelayNWithValid(csrMod.io.out.bits.rData, csrModOutValid, 3)._2
```

但对于 XRet 指令，所有控制信号绕过延迟直接输出，因为 XRet 的延迟由 Redirect 机制保证。这种"分路径延迟"策略是 CSR Wrapper 的核心设计特征。

---

## 5. Exception Handling Flow through CSR

### 5.1 异常信息的传递路径

异常信息从 ROB 到 CSR 的传递路径如下：

1. **ROB 生成异常**：ROB 在 commit 阶段检测到异常时，通过 `io.exception`（`ValidIO[ExceptionInfo]`）发送异常信息
2. **Backend 转发**：`Backend.scala` 中 `csrio.exception := ctrlBlock.io.robio.exception` 将异常转发给 CSR 功能单元
3. **Wrapper 接收**：CSR Wrapper 从 `io.csrio` 接收异常信息，转发给 NewCSR 的 `fromRob.trap` 接口
4. **NewCSR 处理**：NewCSR 内部的 `TrapHandleModule` 根据异常类型和当前特权级确定目标特权级和跳转目标

### 5.2 异常向量生成

Wrapper 在接收到 NewCSR 的输出后，根据特权状态和指令类型生成异常向量：

```scala
exceptionVec(EX_BP)    := DataHoldBypass(isEbreak, false.B, io.in.fire)
exceptionVec(EX_MCALL) := DataHoldBypass(isEcall && privState.isModeM, false.B, io.in.fire)
exceptionVec(EX_HSCALL) := DataHoldBypass(isEcall && privState.isModeHS, false.B, io.in.fire)
exceptionVec(EX_VSCALL) := DataHoldBypass(isEcall && privState.isModeVS, false.B, io.in.fire)
exceptionVec(EX_UCALL) := DataHoldBypass(isEcall && privState.isModeHUorVU, false.B, io.in.fire)
exceptionVec(EX_II)    := csrMod.io.out.bits.EX_II
exceptionVec(EX_VI)    := csrMod.io.out.bits.EX_VI
```

注意：ECALL 指令的特权级编码（MCALL/HSCALL/VSCALL/UCALL）直接由 NewCSR 输出的 `privState` 决定，而非法指令异常（EX_II/EX_VI）由 NewCSR 内部的 `CSRPermitModule` 产生。

### 5.3 Trap 重定向目标

NewCSR 输出两个关键的 PC 目标：

1. **`trapTargetPc`**：异常/中断处理的入口地址，由 `TrapHandleModule` 根据 `xtvec` 寄存器和异常号计算。当 `xtvec.MODE=1`（Vectored）时，中断向量基址加上 `causeNO * 4`。
2. **`xretTargetPc`**：从异常返回的目标地址（mepc/sepc/dpc），由 NewCSR 内部根据异常返回指令直接读取。

Wrapper 将这两个目标通过 `csrOut.trapTarget` 统一输出给 ROB，由 ROB 决定使用哪个目标进行流水线重定向。

### 5.4 Trap 指令追踪

Wrapper 内部实例化了 `TrapInstMod` 模块，用于追踪当前 trap 指令的详细信息：

```scala
trapInstMod.io.fromDecode.trapInstInfo := RegNextWithEnable(io.csrin.get.trapInstInfo, hasInit = true)
trapInstMod.io.faultCsrUop.valid := csrMod.io.out.valid && (csrMod.io.out.bits.EX_II || csrMod.io.out.bits.EX_VI)
```

当 CSR 指令产生非法指令异常（EX_II/EX_VI）时，`faultCsrUop` 信号会清除当前的 trap 指令信息，确保错误的 CSR 指令不会被错误地记录为 trap 的来源。

### 5.5 TrapTvalMod

Wrapper 还实例化了 `TrapTvalMod` 模块，用于管理 trap 时的 tval 寄存器值。该模块接收 `targetPc` 和异常类型信息，在异常发生时锁定正确的 tval 值，防止后续指令覆盖。

### 5.6 Flush 管理

当 CSR 指令触发 flush（如 satp 写入、XRet、frm 变化等）时，Wrapper 通过 `io.out.bits.ctrl.flushPipe.get` 发出 flush 信号。在 NewCSR 中，`redirectFlush` 信号用于取消当前正在进行但已被重定向的 CSR 操作。

---

## 6. Interrupt Injection Timing

### 6.1 中断信号生成

NewCSR 内部的中断处理分为两个关键模块：

**InterruptFilter 模块**：负责中断过滤和优先级仲裁。它接收来自多个来源的中断信号：
- 平台中断（`platformIRP`）：MEIP、MTIP、MSIP、SEIP
- NMI（`nonMaskableIRP`）：不可屏蔽中断 NMI_43、NMI_31
- AIA 中断（`fromAIA`）：来自 IMSIC 的中断
- 内部中断：软件触发的中断

InterruptFilter 根据当前特权级（`privState`）、全局中断使能（`mstatus.MIE`、`sstatus.SIE`、`vsstatus.SIE`）、中断委托寄存器（`mideleg`、`hideleg`）以及中断使能寄存器（`mie`、`sie`、`hip`、`hie`）综合判断哪些中断处于 pending 状态。

### 6.2 中断注入时机

中断信号通过 `csrOut.interrupt` 输出到 ROB。ROB 在 commit 阶段检查该信号，如果检测到中断且当前指令是"中断安全"的（`interrupt_safe`），则触发异常处理流程。

时序关键点：
- 外部中断信号（`platformIRP`）在进入 NewCSR 前经过 Wrapper 层的 `RegNext` 同步
- 中断过滤在一个周期内完成，输出在下一周期有效
- 中断目标 PC 的计算与异常处理共用 `TrapHandleModule`
- NMI 作为不可屏蔽中断，独立于全局中断使能，直接通过 `nmip` 寄存器传递

### 6.3 WFI 事件

WFI 事件通过 `csrOut.wfi_event` 输出，用于前端的功耗管理。WFI 不依赖全局中断使能位，而是检查所有未屏蔽的中断：

```scala
csrio.wfi_event := debugIntr || (mie(11, 0) & mip.asUInt).orR
```

在 NewCSR 中，这一逻辑被更精确地实现为检查每个中断源的独立使能和挂起状态。

### 6.4 Debug 中断

Debug 中断（`platformIRP.debugIP`）有独立的使能控制（`debugIntrEnable`）。当 `debugIntrEnable` 为 true 且 debug 中断有效时，处理器进入 Debug Mode。进入 Debug Mode 后，`debugIntrEnable` 被清零，直到执行 `dret` 指令时才恢复。

NewCSR 中的 SstcInterruptGen 模块为 Sstc 扩展生成定时器比较中断，支持 CLINT 和 stimecmp 寄存器的比较逻辑。

---

## 7. CSR Commit Interface with ROB

### 7.1 RobCommitCSR Bundle

CSR 与 ROB 之间的 commit 接口定义在 `CSRBundles.scala` 中的 `RobCommitCSR`：

```scala
class RobCommitCSR(implicit p: Parameters) extends Bundle {
  val instNum = ValidIO(UInt(7.W))   // 已退休指令数（最多128条/周期）
  val fflags  = ValidIO(Fflags())    // 浮点异常标志（5位）
  val fsDirty = Bool()               // 浮点状态脏位
  val vxsat   = ValidIO(Vxsat())    // 向量饱和标志
  val vsDirty = Bool()               // 向量状态脏位
  val vtype   = ValidIO(new CSRVTypeBundle)  // 向量类型配置
  val vl      = Vl()                 // 向量长度
  val vstart  = ValidIO(Vstart())   // 向量起始索引
}
```

这个 Bundle 承载了 ROB 在 commit 阶段需要传递给 CSR 的所有信息。Wrapper 将这些信号逐一映射：

```scala
csrMod.io.fromRob.commit.fflags := setFflags
csrMod.io.fromRob.commit.fsDirty := setFsDirty
csrMod.io.fromRob.commit.instNum.valid := true.B
csrMod.io.fromRob.commit.instNum.bits  := csrIn.perf.retiredInstr
```

### 7.2 异常信息传递

ROB 的异常信息通过 `ValidIO[ExceptionInfo]` 传递，包含以下关键字段：
- `pc`：异常指令的 PC
- `gpaddr`：Guest 物理地址（用于 H Extension）
- `exceptionVec`：64 位异常向量，每位对应一种异常类型
- `isInterrupt`：是否为中断
- `singleStep`：是否为单步调试异常
- `trigger`：触发器动作信息（前端/后端触发器命中向量和可触发射线向量）
- `crossPageIPFFix`：跨页取指异常修正标志
- `isHls`：是否为 Hypervisor Load/Store 指令
- `isFetchMalAddr`：是否为取指地址错误
- `isForVSnonLeafPTE`：是否为 VS 模式非叶页表项错误

### 7.3 ROB DeqPtr 与 Flush

ROB 通过 `robDeqPtr` 通知 CSR 当前的 commit 指针，CSR 使用此指针来判断哪些异常信息已经过时。当 ROB 发出 flush 信号时，CSR 内部需要取消挂起的操作：

```scala
csrMod.io.fromRob.robDeqPtr := csrIn.robDeqPtr
trapTvalMod.io.fromCtrlBlock.flush := io.flush
trapTvalMod.io.fromCtrlBlock.robDeqPtr := io.csrio.get.robDeqPtr
```

在 `RobCSRIO`（定义于 `RobBundles.scala`）中，接口包括：
- `intrBitSet`：中断位设置
- `trapTarget`：Trap 重定向目标
- `wfiEvent`：WFI 事件
- `fflags`/`dirty_fs`/`vxsat`/`vstart`/`vl`/`vtype`：FPU/VPU commit 状态
- `criticalErrorState`：关键错误状态

### 7.4 Performance Counter Commit

性能计数器的退休指令数通过 `perf.retiredInstr`（7 位）传递，每次最多支持 128 条指令退休。这个值在 NewCSR 内部被用于更新 `minstret` 寄存器和各 HPM 计数器。性能事件还来自控制信息（`ctrlInfo`，包括 robFull/intdqFull/fpdqFull/lsdqFull）和内存信息（`memInfo`，包括 sqFull/lqFull/dcacheMSHRFull）。

---

## 8. CSR Performance Counters Output

### 8.1 性能计数器架构

XiangShan 支持 29 个硬件性能计数器（hpmcounter3 ~ hpmcounter31），加上基本的 `mcycle` 和 `minstret`。性能计数器的事件源来自多个模块：

- **前端事件**（`perfEventsFrontend`）：取指、分支预测等
- **后端事件**（`perfEventsBackend`）：执行、重命名、dispatch 等
- **LSU 事件**（`perfEventsLsu`）：加载、存储队列状态等
- **缓存层次事件**（`perfEventsHc`）：L1/L2 缓存命中率等

### 8.2 旧 CSR 中的性能计数器（已废弃）

在旧的 CSR.scala 中，性能计数器通过 `MaskedRegMap` 直接映射到 CSR 地址空间。每个计数器都有一个事件选择寄存器（`Mhpmevent`），通过 64 位掩码选择底层事件。事件计数使能受 `mcountinhibit` 和特权级模式（通过 `perfEventscounten`）控制。

### 8.3 新 CSR 中的性能计数器

在 NewCSR 架构中，性能计数器通过独立的 `CSRModule` 实现。每个计数器都是一个独立的模块，通过 `csrRwMap` 注册到 CSR 地址空间。事件选择逻辑在 NewCSR 内部实现，支持灵活的事件选择和掩码配置。性能计数器的读写权限检查由 `CSRPermitModule` 中的 `perfcntPermissionCheck` 方法处理，基于 `mcounteren` 和 `scounteren`。

### 8.4 PFEvent 分发

Wrapper 通过 `custom.distribute_csr` 将 CSR 写信号分发给前端和内存子系统的 PFEvent 模块：

```scala
custom.distribute_csr.w.valid := csrMod.io.distributedWenLegal
custom.distribute_csr.w.bits.addr := waddrReg
custom.distribute_csr.w.bits.data := wdataReg
```

`PFEvent` 模块接收这些分发的写信号，更新本地的性能事件选择寄存器。这确保了前端和内存子系统可以独立配置自己的性能监控事件。

### 8.5 isPerfCnt 标记

Wrapper 通过 `csrOut.isPerfCnt` 标记当前 CSR 读操作是否针对性能计数器，这个信号用于 Difftest 跳过检查：

```scala
csrOut.isPerfCnt := io.out.valid && csrMod.io.out.bits.isPerfCnt && 
  RegEnable(func =/= CSROpType.jmp, false.B, io.in.fire)
```

### 8.6 分布式性能监控

NewCSR 的 `distributedWenLegal` 输出确保只有合法的 CSR 写操作才会被分发到前端和内存子系统。这防止了非法写操作影响性能计数器的正确性。

---

## 9. Source File Locations

### 9.1 核心 CSR 文件

| 文件路径 | 大小 | 说明 |
|---------|------|------|
| `src/main/scala/xiangshan/backend/fu/CSR.scala` | 1664 行 | 旧 CSR 实现（已完全注释废弃） |
| `src/main/scala/xiangshan/backend/fu/wrapper/CSR.scala` | ~500 行 | 当前活跃的 CSR Wrapper，连接流水线与 NewCSR |
| `src/main/scala/xiangshan/backend/fu/NewCSR/NewCSR.scala` | ~1800 行 | CSR 核心模块，混合 MachineLevel/SupervisorLevel/HypervisorLevel 等 trait |

### 9.2 NewCSR 子模块

| 文件路径 | 大小 | 说明 |
|---------|------|------|
| `NewCSR/CSRPermitModule.scala` | 22KB | CSR 访问权限检查 |
| `NewCSR/MachineLevel.scala` | 37KB | M 模式 CSR 实现 |
| `NewCSR/SupervisorLevel.scala` | 12KB | S 模式 CSR 实现 |
| `NewCSR/HypervisorLevel.scala` | 15KB | H 模式 CSR 实现 |
| `NewCSR/VirtualSupervisorLevel.scala` | 11KB | VS 模式 CSR 实现 |
| `NewCSR/DebugLevel.scala` | 16KB | Debug 模式 CSR 实现 |
| `NewCSR/CSRBundles.scala` | 9KB | CSR Bundle 定义（PrivState、RobCommitCSR 等） |
| `NewCSR/CSRFields.scala` | 20KB | CSR 字段类型定义 |
| `NewCSR/CSRDefines.scala` | 9KB | CSR 枚举定义 |
| `NewCSR/CSRAIA.scala` | 10KB | AIA 接口 |
| `NewCSR/CSRIND.scala` | 9KB | 中断委托寄存器 |
| `NewCSR/CSRModule.scala` | 3KB | CSR 模块基类 |
| `NewCSR/InterruptFilter.scala` | 22KB | 中断过滤与优先级仲裁 |
| `NewCSR/InterruptBundle.scala` | 24KB | 中断 Bundle 定义 |
| `NewCSR/CSRPMP.scala` | 5KB | Physical Memory Protection |
| `NewCSR/CSRPMA.scala` | 3KB | Physical Memory Attributes |
| `NewCSR/CSRCustom.scala` | 9KB | 自定义 CSR |
| `NewCSR/CSRDocDump.scala` | 11KB | CSR 文档生成 |
| `NewCSR/Unprivileged.scala` | 11KB | 非特权级 CSR |
| `NewCSR/PFEvent.scala` | 1KB | 性能事件分发 |
| `NewCSR/CSRAnnotation.scala` | <1KB | CSR 地址注解 |
| `NewCSR/CSRNamedConstant.scala` | <1KB | CSR 命名常量 |
| `NewCSR/CSROoORead.scala` | 1KB | 乱序读支持 |
| `NewCSR/CSRBundle.scala` | 4KB | CSR Bundle 基类 |
| `NewCSR/CommitIDModule.scala` | 2KB | Git Commit SHA |
| `NewCSR/PMPEntryModule.scala` | 6KB | PMP 条目模块 |
| `NewCSR/PMAEntryModule.scala` | 6KB | PMA 条目模块 |
| `NewCSR/StateEnBundle.scala` | 4KB | 状态使能 Bundle |
| `NewCSR/SstcInterruptGen.scala` | 1KB | Sstc 定时器中断 |
| `NewCSR/IndirectCSRPermitModule.scala` | 7KB | 间接 CSR 权限检查 |
| `NewCSR/ExceptionBundle.scala` | 3KB | 异常 Bundle |

### 9.3 CSREvents 子目录

| 文件路径 | 说明 |
|---------|------|
| `NewCSR/CSREvents/CSREvent.scala` | 事件基类与接口定义 |
| `NewCSR/CSREvents/TrapEntryMEvent.scala` | M 模式 Trap 入口事件 |
| `NewCSR/CSREvents/TrapEntryHSEvent.scala` | HS 模式 Trap 入口事件 |
| `NewCSR/CSREvents/TrapEntryVSEvent.scala` | VS 模式 Trap 入口事件 |
| `NewCSR/CSREvents/TrapEntryDEvent.scala` | Debug 模式 Trap 入口事件 |
| `NewCSR/CSREvents/TrapEntryMNEvent.scala` | MN 模式 Trap 入口事件 |
| `NewCSR/CSREvents/MretEvent.scala` | MRet 事件 |
| `NewCSR/CSREvents/SretEvent.scala` | SRet 事件 |
| `NewCSR/CSREvents/DretEvent.scala` | DRet 事件 |
| `NewCSR/CSREvents/MNretEvent.scala` | MNRet 事件 |

### 9.4 相关集成文件

| 文件路径 | 说明 |
|---------|------|
| `backend/Backend.scala` | 后端顶层，连接 CSR 与 ROB/前端/内存子系统 |
| `backend/Region.scala` | 执行区域划分，CSR 在 Integer Region 中 |
| `backend/fu/FuncUnit.scala` | 功能单元基类，定义 CSR IO 接口 |
| `backend/rob/Rob.scala` | ROB 模块，包含 CSR commit 接口 |
| `backend/rob/RobBundles.scala` | ROB Bundle 定义，包含 RobCSRIO |

---

## 总结

XiangShan 的 CSR 实现从旧的单体架构重构为三层分离设计：Wrapper 层（`wrapper/CSR.scala`）负责流水线接口适配和信号桥接；NewCSR 核心层（`NewCSR/NewCSR.scala`）通过 trait 混合实现各特权级的 CSR 逻辑；辅助模块层提供中断过滤、权限检查、Trap 处理等独立功能。这种架构使得 CSR 的实现更加模块化、可测试，同时通过 Ahead 信号和 XRet 特殊路径优化了关键路径的时序。CSR 与 ROB 的交互通过精心设计的 `RobCommitCSR` Bundle 实现，支持异常信息传递、性能计数器更新和浮点/向量状态管理。整个 CSR 子系统涉及约 40 个源文件，总计超过 300KB 的 Chisel 代码，是 XiangShan 后端最复杂的子系统之一。
