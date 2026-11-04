package se.pusslet.core.account

/** Where "Glömt lösenordet?" is (ADR-064). */
enum class PasswordResetState {
    Idle,

    Sending,

    /** Said the same whether or not the address has an account, so nobody can use it to find out who uses the app. */
    Sent,

    /** The address is missing, or cannot be an e-mail address. */
    BadEmail,

    /** No contact with the server. */
    Failed,
}
