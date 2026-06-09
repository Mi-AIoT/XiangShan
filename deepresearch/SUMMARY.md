# XiangShan Deep Research Summary

## Project Overview
XiangShan (香山) is an open-source high-performance RISC-V processor developed by ICT/CAS.
Current version: **Kunminghu (昆明湖)**, written in Chisel (Scala).
- **446** core design files, **506** total Scala files
- **RV64GCHV** ISA support
- **8-wide** superscalar out-of-order execution
- Architecture generations: Yanqihu -> Nanhu -> Kunminghu (current)

## Research Statistics
- **36** research topics covering the entire project
- **96,373** total words across all reports
- **1.1MB** total research output
- **36/36** reports completed (100%)

## Round 1: CPU Microarchitecture (R01-R24)

| # | Topic | Words | Size |
|---|-------|-------|------|
| R01 | Overall Architecture & SoC Integration | 2,911 | 36KB |
| R02 | Frontend Pipeline | 2,565 | 29KB |
| R03 | Branch Prediction (BPU) | 2,465 | 27KB |
| R04 | Fetch Target Queue (FTQ) | 3,778 | 45KB |
| R05 | ICache & Instruction Fetch | 3,054 | 39KB |
| R06 | Instruction Decode & Rename | 1,697 | 25KB |
| R07 | Dispatch & Issue Queue | 2,900 | 34KB |
| R08 | Execution Units (EXU/FU) | 2,601 | 31KB |
| R09 | Register File & RegCache | 3,159 | 44KB |
| R10 | Reorder Buffer (ROB) | 3,006 | 35KB |
| R11 | Backend Control & DataPath | 3,961 | 32KB |
| R12 | DCache Subsystem | 4,539 | 44KB |
| R13 | Load/Store Queue & Pipeline | 2,373 | 25KB |
| R14 | Memory Dependence Prediction | 3,193 | 39KB |
| R15 | Prefetch Engine | 2,332 | 26KB |
| R16 | Store Buffer (SBuffer) | 2,186 | 29KB |
| R17 | Vector & SIMD Support | 2,487 | 36KB |
| R18 | MMU & TLB | 3,355 | 45KB |
| R19 | Privileged Architecture & CSR | 4,852 | 51KB |
| R20 | Cache Coherence & NoC | 2,614 | 27KB |
| R21 | DiffTest & Verification | 3,173 | 38KB |
| R22 | Build System & Toolchain | 3,261 | 40KB |
| R23 | Performance Monitoring & Trace | 2,049 | 30KB |
| R24 | FPGA & Physical Implementation | 2,961 | 36KB |

## Round 2: Infrastructure & Tooling (R25-R36)

| # | Topic | Words | Size |
|---|-------|-------|------|
| R25 | Utility Library | 1,886 | 37KB |
| R26 | ChiselAIA (Advanced Interrupt Architecture) | 2,111 | 27KB |
| R27 | Rocket-Chip Dependencies | 1,899 | 30KB |
| R28 | DiffTest Internals (C++ Framework) | 2,173 | 28KB |
| R29 | Development Scripts | 2,034 | 27KB |
| R30 | CI/CD Pipeline | 2,242 | 30KB |
| R31 | XSPdb Hardware Debugger | 2,026 | 38KB |
| R32 | SoC Virtual Devices | 2,726 | 32KB |
| R33 | FIRRTL Transforms | 1,825 | 23KB |
| R34 | SRAM & Memory Infrastructure | 1,755 | 23KB |
| R35 | Power & Clock Management | 1,640 | 24KB |
| R36 | Configuration & Parameterization | 2,584 | 31KB |

## Key Architectural Findings

### Frontend
- 2-stage BPU: S1 fast (uBTB/aBTB/MicroTage) + S3 precise (TAGE/SC/ITTAGE/RAS) with S3 Override
- TAGE: 8 tables, geometric history lengths 4-397
- 64-entry FTQ coordinates BPU/IFU/Backend
- 64KB 4-way ICache with FDIP prefetch

### Backend
- 8-wide rename/dispatch/commit
- 18 distributed Issue Queues (11 Int + 3 FP + 4 Vec), 310 total entries
- 3-tier entry design: EnqEntry/Simple/Complex
- Dual-layer wake-up: WB + IQ-to-IQ
- 352-entry ROB, 8-bank interleaved
- 5 register files: Int(224), FP(256), Vec(128), V0(22), Vl(32)
- Register Cache: 36 entries (24 Int + 12 Mem)

### Memory Subsystem
- 64KB 8-way DCache with 16 MSHRs, SECDED ECC
- 5-stage load pipeline (S0-S4), 8-source arbitration
- 5-level Store-to-Load Forwarding: SQ/SBuffer/UncacheBuffer/MSHR/TileLink-D
- 16-entry SBuffer with store merging and vtag/ptag dual-tag design
- Modular Load Queue: Virtual/RAR/RAW/Replay/Uncache

### Cache Hierarchy
- L1-L2: TileLink protocol
- L2-L3: ARM CHI protocol (not TileLink!)
- L2: CoupledL2, 4-bank, DRRIP replacement
- L3: OpenLLC, 4-bank, dual directory (Self + Snoop Filter)
- DCT (Direct Cache Transfer) optimization

### Privileged Architecture
- Full M/HS/HU/VS/VU mode support
- H-Extension with 2-stage address translation
- AIA (Advanced Interrupt Architecture) with IMSIC/APLIC
- 29 HPM performance counters with Smcntrpmf filtering
- Complete Debug mode with trigger chain support

### Infrastructure Highlights
- **Utility Library**: SRAM/CAM templates, PLRU/DRRIP replacement, clock gating, ChiselDB, Constantin
- **Rocket-Chip**: Diplomacy framework, TileLink/AXI4 protocol libraries, CDE configuration
- **DiffTest**: DPI-C batch mode, golden memory (mmap), NEMU/Spike ref proxy, snapshot/waveform
- **CI/CD**: 16 GitHub Actions workflows, SPEC performance regression, nightly testing
- **XSPdb**: Python hardware debugger with breakpoints, FSM triggers, waveform control
- **SoC Devices**: NS16550A UART, CLINT/PLIC, Flash, VGA (SDL2), PS/2 Keyboard, SD card
- **FIRRTL**: Custom PrintModuleName transform, XiangShanStage, split Verilog output
- **SRAM**: Multi-variant templates, SECDED ECC, FPGA TrueDualPortSRAM, MBIST/DFT
- **Power**: WFI clock-gating FSM, mcorepwr/mflushpwr CSR, L2 flush power-down sequence
- **Config**: CDE framework, YAML configs, 250+ parameters, Constantin runtime tuning

## Directory Structure
```
deepresearch/
├── RESEARCH-FRAMEWORK.md
├── SUMMARY.md
├── R01-overall-architecture/report.md
├── R02-frontend-pipeline/report.md
├── ...
├── R24-fpga-implementation/report.md
├── R25-utility-library/report.md
├── R26-chisel-aia/report.md
├── R27-rocket-chip-deps/report.md
├── R28-difftest-internals/report.md
├── R29-dev-scripts/report.md
├── R30-ci-cd-pipeline/report.md
├── R31-xspdb-debugger/report.md
├── R32-soc-devices/report.md
├── R33-firrtl-transforms/report.md
├── R34-sram-infrastructure/report.md
├── R35-power-clock-mgmt/report.md
└── R36-config-parameterization/report.md
```
