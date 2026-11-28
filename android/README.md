# DeskLink — Android client

Plain Java, framework APIs only (no AndroidX, no libraries). Release APK ≈ 185 KB.

```
android/
  core/   pure-Java library (no android.*): protocol, crypto, connection, discovery, files + desktop CLI
  app/    Android UI (com.desklink.app)
```

## Build

```bash
./gradlew assembleRelease          # → app/build/outputs/apk/release/app-release.apk (signed if keystore.properties exists)
./gradlew assembleDebug
```

Release signing reads `keystore.properties` (not committed):

```
storeFile=keystore/desklink-release.jks
storePassword=…
keyAlias=desklink
keyPassword=…
```

## Screen pictures (the README's previews)

`app/src/test/.../PreviewShots.java` draws the app's real screens with Robolectric (a test-only dependency; the app
itself has no AndroidX or libraries) and saves them as PNGs. Nothing connects anywhere, and the PC is a stand-in:
put stand-in pictures of its screen (`desk-one.png` 1920×1080, `desk-all.png` 3840×1200, `game.png` 1920×1080) in
`<folder>/in` (`python docs/standins.py <folder>/in` draws them; it needs Pillow and Windows' Segoe UI font), then:

```bash
./gradlew :app:testDebugUnitTest --tests com.desklink.app.PreviewShots -Ppreviews=<folder>
```

Without `-Ppreviews` the test is skipped.

## The session screen against a real host

`app/src/test/.../SendClipboardLiveTest.java` runs the session screen (Robolectric again) connected to a test build of
the Windows host, taps ⋮ → *Send clipboard*, and looks at that host's clipboard. The host runs in a window station of
its own, so the clipboard is not the desktop's (see [host/README.md](../host/README.md) for starting it):

```bash
./gradlew :app:testDebugUnitTest --tests com.desklink.app.SendClipboardLiveTest -Phost=127.0.0.1:47910 -Pstation=<full path of StationRun.exe>
```

Without `-Phost` and `-Pstation` the test is skipped.

`BackupAndStayLiveTest` runs the backup service and the session screen against a test build of the host (2.12) started
with `--data <folder> --bind 127.0.0.1 --port 47910 --no-ui --auto-approve --no-inject`: a backup that arrives with its
folders and dates, the next one sending nothing, one stopped part way and continued, and *Stay connected* keeping the
session and the PC's sound in the background (a quiet tone plays on the PC for a few seconds):

```bash
./gradlew :app:testDebugUnitTest --tests com.desklink.app.BackupAndStayLiveTest -Phost=127.0.0.1:47910 -Phostdata=<that folder>
```

## Desktop CLI (integration tests against the host)

```bash
./gradlew :core:jar
java -cp core/build/libs/core.jar com.desklink.core.tools.CliClient pair 127.0.0.1
java -cp core/build/libs/core.jar com.desklink.core.tools.CliClient status 127.0.0.1
java -cp core/build/libs/core.jar com.desklink.core.tools.CliClient connect 127.0.0.1 47810 10 out.h264
```

Commands: `pair`, `status`, `connect`, `send-file`, `backup`, `get-file`, `type`, `clip`, `move`, `click`, `key`,
`discover`. `backup <host> <folder> [stopAfterMB]` sends a folder's files as a phone's backup (side connection, the
phone's own backup code); with `stopAfterMB` it stops part way, and the next run goes on from there.
Credentials go to `./desklink-cli.json` (override with env `DESKLINK_CREDS`).

Without Gradle:

```bash
javac --release 17 -encoding UTF-8 -d out $(find core/src/main/java -name '*.java')
java -cp out com.desklink.core.tools.CliClient status 127.0.0.1
```

`com.desklink.core.tools.FakeHost [port]` is a minimal loopback-only host used to self-test the client core.
