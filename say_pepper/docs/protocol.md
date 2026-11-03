# Speech protocol and extension points

## Transport and discovery

The app owns `ros2embedded/7/pepper_1/config`. It subscribes to the three control/data topics, waits for successful SUBACK, publishes the full retained configuration, and waits for an accepted acknowledgement matching connection, domain and configuration UUID. It republishes the same snapshot every three seconds; acknowledgements renew an eight-second host lease. No new transport or ROS binding types are needed.

Config bindings use the existing uppercase `value_type: "JSON"` enum. Data envelopes use lowercase `value_type: "json"`. All four values are structured JSON mapped by the existing bridge into `std_msgs/msg/String`:

| Identifier | Direction | Relative ROS topic |
|---|---|---|
| `pepper.speech.command` | ROS_TO_DEVICE | `speech/command` |
| `pepper.speech.event` | DEVICE_TO_ROS | `speech/event` |
| `pepper.state` | DEVICE_TO_ROS | `state` |
| `pepper.capabilities` | DEVICE_TO_ROS | `capabilities` |

The prefix is `/ros2embedded/domain_7/connection_pepper_5f1`. State and capabilities include the app's random session UUID; each app process gets a different session. All telemetry and commands use QoS 1 without retention. MQTT subscriptions use clean sessions. An unretained offline last will marks unexpected disconnects; the ROS adapter also expires state after 3.5 seconds.

## Speech command

The ROS adapter publishes this JSON object as the String message body. The existing bridge supplies the outer MQTT envelope:

```json
{
  "schema_version": 1,
  "op": "say",
  "goal_id": "f7587de0-4527-40de-b03b-8b3997f5bc82",
  "session_id": "e3d17301-a3cd-4b04-b4b8-78286affb7ca",
  "text": "Hello from ROS 2",
  "timeout_ms": 30000,
  "expires_at_unix_ms": 1792915205000
}
```

The expiry above is illustrative: actual commands use the host's current time plus five seconds. Host and tablet wall clocks must agree. The `goal_id` comes from the ROS action UUID. Text is limited to 500 Unicode code points / 2000 UTF-8 bytes; timeout is 1–120 seconds. Action `timeout_sec=0` selects 30 seconds. Empty text and non-finite/out-of-range timeouts are rejected.

`op: "cancel"` uses the same goal and session IDs, with a fresh short expiry and no text. A cancel received before its say creates a tombstone so delayed delivery cannot start it. A command from a previous app session or an expired/retained command is ignored. QoS duplicates replay the stored status and do not run speech twice. History holds at most 256 recent IDs for at least three minutes and never evicts an active goal. With full history, new goals are rejected until records expire.

## Events and results

Events carry schema, session, goal ID, timestamp, a state and a short message. Text and credentials are not included. The app replays latest cached events after reconnect; the adapter correlates goal/session and ignores regressions or duplicates after a terminal event.

Normal: `accepted → running → succeeded`.

Stop: `cancel_requested → canceled`, `failed`, or `timed_out`, depending on what requested the stop. Construction uses `SayBuilder.buildAsync()` and execution uses `Say.async().run()`. The controller cancels either stage, handles completion before a late start notification, and retains its busy slot until QiSDK confirms completion. A busy or disabled device rejects a new goal rather than queuing it.

After five seconds without SDK stop confirmation, the tablet emits `cancel_unconfirmed`, disables remote speech, and **keeps the speech slot occupied**. It releases that slot only after the pending SDK future completes. The action adapter aborts uncertain results and blocks new requests until fresh tablet state confirms idle and enabled (or a new app session appears).

The adapter independently enforces the action deadline. If it cannot obtain a tablet terminal event within five seconds of requesting cancellation, it returns `outcome_unknown`; it never reports a successful stop based only on MQTT delivery. A completed speech racing a cancellation can still legitimately finish `succeeded`.

## Lifecycle and concurrency

The tablet serializes SDK operations and callbacks through the main looper without blocking it on speech. The pure controller serializes state transitions. Foreground, focus, bridge lease and the local enable switch gate admission. Focus loss, app pause and transport loss stop speech; reconnection does not re-enable it. There is no Android background robot-control service.

The ROS adapter uses a reentrant callback group and a four-thread executor so the executing action's wait cannot block device events, state updates or cancellation. It reserves admission before executing a goal to reject simultaneous clients. The ROS action remains responsive while the device performs the action.

A killed process cannot guarantee that an in-flight physical action has already stopped. Tablet-side deadlines, focus lifecycle and broker disconnect detection provide independent stop requests. Shutdown cancellation is best effort; a missing final acknowledgement is reported as uncertain.

## Adding other QiSDK capabilities later

Use a distinct typed ROS action/service and explicit command schema for each capability. Reuse discovery, session/correlation IDs, presence, expiry and acknowledgements. Add a QiSDK executor behind the tablet controller and advertise only implemented capabilities. Motion, animation, camera and listening need their own resource ownership, focus handling and permission/lifecycle review. Keep this logic in `say_pepper`; generic topic discovery remains in unchanged `ros2embedded`.

The current app advertises speech=true and motion=false. It is not a Nav2 base driver, full NAOqi/ROS compatibility layer, or arbitrary QiSDK proxy.

## References

- [Existing bridge contract](../../ros2embedded/docs/device_configuration.md) and [payload encoder](../../ros2embedded/ros2/connection_handlers/src/payload.cpp), inspected read-only.
- [QiSDK focus lifecycle](https://qisdk.softbankrobotics.com/sdk/doc/pepper-sdk/ch2_principles/focus_lifecycle.html): foreground robot focus governs action availability and cleanup.
- [Pepper 2.9.5 release notes](https://d9d4fpk5grhfv.cloudfront.net/developer-center/articles/Release-note-2.9.5/index.html): QiSDK 1.7.5 / robot API 7 compatibility.
- [ROS 2 action design](https://design.ros2.org/articles/actions.html): goals, feedback, terminal results and cancellation.
- [Humble action state machine](https://github.com/ros2/rcl/blob/humble/rcl_action/src/rcl_action/goal_state_machine.c): completion and abort transitions during cancellation.
