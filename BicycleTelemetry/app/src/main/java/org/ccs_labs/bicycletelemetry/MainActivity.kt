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
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.SeekBar
import java.lang.NumberFormatException

const val DEBUG_TAG : String = "BikeMain"

const val STATE_SERVER_ADDRESS = "serverAddress"
const val STATE_LOW_PASS_CUTOFF = "lowPassCutoff"
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
    private var mLowPassCutoffFrequency : Float = 0f
    private var mPreviousLowPassStepTimeMillis : Long = 0
    private var mLowPassPreviousOutputAngle : Double = 0.0


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

        sbLowPassCutoff.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                etLowPassCutoff.setText((progress / 10.0).toString())
            }
        })

        etLowPassCutoff.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                try {
                    val cof = s.toString().toFloat()
                    sbLowPassCutoff.progress = (cof * 10.0).toInt()
                    mLowPassCutoffFrequency = cof
                    saveCurrentLowPassCutoff()
                } catch (e: NumberFormatException) {}
            }
        })

        if (savedInstanceState != null) {
            /*
            Restore app state. (Needed e.g. for persistence after device rotation.)
            Won't happen if the app was restarted.
             */
            //Log.d(DEBUG_TAG, "Loading saved instance state")
            mCommunicatorStarted = savedInstanceState.getBoolean(STATE_COMMUNICATOR_STARTED)
            val addr = savedInstanceState.getString(STATE_SERVER_ADDRESS)
            etServerAddress.setText(if (addr != null && addr.isNotEmpty()) addr else getSavedServerAddress())
            val lowPassCutoff = savedInstanceState.getFloat(
                STATE_LOW_PASS_CUTOFF, getString(R.string.low_pass_cutoff_default).toFloat())
            etLowPassCutoff.setText(lowPassCutoff.toString())
        } else {
            /* No saved app instance -> try to load settings from previous sessions, else use defaults: */
            etServerAddress.setText(getSavedServerAddress())
            /* Low-pass filter cutoff frequency.
             * Setting this value on etLowPassCutoff should cause it to also be applied to the SeekBar
             * and to the sensor value smoothing process.
             * See the TextWatcher above. */
            etLowPassCutoff.setText(getSavedLowPassCutoff().toString())
        }

        mPreviousLowPassStepTimeMillis = System.currentTimeMillis() // initialization
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
            R.id.action_default_low_pass_cutoff -> {
                etLowPassCutoff.setText(getString(R.string.low_pass_cutoff_default))
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

    private fun saveCurrentLowPassCutoff() {
        val sharedPref = getPreferences(Context.MODE_PRIVATE) ?: return
        with (sharedPref.edit()) {
            putFloat(getString(R.string.sharedpref_key_low_pass_cutoff), mLowPassCutoffFrequency)
            apply()
        }
    }

    private fun getSavedLowPassCutoff() : Float {
        val sharedPref = getPreferences(Context.MODE_PRIVATE) ?:
            return getString(R.string.low_pass_cutoff_default).toFloat()
        return sharedPref.getFloat(
            getString(R.string.sharedpref_key_low_pass_cutoff), getString(R.string.low_pass_cutoff_default).toFloat())
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

//        mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
//            mSensorManager.registerListener(
//                this,
//                accelerometer,
//                // SensorManager.SENSOR_DELAY_FASTEST,
//                SensorManager.SENSOR_DELAY_GAME,
//                SensorManager.SENSOR_DELAY_GAME
//            )
//        }
//        mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magneticField ->
//            mSensorManager.registerListener(
//                this,
//                magneticField,
//                // SensorManager.SENSOR_DELAY_FASTEST,
//                SensorManager.SENSOR_DELAY_GAME,
//                SensorManager.SENSOR_DELAY_GAME
//            )
//        }
        mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.also { rotationVector ->
            mSensorManager.registerListener(
                this,
                rotationVector,
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
        outState.putFloat(STATE_LOW_PASS_CUTOFF, mLowPassCutoffFrequency)
        outState.putBoolean(STATE_COMMUNICATOR_STARTED, mCommunicatorStarted)

        super.onSaveInstanceState(outState)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val rotationMatrix = FloatArray(9)

        // Normalize angle again b/c who knows what toDegrees will do to my previously normalized angle in radians.
        //steeringAngleVisualization.currentAzimuth = getCurrentAzimuth().toFloat()
        when (event.sensor.type){
            Sensor.TYPE_ACCELEROMETER ->
                System.arraycopy(event.values, 0, mAccelerometerReading, 0, mAccelerometerReading.size)
            Sensor.TYPE_MAGNETIC_FIELD ->
                System.arraycopy(event.values, 0, mMagnetometerReading, 0, mMagnetometerReading.size)
            Sensor.TYPE_ROTATION_VECTOR ->
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            else -> return
        }

        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrix(
                rotationMatrix,
                null,
                mAccelerometerReading,
                mMagnetometerReading
            )
        }
        synchronized(mOrientationAngles) {
            SensorManager.getOrientation(rotationMatrix, mOrientationAngles)
        }

        tvCurrentSteeringAngle.text = getString(R.string.current_steering_angle_format).format(
            normalizeAngleDegrees(Math.toDegrees(getCurrentAzimuth()))
            // Normalize angle again b/c who knows what toDegrees will do to my previously normalized angle in radians.
        )
        //steeringAngleVisualization.currentAzimuth = getCurrentAzimuth().toFloat()
    }

    private fun modulo(a: Double, n: Double): Double {
        var r = (a).rem(n)
        if (r < 0) r += n
        return r
    }

    fun normalizeAngleDegrees(deg: Double) : Double {
        return modulo(deg + 180.0, 360.0) - 180.0
    }

    fun normalizeAngleRadians(rad: Double) : Double {
        return modulo(rad + Math.PI, 2 * Math.PI) - Math.PI
    }

    fun getCurrentAzimuth() : Double {
        val dt = (System.currentTimeMillis() - mPreviousLowPassStepTimeMillis).toDouble() / 1000.0
        val alpha = ((2.0 * Math.PI * dt * mLowPassCutoffFrequency) /
                (2.0 * Math.PI * dt * mLowPassCutoffFrequency + 1.0))
        // new_angle = alpha * new_angle + (1.0 - alpha) * self._low_pass_prev_output_angle
        var newAngle = normalizeAngleRadians(
            (if (cbInvertRotation.isChecked) -1.0 else 1.0) *
                    (mOrientationAngles[0] - mOrientationStraight[0]).toDouble()
        )
        if (mLowPassCutoffFrequency > .001) {
            /* Assume low-pass filter enabled. */
            newAngle = alpha * newAngle + (1.0 - alpha) * mLowPassPreviousOutputAngle
            mLowPassPreviousOutputAngle = newAngle
        }

        mPreviousLowPassStepTimeMillis = System.currentTimeMillis()
        return normalizeAngleRadians(newAngle)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        /* Nothing to do here */
    }
}
