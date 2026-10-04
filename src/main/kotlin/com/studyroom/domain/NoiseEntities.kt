package com.studyroom.domain

import jakarta.persistence.*
import java.time.Instant

/**
 * 邻座噪音投诉案件。
 *
 * 与通用 [Complaint] 的区别：通用投诉只负责“受理—归因—工单”，
 * 本实体承载现场处置全链路：店员巡查、违规者意愿、决策树处置（警告/换电话区/终止）、
 * 被影响用户补偿。投诉受理时**不结束**被投诉订单，是否终止由处置决策决定。
 */
@Entity
@Table(
    name = "noise_complaint_case",
    indexes = [
        Index(name = "idx_noise_store_time", columnList = "store_id,receivedAt"),
        Index(name = "idx_noise_zone", columnList = "store_id,zone"),
        Index(name = "idx_noise_respondent_booking", columnList = "respondentBookingNo")
    ]
)
class NoiseComplaintCase(
    @Column(nullable = false, unique = true) var caseNo: String,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "complainant_id")
    var complainant: AppUser,

    /** 被投诉用户；受理时若邻座订单无法定位可空，巡查确认时补录 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "respondent_id")
    var respondent: AppUser? = null,

    /** 被影响用户的在场预约号（延时券与该订单绑定） */
    @Column(nullable = false) var complainantBookingNo: String,
    /** 被投诉用户的预约号（邻座外放来源） */
    var respondentBookingNo: String? = null,

    /** 案发区域快照（座位后续改区也不影响历史归因） */
    @Column(nullable = false) var zone: String,
    var complainantSeatId: Long? = null,
    var complainantSeatCode: String? = null,
    var respondentSeatId: Long? = null,
    var respondentSeatCode: String? = null,

    /** 服务接收投诉时间 */
    @Column(nullable = false) var receivedAt: Instant,
    /** 投诉人是否已自行提醒过邻座 */
    @Column(nullable = false) var alreadyReminded: Boolean = false,
    @Column(nullable = false, length = 500) var description: String,

    // ---------------- 店员巡查 ----------------
    @Enumerated(EnumType.STRING)
    var patrolResult: NoisePatrolResult? = null,
    var patrolledBy: Long? = null,
    @Column(length = 500) var patrolNote: String? = null,
    var patrolledAt: Instant? = null,

    // ---------------- 处置决策（成立后填写） ----------------
    /** 违规者是否愿意换到电话区（决策树第 1 层） */
    var willingToMovePhoneZone: Boolean? = null,
    /** 是否接受店员提醒/警告（决策树第 2 层） */
    var acceptStaffWarning: Boolean? = null,
    /** 处置前该违规用户的历史噪音违规次数（决策树第 3 层依据） */
    @Column(nullable = false) var priorViolationCount: Int = 0,

    @Enumerated(EnumType.STRING)
    var enforcementAction: NoiseEnforcementAction? = null,
    /** 换座时的目标电话区座位 */
    var targetSeatId: Long? = null,
    var targetSeatCode: String? = null,
    @Column(length = 500) var enforcementNote: String? = null,
    var enforcedAt: Instant? = null,
    /** 终止处置对应/退款结算号（可追溯，不做外键约束） */
    var terminatedRefundSummary: String? = null,

    // ---------------- 补偿 ----------------
    /** 发给被影响用户、与其原订单绑定的延时券号 */
    var voucherNo: String? = null,

    /** 关联通用投诉与客服工单（双向可追溯） */
    var relatedComplaintNo: String? = null,
    var relatedTicketNo: String? = null,

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    var status: NoiseCaseStatus = NoiseCaseStatus.OPEN,
    @Column(nullable = false) var substantiated: Boolean = false,
    var resolvedAt: Instant? = null,
    @Column(length = 500) var resolution: String? = null,

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

/**
 * 延时券：噪音投诉成立后发给被影响用户，**与原订单绑定**。
 * 核销时只能用于该用户的预约，且不能核销回被投诉的那场已结束时段；
 * 券与案件互相引用，运营复盘可追溯补偿来源。
 */
@Entity
@Table(
    name = "delay_voucher",
    indexes = [
        Index(name = "idx_voucher_owner", columnList = "owner_id"),
        Index(name = "idx_voucher_booking", columnList = "bookingNo")
    ]
)
class DelayVoucher(
    @Column(nullable = false, unique = true) var voucherNo: String,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id")
    var owner: AppUser,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,

    /** 来源噪音案件 */
    @Column(nullable = false) var sourceCaseNo: String,
    /** 绑定的原订单号（被影响时的在场预约） */
    @Column(nullable = false) var bookingNo: String,
    /** 可抵扣延时分钟数 */
    @Column(nullable = false) var minutes: Int,

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    var status: DelayVoucherStatus = DelayVoucherStatus.ISSUED,

    @Column(nullable = false) var issuedAt: Instant = Instant.now(),
    var redeemedAt: Instant? = null,
    /** 核销时使用的预约号（通常为券主之后的新预约） */
    var redeemedBookingNo: String? = null,
    @Column(length = 300) var note: String? = null,

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

/**
 * 区域噪音治理记录：投诉在同一区域频繁发生后，运营采取的措施留痕。
 * 下次复盘时可对比措施前后投诉/累犯数据，判断是布局问题还是提醒无效。
 */
@Entity
@Table(
    name = "zone_noise_review",
    indexes = [Index(name = "idx_zone_review_store_zone", columnList = "store_id,zone,createdAt")]
)
class ZoneNoiseReview(
    @Column(nullable = false, unique = true) var reviewNo: String,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,

    @Column(nullable = false) var zone: String,

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    var action: ZoneReviewAction,

    /** 复盘窗口 */
    @Column(nullable = false) var windowFrom: Instant,
    @Column(nullable = false) var windowTo: Instant,
    /** 采取措施时窗口内已成立案件数 */
    @Column(nullable = false) var caseCount: Int = 0,
    /** 采取措施时系统诊断（留存，供措施后对比） */
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    var diagnosis: ZoneNoiseDiagnosis,

    @Column(nullable = false, length = 500) var note: String,
    @Column(nullable = false) var createdAt: Instant = Instant.now(),

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)
