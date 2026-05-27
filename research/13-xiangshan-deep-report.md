# 香山处理器嵌入式视角技术深度报告

> **版本锚定**：分支 `kunminghu-v3`，commit `f464649442c85122dbb6d71bd77fd0b5e9bec040`
> **报告日期**：2026-05-27
> **分析方法**：纯静态代码分析，禁止编译运行

---

## 第1章 项目全景与代际演进

### 1.1 项目概况

香山（XiangShan）是中国科学院计算技术研究所和北京开源芯片研究院（BOSC）联合开发的开源 RISC-V 处理器。项目采用 Mulan PSL v2 开源许可证，代码仓库位于 `https://github.com/OpenXiangShan/XiangShan`。

**核心数据**：
- Scala 源文件：446 个（`src/main/scala/xiangshan/`）
- 代码行数：约 125,000 行 Scala + 15,000 行 C/C++
- ISA 支持：RV64GC + V + H + 100+ Z 扩展
- 流水线宽度：8 发射 / 8 退役
- ROB 深度：352 条目

### 1.2 代际演进

香山处理器经历了三个主要代际：

| 代际 | 代号 | 状态 | 关键特征 |
|------|------|------|----------|
| 第一代 | 南湖 (Nanhu) | 已发布 | RV64GC，5 发射，256 条 ROB |
| 第二代 | 昆明湖 (Kunminghu) | 活跃开发 | RV64GCVH，8 发射，352 条 ROB |
| 第三代 | 开源南 (暂定) | 规划中 | 更高 IPC，更多扩展 |

**当前主分支**：`kunminghu-v3`（昆明湖 V3 微架构）

### 1.3 微架构差异表

| 特征 | 南湖 (Nanhu) | 昆明湖 (Kunminghu) |
|------|-------------|-------------------|
| 发射宽度 | 6 | 8 |
| 退役宽度 | 6 | 8 |
| ROB 大小 | 256 | 352 |
| 整数物理寄存器 | 160 | 224 |
| 浮点物理寄存器 | 192 | 256 |
| 向量物理寄存器 | 96 | 128 |
| L1D 缓存 | 64KB, 8-way | 64KB, 8-way |
| L2 缓存 | 256KB | 512KB |
| Load 管道 | 2 | 3 |
| Store 管道 | 2 | 2 |
| 分支预测器 | TAGE + SC | TAGE + SC + ITTAGE |
| V 扩展 | 不支持 | 完整支持 |
| H 扩展 | 不支持 | 完整支持 |
| CHI 接口 | 不支持 | 支持 (Issue B/C/E.b) |

### 1.4 核心贡献者

| 排名 | 贡献者 | 提交数 |
|------|--------|--------|
| 1 | Yinan Xu | 1,532 |
| 2 | William Wang | 813 |
| 3 | Zihao Yu | 679 |
| 4 | Lingrui98 | 643 |
| 5 | Xuan Hu | 557 |
| 6 | ZhangZifei | 502 |
| 7 | LinJiawei | 486 |
| 8 | zhanglinjuan | 448 |
| 9 | Allen | 346 |
| 10 | jinyue110 | 286 |

### 1.5 近期代码变更热点

近 12 个月的主要变更集中在：
- **后端重构**：ExceptionVec 替换为 ExceptSparseVec（2026-03~05）
- **Store 修复**：StoreQueue 的 fullOverlap 跨 16B 边界 bug（2026-05）
- **异常处理**：异常向量的统一重构（2026-03~04）
- **Load/Store**：sc 指令的 store 相关异常生成（2026-04）

---

## 第2章 微架构逐层拆解（嵌入式视角）

### 2.1 流水线架构

香山昆明湖采用深流水线、宽发射的乱序执行架构：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    香山昆明湖流水线                                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Frontend (前端)                                                            │
│  ┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐              │
│  │ Fetch  │→│ Decode │→│ Rename │→│Dispatch│→│ Issue  │              │
│  │ 2-wide │  │ 8-wide │  │ 8-wide │  │ 8-wide │  │        │              │
│  └────────┘  └────────┘  └────────┘  └────────┘  └────────┘              │
│      ↑                                                                   │
│      │ BPU: TAGE + SC + ITTAGE + BTB + RAS                               │
│                                                                             │
│  Backend (后端)                                                             │
│  ┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐                          │
│  │ Issue  │→│ Execute│→│Complete│→│Commit  │                          │
│  │ Queue  │  │ 21 FU  │  │        │  │ 8-wide │                          │
│  └────────┘  └────────┘  └────────┘  └────────┘                          │
│                                                                             │
│  MemBlock (内存块)                                                          │
│  ┌────────┐  ┌────────┐  ┌────────┐  ┌────────┐                          │
│  │LoadUnit│  │StoUnit │  │  L1D   │  │  L2    │                          │
│  │ 3 pipe │  │ 2 pipe │  │ 64KB   │  │ 512KB  │                          │
│  └────────┘  └────────┘  └────────┘  └────────┘                          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

**关键参数**（`src/main/scala/xiangshan/Parameters.scala`）：

| 参数 | 值 | 行号 |
|------|-----|------|
| DecodeWidth | 8 | 80 |
| RenameWidth | 8 | 81 |
| CommitWidth | 8 | 82 |
| RobSize | 352 | 111 |
| RabSize | 352 | 112 |
| VTypeBufferSize | 64 | 113 |
| IntPhysRegs | 224 | 118 |
| FpPhysRegs | 256 | 119 |
| VfPhysRegs | 128 | 120 |

### 2.2 分支预测器

香山的分支预测器是业界最复杂的开源实现之一，包含 11 个子预测器：

**BPU 顶层**：`src/main/scala/xiangshan/frontend/bpu/Bpu.scala`，类 `Bpu`（行 47）

| 预测器 | 文件 | 关键参数 |
|--------|------|----------|
| **uBTB** | `bpu/ubtb/Parameters.scala` | 32 条目，22-bit tag |
| **aBTB** | `bpu/abtb/` | 快速低延迟 BTB |
| **uTAGE** | `bpu/utage/` | 快速路径 TAGE |
| **Main BTB** | `bpu/mbtb/Parameters.scala` | **8192 条目**，4-way，16-bit tag |
| **TAGE** | `bpu/tage/Parameters.scala` | **8 个表**，每表 4096 条目（2-way），历史长度：4, 9, 17, 29, 56, 109, 211, 397 |
| **SC** | `bpu/sc/Parameters.scala` | 路径/全局/后向/IMLI 各 2 表，6-bit 计数器 |
| **ITTAGE** | `bpu/ittage/Parameters.scala` | 5 个表（256~512 条目），9-bit tag |
| **RAS** | `bpu/ras/Parameters.scala` | 提交栈：16 条目，推测队列：32 条目 |
| **MicroRAS** | `bpu/ras/` | 快速路径 RAS |
| **PHR** | `bpu/history/phr/` | 路径历史寄存器 |
| **CommonHR** | `bpu/history/commonhr/` | GHR/BWHR/IMLI 共享历史 |

**TAGE 预测器详解**（`bpu/tage/Parameters.scala`）：
- 8 个历史表，覆盖 4~397 周期的历史长度
- 每表 4096 条目，2-way 组相联
- 4 个 bank 并行访问
- 13-bit tag，3-bit taken 计数器
- 使用压缩历史（folded history）减少存储开销

### 2.3 缓存层次结构

#### L1I 缓存（指令缓存）

**文件**：`src/main/scala/xiangshan/frontend/icache/Parameters.scala`

| 参数 | 值 | 行号 |
|------|-----|------|
| Sets | 256 | 29 |
| Ways | 4 | 30 |
| LineSize | 64 bytes | 32 |
| 总大小 | 64 KB | - |
| 替换策略 | set-PLRU | - |
| Fetch MSHR | 4 | 37 |
| Prefetch MSHR | 10 | 38 |
| Way-lookup buffer | 32 条目 | - |
| ECC | 奇偶校验 | - |

#### L1D 缓存（数据缓存）

**文件**：`src/main/scala/xiangshan/cache/dcache/DCacheWrapper.scala`

| 参数 | 值 | 行号 |
|------|-----|------|
| Sets | 128 | 40 |
| Ways | 8 | 41 |
| LineSize | 64 bytes | - |
| 总大小 | 64 KB | - |
| 替换策略 | set-PLRU | - |
| Miss entries | 16 | 269 |
| Probe entries | 8 | 270 |
| Release entries | 18 | 271 |
| Prefetch entries | 6 | - |
| ECC | SECDED | 266-267 |

**L1D 子模块**：
- `LoadPipe`：Load 访问管道
- `StorePipe`：Store 访问管道
- `MainPipe`：主控制管道
- `MissQueue`：缺失处理队列
- `WritebackQueue`：写回队列
- `Probe`：一致性探针处理

#### L2 缓存

**参数**（`Parameters.scala` 行 277-283）：

| 参数 | 值 |
|------|-----|
| Ways | 8 |
| Sets | 1024 |
| LineSize | 64 bytes |
| 总大小 | 512 KB |
| Banks | 1 per tile |
| 预取器 | Receiver, BOP, Temporal Prefetcher |

#### L3 缓存

L3 缓存位于核心外部，在 `xscache.coupledL2` 框架中实现。

### 2.4 内存模型

香山实现了 RISC-V 的 RVWMO（RISC-V Weak Memory Order）内存模型。

**Store Buffer**（`src/main/scala/xiangshan/mem/lsqueue/NewStoreQueue.scala`）：
- StoreQueueSize = 56 条目
- SQUnalignQueue = 2 条目
- StoreBuffer = 16 条目，阈值=9，2-wide 排空到 L1D

**Load Queue**（`Parameters.scala` 行 99-105）：
- VirtualLoadQueue：72 条目
- LoadQueueRAR：72 条目（Read-After-Read 违规检测）
- LoadQueueRAW：32 条目（Read-After-Write 违规检测）
- LoadQueueReplay：72 条目
- LoadUncacheBuffer：16 条目

**内存消歧**（`src/main/scala/xiangshan/mem/mdp/`）：
- WaitTable：1024 条目（2-bit 计数器）
- StoreSet/SSIT：1024 条目
- LFST：32 条目

### 2.5 中断异常处理

**PLIC**（`src/main/scala/device/AXI4Plic.scala`）：
- 最大支持 1023 个设备，15872 个 hart
- 完整的 RISC-V PLIC 规范实现
- 包含 priority、pending、enable、claim/complete 寄存器

**CLINT**（`src/main/scala/device/TIMER.scala`）：
- 实现 MSIP、MTIMECMP、MTIME 寄存器
- 兼容 `riscv,clint0` device tree binding
- 基地址默认 0x02000000

**CSR 子系统**（`src/main/scala/xiangshan/backend/fu/NewCSR/`）：
- 完整的 M/S/U/VU/VS/Debug 模式支持
- AIA（Advanced Interrupt Architecture）支持
- RNMI（Resumable NMI）支持
- 双重异常（Double Trap）支持

### 2.6 功耗管理

**时钟门控**（`Parameters.scala` 行 75）：
- `EnableClockGate: Boolean = true`（默认启用）
- WFI 时钟门控：`SoC.scala` 行 115，可配置
- 细粒度时钟门控：在缓存、预取器等模块中使用 `withClockGate`

**DVFS**：未在 RTL 中找到显式 DVFS 控制器。时钟门控提供了基础的功耗降低能力。

---

## 第3章 RTL 代码拓扑与导航指南

### 3.1 顶层模块树状图

```
XiangShan (top/Top.scala)
├── XSTile (XSTile.scala)
│   ├── XSCore (XSCore.scala)
│   │   ├── Frontend (frontend/Frontend.scala)
│   │   │   ├── BPU (bpu/Bpu.scala)
│   │   │   │   ├── uBTB, aBTB, uTAGE
│   │   │   │   ├── Main BTB
│   │   │   │   ├── TAGE (8 tables)
│   │   │   │   ├── SC
│   │   │   │   ├── ITTAGE
│   │   │   │   └── RAS, MicroRAS
│   │   │   ├── ICache (icache/)
│   │   │   │   ├── ICacheMainPipe
│   │   │   │   ├── ICacheMissUnit
│   │   │   │   └── IPrefetcher
│   │   │   ├── Ftq (Ftq.scala)
│   │   │   ├── Decode (decode/DecodeUnit.scala)
│   │   │   └── IBuffer (ibuffer.scala)
│   │   ├── Backend (backend/Backend.scala)
│   │   │   ├── Rename (rename/)
│   │   │   │   ├── RenameTable
│   │   │   │   ├── FreeList
│   │   │   │   └── Rab (Register Alias Buffer)
│   │   │   ├── Dispatch (dispatch/)
│   │   │   ├── IssueQueue (issue/IssueQueue.scala)
│   │   │   │   ├── IntIQ (6 ALU/BJU + 3 LDU + 2 STA + 2 STD)
│   │   │   │   ├── FpIQ (4 FEX)
│   │   │   │   └── VecIQ (2 VFEX + 2 VLSU)
│   │   │   ├── RegFile (regfile/Regfile.scala)
│   │   │   │   ├── IntRegfile (224 entries, 4 banks)
│   │   │   │   ├── FpRegfile (256 entries)
│   │   │   │   └── VecRegfile (128 entries)
│   │   │   ├── ROB (rob/Rob.scala)
│   │   │   │   ├── RobImp (352 entries)
│   │   │   │   ├── ExceptionGen
│   │   │   │   └── VTypeBuffer
│   │   │   ├── CSR (fu/NewCSR/NewCSR.scala)
│   │   │   │   ├── MachineLevel
│   │   │   │   ├── SupervisorLevel
│   │   │   │   ├── HypervisorLevel
│   │   │   │   └── DebugLevel
│   │   │   └── ExecutionUnits (fu/)
│   │   │       ├── Alu, MulDiv, Brh
│   │   │       ├── Fmac, Fmisc
│   │   │       └── Valu, Vdiv
│   │   └── MemBlock (mem/MemBlock.scala)
│   │       ├── LoadUnit (3 pipes)
│   │       ├── StoreUnit (2 pipes)
│   │       ├── L1D Cache (cache/dcache/)
│   │       ├── LSQ (lsqueue/)
│   │       │   ├── VirtualLoadQueue
│   │       │   ├── LoadQueueRAR/RAW/Replay
│   │       │   └── StoreQueue
│   │       ├── MMU (mmu/)
│   │       │   ├── TLB (itlb, dtlb)
│   │       │   └── PTW (Page Table Walker)
│   │       └── Prefetcher (prefetch/)
│   └── L2Cache (L2Top.scala)
│       └── CoupledL2 (xscache.coupledL2)
├── LLC (外部 L3)
├── MemoryBus / SystemBus
├── PLIC (device/AXI4Plic.scala)
├── CLINT (device/TIMER.scala)
└── DebugModule (device/RocketDebugWrapper.scala)
```

### 3.2 五阶段流水线代码入口

| 阶段 | 入口文件 | 核心类 | 关键行号 |
|------|----------|--------|----------|
| **取指 (Fetch)** | `frontend/Frontend.scala` | `FrontendImp` | - |
| **译码 (Decode)** | `backend/decode/DecodeUnit.scala` | `DecodeUnit` | - |
| **重命名 (Rename)** | `backend/rename/Rename.scala` | `Rename` | - |
| **发射 (Issue)** | `backend/issue/IssueQueue.scala` | `IssueQueueImp` | 行 63 |
| **执行 (Execute)** | `backend/Backend.scala` | `BackendImp` | - |
| **提交 (Commit)** | `backend/rob/Rob.scala` | `RobImp` | 行 59 |

### 3.3 关键组件代码位置

#### ROB (Reorder Buffer)

**文件**：`src/main/scala/xiangshan/backend/rob/Rob.scala`
**类**：`RobImp`（行 59）
**关键参数**：
- RobSize = 352（Parameters.scala 行 111）
- 8-wide 提交
- Banked 实现（行 172）
- 入队条件（行 949）：`numValidEntries + dispatchNum <= (RobSize - RenameWidth)`

#### 物理寄存器堆

**文件**：`src/main/scala/xiangshan/backend/regfile/Regfile.scala`
**类**：`Regfile`（行 67）

| 寄存器堆 | 条目数 | Banks | 数据宽度 |
|----------|--------|-------|----------|
| 整数 | 224 | 4 | 64-bit |
| 浮点 | 256 | 1 | 64-bit |
| 向量 (Vf) | 128 | 1 | 128-bit |
| V0 | 22 | 1 | 128-bit |
| Vl | 32 | 1 | 8-bit |

**寄存器缓存**（Parameters.scala 行 148-149）：24 整数 + 12 内存 = 36 条目

#### 发射队列

**文件**：`src/main/scala/xiangshan/backend/issue/IssueQueue.scala`
**类**：`IssueQueueImp`（行 63）

| 发射块 | 条目数 | 入队宽度 | 压缩条目 |
|--------|--------|----------|----------|
| ALU0+BJU0 | 18 | 2 | 10 |
| ALU1+BJU1 | 18 | 2 | 10 |
| ALU2+BJU2 | 18 | 2 | 10 |
| ALU3 | 20 | 2 | 12 |
| ALU4 | 20 | 2 | 12 |
| ALU5 | 20 | 2 | 12 |
| LDU0/1/2 | 20 each | 2 | 12 |
| STA0/1 | 16 each | 2 | 12 |
| STD0/1 | 16 each | 2 | 12 |
| FEX0-3 | 20 each | 2 | 16 |
| VFEX0/1 | 16 each | 2 | 12 |
| VLSU0/1 | 16 each | 2 | 12 |

### 3.4 Chisel 代码风格评估

| 维度 | 评级 | 说明 |
|------|------|------|
| **参数化程度** | ★★★★★ | 120+ 可配置参数，YAML 支持 |
| **模块化** | ★★★★☆ | 清晰的模块边界，插件式后端 |
| **代码复用** | ★★★★☆ | 通过 trait 和 mixin 实现复用 |
| **可读性** | ★★★☆☆ | 复杂的泛型和隐式参数，学习曲线陡峭 |
| **文档** | ★★☆☆☆ | 代码注释较少，依赖外部文档 |

---

## 第4章 RISC-V 扩展与嵌入式特性

### 4.1 扩展支持矩阵

**ISA 基础**：`rv64i`（Parameters.scala 行 291）

| 扩展 | 状态 | 说明 |
|------|------|------|
| **I** | ✅ 完全实现 | 基础整数指令 |
| **M** | ✅ 完全实现 | 乘除法 |
| **A** | ✅ 完全实现 | 原子操作 |
| **F** | ✅ 完全实现 | 单精度浮点 |
| **D** | ✅ 完全实现 | 双精度浮点 |
| **C** | ✅ 完全实现 | 压缩指令 |
| **V** | ✅ 完全实现 | 向量扩展，VLEN=128 |
| **H** | ✅ 完全实现 | 虚拟化扩展 |
| **Zicsr** | ✅ 完全实现 | CSR 指令 |
| **Zifencei** | ✅ 完全实现 | 指令屏障 |
| **Zba/Zbb/Zbc/Zbs** | ✅ 完全实现 | 位操作 |
| **Zacas** | ✅ 完全实现 | 原子比较交换 |
| **Zicbom/Zicbop/Zicboz** | ✅ 完全实现 | 缓存管理 |
| **Sv39/Sv48** | ✅ 完全实现 | 虚拟内存 |
| **Svinval/Svnapot/Svpbmt** | ✅ 完全实现 | MMU 扩展 |
| **Smrnmi** | ✅ 完全实现 | 可恢复 NMI |
| **Ssaia/Smaia** | ✅ 完全实现 | AIA 中断架构 |
| **Zkn/Zknd/Zkne/Zknh/Zksed/Zksh/Zkt** | ✅ 完全实现 | 加密扩展 |
| **Sstc** | ✅ 完全实现 | 时间比较器 |
| **Smdbltrp/Ssdbltrp** | ✅ 完全实现 | 双重陷阱 |
| **Sdtrig** | ✅ 完全实现 | 调试触发 |

### 4.2 调试支持

**JTAG/Debug Module**：`src/main/scala/device/RocketDebugWrapper.scala`
- 类 `DebugModule` 封装 Rocket Chip 的 `TLDebugModule`
- 支持 `DebugIO`、`ResetCtrlIO`、`SystemJTAGIO`
- 独立版本：`device/standalone/StandAloneDebugModule.scala`

**Debug CSR**：`src/main/scala/xiangshan/backend/fu/NewCSR/DebugLevel.scala`
- 实现 debug mode entry/exit
- `dcsr` 支持 cause 字段（ebreak, trigger, haltreq 等）
- `dpc`、`dscratch0/1` 完整实现

### 4.3 BootROM 与启动流程

**复位向量**：
- 顶层暴露 `io.riscv_rst_vec`（`top/Top.scala` 行 302）
- 核心接口：`XSCore.scala` 行 93，`reset_vector = Input(UInt(PAddrBits.W))`，宽度 48 位
- 通过 `L2Top`（行 167）延迟寄存器同步后传入 `MemBlock`，最终到达 `Frontend`

**无片上 BootROM**：香山是一个核心（core），不是完整 SoC。复位向量是外部输入，由 SoC 集成者提供（通常来自 SoC 封装中的 BootROM 或外部 Flash）。

### 4.4 OS 适配

**Device Tree 生成**：`src/main/scala/xiangshan/XSDts.scala`
- 声明 `SimpleDevice("cpu", Seq("ICT,xiangshan", "riscv"))`
- ISA 字符串：`riscv,isa` = `"rv64imafdcvh"`（废弃字段）+ `riscv,isa-base` + `riscv,isa-extensions`
- 缓存属性：d-cache-size/sets/block-size, i-cache-size/sets/block-size
- TLB 属性：d-tlb-size, i-tlb-size, `mmu-type` = `riscv,sv39`/`sv48`
- 中断控制器设备节点：`compatible = "riscv,cpu-intc"`

**Linux 兼容性**：完全兼容，支持 Sv39/Sv48 虚拟内存，完整的 M/S/U 特权模式。

**FreeRTOS/Zephyr 兼容性**：理论上兼容（RV64IMAC 模式），但无官方测试。

---

## 第5章 验证基础设施与测试体系

### 5.1 构建系统

**Mill 构建**（`build.mill`）：
- Scala 版本：2.13.17
- Chisel 版本：7.3.0
- JVM 堆：40G，栈：256m
- 依赖模块：rocket-chip、utility、yunsuan、XSCache、difftest、ChiselAIA、ChiselIOPMP

**关键 Makefile 目标**：

| 目标 | 说明 |
|------|------|
| `verilog` | 生成可综合 SystemVerilog（默认） |
| `sim-verilog` | 生成仿真 RTL（含 DiffTest） |
| `emu` | 构建 Verilator 仿真器 |
| `simv` | VCS 仿真 |
| `gsim` | GSIM 仿真 |
| `xsim` | GalaxSim 仿真 |
| `pldm-build` | Palladium 仿真 |

### 5.2 DiffTest 框架

**目录**：`difftest/` — 独立框架，约 15,167 行 C/C++ 代码

**核心组件**：
- `difftest/difftest.cpp/h`：差分测试核心逻辑
- `difftest/diffstate.cpp/h`：状态管理
- `common/ram.cpp`：内存模型
- `emu/emu.cpp`：Emulator 主逻辑

**与 NEMU 的对比机制**：
- RTL 执行结果通过 DPI-C 接口传递给 C++ 框架
- C++ 框架调用 NEMU 参考模型进行逐指令对比
- 发现不一致时报告错误并终止仿真

### 5.3 CI 测试

**17 个 workflow 文件**在 `.github/workflows/`：
- `emu.yml`：主仿真测试（Verilator, GSIM）
- `emu-performance.yml`：性能回归测试
- `nightly.yml`：夜间回归测试
- `perf-v3.yml`：SPEC2006 性能评估

**测试类型**：
- CPU 测试：cputest, riscv-tests
- 功能测试：misc-tests, rvh-tests, mc-tests
- 基准测试：coremark, microbench
- SPEC2006：完整的 SPEC CPU2006 评估

### 5.4 FPGA 支持

- `difftest/fpga.mk`：FPGA 构建 Makefile
- `difftest/src/test/csrc/fpga/`：FPGA C++ 源码（XDMA 驱动）
- `FpgaDefaultConfig`、`FpgaDiffDefaultConfig`：FPGA 专用配置
- `FPGA=1` 标志：启用 FPGA 平台模式（禁用 DiffTest，启用时钟门控和复位生成）

---

## 第6章 竞品架构对比与选型决策

### 6.1 开源 RISC-V 处理器对比

| 特征 | 香山 (Kunminghu) | Rocket Chip | BOOM | CVA6 | SweRV |
|------|-----------------|-------------|------|------|-------|
| **ISA** | RV64GCVH | RV64GC | RV64GC | RV64GC | RV32IMC |
| **流水线** | 乱序 | 顺序 | 乱序 | 顺序/乱序 | 顺序 |
| **发射宽度** | 8 | 1 | 4 | 1-2 | 2 |
| **ROB** | 352 | N/A | 32-128 | 16 | N/A |
| **L1D** | 64KB | 16-64KB | 16-64KB | 32KB | 16KB |
| **L2** | 512KB | 可选 | 可选 | 可选 | 32KB |
| **目标** | 高性能 | 教学/SoC | 高性能 | 中端 | 嵌入式 |
| **IPC 估** | 3-4 | 0.5-1 | 2-3 | 1-2 | 1-1.5 |
| **代码量** | 125K Scala | 30K Scala | 20K Scala | 30K SV | 20K SV |

### 6.2 与 ARM Cortex 的映射

| 香山特征 | ARM 对应 | 说明 |
|----------|----------|------|
| 8 发射乱序 | Cortex-A76/A78 | 高性能乱序核心 |
| 352 条 ROB | Cortex-A76 (128) | 更深的乱序窗口 |
| 64KB L1D | Cortex-A55/A76 | 相同缓存大小 |
| 512KB L2 | Cortex-A76 (256-512KB) | 相当 |
| RV64GCVH | ARMv8.2-A + SVE | 向量 + 虚拟化 |

### 6.3 适用场景矩阵

| 场景 | 香山适合度 | 说明 |
|------|-----------|------|
| **工业控制** | ★★★☆☆ | 过于复杂，Rocket Chip 更合适 |
| **边缘 AI** | ★★★★☆ | 向量扩展支持 AI 推理 |
| **车载 MCU** | ★★☆☆☆ | 功耗和面积过大 |
| **网络加速** | ★★★☆☆ | 可定制，但需要大量工作 |
| **桌面/服务器** | ★★★★★ | 主要目标场景 |
| **学术研究** | ★★★★★ | 最好的开源乱序核心参考 |

---

## 第7章 研究产出与行动建议

### 7.1 核心模块深度阅读笔记

#### 模块 1：分支预测器 TAGE

**文件**：`src/main/scala/xiangshan/frontend/bpu/tage/`

TAGE（TAgged GEometric history length）是香山分支预测器的核心组件，采用几何级数增长的历史长度（4, 9, 17, 29, 56, 109, 211, 397 周期），覆盖从短期到长期的分支行为模式。

**关键设计**：
- 8 个历史表，每表 4096 条目，2-way 组相联
- 4 个 bank 并行访问，减少冲突
- 13-bit tag 减少别名冲突
- 3-bit taken 计数器（饱和计数器）
- 使用压缩历史（folded history）减少存储开销

**时序分析**：
- 取指阶段（Fetch）并行查询所有 8 个表
- 最长历史表（397 周期）需要更大的历史寄存器
- 命中时单周期返回预测结果
- 未命中时回退到 uBTB 或默认预测

**代码片段**（`bpu/tage/Parameters.scala`）：
```scala
val TageTableInfos = Seq(
  (4096, 2, 13, 4, 3),    // (entries, ways, tagBits, histLen, ctrBits)
  (4096, 2, 13, 9, 3),
  (4096, 2, 13, 17, 3),
  (4096, 2, 13, 29, 3),
  (4096, 2, 13, 56, 3),
  (4096, 2, 13, 109, 3),
  (4096, 2, 13, 211, 3),
  (4096, 2, 13, 397, 3)
)
```

#### 模块 2：ROB (Reorder Buffer)

**文件**：`src/main/scala/xiangshan/backend/rob/Rob.scala`

ROB 是乱序执行的核心，负责维护指令的程序顺序，确保精确异常和正确提交。

**关键设计**：
- 352 条目，8-wide 提交
- Banked 实现减少端口冲突
- 支持异常生成和处理
- 集成 VTypeBuffer 管理向量类型状态

**时序分析**：
- 入队：Rename 阶段，每周期最多 8 条指令
- 出队：Commit 阶段，每周期最多 8 条指令
- 入队条件：`numValidEntries + dispatchNum <= (RobSize - RenameWidth)`
- 异常处理：ExceptionGen 模块在 Commit 阶段生成异常

#### 模块 3：L1D 缓存

**文件**：`src/main/scala/xiangshan/cache/dcache/DCacheWrapper.scala`

L1D 缓存是内存子系统的核心，直接影响性能。

**关键设计**：
- 128 sets × 8 ways × 64 bytes = 64KB
- SECDED ECC 保护
- 3 条 Load 管道 + 2 条 Store 管道
- 16 条 Miss 条目，8 条 Probe 条目
- 支持预取（BOP, Temporal Prefetcher）

**时序分析**：
- Load 命中：1 周期（L1D 延迟）
- Load 缺失：需要访问 L2，约 10+ 周期
- Store：写入 Store Buffer，后台排空到 L1D
- 一致性：通过 Probe 机制维护多核一致性

### 7.2 嵌入式开发者 30 分钟代码导航指南

#### 场景 1：想改流水线

**起点**：`src/main/scala/xiangshan/XSCore.scala`
- 修改发射宽度：`Parameters.scala` 的 `DecodeWidth`、`RenameWidth`、`CommitWidth`
- 修改 ROB 大小：`Parameters.scala` 的 `RobSize`
- 修改执行单元：`backend/fu/` 目录

#### 场景 2：想加扩展

**起点**：`src/main/scala/xiangshan/backend/decode/DecodeUnit.scala`
- 添加新指令：在 DecodeTable 中添加条目
- 添加新功能单元：`backend/fu/` 中创建新类
- 修改 CSR：`backend/fu/NewCSR/` 目录

#### 场景 3：想调缓存

**起点**：`src/main/scala/xiangshan/Parameters.scala`
- L1D 参数：行 40-41（sets, ways）
- L2 参数：行 277-283
- 预取器：`mem/prefetch/` 目录

### 7.3 潜在优化点

1. **分支预测器面积优化**：TAGE 的 8 个表可以考虑动态关闭低命中率的表
   - 位置：`bpu/tage/`
   - 影响：减少面积，可能轻微降低预测精度

2. **ROB 深度可配置化**：当前 ROB 固定 352 条目，可以做成运行时可配置
   - 位置：`rob/Rob.scala`
   - 影响：提高不同工作负载的适应性

3. **缓存分区**：L1D 缓存可以添加 WAY 分区支持
   - 位置：`cache/dcache/`
   - 影响：支持 QoS 和实时性保证

4. **功耗优化**：添加更细粒度的时钟门控
   - 位置：各模块的 `withClockGate`
   - 影响：降低动态功耗

5. **调试增强**：添加硬件性能分析器（Performance Analyzer）
   - 位置：`backend/fu/NewCSR/`
   - 影响：简化性能调优

### 7.4 给香山社区的建议

1. **文档改进**：当前代码注释较少，建议为每个主要模块添加架构文档
2. **配置简化**：120+ 参数对新手不友好，建议提供更清晰的配置向导
3. **FPGA 优化**：当前 FPGA 支持主要针对 Xilinx，建议扩展到更多平台

---

## 附录 A：代码导航速查表

| 模块 | 文件 | 类 | 关键函数 |
|------|------|-----|----------|
| 顶层 | `top/Top.scala` | `XSTop` | - |
| 核心 | `XSCore.scala` | `XSCoreImp` | - |
| 前端 | `frontend/Frontend.scala` | `FrontendImp` | - |
| BPU | `frontend/bpu/Bpu.scala` | `Bpu` | - |
| TAGE | `frontend/bpu/tage/` | `Tage` | - |
| 译码 | `backend/decode/DecodeUnit.scala` | `DecodeUnit` | - |
| 重命名 | `backend/rename/Rename.scala` | `Rename` | - |
| ROB | `backend/rob/Rob.scala` | `RobImp` | - |
| 发射队列 | `backend/issue/IssueQueue.scala` | `IssueQueueImp` | - |
| 寄存器堆 | `backend/regfile/Regfile.scala` | `Regfile` | - |
| CSR | `backend/fu/NewCSR/NewCSR.scala` | `NewCSR` | - |
| L1D | `cache/dcache/DCacheWrapper.scala` | `DCacheWrapper` | - |
| Load 单元 | `mem/LoadUnit.scala` | `LoadUnit` | - |
| Store 单元 | `mem/StoreUnit.scala` | `StoreUnit` | - |
| MMU | `mem/mmu/MMU.scala` | `MMU` | - |
| PLIC | `device/AXI4Plic.scala` | `AXI4PLIC` | - |
| CLINT | `device/TIMER.scala` | `TIMER` | - |

## 附录 B：术语表

| 术语 | 英文 | 首次出现 |
|------|------|----------|
| 流水线 | Pipeline | 第2章 |
| 分支预测 | Branch Prediction | 第2章 |
| 重排序缓冲 | Reorder Buffer (ROB) | 第2章 |
| 发射队列 | Issue Queue | 第2章 |
| 物理寄存器 | Physical Register | 第2章 |
| 缓存 | Cache | 第2章 |
| 内存模型 | Memory Model | 第2章 |
| 中断控制器 | Interrupt Controller | 第2章 |
| 差分测试 | Differential Testing | 第5章 |
| 乱序执行 | Out-of-Order Execution | 第2章 |

## 附录 C：参考文献与代码引用

1. XiangShan GitHub: https://github.com/OpenXiangShan/XiangShan
2. XiangShan 文档: https://docs.xiangshan.cc/
3. RISC-V 特权规范: https://riscv.org/technical/specifications/
4. RISC-V ISA 扩展: https://riscv.org/technical/specifications/
5. Chisel 文档: https://www.chisel-lang.org/
6. Verilator: https://verilator.org/

---

*报告生成时间：2026-05-27*
*版本锚定：kunminghu-v3 @ f46464944*
