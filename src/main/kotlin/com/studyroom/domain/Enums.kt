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
    NO_SHOW,
    /** 因噪音违规等被店员终止（未消费部分按规则结算） */
    TERMINATED
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

/** 噪音投诉处理状态：受理 → 店员巡查确认 → 处置中 → 已处置 */
enum class NoiseComplaintStatus {
    /** 已受理，等待店员巡查 */
    RECEIVED,
    /** 店员巡查完成，违规成立，等待对被投诉人处置 */
    CONFIRMED,
    /** 巡查未发现噪音（投诉不成立） */
    UNFOUNDED,
    /** 处置完成（警告/换电话区/终止） */
    HANDLED
}

/** 店员巡查结论 */
enum class PatrolResult {
    /** 确实听到外放网课声等噪音，违规成立 */
    NOISE_CONFIRMED,
    /** 到场未发现噪音，投诉不成立 */
    NOISE_NOT_FOUND
}

/** 噪音违规处置动作 */
enum class NoiseActionType {
    /** 口头/书面警告，订单继续 */
    WARN,
    /** 移至电话区，原订单继续不结束 */
    MOVE_TO_PHONE_ZONE,
    /** 终止违规用户预约（多次违规等严重情形） */
    TERMINATE_BOOKING
}

/** 延时券状态 */
enum class VoucherStatus {
    /** 已发放，未使用 */
    ISSUED,
    /** 已用于一次延时 */
    REDEEMED,
    /** 关联订单已结束，券逾期失效 */
    EXPIRED
}

/** 运营对噪音高发区域采取的干预类型 */
enum class ZoneInterventionType {
    /** 加强静音提示（增派/张贴提示） */
    REINFORCE_SILENCE_NOTICE,
    /** 调整座位分区（如改为电话区/静音区重划） */
    REZONE_LAYOUT
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
