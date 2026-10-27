#!/system/bin/sh
MODDIR=${0%/*}

# Ждем полной загрузки Android
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 3
done

# Даем системе еще 5 секунд на стабилизацию Bluetooth стека
sleep 5

# Добавляем в белый список энергосбережения (Doze whitelist)
dumpsys deviceidle whitelist +com.sync.iphonesync

# Разрешаем NotificationListener для управления медиа
cmd notification allow_listener com.sync.iphonesync/com.sync.iphonesync.MediaListener

# Предоставляем Bluetooth разрешения
pm grant com.sync.iphonesync android.permission.BLUETOOTH_CONNECT 2>/dev/null
pm grant com.sync.iphonesync android.permission.BLUETOOTH_SCAN 2>/dev/null
pm grant com.sync.iphonesync android.permission.ACCESS_FINE_LOCATION 2>/dev/null
pm grant com.sync.iphonesync android.permission.POST_NOTIFICATIONS 2>/dev/null

# Запускаем фоновую службу
am start-foreground-service -n com.sync.iphonesync/.SyncService
