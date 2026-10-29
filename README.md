# پیام‌رسان آزاد | Mesh Messenger

اپ کاملاً بومی اندروید (Kotlin + Jetpack Compose)

## ویژگی‌های فعلی

- چت متنی peer-to-peer روی بروکرهای عمومی MQTT
- لیست افراد آنلاین زنده
- ساخت **گروه**
- ساخت **کانال**
- ساخت **ربات** (اسکلت)
- تم تاریک/روشن (سیستمی)
- معماری تمیز (data / ui)
- آماده برای صدا و تصویر (WebRTC در مراحل بعد)
- GitHub Actions برای بیلد خودکار APK

## بیلد

```bash
./gradlew assembleDebug
```

APK در مسیر زیر ساخته می‌شود:
`app/build/outputs/apk/debug/app-debug.apk`

## پکیج‌نیم

`com.mesh.messenger`

## سرورهای MQTT

- broker.emqx.io
- broker-cn.emqx.io
- broker.hivemq.com
- test.mosquitto.org
