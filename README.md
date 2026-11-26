# Media Player App (Android)

Applicazione Android nativa sviluppata in **Kotlin** con **Jetpack Compose (Material 3)** e **Media3**.

## Stack Tecnologico

- **UI**: Jetpack Compose + Material 3
- **Player**: AndroidX Media3 (ExoPlayer)
- **Persistenza**: Room Database + DataStore Preferences
- **Background Tasks**: WorkManager
- **Networking**: OkHttp + Kotlinx Serialization

## Compilazione

```bash
./gradlew testDebugUnitTest assembleDebug
```

L'APK di debug viene generato in:
`app/build/outputs/apk/debug/app-debug.apk`
