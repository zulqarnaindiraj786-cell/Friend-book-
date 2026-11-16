"""Guards for the "Firebase off, D1 on" switch.

These pin the four things that make the switch real rather than a flag: the
Firestore network is actually disabled, the feed does not fall back to Firestore,
writes are mirrored into D1, and the profile is read from D1. Each of these was
missing at some point during the cutover, and each failure mode looked identical
from the outside - a blank screen.
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/com/friendbook/social"


class FirestoreNetworkIsOffTest(unittest.TestCase):
    def setUp(self):
        self.fb = (JAVA / "core/Fb.java").read_text()

    def test_the_network_is_disabled_when_the_data_flag_is_on(self):
        self.assertIn("disableFirestoreNetwork()", self.fb)
        self.assertIn("CLOUDFLARE_DATA_ENABLED", self.fb)
        self.assertIn("FirebaseFirestore.getInstance().disableNetwork()", self.fb)

    def test_it_is_latched_so_it_runs_once(self):
        self.assertIn("firestoreNetworkOff", self.fb)

    def test_auth_is_not_touched(self):
        # Auth, FCM and Crashlytics are separate products with their own transports. If this
        # ever starts disabling them, sign-in and push die with the data plane.
        start = self.fb.index("private static void disableFirestoreNetwork()")
        body = self.fb[start:start + 1200]
        for forbidden in ["signOut", "clearAll", "deleteApp", "terminate"]:
            self.assertNotIn(forbidden, body, f"the network switch must not {forbidden}")


class NoFirestoreReadFallbackTest(unittest.TestCase):
    def setUp(self):
        self.home = (JAVA / "main/HomeFragment.java").read_text()

    def test_the_feed_has_no_firestore_query_left(self):
        start = self.home.index("private void startPostsQuery(boolean ordered)")
        end = self.home.index("private void consumePostsFresh")
        body = self.home[start:end]
        for token in ["Fb.db()", "Source.CACHE", "first.get()", "addSnapshotListener"]:
            self.assertNotIn(token, body, f"the feed still reaches Firestore via {token}")

    def test_the_feed_reads_d1_for_page_one_and_page_two(self):
        self.assertIn('DocQuery.of("posts")', self.home)
        self.assertEqual(2, self.home.count("CloudflareStore.query(feedQuery"))

    def test_an_empty_result_finishes_instead_of_spinning(self):
        start = self.home.index("private void startPostsQuery(boolean ordered)")
        end = self.home.index("private void consumePostsFresh")
        body = self.home[start:end]
        self.assertIn("loadingPosts = false", body)
        self.assertIn("loadedPosts = true", body)


class ProfileComesFromD1Test(unittest.TestCase):
    def setUp(self):
        self.fb = (JAVA / "core/Fb.java").read_text()
        self.store = (JAVA / "core/CloudflareStore.java").read_text()
        self.models = (JAVA / "models/Models.java").read_text()

    def test_the_profile_is_hydrated_from_d1(self):
        self.assertIn("hydrateMeFromD1", self.fb)
        self.assertIn('CloudflareStore.get("users", uid', self.fb)

    def test_the_store_can_read_one_document(self):
        # Without this every caller paged a whole collection to get a single document.
        self.assertIn("public static void get(String collection, String docId", self.store)

    def test_one_field_mapping_is_shared_by_both_entry_points(self):
        # Two mappings drift, and a drifted one is a profile that loses a field only when it
        # is read from D1 - which is the path that now matters.
        self.assertIn("private static void applyFields(User u, Map<String, Object> m)", self.models)
        self.assertIn("applyFields(u,", self.models)
        self.assertEqual(1, self.models.count("private static void applyFields(User u"))


class WritesAreMirroredTest(unittest.TestCase):
    def setUp(self):
        self.router = (JAVA / "core/DataRouter.java").read_text()

    def test_the_router_exposes_mirror_entry_points(self):
        for name in ["mirrorPut", "mirrorDelete"]:
            self.assertIn(name, self.router)

    def test_the_router_reports_both_put_and_remove_outcomes(self):
        store = (JAVA / "core/CloudflareStore.java").read_text()
        self.assertIn("Runnable onFailure", store)

    def test_mirrors_exist_in_the_screens_that_write(self):
        hits = 0
        for path in JAVA.rglob("*.java"):
            hits += path.read_text().count("DataRouter.mirror")
        # The cutover mirrors ~90 write sites. A regression that deletes them would show up
        # here long before anyone noticed a lost reaction.
        self.assertGreater(hits, 60, f"only {hits} mirrored write sites")

    def test_a_mirror_is_never_placed_inside_a_batch_call(self):
        # batch.set(Fb.db()...) takes the reference as an argument; inserting a mirror there
        # produced code that did not compile and, worse, would have mirrored the wrong doc.
        for path in JAVA.rglob("*.java"):
            lines = path.read_text().split("\n")
            for i, line in enumerate(lines):
                if "DataRouter.mirror" not in line:
                    continue
                if i + 1 < len(lines):
                    self.assertNotRegex(
                        lines[i + 1].strip(),
                        r"^batch\.(set|update)\(Fb\.db",
                        f"{path.name}:{i+1} mirrors a batch argument")


if __name__ == "__main__":
    unittest.main()
