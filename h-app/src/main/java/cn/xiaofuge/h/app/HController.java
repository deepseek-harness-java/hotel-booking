package cn.xiaofuge.h.app;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 酒店预订 REST API */
@RestController
public class HController {

    private final HStore store;

    public HController(HStore store) { this.store = store; }

    @GetMapping("/api/rooms")
    public Map<String, Object> rooms(@RequestParam(required = false) Integer guests) {
        return Map.of("code", 0, "data", store.roomList(guests));
    }

    @GetMapping("/api/calendar")
    public Map<String, Object> calendar(@RequestParam String roomTypeId) {
        return Map.of("code", 0, "data", store.calendar(roomTypeId));
    }

    @PostMapping("/api/booking")
    public Map<String, Object> book(@RequestBody Map<String, Object> body) {
        return Map.of("code", 0, "data", store.book(
                str(body.get("roomTypeId")), str(body.get("checkIn")), str(body.get("checkOut")),
                (int) dbl(body.get("rooms"), 1), str(body.get("guest")), str(body.get("phone")),
                str(body.get("memberName")), str(body.get("note"))));
    }

    @GetMapping("/api/bookings")
    public Map<String, Object> bookings(@RequestParam(required = false) String status) {
        return Map.of("code", 0, "data", store.bookingList(status));
    }

    @GetMapping("/api/members")
    public Map<String, Object> members() {
        return Map.of("code", 0, "data", store.memberList());
    }

    @GetMapping("/api/stats")
    public Map<String, Object> stats() {
        return Map.of("code", 0, "data", store.stats());
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }
    private static double dbl(Object v, double dft) {
        try { return v == null ? dft : Double.parseDouble(String.valueOf(v)); }
        catch (Exception e) { return dft; }
    }
}
