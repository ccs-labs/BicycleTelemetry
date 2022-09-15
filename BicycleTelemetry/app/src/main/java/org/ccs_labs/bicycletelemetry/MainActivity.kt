package org.ccs_labs.bicycletelemetry

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.CompoundButton
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkRequest
import org.ccs_labs.bicycletelemetry.databinding.ActivityMainBinding
import kotlin.NumberFormatException

const val DEBUG_TAG : String = "BikeMain"

const val STATE_SERVER_ADDRESS = "serverAddress"
const val STATE_LOW_PASS_CUTOFF = "lowPassCutoff"
const val STATE_COMMUNICATOR_STARTED = "communicatorStarted"
const val STATE_TRANSMIT_DEBUG = "transmitDebug"
const val STATE_GYRO_FUSION = "gyroSensorFusion"

class MainActivity : AppCompatActivity() {
    private lateinit var activityMainBinding: ActivityMainBinding

    /**
     * Indicates whether we should attempt to restore the communicator on resume or not.
     */
    var mCommunicatorStarted = false

    private val mSteeringDataModel: SteeringDataModel by viewModels()
    // `by viewModels()` should provide access to an application-wide instance of
    // the SteeringDataModel view model, if I understand correctly:
    // https://stackoverflow.com/a/54313573/1018176

    private var mPreviousLowPassStepTimeMillis : Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        val view = activityMainBinding.root
        setContentView(view)

        activityMainBinding.btConnect.setOnClickListener {
            mSteeringDataModel.startTransmission()
        }

        activityMainBinding.btResetStraight.setOnClickListener {
            mSteeringDataModel.setStraight()
        }

        if (savedInstanceState != null) {
            /*
            Restore app state. (Needed e.g. for persistence after device rotation.)
            Won't happen if the app was restarted.
             */
            //Log.d(DEBUG_TAG, "Loading saved instance state")
            mCommunicatorStarted = savedInstanceState.getBoolean(STATE_COMMUNICATOR_STARTED)
            val addr = savedInstanceState.getString(STATE_SERVER_ADDRESS)
            activityMainBinding.etServerAddress.setText(if (addr != null && addr.isNotEmpty()) addr else getSavedServerAddress())
            val lowPassCutoff = savedInstanceState.getFloat(
                STATE_LOW_PASS_CUTOFF, getString(R.string.low_pass_cutoff_default).toFloat())
        } else {
            /* No saved app instance -> try to load settings from previous sessions, else use defaults: */
            activityMainBinding.etServerAddress.setText(getSavedServerAddress())
        }

        mPreviousLowPassStepTimeMillis = System.currentTimeMillis() // initialization
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_activity_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_default_server -> {
                activityMainBinding.etServerAddress.setText(getString(R.string.server_address_default))
                true
            }
            else -> {
                super.onOptionsItemSelected(item)
            }
        }
    }

    fun setConnectionStatusText(msg: String) {
        runOnUiThread {
            activityMainBinding.tvConnectionStatus.text = msg
        }
    }

    fun setConnectButtonText(text: String) {
        runOnUiThread {
            activityMainBinding.btConnect.text = text
        }
    }

    // TODO: use!
    private fun saveCurrentServerAddress() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE) ?: return
        with (sharedPref.edit()) {
            putString(getString(R.string.sharedprefs_key_server_address), activityMainBinding.etServerAddress.text.toString())
            apply()
        }
    }

    private fun getSavedServerAddress() : String {
        val sharedPref = getPreferences(Context.MODE_PRIVATE) ?: return getString(R.string.server_address_default)
        return sharedPref.getString(
            getString(R.string.sharedprefs_key_server_address), getString(R.string.server_address_default)) as String
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        /*
        Save current app state. (Needed e.g. for persistence after device rotation.)
         */
        outState.putString(STATE_SERVER_ADDRESS, activityMainBinding.etServerAddress.text.toString())
        outState.putBoolean(STATE_COMMUNICATOR_STARTED, mCommunicatorStarted)

        super.onSaveInstanceState(outState)
    }
}
