#!/usr/bin/env python3
"""匿名共学轮询压测（P2-05 出口：1k 并发轮询 P95 < 50ms）。

只打 GET /api/study/presence（每个请求都会 touch 一次在场，语义与前端轮询一致）。
标准库实现（concurrent.futures + http.client，无第三方依赖），keep-alive 复用连接。

用法（在能连通目标环境的机器上跑；本机无中间件，不跑）：
    ANNONA_BENCH_BASE=http://demo-host:8080 \\
    ANNONA_BENCH_AUTH='Authorization: Bearer <session-token>' \\
    python scripts/bench/presence-bench.py [--users 1000] [--requests 5000]

ANNONA_BENCH_AUTH 是完整的请求头行（名字: 值），按部署环境的鉴权头形态填。
结果输出 P50/P95/P99/max 与错误数，供 docs/tests/指标测试-共学.md 回填。
"""

import argparse
import http.client
import os
import random
import ssl
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urlparse


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="presence polling benchmark")
    parser.add_argument("--users", type=int, default=1000, help="并发虚拟用户数（默认 1000）")
    parser.add_argument("--requests", type=int, default=5000, help="总请求数（默认 5000）")
    parser.add_argument("--timeout", type=float, default=5.0, help="单请求超时秒数")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    base = os.environ.get("ANNONA_BENCH_BASE")
    auth = os.environ.get("ANNONA_BENCH_AUTH")
    if not base or not auth:
        print("需要环境变量 ANNONA_BENCH_BASE 与 ANNONA_BENCH_AUTH（完整请求头行）", file=sys.stderr)
        return 2

    url = urlparse(base)
    target = url.path.rstrip("/") + "/api/study/presence"
    header_name, _, header_value = auth.partition(":")
    header_value = header_value.strip()
    is_https = url.scheme == "https"
    if is_https:
        conn_factory = lambda: http.client.HTTPSConnection(  # noqa: E731
            url.hostname, url.port or 443, context=ssl._create_unverified_context(), timeout=args.timeout
        )
    else:
        conn_factory = lambda: http.client.HTTPConnection(  # noqa: E731
            url.hostname, url.port or 80, timeout=args.timeout
        )

    latencies: list[float] = []
    errors = 0

    def worker(seed: int) -> None:
        nonlocal errors
        conn = conn_factory()
        headers = {"Accept": "application/json", header_name: header_value}
        rng = random.Random(seed)
        try:
            for _ in range(max(1, args.requests // args.users)):
                start = time.perf_counter()
                try:
                    conn.request("GET", target, headers=headers)
                    response = conn.getresponse()
                    response.read()
                    elapsed_ms = (time.perf_counter() - start) * 1000
                    with results_lock:
                        if response.status == 200:
                            latencies.append(elapsed_ms)
                        else:
                            errors += 1
                except Exception:  # noqa: BLE001——压测中任何网络异常都只计错误不中断
                    with results_lock:
                        errors += 1
                # 模拟 10s 轮询间隔的抖动（压测按比例压缩，保持并发形态）
                time.sleep(rng.uniform(0.01, 0.05))
        finally:
            conn.close()

    import threading

    results_lock = threading.Lock()
    print(f"目标 {url.scheme}://{url.netloc}{target} · users={args.users} requests={args.requests}")
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=args.users) as pool:
        list(pool.map(worker, range(args.users)))
    wall = time.perf_counter() - started

    if not latencies:
        print("全部请求失败——检查 BASE/AUTH 与服务可用性", file=sys.stderr)
        return 1
    latencies.sort()

    def pct(p: float) -> float:
        return latencies[min(len(latencies) - 1, int(len(latencies) * p))]

    print(f"成功 {len(latencies)} · 失败 {errors} · 墙钟 {wall:.1f}s · QPS {len(latencies) / wall:.0f}")
    print(f"P50={pct(0.50):.1f}ms  P95={pct(0.95):.1f}ms  P99={pct(0.99):.1f}ms  max={latencies[-1]:.1f}ms")
    verdict = "达标（P95 < 50ms）" if pct(0.95) < 50 else "未达标（P95 ≥ 50ms）"
    print(f"P2-05 出口口径：{verdict}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
