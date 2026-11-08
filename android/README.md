# DeskLink — Android client

Plain Java, framework APIs only (no AndroidX, no libraries). Release APK ≈ 100 KB.

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

## Desktop CLI (integration tests against the host)

```bash
./gradlew :core:jar
java -cp core/build/libs/core.jar com.desklink.core.tools.CliClient pair 127.0.0.1
java -cp core/build/libs/core.jar com.desklink.core.tools.CliClient status 127.0.0.1
java -cp core/build/libs/core.jar com.desklink.core.tools.CliClient connect 127.0.0.1 47810 10 out.h264
```

Commands: `pair`, `status`, `connect`, `send-file`, `get-file`, `type`, `clip`, `move`, `click`, `key`, `discover`.
Credentials go to `./desklink-cli.json` (override with env `DESKLINK_CREDS`).

Without Gradle:

```bash
javac --release 17 -encoding UTF-8 -d out $(find core/src/main/java -name '*.java')
java -cp out com.desklink.core.tools.CliClient status 127.0.0.1
```

`com.desklink.core.tools.FakeHost [port]` is a minimal loopback-only host used to self-test the client core.
