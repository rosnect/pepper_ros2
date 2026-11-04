package com.rosnect.peppervision;
import org.junit.Test;
import static org.junit.Assert.*;
public class LatestFrameQueueTest {
    @Test public void slowConsumerReceivesOnlyTheFreshestFrame(){LatestFrameQueue<Integer> queue=new LatestFrameQueue<>();for(int frame=0;frame<10000;frame++)queue.offer(frame);assertEquals(Integer.valueOf(9999),queue.poll());assertNull(queue.poll());assertEquals(9999,queue.dropped());}
    @Test public void disconnectDiscardsPendingFrameBeforeNextSession(){LatestFrameQueue<String> queue=new LatestFrameQueue<>();queue.offer("old session");queue.clear();assertNull(queue.poll());queue.offer("fresh session");assertEquals("fresh session",queue.poll());}
}
