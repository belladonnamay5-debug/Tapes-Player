package com.feiyu.tape;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.feiyu.tape.core.Quality;
import com.feiyu.tape.core.TapeModel;

import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 播放引擎：一条连续的"带子" + 一个 MediaPlayer。
 *
 * 关键点（和网页版一致）：
 *  - 全局时间轴 = 歌 / 4 秒空白 / 歌 / 4 秒空白 …
 *  - 倒带快进都作用在全局位置上，所以能倒回上一首中间
 *  - 没有 stop，只有播放 / 暂停；暂停后再按播放从原处继续
 *  - 长按倒带 / 快进时音频静音，只有带子（和沙沙声）在动
 */
public class TapeEngine {

    public interface Listener {
        void onTapeChanged();   // 换了一盘带子
        void onPositionJump();  // 位置突变（渲染器可用来做微抖）
        void onTapeFinished();  // 整盘带走完，自动停机
    }

    private static final int TICK_MS = 20;
    /**
     * 长按倒带的倍速：刚按下去是 {@link #SCRATCH_START} 倍，按得越久越快，
     * 按住 {@link #SCRATCH_RAMP} 秒爬到 {@link #SCRATCH_TOP} 倍之后就封顶。
     * 6 → 48 倍：一盘 60 分钟的带子，松手前大概 1 分半能倒穿。
     */
    private static final float SCRATCH_START = 6f;
    private static final float SCRATCH_TOP = 48f;
    private static final float SCRATCH_RAMP = 2.8f;

    public final TapeModel model = new TapeModel();

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Random rnd = new Random();

    private RadioPlayer mp;
    /** 音质档位：HI-FI / AM / BASS */
    private volatile int quality = Quality.AM;
    private int mpSeg = -1;         // MediaPlayer 当前准备的是第几段
    private int loadToken;
    private int pendingSeg = -1;
    private boolean mpPlaying;

    public volatile long pos;       // 全局位置（毫秒）
    public volatile boolean playing;
    public volatile boolean scratching;
    public volatile boolean scratchForward;
    public volatile boolean loading;
    /** 当前倒带倍速（渲染器拿它转卷轴、调沙沙声） */
    public volatile float scratchSpeed = SCRATCH_START;
    /** 这一次长按已经按住多久（秒），倍数按它往上爬 */
    private float scratchHeld;

    private boolean wasPlayingBeforeScratch;
    private long lastTick;
    /**
     * 最近一次把播放器 seek 到新位置的时间。seek 之后播放头需要一段时间才追上，
     * 这段“稳定期”内不能用旧的 positionMs 反推全局位置，否则会把刚跳到的位置
     * 又拽回去，听起来就是回跳、卡顿和反复的滋滋声。
     */
    private long lastSeekAt;
    private static final long SEEK_GRACE_MS = 700L;
    private float level;
    private Listener listener;

    public TapeEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    // =====================================================================
    // 装带
    // =====================================================================
    public void loadTape(List<TapeModel.Track> tracks, long startPos) {
        stopEverything();
        model.build(tracks);
        pos = Math.max(0, Math.min(startPos, model.totalMs));
        if (listener != null) listener.onTapeChanged();
        startTicking();
    }

    public void restoreTape(List<TapeModel.Track> tracks, long startPos) {
        model.build(tracks);
        pos = Math.max(0, Math.min(startPos, model.totalMs));
        startTicking();
    }

    private void stopEverything() {
        playing = false;
        scratching = false;
        if (mp != null) {
            try {
                mp.release();
            } catch (Throwable ignored) {
            }
            mp = null;
        }
        mpSeg = -1;
        mpPlaying = false;
        pendingSeg = -1;
        loadToken++;
    }

    public int quality() {
        return quality;
    }

    /** 切音质：正在放的这首会立刻换过去，位置不丢 */
    public void setQuality(int q) {
        quality = Quality.clamp(q);
        RadioPlayer rp = mp;
        if (rp != null) rp.setQuality(quality);
    }

    public boolean hasTape() {
        return model.hasTape();
    }

    public float progress() {
        return model.progress(pos);
    }

    public float level() {
        return level;
    }

    // =====================================================================
    // 走带控制
    // =====================================================================
    public void play() {
        if (!model.hasTape()) return;
        if (pos >= model.totalMs - 1) {
            // 带子已经走完、机芯停着：再按 ▶ 就从第一首重新装带。
            // 这不是"自动倒回"——停机后仍停在带尾，只有人再按一次才回到开头。
            pos = 0;
        }
        playing = true;
        syncSegment();
        startTicking();
    }

    public void pause() {
        playing = false;
        if (mp != null) {
            try {
                mp.pause();
            } catch (Throwable ignored) {
            }
        }
    }

    public void toggle() {
        if (playing) pause();
        else play();
    }

    /** 全局长跳（按钮按一下 = 10 秒） */
    public void nudge(long deltaMs) {
        seekTo(pos + deltaMs, playing);
    }

    public void seekTo(long target, boolean autoplay) {
        if (!model.hasTape()) return;
        pos = Math.max(0, Math.min(target, model.totalMs));
        playing = autoplay;
        syncSegment();
        if (autoplay) startTicking();
    }

    /**
     * 红色圆点键：整机终止。
     * 带子退回开头，机芯停转，但带子还留在机里——再按 ▶ 就是从头开始。
     */
    public void stop() {
        playing = false;
        scratching = false;
        loadToken++;            // 让正在装载的结果作废
        pendingSeg = -1;
        loading = false;
        if (mp != null) {
            try {
                mp.release();
            } catch (Throwable ignored) {
            }
            mp = null;
        }
        mpPlaying = false;
        mpSeg = -1;
        pos = 0;
        handler.removeCallbacks(tick);
        updateLevel(0.05f, false);
    }

    /**
     * 整盘带走完：机芯自己停，位置停在带尾，不再空转。
     * 和红色停止键不同，这里不把带子退回开头；用户再按 ▶ 才从头开始。
     */
    private void finishTape() {
        if (!playing && !scratching) return;
        playing = false;
        scratching = false;
        scratchHeld = 0f;
        scratchSpeed = SCRATCH_START;
        pos = model.totalMs;
        handler.removeCallbacks(tick);
        loadToken++;            // 让正在装载的结果作废
        pendingSeg = -1;
        loading = false;
        if (mp != null) {
            try {
                mp.release();
            } catch (Throwable ignored) {
            }
            mp = null;
        }
        mpPlaying = false;
        mpSeg = -1;
        updateLevel(0.05f, false);
        if (listener != null) listener.onTapeFinished();
    }

    // 长按倒带 / 快进：磁带快速走，但不发声
    public void beginScratch(boolean forward) {
        if (!model.hasTape()) return;
        wasPlayingBeforeScratch = playing;
        if (mp != null) {
            try {
                mp.pause();
            } catch (Throwable ignored) {
            }
        }
        playing = false;
        scratching = true;
        scratchForward = forward;
        // 每次重新按下去都从最慢的那一档起步
        scratchHeld = 0f;
        scratchSpeed = SCRATCH_START;
        startTicking();
    }

    public void endScratch() {
        if (!scratching) return;
        scratching = false;
        scratchHeld = 0f;
        scratchSpeed = SCRATCH_START;
        if (wasPlayingBeforeScratch) {
            playing = true;
            syncSegment();
        }
    }

    /** 当前倒带倍速，给渲染器用 */
    public float scratchSpeed() {
        return scratchSpeed;
    }

    public boolean isScratching() {
        return scratching;
    }

    // =====================================================================
    // 走带时钟
    // =====================================================================
    private void startTicking() {
        handler.removeCallbacks(tick);
        lastTick = SystemClock.uptimeMillis();
        handler.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            float dt = (now - lastTick) / 1000f;
            lastTick = now;
            if (dt > 0.25f) dt = 0.25f;
            try {
                step(dt);
            } catch (Throwable t) {
                // 走带走不动了不该把整个机器带走：记下来、停下来，等下一次按键
                TapeApp.report(t);
                playing = false;
                scratching = false;
            }
            // 只在真的要动的时候才继续跑：静止时让主线程彻底歇下来
            if (playing || scratching) handler.postDelayed(this, TICK_MS);
        }
    };

    private void step(float dt) {
        if (!model.hasTape()) return;
        // 带尾：上一次 tick 刚好把位置推到头，直接停机，别让机芯空转。
        if (playing && pos >= model.totalMs) {
            finishTape();
            return;
        }

        if (scratching) {
            // 按住不放：倍速从 START 顺着 smoothstep 爬到 TOP，爬满就封顶，不再快下去
            scratchHeld = Math.min(SCRATCH_RAMP, scratchHeld + dt);
            float k = scratchHeld / SCRATCH_RAMP;
            float smooth = k * k * (3f - 2f * k);
            scratchSpeed = SCRATCH_START + (SCRATCH_TOP - SCRATCH_START) * smooth;
            pos += (long) (dt * scratchSpeed * 1000f * (scratchForward ? 1 : -1));
            if (pos <= 0) {
                pos = 0;
                scratching = false;
                scratchHeld = 0f;
                scratchSpeed = SCRATCH_START;
                if (wasPlayingBeforeScratch) {
                    playing = true;
                    syncSegment();
                }
            } else if (pos >= model.totalMs) {
                pos = model.totalMs;
                scratching = false;
                scratchHeld = 0f;
                scratchSpeed = SCRATCH_START;
            }
            updateLevel(dt, false);
            return;
        }

        if (!playing) {
            updateLevel(dt, false);
            return;
        }

        TapeModel.Segment s = model.locate(pos);
        if (s == null) return;

        if (s.gap) {
            if (mp != null) {
                try {
                    mp.pause();
                } catch (Throwable ignored) {
                }
            }
            pos += (long) (dt * 1000f);
            if (pos >= s.end) {
                pos = s.end;
                if (pos >= model.totalMs) {
                    finishTape();
                    return;
                }
                syncSegment();
            }
            updateLevel(dt, false);
            return;
        }

        // 歌曲段
        if (mpSeg != s.index) {
            if (pendingSeg != s.index) requestLoad(s);
            updateLevel(dt, false); // 换曲的瞬间时间停住，属于"机械动作"
            return;
        }
        if (mp != null && mpPlaying) {
            int mpPos = 0;
            try {
                mpPos = (int) mp.positionMs();
            } catch (Throwable ignored) {
            }
            long expect = pos - s.start;
            long sinceSeek = SystemClock.uptimeMillis() - lastSeekAt;
            if (Math.abs(expect - mpPos) > 700) {
                // seek 刚发出时播放头还停在旧位置，这时再 seek 一次会把目标越推越远。
                // 稳定期内只等，不用旧播放头覆盖 pos。
                if (sinceSeek > SEEK_GRACE_MS) {
                    try {
                        mp.seekTo((int) expect);
                    } catch (Exception ignored) {
                    }
                    lastSeekAt = SystemClock.uptimeMillis();
                }
            } else {
                pos = s.start + mpPos;
            }
        } else {
            pos += (long) (dt * 1000f);
        }

        if (pos >= s.end) {
            pos = s.end;
            if (pos >= model.totalMs) {
                finishTape();
                return;
            }
            syncSegment();
        }
        updateLevel(dt, true);
    }

    /** 让 MediaPlayer / 播放状态跟上当前 pos */
    private void syncSegment() {
        if (!model.hasTape()) return;
        TapeModel.Segment s = model.locate(pos);
        if (s == null) return;
        if (s.gap) {
            if (mp != null && mpPlaying) {
                try {
                    mp.pause();
                } catch (Exception ignored) {
                }
                mpPlaying = false;
            }
            return;
        }
        if (mp == null || mpSeg != s.index) {
            if (pendingSeg != s.index) requestLoad(s);
        } else {
            long offset = pos - s.start;
            try {
                mp.seekTo(offset);
                lastSeekAt = SystemClock.uptimeMillis();
            } catch (Exception ignored) {
            }
            if (playing) startMp();
        }
    }

    private void startMp() {
        if (mp == null) return;
        try {
            mp.start();
            mpPlaying = true;
        } catch (Throwable ignored) {
        }
    }

    // =====================================================================
    // 换曲：在 IO 线程 prepare，避免老机器卡界面
    // =====================================================================
    private void requestLoad(final TapeModel.Segment s) {
        final int token = ++loadToken;
        final TapeModel.Track track = s.track;
        final int segIndex = s.index;
        pendingSeg = segIndex;
        loading = true;
        io.execute(new Runnable() {
            @Override
            public void run() {
                RadioPlayer p = null;
                boolean ok = false;
                try {
                    p = new RadioPlayer(ctx, Uri.parse(track.uri), new RadioPlayer.OnComplete() {
                        @Override
                        public void onComplete() {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    mpPlaying = false;
                                    int idx = mpSeg;
                                    if (idx < 0 || idx >= model.timeline.size()) return;
                                    TapeModel.Segment cur = model.timeline.get(idx);
                                    if (playing) {
                                        pos = cur.end;
                                        syncSegment();
                                    }
                                }
                            });
                        }
                    });
                    p.applyQuality(quality);
                    ok = true;
                } catch (Throwable e) {
                    TapeApp.report(e);
                    ok = false;
                }
                final RadioPlayer made = p;
                final boolean success = ok;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (token != loadToken) {
                                if (made != null) {
                                    try {
                                        made.release();
                                    } catch (Throwable ignored) {
                                    }
                                }
                                return;
                            }
                            loading = false;
                            pendingSeg = -1;
                            if (!success) {
                                if (segIndex >= 0 && segIndex < model.timeline.size()) {
                                    pos = model.timeline.get(segIndex).end;
                                }
                                syncSegment();
                                return;
                            }
                            if (mp != null) {
                                try {
                                    mp.release();
                                } catch (Throwable ignored) {
                                }
                            }
                            mp = made;
                            mpSeg = segIndex;
                            mpPlaying = false;
                            long base = 0;
                            if (segIndex >= 0 && segIndex < model.timeline.size()) {
                                base = model.timeline.get(segIndex).start;
                            }
                            long offset = Math.max(0, pos - base);
                            try {
                                mp.seekTo(offset);
                                lastSeekAt = SystemClock.uptimeMillis();
                            } catch (Throwable ignored) {
                            }
                            if (playing) startMp();
                            if (listener != null) listener.onPositionJump();
                        } catch (Throwable t) {
                            TapeApp.report(t);
                            loading = false;
                            pendingSeg = -1;
                        }
                    }
                });
            }
        });
    }

    // =====================================================================
    // 电平表（没有 VISUALIZER 权限，用带惯性的随机包络模拟指针）
    // =====================================================================
    private float lvA, lvB, lvTarget;

    private void updateLevel(float dt, boolean sounding) {
        if (sounding && playing && mp != null) {
            // 电台的指针跟着真实响度走：拿解码后处理完的 RMS，不再用随机数假装
            lvTarget = Math.max(lvTarget * 0.6f, Math.min(1f, mp.level()));
        } else {
            lvTarget = 0f;
        }
        float k = sounding ? 14f : 7f;
        lvA += (lvTarget - lvA) * Math.min(1f, k * dt);
        lvB += (lvA - lvB) * Math.min(1f, 9f * dt);
        level = Math.max(0f, Math.min(1f, lvB));
    }

    public void shutdown() {
        handler.removeCallbacks(tick);
        stopEverything();
        io.shutdownNow();
    }
}
