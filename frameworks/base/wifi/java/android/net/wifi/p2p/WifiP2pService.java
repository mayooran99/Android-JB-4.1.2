/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.net.wifi.p2p;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.IConnectivityManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfoInternal;
import android.net.DhcpStateMachine;
import android.net.InterfaceConfiguration;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.NetworkUtils;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiMonitor;
import android.net.wifi.WifiNative;
import android.net.wifi.WifiStateMachine;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pServiceRequest;
import android.net.wifi.p2p.nsd.WifiP2pServiceResponse;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.Parcelable.Creator;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

/**
 * WifiP2pService includes a state machine to perform Wi-Fi p2p operations. Applications
 * communicate with this service to issue device discovery and connectivity requests
 * through the WifiP2pManager interface. The state machine communicates with the wifi
 * driver through wpa_supplicant and handles the event responses through WifiMonitor.
 *
 * Note that the term Wifi when used without a p2p suffix refers to the client mode
 * of Wifi operation
 * @hide
 */
public class WifiP2pService extends IWifiP2pManager.Stub {
    private static final String TAG = "WifiP2pService";
    private static final boolean DBG = false;
    private static final String NETWORKTYPE = "WIFI_P2P";

    private Context mContext;
    private String mInterface;
    private Notification mNotification;

    INetworkManagementService mNwService;
    private DhcpStateMachine mDhcpStateMachine;

    private ActivityManager mActivityMgr;

    private P2pStateMachine mP2pStateMachine;
    private AsyncChannel mReplyChannel = new AsyncChannel();
    private AsyncChannel mWifiChannel;

    private static final Boolean JOIN_GROUP = true;
    private static final Boolean FORM_GROUP = false;

    /* Two minutes comes from the wpa_supplicant setting */
    private static final int GROUP_CREATING_WAIT_TIME_MS = 120 * 1000;
    private static int mGroupCreatingTimeoutIndex = 0;

    /* Set a two minute discover timeout to avoid STA scans from being blocked */
    private static final int DISCOVER_TIMEOUT_S = 120;

    /* Idle time after a peer is gone when the group is torn down */
    private static final int GROUP_IDLE_TIME_S = 2;

    /**
     * Delay between restarts upon failure to setup connection with supplicant
     */
    private static final int P2P_RESTART_INTERVAL_MSECS = 5000;

    /**
     * Number of times we attempt to restart p2p
     */
    private static final int P2P_RESTART_TRIES = 5;

    private int mP2pRestartCount = 0;

    private static final int BASE = Protocol.BASE_WIFI_P2P_SERVICE;

    /* Delayed message to timeout group creation */
    public static final int GROUP_CREATING_TIMED_OUT        =   BASE + 1;

    /* User accepted a peer request */
    private static final int PEER_CONNECTION_USER_ACCEPT    =   BASE + 2;
    /* User rejected a peer request */
    private static final int PEER_CONNECTION_USER_REJECT    =   BASE + 3;

    private final boolean mP2pSupported;

    private WifiP2pDevice mThisDevice = new WifiP2pDevice();

    /* When a group has been explicitly created by an app, we persist the group
     * even after all clients have been disconnected until an explicit remove
     * is invoked */
    private boolean mAutonomousGroup;

    /* Invitation to join an existing p2p group */
    private boolean mJoinExistingGroup;

    /* Track whether we are in p2p discovery. This is used to avoid sending duplicate
     * broadcasts
     */
    private boolean mDiscoveryStarted;

    private NetworkInfo mNetworkInfo;

    /* The transaction Id of service discovery request */
    private byte mServiceTransactionId = 0;

    /* Service discovery request ID of wpa_supplicant.
     * null means it's not set yet. */
    private String mServiceDiscReqId;

    /* clients(application) information list. */
    private HashMap<Messenger, ClientInfo> mClientInfoList = new HashMap<Messenger, ClientInfo>();

    /* The foreground application's messenger.
     * The connection request is notified only to foreground application  */
    private Messenger mForegroundAppMessenger;

    /* the package name of foreground application. */
    private String mForegroundAppPkgName;

    /* Is chosen as a unique range to avoid conflict with
       the range defined in Tethering.java */
    private static final String[] DHCP_RANGE = {"192.168.49.2", "192.168.49.254"};
    private static final String SERVER_ADDRESS = "192.168.49.1";

    public WifiP2pService(Context context) {
        mContext = context;

        //STOPSHIP: get this from native side
        mInterface = "p2p0";
        mActivityMgr = (ActivityManager)context.getSystemService(Activity.ACTIVITY_SERVICE);

        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_WIFI_P2P, 0, NETWORKTYPE, "");

        mP2pSupported = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WIFI_DIRECT);

        mThisDevice.primaryDeviceType = mContext.getResources().getString(
                com.android.internal.R.string.config_wifi_p2p_device_type);

        mP2pStateMachine = new P2pStateMachine(TAG, mP2pSupported);
        mP2pStateMachine.start();
    }

    public void connectivityServiceReady() {
        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNwService = INetworkManagementService.Stub.asInterface(b);
    }

    private void enforceAccessPermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.ACCESS_WIFI_STATE,
                "WifiP2pService");
    }

    private void enforceChangePermission() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.CHANGE_WIFI_STATE,
                "WifiP2pService");
    }

    /**
     * Get a reference to handler. This is used by a client to establish
     * an AsyncChannel communication with WifiP2pService
     */
    public Messenger getMessenger() {
        enforceAccessPermission();
        enforceChangePermission();
        return new Messenger(mP2pStateMachine.getHandler());
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump WifiP2pService from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }
    }


    /**
     * Handles interaction with WifiStateMachine
     */
    private class P2pStateMachine extends StateMachine {

        private DefaultState mDefaultState = new DefaultState();
        private P2pNotSupportedState mP2pNotSupportedState = new P2pNotSupportedState();
        private P2pDisablingState mP2pDisablingState = new P2pDisablingState();
        private P2pDisabledState mP2pDisabledState = new P2pDisabledState();
        private P2pEnablingState mP2pEnablingState = new P2pEnablingState();
        private P2pEnabledState mP2pEnabledState = new P2pEnabledState();
        // Inactive is when p2p is enabled with no connectivity
        private InactiveState mInactiveState = new InactiveState();
        private GroupCreatingState mGroupCreatingState = new GroupCreatingState();
        private UserAuthorizingInvitationState mUserAuthorizingInvitationState
                = new UserAuthorizingInvitationState();
        private ProvisionDiscoveryState mProvisionDiscoveryState = new ProvisionDiscoveryState();
        private GroupNegotiationState mGroupNegotiationState = new GroupNegotiationState();

        private GroupCreatedState mGroupCreatedState = new GroupCreatedState();
        private UserAuthorizingJoinState mUserAuthorizingJoinState = new UserAuthorizingJoinState();

        private WifiNative mWifiNative = new WifiNative(mInterface);
        private WifiMonitor mWifiMonitor = new WifiMonitor(this, mWifiNative);

        private WifiP2pDeviceList mPeers = new WifiP2pDeviceList();
        private WifiP2pInfo mWifiP2pInfo = new WifiP2pInfo();
        private WifiP2pGroup mGroup;

        // Saved WifiP2pConfig for a peer connection
        private WifiP2pConfig mSavedPeerConfig;

        // Saved WifiP2pGroup from invitation request
        private WifiP2pGroup mSavedP2pGroup;

        // Saved WifiP2pDevice from provisioning request
        private WifiP2pDevice mSavedProvDiscDevice;

        P2pStateMachine(String name, boolean p2pSupported) {
            super(name);

            addState(mDefaultState);
                addState(mP2pNotSupportedState, mDefaultState);
                addState(mP2pDisablingState, mDefaultState);
                addState(mP2pDisabledState, mDefaultState);
                addState(mP2pEnablingState, mDefaultState);
                addState(mP2pEnabledState, mDefaultState);
                    addState(mInactiveState, mP2pEnabledState);
                    addState(mGroupCreatingState, mP2pEnabledState);
                        addState(mUserAuthorizingInvitationState, mGroupCreatingState);
                        addState(mProvisionDiscoveryState, mGroupCreatingState);
                        addState(mGroupNegotiationState, mGroupCreatingState);
                    addState(mGroupCreatedState, mP2pEnabledState);
                        addState(mUserAuthorizingJoinState, mGroupCreatedState);

            if (p2pSupported) {
                setInitialState(mP2pDisabledState);
            } else {
                setInitialState(mP2pNotSupportedState);
            }
        }

    class DefaultState extends State {
        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (message.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        if (DBG) logd("Full connection with WifiStateMachine established");
                        mWifiChannel = (AsyncChannel) message.obj;
                    } else {
                        loge("Full connection failure, error = " + message.arg1);
                        mWifiChannel = null;
                    }
                    break;

                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    if (message.arg1 == AsyncChannel.STATUS_SEND_UNSUCCESSFUL) {
                        loge("Send failed, client connection lost");
                    } else {
                        loge("Client connection lost with reason: " + message.arg1);
                    }
                    mWifiChannel = null;
                    break;

                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION:
                    AsyncChannel ac = new AsyncChannel();
                    ac.connect(mContext, getHandler(), message.replyTo);
                    break;
                case WifiP2pManager.DISCOVER_PEERS:
                    replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.STOP_DISCOVERY:
                    replyToMessage(message, WifiP2pManager.STOP_DISCOVERY_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.DISCOVER_SERVICES:
                    replyToMessage(message, WifiP2pManager.DISCOVER_SERVICES_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.CONNECT:
                    replyToMessage(message, WifiP2pManager.CONNECT_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.CANCEL_CONNECT:
                    replyToMessage(message, WifiP2pManager.CANCEL_CONNECT_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.CREATE_GROUP:
                    replyToMessage(message, WifiP2pManager.CREATE_GROUP_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.REMOVE_GROUP:
                    replyToMessage(message, WifiP2pManager.REMOVE_GROUP_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.ADD_LOCAL_SERVICE:
                    replyToMessage(message, WifiP2pManager.ADD_LOCAL_SERVICE_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.REMOVE_LOCAL_SERVICE:
                    replyToMessage(message, WifiP2pManager.REMOVE_LOCAL_SERVICE_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.CLEAR_LOCAL_SERVICES:
                    replyToMessage(message, WifiP2pManager.CLEAR_LOCAL_SERVICES_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.ADD_SERVICE_REQUEST:
                    replyToMessage(message, WifiP2pManager.ADD_SERVICE_REQUEST_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.REMOVE_SERVICE_REQUEST:
                    replyToMessage(message,
                            WifiP2pManager.REMOVE_SERVICE_REQUEST_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.CLEAR_SERVICE_REQUESTS:
                    replyToMessage(message,
                            WifiP2pManager.CLEAR_SERVICE_REQUESTS_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.SET_DEVICE_NAME:
                    replyToMessage(message, WifiP2pManager.SET_DEVICE_NAME_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.REQUEST_PEERS:
                    replyToMessage(message, WifiP2pManager.RESPONSE_PEERS, mPeers);
                    break;
                case WifiP2pManager.REQUEST_CONNECTION_INFO:
                    replyToMessage(message, WifiP2pManager.RESPONSE_CONNECTION_INFO, mWifiP2pInfo);
                    break;
                case WifiP2pManager.REQUEST_GROUP_INFO:
                    replyToMessage(message, WifiP2pManager.RESPONSE_GROUP_INFO, mGroup);
                    break;
                case WifiP2pManager.SET_DIALOG_LISTENER:
                    String appPkgName = (String)message.getData().getString(
                            WifiP2pManager.APP_PKG_BUNDLE_KEY);
                    boolean isReset = message.getData().getBoolean(
                            WifiP2pManager.RESET_DIALOG_LISTENER_BUNDLE_KEY);
                    if (setDialogListenerApp(message.replyTo, appPkgName, isReset)) {
                        replyToMessage(message, WifiP2pManager.DIALOG_LISTENER_ATTACHED);
                    } else {
                        replyToMessage(message, WifiP2pManager.DIALOG_LISTENER_DETACHED,
                                WifiP2pManager.NOT_IN_FOREGROUND);
                    }
                    break;
                    // Ignore
                case WifiMonitor.P2P_INVITATION_RESULT_EVENT:
                case WifiMonitor.SCAN_RESULTS_EVENT:
                case WifiMonitor.SUP_CONNECTION_EVENT:
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                case WifiMonitor.NETWORK_CONNECTION_EVENT:
                case WifiMonitor.NETWORK_DISCONNECTION_EVENT:
                case WifiMonitor.SUPPLICANT_STATE_CHANGE_EVENT:
                case WifiMonitor.P2P_GROUP_REMOVED_EVENT:
                case WifiMonitor.P2P_DEVICE_FOUND_EVENT:
                case WifiMonitor.P2P_DEVICE_LOST_EVENT:
                case WifiMonitor.P2P_FIND_STOPPED_EVENT:
                case WifiMonitor.P2P_SERV_DISC_RESP_EVENT:
                case WifiStateMachine.CMD_ENABLE_P2P:
                case WifiStateMachine.CMD_DISABLE_P2P:
                case PEER_CONNECTION_USER_ACCEPT:
                case PEER_CONNECTION_USER_REJECT:
                case GROUP_CREATING_TIMED_OUT:
                    break;
                    /* unexpected group created, remove */
                case WifiMonitor.P2P_GROUP_STARTED_EVENT:
                    mGroup = (WifiP2pGroup) message.obj;
                    loge("Unexpected group creation, remove " + mGroup);
                    mWifiNative.p2pGroupRemove(mGroup.getInterface());
                    break;
                // A group formation failure is always followed by
                // a group removed event. Flushing things at group formation
                // failure causes supplicant issues. Ignore right now.
                case WifiMonitor.P2P_GROUP_FORMATION_FAILURE_EVENT:
                    break;
                default:
                    loge("Unhandled message " + message);
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class P2pNotSupportedState extends State {
        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
               case WifiP2pManager.DISCOVER_PEERS:
                    replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.STOP_DISCOVERY:
                    replyToMessage(message, WifiP2pManager.STOP_DISCOVERY_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.DISCOVER_SERVICES:
                    replyToMessage(message, WifiP2pManager.DISCOVER_SERVICES_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.CONNECT:
                    replyToMessage(message, WifiP2pManager.CONNECT_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.CANCEL_CONNECT:
                    replyToMessage(message, WifiP2pManager.CANCEL_CONNECT_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
               case WifiP2pManager.CREATE_GROUP:
                    replyToMessage(message, WifiP2pManager.CREATE_GROUP_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.REMOVE_GROUP:
                    replyToMessage(message, WifiP2pManager.REMOVE_GROUP_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.ADD_LOCAL_SERVICE:
                    replyToMessage(message, WifiP2pManager.ADD_LOCAL_SERVICE_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.SET_DIALOG_LISTENER:
                    replyToMessage(message, WifiP2pManager.DIALOG_LISTENER_DETACHED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.REMOVE_LOCAL_SERVICE:
                    replyToMessage(message, WifiP2pManager.REMOVE_LOCAL_SERVICE_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.CLEAR_LOCAL_SERVICES:
                    replyToMessage(message, WifiP2pManager.CLEAR_LOCAL_SERVICES_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.ADD_SERVICE_REQUEST:
                    replyToMessage(message, WifiP2pManager.ADD_SERVICE_REQUEST_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.REMOVE_SERVICE_REQUEST:
                    replyToMessage(message,
                            WifiP2pManager.REMOVE_SERVICE_REQUEST_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.CLEAR_SERVICE_REQUESTS:
                    replyToMessage(message,
                            WifiP2pManager.CLEAR_SERVICE_REQUESTS_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
                case WifiP2pManager.SET_DEVICE_NAME:
                    replyToMessage(message, WifiP2pManager.SET_DEVICE_NAME_FAILED,
                            WifiP2pManager.P2P_UNSUPPORTED);
                    break;
               default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class P2pDisablingState extends State {
        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    if (DBG) logd("p2p socket connection lost");
                    transitionTo(mP2pDisabledState);
                    break;
                case WifiStateMachine.CMD_ENABLE_P2P:
                case WifiStateMachine.CMD_DISABLE_P2P:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class P2pDisabledState extends State {
       @Override
        public void enter() {
            if (DBG) logd(getName());
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiStateMachine.CMD_ENABLE_P2P:
                    try {
                        mNwService.setInterfaceUp(mInterface);
                    } catch (RemoteException re) {
                        loge("Unable to change interface settings: " + re);
                    } catch (IllegalStateException ie) {
                        loge("Unable to change interface settings: " + ie);
                    }
                    mWifiMonitor.startMonitoring();
                    transitionTo(mP2pEnablingState);
                    break;
                case WifiStateMachine.CMD_DISABLE_P2P:
                    //Nothing to do
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class P2pEnablingState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiMonitor.SUP_CONNECTION_EVENT:
                    if (DBG) logd("P2p socket connection successful");
                    transitionTo(mInactiveState);
                    break;
                case WifiMonitor.SUP_DISCONNECTION_EVENT:
                    loge("P2p socket connection failed");
                    transitionTo(mP2pDisabledState);
                    break;
                case WifiStateMachine.CMD_ENABLE_P2P:
                case WifiStateMachine.CMD_DISABLE_P2P:
                    deferMessage(message);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class P2pEnabledState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            sendP2pStateChangedBroadcast(true);
            mNetworkInfo.setIsAvailable(true);
            sendP2pConnectionChangedBroadcast();
            initializeP2pSettings();
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiStateMachine.CMD_ENABLE_P2P:
                    //Nothing to do
                    break;
                case WifiStateMachine.CMD_DISABLE_P2P:
                    if (mPeers.clear()) sendP2pPeersChangedBroadcast();
                    mWifiNative.closeSupplicantConnection();
                    transitionTo(mP2pDisablingState);
                    break;
                case WifiP2pManager.SET_DEVICE_NAME:
                    WifiP2pDevice d = (WifiP2pDevice) message.obj;
                    if (d != null && setAndPersistDeviceName(d.deviceName)) {
                        if (DBG) logd("set device name " + d.deviceName);
                        replyToMessage(message, WifiP2pManager.SET_DEVICE_NAME_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.SET_DEVICE_NAME_FAILED,
                                WifiP2pManager.ERROR);
                    }
                    break;
                case WifiP2pManager.DISCOVER_PEERS:
                    // do not send service discovery request while normal find operation.
                    clearSupplicantServiceRequest();
                    if (mWifiNative.p2pFind(DISCOVER_TIMEOUT_S)) {
                        replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_SUCCEEDED);
                        sendP2pDiscoveryChangedBroadcast(true);
                    } else {
                        replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                                WifiP2pManager.ERROR);
                    }
                    break;
                case WifiMonitor.P2P_FIND_STOPPED_EVENT:
                    sendP2pDiscoveryChangedBroadcast(false);
                    break;
                case WifiP2pManager.STOP_DISCOVERY:
                    if (mWifiNative.p2pStopFind()) {
                        replyToMessage(message, WifiP2pManager.STOP_DISCOVERY_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.STOP_DISCOVERY_FAILED,
                                WifiP2pManager.ERROR);
                    }
                    break;
                case WifiP2pManager.DISCOVER_SERVICES:
                    if (DBG) logd(getName() + " discover services");
                    if (!updateSupplicantServiceRequest()) {
                        replyToMessage(message, WifiP2pManager.DISCOVER_SERVICES_FAILED,
                                WifiP2pManager.NO_SERVICE_REQUESTS);
                        break;
                    }
                    if (mWifiNative.p2pFind(DISCOVER_TIMEOUT_S)) {
                        replyToMessage(message, WifiP2pManager.DISCOVER_SERVICES_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.DISCOVER_SERVICES_FAILED,
                                WifiP2pManager.ERROR);
                    }
                    break;
                case WifiMonitor.P2P_DEVICE_FOUND_EVENT:
                    WifiP2pDevice device = (WifiP2pDevice) message.obj;
                    if (mThisDevice.deviceAddress.equals(device.deviceAddress)) break;
                    mPeers.update(device);
                    sendP2pPeersChangedBroadcast();
                    break;
                case WifiMonitor.P2P_DEVICE_LOST_EVENT:
                    device = (WifiP2pDevice) message.obj;
                    if (mPeers.remove(device)) sendP2pPeersChangedBroadcast();
                    break;
               case WifiP2pManager.ADD_LOCAL_SERVICE:
                    if (DBG) logd(getName() + " add service");
                    WifiP2pServiceInfo servInfo = (WifiP2pServiceInfo)message.obj;
                    if (addLocalService(message.replyTo, servInfo)) {
                        replyToMessage(message, WifiP2pManager.ADD_LOCAL_SERVICE_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.ADD_LOCAL_SERVICE_FAILED);
                    }
                    break;
                case WifiP2pManager.REMOVE_LOCAL_SERVICE:
                    if (DBG) logd(getName() + " remove service");
                    servInfo = (WifiP2pServiceInfo)message.obj;
                    removeLocalService(message.replyTo, servInfo);
                    replyToMessage(message, WifiP2pManager.REMOVE_LOCAL_SERVICE_SUCCEEDED);
                    break;
                case WifiP2pManager.CLEAR_LOCAL_SERVICES:
                    if (DBG) logd(getName() + " clear service");
                    clearLocalServices(message.replyTo);
                    replyToMessage(message, WifiP2pManager.CLEAR_LOCAL_SERVICES_SUCCEEDED);
                    break;
                case WifiP2pManager.ADD_SERVICE_REQUEST:
                    if (DBG) logd(getName() + " add service request");
                    if (!addServiceRequest(message.replyTo, (WifiP2pServiceRequest)message.obj)) {
                        replyToMessage(message, WifiP2pManager.ADD_SERVICE_REQUEST_FAILED);
                        break;
                    }
                    replyToMessage(message, WifiP2pManager.ADD_SERVICE_REQUEST_SUCCEEDED);
                    break;
                case WifiP2pManager.REMOVE_SERVICE_REQUEST:
                    if (DBG) logd(getName() + " remove service request");
                    removeServiceRequest(message.replyTo, (WifiP2pServiceRequest)message.obj);
                    replyToMessage(message, WifiP2pManager.REMOVE_SERVICE_REQUEST_SUCCEEDED);
                    break;
                case WifiP2pManager.CLEAR_SERVICE_REQUESTS:
                    if (DBG) logd(getName() + " clear service request");
                    clearServiceRequests(message.replyTo);
                    replyToMessage(message, WifiP2pManager.CLEAR_SERVICE_REQUESTS_SUCCEEDED);
                    break;
               case WifiMonitor.P2P_SERV_DISC_RESP_EVENT:
                    if (DBG) logd(getName() + " receive service response");
                    List<WifiP2pServiceResponse> sdRespList =
                        (List<WifiP2pServiceResponse>) message.obj;
                    for (WifiP2pServiceResponse resp : sdRespList) {
                        WifiP2pDevice dev =
                            mPeers.get(resp.getSrcDevice().deviceAddress);
                        resp.setSrcDevice(dev);
                        sendServiceResponse(resp);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            sendP2pStateChangedBroadcast(false);
            mNetworkInfo.setIsAvailable(false);
        }
    }

    class InactiveState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            //Start listening every time we get inactive
            //TODO: Fix listen after driver behavior is fixed
            //mWifiNative.p2pListen();
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiP2pManager.CONNECT:
                    if (DBG) logd(getName() + " sending connect");
                    WifiP2pConfig config = (WifiP2pConfig) message.obj;
                    mAutonomousGroup = false;

                    /* Update group capability before connect */
                    int gc = mWifiNative.getGroupCapability(config.deviceAddress);
                    mPeers.updateGroupCapability(config.deviceAddress, gc);

                    if (mSavedPeerConfig != null && config.deviceAddress.equals(
                                    mSavedPeerConfig.deviceAddress)) {
                        mSavedPeerConfig = config;

                        //Stop discovery before issuing connect
                        mWifiNative.p2pStopFind();
                        if (mPeers.isGroupOwner(mSavedPeerConfig.deviceAddress)) {
                            p2pConnectWithPinDisplay(mSavedPeerConfig, JOIN_GROUP);
                        } else {
                            p2pConnectWithPinDisplay(mSavedPeerConfig, FORM_GROUP);
                        }
                        transitionTo(mGroupNegotiationState);
                    } else {
                        mSavedPeerConfig = config;
                        int netId = configuredNetworkId(mSavedPeerConfig.deviceAddress);
                        if (netId >= 0) {
                            //TODO: if failure, remove config and do a regular p2pConnect()
                            mWifiNative.p2pReinvoke(netId, mSavedPeerConfig.deviceAddress);
                        } else {
                            //Stop discovery before issuing connect
                            mWifiNative.p2pStopFind();
                            //If peer is a GO, we do not need to send provisional discovery,
                            //the supplicant takes care of it.
                            if (mPeers.isGroupOwner(mSavedPeerConfig.deviceAddress)) {
                                if (DBG) logd("Sending join to GO");
                                p2pConnectWithPinDisplay(mSavedPeerConfig, JOIN_GROUP);
                                transitionTo(mGroupNegotiationState);
                            } else {
                                if (DBG) logd("Sending prov disc");
                                transitionTo(mProvisionDiscoveryState);
                            }
                        }
                    }
                    mPeers.updateStatus(mSavedPeerConfig.deviceAddress, WifiP2pDevice.INVITED);
                    sendP2pPeersChangedBroadcast();
                    replyToMessage(message, WifiP2pManager.CONNECT_SUCCEEDED);
                    break;
                case WifiMonitor.P2P_GO_NEGOTIATION_REQUEST_EVENT:
                    mSavedPeerConfig = (WifiP2pConfig) message.obj;

                    mAutonomousGroup = false;
                    mJoinExistingGroup = false;
                    if (!sendConnectNoticeToApp(mPeers.get(mSavedPeerConfig.deviceAddress),
                            mSavedPeerConfig)) {
                        transitionTo(mUserAuthorizingInvitationState);
                    }
                    break;
                case WifiMonitor.P2P_INVITATION_RECEIVED_EVENT:
                    WifiP2pGroup group = (WifiP2pGroup) message.obj;
                    WifiP2pDevice owner = group.getOwner();

                    if (owner == null) {
                        if (DBG) loge("Ignored invitation from null owner");
                        break;
                    }

                    mSavedPeerConfig = new WifiP2pConfig();
                    mSavedPeerConfig.deviceAddress = group.getOwner().deviceAddress;

                    //Check if we have the owner in peer list and use appropriate
                    //wps method. Default is to use PBC.
                    if ((owner = mPeers.get(owner.deviceAddress)) != null) {
                        if (owner.wpsPbcSupported()) {
                            mSavedPeerConfig.wps.setup = WpsInfo.PBC;
                        } else if (owner.wpsKeypadSupported()) {
                            mSavedPeerConfig.wps.setup = WpsInfo.KEYPAD;
                        } else if (owner.wpsDisplaySupported()) {
                            mSavedPeerConfig.wps.setup = WpsInfo.DISPLAY;
                        }
                    }

                    mAutonomousGroup = false;
                    mJoinExistingGroup = true;
                    //TODO In the p2p client case, we should set source address correctly.
                    if (!sendConnectNoticeToApp(mPeers.get(mSavedPeerConfig.deviceAddress),
                            mSavedPeerConfig)) {
                        transitionTo(mUserAuthorizingInvitationState);
                    }
                    break;
                case WifiMonitor.P2P_FIND_STOPPED_EVENT:
                    // When discovery stops in inactive state, flush to clear
                    // state peer data
                    mWifiNative.p2pFlush();
                    mServiceDiscReqId = null;
                    sendP2pDiscoveryChangedBroadcast(false);
                    break;
                case WifiMonitor.P2P_PROV_DISC_PBC_REQ_EVENT:
                case WifiMonitor.P2P_PROV_DISC_ENTER_PIN_EVENT:
                case WifiMonitor.P2P_PROV_DISC_SHOW_PIN_EVENT:
                    //We let the supplicant handle the provision discovery response
                    //and wait instead for the GO_NEGOTIATION_REQUEST_EVENT.
                    //Handling provision discovery and issuing a p2p_connect before
                    //group negotiation comes through causes issues
                   break;
                case WifiP2pManager.CREATE_GROUP:
                   mAutonomousGroup = true;
                   if (mWifiNative.p2pGroupAdd()) {
                        replyToMessage(message, WifiP2pManager.CREATE_GROUP_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.CREATE_GROUP_FAILED,
                                WifiP2pManager.ERROR);
                    }
                    transitionTo(mGroupNegotiationState);
                    break;
               default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class GroupCreatingState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            sendMessageDelayed(obtainMessage(GROUP_CREATING_TIMED_OUT,
                    ++mGroupCreatingTimeoutIndex, 0), GROUP_CREATING_WAIT_TIME_MS);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            boolean ret = HANDLED;
            switch (message.what) {
               case GROUP_CREATING_TIMED_OUT:
                    if (mGroupCreatingTimeoutIndex == message.arg1) {
                        if (DBG) logd("Group negotiation timed out");
                        handleGroupCreationFailure();
                        transitionTo(mInactiveState);
                    }
                    break;
                case WifiMonitor.P2P_DEVICE_LOST_EVENT:
                    WifiP2pDevice device = (WifiP2pDevice) message.obj;

                    // If we lose a device during an autonomous group creation,
                    // mSavedPeerConfig can be empty
                    if (mSavedPeerConfig != null &&
                            !mSavedPeerConfig.deviceAddress.equals(device.deviceAddress)) {
                        // Do the regular device lost handling
                        ret = NOT_HANDLED;
                        break;
                    }
                    // Do nothing
                    if (DBG) logd("Retain connecting device " + device);
                    break;
                case WifiP2pManager.DISCOVER_PEERS:
                    /* Discovery will break negotiation */
                    replyToMessage(message, WifiP2pManager.DISCOVER_PEERS_FAILED,
                            WifiP2pManager.BUSY);
                    break;
                case WifiP2pManager.CANCEL_CONNECT:
                    //Do a supplicant p2p_cancel which only cancels an ongoing
                    //group negotiation. This will fail for a pending provision
                    //discovery or for a pending user action, but at the framework
                    //level, we always treat cancel as succeeded and enter
                    //an inactive state
                    mWifiNative.p2pCancelConnect();
                    handleGroupCreationFailure();
                    transitionTo(mInactiveState);
                    replyToMessage(message, WifiP2pManager.CANCEL_CONNECT_SUCCEEDED);
                    break;
                default:
                    ret = NOT_HANDLED;
            }
            return ret;
        }
    }

    class UserAuthorizingInvitationState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            if (!sendConnectNoticeToApp(mPeers.get(mSavedPeerConfig.deviceAddress),
                    mSavedPeerConfig)) {
                notifyInvitationReceived();
            }
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            boolean ret = HANDLED;
            switch (message.what) {
                case PEER_CONNECTION_USER_ACCEPT:
                    //TODO: handle persistence
                    if (mJoinExistingGroup) {
                        p2pConnectWithPinDisplay(mSavedPeerConfig, JOIN_GROUP);
                    } else {
                        p2pConnectWithPinDisplay(mSavedPeerConfig, FORM_GROUP);
                    }
                    mPeers.updateStatus(mSavedPeerConfig.deviceAddress, WifiP2pDevice.INVITED);
                    sendP2pPeersChangedBroadcast();
                    transitionTo(mGroupNegotiationState);
                    break;
                case PEER_CONNECTION_USER_REJECT:
                    if (DBG) logd("User rejected invitation " + mSavedPeerConfig);
                    mSavedPeerConfig = null;
                    transitionTo(mInactiveState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return ret;
        }

        @Override
        public void exit() {
            //TODO: dismiss dialog if not already done
        }
    }

    class ProvisionDiscoveryState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            mWifiNative.p2pProvisionDiscovery(mSavedPeerConfig);
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            WifiP2pProvDiscEvent provDisc;
            WifiP2pDevice device;
            switch (message.what) {
                case WifiMonitor.P2P_PROV_DISC_PBC_RSP_EVENT:
                    provDisc = (WifiP2pProvDiscEvent) message.obj;
                    device = provDisc.device;
                    if (!device.deviceAddress.equals(mSavedPeerConfig.deviceAddress)) break;

                    if (mSavedPeerConfig.wps.setup == WpsInfo.PBC) {
                        if (DBG) logd("Found a match " + mSavedPeerConfig);
                        mWifiNative.p2pConnect(mSavedPeerConfig, FORM_GROUP);
                        transitionTo(mGroupNegotiationState);
                    }
                    break;
                case WifiMonitor.P2P_PROV_DISC_ENTER_PIN_EVENT:
                    provDisc = (WifiP2pProvDiscEvent) message.obj;
                    device = provDisc.device;
                    if (!device.deviceAddress.equals(mSavedPeerConfig.deviceAddress)) break;

                    if (mSavedPeerConfig.wps.setup == WpsInfo.KEYPAD) {
                        if (DBG) logd("Found a match " + mSavedPeerConfig);
                        /* we already have the pin */
                        if (!TextUtils.isEmpty(mSavedPeerConfig.wps.pin)) {
                            mWifiNative.p2pConnect(mSavedPeerConfig, FORM_GROUP);
                            transitionTo(mGroupNegotiationState);
                        } else {
                            mJoinExistingGroup = false;
                            transitionTo(mUserAuthorizingInvitationState);
                        }
                    }
                    break;
                case WifiMonitor.P2P_PROV_DISC_SHOW_PIN_EVENT:
                    provDisc = (WifiP2pProvDiscEvent) message.obj;
                    device = provDisc.device;
                    if (!device.deviceAddress.equals(mSavedPeerConfig.deviceAddress)) break;

                    if (mSavedPeerConfig.wps.setup == WpsInfo.DISPLAY) {
                        if (DBG) logd("Found a match " + mSavedPeerConfig);
                        mSavedPeerConfig.wps.pin = provDisc.pin;
                        mWifiNative.p2pConnect(mSavedPeerConfig, FORM_GROUP);
                        if (!sendShowPinReqToFrontApp(provDisc.pin)) {
                            notifyInvitationSent(provDisc.pin, device.deviceAddress);
                        }
                        transitionTo(mGroupNegotiationState);
                    }
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }

    class GroupNegotiationState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                // We ignore these right now, since we get a GROUP_STARTED notification
                // afterwards
                case WifiMonitor.P2P_GO_NEGOTIATION_SUCCESS_EVENT:
                case WifiMonitor.P2P_GROUP_FORMATION_SUCCESS_EVENT:
                    if (DBG) logd(getName() + " go success");
                    break;
                case WifiMonitor.P2P_GROUP_STARTED_EVENT:
                    mGroup = (WifiP2pGroup) message.obj;
                    if (DBG) logd(getName() + " group started");
                    if (mGroup.isGroupOwner()) {
                        startDhcpServer(mGroup.getInterface());
                    } else {
                        // Set group idle only for a client on the group interface to speed up
                        // disconnect when GO is gone. Setting group idle time for a group owner
                        // causes connectivity issues for new clients
                        mWifiNative.setP2pGroupIdle(mGroup.getInterface(), GROUP_IDLE_TIME_S);
                        mDhcpStateMachine = DhcpStateMachine.makeDhcpStateMachine(mContext,
                                P2pStateMachine.this, mGroup.getInterface());
                        mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_START_DHCP);
                        WifiP2pDevice groupOwner = mGroup.getOwner();
                        mPeers.updateStatus(groupOwner.deviceAddress, WifiP2pDevice.CONNECTED);
                        sendP2pPeersChangedBroadcast();
                    }
                    mSavedPeerConfig = null;
                    transitionTo(mGroupCreatedState);
                    break;
                case WifiMonitor.P2P_GO_NEGOTIATION_FAILURE_EVENT:
                case WifiMonitor.P2P_GROUP_REMOVED_EVENT:
                    if (DBG) logd(getName() + " go failure");
                    handleGroupCreationFailure();
                    transitionTo(mInactiveState);
                    break;
                // A group formation failure is always followed by
                // a group removed event. Flushing things at group formation
                // failure causes supplicant issues. Ignore right now.
                case WifiMonitor.P2P_GROUP_FORMATION_FAILURE_EVENT:
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }
    }



    class GroupCreatedState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());
            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, null, null);

            updateThisDevice(WifiP2pDevice.CONNECTED);

            //DHCP server has already been started if I am a group owner
            if (mGroup.isGroupOwner()) {
                setWifiP2pInfoOnGroupFormation(SERVER_ADDRESS);
                sendP2pConnectionChangedBroadcast();
            }
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiMonitor.AP_STA_CONNECTED_EVENT:
                    WifiP2pDevice device = (WifiP2pDevice) message.obj;
                    String deviceAddress = device.deviceAddress;
                    if (deviceAddress != null) {
                        if (mSavedProvDiscDevice != null &&
                                deviceAddress.equals(mSavedProvDiscDevice.deviceAddress)) {
                            mSavedProvDiscDevice = null;
                        }
                        mGroup.addClient(deviceAddress);
                        mPeers.updateStatus(deviceAddress, WifiP2pDevice.CONNECTED);
                        if (DBG) logd(getName() + " ap sta connected");
                        sendP2pPeersChangedBroadcast();
                    } else {
                        loge("Connect on null device address, ignore");
                    }
                    break;
                case WifiMonitor.AP_STA_DISCONNECTED_EVENT:
                    device = (WifiP2pDevice) message.obj;
                    deviceAddress = device.deviceAddress;
                    if (deviceAddress != null) {
                        mPeers.updateStatus(deviceAddress, WifiP2pDevice.AVAILABLE);
                        if (mGroup.removeClient(deviceAddress)) {
                            if (DBG) logd("Removed client " + deviceAddress);
                            if (!mAutonomousGroup && mGroup.isClientListEmpty()) {
                                Slog.d(TAG, "Client list empty, remove non-persistent p2p group");
                                mWifiNative.p2pGroupRemove(mGroup.getInterface());
                            }
                        } else {
                            if (DBG) logd("Failed to remove client " + deviceAddress);
                            for (WifiP2pDevice c : mGroup.getClientList()) {
                                if (DBG) logd("client " + c.deviceAddress);
                            }
                        }
                        sendP2pPeersChangedBroadcast();
                        if (DBG) loge(getName() + " ap sta disconnected");
                    } else {
                        loge("Disconnect on unknown device: " + device);
                    }
                    break;
                case DhcpStateMachine.CMD_POST_DHCP_ACTION:
                    DhcpInfoInternal dhcpInfo = (DhcpInfoInternal) message.obj;
                    if (message.arg1 == DhcpStateMachine.DHCP_SUCCESS &&
                            dhcpInfo != null) {
                        if (DBG) logd("DhcpInfo: " + dhcpInfo);
                        setWifiP2pInfoOnGroupFormation(dhcpInfo.serverAddress);
                        sendP2pConnectionChangedBroadcast();
                        //Turn on power save on client
                        mWifiNative.setP2pPowerSave(mGroup.getInterface(), true);
                    } else {
                        loge("DHCP failed");
                        mWifiNative.p2pGroupRemove(mGroup.getInterface());
                    }
                    break;
                case WifiP2pManager.REMOVE_GROUP:
                    if (DBG) loge(getName() + " remove group");
                    if (mWifiNative.p2pGroupRemove(mGroup.getInterface())) {
                        replyToMessage(message, WifiP2pManager.REMOVE_GROUP_SUCCEEDED);
                    } else {
                        replyToMessage(message, WifiP2pManager.REMOVE_GROUP_FAILED,
                                WifiP2pManager.ERROR);
                    }
                    break;
                case WifiMonitor.P2P_GROUP_REMOVED_EVENT:
                    if (DBG) loge(getName() + " group removed");
                    Collection <WifiP2pDevice> devices = mGroup.getClientList();
                    boolean changed = false;
                    for (WifiP2pDevice d : mPeers.getDeviceList()) {
                        if (devices.contains(d) || mGroup.getOwner().equals(d)) {
                            d.status = WifiP2pDevice.AVAILABLE;
                            changed = true;
                        }
                    }

                    if (mGroup.isGroupOwner()) {
                        stopDhcpServer();
                    } else {
                        if (DBG) logd("stop DHCP client");
                        mDhcpStateMachine.sendMessage(DhcpStateMachine.CMD_STOP_DHCP);
                        mDhcpStateMachine.quit();
                        mDhcpStateMachine = null;
                    }

                    mGroup = null;
                    mWifiNative.p2pFlush();
                    mServiceDiscReqId = null;
                    if (changed) sendP2pPeersChangedBroadcast();
                    transitionTo(mInactiveState);
                    break;
                case WifiMonitor.P2P_DEVICE_LOST_EVENT:
                    device = (WifiP2pDevice) message.obj;
                    //Device loss for a connected device indicates it is not in discovery any more
                    if (mGroup.contains(device)) {
                        if (DBG) logd("Lost " + device +" , do nothing");
                        return HANDLED;
                    }
                    // Do the regular device lost handling
                    return NOT_HANDLED;
                case WifiStateMachine.CMD_DISABLE_P2P:
                    sendMessage(WifiP2pManager.REMOVE_GROUP);
                    deferMessage(message);
                    break;
                case WifiP2pManager.CONNECT:
                    WifiP2pConfig config = (WifiP2pConfig) message.obj;
                    if (config.deviceAddress == null ||
                            (mSavedProvDiscDevice != null &&
                                    mSavedProvDiscDevice.deviceAddress.equals(
                                            config.deviceAddress))) {
                        if (config.wps.setup == WpsInfo.PBC) {
                            mWifiNative.startWpsPbc(mGroup.getInterface(), null);
                        } else {
                            if (config.wps.pin == null) {
                                String pin = mWifiNative.startWpsPinDisplay(mGroup.getInterface());
                                try {
                                    Integer.parseInt(pin);
                                    if (!sendShowPinReqToFrontApp(pin)) {
                                        notifyInvitationSent(pin,
                                                config.deviceAddress != null ?
                                                        config.deviceAddress : "any");
                                    }
                                } catch (NumberFormatException ignore) {
                                    // do nothing if pin is invalid
                                }
                            } else {
                                mWifiNative.startWpsPinKeypad(mGroup.getInterface(),
                                        config.wps.pin);
                            }
                        }
                        if (config.deviceAddress != null) {
                            mPeers.updateStatus(config.deviceAddress, WifiP2pDevice.INVITED);
                            sendP2pPeersChangedBroadcast();
                        }
                        replyToMessage(message, WifiP2pManager.CONNECT_SUCCEEDED);
                    } else {
                        logd("Inviting device : " + config.deviceAddress);
                        if (mWifiNative.p2pInvite(mGroup, config.deviceAddress)) {
                            mPeers.updateStatus(config.deviceAddress, WifiP2pDevice.INVITED);
                            sendP2pPeersChangedBroadcast();
                            replyToMessage(message, WifiP2pManager.CONNECT_SUCCEEDED);
                        } else {
                            replyToMessage(message, WifiP2pManager.CONNECT_FAILED,
                                    WifiP2pManager.ERROR);
                        }
                    }
                    // TODO: figure out updating the status to declined when invitation is rejected
                    break;
                case WifiMonitor.P2P_PROV_DISC_PBC_REQ_EVENT:
                case WifiMonitor.P2P_PROV_DISC_ENTER_PIN_EVENT:
                case WifiMonitor.P2P_PROV_DISC_SHOW_PIN_EVENT:
                    WifiP2pProvDiscEvent provDisc = (WifiP2pProvDiscEvent) message.obj;
                    mSavedProvDiscDevice = provDisc.device;
                    mSavedPeerConfig = new WifiP2pConfig();
                    mSavedPeerConfig.deviceAddress = provDisc.device.deviceAddress;
                    if (message.what == WifiMonitor.P2P_PROV_DISC_ENTER_PIN_EVENT) {
                        mSavedPeerConfig.wps.setup = WpsInfo.KEYPAD;
                    } else if (message.what == WifiMonitor.P2P_PROV_DISC_SHOW_PIN_EVENT) {
                        mSavedPeerConfig.wps.setup = WpsInfo.DISPLAY;
                        mSavedPeerConfig.wps.pin = provDisc.pin;
                    } else {
                        mSavedPeerConfig.wps.setup = WpsInfo.PBC;
                    }
                    if (!sendConnectNoticeToApp(mSavedProvDiscDevice, mSavedPeerConfig)) {
                        transitionTo(mUserAuthorizingJoinState);
                    }
                    break;
                case WifiMonitor.P2P_GROUP_STARTED_EVENT:
                    Slog.e(TAG, "Duplicate group creation event notice, ignore");
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        public void exit() {
            mSavedProvDiscDevice = null;
            updateThisDevice(WifiP2pDevice.AVAILABLE);
            setWifiP2pInfoOnGroupTermination();
            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED, null, null);
            sendP2pConnectionChangedBroadcast();
        }
    }

    class UserAuthorizingJoinState extends State {
        @Override
        public void enter() {
            if (DBG) logd(getName());

            notifyInvitationReceived();
        }

        @Override
        public boolean processMessage(Message message) {
            if (DBG) logd(getName() + message.toString());
            switch (message.what) {
                case WifiMonitor.P2P_PROV_DISC_PBC_REQ_EVENT:
                case WifiMonitor.P2P_PROV_DISC_ENTER_PIN_EVENT:
                case WifiMonitor.P2P_PROV_DISC_SHOW_PIN_EVENT:
                    //Ignore more client requests
                    break;
                case PEER_CONNECTION_USER_ACCEPT:
                    if (mSavedPeerConfig.wps.setup == WpsInfo.PBC) {
                        mWifiNative.startWpsPbc(mGroup.getInterface(), null);
                    } else {
                        mWifiNative.startWpsPinKeypad(mGroup.getInterface(),
                                mSavedPeerConfig.wps.pin);
                    }
                    mSavedPeerConfig = null;
                    transitionTo(mGroupCreatedState);
                    break;
                case PEER_CONNECTION_USER_REJECT:
                    if (DBG) logd("User rejected incoming request");
                    mSavedPeerConfig = null;
                    transitionTo(mGroupCreatedState);
                    break;
                default:
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void exit() {
            //TODO: dismiss dialog if not already done
        }
    }

    private void sendP2pStateChangedBroadcast(boolean enabled) {
        final Intent intent = new Intent(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        if (enabled) {
            intent.putExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                    WifiP2pManager.WIFI_P2P_STATE_ENABLED);
        } else {
            intent.putExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                    WifiP2pManager.WIFI_P2P_STATE_DISABLED);
        }
        mContext.sendStickyBroadcast(intent);
    }

    private void sendP2pDiscoveryChangedBroadcast(boolean started) {
        if (mDiscoveryStarted == started) return;
        mDiscoveryStarted = started;

        if (DBG) logd("discovery change broadcast " + started);

        final Intent intent = new Intent(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, started ?
                WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED :
                WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED);
        mContext.sendStickyBroadcast(intent);
    }

    private void sendThisDeviceChangedBroadcast() {
        final Intent intent = new Intent(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, new WifiP2pDevice(mThisDevice));
        mContext.sendStickyBroadcast(intent);
    }

    private void sendP2pPeersChangedBroadcast() {
        final Intent intent = new Intent(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        mContext.sendBroadcast(intent);
    }

    private void sendP2pConnectionChangedBroadcast() {
        if (DBG) logd("sending p2p connection changed broadcast");
        Intent intent = new Intent(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, new WifiP2pInfo(mWifiP2pInfo));
        intent.putExtra(WifiP2pManager.EXTRA_NETWORK_INFO, new NetworkInfo(mNetworkInfo));
        mContext.sendStickyBroadcast(intent);
    }

    private void startDhcpServer(String intf) {
        InterfaceConfiguration ifcg = null;
        try {
            ifcg = mNwService.getInterfaceConfig(intf);
            ifcg.setLinkAddress(new LinkAddress(NetworkUtils.numericToInetAddress(
                        SERVER_ADDRESS), 24));
            ifcg.setInterfaceUp();
            mNwService.setInterfaceConfig(intf, ifcg);
            /* This starts the dnsmasq server */
            mNwService.startTethering(DHCP_RANGE);
        } catch (Exception e) {
            loge("Error configuring interface " + intf + ", :" + e);
            return;
        }

        logd("Started Dhcp server on " + intf);
   }

    private void stopDhcpServer() {
        try {
            mNwService.clearInterfaceAddresses(mInterface);
            mNwService.stopTethering();
        } catch (Exception e) {
            loge("Error stopping Dhcp server" + e);
            return;
        }

        logd("Stopped Dhcp server");
    }

    private void notifyP2pEnableFailure() {
        Resources r = Resources.getSystem();
        AlertDialog dialog = new AlertDialog.Builder(mContext)
            .setTitle(r.getString(R.string.wifi_p2p_dialog_title))
            .setMessage(r.getString(R.string.wifi_p2p_failed_message))
            .setPositiveButton(r.getString(R.string.ok), null)
            .create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();
    }

    private void addRowToDialog(ViewGroup group, int stringId, String value) {
        Resources r = Resources.getSystem();
        View row = LayoutInflater.from(mContext).inflate(R.layout.wifi_p2p_dialog_row,
                group, false);
        ((TextView) row.findViewById(R.id.name)).setText(r.getString(stringId));
        ((TextView) row.findViewById(R.id.value)).setText(value);
        group.addView(row);
    }

    private void notifyInvitationSent(String pin, String peerAddress) {
        Resources r = Resources.getSystem();

        final View textEntryView = LayoutInflater.from(mContext)
                .inflate(R.layout.wifi_p2p_dialog, null);

        ViewGroup group = (ViewGroup) textEntryView.findViewById(R.id.info);
        addRowToDialog(group, R.string.wifi_p2p_to_message, getDeviceName(peerAddress));
        addRowToDialog(group, R.string.wifi_p2p_show_pin_message, pin);

        AlertDialog dialog = new AlertDialog.Builder(mContext)
            .setTitle(r.getString(R.string.wifi_p2p_invitation_sent_title))
            .setView(textEntryView)
            .setPositiveButton(r.getString(R.string.ok), null)
            .create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();
    }

    private void notifyInvitationReceived() {
        Resources r = Resources.getSystem();
        final WpsInfo wps = mSavedPeerConfig.wps;
        final View textEntryView = LayoutInflater.from(mContext)
                .inflate(R.layout.wifi_p2p_dialog, null);

        ViewGroup group = (ViewGroup) textEntryView.findViewById(R.id.info);
        addRowToDialog(group, R.string.wifi_p2p_from_message, getDeviceName(
                mSavedPeerConfig.deviceAddress));

        final EditText pin = (EditText) textEntryView.findViewById(R.id.wifi_p2p_wps_pin);

        AlertDialog dialog = new AlertDialog.Builder(mContext)
            .setTitle(r.getString(R.string.wifi_p2p_invitation_to_connect_title))
            .setView(textEntryView)
            .setPositiveButton(r.getString(R.string.accept), new OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            if (wps.setup == WpsInfo.KEYPAD) {
                                mSavedPeerConfig.wps.pin = pin.getText().toString();
                            }
                            if (DBG) logd(getName() + " accept invitation " + mSavedPeerConfig);
                            sendMessage(PEER_CONNECTION_USER_ACCEPT);
                        }
                    })
            .setNegativeButton(r.getString(R.string.decline), new OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (DBG) logd(getName() + " ignore connect");
                            sendMessage(PEER_CONNECTION_USER_REJECT);
                        }
                    })
            .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface arg0) {
                            if (DBG) logd(getName() + " ignore connect");
                            sendMessage(PEER_CONNECTION_USER_REJECT);
                        }
                    })
            .create();

        //make the enter pin area or the display pin area visible
        switch (wps.setup) {
            case WpsInfo.KEYPAD:
                if (DBG) logd("Enter pin section visible");
                textEntryView.findViewById(R.id.enter_pin_section).setVisibility(View.VISIBLE);
                break;
            case WpsInfo.DISPLAY:
                if (DBG) logd("Shown pin section visible");
                addRowToDialog(group, R.string.wifi_p2p_show_pin_message, wps.pin);
                break;
            default:
                break;
        }

        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        dialog.show();
    }

    //TODO: implement when wpa_supplicant is fixed
    private int configuredNetworkId(String deviceAddress) {
        return -1;
    }

    private void setWifiP2pInfoOnGroupFormation(String serverAddress) {
        mWifiP2pInfo.groupFormed = true;
        mWifiP2pInfo.isGroupOwner = mGroup.isGroupOwner();
        mWifiP2pInfo.groupOwnerAddress = NetworkUtils.numericToInetAddress(serverAddress);
    }

    private void setWifiP2pInfoOnGroupTermination() {
        mWifiP2pInfo.groupFormed = false;
        mWifiP2pInfo.isGroupOwner = false;
        mWifiP2pInfo.groupOwnerAddress = null;
    }

    private String getDeviceName(String deviceAddress) {
        WifiP2pDevice d = mPeers.get(deviceAddress);
        if (d != null) {
                return d.deviceName;
        }
        //Treat the address as name if there is no match
        return deviceAddress;
    }

    private void p2pConnectWithPinDisplay(WifiP2pConfig config, boolean join) {
        String pin = mWifiNative.p2pConnect(config, join);
        try {
            Integer.parseInt(pin);
            if (!sendShowPinReqToFrontApp(pin)) {
                notifyInvitationSent(pin, config.deviceAddress);
            }
        } catch (NumberFormatException ignore) {
            // do nothing if p2pConnect did not return a pin
        }
    }

    private String getPersistedDeviceName() {
        String deviceName = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.WIFI_P2P_DEVICE_NAME);
        if (deviceName == null) {
            /* We use the 4 digits of the ANDROID_ID to have a friendly
             * default that has low likelihood of collision with a peer */
            String id = Settings.Secure.getString(mContext.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            return "Android_" + id.substring(0,4);
        }
        return deviceName;
    }

    private boolean setAndPersistDeviceName(String devName) {
        if (devName == null) return false;

        if (!mWifiNative.setDeviceName(devName)) {
            loge("Failed to set device name " + devName);
            return false;
        }

        mThisDevice.deviceName = devName;
        mWifiNative.setP2pSsidPostfix("-" + mThisDevice.deviceName);

        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.WIFI_P2P_DEVICE_NAME, devName);
        sendThisDeviceChangedBroadcast();
        return true;
    }

    private void initializeP2pSettings() {
        mWifiNative.setPersistentReconnect(true);
        mThisDevice.deviceName = getPersistedDeviceName();
        mWifiNative.setDeviceName(mThisDevice.deviceName);
        // DIRECT-XY-DEVICENAME (XY is randomly generated)
        mWifiNative.setP2pSsidPostfix("-" + mThisDevice.deviceName);
        mWifiNative.setDeviceType(mThisDevice.primaryDeviceType);
        // Supplicant defaults to using virtual display with display
        // which refers to a remote display. Use physical_display
        mWifiNative.setConfigMethods("virtual_push_button physical_display keypad");
        // STA has higher priority over P2P
        mWifiNative.setConcurrencyPriority("sta");

        mThisDevice.deviceAddress = mWifiNative.p2pGetDeviceAddress();
        updateThisDevice(WifiP2pDevice.AVAILABLE);
        if (DBG) Slog.d(TAG, "DeviceAddress: " + mThisDevice.deviceAddress);

        mClientInfoList.clear();
        mWifiNative.p2pFlush();
        mWifiNative.p2pServiceFlush();
        mServiceTransactionId = 0;
        mServiceDiscReqId = null;
    }

    private void updateThisDevice(int status) {
        mThisDevice.status = status;
        sendThisDeviceChangedBroadcast();
    }

    private void handleGroupCreationFailure() {
        mSavedPeerConfig = null;
        /* After cancelling group formation, new connections on existing peers can fail
         * at supplicant. Flush and restart discovery */
        mWifiNative.p2pFlush();
        mServiceDiscReqId = null;
        sendMessage(WifiP2pManager.DISCOVER_PEERS);
    }

    //State machine initiated requests can have replyTo set to null indicating
    //there are no recipients, we ignore those reply actions
    private void replyToMessage(Message msg, int what) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessage(msg);
        dstMsg.what = what;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, int arg1) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessage(msg);
        dstMsg.what = what;
        dstMsg.arg1 = arg1;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    private void replyToMessage(Message msg, int what, Object obj) {
        if (msg.replyTo == null) return;
        Message dstMsg = obtainMessage(msg);
        dstMsg.what = what;
        dstMsg.obj = obj;
        mReplyChannel.replyToMessage(msg, dstMsg);
    }

    /* arg2 on the source message has a hash code that needs to be retained in replies
     * see WifiP2pManager for details */
    private Message obtainMessage(Message srcMsg) {
        Message msg = Message.obtain();
        msg.arg2 = srcMsg.arg2;
        return msg;
    }

    private void logd(String s) {
        Slog.d(TAG, s);
    }

    private void loge(String s) {
        Slog.e(TAG, s);
    }

    /**
     * Update service discovery request to wpa_supplicant.
     */
    private boolean updateSupplicantServiceRequest() {
        clearSupplicantServiceRequest();

        StringBuffer sb = new StringBuffer();
        for (ClientInfo c: mClientInfoList.values()) {
            int key;
            WifiP2pServiceRequest req;
            for (int i=0; i < c.mReqList.size(); i++) {
                req = c.mReqList.valueAt(i);
                if (req != null) {
                    sb.append(req.getSupplicantQuery());
                }
            }
        }

        if (sb.length() == 0) {
            return false;
        }

        mServiceDiscReqId = mWifiNative.p2pServDiscReq("00:00:00:00:00:00", sb.toString());
        if (mServiceDiscReqId == null) {
            return false;
        }
        return true;
    }

    /**
     * Clear service discovery request in wpa_supplicant
     */
    private void clearSupplicantServiceRequest() {
        if (mServiceDiscReqId == null) return;

        mWifiNative.p2pServDiscCancelReq(mServiceDiscReqId);
        mServiceDiscReqId = null;
    }

    /* TODO: We could track individual service adds separately and avoid
     * having to do update all service requests on every new request
     */
    private boolean addServiceRequest(Messenger m, WifiP2pServiceRequest req) {
        clearClientDeadChannels();
        ClientInfo clientInfo = getClientInfo(m, true);
        if (clientInfo == null) {
            return false;
        }

        ++mServiceTransactionId;
        //The Wi-Fi p2p spec says transaction id should be non-zero
        if (mServiceTransactionId == 0) ++mServiceTransactionId;
        req.setTransactionId(mServiceTransactionId);
        clientInfo.mReqList.put(mServiceTransactionId, req);

        if (mServiceDiscReqId == null) {
            return true;
        }

        return updateSupplicantServiceRequest();
    }

    private void removeServiceRequest(Messenger m, WifiP2pServiceRequest req) {
        ClientInfo clientInfo = getClientInfo(m, false);
        if (clientInfo == null) {
            return;
        }

        //Application does not have transaction id information
        //go through stored requests to remove
        boolean removed = false;
        for (int i=0; i < clientInfo.mReqList.size(); i++) {
            if (req.equals(clientInfo.mReqList.valueAt(i))) {
                removed = true;
                clientInfo.mReqList.removeAt(i);
                break;
            }
        }

        if (!removed) return;

        if (clientInfo.mReqList.size() == 0 && clientInfo.mServList.size() == 0) {
            if (DBG) logd("remove client information from framework");
            mClientInfoList.remove(clientInfo.mMessenger);
        }

        if (mServiceDiscReqId == null) {
            return;
        }

        updateSupplicantServiceRequest();
    }

    private void clearServiceRequests(Messenger m) {

        ClientInfo clientInfo = getClientInfo(m, false);
        if (clientInfo == null) {
            return;
        }

        if (clientInfo.mReqList.size() == 0) {
            return;
        }

        clientInfo.mReqList.clear();

        if (clientInfo.mServList.size() == 0) {
            if (DBG) logd("remove channel information from framework");
            mClientInfoList.remove(clientInfo.mMessenger);
        }

        if (mServiceDiscReqId == null) {
            return;
        }

        updateSupplicantServiceRequest();
    }

    private boolean addLocalService(Messenger m, WifiP2pServiceInfo servInfo) {
        clearClientDeadChannels();
        ClientInfo clientInfo = getClientInfo(m, true);
        if (clientInfo == null) {
            return false;
        }

        if (!clientInfo.mServList.add(servInfo)) {
            return false;
        }

        if (!mWifiNative.p2pServiceAdd(servInfo)) {
            clientInfo.mServList.remove(servInfo);
            return false;
        }

        return true;
    }

    private void removeLocalService(Messenger m, WifiP2pServiceInfo servInfo) {
        ClientInfo clientInfo = getClientInfo(m, false);
        if (clientInfo == null) {
            return;
        }

        mWifiNative.p2pServiceDel(servInfo);

        clientInfo.mServList.remove(servInfo);
        if (clientInfo.mReqList.size() == 0 && clientInfo.mServList.size() == 0) {
            if (DBG) logd("remove client information from framework");
            mClientInfoList.remove(clientInfo.mMessenger);
        }
    }

    private void clearLocalServices(Messenger m) {
        ClientInfo clientInfo = getClientInfo(m, false);
        if (clientInfo == null) {
            return;
        }

        for (WifiP2pServiceInfo servInfo: clientInfo.mServList) {
            mWifiNative.p2pServiceDel(servInfo);
        }

        clientInfo.mServList.clear();
        if (clientInfo.mReqList.size() == 0) {
            if (DBG) logd("remove client information from framework");
            mClientInfoList.remove(clientInfo.mMessenger);
        }
    }

    private void clearClientInfo(Messenger m) {
        clearLocalServices(m);
        clearServiceRequests(m);
    }

    /**
     * Send the service response to the WifiP2pManager.Channel.
     *
     * @param resp
     */
    private void sendServiceResponse(WifiP2pServiceResponse resp) {
        for (ClientInfo c : mClientInfoList.values()) {
            WifiP2pServiceRequest req = c.mReqList.get(resp.getTransactionId());
            if (req != null) {
                Message msg = Message.obtain();
                msg.what = WifiP2pManager.RESPONSE_SERVICE;
                msg.arg1 = 0;
                msg.arg2 = 0;
                msg.obj = resp;
                try {
                    c.mMessenger.send(msg);
                } catch (RemoteException e) {
                    if (DBG) logd("detect dead channel");
                    clearClientInfo(c.mMessenger);
                    return;
                }
            }
        }
    }

    /**
     * We dont get notifications of clients that have gone away.
     * We detect this actively when services are added and throw
     * them away.
     *
     * TODO: This can be done better with full async channels.
     */
    private void clearClientDeadChannels() {
        ArrayList<Messenger> deadClients = new ArrayList<Messenger>();

        for (ClientInfo c : mClientInfoList.values()) {
            Message msg = Message.obtain();
            msg.what = WifiP2pManager.PING;
            msg.arg1 = 0;
            msg.arg2 = 0;
            msg.obj = null;
            try {
                c.mMessenger.send(msg);
            } catch (RemoteException e) {
                if (DBG) logd("detect dead channel");
                deadClients.add(c.mMessenger);
            }
        }

        for (Messenger m : deadClients) {
            clearClientInfo(m);
        }
    }

    /**
     * Return the specified ClientInfo.
     * @param m Messenger
     * @param createIfNotExist if true and the specified channel info does not exist,
     * create new client info.
     * @return the specified ClientInfo.
     */
    private ClientInfo getClientInfo(Messenger m, boolean createIfNotExist) {
        ClientInfo clientInfo = mClientInfoList.get(m);

        if (clientInfo == null && createIfNotExist) {
            if (DBG) logd("add a new client");
            clientInfo = new ClientInfo(m);
            mClientInfoList.put(m, clientInfo);
        }

        return clientInfo;
    }

    /**
     * Send detached message to dialog listener in the foreground application.
     * @param reason
     */
    private void sendDetachedMsg(int reason) {
        if (mForegroundAppMessenger == null) return;

        Message msg = Message.obtain();
        msg.what = WifiP2pManager.DIALOG_LISTENER_DETACHED;
        msg.arg1 = reason;
        try {
            mForegroundAppMessenger.send(msg);
        } catch (RemoteException e) {
        }
        mForegroundAppMessenger = null;
        mForegroundAppPkgName = null;
    }

    /**
     * Send a request to show wps pin to dialog listener in the foreground application.
     * @param pin WPS pin
     * @return
     */
    private boolean sendShowPinReqToFrontApp(String pin) {
        if (!isForegroundApp(mForegroundAppPkgName)) {
            sendDetachedMsg(WifiP2pManager.NOT_IN_FOREGROUND);
            return false;
        }
        Message msg = Message.obtain();
        msg.what = WifiP2pManager.SHOW_PIN_REQUESTED;
        Bundle bundle = new Bundle();
        bundle.putString(WifiP2pManager.WPS_PIN_BUNDLE_KEY, pin);
        msg.setData(bundle);
        return sendDialogMsgToFrontApp(msg);
    }

    /**
     * Send a request to establish the connection to dialog listener in the foreground
     * application.
     * @param dev source device
     * @param config
     * @return
     */
    private boolean sendConnectNoticeToApp(WifiP2pDevice dev, WifiP2pConfig config) {
        if (dev == null) {
            dev = new WifiP2pDevice(config.deviceAddress);
        }

        if (!isForegroundApp(mForegroundAppPkgName)) {
            if (DBG) logd("application is NOT foreground");
            sendDetachedMsg(WifiP2pManager.NOT_IN_FOREGROUND);
            return false;
        }

        Message msg = Message.obtain();
        msg.what = WifiP2pManager.CONNECTION_REQUESTED;
        Bundle bundle = new Bundle();
        bundle.putParcelable(WifiP2pManager.P2P_DEV_BUNDLE_KEY, dev);
        bundle.putParcelable(WifiP2pManager.P2P_CONFIG_BUNDLE_KEY, config);
        msg.setData(bundle);
        return sendDialogMsgToFrontApp(msg);
    }

    /**
     * Send dialog event message to front application's dialog listener.
     * @param msg
     * @return true if success.
     */
    private boolean sendDialogMsgToFrontApp(Message msg) {
        try {
            mForegroundAppMessenger.send(msg);
        } catch (RemoteException e) {
            mForegroundAppMessenger = null;
            mForegroundAppPkgName = null;
            return false;
        }
        return true;
    }

    /**
     * Set dialog listener application.
     * @param m
     * @param appPkgName if null, reset the listener.
     * @param isReset if true, try to reset.
     * @return
     */
    private boolean setDialogListenerApp(Messenger m,
            String appPkgName, boolean isReset) {

        if (mForegroundAppPkgName != null && !mForegroundAppPkgName.equals(appPkgName)) {
            if (isForegroundApp(mForegroundAppPkgName)) {
                // The current dialog listener is foreground app's.
                if (DBG) logd("application is NOT foreground");
                return false;
            }
            // detach an old listener.
            sendDetachedMsg(WifiP2pManager.NOT_IN_FOREGROUND);
        }

        if (isReset) {
            if (DBG) logd("reset dialog listener");
            mForegroundAppMessenger = null;
            mForegroundAppPkgName = null;
            return true;
        }

        if (!isForegroundApp(appPkgName)) {
            return false;
        }

        mForegroundAppMessenger = m;
        mForegroundAppPkgName = appPkgName;
        if (DBG) logd("set dialog listener. app=" + appPkgName);
        return true;
    }

    /**
     * Return true if the specified package name is foreground app's.
     *
     * @param pkgName application package name.
     * @return
     */
    private boolean isForegroundApp(String pkgName) {
        if (pkgName == null) return false;

        List<RunningTaskInfo> tasks = mActivityMgr.getRunningTasks(1);
        if (tasks.size() == 0) {
            return false;
        }

        return pkgName.equals(tasks.get(0).baseActivity.getPackageName());
    }

    }

    /**
     * Information about a particular client and we track the service discovery requests
     * and the local services registered by the client.
     */
    private class ClientInfo {

        /*
         * A reference to WifiP2pManager.Channel handler.
         * The response of this request is notified to WifiP2pManager.Channel handler
         */
        private Messenger mMessenger;

        /*
         * A service discovery request list.
         */
        private SparseArray<WifiP2pServiceRequest> mReqList;

        /*
         * A local service information list.
         */
        private List<WifiP2pServiceInfo> mServList;

        private ClientInfo(Messenger m) {
            mMessenger = m;
            mReqList = new SparseArray();
            mServList = new ArrayList<WifiP2pServiceInfo>();
        }
    }

}
