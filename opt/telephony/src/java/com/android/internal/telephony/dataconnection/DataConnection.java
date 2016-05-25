/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony.dataconnection;

import com.android.internal.telephony.CallTracker;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.DctConstants;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.RILConstants;
import com.android.internal.telephony.RetryManager;
import com.android.internal.telephony.SubscriptionController;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.TelephonyProperties;
import com.android.internal.telephony.ServiceStateTracker;
import com.android.internal.util.AsyncChannel;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.Protocol;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.server.net.BaseNetworkObserver;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.INetworkManagementEventObserver;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkAgent;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkMisc;
import android.net.NetworkUtils;
import android.net.ProxyInfo;
import android.os.AsyncResult;
import android.os.Build;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Pair;
import android.util.Patterns;
import android.util.SparseBooleanArray;
import android.util.TimeUtils;

import java.io.FileDescriptor;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import com.mediatek.common.MPlugin;
import com.mediatek.common.telephony.IGsmDCTExt;
import com.mediatek.internal.telephony.ITelephonyEx;
import com.mediatek.internal.telephony.dataconnection.DcFailCauseManager;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Collection;

/**
 * {@hide}
 *
 * DataConnection StateMachine.
 *
 * This a class for representing a single data connection, with instances of this
 * class representing a connection via the cellular network. There may be multiple
 * data connections and all of them are managed by the <code>DataConnectionTracker</code>.
 *
 * A recent change is to move retry handling into this class, with that change the
 * old retry manager is now used internally rather than exposed to the DCT. Also,
 * bringUp now has an initialRetry which is used limit the number of retries
 * during the initial bring up of the connection. After the connection becomes active
 * the current max retry is restored to the configured value.
 *
 * NOTE: All DataConnection objects must be running on the same looper, which is the default
 * as the coordinator has members which are used without synchronization.
 */
public final class DataConnection extends StateMachine {
    private static final boolean DBG = true;
    private static final boolean VDBG = true;

    /** Retry configuration: A doubling of retry times from 5secs to 30minutes. */
    private static final String DEFAULT_DATA_RETRY_CONFIG = "default_randomization=2000,"
        + "5000,10000,20000,40000,80000:5000,160000:5000,"
        + "320000:5000,640000:5000,1280000:5000,1800000:5000";

    /** Retry configuration for secondary networks: 4 tries in 20 sec. */
    private static final String SECONDARY_DATA_RETRY_CONFIG =
            "max_retries=3, 5000, 5000, 5000";

    private static final String NETWORK_TYPE = "MOBILE";

    // The data connection controller
    private DcController mDcController;

    // The Tester for failing all bringup's
    private DcTesterFailBringUpAll mDcTesterFailBringUpAll;

    private static AtomicInteger mInstanceNumber = new AtomicInteger(0);
    private AsyncChannel mAc;

    // Utilities for the DataConnection
    private DcRetryAlarmController mDcRetryAlarmController;

    // The DCT that's talking to us, we only support one!
    private DcTrackerBase mDct = null;

    protected String[] mPcscfAddr;
    //SM_CAUSE
    private boolean mIsBsp = SystemProperties.getBoolean("ro.mtk_bsp_package", false);
    private IGsmDCTExt mGsmDCTExt;
    private boolean mIsTestSim = false;

    //M:
    private SubscriptionController mSubController = SubscriptionController.getInstance();

    private final INetworkManagementService mNetworkManager;
    private String mInterfaceName = null;
    /**
     * Used internally for saving connecting parameters.
     */
    static class ConnectionParams {
        int mTag;
        ApnContext mApnContext;
        int mInitialMaxRetry;
        int mProfileId;
        int mRilRat;
        boolean mRetryWhenSSChange;
        Message mOnCompletedMsg;

        ConnectionParams(ApnContext apnContext, int initialMaxRetry, int profileId,
                int rilRadioTechnology, boolean retryWhenSSChange, Message onCompletedMsg) {
            initParams(apnContext, initialMaxRetry, profileId, rilRadioTechnology, retryWhenSSChange, onCompletedMsg);
        }

        private void initParams(ApnContext apnContext, int initialMaxRetry, int profileId,
                int rilRadioTechnology, boolean retryWhenSSChange, Message onCompletedMsg) {
            this.mApnContext = apnContext;
            this.mInitialMaxRetry = initialMaxRetry;
            this.mProfileId = profileId;
            this.mRilRat = rilRadioTechnology;
            this.mOnCompletedMsg = onCompletedMsg;
            this.mRetryWhenSSChange = retryWhenSSChange;
        }

        @Override
        public String toString() {
            return "{mTag=" + mTag + " mApnContext=" + mApnContext
                    + " mInitialMaxRetry=" + mInitialMaxRetry + " mProfileId=" + mProfileId
                    + " mRat=" + mRilRat + " retryWhenSSChange: " + mRetryWhenSSChange
                    + " mOnCompletedMsg=" + msgToString(mOnCompletedMsg) + "}";
        }
    }

    /**
     * Used internally for saving disconnecting parameters.
     */
    static class DisconnectParams {
        int mTag;
        ApnContext mApnContext;
        String mReason;
        Message mOnCompletedMsg;

        DisconnectParams(ApnContext apnContext, String reason, Message onCompletedMsg) {
            mApnContext = apnContext;
            mReason = reason;
            mOnCompletedMsg = onCompletedMsg;
        }

        @Override
        public String toString() {
            return "{mTag=" + mTag + " mApnContext=" + mApnContext
                    + " mReason=" + mReason
                    + " mOnCompletedMsg=" + msgToString(mOnCompletedMsg) + "}";
        }
    }

    private ApnSetting mApnSetting;
    private ConnectionParams mConnectionParams;
    private DisconnectParams mDisconnectParams;
    private DcFailCause mDcFailCause;

    private PhoneBase mPhone;
    private LinkProperties mLinkProperties = new LinkProperties();
    private long mCreateTime;
    private long mLastFailTime;
    private DcFailCause mLastFailCause;
    private static final String NULL_IP = "0.0.0.0";
    private Object mUserData;
    private int mRilRat = Integer.MAX_VALUE;
    private int mDataRegState = Integer.MAX_VALUE;
    private NetworkInfo mNetworkInfo;
    private NetworkAgent mNetworkAgent;

    //***** Package visible variables
    int mTag;
    int mCid;
    List<ApnContext> mApnContexts = null;
    PendingIntent mReconnectIntent = null;
    RetryManager mRetryManager = new RetryManager();
    DcFailCauseManager mDcFailCauseManager = new DcFailCauseManager();


    // ***** Event codes for driving the state machine, package visible for Dcc
    static final int BASE = Protocol.BASE_DATA_CONNECTION;
    static final int EVENT_CONNECT = BASE + 0;
    static final int EVENT_SETUP_DATA_CONNECTION_DONE = BASE + 1;
    static final int EVENT_GET_LAST_FAIL_DONE = BASE + 2;
    static final int EVENT_DEACTIVATE_DONE = BASE + 3;
    static final int EVENT_DISCONNECT = BASE + 4;
    static final int EVENT_RIL_CONNECTED = BASE + 5;
    static final int EVENT_DISCONNECT_ALL = BASE + 6;
    static final int EVENT_DATA_STATE_CHANGED = BASE + 7;
    static final int EVENT_TEAR_DOWN_NOW = BASE + 8;
    static final int EVENT_LOST_CONNECTION = BASE + 9;
    static final int EVENT_RETRY_CONNECTION = BASE + 10;
    static final int EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED = BASE + 11;
    static final int EVENT_DATA_CONNECTION_ROAM_ON = BASE + 12;
    static final int EVENT_DATA_CONNECTION_ROAM_OFF = BASE + 13;
    static final int EVENT_BW_REFRESH_RESPONSE = BASE + 14;
    static final int EVENT_DATA_STATE_CHANGED_FOR_LOADED = BASE + 15;

    //FALLBACK PDP Retry
    static final int EVENT_FALLBACK_RETRY_CONNECTION = BASE + 16;
    static final int EVENT_FALLBACK_GET_LAST_FAIL_DONE = BASE + 17;

    static final int EVENT_IPV4_ADDRESS_REMOVED = BASE + 18;
    static final int EVENT_IPV6_ADDRESS_REMOVED = BASE + 19;
    static final int EVENT_ADDRESS_REMOVED = BASE + 20;

    static final int EVENT_DATA_CONNECTION_VOICE_CALL_STARTED = BASE + 21;
    static final int EVENT_DATA_CONNECTION_VOICE_CALL_ENDED = BASE + 22;

    private static final int CMD_TO_STRING_COUNT =
            EVENT_DATA_CONNECTION_VOICE_CALL_ENDED - BASE + 1;
    private static String[] sCmdToString = new String[CMD_TO_STRING_COUNT];
    static {
        sCmdToString[EVENT_CONNECT - BASE] = "EVENT_CONNECT";
        sCmdToString[EVENT_SETUP_DATA_CONNECTION_DONE - BASE] =
                "EVENT_SETUP_DATA_CONNECTION_DONE";
        sCmdToString[EVENT_GET_LAST_FAIL_DONE - BASE] = "EVENT_GET_LAST_FAIL_DONE";
        sCmdToString[EVENT_DEACTIVATE_DONE - BASE] = "EVENT_DEACTIVATE_DONE";
        sCmdToString[EVENT_DISCONNECT - BASE] = "EVENT_DISCONNECT";
        sCmdToString[EVENT_RIL_CONNECTED - BASE] = "EVENT_RIL_CONNECTED";
        sCmdToString[EVENT_DISCONNECT_ALL - BASE] = "EVENT_DISCONNECT_ALL";
        sCmdToString[EVENT_DATA_STATE_CHANGED - BASE] = "EVENT_DATA_STATE_CHANGED";
        sCmdToString[EVENT_TEAR_DOWN_NOW - BASE] = "EVENT_TEAR_DOWN_NOW";
        sCmdToString[EVENT_LOST_CONNECTION - BASE] = "EVENT_LOST_CONNECTION";
        sCmdToString[EVENT_RETRY_CONNECTION - BASE] = "EVENT_RETRY_CONNECTION";
        sCmdToString[EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED - BASE] =
                "EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED";
        sCmdToString[EVENT_DATA_CONNECTION_ROAM_ON - BASE] = "EVENT_DATA_CONNECTION_ROAM_ON";
        sCmdToString[EVENT_DATA_CONNECTION_ROAM_OFF - BASE] = "EVENT_DATA_CONNECTION_ROAM_OFF";
        sCmdToString[EVENT_BW_REFRESH_RESPONSE - BASE] = "EVENT_BW_REFRESH_RESPONSE";
        sCmdToString[EVENT_DATA_STATE_CHANGED_FOR_LOADED - BASE] =
                "EVENT_DATA_STATE_CHANGED_FOR_LOADED";
        sCmdToString[EVENT_FALLBACK_RETRY_CONNECTION - BASE] = "EVENT_FALLBACK_RETRY_CONNECTION";
        sCmdToString[EVENT_FALLBACK_GET_LAST_FAIL_DONE - BASE] =
                "EVENT_FALLBACK_GET_LAST_FAIL_DONE";
        sCmdToString[EVENT_IPV4_ADDRESS_REMOVED - BASE] = "EVENT_IPV4_ADDRESS_REMOVED";
        sCmdToString[EVENT_IPV6_ADDRESS_REMOVED - BASE] = "EVENT_IPV6_ADDRESS_REMOVED";
        sCmdToString[EVENT_ADDRESS_REMOVED - BASE] = "EVENT_ADDRESS_REMOVED";
        sCmdToString[EVENT_DATA_CONNECTION_VOICE_CALL_STARTED - BASE] =
                "EVENT_DATA_CONNECTION_VOICE_CALL_STARTED";
        sCmdToString[EVENT_DATA_CONNECTION_VOICE_CALL_ENDED - BASE] =
                "EVENT_DATA_CONNECTION_VOICE_CALL_ENDED";
    }
    // Convert cmd to string or null if unknown
    static String cmdToString(int cmd) {
        String value;
        cmd -= BASE;
        if ((cmd >= 0) && (cmd < sCmdToString.length)) {
            value = sCmdToString[cmd];
        } else {
            value = DcAsyncChannel.cmdToString(cmd + BASE);
        }
        if (value == null) {
            value = "0x" + Integer.toHexString(cmd + BASE);
        }
        return value;
    }

    /**
     * Create the connection object.
     *
     * @param phone the Phone
     * @param id the connection id
     * @return DataConnection that was created.
     */
    static DataConnection makeDataConnection(PhoneBase phone, int id,
            DcTrackerBase dct, DcTesterFailBringUpAll failBringUpAll,
            DcController dcc) {
        DataConnection dc = new DataConnection(phone,
                "DC-" + mInstanceNumber.incrementAndGet(), id, dct, failBringUpAll, dcc);
        dc.start();
        if (DBG) dc.log("Made " + dc.getName());
        return dc;
    }

    void dispose() {
        log("dispose: call quiteNow()");
        //unregister network observer
        unRegisterNetworkAlertObserver();
        quitNow();
    }

    /* Getter functions */

    NetworkCapabilities getCopyNetworkCapabilities() {
        return makeNetworkCapabilities();
    }

    LinkProperties getCopyLinkProperties() {
        return new LinkProperties(mLinkProperties);
    }

    boolean getIsInactive() {
        return getCurrentState() == mInactiveState;
    }

    int getCid() {
        return mCid;
    }

    ApnSetting getApnSetting() {
        return mApnSetting;
    }

    void setLinkPropertiesHttpProxy(ProxyInfo proxy) {
        mLinkProperties.setHttpProxy(proxy);

        if (mNetworkAgent != null) {
            if (DBG) log("Update LinkProperties to NetworkAgent");
            mNetworkAgent.sendLinkProperties(mLinkProperties);
        }

    }

    static class UpdateLinkPropertyResult {
        public DataCallResponse.SetupResult setupResult = DataCallResponse.SetupResult.SUCCESS;
        public LinkProperties oldLp;
        public LinkProperties newLp;
        public UpdateLinkPropertyResult(LinkProperties curLp) {
            oldLp = curLp;
            newLp = curLp;
        }
    }

    public boolean isIpv4Connected() {
        boolean ret = false;
        Collection <InetAddress> addresses = mLinkProperties.getAddresses();

        for (InetAddress addr: addresses) {
            log("isIpv4Connected(), addr:" + addr);
            if (addr instanceof java.net.Inet4Address) {
                java.net.Inet4Address i4addr = (java.net.Inet4Address) addr;

                log("isAnyLocalAddress:" + i4addr.isAnyLocalAddress()
                    + "/isLinkLocalAddress()" + i4addr.isLinkLocalAddress()
                    + "/isLoopbackAddress()" + i4addr.isLoopbackAddress()
                    + "/isMulticastAddress()" + i4addr.isMulticastAddress());

                if (!i4addr.isAnyLocalAddress() && !i4addr.isLinkLocalAddress() &&
                        !i4addr.isLoopbackAddress() && !i4addr.isMulticastAddress()) {
                    ret = true;
                    break;
                }
            }
        }
        return ret;
    }

    public boolean isIpv6Connected() {
        boolean ret = false;
        Collection <InetAddress> addresses = mLinkProperties.getAddresses();

        for (InetAddress addr: addresses) {
            if (addr instanceof java.net.Inet6Address) {
                java.net.Inet6Address i6addr = (java.net.Inet6Address) addr;
                if (!i6addr.isAnyLocalAddress() && !i6addr.isLinkLocalAddress() &&
                        !i6addr.isLoopbackAddress() && !i6addr.isMulticastAddress()) {
                    ret = true;
                    break;
                }
            }
        }
        return ret;
    }

    UpdateLinkPropertyResult updateLinkProperty(DataCallResponse newState) {
        UpdateLinkPropertyResult result = new UpdateLinkPropertyResult(mLinkProperties);

        if (newState == null) return result;

        DataCallResponse.SetupResult setupResult;
        result.newLp = new LinkProperties();

        // set link properties based on data call response
        result.setupResult = setLinkProperties(newState, result.newLp);
        if (result.setupResult != DataCallResponse.SetupResult.SUCCESS) {
            if (DBG) log("updateLinkProperty failed : " + result.setupResult);
            return result;
        }
        // copy HTTP proxy as it is not part DataCallResponse.
        result.newLp.setHttpProxy(mLinkProperties.getHttpProxy());

        checkSetMtu(mApnSetting, result.newLp);

        mLinkProperties = result.newLp;

        updateTcpBufferSizes(mRilRat);

        if (DBG && (! result.oldLp.equals(result.newLp))) {
            log("updateLinkProperty old LP=" + result.oldLp);
            log("updateLinkProperty new LP=" + result.newLp);
        }

        if (result.newLp.equals(result.oldLp) == false &&
                mNetworkAgent != null) {
            mNetworkAgent.sendLinkProperties(mLinkProperties);
        }

        return result;
    }

    /**
     * Read the MTU value from link properties where it can be set from network. In case
     * not set by the network, set it again using the mtu szie value defined in the APN
     * database for the connected APN
     */
    private void checkSetMtu(ApnSetting apn, LinkProperties lp) {
        if (lp == null) return;

        if (apn == null || lp == null) return;

        if (lp.getMtu() != PhoneConstants.UNSET_MTU) {
            if (DBG) log("MTU set by call response to: " + lp.getMtu());
            return;
        }

        if (apn != null && apn.mtu != PhoneConstants.UNSET_MTU) {
            lp.setMtu(apn.mtu);
            if (DBG) log("MTU set by APN to: " + apn.mtu);
            return;
        }

        int mtu = mPhone.getContext().getResources().getInteger(
                com.android.internal.R.integer.config_mobile_mtu);
        if (mtu != PhoneConstants.UNSET_MTU) {
            lp.setMtu(mtu);
            if (DBG) log("MTU set by config resource to: " + mtu);
        }
    }

    //***** Constructor (NOTE: uses dcc.getHandler() as its Handler)
    private DataConnection(PhoneBase phone, String name, int id,
                DcTrackerBase dct, DcTesterFailBringUpAll failBringUpAll,
                DcController dcc) {
        super(name, dcc.getHandler());
        setLogRecSize(300);
        setLogOnlyTransitions(true);
        if (DBG) log("DataConnection constructor E");

        mPhone = phone;
        mDct = dct;
        mDcTesterFailBringUpAll = failBringUpAll;
        mDcController = dcc;
        mId = id;
        mCid = -1;
        mDcRetryAlarmController = new DcRetryAlarmController(mPhone, this);
        ServiceState ss = mPhone.getServiceState();
        mRilRat = ss.getRilDataRadioTechnology();
        mDataRegState = mPhone.getServiceState().getDataRegState();
        int networkType = ss.getDataNetworkType();
        mNetworkInfo = new NetworkInfo(ConnectivityManager.TYPE_MOBILE,
                networkType, NETWORK_TYPE, TelephonyManager.getNetworkTypeName(networkType));
        mNetworkInfo.setRoaming(ss.getDataRoaming());
        mNetworkInfo.setIsAvailable(true);

        //[SM_CAUSE]
        if (mIsBsp == false) {
            try {
                mGsmDCTExt =
                    MPlugin.createInstance(IGsmDCTExt.class.getName(), mPhone.getContext());
            } catch (Exception e) {
                if (DBG) log("mGsmDCTExt init fail");
                e.printStackTrace();
            }
        }

        if (checkIfCreateGsmDCTExt(mPhone)) {
            if (DBG) log("mGsmDCTExt init success");
        }
        //[SM_CASUE]}
        mRetryManager.resetRetryCount();

        addState(mDefaultState);
            addState(mInactiveState, mDefaultState);
            addState(mActivatingState, mDefaultState);
            addState(mRetryingState, mDefaultState);
            addState(mActiveState, mDefaultState);
            addState(mDisconnectingState, mDefaultState);
            addState(mDisconnectingErrorCreatingConnection, mDefaultState);
        setInitialState(mInactiveState);

        mApnContexts = new ArrayList<ApnContext>();


        // M: IPv6 RA update
        log("get INetworkManagementService");
        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNetworkManager = INetworkManagementService.Stub.asInterface(b);
        // register network observer
        regiseterNetworkAlertObserver();

        if (DBG) log("DataConnection constructor X");
    }

    private String getRetryConfig(boolean forDefault) {
        int nt = mPhone.getServiceState().getNetworkType();

        if (Build.IS_DEBUGGABLE) {
            String config = SystemProperties.get("test.data_retry_config");
            if (! TextUtils.isEmpty(config)) {
                return config;
            }
        }

        if ((nt == TelephonyManager.NETWORK_TYPE_CDMA) ||
            (nt == TelephonyManager.NETWORK_TYPE_1xRTT) ||
            (nt == TelephonyManager.NETWORK_TYPE_EVDO_0) ||
            (nt == TelephonyManager.NETWORK_TYPE_EVDO_A) ||
            (nt == TelephonyManager.NETWORK_TYPE_EVDO_B) ||
            (nt == TelephonyManager.NETWORK_TYPE_EHRPD)) {
            // CDMA variant
            return SystemProperties.get("ro.cdma.data_retry_config");
        } else {
            // Use GSM variant for all others.
            if (forDefault) {
                return SystemProperties.get("ro.gsm.data_retry_config");
            } else {
                return SystemProperties.get("ro.gsm.2nd_data_retry_config");
            }
        }
    }

    private void configureRetry(boolean forDefault) {
        String retryConfig = getRetryConfig(forDefault);

        if (!mRetryManager.configure(retryConfig)) {
            if (forDefault) {
                if (!mRetryManager.configure(DEFAULT_DATA_RETRY_CONFIG)) {
                    // Should never happen, log an error and default to a simple linear sequence.
                    loge("configureRetry: Could not configure using " +
                            "DEFAULT_DATA_RETRY_CONFIG=" + DEFAULT_DATA_RETRY_CONFIG);
                    mRetryManager.configure(5, 2000, 1000);
                }
            } else {
                // M: modified.
                boolean bSkipRetry = false;
                for (ApnContext apnContext : mApnContexts) {
                    String apnType = apnContext.getApnType();
                    if (TextUtils.equals(PhoneConstants.APN_TYPE_IMS, apnType) ||
                        TextUtils.equals(PhoneConstants.APN_TYPE_EMERGENCY, apnType)) {
                        bSkipRetry = true;
                        break;
                    }
                }

                if (bSkipRetry) {
                    // M: modified.
                    logd("configureRetry: IMS/EIMS, no retry by mobile. ");
                    mRetryManager.configure(0, 2000, 1000);
                } else {
                    if (!mRetryManager.configure(SECONDARY_DATA_RETRY_CONFIG)) {
                        // Should never happen, log an error and default to a simple sequence.
                        loge("configureRetry: Could note configure using " +
                                "SECONDARY_DATA_RETRY_CONFIG=" + SECONDARY_DATA_RETRY_CONFIG);
                        mRetryManager.configure(5, 2000, 1000);
                    }
                }
            }
        }
        if (DBG) {
            log("configureRetry: forDefault=" + forDefault + " mRetryManager=" + mRetryManager);
        }
    }

    /**
     * Begin setting up a data connection, calls setupDataCall
     * and the ConnectionParams will be returned with the
     * EVENT_SETUP_DATA_CONNECTION_DONE AsyncResul.userObj.
     *
     * @param cp is the connection parameters
     */
    private void onConnect(ConnectionParams cp) {
        if (DBG) log("onConnect: carrier='" + mApnSetting.carrier
                + "' APN='" + mApnSetting.apn
                + "' proxy='" + mApnSetting.proxy + "' port='" + mApnSetting.port + "'");
        if (cp.mApnContext != null) cp.mApnContext.requestLog("DataConnection.onConnect");


        // Check if we should fake an error.
        if (mDcTesterFailBringUpAll.getDcFailBringUp().mCounter  > 0) {
            DataCallResponse response = new DataCallResponse();
            response.version = mPhone.mCi.getRilVersion();
            response.status = mDcTesterFailBringUpAll.getDcFailBringUp().mFailCause.getErrorCode();
            response.cid = 0;
            response.active = 0;
            response.type = "";
            response.ifname = "";
            response.addresses = new String[0];
            response.dnses = new String[0];
            response.gateways = new String[0];
            response.suggestedRetryTime =
                    mDcTesterFailBringUpAll.getDcFailBringUp().mSuggestedRetryTime;
            response.pcscf = new String[0];
            response.mtu = PhoneConstants.UNSET_MTU;

            Message msg = obtainMessage(EVENT_SETUP_DATA_CONNECTION_DONE, cp);
            AsyncResult.forMessage(msg, response, null);
            sendMessage(msg);
            if (DBG) {
                log("onConnect: FailBringUpAll=" + mDcTesterFailBringUpAll.getDcFailBringUp()
                        + " send error response=" + response);
            }
            mDcTesterFailBringUpAll.getDcFailBringUp().mCounter -= 1;
            return;
        }

        mCreateTime = -1;
        mLastFailTime = -1;
        mLastFailCause = DcFailCause.NONE;

        // msg.obj will be returned in AsyncResult.userObj;
        Message msg = obtainMessage(EVENT_SETUP_DATA_CONNECTION_DONE, cp);
        msg.obj = cp;

        int authType = mApnSetting.authType;
        if (authType == -1) {
            authType = TextUtils.isEmpty(mApnSetting.user) ? RILConstants.SETUP_DATA_AUTH_NONE
                    : RILConstants.SETUP_DATA_AUTH_PAP_CHAP;
        }

        String protocol;
        if (mPhone.getServiceState().getDataRoamingFromRegistration()) {
            protocol = mApnSetting.roamingProtocol;
        } else {
            protocol = mApnSetting.protocol;
        }

        mPhone.mCi.setupDataCall(
                Integer.toString(cp.mRilRat + 2),
                Integer.toString(cp.mProfileId),
                mApnSetting.apn, mApnSetting.user, mApnSetting.password,
                Integer.toString(authType),
                protocol, String.valueOf(mId + 1), msg);
    }

    /**
     * TearDown the data connection when the deactivation is complete a Message with
     * msg.what == EVENT_DEACTIVATE_DONE and msg.obj == AsyncResult with AsyncResult.obj
     * containing the parameter o.
     *
     * @param o is the object returned in the AsyncResult.obj.
     */
    private void tearDownData(Object o) {
        int discReason = RILConstants.DEACTIVATE_REASON_NONE;
        ApnContext apnContext = null;
        if ((o != null) && (o instanceof DisconnectParams)) {
            DisconnectParams dp = (DisconnectParams) o;
            apnContext = dp.mApnContext;

            if (TextUtils.equals(dp.mReason, Phone.REASON_RADIO_TURNED_OFF)) {
                discReason = RILConstants.DEACTIVATE_REASON_RADIO_OFF;
            } else if (TextUtils.equals(dp.mReason, Phone.REASON_PDP_RESET)) {
                discReason = RILConstants.DEACTIVATE_REASON_PDP_RESET;
            }
        }
        if (mPhone.mCi.getRadioState().isOn()
                || (mPhone.getServiceState().getRilDataRadioTechnology()
                        == ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN )) {
            String str = "tearDownData radio is on, call deactivateDataCall";
            if (DBG) log(str);
            if (apnContext != null) apnContext.requestLog(str);
            mPhone.mCi.deactivateDataCall(mCid, discReason,
                    obtainMessage(EVENT_DEACTIVATE_DONE, mTag, 0, o));
        } else {
            /* For solving [ALPS02583120]WFC registration does not succeed after AP mode. */
            if (apnContext != null) {
                if (DBG)
                    log("tearDownData: check PDN type");
                String apnType = apnContext.getApnType();
                if (apnContext.getState() == DctConstants.State.CONNECTED) {
                    if (isApnTypeIMSorEmergency(apnType)) {
                        if (DBG)
                            log("tearDownData: ims pdn");
                        String str = "tearDownData radio is on, call deactivateDataCall";
                        if (DBG)
                            log(str);
                        if (apnContext != null)
                            apnContext.requestLog(str);
                        mPhone.mCi.deactivateDataCall(mCid, discReason,
                                obtainMessage(EVENT_DEACTIVATE_DONE, mTag, 0, o));
                        return;
                    }
                }
            }

            String str = "tearDownData radio is off sendMessage EVENT_DEACTIVATE_DONE immediately";
            if (DBG) log(str);
            if (apnContext != null) apnContext.requestLog(str);
            AsyncResult ar = new AsyncResult(o, null, null);
            sendMessage(obtainMessage(EVENT_DEACTIVATE_DONE, mTag, 0, ar));
        }
    }

    private void notifyAllWithEvent(ApnContext alreadySent, int event, String reason) {
        mNetworkInfo.setDetailedState(mNetworkInfo.getDetailedState(), reason,
                mNetworkInfo.getExtraInfo());
        for (ApnContext apnContext : mApnContexts) {
            if (apnContext == alreadySent) continue;
            if (reason != null) apnContext.setReason(reason);
            Message msg = mDct.obtainMessage(event, apnContext);
            AsyncResult.forMessage(msg);
            msg.sendToTarget();
        }
    }

    private void notifyAllOfConnected(String reason) {
        notifyAllWithEvent(null, DctConstants.EVENT_DATA_SETUP_COMPLETE, reason);
    }

    private void notifyAllOfDisconnectDcRetrying(String reason) {
        notifyAllWithEvent(null, DctConstants.EVENT_DISCONNECT_DC_RETRYING, reason);
    }
    private void notifyAllDisconnectCompleted(DcFailCause cause) {
        notifyAllWithEvent(null, DctConstants.EVENT_DISCONNECT_DONE, cause.toString());
    }

    // M: [LTE][Low Power][UL traffic shaping] Start
    private void notifyDefaultApnReferenceCountChanged(int refCount, int event) {
        Message msg = mDct.obtainMessage(event);
        msg.arg1 = refCount;
        AsyncResult.forMessage(msg);
        msg.sendToTarget();
    }
    // M: [LTE][Low Power][UL traffic shaping] End

    /**
     * Send the connectionCompletedMsg.
     *
     * @param cp is the ConnectionParams
     * @param cause and if no error the cause is DcFailCause.NONE
     * @param sendAll is true if all contexts are to be notified
     */
    private void notifyConnectCompleted(ConnectionParams cp, DcFailCause cause,
                                                boolean sendAll) {
        ApnContext alreadySent = null;

        if (cp != null && cp.mOnCompletedMsg != null) {
            // Get the completed message but only use it once
            Message connectionCompletedMsg = cp.mOnCompletedMsg;
            cp.mOnCompletedMsg = null;
            if (connectionCompletedMsg.obj instanceof ApnContext) {
                alreadySent = (ApnContext) connectionCompletedMsg.obj;
            }

            long timeStamp = System.currentTimeMillis();
            connectionCompletedMsg.arg1 = mCid;

            if (cause == DcFailCause.NONE) {
                mCreateTime = timeStamp;
                AsyncResult.forMessage(connectionCompletedMsg);
            } else {
                mLastFailCause = cause;
                mLastFailTime = timeStamp;

                // Return message with a Throwable exception to signify an error.
                if (cause == null) cause = DcFailCause.UNKNOWN;
                AsyncResult.forMessage(connectionCompletedMsg, cause,
                        new Throwable(cause.toString()));
            }
            if (DBG) {
                log("notifyConnectCompleted at " + timeStamp + " cause=" + cause
                        + " connectionCompletedMsg=" + msgToString(connectionCompletedMsg));
            }

            connectionCompletedMsg.sendToTarget();
        }
        if (sendAll) {
            notifyAllWithEvent(alreadySent, DctConstants.EVENT_DATA_SETUP_COMPLETE_ERROR,
                    cause.toString());
        }
    }

    /**
     * Send ar.userObj if its a message, which is should be back to originator.
     *
     * @param dp is the DisconnectParams.
     */
    private void notifyDisconnectCompleted(DisconnectParams dp, boolean sendAll) {
        if (VDBG) log("NotifyDisconnectCompleted");

        ApnContext alreadySent = null;
        String reason = null;

        if (dp != null && dp.mOnCompletedMsg != null) {
            // Get the completed message but only use it once
            Message msg = dp.mOnCompletedMsg;
            dp.mOnCompletedMsg = null;
            if (msg.obj instanceof ApnContext) {
                alreadySent = (ApnContext) msg.obj;
                // M: for RA fail, dcTracker can get the correct reason
                for (ApnContext apnContext : mApnContexts) {
                    if (apnContext == alreadySent && Phone.REASON_RA_FAILED.equals(dp.mReason)) {
                        log("set reason:" + dp.mReason);
                        apnContext.setReason(dp.mReason);
                    }
                }
            }
            reason = dp.mReason;
            if (VDBG) {
                log(String.format("msg=%s msg.obj=%s", msg.toString(),
                    ((msg.obj instanceof String) ? (String) msg.obj : "<no-reason>")));
            }
            AsyncResult.forMessage(msg);
            msg.sendToTarget();
        }
        if (sendAll) {
            if (reason == null) {
                reason = DcFailCause.UNKNOWN.toString();
            }
            notifyAllWithEvent(alreadySent, DctConstants.EVENT_DISCONNECT_DONE, reason);
        }
        if (DBG) log("NotifyDisconnectCompleted DisconnectParams=" + dp);
    }

    /*
     * **************************************************************************
     * Begin Members and methods owned by DataConnectionTracker but stored
     * in a DataConnection because there is one per connection.
     * **************************************************************************
     */

    /*
     * The id is owned by DataConnectionTracker.
     */
    private int mId;

    /**
     * Get the DataConnection ID.
     */
    public int getDataConnectionId() {
        return mId;
    }

    /*
     * **************************************************************************
     * End members owned by DataConnectionTracker
     * **************************************************************************
     */

    /**
     * Clear all settings called when entering mInactiveState.
     */
    private void clearSettings() {
        if (DBG) log("clearSettings");

        //for SM_CAUSE, special case for retry before initial
        if (mDcFailCause != DcFailCause.LOST_CONNECTION) {
            mRetryManager.resetRetryCount();
        }

        mCreateTime = -1;
        mLastFailTime = -1;
        mLastFailCause = DcFailCause.NONE;
        mCid = -1;

        mPcscfAddr = new String[5];

        mLinkProperties = new LinkProperties();
        mApnContexts.clear();
        mApnSetting = null;
        mDcFailCause = null;
    }

    /**
     * Process setup completion.
     *
     * @param ar is the result
     * @return SetupResult.
     */
    private DataCallResponse.SetupResult onSetupConnectionCompleted(AsyncResult ar) {
        DataCallResponse response = (DataCallResponse) ar.result;
        ConnectionParams cp = (ConnectionParams) ar.userObj;
        DataCallResponse.SetupResult result;

        if (cp.mTag != mTag) {
            if (DBG) {
                log("onSetupConnectionCompleted stale cp.tag=" + cp.mTag + ", mtag=" + mTag);
            }
            result = DataCallResponse.SetupResult.ERR_Stale;
        } else if (ar.exception != null) {
            if (DBG) {
                log("onSetupConnectionCompleted failed, ar.exception=" + ar.exception +
                    " response=" + response);
            }

            if (ar.exception instanceof CommandException
                    && ((CommandException) (ar.exception)).getCommandError()
                    == CommandException.Error.RADIO_NOT_AVAILABLE) {
                result = DataCallResponse.SetupResult.ERR_BadCommand;
                result.mFailCause = DcFailCause.RADIO_NOT_AVAILABLE;
            } else if ((response == null) || (response.version < 4)) {
                result = DataCallResponse.SetupResult.ERR_GetLastErrorFromRil;
            } else {
                result = DataCallResponse.SetupResult.ERR_RilError;
                result.mFailCause = DcFailCause.fromInt(response.status);
            }
        } else if (response.status != 0) {
            result = DataCallResponse.SetupResult.ERR_RilError;
            result.mFailCause = DcFailCause.fromInt(response.status);
        } else {
            if (DBG) log("onSetupConnectionCompleted received DataCallResponse: " + response);
            mCid = response.cid;

            mPcscfAddr = response.pcscf;

            result = updateLinkProperty(response).setupResult;

            // M: IPv6 RA update
            mInterfaceName = response.ifname.toString();
            log("onSetupConnectionCompleted: ifname-" + mInterfaceName);
        }

        return result;
    }

    private DataCallResponse.SetupResult onSetupFallbackConnection(AsyncResult ar) {
        DataCallResponse response = (DataCallResponse) ar.result;
        ConnectionParams cp = (ConnectionParams) ar.userObj;
        DataCallResponse.SetupResult result;

        if (cp.mTag != mTag) {
            if (DBG) {
                log("onSetupFallbackConnection stale cp.tag=" + cp.mTag + ", mtag=" + mTag);
            }
            result = DataCallResponse.SetupResult.ERR_Stale;
        } else {
            if (DBG) {
                log("onSetupFallbackConnection received DataCallResponse: " + response);
            }
            mCid = response.cid;

            mPcscfAddr = response.pcscf;

            //To pass the error check since we have at least one IPv4 or IPv6 is accepted
            //store the temp status
            int tempStatus = response.status;
            response.status = DcFailCause.NONE.getErrorCode();

            result = updateLinkProperty(response).setupResult;

            response.status = tempStatus;

            // M: IPv6 RA update
            mInterfaceName = response.ifname.toString();

            log("onSetupFallbackConnection: ifname-" + mInterfaceName);
        }

        return result;
    }

    private boolean isDnsOk(String[] domainNameServers) {
        if (NULL_IP.equals(domainNameServers[0]) && NULL_IP.equals(domainNameServers[1])
                && !mPhone.isDnsCheckDisabled()) {
            // Work around a race condition where QMI does not fill in DNS:
            // Deactivate PDP and let DataConnectionTracker retry.
            // Do not apply the race condition workaround for MMS APN
            // if Proxy is an IP-address.
            // Otherwise, the default APN will not be restored anymore.
            if (!mApnSetting.types[0].equals(PhoneConstants.APN_TYPE_MMS)
                || !isIpAddress(mApnSetting.mmsProxy)) {
                log(String.format(
                        "isDnsOk: return false apn.types[0]=%s APN_TYPE_MMS=%s isIpAddress(%s)=%s",
                        mApnSetting.types[0], PhoneConstants.APN_TYPE_MMS, mApnSetting.mmsProxy,
                        isIpAddress(mApnSetting.mmsProxy)));
                return false;
            }
        }
        return true;
    }

    private static final String TCP_BUFFER_SIZES_GPRS = "4092,8760,11680,4096,8760,11680";
    private static final String TCP_BUFFER_SIZES_EDGE = "4093,26280,35040,4096,16384,35040";
    private static final String TCP_BUFFER_SIZES_UMTS = "4094,87380,524288,4096,16384,524288";
    private static final String TCP_BUFFER_SIZES_1XRTT= "16384,32768,131072,4096,16384,102400";
    private static final String TCP_BUFFER_SIZES_EVDO = "4094,87380,524288,4096,16384,524288";
    private static final String TCP_BUFFER_SIZES_EHRPD= "131072,262144,1048576,4096,16384,524288";
    private static final String TCP_BUFFER_SIZES_HSDPA = "4094,87380,524288,4096,16384,524288";
    private static final String TCP_BUFFER_SIZES_HSPA = "4094,87380,524288,4096,16384,524288";
    private static final String TCP_BUFFER_SIZES_LTE  =
            "524288,1048576,2097152,262144,524288,1048576";
    private static final String TCP_BUFFER_SIZES_HSPAP = "4094,87380,1220608,4096,16384,1220608";


    private void updateTcpBufferSizes(int rilRat) {
        String sizes = null;
        String ratName = ServiceState.rilRadioTechnologyToString(rilRat).toLowerCase(Locale.ROOT);
        // ServiceState gives slightly different names for EVDO tech ("evdo-rev.0" for ex)
        // - patch it up:
        if (rilRat == ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_0 ||
                rilRat == ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A ||
                rilRat == ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_B) {
            ratName = "evdo";
        }

        // in the form: "ratname:rmem_min,rmem_def,rmem_max,wmem_min,wmem_def,wmem_max"
        String[] configOverride = mPhone.getContext().getResources().getStringArray(
                com.android.internal.R.array.config_mobile_tcp_buffers);
        for (int i = 0; i < configOverride.length; i++) {
            String[] split = configOverride[i].split(":");
            if (ratName.equals(split[0]) && split.length == 2) {
                sizes = split[1];
                break;
            }
        }

        if (sizes == null) {
            // no override - use telephony defaults
            // doing it this way allows device or carrier to just override the types they
            // care about and inherit the defaults for the others.
            switch (rilRat) {
                case ServiceState.RIL_RADIO_TECHNOLOGY_GPRS:
                    sizes = TCP_BUFFER_SIZES_GPRS;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_EDGE:
                    sizes = TCP_BUFFER_SIZES_EDGE;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_UMTS:
                    sizes = TCP_BUFFER_SIZES_UMTS;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_1xRTT:
                    sizes = TCP_BUFFER_SIZES_1XRTT;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_0:
                case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A:
                case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_B:
                    sizes = TCP_BUFFER_SIZES_EVDO;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD:
                    sizes = TCP_BUFFER_SIZES_EHRPD;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_HSDPA:
                    sizes = TCP_BUFFER_SIZES_HSDPA;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_HSPA:
                case ServiceState.RIL_RADIO_TECHNOLOGY_HSUPA:
                    sizes = TCP_BUFFER_SIZES_HSPA;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_LTE:
                    sizes = TCP_BUFFER_SIZES_LTE;
                    break;
                case ServiceState.RIL_RADIO_TECHNOLOGY_HSPAP:
                    sizes = TCP_BUFFER_SIZES_HSPAP;
                    break;
                default:
                    // Leave empty - this will let ConnectivityService use the system default.
                    break;
            }
        }
        mLinkProperties.setTcpBufferSizes(sizes);
    }

    private NetworkCapabilities makeNetworkCapabilities() {
        NetworkCapabilities result = new NetworkCapabilities();
        result.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR);

        // M: check if data enabled
        boolean isDataEnable = mDct.getDataEnabled();
        log("makeNetworkCapabilities: check data enable:" + isDataEnable);

        if (mApnSetting != null) {
            for (String type : mApnSetting.types) {
                switch (type) {
                    case PhoneConstants.APN_TYPE_ALL: {
                        if (isDataEnable && isDefaultDataSubPhone(mPhone)) {
                            result.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                        }
                        if (isVsimActive()) {
                            // M:  Vsim feature
                            result.addCapability(NetworkCapabilities.NET_CAPABILITY_VSIM);
                            result.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                        }
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_SUPL);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_FOTA);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_IMS);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_EIMS);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_CBS);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_IA);
                        /** M: start */
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_DM);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_WAP);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_NET);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_CMMAIL);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_TETHERING);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_RCSE);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_XCAP);
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_RCS);
                        /** M: end */
                        break;
                    }
                    case PhoneConstants.APN_TYPE_DEFAULT: {
                        if (isDataEnable && isDefaultDataSubPhone(mPhone)) {
                            result.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                        }
                        if (isVsimActive()) {
                            // M:  Vsim feature
                            result.addCapability(NetworkCapabilities.NET_CAPABILITY_VSIM);
                            result.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                        }
                        break;
                    }
                    case PhoneConstants.APN_TYPE_MMS: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_SUPL: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_SUPL);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_DUN: {
                        ApnSetting securedDunApn = mDct.fetchDunApn();
                        if (securedDunApn == null || securedDunApn.equals(mApnSetting)) {
                            result.addCapability(NetworkCapabilities.NET_CAPABILITY_DUN);
                        }
                        break;
                    }
                    case PhoneConstants.APN_TYPE_FOTA: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_FOTA);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_IMS: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_IMS);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_EMERGENCY: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_EIMS);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_CBS: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_CBS);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_IA: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_IA);
                        break;
                    }
                    /** M: start */
                    case PhoneConstants.APN_TYPE_DM: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_DM);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_WAP: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_WAP);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_NET: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_NET);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_CMMAIL: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_CMMAIL);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_TETHERING: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_TETHERING);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_RCSE: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_RCSE);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_XCAP: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_XCAP);
                        break;
                    }
                    case PhoneConstants.APN_TYPE_RCS: {
                        result.addCapability(NetworkCapabilities.NET_CAPABILITY_RCS);
                        break;
                    }
                    /** M: end */
                    default:
                }
            }
            result.maybeMarkCapabilitiesRestricted();
        }
        int up = 14;
        int down = 14;
        switch (mRilRat) {
            case ServiceState.RIL_RADIO_TECHNOLOGY_GPRS:
                up = 80;
                down = 80;
                break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_EDGE:
                up = 59;
                down = 236;
                break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_UMTS:
                up = 384;
                down = 384;
                break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_IS95A: // fall through
            case ServiceState.RIL_RADIO_TECHNOLOGY_IS95B:
                up = 14;
                down = 14;
                break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_0:
                up = 153;
                down = 2457;
                break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_A:
                up = 1843;
                down = 3174;
                break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_1xRTT:
                up = 100;
                down = 100;
                break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_HSDPA:
                up = 2048;
                down = 14336;
                break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_HSUPA:
                up = 5898;
                down = 14336;
                break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_HSPA:
                up = 5898;
                down = 14336;
                break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_EVDO_B:
                up = 1843;
                down = 5017;
                break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_LTE:
                up = 51200;
                down = 102400;
                break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_EHRPD:
                up = 153;
                down = 2516;
                break;
            case ServiceState.RIL_RADIO_TECHNOLOGY_HSPAP:
                up = 11264;
                down = 43008;
                break;
            default:
        }
        result.setLinkUpstreamBandwidthKbps(up);
        result.setLinkDownstreamBandwidthKbps(down);

        result.setNetworkSpecifier(Integer.toString(mPhone.getSubId()));

        return result;
    }

    private boolean isIpAddress(String address) {
        if (address == null) return false;

        return Patterns.IP_ADDRESS.matcher(address).matches();
    }

    private DataCallResponse.SetupResult setLinkProperties(DataCallResponse response,
            LinkProperties lp) {
        // Check if system property dns usable
        boolean okToUseSystemPropertyDns = false;
        String propertyPrefix = "net." + response.ifname + ".";
        String dnsServers[] = new String[2];
        dnsServers[0] = SystemProperties.get(propertyPrefix + "dns1");
        dnsServers[1] = SystemProperties.get(propertyPrefix + "dns2");
        okToUseSystemPropertyDns = isDnsOk(dnsServers);

        // set link properties based on data call response
        return response.setLinkProperties(lp, okToUseSystemPropertyDns);
    }

    /**
     * Initialize connection, this will fail if the
     * apnSettings are not compatible.
     *
     * @param cp the Connection paramemters
     * @return true if initialization was successful.
     */
    private boolean initConnection(ConnectionParams cp) {
        ApnContext apnContext = cp.mApnContext;
        if (mApnSetting == null) {
            // Only change apn setting if it isn't set, it will
            // only NOT be set only if we're in DcInactiveState.
            mApnSetting = apnContext.getApnSetting();
        }
        if (mApnSetting == null || !mApnSetting.canHandleType(apnContext.getApnType())) {
            if (DBG) {
                log("initConnection: incompatible apnSetting in ConnectionParams cp=" + cp
                        + " dc=" + DataConnection.this);
            }
            return false;
        }
        mTag += 1;
        mConnectionParams = cp;
        mConnectionParams.mTag = mTag;

        if (!mApnContexts.contains(apnContext)) {
            mApnContexts.add(apnContext);
            // M: [LTE][Low Power][UL traffic shaping] Start
            if(PhoneConstants.APN_TYPE_DEFAULT.equals(apnContext.getApnType())
                    && DctConstants.State.CONNECTED.equals(apnContext.getState())) {
                if (DBG) log("initConnection: refCount = " + mApnContexts.size());
                notifyDefaultApnReferenceCountChanged(mApnContexts.size(),
                        DctConstants.EVENT_DEFAULT_APN_REFERENCE_COUNT_CHANGED);
            }
            // M: [LTE][Low Power][UL traffic shaping] End
        }
        configureRetry(mApnSetting.canHandleType(PhoneConstants.APN_TYPE_DEFAULT));
        mRetryManager.setRetryCount(0);
        mRetryManager.setCurMaxRetryCount(mConnectionParams.mInitialMaxRetry);
        mRetryManager.setRetryForever(false);

        mIsTestSim = isTestSim();

        if (DBG) {
            log("initConnection: "
                    + " RefCount=" + mApnContexts.size()
                    + " mApnList=" + mApnContexts
                    + " mConnectionParams=" + mConnectionParams);
        }
        return true;
    }

    /**
     * The parent state for all other states.
     */
    private class DcDefaultState extends State {
        @Override
        public void enter() {
            if (DBG) log("DcDefaultState: enter");

            // Register for DRS or RAT change
            mPhone.getServiceStateTracker().registerForDataRegStateOrRatChanged(getHandler(),
                    DataConnection.EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED, null);

            mPhone.getServiceStateTracker().registerForDataRoamingOn(getHandler(),
                    DataConnection.EVENT_DATA_CONNECTION_ROAM_ON, null);
            mPhone.getServiceStateTracker().registerForDataRoamingOff(getHandler(),
                    DataConnection.EVENT_DATA_CONNECTION_ROAM_OFF, null);

            // Add ourselves to the list of data connections
            mDcController.addDc(DataConnection.this);
        }
        @Override
        public void exit() {
            if (DBG) log("DcDefaultState: exit");

            // Unregister for DRS or RAT change.
            mPhone.getServiceStateTracker().unregisterForDataRegStateOrRatChanged(getHandler());

            mPhone.getServiceStateTracker().unregisterForDataRoamingOn(getHandler());
            mPhone.getServiceStateTracker().unregisterForDataRoamingOff(getHandler());

            // Remove ourselves from the DC lists
            mDcController.removeDc(DataConnection.this);

            if (mAc != null) {
                mAc.disconnected();
                mAc = null;
            }
            mDcRetryAlarmController.dispose();
            mDcRetryAlarmController = null;
            mApnContexts = null;
            mReconnectIntent = null;
            mDct = null;
            mApnSetting = null;
            mPhone = null;
            mLinkProperties = null;
            mLastFailCause = null;
            mUserData = null;
            mDcController = null;
            mDcTesterFailBringUpAll = null;
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal = HANDLED;

            if (VDBG) {
                log("DcDefault msg=" + getWhatToString(msg.what)
                        + " RefCount=" + mApnContexts.size());
            }
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                    if (mAc != null) {
                        if (VDBG) log("Disconnecting to previous connection mAc=" + mAc);
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_FULL_CONNECTION_REFUSED_ALREADY_CONNECTED);
                    } else {
                        mAc = new AsyncChannel();
                        mAc.connected(null, getHandler(), msg.replyTo);
                        if (VDBG) log("DcDefaultState: FULL_CONNECTION reply connected");
                        mAc.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_SUCCESSFUL, mId, "hi");
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    if (DBG) {
                        log("DcDefault: CMD_CHANNEL_DISCONNECTED before quiting call dump");
                        dumpToLog();
                    }

                    quit();
                    break;
                }
                case DcAsyncChannel.REQ_IS_INACTIVE: {
                    boolean val = getIsInactive();
                    if (VDBG) log("REQ_IS_INACTIVE  isInactive=" + val);
                    mAc.replyToMessage(msg, DcAsyncChannel.RSP_IS_INACTIVE, val ? 1 : 0);
                    break;
                }
                case DcAsyncChannel.REQ_GET_CID: {
                    int cid = getCid();
                    if (VDBG) log("REQ_GET_CID  cid=" + cid);
                    mAc.replyToMessage(msg, DcAsyncChannel.RSP_GET_CID, cid);
                    break;
                }
                case DcAsyncChannel.REQ_GET_APNSETTING: {
                    ApnSetting apnSetting = getApnSetting();
                    if (VDBG) log("REQ_GET_APNSETTING  mApnSetting=" + apnSetting);
                    mAc.replyToMessage(msg, DcAsyncChannel.RSP_GET_APNSETTING, apnSetting);
                    break;
                }
                case DcAsyncChannel.REQ_GET_LINK_PROPERTIES: {
                    LinkProperties lp = getCopyLinkProperties();
                    if (VDBG) log("REQ_GET_LINK_PROPERTIES linkProperties" + lp);
                    mAc.replyToMessage(msg, DcAsyncChannel.RSP_GET_LINK_PROPERTIES, lp);
                    break;
                }
                case DcAsyncChannel.REQ_SET_LINK_PROPERTIES_HTTP_PROXY: {
                    ProxyInfo proxy = (ProxyInfo) msg.obj;
                    if (VDBG) log("REQ_SET_LINK_PROPERTIES_HTTP_PROXY proxy=" + proxy);
                    setLinkPropertiesHttpProxy(proxy);
                    mAc.replyToMessage(msg, DcAsyncChannel.RSP_SET_LINK_PROPERTIES_HTTP_PROXY);
                    break;
                }
                case DcAsyncChannel.REQ_GET_NETWORK_CAPABILITIES: {
                    NetworkCapabilities nc = getCopyNetworkCapabilities();
                    if (VDBG) log("REQ_GET_NETWORK_CAPABILITIES networkCapabilities" + nc);
                    mAc.replyToMessage(msg, DcAsyncChannel.RSP_GET_NETWORK_CAPABILITIES, nc);
                    break;
                }
                case DcAsyncChannel.REQ_RESET:
                    if (VDBG) log("DcDefaultState: msg.what=REQ_RESET");
                    transitionTo(mInactiveState);
                    break;

                //TODO: VoLTE
                // TODO: need to double check if this can be removed for IMS refactory [start]
                case DcAsyncChannel.REQ_GET_LAST_DATA_CONN_FAIL_CAUSE:
                    if (VDBG) log("REQ_GET_LAST_DATA_CONN_FAIL_CAUSE last fail cause is " + mLastFailCause);
                    mAc.replyToMessage(msg, DcAsyncChannel.RSP_GET_LAST_DATA_CONN_FAIL_CAUSE, mLastFailCause);
                    break;
                // TODO: need to double check if this can be removed for IMS refactory [end]
                case EVENT_CONNECT:
                    if (DBG) log("DcDefaultState: msg.what=EVENT_CONNECT, fail not expected");
                    ConnectionParams cp = (ConnectionParams) msg.obj;
                    notifyConnectCompleted(cp, DcFailCause.UNKNOWN, false);
                    break;

                case EVENT_DISCONNECT:
                    if (DBG) {
                        log("DcDefaultState deferring msg.what=EVENT_DISCONNECT RefCount="
                                + mApnContexts.size());
                    }
                    deferMessage(msg);
                    break;

                case EVENT_DISCONNECT_ALL:
                    if (DBG) {
                        log("DcDefaultState deferring msg.what=EVENT_DISCONNECT_ALL RefCount="
                                + mApnContexts.size());
                    }
                    deferMessage(msg);
                    break;

                case EVENT_TEAR_DOWN_NOW:
                    if (DBG) log("DcDefaultState EVENT_TEAR_DOWN_NOW");
                    mPhone.mCi.deactivateDataCall(mCid, 0,  null);
                    break;

                case EVENT_LOST_CONNECTION:
                    if (DBG) {
                        String s = "DcDefaultState ignore EVENT_LOST_CONNECTION"
                            + " tag=" + msg.arg1 + ":mTag=" + mTag;
                        logAndAddLogRec(s);
                    }
                    break;

                case EVENT_RETRY_CONNECTION:
                    if (DBG) {
                        String s = "DcDefaultState ignore EVENT_RETRY_CONNECTION"
                                + " tag=" + msg.arg1 + ":mTag=" + mTag;
                        logAndAddLogRec(s);
                    }
                    break;

                case EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED:
                    AsyncResult ar = (AsyncResult)msg.obj;
                    Pair<Integer, Integer> drsRatPair = (Pair<Integer, Integer>) ar.result;
                    mDataRegState = drsRatPair.first;
                    if (mRilRat != drsRatPair.second) {
                        updateTcpBufferSizes(drsRatPair.second);
                    }
                    mRilRat = drsRatPair.second;
                    if (DBG) {
                        log("DcDefaultState: EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED"
                                + " drs=" + mDataRegState
                                + " mRilRat=" + mRilRat);
                    }
                    ServiceState ss = mPhone.getServiceState();
                    int networkType = ss.getDataNetworkType();
                    mNetworkInfo.setSubtype(networkType,
                            TelephonyManager.getNetworkTypeName(networkType));
                    if (mNetworkAgent != null) {
                        updateNetworkInfoSuspendState();
                        mNetworkAgent.sendNetworkCapabilities(makeNetworkCapabilities());
                        mNetworkAgent.sendNetworkInfo(mNetworkInfo);
                        mNetworkAgent.sendLinkProperties(mLinkProperties);
                    }
                    break;

                case EVENT_DATA_CONNECTION_ROAM_ON:
                    mNetworkInfo.setRoaming(true);
                    break;

                case EVENT_DATA_CONNECTION_ROAM_OFF:
                    mNetworkInfo.setRoaming(false);
                    break;

                case EVENT_IPV4_ADDRESS_REMOVED:
                    if (DBG) {
                        log("DcDefaultState: ignore EVENT_IPV4_ADDRESS_REMOVED not in ActiveState");
                    }
                    break;

                case EVENT_IPV6_ADDRESS_REMOVED:
                    if (DBG) {
                        log("DcDefaultState: ignore EVENT_IPV6_ADDRESS_REMOVED not in ActiveState");
                    }
                    break;

                case EVENT_ADDRESS_REMOVED:
                    if (DBG) {
                        log("DcDefaultState: " + getWhatToString(msg.what));
                    }
                    // TODO: need to do something
                    break;

                default:
                    if (DBG) {
                        log("DcDefaultState: shouldn't happen but ignore msg.what="
                                + getWhatToString(msg.what));
                    }
                    break;
            }

            return retVal;
        }
    }

    private boolean updateNetworkInfoSuspendState() {
        final NetworkInfo.DetailedState oldState = mNetworkInfo.getDetailedState();

        // this is only called when we are either connected or suspended.  Decide which.
        if (mNetworkAgent == null) {
            Rlog.e(getName(), "Setting suspend state without a NetworkAgent");
        }

        // if we are not in-service change to SUSPENDED
        final ServiceStateTracker sst = mPhone.getServiceStateTracker();
        if (sst.getCurrentDataConnectionState() != ServiceState.STATE_IN_SERVICE) {
            //mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.SUSPENDED, null, null);
            // M : Workaround for google issue that sets orignal param. to avoid missing it.
            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.SUSPENDED,
                    mNetworkInfo.getReason(), mNetworkInfo.getExtraInfo());
        } else {
            // check for voice call and concurrency issues
            if (sst.isConcurrentVoiceAndDataAllowed() == false) {
                final CallTracker ct = mPhone.getCallTracker();
                if (ct.getState() != PhoneConstants.State.IDLE) {
                   //mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.SUSPENDED, null, null);
                    // M : Workaround for google issue that sets orignal param. to avoid missing it.
                    mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.SUSPENDED,
                            mNetworkInfo.getReason(), mNetworkInfo.getExtraInfo());
                    return (oldState != NetworkInfo.DetailedState.SUSPENDED);
                }
            }
            //mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED, null, null);
            // M : Workaround for google issue that sets orignal param. to avoid missing it.
            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED,
                    mNetworkInfo.getReason(), mNetworkInfo.getExtraInfo());
        }
        return (oldState != mNetworkInfo.getDetailedState());
    }

    private DcDefaultState mDefaultState = new DcDefaultState();

    /**
     * The state machine is inactive and expects a EVENT_CONNECT.
     */
    private class DcInactiveState extends State {
        // Inform all contexts we've failed connecting
        public void setEnterNotificationParams(ConnectionParams cp, DcFailCause cause) {
            if (VDBG) log("DcInactiveState: setEnterNoticationParams cp,cause");
            mConnectionParams = cp;
            mDisconnectParams = null;
            mDcFailCause = cause;
        }

        // Inform all contexts we've failed disconnected
        public void setEnterNotificationParams(DisconnectParams dp) {
            if (VDBG) log("DcInactiveState: setEnterNoticationParams dp");
            mConnectionParams = null;
            mDisconnectParams = dp;
            mDcFailCause = DcFailCause.NONE;
        }

        // Inform all contexts of the failure cause
        public void setEnterNotificationParams(DcFailCause cause) {
            mConnectionParams = null;
            mDisconnectParams = null;
            mDcFailCause = cause;
        }

        @Override
        public void enter() {
            mTag += 1;
            if (DBG) log("DcInactiveState: enter() mTag=" + mTag);

            if (mConnectionParams != null) {
                if (DBG) {
                    log("DcInactiveState: enter notifyConnectCompleted +ALL failCause="
                            + mDcFailCause);
                }
                notifyConnectCompleted(mConnectionParams, mDcFailCause, true);
            }
            if (mDisconnectParams != null) {
                if (DBG) {
                    log("DcInactiveState: enter notifyDisconnectCompleted +ALL failCause="
                            + mDcFailCause);
                }
                notifyDisconnectCompleted(mDisconnectParams, true);
            }
            if (mDisconnectParams == null && mConnectionParams == null && mDcFailCause != null) {
                if (DBG) {
                    log("DcInactiveState: enter notifyAllDisconnectCompleted failCause="
                            + mDcFailCause);
                }
                if (mDcFailCause == DcFailCause.LOST_CONNECTION) {
                    log("DcInactiveState: lost connection, reset dcac");
                    for (ApnContext apnContext : mApnContexts) {
                        apnContext.setDataConnectionAc(null);
                    }
                }
                notifyAllDisconnectCompleted(mDcFailCause);
            }

            // Remove ourselves from cid mapping, before clearSettings
            mDcController.removeActiveDcByCid(DataConnection.this);
            clearSettings();
        }

        @Override
        public void exit() {
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case DcAsyncChannel.REQ_RESET:
                    if (DBG) {
                        log("DcInactiveState: msg.what=RSP_RESET, ignore we're already reset");
                    }
                    retVal = HANDLED;
                    break;

                case EVENT_CONNECT:
                    if (DBG) log("DcInactiveState: mag.what=EVENT_CONNECT");

                    ConnectionParams cp = (ConnectionParams) msg.obj;

                    /** M: the connect request could be executed only if this is active data sub */
                    if (!DctController.getInstance().isActivePhone(mPhone.getPhoneId())) {
                        log("DcInactiveState: msg.what=EVENT_CONNECT but is not active data sub");
                        notifyConnectCompleted(cp, DcFailCause.UNACCEPTABLE_NETWORK_PARAMETER,
                                false);
                        retVal = HANDLED;
                        break;
                    }

                    if (initConnection(cp)) {
                        onConnect(mConnectionParams);
                        transitionTo(mActivatingState);
                    } else {
                        if (DBG) {
                            log("DcInactiveState: msg.what=EVENT_CONNECT initConnection failed");
                        }
                        notifyConnectCompleted(cp, DcFailCause.UNACCEPTABLE_NETWORK_PARAMETER,
                                false);
                    }
                    retVal = HANDLED;
                    break;

                case EVENT_DISCONNECT:
                    if (DBG) log("DcInactiveState: msg.what=EVENT_DISCONNECT");
                    notifyDisconnectCompleted((DisconnectParams) msg.obj, false);
                    retVal = HANDLED;
                    break;

                case EVENT_DISCONNECT_ALL:
                    if (DBG) log("DcInactiveState: msg.what=EVENT_DISCONNECT_ALL");
                    notifyDisconnectCompleted((DisconnectParams) msg.obj, false);
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcInactiveState nothandled msg.what=" + getWhatToString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcInactiveState mInactiveState = new DcInactiveState();

    /**
     * The state machine is retrying and expects a EVENT_RETRY_CONNECTION.
     */
    private class DcRetryingState extends State {
        @Override
        public void enter() {
            if (((mConnectionParams.mRilRat != mRilRat)
                    || (mDataRegState != ServiceState.STATE_IN_SERVICE
                    && (TelephonyManager.getDefault().getPhoneCount() <= 1)))
                    && !(mDcFailCauseManager.canHandleFailCause(mDcFailCause, null, null))) {
                // M: sm cause doesn't follow lost connection rule
                // RAT has changed or we're not in service so don't even begin retrying.
                if (DBG) {
                    String s = "DcRetryingState: enter() not retrying rat changed"
                        + ", mConnectionParams.mRilRat=" + mConnectionParams.mRilRat
                        + " != mRilRat:" + mRilRat
                        + " transitionTo(mInactiveState)";
                    logAndAddLogRec(s);
                }
                mInactiveState.setEnterNotificationParams(DcFailCause.LOST_CONNECTION);
                transitionTo(mInactiveState);
            } else {
                if (DBG) {
                    log("DcRetryingState: enter() mTag=" + mTag
                        + ", call notifyAllOfDisconnectDcRetrying lostConnection");
                }

                notifyAllOfDisconnectDcRetrying(Phone.REASON_LOST_DATA_CONNECTION);

                // Remove ourselves from cid mapping
                mDcController.removeActiveDcByCid(DataConnection.this);
                mCid = -1;
            }
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED:
                    AsyncResult ar = (AsyncResult) msg.obj;
                    Pair<Integer, Integer> drsRatPair = (Pair<Integer, Integer>) ar.result;
                    int drs = drsRatPair.first;
                    int rat = drsRatPair.second;
                    if ((rat == mRilRat) && (drs == mDataRegState)) {
                        if (DBG) {
                            log("DcRetryingState: EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED"
                                    + " strange no change in drs=" + drs
                                    + " rat=" + rat + " ignoring");
                        }
                    } else {
                        if (mDcFailCauseManager.canHandleFailCause(mDcFailCause, null, null)) {
                            //M: SM_CAUSE
                            if (DBG) {
                                log("DcRetryingState: EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED"
                                        + "because SM retry fail cause " + mDcFailCause
                                        + "ignoring this event");
                            }
                        } else {
                        // have to retry connecting since no attach event will come
                        if (mConnectionParams.mRetryWhenSSChange) {
                            retVal = NOT_HANDLED;
                            break;
                        }
                        // We've lost the connection and we're retrying but DRS or RAT changed
                        // so we may never succeed, might as well give up.
                        mInactiveState.setEnterNotificationParams(DcFailCause.LOST_CONNECTION);
                        deferMessage(msg);
                        transitionTo(mInactiveState);

                        if (DBG) {
                            String s = "DcRetryingState: EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED"
                                    + " giving up changed from " + mRilRat
                                    + " to rat=" + rat
                                    + " or drs changed from " + mDataRegState + " to drs=" + drs;
                            logAndAddLogRec(s);
                        }
                        }
                        mDataRegState = drs;
                        mRilRat = rat;
                        // TODO - pass the other type here too?
                        ServiceState ss = mPhone.getServiceState();
                        int networkType = ss.getDataNetworkType();
                        mNetworkInfo.setSubtype(networkType,
                                TelephonyManager.getNetworkTypeName(networkType));
                    }
                    retVal = HANDLED;
                    break;

                case EVENT_RETRY_CONNECTION: {
                    //in DcRetryingState
                    if (msg.arg1 == mTag) {
                        if (mDataRegState != ServiceState.STATE_IN_SERVICE) {
                            if (DBG) {
                                log("DcRetryingState: EVENT_RETRY_CONNECTION not in service");
                            }
                            mInactiveState.setEnterNotificationParams(DcFailCause.LOST_CONNECTION);
                            transitionTo(mInactiveState);
                        } else {
                            mRetryManager.increaseRetryCount();
                            if (DBG) {
                                log("DcRetryingState EVENT_RETRY_CONNECTION"
                                        + " RetryCount=" + mRetryManager.getRetryCount()
                                        + " mConnectionParams=" + mConnectionParams);
                            }
                            onConnect(mConnectionParams);
                            transitionTo(mActivatingState);
                        }
                    } else {
                        if (DBG) {
                            log("DcRetryingState stale EVENT_RETRY_CONNECTION"
                                    + " tag:" + msg.arg1 + " != mTag:" + mTag);
                        }
                    }
                    retVal = HANDLED;
                    break;
                }
                case DcAsyncChannel.REQ_RESET: {
                    if (DBG) {
                        log("DcRetryingState: msg.what=RSP_RESET, ignore we're already reset");
                    }
                    mInactiveState.setEnterNotificationParams(mConnectionParams,
                            DcFailCause.RESET_BY_FRAMEWORK);
                    transitionTo(mInactiveState);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_CONNECT: {
                    ConnectionParams cp = (ConnectionParams) msg.obj;
                    if (DBG) {
                        log("DcRetryingState: msg.what=EVENT_CONNECT"
                                + " RefCount=" + mApnContexts.size() + " cp=" + cp
                                + " mConnectionParams=" + mConnectionParams);
                    }

                    /** M: the connect request could be executed only if this is active data sub */
                    if (!DctController.getInstance().isActivePhone(mPhone.getPhoneId())) {
                        log("DcRetryingState: msg.what=EVENT_CONNECT but is not active data sub");
                        retVal = HANDLED;
                        break;
                    }

                    if (mDcFailCauseManager.canHandleFailCause(mDcFailCause, null,
                            cp.mApnContext.getReason())
                            && mRetryManager.isRetryNeeded()) {
                        // M : SM_CAUSE
                        // if sm_cause still work on timer. don't reconnect a new initConnection
                        log("DcRetryingState: msg.what=EVENT_CONNECT"
                                + "but sm cause retrying, so ignore this event. connect reason:"
                                + cp.mApnContext.getReason());
                        retVal = HANDLED;
                        break;
                    }

                    if (initConnection(cp)) {
                        onConnect(mConnectionParams);
                        transitionTo(mActivatingState);
                    } else {
                        if (DBG) {
                            log("DcRetryingState: msg.what=EVENT_CONNECT initConnection failed");
                        }
                        notifyConnectCompleted(cp, DcFailCause.UNACCEPTABLE_NETWORK_PARAMETER,
                                false);
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DISCONNECT: {
                    DisconnectParams dp = (DisconnectParams) msg.obj;

                    if (mApnContexts.remove(dp.mApnContext) && mApnContexts.size() == 0) {
                        if (DBG) {
                            log("DcRetryingState msg.what=EVENT_DISCONNECT " + " RefCount="
                                    + mApnContexts.size() + " dp=" + dp);
                        }
                        mInactiveState.setEnterNotificationParams(dp);
                        transitionTo(mInactiveState);
                    } else {
                        if (DBG) log("DcRetryingState: msg.what=EVENT_DISCONNECT");
                        notifyDisconnectCompleted(dp, false);
                    }

                    // M: [LTE][Low Power][UL traffic shaping] Start
                    checkIfDefaultApnReferenceCountChanged();
                    // M: [LTE][Low Power][UL traffic shaping] End

                    retVal = HANDLED;
                    break;
                }
                case EVENT_DISCONNECT_ALL: {
                    if (DBG) {
                        log("DcRetryingState msg.what=EVENT_DISCONNECT/DISCONNECT_ALL "
                                + "RefCount=" + mApnContexts.size());
                    }
                    mInactiveState.setEnterNotificationParams(DcFailCause.LOST_CONNECTION);
                    transitionTo(mInactiveState);
                    retVal = HANDLED;
                    break;
                }
                default: {
                    if (VDBG) {
                        log("DcRetryingState nothandled msg.what=" + getWhatToString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
                }
            }
            return retVal;
        }
    }
    private DcRetryingState mRetryingState = new DcRetryingState();

    /**
     * The state machine is activating a connection.
     */
    private class DcActivatingState extends State {
        @Override public void enter() {
            if (DBG) {
                log("DcActivatingState: enter dc=" + DataConnection.this);
            }
        }

        @Override public void exit() {
            if (DBG) log("DcActivatingState: exit dc=" + this);
        }

        // M: IPv6 RA update
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;
            AsyncResult ar;
            ConnectionParams cp;

            if (DBG) log("DcActivatingState: msg=" + msgToString(msg));
            switch (msg.what) {
                case EVENT_DATA_CONNECTION_DRS_OR_RAT_CHANGED:
                case EVENT_CONNECT:
                    // Activating can't process until we're done.
                    deferMessage(msg);
                    retVal = HANDLED;
                    break;

                case EVENT_SETUP_DATA_CONNECTION_DONE:
                    ar = (AsyncResult) msg.obj;
                    cp = (ConnectionParams) ar.userObj;

                    DataCallResponse.SetupResult result = onSetupConnectionCompleted(ar);
                    if (result != DataCallResponse.SetupResult.ERR_Stale) {
                        if (mConnectionParams != cp) {
                            loge("DcActivatingState: WEIRD mConnectionsParams:" + mConnectionParams
                                    + " != cp:" + cp);
                        }
                    }
                    if (DBG) {
                        log("DcActivatingState onSetupConnectionCompleted result=" + result
                                + " dc=" + DataConnection.this);
                    }
                    if (cp.mApnContext != null) {
                        cp.mApnContext.requestLog("onSetupConnectionCompleted result=" + result);
                    }
                    switch (result) {
                        case SUCCESS:
                            // All is well
                            mDcFailCause = DcFailCause.NONE;
                            transitionTo(mActiveState);
                            //MTK: SM_CAUSE
                            mRetryManager.resetRetryCount();
                            break;
                        case ERR_BadCommand:
                            // Vendor ril rejected the command and didn't connect.
                            // Transition to inactive but send notifications after
                            // we've entered the mInactive state.
                            mInactiveState.setEnterNotificationParams(cp, result.mFailCause);
                            transitionTo(mInactiveState);
                            break;
                        case ERR_UnacceptableParameter:
                            // The addresses given from the RIL are bad
                            tearDownData(cp);
                            transitionTo(mDisconnectingErrorCreatingConnection);
                            break;
                        case ERR_GetLastErrorFromRil:
                            // Request failed and this is an old RIL
                            mPhone.mCi.getLastDataCallFailCause(
                                    obtainMessage(EVENT_GET_LAST_FAIL_DONE, cp));
                            break;
                        case ERR_RilError:
                            int delay = mDcRetryAlarmController.getSuggestedRetryTime(
                                                                    DataConnection.this, ar);
                            String str = "DcActivatingState: ERR_RilError "
                                    + " delay=" + delay
                                    + " isRetryNeeded=" + mRetryManager.isRetryNeeded()
                                    + " result=" + result
                                    + " result.isRestartRadioFail=" +
                                    result.mFailCause.isRestartRadioFail()
                                    + " result.isPermanentFail=" +
                                    mDct.isPermanentFail(result.mFailCause);
                            if (DBG) log(str);
                            if (cp.mApnContext != null) cp.mApnContext.requestLog(str);
                            if (mDcFailCauseManager.canHandleFailCause(result.mFailCause,
                                    mRetryManager, null)) {
                                //At least one IPv4 or IPv6 is accepted, setup connection
                                onSetupFallbackConnection(ar);
                                if (mRetryManager.isRetryNeeded()) {
                                    delay = mRetryManager.getRetryTimer();
                                    if (DBG) {
                                        log("DcActivatingState: FALLBACK PDP retry start,"
                                                + " only one IPv4 or IPv6 is accepted,"
                                                + " change delay=" + delay);
                                    }
                                    // Enter active state but with EVENT_FALLBACK_RETRY_CONNECTION
                                    mDcFailCause = DcFailCause.PDP_FAIL_FALLBACK_RETRY;
                                    mDcRetryAlarmController.startRetryAlarm(
                                                EVENT_FALLBACK_RETRY_CONNECTION, mTag, delay);
                                    transitionTo(mActiveState);
                                } else {
                                    if (DBG) {
                                        log("DcActivatingState: No FALLBACK PDP retry start"
                                                + " but at least one IPv4 or IPv6 is accepted");
                                    }
                                    // Enter active state for this case
                                    mDcFailCause = DcFailCause.NONE;
                                    transitionTo(mActiveState);
                                    //MTK: SM_CAUSE
                                    mRetryManager.resetRetryCount();
                                }
                            } else if (result.mFailCause.isRestartRadioFail() ||
                                    (cp.mApnContext != null &&
                                    cp.mApnContext.restartOnError(
                                    result.mFailCause.getErrorCode()))) {
                                if (DBG) log("DcActivatingState: ERR_RilError restart radio");
                                mDct.sendRestartRadio();
                                mInactiveState.setEnterNotificationParams(cp, result.mFailCause);
                                transitionTo(mInactiveState);
                            } else if (mDct.isPermanentFail(result.mFailCause)) {
                                if (DBG) log("DcActivatingState: ERR_RilError perm error");
                                mInactiveState.setEnterNotificationParams(cp, result.mFailCause);
                                transitionTo(mInactiveState);
                            } else if (delay >= 0) {
                                if (DBG) log("DcActivatingState: ERR_RilError retry");
                                mDcRetryAlarmController.startRetryAlarm(EVENT_RETRY_CONNECTION,
                                                            mTag, delay);
                                transitionTo(mRetryingState);
                            } else {
                                if (DBG) log("DcActivatingState: ERR_RilError no retry");
                                mInactiveState.setEnterNotificationParams(cp, result.mFailCause);
                                transitionTo(mInactiveState);
                            }
                            break;
                        case ERR_Stale:
                            loge("DcActivatingState: stale EVENT_SETUP_DATA_CONNECTION_DONE"
                                    + " tag:" + cp.mTag + " != mTag:" + mTag);
                            break;
                        default:
                            throw new RuntimeException("Unknown SetupResult, should not happen");
                    }
                    retVal = HANDLED;
                    break;

                case EVENT_GET_LAST_FAIL_DONE:
                    ar = (AsyncResult) msg.obj;
                    cp = (ConnectionParams) ar.userObj;
                    if (cp.mTag == mTag) {
                        if (mConnectionParams != cp) {
                            loge("DcActivatingState: WEIRD mConnectionsParams:" + mConnectionParams
                                    + " != cp:" + cp);
                        }

                        DcFailCause cause = DcFailCause.UNKNOWN;

                        if (ar.exception == null) {
                            int rilFailCause = ((int[]) (ar.result))[0];
                            cause = DcFailCause.fromInt(rilFailCause);
                            if (cause == DcFailCause.NONE) {
                                if (DBG) {
                                    log("DcActivatingState msg.what=EVENT_GET_LAST_FAIL_DONE"
                                            + " BAD: error was NONE, change to UNKNOWN");
                                }
                                cause = DcFailCause.UNKNOWN;
                            }
                        }
                        mDcFailCause = cause;

                        //[SM_CAUSE] new mechanism
                        if (mDcFailCauseManager.canHandleFailCause(cause, mRetryManager, null)) {
                            //both IPv4 and IPv6 are rejected
                            //or sm retry cause config
                            log("DcActivatingState: DcFailCauseManager re-configure retry manager");
                        } else {
                            //not special sm cause, restore retry config
                            int tmpCount = mRetryManager.getRetryCount();
                            configureRetry(
                                    mApnSetting.canHandleType(PhoneConstants.APN_TYPE_DEFAULT));
                            mRetryManager.setRetryCount(tmpCount);
                        }

                        int retryDelay = mRetryManager.getRetryTimer();
                        if (DBG) {
                            log("DcActivatingState msg.what=EVENT_GET_LAST_FAIL_DONE"
                                    + " cause=" + cause
                                    + " retryDelay=" + retryDelay
                                    + " isRetryNeeded=" + mRetryManager.isRetryNeeded()
                                    + " dc=" + DataConnection.this);
                        }
                        if (cause.isRestartRadioFail()) {
                            if (DBG) {
                                log("DcActivatingState: EVENT_GET_LAST_FAIL_DONE"
                                        + " restart radio");
                            }
                            mDct.sendRestartRadio();
                            mInactiveState.setEnterNotificationParams(cp, cause);
                            transitionTo(mInactiveState);
                        } else if (mDcFailCauseManager.canHandleFailCause(cause, null, null)
                                && mRetryManager.isRetryNeeded()) {
                            if (DBG) {
                                log("DcActivatingState: mOperator="
                                        + mDcFailCauseManager.mOperator);
                            }
                            if (mDcFailCauseManager.mOperator ==
                                    DcFailCauseManager.Operator.OP002Ext) {
                                if (DBG) {
                                    log("DcActivatingState:"
                                            +" EVENT_GET_LAST_FAIL_DONE FALLBACK retry");
                                }
                                mDcRetryAlarmController.startRetryAlarm(EVENT_RETRY_CONNECTION,
                                        mTag, retryDelay);
                                transitionTo(mRetryingState);
                            } else if (mDcFailCauseManager.mOperator ==
                                    DcFailCauseManager.Operator.OP001Ext) {
                                if (DBG) log("DcActivatingState: SM_CAUSE need retry");
                                mDcRetryAlarmController.startRetryAlarmExact(EVENT_RETRY_CONNECTION,
                                        mTag, retryDelay);
                                transitionTo(mRetryingState);
                            } else {
                                String dbgMsg = "DcActivatingState: SM_CAUSE no retry"
                                                + " because exceed retry count";
                                if (DBG) log(dbgMsg);
                                mInactiveState.setEnterNotificationParams(cp, cause);
                                transitionTo(mInactiveState);
                            }

                        } else if (mDct.isPermanentFail(cause) || checkIgnoredCause(cause)) {
                            if (DBG) log("DcActivatingState: EVENT_GET_LAST_FAIL_DONE perm er");
                            mInactiveState.setEnterNotificationParams(cp, cause);
                            transitionTo(mInactiveState);
                        } else if (mIsTestSim &&
                                    (cause == DcFailCause.MULTIPLE_PDN_APN_NOT_ALLOWED ||
                                      cause == DcFailCause.DUE_TO_REACH_RETRY_COUNTER)) {
                            // no retry to prevent
                            if (DBG) log("DcActivatingState: no retry by NW reject");
                            mInactiveState.setEnterNotificationParams(cp, cause);
                            transitionTo(mInactiveState);
                        } else if (((retryDelay >= 0) && (mRetryManager.isRetryNeeded()))) {
                            if (DBG) log("DcActivatingState: EVENT_GET_LAST_FAIL_DONE retry");
                            mDcRetryAlarmController.startRetryAlarm(EVENT_RETRY_CONNECTION, mTag,
                                                            retryDelay);
                            transitionTo(mRetryingState);
                        } else {
                            if (DBG) log("DcActivatingState: EVENT_GET_LAST_FAIL_DONE no retry");
                            mInactiveState.setEnterNotificationParams(cp, cause);
                            transitionTo(mInactiveState);
                        }
                    } else {
                        loge("DcActivatingState: stale EVENT_GET_LAST_FAIL_DONE"
                                + " tag:" + cp.mTag + " != mTag:" + mTag);
                    }

                    retVal = HANDLED;
                    break;

                // M: IPv6 RA update
                case EVENT_IPV4_ADDRESS_REMOVED:
                case EVENT_IPV6_ADDRESS_REMOVED:
                    log("DcActivatingState deferMsg: " + getWhatToString(msg.what) + ", address info: " + (AddressInfo) msg.obj);
                    deferMessage(msg);
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcActivatingState not handled msg.what=" +
                                getWhatToString(msg.what) + " RefCount=" + mApnContexts.size());
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcActivatingState mActivatingState = new DcActivatingState();

    /**
     * The state machine is connected, expecting an EVENT_DISCONNECT.
     */
    private class DcActiveState extends State {
        @Override public void enter() {
            if (DBG) log("DcActiveState: enter dc=" + DataConnection.this);

            if (mRetryManager.getRetryCount() != 0) {
                log("DcActiveState: connected after retrying call notifyAllOfConnected");
                mRetryManager.setRetryCount(0);
            }
            // If we were retrying there maybe more than one, otherwise they'll only be one.
            notifyAllOfConnected(Phone.REASON_CONNECTED);

            mPhone.getCallTracker().registerForVoiceCallStarted(getHandler(),
                    DataConnection.EVENT_DATA_CONNECTION_VOICE_CALL_STARTED, null);
            mPhone.getCallTracker().registerForVoiceCallEnded(getHandler(),
                    DataConnection.EVENT_DATA_CONNECTION_VOICE_CALL_ENDED, null);

            // If the EVENT_CONNECT set the current max retry restore it here
            // if it didn't then this is effectively a NOP.
            mRetryManager.restoreCurMaxRetryCount();
            mDcController.addActiveDcByCid(DataConnection.this);

            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.CONNECTED,
                    mNetworkInfo.getReason(), null);
            mNetworkInfo.setExtraInfo(mApnSetting.apn);
            updateTcpBufferSizes(mRilRat);

            final NetworkMisc misc = new NetworkMisc();
            misc.subscriberId = mPhone.getSubscriberId();

            // VoLTE
            // TODO: need to double check if this can be removed for IMS refactory [start]
            int nNetworkType = ConnectivityManager.TYPE_NONE;
            mNetworkInfo.setType(ConnectivityManager.TYPE_MOBILE);
            for (String apnType: mApnSetting.types) {
                if (TextUtils.equals(apnType, PhoneConstants.APN_TYPE_IMS)) {
                    nNetworkType = ConnectivityManager.TYPE_MOBILE_IMS;;
                    mNetworkInfo.setType(nNetworkType);
                    break;
                } else if (TextUtils.equals(apnType, PhoneConstants.APN_TYPE_EMERGENCY)) {
                    nNetworkType = ConnectivityManager.TYPE_MOBILE_EMERGENCY;
                    mNetworkInfo.setType(nNetworkType);
                    break;
                }
            }
            mNetworkAgent = new DcNetworkAgent(getHandler().getLooper(), mPhone.getContext(),
                    "DcNetworkAgent", mNetworkInfo, makeNetworkCapabilities(), mLinkProperties,
                    50, misc);

            /* for op01 begin */
            if (!mIsBsp) {
                try {
                    mGsmDCTExt.onDcActivated(
                        mApnSetting == null ? null : mApnSetting.types,
                        mLinkProperties == null ? "" : mLinkProperties.getInterfaceName());
                } catch (Exception e) {
                    loge("onDcActivated fail!");
                    e.printStackTrace();
                }
            }
            /* for op01 end */
        }

        @Override
        public void exit() {
            if (DBG) log("DcActiveState: exit dc=" + this);

            String reason = mNetworkInfo.getReason();
            if(mDcController.isExecutingCarrierChange()) {
                reason = Phone.REASON_CARRIER_CHANGE;
            } else if (mDisconnectParams != null && mDisconnectParams.mReason != null) {
                reason = mDisconnectParams.mReason;
            } else if (mDcFailCause != null) {
                reason = mDcFailCause.toString();
            }

            /* for op01 begin */
            if (!mIsBsp) {
                try {
                    mGsmDCTExt.onDcDeactivated(
                        mApnSetting == null ? null : mApnSetting.types,
                        mLinkProperties == null ? "" : mLinkProperties.getInterfaceName());
                } catch (Exception e) {
                    loge("onDcDeactivated fail!");
                    e.printStackTrace();
                }
            }
            /* for op01 end */

            mPhone.getCallTracker().unregisterForVoiceCallStarted(getHandler());
            mPhone.getCallTracker().unregisterForVoiceCallEnded(getHandler());

            mNetworkInfo.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED,
                    reason, mNetworkInfo.getExtraInfo());
            mNetworkAgent.sendNetworkInfo(mNetworkInfo);
            mNetworkAgent = null;
        }

        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case EVENT_CONNECT: {
                    ConnectionParams cp = (ConnectionParams) msg.obj;
                    if (DBG) {
                        log("DcActiveState: EVENT_CONNECT cp=" + cp + " dc=" + DataConnection.this);
                    }
                    if (mApnContexts.contains(cp.mApnContext)) {
                        log("DcActiveState ERROR already added apnContext=" + cp.mApnContext);
                    } else {
                        mApnContexts.add(cp.mApnContext);
                        if (DBG) {
                            log("DcActiveState msg.what=EVENT_CONNECT RefCount="
                                    + mApnContexts.size());
                        }

                        // M: [LTE][Low Power][UL traffic shaping] Start
                        checkIfDefaultApnReferenceCountChanged();
                        // M: [LTE][Low Power][UL traffic shaping] End

                        NetworkCapabilities cap = makeNetworkCapabilities();
                        mNetworkAgent.sendNetworkCapabilities(cap);
                        log("DcActiveState update Capabilities:" + cap);
                    }
                    notifyConnectCompleted(cp, DcFailCause.NONE, false);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DISCONNECT: {
                    DisconnectParams dp = (DisconnectParams) msg.obj;
                    if (DBG) {
                        log("DcActiveState: EVENT_DISCONNECT dp=" + dp
                                + " dc=" + DataConnection.this);
                    }
                    if (mApnContexts.contains(dp.mApnContext)) {
                        if (DBG) {
                            log("DcActiveState msg.what=EVENT_DISCONNECT RefCount="
                                    + mApnContexts.size());
                        }

                        if (mApnContexts.size() == 1) {
                            mApnContexts.clear();
                            mDisconnectParams = dp;
                            mConnectionParams = null;
                            dp.mTag = mTag;
                            tearDownData(dp);
                            transitionTo(mDisconnectingState);
                        } else {
                            mApnContexts.remove(dp.mApnContext);

                            // M : notify Capability if default removed, need remove "internet"
                            if (mNetworkAgent != null) {
                                NetworkCapabilities cap = makeNetworkCapabilities();
                                mNetworkAgent.sendNetworkCapabilities(cap);
                                log("DcActiveState update Capabilities:" + cap);
                            }

                            // M: [LTE][Low Power][UL traffic shaping] Start
                            checkIfDefaultApnReferenceCountChanged();
                            // M: [LTE][Low Power][UL traffic shaping] End

                            notifyDisconnectCompleted(dp, false);
                        }
                    } else {
                        log("DcActiveState ERROR no such apnContext=" + dp.mApnContext
                                + " in this dc=" + DataConnection.this);
                        notifyDisconnectCompleted(dp, false);
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DISCONNECT_ALL: {
                    if (DBG) {
                        log("DcActiveState EVENT_DISCONNECT_ALL clearing apn contexts,"
                                + " dc=" + DataConnection.this);
                    }
                    DisconnectParams dp = (DisconnectParams) msg.obj;
                    mDisconnectParams = dp;
                    mConnectionParams = null;
                    dp.mTag = mTag;
                    tearDownData(dp);
                    transitionTo(mDisconnectingState);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_LOST_CONNECTION: {
                    if (DBG) {
                        log("DcActiveState EVENT_LOST_CONNECTION dc=" + DataConnection.this);
                    }
                    if (mRetryManager.isRetryNeeded()) {
                        // We're going to retry
                        int delayMillis = mRetryManager.getRetryTimer();
                        if (DBG) {
                            log("DcActiveState EVENT_LOST_CONNECTION startRetryAlarm"
                                    + " mTag=" + mTag + " delay=" + delayMillis + "ms");
                        }
                        mDcRetryAlarmController.startRetryAlarm(EVENT_RETRY_CONNECTION, mTag,
                                delayMillis);
                        transitionTo(mRetryingState);
                    } else {
                        mInactiveState.setEnterNotificationParams(DcFailCause.LOST_CONNECTION);
                        transitionTo(mInactiveState);
                    }
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DATA_CONNECTION_ROAM_ON: {
                    mNetworkInfo.setRoaming(true);
                    mNetworkAgent.sendNetworkInfo(mNetworkInfo);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_DATA_CONNECTION_ROAM_OFF: {
                    mNetworkInfo.setRoaming(false);
                    mNetworkAgent.sendNetworkInfo(mNetworkInfo);
                    retVal = HANDLED;
                    break;
                }
                case EVENT_BW_REFRESH_RESPONSE: {
                    AsyncResult ar = (AsyncResult)msg.obj;
                    if (ar.exception != null) {
                        log("EVENT_BW_REFRESH_RESPONSE: error ignoring, e=" + ar.exception);
                    } else {
                        final ArrayList<Integer> capInfo = (ArrayList<Integer>)ar.result;
                        final int lceBwDownKbps = capInfo.get(0);
                        NetworkCapabilities nc = makeNetworkCapabilities();
                        if (mPhone.getLceStatus() == RILConstants.LCE_ACTIVE) {
                            nc.setLinkDownstreamBandwidthKbps(lceBwDownKbps);
                            if (mNetworkAgent != null) {
                                mNetworkAgent.sendNetworkCapabilities(nc);
                            }
                        }
                    }
                    retVal = HANDLED;
                    break;
                }
                //FALLBACK PDP Retry
                case EVENT_FALLBACK_RETRY_CONNECTION:
                    if (msg.arg1 == mTag) {
                        if (mDataRegState != ServiceState.STATE_IN_SERVICE) {
                            if (DBG) {
                                log("DcActiveState: EVENT_FALLBACK_RETRY_CONNECTION"
                                    + " not in service");
                            }
                        } else {
                            mRetryManager.increaseRetryCount();
                            if (DBG) {
                                log("DcActiveState EVENT_FALLBACK_RETRY_CONNECTION"
                                        + " RetryCount=" + mRetryManager.getRetryCount()
                                        + " mConnectionParams=" + mConnectionParams);
                            }
                            onConnect(mConnectionParams);
                        }
                    } else {
                        if (DBG) {
                            log("DcActiveState stale EVENT_FALLBACK_RETRY_CONNECTION"
                                    + " tag:" + msg.arg1 + " != mTag:" + mTag);
                        }
                    }
                    retVal = HANDLED;
                    break;
                //FALLBACK PDP Retry
                case EVENT_SETUP_DATA_CONNECTION_DONE: {
                    AsyncResult ar = (AsyncResult) msg.obj;
                    ConnectionParams cp = (ConnectionParams) ar.userObj;

                    DataCallResponse.SetupResult result = onSetupConnectionCompleted(ar);
                    if (result != DataCallResponse.SetupResult.ERR_Stale) {
                        if (mConnectionParams != cp) {
                            loge("DcActiveState_FALLBACK_Retry: WEIRD mConnectionsParams:"
                                    + mConnectionParams + " != cp:" + cp);
                        }
                    }
                    if (DBG) {
                        log("DcActiveState_FALLBACK_Retry onSetupConnectionCompleted result="
                                + result + " dc=" + DataConnection.this);
                    }
                    switch (result) {
                        case SUCCESS:
                            // All is well
                            mDcFailCause = DcFailCause.NONE;
                            //MTK: SM_CAUSE
                            mRetryManager.resetRetryCount();
                            break;
                        case ERR_GetLastErrorFromRil:
                            // Request failed and this is an old RIL
                            mPhone.mCi.getLastDataCallFailCause(
                                    obtainMessage(EVENT_FALLBACK_GET_LAST_FAIL_DONE, cp));
                            break;
                        case ERR_RilError:
                            if (DBG) {
                                log("DcActiveState_FALLBACK_Retry: ERR_RilError "
                                        + " isRetryNeeded=" + mRetryManager.isRetryNeeded()
                                        + " result=" + result
                                        + " result.isRestartRadioFail=" +
                                        result.mFailCause.isRestartRadioFail()
                                        + " result.isPermanentFail=" +
                                        mDct.isPermanentFail(result.mFailCause));
                            }
                            if (mDcFailCauseManager.canHandleFailCause(result.mFailCause,
                                    mRetryManager, null)) {
                                if (mRetryManager.isRetryNeeded()) {
                                    if (DBG) {
                                        log("DcActiveState_FALLBACK_Retry:"
                                                + " only one IPv4 or IPv6 is accepted");
                                    }
                                    int delay = mRetryManager.getRetryTimer();
                                    if (DBG) {
                                        log("DcActiveState_FALLBACK_Retry: delay=" + delay);
                                    }

                                    // Enter active state but with EVENT_FALLBACK_RETRY_CONNECTION
                                    mDcFailCause = DcFailCause.PDP_FAIL_FALLBACK_RETRY;
                                    mDcRetryAlarmController.startRetryAlarm(
                                            EVENT_FALLBACK_RETRY_CONNECTION, mTag, delay);
                                } else {
                                    if (DBG) {
                                        log("DcActiveState_FALLBACK_Retry: No retry"
                                                + " but at least one IPv4 or IPv6 is accepted");
                                    }
                                    // Not to do retry anymore
                                    mDcFailCause = DcFailCause.NONE;
                                }
                            } else {
                                if (DBG) {
                                    log("DcActiveState_FALLBACK_Retry: ERR_RilError"
                                                + " Not retry anymore");
                                }
                            }
                            break;
                        case ERR_Stale:
                            loge("DcActiveState_FALLBACK_Retry:"
                                    + " stale EVENT_SETUP_DATA_CONNECTION_DONE"
                                    + " tag:" + cp.mTag + " != mTag:" + mTag
                                    + " Not retry anymore");
                            break;
                        default:
                            if (DBG) {
                                log("DcActiveState_FALLBACK_Retry: Another error cause,"
                                            + " Not retry anymore");
                            }
                    }
                    retVal = HANDLED;
                    break;
                }
                //FALLBACK PDP Retry
                case EVENT_FALLBACK_GET_LAST_FAIL_DONE: {
                    AsyncResult ar = (AsyncResult) msg.obj;
                    ConnectionParams cp = (ConnectionParams) ar.userObj;
                    if (cp.mTag == mTag) {
                        if (mConnectionParams != cp) {
                            loge("DcActiveState_FALLBACK_Retry: WEIRD mConnectionsParams:"
                                    + mConnectionParams + " != cp:" + cp);
                        }

                        DcFailCause cause = DcFailCause.UNKNOWN;

                        if (ar.exception == null) {
                            int rilFailCause = ((int[]) (ar.result))[0];
                            cause = DcFailCause.fromInt(rilFailCause);
                            if (cause == DcFailCause.NONE) {
                                if (DBG) {
                                    log("DcActiveState_FALLBACK_Retry"
                                            + " msg.what=EVENT_FALLBACK_GET_LAST_FAIL_DONE"
                                            + " BAD: error was NONE, change to UNKNOWN");
                                }
                                cause = DcFailCause.UNKNOWN;
                            }
                        }
                        mDcFailCause = cause;

                        //[SM_CAUSE] new mechanism
                        if (mDcFailCauseManager.canHandleFailCause(cause, mRetryManager, null)) {
                            //one IPv4 or IPv6 is rejected again
                            if (DBG) {
                                log("DcActiveState_FALLBACK_Retry:"
                                          + " one IPv4 or IPv6 is rejected again");
                            }
                        }

                        int retryDelay = mRetryManager.getRetryTimer();
                        if (DBG) {
                            log("DcActiveState_FALLBACK_Retry msg.what="
                                    + "EVENT_FALLBACK_GET_LAST_FAIL_DONE"
                                    + " cause=" + cause
                                    + " retryDelay=" + retryDelay
                                    + " isRetryNeeded=" + mRetryManager.isRetryNeeded()
                                    + " dc=" + DataConnection.this);
                        }
                        if (mRetryManager.isRetryNeeded() &&
                                mDcFailCauseManager.canHandleFailCause(cause, null, null)) {
                            if (DBG) {
                                log("DcActiveState_FALLBACK_Retry:"
                                            + " EVENT_FALLBACK_GET_LAST_FAIL_DONE start retry");
                            }
                            mDcRetryAlarmController.startRetryAlarm(EVENT_FALLBACK_RETRY_CONNECTION,
                                                            mTag, retryDelay);
                        } else {
                            if (DBG) {
                                log("DcActiveState_FALLBACK_Retry:"
                                        + " EVENT_FALLBACK_GET_LAST_FAIL_DONE not retry anymore");
                            }
                        }
                    } else {
                        loge("DcActiveState_FALLBACK_Retry: stale EVENT_FALLBACK_GET_LAST_FAIL_DONE"
                                + " tag:" + cp.mTag + " != mTag:" + mTag + " not retry anymore");
                    }

                    retVal = HANDLED;
                    break;
                }

                // M: IPv6 RA update
                case EVENT_IPV4_ADDRESS_REMOVED:
                    AddressInfo addrV4Info = (AddressInfo) msg.obj;
                    if (DBG) {
                        log("DcActiveState: " + getWhatToString(msg.what) + ": " + addrV4Info);
                    }
                    // TODO: currently do nothing here
                    retVal = HANDLED;
                    break;

                case EVENT_IPV6_ADDRESS_REMOVED:
                    AddressInfo addrV6Info = (AddressInfo) msg.obj;
                    if (DBG) {
                        log("DcActiveState: " + getWhatToString(msg.what) + ": " + addrV6Info);
                    }
                    if (mInterfaceName != null && mInterfaceName.equals(addrV6Info.mIntfName)) {
                        long valid;
                        if (!mIsBsp) {
                            try {
                            valid = mGsmDCTExt.getIPv6Valid(addrV6Info.mLinkAddr);
                            //M: this api getValid is not exist in BSP package
                            } catch (Exception e) {
                                loge("DcActiveState: getIPv6Valid fail!");
                                valid = -1000;
                                e.printStackTrace();
                            }

                            if (valid == 0 || valid == -1) {
                                log("DcActiveState: RA is failed or life time expired, valid:" + valid);
                                onAddressRemoved();
                            }
                        }
                    }

                    retVal = HANDLED;
                    break;
                case EVENT_DATA_CONNECTION_VOICE_CALL_STARTED:
                case EVENT_DATA_CONNECTION_VOICE_CALL_ENDED: {
                    if (updateNetworkInfoSuspendState()) {
                        // state changed
                        mNetworkAgent.sendNetworkInfo(mNetworkInfo);
                    }
                    retVal = HANDLED;
                    break;
                }
                default:
                    if (VDBG) {
                        log("DcActiveState not handled msg.what=" + getWhatToString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcActiveState mActiveState = new DcActiveState();

    /**
     * The state machine is disconnecting.
     */
    private class DcDisconnectingState extends State {
        @Override public void enter() {
        }
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case EVENT_CONNECT:
                    if (DBG) log("DcDisconnectingState msg.what=EVENT_CONNECT. Defer. RefCount = "
                            + mApnContexts.size());
                    deferMessage(msg);
                    retVal = HANDLED;
                    break;

                case EVENT_DEACTIVATE_DONE:
                    AsyncResult ar = (AsyncResult) msg.obj;
                    DisconnectParams dp = (DisconnectParams) ar.userObj;

                    String str = "DcDisconnectingState msg.what=EVENT_DEACTIVATE_DONE RefCount="
                            + mApnContexts.size();
                    if (DBG) log(str);
                    if (dp.mApnContext != null) dp.mApnContext.requestLog(str);

                    if (dp.mTag == mTag) {
                        // Transition to inactive but send notifications after
                        // we've entered the mInactive state.

                        mInactiveState.setEnterNotificationParams((DisconnectParams) ar.userObj);
                        transitionTo(mInactiveState);
                    } else {
                        if (DBG) log("DcDisconnectState stale EVENT_DEACTIVATE_DONE"
                                + " dp.tag=" + dp.mTag + " mTag=" + mTag);
                    }
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcDisconnectingState not handled msg.what="
                                + getWhatToString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcDisconnectingState mDisconnectingState = new DcDisconnectingState();

    /**
     * The state machine is disconnecting after an creating a connection.
     */
    private class DcDisconnectionErrorCreatingConnection extends State {
        @Override public void enter() {
        }
        @Override
        public boolean processMessage(Message msg) {
            boolean retVal;

            switch (msg.what) {
                case EVENT_DEACTIVATE_DONE:
                    AsyncResult ar = (AsyncResult) msg.obj;
                    ConnectionParams cp = (ConnectionParams) ar.userObj;
                    if (cp.mTag == mTag) {
                        String str = "DcDisconnectionErrorCreatingConnection" +
                                " msg.what=EVENT_DEACTIVATE_DONE";
                        if (DBG) log(str);
                        if (cp.mApnContext != null) cp.mApnContext.requestLog(str);

                        // Transition to inactive but send notifications after
                        // we've entered the mInactive state.
                        mInactiveState.setEnterNotificationParams(cp,
                                DcFailCause.UNACCEPTABLE_NETWORK_PARAMETER);
                        transitionTo(mInactiveState);
                    } else {
                        if (DBG) {
                            log("DcDisconnectionErrorCreatingConnection stale EVENT_DEACTIVATE_DONE"
                                    + " dp.tag=" + cp.mTag + ", mTag=" + mTag);
                        }
                    }
                    retVal = HANDLED;
                    break;

                default:
                    if (VDBG) {
                        log("DcDisconnectionErrorCreatingConnection not handled msg.what="
                                + getWhatToString(msg.what));
                    }
                    retVal = NOT_HANDLED;
                    break;
            }
            return retVal;
        }
    }
    private DcDisconnectionErrorCreatingConnection mDisconnectingErrorCreatingConnection =
                new DcDisconnectionErrorCreatingConnection();


    private class DcNetworkAgent extends NetworkAgent {
        public DcNetworkAgent(Looper l, Context c, String TAG, NetworkInfo ni,
                NetworkCapabilities nc, LinkProperties lp, int score, NetworkMisc misc) {
            super(l, c, TAG, ni, nc, lp, score, misc);
        }

        @Override
        protected void unwanted() {
            if (mNetworkAgent != this) {
                log("DcNetworkAgent: unwanted found mNetworkAgent=" + mNetworkAgent +
                        ", which isn't me.  Aborting unwanted");
                return;
            }

            if (DBG) log("DcNetworkAgent unwanted!");
            // this can only happen if our exit has been called - we're already disconnected
            if (mApnContexts == null) return;
            for (ApnContext apnContext : mApnContexts) {
                log("DcNetworkAgent: [unwanted]: disconnect apnContext=" + apnContext);
                Message msg = mDct.obtainMessage(DctConstants.EVENT_DISCONNECT_DONE, apnContext);
                DisconnectParams dp = new DisconnectParams(apnContext, apnContext.getReason(), msg);
                DataConnection.this.sendMessage(DataConnection.this.
                        obtainMessage(EVENT_DISCONNECT, dp));
            }
        }

        @Override
        protected void pollLceData() {
            if(mPhone.getLceStatus() == RILConstants.LCE_ACTIVE) {  // active LCE service
                mPhone.mCi.pullLceData(DataConnection.this.obtainMessage(EVENT_BW_REFRESH_RESPONSE));
            }
        }
    }

    // M: ipv6
    private class AddressInfo {
        String mIntfName;
        LinkAddress mLinkAddr;

        public AddressInfo(String intfName, LinkAddress linkAddr) {
            mIntfName = intfName;
            mLinkAddr = linkAddr;
        }

        public String toString() {
            return "interfaceName: "  + mIntfName + "/" + mLinkAddr;
        }
    }

    // ******* "public" interface

    /**
     * Used for testing purposes.
     */
    /* package */ void tearDownNow() {
        if (DBG) log("tearDownNow()");
        sendMessage(obtainMessage(EVENT_TEAR_DOWN_NOW));
    }

    /**
     * @return the string for msg.what as our info.
     */
    @Override
    protected String getWhatToString(int what) {
        return cmdToString(what);
    }

    private static String msgToString(Message msg) {
        String retVal;
        if (msg == null) {
            retVal = "null";
        } else {
            StringBuilder   b = new StringBuilder();

            b.append("{what=");
            b.append(cmdToString(msg.what));

            b.append(" when=");
            TimeUtils.formatDuration(msg.getWhen() - SystemClock.uptimeMillis(), b);

            if (msg.arg1 != 0) {
                b.append(" arg1=");
                b.append(msg.arg1);
            }

            if (msg.arg2 != 0) {
                b.append(" arg2=");
                b.append(msg.arg2);
            }

            if (msg.obj != null) {
                b.append(" obj=");
                b.append(msg.obj);
            }

            b.append(" target=");
            b.append(msg.getTarget());

            b.append(" replyTo=");
            b.append(msg.replyTo);

            b.append("}");

            retVal = b.toString();
        }
        return retVal;
    }

    static void slog(String s) {
        Rlog.d("DC", s);
    }

    /**
        * Log with debug.
        *
        * @param s is string log
        */
    @Override
    protected void log(String s) {
        Rlog.d(getName(), s);
    }

    /**
        * Log with debug attribute.
        *
        * @param s is string log
        */
    @Override
    protected void logd(String s) {
        Rlog.d(getName(), s);
    }

    /**
        * Log with verbose attribute.
        *
        * @param s is string log
        */
    @Override
    protected void logv(String s) {
        Rlog.v(getName(), s);
    }

    /**
        * Log with info attribute.
        *
        * @param s is string log
        */
    @Override
    protected void logi(String s) {
        Rlog.i(getName(), s);
    }

    /**
        * Log with warning attribute.
        *
        * @param s is string log
        */
    @Override
    protected void logw(String s) {
        Rlog.w(getName(), s);
    }

    /**
        * Log with error attribute.
        *
        * @param s is string log
        */
    @Override
    protected void loge(String s) {
        Rlog.e(getName(), s);
    }

    /**
        * Log with error attribute.
        *
        * @param s is string log
        * @param e is a Throwable which logs additional information.
        */
    @Override
    protected void loge(String s, Throwable e) {
        Rlog.e(getName(), s, e);
    }

    /** Doesn't print mApnList of ApnContext's which would be recursive. */
    public String toStringSimple() {
        return getName() + ": State=" + getCurrentState().getName()
                + " mApnSetting=" + mApnSetting + " RefCount=" + mApnContexts.size()
                + " mCid=" + mCid + " mCreateTime=" + mCreateTime
                + " mLastastFailTime=" + mLastFailTime
                + " mLastFailCause=" + mLastFailCause
                + " mTag=" + mTag
                + " mRetryManager=" + mRetryManager
                + " mDcFailCauseManager=" + mDcFailCauseManager
                + " mLinkProperties=" + mLinkProperties
                + " linkCapabilities=" + makeNetworkCapabilities();
    }

    @Override
    public String toString() {
        return "{" + toStringSimple() + " mApnContexts=" + mApnContexts + "}";
    }

    private void dumpToLog() {
        dump(null, new PrintWriter(new StringWriter(0)) {
            @Override
            public void println(String s) {
                DataConnection.this.logd(s);
            }

            @Override
            public void flush() {
            }
        }, null);
    }

    /**
        * Dump the current state.
        *
        * @param fd
        * @param pw
        * @param args
        */
    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("DataConnection ");
        super.dump(fd, pw, args);
        pw.println(" mApnContexts.size=" + mApnContexts.size());
        pw.println(" mApnContexts=" + mApnContexts);
        pw.flush();
        pw.println(" mDataConnectionTracker=" + mDct);
        pw.println(" mApnSetting=" + mApnSetting);
        pw.println(" mTag=" + mTag);
        pw.println(" mCid=" + mCid);
        pw.println(" mRetryManager=" + mRetryManager);
        pw.println(" mDcFailCauseManager=" + mDcFailCauseManager);
        pw.println(" mConnectionParams=" + mConnectionParams);
        pw.println(" mDisconnectParams=" + mDisconnectParams);
        pw.println(" mDcFailCause=" + mDcFailCause);
        pw.flush();
        pw.println(" mPhone=" + mPhone);
        pw.flush();
        pw.println(" mLinkProperties=" + mLinkProperties);
        pw.flush();
        pw.println(" mDataRegState=" + mDataRegState);
        pw.println(" mRilRat=" + mRilRat);
        pw.println(" mNetworkCapabilities=" + makeNetworkCapabilities());
        pw.println(" mCreateTime=" + TimeUtils.logTimeOfDay(mCreateTime));
        pw.println(" mLastFailTime=" + TimeUtils.logTimeOfDay(mLastFailTime));
        pw.println(" mLastFailCause=" + mLastFailCause);
        pw.flush();
        pw.println(" mUserData=" + mUserData);
        pw.println(" mInstanceNumber=" + mInstanceNumber);
        pw.println(" mAc=" + mAc);
        pw.println(" mDcRetryAlarmController=" + mDcRetryAlarmController);
        pw.flush();
    }

    boolean isApnTypeIMSorEmergency(String apnType) {
        if (TextUtils.equals(apnType, PhoneConstants.APN_TYPE_IMS) ||
           TextUtils.equals(apnType, PhoneConstants.APN_TYPE_EMERGENCY)) {
            return true;
        }
        return false;
    }

    private boolean checkIfCreateGsmDCTExt(PhoneBase phone) {
        return mDcFailCauseManager.createGsmDCTExt(phone);
    }

    /**
     *  M: IPv6 RA updateObserver that watches for {@link INetworkManagementService} alerts.
     */
    private int getEventByAddress(boolean bUpdated, LinkAddress linkAddr) {
            int event = -1;
            InetAddress addr = linkAddr.getAddress();
            if (bUpdated == false) {
                if (addr instanceof Inet6Address) {
                    event = EVENT_IPV6_ADDRESS_REMOVED;
                } else if (addr instanceof Inet4Address) {
                    event = EVENT_IPV4_ADDRESS_REMOVED;
                } else {
                    loge("unknown address type, linkAddr: " + linkAddr);
                }
            }

            return event;
    }

    private boolean checkIgnoredCause(DcFailCause cause) {
        boolean ignored = false;
        try {
            ignored = mGsmDCTExt.isIgnoredCause(cause);
        } catch (Exception e) {
            loge("checkIgnoredCause fail!");
            e.printStackTrace();
        }
        return ignored;
    }

    private void sendMessageForSM(int event, String iface, LinkAddress address) {
        if (event < 0) {
            loge("sendMessageForSM: Skip notify!!!");
            return;
        }
        AddressInfo addrInfo = new AddressInfo(iface, address);
        log("sendMessageForSM: " + cmdToString(event) + ", addressInfo: " + addrInfo);
        sendMessage(obtainMessage(event, addrInfo));
    }

    private INetworkManagementEventObserver mAlertObserver = new BaseNetworkObserver() {
        @Override
        public void addressRemoved(String iface, LinkAddress address) {
            int event = getEventByAddress(false, address);

            sendMessageForSM(event, iface, address);
        }
    };

    private void onAddressRemoved() {
        if ((RILConstants.SETUP_DATA_PROTOCOL_IPV6.equals(mApnSetting.protocol)
            || RILConstants.SETUP_DATA_PROTOCOL_IPV4V6.equals(mApnSetting.protocol)) &&
            !isIpv4Connected()) {
            log("onAddressRemoved: EVENT_DISCONNECT_ALL");
            log("IPv6 RA failed and didn't connect with IPv4");
            if (mApnContexts != null) {
                log("onAddressRemoved: mApnContexts size: " + mApnContexts.size());
                for (ApnContext apnContext : mApnContexts) {
                    String apnType = apnContext.getApnType();
                    if (apnContext.getState() == DctConstants.State.CONNECTED) {
                        if (isApnTypeIMSorEmergency(apnType)) {
                            // TODO: skip ims and emergency pdn if RA address removed
                            // TODO: might need to check if OP12 specs satisfied or not
                            log("apnType: " + apnType + ", skip disconnect while onAddressRemoved!!");
                        } else {
                            Message msg =
                                mDct.obtainMessage(DctConstants.EVENT_DISCONNECT_DONE, apnContext);
                            DisconnectParams dp =
                                new DisconnectParams(apnContext, Phone.REASON_RA_FAILED, msg);
                            DataConnection.this.sendMessage(DataConnection.this.
                                    obtainMessage(EVENT_DISCONNECT_ALL, dp));
                            break;
                        }
                    }
                }
            }
        } else {
            log("onAddressRemoved: no need to remove");
        }
    }

    private boolean isTestSim() {
        try {
            ITelephonyEx iTelEx = ITelephonyEx.Stub.asInterface(
                    ServiceManager.getService(Context.TELEPHONY_SERVICE_EX));

            if (null == iTelEx) {
                loge("iTelEx is null");
                return false;
            }

            int slotId = SubscriptionManager.getSlotId(mPhone.getSubId());
            if (SubscriptionManager.isValidSlotId(slotId) && iTelEx.isTestIccCard(slotId)) {
                loge("isTestSim");
                return true;
            }
        } catch (Exception ex) {
            loge("dataConnection test SIM detection fail");
            ex.printStackTrace();
        }

        return false;
    }

    // M: [LTE][Low Power][UL traffic shaping] Start
    void checkIfDefaultApnReferenceCountChanged() {
        for (ApnContext apnContext : mApnContexts) {
            if(PhoneConstants.APN_TYPE_DEFAULT.equals(apnContext.getApnType())
                    && DctConstants.State.CONNECTED.equals(apnContext.getState())) {
                if (DBG) log("refCount = " + mApnContexts.size());
                notifyDefaultApnReferenceCountChanged(mApnContexts.size(),
                        DctConstants.EVENT_DEFAULT_APN_REFERENCE_COUNT_CHANGED);
            }
        }
    }
    // M: [LTE][Low Power][UL traffic shaping] End

    private boolean isDefaultDataSubPhone(Phone phone) {
        final int defaultDataPhoneId = mSubController.getPhoneId(
                mSubController.getDefaultDataSubId());
        int curPhoneId = phone.getPhoneId();

        if (defaultDataPhoneId != curPhoneId) {
            log("Current phone is not default phone: curPhoneId = "
                    + curPhoneId + ", defaultDataPhoneId = "
                    + defaultDataPhoneId);
            return false;
        }
        return true;
    }

    private void regiseterNetworkAlertObserver() {
        if (mNetworkManager != null) {
            log("regiseterNetworkAlertObserver X");
            try {
                mNetworkManager.registerObserver(mAlertObserver);
                log("regiseterNetworkAlertObserver E");
            } catch (Exception e) {
                // ignored; service lives in system_server
                loge("regiseterNetworkAlertObserver failed E");
            }
        }
    }
    private void unRegisterNetworkAlertObserver() {
        if (mNetworkManager != null) {
            log("unRegiseterNetworkAlertObserver X");
            try {
                mNetworkManager.unregisterObserver(mAlertObserver);
                log("unRegiseterNetworkAlertObserver E");
            } catch (Exception e) {
                // ignored; service lives in system_server
                loge("unRegisterNetworkAlertObserver failed E");
            }
            mInterfaceName = null;
        }
    }

    // M: isVsimActive.
    private boolean isVsimActive() {
        int phoneId = mPhone.getPhoneId();
        return ((DcTracker)mDct).isVsimActive(phoneId);
    }

}