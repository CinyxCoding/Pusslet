package se.pusslet.feature.family.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import se.pusslet.core.model.Child
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Place
import se.pusslet.core.model.SchoolRef
import se.pusslet.core.model.SwedishPlaces
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.seed.DemoSeed
import kotlin.math.abs
import kotlin.time.Clock

enum class OnboardingStep { Welcome, Family, Children, Place, Done }

data class ChildDraft(val id: String, val name: String = "", val birthYear: String = "", val school: SchoolRef? = null)

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val familyName: String = "",
    val myName: String = "",
    val children: List<ChildDraft> = emptyList(),
    val placeQuery: String = "",
    val place: Place? = null,
    val saving: Boolean = false,
) {
    val canContinue: Boolean get() = when (step) {
        OnboardingStep.Family -> myName.isNotBlank()
        else -> true
    }
}

/**
 * "Skapa familj → lägg till barn → välj ort → klart" in under two minutes. Everything except a name is optional,
 * nothing needs an account or the network, and a demo family is one tap away.
 */
class OnboardingViewModel(
    private val family: FamilyRepository,
    private val prefs: PreferencesRepository,
    private val seed: DemoSeed,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun go(step: OnboardingStep) = _state.update { it.copy(step = step) }

    fun back() = _state.update { s ->
        s.copy(step = OnboardingStep.entries.getOrElse(s.step.ordinal - 1) { OnboardingStep.Welcome })
    }

    /** [defaultFamilyName] is used when the family name was left empty ("Familjen Sara"). */
    fun next(defaultFamilyName: String, onCreating: () -> Unit = {}) {
        val s = _state.value
        if (!s.canContinue) return
        when (s.step) {
            OnboardingStep.Place -> {
                onCreating()
                finish(defaultFamilyName)
            }
            OnboardingStep.Done -> Unit
            else -> _state.update { it.copy(step = OnboardingStep.entries[it.step.ordinal + 1]) }
        }
    }

    fun setFamilyName(v: String) = _state.update { it.copy(familyName = v) }

    fun setMyName(v: String) = _state.update { it.copy(myName = v) }

    fun addChild() = _state.update { it.copy(children = it.children + ChildDraft(ids.newId())) }

    fun updateChild(id: String, transform: (ChildDraft) -> ChildDraft) =
        _state.update { s -> s.copy(children = s.children.map { if (it.id == id) transform(it) else it }) }

    fun removeChild(id: String) = _state.update { s -> s.copy(children = s.children.filterNot { it.id == id }) }

    fun setPlaceQuery(q: String) = _state.update { it.copy(placeQuery = q) }

    fun choosePlace(place: Place?) = _state.update { it.copy(place = place) }

    /** Uses the phone's coarse location: the nearest town gives the place a friendly name. */
    fun useLocation(latitude: Double, longitude: Double) {
        val nearest = SwedishPlaces.all.minByOrNull { abs(it.latitude - latitude) + abs(it.longitude - longitude) * LON_SCALE }
        _state.update { it.copy(place = Place(nearest?.name ?: "", latitude, longitude)) }
    }

    private fun finish(defaultFamilyName: String) {
        val s = _state.value
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val name = s.familyName.ifBlank { defaultFamilyName }
            family.createFamily(name, s.myName)
            val used = mutableListOf(MemberColor.Sage)
            s.children.filter { it.name.isNotBlank() }.forEach { draft ->
                val color = MemberColor.nextFree(used).also { used += it }
                val year = draft.birthYear.trim().toIntOrNull()?.takeIf { it in MIN_YEAR..clock.todayIn(zone).year }
                family.saveChild(
                    Child(
                        id = draft.id,
                        firstName = draft.name.trim(),
                        color = color,
                        birthDate = year?.let { LocalDate(it, 7, 1) },
                        school = draft.school,
                    ),
                )
            }
            s.place?.let { p -> family.saveSettings { it.copy(weatherPlace = p) } }
            prefs.set(Prefs.OnboardingDone, true)
            _state.update { it.copy(saving = false, step = OnboardingStep.Done) }
        }
    }

    fun startDemo(onDone: () -> Unit) {
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            seed.seed(clock.todayIn(zone))
            prefs.set(Prefs.OnboardingDone, true)
            onDone()
        }
    }

    private companion object {
        const val MIN_YEAR = 2000
        const val LON_SCALE = 0.5
    }
}
