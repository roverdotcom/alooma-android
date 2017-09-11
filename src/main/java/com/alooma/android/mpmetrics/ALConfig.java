package com.alooma.android.mpmetrics;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;

import com.alooma.android.BuildConfig;
import com.alooma.android.util.ALLog;
import com.alooma.android.util.OfflineMode;

import java.security.GeneralSecurityException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;


/**
 * Stores global configuration options for the Alooma library. You can enable and disable configuration
 * options using &lt;meta-data&gt; tags inside of the &lt;application&gt; tag in your AndroidManifest.xml.
 * All settings are optional, and default to reasonable recommended values. Most users will not have to
 * set any options.
 *
 * Alooma understands the following options:
 *
 * <dl>
 *     <dt>com.alooma.android.ALConfig.EnableDebugLogging</dt>
 *     <dd>A boolean value. If true, emit more detailed log messages. Defaults to false</dd>
 *
 *     <dt>com.alooma.android.ALConfig.BulkUploadLimit</dt>
 *     <dd>An integer count of messages, the maximum number of messages to queue before an upload attempt. This value should be less than 50.</dd>
 *
 *     <dt>com.alooma.android.ALConfig.FlushInterval</dt>
 *     <dd>An integer number of milliseconds, the maximum time to wait before an upload if the bulk upload limit isn't reached.</dd>
 *
 *     <dt>com.alooma.android.ALConfig.DebugFlushInterval</dt>
 *     <dd>An integer number of milliseconds, the maximum time to wait before an upload if the bulk upload limit isn't reached in debug mode.</dd>
 *
 *     <dt>com.alooma.android.ALConfig.DataExpiration</dt>
 *     <dd>An integer number of milliseconds, the maximum age of records to send to Alooma. Corresponds to Alooma's server-side limit on record age.</dd>
 *
 *     <dt>com.alooma.android.ALConfig.MinimumDatabaseLimit</dt>
 *     <dd>An integer number of bytes. Alooma attempts to limit the size of its persistent data
 *          queue based on the storage capacity of the device, but will always allow queing below this limit. Higher values
 *          will take up more storage even when user storage is very full.</dd>
 *
 *     <dt>com.alooma.android.ALConfig.DisableFallback</dt>
 *     <dd>A boolean value. If true, do not send data over HTTP, even if HTTPS is unavailable. Defaults to true - by default, Alooma will only attempt to communicate over HTTPS.</dd>
 *
 *     <dt>com.alooma.android.ALConfig.DisableAppOpenEvent</dt>
 *     <dd>A boolean value. If true, do not send an "$app_open" event when the AloomaAPI object is created for the first time. Defaults to true - the $app_open event will not be sent by default.</dd>
 *
 *     <dt>com.alooma.android.ALConfig.EventsEndpoint</dt>
 *     <dd>A string URL. If present, the library will attempt to send events to this endpoint rather than to the default Alooma endpoint.</dd>
 *
 *     <dt>com.alooma.android.ALConfig.EventsFallbackEndpoint</dt>
 *     <dd>A string URL. If present, AND if DisableFallback is false, events will be sent to this endpoint if the EventsEndpoint cannot be reached.</dd>
 *
 *     <dt>com.alooma.android.ALConfig.DecideEndpoint</dt>
 *     <dd>A string URL. If present, the library will attempt to get notification, codeless event tracking, and A/B test variant information from this url rather than the default Alooma endpoint.</dd>
 *
 *     <dt>com.alooma.android.ALConfig.DecideFallbackEndpoint</dt>
 *     <dd>A string URL. If present, AND if DisableFallback is false, the library will query this url if the DecideEndpoint url cannot be reached.</dd>
 *
 *     <dt>com.alooma.android.ALConfig.EditorUrl</dt>
 *     <dd>A string URL. If present, the library will attempt to connect to this endpoint when in interactive editing mode, rather than to the default Alooma editor url.</dd>
 * </dl>
 *
 */
public class ALConfig {

    public static final String VERSION = BuildConfig.ALOOMA_VERSION; //MJA

    public static boolean DEBUG = false;

    /**
     * Minimum API level for support of rich UI features, like In-App notifications and dynamic event binding.
     * Devices running OS versions below this level will still support tracking and push notification features.
     */
    public static final int UI_FEATURES_MIN_API = 16;

    // Name for persistent storage of app referral SharedPreferences
    /* package */ static final String REFERRER_PREFS_NAME = "com.alooma.android.mpmetrics.ReferralInfo";

    // Max size of the number of notifications we will hold in memory. Since they may contain images,
    // we don't want to suck up all of the memory on the device.
    /* package */ static final int MAX_NOTIFICATION_CACHE_COUNT = 2;

    // Instances are safe to store, since they're immutable and always the same.
    public static ALConfig getInstance(Context context) {
        synchronized (sInstanceLock) {
            if (null == sInstance) {
                final Context appContext = context.getApplicationContext();
                sInstance = readConfig(appContext);
            }
        }

        return sInstance;
    }

    /**
     * The AloomaAPI will use the system default SSL socket settings under ordinary circumstances.
     * That means it will ignore settings you associated with the default SSLSocketFactory in the
     * schema registry or in underlying HTTP libraries. If you'd prefer for Alooma to use your
     * own SSL settings, you'll need to call setSSLSocketFactory early in your code, like this
     *
     * {@code
     * <pre>
     *     ALConfig.getInstance(context).setSSLSocketFactory(someCustomizedSocketFactory);
     * </pre>
     * }
     *
     * Your settings will be globally available to all Alooma instances, and will be used for
     * all SSL connections in the library. The call is thread safe, but should be done before
     * your first call to AloomaAPI.getInstance to insure that the library never uses it's
     * default.
     *
     * The given socket factory may be used from multiple threads, which is safe for the system
     * SSLSocketFactory class, but if you pass a subclass you should ensure that it is thread-safe
     * before passing it to Alooma.
     *
     * @param factory an SSLSocketFactory that
     */
    public synchronized void setSSLSocketFactory(SSLSocketFactory factory) {
        mSSLSocketFactory = factory;
    }

    /**
     * {@link OfflineMode} allows Alooma to be in-sync with client offline internal logic.
     * If you want to integrate your own logic with Alooma you'll need to call
     * {@link #setOfflineMode(OfflineMode)} early in your code, like this
     *
     * {@code
     * <pre>
     *     ALConfig.getInstance(context).setOfflineMode(OfflineModeImplementation);
     * </pre>
     * }
     *
     * Your settings will be globally available to all Alooma instances, and will be used across
     * all the library. The call is thread safe, but should be done before
     * your first call to AloomaAPI.getInstance to insure that the library never uses it's
     * default.
     *
     * The given {@link OfflineMode} may be used from multiple threads, you should ensure that
     * your implementation is thread-safe before passing it to Alooma.
     *
     * @param offlineMode client offline implementation to use on Alooma
     */
    public synchronized void setOfflineMode(OfflineMode offlineMode) {
        mOfflineMode = offlineMode;
    }

    /* package */ ALConfig(Bundle metaData, Context context) {

        // By default, we use a clean, FACTORY default SSLSocket. In general this is the right
        // thing to do, and some other third party libraries change the
        SSLSocketFactory foundSSLFactory;
        try {
            final SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, null, null);
            foundSSLFactory = sslContext.getSocketFactory();
        } catch (final GeneralSecurityException e) {
            ALLog.i("AloomaAPI.Conf", "System has no SSL support. Built-in events editor will not be available", e);
            foundSSLFactory = null;
        }
        mSSLSocketFactory = foundSSLFactory;

        DEBUG = metaData.getBoolean("com.alooma.android.ALConfig.EnableDebugLogging", false);
        if (DEBUG) {
            ALLog.setLevel(ALLog.VERBOSE);
        }

        if (metaData.containsKey("com.alooma.android.ALConfig.DebugFlushInterval")) {
            ALLog.w(LOGTAG, "We do not support com.alooma.android.ALConfig.DebugFlushInterval anymore. There will only be one flush interval. Please, update your AndroidManifest.xml.");
        }

        mBulkUploadLimit = metaData.getInt("com.alooma.android.ALConfig.BulkUploadLimit", 40); // 40 records default
        mFlushInterval = metaData.getInt("com.alooma.android.ALConfig.FlushInterval", 60 * 1000); // one minute default
        mDataExpiration = metaData.getInt("com.alooma.android.ALConfig.DataExpiration", 1000 * 60 * 60 * 24 * 5); // 5 days default
        mMinimumDatabaseLimit = metaData.getInt("com.alooma.android.ALConfig.MinimumDatabaseLimit", 20 * 1024 * 1024); // 20 Mb
        mDisableFallback = metaData.getBoolean("com.alooma.android.ALConfig.DisableFallback", true);
        mDisableAppOpenEvent = metaData.getBoolean("com.alooma.android.ALConfig.DisableAppOpenEvent", true);
        mDisableDecideChecker = metaData.getBoolean("com.alooma.android.ALConfig.DisableDecideChecker", false);
        mMinSessionDuration = metaData.getInt("com.alooma.android.ALConfig.MinimumSessionDuration", 10 * 1000); // 10 seconds
        mSessionTimeoutDuration = metaData.getInt("com.alooma.android.ALConfig.SessionTimeoutDuration", Integer.MAX_VALUE); // no timeout by default

        mTestMode = metaData.getBoolean("com.alooma.android.ALConfig.TestMode", false);

        String eventsEndpoint = metaData.getString("com.alooma.android.ALConfig.EventsEndpoint");
        if (null == eventsEndpoint) {
            eventsEndpoint = "https://api.alooma.com/track?ip=1";
        }
        mEventsEndpoint = eventsEndpoint;

        String eventsFallbackEndpoint = metaData.getString("com.alooma.android.ALConfig.EventsFallbackEndpoint");
        if (null == eventsFallbackEndpoint) {
            eventsFallbackEndpoint = "http://api.alooma.com/track?ip=1";
        }
        mEventsFallbackEndpoint = eventsFallbackEndpoint;

        String decideEndpoint = metaData.getString("com.alooma.android.ALConfig.DecideEndpoint");
        if (null == decideEndpoint) {
            decideEndpoint = "https://decide.alooma.com/decide";
        }
        mDecideEndpoint = decideEndpoint;

        String decideFallbackEndpoint = metaData.getString("com.alooma.android.ALConfig.DecideFallbackEndpoint");
        if (null == decideFallbackEndpoint) {
            decideFallbackEndpoint = "http://decide.alooma.com/decide";
        }
        mDecideFallbackEndpoint = decideFallbackEndpoint;

        String editorUrl = metaData.getString("com.alooma.android.ALConfig.EditorUrl");
        if (null == editorUrl) {
            editorUrl = "wss://switchboard.alooma.com/connect/";
        }
        mEditorUrl = editorUrl;

        ALLog.v(LOGTAG,
                "Alooma (" + VERSION + ") configured with:\n" +
                "    BulkUploadLimit " + getBulkUploadLimit() + "\n" +
                "    FlushInterval " + getFlushInterval() + "\n" +
                "    DataExpiration " + getDataExpiration() + "\n" +
                "    MinimumDatabaseLimit " + getMinimumDatabaseLimit() + "\n" +
                "    DisableFallback " + getDisableFallback() + "\n" +
                "    DisableAppOpenEvent " + getDisableAppOpenEvent() + "\n" +
                "    EnableDebugLogging " + DEBUG + "\n" +
                "    TestMode " + getTestMode() + "\n" +
                "    EventsEndpoint " + getEventsEndpoint() + "\n" +
                "    DecideEndpoint " + getDecideEndpoint() + "\n" +
                "    EventsFallbackEndpoint " + getEventsFallbackEndpoint() + "\n" +
                "    DecideFallbackEndpoint " + getDecideFallbackEndpoint() + "\n" +
                "    EditorUrl " + getEditorUrl() + "\n" +
                "    DisableDecideChecker " + getDisableDecideChecker() + "\n" +
                "    MinimumSessionDuration: " + getMinimumSessionDuration() + "\n" +
                "    SessionTimeoutDuration: " + getSessionTimeoutDuration()
            );
    }

    // Max size of queue before we require a flush. Must be below the limit the service will accept.
    public int getBulkUploadLimit() {
        return mBulkUploadLimit;
    }

    // Target max milliseconds between flushes. This is advisory.
    public int getFlushInterval() {
        return mFlushInterval;
    }

    // Throw away records that are older than this in milliseconds. Should be below the server side age limit for events.
    public int getDataExpiration() {
        return mDataExpiration;
    }

    public int getMinimumDatabaseLimit() { return mMinimumDatabaseLimit; }

    public boolean getDisableFallback() {
        return mDisableFallback;
    }

    public boolean getDisableAppOpenEvent() {
        return mDisableAppOpenEvent;
    }

    public boolean getTestMode() {
        return mTestMode;
    }

    // Preferred URL for tracking events
    public String getEventsEndpoint() {
        return mEventsEndpoint;
    }

    // Preferred URL for pulling decide data
    public String getDecideEndpoint() {
        return mDecideEndpoint;
    }

    // Fallback URL for tracking events if post to preferred URL fails
    public String getEventsFallbackEndpoint() {
        return mEventsFallbackEndpoint;
    }

    // Fallback URL for pulling decide data if preferred URL fails
    public String getDecideFallbackEndpoint() {
        return mDecideFallbackEndpoint;
    }

    // Preferred URL for connecting to the editor websocket
    public String getEditorUrl() {
        return mEditorUrl;
    }

    public boolean getDisableDecideChecker() {
        return mDisableDecideChecker;
    }

    public int getMinimumSessionDuration() {
        return mMinSessionDuration;
    }

    public int getSessionTimeoutDuration() {
        return mSessionTimeoutDuration;
    }

    // This method is thread safe, and assumes that SSLSocketFactory is also thread safe
    // (At this writing, all HttpsURLConnections in the framework share a single factory,
    // so this is pretty safe even if the docs are ambiguous)
    public synchronized SSLSocketFactory getSSLSocketFactory() {
        return mSSLSocketFactory;
    }

    // This method is thread safe, and assumes that OfflineMode is also thread safe
    public synchronized OfflineMode getOfflineMode() {
        return mOfflineMode;
    }

    ///////////////////////////////////////////////

    // Package access for testing only- do not call directly in library code
    /* package */ static ALConfig readConfig(Context appContext) {
        final String packageName = appContext.getPackageName();
        try {
            final ApplicationInfo appInfo = appContext.getPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            Bundle configBundle = appInfo.metaData;
            if (null == configBundle) {
                configBundle = new Bundle();
            }
            return new ALConfig(configBundle, appContext);
        } catch (final NameNotFoundException e) {
            throw new RuntimeException("Can't configure Alooma with package name " + packageName, e);
        }
    }

    private final int mBulkUploadLimit;
    private final int mFlushInterval;
    private final int mDataExpiration;
    private final int mMinimumDatabaseLimit;
    private final boolean mDisableFallback;
    private final boolean mTestMode;
    private final boolean mDisableAppOpenEvent;
    private final String mEventsEndpoint;
    private final String mEventsFallbackEndpoint;
    private final String mDecideEndpoint;
    private final String mDecideFallbackEndpoint;
    private final String mEditorUrl;
    private final boolean mDisableDecideChecker;
    private final int mMinSessionDuration;
    private final int mSessionTimeoutDuration;

    // Mutable, with synchronized accessor and mutator
    private SSLSocketFactory mSSLSocketFactory;
    private OfflineMode mOfflineMode;

    private static ALConfig sInstance;
    private static final Object sInstanceLock = new Object();
    private static final String LOGTAG = "AloomaAPI.Conf";
}
