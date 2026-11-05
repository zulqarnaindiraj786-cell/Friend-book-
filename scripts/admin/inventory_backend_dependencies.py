"""Offline source inventory; no credentials, network calls or migration writes.

A clean source gate is NOT proof of a successful data migration or app capacity.
Only production-source directories are scanned; backups/tests remain separate.
"""
import argparse
import json
from pathlib import Path
import re

SOURCE_SUFFIXES = {'.java', '.kt', '.js', '.mjs', '.ts'}
STATE_PATTERNS = {
    'firebase_remote_config': re.compile(r'com\.google\.firebase\.remoteconfig\b|FirebaseRemoteConfig\b|firebaseremoteconfig\.googleapis\.com'),
    'supabase_backend': re.compile(r'\bSUPABASE_(?:URL|ANON_KEY)|supabase\.co|@supabase/supabase-js'),
    'firestore': re.compile(r'com\.google\.firebase\.firestore\b|FirebaseFirestore\b|'
                            r'\bFb\.db\s*\(|firestore\.googleapis\.com|'
                            r'firebase-admin/firestore|\bgetFirestore\s*\(|\badmin\.firestore\s*\('),
    'realtime_database': re.compile(r'com\.google\.firebase\.database\b|FirebaseDatabase\b|'
                                    r'\bFb\.rtdb\s*\(|firebaseio\.com|firebasedatabase\.app|'
                                    r'firebase-admin/database|\bgetDatabase\s*\(|\badmin\.database\s*\('),
    'firebase_storage': re.compile(r'com\.google\.firebase\.storage\b|FirebaseStorage\b|\b(?:StorageReference|UploadTask)\b|'
                                   r'\bFb\.storage\s*\(|firebasestorage\.googleapis\.com|'
                                   r'firebase-admin/storage|\badmin\.storage\s*\('),
}
ALLOWED_PATTERNS = {
    'firebase_auth': re.compile(r'com\.google\.firebase\.auth\b|FirebaseAuth\b|'
                                r'firebase-admin/auth|securetoken@system\.gserviceaccount\.com|'
                                r'securetoken\.google\.com'),
    'fcm_delivery': re.compile(r'com\.google\.firebase\.messaging\b|FirebaseMessaging\b|'
                               r'firebase-admin/messaging|fcm\.googleapis\.com'),
}
# Preserve quoted strings while removing comments. This is a heuristic inventory,
# not a Java/TypeScript compiler, call graph or an exhaustive dependency proof.
LEX = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|`(?:\\.|[^`\\])*`|'
                 r'//[^\n]*|/\*[\s\S]*?\*/')
LITERAL_COLLECTION = re.compile(r'\.collection(?:Group)?\s*\(\s*["\']([A-Za-z0-9_-]+)["\']\s*\)')
LITERAL_RTDB = re.compile(r'\.getReference\s*\(\s*["\']([A-Za-z0-9_./-]+)["\']\s*\)')


def without_comments(text):
    return LEX.sub(lambda m: '\n' * m[0].count('\n') if m[0].startswith(('//', '/*')) else m[0], text)


def production_sources(root):
    root = Path(root).resolve()
    required = [root / 'app/src/main', root / 'cloudflare', root / 'worker.js']
    if not all(p.exists() for p in required):
        raise ValueError('Expected repository production-source roots are missing')
    paths = {root / 'worker.js'}
    for folder in [root / 'app/src/main', root / 'cloudflare', root / 'supabase/functions']:
        if folder.exists():
            paths.update(p for p in folder.rglob('*') if p.is_file() and p.suffix in SOURCE_SUFFIXES)
    for path in sorted(paths):
        relative = path.relative_to(root)
        if ('test' in relative.parts or 'tests' in relative.parts or 'fixtures' in relative.parts or
                'node_modules' in relative.parts or '.test.' in path.name or
                path.stem.endswith('_test') or path.name in {'integration.mjs', 'build-assets.mjs'}):
            continue
        if not path.resolve().is_relative_to(root):
            raise ValueError('Refusing to scan a source symlink outside the repository')
        yield relative.as_posix(), without_comments(path.read_text(encoding='utf8'))


def inventory(root):
    remaining, allowed = [], []
    files = 0
    collections, rtdb_roots = set(), set()
    for path, source in production_sources(root):
        files += 1
        services = sorted(name for name, pattern in STATE_PATTERNS.items() if pattern.search(source))
        permitted = sorted(name for name, pattern in ALLOWED_PATTERNS.items() if pattern.search(source))
        if services:
            names = sorted(set(LITERAL_COLLECTION.findall(source)))
            roots = sorted(set(LITERAL_RTDB.findall(source)))
            collections.update(names)
            rtdb_roots.update(roots)
            remaining.append({'path': path, 'services': services,
                              'literal_collection_names': names, 'literal_rtdb_paths': roots})
        if permitted:
            allowed.append({'path': path, 'services': permitted})
    return {
        'inventory_kind': 'offline_production_source_heuristic',
        'target': {'app_data_and_realtime': 'cloudflare', 'new_media_uploads': 'cloudinary',
                   'firebase_allowed': ['auth', 'fcm_delivery_only']},
        'source_files_scanned': files,
        'remaining_state_dependency_files': len(remaining),
        'remaining_by_service': {service: sum(service in row['services'] for row in remaining)
                                 for service in STATE_PATTERNS},
        'literal_collection_names': sorted(collections), 'literal_rtdb_paths': sorted(rtdb_roots),
        'remaining': remaining, 'allowed_auth_and_delivery': allowed,
        'source_reference_gate': 'BLOCKED' if remaining else 'PASS',
        'production_data_copy_verified': False, 'end_to_end_migration_verified': False,
        'capacity_verified': False, 'safe_to_cutover': False,
        'limitations': ['Literal names do not enumerate live collections, objects or nested subcollections.',
                        'Aliases, generated URLs, dependencies and runtime configuration need separate review.',
                        'Old clients can continue writing until an upgrade-first fence is verified.',
                        'A source-only PASS cannot authorize cutover or certify quotas/capacity.'],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--output', type=Path, help='Optional source-only report; contains no live export records')
    parser.add_argument('--check', action='store_true', help='Exit 2 when state dependencies remain')
    args = parser.parse_args()
    result = inventory(args.root)
    text = json.dumps(result, indent=2, sort_keys=True) + '\n'
    if args.output:
        # Do not silently overwrite a previous assessment or unrelated file.
        with args.output.open('x', encoding='utf8') as stream:
            stream.write(text)
    else:
        print(text, end='')
    return 2 if args.check and result['remaining_state_dependency_files'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
