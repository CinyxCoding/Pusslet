package se.pusslet.data.di

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The scope for work that outlives a screen ([AppScope]): sync, the phone calendar export, widgets, notification planning
 * and push. The jobs catch what they expect themselves; anything else is logged here (its kind and stack, no content)
 * instead of taking the whole app down, and the other jobs keep running.
 */
fun backgroundScope(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    onFailure: (Throwable) -> Unit = { e -> log.e(e) { "A background job failed: ${e::class.simpleName}" } },
): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> onFailure(e) })

private val log = Logger.withTag("Background")
