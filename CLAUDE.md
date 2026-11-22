# CLAUDE.md

Guidance for working in this repository: **Wysebee Agent for Android**, an Android app that pairs
with Wysebee Cloud and lets one agent operate the phone — read the screen, screenshot, tap, type,
swipe, scroll, keys, open apps — through an accessibility service, only in the apps the person
allowed in its window. It runs no model. README.md says how to build, run and test it. It is the
sibling of `wysebee-agent-osx` (read its CLAUDE.md: the same rules apply where they fit).

## What binds

- **The link protocol is the contract** with Wysebee Cloud and Wysebee Assistant. A change to it
  is a change in all of them; raise it with the owner. This repository is public: never write
  internal design documents, paths or other repositories' internals into it.
- **Never widen what the agent can do without the owner's say**: the person allows apps here,
  one by one (never "every app": the owner removed it, 2026-11-17), never the cloud; never this
  app itself; never a password field; never a picture of a banking, payment, investing or
  password app (`core/Sensitive.kt`); System UI only with its own switch (`State.systemUi`);
  nothing while the phone is locked (`Device.locked`); refused while paused; no op the
  protocol does not list.
  Each is a decision, not a detail.
- **The secret is only in the Keystore-sealed file** (`device/KeystoreSecrets.kt`), never in
  the state file or a log, and goes only to the cloud the link was made with (no redirects).
- **Tests inject everything**: the phone (`Device`, `UiNode`: `FakeDevice`, `FakeNode`), the
  secret store (`MemorySecrets`), the cloud (`FakeCloud`, MockWebServer on 127.0.0.1), the
  state file (a temporary folder). No test reaches the internet or a real phone.
- **The server** (2026-11-22, the owner's call, replacing a server fixed per build): every
  build starts on `https://app.wysebee.com` (`DEFAULT_CLOUD_URL`); the person may type another
  under Server before pairing (`Agent.setServer`, `State.server`), and a scanned QR code pairs
  with the cloud it names (`Agent.scan`, then `pairScanned` with the name), shown first, with a
  warning when it is not Wysebee's own, beside the account that showed it (`LinkClient.previewOffer`,
  the cloud's `POST /offers/preview`: name and email, for the person to confirm it is theirs). https only (`CloudAddress`). A link and a pending pairing
  keep their own server. **Never write a dev, staging or tunnel address into the repo** (it is
  public).
- **Do not bump the version unless asked.** It lives in `app/build.gradle.kts` only.

## Layout

```
app/src/main/java/com/wysebee/agent/android/
  core/          plain Kotlin, no Android class: everything testable on the JVM
    Secrets.kt   SecretStore, MemorySecrets, the secret and its SHA-256
    State.kt     State (the file: link, pending, paused, allowed apps), StateStore (atomic writes)
    LinkClient.kt the protocol's HTTP calls: start (with a QR code's offer, or not), poll,
                 result, unpair
    PairingQr.kt the pairing QR code Wysebee shows: its offer, and the cloud that showed it
    CloudAddress.kt a server's address: https://host[:port], https only
    Device.kt    Device and UiNode: the phone as the ops see it
    Snapshot.kt  the screen as text with refs
    Sensitive.kt the apps never screenshotted: banking, payment, investing, password
    Ops.kt       every op: the gates (paused, service, allowed app, own app, password), then the act
    Agent.kt     the server, pairing (scan first, name after; or a code), the poll loop, calls
                 beside it, pause, allow, unpair
  device/        the Android side
    AgentAccessibilityService.kt  Device over AccessibilityService (gestures, global actions,
                 takeScreenshot, windows); AndroidNode.kt  UiNode over AccessibilityNodeInfo;
    KeystoreSecrets.kt
  AgentApp.kt    holds the Agent and runs its loop while the process lives
  ui/MainActivity.kt  the window (Compose): service state, pairing, pause, apps (a picker with a
                 checkbox each), the cloud it uses
scripts/         build.sh [dev|prod], install.sh [dev] (installs a build already made)
app/src/test/    FakeDevice / FakeNode (Fakes.kt), FakeCloud (AgentTest.kt), Ops, Snapshot, State tests
```

## How the pieces behave (and why)

- **No foreground service.** The loop runs in the process; the system keeps a bound
  accessibility service alive, which keeps the process alive. Service off and window closed, the
  process may die and the cloud shows the phone offline — honest, since it could do nothing.
- **A poll is cut short only when the link changes** (`Agent.edit`): a poll's answer may carry
  calls the cloud hands out once, so pausing or allowing an app never cancels it. Both are read
  at each call (`Ops` takes `Policy` per call); the next poll reports the pause.
- **One op at a time** (`Ops.hands`). Refs are the latest snapshot's (`Ops.refs`); a ref is
  refreshed before use and refused if gone or in an app not allowed.
- **Gates are in `Ops.gate`**: the active window's package must be allowed and not this app.
  `android_key` and `android_launch` (which checks its own target) are the only acting ops that
  skip it, so the agent can always leave an app it may not use.
- **Typing** sets the text with `ACTION_SET_TEXT`; a stand-in field (Contacts' search bar) that
  refuses it but moves focus on tap gets the text typed into the newly focused field.
- **Settling**: after an op, wait until 300 ms pass with no accessibility event (at most 2 s,
  4 s after a launch), so the next snapshot reads the new screen.
- **401 forgets everything** (link, secret, allowed apps, pause): a new pairing is a new agent.

## Known limits

- English only. Android 11+.
- Apps that draw their own UI (games, Flutter without semantics, WebViews with little
  accessibility) give thin snapshots: use `android_screenshot` and coordinates.
- `FLAG_SECURE` screens (banking apps, some sign-ins) cannot be screenshotted.
- Not reachable while the screen is locked; the agent cannot unlock the phone.

## Testing

```bash
./gradlew testDebugUnitTest
```

Every test passes, every time. Cover new core code with a real test against the fakes; keep the
Android layer (`device/`, `ui/`) thin enough to need none.
