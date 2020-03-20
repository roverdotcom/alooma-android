package com.github.aloomaio.androidsdk.aloomametrics;

import org.json.JSONObject;

public class AnalyticsEvent {
    public AnalyticsEvent(String eventName, JSONObject properties, String token) {
        this.eventName = eventName;
        this.properties = properties;
        this.token = token;
    }

    public String getEventName() {
        return eventName;
    }

    public JSONObject getProperties() {
        return properties;
    }

    public String getToken() {
        return token;
    }

    private final String eventName;
    private final JSONObject properties;
    private final String token;
}
