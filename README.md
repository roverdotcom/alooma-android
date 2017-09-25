<p align="center">
  <img src="https://github.com/aloomaio/androidsdk/blob/assets/alooma.png?raw=true" alt="Alooma Android Library" height="150"/>
</p>

# Latest Version [![Build Status](https://travis-ci.org/alooma/alooma-android.svg)](https://travis-ci.org/alooma/alooma-android)

##### _September 25, 2017_ - [v1.0.2](https://github.com/aloomaio/androidsdk/releases/tag/v1.0.2)

# Table of Contents

<!-- MarkdownTOC -->

- [Quick Start Guide](#quick-start-guide)
    - [Installation](#installation)
    - [Integration](#integration)
- [I want to know more!](#i-want-to-know-more)
- [Want to Contribute?](#want-to-contribute)
- [Changelog](#changelog)
- [License](#license)

<!-- /MarkdownTOC -->

<a name="quick-start-guide"></a>
# Quick Start Guide

Check out our **[official documentation](https://support.alooma.com/hc/en-us/articles/214019489-Android-SDK-integration)** for more in depth information on installing and using Alooma on Android.

<a name="installation"></a>
## Installation

### Dependencies in *app/build.gradle*

Add Alooma and Google Play Services to the `dependencies` section in *app/build.gradle*

```gradle
compile "com.alooma.android:alooma-android:5.+"
compile "com.google.android.gms:play-services:7.5.0+"
```

### Permissions in *app/src/main/AndroidManifest.xml*

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.BLUETOOTH" />
```

<a name="integration"></a>
## Integration

### Initialization

Initialize Alooma in your main activity *app/src/main/java/com/alooma/example/myapplication/MainActivity.java*. Usually this should be done in [onCreate](https://developer.android.com/reference/android/app/Activity.html#onCreate(android.os.Bundle)).

```java
String projectToken = YOUR_PROJECT_TOKEN; // e.g.: "1ef7e30d2a58d27f4b90c42e31d6d7ad" 
AloomaAPI alooma = AloomaAPI.getInstance(this, projectToken);
```
Remember to replace `YOUR_PROJECT_TOKEN` with the token provided to you on alooma.com.

### Tracking

With the `alooma` object created in [the last step](#integration) a call to `track` is all you need to start sending events to Alooma.

```java
alooma.track("Event name no props")

JSONObject props = new JSONObject();
props.put("Prop name", "Prop value");
props.put("Prop 2", "Value 2");
alooma.track("Event name", props);
```

<a name="i-want-to-know-more"></a>
# I want to know more!

No worries, here are some links that you will find useful:
* **[Sample app](https://github.com/aloomaio/sample-android-alooma-integration)**
* **[Android integration video tutorial](https://www.youtube.com/watch?v=KcpOa93eSVs)**
* **[Full API Reference](https://support.alooma.com/hc/en-us/articles/214019489-Android-SDK-integration)**

Have any questions? Reach out to [support@alooma.com](mailto:support@alooma.com) to speak to someone smart, quickly.

<a name="want-to-contribute"></a>
# Want to Contribute?

The Alooma library for Android is an open source project, and we'd love to see your contributions!
We'd also love for you to come and work with us! Check out our **[opening positions](https://alooma.com/jobs/#openings)** for details.

<a name="changelog"></a>
# Changelog

See [wiki page](https://github.com/aloomaio/androidsdk/wiki/Changelog).

<a name="license"></a>
# License

```
See LICENSE File for details. The Base64Coder,
ConfigurationChecker, and StackBlurManager classes, and the entirety of the
 alooma.android.java_websocket package used by this
software have been licensed from non-Alooma sources and modified
for use in the library. Please see the relevant source files, and the
LICENSE file in the alooma.android.java_websocket package for details.

The StackBlurManager class uses an algorithm by Mario Klingemann <mario@quansimondo.com>
You can learn more about the algorithm at
http://www.quasimondo.com/StackBlurForCanvas/StackBlurDemo.html.
```
