package com.feiyu.tape;

import android.content.Context;

import com.feiyu.tape.core.TapeModel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 磁带要"留在机器里"：关掉应用再打开，带子还在、还停在原来的位置。
 * 存成一个极简文本文件，不引第三方库。
 */
public class TapeStore {

    private final File file;

    public TapeStore(Context ctx) {
        this.file = new File(ctx.getFilesDir(), "tape.txt");
    }

    public static class Saved {
        public long pos;
        public final List<TapeModel.Track> tracks = new ArrayList<TapeModel.Track>();
    }

    public void save(List<TapeModel.Track> tracks, long pos) {
        try {
            FileOutputStream fos = new FileOutputStream(file);
            OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
            w.write("v1\n");
            w.write("pos=" + pos + "\n");
            for (TapeModel.Track t : tracks) {
                w.write(esc(t.uri) + "\t" + esc(t.name) + "\t" + esc(t.artist) + "\t" + t.durationMs + "\n");
            }
            w.close();
        } catch (Throwable ignored) {
        }
    }

    public Saved load() {
        Saved out = new Saved();
        if (!file.exists()) return out;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first) {
                    first = false;
                    continue;   // 版本行
                }
                if (line.startsWith("pos=")) {
                    try {
                        out.pos = Long.parseLong(line.substring(4).trim());
                    } catch (Throwable ignored) {
                    }
                    continue;
                }
                String[] f = line.split("\t", -1);
                if (f.length >= 4) {
                    TapeModel.Track t = new TapeModel.Track();
                    t.uri = unesc(f[0]);
                    t.name = unesc(f[1]);
                    t.artist = unesc(f[2]);
                    try {
                        t.durationMs = Long.parseLong(f[3]);
                    } catch (Throwable e) {
                        t.durationMs = 240_000L;
                    }
                    if (t.uri.length() > 0 && t.durationMs > 0) out.tracks.add(t);
                }
            }
            r.close();
        } catch (Throwable ignored) {
        }
        return out;
    }

    public void clear() {
        try {
            file.delete();
        } catch (Throwable ignored) {
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\t", " ").replace("\n", " ").replace("\r", " ");
    }

    private static String unesc(String s) {
        return s.replace("\\\\", "\\");
    }
}
