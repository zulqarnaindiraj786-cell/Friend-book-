"""Offline necessary-condition check for the current FriendBook chat storage path.

No production access. This is not a concurrency test or a D1 billing estimator.
"""
import argparse
import json


def capacity(users, sends, write_limit, include_first_receipt=False):
    # A normal send stores the message and updates the current room. A first
    # receipt/unread reset updates the message and current room again. Deliberately
    # exclude guards, indexes, retries, other features and all background work.
    rows_per_send = 2 + (2 if include_first_receipt else 0)
    minimum = users * sends * rows_per_send
    return {
        'daily_active_users': users,
        'message_sends_per_user_per_day': sends,
        'include_first_receipt_and_room_reset': include_first_receipt,
        'daily_write_limit': write_limit,
        'minimum_document_rows_written_per_day': minimum,
        'maximum_daily_active_users_at_this_workload_before_overhead': write_limit // (sends * rows_per_send),
        'gate': 'FAILS_WRITE_FLOOR' if minimum > write_limit else 'NOT_VERIFIED',
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--users', type=int, nargs='+', default=[100_000, 500_000, 1_000_000])
    parser.add_argument('--sends', type=int, default=1)
    parser.add_argument('--write-limit', type=int, default=100_000)
    args = parser.parse_args()
    if min(*args.users, args.sends, args.write_limit) < 1:
        parser.error('all inputs must be positive')
    print(json.dumps([capacity(users, args.sends, args.write_limit, receipt)
                      for users in args.users for receipt in (False, True)], indent=2))


if __name__ == '__main__':
    main()
