package com.studyroom.web

import com.studyroom.domain.*
import com.studyroom.service.CheckoutSettlement
import com.studyroom.service.StoreMetrics
import com.studyroom.service.TicketEvidence
import com.studyroom.service.ZoneNoiseStat
import com.studyroom.service.ZoneNoiseStats
import java.math.BigDecimal
import java.time.Instant

// ---------------- 请求 ----------------

data class CreateUserRequest(
    val phone: String,
    val name: String,
    val realNameVerified: Boolean = false,
    val role: UserRole = UserRole.USER
)

data class CreateBookingRequest(
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

data class ExtendRequest(val minutes: Long)

data class EnterRequest(val userId: Long, val storeId: Long, val doorId: String? = null)

data class LeaveRequest(
    val userId: Long,
    val storeId: Long,
    val lockerReturned: Boolean = true,
    val seatEquipmentOk: Boolean = true,
    /** NORMAL / EARLY_LEAVE / STORE_FAULT */
    val mode: String = "NORMAL",
    val incidentNo: String? = null,
    val doorId: String? = null
)

data class IncidentRequest(
    val storeId: Long,
    val type: IncidentType,
    val description: String,
    val seatId: Long? = null
)

data class ResolveIncidentRequest(val resolution: String)

data class BlackoutRequest(val blackout: Boolean, val reason: String = "门店临时停电")

data class ComplaintRequest(
    val bookingNo: String,
    val category: ComplaintCategory,
    val description: String,
    val againstBookingNo: String? = null,
    val incidentNo: String? = null
)

data class ResolveComplaintRequest(val resolution: String, val rejected: Boolean = false)

/** 邻座噪音投诉受理：服务接收时间由系统记录，只需提供在场预约号与是否已自行提醒 */
data class NoiseComplaintRequest(
    val complainantBookingNo: String,
    val description: String,
    val alreadyReminded: Boolean = false,
    /** 被投诉预约号；同区域仅一名其他在场用户时可省略（系统自动定位） */
    val respondentBookingNo: String? = null
)

data class NoisePatrolRequest(
    val result: com.studyroom.domain.NoisePatrolResult,
    val note: String? = null,
    val staffUserId: Long? = null,
    /** 巡查现场补录/指认被投诉订单 */
    val respondentBookingNo: String? = null
)

/**
 * 店员处置。决策按“换电话区意愿 → 是否接受提醒 → 是否多次违规”顺序进行：
 * 愿意换座则忽略提醒字段；两者都拒绝且历史多次违规才终止预约。
 */
data class NoiseEnforceRequest(
    val willingToMovePhoneZone: Boolean = false,
    val acceptStaffWarning: Boolean = false,
    val voucherMinutes: Int = 30,
    val note: String? = null,
    val staffUserId: Long? = null
)

data class NoiseVoucherRedeemRequest(val bookingNo: String)

data class ZoneNoiseReviewRequest(
    val zone: String,
    val action: com.studyroom.domain.ZoneReviewAction,
    val note: String,
    val from: Instant? = null,
    val to: Instant? = null
)

data class AppealRequest(val text: String)

data class DecideTicketRequest(
    val responsibility: Responsibility,
    val refundAmount: BigDecimal? = null,
    val note: String
)

data class SensorReportRequest(
    val storeId: Long,
    val seatId: Long,
    val occupied: Boolean,
    val occupiedByBookingNo: String? = null
)

data class PriceAdjustRequest(val priceMultiplier: Double, val note: String? = null)
data class SeatAdjustRequest(val zone: String? = null, val hourlyPrice: Double? = null, val active: Boolean? = null)
data class PlanAdjustRequest(val price: Double? = null, val active: Boolean? = null, val fixedSeat: Boolean? = null, val days: Int? = null)

data class ClockOverrideRequest(val time: Instant?)

// ---------------- 视图 ----------------

data class UserView(
    val id: Long, val phone: String, val name: String,
    val realNameVerified: Boolean, val role: UserRole,
    val noShowCount: Int, val blacklisted: Boolean
)

data class StoreView(
    val id: Long, val name: String, val address: String,
    val unattended: Boolean, val blackout: Boolean,
    val lateGraceMinutes: Int, val earlyEntryMinutes: Int,
    val priceMultiplier: Double, val earlyLeaveRefundRate: Double,
    val overtimeRate: Double
)

data class SeatView(
    val id: Long, val storeId: Long, val code: String,
    val type: SeatType, val status: SeatStatus, val zone: String,
    val hourlyPrice: BigDecimal, val lockerAvailable: Boolean, val active: Boolean,
    val sensorOccupied: Boolean?,
    val sensorOccupiedBy: String?
)

data class PlanView(
    val id: Long, val storeId: Long, val name: String,
    val seatType: SeatType?, val price: BigDecimal, val deposit: BigDecimal,
    val days: Int, val fixedSeat: Boolean, val active: Boolean
)

data class BookingView(
    val bookingNo: String,
    val userId: Long, val storeId: Long,
    val seatId: Long?, val seatCode: String?,
    val planId: Long?,
    val type: BookingType, val status: BookingStatus,
    val startTime: Instant, val endTime: Instant,
    val extendedEndTime: Instant?, val extended: Boolean,
    val actualCheckIn: Instant?, val actualCheckOut: Instant?,
    val price: BigDecimal, val deposit: BigDecimal,
    val paidAmount: BigDecimal, val refundedAmount: BigDecimal,
    val extraChargedAmount: BigDecimal, val depositDeducted: BigDecimal,
    val useLocker: Boolean, val silenceRuleAcknowledged: Boolean,
    val late: Boolean, val lateMinutes: Long,
    val lockerReturned: Boolean, val seatEquipmentOk: Boolean,
    val relatedIncidentNo: String?, val originalSeatId: Long?
)

data class AccessLogView(
    val id: Long, val bookingNo: String?, val userId: Long, val storeId: Long,
    val doorId: String?, val direction: AccessDirection, val result: AccessResult,
    val detail: String?, val monitorEventNo: String?, val createdAt: Instant
)

data class ChargeView(val id: Long, val type: ChargeType, val amount: BigDecimal, val note: String?, val createdAt: Instant)
data class RefundView(
    val refundNo: String, val type: RefundReason, val amount: BigDecimal,
    val status: RefundStatus, val depositRefund: Boolean,
    val ticketNo: String?, val incidentNo: String?, val note: String?,
    val createdAt: Instant, val completedAt: Instant?
)

data class IncidentView(
    val incidentNo: String, val storeId: Long, val seatCode: String?,
    val type: IncidentType, val status: IncidentStatus,
    val description: String, val monitorEventNo: String?,
    val openedAt: Instant, val resolvedAt: Instant?, val resolution: String?
)

data class ComplaintView(
    val complaintNo: String, val storeId: Long, val complainantUserId: Long,
    val bookingNo: String, val againstBookingNo: String?,
    val category: ComplaintCategory, val status: ComplaintStatus,
    val description: String, val relatedTicketNo: String?,
    val relatedIncidentNo: String?, val monitorEventNo: String?,
    val createdAt: Instant, val resolvedAt: Instant?, val resolution: String?
)

data class TicketView(
    val ticketNo: String, val storeId: Long, val userId: Long,
    val bookingNo: String, val cause: DisputeCause, val status: TicketStatus,
    val responsibility: Responsibility, val title: String, val description: String,
    val userAppeal: String?, val staffNote: String?,
    val monitorEventNos: String?, val incidentNo: String?, val complaintNo: String?,
    val createdAt: Instant, val resolvedAt: Instant?
)

data class TimelineEventView(
    val id: Long, val eventType: String, val occurredAt: Instant,
    val storeId: Long?, val bookingNo: String?, val actor: String?, val detail: String?
)

data class SettlementView(
    val overtimeFee: BigDecimal, val earlyRefund: BigDecimal,
    val depositDeduction: BigDecimal, val depositReturned: BigDecimal, val note: String
)

data class EntryDecisionView(
    val granted: Boolean, val result: AccessResult,
    val bookingNo: String?, val seatCode: String?, val detail: String
)

data class ExitResultView(
    val bookingNo: String, val seatCode: String,
    val settlement: SettlementView, val status: BookingStatus, val detail: String
)

data class SensorView(
    val seatId: Long, val occupied: Boolean, val occupiedByBookingNo: String?, val updatedAt: Instant
)

data class MetricsView(
    val storeId: Long, val from: Instant, val to: Instant,
    val activeSeats: Int, val checkedInBookings: Int, val extendedBookings: Int,
    val complaints: Long, val deviceIncidents: Long,
    val occupancyRate: Double, val extensionRate: Double, val complaintRate: Double,
    val disputesByCause: Map<String, Long>
)

data class EvidenceView(
    val ticket: TicketView,
    val booking: BookingView,
    val accessLogs: List<AccessLogView>,
    val timeline: List<TimelineEventView>,
    val sensor: SensorView?,
    val refunds: List<RefundView>,
    val complaint: ComplaintView?,
    val incident: IncidentView?,
    /** 邻座噪音案件（仅当工单由噪音投诉产生时存在） */
    val noiseCase: NoiseCaseView? = null
)

data class NoiseCaseView(
    val caseNo: String,
    val storeId: Long,
    val complainantUserId: Long,
    val respondentUserId: Long?,
    val complainantBookingNo: String,
    val respondentBookingNo: String?,
    val zone: String,
    val complainantSeatCode: String?,
    val respondentSeatCode: String?,
    val receivedAt: Instant,
    val alreadyReminded: Boolean,
    val description: String,
    val patrolResult: com.studyroom.domain.NoisePatrolResult?,
    val patrolledBy: Long?,
    val patrolNote: String?,
    val patrolledAt: Instant?,
    val willingToMovePhoneZone: Boolean?,
    val acceptStaffWarning: Boolean?,
    val priorViolationCount: Int,
    val enforcementAction: com.studyroom.domain.NoiseEnforcementAction?,
    val targetSeatCode: String?,
    val enforcementNote: String?,
    val enforcedAt: Instant?,
    val terminatedRefundSummary: String?,
    val voucherNo: String?,
    val relatedComplaintNo: String?,
    val relatedTicketNo: String?,
    val status: com.studyroom.domain.NoiseCaseStatus,
    val substantiated: Boolean,
    val resolvedAt: Instant?,
    val resolution: String?
)

data class DelayVoucherView(
    val voucherNo: String,
    val ownerUserId: Long,
    val storeId: Long,
    val sourceCaseNo: String,
    /** 绑定的原订单号 */
    val bookingNo: String,
    val minutes: Int,
    val status: com.studyroom.domain.DelayVoucherStatus,
    val issuedAt: Instant,
    val redeemedAt: Instant?,
    val redeemedBookingNo: String?,
    val note: String?
)

data class ZoneNoiseReviewView(
    val reviewNo: String,
    val storeId: Long,
    val zone: String,
    val action: com.studyroom.domain.ZoneReviewAction,
    val windowFrom: Instant,
    val windowTo: Instant,
    val caseCount: Int,
    val diagnosis: com.studyroom.domain.ZoneNoiseDiagnosis,
    val note: String,
    val createdAt: Instant
)

data class ZoneNoiseStatView(
    val zone: String,
    val cases: Int,
    val distinctRespondents: Int,
    val repeatOffenderCases: Int,
    val warnings: Int,
    val movedToPhoneZone: Int,
    val terminated: Int,
    val repeatRate: Double,
    val layoutSignal: Boolean,
    val reminderSignal: Boolean,
    val frequent: Boolean,
    val diagnosis: com.studyroom.domain.ZoneNoiseDiagnosis,
    val latestReviewAction: com.studyroom.domain.ZoneReviewAction?,
    val latestReviewAt: Instant?,
    val latestReviewDiagnosis: com.studyroom.domain.ZoneNoiseDiagnosis?,
    val casesAfterLatestReview: Int?
)

data class ZoneNoiseStatsView(
    val storeId: Long,
    val from: Instant,
    val to: Instant,
    val frequentThreshold: Int,
    val totalSubstantiatedCases: Int,
    val zones: List<ZoneNoiseStatView>
)

object Views {
    fun of(u: AppUser) = UserView(u.id!!, u.phone, u.name, u.realNameVerified, u.role, u.noShowCount, u.blacklisted)
    fun of(s: Store) = StoreView(
        s.id!!, s.name, s.address, s.unattended, s.blackout,
        s.lateGraceMinutes, s.earlyEntryMinutes, s.priceMultiplier,
        s.earlyLeaveRefundRate, s.overtimeRate
    )
    fun of(seat: Seat, sensor: SeatSensorReading? = null) = SeatView(
        seat.id!!, seat.store.id!!, seat.code, seat.seatType, seat.status, seat.zone,
        seat.hourlyPrice, seat.lockerAvailable, seat.active,
        sensor?.occupied, sensor?.occupiedByBookingNo
    )
    fun of(p: MembershipPlan) = PlanView(
        p.id!!, p.store.id!!, p.name, p.seatType, p.price, p.deposit, p.days, p.fixedSeat, p.active
    )
    fun of(b: Booking) = BookingView(
        b.bookingNo, b.user.id!!, b.store.id!!, b.seat?.id, b.seat?.code, b.plan?.id,
        b.type, b.status, b.startTime, b.endTime, b.extendedEndTime, b.extended,
        b.actualCheckIn, b.actualCheckOut,
        b.price, b.deposit, b.paidAmount, b.refundedAmount, b.extraChargedAmount, b.depositDeducted,
        b.useLocker, b.silenceRuleAcknowledged, b.late, b.lateMinutes,
        b.lockerReturned, b.seatEquipmentOk, b.relatedIncidentNo, b.originalSeatId
    )
    fun of(l: AccessLog) = AccessLogView(
        l.id!!, l.bookingNo, l.user.id!!, l.store.id!!, l.doorId,
        l.direction, l.result, l.detail, l.monitorEventNo, l.createdAt
    )
    fun of(c: Charge) = ChargeView(c.id!!, c.type, c.amount, c.note, c.createdAt)
    fun of(r: Refund) = RefundView(
        r.refundNo!!, r.reason, r.amount, r.status, r.depositRefund,
        r.ticketNo, r.incidentNo, r.note, r.createdAt, r.completedAt
    )
    fun of(i: Incident) = IncidentView(
        i.incidentNo, i.store.id!!, i.seat?.code, i.type, i.status,
        i.description, i.monitorEventNo, i.openedAt, i.resolvedAt, i.resolution
    )
    fun of(c: Complaint) = ComplaintView(
        c.complaintNo, c.store.id!!, c.complainant.id!!, c.bookingNo, c.againstBookingNo,
        c.category, c.status, c.description, c.relatedTicketNo, c.relatedIncidentNo,
        c.monitorEventNo, c.createdAt, c.resolvedAt, c.resolution
    )
    fun of(t: SupportTicket) = TicketView(
        t.ticketNo, t.store.id!!, t.user.id!!, t.bookingNo, t.cause, t.status,
        t.responsibility, t.title, t.description, t.userAppeal, t.staffNote,
        t.monitorEventNos, t.incidentNo, t.complaintNo, t.createdAt, t.resolvedAt
    )
    fun of(e: TimelineEvent) = TimelineEventView(e.id!!, e.eventType, e.occurredAt, e.storeId, e.bookingNo, e.actor, e.detail)
    fun of(s: CheckoutSettlement) = SettlementView(s.overtimeFee, s.earlyRefund, s.depositDeduction, s.depositReturned, s.note)
    fun of(m: StoreMetrics) = MetricsView(
        m.storeId, m.from, m.to, m.activeSeats, m.checkedInBookings, m.extendedBookings,
        m.complaints, m.deviceIncidents, m.occupancyRate, m.extensionRate, m.complaintRate,
        m.disputesByCause
    )
    fun of(r: SeatSensorReading) = SensorView(r.seat.id!!, r.occupied, r.occupiedByBookingNo, r.updatedAt)
    fun of(c: NoiseComplaintCase) = NoiseCaseView(
        c.caseNo, c.store.id!!, c.complainant.id!!, c.respondent?.id,
        c.complainantBookingNo, c.respondentBookingNo,
        c.zone, c.complainantSeatCode, c.respondentSeatCode,
        c.receivedAt, c.alreadyReminded, c.description,
        c.patrolResult, c.patrolledBy, c.patrolNote, c.patrolledAt,
        c.willingToMovePhoneZone, c.acceptStaffWarning, c.priorViolationCount,
        c.enforcementAction, c.targetSeatCode, c.enforcementNote, c.enforcedAt,
        c.terminatedRefundSummary, c.voucherNo, c.relatedComplaintNo, c.relatedTicketNo,
        c.status, c.substantiated, c.resolvedAt, c.resolution
    )
    fun of(v: DelayVoucher) = DelayVoucherView(
        v.voucherNo, v.owner.id!!, v.store.id!!, v.sourceCaseNo,
        v.bookingNo, v.minutes, v.status, v.issuedAt,
        v.redeemedAt, v.redeemedBookingNo, v.note
    )
    fun of(r: ZoneNoiseReview) = ZoneNoiseReviewView(
        r.reviewNo, r.store.id!!, r.zone, r.action,
        r.windowFrom, r.windowTo, r.caseCount, r.diagnosis, r.note, r.createdAt
    )
    fun of(s: ZoneNoiseStat) = ZoneNoiseStatView(
        s.zone, s.cases, s.distinctRespondents, s.repeatOffenderCases,
        s.warnings, s.movedToPhoneZone, s.terminated, s.repeatRate,
        s.layoutSignal, s.reminderSignal, s.frequent, s.diagnosis,
        s.latestReviewAction, s.latestReviewAt, s.latestReviewDiagnosis, s.casesAfterLatestReview
    )
    fun of(s: ZoneNoiseStats) = ZoneNoiseStatsView(
        s.storeId, s.from, s.to, s.frequentThreshold, s.totalSubstantiatedCases,
        s.zones.map { of(it) }
    )
    fun of(e: TicketEvidence) = EvidenceView(
        of(e.ticket), of(e.booking),
        e.accessLogs.map { of(it) }, e.timeline.map { of(it) },
        e.sensor?.let { of(it) }, e.refunds.map { of(it) },
        e.complaint?.let { of(it) }, e.incident?.let { of(it) },
        e.noiseCase?.let { of(it) }
    )
}
