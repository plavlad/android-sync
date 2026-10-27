package com.sync.iphonesync;

import android.service.notification.NotificationListenerService;
import android.util.Log;

public class MediaListener extends NotificationListenerService {
    private static final String TAG = "iPhoneSync";
    public static MediaListener instance;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        Log.i(TAG, "MediaListener connected to system framework.");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        instance = null;
        Log.i(TAG, "MediaListener disconnected.");
    }
}
