package com.feiyu.tape;

import java.util.Random;

/**
 * 收音机音质：把干净的立体声压成一台小喇叭收音机里出来的声音。
 *
 * 和第一版最大的不同是——这一版真的把采样率降下来了。
 * 老式收音机的带宽只有三四个千赫，中波更窄；数字设备上听起来"糊"，
 * 是因为高频被切掉、剩下的采样点又只够撑住那点带宽。所以这条链子是：
 *
 *   1. 抗混叠低通（在源采样率上做）—— 不先切，抽取会把高频折回成刺耳的噪声
 *   2. 抽取到约 11kHz：固定采样率，这是"收音机"最硬的骨相
 *   3. 带通 220Hz ~ 3.2kHz：低频的轰隆与齿音全砍，只留下人声那一截
 *   4. 1.8kHz 顶一点：小喇叭的箱声，听得见"在说话"
 *   5. 压缩器：电台那种被榨干的动态，忽大忽小都被拉平
 *   6. 7 位量化 + 抖动：沙砾感，不是白噪声，是"老"的那种糙
 *   7. 50Hz 交流哼声 + 一点点嘶声：信号弱的时候的杂音
 *
 * 全部 32 位浮点，结果写进调用方给的输出数组。
 */
public class RadioDsp {

    /** 目标采样率：11.025kHz，44.1k 的四分之一，整数抽取不带小数相位 */
    public static final int TARGET_RATE = 11025;

    private final int srcRate;
    private final int outRate;
    private final int decim;

    // ---- 抗混叠（源采样率）----
    private final Biquad aa1 = new Biquad();
    private final Biquad aa2 = new Biquad();
    // ---- 频带（输出采样率）----
    private final Biquad hp = new Biquad();
    private final Biquad pres = new Biquad();
    private final Biquad lp1 = new Biquad();
    private final Biquad lp2 = new Biquad();

    private final Random rnd = new Random(20261113L);

    // ---- 压缩器状态 ----
    private float env;
    private float gain = 1f;
    private final float atk;
    private final float rel;
    private final float thresh;
    private final float ratio;
    private final float makeup;

    // ---- 杂音 ----
    private float humPhase;
    /** 杂音强度：0 = 干净得不像收音机，1 = 信号很弱 */
    private float grit = 0.55f;

    // ---- 抽取相位 ----
    private int phase;

    /** 折中档：比满档松一大截，还留着收音机的骨相 */
    public static final float DEFAULT_STRENGTH = 0.29f;

    private final float strength;

    public RadioDsp(int srcRate) {
        this(srcRate, DEFAULT_STRENGTH);
    }

    /**
     * @param strength 0 = 只是闷一点的老录音，1 = 满档收音机
     */
    public RadioDsp(int srcRate, float strength) {
        this.srcRate = Math.max(8000, srcRate);
        this.strength = Math.max(0f, Math.min(1f, strength));
        float s = this.strength;
        // 采样率现在由外面的重采样器统一决定（32kHz），这里只做频带与失真
        this.decim = 1;
        this.outRate = this.srcRate / this.decim;
        this.thresh = 0.30f - 0.12f * s;
        this.ratio = 2f + 2f * s;
        this.makeup = 2.2f + 1.4f * s;

        // 抗混叠：切在输出奈奎斯特的一半多一点，四阶
        float aaCut = Math.min(outRate * 0.42f, 6000f - 2800f * s);
        aa1.lowPass(this.srcRate, aaCut, 0.5412f);
        aa2.lowPass(this.srcRate, aaCut, 1.3066f);

        // 频带：只把最下面的轰隆和最上面的齿音削掉，不再是"电话线"
        hp.highPass(outRate, 150f - 50f * s, 0.7071f);
        pres.peaking(outRate, 2000f, 1.0f, 4f - 1f * s);
        float cut = 6000f - 2800f * s;
        lp1.lowPass(outRate, cut, 0.5412f);
        lp2.lowPass(outRate, cut, 1.3066f);

        // 压缩器时间常数（换算成每采样的系数）
        atk = (float) Math.exp(-1.0 / (0.015 * outRate));
        rel = (float) Math.exp(-1.0 / (0.200 * outRate));
    }

    public int outRate() {
        return outRate;
    }

    public int decim() {
        return decim;
    }

    public void setGrit(float g) {
        grit = Math.max(0f, Math.min(1f, g));
    }

    /**
     * 处理 len 个输入采样，结果写进 out，返回写出的采样个数（约为 len/decim）。
     * out 至少要能装 len/decim + 8 个。
     */
    public int process(float[] in, int len, float[] out) {
        int o = 0;
        for (int i = 0; i < len; i++) {
            float x = in[i];
            // 1) 抗混叠
            x = aa1.step(x);
            x = aa2.step(x);

            // 2) 抽取：只在相位对上时往下走
            if (phase == 0) {
                // 3) 频带 + 箱声
                x = hp.step(x);
                x = pres.step(x);
                x = lp1.step(x);
                x = lp2.step(x);

                // 4) 压缩器
                float a = Math.abs(x);
                env = a > env ? a : env * rel;
                float over = env / thresh;
                float want = over <= 1f ? 1f : (float) Math.pow(over, -(1f - 1f / ratio));
                if (want < gain) gain += (want - gain) * (1f - atk);
                else gain += (want - gain) * (1f - rel);
                x *= gain * makeup;

                // 5) 软削波：轻轻压一下峰，别把波形压成方波
                x = (float) Math.tanh(x * (0.55f + 0.30f * strength)) * 1.05f;

                // 6) 量化：折中档约 10 位，只有拉到满档才是 7 位的沙砾
                float q = 2048f - 1984f * strength;
                float d = (rnd.nextFloat() - rnd.nextFloat()) / q;
                x = Math.round((x + d) * q) / q;

                // 7) 哼声 + 嘶声
                humPhase += 2f * (float) Math.PI * 50f / outRate;
                if (humPhase > 2f * (float) Math.PI) humPhase -= 2f * (float) Math.PI;
                x += (rnd.nextFloat() * 2f - 1f) * (0.0010f + 0.0015f * strength) * grit;
                x += (float) Math.sin(humPhase) * (0.0006f + 0.0009f * strength) * grit;

                if (x > 1f) x = 1f;
                else if (x < -1f) x = -1f;

                out[o++] = x;
            }
            phase++;
            if (phase >= decim) phase = 0;
        }
        return o;
    }

    /** 二级 biquad（RBJ cookbook） */
    static final class Biquad {
        private float b0 = 1, b1, b2, a1, a2;
        private float z1, z2;

        float step(float x) {
            float v = b0 * x + z1;
            z1 = b1 * x - a1 * v + z2;
            z2 = b2 * x - a2 * v;
            return v;
        }

        void highPass(int sr, float f, float q) {
            float w = (float) (2 * Math.PI * f / sr);
            float cw = (float) Math.cos(w);
            float alpha = (float) (Math.sin(w) / (2 * q));
            float a0 = 1 + alpha;
            b0 = (1 + cw) / 2 / a0;
            b1 = -(1 + cw) / a0;
            b2 = (1 + cw) / 2 / a0;
            a1 = -2 * cw / a0;
            a2 = (1 - alpha) / a0;
        }

        void lowPass(int sr, float f, float q) {
            float w = (float) (2 * Math.PI * f / sr);
            float cw = (float) Math.cos(w);
            float alpha = (float) (Math.sin(w) / (2 * q));
            float a0 = 1 + alpha;
            b0 = (1 - cw) / 2 / a0;
            b1 = (1 - cw) / a0;
            b2 = (1 - cw) / 2 / a0;
            a1 = -2 * cw / a0;
            a2 = (1 - alpha) / a0;
        }

        void peaking(int sr, float f, float q, float gainDb) {
            float A = (float) Math.pow(10, gainDb / 40f);
            float w = (float) (2 * Math.PI * f / sr);
            float cw = (float) Math.cos(w);
            float alpha = (float) (Math.sin(w) / (2 * q));
            float a0 = 1 + alpha / A;
            b0 = (1 + alpha * A) / a0;
            b1 = -2 * cw / a0;
            b2 = (1 - alpha * A) / a0;
            a1 = -2 * cw / a0;
            a2 = (1 - alpha / A) / a0;
        }
    }
}
