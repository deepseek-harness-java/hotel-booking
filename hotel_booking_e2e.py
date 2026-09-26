#!/usr/bin/env python3
"""hotel-booking E2E：通过业务应用 SSE 代理调用 DSH Agent，验证工具全链路。"""
import json, subprocess, sys

AGENT = "hotel-copilot"
URL = "http://127.0.0.1:18095/api/assistant/stream"

CASES = [
    ("T1 房型查询", "酒店有哪些房型可以预订？高级大床房多少钱？简洁回答", ["高级大床房", "488"]),
    ("T2 房态查询", "查一下酒店明天的大床房房态，还有几间？简洁回答", ["大床房"]),
    ("T3 预订下单", "我是新客户秦女士，电话13500002222，帮我在酒店订一间豪华双床房，9月28日入住 9月29日退房，直接下单告诉我订单号", ["B10", "豪华双床"]),
    ("T4 订单查询", "查一下酒店订单 B1005，谁订的？简洁回答", ["刘先生", "B1005"]),
    ("T5 经营统计", "酒店今天的经营情况怎么样？入住率多少？简洁回答", ["入住率", "平均房价"]),
]

def ask(message, timeout=170):
    payload = json.dumps({"message": message}, ensure_ascii=False)
    try:
        out = subprocess.run(
            ["curl", "-s", "--noproxy", "*", "-N", "-X", "POST", URL,
             "-H", "Content-Type: application/json", "-d", payload,
             "--max-time", str(timeout)],
            capture_output=True, text=True, timeout=timeout + 10).stdout
    except Exception as e:
        return "", f"curl 异常: {e}"
    text = []
    ev = ""
    for line in out.splitlines():
        line = line.rstrip("\r")
        if line.startswith("event:"):
            ev = line[6:].strip()
        elif line.startswith("data:"):
            s = line[5:].strip()
            if not s or s == "[DONE]" or ev != "chunk":
                continue
            try:
                j = json.loads(s)
                c = j.get("content", "")
                if c:
                    text.append(c)
            except Exception:
                pass
            ev = ""
    return "".join(text), out

def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    cases = CASES if not only else [c for c in CASES if c[0].startswith(only)]
    passed, failed = 0, []
    for name, q, keys in cases:
        reply, raw = ask(q)
        ok = all(k in reply for k in keys)
        print(f"[{'PASS' if ok else 'FAIL'}] {name}\n  Q: {q}\n  A: {reply[:200]}")
        if ok:
            passed += 1
        else:
            failed.append(name)
            if not reply:
                print(f"  raw 首行: {raw.splitlines()[:3] if raw else '(空)'}")
    print(f"\n===== hotel-booking E2E: {passed}/{len(cases)} PASS =====")

if __name__ == "__main__":
    main()
