#!/usr/bin/env bash
# 邻座噪音投诉处理全链路演示：
# 受理（时间/座位号/是否已提醒，不结束订单）→ 店员巡查 → 警告/换电话区/累犯终止决策树
# → 被影响用户延时券（与原订单绑定）→ 后续预约核销 → 区域高发诊断（布局问题 vs 提醒无效）与运营治理留痕。
set -e
BASE=http://localhost:8080
iso() { python3 -c "import datetime,sys; print((datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)+datetime.timedelta(minutes=float(sys.argv[1]))).isoformat().replace('+00:00','Z'))" "$1"; }
NOW=$(iso 0)
T_END=$(iso 120)

curl -s -X POST "$BASE/api/admin/clock" -H 'Content-Type: application/json' -d '{"time":null}' >/dev/null
SEAT_ID() { curl -s "$BASE/api/stores/1/seats" | python3 -c 'import sys,json; print([s["id"] for s in json.load(sys.stdin) if s["code"]=="'$1'"][0])'; }
A01=$(SEAT_ID A01); A02=$(SEAT_ID A02); P01=$(SEAT_ID P01)
mkuser() { curl -s -X POST "$BASE/api/users" -H 'Content-Type: application/json' -d '{"phone":"'$1'","name":"'$2'","realNameVerified":true}' | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])'; }
book_enter() { # USER SEAT START END -> 进场后的 bookingNo
  local b=$(curl -s -X POST "$BASE/api/bookings" -H 'Content-Type: application/json' -d \
    '{"userId":'$1',"storeId":1,"seatId":'$2',"type":"SINGLE","startTime":"'$3'","endTime":"'$4'","silenceRuleAcknowledged":true}' \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["bookingNo"])')
  curl -s -X POST "$BASE/api/bookings/$b/pay" >/dev/null
  curl -s -X POST "$BASE/api/access/enter" -H 'Content-Type: application/json' -d '{"userId":'$1',"storeId":1}' >/dev/null
  echo "$b"
}
leave_if_in() { # USER
  curl -s -X POST "$BASE/api/access/leave" -H 'Content-Type: application/json' \
    -d '{"userId":'$1',"storeId":1,"mode":"NORMAL"}' >/dev/null 2>&1 || true
}

# 一场完整纠纷：下单进场 → 投诉（已自行提醒）→ 店员巡查属实 → 处置
# $1=受害者手机号 $2=违规者手机号 $3=willingToMove $4=acceptWarning；echo 案件 JSON 到 stdout
run_case() {
  local cu=$(mkuser $1 "受害者-$1") ru=$(mkuser $2 "违规者-$2")
  local cb=$(book_enter $cu $A01 "$NOW" "$T_END")
  local rb=$(book_enter $ru $A02 "$NOW" "$T_END")
  local case_no=$(curl -s -X POST "$BASE/api/noise/cases" -H 'Content-Type: application/json' -d \
    '{"complainantBookingNo":"'$cb'","description":"邻座外放网课声音很大","alreadyReminded":true,"respondentBookingNo":"'$rb'"}' \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["caseNo"])')
  curl -s -X POST "$BASE/api/noise/cases/$case_no/patrol" -H 'Content-Type: application/json' \
    -d '{"result":"SUBSTANTIATED","note":"现场确认外放网课","staffUserId":4}' >/dev/null
  curl -s -X POST "$BASE/api/noise/cases/$case_no/enforce" -H 'Content-Type: application/json' \
    -d '{"willingToMovePhoneZone":'$3',"acceptStaffWarning":'$4',"voucherMinutes":30,"staffUserId":4}'
  leave_if_in $cu
  # 被终止的违规者已由店员强制离场，正常离场会返回 409，忽略即可
  leave_if_in $ru
}

echo "== 第一场：违规者愿意换电话区 → 换座（订单保留）、原座释放、受害者得绑定原订单的延时券 =="
C1=$(run_case 13770000001 13770000002 true false)
echo "$C1" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  案件:",d["caseNo"],"状态:",d["status"],"处置:",d["enforcementAction"],"换至:",d["targetSeatCode"],"延时券:",d["voucherNo"])'
CB1=$(echo "$C1" | python3 -c 'import sys,json; print(json.load(sys.stdin)["complainantBookingNo"])')
RB1=$(echo "$C1" | python3 -c 'import sys,json; print(json.load(sys.stdin)["respondentBookingNo"])')
VOUCHER=$(echo "$C1" | python3 -c 'import sys,json; print(json.load(sys.stdin)["voucherNo"])')
curl -s "$BASE/api/bookings/$RB1" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  被诉订单未被终止，座位已改派:",d["seatCode"],"（原 A02 → 电话区）")'
echo "  延时券与原订单绑定："
curl -s "$BASE/api/noise/bookings/$CB1/vouchers" | python3 -c 'import sys,json; v=json.load(sys.stdin)[0]; print("   ", v["voucherNo"], v["minutes"],"分钟 绑定订单", v["bookingNo"], "状态", v["status"])'

echo "== 第二场：同一违规用户再次外放且拒不换座/不接受提醒 → 多次违规，终止预约（订单此前未结束）=="
NOW=$(iso 200); T_END=$(iso 320)
curl -s -X POST "$BASE/api/admin/clock" -H 'Content-Type: application/json' -d "{\"time\":\"$NOW\"}" >/dev/null
# 复用第 1 场的违规用户 13770000002
RU=$(curl -s "$BASE/api/users" | python3 -c 'import sys,json; print([u["id"] for u in json.load(sys.stdin) if u["phone"]=="13770000002"][0])' 2>/dev/null || true)
if [ -z "$RU" ]; then echo "无法定位累犯用户"; exit 1; fi
CU2=$(mkuser 13770000003 "累犯场受害者")
CB=$(book_enter $CU2 $A01 "$NOW" "$T_END"); RB=$(book_enter $RU $A02 "$NOW" "$T_END")
CASE2=$(curl -s -X POST "$BASE/api/noise/cases" -H 'Content-Type: application/json' -d \
  '{"complainantBookingNo":"'$CB'","description":"又是他在放网课","alreadyReminded":true,"respondentBookingNo":"'$RB'"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["caseNo"])')
curl -s -X POST "$BASE/api/noise/cases/$CASE2/patrol" -H 'Content-Type: application/json' -d '{"result":"SUBSTANTIATED","staffUserId":4}' >/dev/null
curl -s -X POST "$BASE/api/noise/cases/$CASE2/enforce" -H 'Content-Type: application/json' \
  -d '{"willingToMovePhoneZone":false,"acceptStaffWarning":false,"staffUserId":4}' \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  历史违规次数:",d["priorViolationCount"],"处置:",d["enforcementAction"]); print("  ",d["terminatedRefundSummary"])'
curl -s "$BASE/api/bookings/$RB" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  被诉订单状态:",d["status"],"已退:",d["refundedAmount"],"（剩余使用费不退，押金余额退回）")'
leave_if_in $CU2

echo "== 再补两起不同违规者的警告案件，制造“多名不同用户涉案”的布局信号 =="
NOW=$(iso 400); T_END=$(iso 520)
curl -s -X POST "$BASE/api/admin/clock" -H 'Content-Type: application/json' -d "{\"time\":\"$NOW\"}" >/dev/null
run_case 13770000004 13770000005 false true | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  第三场处置:",d["enforcementAction"])'
NOW=$(iso 600); T_END=$(iso 720)
curl -s -X POST "$BASE/api/admin/clock" -H 'Content-Type: application/json' -d "{\"time\":\"$NOW\"}" >/dev/null
run_case 13770000006 13770000007 false true | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  第四场处置:",d["enforcementAction"])'

echo "== 被影响用户在后续预约上核销延时券（不可在原订单使用）=="
NOW=$(iso 800); T_END=$(iso 920)
curl -s -X POST "$BASE/api/admin/clock" -H 'Content-Type: application/json' -d "{\"time\":\"$NOW\"}" >/dev/null
CU1_ID=$(curl -s "$BASE/api/users" | python3 -c 'import sys,json; print([u["id"] for u in json.load(sys.stdin) if u["phone"]=="13770000001"][0])')
LB=$(curl -s -X POST "$BASE/api/bookings" -H 'Content-Type: application/json' -d \
  '{"userId":'$CU1_ID',"storeId":1,"seatId":'$A01',"type":"SINGLE","startTime":"'$NOW'","endTime":"'$T_END'","silenceRuleAcknowledged":true}')
LB_NO=$(echo "$LB" | python3 -c 'import sys,json; print(json.load(sys.stdin)["bookingNo"])')
curl -s -X POST "$BASE/api/bookings/$LB_NO/pay" >/dev/null
curl -s -X POST "$BASE/api/noise/vouchers/$VOUCHER/redeem" -H 'Content-Type: application/json' -d "{\"bookingNo\":\"$LB_NO\"}" \
  | python3 -c 'import sys,json; v=json.load(sys.stdin); print("  券",v["voucherNo"],"状态",v["status"],"核销于",v["redeemedBookingNo"])'
curl -s "$BASE/api/bookings/$LB_NO" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  后续订单延时后结束:",d["extendedEndTime"],"追加费用:",d["extraChargedAmount"])'

echo "== 运营：区域噪音诊断（布局问题 vs 提醒无效）+ 治理留痕 =="
WIN_FROM=$(iso -60); WIN_TO=$(iso 1000)
curl -s "$BASE/api/ops/stores/1/noise-zones?from=$WIN_FROM&to=$WIN_TO" | python3 -c '
import sys,json
for z in json.load(sys.stdin)["zones"]:
    if z["zone"]=="静音区A":
        print("  静音区A：案件",z["cases"],"不同违规者",z["distinctRespondents"],"累犯率",z["repeatRate"],
              "布局信号",z["layoutSignal"],"提醒无效信号",z["reminderSignal"],"诊断",z["diagnosis"])'
curl -s -X POST "$BASE/api/ops/stores/1/noise-zones/review" -H 'Content-Type: application/json' -d \
  '{"zone":"静音区A","action":"LAYOUT_ADJUST","note":"A01/A02 间加装隔音隔断并增设缓冲带","from":"'$WIN_FROM'","to":"'$WIN_TO'"}' \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print("  治理留痕:",d["reviewNo"],d["action"],"措施时诊断",d["diagnosis"])'
curl -s "$BASE/api/ops/stores/1/noise-zones?from=$WIN_FROM&to=$WIN_TO" | python3 -c '
import sys,json
z=[x for x in json.load(sys.stdin)["zones"] if x["zone"]=="静音区A"][0]
print("  留痕后最新措施:",z["latestReviewAction"],"措施后新增案件:",z["casesAfterLatestReview"])'
curl -s -X POST "$BASE/api/admin/clock" -H 'Content-Type: application/json' -d '{"time":null}' >/dev/null
echo E2E_NOISE_OK
