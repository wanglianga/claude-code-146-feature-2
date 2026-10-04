#!/usr/bin/env bash
set -e
BASE=http://localhost:8080
T0=$(python3 -c "import datetime; print((datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)-datetime.timedelta(minutes=5)).isoformat().replace('+00:00','Z'))")
T1=$(python3 -c "import datetime; print((datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)+datetime.timedelta(minutes=55)).isoformat().replace('+00:00','Z'))")
T2=$(python3 -c "import datetime; print((datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)+datetime.timedelta(hours=2)).isoformat().replace('+00:00','Z'))")
curl -s -X POST "$BASE/api/admin/clock" -H 'Content-Type: application/json' -d '{"time":null}' >/dev/null

sid() { curl -s "$BASE/api/stores/1/seats" | python3 -c "import sys,json;print([s['id'] for s in json.load(sys.stdin) if s['code']=='$1'][0])"; }
A01=$(sid A01); A02=$(sid A02); A03=$(sid A03)

mk() { # phone name -> userId
  curl -s -X POST "$BASE/api/users" -H 'Content-Type: application/json' \
    -d "{\"phone\":\"$1\",\"name\":\"$2\",\"realNameVerified\":true}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])'
}
book_pay_enter() { # userId seatId -> bookingNo
  local BK
  BK=$(curl -s -X POST "$BASE/api/bookings" -H 'Content-Type: application/json' -d \
    "{\"userId\":$1,\"storeId\":1,\"seatId\":$2,\"type\":\"SINGLE\",\"startTime\":\"$T0\",\"endTime\":\"$T1\",\"silenceRuleAcknowledged\":true}" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["bookingNo"])')
  curl -s -X POST "$BASE/api/bookings/$BK/pay" >/dev/null
  curl -s -X POST "$BASE/api/access/enter" -H 'Content-Type: application/json' -d "{\"userId\":$1,\"storeId\":1}" >/dev/null
  echo "$BK"
}

echo "== 场景一：外放网课投诉 → 确认 → 自愿换电话区（订单不结束）→ 延时券绑定原订单"
O1=$(mk 13762000001 噪音用户甲)
V1=$(mk 13762000002 被影响用户甲)
OB1=$(book_pay_enter $O1 $A01)
VB1=$(book_pay_enter $V1 $A02)
NC=$(curl -s -X POST "$BASE/api/noise-complaints" -H 'Content-Type: application/json' -d "{
  \"complainantBookingNo\":\"$VB1\",\"offenderBookingNo\":\"$OB1\",
  \"description\":\"邻座外放网课声音很大\",\"remindedBeforePatrol\":true}" \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["noiseNo"])')
echo "  噪音投诉单=$NC（状态 RECEIVED，已记录投诉时间/座位/是否已提醒）"

curl -s -X POST "$BASE/api/noise-complaints/$NC/patrol" -H 'Content-Type: application/json' -d "{
  \"staffId\":900,\"result\":\"NOISE_CONFIRMED\",\"willingToMovePhoneZone\":true,
  \"acceptsReminder\":true,\"note\":\"到场听到外放讲课声\"}" \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print("  巡查成立: 状态=%s 违规累计=%s 建议处置=%s 延时券=%s" % (d["status"],d["violationCountAfter"],d["recommendedAction"],d["voucher"]["voucherNo"]))'
echo "  被投诉订单巡查后状态: $(curl -s "$BASE/api/bookings/$OB1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["status"])')（IN_PROGRESS，未立即结束）"

curl -s -X POST "$BASE/api/noise-complaints/$NC/handle" -H 'Content-Type: application/json' -d \
  '{"action":"MOVE_TO_PHONE_ZONE","staffId":900,"note":"引导至电话区"}' \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print("  处置:%s 换至座位=%s 状态=%s" % (d["actionTaken"],d["movedToSeatCode"],d["status"]))'
echo "  换区后订单状态/座位: $(curl -s "$BASE/api/bookings/$OB1" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["status"],d["seatCode"])')"

VC=$(curl -s "$BASE/api/vouchers/by-booking/$VB1" | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["voucherNo"])')
echo "  延时券=$VC（绑定原订单 $VB1）"
curl -s -X POST "$BASE/api/bookings/$VB1/extend" -H 'Content-Type: application/json' -d \
  "{\"minutes\":30,\"voucherNo\":\"$VC\"}" \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print("  用券延时30分钟: extended=%s 追加费用=%s" % (d["extended"],d["extraChargedAmount"]))'

echo "== 场景二：拒不配合的累犯 → 巡查确认 → 终止违规预约"
O2=$(mk 13762000003 噪音用户乙)
V2=$(mk 13762000004 被影响用户乙)
OB2=$(book_pay_enter $O2 $A03)
VB2=$(book_pay_enter $V2 $A01)
NC2=$(curl -s -X POST "$BASE/api/noise-complaints" -H 'Content-Type: application/json' -d "{
  \"complainantBookingNo\":\"$VB2\",\"offenderBookingNo\":\"$OB2\",
  \"description\":\"外放网课且不听劝阻\",\"remindedBeforePatrol\":true}" \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["noiseNo"])')
curl -s -X POST "$BASE/api/noise-complaints/$NC2/patrol" -H 'Content-Type: application/json' -d \
  '{"staffId":900,"result":"NOISE_CONFIRMED","willingToMovePhoneZone":false,"acceptsReminder":false}' >/dev/null
curl -s -X POST "$BASE/api/noise-complaints/$NC2/handle" -H 'Content-Type: application/json' -d \
  '{"action":"TERMINATE_BOOKING","staffId":900,"note":"拒绝提醒且拒绝换区"}' >/dev/null
curl -s "$BASE/api/bookings/$OB2" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("  违规订单状态=%s 已付=%s 已退=%s（押金退回、剩余使用费不退）" % (d["status"],d["paidAmount"],d["refundedAmount"]))'

echo "== 运营：按分区诊断“座位布局问题 vs 管理提醒无效”"
curl -s "$BASE/api/ops/stores/1/noise-zones?from=$T0&to=$T2" | python3 -c '
import sys,json
for z in json.load(sys.stdin):
    print("  分区=%s 成立投诉=%s 涉及用户=%s 座位分布=%s 诊断=%s 建议=%s" % (
        z["zone"],z["confirmedComplaints"],z["distinctOffenders"],z["complaintsBySeat"],z["diagnosis"],z["recommendedIntervention"]))'
echo "  -> 运营可 POST /api/ops/stores/1/noise-zones/静音区A/reinforce 加强静音提示，"
echo "     或 POST /api/ops/stores/1/seats/{seatId}/rezone 调整分区，再次投诉时即可区分布局问题/提醒无效"
echo E2E4_OK
