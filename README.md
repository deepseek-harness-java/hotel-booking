# hotel-booking · AI 预订管家（DSH Java Native 插件场景案例 P56）

> 基于 **deepseek-harness-java（DSH）Java Native 插件机制** 的酒店预订场景案例：5 类房型（门市价/会员价/余量/权益）+ 7 天房态日历（周末上浮 20%）+ 预订下单（二次确认 + 会员价 + 超订拦截）+ 订单管理 + 入住率/ADR 经营分析，通过 `hotel-copilot` 插件接入 AI 助手，支持自然语言问房、比价、下单、查订单、看经营。

![总览](docs/images/01-overview.png)

## 一、项目组成

| 模块 | 说明 |
|------|------|
| `h-app` | Spring Boot 3.2 应用（端口 **18095**），酒店预订 REST API 与前端页面 |
| `h-plugin` | DSH Java Native 插件（`hotel-copilot`），打包 5 个 AI 工具 |

业务数据：5 类房型（高级大床房/豪华双床房/湖景套房/商务行政房/钟点房，56 间房）、3 条会员档案（金卡/钻石卡/银卡）、5 笔初始订单（已确认/已入住/已退房）、7 天房态日历（周五周六上浮 20%）。

## 二、插件工具（5 个）

| 工具 | 说明 |
|------|------|
| `room_list` | 房型列表：床型/面积/可住人数/门市价/会员价/余量/权益，可按人数过滤 |
| `calendar` | 房态日历：未来 7 天每日房价/剩余间数/紧张程度（周末上浮 20%） |
| `book` | 预订下单：二次确认后生成订单，会员自动享会员价，超订拦截 |
| `booking_list` | 订单列表：入住人/房型/日期/晚数/总价/状态，可按状态过滤 |
| `stats` | 经营指标：入住率/ADR/在店收入/分房型入住率/经营建议 |

## 三、REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/rooms?guests=` | 房型列表 |
| GET | `/api/calendar?roomTypeId=` | 7 天房态日历 |
| POST | `/api/booking` | 预订下单 `{roomTypeId,checkIn,checkOut,rooms,guest,phone,memberName,note}` |
| GET | `/api/bookings?status=` | 订单列表 |
| GET | `/api/members` | 会员列表 |
| GET | `/api/stats` | 经营指标 |
| POST | `/api/assistant/stream` | AI 助手 SSE（透传 DSH） |

## 四、快速开始

```bash
mvn clean package -DskipTests
java -Dserver.port=18095 -jar h-app/target/h-app-1.0.0-SNAPSHOT.jar

bash install_plugin.sh h-plugin/target/h-plugin-1.0.0-SNAPSHOT.jar \
  hotel-copilot 1.0.0-SNAPSHOT h-plugin-1.0.0-SNAPSHOT.jar "AI 预订助手"

open http://127.0.0.1:18095/
```

## 五、端到端验证

```bash
bash agent_stream.sh 127.0.0.1:8090 hotel-copilot "两个人住，推荐个性价比高的房型，周末贵吗？"
bash agent_stream.sh 127.0.0.1:8090 hotel-copilot "我是会员王先生，订一间高级大床房，9月25日入住9月27日退房"
bash agent_stream.sh 127.0.0.1:8090 hotel-copilot "电话 13812345678，确认下单"
bash agent_stream.sh 127.0.0.1:8090 hotel-copilot "现在酒店入住率多少？哪个房型卖得最好？"
```

5 个工具全部验证通过。验证截图：

| 截图 | 内容 |
|------|------|
| ![AI 房型推荐](docs/images/02-ai-rooms.png) | AI 比价后推荐高级大床房并说明周末上浮规则 |
| ![AI 房态日历](docs/images/03-ai-calendar.png) | AI 报 7 天房价与余量，标记售罄日并给替代日期 |
| ![AI 预订确认](docs/images/04-ai-booking.png) | AI 复述订单要素二次确认并提醒 9/26 仅剩 1 间 |

## 六、技术要点

- **价格模型**：总价 = 每晚价 × 晚数 × 间数；会员报姓名自动匹配 `memberPrice`；房态日历周五周六上浮 20%。
- **库存机**：`left = total - booked`，下单即扣减；`rooms > left` 直接拦截（「湖景套房仅剩 2 间，无法预订 5 间」）；left≤3 标记 scarce 前端红标。
- **二次确认**：系统提示强制「先查房型房态 → 复述房型+日期+间数+每晚价+总价 → 用户确认（含电话）→ 才可下单」。
- **超时修复**：SSE 代理遇长回答触发 `AsyncRequestTimeoutException`（默认 30s），配置 `spring.mvc.async.request-timeout: 180s` 解决 503/IOException: closed。
- **结论约束**：报价必须区分门市价与会员价；订单问题先报订单号；改期/取消先确认身份；数据全部来自工具返回。

## 七、目录结构

```
hotel-booking/
├── pom.xml                  # 父 pom（maven.compiler.parameters=true）
├── h-app/                   # Spring Boot 应用 (18095)
│   └── src/main/java/cn/xiaofuge/h/app/
│       ├── HotelApplication.java
│       ├── HStore.java        # 房型/房态/订单/会员/经营指标
│       ├── HController.java   # REST API
│       └── AssistantController.java # SSE 透传 DSH
├── h-plugin/                # DSH 插件 (hotel-copilot)
│   └── src/main/
│       ├── java/.../HotelPlugin.java  # 5 工具
│       └── resources/META-INF/       # plugin.yaml + SPI
└── docs/
    ├── 使用说明.md
    └── images/              # 验证截图 ×4
```
