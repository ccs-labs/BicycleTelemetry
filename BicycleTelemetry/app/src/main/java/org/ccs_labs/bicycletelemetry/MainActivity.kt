package org.ccs_labs.bicycletelemetry

// For view binding (so we don't have to use findViewById all the time):
import kotlinx.android.synthetic.main.activity_main.*

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem

const val DEBUG_TAG : String = "BikeMain"

const val STATE_SERVER_ADDRESS = "serverAddress"
const val STATE_COMMUNICATOR_STARTED = "communicatorStarted"

class MainActivity : AppCompatActivity(), SensorEventListener {
    private var communicator : Communicator? = null

    // Orientation readings according to https://developer.android.com/guide/topics/sensors/sensors_position
    private lateinit var mSensorManager : SensorManager
    private val mAccelerometerReading = FloatArray(3)
    private val mMagnetometerReading = FloatArray(3)

    /**
     * Indicates whether we should attempt to restore the communicator on resume or not.
     */
    var mCommunicatorStarted = false

    val mOrientationAngles = FloatArray(3)
    private val mOrientationStraight = FloatArray(3) { 0f }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btConnect.setOnClickListener {
            // `mCommunicatorStarted` will be set from within the communicator itself!
            if (!mCommunicatorStarted) {
                saveCurrentServerAddress()
                initializeCommunicator()
            } else {
                communicator?.close()
            }
        }

        btResetStraight.setOnClickListener {
            mOrientationStraight[0] = mOrientationAngles[0]
            mOrientationStraight[1] = mOrientationAngles[1]
            mOrientationStraight[2] = mOrientationAngles[2]
            // TODO: find a more Kotlin way of doing this…
        }

        if (savedInstanceState != null) {
            /*
            Restore app state. (Needed e.g. for persistence after device rotation.)
            Won't happen if the app was restarted.
             */
            //Log.d(DEBUG_TAG, "Loading saved instance state")
            mCommunicatorStarted = savedInstanceState.getBoolean(STATE_COMMUNICATOR_STARTED)
            val t = savedInstanceState.getString(STATE_SERVER_ADDRESS)
            etServerAddress.setText(if (t != null && t.isNotEmpty()) t else getSavedServerAddress())
        } else {
            /* No saved app instance -> try to load settings from previous sessions, else use defaults: */
            etServerAddress.setText(getSavedServerAddress())
        }

        mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_activity_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        return when (item?.itemId) {
            R.id.action_default_server -> {
                etServerAddress.setText(getString(R.string.server_address_default))
                true
            }
            else -> {
                super.onOptionsItemSelected(item)
            }
        }
    }

    private fun saveCurrentServerAddress() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE) ?: return
        with (sharedPref.edit()) {
            putString(getString(R.string.sharedprefs_key_server_address), etServerAddress.text.toString())
            apply()
        }
    }

    private fun getSavedServerAddress() : String {
        val sharedPref = getPreferences(Context.MODE_PRIVATE) ?: return getString(R.string.server_address_default)
        return sharedPref.getString(
            getString(R.string.sharedprefs_key_server_address), getString(R.string.server_address_default)) as String
    }

    private fun initializeCommunicator() {
        communicator?.close()
        communicator = Communicator(
            address = etServerAddress.text.toString(),
            intervalMillis = 50,
            activity = this
        )
        communicator!!.start() // TODO: stop on pause, restart on resume
        mCommunicatorStarted = true
    }

    override fun onResume() {
        super.onResume()

        mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
            mSensorManager.registerListener(
                this,
                accelerometer,
                // SensorManager.SENSOR_DELAY_FASTEST,
                SensorManager.SENSOR_DELAY_GAME,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
        mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magneticField ->
            mSensorManager.registerListener(
                this,
                magneticField,
                // SensorManager.SENSOR_DELAY_FASTEST,
                SensorManager.SENSOR_DELAY_GAME,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        if (mCommunicatorStarted) {
            initializeCommunicator()
        }
    }

    override fun onPause() {
        super.onPause()

        mSensorManager.unregisterListener(this)
        communicator?.close()
        communicator = null
    }

    override fun onDestroy() {
        super.onDestroy()

        communicator?.close()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        /*
        Save current app state. (Needed e.g. for persistence after device rotation.)
         */
        outState.putString(STATE_SERVER_ADDRESS, etServerAddress.text.toString())
        outState.putBoolean(STATE_COMMUNICATOR_STARTED, mCommunicatorStarted)

        super.onSaveInstanceState(outState)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, mAccelerometerReading, 0, mAccelerometerReading.size)
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, mMagnetometerReading, 0, mMagnetometerReading.size)
        }

        val rotationMatrix = FloatArray(9)

        SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            mAccelerometerReading,
            mMagnetometerReading
        )
        synchronized(mOrientationAngles) {
            SensorManager.getOrientation(rotationMatrix, mOrientationAngles)
        }

        tvCurrentSteeringAngle.text = getString(R.string.current_steering_angle_format).format(
            normalizeAngleDegrees(Math.toDegrees(getCurrentAzimuth()))
            // Normalize angle again b/c who knows what toDegrees will do to my previously normalized angle in radians.
        )
        steeringAngleVisualization.currentAzimuth = getCurrentAzimuth().toFloat()
    }

    fun normalizeAngleDegrees(deg: Double) : Double {
        return (deg + 180) % 360 - 180
    }

    fun normalizeAngleRadians(rad: Double) : Double {
        return (rad + Math.PI) % (2 * Math.PI) - Math.PI
    }

    fun getCurrentAzimuth() : Double {
        return normalizeAngleRadians(
            (if (cbInvertRotation.isChecked) -1.0 else 1.0) *
                    (mOrientationAngles[0] - mOrientationStraight[0]).toDouble()
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        /* Nothing to do here */
    }
}
