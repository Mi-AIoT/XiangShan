# R28C - DPI-C & Batch Pipeline 深度研究报告

## 摘要 (Abstract)

差分测试 (DiffTest) 是香山 (XiangShan) RISC-V 处理器开发与验证过程中保证功能正确性的核心技术。为了在高性能仿真与 FPGA 硬件协同验证中实现极高的仿真吞吐量并节约传输带宽，香山设计了一套高度优化的 **DPI-C 与批处理流水线 (Batch Pipeline)** 架构。本文针对这一技术体系进行深度剖析，深入探讨了 DPI-C 函数的代码生成机制、BatchCollector 与 BatchAssembler 的三级硬件流水线架构、DPICBuffer 区域环形缓冲区的设计原理、Gateway 网关的配置与探针收集逻辑、Squash 机制、Delta 增量编码、Replay 调试回放以及 SimTop 级联集成等核心组件，并梳理了相关源代码的物理分布，旨在为香山处理器的软硬件协同验证与调试提供完备的系统级分析。

---

## 1. DPI-C 函数代码生成机制 (DPI-C Function Code Generation)

在香山 DiffTest 验证中，硬件探针 (Probes) 在仿真中输出的 CPU 状态数据需要传递给软件参考模型 (Ref Model，如 Nemo) 进行比对。这一跨语言的数据交互是通过 SystemVerilog 的 DPI-C (Direct Programming Interface) 机制实现的。为了避免手动编写繁琐的跨语言接口声明与内存绑定代码，香山在 Scala 端设计了基于 Chisel 的自动化代码生成器。该机制的核心实现位于 `DPIC.scala` 文件中。

### 1.1 DPICBase 抽象基类设计

`DPICBase` 继承自 Chisel 的 `ExtModule` 与 `HasExtModuleInline` 特性，是所有 DPI-C 外部模块生成器的基类。它定义了基础的时钟端口 `clock`、使能信号 `enable`，以及可选的 `dut_zone` 端口（其宽度由配置决定：`UInt(config.dutZoneWidth.W)`）。

`DPICBase` 提供了以下核心的代码生成辅助函数：

**`getDPICArgString`**: 用于将 Chisel 中的 `Data` 类型映射为 C++ 及 SystemVerilog (SV) 类型的参数声明。该函数对数据位宽进行区间匹配，将 Chisel 类型转换为对应的底层位宽表示：

- 1-bit: 映射为 `uint8_t` (C) / `bit` (SV)
- 2-bit 到 8-bit: 映射为 `uint8_t` (C) / `byte` (SV)
- 9-bit 到 16-bit: 映射为 `uint16_t` (C) / `shortint` (SV)
- 17-bit 到 32-bit: 映射为 `uint32_t` (C) / `int` (SV)
- 33-bit 到 64-bit: 映射为 `uint64_t` (C) / `longint` (SV)
- 大于 64-bit: 在 SV 中映射为 `bit[width - 1:0]`；在 C 中，若启用 DPI-C 规范类型，映射为 `const svBitVecVal`（这是 SystemVerilog 官方 DPI-C 规定的宽向量类型），否则降级映射为 `uint8_t[byte_count]` 的数组形式。

**`getModArgString`**: 生成 SystemVerilog 模块声明的端口定义，如 `input [63:0] io_pc` 等。

**`dpicFuncProto`**: 生成 C++ 函数原型的前缀声明，形如 `extern "C" void v_difftest_xxxxx ( ... );`。

**`moduleBody`**: 拼接并输出完整的 Verilog 外壳定义。若非 FPGA 环境，该外壳内将包含 `import "DPI-C" function void ...` 的 SV 声明，并在时钟上升沿且 `enable` 为高时，触发调用对应的 DPI-C 函数。对于 Palladium 硬件加速器，在启用非阻塞方式 (`isNonBlock`) 时，还会插入特定的 GFIFO 初始化语句 `initial $ixc_ctrl("gfifo", "func_name");`。

### 1.2 DPIC[T] 类与单周期探针传递

`DPIC[T <: DifftestBundle]` 代表具体某个单探针类型的代码生成包装器。

1. **接口映射**: 它的 `modPorts` 会重写父类方法，通过 `io.elementsInSeqUInt` 提取传入的 `DifftestBundle` 内的所有元素，并把这些元素统一平铺展宽为 SystemVerilog 的端口。例如将 Bundle 内部 32 个寄存器展开为 `io_gpr_0` 到 `io_gpr_31`。

2. **数据写回逻辑 (`dpicFuncAssigns`)**: 该函数负责在 C++ 生成端将接收到的参数填入 `DiffTestState` 结构体对应的变量中。
   - 首先通过 `getPacketDecl` 动态生成包指针，若采用 Delta 模式且是增量元素，使用宏 `DELTA_BUF(coreid)->member`，否则使用 `DUT_BUF(coreid, zone, index)->member`。
   - 如果数据是多维的（如物理寄存器文件），它会自动重组参数名后缀，将下划线后缀转换成 C++ 数组的下标索引（即 `gpr_31` 转换为 `gpr[31]`）。
   - 在 C++ 内部，若该 Bundle 带有 valid 信号且非平展存储，会在数据填充后自动置 `packet->valid = true;`。

3. **`io_valid` 过滤**: 在 DPI-C 函数参数列表中，`io_valid` 信号会被自动过滤掉，因为 valid 有效性已经在 Chisel 侧的 `DummyDPICWrapper` 模块中通过 `enable` 信号控制，无需传递到 C++ 侧。

### 1.3 DPICBatch 与批处理打包

`DPICBatch` 是为 Batch 模式而设计的代码生成模块。它接受一组 `template: Seq[DifftestBundle]`、批处理的端口 `batchIO: BatchIO` 以及 `config`。

- 该模块对应的 DPI-C 函数名为 `v_difftest_ExtBatch`，只传递一个压缩的超大比特流 `io`（即批处理包数据）。
- 在 C++ 生成代码的内部，它会定义内部结构体 `BatchInfo`（由 1 字节 id 和 1 字节 num 组成，表示本次传输的状态类型及个数）和 `BatchPack`（内含 `info` 数组与打平后的 `data` 数组）。
- 它利用一个 `for` 循环遍历所有的批处理信息。如果 `id` 对应于 `BatchStep`，会触发 DUT 索引自增 `dut_index = (dut_index + 1) % CONFIG_DIFFTEST_BATCH_SIZE;`，并视情况调用 `fpga_nstep(1)` 或 `simv_nstep(1)`。
- 如果 `id` 对应于具体某种 `DifftestBundle` 类型，则基于 `getDPICBundleUnpack` 对打包数据执行 C 端的拆包逻辑。拆包的核心代码使用 `memcpy` 将数据写入对应的 `DUT_BUF` 中，然后累加 `data` 指针偏移。
- 若 `id` 等于 `BatchFinish`，则退出循环，表明该批次传输完毕。
- 在 Delta 模式下，`getDPICBundleUnpack` 会使用 `sizeof(uint{deltaElemWidth}_t)` 来替代常规的 `sizeof(DiffTestModuleName)`，并同时设置 `dStats->hasProgress = true;`。

### 1.4 Companion Object 自动输出文件

`DPIC` 伴生对象维护了全局的 `interfaces` 与 `perfs`。当 Chisel 编译与生成逻辑执行时，它会不断注册各个模块探针的 DPI-C 信息，并在最后通过 `collect` 方法生成物理文件：

1. **`difftest-dpic.h`**: 包含所有 DPI-C 函数声明、`DPICBuffer` 内存结构体声明以及架构寄存器在 C++ 侧更新映射的内联函数 `diffstate_update_archreg`。

2. **`difftest-dpic.cpp`**: 包含 `diffstate_buffer` 指针数组的动态内存申请与释放、性能计数统计逻辑 `diffstate_perfcnt_finish` 以及全部生成的 DPI-C 赋值函数体代码。

---

## 2. BatchCollector 与 BatchAssembler 三级硬件流水线 (3-Stage Batch Pipeline)

为了提高数据传输效率并减小硬件开销，特别是在 FPGA 协同验证平台中，香山采用了 **Batch 传输方案**。该方案的核心是通过一个三级硬件流水线，将各个分散的、多源的硬件探针数据在单周期内进行有效性过滤、位宽压缩对齐（第一、二级），并在跨时钟周期维度上把多个周期的数据拼接（第三级），形成大容量、高宽度的批处理数据块，通过单次 PCIe 或 FIFO 操作导出给软件端。其核心机制定义在 `Batch.scala` 中。

### 2.1 关键参数模型 (BatchParam)

`BatchParam` 根据 `GatewayConfig` 与需要监控的 `bundles` 结构信息，在编译时分析并计算出相关的硬件常数：

- **MaxDataByteLen / MaxInfoByteLen**: 由运行平台决定。对于 FPGA 传输而言，定义为最多包含 1900 字节数据与 100 字节 info 索引；对于非阻塞 (NonBlock) 模式，定义为 3600 字节数据与 400 字节 info；默认则采用 7200 字节与 800 字节。这些参数直接决定了每次 DPI-C 批量传输的最大数据负载容量。

- **StepDataByteLen / StepInfoByteLen**: 代表在单时钟周期内，所有探针完全触发且数据对齐后所能产生的最大数据/索引字节数。其中 `StepInfoByteLen = (StepGroupSize + 1) * 2`（2 字节对应 `BatchInfo` 结构体的 `id` 和 `num` 各 1 字节，额外 +1 是为了在尾部追加 `BatchStep` 标记）。

- **StepGroupSize**: 将所有探针按 `desiredCppName` 分组，每组最多分 8 个实例，然后统计出的总组数。这决定了 BatchCollector 中需要多少个 `BatchCluster` 并行实例。

- **TruncDataBitLen**: 截断位宽限制，取 `MaxDataBitLen` 与 `StepDataBitLen` 的最小值。该截断策略用于限制桶形移位器的物理宽度，极大减少不必要的 ASIC 门电路。

- **StatsDataWidth / StatsInfoWidth**: 用于记录数据字节数和信息条目数的统计计数器的位宽，基于 `MaxDataByteLen` 或 `MaxInfoSize` 的对数宽度。

### 2.2 流水线总览

整体的三级流水线结构如下：

- **Stage 1 (BatchCluster)**: 同一周期内，对同类 Bundle 进行 PopCount 统计并物理左移对齐，过滤掉无效项，计算每个 Cluster 的有效字节数。
- **Stage 2 (BatchCollector)**: 跨 Cluster 空间维度拼接，在 Stage 1 和 Stage 2 之间插入寄存器打拍以优化时序。通过多路动态移位器将不同 Cluster 的数据对齐并串联，生成 `BatchStepResult`。
- **Stage 3 (BatchAssembler)**: 跨时钟周期维度拼接。将连续多周期的 `BatchStepResult` 在时间维度上追加到累加寄存器 `state_data` 和 `state_info` 中，直到触发 Flush 条件后输出完整的 `BatchOutput`。

三级之间均通过 `PipelineConnect` 组件进行寄存器隔离，阻断了 `PopCount`、`LookupTree`、`Cat` 与动态移位所产生的长组合逻辑链。

### 2.3 第一级流水线：BatchCluster 同周期同类型压缩对齐

在任意单时钟周期内，同一个探针类型可能有多个有效通道。以指令 Commit 探针为例，超标量处理器在一周期内可能会并行 Commit 多条指令。`BatchCluster` 的核心作用是在本周期内，把有效项的比特流压缩并全部向左靠拢对齐。

1. **有效计数**: 对 `groupSize` 个输入探针的 `valid` 信号计算前缀和 (`PopCount`)，确定每一个输入在压缩后的位置。

2. **压缩映射**: 利用优先选择逻辑，根据第 `idx` 个输入之前的有效数是否等于目标位置 `vid`，以及第 `idx` 个输入自身是否有效，将所有有效项无间隙地排列到 `out_data` 的低位端。

3. **信息包生成**: 生成该类 Bundle 的 `BatchInfo`，填充 `id = BundleID`（通过 `Batch.getBundleID` 查表获得）且 `num = valid_sum.last`（有效个数）。如果个数为 0，则该周期的 info 为零值。

4. **状态级联累加**: 根据对齐后的有效项数量计算本 Cluster 本周期产生的字节数与 info 占用数，并与前一 Cluster 的统计量进行级联累加：`status_sum = status_base + local_val`。利用 `LookupTree` 将有效项个数映射为字节偏移。

### 2.4 第二级流水线：BatchCollector 跨类型空间拼接

`BatchCollector` 接收第一级生成的各 Cluster 信息，将其按照数据位宽及数量规则做空间维度上的最终对齐。

1. **分类排序**: 将单实例 Bundle 与多实例 Bundle 区分开来，并按照 `getGroupDataWidth` 降序排列。这使得宽度较大的数据块排列在前，便于级联拼接。

2. **单实例组拼接 (Single)**: 使用直接的级联拼接。如果某个组的 info 不为零（即该组在本周期有有效数据），则通过 `Cat(base, toCat_data(idx))` 将其拼接到已有的数据头部之前。

3. **多实例组移位拼接 (Multi)**: 由于多实例组（如 8 路并行 Commit）的长度在运行期是动态波动的，使用动态移位拼接。偏移量 `offset` 根据前序状态的累加字节数左移 3 位换算为 bit 偏移，然后将数据左移到正确位置，多路移位结果经过 Bitwise-OR 操作叠加为最终输出。

4. **BatchStep 标记追加**: 在该周期 Info 数据链尾部强制追加一个 `BatchStep` 包（表示一个硬件时钟周期的数据收集结束），同时更新 `status.last.info_size` 的值加 1。

5. **流水线隔离**: 利用 `PipelineConnect(grouped, delay_grouped, delay_grouped.fire)` 在 Stage 1 和 Stage 2 的输出之间插入一级寄存器。

### 2.5 第三级流水线：BatchAssembler 跨周期累加与 Flush

`BatchAssembler` 是整个 Batch 流水线的终点核心。它维护了以下关键状态寄存器组：

- `state_data`: 已积累的所有周期的压缩数据比特流。
- `state_info`: 已积累的所有周期的信息索引比特流。
- `state_status`: 当前状态中的 `data_bytes`（已填充字节数）与 `info_size`（已填充 info 条目数）。
- `state_step_cnt`: 当前已积累的时钟周期步数。
- `state_trace_size`: 在 Replay 模式下，当前已积累的 Trace 数据量。

**追加与分裂逻辑**:

当新的 `BatchStepResult` 到达时，Assembler 首先计算剩余可用空间：

- `data_bytes_avail = MaxDataByteLen - state_status.data_bytes`
- `info_size_avail = MaxInfoSize - 1 - state_status.info_size`（保留 1 格存储 `BatchFinish`）

若配置支持 `batchSplit`，则在当前周期数据部分超出可用空间时，可以通过位置掩码把该周期中能够容纳的前半部分探针写入当前包，后半部分的探针及 `BatchStep` 保留在下一轮批处理中。这最大限度地利用了传输参数空间。

**触发 Flush 的条件**:

以下五个条件中任意一个发生时，均会使 `want_tick` 拉高，向外输出完整批处理数据，并在获得 `out.ready` 握手后将内部状态清空：

- **`timeout`**: 批处理组装器挂起时间超过 200,000 个时钟周期。这是一个安全阈值，防止在异常条件下数据无限堆积。
- **`data_exceed` / `info_exceed`**: 数据区或信息区空间不足以容纳新周期的数据。
- **`step_exceed`**: 周期累加数达到了 `batchSize` 限制（通常为 64），必须及时导出以保证软件端的处理时延可控。
- **`trace_exceed`**: 在 Replay 模式下 Trace 记录大小超限（`state_trace_size + delay_step_trace_info.trace_size >= config.replaySize`）。
- **`in_replay`**: 处理器正处于 Replay 重放阶段，必须保证单步周期数据的隔离与透明传递。

**组装输出数据**:

当触发 `want_tick` 时，`BatchAssembler` 将当前 `state_data` 与正在排队的周期数据根据偏移量进行最后一轮移位叠加，并在 `state_info` 尾端拼上 `BatchFinish` 控制信息包（其 `num` 字段代表该批次实际打包的 DUT 执行周期步数），直接送往 `out` 接口。输出时 `out.bits.step` 被设置为 `finish_step`，代表该批处理块包含的实际硬件周期数。

---

## 3. DPICBuffer 区域环形缓冲区机制 (Zone-based Ring Buffer)

与直接、原生的 DPI-C 方式不同，由于硬件执行（时钟沿触发的 DPI-C 调用）与软件仿真主循环（对比评估逻辑）存在调度与执行相干性上的异步问题，DiffTest 在 C++ 端设计了一层高性能、零拷贝的 **区域环形缓冲区机制 (DPICBuffer)**，用以平衡并缓冲 DUT 状态输出流。

### 3.1 内存拓扑与二维数组布局

`DPICBuffer` 继承自 C++ 基类 `DiffStateBuffer`，其最核心的私有数据成员为一个二维结构体数组：

```
DiffTestState buffer[CONFIG_DIFFTEST_ZONESIZE][CONFIG_DIFFTEST_BUFLEN];
```

- **CONFIG_DIFFTEST_ZONESIZE (通常定义为 2)**: 引入 Zone 的设计初衷是为了在双区（Zone）系统下工作。在安全扩展验证（如 RISC-V TrustZone / RVM）或双时钟域/核间交互的测试中，不同的特权域或不同模块所导出的包对应不同的 Zone。不同的 Zone 在空间上完全物理隔离，避免时序或特权状态的交织破坏数据的一致性。

- **CONFIG_DIFFTEST_BUFLEN (通常等于 BatchSize)**: 环形缓冲区的单区最大容量。在 Batch 模式下它等于 `batchSize`。

### 3.2 环形指针轮转与状态就地同步

`DPICBuffer` 维护了两个关键的指针：

- **read_ptr**: 指向参考模型对比线程当前应读取的 `DiffTestState` 位置。
- **zone_ptr**: 标志当前的 Zone 空间索引。

核心公有接口的定义及工作逻辑如下：

**`get(int zone, int index)`**: 返回 `buffer[zone] + index` 指针。该接口被 DPI-C 端代码直接调用。当 Scala 生成的 DPI-C 传输函数接收到带有 `dut_zone` 标志和 `dut_index` 偏移的状态包时，会调用 `diffstate_buffer[core_id]->get(zone, index)` 直接定位到内存空间上对应的 `DiffTestState` 元素并执行 `memcpy`。这实现了对硬件仿真输出数据的就地填充，避免了多次拷贝的性能消耗。

**`next()`**: 返回当前 `zone_ptr` 和 `read_ptr` 指向的状态结构体指针，并在返回后将 `read_ptr` 自动累加 1 并取模（`(read_ptr + 1) % CONFIG_DIFFTEST_BUFLEN`）。该接口供 Ref Model 获取下一步用于对比的 CPU 黄金状态。特别地，如果系统导出了物理寄存器 (`phyRegs`)，它会在返回 ret 指针之前，自动调用 `diffstate_update_archreg(ret)`，将重命名表和物理寄存器映射回 32 个标准的 RISC-V 架构寄存器。

**`switch_zone()`**: 将 `zone_ptr` 自增并取模（`(zone_ptr + 1) % CONFIG_DIFFTEST_ZONESIZE`），同时将 `read_ptr` 重置为 0。用于在 Zone 之间切换，确保双区验证的流程能够同步滚动。首次调用时由于 `init` 标志会跳过实际切换，避免初始化阶段的错误。

### 3.3 架构寄存器映射的延迟计算

在 `DPIC.collect()` 方法中，当检测到存在物理寄存器探针 (`pregs`) 时，它会在 C++ 生成的头文件中自动插入 `diffstate_update_archreg` 函数。该函数的核心逻辑为：

- 对每一个 `pregs_xrf` (物理整数寄存器文件) 类型的 Bundle，找到对应的 `rat_xrf` (寄存器重命名映射表)。
- 遍历所有 32 个架构寄存器索引 `i`，通过重命名表进行查表：`dut->regs.gpr.value[i] = dut->pregs_xrf.value[dut->rat_xrf.value[i]]`。

这一设计将硬件上导出的物理寄存器 + 重命名表的原始数据，在软件端按需重组为架构寄存器快照，极大地简化了硬件探针的逻辑复杂度。

### 3.4 diffstate_buffer 的生命周期管理

在 `difftest-dpic.cpp` 的生成代码中，提供了完整的缓冲区管理 API：

- `diffstate_buffer_init()`: 为每一个核心分配一个 `DPICBuffer` 实例，并在 Delta 模式下额外初始化 `dStats = new DeltaStats`。
- `diffstate_buffer_free()`: 释放所有核心的 `DPICBuffer` 实例，清理全局 `diffstate_buffer` 指针。

全局指针 `diffstate_buffer` 通过宏 `DUT_BUF(core_id, zone, index)` 被 C++ 侧的所有 DPI-C 传输函数直接调用，实现了从硬件探针到软件缓冲区的直通写入。

---

## 4. GatewayConfig 与 DifftestModule 探针收集

`Gateway`（网关）是整个香山 DiffTest 探针网络的前端控制中枢。它连接着香山内核内部打下的成百上千个 Chisel 监视点，并负责将这些监视点根据运行时配置路由到正确的输出端点。

### 4.1 GatewayConfig 的参数体系与合法性校验

`GatewayConfig` 是一个 case class 配置集合，包含了几乎所有与仿真比对相关的使能标志。主要参数及其关联逻辑包括：

| 参数 | 类型 | 说明 |
| :--- | :--- | :--- |
| `style` | String | 仿真导出类型，香山默认为 `"dpic"` |
| `isSquash` | Boolean | 开启流水线 Squash 折叠 |
| `hasReplay` | Boolean | 开启调试回放 |
| `isDelta` | Boolean | 开启增量状态编码 |
| `isBatch` | Boolean | 开启批处理流水线 |
| `isFPGA` | Boolean | 面向 FPGA 原型验证板 |
| `isGSIM` | Boolean | 面向 GSIM 仿真环境 |
| `hasDutZone` | Boolean | 启用双区 (Dual Zone) 系统 |
| `hasGlobalEnable` | Boolean | 全局使能控制 |
| `hasInternalStep` | Boolean | 内部步进控制 |
| `isNonBlock` | Boolean | 非阻塞模式（适配 Palladium） |
| `hasBuiltInPerf` | Boolean | 内建性能统计计数器 |
| `traceDump` / `traceLoad` | Boolean | IO Trace 的 dump 与 load |

在配置初始化后，`check()` 方法会执行严格的参数安全约束性验证：

- 若启用 `hasReplay`，则必须强制启用 `isSquash`（因为 Replay 基于正确还原的执行戳序列）。
- 若启用 `hasInternalStep`，则必须强制启用 `isBatch`（因为内部步进依赖于批处理的节拍控制）。
- 若启用 `isDelta`，则必须强制启用 `isBatch`（因为增量的拆分依赖于批处理接口的统一打包传输）。
- 若启用 `isFPGA`，则必须开启 `isBatch` 以通过标准 BatchIO 对接 PCIe 通信协议。
- `traceDump` 和 `traceLoad` 不能同时启用。

### 4.2 宏生成策略

`GatewayConfig` 提供了 `cppMacros` 和 `vMacros` 两个方法，用于为 C++ 编译和 Verilog 编译生成对应的预处理器宏：

- C++ 宏包含：`CONFIG_DIFFTEST_DPIC`、`CONFIG_DIFFTEST_ZONESIZE`、`CONFIG_DIFFTEST_BUFLEN`、`CONFIG_DIFFTEST_BATCH`、`CONFIG_DIFFTEST_BATCH_SIZE`、`CONFIG_DIFFTEST_BATCH_BYTELEN`、`CONFIG_DIFFTEST_SQUASH`、`CONFIG_DIFFTEST_SQUASH_STAMPSIZE`（固定为 4096，对应 12 位宽的 stamp）、`CONFIG_DIFFTEST_DELTA`、`CONFIG_DIFFTEST_REPLAY` 等。
- Verilog 宏包含：`CONFIG_DIFFTEST_STEPWIDTH`、`CONFIG_DIFFTEST_BATCH_IO_WITDH`、`CONFIG_DIFFTEST_CLOCKGATE` 等。

### 4.3 Gateway 的生命周期

1. **setConfig**: 根据配置字符串（如 `"SBDRN"`）逐字符解析并设置对应的功能标志位。

2. **apply**: 每一个硬件探针通过 `Gateway(gen, delay)` 声明并注册到网关全局列表 `instanceWithDelay` 中。根据是否需要端点 (`needEndpoint`)，探针数据要么被 `DifftestWiring` 提取用于后续统一处理，要么直接实例化 `GatewaySink`。

3. **collect()**: 在编译期汇总所有注册的探针，生成完整的 `GatewayResult`。若启用了高级功能，实例化 `GatewayEndpoint`；否则退化为基础的 DPI-C 直通模式。

### 4.4 GatewayEndpoint 网关端点数据流水线

`GatewayEndpoint` 是整个数据处理的核心枢纽，其内部硬件流水线路径为：

- **Preprocess**: 数据预处理（物理寄存器到架构寄存器的硬件转换、Load 事件过滤等）。
- **Replay**: 回放缓冲区的注入。
- **Validate**: 有效性验证。
- **Squash**: 多周期状态折叠。
- **Delta**: 增量拆分器。
- **Batch / GatewaySink**: 批处理打包或直接 DPI-C 导出。

此外，`GatewayEndpoint` 还负责：
- 在 `hasClockGate` 配置下生成时钟使能信号 `clockEnable`，控制整个 DiffTest 子系统的时钟门控。
- 在 FPGA 模式下，将 Batch 输出对接到 `FpgaDiffIO` 接口。
- 暴露 `step` 输出信号用于超时检测。

---

## 5. Squash 机制与多周期折叠 (Squash Mechanism)

在现代超标量处理器中，由于推测执行、分支预测失败以及异常的存在，许多已经被取指并执行的操作随时可能在流水线后端被重置（Squash）。如果把每一周期的推测状态都输出进行差分对比，不仅带来海量的传输压力，也会因为状态回滚导致 Ref Model 难以处理。**Squash 机制**的作用在于利用一套硬件状态折叠逻辑，将那些发生在非退休路径上的瞬态事件进行丢弃，或者在无冲突的多周期之间对相同的寄存器写等状态进行覆盖合并，仅保留最终真正退休后的系统状态。核心机制实现在 `Squash.scala` 中。

### 5.1 Stamper 模块与指令印记机制

`Stamper` 模块负责为事件探针打上统一的时效戳（Stamp）。

1. **提交计数**: `Stamper` 内部为每一个 CPU 核心维护了一个 12 位的印记计数器 `stamp`，其最大计数值为 4096（对应 C++ 宏 `CONFIG_DIFFTEST_SQUASH_STAMPSIZE 4096`）。

2. **Commit 指令累加**: 在每个周期，过滤出所有名为 `commit` 的 `DiffInstrCommit` 探针。如果探针有效且 `skip` 信号为低，则判定指令退休，单周期提交数累加值计算为 `commitCnt = 1.U + c.bits.nFused`（1 加上融合指令数）。对当前周期的多个 Commit 通道的前缀和做累加，并在时钟上升沿更新至该核心的全局印记：`stamp(id) := stamp(id) + commitSum(id).last`。

3. **LoadEvent 印记注入**: 将当前的 `stamp(coreid) + commitSum` 写入 `DiffLoadEventQueue.stamp` 中，同时同步拷贝对应的 Commit 数据 (`commitData`)、向量 Commit 数据 (`vecCommitData`)、寄存器写使能 (`regWen`)、写目标寄存器号 (`wdest`)、浮点写使能 (`fpwen`)、向量写使能 (`vecwen`) 等信息。

4. **StoreEvent 印记注入**: 如果当前周期发生了 Commit，印记设为 `stamp + inc`；否则设为 `stamp + 1.U`。这是因为在没有 Commit 发生的周期里，Store 事件需要在接下来的 Commit 中被最终确认并校验。

5. **输出重组**: Stamp 处理完成后，`loadQueues` 和 `storeQueues` 会替换原始的 `load` 和 `store` 事件，形成带完整时序印记的新事件序列输出。

### 5.2 Squasher 的折叠与状态转移控制

在 `SquashEndpoint` 中，除 `commit` 外的各组探针被送入独立的 `Squasher` 模块进行状态累加和判定。`Squasher` 维护了一个与输入探针类型一致的 `state` 寄存器。

**合并合法性判定**:

- **`supportsSquash`**: 遍历所有探针包，调用每个包的 `supportsSquash(s)` 方法，判断当前周期输入 `in` 与已暂存的 `state` 是否可以合并。例如，如果两个包对应的写地址相同，后一个可以直接覆盖（squash）前一个。所有探针都支持 squash 时才为真。
- **`supportsSquashBase`**: 判断当前的暂存状态是否满足继续作为折叠基准的条件。

**Tick 触发条件**:

```scala
want_tick := !supportsSquash || !supportsSquashBase || tick_first_commit.getOrElse(false.B)
```

- `tick_first_commit`: 对于 `commit` 类型探针，检测每个核心是否是第一次出现有效 Commit。如果是第一次，则强制拉高 tick，以确保初始状态的同步。
- 当外部总控发出 `group_tick`（同组内其他 Squasher 发生 Tick 导致状态必须级联对齐）或 `global_tick`（超时或 Replay，或者 SquashControl 使能关闭）时，硬件同样会触发 `should_tick = true.B`。

**SquashControl 的 C++ 交互**:

`SquashControl` 是一个内联 Verilog 模块，通过 DPI-C 导出 `set_squash_enable` 函数和导入 `set_squash_scope` 函数，使 C++ 仿真主程序能够动态控制 Squash 机制的开关。此外，它还支持通过仿真参数 `+squash-cycles=N` 设置最大压缩周期数，超过此周期数后自动关闭 Squash 功能，以便对比分析。

**状态流转机制**:

- 当 `out.fire`（当前折叠数据成功发送给下一级且被接收）时：寄存器被排队进来的新输入覆盖 `s := i`。
- 当 `in.fire`（正常接收新数据但没有发生 Tick 时）：执行折叠函数更新暂存寄存器 `s := i.squash(s)`。
- 当 `should_tick` 为高但 `out` 未准备好时：内部状态冻结等待。

---

## 6. Delta 编码与增量状态传输 (Delta Encoding)

像物理寄存器堆 (`PhyRegState`，通常多达 100 到 200 个 64 位寄存器) 等超大状态，每次仿真同步都完整地传给软件端会造成极大的带宽浪费。**Delta 编码机制**的思路是：硬件上实时比对寄存器堆的修改，每次只传递那些在本周期内发生了改变的寄存器值，由 C++ 端的 `DeltaStats` 进行增量维护。核心实现在 `Delta.scala` 中。

### 6.1 DeltaSplitter 增量检测与串行化拆分器

`DeltaSplitter` 挂载在支持增量编码的探针（即 `supportsDelta == true`，例如 `DiffPhyRegState`）的输出路径上。

**差异比较与位掩码**:

`DeltaSplitter` 内部维护了与原始 Bundle 具有相同元素数的 `r_elems` 寄存器。在每个时钟周期，将 Chisel 打平后的输入元素 `first_elems` 与寄存器中的状态 `r_elems` 进行并行对比：

```scala
val first_updates = VecInit(first_elems.zip(r_elems).zip(update_mask).map {
  case ((e, s), m) => e =/= s && in.fire && in.bits.valid && m
})
```

如果第 i 个元素发生改变，且对应掩码 `update_mask` 为高，则 `first_updates(i)` 标志置 1。更新后的值会被存储回 `r_elems` 寄存器。

**动态过滤机制**:

对于 `DiffPhyRegState`，`DeltaSplitter` 还支持基于 RAT (Register Alias Table) 和 Commit 写目标寄存器的动态过滤。当硬件中存在 `DiffArchRenameTable` 或 `DiffInstrCommit` 时，`DeltaSplitter` 的 `filter` 输入会通过以下逻辑生成：

- 从所有 `DiffArchRenameTable` 中提取每个架构寄存器对应的物理寄存器号，通过 `UIntToOH` 生成热位掩码，并对所有表进行 OR 归约。
- 从 `DiffInstrCommit` 中提取写目标物理寄存器 `wpdest`，同样通过 `UIntToOH` 生成热位掩码。
- 将所有来源的热位掩码 OR 合并后作为 `update_mask`，仅允许那些被重命名表或 Commit 事件引用的物理寄存器参与差异检测，大幅减少无效的 Delta 传输。

**分批次输出与 PriorityEncoder**:

考虑到硬件传输的物理限制，系统不可能在一个周期内发送无数个 Delta 改变。配置中定义了 `deltaLimit`（默认每个物理周期最大传输 8 个变动元素）。

- 如果在一个周期内有超过 8 个以上的元素发生了变化，`DeltaSplitter` 需要将其拆分到连续的几个时钟周期里分批输出。
- 它将 `first_updates` 以 8 个为一组分块，并用 `PriorityEncoder` 计算出当前正在输出的组索引 `group_idx`。
- 利用掩码清除机制，在当前组成功发送后，清除已发送的比特位。当 `next_group_updates` 不为零时，将 `inPending` 标志拉高。

**上游反压**:

若 Delta 拆分工作尚未完成（`inPending` 或 `visiblePending` 为高），`DeltaSplitter` 会强行将 `in.ready` 拉低，挂起上游的全部流水线，直到把该周期的所有增量差异项完全排空。`in.ready` 的判定还加入了 `RegNext` 一拍延迟，以防止在最后一个 hold beat 的周期被新的输入替换。

**组装差异元素 (DiffDeltaElem)**:

对于当前选定的输出组，使用 `LookupTree` 选出最多 8 个差异项的值，打包为 `DiffDeltaElem`（包含 `coreid`、发生修改的元素 `index`、以及新 `data`），并置 `out.valid` 为高。

### 6.2 DeltaEndpoint 的集成

`DeltaEndpoint` 负责将 `DeltaSplitter` 与非增量探针的直通路径整合在一起：

1. 从输入 MixedVec 中筛选出所有 `supportsDelta` 的探针，为每个实例化一个 `DeltaSplitter`。
2. 将不支持增量编码的探针（如 `InstrCommit`、`TrapEvent` 等事件型探针）通过门控逻辑处理：仅在 `pipelined.fire` 时有效，否则清零。
3. 当所有 `DeltaSplitter` 均完成输出（`!inPending`）且当前有 Delta 数据（`deltas` 非空）时，生成一个 `DiffDeltaInfo` 标记包，表示增量更新序列结束。
4. 将非 Delta 直通数据、Delta 差异元素以及 `DiffDeltaInfo` 合并输出到下游。

### 6.3 DeltaStats 的 C++ 状态合并与同步

当增量数据块经过 Batch 打包传输到 C++ 侧后，由 `DeltaStats` 负责接收并将这些修改还原到全局的 `diffstate_buffer` 中：

- **DeltaState 内存镜像**: `DeltaStats` 内部拥有一份基于各核心的增量状态镜像 `DeltaState buffer[NUM_CORES]`，包含用于握手控制的 `DifftestDeltaInfo delta_info` 结构体和增量数组。

- **need_pending 处理**: 当增量包在 CPU 运行期间不断通过 DPI-C 解包写入时，`hasProgress` 会被置为 `true`。若当前批次传输未完结，`delta_info.valid` 为假。此时 `need_pending()` 返回 `true`，使得 `DPICBatch` 的 C++ 循环在此处跳过更新（`continue`），挂起 DUT 状态的同步，继续等待后续的批数据。

- **内存同步 (sync)**: 一旦最后一组增量包传输完毕，`delta_info.valid` 变为 `true`。`DPICBatch` 的 C++ 循环会检测到这一变化，触发 `sync(zone, index)` 操作。该操作遍历所有核心，对每个核心的 `DiffTestState` 和对应的 `DeltaState` 执行 `memcpy`，将增量元素逐个写回全局结构体。完成同步后，重置 `hasProgress = false` 和 `delta_info.valid = false`。

---

## 7. Replay 调试回放支持 (Replay Support)

差分测试的一个难点是：当发现系统在第 T 个周期对比失败时，由于失败的根源通常发生在很久以前（例如第 T-100 个周期的一起错误推测写），调试时需要能够将执行流倒退回故障前夕并重新演绎。为此，香山设计了硬件级的 **Replay 回放机制**。核心实现在 `Replay.scala` 中。

### 7.1 ReplayEndpoint 的环形回放缓冲区

`ReplayEndpoint` 核心围绕着一个超宽的硬件循环存储器（使用 Chisel `Mem` 实现）进行构建，其存储深度由 `config.replaySize`（通常为 1024 周期）决定，指针宽度为 `config.replayWidth`（即 `log2Ceil(replaySize + 1)` 位）。

**输入打平与拓扑扩张**:

`ReplayEndpoint` 的输入包括所有可能发生比对的探针 MixedVec，并在其末尾追加挂载了一个独占的 `DiffTraceInfo` 结构体。`DiffTraceInfo` 包含以下字段：

- `valid`: 标识该条记录是否有效（需要被存储）。
- `in_replay`: 标识当前是否处于 Replay 状态。
- `trace_head`: 记录该周期数据在环形缓冲区中的写入指针位置。
- `trace_size`: 记录本周期有效 Trace 的条目数量。

**写入模式**:

在处理器处于正常运行状态时（`!control.replay` 为高）：

- 如果使能了全局使能（`hasGlobalEnable`），只有当本周期产生了需要更新的有效数据时（`needStore` 为真），才会将当前打平的数据写入 `buffer(ptr)`。
- 同时在 `DiffTraceInfo` 里盖上当前物理写指针 `trace_head = ptr` 并设置 `trace_size = 1.U`。
- 之后 `ptr` 自增，并在超出深度（`ptr === replaySize - 1`）时自动回绕到 0，实现了一个在硬件端自动滚动的 1024 步执行快照记录器。

### 7.2 回放模式激活与数据重灌

一旦外部 C++ 验证程序或者调试器在第 T 步检测到 Mismatch：

1. **DPI-C 触发**: 验证程序会通过调用导出的 DPI-C 函数 `set_replay_head(int head)` 通知硬件控制端。

2. **ReplayControl 响应**: 在内联 Verilog 模块 `ReplayControl` 内部，该函数调用将 `replay` 输出信号拉高，并将重置起点 `replay_head` 传回给 `ReplayEndpoint`。

3. **ReplayEndpoint 模式切换**:
   - 在检测到 `control.replay` 为高时，首先拉起内部寄存的 `in_replay` 标志，并强行将环形存储器的读指针 `ptr` 覆盖为 `control.replay_head`。
   - 在此后的每一个时钟周期中，数据分配总线不再选取输入端的实时数据 `appendIn`，而是从 `buffer(ptr)` 中读取历史快照数据重新灌入仿真对比流水线。
   - `ptr` 依次递增，并在超出深度时自动回绕，完美复刻历史执行痕迹。
   - 在输出数据中，会把 `DiffTraceInfo.in_replay` 属性强行置为 `true`，以通知下游模块和 Ref 模型目前正在执行重放对比。

### 7.3 Replay 与 Batch/Squash 的协同

Replay 机制与 Batch 流水线和 Squash 机制存在紧密的协同关系：

- 在 BatchAssembler 中，当检测到 `in_replay` 时，会触发 `want_tick` 强制 Flush 当前批次。这是因为 Replay 数据必须保证逐周期精确传递，不能与正常执行周期的数据混合打包。
- 在 SquashEndpoint 中，`global_tick` 信号包含了 `in_replay` 条件。当 Replay 激活时，Squash 折叠被强制暂停（全局 Tick），以确保回放数据的时序完整性。
- 在 Batch 的 C++ 接收端，`state_trace_size` 的追踪机制确保 Replay 期间的 Trace 数据不会超出缓冲区容量。一旦超出阈值（`trace_exceed`），BatchAssembler 同样会触发 Flush 并清空状态。

---

## 8. SimTop 级联集成 (SimTop Integration)

`SimTop` 是香山 DiffTest 硬件设计的顶层封装模块，它负责将香山 CPU 核心（包含外部总线及状态端口）与网关生成的探针输出网络无缝编织在一起。核心实现在 `SimTop.scala` 中。

### 8.1 模块参数化与构造

`SimTop[T <: RawModule with HasDiffTestInterfaces]` 采用 Scala 的参数化构造，接受一个动态的 CPU 模块生成器 `cpuGen` 和可选的模块前缀 `modPrefix`。

在构造函数内部：
- 实例化 CPU 核心：`val cpu = Module(cpuGen)`
- 连接时钟与复位：`cpu.dutClock := clock`，`cpu.dutReset := reset.asTypeOf(cpu.dutReset)`
- 提取 CPU 名称：从 `cpu.cpuName` 获取或从类名自动推导
- 调用 `DifftestModule.collect(cpuName)` 触发网关汇总
- 创建 DiffTest 顶层 IO：`val difftest = IO(new DifftestTopIO)`

### 8.2 内部端口过滤与 Boring 提取

由于香山 CPU 内部使用了大量的 `BoringUtils` 向外钻孔引出信号，`SimTop` 需要通过特定的规则从反射获取的端口中筛选出需要暴露给外部的端口：

**`dutIOs` 过滤逻辑**:
- `portFilter` 函数会自动过滤掉 `bore` 前缀的信号（这些是内部钻孔信号，已被 DiffTest 网关通过 `DifftestWiring` 处理）。
- `UARTIO` 端口被特殊处理：其子元素被递归展开并扁平化映射到 DiffTest 的顶层 IO。
- `dutClock` 和 `dutReset` 因为已被 Chisel 模块自动生成，不需要额外暴露。

**端口提取与导出**:
```scala
cpu.dutIOs.foreach { case (name, gen) =>
  val io = IO(chiselTypeOf(gen)).suggestName(name)
  dontTouch(gen)
  io <> gen
}
```

### 8.3 时钟与复位管理

- **Timer 计数器**: 在 `difftest` 命名前缀区内，维护一个 64 位的自由运行计数器 `timer`，用于 LogPerfControl 的日志时间窗口过滤。
- **Reference Clock**: 当网关有时钟门控需求时，引入额外的 `ref_clock` 输入引脚，并将其连接到 `gateway.refClock`。
- **Clock Enable**: 将 `gateway.clockEnable` 输出到 `clock_enable` 物理引脚，供 FPGA 板级时钟门控控制。

### 8.4 FPGA 主机端点级联 (HostEndpoint)

当 DiffTest 面临 FPGA 验证场景（`config.isFPGA` 开启）时，探针数据不能通过简单的 C++ DPI-C 直接写入，而是需要跨越 PCIe 物理链路输送给主机。

1. 在 `SimTop` 的 `difftest` 命名前缀区内，若发现有 `fpgaIO` 指针，会实例化一个 `HostEndpoint` 模块。

2. `HostEndpoint` 的输入端直接对接来自 Batch 流水线终点的 `fpgaIO` 接口，在 ref_clock 上升沿对宽达数千比特的打包数据进行缓存。

3. 在输出端，`HostEndpoint` 按照标准的 AXI-Stream (AXIS) 接口协议进行格式化打包，将其暴露给顶层的物理管脚 `to_host_axis`，供外部 Xilinx XDMA 等 PCIe IP 核读取并传输回上位机验证程序。

4. 额外暴露 `pcie_clock` 输入引脚用于 PCIe 链路时钟域。

### 8.5 完整性校验

`SimTop` 在构造函数的最后会执行一个断言：
```scala
require(DifftestWiring.isEmpty, s"pending wires left: ${DifftestWiring.getPending}")
```
这确保了所有通过 `DifftestWiring` 注册的信号都已被正确消费和连接，没有遗留的悬空探针。

---

## 9. 源码文件物理位置与功能映射 (Source File Map)

香山处理器的 DiffTest 硬件组件均作为 Scala 项目模块存放在 `difftest/src/main/scala/` 目录下，具体路径和职责如下：

| 绝对物理路径 | 主要模块/类 | 核心技术职责 |
| :--- | :--- | :--- |
| `.../difftest/src/main/scala/DPIC.scala` | DPICBase, DPIC[T], DPICBatch, DPIC | DPI-C 代码生成器核心。定义 SV 模块外壳生成、C++ 函数原型与函数体生成、DPICBuffer 区域环形缓冲区 C++ 类声明与实现、diffstate_buffer 全局指针管理、性能计数器框架。 |
| `.../difftest/src/main/scala/Batch.scala` | BatchParam, BatchIO, BatchCluster, BatchCollector, BatchAssembler, BatchEndpoint | 三级硬件批处理流水线的完整实现。包含参数计算模型、同周期同类型 Bundle 的 PopCount 压缩对齐、跨类型空间动态拼接、跨周期状态累加与 Flush 控制。 |
| `.../difftest/src/main/scala/Gateway.scala` | GatewayConfig, GatewayResult, Gateway, GatewayEndpoint, GatewaySink, ZoneControl | 网关配置中心与探针生命周期管理。包含全部功能标志位定义、配置合法性校验、探针注册与收集、网关端点硬件流水线编排、双区 Zone 控制。 |
| `.../difftest/src/main/scala/Squash.scala` | Squash, Stamper, SquashEndpoint, Squasher, SquashControl | 多周期状态折叠机制。包含指令退休印记计算、基于 supportsSquash 的状态覆盖合并、组级联 Tick 控制、SquashControl 的 DPI-C 交互与参数配置。 |
| `.../difftest/src/main/scala/Delta.scala` | Delta, DeltaSplitter, DeltaEndpoint, DeltaStats | 增量状态编码。包含寄存器级差异检测、基于 RAT/Commit 的动态掩码过滤、PriorityEncoder 串行化分批输出、C++ 端 DeltaStats 的增量接收与 sync 同步。 |
| `.../difftest/src/main/scala/Replay.scala` | Replay, ReplayEndpoint, ReplayControl | 调试回放机制。包含硬件环形 RAM 环形缓冲区的写入与读取模式、DPI-C 导出的 set_replay_head 回放触发接口、in_replay 状态的流转控制。 |
| `.../difftest/src/main/scala/Bundles.scala` | DifftestBaseBundle, InstrCommit, CSRState, PhyRegState, ArchRegState, DeltaElem, TraceInfo 等 | DiffTest 所有监控探针的 Chisel 数据结构定义。涵盖指令提交、CSR 状态、物理/架构寄存器、TLB 事件、原子操作、向量操作等全部 Bundle 类型。 |
| `.../difftest/src/main/scala/SimTop.scala` | SimTop, DifftestTopIO, HasDiffTestInterfaces | 仿真顶层模块外壳。负责 CPU 端口反射提取、Boring 信号过滤、Gateway 端点实例化、FPGA HostEndpoint AXI-Stream 级联、时钟门控引出。 |
| `.../difftest/src/main/scala/Preprocess.scala` | Preprocess, PreprocessEndpoint | 数据预处理段。在硬件上依据重命名映射关系将物理寄存器和重命名表还原为架构寄存器状态，以及单核模式下移除无效的 Load 事件。 |

注：上表中 `...` 代表 `/home/agi/workspace/gitwork/XiangShan`。

---

## 10. 总结与展望 (Conclusion)

香山的 DPI-C 与 Batch 流水线技术将复杂的超标量内核差分验证逻辑成功解耦，其设计亮点主要体现在以下几个维度：

1. **高度自动化的代码生成能力**: 基于 Scala 对 Chisel 结构的静态分析，直接编译出 C++ 与 SV 两侧结构体完全对齐的数据搬运函数。当处理器内部新增或修改探针时，只需在 Scala 端声明一次，两侧接口代码均自动生成与更新，彻底避免了传统手动维护 DPI-C 接口所带来的接口不一致风险。

2. **优雅的三级打包流水线**: 通过 BatchCluster 的 PopCount 压缩、BatchCollector 的多路动态移位拼接以及 BatchAssembler 的跨周期累加，三个流水级各司其职、相互隔离。该架构不仅解决了多通道探针 valid 的对齐问题，还通过批处理模式使得仿真数据以高吞吐模式回传给 Nemo 引擎。`batchSplit` 机制在大负载时允许跨周期拆分，最大限度利用了传输参数空间。

3. **区域隔离的环形缓冲区**: `DPICBuffer` 的二维数组布局和 `get/next/switch_zone` 接口设计，使得双区系统（如 TrustZone 场景）的 DiffTest 变得自然透明。就地写入和按需计算架构寄存器的设计，将计算开销从硬件转移到了软件，大幅降低了 Chisel 端的布线压力。

4. **精密的折叠与增量优化**: Squash 机制通过硬件级的 `supportsSquash` 判定和印记时间戳，精准过滤了推测执行产生的无效事件；Delta 编码则通过并行差异检测和串行化分批输出，将寄存器文件等超大状态的传输开销降低了数个数量级。两者结合，在不牺牲功能正确性的前提下极大减少了仿真带宽。

5. **可靠的调试回放保障**: 1024 深度的 Replay 环形缓冲区配合 DPI-C 的 `set_replay_head` 远程触发接口，使得在发现 DiffTest Mismatch 后能够快速将处理器状态回溯到故障前夕并精确重演，为香山处理器的快速定位与修复 Bug 提供了完备的硬件基础设施。

展望未来，随着香山处理器在多核一致性验证、虚拟化扩展验证等方向的深入，DiffTest 框架有望在以下几个方面进一步演进：

- **多核一致性 Squash**: 扩展 Squash 机制以支持跨核心的事务级合并，在多核 MESI/MOESI 协议的缓存一致性验证中进一步压缩传输量。
- **更智能的 Delta 过滤**: 结合处理器微架构信息（如指令依赖图），在硬件端实现更精确的活跃寄存器集合推断，进一步减少无效 Delta 的传输。
- **异构加速器适配**: 将 Batch Pipeline 扩展到 RISC-V 向量扩展 (RVV) 和专用加速器的验证场景中，处理更大规模的并行状态输出。

---

*本报告基于香山 (XiangShan) RISC-V 处理器 DiffTest 框架源码分析编写，涵盖 DPI-C 代码生成、Batch 流水线、区域环形缓冲、Squash 折叠、Delta 增量编码、Replay 回放、SimTop 集成等核心技术模块的深度技术剖析。*
