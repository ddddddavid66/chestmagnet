"""Summarize two exact-JAR QA runs. Trial 0 in each fixture is warm-up."""
from pathlib import Path
from math import ceil
from statistics import median
import argparse
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")

parser = argparse.ArgumentParser()
parser.add_argument("baseline", type=Path)
parser.add_argument("candidate", type=Path)
args = parser.parse_args()


def percentile(values, q):
    return sorted(values)[ceil(len(values) * q) - 1]


print("| 版本 | 场景 | 首次到账 tick / ms | 收完 tick / ms | 到账 | 候选判定 / 槽位模拟 | work p50 / p95 (µs) | 超 2 ms |")
print("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |")
frames = []
for label, path in (("1.2.4", args.baseline), ("1.2.5", args.candidate)):
    content = path.read_text(encoding="utf-8-sig", errors="replace")
    if "QA125_BENCH_PASS" not in content:
        raise SystemExit(f"Incomplete run: {path}")
    for kind, expected in (("sparse", 4), ("dense", 768)):
        measured = []
        for line in content.splitlines():
            if f"QA125_BENCH kind={kind}" not in line:
                continue
            values = {key: int(value) for key, value in re.findall(r"(\w+)=(\d+)", line)}
            if not values["trial"]:
                continue
            costs = re.search(r"costs_us=\[([0-9, ]+)\]", line)
            measured.append((values, [int(n) for n in costs.group(1).split(",")]))
        assert len(measured) == 5, (label, kind, len(measured))
        assert all(v["items"] == expected and v["probes"] > 0 and v["eligibility_checks"] > 0
                   for v, _ in measured)
        costs = [n for _, sample in measured for n in sample]

        def med(key):
            return int(median(v[key] for v, _ in measured))

        print(f"| {label} | {kind} | {med('first_ticks')} / {med('first_ms')} | "
              f"{med('last_ticks')} / {med('last_ms')} | {expected} | "
              f"{med('eligibility_checks')} / {med('probes')} | "
              f"{percentile(costs, .50)} / {percentile(costs, .95)} | "
              f"{sum(v['overruns'] for v, _ in measured)} / {len(costs)} |")
    frame = re.search(r"QA125_FRAME n=(\d+) p50_us=(\d+) p95_us=(\d+)", content)
    if frame:
        frames.append(f"- {label}：{frame[1]} 帧，p50 {frame[2]} µs，p95 {frame[3]} µs。")
print()
print("\n".join(frames))
