package com.feiyu.tape;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import com.feiyu.tape.core.Quality;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * 代替 MediaPlayer 的播放器：自己解码，自己过一遍 RadioDsp，再自己送给 AudioTrack。
 *
 * 之所以不用 MediaPlayer，是因为它不给插手音频数据的机会 —— 只能用系统那套
 * AudioEffect（均衡器之类），做不出"砍频带 + 削波 + 底噪"的收音机味。
 *
 * 线程模型：{@link #open} 在调用线程把 extractor / codec 打开，
 * 之后由一个后台线程持续解码、处理、写 AudioTrack。对外的方法都是线程安全的。
 */
public class RadioPlayer {

    public interface OnComplete {
        void onComplete();
    }

    private final MediaExtractor ex = new MediaExtractor();
    private MediaCodec dec;
    private AudioTrack track;
    private RadioDsp dsp;

    private Thread pump;
    private final Object lock = new Object();

    private volatile boolean released;
    private volatile boolean wantPlay;
    private volatile boolean paused = true;
    private volatile boolean eos;
    private volatile long seekReqMs = -1;
    private volatile long posBaseMs;
    private volatile int sampleRate = 44100;
    /** DSP 之后真正送进 AudioTrack 的采样率（抽取后的固定值，11kHz 上下） */
    private volatile int outRate = RadioDsp.TARGET_RATE;
    private float[] outBuf;
    private float[] resBuf;
    private Resampler rs;
    private int channels = 2;
    private volatile float level;
    /** seek / 新音轨开头淡入的剩余帧数，避免从旧相位直接切进新波形产生“啪”声 */
    private static final int FADE_LEN = 256;
    private int fadePos = FADE_LEN;
    /**
     * 新轨/seek 后先攒一点真实 PCM 再 play()。
     * 之前是建轨就 play，播放头先跑、解码器还没写出，必然 underrun：
     * 听感就是开头“滋”一下或卡一下。这里不塞静音，只等首块数据。
     */
    private volatile boolean startPending;
    private volatile int prefillFrames;

    private OnComplete onComplete;

    /** 调试用：把解码后（处理前）与处理后的 PCM 各 dump 一段，用来判断"噪声"到底哪一步来的 */
    public static volatile boolean DUMP;
    /** 收音机档固定输出 32kHz */
    private static final int RADIO_RATE = 32000;
    /** 音质档位：HI-FI / AM / BASS */
    private volatile int quality = Quality.AM;
    /** 主线程只登记"想切到哪一档"，真正重建交给泵线程，避免和正在写音轨的线程抢 */
    private volatile int pendingQuality = Quality.AM;
    private volatile long pendingSeekMs = -1;
    /** BASS 档的专用 DSP（源采样率上跑，不重采样） */
    private volatile BassDsp bass;
    /** 收音机强度 0..1：0 只是闷一点，1 是满档。默认取折中档 */
    public static volatile float STRENGTH = RadioDsp.DEFAULT_STRENGTH;
    private static final long DUMP_FRAMES = 44100L * 8;
    /** 前 25 秒不 dump：很多歌开头是近乎无声的前奏，抓下来全是底噪，看不出问题 */
    private static final long DUMP_SKIP = 44100L * 25;
    private long totalFrames;
    private java.io.OutputStream dumpPre, dumpPost;
    private long dumpedFrames;
    private android.content.Context appCtx;

    /** 打开一个文件（可能在 IO 线程上调用，慢一点没关系） */
    public RadioPlayer(Context ctx, Uri uri, OnComplete cb) throws Exception {
        appCtx = ctx;
        onComplete = cb;
        ex.setDataSource(ctx, uri, null);
        int trackIdx = -1;
        int i = 0;
        int n = ex.getTrackCount();
        String mime = null;
        while (i < n) {
            MediaFormat f = ex.getTrackFormat(i);
            String m = f.getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith("audio/")) {
                trackIdx = i;
                mime = m;
                break;
            }
            i++;
        }
        if (trackIdx < 0) throw new IllegalStateException("没有音频轨");
        ex.selectTrack(trackIdx);
        dec = MediaCodec.createDecoderByType(mime);
        dec.configure(ex.getTrackFormat(trackIdx), null, null, 0);
        dec.start();
    }

    public void start() {
        wantPlay = true;
        paused = false;
        // 恢复播放也别让空轨先跑，等泵线程写进第一块数据再 play。
        startPending = true;
        prefillFrames = 0;
        ensurePump();
    }

    public void pause() {
        paused = true;
        startPending = true;
        prefillFrames = 0;
    }

    public boolean isPlaying() {
        return wantPlay && !paused;
    }

    public void seekTo(long ms) {
        seekReqMs = Math.max(0, ms);
    }

    /** 已经从这次 seek 起走到哪儿了（毫秒） */
    public long positionMs() {
        long head = 0;
        AudioTrack t = track;
        try {
            if (t != null) head = t.getPlaybackHeadPosition() & 0xffffffffL;
        } catch (Throwable ignored) {
        }
        return posBaseMs + head * 1000L / Math.max(1, outRate);
    }

    public int quality() {
        return quality;
    }

    /**
     * 按当前档位 / sampleRate 重建整条采样率链。
     *
     * 之前 applyRadio() 和 INFO_OUTPUT_FORMAT_CHANGED 各自改一半字段：
     * 解码器报出真实采样率时只重建 dsp / outRate，却把 applyRadio 建好的
     * rs（44.1k → 32k）留着，于是 32k 的采样被写进 44.1k 的 AudioTrack，
     * 速度快 44100/32000 ≈ 1.378 倍，人声变成小孩。统一走这里就不会再错位。
     */
    private void rebuildChain() {
        int q = Quality.clamp(quality);
        if (q == Quality.AM) {
            rs = new Resampler(sampleRate, RADIO_RATE);
            dsp = new RadioDsp(RADIO_RATE, STRENGTH);
            bass = null;
            outRate = RADIO_RATE;
        } else if (q == Quality.BASS) {
            rs = null;
            dsp = null;
            bass = new BassDsp(sampleRate);
            outRate = Math.max(8000, sampleRate);
        } else {
            rs = null;
            dsp = null;
            bass = null;
            outRate = Math.max(8000, sampleRate);
        }
    }

    /** 只切内部状态，不重建音轨（新建播放器时用，此时还没有轨道） */
    void applyQuality(int q) {
        quality = Quality.clamp(q);
        pendingQuality = quality;
        rebuildChain();
    }

    /**
     * 播放中切换音质：只登记目标档位和当前位置，由泵线程在两次写入之间换轨。
     * 不能在主线程直接 makeTrack()：那会把泵线程正在写的 AudioTrack release 掉，
     * 泵线程下一句 rs.process / write 就 NPE，声音停住、位置还会跳。
     */
    public void setQuality(int q) {
        int target = Quality.clamp(q);
        pendingSeekMs = positionMs();
        pendingQuality = target;
    }

    public float level() {
        return level;
    }

    public void release() {
        released = true;
        wantPlay = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        try {
            if (pump != null) pump.join(600);
        } catch (InterruptedException ignored) {
        }
        pump = null;
        closeAll();
    }

    private void closeAll() {
        if (track != null) {
            try {
                track.pause();
            } catch (Throwable ignored) {
            }
            try {
                track.flush();
            } catch (Throwable ignored) {
            }
            try {
                track.release();
            } catch (Throwable ignored) {
            }
            track = null;
        }
        if (dec != null) {
            try {
                dec.stop();
            } catch (Throwable ignored) {
            }
            try {
                dec.release();
            } catch (Throwable ignored) {
            }
            dec = null;
        }
        try {
            ex.release();
        } catch (Throwable ignored) {
        }
    }

    private void ensurePump() {
        if (pump != null) return;
        pump = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    loop();
                } catch (Throwable t) {
                    TapeApp.report(t);
                }
            }
        }, "radio-pump");
        pump.setPriority(Thread.NORM_PRIORITY + 1);
        pump.start();
    }

    // =====================================================================
    // 后台泵：解码 → DSP → AudioTrack
    // =====================================================================
    private void loop() throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        ByteBuffer[] inputBuffers = dec.getInputBuffers();
        ByteBuffer[] outputBuffers = dec.getOutputBuffers();
        boolean inputDone = false;
        long framesOut = 0;
        long lastTick = System.currentTimeMillis();
        float lv = 0;

        while (!released) {
            // ---- 音质切换：在泵线程里换链、换轨，和写入严格串行 ----
            int wantQ = pendingQuality;
            if (wantQ != quality) {
                long at = pendingSeekMs;
                pendingSeekMs = -1;
                applyQuality(wantQ);
                makeTrack();
                seekReqMs = Math.max(0, at >= 0 ? at : positionMs());
                framesOut = 0;
            }

            // 默认档（尤其 AM）启动时不会触发上面的“音质切换”，
            // 这里补一次建轨，否则 track 一直为 null，writeBlocking 永远直接返回。
            if (track == null) makeTrack();

            // ---- 处理 seek ----
            long s = seekReqMs;
            if (s >= 0) {
                seekReqMs = -1;
                doSeek(s);
                inputDone = false;
                eos = false;
                framesOut = 0;
            }

            if (!wantPlay || paused) {
                if (track != null) {
                    try {
                        track.pause();
                    } catch (Throwable ignored) {
                    }
                    // 暂停时清掉还没播完的旧数据，恢复/seek 后不会先吐出
                    // 一段上一次播放的缓冲。
                    try {
                        track.flush();
                    } catch (Throwable ignored) {
                    }
                    // flush 把缓冲清空了，恢复时必须重新攒首块再 play。
                    startPending = true;
                    prefillFrames = 0;
                }
                synchronized (lock) {
                    lock.wait(40);
                }
                lastTick = System.currentTimeMillis();
                continue;
            }

            if (track != null && !startPending
                    && track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    track.play();
                } catch (Throwable ignored) {
                }
            }

            // ---- 喂输入 ----
            if (!inputDone) {
                int ii = dec.dequeueInputBuffer(2000);
                if (ii >= 0) {
                    // 用 getInputBuffers() 而不是 getInputBuffer()：后者是 API 21 起
                    // 才有的，这台平板是 19，得走老接口
                    ByteBuffer ib = inputBuffers[ii];
                    int sz = ib == null ? -1 : ex.readSampleData(ib, 0);
                    if (sz < 0) {
                        dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        dec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }

            // ---- 取输出 ----
            int oi = dec.dequeueOutputBuffer(info, 4000);
            if (oi >= 0) {
                if (info.size > 0) {
                    ByteBuffer ob = (oi < outputBuffers.length) ? outputBuffers[oi] : null;
                    if (ob != null) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ShortBuffer sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer();
                        int frames = sb.remaining() / Math.max(1, channels);
                        short[] pcm = new short[sb.remaining()];
                        sb.get(pcm);
                        float[] mono = toMono(pcm, frames);
                        float[] preDsp = null;
                        if (DUMP) {
                            totalFrames += frames;
                            if (totalFrames > DUMP_SKIP && totalFrames < DUMP_SKIP + DUMP_FRAMES) {
                                preDsp = mono.clone();
                            }
                        }
                        int q = Quality.clamp(quality);
                        if (q == Quality.AM) {
                            if (dsp == null || rs == null) {
                                rebuildChain();
                                if (track != null) makeTrack();
                            }
                            int need = (int) (frames * (double) RADIO_RATE / Math.max(8000, sampleRate)) + 8;
                            if (resBuf == null || resBuf.length < need) resBuf = new float[need];
                            int n32 = rs.process(mono, frames, resBuf);
                            if (outBuf == null || outBuf.length < n32 + 8) outBuf = new float[n32 + 8];
                            int n = dsp.process(resBuf, n32, outBuf);
                            if (preDsp != null) {
                                dump(preDsp, frames, false);
                                // 处理后的采样率是 outRate，和 pre 不同，分析时按各自的率算
                                dump(outBuf, n, true);
                            }
                            short[] out = toShort(outBuf, n);
                            writeBlocking(out, n);
                            framesOut += n;
                            float rms = rmsOf(outBuf, n);
                            lv += (rms - lv) * 0.25f;
                            level = Math.min(1f, lv * 3.4f);
                        } else if (q == Quality.BASS) {
                            if (bass == null) {
                                rebuildChain();
                                if (track != null) makeTrack();
                            }
                            if (outBuf == null || outBuf.length < frames + 8) outBuf = new float[frames + 8];
                            int n = bass.process(mono, frames, outBuf);
                            if (preDsp != null) dump(preDsp, frames, false);
                            if (preDsp != null) dump(outBuf, n, true);
                            short[] out = toShort(outBuf, n);
                            writeBlocking(out, n);
                            framesOut += n;
                            float rms = rmsOf(outBuf, n);
                            lv += (rms - lv) * 0.25f;
                            level = Math.min(1f, lv * 2.6f);
                        } else {
                            // 原声：一个字都不改，直接按源采样率送出去
                            if (rs != null || dsp != null || bass != null) {
                                rebuildChain();
                                if (track != null) makeTrack();
                            }
                            if (preDsp != null) dump(preDsp, frames, false);
                            short[] out = toShort(mono, frames);
                            writeBlocking(out, frames);
                            framesOut += frames;
                            float rms = rmsOf(mono, frames);
                            lv += (rms - lv) * 0.25f;
                            level = Math.min(1f, lv * 2.4f);
                        }
                    }
                }
                boolean last = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                dec.releaseOutputBuffer(oi, false);
                if (last) eos = true;
            } else if (oi == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                outputBuffers = dec.getOutputBuffers();
            } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = dec.getOutputFormat();
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                }
                if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
                // 真实采样率到这时才确定：整条链（重采样 + DSP + 音轨）一起重建，
                // 不能再只改 outRate 而留下旧的重采样器。
                rebuildChain();
                makeTrack();
            }

            // ---- 放完了 ----
            if (eos && track != null) {
                long head = track.getPlaybackHeadPosition() & 0xffffffffL;
                if (head >= framesOut) {
                    paused = true;
                    wantPlay = false;
                    if (onComplete != null) onComplete.onComplete();
                    eos = false;
                }
            }

            long now = System.currentTimeMillis();
            if (now - lastTick > 90) {
                lastTick = now;
            } else if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                synchronized (lock) {
                    lock.wait(6);
                }
            }
        }
    }

    private void doSeek(long ms) {
        try {
            ex.seekTo(ms * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            dec.flush();
            if (track != null) {
                track.pause();
                track.flush();
                // seek 后解码器要重新出第一块，先别让空轨跑。
                startPending = true;
                prefillFrames = 0;
            }
            // flush 后播放头不一定立刻归零；把这段旧头补进基准，避免 seek 后
            // positionMs() 直接把全局位置多算几十毫秒，TapeEngine 又反向纠正。
            long head = 0;
            if (track != null) {
                try {
                    head = track.getPlaybackHeadPosition() & 0xffffffffL;
                } catch (Throwable ignored) {
                }
            }
            posBaseMs = Math.max(0, ms - head * 1000L / Math.max(1, outRate));
            fadePos = 0;
            level = 0f;
            if (rs != null) rs.reset();
            if (quality == Quality.AM) {
                // 滤波器状态留着会"啪"一声，重建最省事。
                // 收音机档的 DSP 固定跑在 RADIO_RATE 上（重采样已经先做过了）。
                dsp = new RadioDsp(RADIO_RATE, STRENGTH);
            } else if (quality == Quality.BASS && bass != null) {
                bass.reset();
            }
        } catch (Throwable t) {
            TapeApp.report(t);
        }
    }

    private void makeTrack() {
        int chCfg = channels >= 2 ? android.media.AudioFormat.CHANNEL_OUT_MONO
                : android.media.AudioFormat.CHANNEL_OUT_MONO;
        int minBuf = AudioTrack.getMinBufferSize(outRate, chCfg,
                android.media.AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, outRate * 2);
        AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, outRate, chCfg,
                android.media.AudioFormat.ENCODING_PCM_16BIT, bufSize,
                AudioTrack.MODE_STREAM);
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            try {
                t.release();
            } catch (Throwable ignored) {
            }
            return;
        }
        AudioTrack old = track;
        track = t;
        fadePos = 0;
        prefillFrames = 0;
        startPending = wantPlay && !paused;
        if (old != null) {
            // 先停再放，避免旧轨在 release 前还吐缓冲。
            try {
                old.pause();
            } catch (Throwable ignored) {
            }
            try {
                old.flush();
            } catch (Throwable ignored) {
            }
            try {
                old.release();
            } catch (Throwable ignored) {
            }
        }
        // 不在这里 play：等 writeBlocking 先写入一小段真实 PCM 再启动。
    }

    private void writeBlocking(short[] buf, int frames) {
        AudioTrack t = track;
        if (t == null) return;
        int off = 0;
        int left = frames;
        while (left > 0 && !released) {
            if (paused || !wantPlay) return;
            int w = t.write(buf, off, left);
            if (w <= 0) {
                synchronized (lock) {
                    try {
                        lock.wait(5);
                    } catch (InterruptedException ignored) {
                    }
                }
                continue;
            }
            off += w;
            left -= w;
            if (startPending) {
                prefillFrames += w;
                // 约 46–64ms 的真实 PCM，足够覆盖解码线程下一轮的抖动；
                // 不是静音，所以不会额外增加可听出来的启动延迟。
                int target = Math.min(2048, Math.max(256, outRate / 16));
                if (prefillFrames >= target && !paused && wantPlay) {
                    try {
                        t.play();
                    } catch (Throwable ignored) {
                    }
                    startPending = false;
                }
            }
        }
    }

    private void dump(float[] buf, int n, boolean post) {
        try {
            if (dumpedFrames > DUMP_FRAMES) return;

            java.io.OutputStream os = post ? dumpPost : dumpPre;
            if (os == null) {
                java.io.File f = new java.io.File(appCtx.getExternalFilesDir(null),
                        post ? "dump_post.raw" : "dump_pre.raw");
                os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(f));
                if (post) dumpPost = os;
                else dumpPre = os;
            }
            byte[] b = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                int v = (int) (Math.max(-1f, Math.min(1f, buf[i])) * 32767);
                b[i * 2] = (byte) (v & 0xff);
                b[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
            }
            os.write(b);
            dumpedFrames += n;
            if (dumpedFrames > DUMP_FRAMES) {
                try {
                    if (dumpPre != null) dumpPre.close();
                    if (dumpPost != null) dumpPost.close();
                } catch (Throwable ignored) {
                }
                dumpPre = dumpPost = null;
            }
        } catch (Throwable ignored) {
        }
    }

    private float[] toMono(short[] pcm, int frames) {
        float[] mono = new float[frames];
        int ch = Math.max(1, channels);
        for (int i = 0; i < frames; i++) {
            float s = 0;
            for (int c = 0; c < ch; c++) {
                int idx = i * ch + c;
                if (idx < pcm.length) s += pcm[idx] / 32768f;
            }
            mono[i] = s / ch;
        }
        return mono;
    }

    private short[] toShort(float[] mono, int frames) {
        short[] out = new short[frames];
        int fade = Math.min(frames, Math.max(0, FADE_LEN - fadePos));
        for (int i = 0; i < frames; i++) {
            float v = mono[i];
            if (i < fade) {
                v *= (fadePos + i + 1) / (float) (FADE_LEN + 1);
            }
            if (v > 1f) v = 1f;
            else if (v < -1f) v = -1f;
            out[i] = (short) (v * 32767f);
        }
        fadePos = Math.min(FADE_LEN, fadePos + frames);
        return out;
    }

    private float rmsOf(float[] buf, int n) {
        float sum = 0;
        for (int i = 0; i < n; i++) sum += buf[i] * buf[i];
        return (float) Math.sqrt(sum / Math.max(1, n));
    }

    /** 线性插值重采样：只在"原声 -> 收音机"这一档的入口用，后面的低通会把镜像削掉 */
    static final class Resampler {
        private final double step;
        private double pos;
        private float last;

        Resampler(int srcRate, int dstRate) {
            this.step = Math.max(8000, srcRate) / (double) Math.max(8000, dstRate);
        }

        void reset() {
            pos = 0;
            last = 0f;
        }

        int process(float[] in, int len, float[] out) {
            int o = 0;
            int i = 0;
            while (pos < len) {
                int i0 = (int) pos;
                if (i0 >= len - 1) break;
                float frac = (float) (pos - i0);
                out[o++] = in[i0] * (1f - frac) + in[i0 + 1] * frac;
                pos += step;
            }
            pos -= len;
            if (pos < 0) pos = 0;
            return o;
        }
    }
}
