package com.rosnect.peppervision;

/** Single capture in flight, no catch-up bursts, no restart after lost focus/network. */
final class VisionController {
    interface Clock { long now(); }
    interface Backend {
        interface Completion { void done(VisionFrame frame,String error); }
        void capture(VisionSettings settings,Completion completion);
        void cancel();
    }
    interface Sink { boolean offer(VisionFrame frame); }
    private final Clock clock;
    private final Backend backend;
    private final Sink sink;
    private VisionSettings settings=new VisionSettings(2,640,480,70);
    private boolean ready,enabled,busy;
    private long generation,nextDue,started,total,dropped,windowStart,windowFrames;
    private int failures;
    private double observedFps;
    private String error="Camera off";
    VisionController(Clock clock,Backend backend,Sink sink) { this.clock=clock;this.backend=backend;this.sink=sink; }
    synchronized void settings(VisionSettings value) { if (enabled || busy) throw new IllegalStateException("Stop camera before changing settings");settings=value; }
    synchronized void ready(boolean value) { if (ready==value) return;ready=value;if (!value) stop(); }
    synchronized void enable(boolean value) {
        if (!value || !ready) { stop();return; }
        if (enabled) return;
        enabled=true;generation++;nextDue=clock.now();failures=0;
        windowStart=clock.now();windowFrames=0;observedFps=0;error="Waiting for first frame";
    }
    synchronized void stop() {
        if (enabled) { generation++;error="Camera off"; }
        enabled=false;observedFps=0;
        if (busy) backend.cancel();
    }
    synchronized void tick() {
        long now=clock.now();
        if (busy && enabled && now-started > 10000) {
            stop();error="Capture timeout; stop requested. Re-enable after capture finishes.";return;
        }
        if (!ready || !enabled || busy || now < nextDue) return;
        busy=true;started=now;long token=generation;
        try { backend.capture(settings,(frame,failure)->completed(token,frame,failure)); }
        catch (RuntimeException e) { completed(token,null,"Camera start failed: "+e.getClass().getSimpleName()); }
    }
    private synchronized void completed(long token,VisionFrame frame,String failure) {
        busy=false;
        if (token != generation || !enabled || !ready) { dropped++;return; }
        long now=clock.now();nextDue=Math.max(started+settings.periodMs(),now);
        if (failure != null || frame == null) {
            failures++;error=failure == null ? "Empty camera frame" : failure;
            nextDue=now+2000;
            if (failures>=3) { enabled=false;generation++;error="Camera stopped after 3 failures: "+error; }
            return;
        }
        failures=0;total++;
        if (windowFrames==0) windowStart=now;
        windowFrames++;
        long elapsed=now-windowStart;
        if (elapsed >= 2000) { observedFps=(windowFrames-1)*1000.0/elapsed;windowFrames=1;windowStart=now; }
        if (!sink.offer(frame)) dropped++;
        error="Streaming";
    }
    synchronized boolean enabled() { return enabled; }
    synchronized boolean ready() { return ready; }
    synchronized boolean busy() { return busy; }
    synchronized String message() { return error; }
    synchronized long captured() { return total; }
    synchronized long dropped() { return dropped; }
    synchronized double fps() { return observedFps; }
}
