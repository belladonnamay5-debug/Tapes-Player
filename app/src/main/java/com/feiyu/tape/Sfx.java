package com.feiyu.tape;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import com.feiyu.tape.core.DeckRenderer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * 机械声：按键的"咔哒"、装带的"咔嗒"、长按倒带的"沙沙"。
 *
 * 2.7 起不再用代码合成音色，统一播放资源里的
 * res/raw/tape_click.wav —— 关闭盒盖/开合的那一下"咔哒"。
 * 按键、装带、倒带长按都用这同一个采样，只改音量与是否循环。
 */
public class Sfx {

    /** 解析不出来时的兜底采样率，正常情况用 WAV 自己的采样率 */
    private static final int FALLBACK_RATE = 22050;

    private final int rate;
    private final short[] sample;
    /** 按键用的短咔哒：从整段机械声里截主瞬态，避免 1 秒尾巴拖进播放。 */
    private final short[] clickSample;
    /** 快进/快退只取更短的一小段，避免连续操作时机械声盖住音乐。 */
    private final short[] nudgeSample;

    private AudioTrack scratch;
    private boolean scratchOn;

    /** 按键/装带的一次性轨，暂停时必须能一起掐掉，否则尾巴会拖进静音里。 */
    private final ArrayList<AudioTrack> oneShots = new ArrayList<>();

    public Sfx(Context ctx) {
        int r = FALLBACK_RATE;
        short[] s = null;
        try {
            Pcm p = readWav(ctx, R.raw.tape_click);
            if (p != null && p.data.length > 0) {
                r = p.rate;
                s = p.data;
            }
        } catch (Throwable ignored) {
        }
        if (s == null) {
            // 资源损坏也不能让整台机器没声：用一个最小的合成咔哒兜底。
            s = fallbackClick();
        }
        rate = r;
        sample = s;
        clickSample = makeClick(s, r, 0.10f, 0.48f, 0.03f);
        nudgeSample = makeClick(s, r, 0.10f, 0.26f, 0.02f);
    }

    /**
     * 从完整机械声里截一段给按键用：保留 0.12s 的小瞬态和 0.28s 的主咔哒，
     * 0.48s 收尾并淡出；装带/沙沙声仍用完整采样。
     */
    private static short[] makeClick(short[] src, int rate, float from, float to, float fadeSec) {
        if (src == null || src.length < (int) (to * rate)) return src;
        int a = Math.min(src.length, (int) (from * rate));
        int b = Math.min(src.length, (int) (to * rate));
        if (b <= a) return src;
        short[] out = new short[b - a];
        System.arraycopy(src, a, out, 0, out.length);
        int fade = Math.max(1, (int) (fadeSec * rate));
        for (int i = Math.max(0, out.length - fade); i < out.length; i++) {
            float g = (out.length - i) / (float) fade;
            out[i] = (short) (out[i] * g);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 统一的播放
    // ------------------------------------------------------------------
    private void playBuffer(short[] buf, float volume) {
        if (buf == null || buf.length == 0) return;
        try {
            int bytes = buf.length * 2;
            int minBuf = AudioTrack.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, rate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(bytes, minBuf), AudioTrack.MODE_STATIC);
            t.write(buf, 0, buf.length);
            t.setVolume(volume);
            t.play();
            final AudioTrack ref = t;
            synchronized (oneShots) {
                oneShots.add(ref);
            }
            final int ms = Math.max(120, (bytes * 1000) / Math.max(1, rate * 2));
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(ms);
                    } catch (InterruptedException ignored) {
                    }
                    stopOne(ref);
                }
            }).start();
        } catch (Throwable ignored) {
        }
    }

    /** 停掉一条一次性轨；重复调用是安全的。 */
    private void stopOne(AudioTrack t) {
        if (t == null) return;
        try {
            t.stop();
        } catch (Throwable ignored) {
        }
        try {
            t.release();
        } catch (Throwable ignored) {
        }
        synchronized (oneShots) {
            oneShots.remove(t);
        }
    }

    /** 暂停/停止/退到后台时，把所有按键、装带的尾巴一起掐掉。 */
    public void stopClicks() {
        ArrayList<AudioTrack> copy;
        synchronized (oneShots) {
            if (oneShots.isEmpty()) return;
            copy = new ArrayList<>(oneShots);
            oneShots.clear();
        }
        for (AudioTrack t : copy) {
            stopOne(t);
        }
    }

    // ------------------------------------------------------------------
    // 对外
    // ------------------------------------------------------------------
    public void click(int btn) {
        // 连续点按时只留最新一声，避免几声咔哒叠在一起。
        stopClicks();
        playBuffer(clickSample, btn == DeckRenderer.BTN_PLUS ? 0.34f : 0.50f);
    }

    /** 快进/快退短按：只给一记很短的机械瞬态，不拖长尾。 */
    public void nudge() {
        stopClicks();
        playBuffer(nudgeSample, 0.30f);
    }

    public void clunk() {
        playBuffer(sample, 0.75f);
    }

    public void startScratch() {
        if (scratchOn) return;
        // 长按进入沙沙声时，按下那一下的 click 尾巴不该还在响。
        stopClicks();
        scratchOn = true;
        try {
            int bytes = sample.length * 2;
            scratch = new AudioTrack(AudioManager.STREAM_MUSIC, rate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(bytes, AudioTrack.getMinBufferSize(rate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT)),
                    AudioTrack.MODE_STATIC);
            scratch.write(sample, 0, sample.length);
            scratch.setLoopPoints(0, sample.length, -1);
            scratch.setVolume(0.22f);
            scratch.play();
        } catch (Throwable e) {
            scratchOn = false;
        }
    }

    public void stopScratch() {
        if (!scratchOn) return;
        scratchOn = false;
        AudioTrack t = scratch;
        scratch = null;
        if (t != null) {
            try {
                t.stop();
            } catch (Throwable ignored) {
            }
            try {
                t.release();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 倒带越快，声音越响一点（0 = 刚按下，1 = 已经到顶）。
     * 只是抬音量，不改播放速率。
     */
    public void setScratchIntensity(float t) {
        AudioTrack a = scratch;
        if (!scratchOn || a == null) return;
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        try {
            a.setVolume(0.20f + 0.16f * t);
        } catch (Throwable ignored) {
        }
    }

    public void release() {
        stopClicks();
        stopScratch();
    }

    // ------------------------------------------------------------------
    // 读资源里的 WAV（PCM 16-bit），转成单声道 16-bit
    // ------------------------------------------------------------------
    private static final class Pcm {
        final short[] data;
        final int rate;

        Pcm(short[] data, int rate) {
            this.data = data;
            this.rate = rate;
        }
    }

    private static Pcm readWav(Context ctx, int resId) throws Exception {
        InputStream in = null;
        try {
            in = ctx.getResources().openRawResource(resId);
            ByteArrayOutputStream out = new ByteArrayOutputStream(96 * 1024);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            byte[] b = out.toByteArray();

            if (b.length < 44 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F') return null;

            int p = 12;
            int fmt = -1, channels = 1, bits = 16, rate = FALLBACK_RATE;
            int dataOff = -1, dataLen = 0;
            while (p + 8 <= b.length) {
                int size = le32(b, p + 4);
                int body = p + 8;
                if (size < 0 || body + size > b.length) break;
                if (b[p] == 'f' && b[p + 1] == 'm' && b[p + 2] == 't' && b[p + 3] == ' ') {
                    fmt = le16(b, body);
                    channels = Math.max(1, le16(b, body + 2));
                    rate = Math.max(8000, le32(b, body + 4));
                    bits = le16(b, body + 14);
                } else if (b[p] == 'd' && b[p + 1] == 'a' && b[p + 2] == 't' && b[p + 3] == 'a') {
                    dataOff = body;
                    dataLen = size;
                }
                p = body + size + (size & 1);
            }
            if (fmt != 1 || bits != 16 || dataOff < 0 || dataLen <= 0) return null;

            int frameBytes = channels * 2;
            int frames = dataLen / frameBytes;
            if (frames <= 0) return null;
            short[] mono = new short[frames];
            int off = dataOff;
            for (int i = 0; i < frames; i++) {
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    int lo = b[off] & 0xFF;
                    int hi = b[off + 1];
                    sum += (short) (lo | (hi << 8));
                    off += 2;
                }
                mono[i] = (short) (sum / channels);
            }
            return new Pcm(mono, rate);
        } finally {
            try {
                if (in != null) in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int le16(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8)
                | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
    }

    /** 仅当资源读不出来时兜底，正常情况听不到它。 */
    private static short[] fallbackClick() {
        int n = 1600;
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) n;
            float env = (float) Math.pow(1f - t, 3f);
            out[i] = (short) (Math.sin(2 * Math.PI * 180f * i / FALLBACK_RATE) * env * 18000);
        }
        return out;
    }
}
