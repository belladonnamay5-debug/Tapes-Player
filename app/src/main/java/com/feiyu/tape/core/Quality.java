package com.feiyu.tape.core;

/** 音质档位：从左到右 HI-FI / AM / BASS。 */
public final class Quality {

    public static final int HIFI = 0;
    public static final int AM = 1;
    public static final int BASS = 2;

    private Quality() {
    }

    public static int clamp(int q) {
        if (q < HIFI) return HIFI;
        if (q > BASS) return BASS;
        return q;
    }

    public static String label(int q) {
        switch (clamp(q)) {
            case HIFI:
                return "HI-FI";
            case BASS:
                return "BASS";
            default:
                return "AM";
        }
    }
}
