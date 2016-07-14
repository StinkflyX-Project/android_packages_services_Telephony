/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.phone.vvm.omtp.protocol;

import android.annotation.WorkerThread;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.telecom.PhoneAccountHandle;
import android.telephony.TelephonyManager;
import android.text.Html;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.util.ArrayMap;

import com.android.phone.Assert;
import com.android.phone.vvm.omtp.ActivationTask;
import com.android.phone.vvm.omtp.OmtpConstants;
import com.android.phone.vvm.omtp.OmtpEvents;
import com.android.phone.vvm.omtp.OmtpVvmCarrierConfigHelper;
import com.android.phone.vvm.omtp.VvmLog;
import com.android.phone.vvm.omtp.sms.StatusMessage;
import com.android.phone.vvm.omtp.sms.StatusSmsFetcher;
import com.android.phone.vvm.omtp.sync.VvmNetworkRequest;
import com.android.phone.vvm.omtp.sync.VvmNetworkRequest.NetworkWrapper;
import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.HurlStack;
import com.android.volley.toolbox.RequestFuture;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Class to subscribe to basic VVM3 visual voicemail, for example, Verizon. Subscription is required
 * when the user is unprovisioned. This could happen when the user is on a legacy service, or
 * switched over from devices that used other type of visual voicemail.
 *
 * The STATUS SMS will come with a URL to the voicemail management gateway. From it we can find the
 * self provisioning gateway URL that we can modify voicemail services.
 *
 * A request to the self provisioning gateway to activate basic visual voicemail will return us with
 * a web page. If the user hasn't subscribe to it yet it will contain a link to confirm the
 * subscription. This link should be clicked through cellular network, and have cookies enabled.
 *
 * After the process is completed, the carrier should send us another STATUS SMS with a new or ready
 * user.
 */
public class Vvm3Subscriber {

    private static final String TAG = "Vvm3Subscriber";

    private static final String OPERATION_GET_SPG_URL = "retrieveSPGURL";
    private static final String SPG_URL_TAG = "spgurl";
    private static final String TRANSACTION_ID_TAG = "transactionid";
    //language=XML
    private static final String VMG_XML_REQUEST_FORMAT = ""
            + "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<VMGVVMRequest>"
            + "  <MessageHeader>"
            + "    <transactionid>%1$s</transactionid>"
            + "  </MessageHeader>"
            + "  <MessageBody>"
            + "    <mdn>%2$s</mdn>"
            + "    <operation>%3$s</operation>"
            + "    <source>Device</source>"
            + "    <devicemodel>%4$s</devicemodel>"
            + "  </MessageBody>"
            + "</VMGVVMRequest>";

    private static final String VMG_URL_KEY = "vmg_url";

    // Self provisioning POST key/values. VVM3 API 2.1.0 12.3
    private static final String SPG_VZW_MDN_PARAM = "VZW_MDN";
    private static final String SPG_VZW_SERVICE_PARAM = "VZW_SERVICE";
    private static final String SPG_VZW_SERVICE_BASIC = "BVVM";
    private static final String SPG_DEVICE_MODEL_PARAM = "DEVICE_MODEL";
    // Value for all android device
    private static final String SPG_DEVICE_MODEL_ANDROID = "DROID_4G";
    private static final String SPG_APP_TOKEN_PARAM = "APP_TOKEN";
    private static final String SPG_APP_TOKEN = "q8e3t5u2o1";
    private static final String SPG_LANGUAGE_PARAM = "SPG_LANGUAGE_PARAM";
    private static final String SPG_LANGUAGE_EN = "ENGLISH";

    private static final String BASIC_SUBSCRIBE_LINK_TEXT = "Subscribe to Basic Visual Voice Mail";

    private static final int REQUEST_TIMEOUT_SECONDS = 30;

    private final ActivationTask mTask;
    private final PhoneAccountHandle mHandle;
    private final OmtpVvmCarrierConfigHelper mHelper;
    private final Bundle mData;

    private final String mNumber;

    private RequestQueue mRequestQueue;

    private static class ProvisioningException extends Exception {

        public ProvisioningException(String message) {
            super(message);
        }
    }

    static {
        // Set the default cookie handler to retain session data for the self provisioning gateway.
        // Note; this is not ideal as it is application-wide, and can easily get clobbered.
        // But it seems to be the preferred way to manage cookie for HttpURLConnection, and manually
        // managing cookies will greatly increase complexity.
        CookieManager cookieManager = new CookieManager();
        CookieHandler.setDefault(cookieManager);
    }

    @WorkerThread
    public Vvm3Subscriber(ActivationTask task, PhoneAccountHandle handle,
            OmtpVvmCarrierConfigHelper helper, Bundle data) {
        Assert.isNotMainThread();
        mTask = task;
        mHandle = handle;
        mHelper = helper;
        mData = data;

        // Assuming getLine1Number() will work with VVM3. For unprovisioned users the IMAP username
        // is not included in the status SMS, thus no other way to get the current phone number.
        mNumber = mHelper.getContext().getSystemService(TelephonyManager.class)
                .getLine1Number(mHelper.getSubId());
    }

    @WorkerThread
    public void subscribe() {
        Assert.isNotMainThread();
        // Cellular data is required to subscribe.
        // processSubscription() is called after network is available.
        VvmLog.i(TAG, "Subscribing");

        try (NetworkWrapper wrapper = VvmNetworkRequest.getNetwork(mHelper, mHandle)) {
            Network network = wrapper.get();
            VvmLog.d(TAG, "provisioning: network available");
            mRequestQueue = Volley
                    .newRequestQueue(mHelper.getContext(), new NetworkSpecifiedHurlStack(network));
            processSubscription();
        }
    }

    private void processSubscription() {
        try {
            String gatewayUrl = getSelfProvisioningGateway();
            String selfProvisionResponse = getSelfProvisionResponse(gatewayUrl);
            String subscribeLink = findSubscribeLink(selfProvisionResponse);
            clickSubscribeLink(subscribeLink);
        } catch (ProvisioningException e) {
            VvmLog.e(TAG, e.toString());
            mTask.fail();
        }
    }

    /**
     * Get the URL to perform self-provisioning from the voicemail management gateway.
     */
    private String getSelfProvisioningGateway() throws ProvisioningException {
        VvmLog.i(TAG, "retrieving SPG URL");
        String response = vvm3XmlRequest(OPERATION_GET_SPG_URL);
        return extractText(response, SPG_URL_TAG);
    }

    /**
     * Sent a request to the self-provisioning gateway, which will return us with a webpage. The
     * page might contain a "Subscribe to Basic Visual Voice Mail" link to complete the
     * subscription. The cookie from this response and cellular data is required to click the link.
     */
    private String getSelfProvisionResponse(String url) throws ProvisioningException {
        VvmLog.i(TAG, "Retrieving self provisioning response");

        RequestFuture<String> future = RequestFuture.newFuture();

        StringRequest stringRequest = new StringRequest(Request.Method.POST, url, future, future) {
            @Override
            protected Map<String, String> getParams() {
                Map<String, String> params = new ArrayMap<>();
                params.put(SPG_VZW_MDN_PARAM, mNumber);
                params.put(SPG_VZW_SERVICE_PARAM, SPG_VZW_SERVICE_BASIC);
                params.put(SPG_DEVICE_MODEL_PARAM, SPG_DEVICE_MODEL_ANDROID);
                params.put(SPG_APP_TOKEN_PARAM, SPG_APP_TOKEN);
                // Language to display the subscription page. The page is never shown to the user
                // so just use English.
                params.put(SPG_LANGUAGE_PARAM, SPG_LANGUAGE_EN);
                return params;
            }
        };

        mRequestQueue.add(stringRequest);
        try {
            return future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            mHelper.handleEvent(OmtpEvents.VVM3_SPG_CONNECTION_FAILED);
            throw new ProvisioningException(e.toString());
        }
    }

    private void clickSubscribeLink(String subscribeLink) throws ProvisioningException {
        VvmLog.i(TAG, "Clicking subscribe link");
        RequestFuture<String> future = RequestFuture.newFuture();

        StringRequest stringRequest = new StringRequest(Request.Method.POST,
                subscribeLink, future, future);
        mRequestQueue.add(stringRequest);
        try (StatusSmsFetcher fetcher = new StatusSmsFetcher(mHelper.getContext(),
                mHelper.getSubId())) {
            try {
                // A new STATUS SMS will be sent after this request.
                future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                mHelper.handleEvent(OmtpEvents.VVM3_SPG_CONNECTION_FAILED);
                throw new ProvisioningException(e.toString());
            }
            Bundle data = fetcher.get();
            StatusMessage message = new StatusMessage(data);
            switch (message.getProvisioningStatus()) {
                case OmtpConstants.SUBSCRIBER_READY:
                    ActivationTask.updateSource(mHelper.getContext(), mHandle,
                            mHelper.getSubId(), message);
                    break;
                case OmtpConstants.SUBSCRIBER_NEW:
                    mHelper.getProtocol().startProvisioning(mTask, mHandle, mHelper, message, data);
                    break;
                default:
                    mHelper.handleEvent(OmtpEvents.VVM3_SPG_CONNECTION_FAILED);
                    throw new ProvisioningException("status is not ready or new after subscribed");
            }
        } catch (TimeoutException e) {
            mHelper.handleEvent(OmtpEvents.CONFIG_STATUS_SMS_TIME_OUT);
            throw new ProvisioningException("Timed out waiting for STATUS SMS after subscribed");
        } catch (InterruptedException | ExecutionException | IOException e) {
            mHelper.handleEvent(OmtpEvents.VVM3_SPG_CONNECTION_FAILED);
            throw new ProvisioningException(e.toString());
        }
    }

    private String vvm3XmlRequest(String operation) throws ProvisioningException {
        VvmLog.d(TAG, "Sending vvm3XmlRequest for " + operation);
        String voicemailManagementGateway = mData.getString(VMG_URL_KEY);
        if (voicemailManagementGateway == null) {
            VvmLog.e(TAG, "voicemailManagementGateway url unknown");
            return null;
        }
        String transactionId = createTransactionId();
        String body = String.format(Locale.US, VMG_XML_REQUEST_FORMAT,
                transactionId, mNumber, operation, Build.MODEL);

        RequestFuture<String> future = RequestFuture.newFuture();
        StringRequest stringRequest = new StringRequest(Request.Method.POST,
                voicemailManagementGateway, future, future) {
            @Override
            public byte[] getBody() throws AuthFailureError {
                return body.getBytes();
            }
        };
        mRequestQueue.add(stringRequest);

        try {
            String response = future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!transactionId.equals(extractText(response, TRANSACTION_ID_TAG))) {
                throw new ProvisioningException("transactionId mismatch");
            }
            return response;
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            mHelper.handleEvent(OmtpEvents.VVM3_VMG_CONNECTION_FAILED);
            throw new ProvisioningException(e.toString());
        }
    }

    private String findSubscribeLink(String response) throws ProvisioningException {
        Spanned doc = Html.fromHtml(response, Html.FROM_HTML_MODE_LEGACY);
        URLSpan[] spans = doc.getSpans(0, doc.length(), URLSpan.class);
        StringBuilder fulltext = new StringBuilder();
        for (URLSpan span : spans) {
            String text = doc.subSequence(doc.getSpanStart(span), doc.getSpanEnd(span)).toString();
            if (BASIC_SUBSCRIBE_LINK_TEXT.equals(text)) {
                return span.getURL();
            }
            fulltext.append(text);
        }
        throw new ProvisioningException("Subscribe link not found: " + fulltext);
    }

    private String createTransactionId() {
        return String.valueOf(Math.abs(new Random().nextLong()));
    }

    private String extractText(String xml, String tag) throws ProvisioningException {
        Pattern pattern = Pattern.compile("<" + tag + ">(.*)<\\/" + tag + ">");
        Matcher matcher = pattern.matcher(xml);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new ProvisioningException("Tag " + tag + " not found in xml response");
    }

    private static class NetworkSpecifiedHurlStack extends HurlStack {

        private final Network mNetwork;

        public NetworkSpecifiedHurlStack(Network network) {
            mNetwork = network;
        }

        @Override
        protected HttpURLConnection createConnection(URL url) throws IOException {
            return (HttpURLConnection) mNetwork.openConnection(url);
        }

    }
}
