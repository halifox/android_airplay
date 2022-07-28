package com.halifox.airplay;

import java.util.logging.Logger;

/**
 * Created by audioson on 16-7-28.
 */
public class AirplayJni {
    private static final Logger LOG = Logger.getLogger(AirplayJni.class.getName());

    static {
        try {
            System.loadLibrary("airplay_v2");
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private static AirplayJni singleInstance = null;

    private AirplayJni() {

    }

    static public AirplayJni getInstance() {
        if (null == singleInstance) {
            synchronized (AirplayJni.class) {
                if (null == singleInstance) {
                    singleInstance = new AirplayJni();
                }
            }
        }
        return singleInstance;
    }

    public interface CallBacks {
        void audio_init(int bits, int channels, int sampleRate);

        void audio_destroy();

        void audio_flush();

        void audio_data(byte[] buffer, int buflen);

        void audio_set_volume(float volume);

        void audio_set_metadata(byte[] metadata, int buflen);

        void audio_set_coverart(byte[] metadata, int buflen);

        void audio_set_progress(byte[] progress, int buflen);

        void audio_set_total_len(byte[] totalLen, int buflen);
    }

    private CallBacks callbacks = null;

    public synchronized void registerCallBacks(CallBacks callBacks) {
        if (null == callBacks)
            return;
        callbacks = callBacks;
    }

    private final int retryTimes = 10;
    private final int retryInterval = 8;

    public synchronized int airplayInit(int iShairPort, String hardwareAddr) {
        boolean ret;
        int port = 0;
        int i;

        for (i = 0; i < retryTimes; i++) {
            port = init(iShairPort + retryInterval * i, hardwareAddr);
            if (0 < port)
                break;
        }

        if (0 > port) {
            return -1;
        }

        ret = start();
        if (false == ret) {
            unInit();
            return -1;
        }

        return port;
    }


    public synchronized void unRegisgterCallBacks() {
        callbacks = null;
    }

    public synchronized boolean airplayUninit() {
        stop();
        unInit();
        return true;
    }


    private native int init(int iShairPort, String hardwareAddr);

    private native boolean start();

    public native boolean stop();

    private native boolean unInit();

    public void audio_init(int bits, int channels, int sampleRate) {
        LOG.warning("call audio_init : " + "bits : " + bits + "channels : " + channels + "sampleRate : " + sampleRate);
        if (null != callbacks)
            callbacks.audio_init(bits, channels, sampleRate);
        return;
    }


    public void audio_destroy() {
        LOG.warning("call audio_destroy");
        if (null != callbacks)
            callbacks.audio_destroy();
        return;
    }

    public void audio_flush() {
        LOG.warning("call audio_flush");
        if (null != callbacks)
            callbacks.audio_flush();
        return;
    }

    public void audio_data(byte[] buffer, int buflen) {
//        LOG.warning("call audio_data");
        if (null != callbacks)
            callbacks.audio_data(buffer, buflen);
        return;
    }

    public void audio_set_volume(float volume) {
        LOG.warning("call audio_set_volume : " + "volume : " + volume);
        if (null != callbacks)
            callbacks.audio_set_volume(volume);
        return;
    }

    public void audio_set_progress(byte[] progress, int buflen) {
//        LOG.warning("call audio_set_progress : " + "buflen : " + buflen);
        if (null != callbacks)
            callbacks.audio_set_progress(progress, buflen);
        return;
    }

    public void audio_set_total_len(byte[] totalLen, int buflen) {
        LOG.warning("call audio_set_total_len : " + "buflen : " + buflen);
        if (null != callbacks)
            callbacks.audio_set_total_len(totalLen, buflen);
        return;
    }

    public void audio_set_metadata(byte[] metadata, int buflen) {
        LOG.warning("call audio_set_metadata : " + "buflen : " + buflen);
        if (null != callbacks)
            callbacks.audio_set_metadata(metadata, buflen);
        return;
    }

    public void audio_set_coverart(byte[] metadata, int buflen) {
        LOG.warning("call audio_set_coverart : " + "buflen : " + buflen);
        if (null != callbacks)
            callbacks.audio_set_coverart(metadata, buflen);

        return;
    }

}
