# R11 - Backend Control & DataPath Research Report

## 1. Overview

XiangShan backend is a superscalar, out-of-order processor backend based on Tomasulo algorithm. The backend is divided into a **Control Block** (pipeline control, rename, dispatch, ROB) and three **Regions** (Int, FP, Vec), each containing Issue Queues, DataPath, BypassNetwork, ExuBlock, and Writeback DataPath. This report covers the control logic, datapath topology, writeback/forwarding network, and key pipeline control signals.

## 2. Backend Pipeline Overview

```
                          +--------------------------+
                          |       Frontend (IFU)     |
                          +------+--------+----------+
                                 | FTQ Ptr + Instr
                                 v
                    +-------------------------------+
                    |      CtrlBlock                |
                    |  +-----------+                |
                    |  | DecodeStage|  (DecodeWidth) |
                    |  +-----+-----+                |
                    |        v                       |
                    |  +-----------+                |
                    |  |FusionDecoder|               |
                    |  +-----+-----+                |
                    |        v                       |
                    |  +-----------+                |
                    |  |   Rename   |  (RenameWidth) |
                    |  +-----+-----+                |
                    |        v                       |
                    |  +-----------+                |
                    |  |  Dispatch  |  (DispatchWidth)|
                    |  +--+----+---+                |
                    |     |    |                     |
                    |  +--+--+ |                     |
                    |  | ROB  | |                     |
                    |  +------+ |                     |
                    +----+------|---------------------+
                         |      |
            +------------+      +-----------+
            |                              |
            v                              v
   +------------------+        +------------------+
   |    IntRegion     |        |    FPRegion      |
   | +-----------+    |        | +-----------+    |
   | |IssueQueue |x11 |        | |IssueQueue |x3  |
   | +-----+-----+    |        | +-----+-----+    |
   |       v           |        |       v          |
   | +-----------+    |        | +-----------+    |
   | | DataPath  |    |        | | DataPath  |    |
   | +-----+-----+    |        | +-----+-----+    |
   |       v           |        |       v          |
   | +--------------+ |        | +--------------+ |
   | |BypassNetwork | |        | |BypassNetwork | |
   | +-----+--------+ |        | +-----+--------+ |
   |       v           |        |       v          |
   | +-----------+    |        | +-----------+    |
   | |  ExuBlock  |    |        | |  ExuBlock  |    |
   | +-----+-----+    |        | +-----+-----+    |
   |       v           |        |       v          |
   | +-----------+    |        | +-----------+    |
   | | WbDataPath |    |        | | WbDataPath |    |
   | +-----+-----+    |        | +-----+-----+    |
   +------------------+        +------------------+
                                          |
                                 +------------------+
                                 |    VecRegion     |
                                 | +-----------+    |
                                 | |IssueQueue |x4  |
                                 | +-----+-----+    |
                                 |       v          |
                                 | +-----------+    |
                                 | | DataPath  |    |
                                 | +-----+-----+    |
                                 |       v          |
                                 | +--------------+ |
                                 | |BypassNetwork | |
                                 | +-----+--------+ |
                                 |       v          |
                                 | +-----------+    |
                                 | |  ExuBlock  |    |
                                 | +-----+-----+    |
                                 |       v          |
                                 | +-----------+    |
                                 | | WbDataPath |    |
                                 | +-----+-----+    |
                                 +------------------+
                                          |
                                          v
                                   +-------------+
                                   |  Register   |
                                   |  File (RF)  |
                                   +-------------+
```

## 3. Width Parameters

From `Parameters.scala`, XiangShan V2 default configuration:

| Parameter | Value | Description |
|-----------|-------|-------------|
| DecodeWidth | 8 | Instructions decoded per cycle |
| RenameWidth | 8 | Instructions renamed per cycle |
| CommitWidth | 8 | Instructions committed per cycle |
| RobCommitWidth | 8 | ROB commit width |
| RabCommitWidth | 8 | RAB (RAT checkpoint buffer) commit width |

From `BackendV2SchdParams`, the dispatch and issue configuration:

| Scheduler | IssueQueues | Entries per IQ | DeqWidth per IQ | Total Issue Width |
|-----------|-------------|----------------|------------------|-------------------|
| IntScheduler | 11 IQs | 16-20 | 2 | 22 |
| FpScheduler | 3 IQs | 18 | 2 | 6 |
| VecScheduler | 4 IQs | 16 | 2 | 8 |

The total number of physical register files:
- Int Preg: 224 entries (64-bit)
- FP Preg: 192 entries (64-bit)
- Vec Preg: 128 entries (128-bit)
- V0 Preg: separate mask register file
- Vl Preg: vector length register file

## 4. Control Block Design

### 4.1 CtrlBlock Architecture

The Control Block (`CtrlBlock.scala`) is the central controller of the backend pipeline. It is implemented as a `LazyModule` containing:

- **DecodeStage**: 8-wide instruction decode
- **FusionDecoder**: detects and fuses adjacent ALU-ALU instruction pairs
- **Rename**: 8-wide register rename with speculative checkpoint support
- **Dispatch**: distributes renamed uops to Int/FP/Vec Issue Queues
- **Rob**: Reorder Buffer for in-order commit
- **RedirectGenerator**: selects the oldest redirect from multiple sources
- **MemCtrl**: memory dependency prediction (SSIT + LFST)
- **SnapshotGenerator**: for checkpoint management on branch misprediction
- **PC Memory**: stores FTQ start PCs, read by multiple functional units
- **Trace module**: trace output for hardware tracing

### 4.2 Pipeline Interconnect

The pipeline stages within CtrlBlock are connected via `PipelineConnect` modules:

```
Frontend -> [DecodeBuf] -> DecodeStage -> [PipelineConnect] -> Rename -> [PipeGroupConnect] -> Dispatch -> ROB
```

**Decode Buffer**: A `DecodeWidth`-deep buffer sits between the frontend and decode stage. It temporarily stores instructions that the decode stage cannot accept. The decode buffer uses a state machine that:
1. Prioritizes buffer contents over new frontend instructions
2. Handles redirect by clearing buffer entries
3. Implements compacting logic to remove consumed entries

**Decode-Rename Pipeline**: A `PipelineConnect` stage connects decode output to rename input, with redirect flushing support. The stage is flushed on `s1_s3_redirect` or `s2_s4_pendingRedirectValid`.

**Rename-Dispatch Pipeline**: Uses `PipeGroupConnect` for rename-to-dispatch connection, with redirect flushing. This also handles the `toRenameAllFire` signal for snapshot management.

### 4.3 Redirect and Flush Mechanism

The redirect pipeline follows a multi-stage scheme:

```
T0: rob.io.flushOut (s0_robFlushRedirect)
T1: s1_robFlushRedirect, rob.io.exception.valid
T2: csr.redirect.valid
T3: csr.exception.valid
T4: get csr.trapTarget from csr
T5: io.frontend.toFtq redirect
```

Key redirect signals:
- `s1_s3_redirect`: The unified redirect signal combining `s1_robFlushRedirect` and `s3_redirectGen`. This is the primary flush signal for the backend.
- `s2_s4_redirect`: RegNext of `s1_s3_redirect`, used for downstream pipeline stages.
- `s3_s5_redirect`: Another RegNext stage for further downstream.

The `RedirectGenerator` module arbitrates between three redirect sources:
1. `oldestExuRedirect`: from execution units (branch misprediction)
2. `loadReplay`: from memory violation (load ordering violation)
3. `robFlush`: from ROB (exception/interrupt)

The generator uses `Redirect.selectOldestRedirect` to find the oldest by ROB index, with a `flushAfter` register to suppress later redirects after one has been selected.

### 4.4 Snapshot and Checkpoint Management

The snapshot mechanism supports speculative execution recovery:

- `SnapshotGenerator` stores `RenameWidth`-wide ROB indices and CFI flags per snapshot entry.
- On redirect, `flushVec` determines which snapshots to invalidate based on whether the redirect's ROB index is newer.
- `useSnpt` and `snptSelect` determine which snapshot to restore, finding the most recent valid snapshot older than the redirect.
- Snapshot enq/deq is tied to `genSnapshot` (when rename produces a CFI) and ROB commit.

### 4.5 Memory Dependency Prediction (MemCtrl)

The `MemCtrl` module contains:
- **SSIT (Store Set Identification Table)**: predicts store-to-load dependencies using PC-indexed tables.
- **LFST (Load Functional Status Table)**: tracks load wait status for dispatch.
- **WaitTable**: stores load wait bits (currently bypassed with DontCare).

MemCtrl receives:
- Store information from memory unit (`stIn`)
- MDP training data from memory violation
- Dispatch LFST interface for load/store scheduling

## 5. Data Path Topology

### 5.1 Region Architecture

The backend uses a **Region-based architecture** (`Region.scala`). Each scheduler type (Int, FP, Vec) has its own Region containing:

```
Region {
  IssueQueues[]       -- Tomasulo reservation stations
  DataPath            -- Regfile read/write, arbiter logic
  BypassNetwork       -- Forwarding multiplexer network
  ExuBlock            -- Functional units
  WbDataPath          -- Writeback arbiter and regfile write
  WbFuBusyTable       -- Function unit busy tracking
}
```

### 5.2 DataPath Module

The `DataPath` module (`DataPath.scala`) handles:

1. **Register File Instantiation**: Each Region instantiates its own register file:
   - IntRegion: `IntRegFileBank` with multi-bank structure for parallel read
   - FPRegion: `FpRegFileSplit` with split structure
   - VecRegion: `VfRegFile` (128-bit), `V0RegFile`, `VlRegFile`

2. **Register File Read Arbitration**: `RFReadArbiter` modules (`IntRFBankReadArbiter`, `FpRFReadArbiter`, etc.) arbitrate read port access among multiple Issue Queue outputs that may target the same physical register file port.

3. **Write-Back Conflict Checking**: `IntRFWBCollideChecker`, `FpRFWBCollideChecker`, etc. detect when a newly issued instruction's source register has not yet been written back, causing a pipeline stall (the `WbNotBlock` signals).

4. **Two-Stage Issue Pipeline**:
   - **s0 (OG0)**: Issue Queue outputs uops with source register addresses. The arbiter determines read port allocation. If allocation fails (`og0FailedVec2`), the IQ is notified.
   - **s1 (OG1)**: Register file read data is available. The data is forwarded to the BypassNetwork/ExuBlock. If downstream is not ready (`og1FailedVec2`), the IQ is notified.

5. **RegCache**: A small, fast register cache for frequently used integer values, reducing access latency to the main register file. The cache is written by the BypassNetwork and read by the DataPath.

### 5.3 Cross-Region Data Paths

The Region architecture supports cross-region operations:

- **Int IQ reads FP regfile**: `intRegion.io.intIQOut` -> `fpRegion.io.fromIntIQ`, allowing integer issue queues to read FP register file data for instructions like FMV.X.W.
- **FP IQ writes Int regfile**: `fpRegion.io.fpIQOut` -> `intRegion.io.fromFpIQ`, for F2I conversions.
- **Vec IQ reads Int/FP regfile**: `vecRegion.io.fromIntIQ`, `vecRegion.io.fromFpIQ`.
- **I2F/F2I cross-domain wakeup**: `intRegion.io.cross.I2FWakeupOut` <-> `fpRegion.io.cross.I2FWakeupIn`, enabling wakeup of floating-point instructions dependent on integer results.

## 6. Bypass/Forwarding Network

### 6.1 DataSource Types

The `DataSource` module (`DataSource.scala`) encodes the source of each operand using a 4-bit encoding:

| Encoding | Name | Description |
|----------|------|-------------|
| b1000 | reg | Read from physical register file |
| b0110 | regcache | Read from register cache (fast path) |
| b0101 | v0 | Read V0 register |
| b0000 | zero | Hardwired zero (for integer source 0) |
| b0001 | forward | **Forward** from execution unit result (same cycle) |
| b0010 | bypass | **Bypass** from execution unit result (1-cycle delayed) |
| b0011 | bypass2 | **Bypass2** from execution unit result (2-cycle delayed) |
| b0100 | imm | Immediate value |

The priority chain from the Issue Queue determines which DataSource is selected for each source operand, based on the wakeup timing from the producer instruction.

### 6.2 BypassNetwork Architecture

The `BypassNetwork` module (`BypassNetwork.scala`) implements a multi-level forwarding network:

```
From DataPath (Og1InUop with DataSource tags)
        |
        v
+----------------------------------+
|     BypassNetwork                |
|                                  |
|  forwardOrBypassValidVec3:       |
|    [exuIdx][srcIdx][bypassExuOH] |
|                                  |
|  Mux1H per source operand:       |
|    readForward  -> forwardData   |
|    readBypass   -> bypassData    |
|    readBypass2  -> bypass2Data   |
|    readRegOH    -> regfile read  |
|    readRegCache -> regcache data |
|    readZero     -> 0             |
|    readImm      -> immediate     |
|                                  |
+----------------------------------+
        |
        v
  To ExuBlock (NewExuInput with resolved data)
```

**Forward Path**: Single-cycle forwarding from an execution unit's output directly to the consuming instruction's input. The `forwardDataVec` collects all execution unit results.

**Bypass Path**: One-cycle delayed forwarding using `RegNext` on the execution unit outputs. The `bypassDataVec` holds delayed results.

**Bypass2 Path**: Two-cycle delayed forwarding, specifically for vector register file access where the latency is higher. The `bypass2DataVec` holds results from vector-capable execution units that write VfRF.

**Forward/Bypass Valid Signals**: The `forwardOrBypassValidVec3` is a 3-dimensional vector indexed by `[exuIdx][srcIdx][bypassExuIdx]`. Each element indicates whether the corresponding execution unit's result is a valid forward/bypass source for the given operand.

### 6.3 Bypass Network Connections

The BypassNetwork connects:
- **Inputs from DataPath**: `fromDataPath.int/fp/vf` carry `Og1InUop` bundles with source operand addresses and data source tags.
- **Inputs from ExuBlock**: `fromExus.int/fp/vf` carry `ExuBypassBundle` with results from all execution units.
- **Outputs to ExuBlock**: `toExus.int/fp/vf` carry fully resolved `NewExuInput` bundles.
- **Outputs to RegCache**: `toDataPath` carries RegCache write data for fast integer register caching.

## 7. Writeback Bus

### 7.1 WbDataPath Module

The `WbDataPath` module (`WbArbiter.scala`) routes execution results to the correct physical register file:

1. **Dispatcher**: Each execution unit output is dispatched to the appropriate register file write arbiter based on `writeIntRf`, `writeFpRf`, `writeVecRf`, `writeV0Rf`, `writeVlRf` flags.

2. **Five Parallel Arbiters**:
   - `intWbArbiter` (RealWBCollideChecker for IntRF)
   - `fpWbArbiter` (RealWBCollideChecker for FpRF)
   - `vfWbArbiter` (RealWBCollideChecker for VfRF)
   - `v0WbArbiter` (RealWBCollideChecker for V0RF)
   - `vlWbArbiter` (RealWBCollideChecker for VlRF)

3. **Collision Checking**: Within each arbiter, `RealWBArbiter` prioritizes among multiple execution units sharing the same write port, using the priority field from `WbConfig`.

4. **Vector Load Merge**: `VldMergeUnit` handles merging of vector load results before writeback.

5. **Latency Handling**:
   - **Certain latency** execution units: writeback is guaranteed; assertion fails if arbitration is lost.
   - **Uncertain latency** execution units: can be stalled if arbitration fails, holding the result until successful.

### 7.2 Writeback to CtrlBlock

The writeback path to the Control Block carries `WriteBackRobBundle` containing:
- ROB index
- Exception vector
- Redirect information
- Writeback metadata (for performance counters)

The CtrlBlock receives all writeback data through `ctrlBlock.io.fromWB.wbData`, which is a concatenation of Int, FP, and Vec Region writebacks. It then:
1. Delays and filters writebacks (`delayedNotFlushedWriteBack`) to avoid recording killed instructions.
2. Counts writebacks with matching ROB indices for performance counters.
3. Feeds them to the ROB for instruction retirement.

### 7.3 Writeback to Issue Queues (Wakeup)

The Region module connects writeback results to Issue Queues for wakeup:

- **IQ Wakeup**: Each IQ receives `wakeupFromIQ` signals from other IQs, indicating that a producer instruction's result is ready.
- **WB Wakeup**: `wakeupFromWB` provides writeback-based wakeup for cross-region dependencies (e.g., Vec IQ receiving Int/FP writeback).
- **Delayed Wakeup**: `wakeupFromWBDelayed` (1-cycle delayed) for timing optimization.
- **LDU Wakeup**: Load unit wakeup for memory-dependent instructions.
- **Cross-Domain Wakeup**: I2F/F2I wakeup for data conversion instructions.

## 8. Pipeline Control Signals

### 8.1 Stall Signals

Pipeline stalls propagate from back to front:

1. **Issue Queue Full**: `rob.io.enq.canAccept` limits dispatch rate.
2. **ROB Full**: `rob.io.robFull` stalls the frontend.
3. **Load/Store Queue Full**: `lqCanAccept`, `sqCanAccept` from memory block.
4. **Data Hazard**: `rdSrcsNotBlock` in DataPath detects unresolved source operands; `WbNotBlock` detects writeback conflicts.
5. **OG0/OG1 Failure**: When issue arbitration fails, the IQ retains the entry.
6. **blockBackward**: Instructions like fence set `blockBackward`, preventing later instructions from dispatching.
7. **Snapshot Full**: `snptIsFull` stalls rename when all snapshots are occupied.

### 8.2 Flush Signals

The backend uses a hierarchical flush scheme:

- `s1_s3_redirect`: Primary flush signal, propagates to:
  - Decode stage
  - Rename (via `rename.io.redirect`)
  - Dispatch (via `dispatch.io.redirect`)
  - Issue Queues (via `io.toIssueBlock.flush`)
  - ROB (via `rob.io.redirect`)
  - Memory block (via `io.mem.redirect`)

- `s2_s4_redirect`: One-cycle delayed flush for:
  - DataPath
  - ExuBlock
  - All Regions' flushCopyRegVec

- `flushCopyRegVec`: Each Region creates `issueQueues.size + 2` copies of the flush signal (RegNextWithEnable) for timing distribution across multiple Issue Queues.

### 8.3 Cancel Signals

- **og0Cancel**: Generated by DataPath when an OG0-stage instruction is cancelled. Used by Issue Queues to remove entries.
- **og1Cancel**: Generated when an OG1-stage instruction cannot proceed.
- **ldCancel**: Load cancel signals from memory block, propagated to Issue Queues for load dependency cancellation.

### 8.4 Commit Signal

The commit path flows from ROB:
- `rob.io.commits` provides `commitValid`, `isCommit`, and commit info.
- The CtrlBlock forwards commit to frontend (`io.frontend.toFtq.commit`) for FTQ management.
- ROB also provides `rob.io.wfi` for WFI (Wait For Interrupt) handling.
- Store commit information (`scommit`, `lcommit`) flows to memory block.

## 9. Key Source File Locations

| Component | Path |
|-----------|------|
| Backend Top-Level | `src/main/scala/xiangshan/backend/Backend.scala` |
| Backend Parameters | `src/main/scala/xiangshan/backend/BackendParams.scala` |
| CtrlBlock | `src/main/scala/xiangshan/backend/CtrlBlock.scala` |
| Region | `src/main/scala/xiangshan/backend/Region.scala` |
| RedirectGenerator | `src/main/scala/xiangshan/backend/ctrlblock/RedirectGenerator.scala` |
| MemCtrl | `src/main/scala/xiangshan/backend/ctrlblock/MemCtrl.scala` |
| LsInfo | `src/main/scala/xiangshan/backend/ctrlblock/LsInfo.scala` |
| DataPath | `src/main/scala/xiangshan/backend/datapath/DataPath.scala` |
| BypassNetwork | `src/main/scala/xiangshan/backend/datapath/BypassNetwork.scala` |
| DataSource | `src/main/scala/xiangshan/backend/datapath/DataSource.scala` |
| WbDataPath/WbArbiter | `src/main/scala/xiangshan/backend/datapath/WbArbiter.scala` |
| RF Read Arbiter | `src/main/scala/xiangshan/backend/datapath/RFReadArbiter.scala` |
| RF WB Conflict Checker | `src/main/scala/xiangshan/backend/datapath/RFWBConflictChecker.scala` |
| WbFuBusyTable | `src/main/scala/xiangshan/backend/datapath/WbFuBusyTable.scala` |
| DataConfig | `src/main/scala/xiangshan/backend/datapath/DataConfig.scala` |
| WakeUpConfig | `src/main/scala/xiangshan/backend/datapath/WakeUpConfig.scala` |
| WbConfig | `src/main/scala/xiangshan/backend/datapath/WbConfig.scala` |
| RdConfig | `src/main/scala/xiangshan/backend/datapath/RdConfig.scala` |
| Bundles | `src/main/scala/xiangshan/backend/Bundles.scala` |
| PipeGroupConnect | `src/main/scala/xiangshan/backend/PipeGroupConnect.scala` |
| NewPipelineConnect | `src/main/scala/xiangshan/backend/datapath/NewPipelineConnect.scala` |
| TopDownGen | `src/main/scala/xiangshan/backend/TopDownGen.scala` |
| GPAMem | `src/main/scala/xiangshan/backend/GPAMem.scala` |
| Parameters | `src/main/scala/xiangshan/Parameters.scala` |

## 10. Execution Unit Configuration and Functional Unit Distribution

### 10.1 Integer Region Execution Units

From `BackendV2SchdParams`, the IntScheduler contains 11 Issue Queues with the following execution units:

| IQ Name | ExuUnit | Functional Units | WB Ports | Read Ports | DeqWidth |
|---------|---------|------------------|----------|------------|----------|
| IQ0 | ALU0 | Alu, Csr, Fence | IntWB(0,0) | IntRD(0,0), IntRD(4,0) | 2 |
| | BJU0 | Brh, Jmp | (no WB) | IntRD(0,1), IntRD(4,1) | |
| IQ1 | ALU1 | Alu, Div | IntWB(1,0) | IntRD(1,0), IntRD(5,0) | 2 |
| | BJU1 | Brh, Jmp | (no WB) | IntRD(1,1), IntRD(5,1) | |
| IQ2 | ALU2 | Alu, I2f, VSetRiWi, I2v, Bku, Mul | IntWB(2,0), VfWB(4,0), V0WB(2,0), FpWB(0,1) | IntRD(2,0), IntRD(6,0) | 2 |
| | BJU2 | Brh, Jmp | (no WB) | IntRD(2,1), IntRD(6,1) | |
| IQ3 | ALU3 | Alu, Bku, Mul | IntWB(3,0) | IntRD(3,0), IntRD(7,0) | 2 |
| IQ4 | LDU0 | Ldu | IntWB(4,0), FpWB(3,0) | IntRD(8,0) | 2 |
| IQ5 | LDU1 | Ldu | IntWB(5,0), FpWB(4,0) | IntRD(9,0) | 2 |
| IQ6 | LDU2 | Ldu | IntWB(6,0), FpWB(5,0) | IntRD(10,0) | 2 |
| IQ7 | STA0 | Sta, Mou | FakeIntWB | IntRD(3,1) | 2 |
| IQ8 | STA1 | Sta, Mou | FakeIntWB | IntRD(7,1) | 2 |
| IQ9 | STD0 | Std, Moud | (no WB) | IntRD(4,2), FpRD(9,0) | 2 |
| IQ10 | STD1 | Std, Moud | (no WB) | IntRD(5,2), FpRD(10,0) | 2 |

Key observations:
- ALU0/1/2/3 are the primary integer ALU units, each paired with a BJU (Branch/Jump Unit) for branch resolution.
- LDU0/1/2 handle integer load operations with dual-writeback capability (both IntRF and FpRF).
- STA0/1 handle store address generation using `FakeIntWB` (no real integer writeback, used for wakeup).
- STD0/1 handle store data operations, reading either Int or FP source data.
- ALU2 is the most versatile unit, supporting integer-to-float conversion (I2f), vector integer operations (I2v), and multiplication (Mul).

### 10.2 Floating-Point Region Execution Units

| IQ Name | ExuUnit | Functional Units | WB Ports | Read Ports |
|---------|---------|------------------|----------|------------|
| IQ0 | FEX0 | Falu, Fmac, Fcvt, Fcmp, F2v | FpWB(0,0), IntWB(3,1), VfWB(5,0), V0WB(3,0) | FpRD(0,0), FpRD(1,0), FpRD(2,0) |
| IQ1 | FEX1 | Falu, Fmac, Fdiv | FpWB(1,0) | FpRD(3,0), FpRD(4,0), FpRD(5,0) |
| IQ2 | FEX2 | Falu, Fmac, Fdiv | FpWB(2,0) | FpRD(6,0), FpRD(7,0), FpRD(8,0) |

FEX0 is notable for its wide writeback capability, writing to both IntRF (for F2I conversions), FpRF, VfRF, and V0RF. FEX1 and FEX2 include hardware floating-point divider (Fdiv), which has uncertain latency.

### 10.3 Vector Region Execution Units

| IQ Name | ExuUnit | Functional Units | WB Ports | Read Ports |
|---------|---------|------------------|----------|------------|
| IQ0 | VFEX0 | Vialu, Vfalu, Vfma, Vimac, Vppu, Vipu, Vfcvt, VSetRvfWvf, Vmove | VfWB(0,0), V0WB(0,0), IntWB(7,0), FpWB(6,0) | VfRD(0,0)..VfRD(2,0), V0RD(0,0) |
| IQ1 | VFEX1 | Vialu, Vfalu, Vfma, Vfdiv, Vidiv | VfWB(1,0), V0WB(1,0), FpWB(7,0) | VfRD(3,0)..VfRD(5,0), V0RD(1,0) |
| IQ2 | VLSU0 | Vldu, Vstu, Vsegldu, Vsegstu | VfWB(2,0), V0WB(2,0) | VfRD(6,0)..VfRD(8,0), V0RD(2,0) |
| IQ3 | VLSU1 | Vldu, Vstu | VfWB(3,0), V0WB(3,0) | VfRD(9,0)..VfRD(11,0), V0RD(3,0) |

VFEX0 is the primary vector ALU unit with a wide range of vector integer, float, and fixed-point operations. VFEX1 adds vector division support. VLSU0/1 handle vector load/store operations with segment load/store support in VLSU0.

### 10.4 Wakeup Configuration

The IQ wakeup configuration (`iqWakeUpParams`) defines which execution units can wake up which Issue Queues:

```
WakeUpConfig(
  Seq("ALU0", "ALU1", "ALU2", "ALU3", "LDU0", "LDU1", "LDU2") ->
  Seq("ALU0", "ALU1", "ALU2", "ALU3", "LDU0", "LDU1", "LDU2",
      "STA0", "STA1", "STD0", "STD1", "BJU0", "BJU1", "BJU2")
)
```

This means integer ALU and LDU results can wake up all integer Issue Queues (including STA, STD, and BJU units). Cross-domain wakeup is configured separately:
- FP execution units (FEX0/1/2) wake up FP Issue Queues.
- LDU results wake up FP Issue Queues (for load-to-FP forwarding).
- FP FEX results wake up STD Issue Queues (for FP store data).

## 11. Register Cache (RegCache) Mechanism

The integer DataPath includes a `RegCache` module that provides a fast, small register file cache for frequently accessed integer values. The RegCache is designed to reduce access latency and bank conflict pressure on the main integer register file.

**Write Path**: The BypassNetwork writes to the RegCache whenever an execution unit produces an integer result. The write port carries:
- `wen`: write enable (gated by the execution unit's valid and intWen signals)
- `addr`: the physical register destination address (tagged with `pdest`)
- `data`: the execution unit's integer result

**Read Path**: The DataPath reads the RegCache when an Issue Queue entry specifies `readRegCache` in its DataSource. The RegCache index (`rcIdx`) is a compressed index that maps physical register addresses to cache slots. The read data is then provided to the BypassNetwork as `toBypassNetworkRCData`.

**RC Index Management**: The `WakeupQueue` tracks RegCache indices for each issued instruction. When an instruction is allocated in the IQ, its corresponding RC index is stored. On wakeup, the RC index is forwarded along with the wakeup signal, allowing the consuming instruction to access the correct cache slot.

The RegCache has the following dimensions:
- Read ports: `getIntExuRCReadSize + getMemExuRCReadSize` (covering all integer and memory unit source operands)
- Write ports: `getIntExuRCWriteSize + getMemExuRCWriteSize` (one per ALU/LDU unit with IQ wakeup source capability)

## 12. Snapshot Flush Logic and Recovery

The snapshot flush mechanism is critical for correct speculative execution recovery. The logic works as follows:

1. **Snapshot Enqueue**: When rename produces a CFI (Control Flow Instruction) instruction, `genSnapshot` is asserted. The `SnapshotGenerator` records the ROB indices of all `RenameWidth` instructions in that cycle, along with their CFI flags.

2. **Flush Determination**: On redirect, each snapshot entry is checked:
   ```
   shouldFlush(i) = snapshot.robIdx >= redirect.robIdx (or == if !flushItself)
   shouldFlushMask = Cat(shouldFlush.zip(notCFIMask).map(x => x._1 | x._2))
   ```
   This ensures that snapshots containing the redirect's ROB index or later are invalidated, while preserving snapshots for instructions older than the redirect.

3. **Snapshot Selection**: `snptSelect` chooses the most recent valid snapshot older than the redirect using a priority mux from `enqPtr - 1` down to `enqPtr - RenameSnapshotNum`.

4. **Recovery**: The selected snapshot's ROB indices are used to:
   - Restore the ROB state (via `rob.io.snpt.snptSelect` and `rob.io.snpt.flushVec`)
   - Restore the RAT (Register Alias Table) state (via `rename.io.ratSnpt.snptSelect` and `rename.io.ratSnpt.flushVec`)
   - Invalidate rename snapshots that should be flushed (`snpt.io.flushVec`)

5. **BlockBackward Handling**: The first element of `renameOut` carries the combined snapshot valid signal. When a `blockBackward` instruction (e.g., fence) is present, the snapshot is set to prevent dispatch of later instructions until the fence completes.

## 13. Decoded Instruction Flow and Fusion

The decode-to-rename flow includes a **FusionDecoder** that identifies pairs of adjacent instructions that can be fused into a single macro-operation:

- The FusionDecoder receives `DecodeWidth` instructions from the decode stage.
- It examines pairs of adjacent non-exception instructions.
- When fusion is detected (e.g., ADD + BEQ can be fused into a single compare-and-branch), the second instruction is cleared (`fusionDecoder.io.clear(i+1)`), and the first instruction's fields are updated with the fusion info.
- The `disableFusion` signal (from CSR `singlestep` or `fusion_enable`) can globally disable fusion.

After fusion handling, instructions flow to rename via `PipelineConnect`. The rename stage performs:
1. RAT (Register Alias Table) lookup for source operands
2. Physical register allocation from the freelist
3. RAT update with new mappings
4. Snapshot generation for CFIs

The rename output connects to dispatch via `PipeGroupConnect`, which groups rename outputs into dispatch-width bundles and manages the `toRenameAllFire` signal for snapshot management.

## 14. Summary of Key Design Decisions

1. **Region-based partitioning**: The backend is split into Int, FP, and Vec regions, each with independent Issue Queues, DataPath, BypassNetwork, and ExuBlock. Cross-region dependencies are handled through explicit wakeup and data forwarding ports.

2. **Three-level forwarding (forward/bypass/bypass2)**: Forward provides same-cycle data, bypass provides 1-cycle delayed data, and bypass2 provides 2-cycle delayed data. This allows the Issue Queue to select the optimal data source based on producer-consumer timing.

3. **RegCache optimization**: A small, fast integer register cache supplements the main register file, reducing access latency for frequently used values and bypassing bank conflict issues.

4. **Multi-level redirect pipeline**: The 5-stage redirect pipeline (T0-T5) provides sufficient time for target computation while maintaining correct flush behavior across all pipeline stages.

5. **Five parallel register file arbiters**: Separate arbiters for Int, FP, Vf, V0, and Vl register files allow independent writeback without cross-contention.

6. **Snapshot-based checkpoint management**: The rename module maintains multiple snapshots for speculative state recovery on branch misprediction, supporting efficient recovery to the correct checkpoint.

7. **Decoupled decode buffer**: The decode buffer between frontend and decode stage absorbs timing mismatches and reduces frontend stall pressure.

8. **Issue Queue 2-stage deq pipeline (OG0/OG1)**: The two-stage dequeue with register file read arbitration in between allows for clock gating and timing optimization while maintaining high throughput.
