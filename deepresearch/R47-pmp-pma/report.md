The Write tool is not available in this subagent context. Below is the complete report content that should be written to `/home/agi/workspace/gitwork/XiangShan/desearch/R47-pmp-pma/report.md`.

---

# R47 - PMP & PMA Implementation Deep Dive (XiangShan)

## Overview

XiangShan RISC-V processor implements complete Physical Memory Protection (PMP) and Physical Memory Attributes (PMA) mechanisms. PMP is the hardware memory protection mechanism defined in the RISC-V privileged specification, preventing lower-privilege software from illegally accessing higher-privilege memory regions. PMA defines physical memory region attributes such as cacheability and atomic support. XiangShan unifies both within a common match/check framework and designs a Partial Static PMP optimization for timing-critical paths.

---

## 1. PMP Entry Structure (pmpcfg + pmpaddr)

### 1.1 PMPConfig Register Fields

`PMPConfig` at `src/main/scala/xiangshan/backend/fu/PMP.scala:41-61` is the hardware representation of a PMP entry configuration byte:

```scala
class PMPConfig(implicit p: Parameters) extends PMPBundle {
  val l = Bool()       // bit[7] -- Lock bit
  val c = Bool()       // bit[6] -- Reserved in PMP; Cacheable attribute in PMA
  val atomic = Bool()  // bit[5] -- Reserved in PMP; Atomic attribute in PMA
  val a = UInt(2.W)    // bit[5:4] -- Address-matching mode
  val x = Bool()       // bit[2] -- Execute permission
  val w = Bool()       // bit[1] -- Write permission
  val r = Bool()       // bit[0] -- Read permission
}
```

Key helper methods: `off` (a==0, disabled), `tor` (a==1, Top of Range), `na4` (a==2, naturally aligned 4-byte, unavailable when CoarserGrain > 2), `napot` (a==3, naturally aligned power-of-two >= 8 bytes). The `addr_locked` method implements TOR lock chaining: an entry is locked if its own L bit is set OR if the next entry is locked and uses TOR mode (`locked || (next.locked && next.tor)`).

### 1.2 PMP Address and Mask

`PMPEntry` (PMP.scala:268-287) extends `PMPBase` with an additional `mask` field for NAPOT matching acceleration:

```scala
class PMPEntry(implicit p: Parameters) extends PMPBase with PMPMatchMethod {
  val mask = UInt(PMPAddrBits.W)  // pre-computed mask for NAPOT matching
}
```

The `compare_addr` method recovers the full physical address from the stored addr (right-shifted by PMPOffBits=2) and masks off PlatformGrain-low bits: `((addr << PMPOffBits) & ~(((1 << PlatformGrain) - 1).U(PMPAddrBits.W))).asUInt`

### 1.3 NewCSR Architecture PMPEntry

In the NewCSR implementation at `src/main/scala/xiangshan/backend/fu/NewCSR/PMPEntryModule.scala:161-177`, PMPEntry uses structured CSRBundle-style fields:

```scala
class PMPEntry(implicit p: Parameters) extends PMPBundle with PMPReadWrite {
  val cfg  = new PMPCfgBundle    // CSRBundle-style config with typed fields
  val addr = new PMPAddrBundle   // Contains ADDRESS field
  val mask = UInt(PMPAddrBits.W)
}
```

`PMPCfgBundle` (CSRPMP.scala:80-89) enforces write constraints: `W := W_new & R_new` prevents illegal W=1/R=0 states; for CoarserGrain, `A` is forced to `Cat(a(1), a.orR)`, remapping NA4 to NAPOT when the grain is coarse.

### 1.4 Parameters

Defined in `src/main/scala/xiangshan/PMParameters.scala:27-42`:
- `NumPMPReal = 32` (actual PMP entries implemented)
- `NumPMAReal = 32` (actual PMA entries implemented)
- `PlatformGrain = log2Ceil(4*1024) = 12` (4KB granularity)

---

## 2. PMP Address Matching (TOR / NAPOT / NA4)

### 2.1 Match Mode Summary

| Mode | `cfg.a` | Description |
|------|---------|-------------|
| OFF  | 00      | Disabled |
| TOR  | 01      | Matches [PMP[i-1].addr, PMP[i].addr) |
| NA4  | 10      | Naturally aligned 4-byte (unavailable with coarse grain) |
| NAPOT| 11      | Naturally aligned power-of-two >= 8 bytes |

### 2.2 Core Match Logic

Entry point at `PMPMatchMethod.is_match()` (PMP.scala:200-203):

```scala
def is_match(paddr: UInt, lgSize: UInt, lgMaxSize: Int, last_pmp: PMPEntry): Bool = {
  Mux(cfg.na4_napot, napotMatch(paddr, lgSize, lgMaxSize),
    Mux(cfg.tor, torMatch(paddr, lgSize, lgMaxSize, last_pmp), false.B))
}
```

`na4_napot = a(1)`: when bit[1] is set (NA4 or NAPOT), both use NAPOT match logic since NA4 is a degenerate NAPOT case.

### 2.3 TOR Matching

TOR mode uses the current entry's addr as upper bound and the previous entry's addr as lower bound (PMP.scala:229-231):

```scala
def torMatch(paddr: UInt, lgSize: UInt, lgMaxSize: Int, last_pmp: PMPEntry): Bool = {
  last_pmp.lowerBoundMatch(paddr, lgSize, lgMaxSize) && higherBoundMatch(paddr, lgMaxSize)
}
```

`boundMatch` handles cross-`lgMaxSize` boundary comparisons by splitting into high and low parts. For `lgMaxSize > PlatformGrain`, it compares the upper bits and lower bits independently, using `OneHot.UIntToOH1(lgSize, lgMaxSize)` to account for the access size extending past the comparison boundary.

### 2.4 NAPOT Matching

NAPOT matching uses the pre-computed `mask` for efficient comparison (PMP.scala:237-246):

```scala
def napotMatch(paddr: UInt, lgSize: UInt, lgMaxSize: Int) = {
  if (lgMaxSize <= PlatformGrain) {
    unmaskEqual(paddr, compare_addr, mask)  // (a & ~m) === (b & ~m)
  } else {
    val lowMask = mask | OneHot.UIntToOH1(lgSize, lgMaxSize)
    val highMatch = unmaskEqual(paddr >> lgMaxSize, compare_addr >> lgMaxSize, mask >> lgMaxSize)
    val lowMatch = unmaskEqual(paddr(lgMaxSize-1,0), compare_addr(lgMaxSize-1,0), lowMask(lgMaxSize-1,0))
    highMatch && lowMatch
  }
}
```

The `match_mask` method (PMP.scala:84-87) pre-computes the mask at entry write time using the NAPOT boundary property: `addr & ~(addr + 1)` produces a mask of consecutive 1-bits from the MSB downward. This makes runtime matching a single AND-NOT-Compare operation.

### 2.5 Alignment Check

`aligned` (PMP.scala:248-261) checks whether memory accesses spanning region boundaries are properly aligned, preventing protection bypass through cross-boundary accesses. For NAPOT: `napotAligned = (lowBitsMask & ~mask(lgMaxSize-1,0)) === 0.U`.

---

## 3. PMP Permission Checking Logic

### 3.1 pmp_check

`PMPCheckMethod.pmp_check()` at PMP.scala:404-412 determines violations based on the matched entry's permissions and the requested access type:

```scala
resp.ld    := TlbCmd.isRead(cmd) && !TlbCmd.isAmo(cmd) && !cfg.r
resp.st    := (TlbCmd.isWrite(cmd) || TlbCmd.isAmo(cmd)) && !cfg.w
resp.instr := TlbCmd.isExec(cmd) && !cfg.x
resp.mmio  := false.B    // PMP does not produce MMIO signals
resp.atomic := false.B   // PMP does not produce atomic signals
```

### 3.2 PMPRespBundle

`PMPRespBundle` (PMP.scala:385-401) is the standard output: `ld` (load access fault), `st` (store access fault), `instr` (instruction access fault), `mmio` (MMIO flag, PMA only), `atomic` (atomic support, PMA only). The `|` operator OR-aggregates results from multiple sources.

### 3.3 pmp_match_res: Global Priority Logic

`pmp_match_res()` (PMP.scala:414-457) traverses all entries and uses `ParallelPriorityMux` to select the first matching entry's permissions:

- `passThrough = mode > 1`: In M-mode, unmatched default entry allows all access
- `ignore = passThrough && !pmp.cfg.l`: In M-mode, non-locked entries bypass permission checking
- Final permission: `aligned && (pmp.cfg.r || ignore)`

### 3.4 PMPChecker

`PMPChecker` (PMP.scala:556-617) wraps combined PMP+PMA checking with optional KeyID support:

1. Strips KeyID from address if `keyIDen` is set (for CVM/confidential VM)
2. Runs PMP and PMA matching independently via `pmp_match_res` and `pma_match_res`
3. Checks PMP and PMA permissions via `pmp_check` and `pma_check`
4. Performs KeyID validation (non-zero KeyID forbidden for non-cmode, non-M-mode)
5. OR-aggregates all three results: `resp = resp_pmp | resp_pma | resp_keyid`

`pmpUsed` parameter allows disabling PMP in specific paths (keeping only PMA).

### 3.5 PMPCheckerv2

`PMPCheckerv2` (PMP.scala:620-656) returns a merged `PMPConfig` instead of `PMPRespBundle`, AND-ing PMP and PMA permissions:

```scala
tmp_res.r := pmp.cfg.r && pma.cfg.r  // intersection of PMP and PMA permissions
tmp_res.c := pma.cfg.c               // cacheability from PMA only
tmp_res.atomic := pma.cfg.atomic     // atomic from PMA only
```

---

## 4. PMA (Physical Memory Attributes) Implementation

### 4.1 PMA vs PMP

PMA reuses the PMP entry structure and matching logic but differs semantically:
- **PMP**: Protection mechanism -- decides if access is allowed; no mmio/atomic signals
- **PMA**: Attribute mechanism -- defines cacheability, atomic, etc.; must be checked even in M-mode

`PMAConfigEntry` (PMA.scala:42-52) defines initial PMA configuration with `c` (Cacheable) and `atomic` fields unique to PMA.

### 4.2 PMA Permission Check

`pma_check()` (PMA.scala:211-219) differs from PMP in key ways:
- AMO operations additionally check `cfg.atomic` (`!cfg.atomic || !cfg.w`)
- Produces `mmio := !cfg.c` (non-cacheable = MMIO) and `atomic := cfg.atomic`
- Does NOT distinguish AMO from read for the `ld` signal

### 4.3 PMA Match Behavior

`pma_match_res()` has **no passThrough default**: `pmaDefault` has all permissions/attributes set to false. Unmatched regions default to non-accessible, non-cacheable, non-atomic. PMA requires initialization covering all physical address space.

### 4.4 Memory-Mapped PMA (MMPMA)

MMPMA (PMA.scala:28-111) is a special PMA subset accessible via memory-mapped interface at `0x38021000`, allowing runtime configuration of a small number of PMA entries (default 2). Uses `RegField` with `ValidHold` handshake.

---

## 5. PMP/PMA Interaction with TLB and PTW

### 5.1 PMP Deployment Locations

| Location | File | Count | lgMaxSize | sameCycle | leaveHitMux |
|----------|------|-------|-----------|-----------|-------------|
| DTLB | MemBlock.scala:694 | DTlbSize | 4 | false | true |
| ITLB | Frontend.scala:160 | 2 (ipmpPortNum) | 3 | true | false |
| L2TLB/PTW | L2TLB.scala:94 | 4-5 | 3 | true | false |

### 5.2 DTLB-PMP Interaction

In MemBlock.scala:691-705, a global PMP module and DTlbSize PMPCheckers are instantiated. Each TLB port outputs a `PMPReqBundle` after translation; the corresponding PMPChecker matches and checks permissions. With `leaveHitMux = true`, results are delayed one cycle for timing optimization. Results feed into LoadUnit (`pmp.resp.ld`), StoreUnit (`pmp.resp.st`), AtomicsUnit, and prefetchers.

### 5.3 ITLB-PMP Interaction

In Frontend.scala:156-180, ITLB uses `sameCycle = true` (single-cycle PMP check via `ParallelPriorityMux`) because instruction fetch is extremely latency-sensitive.

### 5.4 L2TLB/PTW-PMP Interaction

L2TLB.scala:93-603 instantiates 4-5 PMPCheckers:
- `pmp_check(0)` -> PTW (single-level page table walker)
- `pmp_check(1)` -> LLPTW port 0 (long-latency PTW)
- `pmp_check(2)` -> LLPTW port 1
- `pmp_check(3)` -> HPTW (hypervisor PTW)
- `pmp_check(4)` -> Bitmap cache (optional)

PTW's `s_pmp_check` state machine checks each PTE level's physical address. If PMP fails (`accessFault`), PTW raises an Access Fault exception. The `mmio` signal from PMP response is also treated as fault because page tables must reside in cacheable memory.

### 5.5 KeyID/CVM Support in L2TLB

When KeyIDBits > 0, L2TLB passes `KEYIDEN` and `CMODE` signals to PMPCheckers for confidential VM support.

---

## 6. Partial Static PMP Optimization

### 6.1 Problem

PMP checking is timing-critical: it must complete after TLB translation, involving O(log2(N)) priority MUX depth.

### 6.2 Strategy

From TLB.scala:406-409:
```
// dynamic: superpage (or full-connected reg entries) -> check pmp when translation done
// static: 4K pages (or sram entries) -> check pmp with pre-checked results
```

- **Dynamic PMP**: For superpage hits -- physical address determined only after translation; runtime PMP check required
- **Static PMP**: For 4KB page hits -- PPN known at TLB refill time; pre-compute PMP results and store in TLB entries

### 6.3 Implementation

All TLB configurations (Parameters.scala:218-254) set `partialStaticPMP = true`. At refill time, 4KB pages get pre-checked against all PMP entries. Results are stored alongside PTE data. On hit, 4KB pages output stored results directly; superpages still require runtime checking.

### 6.4 leaveHitMux Timing Optimization

DTLB's PMPChecker uses `leaveHitMux = true` (MemBlock.scala:694), pushing `ParallelPriorityMux` after registers via `RegEnable`, reducing critical path depth at the cost of one cycle (absorbed by existing pipeline registers).

---

## 7. ChiselIOPMP vs Core PMP

### 7.1 Legacy PMP (PMP.scala)

- `PMP` module combines PMP and PMA with shared `pmp_gen_mapping`
- Uses `DistributedCSRIO` for CSR writes
- `PMPEntry` extends `PMPBase` with cfg/addr/mask

### 7.2 NewCSR PMP (NewCSR/PMPEntryModule.scala)

- `PMPEntryHandleModule` wraps CSR read/write via `PMPEntryHandleIOBundle`
- Uses structured `PMPCfgBundle` (CSRBundle) for type-safe field definitions
- `PMAEntryHandleModule` (NewCSR/PMAEntryModule.scala) provides independent PMA CSR management
- CSR addresses: `PmacfgBase = 0x7C0`, `PmaaddrBase = 0x7C8`
- Supports richer `addrLocked` semantics considering TOR chaining

### 7.3 Coexistence

Both share matching logic cores (`PMPMatchMethod`/`PMPReadWrite`). NewCSR version integrates with the new CSR subsystem (`NewCSR.scala`); Legacy version is still used in some paths (e.g., ITLB PMP in Frontend.scala). `PMPChecker` is shared between both.

---

## 8. Source File Locations

| File | Content |
|------|---------|
| `src/main/scala/xiangshan/backend/fu/PMP.scala` | Core PMP: PMPConfig, PMPEntry, PMPChecker, PMPCheckerv2, matching/checking logic |
| `src/main/scala/xiangshan/backend/fu/PMA.scala` | Core PMA: PMAConfigEntry, PMAMethod, PMACheckMethod, MMPMAMethod |
| `src/main/scala/xiangshan/PMParameters.scala` | Parameters: NumPMPReal, NumPMAReal, PlatformGrain |
| `src/main/scala/xiangshan/backend/fu/NewCSR/PMPEntryModule.scala` | NewCSR PMP: PMPEntryHandleModule, PMPEntry, PMPReadWrite |
| `src/main/scala/xiangshan/backend/fu/NewCSR/CSRPMP.scala` | NewCSR PMP CSRs: PMPCfgBundle, PMPAddrBundle, PMPCfgAField |
| `src/main/scala/xiangshan/backend/fu/NewCSR/PMAEntryModule.scala` | NewCSR PMA: PMAEntryHandleModule, PMAEntry |
| `src/main/scala/xiangshan/backend/fu/NewCSR/CSRPMA.scala` | NewCSR PMA CSR definitions |
| `src/main/scala/xiangshan/cache/mmu/MMUConst.scala` | TLB params: partialStaticPMP, L2TLBParameters |
| `src/main/scala/xiangshan/cache/mmu/TLB.scala` | TLB: pmp_check output, dynamic/static PMP optimization |
| `src/main/scala/xiangshan/cache/mmu/L2TLB.scala` | L2TLB: PMP instantiation, 4-5 PMPChecker connections |
| `src/main/scala/xiangshan/cache/mmu/PageTableWalker.scala` | PTW: s_pmp_check FSM, PMP requests, accessFault handling |
| `src/main/scala/xiangshan/mem/MemBlock.scala` | MemBlock: DTLB PMPCheckers, result distribution |
| `src/main/scala/xiangshan/frontend/Frontend.scala` | Frontend: ITLB PMPCheckers (sameCycle=true) |
| `src/main/scala/xiangshan/cache/mmu/BitmapCheck.scala` | Bitmap Cache PMP checking |
| `src/main/scala/xiangshan/backend/fu/util/CSRConst.scala` | CSR address constants |
| `src/main/scala/xiangshan/Parameters.scala` | Global params: partialStaticPMP=true, ipmpPortNum=2 |

---

## Summary

XiangShan's PMP/PMA implementation is a highly engineered subsystem with key design decisions:

1. **Unified entry structure**: PMP and PMA share `PMPEntry` with `c`/`atomic` fields repurposing reserved PMP bits for PMA attributes
2. **Efficient address matching**: Pre-computed `mask` reduces NAPOT matching to one AND-NOT-Compare; `ParallelPriorityMux` provides O(log2(N)) parallel priority selection
3. **Multi-level checking**: PMPChecker deployed across L1 DTLB/ITLB, L2 TLB/PTW, and L2 Cache data paths
4. **Timing optimization**: Partial Static PMP pre-computes 4KB page results at TLB refill; `leaveHitMux` pushes priority MUX after registers
5. **CVM/KeyID support**: PMPChecker supports KeyID separation for Confidential Virtual Machine hardware isolation
6. **Incremental refactoring**: Legacy and NewCSR PMP implementations coexist, sharing matching logic cores for consistency