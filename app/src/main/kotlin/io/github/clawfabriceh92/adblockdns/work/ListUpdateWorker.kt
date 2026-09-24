package io.github.clawfabriceh92.adblockdns.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.clawfabriceh92.adblockdns.appContainer
import io.github.clawfabriceh92.adblockdns.data.UpdateOutcome
import java.util.concurrent.TimeUnit

/** Mise à jour automatique des listes (option « Mise à jour automatique », désactivée par défaut). */
class ListUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val outcomes = applicationContext.appContainer.blocklists.updateAll()
        return if (outcomes.values.any { it is UpdateOutcome.Failed }) Result.retry() else Result.success()
    }
}

object ListUpdateScheduler {
    private const val WORK_NAME = "maj-listes"

    /** Une fois par semaine, en Wi-Fi (réseau non facturé) et batterie correcte. */
    fun apply(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<ListUpdateWorker>(7, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
