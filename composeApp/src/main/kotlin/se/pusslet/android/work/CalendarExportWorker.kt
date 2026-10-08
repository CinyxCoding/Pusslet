package se.pusslet.android.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import se.pusslet.data.usecase.DeviceCalendarExport

/** Keeps the phone's calendar in step with the family even when the app has not been opened (changes from others). */
class CalendarExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    private val export: DeviceCalendarExport by inject()

    override suspend fun doWork(): Result {
        export.syncOnce()
        return Result.success()
    }
}
