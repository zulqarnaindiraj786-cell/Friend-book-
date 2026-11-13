"""Read-only Firebase access check; never logs credentials or user records."""
import json
import os
import sys

PROJECT = 'friendbook-17551'


def main():
    raw = os.environ.get('FIREBASE_ADMIN_SERVICE_ACCOUNT', '')
    if not raw:
        print('::error::FIREBASE_ADMIN_SERVICE_ACCOUNT secret is missing or unavailable.')
        return 1
    try:
        info = json.loads(raw)
        if not isinstance(info, dict) or info.get('type') != 'service_account':
            print('::error::Firebase secret must contain the full Admin SDK service-account JSON, not Android google-services.json.')
            return 1
        if info.get('project_id') != PROJECT:
            print('::error::Firebase service-account project does not match friendbook-17551.')
            return 1
        from google.oauth2 import service_account
        from google.auth.transport.requests import AuthorizedSession
        credentials = service_account.Credentials.from_service_account_info(
            info, scopes=['https://www.googleapis.com/auth/datastore'])
        with AuthorizedSession(credentials) as session:
            for collection, descendants in [('posts', False), ('reels', False),
                                             ('comments', True), ('reactions', True)]:
                response = session.post(
                    f'https://firestore.googleapis.com/v1/projects/{PROJECT}/databases/(default)/documents:runAggregationQuery',
                    json={'structuredAggregationQuery': {
                        'structuredQuery': {'from': [{'collectionId': collection,
                                                     'allDescendants': descendants}]},
                        'aggregations': [{'alias': 'total', 'count': {}}],
                    }}, timeout=60)
                if response.status_code != 200:
                    print(f'::error::Read-only Firestore count failed (HTTP {response.status_code}); no data was changed.')
                    return 1
                values = [row['result']['aggregateFields']['total']['integerValue']
                          for row in response.json() if row.get('result')]
                if len(values) != 1 or int(values[0]) < 0:
                    print('::error::Unexpected aggregate response; check stopped without writes.')
                    return 1
                print(f'Read-only count access verified: {collection} ({"collection group" if descendants else "root"}).')
        print('Firebase read-only access PASS. This is NOT a backup, migration or cutover verification.')
        return 0
    except Exception as error:
        # Do not echo credential parsing/auth response details or private records.
        print(f'::error::Firebase access check failed ({type(error).__name__}); credential details suppressed.')
        return 1


if __name__ == '__main__':
    sys.exit(main())
