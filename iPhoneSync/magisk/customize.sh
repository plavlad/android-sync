ui_print "- Установка iPhone Continuity Call Sync..."
ui_print "- Копирование системного приложения в priv-app..."

set_perm_recursive $MODPATH 0 0 0755 0644
set_perm $MODPATH/system/priv-app/IPhoneSync/IPhoneSync.apk 0 0 0644
set_perm $MODPATH/service.sh 0 0 0755

ui_print "- Модуль успешно установлен!"
