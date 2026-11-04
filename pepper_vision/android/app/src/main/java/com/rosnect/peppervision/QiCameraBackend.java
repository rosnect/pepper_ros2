package com.rosnect.peppervision;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.aldebaran.qi.Future;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.builder.TakePictureBuilder;
import com.aldebaran.qi.sdk.object.camera.TakePicture;
import com.aldebaran.qi.sdk.object.image.TimestampedImageHandle;
import com.aldebaran.qi.sdk.object.image.EncodedImage;

/** Separate camera worker: large robot image copies cannot block speech cancellation. */
final class QiCameraBackend implements VisionController.Backend {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final SpeechDispatcher dispatch=new SpeechDispatcher(task -> handler.post(task),"pepper-camera");
    private final String session;
    private QiContext context;
    private TakePicture action;
    private Operation active;
    private long sequence,lastRobotTimestamp;
    private static final class Operation {
        Future<?> future;
        boolean cancelled,finished;
        final Completion completion;
        final VisionSettings settings;
        Operation(VisionSettings settings,Completion completion) { this.settings=settings;this.completion=completion; }
    }
    QiCameraBackend(String session) { this.session=session; }
    void context(QiContext context) { dispatch.sdk(() -> { this.context=context;action=null;lastRobotTimestamp=0; }); }
    @Override public void capture(VisionSettings settings,Completion completion) {
        dispatch.sdk(() -> {
            Operation op=new Operation(settings,completion);
            if (active!=null || context==null) { finish(op,null,"Camera busy or robot focus unavailable");return; }
            active=op;
            if (action!=null) { run(op);return; }
            try {
                Future<TakePicture> future=TakePictureBuilder.with(context).buildAsync();op.future=future;
                future.thenConsume(result -> dispatch.sdk(() -> {
                    if (!usable(op,result,"build")) return;
                    try { action=result.getValue();run(op); }
                    catch (Exception e) { fail(op,"build",e.toString()); }
                }));
            } catch (Exception e) { fail(op,"build",e.toString()); }
        });
    }
    private void run(Operation op) {
        try {
            Future<TimestampedImageHandle> future=action.async().run();op.future=future;
            future.thenConsume(result -> dispatch.sdk(() -> {
                if (!usable(op,result,"capture")) return;
                try {
                    TimestampedImageHandle picture=result.getValue();
                    long receiptMs=System.currentTimeMillis(),robotNs=picture.getTime();
                    if (robotNs>0 && robotNs<=lastRobotTimestamp) {
                        finish(op,null,"Repeated robot frame timestamp; lower requested FPS");return;
                    }
                    lastRobotTimestamp=robotNs;
                    Future<EncodedImage> copying=picture.getImage().async().getValue();op.future=copying;
                    copying.thenConsume(image -> dispatch.sdk(() -> {
                        if (!usable(op,image,"copy")) return;
                        try { finish(op,FrameEncoder.encode(image.getValue().getData(),op.settings,receiptMs,robotNs,++sequence,session),null); }
                        catch (Exception e) { fail(op,"encode",e.toString()); }
                    }));
                } catch (Exception e) { fail(op,"copy",e.toString()); }
            }));
        } catch (Exception e) { fail(op,"capture",e.toString()); }
    }
    private boolean usable(Operation op,Future<?> future,String stage) {
        if (op.finished) return false;
        if (op.cancelled || future.isCancelled()) { finish(op,null,"Capture canceled");return false; }
        if (!future.isSuccess()) { fail(op,stage,future.getErrorMessage());return false; }
        return true;
    }
    private void fail(Operation op,String stage,String detail) {
        action=null;String message="QiSDK camera "+stage+": "+detail;
        Log.e("PepperVision",message);finish(op,null,message.substring(0,Math.min(300,message.length())));
    }
    private void finish(Operation op,VisionFrame frame,String error) {
        if (op.finished) return;
        op.finished=true;if (active==op) active=null;
        dispatch.notifyUi(() -> op.completion.done(frame,error));
    }
    @Override public void cancel() { dispatch.sdk(this::cancelOnWorker); }
    private void cancelOnWorker() {
        action=null;
        if (active!=null) {
            active.cancelled=true;
            if (active.future!=null && !active.future.isDone()) active.future.requestCancellation();
        }
    }
    void close() { dispatch.close(() -> { cancelOnWorker();context=null; }); }
}
