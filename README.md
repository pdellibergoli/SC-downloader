# SC Downloader per Android (Kotlin + Jetpack Compose)

Versione nativa Android riscritta interamente in **Kotlin** con **Jetpack Compose (Material 3)**, senza dipendenza da Python.

## Funzionalità Incluse

1. **Core Completo (StreamingCommunity + AnimeUnity)**:
   - Ricerca unificata con filtri (`Film`, `Serie TV` per StreamingCommunity; `TV`, `Movie`, `OVA`, `ONA` e filtro `Solo ITA (Dub)` per AnimeUnity).
   - Scaffali della pagina iniziale (*Di tendenza*, *Aggiunti di recente*, *Top 10 di oggi*, *Ultimi episodi*, *In evidenza*) con cache TTL e gestione automatica del segmento `/it` per host.
   - Metadati completi dai props Inertia delle pagine titolo (trama, generi, cast, regia, voto, anno, backdrop/poster tramite proxy interno CDN con header `Referer` automatico).
   - Risoluzione stream `vixcloud`, estrazione chiave AES-128 (`/storage/enc.key`), supporto tracce audio multiple e sottotitoli WebVTT (`.vtt`).
2. **Motore Download HLS M3U8 & Muxing Nativo**:
   - Parser M3U8 completo (master playlist, varianti per banda/risoluzione, tracce `#EXT-X-MEDIA` audio/sottotitoli, `#EXT-X-KEY` con IV esplicito o sequenziale, fallback `?b=1` su errori `403`).
   - `AdaptiveLimiter` (AIMD) per regolare dinamicamente la concorrenza dei worker sui segmenti `.ts`, backoff esponenziale con jitter, supporto `Retry-After`, secondo passaggio sequenziale di recupero segmenti falliti e verifica integrità completa prima dell'unione.
   - Decrittazione `AES/CBC/PKCS5Padding` nativa tramite `javax.crypto.Cipher`.
   - Demuxer MPEG-TS nativo in Kotlin + `android.media.MediaMuxer` (con supporto automatico anche a `ffmpeg` di sistema se disponibile) per produrre file `.mp4` / `.mkv` riproducibili su qualsiasi player Android.
3. **Download in Background & Libreria Locale**:
   - `DownloadForegroundService` (`dataSync`) con notifiche Android in tempo reale (percentuale, velocità in `MB/s`, `ETA` e pulsante *Annulla*).
   - Salvataggio automatico in `Download/StreamingCommunity/{Movies,Series,Anime}` con scansione immediata nel `MediaStore` di Android.
   - Scheda **Libreria** integrata per riprodurre, condividere o eliminare i file scaricati tramite `FileProvider`.
4. **Serie Seguite (Watchlist) & Domain Recovery**:
   - **Serie Seguite**: monitoraggio periodico tramite `WorkManager` (`WatchlistPollWorker`) e controllo manuale per scaricare automaticamente i nuovi episodi pubblicati.
   - **Domain Recovery**: verifica e ricerca automatica del nuovo dominio StreamingCommunity (`DomainRecoveryManager` + `DomainCheckWorker`) con validazione sintattica, verifica DNS pubblica e conferma del payload Inertia.

## Compilazione APK

Dalla cartella `android/`:

```bash
./gradlew testDebugUnitTest assembleDebug
```

L'APK installabile viene generato in:
`android/app/build/outputs/apk/debug/app-debug.apk`
