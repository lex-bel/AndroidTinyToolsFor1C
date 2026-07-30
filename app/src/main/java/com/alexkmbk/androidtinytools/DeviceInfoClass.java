package com.alexkmbk.androidtinytools;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class DeviceInfoClass {
    private final Activity mContext;

    public DeviceInfoClass(Activity mContext) {
        this.mContext = mContext;
    }

    public String getDeviceInfo() {
        try {
            JSONObject json = new JSONObject();
            json.put("model", Build.MODEL != null ? Build.MODEL : "");
            json.put("manufacturer", Build.MANUFACTURER != null ? Build.MANUFACTURER : "");
            json.put("product", Build.PRODUCT != null ? Build.PRODUCT : "");

            String androidId = Settings.Secure.getString(
                    mContext.getContentResolver(),
                    Settings.Secure.ANDROID_ID
            );
            json.put("device_id", androidId != null ? androidId : "");
            json.put("ip_address", getIpAddress());
            json.put("wifi_rssi", getWifiRssi());
            return json.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private String getIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface iface : interfaces) {
                List<InetAddress> addrs = Collections.list(iface.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    @SuppressWarnings("deprecation")
    private int getWifiRssi() {
        try {
            WifiManager wifiManager = (WifiManager) mContext.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                if (wifiInfo != null) {
                    return wifiInfo.getRssi();
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }
}
