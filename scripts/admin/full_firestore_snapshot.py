"""Read-only ALL-root Firestore snapshot -> INSERT-only private D1 staging.

Not RTDB/Storage/Remote Config/Supabase backup; not a normalized import/cutover.
No automatic execution. Provider quotas must be reviewed before --execute.
"""
import argparse
import json
import os
from pathlib import Path
import sys
import uuid

try:
    from .copy_interaction_snapshot import (Snapshot, BackupError, PROJECT, PREFIX,
                                           canonical, copy_and_verify, d1_query)
except ImportError:  # direct CLI invocation
    from copy_interaction_snapshot import (Snapshot, BackupError, PROJECT, PREFIX,
                                          canonical, copy_and_verify, d1_query)


class BudgetedDocuments(dict):
    def __init__(self, max_documents, max_bytes):
        super().__init__()
        self.max_documents, self.max_bytes, self.bytes = max_documents, max_bytes, 0

    def __setitem__(self, key, value):
        if key in self:
            raise BackupError('Duplicate snapshot document; no replacement allowed')
        size = len(value.encode('utf8'))
        if len(self) >= self.max_documents or self.bytes + size > self.max_bytes:
            raise BackupError('Snapshot safety budget exceeded; no truncation or D1 copy permitted')
        super().__setitem__(key, value)
        self.bytes += size


class FullFirestoreSnapshot(Snapshot):
    def __init__(self, session, max_documents=10_000, max_bytes=64 * 1024 * 1024):
        super().__init__(session)
        if max_documents < 1 or max_bytes < 1:
            raise ValueError('Snapshot budgets must be positive')
        self.docs = BudgetedDocuments(max_documents, max_bytes)
        self.roots = []
        self.complete = False

    def collections(self, parent):
        seen = set()
        for name in super().collections(parent):
            if (not isinstance(name, str) or not name or '/' in name or
                    name in ('.', '..') or name in seen):
                raise BackupError('Invalid or repeated collection identity')
            seen.add(name)
            yield name

    def walk(self, parent, collection):
        # Source root identifiers must not become arbitrary project/URL paths.
        if not isinstance(collection, str) or not collection or '/' in collection or collection in ('.', '..'):
            raise BackupError('Invalid collection identity')
        return super().walk(parent, collection)

    def export(self):
        if self.visited or self.docs or self.time:
            raise BackupError('Refusing to reuse a snapshot reader')
        self.count(PREFIX, 'users')  # pins one readTime before discovering any roots
        if not isinstance(self.time, str) or not self.time:
            raise BackupError('Missing authoritative snapshot timestamp')
        self.roots = list(self.collections(PREFIX))
        if not self.roots:
            raise BackupError('Refusing to certify an empty root listing')
        for name in sorted(self.roots):
            self.walk(PREFIX, name)
        if not self.docs:
            raise BackupError('Refusing to certify an empty Firestore backup')
        self.complete = True
        return self.docs


def private_source_copy(snapshot, directory):
    # Directory may not be reused; old/partial backups are never replaced/deleted.
    if not snapshot.complete or not snapshot.docs or sum(snapshot.counts.values()) != len(snapshot.docs):
        raise BackupError('Refusing to label an incomplete source snapshot as a full private copy')
    directory = Path(directory)
    if any(path.is_symlink() for path in [directory, *directory.absolute().parents]):
        raise BackupError('Private export paths must not follow symlinks')
    directory.mkdir(parents=True, mode=0o700, exist_ok=False)
    for filename, text in [('source.jsonl', '\n'.join(snapshot.docs[name] for name in sorted(snapshot.docs)) + '\n'),
                           ('manifest.json', canonical({
                               'scope': 'all_discovered_firestore_roots_and_descendants',
                               'read_time': snapshot.time, 'documents': len(snapshot.docs),
                               'collection_counts': snapshot.counts,
                               'other_firebase_services_backed_up': False,
                               'safe_to_cutover': False}) + '\n')]:
        descriptor = os.open(directory / filename, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'w', encoding='utf8') as output:
            output.write(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--quota-reviewed', action='store_true',
                        help='Operator confirms source reads/destination row writes have headroom; not a quota detector')
    parser.add_argument('--max-documents', type=int, default=10_000)
    parser.add_argument('--max-bytes', type=int, default=64 * 1024 * 1024)
    args = parser.parse_args()
    if args.max_documents < 1 or args.max_bytes < 1:
        parser.error('Snapshot budgets must be positive')
    if not args.execute or not args.quota_reviewed:
        print('NOT EXECUTED: requires --execute --quota-reviewed; no provider contacted.')
        return 2
    try:
        from google.oauth2 import service_account
        from google.auth.transport.requests import AuthorizedSession
        import requests
        info = json.loads(os.environ['FIREBASE_ADMIN_SERVICE_ACCOUNT'])
        if info.get('project_id') != PROJECT or info.get('type') != 'service_account':
            raise BackupError('Wrong Firebase project or credential type')
        token = os.environ['CLOUDFLARE_API_TOKEN']
        if not token:
            raise BackupError('Cloudflare credential unavailable')
        with requests.Session() as destination:
            destination.headers['Authorization'] = 'Bearer ' + token
            d1_query(destination, 'SELECT 1 AS connected')
            credentials = service_account.Credentials.from_service_account_info(
                info, scopes=['https://www.googleapis.com/auth/datastore'])
            with AuthorizedSession(credentials) as source:
                snapshot = FullFirestoreSnapshot(source, args.max_documents, args.max_bytes)
                docs = snapshot.export()
            batch = str(uuid.uuid4())
            private_source_copy(snapshot, Path('migration-artifacts') / ('full-firestore-' + batch))
            copy_and_verify(destination, batch, docs)
        # No UIDs, document paths, bodies, media URLs, tokens or credentials in logs.
        print(canonical({'batch': batch, 'read_time': snapshot.time,
                         'firestore_documents_copied_and_verified': len(docs),
                         'root_count': len(snapshot.roots), 'firebase_writes': 0,
                         'deletions': 0, 'safe_to_cutover': False,
                         'other_firebase_services_backed_up': False}))
        return 0
    except Exception as error:
        print('Backup stopped (' + type(error).__name__ + '). Private details suppressed; partial staging retained, cutover forbidden.')
        return 1


if __name__ == '__main__':
    sys.exit(main())
