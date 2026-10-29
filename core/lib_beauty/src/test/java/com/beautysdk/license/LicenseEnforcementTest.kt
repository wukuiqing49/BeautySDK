package com.beautysdk.license

import com.beautysdk.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer

class LicenseEnforcementTest {
    @Test fun commercialReleaseRejectsDirectCpuRendererAndSessionWithoutInitialization() {
        assumeTrue(BuildConfig.LICENSE_ENFORCED)
        val frame = RgbaFrame(FrameInfo(2, 2, 1), ByteBuffer.allocateDirect(16), 8)
        val settings = BeautySettings(smoothing = .5f, whitening = .5f)
        assertFalse(BeautyLicensing.allowsSettings(settings))
        assertSame(frame, RgbaBeautyRenderer().render(frame, settings, null))
        var errors = 0
        val session = BeautySession(onError = { errors++ })
        session.settings = settings
        session.start()
        assertSame(frame, session.process(frame))
        session.redraw(frame)
        assertEquals(1, errors)
        session.close()
        assertFalse(BeautyLicensing.allows(LicensedFeature.GESTURE))
    }
}
