package org.ccs_labs.bicycletelemetry

import android.util.Log
import kotlinx.android.synthetic.main.activity_main.*
import org.zeromq.ZMQ
import org.zeromq.ZMQException
import java.io.Closeable

const val COMMUNICATOR_DEBUG_TAG : String = "BikeCommunicator"

class Communicator(
    private val address: String,
    private val intervalMillis: Long,
    private val activity: MainActivity
) : Closeable, Thread() {

    private lateinit var context: ZMQ.Context
    private var isConnected = false

    override fun run() {
        try {
            context = ZMQ.context(1)
            val sendSocket: ZMQ.Socket = context.socket(ZMQ.PUSH)
            isConnected = sendSocket.connect(address)
            var previousTime: Long = System.currentTimeMillis()

            activity.runOnUiThread {
                activity.tvConnectionStatus.text = if (isConnected) "Connected" else "Unable to connect"
            }
            if (!isConnected) {
                return
            }

            while (!Thread.currentThread().isInterrupted) {
                // send: http://api.zeromq.org/2-1:zmq-send
                // missing message problem solver: http://zguide.zeromq.org/page:all#Missing-Message-Problem-Solver

                synchronized(activity.mOrientationAngles) {
                    val success = sendSocket.send(
                        (activity.normalizeAngleRadians((if (activity.cbInvertRotation.isChecked) -1 else 1) *
                                (activity.mOrientationAngles[0] - activity.mOrientationStraight[0]) as Double))
                            .toString()
                    )
                    if (success) {
                        Log.d(COMMUNICATOR_DEBUG_TAG, "Sent " +
                                (activity.mOrientationAngles[0] - activity.mOrientationStraight[0]).toString())
                    } else {
                        // TODO: is this necessary or does JeroMQ produce regular exceptions?
                        // TODO: try to reconnect (sometimes the app shows "connected" even when no server is running)
                        when (sendSocket.errno()) {
                            ZMQ.Error.EAGAIN.code -> Log.e(COMMUNICATOR_DEBUG_TAG, "Send: EAGAIN")
                            ZMQ.Error.ENOTSUP.code -> Log.e(COMMUNICATOR_DEBUG_TAG, "Send: ENOTSUP")
                            ZMQ.Error.EFSM.code -> Log.e(COMMUNICATOR_DEBUG_TAG, "Send: EFSM")
                            ZMQ.Error.ETERM.code -> Log.e(COMMUNICATOR_DEBUG_TAG, "Send: ETERM")
                            ZMQ.Error.ENOTSOCK.code -> Log.e(COMMUNICATOR_DEBUG_TAG, "Send: ENOTSOCK")
                            else -> Log.e(COMMUNICATOR_DEBUG_TAG, "Send: errno " + sendSocket.errno())
                        }
                    }
                }

                //Log.d(COMMUNICATOR_DEBUG_TAG, "Sleeping for " +
                //        Math.max(0, intervalMillis - (System.currentTimeMillis() - previousTime)).toString() +
                //        " ms, delta = " + (System.currentTimeMillis() - previousTime).toString() + " ms"
                //)
                Thread.sleep(Math.max(0, intervalMillis - (System.currentTimeMillis() - previousTime)))
                //Thread.sleep(intervalMillis)
                previousTime = System.currentTimeMillis()
            }
        } catch (e: IllegalArgumentException) {
            activity.runOnUiThread { activity.tvConnectionStatus.text = "Invalid server address" }
        } catch (e: ZMQException) {
            activity.runOnUiThread { activity.tvConnectionStatus.text = "Unable to connect: " + e.localizedMessage }
        } catch (e: java.lang.Exception) {
            activity.runOnUiThread { activity.tvConnectionStatus.text = "Exception: " + e.toString() }
        }
    }

    override fun close() {
        if (!context.isClosed) {
            context.close()
        }
    }
}
