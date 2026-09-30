# HailTone

A minimal native Android app built with Kotlin and Jetpack Compose.

In GitHub Codespaces, reopen the repository in the configured dev container to install Java 21 and the Android SDK. Then build the debug APK with the Gradle Wrapper:

```sh
./gradlew assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Two-device signaling test

Phase 3 uses unauthenticated Supabase Realtime public Broadcast channels for temporary test signaling. In the root `local.properties` file (ignored by Git), keep the existing `sdk.dir` entry and add the project URL, a public publishable key (or legacy anon key), and the identity for the APK being built:

```properties
supabase.url=https://YOUR_PROJECT_REF.supabase.co
supabase.publishableKey=YOUR_SUPABASE_PUBLISHABLE_OR_LEGACY_ANON_KEY
pupsikcall.deviceId=pupsik-a
```

In the Supabase dashboard, open the project and use **Connect** to copy the Project URL and public key; publishable keys are also listed under **Project Settings → API Keys**. Under **Realtime → Settings**, turn **Allow public access** on. The app uses public channels (not `private=true`) and does not use Supabase Auth. No database table or SQL policy is required. Use only the `sb_publishable_...` key (or legacy `anon` key); never use an `sb_secret_...` or `service_role` key. Treat this as test-only: anyone with the Project URL and publishable key can join these channels and observe signaling metadata.

Build one APK per identity. The app targets the other fixed identity automatically. Build and save Device A's APK first, then change only `pupsikcall.deviceId` to `pupsik-b`, rebuild, and save Device B's APK:

```sh
./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk /tmp/hailtone-device-a.apk
# Change pupsikcall.deviceId to pupsik-b in local.properties before the next build.
./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk /tmp/hailtone-device-b.apk
```

Install the saved APKs on their matching devices, keep both apps open and online, and grant microphone permission when prompted. Tap the existing Sign In button on both devices to reach Contacts, then start the call from Device A. Device B receives the incoming call and can Answer or Decline. End Call terminates the peer session on both devices.

The app uses one shared public channel for call invitations, UUID-scoped public channels for each call's SDP and ICE, and a dedicated public Presence channel for live A/B online status. STUN is enabled for direct connectivity; TURN, background delivery, Auto Answer, and authentication are not included. Calls require both apps to be foregrounded, and restrictive NATs may require TURN later.