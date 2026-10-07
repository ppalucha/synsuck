# Synsuck

Private Android app for reading Librus Synergia messages across multiple accounts.
See [docs/design.md](docs/design.md) and [docs/librus-api.md](docs/librus-api.md).

## Modules

- `app` — the Android app (Compose, Room, Keystore-encrypted passwords)
- `librus-client` — Kotlin/JVM client for the Librus login flow and messages API
- `probe` — dev-only CLI that runs the client against a real account (redacted output)

## Building (Docker, nothing to install locally)

All tooling (JDK 21, Android SDK, Gradle) runs in a container via `./dev`:

```sh
./dev ./gradlew test                 # run tests
./dev ./gradlew :probe:installDist   # build the probe
./dev --rebuild                      # rebuild the toolchain image
./dev                                # shell inside the container
```

The first run builds the image (~1 GB, Android SDK included). The Gradle cache lives in
the `synsuck-gradle` Docker volume.

## Installing on a phone

Build: `./dev ./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

**Option A — copy the file:** send the APK to the phone (Google Drive, Signal, email…),
open it, and allow "Install unknown apps" for that app when asked.

**Option B — wireless adb** (phone and Mac on the same Wi-Fi; pairing is remembered in
the `synsuck-adb` volume):

1. Phone: Settings → Developer options → **Wireless debugging** → on.
2. Pair once: tap *Pair device with pairing code*, then
   `./dev adb pair <ip>:<pairing-port> <code>`
3. Install (the port shown on the Wireless debugging screen, not the pairing one):
   `./dev sh -c 'adb connect <ip>:<port> && adb install -r app/build/outputs/apk/debug/app-debug.apk'`

## Probing a real account

From a real terminal (needs a TTY for the hidden password prompt):

```sh
./dev probe/build/install/probe/bin/probe <librus-login>
./dev probe/build/install/probe/bin/probe <librus-login> --show-content   # include names/subjects/body
./dev probe/build/install/probe/bin/probe <librus-login> --trace          # redacted HTTP redirect chain
```

Or provide the password via `LIBRUS_PASS` (forwarded into the container by name only).
Set it without leaving it in shell history:

```sh
read -rs "LIBRUS_PASS?Librus password: " && export LIBRUS_PASS   # zsh; bash: read -rsp "Librus password: " LIBRUS_PASS
./dev probe/build/install/probe/bin/probe <librus-login> --trace
unset LIBRUS_PASS
```

The probe never opens unread messages, since that marks them read in Librus.
