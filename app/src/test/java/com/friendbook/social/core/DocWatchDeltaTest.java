package com.friendbook.social.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * The delta maths is what decides whether a feed re-renders on every poll. It is exercised
 * against the pure {@link DocDelta} logic - no Android, no Looper, no network - because getting
 * it wrong is a flickering list at lakhon users rather than a visible crash.
 */
public class DocWatchDeltaTest {

    private static Map<String, Object> doc(String id, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("content", content);
        return m;
    }

    private static List<Map<String, Object>> page(Map<String, Object>... docs) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> d : docs) list.add(d);
        return list;
    }

    /** Applies one poll, returning the signatures state a real watch would carry forward. */
    private static final class Tracker {
        Map<String, String> signatures = new LinkedHashMap<>();

        DocDelta.Result poll(List<Map<String, Object>> documents) {
            DocDelta.Result r = DocDelta.compute(signatures, documents);
            Map<String, String> next = new LinkedHashMap<>();
            for (Map<String, Object> d : r.documents) next.put(DocDelta.idOf(d), DocDelta.signature(d));
            signatures = next;
            return r;
        }
    }

    @Test public void theFirstPollReportsEverythingAsAdded() {
        Tracker t = new Tracker();
        DocDelta.Result r = t.poll(page(doc("a", "x"), doc("b", "y")));
        assertTrue(r.changed());
        assertEquals(2, r.added.size());
        assertTrue(r.updated.isEmpty());
        assertTrue(r.removed.isEmpty());
    }

    @Test public void aPollIdenticalToThePreviousOneReportsNoChange() {
        Tracker t = new Tracker();
        t.poll(page(doc("a", "hello"), doc("b", "world")));
        DocDelta.Result r = t.poll(page(doc("a", "hello"), doc("b", "world")));
        assertFalse("an idle feed must not fire the listener", r.changed());
        assertEquals(2, r.documents.size(), "the window is still available for rendering");
    }

    @Test public void reorderingAloneIsNotAChange() {
        Tracker t = new Tracker();
        t.poll(page(doc("a", "x"), doc("b", "y")));
        assertFalse(t.poll(page(doc("b", "y"), doc("a", "x"))).changed());
    }

    @Test public void newAndRemovedDocumentsAreReportedDeltaOnly() {
        Tracker t = new Tracker();
        t.poll(page(doc("a", "x"), doc("b", "y")));
        DocDelta.Result r = t.poll(page(doc("b", "y"), doc("c", "z")));
        assertTrue(r.changed());
        assertEquals(1, r.added.size());
        assertEquals("c", DocDelta.idOf(r.added.get(0)));
        assertTrue(r.updated.isEmpty());
        assertEquals(1, r.removed.size());
        assertEquals("a", r.removed.get(0));
    }

    @Test public void anEditedDocumentIsUpdatedNotReAdded() {
        Tracker t = new Tracker();
        t.poll(page(doc("a", "before")));
        DocDelta.Result r = t.poll(page(doc("a", "after")));
        assertTrue(r.added.isEmpty());
        assertEquals(1, r.updated.size());
        assertEquals("a", DocDelta.idOf(r.updated.get(0)));
        assertTrue(r.removed.isEmpty());
    }

    @Test public void anEmptyResponseClearsTheWindow() {
        Tracker t = new Tracker();
        t.poll(page(doc("a", "x")));
        DocDelta.Result r = t.poll(new ArrayList<>());
        assertEquals(1, r.removed.size());
        assertEquals("a", r.removed.get(0));
    }

    @Test public void documentsWithoutAnIdAreIgnoredRatherThanCrashed() {
        Tracker t = new Tracker();
        Map<String, Object> noId = new LinkedHashMap<>();
        noId.put("content", "anonymous");
        assertFalse(t.poll(page(noId)).changed());
    }

    @Test public void aPathOnlyDocumentStillGetsAnIdentity() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", "posts/abc123");
        assertEquals("abc123", DocDelta.idOf(m));
    }

    @Test public void signatureIsStableAndIgnoresKeyOrder() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", "d1"); a.put("likes", 3); a.put("content", "hi");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("content", "hi"); b.put("likes", 3); b.put("id", "d1");
        assertEquals(DocDelta.signature(a), DocDelta.signature(b));
    }

    @Test public void changingOnlyAValueChangesTheSignature() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("id", "d1"); before.put("likes", 3);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("id", "d1"); after.put("likes", 4);
        assertNotEquals(DocDelta.signature(before), DocDelta.signature(after));
    }

    @Test public void listOrderIsSignificantButMapOrderIsNot() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("participants", new ArrayList<>(List.of("u1", "u2")));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("participants", new ArrayList<>(List.of("u2", "u1")));
        // A participant list reordering is a different value, unlike a map's key order.
        assertNotEquals(DocDelta.signature(first), DocDelta.signature(second));
    }

    @Test public void nestingAndTypesSurviveSerialisation() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("a", 1);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", "d1");
        doc.put("nested", inner);
        doc.put("flag", true);
        doc.put("score", 1.5);
        String sig = DocDelta.signature(doc);
        // True and 1 must not collide; a naive toString would blur some of these.
        assertNotEquals(sig, DocDelta.signature(new LinkedHashMap<String, Object>() {{ put("id", "d1"); put("nested", inner); put("flag", 1); put("score", 1.5); }}));
        assertTrue(sig.contains("true"));
    }
}
