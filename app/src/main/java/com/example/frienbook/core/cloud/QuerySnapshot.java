package com.example.frienbook.core.cloud;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * One page of results, in the order the query asked for.
 *
 * It also carries the two things a paging or polling screen needs: where the next page starts, and
 * the change position this answer already includes - a watcher resumes from that position instead of
 * downloading the page again.
 */
public final class QuerySnapshot implements Iterable<QueryDocumentSnapshot> {

    private final List<QueryDocumentSnapshot> documents;
    private final String next;
    private final long since;
    private final int count;

    QuerySnapshot(List<QueryDocumentSnapshot> documents, String next, long since, int count) {
        this.documents = documents == null ? new ArrayList<QueryDocumentSnapshot>() : documents;
        this.next = next;
        this.since = since;
        this.count = count;
    }

    public List<QueryDocumentSnapshot> getDocuments() {
        return documents;
    }

    public int size() {
        return documents.size();
    }

    public boolean isEmpty() {
        return documents.isEmpty();
    }

    @Override
    public Iterator<QueryDocumentSnapshot> iterator() {
        return documents.iterator();
    }

    /** The cursor for the next page, or null when this page was the last one. */
    public String getNext() {
        return next;
    }

    /** The change position this answer includes; a listener polls from here. */
    public long getSince() {
        return since;
    }

    /** Only set when the query asked for a count instead of the rows. */
    public int getCount() {
        return count;
    }
}
