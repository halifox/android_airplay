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
