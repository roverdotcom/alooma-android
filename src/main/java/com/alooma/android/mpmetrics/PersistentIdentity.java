package com.alooma.android.mpmetrics;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.json.JSONException;
import org.json.JSONObject;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import com.alooma.android.util.ALLog;

// In order to use writeEdits, we have to suppress the linter's check for commit()/apply()
@SuppressLint("CommitPrefEdits")
/* package */ class PersistentIdentity {

    public PersistentIdentity(Future<SharedPreferences> storedPreferences, Future<SharedPreferences> timeEventsPreferences, Future<SharedPreferences> aloomaPreferences) {
        mLoadStoredPreferences = storedPreferences;
        mTimeEventsPreferences = timeEventsPreferences;
        mAloomaPreferences = aloomaPreferences;
        mSuperPropertiesCache = null;
        mIdentitiesLoaded = false;
    }

    public synchronized void addSuperPropertiesToObject(JSONObject ob) {
        final JSONObject superProperties = this.getSuperPropertiesCache();
        final Iterator<?> superIter = superProperties.keys();
        while (superIter.hasNext()) {
            final String key = (String) superIter.next();

            try {
                ob.put(key, superProperties.get(key));
            } catch (JSONException e) {
                ALLog.e(LOGTAG, "Object read from one JSON Object cannot be written to another", e);
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
            ALLog.e(LOGTAG, "Can't copy from one JSONObject to another", e);
            return;
        }

        final JSONObject replacementCache = updates.update(copy);
        if (null == replacementCache) {
            ALLog.w(LOGTAG, "An update to Alooma's super properties returned null, and will have no effect.");
            return;
        }

        mSuperPropertiesCache = replacementCache;
        storeSuperProperties();
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
                ALLog.e(LOGTAG, "Exception registering super property.", e);
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
                    ALLog.e(LOGTAG, "Exception registering super property.", e);
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
            SharedPreferences prefs = mAloomaPreferences.get();
            firstLaunch = prefs.getBoolean(token, false);
        }  catch (final ExecutionException e) {
            ALLog.e(LOGTAG, "Couldn't read internal Alooma shared preferences.", e.getCause());
        } catch (final InterruptedException e) {
            ALLog.e(LOGTAG, "Couldn't read internal Alooma from shared preferences.", e);
        }
        return firstLaunch;
    }

    public synchronized void setIsIntegrated(String token) {
        try {
            SharedPreferences.Editor aloomaEditor = mAloomaPreferences.get().edit();
            aloomaEditor.putBoolean(token, true);
            writeEdits(aloomaEditor);
        } catch (ExecutionException e) {
            ALLog.e(LOGTAG, "Couldn't write internal Alooma shared preferences.", e.getCause());
        } catch (InterruptedException e) {
            ALLog.e(LOGTAG, "Couldn't write internal Alooma from shared preferences.", e);
        }
    }

    public synchronized boolean isNewVersion(String versionCode) {
        if (versionCode == null) {
            return false;
        }

        Integer version = Integer.valueOf(versionCode);
        try {
            if (sPreviousVersionCode == null) {
                SharedPreferences aloomaPreferences = mAloomaPreferences.get();
                sPreviousVersionCode = aloomaPreferences.getInt("latest_version_code", -1);
                if (sPreviousVersionCode == -1) {
                    sPreviousVersionCode = version;
                    SharedPreferences.Editor aloomaPreferencesEditor = mAloomaPreferences.get().edit();
                    aloomaPreferencesEditor.putInt("latest_version_code", version);
                    writeEdits(aloomaPreferencesEditor);
                }
            }

            if (sPreviousVersionCode.intValue() < version.intValue()) {
                SharedPreferences.Editor aloomaPreferencesEditor = mAloomaPreferences.get().edit();
                aloomaPreferencesEditor.putInt("latest_version_code", version);
                writeEdits(aloomaPreferencesEditor);
                return true;
            }
        } catch (ExecutionException e) {
            ALLog.e(LOGTAG, "Couldn't write internal Alooma shared preferences.", e.getCause());
        } catch (InterruptedException e) {
            ALLog.e(LOGTAG, "Couldn't write internal Alooma from shared preferences.", e);
        }

        return false;
    }

    public synchronized boolean isFirstLaunch(boolean dbExists) {
        if (sIsFirstAppLaunch == null) {
            try {
                SharedPreferences aloomaPreferences = mAloomaPreferences.get();
                boolean hasLaunched = aloomaPreferences.getBoolean("has_launched", false);
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
            SharedPreferences.Editor aloomaPreferencesEditor = mAloomaPreferences.get().edit();
            aloomaPreferencesEditor.putBoolean("has_launched", true);
            writeEdits(aloomaPreferencesEditor);
        } catch (ExecutionException e) {
            ALLog.e(LOGTAG, "Couldn't write internal Alooma shared preferences.", e.getCause());
        } catch (InterruptedException e) {
            ALLog.e(LOGTAG, "Couldn't write internal Alooma shared preferences.", e);
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
            ALLog.v(LOGTAG, "Loading Super Properties " + props);
            mSuperPropertiesCache = new JSONObject(props);
        } catch (final ExecutionException e) {
            ALLog.e(LOGTAG, "Cannot load superProperties from SharedPreferences.", e.getCause());
        } catch (final InterruptedException e) {
            ALLog.e(LOGTAG, "Cannot load superProperties from SharedPreferences.", e);
        } catch (final JSONException e) {
            ALLog.e(LOGTAG, "Cannot parse stored superProperties");
            storeSuperProperties();
        } finally {
            if (null == mSuperPropertiesCache) {
                mSuperPropertiesCache = new JSONObject();
            }
        }
    }

    // All access should be synchronized on this
    private void storeSuperProperties() {
        if (null == mSuperPropertiesCache) {
            ALLog.e(LOGTAG, "storeSuperProperties should not be called with uninitialized superPropertiesCache.");
            return;
        }

        final String props = mSuperPropertiesCache.toString();
        ALLog.v(LOGTAG, "Storing Super Properties " + props);

        try {
            final SharedPreferences prefs = mLoadStoredPreferences.get();
            final SharedPreferences.Editor editor = prefs.edit();
            editor.putString("super_properties", props);
            writeEdits(editor);
        } catch (final ExecutionException e) {
            ALLog.e(LOGTAG, "Cannot store superProperties in shared preferences.", e.getCause());
        } catch (final InterruptedException e) {
            ALLog.e(LOGTAG, "Cannot store superProperties in shared preferences.", e);
        }
    }

    // All access should be synchronized on this
    private void readIdentities() {
        SharedPreferences prefs = null;
        try {
            prefs = mLoadStoredPreferences.get();
        } catch (final ExecutionException e) {
            ALLog.e(LOGTAG, "Cannot read distinct ids from sharedPreferences.", e.getCause());
        } catch (final InterruptedException e) {
            ALLog.e(LOGTAG, "Cannot read distinct ids from sharedPreferences.", e);
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
            ALLog.e(LOGTAG, "Can't write distinct ids to shared preferences.", e.getCause());
        } catch (final InterruptedException e) {
            ALLog.e(LOGTAG, "Can't write distinct ids to shared preferences.", e);
        }
    }

    private static void writeEdits(final SharedPreferences.Editor editor) {
        editor.apply();
    }

    private final Future<SharedPreferences> mLoadStoredPreferences;
    private final Future<SharedPreferences> mTimeEventsPreferences;
    private final Future<SharedPreferences> mAloomaPreferences;
    private JSONObject mSuperPropertiesCache;
    private boolean mIdentitiesLoaded;
    private String mEventsDistinctId;
    private static Integer sPreviousVersionCode;
    private static Boolean sIsFirstAppLaunch;

    private static final String LOGTAG = "AloomaAPI.PIdentity";
}
