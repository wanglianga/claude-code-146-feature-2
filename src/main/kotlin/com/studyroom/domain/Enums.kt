package com.studyroom.domain

/** 座位/预约种类：单座、包间、夜间座 */
enum class SeatType { SINGLE, ROOM, NIGHT }

enum class BookingType {
    /** 单座按小时 */
    SINGLE,
    /** 包间按小时 */
    ROOM,
    /** 夜间座（通宵时段一口价） */
    NIGHT,
    /** 包月权益（一段日期内可重复进出） */
    MONTHLY
}

enum class BookingStatus {
    /** 已下单未支付 */
    PENDING_PAYMENT,
    /** 已支付，等待进场 */
    CONFIRMED,
    /** 在场（包月会员单次进场后也是该状态，离场后回到 CONFIRMED） */
    IN_PROGRESS,
    /** 已正常结束（可能含部分退费） */
    COMPLETED,
    /** 开场前取消 / 全退后关闭 */
    CANCELLED,
    /** 超时未到，计为爽约 */
    NO_SHOW
}

/** 账目方向：应收 / 退还（押金抵扣等） */
enum class ChargeType { FEE, DEPOSIT, EXTENSION_FEE, OVERTIME_FEE, DEPOSIT_DEDUCT, COMPENSATION }

enum class RefundReason {
    /** 用户提前离场 */
    EARLY_LEAVE,
    /** 门店责任：停电、空调故障、门禁故障等 */
    STORE_FAULT,
    /** 迟到顺延碰撞导致下一位用户无法使用原座 */
    COLLISION_REASSIGN,
    /** 客服争议判定退款 */
    DISPUTE_APPROVED,
    /** 未消费全额退（取消/爽约免责） */
    UNUSED_FULL,
    /** 押金余额正常退回 */
    DEPOSIT_RETURN
}

enum class RefundStatus { PENDING, COMPLETED, REJECTED }

enum class AccessDirection { IN, OUT }

/** 门禁判定结果——放行与拒绝的原因必须可追溯 */
enum class AccessResult {
    GRANTED,
    /** 进场时发现座位被他人占用，自动换座后放行 */
    GRANTED_REASSIGNED,
    DENIED_NO_BOOKING,
    DENIED_UNPAID,
    DENIED_NOT_REALNAME,
    DENIED_OUTSIDE_WINDOW,
    DENIED_LATE_EXPIRED,
    DENIED_DOOR_FAULT,
    DENIED_STORE_BLACKOUT,
    DENIED_NO_FREE_SEAT
}

enum class IncidentType { DOOR_FAILURE, AC_FAILURE, POWER_OUTAGE, SEAT_DAMAGE, LOCKER_FAILURE }
enum class IncidentStatus { OPEN, RESOLVED }

enum class ComplaintCategory { NOISE_NEIGHBOR, SEAT_OCCUPIED, EQUIPMENT_ENV, OTHER }
enum class ComplaintStatus { OPEN, RESOLVED, REJECTED }

/** 邻座噪音投诉案件状态 */
enum class NoiseCaseStatus {
    /** 已受理，待店员巡查 */
    OPEN,
    /** 店员巡查确认（含违规者已离场），处置/补偿已记录 */
    CONFIRMED,
    /** 店员巡查未发现噪音，投诉不成立 */
    REJECTED
}

/** 店员现场巡查结论 */
enum class NoisePatrolResult {
    /** 外放噪音属实，违规者仍在场 */
    SUBSTANTIATED,
    /** 到场未发现噪音，投诉不成立 */
    UNFOUNDED,
    /** 违规者已停止/离场，投诉按成立记录但不再现场处置 */
    OFFENDER_LEFT
}

/** 噪音违规处置动作（订单不会在投诉受理时立即结束，处置依决策树产生） */
enum class NoiseEnforcementAction {
    /** 警告：违规者接受店员提醒，订单继续 */
    WARN,
    /** 换到电话区：违规者愿意换座，订单保留并改派电话区座位 */
    MOVE_TO_PHONE_ZONE,
    /** 终止违规用户预约：拒不接受提醒且已多次违规，强制离场结算 */
    TERMINATE_BOOKING
}

/** 延时券状态 */
enum class DelayVoucherStatus { ISSUED, REDEEMED, EXPIRED }

/** 区域噪音高发诊断：帮助运营区分“布局问题”与“提醒无效” */
enum class ZoneNoiseDiagnosis {
    /** 样本不足（窗口内投诉少于阈值） */
    INSUFFICIENT,
    /** 座位布局/隔音问题：投诉来自多名不同用户且累犯率低 */
    SEAT_LAYOUT_ISSUE,
    /** 管理提醒无效：被警告/提醒后的违规者再次案发率高 */
    REMINDER_INEFFECTIVE,
    /** 两类信号同时存在，需组合治理 */
    MIXED
}

/** 运营针对高发区域的治理动作（留痕，便于下次投诉时评估措施有效性） */
enum class ZoneReviewAction {
    /** 调整座位分区（如加装隔断、改电话区/缓冲带） */
    LAYOUT_ADJUST,
    /** 加强静音提示（标识、广播、加密巡查） */
    SILENCE_REMINDER
}

/** 争议根因归类，供运营端筛选 */
enum class DisputeCause {
    /** 设备/门店设施故障（含停电、空调、门禁） */
    DEVICE_FAULT,
    /** 用户迟到及迟到引发的连锁碰撞 */
    USER_LATE,
    /** 包月权益滥用/冲突 */
    PRIVILEGE_ABUSE,
    /** 邻座噪音等用户间纠纷 */
    USER_BEHAVIOR,
    OTHER
}

enum class TicketStatus { OPEN, IN_PROGRESS, RESOLVED, CLOSED }

/** 责任方：门店 / 用户 / 共担 / 无责 */
enum class Responsibility { STORE, USER, SHARED, NONE }

enum class SeatStatus { AVAILABLE, OCCUPIED, MAINTENANCE }

enum class UserRole { USER, STAFF, ADMIN }
