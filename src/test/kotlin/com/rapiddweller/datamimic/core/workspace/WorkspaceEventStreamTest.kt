// DATAMIMIC for JetBrains IDEs
// Copyright (c) 2026 Rapiddweller Asia Co., Ltd.
// SPDX-License-Identifier: MIT

package com.rapiddweller.datamimic.core.workspace

import com.rapiddweller.datamimic.core.FakeWebSocketServer
import com.rapiddweller.datamimic.core.SessionService
import com.rapiddweller.datamimic.core.StoredSession
import com.rapiddweller.datamimic.core.json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.http.HttpClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Runs [WorkspaceEventStream] against a fake of the platform's events route. */
class WorkspaceEventStreamTest {
    private val server = FakeWebSocketServer()
    private val origin = server.origin
    private val handshakes = server.handshakes
    private val events = CopyOnWriteArrayList<WorkspaceEvent>()
    private val states = LinkedBlockingQueue<StreamState>()
    private var stored: String? = json.encodeToString(StoredSession.serializer(), StoredSession(origin, "session-1"))
    private val stream = stream()

    private fun stream(reconnectDelaysMs: List<Long>? = null) = WorkspaceEventStream(
        HttpClient.newHttpClient(),
        SessionService(HttpClient.newHttpClient(), { stored }, { stored = it }),
        "binding-1",
        "p1",
        onEvent = events::add,
        onState = states::add,
        reconnectDelaysMs = reconnectDelaysMs ?: listOf(250L, 500L),
    )

    @After
    fun tearDown() {
        stream.close()
        server.close()
    }

    @Test
    fun `authenticates with the session cookie and origin, goes live on the snapshot, and stops when superseded`() {
        server.serve { connection ->
            connection.text(SNAPSHOT)
            connection.close(4001)
        }

        stream.start()

        val handshake = handshakes.poll(5, TimeUnit.SECONDS)!!
        assertEquals("GET /api/v2/projects/p1/workspace/events?client_binding_id=binding-1 HTTP/1.1", handshake.getValue("request-line"))
        assertEquals(origin.value, handshake.getValue("origin"))
        assertEquals("datamimic_session=session-1", handshake.getValue("cookie"))
        assertEquals(listOf(StreamState.CONNECTING, StreamState.LIVE, StreamState.SUPERSEDED), awaitStates(StreamState.SUPERSEDED))
        assertTrue(events.single() is WorkspaceEvent.Snapshot)
    }

    @Test
    fun `a handshake the platform refuses stops the stream without retrying`() {
        server.refuseWith = 403
        server.serve()

        stream.start()

        assertEquals(listOf(StreamState.CONNECTING, StreamState.REFUSED), awaitStates(StreamState.REFUSED))
        Thread.sleep(1_000)
        assertEquals("no retry after a refusal", 1, handshakes.size)
    }

    @Test
    fun `a stream closed while it waits to reconnect never connects again`() {
        server.serve { connection -> connection.close(1011) }

        stream.start()
        handshakes.poll(5, TimeUnit.SECONDS)!!
        Thread.sleep(50)
        stream.close()
        Thread.sleep(1_000)

        assertEquals("no reconnect after close", 0, handshakes.size)
        assertEquals(StreamState.CLOSED, generateSequence { states.poll() }.last())
    }

    @Test
    fun `an unreachable platform is retried until it answers, without flickering between states`() {
        server.refuseWith = 503
        server.serve { connection -> connection.text(SNAPSHOT) }
        val quick = stream(reconnectDelaysMs = listOf(10L, 10L))

        try {
            quick.start()
            repeat(5) { handshakes.poll(5, TimeUnit.SECONDS)!! }
            server.refuseWith = null

            assertEquals(listOf(StreamState.CONNECTING, StreamState.UNAVAILABLE, StreamState.LIVE), awaitStates(StreamState.LIVE))
        } finally {
            quick.close()
        }
    }

    @Test
    fun `a late older handshake cannot replace the newer live socket`() {
        server.refuseWith = 503
        server.serve { connection -> connection.text(SNAPSHOT) }
        val quick = stream(reconnectDelaysMs = listOf(10L, 10L))

        try {
            quick.start()
            repeat(3) { handshakes.poll(5, TimeUnit.SECONDS)!! }
            val gate = CountDownLatch(1)
            server.handshakeGate = gate
            handshakes.poll(5, TimeUnit.SECONDS)!!

            server.refuseWith = null
            quick.start()
            handshakes.poll(5, TimeUnit.SECONDS)!!
            awaitStates(StreamState.LIVE)
            events.clear()
            val current = server.connections.poll(5, TimeUnit.SECONDS)!!
            gate.countDown()

            current.text("""{"type":"ping"}""")
            awaitUntil { WorkspaceEvent.Ping in events }
        } finally {
            quick.close()
        }
    }

    @Test
    fun `a handshake that completes after close is aborted without scheduler adoption`() {
        server.serve { connection -> connection.text(SNAPSHOT) }
        val gate = CountDownLatch(1)
        server.handshakeGate = gate
        val quick = stream(reconnectDelaysMs = listOf(10L))

        try {
            quick.start()
            handshakes.poll(5, TimeUnit.SECONDS)!!
            quick.close()
            gate.countDown()
            server.connections.poll(5, TimeUnit.SECONDS)!!
            Thread.sleep(50)

            assertTrue(events.isEmpty())
            assertEquals(StreamState.CLOSED, generateSequence { states.poll() }.last())
        } finally {
            quick.close()
        }
    }

    private fun awaitStates(last: StreamState): List<StreamState> {
        val seen = mutableListOf<StreamState>()
        while (seen.lastOrNull() != last) seen += states.poll(5, TimeUnit.SECONDS) ?: break
        return seen
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }

    private companion object {
        const val SNAPSHOT = """{"type":"snapshot","tree_revision":"r1","transport":{"keepalive_interval_seconds":5,"stale_after_seconds":15},
            "locks":{},"collaboration":{"active_client_count":1,"edit_mode":"automatic"}}"""
    }
}
