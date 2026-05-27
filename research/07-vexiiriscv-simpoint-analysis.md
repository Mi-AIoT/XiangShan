# VexiiRiscv 与 SimPoint 对接可行性分析

> 本文档分析 VexiiRiscv 项目是否可以对接 SimPoint 技术进行 SPEC2006 评分。

---

## 目录

1. [VexiiRiscv 项目概述](#1-vexiiriscv-项目概述)
2. [架构对比：VexiiRiscv vs XiangShan](#2-架构对比vexiiriscv-vs-xiangshan)
3. [SpinalHDL vs Chisel 仿真流程](#3-spinalhdl-vs-chisel-仿真流程)
4. [SimPoint 对接可行性分析](#4-simpoint-对接可行性分析)
5. [技术实现方案](#5-技术实现方案)
6. [工作量评估](#6-工作量评估)
7. [结论与建议](#7-结论与建议)

---

## 1. VexiiRiscv 项目概述

### 1.1 基本信息

| 项目 | 信息 |
|------|------|
| **仓库** | https://github.com/SpinalHDL/VexiiRiscv |
| **作者** | Charles Papon (SpinalHDL 作者) |
| **语言** | SpinalHDL (Scala 99%) |
| **许可证** | MIT |
| **目标** | 可配置的 RISC-V 处理器，从 Cortex-M0 到 Cortex-A53 |

### 1.2 架构特点

**可配置架构**：
- 单发射/双发射可选
- 顺序执行/乱序执行可选
- 可选 L1 缓存
- 可选 MMU (SV32/SV39)
- 可选浮点单元

**支持的 RISC-V 扩展**：
- RV32/64 I, M, A, F, D, C, S, U, B
- 覆盖整数、乘法、原子操作、浮点、压缩指令、特权模式、位操作

**性能指标**：
- CoreMark: 5.24/MHz
- Dhrystone: 2.50/MHz

### 1.3 插件化架构

VexiiRiscv 使用 SpinalHDL 的插件系统：

```
┌─────────────────────────────────────────────────────────────┐
│                    VexiiRiscv 插件架构                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐   │
│  │ FetchPlugin   │  │ DecodePlugin  │  │ ExecutePlugin │   │
│  │ (取指)        │  │ (译码)        │  │ (执行)        │   │
│  └───────────────┘  └───────────────┘  └───────────────┘   │
│                                                             │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐   │
│  │ BtbPlugin     │  │ GSharePlugin  │  │ RasPlugin     │   │
│  │ (BTB)         │  │ (分支预测)    │  │ (返回栈)      │   │
│  └───────────────┘  └───────────────┘  └───────────────┘   │
│                                                             │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐   │
│  │ LsuPlugin     │  │ MmuPlugin     │  │ FpuPlugin     │   │
│  │ (Load/Store)  │  │ (MMU)         │  │ (浮点)        │   │
│  └───────────────┘  └───────────────┘  └───────────────┘   │
│                                                             │
│  ┌───────────────┐  ┌───────────────┐                       │
│  │ CsrPlugin     │  │ PerfCounter   │                       │
│  │ (CSR)         │  │ (性能计数器)  │                       │
│  └───────────────┘  └───────────────┘                       │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 1.4 支持的总线接口

| 总线 | 说明 |
|------|------|
| **AXI4** | 高性能总线，适合 SoC 集成 |
| **Wishbone** | 开源总线，适合 FPGA |
| **TileLink** | RISC-V 生态总线 |

---

## 2. 架构对比：VexiiRiscv vs XiangShan

### 2.1 设计目标对比

| 方面 | VexiiRiscv | XiangShan |
|------|------------|-----------|
| **目标** | 可配置，从 MCU 到中端应用处理器 | 高性能，对标现代 x86/ARM |
| **复杂度** | 中等（可伸缩） | 高（复杂乱序） |
| **流水线** | 5-7 级，单/双发射 | 10+ 级，4-6 发射 |
| **乱序能力** | 可选，有限 | 完整乱序执行 |
| **缓存** | 可选 L1 | L1 + L2 + L3 |
| **目标平台** | FPGA + ASIC | 主要 ASIC |

### 2.2 性能对比

| 指标 | VexiiRiscv | XiangShan |
|------|------------|-----------|
| **CoreMark/MHz** | 5.24 | ~15 (估算) |
| **Dhrystone/MHz** | 2.50 | ~10 (估算) |
| **SPEC2006/GHz** | 未评估 | 25+ |
| **频率 (FPGA)** | 200+ MHz | N/A |
| **频率 (ASIC)** | 1+ GHz | 2+ GHz |

### 2.3 仿真工具对比

| 工具 | VexiiRiscv | XiangShan |
|------|------------|-----------|
| **主要仿真器** | SpinalSim + Verilator | Verilator |
| **ISA 模拟器** | Spike | NEMU |
| **DiffTest** | Spike lock-step | NEMU DiffTest |
| **波形** | VCD/FST + GTKWave | VCD/FST + GTKWave |
| **流水线可视化** | Konata | 无 |

### 2.4 关键差异

```
VexiiRiscv:
┌─────────────────────────────────────────────────────────┐
│  SpinalHDL 源码 (Scala)                                 │
│       │                                                 │
│       ▼ (SpinalHDL 编译器)                              │
│  Verilog 输出 (VexiiRiscv.v)                            │
│       │                                                 │
│       ▼ (SpinalSim + Verilator)                         │
│  仿真执行                                               │
│       │                                                 │
│       ▼ (Spike lock-step)                               │
│  功能验证                                               │
└─────────────────────────────────────────────────────────┘

XiangShan:
┌─────────────────────────────────────────────────────────┐
│  Chisel 源码 (Scala)                                    │
│       │                                                 │
│       ▼ (mill + firtool)                                │
│  SystemVerilog 输出 (SimTop.sv)                         │
│       │                                                 │
│       ▼ (Verilator + DiffTest 框架)                     │
│  仿真执行                                               │
│       │                                                 │
│       ▼ (NEMU DiffTest)                                 │
│  功能验证                                               │
└─────────────────────────────────────────────────────────┘
```

---

## 3. SpinalHDL vs Chisel 仿真流程

### 3.1 SpinalHDL 仿真流程

**SpinalSim** 是 SpinalHDL 的仿真框架：

```scala
// VexiiRiscv 仿真示例
import spinal.core._
import spinal.core.sim._

object VexiiRiscvSim {
  def main(args: Array[String]): Unit = {
    // 配置 VexiiRiscv
    val config = VexiiRiscvConfig()
    config.add(new FetchPlugin)
    config.add(new DecodePlugin)
    config.add(new ExecutePlugin)
    // ...
    
    // 编译为 Verilator
    val compiled = SimConfig
      .withConfig(config)
      .withWave
      .compile(new VexiiRiscv)
    
    // 运行仿真
    compiled.doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      
      // 加载程序
      loadMemory(dut, "program.bin")
      
      // 运行
      for (i <- 0 until 1000000) {
        dut.clockDomain.waitSampling()
      }
    }
  }
}
```

### 3.2 Chisel 仿真流程

**ChiselTest** 是 Chisel 的仿真框架：

```scala
// XiangShan 仿真示例
import chisel3._
import chiseltest._

object XiangShanSim {
  def main(args: Array[String]): Unit = {
    // 编译为 Verilator
    val compiled = new VerilatorBackendAnnotation
    
    // 运行仿真
    test(new SimTop).withAnnotations(Seq(compiled)) { dut =>
      dut.clock.setTimeout(1000000)
      
      // 加载程序
      loadMemory(dut, "program.bin")
      
      // 运行
      while (!dut.io.trap.peek().litToBoolean) {
        dut.clock.step()
      }
    }
  }
}
```

### 3.3 关键差异

| 方面 | SpinalHDL (SpinalSim) | Chisel (ChiselTest) |
|------|----------------------|---------------------|
| **API 风格** | 函数式，简洁 | 命令式，详细 |
| **仿真后端** | Verilator, GHDL, Java | Verilator, Treadle |
| **线程模型** | SimThread | PeekPoke |
| **波形支持** | 内置 | 需配置 |
| **学习曲线** | 较平缓 | 较陡峭 |

### 3.4 Verilator 集成

**两者都使用 Verilator 作为主要仿真后端**：

```
SpinalHDL:
  SpinalHDL 源码 → SpinalHDL 编译器 → Verilog → Verilator → C++ → 仿真

Chisel:
  Chisel 源码 → firtool → SystemVerilog → Verilator → C++ → 仿真
```

**结论**：两者的 Verilator 仿真流程相似，可以复用 XiangShan 的 Verilator 编译框架。

---

## 4. SimPoint 对接可行性分析

### 4.1 可行性评估

| 组件 | 可行性 | 难度 | 说明 |
|------|--------|------|------|
| **BBV 提取** | ✅ 可行 | 中 | 需要在仿真中提取基本块向量 |
| **SimPoint 聚类** | ✅ 可行 | 低 | 使用现有 SimPoint 工具 |
| **Checkpoint 生成** | ⚠️ 需开发 | 高 | 需要实现状态保存/恢复 |
| **性能计数器** | ✅ 可行 | 低 | 已有 PerformanceCounterPlugin |
| **加权计算** | ✅ 可行 | 低 | 可复用 XiangShan 脚本 |

### 4.2 优势

**VexiiRiscv 对接 SimPoint 的优势**：

1. **Verilator 仿真**：与 XiangShan 相同，可复用仿真框架
2. **Spike 支持**：可用于 BBV 提取和功能验证
3. **插件架构**：易于添加性能计数器和 BBV 提取逻辑
4. **总线接口**：支持 AXI4，便于集成内存模型

### 4.3 挑战

**需要解决的问题**：

1. **Checkpoint 机制**：
   - XiangShan 使用 GCPT 格式 + NEMU 生成
   - VexiiRiscv 没有内置的 checkpoint 机制
   - 需要实现类似 lightSSS 的 fork-based checkpoint

2. **DiffTest 框架**：
   - XiangShan 有完整的 DiffTest 框架
   - VexiiRiscv 使用 Spike lock-step，机制不同
   - 需要适配或绕过

3. **内存模型**：
   - XiangShan 使用 DiffTest 的内存模型
   - VexiiRiscv 需要集成类似的内存模型

4. **性能计数器格式**：
   - XiangShan 使用 `$fwrite` 输出特定格式
   - VexiiRiscv 需要适配输出格式

---

## 5. 技术实现方案

### 5.1 方案 1：基于 XiangShan 框架适配（推荐）

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    方案 1：基于 XiangShan 框架适配                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  步骤 1: 生成 VexiiRiscv Verilog                                            │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ SpinalHDL 源码 → SpinalHDL 编译器 → VexiiRiscv.v                   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  步骤 2: 创建仿真顶层                                                      │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 创建 SimTop.v，实例化 VexiiRiscv，添加 DiffTest 接口                │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  步骤 3: 配置 Verilator                                                    │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 复用 XiangShan 的 verilator.mk，修改顶层模块名                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  步骤 4: 添加性能计数器                                                    │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 使用 PerformanceCounterPlugin，添加 $fwrite 输出                    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  步骤 5: 生成 Checkpoint                                                   │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 方法 A: 使用 Spike 生成 checkpoint（需要开发）                       │   │
│  │ 方法 B: 使用 lightSSS fork-based checkpoint                         │   │
│  │ 方法 C: 使用 NEMU 生成（如果 VexiiRiscv 兼容 NEMU）                │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  步骤 6: 运行评估                                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 复用 XiangShan 的分析脚本，修改正则表达式                           │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 方案 2：独立开发评估框架

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    方案 2：独立开发评估框架                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  步骤 1: 使用 SpinalSim 直接仿真                                            │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 不使用 Verilator，使用 SpinalSim 的 Java 仿真器                    │   │
│  │ 优点：简单，无需 C++ 编译                                           │   │
│  │ 缺点：慢，不适合 SPEC2006                                          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  步骤 2: 使用 gem5 全系统仿真                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ gem5 支持 SimPoint，但需要将 VexiiRiscv 集成到 gem5                │   │
│  │ 优点：完整的 SimPoint 支持                                          │   │
│  │ 缺点：工作量大，需要理解 gem5 架构                                  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 5.3 方案 3：使用 Spike + SimPoint（最简方案）

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    方案 3：使用 Spike + SimPoint                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  步骤 1: 使用 Spike 运行 SPEC2006                                           │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Spike 是 RISC-V ISA 模拟器，运行速度快                              │   │
│  │ 可以直接提取 BBV，运行 SimPoint                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                              │                                              │
│                              ▼                                              │
│  步骤 2: 使用 RTL 仿真验证关键采样点                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 只仿真 SimPoint 选择的采样点，验证 Spike 的预测                    │   │
│  │ 优点：快速，精度高                                                  │   │
│  │ 缺点：不是真正的 RTL 评估                                          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. 工作量评估

### 6.1 方案 1 工作量

| 任务 | 工作量 | 说明 |
|------|--------|------|
| 生成 Verilog | 1 天 | SpinalHDL 编译 |
| 创建仿真顶层 | 2-3 天 | 添加 DiffTest 接口 |
| 配置 Verilator | 1 天 | 修改 Makefile |
| 添加性能计数器 | 2-3 天 | 修改插件 |
| Checkpoint 机制 | 1-2 周 | 最复杂的部分 |
| 适配分析脚本 | 1-2 天 | 修改正则表达式 |
| 测试验证 | 3-5 天 | 端到端验证 |
| **总计** | **2-3 周** | |

### 6.2 方案 2 工作量

| 任务 | 工作量 | 说明 |
|------|--------|------|
| SpinalSim 仿真 | 1 周 | 简单但慢 |
| gem5 集成 | 1-2 月 | 需要理解 gem5 |
| **总计** | **1-2 月** | |

### 6.3 方案 3 工作量

| 任务 | 工作量 | 说明 |
|------|--------|------|
| Spike SimPoint | 1 周 | 使用现有工具 |
| RTL 验证 | 1 周 | 只验证关键点 |
| **总计** | **2 周** | |

---

## 7. 结论与建议

### 7.1 可行性结论

**VexiiRiscv 可以对接 SimPoint 技术**，但需要开发工作：

1. **技术上可行**：VexiiRiscv 使用 Verilator 仿真，与 XiangShan 相同
2. **需要开发**：Checkpoint 机制是主要挑战
3. **工作量适中**：2-3 周（方案 1）

### 7.2 推荐方案

**推荐方案 1：基于 XiangShan 框架适配**

**理由**：
1. 复用 XiangShan 的成熟框架
2. Verilator 仿真流程相同
3. 分析脚本可直接复用
4. 工作量可控

### 7.3 实施建议

**阶段 1：基础验证（1 周）**
1. 生成 VexiiRiscv Verilog
2. 配置 Verilator 编译
3. 验证能运行简单程序

**阶段 2：性能计数器（1 周）**
1. 添加 commitInstr 和 total_cycles 计数器
2. 验证 IPC 计算正确

**阶段 3：Checkpoint 支持（1 周）**
1. 实现简单的内存快照加载
2. 验证能从 checkpoint 恢复

**阶段 4：完整评估（3-5 天）**
1. 运行完整 SPEC2006 评估
2. 验证分数合理性

### 7.4 风险评估

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| Checkpoint 不兼容 | 高 | 先实现简化方案 |
| 性能计数器缺失 | 中 | 使用现有插件 |
| 内存模型不匹配 | 中 | 适配 AXI 接口 |
| 仿真速度慢 | 低 | 使用 Verilator 多线程 |

### 7.5 最小可行方案 (MVP)

如果时间有限，可以先实现：

1. **跳过 Checkpoint**：直接运行完整程序到采样点
2. **只计算 IPC**：只添加 commitInstr 和 total_cycles
3. **单 benchmark 测试**：先跑通一个 benchmark

---

## 附录 A：VexiiRiscv 关键资源

| 资源 | 链接 |
|------|------|
| GitHub 仓库 | https://github.com/SpinalHDL/VexiiRiscv |
| 文档 | https://spinalhdl.github.io/VexiiRiscv-RTD/ |
| SpinalHDL 文档 | https://spinalhdl.github.io/SpinalHDL-Documentation/ |
| 讨论区 | https://github.com/SpinalHDL/VexiiRiscv/discussions |

## 附录 B：相关项目

| 项目 | 说明 | 与 SimPoint 的关系 |
|------|------|-------------------|
| **gem5** | 全系统模拟器 | 内置 SimPoint 支持 |
| **NEMU** | RISC-V ISA 模拟器 | 用于 XiangShan checkpoint |
| **Spike** | RISC-V ISA 模拟器 | 可用于 BBV 提取 |
| **SimPoint** | 采样工具 | 核心聚类算法 |
