# Liquid Player

Player de músicas **locais** para Android (Kotlin + Jetpack Compose), offline-first, visual Liquid Glass.

## Como compilar
1. Android Studio Ladybug (2024.2) ou mais novo, JDK 17+.
2. `File > Open` e escolha a pasta `LiquidPlayer`. O Android Studio baixa o Gradle 8.10.2 e gera o wrapper.
   (Pelo terminal: `gradle wrapper --gradle-version 8.10.2` e depois `./gradlew assembleDebug`.)
3. Rode em um aparelho/emulador com Android 8.0+ (API 26). O desfoque real do fundo (Haze/RenderEffect) requer Android 12+; abaixo disso o app usa translucidez + gradientes.
4. APK de debug: `app/build/outputs/apk/debug/app-debug.apk`.

## Primeiros passos no app
Início → "Adicionar pasta" (ou Ajustes → Gerenciar diretórios) → escolha a pasta de músicas no seletor do Android.
Ao terminar a leitura, o app oferece (opcionalmente) "Criar playlist com as músicas desta pasta".

## Estrutura
```
app/src/main/java/com/liquidplayer/
├─ di/            AppModule (Hilt, Room, escopo do app)
├─ domain/        model/ (Song, Playlist, Lyrics, AppSettings…)  usecase/ (playlist a partir de pasta)
├─ data/
│  ├─ database/   Room: entidades, DAOs, AppDatabase
│  ├─ local/      FolderScanner (SAF), MetadataReader, CoverCache, EmbeddedLyricsReader
│  └─ repository/ Library, Playlist, Favorites, History, Lyrics, Settings
├─ player/        PlaybackService (Media3 ExoPlayer + MediaSession), PlayerController, PlayerState
├─ presentation/
│  ├─ theme/      Theme.kt (Material 3 + acento), Glass.kt (Liquid Glass)
│  ├─ components/ Artwork, SongRow, MiniPlayer, GlassBottomBar, diálogos…
│  ├─ navigation/ Routes, AppRoot (NavHost + camada flutuante)
│  ├─ screens/    Home, Library, Folders, Playlists, PlaylistDetail, Favorites, NowPlaying, Settings
│  └─ viewmodel/  ViewModels (Hilt)
└─ utils/         LrcParser, Formatters
```
