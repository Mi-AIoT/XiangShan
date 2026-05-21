// Copyright (c) 2024-2025 Beijing Institute of Open Source Chip (BOSC)
// Copyright (c) 2020-2025 Institute of Computing Technology, Chinese Academy of Sciences
// Copyright (c) 2020-2021 Peng Cheng Laboratory
//
// XiangShan is licensed under Mulan PSL v2.
// You can use this software according to the terms and conditions of the Mulan PSL v2.
// You may obtain a copy of Mulan PSL v2 at:
//          https://license.coscl.org.cn/MulanPSL2
//
// THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
// EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
// MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
//
// See the Mulan PSL v2 for more details.

package xiangshan.frontend.ifu

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import utility.HasCircularQueuePtrHelper
import utility.HasPerfEvents
import utility.InstSeqNum
import utility.PerfCCT
import utility.UIntToMask
import utility.ValidHold
import utility.XORFold
import utility.XSPerfAccumulate
import xiangshan.FrontendTdataDistributeIO
import xiangshan.cache.mmu.HasTlbConst
import xiangshan.cache.mmu.TlbRequestIO
import xiangshan.frontend.BackendRedirectTopdown
import xiangshan.frontend.ExceptionType
import xiangshan.frontend.FetchToIBuffer
import xiangshan.frontend.FrontendRedirect
import xiangshan.frontend.FtqToIfuIO
import xiangshan.frontend.ICacheToIfuIO
import xiangshan.frontend.IfuToBackendIO
import xiangshan.frontend.IfuToFtqIO
import xiangshan.frontend.IfuToInstrUncacheIO
import xiangshan.frontend.InstrUncacheToIfuIO
import xiangshan.frontend.PreDecodeInfo
import xiangshan.frontend.PrunedAddr
import xiangshan.frontend.PrunedAddrInit
import xiangshan.frontend.bpu.BranchAttribute
import xiangshan.frontend.ibuffer.IBufPtr
import xiangshan.frontend.icache.PmpCheckBundle

class Ifu(implicit p: Parameters) extends IfuModule
    with PreDecodeHelper
    with IfuHelper
    with HasCircularQueuePtrHelper
    with HasPerfEvents
    with HasTlbConst {

  class IfuIO(implicit p: Parameters) extends IfuBundle {
    // Ftq: request / write back
    val fromFtq: FtqToIfuIO = Flipped(new FtqToIfuIO)
    val toFtq:   IfuToFtqIO = new IfuToFtqIO

    // ICache: response
    val fromICache: ICacheToIfuIO = Flipped(new ICacheToIfuIO)

    // Uncache: mmio request / response
    val toUncache:   IfuToInstrUncacheIO = new IfuToInstrUncacheIO
    val fromUncache: InstrUncacheToIfuIO = Flipped(new InstrUncacheToIfuIO)

    // IBuffer: enqueue
    val toIBuffer:    DecoupledIO[FetchToIBuffer] = DecoupledIO(new FetchToIBuffer)
    val ibufferEmpty: Bool                        = Input(Bool())

    // Backend: gpaMem
    val toBackend:    IfuToBackendIO = new IfuToBackendIO
    val backendEmpty: Bool           = Input(Bool())

    // debug extension: frontend trigger
    val frontendTrigger: FrontendTdataDistributeIO = Flipped(new FrontendTdataDistributeIO)

    // Backend: csr control
    val csrFsIsOff: Bool = Input(Bool())

    // Topdown analysis
    val backendRedirectTopdown: BackendRedirectTopdown = Input(new BackendRedirectTopdown)
  }
  val io: IfuIO = IO(new IfuIO)

  // submodule
  private val instrBoundary      = Module(new InstrBoundary)
  private val predChecker        = Module(new PredChecker)
  private val frontendTrigger    = Module(new FrontendTrigger)
  private val rvcExpanders       = Seq.fill(IBufferEnqueueWidth)(Module(new RvcExpander))
  private val perfAnalyzer       = Module(new IfuPerfAnalysis)
  private val uncacheUnit        = Module(new IfuUncacheUnit)
  private val uncacheRvcExpander = Module(new RvcExpander)

  // alias
  private val (toFtq, fromFtq)         = (io.toFtq, io.fromFtq)
  private val fromICache               = io.fromICache.req
  private val (toUncache, fromUncache) = (io.toUncache.req, io.fromUncache.resp)
  private val (checkerIn, checkerOutStage1, checkerOutStage2) =
    (predChecker.io.req, predChecker.io.resp.stage1Out, predChecker.io.resp.stage2Out)

  private val s0_ready, s1_ready, s2_ready = WireInit(false.B)
  private val s0_fire, s1_fire, s2_fire    = WireInit(false.B)
  private val s0_flush, s1_flush, s2_flush = WireInit(false.B)
  private val s0_flushFromBpu              = Wire(Bool())

  private val backendRedirect             = WireInit(false.B)
  private val wbRedirect, uncacheRedirect = WireInit(0.U.asTypeOf(new IfuRedirectInternal))

  private val s2_wbNotFlush = WireInit(false.B)

  backendRedirect := fromFtq.redirect.valid
  s2_flush        := backendRedirect || (wbRedirect.valid && !s2_wbNotFlush)
  s1_flush        := backendRedirect || uncacheRedirect.valid || wbRedirect.valid
  s0_flush        := s1_flush || s0_flushFromBpu

  /* --------------------------------------------------------------------------------------------------------------
     stage 0
     - get req from ICacheMainPipe
     - get instruction boundary
     - compact instructions
     -------------------------------------------------------------------------------------------------------------- */

  private val s0_valid = io.fromICache.req.valid

  s0_fire := s0_valid && s1_ready && !s0_flush

  fromICache.ready := s1_ready || s0_flush

  private val s0_fetchBlock = VecInit(
    Wire(new FetchBlock).fromICacheReq(io.fromICache.req.bits(0)),
    Wire(new FetchBlock).fromICacheReq(io.fromICache.req.bits(1))
  )

  private val s0_icacheData = Wire(new IfuData).fromICacheReq(io.fromICache.req.bits)
  private val s0_icacheMeta = VecInit(io.fromICache.req.bits.map(_.icacheMeta))

  s0_flushFromBpu := fromFtq.flushFromBpu.shouldFlushByStage3(s0_fetchBlock(0).ftqIdx, s0_valid)

  private val s0_prevEndIsHalfRvi = RegInit(false.B)

  private val s0_totalEndPos = Mux(
    s0_fetchBlock(1).valid,
    s0_fetchBlock(1).takenCfiOffset.bits + s0_fetchBlock(0).size,
    s0_fetchBlock(0).takenCfiOffset.bits
  )

  instrBoundary.io.req.valid               := s0_valid
  instrBoundary.io.req.fetchBlock          := s0_fetchBlock
  instrBoundary.io.req.icacheData          := s0_icacheData
  instrBoundary.io.req.firstInstrIsHalfRvi := s0_prevEndIsHalfRvi
  instrBoundary.io.req.totalEndPos         := s0_totalEndPos

  private val s0_endIsHalfRvi = instrBoundary.io.resp.endIsHalfRvi
  private val s0_rawInstrVec  = instrBoundary.io.resp.rawInstrVec
  private val s0_instrEndMask = instrBoundary.io.resp.instrEndMask

  private val s0_rawInstrValid = VecInit(s0_rawInstrVec.map(_.valid)).asUInt

  private val (s0_compactedInstrVec, s0_instrCount) = compact(s0_rawInstrVec)

  private val s0_instrValid = VecInit(s0_compactedInstrVec.map(_.valid)).asUInt

  // When invalidTaken is true, we can not flush s2_prevLastIsHalfRvi because the fetch block after it is fall-through.
  when(backendRedirect) {
    s0_prevEndIsHalfRvi := false.B
  }.elsewhen(wbRedirect.valid) {
    s0_prevEndIsHalfRvi := wbRedirect.isHalfInstr
  }.elsewhen(uncacheRedirect.valid) {
    s0_prevEndIsHalfRvi := uncacheRedirect.isHalfInstr
  }.elsewhen(s0_fire && !s0_icacheMeta(0).isUncache) {
    s0_prevEndIsHalfRvi := s0_endIsHalfRvi
  }

  private val s0_predTakenInstrIdx = VecInit(
    PopCount(s0_rawInstrValid & s0_fetchBlock(0).range) - 1.U,
    PopCount(s0_rawInstrValid & s0_icacheData.range) - 1.U
  )

  private val s0_invalidTaken = VecInit(
    s0_fetchBlock(0).takenCfiOffset.valid && !s0_instrEndMask(s0_fetchBlock(0).takenCfiOffset.bits),
    s0_fetchBlock(1).takenCfiOffset.valid && !s0_instrEndMask(s0_fetchBlock(1).takenCfiOffset.bits)
  )

  // When an exception occurs, forward the exception information immediately instead of
  // waiting for instruction concatenation to complete.
  private val s0_hasException   = s0_icacheMeta(0).exception.hasException
  private val s0_realInstrValid = Mux(s0_hasException, 1.U(FetchBlockInstNum.W), s0_instrValid)
  private val s0_realInstrCount = Mux(s0_hasException, 1.U((log2Ceil(FetchBlockInstNum) + 1).W), s0_instrCount)

  // debug
  (0 until FetchPorts).map { i =>
    when(s0_fire && s0_fetchBlock(i).valid && !s0_icacheMeta(i).isUncache) {
      val data              = io.fromICache.req.bits(i).data.asTypeOf(Vec(FetchBlockInstNum, UInt(16.W)))
      val debug_maybeRvcMap = VecInit(data.map(_(1, 0) =/= 3.U)).asUInt & s0_fetchBlock(i).range
      dontTouch(debug_maybeRvcMap)
      assert(io.fromICache.req.bits(i).maybeRvcMap === debug_maybeRvcMap, "ICache MainPipe: maybeRvcMap wrong")
    }
  }

  /* --------------------------------------------------------------------------------------------------------------
     stage 1
     - cat half rvi instruction
     - generate instruction PC
     -------------------------------------------------------------------------------------------------------------- */

  private val s1_valid = ValidHold(s0_fire && !s0_flush, s1_fire, s1_flush)

  s1_fire  := s1_valid && s2_ready
  s1_ready := s1_fire || !s1_valid

  private val s1_fetchBlock        = RegEnable(s0_fetchBlock, s0_fire)
  private val s1_predTakenInstrIdx = RegEnable(s0_predTakenInstrIdx, s0_fire)
  private val s1_invalidTaken      = RegEnable(s0_invalidTaken, s0_fire)
  private val s1_instrCount        = RegEnable(s0_realInstrCount, s0_fire)
  private val s1_instrValid        = RegEnable(s0_realInstrValid, s0_fire)

  private val s1_prevIBufEnqPtr     = RegInit(0.U.asTypeOf(new IBufPtr))
  private val s1_prevEndIsHalfRvi   = RegEnable(s0_prevEndIsHalfRvi, s0_fire)
  private val s1_prevEndHalfRviData = RegInit(0.U(16.W))
  private val s1_prevEndHalfRviPc   = RegInit(0.U.asTypeOf(PrunedAddr(VAddrBits)))

  private val s1_endIsHalfRvi = RegEnable(s0_endIsHalfRvi, s0_fire)

  private val s1_instrData   = RegEnable(s0_icacheData.data, s0_fire)
  private val s1_totalEndPos = RegEnable(s0_totalEndPos, s0_fire)
  private val s1_icacheMeta  = RegEnable(s0_icacheMeta, s0_fire)
  private val s1_instrVec    = RegEnable(s0_compactedInstrVec, s0_fire)

  private val s1_predTakenMask = VecInit((0 until FetchPorts).map { i =>
    Mux(
      s1_fetchBlock(i).valid && s1_fetchBlock(i).takenCfiOffset.valid,
      UIntToOH(s1_predTakenInstrIdx(i), FetchBlockInstNum),
      0.U(FetchBlockInstNum.W)
    )
  })
  private val s1_mergedPredTakenMask = s1_predTakenMask(0) | s1_predTakenMask(1)

  private val s1_invalidTakenMask = VecInit((0 until FetchPorts).map { i =>
    Mux(
      s1_fetchBlock(i).valid && s1_invalidTaken(i),
      s1_predTakenMask(i),
      0.U(FetchBlockInstNum.W)
    )
  })
  private val s1_mergedInvalidTakenMask = s1_invalidTakenMask(0) | s1_invalidTakenMask(1)

  private val s1_fixedInstrVec = Wire(Vec(FetchBlockInstNum, new Instruction))
  s1_fixedInstrVec := s1_instrVec
  s1_fixedInstrVec(0).data := Mux(
    s1_prevEndIsHalfRvi,
    Cat(s1_instrVec(0).data(15, 0), s1_prevEndHalfRviData),
    s1_instrVec(0).data
  )
  s1_fixedInstrVec(0).isPrevEndHalfRvi := s1_prevEndIsHalfRvi
  s1_fixedInstrVec(0).endOffset        := Mux(s1_prevEndIsHalfRvi, 0.U, s1_instrVec(0).endOffset)
  s1_fixedInstrVec.zipWithIndex.foreach { case (instr, i) =>
    instr.isPredTaken  := s1_mergedPredTakenMask(i)
    instr.invalidTaken := s1_mergedInvalidTakenMask(i)
  }

  private val s1_alignShiftNum = s1_prevIBufEnqPtr.value(1, 0)
  dontTouch(s1_alignShiftNum)
  private val s1_alignedInstrVec = align(s1_fixedInstrVec, s1_alignShiftNum)

  private val s1_alignedInstrPcVec = VecInit(s1_alignedInstrVec.map(instr => getInstrPc(instr, s1_fetchBlock)))

  private val s1_alignedInstrPcLowerBitsVec = VecInit(s1_alignedInstrVec.map { instr =>
    getInstrPcLowerBits(instr, s1_fetchBlock)
  })

  private val s1_realAlignedInstrPcVec =
    WireDefault(VecInit.fill(IBufferEnqueueWidth)(0.U.asTypeOf(PrunedAddr(VAddrBits))))
  for (i <- 0 until IBufferEnqueueWidth) {
    s1_realAlignedInstrPcVec(i) := Mux(
      s1_alignedInstrVec(i).isPrevEndHalfRvi,
      s1_prevEndHalfRviPc,
      s1_alignedInstrPcVec(i)
    )
  }

  val s1_endHalfRviPc = Mux(
    s1_fetchBlock(1).valid,
    s1_fetchBlock(1).startVAddr + ((s1_totalEndPos - s1_fetchBlock(0).size) << 1),
    s1_fetchBlock(0).startVAddr + (s1_totalEndPos << 1)
  )
  private val s1_endHalfRviData = s1_instrData(s1_totalEndPos)

  private val s1_alignedFoldPc =
    VecInit(s1_realAlignedInstrPcVec.map(i => XORFold(i(VAddrBits - 1, 1), MemPredPCWidth)))

  // backendRedirect has the highest priority
  when(backendRedirect) {
    s1_prevEndHalfRviData := 0.U
    s1_prevEndHalfRviPc   := 0.U.asTypeOf(PrunedAddr(VAddrBits))
  }.elsewhen(wbRedirect.valid) {
    s1_prevEndHalfRviData := wbRedirect.halfData
    s1_prevEndHalfRviPc   := wbRedirect.halfPc
  }.elsewhen(uncacheRedirect.valid) {
    s1_prevEndHalfRviData := uncacheRedirect.halfData
    s1_prevEndHalfRviPc   := uncacheRedirect.halfPc
  }.elsewhen(s1_fire) {
    s1_prevEndHalfRviData := s1_endHalfRviData
    s1_prevEndHalfRviPc   := s1_endHalfRviPc
  }

  when(backendRedirect) {
    s1_prevIBufEnqPtr := 0.U.asTypeOf(new IBufPtr)
  }.elsewhen(wbRedirect.valid) {
    s1_prevIBufEnqPtr := wbRedirect.prevIBufEnqPtr + wbRedirect.instrCount
  }.elsewhen(uncacheRedirect.valid) {
    s1_prevIBufEnqPtr := uncacheRedirect.prevIBufEnqPtr + uncacheRedirect.instrCount
  }.elsewhen(s1_fire && !s1_icacheMeta(0).isUncache) {
    s1_prevIBufEnqPtr := s1_prevIBufEnqPtr + s1_instrCount
  }

  // reqIsUncache is used to limit the number of fetch requests and enable special pre-decode configurations.
  private val s1_reqIsUncache = s1_valid && s1_icacheMeta(0).isUncache
  // useUncacheFetch controls whether the instruction fetch operation follows the uncache control logic.
  private val s1_useUncacheFetch = s1_valid && s1_icacheMeta(0).isUncache && s1_icacheMeta(0).exception.isNone

  /* --------------------------------------------------------------------------------------------------------------
     stage 2
     - expand instructions
     - pre decode
     - check
     - process uncache req
     -------------------------------------------------------------------------------------------------------------- */

  // assign later
  private val s2_valid       = WireInit(false.B)
  private val s2_firstValid  = ValidHold(s1_fire && !s1_flush && s1_fetchBlock(0).valid, s2_fire, s2_flush)
  private val s2_secondValid = ValidHold(s1_fire && !s1_flush && s1_fetchBlock(1).valid, s2_fire, s2_flush)
  private val s2_fetchBlock  = RegEnable(s1_fetchBlock, s1_fire)
  dontTouch(s2_fetchBlock)

  private val s2_prevIBufEnqPtr = RegEnable(s1_prevIBufEnqPtr, s1_fire)

  private val s2_prevEndIsHalfRvi   = RegEnable(s1_prevEndIsHalfRvi, false.B, s1_fire)
  private val s2_prevEndHalfPc      = RegEnable(s1_prevEndHalfRviPc, s1_fire)
  private val s2_prevEndHalfRviData = RegEnable(s1_prevEndHalfRviData, s1_fire)

  private val s2_endIsHalfRvi   = RegEnable(s1_endIsHalfRvi, false.B, s1_fire)
  private val s2_endHalfRviData = RegEnable(s1_endHalfRviData, s1_fire)
  private val s2_endHalfRviPc   = RegEnable(s1_endHalfRviPc, s1_fire)

  private val s2_instrCount        = RegEnable(s1_instrCount, s1_fire)
  private val s2_icacheMeta        = RegEnable(s1_icacheMeta, s1_fire)
  private val s2_alignedInstrVec   = RegEnable(s1_alignedInstrVec, s1_fire)
  private val s2_alignedInstrPcVec = RegEnable(s1_realAlignedInstrPcVec, s1_fire)
  private val s2_alignedFoldPc     = RegEnable(s1_alignedFoldPc, s1_fire)

  s2_fire := io.toIBuffer.fire
  dontTouch(s2_fire)

  private val s2_alignShiftNum = s2_prevIBufEnqPtr.value(1, 0)
  dontTouch(s2_alignShiftNum)

  rvcExpanders.zipWithIndex.foreach { case (expander, i) =>
    expander.io.in      := s2_alignedInstrVec(i).data
    expander.io.fsIsOff := io.csrFsIsOff
  }

  private val s2_expandedInstrDataVec = VecInit(rvcExpanders.map { expander =>
    Mux(expander.io.ill, expander.io.in, expander.io.out.bits)
  })
  dontTouch(s2_expandedInstrDataVec)

  private val s2_expandedInstrVec = Wire(Vec(IBufferEnqueueWidth, new Instruction))
  s2_expandedInstrVec := s2_alignedInstrVec
  s2_expandedInstrVec.zip(s2_expandedInstrDataVec).foreach { case (instr, expandedData) =>
    instr.data := expandedData
  }

  private val s2_blockSel      = VecInit(s2_expandedInstrVec.map(_.blockSel))
  private val s2_endOffsetVec  = VecInit(s2_expandedInstrVec.map(_.endOffset))
  private val s2_rawInstrValid = VecInit(s2_expandedInstrVec.map(_.valid)).asUInt
  dontTouch(s2_blockSel)
  dontTouch(s2_rawInstrValid)

  private val s2_pdInfoVec     = Wire(Vec(IBufferEnqueueWidth, new PreDecodeInfo))
  private val s2_jumpOffsetVec = Wire(Vec(IBufferEnqueueWidth, PrunedAddr(VAddrBits)))
  s2_expandedInstrVec.zipWithIndex.foreach { case (instr, i) =>
    val jalOffset = getJalOffset(instr.data, instr.isRvc)
    val brOffset  = getBrOffset(instr.data, instr.isRvc)
    s2_pdInfoVec(i).valid       := instr.valid
    s2_pdInfoVec(i).isRVC       := instr.isRvc
    s2_pdInfoVec(i).brAttribute := BranchAttribute.decode(instr.data, instr.valid && s2_valid)
    s2_jumpOffsetVec(i)         := Mux(s2_pdInfoVec(i).isBr, brOffset, jalOffset)
  }

  private val s2_uncacheLowerPc = RegEnable(s1_alignedInstrPcLowerBitsVec(s1_alignShiftNum), s1_fire)
  private val s2_uncachePc =
    catPC(s2_uncacheLowerPc, s2_fetchBlock(0).pcUpperBits, s2_fetchBlock(0).pcUpperBitsPlus1)
  private val s2_reqIsUncache    = RegEnable(s1_reqIsUncache, false.B, s1_fire)
  private val s2_useUncacheFetch = RegEnable(s1_useUncacheFetch, s1_fire)
  private val s2_uncacheCanGo =
    (uncacheUnit.io.resp.valid && !uncacheUnit.io.resp.bits.needResend) || !s2_useUncacheFetch
  private val s2_uncacheCrossPageMask = s2_valid && uncacheUnit.io.resp.valid && uncacheUnit.io.resp.bits.needResend
  private val s2_toIBufferValid =
    s2_valid && (!s2_reqIsUncache || (s2_uncacheCanGo && s2_reqIsUncache)) && !s2_flush

  /* ** unache state handle ** */
  private val uncacheBusy = RegInit(false.B)
  // For uncache cross-page instr, the real PC is in the prev fetch block.
  private val uncachePc = RegInit(0.U.asTypeOf(PrunedAddr(VAddrBits)))
  // Uncache cross-page may hit seq fetch or mispred, check required.
  private val uncacheResendCheck = RegInit(false.B)
  when(s2_flush) {
    uncacheBusy := false.B
    uncachePc   := 0.U.asTypeOf(PrunedAddr(VAddrBits))
  }.elsewhen(uncacheUnit.io.req.fire) {
    uncacheBusy := true.B
    uncachePc   := Mux(s2_prevEndIsHalfRvi, s2_prevEndHalfPc, s2_alignedInstrPcVec(s2_alignShiftNum))
  }.elsewhen(uncacheUnit.io.resp.valid) {
    uncacheBusy := false.B
    // uncachePc := uncachePc
  }

  private val isFirstInstr = RegInit(true.B)
  when(isFirstInstr && io.toIBuffer.fire) {
    isFirstInstr := false.B
  }

  uncacheUnit.io.req.valid       := s2_valid && s2_useUncacheFetch && !uncacheBusy
  uncacheUnit.io.req.bits.pbmt   := s2_icacheMeta(0).itlbPbmt
  uncacheUnit.io.req.bits.isMmio := s2_icacheMeta(0).pmpMmio
  uncacheUnit.io.req.bits.paddr  := s2_icacheMeta(0).pAddr
  uncacheUnit.io.flush           := s2_flush
  uncacheUnit.io.isFirstInstr    := isFirstInstr
  uncacheUnit.io.ifuStall        := !io.toIBuffer.ready
  uncacheUnit.io.emptyAfter      := io.backendEmpty && io.ibufferEmpty
  io.toUncache <> uncacheUnit.io.toUncache
  uncacheUnit.io.fromUncache <> io.fromUncache

  private val uncacheData      = uncacheUnit.io.resp.bits.uncacheData
  private val uncacheException = uncacheUnit.io.resp.bits.exception
  // not RVC, no exception, crossing page boundary
  private val uncacheNeedResend = uncacheUnit.io.resp.bits.needResend && uncacheUnit.io.resp.valid

  private val s2_uncacheData = Mux(s2_prevEndIsHalfRvi, Cat(uncacheData(15, 0), s2_prevEndHalfRviData), uncacheData)
  private val uncacheIsRvc   = s2_uncacheData(1, 0) =/= "b11".U
  uncacheRvcExpander.io.in      := Mux(s2_reqIsUncache, s2_uncacheData, 0.U)
  uncacheRvcExpander.io.fsIsOff := io.csrFsIsOff

  s2_valid := ValidHold(
    // infire: s1 -> s2 fire
    s1_fire && !s1_flush,
    // outfire: When an uncache cross-page occurs and it is not an exception,
    // this instruction fetch should end and prepare to receive the next fetch signal.
    io.toIBuffer.fire || s2_uncacheCrossPageMask,
    // On flush, waiting for uncache response is handled by the channel itself.
    s2_flush
  )

  s2_ready := (io.toIBuffer.ready && (s2_uncacheCanGo || !s2_reqIsUncache)) || !s2_valid

  /* ** prediction result check ** */
  checkerIn.valid                 := s2_valid
  checkerIn.bits.jumpOffsetVec    := s2_jumpOffsetVec
  checkerIn.bits.pdInfoVec        := s2_pdInfoVec
  checkerIn.bits.instrPcVec       := s2_alignedInstrPcVec
  checkerIn.bits.expandedInstrVec := s2_expandedInstrVec

  private val s2_fixedInstrValid = checkerOutStage1.fixedInstrValid.asUInt
  dontTouch(s2_fixedInstrValid)

  /* ** frontend Trigger  ** */
  frontendTrigger.io.pds             := s2_pdInfoVec
  frontendTrigger.io.pc              := s2_alignedInstrPcVec
  frontendTrigger.io.data            := 0.U.asTypeOf(Vec(IBufferEnqueueWidth + 1, UInt(16.W)))
  frontendTrigger.io.frontendTrigger := io.frontendTrigger
  private val s2_alignTriggered = frontendTrigger.io.triggered

  /* ** send to IBuffer ** */
  io.toIBuffer.valid               := s2_toIBufferValid
  io.toIBuffer.bits.instrs         := s2_expandedInstrDataVec
  io.toIBuffer.bits.valid          := s2_rawInstrValid
  io.toIBuffer.bits.enqEnable      := s2_fixedInstrValid
  io.toIBuffer.bits.isRvc          := s2_expandedInstrVec.map(_.isRvc)
  io.toIBuffer.bits.pc             := s2_alignedInstrPcVec // for debug
  io.toIBuffer.bits.prevIBufEnqPtr := s2_prevIBufEnqPtr
  io.toIBuffer.bits.ftqPtr.zipWithIndex.foreach { case (ftqPtr, i) =>
    ftqPtr := Mux(s2_blockSel(i), s2_fetchBlock(1).ftqIdx, s2_fetchBlock(0).ftqIdx)
  }

  /* in s2, prevInstrCount equals to next cycle's IBuffer.numFromFetch without predChecker. "prev" means s1;
   * when s1 fire (s1_valid && s2_ready), use s1_instrCount;
   * else when s2 stall (s2_valid && !s2_ready). use s2_instrCount because prevInstrCount equals to current instrCount;
   * otherwise, we don't care about prevInstrCount because next cycle's toIBuffer.valid won't set.
   */
  io.toIBuffer.bits.prevInstrCount := Mux(
    s1_fire,
    Mux(s1_reqIsUncache, 1.U, s1_instrCount),
    Mux(s2_reqIsUncache, 1.U, s2_instrCount)
  )

  // Find the last entry based on the boundaries of compacted valid signals.
  private val select = s2_blockSel
  private val enq    = io.toIBuffer.bits.enqEnable

  private val s2_rvcIll             = VecInit(rvcExpanders.map(_.io.ill))
  private val s2_rvcException       = ExceptionType.fromRvcExpander((enq & s2_rvcIll.asUInt).orR, s2_valid)
  private val s2_rvcExceptionOffset = PriorityEncoder(enq & s2_rvcIll.asUInt)

  io.toIBuffer.bits.isLastInFtqEntry := (0 until IBufferEnqueueWidth).map { i =>
    if (i == IBufferEnqueueWidth - 1) enq(i)
    else enq(i) ^ ((select(i) === select(i + 1)) & enq(i + 1))
  }
  io.toIBuffer.bits.instrEndOffset.zipWithIndex.foreach { case (a, i) =>
    a.predTaken  := s2_expandedInstrVec(i).isPredTaken && !s2_reqIsUncache
    a.fixedTaken := checkerOutStage1.fixedTaken(i) && !s2_reqIsUncache
    a.offset     := s2_endOffsetVec(i)
  }
  io.toIBuffer.bits.foldpc := s2_alignedFoldPc
  // mark the exception only on first instruction
  io.toIBuffer.bits.exceptionType := s2_icacheMeta(0).exception || s2_rvcException
  // backendException only needs to be set for the first instruction.
  // Other instructions in the same block may have pf or af set,
  // which is a side effect of the first instruction and actually not necessary.
  io.toIBuffer.bits.isBackendException := s2_icacheMeta(0).isBackendException
  // if we have last half RV-I instruction, and has exception, we need to tell backend to caculate the correct pc
  io.toIBuffer.bits.exceptionCrossPage := s2_icacheMeta(0).exception.hasException && s2_prevEndIsHalfRvi
  // if icache respond with exception, it's marked on entire cacheline,
  // so the first enqueued instr should be marked with exception
  // otherwise, we only have rvcException, so select its offset
  io.toIBuffer.bits.exceptionOffset := Mux(
    s2_icacheMeta(0).exception.hasException,
    s2_alignShiftNum.pad(log2Ceil(IBufferEnqueueWidth)),
    s2_rvcExceptionOffset
  )

  io.toIBuffer.bits.triggered := s2_alignTriggered

  val enqVec = io.toIBuffer.bits.enqEnable
  val allocateSeqNum = VecInit((0 until IBufferEnqueueWidth).map { i =>
    val idx  = PopCount(enqVec.take(i + 1))
    val pc   = s2_alignedInstrPcVec(i).toUInt
    val code = io.toIBuffer.bits.instrs(i)
    val seq  = PerfCCT.createInstMetaAtFetch(idx, pc, code, s2_fire & enqVec(i), clock, reset)
    val res  = WireDefault(0.U.asTypeOf(new InstSeqNum))
    res.seqNum := seq
    // leave uopIdx to 0.U
    res
  })
  io.toIBuffer.bits.debug_seqNum.zipWithIndex.foreach { case (seqNum, i) =>
    seqNum := Mux(s2_fire, allocateSeqNum(i), 0.U.asTypeOf(new InstSeqNum))
  }

  /** to backend */
  // s4_gpAddr is valid iff gpf is detected.
  // Uncache doesn’t request iTLB; it only returns bus exceptions.
  io.toBackend.gpAddrMem.wen                     := s2_toIBufferValid && s2_icacheMeta(0).exception.isGpf
  io.toBackend.gpAddrMem.waddr                   := s2_fetchBlock(0).ftqIdx.value
  io.toBackend.gpAddrMem.wdata.gpaddr            := s2_icacheMeta(0).gpAddr.toUInt
  io.toBackend.gpAddrMem.wdata.isForVSnonLeafPTE := s2_icacheMeta(0).isForVSnonLeafPTE

  // Write back to Ftq
  private val s2_uncacheRange     = VecInit((0 until FetchBlockInstNum).map(i => if (i == 0) true.B else false.B))
  private val uncacheFlushWb      = Wire(Valid(new FrontendRedirect))
  private val uncachePd           = 0.U.asTypeOf(Vec(FetchBlockInstNum, new PreDecodeInfo))
  private val uncacheMisEndOffset = Wire(Valid(UInt(FetchBlockInstOffsetWidth.W)))
  uncacheMisEndOffset.valid := s2_reqIsUncache
  uncacheMisEndOffset.bits  := Mux(uncacheIsRvc || uncacheNeedResend, 0.U, 1.U)

  // Send mmioFlushWb back to FTQ 1 cycle after uncache fetch return
  // When backend redirect, mmioState reset after 1 cycle.
  // In this case, mask .valid to avoid overriding backend redirect
  private val uncacheTarget =
    Mux(
      uncacheIsRvc || uncacheNeedResend,
      s2_fetchBlock(0).startVAddr + 2.U,
      s2_fetchBlock(0).startVAddr + 4.U
    )
  // Due to the presence of uncache requests, s2_valid && io.toIBuffer.ready is not equivalent to s2_fire.
  uncacheFlushWb.valid :=
    s2_valid && io.toIBuffer.ready && s2_reqIsUncache && !backendRedirect && (s2_uncacheCanGo || uncacheNeedResend)
  uncacheFlushWb.bits.ftqIdx    := s2_fetchBlock(0).ftqIdx
  uncacheFlushWb.bits.pc        := s2_fetchBlock(0).startVAddr.toUInt
  uncacheFlushWb.bits.taken     := false.B
  uncacheFlushWb.bits.ftqOffset := uncacheMisEndOffset.bits
  uncacheFlushWb.bits.isRVC     := uncacheIsRvc
  uncacheFlushWb.bits.attribute := BranchAttribute.None
  uncacheFlushWb.bits.target    := uncacheTarget.toUInt

  when(s2_reqIsUncache) {
    val inst        = s2_uncacheData
    val brAttribute = BranchAttribute.decode(inst)

    val uncacheRvcException = ExceptionType.fromRvcExpander(uncacheRvcExpander.io.ill)
    io.toIBuffer.bits.instrs(s2_alignShiftNum) := Mux(
      uncacheRvcExpander.io.ill,
      uncacheRvcExpander.io.in,
      uncacheRvcExpander.io.out.bits
    )

    io.toIBuffer.bits.pc(s2_alignShiftNum)                    := uncachePc
    io.toIBuffer.bits.isRvc(s2_alignShiftNum)                 := uncacheIsRvc
    io.toIBuffer.bits.instrEndOffset(s2_alignShiftNum).offset := Mux(uncacheIsRvc, 0.U, 1.U)

    io.toIBuffer.bits.exceptionType := s2_icacheMeta(0).exception || uncacheException || uncacheRvcException
    // execption can happen in next page only when cross page.
    io.toIBuffer.bits.exceptionCrossPage :=
      s2_prevEndIsHalfRvi && (s2_icacheMeta(0).exception.hasException || uncacheException.hasException)
    io.toIBuffer.bits.exceptionOffset := s2_alignShiftNum.pad(log2Ceil(IBufferEnqueueWidth))

    // In uncache scenarios, only a single instruction is allowed for execution,
    // so the valid signal enqueued into the IBuffer must be aligned.
    io.toIBuffer.bits.valid     := Cat(0.U(FetchBlockInstNum.W), UIntToOH(s2_alignShiftNum))
    io.toIBuffer.bits.enqEnable := Cat(0.U(FetchBlockInstNum.W), UIntToOH(s2_alignShiftNum))

    uncacheFlushWb.bits.isRVC     := uncacheIsRvc
    uncacheFlushWb.bits.attribute := brAttribute
  }

  // Core change: Route cross-page uncache data to S1 for unified management.
  // S2 can now directly concatenate uncache instructions using s2_prevLastIsHalfRvi during fetch.
  // This fixes the edge case where instructions spanning both cache and uncache channels fell through
  // the cracks of the existing S1 (cache) and S2 (uncache) cross-page handling logic.
  uncacheRedirect.valid := s2_valid && io.toIBuffer.ready && s2_reqIsUncache && (s2_uncacheCanGo || uncacheNeedResend)
  uncacheRedirect.instrCount     := Mux(uncacheNeedResend, 0.U, 1.U)
  uncacheRedirect.prevIBufEnqPtr := s2_prevIBufEnqPtr
  uncacheRedirect.isHalfInstr    := uncacheNeedResend
  uncacheRedirect.halfPc         := uncachePc
  uncacheRedirect.halfData       := uncacheData(15, 0)
  /* *****************************************************************************
   * IFU Write-back Stage
   * - write back preDecode information to Ftq to update
   * - redirect if found fault prediction
   * - redirect if false hit last half(last PC is not start + 32 Bytes, but in the middle of an notCFI RVI instruction)
   * ***************************************************************************** */
  private val wbEnable          = RegNext(s1_fire && !s1_flush) && !s2_reqIsUncache && !s2_flush
  private val wbValid           = RegNext(wbEnable, init = false.B)
  private val wbFirstValid      = RegEnable(s2_firstValid, wbEnable)
  private val wbSecondValid     = RegEnable(s2_secondValid, wbEnable)
  private val wbAlignFetchBlock = RegEnable(s2_fetchBlock, wbEnable)
  private val wbPrevIBufEnqPtr  = RegEnable(s2_prevIBufEnqPtr, wbEnable)
  private val wbInstrCount      = RegEnable(PopCount(io.toIBuffer.bits.enqEnable), wbEnable)

  private val wbCurrentLastHalfData = RegEnable(s2_endHalfRviData, wbEnable)
  private val wbCurrentLastHalfPc   = RegEnable(s2_endHalfRviPc, wbEnable)
  private val wbCurrentLastRvi      = RegEnable(s2_endIsHalfRvi, wbEnable)

  s2_wbNotFlush := wbAlignFetchBlock(0).ftqIdx === s2_fetchBlock(0).ftqIdx && s2_valid && wbValid

  private val checkerRedirect = checkerOutStage2.checkerRedirect
  private val checkFlushWb = {
    val b         = Wire(Valid(new FrontendRedirect))
    val missIdx   = checkerRedirect.bits.misIdx.bits
    val ftqIdx    = VecInit(wbAlignFetchBlock.map(_.ftqIdx))
    val startAddr = VecInit(wbAlignFetchBlock.map(_.startVAddr.toUInt))
    b.valid          := wbValid && checkerRedirect.valid
    b.bits.ftqIdx    := Mux(checkerRedirect.bits.selectBlock, ftqIdx(1), ftqIdx(0))
    b.bits.pc        := Mux(checkerRedirect.bits.selectBlock, startAddr(1), startAddr(0))
    b.bits.taken     := checkerRedirect.bits.taken
    b.bits.ftqOffset := checkerRedirect.bits.endOffset
    b.bits.isRVC     := checkerRedirect.bits.isRVC
    b.bits.attribute := checkerRedirect.bits.attribute
    b.bits.target    := checkerRedirect.bits.target.toUInt
    b
  }

  toFtq.wbRedirect := Mux(wbValid, checkFlushWb, uncacheFlushWb)

  wbRedirect.valid       := checkFlushWb.valid
  wbRedirect.isHalfInstr := wbCurrentLastRvi && checkerRedirect.bits.invalidTaken && !checkerRedirect.bits.notCfiTaken
  wbRedirect.instrCount  := wbInstrCount
  wbRedirect.prevIBufEnqPtr := wbPrevIBufEnqPtr
  wbRedirect.halfPc         := checkerRedirect.bits.mispredPc
  wbRedirect.halfData       := wbCurrentLastHalfData

  private val s1_icachePerfInfo = RegEnable(io.fromICache.perf, s0_fire)
  private val s2_icachePerfInfo = RegEnable(s1_icachePerfInfo, s1_fire)

  val perfEvents: Seq[(String, Bool)] = Seq(
    ("frontendFlush                ", wbRedirect.valid),
    ("ifu_req                      ", io.toIBuffer.fire),
    ("ifu_miss                     ", io.toIBuffer.fire && !s2_icachePerfInfo.hit),
    ("ifu_req_cacheline_0          ", io.toIBuffer.fire),
    ("ifu_req_cacheline_1          ", io.toIBuffer.fire && s2_icachePerfInfo.isDoubleLine),
    ("ifu_req_cacheline_0_hit      ", io.toIBuffer.fire && s2_icachePerfInfo.hit0),
    ("ifu_req_cacheline_1_hit      ", io.toIBuffer.fire && s2_icachePerfInfo.hit1),
    ("only_0_hit                   ", io.toIBuffer.fire && s2_icachePerfInfo.hit0NoReq1),
    ("only_0_miss                  ", io.toIBuffer.fire && s2_icachePerfInfo.miss0NoReq1),
    ("hit_0_hit_1                  ", io.toIBuffer.fire && s2_icachePerfInfo.hit0Hit1),
    ("hit_0_miss_1                 ", io.toIBuffer.fire && s2_icachePerfInfo.hit0Miss1),
    ("miss_0_hit_1                 ", io.toIBuffer.fire && s2_icachePerfInfo.miss0Hit1),
    ("miss_0_miss_1                ", io.toIBuffer.fire && s2_icachePerfInfo.miss0Miss1)
  )
  generatePerfEvent()
  perfAnalyzer.io.ifuPerfCtrl.fromFtqBubble    := !fromICache.valid && fromICache.ready
  perfAnalyzer.io.ifuPerfCtrl.backendRedirect  := backendRedirect
  perfAnalyzer.io.ifuPerfCtrl.ifuWbRedirect    := wbRedirect.valid
  perfAnalyzer.io.ifuPerfCtrl.fromBpuFlush     := s0_flushFromBpu(0) // FIXME
  perfAnalyzer.io.ifuPerfCtrl.fromICacheBubble := false.B

  perfAnalyzer.io.topdownIn.icacheTopdown          := io.fromICache.topdown
  perfAnalyzer.io.topdownIn.ftqTopdown             := io.fromFtq.topdownInfo
  perfAnalyzer.io.topdownIn.backendRedirectTopdown := io.backendRedirectTopdown

  perfAnalyzer.io.perfInfo.icachePerfInfo                 := s2_icachePerfInfo
  perfAnalyzer.io.perfInfo.checkPerfInfo.valid(0)         := wbValid && wbFirstValid
  perfAnalyzer.io.perfInfo.checkPerfInfo.valid(1)         := wbValid && wbSecondValid
  perfAnalyzer.io.perfInfo.checkPerfInfo.perfFaultType(0) := checkerOutStage2.perfFaultType(0)
  perfAnalyzer.io.perfInfo.checkPerfInfo.perfFaultType(1) := checkerOutStage2.perfFaultType(1)
  perfAnalyzer.io.perfInfo.checkPerfInfo.startVAddr(0)    := wbAlignFetchBlock(0).startVAddr.toUInt
  perfAnalyzer.io.perfInfo.checkPerfInfo.startVAddr(1)    := wbAlignFetchBlock(1).startVAddr.toUInt
  perfAnalyzer.io.perfInfo.checkPerfInfo.target(0)        := wbAlignFetchBlock(0).target.toUInt
  perfAnalyzer.io.perfInfo.checkPerfInfo.target(1)        := wbAlignFetchBlock(1).target.toUInt
  perfAnalyzer.io.perfInfo.checkPerfInfo.taken(0)         := wbAlignFetchBlock(0).takenCfiOffset.valid
  perfAnalyzer.io.perfInfo.checkPerfInfo.taken(1)         := wbAlignFetchBlock(1).takenCfiOffset.valid
  perfAnalyzer.io.perfInfo.checkPerfInfo.misPred          := checkerRedirect.valid
  perfAnalyzer.io.perfInfo.checkPerfInfo.selectBlock      := checkerRedirect.bits.selectBlock
  perfAnalyzer.io.perfInfo.checkPerfInfo.misEndOffset     := checkerRedirect.bits.endOffset
  perfAnalyzer.io.perfInfo.checkPerfInfo.uncacheBubble    := s2_reqIsUncache && !s2_uncacheCanGo

  perfAnalyzer.io.perfInfo.toIBufferInfo.ibufferFire   := io.toIBuffer.fire
  perfAnalyzer.io.perfInfo.toIBufferInfo.enqEnable     := io.toIBuffer.bits.enqEnable & io.toIBuffer.bits.valid
  perfAnalyzer.io.perfInfo.toIBufferInfo.startVAddr(0) := s2_fetchBlock(0).startVAddr.toUInt
  perfAnalyzer.io.perfInfo.toIBufferInfo.startVAddr(1) := s2_fetchBlock(1).startVAddr.toUInt
  io.toIBuffer.bits.topdownInfo                        := perfAnalyzer.io.topdownOut.topdown

  XSPerfAccumulate("2fetch_to_ibuffer", io.toIBuffer.fire && s2_secondValid)
}
