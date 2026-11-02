package se.pusslet.data.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import se.pusslet.data.repository.GuideStatus
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs

/**
 * The guide through the app (ADR-062). It shows by itself once the first start on this phone is over – a new family, the
 * example family or a family joined with an invite – and then at every start until it is finished or closed with "Visa
 * inte igen". "Stäng" only closes it until the app starts again; the shell keeps that in memory. Phones that used the app
 * before the guide existed never get it by itself, only from Inställningar.
 */
class AppGuide(private val prefs: PreferencesRepository) {
    /** True while the guide should open by itself when the app starts. */
    val due: Flow<Boolean> = prefs.observe(Prefs.Guide).map { it == GuideStatus.Due }.distinctUntilChanged()

    /** The first start is over: the guide is due, unless this phone has already been through it. */
    suspend fun firstStartDone() = prefs.update(Prefs.Guide) { if (it == GuideStatus.Done) it else GuideStatus.Due }

    /** Finished, or "Visa inte igen": it never opens by itself again on this phone. */
    suspend fun done() = prefs.set(Prefs.Guide, GuideStatus.Done)
}
