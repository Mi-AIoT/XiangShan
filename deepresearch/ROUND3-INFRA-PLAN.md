# Round 3: Infrastructure Deep Research Plan

## Goal
Further refine and expand the 12 infrastructure research reports (R25-R36) by breaking each into detailed sub-topics with dedicated research agents.

## Sub-Research Tasks (30 total)

### From R25 - Utility Library (3 sub-tasks)
| ID | Topic | Focus |
|----|-------|-------|
| R25A | SRAM Templates & Data Modules | SRAMTemplate variants, DataModuleTemplate patterns, conflict behaviors, banking strategies |
| R25B | Replacement & Arbitration | PLRU, DRRIP, replacement state machines, FastArbiter, Sort, PriorityMuxGen |
| R25C | Clock/Reset/Perf Infrastructure | ClockGate, ClockGatedReg, ClockMux, ResetGen, HardwarePerfMonitor, PerfCounterUtils, ChiselDB, Constantin |

### From R26 - ChiselAIA (2 sub-tasks)
| ID | Topic | Focus |
|----|-------|-------|
| R26A | IMSIC Design Deep Dive | IMSICGateWay, IntFile, priority arbitration, TEE support, async bridge |
| R26B | APLIC & AIA Integration | APLIC dual-domain, interrupt rectification, AIA CSR mapping, XiangShan InterruptFilter |

### From R27 - Rocket-Chip Dependencies (3 sub-tasks)
| ID | Topic | Focus |
|----|-------|-------|
| R27A | Diplomacy Framework | LazyModule, node types, negotiation protocol, edge parameters |
| R27B | TileLink Protocol Deep Dive | Channel A-E, message types, cache coherence agents, adapters, buffers |
| R27C | AXI4/CDE/Debug/HardFloat | AXI4 protocol, CDE Field/Config, Debug Module (JTAG), HardFloat FPU |

### From R28 - DiffTest Internals (3 sub-tasks)
| ID | Topic | Focus |
|----|-------|-------|
| R28A | DiffTest Core Engine | difftest.cpp check loop, DiffState, golden memory, store log, replay |
| R28B | Reference Model Integration | refproxy dlopen/dlsym, NEMU/Spike binding, multi-core isolation, compare logic |
| R28C | DPI-C & Batch Pipeline | DPIC code generation, BatchAssembler pipeline, DPICBuffer zones, Squash/Delta/Replay |

### From R29 - Development Scripts (2 sub-tasks)
| ID | Topic | Focus |
|----|-------|-------|
| R29A | xiangshan.py & CI Scripts | XSArgs/XiangShan classes, test framework, NUMA scheduling, Verilator/gsim backends |
| R29B | Analysis Tools | parser.py RTL analysis, constantHelper.py GA optimizer, top-down framework, coverage tools, perfcct.py |

### From R30 - CI/CD Pipeline (2 sub-tasks)
| ID | Topic | Focus |
|----|-------|-------|
| R30A | Test & Performance Workflows | emu.yml, emu-basics.yml, emu-performance.yml, perf-v2/v3, nightly.yml |
| R30B | Release & Quality Gates | release.yml, check_verilog.py, CODEOWNERS, pr-labeler, logrotate |

### From R31 - XSPdb Debugger (2 sub-tasks)
| ID | Topic | Focus |
|----|-------|-------|
| R31A | Breakpoint & Trigger System | xbreak, xbreak_expr, xbreak_fsm, ExprEngine, FSM execution semantics |
| R31B | Execution Control & Data I/O | xstep/xistep, waveform control, DiffTest snapshot, fork backup, memory load/export, batch mode |

### From R32 - SoC Virtual Devices (2 sub-tasks)
| ID | Topic | Focus |
|----|-------|-------|
| R32A | UART/Timer/PLIC | NS16550A emulation, CLINT/PLIC interrupt controller, SYSCNT timer |
| R32B | I/O Devices & MMIO | Flash, VGA/SDL2, PS/2 keyboard, SD card, AXI4 slave FSM, RegMap |

### From R33 - FIRRTL Transforms (2 sub-tasks)
| ID | Topic | Focus |
|----|-------|-------|
| R33A | Chisel Elaboration Pipeline | XiangShanStage, PhaseManager, PrintModuleName, ChiselCircuitHelpers |
| R33B | Verilog Generation & Post-Processing | Split verilog, git info injection, $fatal replacement, CommitIDModule |

### From R34 - SRAM Infrastructure (2 sub-tasks)
| ID | Topic | Focus |
|----|-------|-------|
| R34A | SRAM Primitives & ECC | SRAMTemplate hierarchy, TrueDualPortSRAM, SyncReadMem patterns, SECDED |
| R34B | SRAM Banking & FPGA Mapping | SplittedSRAMTemplate, FoldedSRAMTemplate, readmemh tools, FPGA vs ASIC mapping |

### From R35 - Power & Clock Management (3 sub-tasks)
| ID | Topic | Focus |
|----|-------|-------|
| R35A | Clock Gating & Reset | ClockGate ICG, GatedValidRegNext, ClockMux, ResetGen 3-stage sync |
| R35B | WFI & Core Power | WFI FSM (4 states), mcorepwr/mflushpwr CSRs, power-down sequence |
| R35C | Async Bridges & DFT | CHI AsyncBridge, CLINT AsyncBridge, DFT/MBIST, scan chain |

### From R36 - Configuration System (2 sub-tasks)
| ID | Topic | Focus |
|----|-------|-------|
| R36A | CDE & Parameters Deep Dive | CDE Field/Config mechanism, Parameters.scala hierarchy, 250+ fields catalog |
| R36B | Configuration Variants & Runtime Tuning | DefaultConfig/MinimalConfig/FPGA variants, YAML configs, Constantin runtime constants |
