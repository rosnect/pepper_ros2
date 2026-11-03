# say_pepper

A separate Pepper tablet app and ROS 2 adapter that turn a QiSDK capability into a native ROS 2 action. This first milestone exposes **speech** through `/pepper/say`, with feedback, results, cancellation, device presence and robot-focus checks.

The existing `ros2embedded` checkout and `Hello-Pepper` example are unchanged. Pepper-specific code lives here. The working Rosnect phone connection can continue using its own connection ID.

```mermaid
flowchart LR
    C[ROS 2 action clients] <-->|/pepper/say| A[say_pepper ROS adapter]
    A <-->|JSON in String topics| B[existing ros2embedded]
    B <-->|MQTT broker| T[say_pepper tablet app]
    T <-->|QiSDK 1.7.5| P[Pepper]
```

## Included

- Native Android app: minimum Android 6 / API 23, target API 28, QiSDK 1.7.5 / robot API 7. Same robot SDK versions as `Hello-Pepper`.
- Broker defaults: **192.168.0.81:1883**. Select 1883 MQTT or 8883 TLS, with optional username/password. Passwords are not saved. TLS uses Android's trusted CA store and hostname verification; custom CA files and client certificates are not implemented in this milestone.
- Broker/configuration state and robot focus shown separately; remote speech starts disabled and must be enabled locally. Pause, lost focus and lost connection disable speech and request cancellation.
- One speech action at a time. UUID correlation, short command expiry, session identity, duplicate detection, bounded command history, deadlines and confirmed cancellation.
- Four automatically discovered topic bindings; no bridge changes or static bridge config file.
- A command-line action client and a ROS smoke test using an isolated simulated topic peer.

This is the foundation for the Pepper master app. Camera, listening, animations, navigation and other QiSDK capabilities are **not exposed yet**. Speech uses Pepper's configured language, with speech body-language gestures disabled; it does not provide arbitrary method invocation or direct wheel/joint control.

## Build the Android APK

Open `android/` in Android Studio, or use Java 17 and an Android SDK containing platform 34:

```bash
cd android
# Set sdk.dir in local.properties to your Android SDK installation, or set ANDROID_HOME.
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`.
A ready-built copy is in `artifacts/say_pepper-debug.apk` in this workspace. It is a **debug APK for lab testing**. Source files for transfer to Linux are also packaged in `artifacts/say_pepper-source.zip`.

The app intentionally retains the original Pepper application's target SDK and legacy support libraries. Two specific Gradle lint checks are scoped out in `android/app/lint.xml` for that compatibility choice. Google Play publication is outside this build.

## Next lab visit

### 1. Install on Pepper's tablet

Connect the Pepper tablet through your existing authorized ADB setup. Use its actual serial, especially if your phone is also connected:

```bash
adb devices -l
adb -s PEPPER_TABLET_SERIAL install -r artifacts/say_pepper-debug.apk
adb -s PEPPER_TABLET_SERIAL shell am start -n com.rosnect.saypepper/.MainActivity
```

Launch **say_pepper**, keep it in the foreground, and close the old Hello-Pepper app. The screen stays awake while this app is open. Nothing is spoken just by launching it.

The merged APK declares Internet, network-state and QiSDK's `com.aldebaran.permission.ROBOT` permission. There are **no microphone, camera, location or storage permission prompts** for speech output. Pepper's Robot Service/QiSDK setup and robot focus are required. Confirm the robot software matches the API 7 / QiSDK 1.7.5 environment that runs your existing example. No root access or modification of the robot OS is required by this implementation.

### 2. Build only the new ROS packages on Linux

Use the ROS installation and already-built `ros2embedded` workspace that work in your lab. Example for ROS 2 Humble (adjust the ROS distribution and existing workspace path):

```bash
source /opt/ros/humble/setup.bash
source /path/to/ros2embedded/install/setup.bash
cd /path/to/say_pepper/ros2
# These are the only new packages built by this command.
colcon build --symlink-install --packages-select say_pepper_interfaces say_pepper_bridge
source install/setup.bash
```

Dependencies are standard ROS packages: `ament_cmake`, `ament_python`, `rosidl_default_generators`, `rclpy`, `std_msgs`, `action_msgs` and `launch_ros`, plus your existing `bridge_manager`. Install any missing standard dependencies through your normal ROS package setup. Do not rebuild or edit `ros2embedded` for this app.

Use the same `ROS_DOMAIN_ID` in the ROS terminals that need to discover each other. The bridge's logical `domain_id=7` determines MQTT routing and namespacing; it is independent of the DDS `ROS_DOMAIN_ID` environment variable.

You can first check the action server without hardware or MQTT:

```bash
cd /path/to/say_pepper
python3 tests/ros_action_smoke.py -v
```

This test uses a unique ROS namespace and never sends commands to Pepper. It covers success, cancellation, concurrent-goal rejection, timeout and a disabled tablet. It still requires the new ROS packages to be built and sourced.

### 3. Start the host bridge and speech adapter

```bash
ros2 launch say_pepper_bridge say_pepper.launch.py broker:=192.168.0.81
```

The launch file starts a separate existing bridge-manager instance for **connection `pepper_1`, logical domain 7**, plus the new speech action node. Do not also start another bridge for this same connection. If you already run that Pepper bridge with custom settings, start only the action adapter:

```bash
ros2 launch say_pepper_bridge say_pepper.launch.py start_bridge:=false
```

The existing phone bridge can run under `phone_1` at the same time. This does not change its bindings.

In the tablet app, tap **Connect**. Wait for `bridge ready` and `robot focus`, then turn on **Enable remote speech**. `READY FOR SPEECH` means all three conditions hold and no speech is active.

### 4. Speak and observe

In another terminal with the same ROS overlays sourced:

```bash
ros2 run say_pepper_bridge pepper_say "Hello, I am Pepper, speaking from ROS 2."
```

Expected feedback: `accepted`, `running`, then `succeeded`. Success is reported after QiSDK completes the action.

The standard ROS CLI also works:

```bash
ros2 action send_goal /pepper/say say_pepper_interfaces/action/Say \
  "{text: 'Hello from ROS 2', timeout_sec: 30.0}" --feedback
```

Observe device presence and events:

```bash
ros2 topic echo /ros2embedded/domain_7/connection_pepper_5f1/state
ros2 topic echo /ros2embedded/domain_7/connection_pepper_5f1/speech/event
```

The `_5f` is the existing bridge's escaping of `_` in `pepper_1`.

### 5. Test cancellation and loss of focus

Use a sufficiently long sentence:

```bash
ros2 run say_pepper_bridge pepper_say \
  "This is a longer speech test. I will stop when the ROS action cancellation reaches my tablet." \
  --cancel-after 1
```

Expect `cancel_requested`, followed by `canceled` once QiSDK confirms completion of cancellation. Try the tablet's **Stop speech & disable remote control** button too. A local stop aborts the ROS action with a `canceled` result state; a ROS-requested, confirmed stop uses ROS's CANCELED action status.

While speaking, switching away from the app should disable remote speech and request a stop. Returning to the app requires enabling speech again. Disconnecting the broker or losing robot focus also disables it. If the host disappears while MQTT stays connected, the tablet expires its configuration acknowledgement after approximately eight seconds and requests a stop.

## Network and broker setup

Use the laptop's current reachable Wi-Fi address for both the tablet app and host bridge. The default `192.168.0.81` is only correct while that laptop still has that address. Pepper's tablet and the Linux host must both reach the broker; Wi-Fi client isolation and host firewall rules can prevent this.

Leave your working broker setup in place. If it uses ACLs, the tablet needs:

- Publish: `ros2embedded/7/pepper_1/config` and `ros2embedded/7/pepper_1/to_ros`.
- Subscribe: `ros2embedded/7/pepper_1/to_device`, `config_status` and `config_request` under that same prefix.

The host bridge needs the reverse permissions. Use one device owner for this connection. Treat the command topics as robot-control access: devices that can publish there can request speech while it is enabled.

For TLS or authenticated brokers, select TLS/enter credentials on the tablet and run your **existing** bridge manager with corresponding `mqtt_tls`, `mqtt_username`, `mqtt_password`, `mqtt_ca_file` parameters via a local ROS parameter file; then launch the action adapter with `start_bridge:=false`. Merely changing the launch `port` to 8883 does not turn on TLS. Android 6 must trust the broker's certificate chain and the certificate must match its hostname/IP. This app does not bypass certificate checks.

**Synchronize the Linux host and tablet clocks** (automatic network date/time). Commands expire after five seconds and excessive clock skew causes them to be ignored. Commands are never retained. Retained interface configuration is expected and safe; it does not grant speech readiness without fresh tablet heartbeats.

## Testing status

Local verification and limitations are recorded in [docs/validation.md](docs/validation.md). The APK and core state machines are tested locally. ROS 2 action runtime, physical Pepper speech, focus handling on the actual tablet, and the complete MQTT-to-QiSDK path remain lab acceptance checks.

See [docs/protocol.md](docs/protocol.md) for extension points and message contracts.

## Diagnosing speech errors (0.1.1)

If ROS reports `accepted` followed by a QiSDK failure, delivery succeeded but speech did not complete. Version 0.1.1 includes the failing SDK stage and detail in the event/result. Keep the app open, reconnect and enable remote speech after reinstalling. For live diagnostics on the tablet laptop:

```bash
adb -s 192.168.50.6:5555 logcat -v time "SayPepper:V" "*:S"
```

Retry one short speech command from ROS, then copy the error and any nearby focus messages. The diagnostic update exposes the cause; it does not claim to fix an as-yet-unknown robot-side failure. The generated `artifacts/qisdk-diagnostics.patch` can be copied to a clean clone and applied from the `pepper_ros2` repository root using `git apply /path/to/qisdk-diagnostics.patch` before rebuilding.

### NetworkOnMainThreadException fix (0.1.2)

A physical speech test identified synchronous QiSDK listener registration on Android's UI thread. Version 0.1.2 moves SDK operations to a dedicated worker and preserves UI updates on the main thread. No new robot permissions are needed for this error. Copy the latest `artifacts/say_pepper-debug.apk` to the tablet-connected laptop and install it with `adb -s 192.168.50.6:5555 install -r say_pepper-debug.apk`. Reopen, reconnect, enable remote speech and retry. ROS packages do not need rebuilding.

To build from source on a lab checkout currently at diagnostic version 0.1.1, copy `artifacts/qisdk-threading-fix.patch` there, apply it from the `pepper_ros2` repository root using `git apply /path/to/qisdk-threading-fix.patch`, then rebuild the Android app. These local changes must be transferred before a lab rebuild can contain the fix.
