"""Native ROS camera topics around unchanged ros2embedded compressed-image discovery."""
import json
import math
import time

import rclpy
from rclpy.node import Node
from rclpy.executors import ExternalShutdownException
from rclpy.qos import QoSProfile, ReliabilityPolicy, DurabilityPolicy
from sensor_msgs.msg import CompressedImage, Image, CameraInfo
from std_msgs.msg import String
from diagnostic_msgs.msg import DiagnosticArray, DiagnosticStatus, KeyValue
from .imaging import (Calibration, DEFAULT_BRIDGE, DEFAULT_OUTPUT, OPTICAL_FRAME,
                      jpeg_dimensions, decode_rgb, camera_info_values)


class VisionNode(Node):
    def __init__(self, **kwargs):
        super().__init__('pepper_vision', **kwargs)
        self.declare_parameter('bridge_namespace', DEFAULT_BRIDGE)
        self.declare_parameter('output_namespace', DEFAULT_OUTPUT)
        self.declare_parameter('calibration_file', '')
        self.declare_parameter('max_frame_age_sec', 3.0)
        source = self.get_parameter('bridge_namespace').value.rstrip('/')
        target = self.get_parameter('output_namespace').value.rstrip('/')
        self.max_age = float(self.get_parameter('max_frame_age_sec').value)
        if not math.isfinite(self.max_age) or self.max_age < 0:
            raise ValueError('max_frame_age_sec must be finite and nonnegative')
        self.calibration = None
        path = self.get_parameter('calibration_file').value
        if path:
            import yaml
            with open(path, encoding='utf-8') as stream:
                self.calibration = Calibration.from_mapping(yaml.safe_load(stream))
        # A depth-one reliable output accepts both reliable and sensor-data subscribers.
        qos = QoSProfile(depth=1, reliability=ReliabilityPolicy.RELIABLE, durability=DurabilityPolicy.VOLATILE)
        self.compressed = self.create_publisher(CompressedImage, target + '/color/image_raw/compressed', qos)
        self.raw = self.create_publisher(Image, target + '/color/image_raw', qos)
        self.info = self.create_publisher(CameraInfo, target + '/color/camera_info', qos)
        self.diagnostics = self.create_publisher(DiagnosticArray, target + '/diagnostics', 5)
        self.create_subscription(CompressedImage, source + '/camera/color/image_raw/compressed', self.on_frame, qos)
        self.create_subscription(String, source + '/camera/metadata', self.on_metadata, 5)
        self.create_subscription(String, source + '/state', self.on_state, 5)
        self.last_frame = None
        self.started = time.monotonic()
        self.accepted = self.dropped = self.decoded = 0
        self.last_error = ''
        self.last_state = self.metadata = {}
        self.last_state_time = float('-inf')
        self.calibrated = False
        self.create_timer(1.0, self.status)
        self.get_logger().info('Camera outputs at ' + target + '; depth unavailable via QiSDK 1.7.5')

    def on_frame(self, message):
        try:
            if message.format.lower() not in ('jpeg', 'jpg'):
                raise ValueError('Expected JPEG camera frames')
            if message.header.frame_id != OPTICAL_FRAME:
                raise ValueError('Unexpected camera optical frame')
            stamp = message.header.stamp.sec + message.header.stamp.nanosec/1e9
            age = time.time() - stamp
            if self.max_age and (stamp <= 0 or age > self.max_age or age < -self.max_age):
                raise ValueError('Stale frame or unsynchronized tablet/host clocks')
            width, height = jpeg_dimensions(message.data)
            self.compressed.publish(message)
            if self.raw.get_subscription_count():
                width, height, step, data = decode_rgb(message.data)
                raw = Image(header=message.header, width=width, height=height, encoding='rgb8',
                            is_bigendian=0, step=step, data=data)
                self.raw.publish(raw); self.decoded += 1
            try:
                values = camera_info_values(width, height, self.calibration)
                self.calibrated = self.calibration is not None
            except ValueError as error:
                self.calibrated = False
                values = camera_info_values(width, height)
                self.last_error = str(error)
            self.info.publish(CameraInfo(header=message.header, **values))
            self.accepted += 1; self.last_frame = time.monotonic()
        except (ValueError, TypeError, RuntimeError) as error:
            self.dropped += 1; self.last_error = str(error)
            self.get_logger().warning('Dropped camera frame: ' + str(error))

    def on_metadata(self, message):
        try:
            if len(message.data) > 4096: return
            value = json.loads(message.data)
            if isinstance(value, dict) and value.get('schema_version') == 1:
                self.metadata = value
        except (ValueError, TypeError):
            pass

    def on_state(self, message):
        try:
            if len(message.data) > 65536: return
            value = json.loads(message.data)
            if isinstance(value, dict) and value.get('schema_version') == 1:
                self.last_state = value; self.last_state_time = time.monotonic()
        except (ValueError, TypeError):
            pass

    def status(self):
        now = time.monotonic()
        streaming = self.last_frame is not None and now-self.last_frame <= 3.5
        online = now-self.last_state_time <= 3.5 and self.last_state.get('online', False)
        def diagnostic(name, level, message, values):
            return DiagnosticStatus(name=name, hardware_id='pepper_vision', level=level, message=message,
                                    values=[KeyValue(key=str(k), value=str(v)) for k,v in values.items()])
        array = DiagnosticArray()
        array.header.stamp = self.get_clock().now().to_msg()
        array.status = [
            diagnostic('pepper_vision/rgb', DiagnosticStatus.OK if streaming else DiagnosticStatus.WARN,
                       'RGB streaming' if streaming else 'No recent RGB frame',
                       dict(accepted=self.accepted, dropped=self.dropped, decoded=self.decoded,
                            tablet_online=online, last_error=self.last_error,
                            timestamp_source='tablet_capture_result_received', **{'metadata': json.dumps(self.metadata)})),
            diagnostic('pepper_vision/depth', DiagnosticStatus.WARN, 'Raw depth unavailable through public QiSDK 1.7.5',
                       dict(available=False, simulated=False)),
            diagnostic('pepper_vision/calibration', DiagnosticStatus.OK if self.calibrated else DiagnosticStatus.WARN,
                       'Real calibration loaded' if self.calibrated else 'Uncalibrated CameraInfo: K[0]=0', {})]
        self.diagnostics.publish(array)


def main(args=None):
    rclpy.init(args=args)
    node = VisionNode()
    try:
        rclpy.spin(node)
    except (KeyboardInterrupt, ExternalShutdownException):
        pass
    finally:
        node.destroy_node()
        if rclpy.ok(): rclpy.shutdown()
