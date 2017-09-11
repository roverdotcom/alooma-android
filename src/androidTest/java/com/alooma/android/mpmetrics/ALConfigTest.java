package com.alooma.android.mpmetrics;

import android.os.Bundle;
import android.test.AndroidTestCase;

public class ALConfigTest extends AndroidTestCase {

    public static final String TOKEN = "TOKEN";

    private ALConfig mpConfig(final Bundle metaData) {
        return new ALConfig(metaData, getContext());
    }

    private AloomaAPI aloomaApi(final ALConfig config) {
        return new AloomaAPI(getContext(), new TestUtils.EmptyPreferences(getContext()), TOKEN, config);
    }
}
