#!/usr/bin/env python3
"""JFR 記録からアロケーション上位サイトを要約する。

使い方: python3 tools/jfr_top_allocs.py <recording.jfr> [top_n]

`jfr print --events jdk.ObjectAllocationSample` の出力を集計する。
ObjectAllocationSample は TLAB ベースの標本なので<b>推計</b>である —
絶対量ではなく、A/B での順位と weight 比の変化を読む。
"""
import subprocess
import sys
import re
import os
from collections import defaultdict

JAVA_HOME = os.environ.get("JAVA_HOME", "/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home")


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 64
    path = sys.argv[1]
    top_n = int(sys.argv[2]) if len(sys.argv) > 2 else 25
    jfr = os.path.join(JAVA_HOME, "bin", "jfr")
    proc = subprocess.run(
        [jfr, "print", "--events", "jdk.ObjectAllocationSample", path],
        capture_output=True, text=True)
    if proc.returncode != 0:
        print("jfr print failed:", proc.stderr[:500])
        return 1
    by_site = defaultdict(float)
    by_type = defaultdict(float)
    total = 0.0
    object_class = None
    weight = None
    top_frame = None
    in_stack = False
    for line in proc.stdout.splitlines():
        stripped = line.strip()
        m = re.match(r"objectClass = (.+?) \(", stripped)
        if m:
            object_class = m.group(1)
        m = re.match(r"weight = ([0-9.]+) ([kMG]?B)", stripped)
        if m:
            value = float(m.group(1))
            unit = m.group(2)
            factor = {"B": 1, "kB": 1e3, "MB": 1e6, "GB": 1e9}[unit]
            weight = value * factor
        if stripped.startswith("stackTrace = ["):
            in_stack = True
            top_frame = None
            continue
        if in_stack:
            if stripped == "]" or stripped.startswith("jdk.jfr"):
                in_stack = False
            elif top_frame is None and stripped:
                top_frame = stripped.split(" line:")[0].strip()
                # イベント終端扱い: サイト＝(型, 先頭フレーム)
                if object_class and weight is not None:
                    by_site[f"{object_class}  @  {top_frame}"] += weight
                    by_type[object_class] += weight
                    total += weight
                    object_class = None
                    weight = None
    print(f"total sampled allocation weight: {total / 1e6:.1f} MB (TLAB-sample estimate)")
    print(f"\n== top {top_n} allocation sites (type @ topmost frame) ==")
    for site, w in sorted(by_site.items(), key=lambda kv: -kv[1])[:top_n]:
        print(f"{w / 1e6:10.1f} MB  {site}")
    print(f"\n== top {top_n} allocated types ==")
    for type_name, w in sorted(by_type.items(), key=lambda kv: -kv[1])[:top_n]:
        print(f"{w / 1e6:10.1f} MB  {type_name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
