#!/usr/bin/env python3
"""Illustrative D1 daily-budget forecast using operator-supplied billed counters.

No network calls. This is not monitoring, a spending cap, or a capacity guarantee.
Counters must be account-wide, cover the same UTC day, and come from the billing
source; adaptive query Analytics and local SQLite measurements are not substitutes.
"""
import argparse
import json
import math


def estimate(reads, writes, elapsed_hours, read_limit, write_limit, reserve):
    if any(type(n) is not int or n < 0 for n in (reads, writes)):
        raise ValueError('counters must be non-negative integers')
    if any(type(n) is not int or n < 1 for n in (read_limit, write_limit)):
        raise ValueError('limits must be positive integers')
    if not math.isfinite(elapsed_hours) or not 0 < elapsed_hours <= 24:
        raise ValueError('elapsed hours must be in (0,24]')
    if not math.isfinite(reserve) or not 0 <= reserve < 1:
        raise ValueError('reserve must be in [0,1)')

    def metric(value, limit):
        forecast = value * 24 / elapsed_hours
        threshold = limit * (1 - reserve)
        status = ('LIMIT_REACHED' if value >= limit else
                  'PROJECTED_OVER_HEADROOM' if forecast >= threshold else
                  'WITHIN_LINEAR_ESTIMATE_ONLY')
        return {'used': value, 'daily_limit': limit,
                'remaining_before_limit': max(0, limit - value),
                'projected_end_of_day_at_average_rate': round(forecast),
                'reserve_fraction': reserve, 'status': status}

    return {
        'elapsed_hours': elapsed_hours,
        'reads': metric(reads, read_limit),
        'writes': metric(writes, write_limit),
        'warning': 'Linear extrapolation only: traffic, billing delay, index builds and other services can change. No capacity or availability guarantee.',
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reads', type=int, required=True, help='account-wide billed read rows so far today')
    parser.add_argument('--writes', type=int, required=True, help='account-wide billed written rows so far today')
    parser.add_argument('--elapsed-hours', type=float, required=True, help='hours since the dashboard daily reset')
    parser.add_argument('--read-limit', type=int, default=5_000_000)
    parser.add_argument('--write-limit', type=int, default=100_000)
    parser.add_argument('--reserve', type=float, default=0.20, help='fraction of each limit reserved as headroom')
    args = parser.parse_args()
    try:
        result = estimate(args.reads, args.writes, args.elapsed_hours, args.read_limit, args.write_limit, args.reserve)
    except ValueError as error:
        parser.error(str(error))
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
