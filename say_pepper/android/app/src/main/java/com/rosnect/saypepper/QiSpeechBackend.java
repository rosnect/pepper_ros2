package com.rosnect.saypepper;

import android.os.Handler;
import android.os.Looper;
import com.aldebaran.qi.Future;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.builder.SayBuilder;
import com.aldebaran.qi.sdk.object.conversation.Say;
import com.aldebaran.qi.sdk.object.conversation.BodyLanguageOption;

/** Serialize SDK operations and callbacks on the main looper; never block the UI on QiSDK. */
final class QiSpeechBackend implements SpeechController.Backend {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private QiContext context;
    private Future<Say> building;
    private Future<Void> running;
    private boolean cancelled;
    void context(QiContext context) { handler.post(() -> this.context=context); }
    @Override public void start(String text, Runnable started, Completion completion) {
        handler.post(() -> {
            if (context == null) { completion.finished(false,"No robot focus"); return; }
            cancelled=false;
            try {
                building=SayBuilder.with(context).withText(text)
                        .withBodyLanguageOption(BodyLanguageOption.DISABLED).buildAsync();
                building.thenConsume(result -> handler.post(() -> {
                    if (!result.isSuccess()) {
                        building=null;
                        completion.finished(result.isCancelled(),"build failed"); return;
                    }
                    if (cancelled) { building=null; completion.finished(true,null); return; }
                    try {
                        Say say=result.getValue();
                        say.addOnStartedListener(() -> handler.post(started));
                        running=say.async().run();
                        running.thenConsume(done -> handler.post(() -> {
                            running=null; building=null;
                            completion.finished(done.isCancelled(),done.hasError() ? "run failed" : null);
                        }));
                    } catch (Exception e) { completion.finished(false,"QiSDK run failed"); }
                }));
            } catch (Exception e) { completion.finished(false,"QiSDK build failed"); }
        });
    }
    @Override public void cancel() {
        handler.post(() -> {
            cancelled=true;
            if (building != null && !building.isDone()) building.requestCancellation();
            if (running != null && !running.isDone()) running.requestCancellation();
        });
    }
}
