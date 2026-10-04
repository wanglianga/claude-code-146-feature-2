package com.studyroom.service

import com.studyroom.domain.*
import com.studyroom.repo.*
import com.studyroom.support.BizException
import com.studyroom.support.ConflictException
import com.studyroom.support.NotFoundException
import com.studyroom.support.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

data class CreateBookingCommand(
    val userId: Long,
    val storeId: Long,
    val seatId: Long? = null,
    val planId: Long? = null,
    val type: BookingType,
    val startTime: Instant? = null,
    val endTime: Instant? = null,
    val useLocker: Boolean = false,
    val silenceRuleAcknowledged: Boolean = false
)

@Service
class BookingService(
    private val bookingRepo: BookingRepository,
    private val userRepo: AppUserRepository,
    private val storeRepo: StoreRepository,
    private val seatRepo: SeatRepository,
    private val planRepo: MembershipPlanRepository,
    private val voucherRepo: ExtensionVoucherRepository,
    private val billing: BillingService,
    private val timeline: TimelineService,
    private val clock: TimeProvider
) {
    @Transactional
    fun create(cmd: CreateBookingCommand): Booking {
        val user = userRepo.findById(cmd.userId).orElseThrow { NotFoundException("用户不存在") }
        val store = storeRepo.findById(cmd.storeId).orElseThrow { NotFoundException("门店不存在") }
        if (user.blacklisted) throw ConflictException("USER_BLACKLISTED", "用户已被限制预约")
        if (!cmd.silenceRuleAcknowledged) {
            throw BizException("SILENCE_RULE_REQUIRED", "请先确认静音规则")
        }

        return if (cmd.type == BookingType.MONTHLY) createMonthly(user, store, cmd)
        else createTimed(user, store, cmd)
    }

    private fun createTimed(user: AppUser, store: Store, cmd: CreateBookingCommand): Booking {
        val seatId = cmd.seatId ?: throw BizException("SEAT_REQUIRED", "计时类预约必须选择座位")
        val seat = seatRepo.findByStoreIdAndId(store.id!!, seatId)
            ?: throw NotFoundException("座位不存在或不属于该门店")
        if (seat.status == SeatStatus.MAINTENANCE || !seat.active) {
            throw ConflictException("SEAT_UNAVAILABLE", "座位 ${seat.code} 维护中，暂不可约")
        }
        if (cmd.useLocker && !seat.lockerAvailable) {
            throw BizException("LOCKER_UNAVAILABLE", "该座位无可用储物柜")
        }
        val start = cmd.startTime ?: throw BizException("START_REQUIRED", "请选择开始时间")
        val end = cmd.endTime ?: throw BizException("END_REQUIRED", "请选择结束时间")
        if (!end.isAfter(start)) throw BizException("BAD_TIME_RANGE", "结束时间必须晚于开始时间")

        val expectedType = when (seat.seatType) {
            SeatType.SINGLE -> BookingType.SINGLE
            SeatType.ROOM -> BookingType.ROOM
            SeatType.NIGHT -> BookingType.NIGHT
        }
        if (cmd.type != expectedType) {
            throw BizException("SEAT_TYPE_MISMATCH", "该座位只能按 ${seat.seatType} 类型预约")
        }
        ensureNoOverlap(seatId, start, end, ignoreBookingId = null)

        val minutes = Duration.between(start, end).toMinutes()
        val price = billing.baseFee(seat, store, cmd.type, minutes)
        val deposit = billing.depositFor(seat, cmd.useLocker)

        val b = bookingRepo.save(
            Booking(
                bookingNo = "TMP",
                user = user, store = store, seat = seat, type = cmd.type,
                status = BookingStatus.PENDING_PAYMENT,
                startTime = start, endTime = end,
                price = price, deposit = deposit,
                useLocker = cmd.useLocker,
                silenceRuleAcknowledged = true
            )
        )
        b.bookingNo = BizNoGenerator.booking(b.id!!)
        timeline.record("BOOKING_CREATED", store.id, b.bookingNo, "USER:${user.id}",
            "${seat.code} $start ~ $end，费用 $price 押金 $deposit 储物柜=${cmd.useLocker}")
        return b
    }

    private fun createMonthly(user: AppUser, store: Store, cmd: CreateBookingCommand): Booking {
        val plan = cmd.planId?.let { planRepo.findById(it).orElseThrow { NotFoundException("套餐不存在") } }
            ?: throw BizException("PLAN_REQUIRED", "包月预约必须选择套餐")
        if (plan.store.id != store.id || !plan.active) throw BizException("PLAN_INVALID", "套餐不可用")
        val start = (cmd.startTime ?: clock.now())
        val end = start.plus(Duration.ofDays(plan.days.toLong())).minusSeconds(1)
        val storeId = store.id!!
        val userId = user.id!!

        // 包月权益冲突：同门店有效期重叠的包月权益直接拒绝
        if (bookingRepo.findActiveMembership(storeId, userId, start) != null ||
            bookingRepo.findActiveMembership(storeId, userId, end) != null
        ) {
            throw ConflictException("MEMBERSHIP_CONFLICT", "已存在有效期重叠的包月权益")
        }

        var seat: Seat? = null
        if (plan.fixedSeat) {
            seat = cmd.seatId?.let {
                seatRepo.findByStoreIdAndId(storeId, it)
                    ?: throw NotFoundException("座位不存在或不属于该门店")
            } ?: throw BizException("FIXED_SEAT_REQUIRED", "固定座包月需指定座位")
            if (plan.seatType != null && seat.seatType != plan.seatType) {
                throw BizException("SEAT_TYPE_MISMATCH", "座位与套餐适用类型不符")
            }
            ensureNoOverlap(seat.id!!, start, end, null)
        }

        val b = bookingRepo.save(
            Booking(
                bookingNo = "TMP",
                user = user, store = store, seat = seat, plan = plan,
                type = BookingType.MONTHLY,
                status = BookingStatus.PENDING_PAYMENT,
                startTime = start, endTime = end,
                price = plan.price, deposit = plan.deposit,
                useLocker = cmd.useLocker,
                silenceRuleAcknowledged = true
            )
        )
        b.bookingNo = BizNoGenerator.booking(b.id!!)
        timeline.record("MEMBERSHIP_CREATED", store.id, b.bookingNo, "USER:${user.id}",
            "套餐 ${plan.name}，${if (plan.fixedSeat) "固定座 ${seat!!.code}" else "浮动权益"}")
        return b
    }

    /** 模拟支付：支付时再次校验冲突，防止两个待支付订单先后支付造成重座 */
    @Transactional
    fun pay(bookingNo: String): Booking {
        val b = bookingRepo.findByBookingNo(bookingNo) ?: throw NotFoundException("预约不存在")
        if (b.status != BookingStatus.PENDING_PAYMENT) {
            throw ConflictException("INVALID_STATUS", "当前状态 ${b.status} 不可支付")
        }
        b.seat?.let { ensureNoOverlap(it.id!!, b.startTime, b.effectiveEndTime, b.id) }

        b.status = BookingStatus.CONFIRMED
        b.paidAmount = b.price.add(b.deposit)
        billing.charge(b, ChargeType.FEE, b.price, "预约费用")
        if (b.deposit.signum() > 0) billing.charge(b, ChargeType.DEPOSIT, b.deposit, "押金")
        timeline.record("PAYMENT_CONFIRMED", b.store.id, b.bookingNo, "USER:${b.user.id}",
            "支付 ${b.paidAmount}（费用 ${b.price} + 押金 ${b.deposit}）")
        return b
    }

    @Transactional
    fun cancel(bookingNo: String): Booking {
        val b = mustGet(bookingNo)
        if (b.status == BookingStatus.IN_PROGRESS) throw ConflictException("INVALID_STATUS", "已进场订单请走离场结算")
        if (b.status == BookingStatus.COMPLETED || b.status == BookingStatus.CANCELLED || b.status == BookingStatus.NO_SHOW) {
            throw ConflictException("INVALID_STATUS", "订单 ${b.status} 不可取消")
        }
        b.status = BookingStatus.CANCELLED
        if (b.paidAmount.signum() > 0) {
            billing.refund(b, RefundReason.UNUSED_FULL, b.paidAmount, note = "开场前取消，全额原路退回")
        }
        timeline.record("BOOKING_CANCELLED", b.store.id, b.bookingNo, "USER:${b.user.id}", "未消费取消")
        return b
    }

    /**
     * 临时延时申请（无人值守场景）。
     * - 后续无人预约：直接批准并计费；
     * - 下一位用户已支付但未进场：批准，但记录碰撞，下一位进场时自动换座+补偿；
     * - 下一位用户已经在场：拒绝，避免侵占。
     *
     * 传入 [voucherNo] 时使用噪音投诉补偿的延时券抵扣，本次延时免费；
     * 延时券与订单状态在同一事务内更新。
     */
    @Transactional
    fun extend(bookingNo: String, minutes: Long, voucherNo: String? = null): Booking {
        if (minutes <= 0) throw BizException("BAD_MINUTES", "延时分钟数必须大于 0")
        val b = mustGet(bookingNo)
        if (b.status != BookingStatus.IN_PROGRESS && b.status != BookingStatus.CONFIRMED) {
            throw ConflictException("INVALID_STATUS", "仅进行中或已确认的订单可延时")
        }
        if (b.type == BookingType.MONTHLY) throw BizException("MONTHLY_NO_EXTEND", "包月权益在有效期内无需延时")
        val seat = b.seat ?: throw BizException("NO_SEAT", "订单未分配座位")

        val currentEnd = b.effectiveEndTime
        val newEnd = currentEnd.plus(Duration.ofMinutes(minutes))
        val blockers = bookingRepo.findActiveOnSeat(seat.id!!, currentEnd)
            .filter { it.id != b.id && it.startTime < newEnd }
        if (blockers.any { it.status == BookingStatus.IN_PROGRESS }) {
            throw ConflictException("SEAT_STILL_IN_USE", "下一位用户已经在场，无法延时到 $newEnd")
        }
        val waiting = blockers.filter { it.status == BookingStatus.CONFIRMED }

        val voucher = voucherNo?.let { no ->
            val v = voucherRepo.findByVoucherNo(no) ?: throw NotFoundException("延时券不存在")
            if (v.owner.id != b.user.id) throw BizException("VOUCHER_NOT_OWNED", "延时券不属于当前用户")
            if (v.status != VoucherStatus.ISSUED) throw BizException("VOUCHER_NOT_AVAILABLE", "延时券状态 ${v.status}，不可使用")
            if (v.sourceBookingNo != b.bookingNo) {
                throw BizException("VOUCHER_BOUND_TO_OTHER_BOOKING", "该延时券与原订单 ${v.sourceBookingNo} 绑定，仅可用于该订单的延时")
            }
            if (v.minutes != minutes) {
                throw BizException("VOUCHER_MINUTES_MISMATCH", "该延时券面额为 ${v.minutes} 分钟，须一次性核销")
            }
            v
        }

        if (voucher != null) {
            voucher.status = VoucherStatus.REDEEMED
            voucher.redeemedAt = clock.now()
            voucher.redeemedOnBookingNo = b.bookingNo
        } else {
            val fee = billing.extensionFee(seat, b.store, minutes)
            billing.charge(b, ChargeType.EXTENSION_FEE, fee, "临时延时 $minutes 分钟")
            b.paidAmount = b.paidAmount.add(fee)
        }
        b.extendedEndTime = newEnd
        b.extended = true

        timeline.record(
            if (voucher != null) "EXTENSION_APPROVED_WITH_VOUCHER" else "EXTENSION_APPROVED",
            b.store.id, b.bookingNo, "USER:${b.user.id}",
            (if (voucher != null) "延时券 ${voucher.voucherNo} 抵扣，延时至 $newEnd，免收延时费"
             else "延时至 $newEnd") +
                if (waiting.isNotEmpty()) "；碰撞预警：影响后续订单 ${waiting.joinToString { it.bookingNo }}" else ""
        )
        waiting.forEach { next ->
            timeline.record(
                "COLLISION_PREDICTED", b.store.id, next.bookingNo, "SYSTEM",
                "上一订单 ${b.bookingNo} 延时至 $newEnd，占用本订单座位 ${seat.code}，进场时自动处理换座"
            )
        }
        return b
    }

    /** 迟到顺延：把未使用的窗口整体后移（等价于按迟到分钟延时，费用多退少补策略此处免收顺延差价） */
    @Transactional
    fun postpone(bookingNo: String, minutes: Long): Booking {
        if (minutes <= 0) throw BizException("BAD_MINUTES", "顺延分钟数必须大于 0")
        val b = mustGet(bookingNo)
        if (b.status != BookingStatus.CONFIRMED) {
            throw ConflictException("INVALID_STATUS", "仅已确认未进场的订单可申请顺延")
        }
        if (b.type == BookingType.MONTHLY) throw BizException("MONTHLY_NO_POSTPONE", "包月权益无需顺延")
        if (clock.now() <= b.startTime) throw BizException("NOT_LATE", "未迟到，无需顺延")
        return extend(bookingNo, minutes)
    }

    fun mustGet(bookingNo: String): Booking =
        bookingRepo.findByBookingNo(bookingNo) ?: throw NotFoundException("预约 $bookingNo 不存在")

    private fun ensureNoOverlap(seatId: Long, start: Instant, end: Instant, ignoreBookingId: Long?) {
        val clash = bookingRepo.findOverlapping(seatId, start, end).firstOrNull { it.id != ignoreBookingId }
        if (clash != null) {
            throw ConflictException(
                "SEAT_TIME_CONFLICT",
                "座位时段冲突：已被 ${clash.bookingNo}（${clash.startTime} ~ ${clash.effectiveEndTime}）占用"
            )
        }
    }

    /**
     * 定时/手动扫描：超过宽限期仍未到场的订单标记爽约，押金退回，累计爽约次数；
     * 同时关闭已到期的包月权益并退还押金余额。
     */
    @Transactional
    fun runSweep(): SweepResult {
        val noShows = mutableListOf<String>()
        for (b in bookingRepo.findArrivedButNotCheckedIn(clock.now())) {
            val deadline = b.startTime.plus(Duration.ofMinutes(b.store.lateGraceMinutes.toLong()))
            if (clock.now().isAfter(deadline)) {
                b.status = BookingStatus.NO_SHOW
                b.late = true
                b.lateMinutes = Duration.between(b.startTime, clock.now()).toMinutes()
                b.user.noShowCount += 1
                if (b.deposit.signum() > 0) {
                    billing.refund(b, RefundReason.UNUSED_FULL, b.deposit, depositRefund = true, note = "爽约：押金退回")
                }
                timeline.record("NO_SHOW", b.store.id, b.bookingNo, "SYSTEM",
                    "超过宽限 ${b.store.lateGraceMinutes} 分钟未进场，计爽约；使用费 ${b.price} 不退")
                noShows.add(b.bookingNo)
            }
        }

        val closedMemberships = mutableListOf<String>()
        for (m in bookingRepo.findExpiredMemberships(clock.now())) {
            m.status = BookingStatus.COMPLETED
            val back = m.deposit.subtract(m.depositDeducted)
            if (back.signum() > 0) {
                billing.refund(m, RefundReason.DEPOSIT_RETURN, back, depositRefund = true, note = "包月到期，押金余额退回")
            }
            timeline.record("MEMBERSHIP_CLOSED", m.store.id, m.bookingNo, "SYSTEM",
                "包月权益到期关闭${if (back.signum() > 0) "，退还押金 $back 元" else ""}")
            closedMemberships.add(m.bookingNo)
        }
        return SweepResult(noShows, closedMemberships)
    }

    @Transactional
    fun markNoShows(): List<String> = runSweep().noShowBookings
}

data class SweepResult(val noShowBookings: List<String>, val closedMemberships: List<String>)
