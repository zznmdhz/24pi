package com.twentyfourpi.lifelog.collector

/**
 * Reserves a notification key/signature while its database write is in flight.
 * A signature becomes a duplicate only after [commit] succeeds; [rollback] makes it retryable.
 */
internal class NotificationWriteGate {
    private val lock = Any()
    private val persisted = mutableMapOf<String, PersistedSignature>()
    private val inFlight = mutableMapOf<SignatureKey, Reservation>()
    private var nextToken = 0L

    fun reserve(key: String, signature: String): Reservation? = synchronized(lock) {
        val signatureKey = SignatureKey(key, signature)
        if (persisted[key]?.signature == signature || signatureKey in inFlight) return null
        Reservation(key, signature, ++nextToken).also { inFlight[signatureKey] = it }
    }

    fun commit(reservation: Reservation, persistedAt: Long) = synchronized(lock) {
        val signatureKey = SignatureKey(reservation.key, reservation.signature)
        if (inFlight[signatureKey] != reservation) return@synchronized
        inFlight.remove(signatureKey)
        persisted[reservation.key] = PersistedSignature(reservation.signature, persistedAt)
    }

    fun recordPersisted(key: String, signature: String, persistedAt: Long) = synchronized(lock) {
        persisted[key] = PersistedSignature(signature, persistedAt)
    }

    fun rollback(reservation: Reservation) = synchronized(lock) {
        inFlight.remove(SignatureKey(reservation.key, reservation.signature), reservation)
    }

    fun isReservedOrPersisted(key: String, signature: String): Boolean = synchronized(lock) {
        persisted[key]?.signature == signature || SignatureKey(key, signature) in inFlight
    }

    fun trim(maxSize: Int, cutoff: Long) = synchronized(lock) {
        if (persisted.size <= maxSize) return@synchronized
        persisted.entries.removeIf { it.value.persistedAt < cutoff }
    }

    internal data class Reservation(
        val key: String,
        val signature: String,
        private val token: Long,
    )

    private data class PersistedSignature(val signature: String, val persistedAt: Long)
    private data class SignatureKey(val key: String, val signature: String)
}
