package com.feiyu.tape.core;

import java.util.Random;

/**
 * 磁带机的全部绘制与动画。纯 Java，只依赖 Gfx 接口，
 * 所以电脑端（Java2D）和安卓端（Canvas）画出来完全一致。
 *
 * 质感来源都是结构件级别的：金属边的倒角、内阴影、拉丝纹、喷砂颗粒、
 * 玻璃反光、按键的侧壁与顶面渐层、螺丝的高光月牙、凹刻字的重影。
 */
public class DeckRenderer {

    public static final int BTN_REW = 0;
    public static final int BTN_PLAY = 1;
    public static final int BTN_PAUSE = 2;
    public static final int BTN_FF = 3;
    public static final int BTN_PLUS = 4;
    public static final int BTN_STOP = 5;   // 红色圆点：整机终止
    public static final int BTN_MODE = 6;   // FEIYU 旁的磨砂音质开关

    private static final int N_BTN = 7;

    /** 五个按键槽位里放的是谁：实体机那一排 REW · STOP · PLAY · PAUSE · F.FWD */
    private static final int[] SLOT_BTN = {BTN_REW, BTN_STOP, BTN_PLAY, BTN_PAUSE, BTN_FF};

    /** 音质开关的滑块：0 = 原声，1 = 收音机 */
    private float modeSlide = 1f;
    private float modeTarget = 1f;
    private final float[] press = new float[N_BTN];
    private final float[] pressTarget = new float[N_BTN];

    private float angleL, angleR;          // 卷轴角度（度）
    private float speedL, speedR;          // 卷轴角速度（度/秒）
    private float insertT = 1f;            // 插带动画 0..1
    private boolean staticShellBaked;      // 静态层里是否已经烤进磁带盒壳
    private float levelSmoothed, meterPeak;
    private float glow = 0.12f, glowTarget = 0.12f;
    private boolean glowInit;

    // ---------- 预生成的表面纹理（固定种子，每次启动一样，不会闪） ----------
    private final float[] brushY, brushAlpha, brushX, brushLen;
    private final float[] noiseX, noiseY, noiseR;
    private final boolean[] noiseLight;

    // 每帧复用：这些只是 Gfx 的临时坐标，没必要每帧 new
    private final float[] meterLefts = {
            Theme.METER_LX, Theme.METER_RX - Theme.METER_W
    };
    private final float[] reelCenters = {Theme.REEL_LX, Theme.REEL_RX};
    private final float[] screwX = {
            Theme.CAS_X + 16, Theme.CAS_X + Theme.CAS_W - 16,
            Theme.CAS_X + 16, Theme.CAS_X + Theme.CAS_W - 16
    };
    private final float[] screwY = {
            Theme.CAS_Y + 16, Theme.CAS_Y + 16,
            Theme.CAS_Y + Theme.CAS_H - 16, Theme.CAS_Y + Theme.CAS_H - 16
    };
    private final float[] reelXs = new float[3];
    private final float[] reelYs = new float[3];
    private final float[] iconXs = new float[3];
    private final float[] iconYs = new float[3];
    private final float[] iconShadowYs = new float[3];

    public DeckRenderer() {
        Random rnd = new Random(20261113L);

        int n = 300;
        brushY = new float[n];
        brushAlpha = new float[n];
        brushX = new float[n];
        brushLen = new float[n];
        for (int i = 0; i < n; i++) {
            brushY[i] = Theme.CH_Y + 4 + rnd.nextFloat() * (Theme.CH_H - 8);
            brushAlpha[i] = 0.010f + rnd.nextFloat() * 0.055f;
            brushX[i] = Theme.CH_X + 8 + rnd.nextFloat() * (Theme.CH_W - 120);
            brushLen[i] = 90 + rnd.nextFloat() * (Theme.CH_W - 160);
        }

        int m = 420;
        noiseX = new float[m];
        noiseY = new float[m];
        noiseR = new float[m];
        noiseLight = new boolean[m];
        for (int i = 0; i < m; i++) {
            noiseX[i] = Theme.CH_X + 3 + rnd.nextFloat() * (Theme.CH_W - 6);
            noiseY[i] = Theme.CH_Y + 3 + rnd.nextFloat() * (Theme.CH_H - 6);
            noiseR[i] = 0.5f + rnd.nextFloat() * 1.0f;
            noiseLight[i] = rnd.nextBoolean();
        }

    }

    // =====================================================================
    //  坐标与命中
    // =====================================================================

    /** 带子插入动画的进度（0=还在左边，1=已经就位），调试用 */
    public float insertProgress() { return insertT; }

    public float scaleFor(float w, float h) {
        return Math.min(w / Theme.VW, h / Theme.VH);
    }

    private void applyTransform(Gfx g, float w, float h) {
        float s = scaleFor(w, h);
        g.translate((w - Theme.VW * s) * 0.5f, (h - Theme.VH * s) * 0.5f);
        g.scale(s);
    }

    private float[] toVirtual(float w, float h, float px, float py) {
        float s = scaleFor(w, h);
        return new float[]{
                (px - (w - Theme.VW * s) * 0.5f) / s,
                (py - (h - Theme.VH * s) * 0.5f) / s
        };
    }

    /** 点是否落在机身上（用来判断"点机器加带子"） */
    public boolean hit(float w, float h, float px, float py) {
        float[] v = toVirtual(w, h, px, py);
        return v[0] >= Theme.CH_X && v[0] <= Theme.CH_X + Theme.CH_W
                && v[1] >= Theme.CH_Y && v[1] <= Theme.CH_Y + Theme.CH_H;
    }

    /** 返回按下的键，-1 表示没按到 */
        /** 返回按下的键，-1 表示没按到 */
    public int buttonAt(float w, float h, float px, float py) {
        float[] v = toVirtual(w, h, px, py);
        float x = v[0], y = v[1];

        // FEIYU 旁边那枚磨砂开关
        if (x >= Theme.MODE_X - 6 && x <= Theme.MODE_X + Theme.MODE_W + 6
                && y >= Theme.MODE_Y - 8 && y <= Theme.MODE_Y + Theme.MODE_H + 8) {
            return BTN_MODE;
        }
        // 「＋」是圆钮，按圆判定
        if (dist(x, y, Theme.PLUS_CX, Theme.PLUS_CY) <= Theme.PLUS_R + 10) return BTN_PLUS;

        for (int s = 0; s < SLOT_BTN.length; s++) {
            float bx = slotX(s);
            if (x >= bx - 6 && x <= bx + Theme.SLOT_W + 6
                    && y >= Theme.KEY_Y - 10 && y <= Theme.KEY_Y + Theme.KEY_H + 10) {
                return SLOT_BTN[s];
            }
        }
        return -1;
    }

    /** 音质开关上具体点到了哪一段：0=HI-FI，1=AM，2=BASS；-1=没点到 */
    public int qualityAt(float w, float h, float px, float py) {
        float[] v = toVirtual(w, h, px, py);
        if (v[1] < Theme.MODE_Y - 8 || v[1] > Theme.MODE_Y + Theme.MODE_H + 8) return -1;
        if (v[0] < Theme.MODE_X || v[0] > Theme.MODE_X + Theme.MODE_W) return -1;
        float seg = Theme.MODE_W / 3f;
        return Quality.clamp((int) ((v[0] - Theme.MODE_X) / seg));
    }

    /** 第 s 个槽位的左边界 */
    public static float slotX(int s) {
        return Theme.SLOT_X0 + s * (Theme.SLOT_W + Theme.SLOT_GAP);
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2, dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // =====================================================================
    //  每帧更新
    // =====================================================================

    public void update(float dt, DeckState st) {
        if (dt <= 0f) dt = 1f / 60f;

        // 带子滑进来
        if (st.hasTape) {
            if (insertT < 1f) insertT = Math.min(1f, insertT + dt * 1.5f);
        } else {
            insertT = 0f;
        }

        // 卷轴：走带时按剩余带量变速；长按倒带时跟着真实倍速转（有上限，不然看着像电风扇）
        float dir = (st.scratching && !st.scratchForward) ? -1f : 1f;
        float boost = st.scratching ? Math.min(6.5f, Math.max(1f, st.scratchSpeed)) : 1f;
        float target = (st.playing || st.scratching) ? 96f * dir * boost : 0f;
        float ease = 1f - (float) Math.pow(0.0015, dt);
        speedL += (target - speedL) * ease;
        speedR += (target - speedR) * ease;
        angleL = (angleL + speedL * dt) % 360f;
        angleR = (angleR + speedR * dt) % 360f;

        // 按键下沉
        float k = 1f - (float) Math.pow(0.0005, dt);
        for (int i = 0; i < N_BTN; i++) press[i] += (pressTarget[i] - press[i]) * k;
        modeTarget = st.quality;
        modeSlide += (modeTarget - modeSlide) * (1f - (float) Math.pow(0.002f, dt));

        // 电平表：起得快、落得慢，峰值点自己往回掉
        float lv = clamp01(st.level);
        levelSmoothed += (lv - levelSmoothed) * (1f - (float) Math.pow(0.0008, dt));
        if (levelSmoothed >= meterPeak) meterPeak = levelSmoothed;
        else meterPeak = Math.max(levelSmoothed, meterPeak - dt * 0.30f);

        // 电源灯与光晕
        glowTarget = st.playing ? 1f : (st.scratching ? 0.75f : (st.paused ? 0.45f : 0.12f));
        if (!glowInit) {
            glow = glowTarget;
            glowInit = true;
        }
        glow += (glowTarget - glow) * (1f - (float) Math.pow(0.01, dt));
    }

    public boolean isAnimating() {
        if (insertT > 0f && insertT < 1f) return true;
        if (Math.abs(speedL) > 0.15f || Math.abs(speedR) > 0.15f) return true;
        if (Math.abs(glow - glowTarget) > 0.01f) return true;
        if (meterPeak > 0.02f) return true;
        if (Math.abs(modeSlide - modeTarget) > 0.01f) return true;
        for (int i = 0; i < N_BTN; i++) {
            if (Math.abs(press[i] - pressTarget[i]) > 0.01f) return true;
        }
        return false;
    }

    public void setPressed(int btn, boolean down) {
        if (btn < 0 || btn >= N_BTN) return;
        pressTarget[btn] = down ? 1f : 0f;
    }

    public void releaseAll() {
        for (int i = 0; i < N_BTN; i++) pressTarget[i] = 0f;
    }

    // =====================================================================
    //  静态层：不动的结构件
    // =====================================================================

    public void drawStatic(Gfx g, float w, float h) {
        drawStatic(g, w, h, false);
    }

    /**
     * @param withCassette 稳态有带时把磁带盒壳一起烤进静态层；
     *                     插带/换带期间传 false，壳由动态层跟着动画走。
     */
    public void drawStatic(Gfx g, float w, float h, boolean withCassette) {
        staticShellBaked = withCassette;
        g.save();
        applyTransform(g, w, h);

        drawBackground(g);
        drawChassis(g);
        drawVents(g);
        drawScrews(g);
        drawBrand(g);
        drawModeBase(g);
        drawLowerPanel(g);
        drawWellFrame(g);
        drawTapePath(g);
        drawMeterFrames(g);
        if (withCassette) drawCassetteShell(g);
        drawKeyBases(g);

        g.restore();
    }

    private void drawBackground(Gfx g) {
        g.fillRectGrad(-40, -40, Theme.VW + 80, Theme.VH + 80, 0xFF23262B, 0xFF040506, true);
        // 顶部一盏顶灯
        g.fillCircleRadial(500, -80, 760, 0x1CFFFFFF, 0x00FFFFFF);
        // 机器底下的一圈反光
        g.fillCircleRadial(500, 596, 640, 0x0CFFFFFF, 0x00000000);
    }

    private void drawChassis(Gfx g) {
        float x = Theme.CH_X, y = Theme.CH_Y, w = Theme.CH_W, h = Theme.CH_H, r = Theme.CH_R;

        // 桌面阴影：贴地的一层实、散开的一层虚
        g.fillRoundRect(x - 10, y + 18, w + 20, h + 12, r + 12, 0x38000000);
        g.fillRoundRect(x - 4, y + 8, w + 8, h + 8, r + 6, 0x70000000);

        // 外圈：先铺一层亮金属，再用机身盖住中间，剩下 1.3px 就是被打磨过的边
        g.fillRoundRect(x, y, w, h, r, 0x73B4BCC8);

        float ix = x + 1.3f, iy = y + 1.3f, iw = w - 2.6f, ih = h - 2.6f, ir = r - 1.2f;
        g.fillRoundRectGrad(ix, iy, iw, ih, ir, Theme.SHELL_TOP, Theme.SHELL_BOT, true);

        g.save();
        g.clipRoundRect(ix, iy, iw, ih, ir);

        // 顶灯照亮的上三分之一
        g.fillRectGrad(ix, iy, iw, 170, 0x22FFFFFF, 0x00FFFFFF, true);
        // 左上斜射的柔光带
        g.save();
        g.translate(430, 300);
        g.rotate(-13);
        g.fillRectGrad(-380, -320, 210, 660, 0x0EFFFFFF, 0x00FFFFFF, false);
        g.fillRectGrad(120, -320, 90, 660, 0x08FFFFFF, 0x00FFFFFF, false);
        g.restore();

        // 横向拉丝：长短交错
        for (int i = 0; i < brushY.length; i++) {
            float x1 = brushX[i];
            float x2 = Math.min(ix + iw - 3, x1 + brushLen[i]);
            if (x2 <= x1) continue;
            g.line(x1, brushY[i], x2, brushY[i], 1f, Theme.alpha(0xFFFFFFFF, brushAlpha[i]));
        }
        // 几丝更亮的，像被砂轮咬出来的
        for (int i = 0; i < brushY.length; i += 13) {
            g.line(brushX[i], brushY[i], brushX[i] + brushLen[i] * 0.55f, brushY[i],
                    1.1f, Theme.alpha(0xFFFFFFFF, brushAlpha[i] * 2.4f));
        }

        // 喷砂颗粒
        for (int i = 0; i < noiseX.length; i++) {
            boolean light = noiseLight[i];
            g.fillCircle(noiseX[i], noiseY[i], noiseR[i],
                    light ? 0x0DFFFFFF : 0x12000000);
        }

        // 四周内阴影（倒角压在面上）
        g.fillRectGrad(ix, iy, iw, 32, 0xB0000000, 0x00000000, true);
        g.fillRectGrad(ix, iy + ih - 36, iw, 36, 0x00000000, 0xB0000000, true);
        g.fillRectGrad(ix, iy, 28, ih, 0x8A000000, 0x00000000, false);
        g.fillRectGrad(ix + iw - 28, iy, 28, ih, 0x00000000, 0x8A000000, false);
        // 桌面反上来的光，压在最下面
        g.fillRectGrad(ix, iy + ih - 96, iw, 44, 0x00000000, 0x14FFFFFF, true);
        g.restore();

        // 顶边高光（只画直边，避开圆角）
        g.line(x + 26, y + 1.5f, x + w - 26, y + 1.5f, 1.5f, 0xAAB4BCC8);
        g.line(x + 1.5f, y + 26, x + 1.5f, y + h - 26, 1.1f, 0x3DFFFFFF);
        g.line(x + 26, y + h - 1.5f, x + w - 26, y + h - 1.5f, 1.6f, 0x8C000000);
    }

    /**
     * 机身左右两侧各一路散热槽。原来把它们放在机身下沿，正好压在 F.FWD 与「＋」上，
     * 现在挪到井口两侧的窄边上，和实体的机器一样在侧面开槽。
     */
    private void drawVents(Gfx g) {
        float[] xs = {Theme.CH_X + 12f, Theme.CH_X + Theme.CH_W - 18f};
        for (int k = 0; k < xs.length; k++) {
            float x = xs[k];
            for (int i = 0; i < 10; i++) {
                float y = Theme.WELL_Y + 24 + i * 26;
                g.fillRoundRect(x, y, 6, 16, 3, 0xFF050607);
                g.line(x + 0.7f, y + 1.6f, x + 0.7f, y + 13.6f, 1f, 0x1AFFFFFF);
            }
        }
    }

    private void drawScrews(Gfx g) {
        float[][] pts = {
                {Theme.CH_X + 22, Theme.CH_Y + 22},
                {Theme.CH_X + Theme.CH_W - 22, Theme.CH_Y + 22},
                {Theme.CH_X + 22, Theme.CH_Y + Theme.CH_H - 22},
                {Theme.CH_X + Theme.CH_W - 22, Theme.CH_Y + Theme.CH_H - 22},
        };
        for (float[] p : pts) {
            // 凹坑
            g.fillCircle(p[0], p[1], 9f, 0x66000000);
            g.fillCircleRadial(p[0], p[1] + 0.6f, 8.5f, 0xFF3A3F47, 0xFF101216);
            // 螺帽
            g.fillCircleRadial(p[0] - 1.2f, p[1] - 1.4f, 6.2f, Theme.SCREW_LIGHT, 0xFF14161A);
            g.strokeCircle(p[0], p[1], 6.2f, 1f, 0x99000000);
            // 一字槽
            g.save();
            g.translate(p[0], p[1]);
            g.rotate(28);
            g.fillRect(-5.2f, -1.1f, 10.4f, 2.2f, 0xFF07080A);
            g.fillRect(-5.2f, 0.9f, 10.4f, 1.1f, 0x33FFFFFF);
            g.restore();
            // 高光月牙
            g.fillCircleRadial(p[0] - 2.4f, p[1] - 2.8f, 3.6f, 0x4DFFFFFF, 0x00FFFFFF);
        }
    }

    private void drawBrand(Gfx g) {
        float x = Theme.WELL_X2;                 // 74：和井口、走带机构、电平表同一条左边线
        engrave(g, "F E I Y U", x, Theme.CH_Y + 60, 21, 0xBFD2D8E0, true, false);
        engrave(g, "CASSETTE DECK   TD-7", x + 1, Theme.CH_Y + 78, 9.5f, Theme.ENGRAVE, false, false);

        // 电源灯座：圆心落在井口右边界上，左右对称
        float lx = Theme.WELL_X2 + Theme.WELL_W2, ly = Theme.CH_Y + 44;
        g.fillCircle(lx, ly, 13f, 0x77000000);
        g.fillCircleRadial(lx, ly, 11.5f, 0xFF1A1D21, 0xFF08090A);
        g.strokeCircle(lx, ly, 11.5f, 1f, 0x33FFFFFF);
        g.fillCircle(lx, ly, 7f, 0xFF050506);
        engrave(g, "POWER", lx, ly + 22, 8f, 0x59484E57, false, true);
    }

    /** 凹刻：先画一层暗的重影，再压一层亮的，像被刀刻下去 */
    private void engrave(Gfx g, String text, float x, float y, float size, int color, boolean bold, boolean centered) {
        g.drawText(text, x, y + 1.2f, size, Theme.alpha(0xFF000000, 0.85f), bold, centered);
        g.drawText(text, x, y, size, color, bold, centered);
    }

    private void drawLowerPanel(Gfx g) {
        // 按键排与机身下沿之间只留一条收边线，不再挂字，免得和键位铭牌抢位置
        float y = Theme.KEY_Y + Theme.KEY_H + 36;
        g.line(Theme.WELL_X2, y, Theme.WELL_X2 + Theme.WELL_W2, y, 1.2f, 0x66000000);
        g.line(Theme.WELL_X2, y + 1.2f, Theme.WELL_X2 + Theme.WELL_W2, y + 1.2f, 1f, 0x1AFFFFFF);
    }

    private void drawWellFrame(Gfx g) {
        float x = Theme.WELL_X, y = Theme.WELL_Y, w = Theme.WELL_W, h = Theme.WELL_H, r = Theme.WELL_R;

        // 井口：三层往里收的倒角
        g.fillRoundRect(x, y, w, h, r, 0xFF12151A);
        g.fillRoundRect(x + 3, y + 3, w - 6, h - 6, r - 2f, 0xFF090B0D);
        g.fillRoundRectGrad(Theme.WELL_X2, Theme.WELL_Y2, Theme.WELL_W2, Theme.WELL_H2, 12,
                0xFF070809, 0xFF040506, true);

        // 井内阴影
        g.save();
        g.clipRoundRect(Theme.WELL_X2, Theme.WELL_Y2, Theme.WELL_W2, Theme.WELL_H2, 12);
        g.fillRectGrad(Theme.WELL_X2, Theme.WELL_Y2, Theme.WELL_W2, 46, 0xD9000000, 0x00000000, true);
        g.fillRectGrad(Theme.WELL_X2, Theme.WELL_Y2, 26, Theme.WELL_H2, 0x99000000, 0x00000000, false);
        g.fillRectGrad(Theme.WELL_X2 + Theme.WELL_W2 - 26, Theme.WELL_Y2, 26, Theme.WELL_H2,
                0x00000000, 0x99000000, false);
        g.fillRectGrad(Theme.WELL_X2, Theme.WELL_Y2 + Theme.WELL_H2 - 30, Theme.WELL_W2, 30,
                0x00000000, 0x1FFFFFFF, true);
        g.restore();

        // 井口外沿的亮边
        g.line(x + 22, y + 0.9f, x + w - 22, y + 0.9f, 1.2f, 0x2EFFFFFF);
        g.line(x + 22, y + h - 0.9f, x + w - 22, y + h - 0.9f, 1.4f, 0x7A000000);
        g.line(x + 0.9f, y + 22, x + 0.9f, y + h - 22, 1.1f, 0x24FFFFFF);
        g.line(x + w - 0.9f, y + 22, x + w - 0.9f, y + h - 22, 1.1f, 0x66000000);
    }

    /** 磁带井下方露出来的走带机构 */
    private void drawTapePath(Gfx g) {
        float y = 406;
        g.fillRect(74, y, 852, 30, 0xFF0A0C0E);
        g.fillRectGrad(74, y, 852, 30, 0xCC000000, 0x33000000, true);
        g.line(74, y + 0.8f, 926, y + 0.8f, 1.2f, 0x1AFFFFFF);

        // 两个导带轮
        for (float rx : new float[]{246, 754}) {
            g.fillCircle(rx, y + 16, 13f, 0xAA000000);
            g.fillCircleRadial(rx - 1f, y + 15, 11.5f, 0xFF9AA2AC, 0xFF23262B);
            g.fillCircle(rx, y + 16, 4.2f, 0xFF08090A);
            g.strokeCircle(rx, y + 16, 11.5f, 1f, 0x66000000);
        }

        // 磁头组
        g.fillRoundRect(468, y + 6, 64, 20, 3, 0xFF15181C);
        g.fillRoundRect(472, y + 8, 56, 15, 2, 0xFF2C3037);
        g.line(474, y + 9.4f, 526, y + 9.4f, 1f, 0x33FFFFFF);
        g.fillRect(496, y + 19, 8, 4, 0xFF9AA2AC);

        // 被拉出来的一小段带子
        g.line(262, y + 16, 470, y + 16, 1.6f, 0xFF1A1410);
        g.line(538, y + 16, 740, y + 16, 1.6f, 0xFF1A1410);
        g.line(262, y + 15.2f, 470, y + 15.2f, 0.8f, 0x33FFFFFF);
    }

    private void drawMeterFrames(Gfx g) {
        for (float x : meterLefts) {
            float w = Theme.METER_W;
            g.fillRoundRect(x - 5, Theme.METER_Y - 8, w + 10, Theme.METER_H + 16, 9, 0xCC000000);
            g.fillRoundRectGrad(x - 4, Theme.METER_Y - 7, w + 8, Theme.METER_H + 14, 8,
                    0xFF0C0E11, 0xFF040506, true);
            // 刻度全画在框内，不再往上压到走带机构那一层
            for (int i = 0; i <= 12; i++) {
                float tx = x + 4 + i * (w - 12) / 12f;
                float len = (i % 3 == 0) ? 4f : 2f;
                g.line(tx, Theme.METER_Y - 6.4f, tx, Theme.METER_Y - 6.4f + len, 1f, 0x30FFFFFF);
            }
            g.line(x - 4, Theme.METER_Y - 7f, x + w + 4, Theme.METER_Y - 7f, 1f, 0x2EFFFFFF);
            g.line(x - 4, Theme.METER_Y + Theme.METER_H + 7f, x + w + 4,
                    Theme.METER_Y + Theme.METER_H + 7f, 1.2f, 0x6B000000);
        }
    }

    private void drawKeyBases(Gfx g) {
        // 五颗走带键共用一套槽位：REW · STOP · PLAY · PAUSE · F.FWD
        for (int s = 0; s < SLOT_BTN.length; s++) {
            float x = slotX(s), y = Theme.KEY_Y, w = Theme.SLOT_W;
            g.fillRoundRect(x - 2, y - 2, w + 4, Theme.KEY_H + 4, 11, 0xCC000000);
            g.fillRoundRectGrad(x, y, w, Theme.KEY_H, 9, 0xFF0A0B0D, 0xFF050506, true);
            g.line(x + 4, y + 0.7f, x + w - 4, y + 0.7f, 2.4f, 0xFF000000);
            g.line(x + 4, y + Theme.KEY_H - 0.7f, x + w - 4, y + Theme.KEY_H - 0.7f,
                    1.1f, 0x2EFFFFFF);
        }

        String[] labels = {"REW", "STOP", "PLAY", "PAUSE", "F.FWD"};
        float ly = Theme.KEY_Y + Theme.KEY_H + 24;
        for (int s = 0; s < SLOT_BTN.length; s++) {
            engrave(g, labels[s], slotX(s) + Theme.SLOT_W / 2f, ly, 9.5f, 0x59484E57, false, true);
        }

        // 第六槽：「＋」圆钮的底座与铭牌
        g.fillCircle(Theme.PLUS_CX, Theme.PLUS_CY + 3, Theme.PLUS_R + 7, 0x77000000);
        g.fillCircle(Theme.PLUS_CX, Theme.PLUS_CY, Theme.PLUS_R + 5, 0xE6000000);
        g.strokeCircle(Theme.PLUS_CX, Theme.PLUS_CY, Theme.PLUS_R + 5, 1.1f, 0x2EFFFFFF);
        engrave(g, "ADD", Theme.PLUS_CX, ly, 9.5f, 0x59484E57, false, true);
    }



    // =====================================================================
    //  动态层
    // =====================================================================

    public void drawDynamic(Gfx g, float w, float h, DeckState st) {
        drawDynamic(g, w, h, st, true);
    }

    /** Android 端把井玻璃单独缓存成位图时传 withGlass=false，玻璃由 DeckView 最后盖上去。 */
    public void drawDynamic(Gfx g, float w, float h, DeckState st, boolean withGlass) {
        g.save();
        applyTransform(g, w, h);

        drawPowerLed(g, st);
        drawMeters(g, st);
        drawMeterGlass(g);
        drawCassette(g, st);
        if (withGlass) drawWellGlass(g);
        drawKeys(g, st);
        drawPlus(g, st);
        drawMode(g, st);

        g.restore();
    }

    /** 把井玻璃画进一张透明位图时用；坐标仍按 1000×620 虚拟画布。 */
    public void drawWellGlassLayer(Gfx g) {
        drawWellGlass(g);
    }


    private void drawPowerLed(Gfx g, DeckState st) {
        float lx = Theme.WELL_X2 + Theme.WELL_W2, ly = Theme.CH_Y + 44;
        float a = st.stopped ? 0.10f : (st.hasTape ? 0.55f + 0.45f * glow : 0.16f);
        if (st.hasTape && !st.stopped) {
            g.fillCircleRadial(lx, ly, 26, Theme.alpha(Theme.AMBER, 0.26f * a), 0x00FFB23C);
        }
        g.fillCircle(lx, ly, 6.6f, Theme.alpha(Theme.AMBER, a));
        g.fillCircleRadial(lx - 1.6f, ly - 1.8f, 3.2f, Theme.alpha(0xFFFFFFFF, 0.75f * a), 0x00FFFFFF);
        g.strokeCircle(lx, ly, 7.6f, 1f, Theme.alpha(0xFF000000, 0.6f));
    }

    private void drawMeters(Gfx g, DeckState st) {
        int segs = 20;
        float segW = 10.05f, gap = 4.4f;
        float level = st.hasTape ? levelSmoothed : 0f;

        for (int ch = 0; ch < 2; ch++) {
            float x0 = meterLefts[ch] + 4;
            // 左右声道略有差异，看起来才像真的在动
            float lv = clamp01(level * (ch == 0 ? 1f : 0.86f + 0.14f * (float) Math.sin(angleL * 0.05f)));
            int lit = (int) Math.round(lv * segs);

            for (int i = 0; i < segs; i++) {
                float x = x0 + i * (segW + gap);
                float t = i / (float) (segs - 1);
                // 响度条统一 #63BBD0：还是三段，但只在明度上做层次，不再换色相
                int on;
                if (t > 0.92f) on = 0xFFC4F0F8;
                else if (t > 0.80f) on = 0xFF8AD6E6;
                else on = 0xFF63BBD0;

                if (i < lit) {
                    g.fillRect(x, Theme.METER_Y, segW, Theme.METER_H, on);
                    g.fillRect(x, Theme.METER_Y, segW, 3.4f, Theme.alpha(0xFFFFFFFF, 0.35f));
                    if (i >= lit - 1) {
                        g.fillCircleRadial(x + segW * 0.5f, Theme.METER_Y + Theme.METER_H * 0.5f,
                                segW * 1.5f, Theme.alpha(on, 0.28f), Theme.alpha(on, 0f));
                    }
                } else {
                    // 熄灭的段：暗到还能看见位置
                    g.fillRect(x, Theme.METER_Y, segW, Theme.METER_H, Theme.alpha(on, 0.10f));
                }
            }

            // 峰值点
            int pk = (int) Math.round(clamp01(meterPeak) * segs);
            if (pk > 0 && st.hasTape) {
                float px = x0 + (pk - 1) * (segW + gap);
                g.fillRect(px, Theme.METER_Y - 2.4f, segW, 2.2f, 0xFFD9F5FC);
            }
        }
    }

    private void drawMeterGlass(Gfx g) {
        for (float x : meterLefts) {
            g.save();
            g.clipRoundRect(x - 4, Theme.METER_Y - 7, Theme.METER_W + 8, Theme.METER_H + 14, 8);
            g.fillRectGrad(x - 4, Theme.METER_Y - 7, Theme.METER_W + 8, 10, 0x38FFFFFF, 0x00FFFFFF, true);
            g.fillRectGrad(x - 4, Theme.METER_Y + Theme.METER_H + 1, Theme.METER_W + 8, 6, 0x00000000, 0x2E000000, true);
            g.restore();
        }
    }

    // ---------------------------------------------------------------- 磁带

    private void drawCassette(Gfx g, DeckState st) {
        float t = insertT;
        // 空机（还没选歌）时不画带子：insertT=0 是"带子完全滑出"的位置，
        // 以前这里仍会把整盘带子画在 -300 的位置，看起来像画面错位。
        if (t <= 0.001f) return;
        float offX = (1f - easeOutBack(t)) * -300f;
        float offY = (1f - t) * 8f;

        g.save();
        g.translate(offX, offY);

        // 稳态时壳已经烤进静态层，这里只画卷盘；插带/换带时才动态画壳
        if (t < 0.999f || !staticShellBaked) {
            drawCassetteShell(g);
        }
        drawCassetteReels(g, st);

        g.restore();
    }

    /** 磁带盒壳：影子、壳、面板、窗、观察窗、索引、螺丝。坐标是盒子的本地坐标。 */
    private void drawCassetteShell(Gfx g) {
        float x = Theme.CAS_X, y = Theme.CAS_Y, w = Theme.CAS_W, h = Theme.CAS_H, r = Theme.CAS_R;

        // 落在井底和井壁上的影子
        g.fillRoundRect(x - 8, y + 16, w + 16, h + 8, r + 8, 0x59000000);
        g.fillRoundRect(x - 3, y + 7, w + 6, h + 5, r + 4, 0x8C000000);

        // 壳
        g.fillRoundRectGrad(x, y, w, h, r, Theme.SHELL_CASSETTE_TOP, Theme.SHELL_CASSETTE_BOT, true);
        g.fillRoundRectGrad(x + 1, y + 1, w - 2, 60, r - 1, 0x1FFFFFFF, 0x00FFFFFF, true);

        // 壳边缘：上亮下暗
        g.line(x + r, y + 1f, x + w - r, y + 1f, 1.4f, 0x4DFFFFFF);
        g.line(x + r, y + h - 1.2f, x + w - r, y + h - 1.2f, 1.6f, 0x7A000000);

        // 面板：整块微微压下去，边缘一圈暗线
        float fx0 = x + 14, fy0 = y + 12, fw = w - 28, fh = h - 24;
        g.fillRoundRectGrad(fx0, fy0, fw, fh, 8, 0x0EFFFFFF, 0x00000000, true);
        g.line(fx0 + 8, fy0 + 0.9f, fx0 + fw - 8, fy0 + 0.9f, 1.3f, 0x59000000);
        g.line(fx0 + 8, fy0 + fh - 0.9f, fx0 + fw - 8, fy0 + fh - 0.9f, 1.1f, 0x1FFFFFFF);
        g.line(fx0 + 1f, fy0 + 8, fx0 + 1f, fy0 + fh - 8, 1f, 0x40000000);
        g.line(fx0 + fw - 1f, fy0 + 8, fx0 + fw - 1f, fy0 + fh - 8, 1f, 0x14FFFFFF);

        // 两个卷盘窗口
        for (float cx : reelCenters) {
            g.fillCircle(cx, Theme.REEL_Y, Theme.PACK_MAX + 9, 0x66000000);
            g.fillCircleRadial(cx, Theme.REEL_Y, Theme.PACK_MAX + 8, 0xFF101215, 0xFF070809);
            g.strokeCircle(cx, Theme.REEL_Y, Theme.PACK_MAX + 8, 1.2f, 0x1FFFFFFF);
        }

        // 中间的观察窗
        g.fillRoundRect(492, y + 60, 16, 150, 8, 0x3D000000);
        g.fillRoundRect(494, y + 62, 12, 146, 6, 0x1AFFFFFF);

        // 底部索引条：一条琥珀细线 + 刻度，像磁带机上的走带标尺
        float ix = x + 44, iw = w - 88, iy = y + h - 27;
        g.fillRect(ix, iy - 1.6f, iw, 1.4f, 0x38000000);
        g.fillRect(ix, iy + 1.6f, iw, 1.7f, Theme.alpha(Theme.AMBER, 0.72f));
        for (int i = 0; i < 26; i++) {
            float tx = ix + i * iw / 25f;
            float len = (i % 5 == 0) ? 6.5f : 3f;
            g.line(tx, iy + 5.2f, tx, iy + 5.2f + len, 0.9f,
                    (i % 5 == 0) ? 0x50FFFFFF : 0x26FFFFFF);
        }

        // 壳上的四颗螺丝
        for (int i = 0; i < screwX.length; i++) {
            float sx = screwX[i], sy = screwY[i];
            g.fillCircle(sx, sy + 1, 5.6f, 0x77000000);
            g.fillCircleRadial(sx, sy, 5.2f, 0xFF8A929C, 0xFF20242A);
            g.fillCircle(sx, sy, 3.4f, 0xFF14171B);
            g.line(sx - 2.3f, sy, sx + 2.3f, sy, 1f, 0xFF060708);
        }
    }

    /** 只画两个会转的卷盘，叠在静态层/动态壳上面。 */
    private void drawCassetteReels(Gfx g, DeckState st) {
        float pl = clamp01(st.progress);
        float rl = Theme.PACK_MAX - pl * (Theme.PACK_MAX - Theme.PACK_MIN);
        float rr = Theme.PACK_MIN + pl * (Theme.PACK_MAX - Theme.PACK_MIN);
        drawReel(g, Theme.REEL_LX, rl, angleL, st);
        drawReel(g, Theme.REEL_RX, rr, angleR, st);
    }

    private void drawReel(Gfx g, float cx, float packR, float angle, DeckState st) {
        float cy = Theme.REEL_Y;
        float r = Math.max(Theme.PACK_MIN, packR);

        // 带子盘：外亮内暗的径向渐变，看起来是一圈圈绕起来的
        // 圆心必须就在 (cx,cy)；高光偏左上只体现在渐变中心上
        g.fillCircleRadialAt(cx, cy, r, cx - r * 0.18f, cy - r * 0.22f,
                Theme.TAPE_PACK_HI, Theme.TAPE_PACK);
        g.strokeCircle(cx, cy, r, 1.2f, 0x66FFFFFF);
        g.strokeCircle(cx, cy, r - 1.6f, 1f, 0x40000000);

        // 盘面的绕带纹（随角度转），一次 Path 描边
        g.strokeConcentric(cx, cy, Theme.HUB_R + 4, r - 1, 13, 0.7f, 0x14000000);

        // 三道高光扇，转速感全靠它
        for (int i = 0; i < 3; i++) {
            float a0 = angle + i * 120f;
            float[] xs = reelXs;
            float[] ys = reelYs;
            for (int k = 0; k < 2; k++) {
                double rad = Math.toRadians(a0 + k * 26);
                xs[k] = cx + (float) Math.cos(rad) * r;
                ys[k] = cy + (float) Math.sin(rad) * r;
            }
            xs[2] = cx + (float) Math.cos(Math.toRadians(a0 + 13)) * (Theme.HUB_R + 6);
            ys[2] = cy + (float) Math.sin(Math.toRadians(a0 + 13)) * (Theme.HUB_R + 6);
            g.fillPoly(xs, ys, 0x12FFFFFF);
        }

        // 轮毂
        g.fillCircle(cx, cy + 1, Theme.HUB_R + 2, 0x59000000);
        g.fillCircleRadialAt(cx, cy, Theme.HUB_R, cx - 3, cy - 4, Theme.HUB_METAL, Theme.HUB_DARK);
        g.strokeCircle(cx, cy, Theme.HUB_R, 1.3f, 0x8C000000);

        // 六个齿（跟着卷轴转）
        for (int i = 0; i < 6; i++) {
            float a = angle + i * 60f;
            g.save();
            g.translate(cx, cy);
            g.rotate(a);
            g.fillRoundRect(Theme.HUB_R * 0.42f, -6.5f, Theme.HUB_R * 0.5f, 13f, 4, 0xFF07080A);
            g.fillRoundRect(Theme.HUB_R * 0.44f, -5.2f, Theme.HUB_R * 0.44f, 4f, 2, 0x2EFFFFFF);
            g.restore();
        }

        // 中心孔
        g.fillCircle(cx, cy, 12.5f, 0xFF07080A);
        g.strokeCircle(cx, cy, 12.5f, 1.2f, 0x40FFFFFF);
        if (st.loading) {
            g.fillCircleRadial(cx, cy, 9f, Theme.alpha(Theme.AMBER, 0.45f), 0x00FFB23C);
        }
    }

    private void drawWellGlass(Gfx g) {
        g.save();
        g.clipRoundRect(Theme.WELL_X2, Theme.WELL_Y2, Theme.WELL_W2, Theme.WELL_H2, 12);

        // 斜向反光：两条宽的一条窄的
        g.save();
        g.translate(500, 260);
        g.rotate(-24);
        g.fillRectGrad(-330, -420, 120, 840, 0x10FFFFFF, 0x00FFFFFF, false);
        g.fillRectGrad(-140, -420, 44, 840, 0x0CFFFFFF, 0x00FFFFFF, false);
        g.fillRectGrad(230, -420, 70, 840, 0x0AFFFFFF, 0x00FFFFFF, false);
        g.restore();

        // 顶部弧光
        g.fillCircleRadial(500, Theme.WELL_Y2 - 40, 520, 0x14FFFFFF, 0x00FFFFFF);
        // 底部压暗
        g.fillRectGrad(Theme.WELL_X2, Theme.WELL_Y2 + Theme.WELL_H2 - 60, Theme.WELL_W2, 60,
                0x00000000, 0x4D000000, true);
        // 一点灰尘，别太干净
        g.fillCircle(182, 214, 1.2f, 0x1AFFFFFF);
        g.fillCircle(714, 158, 1f, 0x14FFFFFF);
        g.fillCircle(806, 336, 1.3f, 0x12FFFFFF);
        g.restore();
    }

    // ---------------------------------------------------------------- 按键

    private void drawKeys(Gfx g, DeckState st) {
        for (int s = 0; s < SLOT_BTN.length; s++) {
            int btn = SLOT_BTN[s];
            float x = slotX(s), w = Theme.SLOT_W;
            float y = Theme.KEY_Y + press[btn] * 3.2f;
            boolean engaged = st.playing ? (btn == BTN_PLAY) : (st.paused && btn == BTN_PAUSE);
            boolean isStop = (btn == BTN_STOP);

            if (isStop) {
                // 实体机上的停止钮是一枚凸起的红钮，压在 REW 与 PLAY 中间那个槽里
                drawStopDot(g, x + w / 2f, Theme.KEY_Y + Theme.KEY_H / 2f, press[btn]);
                continue;
            }

            // 侧壁
            g.fillRoundRect(x, y + 3, w, Theme.KEY_H, 9, 0xFF050607);

            g.fillRoundRectGrad(x, y, w, Theme.KEY_H, 9, Theme.KEY_TOP, Theme.KEY_BOT, true);
            g.fillRoundRectGrad(x + 2, y + 2, w - 4, 26, 7, 0x1FFFFFFF, 0x00FFFFFF, true);
            g.line(x + 8, y + 1f, x + w - 8, y + 1f, 1.3f, 0x4DFFFFFF);
            g.line(x + 8, y + Theme.KEY_H - 1f, x + w - 8, y + Theme.KEY_H - 1f,
                    1.3f, 0x8C000000);

            if (engaged) {
                // 按下时按键缝隙里透出来的琥珀色
                g.fillRoundRect(x + 3, y + Theme.KEY_H - 6, w - 6, 4, 2,
                        Theme.alpha(Theme.AMBER, 0.55f));
                g.fillRoundRect(x + 6, y + 3, w - 12, Theme.KEY_H - 8, 6,
                        Theme.alpha(Theme.AMBER, 0.07f));
            }

            drawIcon(g, btn, x + w / 2f, y + Theme.KEY_H / 2f, engaged);
        }
    }

    /**
     * 红色停止钮：槽底先挖一个圆坑，钮坐在坑里凸出来。
     * 按下时整枚钮往下沉 2.6，白色方块跟着走。
     */
    private void drawStopDot(Gfx g, float cx, float cy, float press) {
        float r = 25.5f;
        float y = cy + press * 2.6f;

        // 槽底的圆坑（比钮大一圈，看着是嵌进去的）
        g.fillCircle(cx, cy + 1.5f, r + 7f, 0xCC000000);
        g.fillCircle(cx, cy + 0.5f, r + 5.2f, 0xFF0A0B0D);
        g.strokeCircle(cx, cy + 0.5f, r + 5.2f, 1f, 0x33FFFFFF);
        // 钮投在坑底的影子
        g.fillCircle(cx, y + 3.6f, r + 0.6f, 0x99000000);
        // 红色玻璃钮：左上受光，右下沉进暗红（圆的中心仍是 (cx,y)，只有渐变偏心）
        g.fillCircleRadialAt(cx, y, r, cx - r * 0.30f, y - r * 0.34f, 0xFFC6402D, 0xFF520C06);
        g.strokeCircle(cx, y, r, 1.5f, 0x66FFD6C9);
        g.strokeCircle(cx, y - 0.7f, r - 1.7f, 0.8f, 0x33000000);
        // 顶上一抹高光
        g.fillCircleRadial(cx - r * 0.32f, y - r * 0.40f, r * 0.62f, 0x55FFFFFF, 0x00FFFFFF);
        // 停止符号：一枚白色小方块
        int face = press > 0.5f ? 0xFFFFEDE8 : 0xFFF7EAE5;
        g.fillRoundRect(cx - 7.6f, y - 7.6f, 15.2f, 15.2f, 3.2f, face);
    }

    private void drawIcon(Gfx g, int btn, float cx, float cy, boolean engaged) {
        int col = engaged ? 0xFFFFD79A : Theme.KEY_ICON;
        int shadow = 0x99000000;

        if (btn == BTN_REW || btn == BTN_FF) {
            float s = (btn == BTN_REW) ? -1f : 1f;
            for (int k = 0; k < 2; k++) {
                float ox = cx + s * (k * 13 - 6.5f);
                iconXs[0] = ox + 8f * s;
                iconXs[1] = ox - 8f * s;
                iconXs[2] = ox - 8f * s;
                iconYs[0] = cy;
                iconYs[1] = cy - 10f;
                iconYs[2] = cy + 10f;
                iconShadowYs[0] = cy + 1.2f;
                iconShadowYs[1] = cy - 8.8f;
                iconShadowYs[2] = cy + 11.2f;
                g.fillPoly(iconXs, iconShadowYs, shadow);
                g.fillPoly(iconXs, iconYs, col);
            }
        } else if (btn == BTN_PLAY) {
            iconXs[0] = cx - 8;
            iconXs[1] = cx + 11;
            iconXs[2] = cx - 8;
            iconYs[0] = cy - 11;
            iconYs[1] = cy;
            iconYs[2] = cy + 11;
            iconShadowYs[0] = cy - 9.8f;
            iconShadowYs[1] = cy + 1.2f;
            iconShadowYs[2] = cy + 12.2f;
            g.fillPoly(iconXs, iconShadowYs, shadow);
            g.fillPoly(iconXs, iconYs, col);
        } else if (btn == BTN_PAUSE) {
            g.fillRect(cx - 9, cy - 10.5f, 6.4f, 21f, shadow);
            g.fillRect(cx + 2.6f, cy - 10.5f, 6.4f, 21f, shadow);
            g.fillRect(cx - 9, cy - 11.5f, 6.4f, 21f, col);
            g.fillRect(cx + 2.6f, cy - 11.5f, 6.4f, 21f, col);
        } else if (btn == BTN_STOP) {
            // 实体机上的停止：一个白方块，压在红键上
            int face = engaged ? 0xFFFFEDE8 : 0xFFF6E2DC;
            g.fillRoundRect(cx - 8.6f, cy - 7.2f, 17.2f, 16.4f, 3.4f, shadow);
            g.fillRoundRect(cx - 8.6f, cy - 8.2f, 17.2f, 16.4f, 3.4f, face);
        }
    }


    private void drawPlus(Gfx g, DeckState st) {
        float p = press[BTN_PLUS];
        float cx = Theme.PLUS_CX, cy = Theme.PLUS_CY + p * 2.4f, r = Theme.PLUS_R;

        g.fillCircle(cx, cy, r, 0xFF0A0B0D);
        g.fillCircleRadialAt(cx, cy, r - 1.2f, cx - r * 0.3f, cy - r * 0.34f, 0xFF31363D, 0xFF121417);
        g.strokeCircle(cx, cy, r, 1.2f, 0x33FFFFFF);
        g.fillCircleRadial(cx - r * 0.3f, cy - r * 0.4f, r * 0.6f, 0x26FFFFFF, 0x00FFFFFF);

        int col = st.hasTape ? 0xFFFFD79A : Theme.KEY_ICON;
        g.fillRect(cx - 9, cy - 1.6f, 18, 3.2f, col);
        g.fillRect(cx - 1.6f, cy - 9, 3.2f, 18, col);
    }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        float p = t - 1f;
        return 1f + c3 * p * p * p + c1 * p * p;
    }

    // =====================================================================
    // FEIYU 旁边那枚磨砂音质开关：HI-FI / AM / BASS，点哪一段就滑到哪一段
    // =====================================================================

    /** 静态层：胶囊的底、边、内部分隔线、磨砂颗粒 */
    private void drawModeBase(Gfx g) {
        float x = Theme.MODE_X, y = Theme.MODE_Y, w = Theme.MODE_W, h = Theme.MODE_H, r = Theme.MODE_R;

        // 底下压一点影子，才像块真的玻璃贴在金属上
        g.fillRoundRect(x - 1.5f, y + 3.5f, w + 3f, h + 2f, r + 2f, 0x40000000);

        // 玻璃本体：上面透一点光，下面沉下去
        g.fillRoundRectGrad(x, y, w, h, r, 0x1EFFFFFF, 0x08000000, true);

        // 边：外圈亮、内圈暗，一圈半透明的"厚度"
        g.fillRoundRect(x - 1f, y - 1f, w + 2f, h + 2f, r + 1f, 0x26FFFFFF);
        g.fillRoundRect(x, y, w, h, r, 0x14000000);
        g.fillRoundRectGrad(x, y, w, h, r, 0x1AFFFFFF, 0x06000000, true);

        // 顶部那道窄高光：磨砂玻璃最典型的特征
        g.fillRoundRect(x + 4f, y + 1.6f, w - 8f, 1.6f, 0.8f, 0x3AFFFFFF);
        g.fillRoundRect(x + 10f, y + 4.6f, w - 20f, 1f, 0.5f, 0x14FFFFFF);

        // 两处分隔刻线：HI-FI | AM | BASS
        float seg = w / 3f;
        g.line(x + seg, y + 6f, x + seg, y + h - 6f, 0.8f, 0x1C000000);
        g.line(x + seg * 2f, y + 6f, x + seg * 2f, y + h - 6f, 0.8f, 0x1C000000);

        // 磨砂颗粒：疏疏落落一层，别太密，密了就像塑料
        java.util.Random rd = new java.util.Random(20261114L);
        for (int i = 0; i < 120; i++) {
            float px = x + 3f + rd.nextFloat() * (w - 6f);
            float py = y + 3f + rd.nextFloat() * (h - 6f);
            float pr = 0.35f + rd.nextFloat() * 0.55f;
            g.fillCircle(px, py, pr, (i & 1) == 0 ? 0x12FFFFFF : 0x0C000000);
        }
    }

    /** 动态层：滑块、两个标签、状态灯 */
    private void drawMode(Gfx g, DeckState st) {
        float x = Theme.MODE_X, y = Theme.MODE_Y, w = Theme.MODE_W, h = Theme.MODE_H, r = Theme.MODE_R;
        float p = press[BTN_MODE];

        float seg = w / 3f;
        float kw = seg - 5f, kh = h - 6f;
        float kx = x + 2.5f + (modeSlide / 2f) * (seg - 0.5f);
        float ky = y + 3f - p * 0.8f;

        // 滑块：比底更白一点，边缘带一点冷光
        g.fillRoundRect(kx + 0.6f, ky + 1.6f, kw, kh, r - 3f, 0x33000000);
        g.fillRoundRectGrad(kx, ky, kw, kh, r - 3f, 0x3EFFFFFF, 0x16FFFFFF, true);
        g.fillRoundRect(kx + 1f, ky + 1f, kw - 2f, kh - 2f, r - 4f, 0x0CFFFFFF);
        g.fillRoundRect(kx + 3f, ky + 1.2f, kw - 6f, 1.2f, 0.6f, 0x40FFFFFF);

        // 三个标签
        float cy = y + h / 2f + 3.6f;
        int q = Quality.clamp(st.quality);
        int cOn = 0xF2FFFFFF;
        int cOff = 0x8AFFFFFF;
        g.drawText("HI-FI", x + seg * 0.5f, cy, 10.5f, q == Quality.HIFI ? cOn : cOff,
                q == Quality.HIFI, true);
        g.drawText("AM", x + seg * 1.5f, cy, 10.5f, q == Quality.AM ? cOn : cOff,
                q == Quality.AM, true);
        g.drawText("BASS", x + seg * 2.5f, cy, 10.5f, q == Quality.BASS ? cOn : cOff,
                q == Quality.BASS, true);

        // 状态灯：原声是青（和电平表一个色），收音机是琥珀，BASS 是暖橙
        float lx = x + w - 9f, ly = y + h / 2f;
        int ledColor = q == Quality.AM ? Theme.AMBER
                : (q == Quality.BASS ? 0xFFFF7A45 : 0xFF63BBD0);
        float a = 0.55f + 0.45f * (q == Quality.HIFI ? 1f : glow);
        g.fillCircleRadial(lx, ly, 7f, Theme.alpha(ledColor, 0.30f * a), 0x00000000);
        g.fillCircle(lx, ly, 2.2f, Theme.alpha(ledColor, a));
        g.fillCircle(lx - 0.6f, ly - 0.7f, 0.9f, Theme.alpha(0xFFFFFFFF, 0.7f * a));
    }
}
