package com.kr1901.tennisvideo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPoseTrackerTest {
    private fun pose(x: Float, y: Float, confidence: Float = 0.9f) = PlayerPose(
        footX = x,
        footY = y,
        confidence = confidence,
        leftWristX = x - 0.03f,
        leftWristY = y - 0.2f,
        rightWristX = x + 0.03f,
        rightWristY = y - 0.2f,
        boxLeft = x - 0.1f,
        boxTop = y - 0.8f,
        boxRight = x + 0.1f,
        boxBottom = y
    )

    @Test
    fun assignsStableIdsByNearestFootPoint() {
        val tracker = PlayerPoseTracker()
        val first = tracker.assign(listOf(pose(0.2f, 0.7f), pose(0.8f, 0.7f)))

        assertEquals("P1", first.first { it.pose.footX < 0.5f }.playerId)
        assertEquals("P2", first.first { it.pose.footX > 0.5f }.playerId)

        val next = tracker.assign(listOf(pose(0.75f, 0.7f), pose(0.25f, 0.7f)))
        assertEquals("P1", next.first { it.pose.footX < 0.5f }.playerId)
        assertEquals("P2", next.first { it.pose.footX > 0.5f }.playerId)
    }

    @Test
    fun restoresExistingTrackIdsAcrossAnalysisChunks() {
        val tracker = PlayerPoseTracker(
            listOf(
                PlayerTrackState("P2", 0.8f, 0.7f),
                PlayerTrackState("P1", 0.2f, 0.7f)
            )
        )
        val assigned = tracker.assign(listOf(pose(0.21f, 0.71f), pose(0.79f, 0.69f)))
        assertEquals("P1", assigned.first { it.pose.footX < 0.5f }.playerId)
        assertEquals("P2", assigned.first { it.pose.footX > 0.5f }.playerId)
    }

    @Test
    fun capsAssignmentsAtTwoPlayers() {
        val assigned = PlayerPoseTracker().assign(
            listOf(pose(0.2f, 0.7f), pose(0.5f, 0.7f), pose(0.8f, 0.7f))
        )
        assertEquals(2, assigned.size)
        assertTrue(assigned.map { it.playerId }.distinct().size == 2)
    }
}
