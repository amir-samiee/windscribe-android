package com.windscribe.mobile.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.windscribe.mobile.R
import com.windscribe.mobile.ui.connection.ConnectionViewmodel
import com.windscribe.mobile.ui.theme.AppColors
import com.windscribe.mobile.ui.theme.font12
import com.windscribe.vpn.autoconnection.BruteForceState

/**
 * Secondary button next to the connect button. Tapping it walks every location on the current
 * protocol, then every location on the next, until one connects; tapping it again stops the walk.
 * A sweep in progress shows its position in the grid.
 */
@Composable
fun BruteForceConnectButton(connectionViewmodel: ConnectionViewmodel) {
    val state by connectionViewmodel.bruteForceState.collectAsState()
    BruteForceConnectButtonContent(state = state, onClick = { connectionViewmodel.onBruteForceClick() })
}

@Composable
fun BruteForceConnectButtonContent(
    state: BruteForceState,
    onClick: () -> Unit,
) {
    val sweeping = state is BruteForceState.Sweeping
    val connected = state is BruteForceState.Connected
    val tint =
        when {
            connected -> AppColors.mintGreen
            sweeping -> AppColors.cyberBlue
            else -> AppColors.white
        }
    val label =
        when (state) {
            is BruteForceState.Sweeping ->
                if (state.total > 0) {
                    "${state.index}/${state.total}"
                } else {
                    stringResource(com.windscribe.vpn.R.string.brute_force_connect)
                }

            else -> stringResource(com.windscribe.vpn.R.string.brute_force_connect)
        }
    val description =
        stringResource(
            if (sweeping) {
                com.windscribe.vpn.R.string.accessibility_brute_force_cancel
            } else {
                com.windscribe.vpn.R.string.accessibility_brute_force
            },
        )
    val status =
        stringResource(
            if (sweeping) {
                com.windscribe.vpn.R.string.accessibility_status_brute_force_trying
            } else {
                com.windscribe.vpn.R.string.accessibility_status_disconnected
            },
        )
    val rotation by rememberInfiniteTransition(label = "bruteForceRotation").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "bruteForceRingRotation",
    )
    Row(
        modifier =
            Modifier
                .testTag("brute_force_button")
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    stateDescription = status
                }.background(color = AppColors.white.copy(alpha = 0.12f), shape = RoundedCornerShape(50))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = description,
                    role = Role.Button,
                ) {
                    onClick()
                }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_auto_detect_icon),
            contentDescription = null,
            modifier =
                Modifier
                    .size(18.dp)
                    .graphicsLayer { if (sweeping) rotationZ = rotation },
            colorFilter = ColorFilter.tint(tint),
        )
        Text(
            text = label,
            style = font12.copy(fontWeight = FontWeight.Bold),
            color = tint,
        )
    }
}

@Preview
@Composable
private fun BruteForceConnectButtonPreview() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(12.dp)) {
        BruteForceConnectButtonContent(state = BruteForceState.Idle, onClick = {})
        BruteForceConnectButtonContent(
            state = BruteForceState.Sweeping("WireGuard", "New York", 7, 42),
            onClick = {},
        )
        BruteForceConnectButtonContent(state = BruteForceState.Connected("WireGuard", "New York"), onClick = {})
    }
}
