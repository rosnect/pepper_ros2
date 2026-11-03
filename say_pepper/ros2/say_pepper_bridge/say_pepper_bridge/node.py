"""ROS action server around the existing ros2embedded String topic bindings."""
import json
import threading
import time
import uuid

import rclpy
from rclpy.action import ActionServer, CancelResponse, GoalResponse
from rclpy.callback_groups import ReentrantCallbackGroup
from rclpy.executors import MultiThreadedExecutor, ExternalShutdownException
from rclpy.node import Node
from rclpy.qos import QoSProfile, ReliabilityPolicy, DurabilityPolicy
from std_msgs.msg import String
from say_pepper_interfaces.action import Say
from .protocol import DEFAULT_NAMESPACE, Exchange, Presence, command, decode, validate_goal


class SayPepper(Node):
    def __init__(self, **kwargs):
        super().__init__('say_pepper', **kwargs)
        self.declare_parameter('bridge_namespace', DEFAULT_NAMESPACE)
        namespace = self.get_parameter('bridge_namespace').value.rstrip('/')
        self.declare_parameter('action_name', '/pepper/say')
        self.lock = threading.RLock()
        self.changed = threading.Condition(self.lock)
        self.presence = Presence()
        self.reserved = False
        self.exchange = None
        self.accepted_session = ''
        self.closed = False
        self.blocked_session = ''
        self.group = ReentrantCallbackGroup()
        qos = QoSProfile(depth=10, reliability=ReliabilityPolicy.RELIABLE,
                         durability=DurabilityPolicy.VOLATILE)
        self.commands = self.create_publisher(String, namespace + '/speech/command', qos)
        self.create_subscription(String, namespace + '/state', self.on_state, qos, callback_group=self.group)
        self.create_subscription(String, namespace + '/speech/event', self.on_event, qos, callback_group=self.group)
        self.server = ActionServer(self, Say, self.get_parameter('action_name').value,
                                   execute_callback=self.execute, goal_callback=self.goal,
                                   cancel_callback=self.cancel, callback_group=self.group)
        self.get_logger().info('Speech action ready; awaiting a fresh enabled tablet at ' + namespace)

    def on_state(self, message):
        try:
            value = decode(message.data)
            with self.changed:
                self.presence.update(value, time.monotonic())
                # After an unconfirmed stop, only confirmed idle + remote re-enablement or
                # a new app session allows another action. Device itself also retains its busy lock.
                if self.blocked_session and (self.presence.session != self.blocked_session or
                        (value['ready'] and not value.get('active_goal_id'))):
                    self.blocked_session = ''
                self.changed.notify_all()
        except (ValueError, TypeError):
            self.get_logger().warning('Ignoring invalid tablet state')

    def on_event(self, message):
        try:
            value = decode(message.data)
            with self.changed:
                if self.exchange and self.exchange.event(value):
                    self.changed.notify_all()
        except (ValueError, TypeError):
            self.get_logger().warning('Ignoring invalid tablet event')

    def goal(self, request):
        try:
            validate_goal(request.text, request.timeout_sec)
        except (ValueError, UnicodeError):
            return GoalResponse.REJECT
        with self.lock:
            if (self.reserved or self.blocked_session or not self.presence.ready(time.monotonic())
                    or self.commands.get_subscription_count() == 0 or self.closed):
                self.get_logger().warning('Speech rejected: tablet unavailable, disabled, or busy')
                return GoalResponse.REJECT
            self.reserved = True
            self.accepted_session = self.presence.session
            return GoalResponse.ACCEPT

    def cancel(self, handle):
        # rclpy changes the goal state AFTER this callback returns. The execution loop
        # observes is_cancel_requested, then asks the tablet for confirmed cancellation.
        return CancelResponse.ACCEPT

    def publish_command(self, operation, exchange, text):
        value = command(operation, exchange.goal_id, exchange.session, text=text, timeout=exchange.timeout)
        self.commands.publish(String(data=json.dumps(value, ensure_ascii=False, separators=(',', ':'))))

    def execute(self, handle):
        timeout = validate_goal(handle.request.text, handle.request.timeout_sec)
        goal_id = str(uuid.UUID(bytes=bytes(handle.goal_id.uuid)))
        feedback_state = ''
        with self.changed:
            exchange = Exchange(goal_id, self.accepted_session, timeout, time.monotonic())
            self.exchange = exchange
            if handle.is_cancel_requested:
                exchange.cancel('canceled', time.monotonic())
            # Re-check state after admission; focus could have changed before execution.
            exchange.poll(time.monotonic(), self.presence.ready(time.monotonic()),
                          self.presence.session == exchange.session)
            self.publish_command('cancel' if exchange.cancel_started is not None else 'say',
                                 exchange, handle.request.text)
        try:
            while rclpy.ok():
                with self.changed:
                    if self.closed:
                        exchange.state = 'outcome_unknown'
                        exchange.message = 'Adapter shutting down; check tablet to confirm stop'
                        exchange.terminal = True
                    if handle.is_cancel_requested:
                        exchange.cancel('canceled', time.monotonic())
                    operation = exchange.poll(time.monotonic(), self.presence.alive(time.monotonic()),
                                              self.presence.session == exchange.session)
                    if operation:
                        self.publish_command(operation, exchange, handle.request.text)
                    if exchange.state != feedback_state:
                        handle.publish_feedback(Say.Feedback(state=exchange.state, message=exchange.message))
                        feedback_state = exchange.state
                    if exchange.terminal:
                        break
                    self.changed.wait(timeout=0.1)
            with self.lock:
                state = exchange.state if exchange.terminal else 'outcome_unknown'
                # A canceled tablet event confirms stop; preserve a host deadline/disconnect reason.
                if state == 'canceled' and exchange.cancel_reason in ('timed_out', 'disconnected'):
                    state = exchange.cancel_reason
                if state in ('cancel_unconfirmed', 'outcome_unknown'):
                    self.blocked_session = exchange.session
                result = Say.Result(success=state == 'succeeded', state=state, message=exchange.message)
            if state == 'succeeded':
                handle.succeed()
            elif state == 'canceled' and handle.is_cancel_requested:
                handle.canceled()
            else:
                handle.abort()
            return result
        finally:
            with self.lock:
                self.exchange = None
                self.reserved = False

    def shutdown_speech(self):
        with self.changed:
            self.closed = True
            if self.exchange and not self.exchange.terminal and rclpy.ok():
                self.publish_command('cancel', self.exchange, '')
            self.changed.notify_all()


def main(args=None):
    rclpy.init(args=args)
    node = SayPepper()
    executor = MultiThreadedExecutor(num_threads=4)
    executor.add_node(node)
    try:
        executor.spin()
    except (KeyboardInterrupt, ExternalShutdownException):
        pass
    finally:
        try:
            node.shutdown_speech()
        finally:
            executor.shutdown(timeout_sec=2.0)
            node.destroy_node()
            if rclpy.ok():
                rclpy.shutdown()
