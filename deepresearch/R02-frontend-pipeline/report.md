# XiangShan Frontend Pipeline 研究报告

## 目录
1. [概述](#1-概述)
2. [Frontend 顶层架构](#2-frontend-顶层架构)
3. [IFU (Instruction Fetch Unit) 流水线](#3-ifu-instruction-fetch-unit-流水线)
4. [FTQ (Fetch Target Queue)](#4-ftq-fetch-target-queue)
5. [ICache 与预取](#5-icache-与预取)
6. [IBuffer 指令缓冲](#6-ibuffer-指令缓冲)
7. [InstrUncache 非缓存取指](#7-instruncache-非缓存取指)
8. [SimFrontend 仿真前端](#8-simfrontend-仿真前端)
9. [前端与后端接口](#9-前端与后端接口)
10. [Pipeline 控制：Stall, Flush, Redirect](#10-pipeline-控制stall-flush-redirect)
11. [关键源文件索引](#11-关键源文件索引)
12. [总结](#12-总结)

---

## 1. 概述

XiangShan 是一个开源的高性能 RISC-V 处理器，其 Frontend (前端) 负责从 ICache 取出指令，经过预解码、指令边界识别、预测校验等步骤后，将指令送入后端的 Decode 阶段。前端流水线的设计目标是最大化指令带宽 (Fetch Width)，同时高效处理分支预测、MMIO/非缓存访问、以及来自后端的各种 redirect。

XiangShan 前端的核心设计采用多级流水线 (S0-S4)，以 Fetch Block 为基本单位，支持双 cacheline 取指 (double-line fetch)，并集成 BPU (Branch Prediction Unit) 进行投机取指。前端的关键模块包括：

- **Ftq (Fetch Target Queue)**：管理取指目标地址，协调 BPU 预测与 IFU 取指
- **Ifu (Instruction Fetch Unit)**：四级流水线 (S0-S3) + Write-back 阶段，执行实际取指
- **I Cache**：指令缓存，提供高速指令数据
- **IBuffer**：在前端和后端之间提供指令缓冲
- **InstrUncache**：处理 MMIO/非缓存指令取指
- **BPU (Branch Prediction Unit)**：分支预测单元（详见 R03）

---

## 2. Frontend 顶层架构

### 2.1 Frontend 模块层次

```
Frontend (LazyModule)
  └── FrontendInlined (LazyModule)
      ├── InstrUncache (LazyModule)  -- 非缓存取指
      ├── ICache (LazyModule)        -- 指令缓存
      └── FrontendInlinedImp (Imp)
          ├── Bpu                    -- 分支预测单元
          ├── Ifu                    -- 取指单元
          ├── IBuffer                -- 指令缓冲
          ├── Ftq                    -- 取指目标队列
          ├── ITLB                   -- 指令 TLB
          ├── PMP                    -- 物理内存保护
          └── PTW Repeater           -- 页表遍历中继
```

**关键设计点**：`Frontend` 外层是一个 `LazyModule` wrapper，内部是 `FrontendInlined`，后者才是真正实例化各子模块的地方。根据 `env.EnableSimFrontend` 配置，可以切换为 `SimFrontendInlinedImp` (仿真前端) 或 `FrontendInlinedImp` (真实前端)。

### 2.2 FrontendIO 接口定义

`FrontendIO` 定义了前端与处理器其他部分的全部外部接口：

| 接口信号 | 方向 | 说明 |
|---------|------|------|
| `hartId` | Input | 硬件线程 ID |
| `reset_vector` | Input | 复位向量 (起始 PC) |
| `sfence` | Input | SFENCE 指令信号，用于 TLB/Cache 刷新 |
| `fencei` | Input | FENCE.I 指令信号，用于 ICache 刷新 |
| `ptw` | Bidir | 页表遍历 (Page Table Walk) 接口 |
| `backend` | Bidir | FrontendToCtrlIO，前端与后端的控制流接口 |
| `tlbCsr` | Input | TLB 相关 CSR 配置 |
| `csrCtrl` | Input | 自定义 CSR 控制信号 (分支预测开关、prefetch 使能等) |
| `softPrefetch` | Input | 软件预取请求 |
| `error` | Output | L1 总线错误信息 |

### 2.3 模块间连接关系

在 `FrontendInlinedImp` 中，各模块的连接关系如下：

```
                BPU
                 │
    ┌────────────┤
    │  fromBpu   │  toBpu
    │            │
    ▼            │
   FTQ ─────────┤
    │            │
    ├── toIfu ──► IFU ◄── fromICache
    │  fromIfu   │  │──► toICache
    │            │  │──► toIBuffer
    │            │  │──► toUncache ◄──► InstrUncache
    │            │  │──► toBackend
    │            │
    ├── toICache ─► ICache
    │  fromICache  │──► ITLB ──► PTW
    │              │──► PMP
    │
    └── toBackend ──► 后端 Decode
```

关键的 ready-valid 握手控制：

```scala
ftq.io.toIfu.req.ready := ifu.io.fromFtq.req.ready && icache.io.fromFtq.fetchReq.ready
ftq.io.toICache.fetchReq.ready := ifu.io.fromFtq.req.ready && icache.io.fromFtq.fetchReq.ready
```

这意味着 FTQ 向 IFU 和 ICache 发送 fetch 请求时，需要同时满足 IFU 准备好接收、ICache 准备好提供数据两个条件。

---

## 3. IFU (Instruction Fetch Unit) 流水线

### 3.1 流水线级数与阶段图

IFU 采用 **4 级流水线 + 1 级 Write-back** 的设计：

```
┌─────┐   ┌─────┐   ┌─────┐   ┌─────┐   ┌──────────┐
│ S0  │──►│ S1  │──►│ S2  │──►│ S3  │──►│ WB Stage │
│     │   │     │   │     │   │     │   │(回写阶段) │
└─────┘   └─────┘   └─────┘   └─────┘   └──────────┘
 取指请求   ICache    指令对齐    发送IBuffer   检查预测
 发出      响应      PreDecode   +Uncache处理  +Redirect
```

**S0 (Stage 0)**：发送 cacheline 取指请求
- 从 Ftq 接收 `FetchRequestBundle` (包含起始虚拟地址、FTQ 索引、CFI 偏移等)
- 计算 Fetch Block 信息，包括双 Fetch Port 的 PC 范围
- 生成 `s0_flushFromBpu` 信号
- S0 Fire 条件：`fromFtq.req.fire` (即 Ftq valid && IFU ready && ICache ready)

**S1 (Stage 1)**：接收 ICache 响应
- 接收 ICache 返回的数据 (`ICacheRespBundle`)
- 调用 `InstrBoundary` 模块确定指令边界
- 计算指令有效位 (`rawInstrValid`)、是否 RVC (`rawIsRvc`)
- 处理前一条指令的半条 RV-I (cross-predict-block 场景)
- 调用 `InstrCompact` 计算压缩后的指令位置信息
- S1 Fire 条件：`s1_valid && s2_ready && s1_iCacheRespValid`

**S2 (Stage 2)**：指令对齐与 PreDecode
- 将 ICache 返回的 cacheline 数据按 IBuffer 的写入端口宽度进行对齐 (`alignData`)
- 处理跨预测块指令拼接 (`adjustedBlockHeadInst`)
- 计算 PC 地址映射 (`s2_alignPc`)
- 调用 `PreDecode` 模块进行预解码（判断是否 RVC、分支属性等）
- 生成 IBuffer enqueue 指针 (`s2_prevIBufEnqPtr`)
- S2 Fire 条件：`s2_valid && s3_ready`

**S3 (Stage 3)**：指令发送与 Uncache 处理
- 调用 `RvcExpander` 将 16-bit RVC 指令展开为 32-bit
- 处理 Uncache/MMIO 指令 (通过 `IfuUncacheUnit`)
- 调用 `FrontendTrigger` 进行触发点检查
- 通过 `PredChecker` 校验预测结果
- 将指令写入 IBuffer (`io.toIBuffer`)
- S3 Fire 条件：`io.toIBuffer.fire`

**WB (Write-back) Stage**：预测回写与 Redirect
- 回写 PreDecode 信息到 Ftq (`toFtq.wbRedirect`)
- 当检测到预测错误时，生成 redirect 信号
- 处理 invalidTaken (预测 taken 但实际目标超出 Fetch Block 范围)

### 3.2 Fetch Width 与对齐

XiangShan 的取指宽度参数：

| 参数 | 值 | 说明 |
|------|-----|------|
| `FetchBlockSize` | 64 bytes | 每次取指的块大小 |
| `FetchPorts` | 2 | 双 Fetch Port (支持 double-line) |
| `FetchBlockInstNum` | 32 | 64B / 2B (最小指令宽度) |
| `IBufferEnqueueWidth` | `FetchBlockInstNum + NumWriteBank` | IBuffer 写入端口宽度 |

**对齐机制**：
- Fetch 地址可以是非对齐的，`FetchBlockInstNum` 是 64B / 2B = 32 个半字位置
- 通过 `FetchPorts=2` 支持跨 cacheline 取指（第一条 fetch 可能跨越 cacheline 边界）
- IFU 内部通过 `alignData`、`alignInstrCompact` 等函数，将 Fetch Block 中的指令映射到 IBuffer 的写入银行 (Write Bank)
- IBuffer 的写入银行数 (`NumWriteBank=4`) 决定了 IFU 的预对齐 (pre-alignment) 粒度

### 3.3 跨 Cache Line 取指

当 `FetchPorts=2` 时，XiangShan 支持 double-line fetch：
- 第一个 Fetch Port 从当前 cacheline 取指
- 当取指范围跨越 cacheline 边界时，第二个 Fetch Port 从下一个 cacheline 取指
- `crossCacheline` 信号表示请求是否跨 cacheline
- FTQ 会提前提供下一个 cacheline 的地址 (`nextCachelineVAddr`)

---

## 4. FTQ (Fetch Target Queue)

### 4.1 FTQ 在前端流水线中的角色

FTQ 是前端流水线的核心控制模块，承担以下职责：

1. **存储 BPU 预测结果**：接收 BPU 的预测信息 (分支偏移、目标地址等)
2. **生成 Fetch 请求**：根据预测结果生成 IFU 和 ICache 的取指请求
3. **管理 Redirect**：处理来自后端的 redirect (分支误预测、异常等)
4. **训练 BPU**：将后端 resolve/commit 的信息反馈给 BPU 进行训练
5. **协调 Prefetch**：向 ICache Prefetch Pipe 发送预取请求

### 4.2 FTQ 指针管理

FTQ 使用多个指针来管理不同的流水线位置：

```
bpuPtr ──────► BPU 准备写入的位置
pfPtr  ──────► Prefetch 读取的位置
ifuPtr ──────► IFU 正在取指的位置
ifuWbPtr ────► IFU 正在回写的位置
commitPtr ───► 后端准备提交的位置
```

关键约束：
- `bpuPtr >= ifuPtr` (BPU 不能落后于 IFU)
- BPU 与 IF 之间距离限制：`distanceBetween(bpuPtr, ifuPtr) < BpRunAheadDistance`
- FTQ 大小限制：`distanceBetween(bpuPtr, commitPtr) < FtqSize`

### 4.3 FTQ 内部数据结构

```scala
entryQueue:       Vec[FtqSize, FtqEntry]          // 预测条目
twoFetchInfoVec:  Vec[FtqSize, TwoFetchInfo]      // 双 Fetch 信息
metaQueueRedirect: Vec[FtqSize, BpuRedirectMeta]  // BPU redirect 元数据
metaQueueResolve:  Vec[FtqSize, BpuResolveMeta]   // BPU resolve 元数据
metaQueueCommit:   Vec[FtqSize, BpuCommitMeta]    // BPU commit 元数据
resolveQueue:     Module(ResolveQueue)             // 后端 resolve 缓冲
commitQueue:      Module(CommitQueue)              // 后端 commit 缓冲
```

---

## 5. ICache 与预取

### 5.1 ICache 在前端流水线中的作用

ICache 作为前端取指的数据源，通过 `ICacheToIfuIO` 和 `FtqToICacheIO` 接口与 IFU 和 FTQ 连接。

```scala
class ICacheToIfuIO {
  val fetchResp:  Valid[ICacheRespBundle]  // cacheline 数据响应
  val topdown:    ICacheTopdownInfo        // Top-down 分析信息
  val perf:       ICachePerfInfo           // 性能计数信息
  val fetchReady: Bool                     // ICache 是否准备好
}
```

### 5.2 ICache 流水线

ICache 内部包含：
- **Main Pipe**：主取指流水线，处理 FTQ 发来的 fetch 请求
- **Prefetch Pipe**：预取流水线
- **Miss Unit**：处理 cache miss
- **Way Lookup**：组相联查找

### 5.3 ICache 与 ITLB/PMP

ICache 的取指地址经过 ITLB 虚拟地址到物理地址翻译和 PMP 权限检查：

```scala
itlb.io.requestor(0) <> icache.io.itlb    // ITLB 地址翻译请求
pmpChecker(i).apply(...)                    // PMP 权限检查
pmpRequestor(i).req / .resp                // PMP 请求/响应
```

---

## 6. IBuffer 指令缓冲

### 6.1 IBuffer 的设计目标

IBuffer 位于 IFU 和后端 Decode 之间，主要作用：

1. **解耦前端和后端**：允许前端和后端以不同的速率运行
2. **指令对齐**：将 IFU 的预对齐指令转换为 Decode 阶段可直接使用的格式
3. **异常处理**：记录第一条异常指令的信息
4. **Bypass 优化**：当 IBuffer 为空且 Decode 可以接受时，直接旁路 (bypass) IFU 输出

### 6.2 IBuffer 参数

```scala
case class IBufferParameters(
    Size:         Int = 48,     // IBuffer 大小 (条目数)
    NumWriteBank: Int = 4,      // 写入银行数 (IFU 预对齐)
    NumReadBank:  Int = 8       // 读取银行数 (Decode 读出)
)
```

### 6.3 IBuffer 内部组织

IBuffer 使用寄存器堆实现（而非 SRAM），采用分银行 (banked) 设计：

```
  写入端口 (NumWriteBank=4)        读取端口 (NumReadBank=8)
       │                                    │
       ▼                                    ▼
  ┌─────────┐                        ┌─────────┐
  │ Bank 0  │◄──── ibuf[0, 4, 8..]  │ Bank 0  │
  │ Bank 1  │◄──── ibuf[1, 5, 9..]  │ Bank 1  │
  │ Bank 2  │◄──── ibuf[2, 6, 10..] │ Bank 2  │
  │ Bank 3  │◄──── ibuf[3, 7, 11..] │ Bank 3  │
  └─────────┘                        │ Bank 4  │
                                     │ Bank 5  │
                                     │ Bank 6  │
                                     │ Bank 7  │
                                     └─────────┘
```

读取采用两级选择：Bank 间选择 + Bank 内选择，降低 Mux 面积。

### 6.4 IBuffer Bypass 机制

当 IBuffer 为空且 Decode 可以接受指令时，IFU 的输出可以直接 bypass 到 Decode 端口，无需经过 IBuffer 存储：

```scala
private val useBypass = enqPtr === deqPtr && decodeCanAccept
private val numBypass = Wire(UInt(DecodeWidth.U.getWidth.W))
when(useBypass) {
  when(numFromFetch >= DecodeWidth.U) {
    numBypass := DecodeWidth.U
  }.otherwise {
    numBypass := numFromFetch
  }
}
```

Bypass 的指令不会写入 ibuf 寄存器堆，直接出现在输出端口。

### 6.5 IBuffer 输出到 Decode

```scala
io.out: Vec[DecoupledIO[CtrlFlow]]  // DecodeWidth 个解码端口
```

每个输出端口是一个 `CtrlFlow` 信号，包含：
- `instr`：32-bit 指令 (RVC 已展开)
- `pc`：指令 PC
- `foldpc`：折叠 PC (用于分支预测表索引)
- `isRvc`：是否 RVC 指令
- `fixedTaken` / `predTaken`：预测是否 taken
- `ftqPtr` / `ftqOffset`：FTQ 索引和偏移
- `isLastInFtqEntry`：是否 FTQ 条目中最后一条指令

---

## 7. InstrUncache 非缓存取指

### 7.1 设计目的

InstrUncache 用于处理 MMIO (Memory-Mapped I/O) 指令和非缓存 (Non-Cacheable) 指令的取指。这些指令不能通过 ICache 获取，必须直接从总线读取。

### 7.2 InstrUncache 架构

```scala
class InstrUncache extends LazyModule {
  val clientNode: TLClientNode  // TileLink 客户端节点
}
```

InstrUncache 通过 TileLink 协议连接到 L1 总线，支持：
- 多个 MMIO 条目并发处理 (`nMmioEntry` 个)
- 跨页 MMIO 指令处理 (`needResend`)
- WFI (Wait For Interrupt) 安全机制

### 7.3 Uncache 流程

1. IFU S3 阶段检测到 `s3_reqIsUncache` (ICache meta 标记为 uncache)
2. 通过 `IfuUncacheUnit` 发送请求到 InstrUncache
3. InstrUncache 通过 TileLink 获取数据
4. 数据返回后，IFU 将其作为单条指令写入 IBuffer
5. 每次 uncache 取指只获取一条指令，且会阻塞 fetch pipeline
6. 对于跨页 MMIO 指令，需要两次取指 (`needResend`)

### 7.4 Uncache Redirect

Uncache 取指完成后，需要通知 FTQ 更新状态：

```scala
uncacheRedirect.valid := s3_valid && io.toIBuffer.ready && s3_reqIsUncache && (s3_uncacheCanGo || uncacheNeedResend)
uncacheRedirect.instrCount     := Mux(uncacheNeedResend, 0.U, 1.U)
uncacheRedirect.prevIBufEnqPtr := s3_prevIBufEnqPtr
uncacheRedirect.isHalfInstr    := uncacheNeedResend
```

---

## 8. SimFrontend 仿真前端

### 8.1 设计目的

SimFrontend 是一个理想化的仿真前端，用于后端性能评估 (Verilog simulation)，实现前后端解耦。它绕过真实的 ICache 和 IFU 流水线，直接从 C++ 仿真模型获取指令。

### 8.2 工作原理

通过 DPI-C (Direct Programming Interface - C) 调用与 SystemVerilog 仿真器交互：

```scala
class SimFrontFetchHelper extends ExtModule {
  val io = IO(new Bundle {
    val out = Vec(8, new Bundle {
      val pc, instr, preDecode  // 8 条指令的 PC、指令、预解码信息
    })
    val updatePtrCount    // 消费者接受的指令数
    val redirect          // redirect 信息
    val robCommitValid    // ROB 提交信息
  })
}
```

### 8.3 特点

- 每周期理想情况下输出 8 条指令
- 不处理架构相关场景 (如 lr/sc 动态执行差异、mcycle/mtime 分支)
- 通过 `env.EnableSimFrontend` 配置开关
- 后端执行/提交较慢时会产生假的 FTQ full，限制实际吞吐

---

## 9. 前端与后端接口

### 9.1 FrontendToCtrlIO

前端通过 `FrontendToCtrlIO` 与后端交互：

```scala
class FrontendToCtrlIO {
  // 前端 -> 后端
  val cfVec: Vec[DecodeWidth, DecoupledIO[CtrlFlow]]  // 指令流 (DecodeWidth 条)
  val stallReason: StallReasonIO(DecodeWidth)          // 停顿原因分析
  val fromFtq: FtqToCtrlIO                             // FTQ 到后端控制
  val fromIfu: IfuToBackendIO                          // IFU 到后端 (gpAddrMem)

  // 后端 -> 前端
  val toFtq: CtrlToFtqIO                               // 后端 redirect 到 FTQ
  val canAccept: Bool                                  // 后端是否可以接受指令
  val backendEmpty: Bool                               // 后端流水线是否为空
  val wfi: WfiReqBundle                                // WFI 请求/安全确认
}
```

### 9.2 指令交付机制

指令从前端到后端的交付流程：

```
IFU S3 ──► IBuffer ──► Decode ◄──► 后端
  │         │           │
  │    DecoupledIO   CtrlFlow
  │    FetchToIBuffer
  │
  └──► gpAddrMem (GPA 地址写入)
```

**关键握手信号**：
- `io.backend.canAccept`：后端是否可以接受新指令
- `ibuffer.io.decodeCanAccept`：连接到后端 canAccept
- `io.backend.cfVec`：IBuffer 的 8 路输出 (`DecodeWidth` 路)

### 9.3 Redirect 传递

后端的 redirect 通过 `CtrlToFtqIO` 送回 FTQ：

```scala
io.backend.toFtq.redirect  // Valid[Redirect] - redirect 请求
io.backend.toFtq.commit    // Valid[FtqPtr]   - 指令提交
```

FTQ 收到 redirect 后：
1. 刷新所有指针到 redirect 目标位置
2. 通知 BPU 更新状态
3. 通知 IFU 刷新流水线
4. IBuffer 被清空 (`ibuffer.io.flush := needFlush`)

---

## 10. Pipeline 控制：Stall, Flush, Redirect

### 10.1 Stall (停顿) 机制

IFU 的流水线停顿通过 `ready` 信号控制：

```scala
s0_fire := fromFtq.req.fire
s1_fire := s1_valid && s2_ready && s1_iCacheRespValid
s2_fire := s2_valid && s3_ready
s3_fire := io.toIBuffer.fire

s1_ready := s1_fire || !s1_valid  // S1 有数据且可以 fire，或 S1 空闲
s2_ready := s2_fire || !s2_valid
s3_ready := (io.toIBuffer.ready && (s3_uncacheCanGo || !s3_reqIsUncache)) || !s3_valid
```

常见 stall 原因：
1. **FTQ 不有效**：`!ftq.io.toIfu.req.valid`
2. **IFU 未就绪**：`!ifu.io.fromFtq.req.ready`
3. **ICache 未就绪**：`!icache.io.fromFtq.fetchReq.ready`
4. **IBuffer 满**：`ibuffer.io.full`
5. **后端不能接受**：`!io.backend.canAccept`
6. **Uncache 忙**：`uncacheBusy` (MMIO 指令正在取指)
7. **ICache miss**：`s1_valid && !s1_iCacheRespValid`

### 10.2 Flush (刷新) 机制

IFU 流水线的 flush 具有优先级控制：

```scala
s3_flush := backendRedirect || (wbRedirect.valid && !s3_wbNotFlush)
s2_flush := backendRedirect || uncacheRedirect.valid || wbRedirect.valid
s1_flush := s2_flush || s1_flushFromBpu(0)
s0_flush := s1_flush || s0_flushFromBpu(0)
```

**Flush 优先级**（从高到低）：
1. `backendRedirect`：后端 redirect（最高优先级）
2. `uncacheRedirect`：uncache 指令完成后的 redirect
3. `wbRedirect`：IFU Write-back 阶段检测到的预测错误
4. `s1_flushFromBpu`：BPU flush（S3 阶段预测更新）
5. `s0_flushFromBpu`：BPU flush（S2 阶段预测更新）

**Flush 传播**：高级别 flush 会传播到所有低级别 stage，确保整个流水线一致性刷新。

### 10.3 Redirect 处理

XiangShan 有多种 redirect 来源：

#### (1) 后端 Redirect (`backendRedirect`)
来自后端的分支误预测、内存违例、中断、异常等：

```scala
backendRedirect := fromFtq.redirect.valid
```

后端 redirect 触发时：
- FTQ 刷新所有指针
- IFU S1-S3 所有 stage 被 flush
- `s1_prevLastIsHalfRvi` 重置为 false
- `s2_prevLastHalfData` 重置为 0
- `s2_prevIBufEnqPtr` 重置为 0
- IBuffer 清空

#### (2) IFU Write-back Redirect (`wbRedirect`)
IFU 在 WB 阶段通过 PredChecker 检测到预测错误：

```scala
wbRedirect.valid := checkFlushWb.valid
wbRedirect.isHalfInstr := wbCurrentLastRvi && checkerRedirect.bits.invalidTaken
```

PredChecker 检测的错误类型：
- `jalFault`：JAL 指令预测错误
- `retFault`：RET 指令预测错误
- `notCFIFault`：非 CFI 指令被错误预测为 taken
- `invalidTakenFault`：taken 目标超出 Fetch Block 范围
- `targetFault`：跳转目标地址错误

#### (3) Uncache Redirect (`uncacheRedirect`)
uncache 指令取指完成后触发：

```scala
uncacheRedirect.valid := s3_valid && io.toIBuffer.ready && s3_reqIsUncache && (s3_uncacheCanGo || uncacheNeedResend)
```

### 10.4 Flush 信号在各模块中的处理

| 模块 | Flush 信号 | 作用 |
|------|-----------|------|
| IBuffer | `io.flush` | 清空所有条目，重置所有指针 |
| IFU | `s0-s3_flush` | 刷新对应级流水线寄存器 |
| FTQ | `redirect` | 刷新 FTQ 指针和条目 |
| ICache | `icache.io.flush` | 刷新 ICache 流水线 |
| ITLB | `itlb.io.flushPipe` | 刷新 TLB 流水线 |

### 10.5 BPU Flush 信号

BPU 的 flush 信号 (`flushFromBpu`) 用于处理 BPU 在 S3 阶段的覆写 (override)：

```scala
s0_flushFromBpu := s0_ftqFetch.map(fetch =>
  fromFtq.flushFromBpu.shouldFlushByStage3(fetch.ftqIdx, fetch.valid)
)
```

当 BPU 的 S3 阶段预测更新时，需要 flush 已经进入 IFU 流水线的后续请求。这确保了 BPU 预测更新的一致性。

---

## 11. 关键源文件索引

### 11.1 Frontend 顶层

| 文件路径 | 说明 |
|---------|------|
| `src/main/scala/xiangshan/frontend/Frontend.scala` | Frontend 顶层模块，实例化所有子模块并连接 |
| `src/main/scala/xiangshan/frontend/FrontendParameters.scala` | 前端参数定义 (FetchBlockSize, FetchPorts 等) |
| `src/main/scala/xiangshan/frontend/FrontendBundle.scala` | FrontendBundle 基类 |
| `src/main/scala/xiangshan/frontend/Bundles.scala` | 所有前端 bundle 定义 (接口信号) |
| `src/main/scala/xiangshan/frontend/FrontendModule.scala` | 前端模块基类 |
| `src/main/scala/xiangshan/frontend/TwoFetch.scala` | 双 Fetch Port 相关定义 |
| `src/main/scala/xiangshan/frontend/PrunedAddr.scala` | 地址截断类型定义 |

### 11.2 IFU (Instruction Fetch Unit)

| 文件路径 | 说明 |
|---------|------|
| `src/main/scala/xiangshan/frontend/ifu/Ifu.scala` | IFU 主模块 (S0-S3 + WB 流水线) |
| `src/main/scala/xiangshan/frontend/ifu/Parameters.scala` | IFU 参数 (PcCutPoint, IfuAlignWidth) |
| `src/main/scala/xiangshan/frontend/ifu/PreDecode.scala` | PreDecode 模块 (指令类型识别) |
| `src/main/scala/xiangshan/frontend/ifu/PredChecker.scala` | 预测校验模块 (检测预测错误) |
| `src/main/scala/xiangshan/frontend/ifu/InstrBoundary.scala` | 指令边界识别 |
| `src/main/scala/xiangshan/frontend/ifu/InstrCompact.scala` | 指令压缩/对齐逻辑 |
| `src/main/scala/xiangshan/frontend/ifu/RvcExpander.scala` | RVC 指令展开器 |
| `src/main/scala/xiangshan/frontend/ifu/F3PreDecode.scala` | F3 级预解码 |
| `src/main/scala/xiangshan/frontend/ifu/FrontendTrigger.scala` | 前端触发点逻辑 |
| `src/main/scala/xiangshan/frontend/ifu/IfuUncacheUnit.scala` | IFU 内部的 uncache 处理单元 |
| `src/main/scala/xiangshan/frontend/ifu/IfuPerfAnalysis.scala` | IFU 性能分析模块 |
| `src/main/scala/xiangshan/frontend/ifu/Helpers.scala` | IFU 辅助工具函数 |

### 11.3 FTQ (Fetch Target Queue)

| 文件路径 | 说明 |
|---------|------|
| `src/main/scala/xiangshan/frontend/ftq/Ftq.scala` | FTQ 主模块 |
| `src/main/scala/xiangshan/frontend/ftq/FtqParameters.scala` | FTQ 参数 (FtqSize 等) |
| `src/main/scala/xiangshan/frontend/ftq/FtqBundle.scala` | FTQ 内部 bundle |
| `src/main/scala/xiangshan/frontend/ftq/FtqPtr.scala` | FTQ 指针类型 |
| `src/main/scala/xiangshan/frontend/ftq/FtqPtrVec.scala` | FTQ 指针向量 |
| `src/main/scala/xiangshan/frontend/ftq/EntryQueue.scala` | FTQ 条目队列 |
| `src/main/scala/xiangshan/frontend/ftq/MetaQueue.scala` | FTQ 元数据队列 |
| `src/main/scala/xiangshan/frontend/ftq/ResolveQueue.scala` | 后端 resolve 信息缓冲 |
| `src/main/scala/xiangshan/frontend/ftq/CommitQueue.scala` | 后端 commit 信息缓冲 |
| `src/main/scala/xiangshan/frontend/ftq/SpeculationQueue.scala` | 投机信息队列 |
| `src/main/scala/xiangshan/frontend/ftq/CfiQueue.scala` | CFI (Control Flow Instruction) 队列 |
| `src/main/scala/xiangshan/frontend/ftq/IfuRedirectReceiver.scala` | 接收 IFU redirect |
| `src/main/scala/xiangshan/frontend/ftq/BackendRedirectReceiver.scala` | 接收后端 redirect |

### 11.4 ICache

| 文件路径 | 说明 |
|---------|------|
| `src/main/scala/xiangshan/frontend/icache/ICache.scala` | ICache 顶层 |
| `src/main/scala/xiangshan/frontend/icache/ICacheImp.scala` | ICache 实现 |
| `src/main/scala/xiangshan/frontend/icache/ICacheMainPipe.scala` | ICache 主流水线 |
| `src/main/scala/xiangshan/frontend/icache/ICacheMetaArray.scala` | 元数据 SRAM |
| `src/main/scala/xiangshan/frontend/icache/ICacheDataArray.scala` | 数据 SRAM |
| `src/main/scala/xiangshan/frontend/icache/ICacheMissUnit.scala` | Miss 处理单元 |
| `src/main/scala/xiangshan/frontend/icache/ICachePrefetchPipe.scala` | 预取流水线 |
| `src/main/scala/xiangshan/frontend/icache/ICacheWayLookup.scala` | 组相联查找 |
| `src/main/scala/xiangshan/frontend/icache/Parameters.scala` | ICache 参数 |

### 11.5 IBuffer

| 文件路径 | 说明 |
|---------|------|
| `src/main/scala/xiangshan/frontend/ibuffer/IBuffer.scala` | IBuffer 主模块 |
| `src/main/scala/xiangshan/frontend/ibuffer/Parameters.scala` | IBuffer 参数 |
| `src/main/scala/xiangshan/frontend/ibuffer/Bundles.scala` | IBuffer bundle 定义 |
| `src/main/scala/xiangshan/frontend/ibuffer/Abstracts.scala` | 抽象基类 |

### 11.6 InstrUncache

| 文件路径 | 说明 |
|---------|------|
| `src/main/scala/xiangshan/frontend/instruncache/InstrUncache.scala` | InstrUncache 顶层 (LazyModule) |
| `src/main/scala/xiangshan/frontend/instruncache/InstrUncacheImp.scala` | InstrUncache 实现 |
| `src/main/scala/xiangshan/frontend/instruncache/InstrUncacheEntry.scala` | MMIO 条目管理 |
| `src/main/scala/xiangshan/frontend/instruncache/Parameters.scala` | 参数定义 |

### 11.7 SimFrontend

| 文件路径 | 说明 |
|---------|------|
| `src/main/scala/xiangshan/frontend/simfrontend/SimFrontend.scala` | 仿真前端 (DPI-C 接口) |

### 11.8 BPU (Branch Prediction Unit)

| 文件路径 | 说明 |
|---------|------|
| `src/main/scala/xiangshan/frontend/bpu/Bpu.scala` | BPU 主模块 |
| `src/main/scala/xiangshan/frontend/bpu/Parameters.scala` | BPU 参数 |
| `src/main/scala/xiangshan/frontend/bpu/ubtb/` | Micro BTB |
| `src/main/scala/xiangshan/frontend/bpu/mbtb/` | Main BTB |
| `src/main/scala/xiangshan/frontend/bpu/utage/` | Micro TAGE |
| `src/main/scala/xiangshan/frontend/bpu/tage/` | TAGE |
| `src/main/scala/xiangshan/frontend/bpu/sc/` | Statistical Corrector |
| `src/main/scala/xiangshan/frontend/bpu/ittage/` | ITTAGE (间接跳转预测) |
| `src/main/scala/xiangshan/frontend/bpu/ras/` | Return Address Stack |
| `src/main/scala/xiangshan/frontend/bpu/abtb/` | Ahead BTB |

---

## 12. 总结

XiangShan 的前端流水线是一个精心设计的多级流水线，具有以下关键特征：

1. **四级流水线 + Write-back**：IFU 的 S0-S3 + WB 设计在取指带宽和时序之间取得了良好的平衡。S0 发出请求，S1 接收 ICache 数据，S2 进行指令对齐和预解码，S3 发送指令到 IBuffer，WB 阶段回写预测信息并处理预测错误。

2. **双 Fetch Port**：通过 `FetchPorts=2` 支持跨 cacheline 取指，提高了取指效率，减少了边界对齐带来的浪费。

3. **灵活的 Redirect 处理**：多优先级的 flush/redirect 机制确保了前端与后端的一致性。后端 redirect 优先级最高，其次是 uncache redirect 和 WB 阶段的预测错误 redirect。

4. **IBuffer 优化**：分银行 (banked) 设计的 IBuffer 支持高效的并行读写，bypass 机制减少了空泡 (bubble)，提高了前端到后端的指令传输效率。

5. **Uncache 指令处理**：专用的 InstrUncache 模块通过 TileLink 协议处理 MMIO 指令，支持跨页 uncache 取指。

6. **SimFrontend 支持**：通过 `EnableSimFrontend` 配置，可以在仿真中使用理想前端，实现前后端解耦的性能评估。

7. **参数化设计**：所有关键参数 (FetchBlockSize, FetchPorts, IBuffer 大小等) 都通过 `FrontendParameters` 进行参数化，便于不同配置的探索。

前端流水线的性能瓶颈主要来自：
- ICache miss 导致的 stall
- 后端 redirect 导致的流水线 flush
- IBuffer 满导致的 stall
- Uncache 指令的阻塞
- BPU 预测错误导致的 WB redirect

这些 stall 原因在 IFU 的性能计数器中有详细统计 (`stallCycles_fetch`, `squashCycles_bpWrong_preDecode` 等)，为性能优化提供了重要参考。
