package com.studyroom.service

import com.studyroom.domain.*
import com.studyroom.repo.*
import com.studyroom.support.BizException
import com.studyroom.support.ConflictException
import com.studyroom.support.NotFoundException
import com.studyroom.support.SeatLockManager
import com.studyroom.support.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/** 店员处置决策结果（体现“先判断再处置”，决策理由可追溯） */
data class NoiseDecision(
    val action: NoiseEnforcementAction,
    val reason: String
)

/** 单个区域的噪音聚合诊断 */
data class ZoneNoiseStat(
    val zone: String,
    /** 窗口内已成立案件数 */
    val cases: Int,
    /** 涉案不同用户数 */
    val distinctRespondents: Int,
    /** 属于累犯（此前已有成立噪音记录）的案件数 */
    val repeatOffenderCases: Int,
    val warnings: Int,
    val movedToPhoneZone: Int,
    val terminated: Int,
    /** 累犯率 = 1 - 涉案人数/案件数 */
    val repeatRate: Double,
    /** 布���信号：投诉主要来自彼此不同的用户 */
    val layoutSignal: Boolean,
    /** 提醒无效信号：半数以上案件为被提醒后的再犯 */
    val reminderSignal: Boolean,
    val frequent: Boolean,
    val diagnosis: ZoneNoiseDiagnosis,
    val latestReviewAction: ZoneReviewAction?,
    val latestReviewAt: Instant?,
    val latestReviewDiagnosis: ZoneNoiseDiagnosis?,
    /** 最近一次治理措施之后（至窗口结束）新增成立案件数，用于评估措施有效性 */
    val casesAfterLatestReview: Int?
)

data class ZoneNoiseStats(
    val storeId: Long,
    val from: Instant,
    val to: Instant,
    val frequentThreshold: Int,
    val zones: List<ZoneNoiseStat>,
    val totalSubstantiatedCases: Int
)

/**
 * 邻座噪音投诉处理。
 *
 * 业务主线（与需求一一对应）：
 * 1. [receive] 受理：记录接收时间、座位号、投诉人是否已自行提醒；**不结束**被投诉订单；
 *    同步生成通用投诉与客服工单（一一对应），自动归因 USER_BEHAVIOR。
 * 2. [patrol] 店员巡查：确认属实 / 不属实 / 违规者已离场。
 * 3. [enforce] 处置决策树（违规者在场时）：
 *    愿意换电话区 → 换座（订单保留）；
 *    不愿换但接受提醒 → 警告（订单继续）；
 *    既不愿换也不接受提醒：多次违规 → 终止预约（强制离场结算），首次违规 → 书面警告留档。
 * 4. 投诉成立即给被影响用户发放延时券，券与其**原订单绑定**（[DelayVoucher.bookingNo]），
 *    后续 [redeemVoucher] 只能在券主的其他预约上抵扣延时。
 * 5. [zoneStats] 按区域聚合，区分“座位布局问题（多名不同用户涉案、累犯率低）”
 *    与“管理提醒无效（被警告后再犯率高）”；[reviewZone] 对运营治理动作留痕，
 *    下次统计会给出措施后新增案件数。
 */
@Service
class NoiseComplaintService(
    private val noiseCaseRepo: NoiseComplaintCaseRepository,
    private val voucherRepo: DelayVoucherRepository,
    private val zoneReviewRepo: ZoneNoiseReviewRepository,
    private val bookingRepo: BookingRepository,
    private val seatRepo: SeatRepository,
    private val sensorRepo: SeatSensorRepository,
    private val storeRepo: StoreRepository,
    private val accessLogRepo: AccessLogRepository,
    private val ticketRepo: SupportTicketRepository,
    private val complaints: ComplaintService,
    private val billing: BillingService,
    private val seatLocks: SeatLockManager,
    private val timeline: TimelineService,
    private val clock: TimeProvider
) {
    companion object {
        /** 电话区分区名（允许外放网课/通话的隔离区） */
        const val PHONE_ZONE = "电话区"
        /** 默认补偿延时券分钟数 */
        const val DEFAULT_VOUCHER_MINUTES = 30
        /** 区域“频繁发生”阈值 */
        const val FREQUENT_THRESHOLD = 3
        /** 历史已有该次数成立违规即视为“多次违规”（本次为第 2 次） */
        const val REPEAT_VIOLATION_THRESHOLD = 1
    }

    // ---------------- 受理 ----------------

    /**
     * 受理邻座噪音投诉。
     *
     * @param complainantBookingNo 被影响用户的在场预约号
     * @param respondentBookingNo 被投诉预约号；不传时尝试按“同区域其他在场用户”自动定位，
     *        有多名在场用户时必须显式指定
     * @param alreadyReminded 投诉人是否已提醒过邻座
     */
    @Transactional
    fun receive(
        complainantBookingNo: String,
        description: String,
        alreadyReminded: Boolean,
        respondentBookingNo: String? = null
    ): NoiseComplaintCase {
        val now = clock.now()
        val complainantBooking = bookingRepo.findByBookingNo(complainantBookingNo)
            ?: throw NotFoundException("预约 $complainantBookingNo 不存在")
        if (complainantBooking.status != BookingStatus.IN_PROGRESS) {
            throw ConflictException("NOT_IN_STORE", "仅在场预约可受理邻座噪音投诉（当前 ${complainantBooking.status}）")
        }
        val store = complainantBooking.store
        val complainantSeat = complainantBooking.seat
            ?: throw BizException("NO_SEAT", "投诉人订单未分配座位")

        val respondent = resolveRespondent(store.id!!, complainantBooking, respondentBookingNo)

        // 同步生成通用投诉 + 客服工单，复用既有“投诉—工单一一对应、自动归因”能力
        val complaint = complaints.file(
            bookingNo = complainantBookingNo,
            category = ComplaintCategory.NOISE_NEIGHBOR,
            description = description,
            againstBookingNo = respondent?.bookingNo
        )

        val case = noiseCaseRepo.save(
            NoiseComplaintCase(
                caseNo = "TMP",
                store = store,
                complainant = complainantBooking.user,
                respondent = respondent?.user,
                complainantBookingNo = complainantBookingNo,
                respondentBookingNo = respondent?.bookingNo,
                zone = complainantSeat.zone,
                complainantSeatId = complainantSeat.id,
                complainantSeatCode = complainantSeat.code,
                respondentSeatId = respondent?.seat?.id,
                respondentSeatCode = respondent?.seat?.code,
                receivedAt = now,
                alreadyReminded = alreadyReminded,
                description = description,
                relatedComplaintNo = complaint.complaintNo,
                relatedTicketNo = complaint.relatedTicketNo
            )
        )
        case.caseNo = BizNoGenerator.noiseCase(case.id!!)
        timeline.record(
            "NOISE_COMPLAINT_RECEIVED", store.id, complainantBookingNo, "USER:${complainantBooking.user.id}",
            "${case.caseNo} 座位 ${complainantSeat.code}（${complainantSeat.zone}），" +
                "已自行提醒=$alreadyReminded" +
                (respondent?.let { "，被诉订单 ${it.bookingNo}（${it.seat?.code}）" } ?: "，被诉人待店员现场指认")
        )
        return case
    }

    private fun resolveRespondent(
        storeId: Long,
        complainantBooking: Booking,
        explicit: String?
    ): Booking? {
        val complainantSeatId = complainantBooking.seat?.id
        if (explicit != null) {
            val b = bookingRepo.findByBookingNo(explicit) ?: throw NotFoundException("被投诉预约 $explicit 不存在")
            if (b.store.id != storeId) throw BizException("STORE_MISMATCH", "被投诉预约不属于该门店")
            if (b.status != BookingStatus.IN_PROGRESS) {
                throw ConflictException("RESPONDENT_NOT_IN_STORE", "被投诉用户当前不在场（订单 ${b.status}）")
            }
            if (b.seat?.id == complainantSeatId) {
                throw BizException("SAME_SEAT", "不能投诉同一座位的订单")
            }
            return b
        }
        // 自动定位：同区域、其他座位、当前在场的订单
        val sameZone = bookingRepo.findAll().filter {
            it.store.id == storeId &&
                it.status == BookingStatus.IN_PROGRESS &&
                it.seat?.id != complainantSeatId &&
                it.seat?.zone == complainantBooking.seat?.zone
        }
        return when {
            sameZone.isEmpty() -> null
            sameZone.size == 1 -> sameZone.first()
            else -> throw BizException(
                "RESPONDENT_AMBIGUOUS",
                "该区域有多名在场用户（${sameZone.joinToString { it.bookingNo }}），请指定被投诉预约号"
            )
        }
    }

    // ---------------- 店员巡查 ----------------

    /**
     * 店员现场巡查。
     * - SUBSTANTIATED：外放属实且违规者在场，案件等待 [enforce] 处置（可此时补录被诉订单）；
     * - UNFOUNDED：未发现噪音，投诉驳回，不处置不补偿；
     * - OFFENDER_LEFT：违规者已停止/离场，投诉按成立记录，直接发券，不再现场处置。
     */
    @Transactional
    fun patrol(
        caseNo: String,
        result: NoisePatrolResult,
        note: String? = null,
        staffUserId: Long? = null,
        respondentBookingNo: String? = null
    ): NoiseComplaintCase {
        val case = mustGetOpenCase(caseNo)
        if (case.patrolResult != null) {
            throw ConflictException("ALREADY_PATROLLED", "案件已完成巡查（${case.patrolResult}）")
        }

        var respondent = case.respondentBookingNo?.let { bookingRepo.findByBookingNo(it) }
        if (respondent == null && respondentBookingNo != null) {
            respondent = bookingRepo.findByBookingNo(respondentBookingNo)
                ?: throw NotFoundException("被投诉预约 $respondentBookingNo 不存在")
            if (respondent.store.id != case.store.id || respondent.seat?.id == case.complainantSeatId) {
                throw BizException("BAD_RESPONDENT", "被投诉预约与案件不符")
            }
            case.respondent = respondent.user
            case.respondentBookingNo = respondent.bookingNo
            case.respondentSeatId = respondent.seat?.id
            case.respondentSeatCode = respondent.seat?.code
        }

        case.patrolResult = result
        case.patrolledBy = staffUserId
        case.patrolNote = note
        case.patrolledAt = clock.now()
        case.priorViolationCount = respondent?.let { priorViolations(it.user.id!!).size } ?: 0

        val actor = staffUserId?.let { "STAFF:$it" } ?: "STAFF"
        timeline.record("NOISE_PATROL", case.store.id, case.complainantBookingNo, actor,
            "${case.caseNo} 巡查结论 $result${note?.let { "：$it" } ?: ""}")

        when (result) {
            NoisePatrolResult.UNFOUNDED -> rejectCase(case, actor, "店员巡查未发现外放噪音，投诉不成立")
            NoisePatrolResult.OFFENDER_LEFT -> {
                if (respondent == null) {
                    throw BizException("RESPONDENT_REQUIRED", "违规者已离场但需先指认被诉订单，才能记录累犯与补偿")
                }
                substantiateWithoutEnforcement(case, actor,
                    "违规者已停止外放/离场，投诉成立；被影响用户获延时券，现场不再处置")
            }
            NoisePatrolResult.SUBSTANTIATED -> {
                if (respondent == null) {
                    throw BizException("RESPONDENT_REQUIRED", "噪音属实，请在巡查时补录被投诉预约号")
                }
                if (respondent.status != BookingStatus.IN_PROGRESS) {
                    // 巡查与受理之间违规者离场：按 OFFENDER_LEFT 等价处理
                    substantiateWithoutEnforcement(case, actor,
                        "巡查确认噪音属实但违规者已离场；被影响用户获延时券")
                }
                // 在场：保持 OPEN，等待 enforce 处置
            }
        }
        return case
    }

    // ---------------- 处置决策树 ----------------

    /**
     * 按决策树处置已确认、违规者仍在场的案件，并在处置完成后向被影响用户发放延时券。
     *
     * 被投诉订单在受理/巡查阶段均不会结束；是否终止仅由本方法依据
     * “换电话区意愿 → 是否接受提醒 → 是否多次违规”的顺序判断。
     */
    @Transactional
    fun enforce(
        caseNo: String,
        willingToMovePhoneZone: Boolean,
        acceptStaffWarning: Boolean,
        voucherMinutes: Int = DEFAULT_VOUCHER_MINUTES,
        note: String? = null,
        staffUserId: Long? = null
    ): NoiseComplaintCase {
        if (voucherMinutes <= 0) throw BizException("BAD_MINUTES", "延时券分钟数必须大于 0")
        val case = mustGetOpenCase(caseNo)
        if (case.patrolResult != NoisePatrolResult.SUBSTANTIATED) {
            throw ConflictException("NOT_SUBSTANTIATED", "需店员巡查确认属实且违规者在场后才能处置")
        }
        val respondentBooking = case.respondentBookingNo?.let { bookingRepo.findByBookingNo(it) }
            ?: throw BizException("RESPONDENT_REQUIRED", "缺少被投诉订单")
        if (respondentBooking.status != BookingStatus.IN_PROGRESS) {
            throw ConflictException("RESPONDENT_NOT_IN_STORE", "被投诉用户已不在场，无法现场处置")
        }

        case.willingToMovePhoneZone = willingToMovePhoneZone
        case.acceptStaffWarning = acceptStaffWarning

        val decision = decide(case, respondentBooking)
        val actor = staffUserId?.let { "STAFF:$it" } ?: "STAFF"
        val noteSuffix = note?.let { "；$it" } ?: ""

        when (decision.action) {
            NoiseEnforcementAction.WARN -> {
                case.enforcementAction = NoiseEnforcementAction.WARN
                timeline.record("NOISE_WARNING", case.store.id, respondentBooking.bookingNo, actor,
                    "对 ${respondentBooking.user.name} 口头/书面警告（${decision.reason}），订单继续$noteSuffix")
            }
            NoiseEnforcementAction.MOVE_TO_PHONE_ZONE -> {
                val target = moveToPhoneZone(case, respondentBooking, actor)
                if (target == null) {
                    // 电话区无空位：降级为警告，留痕待有空位后再换
                    case.enforcementAction = NoiseEnforcementAction.WARN
                    timeline.record("NOISE_WARNING", case.store.id, respondentBooking.bookingNo, actor,
                        "用户愿换电话区但该区满座，先行警告（${decision.reason}）$noteSuffix")
                } else {
                    case.enforcementAction = NoiseEnforcementAction.MOVE_TO_PHONE_ZONE
                    case.targetSeatId = target.id
                    case.targetSeatCode = target.code
                    timeline.record("NOISE_MOVE_TO_PHONE_ZONE", case.store.id, respondentBooking.bookingNo, actor,
                        "订单 ${respondentBooking.bookingNo} 由 ${respondentBooking.seat?.code} 换至电话区 ${target.code}，订单保留$noteSuffix")
                }
            }
            NoiseEnforcementAction.TERMINATE_BOOKING -> {
                terminateBooking(case, respondentBooking, actor, decision.reason, noteSuffix)
                case.enforcementAction = NoiseEnforcementAction.TERMINATE_BOOKING
            }
        }
        case.enforcedAt = clock.now()
        case.enforcementNote = decision.reason + noteSuffix

        completeSubstantiated(case, actor, voucherMinutes,
            "处置=${case.enforcementAction}，${decision.reason}")
        return case
    }

    /**
     * 决策树（纯判断，便于测试与复用）：
     * 1. 愿意换电话区 → MOVE_TO_PHONE_ZONE；
     * 2. 不愿换、接受提醒 → WARN；
     * 3. 不愿换、不接受提醒：历史违规 >= 阈值 → TERMINATE_BOOKING，否则 WARN（首违书面警告，再犯直接终止）。
     */
    private fun decide(case: NoiseComplaintCase, respondentBooking: Booking): NoiseDecision {
        if (case.willingToMovePhoneZone == true) {
            return NoiseDecision(
                NoiseEnforcementAction.MOVE_TO_PHONE_ZONE,
                "用户愿意换到电话区，优先换座隔离声源，订单继续"
            )
        }
        if (case.acceptStaffWarning == true) {
            return NoiseDecision(
                NoiseEnforcementAction.WARN,
                "用户不换座但接受店员提醒，给予警告，订单继续"
            )
        }
        return if (case.priorViolationCount >= REPEAT_VIOLATION_THRESHOLD) {
            NoiseDecision(
                NoiseEnforcementAction.TERMINATE_BOOKING,
                "拒绝换座且不接受提醒，历史已有 ${case.priorViolationCount} 次噪音违规成立，终止本次预约"
            )
        } else {
            NoiseDecision(
                NoiseEnforcementAction.WARN,
                "拒绝换座且不接受提醒，但属首次违规（历史 ${case.priorViolationCount} 次），书面警告留档，再犯将终止预约"
            )
        }
    }

    /** 把在场订单换至电话区空闲座位：同临界区内迁移 Redis 占用与传感器，订单状态不变。 */
    private fun moveToPhoneZone(case: NoiseComplaintCase, booking: Booking, actor: String): Seat? {
        val current = booking.seat ?: throw BizException("NO_SEAT", "被投诉订单未分配座位")
        val target = findFreePhoneZoneSeat(booking.store.id!!, current.seatType, booking.bookingNo, clock.now())
            ?: return null

        val oldId = current.id!!
        val newId = target.id!!
        return seatLocks.withSeatLock(oldId) {
            seatLocks.withSeatLock(newId) {
                // 二次确认新座位未被占用
                if (seatLocks.isOccupied(newId) ||
                    sensorRepo.findBySeatId(newId)?.occupied == true
                ) {
                    return@withSeatLock null
                }
                val now = clock.now()
                // 释放旧座位
                current.status = SeatStatus.AVAILABLE
                seatLocks.release(oldId, booking.bookingNo)
                sensorRepo.save(
                    sensorRepo.findBySeatId(oldId)?.apply {
                        occupied = false; occupiedByBookingNo = null; updatedAt = now
                    } ?: SeatSensorReading(seat = current, occupied = false, occupiedByBookingNo = null, updatedAt = now)
                )
                // 占用电话区座位（不写 originalSeatId，避免污染迟到碰撞归因）
                booking.seat = target
                target.status = SeatStatus.OCCUPIED
                val ttl = Duration.between(now, booking.effectiveEndTime)
                    .plus(if (booking.type == BookingType.MONTHLY) Duration.ofMinutes(10) else Duration.ofHours(2))
                seatLocks.markOccupied(newId, booking.bookingNo, ttl)
                sensorRepo.save(
                    sensorRepo.findBySeatId(newId)?.apply {
                        occupied = true; occupiedByBookingNo = booking.bookingNo; updatedAt = now
                    } ?: SeatSensorReading(seat = target, occupied = true, occupiedByBookingNo = booking.bookingNo, updatedAt = now)
                )
                timeline.record("SEAT_RELEASED_ON_NOISE_MOVE", case.store.id, booking.bookingNo, actor,
                    "原座位 ${current.code} 已释放")
                target
            }
        }
    }

    private fun findFreePhoneZoneSeat(storeId: Long, type: SeatType, selfBookingNo: String, now: Instant): Seat? =
        seatRepo.findByStoreIdAndActiveTrue(storeId)
            .filter {
                it.zone == PHONE_ZONE && it.seatType == type && it.status != SeatStatus.MAINTENANCE
            }
            .sortedBy { it.code }
            .firstOrNull { s ->
                val sid = s.id!!
                !seatLocks.isOccupied(sid) &&
                    sensorRepo.findBySeatId(sid)?.occupied != true &&
                    bookingRepo.findActiveOnSeat(sid, now).none { it.actualCheckIn != null }
            }

    /** 终止违规用户预约：强制离场结算并释放座位。用户责任，不退剩余使用费；押金余额正常退还。 */
    private fun terminateBooking(
        case: NoiseComplaintCase, booking: Booking, actor: String, reason: String, noteSuffix: String
    ) {
        val seat = booking.seat ?: throw BizException("NO_SEAT", "被投诉订单未分配座位")
        val seatId = seat.id!!
        val now = clock.now()

        seatLocks.withSeatLock(seatId) {
            val summary: String
            if (booking.type == BookingType.MONTHLY) {
                // 包月权益：终止即关闭整段权益，套餐费不退，押金余额退还
                val back = booking.deposit.subtract(booking.depositDeducted)
                if (back.signum() > 0) {
                    billing.refund(booking, RefundReason.DEPOSIT_RETURN, back, depositRefund = true,
                        note = "噪音违规终止包月权益，押金余额退回")
                }
                summary = "包月权益终止，套餐费不退，押金余额退回 $back"
            } else {
                val settlement = billing.settleCheckout(
                    booking, now,
                    lockerReturned = true, seatEquipmentOk = true,
                    mode = CheckoutMode.NORMAL, incidentNo = null
                )
                summary = settlement.note.ifBlank { "用户责任终止，剩余使用费不退，押金正常退回" }
            }
            booking.actualCheckOut = now
            booking.status = BookingStatus.COMPLETED
            seat.status = SeatStatus.AVAILABLE
            seatLocks.release(seatId, booking.bookingNo)
            sensorRepo.save(
                sensorRepo.findBySeatId(seatId)?.apply {
                    occupied = false; occupiedByBookingNo = null; updatedAt = now
                } ?: SeatSensorReading(seat = seat, occupied = false, occupiedByBookingNo = null, updatedAt = now)
            )

            accessLogRepo.save(
                AccessLog(
                    bookingNo = booking.bookingNo, user = booking.user, store = booking.store,
                    doorId = "STAFF", direction = AccessDirection.OUT, result = AccessResult.GRANTED,
                    detail = "噪音违规（案件 ${case.caseNo}）被终止预约，强制离场：$reason$noteSuffix",
                    monitorEventNo = monitorEventNo(booking.store, "STAFF", AccessResult.GRANTED, now),
                    createdAt = now
                )
            )
            timeline.record("NOISE_BOOKING_TERMINATED", case.store.id, booking.bookingNo, actor,
                "多次噪音违规且不服从管理，终止预约并强制离场；$summary$noteSuffix")
            case.terminatedRefundSummary = summary
        }
    }

    // ---------------- 结案与补偿 ----------------

    private fun rejectCase(case: NoiseComplaintCase, actor: String, reason: String) {
        case.status = NoiseCaseStatus.REJECTED
        case.substantiated = false
        case.resolvedAt = clock.now()
        case.resolution = reason
        case.relatedComplaintNo?.let { complaints.resolve(it, reason, rejected = true) }
        closeTicket(case, reason, Responsibility.NONE)
        timeline.record("NOISE_CASE_REJECTED", case.store.id, case.complainantBookingNo, actor,
            "${case.caseNo}：$reason")
    }

    private fun substantiateWithoutEnforcement(case: NoiseComplaintCase, actor: String, reason: String) {
        completeSubstantiated(case, actor, DEFAULT_VOUCHER_MINUTES, reason)
    }

    /** 成立案件收尾：置 CONFIRMED、发放与原订单绑定的延时券、同步关闭通用投诉与工单。 */
    private fun completeSubstantiated(
        case: NoiseComplaintCase, actor: String, voucherMinutes: Int, reason: String
    ) {
        val complainant = bookingRepo.findByBookingNo(case.complainantBookingNo)?.user
            ?: throw NotFoundException("被影响订单 ${case.complainantBookingNo} 不存在")
        val voucher = issueVoucher(case, complainant, voucherMinutes)

        case.status = NoiseCaseStatus.CONFIRMED
        case.substantiated = true
        case.voucherNo = voucher.voucherNo
        case.resolvedAt = clock.now()
        case.resolution = reason
        case.relatedComplaintNo?.let { complaints.resolve(it, "$reason；延时券 ${voucher.voucherNo}", rejected = false) }
        closeTicket(case, "$reason；延时券 ${voucher.voucherNo}", Responsibility.USER)
        timeline.record("NOISE_CASE_CONFIRMED", case.store.id, case.complainantBookingNo, actor,
            "${case.caseNo}：$reason；被影响用户获 $voucherMinutes 分钟延时券 ${voucher.voucherNo}（绑定原订单 ${case.complainantBookingNo}）")
    }

    /** 噪音案件由现场处置闭环，同步把客服工单置 RESOLVED（避免遗留 OPEN 工单干扰运营统计）。 */
    private fun closeTicket(case: NoiseComplaintCase, note: String, responsibility: Responsibility) {
        val ticketNo = case.relatedTicketNo ?: return
        val t = ticketRepo.findByTicketNo(ticketNo) ?: return
        if (t.status == TicketStatus.CLOSED || t.status == TicketStatus.RESOLVED) return
        t.status = TicketStatus.RESOLVED
        t.responsibility = responsibility
        t.staffNote = note.take(1000)
        t.resolvedAt = clock.now()
        t.updatedAt = clock.now()
        timeline.record("TICKET_RESOLVED", case.store.id, case.complainantBookingNo, "STAFF",
            "工单 $ticketNo 随噪音案件 ${case.caseNo} 关闭")
    }

    private fun issueVoucher(case: NoiseComplaintCase, owner: AppUser, minutes: Int): DelayVoucher {
        val v = voucherRepo.save(
            DelayVoucher(
                voucherNo = "TMP",
                owner = owner,
                store = case.store,
                sourceCaseNo = case.caseNo,
                bookingNo = case.complainantBookingNo,
                minutes = minutes,
                issuedAt = clock.now(),
                note = "邻座噪音投诉 ${case.caseNo} 补偿（区域 ${case.zone} 座位 ${case.complainantSeatCode}）"
            )
        )
        v.voucherNo = BizNoGenerator.voucher(v.id!!)
        timeline.record("DELAY_VOUCHER_ISSUED", case.store.id, case.complainantBookingNo, "SYSTEM",
            "延时券 ${v.voucherNo}：$minutes 分钟，绑定原订单 ${case.complainantBookingNo}")
        return v
    }

    // ---------------- 延时券核销 ----------------

    /**
     * 在券主的另一个预约上核销延时券，免费延长 [DelayVoucher.minutes] 分钟。
     * 碰撞规则与普通延时一致：下一位已在场则不可用；下一位已支付则预警并在其进场时自动换座。
     */
    @Transactional
    fun redeemVoucher(voucherNo: String, bookingNo: String): DelayVoucher {
        val voucher = voucherRepo.findByVoucherNo(voucherNo) ?: throw NotFoundException("延时券不存在")
        if (voucher.status != DelayVoucherStatus.ISSUED) {
            throw ConflictException("VOUCHER_NOT_USABLE", "延时券状态 ${voucher.status}，不可核销")
        }
        val booking = bookingRepo.findByBookingNo(bookingNo) ?: throw NotFoundException("预约 $bookingNo 不存在")
        if (booking.user.id != voucher.owner.id) {
            throw BizException("VOUCHER_OWNER_MISMATCH", "延时券仅限本人使用")
        }
        if (booking.store.id != voucher.store.id) {
            throw BizException("VOUCHER_STORE_MISMATCH", "延时券仅限发券门店使用")
        }
        if (booking.bookingNo == voucher.bookingNo) {
            throw BizException("VOUCHER_BOUND_ORDER", "延时券与原订单 $bookingNo 绑定用于溯源，请在后续预约上使用")
        }
        if (booking.type == BookingType.MONTHLY) {
            throw BizException("MONTHLY_NO_EXTEND", "包月权益无需延时券")
        }
        if (booking.status != BookingStatus.IN_PROGRESS && booking.status != BookingStatus.CONFIRMED) {
            throw ConflictException("INVALID_STATUS", "订单 ${booking.status} 不可使用延时券")
        }
        val seat = booking.seat ?: throw BizException("NO_SEAT", "订单未分配座位")
        val now = clock.now()

        val minutes = voucher.minutes.toLong()
        val currentEnd = booking.effectiveEndTime
        val newEnd = currentEnd.plus(Duration.ofMinutes(minutes))
        val blockers = bookingRepo.findActiveOnSeat(seat.id!!, currentEnd)
            .filter { it.id != booking.id && it.startTime < newEnd }
        if (blockers.any { it.status == BookingStatus.IN_PROGRESS }) {
            throw ConflictException("SEAT_STILL_IN_USE", "下一位用户已在场，延时券暂不可用，券保留")
        }
        val waiting = blockers.filter { it.status == BookingStatus.CONFIRMED }

        booking.extendedEndTime = newEnd
        booking.extended = true
        val ttl = Duration.between(now, newEnd).plusHours(2)
        seatLocks.markOccupied(seat.id!!, booking.bookingNo, ttl)

        voucher.status = DelayVoucherStatus.REDEEMED
        voucher.redeemedAt = now
        voucher.redeemedBookingNo = bookingNo

        timeline.record("VOUCHER_REDEEMED", booking.store.id, bookingNo, "USER:${booking.user.id}",
            "延时券 ${voucher.voucherNo} 核销，免费延长 $minutes 分钟至 $newEnd（来源案件 ${voucher.sourceCaseNo}）")
        waiting.forEach { next ->
            timeline.record("COLLISION_PREDICTED", booking.store.id, next.bookingNo, "SYSTEM",
                "上一订单 ${booking.bookingNo} 凭延时券延至 $newEnd，占用本订单座位 ${seat.code}，进场时自动处理换座")
        }
        return voucher
    }

    // ---------------- 区域聚合与运营治理 ----------------

    @Transactional(readOnly = true)
    fun zoneStats(storeId: Long, from: Instant, to: Instant): ZoneNoiseStats {
        if (!to.isAfter(from)) throw BizException("BAD_RANGE", "结束时间必须晚于开始时间")
        val cases = noiseCaseRepo.findByStoreIdAndReceivedAtBetween(storeId, from, to).filter { it.substantiated }
        val byZone = cases.groupBy { it.zone }

        val zones = byZone.map { (zone, zoneCases) ->
            val respondentIds = zoneCases.mapNotNull { it.respondent?.id }.distinct()
            // 每案是否为此前已有成立记录的累犯
            val repeatCases = zoneCases.count { c ->
                val respondentId = c.respondent?.id
                respondentId != null &&
                    cases.any { other ->
                        other.id != c.id &&
                            other.respondent?.id == respondentId &&
                            other.receivedAt <= c.receivedAt
                    }
            }
            val reviews = zoneReviewRepo.findByStoreIdAndZoneOrderByCreatedAtDesc(storeId, zone)
            val latest = reviews.firstOrNull()
            val afterCount = latest?.let { r ->
                noiseCaseRepo.findByStoreIdAndZoneAndReceivedAtBetween(storeId, zone, r.createdAt, to)
                    .count { it.substantiated }
            }
            val repeatRate = if (zoneCases.isEmpty()) 0.0 else
                java.math.BigDecimal(1.0 - respondentIds.size.toDouble() / zoneCases.size)
                    .setScale(4, java.math.RoundingMode.HALF_UP).toDouble()
            // 布局信号：多名不同用户涉案且累犯率低于一半
            val layoutSignal = respondentIds.size >= 2 && repeatRate < 0.5
            // 提醒无效信号：半数以上案件是“被提醒后再犯”
            val reminderSignal = zoneCases.size >= 2 && repeatCases * 2 >= zoneCases.size
            val frequent = zoneCases.size >= FREQUENT_THRESHOLD
            val diagnosis = when {
                !frequent -> ZoneNoiseDiagnosis.INSUFFICIENT
                layoutSignal && reminderSignal -> ZoneNoiseDiagnosis.MIXED
                reminderSignal -> ZoneNoiseDiagnosis.REMINDER_INEFFECTIVE
                else -> ZoneNoiseDiagnosis.SEAT_LAYOUT_ISSUE
            }
            ZoneNoiseStat(
                zone = zone,
                cases = zoneCases.size,
                distinctRespondents = respondentIds.size,
                repeatOffenderCases = repeatCases,
                warnings = zoneCases.count { it.enforcementAction == NoiseEnforcementAction.WARN },
                movedToPhoneZone = zoneCases.count { it.enforcementAction == NoiseEnforcementAction.MOVE_TO_PHONE_ZONE },
                terminated = zoneCases.count { it.enforcementAction == NoiseEnforcementAction.TERMINATE_BOOKING },
                repeatRate = repeatRate,
                layoutSignal = layoutSignal,
                reminderSignal = reminderSignal,
                frequent = frequent,
                diagnosis = diagnosis,
                latestReviewAction = latest?.action,
                latestReviewAt = latest?.createdAt,
                latestReviewDiagnosis = latest?.diagnosis,
                casesAfterLatestReview = afterCount
            )
        }.sortedWith(compareByDescending<ZoneNoiseStat> { it.cases }.thenBy { it.zone })

        return ZoneNoiseStats(
            storeId = storeId, from = from, to = to,
            frequentThreshold = FREQUENT_THRESHOLD,
            zones = zones,
            totalSubstantiatedCases = cases.size
        )
    }

    /**
     * 运营对高发区域采取治理措施并留痕（实际分区调整配合 /api/ops 座位布局接口完成）。
     * 下次 [zoneStats] 会返回措施后新增案件数，用于判断措施是否有效。
     */
    @Transactional
    fun reviewZone(
        storeId: Long, zone: String, action: ZoneReviewAction, note: String,
        from: Instant? = null, to: Instant? = null
    ): ZoneNoiseReview {
        val store = storeRepo.findById(storeId).orElseThrow { NotFoundException("门店不存在") }
        val end = to ?: clock.now()
        val start = from ?: end.minus(Duration.ofDays(30))
        val stats = zoneStats(storeId, start, end)
        val current = stats.zones.firstOrNull { it.zone == zone }
        val review = zoneReviewRepo.save(
            ZoneNoiseReview(
                reviewNo = "TMP",
                store = store,
                zone = zone, action = action,
                windowFrom = start, windowTo = end,
                caseCount = current?.cases ?: 0,
                diagnosis = current?.diagnosis ?: ZoneNoiseDiagnosis.INSUFFICIENT,
                note = note,
                createdAt = clock.now()
            )
        )
        review.reviewNo = BizNoGenerator.zoneReview(review.id!!)
        timeline.record("ZONE_NOISE_REVIEW", storeId, null, "ADMIN",
            "区域 $zone 采取 $action（窗口案件 ${current?.cases ?: 0}，诊断 ${current?.diagnosis}）：$note")
        return review
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    fun getCase(caseNo: String): NoiseComplaintCase = mustGetCase(caseNo)

    @Transactional(readOnly = true)
    fun listByStore(storeId: Long): List<NoiseComplaintCase> =
        noiseCaseRepo.findByStoreIdOrderByReceivedAtDesc(storeId)

    @Transactional(readOnly = true)
    fun vouchersOfUser(userId: Long): List<DelayVoucher> =
        voucherRepo.findByOwnerIdOrderByIssuedAtDesc(userId)

    @Transactional(readOnly = true)
    fun vouchersOfBooking(bookingNo: String): List<DelayVoucher> =
        voucherRepo.findByBookingNoOrderByIssuedAtDesc(bookingNo)

    @Transactional(readOnly = true)
    fun getVoucher(voucherNo: String): DelayVoucher =
        voucherRepo.findByVoucherNo(voucherNo) ?: throw NotFoundException("延时券不存在")

    private fun priorViolations(userId: Long): List<NoiseComplaintCase> =
        noiseCaseRepo.findByRespondentIdAndSubstantiatedTrueOrderByReceivedAtAsc(userId)

    private fun mustGetCase(caseNo: String): NoiseComplaintCase =
        noiseCaseRepo.findByCaseNo(caseNo) ?: throw NotFoundException("噪音投诉案件 $caseNo 不存在")

    private fun mustGetOpenCase(caseNo: String): NoiseComplaintCase {
        val case = mustGetCase(caseNo)
        if (case.status != NoiseCaseStatus.OPEN) {
            throw ConflictException("CASE_CLOSED", "案件已结案（${case.status}）")
        }
        return case
    }
}
