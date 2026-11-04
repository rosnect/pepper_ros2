# Verification — 2026-11-03

## Completed locally

- Separate Android application ID `com.rosnect.peppervision`, minimum API 23 / Android 6, target API 28, compile API 34, QiSDK 1.7.5 / robot API 7, Paho 1.2.5.
- APK assembled successfully using the same Gradle 7.6.3 / AGP 7.3.0 toolchain as the working speech app. No robot or phone was targeted during the build.
- **32 Java/JUnit tests passed:** inherited speech protocol/lifecycle and worker-thread tests, plus camera permission/readiness gating, cadence, one capture in flight, late-frame suppression after stop/restart, disconnection/re-arm, three-failure backoff, capture watchdog, locked settings, measured FPS, sink drops, settings limits and latest-frame queue behavior under a slow consumer.
- **11 Python tests passed:** real baseline/progressive JPEG dimensions and decoding, ROS RGB channel order and byte stride, invalid/truncated/oversized images, decoder failure, explicit uncalibrated CameraInfo, preservation of real calibration, dimension mismatch, invalid calibration and received-FPS calculations.
- Python imaging tests executed against actual OpenCV and NumPy in an isolated temporary virtual environment. No ROS mocks were used to claim runtime integration.
- Python source syntax checked for the new package and Linux ROS smoke tests.
- Android lint completed with zero errors and nine localization warnings. Existing narrowly scoped legacy SDK/support-library lint exceptions were retained from the Pepper-compatible speech app.
- Native image declaration and fields match the current read-only bridge implementation: `JSON` config enum, lowercase `json` data envelope, `sensor_msgs/msg/CompressedImage`, JPEG format, explicit header, padded whitespace-free base64, 128 KiB frame cap versus the bridge's 256 KiB limit.
- Actual QiSDK 1.7.5 dependency JAR signatures were inspected. Public `Camera` exposes TakePicture; image copying is `EncodedImageHandle.async().getValue()`. No public raw depth API was present in that camera interface.
- Source snapshots verify `say_pepper`, `Hello-Pepper` and `ros2embedded` remain unchanged by this work.

## Pending physical/Linux acceptance

ROS 2 and Pepper are not available to this local build process. These checks are supplied for the lab and have **not** been reported as passed:

1. Build/source the new ROS package together with the existing speech interfaces/adapter against the lab's ROS distribution.
2. Run `python3 pepper_vision/tests/ros_vision_smoke.py -v` and `python3 say_pepper/tests/ros_action_smoke.py -v` in the sourced ROS environment. The vision smoke test uses isolated local topics and verifies native compressed/RGB/CameraInfo outputs, stale/corrupt-frame rejection and truthful depth diagnostics.
3. Install the APK on Pepper's tablet and confirm focus and configuration acknowledgement for connection `pepper_vision`.
4. Verify real head-camera data, tablet preview, native ROS compressed topic, decoded `rgb8` topic and RViz Image display.
5. Measure received FPS and approximate age with `vision_probe` at 1, 2, 3 and optionally 5 FPS. Test speech/cancellation simultaneously. **2 FPS is a conservative default, not a measured guaranteed rate.**
6. Test local stop, pause/focus loss, Wi-Fi/broker/host loss, reconnect and explicit re-enablement. Confirm stale frames do not accumulate under a slow receiver.
7. Validate actual calibrated geometry only after measuring real intrinsics/extrinsics. RGB-D is not supplied by this SDK path; no fake depth or guessed calibration is published.

The ROS header is an approximate tablet capture-result receipt timestamp, not a converted robot epoch or an exposure-synchronized measurement. Both clocks must be synchronized for age filtering and speech expiry. Original robot monotonic nanoseconds remain in metadata.

## Reproduce local checks

```bash
cd pepper_ros2/pepper_vision/android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
cd ..
# With numpy and opencv-python-headless installed in a development venv:
python -m unittest discover -s tests -v
python -m compileall -q ros2 tests
```
