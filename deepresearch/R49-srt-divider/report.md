# R49 - SRT16 Integer Divider Deep-Dive Report

## 1. Overview

XiangShan's integer divider is implemented in `src/main/scala/xiangshan/backend/fu/SRT16Divider.scala` (477 lines). It uses a **SRT Radix-16 digit-recurrence** algorithm that produces **4 quotient bits per iteration**, significantly reducing iteration count compared to radix-2 or radix-4 designs.

The implementation is credited to Yifei He (hyf_sysu@qq.com) and references:

> Antelo, Lang, Montuschi, Nannarelli. "Digit-recurrence dividers with reduced logical depth." IEEE TC 54.7, 2005.

Core design parameters:
- **Parameterized width**: `len` (supports 32-bit and 64-bit)
- **Internal width**: `itn_len = 1 + len + 2 + 1` (sign + data + guard + round bits)
- **Quotient digit set**: `{-2, -1, 0, +1, +2}` (symmetric redundant, radix-4 steps combined into radix-16 per cycle)

---

## 2. SRT Radix-16 Division Algorithm

### 2.1 Mathematical Foundation

SRT (Sweeney-Robertson-Tocher) division uses a **redundant digit representation** for quotient digits. Unlike restoring division, the partial remainder may become negative without immediate correction, trading exactness for reduced critical path delay.

The fundamental iteration is:

```
w[j+1] = 16 * w[j] - q[j+1] * d
```

where:
- `w[j]` is the partial remainder (stored in carry-save form: `rSum`, `rCarry`)
- `q[j+1]` is the selected quotient digit from {-2, -1, 0, +1, +2}
- `d` is the normalized divisor

The redundancy factor r=4 satisfies the SRT convergence condition alpha+beta < r where alpha=beta=2.

### 2.2 Radix-16 as Two Cascaded Radix-4 Steps

Although named "Radix-16," the hardware implements this as **two radix-4 steps per clock cycle**. This is visible in the iteration core (lines 300-309):

```scala
// First CSA: w[j+1] = 4*w[j] - q[j]*d
csaWide1.io.in(0) := rSumReg << 2
csaWide1.io.in(1) := rCarryReg << 2
csaWide1.io.in(2) := Mux1H(qPrevReg, udNegReg.toSeq) << 2

// Second CSA: w[j+2] = 4*w[j+1] - q[j+1]*d
csaWide2.io.in(0) := csaWide1.io.out(0) << 2
csaWide2.io.in(1) := (csaWide1.io.out(1) << 1)(itn_len-1, 0) << 2
csaWide2.io.in(2) := Mux1H(qNext, udNegReg.toSeq) << 2
```

Each step multiplies by 4 (left-shift by 2) and subtracts `q*d`. The two cascaded CSAs produce the updated partial remainder for two radix-4 steps in one cycle.

---

## 3. FSM States and Transitions

### 3.1 State Encoding

The FSM uses one-hot encoding with 7 states (line 60):

```scala
val s_idle :: s_pre_0 :: s_pre_1 :: s_iter :: s_post_0 :: s_post_1 :: s_finish :: Nil = Enum(7)
val state = RegInit((1 << s_idle.litValue.toInt).U(7.W))
```

### 3.2 State Descriptions

| State | Name | Function | Duration |
|-------|------|----------|----------|
| `s_idle` | Idle | Accept new division request | 1 cycle |
| `s_pre_0` | Pre-process 0 | Leading Zero Count (LZC) computation | 1 cycle |
| `s_pre_1` | Pre-process 1 | Normalize operands, init quotient, check special cases | 1 cycle |
| `s_iter` | Iteration | SRT radix-16 iteration loop | N cycles (variable) |
| `s_post_0` | Post-process 0 | Full-width remainder recovery (if negative) | 1 cycle |
| `s_post_1` | Post-process 1 | De-normalization, final mux, sign correction | 1 cycle |
| `s_finish` | Finish | Output result, return to idle | 0-1 cycles |

### 3.3 Transition Logic (lines 91-109)

```scala
when(kill_r) {
  state := UIntToOH(s_idle, 7)
} .elsewhen(state(s_idle) && in_fire && !kill_w) {
  state := UIntToOH(s_pre_0, 7)
} .elsewhen(state(s_pre_0)) {
  state := UIntToOH(s_pre_1, 7)
} .elsewhen(state(s_pre_1)) {
  state := Mux(special, UIntToOH(s_post_1, 7), UIntToOH(s_iter, 7))
} .elsewhen(state(s_iter)) {
  state := Mux(finalIter, UIntToOH(s_post_0, 7), UIntToOH(s_iter, 7))
} .elsewhen(state(s_post_0)) {
  state := UIntToOH(s_post_1, 7)
} .elsewhen(state(s_post_1)) {
  state := UIntToOH(s_finish, 7)
} .elsewhen(state(s_finish) && !specialReg) {
  state := UIntToOH(s_idle, 7)
}
```

Key design choice: `special` causes a direct jump from `s_pre_1` to `s_post_1`, bypassing all iterations. The `kill_r` signal can force return to `s_idle` from any state.

### 3.4 Kill Mechanism

Two-level flush support (DivUnit.scala lines 35-36):
- **`kill_w`**: Detected before the divider starts computing (input stage). Prevents new iteration from beginning.
- **`kill_r`**: Detected while divider is already running. Forces state machine back to `s_idle`.

---

## 4. Quotient Digit Selection (QDS)

### 4.1 Selection Architecture

QDS determines the quotient digit by comparing the scaled partial remainder against pre-computed thresholds. The implementation uses a **two-step look-ahead** strategy:

1. **Step 1**: Select `qNext` (current radix-4 digit) from partial remainder
2. **Step 2**: Speculatively compute `qNext2` for all 5 possible `qNext` values, then select with Mux1H

### 4.2 Selection Block (lines 291-299)

The selection uses a 10-bit carry-save comparator:

```scala
val signs = VecInit(Seq.tabulate(4){ i => {
  val csa = Module(new CSA3_2(10))
  csa.io.in(0) := r2ws              // r^2 * ws (scaled sum high bits)
  csa.io.in(1) := r2wc              // r^2 * wc (scaled carry high bits)
  csa.io.in(2) := Mux1H(qPrevReg, rudPmNegReg.toSeq)(i)  // -r*q*d + m_k
  (csa.io.out(0) + (csa.io.out(1)(8, 0) << 1))(9)         // sign bit
}})
qNext := DetectSign(signs.asUInt, "sel_q")
```

### 4.3 DetectSign Function (lines 281-289)

Converts 4 sign bits into a one-hot quotient digit encoding:

```scala
def DetectSign(signs: UInt, name: String): UInt = {
  qVec(quot_neg_2) := signs(0) && signs(1) && signs(2)
  qVec(quot_neg_1) := ~signs(0) && signs(1) && signs(2)
  qVec(quot_0)     := signs(2) && ~signs(1)
  qVec(quot_pos_1) := signs(3) && ~signs(2) && ~signs(1)
  qVec(quot_pos_2) := ~signs(3) && ~signs(2) && ~signs(1)
}
```

This maps sign-pattern to digit using the interval partitioning property of SRT algorithms.

### 4.4 M Look-up Table (lines 409-453)

`mLookUpTable2` contains pre-computed threshold values `-m[k]` for 4 quotient categories, each with 8 entries indexed by the top 3 bits of the divisor:

```scala
object mLookUpTable2 {
  val minus_m = Seq(
    Seq(/* -m[-2]: 8 entries */ 0.U -> "b00_11010".U, ...),
    Seq(/* -m[-1]: 8 entries */ 0.U -> "b000_0100".U, ...),
    Seq(/* -m[0]:  8 entries */ 0.U -> "b111_1101".U, ...),
    Seq(/* -m[1]:  8 entries */ 0.U -> "b11_01000".U, ...)
  )
}
```

These thresholds are derived from P-D plot analysis to ensure correctness under finite-precision selection.

### 4.5 Initial QDS (s_pre_1)

First-iteration QDS uses simplified logic since the initial partial remainder has a known format `0.001xxx`:

```scala
val rSumInitTrunc = Cat(0.U(1.W), rSumInit(itn_len-4, itn_len-4-4+1))
val mInitPos1 = MuxLookup(dNormReg(len-2, len-4), ...)(...)  // 8-entry LUT
val mInitPos2 = MuxLookup(dNormReg(len-2, len-4), ...)(...)
val qInit = Mux(initCmpPos2, ..., Mux(initCmpPos1, ..., UIntToOH(quot_0, 5)))
```

### 4.6 Speculative Block (lines 312-330)

For the second radix-4 step, all 5 possible next digits are computed in parallel:

```scala
qSpec := VecInit(Seq.tabulate(5){ q_spec => {
  // For each possible qNext value, compute the corresponding qNext2
  val csa1 = Module(new CSA3_2(13))
  // ... 4-level sign detection per speculation
}})
qNext2 := Mux1H(qNext, qSpec.toSeq)  // select correct speculation
```

This speculate-and-select strategy trades area (25 CSA3_2 instances) for single-cycle completion of both radix-4 steps.

---

## 5. Partial Remainder Update (CSA)

### 5.1 CSA Module (CSA.scala)

The Carry-Save Adder is defined in `src/main/scala/xiangshan/backend/fu/util/CSA.scala`:

```scala
class CSA3_2(len: Int) extends CarrySaveAdderMToN(3, 2)(len){
  // Bit-serial: no carry propagation between bits
  val sum = a_xor_b ^ cin
  val cout = a_and_b | (a_xor_b & cin)
}
```

Each bit is computed independently -- no carry chain, so delay is O(1) regardless of width.

### 5.2 Wide CSA (Iteration Core)

The iteration core uses two `CSA3_2(itn_len)` instances cascaded (line 300-309):

**CSA Wide 1** (first radix-4 step):
- Input: `4*rSumReg`, `4*rCarryReg`, `-q[j]*d` (from `udNegReg`)
- Output: partial remainder after first radix-4 step

**CSA Wide 2** (second radix-4 step):
- Input: CSA1 output (shifted left by 2 for ×4), `-q[j+1]*d`
- Output: partial remainder after second radix-4 step

### 5.3 Odd Iteration Handling (lines 308-309)

```scala
rSumIter := Mux(~oddIter & finalIter, csaWide1.io.out(0), csaWide2.io.out(0))
rCarryIter := Mux(~oddIter & finalIter, csaWide1.io.out(1) << 1, csaWide2.io.out(1) << 1)
```

When `oddIter` is true, the final iteration uses only the first CSA output (single radix-4 step), avoiding over-computation.

### 5.4 Selection Block CSAs

4 instances of `CSA3_2(10)` in the QDS path (lines 292-298) compute comparison values with minimal delay.

### 5.5 Speculative Block CSAs

5 x (1 + 4) = 25 instances of `CSA3_2(13)` in the speculative path (lines 313-327). This is the largest area consumer in the design.

### 5.6 udNeg Pre-computation (lines 258-263)

The multiples `{-2d, -d, 0, +d, +2d}` are pre-computed in `s_pre_1` and stored in registers:

```scala
udNeg := VecInit(
  Cat(SignExt(dPos, 66), 0.U(2.W)),    // -2d
  Cat(SignExt(dPos, 67), 0.U(1.W)),    // -d
  0.U,                                   // 0
  Cat(SignExt(dNeg, 67), 0.U(1.W)),    // +d
  Cat(SignExt(dNeg, 66), 0.U(2.W))     // +2d
)
```

---

## 6. On-the-Fly Quotient Conversion (OTFC)

### 6.1 Principle

OTFC eliminates the post-iteration quotient correction adder by maintaining two registers:
- `quotIterReg`: current quotient estimate
- `quotM1IterReg`: current quotient estimate minus 1

When a negative digit is selected, the conversion uses `quotM1Iter` as base; when positive, uses `quotIter`. This avoids any carry-propagate addition.

### 6.2 OTFC Function (lines 337-353)

```scala
def OTFC(q: UInt, quot: UInt, quotM1: UInt): (UInt, UInt) = {
  val quotNext = Mux1H(Seq(
    q(quot_pos_2) -> (quot << 2 | "b10".U),   // q=+2
    q(quot_pos_1) -> (quot << 2 | "b01".U),   // q=+1
    q(quot_0)     -> (quot << 2 | "b00".U),   // q=0
    q(quot_neg_1) -> (quotM1 << 2 | "b11".U), // q=-1
    q(quot_neg_2) -> (quotM1 << 2 | "b10".U)  // q=-2
  ))
  val quotM1Next = Mux1H(Seq(
    q(quot_pos_2) -> (quot << 2 | "b01".U),
    q(quot_pos_1) -> (quot << 2 | "b00".U),
    q(quot_0)     -> (quotM1 << 2 | "b11".U),
    q(quot_neg_1) -> (quotM1 << 2 | "b10".U),
    q(quot_neg_2) -> (quotM1 << 2 | "b01".U)
  ))
}
```

All paths use `Mux1H` for constant-delay selection regardless of quotient digit value.

### 6.3 Dual-Step OTFC (lines 354-357)

Since two radix-4 steps execute per cycle, OTFC is called twice:

```scala
quotHalfIter := OTFC(qPrevReg, quotIterReg, quotM1IterReg)._1
quotM1HalfIter := OTFC(qPrevReg, quotIterReg, quotM1IterReg)._2
quotIterNext := Mux(~oddIter && finalIter, quotHalfIter,
                    OTFC(qNext, quotHalfIter, quotM1HalfIter)._1)
```

On the final odd iteration, only the first OTFC call is used.

### 6.4 Sign Correction (s_finish)

```scala
quotIter := Mux(state(s_iter), quotIterNext,
                Mux(state(s_pre_1), 0.U(len.W),
                  Mux(quotSignReg, aInverter, quotIterReg)))
```

The `aInverter` (which computes `-quotIterReg` when not in idle state) provides two's complement negation for signed results.

---

## 7. Special Case Handling

### 7.1 Detection Logic (lines 156-160)

```scala
val dIsOne = dLZC(lzc_width - 1, 0).andR      // divisor absolute value == 1
val dIsZero = ~dNormReg.orR                     // divisor is zero
val aIsZero = aLZC(lzc_width)                   // dividend is zero
val aTooSmall = aLZC(lzc_width) | lzcWireDiff(lzc_width)  // |dividend| < |divisor|
special := dIsOne | dIsZero | aTooSmall
```

### 7.2 Division by Zero

Per RISC-V spec: quotient = all-ones (-1), remainder = dividend.

```scala
quotSpecial = Mux(dIsZero, VecInit(Seq.fill(len)(true.B)).asUInt, ...)
remSpecial = Mux(dIsZero || aTooSmall, aReg, 0.U)
```

### 7.3 Overflow (|dividend| < |divisor|)

When the dividend is smaller than the divisor, quotient = 0, remainder = dividend. This covers the `INT_MIN / -1` overflow case in signed division.

### 7.4 Divisor Equals 1

`dIsOne` is true when all LZC bits are set (no leading zeros after the implied 1). Result is trivially the dividend itself.

### 7.5 Special Path Timing

Special cases bypass all iterations:
```scala
state := Mux(special, UIntToOH(s_post_1, 7), UIntToOH(s_iter, 7))
```

The result registers are loaded in `s_pre_1` and selected in `s_post_1`. Total special-case latency: 4-5 cycles.

---

## 8. Remainder Recovery (Post-Processing)

### 8.1 Recovery Addition (lines 373-379)

After iterations, the partial remainder may be negative (SRT redundancy allows this):

```scala
when(rSignReg) {
  rNext := ~rSumReg + ~rCarryReg + 2.U                        // -w = ~w + 2
  rNextPd := ~rSumReg + ~rCarryReg + ~Cat(0.U(1.W), dNormReg, 0.U(3.W)) + 3.U  // -w + d
} .otherwise {
  rNext := rSumReg + rCarryReg                                 // w
  rNextPd := rSumReg + rCarryReg + Cat(0.U(1.W), dNormReg, 0.U(3.W))  // w + d
}
```

Both `rNext` (raw remainder) and `rNextPd` (remainder + divisor) are computed.

### 8.2 Correction Check (line 387)

```scala
val needCorr = Mux(rSignReg, ~r(len) & r.orR, r(len))
val rPreShifted = Mux(needCorr, rPd, r)
```

If correction is needed, select `rPd` and use `quotM1IterReg` (quotient minus 1).

### 8.3 De-normalization

The remainder is right-shifted by `dLZCReg` to restore original scale:

```scala
rightShifter.io.in := rPreShifted
rightShifter.io.shiftNum := dLZCReg
rightShifter.io.msb := Mux(~(rPreShifted.orR), 0.U, rSignReg)
```

### 8.4 RightShifter Module (lines 455-477)

A parameterized barrel shifter supporting 32-bit and 64-bit with sign extension:

```scala
class RightShifter(len: Int, lzc_width: Int) extends Module {
  val s0 = Mux(shift(0), Cat(msb, io.in(len-1, 1)), io.in)
  val s1 = Mux(shift(1), Cat(Fill(2, msb), s0(len-1, 2)), s0)
  // ... up to s5 for 64-bit
}
```

---

## 9. Latency Analysis

### 9.1 Iteration Count Formula

```scala
iterNum := Mux(state(s_pre_1), (lzcRegDiff + 1.U) >> 2, iterNumReg -% 1.U)
```

Where `lzcRegDiff = dLZC - aLZC` (0 to len-1). Each iteration produces 4 bits, so iterations = ceil((lzcDiff+1)/4).

### 9.2 Worst-Case Latency

| Phase | 64-bit | 32-bit |
|-------|--------|--------|
| s_idle | 1 | 1 |
| s_pre_0 | 1 | 1 |
| s_pre_1 | 1 | 1 |
| s_iter (max) | 16 | 8 |
| s_post_0 | 1 | 1 |
| s_post_1 | 1 | 1 |
| s_finish | 1 | 1 |
| **Total** | **22 cycles** | **14 cycles** |

### 9.3 Best-Case Latency (Special Path)

4-5 cycles (detects special in `s_pre_1`, jumps to `s_post_1`).

### 9.4 outValidAhead3Cycle (line 405)

```scala
io.outValidAhead3Cycle := finalIter && state(s_iter) || special && state(s_pre_1)
```

Signals the downstream pipeline 3 cycles before output is valid, reducing stalls.

### 9.5 Critical Path Per Cycle

Single-cycle critical path: 2 cascaded CSAs + QDS comparison + speculative Mux1H. This is approximately 3 CSA levels + comparator + Mux, well within a single clock period.

---

## 10. Integration with Execution Pipeline

### 10.1 DivUnit Wrapper (DivUnit.scala)

`src/main/scala/xiangshan/backend/fu/wrapper/DivUnit.scala` wraps the data module as a `FuncUnit`:

```scala
class DivUnit(cfg: FuConfig) extends FuncUnit(cfg) {
  val divDataModule = Module(new SRT16DividerDataModule(cfg.destDataBits))
  io.in.ready := divDataModule.io.in_ready
  io.out.valid := divDataModule.io.out_valid
  io.out.bits.res.data := divDataModule.io.out_data
  io.outValidAhead3Cycle.get := divDataModule.io.outValidAhead3Cycle
}
```

### 10.2 Input Conversion

32-bit operations (DIVW, REMW, etc.) sign-extend or zero-extend inputs to 64-bit:

```scala
val divInputCvtFunc: UInt => UInt = (x: UInt) => Mux(
  ctrl.isW,
  Mux(ctrl.sign, SignExt(x(31, 0), xlen), ZeroExt(x(31, 0), xlen)),
  x
)
```

### 10.3 Output Conversion (line 397-400)

```scala
io.out_data := Mux(isW, SignExt(res(31, 0), len), res)
```

32-bit results are sign-extended to 64 bits.

### 10.4 Pipeline Characteristics

- **Not pipelined** (`piped = false`): blocks execution port during computation
- **Non-blocking ready**: `io.in.ready` only asserted in `s_idle`
- **Flush-aware**: two-level kill ensures correct behavior on pipeline exceptions
- **Wake-up**: `outValidAhead3Cycle` enables early wake-up for dependent instructions

---

## 11. Source File Locations

| File | Path | Lines | Purpose |
|------|------|-------|---------|
| SRT16Divider.scala | `src/main/scala/xiangshan/backend/fu/SRT16Divider.scala` | 477 | Core divider data path + FSM |
| CSA.scala | `src/main/scala/xiangshan/backend/fu/util/CSA.scala` | 64 | Carry-Save Adder modules |
| DivUnit.scala | `src/main/scala/xiangshan/backend/fu/wrapper/DivUnit.scala` | 55 | Pipeline interface wrapper |
| FuConfig.scala | `src/main/scala/xiangshan/backend/fu/FuConfig.scala` | - | Function unit configuration |

---

## 12. Design Highlights and Optimizations

1. **Dual-step Radix-16**: Two radix-4 steps per cycle halve iteration count versus pure radix-4
2. **Speculative QDS**: All 5 possible next digits computed in parallel, selected by Mux1H
3. **OTFC**: Eliminates post-iteration quotient correction adder
4. **Mux1H everywhere**: All multiplexers use one-hot encoding for constant delay
5. **CSA throughout**: No wide carry-propagate adders in the iteration loop
6. **Fast special path**: Division by zero, overflow, and divide-by-one skip all iterations (4-5 cycles)
7. **Ahead valid signal**: 3-cycle early notification reduces downstream pipeline bubbles
8. **Two-level kill**: Input-stage and running-stage flush support for correct exception handling

---

## 13. Summary

The XiangShan SRT16 integer divider is a well-engineered digit-recurrence divider implementing the SRT Radix-16 algorithm through cascaded radix-4 steps. Key metrics:

- **64-bit worst case**: ~22 cycles
- **32-bit worst case**: ~14 cycles
- **Special cases**: 4-5 cycles
- **Core width**: `len + 4` bits (carry-save representation)
- **Gate-intensive components**: 25 speculative CSA3_2(13) instances, 4 selection CSA3_2(10) instances, 2 iteration-wide CSA3_2(itn_len) instances
- **Total code**: 477 lines of Chisel Scala

The design achieves excellent latency through the combination of high-radix iteration, speculative quotient selection, and on-the-fly conversion, while maintaining a compact and parameterizable implementation suitable for both RV64 and RV32 configurations.
