The report file cannot be written directly due to subagent restrictions. The directory `/home/agi/workspace/gitwork/XiangShan/deepresearch/R18-mmu-tlb/` exists and is ready. Here is the complete report content that should be saved to that path:

---

# XiangShan MMU & TLB 子系统深度研究报告

## 1. 概述

XiangShan（香山）处理器是一款基于 RISC-V 架构的开源高性能处理器核，其内存管理单元（MMU, Memory Management Unit）子系统是整个处理器中最为复杂和关键的模块之一。该子系统负责虚拟地址到物理地址的转换、权限检查、以及对 H-Extension 虚拟化的二级地址翻译支持。

MMU 子系统采用多级 TLB（Translation Lookaside Buffer）层次结构，结合硬件页表遍历器（Page Table Walker, PTW），实现了高效的地址翻译流水线。整体设计支持 Sv39 和 Sv48 两种分页模式，具备完整的 TLB 射落（Shootdown）机制、指针屏蔽（Pointer Masking）、Svnapot 64KB 大页、Svpbmt 内存类型标注等 RISC-V 标准扩展特性。

本报告基于源码分析，深入研究 XiangShan MMU/TLB 子系统的架构设计、关键数据结构、状态机实现、地址翻译流程以及相关的 I/O 内存保护单元（IOPMP）。

---

## 2. MMU 整体架构

### 2.1 架构总览

XiangShan 的 MMU 子系统由以下几个核心层次组成：

```
┌─────────────────────────────────────────────────────────────────┐
│                        XiangShan Core                           │
│                                                                 │
│  ┌─────────────┐                ┌──────────────────────────┐   │
│  │  Frontend    │                │      MemBlock             │   │
│  │             │                │                           │   │
│  │  ┌───────┐  │  ┌─────────┐  │  ┌────────┐ ┌────────┐   │   │
│  │  │ iTLB  │──┼──│Repeater │  │  │ dTLB-LD│ │dTLB-ST │   │   │
│  │  │(48way)│  │  │ (1-entry│  │  │(48way) │ │(48way) │   │   │
│  │  └───────┘  │  │  buffer)│  │  └────────┘ └────────┘   │   │
│  └─────────────┘  └────┬────┘  │  ┌────────┐              │   │
│                        │       │  │dTLB-PF │              │   │
│  ┌─────────────┐       │       │  │(48way) │              │   │
│  │   IFU       │       │       │  └───┬────┘              │   │
│  │  (指令预取)  │       │       └──────┼──────────────────┘   │
│  └─────────────┘       │              │                       │
│                        │              │                       │
│                  ┌─────┴──────────────┴──────┐               │
│                  │       L2 TLB (统一)         │               │
│                  │                             │               │
│                  │  ┌─────┐ ┌─────┐ ┌──────┐  │               │
│                  │  │ L3  │→│ L2  │→│ L1   │  │               │
│                  │  │(16e)│ │(16e)│ │(4Sx2W│  │               │
│                  │  └─────┘ └─────┘ └──┬───┘  │               │
│                  │                 ┌────┴────┐  │               │
│                  │                 │  L0     │  │               │
│                  │                 │(64Sx4W) │  │               │
│                  │                 └────┬────┘  │               │
│                  │              ┌────────┴───┐   │               │
│                  │              │  SP(16e)   │   │               │
│                  │              │ Bitmap Cache│   │               │
│                  │              └──────┬─────┘   │               │
│                  │                     │         │               │
│                  │  ┌──────────────────┤         │               │
│                  │  │  PTW / LLPTW / HPTW       │               │
│                  │  │  (页表遍历状态机)           │               │
│                  │  └──────────────────┤         │               │
│                  └─────────────────────┼─────────┘               │
│                                        │                         │
│                              ┌─────────┴─────────┐              │
│                              │   TileLink Bus     │              │
│                              │   (连接 L2 Cache)   │              │
│                              └───────────────────┘              │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 源文件索引

| 文件名 | 行数 | 核心职责 |
|--------|------|----------|
| `MMUConst.scala` | ~383 | TLB 参数常量、PTE 格式定义、地址计算辅助函数 |
| `MMUBundle.scala` | ~1487 | 所有 IO Bundle 和数据结构定义（PTE、TLB 条目、PTW 条目等） |
| `TLB.scala` | ~765 | L1 TLB 主模块，地址翻译、权限检查、miss 处理 |
| `TLBStorage.scala` | ~458 | TLB 存储实现（全相联）、替换策略（PLRU）、Sfence 处理 |
| `PageTableWalker.scala` | ~1000+ | PTW 非叶节点遍历 FSM + LLPTW 叶节点并行遍历 FSM |
| `PageTableCache.scala` | ~1000+ | 多级页表缓存（L3/L2/L1/L0/SP + Bitmap Cache）、ECC 保护 |
| `L2TLB.scala` | ~1100+ | L2 TLB 顶层集成，仲裁器层次，MissQueue，TileLink 接口 |
| `L2TlbMissQueue.scala` | ~50 | L1 与 L2 TLB 之间的去重队列 |
| `L2TlbPrefetch.scala` | ~77 | 顺序预取逻辑，避免冗余预取 |
| `Repeater.scala` | ~80 | PTWRepeater：L1 TLB 与 L2 TLB 之间的单条目缓冲器 |
| `BitmapCheck.scala` | ~200+ | 页表条目 Bitmap 完整性检查 |

相关 IOPMP 文件：

| 文件名 | 行数 | 核心职责 |
|--------|------|----------|
| `Iopmp.scala` | ~199 | IOPMP 顶层模块，AXI4 Bridge 集成 |
| `IopmpChecker.scala` | ~1375 | 三级表查找引擎、NAPOT 解码、寄存器配置 |
| `IopmpBridge.scala` | ~200+ | AXI4 总线桥接，事务拦截与转发 |

---

## 3. TLB 层次结构

### 3.1 L1 TLB 参数

XiangShan 在 L1 级设置了四个独立的 TLB 实例（定义于 `Parameters.scala`）：

| TLB 实例 | 名称 | 用途 | Way 数 | 替换策略 | 相联方式 | 特殊配置 |
|----------|------|------|--------|----------|----------|----------|
| iTLB | `itlb` | 指令地址翻译 | 48 | PLRU | 全相联 | `fetchi=true` |
| dTLB-LD | `ldtlb` | Load 地址翻译 | 48 | PLRU | 全相联 | `partialStaticPMP=true` |
| dTLB-ST | `sttlb` | Store 地址翻译 | 48 | PLRU | 全相联 | `lgMaxSize=4` |
| dTLB-PF | `pftlb` | Prefetch 地址翻译 | 48 | PLRU | 全相联 | 与 LD 相同配置 |

所有 L1 TLB 均采用 **全相联（Fully Associative）** 设计，每个 TLB 拥有 **48 个 Way**，使用 **PLRU（Pseudo Least Recently Used）** 替换策略。

### 3.2 Sector TLB 条目格式

L1 TLB 使用 **Sector TLB** 设计（`TlbSectorEntry`），每个条目可以映射 **8 个连续的 4KB 页面**（由 `tlbcontiguous=8` 控制）：

```
┌──────────────────────────────────────────────────────────────────┐
│                    TlbSectorEntry                                 │
├──────────────────────────────────────────────────────────────────┤
│ tag        │ VPN 标签，用于全相联匹配                            │
│ asid       │ 地址空间标识符                                       │
│ vmid       │ 虚拟机标识符（H-Extension）                          │
│ level      │ 页级别（0=4KB, 1=2MB, 2=1GB）                       │
│ ppn        │ 物理页号（高位部分）                                  │
│ ppn_low[8] │ 8 个子页面的低比特 PPN                               │
│ n          │ Svnapot 标志（n=1 表示 64KB 对齐页）                  │
│ pbmt       │ Svpbmt 物理内存类型（pma/nc/io）                     │
│ perm       │ 权限位（R/W/X/U/G/A/D）                             │
│ valididx[8]│ 每个子页面的有效位                                    │
│ pteidx[8]  │ 每个子页面的 PTE 索引                                │
│ g_perm     │ 二级翻译权限（G-Stage）                               │
│ s2xlate    │ 二级翻译类型（noS2xlate/onlyStage1/onlyStage2/all）  │
└──────────────────────────────────────────────────────────────────┘
```

**Hit 判断逻辑**（`TlbSectorEntry.hit()`）：
1. 比较 ASID
2. 如果处于二级翻译模式，比较 VMID
3. 进行 Level-matched 的 Tag 比较
4. 检查对应子页面的 `valididx` 位

**PPN 生成逻辑**（`TlbSectorEntry.genPPN()`）：
- Level 0（4KB）：直接使用 `ppn` 和 `ppn_low[idx]` 拼接
- Level 1（2MB）：`ppn[53:18]` + VPN[1] + `ppn_low[idx]` 的低 9 位
- Level 2（1GB）：`ppn[53:27]` + VPN[2:1] + `ppn_low[idx]` 的低 9 位
- Svnapot 64KB 页：使用 `ppn_napot` 对齐到 64KB 边界

### 3.3 TLBFA 存储实现

`TLBFA`（TLB Fully Associative）是实际的存储模块（`TLBStorage.scala`）：

- **数据结构**：使用 `RegInit(Vec(NWays, 0.U.asTypeOf(new TlbSectorEntry)))` 作为条目存储
- **有效位**：独立的 `v` 位向量，每个 Way 一个 bit
- **并行匹配**：所有 48 个 Way 并行进行 hit 检测，输出 `hitVec`
- **数据选择**：使用 `Mux1H` 根据 hitVec 选择命中的条目数据

**Sfence 处理**：
- `sfence.vma`：根据地址和 ASID 刷新，或全部刷新（`sfence_vpn === 0.U && sfence_asid === 0.U`）
- `hfence.vvma`：根据 VMID 和 ASID 刷新（VS 阶段）
- `hfence.gvma`：仅根据 VMID 刷新（G 阶段）
- **已知限制**：`hfence.vvma` 在 VS 阶段为大页、G 阶段为小页时，无法正确进行地址匹配

---

## 4. 地址翻译流程

### 4.1 VM 启用判断

地址翻译是否启用取决于以下条件（`TLB.scala`）：

```scala
vmEnable = (Sv39Enable || Sv48Enable) && (mode < ModeM)
```

其中：
- `Sv39Enable`：satp.MODE == Sv39
- `Sv48Enable`：satp.MODE == Sv48
- `mode`：当前特权级，M 模式下不启用虚拟化

### 4.2 二级翻译模式判断

对于 H-Extension 虚拟化环境，需要判断二级翻译（Stage-2 Translation）的模式：

| 条件 | s2xlate 值 | 含义 |
|------|-----------|------|
| vsatp.MODE == BARE && hgatp.MODE == BARE | `noS2xlate` | 不进行任何翻译 |
| vsatp.MODE == BARE && hgatp.MODE != BARE | `onlyStage2` | 仅 G-Stage 翻译 |
| vsatp.MODE != BARE && hgatp.MODE == BARE | `onlyStage1` | 仅 VS-Stage 翻译 |
| vsatp.MODE != BARE && hgatp.MODE != BARE | `allStage` | 两级翻译都进行 |

### 4.3 完整地址翻译流程

```
                    ┌─────────────────┐
                    │ CPU 发出 VA 请求 │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │ 检查 no_translate │
                    │ 标志（跳过翻译）  │
                    └────────┬────────┘
                             │ 需要翻译
                    ┌────────▼────────┐
                    │ 指针屏蔽处理     │
                    │ (Pointer Masking)│
                    │ PMLEN7 / PMLEN16 │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │ 拆分 VA 为       │
                    │ VPN[2:0] + Offset│
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
     ┌────────▼───────┐     │     ┌────────▼───────┐
     │  查询 L1 TLB    │     │     │  查询 L1 TLB    │
     │  (VS-Stage)     │     │     │  (G-Stage)      │
     └────────┬───────┘     │     └────────┬───────┘
              │              │              │
        ┌─────▼─────┐       │        ┌─────▼─────┐
        │  TLB Hit?  │       │        │  TLB Hit?  │
        └──┬─────┬──┘       │        └──┬─────┬──┘
      Yes  │     │ No       │      Yes  │     │ No
           │     │          │           │     │
           │  ┌──▼──────┐   │           │  ┌──▼──────┐
           │  │ 送 PTW   │   │           │  │ 送 PTW   │
           │  │ (miss)   │   │           │  │ (miss)   │
           │  └──┬──────┘   │           │  └─────────┘
           │     │          │           │
           │  ┌──▼──────────▼──┐        │
           │  │   PTW 状态机     │        │
           │  │   遍历页表       │        │
           │  └──┬──────────────┘        │
           │     │ 找到叶节点 PTE         │
           │     │                       │
    ┌──────▼─────▼──────┐                │
    │  权限检查 (perm_check)             │
    │  - Mode (Sv39/Sv48)              │
    │  - R/W/X 权限                     │
    │  - U 位 (用户/超级模式)            │
    │  - SUM (监督者访问用户页)           │
    │  - MXR (读取执行页)               │
    └────────┬──────────┘                │
             │                           │
    ┌────────▼──────────┐                │
    │  PMP 检查          │                │
    │  (物理地址权限)     │                │
    └────────┬──────────┘                │
             │                           │
    ┌────────▼──────────┐                │
    │  输出 PA 给 CPU    │                │
    └───────────────────┘                │
```

### 4.4 指针屏蔽（Pointer Masking）

XiangShan 实现了 Ssnpm 扩展的指针屏蔽功能：

- **PMLEN7**：掩码长度为 7 位，虚拟地址的高 7 位被清零
- **PMLEN16**：掩码长度为 16 位，虚拟地址的高 16 位被清零
- 仅在非 M 模式且非裸机模式下生效
- 屏蔽后的地址用于 TLB 查询和权限检查

### 4.5 权限检查

权限检查在 `TLB.perm_check()` 中实现，分两级：

**Stage-1 权限检查**：
- 模式匹配（Sv39/Sv48）
- 读/写/执行权限（R/W/X）
- 用户模式位（U）
- 监督者用户页访问（SUM）
- 执行权限与读权限（MXR）
- 访问位（A）和脏位（D）检查

**Stage-2 权限检查**（H-Extension）：
- 二级翻译的 Guest 权限（g_perm）
- 与 Stage-1 权限取交集

---

## 5. PTW 状态机

### 5.1 PTW（非叶节点遍历）

`class PTW` 实现非叶节点的页表遍历（`PageTableWalker.scala`），使用 **s/w 寄存器对** 设计：

```
┌────────────────────────────────────────────────────────────┐
│                    PTW FSM 状态转换                          │
│                                                            │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐             │
│  │ s_pmp_   │───▶│ s_mem_   │───▶│ w_mem_   │             │
│  │ check    │    │ req      │    │ resp     │             │
│  └──────────┘    └──────────┘    └────┬─────┘             │
│                                       │                     │
│                              ┌────────▼────────┐           │
│                              │  检查 PTE         │           │
│                              │  isLeaf()?       │           │
│                              └──┬───────────┬───┘           │
│                          Leaf   │           │ Non-Leaf      │
│                          ┌──────▼───┐  ┌───▼────────┐      │
│                          │ 送 LLPTW  │  │ 级别递减    │      │
│                          │ (叶节点)  │  │ level--     │      │
│                          └──────────┘  └───┬────────┘      │
│                                            │               │
│                                    ┌───────▼───────┐       │
│                                    │  还有更高级？   │       │
│                                    └──┬─────────┬──┘       │
│                                   Yes │         │ No       │
│                                       │    ┌────▼────┐     │
│                                       │    │ page    │     │
│                                       │    │ fault   │     │
│                                       │    └─────────┘     │
│                                       │                    │
│                              ┌────────▼────────┐           │
│                              │ s_mem_req        │           │
│                              │ (读取下一级 PTE)  │           │
│                              └─────────────────┘           │
│                                                            │
│  ┌───────────────┐    ┌───────────────┐                    │
│  │ s_hptw_req    │───▶│ w_hptw_resp   │  (G-Stage 翻译)    │
│  └───────────────┘    └──────┬────────┘                    │
│                              │                              │
│  ┌───────────────────┐  ┌───▼──────────────┐               │
│  │ s_last_hptw_req  │◀─│ w_last_hptw_resp │               │
│  └───────────────────┘  └──────────────────┘               │
│                                                            │
│  ┌───────────────────┐                                     │
│  │ mem_addr_update    │ (地址更新，准备下一轮遍历)            │
│  └───────────────────┘                                     │
└────────────────────────────────────────────────────────────┘
```

**关键设计点**：

1. **s/w 寄存器对**：每个状态 `s_xxx` 配对一个等待状态 `w_xxx`，`s_xxx` 发出请求，`w_xxx` 等待响应
2. **地址计算**：
   - L3 地址：`MakeAddr(satp.ppn, getVpnn(vpn, 3))`
   - L2 地址：`MakeAddr(Mux(l3Hit, ppn, pte.getPPN()), getVpnn(vpn, 2))`
   - L1 地址：`MakeAddr(Mux(l2Hit, ppn, pte.getPPN()), getVpnn(vpn, 1))`
3. **级别递减**：从 Level 3（Sv48）或 Level 2（Sv39）开始，每遍历一级非叶节点 level 减 1
4. **叶节点发现**：当 `level >= 2` 时发现叶节点，发送给 LLPTW 处理（大页）
5. **G-Stage 支持**：通过 `s_hptw_req` -> `w_hptw_resp` -> `s_last_hptw_req` -> `w_last_hptw_resp` 进行二级翻译

### 5.2 LLPTW（叶节点并行遍历）

`class LLPTW` 是叶节点的并行遍历状态机，拥有 **12 个状态**，支持 **6 路并行**（`llptwsize=6`）：

```
idle ──▶ hptw_req ──▶ hptw_resp ──▶ addr_check ──▶ mem_req
  ▲                                                        │
  │                                                        ▼
  │                                                   mem_waiting
  │                                                        │
  └──────── cache ◀── bitmap_resp ◀── bitmap_check ◀── mem_out
               │                            ▲
               │        ┌───────────────────┘
               │        │
               │  ┌─────┴──────────────┐
               │  │ last_hptw_req      │
               │  │ last_hptw_resp     │
               │  └────────────────────┘
```

**12 个状态详解**：

| 状态 | 功能 |
|------|------|
| `idle` | 空闲，等待新请求 |
| `hptw_req` | 向 HPTW 发送二级翻译请求 |
| `hptw_resp` | 等待 HPTW 响应 |
| `addr_check` | 物理地址检查 |
| `mem_req` | 向内存发送读请求 |
| `mem_waiting` | 等待内存响应 |
| `mem_out` | 内存响应到达 |
| `last_hptw_req` | 最后一级 HPTW 请求 |
| `last_hptw_resp` | 等待最后 HPTW 响应 |
| `cache` | 写入页表缓存 |
| `bitmap_check` | Bitmap 完整性检查 |
| `bitmap_resp` | 等待 Bitmap 检查结果 |

**关键机制**：

- **重复检测**：`dup_vec` 检查新请求的 VPN 是否与已有条目匹配，避免重复遍历
- **Round-Robin 仲裁**：6 路条目使用 RR 策略竞争内存访问
- **G-Stage 路径**：完整路径 `to_hptw_req -> state_hptw_req -> state_hptw_resp -> state_last_hptw_req -> state_last_hptw_resp`

---

## 6. L2 TLB / 页表缓存

### 6.1 多级页表缓存层次

L2 TLB（`PageTableCache.scala`）采用多级缓存结构：

| 缓存级别 | 容量 | 组织方式 | 相联方式 | 用途 |
|----------|------|----------|----------|------|
| L3 | 16 条目 | 全相联 | FA | 最高优先级缓存，存储最近使用的完整 PTE |
| L2 | 16 条目 | 全相联 | FA | 次高优先级缓存 |
| L1 | 4 组 x 2 Way | 组相联 | SA | 中间级缓存 |
| L0 | 64 组 x 4 Way | 组相联 | SA | 最低级缓存，存储单级 PTE |
| SP | 16 条目 | 全相联 | FA | SuperPage 缓存，存储大页 PTE |

### 6.2 数据流

```
                    ┌─────────────────────────┐
                    │     5-Stage Pipeline      │
                    │                           │
     TLB Miss ────▶ │ stageReq                  │
                    │   │                       │
                    │   ▼                       │
                    │ stageDelay(0) ──▶ 查询 L3/L2  │
                    │   │                       │
                    │   ▼                       │
                    │ stageDelay(1) ──▶ 查询 L1/L0  │
                    │   │                       │
                    │   ▼                       │
                    │ stageCheck(0) ──▶ 查询 SP     │
                    │   │                       │
                    │   ▼                       │
                    │ stageCheck(1) ──▶ 综合判断    │
                    │   │                       │
                    │   ▼                       │
                    │ stageResp ──▶ 输出结果      │
                    └─────────────────────────┘
```

### 6.3 ECC 保护

页表缓存使用 **SECDED（Single Error Correction, Double Error Detection）** ECC 保护：

- `PTWEntriesWithEcc`：对缓存条目进行 ECC 编码/解码
- 读取时进行 ECC 解码，检测并纠正单位错误
- 写入时进行 ECC 编码
- 双位错误将触发页面错误

### 6.4 SRAM 读写冲突处理

`rwHarzad` 机制处理 SRAM 单端口冲突：
- 当 refill 操作与读操作同时发生时，优先满足 refill
- 读操作延迟一个周期重试
- 通过 `stageDelay` 流水级处理这种冲突

### 6.5 Miss Queue 去重

`L2TlbMissQueue`（`L2TlbMissQueue.scala`）实现 L1 与 L2 TLB 之间的请求去重：
- 当多个 L1 TLB 同时 miss 同一 VPN 时，仅向 L2 TLB 发送一次请求
- 使用简单的 FIFO 队列跟踪在途请求
- 请求完成后自动释放对应条目

### 6.6 顺序预取

`L2TlbPrefetch`（`L2TlbPrefetch.scala`）实现顺序预取：
- 预取地址：`next_line = get_next_line(vpn)` -- 下一个缓存行边界
- 使用 4 条目的 `old_reqs` 缓冲区避免冗余预取
- 仅在 PTW 完成一次遍历后触发

---

## 7. Sv39/Sv48 PTE 格式支持

### 7.1 PTE 位字段定义

```
┌────────────────────────────────────────────────────────────────┐
│                    RISC-V PTE (64-bit)                         │
├──────┬──────┬──────┬──────┬──────────────┬──────┬──────┬──────┤
│ PPN  │PBMT  │RSW   │D     │A             │G     │U     │X     │
│[53:10]│[9:8] │[7:6] │[7]   │[6]           │[5]   │[4]   │[3]   │
├──────┴──────┴──────┴──────┴──────────────┴──────┴──────┼──────┤
│                                                        │W     │
│                                                        │[2]   │
│                                                        ├──────┤
│                                                        │R     │
│                                                        │[1]   │
│                                                        ├──────┤
│                                                        │V     │
│                                                        │[0]   │
└────────────────────────────────────────────────────────┴──────┘
```

### 7.2 PTE 类型判断

`PteBundle`（`MMUBundle.scala`）提供以下判断方法：

| 方法 | 条件 | 含义 |
|------|------|------|
| `isLeaf()` | R=1 或 X=1 | 叶节点（可直接翻译） |
| `isNext()` | R=0 且 X=0 | 非叶节点（指向下一级页表） |
| `isPf()` | V=0 或 (R=0 且 W=1) | 页面错误 |
| `isAf()` | A=0 | 访问位缺失 |
| `isNapot()` | N=1 | Svnapot 64KB 页 |
| `canRefill()` | isLeaf() 且非 reserved | 可以写入 TLB |

### 7.3 页大小支持

| 页类型 | Level | 大小 | VPN 位数 | PPN 位数 |
|--------|-------|------|----------|----------|
| Sv39 4KB | 0 | 4KB | VPN[2:0] 各 9 位 | PPN[43:0] |
| Sv39 2MB | 1 | 2MB | VPN[2] + PPN[1] | PPN[43:18] |
| Sv39 1GB | 2 | 1GB | VPN[2:1] | PPN[43:27] |
| Sv48 4KB | 0 | 4KB | VPN[3:0] 各 9 位 | PPN[43:0] |
| Sv48 2MB | 1 | 2MB | VPN[3:0] | PPN[43:18] |
| Sv48 1GB | 2 | 1GB | VPN[3:0] | PPN[43:27] |
| Sv48 512GB | 3 | 512GB | VPN[3:0] | PPN[43:36] |

### 7.4 Svnapot 支持

Svnapot（NAPOT，Naturally Aligned Power-of-Two）扩展允许单个 PTE 映射 64KB 的自然对齐内存区域：

- PTE 的 N 位（bit[63]）置 1 表示 NAPOT 页
- 当 N=1 时，PPN[3:0] 必须为 `8'b1000_0000`（即 8）
- 实际效果：8 个连续 4KB 页的 PTE 被合并为一个 64KB 页
- 在 `TlbSectorEntry.genPPN()` 中，NAPOT 页使用对齐到 64KB 的物理地址

---

## 8. TLB 射落与刷新机制

### 8.1 Sfence.vma

用于刷新 VS 阶段或 S 阶段的 TLB：

```scala
sfence_vma  := sfence.valid && !sfence.bits.hfence
sfence_vpn  := sfence.bits.vaddr >> offLen
sfence_asid := sfence.bits.asid
```

**刷新条件**：
- `vpn === 0.U && asid === 0.U`：全局刷新所有条目
- `vpn === 0.U && asid !== 0.U`：刷新指定 ASID 的所有条目
- `vpn !== 0.U && asid === 0.U`：刷新指定虚拟地址的所有 ASID 条目
- `vpn !== 0.U && asid !== 0.U`：精确刷新指定 VPN + ASID 的条目

### 8.2 hfence.vvma

用于刷新 VS 阶段的 TLB（H-Extension）：

```scala
sfence_vvma := sfence.valid && sfence.bits.hfence && sfence.bits.hg
```

基于 **VMID + ASID + VPN** 进行刷新。

**已知限制**（源码注释）：
> hfence.vvma 无法在 VS 阶段使用大页（如 1GB）、G 阶段使用小页（如 4KB）的嵌套翻译场景中正确进行地址匹配。这是因为大页的 VPN 映射范围与小页的精确地址范围不一致。

### 8.3 hfence.gvma

用于刷新 G 阶段的 TLB：

```scala
sfence_gvma := sfence.valid && sfence.bits.hfence && !sfence.bits.hg
```

基于 **VMID** 进行刷新，忽略地址和 ASID。

### 8.4 全局刷新触发条件

以下情况触发全局 TLB 刷新：
1. 软件执行 `sfence.vma`（vpn=0, asid=0）
2. M 模式下的模式切换
3. SATP 寄存器写入改变翻译模式
4. HGATP 寄存器写入改变 G-Stage 翻译配置

---

## 9. 二级翻译（H-Extension 虚拟化支持）

### 9.1 架构概述

XiangShan 完整支持 RISC-V H-Extension 的两级地址翻译：

```
┌──────────────────────────────────────────────────────────┐
│                 两级翻译流程                               │
│                                                          │
│  Guest VA (GVA)                                          │
│       │                                                  │
│       ▼                                                  │
│  ┌──────────────────────────┐                            │
│  │ Stage-1 翻译 (vsatp)     │                            │
│  │ VA -> GPA (Guest PA)      │                            │
│  │ 使用 vsatp 的页表          │                            │
│  └──────────┬───────────────┘                            │
│             │                                            │
│             ▼                                            │
│  ┌──────────────────────────┐                            │
│  │ Stage-2 翻译 (hgatp)     │                            │
│  │ GPA -> PA (Host PA)       │                            │
│  │ 使用 hgatp 的页表          │                            │
│  └──────────┬───────────────┘                            │
│             │                                            │
│             ▼                                            │
│         Physical Address                                 │
└──────────────────────────────────────────────────────────┘
```

### 9.2 HPTW 模块

HPTW（Hypervisor Page Table Walker）专门处理 Stage-2 翻译：

- 接收来自 PTW 和 LLPTW 的 G-Stage 翻译请求
- 使用 hgatp 寄存器中的 PPN 作为 G-Stage 页表的根地址
- 翻译结果（PA）返回给 PTW/LLPTW 继续 VS-Stage 翻译
- 支持嵌套翻译：G-Stage 页表本身也可能需要 G-Stage 翻译（通过递归处理）

### 9.3 GPA 处理

`PtwRespS2`（`MMUBundle.scala`）封装了两级翻译的结果：

```scala
class PtwRespS2 Bundle {
  val s1: PtwSectorResp  // Stage-1 翻译结果（GPA）
  val s2: HptwResp       // Stage-2 翻译结果（PA）
  val s2xlate: UInt       // 翻译类型
}
```

当 Stage-1 发生 Guest Page Fault 时，通过 `need_gpa` 机制请求 GPA 信息，而不是直接触发异常。

---

## 10. PMP 与 ChiselIOPMP

### 10.1 L1 PMP 检查

XiangShan 在 TLB 输出阶段进行 PMP（Physical Memory Protection）检查：

- **L1 PMP**：在 TLB 命中后立即检查物理地址
- **partialStaticPMP**：对于 dTLB-LD，部分 PMP 检查在 TLB 内完成
- PMP 检查结果（`pmp_addr`）传递给后续流水级

### 10.2 L2 PMP 检查

L2 PMP 在 PTW 遍历过程中检查每次内存访问的物理地址合法性，防止恶意页表遍历访问非法内存区域。

### 10.3 ChiselIOPMP 架构

ChiselIOPMP（`ChiselIOPMP/src/main/scala/`）是独立的 I/O 内存保护单元，用于保护 I/O 外设的内存访问：

```
┌───────────────────────────────────────────────────────────┐
│                  ChiselIOPMP 架构                          │
│                                                           │
│  AXI4 Master ──▶┌────────────┐                           │
│  (总线主机)       │ IOPMP      │                           │
│                   │ Bridge     │                           │
│                   └─────┬──────┘                           │
│                         │ check_req / check_resp           │
│                   ┌─────▼──────────────────────────┐      │
│                   │       IopmpChecker               │      │
│                   │                                   │      │
│                   │  ┌─────────────────────────────┐  │      │
│                   │  │  SrcmdTable                  │  │      │
│                   │  │  RRID -> MD 位图查找          │  │      │
│                   │  └──────────────┬──────────────┘  │      │
│                   │                 │                  │      │
│                   │  ┌──────────────▼──────────────┐  │      │
│                   │  │  MdcfgTable                  │  │      │
│                   │  │  MD -> Entry 范围映射         │  │      │
│                   │  └──────────────┬──────────────┘  │      │
│                   │                 │                  │      │
│                   │  ┌──────────────▼──────────────┐  │      │
│                   │  │  EntryTable                  │  │      │
│                   │  │  地址范围 + 权限检查           │  │      │
│                   │  │  (最多 512 条目)              │  │      │
│                   │  └──────────────┬──────────────┘  │      │
│                   │                 │                  │      │
│                   │  ┌──────────────▼──────────────┐  │      │
│                   │  │  Ctrl FSM                     │  │      │
│                   │  │  sIdle -> sSrcmd -> sMdcfg   │  │      │
│                   │  │  -> sEntry -> sPriority      │  │      │
│                   │  │  -> sMatching -> sErr/sDone  │  │      │
│                   │  └─────────────────────────────┘  │      │
│                   └───────────────────────────────────┘      │
│                                                           │
│  AXI4 Slave ◀─── check_resp (通过/拒绝)                   │
│  (目标外设)                                                │
│                                                           │
│  APB ──▶ RegMap (配置接口)                                │
│  (配置总线)    - version, hwcfg0/1/2                       │
│               - errcfg, errinfo, erreqaddr                 │
└───────────────────────────────────────────────────────────┘
```

### 10.4 IOPMP 三级表查找

1. **SrcmdTable**：将 RRID（Requestor ID）映射到 Master Domain（MD）位图
   - 每个 RRID 可以属于多个 MD
   - 输出 MD 位图用于下一级查找

2. **MdcfgTable**：将每个 MD 映射到 Entry 的起始和结束索引
   - 每个 MD 对应一组连续的 EntryTable 条目
   - 快速定位需要检查的条目范围

3. **EntryTable**：存储具体的地址范围和权限信息
   - 支持 NAPOT（Naturally Aligned Power-of-Two）地址范围
   - 支持 NA4（4 字节精确匹配）地址范围
   - 每个条目包含：`r, w, x, a, sire, siwe, sixe, sere, sewe, sexe` 权限位

### 10.5 Ctrl FSM 状态

```
sIdle ──▶ sSrcmd ──▶ sMdcfg ──▶ sMdcfgPre ──▶ sEntry
  ▲                                                   │
  │                                                   ▼
  │                                             sPriority
  │                                                   │
  │                                                   ▼
  │                                             sMatching
  │                                                   │
  │                                            ┌──────┴──────┐
  │                                            │             │
  │                                      ┌─────▼────┐  ┌────▼────┐
  │                                      │  sDone    │  │  sErr   │
  │                                      │ (允许通过) │  │ (拒绝)  │
  │                                      └──────────┘  └─────────┘
```

### 10.6 NAPOT 解码器

`NAPOTDecoder` 将 NAPOT 编码转换为具体的地址范围：

- 输入：NAPOT 编码的基地址和 log2 大小
- 输出：`(start, end)` 地址范围
- 支持的大小：8 字节到 2^56 字节的 2 的幂次方范围
- 典型应用：保护整个 I/O 外设地址空间

---

## 11. ECC 保护

### 11.1 SECDED 编码

页表缓存（Page Table Cache）使用 SECDED（Single Error Correction, Double Error Detection）ECC 保护：

- **编码**：在条目写入缓存时生成 ECC 校验码
- **解码**：在条目读取时验证并纠正单位错误
- **双位错误检测**：无法纠正，触发页面错误
- **实现位置**：`PTWEntriesWithEcc` 封装了编解码逻辑

### 11.2 保护范围

ECC 保护应用于：
- L3/L2 全相联缓存条目
- L1/L0 组相联缓存条目
- SP（SuperPage）缓存条目
- Bitmap 缓存条目

---

## 12. Repeater 与 Filter 机制

### 12.1 PTWRepeater

`PTWRepeater`（`Repeater.scala`）是 L1 TLB 与 L2 TLB 之间的单条目缓冲器：

- 使用 `BoolStopWatch` 跟踪请求状态
- 当 L1 TLB miss 且 L2 TLB 正忙时，缓冲请求
- 防止 L1 TLB 被长时间阻塞
- 仅支持单条目，如果新请求到来且旧请求未完成，则阻塞 L1 TLB

### 12.2 请求去重

`L2TlbMissQueue` 实现请求去重：
- 当多个 L1 TLB（如 iTLB 和 dTLB）同时 miss 同一 VPN 时
- 仅向 L2 TLB 发送一次遍历请求
- 所有等待该 VPN 的 L1 TLB 共享遍历结果

---

## 13. 性能监控计数器

MMU 子系统提供以下性能事件供 CSR 读取：

| 事件 | 描述 |
|------|------|
| ITLB miss | 指令 TLB 未命中 |
| DTLB miss | 数据 TLB 未命中 |
| TLB miss (L1) | L1 TLB 总未命中数 |
| L2 TLB miss | L2 TLB 未命中（需要遍历内存） |
| Page fault | 权限检查失败 |
| PTW cycle | 页表遍历周期数 |
| TLB flush | TLB 刷新次数 |

---

## 14. 关键设计特性总结

1. **全相联 L1 TLB**：48 Way 全相联设计，最大化命中率
2. **Sector TLB**：每个条目映射 8 个 4KB 页面，扩大覆盖范围
3. **多级页表缓存**：L3/L2/L1/L0/SP 五级缓存，平衡命中率与访问延迟
4. **并行叶节点遍历**：LLPTW 支持 6 路并行遍历，加速 TLB miss 处理
5. **s/w 寄存器对**：PTW 状态机使用请求-等待配对，清晰的流水线设计
6. **完整的 H-Extension 支持**：两级翻译、HPTW、GPA 处理
7. **Sfence 全系列支持**：sfence.vma、hfence.vvma、hfence.gvma
8. **ECC 保护**：SECDED 编码确保页表缓存数据完整性
9. **请求去重**：避免多个 L1 TLB 重复遍历同一页面
10. **ChiselIOPMP**：三级表查找的 I/O 内存保护，支持 NAPOT 地址范围

---

## 15. 关键源文件路径

| 功能模块 | 源文件路径 |
|----------|-----------|
| TLB 参数常量 | `src/main/scala/xiangshan/cache/mmu/MMUConst.scala` |
| MMU Bundle 定义 | `src/main/scala/xiangshan/cache/mmu/MMUBundle.scala` |
| L1 TLB 主模块 | `src/main/scala/xiangshan/cache/mmu/TLB.scala` |
| TLB 存储实现 | `src/main/scala/xiangshan/cache/mmu/TLBStorage.scala` |
| 页表遍历器 | `src/main/scala/xiangshan/cache/mmu/PageTableWalker.scala` |
| 页表缓存 | `src/main/scala/xiangshan/cache/mmu/PageTableCache.scala` |
| L2 TLB 集成 | `src/main/scala/xiangshan/cache/mmu/L2TLB.scala` |
| Miss Queue 去重 | `src/main/scala/xiangshan/cache/mmu/L2TlbMissQueue.scala` |
| 预取逻辑 | `src/main/scala/xiangshan/cache/mmu/L2TlbPrefetch.scala` |
| Repeater 缓冲 | `src/main/scala/xiangshan/cache/mmu/Repeater.scala` |
| Bitmap 检查 | `src/main/scala/xiangshan/cache/mmu/BitmapCheck.scala` |
| TLB 参数配置 | `src/main/scala/xiangshan/Parameters.scala` |
| MemBlock dTLB 实例化 | `src/main/scala/xiangshan/mem/MemBlock.scala` |
| Frontend iTLB 实例化 | `src/main/scala/xiangshan/frontend/Frontend.scala` |
| IOPMP 顶层 | `ChiselIOPMP/src/main/scala/Iopmp.scala` |
| IOPMP 检查器 | `ChiselIOPMP/src/main/scala/IopmpChecker.scala` |
| IOPMP 桥接 | `ChiselIOPMP/src/main/scala/IopmpBridge.scala` |

---

*报告生成日期：2026-06-09*
*基于 XiangShan 源码分析*
*分析范围：MMU/TLB 子系统全部 11 个源文件 + IOPMP 3 个源文件 + Parameters.scala 配置 + MemBlock/Frontend 实例化*