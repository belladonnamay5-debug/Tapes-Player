package com.feiyu.tape.core;

/**
 * 极简绘图接口：只暴露磁带机用得到的原语。
 * 电脑端用 Java2D 实现（出预览图），安卓端用 Canvas 实现，
 * 这样同一份 DeckRenderer 画出来的东西两边完全一致。
 */
public interface Gfx {

    int width();

    int height();

    void save();

    void restore();

    void translate(float x, float y);

    void rotate(float deg);

    void scale(float s);

    void clipRoundRect(float x, float y, float w, float h, float r);

    void fillRoundRect(float x, float y, float w, float h, float r, int color);

    void fillRoundRectGrad(float x, float y, float w, float h, float r, int c1, int c2, boolean vertical);

    void fillRect(float x, float y, float w, float h, int color);

    void fillRectGrad(float x, float y, float w, float h, int c1, int c2, boolean vertical);

    void fillCircle(float cx, float cy, float r, int color);

    void fillCircleRadial(float cx, float cy, float r, int cInner, int cOuter);

    /**
     * 圆画在 (cx,cy)，但渐变的高光中心放在 (hx,hy)。
     * 只想要"左上受光"的立体感时用这个 —— 别再把高光偏移写进圆心，
     * 那会把整个圆挪走（磁带盘和红钮就是这么错位的）。
     */
    void fillCircleRadialAt(float cx, float cy, float r, float hx, float hy, int cInner, int cOuter);

    void strokeCircle(float cx, float cy, float r, float width, int color);

    /** 一次描边多条同心圆，减少 drawCircle 调用。 */
    void strokeConcentric(float cx, float cy, float r0, float r1, int count, float width, int color);

    void line(float x1, float y1, float x2, float y2, float width, int color);

    void fillPoly(float[] xs, float[] ys, int color);

    /** 整体透明度 0..1，作用于之后所有绘制，restore 后失效 */
    void setAlpha(float a);

    void drawText(String text, float x, float y, float size, int color, boolean bold, boolean centered);
}
