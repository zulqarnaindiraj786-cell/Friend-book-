import importlib.util
import json
import sqlite3
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("legacy_comment_preflight", ROOT / "scripts/loadtest/legacy_comment_preflight.py")
preflight = importlib.util.module_from_spec(spec)
spec.loader.exec_module(preflight)


class Response:
    def __init__(self, payload): self.payload = payload
    def __enter__(self): return self
    def __exit__(self, *args): pass
    def read(self, *args): return json.dumps(self.payload).encode()


class LegacyCommentPreflightTests(unittest.TestCase):
    def test_capped_aggregate_uses_collection_seek_and_does_not_return_values(self):
        db = sqlite3.connect(":memory:")
        db.executescript((ROOT / "cloudflare/migrations/0011_additive_app_documents.sql").read_text())
        records = [("comments/a", "comments", "a", "x", '{"postId":"private","createdAt":100}', 1),
                   ("comments/b", "comments", "b", "x", '{"postId":"private","post_id":"private","createdAt":"old"}', 2),
                   ("posts/c", "posts", "c", "x", "{}", 3)]
        db.executemany("INSERT INTO friendbook_cf_documents VALUES (?,?,?,?,?,?)", records)
        self.assertEqual(db.execute(preflight.SQL).fetchone(), (2, 1, 2))
        plan = " ".join(str(row) for row in db.execute("EXPLAIN QUERY PLAN " + preflight.SQL))
        self.assertIn("SEARCH friendbook_cf_documents USING INDEX idx_friendbook_cf_documents_collection_updated (collection=?)", plan)

    def test_guard_rejects_truncation_exception_overflow_and_incomplete_result(self):
        def payload(row): return {"success": True, "result": [{"success": True, "results": [row], "meta": {}}]}
        def run(value):
            with patch.dict("os.environ", {"CLOUDFLARE_API_TOKEN": "synthetic"}), patch("sys.argv", ["preflight", "--require-index-build-safe"]), patch.object(preflight.urllib.request, "urlopen", return_value=Response(payload(value))):
                preflight.main()
        run({"sampled": 422, "numeric_safe": 268, "largest_exception_route_upper_bound": 12})
        for bad in ({"sampled": 5001, "numeric_safe": 0, "largest_exception_route_upper_bound": 1},
                    {"sampled": 422, "numeric_safe": 268, "largest_exception_route_upper_bound": 65},
                    {"sampled": 1, "numeric_safe": 4, "largest_exception_route_upper_bound": 0}):
            with self.assertRaises(SystemExit): run(bad)


if __name__ == "__main__": unittest.main()
