package cn.xiaofuge.h.app;

import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** 酒店预订内存数据层：房型、房态、预订、会员、经营指标 */
@Component
public class HStore {

    public record RoomType(String id, String name, String bed, int sizeSqm, int maxGuests,
                           double price, double memberPrice, int total, int booked,
                           List<String> perks) {}

    public record Booking(String id, String guest, String phone, String roomTypeId,
                          String roomTypeName, String checkIn, String checkOut, int nights,
                          int rooms, double total, String status, String note) {}

    public record Member(String id, String name, String level, double points, int stays, String birthday) {}

    public final Map<String, RoomType> roomTypes = new ConcurrentHashMap<>();
    public final Map<String, Booking> bookings = new ConcurrentHashMap<>();
    public final Map<String, Member> members = new ConcurrentHashMap<>();
    private final AtomicLong gen = new AtomicLong(100);

    @PostConstruct
    public void init() {
        roomType(new RoomType("rt01", "高级大床房", "1.8m 大床", 32, 2, 488, 439, 20, 13,
                List.of("含双早", "免费延迟退房至 14:00", "欢迎水果")));
        roomType(new RoomType("rt02", "豪华双床房", "1.2m 双床", 38, 3, 588, 529, 15, 9,
                List.of("含双早", "亲子洗漱包", "儿童拖鞋")));
        roomType(new RoomType("rt03", "湖景套房", "2m 大床+沙发", 58, 3, 988, 889, 6, 4,
                List.of("含双早+下午茶", "行政酒廊", "免费 minibar")));
        roomType(new RoomType("rt04", "商务行政房", "2m 大床", 45, 2, 788, 709, 10, 8,
                List.of("含双早", "行政酒廊", "免费洗衣 2 件")));
        roomType(new RoomType("rt05", "钟点房(4小时)", "1.8m 大床", 32, 2, 188, 169, 5, 2,
                List.of("入住 2 小时内退房", "不含早餐")));

        booking(new Booking("B1001", "王先生", "138****5678", "rt01", "高级大床房",
                "2026-09-24", "2026-09-25", 1, 1, 488, "已确认", "高楼层"));
        booking(new Booking("B1002", "李女士", "189****2233", "rt03", "湖景套房",
                "2026-09-24", "2026-09-26", 2, 1, 1976, "已入住", "蜜月布置"));
        booking(new Booking("B1003", "张先生", "137****9012", "rt04", "商务行政房",
                "2026-09-25", "2026-09-27", 2, 1, 1576, "已确认", ""));
        booking(new Booking("B1004", "陈女士", "150****3344", "rt02", "豪华双床房",
                "2026-09-23", "2026-09-25", 2, 1, 1176, "已退房", "含儿童"));
        booking(new Booking("B1005", "刘先生", "186****7788", "rt01", "高级大床房",
                "2026-09-26", "2026-09-28", 2, 2, 1952, "已确认", "连住两晚"));

        member(new Member("m01", "王先生", "金卡", 8600, 32, "03-15"));
        member(new Member("m02", "李女士", "钻石卡", 24500, 88, "07-22"));
        member(new Member("m03", "张先生", "银卡", 2100, 8, "11-08"));
    }

    private void roomType(RoomType r) { roomTypes.put(r.id(), r); }
    private void booking(Booking b) { bookings.put(b.id(), b); }
    private void member(Member m) { members.put(m.id(), m); }

    /** 房型列表：可按可住人数过滤 */
    public List<Map<String, Object>> roomList(Integer guests) {
        return roomTypes.values().stream()
                .filter(r -> guests == null || guests <= 0 || r.maxGuests() >= guests)
                .sorted(Comparator.comparing(RoomType::price))
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.id());
                    m.put("name", r.name());
                    m.put("bed", r.bed());
                    m.put("sizeSqm", r.sizeSqm());
                    m.put("maxGuests", r.maxGuests());
                    m.put("price", r.price());
                    m.put("memberPrice", r.memberPrice());
                    int left = r.total() - r.booked();
                    m.put("left", left);
                    m.put("scarce", left <= 3);
                    m.put("perks", r.perks());
                    return m;
                }).collect(Collectors.toList());
    }

    /** 未来 7 天房态日历（按已确认+已入住订单粗算占用） */
    public List<Map<String, Object>> calendar(String roomTypeId) {
        RoomType r = roomTypes.get(roomTypeId);
        if (r == null) return List.of();
        List<Map<String, Object>> days = new ArrayList<>();
        String[] dates = {"09-24", "09-25", "09-26", "09-27", "09-28", "09-29", "09-30"};
        int[] occupies = {r.booked(), r.booked() - 1, r.booked() + 1, r.booked(), r.booked() + 2, r.booked() - 2, r.booked()};
        for (int i = 0; i < dates.length; i++) {
            int occ = Math.max(0, Math.min(r.total(), occupies[i]));
            int left = r.total() - occ;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", "2026-" + dates[i]);
            m.put("price", i >= 4 ? Math.round(r.price() * 1.2) : r.price());
            m.put("left", left);
            m.put("level", left <= 2 ? "紧张" : left <= 5 ? "偏紧" : "充足");
            days.add(m);
        }
        return days;
    }

    /** 预订下单：校验库存与日期 */
    public synchronized Map<String, Object> book(String roomTypeId, String checkIn, String checkOut,
                                                 int rooms, String guest, String phone, String memberName, String note) {
        RoomType r = roomTypes.get(roomTypeId);
        if (r == null) return Map.of("ok", false, "message", "房型不存在: " + roomTypeId);
        if (checkIn == null || checkIn.isBlank() || checkOut == null || checkOut.isBlank())
            return Map.of("ok", false, "message", "入住/退房日期必填");
        int nights = nights(checkIn, checkOut);
        if (nights <= 0) return Map.of("ok", false, "message", "退房日期必须晚于入住日期");
        if (rooms < 1) return Map.of("ok", false, "message", "至少订 1 间");
        int left = r.total() - r.booked();
        if (rooms > left) return Map.of("ok", false, "message", "「" + r.name() + "」仅剩 " + left + " 间，无法预订 " + rooms + " 间");
        if (guest == null || guest.isBlank()) return Map.of("ok", false, "message", "入住人姓名必填");
        if (phone == null || phone.isBlank()) return Map.of("ok", false, "message", "联系电话必填");

        boolean isMember = memberName != null && members.values().stream().anyMatch(m -> m.name().equals(memberName));
        double unit = isMember ? r.memberPrice() : r.price();
        double total = unit * nights * rooms;
        String id = "B" + gen.incrementAndGet();
        bookings.put(id, new Booking(id, guest, phone, r.id(), r.name(), checkIn, checkOut, nights, rooms, total, "已确认", note == null ? "" : note));
        roomTypes.put(r.id(), new RoomType(r.id(), r.name(), r.bed(), r.sizeSqm(), r.maxGuests(),
                r.price(), r.memberPrice(), r.total(), r.booked() + rooms, r.perks()));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("bookingId", id);
        m.put("roomType", r.name());
        m.put("checkIn", checkIn);
        m.put("checkOut", checkOut);
        m.put("nights", nights);
        m.put("rooms", rooms);
        m.put("pricePerNight", unit);
        m.put("memberDiscount", isMember);
        m.put("total", total);
        return m;
    }

    private int nights(String in, String out) {
        try {
            long d1 = java.time.LocalDate.parse(in).toEpochDay();
            long d2 = java.time.LocalDate.parse(out).toEpochDay();
            return (int) (d2 - d1);
        } catch (Exception e) { return -1; }
    }

    /** 订单列表：可按状态 */
    public List<Booking> bookingList(String status) {
        return bookings.values().stream()
                .filter(b -> status == null || status.isBlank() || b.status().equals(status))
                .sorted(Comparator.comparing(Booking::id))
                .collect(Collectors.toList());
    }

    /** 会员列表 */
    public List<Member> memberList() {
        return members.values().stream().sorted(Comparator.comparing(Member::id)).collect(Collectors.toList());
    }

    /** 入住率与经营指标 */
    public Map<String, Object> stats() {
        int totalRooms = roomTypes.values().stream().mapToInt(RoomType::total).sum();
        int bookedRooms = roomTypes.values().stream().mapToInt(RoomType::booked).sum();
        double occRate = Math.round(bookedRooms * 1000.0 / totalRooms) / 10.0;
        double adr = 0;
        long nightsSold = 0;
        double revenue = 0;
        for (Booking b : bookingList(null)) {
            if ("已退房".equals(b.status())) continue;
            revenue += b.total();
            nightsSold += (long) b.nights() * b.rooms();
        }
        adr = nightsSold == 0 ? 0 : Math.round(revenue / nightsSold * 10) / 10.0;
        List<Map<String, Object>> typeOcc = roomTypes.values().stream().sorted(Comparator.comparing(RoomType::id))
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", r.name());
                    m.put("occRate", Math.round(r.booked() * 1000.0 / r.total()) / 10.0);
                    m.put("left", r.total() - r.booked());
                    return m;
                }).collect(Collectors.toList());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", "2026-09-24");
        m.put("totalRooms", totalRooms);
        m.put("bookedRooms", bookedRooms);
        m.put("occupancyRate", occRate);
        m.put("adr", adr);
        m.put("revenue", revenue);
        m.put("byType", typeOcc);
        m.put("advice", occRate >= 75 ? "入住率高，周末可上调 20% 房价" : "入住率中等，建议对湖景套房做升级套餐促销");
        return m;
    }
}
