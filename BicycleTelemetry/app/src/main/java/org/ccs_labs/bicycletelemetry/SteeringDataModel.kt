package org.ccs_labs.bicycletelemetry

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.work.*
import org.ccs_labs.bicycletelemetry.SteeringSensorWorker.Companion.SteeringAngleDataKey

/**
 * This class stores the state data that will live as long as
 * SteeringSensorWorker is running in the background,
 * even if MainActivity is paused or recreated.
 *
 * Instead of dealing with string data directly, this class should
 * only ever set the string IDs from `R.string`.
 * This way, if the system locale changes while the app is running,
 * MainActivity will be recreated automatically and it will be able
 * to use the string ID stored here to display the correct string value.
 * https://medium.com/androiddevelopers/locale-changes-and-the-androidviewmodel-antipattern-84eb677660d9
 *
 * …on the other hand, this approach doesn't let us set formatted text.
 * So let's not do that after all. Feel free to deal with string data directly.
 */

class SteeringDataModel(application: Application) : AndroidViewModel(application) {
    private val workManager = WorkManager.getInstance(application)

    val currentAzimuth: MutableLiveData<Double> by lazy {
        MutableLiveData<Double>(0.0)
    }
    val statusText: MutableLiveData<String> by lazy {
        MutableLiveData<String>("")
    }
    val stopped: MutableLiveData<Boolean> by lazy {
        MutableLiveData<Boolean>(true)
    }
    val serverAddress: MutableLiveData<String> by lazy {
        MutableLiveData<String>("") // TODO: set this in activity
    }

    internal fun startTransmission() {
        // TODO: Cancel work if already running

        val workData : Data = Data.Builder()
            // .putString("ADDRESS", activityMainBinding.etServerAddress.text.toString())
            .putString("ADDRESS", serverAddress.toString())
            .build()
        val workRequest : WorkRequest = OneTimeWorkRequestBuilder<SteeringSensorWorker>()
            .setInputData(workData)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        // TODO: use workRequest (start it?)


        workManager.getWorkInfoByIdLiveData(workRequest.id)
            .observe(this, Observer { workInfo: WorkInfo? ->
                if (workInfo != null) {
                    val progress = workInfo.progress
                    currentAzimuth.value = progress.getDouble(
                        SteeringAngleDataKey,
                        0.0
                    )
                }
            })
        // `mCommunicatorStarted` will be set from within the communicator itself!
        if (!mCommunicatorStarted) {
            saveCurrentServerAddress()
            // TODO: trigger the following in worker:
            // initializeCommunicator()
        } else {
            // TODO: trigger the following in worker:
            // communicator?.close()
        }
    }
}