package com.friendbook.social.core;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * The query DSL must serialise to exactly what the worker parses. A mismatch here is silent:
 * the server would return a broader result set than the caller asked for.
 */
public class DocQueryTest {

    @Test public void serialisesTheFirestoreShapeTheWorkerParses() {
        String path = DocQuery.of("posts")
                .whereEqualTo("authorId", "u1")
                .whereLessThan("createdAt", 999)
                .orderByDescending("createdAt")
                .limit(25)
                .build()
                .toPath();
        assertEquals("/v1/docs?collection=posts"
                + "&where=authorId%3Aeq%3Au1"
                + "&where=createdAt%3Alt%3A999"
                + "&orderBy=createdAt%3Adesc"
                + "&limit=25", path);
    }

    @Test public void arrayContainsAnyJoinsWithCommas() {
        java.util.List<String> ids = java.util.Arrays.asList("u1", "u7");
        String path = DocQuery.of("chats")
                .whereArrayContainsAny("participants", ids)
                .build()
                .toPath();
        assertTrue(path.contains("where=participants%3Aarray_contains_any%3Au1%2Cu7"));
    }

    @Test public void limitIsClampedToTheServerCeiling() {
        assertEquals(DocQuery.MAX_LIMIT, DocQuery.of("posts").limit(5000).build().limit());
        assertEquals(1, DocQuery.of("posts").limit(0).build().limit());
    }

    @Test public void cursorRoundTripsIntoTheNextPage() {
        DocQuery first = DocQuery.of("posts").orderBy("createdAt", DocQuery.DESC).limit(20).build();
        DocQuery second = first.nextPage("eyJ2IjoxfQ");
        assertTrue(second.toPath().contains("after=eyJ2IjoxfQ"));
        // The cursor must not be lost or duplicated onto the first page.
        assertFalse(first.toPath().contains("after="));
    }

    @Test public void directionDefaultsToAscending() {
        assertTrue(DocQuery.of("posts").orderBy("createdAt", "sideways").build()
                .toPath().contains("orderBy=createdAt%3Aasc"));
        assertTrue(DocQuery.of("posts").orderBy("createdAt", DocQuery.DESC).build()
                .toPath().contains("orderBy=createdAt%3Adesc"));
    }

    @Test public void booleansAndNumbersSurviveSerialisation() {
        String path = DocQuery.of("users")
                .whereEqualTo("banned", false)
                .whereGreaterThanOrEqualTo("followers", 100)
                .build()
                .toPath();
        assertTrue(path.contains("where=banned%3Aeq%3Afalse"));
        assertTrue(path.contains("where=followers%3Agte%3A100"));
    }

    @Test public void valuesAreUrlEncodedSoSeparatorsCannotBreakTheQuery() {
        // A colon in the *value* is legal and must reach the server intact, not be dropped.
        String path = DocQuery.of("posts")
                .whereEqualTo("caption", "a:b")
                .build()
                .toPath();
        assertTrue(path.contains("%3Aa%3Ab"));
    }

    @Test public void fieldNamesContainingTheSeparatorAreRejected() {
        try {
            DocQuery.of("posts").whereEqualTo("bad:field", "x");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { /* correct */ }
        try {
            DocQuery.of("posts").orderBy("bad:field", DocQuery.ASC);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { /* correct */ }
    }

    @Test public void nullValuesAndEmptyCollectionsAreRejected() {
        try { DocQuery.of(""); fail("expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { /* correct */ }
        try { DocQuery.of("posts").whereEqualTo("authorId", null); fail("expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { /* correct */ }
        try { DocQuery.of("posts").whereArrayContainsAny("participants", java.util.Collections.emptyList()); fail("expected IllegalArgumentException"); }
        catch (IllegalArgumentException expected) { /* correct */ }
    }

    @Test public void existsAndNotExistsCarryNoValue() {
        String path = DocQuery.of("posts").whereExists("mediaUrl").build().toPath();
        assertTrue(path.contains("where=mediaUrl%3Aexists%3A1"));
    }
}
