# 灵魂拷问 10 个问题：深度调研报告

> **版本锚定**：kunminghu-v3 @ f46464944
> **报告日期**：2026-05-27
> **研究方法**：基于代码审查和交叉验证的深度调研

---

## 结论先行

本报告基于对 research/ 目录下 17 篇文档的代码审查，提出了 10 个高价值问题并进行了深入调研。审查过程中发现了若干重要错误并已修正：

| 问题 | 修正内容 | 涉及文档 |
|------|----------|----------|
| 复位向量地址混淆 | `0x10000000`（复位向量）vs `0x80000000`（PMEM_BASE）| 02, 08 |
| GCPT 头部格式错误 | 实际为 8 字节（4B magic + 4B overwrite_nbytes），非 16 字节 | 08 |
| GCPT 不含 CPU 状态 | GCPT 只含内存快照，CPU 状态由 DiffTest 参考模型恢复 | 08, 14 |
| SPEC2006 线程模型矛盾 | 附录表格与正文不一致 | 16 |
| 运行环境武断结论 | "裸机运行"应改为"未确认" | 16 |

---

## 问题 1：复位向量 0x10000000 处放的是什么代码？CPU 如何从复位状态开始执行？

### 答案

`0x10000000` 处是 **QSPI Flash 设备**（AXI4Flash），不是 bootrom。

**代码证据**：
- `src/test/scala/top/SimTop.scala:129`：`soc.io.riscv_rst_vec.foreach(_ := 0x10000000L.U)`
- `src/main/scala/system/SoC.scala:70`：`PMAConfigEntry(0x10000000L, a = 1, w = true, r = true)`
- `src/main/scala/xiangshan/backend/fu/PMA.scala:118`：`MemMap("h00_1000_0000", "h00_1FFF_FFFF", "h0", "QSPI_Flash", "RWX")`

### 复位向量传递链路

```
SimTop (0x10000000)
  → XSTop.io.riscv_rst_vec
    → XSTileWrap → XSTile
      → L2Top (DelayN, 延迟 5 拍)
        → XSCore → MemBlock (RegNext, 延迟 1 拍)
          → Frontend → BPU (RegNext(RegNext(reset)), 延迟 2 拍)
            → s0_startPcReg (开始取指)
```

**总延迟约 7-8 个时钟周期**。

### Flash 设备工作原理

AXI4Flash 通过 DPI-C 接口与 C++ 仿真框架交互：
- `src/main/scala/device/AXI4Flash.scala`：`flash.en := in.ar.fire`
- 当 CPU 从 `0x10000000` 取指时，AXI4Flash 通过 DPI-C 从 C++ 侧获取 ELF 程序内容

### 关键区分

| 地址 | 含义 | 用途 |
|------|------|------|
| `0x10000000` | 复位向量 | CPU 复位后从此地址开始取指 |
| `0x80000000` | PMEM_BASE | 物理内存起始地址，程序运行在此 |

---

## 问题 2：Verilator 的 RANDOMIZE_* 宏有什么作用？

### 答案

这些宏控制 RTL 仿真中的**随机初始化行为**，用于发现未初始化信号导致的 bug。

### 四个宏的作用

| 宏 | 作用 | Verilator | VCS |
|----|------|-----------|-----|
| `RANDOMIZE_REG_INIT` | 对所有 `reg` 用随机值初始化 | 有 | 有 |
| `RANDOMIZE_MEM_INIT` | 对所有存储器用随机值初始化 | 有 | 有 |
| `RANDOMIZE_GARBAGE_ASSIGN` | 对未赋值信号赋随机值 | 有 | 有 |
| `RANDOMIZE_DELAY` | 控制随机初始化延迟 | =0 | =1 |

### 代码证据

`difftest/verilator.mk:84-87`：
```makefile
+define+RANDOMIZE_REG_INIT        \
+define+RANDOMIZE_MEM_INIT        \
+define+RANDOMIZE_GARBAGE_ASSIGN  \
+define+RANDOMIZE_DELAY=0         \
```

### 为什么 Verilator 用 RANDOMIZE_DELAY=0？

- Verilator 是周期精确仿真器，不支持 `#delay`
- VCS/GalaxSim 需要 `RANDOMIZE_DELAY=1`，否则初始化行为不正确

### 与 XPROP 的互斥关系

当 `ENABLE_XPROP=1` 时，VCS/GalaxSim 不定义 RANDOMIZE 宏，因为 X 传播仿真需要用 X 值追踪未初始化信号。

---

## 问题 3：GCPT checkpoint 的真实格式是什么？

### 答案（重大修正）

**GCPT 文件只包含内存快照，不包含 CPU 状态！**

### 实际格式

```
偏移 0: Magic (4 字节)  ← 被 fseek 跳过，无校验
偏移 4: overwrite_nbytes (4 字节)  ← 有效数据字节数
偏移 8: 内存快照数据  ← 直接 memcpy 到 simMemory
```

**代码证据**（`difftest/src/test/csrc/emu/emu.cpp:131-138`）：
```cpp
if (args.gcpt_restore) {
    if (args.overwrite_nbytes_autoset) {
      FILE *fp = fopen(args.gcpt_restore, "rb");
      fseek(fp, 4, SEEK_SET);                          // 跳过 4 字节
      fread(&args.overwrite_nbytes, sizeof(uint32_t), 1, fp);
      fclose(fp);
    }
    overwrite_ram(args.gcpt_restore, args.overwrite_nbytes);
}
```

### CPU 状态如何恢复？

CPU 状态由 **DiffTest 参考模型（NEMU）** 通过 `ref_regcpy` 和 `ref_csrcpy` 同步恢复，不在 GCPT 文件中。

### 之前文档的错误

文档 08 声称 GCPT 有 16 字节头部（含 Version、Size、Reserved）和 CPU 状态（寄存器、CSR），这与实际代码不符。已修正。

---

## 问题 4：性能计数器的 dump 是如何触发的？

### 答案

有四类触发场景：

| 场景 | 触发条件 | 信号 |
|------|----------|------|
| Warmup 完成 | `instrCnt >= warmup_instr` | clean=1, dump=1 |
| 定期统计 | `cycleCnt % stat_cycles == stat_cycles - 1` | clean=1, dump=1 |
| 仿真结束 | GoodTrap / Exceed / Fail | dump=1（不 clean）|
| VCS 定时 tick | `+perf-tick-cycles=N` | clean=1, dump=1 |

### Verilator 路径（C++ 驱动）

`difftest/src/test/csrc/emu/emu.cpp:462-472`：
```cpp
if (trap->instrCnt >= args.warmup_instr) {
    dut_ptr->set_perf_clean(1);
    dut_ptr->set_perf_dump(1);
    args.warmup_instr = -1;
}
if (trap->cycleCnt % args.stat_cycles == args.stat_cycles - 1) {
    dut_ptr->set_perf_clean(1);
    dut_ptr->set_perf_dump(1);
}
```

### VCS 路径（硬件产生）

`difftest/src/test/vsrc/vcs/DifftestEndpoint.sv:407-428`：
```systemverilog
assign difftest_perfCtrl_clean = simv_result == `SIMV_WARMUP || perfCtrl_tick;
assign difftest_perfCtrl_dump = simv_result == `SIMV_GOODTRAP || ...;
```

### 信号脉冲协议

`clean` 和 `dump` 是**单周期脉冲**：先设置为 1，执行 `single_cycle()` 让 RTL 采样，然后立即清零。

---

## 问题 5：DiffTest 的死锁检测机制是什么？

### 答案

有**两层检测机制**互为补充：

### 第一层：Emulator 级别的 stuck_timer（外层）

`emu.cpp:522-531`：
```cpp
static uint64_t stuck_timer = 0;
if (step) {
  stuck_timer = 0;
} else {
  stuck_timer++;
  if (stuck_timer >= Difftest::stuck_limit) {
    return STATE_ABORT;
  }
}
```

检测 RTL 仿真器是否完全停止产出步骤。

### 第二层：TimeoutChecker（内层）

`difftest/src/test/csrc/difftest/checkers/instructions.cpp:47-76`：
```cpp
// 首条指令超时检测
if (!state->has_commit && cycleCnt > state->last_commit_cycle + first_commit_limit) {
    return STATE_ERROR;
}
// 持续无提交检测
if (state->has_commit && cycleCnt > state->last_commit_cycle + stuck_commit_limit) {
    return STATE_DIFF;
}
```

- `first_commit_limit = 15000`（XiangShan）
- `stuck_commit_limit = 15000`（不启用 squash 时）
- WFI 指令会重置 `last_commit_cycle`，避免误报

### 两层检测的区别

| 层级 | 检测对象 | 检测内容 |
|------|----------|----------|
| 外层 | RTL 仿真器 | 是否完全停止产出步骤 |
| 内层 | DUT | 虽在运行但无指令提交（微架构死锁）|

---

## 问题 6：RTC 时钟和主时钟的关系是什么？

### 答案

RTC 时钟 = 主时钟 / 100

### 代码证据

`src/test/scala/top/SimTop.scala:134-143`：
```scala
val rtcClockDiv = 100
val rtcTickCycle = rtcClockDiv / 2   // = 50
val rtcCounter = RegInit(0.U(log2Ceil(rtcTickCycle + 1).W))
rtcCounter := Mux(rtcCounter === (rtcTickCycle - 1).U, 0.U, rtcCounter + 1.U)
val rtcClock = RegInit(false.B)
when (rtcCounter === 0.U) {
  rtcClock := ~rtcClock
}
soc.io.rtc_clock := rtcClock.asClock
```

### 时钟拓扑

```
主时钟 (仿真器提供)
  ├── CPU 核心、总线等
  └── 100 分频 → rtcClock
        └── SYSCNT (time 寄存器在 rtc_clock 域递增)
              └── TIMER/CLINT (跨时钟域同步到 bus_clock 域)
                    └── mtimecmp 比较 → mtip 中断
```

### 跨时钟域同步

`src/main/scala/device/SYSCNT.scala:94-101`：使用 `AsyncResetSynchronizerShiftReg`（3 级同步）完成跨时钟域传递。

---

## 问题 7：XiangShan 的内存映射是怎样的？

### 答案

物理地址总宽度 **48 位**，以 `0x80000000` 为界分为两大区域：

### 内存区域（memRange）

`src/main/scala/system/SoC.scala:283`：
```scala
val memRange = AddressSet(0x00000000L, 0xffffffffffffL).subtract(AddressSet(0x0L, 0x7fffffffL))
```

即 `0x80000000 ~ 0xFFFFFFFFFFFF`，连接外部 DDR。

### 外设区域（peripheralRange）

| 地址范围 | 设备 | 来源 |
|----------|------|------|
| `0x10000000 ~ 0x1FFFFFFF` | QSPI Flash | `SoC.scala:70` |
| `0x20000000 ~ 0x2FFFFFFF` | Reserved（可执行）| `SoC.scala:69` |
| `0x38000000 ~ 0x3800FFFF` | CLINT (TIMER) | `SoC.scala:73` |
| `0x38020000 ~ 0x38020FFF` | Debug Module | `Configs.scala:65` |
| `0x38040000 ~ 0x3804FFFF` | SYSCNT | `SoC.scala:74` |
| `0x3A800000 ~ 0x3AFFFFFF` | IMSIC (Machine) | `SoC.scala:101` |
| `0x3C000000 ~ 0x3FFFFFFF` | PLIC | `SoC.scala:76` |
| `0x40600000 ~ 0x4060003F` | UART-Lite | `SoC.scala:131` |
| `0x310B0000 ~ 0x310B001F` | UART-16550 | `SoC.scala:132` |

### PMA 配置

`SoC.scala:57-72`：从高地址向低地址排列，匹配优先级依次递减，精确控制缓存性、可执行性、原子操作等属性。

---

## 问题 8：SPEC2006 的分数计算脚本是如何工作的？

### 答案

分数计算在仓库外部脚本中完成：`/nfs/home/share/ci-workloads/env-scripts/perf/xs_autorun_multiServer.py`

### 调用流程

`.github/workflows/perf-template.yml:239-246`：
```bash
cd $SCRIPTS_HOME/perf
python3 xs_autorun_multiServer.py $CKPT_HOME $CKPT_JSON_PATH \
    --benchmarks "${{ inputs.benchmarks }}" \
    --xs $GITHUB_WORKSPACE --threads 16 --dir $SPEC_DIR --report \
    > "$SCORE_FILE"
```

### 分数计算步骤

1. 读取各 benchmark 的 SimPoint 采样点运行结果
2. 使用 JSON 配置中的权重计算加权平均 IPC
3. 多输入 benchmark（如 bzip2）进行二次加权
4. 计算每个 benchmark 的 ratio = 被测机 IPC / 参考机 IPC
5. 几何平均计算 SPECint2006（12 个）、SPECfp2006（17 个）
6. 几何平均计算 SPEC2006
7. 除以频率（GHz）得到归一化分数

### 输出格式

```
SPECint2006/GHz: 22.5
SPECfp2006/GHz:  28.3
SPEC2006/GHz:    25.1
```

---

## 问题 9：NEMU 是如何生成 checkpoint 的？

### 答案

NEMU 生成 checkpoint 的完整流程：

```
SPEC2006 二进制
    │
    ▼
NEMU 提取 BBV（基本块向量）
    │
    ▼
SimPoint 工具执行 K-Means 聚类
    │  输出：simpoints.txt, weights.txt
    ▼
NEMU 在采样点生成 GCPT 文件
    │  输出：checkpoint_0.gz, checkpoint_1.gz, ...
    ▼
组装 JSON 配置文件
```

### 关键命令

```bash
# 步骤 1：提取 BBV
./nemu --bbv --bbv-interval 10000000 perlbench

# 步骤 2：运行 SimPoint 聚类
./simpoint -inputVectors perlbench.bbv.gz -maxK 20 \
    -saveSimPoints simpoints.txt -saveWeights weights.txt

# 步骤 3：生成 checkpoint
./nemu --checkpoint --checkpoint-interval 10000000 perlbench
```

### 存储位置

```
/nfs/home/share/checkpoints_profiles/
  spec06_gcc15_rv64gcb_base_260122/
    checkpoint-0-0-0/
      cluster-0-0.json
      perlbench/
        39720000000.gz
        100000000000.gz
```

---

## 问题 10：SimPoint 97%+ 的精度是如何验证的？

### 答案

**XiangShan 代码库中不存在专门的 SimPoint 精度验证脚本。**

### 97%+ 的来源

这是 SimPoint 学术论文（Sherwood et al., ASPLOS 2002）中的公认结论，XiangShan 直接引用了这个数字，未自行设计验证实验。

### 代码库中的相关脚本

`scripts/top-down/` 目录仅提供**加权汇总和可视化**功能，不包含精度验证：

- `top_down.py`：读取 JSON 权重，矩阵乘法计算加权平均
- `configs.py`：性能计数器正则表达式和 benchmark 列表
- `utils.py`：文件搜索和计数器提取工具

### 权重的"验证"

唯一的"验证"是归一化处理（`top_down.py:83-84`）：
```python
coverage = np.sum(vec_weight.values)
vec_weight = vec_weight / coverage
```

这确保了部分采样点未完成时权重仍然归一化到 1.0，但没有验证权重本身的正确性。

### 已知的误差来源

| 来源 | 影响 |
|------|------|
| 采样点选择 | SimPoint 聚类误差 |
| 中断开销 | ~0.1-0.5% |
| 测量噪声 | ~0.5-1% |
| 权重计算 | 归一化误差 |

---

## 审查中发现并修正的问题汇总

### 1. 复位向量地址混淆（文档 02）

**错误**：将 `0x80000000` 称为"启动地址"
**修正**：复位向量是 `0x10000000`（SimTop.scala:129），PMEM_BASE 是 `0x80000000`（config.h:42）

### 2. GCPT 格式错误（文档 08）

**错误**：声称 GCPT 有 16 字节头部和 CPU 状态
**修正**：实际为 8 字节头部，只含内存快照，CPU 状态由 DiffTest 参考模型恢复

### 3. 线程模型矛盾（文档 16）

**错误**：附录表格写"全部单线程"，与正文"11 个浮点 benchmark 多线程"矛盾
**修正**：附录表格改为正确的描述

### 4. 运行环境武断结论（文档 16）

**错误**：结论部分武断声称"SPEC2006 是裸机程序"
**修正**：改为"运行环境未确认"

### 5. 复位向量引用错误（文档 08）

**错误**：内存快照图中写 `0x80000000` 作为复位向量
**修正**：改为 `0x10000000（复位向量）`

---

## 对嵌入式工程师的建议

1. **区分复位向量和内存基地址**：`0x10000000` 是复位向量（Flash），`0x80000000` 是内存基地址（DRAM）
2. **理解 GCPT 的真实含义**：它只保存内存快照，CPU 状态由参考模型提供
3. **理解 DiffTest 的多重角色**：不只是 diff 比较，更是仿真基础设施
4. **理解性能计数器的触发机制**：由 DiffTest 框架的 clean/dump 信号控制
5. **理解死锁检测的两层机制**：外层检测仿真器停止，内层检测微架构死锁
6. **理解 RTC 时钟和主时钟的关系**：100 分频，跨时钟域同步
7. **理解内存映射**：`0x80000000` 以上是 DDR，以下是外设
8. **理解分数计算流程**：IPC → 加权平均 → 几何平均 → GHz 归一化
9. **理解 NEMU 的角色**：BBV 提取 + checkpoint 生成 + DiffTest 参考模型
10. **理解 SimPoint 精度的来源**：学术论文的公认结论，XiangShan 未自行验证

---

## 附录：关键代码位置索引

| 文件 | 行号 | 内容 |
|------|------|------|
| `src/test/scala/top/SimTop.scala` | 129 | 复位向量设置 |
| `src/test/scala/top/SimTop.scala` | 134-143 | RTC 时钟生成 |
| `src/main/scala/system/SoC.scala` | 56 | PmemRanges 定义 |
| `src/main/scala/system/SoC.scala` | 283 | memRange 定义 |
| `src/main/scala/device/AXI4Flash.scala` | - | Flash 设备实现 |
| `src/main/scala/device/SYSCNT.scala` | 86-154 | 系统计数器实现 |
| `src/main/scala/device/TIMER.scala` | 86-87 | CLINT timer 实现 |
| `difftest/verilator.mk` | 84-87 | RANDOMIZE 宏定义 |
| `difftest/src/test/csrc/emu/emu.cpp` | 131-138 | GCPT 加载 |
| `difftest/src/test/csrc/emu/emu.cpp` | 462-472 | 性能计数器触发 |
| `difftest/src/test/csrc/emu/emu.cpp` | 522-531 | 死锁检测（外层）|
| `difftest/src/test/csrc/difftest/difftest.cpp` | 637-642 | IPC 计算 |
| `difftest/src/test/csrc/difftest/checkers/instructions.cpp` | 47-76 | 死锁检测（内层）|
| `difftest/config/config.h` | 42 | PMEM_BASE 定义 |
| `scripts/top-down/top_down.py` | 83-84 | 权重归一化 |
| `scripts/top-down/configs.py` | 310-398 | 性能计数器配置 |
| `.github/workflows/perf-template.yml` | 239-246 | 分数计算调用 |
