"""Find out what is actually refusing reads, and whether an export can run.

Two questions, both cheap, both unanswerable from the current logs:

1. Which quota is exceeded? A REST 429 carries a QuotaFailure body naming the metric and
   the limit. The gRPC path threw that away, so every run so far knew only "Quota exceeded."
   and could not tell a per-minute cap from a daily allowance from a project-level block.

2. Can the project export to Cloud Storage? A managed export does not consume document
   reads - it is a bulk operation, billed to Storage instead. If it works, the data can be
   taken out of Firestore today without touching the quota that is refusing us.
"""
import json
import os
import sys

import requests

PROJECT_ID = os.environ.get("FIREBASE_PROJECT_ID") or "friendbook-17551"
SERVICE_ACCOUNT = os.environ.get("FIREBASE_SERVICE_ACCOUNT", "")
SCOPES = ["https://www.googleapis.com/auth/datastore",
          "https://www.googleapis.com/auth/cloud-platform"]


def announce(line):
    """Print for the log and as an annotation; the job log blob has been unreadable."""
    print(line)
    print(f"::notice::{line[:900]}")


def credentials():
    from google.auth.transport.requests import Request
    from google.oauth2 import service_account
    if SERVICE_ACCOUNT.lstrip().startswith("{"):
        creds = service_account.Credentials.from_service_account_info(
            json.loads(SERVICE_ACCOUNT), scopes=SCOPES)
    else:
        import google.auth
        creds, _ = google.auth.default(scopes=SCOPES)
    creds.refresh(Request())
    return creds


def probe_rest_read(token):
    url = (f"https://firestore.googleapis.com/v1/projects/{PROJECT_ID}"
           f"/databases/(default)/documents/users?pageSize=1")
    response = requests.get(url, headers={"Authorization": f"Bearer {token}"}, timeout=30)
    announce(f"read: HTTP {response.status_code}")
    if response.status_code == 200:
        return True
    # The body is the whole point: it names the metric, the limit and the quota id.
    try:
        body = response.json()
    except Exception:
        announce(f"read body (not json): {response.text[:400]}")
        return False
    error = body.get("error", {})
    announce(f"read status: {error.get('status')} message: {error.get('message')}")
    for detail in error.get("details", []):
        if not isinstance(detail, dict):
            continue
        kind = str(detail.get("@type", "")).rsplit("/", 1)[-1]
        if kind == "QuotaFailure":
            for violation in detail.get("violations", []):
                announce(f"quota metric: {violation.get('quotaMetric')} "
                         f"id: {violation.get('quotaId')} "
                         f"dimensions: {violation.get('quotaDimensions')} "
                         f"limit: {violation.get('quotaValue')}")
        elif kind == "ErrorInfo":
            announce(f"error reason: {detail.get('reason')} domain: {detail.get('domain')} "
                     f"metadata: {detail.get('metadata')}")
        elif kind == "RetryInfo":
            announce(f"retry after: {detail.get('retryDelay')}")
        else:
            announce(f"detail {kind}: {json.dumps(detail)[:300]}")
    return False


def probe_export(token):
    """Can this project write an export to Cloud Storage? Costs no document reads."""
    listing = requests.get(
        "https://storage.googleapis.com/storage/v1/b",
        params={"project": PROJECT_ID, "maxResults": 5},
        headers={"Authorization": f"Bearer {token}"}, timeout=30)
    announce(f"buckets: HTTP {listing.status_code}")
    bucket = None
    if listing.status_code == 200:
        names = [b.get("name") for b in listing.json().get("items", [])]
        announce(f"buckets visible: {names[:5]}")
        # A Firebase project already has one; exporting into it needs no new bucket.
        for name in names:
            if name and (name.startswith(PROJECT_ID)):
                bucket = name
                break
    else:
        announce(f"buckets body: {listing.text[:300]}")

    if not bucket:
        announce("export: no usable bucket, so an export cannot be attempted")
        return False

    announce(f"export: attempting into gs://{bucket}/migration")
    started = requests.post(
        f"https://firestore.googleapis.com/v1/projects/{PROJECT_ID}"
        f"/databases/(default):exportDocuments",
        headers={"Authorization": f"Bearer {token}"},
        json={"outputUriPrefix": f"gs://{bucket}/migration"}, timeout=60)
    announce(f"export: HTTP {started.status_code}")
    if started.status_code != 200:
        announce(f"export body: {started.text[:400]}")
        return False
    operation = started.json().get("name", "")
    announce(f"export started: {operation}")
    return True


def main():
    try:
        creds = credentials()
    except Exception as exc:
        announce(f"credentials failed: {type(exc).__name__}: {str(exc)[:200]}")
        return 2
    readable = probe_rest_read(creds.token)
    if readable:
        announce("reads work over REST")
        return 0
    probe_export(creds.token)
    return 1


if __name__ == "__main__":
    sys.exit(main())
