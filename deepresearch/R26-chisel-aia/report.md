# R26 - ChiselAIA：RISC-V 高级中断架构的 Chisel 开源实现

## 1. AIA 架构概述（Advanced Interrupt Architecture）

### 1.1 背景与动机

RISC-V Advanced Interrupt Architecture (AIA) 是 RISC-V 架构中定义的高级中断子系统标准，旨在为多核、多特权级（Machine、Supervisor、Virtual Supervisor）以及虚拟化环境提供统一且可扩展的中断管理框架。传统的 RISC-V PLIC（Platform-Level Interrupt Controller）在中断优先级、虚拟化支持以及中断路由灵活性方面存在不足，AIA 通过引入两个核心组件——APLIC（Advanced Platform-Level Interrupt Controller）和 IMSIC（Incoming Message-Signaled Interrupt Controller）——来解决这些问题。

ChiselAIA 是由北京开源芯片研究院（Beijing Institute of Open Source Chip, BOSC）开发的 AIA 开源 Chisel 实现，采用 Mulan PSL v2 许可证发布。与现有的 Verilog 实现（如 OpenXiangShan/OpenAIA、zero-day-labs/riscv-aia）不同，ChiselAIA 利用 Chisel 硬件构造语言的参数化与组合抽象能力，实现了高度灵活、可配置的 AIA 控制器。

### 1.2 APLIC（Advanced Platform-Level Interrupt Controller）

APLIC 是 AIA 架构中的平台级中断控制器，负责管理外部线中断（wired interrupt sources），并将其转换为 MSI（Message-Signaled Interrupt）消息发送给 IMSIC。ChiselAIA 中的 APLIC 实现了以下核心特性：

- **中断源管理**：支持最多 1023 个中断源（`aplicIntSrcWidth` 最大为 10），每个中断源具有独立的配置寄存器（sourcecfg），支持 inactive、detached、edge1、edge0、level1、level0 六种模式。
- **双域架构**：APLIC 内部包含两个 Domain——Machine-level domain（domain 0）和 Supervisor-level domain（domain 1）。Machine domain 拥有全部中断源，Supervisor domain 通过 delegation 机制接收被 Machine domain 委托的中断源。
- **MSI 发送模式**：当前实现仅支持 MSI delivery mode（`domaincfg.DM=1`），不支持 direct delivery mode。APLIC 将线中断信号转换为 MSI 写事务，通过 TileLink 或 AXI4 总线发送至 IMSIC。
- **中断优先级判定**：通过 `topi`（Top Pending Interrupt）机制，采用 `ParallelPriorityMux` 实现对所有 pending 且 enabled 的中断源进行并行优先级仲裁。
- **GenMSI 机制**：支持通过 `genmsi` 寄存器发送任意中断的外发 MSI，无需等待中断源实际触发。

### 1.3 IMSIC（Incoming Message-Signaled Interrupt Controller）

IMSIC 是 AIA 架构中的 MSI 中断接收控制器，每个 CPU hart 都有自己独立的 IMSIC 实例。IMSIC 负责接收来自 APLIC 或其他 MSI 发送设备的中断消息，并维护每个中断的 pending/enable 状态。核心特性包括：

- **多中断文件（Interrupt Files）**：每个 IMSIC 实例包含多个中断文件，分别对应 Machine mode、Supervisor mode 和多个 Virtual Supervisor mode（由 `geilen` 参数控制，默认 7 个 guest interrupt files）。中断文件总数为 `2 + geilen`。
- **间接 CSR 访问**：通过 `iselect` 寄存器间接访问 eidelivery、eithreshold、eips、eies 等 CSR，支持 CSRRW、CSRRS、CSRRC 三种操作。
- **中断优先级判定**：内部实现 `topei`（Top External Interrupt Priority）计算逻辑，采用 `ParallelPriorityMux` 对所有 pending 且 enabled 的中断进行优先级仲裁，并通过 eithreshold 进行阈值过滤。
- **Claim 机制**：支持通过 `claim` 信号完成中断声明（claim），声明后自动清除最高优先级 pending 中断的 pending 位。
- **异步桥支持**：通过 `EnableImsicAsyncBridge` 参数可选启用异步时钟域桥接（基于 `AsyncResetSynchronizerShiftReg`），支持 IMSIC 工作在与 SoC 总线不同的时钟域。
- **TEE IMSIC 扩展**：通过 `HasTEEIMSIC` 参数支持 TEE（Trusted Execution Environment）安全扩展，可以实例化独立的 TEE IMSIC 中断文件，由 `cmode` 信号控制在 REE/TEE 模式之间切换。

### 1.4 中断源编号与优先级

AIA 规范定义了标准中断编号体系：
- 1-3: Supervisor Software Interrupt (SSI), Virtual Supervisor Software Interrupt (VSSI), Machine Software Interrupt (MSI)
- 5-7: Supervisor Timer Interrupt (STI), Virtual Supervisor Timer Interrupt (VSTI), Machine Timer Interrupt (MTI)
- 9-11: Supervisor External Interrupt (SEI), Virtual Supervisor External Interrupt (VSEI), Machine External Interrupt (MEI)
- 13+: Local interrupts（SGEI, LCOFI 等）

优先级从高到低的默认排序由 `InterruptNO.interruptDefaultPrio` 定义，在 XiangShan 的 InterruptFilter 模块中实现完整的优先级仲裁。

---

## 2. ChiselAIA 模块结构与关键类

### 2.1 项目结构

ChiselAIA 项目以独立 git 仓库形式存在，通过 `.gitmodules` 集成为 XiangShan 的子模块。项目使用 Mill 构建系统，依赖 Chisel 6.5.0 和 Rocket-Chip。

```
ChiselAIA/
  src/main/scala/
    common.scala           -- 公共类型定义（RegMapDV、OpType、PrivType、Bundle 定义）
    IMSICParameters.scala  -- IMSIC 参数定义与 trait
    IMSIC.scala            -- IMSIC 核心实现（IMSICGateWay、IntFile、IMSIC、IMSIC_WRAP 等）
    APLIC.scala            -- APLIC 核心实现（Domain、APLIC、TLAPLIC、AXI4APLIC）
    Example.scala          -- TileLink AIA 顶层示例（TLAIA）
    Example-axi.scala      -- AXI4 AIA 顶层示例（AXI4AIA）
  test/
    common.py              -- cocotb 测试公共函数与常量
    imsic/main.py          -- IMSIC 单元测试
    aplic/main.py          -- APLIC 单元测试
    integration/main.py    -- 集成测试
    axi/main.py            -- AXI 总线测试
```

### 2.2 关键类层次结构

ChiselAIA 的设计遵循 Rocket-Chip 的 Diplomacy 框架，区分了 LazyModule（硬件描述的静态拓扑）和 Module（实际硬件逻辑）：

**APLIC 相关类：**
- `APLIC` — 核心 APLIC Module，包含两个 Domain 实例
- `TLAPLIC` — TileLink 接口的 APLIC LazyModule 封装
- `AXI4APLIC` — AXI4 接口的 APLIC LazyModule 封装

**IMSIC 相关类：**
- `IMSICGateWay` — MSI 网关，接收外部 MSI 写事务并生成中断文件索引
- `IntFile` — 单个中断文件，维护 eips/eies/eidelivery/eithreshold
- `IMSIC` — 单个 IMSIC 实例核心，包含 Gateway 和多个 IntFile
- `IMSIC_WRAP` — IMSIC 包装器，支持 TEE/REE 双 IMSIC 实例
- `TLIMSIC` — TileLink 接口的 IMSIC LazyModule
- `AXI4IMSIC` — AXI4 接口的 IMSIC LazyModule
- `TLRegIMSIC` / `AXIRegIMSIC` — TileLink/AXI4 寄存器接口层
- `TLRegIMSIC_WRAP` / `AXIRegIMSIC_WRAP` — 寄存器接口层包装器，处理 REE/TEE 地址映射
- `RegGen` — 寄存器生成器，桥接总线寄存器访问与 IMSIC 内部逻辑

**公共组件：**
- `RegMapDV` — 自定义寄存器映射生成器
- `AXI4ToLite` — AXI4 到 AXI4-Lite 的总线适配器
- `AXI4Map` / `TLMap` — 地址映射适配器
- `AXI4ToTLNoTLError` — 无错误转发的 AXI4 到 TileLink 适配器
- `TLRegMapperNode` / `AXI4RegMapperNode` — 自定义 Register Mapper 节点

---

## 3. IMSIC 设计详解

### 3.1 IMSICGateWay（MSI 网关）

`IMSICGateWay` 是 IMSIC 的入口模块，负责接收来自 APLIC 或其他 MSI 发送设备的 MSI 写事务。其核心设计如下：

- **输入接口**：`MSITransBundle`，包含 `vld_req`（请求有效）、`data`（MSI 信息编码）和 `vld_ack`（确认信号）。
- **MSI 信息格式**：`data` 的低位（`imsicIntSrcWidth` 位）编码中断源 ID，高位（`INTP_FILE_WIDTH` 位）编码目标中断文件索引。
- **握手机制**：采用请求-确认的握手协议。当 `vld_req` 上升沿到来时，`msi_vld_ack_cpu` 置高以确认，`msi_vld_ris_cpu` 通过 `vld_req & ~vld_ack` 检测上升沿。
- **异步桥**：当 `EnableImsicAsyncBridge` 为 true 时，`vld_req` 通过 3 级异步同步器（`AsyncResetSynchronizerShiftReg`）进入 CPU 时钟域。
- **输出**：`msi_data_o` 为中断源 ID，`msi_valid_o` 为 one-hot 编码的中断文件选择向量。

### 3.2 IntFile（中断文件）

`IntFile` 是 IMSIC 的核心数据结构，每个特权级模式对应一个 IntFile 实例。其内部寄存器包括：

- **eidelivery**（iselect 0x70）：中断投递使能，bit[0] 为投递使能位，其余位必须为 0。
- **eithreshold**（iselect 0x72）：中断优先级阈值，仅优先级编号严格小于阈值的中断才被视为 pending。
- **eips[]**（iselect 0x80-0xBF）：中断 pending 状态寄存器组，按 64 位分组。注意 `eips[0]`（bit 0）为只读零。
- **eies[]**（iselect 0xC0-0xFF）：中断 enable 状态寄存器组，同样按 64 位分组。`eies[0]`（bit 0）为只读零。

CSR 访问通过 `RegMapDV.generate` 方法实现，支持 CSRRW/CSRRS/CSRRC 操作码。写入逻辑通过 Chisel 的 Conditional Last Connect Semantics 实现优先级控制：
1. 寄存器映射的写操作（setips、setipnum 等）
2. 中断源触发（intSrcsTriggered 设置 ip）
3. MSI 发送完成（清除 ip）

### 3.3 IMSIC 核心模块

`IMSIC` 模块实例化一个 `IMSICGateWay` 和 `2 + geilen` 个 `IntFile` 实例（M、S、VS0、VS1...VSgeilen）。关键设计：

- **中断文件选择**：根据 CSR 访问的特权级（priv）和虚拟化模式（virt）生成 one-hot 选择信号 `intFilesSelOH_r/w`。M-mode 选择 intFile[0]，S-mode 选择 intFile[1]，VS-mode 根据 vgein 值选择 intFile[2+vgein-1]。
- **权限检查**：非法特权级访问（如 M-mode + virt=1、S-mode + vgein 越界）产生 illegal 信号。
- **topei 计算**：每个 IntFile 独立计算 topei，通过 `ParallelPriorityMux` 对 `(eip & eie)` 进行优先级仲裁。topei 经过 `xtopei_filter` 函数应用 eidelivery 和 eithreshold 过滤。
- **topei 格式**：按照 AIA 规范，topei 的 bit[26:16] 和 bit[10:0] 都编码中断 ID（通过 `wrap` 函数实现位复制）。
- **Claim 操作**：当 `claim` 信号有效时，清除最高优先级 pending 中断的 eip 位。清除操作通过 `RegNext` 延迟一拍实现。
- **MSI 设置 eip**：`seteipnum` 通过 `UIntToOH` 转换为 one-hot 后与现有 eips 进行 OR 操作，实现原子置位。

### 3.4 IMSIC_WRAP（TEE 扩展封装）

`IMSIC_WRAP` 通过 `HasTEEIMSIC` 参数控制是否启用 TEE 双实例模式：

- **单实例模式**（`HasTEEIMSIC=false`）：仅实例化一个 `IMSIC` 模块。
- **双实例模式**（`HasTEEIMSIC=true`）：实例化两个 `IMSIC` 模块（REE 和 TEE），通过 `cmode` 信号在运行时切换：
  - `cmode=0`：访问 REE IMSIC 的 CSR 和中断文件
  - `cmode=1`：访问 TEE IMSIC 的 CSR 和中断文件（Machine 中断始终来自 REE IMSIC）
  - `notice_pending` 信号：反映非当前模式下的 pending 中断状态，用于跨域中断通知

---

## 4. 中断路由与优先级仲裁

### 4.1 APLIC 中断路由

APLIC 的中断路由分为以下步骤：

1. **中断源采集**：外部中断源通过 `intSrcs` 端口输入，经过 3 级同步器（`RegNextN`）去抖动。
2. **中断整形**：根据 sourcecfg 的 SM 字段（edge1/edge0/level1/level0）对中断信号进行整形（`intSrcsRectified`），产生极性校正后的中断信号。
3. **边沿检测**：通过 `rect & !RegNext(rect)` 检测上升沿，生成 `intSrcsTriggered` 脉冲信号。
4. **Pending 位设置**：`intSrcsTriggered` 触发 `ips.wBitUI(i, true.B)`，设置对应中断的 pending 位。对于 level 模式，还受 `domaincfg.DM`（MSI 模式）限制。
5. **优先级仲裁**：`topi` 通过 `ParallelPriorityMux` 对所有 `(ip[i] & ie[i])` 进行优先级编码。
6. **MSI 发送**：当 `genmsi.Busy` 为 true 时优先发送 genmsi，否则发送 topi 对应的中断。MSI 地址通过 `getMSIAddr` 函数计算，结合 groupID、memberID、guestID 和中断文件基地址。发送完成后清除对应 ip 位。

### 4.2 XiangShan InterruptFilter 优先级仲裁

在 XiangShan 处理器中，`InterruptFilter` 模块实现了完整的 AIA 优先级仲裁逻辑。该模块处理三个层级的中断判定：

**Machine Level (mtopi)：**
- 汇聚 `mtopigather = mip & mie & (~mideleg)` 得到 M 级未委托中断
- 对每个中断按默认优先级排序，考虑 `miprios` 和 `mtopei` 优先级
- MEI（Machine External Interrupt）的优先级特殊处理：当 AIA 有效（`fromAIAValid` 或 `flag`）时使用 mtopei 中的 IPRIO，否则使用默认值
- 最终输出 mtopi 的 IID 和 IPRIO

**Supervisor Level (stopi)：**
- 汇聚 `hstopigather = (hip | sip) & (hie | sie) & (~hideleg)`
- 类似 M 级的优先级仲裁，使用 `hsiprios` 和 `stopei`
- SEI 的优先级同样考虑 AIA 有效状态

**Virtual Supervisor Level (vstopi)：**
- 通过 Candidate 机制实现复杂的虚拟中断判定：
  - Candidate 1/2/3：处理 vstopei 中的 SEI 相关中断（vsip.SEIP && vsie.SEIE）
  - Candidate 4/5：处理 hvictl 虚拟注入和 vstopigather 中断
- 支持 hvictl.IID/vtictl.IPRIO 虚拟中断注入（Candidate 2/5）
- 支持 DPR（Default Priority Reserved）位控制优先级默认值
- 最终输出 vstopi 的 IID 和 IPRIO

**中断仲裁与投递：**
- M 级中断：当 `privState >= ModeM` 且 `mstatusMIE` 时投递
- HS 级中断：当 `privState >= ModeHS` 且 `sstatusSIE` 时投递
- VS 级中断：当 `privState >= ModeVS` 且 `vsstatusSIE` 时投递
- 优先级从高到低：M > HS > VS
- 支持 NMI（Non-Maskable Interrupt）覆盖正常中断
- 支持 Debug 中断
- 支持 `mnstatusNMIE` 全局中断禁止

---

## 5. AIA CSR 集成

### 5.1 CSRAIA Trait

在 XiangShan 的 `NewCSR` 模块中，`CSRAIA` trait 定义了所有 AIA 相关的 CSR 模块：

**Top-of-Interrupt CSRs（只读）：**
- `mtopi`（CSR 地址在 CSRs.mtopi）：M 级最高优先级 pending 中断，IID[27:16] 为中断标识，IPRIO[7:0] 为优先级
- `stopi`（CSR 地址在 CSRs.stopi）：S 级最高优先级 pending 中断
- `vstopi`（CSR 地址在 CSRs.vstopi）：VS 级最高优先级 pending 中断

这三个 CSR 的值由 `InterruptFilter` 模块计算后通过 `HasInterruptFilterSink` trait 注入。

**Top-of-External-Interrupt CSRs（读写，IMSIC 接口）：**
- `mtopei`（CSR 地址在 CSRs.mtopei）：M 级最高优先级外部中断声明寄存器，IID[26:16] 和 IPRIO[10:0] 均可读写
- `stopei`（CSR 地址在 CSRs.stopei）：S 级最高优先级外部中断声明寄存器
- `vstopei`（CSR 地址在 CSRs.vstopei）：VS 级最高优先级外部中断声明寄存器

这三个 CSR 的值由 IMSIC 模块通过 `AIAToCSRBundle` 接口提供。

**中断优先级寄存器（间接访问）：**
- `miprio0`-`miprioF`：M 级中断优先级寄存器，通过 miselect 间接访问
- `siprio0`-`siprioF`：S 级中断优先级寄存器，通过 siselect 间接访问

### 5.2 ISelect 间接寻址

AIA 规范通过 ISelect 机制间接访问 IMSIC 的中断文件 CSR。XiangShan 定义了三种 ISelect 字段：

- `MISelectField`：M 级中断选择器，范围 0x00-0xFF
- `SISelectField`：S 级中断选择器，范围 0x000-0xFFF
- `VSISelectField`：VS 级中断选择器，范围 0x000-0xFFF

每种字段都有保留范围约束（如 0x00-0x2F、0x40-0x6F 为保留区域）。

### 5.3 CSRBundle 定义

- `TopIBundle`：包含 IID（27:16，只读）和 IPRIO（7:0，只读）
- `TopEIBundle`：包含 IID（26:16，读写）和 IPRIO（10:0，读写）
- `Iprio0Bundle`：定义 PrioSSI(15:8), PrioVSSI(23:16), PrioMSI(31:24), PrioSTI(47:40), PrioVSTI(55:48), PrioMTI(63:56)
- `MIprio2Bundle`：定义 PrioSEI(15:8), PrioVSEI(23:16), PrioMEI(31:24, 只读), PrioSGEI(39:32), PrioLCOFI(47:40), Prio14(55:48), Prio15(63:56)

---

## 6. 与 XiangShan NewCSR 模块的交互

### 6.1 接口定义

ChiselAIA 与 XiangShan NewCSR 通过两组 Bundle 进行交互：

**CSRToAIABundle**（NewCSR -> IMSIC）：
- `addr`：ValidIO，包含 iselect 地址、虚拟化模式（v）和特权级（prvm）
- `vgein`：Guest Interrupt File 索引
- `wdata`：ValidIO，包含操作码（CSRRW/CSRRS/CSRRC）和写入数据
- `mClaim/sClaim/vsClaim`：中断声明信号

**AIAToCSRBundle**（IMSIC -> NewCSR）：
- `rdata`：ValidIO，包含读取数据和 illegal 标志
- `meip/seip`：Machine/Supervisor External Interrupt Pending
- `notice_pending`：TEE/REE 跨域中断通知
- `vseip`：Virtual Supervisor External Interrupt Pending 向量
- `mtopei/stopei/vstopei`：各特权级的 Top External Interrupt 信息

### 6.2 NewCSR 集成

在 `NewCSR.scala` 中，AIA 集成的关键代码路径：

```scala
class NewCSR(...) extends Module
  with CSRAIA    // 混入 AIA trait
  with HypervisorLevel {
  
  // 实例化 InterruptFilter
  val intrMod = Module(new InterruptFilter)
  
  // 连接 topei 到 InterruptFilter
  intrMod.io.in.mtopei := mtopei.regOut
  intrMod.io.in.stopei := stopei.regOut
  intrMod.io.in.vstopei := vstopei.regOut
  
  // 连接 AIA 输入到各 CSR 模块
  aiaCSRMods.foreach { case m: HasAIABundle =>
    m.aiaToCSR.rdata.valid := fromAIA.rdata.valid
    m.aiaToCSR.mtopei := fromAIA.mtopei
    m.aiaToCSR.stopei := fromAIA.stopei
    m.aiaToCSR.vstopei := fromAIA.vstopei
  }
  
  // 连接 InterruptFilter 输出到 topi CSR
  aiaCSRMods.foreach { case m: HasInterruptFilterSink =>
    m.topIR.mtopi := intrMod.io.out.mtopi
    m.topIR.stopi := intrMod.io.out.stopi
    m.topIR.vstopi := intrMod.io.out.vstopi
  }
}
```

### 6.3 数据流总结

完整的中断处理数据流如下：

1. **中断源** -> APLIC `intSrcs` 端口
2. APLIC 对中断进行整形、触发检测、优先级仲裁
3. APLIC 通过 TileLink/AXI4 总线向 IMSIC 的 `seteipnum` 地址写入 MSI 数据
4. IMSIC 的 `RegGen` 模块接收总线写事务，生成 `{中断文件索引, 中断源ID}` 信息
5. IMSIC 的 `IMSICGateWay` 接收 MSI 信息，分发到对应的 `IntFile`
6. `IntFile` 设置对应中断的 eip 位
7. `IMSIC` 计算各特权级的 `topei`
8. `NewCSR` 通过 `AIAToCSRBundle` 获取 topei 和 pending 信号
9. `InterruptFilter` 结合 mtopei/stopei/vstopei 和各特权级的 mip/mie/sip/sie/hip/hie 信号
10. `InterruptFilter` 计算 mtopi/stopi/vstopi，并决定最终的中断投递向量
11. 处理器执行中断服务程序

---

## 7. 多 Hart 支持

### 7.1 多 Hart 地址映射

ChiselAIA 通过 `APlicParams` 和 `IMSICParams` 的组合参数实现多 hart 支持：

- **groupsNum**：IMSIC 组的数量（对应 AIA 规范中的 g_max）
- **membersNum**：每组中的成员数量（对应 h_max）
- **groupStrideWidth**：组间地址间距
- **mStrideWidth/sgStrideWidth**：M/S/G 中断文件间地址间距

在 Example.scala 中的 TLAIA 顶层模块展示了 4 个 hart 的配置：
```scala
val imsic_params = IMSICParams(EnableImsicAsyncBridge = false)
val aplic_params = APLICParams(groupsNum=2, membersNum=2)
```

### 7.2 Hart Index 编码

`hartIndex_to_gh` 方法将线性 hart index 编码为 (groupID, memberID) 二元组：
```scala
def hartIndex_to_gh(hartIndex: Int): (Int, Int) = {
  val g = (hartIndex >> membersWidth) & (pow2(groupsWidth) - 1)
  val h = hartIndex & (pow2(membersWidth) - 1)
  (g, h)
}
```

### 7.3 地址计算

每个 IMSIC 实例的中断文件地址通过以下公式计算：
- **M-mode 地址**：`groupID * groupStride + mBaseAddr + memberID * mStride`
- **S/G-mode 地址**：`groupID * groupStride + sgBaseAddr + memberID * sgStride`

APLIC 通过 `getMSIAddr` 函数将 HartIndex、GuestIndex 编码为 MSI 目标地址：
```scala
def getMSIAddr(HartIndex: UInt, guestID: UInt): UInt = {
  imsicBaseAddr.U |
    (groupID << groupStrideWidth) |
    (memberID << imsicMemberStrideWidth) |
    (guestID << intFileMemWidth)
}
```

### 7.4 XiangShan 集成配置

在 XiangShan 的 SoC.scala 中，IMSIC 默认配置为：
```scala
IMSICParams: aia.IMSICParams = aia.IMSICParams(
  imsicIntSrcWidth = 9,          // 支持最多 512 个中断源
  mAddr = 0x3A800000,            // M-mode 中断文件基地址
  sgAddr = 0x3B000000,           // S/G-mode 中断文件基地址
  geilen = 7,                    // 7 个 guest interrupt files
  vgeinWidth = 6,                // vgein 信号位宽
  iselectWidth = 12,             // iselect 信号位宽
  EnableImsicAsyncBridge = true, // 启用异步桥
  HasTEEIMSIC = false            // 不启用 TEE IMSIC
)
```

---

## 8. 测试基础设施

### 8.1 测试框架

ChiselAIA 使用 **cocotb** 作为仿真测试框架，基于 Python 协程编写测试用例，通过 VPI 接口与 Verilog 仿真器交互。测试分为四个层次：

1. **common.py** -- 公共测试库，提供 TileLink 事务操作函数和 AIA 寄存器操作封装
2. **imsic/main.py** -- IMSIC 单元测试
3. **aplic/main.py** -- APLIC 单元测试
4. **integration/main.py** -- APLIC + IMSIC 集成测试

### 8.2 公共测试函数（common.py）

提供以下关键封装：

**总线操作：**
- `a_put_full32(dut, addr, data)`：发送 32 位 TileLink 写事务
- `a_get32(dut, addr)`：发送 32 位 TileLink 读事务并返回结果
- `interrupt(dut, i)`：模拟中断源触发，等待 topei 更新

**IMSIC 操作：**
- `init_imsic(dut, imsicID)`：初始化指定 IMSIC 的所有中断文件（使能 eidelivery 和 eies）
- `select_m_intfile(dut, imsicID)`：选择 M-mode 中断文件（priv=3, virt=0）
- `select_s_intfile(dut, imsicID)`：选择 S-mode 中断文件（priv=1, virt=0）
- `select_vs_intfile(dut, vgein, imsicID)`：选择 VS-mode 中断文件（priv=1, virt=1, vgein=N）
- `write_csr(dut, iselect, data, imsicID)`：通过间接 CSR 写入
- `read_csr(dut, iselect, imsicID)`：通过间接 CSR 读取
- `m_int/s_int/v_int_vgein(dut, intnum, imsicID, guestID)`：向 M/S/VS 中断文件发送 MSI
- `claim(dut, imsicID)`：触发中断声明

**APLIC 常量：** 定义了所有 APLIC 寄存器偏移量（domaincfg、sourcecfg、setips、seties、genmsi、targets 等）和 sourcecfg 模式常量。

### 8.3 IMSIC 单元测试

`imsic/main.py` 中的 `imsic_1_test` 覆盖以下测试场景：

1. **mseteipnum**：通过 MSI 设置 M-mode 中断 pending 位，验证 topei 正确更新
2. **mclaim**：声明中断后验证 topei 清零
3. **多中断优先级**：同时设置多个中断，验证最高优先级中断被正确选择
4. **CSR 操作**：测试 CSRRS/CSRRC 操作码对 eidelivery 的影响
5. **eithreshold**：验证中断优先级阈值过滤功能
6. **eip/eie 读写**：通过间接 CSR 读写 eip 和 eie 寄存器
7. **S-mode 测试**：切换到 S-mode 中断文件，验证 Supervisor 中断处理
8. **VS-mode 测试**：使用 vgein=2 测试 Virtual Supervisor 中断处理
9. **非法访问测试**：验证非法 iselect、非法 vgein、非法操作码、非法特权级访问的 illegal 信号
10. **eip0[0] 只读零测试**：验证 eip0 的 bit 0 为只读零

### 8.4 APLIC 单元测试

`aplic/main.py` 包含五个测试用例：

1. **aplic_write_read_test**：验证 domaincfg、sourcecfg、setips、seties、targets、readonly0 等寄存器的读写正确性，包括 WARL 行为
2. **aplic_set_clr_test**：测试 setienum/clrienum/setipnum_le 等 set/clear 寄存器语义
3. **aplic_triggered_int_test**：验证 edge1/edge0/level1/level0 四种中断源模式的触发行为
4. **aplic_in_clrips_test**：验证 in_clrips 寄存器正确反映 rectified 中断状态
5. **aplic_msi_test**：验证 APLIC 将中断转换为 MSI 的完整流程，包括 targets 路由、genmsi 发送、delegation 机制

### 8.5 集成测试

`integration/main.py` 中的 `integration_simple_test` 验证 APLIC + IMSIC 的完整中断路径：
1. 初始化 APLIC（domaincfg、sourcecfg1-63、target1-63、ie 使能）
2. 初始化 IMSIC0
3. 通过中断源触发中断，验证中断从外部输入到 IMSIC topei 的完整通路

---

## 9. 关键源文件位置

### 9.1 ChiselAIA 核心文件

| 文件路径 | 描述 |
|---------|------|
| `ChiselAIA/src/main/scala/IMSIC.scala` | IMSIC 核心实现（945 行），包含 IMSICGateWay、IntFile、IMSIC、IMSIC_WRAP、TLIMSIC、AXI4IMSIC、TLRegIMSIC_WRAP、AXIRegIMSIC_WRAP、TLRegIMSIC、AXIRegIMSIC、RegGen |
| `ChiselAIA/src/main/scala/APLIC.scala` | APLIC 核心实现（417 行），包含 Domain、APLIC、TLAPLIC、AXI4APLIC |
| `ChiselAIA/src/main/scala/common.scala` | 公共组件（629 行），包含 RegMapDV、OpType、PrivType、MSITransBundle、CSRToIMSICBundle、IMSICToCSRBundle、IMSICParams、AXI4ToLite、AXI4Map、AXI4ToTLNoTLError、TLRegMapperNode、AXI4RegMapperNode |
| `ChiselAIA/src/main/scala/IMSICParameters.scala` | IMSIC 参数定义（23 行），包含 IMSICParameters case class 和 HasIMSICParameters trait |
| `ChiselAIA/src/main/scala/Example.scala` | TL AIA 顶层示例（127 行），TLAIA LazyModule 和 Verilog 生成入口 |
| `ChiselAIA/src/main/scala/Example-axi.scala` | AXI4 AIA 顶层示例（131 行），AXI4AIA LazyModule 和 Verilog 生成入口 |

### 9.2 XiangShan 集成文件

| 文件路径 | 描述 |
|---------|------|
| `src/main/scala/xiangshan/backend/fu/NewCSR/CSRAIA.scala` | AIA CSR 定义（289 行），CSRAIA trait、TopIBundle/TopEIBundle、ISelect 字段、CSRToAIABundle/AIAToCSRBundle |
| `src/main/scala/xiangshan/backend/fu/NewCSR/InterruptFilter.scala` | 中断过滤与优先级仲裁（630 行），mtopi/stopi/vstopi 计算逻辑、Candidate 机制、中断投递判定 |
| `src/main/scala/xiangshan/backend/fu/NewCSR/NewCSR.scala` | NewCSR 顶层模块，混入 CSRAIA trait，实例化 InterruptFilter，连接 AIA 接口 |
| `src/main/scala/system/SoC.scala` | SoC 参数定义，IMSICParams 默认配置 |

### 9.3 测试文件

| 文件路径 | 描述 |
|---------|------|
| `ChiselAIA/test/common.py` | cocotb 测试公共库（238 行），TileLink 事务函数、IMSIC/APLIC 操作封装、常量定义 |
| `ChiselAIA/test/imsic/main.py` | IMSIC 单元测试（217 行），覆盖 pending/claim/eidelivery/eithreshold/eip/eie/illegal 等 |
| `ChiselAIA/test/aplic/main.py` | APLIC 单元测试（262 行），覆盖寄存器读写/set-clr/触发模式/in_clrips/MSI 发送 |
| `ChiselAIA/test/integration/main.py` | 集成测试（49 行），验证 APLIC+IMSIC 完整中断通路 |

---

## 总结

ChiselAIA 是 RISC-V AIA 架构的一个完整 Chisel 实现，提供了 APLIC 和 IMSIC 两大核心组件。其设计特点包括：

1. **高度参数化**：通过 `APLICParams` 和 `IMSICParams` 支持灵活配置中断源数量、地址映射、hart 数量、guest interrupt file 数量等。
2. **总线接口多样性**：同时支持 TileLink 和 AXI4 总线接口，通过 Diplomacy 框架实现总线拓扑自动生成。
3. **虚拟化完整支持**：IMSIC 支持 M/S/VS 三个特权级的独立中断文件，APLIC 支持 domain delegation 机制。
4. **异步时钟域**：支持 IMSIC 工作在与 SoC 总线不同的时钟域，通过异步桥实现跨时钟域同步。
5. **TEE 安全扩展**：通过 HasTEEIMSIC 参数支持 TEE/REE 双 IMSIC 实例，用于可信执行环境场景。
6. **完善的测试覆盖**：通过 cocotb 框架实现 IMSIC、APLIC 和集成三个层次的测试，覆盖了中断处理的核心路径。
7. **与 XiangShan 深度集成**：通过 CSRAIA trait、InterruptFilter 模块和 AIAToCSRBundle/CSRToAIABundle 接口实现与 XiangShan 处理器的无缝集成。
