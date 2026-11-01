/*
 * Copyright (c) 2026 Windscribe Limited.
 */

package com.windscribe.vpn.autoconnection

import org.junit.Assert.assertEquals
import org.junit.Test

class BruteForceSequenceTest {
    @Test
    fun `current protocol is tried first and the rest keep the app order`() {
        val order = BruteForceSequence.protocolOrder("TCP", listOf("IKEv2", "UDP", "TCP", "WireGuard"))

        assertEquals(listOf("TCP", "IKEv2", "UDP", "WireGuard"), order)
    }

    @Test
    fun `an unknown current protocol leaves the app order untouched`() {
        val available = listOf("IKEv2", "UDP", "TCP")

        assertEquals(available, BruteForceSequence.protocolOrder(null, available))
        assertEquals(available, BruteForceSequence.protocolOrder("Stealth", available))
    }

    @Test
    fun `the sweep is protocol major, all locations before the next protocol`() {
        val sweep = BruteForceSequence.sweep(listOf("IKEv2", "UDP"), listOf(10, 20, 30))

        assertEquals(
            listOf(
                "IKEv2" to 10,
                "IKEv2" to 20,
                "IKEv2" to 30,
                "UDP" to 10,
                "UDP" to 20,
                "UDP" to 30,
            ),
            sweep,
        )
    }

    @Test
    fun `nothing to sweep produces no attempts`() {
        assertEquals(emptyList<Pair<String, Int>>(), BruteForceSequence.sweep(emptyList(), listOf(1)))
        assertEquals(emptyList<Pair<String, Int>>(), BruteForceSequence.sweep(listOf("IKEv2"), emptyList()))
    }
}
