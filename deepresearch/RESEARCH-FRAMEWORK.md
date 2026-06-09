# XiangShan Deep Research Framework

## Project Overview
XiangShan (香山) is an open-source high-performance RISC-V processor developed by ICT/CAS.
Current version: **Kunminghu (昆明湖)** on master branch.
Written in **Chisel (Scala)**, ~446 core design files, ~506 total Scala files.
Previous architectures: Yanqihu (雁栖湖), Nanhu (南湖).

## Research Topics (24 total)

| ID | Topic | Directory | Focus |
|----|-------|-----------|-------|
| R01 | Overall Architecture & SoC Integration | `R01-overall-architecture` | Top module, SoC wrapper, system integration, configuration system |
| R02 | Frontend Pipeline Overview | `R02-frontend-pipeline` | IFU pipeline stages, frontend flow, instruction fetch |
| R03 | Branch Prediction (BPU) | `R03-branch-prediction` | BPU architecture, predictors, BTB, TAGE, RAS |
| R04 | Fetch Target Queue (FTQ) | `R04-fetch-target-queue` | FTQ design, fetch target management |
| R05 | ICache & Instruction Fetch | `R05-icache` | ICache design, coherence, prefetch |
| R06 | Instruction Decode & Rename | `R06-decode-rename` | Decode logic, register renaming, RAT |
| R07 | Dispatch & Issue Queue | `R07-dispatch-issue` | Dispatch policy, issue queue design, scheduling |
| R08 | Execution Units (EXU/FU) | `R08-execution-units` | EXU types, functional units, ALU, multiplier, divider |
| R09 | Register File & RegCache | `R09-register-file` | Physical register file, register caching |
| R10 | Reorder Buffer (ROB) | `R10-reorder-buffer` | ROB structure, commit, exceptions |
| R11 | Backend Control & DataPath | `R11-backend-control` | Control block, datapath, pipeline integration |
| R12 | DCache Subsystem | `R12-dcache` | DCache design, MSHR, writeback, coherence |
| R13 | Load/Store Queue & Pipeline | `R13-lsq-pipeline` | Load queue, store queue, memory pipeline |
| R14 | Memory Dependence Prediction (MDP) | `R14-memory-dependence` | Store-to-load forwarding, memory disambiguation |
| R15 | Prefetch Engine | `R15-prefetch` | Hardware prefetchers, prefetch strategies |
| R16 | Store Buffer (SBuffer) | `R16-store-buffer` | SBuffer design, store merging, writeback |
| R17 | Vector & SIMD Support | `R17-vector-simd` | Vector extension support, vector memory ops, yunsuan |
| R18 | MMU & TLB | `R18-mmu-tlb` | Memory management, TLB, page table walker |
| R19 | Privileged Architecture & CSR | `R19-privileged-csr` | RISC-V privileged modes, CSR, interrupts, exceptions |
| R20 | Cache Coherence & NoC | `R20-cache-coherence` | L2/L3 cache, coherence protocol, bus/NoC |
| R21 | DiffTest & Verification | `R21-difftest-verification` | Co-simulation framework, verification methodology |
| R22 | Build System & Toolchain | `R22-build-toolchain` | Chisel build, Makefile, Mill, FIRRTL, Verilog generation |
| R23 | Performance Monitoring & Trace | `R23-performance-trace` | PMU, hardware trace, performance counters, top-down analysis |
| R24 | FPGA & Physical Implementation | `R24-fpga-implementation` | FPGA flow, ChiselIOPMP, XSCache, physical considerations |

## Research Topics - Round 2: Infrastructure & Tooling (12 topics)

| ID | Topic | Directory | Focus |
|----|-------|-----------|-------|
| R25 | Utility Library | `R25-utility-library` | SRAM/CAM templates, replacement, clock gating, perf counters, logging, ChiselDB |
| R26 | ChiselAIA (Advanced Interrupt Architecture) | `R26-chisel-aia` | AIA module, IMSIC, interrupt routing, AIA CSR integration |
| R27 | Rocket-Chip Dependencies | `R27-rocket-chip-deps` | Diplomacy framework, TileLink/AXI4 protocol, regmapper, CDE config |
| R28 | DiffTest Internals (C++ Framework) | `R28-difftest-internals` | Golden memory, ref proxy, snapshot, waveform, emulator core, Verilator/VCS |
| R29 | Development Scripts | `R29-dev-scripts` | xiangshan.py, parser.py, constantHelper.py, rolling.py, perfcct.py |
| R30 | CI/CD Pipeline | `R30-ci-cd-pipeline` | GitHub Actions, nightly testing, performance regression, release flow |
| R31 | XSPdb Hardware Debugger | `R31-xspdb-debugger` | Python debugger, breakpoints, triggers, FSM, waveform, snapshot |
| R32 | SoC Virtual Devices | `R32-soc-devices` | UART, Timer, PLIC, Flash, VGA, Keyboard, SD card emulation |
| R33 | FIRRTL Transforms | `R33-firrtl-transforms` | Custom FIRRTL passes, XiangShanStage, PrintModuleName, elaboration |
| R34 | SRAM & Memory Infrastructure | `R34-sram-infrastructure` | SRAM templates, data modules, ECC, TrueDualPortSRAM, memory init |
| R35 | Power & Clock Management | `R35-power-clock-mgmt` | Clock gating, WFI, reset generation, power domains, DFT/MBIST |
| R36 | Configuration & Parameterization | `R36-config-parameterization` | CDE framework, YAML configs, parameter hierarchy, runtime tuning |
