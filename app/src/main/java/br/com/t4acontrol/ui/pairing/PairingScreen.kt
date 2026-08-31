package br.com.t4acontrol.ui.pairing

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import br.com.t4acontrol.R
import br.com.t4acontrol.backend.T4AState

@Composable
internal fun PairingScreen(current: T4AState, foreground: Color, onPair: () -> Unit, onScan: () -> Unit) {
    val busy = current.pairing == T4AState.Pairing.SCANNING || current.pairing == T4AState.Pairing.PAIRING
    Text(stringResource(R.string.pairing_status, pairingLabel(current.pairing)), color = foreground)
    when {
        current.pairing == T4AState.Pairing.READY -> Button(onClick = onPair, enabled = !busy) { Text(stringResource(R.string.pair_t4a)) }
        !busy -> Button(onClick = onScan) { Text(stringResource(R.string.find_t4a)) }
    }
}

@Composable
internal fun pairingLabel(pairing: T4AState.Pairing): String = stringResource(
    when (pairing) {
        T4AState.Pairing.PAIRED -> R.string.pairing_paired
        T4AState.Pairing.PAIRING -> R.string.pairing_in_progress
        T4AState.Pairing.READY -> R.string.pairing_ready
        T4AState.Pairing.SCANNING -> R.string.pairing_scanning
        T4AState.Pairing.REMOVING -> R.string.pairing_removing
        T4AState.Pairing.NO_HOME -> R.string.pairing_no_home
        T4AState.Pairing.UNPAIRED -> R.string.pairing_unpaired
    },
)
