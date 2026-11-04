"""OFFLINE staging/verification only. Never contacts Firebase or Cloudflare.
Input: Firestore REST Document JSONL (including all nested subcollections).
Output contains private data: keep it out of Git and public website assets.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import uuid

PREFIX = 'projects/friendbook-17551/databases/(default)/documents/'
MAX_SQL_BYTES = 100_000


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False)


def digest(text):
    return hashlib.sha256(text.encode('utf8')).hexdigest()


def parse_json(text):
    def invalid(_):
        raise ValueError('Non-finite JSON numbers are not supported')
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate JSON keys are not supported')
            result[key] = value
        return result
    return json.loads(text, parse_constant=invalid, object_pairs_hook=unique)


def source_documents(file):
    docs = {}
    for line in Path(file).read_text(encoding='utf8').splitlines():
        if not line.strip():
            continue
        doc = parse_json(line)
        if not isinstance(doc, dict):
            raise ValueError('Expected Firestore REST Document objects')
        name = doc.get('name', '')
        if not isinstance(name, str) or not name.startswith(PREFIX):
            raise ValueError('Wrong Firebase project/database or missing document name')
        path = name[len(PREFIX):]
        parts = path.split('/')
        if len(parts) % 2 or any(not p or p in ('.', '..') for p in parts):
            raise ValueError('Invalid Firestore document path')
        if path in docs:
            raise ValueError('Duplicate source document path')
        # Preserve REST type envelopes, integer strings, timestamps, references,
        # bytes, nested maps/arrays and URLs; never coerce them to JS numbers.
        if not isinstance(doc.get('fields', {}), dict):
            raise ValueError('Invalid Firestore fields map')
        text = canonical(doc)
        docs[path] = (text, digest(text))
    if not docs:
        raise ValueError('Refusing to certify an empty export')
    return docs


def counts(docs):
    return dict(sorted(Counter(path.rsplit('/', 1)[0] for path in docs).items()))


def quote(value):
    return "'" + value.replace("'", "''") + "'"


def batch_id(value):
    return str(uuid.UUID(value))


def prepare(source, output, batch):
    batch = batch_id(batch)
    docs = source_documents(source)
    statements = []
    for path, (text, sha) in sorted(docs.items()):
        sql = ('INSERT INTO friendbook_cf_copy_staging '
               '(batch_id, path, document_json, sha256) VALUES (' +
               ','.join(quote(v) for v in (batch, path, text, sha)) + ');')
        if len(sql.encode('utf8')) > MAX_SQL_BYTES:
            raise ValueError('A document exceeds the offline SQL statement budget; use a separately reviewed importer')
        statements.append(sql)
    report = {'batch_id': batch, 'source_documents': len(docs), 'collection_counts': counts(docs),
              'source_snapshot_complete': False, 'production_copy_verified': False, 'safe_to_cutover': False}
    output = Path(output)
    # Never overwrite an earlier export/import artifact or re-use an output folder.
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    (output / 'copy.sql').write_text('\n'.join(statements) + '\n', encoding='utf8')
    (output / 'manifest.json').write_text(canonical(report) + '\n', encoding='utf8')
    return report


def verify(source, destination, batch):
    batch = batch_id(batch)
    expected = source_documents(source)
    rows = parse_json(Path(destination).read_text(encoding='utf8'))
    # Accept a plain result array or Wrangler's single-query JSON envelope.
    if isinstance(rows, list) and len(rows) == 1 and isinstance(rows[0], dict) and 'results' in rows[0]:
        if rows[0].get('success') is not True:
            raise ValueError('Destination export query failed')
        rows = rows[0]['results']
    if not isinstance(rows, list):
        raise ValueError('Expected destination JSON rows')
    actual = {}
    for row in rows:
        if not isinstance(row, dict) or row.get('batch_id') != batch:
            raise ValueError('Unexpected destination batch')
        path, text = row.get('path'), row.get('document_json')
        if not isinstance(path, str) or not isinstance(text, str) or path in actual:
            raise ValueError('Invalid or duplicate destination row')
        document = parse_json(text)
        if not isinstance(document, dict) or document.get('name') != PREFIX + path:
            raise ValueError('Destination document identity mismatch')
        text = canonical(document)
        sha = digest(text)
        if sha != row.get('sha256'):
            raise ValueError('Destination content hash mismatch')
        actual[path] = (text, sha)
    # Count equality alone is insufficient: paths and complete typed content must match.
    if expected != actual:
        raise ValueError('Source/destination document identities or contents do not match')
    return {'batch_id': batch, 'source_documents': len(expected), 'destination_documents': len(actual),
            'collection_counts': counts(actual), 'provided_exports_match': True,
            'source_snapshot_complete': False, 'production_copy_verified': False, 'safe_to_cutover': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    for command in ('prepare', 'verify'):
        p = sub.add_parser(command)
        p.add_argument('--source', required=True)
        p.add_argument('--batch', required=True)
        p.add_argument('--output' if command == 'prepare' else '--destination', required=True)
    args = parser.parse_args()
    try:
        if args.command == 'prepare':
            result = prepare(args.source, args.output, args.batch)
        else:
            result = verify(args.source, args.destination, args.batch)
    except (ValueError, OSError, TypeError, KeyError):
        # Do not print exception contents, document IDs or private payloads into CI logs.
        parser.exit(1, 'Offline validation failed; check input format, batch, completeness and output location.\n')
    print(canonical({'documents_checked': result['source_documents'], 'safe_to_cutover': False,
                     'mode': 'OFFLINE_ONLY_NOT_PRODUCTION_VERIFICATION'}))


if __name__ == '__main__':
    main()
