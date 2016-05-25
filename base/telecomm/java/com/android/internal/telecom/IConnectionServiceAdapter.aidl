/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.telecom;

import android.os.Bundle;
import android.app.PendingIntent;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.ConnectionRequest;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;
import android.telecom.ParcelableConnection;
import android.telecom.ParcelableConference;
import android.telecom.StatusHints;

import com.android.internal.telecom.IVideoProvider;
import com.android.internal.telecom.RemoteServiceCallback;

/**
 * Internal remote callback interface for connection services.
 *
 * @see android.telecom.ConnectionServiceAdapter
 *
 * {@hide}
 */
oneway interface IConnectionServiceAdapter {
    void handleCreateConnectionComplete(
            String callId,
            in ConnectionRequest request,
            in ParcelableConnection connection);

    void setActive(String callId);

    void setRinging(String callId);

    void setDialing(String callId);

    void setDisconnected(String callId, in DisconnectCause disconnectCause);

    void setOnHold(String callId);

    void setRingbackRequested(String callId, boolean ringing);

    void setConnectionCapabilities(String callId, int connectionCapabilities);

    void setIsConferenced(String callId, String conferenceCallId);

    void setConferenceMergeFailed(String callId);

    void addConferenceCall(String callId, in ParcelableConference conference);

    void removeCall(String callId);

    void onPostDialWait(String callId, String remaining);

    void onPostDialChar(String callId, char nextChar);

    void queryRemoteConnectionServices(RemoteServiceCallback callback);

    void setVideoProvider(String callId, IVideoProvider videoProvider);

    void setVideoState(String callId, int videoState);

    void setIsVoipAudioMode(String callId, boolean isVoip);

    void setStatusHints(String callId, in StatusHints statusHints);

    void setAddress(String callId, in Uri address, int presentation);

    void setCallerDisplayName(String callId, String callerDisplayName, int presentation);

    void setConferenceableConnections(String callId, in List<String> conferenceableCallIds);

    void addExistingConnection(String callId, in ParcelableConnection connection);

    void setExtras(String callId, in Bundle extras);

    /* M: call control part start */
    void notifyConnectionLost(String callId);

    void notifyActionFailed(String callId, int action);
    void notifySSNotificationToast(String callId, int notiType, int type, int code, String number, int index);
    void notifyNumberUpdate(String callId, String number);
    void notifyIncomingInfoUpdate(String callId, int type, String alphaid, int cli_validity);
    void notifyCdmaCallAccepted(String callId);
    void notifyAccountChanged(in String connectionId, in PhoneAccountHandle handle);
    /* M: call control part end */

    /// M: For 3G VT only @{
    void notifyVtStatusInfo(String callId, int status);
    /// @}

    /// M: For volte @{
    void updateExtras(String callId, in Bundle bundle);
    void handleCreateConferenceComplete(
            String conferenceId,
            in ConnectionRequest request,
            in ParcelableConference conference);
    /// @}
}