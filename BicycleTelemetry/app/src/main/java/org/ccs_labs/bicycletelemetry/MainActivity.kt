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
import java.lang.IllegalArgumentException
import org.zeromq.ZMQException

const val DEBUG_TAG : String = "MainActivity"

class MainActivity : AppCompatActivity(), SensorEventListener {
    private var communicator : Communicator? = null

    // Orientation readings according to https://developer.android.com/guide/topics/sensors/sensors_position
    private lateinit var mSensorManager : SensorManager
    private val mAccelerometerReading = FloatArray(3)
    private val mMagnetometerReading = FloatArray(3)
    val mOrientationAngles = FloatArray(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btConnect.setOnClickListener {
            try {
                communicator = Communicator(
                    address = etServerAddress.text.toString(),
                    intervalMillis = 20,
                    activity = this
                )
                communicator!!.start() // TODO: stop on pause, restart on resume
                tvConnectionStatus.text = if (communicator!!.isConnected) "Connected" else "Unable to connect"
                //sensorReader = SensorReader(this, communicator!!)
            } catch (e: IllegalArgumentException) {
                tvConnectionStatus.text = "Invalid server address"
            } catch (e: ZMQException) {
                tvConnectionStatus.text = "Unable to connect: " + e.localizedMessage
            }
            // communicator?.close()
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
        SensorManager.getOrientation(rotationMatrix, mOrientationAngles)

        tvCurrentSteeringAngle.text = String.format("%.1f", Math.toDegrees(mOrientationAngles[0].toDouble()))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        /* Nothing to do here */
    }
}
