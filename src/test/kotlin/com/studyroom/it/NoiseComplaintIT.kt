package com.studyroom.it

import com.studyroom.domain.*
import com.studyroom.repo.AppUserRepository
import com.studyroom.repo.ChargeRepository
import com.studyroom.repo.ComplaintRepository
import com.studyroom.repo.ExtensionVoucherRepository
import com.studyroom.service.*
import com.studyroom.support.BizException
import com.studyroom.support.SeatLockManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 邻座噪音投诉处理全链路：
 * 受理（时间/座位/是否已提醒）→ 店员巡查确认（不立即结束订单）→
 * 警告/换电话区/终止 → 与原订单绑定的延时券 → 同区域频繁投诉的运营诊断。
 */
class NoiseComplaintIT : BaseIT() {

    @Autowired lateinit var fx: Fixtures
    @Autowired lateinit var access: AccessService
    @Autowired lateinit var noise: NoiseComplaintService
    @Autowired lateinit var noiseOps: NoiseOperationsService
    @Autowired lateinit var bookingService: BookingService
    @Autowired lateinit var complaintRepo: ComplaintRepository
    @Autowired lateinit var voucherRepo: ExtensionVoucherRepository
    @Autowired lateinit var chargeRepo: ChargeRepository
    @Autowired lateinit var userRepo: AppUserRepository
    @Autowired lateinit var redis: StringRedisTemplate

    /** 一对同分区在场用户及其订单 */
    private class PairScene(
        val offender: AppUser, val victim: AppUser,
        val offenderBooking: Booking, val victimBooking: Booking
    )

    /** 构造一对同分区在场用户：offender 坐 offenderCode、victim 坐 victimCode */
    private fun pairInZone(offenderCode: String, victimCode: String, suffix: String): PairScene {
        val store = fx.store1()
        val offender = fx.verifiedUser("噪音用户$suffix", "1376000${suffix.take(3)}")
        val victim = fx.verifiedUser("受影响用户$suffix", "13761000${suffix.take(3)}")
        val t = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val end = t.plus(Duration.ofMinutes(120))
        val ob = fx.paidTimedBooking(offender, store, fx.seat(store.id!!, offenderCode), t, end)
        val vb = fx.paidTimedBooking(victim, store, fx.seat(store.id!!, victimCode), t, end)
        access.enter(offender.id!!, store.id!!)
        access.enter(victim.id!!, store.id!!)
        return PairScene(offender, victim, ob, vb)
    }

    private fun confirm(
        victim: Booking, offender: Booking,
        willing: Boolean? = null, accepts: Boolean? = null
    ): NoiseComplaint {
        val filed = noise.file(
            FileNoiseCommand(
                complainantBookingNo = victim.bookingNo,
                offenderBookingNo = offender.bookingNo,
                description = "邻座外放网课声音",
                remindedBeforePatrol = true
            )
        )
        return noise.patrol(
            filed.noiseNo,
            NoisePatrolCommand(staffId = 900L, result = PatrolResult.NOISE_CONFIRMED,
                willingToMovePhoneZone = willing, acceptsReminder = accepts, note = "到场听到外放讲课声")
        )
    }

    @Test
    fun `warn keeps offender booking alive and voucher bound to original booking redeems free extension`() {
        val s = pairInZone("A01", "A02", "001")
        val offender = s.offenderBooking
        val victim = s.victimBooking

        // 1) 受理：自动按同分区在场订单定位被投诉人；同步生成 NOISE_NEIGHBOR 投诉单与工单
        val filed = noise.file(
            FileNoiseCommand(
                complainantBookingNo = victim.bookingNo,
                description = "邻座 A01 外放网课",
                remindedBeforePatrol = true
            )
        )
        assertThat(filed.status).isEqualTo(NoiseComplaintStatus.RECEIVED)
        assertThat(filed.staffPatrolled).isFalse()
        assertThat(filed.remindedBeforePatrol).isTrue()
        assertThat(filed.seatCode).isEqualTo("A01")
        assertThat(filed.zone).isEqualTo("静音区A")
        assertThat(filed.offenderBookingNo).isEqualTo(offender.bookingNo)

        val linkedComplaint = complaintRepo.findByComplaintNo(filed.relatedComplaintNo!!)!!
        assertThat(linkedComplaint.category).isEqualTo(ComplaintCategory.NOISE_NEIGHBOR)
        assertThat(linkedComplaint.againstBookingNo).isEqualTo(offender.bookingNo)
        assertThat(linkedComplaint.status).isEqualTo(ComplaintStatus.OPEN)

        // 2) 巡查确认：被投诉订单不得立即结束；即时发放与原订单绑定的延时券
        val confirmed = noise.patrol(
            filed.noiseNo,
            NoisePatrolCommand(
                staffId = 900L, result = PatrolResult.NOISE_CONFIRMED,
                willingToMovePhoneZone = false, acceptsReminder = true
            )
        )
        assertThat(confirmed.status).isEqualTo(NoiseComplaintStatus.CONFIRMED)
        assertThat(confirmed.priorViolationCount).isEqualTo(0)
        assertThat(confirmed.violationCountAfter).isEqualTo(1)
        // 推荐处置：不愿换电话区 → 终止（店员仍可裁量仅警告）
        assertThat(noise.recommendAction(confirmed)).isEqualTo(NoiseActionType.TERMINATE_BOOKING)

        val offenderStillInStore = fx.bookings.findByBookingNo(offender.bookingNo)!!
        assertThat(offenderStillInStore.status).isEqualTo(BookingStatus.IN_PROGRESS)

        val vouchers = voucherRepo.findBySourceBookingNo(victim.bookingNo)
        assertThat(vouchers).hasSize(1)
        val voucher = vouchers.first()
        assertThat(voucher.minutes).isEqualTo(30L)
        assertThat(voucher.status).isEqualTo(VoucherStatus.ISSUED)
        assertThat(voucher.sourceNoiseNo).isEqualTo(filed.noiseNo)

        // 3) 店员裁量警告：订单继续，关联通用投诉单随之结案
        noise.handle(filed.noiseNo, NoiseActionCommand(NoiseActionType.WARN, staffId = 900L, note = "首次警告"))
        assertThat(fx.bookings.findByBookingNo(offender.bookingNo)!!.status).isEqualTo(BookingStatus.IN_PROGRESS)
        assertThat(complaintRepo.findByComplaintNo(filed.relatedComplaintNo!!)!!.status)
            .isEqualTo(ComplaintStatus.RESOLVED)

        // 券不能用于非绑定订单（即使是同一用户的另一订单）
        val t = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val other = fx.paidTimedBooking(
            s.victim,
            fx.store1(), fx.seat(fx.store1().id!!, "A03"),
            t.plus(Duration.ofMinutes(200)), t.plus(Duration.ofMinutes(260))
        )
        assertThrows<BizException> {
            bookingService.extend(other.bookingNo, 30, voucher.voucherNo)
        }.also { assertThat(it.code).isEqualTo("VOUCHER_BOUND_TO_OTHER_BOOKING") }

        // 4) 被影响用户在原订单上用券延时：免收延时费
        val extended = bookingService.extend(victim.bookingNo, 30, voucher.voucherNo)
        assertThat(extended.extended).isTrue()
        assertThat(extended.extraChargedAmount.compareTo(java.math.BigDecimal.ZERO)).isEqualTo(0)
        assertThat(chargeRepo.findByBookingIdOrderByCreatedAtAsc(extended.id!!))
            .noneMatch { it.type == ChargeType.EXTENSION_FEE }

        val redeemed = voucherRepo.findByVoucherNo(voucher.voucherNo)!!
        assertThat(redeemed.status).isEqualTo(VoucherStatus.REDEEMED)
        assertThat(redeemed.redeemedOnBookingNo).isEqualTo(victim.bookingNo)
        // 券不可重复使用
        assertThrows<BizException> {
            bookingService.extend(victim.bookingNo, 30, voucher.voucherNo)
        }.also { assertThat(it.code).isEqualTo("VOUCHER_NOT_AVAILABLE") }
    }

    @Test
    fun `willing offender is moved to phone zone without ending booking and seat state migrates`() {
        val s = pairInZone("A01", "A02", "002")
        val offender = s.offenderBooking
        val victim = s.victimBooking
        val confirmed = confirm(victim, offender, willing = true, accepts = true)
        assertThat(noise.recommendAction(confirmed)).isEqualTo(NoiseActionType.MOVE_TO_PHONE_ZONE)

        val a01 = fx.seat(fx.store1().id!!, "A01")
        val p01 = fx.seat(fx.store1().id!!, "P01")

        val handled = noise.handle(
            confirmed.noiseNo,
            NoiseActionCommand(NoiseActionType.MOVE_TO_PHONE_ZONE, staffId = 900L, note = "引导至电话区 P01")
        )
        assertThat(handled.status).isEqualTo(NoiseComplaintStatus.HANDLED)
        assertThat(handled.movedToSeatCode).isEqualTo("P01")

        // 订单不结束，只换座位；旧座释放、电话区座位被同一订单占用
        val moved = fx.bookings.findByBookingNo(offender.bookingNo)!!
        assertThat(moved.status).isEqualTo(BookingStatus.IN_PROGRESS)
        assertThat(moved.seat!!.id).isEqualTo(p01.id)
        assertThat(moved.noiseMovedFromSeatId).isEqualTo(a01.id)
        assertThat(redis.hasKey(SeatLockManager.stateKey(a01.id!!))).isFalse()
        assertThat(redis.opsForValue().get(SeatLockManager.stateKey(p01.id!!))).isEqualTo(offender.bookingNo)

        // 被影响用户仍拿到绑定原订单的延时券
        assertThat(voucherRepo.findBySourceBookingNo(victim.bookingNo)).hasSize(1)
    }

    @Test
    fun `unwilling repeat offender gets booking terminated and repeated violations accumulate`() {
        val store = fx.store1()
        val offender = fx.verifiedUser("累犯噪音用户", "13760000003")
        val victim1 = fx.verifiedUser("受害人甲", "13761000003")
        val t = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val end = t.plus(Duration.ofMinutes(120))

        // 第一次违规：拒绝换区也不接受提醒 → 终止
        val b1 = fx.paidTimedBooking(offender, store, fx.seat(store.id!!, "A01"), t, end)
        val v1 = fx.paidTimedBooking(victim1, store, fx.seat(store.id!!, "A02"), t, end)
        access.enter(offender.id!!, store.id!!)
        access.enter(victim1.id!!, store.id!!)
        val c1 = confirm(v1, b1, willing = false, accepts = false)
        assertThat(noise.recommendAction(c1)).isEqualTo(NoiseActionType.TERMINATE_BOOKING)
        noise.handle(c1.noiseNo, NoiseActionCommand(NoiseActionType.TERMINATE_BOOKING, staffId = 900L))

        val terminated = fx.bookings.findByBookingNo(b1.bookingNo)!!
        assertThat(terminated.status).isEqualTo(BookingStatus.TERMINATED)
        // 违规终止：押金余额退回，剩余使用费不退（仅 1 笔押金退款）
        assertThat(terminated.refundedAmount.compareTo(terminated.deposit)).isEqualTo(0)
        assertThat(terminated.refundedAmount.compareTo(terminated.paidAmount)).isLessThan(0)
        assertThat(redis.hasKey(SeatLockManager.stateKey(fx.seat(store.id!!, "A01").id!!))).isFalse()

        // 第二次违规（A01 已释放；新受害人坐 A01，累犯坐 A03，显式指定被投诉订单）
        val victim2 = fx.verifiedUser("受害人乙", "13761000004")
        val b2 = fx.paidTimedBooking(offender, store, fx.seat(store.id!!, "A03"), t, end)
        val v2 = fx.paidTimedBooking(victim2, store, fx.seat(store.id!!, "A01"), t, end)
        access.enter(victim2.id!!, store.id!!)
        access.enter(offender.id!!, store.id!!)
        val filed2 = noise.file(
            FileNoiseCommand(v2.bookingNo, b2.bookingNo, "又是外放网课", remindedBeforePatrol = false)
        )
        val c2 = noise.patrol(
            filed2.noiseNo,
            NoisePatrolCommand(900L, PatrolResult.NOISE_CONFIRMED, willingToMovePhoneZone = false, acceptsReminder = false)
        )
        assertThat(c2.priorViolationCount).isEqualTo(1)
        assertThat(c2.violationCountAfter).isEqualTo(2)
        noise.handle(c2.noiseNo, NoiseActionCommand(NoiseActionType.TERMINATE_BOOKING, staffId = 900L))
        assertThat(fx.bookings.findByBookingNo(b2.bookingNo)!!.status).isEqualTo(BookingStatus.TERMINATED)
        assertThat(userRepo.findById(offender.id!!).get().noiseViolationCount).isEqualTo(2)
    }

    @Test
    fun `patrol finding no noise rejects complaint without voucher or violation and cannot be handled`() {
        val s = pairInZone("A01", "A02", "004")
        val offender = s.offenderBooking
        val victim = s.victimBooking
        val filed = noise.file(
            FileNoiseCommand(victim.bookingNo, offender.bookingNo, "怀疑邻座外放", remindedBeforePatrol = false)
        )
        val result = noise.patrol(
            filed.noiseNo,
            NoisePatrolCommand(900L, PatrolResult.NOISE_NOT_FOUND, note = "到场安静，未发现外放")
        )
        assertThat(result.status).isEqualTo(NoiseComplaintStatus.UNFOUNDED)
        assertThat(voucherRepo.findBySourceBookingNo(victim.bookingNo)).isEmpty()
        val offenderUser = userRepo.findById(s.offender.id!!).get()
        assertThat(offenderUser.noiseViolationCount).isEqualTo(0)
        assertThat(complaintRepo.findByComplaintNo(filed.relatedComplaintNo!!)!!.status)
            .isEqualTo(ComplaintStatus.REJECTED)

        assertThrows<BizException> {
            noise.handle(filed.noiseNo, NoiseActionCommand(NoiseActionType.WARN))
        }.also { assertThat(it.code).isEqualTo("INVALID_STATUS") }
    }

    @Test
    fun `unused bound voucher expires when affected booking is checked out`() {
        val s = pairInZone("A01", "A02", "005")
        val confirmed = confirm(s.victimBooking, s.offenderBooking, willing = true, accepts = true)
        noise.handle(confirmed.noiseNo, NoiseActionCommand(NoiseActionType.WARN, staffId = 900L))

        val voucher = voucherRepo.findBySourceBookingNo(s.victimBooking.bookingNo).single()
        assertThat(voucher.status).isEqualTo(VoucherStatus.ISSUED)

        // 被影响用户直接离场，未使用券 → 计时订单结束，券自动失效
        access.leave(s.victim.id!!, fx.store1().id!!)
        assertThat(voucherRepo.findByVoucherNo(voucher.voucherNo)!!.status).isEqualTo(VoucherStatus.EXPIRED)
    }

    @Test
    fun `repeated multi-seat complaints diagnose layout issue then reminder ineffective after reinforcement`() {
        val store = fx.store1()
        val t = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val from = t.minus(Duration.ofMinutes(60))
        val to = t.plus(Duration.ofHours(4))

        // 场景一：未做任何干预，2 名不同用户在 A01/A03 先后被投诉成立 → 疑似布局问题
        run {
            val p1 = pairInZone("A01", "A02", "101")
            val n1 = confirm(p1.victimBooking, p1.offenderBooking, willing = true, accepts = true)
            noise.handle(n1.noiseNo, NoiseActionCommand(NoiseActionType.WARN, staffId = 900L))
            access.leave(p1.offender.id!!, store.id!!)
            access.leave(p1.victim.id!!, store.id!!)

            val p2 = pairInZone("A03", "A01", "102")
            val n2 = confirm(p2.victimBooking, p2.offenderBooking, willing = true, accepts = true)
            noise.handle(n2.noiseNo, NoiseActionCommand(NoiseActionType.WARN, staffId = 900L))

            val stats = noiseOps.zoneStats(store.id!!, from, to)
            val zoneA = stats.single { it.zone == "静音区A" }
            assertThat(zoneA.confirmedComplaints).isEqualTo(2)
            assertThat(zoneA.distinctOffenders).isEqualTo(2)
            assertThat(zoneA.complaintsBySeat).containsEntry("A01", 1).containsEntry("A03", 1)
            assertThat(zoneA.diagnosis).isEqualTo("LAYOUT_SUSPECTED")
            assertThat(zoneA.recommendedIntervention).isEqualTo("REINFORCE_SILENCE_NOTICE")
        }
    }

    @Test
    fun `complaints continuing after silence notice diagnose reminder ineffective and lead to rezone`() {
        val store = fx.store1()
        val t = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val from = t.minus(Duration.ofMinutes(60))
        val to = t.plus(Duration.ofHours(4))

        // 先加强静音提示
        noiseOps.reinforceSilenceNotice(store.id!!, "静音区A", "张贴提示牌并增加巡视频次", adminId = 1L)

        // 提示之后仍有 2 名不同用户继续违规
        val p1 = pairInZone("A01", "A02", "201")
        val n1 = confirm(p1.victimBooking, p1.offenderBooking, willing = false, accepts = false)
        noise.handle(n1.noiseNo, NoiseActionCommand(NoiseActionType.WARN, staffId = 900L))
        access.leave(p1.offender.id!!, store.id!!)
        access.leave(p1.victim.id!!, store.id!!)

        val p2 = pairInZone("A03", "A01", "202")
        val n2 = confirm(p2.victimBooking, p2.offenderBooking, willing = false, accepts = false)
        noise.handle(n2.noiseNo, NoiseActionCommand(NoiseActionType.WARN, staffId = 900L))

        val stats = noiseOps.zoneStats(store.id!!, from, to)
        val zoneA = stats.single { it.zone == "静音区A" }
        assertThat(zoneA.diagnosis).isEqualTo("REMINDER_INEFFECTIVE")
        assertThat(zoneA.interventions).anyMatch { it.type == ZoneInterventionType.REINFORCE_SILENCE_NOTICE }
        assertThat(zoneA.recommendedIntervention).isEqualTo("REZONE_LAYOUT")

        // 运营据此重划分区（A03 改电话区），干预全程留痕
        val rezoned = noiseOps.rezoneSeat(store.id!!, fx.seat(store.id!!, "A03").id!!, "电话区", "改造为可外放区", 1L)
        assertThat(rezoned.zone).isEqualTo("电话区")
        val after = noiseOps.zoneStats(store.id!!, from, to).single { it.zone == "静音区A" }
        assertThat(after.interventions.map { it.type })
            .contains(ZoneInterventionType.REINFORCE_SILENCE_NOTICE, ZoneInterventionType.REZONE_LAYOUT)
    }
}
