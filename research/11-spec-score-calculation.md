# SPEC2006 分数计算方法详解

> 本文档详细解释如何从 IPC 计算 SPEC2006 分数，适合嵌入式软件工程师理解。

---

## 目录

1. [什么是 SPEC2006？](#1-什么是-spec2006)
2. [IPC 基础概念](#2-ipc-基础概念)
3. [加权平均计算](#3-加权平均计算)
4. [SPEC 分数计算规则](#4-spec-分数计算规则)
5. [GHz 归一化](#5-ghz-归一化)
6. [XiangShan 的实现](#6-xiangshan-的实现)
7. [计算示例](#7-计算示例)
8. [常见问题解答](#8-常见问题解答)

---

## 1. 什么是 SPEC2006？

### 1.1 简单定义

**SPEC CPU2006**：业界标准的 CPU 性能评估基准测试，包含 12 个整数程序和 17 个浮点程序。

### 1.2 基准测试列表

**整数基准测试 (12 个)**：

| 程序 | 说明 | 类型 |
|------|------|------|
| perlbench | Perl 解释器 | 文本处理 |
| bzip2 | 数据压缩 | 压缩 |
| gcc | C 编译器 | 编译 |
| mcf | 最小费用流 | 组合优化 |
| gobmk | 围棋 AI | 人工智能 |
| hmmer | 基因序列搜索 | 生物信息 |
| sjeng | 国际象棋 AI | 人工智能 |
| libquantum | 量子计算模拟 | 科学计算 |
| h264ref | H.264 视频编码 | 视频处理 |
| omnetpp | 网络模拟 | 网络 |
| astar | 寻路算法 | 路径规划 |
| xalancbmk | XML 处理 | 文本处理 |

**浮点基准测试 (17 个)**：

| 程序 | 说明 | 类型 |
|------|------|------|
| bwaves | 流体力学 | 科学计算 |
| gamess | 量子化学 | 科学计算 |
| milc | 量子色动力学 | 科学计算 |
| zeusmp | 流体力学 | 科学计算 |
| gromacs | 分子动力学 | 科学计算 |
| cactusADM | 广义相对论 | 科学计算 |
| leslie3d | 流体力学 | 科学计算 |
| namd | 分子动力学 | 科学计算 |
| dealII | 有限元分析 | 科学计算 |
| soplex | 线性规划 | 优化 |
| povray | 光线追踪 | 图形 |
| calculix | 有限元分析 | 工程 |
| GemsFDTD | 电磁场模拟 | 科学计算 |
| tonto | 量子化学 | 科学计算 |
| lbm | 格子玻尔兹曼 | 科学计算 |
| wrf | 天气预报 | 科学计算 |
| sphinx3 | 语音识别 | 人工智能 |

### 1.3 SPEC 分数的意义

**SPEC 分数用于**：
- 比较不同 CPU 的性能
- 评估 CPU 设计的优劣
- 指导 CPU 优化方向

---

## 2. IPC 基础概念

### 2.1 什么是 IPC？

**IPC (Instructions Per Cycle)**：每周期指令数，衡量 CPU 效率的指标。

```
IPC = 提交指令数 / 总周期数
    = commitInstr / total_cycles
```

### 2.2 IPC 与其他指标的关系

**IPC vs MIPS**：

```
MIPS = 指令数 / 时间(秒) / 10^6
     = 指令数 / (周期数 / 频率) / 10^6
     = IPC × 频率(MHz)

示例：
IPC = 4.0, 频率 = 3 GHz
MIPS = 4.0 × 3000 = 12000 MIPS
```

**IPC vs CPI**：

```
CPI = Cycles Per Instruction = 1 / IPC

示例：
IPC = 4.0 → CPI = 0.25
```

### 2.3 IPC 的典型值

| CPU 类型 | 典型 IPC | 说明 |
|----------|----------|------|
| 简单 MCU | 0.5-1.0 | 单发射，顺序执行 |
| 中端 CPU | 1.0-2.0 | 双发射，简单乱序 |
| 高端 CPU | 2.0-4.0 | 多发射，复杂乱序 |
| XiangShan | 2.0-4.0 | 4-6 发射，复杂乱序 |

### 2.4 IPC 的局限性

**IPC 不是唯一的性能指标**：

```
性能 = IPC × 频率

示例：
CPU A: IPC=4.0, 频率=2 GHz → 性能 = 8
CPU B: IPC=2.0, 频率=4 GHz → 性能 = 8

两者性能相同，但 IPC 不同
```

---

## 3. 加权平均计算

### 3.1 为什么需要加权平均？

**问题**：一个 benchmark 有多个采样点，每个采样点的 IPC 不同。

**解决方案**：使用 SimPoint 权重计算加权平均。

### 3.2 单个 benchmark 的加权平均

**公式**：

```
IPC_weighted = Σ(weight_i × IPC_i)
```

**示例**：

```
astar_biglakes 有 12 个采样点：

采样点 1: 权重 = 0.000500, IPC = 2.1
采样点 2: 权重 = 0.244818, IPC = 3.5
采样点 3: 权重 = 0.044143, IPC = 2.8
...
采样点 12: 权重 = 0.009740, IPC = 2.2

IPC_weighted = 0.000500 × 2.1 
             + 0.244818 × 3.5 
             + 0.044143 × 2.8 
             + ... 
             + 0.009740 × 2.2
             ≈ 3.2
```

### 3.3 多输入 benchmark 的加权平均

**问题**：某些 benchmark 有多个输入（如 bzip2 有 chicken, combined, liberty）。

**解决方案**：二次加权。

**第一次加权**：按采样点加权

```
IPC_input1 = Σ(weight_point × IPC_point)  // 输入 1 的 IPC
IPC_input2 = Σ(weight_point × IPC_point)  // 输入 2 的 IPC
```

**第二次加权**：按指令数加权

```
IPC_benchmark = (insts_1 × IPC_input1 + insts_2 × IPC_input2) / (insts_1 + insts_2)
```

### 3.4 矩阵乘法实现

**代码位置**：`scripts/top-down/top_down.py:91`

```python
# 矩阵乘法计算加权平均
weight_metrics = np.matmul(
    vec_weight.values.reshape(1, -1),  # 权重向量 (1, W)
    wl_df.values                        # 性能矩阵 (W, N)
)

# 示例：
# vec_weight = [0.0005, 0.2448, 0.0441, ...]  (1 × 12)
# wl_df = [[2.1, 0.60, 0.15, ...],            (12 × N)
#          [3.5, 0.72, 0.08, ...],
#          ...]
# weight_metrics = [[3.2, 0.68, 0.10, ...]]   (1 × N)
```

---

## 4. SPEC 分数计算规则

### 4.1 SPEC 官方规则

**SPEC 官方使用几何平均**：

```
SPECint2006 = geometric_mean(ratio_1, ratio_2, ..., ratio_12)
SPECfp2006 = geometric_mean(ratio_1, ratio_2, ..., ratio_17)
SPEC2006 = geometric_mean(SPECint2006, SPECfp2006)
```

**其中**：

```
ratio_i = 参考机时间_i / 被测机时间_i
        = 被测机 IPC_i / 参考机 IPC_i
```

### 4.2 几何平均 vs 算术平均

**算术平均**：

```
average = (a + b + c + ...) / n

示例：
a = 10, b = 100, c = 1000
average = (10 + 100 + 1000) / 3 = 370
```

**几何平均**：

```
geometric_mean = (a × b × c × ...)^(1/n)

示例：
a = 10, b = 100, c = 1000
geometric_mean = (10 × 100 × 1000)^(1/3) = 100
```

**为什么用几何平均？**

| 方面 | 算术平均 | 几何平均 |
|------|----------|----------|
| **对极端值敏感** | 敏感 | 不敏感 |
| **可比性** | 差 | 好 |
| **SPEC 使用** | 否 | 是 |

**示例**：

```
CPU A: ratio = [10, 10, 10]
CPU B: ratio = [1, 100, 10]

算术平均：
A = 10, B = 37 → B 更好？

几何平均：
A = 10, B = 10 → 相同

几何平均更公平
```

### 4.3 参考机

**SPEC 参考机**：Sun Fire V490 (2006 年)

**参考机规格**：
- CPU: UltraSPARC IV+
- 频率: 1.35 GHz
- IPC: 约 0.5-1.0

**参考机的作用**：
- 提供基准线
- 使得不同 CPU 的分数可比
- 分数表示"比参考机快多少倍"

### 4.4 分数计算公式

**单个 benchmark 的分数**：

```
score_i = 参考机时间_i / 被测机时间_i
        = 被测机 IPC_i / 参考机 IPC_i
```

**SPECint2006**：

```
SPECint2006 = geometric_mean(score_1, score_2, ..., score_12)
            = (score_1 × score_2 × ... × score_12)^(1/12)
```

**SPECfp2006**：

```
SPECfp2006 = geometric_mean(score_1, score_2, ..., score_17)
           = (score_1 × score_2 × ... × score_17)^(1/17)
```

**SPEC2006**：

```
SPEC2006 = geometric_mean(SPECint2006, SPECfp2006)
         = (SPECint2006 × SPECfp2006)^(1/2)
```

---

## 5. GHz 归一化

### 5.1 什么是 GHz 归一化？

**GHz 归一化**：将 SPEC 分数除以 CPU 频率，得到"每 GHz 的分数"。

```
SPEC2006/GHz = SPEC2006 / 频率(GHz)
```

### 5.2 为什么需要 GHz 归一化？

**问题**：不同 CPU 的频率不同，直接比较不公平。

**示例**：

```
CPU A: IPC=4.0, 频率=2 GHz
CPU B: IPC=2.0, 频率=4 GHz

SPEC2006:
A = 4.0 × 2 = 8
B = 2.0 × 4 = 8

SPEC2006/GHz:
A = 8 / 2 = 4
B = 8 / 4 = 2

GHz 归一化后，A 的微架构效率更高
```

### 5.3 GHz 归一化的意义

**GHz 归一化衡量的是**：
- CPU 的微架构效率
- 与频率无关的性能
- IPC 的综合体现

**用途**：
- 比较不同 CPU 的微架构设计
- 评估 CPU 设计的优劣
- 指导 CPU 优化方向

---

## 6. XiangShan 的实现

### 6.1 分数计算脚本

**脚本位置**：`/nfs/home/share/ci-workloads/env-scripts/perf/xs_autorun_multiServer.py`（外部）

**使用方法**：

```bash
cd $SCRIPTS_HOME/perf
python3 xs_autorun_multiServer.py $CKPT_HOME $CKPT_JSON_PATH \
    --benchmarks "${{ inputs.benchmarks }}" \
    --xs $GITHUB_WORKSPACE --threads 16 --dir $SPEC_DIR --report \
    > "$SCORE_FILE"
```

### 6.2 输出示例

**score.txt 示例**：

```
SPEC CPU2006 Benchmark Result
=============================

Integer Benchmarks:
  perlbench:  IPC=1.23, Score=24.5
  bzip2:      IPC=2.45, Score=18.2
  gcc:        IPC=1.89, Score=21.3
  mcf:        IPC=0.45, Score=4.5
  gobmk:      IPC=1.12, Score=11.2
  hmmer:      IPC=2.34, Score=23.4
  sjeng:      IPC=1.56, Score=15.6
  libquantum: IPC=3.21, Score=32.1
  h264ref:    IPC=1.78, Score=17.8
  omnetpp:    IPC=0.89, Score=8.9
  astar:      IPC=0.67, Score=6.7
  xalancbmk:  IPC=1.34, Score=13.4

Floating-Point Benchmarks:
  bwaves:     IPC=3.45, Score=34.5
  gamess:     IPC=2.12, Score=21.2
  milc:       IPC=1.89, Score=18.9
  ...

SPECint2006/GHz: 22.5
SPECfp2006/GHz:  28.3
SPEC2006/GHz:    25.1
```

### 6.3 GitHub Actions 输出

**代码位置**：`.github/workflows/perf-template.yml:248-265`

```yaml
- name: Summary result
  if: always()
  run: |
    echo "### :rocket: Performance Test Result" >> $GITHUB_STEP_SUMMARY
    echo '```' >> $GITHUB_STEP_SUMMARY
    echo "$SCORE_FILE" >> $GITHUB_STEP_SUMMARY
    cat "$SCORE_FILE" >> $GITHUB_STEP_SUMMARY
    echo '' >> $GITHUB_STEP_SUMMARY
    echo '```' >> $GITHUB_STEP_SUMMARY

    echo "### :rainbow: Key Indicators" >> $GITHUB_STEP_SUMMARY
    echo "Estimated SPEC CPU2006 Score per GHz: " >> $GITHUB_STEP_SUMMARY
    TOTAL_SCORE=$(grep "SPEC2006/GHz:" "$SCORE_FILE" | awk '{print $NF}')
    INT_SCORE=$(grep "SPECint2006/GHz" "$SCORE_FILE" | awk '{print $(NF-1)}')
    FP_SCORE=$(grep "SPECfp2006/GHz" "$SCORE_FILE" | awk '{print $(NF-1)}')
    echo "- int: **${INT_SCORE}**" >> $GITHUB_STEP_SUMMARY
    echo "- fp: **${FP_SCORE}**" >> $GITHUB_STEP_SUMMARY
    echo "- total: **${TOTAL_SCORE}**" >> $GITHUB_STEP_SUMMARY
```

---

## 7. 计算示例

### 7.1 完整计算流程

**假设**：
- 被测 CPU: XiangShan (3 GHz)
- 参考机: Sun Fire V490 (1.35 GHz)

**步骤 1: 计算每个 benchmark 的 IPC**

```
perlbench: IPC = 1.23
bzip2:     IPC = 2.45
gcc:       IPC = 1.89
...
```

**步骤 2: 计算每个 benchmark 的分数**

```
参考机 IPC (假设):
perlbench: IPC_ref = 0.50
bzip2:     IPC_ref = 0.80
gcc:       IPC_ref = 0.60
...

分数计算:
perlbench: score = 1.23 / 0.50 = 2.46 → 24.6 (×10)
bzip2:     score = 2.45 / 0.80 = 3.06 → 30.6 (×10)
gcc:       score = 1.89 / 0.60 = 3.15 → 31.5 (×10)
...
```

**步骤 3: 计算 SPECint2006**

```
SPECint2006 = geometric_mean(24.6, 30.6, 31.5, ...)
            = (24.6 × 30.6 × 31.5 × ...)^(1/12)
            ≈ 22.5
```

**步骤 4: 计算 GHz 归一化**

```
SPECint2006/GHz = 22.5 / 3 = 7.5
```

### 7.2 Python 实现

```python
import numpy as np

# 参考机 IPC
ref_ipc = {
    'perlbench': 0.50,
    'bzip2': 0.80,
    'gcc': 0.60,
    'mcf': 0.20,
    # ...
}

# 被测机 IPC
test_ipc = {
    'perlbench': 1.23,
    'bzip2': 2.45,
    'gcc': 1.89,
    'mcf': 0.45,
    # ...
}

# 计算每个 benchmark 的分数
scores = {}
for bmk in test_ipc:
    scores[bmk] = test_ipc[bmk] / ref_ipc[bmk] * 10

# 计算几何平均
int_bmks = ['perlbench', 'bzip2', 'gcc', 'mcf', ...]
int_scores = [scores[bmk] for bmk in int_bmks]
spec_int = np.exp(np.mean(np.log(int_scores)))

# GHz 归一化
freq_ghz = 3.0
spec_int_per_ghz = spec_int / freq_ghz

print(f"SPECint2006: {spec_int:.1f}")
print(f"SPECint2006/GHz: {spec_int_per_ghz:.1f}")
```

---

## 8. 常见问题解答

### 8.1 Q: 为什么用几何平均而不是算术平均？

**A**: 几何平均更公平。

**原因**：
- 算术平均对极端值敏感
- 几何平均对极端值不敏感
- SPEC 官方规定使用几何平均

### 8.2 Q: GHz 归一化有什么意义？

**A**: 衡量微架构效率。

**说明**：
- SPEC2006 = IPC × 频率
- SPEC2006/GHz = IPC
- GHz 归一化消除了频率的影响，只看 IPC

### 8.3 Q: 参考机的 IPC 是怎么确定的？

**A**: 由 SPEC 官方测量。

**说明**：
- 参考机是 Sun Fire V490
- SPEC 官方在参考机上运行所有 benchmark
- 测量每个 benchmark 的执行时间
- 作为基准线

### 8.4 Q: 分数越高越好吗？

**A**: 是的。

**说明**：
- 分数表示"比参考机快多少倍"
- 分数 22.5 表示比参考机快 22.5 倍
- 分数越高，性能越好

### 8.5 Q: 如何提高 SPEC 分数？

**A**: 提高 IPC 或频率。

**方法**：
1. 提高 IPC：
   - 增加发射宽度
   - 改善分支预测
   - 减少缓存缺失
   - 优化乱序执行

2. 提高频率：
   - 优化关键路径
   - 增加流水线级数
   - 改善时序设计

---

## 总结

### SPEC 分数计算核心概念

| 概念 | 说明 |
|------|------|
| **IPC** | 每周期指令数，衡量 CPU 效率 |
| **加权平均** | 使用 SimPoint 权重计算平均 IPC |
| **几何平均** | 计算多个 benchmark 的综合分数 |
| **GHz 归一化** | 消除频率影响，只看微架构效率 |

### 嵌入式工程师的理解要点

1. **IPC 类似于"效率"**：每个周期能完成多少工作
2. **加权平均类似于"综合评分"**：考虑不同阶段的重要性
3. **几何平均类似于"公平比较"**：避免极端值的影响
4. **GHz 归一化类似于"性价比"**：看微架构设计的优劣

### 实践建议

1. **先理解 IPC**：这是最基本的性能指标
2. **理解加权平均**：SimPoint 的核心
3. **理解几何平均**：SPEC 的标准
4. **理解 GHz 归一化**：比较微架构效率
