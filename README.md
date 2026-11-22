# Wysebee Agent for Android

An Android app that lets **one Wysebee agent operate this phone as a person would**: read the
screen, take screenshots, tap, type, swipe, scroll, press back / home, and open apps — **only in
the apps you allow here**. It is meant for having an agent use an app end to end (testing an app
as its users would, say). It runs no model: the agent's loop runs in Wysebee Cloud (or a
desktop) and sends it one operation at a time, as it does to the browser extension and to
Wysebee Agent for Mac.

The app and Wysebee Cloud speak Wysebee's agent link protocol. A cloud that does not know the
Android agent type answers pairing with "This Wysebee Cloud does not pair Android agents yet."

## How it works

1. Install the app and open it. Tap **Open accessibility settings** and turn on **Wysebee
   Agent** (Android asks you to confirm: it is what lets the app see and touch the screen).
2. In Wysebee, open **Your devices › Pair a device › Android phone**: it shows a QR code and
   the server it comes from. Tap **Scan QR code** on the phone and point it at the screen. The
   phone shows that server and the Wysebee account that showed the code (its name and email),
   so you can check it is yours; type a name for a new agent and tap **Pair**. That agent — and only
   it — now operates this phone.
3. Or type a name and tap **Pair with a code** instead: the phone shows a code (`XXXX-XXXX`)
   to type in that dialog. It pairs with the server shown under **Server**
   (`https://app.wysebee.com` unless you change it).
4. Under **Apps the agent may operate**, tap **Add apps…**, tick the apps it may open, read and
   touch, and tap **Add**. There is no "every app": each is chosen.
5. Ask the agent to do something on the phone. **Let Wysebee operate this phone** pauses it;
   turning the accessibility service off stops it at once; **Unpair** ends it.

## The security model

- **You allow apps, here.** The agent cannot open, read or touch an app you did not list; an
  op is refused while such an app is in front. It never operates this app itself, whatever is
  allowed. The cloud cannot change the list.
- **System UI only if you turn it on**: a switch above your apps lets the agent use the
  notification shade and quick settings — it can then read every app's notifications (codes your
  bank texts you too) and toggle Wi-Fi, Bluetooth or airplane mode.
- **Nothing while the phone is locked**, whatever you allowed: it never touches the lock screen.
- **Never a password field**: its text is not read and nothing is typed into it.
- **Never a screenshot of a banking, payment, investing or password app**, even one you allowed:
  the agent reads it as text only. Such apps are marked when you pick them.
- **The accessibility service is the switch Android gives you**: off, the app can do nothing on
  the screen. It reads nothing by itself: only what an op asks for, when it asks.
- **The link's secret** (32 random bytes) is made on the phone and sealed with an Android
  Keystore key; the cloud keeps its SHA-256. It is sent only to the cloud the pairing was made
  with (no redirects followed).
- **The server**, https only, is `https://app.wysebee.com` unless the person types another
  under **Server** before pairing, or scans a QR code another Wysebee Cloud shows: the phone
  pairs with the server the QR code names, after showing it (and saying so when it is not
  Wysebee's own). A pairing keeps its server until it is unpaired.
- **What Wysebee Cloud sees**: each call's arguments and answer (screen text and screenshots of
  the allowed apps), kept until read, an hour at most.

## Build, run, test

JDK 17 and the Android SDK (`local.properties`' `sdk.dir`). Kotlin, Jetpack Compose, one
module, Android 11+ (API 30: the accessibility screenshot and Enter action need it).

Two versions, each its own app (package, name, data, keys), so both can be on one phone side
by side. Both start on `https://app.wysebee.com`; to try another Wysebee Cloud, scan the QR code
it shows, or type its address under **Server**:

| Version | App name | Package |
|---|---|---|
| `dev` (debug) | Wysebee Agent Dev | `com.wysebee.agent.android.dev` |
| `release` | Wysebee Agent | `com.wysebee.agent.android` |

```bash
scripts/build.sh dev             # or release
scripts/install.sh dev           # installs the build already made, and opens it
./gradlew testDebugUnitTest      # the core against a fake phone and a fake cloud on 127.0.0.1
```

`install.sh` does not build. With a phone and an emulator both attached, name one:
`ANDROID_SERIAL=emulator-5554 scripts/install.sh dev` (`adb devices` lists them).

### Trying it on the emulator

```bash
emulator -avd <your-avd> -no-snapshot-save &
scripts/build.sh dev && scripts/install.sh dev
adb shell settings put secure enabled_accessibility_services \
  com.wysebee.agent.android.dev/com.wysebee.agent.android.device.AgentAccessibilityService
```

(The last line turns the service on without the Settings screens. On a real phone, use the
app's button.)

Avoid `uiautomator dump` while it runs: UiAutomator turns other accessibility services off while
it works, so the app reads as off until the system binds it again.

## Releases

Wysebee Agent for Android is downloaded from [wysebee.com](https://www.wysebee.com/agents/android)
as a signed APK, not from Google Play. People install it by allowing their browser to install
unknown apps; Google Play Protect may ask them to confirm an app it has not seen. Scanning needs
Google Play services, which fetches its QR code scanner the first time the app opens.

`scripts/build.sh release` makes a release build, signed only when the signing properties
(`WYSEBEE_AGENT_ANDROID_STORE_FILE`, `_STORE_PASSWORD`, `_KEY_ALIAS`) are given as Gradle
properties; without them it comes out unsigned. The version is `appVersion` and
`appVersionCode` in `app/build.gradle.kts`.

## License

MIT: see [LICENSE](LICENSE).
