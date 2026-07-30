package com.alexkmbk.androidtinytools;

import android.app.Activity;
import android.provider.Settings;

public class DeviceIdClass {
    private final Activity mContext;

    public DeviceIdClass(Activity mContext) {
        this.mContext = mContext;
    }

    public String getDeviceId() {
        String androidId = Settings.Secure.getString(
                mContext.getContentResolver(),
                Settings.Secure.ANDROID_ID
        );

        return androidId == null ? "" : androidId;
    }
}
