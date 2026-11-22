package com.friendbook.social.core;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.effect.FrameDropEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.*;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Encodes both files locally; the original URI is never uploaded by this path. */
final class PhoneVideoEncoder {
    interface Attempt { void run(String mime, boolean compatible) throws Exception; }

    static void encode(Context context, Uri source, File output, boolean preview) throws Exception {
        encodeWithFallback(output, (mime, compatible) -> export(context, source, output, preview, mime, compatible));
    }

    // Retry only completed export errors. A timeout/interruption can still have
    // a running codec; it must never race a second export to the same file.
    static void encodeWithFallback(File output, Attempt attempt) throws Exception {
        IOException first = null;
        String[] mimes = { MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H264 };
        for (int i = 0; i < mimes.length; i++) {
            try { attempt.run(mimes[i], i == 2); return; }
            catch (IOException failure) {
                if (first == null) first = failure; else failure.addSuppressed(first);
                first = failure;
                if (output.exists() && !output.delete()) throw failure;
                if (i == mimes.length - 1) throw failure;
            }
        }
    }

    private static void export(Context context, Uri source, File output, boolean preview, String mime, boolean compatible) throws Exception {
        Handler main = new Handler(Looper.getMainLooper());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<Transformer> running = new AtomicReference<>();
        main.post(() -> {
            try {
                ChannelMixingAudioProcessor mono = new ChannelMixingAudioProcessor();
                mono.putChannelMixingMatrix(ChannelMixingMatrix.create(1, 1));
                mono.putChannelMixingMatrix(ChannelMixingMatrix.create(2, 1));
                int fps = preview ? 24 : 30;
                EditedMediaItem item = new EditedMediaItem.Builder(MediaItem.fromUri(source))
                        .setEffects(new Effects(preview && !compatible ? Collections.singletonList(mono) : Collections.emptyList(),
                                compatible ? Collections.singletonList(Presentation.createForHeight(preview ? 360 : 720))
                                        : Arrays.asList(Presentation.createForHeight(preview ? 360 : 720),
                                                FrameDropEffect.createDefaultFrameDropEffect(fps)))).build();
                DefaultEncoderFactory.Builder factory = new DefaultEncoderFactory.Builder(context).setEnableFallback(true);
                if (!compatible) {
                    factory.setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate(preview ? 150_000 : 800_000).build())
                            .setRequestedAudioEncoderSettings(new AudioEncoderSettings.Builder().setBitrate(preview ? 32_000 : 64_000).build());
                }
                DefaultEncoderFactory encoder = factory.build();
                Transformer transformer = new Transformer.Builder(context).setEncoderFactory(encoder)
                        .setVideoMimeType(mime).setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(new Transformer.Listener() {
                            @Override public void onCompleted(Composition composition, ExportResult result) { done.countDown(); }
                            @Override public void onError(Composition composition, ExportResult result, ExportException failure) {
                                error.set(new IOException("Phone video encoding failed", failure)); done.countDown();
                            }
                        }).build();
                running.set(transformer);
                transformer.start(item, output.getAbsolutePath());
            } catch (Exception failure) { error.set(new IOException("Phone encoder unavailable", failure)); done.countDown(); }
        });
        try {
            if (!done.await(5, TimeUnit.MINUTES)) throw new java.util.concurrent.TimeoutException("Phone video encoding timed out");
        } finally {
            if (done.getCount() != 0) main.post(() -> { if (running.get() != null) running.get().cancel(); });
        }
        if (error.get() != null) throw error.get();
        if (output.length() == 0) throw new IOException("Phone encoder produced no video");
    }
}
