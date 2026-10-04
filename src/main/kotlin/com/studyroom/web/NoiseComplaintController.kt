package com.studyroom.web

import com.studyroom.service.FileNoiseCommand
import com.studyroom.service.NoiseActionCommand
import com.studyroom.service.NoiseComplaintService
import com.studyroom.service.NoisePatrolCommand
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@Tag(
    name = "邻座噪音投诉",
    description = "受理（投诉时间/座位号/是否已提醒）→ 店员巡查确认 → 警告/换电话区/终止预约；成立即发与原订单绑定的延时券"
)
@RestController
@RequestMapping("/api/noise-complaints")
@org.springframework.transaction.annotation.Transactional
class NoiseComplaintController(
    private val noiseService: NoiseComplaintService
) {
    @Operation(
        summary = "受理邻座噪音投诉",
        description = "记录服务接收投诉时间、被投诉座位号/分区、店员是否巡查、用户是否已提醒邻座；" +
            "同步生成 NOISE_NEIGHBOR 投诉单与客服工单。被投诉订单可自动按同分区在场订单定位。"
    )
    @PostMapping
    fun file(@RequestBody @Valid req: NoiseComplaintRequest): NoiseComplaintView {
        val n = noiseService.file(
            FileNoiseCommand(
                complainantBookingNo = req.complainantBookingNo,
                offenderBookingNo = req.offenderBookingNo,
                description = req.description,
                remindedBeforePatrol = req.remindedBeforePatrol
            )
        )
        return Views.of(n)
    }

    @Operation(
        summary = "店员巡查确认",
        description = "巡查确认违规成立后：累计违规次数、记录是否愿意换电话区/是否接受提醒、发放与原订单绑定的延时券；" +
            "被投诉订单此时不会结束。巡查未发现噪音则投诉不成立。"
    )
    @PostMapping("/{noiseNo}/patrol")
    fun patrol(@PathVariable noiseNo: String, @RequestBody @Valid req: NoisePatrolRequest): NoiseComplaintView {
        val n = noiseService.patrol(
            noiseNo,
            NoisePatrolCommand(
                staffId = req.staffId, result = req.result,
                willingToMovePhoneZone = req.willingToMovePhoneZone,
                acceptsReminder = req.acceptsReminder, note = req.note
            )
        )
        return Views.of(n, if (n.status == com.studyroom.domain.NoiseComplaintStatus.CONFIRMED) noiseService.recommendAction(n) else null)
    }

    @Operation(
        summary = "店员处置：警告 / 换电话区 / 终止违规用户预约",
        description = "只有 TERMINATE_BOOKING 才会结束被投诉订单（计时单置 TERMINATED 退押金不退剩余费；包月仅结束本次在场）；" +
            "换电话区要求被投诉人已确认愿意换区，订单继续不结束。"
    )
    @PostMapping("/{noiseNo}/handle")
    fun handle(@PathVariable noiseNo: String, @RequestBody @Valid req: NoiseActionRequest): NoiseComplaintView =
        Views.of(noiseService.handle(noiseNo, NoiseActionCommand(req.action, req.staffId, req.note, req.targetSeatId)))

    @Operation(summary = "门店噪音投诉处理单列表")
    @GetMapping
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun list(@RequestParam storeId: Long): List<NoiseComplaintView> =
        noiseService.listByStore(storeId).map { Views.of(it) }

    @Operation(summary = "噪音投诉处理单详情（含延时券、巡查与处置记录）")
    @GetMapping("/{noiseNo}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun get(@PathVariable noiseNo: String): NoiseComplaintView =
        Views.of(noiseService.get(noiseNo))
}
