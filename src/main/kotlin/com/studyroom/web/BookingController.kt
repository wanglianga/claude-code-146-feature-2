package com.studyroom.web

import com.studyroom.repo.BookingRepository
import com.studyroom.repo.ChargeRepository
import com.studyroom.repo.RefundRepository
import com.studyroom.service.BookingService
import com.studyroom.service.CreateBookingCommand
import com.studyroom.support.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@Tag(name = "预约与支付", description = "下单、支付、取消、迟到顺延、临时延时")
@RestController
@RequestMapping("/api/bookings")
@org.springframework.transaction.annotation.Transactional
class BookingController(
    private val bookingService: BookingService,
    private val bookingRepo: BookingRepository,
    private val chargeRepo: ChargeRepository,
    private val refundRepo: RefundRepository
) {
    @Operation(summary = "创建预约（单座/包间/夜间座/包月权益）")
    @PostMapping
    fun create(@RequestBody @Valid req: CreateBookingRequest): BookingView =
        Views.of(
            bookingService.create(
                CreateBookingCommand(
                    userId = req.userId, storeId = req.storeId, seatId = req.seatId, planId = req.planId,
                    type = req.type, startTime = req.startTime, endTime = req.endTime,
                    useLocker = req.useLocker, silenceRuleAcknowledged = req.silenceRuleAcknowledged
                )
            )
        )

    @Operation(summary = "模拟支付（费用+押金，立即到账）")
    @PostMapping("/{bookingNo}/pay")
    fun pay(@PathVariable bookingNo: String): BookingView = Views.of(bookingService.pay(bookingNo))

    @Operation(summary = "取消未消费订单（已支付则全额原路退回，释放占用）")
    @PostMapping("/{bookingNo}/cancel")
    fun cancel(@PathVariable bookingNo: String): BookingView = Views.of(bookingService.cancel(bookingNo))

    @Operation(summary = "迟到顺延（将结束时间整体后移，可能预警碰撞）")
    @PostMapping("/{bookingNo}/postpone")
    fun postpone(@PathVariable bookingNo: String, @RequestBody @Valid req: ExtendRequest): BookingView =
        Views.of(bookingService.postpone(bookingNo, req.minutes))

    @Operation(summary = "临时延时（可传 voucherNo 用噪音补偿延时券免费抵扣；占用下一座位会预警/拒绝）")
    @PostMapping("/{bookingNo}/extend")
    fun extend(@PathVariable bookingNo: String, @RequestBody @Valid req: ExtendRequest): BookingView =
        Views.of(bookingService.extend(bookingNo, req.minutes, req.voucherNo))

    @Operation(summary = "预约详情")
    @GetMapping("/{bookingNo}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun get(@PathVariable bookingNo: String): BookingView =
        Views.of(bookingRepo.findByBookingNo(bookingNo) ?: throw NotFoundException("预约不存在"))

    @Operation(summary = "预约账目流水（费用/押金/延时费/扣赔）")
    @GetMapping("/{bookingNo}/charges")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun charges(@PathVariable bookingNo: String): List<ChargeView> {
        val b = bookingRepo.findByBookingNo(bookingNo) ?: throw NotFoundException("预约不存在")
        return chargeRepo.findByBookingIdOrderByCreatedAtAsc(b.id!!).map { Views.of(it) }
    }

    @Operation(summary = "预约退款记录")
    @GetMapping("/{bookingNo}/refunds")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun refunds(@PathVariable bookingNo: String): List<RefundView> {
        val b = bookingRepo.findByBookingNo(bookingNo) ?: throw NotFoundException("预约不存在")
        return refundRepo.findByBookingIdOrderByCreatedAtDesc(b.id!!).map { Views.of(it) }
    }
}
