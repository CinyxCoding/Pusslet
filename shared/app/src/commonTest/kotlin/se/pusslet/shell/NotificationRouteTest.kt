package se.pusslet.shell

import kotlinx.datetime.LocalDate
import se.pusslet.core.notify.NotificationTarget
import se.pusslet.shell.navigation.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationRouteTest {
    private val date = LocalDate(2026, 10, 21)

    @Test
    fun aTappedNotificationOpensWhatItIsAbout() {
        assertEquals(Route.Today, NotificationTarget.Today.route())
        assertEquals(Route.EventDetails("e-1", date), NotificationTarget.Occurrence("e-1", date).route())
        assertEquals(Route.TaskEditor("t-1"), NotificationTarget.Task("t-1").route())
        assertEquals(Route.Shopping, NotificationTarget.Shopping.route())
    }

    @Test
    fun theHelperViewHasNoToDoEditor() {
        assertEquals(Route.EventDetails("e-1", date), NotificationTarget.Occurrence("e-1", date).helperRoute())
        assertEquals(Route.Shopping, NotificationTarget.Shopping.helperRoute())
        assertNull(NotificationTarget.Task("t-1").helperRoute())
        assertNull(NotificationTarget.Today.helperRoute())
    }

    @Test
    fun theTargetSurvivesTheIntentExtra() {
        val target = NotificationTarget.Occurrence("e/1", date)
        assertEquals(target, NotificationTarget.decode(target.encode()))
    }
}
