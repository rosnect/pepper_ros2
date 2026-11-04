package com.rosnect.peppervision;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class VisionControllerTest {
    static class Clock implements VisionController.Clock { long time;public long now(){return time;} }
    static class Backend implements VisionController.Backend {
        int captures,cancels;Completion completion;
        public void capture(VisionSettings value,Completion done){captures++;completion=done;}
        public void cancel(){cancels++;}
        void finish(){completion.done(new VisionFrame(Wire.object(),Wire.object(),null),null);}
    }
    Clock clock;Backend backend;VisionController camera;int accepted;
    @Before public void setup(){clock=new Clock();backend=new Backend();accepted=0;camera=new VisionController(clock,backend,frame->{accepted++;return true;});}
    void start(){camera.ready(true);camera.enable(true);camera.tick();}
    @Test public void cameraNeedsLocalEnableAndFreshReadiness(){camera.tick();camera.enable(true);camera.tick();assertEquals(0,backend.captures);camera.ready(true);camera.tick();assertEquals(0,backend.captures);camera.enable(true);camera.tick();assertEquals(1,backend.captures);}
    @Test public void oneCaptureInFlightEvenWhenTargetIsMissed(){start();clock.time=5000;camera.tick();assertEquals(1,backend.captures);backend.finish();camera.tick();assertEquals(2,backend.captures);camera.tick();assertEquals(2,backend.captures);}
    @Test public void targetRateDoesNotCauseEarlyCapture(){start();backend.finish();clock.time=499;camera.tick();assertEquals(1,backend.captures);clock.time=500;camera.tick();assertEquals(2,backend.captures);}
    @Test public void stoppedCaptureCannotLeakAfterRestart(){start();camera.stop();camera.enable(true);backend.finish();assertEquals(0,accepted);camera.tick();assertEquals(2,backend.captures);backend.finish();assertEquals(1,accepted);}
    @Test public void reconnectRequiresManualEnable(){start();camera.ready(false);backend.finish();camera.ready(true);camera.tick();assertFalse(camera.enabled());assertEquals(1,backend.captures);assertEquals(0,accepted);}
    @Test public void failuresBackOffAndDisableAfterThree(){start();for(int i=0;i<3;i++){backend.completion.done(null,"camera denied");if(i<2){clock.time+=1999;camera.tick();assertEquals(i+1,backend.captures);clock.time++;camera.tick();}}assertFalse(camera.enabled());assertTrue(camera.message().contains("3 failures"));}
    @Test public void timeoutDoesNotPretendTheSdkOperationStopped(){start();clock.time=10001;camera.tick();assertFalse(camera.enabled());assertTrue(camera.busy());assertEquals(1,backend.cancels);camera.tick();assertEquals(1,backend.captures);backend.finish();assertFalse(camera.busy());assertEquals(0,accepted);}
    @Test public void settingsCannotChangeDuringCapture(){start();try{camera.settings(new VisionSettings(1,320,240,60));fail("settings should be locked");}catch(IllegalStateException expected){}}
    @Test public void measuredRateCountsIntervalsRatherThanRequestedFps(){start();backend.finish();for(int i=1;i<=4;i++){clock.time=i*500;camera.tick();backend.finish();}assertEquals(2.0,camera.fps(),.01);camera.stop();assertEquals(0,camera.fps(),0);}
    @Test public void sinkFailureDropsInsteadOfAccumulatingFrames(){camera=new VisionController(clock,backend,frame->false);start();backend.finish();assertEquals(1,camera.dropped());assertFalse(camera.busy());}
    @Test public void invalidSettingsAreRejected(){for(double rate:new double[]{Double.NaN,Double.POSITIVE_INFINITY,0,6}){try{new VisionSettings(rate,640,480,70);fail("invalid rate");}catch(IllegalArgumentException expected){}}}
}
