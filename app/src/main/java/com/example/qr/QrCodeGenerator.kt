package com.example.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class ParsedShareInvite(
    val code: String,
    val hostIp: String? = null,
    val hostPort: Int? = null,
    val hostPublicKeyB64: String? = null,
    val p2pIp: String? = null,
    val p2pSsid: String? = null,
    val p2pPassphrase: String? = null
)

object QrCodeGenerator {
    fun generateQrBitmap(content: String, sizePx: Int = 512): ImageBitmap? {
        if (content.isBlank()) return null
        return runCatching {
            val hints = mapOf(
                EncodeHintType.MARGIN to 2,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.parseColor("#060D1A") else Color.WHITE)
                }
            }
            bmp.asImageBitmap()
        }.getOrNull()
    }

    fun buildInviteUri(
        code: String,
        hostIp: String,
        port: Int,
        hostPublicKeyB64: String,
        p2pIp: String = "",
        p2pSsid: String = "",
        p2pPassphrase: String = ""
    ): String {
        val encCode = URLEncoder.encode(code, "UTF-8")
        val encHost = URLEncoder.encode(hostIp, "UTF-8")
        val encPub = URLEncoder.encode(hostPublicKeyB64, "UTF-8")
        return buildString {
            append("wifishare://join?code=$encCode&host=$encHost&port=$port&pub=$encPub")
            if (p2pIp.isNotBlank()) {
                append("&p2pip=").append(URLEncoder.encode(p2pIp, "UTF-8"))
            }
            if (p2pSsid.isNotBlank()) {
                append("&ssid=").append(URLEncoder.encode(p2pSsid, "UTF-8"))
            }
            if (p2pPassphrase.isNotBlank()) {
                append("&pass=").append(URLEncoder.encode(p2pPassphrase, "UTF-8"))
            }
        }
    }

    /**
     * Parses either a full wifishare://join?code=...&host=...&port=...&pub=... URI or raw sharing code.
     */
    fun parseShareInvite(rawScanned: String): ParsedShareInvite? {
        val trimmed = rawScanned.trim()
        if (trimmed.startsWith("wifishare://", ignoreCase = true)) {
            val params = parseQueryParams(trimmed)
            val code = params["code"]?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
            val host = params["host"]?.trim()?.takeIf { it.isNotBlank() }
            val port = params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 }
            val pub = params["pub"]?.trim()?.takeIf { it.isNotBlank() }
            val p2pIp = params["p2pip"]?.trim()?.takeIf { it.isNotBlank() }
            val p2pSsid = params["ssid"]?.trim()?.takeIf { it.isNotBlank() }
            val p2pPass = params["pass"]?.trim()?.takeIf { it.isNotBlank() }
            return ParsedShareInvite(
                code = code,
                hostIp = host,
                hostPort = port,
                hostPublicKeyB64 = pub,
                p2pIp = p2pIp,
                p2pSsid = p2pSsid,
                p2pPassphrase = p2pPass
            )
        }
        val code = extractSharingCode(trimmed) ?: return null
        return ParsedShareInvite(code = code)
    }

    fun extractSharingCode(rawScanned: String): String? {
        val trimmed = rawScanned.trim()
        if (trimmed.startsWith("wifishare://", ignoreCase = true)) {
            return parseQueryParams(trimmed)["code"]?.trim()?.uppercase()
        }
        val codePattern = Regex("WFS-[A-Z0-9]{4}-[A-Z0-9]{4}")
        return codePattern.find(trimmed.uppercase())?.value
            ?: trimmed.takeIf { it.length in 6..24 && !it.contains("://") }?.uppercase()
    }

    private fun parseQueryParams(uriString: String): Map<String, String> {
        val query = runCatching { URI(uriString).rawQuery }.getOrNull()
            ?: uriString.substringAfter("?", "")
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@mapNotNull null
            val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            key to value
        }.toMap()
    }
}
