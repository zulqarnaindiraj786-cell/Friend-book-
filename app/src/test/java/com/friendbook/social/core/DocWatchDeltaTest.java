package com.friendbook.social.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * The delta maths is what decides whether a feed re-renders on every poll. It is exercised
 * here directly, without Android or a network, because getting it wrong is a flickering list
 * at lakhon users rather than a visible crash.
 */
public class DocWatchDeltaTest {

    /** Mirrors DocWatch's comparison so the rules are pinned independently of the Handler. */
    private static final class Tracked {
        final Map<String, String> signatures = new LinkedHashMap<>();

        /** @return added / updated / removed, or null when nothing changed. */
        String[] apply(List<Map<String, Object>> documents) {
            Map<String, String> current = new LinkedHashMap<>();
            for (Map<String, Object> doc : documents) {
                Object id = doc.get("id");
                if (!(id instanceof String)) continue;
                current.put((String) id, CloudflareStore.toJson(doc).toString());
            }
            List<String> added = new ArrayList<>();
            List<String> updated = new ArrayList<>();
            for (Map.Entry<String, String> e : current.entrySet()) {
                if (!signatures.containsKey(e.getKey())) added.add(e.getKey());
                else if (!signatures.get(e.getKey()).equals(e.getValue())) updated.add(e.getKey());
            }
            List<String> removed = new ArrayList<>();
            for (String id : signatures.keySet()) if (!current.containsKey(id)) removed.add(id);
            signatures.clear();
            signatures.putAll(current);
            if (added.isEmpty() && updated.isEmpty() && removed.isEmpty()) return null;
            return new String[]{String.join(",", added), String.join(",", updated), String.join(",", removed)};
        }
    }

    private static Map<String, Object> doc(String id, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("content", content);
        return m;
    }

    @Test public void aPollIdenticalToThePreviousOneReportsNoChange() {
        Tracked t = new Tracked();
        List<Map<String, Object>> page = new ArrayList<>();
        page.add(doc("a", "hello"));
        page.add(doc("b", "world"));
        assertNotNull(t.apply(page));
        assertNull("an idle feed must not fire the listener", t.apply(page));
    }

    @Test public void reorderingAloneIsNotAChange() {
        Tracked t = new Tracked();
        t.apply(new ArrayList<>(List.of(doc("a", "x"), doc("b", "y"))));
        // Same documents, different order: nothing was added, updated or removed.
        assertNull(t.apply(new ArrayList<>(List.of(doc("b", "y"), doc("a", "x")))));
    }

    @Test public void newAndRemovedDocumentsAreReportedDeltaOnly() {
        Tracked t = new Tracked();
        t.apply(new ArrayList<>(List.of(doc("a", "x"), doc("b", "y"))));
        String[] delta = t.apply(new ArrayList<>(List.of(doc("b", "y"), doc("c", "z"))));
        assertNotNull(delta);
        assertEquals("c", delta[0], "added");
        assertEquals("", delta[1], "updated");
        assertEquals("a", delta[2], "removed");
    }

    @Test public void anEditedDocumentIsUpdatedNotReAdded() {
        Tracked t = new Tracked();
        t.apply(new ArrayList<>(List.of(doc("a", "before"))));
        String[] delta = t.apply(new ArrayList<>(List.of(doc("a", "after"))));
        assertNotNull(delta);
        assertEquals("", delta[0]);
        assertEquals("a", delta[1]);
        assertEquals("", delta[2]);
    }

    @Test public void anEmptyResponseClearsTheWindow() {
        Tracked t = new Tracked();
        t.apply(new ArrayList<>(List.of(doc("a", "x"))));
        String[] delta = t.apply(new ArrayList<>());
        assertNotNull(delta);
        assertEquals("a", delta[2], "everything is removed when the query returns nothing");
    }

    @Test public void documentsWithoutAnIdAreIgnoredRatherThanCrashing() {
        Tracked t = new Tracked();
        Map<String, Object> noId = new LinkedHashMap<>();
        noId.put("content", "anonymous");
        assertNull(t.apply(new ArrayList<>(List.of(noId))));
    }

    @Test public void theSignatureIsStableForTheSameDocument() {
        // If serialising the same document twice produced different text, every poll would
        // look like an update and the feed would re-render forever.
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", "d1");
        doc.put("likes", 3);
        doc.put("content", "hi");
        String first = CloudflareStore.toJson(doc).toString();
        String second = CloudflareStore.toJson(doc).toString();
        assertEquals(first, second);
        assertTrue(first.contains("\"likes\""));
    }

    @Test public void aValueChangingButKeysStayingIdenticalIsDetected() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("id", "d1");
        before.put("likes", 3);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("id", "d1");
        after.put("likes", 4);
        assertNotEquals(CloudflareStore.toJson(before).toString(), CloudflareStore.toJson(after).toString());
    }
}
