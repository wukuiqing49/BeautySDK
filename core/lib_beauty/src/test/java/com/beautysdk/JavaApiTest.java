package com.beautysdk;

import java.nio.ByteBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

public class JavaApiTest {
    @Test public void javaHostCanUseFrameAndLifecycleWithoutKotlinHelpers() {
        FrameInfo info = new FrameInfo(1, 1, 0L);
        RgbaFrame frame = new RgbaFrame(info, ByteBuffer.allocate(4), 4);
        try (BeautyProcessor processor = new BeautyProcessor()) {
            processor.start();
            VideoFrame output = processor.process(frame);
            assertSame(frame, output);
            assertEquals(1, output.getInfo().getWidth());
            processor.stop();
        }
    }
    @Test public void javaEngineCallbackCanUseConnector() {
        RgbaFrame frame = new RgbaFrame(new FrameInfo(1, 1, 1L), ByteBuffer.allocate(4), 4);
        BeautyEngineFrameAdapter<RgbaFrame> adapter = new BeautyEngineFrameAdapter<RgbaFrame>() {
            public VideoFrame read(RgbaFrame input) { return input; }
            public boolean write(RgbaFrame input, VideoFrame output) { return input == output; }
            public boolean passthrough(RgbaFrame input) { return true; }
        };
        try (BeautyEngineConnector<RgbaFrame> connector = new BeautyEngineConnector<>(adapter, new BeautyProcessor())) {
            connector.start();
            assertTrue(connector.onFrame(frame).getAccepted());
        }
    }
}
