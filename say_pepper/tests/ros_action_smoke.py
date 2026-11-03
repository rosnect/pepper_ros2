"""Run on Linux after sourcing ROS + say_pepper. Fake ROS peer, no MQTT/robot required.
This checks the real rclpy action server. It never connects to a broker or Pepper.
"""
import json
import threading
import time
import unittest
import uuid

import rclpy
from action_msgs.msg import GoalStatus
from rclpy.action import ActionClient
from rclpy.executors import MultiThreadedExecutor
from rclpy.node import Node
from rclpy.parameter import Parameter
from std_msgs.msg import String
from say_pepper_interfaces.action import Say
from say_pepper_bridge.node import SayPepper


class Peer(Node):
    def __init__(self, namespace):
        super().__init__('fake_pepper')
        self.session = str(uuid.uuid4())
        self.enabled = True
        self.goal = None
        self.finish_at = 0
        self.delay = .2
        self.say_count = 0
        self.seen = set()
        self.states = self.create_publisher(String, namespace + '/state', 10)
        self.events = self.create_publisher(String, namespace + '/speech/event', 10)
        self.create_subscription(String, namespace + '/speech/command', self.command, 10)
        self.create_timer(.05, self.tick)

    def send(self, state, goal):
        self.events.publish(String(data=json.dumps(dict(schema_version=1, session_id=self.session,
                                                       goal_id=goal, state=state, message='Simulated ' + state))))

    def command(self, message):
        c = json.loads(message.data)
        if c['session_id'] != self.session:
            return
        if c['op'] == 'cancel':
            self.send('canceled', c['goal_id']); self.goal = None
        elif c['goal_id'] not in self.seen:
            self.seen.add(c['goal_id']); self.say_count += 1
            self.goal = c['goal_id']; self.finish_at = time.monotonic() + self.delay
            self.send('accepted', self.goal); self.send('running', self.goal)

    def tick(self):
        if self.goal and time.monotonic() >= self.finish_at:
            self.send('succeeded', self.goal); self.goal = None
        self.states.publish(String(data=json.dumps(dict(schema_version=1, session_id=self.session,
                online=True, robot_focus=True, bridge_ready=True, remote_enabled=self.enabled,
                ready=self.enabled and self.goal is None, active_goal_id=self.goal or ''))))


class ActionSmoke(unittest.TestCase):
    def setUp(self):
        rclpy.init(args=[])
        namespace = '/say_pepper_test_' + uuid.uuid4().hex
        self.action_name = namespace + '/say'
        self.server = SayPepper(parameter_overrides=[Parameter('bridge_namespace', value=namespace),
                                                    Parameter('action_name', value=self.action_name)])
        self.peer = Peer(namespace)
        self.client_node = Node('test_speech_client')
        self.client = ActionClient(self.client_node, Say, self.action_name)
        self.executor = MultiThreadedExecutor(num_threads=6)
        for node in (self.server, self.peer, self.client_node): self.executor.add_node(node)
        self.thread = threading.Thread(target=self.executor.spin, daemon=True); self.thread.start()
        self.assertTrue(self.client.wait_for_server(timeout_sec=5))
        self.until(lambda: self.server.presence.ready(time.monotonic())
                   and self.server.commands.get_subscription_count() > 0)

    def until(self, predicate, timeout=8):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate(): return
            time.sleep(.01)
        self.fail('Timed out waiting for ROS callback')

    def wait(self, future, timeout=8):
        self.until(future.done, timeout)
        return future.result()

    def goal(self, timeout=3):
        return self.wait(self.client.send_goal_async(Say.Goal(text='Hello test', timeout_sec=float(timeout))))

    def test_success(self):
        handle = self.goal(); self.assertTrue(handle.accepted)
        result = self.wait(handle.get_result_async())
        self.assertEqual(result.status, GoalStatus.STATUS_SUCCEEDED)
        self.assertTrue(result.result.success); self.assertEqual(self.peer.say_count, 1)

    def test_cancel_is_confirmed_and_busy_goal_rejected(self):
        self.peer.delay = 20
        handle = self.goal(); self.assertTrue(handle.accepted)
        self.until(lambda: self.peer.goal is not None)
        self.assertFalse(self.goal().accepted)
        self.assertTrue(self.wait(handle.cancel_goal_async()).goals_canceling)
        result = self.wait(handle.get_result_async())
        self.assertEqual(result.status, GoalStatus.STATUS_CANCELED)
        self.assertEqual(result.result.state, 'canceled'); self.assertIsNone(self.peer.goal)

    def test_timeout_cancels_before_abort(self):
        self.peer.delay = 20
        handle = self.goal(timeout=1)
        result = self.wait(handle.get_result_async())
        self.assertEqual(result.status, GoalStatus.STATUS_ABORTED)
        self.assertEqual(result.result.state, 'timed_out'); self.assertIsNone(self.peer.goal)

    def test_disabled_tablet_rejects_goal(self):
        self.peer.enabled = False
        self.until(lambda: not self.server.presence.ready(time.monotonic()))
        self.assertFalse(self.goal().accepted)

    def tearDown(self):
        self.server.shutdown_speech()
        self.executor.shutdown(timeout_sec=3)
        self.thread.join(timeout=3)
        self.client.destroy()
        for node in (self.client_node, self.peer, self.server): node.destroy_node()
        rclpy.shutdown()


if __name__ == '__main__':
    unittest.main()
