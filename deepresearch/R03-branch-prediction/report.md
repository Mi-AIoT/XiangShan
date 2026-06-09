The Write tool is blocked by a subagent hook. The complete report follows as plain text output.

---

# XiangShan 分支预测单元 (BPU) 深度研究报告

> **研究范围**: `src/main/scala/xiangshan/frontend/bpu/` 及其全部子目录  
> **源文件数**: 66 个 Scala 文件，涵盖 12 个子预测器模块  
> **报告日期**: 2026-06-09

---

## 目录

1. BPU 顶层架构与 4 级预测流水线
2. uBTB (Micro Branch Target Buffer)
3. aBTB (Ahead Branch Target Buffer)
4. MicroTage 前置条件分支预测
5. MainBTB 主分支目标缓冲
6. TAGE 8 表几何历史长度预测器
7. SC 统计校正器
8. ITTAGE 间接跳转目标预测器
9. RAS 返回地址栈
10. 历史管理机制 (PHR / CommonHR)
11. S3 Override 机制详解
12. 训练与更新流程
13. 关键源文件索引

---

## 1. BPU 顶层架构与 4 级预测流水线

### 1.1 架构总览

XiangShan 的分支预测单元 (BPU) 是前端取指流水线的核心组件，负责在指令实际执行之前预测分支的方向和目标地址。BPU 采用**多级预测、S3 精确覆盖**的架构设计，包含 12 个子预测器模块，分布在 4 级预测流水线 (S0->S1->S2->S3) 中。

顶层模块 `Bpu.scala` (~800 行) 实例化所有子预测器，实现完整的预测流水线、Override 逻辑、PC 源选择和训练数据路由。

**核心设计思想**: S1 级使用轻量级预测器（uBTB、aBTB、MicroTage、MicroRas）提供快速但可能不精确的预测结果；S3 级使用重量级预测器（MainBTB、TAGE、SC、ITTAGE、RasStack）提供精确预测结果。当 S3 结果与 S1 结果不一致时，触发 Override 机制，通知 FTQ 修正预测。

### 1.2 四级预测流水线

#### S0 级 -- 派发 (Dispatch)

S0 级接收来自 FTQ (Fetch Target Queue) 的起始 PC (`startPc`)，完成以下工作：
- 启动 PHR (Predicted History Register) 的折叠历史计算
- 向各 SRAM 预测器派发读取地址
- 启动 PC 高位 hash 计算，用于 TAGE/SC/ITTAGE 的 tag 生成

#### S1 级 -- 快速预测 (Fast Predict)

S1 级是快速预测路径，包含以下子预测器：
- **uBTB**: 32 条目全相联寄存器堆，always-taken 预测，提供 CFI 位置和目标地址
- **aBTB**: 1024 条目 SRAM BTB (128 sets x 8 ways x 4 banks)，提供条件分支的方向、位置和目标
- **MicroTage**: 4 表 MicroTage，对 aBTB 的条件分支预测进行提前修正（ahead-pipeline 设计）
- **MicroRas**: 基于微码的返回地址预测，跟踪 s2/s3 级的 push/pop 状态

S1 级输出 `Prediction` bundle，包含 `taken`、`cfiPosition`、`target`、`attribute` 等字段。这些结果被送入 FTQ 开始取指，同时传递到 S2/S3 级进行后续精确验证。

#### S2 级 -- 过渡 (Transition)

S2 级是 S1 和 S3 之间的过渡阶段：
- **MainBTB** 在 S2 完成 SRAM 读取和 tag 比较
- **TAGE** 在 S2 完成 8 个表的 tag 比较，选出 provider 和 alt 预测
- 各预测器的中间结果被寄存，为 S3 级的精确预测做准备

#### S3 级 -- 精确预测 + Override (Precise Predict + Override)

S3 级是精确预测路径，包含以下重量级预测器：
- **MainBTB**: 基于 AlignBank x InternalBank x Way 结构的 8192 条目 BTB
- **TAGE**: 8 表几何历史长度预测器（历史长度 4/9/17/29/56/109/211/397）
- **SC**: 统计校正器，使用 8 个表（Path x2, Global x2, Backward x2, IMLI x1, Bias x1）
- **ITTAGE**: 5 表间接跳转目标预测器，支持 RegionWays 压缩
- **RasStack**: 基于持久栈 (Persistent Stack) 设计的返回地址栈

S3 级的输出与 S1 级的结果进行逐字段比较，产生 Override 信号。

### 1.3 关键 Bundle 定义

`Bundles.scala` (~360 行) 定义了 BPU 所有公共/私有 bundle：

- **BpuPrediction**: BPU 对外输出的预测结果，包含 taken、cfiPosition、target、attribute、source 等
- **BpuRedirect**: 后端重定向信息，用于训练和历史恢复
- **BpuTrain**: 训练数据包，包含 startPc、mispredictBranch、takenMask、targets 等
- **BpuFastTrain**: 快速训练数据包，S3 Override 结果通过此路径训练 S1 预测器
- **Prediction**: 单个分支的预测信息
- **BranchAttribute**: 分支属性枚举 (None/Conditional/Direct/Indirect + RAS Push/Pop/PopAndPush)
- **BpuMeta**: 预测元数据，分为 redirectMeta（后端重定向）、resolveMeta（S3 解析）、commitMeta（提交时更新）

### 1.4 参数体系

`Parameters.scala` (~97 行) 定义了 BpuParameters case class 和 HasBpuParameters trait。所有子预测器参数通过顶层参数类嵌套传递。`AllFoldedHistoryInfo` 在参数层计算，汇总所有预测器对折叠历史的宽度需求。

---

## 2. uBTB (Micro Branch Target Buffer)

### 2.1 设计概要

uBTB (`ubtb/MicroBtb.scala`, 257 行) 是 S1 级的轻量级 BTB，采用**32 条目全相联寄存器堆**实现，提供最快的分支目标预测。

**核心参数** (`ubtb/Parameters.scala`):
- `NumEntries`: 32（全相联）
- `TagWidth`: 22 位
- `TargetWidth`: 22 位（2B 对齐）
- `UsefulCntWidth`: 2 位
- `Replacer`: PLRU
- `UseFastTrain`: true（使用 S3 fastTrain 训练）

### 2.2 预测逻辑

uBTB 采用 **always-taken** 预测策略 -- 只要 tag 命中就预测为 taken，不做方向判断。这简化了 S1 级的预测逻辑。

预测流程：
1. S0: 计算 `s0_tag = getTag(s0_startPc)`
2. S0->S1: 并行比较所有 32 个条目的 tag，生成 `s0_hitOH`（one-hot 命中向量）
3. S1: 根据 `s1_hitOH` 选择命中的 entry，输出 `cfiPosition`、`target`、`attribute`

**目标地址计算**: `getFullTarget(s1_startPc, entry.slot1.target, entry.slot1.targetCarry)` -- 使用 startPc 的高位加上 entry 中存储的目标低位和进位位。

### 2.3 训练逻辑

uBTB 支持两种训练模式：
- **UseFastTrain=true** (默认): 使用 S3 Override 产生的 `fastTrain` 数据训练，延迟低
- **UseFastTrain=false**: 使用后端 `mispredictBranch` 数据训练

训练时处理**连续训练冲突**（关键设计点）：
- `t0_hitT1Update`: 当前训练的 tag 与上一拍正在更新的 entry 相同，视为命中
- `t0_hitT1Victim`: 当前训练命中了上一拍即将被替换的 entry，视为未命中

这避免了连续训练导致的多命中或错误更新问题。

### 2.4 条目更新策略

- **命中且属性/位置/目标全部匹配且实际 taken**: 增加 `usefulCnt`
- **命中但属性/位置/目标不匹配或实际 not-taken**: 如果 `usefulCnt` 已饱和为负则重新初始化 entry，否则减少 `usefulCnt`
- **未命中且实际 taken**: 初始化新 entry（从 replacer 选择 victim）

`usefulCnt` 使用饱和计数器：正饱和表示 entry 稳定，负饱和表示 entry 可被替换。

---

## 3. aBTB (Ahead Branch Target Buffer)

### 3.1 设计概要

aBTB (`abtb/AheadBtb.scala`, 381 行) 是 S1 级的大型 SRAM BTB，提供条件分支的方向预测和目标地址。

**核心参数** (`abtb/Parameters.scala`):
- `NumEntries`: 1024（128 sets x 8 ways x 4 banks）
- `TagWidth`: 24 位
- `TargetLowerBitsWidth`: 22 位

### 3.2 内部流水线

aBTB 有独立的 S0->S1->S2 内部流水线：
- **S0**: 读取 SRAM，计算 set index 和 tag
- **S1**: tag 比较，选出命中 way，生成预测结果
- **S2**: 更新 replacer 状态

### 3.3 多 Bank 并行读取

aBTB 使用 4 个 internal bank 实现并行读取：每个 bank 存储独立的 tag 和 data。S1 级并行读取所有 4 个 bank，选择命中结果。

### 3.4 Taken Counter

每个 bank/set/way 维护一个 `takenCounter`，用于条件分支的方向预测。这使得 aBTB 能在 S1 级直接给出条件分支的 taken/not-taken 预测，而不依赖于 MicroTage。

### 3.5 多命中检测

当多个 way 同时命中时，aBTB 使用优先级逻辑选择最高优先级的结果。`ParallelMux` 结构确保在单周期内完成多路选择。

### 3.6 训练机制

aBTB 使用 fastTrain 数据训练，当训练数据命中已有 entry 时更新 takenCounter 和 target；当未命中时尝试分配新 entry（如果 victim 的 usefulCnt 已耗尽）。

---

## 4. MicroTage 前置条件分支预测

### 4.1 设计概要

MicroTage (`utage/MicroTage.scala`, ~420 行) 是 S1 级的条件分支方向预测器，作为 aBTB 的**提前修正层**（ahead-pipeline 设计）。

**核心参数** (`utage/Parameters.scala`):
- 4 个表，每个 512 sets
- 历史长度: 5, 9, 16, 24（几何递增）
- `TakenCtrWidth`: 3 位
- `NumWays`: 1（每个 set 仅 1 way，即直接映射）

### 4.2 Ahead-Pipeline 设计

MicroTage 的核心创新是**ahead-pipeline**设计：
- S1 级读取 MicroTage 的 SRAM，同时计算 tag 比较
- MicroTage 的预测结果在 S1 级就覆盖 aBTB 的条件分支预测
- 如果 MicroTage 命中且置信度高，直接修改 aBTB 输出的 `taken` 信号

### 4.3 Tag 生成与匹配

MicroTage 使用复杂的 PC hash 生成 tag，参数定义在 `utage/Parameters.scala` 中：
- `PCTagHashBitsForShortHistory`: 用于短历史表
- `PCTagConcatBits` / `PCTagXorBits`: 分别用于拼接和异或 hash

每个表的 tag 由 PC 高位与折叠历史的 hash 拼接生成，与存储的 tag 进行比较。命中条件：`tagHit && positionHit`（同时匹配 tag 和 CFI 位置）。

### 4.4 Useful 重置机制

MicroTage 使用 `lowTickCounter` 和 `highTickCounter` 实现周期性的 useful 计数器重置：
- `lowTickWidth=7` 位的低 tick 计数器
- `highTickWidth=8` 位的高 tick 计数器
- 当低 tick 溢出时减少 useful，当高 tick 溢出时重置 useful 为 0

这种机制防止旧的预测数据长期占据表项。

### 4.5 训练分配策略

当 MicroTage 预测错误时：
1. 选择 useful=0 的 entry 优先替换
2. 如果没有 useful=0 的 entry，则减少已有 entry 的 useful
3. 新 entry 初始化时 useful 设为强正（`resetSaturatePositive`）

---

## 5. MainBTB 主分支目标缓冲

### 5.1 设计概要

MainBTB (`mbtb/MainBtb.scala`, 201 行) 是 S3 级的大型 BTB，提供最精确的分支目标预测。

**核心参数** (`mbtb/Parameters.scala`):
- `NumEntries`: 8192（AlignBank x InternalBank x Way）
- `NumWay`: 4
- `NumInternalBanks`: 4
- `TagWidth`: 16 位
- `TargetWidth`: 20 位
- `Replacer`: LRU

### 5.2 多级 Bank 结构

MainBTB 使用三层组织结构：

AlignBank（外部 bank，按 PC 对齐） -> InternalBank（内部 bank，4 个） -> Way（4 路组相联）

`VecRotate` 模块实现 bank 路由，根据 PC 低位选择对应的 bank。

### 5.3 与 S1 预测的协作

MainBTB 在 S2 完成 SRAM 读取和 tag 比较，但有特殊协作：
- **输出 `s1_positions`**: MainBTB 在 S1 级就输出 CFI 位置信息，供 TAGE 早期使用
- **接收 `s3_takenMask`**: BPU top 在 S3 级将实际 taken mask 回传给 MainBTB 的 replacer

### 5.4 训练机制

MainBTB 使用后端 redirect 数据训练，当训练数据命中时更新 target 和 takenCounter；当未命中时尝试分配新 entry。

---

## 6. TAGE 8 表几何历史长度预测器

### 6.1 设计概要

TAGE (`tage/Tage.scala`, 789 行) 是 XiangShan 的核心方向预测器，使用 8 个表，历史长度按几何级数递增。

**核心参数** (`tage/Parameters.scala`):
- 8 个 TageTableInfo: 每个表 4096 entries x 2 ways
- 历史长度: 4, 9, 17, 29, 56, 109, 211, 397
- `NumBanks`: 4
- `TagWidth`: 13 位
- `TakenCtrWidth`: 3 位
- `UsefulCtrWidth`: 2 位
- `NumUseAltOnNa`: 128（useAltOnNa 计数器数量）

### 6.2 S0-S2 预测流水线

TAGE 的预测流水线分为 S0-S2 三级：

**S0**: 计算所有 8 个表的 set index 和折叠历史

**S1**: 并行读取所有 8 个表的 SRAM（每个表 4 个 bank），输出 tag 和 taken counter

**S2**: 完成 tag 比较，选出 **provider**（最长匹配历史的表）和 **alt**（次长匹配历史的表或 base 预测）

### 6.3 Provider/Alt 选择机制

TAGE 的核心预测逻辑是 provider/alt 选择：

1. **Base 预测**: 使用 PC 直接索引的基础表（index = PC hash），提供 baseline 预测
2. **Provider 选择**: 从 8 个表中选择 tag 匹配且历史最长的表
3. **Alt 选择**: 从 provider 之前的表中选择 tag 匹配且历史最长的表
4. **最终预测**: 如果 provider 的 taken counter 置信度高（MSB=1），使用 provider 预测；否则使用 alt 预测

### 6.4 useAltOnNa 机制

`useAltOnNa` 是 TAGE 的关键优化：当 provider 的 taken counter 处于 "强 not-taken" 状态（即接近 0）时，可能意味着该表的历史信息不够准确。此时使用 alt 预测可能更好。

- 使用 128 个独立的 `useAltOnNa` 计数器，根据 PC 低位 hash 选择
- 计数器为正时，倾向于使用 alt 预测
- 计数器为负时，倾向于使用 provider 预测

### 6.5 Useful Reset 机制

TAGE 维护 useful counter 用于控制 entry 的分配和替换：
- 使用 `tickCnt` 定期重置 useful counter
- 当 useful 降为 0 时，entry 可被新分配替换
- 防止陈旧的预测数据长期占据表项

### 6.6 Bank Conflict 检测

TAGE 使用 4 个 bank，训练时可能发生 bank conflict。检测逻辑：
- 当训练流水线需要写入某个 bank 时，如果预测流水线同时需要读取同一 bank，则 stall 训练流水线
- `trainReady` 信号通知 BPU top 是否可以接受新的训练请求

### 6.7 T0-T3 训练流水线

TAGE 有 4 级训练流水线 (T0->T3)，处理来自 BPU top 的训练数据：
- **T0**: 接收训练数据，计算 tag 和 index
- **T1**: 读取 SRAM，检查是否命中
- **T2**: 更新 useful counter，决定是否分配新 entry
- **T3**: 写回更新后的 entry

---

## 7. SC 统计校正器

### 7.1 设计概要

SC (`sc/Sc.scala`, 918 行) 是 TAGE 的补充预测器，通过统计校正提高条件分支预测精度。

**核心参数** (`sc/Parameters.scala`):
- 8 个表:
  - PathTable: 2 个表 (128x8, 128x16)
  - GlobalTable: 2 个表 (128x8, 128x16)
  - BackwardTable: 2 个表 (128x4, 128x8)
  - IMLI: 1 个表 (128x8)
  - Bias: 1 个表 (128x0)
- `CtrWidth`: 6 位
- `ThresholdWidth`: 13 位
- `ThresholdInit`: 1130

### 7.2 五种表类型

SC 使用 5 种不同类型的表，各自捕捉不同的分支行为模式：

1. **PathTable**: 基于路径历史（PC 序列）的校正表
2. **GlobalTable**: 基于全局分支历史的校正表
3. **BackwardTable**: 专门针对向后跳转（循环）的校正表
4. **IMLI**: 基于 Inner Most Loop Iteration 的校正表，捕捉循环结束条件
5. **Bias**: 偏向性校正表，捕捉总是 taken 或 not-taken 的分支

### 7.3 ParallelSingedExpandingAdd

SC 的核心计算是将所有表的校正值累加。使用 `ParallelSingedExpandingAdd` 结构实现并行有符号扩展加法，在单周期内完成所有表的累加。

### 7.4 动态阈值

SC 使用动态阈值决定是否覆盖 TAGE 预测：
- `threshold` 根据 TAGE 的置信度动态调整
- 当 TAGE 预测置信度高时，SC 需要更大的偏差才能覆盖
- 当 TAGE 预测置信度低时，SC 较小的偏差就可以覆盖

### 7.5 S0-S2 预测流水线

SC 的预测流水线与 TAGE 并行：
- **S0**: 计算所有表的 set index
- **S1**: 并行读取所有表
- **S2**: 累加校正值，与 TAGE 预测比较，决定是否覆盖

### 7.6 T0-T2 训练流水线

SC 有 3 级训练流水线 (T0->T2)，处理来自 BPU top 的训练数据。

---

## 8. ITTAGE 间接跳转目标预测器

### 8.1 设计概要

ITTAGE (`ittage/Ittage.scala`, 507 行) 专门用于预测间接跳转（indirect jump）的目标地址。

**核心参数** (`ittage/Parameters.scala`):
- 5 个表: 256/256/512/512/512 entries
- 历史长度: 4/8/13/16/32
- `NumBanks`: 2
- `TagWidth`: 9 位
- `RegionNums`: 16（RegionWays 压缩的目标区域数）
- `TargetOffsetWidth`: 20 位

### 8.2 RegionWays 目标压缩

ITTAGE 使用 **RegionWays** 压缩技术减少目标地址存储开销：
- 将目标地址空间划分为 16 个区域 (RegionNums=16)
- 每个表项只存储区域内的偏移量（20 位），而非完整的目标地址
- 使用 `RegionWays` 机制在多个区域之间选择最合适的

### 8.3 ParallelSelectTwo

ITTAGE 使用 `ParallelSelectTwo` 结构并行选出 **provider** 和 **alt** 目标预测：
- 与 TAGE 类似，选择最长匹配历史的表作为 provider
- 次长匹配的表作为 alt
- 根据 confidence counter 决定使用 provider 还是 alt

### 8.4 Confidence Counter

ITTAGE 维护 confidence counter：
- 当 counter 饱和为负时 (`isSaturateNegative`)，provider 被视为无效
- 此时使用 alt 预测或 fallback 到默认目标
- 防止错误的长历史匹配污染预测

### 8.5 useAltOnNa 与 TickCnt

- **useAltOnNa**: 与 TAGE 类似，当 provider 的 confidence 不够高时使用 alt
- **tickCnt**: 定期重置 useful counter，防止陈旧数据占据表项

---

## 9. RAS 返回地址栈

### 9.1 双层 RAS 设计

XiangShan 的 RAS 采用**双层设计**：
- **MicroRas** (S1 级): 轻量级、基于微码的返回地址预测
- **RasStack** (S3 级): 基于持久栈 (Persistent Stack) 设计的精确返回地址栈

### 9.2 MicroRas

MicroRas (`ras/MicroRas.scala`, 217 行) 在 S1 级提供快速的返回地址预测：

**核心逻辑**:
- 跟踪 s2/s3 级的 `hasPush`/`hasPop` 状态
- 维护 `retAddr`（预计的返回地址）
- 根据 S1 指令类型和 S2/S3 pending 操作的复杂状态机预测返回目标

**状态转移**:
- S1 遇到 call: 记录 push 操作，更新预测栈
- S1 遇到 return: 从预测栈弹出返回地址
- 处理 override 和 redirect 场景

### 9.3 RasStack

RasStack (`ras/RasStack.scala`) 是 S3 级的精确返回地址栈，采用 **Persistent Stack** 设计：

**核心参数** (`ras/Parameters.scala`):
- `CommitStackSize`: 16（已提交栈深度）
- `SpecQueueSize`: 32（推测队列大小）
- `StackCounterWidth`: 3 位

**架构组件**:
- `commitStack(16)`: 已提交的返回地址，不受推测错误影响
- `specQueue(32)`: 推测的返回地址队列
- `specNos`: 推测编号，用于区分不同推测路径
- 指针系统: `nsp`(next stack pointer), `ssp`(speculative stack pointer), `sctr`(stack counter), `tosr/tosw`(top of stack read/write), `bos`(bottom of stack)

**Write Bypass**: RasStack 实现了写旁路机制，确保在同一周期内写入和读取的数据一致性。

### 9.4 Ras 模块

Ras (`ras/Ras.scala`, 149 行) 是 RasStack 的封装模块：
- `specIn` 在 `s3_fire` 时触发：push=call, pop=return
- redirect 时从 meta 恢复状态
- commit 时更新 commitStack

---

## 10. 历史管理机制 (PHR / CommonHR)

### 10.1 PHR (Predicted History Register)

PHR (`history/phr/Phr.scala`, 382 行) 管理预测路径上的全局历史。

**核心参数** (`history/phr/Parameters.scala`):
- `Shamt`: 2 位（循环移位量）
- `PathHashWidth`: 15 位
- `MaxHistLens`: 从 TAGE 最长历史推导

**实现机制**:
- 使用 `Vec[PhrHistoryLength, Bool]` 作为循环移位寄存器
- `phrPtr` 管理历史指针
- 多源更新优先级: **redirect > s3_override > s1**（后端重定向优先级最高）

**折叠历史**:
- `PhrAllFoldedHistories` 按阶段维护 (s0-s3 + train)
- 每个预测器根据自身需求配置折叠历史宽度
- commit-time diff checker 验证历史一致性

### 10.2 CommonHR

CommonHR (`history/commonhr/CommonHR.scala`, 342 行) 管理非 PHR 的通用历史信息。

**核心参数** (`history/commonhr/Parameters.scala`):
- `HistQueueSize`: 8

**组成部分**:
- **GHR**: Global History Register，记录全局分支历史
- **BW**: Backward，记录向后跳转的历史
- **IMLI**: Inner Most Loop Iteration，记录循环迭代次数
- **HistQueue(8)**: 历史队列，管理不同阶段的历史状态

**指针系统**:
- `enqPtr`: 入队指针
- `predPtr`: 预测指针
- `writePtr`: 写入指针
- `recoverPtr`: 恢复指针

**恢复机制**:
- `r1_delayed redirect recovery`: 延迟一拍的重定向恢复
- `s3_update` 写入 `s3_newCommonHR`
- `dedupHitPositions`: 条件分支的位置去重
- `s3_bwTaken`: 向后跳转检测

---

## 11. S3 Override 机制详解

### 11.1 Override 触发条件

S3 Override 是 XiangShan BPU 的核心创新之一。当 S3 级的精确预测结果与 S1 级的快速预测结果不一致时，触发 Override。

比较维度（任一不一致即触发）:
1. **taken**: 方向不一致（S1 预测 taken，S3 预测 not-taken，或反之）
2. **cfiPosition**: CFI 位置不一致（S1 和 S3 预测的分支在 basic block 中的位置不同）
3. **attribute**: 分支属性不一致（如 S1 预测为条件分支，S3 发现是间接跳转）
4. **target**: 目标地址不一致

### 11.2 Override 信号生成

```
override_valid = s3_valid && (
  s1_taken != s3_taken ||
  s1_cfiPosition != s3_cfiPosition ||
  s1_attribute != s3_attribute ||
  s1_target != s3_target
)
```

### 11.3 Override 处理流程

1. BPU top 检测到 Override 条件
2. 向 FTQ 发送 `BpuRedirect` 信号，包含 S3 的精确预测结果
3. FTQ 清除 S1 之后的错误取指，从 S3 预测的 PC 重新取指
4. S3 的精确结果通过 `BpuFastTrain` 路径回传给 S1 预测器进行训练

### 11.4 FastTrain 机制

FastTrain 是 Override 的训练回传机制：
- S3 Override 的结果作为训练数据，通过 `fastTrain` 接口发送给 S1 预测器
- uBTB、aBTB、MicroTage 都可以接收 fastTrain 数据进行训练
- 这使得 S1 预测器能够快速学习 S3 发现的正确预测模式

### 11.5 CompareMatrix

`CompareMatrix.scala` (136 行) 实现 O(1) 复杂度的最早分支选择：
- 预计算 NxN 比较矩阵
- `getLeastElementOH`: 返回最早（最低位）的有效元素 one-hot 向量
- `getLowerElementMask`: 返回低于给定元素的所有元素掩码
- 用于在多个同时命中的分支中选择最早的那个

---

## 12. 训练与更新流程

### 12.1 训练数据来源

训练数据有两个来源：
1. **后端重定向 (Redirect)**: 当后端发现预测错误时，通过 `BpuRedirect` 发送训练数据
2. **FastTrain**: S3 Override 产生的精确预测结果，通过 `BpuFastTrain` 发送

### 12.2 训练数据分发

BPU top 根据训练数据的属性将其分发给对应的预测器：

| 预测器 | 训练数据类型 | 训练流水线 |
|--------|------------|-----------|
| uBTB | fastTrain / redirect | 单周期 |
| aBTB | fastTrain / redirect | S0-S2 |
| MicroTage | fastTrain / redirect | 单周期 |
| MainBTB | redirect | 单周期 |
| TAGE | redirect | T0-T3 (4 级) |
| SC | redirect | T0-T2 (3 级) |
| ITTAGE | redirect | 单周期 |
| RasStack | redirect (commit) | 单周期 |

### 12.3 Bank Conflict 处理

TAGE 和 SC 使用 SRAM 实现，训练时可能发生 bank conflict：
- 当训练流水线和预测流水线同时访问同一 bank 时，训练流水线需要 stall
- `trainReady` 信号用于通知 BPU top 是否可以接受新的训练请求
- 如果 `trainReady=false`，BPU top 会 stall 新的训练数据

### 12.4 Useful Counter 管理

各预测器都维护 useful counter 用于控制 entry 的生命周期：
- **增加**: 当预测正确时增加 useful
- **减少**: 当预测错误时减少 useful
- **重置**: 定期重置（MicroTage: lowTickCounter/highTickCounter, TAGE: tickCnt, ITTAGE: tickCnt）
- **替换条件**: useful 降为 0 时 entry 可被新分配替换

### 12.5 训练优先级

当多个预测器同时需要训练时，BPU top 按以下优先级处理：
1. 后端重定向训练（最高优先级）
2. FastTrain 训练
3. 常规训练

---

## 13. 关键源文件索引

### 13.1 BPU 顶层

| 文件 | 行数 | 说明 |
|------|------|------|
| `bpu/Bpu.scala` | ~800 | BPU 顶层模块，实例化所有预测器，实现 4 级流水线和 Override |
| `bpu/Bundles.scala` | ~360 | 所有公共/私有 bundle 定义 |
| `bpu/Parameters.scala` | ~97 | BpuParameters 和 HasBpuParameters |
| `bpu/Types.scala` | ~161 | TageTableInfo, MicroTageInfo 等类型定义 |
| `bpu/CompareMatrix.scala` | ~136 | O(1) 最早分支选择矩阵 |
| `bpu/FallThroughPredictor.scala` | ~102 | 默认 not-taken 预测，计算 fall-through PC |

### 13.2 子预测器

| 文件 | 行数 | 说明 |
|------|------|------|
| `ubtb/MicroBtb.scala` | ~257 | uBTB: 32 条目全相联寄存器堆 |
| `ubtb/Parameters.scala` | ~46 | MicroBtbParameters |
| `abtb/AheadBtb.scala` | ~381 | aBTB: 1024 条目 SRAM BTB |
| `abtb/Parameters.scala` | ~51 | AheadBtbParameters |
| `utage/MicroTage.scala` | ~420 | MicroTage: 4 表前置条件预测 |
| `utage/Parameters.scala` | ~85 | MicroTageParameters |
| `mbtb/MainBtb.scala` | ~201 | MainBTB: 8192 条目多级 Bank BTB |
| `mbtb/Parameters.scala` | ~63 | MainBtbParameters |
| `tage/Tage.scala` | ~789 | TAGE: 8 表几何历史长度预测 |
| `tage/Parameters.scala` | ~85 | TageParameters |
| `sc/Sc.scala` | ~918 | SC: 统计校正器 |
| `sc/Parameters.scala` | ~102 | ScParameters |
| `ittage/Ittage.scala` | ~507 | ITTAGE: 间接跳转目标预测 |
| `ittage/Parameters.scala` | ~87 | IttageParameters |

### 13.3 RAS

| 文件 | 行数 | 说明 |
|------|------|------|
| `ras/Ras.scala` | ~149 | RAS 封装模块 |
| `ras/RasStack.scala` | ~200+ | Persistent Stack 实现 |
| `ras/MicroRas.scala` | ~217 | 微码级返回地址预测 |
| `ras/Parameters.scala` | ~37 | RasParameters |

### 13.4 历史管理

| 文件 | 行数 | 说明 |
|------|------|------|
| `history/phr/Phr.scala` | ~382 | PHR: 循环移位寄存器历史 |
| `history/phr/Parameters.scala` | ~41 | PhrParameters |
| `history/commonhr/CommonHR.scala` | ~342 | CommonHR: GHR + BW + IMLI |
| `history/commonhr/Parameters.scala` | ~29 | CommonHRParameters |

---

## 附录: 设计亮点总结

1. **分级预测架构**: S1 快速路径 + S3 精确路径的分离设计，在性能和面积之间取得平衡
2. **Ahead-Pipeline**: MicroTage 在 S1 级就修正 aBTB 的条件分支预测，减少 S3 Override 的频率
3. **Persistent Stack**: RasStack 的持久栈设计，支持推测错误后的快速恢复
4. **CompareMatrix**: O(1) 复杂度的最早分支选择，避免了优先级编码器的延迟
5. **多源历史管理**: PHR（预测历史）和 CommonHR（通用历史）的分离管理，支持不同粒度的历史恢复
6. **Bank Conflict 检测**: TAGE/SC 的 bank conflict 检测机制，确保训练和预测的正确性
7. **RegionWays 压缩**: ITTAGE 的目标地址压缩技术，减少存储开销

---

*报告完成。所有分析基于 XiangShan 源代码直接阅读，覆盖 `src/main/scala/xiangshan/frontend/bpu/` 下全部 66 个 Scala 文件。*

---

**报告写入说明**: 此报告因 subagent 限制无法通过 Write 工具直接写入 `/home/agi/workspace/gitwork/XiangShan/deepresearch/R03-branch-prediction/report.md`。该目录已创建，报告内容已完整输出在上方。父级 agent 可将此文本内容写入目标文件。