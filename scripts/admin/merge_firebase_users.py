#!/usr/bin/env python3
"""Merge Firestore user profiles into D1 - names and photos only, nothing else.

The document rebuild converted D1's own rows into documents, so every author still renders
as the schema default: `users.display_name` is `NOT NULL DEFAULT 'User'` and the feed shows
that default with a grey avatar. The real names and photos only exist in Firebase, which the
rebuild deliberately left untouched.

This fills the gaps by uid, and only the gaps:

  users.display_name / users.photo_url          the data API's author fields
  users document displayName / photoUrl         what the app reads for a profile
  posts.author_name / posts.author_photo        the feed row
  posts documents authorName / authorPhoto      the feed document

Rules, enforced in the SQL itself:
  * no INSERT and no DELETE - a user that is not already in D1 is reported, never created;
  * a name or photo is written only when the stored value is a placeholder
    (NULL, '', 'User'), so real data is never replaced;
  * a post is only touched when its author exists in Firebase with a real name.

Subcommands:
  plan     read-only: who is in Firebase, who is in D1, what would change
  apply    the merge (guarded updates above)
  verify   read-only: what the feed would render now, plus a few real rows
"""
import json
import os
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from rebuild_documents_d1 import d1, say  # noqa: E402  (same D1 REST helper as the rebuild)

PROJECT = os.environ.get('FIREBASE_PROJECT_ID', 'friendbook-17551')
SCOPE = 'https://www.googleapis.com/auth/datastore'
PAGE = 400
PLACEHOLDER_NAMES = ('', 'user', 'unknown', 'null', 'none')
MAX_SAMPLE = 6


class MergeError(RuntimeError):
    pass


def placeholder(value):
    """True when a stored name is the schema default rather than a real one."""
    if value is None:
        return True
    text = str(value).strip()
    return text.lower() in PLACEHOLDER_NAMES


# --------------------------------------------------------------------------- Firebase

def firestore_session(scopes=None):
    raw = os.environ.get('FIREBASE_ADMIN_SERVICE_ACCOUNT', '').strip()
    if not raw:
        raise MergeError('FIREBASE_ADMIN_SERVICE_ACCOUNT is missing; nothing was read or written')
    try:
        info = json.loads(raw)
    except ValueError as error:
        raise MergeError(f'FIREBASE_ADMIN_SERVICE_ACCOUNT is not JSON: {error}') from None
    if info.get('type') != 'service_account':
        raise MergeError('the secret is not a service-account JSON')
    from google.auth.transport.requests import AuthorizedSession
    from google.oauth2 import service_account
    credentials = service_account.Credentials.from_service_account_info(
        info, scopes=scopes or [SCOPE])
    return AuthorizedSession(credentials)


def identity_users(session):
    """The same profiles through Firebase Authentication.

    Firestore reads are quota-metered per project and answer 429 with a lot of room left in
    the day (observed live). Auth keeps the display name and photo for every account and is
    metered separately, so it is the fallback - and the tie-breaker - for the same fields.
    """
    url = f'https://identitytoolkit.googleapis.com/v1/projects/{PROJECT}/accounts:batchGet'
    token = None
    users = []
    while True:
        params = {'maxResults': 1000}
        if token:
            params['nextPageToken'] = token
        response = session.get(url, params=params, timeout=90)
        if response.status_code != 200:
            detail = ''
            try:
                detail = str((response.json() or {}).get('error', {}).get('message', ''))[:160]
            except (ValueError, AttributeError):
                pass
            raise MergeError(f'Firebase Auth read failed (HTTP {response.status_code}'
                             f'{": " + detail if detail else ""}); nothing was written')
        body = response.json()
        for account in body.get('users', []):
            uid = account.get('localId')
            if not uid:
                continue
            name = account.get('displayName')
            photo = account.get('photoUrl')
            if not photo:
                for provider in account.get('providerUserInfo') or []:
                    if provider.get('photoUrl'):
                        photo = provider['photoUrl']
                        break
            users.append({'uid': uid,
                          'name': name.strip() if isinstance(name, str) and name.strip() else None,
                          'photo': photo.strip() if isinstance(photo, str) and photo.strip() else None})
        token = body.get('nextPageToken')
        if not token:
            break
    return users


def firestore_value(field):
    """Unwrap one Firestore typed value (only the types a profile can use)."""
    if not isinstance(field, dict):
        return None
    for key in ('stringValue', 'timestampValue'):
        if key in field:
            return field[key]
    if 'integerValue' in field:
        return int(field['integerValue'])
    if 'doubleValue' in field:
        return field['doubleValue']
    if 'booleanValue' in field:
        return bool(field['booleanValue'])
    if 'nullValue' in field:
        return None
    return None


def firestore_users(session):
    """Every user document: uid plus the first non-empty name and photo it carries."""
    url = f'https://firestore.googleapis.com/v1/projects/{PROJECT}/databases/(default)/documents/users'
    token = None
    users = []
    while True:
        params = {'pageSize': PAGE}
        if token:
            params['pageToken'] = token
        response = session.get(url, params=params, timeout=90)
        if response.status_code != 200:
            detail = ''
            try:
                detail = str((response.json() or {}).get('error', {}).get('message', ''))[:160]
            except (ValueError, AttributeError):
                pass
            raise MergeError(f'Firestore users read failed (HTTP {response.status_code}'
                             f'{": " + detail if detail else ""}); nothing was written')
        body = response.json()
        for document in body.get('documents', []):
            uid = document.get('name', '').rsplit('/', 1)[-1]
            if not uid:
                continue
            fields = document.get('fields', {})
            name = None
            for key in ('displayName', 'name', 'fullName', 'username'):
                candidate = firestore_value(fields.get(key))
                if isinstance(candidate, str) and candidate.strip() and not placeholder(candidate):
                    name = candidate.strip()
                    break
            photo = None
            for key in ('photoUrl', 'photoURL', 'photo', 'profilePhoto', 'profilePhotoUrl'):
                candidate = firestore_value(fields.get(key))
                if isinstance(candidate, str) and candidate.strip():
                    photo = candidate.strip()
                    break
            users.append({'uid': uid, 'name': name, 'photo': photo})
        token = body.get('nextPageToken')
        if not token:
            break
    return users


# --------------------------------------------------------------------------- D1 reads

def rows(sql, params=()):
    result, _ = d1(sql, params)
    return result


def all_rows(sql, params=(), page=500, key='path'):
    """Keyset paginate a read so a big collection never truncates silently."""
    collected = []
    cursor = ''
    while True:
        batch = rows(sql + ' AND ' + key + ' > ? ORDER BY ' + key + ' LIMIT ?',
                     [*params, cursor, page])
        if not batch:
            return collected
        collected.extend(batch)
        cursor = batch[-1][key]
        if len(batch) < page:
            return collected


def counters():
    sql = ("SELECT (SELECT COUNT(*) FROM users) AS usersTable,"
           " (SELECT COUNT(*) FROM posts) AS postsTable,"
           " (SELECT COUNT(*) FROM friendbook_cf_documents WHERE collection = 'users') AS userDocs,"
           " (SELECT COUNT(*) FROM friendbook_cf_documents WHERE collection = 'posts') AS postDocs")
    return rows(sql)[0]


def placeholderStats():
    users = rows("SELECT COUNT(*) AS n FROM users WHERE display_name IS NULL OR TRIM(display_name) IN ('', 'User')")[0]['n']
    userPhotos = rows("SELECT COUNT(*) AS n FROM users WHERE photo_url IS NULL OR TRIM(photo_url) = ''")[0]['n']
    userDocs = rows("SELECT COUNT(*) AS n FROM friendbook_cf_documents WHERE collection = 'users' "
                    "AND (json_extract(document_json, '$.displayName') IS NULL "
                    "OR TRIM(json_extract(document_json, '$.displayName')) IN ('', 'User'))")[0]['n']
    postDocs = rows("SELECT COUNT(*) AS n FROM friendbook_cf_documents WHERE collection = 'posts' "
                    "AND (json_extract(document_json, '$.authorName') IS NULL "
                    "OR TRIM(json_extract(document_json, '$.authorName')) IN ('', 'User'))")[0]['n']
    postRows = rows("SELECT COUNT(*) AS n FROM posts WHERE author_name IS NULL OR TRIM(author_name) IN ('', 'User')")[0]['n']
    return {'usersTableWithoutName': users, 'usersTableWithoutPhoto': userPhotos,
            'userDocumentsWithoutName': userDocs, 'postDocumentsWithoutAuthorName': postDocs,
            'postsTableWithoutAuthorName': postRows}


# --------------------------------------------------------------------------- plan

SOURCE = os.environ.get('PROFILE_SOURCE', 'auto').strip().lower()


def load_profiles():
    """Every profile we can read, Firestore first and Auth filling whatever it could not.

    Returns (users, sources). A source that refuses (quota, permission) is reported, not
    fatal, as long as the other one answered - the merge only needs names and photos.
    """
    sources = {}
    firestore = []
    auth = []
    if SOURCE in ('auto', 'firestore'):
        try:
            firestore = firestore_users(firestore_session())
            sources['firestore'] = len(firestore)
        except MergeError as error:
            sources['firestoreError'] = str(error)[:200]
            if SOURCE == 'firestore':
                raise
    if SOURCE in ('auto', 'auth'):
        try:
            auth = identity_users(firestore_session(
                ['https://www.googleapis.com/auth/cloud-platform',
                 'https://www.googleapis.com/auth/identitytoolkit']))
            sources['auth'] = len(auth)
        except MergeError as error:
            sources['authError'] = str(error)[:200]
            if SOURCE == 'auth':
                raise
    if not firestore and not auth:
        raise MergeError(f'no profile source answered ({sources})')

    merged = {}
    for user in auth:                     # Auth is complete for every account
        merged[user['uid']] = dict(user)
    for user in firestore:                # a Firestore profile wins when it has the field
        current = merged.setdefault(user['uid'], {'uid': user['uid'], 'name': None, 'photo': None})
        if user['name']:
            current['name'] = user['name']
        if user['photo']:
            current['photo'] = user['photo']
    return list(merged.values()), sources


def plan():
    firebase, sources = load_profiles()
    firebaseByName = {user['uid']: user for user in firebase}
    d1Users = rows('SELECT uid, display_name, photo_url FROM users')
    missingInD1 = [user['uid'] for user in firebase if user['uid'] not in {u['uid'] for u in d1Users}]
    authors = rows('SELECT author_id, COUNT(*) AS posts FROM posts GROUP BY author_id')

    resolvable = sum(1 for author in authors
                     if firebaseByName.get(author['author_id'], {}).get('name'))
    unresolved = [author['author_id'] for author in authors
                  if not firebaseByName.get(author['author_id'], {}).get('name')]

    say({'mode': 'PLAN',
         'sources': sources,
         'firebase': {'users': len(firebase),
                      'withName': sum(1 for u in firebase if u['name']),
                      'withPhoto': sum(1 for u in firebase if u['photo']),
                      'sample': [{'uid': u['uid'][:6] + '…', 'name': u['name'],
                                  'hasPhoto': bool(u['photo'])} for u in firebase[:MAX_SAMPLE]]},
         'd1': {'users': len(d1Users),
                'usersMissingFromD1': len(missingInD1),
                'usersMissingFromD1Sample': [uid[:6] + '…' for uid in missingInD1[:MAX_SAMPLE]],
                **placeholderStats()},
         'posts': {'rows': counters()['postsTable'],
                   'distinctAuthors': len(authors),
                   'authorsResolvableFromFirebase': resolvable,
                   'authorsStillUnresolved': len(unresolved),
                   'unresolvedSample': [uid[:6] + '…' for uid in unresolved[:MAX_SAMPLE]],
                   'documents': counters()['postDocs']}})
    return 0


# --------------------------------------------------------------------------- apply

def author_of(record, document, postAuthors):
    """Who wrote this post document: the posts row, else the stored owner, else the document.

    A posts row migrated from Firestore can carry an empty author_id, and a document written
    by the app can carry no owner_uid - so neither field alone is enough.
    """
    for candidate in (postAuthors.get(record.get('doc_id')), record.get('owner_uid'),
                      document.get('authorId'), document.get('authorUid')):
        if isinstance(candidate, str) and candidate.strip():
            return candidate.strip()
    return None


def updateDocument(path, document, now):
    """Write back one document we edited in memory. Only ever an UPDATE of that path."""
    d1('UPDATE friendbook_cf_documents SET document_json = ?, updated_at = ? WHERE path = ?',
       [json.dumps(document, ensure_ascii=False, sort_keys=True), now, path])


def apply(limit=0):
    firebase, sources = load_profiles()
    if limit:
        firebase = firebase[:limit]
    now = int(time.time() * 1000)
    stats = {'sources': sources, 'firebaseUsers': len(firebase), 'noNameInFirebase': 0,
             'usersTableName': 0, 'usersTablePhoto': 0,
             'userDocumentsName': 0, 'userDocumentsPhoto': 0,
             'postsTableName': 0, 'postsTablePhoto': 0,
             'postDocumentsName': 0, 'postDocumentsPhoto': 0,
             'notInD1': []}

    d1Uids = {row['uid'] for row in rows('SELECT uid FROM users')}
    userDocs = {row['doc_id']: row for row in all_rows(
        "SELECT path, doc_id, document_json FROM friendbook_cf_documents WHERE collection = 'users'")}

    for user in firebase:
        uid, name, photo = user['uid'], user['name'], user['photo']
        if not name:
            stats['noNameInFirebase'] += 1
        if uid not in d1Uids:
            # Never create a user: report it so the caller can decide.
            if len(stats['notInD1']) < 50:
                stats['notInD1'].append(uid[:6] + '…')
            continue
        if name:
            _, meta = d1("UPDATE users SET display_name = ? WHERE uid = ? "
                         "AND (display_name IS NULL OR TRIM(display_name) IN ('', 'User'))",
                         [name, uid])
            stats['usersTableName'] += int((meta or {}).get('changes') or 0)
            _, meta = d1("UPDATE posts SET author_name = ? WHERE author_id = ? "
                         "AND (author_name IS NULL OR TRIM(author_name) IN ('', 'User'))",
                         [name, uid])
            stats['postsTableName'] += int((meta or {}).get('changes') or 0)
        if photo:
            _, meta = d1("UPDATE users SET photo_url = ? WHERE uid = ? "
                         "AND (photo_url IS NULL OR TRIM(photo_url) = '')", [photo, uid])
            stats['usersTablePhoto'] += int((meta or {}).get('changes') or 0)
            _, meta = d1("UPDATE posts SET author_photo = ? WHERE author_id = ? "
                         "AND (author_photo IS NULL OR TRIM(author_photo) = '')", [photo, uid])
            stats['postsTablePhoto'] += int((meta or {}).get('changes') or 0)

        record = userDocs.get(uid)
        if not record or not record.get('document_json'):
            continue
        try:
            document = json.loads(record['document_json'])
        except ValueError:
            continue
        changed = False
        if name and placeholder(document.get('displayName')):
            document['displayName'] = name
            stats['userDocumentsName'] += 1
            changed = True
        if photo and placeholder(document.get('photoUrl')):
            document['photoUrl'] = photo
            stats['userDocumentsPhoto'] += 1
            changed = True
        if changed:
            updateDocument(record['path'], document, now)

    # Posts documents: patch the authors' feed copy in place, never the whole document.
    byUid = {user['uid']: user for user in firebase}
    postDocs = all_rows("SELECT path, doc_id, owner_uid, document_json "
                        "FROM friendbook_cf_documents WHERE collection = 'posts'")
    # A document written by the app does not always carry owner_uid; the posts row always
    # knows its author, so resolve through it and fall back to the stored owner only then.
    postAuthors = {row['id']: row['author_id'] for row in rows('SELECT id, author_id FROM posts')}
    for record in postDocs:
        try:
            document = json.loads(record['document_json'] or '{}')
        except ValueError:
            continue
        authorId = author_of(record, document, postAuthors)
        user = byUid.get(authorId)
        if not user or not (user['name'] or user['photo']):
            continue
        changed = False
        if user['name'] and placeholder(document.get('authorName')):
            document['authorName'] = user['name']
            stats['postDocumentsName'] += 1
            changed = True
        if user['photo'] and placeholder(document.get('authorPhoto')):
            document['authorPhoto'] = user['photo']
            stats['postDocumentsPhoto'] += 1
            changed = True
        if changed:
            updateDocument(record['path'], document, now)

    # Third pass: an author with no name in Firebase (92 of 412 accounts never set one) but a
    # real name on their D1 row still has a name we can show. Same guard, same direction.
    stats['postsTableNameFromD1'] = 0
    stats['postDocumentsNameFromD1'] = 0
    d1Names = {row['uid']: row['display_name'] for row in rows("SELECT uid, display_name FROM users")
               if not placeholder(row['display_name'])}
    stats['d1NamesAvailable'] = len(d1Names)
    for record in postDocs:
        try:
            document = json.loads(record['document_json'] or '{}')
        except ValueError:
            continue
        authorId = author_of(record, document, postAuthors)
        # Firebase wins: never let the D1 copy replace a name the live profile just gave us.
        if byUid.get(authorId, {}).get('name'):
            continue
        name = d1Names.get(authorId)
        if not name:
            continue
        if placeholder(document.get('authorName')):
            document['authorName'] = name
            stats['postDocumentsNameFromD1'] += 1
            updateDocument(record['path'], document, now)
            _, meta = d1("UPDATE posts SET author_name = ? WHERE author_id = ? "
                         "AND (author_name IS NULL OR TRIM(author_name) IN ('', 'User'))",
                         [name, authorId])
            stats['postsTableNameFromD1'] += int((meta or {}).get('changes') or 0)

    stats['notInD1'] = {'count': len(stats['notInD1']), 'sample': stats['notInD1']}
    say({'mode': 'APPLY', 'changed': stats})
    verify()
    return 0


# --------------------------------------------------------------------------- verify

def verify():
    sample = rows("SELECT p.id, p.author_id, p.author_name, p.author_photo, "
                  "json_extract(d.document_json, '$.authorName') AS docAuthorName, "
                  "json_extract(d.document_json, '$.authorPhoto') AS docAuthorPhoto "
                  "FROM posts p JOIN friendbook_cf_documents d "
                  "ON d.path = 'posts/' || p.id ORDER BY p.id LIMIT ?", [MAX_SAMPLE])
    # Exactly what the feed renders, newest first: the document fields the app binds.
    feed = rows("SELECT p.id, json_extract(d.document_json, '$.authorName') AS authorName, "
                "json_extract(d.document_json, '$.authorPhoto') AS authorPhoto, "
                "substr(COALESCE(json_extract(d.document_json, '$.content'), ''), 1, 60) AS content, "
                "json_extract(d.document_json, '$.createdAt') AS createdAt, "
                "json_extract(d.document_json, '$.authorId') AS docAuthorId, "
                "p.author_id AS rowAuthorId, d.owner_uid AS ownerUid "
                "FROM friendbook_cf_documents d LEFT JOIN posts p ON d.path = 'posts/' || p.id "
                "WHERE d.collection = 'posts' "
                "ORDER BY COALESCE(json_extract(d.document_json, '$.createdAt'), 0) DESC LIMIT 12")
    stats = placeholderStats()
    realTable = counters()['postsTable'] - stats['postsTableWithoutAuthorName']
    realDocs = counters()['postDocs'] - stats['postDocumentsWithoutAuthorName']
    outstanding = rows(
        "SELECT p.id, p.author_id, "
        "(SELECT display_name FROM users u WHERE u.uid = p.author_id) AS usersTableName, "
        "p.author_name AS rowName, json_extract(d.document_json, '$.authorName') AS docName "
        "FROM friendbook_cf_documents d JOIN posts p ON d.path = 'posts/' || p.id "
        "WHERE d.collection = 'posts' AND (json_extract(d.document_json, '$.authorName') IS NULL "
        "OR TRIM(json_extract(d.document_json, '$.authorName')) IN ('', 'User'))")
    fixable = [row for row in outstanding if not placeholder(row['usersTableName'])]
    say({'mode': 'VERIFY',
         'outstanding': {'documentsWithoutAuthorName': len(outstanding),
                         'fixableFromD1UsersTable': len(fixable),
                         'authorsWithNoNameAnywhere': len(outstanding) - len(fixable),
                         'sample': [{'id': row['id'], 'authorId': (row['author_id'] or '')[:6] + '…',
                                     'd1Name': row['usersTableName']}
                                    for row in outstanding[:MAX_SAMPLE]]},
         'feedNewestFirst': [{'id': row['id'], 'authorName': row['authorName'],
                              'authorRef': (row['rowAuthorId'] or row['docAuthorId']
                                            or row['ownerUid'] or '')[:6] or None,
                              'hasPhoto': bool(row['authorPhoto']),
                              'photoHost': (row['authorPhoto'] or '').split('/')[2]
                              if row['authorPhoto'] and '://' in row['authorPhoto'] else None,
                              'content': row['content'], 'createdAt': row['createdAt']}
                             for row in feed],
         'users': {'withoutName': stats['usersTableWithoutName'],
                   'withoutPhoto': stats['usersTableWithoutPhoto'],
                   'documentsWithoutName': stats['userDocumentsWithoutName']},
         'posts': {'rowsWithRealAuthorName': realTable,
                   'rowsWithoutAuthorName': stats['postsTableWithoutAuthorName'],
                   'documentsWithRealAuthorName': realDocs,
                   'documentsWithoutAuthorName': stats['postDocumentsWithoutAuthorName'],
                   'sample': [{'id': row['id'], 'authorId': (row['author_id'] or '')[:6] + '…',
                               'rowName': row['author_name'], 'docName': row['docAuthorName'],
                               'hasRowPhoto': bool(row['author_photo']),
                               'hasDocPhoto': bool(row['docAuthorPhoto'])}
                              for row in sample]}})
    return 0


def orphans(limit=5):
    """Read-only: the documents the feed still cannot attribute, in full.

    Printed so a gap is explained rather than assumed: if the stored document carries no author
    reference at all, no merge can invent one.
    """
    postAuthors = {row['id']: row['author_id'] for row in rows('SELECT id, author_id FROM posts')}
    found = []
    for record in all_rows("SELECT path, doc_id, owner_uid, document_json "
                           "FROM friendbook_cf_documents WHERE collection = 'posts'"):
        try:
            document = json.loads(record['document_json'] or '{}')
        except ValueError:
            continue
        authorId = author_of(record, document, postAuthors)
        if authorId and not placeholder(document.get('authorName')):
            continue
        found.append({'path': record['path'], 'ownerUid': record['owner_uid'],
                      'rowAuthorId': postAuthors.get(record['doc_id']),
                      'resolvedAuthorId': authorId,
                      'authorFields': {key: document.get(key) for key in
                                       ('authorId', 'authorUid', 'authorName', 'authorPhoto',
                                        'author', 'uid', 'content', 'privacy', 'likes',
                                        'createdAt', 'sponsored')}})
        if len(found) >= limit:
            break
    say({'mode': 'ORPHANS', 'count': len(found), 'documents': found})
    return 0


def main(argv):
    command = (argv[1] if len(argv) > 1 else 'plan').strip()
    if command == 'plan':
        return plan()
    if command == 'apply':
        limit = 0
        for flag in argv[2:]:
            if flag.startswith('--limit='):
                limit = int(flag.split('=', 1)[1])
        return apply(limit)
    if command == 'verify':
        return verify()
    if command == 'orphans':
        limit = 5
        for flag in argv[2:]:
            if flag.startswith('--limit='):
                limit = int(flag.split('=', 1)[1])
        return orphans(limit)
    print('usage: merge_firebase_users.py [plan|apply [--limit=N]|verify]', file=sys.stderr)
    return 2


if __name__ == '__main__':
    try:
        sys.exit(main(sys.argv))
    except MergeError as error:
        print(f'::error::{error}', file=sys.stderr)
        sys.exit(1)
