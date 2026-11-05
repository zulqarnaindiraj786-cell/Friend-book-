"""Read-only normalization proposal from the preserved D1 snapshot. Never writes SQL.
A successful audit is NOT a live-write fence or permission to activate D1.
"""
from collections import Counter, defaultdict
from datetime import datetime
import json
import os
import re
import sys
from copy_interaction_snapshot import D1, PREFIX, BackupError, canonical, digest, d1_query

TYPES = {'like', 'love', 'haha', 'wow', 'sad', 'angry'}
ID = re.compile(r'^[A-Za-z0-9_-]{1,128}$')


def decode(value):
    if not isinstance(value, dict) or len(value) != 1:
        raise BackupError('Malformed typed Firestore value')
    key, data = next(iter(value.items()))
    if key == 'mapValue': return {k: decode(v) for k, v in data.get('fields', {}).items()}
    if key == 'arrayValue': return [decode(v) for v in data.get('values', [])]
    if key == 'integerValue': return int(data)
    if key == 'doubleValue': return float(data)
    if key == 'nullValue': return None
    if key in ('stringValue', 'timestampValue', 'booleanValue', 'referenceValue', 'bytesValue', 'geoPointValue'):
        return data
    raise BackupError('Unrecognized Firestore value: manual review required')


def fields(document):
    return {key: decode(value) for key, value in document.get('fields', {}).items()}


def timestamp(value):
    if isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= 9007199254740991:
        return value
    if isinstance(value, str) and 'T' in value:
        parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
        if parsed.tzinfo is None: raise ValueError('Missing timezone')
        stamp = int(parsed.timestamp() * 1000)
        if 0 <= stamp <= 9007199254740991: return stamp
    raise ValueError('Unsupported timestamp')


def first(data, *keys):
    return next((data[k] for k in keys if data.get(k) not in (None, '')), '')


def plan(docs):
    issues = Counter()
    counts = Counter()
    parents = {}
    comments = {}
    reactions = {}
    reaction_times = {}
    pins = defaultdict(set)
    rows = defaultdict(list)
    entries = []
    for name, raw in docs.items():
        if not name.startswith(PREFIX + '/'):
            raise BackupError('Wrong source project')
        path = name[len(PREFIX) + 1:].split('/')
        data = fields(json.loads(raw))
        entries.append((path, data))
        if len(path) == 2 and path[0] in ('posts', 'reels'):
            parents['/'.join(path)] = data
    counts['resources'] = len(parents)

    def add_comment(resource, cid, data):
        counts['comment_representations'] += 1
        if resource not in parents:
            issues['comment_missing_resource'] += 1; return
        if not isinstance(cid, str) or not ID.fullmatch(cid):
            issues['unsupported_comment_id'] += 1; return
        try:
            uid = first(data, 'authorId', 'userId', 'uid')
            text = first(data, 'content', 'text', 'message')
            media = first(data, 'imageUrl', 'mediaUrl')
            parent = data.get('parentId') or None
            if not isinstance(uid, str) or not uid or len(uid) > 128: raise ValueError()
            if not isinstance(text, str) or not isinstance(media, str): raise ValueError()
            if parent is not None and (not isinstance(parent, str) or not ID.fullmatch(parent)): raise ValueError()
            mentions = data.get('mentions', [])
            if not isinstance(mentions, list): raise ValueError()
            if any(k in data and not isinstance(data[k], bool) for k in ('edited', 'removed', 'pinned')): raise ValueError()
            rx = data.get('reactions') or {}
            if not isinstance(rx, dict): raise ValueError()
            rx = dict(rx)
            liked = data.get('likedBy') or []
            if not isinstance(liked, list): raise ValueError()
            for actor in liked: rx.setdefault(actor, 'like')
            if any(not isinstance(actor, str) or not actor or (not isinstance(kind, str) or kind not in TYPES) for actor, kind in rx.items()): raise ValueError()
            row = {'resource': resource, 'id': cid, 'uid': uid, 'text': text,
                   'parent_id': parent, 'created_at': timestamp(data.get('createdAt')),
                   'media_url': media, 'mentions': mentions,
                   'name': first(data, 'authorName', 'userName', 'name'),
                   'photo': first(data, 'authorPhotoUrl', 'authorPhoto', 'userPhoto'),
                   'edited': bool(data.get('edited', False)), 'removed': bool(data.get('removed', False)),
                   'reactions': rx}
            if int(data.get('likes', len(rx))) != len(rx): issues['comment_reaction_counter_mismatch'] += 1
        except (ValueError, TypeError):
            issues['invalid_comment_fields'] += 1; return
        key = resource, cid
        if key in comments and comments[key] != row:
            issues['conflicting_comment_representations'] += 1
        else: comments[key] = row
        if data.get('pinned'): pins[resource].add(cid)

    for resource, data in parents.items():
        rows['resources'].append({'resource': resource, 'state': 'staging'})
        candidates = data.get('commentsMap') or {}
        if not isinstance(candidates, dict): issues['invalid_embedded_comment_map'] += 1
        else:
            for cid, value in candidates.items():
                if isinstance(value, dict): add_comment(resource, cid, value)
                else: issues['invalid_embedded_comment'] += 1
        candidates = data.get('commentsList') or []
        if not isinstance(candidates, list): issues['invalid_embedded_comment_list'] += 1
        else:
            for value in candidates:
                if isinstance(value, dict): add_comment(resource, value.get('id'), value)
                else: issues['invalid_embedded_comment'] += 1
        latest = data.get('lastComment')
        if isinstance(latest, dict): add_comment(resource, latest.get('id'), latest)
        pinned = first(data, 'pinnedCommentId', 'pinnedComment')
        if isinstance(pinned, str) and pinned: pins[resource].add(pinned)
        embedded = {}
        for field in ('reactions', 'reactionsMap'):
            value = data.get(field) or {}
            if not isinstance(value, dict): issues['invalid_embedded_reactions'] += 1; continue
            for actor, kind in value.items():
                if (not isinstance(kind, str) or kind not in TYPES) or not actor: issues['unsupported_reaction'] += 1; continue
                if actor in embedded and embedded[actor] != kind: issues['conflicting_embedded_reactions'] += 1
                embedded[actor] = kind
        liked = data.get('likedBy') or []
        if not isinstance(liked, list): issues['invalid_liked_by'] += 1
        else:
            for actor in liked:
                if not isinstance(actor, str) or not actor: issues['invalid_reactor'] += 1; continue
                embedded.setdefault(actor, 'love' if resource.startswith('reels/') else 'like')
        for actor, kind in embedded.items(): reactions[resource, actor] = kind

    for path, data in entries:
        if len(path) == 2 and path[0] in ('posts', 'reels'): continue
        if len(path) == 4 and path[0] in ('posts', 'reels'):
            resource = '/'.join(path[:2])
            if path[2] == 'comments': add_comment(resource, path[3], data); continue
            if path[2] == 'reactions':
                if resource not in parents: issues['reaction_missing_resource'] += 1; continue
                kind = data.get('type')
                key = resource, path[3]
                if (not isinstance(kind, str) or kind not in TYPES): issues['unsupported_canonical_reaction'] += 1; continue
                if first(data, 'userId', 'uid') not in ('', path[3]): issues['reaction_actor_mismatch'] += 1
                if key in reactions and reactions[key] != kind: issues['canonical_embedded_reaction_conflict'] += 1
                reactions[key] = kind
                try: reaction_times[key] = timestamp(data.get('updatedAt', data.get('createdAt')))
                except (ValueError, TypeError): issues['invalid_reaction_timestamp'] += 1
                continue
        if len(path) == 2 and path[0] == 'comments':
            target = first(data, 'postId', 'post_id')
            choices = [r for r in parents if r == 'posts/' + str(target) or
                       (str(target).startswith('reel_') and r == 'reels/' + str(target)[5:])]
            if len(choices) == 1: add_comment(choices[0], path[1], data)
            else: issues['ambiguous_or_orphan_root_comment'] += 1
            continue
        # Preserve raw data, but do not silently drop any unrecognized interaction path.
        issues['unmapped_snapshot_document'] += 1

    for (resource, cid), row in sorted(comments.items()):
        parent = row['parent_id']
        if parent and (resource, parent) not in comments: issues['missing_reply_parent'] += 1
        seen = {cid}; node = parent
        while node and (resource, node) in comments:
            if node in seen: issues['reply_cycle'] += 1; break
            seen.add(node); node = comments[resource, node]['parent_id']
        rows['comments'].append(row)
    totals = Counter(resource for resource, _ in reactions)
    comment_totals = Counter(resource for resource, _ in comments)
    for resource, data in parents.items():
        if data.get('likes', 0) != totals[resource]: issues['resource_reaction_counter_mismatch'] += 1
        if data.get('comments', 0) != comment_totals[resource]: issues['resource_comment_counter_mismatch'] += 1
    for resource, ids in pins.items():
        if len(ids) != 1: issues['multiple_pinned_comments'] += 1
        for cid in ids:
            if (resource, cid) not in comments: issues['missing_pinned_comment'] += 1
            rows['pins'].append({'resource': resource, 'id': cid})
    for (resource, actor), kind in sorted(reactions.items()):
        if (resource, actor) not in reaction_times:
            issues['missing_historical_reaction_timestamp'] += 1
        rows['reactions'].append({'resource': resource, 'uid': actor, 'type': kind,
                                  'updated_at': reaction_times.get((resource, actor))})
    counts.update({'comments': len(comments), 'reactions': len(reactions), 'snapshot_documents': len(docs)})
    return dict(rows), {'counts': dict(counts), 'blockers': dict(issues),
                        'normalized_import_applied': False, 'safe_to_cutover': False,
                        'requires_live_write_reconciliation': True}


def load_snapshot(session):
    batches = d1_query(session, 'SELECT DISTINCT batch_id FROM friendbook_cf_copy_staging')
    if len(batches) != 1:
        raise BackupError('Expected exactly one source batch; explicit verified-batch selection required')
    batch = batches[0]['batch_id']; docs = {}; after = ''
    while True:
        page = d1_query(session, 'SELECT path,document_json,sha256 FROM friendbook_cf_copy_staging WHERE batch_id=? AND path>? ORDER BY path LIMIT 100', (batch, after))
        if not page: break
        for row in page:
            text = row['document_json']; document = json.loads(text); name = PREFIX + '/' + row['path']
            if digest(text) != row['sha256'] or document.get('name') != name or name in docs:
                raise BackupError('Snapshot identity/hash verification failed')
            docs[name] = text
        if page[-1]['path'] <= after: raise BackupError('Non-advancing snapshot cursor')
        after = page[-1]['path']
    if not docs: raise BackupError('Snapshot empty')
    return batch, docs


def main():
    try:
        import requests
        with requests.Session() as session:
            session.headers['Authorization'] = 'Bearer ' + os.environ['CLOUDFLARE_API_TOKEN']
            batch, docs = load_snapshot(session)
        rows, report = plan(docs)
        report['batch'] = batch
        # Proposal contains private IDs/media; only keep it in ignored ephemeral storage.
        from pathlib import Path
        path = Path('migration-artifacts') / 'normalized-proposal.json'
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        with path.open('x') as out:
            os.chmod(path, 0o600); out.write(canonical({'rows': rows, 'report': report}))
        print(canonical(report))
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as out:
                out.write('Read-only normalized import proposal (no database writes).\n```json\n' + canonical(report) + '\n```\n')
        if report['blockers']:
            print('::error::Normalization has unresolved legacy-data blockers; no import or activation performed.')
            # Only aggregate issue labels/counts, never record IDs/content.
            print('::notice::' + canonical(report))
            return 1
        print('::notice::' + canonical(report))
        return 0
    except Exception as error:
        if isinstance(error, BackupError): print('::error::' + str(error))
        print(f'::error::Read-only normalization stopped ({type(error).__name__}); private details suppressed.')
        return 1

if __name__ == '__main__': sys.exit(main())
