package com.feiyu.tape;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * 桌面上的第二个图标：「磁带机 · 极简」。
 *
 * 它的存在只为一件事 —— 万一主界面（那台自绘的机器）在这块平板上怎么都打不开，
 * 这个入口可以绕开全部绘制代码，直接进能按键的极简界面。
 * 里面不做任何绘制，只把标志位竖起来然后把活交给 MainActivity 的极简分支。
 */
public class MiniActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            TapeApp.setMinimalMode(this, true);
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Throwable ignored) {
        }
        finish();
    }
}
