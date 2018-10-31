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
import android.os.PersistableBundle
import android.util.Log
import java.lang.IllegalArgumentException
import org.zeromq.ZMQException

const val DEBUG_TAG : String = "BikeMain"

const val STATE_SERVER_ADDRESS = "serverAddress"

class MainActivity : AppCompatActivity(), SensorEventListener {
    private var communicator : Communicator? = null

    // Orientation readings according to https://developer.android.com/guide/topics/sensors/sensors_position
    private lateinit var mSensorManager : SensorManager
    private val mAccelerometerReading = FloatArray(3)
    private val mMagnetometerReading = FloatArray(3)
    val mOrientationAngles = FloatArray(3)
    val mOrientationStraight = FloatArray(3) { 0f }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btConnect.setOnClickListener {
            communicator = Communicator(
                address = etServerAddress.text.toString(),
                intervalMillis = 50,
                activity = this
            )
            communicator!!.start() // TODO: stop on pause, restart on resume
            //tvConnectionStatus.text = if (communicator!!.isConnected) "Connected" else "Unable to connect"
            //sensorReader = SensorReader(this, communicator!!)

            // communicator?.close()
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
            Log.d(DEBUG_TAG, "Loading saved instance state")
            val t = savedInstanceState.getString(STATE_SERVER_ADDRESS)
            etServerAddress.setText(if (t != null && t.isNotEmpty()) t else "tcp://localhost:15007")
        } else {
            Log.d(DEBUG_TAG, "No saved instance state")
        }

        mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    override fun onResume() {
        super.onResume()

        mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.also { accelerometer ->
            mSensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_FASTEST,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
        mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.also { magneticField ->
            mSensorManager.registerListener(
                this,
                magneticField,
                SensorManager.SENSOR_DELAY_FASTEST,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
    }

    override fun onPause() {
        super.onPause()

        mSensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()

        communicator?.close()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        /*
        Save current app state. (Needed e.g. for persistence after device rotation.)
         */

        Log.d(DEBUG_TAG, "Saving instance state")
        outState.putString(STATE_SERVER_ADDRESS, etServerAddress.text.toString())

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

        tvCurrentSteeringAngle.text = String.format("%.1f",
            Math.toDegrees((mOrientationAngles[0] - mOrientationStraight[0]).toDouble())) + " °"
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        /* Nothing to do here */
    }
}
