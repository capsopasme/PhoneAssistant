package com.capsopasme.assistant.memory

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Distills finished calls into memory ([MemoryDistiller]) as a system job: it runs once there's a
 * network, is retried with backoff when the network or the server fails, and still happens if the
 * app was closed in between. Nothing stays running: the job ends when the waiting calls are done.
 */
class MemoryJobService : JobService() {

    @Volatile
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread({
            val retry = try {
                MemoryDistiller.runPending(this)
            } catch (e: Exception) {
                Log.w(TAG, "distilling crashed", e)
                false
            }
            jobFinished(params, retry)
        }, "memory-distill").apply { start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // network lost, or a newer call scheduled it again: what isn't saved yet is redone later
        MemoryDistiller.cancel()
        worker?.interrupt()
        return true
    }

    companion object {
        private const val TAG = "MemoryJob"
        private const val JOB_ID = 0x4C55 // "LU"

        /** distill the calls waiting (soon, once there is a network) */
        fun schedule(ctx: Context) {
            val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, MemoryJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(60_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                // a call that ended offline is still distilled after a reboot (needs
                // RECEIVE_BOOT_COMPLETED), not only once the next call starts
                .setPersisted(true)
                .build()
            try {
                ctx.getSystemService(JobScheduler::class.java).schedule(info)
            } catch (e: Exception) {
                Log.w(TAG, "can't schedule", e)
            }
        }
    }
}
