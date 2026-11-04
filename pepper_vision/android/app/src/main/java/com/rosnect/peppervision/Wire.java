package com.rosnect.peppervision;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Existing ros2embedded JSON protocol. No Pepper changes are needed in the bridge. */
final class Wire {
    static final int DOMAIN = 7;
    static final String CONNECTION = "pepper_vision";
    static final String BASE = "ros2embedded/" + DOMAIN + "/" + CONNECTION + "/";
    static final String COMMAND = "pepper.speech.command";
    static final String EVENT = "pepper.speech.event";
    static final String STATE = "pepper.state";
    static final String CAPABILITIES = "pepper.capabilities";
    static final String IMAGE = "pepper.camera.rgb";
    static final String METADATA = "pepper.camera.metadata";
    static final String OPTICAL_FRAME = "pepper_head_camera_optical_frame";

    static JSONObject object(Object... pairs) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) o.put((String)pairs[i], pairs[i+1]);
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
        return o;
    }
    static JSONObject envelope(String identifier, JSONObject value) {
        return object("identifier", identifier, "domain_id", DOMAIN, "value_type", "json",
                "value", value, "timestamp_unix_ms", System.currentTimeMillis());
    }
    static JSONObject configuration(String id) {
        JSONArray bindings = new JSONArray();
        bindings.put(binding(COMMAND, "speech/command", "ROS_TO_DEVICE"));
        bindings.put(binding(EVENT, "speech/event", "DEVICE_TO_ROS"));
        bindings.put(binding(STATE, "state", "DEVICE_TO_ROS"));
        bindings.put(binding(CAPABILITIES, "capabilities", "DEVICE_TO_ROS"));
        bindings.put(object("identifier",IMAGE,"ros_topic","camera/color/image_raw/compressed",
                "direction","DEVICE_TO_ROS","value_type","JSON","ros_message_type","sensor_msgs/msg/CompressedImage"));
        bindings.put(binding(METADATA,"camera/metadata","DEVICE_TO_ROS"));
        return object("schema_version", 1, "configuration_id", id, "connection_id", CONNECTION,
                "domain_id", DOMAIN, "protocol", "PROTOCOL_MQTT", "bindings", bindings);
    }
    private static JSONObject binding(String identifier, String topic, String direction) {
        return object("identifier", identifier, "ros_topic", topic, "direction", direction,
                "value_type", "JSON");
    }
    static boolean validId(String s) {
        try { return UUID.fromString(s).toString().equals(s); }
        catch (IllegalArgumentException e) { return false; }
    }
    static boolean validText(String text) {
        return !text.trim().isEmpty() && text.codePointCount(0, text.length()) <= 500
                && text.getBytes(StandardCharsets.UTF_8).length <= 2000;
    }
}
