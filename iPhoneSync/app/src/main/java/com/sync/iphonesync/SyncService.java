package com.sync.iphonesync;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.*;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class SyncService extends Service {
    public static final String TAG = "iPhoneSync";
    public static final String ACTION_STATUS_UPDATE = "com.sync.iphonesync.STATUS_UPDATE";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_LOG = "log";

    public static final String PREF_NAME = "iphonesync_prefs";
    public static final String PREF_TARGET_MAC = "target_mac";
    public static final String DEFAULT_MAC = "10:7D:C8:02:90:27";

    public static final UUID ANCS_SERVICE_UUID = UUID.fromString("7905f431-b5ce-4e99-a40f-4b1e122d00d0");
    public static final UUID NOTIF_SOURCE_UUID = UUID.fromString("9fbf120d-6301-42d9-8c58-25e699a21dbd");
    public static final UUID CONTROL_POINT_UUID = UUID.fromString("69d1d8f3-45e1-49a8-9821-9bbdf552d88f");
    public static final UUID DATA_SOURCE_UUID   = UUID.fromString("22eac6e9-24d6-4bb5-be44-b36ace7c7bfb");
    public static final UUID CCCD_UUID          = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String CHANNEL_ID = "iphonesync_channel";
    private static final int NOTIF_ID = 1001;

    public static SyncService instance;

    private Handler handler;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic controlPointChar;

    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private MediaSessionManager mediaSessionManager;
    private ComponentName listenerComponent;

    private boolean isConnected = false;
    private boolean isCallActive = false;
    private boolean wasPlayingBeforeCall = false;
    private final List<String> packagesToResume = new ArrayList<>();
    private final Map<Integer, Integer> activeCallUids = new HashMap<>();

    private Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isConnected) {
                log("Попытка переподключения к iPhone...");
                connectToDevice();
            }
        }
    };

    public boolean isConnected() {
        return isConnected;
    }

    public boolean isCallActive() {
        return isCallActive;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        handler = new Handler(Looper.getMainLooper());
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        listenerComponent = new ComponentName(this, MediaListener.class);

        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification("Поиск iPhone..."));

        log("Служба синхронизации запущена.");
        connectToDevice();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isConnected && bluetoothGatt == null) {
            connectToDevice();
        }
        return START_STICKY;
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        Intent it = new Intent(ACTION_STATUS_UPDATE);
        it.putExtra(EXTRA_LOG, msg);
        it.putExtra(EXTRA_STATUS, isConnected ? "CONNECTED" : "DISCONNECTED");
        sendBroadcast(it);
    }

    public void triggerReconnect() {
        log("Запрошено ручное переподключение.");
        disconnectGatt();
        handler.removeCallbacks(reconnectRunnable);
        connectToDevice();
    }

    private String getTargetMac() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        return sp.getString(PREF_TARGET_MAC, DEFAULT_MAC);
    }

    private void connectToDevice() {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null) {
            log("BluetoothManager недоступен.");
            scheduleReconnect();
            return;
        }
        BluetoothAdapter adapter = bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            log("Bluetooth выключен.");
            updateNotification("Bluetooth выключен");
            scheduleReconnect();
            return;
        }

        String targetMac = getTargetMac();
        BluetoothDevice targetDevice = null;
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded != null) {
            for (BluetoothDevice d : bonded) {
                if (targetMac.equalsIgnoreCase(d.getAddress())) {
                    targetDevice = d;
                    break;
                }
            }
        }

        if (targetDevice == null) {
            try {
                targetDevice = adapter.getRemoteDevice(targetMac);
            } catch (Exception e) {
                log("Некорректный MAC адрес: " + targetMac);
                scheduleReconnect();
                return;
            }
        }

        log("Подключение к " + targetDevice.getName() + " [" + targetMac + "] (BLE / Без звука)...");
        updateNotification("Подключение к " + targetDevice.getName() + "...");
        
        try {
            bluetoothGatt = targetDevice.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            log("Ошибка вызова connectGatt: " + e.getMessage());
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        handler.removeCallbacks(reconnectRunnable);
        handler.postDelayed(reconnectRunnable, 4000);
    }

    private void disconnectGatt() {
        isConnected = false;
        if (bluetoothGatt != null) {
            try {
                bluetoothGatt.disconnect();
                bluetoothGatt.close();
            } catch (Exception ignored) {}
            bluetoothGatt = null;
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isConnected = true;
                log("Подключено к iPhone по BLE!");
                updateNotification("Подключено (Синхронизация активна)");
                handler.removeCallbacks(reconnectRunnable);
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isConnected = false;
                log("Соединение с iPhone разорвано (код " + status + ").");
                updateNotification("Отключено. Ожидание iPhone...");
                disconnectGatt();
                scheduleReconnect();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothGattService ancsService = gatt.getService(ANCS_SERVICE_UUID);
                if (ancsService != null) {
                    controlPointChar = ancsService.getCharacteristic(CONTROL_POINT_UUID);
                    subscribeData(gatt, ancsService);
                } else {
                    log("ANCS сервис не найден в GATT таблице.");
                }
            }
        }

        private void subscribeData(final BluetoothGatt gatt, final BluetoothGattService service) {
            BluetoothGattCharacteristic dataChar = service.getCharacteristic(DATA_SOURCE_UUID);
            if (dataChar != null) {
                gatt.setCharacteristicNotification(dataChar, true);
                BluetoothGattDescriptor desc = dataChar.getDescriptor(CCCD_UUID);
                if (desc != null) {
                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(desc);
                }
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            UUID charUuid = descriptor.getCharacteristic().getUuid();
            if (DATA_SOURCE_UUID.equals(charUuid)) {
                BluetoothGattService service = gatt.getService(ANCS_SERVICE_UUID);
                if (service != null) {
                    BluetoothGattCharacteristic notifChar = service.getCharacteristic(NOTIF_SOURCE_UUID);
                    if (notifChar != null) {
                        gatt.setCharacteristicNotification(notifChar, true);
                        BluetoothGattDescriptor desc = notifChar.getDescriptor(CCCD_UUID);
                        if (desc != null) {
                            desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            gatt.writeDescriptor(desc);
                        }
                    }
                }
            } else if (NOTIF_SOURCE_UUID.equals(charUuid)) {
                log("Каналы ANCS активны. Мониторинг вызовов запущен.");
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            UUID uuid = characteristic.getUuid();
            byte[] val = characteristic.getValue();
            if (val == null) return;

            if (NOTIF_SOURCE_UUID.equals(uuid) && val.length >= 8) {
                int eventId = val[0] & 0xFF;    // 0=Added, 1=Modified, 2=Removed
                int eventFlags = val[1] & 0xFF;  // bit 2 (0x04) = PreExisting
                int categoryId = val[2] & 0xFF;  // 1=IncomingCall, 12=Active/InCall

                ByteBuffer bb = ByteBuffer.wrap(val, 4, 4);
                bb.order(ByteOrder.LITTLE_ENDIAN);
                int uid = bb.getInt();

                // Игнорируем архивные уведомления при первом подключении
                if ((eventFlags & 0x04) != 0) {
                    return;
                }

                // 1. Входящий звонок (звенит телефон)
                if (categoryId == 1) {
                    if (eventId == 0) {
                        activeCallUids.put(uid, categoryId);
                        setCallState(true, "Входящий вызов (звонок)");
                    } else if (eventId == 2) {
                        activeCallUids.remove(uid);
                        handler.postDelayed(() -> {
                            if (activeCallUids.isEmpty()) {
                                setCallState(false, "Вызов сброшен");
                            }
                        }, 600);
                    }
                }
                // 2. Активный разговор / Исходящий звонок
                else if (categoryId == 12) {
                    if (eventId == 0) {
                        activeCallUids.put(uid, categoryId);
                        setCallState(true, "Разговор активен");
                    } else if (eventId == 2) {
                        activeCallUids.remove(uid);
                        if (activeCallUids.isEmpty()) {
                            setCallState(false, "Разговор завершен");
                        }
                    }
                }
            }
        }
    };

    public synchronized void setCallState(boolean active, String reason) {
        if (active) {
            if (isCallActive) return;
            isCallActive = true;

            // Запоминаем состояние воспроизведения ДО звонка
            checkPreCallPlaybackState();

            if (wasPlayingBeforeCall) {
                log(">>> [ПАУЗА ВИДЕО] " + reason + " (Медиа воспроизводилось) <<<");
                updateNotification("Звонок активен: Пауза видео");
                pauseAllMedia();
            } else {
                log(">>> [ЗВОНОК] " + reason + " (Видео уже было на паузе — оставляем на паузе) <<<");
                updateNotification("Звонок активен (видео на паузе)");
            }
        } else {
            if (!isCallActive) return;
            isCallActive = false;

            if (wasPlayingBeforeCall) {
                log(">>> [ВОЗОБНОВЛЕНИЕ ВИДЕО] " + reason + " (Возврат к воспроизведению) <<<");
                updateNotification("Подключено (Синхронизация активна)");
                resumeAllMedia();
            } else {
                log(">>> [ЗВОНОК ОКОНЧЕН] " + reason + " (Медиа НЕ возобновляется, т.к. стояло на паузе до звонка) <<<");
                updateNotification("Подключено (Синхронизация активна)");
            }

            wasPlayingBeforeCall = false;
            packagesToResume.clear();
        }
    }

    private void checkPreCallPlaybackState() {
        wasPlayingBeforeCall = false;
        packagesToResume.clear();

        try {
            List<MediaController> controllers = mediaSessionManager.getActiveSessions(listenerComponent);
            if (controllers != null) {
                for (MediaController mc : controllers) {
                    PlaybackState ps = mc.getPlaybackState();
                    if (ps != null) {
                        int state = ps.getState();
                        log("Сессия " + mc.getPackageName() + " перед звонком: state=" + state);
                        if (state == PlaybackState.STATE_PLAYING 
                                || state == PlaybackState.STATE_BUFFERING 
                                || state == PlaybackState.STATE_FAST_FORWARDING 
                                || state == PlaybackState.STATE_REWINDING) {
                            wasPlayingBeforeCall = true;
                            packagesToResume.add(mc.getPackageName());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log("Ошибка проверки MediaSession: " + e.getMessage());
        }

        if (!wasPlayingBeforeCall && audioManager != null && audioManager.isMusicActive()) {
            wasPlayingBeforeCall = true;
            log("AudioManager.isMusicActive() == true (обнаружено активное аудио)");
        }

        log("Состояние до звонка: " + (wasPlayingBeforeCall ? "ИГРАЕТ (будет возобновлено)" : "НА ПАУЗЕ (возобновляться НЕ будет)"));
    }

    private void pauseAllMedia() {
        // 1. Прямое управление через MediaSession (YouTube и другие плееры)
        try {
            List<MediaController> controllers = mediaSessionManager.getActiveSessions(listenerComponent);
            if (controllers != null) {
                for (MediaController mc : controllers) {
                    log("Пауза плеера: " + mc.getPackageName());
                    mc.getTransportControls().pause();
                }
            }
        } catch (Exception e) {
            log("Ошибка MediaSession: " + e.getMessage());
        }

        // 2. Системный голосовой AudioFocus (глушит браузеры и системный звук)
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .build();
            audioManager.requestAudioFocus(focusRequest);
        } catch (Exception e) {
            log("Ошибка AudioFocus: " + e.getMessage());
        }

        // 3. Дополнительная отправка системного события клавиши медиа
        long now = SystemClock.uptimeMillis();
        audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
        audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
    }

    private void resumeAllMedia() {
        // 1. Прямой запуск через MediaSession
        try {
            List<MediaController> controllers = mediaSessionManager.getActiveSessions(listenerComponent);
            if (controllers != null) {
                for (MediaController mc : controllers) {
                    if (packagesToResume.isEmpty() || packagesToResume.contains(mc.getPackageName())) {
                        log("Возобновление плеера: " + mc.getPackageName());
                        mc.getTransportControls().play();
                    }
                }
            }
        } catch (Exception e) {
            log("Ошибка MediaSession: " + e.getMessage());
        }

        // 2. Снятие AudioFocus
        try {
            if (focusRequest != null) {
                audioManager.abandonAudioFocusRequest(focusRequest);
            }
        } catch (Exception e) {
            log("Ошибка abandonAudioFocus: " + e.getMessage());
        }

        // 3. Отправка события клавиши PLAY
        long now = SystemClock.uptimeMillis();
        audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
        audioManager.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(
                CHANNEL_ID,
                "iPhone Sync Service",
                NotificationManager.IMPORTANCE_LOW
            );
            chan.setDescription("Фоновая служба синхронизации звонков с iPhone");
            chan.setShowBadge(false);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(chan);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent notifyIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
            this, 0, notifyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle("iPhone Sync")
               .setContentText(text)
               .setSmallIcon(android.R.drawable.ic_dialog_info)
               .setContentIntent(pi)
               .setOngoing(true);

        return builder.build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIF_ID, buildNotification(text));
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        disconnectGatt();
        handler.removeCallbacks(reconnectRunnable);
        log("Служба синхронизации остановлена.");
    }
}
