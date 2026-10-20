package es.vidal.finanzas

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

const val BRIDGE_PROVIDER = "enablebanking"
const val BANK_ID_PREFIX = "ob:"
const val ENABLE_BANKING_AUTH_HOST = "auth.enablebanking.com"
private const val MAX_BRIDGE_RESPONSE = 20 * 1024 * 1024
private const val MAX_BRIDGE_ERROR = 64 * 1024
private const val MAX_BANK_TRANSACTIONS = 50_000
private const val MAX_BANK_ACCOUNTS = 100
private const val MAX_WINDOW_DAYS = 731L
private const val SYNC_READ_TIMEOUT_MS = 210_000
private val ACCOUNT_KEY = Regex("[0-9a-f]{64}")
private val PAIRING_TOKEN = Regex("[\\x21-\\x7E]{32,256}")
private val ERROR_CODE = Regex("[A-Z_]{1,64}")
private val CONTROL = Regex("[\\p{Cntrl}\\u2028\\u2029]")

/** Message is always a fixed, user-safe text: never server bodies, URLs or tokens. */
class BridgeException(message: String) : Exception(message)
private fun fail(message: String): Nothing = throw BridgeException(message)

@Serializable
data class BridgeConfig(val url: String, val token: String) {
    override fun toString() = "BridgeConfig(url=$url)"
}
@Serializable
data class BridgeStatus(
    val provider: String, val configured: Boolean, val connected: Boolean,
    val environment: String, @SerialName("valid_until") val validUntil: String? = null
)
@Serializable
data class BridgeAccount(val key: String, val label: String, val currency: String)
@Serializable
data class BridgeAccounts(val accounts: List<BridgeAccount>, @SerialName("valid_until") val validUntil: String? = null)
@Serializable
data class BridgeConnect(@SerialName("authorization_url") val authorizationUrl: String)
@Serializable
data class BridgeTransaction(
    val id: String, val date: String, @SerialName("amount_cents") val amountCents: Long,
    val description: String? = null, @SerialName("value_date") val valueDate: String? = null
)
@Serializable
data class BridgeSync(
    val account: BridgeAccount, val transactions: List<BridgeTransaction>,
    @SerialName("fetched_at") val fetchedAt: String, @SerialName("date_from") val dateFrom: String,
    @SerialName("date_to") val dateTo: String, val complete: Boolean
)
@Serializable
private data class BridgeError(val error: String? = null)

val bridgeJson = Json { ignoreUnknownKeys = true }

inline fun <reified T> decodeBridge(bytes: ByteArray): T = try {
    bridgeJson.decodeFromString<T>(strictUtf8(bytes))
} catch (_: Exception) {
    throw BridgeException("Respuesta del puente no válida")
}

fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes)).toString()

fun bridgeBaseUrl(raw: String): String {
    val s = raw.trim()
    require(s.length in 1..2048 && s.none { it.isWhitespace() || it.isISOControl() }) { "URL del puente no válida" }
    val uri = runCatching { URI(s) }.getOrNull() ?: throw IllegalArgumentException("URL del puente no válida")
    require(!uri.isOpaque && uri.scheme.equals("https", ignoreCase = true)) { "El puente debe usar HTTPS" }
    require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "URL del puente no válida: solo https://servidor[:puerto]" }
    require(uri.port == -1 || uri.port in 1..65535) { "Puerto del puente no válido" }
    require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "Indica solo el origen del puente: https://servidor[:puerto]" }
    return "https://${uri.rawAuthority}"
}

fun bridgeToken(raw: String): String {
    val token = raw.trim()
    require(PAIRING_TOKEN.matches(token)) { "El token de emparejamiento debe tener entre 32 y 256 caracteres, sin espacios" }
    return token
}

fun isEnableBankingAuthUrl(raw: String): Boolean {
    if (raw.length > 8192 || raw.any { it.isWhitespace() || it.isISOControl() }) return false
    val uri = runCatching { URI(raw) }.getOrNull() ?: return false
    return !uri.isOpaque && uri.scheme == "https" && uri.host == ENABLE_BANKING_AUTH_HOST &&
        uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443)
}

fun cleanBankText(raw: String?): String = CONTROL.replace(raw.orEmpty(), " ").replace(Regex("\\s+"), " ").trim()

private fun strictDate(raw: String?, what: String): LocalDate {
    val date = runCatching { LocalDate.parse(raw, DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)) }.getOrNull()
        ?: fail("Fecha no válida en $what. No se ha importado nada.")
    if (date.year !in 1900..2200) fail("Fecha fuera de rango en $what. No se ha importado nada.")
    return date
}

fun validatedBridgeAccount(a: BridgeAccount): BridgeAccount {
    if (!ACCOUNT_KEY.matches(a.key)) fail("Identificador de cuenta no válido")
    val label = cleanBankText(a.label).take(120).ifBlank { "Ibercaja" }
    return a.copy(label = label)
}

fun validatedBridgeAccounts(r: BridgeAccounts): BridgeAccounts {
    if (r.accounts.size > MAX_BANK_ACCOUNTS) fail("Demasiadas cuentas en la respuesta")
    val accounts = r.accounts.map(::validatedBridgeAccount)
    if (accounts.map { it.key }.toSet().size != accounts.size) fail("Cuentas repetidas en la respuesta")
    return r.copy(accounts = accounts)
}

data class BankBatch(val account: Account, val movements: List<Movement>, val dateFrom: String, val dateTo: String)

object BankSync {
    fun accountId(key: String) = BANK_ID_PREFIX + key
    fun movementId(key: String, remoteId: String) = BANK_ID_PREFIX + sha256("$key|$remoteId".toByteArray())

    fun normalize(r: BridgeSync, expectedKey: String, rules: List<Rule>): BankBatch {
        if (!ACCOUNT_KEY.matches(expectedKey)) fail("Identificador de cuenta no válido")
        val account = validatedBridgeAccount(r.account)
        if (account.key != expectedKey) fail("La respuesta no corresponde a la cuenta solicitada")
        if (account.currency != "EUR") fail("Solo se admiten cuentas en EUR")
        if (!r.complete) fail("Respuesta incompleta del puente. No se ha importado nada.")
        (runCatching { OffsetDateTime.parse(r.fetchedAt) }.getOrNull() ?: runCatching { Instant.parse(r.fetchedAt) }.getOrNull()) ?: fail("Respuesta del puente no válida")
        val from = strictDate(r.dateFrom, "la ventana"); val to = strictDate(r.dateTo, "la ventana")
        if (from > to || ChronoUnit.DAYS.between(from, to) > MAX_WINDOW_DAYS) fail("Ventana de fechas no válida")
        if (r.transactions.size > MAX_BANK_TRANSACTIONS) fail("Demasiados movimientos en una sola sincronización")
        if (r.transactions.map { it.id }.toSet().size != r.transactions.size) fail("Referencias bancarias repetidas. No se ha importado nada.")
        val accountId = accountId(account.key)
        val movements = r.transactions.map { t ->
            if (t.id.isBlank() || t.id != t.id.trim() || t.id.length > 512 || CONTROL.containsMatchIn(t.id)) fail("Referencia bancaria no válida. No se ha importado nada.")
            val date = strictDate(t.date, "un movimiento")
            if (date < from || date > to) fail("Movimiento fuera de la ventana solicitada. No se ha importado nada.")
            val valueDate = t.valueDate?.let { strictDate(it, "una fecha valor").toString() }
            if (t.amountCents !in -100_000_000_000L..100_000_000_000L) fail("Importe fuera del límite admitido. No se ha importado nada.")
            val text = cleanBankText(t.description).ifBlank { "Sin concepto" }
            if (text.length > 10_000) fail("Concepto demasiado largo. No se ha importado nada.")
            Movement(
                id = movementId(account.key, t.id), accountId = accountId, date = date.toString(), valueDate = valueDate,
                amount = t.amountCents, original = text, categoryId = categorize(text, rules), reference = t.id,
                fingerprint = sha256("$accountId|$date|${t.amountCents}|${normalized(text)}".toByteArray()), batchId = null
            )
        }
        return BankBatch(Account(id = accountId, name = account.label, currency = "EUR"), movements, from.toString(), to.toString())
    }

    /** Returns only movements not yet stored; existing ones (and their edits) are never rewritten. */
    fun plan(batch: BankBatch, existing: List<Movement>): List<Movement> {
        val byId = existing.associateBy { it.id }
        val changed = batch.movements.count { m -> byId[m.id]?.let { it.accountId != m.accountId || it.date != m.date || it.amount != m.amount || it.original != m.original } ?: false }
        if (changed > 0) fail("$changed movimientos ya importados aparecen cambiados en el banco (importe, fecha o concepto). No se ha guardado nada para no pisar tus ediciones.")
        return batch.movements.filter { it.id !in byId }
    }
}

fun bridgeErrorMessage(status: Int, code: String?): String = when {
    code == "EXPIRED_SESSION" -> "La autorización bancaria ha caducado. Pulsa «Revocar conexión» y vuelve a conectar."
    code == "CONNECTION_EXISTS" || status == 409 -> "Ya hay una conexión. Revoca la conexión anterior y vuelve a conectar."
    code == "ACCOUNT_NOT_ALLOWED" || status == 403 -> "Acceso a esta cuenta no permitido por el puente."
    code == "PROVIDER_NOT_CONFIGURED" || status == 503 -> "El puente no tiene configurado el proveedor."
    status == 401 -> "Token de emparejamiento no válido o sesión caducada."
    status == 404 -> "El puente no reconoce esta operación o cuenta."
    status == 422 || status == 502 -> "El proveedor devolvió una respuesta no utilizable. No se ha importado nada."
    status == 429 -> "Demasiadas solicitudes. Inténtalo más tarde."
    else -> "El puente respondió con un error ($status)."
}

private fun InputStream.bridgeBytes(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer); if (n < 0) break
        if (out.size() + n > limit) fail("Respuesta del puente demasiado grande")
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

class BridgeClient(private val config: BridgeConfig) {
    fun status(): BridgeStatus = decodeBridge<BridgeStatus>(call("GET", "/v1/status", null)).also {
        if (it.provider != BRIDGE_PROVIDER) fail("El puente no usa Enable Banking")
    }
    fun connect(): String = decodeBridge<BridgeConnect>(call("POST", "/v1/connect", "{}")).authorizationUrl.also {
        if (!isEnableBankingAuthUrl(it)) fail("El puente devolvió una dirección de autorización no permitida")
    }
    fun accounts(): BridgeAccounts = validatedBridgeAccounts(decodeBridge(call("GET", "/v1/accounts", null)))
    fun sync(key: String): BridgeSync {
        if (!ACCOUNT_KEY.matches(key)) fail("Identificador de cuenta no válido")
        return decodeBridge(call("POST", "/v1/accounts/$key/sync", "{}", SYNC_READ_TIMEOUT_MS))
    }
    fun revoke() { call("DELETE", "/v1/connection", null) }

    private fun call(method: String, path: String, body: String?, readTimeoutMs: Int = 60_000): ByteArray {
        val connection = runCatching { URL(config.url + path).openConnection() }.getOrNull() as? HttpsURLConnection
            ?: fail("El puente debe usar HTTPS")
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 15_000
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer ${config.token}")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status in 300..399) fail("El puente respondió con una redirección y se ha bloqueado")
            if (status !in 200..299) {
                val code = runCatching { connection.errorStream?.use { it.bridgeBytes(MAX_BRIDGE_ERROR) } }.getOrNull()
                    ?.let { runCatching { decodeBridge<BridgeError>(it).error }.getOrNull() }?.takeIf { ERROR_CODE.matches(it) }
                fail(bridgeErrorMessage(status, code))
            }
            if (connection.contentLengthLong > MAX_BRIDGE_RESPONSE) fail("Respuesta del puente demasiado grande")
            return connection.inputStream.use { it.bridgeBytes(MAX_BRIDGE_RESPONSE) }
        } catch (e: BridgeException) {
            throw e
        } catch (_: SocketTimeoutException) {
            fail("El puente no respondió a tiempo")
        } catch (_: SSLException) {
            fail("No se pudo establecer HTTPS seguro con el puente")
        } catch (_: Exception) {
            fail("No se pudo contactar con el puente")
        } finally {
            connection.disconnect()
        }
    }
}

/** Pairing config lives apart from the vault: its own Keystore key and a no-backup AtomicFile. */
object BridgeStore {
    private const val ALIAS = "vidal.finanzas.bridge.v1"
    private val aad = "VFBRIDGE1".toByteArray(Charsets.US_ASCII)
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "bridge.cfg"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
        }
        return store.getKey(ALIAS, null) as SecretKey
    }
    fun load(context: Context): BridgeConfig? {
        val file = file(context)
        if (!file.baseFile.exists()) return null
        val data = file.readFully()
        if (data.size < 29) fail("Configuración del puente dañada. Vuelve a guardarla.")
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12))); updateAAD(aad); doFinal(data.copyOfRange(12, data.size))
            }
        } catch (_: Exception) { fail("Configuración del puente no legible. Vuelve a guardarla.") }
        try {
            val config = runCatching { bridgeJson.decodeFromString<BridgeConfig>(plain.toString(Charsets.UTF_8)) }.getOrNull()
                ?: fail("Configuración del puente dañada. Vuelve a guardarla.")
            return BridgeConfig(bridgeBaseUrl(config.url), bridgeToken(config.token))
        } finally { plain.fill(0) }
    }
    fun save(context: Context, config: BridgeConfig) {
        val plain = bridgeJson.encodeToString(BridgeConfig(bridgeBaseUrl(config.url), bridgeToken(config.token))).toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(aad) }
            val encrypted = cipher.iv + cipher.doFinal(plain)
            val file = file(context); val out = file.startWrite()
            try { out.write(encrypted); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
        } finally { plain.fill(0) }
    }
    fun clear(context: Context) = file(context).delete()
}
