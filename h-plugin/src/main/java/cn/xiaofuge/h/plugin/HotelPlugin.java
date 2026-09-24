package cn.xiaofuge.h.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** AI 预订助手插件：把 hotel-booking REST API 注册为 DSH Agent 工具 */
public class HotelPlugin extends AbstractHarnessPlugin {

    public static final String PLUGIN_ID = "hotel-copilot";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public HotelPlugin() { super(PLUGIN_ID); }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new RoomListTool(),
                new CalendarTool(),
                new BookTool(),
                new BookingListTool(),
                new StatsTool());
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("hotel-capabilities", 20, """
                ## AI 预订助手（云栖湖畔酒店 · 2026-09-24）
                - 用户问"有什么房型/多少钱/能住几人" → room_list（可传 guests 过滤；left≤3 标记 scarce 时主动提醒紧张）
                - 用户问"某天有没有房/房价多少/周末涨价吗" → calendar（roomTypeId 必填，返回 7 天房态与价格，周五周六上浮 20%）
                - 用户要订房 → book（roomTypeId/checkIn/checkOut/rooms/guest/phone 必填；会员报姓名享 memberPrice；
                  下单前必须先查房型与房态，并把「房型+日期+间数+每晚价+总价」完整复述请用户确认，未确认不得下单）
                - 用户问"我的订单/预订到几号/改期" → booking_list（可按 status：已确认/已入住/已退房）
                - 用户问"入住率/营业额/平均房价/经营怎么样" → stats
                - 回答要求：
                  1) 报价必须区分门市价与会员价，总价=每晚价×晚数×间数
                  2) 库存紧张（left≤3）或周末涨价必须主动告知
                  3) 订单问题先报订单号，改期/取消先确认身份（姓名+手机尾号）
                  4) 数据来自工具返回，禁止编造房型与房价
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__")) {
                return PluginHookResult.context("audit: hotel tool call.");
            }
            return null;
        });
    }

    private String get(String path, Map<String, Object> args) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl(args) + path)).GET().build());
    }

    private String post(String path, String jsonBody, Map<String, Object> args) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl(args) + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("appBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("HOTEL_APP_BASE_URL", "http://127.0.0.1:18095")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return "{\"error\":true,\"status\":" + resp.statusCode() + "}";
            return resp.body();
        } catch (Exception e) {
            return "{\"error\":true,\"message\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    private String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private String json(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    private class RoomListTool extends AbstractTool {
        @Override public String name() { return "room_list"; }
        @Override public String description() {
            return "房型列表：床型/面积/可住人数/门市价/会员价/剩余间数/权益，可按可住人数过滤。"
                    + "何时必须调用：介绍房型、比价、看余量。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema().prop("guests", stringSchema("可选：入住人数，数字")).build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String g = str(args, "guests");
            return ok(get("/api/rooms" + (g.isBlank() ? "" : "?guests=" + enc(g)), args));
        }
    }

    private class CalendarTool extends AbstractTool {
        @Override public String name() { return "calendar"; }
        @Override public String description() {
            return "房态日历：未来 7 天某房型的每日房价/剩余间数/紧张程度（周五周六上浮 20%）。"
                    + "何时必须调用：问某天有没有房、某天房价、规划入住日期。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema().prop("roomTypeId", stringSchema("房型 ID，如 rt01")).required("roomTypeId").build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            return ok(get("/api/calendar?roomTypeId=" + enc(str(args, "roomTypeId")), args));
        }
    }

    private class BookTool extends AbstractTool {
        @Override public String name() { return "book"; }
        @Override public String description() {
            return "预订下单：roomTypeId/checkIn(YYYY-MM-DD)/checkOut/rooms/guest/phone 必填，memberName 可享会员价，note 备注。"
                    + "下单前必须先复述订单要素并获用户确认。何时必须调用：用户确认预订方案后。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("roomTypeId", stringSchema("房型 ID，如 rt01"))
                    .prop("checkIn", stringSchema("入住日期，YYYY-MM-DD"))
                    .prop("checkOut", stringSchema("退房日期，YYYY-MM-DD"))
                    .prop("rooms", stringSchema("间数，数字"))
                    .prop("guest", stringSchema("入住人姓名"))
                    .prop("phone", stringSchema("联系电话"))
                    .prop("memberName", stringSchema("会员姓名，可空"))
                    .prop("note", stringSchema("备注，可空"))
                    .required("roomTypeId", "checkIn", "checkOut", "rooms", "guest", "phone")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String body = "{\"roomTypeId\":\"" + json(str(args, "roomTypeId"))
                    + "\",\"checkIn\":\"" + json(str(args, "checkIn"))
                    + "\",\"checkOut\":\"" + json(str(args, "checkOut"))
                    + "\",\"rooms\":" + (str(args, "rooms").isBlank() ? "1" : str(args, "rooms"))
                    + ",\"guest\":\"" + json(str(args, "guest"))
                    + "\",\"phone\":\"" + json(str(args, "phone"))
                    + "\",\"memberName\":\"" + json(str(args, "memberName"))
                    + "\",\"note\":\"" + json(str(args, "note")) + "\"}";
            return ok(post("/api/booking", body, args));
        }
    }

    private class BookingListTool extends AbstractTool {
        @Override public String name() { return "booking_list"; }
        @Override public String description() {
            return "订单列表：入住人/房型/日期/晚数/间数/总价/状态，可按状态过滤（已确认/已入住/已退房）。"
                    + "何时必须调用：查订单、问预订信息。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema().prop("status", stringSchema("可选：已确认 / 已入住 / 已退房")).build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String s = str(args, "status");
            return ok(get("/api/bookings" + (s.isBlank() ? "" : "?status=" + enc(s)), args));
        }
    }

    private class StatsTool extends AbstractTool {
        @Override public String name() { return "stats"; }
        @Override public String description() {
            return "经营指标：总房量/已订房量/入住率/平均房价 ADR/在店收入/分房型入住率/经营建议。"
                    + "何时必须调用：问入住率、营业额、经营情况。";
        }
        @Override public Map<String, Object> parameters() { return objectSchema().build(); }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            return ok(get("/api/stats", args));
        }
    }
}
