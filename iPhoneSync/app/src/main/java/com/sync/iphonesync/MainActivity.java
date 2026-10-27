package com.sync.iphonesync;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

public class MainActivity extends Activity {
    private TextView statusBadge;
    private TextView macText;
    private TextView logTextView;
    private ScrollView logScrollView;

    private BroadcastReceiver updateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (SyncService.ACTION_STATUS_UPDATE.equals(intent.getAction())) {
                String logMsg = intent.getStringExtra(SyncService.EXTRA_LOG);
                String status = intent.getStringExtra(SyncService.EXTRA_STATUS);
                
                if (status != null) {
                    updateStatusUI("CONNECTED".equals(status));
                }
                if (logMsg != null) {
                    appendLog(logMsg);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Ensure SyncService is running
        Intent sIntent = new Intent(this, SyncService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(sIntent);
        } else {
            startService(sIntent);
        }

        buildUI();
    }

    private void buildUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#121212"));
        root.setPadding(35, 35, 35, 35);

        // Header Title
        TextView title = new TextView(this);
        title.setText("iPhone Continuity Sync");
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Автоматическая пауза видео при звонках на iPhone (BLE ANCS)");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.parseColor("#888888"));
        subtitle.setPadding(0, 5, 0, 25);
        root.addView(subtitle);

        // Status Card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(30, 25, 30, 25);
        
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#1E1E1E"));
        cardBg.setCornerRadius(20);
        cardBg.setStroke(2, Color.parseColor("#333333"));
        card.setBackground(cardBg);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText("Статус связи: ");
        label.setTextColor(Color.parseColor("#CCCCCC"));
        label.setTextSize(16);
        row1.addView(label);

        statusBadge = new TextView(this);
        statusBadge.setText(" ПОДКЛЮЧЕНИЕ... ");
        statusBadge.setTextSize(14);
        statusBadge.setTypeface(null, Typeface.BOLD);
        statusBadge.setTextColor(Color.WHITE);
        statusBadge.setPadding(15, 6, 15, 6);
        
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.parseColor("#555555"));
        badgeBg.setCornerRadius(12);
        statusBadge.setBackground(badgeBg);
        row1.addView(statusBadge);

        card.addView(row1);

        SharedPreferences sp = getSharedPreferences(SyncService.PREF_NAME, MODE_PRIVATE);
        String mac = sp.getString(SyncService.PREF_TARGET_MAC, SyncService.DEFAULT_MAC);

        macText = new TextView(this);
        macText.setText("iPhone (Владислав): " + mac);
        macText.setTextColor(Color.parseColor("#AAAAAA"));
        macText.setTextSize(13);
        macText.setPadding(0, 15, 0, 0);
        card.addView(macText);

        root.addView(card);

        // Control Buttons
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, 25, 0, 20);

        Button btnReconnect = createButton("🔄 Переподключить", "#2A2A2A", "#FFFFFF");
        btnReconnect.setOnClickListener(v -> {
            if (SyncService.instance != null) {
                SyncService.instance.triggerReconnect();
            } else {
                startService(new Intent(this, SyncService.class));
            }
        });
        btnRow.addView(btnReconnect);

        Button btnTestPause = createButton("⏸ Тест Пауза", "#3A2A2A", "#FF8888");
        btnTestPause.setOnClickListener(v -> {
            if (SyncService.instance != null) {
                SyncService.instance.setCallState(true, "Ручной тест");
            }
        });
        btnRow.addView(btnTestPause);

        Button btnTestPlay = createButton("▶ Тест Play", "#2A3A2A", "#88FF88");
        btnTestPlay.setOnClickListener(v -> {
            if (SyncService.instance != null) {
                SyncService.instance.setCallState(false, "Ручной тест");
            }
        });
        btnRow.addView(btnTestPlay);

        root.addView(btnRow);

        // Console Log View
        TextView logTitle = new TextView(this);
        logTitle.setText("Журнал событий:");
        logTitle.setTextColor(Color.parseColor("#888888"));
        logTitle.setTextSize(13);
        logTitle.setPadding(0, 10, 0, 8);
        root.addView(logTitle);

        logScrollView = new ScrollView(this);
        logScrollView.setBackgroundColor(Color.parseColor("#0A0A0A"));
        
        GradientDrawable logBg = new GradientDrawable();
        logBg.setColor(Color.parseColor("#0A0A0A"));
        logBg.setCornerRadius(16);
        logBg.setStroke(1, Color.parseColor("#222222"));
        logScrollView.setBackground(logBg);
        logScrollView.setPadding(20, 20, 20, 20);

        logTextView = new TextView(this);
        logTextView.setTextSize(12);
        logTextView.setTextColor(Color.parseColor("#A0FFA0"));
        logTextView.setTypeface(Typeface.MONOSPACE);
        logScrollView.addView(logTextView);

        root.addView(logScrollView);

        setContentView(root);

        // Initial check
        if (SyncService.instance != null) {
            updateStatusUI(SyncService.instance.isConnected());
        }
    }

    private Button createButton(String text, String bgColor, String textColor) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.parseColor(textColor));
        btn.setTextSize(13);
        
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(bgColor));
        bg.setCornerRadius(14);
        btn.setBackground(bg);
        
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        lp.setMargins(6, 0, 6, 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void updateStatusUI(boolean connected) {
        runOnUiThread(() -> {
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setCornerRadius(12);
            if (connected) {
                statusBadge.setText(" ● ПОДКЛЮЧЕНО ");
                badgeBg.setColor(Color.parseColor("#1B5E20"));
                statusBadge.setTextColor(Color.parseColor("#A5D6A7"));
            } else {
                statusBadge.setText(" ○ ОЖИДАНИЕ... ");
                badgeBg.setColor(Color.parseColor("#B71C1C"));
                statusBadge.setTextColor(Color.parseColor("#EF9A9A"));
            }
            statusBadge.setBackground(badgeBg);
        });
    }

    private void appendLog(String line) {
        runOnUiThread(() -> {
            if (logTextView != null) {
                logTextView.append(line + "\n");
                logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(SyncService.ACTION_STATUS_UPDATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }

        if (SyncService.instance != null) {
            updateStatusUI(SyncService.instance.isConnected());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(updateReceiver);
        } catch (Exception ignored) {}
    }
}
