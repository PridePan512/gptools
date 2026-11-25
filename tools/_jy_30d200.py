# -*- coding: utf-8 -*-
import json
import urllib.request


def fetch_klines(secid, lmt=80):
    url = (
        "https://push2his.eastmoney.com/api/qt/stock/kline/get"
        f"?secid={secid}&fields1=f1,f2,f3,f4,f5,f6"
        "&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61"
        f"&klt=101&fqt=0&end=20500101&lmt={lmt}"
    )
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "Mozilla/5.0", "Referer": "https://quote.eastmoney.com/"},
    )
    raw = urllib.request.urlopen(req, timeout=20).read().decode("utf-8")
    data = json.loads(raw)
    rows = []
    for line in data["data"]["klines"]:
        p = line.split(",")
        rows.append({"date": p[0], "close": float(p[2]), "pct": float(p[8])})
    return data["data"].get("name"), rows


name, stock = fetch_klines("0.002242", 80)
_, idx = fetch_klines("0.399107", 80)  # 深证A指
print("stock", name, "last", stock[-1])
print("index last", idx[-1])

smap = {r["date"]: r for r in stock}
imap = {r["date"]: r for r in idx}
dates = [r["date"] for r in stock if r["date"] in imap]
print("aligned", len(dates), "from", dates[0], "to", dates[-1])

devs = []
for d in dates:
    s = smap[d]["pct"]
    i = imap[d]["pct"]
    devs.append(
        {
            "date": d,
            "stock_pct": s,
            "idx_pct": i,
            "dev": s - i,
            "close": smap[d]["close"],
        }
    )

print("\n=== rolling 30d cumulative deviation ===")
for i in range(29, len(devs)):
    window = devs[i - 29 : i + 1]
    cum = sum(x["dev"] for x in window)
    if dates[i] >= "2026-10-20":
        print(
            f"{dates[i]} close={devs[i]['close']:.2f} "
            f"stock%={devs[i]['stock_pct']:+.2f} idx%={devs[i]['idx_pct']:+.2f} "
            f"day_dev={devs[i]['dev']:+.2f} cum30={cum:.2f}"
        )

i = len(devs) - 1
window = devs[i - 29 : i + 1]
cum_now = sum(x["dev"] for x in window)
oldest = window[0]
print("\nNOW window:", window[0]["date"], "->", window[-1]["date"])
print("cum_now", round(cum_now, 4))
print(
    "oldest drop tomorrow:",
    oldest["date"],
    "dev",
    round(oldest["dev"], 4),
    "stock%",
    oldest["stock_pct"],
    "idx%",
    oldest["idx_pct"],
)

remain_base = 200 - (cum_now - oldest["dev"])
print("need day_dev tomorrow >=", round(remain_base, 4))
print("if index flat (0%): stock need rise", round(remain_base, 4), "%")
last_close = devs[-1]["close"]
print("last close", last_close)
print("trigger price if idx=0:", round(last_close * (1 + remain_base / 100), 4))
print("limit up +10%:", round(last_close * 1.1, 4))
print(
    "if tomorrow涨停+10% and idx=0, new_cum=",
    round(cum_now - oldest["dev"] + 10, 4),
)

print("\n=== tomorrow scenarios (assume idx pct=0) ===")
for pct in [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10.02]:
    new_cum = cum_now - oldest["dev"] + pct
    price = last_close * (1 + pct / 100)
    print(
        f"  stock+{pct:.2f}% price={price:.2f} new_cum30={new_cum:.2f} trigger={new_cum >= 200}"
    )

print("\n=== trigger stock% needed under various idx moves ===")
for idx_pct in [-1, -0.5, 0, 0.5, 1, 2]:
    need = remain_base + idx_pct
    price = last_close * (1 + need / 100)
    print(f"  idx {idx_pct:+.1f}% -> stock need {need:.2f}% price>={price:.2f}")

# also check eastmoney-style: some use 29 prior + today for T+1 forecast
# verify window length
print("\nwindow days sample:")
for x in window:
    print(f"  {x['date']} stock={x['stock_pct']:+.2f} idx={x['idx_pct']:+.2f} dev={x['dev']:+.2f}")
