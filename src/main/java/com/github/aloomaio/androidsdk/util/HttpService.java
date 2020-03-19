package com.github.aloomaio.androidsdk.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import com.github.aloomaio.androidsdk.aloomametrics.AConfig;

import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.message.BasicNameValuePair;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;

public class HttpService implements RemoteService {

    public boolean isOnline(Context context) {
        boolean isOnline;
        try {
            final ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            final NetworkInfo netInfo = cm.getActiveNetworkInfo();
            isOnline = netInfo != null && netInfo.isConnectedOrConnecting();
            if (AConfig.DEBUG) {
                Log.v(LOGTAG, "ConnectivityManager says we " + (isOnline ? "are" : "are not") + " online");
            }
        } catch (final SecurityException e) {
            isOnline = true;
            if (AConfig.DEBUG) {
                Log.v(LOGTAG, "Don't have permission to check connectivity, will assume we are online");
            }
        }
        return isOnline;
    }

    public byte[] getUrls(Context context, String[] urls) {
        if (! isOnline(context)) {
            return null;
        }

        byte[] response = null;
        for (String url : urls) {
            try {
                response = performRequest(url, null, null, ContentType.URL_FORM_ENCODED, null);
                break;
            } catch (final MalformedURLException e) {
                Log.e(LOGTAG, "Cannot interpret " + url + " as a URL.", e);
            } catch (final IOException e) {
                if (AConfig.DEBUG) {
                    Log.v(LOGTAG, "Cannot get " + url + ".", e);
                }
            } catch (final OutOfMemoryError e) {
                Log.e(LOGTAG, "Out of memory when getting to " + url + ".", e);
                break;
            }
        }

        return response;
    }

    public byte[] performRequest(
            String endpointUrl,
            List<NameValuePair> params,
            Map<String, String> headers,
            RemoteService.ContentType contentType,
            String data
    ) throws IOException {
        if (AConfig.DEBUG) {
            Log.v(LOGTAG, "Attempting request to " + endpointUrl);
        }
        byte[] response = null;

        // the while(retries) loop is a workaround for a bug in some Android HttpURLConnection
        // libraries- The underlying library will attempt to reuse stale connections,
        // meaning the second (or every other) attempt to connect fails with an EOFException.
        // Apparently this nasty retry logic is the current state of the workaround art.
        int retries = 0;
        boolean succeeded = false;
        while (retries < 3 && !succeeded) {
            InputStream in = null;
            OutputStream out = null;
            BufferedOutputStream bout = null;
            HttpURLConnection connection = null;

            try {
                final URL url = new URL(endpointUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(10000);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);

                if (headers != null){
                    for (Map.Entry<String, String> header: headers.entrySet()){
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }

                if (contentType == ContentType.URL_FORM_ENCODED && data != null){
                    final String encodedData = Base64Coder.encodeString(data);
                    params.add(new BasicNameValuePair("data", encodedData));

                    final UrlEncodedFormEntity form = new UrlEncodedFormEntity(params, "UTF-8");
                    connection.setFixedLengthStreamingMode((int)form.getContentLength());
                    out = connection.getOutputStream();
                    bout = new BufferedOutputStream(out);
                    form.writeTo(bout);
                } else if (contentType == ContentType.JSON && data != null){
                    connection.setRequestProperty("Content-Type", "application/json; utf-8");
                    connection.setRequestProperty("Accept", "application/json");

                    byte[] input = data.getBytes("UTF-8");
                    connection.setFixedLengthStreamingMode(input.length);

                    out = connection.getOutputStream();
                    bout = new BufferedOutputStream(out);
                    bout.write(input);
                }

                bout.close();
                bout = null;
                out.close();
                out = null;

                in = connection.getInputStream();
                response = slurp(in);
                in.close();
                in = null;
                succeeded = true;
            } catch (final EOFException e) {
                if (AConfig.DEBUG) {
                    Log.d(LOGTAG, "Failure to connect, likely caused by a known issue with Android lib. Retrying.");
                }
                retries = retries + 1;
            } finally {
                if (null != bout)
                    try { bout.close(); } catch (final IOException e) { ; }
                if (null != out)
                    try { out.close(); } catch (final IOException e) { ; }
                if (null != in)
                    try { in.close(); } catch (final IOException e) { ; }
                if (null != connection)
                    connection.disconnect();
            }
        }
        if (AConfig.DEBUG) {
            if (retries >= 3) {
                Log.v(LOGTAG, "Could not connect to Mixpanel service after three retries.");
            }
        }
        return response;
    }

    // Does not close input stream
    private byte[] slurp(final InputStream inputStream)
        throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        int nRead;
        byte[] data = new byte[8192];

        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        buffer.flush();
        return buffer.toByteArray();
    }

    private static final String LOGTAG = "AloomaAPI.ServerMessage";
}
