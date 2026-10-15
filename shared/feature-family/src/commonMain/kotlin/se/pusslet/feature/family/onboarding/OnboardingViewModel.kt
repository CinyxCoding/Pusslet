package se.pusslet.feature.family.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import se.pusslet.core.model.Child
import se.pusslet.core.model.Member
import se.pusslet.core.model.MemberColor
import se.pusslet.core.model.Place
import se.pusslet.core.model.Role
import se.pusslet.core.model.SchoolRef
import se.pusslet.core.model.SwedishPlaces
import se.pusslet.core.util.IdGenerator
import se.pusslet.data.repository.FamilyRepository
import se.pusslet.data.repository.PreferencesRepository
import se.pusslet.data.repository.Prefs
import se.pusslet.data.seed.DemoSeed
import se.pusslet.data.session.SessionProvider
import se.pusslet.feature.family.nextChildColor
import kotlin.math.abs
import kotlin.time.Clock

enum class OnboardingStep { Welcome, Family, Children, Place, Done }

/** Only a first name is needed; the birth year and the school are optional. */
data class ChildDraft(val id: String, val name: String = "", val birthYear: String = "", val school: SchoolRef? = null)

/** Another adult who drops off and picks up ("Johan", "Morfar Bengt"): a name and nothing else. */
data class AdultDraft(val id: String, val name: String)

/** What the done screen says under "Klart!". */
enum class DoneMessage {
    /** Parents: "Lägg till med +. Resten kan vänta." */
    AddWithPlus,

    /** Caregivers have no + in their view (ADR-029): "Nu ser du när du hämtar och lämnar." */
    SeeHandoffs,
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val familyName: String = "",
    val myName: String = "",
    /** Step 1, optional: other adults who drop off and pick up. Saved as members without an account. */
    val adults: List<AdultDraft> = emptyList(),
    /** The name being typed for the next adult (added with "Lägg till", or by going on to the next step). */
    val adultName: String = "",
    val children: List<ChildDraft> = emptyList(),
    val placeQuery: String = "",
    val place: Place? = null,
    /** The role of whoever reaches the done screen: whoever creates the family is a parent. */
    val role: Role = Role.Parent,
    val saving: Boolean = false,
) {
    val canContinue: Boolean get() = when (step) {
        OnboardingStep.Family -> myName.isNotBlank()
        else -> true
    }

    /** Search first: no list of places until something is typed. */
    val placeSuggestions: List<Place> get() = SwedishPlaces.suggestions(placeQuery)

    val doneMessage: DoneMessage get() = if (role == Role.Parent) DoneMessage.AddWithPlus else DoneMessage.SeeHandoffs
}

/**
 * "Skapa familj → lägg till barn → välj ort → klart" in under two minutes. Everything except a name is optional,
 * nothing needs an account or the network, and a demo family is one tap away.
 *
 * Onboarding counts as done ([Prefs.OnboardingDone]) when "Visa min dag" is tapped on the done screen, so the screen is
 * really seen; the shell then shows the main UI (or the helper view for a caregiver). A family that is already on this
 * device without that flag (the app was closed on the done screen) opens on the done screen again, never on the welcome
 * screen, so no second family is created.
 */
class OnboardingViewModel(
    private val family: FamilyRepository,
    private val prefs: PreferencesRepository,
    private val seed: DemoSeed,
    private val sessions: SessionProvider,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        // "Visa första start igen" (developer tools) clears the flag while this view model may still hold a finished
        // run (step Done, or saving after the demo): start over from the welcome screen then.
        viewModelScope.launch {
            var wasDone = false
            prefs.observe(Prefs.OnboardingDone).collect { done ->
                if (wasDone && !done) _state.value = OnboardingUiState()
                wasDone = done
            }
        }
        viewModelScope.launch {
            sessions.loaded.first { it }
            val session = sessions.session.value
            if (session.hasFamily && !prefs.get(Prefs.OnboardingDone)) {
                _state.update { s -> if (s.step == OnboardingStep.Welcome) s.copy(step = OnboardingStep.Done, role = session.role) else s }
            }
        }
    }

    fun go(step: OnboardingStep) = _state.update { it.at(step) }

    fun back() = _state.update { s -> s.at(OnboardingStep.entries.getOrElse(s.step.ordinal - 1) { OnboardingStep.Welcome }) }

    /** [defaultFamilyName] is used when the family name was left empty ("Familjen Sara"). */
    fun next(defaultFamilyName: String) {
        val s = _state.value
        if (!s.canContinue) return
        when (s.step) {
            OnboardingStep.Place -> finish(defaultFamilyName)
            OnboardingStep.Done -> Unit
            // A name typed but not added yet counts: going on is the obvious way to say "that's it".
            OnboardingStep.Family -> _state.update { it.withAdultAdded().at(OnboardingStep.Children) }
            else -> _state.update { it.at(OnboardingStep.entries[it.step.ordinal + 1]) }
        }
    }

    /** The children step always starts with one empty row, so typing a name is the first thing to do. */
    private fun OnboardingUiState.at(step: OnboardingStep): OnboardingUiState =
        if (step == OnboardingStep.Children && children.isEmpty()) {
            copy(step = step, children = listOf(ChildDraft(ids.newId())))
        } else {
            copy(step = step)
        }

    fun setFamilyName(v: String) = _state.update { it.copy(familyName = v) }

    fun setMyName(v: String) = _state.update { it.copy(myName = v) }

    fun setAdultName(v: String) = _state.update { it.copy(adultName = v) }

    /** Adds the typed name as another adult. Blank names and names already in the list are ignored. */
    fun addAdult() = _state.update { it.withAdultAdded() }

    private fun OnboardingUiState.withAdultAdded(): OnboardingUiState {
        val name = adultName.trim()
        if (name.isEmpty()) return copy(adultName = "")
        if (adults.any { it.name.equals(name, ignoreCase = true) }) return copy(adultName = "")
        return copy(adults = adults + AdultDraft(ids.newId(), name), adultName = "")
    }

    fun removeAdult(id: String) = _state.update { s -> s.copy(adults = s.adults.filterNot { it.id == id }) }

    fun addChild() = _state.update { it.copy(children = it.children + ChildDraft(ids.newId())) }

    fun updateChild(id: String, transform: (ChildDraft) -> ChildDraft) =
        _state.update { s -> s.copy(children = s.children.map { if (it.id == id) transform(it) else it }) }

    fun removeChild(id: String) = _state.update { s -> s.copy(children = s.children.filterNot { it.id == id }) }

    /** Typing something else than the chosen place's name starts a new search, so nothing hidden stays chosen. */
    fun setPlaceQuery(q: String) = _state.update { s ->
        s.copy(placeQuery = q, place = s.place?.takeIf { it.name.isNotBlank() && it.name.equals(q.trim(), ignoreCase = true) })
    }

    /** The chosen place's name goes into the search field, which then shows it as the chosen row. */
    fun choosePlace(place: Place?) = _state.update { it.copy(place = place, placeQuery = place?.name ?: it.placeQuery) }

    /** Uses the phone's coarse location: the nearest town gives the place a friendly name. */
    fun useLocation(latitude: Double, longitude: Double) {
        val nearest = SwedishPlaces.all.minByOrNull { abs(it.latitude - latitude) + abs(it.longitude - longitude) * LON_SCALE }
        val name = nearest?.name.orEmpty()
        _state.update { it.copy(place = Place(name, latitude, longitude), placeQuery = name) }
    }

    private fun finish(defaultFamilyName: String) {
        val s = _state.value
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val name = s.familyName.ifBlank { defaultFamilyName }
            family.createFamily(name, s.myName)
            val used = mutableListOf(MemberColor.Sage)
            s.children.filter { it.name.isNotBlank() }.forEach { draft ->
                val color = nextChildColor(used).also { used += it }
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
            // Names only, no account: they can be chosen as the one who drops off or picks up right away, and be
            // invited later (the invite sets the role they get). Caregiver is the least they need for that.
            s.adults.forEach { adult ->
                val color = nextAdultColor(used).also { used += it }
                family.saveMember(Member(adult.id, adult.name, Role.Caregiver, color))
            }
            s.place?.let { p -> family.saveSettings { it.copy(weatherPlace = p) } }
            _state.update { it.copy(saving = false, step = OnboardingStep.Done, role = sessions.session.value.role) }
        }
    }

    /** "Visa min dag": onboarding is done and the shell takes over. */
    fun showMyDay(onDone: () -> Unit) {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            prefs.set(Prefs.OnboardingDone, true)
            _state.update { it.copy(saving = false) }
            onDone()
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

        /** Adults start on Teal and Slate: terracotta means "reminder", and Ocean and Plum go to the children. */
        val adultStartColors = listOf(MemberColor.Teal, MemberColor.Slate, MemberColor.Ochre, MemberColor.Rose)

        fun nextAdultColor(used: Collection<MemberColor>): MemberColor =
            adultStartColors.firstOrNull { it !in used } ?: MemberColor.nextFree(used)
    }
}
