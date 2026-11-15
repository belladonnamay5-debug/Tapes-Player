package com.feiyu.tape.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 一条"带子"的全局时间轴：歌曲与 4 秒空白交替，整条带子连续计时。
 * 倒带 / 快进都作用在这条全局时间上，所以能倒回上一首的中间。
 */
public class TapeModel {

    public static final long GAP_MS = 4000L;

    public static class Track {
        public String name;
        public String artist;
        public String uri;        // content:// 或 file://
        public long durationMs;

        public Track() {
        }

        public Track(String name, String artist, String uri, long durationMs) {
            this.name = name;
            this.artist = artist;
            this.uri = uri;
            this.durationMs = durationMs;
        }
    }

    public static class Segment {
        public int index;          // 在 timeline 中的下标
        public boolean gap;
        public long start;
        public long end;
        public Track track;        // gap 段为 null

        public long duration() {
            return end - start;
        }
    }

    public final List<Track> tracks = new ArrayList<Track>();
    public final List<Segment> timeline = new ArrayList<Segment>();
    public long totalMs;

    public void build(List<Track> src) {
        // 先把入参抄一份：调用方有时传的就是 tracks 本身（MainActivity 从
        // engine.model.tracks 接过来的引用），先 clear 会把源一起清空。
        List<Track> input = new ArrayList<Track>(src);
        tracks.clear();
        timeline.clear();
        long cursor = 0;
        for (Track t : input) {
            if (t.durationMs <= 0) continue;
            tracks.add(t);
            Segment s = new Segment();
            s.index = timeline.size();
            s.gap = false;
            s.start = cursor;
            s.end = cursor + t.durationMs;
            s.track = t;
            timeline.add(s);
            cursor = s.end;

            Segment g = new Segment();
            g.index = timeline.size();
            g.gap = true;
            g.start = cursor;
            g.end = cursor + GAP_MS;
            timeline.add(g);
            cursor = g.end;
        }
        totalMs = cursor;
        // 最后那首歌后面不再留 4 秒空白：带到最后一首唱完就结束，
        // 否则播完还要空转 4 秒才停，听感上像"机器忘了停机"。
        if (!timeline.isEmpty()) {
            Segment last = timeline.get(timeline.size() - 1);
            if (last.gap) {
                timeline.remove(timeline.size() - 1);
                totalMs = last.start;
            }
        }
    }

    public boolean hasTape() {
        return totalMs > 0;
    }

    /** 把全局位置映射到某一段 */
    public Segment locate(long pos) {
        if (timeline.isEmpty()) return null;
        if (pos < 0) pos = 0;
        if (pos > totalMs) pos = totalMs;
        for (int i = 0; i < timeline.size(); i++) {
            Segment s = timeline.get(i);
            if (pos < s.end || i == timeline.size() - 1) return s;
        }
        return timeline.get(timeline.size() - 1);
    }

    public float progress(long pos) {
        if (totalMs <= 0) return 0f;
        float p = pos / (float) totalMs;
        return Math.max(0f, Math.min(1f, p));
    }
}
