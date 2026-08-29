package com.mattdixon.jobjar

import android.net.Uri

/**
 * What an incoming `jobjar://...` Uri (from Home Jobs Tracker's "Send to Job Jar" button, or any
 * other app) is asking this app to do. Parsed explicitly here - rather than relying on
 * Navigation-Compose's declarative `navDeepLink` auto-matching - so the whole handoff is plain,
 * readable code: [MainActivity] parses the incoming Uri once and calls `navController.navigate`
 * itself, for both cold start and an already-running instance ([android.app.Activity.onNewIntent]).
 */
sealed interface IncomingDeepLink {
    /** `jobjar://newjob?title=...&category=...&sourceId=...&estimatedMinutes=...&scheduledDate=...` -
     * [estimatedMinutes] and [scheduledDate] (an ISO "yyyy-MM-dd" string) are both optional,
     * carried over only when Tracker had them set on the job being sent. */
    data class CreateJob(
        val title: String?,
        val category: String?,
        val sourceTrackerJobId: Long?,
        val estimatedMinutes: Int?,
        val scheduledDate: String?,
    ) : IncomingDeepLink

    /** `jobjar://job/{jobId}` */
    data class ViewJob(val jobId: Long) : IncomingDeepLink

    /** `jobjar://pickjob?returnJobId=...` - Tracker wants to link [returnJobId] to a job picked here. */
    data class PickJob(val returnJobId: Long) : IncomingDeepLink

    /** `jobjar://linked?jobId=...&otherId=...` - the return trip: [jobId] (one of our own jobs) is
     * now linked to Tracker job [otherId]; persist that and show the job. */
    data class Linked(val jobId: Long, val otherId: Long) : IncomingDeepLink

    /** `jobjar://unlinked?jobId=...` - the other half of an explicit Unlink action taken on the
     * Tracker side: this app's own job [jobId] should forget whatever Tracker job it was linked
     * to, since Tracker just forgot its half too. */
    data class Unlinked(val jobId: Long) : IncomingDeepLink
}

fun parseIncomingDeepLink(uri: Uri): IncomingDeepLink? = when (uri.host) {
    "newjob" -> IncomingDeepLink.CreateJob(
        title = uri.getQueryParameter("title"),
        category = uri.getQueryParameter("category"),
        sourceTrackerJobId = uri.getQueryParameter("sourceId")?.toLongOrNull(),
        estimatedMinutes = uri.getQueryParameter("estimatedMinutes")?.toIntOrNull(),
        scheduledDate = uri.getQueryParameter("scheduledDate")
    )
    "job" -> uri.pathSegments.firstOrNull()?.toLongOrNull()?.let { IncomingDeepLink.ViewJob(it) }
    "pickjob" -> uri.getQueryParameter("returnJobId")?.toLongOrNull()?.let { IncomingDeepLink.PickJob(it) }
    "linked" -> {
        val jobId = uri.getQueryParameter("jobId")?.toLongOrNull()
        val otherId = uri.getQueryParameter("otherId")?.toLongOrNull()
        if (jobId != null && otherId != null) IncomingDeepLink.Linked(jobId, otherId) else null
    }
    "unlinked" -> uri.getQueryParameter("jobId")?.toLongOrNull()?.let { IncomingDeepLink.Unlinked(it) }
    else -> null
}
