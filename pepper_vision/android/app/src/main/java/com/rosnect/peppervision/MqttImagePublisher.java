package com.rosnect.peppervision;

import android.os.Handler;
import android.os.Looper;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLSocketFactory;

/** A separate MQTT socket keeps large frames off the speech/control connection. */
final class MqttImagePublisher {
    interface Listener { void written(VisionFrame frame); void message(String value); void unavailable(); }
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> new Thread(r,"pepper-images"));
    private final LatestFrameQueue<VisionFrame> queue=new LatestFrameQueue<>();
    private final Listener listener;
    private final AtomicBoolean drainQueued=new AtomicBoolean();
    private MqttAsyncClient client;
    private volatile boolean closed,connected,enabled;
    private volatile long sent;
    private boolean inFlight;
    private long flightId,epoch;
    MqttImagePublisher(Listener listener) { this.listener=listener; }
    private void work(Runnable task) {
        if (closed) return;
        try { worker.execute(() -> { if (!closed) task.run(); }); }
        catch (RejectedExecutionException ignored) { /* closed concurrently */ }
    }
    private void message(String text) { ui.post(() -> { if (!closed) listener.message(text); }); }
    void connect(String host,boolean tls,String username,String password) {
        work(() -> {
            try {
                if (host.isEmpty() || host.contains("/") || host.contains(":")) throw new IllegalArgumentException("Invalid broker hostname");
                client=new MqttAsyncClient((tls ? "ssl://" : "tcp://")+host+(tls ? ":8883" : ":1883"),
                        "pepper-vision-images-"+UUID.randomUUID(),new MemoryPersistence());
                client.setCallback(new MqttCallbackExtended() {
                    public void connectComplete(boolean reconnect,String uri) { work(() -> { connected=client!=null&&client.isConnected();queue.clear();message("Image transport connected"); }); }
                    public void connectionLost(Throwable error) { connected=false;ui.post(() -> { if(!closed)listener.unavailable(); });work(() -> { connected=false;queue.clear();inFlight=false;flightId++;epoch++;message("Image transport lost; camera must be re-enabled"); }); }
                    public void messageArrived(String topic,MqttMessage message) { }
                    public void deliveryComplete(IMqttDeliveryToken token) { }
                });
                MqttConnectOptions options=new MqttConnectOptions();options.setAutomaticReconnect(true);
                options.setCleanSession(true);options.setKeepAliveInterval(5);options.setConnectionTimeout(8);options.setMaxInflight(1);
                if (!username.isEmpty()) options.setUserName(username);
                if (!password.isEmpty()) options.setPassword(password.toCharArray());
                if (tls) { options.setSocketFactory(SSLSocketFactory.getDefault());options.setHttpsHostnameVerificationEnabled(true); }
                client.connect(options,null,new IMqttActionListener() {
                    public void onSuccess(IMqttToken token) { }
                    public void onFailure(IMqttToken token,Throwable error) { message("Image transport failed; disconnect and check broker settings"); }
                });
            } catch (Exception e) { message("Image transport: "+e.getMessage()); }
        });
    }
    boolean ready() { return connected && !closed; }
    void enabled(boolean value) {
        if (enabled==value) return;
        enabled=value;
        if (!value) { queue.clear();work(() -> { epoch++; }); }
    }
    boolean offer(VisionFrame frame) {
        if (!ready() || !enabled) return false;
        queue.offer(frame);
        if (drainQueued.compareAndSet(false,true)) work(() -> { drainQueued.set(false);drain(); });
        return true;
    }
    private void drain() {
        if (inFlight || !connected || !enabled || closed) return;
        VisionFrame frame=queue.poll();if (frame==null) return;
        inFlight=true;long id=++flightId,stream=epoch;
        try {
            byte[] bytes=Wire.envelope(Wire.IMAGE,frame.image).toString().getBytes(StandardCharsets.UTF_8);
            client.publish(Wire.BASE+"to_ros",bytes,0,false,null,new IMqttActionListener() {
                public void onSuccess(IMqttToken token) { work(() -> finish(id,stream,frame,true)); }
                public void onFailure(IMqttToken token,Throwable error) { work(() -> finish(id,stream,frame,false)); }
            });
        } catch (Exception e) { finish(id,stream,frame,false); }
    }
    private void finish(long id,long stream,VisionFrame frame,boolean success) {
        if (id!=flightId) return;
        inFlight=false;
        if (success && stream==epoch && connected && enabled) {
            sent++;ui.post(() -> { if (!closed && enabled) listener.written(frame); });
        }
        if (!success) { queue.clear();message("Image publish failed; latest frame dropped");return; }
        drain();
    }
    long sent() { return sent; }
    long dropped() { return queue.dropped(); }
    void stop() {
        if (closed) return;
        closed=true;connected=false;enabled=false;queue.clear();
        worker.execute(() -> {
            if (client==null) return;
            try { client.disconnectForcibly(500,500,false); } catch (Exception ignored) { }
            try { client.close(true); } catch (Exception ignored) { }
        });
        worker.shutdown();
    }
}
