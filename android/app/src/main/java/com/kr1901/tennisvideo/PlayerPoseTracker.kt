package com.kr1901.tennisvideo

import kotlin.math.hypot

internal data class PlayerTrackState(
    val playerId: String,
    val x: Float,
    val y: Float
)

internal data class TrackedPlayer(
    val playerId: String,
    val pose: PlayerPose
)

internal class PlayerPoseTracker(previous: List<PlayerTrackState> = emptyList()) {
    private var lastTracks: List<PlayerTrackState> = previous.take(MAX_PLAYERS)

    fun assign(detections: List<PlayerPose>): List<TrackedPlayer> {
        if (detections.isEmpty()) return emptyList()

        val remaining = detections.toMutableList()
        val assignments = ArrayList<TrackedPlayer>(detections.size)

        if (lastTracks.isEmpty()) {
            remaining.sortedBy { it.footX }.take(MAX_PLAYERS).forEachIndexed { index, pose ->
                assignments.add(TrackedPlayer(playerId(index), pose))
            }
        } else {
            val candidates = ArrayList<Triple<Float, PlayerTrackState, PlayerPose>>()
            lastTracks.forEach { previous ->
                remaining.forEach { current ->
                    candidates.add(
                        Triple(
                            hypot(current.footX - previous.x, current.footY - previous.y),
                            previous,
                            current
                        )
                    )
                }
            }
            val usedPrevious = HashSet<String>()
            val usedCurrent = HashSet<PlayerPose>()
            candidates.sortedBy { it.first }.forEach { candidate ->
                val distance = candidate.first
                val previous = candidate.second
                val current = candidate.third
                if (distance <= MAX_TRACK_JUMP &&
                    previous.playerId !in usedPrevious &&
                    current !in usedCurrent
                ) {
                    assignments.add(TrackedPlayer(previous.playerId, current))
                    usedPrevious.add(previous.playerId)
                    usedCurrent.add(current)
                }
            }

            remaining.filterNot { it in usedCurrent }.sortedBy { it.footX }.forEach { pose ->
                val unusedId = (0 until MAX_PLAYERS)
                    .map(::playerId)
                    .firstOrNull { candidate -> assignments.none { it.playerId == candidate } }
                if (unusedId != null && assignments.size < MAX_PLAYERS) {
                    assignments.add(TrackedPlayer(unusedId, pose))
                }
            }
        }

        lastTracks = assignments.map {
            PlayerTrackState(it.playerId, it.pose.footX, it.pose.footY)
        }
        return assignments.sortedBy { it.playerId }
    }

    companion object {
        private const val MAX_PLAYERS = 2
        private const val MAX_TRACK_JUMP = 0.32f
        private fun playerId(index: Int) = "P" + (index + 1)
    }
}
