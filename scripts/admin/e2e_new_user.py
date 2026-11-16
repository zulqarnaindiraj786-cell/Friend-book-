#!/usr/bin/env python3
"""End-to-end proof that a brand-new account can use the D1 data plane.

A real account is created in Firebase Auth (a throwaway address), then everything the app does
after signup is driven through the Worker over HTTP with that account's own ID token - the same
requests the APK makes, not a database shortcut:

  signup (Auth only)      -> identity toolkit, no Firestore
  profile                 -> PUT /v1/docs/users/<uid>            (documents + users table)
  post                    -> PUT /v1/docs/posts/<id>
  like                    -> PATCH /v1/docs/posts/<id>  {likes +1, reactionsMap}
  comment                 -> PUT /v1/docs/comments/<id>
  chat + message          -> PUT /v1/docs/chats/<id>, /v1/docs/messages/<id>
  read back               -> GET each document, plus a page of existing posts

Every step prints its HTTP status and the field that proves it. The test account's post is
"Only me" and the chat is between the two throwaway accounts, so nothing lands in a real feed.
"""
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

WORKER = os.environ.get('WORKER_URL', 'https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev').rstrip('/')


def api_key():
    """The Firebase web API key: the Android client identifier, not a credential.

    It ships inside every APK and lives in app/google-services.json, so reading it from the
    checkout adds no secret; the repository secret of the same name is simply not set. Without
    it the identity toolkit cannot sign a test account in.
    """
    value = os.environ.get('FIREBASE_API_KEY', '').strip()
    if value:
        return value
    path = pathlib.Path(__file__).resolve().parents[2] / 'app' / 'google-services.json'
    try:
        data = json.loads(path.read_text())
        return str(data['client'][0]['api_key'][0]['current_key']).strip()
    except Exception:
        return ''


API_KEY = api_key()
STAMP = int(time.time())
TAG = f'e2e{STAMP}'
PASSWORD = f'E2e-{STAMP}-Passw0rd!'


class E2EError(RuntimeError):
    pass


def http(method, url, body=None, token=None, timeout=45):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header('Content-Type', 'application/json')
    if token:
        request.add_header('Authorization', f'Bearer {token}')
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode() or '{}'
            try:
                return response.status, json.loads(raw)
            except ValueError:
                return response.status, {'raw': raw[:400]}
    except urllib.error.HTTPError as error:
        raw = error.read().decode() or '{}'
        try:
            return error.code, json.loads(raw)
        except ValueError:
            return error.code, {'raw': raw[:400]}


def authCall(action, payload):
    url = f'https://identitytoolkit.googleapis.com/v1/accounts:{action}?key={API_KEY}'
    return http('POST', url, payload)


def signUp(email):
    status, body = authCall('signUp', {'email': email, 'password': PASSWORD,
                                       'displayName': None, 'returnSecureToken': True})
    if status == 200 and body.get('idToken'):
        return body
    message = str((body.get('error') or {}).get('message', ''))
    if 'EMAIL_EXISTS' in message:
        status, body = authCall('signInWithPassword', {'email': email, 'password': PASSWORD,
                                                       'returnSecureToken': True})
        if status == 200 and body.get('idToken'):
            return body
    raise E2EError(f'signUp failed: HTTP {status} {message[:200]}')


def setDisplayName(token, name):
    # Auth is the only place a profile field is written at signup.
    status, body = http('POST', f'https://identitytoolkit.googleapis.com/v1/accounts:update?key={API_KEY}',
                        {'idToken': token, 'displayName': name, 'returnSecureToken': True})
    return status, body


def main():
    if not API_KEY:
        raise E2EError('FIREBASE_API_KEY is missing; nothing was attempted')

    steps = []

    def record(step, ok, detail):
        steps.append({'step': step, 'ok': bool(ok), 'detail': detail})
        print(json.dumps({'step': step, 'ok': bool(ok), **detail}, sort_keys=True), flush=True)

    # ---- 1. signup: Auth only -----------------------------------------------------
    email = f'friendbook.e2e.{STAMP}@example.com'
    peerEmail = f'friendbook.e2e.peer.{STAMP}@example.com'
    me = signUp(email)
    peer = signUp(peerEmail)
    uid, token = me['localId'], me['idToken']
    peerUid, peerToken = peer['localId'], peer['idToken']
    name = f'Arena Test {STAMP % 100000}'
    peerName = f'Arena Peer {STAMP % 100000}'
    setDisplayName(token, name)
    setDisplayName(peerToken, peerName)
    record('signup (Firebase Auth)', True,
           {'uid': uid, 'peerUid': peerUid, 'firestoreTouched': False})

    # ---- 2. profile into D1 -------------------------------------------------------
    status, body = http('PUT', f'{WORKER}/v1/docs/users/{uid}',
                        {'displayName': name, 'email': email, 'photoUrl': '', 'isOnline': True,
                         'createdAt': int(time.time() * 1000)}, token)
    record('profile PUT /v1/docs/users', status == 200, {'status': status,
                                                        'storedName': body.get('document', {}).get('displayName')})
    status, body = http('PUT', f'{WORKER}/v1/docs/users/{peerUid}',
                        {'displayName': peerName, 'email': peerEmail, 'photoUrl': '',
                         'createdAt': int(time.time() * 1000)}, peerToken)

    # ---- 3. post ------------------------------------------------------------------
    postId = f'{TAG}post'
    now = int(time.time() * 1000)
    status, body = http('PUT', f'{WORKER}/v1/docs/posts/{postId}',
                        {'authorId': uid, 'authorName': name, 'authorPhoto': '',
                         'content': f'E2E post by a brand-new account ({STAMP})',
                         'privacy': 'Only me', 'likes': 0, 'comments': 0, 'shares': 0,
                         'createdAt': now}, token)
    record('post PUT /v1/docs/posts', status == 200,
           {'status': status, 'postId': postId, 'authorName': body.get('document', {}).get('authorName')})

    # ---- 4. like (peer likes it) --------------------------------------------------
    status, body = http('PATCH', f'{WORKER}/v1/docs/posts/{postId}',
                        {'ops': [{'field': 'likes', 'increment': 1},
                                 {'field': 'reactionsMap', 'value': {peerUid: 'like'}}]}, peerToken)
    record('like PATCH posts (increment)', status == 200,
           {'status': status, 'likes': (body.get('document') or {}).get('likes'),
            'likedBy': list(((body.get('document') or {}).get('reactionsMap') or {}).keys())[:1]})

    # ---- 5. comment ---------------------------------------------------------------
    commentId = f'{TAG}comment'
    status, body = http('PUT', f'{WORKER}/v1/docs/comments/{commentId}',
                        {'postId': postId, 'authorId': peerUid, 'authorName': peerName,
                         'text': 'Comment from the second test account', 'likes': 0,
                         'createdAt': int(time.time() * 1000)}, peerToken)
    status2, comments = http('GET', f'{WORKER}/v1/docs?collection=comments&limit=5'
                                    f'&where=postId:eq:{urllib.parse.quote(postId)}', None, token)
    found = [c for c in (comments.get('documents') or []) if c.get('postId') == postId]
    record('comment PUT /v1/docs/comments', status == 200 and bool(found),
           {'status': status, 'listStatus': status2, 'commentsOnPost': len(found),
            'commentAuthor': found[0].get('authorName') if found else None})

    # ---- 6. chat + message --------------------------------------------------------
    chatId = f'{TAG}chat'
    status, body = http('PUT', f'{WORKER}/v1/docs/chats/{chatId}',
                        {'participants': [uid, peerUid], 'isGroup': False,
                         'lastMessage': 'Hello from the new account', 'lastMessageAt': now,
                         'createdAt': now}, token)
    messageId = f'{TAG}message'
    status2, message = http('PUT', f'{WORKER}/v1/docs/messages/{messageId}',
                            {'chatId': chatId, 'senderId': uid, 'senderName': name,
                             'text': 'Hello from the new account',
                             'createdAt': int(time.time() * 1000)}, token)
    status3, fetched = http('GET', f'{WORKER}/v1/docs/messages/{messageId}', None, peerToken)
    record('chat + message PUT /v1/docs', status == 200 and status2 == 200 and status3 == 200,
           {'chatStatus': status, 'messageStatus': status2, 'readStatus': status3,
            'text': (fetched.get('document') or {}).get('text'),
            'senderName': (fetched.get('document') or {}).get('senderName')})

    # ---- 7. the new account can read the old feed ---------------------------------
    status, page = http('GET', f'{WORKER}/v1/docs?collection=posts&limit=6'
                               f'&orderBy=createdAt:desc', None, token)
    documents = page.get('documents') or []
    named = [d for d in documents if d.get('authorName') and d['authorName'] != 'User']
    record('new account reads the existing feed', status == 200 and bool(documents),
           {'status': status, 'documents': len(documents), 'withRealAuthorName': len(named),
            'sample': [{'authorName': d.get('authorName'), 'hasPhoto': bool(d.get('authorPhoto'))}
                       for d in documents[:4]]})

    ok = all(step['ok'] for step in steps)
    print(json.dumps({'mode': 'E2E', 'passed': ok, 'steps': steps}, sort_keys=True), flush=True)
    return 0 if ok else 1


if __name__ == '__main__':
    try:
        sys.exit(main())
    except E2EError as error:
        print(f'::error::{error}', file=sys.stderr)
        sys.exit(1)
