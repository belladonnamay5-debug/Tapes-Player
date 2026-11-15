package com.feiyu.tape;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 加号键打开的“歌曲扫描”界面。
 *
 * 只做三件事：
 * 1. 用 MediaStore 把本机所有大于 500 KB 的音频列出来；
 * 2. 让人按自己想要的顺序点选（点选顺序就是装带顺序，先点先播）；
 * 3. 点“装带”把选中的 URI 交回 MainActivity，整条替换当前磁带。
 */
public class SongScannerActivity extends Activity {

    private static final long MIN_SIZE = 500L * 1024L;
    private static final int SORT_TITLE = 0;
    private static final int SORT_ADDED = 1;

    private final List<Song> songs = new ArrayList<Song>();
    /** 点选顺序；直接存 Song，换排序时不会丢，也不会打乱先点先播 */
    private final List<Song> picked = new ArrayList<Song>();
    private int sortMode = SORT_TITLE;

    private ListView list;
    private TextView status;
    private TextView empty;
    private Button loadBtn;
    private Button sortBtn;
    private ProgressBar progress;
    private SongAdapter adapter;
    private volatile boolean scanning;
    private volatile boolean dead;

    static final class Song {
        Uri uri;
        String title;
        String artist;
        long size;
        long duration;
        long added;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Throwable ignored) {
        }
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().setBackgroundDrawable(new ColorDrawable(0xFF0E0E0E));
        } catch (Throwable ignored) {
        }
        if (!hasAudioPermission()) {
            Toast.makeText(this, "没有读取音频的权限", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setContentView(buildUi());
        goImmersive();
        scan();
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------
    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E0E0E);
        root.setPadding(dp(24), dp(14), dp(24), dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("歌曲扫描");
        title.setTextColor(0xFFFFC46B);
        title.setTextSize(26);
        title.setSingleLine(true);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        head.addView(progress, new LinearLayout.LayoutParams(dp(26), dp(26)));
        root.addView(head);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        status = new TextView(this);
        status.setTextColor(0xFF9A9A9A);
        status.setTextSize(14);
        status.setText("正在扫描大于 500 KB 的音频…");
        statusRow.addView(status, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        sortBtn = makeButton("排序：标题", false);
        sortBtn.setTextSize(14);
        sortBtn.setPadding(dp(12), 0, dp(12), 0);
        sortBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSort();
            }
        });
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        sblp.leftMargin = dp(8);
        statusRow.addView(sortBtn, sblp);

        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        root.addView(statusRow, slp);

        FrameLayout frame = new FrameLayout(this);
        list = new ListView(this);
        list.setBackgroundColor(0xFF0E0E0E);
        list.setCacheColorHint(0);
        list.setDivider(new ColorDrawable(0xFF262626));
        list.setDividerHeight(dp(1));
        list.setFastScrollEnabled(true);
        adapter = new SongAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                toggle(position);
            }
        });
        frame.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        empty = new TextView(this);
        empty.setText("没有找到大于 500 KB 的音频");
        empty.setTextColor(0xFF777777);
        empty.setTextSize(17);
        empty.setGravity(Gravity.CENTER);
        empty.setVisibility(View.GONE);
        frame.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        list.setEmptyView(empty);

        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        flp.topMargin = dp(12);
        root.addView(frame, flp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);

        Button all = makeButton("全选", false);
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectAll(true);
            }
        });
        bottom.addView(all, buttonLp(false));

        Button none = makeButton("清空", false);
        none.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectAll(false);
            }
        });
        bottom.addView(none, buttonLp(false));

        loadBtn = makeButton("装带（0）", true);
        loadBtn.setEnabled(false);
        loadBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                returnToDeck();
            }
        });
        bottom.addView(loadBtn, buttonLp(true));

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        root.addView(bottom, blp);

        return root;
    }

    private Button makeButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setTextColor(primary ? 0xFF20160A : 0xFFEDEDED);
        b.setBackgroundColor(primary ? 0xFFFFC46B : 0xFF2A2A2A);
        b.setPadding(dp(18), 0, dp(18), 0);
        return b;
    }

    private LinearLayout.LayoutParams buttonLp(boolean weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                weight ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT, dp(46), weight ? 1f : 0f);
        lp.leftMargin = dp(8);
        return lp;
    }

    private final class SongAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return songs.size();
        }

        @Override
        public Object getItem(int position) {
            return songs.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            Holder h;
            if (convertView == null) {
                row = new LinearLayout(SongScannerActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(12), dp(9), dp(12), dp(9));
                row.setMinimumHeight(dp(58));

                TextView order = new TextView(SongScannerActivity.this);
                order.setTextSize(15);
                order.setTextColor(0xFFFFC46B);
                order.setGravity(Gravity.CENTER);
                row.addView(order, new LinearLayout.LayoutParams(dp(28),
                        ViewGroup.LayoutParams.MATCH_PARENT));

                CheckBox cb = new CheckBox(SongScannerActivity.this);
                cb.setClickable(false);
                cb.setFocusable(false);
                cb.setPadding(0, 0, 0, 0);
                row.addView(cb, new LinearLayout.LayoutParams(dp(42),
                        ViewGroup.LayoutParams.WRAP_CONTENT));

                LinearLayout texts = new LinearLayout(SongScannerActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView name = new TextView(SongScannerActivity.this);
                name.setTextSize(19);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                TextView sub = new TextView(SongScannerActivity.this);
                sub.setTextSize(14);
                sub.setSingleLine(true);
                sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
                texts.addView(name, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.topMargin = dp(2);
                texts.addView(sub, tlp);
                row.addView(texts, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                h = new Holder(order, cb, name, sub);
                row.setTag(h);
            } else {
                row = (LinearLayout) convertView;
                h = (Holder) row.getTag();
            }

            Song s = songs.get(position);
            int order = picked.indexOf(s);
            boolean on = order >= 0;
            h.order.setText(on ? String.valueOf(order + 1) : "");
            h.check.setChecked(on);
            h.name.setText(s.title);
            h.name.setTextColor(on ? 0xFFFFC46B : 0xFFF2F2F2);
            h.sub.setText(subtitle(s));
            row.setBackgroundColor(on ? 0xFF2B2113 : 0xFF151515);
            return row;
        }
    }

    private static final class Holder {
        final TextView order;
        final CheckBox check;
        final TextView name;
        final TextView sub;

        Holder(TextView order, CheckBox check, TextView name, TextView sub) {
            this.order = order;
            this.check = check;
            this.name = name;
            this.sub = sub;
        }
    }

    private void toggle(int position) {
        if (position < 0 || position >= songs.size()) return;
        Song s = songs.get(position);
        if (picked.contains(s)) {
            picked.remove(s);
        } else {
            picked.add(s);
        }
        adapter.notifyDataSetChanged();
        updateCount();
    }

    private void selectAll(boolean on) {
        if (songs.isEmpty()) return;
        picked.clear();
        if (on) picked.addAll(songs);
        adapter.notifyDataSetChanged();
        updateCount();
    }

    private void toggleSort() {
        sortMode = (sortMode == SORT_TITLE) ? SORT_ADDED : SORT_TITLE;
        sortBtn.setText(sortMode == SORT_TITLE ? "排序：标题" : "排序：添加时间");
        sortSongs();
        adapter.notifyDataSetChanged();
        if (!songs.isEmpty()) list.setSelection(0);
    }

    private void sortSongs() {
        Collections.sort(songs, new Comparator<Song>() {
            @Override
            public int compare(Song a, Song b) {
                if (sortMode == SORT_ADDED) {
                    if (a.added != b.added) return a.added > b.added ? -1 : 1;
                }
                int c = a.title.compareToIgnoreCase(b.title);
                if (c != 0) return c;
                return a.title.compareTo(b.title);
            }
        });
    }

    private void updateCount() {
        int n = picked.size();
        if (scanning) {
            status.setText("正在扫描大于 500 KB 的音频…");
        } else {
            status.setText("找到 " + songs.size() + " 首 · 已选 " + n + " 首"
                    + (n > 0 ? " · 按点选顺序播放" : ""));
        }
        loadBtn.setText("装带（" + n + "）");
        loadBtn.setEnabled(n > 0);
        loadBtn.setAlpha(n > 0 ? 1f : 0.35f);
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------
    private void scan() {
        scanning = true;
        songs.clear();
        picked.clear();
        adapter.notifyDataSetChanged();
        updateCount();
        progress.setVisibility(View.VISIBLE);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final ArrayList<Song> found = new ArrayList<Song>();
                String error = null;
                Cursor c = null;
                try {
                    String[] projection = new String[]{
                            MediaStore.Audio.Media._ID,
                            MediaStore.Audio.Media.TITLE,
                            MediaStore.Audio.Media.ARTIST,
                            MediaStore.Audio.Media.SIZE,
                            MediaStore.Audio.Media.DURATION,
                            MediaStore.Audio.Media.DATE_ADDED,
                            "_display_name"
                    };
                    String selection = MediaStore.Audio.Media.SIZE + " > ?";
                    String[] args = new String[]{String.valueOf(MIN_SIZE)};
                    String order = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC";
                    c = getContentResolver().query(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            projection, selection, args, order);
                    if (c != null) {
                        int idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                        int titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                        int artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                        int sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
                        int durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                        int addedCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED);
                        int nameCol = c.getColumnIndex("_display_name");
                        while (c.moveToNext()) {
                            long size = c.isNull(sizeCol) ? 0 : c.getLong(sizeCol);
                            if (size <= MIN_SIZE) continue;
                            long id = c.getLong(idCol);
                            String title = titleCol < 0 ? null : c.getString(titleCol);
                            if (TextUtils.isEmpty(title)) {
                                title = nameCol < 0 ? null : c.getString(nameCol);
                            }
                            if (TextUtils.isEmpty(title)) title = "未命名音频";
                            title = stripExt(title.trim());
                            String artist = artistCol < 0 ? null : c.getString(artistCol);
                            if (TextUtils.isEmpty(artist)
                                    || "<unknown>".equalsIgnoreCase(artist.trim())) {
                                artist = "未知艺人";
                            } else {
                                artist = artist.trim();
                            }
                            long duration = durCol < 0 || c.isNull(durCol) ? 0 : c.getLong(durCol);
                            long added = addedCol < 0 || c.isNull(addedCol) ? 0 : c.getLong(addedCol);

                            Song s = new Song();
                            s.uri = ContentUris.withAppendedId(
                                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                            s.title = title;
                            s.artist = artist;
                            s.size = size;
                            s.duration = duration;
                            s.added = added;
                            found.add(s);
                        }
                    }
                } catch (Throwable t) {
                    error = t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : ": " + t.getMessage());
                } finally {
                    if (c != null) {
                        try {
                            c.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }

                final String err = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (dead) return;
                        scanning = false;
                        songs.clear();
                        songs.addAll(found);
                        sortSongs();
                        picked.clear();
                        adapter.notifyDataSetChanged();
                        progress.setVisibility(View.GONE);
                        if (err != null) {
                            status.setText("扫描失败：" + err);
                            Toast.makeText(SongScannerActivity.this, "扫描失败：" + err,
                                    Toast.LENGTH_LONG).show();
                        } else {
                            updateCount();
                            if (found.isEmpty()) {
                                Toast.makeText(SongScannerActivity.this,
                                        "本机没有找到大于 500 KB 的音频", Toast.LENGTH_LONG).show();
                            }
                        }
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------
    // 交回磁带
    // ------------------------------------------------------------------
    private void returnToDeck() {
        ArrayList<Uri> uris = new ArrayList<Uri>();
        for (Song s : picked) {
            if (s != null && s.uri != null) uris.add(s.uri);
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "先点几首歌", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent out = new Intent();
        out.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData clip = ClipData.newRawUri("tapes", uris.get(0));
        for (int i = 1; i < uris.size(); i++) {
            clip.addItem(new ClipData.Item(uris.get(i)));
        }
        out.setClipData(clip);
        setResult(RESULT_OK, out);
        finish();
    }

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            String p = Build.VERSION.SDK_INT >= 33
                    ? Manifest.permission.READ_MEDIA_AUDIO
                    : Manifest.permission.READ_EXTERNAL_STORAGE;
            return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------
    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String subtitle(Song s) {
        return s.artist + " · " + sizeText(s.size) + " · " + durationText(s.duration);
    }

    private static String sizeText(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024L) return String.format(Locale.US, "%d KB", bytes / 1024L);
        return bytes + " B";
    }

    private static String durationText(long ms) {
        if (ms <= 0) return "时长未知";
        long total = ms / 1000L;
        long h = total / 3600L;
        long m = (total % 3600L) / 60L;
        long sec = total % 60L;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%d:%02d", m, sec);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0 && name.length() - dot <= 6) return name.substring(0, dot);
        return name;
    }

    private void goImmersive() {
        try {
            View v = getWindow().getDecorView();
            v.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) goImmersive();
    }

    @Override
    protected void onDestroy() {
        dead = true;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
