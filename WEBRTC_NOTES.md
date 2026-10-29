# Voice & Video Calls

برای تماس صوتی و تصویری واقعی در نسخه بومی از کتابخانه رسمی Google WebRTC استفاده می‌شود.

کلاس `VoiceCallManager` فعلاً مدیریت Audio Focus و وضعیت تماس را انجام می‌دهد.
سیگنالینگ از طریق همان MQTT انجام می‌شود (offer / answer / ice).

مراحل بعدی برای فعال‌سازی کامل:
1. اضافه کردن dependency WebRTC
2. ایجاد PeerConnectionFactory
3. ساخت local MediaStream (audio + video)
4. رد و بدل SDP و ICE از طریق MQTT
5. نمایش SurfaceViewRenderer برای تصویر
