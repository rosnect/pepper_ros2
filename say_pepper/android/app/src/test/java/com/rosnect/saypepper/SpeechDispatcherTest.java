package com.rosnect.saypepper;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class SpeechDispatcherTest {
    @Test public void rpcRunsOffCallerAndCallbacksReturnThroughUiExecutor() throws Exception {
        Thread caller=Thread.currentThread();
        BlockingQueue<Runnable> ui=new LinkedBlockingQueue<>();
        SpeechDispatcher dispatch=new SpeechDispatcher(ui::add);
        AtomicReference<Thread> sdkThread=new AtomicReference<>(), callbackThread=new AtomicReference<>();
        try {
            dispatch.sdk(() -> {
                sdkThread.set(Thread.currentThread());
                dispatch.notifyUi(() -> callbackThread.set(Thread.currentThread()));
            });
            Runnable callback=ui.poll(2,TimeUnit.SECONDS);
            assertNotNull(callback); assertNotSame(caller,sdkThread.get());
            assertNull(callbackThread.get()); callback.run(); assertSame(caller,callbackThread.get());
        } finally { dispatch.close(() -> {}); }
    }
    @Test public void focusStartAndCancellationStayInOrderOnOneWorker() throws Exception {
        SpeechDispatcher dispatch=new SpeechDispatcher(Runnable::run);
        List<Integer> order=new ArrayList<>(); List<Thread> threads=new ArrayList<>();
        CountDownLatch done=new CountDownLatch(1);
        try {
            for (int i=0;i<3;i++) {
                final int item=i;
                dispatch.sdk(() -> { order.add(item); threads.add(Thread.currentThread()); });
            }
            dispatch.sdk(done::countDown);
            assertTrue(done.await(2,TimeUnit.SECONDS));
            assertEquals(Arrays.asList(0,1,2),order);
            assertSame(threads.get(0),threads.get(1)); assertSame(threads.get(1),threads.get(2));
        } finally { dispatch.close(() -> {}); }
    }
    @Test public void closeCancelsOffUiAndSuppressesLateUiCallbacks() throws Exception {
        BlockingQueue<Runnable> ui=new LinkedBlockingQueue<>();
        SpeechDispatcher dispatch=new SpeechDispatcher(ui::add);
        AtomicBoolean lateCallback=new AtomicBoolean(false), lateSdk=new AtomicBoolean(false);
        AtomicReference<Thread> cleanupThread=new AtomicReference<>();
        CountDownLatch closed=new CountDownLatch(1);
        dispatch.notifyUi(() -> lateCallback.set(true));
        dispatch.close(() -> { cleanupThread.set(Thread.currentThread()); closed.countDown(); });
        dispatch.sdk(() -> lateSdk.set(true));
        assertTrue(closed.await(2,TimeUnit.SECONDS));
        ui.remove().run();
        assertNotSame(Thread.currentThread(),cleanupThread.get());
        assertFalse(lateCallback.get()); assertFalse(lateSdk.get());
    }
}
