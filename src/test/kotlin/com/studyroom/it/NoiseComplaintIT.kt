package com.studyroom.it

import com.studyroom.domain.*
import com.studyroom.service.AccessService
import com.studyroom.service.NoiseComplaintService
import com.studyroom.support.BizException
import com.studyroom.support.SeatLockManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 邻座噪音投诉处理全链路：
 * 受理（不立即结束订单）→ 店员巡查 → 决策树（警告/换电话区/终止）→
 * 延时券与原订单绑定及核销 → 区域聚合区分布局问题与提醒无效。
 */
class NoiseComplaintIT : BaseIT() {

    @Autowired lateinit var fx: Fixtures
    @Autowired lateinit var access: AccessService
    @Autowired lateinit var noise: NoiseComplaintService
    @Autowired lateinit var redis: StringRedisTemplate

    private val staffId = 9001L

    private fun base(): Instant = Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.SECONDS)

    /** 构造一场完整的邻座噪音纠纷并完成巡查/处置，结束后双方离场（被终止者除外）。 */
    private fun noiseRound(
        complainant: AppUser, respondent: AppUser,
        complainantSeatCode: String, respondentSeatCode: String,
        start: Instant,
        willingToMove: Boolean, acceptWarning: Boolean,
        durationMin: Long = 120
    ): NoiseComplaintCase {
        val store = fx.store1()
        val storeId = store.id!!
        val end = start.plus(Duration.ofMinutes(durationMin))
        val complainantSeat = fx.seat(storeId, complainantSeatCode)
        val respondentSeat = fx.seat(storeId, respondentSeatCode)
        val complainantBooking = fx.paidTimedBooking(complainant, store, complainantSeat, start, end)
        val respondentBooking = fx.paidTimedBooking(respondent, store, respondentSeat, start, end)

        clock.setOverride(start)
        access.enter(complainant.id!!, storeId)
        access.enter(respondent.id!!, storeId)

        val received = noise.receive(
            complainantBookingNo = complainantBooking.bookingNo,
            description = "邻座外放网课声音很大",
            alreadyReminded = true,
            respondentBookingNo = respondentBooking.bookingNo
        )
        clock.setOverride(start.plusSeconds(60))
        noise.patrol(received.caseNo, NoisePatrolResult.SUBSTANTIATED, "现场确认外放网课", staffId)
        val confirmed = noise.enforce(
            received.caseNo,
            willingToMovePhoneZone = willingToMove,
            acceptStaffWarning = acceptWarning,
            staffUserId = staffId
        )
        clock.setOverride(start.plusSeconds(120))
        access.leave(complainant.id!!, storeId)
        if (fx.bookings.findByBookingNo(respondentBooking.bookingNo)!!.status == BookingStatus.IN_PROGRESS) {
            access.leave(respondent.id!!, storeId)
        }
        return confirmed
    }

    @Test
    fun `warning keeps respondent booking alive and voucher is bound to original booking`() {
        val store = fx.store1()
        val storeId = store.id!!
        val t = base()
        val complainant = fx.verifiedUser("噪音受害者甲", "13760000001")
        val respondent = fx.verifiedUser("外放网课者甲", "13760000002")
        val end = t.plus(Duration.ofMinutes(120))
        val complainantSeat = fx.seat(storeId, "A01")
        val respondentSeat = fx.seat(storeId, "A02")
        val cb = fx.paidTimedBooking(complainant, store, complainantSeat, t, end)
        val rb = fx.paidTimedBooking(respondent, store, respondentSeat, t, end)

        clock.setOverride(t)
        access.enter(complainant.id!!, storeId)
        access.enter(respondent.id!!, storeId)

        // 受理：同区域只有一名其他在场用户，被诉订单自动定位；订单不立即结束
        val received = noise.receive(cb.bookingNo, "邻座外放网课，已自行提醒无效", alreadyReminded = true)
        assertThat(received.status).isEqualTo(NoiseCaseStatus.OPEN)
        assertThat(received.respondentBookingNo).isEqualTo(rb.bookingNo)
        assertThat(received.alreadyReminded).isTrue()
        assertThat(fx.bookings.findByBookingNo(rb.bookingNo)!!.status).isEqualTo(BookingStatus.IN_PROGRESS)

        // 巡查确认 → 不愿换座但接受提醒 → 警告，订单继续
        noise.patrol(received.caseNo, NoisePatrolResult.SUBSTANTIATED, "确认外放", staffId)
        val confirmed = noise.enforce(received.caseNo, willingToMovePhoneZone = false, acceptStaffWarning = true, staffUserId = staffId)
        assertThat(confirmed.status).isEqualTo(NoiseCaseStatus.CONFIRMED)
        assertThat(confirmed.substantiated).isTrue()
        assertThat(confirmed.enforcementAction).isEqualTo(NoiseEnforcementAction.WARN)
        val respondentAfter = fx.bookings.findByBookingNo(rb.bookingNo)!!
        assertThat(respondentAfter.status).isEqualTo(BookingStatus.IN_PROGRESS)
        assertThat(respondentAfter.seat!!.id).isEqualTo(respondentSeat.id)

        // 延时券与被影响用户的原订单绑定
        val voucher = noise.getVoucher(confirmed.voucherNo!!)
        assertThat(voucher.owner.id).isEqualTo(complainant.id)
        assertThat(voucher.bookingNo).isEqualTo(cb.bookingNo)
        assertThat(voucher.minutes).isEqualTo(30)
        assertThat(voucher.status).isEqualTo(DelayVoucherStatus.ISSUED)

        // 证据包附带噪音案件，工单归因为用户行为并随案件结案
        // （通过服务层 evidence 间接验证：关联工单可查且案件互链）
        assertThat(noise.listByStore(storeId).map { it.caseNo }).contains(received.caseNo)

        // 券不能在原订单上核销
        val bindError = org.junit.jupiter.api.assertThrows<BizException> {
            noise.redeemVoucher(voucher.voucherNo, cb.bookingNo)
        }
        assertThat(bindError.code).isEqualTo("VOUCHER_BOUND_ORDER")

        // 原纠纷双方离场
        clock.setOverride(t.plus(Duration.ofMinutes(90)))
        access.leave(complainant.id!!, storeId)
        access.leave(respondent.id!!, storeId)

        // 券只能本人使用
        val stranger = fx.verifiedUser("路人", "13760000003")
        val strangerBooking = fx.paidTimedBooking(
            stranger, store, fx.seat(storeId, "A03"),
            t.plus(Duration.ofMinutes(180)), t.plus(Duration.ofMinutes(300))
        )
        val ownerError = org.junit.jupiter.api.assertThrows<BizException> {
            noise.redeemVoucher(voucher.voucherNo, strangerBooking.bookingNo)
        }
        assertThat(ownerError.code).isEqualTo("VOUCHER_OWNER_MISMATCH")

        // 受害者后续预约上核销：免费延长 30 分钟，不计延时费
        val laterBooking = fx.paidTimedBooking(
            complainant, store, complainantSeat,
            t.plus(Duration.ofMinutes(180)), t.plus(Duration.ofMinutes(300))
        )
        val redeemed = noise.redeemVoucher(voucher.voucherNo, laterBooking.bookingNo)
        assertThat(redeemed.status).isEqualTo(DelayVoucherStatus.REDEEMED)
        assertThat(redeemed.redeemedBookingNo).isEqualTo(laterBooking.bookingNo)
        val laterAfter = fx.bookings.findByBookingNo(laterBooking.bookingNo)!!
        assertThat(laterAfter.extended).isTrue()
        assertThat(laterAfter.extendedEndTime).isEqualTo(t.plus(Duration.ofMinutes(330)))
        assertThat(laterAfter.extraChargedAmount.compareTo(java.math.BigDecimal.ZERO)).isEqualTo(0)

        // 券不可重复核销
        val again = org.junit.jupiter.api.assertThrows<BizException> {
            noise.redeemVoucher(voucher.voucherNo, laterBooking.bookingNo)
        }
        assertThat(again.code).isEqualTo("VOUCHER_NOT_USABLE")
    }

    @Test
    fun `willing respondent is moved to phone zone and old seat is released`() {
        val store = fx.store1()
        val storeId = store.id!!
        val t = base()
        val complainant = fx.verifiedUser("受害者乙", "13760000004")
        val respondent = fx.verifiedUser("配合换座者", "13760000005")
        val end = t.plus(Duration.ofMinutes(120))
        val a01 = fx.seat(storeId, "A01")
        val a02 = fx.seat(storeId, "A02")
        val p01 = fx.seat(storeId, "P01")
        val cb = fx.paidTimedBooking(complainant, store, a01, t, end)
        val rb = fx.paidTimedBooking(respondent, store, a02, t, end)

        clock.setOverride(t)
        access.enter(complainant.id!!, storeId)
        access.enter(respondent.id!!, storeId)

        val received = noise.receive(cb.bookingNo, "外放网课", alreadyReminded = false, respondentBookingNo = rb.bookingNo)
        noise.patrol(received.caseNo, NoisePatrolResult.SUBSTANTIATED, null, staffId)
        val confirmed = noise.enforce(received.caseNo, willingToMovePhoneZone = true, acceptStaffWarning = false, staffUserId = staffId)

        assertThat(confirmed.enforcementAction).isEqualTo(NoiseEnforcementAction.MOVE_TO_PHONE_ZONE)
        assertThat(confirmed.targetSeatCode).isEqualTo("P01")
        val moved = fx.bookings.findByBookingNo(rb.bookingNo)!!
        assertThat(moved.status).isEqualTo(BookingStatus.IN_PROGRESS) // 订单保留
        assertThat(moved.seat!!.id).isEqualTo(p01.id)
        // 原座位 A02 已释放，电话区 P01 由该订单占用
        assertThat(redis.hasKey(SeatLockManager.stateKey(a02.id!!))).isFalse()
        assertThat(redis.opsForValue().get(SeatLockManager.stateKey(p01.id!!))).isEqualTo(rb.bookingNo)
        // 被影响用户仍获得绑定原订单的延时券
        assertThat(noise.getVoucher(confirmed.voucherNo!!).bookingNo).isEqualTo(cb.bookingNo)
    }

    @Test
    fun `repeat offender who refuses warning gets booking terminated after decision checks`() {
        val t = base()
        val store = fx.store1()
        val storeId = store.id!!
        val repeatRespondent = fx.verifiedUser("屡教不改者", "13760000006")

        // 第一次违规：拒绝换座也不接受提醒，但属首违 → 书面警告，订单继续
        val c1 = noiseRound(
            fx.verifiedUser("受害者丙1", "13760000007"), repeatRespondent,
            "A01", "A02", t,
            willingToMove = false, acceptWarning = false
        )
        assertThat(c1.enforcementAction).isEqualTo(NoiseEnforcementAction.WARN)
        assertThat(c1.priorViolationCount).isEqualTo(0)

        // 第二次：同一违规用户、新一场纠纷
        val second = noiseRound(
            fx.verifiedUser("受害者丙2", "13760000008"), repeatRespondent,
            "A01", "A02", t.plus(Duration.ofMinutes(200)),
            willingToMove = false, acceptWarning = false
        )
        assertThat(second.priorViolationCount).isEqualTo(1)
        assertThat(second.enforcementAction).isEqualTo(NoiseEnforcementAction.TERMINATE_BOOKING)
        val terminatedBookingNo = second.respondentBookingNo!!
        val terminated = fx.bookings.findByBookingNo(terminatedBookingNo)!!
        assertThat(terminated.status).isEqualTo(BookingStatus.COMPLETED)
        assertThat(terminated.actualCheckOut).isNotNull()
        // 用户责任终止：剩余使用费不退，押金余额正常退回
        assertThat(terminated.refundedAmount).isGreaterThanOrEqualTo(terminated.deposit)
        assertThat(terminated.refundedAmount).isLessThan(terminated.paidAmount)
        // 座位已释放
        val a02 = fx.seat(storeId, "A02")
        assertThat(redis.hasKey(SeatLockManager.stateKey(a02.id!!))).isFalse()
        // 被影响用户仍获得延时券
        assertThat(second.voucherNo).isNotBlank
    }

    @Test
    fun `offender left before patrol substantiates with voucher but no enforcement`() {
        val store = fx.store1()
        val storeId = store.id!!
        val t = base()
        val complainant = fx.verifiedUser("受害者丁", "13760000009")
        val respondent = fx.verifiedUser("提前溜掉者", "13760000010")
        val end = t.plus(Duration.ofMinutes(120))
        val cb = fx.paidTimedBooking(complainant, store, fx.seat(storeId, "A01"), t, end)
        val rb = fx.paidTimedBooking(respondent, store, fx.seat(storeId, "A02"), t, end)
        clock.setOverride(t)
        access.enter(complainant.id!!, storeId)
        access.enter(respondent.id!!, storeId)

        val received = noise.receive(cb.bookingNo, "外放网课", alreadyReminded = true, respondentBookingNo = rb.bookingNo)
        // 店员到达前违规者自行离场
        clock.setOverride(t.plusSeconds(30))
        access.leave(respondent.id!!, storeId)
        val patrolled = noise.patrol(received.caseNo, NoisePatrolResult.OFFENDER_LEFT, "人已离开", staffId)

        assertThat(patrolled.status).isEqualTo(NoiseCaseStatus.CONFIRMED)
        assertThat(patrolled.substantiated).isTrue()
        assertThat(patrolled.enforcementAction).isNull()
        assertThat(patrolled.voucherNo).isNotBlank
        // 案件已闭环，不能再处置
        val closed = org.junit.jupiter.api.assertThrows<BizException> {
            noise.enforce(received.caseNo, willingToMovePhoneZone = false, acceptStaffWarning = true)
        }
        assertThat(closed.code).isEqualTo("CASE_CLOSED")
    }

    @Test
    fun `unfounded patrol rejects case and issues no voucher`() {
        val store = fx.store1()
        val storeId = store.id!!
        val t = base()
        val complainant = fx.verifiedUser("多疑用户", "13760000011")
        val respondent = fx.verifiedUser("被冤枉者", "13760000012")
        val end = t.plus(Duration.ofMinutes(120))
        val cb = fx.paidTimedBooking(complainant, store, fx.seat(storeId, "A01"), t, end)
        val rb = fx.paidTimedBooking(respondent, store, fx.seat(storeId, "A02"), t, end)
        clock.setOverride(t)
        access.enter(complainant.id!!, storeId)
        access.enter(respondent.id!!, storeId)

        val received = noise.receive(cb.bookingNo, "疑似噪音", alreadyReminded = false, respondentBookingNo = rb.bookingNo)
        val rejected = noise.patrol(received.caseNo, NoisePatrolResult.UNFOUNDED, "到场一切正常", staffId)
        assertThat(rejected.status).isEqualTo(NoiseCaseStatus.REJECTED)
        assertThat(rejected.substantiated).isFalse()
        assertThat(rejected.voucherNo).isNull()
        // 被诉订单不受任何影响
        assertThat(fx.bookings.findByBookingNo(rb.bookingNo)!!.status).isEqualTo(BookingStatus.IN_PROGRESS)
    }

    @Test
    fun `complaint requires in-progress booking and ambiguous respondent must be specified`() {
        val store = fx.store1()
        val storeId = store.id!!
        val t = base()
        val end = t.plus(Duration.ofMinutes(120))
        val user = fx.verifiedUser("未进场用户", "13760000013")
        val booking = fx.paidTimedBooking(user, store, fx.seat(storeId, "B01"), t, end) // 只支付未进场
        val err = org.junit.jupiter.api.assertThrows<BizException> {
            noise.receive(booking.bookingNo, "噪音", alreadyReminded = false)
        }
        assertThat(err.code).isEqualTo("NOT_IN_STORE")

        // 同区域两名其他在场用户：必须显式指定被诉订单
        val complainant = fx.verifiedUser("受害者戊", "13760000014")
        val r1 = fx.verifiedUser("邻座1", "13760000015")
        val r2 = fx.verifiedUser("邻座2", "13760000016")
        val cb = fx.paidTimedBooking(complainant, store, fx.seat(storeId, "A01"), t, end)
        fx.paidTimedBooking(r1, store, fx.seat(storeId, "A02"), t, end)
        fx.paidTimedBooking(r2, store, fx.seat(storeId, "A03"), t, end)
        clock.setOverride(t)
        access.enter(complainant.id!!, storeId)
        access.enter(r1.id!!, storeId)
        access.enter(r2.id!!, storeId)
        val ambiguous = org.junit.jupiter.api.assertThrows<BizException> {
            noise.receive(cb.bookingNo, "不知道是谁在放", alreadyReminded = false)
        }
        assertThat(ambiguous.code).isEqualTo("RESPONDENT_AMBIGUOUS")
    }

    @Test
    fun `zone stats diagnose seat layout issue and track effectiveness of operations review`() {
        val t = base()
        val storeId = fx.store1().id!!

        // 3 名彼此不同的违规用户，各案发 1 次：累犯率 0 → 布局问题
        val c1 = noiseRound(fx.verifiedUser("受害者己1", "13760000020"), fx.verifiedUser("违规者己1", "13760000021"),
            "A01", "A02", t, willingToMove = false, acceptWarning = true)
        val c2 = noiseRound(fx.verifiedUser("受害者己2", "13760000022"), fx.verifiedUser("违规者己2", "13760000023"),
            "A01", "A02", t.plus(Duration.ofMinutes(200)), willingToMove = false, acceptWarning = true)
        val c3 = noiseRound(fx.verifiedUser("受害者己3", "13760000024"), fx.verifiedUser("违规者己3", "13760000025"),
            "A01", "A02", t.plus(Duration.ofMinutes(400)), willingToMove = false, acceptWarning = true)
        listOf(c1, c2, c3).forEach { assertThat(it.zone).isEqualTo("静音区A") }

        val windowFrom = t.minus(Duration.ofMinutes(60))
        val windowTo = t.plus(Duration.ofMinutes(800))
        var stats = noise.zoneStats(storeId, windowFrom, windowTo)
        val zoneA = stats.zones.first { it.zone == "静音区A" }
        assertThat(zoneA.frequent).isTrue()
        assertThat(zoneA.cases).isEqualTo(3)
        assertThat(zoneA.distinctRespondents).isEqualTo(3)
        assertThat(zoneA.repeatRate).isEqualTo(0.0)
        assertThat(zoneA.layoutSignal).isTrue()
        assertThat(zoneA.reminderSignal).isFalse()
        assertThat(zoneA.diagnosis).isEqualTo(ZoneNoiseDiagnosis.SEAT_LAYOUT_ISSUE)

        // 运营采取布局调整并留痕
        clock.setOverride(t.plus(Duration.ofMinutes(600)))
        val review = noise.reviewZone(
            storeId, "静音区A", ZoneReviewAction.LAYOUT_ADJUST,
            "A01/A02 间加装隔音隔断，A02 侧增设缓冲带",
            windowFrom, windowTo
        )
        assertThat(review.diagnosis).isEqualTo(ZoneNoiseDiagnosis.SEAT_LAYOUT_ISSUE)

        // 措施之后又发生 1 起 → 统计能看到措施后新增案件数（供判断措施是否有效）
        val c4 = noiseRound(fx.verifiedUser("受害者己4", "13760000026"), fx.verifiedUser("违规者己4", "13760000027"),
            "A01", "A02", t.plus(Duration.ofMinutes(660)), willingToMove = false, acceptWarning = true)
        assertThat(c4.substantiated).isTrue()
        stats = noise.zoneStats(storeId, windowFrom, windowTo)
        val zoneAAfter = stats.zones.first { it.zone == "静音区A" }
        assertThat(zoneAAfter.cases).isEqualTo(4)
        assertThat(zoneAAfter.latestReviewAction).isEqualTo(ZoneReviewAction.LAYOUT_ADJUST)
        assertThat(zoneAAfter.casesAfterLatestReview).isEqualTo(1)
    }

    @Test
    fun `zone stats diagnose reminder ineffective when same offender repeatedly warned`() {
        val t = base()
        val storeId = fx.store1().id!!
        val repeatUser = fx.verifiedUser("反复外放者", "13760000030")

        // 同一违规用户被警告 3 次（每次都“接受提醒”但再犯）
        noiseRound(fx.verifiedUser("受害者庚1", "13760000031"), repeatUser,
            "A01", "A02", t, willingToMove = false, acceptWarning = true)
        noiseRound(fx.verifiedUser("受害者庚2", "13760000032"), repeatUser,
            "A01", "A02", t.plus(Duration.ofMinutes(200)), willingToMove = false, acceptWarning = true)
        noiseRound(fx.verifiedUser("受害者庚3", "13760000033"), repeatUser,
            "A01", "A02", t.plus(Duration.ofMinutes(400)), willingToMove = false, acceptWarning = true)

        val stats = noise.zoneStats(storeId, t.minusSeconds(60), t.plus(Duration.ofMinutes(800)))
        val zoneA = stats.zones.first { it.zone == "静音区A" }
        assertThat(zoneA.cases).isEqualTo(3)
        assertThat(zoneA.distinctRespondents).isEqualTo(1)
        assertThat(zoneA.repeatOffenderCases).isEqualTo(2)
        assertThat(zoneA.reminderSignal).isTrue()
        assertThat(zoneA.layoutSignal).isFalse()
        assertThat(zoneA.diagnosis).isEqualTo(ZoneNoiseDiagnosis.REMINDER_INEFFECTIVE)
        assertThat(zoneA.warnings).isEqualTo(3)
    }
}
