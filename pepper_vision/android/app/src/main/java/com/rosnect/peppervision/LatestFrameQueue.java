package com.rosnect.peppervision;
/** One pending frame. Replacing stale work bounds memory and favours freshness. */
final class LatestFrameQueue<T> {
    private T pending;
    private long dropped;
    synchronized void offer(T frame) { if (pending != null) dropped++; pending=frame; }
    synchronized T poll() { T result=pending;pending=null;return result; }
    synchronized void clear() { if (pending != null) dropped++;pending=null; }
    synchronized long dropped() { return dropped; }
}
