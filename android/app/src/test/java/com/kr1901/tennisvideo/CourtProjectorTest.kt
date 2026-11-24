package com.kr1901.tennisvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class CourtProjectorTest {
    private val imageCorners = listOf(
        0.1f to 0.1f, // far-left
        0.9f to 0.1f, // far-right
        0.9f to 0.9f, // near-right
        0.1f to 0.9f  // near-left
    )

    @Test
    fun projectsCourtCentreToNetCentre() {
        val result = CourtProjector.project(imageCorners, 0.5f, 0.5f)

        assertNotNull(result)
        assertEquals(0.0, result!!.xMeters, 1e-6)
        assertEquals(0.0, result.yMeters, 1e-6)
    }

    @Test
    fun projectsNearAndFarBaselines() {
        val near = CourtProjector.project(imageCorners, 0.5f, 0.9f)
        val far = CourtProjector.project(imageCorners, 0.5f, 0.1f)

        assertNotNull(near)
        assertNotNull(far)
        assertEquals("near", near!!.playerEnd)
        assertEquals("far", far!!.playerEnd)
        assertEquals(0.0, near.baselineDistanceMeters, 1e-6)
        assertEquals(0.0, far.baselineDistanceMeters, 1e-6)
    }

    @Test
    fun signedDistanceDistinguishesBehindAndInsideBaseline() {
        val inside = CourtProjector.project(imageCorners, 0.5f, 0.8f)
        val behind = CourtProjector.project(imageCorners, 0.5f, 1.0f)

        assertNotNull(inside)
        assertNotNull(behind)
        assertEquals(true, inside!!.baselineDistanceMeters < 0.0)
        assertEquals(true, behind!!.baselineDistanceMeters > 0.0)
    }

    @Test
    fun rejectsDegenerateCornerGeometry() {
        val invalid = listOf(
            0.5f to 0.5f,
            0.5f to 0.5f,
            0.5f to 0.5f,
            0.5f to 0.5f
        )

        assertNull(CourtProjector.project(invalid, 0.5f, 0.5f))
    }
}
