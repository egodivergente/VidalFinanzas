package es.vidal.finanzas

import androidx.room.*
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.UUID

fun uid(): String = UUID.randomUUID().toString()
fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}"), "").uppercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun money(cents: Long): String = java.text.NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-ES")).format(BigDecimal.valueOf(cents, 2))
fun amountCents(raw: String): Long {
    var s = raw.trim().replace("€", "").replace("EUR", "", true).replace("\u00a0", "").replace(" ", "").replace("−", "-")
    if (s.startsWith("(") && s.endsWith(")")) s = "-" + s.substring(1, s.length - 1)
    require(s.isNotEmpty()) { "Importe vacío" }
    if (s.contains(',')) {
        require(Regex("[+-]?(?:\\d+|\\d{1,3}(?:\\.\\d{3})+),\\d{1,2}").matches(s)) { "Importe ambiguo: usa coma decimal" }
        s = s.replace(".", "").replace(',', '.')
    } else {
        require(Regex("[+-]?\\d+(?:\\.\\d{1,2})?").matches(s)) { "Importe ambiguo: revisa separadores" }
    }
    val value = BigDecimal(s).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
    require(value in -100_000_000_000L..100_000_000_000L) { "Importe fuera del límite admitido" }
    return value
}
fun bankDate(raw: String): String {
    val s = raw.trim()
    val formats = listOf("dd/MM/uuuu", "d/M/uuuu", "dd-MM-uuuu", "uuuu-MM-dd")
    for (format in formats) {
        try {
            val date = LocalDate.parse(s, DateTimeFormatter.ofPattern(format).withResolverStyle(ResolverStyle.STRICT))
            require(date.year in 1900..2200)
            return date.toString()
        } catch (_: Exception) { }
    }
    throw IllegalArgumentException("Fecha inválida; usa día/mes/año")
}

@Serializable @Entity(tableName = "accounts")
data class Account(@PrimaryKey val id: String = uid(), val name: String, val currency: String = "EUR")
@Serializable @Entity(tableName = "categories")
data class Category(@PrimaryKey val id: String, val name: String, val kind: String = "expense")
@Serializable @Entity(tableName = "movements", indices = [Index("accountId"), Index("date"), Index("fingerprint"), Index("batchId")])
data class Movement(
    @PrimaryKey val id: String = uid(), val accountId: String, val date: String,
    val valueDate: String? = null, val amount: Long, val original: String,
    val categoryId: String = "uncategorized", val nature: String = "normal",
    val manualCategory: Boolean = false, val reference: String? = null,
    val balance: Long? = null, val fingerprint: String, val batchId: String? = null,
    val sourceRow: Int? = null, val note: String = ""
)
@Serializable @Entity(tableName = "rules")
data class Rule(@PrimaryKey val id: String = uid(), val text: String, val categoryId: String, val priority: Int = 100)
@Serializable @Entity(tableName = "budgets", indices = [Index(value = ["accountId", "categoryId", "month"], unique = true)])
data class Budget(@PrimaryKey val id: String = uid(), val accountId: String, val categoryId: String, val month: String, val limit: Long)
@Serializable @Entity(tableName = "goals")
data class Goal(@PrimaryKey val id: String = uid(), val name: String, val target: Long, val due: String? = null)
@Serializable @Entity(tableName = "contributions")
data class Contribution(@PrimaryKey val id: String = uid(), val goalId: String, val amount: Long, val date: String, val note: String = "")
@Serializable @Entity(tableName = "batches")
data class ImportBatch(@PrimaryKey val id: String = uid(), val accountId: String, val filename: String, val hash: String, val importedAt: String, val accepted: Int, val skipped: Int, val parserVersion: Int = 1)
@Serializable
data class Snapshot(
    val version: Int = 1, val accounts: List<Account> = emptyList(), val categories: List<Category> = emptyList(),
    val movements: List<Movement> = emptyList(), val rules: List<Rule> = emptyList(),
    val budgets: List<Budget> = emptyList(), val goals: List<Goal> = emptyList(),
    val contributions: List<Contribution> = emptyList(), val batches: List<ImportBatch> = emptyList()
)
fun initialCategories() = listOf(
    Category("uncategorized", "Sin categoría", "both"), Category("food", "Alimentación"),
    Category("home", "Vivienda y hogar"), Category("transport", "Transporte"),
    Category("health", "Salud"), Category("subscriptions", "Suscripciones"),
    Category("leisure", "Ocio"), Category("bills", "Suministros"), Category("other", "Otros gastos"),
    Category("salary", "Nómina", "income"), Category("income", "Otros ingresos", "income")
)
fun initialRules() = listOf(Rule(text="MERCADONA",categoryId="food"),Rule(text="NETFLIX",categoryId="subscriptions"),Rule(text="NOMINA",categoryId="salary"))
fun categorize(text: String, rules: List<Rule>): String = rules.sortedByDescending { it.priority }.firstOrNull { normalized(text).contains(normalized(it.text)) }?.categoryId ?: "uncategorized"
fun expenseFor(movements: List<Movement>, category: String): Long = -movements.filter { it.categoryId == category && it.nature != "transfer" && (it.amount < 0 || it.nature == "refund") }.sumOf { it.amount }
fun incomeFor(movements: List<Movement>): Long = movements.filter { it.nature == "normal" && it.amount > 0 }.sumOf { it.amount }
fun expensesFor(movements: List<Movement>): Long = -movements.filter { it.nature != "transfer" && (it.amount < 0 || it.nature == "refund") }.sumOf { it.amount }

fun validateSnapshot(s: Snapshot) {
    require(s.version == 1) { "Versión de copia no compatible" }
    require(s.accounts.isNotEmpty() && s.accounts.size <= 100 && s.movements.size <= 100_000) { "Copia fuera de límites" }
    fun unique(ids: List<String>) { require(ids.all { it.isNotBlank() } && ids.size == ids.toSet().size) { "Identificadores repetidos" } }
    listOf(s.accounts.map { it.id },s.categories.map { it.id },s.movements.map { it.id },s.rules.map { it.id },s.budgets.map { it.id },s.goals.map { it.id },s.contributions.map { it.id },s.batches.map { it.id }).forEach(::unique)
    val accounts = s.accounts.map { it.id }.toSet(); val categories=s.categories.map { it.id }.toSet(); val goals=s.goals.map { it.id }.toSet(); val batches=s.batches.map { it.id }.toSet()
    require("uncategorized" in categories)
    s.accounts.forEach { require(it.name.isNotBlank() && it.currency == "EUR") }
    s.categories.forEach { require(it.name.isNotBlank() && it.kind in setOf("expense","income","both")) }
    s.batches.forEach { require(it.accountId in accounts && it.accepted >= 0 && it.skipped >= 0) }
    s.movements.forEach {
        require(it.accountId in accounts && it.categoryId in categories && (it.batchId == null || it.batchId in batches))
        require(it.nature in setOf("normal","transfer","refund") && (it.nature != "refund" || it.amount > 0))
        require(it.amount in -100_000_000_000L..100_000_000_000L && it.original.isNotBlank() && it.original.length <= 10_000)
        LocalDate.parse(it.date); it.valueDate?.let(LocalDate::parse)
    }
    s.rules.forEach { require(it.text.isNotBlank() && it.categoryId in categories) }
    s.budgets.forEach { require(it.accountId in accounts && it.categoryId in categories && it.limit > 0); YearMonth.parse(it.month) }
    require(s.budgets.map { Triple(it.accountId,it.categoryId,it.month) }.distinct().size == s.budgets.size)
    s.goals.forEach { require(it.target > 0 && it.name.isNotBlank()); it.due?.let(LocalDate::parse) }
    s.contributions.forEach { require(it.goalId in goals && it.amount in -100_000_000_000L..100_000_000_000L); LocalDate.parse(it.date) }
    s.goals.forEach { g -> require(s.contributions.filter { it.goalId == g.id }.sumOf { it.amount } >= 0) }
}
