package com.mixpanel.android.mpmetrics;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import com.mixpanel.android.util.MPLog;

// In order to use writeEdits, we have to suppress the linter's check for commit()/apply()
@SuppressLint("CommitPrefEdits")
/* package */ class PersistentIdentity {

    public static void writeReferrerPrefs(Context context, String preferencesName, Map<String, String> properties) {
        synchronized (sReferrerPrefsLock) {
            final SharedPreferences referralInfo = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
            final SharedPreferences.Editor editor = referralInfo.edit();
            editor.clear();
            for (final Map.Entry<String, String> entry : properties.entrySet()) {
                editor.putString(entry.getKey(), entry.getValue());
            }
            writeEdits(editor);
            sReferrerPrefsDirty = true;
        }
    }

    public PersistentIdentity(Future<SharedPreferences> referrerPreferences, Future<SharedPreferences> storedPreferences, Future<SharedPreferences> timeEventsPreferences, Future<SharedPreferences> mixpanelPreferences) {
        mLoadReferrerPreferences = referrerPreferences;
        mLoadStoredPreferences = storedPreferences;
        mTimeEventsPreferences = timeEventsPreferences;
        mMixpanelPreferences = mixpanelPreferences;
        mSuperPropertiesCache = null;
        mReferrerPropertiesCache = null;
        mIdentitiesLoaded = false;
        mReferrerChangeListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                synchronized (sReferrerPrefsLock) {
                    readReferrerProperties();
                    sReferrerPrefsDirty = false;
                }
            }
        };
    }

    public synchronized void addSuperPropertiesToObject(JSONObject ob) {
        final JSONObject superProperties = this.getSuperPropertiesCache();
        final Iterator<?> superIter = superProperties.keys();
        while (superIter.hasNext()) {
            final String key = (String) superIter.next();

            try {
                ob.put(key, superProperties.get(key));
            } catch (JSONException e) {
                MPLog.e(LOGTAG, "Object read from one JSON Object cannot be written to another", e);
            }
        }
    }

    public synchronized void updateSuperProperties(SuperPropertyUpdate updates) {
        final JSONObject oldPropCache = getSuperPropertiesCache();
        final JSONObject copy = new JSONObject();

        try {
            final Iterator<String> keys = oldPropCache.keys();
            while (keys.hasNext()) {
                final String k = keys.next();
                final Object v = oldPropCache.get(k);
                copy.put(k, v);
            }
        } catch (JSONException e) {
            MPLog.e(LOGTAG, "Can't copy from one JSONObject to another", e);
            return;
        }

        final JSONObject replacementCache = updates.update(copy);
        if (null == replacementCache) {
            MPLog.w(LOGTAG, "An update to Mixpanel's super properties returned null, and will have no effect.");
            return;
        }

        mSuperPropertiesCache = replacementCache;
        storeSuperProperties();
    }

    public Map<String, String> getReferrerProperties() {
        synchronized (sReferrerPrefsLock) {
            if (sReferrerPrefsDirty || null == mReferrerPropertiesCache) {
                readReferrerProperties();
                sReferrerPrefsDirty = false;
            }
        }
        return mReferrerPropertiesCache;
    }

    public synchronized String getEventsDistinctId() {
        if (! mIdentitiesLoaded) {
            readIdentities();
        }
        return mEventsDistinctId;
    }

    public synchronized void setEventsDistinctId(String eventsDistinctId) {
        if (! mIdentitiesLoaded) {
            readIdentities();
        }
        mEventsDistinctId = eventsDistinctId;
        writeIdentities();
    }

    public synchronized void clearPreferences() {
        // Will clear distinct_ids, superProperties
        // Will have no effect
        // on messages already queued to send with AnalyticsMessages.

        try {
            final SharedPreferences prefs = mLoadStoredPreferences.get();
            final SharedPreferences.Editor prefsEdit = prefs.edit();
            prefsEdit.clear();
            writeEdits(prefsEdit);
            readSuperProperties();
            readIdentities();
        } catch (final ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } catch (final InterruptedException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    public synchronized void registerSuperProperties(JSONObject superProperties) {
        final JSONObject propCache = getSuperPropertiesCache();

        for (final Iterator<?> iter = superProperties.keys(); iter.hasNext(); ) {
            final String key = (String) iter.next();
            try {
               propCache.put(key, superProperties.get(key));
            } catch (final JSONException e) {
                MPLog.e(LOGTAG, "Exception registering super property.", e);
            }
        }

        storeSuperProperties();
    }

    public synchronized void unregisterSuperProperty(String superPropertyName) {
        final JSONObject propCache = getSuperPropertiesCache();
        propCache.remove(superPropertyName);

        storeSuperProperties();
    }

    public Map<String, Long> getTimeEvents() {
        Map<String, Long> timeEvents = new HashMap<>();

        try {
            final SharedPreferences prefs = mTimeEventsPreferences.get();

            Map<String, ?> allEntries = prefs.getAll();
            for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
                timeEvents.put(entry.getKey(), Long.valueOf(entry.getValue().toString()));
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }

        return timeEvents;
    }

    // access is synchronized outside (mEventTimings)
    public void removeTimeEvent(String timeEventName) {
        try {
            final SharedPreferences prefs = mTimeEventsPreferences.get();
            final SharedPreferences.Editor editor = prefs.edit();
            editor.remove(timeEventName);
            writeEdits(editor);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
    }

    // access is synchronized outside (mEventTimings)
    public void addTimeEvent(String timeEventName, Long timeEventTimestamp) {
        try {
            final SharedPreferences prefs = mTimeEventsPreferences.get();
            final SharedPreferences.Editor editor = prefs.edit();
            editor.putLong(timeEventName, timeEventTimestamp);
            writeEdits(editor);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
    }

    public synchronized void registerSuperPropertiesOnce(JSONObject superProperties) {
        final JSONObject propCache = getSuperPropertiesCache();

        for (final Iterator<?> iter = superProperties.keys(); iter.hasNext(); ) {
            final String key = (String) iter.next();
            if (! propCache.has(key)) {
                try {
                    propCache.put(key, superProperties.get(key));
                } catch (final JSONException e) {
                    MPLog.e(LOGTAG, "Exception registering super property.", e);
                }
            }
        }// for

        storeSuperProperties();
    }

    public synchronized void clearSuperProperties() {
        mSuperPropertiesCache = new JSONObject();
        storeSuperProperties();
    }

    public synchronized boolean isFirstIntegration(String token) {
        boolean firstLaunch = false;
        try {
            SharedPreferences prefs = mMixpanelPreferences.get();
            firstLaunch = prefs.getBoolean(token, false);
        }  catch (final ExecutionException e) {
            MPLog.e(LOGTAG, "Couldn't read internal Mixpanel shared preferences.", e.getCause());
        } catch (final InterruptedException e) {
            MPLog.e(LOGTAG, "Couldn't read internal Mixpanel from shared preferences.", e);
        }
        return firstLaunch;
    }

    public synchronized void setIsIntegrated(String token) {
        try {
            SharedPreferences.Editor mixpanelEditor = mMixpanelPreferences.get().edit();
            mixpanelEditor.putBoolean(token, true);
            writeEdits(mixpanelEditor);
        } catch (ExecutionException e) {
            MPLog.e(LOGTAG, "Couldn't write internal Mixpanel shared preferences.", e.getCause());
        } catch (InterruptedException e) {
            MPLog.e(LOGTAG, "Couldn't write internal Mixpanel from shared preferences.", e);
        }
    }

    public synchronized boolean isNewVersion(String versionCode) {
        if (versionCode == null) {
            return false;
        }

        Integer version = Integer.valueOf(versionCode);
        try {
            if (sPreviousVersionCode == null) {
                SharedPreferences mixpanelPreferences = mMixpanelPreferences.get();
                sPreviousVersionCode = mixpanelPreferences.getInt("latest_version_code", -1);
                if (sPreviousVersionCode == -1) {
                    sPreviousVersionCode = version;
                    SharedPreferences.Editor mixpanelPreferencesEditor = mMixpanelPreferences.get().edit();
                    mixpanelPreferencesEditor.putInt("latest_version_code", version);
                    writeEdits(mixpanelPreferencesEditor);
                }
            }

            if (sPreviousVersionCode.intValue() < version.intValue()) {
                SharedPreferences.Editor mixpanelPreferencesEditor = mMixpanelPreferences.get().edit();
                mixpanelPreferencesEditor.putInt("latest_version_code", version);
                writeEdits(mixpanelPreferencesEditor);
                return true;
            }
        } catch (ExecutionException e) {
            MPLog.e(LOGTAG, "Couldn't write internal Mixpanel shared preferences.", e.getCause());
        } catch (InterruptedException e) {
            MPLog.e(LOGTAG, "Couldn't write internal Mixpanel from shared preferences.", e);
        }

        return false;
    }

    public synchronized boolean isFirstLaunch(boolean dbExists) {
        if (sIsFirstAppLaunch == null) {
            try {
                SharedPreferences mixpanelPreferences = mMixpanelPreferences.get();
                boolean hasLaunched = mixpanelPreferences.getBoolean("has_launched", false);
                if (hasLaunched) {
                    sIsFirstAppLaunch = false;
                } else {
                    sIsFirstAppLaunch = !dbExists;
                }
            } catch (ExecutionException e) {
                sIsFirstAppLaunch = false;
            } catch (InterruptedException e) {
                sIsFirstAppLaunch = false;
            }
        }

        return sIsFirstAppLaunch;
    }

    public synchronized void setHasLaunched() {
        try {
            SharedPreferences.Editor mixpanelPreferencesEditor = mMixpanelPreferences.get().edit();
            mixpanelPreferencesEditor.putBoolean("has_launched", true);
            writeEdits(mixpanelPreferencesEditor);
        } catch (ExecutionException e) {
            MPLog.e(LOGTAG, "Couldn't write internal Mixpanel shared preferences.", e.getCause());
        } catch (InterruptedException e) {
            MPLog.e(LOGTAG, "Couldn't write internal Mixpanel shared preferences.", e);
        }
    }

    public synchronized HashSet<Integer> getSeenCampaignIds() {
        HashSet<Integer> campaignIds = new HashSet<>();
        try {
            SharedPreferences mpPrefs = mLoadStoredPreferences.get();
            String seenIds = mpPrefs.getString("seen_campaign_ids", "");
            StringTokenizer stTokenizer = new StringTokenizer(seenIds, DELIMITER);
            while (stTokenizer.hasMoreTokens()) {
                campaignIds.add(Integer.valueOf(stTokenizer.nextToken()));
            }
        } catch (ExecutionException e) {
            MPLog.e(LOGTAG, "Couldn't read Mixpanel shared preferences.", e.getCause());
        } catch (InterruptedException e) {
            MPLog.e(LOGTAG, "Couldn't read Mixpanel shared preferences.", e);
        }
        return campaignIds;
    }

    public synchronized void saveCampaignAsSeen(Integer notificationId) {
        try {
            final SharedPreferences prefs = mLoadStoredPreferences.get();
            final SharedPreferences.Editor editor = prefs.edit();
            String campaignIds = prefs.getString("seen_campaign_ids", "");
            editor.putString("seen_campaign_ids", campaignIds + notificationId + DELIMITER);
            writeEdits(editor);
        } catch (final ExecutionException e) {
            MPLog.e(LOGTAG, "Can't write campaign d to shared preferences", e.getCause());
        } catch (final InterruptedException e) {
            MPLog.e(LOGTAG, "Can't write campaign id to shared preferences", e);
        }
    }

    //////////////////////////////////////////////////

    // Must be called from a synchronized setting
    private JSONObject getSuperPropertiesCache() {
        if (null == mSuperPropertiesCache) {
            readSuperProperties();
        }
        return mSuperPropertiesCache;
    }

    // All access should be synchronized on this
    private void readSuperProperties() {
        try {
            final SharedPreferences prefs = mLoadStoredPreferences.get();
            final String props = prefs.getString("super_properties", "{}");
            MPLog.v(LOGTAG, "Loading Super Properties " + props);
            mSuperPropertiesCache = new JSONObject(props);
        } catch (final ExecutionException e) {
            MPLog.e(LOGTAG, "Cannot load superProperties from SharedPreferences.", e.getCause());
        } catch (final InterruptedException e) {
            MPLog.e(LOGTAG, "Cannot load superProperties from SharedPreferences.", e);
        } catch (final JSONException e) {
            MPLog.e(LOGTAG, "Cannot parse stored superProperties");
            storeSuperProperties();
        } finally {
            if (null == mSuperPropertiesCache) {
                mSuperPropertiesCache = new JSONObject();
            }
        }
    }

    // All access should be synchronized on this
    private void readReferrerProperties() {
        mReferrerPropertiesCache = new HashMap<String, String>();

        try {
            final SharedPreferences referrerPrefs = mLoadReferrerPreferences.get();
            referrerPrefs.unregisterOnSharedPreferenceChangeListener(mReferrerChangeListener);
            referrerPrefs.registerOnSharedPreferenceChangeListener(mReferrerChangeListener);

            final Map<String, ?> prefsMap = referrerPrefs.getAll();
            for (final Map.Entry<String, ?> entry : prefsMap.entrySet()) {
                final String prefsName = entry.getKey();
                final Object prefsVal = entry.getValue();
                mReferrerPropertiesCache.put(prefsName, prefsVal.toString());
            }
        } catch (final ExecutionException e) {
            MPLog.e(LOGTAG, "Cannot load referrer properties from shared preferences.", e.getCause());
        } catch (final InterruptedException e) {
            MPLog.e(LOGTAG, "Cannot load referrer properties from shared preferences.", e);
        }
    }

    // All access should be synchronized on this
    private void storeSuperProperties() {
        if (null == mSuperPropertiesCache) {
            MPLog.e(LOGTAG, "storeSuperProperties should not be called with uninitialized superPropertiesCache.");
            return;
        }

        final String props = mSuperPropertiesCache.toString();
        MPLog.v(LOGTAG, "Storing Super Properties " + props);

        try {
            final SharedPreferences prefs = mLoadStoredPreferences.get();
            final SharedPreferences.Editor editor = prefs.edit();
            editor.putString("super_properties", props);
            writeEdits(editor);
        } catch (final ExecutionException e) {
            MPLog.e(LOGTAG, "Cannot store superProperties in shared preferences.", e.getCause());
        } catch (final InterruptedException e) {
            MPLog.e(LOGTAG, "Cannot store superProperties in shared preferences.", e);
        }
    }

    // All access should be synchronized on this
    private void readIdentities() {
        SharedPreferences prefs = null;
        try {
            prefs = mLoadStoredPreferences.get();
        } catch (final ExecutionException e) {
            MPLog.e(LOGTAG, "Cannot read distinct ids from sharedPreferences.", e.getCause());
        } catch (final InterruptedException e) {
            MPLog.e(LOGTAG, "Cannot read distinct ids from sharedPreferences.", e);
        }

        if (null == prefs) {
            return;
        }

        mEventsDistinctId = prefs.getString("events_distinct_id", null);

        if (null == mEventsDistinctId) {
            mEventsDistinctId = UUID.randomUUID().toString();
            writeIdentities();
        }

        mIdentitiesLoaded = true;
    }

    // All access should be synchronized on this
    private void writeIdentities() {
        try {
            final SharedPreferences prefs = mLoadStoredPreferences.get();
            final SharedPreferences.Editor prefsEditor = prefs.edit();

            prefsEditor.putString("events_distinct_id", mEventsDistinctId);
            writeEdits(prefsEditor);
        } catch (final ExecutionException e) {
            MPLog.e(LOGTAG, "Can't write distinct ids to shared preferences.", e.getCause());
        } catch (final InterruptedException e) {
            MPLog.e(LOGTAG, "Can't write distinct ids to shared preferences.", e);
        }
    }

    private static void writeEdits(final SharedPreferences.Editor editor) {
        editor.apply();
    }

    private final Future<SharedPreferences> mLoadStoredPreferences;
    private final Future<SharedPreferences> mLoadReferrerPreferences;
    private final Future<SharedPreferences> mTimeEventsPreferences;
    private final Future<SharedPreferences> mMixpanelPreferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener mReferrerChangeListener;
    private JSONObject mSuperPropertiesCache;
    private Map<String, String> mReferrerPropertiesCache;
    private boolean mIdentitiesLoaded;
    private String mEventsDistinctId;
    private static Integer sPreviousVersionCode;
    private static Boolean sIsFirstAppLaunch;

    private static boolean sReferrerPrefsDirty = true;
    private static final Object sReferrerPrefsLock = new Object();
    private static final String DELIMITER = ",";
    private static final String LOGTAG = "MixpanelAPI.PIdentity";
}
