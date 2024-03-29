package net.notjustanna.utils.ipc.server.handler

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import net.notjustanna.utils.io.DataPipe
import net.notjustanna.utils.ipc.proto.ConnectionState.*
import net.notjustanna.utils.ipc.proto.DefaultProtocol
import net.notjustanna.utils.ipc.proto.Protocol
import net.notjustanna.utils.ipc.server.connector.ServerConnection

class ConnectionHandler<T>(
    val serverName: String,
    private val connection: ServerConnection<T>,
    private val calls: Map<String, T.(DataPipe) -> Unit>,
    private val extensions: Map<Byte, T.(DataPipe) -> Unit> = emptyMap(),
    private val proto: Protocol = DefaultProtocol
) : () -> Unit, Protocol by proto {
    override fun invoke() {
        var state = PRE_HANDSHAKE
        val (thisObj, io) = connection
        try {
            io.use {
                state = HANDSHAKE
                (io).writeInt(handshake)
                    .writeString(serverName)
                    .writeShort(ackMe)

                state = ACK_ME
                if (!io.readBoolean()) {
                    state = ENDED
                    io.write(exitNotAckMe)
                    return@use
                }

                state = ACKED_ME
                io.write(ackedMe)

                while (true) {
                    state = IDLE
                    val op = io.readByte()
                    when (op.toInt()) {
                        opExit -> {
                            state = ENDED
                            return@use
                        }

                        opCheck -> {
                            state = ON_INTERNAL_CALL
                            (io).writeInt(handshake)
                                .writeString(serverName)
                                .writeShort(ackMe)
                        }

                        opList -> {
                            state = ON_INTERNAL_CALL
                            io.write(opReqAck).writeSizedStringArray(calls.keys.toTypedArray())
                        }

                        opListExt -> {
                            state = ON_INTERNAL_CALL
                            io.write(opReqAck).writeSizedByteArray(extensions.keys.toByteArray())
                        }

                        opCall -> {
                            state = ON_CALL
                            val call = calls[io.write(opReqAckParams).readString()]

                            if (call == null) {
                                io.write(opReqInvalidParams)
                            } else {
                                thisObj.call(io.write(opReqAck))
                            }
                        }

                        else -> {
                            state = ON_EXTENSION_CALL
                            val extension = extensions[op]

                            if (extension == null) {
                                io.write(opReqInvalid)
                            } else {
                                io.write(opReqAckExt)
                                thisObj.extension(io)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Handler of $thisObj of server $serverName caught an exception on state $state:", e)
        }
    }

    companion object {
        val logger: Logger = LoggerFactory.getLogger(ConnectionHandler::class.java)
    }
}