package com.github.aloomaio.androidsdk.aloomametrics;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.Log;

import com.github.aloomaio.androidsdk.util.HttpService;
import com.github.aloomaio.androidsdk.util.RemoteService;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesUtil;

import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Manage communication of events with the internal database and the Mixpanel servers.
 *
 * <p>This class straddles the thread boundary between user threads and
 * a logical Mixpanel thread.
 */
/* package */ class AnalyticsMessages {
    private static final String LOGTAG = "AnalyticsMessages";
    private static final Map<String, Map<Context, AnalyticsMessages>> sInstanceMap = new HashMap<>();

    // Messages for our thread
    private static int ENQUEUE_PEOPLE = 0; // submit events and people data
    private static int ENQUEUE_EVENTS = 1; // push given JSON message to people DB
    private static int FLUSH_QUEUE = 2; // push given JSON message to events DB
    private static int KILL_WORKER = 5; // Hard-kill the worker thread, discarding all events on the event queue. This is for testing, or disasters.
    private static int INSTALL_DECIDE_CHECK = 12; // Run this DecideCheck at intervals until it isDestroyed()
    private static int REGISTER_FOR_GCM = 13; // Register for GCM using Google Play Services

    // Used across thread boundaries
    private final MessageHandlerThread mWorker;
    private final Context mContext;
    private final AConfig mConfig;
    private final String mAloomaHost;
    private final Map<String, String> mHeaders;
    private final RemoteService.ContentType mContentType;
    private String mSchema;

    private final String DEFAULT_ALOOMA_HOST = "inputs.alooma.com";

    /**
     * Do not call directly. You should call AnalyticsMessages.getInstance()
     */
    /* package */ AnalyticsMessages(final Context context) {
        this(context, null, false, null, RemoteService.ContentType.URL_FORM_ENCODED);
    }

    /**
     * Do not call directly. You should call AnalyticsMessages.getInstance()
     */
    /* package */ AnalyticsMessages(final Context context, String aloomaHost, boolean forceSSL,
                                    Map<String, String> headers, RemoteService.ContentType contentType) {
        mContext = context;

        mConfig = getConfig(context);
        mWorker = new MessageHandlerThread("com.alooma.android.AnalyticsWorker");

        mAloomaHost = (null == aloomaHost) ? DEFAULT_ALOOMA_HOST : aloomaHost;
        mSchema = forceSSL ? "https" : "http";
        mHeaders = headers;
        mContentType = contentType;

        // Start worker thread
        mWorker.start();
    }

    public static AnalyticsMessages getInstance(final Context messageContext) {
        return getInstance(messageContext, null, true,
                null, RemoteService.ContentType.URL_FORM_ENCODED);
    }

    /**
     * Returns a singleton AnalyticsMessages instance with configurable forceSSL attribute.
     * Different aloomaHost values will return new instances.
     */
    public static AnalyticsMessages getInstance(final Context messageContext,
                                                String aloomaHost,
                                                boolean forceSSL,
                                                Map<String, String> headers,
                                                RemoteService.ContentType contentType) {
        synchronized (sInstanceMap) {
            final Context appContext = messageContext.getApplicationContext();

            Map <Context, AnalyticsMessages> instances = sInstanceMap.get(aloomaHost);
            if (instances == null) {
                instances = new HashMap<>();
                sInstanceMap.put(aloomaHost, instances);
            }

            AnalyticsMessages instance = instances.get(appContext);
            if (instance == null) {
                instance = new AnalyticsMessages(appContext, aloomaHost, forceSSL, headers, contentType);
                instances.put(appContext, instance);
            }

            return instance;
        }
    }

    // All methods must be Thread safe.
    public void publishMessage(final AnalyticsEvent event) {
        final Message m = Message.obtain();
        m.what = ENQUEUE_EVENTS;
        m.obj = event;
        mWorker.runMessage(m);
    }

    public void publishMessage(final JSONObject peopleJson) {
        final Message m = Message.obtain();
        m.what = ENQUEUE_PEOPLE;
        m.obj = peopleJson;
        mWorker.runMessage(m);
    }

    public void flushQueue() {
        final Message m = Message.obtain();
        m.what = FLUSH_QUEUE;
        mWorker.runMessage(m);
    }

    public void installDecideCheck(final DecideMessages check) {
        final Message m = Message.obtain();
        m.what = INSTALL_DECIDE_CHECK;
        m.obj = check;
        mWorker.runMessage(m);
    }

    public void registerForGCM(final String senderID) {
        final Message m = Message.obtain();
        m.what = REGISTER_FOR_GCM;
        m.obj = senderID;
        mWorker.runMessage(m);
    }

    public void hardKill() {
        final Message m = Message.obtain();
        m.what = KILL_WORKER;
        mWorker.runMessage(m);
    }

    /////////////////////////////////////////////////////////
    // For testing, to allow for Mocking.

    /* package */ boolean isDead() {
        return mWorker.isDead();
    }

    protected AConfig getConfig(Context context) {
        return AConfig.getInstance(context);
    }

    protected HttpService getPoster() {
        return new HttpService();
    }

    // Sends a message if and only if we are running with alooma Message log enabled.
    // Will be called from the alooma thread.
    private void logAboutMessageToAlooma(String message) {
        if (AConfig.DEBUG) {
            Log.v(LOGTAG, message + " (Thread " + Thread.currentThread().getId() + ")");
        }
    }

    private void logAboutMessageToAlooma(String message, Throwable e) {
        if (AConfig.DEBUG) {
            Log.v(LOGTAG, message + " (Thread " + Thread.currentThread().getId() + ")", e);
        }
    }

    // MessageHandlerThread will manage the (at most single) IO thread associated with
    // this AnalyticsMessages instance. NOTE: The returned worker will run FOREVER, unless you send a hard kill
    // (which you really shouldn't)
    private class MessageHandlerThread extends HandlerThread{
        private final Object mHandlerLock = new Object();
        private Handler mHandler;
        private long mFlushCount = 0;
        private long mAveFlushFrequency = 0;
        private long mLastFlushTime = -1;
        private SystemInformation mSystemInformation;

        public MessageHandlerThread(String name) {
            super(name, Thread.MIN_PRIORITY);
        }

        @Override
        protected void onLooperPrepared() {
            String databaseName = mAloomaHost.equals(DEFAULT_ALOOMA_HOST) ? "alooma.db" : "rover.db";
            mHandler = new AnalyticsMessageHandler(getLooper(), new ADbAdapter(mContext, databaseName));
        }

        public boolean isDead() {
            synchronized(mHandlerLock) {
                return mHandler == null;
            }
        }

        public void runMessage(Message msg) {
            synchronized(mHandlerLock) {
                if (mHandler == null) {
                    // We died under suspicious circumstances. Don't try to send any more events.
                    logAboutMessageToAlooma("Dead alooma worker dropping a message: " + msg.what);
                } else {
                    mHandler.sendMessage(msg);
                }
            }
        }

        private void updateFlushFrequency() {
            final long now = System.currentTimeMillis();
            final long newFlushCount = mFlushCount + 1;

            if (mLastFlushTime > 0) {
                final long flushInterval = now - mLastFlushTime;
                final long totalFlushTime = flushInterval + (mAveFlushFrequency * mFlushCount);
                mAveFlushFrequency = totalFlushTime / newFlushCount;

                final long seconds = mAveFlushFrequency / 1000;
                logAboutMessageToAlooma("Average send frequency approximately " + seconds + " seconds.");
            }

            mLastFlushTime = now;
            mFlushCount = newFlushCount;
        }

        private class AnalyticsMessageHandler extends Handler {
            private ADbAdapter mDbAdapter;
            private final DecideChecker mDecideChecker;
            private final long mFlushInterval;
            private final boolean mDisableFallback;

            public AnalyticsMessageHandler(Looper looper, ADbAdapter dbAdapter) {
                super(looper);
                mDbAdapter = dbAdapter;
                mDecideChecker = new DecideChecker(mContext, mConfig);
                mDisableFallback = mConfig.getDisableFallback();
                mFlushInterval = mConfig.getFlushInterval();
                mSystemInformation = new SystemInformation(mContext);
            }

            @Override
            public void handleMessage(Message msg) {
                try {
                    int queueDepth = -1;

                    if (msg.what == ENQUEUE_PEOPLE) {
                        final JSONObject message = (JSONObject) msg.obj;

                        logAboutMessageToAlooma("Queuing people record for sending later");
                        logAboutMessageToAlooma("    " + message.toString());

                        queueDepth = mDbAdapter.addJSON(message, ADbAdapter.Table.PEOPLE);
                    }
                    else if (msg.what == ENQUEUE_EVENTS) {
                        final AnalyticsEvent eventDescription = (AnalyticsEvent) msg.obj;
                        try {
                            final JSONObject message = prepareEventObject(eventDescription, mContentType);
                            logAboutMessageToAlooma("Queuing event for sending later");
                            logAboutMessageToAlooma("    " + message.toString());
                            queueDepth = mDbAdapter.addJSON(message, ADbAdapter.Table.EVENTS);
                        } catch (final JSONException e) {
                            Log.e(LOGTAG, "Exception tracking event " + eventDescription.getEventName(), e);
                        }
                    }
                    else if (msg.what == FLUSH_QUEUE) {
                        logAboutMessageToAlooma("Flushing queue due to scheduled or forced flush");
                        updateFlushFrequency();
                        sendAllData(mDbAdapter);
                        mDecideChecker.runDecideChecks(getPoster());

                        //TODO: Not sure the best place for this operation. Maybe create a new Message?
                        mDbAdapter.cleanupEvents(System.currentTimeMillis() - mConfig.getDataExpiration(), ADbAdapter.Table.EVENTS);
                        mDbAdapter.cleanupEvents(System.currentTimeMillis() - mConfig.getDataExpiration(), ADbAdapter.Table.PEOPLE);
                    }
                    else if (msg.what == INSTALL_DECIDE_CHECK) {
                        logAboutMessageToAlooma("Installing a check for surveys and in app notifications");
                        final DecideMessages check = (DecideMessages) msg.obj;
                        mDecideChecker.addDecideCheck(check);
                        mDecideChecker.runDecideChecks(getPoster());
                    }
                    else if (msg.what == KILL_WORKER) {
                        Log.w(LOGTAG, "Worker received a hard kill. Dumping all events and force-killing. Thread id " + Thread.currentThread().getId());
                        synchronized(mHandlerLock) {
                            mDbAdapter.deleteDB();
                            mHandler = null;
                            Looper.myLooper().quit();
                        }
                    } else {
                        Log.e(LOGTAG, "Unexpected message received by Alooma worker: " + msg);
                    }

                    ///////////////////////////

                    if (queueDepth >= mConfig.getBulkUploadLimit()) {
                        logAboutMessageToAlooma("Flushing queue due to bulk upload limit");
                        updateFlushFrequency();
                        sendAllData(mDbAdapter);
                        mDecideChecker.runDecideChecks(getPoster());
                    } else if (queueDepth > 0 && !hasMessages(FLUSH_QUEUE)) {
                        // The !hasMessages(FLUSH_QUEUE) check is a courtesy for the common case
                        // of delayed flushes already enqueued from inside of this thread.
                        // Callers outside of this thread can still send
                        // a flush right here, so we may end up with two flushes
                        // in our queue, but we're OK with that.

                        logAboutMessageToAlooma("Queue depth " + queueDepth + " - Adding flush in " + mFlushInterval);
                        if (mFlushInterval >= 0) {
                            sendEmptyMessageDelayed(FLUSH_QUEUE, mFlushInterval);
                        }
                    }
                } catch (final RuntimeException e) {
                    Log.e(LOGTAG, "Worker threw an unhandled exception", e);
                    synchronized (mHandlerLock) {
                        mHandler = null;
                        try {
                            Looper.myLooper().quit();
                            Log.e(LOGTAG, "Alooma will not process any more analytics messages", e);
                        } catch (final Exception tooLate) {
                            Log.e(LOGTAG, "Could not halt looper", tooLate);
                        }
                    }
                }
            }

            private JSONObject getDefaultEventProperties() throws JSONException {
                final JSONObject ret = new JSONObject();

                ret.put("alooma_sdk", "android");
                ret.put("$lib_version", AConfig.VERSION);

                // For querying together with data from other libraries
                ret.put("$os", "Android");
                ret.put("$os_version", Build.VERSION.RELEASE == null ? "UNKNOWN" : Build.VERSION.RELEASE);

                ret.put("$manufacturer", Build.MANUFACTURER == null ? "UNKNOWN" : Build.MANUFACTURER);
                ret.put("$brand", Build.BRAND == null ? "UNKNOWN" : Build.BRAND);
                ret.put("$model", Build.MODEL == null ? "UNKNOWN" : Build.MODEL);

                try {
                    try {
                        final int servicesAvailable = GooglePlayServicesUtil.isGooglePlayServicesAvailable(mContext);
                        switch (servicesAvailable) {
                            case ConnectionResult.SUCCESS:
                                ret.put("$google_play_services", "available");
                                break;
                            case ConnectionResult.SERVICE_MISSING:
                                ret.put("$google_play_services", "missing");
                                break;
                            case ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED:
                                ret.put("$google_play_services", "out of date");
                                break;
                            case ConnectionResult.SERVICE_DISABLED:
                                ret.put("$google_play_services", "disabled");
                                break;
                            case ConnectionResult.SERVICE_INVALID:
                                ret.put("$google_play_services", "invalid");
                                break;
                        }
                    } catch (RuntimeException e) {
                        // Turns out even checking for the service will cause explosions
                        // unless we've set up meta-data
                        ret.put("$google_play_services", "not configured");
                    }

                } catch (NoClassDefFoundError e) {
                    ret.put("$google_play_services", "not included");
                }

                final DisplayMetrics displayMetrics = mSystemInformation.getDisplayMetrics();
                ret.put("$screen_dpi", displayMetrics.densityDpi);
                ret.put("$screen_height", displayMetrics.heightPixels);
                ret.put("$screen_width", displayMetrics.widthPixels);

                final String applicationVersionName = mSystemInformation.getAppVersionName();
                if (null != applicationVersionName)
                    ret.put("$app_version", applicationVersionName);

                final Boolean hasNFC = mSystemInformation.hasNFC();
                if (null != hasNFC)
                    ret.put("$has_nfc", hasNFC.booleanValue());

                final Boolean hasTelephony = mSystemInformation.hasTelephony();
                if (null != hasTelephony)
                    ret.put("$has_telephone", hasTelephony.booleanValue());

                final String carrier = mSystemInformation.getCurrentNetworkOperator();
                if (null != carrier)
                    ret.put("$carrier", carrier);

                final Boolean isWifi = mSystemInformation.isWifiConnected();
                if (null != isWifi)
                    ret.put("$wifi", isWifi.booleanValue());

                final Boolean isBluetoothEnabled = mSystemInformation.isBluetoothEnabled();
                if (isBluetoothEnabled != null)
                    ret.put("$bluetooth_enabled", isBluetoothEnabled);

                final String bluetoothVersion = mSystemInformation.getBluetoothVersion();
                if (bluetoothVersion != null)
                    ret.put("$bluetooth_version", bluetoothVersion);

                return ret;
            }

            private JSONObject prepareEventObject(AnalyticsEvent eventDescription, RemoteService.ContentType contentType) throws JSONException {
                final JSONObject eventObj = new JSONObject();
                final JSONObject eventProperties = eventDescription.getProperties();
                final JSONObject defaultEventProperties = getDefaultEventProperties();

                JSONObject props;
                try {
                    props = eventObj.getJSONObject("properties");
                } catch (JSONException ex) {
                    props = new JSONObject();
                }
                props.put("token", eventDescription.getToken());

                if (eventProperties != null) {
                    for (final Iterator<?> iter = eventProperties.keys(); iter.hasNext();) {
                        final String key = (String) iter.next();
                        if (contentType == RemoteService.ContentType.JSON){
                            props.put(key, eventProperties.get(key));
                        } else {
                            defaultEventProperties.put(key, eventProperties.get(key));
                        }
                    }
                }
                for (final Iterator<?> iter = defaultEventProperties.keys(); iter.hasNext();) {
                    final String key = (String) iter.next();
                    if (contentType == RemoteService.ContentType.JSON){
                        props.put(key, defaultEventProperties.get(key));
                    } else {
                        eventObj.put(key, defaultEventProperties.get(key));
                    }
                }

                eventObj.put("event", eventDescription.getEventName());
                eventObj.put("properties", props);
                return eventObj;
            }

            private void sendAllData(ADbAdapter dbAdapter) {
                final HttpService poster = getPoster();
                if (!poster.isOnline(mContext)) {
                    logAboutMessageToAlooma("Not flushing data to alooma because the device is not connected to the internet.");
                    return;
                }

                logAboutMessageToAlooma("Sending records to alooma");
                sendData(dbAdapter, ADbAdapter.Table.EVENTS,
                        mSchema + "://" + mAloomaHost + "/track?ip=1", mHeaders, mContentType);
            }

            private void sendData(ADbAdapter dbAdapter, ADbAdapter.Table table, String url,
                                  Map<String, String> headers, RemoteService.ContentType contentType) {
                final HttpService poster = getPoster();
                final String[] eventsData = dbAdapter.generateDataString(table);

                if (eventsData != null) {
                    final String lastId = eventsData[0];
                    final String rawMessage = eventsData[1];

                    final List<NameValuePair> params = new ArrayList<NameValuePair>(1);
                    if (AConfig.DEBUG) {
                        params.add(new BasicNameValuePair("verbose", "1"));
                    }

                    boolean deleteEvents = true;
                    byte[] response;
                    try {
                        response = poster.performRequest(url, params, headers, contentType, rawMessage);
                        deleteEvents = true; // Delete events on any successful post, regardless of 1 or 0 response
                        if (null == response) {
                            logAboutMessageToAlooma("Response was null, unexpected failure posting to " + url + ".");
                        } else {
                            String parsedResponse;
                            try {
                                parsedResponse = new String(response, "UTF-8");
                            } catch (UnsupportedEncodingException e) {
                                throw new RuntimeException("UTF not supported on this platform?", e);
                            }

                            logAboutMessageToAlooma("Successfully posted to " + url + ": \n" + rawMessage);
                            logAboutMessageToAlooma("Response was " + parsedResponse);
                        }
                    } catch (final OutOfMemoryError e) {
                        Log.e(LOGTAG, "Out of memory when posting to " + url + ".", e);
                    } catch (final MalformedURLException e) {
                        Log.e(LOGTAG, "Cannot interpret " + url + " as a URL.", e);
                    } catch (final IOException e) {
                        logAboutMessageToAlooma("Cannot post message to " + url + ".", e);
                        deleteEvents = false;
                    }

                    if (deleteEvents) {
                        logAboutMessageToAlooma("Not retrying this batch of events, deleting them from DB.");
                        dbAdapter.cleanupEvents(lastId, table);
                    } else {
                        logAboutMessageToAlooma("Retrying this batch of events.");
                        if (!hasMessages(FLUSH_QUEUE)) {
                            sendEmptyMessageDelayed(FLUSH_QUEUE, mFlushInterval);
                        }
                    }
                }
            }

        }

    }
}
