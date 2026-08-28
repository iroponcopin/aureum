#!/usr/bin/env python3
"""bench-results/ の全ランを 1 枚の比較表に要約する。

使い方: python3 tools/summarize_bench.py [bench-results]

出す数字はレポート JSON と class histogram からの転記のみ(創作しない)。
histogram からは Aureum の対象クラスの実測行だけを抜く。
"""
import json
import os
import re
import sys
from glob import glob

TARGET_CLASSES = [
    "net.minecraft.util.ThreadingDetector",
    "java.util.concurrent.Semaphore",
    "java.util.concurrent.Semaphore$NonfairSync",
    "java.util.concurrent.locks.ReentrantLock",
    "java.util.concurrent.locks.ReentrantLock$NonfairSync",
    "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase$Cache",
    "net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate$StructureBlockInfo",
    "net.minecraft.core.BlockPos",
]


def histogram_rows(path):
    rows = {}
    if not os.path.isfile(path):
        return rows
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = re.match(r"\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)", line)
            if m:
                instances, bytes_, name = int(m.group(1)), int(m.group(2)), m.group(3)
                if name in TARGET_CLASSES:
                    rows[name] = (instances, bytes_)
    return rows


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "bench-results"
    runs = sorted(glob(os.path.join(root, "*/report.json")))
    for report_path in runs:
        run_dir = os.path.dirname(report_path)
        label = os.path.basename(run_dir)
        with open(report_path, encoding="utf-8") as f:
            report = json.load(f)
        heap = sorted(report.get("heapUsedBytes", []))
        heap_median = heap[len(heap) // 2] / 1e6 if heap else float("nan")
        steady = report.get("steady", {})
        spawn = report.get("spawn", {})
        print(f"== {label}")
        print(f"   chunks={report.get('loadedChunksEnd')} players={report.get('players')}"
              f" entitiesEnd={report.get('entitiesEnd')} templates={report.get('templateCacheCount', '-')}")
        print(f"   heapUsedMedian={heap_median:.1f} MB  (samples: "
              + ", ".join(f"{h/1e6:.1f}" for h in heap) + ")")
        if steady.get("count"):
            print(f"   steady: median={steady['medianMs']} mean={steady['meanMs']}"
                  f" p95={steady['p95Ms']} p99={steady['p99Ms']} max={steady['maxMs']} (ms, n={steady['count']})")
        if spawn.get("count"):
            print(f"   spawn:  median={spawn['medianMs']} mean={spawn['meanMs']}"
                  f" p95={spawn['p95Ms']} p99={spawn['p99Ms']} max={spawn['maxMs']} (ms, n={spawn['count']})")
        rows = histogram_rows(os.path.join(run_dir, "class-histogram.txt"))
        for name in TARGET_CLASSES:
            if name in rows:
                inst, byt = rows[name]
                print(f"   {name.split('.')[-1]:<28} {inst:>9,} inst {byt/1e6:>8.2f} MB")
        print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
