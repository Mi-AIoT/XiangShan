# 性能计数器实现详解

> 本文档详细解释性能计数器在 RTL 中的实现原理，适合嵌入式软件工程师理解。

---

## 目录

1. [什么是性能计数器？](#1-什么是性能计数器)
2. [嵌入式的性能计数器](#2-嵌入式的性能计数器)
3. [XiangShan 的性能计数器](#3-xiangshan-的性能计数器)
4. [Top-Down 分析方法](#4-top-down-分析方法)
5. [性能计数器输出格式](#5-性能计数器输出格式)
6. [提取脚本详解](#6-提取脚本详解)
7. [如何添加自定义计数器](#7-如何添加自定义计数器)
8. [常见问题解答](#8-常见问题解答)

---

## 1. 什么是性能计数器？

### 1.1 简单定义

**性能计数器**：用于统计硬件事件的计数器，帮助分析程序执行的性能瓶颈。

### 1.2 类比理解

**汽车仪表盘**：
- 速度计：当前速度
- 转速表：发动机转速
- 油耗表：燃油消耗

**CPU 性能计数器**：
- IPC：每周期指令数
- 缓存命中率：缓存访问成功率
- 分支预测成功率：分支预测正确率

### 1.3 性能计数器的作用

```
程序执行慢
    │
    ▼
┌─────────────────────────────────────────────────────────┐
│                    性能分析                               │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  问题：IPC 低（每周期指令数少）                          │
│                                                         │
│  原因分析：                                              │
│  - 前端停顿：取指/译码慢                                │
│  - 后端停顿：执行/提交慢                                │
│  - 内存停顿：缓存缺失                                   │
│  - 分支预测：预测失败                                   │
│                                                         │
│  性能计数器帮助定位具体原因                              │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

---

## 2. 嵌入式的性能计数器

### 2.1 ARM PMU

**ARM PMU (Performance Monitoring Unit)**：

```c
// 读取 ARM PMU 计数器
uint64_t read_cycle_counter() {
    uint64_t value;
    asm volatile("mrs %0, pmccntr_el0" : "=r"(value));
    return value;
}

uint64_t read_instruction_counter() {
    uint64_t value;
    asm volatile("mrs %0, pmevcntr0_el0" : "=r"(value));
    return value;
}
```

**支持的事件**：
- CPU 周期数
- 指令执行数
- 缓存命中/缺失
- 分支预测成功/失败
- 内存访问数

### 2.2 RISC-V HPM 计数器

**RISC-V HPM (Hardware Performance Monitoring)**：

```c
// 读取 RISC-V HPM 计数器
uint64_t read_cycle_counter() {
    uint64_t value;
    asm volatile("csrr %0, cycle" : "=r"(value));
    return value;
}

uint64_t read_instruction_counter() {
    uint64_t value;
    asm volatile("csrr %0, instret" : "=r"(value));
    return value;
}
```

**支持的 CSR**：
- `cycle`：CPU 周期数
- `instret`：指令执行数
- `hpmcounter3`-`hpmcounter31`：自定义事件

### 2.3 嵌入式 vs RTL 仿真

| 方面 | 嵌入式 PMU | RTL 仿真计数器 |
|------|------------|----------------|
| **实现** | 硬件寄存器 | RTL 代码 |
| **读取** | CSR 指令 | `$fwrite` 输出 |
| **精度** | 精确 | 精确 |
| **开销** | 几乎无 | 无 |
| **用途** | 运行时分析 | 离线分析 |

---

## 3. XiangShan 的性能计数器

### 3.1 计数器分类

**XiangShan 的性能计数器分为 4 类**：

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    XiangShan 性能计数器分类                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  1. 基础计数器                                                               │
│     - commitInstr: 提交指令数                                               │
│     - total_cycles: 总周期数                                                │
│     - NoStall: 无停顿周期                                                   │
│                                                                             │
│  2. 前端计数器                                                               │
│     - ICacheMissBubble: I-Cache 缺失                                        │
│     - ITLBMissBubble: I-TLB 缺失                                           │
│     - BTBMissBubble: BTB 缺失                                              │
│     - FetchFragBubble: 取指碎片化                                           │
│                                                                             │
│  3. 后端计数器                                                               │
│     - DivStall: 除法器停顿                                                  │
│     - RobStall: ROB 满                                                     │
│     - IntFlStall: 整数 freelist 满                                         │
│     - FpFlStall: 浮点 freelist 满                                          │
│                                                                             │
│  4. 内存计数器                                                               │
│     - LoadL1Stall: L1 缺失                                                  │
│     - LoadL2Stall: L2 缺失                                                  │
│     - LoadL3Stall: L3 缺失                                                  │
│     - StoreStall: 存储停顿                                                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 计数器实现示例

**Verilog 实现**：

```verilog
// 性能计数器模块
module PerfCounters (
    input  wire        clock,
    input  wire        reset,
    
    // 输入信号
    input  wire        commit_valid,
    input  wire        icache_miss,
    input  wire        itlb_miss,
    input  wire        div_stall,
    input  wire        load_l1_miss,
    // ... 更多输入
    
    // 输出信号（用于 $fwrite）
    output reg [63:0]  commit_instr_count,
    output reg [63:0]  cycle_count,
    output reg [63:0]  icache_miss_count,
    output reg [63:0]  itlb_miss_count,
    output reg [63:0]  div_stall_count,
    output reg [63:0]  load_l1_miss_count
);

    // 计数器逻辑
    always @(posedge clock) begin
        if (reset) begin
            commit_instr_count <= 64'd0;
            cycle_count <= 64'd0;
            icache_miss_count <= 64'd0;
            itlb_miss_count <= 64'd0;
            div_stall_count <= 64'd0;
            load_l1_miss_count <= 64'd0;
        end else begin
            cycle_count <= cycle_count + 1;
            
            if (commit_valid)
                commit_instr_count <= commit_instr_count + 1;
            
            if (icache_miss)
                icache_miss_count <= icache_miss_count + 1;
            
            if (itlb_miss)
                itlb_miss_count <= itlb_miss_count + 1;
            
            if (div_stall)
                div_stall_count <= div_stall_count + 1;
            
            if (load_l1_miss)
                load_l1_miss_count <= load_l1_miss_count + 1;
        end
    end

endmodule
```

### 3.3 XiangShan 中的实现

**代码位置**：XiangShan 使用 Chisel 宏自动插入性能计数器

```scala
// Chisel 代码示例
class DispatchCtrl extends Module {
    val io = IO(new Bundle {
        val perfEvents = Output(Vec(16, UInt(64.W)))
    })
    
    // 性能事件
    val noStall = WireDefault(false.B)
    val icacheMissBubble = WireDefault(false.B)
    val divStall = WireDefault(false.B)
    
    // 性能计数器
    val perfCounters = RegInit(VecInit(Seq.fill(16)(0.U(64.W))))
    
    when (noStall) {
        perfCounters(0) := perfCounters(0) + 1.U
    }
    when (icacheMissBubble) {
        perfCounters(1) := perfCounters(1) + 1.U
    }
    when (divStall) {
        perfCounters(2) := perfCounters(2) + 1.U
    }
    
    io.perfEvents := perfCounters
}
```

---

## 4. Top-Down 分析方法

### 4.1 什么是 Top-Down 分析？

**Top-Down 分析**：一种微架构性能分析方法，将 CPU 停顿分为四层。

```
                     总周期数
                         │
         ┌───────────────┼───────────────┐
         │               │               │
         ▼               ▼               ▼
      Frontend         Backend         Bad Spec
     (取指/译码)      (执行/提交)      (错误推测)
```

### 4.2 分析层次

**Level 0: 总周期数**

```
总周期数 = 所有停顿周期 + 无停顿周期
```

**Level 1: 四大类别**

```
总周期数 = Frontend + Backend + BadSpec + Other
```

**Level 2: 前端细分**

```
Frontend = ICacheMiss + ITLBMiss + BTBMiss + FetchFrag + ...
```

**Level 3: 后端细分**

```
Backend = DivStall + RobStall + IntFlStall + LoadL1Stall + ...
```

### 4.3 XiangShan 的 Top-Down 分析

**代码位置**：`scripts/top-down/configs.py`

```python
# Frontend 分析
xs_frontend_rename_map = {
    'OverrideBubble': 'MergeOverrideBubble',
    'FtqFullStall': 'MergeFtqFullStall',
    'ICacheMissBubble': 'MergeICacheMissBubble',
    'ITLBMissBubble': 'MergeITLBMissBubble',
    'BTBMissBubble': 'MergeBTBMissBubble',
    'FetchFragBubble': 'MergeFetchFragBubble',
    # ...
}

# Backend 分析
xs_backend_rename_map = {
    'DivStall': 'MergeExecStall',
    'RobStall': 'MergeRobStall',
    'IntFlStall': 'MergeFreelistStall',
    'FpFlStall': 'MergeFreelistStall',
    'LoadDispatchPolicyStall': 'MergeDispatchPolicy',
    # ...
}

# Memory 分析
xs_mem_rename_map = {
    'LoadTLBStall': 'MergeLoadTLBStall',
    'LoadL1Stall': 'MergeLoadL1Stall',
    'LoadL2Stall': 'MergeLoadL2Stall',
    'LoadL3Stall': 'MergeLoadL3Stall',
    'LoadMemStall': 'MergeLoadMemStall',
    'StoreStall': 'MergeStore',
    # ...
}
```

### 4.4 分析结果示例

```
perlbench Top-Down 分析结果：

总周期数: 100%
├── Frontend (20%)
│   ├── ICacheMiss: 8%
│   ├── ITLBMiss: 2%
│   ├── BTBMiss: 4%
│   └── FetchFrag: 3%
├── Backend (60%)
│   ├── DivStall: 2%
│   ├── RobStall: 8%
│   ├── IntFlStall: 4%
│   ├── LoadL1Stall: 12%
│   ├── LoadL2Stall: 6%
│   └── StoreStall: 4%
├── BadSpec (15%)
│   ├── FlushedInsts: 8%
│   └── ControlRedirect: 6%
└── Other (5%)
```

---

## 5. 性能计数器输出格式

### 5.1 输出格式

**XiangShan 使用 `$fwrite` 输出到 stderr**：

```verilog
// 输出格式
$fwrite(32'h80000002, 
    "[PERF][time=%0d].core0.backend.ctrlBlock.dispatch: NoStall, %0d\n",
    cycle_count, no_stall_count);
```

**输出示例**：

```
[PERF][time=1000000].core0.backend.ctrlBlock.dispatch: NoStall, 650000
[PERF][time=1000000].core0.backend.ctrlBlock.dispatch: ICacheMissBubble, 120000
[PERF][time=1000000].core0.backend.ctrlBlock.dispatch: DivStall, 30000
[PERF][time=1000000].core0.backend.ctrlBlock.rob: commitInstr, 20000000
[PERF][time=1000000].core0.backend.ctrlBlock.rob: clock_cycle, 5000000
```

### 5.2 格式解析

```
[PERF][time=1000000].core0.backend.ctrlBlock.dispatch: NoStall, 650000
    │         │          │    │       │          │         │        │
    │         │          │    │       │          │         │        └── 计数值
    │         │          │    │       │          │         └── 计数器名称
    │         │          │    │       │          └── 模块路径
    │         │          │    │       └── 子模块
    │         │          │    └── 模块
    │         │          └── 核心 ID
    │         └── 时间戳
    └── 标记
```

### 5.3 正则表达式

**提取计数器的正则表达式**（`scripts/top-down/configs.py`）：

```python
XS_CORE_PREFIX = r'\[PERF\s*\]\[time=\s*\d+\].*?\.core'

targets = {
    'commitInstr': fr'{XS_CORE_PREFIX}.backend.*?ctrlBlock.rob: commitInstr,\s+(\d+)',
    'total_cycles': fr'{XS_CORE_PREFIX}.backend.*?ctrlBlock.rob: clock_cycle,\s+(\d+)',
    'NoStall': fr'{XS_CORE_PREFIX}.backend.*?ctrlBlock\.dispatch: NoStall,\s+(\d+)',
    'ICacheMissBubble': fr'{XS_CORE_PREFIX}.backend.*?ctrlBlock\.dispatch: ICacheMissBubble,\s+(\d+)',
    # ...
}
```

---

## 6. 提取脚本详解

### 6.1 提取函数

**代码位置**：`scripts/top-down/utils.py:13-55`

```python
def xs_get_stats(stat_file: str, targets: list) -> dict:
    """
    从 simulator_err.txt 提取性能计数器
    
    参数:
        stat_file: 统计文件路径
        targets: 计数器定义字典
    
    返回:
        计数器值字典
    """
    # 读取文件
    with open(stat_file, encoding='utf-8') as f:
        lines = f.read().splitlines()
    
    # 编译正则表达式
    patterns = {}
    for k, p in targets.items():
        patterns[k] = re.compile(p)
    
    # 提取计数器值
    stats = {}
    for line in lines:
        for k, pattern in patterns.items():
            m = pattern.search(line)
            if m is not None:
                stats[k] = to_num(m.group(1))
                break
    
    # 计算 IPC
    stats['ipc'] = stats['commitInstr'] / stats['total_cycles']
    
    return stats
```

### 6.2 使用示例

```python
# 定义计数器
targets = {
    'commitInstr': r'\[PERF.*?\]: commitInstr,\s+(\d+)',
    'total_cycles': r'\[PERF.*?\]: clock_cycle,\s+(\d+)',
    'NoStall': r'\[PERF.*?\]: NoStall,\s+(\d+)',
}

# 提取计数器
stats = xs_get_stats('simulator_err.txt', targets)

# 输出结果
print(f"IPC: {stats['ipc']:.2f}")
print(f"Commit Instructions: {stats['commitInstr']}")
print(f"Total Cycles: {stats['total_cycles']}")
print(f"No Stall Cycles: {stats['NoStall']}")
```

### 6.3 批量处理

**代码位置**：`scripts/top-down/top_down.py:16-59`

```python
def batch(out_csv_path: str):
    """
    批量提取所有 benchmark 的性能计数器
    """
    # 查找所有统计文件
    paths = u.glob_stats(cf.stats_dir, fname='simulator_err.txt')
    
    # 并行处理
    all_bmk_dict = {}
    for workload, path in paths:
        # 检查是否完成
        flag_file = osp.join(osp.dirname(path), 'simulator_out.txt')
        with open(flag_file, encoding='utf-8') as f:
            contents = f.read()
            if 'EXCEEDING CYCLE/INSTR LIMIT' not in contents and \
               'HIT GOOD TRAP' not in contents:
                print('Skip unfinished job:', workload)
                continue
        
        # 提取计数器
        d = u.xs_get_stats(path, cf.targets)
        if len(d):
            # 解析 workload 和 point
            segments = workload.split('_')
            d['point'] = segments[-1]
            d['workload'] = '_'.join(segments[:-1])
            d['bmk'] = segments[0]
        
        all_bmk_dict[workload] = d
    
    # 保存为 CSV
    df = pd.DataFrame.from_dict(all_bmk_dict, orient='index')
    df = df.sort_index()
    df.to_csv(out_csv_path, index=True)
```

---

## 7. 如何添加自定义计数器

### 7.1 步骤 1：在 RTL 中添加计数器

```verilog
// 在你的 CPU 中添加计数器
reg [63:0] custom_stall_count;

always @(posedge clock) begin
    if (reset) begin
        custom_stall_count <= 64'd0;
    end else begin
        if (custom_stall_signal)
            custom_stall_count <= custom_stall_count + 1;
    end
end
```

### 7.2 步骤 2：添加输出语句

```verilog
// 每 N 个周期输出一次
localparam OUTPUT_INTERVAL = 1000000;

always @(posedge clock) begin
    if (!reset && cycle_count % OUTPUT_INTERVAL == 0) begin
        $fwrite(32'h80000002,
            "[PERF][time=%0d].core0.backend.custom: CustomStall, %0d\n",
            cycle_count, custom_stall_count);
    end
end
```

### 7.3 步骤 3：修改配置文件

```python
# 在 configs.py 中添加
targets = {
    # ... 现有计数器
    'CustomStall': r'\[PERF.*?\]: CustomStall,\s+(\d+)',
}
```

### 7.4 步骤 4：验证输出

```bash
# 运行仿真
./build/emu -i checkpoint.gz 2> simulator_err.txt

# 检查输出
grep "CustomStall" simulator_err.txt
```

---

## 8. 常见问题解答

### 8.1 Q: 性能计数器会影响仿真速度吗？

**A**: 影响很小。

**说明**：
- `$fwrite` 是 I/O 操作，有一定开销
- 但只在特定时刻输出（如每 100 万周期）
- 对整体仿真速度影响 < 1%

### 8.2 Q: 为什么用 `$fwrite` 而不是 CSR 读取？

**A**: 因为仿真环境不同。

**说明**：
- 在真实硬件上，通过 CSR 读取性能计数器
- 在仿真环境中，通过 `$fwrite` 输出更方便
- 可以输出更多调试信息

### 8.3 Q: 性能计数器的精度如何？

**A**: 精确。

**说明**：
- RTL 仿真是精确的
- 每个时钟周期的计数都是准确的
- 没有采样误差

### 8.4 Q: 如何选择要添加的计数器？

**A**: 根据分析需求。

**建议**：
1. 先添加基础计数器（commitInstr, total_cycles）
2. 根据 Top-Down 分析需求添加其他计数器
3. 参考 XiangShan 的计数器列表

### 8.5 Q: 性能计数器和 Trace 有什么区别？

**A**: 不同的调试手段。

| 方面 | 性能计数器 | Trace |
|------|------------|-------|
| **内容** | 统计值 | 详细日志 |
| **大小** | 小 | 大 |
| **用途** | 性能分析 | 功能调试 |
| **精度** | 精确 | 精确 |

---

## 总结

### 性能计数器核心概念

| 概念 | 说明 |
|------|------|
| **性能计数器** | 统计硬件事件的计数器 |
| **Top-Down 分析** | 将 CPU 停顿分为四层的分析方法 |
| **$fwrite** | RTL 中输出性能数据的方法 |
| **正则表达式** | 提取性能数据的模式匹配 |

### 嵌入式工程师的理解要点

1. **性能计数器类似于汽车仪表盘**：帮助监控硬件状态
2. **Top-Down 分析类似于故障排查**：从高层到低层逐步定位问题
3. **$fwrite 类似于 printf**：在仿真中输出调试信息
4. **正则表达式类似于模式匹配**：从文本中提取特定信息

### 实践建议

1. **先理解基础计数器**：commitInstr, total_cycles
2. **理解 Top-Down 分析**：四层分类方法
3. **尝试提取计数器**：使用 Python 脚本
4. **添加自定义计数器**：根据需求扩展
