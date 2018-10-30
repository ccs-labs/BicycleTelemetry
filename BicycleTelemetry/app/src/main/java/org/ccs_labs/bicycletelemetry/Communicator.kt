package org.ccs_labs.bicycletelemetry

import org.zeromq.ZMQ
import java.io.Closeable

class Communicator(
    address: String,
    private val intervalMillis: Long,
    private val activity: MainActivity
) : Closeable, Thread() {

    private val context: ZMQ.Context = ZMQ.context(1)
    private val sendSocket: ZMQ.Socket = context.socket(ZMQ.PUSH)
    val isConnected = sendSocket.connect(address)

    override fun run() {
        var previousTime : Long = System.currentTimeMillis()
        while (!Thread.currentThread().isInterrupted) {
            sendSocket.send(activity.mOrientationAngles[0].toString())
            Thread.sleep(Math.max(0, intervalMillis - (System.currentTimeMillis() - previousTime)))
            previousTime = System.currentTimeMillis()
        }
    }

    override fun close() {
        this.context.close()
    }
}
