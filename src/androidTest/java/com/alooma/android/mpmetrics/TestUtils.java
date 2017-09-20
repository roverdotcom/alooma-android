package com.alooma.android.mpmetrics;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Message;

import java.io.UnsupportedEncodingException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TestUtils {
    public static byte[] bytes(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("This is not an android device, or a compatible java. WHO ARE YOU?");
        }
    }

    public static class CleanAloomaAPI extends AloomaAPI {
        public CleanAloomaAPI(final Context context, final String token) {
            super(context, token);
        }

        @Override
        /* package */ boolean sendAppOpen() {
            return false;
        }
    }

    public static class EmptyPreferences implements Future<SharedPreferences> {
        public EmptyPreferences(Context context) {
            mPrefs = context.getSharedPreferences("ALOOMA_TEST_PREFERENCES", Context.MODE_PRIVATE);
            mPrefs.edit().clear().commit();
        }

        @Override
        public boolean cancel(final boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public SharedPreferences get() throws InterruptedException, ExecutionException {
            return mPrefs;
        }

        @Override
        public SharedPreferences get(final long timeout, final TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
        {
            return mPrefs;
        }

        private SharedPreferences mPrefs;
    };

    /**
     * Stub/Mock handler that just runs stuff synchronously
     */
    public static class SynchronousHandler extends Handler {
        @Override
        public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
            dispatchMessage(msg);
            return true;
        }
    }

}
