package com.friendbook.social.core;
import java.util.*;
/** Pure bounded presentation policy. Cursor fetching and persisted totals remain separate. */
public final class PageWindow {
    private PageWindow() {}
    public static <T> List<T> latest(Collection<T> records,Comparator<T> oldestFirst,int visible) {
        if(visible<0) throw new IllegalArgumentException("negative window");
        List<T> result=new ArrayList<>(records); result.sort(oldestFirst.reversed());
        if(result.size()>visible) result=new ArrayList<>(result.subList(0,visible));
        result.sort(oldestFirst); return result;
    }
}
