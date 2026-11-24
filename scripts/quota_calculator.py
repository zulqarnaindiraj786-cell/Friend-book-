"""Scenario calculator. Inputs must be measured billed rows, NOT SQL/HTTP counts."""
import argparse
import json
from decimal import Decimal

def calculate(data):
    def number(key, default=None):
        value = data.get(key, default)
        if isinstance(value, bool) or value is None:
            raise ValueError(f'{key} must be a nonnegative finite number')
        value = Decimal(str(value))
        if not value.is_finite() or value < 0:
            raise ValueError(f'{key} must be a nonnegative finite number')
        return value
    sessions = number('daily_users') * number('sessions_per_user')
    totals = {}
    for metric in ('reads', 'writes', 'invocations'):
        totals[metric] = sessions * number(f'{metric}_per_session') + number(f'background_{metric}_per_day', 0)
    limits = {m: number(f'{m}_daily_limit') for m in totals}
    return {
        'evidence': data.get('evidence', 'assumption-only; not production proof'),
        'daily_sessions': float(sessions),
        'daily_totals': {k: float(v) for k, v in totals.items()},
        'daily_limits': {k: float(v) for k, v in limits.items()},
        'within_supplied_limits': {k: totals[k] <= limits[k] for k in totals},
        'all_within_supplied_limits': all(totals[k] <= limits[k] for k in totals),
        'warning': 'Excludes storage, CPU, subrequest, KV/R2/AE, media and other plan limits. Not a free-plan guarantee.'
    }

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('scenario', help='JSON file with measured or explicitly assumed rates and plan limits')
    args = parser.parse_args()
    try:
        with open(args.scenario) as handle:
            print(json.dumps(calculate(json.load(handle)), indent=2))
    except (ValueError, KeyError, TypeError) as error:
        parser.error(str(error))
