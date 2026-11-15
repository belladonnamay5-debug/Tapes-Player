package com.feiyu.tape;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.feiyu.tape.core.DeckRenderer;
import com.feiyu.tape.core.DeckState;
import com.feiyu.tape.core.Gfx;
import com.feiyu.tape.core.Theme;
import com.feiyu.tape.gfx.CanvasGfx;

/** 整个界面就是这一块自绘的"磁带机" */
public class DeckView extends View {

    public interface Host {
        void onRequestAddTape();

        /** 按下了红色圆点：整机终止 */
        void onRequestStop();

        void onKeyFeedback(boolean longPressArc);

        /** FEIYU 旁的磨砂开关被按了：直接选 HI-FI / AM / BASS 中的一档 */
        void onSelectQuality(int quality);
    }

    private static final long HOLD_DELAY = 350;
    private static final long NUDGE_MS = 10_000;

    private final TapeEngine engine;
    private final Sfx sfx;
    private final Host host;
    private final DeckRenderer renderer = new DeckRenderer();
    private final DeckState state = new DeckState();

    private Bitmap staticLayer;
    private Bitmap glassLayer;
    private final Paint glassPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF glassDst = new RectF();
    private int drawErrCount;
    private boolean healthyMarked;
    private CanvasGfx gfx;
    private long lastFrame;
    private long lastInvalidate;
    private boolean looping;
    private boolean staticWithCassette;

    private int downBtn = -1;
    private boolean holding;
    private float downX, downY;
    private final int slop;

    private final Runnable holdTrigger = new Runnable() {
        @Override
        public void run() {
            try {
                if (downBtn == DeckRenderer.BTN_REW || downBtn == DeckRenderer.BTN_FF) {
                    holding = true;
                    sfx.startScratch();
                    engine.beginScratch(downBtn == DeckRenderer.BTN_FF);
                }
            } catch (Throwable t) {
                // 长按出错不该带走整个应用
                holding = false;
                try {
                    engine.endScratch();
                } catch (Throwable ignored) {
                }
                if (drawErrCount++ < 2) TapeApp.report(t);
            }
        }
    };

    /**
     * 每一帧：Choreographer 回调里抛出来的异常是没人接的，会直接杀掉进程。
     * 所以整段包住——画不出来最多是画面停住，不能再退出。
     */
    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            try {
                long now = SystemClock.uptimeMillis();
                float dt = lastFrame == 0 ? 0.016f : (now - lastFrame) / 1000f;
                if (dt > 0.2f) dt = 0.2f;          // 卡住过就别让动画跳一大步
                lastFrame = now;
                syncState();
                // 倒带越久越急，沙沙声跟着变响一点（只在这个状态下动它）
                if (state.scratching) {
                    sfx.setScratchIntensity((state.scratchSpeed - 6f) / 42f);
                }
                renderer.update(dt, state);
                updateCassetteLayer();
                // 正常播放没必要 60fps；插带/刮擦仍保持 60fps，动画才不卡
                long interval = state.scratching
                        || (renderer.insertProgress() > 0f && renderer.insertProgress() < 0.999f)
                        ? 16 : 33;
                if (lastInvalidate == 0 || now - lastInvalidate >= interval) {
                    invalidate();
                    lastInvalidate = now;
                }
                if (looping && isAnimating()) {
                    postOnAnimation(this);
                } else if (looping) {
                    // 静止时用慢节拍兜底，保证按键/装载事件能唤醒画面
                    postDelayed(wake, 120);
                }
            } catch (Throwable t) {
                if (drawErrCount++ < 2) TapeApp.report(t);
                if (looping) postDelayed(wake, 300);
            }
        }
    };

    private final Runnable wake = new Runnable() {
        @Override
        public void run() {
            try {
                if (!looping) return;
                syncState();
                renderer.update(0.12f, state);
                invalidate();
                if (isAnimating()) postOnAnimation(frame);
                else postDelayed(this, 120);
            } catch (Throwable t) {
                if (drawErrCount++ < 2) TapeApp.report(t);
                if (looping) postDelayed(this, 400);
            }
        }
    };

    public DeckView(Context ctx, TapeEngine engine, Sfx sfx, Host host) {
        super(ctx);
        this.engine = engine;
        this.sfx = sfx;
        this.host = host;
        this.slop = ViewConfiguration.get(ctx).getScaledTouchSlop() * 2;
        setBackgroundColor(0xFF050506);
        setKeepScreenOn(true);
        // 静态层已经是一张预烤位图，动态层只剩卷盘/按键；这台 MI PAD 上硬件绘制
        // 实测 0 掉帧，且避免了软件层每帧把整屏位图重新上传。若将来在老 Tegra 上崩，
        // 再回退到 LAYER_TYPE_SOFTWARE。
    }

    private boolean isAnimating() {
        return engine.playing || engine.scratching || engine.loading || renderer.isAnimating();
    }

    private void syncState() {
        state.hasTape = engine.hasTape();
        state.playing = engine.playing && !engine.scratching;
        state.scratching = engine.scratching;
        // 快进/倒带撞到带首或带尾时引擎会自己停，但沙沙声是独立音轨；
        // 这里兜底停掉，避免手指还按着时噪音一直循环。
        if (holding && !engine.scratching) {
            sfx.stopScratch();
        }
        // 停在带子开头的"终止态"不算暂停：盘不动、灯半灭、出带感都没有
        state.stopped = state.hasTape && !state.playing && !state.scratching && engine.pos <= 0;
        state.paused = state.hasTape && !state.playing && !state.scratching && !state.stopped;
        state.loading = engine.loading;
        state.scratchForward = engine.scratchForward;
        state.scratchSpeed = engine.scratchSpeed();
        state.progress = engine.progress();
        state.level = engine.level();
        state.quality = engine.quality();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        looping = true;
        lastFrame = 0;
        lastInvalidate = 0;
        postOnAnimation(frame);
    }

    @Override
    protected void onDetachedFromWindow() {
        looping = false;
        removeCallbacks(frame);
        removeCallbacks(wake);
        sfx.stopClicks();
        sfx.stopScratch();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        buildStaticLayer(w, h);
    }

    /** 井玻璃单独一张 1000×620 透明位图，硬件绘制时每帧只做一次纹理混合。 */
    private void ensureGlassLayer() {
        if (glassLayer != null) return;
        try {
            Bitmap b = Bitmap.createBitmap(Theme.VW, Theme.VH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            CanvasGfx g = new CanvasGfx(c, Theme.VW, Theme.VH);
            renderer.drawWellGlassLayer(g);
            glassLayer = b;
        } catch (Throwable t) {
            TapeApp.report(t);
        }
    }

    private void buildStaticLayer(int w, int h) {
        if (staticLayer != null) {
            try {
                staticLayer.recycle();
            } catch (Throwable ignored) {
            }
            staticLayer = null;
        }
        staticWithCassette = false;
        if (w <= 0 || h <= 0) return;
        // 太大就不烤了（这台平板 2048×1536 是可以的，留个上限以防别处用大屏）
        if ((long) w * (long) h > 4200000L) return;
        boolean withCassette = engine.hasTape() && renderer.insertProgress() >= 0.999f;
        try {
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.setHasAlpha(false);
            Canvas c = new Canvas(bmp);
            c.drawColor(0xFF050506);
            Gfx g = new CanvasGfx(c, w, h);
            renderer.drawStatic(g, w, h, withCassette);
            staticLayer = bmp;
            staticWithCassette = withCassette;
        } catch (Throwable t) {
            staticLayer = null;
            TapeApp.report(t);
        }
    }

    /** 带子从“滑入”变成“就位”时，把壳烤进静态层；出带时再烤回无壳。 */
    private void updateCassetteLayer() {
        if (staticLayer == null) return;
        boolean withCassette = engine.hasTape() && renderer.insertProgress() >= 0.999f;
        if (withCassette != staticWithCassette) {
            buildStaticLayer(getWidth(), getHeight());
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        try {
            int vw = getWidth(), vh = getHeight();
            if (vw <= 0 || vh <= 0) return;
            // 静态层是一张烤好的位图，动态层直接画在屏幕上。
            // 两层必须用同一组 (vw, vh)：位图尺寸一旦和当前视图不一致就先重烤，
            // 这样"胶带和红钮整体偏一块"就不可能再发生（以前就是位图是转屏前的旧尺寸）。
            if (staticLayer == null || staticLayer.getWidth() != vw || staticLayer.getHeight() != vh) {
                buildStaticLayer(vw, vh);
            }
            if (staticLayer != null) {
                canvas.drawBitmap(staticLayer, 0, 0, null);
            } else {
                canvas.drawColor(0xFF050506);
            }
            if (gfx == null) gfx = new CanvasGfx(canvas, vw, vh);
            gfx.useCanvas(canvas);
            if (staticLayer == null) renderer.drawStatic(gfx, vw, vh);
            renderer.drawDynamic(gfx, vw, vh, state, false);
            ensureGlassLayer();
            if (glassLayer != null) {
                glassDst.set(0, 0, vw, vh);
                canvas.drawBitmap(glassLayer, null, glassDst, glassPaint);
            }
            if (!healthyMarked) {
                // 真的画出来了一帧：说明这一趟没崩，把"连续崩溃"计数清掉
                healthyMarked = true;
                TapeApp.markHealthy(getContext());
            }
        } catch (Throwable t) {
            // 一帧画不出来不该把整个应用带走；记下来，下次启动能看到
            if (drawErrCount++ < 2) TapeApp.report(t);
        }
    }

    // =====================================================================
    // 触摸：真按键的手感 —— 按下就沉，松开才动作（倒带/快进长按则连续走带）
    // =====================================================================
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        try {
            return handleTouch(e);
        } catch (Throwable t) {
            // 按键处理出错也不能带走整个应用
            if (drawErrCount++ < 2) TapeApp.report(t);
            return true;
        }
    }

    private boolean handleTouch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = x;
                downY = y;
                holding = false;
                int btn = renderer.buttonAt(getWidth(), getHeight(), x, y);
                if (btn < 0) {
                    // 空白处（空机时点磁带井 = 装带）
                    if (!engine.hasTape() && renderer.hit(getWidth(), getHeight(), x, y)) {
                        host.onRequestAddTape();
                        return true;
                    }
                    downBtn = -1;
                    return true;
                }
                downBtn = btn;
                renderer.setPressed(btn, true);
                if (btn == DeckRenderer.BTN_REW || btn == DeckRenderer.BTN_FF) {
                    sfx.nudge();
                } else {
                    sfx.click(btn);
                }
                if (btn == DeckRenderer.BTN_REW || btn == DeckRenderer.BTN_FF) {
                    postDelayed(holdTrigger, HOLD_DELAY);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (downBtn >= 0 && !holding) {
                    if (Math.abs(x - downX) > slop || Math.abs(y - downY) > slop) {
                        // 手指滑走了：取消这颗键
                        cancelPress();
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                // cancelPress() 会把 holding 清掉；先记住是不是长按，
                // 否则松手后会先 endScratch 又补一次 nudge，形成双重 seek。
                boolean wasHolding = holding;
                int btn = downBtn;
                cancelPress();
                if (btn < 0) return true;
                switch (btn) {
                    case DeckRenderer.BTN_REW:
                    case DeckRenderer.BTN_FF:
                        if (!wasHolding) {
                            engine.nudge(btn == DeckRenderer.BTN_FF ? NUDGE_MS : -NUDGE_MS);
                        }
                        break;
                    case DeckRenderer.BTN_PLAY:
                        engine.play();
                        break;
                    case DeckRenderer.BTN_PAUSE:
                        // 沙沙声和咔哒声都是独立 AudioTrack，RadioPlayer 的暂停管不到。
                        sfx.stopClicks();
                        sfx.stopScratch();
                        engine.pause();
                        break;
                    case DeckRenderer.BTN_PLUS:
                        host.onRequestAddTape();
                        break;
                    case DeckRenderer.BTN_MODE:
                        int q = renderer.qualityAt(getWidth(), getHeight(), x, y);
                        if (q >= 0) host.onSelectQuality(q);
                        break;
                case DeckRenderer.BTN_STOP:
                        sfx.stopClicks();
                        sfx.stopScratch();
                        host.onRequestStop();
                        break;
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                cancelPress();
                return true;
            }
        }
        return true;
    }

    private void cancelPress() {
        removeCallbacks(holdTrigger);
        if (holding) {
            sfx.stopClicks();
            sfx.stopScratch();
            engine.endScratch();
            holding = false;
        }
        if (downBtn >= 0) renderer.setPressed(downBtn, false);
        downBtn = -1;
    }

    /** 从别处（比如导入完成）唤一次重绘 */
    public void poke() {
        invalidate();
    }

    public void relayout() {
        buildStaticLayer(getWidth(), getHeight());
        invalidate();
    }
}
