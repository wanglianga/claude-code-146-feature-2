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

/** 受理邻座噪音投诉的入参 */
data class FileNoiseCommand(
    /** 被影响用户（投诉人）的在场订单号 */
    val complainantBookingNo: String,
    /** 被投诉的外放噪音订单号；不传则按同分区在场订单自动定位 */
    val offenderBookingNo: String? = null,
    val description: String,
    /** 投诉前用户是否已经提醒过邻座（受理时登记） */
    val remindedBeforePatrol: Boolean = false
)

/** 店员巡查确认入参 */
data class NoisePatrolCommand(
    val staffId: Long? = null,
    val result: PatrolResult,
    /** 被投诉人是否愿意换到电话区 */
    val willingToMovePhoneZone: Boolean? = null,
    /** 是否接受店员提醒（停止外放、保持安静） */
    val acceptsReminder: Boolean? = null,
    val note: String? = null
)

/** 店员处置入参 */
data class NoiseActionCommand(
    val action: NoiseActionType,
    val staffId: Long? = null,
    val note: String? = null,
    /** MOVE_TO_PHONE_ZONE 时可指定电话区座位；不传自动分配 */
    val targetSeatId: Long? = null
)

/**
 * 邻座噪音投诉处理工作流：
 *
 * 受理（记录投诉时间/座位号/是否已提醒）→ 店员巡查确认 →
 * 按“是否愿意换电话区 / 是否接受提醒 / 是否多次违规”选择 警告 / 换电话区 / 终止预约，
 * 成立即给被影响用户发放与原订单绑定的延时券。
 *
 * 关键规则：**巡查确认只判定违规成立，不立即结束被投诉订单**；
 * 订单结束（TERMINATED）仅发生在店员显式选择终止处置时。
 */
@Service
class NoiseComplaintService(
    private val noiseRepo: NoiseComplaintRepository,
    private val voucherRepo: ExtensionVoucherRepository,
    private val bookingRepo: BookingRepository,
    private val seatRepo: SeatRepository,
    private val sensorRepo: SeatSensorRepository,
    private val complaintService: ComplaintService,
    private val seatLocks: SeatLockManager,
    private val billing: BillingService,
    private val timeline: TimelineService,
    private val clock: TimeProvider
) {
    companion object {
        /** 噪音投诉成立后补偿延时券的默认面额（分钟） */
        const val VOUCHER_MINUTES = 30L
        /** 同分区投诉频繁判定阈值（运营诊断用） */
        const val ZONE_FREQUENT_THRESHOLD = 2
    }

    @Transactional
    fun file(cmd: FileNoiseCommand): NoiseComplaint {
        val complainantBooking = bookingRepo.findByBookingNo(cmd.complainantBookingNo)
            ?: throw NotFoundException("投诉人预约不存在")
        if (complainantBooking.status != BookingStatus.IN_PROGRESS) {
            throw ConflictException("NOT_IN_STORE", "仅在场订单可投诉邻座噪音（订单 ${cmd.complainantBookingNo}）")
        }
        val store = complainantBooking.store
        val complainantSeat = complainantBooking.seat ?: throw BizException("NO_SEAT", "投诉人订单未分配座位")

        val offenderBooking = resolveOffender(store, complainantBooking, cmd.offenderBookingNo)
        val offenderSeat = offenderBooking.seat!!

        // 同步生成通用投诉单（NOISE_NEIGHBOR）与客服工单，保持“投诉—工单”一一对应
        val complaint = complaintService.file(
            bookingNo = complainantBooking.bookingNo,
            category = ComplaintCategory.NOISE_NEIGHBOR,
            description = cmd.description,
            againstBookingNo = offenderBooking.bookingNo
        )

        val noise = noiseRepo.save(
            NoiseComplaint(
                noiseNo = "TMP",
                store = store,
                complainant = complainantBooking.user,
                complainantBookingNo = complainantBooking.bookingNo,
                offenderBookingNo = offenderBooking.bookingNo,
                offender = offenderBooking.user,
                seatCode = offenderSeat.code,
                zone = offenderSeat.zone,
                receivedAt = clock.now(),
                staffPatrolled = false,
                remindedBeforePatrol = cmd.remindedBeforePatrol,
                description = cmd.description,
                relatedComplaintNo = complaint.complaintNo,
                relatedTicketNo = complaint.relatedTicketNo
            )
        )
        noise.noiseNo = BizNoGenerator.noise(noise.id!!)

        timeline.record(
            "NOISE_COMPLAINT_RECEIVED", store.id, complainantBooking.bookingNo, "USER:${complainantBooking.user.id}",
            "${noise.noiseNo} 投诉邻座 ${offenderSeat.code}（${offenderSeat.zone}）订单 ${offenderBooking.bookingNo} 外放噪音；" +
                "投诉前已提醒=${cmd.remindedBeforePatrol}；投诉单 ${complaint.complaintNo}，等待店员巡查"
        )
        return noise
    }

    /** 定位被投诉订单：显式指定优先，否则在同分区在场订单中自动挑选邻座 */
    private fun resolveOffender(store: Store, complainantBooking: Booking, explicitNo: String?): Booking {
        if (explicitNo != null) {
            val b = bookingRepo.findByBookingNo(explicitNo) ?: throw NotFoundException("被投诉预约不存在")
            if (b.store.id != store.id) throw BizException("STORE_MISMATCH", "被投诉订单不属于该门店")
            if (b.status != BookingStatus.IN_PROGRESS) throw ConflictException("OFFENDER_NOT_IN_STORE", "被投诉订单当前不在场")
            if (b.user.id == complainantBooking.user.id) throw BizException("SELF_COMPLAINT", "不能投诉自己的订单")
            if (b.seat == null) throw BizException("NO_SEAT", "被投诉订单未分配座位")
            return b
        }
        val complainantSeatId = complainantBooking.seat?.id
        val candidates = bookingRepo.findAll().filter {
            it.store.id == store.id &&
                it.status == BookingStatus.IN_PROGRESS &&
                it.seat != null &&
                it.user.id != complainantBooking.user.id &&
                it.seat!!.id != complainantSeatId &&
                it.seat!!.zone == complainantBooking.seat!!.zone
        }.sortedBy { it.seat!!.code }
        return candidates.firstOrNull()
            ?: throw NotFoundException("未在同分区（${complainantBooking.seat!!.zone}）发现在场邻座订单，请显式传入被投诉订单号")
    }

    /**
     * 店员巡查确认。
     * - NOISE_CONFIRMED：违规成立，累计违规次数，记录换区意愿/提醒接受情况，**立即发放与原订单绑定的延时券**，
     *   但被投诉订单保持 IN_PROGRESS，等待店员处置；
     * - NOISE_NOT_FOUND：投诉不成立，关闭投诉单，不记违规、不发券。
     */
    @Transactional
    fun patrol(noiseNo: String, cmd: NoisePatrolCommand): NoiseComplaint {
        val n = mustGet(noiseNo)
        if (n.status != NoiseComplaintStatus.RECEIVED) {
            throw ConflictException("INVALID_STATUS", "投诉单 ${n.status}，不可重复巡查")
        }
        n.staffPatrolled = true
        n.patrolAt = clock.now()
        n.patrolStaffId = cmd.staffId
        n.patrolResult = cmd.result
        n.patrolNote = cmd.note

        if (cmd.result == PatrolResult.NOISE_NOT_FOUND) {
            n.status = NoiseComplaintStatus.UNFOUNDED
            n.willingToMovePhoneZone = cmd.willingToMovePhoneZone
            n.acceptsReminder = cmd.acceptsReminder
            n.relatedComplaintNo?.let {
                complaintService.resolve(it, "店员到场巡查未发现外放噪音，投诉不成立", rejected = true)
            }
            timeline.record("NOISE_PATROL_UNFOUNDED", n.store.id, n.complainantBookingNo, staffActor(cmd.staffId),
                "$noiseNo 巡查未发现噪音")
            return n
        }

        // 违规成立：登记意愿与历史违规次数（处置决策依据），但不动订单状态
        n.willingToMovePhoneZone = cmd.willingToMovePhoneZone
        n.acceptsReminder = cmd.acceptsReminder
        n.priorViolationCount = n.offender.noiseViolationCount
        n.offender.noiseViolationCount += 1
        n.violationCountAfter = n.offender.noiseViolationCount
        n.status = NoiseComplaintStatus.CONFIRMED

        val voucher = issueVoucher(n)
        n.voucher = voucher

        timeline.record(
            "NOISE_PATROL_CONFIRMED", n.store.id, n.offenderBookingNo, staffActor(cmd.staffId),
            "$noiseNo 巡查确认外放噪音成立；愿意换电话区=${cmd.willingToMovePhoneZone}，" +
                "接受提醒=${cmd.acceptsReminder}，历史违规=${n.priorViolationCount} 次（累计 ${n.violationCountAfter}）；" +
                "建议处置=${recommendAction(n)}；被影响用户补偿券 ${voucher.voucherNo}（${voucher.minutes} 分钟，绑定订单 ${n.complainantBookingNo}）"
        )
        timeline.record("NOISE_VOUCHER_ISSUED", n.store.id, n.complainantBookingNo, "STAFF",
            "延时券 ${voucher.voucherNo}：${voucher.minutes} 分钟，来源 $noiseNo，绑定原订单 ${n.complainantBookingNo}")
        return n
    }

    /** 发放与受影响原订单绑定的延时券 */
    private fun issueVoucher(n: NoiseComplaint): ExtensionVoucher {
        val v = voucherRepo.save(
            ExtensionVoucher(
                voucherNo = "TMP",
                owner = n.complainant,
                store = n.store,
                sourceBookingNo = n.complainantBookingNo,
                sourceNoiseNo = n.noiseNo,
                minutes = VOUCHER_MINUTES,
                issuedAt = clock.now(),
                note = "邻座噪音投诉 ${n.noiseNo} 成立补偿"
            )
        )
        v.voucherNo = BizNoGenerator.voucher(v.id!!)
        return v
    }

    /**
     * 店员处置：警告 / 换电话区 / 终止违规用户预约。
     * 只有本步的 TERMINATE_BOOKING 才会结束被投诉订单。
     */
    @Transactional
    fun handle(noiseNo: String, cmd: NoiseActionCommand): NoiseComplaint {
        val n = mustGet(noiseNo)
        if (n.status != NoiseComplaintStatus.CONFIRMED) {
            throw ConflictException("INVALID_STATUS", "投诉单 ${n.status}，须先巡查确认违规成立")
        }
        val offenderBooking = bookingRepo.findByBookingNo(n.offenderBookingNo)
            ?: throw NotFoundException("被投诉预约不存在")

        when (cmd.action) {
            NoiseActionType.WARN -> {
                timeline.record("NOISE_ACTION_WARN", n.store.id, offenderBooking.bookingNo, staffActor(cmd.staffId),
                    "$noiseNo 店员警告：停止外放网课声音；${cmd.note ?: ""}")
            }

            NoiseActionType.MOVE_TO_PHONE_ZONE -> {
                if (n.willingToMovePhoneZone != true) {
                    throw BizException(
                        "MOVE_REFUSED",
                        "被投诉人未确认愿意换电话区（willingToMovePhoneZone=${n.willingToMovePhoneZone}），请改为警告或终止"
                    )
                }
                val target = moveOffenderToPhoneZone(n, offenderBooking, cmd.targetSeatId)
                n.movedToSeatId = target.id
                n.movedToSeatCode = target.code
                timeline.record("NOISE_ACTION_MOVE_PHONE_ZONE", n.store.id, offenderBooking.bookingNo, staffActor(cmd.staffId),
                    "$noiseNo 移至电话区座位 ${target.code}，订单 ${offenderBooking.bookingNo} 继续不结束；${cmd.note ?: ""}")
            }

            NoiseActionType.TERMINATE_BOOKING -> {
                terminateOffenderBooking(n, offenderBooking, cmd)
            }
        }

        n.actionTaken = cmd.action
        n.actionAt = clock.now()
        n.actionNote = cmd.note
        n.status = NoiseComplaintStatus.HANDLED

        n.relatedComplaintNo?.let {
            complaintService.resolve(
                it,
                "噪音投诉成立，处置=${cmd.action}${n.movedToSeatCode?.let { c -> "，换至 $c" } ?: ""}" +
                    (cmd.note?.let { noteText -> "；$noteText" } ?: ""),
                rejected = false
            )
        }
        timeline.record("NOISE_COMPLAINT_HANDLED", n.store.id, n.complainantBookingNo, staffActor(cmd.staffId),
            "$noiseNo 处置完成：${cmd.action}")
        return n
    }

    /** 在双座位锁内把被投诉人迁移到电话区：订单不结束，仅更换座位与实时占用 */
    private fun moveOffenderToPhoneZone(n: NoiseComplaint, booking: Booking, targetSeatId: Long?): Seat {
        val oldSeat = booking.seat ?: throw BizException("NO_SEAT", "被投诉订单未分配座位")
        val target = targetSeatId?.let {
            seatRepo.findByStoreIdAndId(n.store.id!!, it)
                ?: throw NotFoundException("目标座位不存在或不属于该门店")
        } ?: findFreePhoneZoneSeat(n.store.id!!, oldSeat)
        ?: throw NotFoundException("电话区当前无空闲座位，请先调整分区或改用警告/终止")

        if (!target.zone.contains("电话")) {
            throw BizException("NOT_PHONE_ZONE", "座位 ${target.code} 不属于电话区（当前分区：${target.zone}）")
        }
        if (target.status == SeatStatus.MAINTENANCE || !target.active) {
            throw ConflictException("SEAT_UNAVAILABLE", "座位 ${target.code} 不可用")
        }

        val oldId = oldSeat.id!!
        val newId = target.id!!
        val now = clock.now()
        return seatLocks.withSeatLock(oldId) {
            seatLocks.withSeatLock(newId) {
                if (seatLocks.isOccupied(newId) ||
                    sensorRepo.findBySeatId(newId)?.occupied == true ||
                    bookingRepo.findActiveOnSeat(newId, now).any { it.actualCheckIn != null }
                ) {
                    throw ConflictException("SEAT_STILL_IN_USE", "电话区座位 ${target.code} 刚被占用，请重试或指定其他座位")
                }
                booking.noiseMovedFromSeatId = oldId
                booking.seat = target

                // 旧座位释放
                oldSeat.status = SeatStatus.AVAILABLE
                seatLocks.release(oldId, booking.bookingNo)
                sensorRepo.save(sensorRepo.findBySeatId(oldId)?.apply {
                    occupied = false; occupiedByBookingNo = null; updatedAt = now
                } ?: SeatSensorReading(seat = oldSeat, occupied = false, updatedAt = now))

                // 新座位占用，TTL 与正常进场一致
                target.status = SeatStatus.OCCUPIED
                val ttl = if (booking.type == BookingType.MONTHLY)
                    Duration.between(now, booking.effectiveEndTime).plusMinutes(10)
                else
                    Duration.between(now, booking.effectiveEndTime).plusHours(2)
                seatLocks.markOccupied(newId, booking.bookingNo, ttl)
                sensorRepo.save(sensorRepo.findBySeatId(newId)?.apply {
                    occupied = true; occupiedByBookingNo = booking.bookingNo; updatedAt = now
                } ?: SeatSensorReading(seat = target, occupied = true, occupiedByBookingNo = booking.bookingNo, updatedAt = now))
                target
            }
        }
    }

    private fun findFreePhoneZoneSeat(storeId: Long, oldSeat: Seat): Seat? {
        val now = clock.now()
        return seatRepo.findByStoreIdAndActiveTrue(storeId)
            .filter { it.zone.contains("电话") && it.seatType == oldSeat.seatType && it.status != SeatStatus.MAINTENANCE }
            .sortedBy { it.code }
            .firstOrNull { s ->
                val sid = s.id!!
                !seatLocks.isOccupied(sid) &&
                    sensorRepo.findBySeatId(sid)?.occupied != true &&
                    bookingRepo.findActiveOnSeat(sid, now).none { it.actualCheckIn != null }
            }
    }

    /**
     * 终止违规用户预约：释放座位；计时单置 TERMINATED（不退剩余使用费，押金余额原路退回），
     * 包月权益仅结束本次在场（权益保留，押金留至权益到期）。
     */
    private fun terminateOffenderBooking(n: NoiseComplaint, booking: Booking, cmd: NoiseActionCommand) {
        val seat = booking.seat ?: throw BizException("NO_SEAT", "被投诉订单未分配座位")
        val seatId = seat.id!!
        val now = clock.now()

        // 严重情形提示（不阻断店员裁量，但写入备注）
        val severity = when {
            n.priorViolationCount >= 1 -> "多次违规（历史 ${n.priorViolationCount} 次）"
            n.acceptsReminder == false -> "拒绝接受店员提醒"
            n.willingToMovePhoneZone == false -> "拒绝换至电话区"
            else -> "店员裁量终止（初犯且配合，建议优先警告/换区）"
        }

        seatLocks.withSeatLock(seatId) {
            booking.actualCheckOut = now
            seat.status = SeatStatus.AVAILABLE
            seatLocks.release(seatId, booking.bookingNo)
            sensorRepo.save(sensorRepo.findBySeatId(seatId)?.apply {
                occupied = false; occupiedByBookingNo = null; updatedAt = now
            } ?: SeatSensorReading(seat = seat, occupied = false, updatedAt = now))

            if (booking.type == BookingType.MONTHLY) {
                // 包月：终止本次在场，权益本身保留
                booking.status = BookingStatus.CONFIRMED
                timeline.record("NOISE_ACTION_TERMINATE", n.store.id, booking.bookingNo, staffActor(cmd.staffId),
                    "${n.noiseNo} 包月用户违规，结束本次在场并清出座位 ${seat.code}（包月权益保留）；$severity；${cmd.note ?: ""}")
            } else {
                booking.status = BookingStatus.TERMINATED
                // 违规终止：剩余使用费不退，押金余额（扣除既有赔损后）原路退回
                val depositBack = booking.deposit.subtract(booking.depositDeducted)
                    .max(java.math.BigDecimal.ZERO.setScale(2))
                if (depositBack.signum() > 0) {
                    billing.refund(booking, RefundReason.DEPOSIT_RETURN, depositBack, depositRefund = true,
                        note = "噪音违规终止预约，押金余额退回，剩余使用费不退")
                }
                timeline.record("NOISE_ACTION_TERMINATE", n.store.id, booking.bookingNo, staffActor(cmd.staffId),
                    "${n.noiseNo} 终止违规预约并清出座位 ${seat.code}，订单置 TERMINATED；$severity；" +
                        "剩余使用费不退，押金退 $depositBack；${cmd.note ?: ""}")
            }
        }
    }

    /** 处置建议：多次违规/拒不配合 → 终止；愿意换电话区 → 换区；否则警告 */
    fun recommendAction(n: NoiseComplaint): NoiseActionType = when {
        n.priorViolationCount >= 1 || n.acceptsReminder == false || n.willingToMovePhoneZone == false ->
            NoiseActionType.TERMINATE_BOOKING
        n.willingToMovePhoneZone == true -> NoiseActionType.MOVE_TO_PHONE_ZONE
        else -> NoiseActionType.WARN
    }

    @Transactional(readOnly = true)
    fun get(noiseNo: String): NoiseComplaint = mustGet(noiseNo)

    @Transactional(readOnly = true)
    fun listByStore(storeId: Long): List<NoiseComplaint> =
        noiseRepo.findByStoreIdOrderByReceivedAtDesc(storeId)

    private fun mustGet(noiseNo: String): NoiseComplaint =
        noiseRepo.findByNoiseNo(noiseNo) ?: throw NotFoundException("噪音投诉单不存在")

    private fun staffActor(staffId: Long?) = staffId?.let { "STAFF:$it" } ?: "STAFF"
}
