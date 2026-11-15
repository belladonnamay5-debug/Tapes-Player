package com.feiyu.tape.gfx;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.LruCache;

import com.feiyu.tape.core.Gfx;

/** 安卓端 Gfx：把可移植的绘制指令翻译成 Canvas 调用 */
public class CanvasGfx implements Gfx {

    private Canvas c;
    private final int w, h;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final Path clipPath = new Path();
    private final float[] alphaStack = new float[32];
    private int depth;
    private float alpha = 1f;

    /**
     * 渐变对象每帧重建很贵（尤其软件绘制），按几何+颜色做一个小 LRU。
     * 坐标量化到 0.5px，既不会看出偏差，又能让卷盘旋转/电平跳动时命中缓存。
     */
    private final LruCache<GradKey, Shader> gradCache = new LruCache<GradKey, Shader>(192);

    private static final class GradKey {
        final int type; // 0=横向线性，1=纵向线性，2=中心径向，3=偏心径向
        final float x, y, w, h;
        final float cx, cy, r, hx, hy;
        final int c1, c2;

        private GradKey(int type, float x, float y, float w, float h,
                        float cx, float cy, float r, float hx, float hy, int c1, int c2) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.cx = cx;
            this.cy = cy;
            this.r = r;
            this.hx = hx;
            this.hy = hy;
            this.c1 = c1;
            this.c2 = c2;
        }

        static GradKey linear(float x, float y, float w, float h, int c1, int c2, boolean vertical) {
            return new GradKey(vertical ? 1 : 0, q(x), q(y), q(w), q(h),
                    0, 0, 0, 0, 0, c1, c2);
        }

        static GradKey radial(float cx, float cy, float r, float hx, float hy, int c1, int c2) {
            boolean centered = (cx == hx && cy == hy);
            return new GradKey(centered ? 2 : 3,
                    0, 0, 0, 0, q(cx), q(cy), q(r), q(hx), q(hy), c1, c2);
        }

        private static float q(float v) {
            return Math.round(v * 2f) / 2f;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GradKey)) return false;
            GradKey k = (GradKey) o;
            if (type != k.type || c1 != k.c1 || c2 != k.c2) return false;
            if (type <= 1) {
                return x == k.x && y == k.y && w == k.w && h == k.h;
            }
            return cx == k.cx && cy == k.cy && r == k.r && hx == k.hx && hy == k.hy;
        }

        @Override
        public int hashCode() {
            int h = type;
            h = 31 * h + c1;
            h = 31 * h + c2;
            if (type <= 1) {
                h = 31 * h + Float.floatToIntBits(x);
                h = 31 * h + Float.floatToIntBits(y);
                h = 31 * h + Float.floatToIntBits(w);
                h = 31 * h + Float.floatToIntBits(h);
            } else {
                h = 31 * h + Float.floatToIntBits(cx);
                h = 31 * h + Float.floatToIntBits(cy);
                h = 31 * h + Float.floatToIntBits(r);
                h = 31 * h + Float.floatToIntBits(hx);
                h = 31 * h + Float.floatToIntBits(hy);
            }
            return h;
        }
    }

    private static final Typeface BOLD = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface PLAIN = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    public CanvasGfx(Canvas canvas, int w, int h) {
        this.c = canvas;
        this.w = w;
        this.h = h;
        textPaint.setSubpixelText(true);
        textPaint.setLinearText(true);
    }

    /** 静态层缓存在 Bitmap 上，动态层每帧画到屏幕 Canvas：换一个目标继续画 */
    public void useCanvas(Canvas nc) {
        this.c = nc;
        this.depth = 0;
        this.alpha = 1f;
    }

    private Paint p(int color) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setAlpha((int) (((color >>> 24) & 0xFF) * alpha));
        return paint;
    }

    @Override
    public int width() {
        return w;
    }

    @Override
    public int height() {
        return h;
    }

    @Override
    public void save() {
        if (depth < alphaStack.length) alphaStack[depth] = alpha;
        depth++;
        c.save();
    }

    @Override
    public void restore() {
        depth--;
        if (depth >= 0 && depth < alphaStack.length) alpha = alphaStack[depth];
        c.restore();
    }

    @Override
    public void translate(float x, float y) {
        c.translate(x, y);
    }

    @Override
    public void rotate(float deg) {
        c.rotate(deg);
    }

    @Override
    public void scale(float s) {
        c.scale(s, s);
    }

    @Override
    public void clipRoundRect(float x, float y, float w, float h, float r) {
        clipPath.reset();
        rect.set(x, y, x + w, y + h);
        clipPath.addRoundRect(rect, r, r, Path.Direction.CW);
        c.clipPath(clipPath);
    }

    @Override
    public void fillRoundRect(float x, float y, float w, float h, float r, int color) {
        rect.set(x, y, x + w, y + h);
        c.drawRoundRect(rect, r, r, p(color));
    }

    @Override
    public void fillRoundRectGrad(float x, float y, float w, float h, float r, int c1, int c2, boolean vertical) {
        rect.set(x, y, x + w, y + h);
        paint.setShader(makeGrad(x, y, w, h, c1, c2, vertical));
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
        c.drawRoundRect(rect, r, r, paint);
        paint.setShader(null);
    }

    @Override
    public void fillRect(float x, float y, float w, float h, int color) {
        c.drawRect(x, y, x + w, y + h, p(color));
    }

    @Override
    public void fillRectGrad(float x, float y, float w, float h, int c1, int c2, boolean vertical) {
        paint.setShader(makeGrad(x, y, w, h, c1, c2, vertical));
        paint.setAlpha(255);
        paint.setStyle(Paint.Style.FILL);
        c.drawRect(x, y, x + w, y + h, paint);
        paint.setShader(null);
    }

    private Shader makeGrad(float x, float y, float w, float h, int c1, int c2, boolean vertical) {
        c1 = applyAlpha(c1);
        c2 = applyAlpha(c2);
        GradKey key = GradKey.linear(x, y, w, h, c1, c2, vertical);
        Shader shader = gradCache.get(key);
        if (shader == null) {
            shader = vertical
                    ? new LinearGradient(x, y, x, y + h, c1, c2, Shader.TileMode.CLAMP)
                    : new LinearGradient(x, y, x + w, y, c1, c2, Shader.TileMode.CLAMP);
            gradCache.put(key, shader);
        }
        return shader;
    }

    private Shader radialGrad(float cx, float cy, float r, float hx, float hy, int c1, int c2) {
        c1 = applyAlpha(c1);
        c2 = applyAlpha(c2);
        GradKey key = GradKey.radial(cx, cy, r, hx, hy, c1, c2);
        Shader shader = gradCache.get(key);
        if (shader == null) {
            shader = new RadialGradient(hx, hy, r, c1, c2, Shader.TileMode.CLAMP);
            gradCache.put(key, shader);
        }
        return shader;
    }

    private int applyAlpha(int color) {
        int a = (int) (((color >>> 24) & 0xFF) * alpha);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    @Override
    public void fillCircle(float cx, float cy, float r, int color) {
        c.drawCircle(cx, cy, r, p(color));
    }

    @Override
    public void fillCircleRadial(float cx, float cy, float r, int cInner, int cOuter) {
        if (r <= 0) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setShader(radialGrad(cx, cy, r, cx, cy, cInner, cOuter));
        c.drawCircle(cx, cy, r, paint);
        paint.setShader(null);
    }

    @Override
    public void fillCircleRadialAt(float cx, float cy, float r, float hx, float hy, int cInner, int cOuter) {
        if (r <= 0) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        // 渐变中心在 (hx,hy)，圆的形状仍然以 (cx,cy) 为准
        paint.setShader(radialGrad(cx, cy, r, hx, hy, cInner, cOuter));
        c.drawCircle(cx, cy, r, paint);
        paint.setShader(null);
    }

    @Override
    public void strokeCircle(float cx, float cy, float r, float width, int color) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        paint.setAlpha((int) (((color >>> 24) & 0xFF) * alpha));
        c.drawCircle(cx, cy, r, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void strokeConcentric(float cx, float cy, float r0, float r1, int count, float width, int color) {
        if (count <= 0 || r1 <= r0) return;
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        paint.setAlpha((int) (((color >>> 24) & 0xFF) * alpha));
        path.reset();
        float span = r1 - r0;
        for (int i = 0; i < count; i++) {
            path.addCircle(cx, cy, r0 + span * i / count, Path.Direction.CW);
        }
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void line(float x1, float y1, float x2, float y2, float width, int color) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
        paint.setAlpha((int) (((color >>> 24) & 0xFF) * alpha));
        c.drawLine(x1, y1, x2, y2, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void fillPoly(float[] xs, float[] ys, int color) {
        path.reset();
        path.moveTo(xs[0], ys[0]);
        for (int i = 1; i < xs.length; i++) path.lineTo(xs[i], ys[i]);
        path.close();
        c.drawPath(path, p(color));
    }

    @Override
    public void setAlpha(float a) {
        alpha = Math.max(0f, Math.min(1f, a));
    }

    @Override
    public void drawText(String text, float x, float y, float size, int color, boolean bold, boolean centered) {
        textPaint.setTypeface(bold ? BOLD : PLAIN);
        textPaint.setTextSize(size);
        textPaint.setColor(color);
        textPaint.setAlpha((int) (((color >>> 24) & 0xFF) * alpha));
        float dx = centered ? x - textPaint.measureText(text) / 2f : x;
        c.drawText(text, dx, y, textPaint);
    }
}
