package se.pusslet.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import se.pusslet.core.model.Role
import se.pusslet.data.db.PreferenceDao
import se.pusslet.data.db.PreferenceEntity

/**
 * Who is using the app on this device. Works without any account: [familyId] and [memberId] are created locally
 * during onboarding; [uid] is filled in once the user signs in (or anonymously in the demo flavor).
 */
@Serializable
data class Session(
    val familyId: String? = null,
    val memberId: String? = null,
    val uid: String? = null,
    val role: Role = Role.Parent,
    val demo: Boolean = false,
) {
    val hasFamily: Boolean get() = familyId != null && memberId != null
    val canSync: Boolean get() = hasFamily && uid != null
}

interface SessionProvider {
    val session: StateFlow<Session>

    /** False until the stored session has been read at start-up. */
    val loaded: StateFlow<Boolean>

    suspend fun update(transform: (Session) -> Session)
}

/** Persists the session in the local preferences table. */
class SessionRepository(private val prefs: PreferenceDao, private val json: Json = Json { ignoreUnknownKeys = true }) : SessionProvider {
    private val state = MutableStateFlow(Session())
    override val session: StateFlow<Session> = state.asStateFlow()
    private val isLoaded = MutableStateFlow(false)
    override val loaded: StateFlow<Boolean> = isLoaded.asStateFlow()

    suspend fun load(): Session {
        val stored = prefs.get(KEY)?.let { runCatching { json.decodeFromString(Session.serializer(), it) }.getOrNull() }
        state.value = stored ?: Session()
        isLoaded.value = true
        return state.value
    }

    override suspend fun update(transform: (Session) -> Session) {
        val next = transform(state.value)
        prefs.put(PreferenceEntity(KEY, json.encodeToString(Session.serializer(), next)))
        state.value = next
    }

    private companion object {
        const val KEY = "session"
    }
}

/** In-memory session for tests and previews. */
class FixedSessionProvider(initial: Session) : SessionProvider {
    private val state = MutableStateFlow(initial)
    override val session: StateFlow<Session> = state.asStateFlow()
    override val loaded: StateFlow<Boolean> = MutableStateFlow(true)

    override suspend fun update(transform: (Session) -> Session) {
        state.value = transform(state.value)
    }
}
