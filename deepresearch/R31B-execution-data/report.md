# R31B - XSPdb Execution Control & Data I/O Deep-Dive Report

## 1. Overview

XSPdb is the interactive debugging tool for the XiangShan RISC-V processor project, built on top of Python's `pdb` (Python Debugger) framework. It provides deep control capabilities over the hardware simulator through the `pyxscore.DUTSimTop` interface and integrates with `pydifftest` for reference model comparison. The core class `XSPdb` extends `pdb.Pdb`, enabling chip verification engineers to perform efficient simulation execution control, waveform capture, memory operations, and disassembly analysis.

The tool architecture follows a modular command-class pattern: core logic resides in `scripts/xspdb/xspdb.py`, CLI argument parsing is handled by `scripts/xspdb/cli_parser.py`, and individual features are distributed across command classes in the `scripts/xspdb/xscmd/` directory (including `cmd_dut.py`, `cmd_wave.py`, `cmd_difftest.py`, `cmd_fork_backup.py`, `cmd_files.py`, `cmd_batch.py`, `cmd_dasm.py`, `cmd_instr.py`, `cmd_flash.py`, `cmd_mrw.py`, `cmd_break.py`, `cmd_regs.py`, `cmd_info.py`, `cmd_tools.py`, and others). The entry point `scripts/pdb-run.py` instantiates `DUTSimTop` and `XSPdb` in just a few lines to launch the debugger.

---

## 2. xstep / xistep Execution Control

### 2.1 xstep -- Cycle-Level Stepping

`xstep` is the most fundamental simulation advancement command in XSPdb, used to advance the DUT (Design Under Test) clock by a specified number of cycles. Its implementation is in `cmd_dut.py`'s `api_step_dut()` method.

**Core Parameters:**
- `cycle` (int): Total number of cycles to advance
- `batch_cycle` (int, default 200): Number of cycles per batch run; after each batch, interrupt signals and breakpoints are checked

**Execution Flow:**

1. **Preparation Phase** (`api_dut_step_ready`): Calls `self.dut.xclock.Enable()` to enable the clock and resets `self.interrupt` flag.

2. **Batch Advancement**: The total cycle count is split into `cycle // batch_cycle` full batches plus a remainder batch. Each batch calls `self.dut.Step(batch_cycle)` to perform actual simulation advancement.

3. **Interrupt Check** (`check_break`): After each batch, the following conditions are checked:
   - `dut.xclock.IsDisable()`: Hardware breakpoint triggered
   - `api_is_difftest_diff_exit()`: DiffTest detected divergence exit
   - `api_is_hit_good_trap()`: Hit good trap
   - `api_is_hit_good_loop()`: Hit good loop
   - `api_is_hit_trap_break()`: Hit trap break
   - `self.interrupt`: User-triggered interrupt via Ctrl+C

4. **Callback Execution**: When a hardware breakpoint fires, `call_break_callbacks()` executes all registered callback functions.

5. **Fork Backup Integration**: Within `api_step_dut`, after each batch completes, `_fork_backup_tick()` and `_fork_backup_on_break_any()` are called to ensure the fork backup mechanism can wake the child process for waveform capture when breakpoints trigger.

The interactive command syntax is `xstep [cycle] [steps]`, where `cycle` defaults to 1 and `steps` defaults to 200.

### 2.2 xistep -- Instruction-Level Stepping

`xistep` builds on top of `xstep` with instruction commit detection capability, forming the key mechanism for "single-step one instruction" semantics. Its implementation is in `cmd_difftest.py`'s `api_xistep()` method.

**Working Principle:**

1. **Set Break Condition** (`api_xistep_break_on`): Creates a condition checker using `ComUseCondCheck`, setting PC comparison conditions for all 8 commit slots. The core logic compares "old PC" against "current PC" -- when any commit slot's PC changes, an interrupt fires.

2. **PC Synchronization**: Calls `update_pc_func()` to sync current commit slot PC values into the "old PC" buffer.

3. **Iterative Advancement**: For each target instruction, calls `api_step_dut(10000)` to advance up to 10,000 cycles. If a commit is detected during advancement (`dut.xclock.IsDisable()` is True), it updates the commit PC list and records the event.

4. **Symbol Tracing**: After each commit, calls `api_echo_pc_symbol_block_change()` to track PC symbol block changes.

5. **Cleanup** (`api_xistep_break_off`): Removes the temporary `stepi_check` callback.

**Performance Note:** If 10,000 cycles pass without detecting a commit, a warning is issued and the count is decremented to avoid invalid waits.

---

## 3. run_commits for Commit-Count Execution

The `run_commits` function is defined in `xspdb.py` and serves as the core mechanism for executing simulation by instruction commit count in batch mode.

**Function Signature:** `run_commits(xspdb, commits, max_run_time)`

**Implementation Logic:**

1. **Parameter Handling:** If `commits < 0`, it is set to `0xFFFFFFFFFFFFFF` (effectively unlimited).

2. **Batch Execution:** Uses `batch_size = 100` as the unit. Calculates `batch_count = commits // 100` and `batch_remain = commits % 100`.

3. **Internal Function `run_delta`:** Loops calling `xspdb.api_xistep(delta)` to advance instructions, accumulating the actual commit count `runc` until the target count is reached or an exit condition is met.

4. **Time Limit:** After each advancement, checks `time.time() - time_start > max_run_time`. If exceeded, sets `reach_max_time = True` and breaks out of the loop.

5. **Result Reporting:** Outputs a summary: "Execute {run_ins} commits completed ({commits - run_ins} ignored)".

**CLI Association:** The `-pc` / `--pc-commits` parameter specifies the commit count, and `--max-run-time` specifies maximum runtime (supports `10s`, `1m`, `1h` formats).

In the batch mode execution flow (`__run_batch`), when `args.pc_commits != 0`, `run_commits` is prioritized. After completion, the waveform is closed if needed. This allows users to precisely control simulation execution to a specific number of instruction commits in batch mode.

---

## 4. Waveform Control

### 4.1 Three Waveform States

XSPdb's waveform control is implemented by the `CmdTrap` class (`cmd_wave.py`), supporting three states:

| State | Description | Performance Overhead |
|-------|-------------|---------------------|
| **Off** | Waveform capture completely disabled, zero overhead | ~0% |
| **On** | Waveform capturing active, signals recorded to .fst file | ~5-10% |
| **Paused** | Capture suspended but file handle maintained | ~0% |

### 4.2 Initialization and Default File

During `XSPdb.__init__`, `api_init_waveform()` is called:
- Calls `dut.RefreshComb()` and `dut.FlushWaveform()` to flush combinational logic and waveform buffers.
- Calls `dut.PauseWaveformDump()` to set waveform to Paused state.
- Auto-generates a timestamped default filename: `wave_{YYYYMMDD_HHMMSS}.fst` (extension obtained via `dut.GetWaveFormat()`, defaults to `.fst`).

### 4.3 Waveform Format -- FST (Fast Signal Trace)

XSPdb uses the `.fst` format for waveform data storage. FST format advantages include:
- Efficient signal hierarchy and value change recording
- Automatic compression for reduced disk usage
- Compatibility with standard waveform viewers (GTKWave, etc.)
- Support for signal browsing, searching, and time marking

### 4.4 Waveform Commands

**xwave_on [file]**: Start waveform recording. If no file specified, uses the current default file. Calls `dut.SetWaveform()` to set the target file, then `dut.ResumeWaveformDump()` to resume recording.

**xwave_off**: Pause waveform recording. Calls `dut.PauseWaveformDump()` without closing the file.

**xwave_flush**: Force-write buffered data to disk. Calls `dut.FlushWaveform()`, useful before long pauses to ensure data persistence.

**xwave_continue src_file**: Copies source waveform file to the current default file, then resumes recording. This enables segmented waveform concatenation from existing snapshots.

### 4.5 Batch Mode Waveform Control

In CLI batch mode, the `-b` (wave-begin) and `-e` (wave-end) parameters control the waveform capture lifecycle:

- **`-b N` (N > 0)**: Enables waveform at HW cycle = N. Uses `api_xbreak` to set a one-shot callback on `SimTop_top.SimTop.timer`; when the timer reaches N, `api_waveform_on()` fires.
- **`-b 0` or negative**: Starts capture from HW cycle = 0.
- **`-e N` (N > 0)**: Disables waveform at HW cycle = N. Also via xbreak callback.
- **`-e 0` or negative**: Closes waveform after execution finishes.

This design enables precise waveform capture time windows in batch mode without manual intervention, significantly reducing storage overhead.

---

## 5. DiffTest Snapshot Save/Restore

### 5.1 DiffTest Integration Architecture

DiffTest is XiangShan's core verification framework, comparing RTL simulation results against a reference model (typically the spike ISA simulator) to detect functional correctness. XSPdb deeply integrates with DiffTest through the `pydifftest` module.

**Initialization Flow:**
1. `df.InitFlash("")` initializes Flash memory
2. `df.difftest_init(False, mem_size)` initializes the DiffTest framework
3. `df.GetDifftest(0).dut` obtains the difftest status handle
4. `df.GetFlash()` obtains the Flash data interface

### 5.2 Reference SO Loading

Via the `--diff` CLI parameter or `xload_difftest_ref_so` command, the reference model's shared object (`.so` file) is loaded:
```python
self.df.SetProxyRefSo(so_path)
```

Then `api_init_ref()` completes reference model initialization: device setup, golden memory loading, and difftest startup.

### 5.3 Diff Detection Mechanism

`api_set_difftest_diff(turn_on)` creates a condition checker using `ComUseCondCheck`, executing `df.GetFuncAddressOfDifftestStepAndCheck()` at each clock rising edge for step-and-check comparison. When a divergence is detected, `api_is_difftest_diff_exit()` returns True.

### 5.4 Snapshot Workflow

The DiffTest snapshot workflow comprises four phases:
1. **Debug Phase**: Use DiffTest to locate divergence points
2. **Snapshot Phase**: Save simulator state before critical regions
3. **Analysis Phase**: Restore snapshots and explore alternative execution paths
4. **Verification Phase**: Re-run tests from saved states

Snapshot save/restore relies on simulator-level checkpointing (requires snapshot support at build time). The `xexpdiffstate` command exports the current difftest state to a Python variable for further analysis in JSON format.

### 5.5 Commit PC Monitoring

The `do_xpc` command displays PC and instruction values for all 8 commit slots. The `xwatch_commit_pc` command monitors commit events at specific PC addresses using `ComUseRangeCheck` for range matching.

---

## 6. Fork Backup Mechanism for Waveform Capture

### 6.1 Design Motivation

Fork backup is an innovative mechanism in XSPdb that solves the classic "when to enable waveform capture" dilemma. The core idea is to maintain a "lagging" child process: when the parent process triggers a breakpoint, it wakes the child process to perform waveform capture, thereby capturing waveforms around the bug window without adding overhead during normal execution.

### 6.2 Implementation Architecture

Implemented by the `CmdForkBackup` class (`cmd_fork_backup.py`), using `os.fork()` for child process creation and `os.pipe()` for inter-process communication.

**Key State Variables:**
- `_fork_backup_enabled`: Whether enabled
- `_fork_backup_is_child`: Whether current process is the child
- `_fork_backup_window_sec`: Backup window in seconds, default 10.0
- `_fork_backup_child_state`: Child process state ("waiting" / "running" / "terminating")
- `_fork_backup_target_breaks`: Set of breakpoints the child should wait for
- `_fork_backup_keep_waves`: Number of waveform files to retain, default 2

### 6.3 Workflow

1. **Spawn** (`_fork_backup_spawn_child`): Parent creates child via `os.fork()`. Child closes write-end of pipe and enters `_fork_backup_child_main()` waiting mode. Child redirects stdio to log files and calls `dut.AtClone()` or similar hooks to reinitialize simulator thread pools.

2. **Tick** (`_fork_backup_tick`): Called during each `api_step_dut` batch interval. Checks if the child is still running; if the child completed and `window_sec` has elapsed since the last fork, spawns a new child.

3. **Wake** (`_fork_backup_on_break`): When the parent's xbreak fires, sends `WAKE {break_keys} {wave_file}` message through the pipe. The child responds by:
   - Calling `api_waveform_on(wave_file)` to start waveform capture
   - Calling `_fork_backup_run_until_target()` to advance simulation until reaching the same breakpoint
   - Calling `dut.FlushWaveform()` and `api_waveform_off()` to complete the write

4. **Reap** (`_fork_backup_reap_child`): Reclaims the child process, adds the waveform file path to history.

5. **Prune** (`_fork_backup_prune_waves`): Retains the latest N waveform files, deleting older ones.

### 6.4 Usage

```
xfork_backup_on [window_sec] [wave_dir] [log_path]
xfork_backup_off
xfork_backup_status
```

### 6.5 Logging System

- Parent log: `fork_backup_parent.log`
- Child log: `fork_backup.log` (via fd redirection)
- Child log includes detailed heartbeat information: iter, adv, total, clk, xclk_disable, etc.

### 6.6 Scope

Fork backup operates on the `xstep` path (interactive/TUI mode), not the `emu.py` batch loop. This is because the fork mechanism requires complete replication of simulator state, which is incompatible with the execution flow in batch loops.

---

## 7. Memory Load

### 7.1 Binary Loading

**xload command**: Loads binary files into the RAM memory region.

Implementation logic (`api_dut_bin_load`):
1. Verifies file existence
2. If memory is already initialized, calls `df.overwrite_ram(bin_file, mem_size)` to overwrite
3. If memory is not initialized, calls `api_init_mem()` first (calls `df.InitRam(exec_bin_file, mem_size)`), with default memory size of 1GB
4. Clears disassembly cache `info_cache_asm`

**CLI Parameters:** `-i` / `--image` specifies the binary to auto-load at startup; `--mem-base-address` sets the memory base address (default `0x80000000`).

### 7.2 Flash Loading

**xflash command**: Loads binary files into the Flash region.

Implementation logic (`api_dut_flash_load`):
1. Calls `df.flash_finish()` to clean old Flash data
2. Calls `df.InitFlash(flash_file)` to load the new file
3. Flash base address defaults to `0x10000000` (configurable via `--flash-base-address`)

### 7.3 Instruction List Loading

**xload_instr_file command**: Loads uint64-formatted instruction lists to a specified address.

Usage flow:
1. `xparse_instr_file <file>` -- Parses instruction file and displays hex string
2. `xload_instr_file <address> <file>` -- Writes instruction data to specified memory address

Implementation calls `api_convert_uint64_bytes()` to parse the file, then `api_write_bytes()` to write to memory.

### 7.4 Register File Loading

**xload_reg_file command**: Loads initial register values from a text file. Format is `register_name:value` (one per line), supporting integer registers (x0-x31) and floating-point registers (f0-f31).

### 7.5 Direct Memory Write

**xmem_write command**: Writes bytes or number data to a specified address. Supports two data formats:
- Python bytes: `b'\x00\x01...'`
- Hex number: `0xDEADBEEF`

Implementation via `api_write_bytes()` automatically handles alignment and boundary conditions, selecting RAM (`df.pmem_write`) or Flash (`df.FlashWrite`) write path based on address range.

---

## 8. Memory Export

### 8.1 RAM Export

**xexport_ram command**: Exports RAM data from `mem_base` to a specified end address.

```python
def api_export_ram(self, end_address, bin_file):
    for index in range(self.mem_base, end_index, 8):
        f.write(self.df.pmem_read(index).to_bytes(8, byteorder='little', signed=False))
```

Reads in 8-byte units and writes in little-endian format.

### 8.2 Flash Export

**xexport_flash command**: Exports Flash data. The implementation scans Flash content for the `mret` instruction (`0x30200073`), using it as the end marker for valid Flash data. If `mret` is not found, exports up to 1024*10 = 10KB of data.

### 8.3 Unified Export

**xexport_bin command**: Simultaneously exports Flash and RAM data. Supports two modes:
1. **Standard Mode**: Separately exports `{file}_flash.bin` and `{file}_ram.bin`
2. **Unified Mode**: When `start_address != mem_base`, attempts to export as `{file}_all.bin`, merging Flash and RAM data into a single file. The implementation checks for address conflicts between Flash and RAM data.

### 8.4 Data Conversion Utilities

XSPdb provides a rich set of data conversion commands:
- **xbytes_to_bin**: Writes bytes data to a binary file
- **xbytes2number**: Converts byte sequences to numeric values
- **xnumber2bytes**: Converts numeric values to byte sequences
- **xmem_read / xmem_read_range**: Reads and displays memory data
- **xmem_copy / xmem_copy_range_to**: Memory data copy operations
- **xback_trace**: Retrieves call stack from stack pointer and program counter

---

## 9. CLI Batch Mode

### 9.1 Execution Mode Overview

XSPdb supports three execution modes:

| Mode | Description | Launch Method |
|------|-------------|---------------|
| **Interactive** | Manual command input, full debugging capability | Default mode, or when `--batch` not specified |
| **Batch** | Automated script or replay execution | `--batch` parameter |
| **Mixed** | Batch execution + interactive breakpoints | `--batch -t 0` |

### 9.2 Script Mode

**CLI:** `-s <script_file>` or `--script <script_file>`

Execution flow (`run_script`):
1. Calls `api_exec_script(script_file, gap_time=batch_interval)` to parse the script file
2. Each line in the script is one command; lines starting with `#` are comments; `\#` is supported for escaping
3. Parsed commands are pushed into the `batch_cmds_to_exec` queue
4. `_exec_batch_cmds()` executes commands sequentially, with optional delay between each
5. After completion, enters interactive debugging (`set_trace()`)

### 9.3 Replay Mode

**CLI:** `-r <replay_file>` or `--replay <replay_file>`

Replay mode reproduces sessions from log files. Unlike script mode, replay extracts specific commands from the log (identified by `target_prefix` and `target_subfix` markers, such as `@cmd{...}`), ignoring other log content.

### 9.4 Command Injection

- **--cmds**: Injects commands before script/replay execution (use `\n` for multiple commands)
- **--cmds-post**: Injects commands after script/replay execution

Injected commands are added to the command queue via `api_append_init_cmd()` and `api_batch_append_tail_one_cmd()`.

### 9.5 Command Queue Mechanism

`batch_cmds_to_exec` is a list where each item is a `(cmd, gap_time, callback)` tuple. The `_exec_batch_cmds()` method maintains a `batch_depth` counter, supporting nested batch processing. Each command execution is logged via `record_cmd()` in the format `@cmd{command_text}`.

### 9.6 Execution Flow Control

Complete batch mode execution flow:
1. **Initialization**: Parse arguments, initialize DUT, setup XSPdb
2. **Waveform Setup**: Configure waveform callbacks per `-b`/`-e`/`--wave-path`
3. **Interact Setup**: Set interactive breakpoint per `-t`
4. **Script/Replay**: Execute script or replay
5. **DiffTest**: Load reference SO and start DiffTest
6. **Commits Execution**: Execute per `-pc` count
7. **Cycle Execution**: Execute per `-c` cycle count
8. **Teardown**: Close waveforms, export logs, cleanup resources

### 9.7 Time Limits

The `--max-run-time` parameter supports multiple time formats:
- `10s` -- 10 seconds
- `5m` -- 5 minutes
- `1h` -- 1 hour

Time checks are performed after each batch interval to ensure no timeout occurs.

### 9.8 Performance Considerations

| Mode | Performance Overhead |
|------|---------------------|
| Waveform Disabled | ~0% |
| Waveform Enabled | ~5-10% |
| DiffTest Enabled | ~10-20% |
| Full Debug Mode | ~15-25% |

---

## 10. Disassembly

### 10.1 Dual-Engine Architecture

XSPdb's disassembly system, implemented by the `CmdDASM` class (`cmd_dasm.py`), supports two disassembly backends with automatic fallback:

**Primary Engine -- spike-dasm:**
- Located via `find_executable_in_dirs()` in the `./ready-to-run` directory
- Invoked via subprocess, processing up to 1,000 instructions per batch
- Supports the complete RISC-V ISA including compressed instructions
- Uses `DASM(%016lx)` format to pass instruction data

**Fallback Engine -- capstone:**
- Automatically switches when `spike-dasm` is unavailable
- Uses the Python `capstone` library (`capstone.CS_ARCH_RISCV`, `capstone.CS_MODE_RISCV32|CS_MODE_RISCV64|CS_MODE_RISCVC`)
- Limited ISA coverage; some instructions may display as `unknown`
- Enables `skipdata` mode for handling non-instruction data

### 10.2 Instruction Stream Parsing

The `dasm_bytes()` function implements intelligent 16/32-bit mixed instruction parsing:

```python
# Scan byte-by-byte in 2-byte steps
for i in range(0, len(bytes_data), 2):
    c_instr = bytes_data[i:i+2]
    if full_instr is not None:        # Previous iteration was low half of 32-bit instr
        full_instr += c_instr
        ...
    if c_instr[0] & 0x3 == 0x3:       # 32-bit instruction flag
        full_instr = c_instr
        continue
    # 16-bit compressed instruction
    instrs_todecode.append(...)
```

This ensures correct handling of RISC-V's mixed instruction lengths (16-bit compressed and 32-bit standard).

### 10.3 Disassembly Command Set

| Command | Description | Parameters |
|---------|-------------|------------|
| `xdasm` | Disassemble RAM/Flash memory | `<address> [length]` |
| `xdasmflash` | Disassemble Flash data | `<address> [length]` |
| `xdasmbytes` | Disassemble raw bytes | `<bytes> [address]` |
| `xdasmnumber` | Disassemble single instruction | `<number> [address]` |
| `xclear_dasm_cache` | Clear disassembly cache | No parameters |
| `xload_elf` | Load ELF file for symbols | `<elf_file>` |

### 10.4 Cross-Region Disassembly

The `api_all_data_to_asm()` method implements intelligent cross-memory-region disassembly: when the requested address range spans Flash and RAM boundaries, it automatically splits into two segments, disassembles each separately, and merges the results.

### 10.5 Caching Mechanism

Disassembly results are cached in the `info_cache_asm` dictionary, keyed by addresses aligned to `info_cache_bsz`. Cache entries are automatically invalidated during memory writes (`api_write_bytes`) and Flash writes.

### 10.6 Instruction Encoding/Decoding

Beyond disassembly, XSPdb provides complete instruction encoding/decoding capabilities:

- **xdecode_instr**: Decodes machine code into structured fields (type, opcode, rd, rs1, rs2, funct3, funct7, imm, asm)
- **xencode_instr**: Encodes structured fields back to machine code
- Supports 32-bit standard instructions (R/I/S/B/U/J types) and 16-bit compressed instructions (CIW/CI/CL/CR/CS/CB/CJ types)

---

## 11. Source File Locations

### Core Source Files

| File | Path | Function |
|------|------|----------|
| xspdb.py | `scripts/xspdb/xspdb.py` | XSPdb core class, extends pdb.Pdb |
| cli_parser.py | `scripts/xspdb/cli_parser.py` | CLI argument parser |
| pdb-run.py | `scripts/pdb-run.py` | Entry point script |

### Command Class Files (scripts/xspdb/xscmd/)

| File | Class Name | Function |
|------|------------|----------|
| cmd_dut.py | CmdDut | DUT control: reset, step, watch, print, set |
| cmd_difftest.py | CmdDiffTest | DiffTest integration: ref SO, diff, xistep, commit PC |
| cmd_wave.py | CmdTrap | Waveform control: on/off/flush/continue |
| cmd_fork_backup.py | CmdForkBackup | Fork backup waveform capture |
| cmd_files.py | CmdFiles | File operations: load, export_bin/flash/ram |
| cmd_flash.py | CmdFlash | Flash operations: load, reset, register management |
| cmd_batch.py | CmdBatch | Batch execution: script, replay |
| cmd_dasm.py | CmdDASM | Disassembly: memory/flash/bytes/number |
| cmd_instr.py | CmdInstr | Instruction encode/decode: 16/32 bit |
| cmd_mrw.py | CmdMRW | Memory read/write: read/write/copy |
| cmd_break.py | CmdBreak | Breakpoint management: signal/expr/FSM |
| cmd_regs.py | -- | Register management |
| cmd_info.py | -- | Information queries |
| cmd_tools.py | -- | Utility functions |
| cmd_com.py | -- | Communication |
| cmd_elf.py | -- | ELF file operations |
| cmd_trap.py | -- | Trap handling |
| util.py | -- | Utility functions: logging, completions, dasm |

### Design Documents (docs/XSPdb/design/)

| File | Topic |
|------|-------|
| step.md | Stepping control design |
| waveform.md | Waveform control design |
| difftest_snapshot.md | DiffTest and snapshot design |
| data_io.md | Data I/O design |
| registers.md | Register initialization design |
| disasm.md | Disassembly design |
| cli_batch.md | CLI batch execution design |
| fork_backup.md | Fork backup design |

### Example Documents (docs/XSPdb/examples/)

| File | Content |
|------|---------|
| data_io.md | Data load/export example commands |

---

## 12. Architecture Summary

XSPdb's execution control and data I/O system embodies a layered design philosophy:

**Control Hierarchy:** From the low-level `dut.Step()` (cycle-level simulation advancement) through the mid-level `api_step_dut()` (stepping with interrupt checking) to the high-level `api_xistep()` (instruction-level stepping) and `run_commits()` (commit-count-driven execution), each layer builds upon the one below while adding higher-level abstractions.

**Data Hierarchy:** Memory operations distinguish between RAM and Flash regions, accessed through unified `api_write_bytes()` and `api_read_bytes_from()` interfaces that automatically select the correct low-level operation based on address range. Export functionality provides RAM, Flash, and unified granularities for different analysis needs.

**Debug Hierarchy:** From basic `xbreak` signal breakpoints to `xbreak_expr` expression breakpoints and `xbreak_fsm` FSM triggers, a progressively complex breakpoint system is formed. The fork backup mechanism seamlessly combines breakpoints with waveform capture, transforming the workflow from "post-analysis" to "real-time capture."

**Batch Hierarchy:** Supports multiple command sources including script, replay, and command injection, with unified command queue and batch depth mechanisms for flexible command scheduling while maintaining compatibility with interactive mode.

This comprehensive system provides XiangShan processor verification and debugging with a complete toolchain from "running to the problem point" to "analyzing the problem scene."
