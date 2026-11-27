package com.friendbook.social.core;

import androidx.media3.common.MimeTypes;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;
import static org.junit.Assert.*;

public class PhoneVideoEncoderTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void fallsBackAfterCompletedEncoderFailuresWithoutReusingPartialFiles() throws Exception {
        File out = new File(temp.getRoot(), "video.mp4");
        List<String> attempts = new ArrayList<>();
        PhoneVideoEncoder.encodeWithFallback(out, (mime, compatible) -> {
            assertFalse("partial file must be removed before retry", out.exists());
            attempts.add(mime + ":" + compatible);
            Files.write(out.toPath(), new byte[]{1});
            if (!compatible) throw new IOException("synthetic codec failure");
        });
        assertEquals(Arrays.asList(MimeTypes.VIDEO_H265 + ":false", MimeTypes.VIDEO_H264 + ":false",
                MimeTypes.VIDEO_H264 + ":true"), attempts);
        assertEquals(1, out.length());
    }

    @Test public void stopsAfterSuccessAndDoesNotRetryTimeout() throws Exception {
        File out = new File(temp.getRoot(), "video.mp4");
        int[] calls = {0};
        PhoneVideoEncoder.encodeWithFallback(out, (mime, compatible) -> calls[0]++);
        assertEquals(1, calls[0]);
        calls[0] = 0;
        try {
            PhoneVideoEncoder.encodeWithFallback(out, (mime, compatible) -> { calls[0]++; throw new TimeoutException(); });
            fail("timeout must be propagated");
        } catch (TimeoutException expected) { assertEquals(1, calls[0]); }
    }

    @Test public void allFailuresPropagateAndRemovePartialFile() throws Exception {
        File out = new File(temp.getRoot(), "video.mp4");
        int[] calls = {0};
        try {
            PhoneVideoEncoder.encodeWithFallback(out, (mime, compatible) -> {
                calls[0]++; Files.write(out.toPath(), new byte[]{1}); throw new IOException("failure " + calls[0]);
            });
            fail("all failures must be reported");
        } catch (IOException expected) {
            assertEquals(3, calls[0]);
            assertEquals("failure 3", expected.getMessage());
            assertEquals(1, expected.getSuppressed().length);
            assertFalse(out.exists());
        }
    }
}
