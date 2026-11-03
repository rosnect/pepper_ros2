package com.rosnect.saypepper;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.QiSDK;
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks;
import com.aldebaran.qi.sdk.design.activity.RobotActivity;
import org.json.JSONObject;

public final class MainActivity extends RobotActivity implements RobotLifecycleCallbacks {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final QiSpeechBackend backend=new QiSpeechBackend();
    private SpeechController speech;
    private MqttTransport mqtt;
    private TextView status, message, activity;
    private EditText host, user, password;
    private Spinner port;
    private Button connect;
    private Switch enable;
    private boolean destroyed;
    private final Runnable heartbeat=new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            speech.tick(); if (mqtt != null) mqtt.tick(); render();
            handler.postDelayed(this,1000);
        }
    };
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        speech=new SpeechController(new SpeechController.Clock() {
            public long wall() { return System.currentTimeMillis(); }
            public long monotonic() { return SystemClock.elapsedRealtime(); }
        },backend,event -> {
            if (mqtt != null) mqtt.publish(Wire.EVENT,event);
            if (activity != null) activity.setText(event.optString("state")+" · "+event.optString("message"));
        });
        layout(); QiSDK.register(this,this); handler.post(heartbeat);
    }
    private void layout() {
        ScrollView scroll=new ScrollView(this);
        LinearLayout column=new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(40,32,40,32); column.setBackgroundColor(Color.rgb(17,18,20));
        scroll.addView(column); setContentView(scroll);
        TextView title=label("say_pepper",34); title.setTypeface(Typeface.MONOSPACE,Typeface.BOLD); column.addView(title);
        column.addView(label("rosnect / pepper · speech bridge",16));
        status=label("○ broker offline  ·  ○ robot focus",20); column.addView(status);
        column.addView(label("CONNECTION · domain 7 / pepper_1",14));
        host=field("Broker hostname or IP", "192.168.0.81",false); column.addView(host);
        port=new Spinner(this);
        ArrayAdapter<String> ports=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
                new String[]{"1883 · MQTT", "8883 · MQTT over TLS"});
        port.setAdapter(ports); port.setBackgroundColor(Color.rgb(232,235,237)); column.addView(port);
        user=field("Username (optional)","",false); column.addView(user);
        password=field("Password (not stored)","",true); column.addView(password);
        column.addView(label("TLS uses Android's trusted certificate authorities and verifies the broker hostname.",14));
        connect=new Button(this); connect.setText("Connect"); column.addView(connect);
        connect.setOnClickListener(v -> {
            if (mqtt != null) { disconnect(); return; }
            mqtt=new MqttTransport(new MqttTransport.Listener() {
                public void ready(boolean value) { speech.connected(value); render(); }
                public void command(JSONObject command) { speech.command(command); render(); }
                public void message(String text) { message.setText(text); }
                public JSONObject state() { return speech.state(); }
                public void replay() { speech.replay(); }
            });
            mqtt.connect(host.getText().toString().trim(),port.getSelectedItemPosition()==1,
                    user.getText().toString(),password.getText().toString());
            password.setText(""); connect.setText("Disconnect"); setFields(false);
        });
        enable=new Switch(this); enable.setText("Enable remote speech"); enable.setTextColor(Color.WHITE);
        enable.setPadding(0,24,0,24); column.addView(enable);
        enable.setOnCheckedChangeListener((button,checked) -> { speech.enabled(checked); render(); });
        Button stop=new Button(this); stop.setText("Stop speech & disable remote control");
        column.addView(stop); stop.setOnClickListener(v -> { speech.localStop(); render(); });
        message=label("Keep this app open on Pepper. Connect, wait for robot focus, then enable remote speech.",17);
        column.addView(message);
        activity=label("No speech commands received",17); column.addView(activity);
        column.addView(label("Speech only · one command at a time · speech gestures disabled",14));
    }
    private void setFields(boolean value) {
        host.setEnabled(value); user.setEnabled(value); password.setEnabled(value); port.setEnabled(value);
    }
    private TextView label(String text, int size) {
        TextView view=new TextView(this); view.setText(text); view.setTextSize(size);
        view.setTextColor(Color.rgb(226,229,231)); view.setPadding(0,12,0,12); return view;
    }
    private EditText field(String hint, String value, boolean secret) {
        EditText view=new EditText(this); view.setSingleLine(true); view.setHint(hint); view.setText(value);
        view.setTextColor(Color.BLACK); view.setHintTextColor(Color.DKGRAY);
        GradientDrawable bg=new GradientDrawable(); bg.setColor(Color.rgb(232,235,237)); bg.setCornerRadius(12);
        view.setBackground(bg); view.setPadding(18,12,18,12);
        view.setInputType(secret ? InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,64); params.setMargins(0,6,0,6);
        view.setLayoutParams(params); return view;
    }
    private void render() {
        JSONObject s=speech.state();
        boolean bridge=s.optBoolean("bridge_ready"), focus=s.optBoolean("robot_focus");
        status.setText((bridge ? "● bridge ready" : "○ bridge offline")+"  ·  "+(focus ? "● robot focus" : "○ no robot focus")
                +"\n"+(s.optBoolean("ready") ? "READY FOR SPEECH" : s.optString("phase").toUpperCase(java.util.Locale.ROOT)));
        status.setTextColor(s.optBoolean("ready") ? Color.rgb(95,219,163) : Color.rgb(241,193,107));
        enable.setEnabled(bridge && focus);
        if (enable.isChecked() != speech.isEnabled()) enable.setChecked(speech.isEnabled());
    }
    private void disconnect() {
        if (mqtt != null) { mqtt.stop(); mqtt=null; }
        connect.setText("Connect"); setFields(true); render();
    }
    @Override public void onRobotFocusGained(QiContext context) {
        Log.i("SayPepper","Robot focus gained");
        handler.post(() -> { if (destroyed) return; backend.context(context); speech.focus(true); render(); });
    }
    @Override public void onRobotFocusLost() {
        Log.w("SayPepper","Robot focus lost");
        handler.post(() -> { if (destroyed) return; speech.focus(false); backend.context(null); render(); });
    }
    @Override public void onRobotFocusRefused(String reason) {
        Log.w("SayPepper","Robot focus refused: "+reason);
        handler.post(() -> { if (destroyed) return; speech.focus(false); message.setText("Robot focus refused. Check Pepper and close other robot apps."); render(); });
    }
    @Override protected void onPause() {
        if (speech != null) speech.localStop();
        super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed=true; handler.removeCallbacks(heartbeat);
        speech.localStop(); if (mqtt != null) mqtt.stop();
        QiSDK.unregister(this,this); super.onDestroy();
    }
}
