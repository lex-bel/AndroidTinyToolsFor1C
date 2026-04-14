package com.alexkmbk.androidtinytools;

import android.app.Activity;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Keep;

@Keep
public class BeepClass implements Runnable
{

    Activity mContext;
    int TONE;
    int durationMs;

    public BeepClass(Activity mContext, int TONE, int durationMs) {
        this.mContext = mContext;
        this.TONE = TONE;
        this.durationMs = durationMs;
    }

     public void run() {
        final ToneGenerator  tg = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        tg.startTone(TONE, durationMs);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            tg.stopTone();
            tg.release();
        }, Math.max(durationMs, 1));
    }

    public void beep()
    {
        mContext.runOnUiThread(this);
    }
}
