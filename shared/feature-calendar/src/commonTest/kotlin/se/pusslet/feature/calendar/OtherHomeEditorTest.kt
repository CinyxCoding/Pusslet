package se.pusslet.feature.calendar

import kotlinx.datetime.LocalDate
import se.pusslet.core.model.Child
import se.pusslet.core.model.FamilyPerson
import se.pusslet.core.model.Member
import se.pusslet.core.model.MemberColor
import se.pusslet.data.usecase.SharedSpace
import se.pusslet.feature.calendar.editor.EventDraft
import se.pusslet.feature.calendar.editor.EventEditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The editor's "Delas med andra hemmet" switch (ADR-056): Ella is shared with Lina's home, Noah is not. */
class OtherHomeEditorTest {
    private val people = listOf(
        FamilyPerson("ahmed", "Ahmed", MemberColor.Sage, isChild = false),
        FamilyPerson("ella", "Ella", MemberColor.Rose, isChild = true),
        FamilyPerson("noah", "Noah", MemberColor.Ocean, isChild = true),
    )
    private val space =
        SharedSpace("space", listOf(Child("ella", "Ella")), listOf(Member("lina", "Lina")), shareDiets = false, otherHomeJoined = true)
    private val draft = EventDraft(title = "Fotboll", startDate = LocalDate(2026, 10, 13))

    @Test
    fun newEventsAboutTheSharedChildAreSharedUnlessTheParentSaysNo() {
        val state = EventEditorState(loaded = true, isNew = true, draft = draft, people = people, spaces = listOf(space))
        assertFalse(state.canShare(draft), "nobody chosen yet")
        val ella = draft.copy(participants = setOf("ella", "ahmed"))
        assertTrue(state.canShare(ella) && state.shares(ella))
        assertEquals(listOf("Ahmed", "Lina"), state.handoffAdults(ella).map { it.name }, "Lina can pick Ella up")
        assertTrue(state.handoffAdults(ella).last().otherHome)
        val siblings = draft.copy(participants = setOf("ella", "noah"))
        assertTrue(state.canShare(siblings))
        assertFalse(state.shares(siblings), "Noah's things stay home unless the parent shares it")
        assertEquals(listOf("Ahmed"), state.handoffAdults(siblings).map { it.name })
        val no = ella.copy(shareWithOtherHome = false)
        assertFalse(state.shares(no))
    }

    @Test
    fun withoutASharedSpaceThereIsNoSwitch() {
        val state = EventEditorState(loaded = true, isNew = true, draft = draft, people = people)
        val ella = draft.copy(participants = setOf("ella"))
        assertFalse(state.canShare(ella))
        assertEquals(listOf("Ahmed"), state.handoffAdults(ella).map { it.name })
        assertNull(ella.shareWithOtherHome)
    }

    @Test
    fun anExistingEventShowsWhereItIs() {
        val shared =
            EventEditorState(loaded = true, isNew = false, draft = draft, people = people, spaces = listOf(space), wasShared = true)
        assertTrue(shared.canShare(draft) && shared.shares(draft), "already shared, even with nobody chosen")
        val single = shared.copy(singleOccurrence = true)
        assertFalse(single.canShare(draft), "one occurrence follows its series")
    }
}
