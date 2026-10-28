package se.pusslet.core.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A family (one home), or – with [shared] – the space two homes share for the children they have together (ADR-056).
 * A shared space only holds what is about those children; each home keeps everything else to itself.
 */
@Serializable
data class Family(
    val id: String,
    val name: String,
    val timeZoneId: String = "Europe/Stockholm",
    val createdBy: String = "",
    val createdAt: Instant? = null,
    /** This document is a space shared by two homes, not a home. */
    val shared: Boolean = false,
    /** For a shared space: the homes taking part. Their parents may join without an invite. */
    val familyIds: List<String> = emptyList(),
    /** For a home: the spaces it shares with another home. */
    val sharedSpaces: List<String> = emptyList(),
    /** For a shared space: the children's allergies and diets are shared too (opt-in, sensitive). */
    val shareDiets: Boolean = false,
)

@Serializable
enum class Role {
    /** Full access. */
    @SerialName("parent")
    Parent,

    /** Other adult (e.g. a grandparent): sees and checks off what is shared with them. */
    @SerialName("caregiver")
    Caregiver,

    /** V2: simplified child view. */
    @SerialName("child")
    Child,
}

/**
 * An adult in the family. [id] is a family-scoped person id (works before anyone has an account);
 * [uid] links the person to a Firebase account once they sign in or accept an invite.
 */
@Serializable
data class Member(
    val id: String,
    val displayName: String,
    val role: Role = Role.Parent,
    val color: MemberColor = MemberColor.Sage,
    val uid: String? = null,
    val joinedAt: Instant? = null,
    /** The invite used to join; validated by security rules on create. */
    val inviteCode: String? = null,
    /** What the family calls this person ("Morfar", "Mamma"). Understood by quick add. */
    val nickname: String? = null,
)

/** A child profile. Children never need an account; only a first name is required (data minimisation). */
@Serializable
data class Child(
    val id: String,
    val firstName: String,
    val color: MemberColor = MemberColor.Ocean,
    /** A single emoji or null to show the initial. No photos are needed. */
    val avatar: String? = null,
    /** Optional; used only for age-based clothing advice. */
    val birthDate: LocalDate? = null,
    val school: SchoolRef? = null,
    val className: String? = null,
    val lunch: LunchSource? = null,
    val sizes: List<ClothingSize> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val notes: String = "",
    /** Households the child lives in (prepared for shared custody across two families). */
    val householdIds: List<String> = emptyList(),
)

@Serializable
enum class SchoolKind {
    @SerialName("preschool")
    Preschool,

    @SerialName("school")
    School,

    @SerialName("other")
    Other,
}

@Serializable
data class SchoolRef(
    val name: String,
    /** Skolverket skolenhetskod (8 digits), null for preschools and free-text entries. */
    val schoolUnitCode: String? = null,
    val municipalityCode: String? = null,
    val kind: SchoolKind = SchoolKind.School,
)

@Serializable
enum class LunchProviderType {
    @SerialName("skolmaten")
    Skolmaten,

    /** Any RSS feed URL the user pastes. */
    @SerialName("rss")
    Rss,

    /** Matilda (Matilda Foodtech, formerly FoodIT): the school's menu link on menu.matildaplatform.com (ADR-053). */
    @SerialName("matilda")
    Matilda,

    @SerialName("manual")
    Manual,
}

@Serializable
data class LunchSource(
    /** Defaults so that an app version that does not know a newer provider reads it as manual instead of failing. */
    val provider: LunchProviderType = LunchProviderType.Manual,
    val reference: String = "",
)

@Serializable
enum class ClothingCategory {
    @SerialName("pants")
    Pants,

    @SerialName("top")
    Top,

    @SerialName("shoes")
    Shoes,

    @SerialName("outerwear")
    Outerwear,
}

/** One size observation; the list keeps the history so you can see when shoes got too small. */
@Serializable
data class ClothingSize(val category: ClothingCategory, val size: String, val notedOn: LocalDate)

@Serializable
data class Contact(
    val name: String,
    /** Free text like "Lärare", "Fritids", "Veras mamma". */
    val relation: String = "",
    val phone: String? = null,
    val email: String? = null,
)

/** Anyone an event or task can be about: adult members and children share one id space. */
data class FamilyPerson(
    val id: String,
    val name: String,
    val color: MemberColor,
    val isChild: Boolean,
    val role: Role? = null,
    val avatar: String? = null,
    val birthDate: LocalDate? = null,
    /** Other names the family uses ("morfar"). */
    val aliases: List<String> = emptyList(),
    /** An adult of the other home (from the shared space): shown by name, never offered for this home's own things. */
    val otherHome: Boolean = false,
) {
    /** Colour is never the only signal: UI shows this initial (or the avatar emoji) on the colour. */
    val initial: String get() = avatar ?: name.trim().take(1).uppercase().ifEmpty { "?" }

    companion object {
        fun of(member: Member) = FamilyPerson(
            member.id,
            member.displayName,
            member.color,
            isChild = false,
            role = member.role,
            aliases = listOfNotNull(member.nickname?.takeIf { it.isNotBlank() }),
        )

        fun of(child: Child) = FamilyPerson(
            child.id,
            child.firstName,
            child.color,
            isChild = true,
            avatar = child.avatar,
            birthDate = child.birthDate,
        )
    }
}

/** Opt-in sensitive data. Used only to filter recipes and warn; can be deleted at any time. */
@Serializable
data class DietProfile(
    val personId: String,
    val allergies: Set<Allergen> = emptySet(),
    val diets: Set<Diet> = emptySet(),
    val otherNotes: String = "",
    /** When the family agreed to store this information (shown in the privacy view). */
    val consentedAt: Instant? = null,
)

@Serializable
enum class Allergen {
    Milk,
    Lactose,
    Egg,
    Gluten,
    Peanut,
    TreeNuts,
    Fish,
    Shellfish,
    Soy,
    Sesame,
    Celery,
    Mustard,
    Lupin,
    Sulphites,
}

@Serializable
enum class Diet {
    Vegetarian,
    Vegan,
    Pescetarian,
    Halal,
    NoPork,
    Kosher,
}
