/*
 * Copyright (c) 2026 Windscribe Limited.
 */

package com.windscribe.vpn.autoconnection

import com.windscribe.vpn.apppreference.PreferencesHelper
import com.windscribe.vpn.backend.VPNState
import com.windscribe.vpn.backend.utils.ConnectSource
import com.windscribe.vpn.backend.utils.WindVpnController
import com.windscribe.vpn.commonutils.WindUtilities
import com.windscribe.vpn.localdatabase.LocalDbInterface
import com.windscribe.vpn.repository.LocationRepository
import com.windscribe.vpn.repository.UserRepository
import com.windscribe.vpn.serverlist.entity.Datacenter
import com.windscribe.vpn.serverlist.entity.DatacenterStatus
import com.windscribe.vpn.serverlist.entity.DatacenterStatusHelper
import com.windscribe.vpn.state.ConnectionPolicy
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/** Errors that mean the user ended the session; a sweep must not reconnect over them. */
private val USER_ENDED_ERRORS =
    setOf(
        VPNState.ErrorType.UserDisconnect,
        VPNState.ErrorType.UserTurnOff,
        VPNState.ErrorType.UserPause,
    )

/** Progress of a brute-force sweep, for the UI. */
sealed class BruteForceState {
    object Idle : BruteForceState()

    data class Sweeping(
        val protocol: String,
        val locationLabel: String,
        val index: Int,
        val total: Int,
    ) : BruteForceState()

    data class Connected(
        val protocol: String,
        val locationLabel: String,
    ) : BruteForceState()

    object Failed : BruteForceState()
}

/**
 * Tries every available location on the current protocol, then every location on the next, until one
 * connects or the whole grid has been tried.
 *
 * This is the user-initiated alternative to [AutoConnectionManager]'s protocol-only walk: the sweep
 * is protocol-major (all locations first, so a working location is found on the protocol the user is
 * already on), and it runs in the foreground with its progress published on [state].
 *
 * A sweep the user cancelled, or one that exhausted the grid, ends the session through the policy so
 * the TUN held between attempts is released or kept blocking as [ConnectionPolicy] decides.
 */
@Singleton
class BruteForceConnectManager
    @Inject
    constructor(
        private val scope: CoroutineScope,
        private val autoConnectionManager: AutoConnectionManager,
        private val vpnController: Lazy<WindVpnController>,
        private val localDbInterface: LocalDbInterface,
        private val locationRepository: LocationRepository,
        private val preferencesHelper: PreferencesHelper,
        private val userRepository: Lazy<UserRepository>,
    ) {
        private val logger = LoggerFactory.getLogger("vpn")

        private val _state = MutableStateFlow<BruteForceState>(BruteForceState.Idle)
        val state: StateFlow<BruteForceState> = _state

        private var job: Job? = null

        /** True while a sweep is running. */
        val isSweeping: Boolean
            get() = job?.isActive == true

        /**
         * Starts a sweep, or ignores the call while one is already running. Only the built-in server
         * list is swept: a custom config or static IP authenticates against its own server, so there
         * is no grid of locations to walk.
         */
        fun start() {
            if (isSweeping) return
            if (preferencesHelper.isConnectingToConfigured || preferencesHelper.isConnectingToStaticIp) {
                logger.info("Brute force is only available for the built-in server list.")
                _state.value = BruteForceState.Failed
                return
            }
            _state.value = BruteForceState.Sweeping("", "", 0, 0)
            job =
                scope.launch {
                    try {
                        runSweep()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("Brute force failed unexpectedly.", e)
                        finishFailed()
                    }
                }
        }

        /** Stops a running sweep and ends the session the user no longer wants. */
        fun cancel() {
            val running = isSweeping
            job?.cancel()
            job = null
            if (running) {
                logger.info("Brute force cancelled.")
                autoConnectionManager.stop()
                vpnController.get().endSession(ConnectionPolicy.SessionEnd.UserDisconnect)
            }
            _state.value = BruteForceState.Idle
        }

        fun reset() {
            _state.value = BruteForceState.Idle
        }

        private suspend fun runSweep() {
            val protocols = orderedProtocols()
            val locations = availableLocations()
            if (protocols.isEmpty() || locations.isEmpty()) {
                logger.info("Brute force has nothing to try (protocols=${protocols.size}, locations=${locations.size}).")
                finishFailed()
                return
            }
            val plan =
                BruteForceSequence.sweep(
                    protocols.map { it.protocol },
                    locations.map { it.id },
                )
            logger.info("Brute force starting: ${protocols.size} protocols x ${locations.size} locations = ${plan.size} attempts.")

            var index = 0
            for ((protocol, locationId) in plan) {
                if (!isSweeping) return
                if (!WindUtilities.isOnline()) {
                    logger.info("Brute force stopping: the device lost its network.")
                    finishFailed()
                    return
                }
                val protocolInformation = protocols.firstOrNull { it.protocol == protocol } ?: continue
                val datacenter = locations.firstOrNull { it.id == locationId } ?: continue
                val label = locationLabel(datacenter)
                _state.value = BruteForceState.Sweeping(protocol, label, index + 1, plan.size)
                // Moves the profile build, and with it the location the screen shows, onto this city.
                locationRepository.setSelectedCity(locationId)
                logger.info("Brute force attempt ${index + 1}/${plan.size}: $protocol $label")
                val result = autoConnectionManager.connectOnce(ConnectSource.App, protocolInformation)
                if (result.status == VPNState.Status.Connected) {
                    logger.info("Brute force connected: $protocol $label")
                    _state.value = BruteForceState.Connected(protocol, label)
                    return
                }
                // The user ended the session from the main button mid-sweep.
                if (result.error?.error in USER_ENDED_ERRORS) {
                    logger.info("Brute force stopping: the user ended the session (${result.error?.error}).")
                    autoConnectionManager.stop()
                    _state.value = BruteForceState.Idle
                    return
                }
                // Consent revoked or an input is required: no other location will fare better.
                if (result.status == VPNState.Status.RequiresUserInput ||
                    result.error?.error == VPNState.ErrorType.Revoked
                ) {
                    logger.info("Brute force stopping early: ${result.status} (${result.error?.error}).")
                    finishFailed()
                    return
                }
                index++
            }

            logger.info("Brute force exhausted every protocol and location.")
            finishFailed()
        }

        private fun finishFailed() {
            autoConnectionManager.stop()
            vpnController.get().endSession(ConnectionPolicy.SessionEnd.ConnectFailed)
            _state.value = BruteForceState.Failed
        }

        /** The protocol the user is on first, then the app's order for the rest. */
        private fun orderedProtocols(): List<ProtocolInformation> {
            val infos = autoConnectionManager.listOfProtocols.toList()
            val byName = infos.associateBy { it.protocol }
            return BruteForceSequence
                .protocolOrder(preferencesHelper.selectedProtocol, infos.map { it.protocol })
                .mapNotNull { byName[it] }
        }

        /** Locations a connection can actually be attempted on, the currently selected one first. */
        private suspend fun availableLocations(): List<Datacenter> {
            val user = userRepository.get().user.value
            val isPro = user?.isPro ?: false
            val selectedCity = preferencesHelper.selectedCity
            val available = mutableListOf<Datacenter>()
            for (datacenter in localDbInterface.getDatacentersAsync()) {
                val serverCount = localDbInterface.getServersByDatacenter(datacenter.id).size
                val hasAlcAccess = user?.hasAlcAccess(localDbInterface.getCountryCode(datacenter.id)) ?: false
                val status = DatacenterStatusHelper.getStatus(datacenter, serverCount, isPro, hasAlcAccess)
                if (status == DatacenterStatus.Available) {
                    available.add(datacenter)
                }
            }
            // Stable sort: the selected city leads, everything else keeps the server list's order.
            return available.sortedBy { if (it.id == selectedCity) 0 else 1 }
        }

        private fun locationLabel(datacenter: Datacenter): String {
            val name = datacenter.nodeName.orEmpty()
            val nick = datacenter.nickName.orEmpty()
            return if (nick.isEmpty()) name else "$name $nick"
        }
    }
