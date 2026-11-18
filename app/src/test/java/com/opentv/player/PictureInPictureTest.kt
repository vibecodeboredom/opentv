package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 10.0.0: picture-in-picture entry decisions — the pure part. */
class PictureInPictureTest {

    @Test
    fun aspectRatioIsSixteenByNine() {
        assertEquals(16, PictureInPicture.ASPECT_WIDTH)
        assertEquals(9, PictureInPicture.ASPECT_HEIGHT)
    }

    @Test
    fun canEnterWhenFeaturePresentNotInPipAndPlayerExists() {
        assertTrue(PictureInPicture.canEnterPip(true, false, true))
    }

    @Test
    fun cannotEnterWithoutTheFeature() {
        assertFalse(PictureInPicture.canEnterPip(false, false, true))
    }

    @Test
    fun cannotReenterWhileAlreadyInPip() {
        assertFalse(PictureInPicture.canEnterPip(true, true, true))
    }

    @Test
    fun cannotEnterWithNoPlayer() {
        assertFalse(PictureInPicture.canEnterPip(true, false, false))
    }

    @Test
    fun nothingCanEnterWhenEverythingIsMissing() {
        assertFalse(PictureInPicture.canEnterPip(false, true, false))
    }
}
