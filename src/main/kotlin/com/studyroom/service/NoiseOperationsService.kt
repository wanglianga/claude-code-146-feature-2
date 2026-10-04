package com.studyroom.service

import com.studyroom.domain.*
import com.studyroom.repo.*
import com.studyroom.support.BizException
import com.studyroom.support.NotFoundException
import com.studyroom.support.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** 单个分区的噪音投诉聚合与诊断 */
data class ZoneNoiseStat(
    val storeId: Long,
    val zone: String,
    val confirmedComplaints: Int,
    /** 涉及的不同被投诉用户数 */
    val distinctOffenders: Int,
    /** 涉及的座位及各座位成立投诉数 */
    val complaintsBySeat: Map<String, Int>,
    /** 处置动作分布 */
    val actionsTaken: Map<String, Int>,
    /** 已采取的分区干预（加强提示/重新分区），按时间正序 */
    val interventions: List<ZoneInterventionView>,
    /** 诊断结论：LAYOUT_SUSPECTED 座位布局问题 / REMINDER_INEFFECTIVE 管理提醒无效 / OBSERVE 继续观察 */
    val diagnosis: String,
    val diagnosisReason: String,
    /** 运营下一步建议 */
    val recommendedIntervention: String?
)

data class ZoneInterventionView(
    val type: ZoneInterventionType,
    val note: String,
    val newZone: String?,
    val createdAt: Instant
)

/**
 * 噪音投诉的运营视角：按分区聚合，回答“同一区域反复投诉到底是
 * 座位布局问题，还是管理提醒无效”。
 *
 * 判定规则（窗口内已成立的投诉）：
 * - 加强静音提示之后仍有投诉，且涉及不同违规用户 → REMINDER_INEFFECTIVE（提醒无效，建议重划分区）；
 * - 未做过提示干预、投诉涉及多个座位/多个用户 → LAYOUT_SUSPECTED（疑似布局问题，建议先加强提示观察）；
 * - 其余（含仅 1 起）→ OBSERVE。
 */
@Service
class NoiseOperationsService(
    private val storeRepo: StoreRepository,
    private val seatRepo: SeatRepository,
    private val noiseRepo: NoiseComplaintRepository,
    private val noticeRepo: ZoneSilenceNoticeRepository,
    private val timeline: TimelineService,
    private val clock: TimeProvider
) {
    @Transactional(readOnly = true)
    fun zoneStats(storeId: Long, from: Instant, to: Instant): List<ZoneNoiseStat> {
        storeRepo.findById(storeId).orElseThrow { NotFoundException("门店不存在") }
        if (!to.isAfter(from)) throw BizException("BAD_RANGE", "结束时间必须晚于开始时间")

        val complaints = noiseRepo.findConfirmedByStore(storeId, from, to)
        val notices = noticeRepo.findByStoreIdOrderByCreatedAtDesc(storeId).reversed()

        return complaints.groupBy { it.zone }
            .map { (zone, zoneComplaints) -> buildStat(storeId, zone, zoneComplaints, notices.filter { it.zone == zone }) }
            .sortedWith(compareByDescending<ZoneNoiseStat> { it.confirmedComplaints }.thenBy { it.zone })
    }

    private fun buildStat(
        storeId: Long,
        zone: String,
        complaints: List<NoiseComplaint>,
        notices: List<ZoneSilenceNotice>
    ): ZoneNoiseStat {
        val bySeat = complaints.groupingBy { it.seatCode }.eachCount()
        val offenders = complaints.map { it.offender.id!! }.distinct().size
        val actions = complaints.mapNotNull { it.actionTaken?.name }.groupingBy { it }.eachCount()
        val interventionViews = notices.map {
            ZoneInterventionView(it.interventionType, it.note, it.newZone, it.createdAt)
        }

        val reinforceNotices = notices.filter { it.interventionType == ZoneInterventionType.REINFORCE_SILENCE_NOTICE }
        val afterReinforce = reinforceNotices.flatMap { notice ->
            complaints.filter { it.receivedAt.isAfter(notice.createdAt) }
        }.distinctBy { it.noiseNo }
        val offendersAfterReinforce = afterReinforce.map { it.offender.id!! }.distinct().size
        val rezoned = notices.any { it.interventionType == ZoneInterventionType.REZONE_LAYOUT }

        val frequent = complaints.size >= NoiseComplaintService.ZONE_FREQUENT_THRESHOLD

        val diagnosis: String
        val reason: String
        val recommended: String?
        when {
            // 已加强过静音提示，之后仍有不同用户继续违规 → 提醒手段已被证明无效
            reinforceNotices.isNotEmpty() && afterReinforce.isNotEmpty() && offendersAfterReinforce >= 2 -> {
                diagnosis = "REMINDER_INEFFECTIVE"
                reason = "加强静音提示后仍有 $offendersAfterReinforce 名不同用户在该分区外放被投诉成立，管理提醒无效"
                recommended = if (rezoned) "REINFORCE_SILENCE_NOTICE" else "REZONE_LAYOUT"
            }
            // 多座位/多用户反复出现且尚未干预 → 先怀疑布局（电话区不足/动静分区不合理）
            frequent && offenders >= 2 && bySeat.size >= 2 -> {
                diagnosis = "LAYOUT_SUSPECTED"
                reason = "${bySeat.size} 个座位、$offenders 名用户先后在该分区被投诉，疑似座位布局/分区不合理"
                recommended = "REINFORCE_SILENCE_NOTICE"
            }
            // 高频但集中在同一人 → 人的问题，继续管理处置
            frequent -> {
                diagnosis = "REMINDER_INEFFECTIVE"
                reason = "投诉集中在 $offenders 名用户，分区内其他用户正常，倾向个人行为问题而非布局"
                recommended = if (reinforceNotices.isEmpty()) "REINFORCE_SILENCE_NOTICE" else null
            }
            else -> {
                diagnosis = "OBSERVE"
                reason = "窗口内成立投诉 ${complaints.size} 起，暂不构成频繁投诉"
                recommended = null
            }
        }

        return ZoneNoiseStat(
            storeId = storeId, zone = zone,
            confirmedComplaints = complaints.size,
            distinctOffenders = offenders,
            complaintsBySeat = bySeat,
            actionsTaken = actions,
            interventions = interventionViews,
            diagnosis = diagnosis,
            diagnosisReason = reason,
            recommendedIntervention = recommended
        )
    }

    /** 运营对分区加强静音提示（张贴提示/广播/巡视频次），留痕供下次投诉对比 */
    @Transactional
    fun reinforceSilenceNotice(storeId: Long, zone: String, note: String, adminId: Long?): ZoneSilenceNotice {
        storeRepo.findById(storeId).orElseThrow { NotFoundException("门店不存在") }
        if (zone.isBlank()) throw BizException("BAD_ZONE", "分区不能为空")
        val saved = noticeRepo.save(
            ZoneSilenceNotice(
                store = storeRepo.findById(storeId).get(),
                zone = zone,
                interventionType = ZoneInterventionType.REINFORCE_SILENCE_NOTICE,
                note = note,
                createdBy = adminId,
                createdAt = clock.now()
            )
        )
        timeline.record("ZONE_SILENCE_NOTICE_REINFORCED", storeId, null, adminActor(adminId),
            "分区「$zone」加强静音提示：$note")
        return saved
    }

    /** 运营调整座位分区（如把易互相干扰的座位改划为电话区），留痕用于“布局 vs 提醒”诊断 */
    @Transactional
    fun rezoneSeat(storeId: Long, seatId: Long, newZone: String, note: String, adminId: Long?): Seat {
        if (newZone.isBlank()) throw BizException("BAD_ZONE", "新分区不能为空")
        val seat = seatRepo.findByStoreIdAndId(storeId, seatId) ?: throw NotFoundException("座位不存在")
        val oldZone = seat.zone
        if (oldZone == newZone) throw BizException("ZONE_UNCHANGED", "座位已属于 $newZone")
        seat.zone = newZone
        noticeRepo.save(
            ZoneSilenceNotice(
                store = seat.store, zone = oldZone,
                interventionType = ZoneInterventionType.REZONE_LAYOUT,
                note = "座位 ${seat.code}：$oldZone → $newZone；$note",
                newZone = newZone, createdBy = adminId, createdAt = clock.now()
            )
        )
        timeline.record("ZONE_LAYOUT_REZONED", storeId, null, adminActor(adminId),
            "座位 ${seat.code}：$oldZone → $newZone；$note")
        return seat
    }

    private fun adminActor(adminId: Long?) = adminId?.let { "ADMIN:$it" } ?: "ADMIN"
}
