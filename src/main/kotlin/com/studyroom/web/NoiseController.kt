package com.studyroom.web

import com.studyroom.service.NoiseComplaintService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

@Tag(
    name = "邻座噪音投诉",
    description = "投诉受理（时间/座位/是否已提醒）→ 店员巡查 → 警告/换电话区/终止决策树 → 被影响用户延时券（绑定原订单）；区域高发诊断"
)
@RestController
@RequestMapping("/api/noise")
@Transactional
class NoiseController(
    private val noise: NoiseComplaintService
) {
    @Operation(
        summary = "受理邻座噪音投诉",
        description = "记录服务接收时间、座位号、投诉人是否已自行提醒；同步生成通用投诉与客服工单。被投诉订单不会因此结束。"
    )
    @PostMapping("/cases")
    fun file(@RequestBody @Valid req: NoiseComplaintRequest): NoiseCaseView =
        Views.of(
            noise.receive(
                complainantBookingNo = req.complainantBookingNo,
                description = req.description,
                alreadyReminded = req.alreadyReminded,
                respondentBookingNo = req.respondentBookingNo
            )
        )

    @Operation(summary = "门店噪音投诉案件列表")
    @GetMapping("/cases")
    @Transactional(readOnly = true)
    fun list(@RequestParam storeId: Long): List<NoiseCaseView> =
        noise.listByStore(storeId).map { Views.of(it) }

    @Operation(summary = "噪音投诉案件详情")
    @GetMapping("/cases/{caseNo}")
    @Transactional(readOnly = true)
    fun get(@PathVariable caseNo: String): NoiseCaseView = Views.of(noise.getCase(caseNo))

    @Operation(
        summary = "店员现场巡查",
        description = "SUBSTANTIATED=属实且违规者在场（随后需处置）；UNFOUNDED=投诉不成立；OFFENDER_LEFT=违规者已停止/离场（成立并发券，不再现场处置）。"
    )
    @PostMapping("/cases/{caseNo}/patrol")
    fun patrol(@PathVariable caseNo: String, @RequestBody @Valid req: NoisePatrolRequest): NoiseCaseView =
        Views.of(noise.patrol(caseNo, req.result, req.note, req.staffUserId, req.respondentBookingNo))

    @Operation(
        summary = "店员处置（决策树）",
        description = "按顺序判断：愿意换电话区→换座（订单保留）；不愿换但接受提醒→警告（订单继续）；两者拒绝且多次违规→终止预约强制结算；首违仍以书面警告留档。处置完成后向被影响用户发放与原订单绑定的延时券。"
    )
    @PostMapping("/cases/{caseNo}/enforce")
    fun enforce(@PathVariable caseNo: String, @RequestBody @Valid req: NoiseEnforceRequest): NoiseCaseView =
        Views.of(
            noise.enforce(
                caseNo = caseNo,
                willingToMovePhoneZone = req.willingToMovePhoneZone,
                acceptStaffWarning = req.acceptStaffWarning,
                voucherMinutes = req.voucherMinutes,
                note = req.note,
                staffUserId = req.staffUserId
            )
        )

    @Operation(summary = "在后续预约上核销延时券（券与原订单绑定用于溯源，不可在原订单上核销）")
    @PostMapping("/vouchers/{voucherNo}/redeem")
    fun redeem(
        @PathVariable voucherNo: String,
        @RequestBody @Valid req: NoiseVoucherRedeemRequest
    ): DelayVoucherView = Views.of(noise.redeemVoucher(voucherNo, req.bookingNo))

    @Operation(summary = "查询用户名下延时券")
    @GetMapping("/vouchers")
    @Transactional(readOnly = true)
    fun userVouchers(@RequestParam userId: Long): List<DelayVoucherView> =
        noise.vouchersOfUser(userId).map { Views.of(it) }

    @Operation(summary = "延时券详情")
    @GetMapping("/vouchers/{voucherNo}")
    @Transactional(readOnly = true)
    fun voucher(@PathVariable voucherNo: String): DelayVoucherView = Views.of(noise.getVoucher(voucherNo))

    @Operation(summary = "查询某订单绑定的补偿延时券（运营复盘补偿来源）")
    @GetMapping("/bookings/{bookingNo}/vouchers")
    @Transactional(readOnly = true)
    fun bookingVouchers(@PathVariable bookingNo: String): List<DelayVoucherView> =
        noise.vouchersOfBooking(bookingNo).map { Views.of(it) }
}
