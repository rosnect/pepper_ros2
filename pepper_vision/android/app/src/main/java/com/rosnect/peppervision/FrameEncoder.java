package com.rosnect.peppervision;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** Runs only on the camera worker. Never sends raw RGB or unbounded JSON byte arrays. */
final class FrameEncoder {
    static VisionFrame encode(ByteBuffer source,VisionSettings settings,long stampMs,long robotNs,long sequence,String session) {
        ByteBuffer bytes=source.duplicate();bytes.rewind();
        if (bytes.remaining()==0 || bytes.remaining()>8*1024*1024) throw new IllegalArgumentException("Robot image exceeds 8 MiB source limit");
        byte[] encoded=new byte[bytes.remaining()];bytes.get(encoded);
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(encoded,0,encoded.length,options);
        if (options.outWidth<=0 || options.outHeight<=0 || options.outWidth>4096 || options.outHeight>4096)
            throw new IllegalArgumentException("Invalid or oversized robot image dimensions");
        int sample=1;
        while (options.outWidth/(sample*2)>=settings.width && options.outHeight/(sample*2)>=settings.height) sample*=2;
        options.inJustDecodeBounds=false;options.inSampleSize=sample;
        Bitmap decoded=BitmapFactory.decodeByteArray(encoded,0,encoded.length,options);
        if (decoded==null) throw new IllegalArgumentException("Robot returned an undecodable image");
        Bitmap scaled=decoded;
        try {
            double scale=Math.min(1,Math.min((double)settings.width/decoded.getWidth(),(double)settings.height/decoded.getHeight()));
            if (scale<1) scaled=Bitmap.createScaledBitmap(decoded,Math.max(1,(int)Math.round(decoded.getWidth()*scale)),Math.max(1,(int)Math.round(decoded.getHeight()*scale)),true);
            byte[] jpeg=null;
            for (int quality=settings.quality;quality>=35;quality-=10) {
                ByteArrayOutputStream output=new ByteArrayOutputStream();
                if (!scaled.compress(Bitmap.CompressFormat.JPEG,quality,output)) throw new IllegalArgumentException("JPEG compression failed");
                jpeg=output.toByteArray();
                if (jpeg.length<=VisionSettings.MAX_JPEG_BYTES) break;
            }
            if (jpeg==null || jpeg.length>VisionSettings.MAX_JPEG_BYTES) throw new IllegalArgumentException("Frame exceeds 128 KiB; select 320 x 240");
            double previewScale=Math.min(1,320.0/scaled.getWidth());
            Bitmap preview=Bitmap.createScaledBitmap(scaled,Math.max(1,(int)(scaled.getWidth()*previewScale)),Math.max(1,(int)(scaled.getHeight()*previewScale)),true);
            // Own a separate preview; the encoding bitmaps are recycled below.
            if (preview==scaled) preview=scaled.copy(Bitmap.Config.ARGB_8888,false);
            return new VisionFrame(Wire.object("header",Wire.object("stamp",Wire.object("sec",stampMs/1000,"nanosec",(stampMs%1000)*1000000),
                    "frame_id",Wire.OPTICAL_FRAME),"format","jpeg","data_base64",Base64.encodeToString(jpeg,Base64.NO_WRAP)),
                    Wire.object("schema_version",1,"session_id",session,"sequence",sequence,
                            "stamp_unix_ms",stampMs,"timestamp_source","tablet_capture_result_received",
                            "robot_timestamp_ns",robotNs,"width",scaled.getWidth(),"height",scaled.getHeight(),
                            "jpeg_bytes",jpeg.length,"requested_fps",settings.fps,"depth_available",false),preview);
        } finally { if (scaled!=decoded) scaled.recycle();decoded.recycle(); }
    }
}
