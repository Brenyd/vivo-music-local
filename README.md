# Music: local-first, vivo Music style

A local music player for Android that recreates the look of vivo Music (OriginOS), without the streaming service.

- **Local library**: songs, artists, albums and folders tabs, search, sorting, playlists, favorites, recently played and most played
- **Automatic covers and song info**: embedded covers are used first. Missing covers, artists and albums are matched online (Deezer, iTunes) and shown with vivo's blue "matched" check. Artist photos come from Deezer. Your music files are never modified.
- **Song information sheet**: change the cover, edit the info, "Matching" (pick from online results), and restore the original info
- **Lyrics**: synced, scrolling lyrics from [LRCLIB](https://lrclib.net). Tap a line to jump to it, or paste or edit lyrics yourself
- **Player**: vinyl-style now-playing screen with colors taken from the album art, a sleep timer, the play queue, play modes and a swipe on the cover to change songs
- **Real audio quality**: SQ / HQ / CD / HD tags read from the files themselves, and "FLAC · 24-bit · 48 kHz" shown in the player
- **Sound**: in-app equalizer and bass boost, ReplayGain volume leveling (track or album), gapless playback or fades between songs, 32-bit float hi-res output, and bit-perfect output to USB DACs (Android 14+)
- **Network libraries**: Navidrome/Subsonic servers, WebDAV and SMB shares (NAS or PC). Browse, play, and save songs to the phone
- **Convert to AAC**: makes smaller .m4a copies of songs (Media3 Transformer)
- **Home-screen widget** with the cover and playback controls
- **Hide or delete songs**
- **Android Auto**: browse Songs, Albums, Artists and Playlists, plus search and voice "play …"
- **vivo Origin Island**: the `vmusic-island` build uses the package name `com.hiby.music`, which OriginOS allows on the island. You can't have HiBy Music installed at the same time.

## Downloads

Every push builds two APKs, which are attached to [Releases](../../releases):

- `vmusic-N.apk`: the normal build (`com.brenyd.vmusic`)
- `vmusic-island-N.apk`: the Origin Island build (`com.hiby.music`)

### Signing

Add these repository secrets so every build is signed with the same key and installs as an update:
`SIGNING_KEYSTORE_B64` (the base64 of a .keystore), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`.
Without them, CI signs each build with a throwaway key, so you have to uninstall before installing a new build.

### Android Auto

Apps that aren't from the Play Store only show up in Android Auto when **Unknown sources** is on. To turn it on, open Android Auto settings, tap the version number 10 times, then use ⋮ → Developer settings → Unknown sources.
