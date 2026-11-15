package com.feiyu.tape;

/**
 * BASS 档：在干净的源采样率上做"重低音"，不降采样、不改变速度。
 *
 * 这条链子的骨架来自 F:\Tryptify-main.zip：
 *
 *   - 低架 / 峰值 biquad 的 RBJ 系数与 Transposed Direct Form II 结构，
 *     照搬 Tryptify-main/app/src/main/cpp/dsp/util/biquad.h；
 *   - 低频预设参考 Tryptify-main/app/src/main/java/tf/monochrome/android/
 *     ui/mixer/fxchain/FxPresets.kt 里的 "Headphone Bass Shelf"
 *     (105 Hz, +6 dB, Q 0.707) 和 "Low Shelf Warmth" (100 Hz, +4 dB)，
 *     这里为磁带机的小喇叭再加重一点：55 Hz +7 dB、115 Hz +4.5 dB；
 *   - 压缩器的软拐点增益计算照搬 Tryptify-main/app/src/main/cpp/dsp/
 *     snapins/compressor.h；
 *   - 末级 brickwall 的 ceiling / release 思路来自 Tryptify-main/app/src/
 *     main/cpp/dsp/snapins/limiter.h，这里做成单声道、无前瞻的轻量版
 *     （磁带机输出是单声道，且切档时不想多出 5 ms 延迟）。
 *
 * 另外加了一层 Tryptify 低频思路里很关键的"低频谐波"：
 * 把 180 Hz 以下抽出来做 tanh 饱和，再滤掉基波，只把谐波混回去。
 * 小喇叭放不出 55 Hz，但能放出 110 / 165 Hz 的谐波，所以低音才"听得见"。
 */
public final class BassDsp {

    private final int rate;

    // ---- EQ ----
    private final Biquad sub = new Biquad();
    private final Biquad punch = new Biquad();
    private final Biquad mud = new Biquad();
    private final Biquad air = new Biquad();

    // ---- 低频谐波 ----
    private final Biquad excLp = new Biquad();
    private final Biquad excHp = new Biquad();
    private static final float EXC_MIX = 0.20f;
    private static final float EXC_DRIVE = 2.4f;

    // ---- 压缩器 ----
    private static final float THRESHOLD_DB = -14f;
    private static final float RATIO = 2.2f;
    private static final float KNEE_DB = 6f;
    private static final float MAKEUP_DB = 3f;
    private final float atk;
    private final float rel;
    private float env;
    private float gainEnvDb;

    // ---- 限幅器 ----
    private static final float CEILING = 0.97f;
    private final float limRel;
    private float limGain = 1f;

    // ---- 20 Hz 隔直 ----
    private final float dcR;
    private float dcX;
    private float dcY;

    private final float preGain;

    public BassDsp(int sampleRate) {
        rate = Math.max(8000, sampleRate);
        preGain = dbToLin(-5f);

        // 低架：55 Hz 的"潜" + 115 Hz 的"拳"，中间 330 Hz 稍微挖掉一点，人声不糊。
        sub.lowShelf(rate, 55f, 0.707f, 7f);
        punch.lowShelf(rate, 115f, 0.707f, 4.5f);
        mud.peaking(rate, 330f, 0.9f, -2f);
        air.peaking(rate, 3200f, 0.8f, 1f);

        excLp.lowPass(rate, 180f, 0.707f);
        excHp.highPass(rate, 80f, 0.707f);

        atk = (float) (1.0 - Math.exp(-1.0 / (0.008 * rate)));
        rel = (float) (1.0 - Math.exp(-1.0 / (0.160 * rate)));
        limRel = (float) (1.0 - Math.exp(-1.0 / (0.120 * rate)));
        dcR = (float) Math.exp(-2.0 * Math.PI * 20.0 / rate);
    }

    /** 处理 len 个单声道采样，结果写进 out；返回写出的采样数（等于 len）。 */
    public int process(float[] in, int len, float[] out) {
        for (int i = 0; i < len; i++) {
            float x = in[i] * preGain;

            // 1) 重低音 EQ
            x = sub.step(x);
            x = punch.step(x);
            x = mud.step(x);
            x = air.step(x);

            // 2) 低频谐波：只把 180 Hz 以下的非线性产物混回去
            float low = excLp.step(x);
            float sat = (float) Math.tanh(low * EXC_DRIVE);
            float harm = excHp.step(sat - low);
            x += harm * EXC_MIX;

            // 3) 压缩器（Tryptify compressor.h 的软拐点曲线）
            float a = Math.abs(x);
            if (a > env) env += (a - env) * atk;
            else env += (a - env) * rel;
            float levelDb = linToDb(env);
            float gcDb = computeGain(levelDb);
            float grDb = gcDb - levelDb;
            if (grDb < gainEnvDb) gainEnvDb += (grDb - gainEnvDb) * atk;
            else gainEnvDb += (grDb - gainEnvDb) * rel;
            x *= dbToLin(gainEnvDb + MAKEUP_DB);

            // 4) 20 Hz 隔直
            float y = x - dcX + dcR * dcY;
            dcX = x;
            dcY = y;
            x = y;

            // 5) 末级限幅：瞬时压下、慢慢放开，最后再硬限一次兜底
            float peak = Math.abs(x);
            float target = peak > CEILING ? CEILING / peak : 1f;
            if (target < limGain) limGain = target;
            else limGain += (target - limGain) * limRel;
            x *= limGain;
            if (x > 1f) x = 1f;
            else if (x < -1f) x = -1f;

            out[i] = x;
        }
        return len;
    }

    /** 跳转后清掉滤波器/包络状态，避免"啪"一声。 */
    public void reset() {
        sub.reset();
        punch.reset();
        mud.reset();
        air.reset();
        excLp.reset();
        excHp.reset();
        env = 0f;
        gainEnvDb = 0f;
        limGain = 1f;
        dcX = 0f;
        dcY = 0f;
    }

    private float computeGain(float inputDb) {
        float t = THRESHOLD_DB;
        float w = KNEE_DB;
        if (w <= 0f || inputDb < t - w * 0.5f) {
            if (inputDb <= t) return inputDb;
            return t + (inputDb - t) / RATIO;
        } else if (inputDb > t + w * 0.5f) {
            return t + (inputDb - t) / RATIO;
        } else {
            float xx = inputDb - t + w * 0.5f;
            return inputDb + ((1f / RATIO) - 1f) * xx * xx / (2f * w);
        }
    }

    private static float dbToLin(float db) {
        return (float) Math.pow(10.0, db * 0.05);
    }

    private static float linToDb(float lin) {
        return lin > 1e-10f ? (float) (20.0 * Math.log10(lin)) : -200f;
    }

    /**
     * RBJ cookbook biquad，Transposed Direct Form II。
     * 系数公式与频率/ Q / 增益的边界处理来自 Tryptify 的 biquad.h。
     */
    static final class Biquad {
        private float b0 = 1f, b1, b2, a1, a2;
        private float z1, z2;

        void reset() {
            z1 = 0f;
            z2 = 0f;
        }

        float step(float x) {
            float v = b0 * x + z1;
            z1 = b1 * x - a1 * v + z2;
            z2 = b2 * x - a2 * v;
            return v;
        }

        void lowShelf(int sr, float freq, float q, float gainDb) {
            double[] c = shelf(sr, freq, q, gainDb, true);
            set(c);
        }

        void highShelf(int sr, float freq, float q, float gainDb) {
            double[] c = shelf(sr, freq, q, gainDb, false);
            set(c);
        }

        void peaking(int sr, float freq, float q, float gainDb) {
            double f = clampFreq(sr, freq);
            double qq = clampQ(q);
            double g = clampGain(gainDb);
            double w = 2.0 * Math.PI * f / sr;
            double cw = Math.cos(w);
            double alpha = Math.sin(w) / (2.0 * qq);
            double A = Math.pow(10.0, g / 40.0);
            double a0 = 1.0 + alpha / A;
            set(new double[]{
                    (1.0 + alpha * A) / a0,
                    (-2.0 * cw) / a0,
                    (1.0 - alpha * A) / a0,
                    (-2.0 * cw) / a0,
                    (1.0 - alpha / A) / a0
            });
        }

        void lowPass(int sr, float freq, float q) {
            double f = clampFreq(sr, freq);
            double qq = clampQ(q);
            double w = 2.0 * Math.PI * f / sr;
            double cw = Math.cos(w);
            double alpha = Math.sin(w) / (2.0 * qq);
            double a0 = 1.0 + alpha;
            set(new double[]{
                    (1.0 - cw) * 0.5 / a0,
                    (1.0 - cw) / a0,
                    (1.0 - cw) * 0.5 / a0,
                    (-2.0 * cw) / a0,
                    (1.0 - alpha) / a0
            });
        }

        void highPass(int sr, float freq, float q) {
            double f = clampFreq(sr, freq);
            double qq = clampQ(q);
            double w = 2.0 * Math.PI * f / sr;
            double cw = Math.cos(w);
            double alpha = Math.sin(w) / (2.0 * qq);
            double a0 = 1.0 + alpha;
            set(new double[]{
                    (1.0 + cw) * 0.5 / a0,
                    -(1.0 + cw) / a0,
                    (1.0 + cw) * 0.5 / a0,
                    (-2.0 * cw) / a0,
                    (1.0 - alpha) / a0
            });
        }

        private static double[] shelf(int sr, float freq, float q, float gainDb, boolean low) {
            double f = clampFreq(sr, freq);
            double qq = Math.min(2.0, clampQ(q));
            double g = clampGain(gainDb);
            double w = 2.0 * Math.PI * f / sr;
            double cw = Math.cos(w);
            double alpha = Math.sin(w) / (2.0 * qq);
            double A = Math.pow(10.0, g / 40.0);
            double sq = 2.0 * Math.sqrt(A) * alpha;
            if (low) {
                double a0 = (A + 1.0) + (A - 1.0) * cw + sq;
                return new double[]{
                        A * ((A + 1.0) - (A - 1.0) * cw + sq) / a0,
                        2.0 * A * ((A - 1.0) - (A + 1.0) * cw) / a0,
                        A * ((A + 1.0) - (A - 1.0) * cw - sq) / a0,
                        -2.0 * ((A - 1.0) + (A + 1.0) * cw) / a0,
                        ((A + 1.0) + (A - 1.0) * cw - sq) / a0
                };
            } else {
                double a0 = (A + 1.0) - (A - 1.0) * cw + sq;
                return new double[]{
                        A * ((A + 1.0) + (A - 1.0) * cw + sq) / a0,
                        -2.0 * A * ((A - 1.0) + (A + 1.0) * cw) / a0,
                        A * ((A + 1.0) + (A - 1.0) * cw - sq) / a0,
                        2.0 * ((A - 1.0) - (A + 1.0) * cw) / a0,
                        ((A + 1.0) - (A - 1.0) * cw - sq) / a0
                };
            }
        }

        private void set(double[] c) {
            b0 = (float) c[0];
            b1 = (float) c[1];
            b2 = (float) c[2];
            a1 = (float) c[3];
            a2 = (float) c[4];
        }

        private static double clampFreq(int sr, float freq) {
            return Math.max(1e-3 * sr, Math.min(0.49 * sr, freq));
        }

        private static double clampQ(float q) {
            return Math.max(0.025, Math.min(200.0, q));
        }

        private static double clampGain(float db) {
            return Math.max(-24.0, Math.min(24.0, db));
        }
    }
}
