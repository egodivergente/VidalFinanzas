package es.vidal.finanzas

import org.junit.Assert.*
import org.junit.Test

class BankConnectionTest {
    private val key = "a".repeat(64)
    private fun tx(id: String = "REF-1", date: String = "2026-10-01", amount: Long = -1234, text: String? = "Compra MERCADONA", valueDate: String? = null) =
        BridgeTransaction(id, date, amount, text, valueDate)
    private fun sync(vararg t: BridgeTransaction, account: BridgeAccount = BridgeAccount(key, "Ibercaja · …1234", "EUR"), complete: Boolean = true) =
        BridgeSync(account, t.toList(), "2026-10-20T10:00:00.123456+00:00", "2026-07-22", "2026-10-20", complete)
    private fun rejected(block: () -> Unit) = assertTrue(runCatching(block).exceptionOrNull() is BridgeException)

    @Test fun contractJsonDecodesWithUnknownFieldsAndFractionalTimestamp() {
        val body = """{"account":{"key":"$key","label":"Ibercaja · …1234","currency":"EUR","extra":1},"transactions":[{"id":"R1","date":"2026-10-20","amount_cents":-1234,"description":"Compra","value_date":null}],"fetched_at":"2026-10-20T08:00:00.5Z","date_from":"2026-07-22","date_to":"2026-10-20","complete":true}"""
        val batch = BankSync.normalize(decodeBridge<BridgeSync>(body.toByteArray()), key, emptyList())
        assertEquals(1, batch.movements.size)
        rejected { decodeBridge<BridgeSync>(body.replace("-1234", "-12.34").toByteArray()) }
        rejected { decodeBridge<BridgeSync>(byteArrayOf(0xC3.toByte(), 0x28)) }
    }

    @Test fun mapperBuildsDeterministicIdsSeparateAccountAndNormalFingerprint() {
        val rules = listOf(Rule(text = "MERCADONA", categoryId = "food"))
        val a = BankSync.normalize(sync(tx()), key, rules)
        val b = BankSync.normalize(sync(tx()), key, rules)
        val m = a.movements.single()
        assertEquals("ob:$key", a.account.id)
        assertEquals("Ibercaja · …1234", a.account.name)
        assertEquals("ob:" + sha256("$key|REF-1".toByteArray()), m.id)
        assertEquals(m.id, b.movements.single().id)
        assertEquals(sha256("ob:$key|2026-10-01|-1234|${normalized("Compra MERCADONA")}".toByteArray()), m.fingerprint)
        assertEquals("REF-1", m.reference); assertNull(m.batchId); assertEquals("food", m.categoryId); assertEquals("normal", m.nature)
    }

    @Test fun resyncInsertsOnlyNewAndNeverRewritesEdits() {
        val first = BankSync.normalize(sync(tx()), key, emptyList())
        val edited = first.movements.single().copy(note = "mía", categoryId = "leisure", nature = "transfer", manualCategory = true)
        val second = BankSync.normalize(sync(tx(), tx(id = "REF-2", amount = 5000, text = "NOMINA")), key, emptyList())
        val fresh = BankSync.plan(second, listOf(edited))
        assertEquals(listOf(BankSync.movementId(key, "REF-2")), fresh.map { it.id })
        assertTrue(BankSync.plan(second, listOf(edited) + fresh).isEmpty())
    }

    @Test fun changedExistingMovementAbortsWholeBatch() {
        val stored = BankSync.normalize(sync(tx()), key, emptyList()).movements
        listOf(tx(amount = -1235), tx(date = "2026-10-02"), tx(text = "Otra cosa")).forEach { changed ->
            val batch = BankSync.normalize(sync(changed, tx(id = "REF-NEW")), key, emptyList())
            rejected { BankSync.plan(batch, stored) }
        }
    }

    @Test fun rejectsWrongAccountCurrencyOrIncompleteResponses() {
        rejected { BankSync.normalize(sync(tx()), "b".repeat(64), emptyList()) }
        rejected { BankSync.normalize(sync(tx(), account = BridgeAccount(key, "x", "USD")), key, emptyList()) }
        rejected { BankSync.normalize(sync(tx(), complete = false), key, emptyList()) }
        rejected { BankSync.normalize(sync(tx(), account = BridgeAccount("A".repeat(64), "x", "EUR")), "A".repeat(64), emptyList()) }
        rejected { BankSync.normalize(sync(tx()).copy(fetchedAt = "ayer"), key, emptyList()) }
    }

    @Test fun rejectsInvalidTransactionsDefensively() {
        listOf(
            tx(date = "2026-02-30"), tx(date = "20/10/2026"), tx(date = "2026-06-01"), tx(valueDate = "2026-13-01"),
            tx(id = ""), tx(id = " REF"), tx(id = "R\u0000"), tx(amount = 100_000_000_001L)
        ).forEach { bad -> rejected { BankSync.normalize(sync(bad), key, emptyList()) } }
        rejected { BankSync.normalize(sync(tx(), tx(amount = 1)), key, emptyList()) }
        assertEquals("Sin concepto", BankSync.normalize(sync(tx(text = " \n ")), key, emptyList()).movements.single().original)
    }

    @Test fun bridgeUrlTokenAndAuthorizationUrlAreStrict() {
        assertEquals("https://puente.example:8443", bridgeBaseUrl(" https://puente.example:8443/ "))
        listOf("http://puente.example", "https://puente.example/fin", "https://user:pw@puente.example", "https://puente.example/?a=1", "https://", "ftp://x", "https://puente.example/a b")
            .forEach { assertTrue(it, runCatching { bridgeBaseUrl(it) }.isFailure) }
        assertEquals("x".repeat(32), bridgeToken("x".repeat(32)))
        assertTrue(runCatching { bridgeToken("x".repeat(31)) }.isFailure)
        assertTrue(runCatching { bridgeToken("x".repeat(257)) }.isFailure)
        assertTrue(runCatching { bridgeToken("x".repeat(20) + " " + "x".repeat(20)) }.isFailure)
        assertTrue(isEnableBankingAuthUrl("https://auth.enablebanking.com/start?x=1"))
        listOf("http://auth.enablebanking.com/", "https://auth.enablebanking.com.evil.example/", "https://evil.example/?auth.enablebanking.com",
            "https://x@auth.enablebanking.com/", "https://auth.enablebanking.com:8443/", "intent://auth.enablebanking.com")
            .forEach { assertFalse(it, isEnableBankingAuthUrl(it)) }
        assertFalse(BridgeConfig("https://x", "s".repeat(40)).toString().contains("s".repeat(40)))
    }

    @Test fun errorMessagesNeverEchoServerText() {
        assertEquals("La autorización bancaria ha caducado. Pulsa «Revocar conexión» y vuelve a conectar.", bridgeErrorMessage(401, "EXPIRED_SESSION"))
        assertEquals("Ya hay una conexión. Revoca la conexión anterior y vuelve a conectar.", bridgeErrorMessage(409, "CONNECTION_EXISTS"))
        assertEquals("Ya hay una conexión. Revoca la conexión anterior y vuelve a conectar.", bridgeErrorMessage(409, null))
        assertEquals("Acceso a esta cuenta no permitido por el puente.", bridgeErrorMessage(403, "ACCOUNT_NOT_ALLOWED"))
        assertEquals("Acceso a esta cuenta no permitido por el puente.", bridgeErrorMessage(403, null))
        assertEquals("Token de emparejamiento no válido o sesión caducada.", bridgeErrorMessage(401, null))
        assertEquals("El puente no tiene configurado el proveedor.", bridgeErrorMessage(503, null))
        assertTrue(bridgeErrorMessage(502, "BANK_SAID_SECRET").startsWith("El proveedor devolvió"))
    }
}
