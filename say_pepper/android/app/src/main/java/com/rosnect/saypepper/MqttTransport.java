package com.rosnect.saypepper;

import android.os.Handler;
import android.os.Looper;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.net.ssl.SSLSocketFactory;

/** Async Paho client; every application callback runs on the main looper. */
final class MqttTransport {
    interface Listener {
        void ready(boolean ready);
        void command(JSONObject command);
        void message(String message);
        JSONObject state();
        void replay();
    }
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final String configurationId=UUID.randomUUID().toString();
    private MqttAsyncClient client;
    private boolean stopped, subscribed, accepted;
    private long nextConfig, lastAck;
    MqttTransport(Listener listener) { this.listener=listener; }
    void connect(String host, boolean tls, String user, String password) {
        try {
            if (host.isEmpty() || host.contains("/") || host.contains(":"))
                throw new IllegalArgumentException("Enter a broker hostname or IPv4 address");
            client=new MqttAsyncClient((tls ? "ssl://" : "tcp://")+host+(tls ? ":8883" : ":1883"),
                    "say-pepper-"+UUID.randomUUID(),new MemoryPersistence());
            client.setCallback(new MqttCallbackExtended() {
                @Override public void connectComplete(boolean reconnect, String uri) {
                    handler.post(() -> { if (!stopped) subscribe(); });
                }
                @Override public void connectionLost(Throwable cause) {
                    handler.post(() -> {
                        if (stopped) return;
                        subscribed=false; accepted=false; listener.ready(false);
                        listener.message("Broker disconnected; reconnecting. Enable speech again after recovery.");
                    });
                }
                @Override public void messageArrived(String topic, MqttMessage message) {
                    if (message.getPayload().length > 65536) return;
                    String text=new String(message.getPayload(),StandardCharsets.UTF_8);
                    boolean retained=message.isRetained();
                    handler.post(() -> receive(topic,text,retained));
                }
                @Override public void deliveryComplete(IMqttDeliveryToken token) { }
            });
            MqttConnectOptions options=new MqttConnectOptions();
            options.setAutomaticReconnect(true); options.setCleanSession(true);
            options.setConnectionTimeout(8); options.setKeepAliveInterval(5); options.setMaxInflight(20);
            if (!user.isEmpty()) options.setUserName(user);
            if (!password.isEmpty()) options.setPassword(password.toCharArray());
            if (tls) {
                options.setSocketFactory(SSLSocketFactory.getDefault());
                options.setHttpsHostnameVerificationEnabled(true);
            }
            JSONObject offline=listener.state();
            offline.put("online",false); offline.put("ready",false); offline.put("bridge_ready",false);
            offline.put("remote_enabled",false);
            options.setWill(Wire.BASE+"to_ros",Wire.envelope(Wire.STATE,offline).toString()
                    .getBytes(StandardCharsets.UTF_8),1,false);
            client.connect(options,null,new IMqttActionListener() {
                @Override public void onSuccess(IMqttToken token) { }
                @Override public void onFailure(IMqttToken token, Throwable error) {
                    handler.post(() -> { if (!stopped) listener.message("Connection failed. Check broker, credentials and certificate; disconnect to retry."); });
                }
            });
        } catch (Exception e) { listener.message("Cannot connect: "+e.getMessage()); }
    }
    private void subscribe() {
        subscribed=false; accepted=false; listener.ready(false);
        try {
            client.subscribe(new String[]{Wire.BASE+"to_device",Wire.BASE+"config_status",Wire.BASE+"config_request"},
                    new int[]{1,1,1},null,new IMqttActionListener() {
                        @Override public void onSuccess(IMqttToken token) {
                            handler.post(() -> { if (!stopped) { subscribed=true; configure(); } });
                        }
                        @Override public void onFailure(IMqttToken token, Throwable error) {
                            handler.post(() -> { if (!stopped) listener.message("MQTT subscription refused; check broker ACLs."); });
                        }
                    });
        } catch (MqttException e) { listener.message("Could not subscribe to bridge topics"); }
    }
    private void configure() {
        if (!subscribed || stopped) return;
        send("config",Wire.configuration(configurationId),true);
        nextConfig=android.os.SystemClock.elapsedRealtime()+3000;
        if (!accepted) listener.message("Broker connected; waiting for ros2embedded configuration acknowledgement");
    }
    private void receive(String topic, String text, boolean retained) {
        if (stopped || !subscribed) return;
        try {
            JSONObject json=new JSONObject(text);
            if (topic.equals(Wire.BASE+"config_request")) {
                accepted=false; listener.ready(false); configure();
            } else if (topic.equals(Wire.BASE+"config_status")) {
                if (!configurationId.equals(json.optString("configuration_id"))
                        || !Wire.CONNECTION.equals(json.optString("connection_id"))
                        || json.optInt("domain_id",-1) != Wire.DOMAIN) return;
                boolean ok=json.optBoolean("accepted",false);
                if (ok) lastAck=android.os.SystemClock.elapsedRealtime();
                if (accepted && ok) return;
                accepted=ok; listener.ready(ok);
                listener.message(ok ? "Bridge configured. Acquire robot focus, then enable remote speech."
                        : "Bridge rejected configuration: "+json.optString("error","check bridge logs"));
                if (ok) {
                    publish(Wire.CAPABILITIES,Wire.object("schema_version",1,"session_id",listener.state().optString("session_id"),
                            "device","say_pepper","qisdk","1.7.5","robot_api",7,
                            "speech",true,"motion",false,"max_text_characters",500,"max_timeout_ms",120000));
                    listener.replay(); publish(Wire.STATE,listener.state());
                }
            } else if (topic.equals(Wire.BASE+"to_device") && accepted && !retained
                    && Wire.COMMAND.equals(json.optString("identifier"))
                    && json.optInt("domain_id",-1) == Wire.DOMAIN
                    && "json".equals(json.optString("value_type"))) {
                listener.command(json.getJSONObject("value"));
            }
        } catch (Exception e) { listener.message("Ignored malformed bridge message"); }
    }
    void tick() {
        if (stopped) return;
        long now=android.os.SystemClock.elapsedRealtime();
        if (accepted && now-lastAck > 8000) {
            accepted=false; listener.ready(false);
            listener.message("Bridge acknowledgement expired. Waiting for host; speech disabled.");
        }
        if (subscribed && now >= nextConfig) configure();
        if (accepted) publish(Wire.STATE,listener.state());
    }
    void publish(String identifier, JSONObject value) {
        if (accepted && !stopped) send("to_ros",Wire.envelope(identifier,value),false);
    }
    private void send(String suffix, JSONObject value, boolean retained) {
        try {
            if (client != null && client.isConnected())
                client.publish(Wire.BASE+suffix,value.toString().getBytes(StandardCharsets.UTF_8),1,retained);
        } catch (MqttException e) { listener.message("MQTT publish failed; checking connection"); }
    }
    void stop() {
        // Closing a socket can block; perform graceful disconnect and cleanup off the UI thread.
        listener.ready(false);
        if (accepted) publish(Wire.STATE,listener.state());
        stopped=true; accepted=false;
        final MqttAsyncClient old=client;
        if (old == null) return;
        new Thread(() -> {
            try { old.disconnectForcibly(500,500,false); } catch (Exception ignored) { }
            try { old.close(true); } catch (Exception ignored) { }
        },"pepper-mqtt-close").start();
    }
}
