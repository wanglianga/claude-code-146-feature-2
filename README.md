# 城市共享自习室：预约 / 门禁延时与退费服务（后端）

Kotlin + Spring Boot 3 + JPA(H2/MySQL) + Redis 实现。覆盖单座、包间、夜间座、包月权益四类预约，
把**预约、支付、门禁、座位状态、延时/顺延、投诉、设备事件、计费退款、客服工单、运营指标**收敛到同一条
时间轴上，重点解决无人值守门店的连续事件一致性问题。

- Swagger UI：<http://localhost:8080/swagger-ui.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>
- 健康检查：<http://localhost:8080/actuator/health>

---

## 1. 业务能力

| 领域 | 能力 |
|---|---|
| 预约 | 单座按小时、包间按小时、夜间座一口价、包月（固定座 / 浮动权益）；记录门店、座位、时间、价格、押金、储物柜、静音规则确认 |
| 支付/押金 | 模拟支付（费用+押金）；取消未消费全退；爽约退押金；储物柜/设备损坏从押金扣赔 |
| 门禁进场 | 按 预约 → 支付 → 实名 → 时间窗（提前进场/迟到宽限）→ 门店停电 → 门禁故障 顺序判定；全部判定写门禁日志与监控事件编号 |
| 门禁离场 | 记录实际离场、是否延时/超时、储物柜与座位设备状态；正常/提前离场/门店责任三种结算 |
| 迟到顺延 | 宽限期内迟到可申请顺延（结束时间后移并计延时费），系统立即对后续订单做**碰撞预警** |
| 临时延时 | 进行中订单可延时；下一位已在场则拒绝；下一位已支付未进场则预警并在其进场时自动换座 |
| 连续事件 | **晚到 → 顺延 → 占下一位座位 → 下一位进场自动换座（GRANTED_REASSIGNED）→ 投诉自动归因 USER_LATE 并开工单** |
| 设备/环境 | 门禁打不开、空调故障、座位/储物柜损坏、门店临时停电；停电强制按门店责任清场结算，期间禁止进入 |
| 投诉 | 邻座噪音、座位被占、设备环境等；自动归因 `DEVICE_FAULT / USER_LATE / PRIVILEGE_ABUSE / USER_BEHAVIOR / OTHER`，同步生成客服工单 |
| 邻座噪音处置 | 记录投诉接收时间/座位号/是否已提醒；店员巡查确认后按“换电话区意愿→是否接受提醒→是否多次违规”决策树执行警告/换座/终止；**受理不结束订单**，终止才强制结算；被影响用户获与原订单绑定的延时券；区域高发诊断区分座位布局问题与管理提醒无效 |
| 客服工单 | 证据包聚合：预约、门禁日志、统一时间轴、座位传感器读数、退款记录、设备事件、监控编号、用户申诉、店员处理记录、噪音案件；责任判定（门店/用户/共担）与退款同事务结案 |
| 运营 | 上座率、延时率、投诉率、设备故障数、争议根因分布；按门店筛出“设备故障 vs 用户迟到/权益滥用”；调价倍率、座位布局、包月规则调整 |

## 2. 一致性设计（本服务的核心）

1. **放行即开始**：`/api/access/enter` 在一个事务内完成门禁判定、订单置 `IN_PROGRESS`、记录实际进场、
   写 Redis 座位占用与传感器状态——不会出现“门禁已放行但订单仍未开始”。
2. **退费即释放**：`/api/access/leave`（及停电强制清场）在一个事务内完成结算退款、押金退还、
   订单终态、Redis 占用释放、传感器清空——不会出现“退费完成但座位仍锁定”。
3. **座位临界区**：进/离场、自动换座在 Redis 短租锁 `seat:lock:{seatId}` 内执行；
   实时占用 `seat:state:{seatId}` 带 TTL，释放时校验占用者本人，防止误放他人座位。
4. **碰撞前置预警**：延时时即查出后续已支付订单并在双方时间轴上记录 `COLLISION_PREDICTED`；
   下一位进场若发现物理占用（Redis 或传感器），按同类型、同价或更低价稳定挑选备用座位自动换座。
5. **统一时间轴**：所有状态变化都追加不可变事件（`TimelineEvent`），按预约或门店+时间回放；
   每条门禁日志带确定性监控事件编号（`门店-闸机-结果-时间戳`）。
6. **退款上限保护**：任何退款不得超过 `已付 - 已退 - 押金扣赔`，防止重复赔付。
7. **噪音投诉处置顺序**：受理投诉只落案件/工单，**绝不直接结束被投诉订单**；店员巡查确认后，
   依次判断“是否愿意换电话区 → 是否接受店员提醒 → 是否多次违规”，只有“拒不换座、拒不接受提醒且累犯”
   才终止预约并在座位锁内强制结算释放；警告与换电话区均保留订单。补偿延时券与被影响用户的**原订单绑定**，
   只能在券主后续预约上核销；区域聚合按“不同违规者数量/累犯率”区分布局问题与提醒无效，运营措施留痕后
   持续统计措施后新增案件数。

## 3. 工程结构

```
src/main/kotlin/com/studyroom
├── domain/      枚举与 JPA 实体（门店/座位/传感器/套餐/预约/账目/退款/门禁/时间轴/事件/投诉/工单
│                /噪音案件/延时券/区域治理）
├── repo/        Spring Data 仓储（含时段重叠、可进场预约、在场订单、噪音累犯等查询）
├── support/     全局异常、Redis 座位锁、可覆写业务时钟
├── service/     BookingService / AccessService / BillingService / IncidentService
│                ComplaintService / NoiseComplaintService / SupportService /
│                OperationsService / SensorService
├── web/         REST 控制器与 DTO/视图映射
└── config/      OpenAPI 配置、启动种子数据
src/test/kotlin/com/studyroom/it   20 个端到端集成测试（自动拉起独立端口 Redis）
scripts/         三个 curl 版全链路演示脚本
```

## 4. 本地启动

### 方式 A：Docker Compose（一键，推荐）

```bash
docker compose up --build
# 应用：http://localhost:8080 ，Redis：6379，默认 H2 内存库
```

需要 MySQL 持久化时：

```bash
docker compose --profile mysql up --build
# 应用在 8081；MySQL 8.4 在 3306（studyroom/studyroom）
```

### 方式 B：本机直接运行

前置：JDK 17、Redis 7（`redis-server` 在 PATH 中）。

```bash
# 1) 启动 Redis
redis-server --port 6379 --daemonize yes --save "" --appendonly no

# 2) 启动应用（Gradle Wrapper 会自动下载 Gradle 8.10.2）
./gradlew bootRun
# 或先打包再运行
./gradlew bootJar
java -jar build/libs/studyroom-booking-1.0.0.jar
```

环境变量：`REDIS_HOST`(默认 localhost)、`REDIS_PORT`(6379)、
`DB_URL/DB_DRIVER/DB_USERNAME/DB_PASSWORD`；使用 MySQL 时加 `SPRING_PROFILES_ACTIVE=mysql`。

## 5. 测试账号（启动种子数据自动创建）

| 手机号 | 角色 | 实名 | 用途 |
|---|---|---|---|
| 13800000001 | 普通用户 小明 | 是 | 正常/迟到/顺延场景 |
| 13800000002 | 普通用户 小红 | 是 | “下一位用户”/被换座/投诉 |
| 13800000003 | 普通用户 小强 | **否** | 验证未实名门禁拒绝 |
| 13900000000 | 运营管理员 | 是 | 运营/客服操作（本版接口未做登录拦截，角色仅为数据标记） |

种子门店：`24h 无人值守旗舰店`（迟到宽限 15 分钟、提前进场 15 分钟、提前离场退 50% 剩余、超时 1.5 倍），
座位 A01/A02/A03（静音区单座 10 元/h，A02 带储物柜）、B01（轻音区）、**P01/P02（电话区，允许外放网课/通话，
噪音处置时将违规用户换至此区）**、R01（包间）、N01（夜间座），
包月套餐含“固定单座包月”与“浮动单座包月”。

> 说明：本服务聚焦后端一致性，未实现登录鉴权；接口中的 `userId/storeId` 由调用方直接传入，
> 便于演示与自动化验证。生产接入时在网关层绑定实名身份即可，门禁判定逻辑无需改动。

## 6. 验证方式

### 6.1 自动化测试（已实际执行）

```bash
./gradlew test
```

测试会自动在 **6390 端口**启动独立 `redis-server`（db15，与本地 6379 隔离）。
覆盖 20 个用例、6 个测试类：

- `NormalFlowIT`：支付→放行→订单 IN_PROGRESS→离场退押金释放；储物柜未清空扣 30 元押金
- `CollisionChainIT`：晚到 10 分钟→顺延 40 分钟→碰撞预警→B 进场自动换座 A01→A02→
  投诉归因 USER_LATE→双离场座位全释放；超宽限迟到拒绝 + 爽约扫描退押金
- `ExceptionalScenariosIT`：门禁故障拒绝并带事件编号；停电强制清场全额退剩余费用+押金且禁入；提前离场部分退费
- `MembershipIT`：固定座包月多次进出（权益保留、座位释放、押金不重复退）；到期包月权益扫描关闭并退押金；重叠包月权益拒绝；浮动包月现场分座
- `SupportAndOpsIT`：空调故障投诉自动归因 DEVICE_FAULT、证据包、申诉、门店责任退款结案、运营指标
- `NoiseComplaintIT`（新增 8 例）：
  - 受理记录时间/座位/已提醒，被诉订单不立即结束；同区域多人时必须指定被诉订单；非在场预约不可投诉
  - 警告保留订单；愿意换座则迁至电话区（旧座 Redis 占用与传感器释放、新座占用、订单仍 IN_PROGRESS）
  - 违规者巡查前已离场 → 成立发券但不处置；巡查不属实 → 驳回不发券
  - 首违警告留档、累犯拒不服从 → 终止预约并强制结算（剩余使用费不退、押金余额退回、座位释放）
  - 延时券与原订单绑定：禁止在原订单/他人/跨店使用，后续预约核销免费延时且不计延时费，不可重复核销
  - 区域诊断：3 名不同违规者 → `SEAT_LAYOUT_ISSUE`；同一人被警告后反复再犯 → `REMINDER_INEFFECTIVE`；
    运营措施留痕后可查看措施后新增案件数

最近一次执行结果：**20 tests, 0 failures / 0 errors**（`./gradlew test` BUILD SUCCESSFUL）。

### 6.2 HTTP 全链路手工验证（已实际执行）

先启动应用（方式 A 或 B），然后：

```bash
# 场景一：晚到 → 顺延 → 占下一座位 → 自动换座 → 投诉工单 → 退款 → 释放 → 运营分类
./scripts/e2e-collision-demo.sh

# 场景二：停电强制清场退费释放、停电禁入、空调故障投诉归因 DEVICE_FAULT、运营指标
./scripts/e2e-incident-demo.sh

# 场景三：邻座噪音受理（不结束订单）→ 巡查 → 换电话区/累犯终止决策树 →
#         延时券绑定原订单并核销 → 区域高发诊断（布局问题 vs 提醒无效）与治理留痕
./scripts/e2e-noise-demo.sh
```

> 演示脚本依赖新内存库；若应用里已跑过其他数据，重启应用即可（默认 H2 内存模式）。
> 为便于复现“未来时段”的预约，系统提供演示用时钟覆写接口
> `POST /api/admin/clock {"time":"2026-10-03T20:00:00Z"}`，传 `{"time":null}` 恢复真实时间。

### 6.3 接口速查（均在 Swagger UI 中可直接调用）

- 预约：`POST /api/bookings`、`/pay`、`/cancel`、`/{no}/postpone`、`/{no}/extend`、`GET /{no}`、`/{no}/timeline`
- 门禁：`POST /api/access/enter`、`POST /api/access/leave`、`POST /api/sensors/report`
- 门店/设备：`POST /api/incidents`、`POST /api/stores/{id}/blackout`、`POST /api/stores/no-show-scan`
- 投诉/工单：`POST /api/complaints`、`GET /api/tickets/{no}/evidence`、`POST /api/tickets/{no}/appeal`、`POST /api/tickets/{no}/decide`
- 邻座噪音：`POST /api/noise/cases`、`POST /api/noise/cases/{no}/patrol`、`POST /api/noise/cases/{no}/enforce`、
  `GET /api/noise/cases`、`GET /api/noise/cases/{no}`、`GET /api/noise/vouchers?userId=`、
  `GET /api/noise/bookings/{bookingNo}/vouchers`、`POST /api/noise/vouchers/{no}/redeem`
- 运营：`GET /api/ops/stores/{id}/metrics`、`GET /api/ops/stores/{id}/disputes`、
  `GET /api/ops/stores/{id}/noise-zones`、`POST /api/ops/stores/{id}/noise-zones/review`、
  `POST /api/ops/stores/{id}/price`、`POST /api/ops/stores/{id}/seats/{seatId}`、`POST /api/ops/plans/{planId}`

### 6.4 最小手工调用示例

```bash
# 下单（必须确认静音规则）→ 支付 → 进场 → 提前离场退费
curl -s -X POST localhost:8080/api/bookings -H 'Content-Type: application/json' -d '{
  "userId":1,"storeId":1,"seatId":1,"type":"SINGLE",
  "startTime":"2026-10-03T20:00:00Z","endTime":"2026-10-03T22:00:00Z",
  "silenceRuleAcknowledged":true}'
curl -s -X POST localhost:8080/api/bookings/<bookingNo>/pay
curl -s -X POST localhost:8080/api/access/enter -H 'Content-Type: application/json' \
  -d '{"userId":1,"storeId":1}'
curl -s -X POST localhost:8080/api/access/leave -H 'Content-Type: application/json' \
  -d '{"userId":1,"storeId":1,"mode":"EARLY_LEAVE"}'
```

### 6.5 邻座噪音处置最小调用示例

```bash
# 1) 受害者与外放者均在场后，受理投诉（系统记录接收时间；订单不会结束）
curl -s -X POST localhost:8080/api/noise/cases -H 'Content-Type: application/json' -d '{
  "complainantBookingNo":"BK...","description":"邻座外放网课","alreadyReminded":true,
  "respondentBookingNo":"BK..."}'
# 2) 店员巡查：SUBSTANTIATED / UNFOUNDED / OFFENDER_LEFT
curl -s -X POST localhost:8080/api/noise/cases/NC.../patrol -H 'Content-Type: application/json' \
  -d '{"result":"SUBSTANTIATED","note":"现场确认外放","staffUserId":4}'
# 3) 处置决策树（受理不结束订单；仅累犯且拒不服从才终止），成立后自动发绑定原订单的延时券
curl -s -X POST localhost:8080/api/noise/cases/NC.../enforce -H 'Content-Type: application/json' \
  -d '{"willingToMovePhoneZone":true,"acceptStaffWarning":false,"voucherMinutes":30,"staffUserId":4}'
# 4) 受害者后续预约核销延时券
curl -s -X POST localhost:8080/api/noise/vouchers/DV.../redeem -H 'Content-Type: application/json' \
  -d '{"bookingNo":"BK...后续预约"}'
# 5) 运营看区域诊断（SEAT_LAYOUT_ISSUE / REMINDER_INEFFECTIVE / MIXED）并对措施留痕
curl -s "localhost:8080/api/ops/stores/1/noise-zones?from=2026-10-01T00:00:00Z&to=2026-10-08T00:00:00Z"
curl -s -X POST localhost:8080/api/ops/stores/1/noise-zones/review -H 'Content-Type: application/json' -d '{
  "zone":"静音区A","action":"LAYOUT_ADJUST","note":"A01/A02 间加装隔音隔断"}'
```

## 7. 本地开发说明

- 构建：`./gradlew build`（编译 + 测试）
- 代码风格：Kotlin 2.0，JDK 17；`open-in-view=false`，控制器读接口显式声明只读事务
- 数据层默认 H2 内存库（MySQL 兼容模式），表结构由 Hibernate `ddl-auto=update` 维护；
  生产建议切换 MySQL 8（compose 已提供 profile）并引入迁移工具
- 仓库默认配置了阿里云 Maven 镜像以提升依赖下载成功率，不影响官方 Maven Central 解析
