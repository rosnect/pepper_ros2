package com.rosnect.peppervision;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.WindowManager;
import android.widget.*;
import com.aldebaran.qi.sdk.QiContext;
import com.aldebaran.qi.sdk.QiSDK;
import com.aldebaran.qi.sdk.RobotLifecycleCallbacks;
import com.aldebaran.qi.sdk.design.activity.RobotActivity;
import org.json.JSONObject;
import java.util.Locale;

public final class MainActivity extends RobotActivity implements RobotLifecycleCallbacks {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final QiSpeechBackend speechBackend=new QiSpeechBackend();
    private SpeechController speech;
    private QiCameraBackend cameraBackend;
    private VisionController camera;
    private MqttTransport mqtt;
    private MqttImagePublisher images;
    private TextView status,message,speechStatus,cameraStatus,imageStatus;
    private EditText host,user,password;
    private Spinner port,fps,resolution;
    private Button connect;
    private Switch speechEnable,cameraEnable;
    private ImageView preview;
    private boolean destroyed,visible,bridgeReady,focus;
    private long nextState,nextRender;
    private final Runnable tick=new Runnable() {
        public void run() {
            if (destroyed) return;
            long now=SystemClock.elapsedRealtime();
            updateCameraReady();camera.tick();
            if (now>=nextState) {
                nextState=now+1000;speech.tick();if(mqtt!=null)mqtt.tick();
            }
            if (now>=nextRender) {nextRender=now+250;render();}
            ui.postDelayed(this,50);
        }
    };
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        speech=new SpeechController(new SpeechController.Clock() {
            public long wall() {return System.currentTimeMillis();}
            public long monotonic() {return SystemClock.elapsedRealtime();}
        },speechBackend,event -> {
            if(mqtt!=null)mqtt.publish(Wire.EVENT,event);
            if(speechStatus!=null)speechStatus.setText(event.optString("state")+" · "+event.optString("message"));
        });
        cameraBackend=new QiCameraBackend(speech.session);
        camera=new VisionController(SystemClock::elapsedRealtime,cameraBackend,frame -> {
            if(!visible || images==null || !camera.enabled())return false;
            boolean offered=images.offer(frame);
            if(offered)preview.setImageBitmap(frame.preview);
            return offered;
        });
        layout();QiSDK.register(this,this);ui.post(tick);
    }
    private void layout() {
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.rgb(17,19,22));root.setPadding(20,12,20,12);setContentView(root);
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);controls.setPadding(8,0,24,16);scroll.addView(controls);
        TextView title=label("pepper_vision",30);title.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);controls.addView(title);
        controls.addView(label("HEAD CAMERA + ROS 2 SPEECH",13));
        status=label("○ bridge offline · ○ robot focus",16);controls.addView(status);
        controls.addView(label("connection: pepper_vision · domain: 7",12));
        host=field("Broker hostname / IP",getPreferences(0).getString("broker","192.168.0.81"),false);controls.addView(host);
        port=spinner(new String[]{"1883 · MQTT","8883 · TLS"},0);controls.addView(port);
        user=field("Username (optional)","",false);controls.addView(user);
        password=field("Password (not stored)","",true);controls.addView(password);
        connect=button("Connect",controls);connect.setOnClickListener(v -> {if(mqtt!=null)disconnect();else connect();});
        speechEnable=new Switch(this);speechEnable.setText("Enable remote speech");speechEnable.setTextColor(Color.WHITE);controls.addView(speechEnable);
        speechEnable.setOnCheckedChangeListener((b,value)->{speech.enabled(value);render();});
        cameraEnable=new Switch(this);cameraEnable.setText("Stream head camera");cameraEnable.setTextColor(Color.WHITE);controls.addView(cameraEnable);
        cameraEnable.setOnCheckedChangeListener((b,value)->{
            camera.enable(value);if(images!=null)images.enabled(camera.enabled());if(!camera.enabled())preview.setImageDrawable(null);render();
        });
        controls.addView(label("TARGET FPS (actual depends on capture / Wi-Fi)",12));
        fps=spinner(new String[]{"0.5 FPS","1 FPS","2 FPS · default","3 FPS","5 FPS · experimental"},2);controls.addView(fps);
        resolution=spinner(new String[]{"640 × 480 maximum","320 × 240 maximum"},0);controls.addView(resolution);
        android.widget.AdapterView.OnItemSelectedListener settings=new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> parent,android.view.View view,int position,long id) {applySettings();}
            public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        };
        fps.setOnItemSelectedListener(settings);resolution.setOnItemSelectedListener(settings);
        button("STOP SPEECH + CAMERA",controls).setOnClickListener(v -> stopAll());
        message=label("Keep this app open. Connect, wait for focus, then enable the features you need.",14);controls.addView(message);
        controls.addView(label("TLS verifies broker certificates using Android's trusted CAs.",12));
        LinearLayout vision=new LinearLayout(this);vision.setOrientation(LinearLayout.VERTICAL);vision.setPadding(16,8,0,8);
        root.addView(vision,new LinearLayout.LayoutParams(0,-1,1.2f));
        vision.addView(label("PEPPER'S HEAD CAMERA",17));
        preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setBackgroundColor(Color.BLACK);
        preview.setContentDescription("Preview from Pepper's head camera");vision.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        cameraStatus=label("Camera off",15);vision.addView(cameraStatus);
        imageStatus=label("Image transport offline",13);vision.addView(imageStatus);
        speechStatus=label("No speech commands received",14);vision.addView(speechStatus);
        vision.addView(label("RGB only · raw depth is not exposed by QiSDK 1.7.5\nCameraInfo is uncalibrated until real calibration is supplied in ROS.",12));
    }
    private void connect() {
        String address=host.getText().toString().trim(),username=user.getText().toString(),secret=password.getText().toString();
        boolean tls=port.getSelectedItemPosition()==1;
        getPreferences(0).edit().putString("broker",address).apply();
        images=new MqttImagePublisher(new MqttImagePublisher.Listener() {
            public void written(VisionFrame frame) {if(mqtt!=null)mqtt.metadata(frame.metadata);}
            public void message(String text) {imageStatus.setText(text);}
            public void unavailable() {camera.stop();if(images!=null)images.enabled(false);render();}
        });
        mqtt=new MqttTransport(new MqttTransport.Listener() {
            public void ready(boolean value) {bridgeReady=value;speech.connected(value);updateCameraReady();render();}
            public void command(JSONObject value) {speech.command(value);render();}
            public void message(String value) {message.setText(value);}
            public JSONObject state() {return stateValue();}
            public void replay() {speech.replay();}
        });
        mqtt.connect(address,tls,username,secret);images.connect(address,tls,username,secret);
        password.setText("");connect.setText("Disconnect");fields(false);
    }
    private JSONObject stateValue() {
        JSONObject result=speech.state();
        try {
            result.put("camera_enabled",camera.enabled());result.put("camera_busy",camera.busy());result.put("camera_captured",camera.captured());
            result.put("camera_capture_fps",camera.fps());result.put("camera_written",images==null?0:images.sent());
            result.put("camera_dropped",camera.dropped()+(images==null?0:images.dropped()));result.put("depth_available",false);
        } catch(org.json.JSONException e) {throw new IllegalStateException(e);}
        return result;
    }
    private void updateCameraReady() {
        if(camera==null)return;
        camera.ready(visible && focus && bridgeReady && images!=null && images.ready());
        if(images!=null)images.enabled(camera.enabled());
    }
    private void applySettings() {
        if(fps==null || resolution==null || camera.enabled() || camera.busy())return;
        double[] rates={.5,1,2,3,5};boolean small=resolution.getSelectedItemPosition()==1;
        camera.settings(new VisionSettings(rates[Math.max(0,fps.getSelectedItemPosition())],small?320:640,small?240:480,70));
    }
    private void render() {
        if(status==null || cameraEnable==null || cameraStatus==null)return;
        status.setText((bridgeReady?"● bridge ready":"○ bridge offline")+" · "+(focus?"● robot focus":"○ no focus"));
        status.setTextColor(bridgeReady&&focus?Color.rgb(95,219,163):Color.rgb(241,193,107));
        speechEnable.setEnabled(visible&&bridgeReady&&focus);
        if(speechEnable.isChecked()!=speech.isEnabled())speechEnable.setChecked(speech.isEnabled());
        cameraEnable.setEnabled(camera.ready());
        if(cameraEnable.isChecked()!=camera.enabled())cameraEnable.setChecked(camera.enabled());
        fps.setEnabled(!camera.enabled()&&!camera.busy());resolution.setEnabled(!camera.enabled()&&!camera.busy());
        cameraStatus.setText(String.format(Locale.ROOT,"%s\nCapture %.1f FPS · %d captured · %d written · %d dropped",camera.message(),camera.fps(),camera.captured(),images==null?0:images.sent(),camera.dropped()+(images==null?0:images.dropped())));
    }
    private void stopAll() {speech.localStop();camera.stop();if(images!=null)images.enabled(false);preview.setImageDrawable(null);render();}
    private void disconnect() {
        stopAll();if(mqtt!=null){mqtt.stop();mqtt=null;}if(images!=null){images.stop();images=null;}
        bridgeReady=false;updateCameraReady();connect.setText("Connect");fields(true);imageStatus.setText("Image transport offline");render();
    }
    private void fields(boolean enabled) {host.setEnabled(enabled);user.setEnabled(enabled);password.setEnabled(enabled);port.setEnabled(enabled);}
    private TextView label(String text,int size) {TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(Color.rgb(225,230,234));v.setPadding(0,10,0,10);return v;}
    private EditText field(String hint,String text,boolean secret) {
        EditText v=new EditText(this);v.setSingleLine(true);v.setHint(hint);v.setText(text);v.setTextColor(Color.BLACK);v.setHintTextColor(Color.DKGRAY);
        v.setBackgroundColor(Color.rgb(232,235,237));v.setPadding(12,8,12,8);
        v.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);return v;
    }
    private Spinner spinner(String[] options,int selected) {Spinner v=new Spinner(this);v.setBackgroundColor(Color.rgb(232,235,237));v.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,options));v.setSelection(selected);return v;}
    private Button button(String text,LinearLayout column) {Button v=new Button(this);v.setText(text);column.addView(v);return v;}
    @Override public void onRobotFocusGained(QiContext context) {
        Log.i("PepperVision","Robot focus gained");ui.post(()->{if(destroyed)return;speechBackend.context(context);cameraBackend.context(context);focus=true;speech.focus(true);updateCameraReady();render();});
    }
    @Override public void onRobotFocusLost() {
        Log.w("PepperVision","Robot focus lost");ui.post(()->{if(destroyed)return;focus=false;speech.focus(false);camera.ready(false);speechBackend.context(null);cameraBackend.context(null);render();});
    }
    @Override public void onRobotFocusRefused(String reason) {
        Log.w("PepperVision","Robot focus refused: "+reason);ui.post(()->{if(destroyed)return;focus=false;speech.focus(false);camera.ready(false);message.setText("Robot focus refused: "+reason);render();});
    }
    @Override protected void onResume() {super.onResume();visible=true;}
    @Override protected void onPause() {visible=false;if(camera!=null)stopAll();super.onPause();}
    @Override protected void onDestroy() {
        destroyed=true;ui.removeCallbacks(tick);speech.localStop();camera.stop();if(mqtt!=null)mqtt.stop();if(images!=null)images.stop();
        speechBackend.close();cameraBackend.close();QiSDK.unregister(this,this);super.onDestroy();
    }
}
