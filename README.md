# Airplay Android Library

This repository contains the Airplay receiver extracted from the original
`Airplay-DLNA-Example` application. The public API is in the
`com.halifox.airplay` package and the native library is loaded automatically
by `AirplayJni`.

## Local build

```shell
./gradlew :airplay:test
./gradlew :airplay:assembleRelease
./gradlew :airplay:publishToMavenLocal
```

The release artifact is an Android AAR and includes the native Airplay
implementation for the ABIs built by the Android NDK.

## JitPack

Push this repository to GitHub and create a tag or GitHub release. Replace
`OWNER`, `REPO`, and `VERSION` with the GitHub owner, repository name, and tag:

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.OWNER.REPO:airplay:VERSION'
}
```

Because this repository also contains the optional `app` demo module, the
JitPack dependency uses the multi-module coordinate for the `airplay` module.

## Usage

```java
AirplayJni airplay = AirplayJni.getInstance();
airplay.registerCallBacks(callbacks);

String hardwareAddress = MdnsServices.getHardwareAddressString();
int port = airplay.airplayInit(7000, hardwareAddress);
MdnsServices.registerAirplayService(port, "Music Player");

// When the receiver is no longer needed:
MdnsServices.unRegisterAirplayService();
airplay.airplayUninit();
```

The host application must provide an `AirplayJni.CallBacks` implementation to
consume decoded PCM audio and metadata events.

## Demo app

The optional app module contains a small Java and XML receiver demo. It
shows the current song title, artist, album, JPEG album art, audio format,
playback position, and duration. The demo uses the public airplay module
without changing its native protocol implementation.

Build and install the demo with:

    ./gradlew :app:assembleDebug

After launching the app on an Android device:

1. Connect the phone and the AirPlay sender to the same Wi-Fi network.
2. Tap Start receiving.
3. Select the phone's AirPlay receiver named Airplay Demo on the sender.
4. Stop receiving before closing the app when testing lifecycle changes.

The data path is:

    AirPlay sender -> native RAOP/RTP receiver -> JNI callbacks -> AudioTrack and UI

The Java demo is intentionally a display-only player for transport progress.
The progress bar is not seekable because the current native API does not
expose a seek command. Metadata is read from the DMAP tags minm, asar, and
asal. The current library still has these known limitations:

- audio-only RAOP reception;
- no AirPlay video, photo, or screen-mirroring reception;
- one native AirPlay client at a time;
- the existing mDNS helper only recognizes wlan0 and eth0 interfaces.
