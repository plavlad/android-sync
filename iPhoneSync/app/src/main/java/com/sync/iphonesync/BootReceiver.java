package com.sync.iphonesync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.bluetooth.BluetoothAdapter;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "iPhoneSync";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : "";
        Log.i(TAG, "BootReceiver received action: " + action);

        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state != BluetoothAdapter.STATE_ON) {
                return;
            }
        }

        Intent serviceIntent = new Intent(context, SyncService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.i(TAG, "SyncService started from BootReceiver.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start SyncService from BootReceiver: " + e.getMessage());
        }
    }
}
