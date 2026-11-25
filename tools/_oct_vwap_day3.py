# -*- coding: utf-8 -*-
"""October 2026 连板: if day-2 afternoon holds intraday VWAP, how does day-3 trade."""
import json
import time
import urllib.request
from collections import Counter
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from statistics import mean, median

ROOT = Path(__file__).resolve().parent
FIELD = "199112,10,9001,330323,330324,330325,9002,330329,133971,133970,1968584,3475914,9003"
HORIZON = "2026-10-26"
MONTHS = {
    "oct": ("2026-10-01", "2026-10-26", "2026-10-08", 1200, "_oct_vwap_cache.json", "_oct_vwap_result.json"),
    "sep": ("2026-09-01", "2026-09-30", "2026-09-01", 2000, "_sep_vwap_cache.json", "_sep_vwap_result.json"),
}
MONTH_START, MONTH_END, BAR_START, M5_DATALEN, CACHE_NAME, OUT_NAME = MONTHS["oct"]
CACHE = ROOT / CACHE_NAME
OUT = ROOT / OUT_NAME


def get_json(url, referer, retries=4):
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(
                url,
                headers={"User-Agent": "Mozilla/5.0", "Referer": referer},
            )
            with urllib.request.urlopen(req, timeout=25) as response:
                raw = response.read().decode("utf-8")
            return json.loads(raw)
        except Exception as exc:
            last = exc
            time.sleep(0.5 * (attempt + 1))
    raise last


def load_cache():
    if CACHE.exists():
        return json.loads(CACHE.read_text(encoding="utf-8"))
    return {"pools": {}, "daily": {}, "m5": {}}


def save_cache(cache):
    CACHE.write_text(json.dumps(cache, ensure_ascii=False), encoding="utf-8")


def calendar():
    url = (
        "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
        "CN_MarketData.getKLineData?symbol=sh000001&scale=240&ma=no&datalen=120"
    )
    rows = get_json(url, "https://finance.sina.com.cn/")
    return [row["day"] for row in rows if row["day"] <= HORIZON]


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
        time.sleep(0.12)
    slim = []
    for row in rows:
        slim.append({
            "code": row.get("code"),
            "name": row.get("name"),
            "high_days": row.get("high_days"),
            "high_days_value": row.get("high_days_value"),
            "change_rate": row.get("change_rate"),
            "market_type": row.get("market_type"),
            "reason": row.get("reason_type"),
            "limit_up_type": row.get("limit_up_type"),
            "is_new": row.get("is_new"),
            "open_num": row.get("open_num"),
        })
    return slim


def symbol_qq(code):
    return ("sh" if code.startswith(("5", "6", "9")) else "sz") + code


def symbol_sina(code):
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
    if previous is None or previous <= 0:
        return False
    return money(close) == limit_price(previous, ratio, up)


def height_of(row):
    value = row.get("high_days_value")
    if isinstance(value, int) and value > 0:
        days = value >> 16
        boards = value & 0xFFFF
        if days > 0 and boards > 0:
            return days, boards
    return None, None


def fetch_daily(code):
    url = (
        "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
        f"CN_MarketData.getKLineData?symbol={symbol_sina(code)}&scale=240&ma=no&datalen=160"
    )
    rows = get_json(url, "https://finance.sina.com.cn/") or []
    bars = []
    for row in rows:
        if row["day"] < "2026-08-01" or row["day"] > HORIZON:
            continue
        bars.append({
            "date": row["day"],
            "open": float(row["open"]),
            "close": float(row["close"]),
            "high": float(row["high"]),
            "low": float(row["low"]),
            "volume": float(row["volume"]),
        })
    return bars


def fetch_m5(code):
    url = (
        "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
        f"CN_MarketData.getKLineData?symbol={symbol_sina(code)}&scale=5&ma=no&datalen={M5_DATALEN}"
    )
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0", "Referer": "https://finance.sina.com.cn/"})
    last = None
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=25) as response:
                raw = response.read().decode("utf-8")
            rows = json.loads(raw)
            break
        except Exception as exc:
            last = exc
            rows = None
            time.sleep(0.5 * (attempt + 1))
    if not rows:
        raise last or RuntimeError("empty m5")
    bars = []
    for row in rows:
        day = row["day"]
        if day < BAR_START or day > HORIZON + " 15:00:00":
            continue
        bars.append({
            "day": day,
            "open": float(row["open"]),
            "high": float(row["high"]),
            "low": float(row["low"]),
            "close": float(row["close"]),
            "volume": float(row["volume"]),
        })
    return bars


def attach_vwap(bars):
    grouped = {}
    for bar in bars:
        grouped.setdefault(bar["day"][:10], []).append(bar)
    for day, items in grouped.items():
        items.sort(key=lambda item: item["day"])
        cum_amt = 0.0
        cum_vol = 0.0
        for item in items:
            typical = (item["high"] + item["low"] + item["close"]) / 3.0
            vol = item["volume"]
            if vol > 0:
                cum_amt += typical * vol
                cum_vol += vol
            item["vwap"] = cum_amt / cum_vol if cum_vol else item["close"]
            item["time"] = item["day"][11:19]
    return grouped


def hold_flags(day_bars):
    if not day_bars or len(day_bars) < 40:
        return None
    last = day_bars[-1]
    close = last["close"]
    vwap = last["vwap"]
    tol = 0.998
    afternoon = [bar for bar in day_bars if bar["time"] >= "13:05:00"]
    last_hour = [bar for bar in day_bars if bar["time"] >= "14:05:00"]
    morning = [bar for bar in day_bars if bar["time"] <= "11:30:00"]
    tail = [bar for bar in day_bars if bar["time"] >= "14:50:00"]
    if not afternoon or not last_hour:
        return None

    def below(bars):
        return sum(1 for bar in bars if bar["close"] < bar["vwap"] * tol)

    close_above = close >= vwap * tol
    last_hour_hold = close_above and all(bar["close"] >= bar["vwap"] * tol for bar in last_hour)
    afternoon_hold = close_above and all(bar["close"] >= bar["vwap"] * tol for bar in afternoon)
    loose = (
        close_above
        and below(afternoon) <= max(2, int(len(afternoon) * 0.2))
        and all(bar["close"] >= bar["vwap"] * tol for bar in tail)
    )
    morning_broke = any(bar["close"] < bar["vwap"] * tol for bar in morning)
    return {
        "close": round(close, 2),
        "vwap": round(vwap, 4),
        "close_vs_vwap": round((close / vwap - 1) * 100, 2) if vwap else None,
        "close_above": close_above,
        "last_hour_hold": last_hour_hold,
        "afternoon_hold": afternoon_hold,
        "loose_hold": loose,
        "morning_broke": morning_broke,
        "afternoon_below": below(afternoon),
        "last_hour_below": below(last_hour),
        "bars": len(day_bars),
    }


def pct(a, b):
    return round((a / b - 1) * 100, 2)


def pattern_of(day, previous, ratio):
    gap = pct(day["open"], previous)
    change = pct(day["close"], previous)
    high = pct(day["high"], previous)
    low = pct(day["low"], previous)
    up = is_limit(day["close"], previous, ratio, True)
    down = is_limit(day["close"], previous, ratio, False)
    sealed = abs(day["open"] - day["close"]) < 0.011 and abs(day["high"] - day["low"]) < 0.011
    if up and sealed:
        tag = "一字涨停"
    elif up:
        tag = "涨停"
    elif down and sealed:
        tag = "一字跌停"
    elif down:
        tag = "跌停"
    elif gap >= 1 and day["close"] >= day["open"] and change > 0:
        tag = "高开高走"
    elif gap >= 1 and day["close"] < day["open"]:
        tag = "高开低走"
    elif gap <= -1 and day["close"] > day["open"]:
        tag = "低开高走"
    elif gap <= -1 and change < 0:
        tag = "低开低走"
    elif high >= 4 and change <= high - 4 and day["close"] < day["open"]:
        tag = "冲高回落"
    elif change >= 3:
        tag = "震荡走强"
    elif change <= -3:
        tag = "震荡走弱"
    else:
        tag = "窄幅震荡"
    if change >= 9.5 or (up):
        bucket = "收盘涨停或近涨停"
    elif change >= 5:
        bucket = "收涨5%以上"
    elif change > 0:
        bucket = "收涨0到5%"
    elif change > -5:
        bucket = "收跌0到5%"
    elif down:
        bucket = "收盘跌停"
    else:
        bucket = "收跌5%以上"
    span = day["high"] - day["low"]
    close_pos = round((day["close"] - day["low"]) / span, 2) if span > 0 else 1
    return {
        "gap": gap,
        "change": change,
        "high": high,
        "low": low,
        "tag": tag,
        "bucket": bucket,
        "limit_up": up,
        "limit_down": down,
        "close_pos": close_pos,
        "intraday": pct(day["close"], day["open"]),
    }


def summarize(rows):
    if not rows:
        return {"n": 0}
    changes = [row["d3"]["change"] for row in rows]
    gaps = [row["d3"]["gap"] for row in rows]
    highs = [row["d3"]["high"] for row in rows]
    lows = [row["d3"]["low"] for row in rows]
    intraday = [row["d3"]["intraday"] for row in rows]
    tags = Counter(row["d3"]["tag"] for row in rows)
    buckets = Counter(row["d3"]["bucket"] for row in rows)
    return {
        "n": len(rows),
        "mean_close": round(mean(changes), 2),
        "median_close": round(median(changes), 2),
        "win_rate": round(sum(c > 0 for c in changes) / len(rows), 3),
        "flat_rate": round(sum(c == 0 for c in changes) / len(rows), 3),
        "limit_up_rate": round(sum(row["d3"]["limit_up"] for row in rows) / len(rows), 3),
        "limit_down_rate": round(sum(row["d3"]["limit_down"] for row in rows) / len(rows), 3),
        "mean_gap": round(mean(gaps), 2),
        "median_gap": round(median(gaps), 2),
        "gap_up_rate": round(sum(g > 0 for g in gaps) / len(rows), 3),
        "mean_high": round(mean(highs), 2),
        "median_high": round(median(highs), 2),
        "mean_low": round(mean(lows), 2),
        "median_low": round(median(lows), 2),
        "mean_open_to_close": round(mean(intraday), 2),
        "median_open_to_close": round(median(intraday), 2),
        "open_to_close_win": round(sum(v > 0 for v in intraday) / len(rows), 3),
        "tags": dict(tags),
        "buckets": dict(buckets),
        "avg_close_pos": round(mean(row["d3"]["close_pos"] for row in rows), 2),
    }


def height_bucket(boards):
    if boards <= 2:
        return "2板"
    if boards == 3:
        return "3板"
    return "4板及以上"


def day2_change_bucket(change):
    if change >= 5:
        return "次日收涨5%以上"
    if change > 0:
        return "次日收涨0到5%"
    if change > -5:
        return "次日收跌0到5%"
    return "次日收跌5%以上"


def main():
    import sys
    global MONTH_START, MONTH_END, BAR_START, M5_DATALEN, CACHE, OUT
    month = sys.argv[1] if len(sys.argv) > 1 else "oct"
    if month not in MONTHS:
        raise SystemExit("usage: _oct_vwap_day3.py [sep|oct]")
    MONTH_START, MONTH_END, BAR_START, M5_DATALEN, cache_name, out_name = MONTHS[month]
    CACHE = ROOT / cache_name
    OUT = ROOT / out_name
    all_days = calendar()
    days = [day for day in all_days if MONTH_START <= day <= MONTH_END]
    print(month, "signal days", days, flush=True)
    cache = load_cache()
    for index, day in enumerate(days, 1):
        slot = cache["pools"].get(day)
        if slot and slot.get("ok"):
            continue
        try:
            rows = fetch_pool(day)
            cache["pools"][day] = {"ok": True, "rows": rows}
            print(f"pool {index}/{len(days)} {day} {len(rows)}", flush=True)
        except Exception as exc:
            cache["pools"][day] = {"ok": False, "error": str(exc), "rows": []}
            print(f"pool FAIL {day} {exc}", flush=True)
        save_cache(cache)
        time.sleep(0.1)

    present = {}
    for day in days:
        present[day] = {}
        for row in cache["pools"].get(day, {}).get("rows") or []:
            code = row.get("code")
            if code:
                present[day][code] = row

    next_day = {day: all_days[i + 1] for i, day in enumerate(all_days) if i + 1 < len(all_days)}
    events = []
    for day in days:
        day2 = next_day.get(day)
        day3 = next_day.get(day2) if day2 else None
        if not day2 or not day3:
            continue
        for code, row in present[day].items():
            span, boards = height_of(row)
            if not boards or boards < 2 or span != boards:
                continue
            name = row.get("name") or code
            if row.get("is_new"):
                continue
            if "ST" in name.upper():
                continue
            events.append({
                "code": code,
                "name": name,
                "day1": day,
                "day2": day2,
                "day3": day3,
                "boards": boards,
                "reason": row.get("reason") or "",
                "limit_up_type": row.get("limit_up_type") or "",
                "open_num": row.get("open_num"),
                "board": board_name(code, name),
            })
    print("raw events", len(events), "codes", len({e['code'] for e in events}), flush=True)

    codes = sorted({event["code"] for event in events})
    for index, code in enumerate(codes, 1):
        if cache["daily"].get(code, {}).get("ok"):
            continue
        try:
            cache["daily"][code] = {"ok": True, "bars": fetch_daily(code)}
            print(f"daily {index}/{len(codes)} {code} {len(cache['daily'][code]['bars'])}", flush=True)
        except Exception as exc:
            cache["daily"][code] = {"ok": False, "error": str(exc), "bars": []}
            print(f"daily FAIL {code} {exc}", flush=True)
        if index % 20 == 0:
            save_cache(cache)
        time.sleep(0.05)
    save_cache(cache)

    for index, code in enumerate(codes, 1):
        if cache["m5"].get(code, {}).get("ok"):
            continue
        try:
            cache["m5"][code] = {"ok": True, "bars": fetch_m5(code)}
            print(f"m5 {index}/{len(codes)} {code} {len(cache['m5'][code]['bars'])}", flush=True)
        except Exception as exc:
            cache["m5"][code] = {"ok": False, "error": str(exc), "bars": []}
            print(f"m5 FAIL {code} {exc}", flush=True)
        if index % 15 == 0:
            save_cache(cache)
        time.sleep(0.08)
    save_cache(cache)

    enriched = []
    skipped = Counter()
    for event in events:
        code = event["code"]
        name = event["name"]
        ratio = ratio_of(code, name)
        daily = {bar["date"]: bar for bar in cache["daily"].get(code, {}).get("bars") or []}
        ordered = [bar["date"] for bar in cache["daily"].get(code, {}).get("bars") or []]
        if event["day1"] not in daily or event["day2"] not in daily or event["day3"] not in daily:
            skipped["缺日线"] += 1
            continue
        i1 = ordered.index(event["day1"])
        if i1 == 0:
            skipped["无前收"] += 1
            continue
        prev1 = daily[ordered[i1 - 1]]["close"]
        day1 = daily[event["day1"]]
        day2 = daily[event["day2"]]
        day3 = daily[event["day3"]]
        if not is_limit(day1["close"], prev1, ratio, True):
            skipped["日线未复核连板"] += 1
            continue
        day2_limit = is_limit(day2["close"], day1["close"], ratio, True)
        day2_view = pattern_of(day2, day1["close"], ratio)
        day3_view = pattern_of(day3, day2["close"], ratio)
        grouped = attach_vwap(cache["m5"].get(code, {}).get("bars") or [])
        flags = hold_flags(grouped.get(event["day2"]) or [])
        if flags is None and not day2_limit:
            skipped["缺分钟"] += 1
            continue
        item = {
            **event,
            "ratio": float(ratio),
            "day1_close": day1["close"],
            "day2_limit": day2_limit,
            "day2_sealed": day2_view["tag"] == "一字涨停",
            "d2": day2_view,
            "d3": day3_view,
            "vwap": flags,
            "from_board_close": pct(day3["close"], day1["close"]),
        }
        enriched.append(item)
    print("enriched", len(enriched), "skipped", dict(skipped), flush=True)

    def group_stats(rows):
        base = summarize(rows)
        by_height = {}
        by_board = {}
        by_d2 = {}
        by_shape = {}
        for row in rows:
            by_height.setdefault(height_bucket(row["boards"]), []).append(row)
            by_board.setdefault(row["board"], []).append(row)
            by_d2.setdefault(day2_change_bucket(row["d2"]["change"]), []).append(row)
            if row.get("vwap"):
                if row["vwap"]["morning_broke"]:
                    by_shape.setdefault("上午破均线后下午站稳", []).append(row)
                else:
                    by_shape.setdefault("全天都在均线上方", []).append(row)
        base["by_height"] = {key: summarize(value) for key, value in sorted(by_height.items())}
        base["by_board"] = {key: summarize(value) for key, value in sorted(by_board.items())}
        base["by_day2"] = {key: summarize(value) for key, value in by_d2.items()}
        base["by_shape"] = {key: summarize(value) for key, value in by_shape.items()}
        return base

    hold, loose, fail, continued = [], [], [], []
    for row in enriched:
        if row["day2_limit"]:
            continued.append(row)
            continue
        flags = row["vwap"] or {}
        if flags.get("last_hour_hold"):
            hold.append(row)
        else:
            fail.append(row)
        if flags.get("loose_hold"):
            loose.append(row)

    def compact(rows):
        out = []
        for row in sorted(rows, key=lambda item: (item["day1"], -item["boards"], item["code"])):
            out.append({
                "code": row["code"],
                "name": row["name"],
                "board": row["board"],
                "boards": row["boards"],
                "reason": row["reason"],
                "day1": row["day1"],
                "day2": row["day2"],
                "day3": row["day3"],
                "d2_change": row["d2"]["change"],
                "d2_tag": row["d2"]["tag"],
                "d2_vs_vwap": (row.get("vwap") or {}).get("close_vs_vwap"),
                "morning_broke": (row.get("vwap") or {}).get("morning_broke"),
                "afternoon_hold": (row.get("vwap") or {}).get("afternoon_hold"),
                "d3_gap": row["d3"]["gap"],
                "d3_change": row["d3"]["change"],
                "d3_high": row["d3"]["high"],
                "d3_low": row["d3"]["low"],
                "d3_intraday": row["d3"]["intraday"],
                "d3_tag": row["d3"]["tag"],
                "d3_bucket": row["d3"]["bucket"],
                "d3_limit_up": row["d3"]["limit_up"],
                "d3_limit_down": row["d3"]["limit_down"],
                "from_board_close": row["from_board_close"],
            })
        return out

    payload = {
        "days": days,
        "skipped": dict(skipped),
        "counts": {
            "events": len(enriched),
            "day2_limit": len(continued),
            "day2_last_hour_hold": len(hold),
            "day2_not_hold": len(fail),
            "day2_loose_hold": len(loose),
        },
        "definition": {
            "day1": f"{MONTH_START[:7]}收盘为N天N板且N>=2，已剔除ST和新股",
            "day2": "下一个交易日",
            "day3": "再下一个交易日",
            "vwap": "5分钟典型价累计成交量加权，近似分时均价",
            "hold": "14:05-15:00每根5分钟收盘不低于当时均价的0.2%以内，且收盘站上均价；次日涨停单独分组",
        },
        "hold": group_stats(hold),
        "fail": group_stats(fail),
        "continued": group_stats(continued),
        "loose": group_stats(loose),
        "hold_rows": compact(hold),
        "fail_rows": compact(fail),
        "continued_rows": compact(continued),
    }
    OUT.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print("wrote", OUT, flush=True)
    print(json.dumps({
        "counts": payload["counts"],
        "hold": {k: payload["hold"][k] for k in payload["hold"] if k not in ("by_height", "by_board", "by_day2", "by_shape")},
        "fail": {k: payload["fail"][k] for k in payload["fail"] if not k.startswith("by_")},
        "continued": {k: payload["continued"][k] for k in payload["continued"] if not k.startswith("by_")},
    }, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
