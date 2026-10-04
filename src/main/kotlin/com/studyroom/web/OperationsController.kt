package com.studyroom.web

import com.studyroom.domain.DisputeCause
import com.studyroom.repo.*
import com.studyroom.service.NoiseComplaintService
import com.studyroom.service.OperationsService
import com.studyroom.service.PlanRuleAdjustment
import com.studyroom.service.PriceAdjustment
import com.studyroom.service.SeatLayoutAdjustment
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*
import java.time.Instant

@Tag(name = "运营端", description = "上座率/延时率/投诉率/设备故障指标，价格、座位布局、包月规则调整，噪音高发区域诊断")
@RestController
@RequestMapping("/api/ops")
class OperationsController(
    private val ops: OperationsService,
    private val supportTicketRepo: SupportTicketRepository,
    private val noise: NoiseComplaintService
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

    @Operation(
        summary = "区域噪音高发诊断",
        description = "按区域聚合已成立噪音投诉：SEAT_LAYOUT_ISSUE=多名不同用户涉案且累犯率低（布局/隔音问题）；REMINDER_INEFFECTIVE=被警告后再犯率高（管理提醒无效）；同时返回最近治理措施及其后新增案件数。"
    )
    @GetMapping("/stores/{storeId}/noise-zones")
    fun noiseZones(
        @PathVariable storeId: Long,
        @RequestParam from: Instant,
        @RequestParam to: Instant
    ): ZoneNoiseStatsView = Views.of(noise.zoneStats(storeId, from, to))

    @Operation(
        summary = "高发区域治理留痕（调整分区/加强静音提示）",
        description = "记录运营措施与当时诊断；实际分区调整请配合 POST /api/ops/stores/{id}/seats/{seatId} 完成。再次查看区域诊断时会给出措施后新增案件数，判断是布局问题还是提醒无效。"
    )
    @PostMapping("/stores/{storeId}/noise-zones/review")
    fun reviewNoiseZone(
        @PathVariable storeId: Long,
        @RequestBody @Valid req: ZoneNoiseReviewRequest
    ): ZoneNoiseReviewView =
        Views.of(noise.reviewZone(storeId, req.zone, req.action, req.note, req.from, req.to))
}
