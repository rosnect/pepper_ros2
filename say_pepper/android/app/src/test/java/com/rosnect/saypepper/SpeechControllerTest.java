package com.rosnect.saypepper;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

public class SpeechControllerTest {
    static class Clock implements SpeechController.Clock {
        long now=1_000_000;
        public long wall() { return now; }
        public long monotonic() { return now; }
    }
    static class Backend implements SpeechController.Backend {
        int starts, cancels;
        Completion completion;
        Runnable started;
        public void start(String text, Runnable started, Completion completion) {
            starts++; this.started=started; this.completion=completion;
        }
        public void cancel() { cancels++; }
    }
    Clock clock; Backend backend; SpeechController controller; List<JSONObject> events;
    @Before public void setup() {
        clock=new Clock(); backend=new Backend(); events=new ArrayList<>();
        controller=new SpeechController(clock,backend,events::add);
        controller.focus(true); controller.connected(true); controller.enabled(true);
    }
    JSONObject say(String id) {
        return Wire.object("schema_version",1,"op","say","goal_id",id,"session_id",controller.session,
                "text","Hello Pepper","timeout_ms",1000,"expires_at_unix_ms",clock.now+5000);
    }
    String id() { return UUID.randomUUID().toString(); }
    String last() { return events.get(events.size()-1).optString("state"); }
    @Test public void successfulSpeechReportsActualCompletion() {
        controller.command(say(id())); assertEquals("accepted",last()); assertFalse(controller.isReady());
        backend.started.run(); assertEquals("running",last());
        backend.completion.finished(false,null); assertEquals("succeeded",last()); assertTrue(controller.isReady());
    }
    @Test public void duplicatesNeverRepeatSpeech() {
        JSONObject command=say(id()); controller.command(command); controller.command(command);
        backend.completion.finished(false,null); controller.command(command);
        assertEquals(1,backend.starts); assertEquals("succeeded",last());
    }
    @Test public void cancelBeforeSayLeavesTombstone() throws Exception {
        String id=id(); JSONObject cancel=say(id); cancel.put("op","cancel");
        controller.command(cancel); controller.command(say(id));
        assertEquals("canceled",last()); assertEquals(0,backend.starts);
    }
    @Test public void cancelWaitsForBackendConfirmation() throws Exception {
        String id=id(); controller.command(say(id)); JSONObject cancel=say(id); cancel.put("op","cancel");
        controller.command(cancel); assertEquals("cancel_requested",last()); assertEquals(1,backend.cancels);
        assertFalse(controller.isReady()); backend.completion.finished(true,null);
        assertEquals("canceled",last()); assertTrue(controller.isReady());
    }
    @Test public void timeoutRequestsStopAndKeepsSlotUntilConfirmation() {
        controller.command(say(id())); clock.now+=1001; controller.tick();
        assertEquals("cancel_requested",last()); assertEquals(1,backend.cancels);
        backend.completion.finished(true,null); assertEquals("timed_out",last());
    }
    @Test public void unconfirmedStopBlocksOtherCommands() {
        controller.command(say(id())); controller.localStop(); clock.now+=5001; controller.tick();
        assertEquals("cancel_unconfirmed",last()); controller.enabled(true);
        controller.command(say(id())); assertEquals("rejected",last()); assertEquals(1,backend.starts);
        backend.completion.finished(true,null); assertTrue(controller.isReady());
    }
    @Test public void focusLossCancelsAndRequiresReenable() {
        controller.command(say(id())); controller.focus(false);
        assertEquals(1,backend.cancels); assertFalse(controller.isEnabled());
        backend.completion.finished(true,null); assertEquals("failed",last());
        controller.focus(true); assertFalse(controller.isReady()); controller.enabled(true); assertTrue(controller.isReady());
    }
    @Test public void disconnectCancelsAndDoesNotRearmAutomatically() {
        controller.command(say(id())); controller.connected(false); backend.completion.finished(true,null);
        controller.connected(true); assertFalse(controller.isReady()); assertFalse(controller.isEnabled());
    }
    @Test public void expiredOrWrongSessionCommandsAreIgnored() throws Exception {
        JSONObject old=say(id()); old.put("expires_at_unix_ms",clock.now-1); controller.command(old);
        JSONObject other=say(id()); other.put("session_id",id()); controller.command(other);
        assertEquals(0,backend.starts); assertTrue(events.isEmpty());
    }
    @Test public void invalidAndOverlappingSpeechAreRejected() throws Exception {
        JSONObject empty=say(id()); empty.put("text","   "); controller.command(empty);
        assertEquals("rejected",last()); controller.command(say(id())); controller.command(say(id()));
        assertEquals("rejected",last()); assertEquals(1,backend.starts);
    }
    @Test public void lateStartedCallbackCannotUndoCancellation() {
        controller.command(say(id())); controller.localStop(); backend.started.run();
        assertEquals("cancel_requested",last());
    }
    @Test public void qosReplayRetainsTerminalOutcome() {
        controller.command(say(id())); backend.completion.finished(false,"failure");
        events.clear(); controller.replay(); assertEquals("failed",last());
    }
    @Test public void historyIsBoundedAndActiveGoalCannotBeEvicted() {
        controller.command(say(id()));
        for(int i=0;i<270;i++) controller.command(say(id()));
        assertEquals(1,backend.starts); assertEquals("rejected",last());
        backend.completion.finished(false,null); clock.now+=180001;
        controller.command(say(id())); assertEquals(2,backend.starts);
    }
    @Test public void wireMatchesExistingBridgeJsonContract() throws Exception {
        JSONObject envelope=Wire.envelope(Wire.STATE,controller.state());
        assertEquals("json",envelope.getString("value_type"));
        assertEquals(7,envelope.getInt("domain_id"));
        JSONObject config=Wire.configuration("test-id");
        assertEquals("PROTOCOL_MQTT",config.getString("protocol"));
        assertEquals("JSON",config.getJSONArray("bindings").getJSONObject(0).getString("value_type"));
        assertEquals("ROS_TO_DEVICE",config.getJSONArray("bindings").getJSONObject(0).getString("direction"));
        assertEquals(4,config.getJSONArray("bindings").length());
    }
}
