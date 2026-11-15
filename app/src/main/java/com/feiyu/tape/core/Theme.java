package com.feiyu.tape.core;

/** 黑色金属配色 + 版式基准（虚拟画布 1000 x 620） */
public final class Theme {

    private Theme() {
    }

    public static final int VW = 1000;
    public static final int VH = 620;

    // ---------- 机身 ----------
    public static final int SHELL_TOP = 0xFF23262C;
    public static final int SHELL_BOT = 0xFF0B0C0E;
    public static final int BEVEL_LIGHT = 0x66A8B0BC;
    public static final int BEVEL_DARK = 0xCC000000;
    public static final int SCREW_DARK = 0xFF15171A;
    public static final int SCREW_LIGHT = 0xFF6C737D;

    // ---------- 玻璃井 ----------
    public static final int WELL_INNER = 0xFF07080A;
    public static final int GLASS_TINT = 0x38000000;

    // ---------- 磁带 ----------
    public static final int SHELL_CASSETTE_TOP = 0xFF2A2E35;
    public static final int SHELL_CASSETTE_BOT = 0xFF14171B;
    public static final int TAPE_PACK = 0xFF2B2119;
    public static final int TAPE_PACK_HI = 0xFF4A3A2B;
    public static final int HUB_METAL = 0xFFB9C0CA;
    public static final int HUB_DARK = 0xFF3A3F47;
    public static final int LABEL = 0xFFE9DCC0;

    // ---------- 灯 ----------
    public static final int AMBER = 0xFFFFB23C;
    public static final int AMBER_DIM = 0xFF4A3313;
    public static final int AMBER_GLOW = 0x66FF8A00;
    public static final int RED = 0xFFFF5A44;

    // ---------- 按键 ----------
    public static final int KEY_TOP = 0xFF34383F;
    public static final int KEY_BOT = 0xFF1B1E22;
    public static final int KEY_ICON = 0xFFB9C0CA;
    public static final int KEY_ICON_DIM = 0x66B9C0CA;

    public static final int ENGRAVE = 0xFF6A707A;
    public static final int ENGRAVE_SHADOW = 0x99000000;

    // ---------- 版式 ----------
    public static final float CH_X = 26, CH_Y = 22, CH_W = 948, CH_H = 576, CH_R = 26;

    public static final float WELL_X = 64, WELL_Y = 96, WELL_W = 872, WELL_H = 330, WELL_R = 18;
    public static final float WELL_X2 = 74, WELL_Y2 = 106, WELL_W2 = 852, WELL_H2 = 310;

    public static final float CAS_X = 100, CAS_Y = 118, CAS_W = 800, CAS_H = 286, CAS_R = 16;
    public static final float REEL_Y = 261;
    public static final float REEL_LX = 330, REEL_RX = 670;
    public static final float HUB_R = 44;
    public static final float PACK_MAX = 116, PACK_MIN = 52;

    /**
     * 走带键排成一条等宽的槽位：REW / STOP / PLAY / PAUSE / F.FWD / ＋
     * 总宽 6*124 + 5*12 = 804，从 98 排到 902，左右各留 72，机身里居中对称。
     */
    public static final float KEY_Y = 486, KEY_H = 66;
    public static final float SLOT_W = 124, SLOT_GAP = 12;
    public static final int SLOT_COUNT = 6;
    public static final float SLOT_X0 = 98;

    /** 第 6 个槽里的「＋」圆钮，圆心正好在这个槽的中线上 */
    public static final float PLUS_CX = 840, PLUS_CY = 519, PLUS_R = 26;

    /** FEIYU 字旁边那枚磨砂音质开关：胶囊形，三段 HI-FI / AM / BASS */
    public static final float MODE_X = 246f;
    public static final float MODE_Y = 62f;
    public static final float MODE_W = 198f;
    public static final float MODE_H = 34f;
    public static final float MODE_R = 17f;

    public static final float METER_Y = 448, METER_H = 16;
    public static final float METER_LX = 74, METER_RX = 926;
    public static final float METER_W = 286;

    public static int alpha(int color, float a) {
        int A = (int) Math.max(0, Math.min(255, ((color >>> 24) & 0xFF) * a));
        return (A << 24) | (color & 0x00FFFFFF);
    }

    public static int lerpColor(int c1, int c2, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (((c1 >>> 24) & 0xFF) + (((c2 >>> 24) & 0xFF) - ((c1 >>> 24) & 0xFF)) * t);
        int r = (int) (((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t);
        int g = (int) (((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t);
        int b = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
