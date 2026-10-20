package com.vidgrab.app.core.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vidgrab.app.core.queue.QueueRepository
import timber.log.Timber

class DownloadActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PAUSE = "com.vidgrab.app.action.PAUSE_DOWNLOAD"
        const val ACTION_RESUME = "com.vidgrab.app.action.RESUME_DOWNLOAD"
        const val ACTION_CANCEL = "com.vidgrab.app.action.CANCEL_DOWNLOAD"
        const val EXTRA_TASK_ID = "extra_task_id"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return

        val repo = QueueRepository(context.applicationContext)
        when (action) {
            ACTION_PAUSE -> {
                Timber.i("DownloadActionReceiver: Pause requested for %s", taskId)
                repo.pauseTask(taskId)
            }
            ACTION_RESUME -> {
                Timber.i("DownloadActionReceiver: Resume requested for %s", taskId)
                repo.resumeTask(taskId)
            }
            ACTION_CANCEL -> {
                Timber.i("DownloadActionReceiver: Cancel requested for %s", taskId)
                repo.cancelTask(taskId)
            }
        }
    }
}
