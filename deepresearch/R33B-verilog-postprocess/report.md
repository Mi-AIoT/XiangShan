# R33B - Verilog 生成与后处理 (Verilog Generation & Post-Processing)

## 概述

XiangShan 的 RTL 生成流程是一条从 Chisel/Scala 源码到可综合 SystemVerilog 的完整流水线，涵盖 FIRRTL 编译 (Chisel-to-FIRRTL)、CIRCT/firtool 降级 (FIRRTL-to-SystemVerilog)、以及一系列后处理步骤 (post-processing)。后处理包括：Git 信息注入为硬件常量、assertion 语句替换、SRAM blackbox 替换、filelist 生成等。本文档深入剖析整个流程中每一个关键环节的实现细节、配置选项和源文件位置。

---

## 1. build.mill — Mill 构建配置与 firtool 集成

### 1.1 构建系统架构

XiangShan 使用 [Mill](https://mill-build.com/) 作为构建系统，核心配置文件为 `build.mill`。Mill 定义了多个模块 (module) 之间的依赖关系：

- **xiangshan** (顶层模块)：依赖 `rocket-chip`、`difftest`、`XSCache`、`yunsuan`、`utility`、`ChiselAIA`、`ChiselIOPMP`、`macros`
- **rocket-chip**：子模块包含 `macros`、`hardfloat`、`cde`

### 1.2 Chisel 与 firtool 版本管理

```scala
import $ivy.`org.chipsalliance::chisel:7.3.0`
import $ivy.`org.chipsalliance::firtool-resolver:2.0.1`
```

Chisel 版本固定为 7.3.0。firtool (CIRCT 工具链) 通过 `firtool-resolver` 自动解析和下载，版本号由 `chisel3.BuildInfo.firtoolVersion` 提供：

```scala
def resolveFirtoolDeps = T {
  firtoolresolver.Resolve(chisel3.BuildInfo.firtoolVersion.get, true) match {
    case Right(bin) => bin.path.getAbsolutePath
    case Left(err)  => err
  }
}
```

Makefile 中允许用户通过 `FIRTOOL` 变量覆盖 firtool 二进制路径：
```makefile
ifneq ($(FIRTOOL),)
MFC_ARGS += --firtool-binary-path $(abspath $(FIRTOOL))
endif
```

### 1.3 JVM 配置

构建过程需要大量 JVM 内存，通过两个维度配置：

- **build.mill** 中 `xiangshan` 模块的 `forkArgs`：
  ```scala
  def forkArgs = Seq(
    s"-Xmx${sys.props.getOrElse("jvm-xmx", "40G")}",
    s"-Xss${sys.props.getOrElse("jvm-xss", "256m")}"
  )
  ```
- **Makefile** 中 `JVM_XMX` / `JVM_XSS` 变量，默认 `40G` / `256m`，通过 `MILL_BUILD_ARGS` 传递给 Mill：
  ```makefile
  JVM_XMX ?= 40G
  JVM_XSS ?= 256m
  MILL_BUILD_ARGS = -Djvm-xmx=$(JVM_XMX) -Djvm-xss=$(JVM_XSS)
  ```

### 1.4 XiangShanStage — 自定义 Chisel 编译流水线

文件 `src/main/scala/top/XiangShanStage.scala` 定义了 `XiangShanStage`，继承自 `ChiselStage`，覆写了 `run` 方法以注入自定义 phase：

```scala
override def run(annotations: firrtl.AnnotationSeq): firrtl.AnnotationSeq = {
  val pm = new firrtl.options.PhaseManager(
    targets = Seq(
      Dependency[chisel3.stage.phases.AddImplicitOutputFile],
      Dependency[chisel3.stage.phases.AddImplicitOutputAnnotationFile],
      Dependency[chisel3.stage.phases.AddSerializationAnnotations],
      Dependency[chisel3.stage.phases.xiangshan.PrintModuleName],  // 自定义：打印模块名
      Dependency[chisel3.stage.phases.Convert],
      Dependency[chisel3.stage.phases.AddDedupGroupAnnotations],
      Dependency[circt.stage.phases.AddImplicitOutputFile],
      Dependency[circt.stage.phases.CIRCT]  // 调用 CIRCT/firtool
    ), ...
  )
  pm.transform(annotations)
}
```

其中 `PrintModuleName` 是 XiangShan 自定义的 phase，用于在 FIRRTL 转换阶段打印模块名称信息。

### 1.5 publishVersion — 版本号生成

`build.mill` 中的 `xiangshan` 模块通过 `VcsVersion.vcsState()` 生成版本号：

```scala
private def publishVersion: T[String] = VcsVersion.vcsState().format(
  revHashDigits = 8,
  dirtyHashDigits = 0,
  commitCountPad = -1,
  countSep = "",
  tagModifier = (tag: String) =>
    "[Rr]elease.*".r.findFirstMatchIn(tag) match {
      case Some(_) => "KunminghuV3-Release-" + LocalDateTime.now()
          .format(DateTimeFormatter.ofPattern("MMM-dd-yyyy").withLocale(publishLocale))
      case None => "KunminghuV3-dev"
    },
  untaggedSuffix = " (%s@%s) # %s".format(
    System.getProperty("user.name"),
    java.net.InetAddress.getLocalHost.getHostName,
    LocalDateTime.now()
      .format(DateTimeFormatter.ofPattern("MMM dd hh:mm:ss yyyy").withLocale(publishLocale))
  )
)
```

版本号格式为：release tag 匹配时生成 `KunminghuV3-Release-Jun-09-2026`；非 release 时生成 `KunminghuV3-dev (username@hostname) # Jun 09 03:45:12 2026`。

---

## 2. Split SystemVerilog 输出

### 2.1 firtool 选项配置

Makefile 中定义了核心 firtool 参数 `MFC_ARGS`：

```makefile
MFC_ARGS = --target $(CHISEL_TARGET) \
           --firtool-opt "-O=release \
             --disable-annotation-unknown \
             --lowering-options=explicitBitcast,disallowLocalVariables,\
               disallowPortDeclSharing,locationInfoStyle=none"
```

关键选项含义：
- `--target systemverilog`：输出 SystemVerilog（默认 `CHISEL_TARGET=systemverilog`）
- `-O=release`：release 级别优化（完全展开、内联、常量传播等）
- `--disable-annotation-unknown`：忽略未知的 Chisel annotation，不报错
- `--lowering-options`：控制 CIRCT 降级行为
  - `explicitBitcast`：显式 bitcast 转换
  - `disallowLocalVariables`：不允许生成 local variable（更利于综合）
  - `disallowPortDeclSharing`：不允许端口声明共享
  - `locationInfoStyle=none`：不输出源码位置信息

### 2.2 Split 模式

```makefile
ifeq ($(CHISEL_TARGET),systemverilog)
MFC_ARGS += --split-verilog --dump-fir
endif
```

- `--split-verilog`：firtool 将每个 Chisel module 输出为独立的 `.sv` 文件（而非合并为单个大文件），便于并行编译和增量综合
- `--dump-fir`：同时输出 FIRRTL 中间表示文件 (`.fir`)

### 2.3 Release vs Debug 模式

```makefile
RELEASE_ARGS += --fpga-platform --reset-gen \
  --firtool-opt --ignore-read-enable-mem \
  --firtool-opt "--default-layer-specialization=disable"

# 非 FPGA、非 PLDM 时：
DEBUG_ARGS += --enable-difftest \
  --firtool-opt "--default-layer-specialization=enable"
```

Release 模式启用 FPGA 平台优化 (`--fpga-platform`)、复位生成器 (`--reset-gen`)，关闭 layer specialization（减少不必要的模块层次）。Debug 模式启用 difftest 框架，开启 layer specialization。

---

## 3. CommitIDModule — Git SHA 作为硬件常量

### 3.1 设计目标

`CommitIDModule`（文件 `src/main/scala/xiangshan/backend/fu/NewCSR/CommitIDModule.scala`）将当前 Git commit SHA 编码为硬件常量，嵌入处理器 CSR (Control and Status Register) 中。这样，运行中的处理器可以报告其固件版本，极大方便了调试和版本追踪。

### 3.2 实现细节

```scala
class CommitIDModule(shaWidth: Int, hartIdlen: Int) extends Module {
  val io = IO(new Bundle {
    val hartId = Input(UInt(hartIdlen.W))
    val commitID = Output(UInt(shaWidth.W))
    val dirty    = Output(Bool())
  })

  val props = new Properties()
  props.load((os.resource / "gitStatus").getInputStream)

  val sha = props.get("SHA").asInstanceOf[String].take(shaWidth / 4)
  val dirty = props.get("dirty").asInstanceOf[String].toInt

  io.commitID := BigInt(sha, 16).U(shaWidth.W)
  io.dirty := dirty.U
}
```

工作原理：
1. 在 Scala 编译时 (Chisel elaboration 阶段)，从 classpath 资源中读取 `gitStatus` 文件
2. `gitStatus` 文件由 `build.mill` 的 `resources` task 生成，包含 `SHA=<full-commit-hash>` 和 `dirty=0|1`
3. 取 SHA 的前 40 位（`shaWidth / 4 = 10` 个十六进制字符 = 40 bit），转换为 `UInt` 硬件常量
4. `dirty` 标志表示工作区是否有未提交的修改

### 3.3 仿真打印模块

```scala
class PrintCommitIDModule(shaWidth: Int, hartIdlen: Int)
    extends BlackBox with HasBlackBoxInline {
  setInline("PrintCommitIDModule.v",
    s"""
    |module PrintCommitIDModule(
    |  input [${hartIdlen-1}:0] hartID,
    |  input [${shaWidth-1}:0] commitID,
    |  input dirty
    |);
    |`ifndef SYNTHESIS
    |  initial begin
    |    $$fwrite(32'h80000001, "Core %d's Commit SHA is: %h, dirty: %d\\n",
    |             hartID, commitID, dirty);
    |  end
    |`endif
    |endmodule
    """.stripMargin)
}
```

`PrintCommitIDModule` 是一个内联 Verilog blackbox，在仿真初始化时通过 `$fwrite` 输出 commit SHA 到 stdout (文件描述符 `32'h80000001`)。`ifndef SYNTHESIS` 确保综合时被消除。

### 3.4 在 CSR 中的集成

在 `NewCSR.scala` (line ~307) 中：

```scala
val commidIdMod = Module(new CommitIDModule(40, hartIdLen))
commidIdMod.io.hartId := io.fromTop.hartId
val gitCommitSHA = WireInit(commidIdMod.io.commitID)
val gitDirty     = WireInit(commidIdMod.io.dirty)
dontTouch(gitCommitSHA)
dontTouch(gitDirty)
```

`dontTouch` 确保这两个信号不会被优化掉，即使没有被直接读取。

---

## 4. Git 信息注入 (log, diff 作为 Verilog 注释)

### 4.1 机制

在 Makefile 的 `$(TOP_V)` 和 `$(SIM_TOP_V)` 构建规则中，verilog 生成完成后执行以下步骤：

```makefile
ifeq ($(CHISEL_TARGET),systemverilog)
  @{ git log -n 1; git diff; } | sed 's/^/\/\// ' > $(dir $@).__diff__
  @cat $(dir $@).__diff__ $@ > $(dir $@).__out__ && mv $(dir $@).__out__ $@
endif
```

执行流程：
1. `git log -n 1` 输出最新 commit 的完整信息（commit hash、author、date、message）
2. `git diff` 输出当前工作区与 HEAD 之间的差异（uncommitted changes）
3. `sed 's/^/\/\// '` 将每行开头加上 `//`，转换为 Verilog 注释
4. 写入临时文件 `__diff__`
5. 将 `__diff__` 内容拼接到生成的 `.sv` 文件**头部**，替换原文件

### 4.2 目的

这确保了生成的每个 SystemVerilog 文件头部都包含生成该文件时的精确 Git 状态，包括：
- 精确的 commit SHA 和 commit message
- 任何本地未提交的修改 diff

这对于追踪 RTL 版本、调试综合差异至关重要。

---

## 5. $fatal 替换策略

### 5.1 问题背景

Chisel 7.x / CIRCT firtool 在生成 SystemVerilog 时，会将 Scala 的 `assert` / `require` / `chisel3.assert` 降级为 SystemVerilog 的 `$fatal` 语句。然而：
- `$fatal` 会立即终止仿真，不利于调试
- 某些 EDA 工具 (如 Palladium) 不完全支持 `$fatal`
- 需要灵活控制 assertion 行为（仅在仿真开始后才启用 assertion）

### 5.2 三种替换策略

在 `$(SIM_TOP_V)` 规则中，Makefile 根据构建目标选择不同的替换策略：

```makefile
ifeq ($(PLDM),1)
  # Palladium: $fatal -> $finish
  sed -i -e 's/$$fatal/$$finish/g' $(RTL_DIR)/*.$(RTL_SUFFIX)
else
ifeq ($(ENABLE_XPROP),1)
  # XProp 模式: $fatal -> assert(1'b0)
  sed -i -e "s/\$$fatal/assert(1\'b0)/g" $(RTL_DIR)/*.$(RTL_SUFFIX)
else
  # 默认: $fatal -> xs_assert_v2(`__FILE__, `__LINE__)
  sed -i -e 's/$$fatal/xs_assert_v2(`__FILE__, `__LINE__)/g' $(RTL_DIR)/*.$(RTL_SUFFIX)
endif
endif
```

| 场景 | 替换规则 | 目标 |
|------|---------|------|
| **PLDM=1** (Palladium 仿真) | `$fatal` -> `$finish` | Palladium 对 `$finish` 支持更好，优雅终止仿真 |
| **ENABLE_XPROP=1** (XProp 模式) | `$fatal` -> `assert(1'b0)` | XProp 工具需要标准 SystemVerilog assert 语句 |
| **默认** (Verilator/VCS/Galaxsim) | `$fatal` -> `xs_assert_v2(\`__FILE__, \`__LINE__)` | 通过 DPI-C 调用 C++ 函数，实现可控的 assertion 行为 |

### 5.3 $error 替换

所有模式下，还有统一的 `$error` 替换：

```makefile
sed -i -e "s/\$$error(/\$$fwrite(32'h80000002, /g" $(RTL_DIR)/*.$(RTL_SUFFIX)
```

将 `$error(...)` 替换为 `$fwrite(32'h80000002, ...)`，其中 `32'h80000002` 是 stderr 文件描述符。这使得错误信息输出到 stderr 而不是触发仿真器的特殊错误处理。

### 5.4 xs_assert_v2 的 C++ 实现

文件 `difftest/src/test/csrc/common/common.cpp`：

```cpp
int assert_count = 0;

void xs_assert(long long line) {
  if (assert_count >= 0) {
    printf("Assertion failed at line %lld.\n", line);
    assert_count++;
  }
}

void xs_assert_v2(const char *filename, long long line) {
  if (assert_count >= 0) {
    printf("Assertion failed at %s:%lld.\n", filename, line);
    assert_count++;
  }
}
```

关键设计：
- `assert_count` 控制 assertion 是否生效：`>=0` 时生效，`-1` 时静默
- 仿真启动初期（硬件未初始化时），调用 `common_init_without_assertion()` 将 `assert_count` 设为 `-1`，抑制 false assertion
- 硬件就绪后，调用 `common_enable_assert()` 将 `assert_count` 重置为 `0`，启用 assertion
- assertion 失败时**不终止仿真**，仅打印信息并增加计数器

Verilog 端的 DPI-C 声明在 `difftest/src/test/vsrc/common/assert.v`：
```verilog
import "DPI-C" function void xs_assert(input longint line);
import "DPI-C" function void xs_assert_v2(input string filename, input longint line);
```

### 5.5 Palladium 特殊处理

PLDM 模式还有额外的 `ifdef SYNTHESIS` 去除逻辑：

```makefile
ifeq ($(PLDM),1)
  SED_IFNDEF = `ifndef SYNTHESIS  // src/main/scala/device/RocketDebugWrapper.scala
  SED_ENDIF  = `endif // not def SYNTHESIS
  ...
  sed -i -e '/sed/! { \|$(SED_IFNDEF)|, \|$(SED_ENDIF)| { \|$(SED_IFNDEF)|d; \|$(SED_ENDIF)|d; } }' \
    $(RTL_DIR)/*.$(RTL_SUFFIX)
endif
```

这会删除 `RocketDebugWrapper.scala` 生成的 RTL 中的 `` `ifndef SYNTHESIS ... `endif `` 条件编译块，因为 Palladium 不需要这些 debug wrapper。

---

## 6. SRAM 替换 (ifdef FOUNDRY_MEM)

### 6.1 背景

XiangShan 在仿真中使用 Chisel 生成的 SRAM 行为级模型 (behavioral SRAM)，但在流片 (tapeout) 时需要用 foundry 提供的 SRAM macro 替换。`scripts/parser.py` 实现了这一替换流程。

### 6.2 SRAM 命名规范

SRAM 模块名称遵循正则：
```python
ARRAY_NAME = "sram_array_(\d)p(\d+)x(\d+)m(\d+)(_multicycle|)(_repair|)"
```

解析规则：`sram_array_{ports}p{depth}x{width}m{mask_gran}[_multicycle][_repair]`

例如 `sram_array_2p1024x64m8_repair` 表示：双端口、深度 1024、宽度 64、mask granularity 8、带 repair 功能。

端口类型映射：

| 端口数 | mask_width | 类型 |
|--------|-----------|------|
| 1 | 1 | SINGLE_PORT (rw) |
| 1 | >1 | SINGLE_PORT_MASK (mrw) |
| 2 | 1 | DUAL_PORT (write,read) |
| 2 | >1 | DUAL_PORT_MASK (mwrite,read) |

### 6.3 replace_with_macro 机制

`VModule.replace_with_macro` 方法实现 `ifdef` 包裹：

```python
def replace_with_macro(self, macro, s):
    replaced_lines = []
    in_io, in_body = False, False
    for line in self.lines:
        if self.io_re.match(line):
            in_io = True
            replaced_lines.append(line)
        elif in_io:
            in_io = False
            in_body = True
            replaced_lines.append(line)  # ");"
            replaced_lines.append(f"`ifdef {macro}\n")
            replaced_lines.append(s)     # foundry SRAM wrapper instantiation
            replaced_lines.append(f"`else\n")
        elif in_body:
            if line.strip() == "endmodule":
                replaced_lines.append(f"`endif // {macro}\n")
            replaced_lines.append(line)
        else:
            replaced_lines.append(line)
```

生成的 Verilog 结构如下：

```verilog
module sram_array_2p1024x64m8 (
  input RW0_clk,
  input [9:0] RW0_addr,
  ...
);
`ifdef FOUNDRY_MEM
  RAMSP_1024x64_WRAP u_mem (
    .CK(RW0_clk),
    .A(RW0_addr),
    ...
  );
`else
  // original behavioral SRAM body
  reg [63:0] mem [0:1023];
  ...
`endif // FOUNDRY_MEM
endmodule
```

### 6.4 Foundry SRAM Wrapper 生成

`SRAMConfiguration.get_foundry_sram_wrapper` 方法根据 SRAM 配置生成 foundry wrapper 的实例化代码：

- 单端口 SRAM：wrapper 类型 `RAMSP_{depth}x{width}[_M{mask}]_WRAP`
- 双端口 SRAM：wrapper 类型 `RF2P_{depth}x{width}[_M{mask}]_WRAP`

端口映射包括：
- **功能端口**：`CK`/`WCK`/`RCK` (时钟)、`A`/`WA`/`RA` (地址)、`WEN`/`REN` (使能)、`D`/`Q` (数据)
- **MBIST 端口**：`FSCAN_RAM_BYPSEL`、`FSCAN_RAM_WDIS_B`、`FSCAN_RAM_RDIS_B` 等
- **DFT 端口**：`TRIM_FUSE_IN`、`SLEEP_FUSE_IN`、`PWR_MGMT_IN/OUT`
- **Repair 端口**（如果有）：`ROW_REPAIR_IN`、`COL_REPAIR_IN`、BISR 相关口

### 6.5 替换流程

```python
def replace_sram(out_dir, sram_conf, top_module, module_prefix):
    # 1. 读取 sram_configuration.txt 中的每个 SRAM 配置
    # 2. 加载对应的仿真 SRAM 模块 Verilog 文件
    # 3. 提取 MBIST 类型
    # 4. 生成 foundry SRAM wrapper 实例化代码
    # 5. 调用 replace_with_macro("FOUNDRY_MEM", instantiation_v)
    # 6. 输出替换后的文件到 memory_array/ 目录
    # 7. 生成 memory_wrapper.f filelist
```

---

## 7. Filelist 生成

### 7.1 parser.py 的 filelist 逻辑

`scripts/parser.py` 中的 `create_filelist` 函数生成 `.f` 文件：

```python
def create_filelist(filelist_name, out_dir, file_dirs=None, extra_lines=[]):
    filelist_entries = []
    for file_dir in file_dirs:
        for filename in os.listdir(os.path.join(out_dir, file_dir)):
            if filename.endswith(".v") or filename.endswith(".sv"):
                filelist_entry = os.path.join(file_dir, filename)
                if filelist_entry in filelist_entries:
                    print(f'[warning]: {filelist_entry} is already in filelist_entries')
                else:
                    filelist_entries.append(filelist_entry)
    with open(os.path.join(out_dir, f"{filelist_name}.f"), "w") as f:
        for entry in filelist_entries + extra_lines:
            f.write(f"{entry}\n")
```

### 7.2 多种 filelist 输出

parser.py 主流程根据选项生成不同的 filelist：

1. **基础 filelist** (`{top_module}.f`)：包含所有 RTL 模块
2. **带 foundry SRAM 的 filelist** (`{top_module}_with_foundry_sram.f`)：额外包含 `memory_array/` 目录和 `memory_wrapper.f`
3. **MBIST/Scan controller filelist** (`dfx_blackbox.f`)：包含替换后的 DFT blackbox
4. **SRAM wrapper filelist** (`memory_wrapper.f`)：占位文件，需手动添加 foundry 提供的 SRAM wrapper

### 7.3 优先级机制

filelist 中，不同 `file_dirs` 的顺序决定了模块优先级。如果同一个 `.v` 文件出现在多个目录中，只保留第一次出现的版本。SRAM 替换目录放在最前面，确保 foundry SRAM 版本优先于仿真版本。

---

## 8. Post-processing Makefile Targets

### 8.1 verilog target (Release RTL)

```makefile
$(TOP_V): $(SCALA_FILE)
    mkdir -p $(@D)
    $(TIME_CMD) mill -i $(MILL_BUILD_ARGS) xiangshan.runMain $(FPGATOP) \
        --target-dir $(@D) --config $(CONFIG) --issue $(ISSUE) \
        --num-cores $(NUM_CORES) $(TOPMAIN_ARGS)
    # git info injection (only for systemverilog target)
    @{ git log -n 1; git diff; } | sed 's/^/\/\// ' > $(dir $@).__diff__
    @cat $(dir $@).__diff__ $@ > $(dir $@).__out__ && mv $(dir $@).__out__ $@
```

- **入口点**：`top.TopMain` (FPGATOP)
- **输出**：`build/rtl/{prefix}XSTop.sv`
- **后处理**：Git log + diff 注入为头部注释

### 8.2 sim-verilog target (仿真 RTL)

```makefile
$(SIM_TOP_V): $(SCALA_FILE) $(TEST_FILE)
    mkdir -p $(@D)
    $(TIME_CMD) mill -i $(MILL_BUILD_ARGS) xiangshan.test.runMain $(SIMTOP) \
        --target-dir $(@D) --config $(CONFIG) --issue $(ISSUE) \
        --num-cores $(NUM_CORES) $(SIM_ARGS) --full-stacktrace
    # git info injection
    @{ git log -n 1; git diff; } | sed 's/^/\/\// ' > $(dir $@).__diff__
    @cat $(dir $@).__diff__ $@ > $(dir $@).__out__ && mv $(dir $@).__out__ $@
    # $fatal replacement (3 strategies)
    # $error replacement
```

- **入口点**：`top.XiangShanSim` (SIMTOP)
- **输出**：`build/rtl/SimTop.sv`
- **后处理**：Git 注入 + `$fatal` 替换 + `$error` 替换

### 8.3 编译目标

| Target | 描述 |
|--------|------|
| `verilog` | 生成 Release RTL（默认目标） |
| `sim-verilog` | 生成仿真 RTL（含 assertion 后处理） |
| `comp` | 仅编译 Scala（`xiangshan.compile` + `xiangshan.test.compile`） |
| `jar` / `test-jar` | 构建可执行 JAR |
| `help` / `version` | 打印帮助/版本信息 |
| `clean` | 清理构建产物 |

### 8.4 仿真目标

| Target | 工具 |
|--------|------|
| `emu` | Verilator emulator |
| `simv` | Synopsys VCS |
| `xsim` | Xilinx Vivado xsim |
| `pldm-build` / `pldm-run` | Cadence Palladium |
| `gsim` | GalaxSim |

所有仿真目标都依赖 `sim-verilog`，通过 `$(call docker-deps, ...)` 支持 Docker 构建环境。

### 8.5 Release 构建参数

当 `RELEASE=1` 时，SIM_ARGS 包含 `RELEASE_ARGS`：
```makefile
RELEASE_ARGS += --fpga-platform --reset-gen \
    --firtool-opt --ignore-read-enable-mem \
    --firtool-opt "--default-layer-specialization=disable"
```

- `--fpga-platform`：FPGA 平台标志（禁用某些仿真特性）
- `--reset-gen`：启用复位生成器
- `--ignore-read-enable-mem`：忽略 SRAM 读使能优化
- `--default-layer-specialization=disable`：禁用 layer specialization

---

## 9. Source 文件位置汇总

| 文件路径 | 功能描述 |
|---------|---------|
| `build.mill` | Mill 构建配置，Chisel/firtool 依赖，版本号生成 |
| `Makefile` | Verilog 生成 target，firtool 参数，后处理 sed 命令 |
| `Makefile.test` | 测试相关构建规则 |
| `scripts/Makefile.docker` | Docker 构建环境支持 |
| `scripts/Makefile.pdb` | PDB (Platform Debug Bridge) 支持 |
| `src/main/scala/top/XiangShanStage.scala` | 自定义 ChiselStage，注入 PrintModuleName phase |
| `src/main/scala/top/Generator.scala` | FIRRTL/CIRCT 编译入口 |
| `src/main/scala/top/ArgParser.scala` | 命令行参数解析（含 firtool-opt 透传） |
| `src/main/scala/top/Top.scala` | SoC 顶层，DTS 中嵌入 publishVersion |
| `src/main/scala/xiangshan/backend/fu/NewCSR/CommitIDModule.scala` | Git SHA -> 硬件常量 |
| `src/main/scala/xiangshan/backend/fu/NewCSR/NewCSR.scala` | CSR 模块，实例化 CommitIDModule |
| `difftest/src/test/vsrc/common/assert.v` | DPI-C 声明 xs_assert / xs_assert_v2 |
| `difftest/src/test/csrc/common/common.h` | xs_assert C++ 函数声明 |
| `difftest/src/test/csrc/common/common.cpp` | xs_assert / xs_assert_v2 C++ 实现 |
| `scripts/parser.py` | SRAM 替换、filelist 生成、模块解析 |

---

## 10. 端到端流程总结

完整的 RTL 生成流程如下：

```
Scala/Chisel 源码
    |
    v  (Mill + Chisel 编译)
FIRRTL (.fir)
    |
    v  (firtool / CIRCT)
Split SystemVerilog (.sv, 每个模块一个文件)
    |
    v  (Makefile post-processing)
    +-- Git log + diff 注入为 Verilog 注释 (头部)
    +-- $fatal -> xs_assert_v2 / $finish / assert(1'b0)
    +-- $error -> $fwrite(stderr)
    +-- PLDM: 去除 SYNTHESIS ifdef 块
    |
    v  (可选: scripts/parser.py)
    +-- 提取 SRAM 配置 (sram_configuration.txt)
    +-- 替换 SRAM: behavioral -> ifdef FOUNDRY_MEM wrapper
    +-- 替换 MBIST/Scan controller
    +-- 生成 filelist (.f)
    +-- 生成 SRAM Excel 报告
    |
    v
可综合 RTL + Filelist (交付给后端/综合团队)
```

### 关键设计决策

1. **Git 信息双重注入**：`build.mill` 通过 resources 注入 `gitStatus`/`publishVersion`/`gitModules` 到 Scala classpath（供 CommitIDModule 使用）；`Makefile` 通过 sed 注入 git log/diff 到 Verilog 文件头部（供 RTL 审查使用）
2. **Assertion 可控性**：默认模式下 `$fatal` 被替换为 `xs_assert_v2` DPI-C 调用，配合 `assert_count` 机制实现"延迟启用 assertion"，避免硬件未初始化时的 false assertion
3. **SRAM 可替换性**：通过 `ifdef FOUNDRY_MEM` 宏实现仿真/流片 SRAM 的无缝切换，无需修改 RTL 源码
4. **构建目标分离**：Release (FPGA/综合) 和 Debug (仿真) 使用不同的 firtool 选项和后处理策略，最大化各自场景的效率
