package se.pusslet.feature.calendar

import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import se.pusslet.data.di.AppScope
import se.pusslet.data.sync.SyncEngine
import se.pusslet.feature.calendar.attachments.AttachImageViewModel
import se.pusslet.feature.calendar.attachments.AttachmentsViewModel
import se.pusslet.feature.calendar.details.EventDetailsViewModel
import se.pusslet.feature.calendar.editor.EventEditorViewModel
import se.pusslet.feature.calendar.subscriptions.SubscriptionsViewModel

val calendarModule: Module = module {
    viewModel { CalendarViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { CalendarDayViewModel(get(), get(), get(), get(), get(), get(), get()) }
    viewModel { EventEditorViewModel(get(), get(), get(), get(), get()) }
    viewModel { EventDetailsViewModel(get(), get(), get(), get(), get(), get<SyncEngine>().status, get(), get()) }
    viewModel { SubscriptionsViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { AttachmentsViewModel(get()) }
    viewModel { AttachImageViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(AppScope)) }
}
