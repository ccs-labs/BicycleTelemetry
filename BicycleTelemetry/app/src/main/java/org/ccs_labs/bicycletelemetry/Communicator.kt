package org.ccs_labs.bicycletelemetry

import android.util.Log
import kotlinx.android.synthetic.main.activity_main.*
import java.io.Closeable
import java.io.IOException
import java.net.*
import java.util.*

const val COMMUNICATOR_DEBUG_TAG : String = "BikeCommunicator"

class Communicator(
    private val address: String,
    private val intervalMillis: Long,
    private val activity: MainActivity
) : Closeable, Thread() {
    var stopSending = false
    private var datagramSocket : DatagramSocket? = null

    override fun run() {
        try {
            val addressElements = address.split(":")
            if (addressElements.size != 2) {
                setConnectionStatusText(activity.getString(R.string.invalid_address))
                return
            }
            val host = addressElements[0]
            var port: Int? = null
            try {
                port = addressElements[1].toInt()
            } catch (e: NumberFormatException) {
                setConnectionStatusText(activity.getString(R.string.invalid_port).format(addressElements[1]))
            }
            datagramSocket = DatagramSocket(port!!, InetAddress.getByName("0.0.0.0"))
            datagramSocket!!.connect(InetAddress.getByName(host), port)
            var previousTime: Long = System.currentTimeMillis()

            setConnected(true)

            while (!Thread.currentThread().isInterrupted && !stopSending) {
                synchronized(activity.mOrientationAngles) { // shouldn't matter that it's a different variable
                    val msg = if (!activity.cbTransmitDebug.isChecked) {
                        val azimuth = activity.getCurrentAzimuth(
                            withoutGyro = !activity.cbGyro.isChecked && !activity.cbTransmitDebug.isChecked
                        )
                        "%.5f\n".format(Locale.ROOT, azimuth).toByteArray(Charsets.UTF_8)
                    } else {
                        val azimuth = activity.getCurrentAzimuth(withoutGyro = false)
                        val azWithoutGyro = activity.getCurrentAzimuth(withoutGyro = true)
                        "%.5f,%.5f\n".format(Locale.ROOT, azimuth, azWithoutGyro).toByteArray(Charsets.UTF_8)
                    }
                    val datagramPacket = DatagramPacket(msg, msg.size)

                    try {
                        datagramSocket!!.send(datagramPacket)
                    } catch (e: IOException) {
                        // "if an I/O error occurs."
                        setConnectionStatusText(activity.getString(R.string.send_io_exception).format(e.message))
                        Log.e(COMMUNICATOR_DEBUG_TAG, e.toString())
                        tryReconnect(host, port)
                    } catch (e: SecurityException) {
                        // "if a security manager exists and its checkMulticast or
                        // checkConnect method doesn't allow the send."
                        setConnectionStatusText(activity.getString(R.string.send_security_exception).format(e.message))
                        Log.e(COMMUNICATOR_DEBUG_TAG, e.toString())
                    } catch (e: PortUnreachableException) {
                        // "may be thrown if the socket is connected to a currently unreachable destination.
                        // Note, there is no guarantee that the exception will be thrown."
                        setConnectionStatusText(
                            activity.getString(R.string.send_port_unreachable_exception))
                        Log.e(COMMUNICATOR_DEBUG_TAG, e.toString())
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
        } catch (e: SocketException) {
            // DatagramSocket constructor:
            // "if the socket could not be opened, or the socket could not bind to the specified local port."
            setConnectionStatusText(activity.getString(R.string.socket_exception).format(e.message))
            Log.e(COMMUNICATOR_DEBUG_TAG, e.toString())
        } catch (e: SecurityException) {
            // DatagramSocket constructor:
            // "if a security manager exists and its checkListen method doesn't allow the operation."
            setConnectionStatusText(activity.getString(R.string.security_exception))
            Log.e(COMMUNICATOR_DEBUG_TAG, e.toString())
        } finally {
            datagramSocket?.close()
            setConnected(false)
        }
    }

    /**
     * Apply this communicator's connection status in the parent activity.
     * Also sets this.stopSending to true if connected. Set this to false if you want to stop transmitting.
     */
    private fun setConnected(connected: Boolean) {
        // TODO: this is not very MVC…
        stopSending = !connected
        activity.mCommunicatorStarted = connected
        activity.runOnUiThread {
            if (connected) {
                setConnectionStatusText(activity.getString(R.string.connection_status_sending))
                activity.btConnect.text = activity.getString(R.string.stop_sending)
            } else {
                setConnectionStatusText(activity.getString(R.string.connection_status_not_connected))
                activity.btConnect.text = activity.getString(R.string.connect)
            }
        }
    }

    private fun setConnectionStatusText(msg: String) {
        activity.runOnUiThread {
            activity.tvConnectionStatus.text = msg
        }
    }

    private fun tryReconnect(host: String, port: Int) {
        try {
            datagramSocket!!.disconnect()
            datagramSocket!!.connect(InetAddress.getByName(host), port)
            if (datagramSocket!!.isConnected) {
                setConnectionStatusText(activity.getString(R.string.connection_status_sending))
            }
        } catch (e: SocketException) {
            // DatagramSocket constructor:
            // "if the socket could not be opened, or the socket could not bind to the specified local port."

            // Do not replace the status text here because the error that lead to this reconnect attempt
            // could be more important… TODO: really?
            // setConnectionStatusText(activity.getString(R.string.socket_exception).format(e.message))
            Log.e(COMMUNICATOR_DEBUG_TAG, e.toString())
        }
    }

    override fun close() {
        stopSending = true
        join()
        // should also cause the socket to be closed (see `finally` in `run()`)
    }
}
