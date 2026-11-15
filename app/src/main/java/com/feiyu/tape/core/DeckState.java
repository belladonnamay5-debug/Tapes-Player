package com.feiyu.tape.core;

/** 播放器交给渲染器的"视觉状态"，渲染器只读它，不反向驱动播放。 */
public class DeckState {

    public boolean hasTape;
    public boolean playing;
    public boolean paused;      // 有带子、停在中途（暂停键亮）
    public boolean loading;     // 正在读取音频
    public boolean scratching;  // 长按倒带/快进中
    public boolean scratchForward;
    /** 长按倒带/快进时的当前倍速（1 = 正常播放速度），按住越久越大，爬到顶就封顶 */
    public float scratchSpeed = 1f;
    public boolean stopped;     // 按了红点：带子在机里，但停回开头
    /** 音质开关：0 = HI-FI，1 = AM，2 = BASS */
    public int quality = Quality.AM;

    /** 0..1 全局进度 */
    public float progress;
    /** 0..1 电平（驱动表头） */
    public float level;
}
