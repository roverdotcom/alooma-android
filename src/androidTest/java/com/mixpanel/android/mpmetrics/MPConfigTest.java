package com.mixpanel.android.mpmetrics;

import android.os.Bundle;
import android.test.AndroidTestCase;

public class MPConfigTest extends AndroidTestCase {

    public static final String TOKEN = "TOKEN";

    private MPConfig mpConfig(final Bundle metaData) {
        return new MPConfig(metaData, getContext());
    }

    private MixpanelAPI mixpanelApi(final MPConfig config) {
        return new MixpanelAPI(getContext(), new TestUtils.EmptyPreferences(getContext()), TOKEN, config);
    }
}
