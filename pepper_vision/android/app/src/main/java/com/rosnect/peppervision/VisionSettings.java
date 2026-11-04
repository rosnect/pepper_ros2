package com.rosnect.peppervision;

final class VisionSettings {
    static final int MAX_JPEG_BYTES=128*1024;
    final double fps;
    final int width,height,quality;
    VisionSettings(double fps,int width,int height,int quality) {
        if (Double.isNaN(fps) || fps < .5 || fps > 5 || width < 160 || width > 640
                || height < 120 || height > 480 || quality < 35 || quality > 85)
            throw new IllegalArgumentException("Invalid camera settings");
        this.fps=fps; this.width=width; this.height=height; this.quality=quality;
    }
    long periodMs() { return (long)Math.ceil(1000/fps); }
}
