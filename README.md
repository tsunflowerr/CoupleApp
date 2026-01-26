# 💕 CoupleApp

A comprehensive Android app designed exclusively for couples to connect, share moments, and track their relationship journey in meaningful and fun ways.

## 📥 Download

**Latest Version: v1.0**

👉 **[Download APK](https://github.com/tsunflowerr/CoupleApp/releases)** 👈

Or scan this QR code to download directly on your phone.

> **Note**: Enable "Install from Unknown Sources" in your device settings before installing.

---

## 📱 Key Features

## 📱 Key Features

### 🔗 Partner Hub - Connection Center
- **Link with Partner**: Connect with your loved one via unique PIN code
- **Realtime Chat**: Direct messaging with beautiful, intuitive interface
- **Q&A**: Answer questions together to understand each other better

### 📸 Locket - Instant Photo Sharing
- Send instant selfies directly to your partner's home screen
- Support multiple content types: Photos, Emojis, Drawings, Text messages
- Locket history with view and delete capabilities
- Home screen widget for instant access

### 💌 Missing - Express Your Feelings
- Send "I miss you" with a single tap
- Track how many times you both miss each other
- Streak system to encourage daily connection
- Widget for quick missing messages

### 📍 Distance - Stay Close
- **Realtime distance tracking** between you two
- **Location history**: Review your movement patterns throughout the day
- **Shared Places**: Mark important locations (Home, Work, Date spots)
- **Place Photos**: Save memories tied to specific locations
- **Colocation tracking**: Auto-detect when you're together
- **Background tracking**: Monitor location even when app is closed
- Widget displaying current distance and locations

### 😴 Sleep Tracker - Better Rest Together
- Integrate with Health Connect for sleep data sync
- Compare sleep patterns between partners
- Set sleep goals and track progress
- Get smart bedtime recommendations
- Widget showing sleep status

### 📅 Calendar - Your Shared Timeline
- Mark important dates (First date, First kiss, Anniversary)
- Daily horoscope based on zodiac signs
- Countdown to special events
- Customize with personal emojis

### 🌸 Garden - Love Garden
- Grow and nurture plants based on your interactions
- Every activity (chat, locket, missing) waters your garden
- Watch your shared garden flourish over time

### 🏆 Quest - Relationship Challenges
- Complete couple challenges to earn rewards
- Accumulate points to unlock new features

### 🎁 Store - In-App Shop
- Purchase unique items with earned points
- Customize profiles and app appearance

### 📷 Moments - Photo Memories
- Store and manage shared photo albums
- Organize photos by location and time

## 🛠️ Technology Stack

## 🛠️ Technology Stack

- **Framework**: Jetpack Compose (Modern, declarative UI)
- **Language**: Kotlin
- **Backend**: Firebase (Firestore, Storage, Auth, FCM)
- **Architecture**: MVVM + Repository Pattern
- **DI**: Dagger Hilt
- **Maps**: Google Maps SDK
- **Health**: Health Connect API
- **Location**: Fused Location Provider + Background Service
- **Widgets**: AppWidget with RemoteViews
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 36

## 🚀 Installation & Setup

### Requirements
- Android Studio Iguana or newer
- JDK 11
- Android SDK 36
- Firebase Project

### Setup Steps

1. **Clone repository**
```bash
git clone https://github.com/tsunflowerr/CoupleApp.git
cd CoupleApp
```

2. **Create Firebase Project**
   - Go to [Firebase Console](https://console.firebase.google.com/)
   - Create a new project
   - Add Android app with package name: `com.example.coupleapp`
   - Download `google-services.json` and place it in `app/`

3. **Enable Firebase Services**
   - **Authentication**: Phone, Email/Password
   - **Firestore Database**: Create database
   - **Storage**: Create storage bucket
   - **Cloud Messaging**: Enable FCM
   - **Realtime Database**: Create database (for presence)

4. **Deploy Firestore Rules**
```bash
firebase deploy --only firestore:rules
firebase deploy --only storage:rules
firebase deploy --only database:rules
```

5. **Google Maps API Key**
   - Go to [Google Cloud Console](https://console.cloud.google.com/)
   - Enable Maps SDK for Android
   - Create API Key with appropriate restrictions
   - Create `local.properties` file in root directory:
```properties
sdk.dir=/path/to/Android/sdk
MAPS_API_KEY=YOUR_GOOGLE_MAPS_API_KEY_HERE
```

6. **Build & Run**
```bash
./gradlew assembleDebug
```

Or open project in Android Studio and press Run (Shift+F10)

## 📦 Build APK

### Debug APK
```bash
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release APK (Signed)
1. Generate keystore:
```bash
keytool -genkey -v -keystore coupleapp.keystore -alias coupleapp -keyalg RSA -keysize 2048 -validity 10000
```

2. Add to `local.properties`:
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

The app includes 4 home screen widgets:
- **Sleep Widget**: Display sleep status of both partners
- **Locket Widget**: Show latest locket photo
- **Missing Widget**: Quick "I miss you" button
- **Location Widget**: Display realtime distance

## 🔐 Required Permissions

- **Camera**: Take locket photos
- **Storage**: Access gallery
- **Location**: Calculate distance and shared places
  - Fine Location (Precise GPS)
  - Background Location (Track when app is closed)
- **Notifications**: Receive notifications from partner
- **Health Connect**: Sync sleep data
- **Activity Recognition**: Google Sleep API

## 🏗️ Project Structure

## 🏗️ Project Structure

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
│   ├── screens/        # Main screens
│   ├── components/     # Reusable components
│   └── theme/          # Theme & styling
├── viewmodel/          # ViewModels
├── widget/             # App Widgets
├── worker/             # Background workers
├── receiver/           # Broadcast receivers
├── navigation/         # Navigation setup
└── util/               # Utilities

```

## 🤝 Contributing

Contributions are welcome! Please:
1. Fork the repository
2. Create a new branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## 📄 License

This project is developed for educational and personal purposes.

## 👥 Authors

Developed by UET-VNU students

## 📞 Contact & Support

- **GitHub Issues**: [Report bugs here](https://github.com/tsunflowerr/CoupleApp/issues)
- **Discussions**: [Join discussions](https://github.com/tsunflowerr/CoupleApp/discussions)
- **Email**: Contact via GitHub profile

## 🌟 Screenshots

> Coming soon! We'll add app screenshots in the next update.

## 🔄 Version History

### v1.0 (Current)
- Initial release
- All core features implemented
- Partner Hub, Locket, Missing, Distance, Sleep Tracker
- Calendar, Garden, Quest, Store, Moments
- 4 home screen widgets
- Background location & sleep tracking
- Firebase integration

---

💝 Made with love for couples everywhere

**Star ⭐ this repository if you find it helpful!**

