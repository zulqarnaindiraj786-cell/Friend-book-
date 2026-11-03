package com.friendbook.social.core;
import java.util.*;
import java.util.stream.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class PageWindowTest {
    @Test public void latestFifteenAndOlderWindowDoNotMutateRecords() {
        List<Integer> stored=IntStream.range(0,100).boxed().collect(Collectors.toList());
        assertEquals(IntStream.range(85,100).boxed().collect(Collectors.toList()),PageWindow.latest(stored,Comparator.naturalOrder(),15));
        assertEquals(30,PageWindow.latest(stored,Comparator.naturalOrder(),30).size());
        assertEquals(100,stored.size());assertEquals(Integer.valueOf(0),stored.get(0));
    }
    @Test public void fiveThousandParallelModelSessionsKeepExactWindow() {
        // CPU/memory policy exercise only: no Firebase, sockets, video or production-capacity proof.
        List<Integer> records=IntStream.range(0,200).boxed().collect(Collectors.toList());
        IntStream.range(0,5000).parallel().forEach(session -> {
            List<Integer> first=PageWindow.latest(records,Comparator.naturalOrder(),15);
            assertEquals(15,first.size());assertEquals(Integer.valueOf(185),first.get(0));
            for(int batch:new int[]{10,15,20,30,50}) {
                List<Integer> page=PageWindow.latest(records,Comparator.naturalOrder(),batch);
                assertEquals(batch,page.size());assertEquals(Integer.valueOf(200-batch),page.get(0));
            }
        });
        System.out.println("MODEL_ONLY: 5000 parallel PageWindow sessions passed; NOT a backend load certification");
    }
}
