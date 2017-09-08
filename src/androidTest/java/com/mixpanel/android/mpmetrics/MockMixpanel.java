package com.mixpanel.android.mpmetrics;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import junit.framework.Assert;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.Future;


@SuppressWarnings("deprecation")
class MockMixpanel extends MixpanelAPI {
    public MockMixpanel(Context context, Future<SharedPreferences> prefsFuture, String testToken) {
        super(context, prefsFuture, testToken);
    }
}
