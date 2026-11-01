/*
 * Copyright (c) 2026 Windscribe Limited.
 */

package com.windscribe.vpn.autoconnection

/**
 * Ordering for the brute-force sweep: every location on one protocol before the next protocol, with
 * the protocol already selected tried first. Pure, so the walk is testable without a device.
 */
object BruteForceSequence {
    /**
     * @param current the protocol the user is on, tried first.
     * @param available every protocol the app supports, in the order the app lists them.
     */
    fun protocolOrder(
        current: String?,
        available: List<String>,
    ): List<String> {
        val rest = available.filter { it != current }
        return if (current != null && current in available) listOf(current) + rest else available
    }

    /** The full sweep as (protocol, location) pairs, protocol-major. */
    fun sweep(
        protocols: List<String>,
        locationIds: List<Int>,
    ): List<Pair<String, Int>> = protocols.flatMap { protocol -> locationIds.map { protocol to it } }
}
