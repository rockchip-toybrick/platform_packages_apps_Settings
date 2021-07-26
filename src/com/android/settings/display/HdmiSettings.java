package com.android.settings.display;

import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.preference.Preference.OnPreferenceChangeListener;
import android.util.Log;
import android.view.IWindowManager;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;

import androidx.fragment.app.DialogFragment;
import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.HdmiListPreference;
import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;

import java.io.RandomAccessFile;

import static com.android.settings.display.HdmiSettings.DISPLAY_SHOW_SETTINGS.DOUBLE_SHOW;
import static com.android.settings.display.HdmiSettings.DISPLAY_SHOW_SETTINGS.ONLY_SHOW_AUX;
import static com.android.settings.display.HdmiSettings.DISPLAY_SHOW_SETTINGS.ONLY_SHOW_MAIN;

public class HdmiSettings extends SettingsPreferenceFragment
        implements Preference.OnPreferenceChangeListener,
        Preference.OnPreferenceClickListener {
    /**
     * Called when the activity is first created.
     */
    private static final String TAG = "HdmiSettings";
    private static final String KEY_SYSTEM_ROTATION = "system_rotation";
    private static final String KEY_MAIN_CATEGORY = "main_category";
    private static final String KEY_MAIN_SWITCH = "main_switch";
    private static final String KEY_MAIN_RESOLUTION = "main_resolution";
    private static final String KEY_MAIN_SCALE = "main_screen_scale";
    private static final String KEY_AUX_CATEGORY = "aux_category";
    private static final String KEY_AUX_SWITCH = "aux_switch";
    private static final String KEY_AUX_RESOLUTION = "aux_resolution";
    private static final String KEY_AUX_SCALE = "aux_screen_scale";
    private static final String KEY_AUX_SCREEN_VH = "aux_screen_vh";
    private static final String KEY_AUX_SCREEN_VH_LIST = "aux_screen_vhlist";
    private static final String SYS_HDMI_STATE = "vendor.hdmi_status.aux";
    private static final String SYS_DP_STATE = "vendor.dp_status.aux";
    private final static String SYS_NODE_HDMI_STATUS =
            "/sys/devices/platform/display-subsystem/drm/card0/card0-HDMI-A-1/status";
    private final static String SYS_NODE_DP_STATUS =
            "/sys/devices/platform/display-subsystem/drm/card0/card0-DP-1/status";

    private static final int MSG_UPDATE_STATUS = 0;
    private static final int MSG_UPDATE_STATUS_UI = 1;
    private static final int MSG_SWITCH_DEVICE_STATUS = 2;
    private static final int MSG_UPDATE_DIALOG_INFO = 3;
    private static final int MSG_SHOW_CONFIRM_DIALOG = 4;
    private static final int SWITCH_STATUS_OFF_ON = 0;
    private static final int SWITCH_STATUS_OFF = 1;
    private static final int SWITCH_STATUS_ON = 2;
    private static final long SWITCH_DEVICE_DELAY_TIME = 200;
    private static final long TIME_WAIT_DEVICE_CONNECT = 10000;
    //we found setprop not effect sometimes if control quickly
    private static final boolean USED_NODE_SWITCH = true;
    private static final boolean USED_OFFON_RESOLUTION = false;

    /**
     * TODO
     * 目前hwc只配置了hdmi和dp的开关，如果是其他的设备，需要配合修改，才能进行开关
     * sys.hdmi_status.aux：/sys/devices/platform/display-subsystem/drm/card0/card0-HDMI-A-1/status
     * sys.hdmi_status.aux：/sys/devices/platform/display-subsystem/drm/card0/card0-DP-1/status
     */
    private String sys_main_state = SYS_HDMI_STATE;
    private String sys_aux_state = SYS_DP_STATE;
    private String main_switch_node = SYS_NODE_HDMI_STATUS;
    private String aux_switch_node = SYS_NODE_DP_STATUS;

    private ListPreference mSystemRotation;
    private PreferenceCategory mMainCategory;
    private SwitchPreference mMainSwitch;
    private HdmiListPreference mMainResolution;
    private Preference mMainScale;
    private PreferenceCategory mAuxCategory;
    private SwitchPreference mAuxSwitch;
    private HdmiListPreference mAuxResolution;
    private Preference mAuxScale;
    private CheckBoxPreference mAuxScreenVH;
    private ListPreference mAuxScreenVHList;
    private Context context;
    private String mOldMainResolution;
    private String mOldAuxResolution;
    protected DisplayInfo mMainDisplayInfo;
    protected DisplayInfo mAuxDisplayInfo;
    private DisplayManager mDisplayManager;
    private DisplayListener mDisplayListener;
    private IWindowManager mWindowManager;
    private ProgressDialog mProgressDialog;
    private DISPLAY_SHOW_SETTINGS mShowSettings = ONLY_SHOW_AUX;
    private boolean mDestory;
    private boolean mEnableDisplayListener;
    private Object mLock = new Object();//maybe android reboot if not lock with new thread
    private boolean mResume;
    private long mWaitDialogCountTime;
    private int mRotation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    enum DISPLAY_SHOW_SETTINGS {
        ONLY_SHOW_MAIN,
        ONLY_SHOW_AUX,
        DOUBLE_SHOW
    }

    enum ITEM_CONTROL {
        SHOW_DISPLAY_ITEM_MAIN,//展示主屏分辨率选项
        SHOW_DISPLAY_ITEM_AUX,//展示副屏分辨率选项
        CHANGE_RESOLUTION_MAIN,//切换主屏分辨率
        CHANGE_RESOLUTION_AUX,//切换副屏分辨率
        REFRESH_MAIN_INFO,//刷新主屏信息
        REFRESH_AUX_INFO,//刷新副屏信息
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(final Message msg) {
            if (mDestory && MSG_SWITCH_DEVICE_STATUS != msg.what) {
                return;
            }
            if (MSG_UPDATE_STATUS == msg.what) {
                final ITEM_CONTROL control = (ITEM_CONTROL) msg.obj;
                new Thread() {
                    @Override
                    public void run() {
                        if (ITEM_CONTROL.SHOW_DISPLAY_ITEM_MAIN == control
                                || ITEM_CONTROL.CHANGE_RESOLUTION_MAIN == control
                                || ITEM_CONTROL.REFRESH_MAIN_INFO == control) {
                            updateMainState(control);
                        } else if (ITEM_CONTROL.SHOW_DISPLAY_ITEM_AUX == control
                                || ITEM_CONTROL.CHANGE_RESOLUTION_AUX == control
                                || ITEM_CONTROL.REFRESH_AUX_INFO == control) {
                            updateAuxState(control);
                        }
                        Message message = new Message();
                        message.what = MSG_UPDATE_STATUS_UI;
                        message.obj = control;
                        mHandler.sendMessage(message);
                    }
                }.start();
            } else if (MSG_UPDATE_STATUS_UI == msg.what) {
                ITEM_CONTROL control = (ITEM_CONTROL) msg.obj;
                if (ITEM_CONTROL.SHOW_DISPLAY_ITEM_MAIN == control) {
                    updateMainStateUI(control);
                    if (mMainResolution.isEnabled()) {
                        mMainResolution.showClickDialogItem();
                    }
                } else if (ITEM_CONTROL.SHOW_DISPLAY_ITEM_AUX == control) {
                    updateAuxStateUI(control);
                    if (mAuxResolution.isEnabled()) {
                        mAuxResolution.showClickDialogItem();
                    }
                } else if (ITEM_CONTROL.CHANGE_RESOLUTION_MAIN == control) {
                    updateMainStateUI(control);
                    showConfirmSetMainModeDialog();
                } else if (ITEM_CONTROL.CHANGE_RESOLUTION_AUX == control) {
                    updateAuxStateUI(control);
                    showConfirmSetAuxModeDialog();
                } else if (ITEM_CONTROL.REFRESH_MAIN_INFO == control) {
                    updateMainStateUI(control);
                } else if (ITEM_CONTROL.REFRESH_AUX_INFO == control) {
                    updateAuxStateUI(control);
                }
                mEnableDisplayListener = true;
            } else if (MSG_SWITCH_DEVICE_STATUS == msg.what) {
                final ITEM_CONTROL control = (ITEM_CONTROL) msg.obj;
                if (SWITCH_STATUS_ON == msg.arg1) {
                    if (ITEM_CONTROL.CHANGE_RESOLUTION_MAIN == control
                            || ITEM_CONTROL.REFRESH_MAIN_INFO == control) {
                        showWaitingDialog(R.string.dialog_wait_screen_connect);
                        if (USED_NODE_SWITCH) {
                            mMainSwitch.setEnabled(true);
                            new Thread() {
                                @Override
                                public void run() {
                                    write2Node(main_switch_node, "detect");
                                    mWaitDialogCountTime = TIME_WAIT_DEVICE_CONNECT / 1000;
                                    mHandler.removeMessages(MSG_UPDATE_DIALOG_INFO);
                                    mHandler.sendEmptyMessage(MSG_UPDATE_DIALOG_INFO);
                                    sendUpdateStateMsg(control, TIME_WAIT_DEVICE_CONNECT);
                                }
                            }.start();
                        } else {
                            SystemProperties.set(sys_main_state, "on");
                            mMainSwitch.setEnabled(true);
                            sendUpdateStateMsg(control, 2000);
                        }
                    } else if (ITEM_CONTROL.CHANGE_RESOLUTION_AUX == control
                            || ITEM_CONTROL.REFRESH_AUX_INFO == control) {
                        showWaitingDialog(R.string.dialog_wait_screen_connect);
                        if (USED_NODE_SWITCH) {
                            mAuxSwitch.setEnabled(true);
                            new Thread() {
                                @Override
                                public void run() {
                                    write2Node(aux_switch_node, "detect");
                                    mWaitDialogCountTime = TIME_WAIT_DEVICE_CONNECT / 1000;
                                    mHandler.removeMessages(MSG_UPDATE_DIALOG_INFO);
                                    mHandler.sendEmptyMessage(MSG_UPDATE_DIALOG_INFO);
                                    sendUpdateStateMsg(control, TIME_WAIT_DEVICE_CONNECT);
                                }
                            }.start();
                        } else {
                            SystemProperties.set(sys_aux_state, "on");
                            mAuxSwitch.setEnabled(true);
                            sendUpdateStateMsg(control, 2000);
                        }
                    }
                } else {
                    if (ITEM_CONTROL.CHANGE_RESOLUTION_MAIN == control
                            || ITEM_CONTROL.REFRESH_MAIN_INFO == control) {
                        if (USED_NODE_SWITCH) {
                            mMainSwitch.setEnabled(false);
                            new Thread() {
                                @Override
                                public void run() {
                                    write2Node(main_switch_node, "off");
                                    if (SWITCH_STATUS_OFF_ON == msg.arg1) {
                                        sendSwitchDeviceOffOnMsg(control, SWITCH_STATUS_ON);
                                    } else {
                                        sendUpdateStateMsg(control, 2000);
                                    }
                                }
                            }.start();
                        } else {
                            SystemProperties.set(sys_main_state, "off");
                            mMainSwitch.setEnabled(false);
                            sendUpdateStateMsg(control, 2000);
                            if (SWITCH_STATUS_OFF_ON == msg.arg1) {
                                sendSwitchDeviceOffOnMsg(control, SWITCH_STATUS_ON);
                            } else {
                                sendUpdateStateMsg(control, 2000);
                            }
                        }
                    } else if (ITEM_CONTROL.CHANGE_RESOLUTION_AUX == control
                            || ITEM_CONTROL.REFRESH_AUX_INFO == control) {
                        if (USED_NODE_SWITCH) {
                            mAuxSwitch.setEnabled(false);
                            new Thread() {
                                @Override
                                public void run() {
                                    write2Node(aux_switch_node, "off");
                                    if (SWITCH_STATUS_OFF_ON == msg.arg1) {
                                        sendSwitchDeviceOffOnMsg(control, SWITCH_STATUS_ON);
                                    } else {
                                        sendUpdateStateMsg(control, 2000);
                                    }
                                }
                            }.start();
                        } else {
                            SystemProperties.set(sys_aux_state, "off");
                            mAuxSwitch.setEnabled(false);
                            if (SWITCH_STATUS_OFF_ON == msg.arg1) {
                                sendSwitchDeviceOffOnMsg(control, SWITCH_STATUS_ON);
                            } else {
                                sendUpdateStateMsg(control, 2000);
                            }
                        }
                    }
                }
            } else if (MSG_UPDATE_DIALOG_INFO == msg.what) {
                if (mWaitDialogCountTime > 0) {
                    if (null != mProgressDialog && mProgressDialog.isShowing()) {
                        mProgressDialog.setMessage(getContext().getString(
                                R.string.dialog_wait_screen_connect) + " " + mWaitDialogCountTime);
                        mWaitDialogCountTime--;
                        mHandler.removeMessages(MSG_UPDATE_DIALOG_INFO);
                        mHandler.sendEmptyMessageDelayed(MSG_UPDATE_DIALOG_INFO, 1000);
                    }
                }
            } else if (MSG_SHOW_CONFIRM_DIALOG == msg.what) {
                mHandler.removeMessages(MSG_SHOW_CONFIRM_DIALOG);
                hideWaitingDialog();
                if (ITEM_CONTROL.CHANGE_RESOLUTION_MAIN == msg.obj) {
                    showConfirmSetMainModeDialog();
                } else if (ITEM_CONTROL.CHANGE_RESOLUTION_AUX == msg.obj) {
                    showConfirmSetAuxModeDialog();
                }
            }
        }
    };

    private final BroadcastReceiver HdmiListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctxt, Intent receivedIt) {
            String action = receivedIt.getAction();
            String HDMIINTENT = "android.intent.action.HDMI_PLUGGED";
            if (action.equals(HDMIINTENT)) {
                boolean state = receivedIt.getBooleanExtra("state", false);
                if (state) {
                    Log.d(TAG, "BroadcastReceiver.onReceive() : Connected HDMI-TV");
                } else {
                    Log.d(TAG, "BroadcastReceiver.onReceive() : Disconnected HDMI-TV");
                }
            }
        }
    };

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.DISPLAY;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        context = getActivity();
        mRotation = getActivity().getRequestedOrientation();
        getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        mWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));
        mDisplayListener = new DisplayListener();
        addPreferencesFromResource(R.xml.hdmi_settings);
        int value = 0;
        String currentMainConfig = SystemProperties.get("vendor.hwc.device.primary");
        boolean hasMainDisplay = false;
        if (null != currentMainConfig) {
            currentMainConfig = currentMainConfig.replaceAll("eDP", "");
            hasMainDisplay = currentMainConfig.contains("HDMI")
                    || currentMainConfig.contains("DP");
        }
        String currentAuxConfig = SystemProperties.get("vendor.hwc.device.extend");
        boolean hasAuxDisplay = false;
        if (null != currentAuxConfig) {
            currentAuxConfig = currentAuxConfig.replaceAll("eDP", "");
            hasAuxDisplay = currentAuxConfig.contains("HDMI")
                    || currentAuxConfig.contains("DP");
        }

        if (hasMainDisplay) {
            value = hasAuxDisplay ? 2 : 1;
        }
        Log.d(TAG, "primary=" + currentMainConfig + ", extend=" + currentAuxConfig
                + ", hasMain=" + hasMainDisplay + ", hasAux=" + hasAuxDisplay
                + ", value=" + value);
        switch (value) {
            case 0: {
                mShowSettings = ONLY_SHOW_AUX;
                sys_aux_state = SYS_HDMI_STATE;
                aux_switch_node = SYS_NODE_HDMI_STATUS;
                break;
            }
            case 1: {
                mShowSettings = ONLY_SHOW_MAIN;
                sys_main_state = SYS_HDMI_STATE;
                main_switch_node = SYS_NODE_HDMI_STATUS;
                break;
            }
            default: {
                mShowSettings = DOUBLE_SHOW;
                String primary = SystemProperties.get("vendor.hwc.device.primary", "");
                String extend = SystemProperties.get("vendor.hwc.device.extend", "");
                if (primary.contains("HDMI")) {//配置hdmi为主显
                    sys_main_state = SYS_HDMI_STATE;
                    sys_aux_state = SYS_DP_STATE;
                    main_switch_node = SYS_NODE_HDMI_STATUS;
                    aux_switch_node = SYS_NODE_DP_STATUS;
                } else if (extend.contains("HDMI")) {//主显不配hdmi,副显配置hdmi
                    sys_aux_state = SYS_HDMI_STATE;
                    sys_main_state = SYS_DP_STATE;
                    main_switch_node = SYS_NODE_DP_STATUS;
                    aux_switch_node = SYS_NODE_HDMI_STATUS;
                }
                break;
            }
        }
        init();
        mEnableDisplayListener = true;
        Log.d(TAG, "---------onCreate---------------------");
    }


    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView----------------------------------------");
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        //showWaitingDialog(0, "");
        IntentFilter filter = new IntentFilter("android.intent.action.HDMI_PLUGGED");
        getContext().registerReceiver(HdmiListener, filter);
        //refreshState();
        mDisplayManager.registerDisplayListener(mDisplayListener, null);
        mResume = true;
    }

    private void showWaitingDialog(int msgResId) {
        if (mDestory) {
            return;
        }
        if (null == mProgressDialog) {
            mProgressDialog = new ProgressDialog(getActivity());
            mProgressDialog.setCanceledOnTouchOutside(false);
            mProgressDialog.setCancelable(false);
        }
        mProgressDialog.setMessage(getContext().getString(msgResId));
        if (!mProgressDialog.isShowing()) {
            mProgressDialog.show();
        }
    }

    private void hideWaitingDialog() {
        if (null != mProgressDialog && mProgressDialog.isShowing()) {
            mProgressDialog.cancel();
            mProgressDialog = null;
        }
    }

    public void onPause() {
        super.onPause();
        mResume = false;
        Log.d(TAG, "onPause----------------");
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
        getContext().unregisterReceiver(HdmiListener);
    }

    public void onDestroy() {
        mDestory = true;
        getActivity().setRequestedOrientation(mRotation);
        super.onDestroy();
        mHandler.removeMessages(MSG_UPDATE_STATUS);
        mHandler.removeMessages(MSG_SWITCH_DEVICE_STATUS);
        mHandler.removeMessages(MSG_UPDATE_DIALOG_INFO);
        mHandler.removeMessages(MSG_SHOW_CONFIRM_DIALOG);
        hideWaitingDialog();
        Log.d(TAG, "onDestroy----------------");
    }

    private void init() {
        //boolean showSystemRotation = mShowSettings != DISPLAY_SHOW_SETTINGS.ONLY_SHOW_AUX;
        boolean showSystemRotation = false;
        if (showSystemRotation) {
            mSystemRotation = (ListPreference) findPreference(KEY_SYSTEM_ROTATION);
            mSystemRotation.setOnPreferenceChangeListener(this);
            try {
                int rotation = mWindowManager.getDefaultDisplayRotation();
                switch (rotation) {
                    case Surface.ROTATION_0:
                        mSystemRotation.setValue("0");
                        break;
                    case Surface.ROTATION_90:
                        mSystemRotation.setValue("90");
                        break;
                    case Surface.ROTATION_180:
                        mSystemRotation.setValue("180");
                        break;
                    case Surface.ROTATION_270:
                        mSystemRotation.setValue("270");
                        break;
                    default:
                        mSystemRotation.setValue("0");
                }
            } catch (Exception e) {
                Log.e(TAG, e.toString());
            }
        } else {
            removePreference(KEY_SYSTEM_ROTATION);
        }
        //main
        if (mShowSettings != ONLY_SHOW_AUX) {
            mMainDisplayInfo = getDisplayInfo(0);
            //restore main switch value
            mMainCategory = (PreferenceCategory) findPreference(KEY_MAIN_CATEGORY);
            if (mShowSettings == DOUBLE_SHOW) {
                mMainCategory.setTitle(R.string.screen_main_title);
            }
            String switchState = SystemProperties.get(sys_main_state, "on");
            mMainSwitch = (SwitchPreference) findPreference(KEY_MAIN_SWITCH);
            if ("on".equals(switchState)) {
                mMainSwitch.setChecked(true);
            } else {
                mMainSwitch.setChecked(false);
            }
            mMainSwitch.setOnPreferenceChangeListener(this);
            mMainCategory.removePreference(mMainSwitch);
            mMainResolution = (HdmiListPreference) findPreference(KEY_MAIN_RESOLUTION);
            mMainResolution.setOnPreferenceChangeListener(this);
            mMainResolution.setOnPreferenceClickListener(this);
            if (mMainDisplayInfo != null) {
                mMainResolution.setEntries(DrmDisplaySetting.getDisplayModes(mMainDisplayInfo).toArray(new String[0]));
                mMainResolution.setEntryValues(DrmDisplaySetting.getDisplayModes(mMainDisplayInfo).toArray(new String[0]));
                mMainResolution.setEnabled(true);
            } else {
                mMainResolution.setEnabled(false);
            }
            mMainScale = findPreference(KEY_MAIN_SCALE);
            mMainScale.setOnPreferenceClickListener(this);
            if (null == mMainDisplayInfo) {
                mMainScale.setEnabled(false);
            } else {
                mMainScale.setEnabled(true);
            }
            //mMainCategory.removePreference(mMainScale);
            //mMainCategory.removePreference(mMainSwitch);
        } else {
            removePreference(KEY_MAIN_CATEGORY);
        }

        //aux
        if (mShowSettings != ONLY_SHOW_MAIN) {
            mAuxDisplayInfo = getDisplayInfo(1);
            mAuxCategory = (PreferenceCategory) findPreference(KEY_AUX_CATEGORY);
            if (mShowSettings == DOUBLE_SHOW) {
                mAuxCategory.setTitle(R.string.screen_aux_title);
            }
            mAuxSwitch = (SwitchPreference) findPreference(KEY_AUX_SWITCH);
            String switchState = SystemProperties.get(sys_aux_state, "on");
            if ("on".equals(switchState)) {
                mAuxSwitch.setChecked(true);
            } else {
                mAuxSwitch.setChecked(false);
            }
            mAuxSwitch.setOnPreferenceChangeListener(this);
            mAuxCategory.removePreference(mAuxSwitch);
            mAuxResolution = (HdmiListPreference) findPreference(KEY_AUX_RESOLUTION);
            mAuxResolution.setOnPreferenceChangeListener(this);
            mAuxResolution.setOnPreferenceClickListener(this);
            if (mAuxDisplayInfo != null) {
                mAuxResolution.setEntries(DrmDisplaySetting.getDisplayModes(mAuxDisplayInfo).toArray(new String[0]));
                mAuxResolution.setEntryValues(DrmDisplaySetting.getDisplayModes(mAuxDisplayInfo).toArray(new String[0]));
                mAuxResolution.setEnabled(true);
            } else {
                mAuxResolution.setEnabled(false);
            }
            mAuxScale = findPreference(KEY_AUX_SCALE);
            mAuxScale.setOnPreferenceClickListener(this);
            if (null == mAuxDisplayInfo) {
                mAuxScale.setEnabled(false);
            } else {
                mAuxScale.setEnabled(true);
            }
            //mAuxCategory.removePreference(mAuxScale);

            mAuxScreenVH = (CheckBoxPreference) findPreference(KEY_AUX_SCREEN_VH);
            mAuxScreenVH.setChecked(SystemProperties.getBoolean("persist.sys.rotation.efull", false));
            mAuxScreenVH.setOnPreferenceChangeListener(this);
            mAuxCategory.removePreference(mAuxScreenVH);
            mAuxScreenVHList = (ListPreference) findPreference(KEY_AUX_SCREEN_VH_LIST);
            mAuxScreenVHList.setOnPreferenceChangeListener(this);
            mAuxScreenVHList.setOnPreferenceClickListener(this);
            mAuxCategory.removePreference(mAuxScreenVHList);
        } else {
            removePreference(KEY_AUX_CATEGORY);
        }
    }

    protected DisplayInfo getDisplayInfo(int displayId) {
        DrmDisplaySetting.updateDisplayInfos();
        return DrmDisplaySetting.getDisplayInfo(displayId);
    }

    /**
     * 获取当前分辨率值
     */
    public void updateMainResolutionValue() {
        String resolutionValue = null;
        mMainDisplayInfo = getDisplayInfo(0);
        if (mMainDisplayInfo != null) {
            resolutionValue = DrmDisplaySetting.getCurDisplayMode(mMainDisplayInfo);
            mMainDisplayInfo.setCurrentResolution(resolutionValue);
        }
        Log.i(TAG, "main resolutionValue:" + resolutionValue);
        mOldMainResolution = resolutionValue;
    }

    public void updateAuxResolutionValue() {
        String resolutionValue = null;
        mAuxDisplayInfo = getDisplayInfo(1);
        if (mAuxDisplayInfo != null) {
            resolutionValue = DrmDisplaySetting.getCurDisplayMode(mAuxDisplayInfo);
            mAuxDisplayInfo.setCurrentResolution(resolutionValue);
        }
        Log.i(TAG, "aux resolutionValue:" + resolutionValue);
        mOldAuxResolution = resolutionValue;
    }

    private void sendSwitchDeviceOffOnMsg(ITEM_CONTROL control, int status) {
        mEnableDisplayListener = false;
        Message msg = new Message();
        msg.what = MSG_SWITCH_DEVICE_STATUS;
        msg.arg1 = status;
        msg.obj = control;
        mHandler.removeMessages(MSG_SWITCH_DEVICE_STATUS, control);
        mHandler.sendMessageDelayed(msg, SWITCH_DEVICE_DELAY_TIME);
    }

    public static void write2Node(String node, String values) {
        Log.v(TAG, "write " + node + " " + values);
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(node, "rw");
            raf.writeBytes(values);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (null != raf) {
                try {
                    raf.close();
                } catch (Exception e) {
                    //e.printStackTrace();
                }
            }
        }
    }

    private void updateResolution(final ITEM_CONTROL control, final int index) {
        if (ITEM_CONTROL.CHANGE_RESOLUTION_MAIN == control) {
            mMainResolution.setEnabled(false);
            mMainScale.setEnabled(false);
            if (null == mMainDisplayInfo) {
                return;
            }
        } else if (ITEM_CONTROL.CHANGE_RESOLUTION_AUX == control) {
            mAuxResolution.setEnabled(false);
            mAuxScale.setEnabled(false);
            if (null == mAuxDisplayInfo) {
                return;
            }
        }
        showWaitingDialog(R.string.dialog_update_resolution);
        mEnableDisplayListener = false;
        new Thread() {
            @Override
            public void run() {
                if (ITEM_CONTROL.CHANGE_RESOLUTION_MAIN == control) {
                    synchronized (mLock) {
                        mMainDisplayInfo = getDisplayInfo(0);
                        if (mMainDisplayInfo != null) {
                            DrmDisplaySetting.setDisplayModeTemp(mMainDisplayInfo, index);
                            if (USED_OFFON_RESOLUTION) {
                                sendSwitchDeviceOffOnMsg(control, SWITCH_STATUS_OFF_ON);
                            } else {
                                Message message = new Message();
                                message.what = MSG_SHOW_CONFIRM_DIALOG;
                                message.obj = control;
                                mHandler.sendMessageDelayed(message, 300);
                            }
                        } else {
                            Message message = new Message();
                            message.what = MSG_UPDATE_STATUS_UI;
                            message.obj = ITEM_CONTROL.REFRESH_MAIN_INFO;
                            mHandler.sendMessage(message);
                        }
                    }
                } else if (ITEM_CONTROL.CHANGE_RESOLUTION_AUX == control) {
                    synchronized (mLock) {
                        mAuxDisplayInfo = getDisplayInfo(1);
                        if (mAuxDisplayInfo != null) {
                            DrmDisplaySetting.setDisplayModeTemp(mAuxDisplayInfo, index);
                            if (USED_OFFON_RESOLUTION) {
                                sendSwitchDeviceOffOnMsg(control, SWITCH_STATUS_OFF_ON);
                            } else {
                                Message message = new Message();
                                message.what = MSG_SHOW_CONFIRM_DIALOG;
                                message.obj = control;
                                mHandler.sendMessageDelayed(message, 300);
                            }
                        } else {
                            Message message = new Message();
                            message.what = MSG_UPDATE_STATUS_UI;
                            message.obj = ITEM_CONTROL.REFRESH_AUX_INFO;
                            mHandler.sendMessage(message);
                        }
                    }
                }
            }
        }.start();
    }

    private void sendUpdateStateMsg(ITEM_CONTROL control, long delayMillis) {
        if (mDestory) {
            return;
        }
        Message msg = new Message();
        msg.what = MSG_UPDATE_STATUS;
        msg.obj = control;
        //增加延迟，保证数据能够拿到
        mHandler.removeMessages(MSG_UPDATE_STATUS, control);
        mHandler.sendMessageDelayed(msg, delayMillis);
    }

    private void updateMainState(ITEM_CONTROL control) {
        synchronized (mLock) {
            if (mDestory) {
                return;
            }
            mMainDisplayInfo = getDisplayInfo(0);
            if (mMainDisplayInfo != null
                    && ITEM_CONTROL.SHOW_DISPLAY_ITEM_MAIN == control) {
                updateMainResolutionValue();
            }
        }
    }

    private void updateMainStateUI(ITEM_CONTROL control) {
        if (mDestory) {
            return;
        }
        if (mMainDisplayInfo != null) {
            if (ITEM_CONTROL.SHOW_DISPLAY_ITEM_MAIN == control) {
                String[] modes = null == mMainDisplayInfo.getOrginModes() ? new String[]{} :
                        mMainDisplayInfo.getOrginModes();
                mMainResolution.setEntries(modes);
                mMainResolution.setEntryValues(modes);
                mMainResolution.setValue(mMainDisplayInfo.getCurrentResolution());
            }
            mMainResolution.setEnabled(true);
            mMainScale.setEnabled(true);
        } else {
            mMainResolution.setEnabled(false);
            mMainScale.setEnabled(false);
        }
        hideWaitingDialog();
    }

    private void updateAuxState(ITEM_CONTROL control) {
        if (mDestory) {
            return;
        }
        synchronized (mLock) {
            mAuxDisplayInfo = getDisplayInfo(1);
            if (mAuxDisplayInfo != null
                    && ITEM_CONTROL.SHOW_DISPLAY_ITEM_AUX == control) {
                updateAuxResolutionValue();
            }
        }
    }

    private void updateAuxStateUI(ITEM_CONTROL control) {
        if (mDestory) {
            return;
        }
        if (mAuxDisplayInfo != null) {
            if (ITEM_CONTROL.SHOW_DISPLAY_ITEM_AUX == control) {
                String[] modes = null == mAuxDisplayInfo.getOrginModes() ? new String[]{} :
                        mAuxDisplayInfo.getOrginModes();
                mAuxResolution.setEntries(modes);
                mAuxResolution.setEntryValues(modes);
                mAuxResolution.setValue(mAuxDisplayInfo.getCurrentResolution());
            }
            mAuxResolution.setEnabled(true);
            mAuxScale.setEnabled(true);
            mAuxScreenVH.setEnabled(true);
            mAuxScreenVHList.setEnabled(true);
        } else {
            mAuxResolution.setEnabled(false);
            mAuxScale.setEnabled(false);
            mAuxScreenVH.setEnabled(false);
            mAuxScreenVHList.setEnabled(false);
        }
        hideWaitingDialog();
    }

    protected void showConfirmSetMainModeDialog() {
        //mMainDisplayInfo = getDisplayInfo(0);
        if (mMainDisplayInfo != null && mResume) {
            Log.v(TAG, "showConfirmSetMainModeDialog");
            DialogFragment df = ConfirmSetModeDialogFragment.newInstance(mMainDisplayInfo, new ConfirmSetModeDialogFragment.OnDialogDismissListener() {
                @Override
                public void onDismiss(boolean isok) {
                    Log.i(TAG, "showConfirmSetMainModeDialog->onDismiss->isok:" + isok);
                    Log.i(TAG, "showConfirmSetMainModeDialog->onDismiss->mOldResolution:" + mOldMainResolution);
                    synchronized (mLock) {
                        DrmDisplaySetting.confirmSaveDisplayMode(mMainDisplayInfo, isok);
                        if (!isok) {
                            mMainResolution.setEnabled(false);
                            mMainScale.setEnabled(false);
                            if (USED_OFFON_RESOLUTION) {
                                showWaitingDialog(R.string.dialog_wait_screen_connect);
                                sendSwitchDeviceOffOnMsg(ITEM_CONTROL.REFRESH_MAIN_INFO, SWITCH_STATUS_OFF_ON);
                            } else {
                                showWaitingDialog(R.string.dialog_update_resolution);
                                sendUpdateStateMsg(ITEM_CONTROL.REFRESH_MAIN_INFO, 1000);
                            }
                        } else if (!USED_OFFON_RESOLUTION) {
                            updateMainStateUI(ITEM_CONTROL.REFRESH_MAIN_INFO);
                        }
                    }
                }
            });
            df.show(getFragmentManager(), "ConfirmDialog");
        }
    }

    protected void showConfirmSetAuxModeDialog() {
        //mAuxDisplayInfo = getDisplayInfo(1);
        if (mAuxDisplayInfo != null && mResume) {
            Log.v(TAG, "showConfirmSetAuxModeDialog");
            DialogFragment df = ConfirmSetModeDialogFragment.newInstance(mAuxDisplayInfo, new ConfirmSetModeDialogFragment.OnDialogDismissListener() {
                @Override
                public void onDismiss(boolean isok) {
                    Log.i(TAG, "showConfirmSetAuxModeDialog->onDismiss->isok:" + isok);
                    Log.i(TAG, "showConfirmSetAuxModeDialog->onDismiss->mOldAuxResolution:" + mOldAuxResolution);
                    synchronized (mLock) {
                        DrmDisplaySetting.confirmSaveDisplayMode(mAuxDisplayInfo, isok);
                        if (!isok) {
                            mAuxResolution.setEnabled(false);
                            mAuxScale.setEnabled(true);
                            if (USED_OFFON_RESOLUTION) {
                                showWaitingDialog(R.string.dialog_wait_screen_connect);
                                sendSwitchDeviceOffOnMsg(ITEM_CONTROL.REFRESH_AUX_INFO, SWITCH_STATUS_OFF_ON);//not effect with setprop? so directly write node
                            } else {
                                showWaitingDialog(R.string.dialog_update_resolution);
                                sendUpdateStateMsg(ITEM_CONTROL.REFRESH_AUX_INFO, 1000);
                            }
                        } else if (!USED_OFFON_RESOLUTION) {
                            updateAuxStateUI(ITEM_CONTROL.REFRESH_AUX_INFO);
                        }
                    }
                }
            });
            df.show(getFragmentManager(), "ConfirmDialog");
        }
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        // TODO Auto-generated method stub
        return true;
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        if (preference == mMainScale) {
            Intent screenScaleIntent = new Intent(getActivity(), ScreenScaleActivity.class);
            mMainDisplayInfo = getDisplayInfo(0);
            if (mMainDisplayInfo != null) {
                screenScaleIntent.putExtra(ScreenScaleActivity.EXTRA_DISPLAY_INFO, mMainDisplayInfo);
                startActivity(screenScaleIntent);
            } else {
                mMainResolution.setEnabled(false);
                mMainScale.setEnabled(false);
            }
        } else if (preference == mMainResolution) {
            Log.i(TAG, "onPreferenceClick mMainResolution");
            showWaitingDialog(R.string.dialog_getting_screen_info);
            sendUpdateStateMsg(ITEM_CONTROL.SHOW_DISPLAY_ITEM_MAIN, 1000);
        } else if (preference == mAuxScreenVHList) {
            String value = SystemProperties.get("persist.sys.rotation.einit", "0");
            mAuxScreenVHList.setValue(value);
        } else if (preference == mAuxScale) {
            Intent screenScaleIntent = new Intent(getActivity(), ScreenScaleActivity.class);
            mAuxDisplayInfo = getDisplayInfo(1);
            if (mAuxDisplayInfo != null) {
                screenScaleIntent.putExtra(ScreenScaleActivity.EXTRA_DISPLAY_INFO, mAuxDisplayInfo);
                startActivity(screenScaleIntent);
            } else {
                mAuxResolution.setEnabled(false);
                mAuxScale.setEnabled(false);
                mAuxScreenVH.setEnabled(false);
                mAuxScreenVHList.setEnabled(false);
            }
        } else if (preference == mAuxResolution) {
            Log.i(TAG, "onPreferenceClick mAuxResolution");
            showWaitingDialog(R.string.dialog_getting_screen_info);
            sendUpdateStateMsg(ITEM_CONTROL.SHOW_DISPLAY_ITEM_AUX, 1000);
        }
        return true;
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object obj) {
        String key = preference.getKey();
        Log.i(TAG, key + " onPreferenceChange:" + obj);
        if (preference == mMainResolution) {
            if (KEY_MAIN_RESOLUTION.equals(key)) {
                if (obj.equals(mOldMainResolution))
                    return true;
                int index = mMainResolution.findIndexOfValue((String) obj);
                Log.i(TAG, "onMainPreferenceChange: index= " + index);
                if (-1 == index) {
                    Log.e(TAG, "onMainPreferenceChange: index=-1 start print");
                    CharSequence[] temps = mMainResolution.getEntryValues();
                    if (null == temps) {
                        for (CharSequence temp : temps) {
                            Log.i(TAG, "=======" + temp);
                        }
                    } else {
                        Log.e(TAG, "mMainResolution.getEntryValues() is null, but set " + obj);
                    }
                    Log.e(TAG, "onMainPreferenceChange: index=-1 end print");
                }
                updateResolution(ITEM_CONTROL.CHANGE_RESOLUTION_MAIN, index);
            }
        } else if (preference == mAuxResolution) {
            if (KEY_AUX_RESOLUTION.equals(key)) {
                if (obj.equals(mOldAuxResolution))
                    return true;
                int index = mAuxResolution.findIndexOfValue((String) obj);
                Log.i(TAG, "onAuxPreferenceChange: index= " + index);
                updateResolution(ITEM_CONTROL.CHANGE_RESOLUTION_AUX, index);
            }
        } else if (KEY_MAIN_SWITCH.equals(key)) {
            mEnableDisplayListener = false;
            showWaitingDialog(R.string.dialog_getting_screen_info);
            if (Boolean.parseBoolean(obj.toString())) {
                SystemProperties.set(sys_main_state, "on");
            } else {
                SystemProperties.set(sys_main_state, "off");
            }
            sendUpdateStateMsg(ITEM_CONTROL.REFRESH_MAIN_INFO, 2000);
        } else if (KEY_AUX_SWITCH.equals(key)) {
            mEnableDisplayListener = false;
            showWaitingDialog(R.string.dialog_getting_screen_info);
            if (Boolean.parseBoolean(obj.toString())) {
                SystemProperties.set(sys_aux_state, "on");
            } else {
                SystemProperties.set(sys_aux_state, "off");
            }
            sendUpdateStateMsg(ITEM_CONTROL.REFRESH_AUX_INFO, 2000);
        } else if (preference == mSystemRotation) {
            if (KEY_SYSTEM_ROTATION.equals(key)) {
                try {
                    int value = Integer.parseInt((String) obj);
                    android.os.SystemProperties.set("persist.sys.orientation", (String) obj);
                    Log.d(TAG, "freezeRotation~~~value:" + (String) obj);
                    if (value == 0) {
                        mWindowManager.freezeRotation(Surface.ROTATION_0);
                    } else if (value == 90) {
                        mWindowManager.freezeRotation(Surface.ROTATION_90);
                    } else if (value == 180) {
                        mWindowManager.freezeRotation(Surface.ROTATION_180);
                    } else if (value == 270) {
                        mWindowManager.freezeRotation(Surface.ROTATION_270);
                    } else {
                        return true;
                    }
                    //android.os.SystemProperties.set("sys.boot_completed", "1");
                } catch (Exception e) {
                    Log.e(TAG, "freezeRotation error");
                }
            }
        } else if (preference == mAuxScreenVH) {
            mEnableDisplayListener = false;
            showWaitingDialog(R.string.dialog_wait_screen_connect);
            if ((Boolean) obj) {
                SystemProperties.set("persist.sys.rotation.efull", "true");
            } else {
                SystemProperties.set("persist.sys.rotation.efull", "false");
            }
            sendSwitchDeviceOffOnMsg(ITEM_CONTROL.REFRESH_AUX_INFO, SWITCH_STATUS_OFF_ON);
        } else if (preference == mAuxScreenVHList) {
            mEnableDisplayListener = false;
            showWaitingDialog(R.string.dialog_wait_screen_connect);
            SystemProperties.set("persist.sys.rotation.einit", obj.toString());
            //mDisplayManager.forceScheduleTraversalLocked();
            sendSwitchDeviceOffOnMsg(ITEM_CONTROL.REFRESH_AUX_INFO, SWITCH_STATUS_OFF_ON);
        }
        return true;
    }

    public static boolean isAvailable() {
        return "true".equals(SystemProperties.get("ro.vendor.hdmi_settings"));
    }

    private void refreshState() {
        Log.v(TAG, "refreshState");
        if (mShowSettings != ONLY_SHOW_AUX) {
            showWaitingDialog(R.string.dialog_getting_screen_info);
            sendUpdateStateMsg(ITEM_CONTROL.REFRESH_MAIN_INFO, 1000);
        }
        if (mShowSettings != ONLY_SHOW_MAIN) {
            showWaitingDialog(R.string.dialog_getting_screen_info);
            sendUpdateStateMsg(ITEM_CONTROL.REFRESH_AUX_INFO, 1000);
        }
    }

    class DisplayListener implements DisplayManager.DisplayListener {
        @Override
        public void onDisplayAdded(int displayId) {
            Log.v(TAG, "onDisplayAdded displayId=" + displayId);
            if (mEnableDisplayListener) {
                refreshState();
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
            Log.v(TAG, "onDisplayChanged displayId=" + displayId);
            //refreshState();
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            Log.v(TAG, "onDisplayRemoved displayId=" + displayId);
            if (mEnableDisplayListener) {
                refreshState();
            }
        }
    }
}
