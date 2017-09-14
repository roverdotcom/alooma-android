package com.alooma.android.mpmetrics;

import android.annotation.TargetApi;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import com.alooma.android.util.ALLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Future;


/**
 * Core class for interacting with Alooma Analytics.
 *
 * <p>Call {@link #getInstance(Context, String)} with
 * your main application activity and your Alooma API token as arguments
 * an to get an instance you can use to report how users are using your
 * application.
 *
 * <p>Once you have an instance, you can send events to Alooma
 * using {@link #track(String, JSONObject)}
 *
 * <p>The Alooma library will periodically send information to
 * Alooma servers, so your application will need to have
 * <tt>android.permission.INTERNET</tt>. In addition, to preserve
 * battery life, messages to Alooma servers may not be sent immediately
 * when you call <tt>track</tt>.
 * The library will send messages periodically throughout the lifetime
 * of your application, but you will need to call {@link #flush()}
 * before your application is completely shutdown to ensure all of your
 * events are sent.
 *
 * <p>A typical use-case for the library might look like this:
 *
 * <pre>
 * {@code
 * public class MainActivity extends Activity {
 *      AloomaAPI mAlooma;
 *
 *      public void onCreate(Bundle saved) {
 *          mAlooma = AloomaAPI.getInstance(this, "YOUR ALOOMA API TOKEN");
 *          ...
 *      }
 *
 *      public void whenSomethingInterestingHappens(int flavor) {
 *          JSONObject properties = new JSONObject();
 *          properties.put("flavor", flavor);
 *          mAlooma.track("Something Interesting Happened", properties);
 *          ...
 *      }
 *
 *      public void onDestroy() {
 *          mAlooma.flush();
 *          super.onDestroy();
 *      }
 * }
 * }
 * </pre>
 *
 * <p>In addition to this documentation, you may wish to take a look at
 * <a href="https://github.com/aloomaio/sample-android-alooma-integration">the Alooma sample Android application</a>.
 * It demonstrates a variety of techniques
 *
 * <p>There are also <a href="https://support.alooma.com/hc/en-us/articles/214019489-Android-SDK-integration">step-by-step getting started documents</a>
 * available at alooma.com
 *
 * @see <a href="https://support.alooma.com/hc/en-us/articles/214019489-Android-SDK-integration">getting started documentation for tracking events</a>
 * @see <a href="https://github.com/aloomaio/sample-android-alooma-integration">The Alooma Android sample application</a>
 */
public class AloomaAPI {
    /**
     * String version of the library.
     */
    public static final String VERSION = ALConfig.VERSION;

    /**
     * You shouldn't instantiate AloomaAPI objects directly.
     * Use AloomaAPI.getInstance to get an instance.
     */
    AloomaAPI(Context context, String token) {
        this(context, token, ALConfig.getInstance(context));
    }

    /**
     * You shouldn't instantiate AloomaAPI objects directly.
     * Use AloomaAPI.getInstance to get an instance.
     */
    AloomaAPI(Context context, String token, ALConfig config) {
        mContext = context;
        mToken = token;
        mConfig = config;

        final Map<String, String> deviceInfo = new HashMap<String, String>();
        deviceInfo.put("$android_lib_version", ALConfig.VERSION);
        deviceInfo.put("$android_os", "Android");
        deviceInfo.put("$android_os_version", Build.VERSION.RELEASE == null ? "UNKNOWN" : Build.VERSION.RELEASE);
        deviceInfo.put("$android_manufacturer", Build.MANUFACTURER == null ? "UNKNOWN" : Build.MANUFACTURER);
        deviceInfo.put("$android_brand", Build.BRAND == null ? "UNKNOWN" : Build.BRAND);
        deviceInfo.put("$android_model", Build.MODEL == null ? "UNKNOWN" : Build.MODEL);
        try {
            final PackageManager manager = mContext.getPackageManager();
            final PackageInfo info = manager.getPackageInfo(mContext.getPackageName(), 0);
            deviceInfo.put("$android_app_version", info.versionName);
            deviceInfo.put("$android_app_version_code", Integer.toString(info.versionCode));
        } catch (final PackageManager.NameNotFoundException e) {
            ALLog.e(LOGTAG, "Exception getting app version name", e);
        }
        mDeviceInfo = Collections.unmodifiableMap(deviceInfo);
        mPersistentIdentity = getPersistentIdentity(context, token);
        mEventTimings = mPersistentIdentity.getTimeEvents();

        mMessages = getAnalyticsMessages();

        if (mPersistentIdentity.isFirstLaunch(MPDbAdapter.getInstance(mContext).getDatabaseFile().exists())) {
            track(AutomaticEvents.FIRST_OPEN, null, true);

            mPersistentIdentity.setHasLaunched();
        }

        registerAloomaActivityLifecycleCallbacks();

        if (sendAppOpen()) {
            track("$app_open", null);
        }

        if (!mPersistentIdentity.isFirstIntegration(mToken)) {
            try {
                final JSONObject messageProps = new JSONObject();

                messageProps.put("mp_lib", "Android");
                messageProps.put("lib", "Android");
                messageProps.put("distinct_id", token);

                final AnalyticsMessages.EventDescription eventDescription =
                        new AnalyticsMessages.EventDescription("Integration", messageProps, "85053bf24bba75239b16a601d9387e17", false);
                mMessages.eventsMessage(eventDescription);
                mMessages.postToServer(new AnalyticsMessages.FlushDescription("85053bf24bba75239b16a601d9387e17"));

                mPersistentIdentity.setIsIntegrated(mToken);
            } catch (JSONException e) {
            }
        }

        if (mPersistentIdentity.isNewVersion(deviceInfo.get("$android_app_version_code"))) {
            try {
                final JSONObject messageProps = new JSONObject();
                messageProps.put(AutomaticEvents.VERSION_UPDATED, deviceInfo.get("$android_app_version"));
                track(AutomaticEvents.APP_UPDATED, messageProps, true);
            } catch (JSONException e) {}

        }

        ExceptionHandler.init();
    }

    /**
     * Get the instance of AloomaAPI associated with your Alooma project token.
     *
     * <p>Use getInstance to get a reference to a shared
     * instance of AloomaAPI you can use to send events to Alooma.</p>
     * <p>getInstance is thread safe, but the returned instance is not,
     * and may be shared with other callers of getInstance.
     * The best practice is to call getInstance, and use the returned AloomaAPI,
     * object from a single thread (probably the main UI thread of your application).</p>
     * <p>If you do choose to track events from multiple threads in your application,
     * you should synchronize your calls on the instance itself, like so:</p>
     * <pre>
     * {@code
     * AloomaAPI instance = AloomaAPI.getInstance(context, token);
     * synchronized(instance) { // Only necessary if the instance will be used in multiple threads.
     *     instance.track(...)
     * }
     * }
     * </pre>
     *
     * @param context The application context you are tracking
     * @param token Your Alooma project token. You can get your project token on the Alooma web site,
     *     in the settings dialog.
     * @return an instance of AloomaAPI associated with your project
     */
    public static AloomaAPI getInstance(Context context, String token) {
        if (null == token || null == context) {
            return null;
        }
        synchronized (sInstanceMap) {
            final Context appContext = context.getApplicationContext();

            Map <Context, AloomaAPI> instances = sInstanceMap.get(token);
            if (null == instances) {
                instances = new HashMap<Context, AloomaAPI>();
                sInstanceMap.put(token, instances);
            }

            AloomaAPI instance = instances.get(appContext);
            if (null == instance && ConfigurationChecker.checkBasicConfiguration(appContext)) {
                instance = new AloomaAPI(appContext, token);
                instances.put(appContext, instance);
            }
            return instance;
        }
    }

    /**
     * This function creates a distinct_id alias from alias to original. If original is null, then it will create an alias
     * to the current events distinct_id, which may be the distinct_id randomly generated by the Alooma library
     * before {@link #identify(String)} is called.
     *
     * <p>This call does not identify the user after. You must still call both {@link #identify(String)}
     * if you wish the new alias to be used for Events.
     *
     * @param alias the new distinct_id that should represent original.
     * @param original the old distinct_id that alias will be mapped to.
     */
    public void alias(String alias, String original) {
        if (original == null) {
            original = getDistinctId();
        }
        if (alias.equals(original)) {
            ALLog.w(LOGTAG, "Attempted to alias identical distinct_ids " + alias + ". Alias message will not be sent.");
            return;
        }

        try {
            final JSONObject j = new JSONObject();
            j.put("alias", alias);
            j.put("original", original);
            track("$create_alias", j);
        } catch (final JSONException e) {
            ALLog.e(LOGTAG, "Failed to alias", e);
        }
        flush();
    }

    /**
     * Associate all future calls to {@link #track(String, JSONObject)} with the user identified by
     * the given distinct id.
     *
     * <p>Calls to {@link #track(String, JSONObject)} made before corresponding calls to
     * identify will use an internally generated distinct id, which means it is best
     * to call identify early to ensure that your Alooma funnels and retention
     * analytics can continue to track the user throughout their lifetime. We recommend
     * calling identify as early as you can.
     *
     * <p>Once identify is called, the given distinct id persists across restarts of your
     * application.
     *
     * @param distinctId a string uniquely identifying this user. Events sent to
     *     Alooma using the same disinct_id will be considered associated with the
     *     same visitor/customer for retention and funnel reporting, so be sure that the given
     *     value is globally unique for each individual user you intend to track.
     *
     */
    public void identify(String distinctId) {
        synchronized (mPersistentIdentity) {
            mPersistentIdentity.setEventsDistinctId(distinctId);
        }
    }

    /**
     * Begin timing of an event. Calling timeEvent("Thing") will not send an event, but
     * when you eventually call track("Thing"), your tracked event will be sent with a "$duration"
     * property, representing the number of seconds between your calls.
     *
     * @param eventName the name of the event to track with timing.
     */
    public void timeEvent(final String eventName) {
        final long writeTime = System.currentTimeMillis();
        synchronized (mEventTimings) {
            mEventTimings.put(eventName, writeTime);
            mPersistentIdentity.addTimeEvent(eventName, writeTime);
        }
    }

    /**
     * Retrieves the time elapsed for the named event since timeEvent() was called.
     *
     * @param eventName the name of the event to be tracked that was previously called with timeEvent()
     */
    public double eventElapsedTime(final String eventName) {
        final long currentTime = System.currentTimeMillis();
        Long startTime;
        synchronized (mEventTimings) {
            startTime = mEventTimings.get(eventName);
        }
        return startTime == null ? 0 : (double)((currentTime - startTime) / 1000);
    }

    /**
     * Track an event.
     *
     * <p>Every call to track eventually results in a data point sent to Alooma. These data points
     * are what are measured, counted, and broken down to create your Alooma reports. Events
     * have a string name, and an optional set of name/value pairs that describe the properties of
     * that event.
     *
     * @param eventName The name of the event to send
     * @param properties A Map containing the key value pairs of the properties to include in this event.
     *                   Pass null if no extra properties exist.
     *
     * See also {@link #track(String, org.json.JSONObject)}
     */
    public void trackMap(String eventName, Map<String, Object> properties) {
        if (null == properties) {
            track(eventName, null);
        } else {
            try {
                track(eventName, new JSONObject(properties));
            } catch (NullPointerException e) {
                ALLog.w(LOGTAG, "Can't have null keys in the properties of trackMap!");
            }
        }
    }

    /**
     * Track an event.
     *
     * <p>Every call to track eventually results in a data point sent to Alooma. These data points
     * are what are measured, counted, and broken down to create your Alooma reports. Events
     * have a string name, and an optional set of name/value pairs that describe the properties of
     * that event.
     *
     * @param eventName The name of the event to send
     * @param properties A JSONObject containing the key value pairs of the properties to include in this event.
     *                   Pass null if no extra properties exist.
     */
    // DO NOT DOCUMENT, but track() must be thread safe since it is used to track events.
    // This MAY CHANGE IN FUTURE RELEASES, so minimize code that assumes thread safety
    // (and perhaps document that code here).
    public void track(String eventName, JSONObject properties) {
        track(eventName, properties, false);
    }

    /**
     * Equivalent to {@link #track(String, JSONObject)} with a null argument for properties.
     * Consider adding properties to your tracking to get the best insights and experience from Alooma.
     * @param eventName the name of the event to send
     */
    public void track(String eventName) {
        track(eventName, null);
    }

    /**
     * Push all queued Alooma events changes to Alooma servers.
     *
     * <p>Events are pushed gradually throughout
     * the lifetime of your application. This means that to ensure that all messages
     * are sent to Alooma when your application is shut down, you will
     * need to call flush() to let the Alooma library know it should
     * send all remaining messages to the server. We strongly recommend
     * placing a call to flush() in the onDestroy() method of
     * your main application activity.
     */
    public void flush() {
        mMessages.postToServer(new AnalyticsMessages.FlushDescription(mToken));
    }

    /**
     * Returns a json object of the user's current super properties
     *
     *<p>SuperProperties are a collection of properties that will be sent with every event to Alooma,
     * and persist beyond the lifetime of your application.
     */
      public JSONObject getSuperProperties() {
          JSONObject ret = new JSONObject();
          mPersistentIdentity.addSuperPropertiesToObject(ret);
          return ret;
      }

    /**
     * Returns the string id currently being used to uniquely identify the user associated
     * with events sent using {@link #track(String, JSONObject)}. Before any calls to
     * {@link #identify(String)}, this will be an id automatically generated by the library.
     *
     * @return The distinct id associated with event tracking
     *
     * @see #identify(String)
     */
    public String getDistinctId() {
        return mPersistentIdentity.getEventsDistinctId();
     }

    /**
     * Register properties that will be sent with every subsequent call to {@link #track(String, JSONObject)}.
     *
     * <p>SuperProperties are a collection of properties that will be sent with every event to Alooma,
     * and persist beyond the lifetime of your application.
     *
     * <p>Setting a superProperty with registerSuperProperties will store a new superProperty,
     * possibly overwriting any existing superProperty with the same name (to set a
     * superProperty only if it is currently unset, use {@link #registerSuperPropertiesOnce(JSONObject)})
     *
     * <p>SuperProperties will persist even if your application is taken completely out of memory.
     * to remove a superProperty, call {@link #unregisterSuperProperty(String)} or {@link #clearSuperProperties()}
     *
     * @param superProperties    A Map containing super properties to register
     *
     * See also {@link #registerSuperProperties(org.json.JSONObject)}
     */
    public void registerSuperPropertiesMap(Map<String, Object> superProperties) {
        if (null == superProperties) {
            ALLog.e(LOGTAG, "registerSuperPropertiesMap does not accept null properties");
            return;
        }

        try {
            registerSuperProperties(new JSONObject(superProperties));
        } catch (NullPointerException e) {
            ALLog.w(LOGTAG, "Can't have null keys in the properties of registerSuperPropertiesMap");
        }
    }

    /**
     * Register properties that will be sent with every subsequent call to {@link #track(String, JSONObject)}.
     *
     * <p>SuperProperties are a collection of properties that will be sent with every event to Alooma,
     * and persist beyond the lifetime of your application.
     *
     * <p>Setting a superProperty with registerSuperProperties will store a new superProperty,
     * possibly overwriting any existing superProperty with the same name (to set a
     * superProperty only if it is currently unset, use {@link #registerSuperPropertiesOnce(JSONObject)})
     *
     * <p>SuperProperties will persist even if your application is taken completely out of memory.
     * to remove a superProperty, call {@link #unregisterSuperProperty(String)} or {@link #clearSuperProperties()}
     *
     * @param superProperties    A JSONObject containing super properties to register
     * @see #registerSuperPropertiesOnce(JSONObject)
     * @see #unregisterSuperProperty(String)
     * @see #clearSuperProperties()
     */
    public void registerSuperProperties(JSONObject superProperties) {
        mPersistentIdentity.registerSuperProperties(superProperties);
    }

    /**
     * Remove a single superProperty, so that it will not be sent with future calls to {@link #track(String, JSONObject)}.
     *
     * <p>If there is a superProperty registered with the given name, it will be permanently
     * removed from the existing superProperties.
     * To clear all superProperties, use {@link #clearSuperProperties()}
     *
     * @param superPropertyName name of the property to unregister
     * @see #registerSuperProperties(JSONObject)
     */
    public void unregisterSuperProperty(String superPropertyName) {
        mPersistentIdentity.unregisterSuperProperty(superPropertyName);
    }

    /**
     * Register super properties for events, only if no other super property with the
     * same names has already been registered.
     *
     * <p>Calling registerSuperPropertiesOnce will never overwrite existing properties.
     *
     * @param superProperties A Map containing the super properties to register.
     *
     * See also {@link #registerSuperPropertiesOnce(org.json.JSONObject)}
     */
    public void registerSuperPropertiesOnceMap(Map<String, Object> superProperties) {
        if (null == superProperties) {
            ALLog.e(LOGTAG, "registerSuperPropertiesOnceMap does not accept null properties");
            return;
        }

        try {
            registerSuperPropertiesOnce(new JSONObject(superProperties));
        } catch (NullPointerException e) {
            ALLog.w(LOGTAG, "Can't have null keys in the properties of registerSuperPropertiesOnce!");
        }
    }

    /**
     * Register super properties for events, only if no other super property with the
     * same names has already been registered.
     *
     * <p>Calling registerSuperPropertiesOnce will never overwrite existing properties.
     *
     * @param superProperties A JSONObject containing the super properties to register.
     * @see #registerSuperProperties(JSONObject)
     */
    public void registerSuperPropertiesOnce(JSONObject superProperties) {
        mPersistentIdentity.registerSuperPropertiesOnce(superProperties);
    }

    /**
     * Erase all currently registered superProperties.
     *
     * <p>Future tracking calls to Alooma will not contain the specific
     * superProperties registered before the clearSuperProperties method was called.
     *
     * <p>To remove a single superProperty, use {@link #unregisterSuperProperty(String)}
     *
     * @see #registerSuperProperties(JSONObject)
     */
    public void clearSuperProperties() {
        mPersistentIdentity.clearSuperProperties();
    }

    /**
     * Updates super properties in place. Given a SuperPropertyUpdate object, will
     * pass the current values of SuperProperties to that update and replace all
     * results with the return value of the update. Updates are synchronized on
     * the underlying super properties store, so they are guaranteed to be thread safe
     * (but long running updates may slow down your tracking.)
     *
     * @param update A function from one set of super properties to another. The update should not return null.
     */
    public void updateSuperProperties(SuperPropertyUpdate update) {
        mPersistentIdentity.updateSuperProperties(update);
    }

    /**
     * Clears all distinct_ids, superProperties, and push registrations from persistent storage.
     * Will not clear referrer information.
     */
    public void reset() {
        // Will clear distinct_ids, superProperties,
        // Will have no effect
        // on messages already queued to send with AnalyticsMessages.
        mPersistentIdentity.clearPreferences();
        identify(getDistinctId());
        flush();
    }

    /**
     * Returns an unmodifiable map that contains the device description properties
     * that will be sent to Alooma. These are not all of the default properties,
     * but are a subset that are dependant on the user's device or installed version
     * of the host application, and are guaranteed not to change while the app is running.
     */
    public Map<String, String> getDeviceInfo() {
        return mDeviceInfo;
    }


    /**
     * This method is a no-op, kept for compatibility purposes.
     *
     * To enable verbose logging about communication with Alooma, add
     * {@code
     * <meta-data android:name="com.alooma.android.ALConfig.EnableDebugLogging" />
     * }
     *
     * To the {@code <application>} tag of your AndroidManifest.xml file.
     *
     * @deprecated in 4.1.0, use Manifest meta-data instead
     */
    @Deprecated
    public void logPosts() {
        ALLog.i(
                LOGTAG,
                "AloomaAPI.logPosts() is deprecated.\n" +
                        "    To get verbose debug level logging, add\n" +
                        "    <meta-data android:name=\"com.alooma.android.ALConfig.EnableDebugLogging\" value=\"true\" />\n" +
                        "    to the <application> section of your AndroidManifest.xml."
        );
    }

    /**
     * Attempt to register AloomaActivityLifecycleCallbacks to the application's event lifecycle.
     *
     * This is only available if the android version is >= 16. You can disable livecycle callbacks by setting
     * com.alooma.android.ALConfig.AutoShowAloomaUpdates to false in your AndroidManifest.xml
     *
     * This function is automatically called when the library is initialized unless you explicitly
     * set com.alooma.android.ALConfig.AutoShowAloomaUpdates to false in your AndroidManifest.xml
     */
    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    /* package */ void registerAloomaActivityLifecycleCallbacks() {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            if (mContext.getApplicationContext() instanceof Application) {
                final Application app = (Application) mContext.getApplicationContext();
                mAloomaActivityLifecycleCallbacks = new AloomaActivityLifecycleCallbacks(this, mConfig);
                app.registerActivityLifecycleCallbacks(mAloomaActivityLifecycleCallbacks);
            } else {
                ALLog.i(LOGTAG, "Context is not an Application, We won't be able to automatically flush on an app background.");
            }
        }
    }

    /**
     * Based on the application's event lifecycle this method will determine whether the app
     * is running in the foreground or not.
     *
     * If your build version is below 14 this method will always return false.
     *
     * @return True if the app is running in the foreground.
     */
    public boolean isAppInForeground() {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            if (mAloomaActivityLifecycleCallbacks != null) {
                return mAloomaActivityLifecycleCallbacks.isInForeground();
            }
        } else {
            ALLog.e(LOGTAG, "Your build version is below 14. This method will always return false.");
        }

        return false;
    }

    // when OS-level events occur.
    /* package */ interface InstanceProcessor {
        public void process(AloomaAPI m);
    }

    /* package */ static void allInstances(InstanceProcessor processor) {
        synchronized (sInstanceMap) {
            for (final Map<Context, AloomaAPI> contextInstances : sInstanceMap.values()) {
                for (final AloomaAPI instance : contextInstances.values()) {
                    processor.process(instance);
                }
            }
        }
    }

    ////////////////////////////////////////////////////////////////////
    // Conveniences for testing. These methods should not be called by
    // non-test client code.

    /* package */ AnalyticsMessages getAnalyticsMessages() {
        return AnalyticsMessages.getInstance(mContext);
    }

    /* package */ PersistentIdentity getPersistentIdentity(final Context context, final String token) {

        final String prefsName = "com.alooma.android.mpmetrics.AloomaAPI_" + token;
        final Future<SharedPreferences> storedPreferences = sPrefsLoader.loadPreferences(context, prefsName, null);

        final String timeEventsPrefsName = "com.alooma.android.mpmetrics.AloomaAPI.TimeEvents_" + token;
        final Future<SharedPreferences> timeEventsPrefs = sPrefsLoader.loadPreferences(context, timeEventsPrefsName, null);

        final String aloomaPrefsName = "com.alooma.android.mpmetrics.Alooma";
        final Future<SharedPreferences> aloomaPrefs = sPrefsLoader.loadPreferences(context, aloomaPrefsName, null);

        return new PersistentIdentity(storedPreferences, timeEventsPrefs, aloomaPrefs);
    }


    /* package */ boolean sendAppOpen() {
        return !mConfig.getDisableAppOpenEvent();
    }

    ////////////////////////////////////////////////////
    protected void flushNoDecideCheck() {
        mMessages.postToServer(new AnalyticsMessages.FlushDescription(mToken));
    }

    protected void track(String eventName, JSONObject properties, boolean isAutomaticEvent) {
//        if (isAutomaticEvent && !mDecideMessages.shouldTrackAutomaticEvent()) {
//            return;
//        }

        final Long eventBegin;
        synchronized (mEventTimings) {
            eventBegin = mEventTimings.get(eventName);
            mEventTimings.remove(eventName);
            mPersistentIdentity.removeTimeEvent(eventName);
        }

        try {
            final JSONObject messageProps = new JSONObject();

            mPersistentIdentity.addSuperPropertiesToObject(messageProps);

            // Don't allow super properties or referral properties to override these fields,
            // but DO allow the caller to override them in their given properties.
            final double timeSecondsDouble = (System.currentTimeMillis()) / 1000.0;
            final long timeSeconds = (long) timeSecondsDouble;
            messageProps.put("time", timeSeconds);
            messageProps.put("distinct_id", getDistinctId());

            if (null != eventBegin) {
                final double eventBeginDouble = ((double) eventBegin) / 1000.0;
                final double secondsElapsed = timeSecondsDouble - eventBeginDouble;
                messageProps.put("$duration", secondsElapsed);
            }

            if (null != properties) {
                final Iterator<?> propIter = properties.keys();
                while (propIter.hasNext()) {
                    final String key = (String) propIter.next();
                    messageProps.put(key, properties.get(key));
                }
            }

            final AnalyticsMessages.EventDescription eventDescription =
                    new AnalyticsMessages.EventDescription(eventName, messageProps, mToken, isAutomaticEvent);
            mMessages.eventsMessage(eventDescription);

        } catch (final JSONException e) {
            ALLog.e(LOGTAG, "Exception tracking event " + eventName, e);
        }
    }

    private final Context mContext;
    private final AnalyticsMessages mMessages;
    private final ALConfig mConfig;
    private final String mToken;
    private final PersistentIdentity mPersistentIdentity;
    private final Map<String, String> mDeviceInfo;
    private final Map<String, Long> mEventTimings;
    private AloomaActivityLifecycleCallbacks mAloomaActivityLifecycleCallbacks;

    // Maps each token to a singleton AloomaAPI instance
    private static final Map<String, Map<Context, AloomaAPI>> sInstanceMap = new HashMap<String, Map<Context, AloomaAPI>>();
    private static final SharedPreferencesLoader sPrefsLoader = new SharedPreferencesLoader();
    private static Future<SharedPreferences> sReferrerPrefs;

    private static final String LOGTAG = "AloomaAPI.API";
    private static final String APP_LINKS_LOGTAG = "AloomaAPI.AL";
    private static final String ENGAGE_DATE_FORMAT_STRING = "yyyy-MM-dd'T'HH:mm:ss";
}
