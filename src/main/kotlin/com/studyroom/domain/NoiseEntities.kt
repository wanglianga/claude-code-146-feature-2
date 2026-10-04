package com.studyroom.domain

import jakarta.persistence.*
import java.time.Instant

/**
 * 邻座噪音投诉处理单。
 *
 * 与通用 [Complaint]（NOISE_NEIGHBOR 分类、客服工单）一一关联，
 * 额外承载店员巡查与“警告 / 换电话区 / 终止预约”的处置工作流。
 *
 * 关键约束：噪音投诉成立后被投诉订单**不会立即结束**——
 * 先记录被投诉人是否愿意换电话区、是否接受店员提醒、是否已多次违规，
 * 再由店员选择处置动作。
 */
@Entity
@Table(
    name = "noise_complaint",
    indexes = [
        Index(name = "idx_noise_store_time", columnList = "store_id,receivedAt"),
        Index(name = "idx_noise_zone", columnList = "store_id,zone")
    ]
)
class NoiseComplaint(
    @Column(nullable = false, unique = true) var noiseNo: String,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "complainant_id")
    var complainant: AppUser,
    /** 被影响用户的在场订单（延时券与该订单绑定） */
    @Column(nullable = false) var complainantBookingNo: String,
    /** 被投诉的外放噪音订单 */
    @Column(nullable = false) var offenderBookingNo: String,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offender_id")
    var offender: AppUser,
    /** 投诉发生时被投诉座位编号（快照） */
    @Column(nullable = false) var seatCode: String,
    /** 投诉发生时座位所属分区（快照，运营按分区聚合“布局 vs 提醒”问题） */
    @Column(nullable = false) var zone: String,
    /** 服务接收投诉时间 */
    @Column(nullable = false) var receivedAt: Instant,
    /** 店员是否已巡查 */
    @Column(nullable = false) var staffPatrolled: Boolean = false,
    /** 受理时用户是否已（自行/经提醒）提醒过邻座 */
    @Column(nullable = false) var remindedBeforePatrol: Boolean = false,
    @Column(nullable = false, length = 1000) var description: String = "",

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    var status: NoiseComplaintStatus = NoiseComplaintStatus.RECEIVED,

    /** 巡查结论 */
    @Enumerated(EnumType.STRING)
    var patrolResult: PatrolResult? = null,
    var patrolAt: Instant? = null,
    @Column(length = 1000) var patrolNote: String? = null,
    /** 巡查店员（演示系统无登录，由调用方传入） */
    var patrolStaffId: Long? = null,
    /** 被投诉人是否愿意换到电话区（巡查时询问） */
    var willingToMovePhoneZone: Boolean? = null,
    /** 是否接受店员提醒（保持安静、不外放） */
    var acceptsReminder: Boolean? = null,
    /** 巡查确认时该用户此前已成立的违规次数快照（不含本次） */
    @Column(nullable = false) var priorViolationCount: Int = 0,
    /** 巡查确认后累计违规次数（含本次） */
    @Column(nullable = false) var violationCountAfter: Int = 0,

    /** 处置动作 */
    @Enumerated(EnumType.STRING)
    var actionTaken: NoiseActionType? = null,
    var actionAt: Instant? = null,
    @Column(length = 1000) var actionNote: String? = null,
    /** 换电话区后的目标座位 */
    var movedToSeatId: Long? = null,
    var movedToSeatCode: String? = null,

    /** 关联通用投诉单与客服工单 */
    var relatedComplaintNo: String? = null,
    var relatedTicketNo: String? = null,
    /** 发放给被影响用户的延时券 */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voucher_id")
    var voucher: ExtensionVoucher? = null,

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

/**
 * 延时券：噪音投诉成立后发放给被影响用户。
 * 必须与原受影响订单（[sourceBookingNo]）绑定，券的发放/核销都可追溯到该订单。
 */
@Entity
@Table(
    name = "extension_voucher",
    indexes = [
        Index(name = "idx_voucher_owner", columnList = "owner_id"),
        Index(name = "idx_voucher_source", columnList = "sourceBookingNo")
    ]
)
class ExtensionVoucher(
    @Column(nullable = false, unique = true) var voucherNo: String,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id")
    var owner: AppUser,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,
    /** 绑定的原受影响订单号 */
    @Column(nullable = false) var sourceBookingNo: String,
    /** 来源噪音投诉单 */
    @Column(nullable = false) var sourceNoiseNo: String,
    /** 可抵扣延时分钟数 */
    @Column(nullable = false) var minutes: Long,
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    var status: VoucherStatus = VoucherStatus.ISSUED,
    @Column(nullable = false) var issuedAt: Instant,
    var redeemedAt: Instant? = null,
    /** 核销时使用的预约（可为原订单的继续延时，也可为后续预约） */
    var redeemedOnBookingNo: String? = null,
    var note: String? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

/**
 * 运营对噪音高发分区的干预记录：加强静音提示 / 调整座位分区。
 * 同一分区再次发生投诉时，运营据此区分“座位布局问题”还是“管理提醒无效”。
 */
@Entity
@Table(
    name = "zone_silence_notice",
    indexes = [Index(name = "idx_zone_notice_store_zone", columnList = "store_id,zone,createdAt")]
)
class ZoneSilenceNotice(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,
    @Column(nullable = false) var zone: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    var interventionType: ZoneInterventionType,
    @Column(nullable = false, length = 1000) var note: String,
    /** REZONE_LAYOUT 时的新分区名 */
    var newZone: String? = null,
    var createdBy: Long? = null,
    @Column(nullable = false) var createdAt: Instant,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)
