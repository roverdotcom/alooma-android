package com.alooma.android.mpmetrics;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.Future;


@SuppressWarnings("deprecation")
class MockAlooma extends AloomaAPI {
    public MockAlooma(Context context, Future<SharedPreferences> prefsFuture, String testToken) {
        super(context, prefsFuture, testToken);
    }
}
