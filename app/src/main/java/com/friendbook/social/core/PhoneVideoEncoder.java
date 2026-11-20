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
    static void encode(Context context, Uri source, File output, boolean preview) throws Exception {
        try { export(context, source, output, preview, MimeTypes.VIDEO_H265); }
        catch (IOException unsupported) {
            if (output.exists() && !output.delete()) throw unsupported;
            export(context, source, output, preview, MimeTypes.VIDEO_H264);
        }
    }

    private static void export(Context context, Uri source, File output, boolean preview, String mime) throws Exception {
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
                        .setEffects(new Effects(preview ? Collections.singletonList(mono) : Collections.emptyList(),
                                Arrays.asList(Presentation.createForHeight(preview ? 360 : 720),
                                        FrameDropEffect.createDefaultFrameDropEffect(fps)))).build();
                DefaultEncoderFactory encoder = new DefaultEncoderFactory.Builder(context)
                        .setEnableFallback(false)
                        .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate(preview ? 150_000 : 800_000).build())
                        .setRequestedAudioEncoderSettings(new AudioEncoderSettings.Builder().setBitrate(preview ? 32_000 : 64_000).build()).build();
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
