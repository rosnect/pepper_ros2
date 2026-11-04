package com.rosnect.peppervision;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.aldebaran.qi.Future;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.builder.SayBuilder;
import com.aldebaran.qi.sdk.object.conversation.Say;
import com.aldebaran.qi.sdk.object.conversation.BodyLanguageOption;

/** All SDK work, including listener registration, is serialized off Android's UI thread. */
final class QiSpeechBackend implements SpeechController.Backend {
    private static final String TAG="PepperVision";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final SpeechDispatcher dispatch=new SpeechDispatcher(task -> handler.post(task));
    // These fields are accessed only by the SDK worker.
    private QiContext context;
    private Future<Say> building;
    private Future<Void> running;
    private boolean cancelled;
    void context(QiContext context) { dispatch.sdk(() -> this.context=context); }
    private void complete(Completion completion, boolean cancelled, String error) {
        dispatch.notifyUi(() -> completion.finished(cancelled,error));
    }
    @Override public void start(String text, Runnable started, Completion completion) {
        dispatch.sdk(() -> {
            if (context == null) { complete(completion,false,error("prepare","No robot focus")); return; }
            cancelled=false;
            try {
                building=SayBuilder.with(context).withText(text)
                        .withBodyLanguageOption(BodyLanguageOption.DISABLED).buildAsync();
                building.thenConsume(result -> dispatch.sdk(() -> {
                    if (!result.isSuccess()) {
                        building=null;
                        complete(completion,result.isCancelled(),result.isCancelled() ? null
                                : error("build",result.getErrorMessage())); return;
                    }
                    if (cancelled) { building=null; complete(completion,true,null); return; }
                    try {
                        Say say=result.getValue();
                        // This synchronous registration is a robot RPC and must not run on the UI thread.
                        say.addOnStartedListener(() -> dispatch.notifyUi(started));
                        running=say.async().run();
                        running.thenConsume(done -> dispatch.sdk(() -> {
                            running=null; building=null;
                            complete(completion,done.isCancelled(),done.hasError() ? error("run",done.getErrorMessage()) : null);
                        }));
                    } catch (Exception e) {
                        building=null; running=null;
                        complete(completion,false,error("run",e.toString()));
                    }
                }));
            } catch (Exception e) {
                building=null;
                complete(completion,false,error("build",e.toString()));
            }
        });
    }
    private static String error(String stage, String detail) {
        String message="QiSDK speech "+stage+": "+(detail == null ? "Unknown SDK error" : detail);
        Log.e(TAG,message);
        return message;
    }
    @Override public void cancel() { dispatch.sdk(this::cancelOnWorker); }
    private void cancelOnWorker() {
        cancelled=true;
        if (building != null && !building.isDone()) building.requestCancellation();
        if (running != null && !running.isDone()) running.requestCancellation();
    }
    void close() {
        dispatch.close(() -> {
            try { cancelOnWorker(); }
            catch (RuntimeException e) { Log.w(TAG,"QiSDK cancellation during shutdown failed",e); }
            finally { context=null; }
        });
    }
}
