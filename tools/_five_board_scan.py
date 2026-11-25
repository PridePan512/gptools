# -*- coding: utf-8 -*-
"""Find Sep-Oct 2026 streaks that reached 5+ limit-ups, then the 3 sessions after the break."""
import json
import time
import urllib.request
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / "_zt_cache.json"
OUT = ROOT / "_five_board_out.json"
FIELD = "199112,10,9001,330323,330324,330325,9002,330329,133971,133970,1968584,3475914,9003"
START = "2026-07-01"
END = "2026-10-23"
WINDOW_START = "2026-09-01"
WINDOW_END = "2026-10-23"


def get_json(url, referer, retries=4):
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(
                url,
                headers={"User-Agent": "Mozilla/5.0", "Referer": referer},
            )
            with urllib.request.urlopen(req, timeout=25) as response:
                return json.loads(response.read().decode("utf-8"))
        except Exception as exc:
            last = exc
            time.sleep(0.6 * (attempt + 1))
    raise last


def calendar():
    url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=sh000001,day,,,220,"
    data = get_json(url, "https://gu.qq.com/")
    rows = data["data"]["sh000001"]["day"]
    return [row[0] for row in rows if START <= row[0] <= END]


def fetch_pool(day):
    ymd = day.replace("-", "")
    page = 1
    rows = []
    total = None
    while True:
        url = (
            "https://data.10jqka.com.cn/dataapi/limit_up/limit_up_pool"
            f"?page={page}&limit=200&field={FIELD}"
            f"&filter=HS,GEM2STAR&order_field=330324&order_type=0&date={ymd}"
        )
        data = get_json(url, "https://data.10jqka.com.cn/datacenterph/limitup/limtupInfo.html")
        info = (data.get("data") or {}).get("info") or []
        meta = (data.get("data") or {}).get("page") or {}
        if total is None:
            total = int(meta.get("total") or 0)
        rows.extend(info)
        if len(rows) >= total or not info:
            break
        page += 1
        time.sleep(0.15)
    slim = []
    for row in rows:
        slim.append({
            "code": row.get("code"),
            "name": row.get("name"),
            "high_days": row.get("high_days"),
            "change_rate": row.get("change_rate"),
            "market_type": row.get("market_type"),
            "reason": row.get("reason_type"),
            "limit_up_type": row.get("limit_up_type"),
            "is_new": row.get("is_new"),
        })
    return slim


def symbol(code):
    return ("sh" if code.startswith(("5", "6", "9")) else "sz") + code


def ratio_of(code, name):
    upper = (name or "").upper()
    if code.startswith(("300", "301", "688", "689")):
        return Decimal("0.20")
    if "ST" in upper:
        return Decimal("0.05")
    return Decimal("0.10")


def board_name(code, name):
    upper = (name or "").upper()
    if code.startswith(("300", "301")):
        return "创业板20%"
    if code.startswith(("688", "689")):
        return "科创板20%"
    if "ST" in upper:
        return "主板ST5%"
    return "主板10%"


def money(value):
    return Decimal(str(value)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def limit_price(previous, ratio, up):
    factor = Decimal(1) + ratio if up else Decimal(1) - ratio
    return money(Decimal(str(previous)) * factor)


def is_limit(close, previous, ratio, up):
    return money(close) == limit_price(previous, ratio, up)


def fetch_bars(code):
    url = f"https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param={symbol(code)},day,,,160,"
    data = get_json(url, "https://gu.qq.com/")
    stock = data["data"][symbol(code)]
    rows = stock.get("day") or stock.get("qfqday")
    bars = []
    for row in rows:
        if row[0] < "2026-06-01" or row[0] > END:
            continue
        bars.append({
            "date": row[0],
            "open": float(row[1]),
            "close": float(row[2]),
            "high": float(row[3]),
            "low": float(row[4]),
        })
    return bars


def day_view(bar, previous, ratio, base_close):
    change = (bar["close"] / previous - 1) * 100
    gap = (bar["open"] / previous - 1) * 100
    up = is_limit(bar["close"], previous, ratio, True)
    down = is_limit(bar["close"], previous, ratio, False)
    sealed = abs(bar["open"] - bar["close"]) < 0.011 and abs(bar["high"] - bar["low"]) < 0.011
    if up and sealed:
        tag = "一字涨停"
    elif up:
        tag = "涨停"
    elif down and sealed:
        tag = "一字跌停"
    elif down:
        tag = "跌停"
    elif gap >= 2 and bar["close"] < bar["open"] and change < 0:
        tag = "高开低走"
    elif gap <= -2 and bar["close"] > bar["open"] and change > 0:
        tag = "低开高走"
    elif change >= 5:
        tag = "大阳"
    elif change <= -5:
        tag = "大阴"
    elif change >= 1:
        tag = "收涨"
    elif change <= -1:
        tag = "收跌"
    else:
        tag = "震荡"
    return {
        "date": bar["date"],
        "open": bar["open"],
        "high": bar["high"],
        "low": bar["low"],
        "close": bar["close"],
        "change": round(change, 2),
        "gap": round(gap, 2),
        "from_base": round((bar["close"] / base_close - 1) * 100, 2),
        "tag": tag,
        "limit_up": up,
        "limit_down": down,
    }


def main():
    days = calendar()
    cache = {}
    if CACHE.exists():
        cache = json.loads(CACHE.read_text(encoding="utf-8"))
    for index, day in enumerate(days, 1):
        if day in cache and cache[day].get("ok"):
            continue
        try:
            rows = fetch_pool(day)
            cache[day] = {"ok": True, "rows": rows}
            print(f"pool {index}/{len(days)} {day} {len(rows)}", flush=True)
        except Exception as exc:
            cache[day] = {"ok": False, "error": str(exc), "rows": []}
            print(f"pool FAIL {day} {exc}", flush=True)
        CACHE.write_text(json.dumps(cache, ensure_ascii=False), encoding="utf-8")
        time.sleep(0.12)

    failed = [day for day in days if not cache.get(day, {}).get("ok")]
    names = {}
    reasons = {}
    present = {day: {} for day in days}
    for day in days:
        for row in cache.get(day, {}).get("rows") or []:
            code = row.get("code")
            if not code:
                continue
            present[day][code] = row
            names[code] = row.get("name") or names.get(code) or code
            if row.get("reason"):
                reasons[code] = row["reason"]

    # Consecutive pool streaks. Height is counted on the index calendar.
    events = []
    codes = sorted({code for day in days for code in present[day]})
    for code in codes:
        streak = []
        for day in days:
            if code in present[day]:
                streak.append(day)
                continue
            if streak:
                events.append((code, streak))
            streak = []
        if streak:
            events.append((code, streak))

    qualified = []
    for code, streak in events:
        if len(streak) < 5:
            continue
        fifth = streak[4]
        peak_days = [day for day in streak if day >= WINDOW_START and day <= WINDOW_END]
        if not peak_days:
            continue
        # Reached 5 boards on a day inside Sep-Oct, or was already >=5 during the window.
        height_in_window = max(streak.index(day) + 1 for day in peak_days)
        if height_in_window < 5 and fifth > WINDOW_END:
            continue
        if fifth > WINDOW_END:
            continue
        qualified.append({
            "code": code,
            "name": names.get(code, code),
            "reason": reasons.get(code, ""),
            "streak": streak,
            "height": len(streak),
            "fifth": fifth,
            "last": streak[-1],
        })

    print(f"qualified streaks {len(qualified)} failed_days {failed}", flush=True)
    unique_codes = sorted({item["code"] for item in qualified})
    bars_by_code = {}
    for index, code in enumerate(unique_codes, 1):
        try:
            bars_by_code[code] = fetch_bars(code)
            print(f"kline {index}/{len(unique_codes)} {code} {len(bars_by_code[code])}", flush=True)
        except Exception as exc:
            bars_by_code[code] = []
            print(f"kline FAIL {code} {exc}", flush=True)
        time.sleep(0.08)

    results = []
    for item in qualified:
        code = item["code"]
        name = item["name"]
        ratio = ratio_of(code, name)
        bars = bars_by_code.get(code) or []
        by_date = {bar["date"]: bar for bar in bars}
        ordered = [bar for bar in bars if bar["date"] >= item["streak"][0]]
        # Trust price-based limit-ups from the first pool day, so a missed pool day
        # does not cut the streak, and a suspension does not count as the break.
        start_at = item["streak"][0]
        start_index = next((i for i, bar in enumerate(bars) if bar["date"] >= start_at), None)
        if start_index is None or start_index == 0:
            results.append({**item, "status": "无日线", "board": board_name(code, name)})
            continue
        # Walk back to the true streak start.
        cursor = start_index
        while cursor > 0 and is_limit(bars[cursor]["close"], bars[cursor - 1]["close"], ratio, True):
            cursor -= 1
        run = []
        while cursor < len(bars) and (
            cursor == 0 or is_limit(bars[cursor]["close"], bars[cursor - 1]["close"], ratio, True)
        ):
            if cursor == 0:
                break
            run.append(bars[cursor])
            cursor += 1
            if cursor >= len(bars):
                break
            if not is_limit(bars[cursor]["close"], bars[cursor - 1]["close"], ratio, True):
                break
        if len(run) < 5:
            results.append({
                **item,
                "status": "价格复核不足五板",
                "price_height": len(run),
                "board": board_name(code, name),
                "ratio": float(ratio),
            })
            continue
        fifth_date = run[4]["date"]
        last = run[-1]
        in_window = any(WINDOW_START <= bar["date"] <= WINDOW_END and i >= 4 for i, bar in enumerate(run))
        if not in_window:
            continue
        break_bar = bars[bars.index(last) + 1] if bars.index(last) + 1 < len(bars) else None
        if break_bar is None:
            results.append({
                "code": code,
                "name": name,
                "board": board_name(code, name),
                "reason": item["reason"],
                "height": len(run),
                "fifth": fifth_date,
                "last": last["date"],
                "last_close": last["close"],
                "status": "尚未断板",
                "ratio": float(ratio),
            })
            continue
        after = []
        last_pos = bars.index(last)
        # days after the break bar
        follow = bars[last_pos + 2:last_pos + 5]
        break_view = day_view(break_bar, last["close"], ratio, last["close"])
        previous = break_bar["close"]
        for bar in follow:
            after.append(day_view(bar, previous, ratio, break_bar["close"]))
            previous = bar["close"]
        results.append({
            "code": code,
            "name": name,
            "board": board_name(code, name),
            "reason": item["reason"],
            "height": len(run),
            "fifth": fifth_date,
            "last": last["date"],
            "last_close": last["close"],
            "status": "已断板" if len(after) == 3 else "断板未满三日",
            "ratio": float(ratio),
            "break": break_view,
            "after": after,
            "three_close": after[-1]["from_base"] if len(after) == 3 else None,
        })

    payload = {
        "failed_days": failed,
        "trading_days": days,
        "results": results,
    }
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print("wrote", OUT, "results", len(results), flush=True)


if __name__ == "__main__":
    main()
