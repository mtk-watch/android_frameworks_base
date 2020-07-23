/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mediatek.server.display;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import android.widget.Toast;
import android.os.SystemProperties;

import com.android.server.display.WifiDisplayController;
import com.mediatek.server.powerhal.PowerHalManager;
import com.mediatek.server.MtkSystemServiceFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;


/**
 * This class for extend aosp wfd which mtk will add some feature or modification.
 */
public class MtkWifiDisplayController {
    private static final String TAG = "MtkWifiDisplayController";
    private static boolean DEBUG = true;

    /// GO Intent.
    private static final String goIntent = SystemProperties.get(
        "wfd.source.go_intent",
        String.valueOf(WifiP2pConfig.MAX_GROUP_OWNER_INTENT - 1));

    /// M: MTK Power: FPSGO Mechanism
    private PowerHalManager mPowerHalManager =
                    MtkSystemServiceFactory.getInstance().makePowerHalManager();

    // Initialize in config.xml
    // Resolution part.
    private int WFDCONTROLLER_DISPLAY_RESOLUTION;
    private int mResolution;
    private int mPrevResolution;

    // Power saving part.
    private int WFDCONTROLLER_DISPLAY_POWER_SAVING_OPTION;
    private int WFDCONTROLLER_DISPLAY_POWER_SAVING_DELAY;

    private static final int RECONNECT_RETRY_DELAY_MILLIS = 1000;
    private static final int RESCAN_RETRY_DELAY_MILLIS = 2000;
    private static final int CONNECTION_TIMEOUT_SECONDS = 30;

    private int mReConnection_Timeout_Remain_Seconds;
    private WifiP2pDevice mReConnectDevice;

    private final Context mContext;
    private final Handler mHandler;
    private WifiDisplayController mController;

    // for HDMI/WFD exclude
    private Object mHdmiManager;
    private static final String HDMI_MANAGER_CLASS = "com.mediatek.hdmi.HdmiNative";
    private static final String HDMI_ENABLE = "persist.vendor.sys.hdmi_hidl.enable";

    public MtkWifiDisplayController(       Context context,
        Handler handler,
        WifiDisplayController controller) {

        mContext = context;
        mHandler = handler;
        mController = controller;

        mHdmiManager = getHdmiService();

        registerEMObserver();

        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        context.registerReceiver(mWifiReceiver, intentFilter, null, mHandler);
    }

    public WifiP2pConfig overWriteConfig(WifiP2pConfig oldConfig) {
        WifiP2pConfig config = new WifiP2pConfig(oldConfig);

        Slog.i(TAG, "oldConfig:" + oldConfig);

        config.groupOwnerIntent = Integer.valueOf(goIntent);

        // Slog.i(TAG, "Source go_intent:" + config.groupOwnerIntent);

        // The IOT devices only support connect and doesn't support invite.
        // Use temporary group to do p2p connection instead of persistent way
        // to avoid later connection through invitation.
        if (mController.mConnectingDevice.deviceName.contains("BRAVIA")) {
            Slog.i(TAG, "Force temporary group");
            config.netId = WifiP2pGroup.TEMPORARY_NET_ID;
        }

        Slog.i(TAG, "config:" + config);

        // Prevent wifi scan to improve performance
        stopWifiScan(true);

        return config;
    }

    private boolean isForce720p() {
        String sPlatform = SystemProperties.get(
            "ro.vendor.mediatek.platform", "");

        switch (sPlatform) {
            case "MT6763":
            case "MT6765":
                Slog.d(TAG, "Platform (Force 720p): " + sPlatform);
                return true;

            default:
                Slog.d(TAG, "Platform: " + sPlatform);
                return false;
        }
    }

    public void setWFD(boolean enable) {

        Slog.d(TAG, "setWFD(), enable: " + enable);

        mPowerHalManager.setWFD(enable);

        stopWifiScan(enable);
    }

    private int getResolutionIndex(int settingValue) {
        switch (settingValue) {
            case 0:
            case 3:
                return 5; // 720p/30fps
            case 1:
            case 2:
                return 7;  // 1080p/30fps
            default:
                return 5;  // 720p/30fps
        }
    }

    private static String describeWifiP2pDevice(WifiP2pDevice device) {
        return device != null ? device.toString().replace('\n', ',') : "null";
    }

    private final Runnable mReConnect = new Runnable() {
        @Override
        public void run() {
            // Slog.d(TAG, "mReConnect, run()");
            for (WifiP2pDevice device : mController.mAvailableWifiDisplayPeers) {
                if (DEBUG) {
                    Slog.d(TAG, "\t" + describeWifiP2pDevice(device));
                }

                if (device.deviceAddress.equals(mReConnectDevice.deviceAddress)) {
                    Slog.i(TAG, "connect() in mReConnect. Set mReConnecting as true");
                    mReConnectDevice = null;
                    ///mController.connect(device);
                    mController.requestConnect(device.deviceAddress);
                    return;
                }
            }

            mReConnection_Timeout_Remain_Seconds = mReConnection_Timeout_Remain_Seconds -
                (RECONNECT_RETRY_DELAY_MILLIS / 1000);
            if (mReConnection_Timeout_Remain_Seconds > 0) {
                // check scan result per RECONNECT_RETRY_DELAY_MILLIS ms
                Slog.i(TAG, "post mReconnect, s:" + mReConnection_Timeout_Remain_Seconds);
                mHandler.postDelayed(mReConnect, RECONNECT_RETRY_DELAY_MILLIS);
           } else {
                Slog.e(TAG, "reconnect timeout!");
                Toast.makeText(mContext, getMtkStringResourceId("wifi_display_disconnected")
                    , Toast.LENGTH_SHORT).show();
                mReConnectDevice = null;
                mReConnection_Timeout_Remain_Seconds = 0;
                mHandler.removeCallbacks(mReConnect);
                return;
            }
        }
    };

    private void handleResolutionChange() {
        int r;
        boolean doNotRemind = true;

        r = Settings.Global.getInt(
                mContext.getContentResolver(),
                getMtkSettingsExtGlobalSetting("WIFI_DISPLAY_RESOLUTION"), 0);

        if (r == mResolution) {
            return;
        }
        else {
            mPrevResolution = mResolution;
            mResolution = r;

            Slog.d(TAG, "handleResolutionChange(), resolution:" +
                        mPrevResolution + "->" + mResolution);
        }

        int idxModified = getResolutionIndex(mResolution);
        int idxOriginal = getResolutionIndex(mPrevResolution);

        if (idxModified == idxOriginal) {
            return;
        }

        Slog.d(TAG, "index:" + idxOriginal + "->" + idxModified + ", doNotRemind:" + doNotRemind);

        SystemProperties.set("vendor.media.wfd.video-format", String.valueOf(idxModified));


        // check if need to reconnect
        if (mController.mConnectedDevice != null || mController.mConnectingDevice != null) {
            Slog.d(TAG, "-- reconnect for resolution change --");

            // reconnect again
            if (null != mController.mConnectedDevice) {
                mReConnectDevice = mController.mConnectedDevice;
            }
            mController.requestDisconnect();
        }
    }

    public void checkReConnect() {
        if (null != mReConnectDevice) {
            Slog.i(TAG, "requestStartScan() for resolution change.");
            //scan first
            mController.requestStartScan();
            // check scan result per RECONNECT_RETRY_DELAY_MILLIS ms
            mReConnection_Timeout_Remain_Seconds = CONNECTION_TIMEOUT_SECONDS;
            mHandler.postDelayed(mReConnect, RECONNECT_RETRY_DELAY_MILLIS);
        }
    }

    private void initPortraitResolutionSupport() {

         // Default on
         Settings.Global.putInt(
                 mContext.getContentResolver(),
                 getMtkSettingsExtGlobalSetting("WIFI_DISPLAY_PORTRAIT_RESOLUTION"),
                 0);

         //set system property
         SystemProperties.set("vendor.media.wfd.portrait", String.valueOf(0));
    }

    private void handlePortraitResolutionSupportChange() {
         int value = Settings.Global.getInt(
                 mContext.getContentResolver(),
                 getMtkSettingsExtGlobalSetting("WIFI_DISPLAY_PORTRAIT_RESOLUTION"),
                 0);
         Slog.i(TAG, "handlePortraitResolutionSupportChange:" + value);

         //set system property
         SystemProperties.set("vendor.media.wfd.portrait", String.valueOf(value));
    }

    private void registerEMObserver() {

        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);

        // Slog.i(TAG, "RealMetrics, Width = " + dm.widthPixels + ", Height = " + dm.heightPixels);

        int widthPixels = dm.widthPixels;
        int heightPixels = dm.heightPixels;

        // Init parameter
        WFDCONTROLLER_DISPLAY_RESOLUTION =
            mContext.getResources().getInteger(
                getMtkIntegerResourceId("wfd_display_default_resolution", -1));
        WFDCONTROLLER_DISPLAY_POWER_SAVING_OPTION =
            mContext.getResources().getInteger(
                getMtkIntegerResourceId("wfd_display_power_saving_option", 1));
        WFDCONTROLLER_DISPLAY_POWER_SAVING_DELAY =
            mContext.getResources().getInteger(
                getMtkIntegerResourceId("wfd_display_power_saving_delay", 10));

        Slog.d(TAG, "registerObserver() w:" + widthPixels +
                                         "h:" + heightPixels +
                                         "res:" + WFDCONTROLLER_DISPLAY_RESOLUTION +
                                         ",ps:" + WFDCONTROLLER_DISPLAY_POWER_SAVING_OPTION +
                                         ",psd:" + WFDCONTROLLER_DISPLAY_POWER_SAVING_DELAY);

        int r;
        r = Settings.Global.getInt(
                mContext.getContentResolver(),
                getMtkSettingsExtGlobalSetting("WIFI_DISPLAY_RESOLUTION"),
                -1);

        // boot up for the first time
        if (r == -1) {
            boolean bForce = isForce720p();

            if (WFDCONTROLLER_DISPLAY_RESOLUTION >= 0 &&
                WFDCONTROLLER_DISPLAY_RESOLUTION <= 3) {
                mPrevResolution = mResolution = WFDCONTROLLER_DISPLAY_RESOLUTION;
            } else if (bForce) {
                mPrevResolution = mResolution = 0;  // 0: 720p,30fps  (Menu is disabled)
            } else {
                // initialize resolution and frame rate
                if (widthPixels >= 1080 && heightPixels >= 1920) {
                    mPrevResolution = mResolution = 2;  // 2: 1080p,30fps (Menu is enabled)
                } else {
                    mPrevResolution = mResolution = 0;  // 0: 720p,30fps  (Menu is disabled)
                }
            }
        }
        else {
            if (r >= 0 && r <= 3) {
                // use the previous selection
                mPrevResolution = mResolution = r;
            } else {
                mPrevResolution = mResolution = 0; // 0: 720p,30fps  (Menu is disabled)
            }
        }

        int resolutionIndex = getResolutionIndex(mResolution);
        Slog.i(TAG, "mResolution:" + mResolution + ", resolutionIndex: " + resolutionIndex);

        SystemProperties.set("vendor.media.wfd.video-format", String.valueOf(resolutionIndex));

        Settings.Global.putInt(
                mContext.getContentResolver(),
                getMtkSettingsExtGlobalSetting("WIFI_DISPLAY_RESOLUTION"),
                mResolution);
        Settings.Global.putInt(
                mContext.getContentResolver(),
                getMtkSettingsExtGlobalSetting("WIFI_DISPLAY_POWER_SAVING_OPTION"),
                WFDCONTROLLER_DISPLAY_POWER_SAVING_OPTION);
        Settings.Global.putInt(
                mContext.getContentResolver(),
                getMtkSettingsExtGlobalSetting("WIFI_DISPLAY_POWER_SAVING_DELAY"),
                WFDCONTROLLER_DISPLAY_POWER_SAVING_DELAY);

        initPortraitResolutionSupport();

        // Register observer
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(
                        getMtkSettingsExtGlobalSetting("WIFI_DISPLAY_RESOLUTION")),
                false, mObserver);

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(
                        getMtkSettingsExtGlobalSetting("WIFI_DISPLAY_PORTRAIT_RESOLUTION")),
                false, mObserver);

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.WIFI_DISPLAY_ON),
                false, mObserver);
    }

    private final ContentObserver mObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {

            if (selfChange) {
                return;
            }

            updateHDMIStatus();
            handleResolutionChange();
            handlePortraitResolutionSupportChange();
        }
    };

    private int getMtkStringResourceId(String name) {
        try {
            Class<?> rCls = Class.forName("com.mediatek.internal.R$string",
                                            false, ClassLoader.getSystemClassLoader());
            Field field = rCls.getField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Exception  e) {
            Slog.e(TAG, "Cannot get MTK resource - " + e);
            return 0;
        }
    }

    private String getMtkSettingsExtGlobalSetting(String name) {
        try {
            Class<?> rCls = Class.forName("com.mediatek.provider.MtkSettingsExt$Global",
                                            false, ClassLoader.getSystemClassLoader());
            Field field = rCls.getField(name);
            field.setAccessible(true);
            return (String) field.get(rCls);
        } catch (Exception  e) {
            Slog.e(TAG, "Cannot get MTK settings - " + e);
            return "";
        }
    }

    private int getMtkIntegerResourceId(String name, int defaultVal) {
        try {
            Class<?> rCls = Class.forName("com.mediatek.internal.R$integer",
                                            false, ClassLoader.getSystemClassLoader());
            Field field = rCls.getField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Exception  e) {
            Slog.e(TAG, "Cannot get MTK resource - " + e);
            return defaultVal;
        }
    }

    public void turnOffHdmi() {
        if (null != mHdmiManager) {
            enableHdmi(mHdmiManager, false);
        }
    }

    public void turnOnHdmi() {
        if (null != mHdmiManager) {
            enableHdmi(mHdmiManager, true);
        }
    }

    private Object getHdmiService() {
        Object obj = null;
        try {
            Class<?> hdmiManagerClass = Class.forName(HDMI_MANAGER_CLASS, false, ClassLoader
                .getSystemClassLoader());
            Slog.d(TAG, "getHdmiService, hdmiManagerClass = " + hdmiManagerClass);
            Class<?> paraClass[] = {};
            Method method = hdmiManagerClass.getDeclaredMethod("getInstance", paraClass);
            method.setAccessible(true);
            Object noObject[] = {};
            obj = method.invoke(hdmiManagerClass, noObject);
            Slog.d(TAG, "getHdmiService, obj = " + obj);
        } catch (Exception e) {
            Slog.d(TAG, "getHdmiService, e = " + e);
            obj = null;
        }
        Slog.d(TAG, "getHdmiService, obj = " + obj);
        return obj;
    }

    public void enableHdmi(Object instance, boolean check) {
        try {
            Class<?> hdmiManagerClass = Class.forName(HDMI_MANAGER_CLASS, false, ClassLoader
                    .getSystemClassLoader());
            Class<?> paraClass[] = { boolean.class };
            Method enableHdmi = hdmiManagerClass.getDeclaredMethod("enableHdmi", paraClass);
            enableHdmi.setAccessible(true);
            enableHdmi.invoke(instance, check);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void updateHDMIStatus() {
        boolean HDMIOn = false;
        boolean wfdOn = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.WIFI_DISPLAY_ON, 0) != 0;
        if (!("".equals(SystemProperties.get("ro.vendor.mtk_tb_hdmi")))) {
                HDMIOn = (SystemProperties.getInt(HDMI_ENABLE, 0) == 1);
            }
        if (true == wfdOn && true == HDMIOn) {
            Slog.d(TAG, "When HDMI is on and turn on WFD --> turn off HDMI directly");
            Toast.makeText(mContext, "WFD is on, so trun off hdmi", Toast.LENGTH_SHORT).show();
            turnOffHdmi();
        }
    }

    private static final long WIFI_SCAN_TIMER = 100 * 1000;
    private AlarmManager mAlarmManager;
    private final AlarmManager.OnAlarmListener mWifiScanTimerListener =
            new AlarmManager.OnAlarmListener() {
                public void onAlarm() {
                    Slog.i(TAG, "Stop WiFi scan/reconnect due to scan timer timeout");
                    stopWifiScan(true);
                }
            };
    public boolean mStopWifiScan = false;
    public void stopWifiScan(boolean ifStop) {
        if (mStopWifiScan != ifStop) {
            Slog.i(TAG, "stopWifiScan()," + ifStop);
            try {
                // Get WifiInjector
                Method method = Class.forName(
                    "com.android.server.wifi.WifiInjector", false,
                        this.getClass().getClassLoader()).getDeclaredMethod("getInstance");
                method.setAccessible(true);
                Object wi = method.invoke(null);
                // Get WifiStatemachine
                // P: Method method2 = wi.getClass().getDeclaredMethod("getWifiStateMachine");
                Method method2 = wi.getClass().getDeclaredMethod("getClientModeImpl");
                method2.setAccessible(true);
                Object wsm = method2.invoke(wi);
                // Get WifiConnectivityManager
                Field fieldWcm = wsm.getClass().getDeclaredField("mWifiConnectivityManager");
                fieldWcm.setAccessible(true);
                Object wcm = fieldWcm.get(wsm);
                // Execute WifiConnectivityManager.enable() API
                Method method1 = wcm.getClass().getDeclaredMethod("enable", boolean.class);
                method1.setAccessible(true);
                method1.invoke(wcm, !ifStop);
            } catch (ReflectiveOperationException e) {
                e.printStackTrace();
            }

            if (ifStop == false) {
                // Resume wifi scan
                mAlarmManager.cancel(mWifiScanTimerListener);
            }

            mStopWifiScan = ifStop;
        }
    }

    private final BroadcastReceiver mWifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            /*M: ALPS01012422: can't scan any dongles when wifi ap is connected */
            if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                NetworkInfo info = (NetworkInfo) intent.getParcelableExtra(
                        WifiManager.EXTRA_NETWORK_INFO);

                /// M: [ALPS03631971] Resume WiFi scan/reconnect if WiFi is disconnected.
                /// Stop WiFi scan/reconnect if WiFi is connected.
                if (mController.mConnectedDevice != null) {
                    NetworkInfo.State state = info.getState();
                    if (state == NetworkInfo.State.DISCONNECTED && mStopWifiScan == true) {
                        Slog.i(TAG, "Resume WiFi scan/reconnect if WiFi is disconnected");
                        stopWifiScan(false);
                        mAlarmManager.cancel(mWifiScanTimerListener);
                        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                                        android.os.SystemClock.elapsedRealtime() + WIFI_SCAN_TIMER,
                                        "Set WiFi scan timer",
                                        mWifiScanTimerListener, mHandler);
                    } else if (state == NetworkInfo.State.CONNECTED && mStopWifiScan == false) {
                        Slog.i(TAG, "Stop WiFi scan/reconnect if WiFi is connected");
                        mAlarmManager.cancel(mWifiScanTimerListener);
                        stopWifiScan(true);
                    }
                }
            }
        }
    };
}

