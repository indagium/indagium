package com.indagium.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.indagium.capture.WirelessPairingStage
import com.indagium.capture.newQrPairingCredentials
import com.indagium.capture.qrPairingPayload
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.floor

private const val QR_QUIET_ZONE_MODULES = 4
private const val CONNECTED_LINGER_MS = 700L
private const val PAIRING_CODE_LENGTH = 6

/**
 * QR pairing in the style of Android Studio: the phone scans the code from Developer options >
 * Wireless debugging > Pair device with QR code, and the app pairs and connects once the phone
 * advertises the matching session. The pairing job lives in a [LaunchedEffect] keyed on the
 * credentials, so closing the dialog or asking for a new code cancels it.
 */
@Composable
internal fun QrPairingDialog(
    service: CaptureService,
    onConnected: (String) -> Unit,
    onUseCode: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tc()
    var attempt by remember { mutableStateOf(0) }
    val credentials = remember(attempt) { newQrPairingCredentials() }
    val matrix = remember(credentials) { qrMatrix(qrPairingPayload(credentials.name, credentials.password)) }
    val stage = service.pairingStage
    LaunchedEffect(credentials) {
        val job = service.startQrPairing(credentials)
        try {
            job.join()
        } finally {
            job.cancel()
        }
    }
    DisposableEffect(Unit) { onDispose { service.clearPairingStage() } }
    LaunchedEffect(stage) {
        if (stage is WirelessPairingStage.Connected) {
            onConnected(stage.serial)
            delay(CONNECTED_LINGER_MS)
            onDismiss()
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(360.dp).background(colors.p, RoundedCornerShape(8.dp))
                .border(1.dp, colors.br, RoundedCornerShape(8.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText("Pair over Wi-Fi", color = colors.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            AppText(
                "On the phone: Developer options › Wireless debugging › Pair device with QR code. " +
                    "Then scan this code.",
                color = colors.td,
                fontSize = 11.sp,
                maxLines = 4,
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                QrCodeView(matrix)
            }
            PairingStageLine(stage)
            AppText("Needs Android 11 or newer, and a network that allows device discovery.", color = colors.td, fontSize = 10.sp)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                if (stage is WirelessPairingStage.Failed) {
                    DialogActionButton("New QR code", active = true, onClick = { attempt++ })
                } else {
                    DialogActionButton("Use a code", active = false, onClick = onUseCode)
                }
                DialogActionButton("Close", active = false, onClick = onDismiss)
            }
        }
    }
}

/**
 * Pairing with the 6-digit code from the phone's "Pair device with pairing code" screen. The
 * address field is prefilled from a discovered "Ready to pair" row, and stays editable so it is
 * also the manual fallback when the network blocks mDNS.
 */
@Composable
internal fun CodePairingDialog(
    service: CaptureService,
    initialAddress: String,
    onConnected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = tc()
    var address by remember { mutableStateOf(initialAddress) }
    var code by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val stage = service.pairingStage
    val busy = job?.isActive == true &&
        (stage == null || stage is WirelessPairingStage.Pairing || stage is WirelessPairingStage.Connecting)
    val canPair = !busy && stage !is WirelessPairingStage.Connected && address.isNotBlank() && code.length == PAIRING_CODE_LENGTH
    val submit = {
        if (canPair) job = service.startCodePairing(address.trim(), code)
    }
    val codeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { codeFocus.requestFocus() } }
    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
            service.clearPairingStage()
        }
    }
    LaunchedEffect(stage) {
        if (stage is WirelessPairingStage.Connected) {
            onConnected(stage.serial)
            delay(CONNECTED_LINGER_MS)
            onDismiss()
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(360.dp).background(colors.p, RoundedCornerShape(8.dp))
                .border(1.dp, colors.br, RoundedCornerShape(8.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText("Pair with a code", color = colors.tx, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            AppText(
                "On the phone: Developer options › Wireless debugging › Pair device with pairing code. " +
                    "Keep that screen open.",
                color = colors.td,
                fontSize = 11.sp,
                maxLines = 4,
            )
            AppText("Address", color = colors.td, fontSize = 11.sp)
            InlineField(
                value = address,
                onValue = { address = it },
                placeholder = "192.168.1.23:41234",
                modifier = Modifier.fillMaxWidth(),
                onSubmit = { runCatching { codeFocus.requestFocus() } },
            )
            AppText("Pairing code", color = colors.td, fontSize = 11.sp)
            InlineField(
                value = code,
                onValue = { code = it.filter(Char::isDigit).take(PAIRING_CODE_LENGTH) },
                placeholder = "6 digits",
                modifier = Modifier.fillMaxWidth().focusRequester(codeFocus),
                onSubmit = { submit() },
            )
            PairingStageLine(stage)
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                DialogActionButton("Cancel", active = false, onClick = onDismiss)
                DialogActionButton("Pair", active = true, enabled = canPair, onClick = { submit() })
            }
        }
    }
}

/** One dim status line under the dialog body; failures are in the danger tone. */
@Composable
private fun PairingStageLine(stage: WirelessPairingStage?) {
    val colors = tc()
    when (stage) {
        null -> Unit
        WirelessPairingStage.WaitingForPhone -> AppText("Waiting for the phone to scan…", color = colors.ts, fontSize = 11.sp)
        WirelessPairingStage.Pairing -> AppText("Pairing…", color = colors.ts, fontSize = 11.sp)
        WirelessPairingStage.Connecting -> AppText("Connecting…", color = colors.ts, fontSize = 11.sp)
        is WirelessPairingStage.Connected -> AppText("Connected", color = colors.ok, fontSize = 11.sp)
        is WirelessPairingStage.Failed -> AppText(stage.message, color = DANGER_RED, fontSize = 11.sp, maxLines = 3)
    }
}

/** Always black on white with a quiet zone, even in a dark theme, or phone cameras won't lock on. */
@Composable
private fun QrCodeView(matrix: BitMatrix?) {
    Canvas(Modifier.size(220.dp)) {
        drawRect(Color.White)
        if (matrix == null) return@Canvas
        val modules = matrix.width + 2 * QR_QUIET_ZONE_MODULES
        // Whole-pixel modules, centred: fractional cells anti-alias into hairline gaps between
        // neighbouring dark modules, which some phone cameras read as noise.
        val cell = floor(size.width / modules).coerceAtLeast(1f)
        val origin = (size.width - cell * modules) / 2 + QR_QUIET_ZONE_MODULES * cell
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    drawRect(
                        Color.Black,
                        topLeft = Offset(origin + x * cell, origin + y * cell),
                        size = Size(cell, cell),
                    )
                }
            }
        }
    }
}

/** Zero margin: the quiet zone is drawn by [QrCodeView] so it scales with the canvas. */
private fun qrMatrix(payload: String): BitMatrix? = runCatching {
    QRCodeWriter().encode(
        payload,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
}.getOrNull()
