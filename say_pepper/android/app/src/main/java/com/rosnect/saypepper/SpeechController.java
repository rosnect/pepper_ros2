package com.rosnect.saypepper;

import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** All transitions are serialized, including SDK completion callbacks. Never queues speech. */
final class SpeechController {
    interface Clock { long wall(); long monotonic(); }
    interface Backend {
        interface Completion { void finished(boolean cancelled, String error); }
        void start(String text, Runnable started, Completion completion);
        void cancel();
    }
    interface Sink { void event(JSONObject event); }
    private static final long DEDUP_MS = 180_000;
    private static final int MAX_RECORDS = 256;
    private final Clock clock;
    private final Backend backend;
    private final Sink sink;
    final String session = UUID.randomUUID().toString();
    private final LinkedHashMap<String, Record> records = new LinkedHashMap<>();
    private boolean focus, connected, enabled;
    private Record active;
    private String phase = "idle";
    private static final class Record {
        final String id;
        final long created;
        JSONObject event;
        long deadline, cancelDeadline;
        String stopState;
        Record(String id, long created) { this.id=id; this.created=created; }
    }
    SpeechController(Clock clock, Backend backend, Sink sink) {
        this.clock=clock; this.backend=backend; this.sink=sink;
    }
    synchronized void focus(boolean value) {
        focus=value;
        if (!value) { enabled=false; stop("failed", "Robot focus lost"); }
    }
    synchronized void connected(boolean value) {
        connected=value;
        if (!value) { enabled=false; stop("failed", "Bridge disconnected"); }
    }
    synchronized void enabled(boolean value) {
        enabled=value && focus && connected;
        if (!enabled) stop("canceled", "Remote speech disabled locally");
    }
    synchronized void localStop() { enabled=false; stop("canceled", "Stopped on tablet"); }
    synchronized boolean isEnabled() { return enabled; }
    synchronized boolean isReady() { return connected && focus && enabled && active == null; }
    synchronized JSONObject state() {
        return Wire.object("schema_version",1,"session_id",session,"online",connected,
                "robot_focus",focus,"bridge_ready",connected,"remote_enabled",enabled,
                "ready",isReady(),"active_goal_id",active == null ? "" : active.id,
                "phase",phase,"timestamp_unix_ms",clock.wall());
    }
    synchronized void command(JSONObject c) {
        String id=c.optString("goal_id", "");
        if (!Wire.validId(id) || c.optInt("schema_version") != 1
                || !session.equals(c.optString("session_id"))) return;
        String op=c.optString("op");
        if (!op.equals("say") && !op.equals("cancel")) return;
        long expiry=c.optLong("expires_at_unix_ms",0);
        if (expiry < clock.wall() || expiry > clock.wall()+10_000) return;
        Record previous=records.get(id);
        if (previous != null) {
            if (op.equals("cancel") && previous == active) stop("canceled", "Cancellation requested");
            else if (previous.event != null) sink.event(previous.event);
            return;
        }
        prune();
        if (records.size() >= MAX_RECORDS) {
            emit(new Record(id,clock.monotonic()),"rejected","Command history full; retry later");
            return;
        }
        Record record=new Record(id,clock.monotonic());
        records.put(id,record);
        if (op.equals("cancel")) { // Tombstone: a reordered say must never run later.
            emit(record,"canceled","Canceled before speech began"); return;
        }
        String text=c.optString("text", "");
        long timeout=c.optLong("timeout_ms",0);
        if (!Wire.validText(text) || timeout < 1000 || timeout > 120000) {
            emit(record,"rejected","Invalid text or timeout"); return;
        }
        if (!isReady()) { emit(record,"rejected","Robot is busy or remote speech is not ready"); return; }
        active=record;
        record.deadline=clock.monotonic()+timeout;
        phase="accepted";
        emit(record,"accepted","Speech accepted");
        try {
            backend.start(text, () -> started(record), (cancelled,error) -> finished(record,cancelled,error));
        } catch (RuntimeException e) { finished(record,false,"QiSDK could not start speech"); }
    }
    private synchronized void started(Record record) {
        if (active != record || record.stopState != null) return;
        phase="running"; emit(record,"running","Speech started");
    }
    private synchronized void finished(Record record, boolean canceled, String error) {
        if (active != record) return;
        String state=record.stopState != null ? record.stopState
                : canceled ? "canceled" : error != null ? "failed" : "succeeded";
        active=null; phase="idle";
        emit(record,state,error != null ? error.substring(0,Math.min(error.length(),300)) : "Speech "+state);
    }
    private void stop(String state, String message) {
        if (active == null || active.stopState != null) return;
        active.stopState=state;
        active.cancelDeadline=clock.monotonic()+5000;
        phase="cancel_requested";
        emit(active,"cancel_requested",message);
        try { backend.cancel(); } catch (RuntimeException ignored) { /* watchdog keeps slot busy */ }
    }
    synchronized void tick() {
        if (active == null) return;
        if (active.stopState == null && clock.monotonic() >= active.deadline)
            stop("timed_out","Speech deadline exceeded");
        if (active != null && active.stopState != null && active.cancelDeadline != 0
                && clock.monotonic() >= active.cancelDeadline) {
            active.cancelDeadline=0;
            phase="cancel_unconfirmed";
            enabled=false;
            emit(active,"cancel_unconfirmed","QiSDK has not confirmed stop; speech remains blocked");
        }
    }
    synchronized void replay() {
        // QoS 1 does not preserve events across clean sessions. Replay deduplicated latest states.
        for (Record r : records.values()) if (r.event != null) sink.event(r.event);
    }
    private void emit(Record record, String state, String message) {
        record.event=Wire.object("schema_version",1,"session_id",session,"goal_id",record.id,
                "state",state,"message",message,"timestamp_unix_ms",clock.wall());
        sink.event(record.event);
    }
    private void prune() {
        Iterator<Map.Entry<String,Record>> it=records.entrySet().iterator();
        while (it.hasNext()) {
            Record r=it.next().getValue();
            if (r != active && clock.monotonic()-r.created > DEDUP_MS) it.remove();
        }
    }
}
