package com.stash.core.data.sync

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** In-process handles for independently cancellable queue-item coroutines. */
@Singleton
class DownloadJobRegistry @Inject constructor() {
    private val jobs = ConcurrentHashMap<Long, Job>()

    fun register(queueId: Long, job: Job) {
        jobs.put(queueId, job)?.cancel()
    }

    fun unregister(queueId: Long, job: Job) {
        jobs.remove(queueId, job)
    }

    fun cancel(queueId: Long) {
        jobs[queueId]?.cancel()
    }
}
