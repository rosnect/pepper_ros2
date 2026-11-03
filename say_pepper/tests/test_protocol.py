import json
import math
import sys
import unittest
import uuid
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'ros2/say_pepper_bridge'))
from say_pepper_bridge.protocol import Presence, Exchange, command, decode, validate_goal


def uid():
    return str(uuid.uuid4())


def ready(session=None):
    return dict(schema_version=1, session_id=session or uid(), online=True, robot_focus=True,
                bridge_ready=True, remote_enabled=True, ready=True, active_goal_id='')


class ProtocolTest(unittest.TestCase):
    def test_limits(self):
        self.assertEqual(validate_goal('Hello', 0), 30)
        self.assertEqual(validate_goal('a' * 500, 120), 120)
        for text, timeout in [('', 30), (' ', 30), ('a'*501, 30), ('hi', math.nan),
                              ('hi', math.inf), ('hi', -1), ('hi', .5), ('hi', 121)]:
            with self.subTest(text=text[:10], timeout=timeout), self.assertRaises(ValueError):
                validate_goal(text, timeout)

    def test_command_correlation_and_expiry(self):
        goal, session = uid(), uid()
        value = command('say', goal, session, text='Hello', timeout=1, wall=lambda: 100)
        self.assertEqual(value['expires_at_unix_ms'], 105000)
        self.assertEqual(value['timeout_ms'], 1000)
        self.assertEqual(value['goal_id'], goal)
        self.assertEqual(value['session_id'], session)
        self.assertNotIn('text', command('cancel', goal, session))
        with self.assertRaises(ValueError):
            command('move', goal, session)
        with self.assertRaises(ValueError):
            command('say', 'bad-id', session, text='Hi')

    def test_decoder_rejects_nonobjects_and_oversized_payloads(self):
        self.assertEqual(decode('{"schema_version":1}'), {'schema_version': 1})
        for text in ['[]', 'null', '{}', '{"schema_version":2}', 'x'*65537]:
            with self.subTest(text=text[:30]), self.assertRaises(ValueError):
                decode(text)

    def test_presence_requires_recent_heartbeat_and_enabled_focus(self):
        presence = Presence()
        self.assertFalse(presence.ready(0))
        value = ready(); presence.update(value, 10)
        self.assertTrue(presence.ready(13.5)); self.assertFalse(presence.ready(13.51))
        for key in ['online', 'robot_focus', 'bridge_ready', 'remote_enabled', 'ready']:
            v = ready(); v[key] = False; presence.update(v, 20)
            self.assertFalse(presence.ready(20))

    def test_malformed_presence_does_not_replace_valid_state(self):
        presence = Presence(); value = ready(); presence.update(value, 10)
        for invalid in [dict(value, ready='true'), dict(value, session_id='bad')]:
            with self.assertRaises(ValueError): presence.update(invalid, 20)
            self.assertEqual(presence.received, 10)

    def test_retries_stop_after_ack(self):
        x = Exchange(uid(), uid(), 30, 0)
        self.assertIsNone(x.poll(.49, True, True))
        self.assertEqual(x.poll(.5, True, True), 'say')
        x.event(dict(goal_id=x.goal_id, session_id=x.session, state='accepted'))
        self.assertIsNone(x.poll(1.1, True, True))

    def test_wrong_goal_or_restart_session_cannot_complete_action(self):
        x = Exchange(uid(), uid(), 30, 0)
        self.assertFalse(x.event(dict(goal_id=uid(), session_id=x.session, state='succeeded')))
        self.assertFalse(x.event(dict(goal_id=x.goal_id, session_id=uid(), state='succeeded')))
        self.assertFalse(x.terminal)

    def test_terminal_event_is_final_and_deduplicated(self):
        x = Exchange(uid(), uid(), 30, 0)
        event = dict(goal_id=x.goal_id, session_id=x.session, state='succeeded')
        self.assertTrue(x.event(event)); self.assertTrue(x.terminal)
        self.assertFalse(x.event(dict(event, state='running')))
        self.assertEqual(x.state, 'succeeded')

    def test_replayed_accepted_event_does_not_undo_running(self):
        x = Exchange(uid(), uid(), 30, 0)
        value = dict(goal_id=x.goal_id, session_id=x.session, state='running')
        x.event(value); self.assertFalse(x.event(dict(value, state='accepted')))
        self.assertEqual(x.state, 'running')

    def test_deadline_sends_cancel_before_reporting_unknown(self):
        x = Exchange(uid(), uid(), 1, 0)
        self.assertEqual(x.poll(1, True, True), 'cancel')
        self.assertFalse(x.terminal)
        self.assertEqual(x.cancel_reason, 'timed_out')
        self.assertIsNone(x.poll(6, True, True))
        self.assertEqual(x.state, 'outcome_unknown'); self.assertTrue(x.terminal)

    def test_cancel_waits_for_confirmation_and_retries(self):
        x = Exchange(uid(), uid(), 30, 0); x.cancel('canceled', 1)
        self.assertEqual(x.poll(1, True, True), 'cancel')
        self.assertIsNone(x.poll(1.1, True, True))
        self.assertEqual(x.poll(1.5, True, True), 'cancel')
        x.event(dict(goal_id=x.goal_id, session_id=x.session, state='canceled'))
        self.assertTrue(x.terminal); self.assertEqual(x.state, 'canceled')

    def test_restart_or_disconnect_requests_stop(self):
        for alive, same in [(False, True), (True, False)]:
            x = Exchange(uid(), uid(), 30, 0)
            self.assertEqual(x.poll(1, alive, same), 'cancel')
            self.assertEqual(x.cancel_reason, 'disconnected')

    def test_cancel_unconfirmed_is_explicit_terminal_failure(self):
        x = Exchange(uid(), uid(), 30, 0)
        x.event(dict(goal_id=x.goal_id, session_id=x.session, state='cancel_unconfirmed'))
        self.assertTrue(x.terminal); self.assertEqual(x.state, 'cancel_unconfirmed')


if __name__ == '__main__':
    unittest.main()
