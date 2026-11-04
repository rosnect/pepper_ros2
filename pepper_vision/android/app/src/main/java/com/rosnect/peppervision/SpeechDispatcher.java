package com.rosnect.peppervision;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** SDK RPCs belong on one worker; controller/UI notifications belong on the supplied UI executor. */
final class SpeechDispatcher {
    private final ExecutorService worker;
    private final Executor callbacks;
    private volatile boolean closed;
    SpeechDispatcher(Executor callbacks) { this(callbacks,"pepper-speech"); }
    SpeechDispatcher(Executor callbacks,String name) {
        this.callbacks=callbacks;worker=Executors.newSingleThreadExecutor(r -> new Thread(r,name));
    }
    synchronized void sdk(Runnable task) {
        if (!closed) worker.execute(() -> { if (!closed) task.run(); });
    }
    void notifyUi(Runnable task) {
        if (!closed) callbacks.execute(() -> { if (!closed) task.run(); });
    }
    synchronized void close(Runnable cleanup) {
        if (closed) return;
        closed=true;
        worker.execute(cleanup);
        worker.shutdown();
    }
}
