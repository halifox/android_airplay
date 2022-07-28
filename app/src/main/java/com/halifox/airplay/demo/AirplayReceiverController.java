package com.halifox.airplay.demo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.halifox.airplay.AirplayJni;
import com.halifox.airplay.MdnsServices;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the lifecycle of the AirPlay receiver and the Java-side PCM player.
 *
 * <p>AirplayJni delivers callbacks from native worker threads. This class
 * keeps the native lifecycle on one control thread, protects AudioTrack, and
 * forwards UI events to the main thread.</p>
 */
public final class AirplayReceiverController {
    private static final String TAG = "AirplayReceiver";
    private static final int AIRPLAY_PORT = 7000;
    private static final String SERVICE_NAME = "Airplay Demo";
    private static final long UNKNOWN_DURATION = -1L;

    public interface Listener {
        void onStatusChanged(String message, boolean active, boolean error);

        void onAudioFormatChanged(int bits, int channels, int sampleRate);

        void onMetadataChanged(String title, String artist, String album);

        void onCoverArtChanged(Bitmap coverArt);

        void onProgressChanged(long positionMs, long durationMs);
    }

    private enum State {
        STOPPED,
        STARTING,
        LISTENING,
        STOPPING,
        ERROR
    }

    private interface ListenerAction {
        void run(Listener listener);
    }

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor();
    private final Object audioLock = new Object();
    private final AirplayJni airplay = AirplayJni.getInstance();
    private final AirplayJni.CallBacks callbacks = new AirplayJni.CallBacks() {
        @Override
        public void audio_init(int bits, int channels, int sampleRate) {
            try {
                initializeAudioTrack(bits, channels, sampleRate);
            } catch (Throwable error) {
                Log.e(TAG, "Unable to initialize AudioTrack", error);
                postStatus(
                        appContext.getString(R.string.airplay_status_error)
                                + ": " + safeMessage(error),
                        false,
                        true
                );
            }
        }

        @Override
        public void audio_destroy() {
            releaseAudioTrack();
            resetTrackState();
            notifyMetadata();
            notifyCoverArt(null);
            notifyProgressSnapshot();
            if (state == State.LISTENING || state == State.STARTING) {
                postStatus(appContext.getString(R.string.airplay_status_waiting), true, false);
            }
        }

        @Override
        public void audio_flush() {
            synchronized (audioLock) {
                if (audioTrack != null) {
                    try {
                        audioTrack.pause();
                        audioTrack.flush();
                        audioTrack.play();
                    } catch (IllegalStateException error) {
                        Log.w(TAG, "Unable to flush AudioTrack", error);
                    }
                }
                positionMs = 0L;
                audioStarted = false;
            }
            notifyProgressSnapshot();
        }

        @Override
        public void audio_data(byte[] buffer, int buflen) {
            if (buffer == null || buflen <= 0 || destroyed) {
                return;
            }

            int safeLength = Math.min(buflen, buffer.length);
            if (safeLength <= 0) {
                return;
            }

            int written;
            synchronized (audioLock) {
                if (audioTrack == null) {
                    return;
                }
                try {
                    written = audioTrack.write(
                            buffer,
                            0,
                            safeLength,
                            AudioTrack.WRITE_BLOCKING
                    );
                } catch (IllegalStateException error) {
                    Log.w(TAG, "Unable to write PCM data", error);
                    return;
                }

                if (written > 0 && !audioStarted) {
                    audioStarted = true;
                    postStatus(appContext.getString(R.string.airplay_status_playing), true, false);
                }
            }

            if (written < 0) {
                Log.w(TAG, "AudioTrack.write returned " + written);
            }
        }

        @Override
        public void audio_set_volume(float volume) {
            float linearVolume;
            if (Float.isNaN(volume) || volume <= -90.0f) {
                linearVolume = 0.0f;
            } else {
                linearVolume = (float) Math.pow(10.0, volume / 20.0);
                linearVolume = Math.max(0.0f, Math.min(1.0f, linearVolume));
            }

            synchronized (audioLock) {
                if (audioTrack != null) {
                    try {
                        audioTrack.setVolume(linearVolume);
                    } catch (IllegalStateException error) {
                        Log.w(TAG, "Unable to apply AirPlay volume", error);
                    }
                }
            }
        }

        @Override
        public void audio_set_metadata(byte[] metadata, int buflen) {
            Map<String, String> values = DmapMetadataParser.parse(metadata, buflen);
            if (values.isEmpty()) {
                return;
            }

            String value = values.get("minm");
            if (value != null) {
                title = value;
            }
            value = values.get("asar");
            if (value != null) {
                artist = value;
            }
            value = values.get("asal");
            if (value != null) {
                album = value;
            }
            notifyMetadata();
        }

        @Override
        public void audio_set_coverart(byte[] metadata, int buflen) {
            if (metadata == null || buflen <= 0 || destroyed) {
                return;
            }
            int safeLength = Math.min(buflen, metadata.length);
            Bitmap bitmap = BitmapFactory.decodeByteArray(metadata, 0, safeLength);
            if (bitmap != null) {
                coverArt = bitmap;
                notifyCoverArt(bitmap);
            }
        }

        @Override
        public void audio_set_progress(byte[] progress, int buflen) {
            long timestamp = readUnsignedInt(progress, buflen);
            if (timestamp < 0L) {
                return;
            }

            long relativeTimestamp;
            synchronized (audioLock) {
                relativeTimestamp = hasProgressStart
                        ? timestamp - progressStartUnits
                        : timestamp;
                if (relativeTimestamp < 0L) {
                    relativeTimestamp = timestamp;
                }
                positionMs = audioUnitsToMillis(relativeTimestamp);
                if (durationMs >= 0L) {
                    positionMs = Math.min(positionMs, durationMs);
                }
            }
            notifyProgressSnapshot();
        }

        @Override
        public void audio_set_total_len(byte[] totalLen, int buflen) {
            if (totalLen == null || buflen <= 0) {
                return;
            }
            int safeLength = Math.min(buflen, totalLen.length);
            String value = new String(totalLen, 0, safeLength, StandardCharsets.US_ASCII)
                    .replace("\u0000", "")
                    .trim();
            String[] parts = value.split("/", -1);
            if (parts.length < 3) {
                return;
            }

            long start = parseLong(parts[0]);
            long current = parseLong(parts[1]);
            long end = parseLong(parts[2]);
            if (start < 0L || current < 0L || end < 0L) {
                return;
            }

            long durationUnits = end - start;
            if (durationUnits <= 0L) {
                durationUnits = end;
            }
            long positionUnits = current - start;
            if (positionUnits < 0L) {
                positionUnits = current;
            }

            synchronized (audioLock) {
                progressStartUnits = start;
                hasProgressStart = true;
                durationMs = audioUnitsToMillis(durationUnits);
                positionMs = Math.min(audioUnitsToMillis(positionUnits), durationMs);
            }
            notifyProgressSnapshot();
        }
    };

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            if (state == State.STARTING || state == State.LISTENING) {
                notifyProgressSnapshot();
                mainHandler.postDelayed(this, 500L);
            }
        }
    };

    private volatile Listener listener;
    private volatile State state = State.STOPPED;
    private volatile boolean destroyed;

    private boolean nativeInitialized;
    private WifiManager.MulticastLock multicastLock;

    private AudioTrack audioTrack;
    private int bits;
    private int channels;
    private int sampleRate;
    private boolean audioStarted;

    private long positionMs;
    private long durationMs = UNKNOWN_DURATION;
    private long progressStartUnits;
    private boolean hasProgressStart;

    private volatile String title = "";
    private volatile String artist = "";
    private volatile String album = "";
    private volatile Bitmap coverArt;

    public AirplayReceiverController(Context context, Listener listener) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (!destroyed) {
            controlExecutor.execute(this::startInternal);
        }
    }

    public void stop() {
        if (!destroyed) {
            controlExecutor.execute(this::stopInternal);
        }
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        listener = null;
        mainHandler.removeCallbacks(progressTicker);
        controlExecutor.execute(this::stopInternal);
        controlExecutor.shutdown();
    }

    private void startInternal() {
        if (destroyed || state == State.STARTING || state == State.LISTENING) {
            return;
        }

        state = State.STARTING;
        postStatus(appContext.getString(R.string.airplay_status_starting), true, false);

        try {
            acquireMulticastLock();

            String hardwareAddress = MdnsServices.getHardwareAddressString();
            if (hardwareAddress == null || hardwareAddress.length() != 12) {
                throw new IllegalStateException("没有找到可用的 Wi-Fi MAC 地址");
            }

            airplay.registerCallBacks(callbacks);
            int port = airplay.airplayInit(AIRPLAY_PORT, hardwareAddress);
            if (port <= 0) {
                throw new IllegalStateException("AirPlay native 服务启动失败");
            }
            nativeInitialized = true;

            if (!MdnsServices.registerAirplayService(port, SERVICE_NAME)) {
                throw new IllegalStateException("无法注册 AirPlay mDNS 服务");
            }

            state = State.LISTENING;
            postStatus(appContext.getString(R.string.airplay_status_waiting), true, false);
            mainHandler.removeCallbacks(progressTicker);
            mainHandler.post(progressTicker);
        } catch (Throwable error) {
            Log.e(TAG, "Unable to start AirPlay receiver", error);
            cleanupResources();
            resetTrackState();
            state = State.ERROR;
            postStatus(
                    appContext.getString(R.string.airplay_status_error)
                            + ": " + safeMessage(error),
                    false,
                    true
            );
        }
    }

    private void stopInternal() {
        boolean hasResources = nativeInitialized
                || multicastLock != null
                || audioTrack != null
                || state != State.STOPPED;
        if (!hasResources) {
            resetTrackState();
            return;
        }

        state = State.STOPPING;
        postStatus(appContext.getString(R.string.airplay_status_stopping), true, false);
        mainHandler.removeCallbacks(progressTicker);
        cleanupResources();
        resetTrackState();
        state = State.STOPPED;
        notifyMetadata();
        notifyCoverArt(null);
        notifyProgressSnapshot();
        postStatus(appContext.getString(R.string.airplay_status_stopped), false, false);
    }

    private void cleanupResources() {
        try {
            MdnsServices.unRegisterAirplayService();
        } catch (Throwable error) {
            Log.w(TAG, "Unable to unregister mDNS service", error);
        }

        if (nativeInitialized) {
            try {
                airplay.airplayUninit();
            } catch (Throwable error) {
                Log.w(TAG, "Unable to stop native AirPlay service", error);
            } finally {
                nativeInitialized = false;
            }
        }

        try {
            airplay.unRegisgterCallBacks();
        } catch (Throwable error) {
            Log.w(TAG, "Unable to unregister JNI callbacks", error);
        }

        releaseAudioTrack();
        releaseMulticastLock();
    }

    private void acquireMulticastLock() {
        Context applicationContext = appContext.getApplicationContext();
        WifiManager wifiManager =
                (WifiManager) applicationContext.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) {
            return;
        }

        WifiManager.MulticastLock lock = wifiManager.createMulticastLock(TAG);
        lock.setReferenceCounted(false);
        lock.acquire();
        multicastLock = lock;
    }

    private void releaseMulticastLock() {
        if (multicastLock == null) {
            return;
        }
        try {
            multicastLock.release();
        } catch (Throwable error) {
            Log.w(TAG, "Unable to release multicast lock", error);
        } finally {
            multicastLock = null;
        }
    }

    private void initializeAudioTrack(int newBits, int newChannels, int newSampleRate) {
        if (destroyed || newChannels <= 0 || newSampleRate <= 0) {
            return;
        }

        int channelMask = channelMaskFor(newChannels);
        int encoding = encodingFor(newBits);
        int minBufferSize = AudioTrack.getMinBufferSize(
                newSampleRate,
                channelMask,
                encoding
        );
        if (minBufferSize <= 0) {
            throw new IllegalStateException("AudioTrack 不支持当前音频格式");
        }

        AudioTrack newTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(newSampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(encoding)
                        .build())
                .setBufferSizeInBytes(Math.max(minBufferSize, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        try {
            newTrack.play();
        } catch (RuntimeException | Error error) {
            newTrack.release();
            throw error;
        }

        synchronized (audioLock) {
            releaseAudioTrackLocked();
            audioTrack = newTrack;
            bits = newBits;
            channels = newChannels;
            sampleRate = newSampleRate;
            positionMs = 0L;
            durationMs = UNKNOWN_DURATION;
            progressStartUnits = 0L;
            hasProgressStart = false;
            audioStarted = false;
        }

        notifyFormat(newBits, newChannels, newSampleRate);
        notifyMetadata();
        notifyCoverArt(null);
        notifyProgressSnapshot();
    }

    private int channelMaskFor(int channelCount) {
        switch (channelCount) {
            case 1:
                return AudioFormat.CHANNEL_OUT_MONO;
            case 2:
                return AudioFormat.CHANNEL_OUT_STEREO;
            case 4:
                return AudioFormat.CHANNEL_OUT_QUAD;
            case 6:
                return AudioFormat.CHANNEL_OUT_5POINT1;
            default:
                throw new IllegalArgumentException("不支持的声道数: " + channelCount);
        }
    }

    private int encodingFor(int bitDepth) {
        switch (bitDepth) {
            case 8:
                return AudioFormat.ENCODING_PCM_8BIT;
            case 16:
                return AudioFormat.ENCODING_PCM_16BIT;
            case 24:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    return AudioFormat.ENCODING_PCM_24BIT_PACKED;
                }
                throw new IllegalArgumentException("Android 12 以下不支持 24-bit PCM");
            default:
                throw new IllegalArgumentException("不支持的 PCM 位深: " + bitDepth);
        }
    }

    private void releaseAudioTrack() {
        synchronized (audioLock) {
            releaseAudioTrackLocked();
        }
    }

    private void releaseAudioTrackLocked() {
        if (audioTrack == null) {
            return;
        }
        try {
            if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                audioTrack.stop();
            }
        } catch (IllegalStateException error) {
            Log.w(TAG, "Unable to stop AudioTrack", error);
        } finally {
            audioTrack.release();
            audioTrack = null;
            audioStarted = false;
        }
    }

    private void resetTrackState() {
        synchronized (audioLock) {
            bits = 0;
            channels = 0;
            sampleRate = 0;
            positionMs = 0L;
            durationMs = UNKNOWN_DURATION;
            progressStartUnits = 0L;
            hasProgressStart = false;
            audioStarted = false;
        }
        title = "";
        artist = "";
        album = "";
        coverArt = null;
    }

    private long audioUnitsToMillis(long units) {
        if (units <= 0L) {
            return 0L;
        }
        int rate = sampleRate;
        if (rate <= 0) {
            return units;
        }
        if (units > Long.MAX_VALUE / 1000L) {
            return Long.MAX_VALUE;
        }
        return (units * 1000L) / rate;
    }

    private void notifyFormat(final int formatBits, final int formatChannels, final int formatRate) {
        notifyListener(listener -> listener.onAudioFormatChanged(
                formatBits,
                formatChannels,
                formatRate
        ));
    }

    private void notifyMetadata() {
        String displayTitle = title.isEmpty()
                ? appContext.getString(R.string.airplay_unknown_title)
                : title;
        String displayArtist = artist.isEmpty()
                ? appContext.getString(R.string.airplay_unknown_artist)
                : artist;
        String displayAlbum = album.isEmpty()
                ? appContext.getString(R.string.airplay_unknown_album)
                : album;
        notifyListener(listener -> listener.onMetadataChanged(
                displayTitle,
                displayArtist,
                displayAlbum
        ));
    }

    private void notifyCoverArt(final Bitmap bitmap) {
        notifyListener(listener -> listener.onCoverArtChanged(bitmap));
    }

    private void notifyProgressSnapshot() {
        final long position;
        final long duration;
        synchronized (audioLock) {
            position = positionMs;
            duration = durationMs;
        }
        notifyListener(listener -> listener.onProgressChanged(position, duration));
    }

    private void postStatus(
            final String message,
            final boolean active,
            final boolean error
    ) {
        notifyListener(listener -> listener.onStatusChanged(message, active, error));
    }

    private void notifyListener(ListenerAction action) {
        if (destroyed || listener == null) {
            return;
        }
        mainHandler.post(() -> {
            Listener target = listener;
            if (destroyed || target == null) {
                return;
            }
            try {
                action.run(target);
            } catch (Throwable error) {
                Log.w(TAG, "Listener callback failed", error);
            }
        });
    }

    private static long readUnsignedInt(byte[] data, int length) {
        if (data == null || length < 4 || data.length < 4) {
            return -1L;
        }
        return ((long) (data[0] & 0xff) << 24)
                | ((long) (data[1] & 0xff) << 16)
                | ((long) (data[2] & 0xff) << 8)
                | (long) (data[3] & 0xff);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException error) {
            return -1L;
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}
