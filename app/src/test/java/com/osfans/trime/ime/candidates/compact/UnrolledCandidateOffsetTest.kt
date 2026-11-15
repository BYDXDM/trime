package com.osfans.trime.ime.candidates.compact

import com.osfans.trime.ime.bar.UnrollButtonStateMachine
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class UnrolledCandidateOffsetTest : StringSpec({
    "counts only the contiguous fully visible prefix" {
        fullyVisibleCandidatePrefix(
            decoratedBounds = listOf(0 to 20, 20 to 40, 40 to 60, 60 to 80),
            viewportStart = 0,
            viewportEnd = 55,
        ) shouldBe 2
    }

    "keeps attached offscreen children out of the prefix" {
        fullyVisibleCandidatePrefix(
            decoratedBounds = listOf(0 to 20, 20 to 40, 40 to 60, 60 to 80),
            viewportStart = 0,
            viewportEnd = 40,
        ) shouldBe 2
    }

    "uses unscrolled bounds after horizontal scrolling" {
        fullyVisibleCandidatePrefix(
            decoratedBounds = listOf(-60 to -40, -40 to -20, -20 to 0, 0 to 20),
            viewportStart = 0,
            viewportEnd = 60,
            scrollOffset = 60,
        ) shouldBe 3
    }

    "allows zero offset when a candidate exists and marks actual emptiness" {
        unrolledCandidateOffset(visiblePrefix = 0, visibleCount = 2) shouldBe 0
        unrolledCandidateOffset(visiblePrefix = 0, visibleCount = 0) shouldBe -1
    }

    "keeps unknown totals expandable and hides all-fit rows" {
        shouldShowUnrollButton(total = -1, visibleCount = 2, visiblePrefix = 0, filteredOut = 0) shouldBe true
        shouldShowUnrollButton(total = 4, visibleCount = 3, visiblePrefix = 3, filteredOut = 1) shouldBe false
        shouldShowUnrollButton(total = 4, visibleCount = 2, visiblePrefix = 1, filteredOut = 1) shouldBe true
        shouldShowUnrollButton(total = -1, visibleCount = 0, visiblePrefix = 0, filteredOut = 0) shouldBe false
    }

    "hides after detaching when an attached update makes every candidate fit" {
        val machine = UnrollButtonStateMachine.new {}
        machine.push(
            UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesUpdated,
            UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesEmpty to false,
        )
        machine.push(UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesAttached)
        machine.push(
            UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesUpdated,
            UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesEmpty to true,
        )
        machine.push(UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesDetached)
        machine.currentState shouldBe UnrollButtonStateMachine.State.Hidden
    }

    "keeps expansion available after detaching while unseen candidates remain" {
        val machine = UnrollButtonStateMachine.new {}
        machine.push(
            UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesUpdated,
            UnrollButtonStateMachine.BooleanKey.UnrolledCandidatesEmpty to false,
        )
        machine.push(UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesAttached)
        machine.push(UnrollButtonStateMachine.TransitionEvent.UnrolledCandidatesDetached)
        machine.currentState shouldBe UnrollButtonStateMachine.State.ClickToAttachWindow
    }

    "treats exact viewport edges as fully visible" {
        fullyVisibleCandidatePrefix(
            decoratedBounds = listOf(10 to 30, 30 to 50),
            viewportStart = 10,
            viewportEnd = 50,
        ) shouldBe 2
    }
})
