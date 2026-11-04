"""Measure received camera rate on the ROS computer, not the tablet's requested setting."""
import argparse
import json
import time
import rclpy
from rclpy.node import Node
from rclpy.qos import qos_profile_sensor_data
from sensor_msgs.msg import CompressedImage
from .imaging import FrameMetrics, jpeg_dimensions


def main(args=None):
    parser = argparse.ArgumentParser(description='Measure Pepper image arrival FPS and approximate age')
    parser.add_argument('--duration', type=float, default=30)
    parser.add_argument('--topic', default='/pepper/camera/color/image_raw/compressed')
    options = parser.parse_args(args)
    if not 1 <= options.duration <= 3600: parser.error('Duration must be 1..3600 seconds')
    rclpy.init(args=[]); node = Node('pepper_vision_probe')
    metrics = FrameMetrics(); invalid = [0]
    def receive(message):
        try:
            jpeg_dimensions(message.data)
            stamp = message.header.stamp.sec + message.header.stamp.nanosec/1e9
            metrics.add(time.monotonic(), (time.time()-stamp)*1000, len(message.data))
        except ValueError: invalid[0] += 1
    node.create_subscription(CompressedImage, options.topic, receive, qos_profile_sensor_data)
    deadline = time.monotonic() + options.duration
    try:
        while rclpy.ok() and time.monotonic() < deadline:
            rclpy.spin_once(node, timeout_sec=.1)
    except KeyboardInterrupt:
        pass
    finally:
        report = metrics.report(); report['invalid_frames'] = invalid[0]
        report['age_note'] = 'Tablet capture-result receipt to ROS receipt; requires synchronized clocks, not exposure latency'
        print(json.dumps(report, indent=2))
        node.destroy_node()
        if rclpy.ok(): rclpy.shutdown()
    return 0 if report['frames'] >= 2 and not invalid[0] else 1
