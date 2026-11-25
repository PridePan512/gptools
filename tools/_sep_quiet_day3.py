# -*- coding: utf-8 -*-
"""2026-09: day1 limit-up, day2 not limit-up and volume < 2x day1, then day3 path."""
import json
import time
import urllib.request
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from statistics import mean, median

ROOT = Path(__file__).resolve().parent
SEP_CACHE = ROOT / "_sep_vwap_cache.json"
OCT_CACHE = ROOT / "_oct_vwap_cache.json"
DAILY_CACHE = ROOT / "_sep_quiet_daily.json"
OUT = ROOT / "_sep_quiet_day3.json"
HORIZON = "2026-10-27"
MONTH_START = "2026-09-01"
MONTH_END = "2026-09-30"


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
            time.sleep(0.4 * (attempt + 1))
    raise last


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


def height_label(row):
    value = row.get("high_days_value")
    if isinstance(value, int) and value > 0:
        boards = value & 0xFFFF
        if boards >= 4:
            return "4板及以上"
        if boards == 3:
            return "3板"
        if boards == 2:
            return "2板"
        if boards == 1:
            return "首板"
    text = row.get("high_days") or ""
    if "首板" in text:
        return "首板"
    return "未知"


def calendar():
    url = (
        "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=sh000001,day,,,120,"
    )
    data = get_json(url, "https://gu.qq.com/")
    rows = data["data"]["sh000001"]["day"]
    return [row[0] for row in rows if "2026-08-01" <= row[0] <= HORIZON]


def fetch_daily(code):
    url = (
        "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
        f"CN_MarketData.getKLineData?symbol={symbol_sina(code)}&scale=240&ma=no&datalen=90"
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


def load_seed_daily():
    seeded = {}
    for path in (SEP_CACHE, OCT_CACHE, DAILY_CACHE):
        if not path.exists():
            continue
        blob = json.loads(path.read_text(encoding="utf-8"))
        daily = blob.get("daily", blob) if path != DAILY_CACHE else blob
        if path == DAILY_CACHE:
            daily = blob
        for code, item in daily.items():
            if not isinstance(item, dict):
                continue
            bars = item.get("bars")
            if item.get("ok") and bars:
                seeded[code] = bars
    return seeded


def ensure_daily(codes):
    have = load_seed_daily()
    missing = [code for code in codes if code not in have]
    print(f"daily have {len(have)} missing {len(missing)}", flush=True)
    fetched = {}
    failed = []

    def job(code):
        return code, fetch_daily(code)

    if missing:
        with ThreadPoolExecutor(max_workers=8) as pool:
            futures = [pool.submit(job, code) for code in missing]
            done = 0
            for future in as_completed(futures):
                done += 1
                try:
                    code, bars = future.result()
                    if bars:
                        fetched[code] = bars
                    else:
                        failed.append(code)
                except Exception as exc:
                    failed.append(str(exc)[:80])
                if done % 80 == 0:
                    print(f"fetched {done}/{len(missing)}", flush=True)
    if fetched:
        merged = {code: {"ok": True, "bars": bars} for code, bars in have.items()}
        for code, bars in fetched.items():
            merged[code] = {"ok": True, "bars": bars}
        DAILY_CACHE.write_text(json.dumps(merged, ensure_ascii=False), encoding="utf-8")
        have.update(fetched)
    elif not DAILY_CACHE.exists() and have:
        merged = {code: {"ok": True, "bars": bars} for code, bars in have.items()}
        DAILY_CACHE.write_text(json.dumps(merged, ensure_ascii=False), encoding="utf-8")
    print(f"daily ready {len(have)} failed {len(failed)}", flush=True)
    return have, failed


def pct(part, whole):
    if not whole:
        return None
    return round(part / whole, 4)


def round2(value):
    return round(value, 2)


def summarize(changes):
    if not changes:
        return None
    ordered = sorted(changes)

    def q(p):
        index = min(len(ordered) - 1, max(0, int(round((len(ordered) - 1) * p))))
        return round2(ordered[index])

    return {
        "n": len(changes),
        "mean": round2(mean(changes)),
        "median": round2(median(changes)),
        "p10": q(0.10),
        "p25": q(0.25),
        "p75": q(0.75),
        "p90": q(0.90),
    }


def tag_of(change, up, down, gap, open_px, close_px):
    if up:
        return "涨停"
    if down:
        return "跌停"
    if gap >= 2 and close_px < open_px and change < 0:
        return "高开低走"
    if gap <= -2 and close_px > open_px and change > 0:
        return "低开高走"
    if gap >= 2 and close_px >= open_px and change > 0:
        return "高开高走"
    if gap <= -2 and close_px <= open_px and change < 0:
        return "低开低走"
    if change >= 5:
        return "大阳"
    if change <= -5:
        return "大阴"
    if change >= 1:
        return "收涨"
    if change <= -1:
        return "收跌"
    return "震荡"


def bucket_of(change, up, down):
    if up:
        return "收盘涨停"
    if down:
        return "收盘跌停"
    if change >= 5:
        return "收涨5%以上"
    if change > 0:
        return "收涨0到5%"
    if change <= -5:
        return "收跌5%以上"
    if change < 0:
        return "收跌0到5%"
    return "平盘"


def hist_bin(change, up, down):
    if down:
        return "跌停"
    if up:
        return "涨停"
    if change < -7:
        return "跌超7%"
    if change < -3:
        return "-7%到-3%"
    if change < 0:
        return "-3%到0%"
    if change == 0:
        return "平盘"
    if change <= 3:
        return "0%到3%"
    if change <= 7:
        return "3%到7%"
    return "涨超7%"


HIST_ORDER = ["跌停", "跌超7%", "-7%到-3%", "-3%到0%", "平盘", "0%到3%", "3%到7%", "涨超7%", "涨停"]
BUCKET_ORDER = ["收盘跌停", "收跌5%以上", "收跌0到5%", "平盘", "收涨0到5%", "收涨5%以上", "收盘涨停"]


def pack(rows):
    if not rows:
        return {"n": 0}
    changes = [row["d3_change"] for row in rows]
    gaps = [row["d3_gap"] for row in rows]
    highs = [row["d3_high"] for row in rows]
    lows = [row["d3_low"] for row in rows]
    bodies = [row["d3_body"] for row in rows]
    tags = Counter(row["d3_tag"] for row in rows)
    buckets = Counter(row["d3_bucket"] for row in rows)
    hist = Counter(row["d3_hist"] for row in rows)
    up = sum(row["d3_up"] for row in rows)
    down = sum(row["d3_down"] for row in rows)
    win = sum(row["d3_change"] > 0 for row in rows)
    flat = sum(row["d3_change"] == 0 for row in rows)
    gap_up = sum(row["d3_gap"] > 0 for row in rows)
    return {
        "n": len(rows),
        "close": summarize(changes),
        "gap": summarize(gaps),
        "high": summarize(highs),
        "low": summarize(lows),
        "body": summarize(bodies),
        "win_rate": pct(win, len(rows)),
        "flat_rate": pct(flat, len(rows)),
        "limit_up_rate": pct(up, len(rows)),
        "limit_down_rate": pct(down, len(rows)),
        "gap_up_rate": pct(gap_up, len(rows)),
        "mean_close_pos": round2(mean(row["d3_pos"] for row in rows)),
        "tags": dict(tags.most_common()),
        "buckets": {key: buckets.get(key, 0) for key in BUCKET_ORDER},
        "hist": {key: hist.get(key, 0) for key in HIST_ORDER},
        "vol_ratio": summarize([row["vol_ratio"] for row in rows]),
        "d2_change": summarize([row["d2_change"] for row in rows]),
    }


AMP_ORDER = ["4%以内", "4%到6%", "6%到8%", "8%到10%", "10%到13%", "13%以上"]


def amp_label(amp):
    if amp < 4:
        return "4%以内"
    if amp < 6:
        return "4%到6%"
    if amp < 8:
        return "6%到8%"
    if amp < 10:
        return "8%到10%"
    if amp < 13:
        return "10%到13%"
    return "13%以上"


def detail_group(rows, key, order=None):
    groups = {}
    for row in rows:
        groups.setdefault(row[key], []).append(row)
    names = order or list(groups)
    out = {}
    for name in names:
        items = groups.get(name) or []
        if not items:
            continue
        out[name] = {
            "n": len(items),
            "mean_close": round2(mean(item["d3_change"] for item in items)),
            "median_close": round2(median(item["d3_change"] for item in items)),
            "win_rate": pct(sum(item["d3_change"] > 0 for item in items), len(items)),
            "limit_up_rate": pct(sum(item["d3_up"] for item in items), len(items)),
            "limit_down_rate": pct(sum(item["d3_down"] for item in items), len(items)),
            "mean_gap": round2(mean(item["d3_gap"] for item in items)),
            "median_gap": round2(median(item["d3_gap"] for item in items)),
            "mean_high": round2(mean(item["d3_high"] for item in items)),
            "median_high": round2(median(item["d3_high"] for item in items)),
            "mean_low": round2(mean(item["d3_low"] for item in items)),
            "median_low": round2(median(item["d3_low"] for item in items)),
            "mean_body": round2(mean(item["d3_body"] for item in items)),
            "d2_median_change": round2(median(item["d2_change"] for item in items)),
            "d2_mean_change": round2(mean(item["d2_change"] for item in items)),
            "d2_win_rate": pct(sum(item["d2_change"] > 0 for item in items), len(items)),
            "d2_limit_down_rate": pct(sum(item["d2_down"] for item in items), len(items)),
            "d2_mean_pos": round2(mean(item["d2_pos"] for item in items)),
            "d2_amp_median": round2(median(item["d2_amp"] for item in items)),
            "main_n": sum(item["board"] == "主板10%" for item in items),
            "hist": {key: sum(item["d3_hist"] == key for item in items) for key in HIST_ORDER},
        }
    return out


def group_by(rows, key):
    groups = {}
    for row in rows:
        groups.setdefault(row[key], []).append(row)
    out = {}
    for name, items in groups.items():
        out[name] = {
            "n": len(items),
            "mean_close": round2(mean(item["d3_change"] for item in items)),
            "median_close": round2(median(item["d3_change"] for item in items)),
            "win_rate": pct(sum(item["d3_change"] > 0 for item in items), len(items)),
            "limit_up_rate": pct(sum(item["d3_up"] for item in items), len(items)),
            "limit_down_rate": pct(sum(item["d3_down"] for item in items), len(items)),
            "mean_gap": round2(mean(item["d3_gap"] for item in items)),
            "mean_high": round2(mean(item["d3_high"] for item in items)),
            "mean_low": round2(mean(item["d3_low"] for item in items)),
        }
    return out


def main():
    sep = json.loads(SEP_CACHE.read_text(encoding="utf-8"))
    days = calendar()
    index = {day: i for i, day in enumerate(days)}
    codes = set()
    events = []
    for day, blob in sorted(sep["pools"].items()):
        if not (MONTH_START <= day <= MONTH_END):
            continue
        for row in blob.get("rows") or []:
            codes.add(row["code"])
            events.append((day, row))
    daily, failed = ensure_daily(sorted(codes))
    quiet = []
    doubled = []
    skipped = Counter()
    for day, row in events:
        code = row["code"]
        name = row.get("name") or ""
        bars = daily.get(code)
        if not bars:
            skipped["缺日线"] += 1
            continue
        by_date = {bar["date"]: bar for bar in bars}
        if day not in index:
            skipped["涨停日非交易日"] += 1
            continue
        pos = index[day]
        if pos + 2 >= len(days):
            skipped["第三天超出行情"] += 1
            continue
        d1 = by_date.get(days[pos])
        d2 = by_date.get(days[pos + 1])
        d3 = by_date.get(days[pos + 2])
        if not d1 or not d2 or not d3:
            skipped["停牌或缺K线"] += 1
            continue
        prev = by_date.get(days[pos - 1]) if pos > 0 else None
        ratio = ratio_of(code, name)
        if prev and not is_limit(d1["close"], prev["close"], ratio, True):
            gap = abs(d1["close"] / prev["close"] - 1)
            if gap > 0.25:
                skipped["疑似除权"] += 1
                continue
        if d1["volume"] <= 0 or d2["volume"] <= 0:
            skipped["成交量为0"] += 1
            continue
        d2_up = is_limit(d2["close"], d1["close"], ratio, True)
        if d2_up:
            skipped["次日涨停"] += 1
            continue
        vol_ratio = d2["volume"] / d1["volume"]
        d2_change = (d2["close"] / d1["close"] - 1) * 100
        d2_gap = (d2["open"] / d1["close"] - 1) * 100
        d2_high = (d2["high"] / d1["close"] - 1) * 100
        d2_low = (d2["low"] / d1["close"] - 1) * 100
        d2_span = d2["high"] - d2["low"]
        d2_amp = (d2_span / d1["close"]) * 100 if d1["close"] else 0
        d2_pos = (d2["close"] - d2["low"]) / d2_span if d2_span > 0 else 0.5
        touched = money(d2["high"]) >= limit_price(d1["close"], ratio, True)
        d2_down = is_limit(d2["close"], d1["close"], ratio, False)
        d3_change = (d3["close"] / d2["close"] - 1) * 100
        d3_gap = (d3["open"] / d2["close"] - 1) * 100
        d3_high = (d3["high"] / d2["close"] - 1) * 100
        d3_low = (d3["low"] / d2["close"] - 1) * 100
        d3_body = (d3["close"] / d3["open"] - 1) * 100 if d3["open"] else 0
        span = d3["high"] - d3["low"]
        d3_pos = (d3["close"] - d3["low"]) / span if span > 0 else 0.5
        d3_up = is_limit(d3["close"], d2["close"], ratio, True)
        d3_down = is_limit(d3["close"], d2["close"], ratio, False)
        sealed1 = abs(d1["open"] - d1["close"]) < 0.011 and abs(d1["high"] - d1["low"]) < 0.011
        item = {
            "code": code,
            "name": name,
            "board": board_name(code, name),
            "height": height_label(row),
            "d1": day,
            "d2": d2["date"],
            "d3": d3["date"],
            "sealed1": sealed1,
            "vol_ratio": round(vol_ratio, 3),
            "d2_change": round2(d2_change),
            "d2_gap": round2(d2_gap),
            "d2_high": round2(d2_high),
            "d2_low": round2(d2_low),
            "d2_amp": round2(d2_amp),
            "d2_pos": round(d2_pos, 3),
            "d2_down": d2_down,
            "d2_touched": touched,
            "d2_tag": tag_of(d2_change, False, is_limit(d2["close"], d1["close"], ratio, False), d2_gap, d2["open"], d2["close"]),
            "d3_change": round2(d3_change),
            "d3_gap": round2(d3_gap),
            "d3_high": round2(d3_high),
            "d3_low": round2(d3_low),
            "d3_body": round2(d3_body),
            "d3_pos": round(d3_pos, 3),
            "d3_up": d3_up,
            "d3_down": d3_down,
            "d3_tag": tag_of(d3_change, d3_up, d3_down, d3_gap, d3["open"], d3["close"]),
            "d3_bucket": bucket_of(d3_change, d3_up, d3_down),
            "d3_hist": hist_bin(d3_change, d3_up, d3_down),
        }
        if vol_ratio < 2:
            quiet.append(item)
        else:
            doubled.append(item)

    def slim(rows):
        return [
            {
                "code": row["code"],
                "name": row["name"],
                "board": row["board"],
                "height": row["height"],
                "d1": row["d1"],
                "d2": row["d2"],
                "d3": row["d3"],
                "vol_ratio": row["vol_ratio"],
                "d2_change": row["d2_change"],
                "d2_tag": row["d2_tag"],
                "d3_change": row["d3_change"],
                "d3_gap": row["d3_gap"],
                "d3_high": row["d3_high"],
                "d3_low": row["d3_low"],
                "d3_tag": row["d3_tag"],
            }
            for row in rows
        ]

    result = {
        "definition": {
            "universe": "2026-09同花顺涨停池，沪深主板+创业板+科创板，已不含北交所",
            "day1": "涨停日收盘涨停，落在2026-09-01至2026-09-30",
            "day2": "下一交易日收盘未涨停，且成交量 < 涨停日成交量的2倍",
            "day3": "再下一交易日，涨跌幅相对第二天收盘",
            "volume": "新浪日线成交量，不复权",
            "limit": "主板10%、创业板/科创板20%，四舍五入到分",
            "horizon": HORIZON,
        },
        "funnel": {
            "limit_up_events": len(events),
            "unique_codes": len(codes),
            "skipped": dict(skipped),
            "quiet": len(quiet),
            "doubled_not_limit": len(doubled),
        },
        "quiet": pack(quiet),
        "doubled": pack(doubled),
        "quiet_by_height": group_by(quiet, "height"),
        "quiet_by_board": group_by(quiet, "board"),
        "quiet_by_d2_tag": group_by(quiet, "d2_tag"),
        "quiet_by_d2_touch": group_by(
            [{**row, "touch": "次日盘中触及涨停" if row["d2_touched"] else "次日未触及涨停"} for row in quiet],
            "touch",
        ),
        "quiet_by_vol": group_by(
            [
                {
                    **row,
                    "vol_bin": (
                        "缩量<0.7" if row["vol_ratio"] < 0.7
                        else "0.7到1.0" if row["vol_ratio"] < 1
                        else "1.0到1.5" if row["vol_ratio"] < 1.5
                        else "1.5到2.0"
                    ),
                }
                for row in quiet
            ],
            "vol_bin",
        ),
        "examples_best": slim(sorted(quiet, key=lambda row: row["d3_change"], reverse=True)[:12]),
        "examples_worst": slim(sorted(quiet, key=lambda row: row["d3_change"])[:12]),
        "quiet_amp": summarize([row["d2_amp"] for row in quiet]),
        "quiet_by_amp": detail_group(
            [{**row, "amp_bin": amp_label(row["d2_amp"])} for row in quiet],
            "amp_bin",
            AMP_ORDER,
        ),
        "quiet_by_amp_main": detail_group(
            [{**row, "amp_bin": amp_label(row["d2_amp"])} for row in quiet if row["board"] == "主板10%"],
            "amp_bin",
            AMP_ORDER,
        ),
        "quiet_by_amp_close": detail_group(
            [
                {
                    **row,
                    "amp_side": amp_label(row["d2_amp"]) + ("·收阳" if row["d2_change"] > 0 else "·收阴"),
                }
                for row in quiet
                if row["board"] == "主板10%" and row["d2_change"] != 0
            ],
            "amp_side",
        ),
    }
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        "amp": result["quiet_amp"],
        "by_amp": result["quiet_by_amp"],
        "by_amp_main": result["quiet_by_amp_main"],
        "by_amp_close": result["quiet_by_amp_close"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
