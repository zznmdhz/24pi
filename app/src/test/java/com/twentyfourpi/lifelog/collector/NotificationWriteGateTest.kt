package com.twentyfourpi.lifelog.collector

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationWriteGateTest {
    @Test
    fun `same key and signature has only one concurrent owner`() {
        val gate = NotificationWriteGate()
        val ready = CountDownLatch(16)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(16)

        try {
            val attempts = (1..16).map {
                pool.submit<NotificationWriteGate.Reservation?> {
                    ready.countDown()
                    start.await()
                    gate.reserve("key", "generation|content")
                }
            }
            ready.await(5, TimeUnit.SECONDS)
            start.countDown()

            assertEquals(1, attempts.map { it.get(5, TimeUnit.SECONDS) }.count { it != null })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `failed persistence releases reservation for retry`() {
        val gate = NotificationWriteGate()
        val first = gate.reserve("key", "signature")

        assertNotNull(first)
        assertNull(gate.reserve("key", "signature"))
        gate.rollback(first!!)
        assertNotNull(gate.reserve("key", "signature"))
    }

    @Test
    fun `successful persistence commits duplicate state`() {
        val gate = NotificationWriteGate()
        val reservation = gate.reserve("key", "signature")!!

        gate.commit(reservation, persistedAt = 100L)

        assertNull(gate.reserve("key", "signature"))
        assertNotNull(gate.reserve("key", "new-signature"))
    }

    @Test
    fun `different signature is not treated as a duplicate`() {
        val gate = NotificationWriteGate()
        gate.recordPersisted("key", "old-signature", persistedAt = 100L)

        assertNotNull(gate.reserve("key", "new-signature"))
    }

    @Test
    fun `stale completion cannot overwrite a retried reservation`() {
        val gate = NotificationWriteGate()
        val first = gate.reserve("key", "signature")!!
        gate.rollback(first)
        val retry = gate.reserve("key", "signature")!!

        gate.commit(first, persistedAt = 100L)
        gate.rollback(retry)

        assertNotNull(gate.reserve("key", "signature"))
    }
}
