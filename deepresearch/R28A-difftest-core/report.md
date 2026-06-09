# R28A - DiffTest Core Engine Deep Dive

## 1. Introduction

DiffTest is the core verification framework of the XiangShan RISC-V processor project. Its fundamental idea is: at every architectural commit point, compare the state of the DUT (Design Under Test) with a Golden Reference Model (such as NEMU or Spike) on a per-state basis, to detect micro-architectural implementation errors. This document provides a deep-dive analysis of the DiffTest Core Engine source code, covering the class hierarchy, the check_all() loop, the DiffState structure, Golden Memory management, all Checker types, Trace recording and replay, Emulator initialization and main loop, and the Snapshot save/restore mechanism.

---

## 2. Difftest Class Hierarchy and check_all() Loop

### 2.1 Top-Level Architecture

The `Difftest` class (declared in `difftest.h`, implemented in `difftest.cpp`) is the core driving class of the entire DiffTest engine. Each processor core has its own independent `Difftest` instance; the global array `Difftest **difftest` manages all cores.

The global initialization function `difftest_init(bool enabled, size_t ramsize)` performs the following steps:

1. Calls `diffstate_buffer_init()` to initialize the DiffState double-buffering zone mechanism
2. Allocates the `difftest` array with size `NUM_CORES`
3. If DiffTest is enabled, first calls `init_goldenmem()` to allocate and initialize golden memory
4. Constructs a `Difftest` instance for each core, obtaining the DUT state pointer from `diffstate_buffer`
5. Calls `update_nemuproxy()` to load the Reference Model (NEMU/Spike) shared library
6. Calls `init_checkers()` to register all Checkers
7. Registers signal handlers (SIGINT, SIGTERM, SIGABRT, SIGSEGV, SIGBUS), ensuring `difftest_finish()` is called on abnormal exit

### 2.2 Difftest Constructor

```cpp
Difftest::Difftest(int coreid) {
  state = new DiffState(coreid);
#ifdef CONFIG_DIFFTEST_REPLAY
  state_ss = (DiffState *)malloc(sizeof(DiffState));
#endif
}
```

Each `Difftest` instance holds a `DiffState *state` for recording the core's micro-architectural state (commit trace, store event queue, trap code, etc.). It also holds a `DiffTestState *dut` pointer pointing to the DUT hardware state snapshot, and a `RefProxy *proxy` pointing to the Reference Model proxy interface.

### 2.3 The Core check_all() Loop

`check_all()` is the heart of DiffTest, defined as an inline protected method of the `Difftest` class. Its complete flow is:

**Phase 1: Timeout Check and Normal Checkers Traversal**

```cpp
inline int Difftest::check_all() {
  state->cycle_count = get_trap_event()->cycleCnt;
  state->has_progress = false;

  // normal checkers (TimeoutChecker, StoreRecorder, SbufferChecker, etc.)
  for (auto checker: checkers) {
    if (int ret = checker->step()) {
      return ret;
    }
  }
```

The `checkers` vector holds "per-cycle" type checkers including `TimeoutChecker`, `StoreRecorder`, `SbufferChecker`, `AtomicChecker`, `CmoInvalRecorder`, `LrScChecker`, `NonRegInterruptPendingChecker`, `MhpmeventOverflowChecker`, `CriticalErrorChecker`, `AiaChecker`, `CustomMflushpwrChecker`, `L1TLBChecker`, `L2TLBChecker`, `RefillChecker`, etc. They are called once per cycle to handle their respective events.

**Phase 2: ArchEvent Check**

```cpp
  num_commit = 0;
  if (dut->event.valid) {
    if (int ret = arch_event_checker->step()) {
      return ret;
    }
    dut->commit[0].valid = 0;
  } else {
```

If the DUT has an exception or interrupt (`dut->event.valid` is true), `ArchEventChecker` handles it: it calls `proxy->raise_intr` or `guided_exec` to synchronize the Reference Model. After handling, `commit[0].valid` is cleared, skipping normal commit checks.

**Phase 3: InstrCommit Check**

```cpp
    for (int i = 0; i < CONFIG_DIFF_COMMIT_WIDTH; i++) {
      if (dut->commit[i].valid) {
        num_commit += 1 + dut->commit[i].nFused;
        if (int ret = instr_commit_checker[i]->step()) {
          return ret;
        }
      }
    }
  }
```

It iterates over each commit slot. If the slot is valid, it calls the corresponding `InstrCommitChecker::step()`. Each `InstrCommitChecker` internally either:
- Calls `proxy->skip_one()` to skip MMIO instructions
- Or calls `proxy->ref_exec(1)` to let the Reference Model execute one step
- In Squash mode, calls `op_checkers` (LoadSquashChecker and StoreChecker) after each execution step

**Phase 4: Delayed Writeback Processing**

```cpp
  if (int ret = update_delayed_writeback()) {
    return ret;
  }
```

Using the `CHECK_DELAYED_WB` macro, it checks each delayed integer/floating-point register writeback one by one. If a register's delayed flag has been cleared (commit arrived), the delayed counter is reset to zero. If still nacked, the counter is decremented. If a register is no longer in the delayed list but gets written back, it indicates state inconsistency, returning `STATE_DIFF`.

**Phase 5: Progress Check and State Synchronization**

```cpp
  if (!state->has_progress) {
    return DiffTestChecker::STATE_OK;
  }

  proxy->sync();
```

If there is no progress this cycle (no instruction commits, no delayed writebacks consumed), it returns OK immediately. If there is progress, it calls `proxy->sync()` to copy the Reference Model's register state to the local `proxy->state`.

**Phase 6: Delayed Writeback Application**

```cpp
  if (num_commit > 0) {
    state->record_group(dut->commit[0].pc, num_commit);
  }

  if (apply_delayed_writeback()) {
    return DiffTestChecker::STATE_DIFF;
  }
```

`apply_delayed_writeback()` replaces the DUT-side values of registers still in delayed state with Reference-side values (`proxy->state.reg_type.value[i]`) and increments the delay counter. If any register has been delayed for more than `delay_wb_limit` (default 80 cycles), it reports an error and returns `STATE_DIFF`.

**Phase 7: Reference State Comparison**

```cpp
  if (proxy->compare(dut) || pc_mismatch) {
    return DiffTestChecker::STATE_DIFF;
  }

  return DiffTestChecker::STATE_OK;
}
```

Calls `proxy->compare(dut)` to compare the complete architectural state of DUT and Reference (general registers, CSR, PC, floating-point registers, vector registers, etc.). If mismatched, or if a PC mismatch was previously detected, returns `STATE_DIFF`.

### 2.4 step() and Replay Mechanism

The `step()` method wraps `check_all()` and adds replay logic when `CONFIG_DIFFTEST_REPLAY` is enabled:

1. If currently in replay range, track replay step count
2. Before each `check_all()`, if replay conditions are met (`can_replay()`), call `replay_snapshot()` to save a state snapshot
3. If `check_all()` detects a mismatch, call `do_replay()` to restore the snapshot and begin replay, to locate a more precise error position
4. After replay ends, pass the head index back to RTL via `difftest_replay_head()`

### 2.5 Multi-Core Step Driver

The global function `difftest_nstep(int step, bool enable_diff)` drives multi-core simulation:

1. Before each loop iteration, calls `difftest_switch_zone()` to switch double-buffering zones
2. Calls `difftest_set_dut()` to update the DUT state pointer for each core
3. In each step loop, calls `difftest_step()`, which iterates over each core and calls its `step()`
4. `difftest_step()` differentiates return values: `STATE_DIFF` (print state then ABORT), `STATE_ERROR`, `STATE_TRAP`, etc.

---

## 3. DiffState Structure and Commit Trace

### 3.1 DiffState Class

`DiffState` (declared in `diffstate.h`, implemented in `diffstate.cpp`) is the DiffTest runtime state container for each core.

**Core member variables:**

- `int coreid` -- core identifier
- `uint64_t cycle_count` -- current cycle count
- `bool has_progress` -- whether there was progress this cycle
- `bool has_commit` -- whether an instruction has committed (used for first-instruction detection)
- `uint64_t last_commit_cycle` -- cycle of last commit (used for timeout detection)
- `bool has_trap` / `uint64_t trap_code` -- trap state
- `int delayed_int[32]` / `int delayed_fp[32]` -- delayed register writeback counters
- `bool dump_commit_trace` -- whether to output commit trace

**Store Event Queue:**

```cpp
#ifdef CONFIG_DIFFTEST_STOREEVENT
  typedef struct {
    uint8_t valid;
    uint64_t addr;
    uint64_t data;
    uint8_t mask;
    uint64_t pc;
    uint16_t robidx;
  } StoreCommit;
  std::queue<StoreCommit> store_event_queue;
#endif
```

When `StoreRecorder` records a store event, it pushes the store information into `store_event_queue`, which is later consumed by `StoreChecker` to compare against Reference's `store_commit()`.

**Load Event Queue (Squash mode):**

```cpp
#ifdef CONFIG_DIFFTEST_SQUASH
  int commit_stamp = 0;
#ifdef CONFIG_DIFFTEST_LOADEVENT
  std::queue<DifftestLoadEvent> load_event_queue;
#endif
#endif
```

In Squash mode, load events are not checked immediately but are enqueued first, to be consumed by `LoadSquashChecker` when the corresponding `commit_stamp` matches, ensuring order in squash replay scenarios.

### 3.2 Commit Trace System

DiffState manages two layers of traces:

**Retire Group Queue:**

```cpp
  static const int DEBUG_GROUP_TRACE_SIZE = 16;
  std::queue<std::pair<uint64_t, uint32_t>> retire_group_queue;
```

`record_group(pc, count)` records each commit group's first PC and commit count, keeping at most 16 groups. This is used to quickly locate the offending commit group during mismatches.

**Instruction Trace Queue:**

```cpp
  static const int DEBUG_INST_TRACE_SIZE = 32;
  std::queue<CommitTrace *> commit_trace;
```

`record_inst()` / `record_exception()` / `record_interrupt()` create `InstrTrace`, `ExceptionTrace`, and `InterruptTrace` objects respectively, pushing them into the queue. When `dump_commit_trace` is enabled, each trace is immediately printed to stdout in the format:

```
commit pc %016lx inst %08x wen %d dst %02d data %016lx idx %03x (S/D tag)
```

Where `S` marks skip instructions (MMIO) and `D` marks delayed writeback.

### 3.3 CommitTrace Inheritance Hierarchy

```
CommitTrace (abstract)
  |-- InstrTrace       (normal instruction commit)
  |-- ExceptionTrace   (exception)
        |-- InterruptTrace  (interrupt)
```

- `CommitTrace` base class holds `pc` and `inst`, provides `display()` and `display_line()` methods
- `InstrTrace` adds `wen`, `dest`, `data`, `robidx`, `isLoad`, `isStore`, `lqidx`, `sqidx` fields
- `ExceptionTrace` adds `cause` field
- `InterruptTrace` inherits ExceptionTrace, only changing `get_type()` to return "interrupt"

### 3.4 get_commit_data() Function

```cpp
uint64_t get_commit_data(const DiffTestState *state, int index) {
#if defined(CONFIG_DIFFTEST_COMMITDATA)
  return state->commit_data[index].data;
#else
  if (state->commit[index].fpwen) {
    return get_fp_data(state, index);
  } else {
    return get_int_data(state, index);
  }
#endif
}
```

This function chooses between fetching writeback data directly from the `commit_data` structure, or from the register file (supporting both architectural/physical modes) indexed by `wdest`/`wpdest`.

---

## 4. Golden Memory (mmap, Store Log, Rollback)

### 4.1 Memory Mapping (mmap)

Golden Memory is the Reference Model's memory mirror, defined in `goldenmem.h` and `goldenmem.cpp`.

```cpp
uint8_t *pmem;       // Physical memory main buffer
uint8_t *pmem_flag;  // Flag buffer (1: store update but load check skip; 0: normal update and check)
```

`init_goldenmem()` uses `mmap` to allocate anonymous memory:

```cpp
pmem = mmap(NULL, pmem_size, PROT_READ | PROT_WRITE,
            MAP_ANON | MAP_PRIVATE | MAP_NORESERVE, -1, 0);
pmem_flag = mmap(NULL, pmem_size, PROT_READ | PROT_WRITE,
                 MAP_ANON | MAP_PRIVATE | MAP_NORESERVE, -1, 0);
```

Using `MAP_NORESERVE` means allocating a large virtual address space without immediately consuming physical memory (demand paging). After allocation, `simMemory->clone_on_demand()` copies the DUT's RAM content into Golden Memory on demand. Finally, the `pmem` pointer is assigned to the global variable `ref_golden_mem` for use by the Reference Model.

### 4.2 Address Translation

```cpp
void *guest_to_host(uint64_t addr) {
  return &pmem[addr];
}

bool in_pmem(uint64_t addr) {
  return (PMEM_BASE <= addr) && (addr <= PMEM_BASE + simMemory->get_size() - 1);
}
```

Since `pmem` is mapped from address 0 via `mmap`, the translation between guest physical address and host virtual address only requires the `PMEM_BASE` offset. `paddr_read()` / `paddr_write()` first check if the address is within pmem range, then perform the read/write.

### 4.3 Update and Read Operations

```cpp
extern "C" void update_goldenmem(uint64_t addr, void *data, uint64_t mask, int len, uint8_t flag = 0) {
  for (int i = 0; i < len; i++) {
    if (((mask >> i) & 1) != 0) {
      paddr_write(addr + i, dataArray[i], flag, 1);
    }
  }
}

extern "C" void read_goldenmem(uint64_t addr, void *data, uint64_t len, void *flag = NULL) {
  *(uint64_t *)data = paddr_read(addr, len);
  if (flag != NULL) {
    *(uint64_t *)flag = paddr_flag_read(addr, len);
  }
}
```

`update_goldenmem` is an extern "C" function callable from DPI-C, updating Golden Memory byte by byte according to the byte mask. `read_goldenmem` reads data and flags of specified length.

### 4.4 The pmem_flag System

The `pmem_flag` array corresponds 1:1 with `pmem`. When data is written, the flag is set simultaneously:

- `flag = 0`: Normal data, needs comparison with Reference during load
- `flag = 1`: Store update but load check skip (used for multi-core uncache MM store scenarios)

`paddr_flag_read()` checks each byte is either 0 or 1 during reads, asserting otherwise.

### 4.5 Store Log Mechanism

When `ENABLE_STORE_LOG` is enabled, Golden Memory implements a store log feature for state recovery during replay:

```cpp
#define GOLDENMEM_STORE_LOG_SIZE 1024
struct store_log {
  uint64_t addr;
  word_t org_data;
  word_t org_flag;
} goldenmem_store_log_buf[GOLDENMEM_STORE_LOG_SIZE];
```

- `pmem_record_store(addr)`: Before each paddr_write, records the current address's original data and flag into the log buffer
- `goldenmem_store_log_reset()`: Resets the log pointer
- `goldenmem_store_log_restore()`: Iterates the log buffer in reverse order, restoring each address to its original value

In Replay mode, the store log is enabled during snapshot saving and used during restore to bring Golden Memory back to the snapshot point's state.

---

## 5. All Checker Types

### 5.1 Class Inheritance Hierarchy

```
DiffTestChecker (abstract base)
  |-- SimpleChecker (abstract)
  |     |-- LoadSquashChecker
  |     |-- StoreChecker
  |
  |-- ProbeChecker<Probe> (template)
        |-- ArchEventChecker
        |-- FirstInstrCommitChecker
        |-- InstrCommitChecker
        |-- TimeoutChecker
        |-- StoreRecorder
        |-- LoadChecker
        |-- SbufferChecker
        |-- UncacheMmStoreChecker
        |-- AtomicChecker
        |-- CmoInvalRecorder
        |-- RefillChecker
        |-- L1TLBChecker
        |-- L2TLBChecker
        |-- LrScChecker
        |-- NonRegInterruptPendingChecker
        |-- MhpmeventOverflowChecker
        |-- AiaChecker
        |-- CustomMflushpwrChecker
        |-- CriticalErrorChecker
        |-- GoldenMemoryInit
```

### 5.2 DiffTestChecker Base Class

```cpp
class DiffTestChecker {
public:
  DiffTestChecker(DiffState *state, RefProxy *proxy);
  int step();
  virtual int do_step() = 0;

  static const int STATE_OK = 0;
  static const int STATE_DIFF = 1;
  static const int STATE_ERROR = 2;
  static const int STATE_TRAP = 3;

protected:
  DiffState *state;
  RefProxy *proxy;
  Stopwatch *timer = nullptr;
};
```

The `step()` method calls `before_step()` and `after_step()` around `do_step()` for performance timing (when `CONFIG_DIFFTEST_CHECKER_PERF` is enabled), supporting checker class name resolution via C++ RTTI (`abi::__cxa_demangle`) for performance reports.

### 5.3 ProbeChecker<Probe> Template

```cpp
template <typename Probe> class ProbeChecker : public DiffTestChecker {
  using GetProbeFn = std::function<Probe &()>;
  virtual int do_step() override {
    Probe &probe = get_probe();
    if (get_valid(probe)) {
      int ret = check(probe);
      clear_valid(probe);
      return ret;
    }
    return 0;
  }
protected:
  virtual bool get_valid(const Probe &probe) = 0;
  virtual void clear_valid(Probe &probe) = 0;
  virtual int check(const Probe &probe) = 0;
};
```

Via lambda capture (`GetProbeFn`) it obtains a reference to the corresponding probe struct in the DUT. Subclasses only need to implement three virtual functions: `get_valid()`, `clear_valid()`, and `check()`. `get_valid()` checks if the probe is valid, `clear_valid()` clears the valid bit after checking, and `check()` performs the actual comparison logic.

### 5.4 SimpleChecker Base Class

```cpp
class SimpleChecker : public DiffTestChecker {
  virtual int do_step() override {
    if (get_valid()) {
      int ret = check();
      clear_valid();
      return ret;
    }
    return 0;
  }
protected:
  virtual bool get_valid() = 0;
  virtual void clear_valid() = 0;
  virtual int check() = 0;
};
```

Used for checkers not directly corresponding to a DUT probe, such as `LoadSquashChecker` (consumes from DiffState queue) and `StoreChecker` (consumes from store_event_queue).

### 5.5 TimeoutChecker

```cpp
class TimeoutChecker : public ProbeChecker<DifftestTrapEvent> {
  static const uint64_t first_commit_limit = 15000;  // XiangShan
  static const uint64_t stuck_commit_limit = first_commit_limit * timeout_scale;
};
```

**Check logic:**

1. If the first instruction hasn't committed and `first_commit_limit` cycles have elapsed, report error
2. If a WFI instruction is detected, update `last_commit_cycle` to extend the wait
3. If there were commits but no new commit for `stuck_commit_limit` cycles, print warning and have Reference execute one step, then return `STATE_DIFF`

### 5.6 FirstInstrCommitChecker

Triggers on the first instruction commit, performing critical initialization:

1. Initialize Flash: `proxy->flash_init()`
2. Clone Golden Memory to Reference: `simMemory->clone_on_demand()` copies memory content to Reference via `proxy->mem_init()` in DUT_TO_REF direction
3. Synchronize register state: `proxy->regcpy(&regs, FIRST_INST_ADDRESS)` initializes Reference with `FIRST_INST_ADDRESS` as PC

This is the "first synchronization" moment of DiffTest; before this, Reference has not been aligned with DUT.

### 5.7 InstrCommitChecker

This is the most critical checker, with one instance per commit slot. Its `check()` method:

1. **Records trace:** Calls `state->record_inst()` to record PC, instruction, writeback data, etc.
2. **Fuzzing exit detection:** If `probe.special & 0x2` (isExit), triggers `STATE_SIM_EXIT` trap
3. **Delayed writeback marking:** If `probe.special & 0x1` (isDelayedWb), sets `delayed_int` / `delayed_fp` flags
4. **Skip handling:** For MMIO instructions (`probe.skip`) or debug-mode load/store, calls `proxy->skip_one()` to skip Reference execution and directly synchronize writeback data
5. **Normal execution:** Calls `proxy->ref_exec(1)` to have Reference execute one step (multi-step for fused instructions)
6. **Squash mode:** Calls `op_checkers` (LoadSquashChecker and StoreChecker) after each execution step
7. **Non-Squash mode:** Calls `op_checkers` uniformly after all instructions complete

### 5.8 StoreRecorder and StoreChecker

**StoreRecorder:** Each cycle checks the DUT's store event probe, pushing store information (addr, data, mask, pc, robIdx) into `state->store_event_queue`. It handles three cases:

- Normal store: push directly
- Vector store with split (`probe.vecNeedSplit`): split into multiple stream store commits by EEW
- Cache line write (`probe.wLine`): split into 8 store commits by 64-bit word

**StoreChecker:** Acts as an op_checker of `InstrCommitChecker`, consuming `store_event_queue` during instruction commits, calling `proxy->store_commit()` to compare with Reference. On mismatch, it prints mismatch information.

### 5.9 LoadChecker and LoadSquashChecker

**LoadChecker (non-Squash mode):** Called as an op_checker within `InstrCommitChecker`. When DUT load data mismatches Reference register value:

1. Reads the corresponding address data from Golden Memory
2. If Golden Memory data matches DUT (indicating Golden Memory is out of sync with Reference), synchronizes Golden Memory data to Reference
3. If Golden Memory data also mismatches (flag is 1), uses DUT data to correct Golden Memory and Reference
4. In SMP mode, can also probe another core's local data

**LoadSquashChecker (Squash mode):** Inherits `SimpleChecker`, consumes load events from `state->load_event_queue`, matching by `commit_stamp` order, ensuring load check ordering in squash replay scenarios.

### 5.10 ArchEventChecker

Handles exceptions and interrupts:

- **interrupt:** Records trace, handles NMI/virtual interrupt injection, calls `proxy->raise_intr()` to synchronize Reference
- **exception:** Records trace. For page fault exceptions (IPF/LPF/SPF/IGPF/LGPF/SGPF/HWE), constructs `ExecutionGuide` and calls `proxy->guided_exec()` to force Reference to trigger the same exception; other exceptions directly call `proxy->ref_exec(1)`
- **Fuzzing mode:** Detects infinite loops (same exception PC appearing 5+ times)

### 5.11 Specialized Checkers

- **LrScChecker:** Synchronizes LR/SC micro-architectural status (sc_fail) to Reference
- **NonRegInterruptPendingChecker:** Synchronizes non-register interrupt pending status to Reference
- **AiaChecker:** Synchronizes AIA (Advanced Interrupt Architecture) status
- **CustomMflushpwrChecker:** Synchronizes custom mflushpwr CSR
- **MhpmeventOverflowChecker:** Triggers Reference's mhpmevent overflow
- **CriticalErrorChecker:** Detects double trap and other critical errors
- **SbufferChecker:** Updates store buffer flush events to Golden Memory
- **UncacheMmStoreChecker:** Handles uncache MM store events (flag=1)
- **AtomicChecker:** Executes atomic operations (AMO/LR/SC) in Golden Memory, verifying DUT atomic operation results
- **L1TLBChecker / L2TLBChecker:** Verify TLB behavior
- **RefillChecker:** Verify cache refill events
- **CmoInvalRecorder:** Records CMO invalidate events
- **GoldenMemoryInit:** Dumps Golden Memory initial state at cycle 100 (for debugging)

---

## 6. DiffTrace Recording and Replay

### 6.1 DiffTrace<T> Template Class

`DiffTrace` (declared in `difftrace.h`, implemented in `difftrace.cpp`) is a generic trace recording/replay framework, currently instantiated as `DiffTrace<DiffTestState>`.

**Core members:**

- `char trace_name[32]` -- trace file name prefix
- `bool is_read` -- true for replay mode, false for recording mode
- `T *buffer` -- buffer pointer
- `uint64_t buffer_size` -- buffer size (default 1MB)
- `uint64_t buffer_count` -- current number of entries in buffer

**Recording flow (`append`):**

```cpp
bool DiffTrace<T>::append(const T *trace) {
  memcpy(buffer + buffer_count, trace, sizeof(T));
  buffer_count++;
  if (buffer_count == buffer_size) {
    return trace_file_next();  // write to file when buffer full
  }
  return 0;
}
```

**Replay flow (`read_next`):**

```cpp
bool DiffTrace<T>::read_next(T *trace) {
  if (!buffer || buffer_count == trace_zstd->trace_load_len) {
    trace_file_next();  // load next block when current buffer exhausted
  }
  memcpy(trace, buffer + buffer_count, sizeof(T));
  buffer_count++;
  return 0;
}
```

### 6.2 File Chunk Management

`trace_file_next()` manages trace file chunked writing/reading:

- Write mode: writes buffer contents as binary to `{trace_name}/{index}.bin`
- Read mode: reads the next file into buffer
- Auto-creates directory if it doesn't exist
- Auto-incrementing file index

### 6.3 Zstd Compression Support

When `CONFIG_IOTRACE_ZSTD` is enabled, the `DiffTraceZstd` class provides Zstd compression/decompression:

- `diff_IOtrace_dump()`: Uses ZSTD_compressCCtx to compress trace data and write to file
- `diff_IOtrace_load()` / `diff_IOtrace_ZstdDcompress()`: Streaming read and decompression of Zstd files
- Uses recommended `ZSTD_DStreamInSize()` / `ZSTD_DStreamOutSize()` as buffer sizes

### 6.4 DiffTrace Integration in Difftest

In the `Difftest` class:

```cpp
void set_trace(const char *name, bool is_read) {
  difftrace = new DiffTrace<DiffTestState>(name, is_read);
}

void trace_write(int step) {
  if (difftrace) {
    for (int i = 0; i < step; i++) {
      difftrace->append(diffstate_buffer[state->coreid]->get(zone, i));
    }
  }
}

void trace_read() {
  if (difftrace) {
    difftrace->read_next(dut);
  }
}
```

During recording, each step retrieves DUT state from `diffstate_buffer` and appends to the trace. During replay, trace data is read back and written into the `dut` pointer, driving the simulator from trace rather than RTL simulation.

---

## 7. Emulator Initialization and Main Loop

### 7.1 Emulator Class

`Emulator` (declared in `emu.h`, implemented in `emu.cpp`) inherits from `DUT` and serves as the top-level entry class for simulation.

**Constructor `Emulator::Emulator(int argc, const char *argv[])` performs the following initialization:**

1. **Stack size setup (Verilator):** Sets 32MB stack to prevent segfaults on large designs
2. **Argument parsing:** `parse_args(argc, argv)` parses command-line arguments
3. **Random seed:** `srand(args.seed)` / `srand48(args.seed)` / `Verilated::randSeed(args.seed)`
4. **JTAG initialization:** Initializes JTAG if remote bitbang is enabled
5. **Flash initialization:** `init_flash(args.flash_bin)`
6. **Waveform initialization:** Calls `dut_ptr->waveform_init()` if waveform dump is enabled
7. **Core reset:** `reset_ncycles(args.reset_cycles)` asserts reset and runs several cycles
8. **RAM initialization:** Selects `init_ram()`, `FootprintsMemory`, `MmapMemoryWithFootprints`, or `LinearizedFootprintsMemory` based on configuration
9. **GCPT restore:** Overwrites RAM content if gcpt_restore file is specified
10. **Snapshot initialization:** Calls `dut_ptr->snapshot_init()` if snapshots are enabled; loads snapshot if snapshot_path is specified
11. **DiffTest initialization:** `difftest_init(args.enable_diff, ref_ramsize)`
12. **Trace initialization:** Calls `set_trace()` for each core if trace_name is specified
13. **Device initialization:** `init_device()`
14. **LightSSS (fork) initialization:** Initializes fork debugging if enabled

### 7.2 single_cycle() Method

```cpp
inline void Emulator::single_cycle() {
  // Verilator clock rising edge
  dut_ptr->set_clock(1);
  dut_ptr->step();

  // Waveform dump (based on cycle range)
  // DRAMSim3 step
  // UART step

  // Verilator clock falling edge
  dut_ptr->set_clock(0);
  dut_ptr->step();

  cycles++;
}
```

Each cycle executes one complete clock rising edge + falling edge + peripheral step.

### 7.3 tick() Method -- Main Loop Core

`tick()` is the core method called every cycle by the simulator:

**Phase 1: Exit Condition Checks**

- Fork child detection (LightSSS checkpoint replay point)
- Cycle limit check: `trap->cycleCnt >= args.max_cycles`
- Instruction limit check: `trap->instrCnt >= core_max_instr[i]`
- Assertion check: `assert_count > 0`
- Signal check: `signal_num != 0`
- DUT exit signal: `dut_ptr->get_difftest_exit()`

**Phase 2: Performance Statistics and Warmup**

- Warmup completion detection: dump and reset perf counters after reaching `warmup_instr`
- Periodic stat dump: every `stat_cycles` cycles
- IPC statistics (`ENABLE_IPC`)
- Reference trace / commit trace log begin/end control

**Phase 3: Single Cycle Execution**

- Calls `single_cycle()` to drive RTL simulation

**Phase 4: DiffTest Driving**

```cpp
  int step = 0;
  if (args.trace_name && args.trace_is_read) {
    step = 1;
    difftest_trace_read();       // replay from trace file
  } else {
    step = dut_ptr->get_difftest_step();  // get step from RTL
  }

  // Stuck detection: report deadlock if no step for stuck_limit cycles
  static uint64_t stuck_timer = 0;
  if (step) {
    stuck_timer = 0;
  } else {
    stuck_timer++;
    if (stuck_timer >= Difftest::stuck_limit) {
      return STATE_ABORT;
    }
  }

  // Trace write
  if (args.trace_name && !args.trace_is_read) {
    difftest_trace_write(step);
  }

  // Execute DiffTest core check
  trapCode = difftest_nstep(step, args.enable_diff);
```

**Phase 5: Snapshot Saving and Fork**

- Saves snapshot in memory every 60 seconds
- Dumps one snapshot to file every 60 snapshots
- Fork-based debugging: periodically forks child process based on `fork_interval`

### 7.4 reset_ncycles()

```cpp
inline void Emulator::reset_ncycles(size_t cycles) {
  if (args.trace_name && args.trace_is_read) {
    return;  // skip reset in trace replay mode
  }
  for (int i = 0; i < cycles; i++) {
    dut_ptr->set_clock(1);
    dut_ptr->step();
    dut_ptr->set_clock(0);
    dut_ptr->step();
    dut_ptr->set_reset(0);
  }
}
```

During the reset phase, the reset signal is asserted for several cycles, then released.

---

## 8. Snapshot Save/Restore Mechanism

### 8.1 snapshot_save()

```cpp
void Emulator::snapshot_save() {
  auto snapshot_write = dut_ptr->snapshot_take();

  // 1. Save RAM size
  long size = simMemory->get_size();
  snapshot_write(&size, sizeof(size));

  // 2. Save RAM content
  snapshot_write(simMemory->as_ptr(), size);

  // 3. Save DiffTest state
  auto diff = difftest[0];
  uint64_t cycleCnt = diff->get_trap_event()->cycleCnt;
  snapshot_write(&cycleCnt, sizeof(cycleCnt));

  // 4. Save Reference Model register state
  snapshot_write(&proxy->state, sizeof(proxy->state));

  // 5. Save Reference Model memory
  char *buf = mmap(NULL, size, PROT_READ | PROT_WRITE,
                   MAP_ANON | MAP_PRIVATE, -1, 0);
  proxy->mem_init(PMEM_BASE, buf, size, REF_TO_DUT);
  snapshot_write(buf, size);
  munmap(buf, size);

  // 6. Save CSR state
  uint64_t csr_buf[4096];
  proxy->ref_csrcpy(csr_buf, REF_TO_DUT);
  snapshot_write(&csr_buf, sizeof(csr_buf));

  // 7. Save SD card offset
  long sdcard_offset = fp ? ftell(fp) : 0;
  snapshot_write(&sdcard_offset, sizeof(sdcard_offset));
}
```

Snapshot data is stored sequentially: RAM size -> RAM content -> cycleCnt -> Reference registers -> Reference memory -> CSR -> SD card offset.

### 8.2 snapshot_load()

```cpp
void Emulator::snapshot_load(const char *filename) {
  auto snapshot_read = dut_ptr->snapshot_load(filename);

  // 1. Read and restore RAM
  long size;
  snapshot_read(&size, sizeof(size));
  assert(size == simMemory->get_size());
  snapshot_read(simMemory->as_ptr(), size);

  // 2. Restore cycleCnt
  snapshot_read(cycleCnt, sizeof(*cycleCnt));

  // 3. Restore Reference registers and sync to Reference
  snapshot_read(&proxy->state, sizeof(proxy->state));
  proxy->ref_regcpy(&proxy->state, DUT_TO_REF, false);

  // 4. Restore Reference memory
  char *buf = mmap(NULL, size, PROT_READ | PROT_WRITE,
                   MAP_ANON | MAP_PRIVATE, -1, 0);
  snapshot_read(buf, size);
  proxy->mem_init(PMEM_BASE, buf, size, DUT_TO_REF);
  munmap(buf, size);

  // 5. Restore CSR
  uint64_t csr_buf[4096];
  snapshot_read(&csr_buf, sizeof(csr_buf));
  proxy->ref_csrcpy(csr_buf, DUT_TO_REF);

  // 6. Mark as committed
  diff->set_has_commit();

  // 7. Restore SD card
  snapshot_read(&sdcard_offset, sizeof(sdcard_offset));
  if (fp) fseek(fp, sdcard_offset, SEEK_SET);
}
```

The restore process is the inverse of save. A key difference is direction: saving exports from Reference (`REF_TO_DUT`), restoring imports to Reference (`DUT_TO_REF`).

### 8.3 Snapshot in Emulator Destructor

```cpp
Emulator::~Emulator() {
  // ...
  if (args.enable_snapshot && trapCode != STATE_GOODTRAP
      && trapCode != STATE_LIMIT_EXCEEDED) {
    dut_ptr->snapshot_save(-1);  // save all snapshots to file
  }
  // ...
}
```

Snapshots are only dumped to file on non-normal exits (not good trap and not cycle limit exceeded), facilitating subsequent debugging.

### 8.4 Snapshot in Replay

In `CONFIG_DIFFTEST_REPLAY` mode, `replay_snapshot()` implements a lighter-weight snapshot:

```cpp
void Difftest::replay_snapshot() {
  memcpy(state_ss, state, sizeof(DiffState));
  memcpy(proxy_reg_ss, &proxy->state, sizeof(ref_state_t));
  proxy->ref_csrcpy(squash_csr_buf, REF_TO_DUT);
  proxy->ref_store_log_reset();
  proxy->set_store_log(true);
  goldenmem_store_log_reset();
  goldenmem_set_store_log(true);
}
```

Saves DiffState, Reference register state, and CSR, and enables store log to record subsequent writes. `do_replay()` restores these states and clears store/load event queues.

---

## 9. Source File Locations

All source files are located under `/home/agi/workspace/gitwork/XiangShan/difftest/src/test/csrc/`.

### Difftest Core Engine

| File | Description |
|------|-------------|
| `difftest/difftest.h` | Difftest class declaration, global API declarations |
| `difftest/difftest.cpp` | Difftest class implementation, check_all() loop, global driver functions |
| `difftest/diffstate.h` | DiffState class declaration, CommitTrace inheritance hierarchy |
| `difftest/diffstate.cpp` | DiffState implementation, CommitTrace display, get_commit_data() |
| `difftest/checkers.h` | All Checker class declarations (DiffTestChecker base to concrete Checkers) |
| `difftest/checkers/instructions.cpp` | FirstInstrCommitChecker, TimeoutChecker, InstrCommitChecker implementations |
| `difftest/checkers/traps.cpp` | ArchEventChecker, CriticalErrorChecker implementations |
| `difftest/checkers/store.cpp` | StoreRecorder, StoreChecker implementations |
| `difftest/checkers/load.cpp` | LoadChecker, LoadSquashChecker implementations |
| `difftest/checkers/globalmem.cpp` | GoldenMemoryInit, SbufferChecker, UncacheMmStoreChecker, AtomicChecker implementations |
| `difftest/checkers/sync_states.cpp` | LrScChecker, NonRegInterruptPendingChecker, AiaChecker, MhpmeventOverflowChecker, CustomMflushpwrChecker implementations |
| `difftest/checkers/refill.cpp` | RefillChecker implementation |
| `difftest/checkers/tlb.cpp` | L1TLBChecker, L2TLBChecker implementations |

### Golden Memory and Reference Proxy

| File | Description |
|------|-------------|
| `difftest/goldenmem.h` | Golden memory function declarations |
| `difftest/goldenmem.cpp` | Golden memory implementation (mmap, store log, paddr read/write) |
| `difftest/refproxy.h` | RefProxy class declaration, NemuProxy/SpikeProxy/LinkedProxy declarations |
| `difftest/refproxy.cpp` | RefProxy implementation, shared library dynamic loading |

### Trace and Emulator

| File | Description |
|------|-------------|
| `difftest/difftrace.h` | DiffTrace template class declaration, DiffTraceZstd class declaration |
| `difftest/difftrace.cpp` | DiffTrace implementation, Zstd compression/decompression |
| `emu/emu.h` | Emulator class declaration |
| `emu/emu.cpp` | Emulator implementation (initialization, main loop tick(), snapshot save/load) |
| `emu/main.cpp` | Program entry, Emulator instantiation |
| `emu/simulator.h` | Simulator abstract base class |

---

## 10. Summary

The DiffTest Core Engine is a carefully designed multi-layer verification framework:

1. **Driving Layer:** `Emulator` handles RTL simulation clock driving, argument parsing, and resource management, driving the entire simulation loop through its `tick()` method
2. **Scheduling Layer:** `difftest_nstep()` / `difftest_step()` handles multi-core scheduling and double-buffering zone management
3. **Core Check Layer:** `Difftest::check_all()` is the core check loop, executing in priority order: timeout check -> arch event check -> instruction commit check -> delayed writeback -> state compare
4. **Checker Layer:** 20+ types of Checkers cover everything from basic instruction commit to complex atomic operations, TLB, cache refill, interrupt synchronization, etc.
5. **State Management Layer:** `DiffState` maintains per-core runtime state including commit trace, store/load event queues, and delayed writeback markers
6. **Memory Management Layer:** `goldenmem` provides mmap-based golden memory, store log, and flag system
7. **Trace Layer:** `DiffTrace` supports trace recording/replay with optional Zstd compression
8. **Snapshot Layer:** Supports full simulation snapshot save/restore including RTL state, golden memory, and reference registers/CSR/memory

The entire framework achieves high configurability through `CONFIG_*` macros, allowing flexible combination based on different verification needs (whether to enable squash, replay, store events, load events, vector, AIA, etc.).
