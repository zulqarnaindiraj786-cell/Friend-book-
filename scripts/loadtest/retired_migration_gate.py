#!/usr/bin/env python3
"""Ensure a destructive migration cannot be bundled with unrelated D1 work."""
import re
import sys

NAME = '0086_remove_unused_status_tables.sql'


def pending_safe(text):
    return set(re.findall(r'\b\d{4}_[a-z0-9_]+\.sql\b', text)) == {NAME}


if __name__ == '__main__':
    if len(sys.argv) != 2 or not pending_safe(open(sys.argv[1], encoding='utf8').read()):
        raise SystemExit('Unexpected pending migrations; no migration attempted.')
    print('::notice:: Only the explicitly requested retired-table migration is pending.')
