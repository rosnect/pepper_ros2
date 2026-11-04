package com.rosnect.peppervision;
import android.graphics.Bitmap;
import org.json.JSONObject;
final class VisionFrame {
    final JSONObject image, metadata;
    final Bitmap preview;
    VisionFrame(JSONObject image,JSONObject metadata,Bitmap preview) {
        this.image=image;this.metadata=metadata;this.preview=preview;
    }
}
