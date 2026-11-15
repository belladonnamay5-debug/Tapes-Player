package com.feiyu.tape;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Toast;
import android.view.Window;
import android.view.WindowManager;

import com.feiyu.tape.core.DeckRenderer;
import com.feiyu.tape.core.Quality;
import com.feiyu.tape.core.TapeModel;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 整台机器就是一个 View：DeckView 自己画一切。
 * Activity 只负责三件事：挑歌、把带子存下来、全屏沉浸。
 */
public class MainActivity extends Activity implements DeckView.Host {

    private static final int REQ_SCAN = 11;
    private static final int REQ_PERM_SCAN = 12;

    private TapeEngine engine;
    private Sfx sfx;
    private DeckView deck;
    private TapeStore store;
    private List<TapeModel.Track> tracks = new ArrayList<TapeModel.Track>();
    private boolean picking;
    private boolean loaded;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Throwable ignored) {
        }
        try {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } catch (Throwable ignored) {
        }
        try {
            // 上次是不是还没画出来就没了？（原生崩溃不经过 Java，只能靠哨兵）
            if (TapeApp.bootCrashedLastTime(this)) {
                TapeApp.noteFatal(this);
            }
            TapeApp.bootStart(this);
            // 连着崩了两次就别再硬画了：先把错误摆出来，让人知道是被什么挡住的
            if (TapeApp.crashStreak(this) >= 2) {
                showSafeMode(null);
                return;
            }
            // 从「极简」图标进来，或者上次是在安全模式里选了极简：直接走不绘制的那条路
            if (TapeApp.minimalMode(this)) {
                bootMinimal();
                return;
            }
            boot();
        } catch (Throwable t) {
            TapeApp.reportFatal(t);
            showSafeMode(t);
        }
        // 调试入口：am start -n com.feiyu.tape/.MainActivity --es tapes /path/a.wav,/path/b.wav
        try {
            String spec = getIntent() == null ? null : getIntent().getStringExtra("tapes");
            if (spec != null && spec.length() > 0) loadDebugTapes(spec);
        } catch (Throwable t) {
            TapeApp.report(t);
        }
        // 调试入口：--es radio 0.3 调整"收音机味"的强度（0..1），只认字符串，避免浮点 extra 的兼容问题
        try {
            String rs = getIntent() == null ? null : getIntent().getStringExtra("radio");
            if (rs != null && rs.length() > 0) {
                RadioPlayer.STRENGTH = Math.max(0f, Math.min(1f, Float.parseFloat(rs.trim())));
            }
        } catch (Throwable t) {
            TapeApp.report(t);
        }
        // 调试入口：--ez dump true 把解码前后各 8 秒 PCM 落到外部目录
        try {
            if (getIntent() != null && getIntent().getBooleanExtra("dump", false)) {
                RadioPlayer.DUMP = true;
            }
        } catch (Throwable t) {
            TapeApp.report(t);
        }
        // 调试入口：--es autoscrub rew|ff 让引擎自己按住倒带键三秒，远处就能观察加速过程
        try {
            String scrub = getIntent() == null ? null : getIntent().getStringExtra("autoscrub");
            if (scrub != null && scrub.length() > 0) runDebugScrub(scrub.startsWith("ff"));
        } catch (Throwable t) {
            TapeApp.report(t);
        }
    }

    private void runDebugScrub(final boolean forward) {
        try {
            android.os.Handler h = new android.os.Handler(getMainLooper());
            h.postDelayed(new Runnable() {
                public void run() {
                    try {
                        engine.beginScratch(forward);
                    } catch (Throwable t) {
                        TapeApp.report(t);
                    }
                    android.os.Handler h2 = new android.os.Handler(getMainLooper());
                    h2.postDelayed(new Runnable() {
                        public void run() {
                            try {
                                engine.endScratch();
                            } catch (Throwable t) {
                                TapeApp.report(t);
                            }
                        }
                    }, 3000L);
                }
            }, 1200L);
        } catch (Throwable t) {
            TapeApp.report(t);
        }
    }

    /** 调试用：直接从文件路径铺一条带子，跳过系统的文件选择器 */
    private void loadDebugTapes(String spec) {
        try {
            List<TapeModel.Track> list = new ArrayList<TapeModel.Track>();
            for (String raw : spec.split(",")) {
                String path = raw.trim();
                if (path.length() == 0) continue;
                long dur = 0;
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                try {
                    mmr.setDataSource(path);
                    String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                    if (d != null) dur = Long.parseLong(d.trim());
                } catch (Throwable ignored) {
                } finally {
                    try {
                        mmr.release();
                    } catch (Throwable ignored) {
                    }
                }
                if (dur <= 0) continue;
                String nm = path.substring(path.lastIndexOf('/') + 1);
                list.add(new TapeModel.Track(nm, "", "file://" + path, dur));
            }
            if (list.isEmpty()) return;
            tracks = list;
            engine.loadTape(list, 0);
            store.save(list, 0);
            loaded = true;
            poke();
        } catch (Throwable t) {
            TapeApp.report(t);
        }
    }

    /**
     * 极简模式：完全不碰自绘台面，只留几个能按的键。
     * 台面在这台平板上画不出来（或者画起来就崩）的时候，至少还能听带子。
     */
    private void bootMinimal() {
        store = new TapeStore(this);
        engine = TapeApp.engine(this);
        if (engine == null) throw new IllegalStateException("引擎没起来");
        sfx = new Sfx(this);
        try {
            engine.setQuality(loadQuality());
            TapeStore.Saved saved = store.load();
            if (engine.hasTape()) {
                // 后台还在走带：别打断它，直接把带子接过来接着画。
                // 复制一份，避免这个列表和 engine.model.tracks 变成同一个对象，
                // 下次 build/clear 时把带子自己清空。
                loaded = true;
                tracks = new ArrayList<TapeModel.Track>(engine.model.tracks);
            } else if (!saved.tracks.isEmpty()) {
                tracks = saved.tracks;
                engine.restoreTape(tracks, saved.pos);
                loaded = true;
            }
        } catch (Throwable t) {
            TapeApp.report(t);
        }

        final android.widget.ScrollView sv = new android.widget.ScrollView(this);
        final android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setBackgroundColor(0xFF050608);
        box.setPadding(56, 56, 56, 56);

        android.widget.TextView head = new android.widget.TextView(this);
        head.setTextColor(0xFFE8C27A);
        head.setTextSize(18);
        head.setText("磁带机 · 极简界面");
        box.addView(head);

        final android.widget.TextView status = new android.widget.TextView(this);
        status.setTextColor(0xFFCFD6DF);
        status.setTextSize(15);
        status.setPadding(0, 18, 0, 24);
        status.setText("…");
        box.addView(status);

        android.widget.Button rew = new android.widget.Button(this);
        rew.setText("倒回 10 秒");
        rew.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tap(new Runnable() {
                    @Override
                    public void run() {
                        engine.nudge(-10000);
                        if (sfx != null) sfx.click(DeckRenderer.BTN_REW);
                    }
                });
            }
        });
        box.addView(rew, fullWidth());

        android.widget.Button play = new android.widget.Button(this);
        play.setText("播放 / 暂停");
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tap(new Runnable() {
                    @Override
                    public void run() {
                        if (engine.playing) engine.pause();
                        else engine.play();
                        if (sfx != null) sfx.click(DeckRenderer.BTN_PLAY);
                    }
                });
            }
        });
        box.addView(play, fullWidth());

        android.widget.Button fwd = new android.widget.Button(this);
        fwd.setText("往前 10 秒");
        fwd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tap(new Runnable() {
                    @Override
                    public void run() {
                        engine.nudge(10000);
                        if (sfx != null) sfx.click(DeckRenderer.BTN_FF);
                    }
                });
            }
        });
        box.addView(fwd, fullWidth());

        android.widget.Button add = new android.widget.Button(this);
        add.setText("选歌 / 续带");
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tap(new Runnable() {
                    @Override
                    public void run() {
                        onRequestAddTape();
                    }
                });
            }
        });
        box.addView(add, fullWidth());

        android.widget.Button stop = new android.widget.Button(this);
        stop.setText("红点 · 整机终止");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tap(new Runnable() {
                    @Override
                    public void run() {
                        onRequestStop();
                    }
                });
            }
        });
        box.addView(stop, fullWidth());

        android.widget.Button back = new android.widget.Button(this);
        back.setText("回到磁带机界面");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    TapeApp.setMinimalMode(MainActivity.this, false);
                    TapeApp.markHealthy(MainActivity.this);
                    recreate();
                } catch (Throwable ignored) {
                }
            }
        });
        box.addView(back, fullWidth());

        sv.addView(box);
        setContentView(sv);
        TapeApp.markAlive(this);

        // 每半秒刷一次状态：只在按钮和走带状态变了才有观感差异
        box.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    status.setText(statusText());
                } catch (Throwable ignored) {
                }
                try {
                    if (!isFinishing()) box.postDelayed(this, 500);
                } catch (Throwable ignored) {
                }
            }
        }, 300);
    }

    private android.widget.LinearLayout.LayoutParams fullWidth() {
        return new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    /** 极简模式下每一次按键都包一层：界面简陋可以，崩不行 */
    private void tap(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            TapeApp.report(t);
        }
    }

    private String statusText() {
        try {
            if (engine == null || !engine.hasTape()) return "空机器 —— 先按「选歌 / 续带」挑几首歌";
            int total = 0, song = 0;
            TapeModel.Segment cur = engine.model.locate(engine.pos);
            for (TapeModel.Segment s : engine.model.timeline) {
                if (s.gap) continue;
                total++;
                if (cur != null && s.index == cur.index) song = total;
            }
            String st = engine.playing ? "带子在走" : (engine.pos <= 0 ? "停在开头" : "停着");
            if (song <= 0) song = 1;
            return st + " · 第 " + song + " / " + total + " 首";
        } catch (Throwable t) {
            return "…";
        }
    }

    /** 正常开机那一套。任何一步出错都会被 onCreate 接住，转到安全模式 */
    private void boot() {
        store = new TapeStore(this);
        engine = TapeApp.engine(this);
        if (engine == null) throw new IllegalStateException("引擎没起来");
        sfx = new Sfx(this);
        engine.setListener(new TapeEngine.Listener() {
            @Override
            public void onTapeChanged() {
            }

            @Override
            public void onPositionJump() {
            }

            @Override
            public void onTapeFinished() {
                // 带到尾：机芯停、落一声盒盖咔哒，再把"在走带"的通知撤掉。
                try {
                    if (sfx != null) sfx.clunk();
                } catch (Throwable ignored) {
                }
                try {
                    if (loaded && store != null) store.save(tracks, engine.pos);
                } catch (Throwable ignored) {
                }
                try {
                    PlaybackService.stop(MainActivity.this);
                } catch (Throwable ignored) {
                }
                poke();
            }
        });
        deck = new DeckView(this, engine, sfx, this);
        setContentView(deck);

        try {
            engine.setQuality(loadQuality());
        } catch (Throwable t) {
            TapeApp.report(t);
        }
        TapeStore.Saved saved = store.load();
        if (engine.hasTape()) {
            // 后台还在走带：别打断它，直接把带子接过来接着画。
            // 复制一份，避免和 engine.model.tracks 共用同一个列表。
            loaded = true;
            tracks = new ArrayList<TapeModel.Track>(engine.model.tracks);
        } else if (!saved.tracks.isEmpty()) {
            tracks = saved.tracks;
            engine.restoreTape(tracks, saved.pos);
            loaded = true;
        }
        goImmersive();
        showLastCrash();
    }

    /**
     * 安全模式：界面画不出来的时候，至少让人看到是为什么。
     * 黑底白字，能滚动，能选"再试一次"或"清空带子重来"。
     */
    private void showSafeMode(final Throwable t) {
        try {
            android.widget.ScrollView sv = new android.widget.ScrollView(this);
            android.widget.LinearLayout box = new android.widget.LinearLayout(this);
            box.setOrientation(android.widget.LinearLayout.VERTICAL);
            box.setBackgroundColor(0xFF06070A);
            box.setPadding(56, 56, 56, 56);

            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setTextColor(0xFFD9DEE6);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setTextSize(13);
            tv.setText(safeModeText(t));
            box.addView(tv);

            android.widget.Button retry = new android.widget.Button(this);
            retry.setText("再画一次试试");
            retry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        TapeApp.markHealthy(MainActivity.this);
                        recreate();
                    } catch (Throwable ignored) {
                    }
                }
            });
            box.addView(retry, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

            android.widget.Button wipe = new android.widget.Button(this);
            wipe.setText("清空带子，重新开一台空机器");
            wipe.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        new TapeStore(MainActivity.this).clear();
                        TapeApp.markHealthy(MainActivity.this);
                        recreate();
                    } catch (Throwable ignored) {
                    }
                }
            });
            box.addView(wipe, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

            // 兜底中的兜底：不回台面了，直接进极简界面听带子
            android.widget.Button mini = new android.widget.Button(this);
            mini.setText("不看台面了，用极简界面听");
            mini.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        TapeApp.setMinimalMode(MainActivity.this, true);
                        TapeApp.markHealthy(MainActivity.this);
                        recreate();
                    } catch (Throwable ignored) {
                    }
                }
            });
            box.addView(mini, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

            sv.addView(box);
            setContentView(sv);
            // 这一屏画出来了，说明进程本身是活的：别再把它算作一次崩溃
            TapeApp.markAlive(this);
        } catch (Throwable fallbackFailed) {
            // 连安全模式都起不来：至少写日志，别什么都不留
            TapeApp.reportFatal(fallbackFailed);
        }
    }

    private String safeModeText(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append("磁带机 · 安全模式\n");
        sb.append("────────────\n");
        sb.append("这台机器上次在这里停住了，为了不让它反复退出去，这次先不画台面。\n\n");
        if (t != null) {
            sb.append("这一次的错误：\n");
            sb.append(t.toString()).append("\n\n");
        }
        String last = null;
        try {
            last = TapeApp.takeCrash(this);
        } catch (Throwable ignored) {
        }
        if (last != null && last.length() > 0) {
            sb.append("上一次的现场：\n");
            sb.append(last.length() > 1800 ? last.substring(0, 1800) + "\n…" : last);
            sb.append("\n");
        }
        sb.append("────────────────\n");
        sb.append("机型：").append(android.os.Build.MANUFACTURER).append(' ')
                .append(android.os.Build.MODEL).append('\n');
        sb.append("安卓：").append(android.os.Build.VERSION.RELEASE)
                .append("（API ").append(android.os.Build.VERSION.SDK_INT).append("）\n");
        sb.append("硬件：").append(android.os.Build.HARDWARE).append('\n');
        sb.append("屏幕：").append(getResources().getDisplayMetrics().widthPixels).append('×')
                .append(getResources().getDisplayMetrics().heightPixels).append('\n');
        sb.append("\n把这一屏拍下来发我，基本就能定位了。\n");
        sb.append("日志文件：Android/data/com.feiyu.tape/files/last_crash.txt\n");
        return sb.toString();
    }

    /** deck 在安全模式下是空的，所有"叫它重画"的地方都走这里 */
    private void poke() {
        try {
            if (deck != null) poke();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 上次如果是崩出去的，把现场摆出来。
     * 老平板没有 logcat，这是唯一能把真实原因带回来的办法：
     * 弹一个对话框（能截图），同时文件留在 Android/data/com.feiyu.tape/files/last_crash.txt。
     */
    private void showLastCrash() {
        String all;
        try {
            all = TapeApp.takeCrash(this);
        } catch (Throwable t) {
            return;
        }
        if (all == null || all.length() == 0) return;

        int nl = all.indexOf('\n');
        String body = nl > 0 ? all.substring(nl + 1) : all;   // 去掉 t= 那行
        int second = body.indexOf('\n');
        if (second > 0) body = body.substring(0, second);
        if (body.length() > 220) body = body.substring(0, 220);

        try {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("上一次是这么退出的")
                    .setMessage(all.length() > 1600 ? all.substring(0, 1600) + "\n…" : all)
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Throwable ignored) {
        }
        try {
            Toast.makeText(this, "上次自动退出：" + body, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 沉浸式全屏：老平板的虚拟键条很碍眼，藏掉
    // ------------------------------------------------------------------
    private void goImmersive() {
        View v = getWindow().getDecorView();
        v.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) goImmersive();
    }

    // ------------------------------------------------------------------
    // DeckView.Host
    // ------------------------------------------------------------------
    @Override
    public void onRequestAddTape() {
        if (picking) return;
        if (Build.VERSION.SDK_INT >= 23 && !hasAudioPermission()) {
            picking = true;
            try {
                requestPermissions(new String[]{audioPermission()}, REQ_PERM_SCAN);
            } catch (Throwable t) {
                picking = false;
                if (sfx != null) sfx.clunk();
            }
            return;
        }
        launchScanner();
    }

    /** 扫描本地音频只需要读权限；Android 13 起拆成了 READ_MEDIA_AUDIO */
    private String audioPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            return checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private void launchScanner() {
        picking = true;   // 防连点开出第二个扫描页；onActivityResult 里统一清掉
        try {
            startActivityForResult(new Intent(this, SongScannerActivity.class), REQ_SCAN);
        } catch (Exception e) {
            picking = false;
            if (sfx != null) sfx.clunk();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_PERM_SCAN) return;
        if (res != null && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            launchScanner();
        } else {
            picking = false;
            if (sfx != null) sfx.clunk();
            Toast.makeText(this, "需要读取音频的权限才能扫描歌曲", Toast.LENGTH_LONG).show();
            poke();
        }
    }

    @Override
    public void onKeyFeedback(boolean longPressArc) {
        // 音效已经在 DeckView 里发了；这里留着给将来做触感震动
    }

    /** 音质开关：点哪一段就切哪一档；当前在放的那首立刻换，位置不丢 */
    @Override
    public void onSelectQuality(int quality) {
        try {
            int q = Quality.clamp(quality);
            if (engine.quality() != q) engine.setQuality(q);
            saveQuality(q);
            try {
                sfx.click(com.feiyu.tape.core.DeckRenderer.BTN_MODE);
            } catch (Throwable ignored) {
            }
            poke();
        } catch (Throwable t) {
            TapeApp.report(t);
        }
    }

    /** 读音质档；2.5 及以前只存了 radio 布尔值，自动迁移成 HI-FI / AM */
    private int loadQuality() {
        android.content.SharedPreferences p =
                getSharedPreferences("tape_prefs", MODE_PRIVATE);
        if (p.contains("quality")) {
            return Quality.clamp(p.getInt("quality", Quality.AM));
        }
        return p.getBoolean("radio", true) ? Quality.AM : Quality.HIFI;
    }

    private void saveQuality(int q) {
        android.content.SharedPreferences.Editor e =
                getSharedPreferences("tape_prefs", MODE_PRIVATE).edit();
        e.putInt("quality", Quality.clamp(q));
        // 保留旧键，万一退回 2.5 也还能读到合理的值
        e.putBoolean("radio", Quality.clamp(q) == Quality.AM);
        e.apply();
    }

    @Override
    public void onRequestStop() {
        engine.stop();
        if (loaded) store.save(tracks, 0);
        if (sfx != null) sfx.clunk();
        PlaybackService.stop(this);
        poke();
    }

    // ------------------------------------------------------------------
    // 扫描完回来：读时长、建带子
    // ------------------------------------------------------------------
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SCAN) return;
        picking = false;
        if (res != RESULT_OK || data == null) {
            poke();
            return;
        }

        final List<Uri> uris = new ArrayList<Uri>();
        try {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                for (int k = 0; k < n; k++) {
                    Uri u = data.getClipData().getItemAt(k).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        } catch (Throwable t) {
            TapeApp.report(t);
        }
        if (uris.isEmpty()) {
            poke();
            return;
        }
        // 关机重开之后还能读到这些歌
        for (Uri u : uris) {
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<TapeModel.Track> built = readTracks(uris);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (built.isEmpty()) {
                            if (sfx != null) sfx.clunk();
                            poke();
                            return;
                        }
                        // 设计：每一次选歌就是换一盘磁带。
                        // 新选的歌整条替换旧带子，从第一首开头重新装带；
                        // 不保留、不混入上一次挑的歌。
                        tracks = built;
                        engine.loadTape(tracks, 0);
                        store.save(tracks, 0);
                        loaded = true;
                        if (sfx != null) sfx.clunk();
                        poke();
                    }
                });
            }
        }).start();
    }

    private List<TapeModel.Track> readTracks(List<Uri> uris) {
        List<TapeModel.Track> out = new ArrayList<TapeModel.Track>();
        try {
            return readTracksInner(uris, out);
        } catch (Throwable t) {
            TapeApp.report(t);
            return out;
        }
    }

    private List<TapeModel.Track> readTracksInner(List<Uri> uris, List<TapeModel.Track> out) {
        for (Uri u : uris) {
            String name = null;
            String artist = "";
            long dur = 0;
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(this, u);
                String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) {
                    try {
                        dur = Long.parseLong(d.trim());
                    } catch (Exception ignored) {
                    }
                }
                String t = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
                if (t != null && t.trim().length() > 0) name = t.trim();
                String a = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
                if (a != null) artist = a.trim();
            } catch (Exception ignored) {
            } finally {
                try {
                    mmr.release();
                } catch (Exception ignored) {
                }
            }
            if (name == null) name = displayName(u);
            if (dur <= 0) continue;          // 读不出时长的文件没法铺到带子上
            out.add(new TapeModel.Track(name, artist, u.toString(), dur));
        }
        return out;
    }

    private String displayName(Uri u) {
        String name = null;
        Cursor c = null;
        try {
            c = getContentResolver().query(u, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (name == null) {
            String s = u.getLastPathSegment();
            name = s == null ? "?" : s;
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------
    @Override
    protected void onResume() {
        super.onResume();
        try {
            // 人回来了，通知栏那行字就该收起来
            PlaybackService.stop(this);
        } catch (Throwable t) {
            TapeApp.report(t);
        }
        poke();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (loaded && store != null) store.save(tracks, engine.pos);
        } catch (Throwable t) {
            TapeApp.report(t);
        }
        try {
            // 走带中退出界面：交给前台服务接着走，别停
            if (engine != null && engine.playing) PlaybackService.start(this);
            else PlaybackService.stop(this);
        } catch (Throwable t) {
            TapeApp.report(t);
        }
        poke();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 引擎是进程级共享的（后台服务还在用它），这里不能 shutdown
        try {
            if (engine != null) engine.setListener(null);
        } catch (Throwable ignored) {
        }
        try {
            if (sfx != null) sfx.release();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        // 没有 stop 键，也没有退出键：按返回就等于"把机器放下"
        try {
            moveTaskToBack(true);
        } catch (Throwable ignored) {
        }
    }
}
