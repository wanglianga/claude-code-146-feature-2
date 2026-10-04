package com.studyroom.web

import com.studyroom.domain.DisputeCause
import com.studyroom.repo.*
import com.studyroom.service.NoiseOperationsService
import com.studyroom.service.OperationsService
import com.studyroom.service.PlanRuleAdjustment
import com.studyroom.service.PriceAdjustment
import com.studyroom.service.SeatLayoutAdjustment
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*
import java.time.Instant

@Tag(name = "运营端", description = "上座率/延时率/投诉率/设备故障指标，价格、座位布局、包月规则调整，噪音分区诊断")
@RestController
@RequestMapping("/api/ops")
class OperationsController(
    private val ops: OperationsService,
    private val noiseOps: NoiseOperationsService,
    private val supportTicketRepo: SupportTicketRepository
) {
    @Operation(summary = "门店运营指标与争议根因分布")
    @GetMapping("/stores/{storeId}/metrics")
    fun metrics(
        @PathVariable storeId: Long,
        @RequestParam from: Instant,
        @RequestParam to: Instant
    ): MetricsView = Views.of(ops.metrics(storeId, from, to))

    @Operation(summary = "调整门店价格倍率")
    @PostMapping("/stores/{storeId}/price")
    fun price(@PathVariable storeId: Long, @RequestBody @Valid req: PriceAdjustRequest): StoreView =
        Views.of(ops.adjustPrice(storeId, PriceAdjustment(req.priceMultiplier, req.note)))

    @Operation(summary = "调整座位布局（分区/价格/停用）")
    @PostMapping("/stores/{storeId}/seats/{seatId}")
    fun seat(
        @PathVariable storeId: Long,
        @PathVariable seatId: Long,
        @RequestBody @Valid req: SeatAdjustRequest
    ): SeatView = Views.of(ops.adjustSeat(storeId, seatId, SeatLayoutAdjustment(req.zone, req.hourlyPrice, req.active)))

    @Operation(summary = "调整包月规则")
    @PostMapping("/plans/{planId}")
    fun plan(@PathVariable planId: Long, @RequestBody @Valid req: PlanAdjustRequest) =
        Views.of(ops.adjustPlan(planId, PlanRuleAdjustment(req.price, req.active, req.fixedSeat, req.days)))

    @Operation(
        summary = "按门店查噪音投诉分区诊断",
        description = "按分区聚合成立的噪音投诉，输出 LAYOUT_SUSPECTED（疑似座位布局问题）/ " +
            "REMINDER_INEFFECTIVE（管理提醒无效）/ OBSERVE，并给出干预建议。"
    )
    @GetMapping("/stores/{storeId}/noise-zones")
    fun noiseZones(
        @PathVariable storeId: Long,
        @RequestParam from: Instant,
        @RequestParam to: Instant
    ): List<ZoneNoiseStatView> = noiseOps.zoneStats(storeId, from, to).map { Views.of(it) }

    @Operation(summary = "对噪音高发分区加强静音提示（留痕，供再次投诉时区分“布局 vs 提醒”）")
    @PostMapping("/stores/{storeId}/noise-zones/{zone}/reinforce")
    fun reinforce(
        @PathVariable storeId: Long,
        @PathVariable zone: String,
        @RequestBody @Valid req: ReinforceSilenceRequest
    ): Map<String, Any> {
        val notice = noiseOps.reinforceSilenceNotice(storeId, zone, req.note, req.adminId)
        return mapOf(
            "storeId" to storeId, "zone" to zone,
            "interventionType" to notice.interventionType.name,
            "createdAt" to notice.createdAt
        )
    }

    @Operation(
        summary = "调整座位分区（如改为电话区）",
        description = "运营确认布局问题后重划分区并留痕；换电话区自动分配以 zone 含“电话”二字识别。"
    )
    @PostMapping("/stores/{storeId}/seats/{seatId}/rezone")
    fun rezone(
        @PathVariable storeId: Long,
        @PathVariable seatId: Long,
        @RequestBody @Valid req: RezoneSeatRequest
    ): SeatView = Views.of(noiseOps.rezoneSeat(storeId, seatId, req.newZone, req.note, req.adminId))

    @Operation(summary = "按门店查看争议来源分类（设备故障/用户迟到/权益滥用等）")
    @GetMapping("/stores/{storeId}/disputes")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun disputes(@PathVariable storeId: Long): Map<String, Any> {
        val tickets = supportTicketRepo.findByStoreIdOrderByCreatedAtDesc(storeId)
        val byCause: Map<String, Int> = tickets.groupingBy { it.cause.name }.eachCount()
        return mapOf(
            "storeId" to storeId,
            "total" to tickets.size,
            "byCause" to byCause,
            "deviceFaultTickets" to tickets.filter { it.cause == DisputeCause.DEVICE_FAULT }.map { it.ticketNo },
            "userLateTickets" to tickets.filter { it.cause == DisputeCause.USER_LATE }.map { it.ticketNo },
            "privilegeAbuseTickets" to tickets.filter { it.cause == DisputeCause.PRIVILEGE_ABUSE }.map { it.ticketNo }
        )
    }
}
