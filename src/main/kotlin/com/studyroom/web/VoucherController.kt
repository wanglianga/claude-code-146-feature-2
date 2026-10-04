package com.studyroom.web

import com.studyroom.repo.ExtensionVoucherRepository
import com.studyroom.support.NotFoundException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*

@Tag(name = "延时券", description = "噪音投诉成立补偿的延时券，与受影响原订单绑定")
@RestController
@RequestMapping("/api/vouchers")
@org.springframework.transaction.annotation.Transactional(readOnly = true)
class VoucherController(
    private val voucherRepo: ExtensionVoucherRepository
) {
    @Operation(summary = "查询用户名下延时券（发放/核销记录）")
    @GetMapping
    fun listByOwner(@RequestParam ownerId: Long): List<VoucherView> =
        voucherRepo.findByOwnerIdOrderByIssuedAtDesc(ownerId).map { Views.of(it) }

    @Operation(summary = "按原受影响订单查延时券（验证券与原订单绑定）")
    @GetMapping("/by-booking/{bookingNo}")
    fun listBySourceBooking(@PathVariable bookingNo: String): List<VoucherView> =
        voucherRepo.findBySourceBookingNo(bookingNo).map { Views.of(it) }

    @Operation(summary = "延时券详情")
    @GetMapping("/{voucherNo}")
    fun get(@PathVariable voucherNo: String): VoucherView =
        Views.of(voucherRepo.findByVoucherNo(voucherNo) ?: throw NotFoundException("延时券不存在"))
}
