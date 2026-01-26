# 💕 CoupleApp

Ứng dụng dành riêng cho các cặp đôi để kết nối, chia sẻ khoảnh khắc và theo dõi cuộc sống chung một cách thú vị và ý nghĩa.

## 📱 Tính năng chính

### 🔗 Partner Hub - Trung tâm kết nối
- **Liên kết đôi**: Kết nối với người yêu qua mã PIN độc nhất
- **Chat realtime**: Trò chuyện trực tiếp với giao diện đẹp mắt
- **Q&A**: Trả lời các câu hỏi để hiểu nhau hơn

### 📸 Locket - Ảnh tức thì
- Gửi ảnh selfie tức thời đến màn hình chính của người yêu
- Hỗ trợ nhiều loại nội dung: Ảnh, Emoji, Vẽ tay, Text
- Lịch sử Locket với khả năng xem lại và xóa
- Widget hiển thị ngay trên màn hình chính

### 💌 Missing - Nhớ nhau
- Gửi "I miss you" bằng một chạm
- Theo dõi số lần nhớ nhau của cả hai
- Streak (chuỗi ngày liên tiếp) để động viên nhớ nhau hàng ngày
- Widget hiển thị trạng thái missing

### 📍 Distance - Khoảng cách
- **Theo dõi khoảng cách realtime** giữa hai người
- **Lịch sử vị trí**: Xem lại hành trình di chuyển trong ngày
- **Shared Places**: Đánh dấu các địa điểm quan trọng (Nhà, Nơi làm việc, Nơi hẹn hò)
- **Place Photos**: Lưu ảnh gắn với địa điểm cụ thể
- **Colocation tracking**: Tự động phát hiện khi ở cùng nhau
- **Background tracking**: Theo dõi vị trí ngay cả khi app đóng
- Widget hiển thị khoảng cách và vị trí

### 😴 Sleep Tracker - Theo dõi giấc ngủ
- Tích hợp Health Connect để đồng bộ dữ liệu giấc ngủ
- So sánh thời gian ngủ của cả hai
- Đặt mục tiêu giấc ngủ
- Gợi ý thời gian đi ngủ lý tưởng
- Widget hiển thị trạng thái giấc ngủ

### 📅 Calendar - Lịch đôi
- Đánh dấu ngày kỷ niệm (First date, First kiss, Anniversary)
- Horoscope hàng ngày dựa trên cung hoàng đạo
- Đếm ngược đến các sự kiện quan trọng
- Cài đặt emoji đại diện cho từng người

### 🌸 Garden - Vườn hoa tình yêu
- Trồng và chăm sóc cây dựa trên mức độ tương tác
- Mỗi hoạt động (chat, locket, missing) sẽ tưới nước cho cây
- Theo dõi sự phát triển của khu vườn chung

### 🏆 Quest - Nhiệm vụ
- Hoàn thành các nhiệm vụ đôi để nhận thưởng
- Tích điểm và mở khóa các tính năng mới

### 🎁 Store - Cửa hàng
- Mua sắm các items độc đáo bằng điểm tích lũy
- Trang trí profile và app theo phong cách riêng

### 📷 Moments - Khoảnh khắc
- Lưu trữ và quản lý album ảnh chung
- Phân loại ảnh theo địa điểm và thời gian

## 🛠️ Công nghệ sử dụng

- **Framework**: Jetpack Compose (UI hiện đại, declarative)
- **Ngôn ngữ**: Kotlin
- **Backend**: Firebase (Firestore, Storage, Auth, FCM)
- **Architecture**: MVVM + Repository Pattern
- **DI**: Dagger Hilt
- **Maps**: Google Maps SDK
- **Health**: Health Connect API
- **Location**: Fused Location Provider + Background Service
- **Widgets**: AppWidget với RemoteViews
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 36

## 🚀 Cài đặt & Chạy

### Yêu cầu
- Android Studio Iguana hoặc mới hơn
- JDK 11
- Android SDK 36
- Firebase Project

### Các bước setup

1. **Clone repository**
```bash
git clone https://github.com/tsunflowerr/CoupleApp.git
cd CoupleApp
```

2. **Tạo Firebase Project**
   - Truy cập [Firebase Console](https://console.firebase.google.com/)
   - Tạo project mới
   - Thêm Android app với package name: `com.example.coupleapp`
   - Download file `google-services.json` và đặt vào `app/`

3. **Enable Firebase Services**
   - **Authentication**: Phone, Email/Password
   - **Firestore Database**: Tạo database
   - **Storage**: Tạo bucket
   - **Cloud Messaging**: Enable FCM
   - **Realtime Database**: Tạo database (cho presence)

4. **Import Firestore Rules**
```bash
firebase deploy --only firestore:rules
firebase deploy --only storage:rules
firebase deploy --only database:rules
```

5. **Google Maps API Key**
   - Truy cập [Google Cloud Console](https://console.cloud.google.com/)
   - Enable Maps SDK for Android
   - Tạo API Key với restrictions phù hợp
   - Tạo file `local.properties` ở thư mục gốc:
```properties
sdk.dir=/path/to/Android/sdk
MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY_HERE
```

6. **Build & Run**
```bash
./gradlew assembleDebug
```

Hoặc mở project trong Android Studio và nhấn Run (Shift+F10)

## 📦 Build APK

### Debug APK
```bash
./gradlew assembleDebug
```
File output: `app/build/outputs/apk/debug/app-debug.apk`

### Release APK (Signed)
1. Tạo keystore:
```bash
keytool -genkey -v -keystore coupleapp.keystore -alias coupleapp -keyalg RSA -keysize 2048 -validity 10000
```

2. Thêm vào `local.properties`:
```properties
KEYSTORE_FILE=../coupleapp.keystore
KEYSTORE_PASSWORD=your_password
KEY_ALIAS=coupleapp
KEY_PASSWORD=your_password
```

3. Build:
```bash
./gradlew assembleRelease
```

## 📱 Widget Support

App hỗ trợ 4 loại widget:
- **Sleep Widget**: Hiển thị trạng thái giấc ngủ của cả hai
- **Locket Widget**: Hiển thị ảnh Locket mới nhất
- **Missing Widget**: Nút gửi "I miss you" nhanh
- **Location Widget**: Hiển thị khoảng cách realtime

## 🔐 Quyền yêu cầu

- **Camera**: Chụp ảnh Locket
- **Storage**: Truy cập gallery
- **Location**: Tính khoảng cách và shared places
  - Fine Location (GPS chính xác)
  - Background Location (Theo dõi khi app đóng)
- **Notifications**: Nhận thông báo từ người yêu
- **Health Connect**: Đồng bộ dữ liệu giấc ngủ
- **Activity Recognition**: Google Sleep API

## 🏗️ Cấu trúc project

```
app/src/main/java/com/example/coupleapp/
├── data/               # Data layer
│   ├── model/          # Data models
│   ├── repository/     # Repositories
│   └── local/          # Room Database
├── service/            # Background services
│   ├── LocationTrackingService.kt
│   ├── UnifiedFCMService.kt
│   └── GeofenceReceiver.kt
├── ui/                 # UI layer
│   ├── screens/        # Màn hình chính
│   ├── components/     # Reusable components
│   └── theme/          # Theme & styling
├── viewmodel/          # ViewModels
├── widget/             # App Widgets
├── worker/             # Background workers
├── receiver/           # Broadcast receivers
├── navigation/         # Navigation setup
└── util/               # Utilities

```

## 🤝 Đóng góp

Mọi đóng góp đều được chào đón! Vui lòng:
1. Fork repository
2. Tạo branch mới (`git checkout -b feature/AmazingFeature`)
3. Commit changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to branch (`git push origin feature/AmazingFeature`)
5. Mở Pull Request

## 📄 License

Project này được phát triển cho mục đích học tập và cá nhân.

## 👥 Tác giả

Được phát triển bởi nhóm sinh viên UET-VNU

## 📞 Liên hệ & Hỗ trợ

- **GitHub Issues**: [Báo lỗi tại đây](https://github.com/tsunflowerr/CoupleApp/issues)
- **Email**: Liên hệ qua GitHub profile

---

💝 Được làm với tình yêu cho các cặp đôi
