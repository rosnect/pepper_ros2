"""Lab speech client with feedback and explicit ROS action cancellation."""
import argparse
import sys
import time
import rclpy
from rclpy.action import ActionClient
from rclpy.node import Node
from say_pepper_interfaces.action import Say
from .protocol import validate_goal


def main(args=None):
    parser = argparse.ArgumentParser(description='Speak through Pepper QiSDK via ROS 2')
    parser.add_argument('text')
    parser.add_argument('--timeout', type=float, default=30)
    parser.add_argument('--cancel-after', type=float)
    parser.add_argument('--action', default='/pepper/say')
    options = parser.parse_args(args)
    timeout = validate_goal(options.text, options.timeout)
    rclpy.init(args=[])
    node = Node('pepper_say_client')
    client = ActionClient(node, Say, options.action)
    goal = None
    result = None
    try:
        if not client.wait_for_server(timeout_sec=5):
            raise RuntimeError('Speech action server is not running')
        future = client.send_goal_async(Say.Goal(text=options.text, timeout_sec=timeout),
                                       feedback_callback=lambda m: print(m.feedback.state + ': ' + m.feedback.message))
        rclpy.spin_until_future_complete(node, future, timeout_sec=5)
        if not future.done():
            raise RuntimeError('No goal acknowledgement; check tablet before retrying')
        goal = future.result()
        if not goal.accepted:
            raise RuntimeError('Goal rejected. Check tablet focus, remote speech switch and bridge state.')
        result = goal.get_result_async()
        began = time.monotonic()
        canceled = False
        while not result.done():
            rclpy.spin_once(node, timeout_sec=0.1)
            elapsed = time.monotonic() - began
            if options.cancel_after is not None and elapsed >= options.cancel_after and not canceled:
                goal.cancel_goal_async()
                canceled = True
                print('Cancellation requested; waiting for tablet confirmation')
            if elapsed > timeout + 12:
                raise RuntimeError('No final result. Speech outcome unknown; check tablet.')
        value = result.result().result
        print(value.state + ': ' + value.message)
        return 0 if value.success or value.state == 'canceled' else 1
    except KeyboardInterrupt:
        if goal is not None and (result is None or not result.done()):
            future = goal.cancel_goal_async()
            rclpy.spin_until_future_complete(node, future, timeout_sec=2)
            print('Cancellation sent. Check tablet for confirmed stop.')
        return 130
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 1
    finally:
        client.destroy()
        node.destroy_node()
        if rclpy.ok():
            rclpy.shutdown()
