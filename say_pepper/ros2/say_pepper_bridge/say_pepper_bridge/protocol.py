"""Pure protocol logic; independent of ROS so failure handling can be tested locally."""
import json
import math
import time
import uuid
from dataclasses import dataclass

TERMINAL = frozenset({'succeeded', 'failed', 'canceled', 'rejected', 'timed_out', 'cancel_unconfirmed'})
EVENT_STATES = TERMINAL | {'accepted', 'running', 'cancel_requested'}
DEFAULT_NAMESPACE = '/ros2embedded/domain_7/connection_pepper_5f1'


def decode(text):
    if not isinstance(text, str) or len(text.encode('utf-8')) > 65536:
        raise ValueError('Message too large')
    result = json.loads(text)
    if not isinstance(result, dict) or result.get('schema_version') != 1:
        raise ValueError('Unsupported message schema')
    return result


def validate_goal(text, timeout):
    if not isinstance(text, str) or not text.strip() or len(text) > 500 or len(text.encode('utf-8')) > 2000:
        raise ValueError('Speech must contain 1..500 characters and at most 2000 UTF-8 bytes')
    timeout = float(timeout)
    if timeout == 0:
        timeout = 30.0
    if not math.isfinite(timeout) or not 1 <= timeout <= 120:
        raise ValueError('Timeout must be 0 (default), or between 1 and 120 seconds')
    return timeout


def valid_uuid(value):
    try:
        return str(uuid.UUID(value)) == value
    except (ValueError, TypeError, AttributeError):
        return False


def command(op, goal_id, session_id, *, text='', timeout=30, wall=time.time):
    if op not in ('say', 'cancel') or not valid_uuid(goal_id) or not valid_uuid(session_id):
        raise ValueError('Invalid operation or correlation IDs')
    value = dict(schema_version=1, op=op, goal_id=goal_id, session_id=session_id,
                 expires_at_unix_ms=int(wall() * 1000) + 5000)
    if op == 'say':
        timeout = validate_goal(text, timeout)
        value.update(text=text, timeout_ms=int(timeout * 1000))
    return value


@dataclass
class Presence:
    session: str = ''
    received: float = float('-inf')
    value: dict = None

    def update(self, value, now):
        if not valid_uuid(value.get('session_id')):
            raise ValueError('Invalid device session')
        required = ('online', 'robot_focus', 'bridge_ready', 'remote_enabled', 'ready')
        if any(type(value.get(k)) is not bool for k in required):
            raise ValueError('Invalid device state')
        self.session, self.received, self.value = value['session_id'], now, value

    def alive(self, now):
        return self.value is not None and now - self.received <= 3.5 and self.value['online']

    def ready(self, now):
        return self.alive(now) and all(self.value[k] for k in
                                      ('robot_focus', 'bridge_ready', 'remote_enabled', 'ready'))


class Exchange:
    """Track a single action. Out-of-order/replayed nonterminal events cannot undo completion."""
    def __init__(self, goal_id, session, timeout, now):
        self.goal_id, self.session, self.timeout = goal_id, session, timeout
        self.deadline = now + timeout
        self.state, self.message = 'sent', 'Waiting for tablet acknowledgement'
        self.terminal = False
        self.cancel_reason = None
        self.cancel_started = None
        self.next_send = now + 0.5
        self.acknowledged = False

    def event(self, value):
        if (self.terminal or value.get('goal_id') != self.goal_id
                or value.get('session_id') != self.session or value.get('state') not in EVENT_STATES):
            return False
        state = value['state']
        # MQTT can replay accepted/running after cancellation or running.
        if state not in TERMINAL:
            rank = {'sent': 0, 'accepted': 1, 'running': 2, 'cancel_requested': 3}
            if rank.get(state, -1) < rank.get(self.state, -1):
                return False
        self.acknowledged = True
        self.state, self.message = state, str(value.get('message', ''))[:300]
        self.terminal = state in TERMINAL
        return True

    def cancel(self, reason, now):
        if not self.terminal and self.cancel_started is None:
            self.cancel_reason, self.cancel_started = reason, now
            self.next_send = now

    def poll(self, now, alive, same_session):
        if self.terminal:
            return None
        if not alive or not same_session:
            self.cancel('disconnected', now)
        if now >= self.deadline:
            self.cancel('timed_out', now)
        if self.cancel_started is not None:
            if now - self.cancel_started >= 5:
                self.state = 'outcome_unknown'
                self.message = 'No confirmed tablet stop; robot may still be speaking. Check tablet before retrying.'
                self.terminal = True
                return None
            if now >= self.next_send:
                self.next_send = now + 0.5
                return 'cancel'
        elif not self.acknowledged and now >= self.next_send:
            # Retry with the SAME id and a newly short expiry; device deduplicates it.
            self.next_send = now + 0.5
            return 'say'
        return None
