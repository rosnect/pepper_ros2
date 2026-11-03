# Validation — 2026-10-25

## Completed locally on macOS

- Android debug APK built successfully using Java 17, Gradle 7.6.3, Android Gradle Plugin 7.3.0, compile SDK 34, QiSDK 1.7.5 and Paho 1.2.5.
- 14 Java/JUnit speech-controller and wire-contract tests passed. Covered normal completion, duplicate/reordered delivery, cancellation confirmation, deadlines, unconfirmed-stop blocking, focus loss, disconnection, expired/wrong-session commands, busy/invalid requests, late start callbacks, replay and bounded history.
- 13 Python protocol tests passed. Covered text/time limits, command IDs/expiry, malformed input, heartbeat freshness, device restart, event correlation/order, retries, cancellation, deadline and unknown outcomes.
- All new Python source files compile successfully.
- Android lint completed with zero errors and eight English-UI localization warnings. Legacy support-library/target-SDK compatibility checks are narrowly suppressed for the intentionally retained Pepper configuration in `android/app/lint.xml`.
- APK manifest inspected: `com.rosnect.saypepper`, version 0.1.0, min SDK 23, target SDK 28, Internet/network-state/QiSDK robot permissions.
- APK signing verified for v1 and v2 signatures, including the signing scheme supported by Android 6. This is the local Android debug signing identity.
- Source hash comparison: all 54 baseline `ros2embedded` source files and all 36 baseline `Hello-Pepper` source files remain unchanged. Existing untracked files in `ros2embedded` were preserved.

Reproduce:

```bash
python3 -m unittest discover -s tests -v
python3 -m compileall -q ros2 tests
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

## Pending on Linux / in the lab

ROS 2 is not installed on this Mac, and Pepper is not present. No claim is made that these checks have already passed:

1. Build the two new ROS packages with colcon against the lab ROS distribution.
2. Run `python3 tests/ros_action_smoke.py -v` in a sourced environment. This exercises the real ROS action server with a simulated ROS topic peer, including success, cancellation, busy/disabled rejection and timeout. It does not test MQTT or QiSDK.
3. Install and open the APK on Pepper's actual tablet; confirm QiSDK robot focus.
4. Verify MQTT subscription/configuration acknowledgement and the four discovered bindings through unchanged `ros2embedded`.
5. Verify physical speech, ROS feedback/result and confirmed cancellation.
6. Verify local stop, background/focus loss, broker loss, host loss and recovery with explicit re-enablement.
7. If TLS is needed, verify Android 6 trusts the broker chain and the configured hostname matches the certificate.

The APK has not been installed on the user's phone or sent robot commands during local development. Full speech-through-MQTT acceptance remains a lab test.

## Diagnostic update — 2026-11-03

The first physical test reached tablet command acceptance, then failed in QiSDK speech. The original app hid the actual SDK error, so the cause is not yet identified. Version 0.1.1 preserves the SDK build/run stage and error detail in the tablet/ROS result (bounded to 300 characters), logs full diagnostic messages under `SayPepper`, and logs robot-focus transitions. No robot password or new permissions have been added.

Android build, lint, 16 Java tests and 13 Python protocol tests passed locally for this update. Physical retry and the underlying speech failure diagnosis remain pending lab logs. `ros2embedded` was not modified.

## Threading fix — 2026-11-03 (0.1.2)

The supplied `pepper_log_detail.txt` contains four `QiSDK speech run: android.os.NetworkOnMainThreadException` failures. The run setup registered a QiSDK started listener synchronously on the UI thread. All QiSDK operations now run on a dedicated serialized worker, and only controller/UI notifications return to the main looper. Worker shutdown cancels pending speech and suppresses late UI callbacks. No additional permissions or changes to `ros2embedded` are required for this fix.

APK build and lint passed. All 19 Java tests and 13 Python tests passed, including three regression tests for background SDK dispatch, serialized focus/start/cancel ordering, UI notification dispatch and worker cleanup. Physical speech must be retried on Pepper using version 0.1.2.

Reference: [QiSDK synchronous/asynchronous calls](https://qisdk.softbankrobotics.com/sdk/doc/pepper-sdk/ch2_principles/sync_async.html).
