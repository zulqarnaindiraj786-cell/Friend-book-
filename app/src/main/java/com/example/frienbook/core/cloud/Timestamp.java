package com.example.frienbook.core.cloud;

import java.util.Date;

/**
 * A moment in time, carried as milliseconds since the epoch.
 *
 * Documents in Cloudflare D1 store plain numbers, which is what the app's own helpers already read
 * (Format.ts() accepts a Long, a Double, a Date or a Timestamp). This class exists so the screens
 * that build one - a story's expiresAt, a boost's expiry, a live room's startedAt - keep saying what
 * they mean, and so a comparison in a query still sends millis rather than an object.
 */
public final class Timestamp implements Comparable<Timestamp> {

    private final long millis;

    public Timestamp(long millis) {
        this.millis = millis;
    }

    public Timestamp(Date date) {
        this.millis = date == null ? 0L : date.getTime();
    }

    public static Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }

    public static Timestamp fromDate(Date date) {
        return new Timestamp(date);
    }

    public Date toDate() {
        return new Date(millis);
    }

    public long getTime() {
        return millis;
    }

    public long getSeconds() {
        return millis / 1000L;
    }

    @Override
    public int compareTo(Timestamp other) {
        if (other == null) return 1;
        return Long.compare(millis, other.millis);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Timestamp && ((Timestamp) other).millis == millis;
    }

    @Override
    public int hashCode() {
        return (int) (millis ^ (millis >>> 32));
    }

    @Override
    public String toString() {
        return "Timestamp(" + millis + ")";
    }
}
