/**
 * This package contains the interface to Alooma that you can use from your
 * Android apps. You can use Alooma to send events.
 *
 * The primary interface to Alooma services is in {@link com.alooma.android.mpmetrics.AloomaAPI}.
 * At it's simplest, you can send events with
 * <pre>
 * {@code
 *
 * AloomaAPI alooma = AloomaAPI.getInstance(context, ALOOMA_TOKEN);
 * alooma.track("Library integrated", null);
 *
 * }
 * </pre>
 *
 * In addition to this reference documentation, you can also see our overview
 * and getting started documentation at
 * <a href="https://support.alooma.com/hc/en-us/articles/214019489-Android-SDK-integration" target="_blank"
 *    >https://support.alooma.com/hc/en-us/articles/214019489-Android-SDK-integration</a>
 *
 */
package com.alooma.android.mpmetrics;