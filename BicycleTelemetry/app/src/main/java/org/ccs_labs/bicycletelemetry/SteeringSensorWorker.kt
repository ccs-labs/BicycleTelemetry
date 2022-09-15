package org.ccs_labs.bicycletelemetry

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import androidx.work.workDataOf
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SteeringSensorWorker(
    appContext: Context,
    workerParams: WorkerParameters
):
    CoroutineWorker(appContext, workerParams),
    SensorEventListener {

    /**
     * Key tags for using setProgress() to notify the main activity etc.
     * of status and sensor values.
     */
    companion object {
        const val SteeringAngleDataKey = "SteeringAngle"
        const val TransmissionException = "TransmissionException"
        const val TransmissionStatus = "TransmissionStatus"
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val sensorEvents = Channel<SensorEvent>(
        capacity=2, // even 1 might be fine; we don't need old information
        onBufferOverflow=BufferOverflow.DROP_OLDEST
    )

    private val mCommunicator = Communicator(
        address = workerParams.inputData.getString("ADDRESS").orEmpty(),
        intervalMillis = 50,
        worker = this,
    )

    // Orientation readings according to https://developer.android.com/guide/topics/sensors/sensors_position
    private lateinit var mSensorManager: SensorManager
    internal var mUseGyroscope: Boolean = true
    internal val mSensorDataMutex: Mutex = Mutex()
    private val mAccelerometerReading = FloatArray(3)
    private val mMagnetometerReading = FloatArray(3)
    internal val mOrientationAngles = FloatArray(3)
    private val mOrientationAnglesWithoutGyro = FloatArray(3)
    private val mOrientationStraight = FloatArray(3) { 0f }
    private val mOrientationStraightWithoutGyro = FloatArray(3) { 0f }
    private var mPreviousLowPassStepTimeMillis : Long = 0

    override suspend fun doWork(): Result {
        mSensorManager =
            applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        mCommunicator.transmitBlocking()
        return Result.success()
    }

    override fun onSensorChanged(p0: SensorEvent?) {
        // Process all events in a coroutine so we're able to call setProgress()…
        // See processSensorEvents()
        p0?.let {
            runBlocking {
                sensorEvents.send(it)
            }
        }
    }

    private fun processSensorEvents() = coroutineScope.launch {
        // Running in a coroutine so we're able to call setProgress()
        // to notify listeners of this worker, mainly to display the
        // current steering angle in the UI.
        initializeSensors(false, false)
        sensorEvents.consumeEach(fun (it: SensorEvent) {
            val rotationMatrix = FloatArray(9)

            // Normalize angle again b/c who knows what toDegrees will do to my previously normalized angle in radians.
            //steeringAngleVisualization.currentAzimuth = getCurrentAzimuth().toFloat()
            // TODO: handle possible NullPointerExceptions
            when (it.sensor?.type) {
                Sensor.TYPE_ACCELEROMETER ->
                    System.arraycopy(
                        it.values,
                        0,
                        mAccelerometerReading,
                        0,
                        mAccelerometerReading.size
                    )
                Sensor.TYPE_MAGNETIC_FIELD ->
                    System.arraycopy(
                        it.values,
                        0,
                        mMagnetometerReading,
                        0,
                        mMagnetometerReading.size
                    )
                Sensor.TYPE_ROTATION_VECTOR ->
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, it.values)
                else -> return
            }

            if (it.sensor.type != Sensor.TYPE_ROTATION_VECTOR) {
                SensorManager.getRotationMatrix(
                    rotationMatrix,
                    null,
                    mAccelerometerReading,
                    mMagnetometerReading
                )
                mSensorDataMutex.withLock {
                    SensorManager.getOrientation(rotationMatrix, mOrientationAnglesWithoutGyro)
                }
            } else {
                mSensorDataMutex.withLock {
                    SensorManager.getOrientation(rotationMatrix, mOrientationAngles)
                }
            }

            setProgress(workDataOf(SteeringAngleDataKey to getCurrentAzimuth(false)))
            /* mSteeringDataModel.statusText.value = applicationContext
            .getString(R.string.current_steering_angle_format).format(
                normalizeAngleDegrees(Math.toDegrees(getCurrentAzimuth(false)))
                // Normalize angle again b/c who knows what toDegrees will do to
                // my previously normalized angle in radians.
               )
             */
            //steeringAngleVisualization.currentAzimuth = getCurrentAzimuth().toFloat()
        })
    }

    override fun onAccuracyChanged(p0: Sensor?, p1: Int) {
        TODO("Not yet implemented")
    }

    private fun initializeSensors(
        useGyroscope: Boolean,
        transmitDebugInfo: Boolean,
    ) {
        mSensorManager.unregisterListener(this)

        if (!useGyroscope || transmitDebugInfo) {
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
        }
        if (useGyroscope) {
            mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.also { rotationVector ->
                mSensorManager.registerListener(
                    this,
                    rotationVector,
                    // SensorManager.SENSOR_DELAY_FASTEST,
                    SensorManager.SENSOR_DELAY_GAME,
                    SensorManager.SENSOR_DELAY_GAME
                )
            }
        }
    }

    private fun modulo(a: Double, n: Double): Double {
        var r = (a).rem(n)
        if (r < 0) r += n
        return r
    }

    private fun normalizeAngleDegrees(deg: Double) : Double {
        return modulo(deg + 180.0, 360.0) - 180.0
    }

    // TODO: use
    private fun resetStraight() {
        mOrientationStraight[0] = mOrientationAngles[0]
        mOrientationStraight[1] = mOrientationAngles[1]
        mOrientationStraight[2] = mOrientationAngles[2]
        mOrientationStraightWithoutGyro[0] = mOrientationAnglesWithoutGyro[0]
        mOrientationStraightWithoutGyro[1] = mOrientationAnglesWithoutGyro[1]
        mOrientationStraightWithoutGyro[2] = mOrientationAnglesWithoutGyro[2]
    }

    private fun normalizeAngleRadians(rad: Double) : Double {
        return modulo(rad + Math.PI, 2 * Math.PI) - Math.PI
    }

    internal fun getCurrentAzimuth(withoutGyro: Boolean = false) : Double {
        val oriAngles = if (withoutGyro) mOrientationAnglesWithoutGyro else mOrientationAngles
        val straight = if (withoutGyro) mOrientationStraightWithoutGyro else mOrientationStraight

        val newAngle: Double = normalizeAngleRadians(
            (oriAngles[0] - straight[0]).toDouble()
        )

        mPreviousLowPassStepTimeMillis = System.currentTimeMillis()
        return normalizeAngleRadians(newAngle)
    }

}