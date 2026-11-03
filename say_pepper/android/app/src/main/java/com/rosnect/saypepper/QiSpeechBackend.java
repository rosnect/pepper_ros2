package com.rosnect.saypepper;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.aldebaran.qi.Future;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.builder.SayBuilder;
import com.aldebaran.qi.sdk.object.conversation.Say;
import com.aldebaran.qi.sdk.object.conversation.BodyLanguageOption;

/** Serialize SDK operations and callbacks on the main looper; never block the UI on QiSDK. */
final class QiSpeechBackend implements SpeechController.Backend {
    private static final String TAG="SayPepper";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private QiContext context;
    private Future<Say> building;
    private Future<Void> running;
    private boolean cancelled;
    void context(QiContext context) { handler.post(() -> this.context=context); }
    @Override public void start(String text, Runnable started, Completion completion) {
        handler.post(() -> {
            if (context == null) { completion.finished(false,error("prepare","No robot focus")); return; }
            cancelled=false;
            try {
                building=SayBuilder.with(context).withText(text)
                        .withBodyLanguageOption(BodyLanguageOption.DISABLED).buildAsync();
                building.thenConsume(result -> handler.post(() -> {
                    if (!result.isSuccess()) {
                        building=null;
                        completion.finished(result.isCancelled(),result.isCancelled() ? null
                                : error("build",result.getErrorMessage())); return;
                    }
                    if (cancelled) { building=null; completion.finished(true,null); return; }
                    try {
                        Say say=result.getValue();
                        say.addOnStartedListener(() -> handler.post(started));
                        running=say.async().run();
                        running.thenConsume(done -> handler.post(() -> {
                            running=null; building=null;
                            completion.finished(done.isCancelled(),done.hasError() ? error("run",done.getErrorMessage()) : null);
                        }));
                    } catch (Exception e) { completion.finished(false,error("run",e.toString())); }
                }));
            } catch (Exception e) { completion.finished(false,error("build",e.toString())); }
        });
    }
    private static String error(String stage, String detail) {
        String message="QiSDK speech "+stage+": "+(detail == null ? "Unknown SDK error" : detail);
        Log.e(TAG,message);
        return message;
    }
    @Override public void cancel() {
        handler.post(() -> {
            cancelled=true;
            if (building != null && !building.isDone()) building.requestCancellation();
            if (running != null && !running.isDone()) running.requestCancellation();
        });
    }
}
