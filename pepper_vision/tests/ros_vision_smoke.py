"""Linux ROS test with isolated local image topics. No robot, broker or bridge changes."""
import threading
import time
import unittest
import uuid
from pathlib import Path

import rclpy
from rclpy.executors import MultiThreadedExecutor
from rclpy.node import Node
from rclpy.parameter import Parameter
from sensor_msgs.msg import CompressedImage, Image, CameraInfo
from diagnostic_msgs.msg import DiagnosticArray
from pepper_vision_bridge.node import VisionNode
from pepper_vision_bridge.imaging import OPTICAL_FRAME


class VisionSmoke(unittest.TestCase):
    def setUp(self):
        rclpy.init(args=[])
        self.prefix='/pepper_vision_test_'+uuid.uuid4().hex
        self.source=self.prefix+'/source';self.output=self.prefix+'/output'
        self.node=VisionNode(parameter_overrides=[Parameter('bridge_namespace',value=self.source),
                                                 Parameter('output_namespace',value=self.output)])
        self.peer=Node('vision_test_peer')
        self.publisher=self.peer.create_publisher(CompressedImage,self.source+'/camera/color/image_raw/compressed',1)
        self.raw=[];self.info=[];self.compressed=[];self.diagnostics=[]
        self.peer.create_subscription(Image,self.output+'/color/image_raw',self.raw.append,1)
        self.peer.create_subscription(CameraInfo,self.output+'/color/camera_info',self.info.append,1)
        self.peer.create_subscription(CompressedImage,self.output+'/color/image_raw/compressed',self.compressed.append,1)
        self.peer.create_subscription(DiagnosticArray,self.output+'/diagnostics',self.diagnostics.append,5)
        self.executor=MultiThreadedExecutor(num_threads=3)
        self.executor.add_node(self.node);self.executor.add_node(self.peer)
        self.thread=threading.Thread(target=self.executor.spin,daemon=True);self.thread.start()
        self.until(lambda:self.publisher.get_subscription_count()>0 and self.node.raw.get_subscription_count()>0
                   and self.node.info.get_subscription_count()>0 and self.node.compressed.get_subscription_count()>0)

    def until(self,condition,timeout=5):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            if condition():return
            time.sleep(.01)
        self.fail('Timed out waiting for ROS camera callbacks')

    def frame(self):
        message=CompressedImage()
        message.header.stamp=self.peer.get_clock().now().to_msg()
        message.header.frame_id=OPTICAL_FRAME
        message.format='jpeg'
        message.data=(Path(__file__).parent/'fixtures/red.jpg').read_bytes()
        return message

    def test_native_compressed_rgb_and_uncalibrated_info(self):
        frame=self.frame();self.publisher.publish(frame)
        self.until(lambda:self.raw and self.info and self.compressed)
        self.assertEqual(self.raw[0].encoding,'rgb8')
        self.assertEqual((self.raw[0].width,self.raw[0].height,self.raw[0].step),(64,48,192))
        self.assertEqual(self.raw[0].header,frame.header)
        self.assertEqual(self.info[0].header,frame.header)
        self.assertEqual(self.info[0].k[0],0)
        self.assertEqual(bytes(self.compressed[0].data),bytes(frame.data))

    def test_malformed_and_stale_frames_are_dropped(self):
        bad=self.frame();bad.data=b'not JPEG';self.publisher.publish(bad)
        self.until(lambda:self.node.dropped>=1)
        stale=self.frame();stale.header.stamp.sec-=20;self.publisher.publish(stale)
        self.until(lambda:self.node.dropped>=2)
        self.assertFalse(self.raw)

    def test_depth_is_reported_unavailable_without_fake_depth_publisher(self):
        self.until(lambda:bool(self.diagnostics))
        status=[s for s in self.diagnostics[-1].status if s.name=='pepper_vision/depth'][0]
        values={v.key:v.value for v in status.values}
        self.assertEqual(values['available'],'False')
        self.assertEqual(values['simulated'],'False')
        self.assertFalse(any(name.startswith(self.output+'/depth') for name,_ in self.peer.get_topic_names_and_types()))

    def tearDown(self):
        self.executor.shutdown(timeout_sec=3);self.thread.join(timeout=3)
        self.peer.destroy_node();self.node.destroy_node();rclpy.shutdown()


if __name__=='__main__':unittest.main()
