# XiangShan Analysis Tools Deep Dive Report (R29B)

## Overview

XiangShan (香山) RISC-V processor project includes a comprehensive suite of Python-based analysis tools under `scripts/`. These tools cover RTL parsing and release packaging, genetic-algorithm-driven microarchitecture parameter optimization, top-down performance analysis with weighted benchmark scoring, pipeline lifetime visualization, rolling performance counter analysis with correlation, code coverage analysis, and cache subsystem SQLite database querying. This report provides an in-depth examination of each tool's architecture, algorithms, and role within the XiangShan development workflow.

---

## 1. parser.py -- Verilog RTL Parser and Release Packaging Tool

**Source location:** `/home/agi/workspace/gitwork/XiangShan/scripts/parser.py`

### 1.1 Core Data Structures

The parser is built around three primary classes that model the Verilog module hierarchy:

**VIO (Verilog Input/Output)** represents a single port (input or output) of a Verilog module. It parses the direction (`input`/`output`), width (extracted from bracket notation like `[63:0]`), and signal name. The width calculation handles scalar ports (no bracket notation) by defaulting to 0 then adding 1, effectively making a single-bit signal width=1. VIO supports comparison (`__lt__`), string representation, and prefix-based filtering via `startswith()`.

**VModule** models a single Verilog module with full line-level tracking. It uses compiled regex patterns for structural parsing:

- `module_re`: matches `module` declarations with optional parameter lists
- `io_re`: matches `input`/`output` declarations with optional width brackets
- `submodule_re`: matches single-line submodule instantiations
- `submodule_re_multiline`: handles multi-line instantiations that end with `#(` or `(`
- `difftest_module_re`: specifically identifies Difftest simulation modules

VModule maintains the complete source line list (`self.lines`), an IO list (`self.io`), a submodule dictionary (mapping submodule type names to instance counts), and an instance set of `(module_type, instance_name)` tuples. A key feature is the `add_line()` method which applies synthetic `ifndef SYNTHESIS` guards around Difftest modules and debug signal assignments. For `RenameTable` modules, `io_debug_rdata_*` signals are wrapped; for `SynRegfileSlice` modules, `io_debug_ports_*` signals are wrapped. This ensures debug-only logic is excluded from synthesis.

The `replace_with_macro()` method enables SRAM replacement by inserting a configurable macro guard (`ifdef MACRO ... else ... endif`) around the module body, keeping the IO ports intact while replacing the implementation.

**VCollection** is the top-level container that loads an entire Verilog file, parsing all modules sequentially. It handles edge cases like lines appearing before the first `module` declaration (which are attached to the first module found). The `get_module()` method performs recursive depth-first traversal of the module hierarchy, supporting `try_prefix` for modules with optional name prefixes, `ignore_modules` for excluding sub-trees (e.g., Difftest), and `negedge_prefix` for collecting negedge clock domain modules.

### 1.2 SRAM Replacement Pipeline

The `SRAMConfiguration` class parses SRAM array names following the pattern `sram_array_(\\d)p(\\d+)x(\\d+)m(\\d+)(_multicycle|)(_repair|)` where groups encode port count, depth, width, mask granularity, multi-cycle support, and repair capability. Four port configurations are supported: SINGLE_PORT, SINGLE_PORT_MASK, DUAL_PORT, DUAL_PORT_MASK.

The `get_foundry_sram_wrapper()` method generates Verilog instantiation code for foundry-specific SRAM wrappers. It maps functional ports (CK, A, WEN, D, REN, Q, WM) and foundry-specific MBIST/Test ports (IP_RESET_B, PWR_MGMT_IN, TRIM_FUSE_IN, SLEEP_FUSE_IN, FSCAN_* pins, ROW_REPAIR_IN, COL_REPAIR_IN, etc.) to the correct wrapper module. Wrapper types include `RAMSP` (single-port) and `RF2P` (register-file 2-port), with width-based mask suffixes.

The complete SRAM replacement flow (`replace_sram`) reads simulation SRAM modules, extracts MBIST type information, generates foundry wrapper instantiations, and replaces the simulation implementation with the wrapper under `ifdef FOUNDRY_MEM` guards.

### 1.3 Release Packaging

The `create_verilog()` function orchestrates the full release packaging: it loads all `.v`/`.sv` files from the build directory, extracts the top-level module with its entire submodule hierarchy, and dumps each module to a separate file. The output directory is named `{top_module}-Release-{config}-{date}`. Additional outputs include a filelist (`.f` file), negedge module list for scan synthesis, SRAM configuration text file, SRAM summary Excel spreadsheet (using `xlsxwriter`), and optionally foundry MBIST/scan controller replacements.

The `check_data_module_template()` function validates that all `(Sync|Async)DataModuleTemplate*` modules have symmetric read/write data ports, catching mismatches between `io_wdata_*` and `io_rdata_*` fields that would indicate synthesis issues.

### 1.4 Command-Line Interface

The parser accepts arguments for: `--top` (top module), `--prefix` (module name prefix for multi-configuration builds), `--config` (XSConfig name), `--ignore` (comma-separated modules to skip), `--include` (additional Verilog directories), `--sram-replace` (enable foundry SRAM replacement), `--mbist-scan-replace` (replace MBIST/scan controllers), and flags to skip filelist/sram-conf/sram-xlsx generation.

---

## 2. constantHelper.py -- Genetic Algorithm Parameter Optimizer

**Source location:** `/home/agi/workspace/gitwork/XiangShan/scripts/constantHelper.py`

### 2.1 Architecture Overview

This tool implements a genetic algorithm (GA) framework for automatically optimizing microarchitecture constants (hardcoded parameters in the RTL). It reads a JSON configuration file specifying the constants to optimize, their bit-widths, optimization targets, and GA hyperparameters, then iteratively evaluates candidate configurations using the XiangShan emulator.

### 2.2 Data Model

**Constant** represents a single optimization variable with `name`, `width` (bit-width), `guide` (effective range upper bound, defaults to `2^(width-1)-1`), and `init` (initial value, defaults to random within guide range). The `maxrange()` method returns `(1 << width) - 1`, the theoretical maximum.

**Config** aggregates all constants, optimization targets, and GA parameters:
- `population_num`: number of individuals in each generation (must be even)
- `iteration_num`: total number of generations
- `crossover_rate`: probability (%) for crossover operation
- `mutation_rate`: probability (%) for mutation operation
- `emu_threads`, `concurrent_emu`: emulator parallelism settings
- `max_instr`, `seed`: emulation parameters
- `work_load`: path to the benchmark binary

Optimization targets specify metrics to maximize or minimize, each with a `baseline` value. Examples include `successfully_forward_channel_D` (maximize) and `dcache.missQueue.entries_0: load_miss_penalty_to_use` (minimize with baseline 250396).

### 2.3 Genetic Algorithm Implementation

**Initial Population Generation** (`genFirstPopulation`): Creates `population_num` random individuals. Each individual is a list of `[constant_name, value]` pairs where values are randomly generated within the guide range, with deduplication to ensure uniqueness. The initial user-specified configuration is injected as the last individual.

**Fitness Evaluation** (`profilling_fitness`): After running all individuals in a generation, parses emulator stderr output for performance counter values. For each optimization target, if the metric name appears in the output line, extracts the numeric value and computes a weighted contribution: for `max` policy, `value - baseline`; for `min` policy, `baseline - value`. The sum across all targets forms the fitness score.

**Selection** (`genNextPop`): Sorts the current population by fitness (descending), keeps the top half directly, and applies crossover to the bottom half.

**Crossover** (`crossover`): For each individual, iterates through constants. With probability `crossover_rate`, selects a random donor individual and exchanges a random bit-range segment. The mask width is capped at `min(log2(guide)+1, width)` and the shift position is randomized. The result is taken modulo `guide` to stay within bounds.

**Mutation** (`mutation`): For each constant, generates a random bit mask of width `width`. With probability `mutation_rate`, flips the selected bit using XOR with the mask, then applies modulo `guide`.

**Global Best Tracking**: Uses `HashList` (a hashable list wrapper) as dictionary keys to track the best fitness encountered across all generations, printing the global optimum at the end.

### 2.4 Execution Infrastructure

`RunContext` manages emulator execution with NUMA-aware core allocation. It uses `psutil.cpu_percent()` to detect free CPU cores, respecting both physical core count and NUMA topology (splitting at the halfway point). The emulator command is constructed via `echo` piping constant values through stdin, using `--cst-file stdin` to pass the configuration. Output is directed to `{iter}-{individual}-out.txt` and `{iter}-{individual}-err.txt` files under a timestamped tag directory.

### 2.5 Usage

Run as: `python3 constantHelper.py JSON_FILE_PATH [BUILD_PATH]`. The JSON file structure is documented inline with a reference to the XiangShan Yuque documentation.

---

## 3. Top-Down Performance Analysis Framework

**Source locations:**
- `/home/agi/workspace/gitwork/XiangShan/scripts/top-down/top_down.py`
- `/home/agi/workspace/gitwork/XiangShan/scripts/top-down/draw.py`
- `/home/agi/workspace/gitwork/XiangShan/scripts/top-down/configs.py`

### 3.1 Overview

The top-down analysis framework implements a hierarchical performance bottleneck decomposition methodology. It extracts 50+ microarchitecture events from simulation logs, applies configurable renaming maps to group related events, computes weighted benchmark metrics using SPECCPU 2006 workloads, and produces stacked bar chart visualizations. The framework supports comparing two configurations (base vs. ref) with automatic issue-width normalization.

### 3.2 Event Extraction and Metrics

`configs.py` defines over 50 named events extracted via regex patterns from simulation output. The log format is `[PERF][time= ...].core.backend...ctrlBlock.dispatch: EventName, value`. Events are categorized into:

**Frontend events** (13): OverrideBubble, FtqFullStall, FtqUpdateBubble, TAGEMissBubble, SCMissBubble, ITTAGEMissBubble, RASMissBubble, ICacheMissBubble, ITLBMissBubble, BTBMissBubble, FetchFragBubble, FrontendOtherCoreStall, FlushedInsts

**Backend events** (25+): DivStall, IntNotReadyStall, FPNotReadyStall, MemNotReadyStall, OtherNotReadyStall, RobStall, LqStall, SqStall, FusionBubble, IntFlStall, FpFlStall, VecFlStall, V0FlStall, VlFlStall, MultiFlStall, LoadDispatchPolicyStall, StoreDispatchPolicyStall, OtherDispatchPolicyStall, 8 BalanceDispatchPolicyStall variants, IQEnqPolicyStall variants, 8 IQFullStall variants, 7 RedirectStall/RecoveryStall variants, SpecialInsts, BackendOtherCoreStall

**Memory events** (10): LoadTLBStall, LoadL1Stall, LoadL2Stall, LoadL3Stall, LoadMemStall, StoreStall, AtomicStall, LoadVioReplayStall, LoadMSHRReplayStall, MemVioRedirectStall/Bubble

**Control events**: commitInstr, total_cycles (from rob module)

### 3.3 Rename Maps and Hierarchical Grouping

The framework defines five rename maps at different granularity levels:

**xs_coarse_rename_map** (coarsest): Groups events into broad categories -- MergeFrontend, MergeBadSpec, MergeCore, MergeLoad, MergeStore, MergeFreelistStall, MergeMisc, MergeCoreOther, MergePrivileged, MergeBadSpecInst. All IQ stalls, dispatch policy stalls, balance stalls, and recovery stalls fold into MergeCore.

**xs_frontend_rename_map**: Fine-grained frontend analysis -- MergeOverrideBubble, MergeFtqFullStall, MergeFtqUpdateBubble, MergeTAGEMissBubble, MergeSCMissBubble, MergeITTAGEMissBubble, MergeRASMissBubble, MergeICacheMissBubble, MergeITLBMissBubble, MergeBTBMissBubble, MergeFetchFragBubble.

**xs_backend_rename_map**: Backend-focused breakdown -- MergeExecStall, MergeRobStall, MergeFusion, MergeDispatchPolicy, MergeIQpolicyStall, MergeIQFullStall, MergeRabWalkStall, MergeFreelistStall.

**xs_mem_rename_map**: Memory subsystem analysis -- MergeMemNotReadyStall, MergeLoadTLBStall, MergeLoadL1Stall through MergeLoadMemStall, MergeStore, MergeSqStall, MergeLqStall, MergeAtomicStall, MergeMSHRReplayStall, MergeLoadVioReplay, MergeMemVioRedirect.

**xs_fine_grain_rename_map** (finest): Preserves individual event names for detailed analysis, only merging truly equivalent events. Uses `None` to drop events like LoadVioReplayStall and LoadMSHRReplayStall. Maps `NoStall` to `None` (base cycles are handled separately via `Base = 20M instructions`).

The `Merge` prefix convention indicates that multiple source events should be summed into a single display category.

### 3.4 Weighted Metrics Computation

`top_down.py` implements two-level weighting using matrix multiplication for efficiency:

**Per-input weighting** (`proc_input`): For each workload across multiple input sizes, the CPI (cycles per instruction) is computed, then weighted by input-specific weights from a JSON specification file. The formula is `weight = vec_weight @ matrix_perf` where `vec_weight` is `(1, W)` and `matrix_perf` is `(W, N)` with W workloads and N metrics. The `coverage` metric tracks what fraction of the total weight was actually executed (to handle incomplete runs).

**Per-benchmark weighting** (`proc_bmk`): Aggregates across workloads within a benchmark, using instruction count as the weight. The `commitInstr` column is explicitly excluded from scaling to preserve absolute instruction counts.

**Issue-width scaling**: When comparing configurations with different issue widths, the framework computes `scale = max(base_width, ref_width) / min(base_width, ref_width)` and applies it to the smaller-width configuration, enabling fair per-issue comparisons.

### 3.5 Visualization

`draw.py` produces publication-quality stacked bar charts using matplotlib:

- Supports 1 or 2 configurations for direct comparison
- Uses tab10 colormap with hatch patterns for the second configuration
- Automatically handles column alignment across DataFrames
- Sorts benchmarks by CPI (descending) for intuitive ordering
- Supports INT_ONLY / FP_ONLY filtering and custom benchmark lists
- Plots are saved as 200 DPI PNG files under `results/`
- Legend is positioned above the plot with a 6-column layout

The `BadSpecInst` column is post-processed: `BadSpecInst += Base - 20M` and `Base = 20M`, ensuring the base bar height is always 20M instructions (the reference instruction count for SPEC CPU 2006).

### 3.6 Benchmark Coverage

The framework includes a comprehensive SPECCPU 2006 benchmark database (`spec_bmks['06']`): 12 integer benchmarks (perlbench, bzip2, gcc, mcf, gobmk, hmmer, sjeng, libquantum, h264ref, omnetpp, astar, xalancbmk) and 17 floating-point benchmarks (bwaves, gamess, milc, zeusmp, gromacs, cactusADM, leslie3d, namd, dealII, soplex, povray, calculix, GemsFDTD, tonto, lbm, wrf, sphinx3). High-squash benchmarks (astar, bzip2, gobmk, sjeng) are separately tracked.

### 3.7 Workflow

The main `top_down.py` script:
1. Validates that simulator jobs completed successfully (checking for "EXCEEDING CYCLE/INSTR LIMIT" or "HIT GOOD TRAP")
2. Extracts stats from `simulator_err.txt` using regex targets
3. Computes weighted metrics across benchmarks
4. Optionally scales for issue-width normalization
5. Saves intermediate CSV files and produces visualization

---

## 4. perfcct.py -- Pipeline Lifetime Visualization

**Source location:** `/home/agi/workspace/gitwork/XiangShan/scripts/perfcct.py`

### 4.1 Pipeline Model

The tool models XiangShan's pipeline as 11 stages, each represented by a single-character code:

| Stage | Code | Description |
|-------|------|-------------|
| AtFetch | f | Instruction Fetch |
| AtDecode | d | Decode |
| AtRename | r | Rename |
| AtDispQue | D | Dispatch Queue |
| AtIssueQue | i | Issue Queue |
| AtIssueArb | a | Issue Arbitration |
| AtIssueReadReg | g | Read Register File |
| AtFU | e | Functional Unit execution |
| AtBypassVal | b | Bypass / Value forwarding |
| AtWriteVal | w | Write Value (result writeback) |
| AtCommit | c | Commit |

### 4.2 Data Source

The tool queries a SQLite database table `LifeTimeCommitTrace` which records the cycle at which each instruction enters and leaves each pipeline stage. The `WHERE AtCommit != 0` filter (default mode) selects only committed instructions; the `--spec` flag includes speculative instructions that were flushed. Each row contains an ID, stage entry timestamps (divided by `tick_per_cycle`), PC (as 64-bit unsigned integer), and disassembly information.

### 4.3 Visualization Modes

**Visual mode** (`-v`): Produces an ASCII art timeline where each instruction occupies one line. The timeline uses characters: `.` for idle cycles, and the stage code (`f`, `d`, `r`, `D`, `i`, `a`, `g`, `e`, `b`, `w`, `c`) for active cycles. Two sub-modes:

- **Single-line mode** (`-l`): Each instruction's entire lifetime is shown on one line, with characters positioned by `position % cycle_per_line`. This compresses the display but may cause overlapping for long-lived instructions.
- **Multi-line mode** (default): When an instruction spans more than `cycle_per_line` columns, it wraps to the next line with a line number prefix (e.g., `0000100:`). This gives an accurate time-representation.

**Text mode** (default, without `-v`): Outputs compact stage-position pairs like `f5 d12 r15 D20 i25 a27 g28 e30 b35 w40 c45 {...records}`.

### 4.4 Disassembly Integration

The `dasm_query` class supports two disassembly backends:
- **objdump mode** (`riscv64-linux-gnu-objdump`): Writes raw bytes to a temp file, runs objdump with `-b binary -m riscv:rv64 -M,max -D`, and parses the output. Maintains an instruction cache for efficiency.
- **spike-dasm mode**: Communicates with the `spike-dasm` binary via stdin/stdout pipe, sending `DASM(0xHEXVAL)` requests.

Both backends include a hash-based cache (`self.cache`) to avoid redundant disassembly of the same instruction encoding.

### 4.5 Configuration Parameters

- `--period` / `-p`: ticks-per-cycle ratio (default: 1)
- `--zoom` / `-z`: display width multiplier (default: 1.0, setting `cycle_per_line = 100 * zoom`)
- `--dasm` / `-d`: disassembly command
- `--spec` / `-s`: include speculative (flushed) instructions
- `--singleline` / `-l`: single-line mode
- `--cycle` / `-c`: show cycle numbers on multi-line output

---

## 5. rolling.py -- SQLite-Based Rolling Counter Analysis

**Source location:** `/home/agi/workspace/gitwork/XiangShan/scripts/rolling.py`

### 5.1 Architecture

This is the most architecturally sophisticated tool in the collection, implementing a subcommand-based CLI (plot/diff/list/corr) for analyzing rolling performance counters stored in XiangShan's ChiselDB SQLite dumps. It uses type-annotated Python with dataclasses, numpy for numerical computation, and matplotlib for plotting.

### 5.2 Data Model

**RollingSeries**: A frozen dataclass containing `perf_name`, `hart` (hardware thread ID), `table_name`, `xdata` (numpy int64 array of X-axis points), and `ydata` (numpy int64 array of Y-axis increments). The `sample_count` property provides the series length.

**TableStats**: Metadata for a rolling table including sample count, X/Y min/max, and timestamp range.

**CorrelationResult**: Holds correlation analysis results with `perf_name`, `hart`, `samples`, alignment method, and correlation coefficient.

**DataSet**: The core data access class, wrapping a SQLite connection. Table discovery uses `SELECT name FROM sqlite_master WHERE type='table' AND name LIKE '%_rolling_%'`. The naming convention `{perf_name}_rolling_{hart}` is parsed via regex. Series loading queries `XAXISPT` and `YAXISPT` columns ordered by `ID`.

### 5.3 Subcommands

**plot**: Plots one or more rolling counters from a single database. Supports:
- Aggregation (`--aggregate`): merges N consecutive samples
- Interval mode (`--interval`): for event-interval-based counters (vs. cycle-based)
- Hart selection (`--hart`): specific hart or `all` for overlay
- Filtering: `--perf-name`, `--perf-file`, `--include-prefix`, `--exclude-prefix`, `--exclude-perf`

**diff**: Plots the same counters across multiple databases for comparison. Accepts either multiple DB paths or a single file containing newline-separated paths. Each DB line gets a `@filename` suffix in the legend.

**list**: Lists all rolling tables in a database with their statistics (sample count, X/Y min/max, timestamp range).

**corr**: Correlation analysis. Ranks all rolling counters by their correlation with a reference counter (default: `ipc`). Supports three alignment methods:
- `index`: simple index-based alignment (truncates to min length)
- `xaxis`: alignment by common X-axis values using `np.intersect1d`
- `progress`: normalizes both series to [0,1] progress and interpolates to a common grid (most robust for differently-sampled series)

### 5.4 Data Derivation

The `derive()` method handles two operational modes:

**Normal mode** (interval=-1): The database logs `(xAxis, ydata)` where xAxis is the current position and ydata is the increment. Aggregation sums increments and computes the average rate as `aggydata / x_gap`.

**Interval-based mode** (interval>0): The database logs accumulated values in fixed event intervals. Aggregation sums both axes and computes the ratio `aggxdata / aggydata` (e.g., instructions per cache miss).

### 5.5 Plotting Infrastructure

`get_pyplot()` handles both headless (Agg backend) and GUI environments, with MPLCONFIGDIR fallback for CI/CD environments. Output defaults to `{perf_name}.png` at 300 DPI. The `finalize_plot()` method adds legend, tight layout, and saves with bounding-box optimization.

### 5.6 Performance Features

- SQLite table discovery is lazy (only queries when needed)
- Correlation uses `safe_corrcoef()` which handles degenerate cases (constant series, NaN results)
- Progress alignment uses `np.interp` for efficient linear interpolation
- Series deduplication via `include-prefix`/`exclude-prefix` reduces redundant computation

---

## 6. Coverage Analysis Tools

**Source locations:**
- `/home/agi/workspace/gitwork/XiangShan/scripts/coverage/coverage.py`
- `/home/agi/workspace/gitwork/XiangShan/scripts/coverage/statistics.py`

### 6.1 coverage.py -- Annotated Coverage Report Parser

This tool parses Verilog coverage annotation files (typically from Cadence/Xcelium or Synopsys VCS coverage reports). The input is a text file where each line is prefixed with a coverage count or `%0` (not covered).

**Pattern Matching**: Uses regex to classify each annotated line into five categories:
- `LINE_COVERRED`: non-zero count on if/end-else lines
- `NOT_LINE_COVERRED`: `%0` prefix on if/end-else lines
- `TOGGLE_COVERRED`: non-zero count on reg/wire/input/output declarations
- `NOT_TOGGLE_COVERRED`: `%0` prefix on reg/wire/input/output declarations
- `DONTCARE`: all other lines

The `check_one_hot()` function enforces that each line matches at most one category, preventing ambiguous annotations.

**Module Hierarchy Extraction** (`get_modules`): Parses `module`/`endmodule` boundaries and submodule instantiations to build a tree. Submodules that appear before their definitions are treated as blackbox modules. Each module tracks its line range (`BEGIN`/`END`), type (`ROOT` for top-level, `NODE` for instantiated), and `CHILDREN` list.

**Coverage Statistics** (`get_coverage_statistics`): For a given line range, counts covered and uncovered lines for both line coverage and toggle coverage. The coverage ratio is `covered / (covered + uncovered)` with divide-by-zero protection (defaults to 1.0).

**Tree Coverage** (`get_tree_coverage`): Performs a post-order DFS to compute aggregate coverage including all submodules. For each module, it sums the covered and uncovered counts from its own coverage and all children's tree coverage, then recomputes the ratio. This produces both `SELFCOVERAGE` (module only) and `TREECOVERAGE` (module + submodules) for each module.

**Sorted Output**: Results are sorted by coverage ratio (ascending), making it easy to identify the lowest-coverage modules. Four sorted lists are produced: LineSelfCoverage, LineTreeCoverage, ToggleSelfCoverage, ToggleTreeCoverage. A hierarchical tree printout (`print_tree_coverage`) shows both self and tree metrics for each module with indentation reflecting the hierarchy.

### 6.2 statistics.py -- Coverage Report Sanitizer

This preprocessing tool cleans coverage annotation files before analysis. It handles three types of Verilog preprocessor blocks that should be excluded from coverage metrics:

- `` `ifndef SYNTHESIS `` blocks: Non-synthesizable code (assertions, fprintf statements)
- `` `ifdef RANDOMIZE_REG_INIT `` blocks: Random register initialization
- `` `ifdef RANDOMIZE_MEM_INIT `` blocks: Random memory initialization

The tool tracks nesting levels for each block type independently, replacing coverage numbers with spaces when inside a relevant block. It enforces no nesting within the same block type (asserts `synthesis_nest_level == 0` when entering a new `ifndef SYNTHESIS`), while correctly handling nested `ifdef`/`ifndef`/`endif` within the block.

---

## 7. L2 DB Helper -- Cache Subsystem SQLite Query Tool

**Source location:** `/home/agi/workspace/gitwork/XiangShan/scripts/cache/l2DB_helper.py`

### 7.1 Overview

A lightweight command-line tool for querying XiangShan's L2 cache subsystem debug database. The database is generated during RTL simulation and stored as SQLite files under the `build/` directory.

### 7.2 Commands

**log** (`TLLOG` table): Queries the TileLink transaction log. The generated SQL is piped through `scripts/cache/convert_tllog.sh` for human-readable formatting.

**mp** (`L2MP` table): Queries the L2 MainPipe state. Piped through `scripts/cache/convert_mp.sh` for formatting.

### 7.3 Query Construction

The `parse_db()` function constructs a parameterized SQL query:

```sql
SELECT * FROM (SELECT * FROM {table} WHERE {sql} ORDER BY STAMP DESC LIMIT {limit}) ORDER BY STAMP ASC
```

The inner query applies optional WHERE filters and selects the most recent N records (`ORDER BY STAMP DESC LIMIT N`). The outer query re-sorts to chronological order for readable output. The result is piped to the corresponding shell script via `| sh {script}`.

### 7.4 Features

- Auto-detection of the latest `.db` file in the build directory (`find_lateset(suffix)`)
- Custom SQL WHERE clauses (e.g., `STAMP > 10000` or `ADDRESS=0x80000000`)
- Configurable record limit (default: 20)
- `--last` flag to select the most recent N records
- `--path` override for non-standard database locations

---

## 8. Source File Locations Summary

| Tool | Path | Lines | Purpose |
|------|------|-------|---------|
| parser.py | `scripts/parser.py` | 751 | RTL parsing, SRAM replacement, release packaging |
| constantHelper.py | `scripts/constantHelper.py` | 354 | Genetic algorithm parameter optimizer |
| top_down.py | `scripts/top-down/top_down.py` | 238 | Top-down analysis orchestration |
| draw.py | `scripts/top-down/draw.py` | 270 | Stacked bar chart visualization |
| configs.py | `scripts/top-down/configs.py` | 427 | Event definitions, rename maps, benchmark DB |
| perfcct.py | `scripts/perfcct.py` | 186 | Pipeline lifetime ASCII visualization |
| rolling.py | `scripts/rolling.py` | 687 | Rolling counter plot/diff/corr analysis |
| coverage.py | `scripts/coverage/coverage.py` | 317 | Coverage annotation parser with hierarchy DFS |
| statistics.py | `scripts/coverage/statistics.py` | 112 | Coverage report sanitizer (preprocessor filtering) |
| l2DB_helper.py | `scripts/cache/l2DB_helper.py` | 59 | L2 cache TileLink/MainPipe SQLite queries |

Supporting files in `scripts/cache/`:
- `convert_tllog.sh` -- TileLink log formatting
- `convert_mp.sh` -- MainPipe state formatting
- `convert_dir.sh` -- Directory conversion utility
- `parseAddr.py` -- Address parsing utility

---

## 9. Architectural Patterns and Design Observations

### 9.1 Common Design Patterns

**Regex-based parsing**: All tools rely heavily on regex for structured text processing -- parser.py for Verilog syntax, top_down.py for performance counter logs, coverage.py for annotated reports. This reflects the reality of working with EDA tool outputs and generated RTL.

**SQLite as the data backbone**: Both rolling.py and l2DB_helper.py use SQLite as the primary data store for simulation outputs. This enables efficient querying without loading entire datasets into memory, and supports the multi-hart, multi-counter nature of modern processor simulations.

**Modular pipeline design**: The top-down framework separates data extraction (top_down.py), configuration (configs.py), and visualization (draw.py), enabling independent development and testing of each stage.

### 9.2 Integration Points

These tools form a coherent analysis ecosystem:
- parser.py produces the RTL files that generate simulation databases
- constantHelper.py uses the emulator to evaluate parameter configurations, whose performance is analyzed by the top-down framework
- perfcct.py visualizes individual instruction traces from the same simulation infrastructure
- rolling.py provides time-series analysis of the counters that feed into top_down.py
- coverage.py and statistics.py analyze the code quality of the RTL produced by the Chisel/FIRRTL compilation pipeline

### 9.3 Extensibility

The tools demonstrate several extension points:
- parser.py's `try_prefix` mechanism supports multi-configuration builds (e.g., different cache sizes)
- top_down.py's rename map system allows custom analysis groupings without code changes
- rolling.py's prefix-based filtering enables selective analysis of specific subsystem counters
- constantHelper.py's JSON configuration format makes it straightforward to add new optimization targets

---

## 10. Conclusion

XiangShan's analysis tool suite represents a mature, production-quality infrastructure for processor development. The tools span the full development lifecycle: from RTL packaging and SRAM integration (parser.py), through automated parameter optimization (constantHelper.py), to comprehensive performance analysis at multiple abstraction levels (top-down framework, perfcct.py, rolling.py), and quality verification (coverage tools). The L2 DB helper provides direct insight into cache subsystem behavior. Together, these tools enable the XiangShan team to systematically identify performance bottlenecks, optimize microarchitecture parameters, verify code quality, and prepare RTL for physical implementation -- all within a unified Python-based toolchain that integrates with the Chisel/FIRRTL/Verilog compilation flow and the NEMU-based functional simulation infrastructure.
