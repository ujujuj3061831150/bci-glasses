package com.bci.glasses

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ControlSessionTest {
    private fun look(session: ControlSession, direction: Int, at: Long) {
        val angle = direction * PI / 4.0
        session.observe(ControlGazeSample(cos(angle), sin(angle), at, confidence = 1.0), at)
        session.tick(at)
    }
    private fun candidate(session: ControlSession, direction: Int = 0): Long {
        session.start(0)
        look(session, direction, 1)
        look(session, direction, 402)
        assertEquals(ControlPhase.CANDIDATES, session.phase)
        return session.candidateVersion
    }
    private fun executing(session: ControlSession, direction: Int = 0) {
        val version = candidate(session, direction)
        assertTrue(session.injectSelectionAndConfirmation(direction, version, 403))
    }

    @Test fun allDirectionsIncludeCircularNeighborsAndDistinctCandidateFrequencies() {
        for (center in 0..7) {
            val session = ControlSession()
            candidate(session, center)
            assertEquals(ControlDirections.neighbors(center), session.candidates)
            assertEquals(3, session.candidates.map { ControlDirections.all[it].frequencyHz }.toSet().size)
        }
        assertEquals(listOf(7, 0, 1), ControlDirections.neighbors(0))
    }

    @Test fun shortSweepDoesNotActivateCandidates() {
        val session = ControlSession()
        session.start(0)
        look(session, 0, 1)
        look(session, 2, 100)
        look(session, 4, 200)
        assertEquals(ControlPhase.NAVIGATION, session.phase)
        assertTrue(session.candidates.isEmpty())
    }

    @Test fun LookingBetweenMembersDoesNotChangeGroupOrVersion() {
        val session = ControlSession()
        val version = candidate(session)
        look(session, 1, 500)
        look(session, 1, 1000)
        look(session, 7, 1100)
        look(session, 7, 1600)
        assertEquals(version, session.candidateVersion)
        assertEquals(listOf(7, 0, 1), session.candidates)
    }

    @Test fun boundaryMarginPreventsUnintendedSwitch() {
        val session = ControlSession()
        val version = candidate(session)
        val angle = 70.0 * PI / 180.0 // Just outside the upper-right 67.5 degree sector boundary.
        for (time in listOf(500L, 1100L)) {
            session.observe(ControlGazeSample(cos(angle), sin(angle), time), time)
            session.tick(time)
        }
        assertEquals(version, session.candidateVersion)
    }

    @Test fun stableLookOutsideGroupSwitchesAndInvalidatesOldEvidence() {
        val session = ControlSession()
        val oldVersion = candidate(session)
        look(session, 4, 500)
        assertEquals(oldVersion, session.candidateVersion)
        look(session, 4, 1001)
        assertEquals(listOf(3, 4, 5), session.candidates)
        assertTrue(session.candidateVersion > oldVersion)
        assertFalse(session.injectSelectionAndConfirmation(4, oldVersion, 1002))
    }

    @Test fun confirmationRequiresMatchingCandidateAndValidGaze() {
        val session = ControlSession()
        val version = candidate(session)
        assertFalse(session.injectSelectionAndConfirmation(4, version, 403))
        assertFalse(session.injectSelectionAndConfirmation(1, version, 403))
        look(session, 1, 404)
        assertTrue(session.injectSelectionAndConfirmation(1, version, 405))
        assertFalse(session.injectSelectionAndConfirmation(1, version, 406))
    }

    @Test fun staleOrFutureAndNonFiniteObservationsAreNotUsable() {
        val session = ControlSession()
        session.observe(ControlGazeSample(1.0, 0.0, 0), 0)
        assertFalse(session.gazeUsable(251))
        session.observe(ControlGazeSample(1.0, 0.0, 1000), 500)
        assertFalse(session.gazeUsable(500))
        session.observe(ControlGazeSample(Double.NaN, 0.0, 1001), 1001)
        assertFalse(session.gazeUsable(1001))
    }

    @Test fun outOfOrderObservationCannotReplaceNewerGaze() {
        val session = ControlSession()
        session.observe(ControlGazeSample(1.0, 0.0, 100), 100)
        session.observe(ControlGazeSample(-1.0, 0.0, 50), 101)
        assertEquals(0, session.hoveredDirection(101))
    }

    @Test fun invalidGazeSuspendsMotionAndRequiresRestartAfterGrace() {
        val session = ControlSession()
        executing(session)
        look(session, 0, 503)
        assertTrue(session.positionX > 0.0)
        val stoppedX = session.positionX
        session.observe(ControlGazeSample(1.0, 0.0, 504, valid = false), 504)
        session.tick(504)
        session.tick(1105)
        assertEquals(stoppedX, session.positionX, 0.0)
        assertEquals(ControlPhase.INTERRUPTED, session.phase)
        look(session, 0, 1200)
        assertEquals(ControlPhase.INTERRUPTED, session.phase)
    }

    @Test fun attentionAloneAndLookingAwayAloneDoNotProduceNormalExit() {
        val session = ControlSession()
        executing(session)
        session.setAttentionLow(true, 450)
        look(session, 0, 500)
        look(session, 0, 1000)
        assertEquals(ControlPhase.EXECUTING, session.phase)
        session.setAttentionLow(false, 1001)
        look(session, 4, 1100)
        look(session, 4, 1600)
        assertEquals(ControlPhase.EXECUTING, session.phase)
        assertEquals(0, session.selectedDirection)
    }

    @Test fun normalExitRequiresBothConditionsAndStopsBeforeNavigation() {
        val events = mutableListOf<ControlEvent>()
        val session = ControlSession(onEvent = events::add)
        executing(session)
        session.setAttentionLow(true, 450)
        look(session, 4, 500)
        look(session, 4, 901)
        assertEquals(ControlPhase.NAVIGATION, session.phase)
        assertNull(session.selectedDirection)
        assertTrue(events.any { it.type == "NORMAL_EXIT" })
        val stop = events.indexOfLast { it.type == "MOVEMENT_STOPPED" }
        val navigation = events.indexOfLast { it.type == "NAVIGATION_ENTERED" }
        assertTrue(stop in 0 until navigation)
    }

    @Test fun invalidGazeIsNotNormalExitEvidence() {
        val events = mutableListOf<ControlEvent>()
        val session = ControlSession(onEvent = events::add)
        executing(session)
        session.setAttentionLow(true, 450)
        session.observe(ControlGazeSample(0.0, 0.0, 500, valid = false), 500)
        session.tick(500)
        session.tick(1101)
        assertEquals(ControlPhase.INTERRUPTED, session.phase)
        assertFalse(events.any { it.type == "NORMAL_EXIT" })
    }

    @Test fun trackingOrViewportLossCancelsSelectionAndDoesNotAutoResume() {
        for (loseTracking in listOf(true, false)) {
            val session = ControlSession()
            executing(session)
            if (loseTracking) session.setObjectTracked(false, 500) else session.setLayoutValid(false, 500)
            assertEquals(ControlPhase.INTERRUPTED, session.phase)
            assertNull(session.selectedDirection)
            session.setObjectTracked(true, 501)
            session.setLayoutValid(true, 501)
            look(session, 0, 502)
            assertEquals(ControlPhase.INTERRUPTED, session.phase)
        }
    }

    @Test fun longSchedulingGapDoesNotContinueMovementWithFreshlyInjectedGaze() {
        val session = ControlSession()
        executing(session)
        look(session, 0, 2000)
        assertEquals(ControlPhase.INTERRUPTED, session.phase)
        assertEquals(0.0, session.positionX, 0.0)
    }

    @Test fun diagonalMovementHasSameSpeedAsCardinalMovement() {
        val cardinal = ControlSession(); executing(cardinal, 0); look(cardinal, 0, 503)
        val diagonal = ControlSession(); executing(diagonal, 1); look(diagonal, 1, 503)
        assertEquals(cardinal.positionX, kotlin.math.hypot(diagonal.positionX, diagonal.positionY), 1e-9)
    }

    @Test fun stopAndLayoutChangesPreventLateConfirmation() {
        val session = ControlSession()
        val version = candidate(session)
        session.invalidateLayout(403)
        assertFalse(session.injectSelectionAndConfirmation(0, version, 404))
        session.start(500)
        session.stop(501)
        look(session, 0, 502)
        assertEquals(ControlPhase.STOPPED, session.phase)
    }
}
