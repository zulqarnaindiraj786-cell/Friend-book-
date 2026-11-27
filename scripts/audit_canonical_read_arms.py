"""Audit the three cutover modules for legacy document read arms.

Mirror INSERT/UPDATE/DELETE maintenance is intentionally retained. The single
mirror equality check in unchangedRootState is a write no-op/repair decision,
not a source for a returned document. Canonical UNIONs for rank seeks, message
bridges and comment routes are intentionally allowed.
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FILES = ('app-documents.js', 'comment-api.js', 'firestore-documents.js')
MIRROR_REPAIR_CHECK = (
    'EXISTS(SELECT 1 FROM friendbook_cf_documents WHERE path=?1 '
    'AND document_json=?5 AND owner_uid IS ?6) AS mirror'
)


def audit():
    counts = {}
    for name in FILES:
        source = (ROOT / 'cloudflare' / name).read_text()
        if name == 'firestore-documents.js':
            assert source.count(MIRROR_REPAIR_CHECK) == 1, 'Review mirror repair check changes explicitly'
            source = source.replace(MIRROR_REPAIR_CHECK, '')
        source = re.sub(r'\bDELETE\s+FROM\s+friendbook_cf_documents\b', '', source, flags=re.I)
        reads = re.findall(r'\b(?:FROM|JOIN)\s+friendbook_cf_documents\b', source, re.I)
        counts[name] = len(reads)
    result = {'legacy_read_arms': counts, 'total': sum(counts.values()),
              'mirror_write_maintenance_retained': True}
    if result['total']:
        raise AssertionError(result)
    return result


if __name__ == '__main__':
    print(json.dumps(audit(), indent=2))
