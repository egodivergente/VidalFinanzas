package es.vidal.finanzas

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.util.zip.ZipInputStream

const val MAX_FILE = 20 * 1024 * 1024
const val MAX_ROWS = 50_000
fun InputStream.limitedBytes(limit: Int = MAX_FILE): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
    while (true) { val n=read(buffer); if(n<0) break; require(out.size()+n<=limit){"Archivo demasiado grande"}; out.write(buffer,0,n) }
    return out.toByteArray()
}
data class ImportTable(val name: String, val hash: String, val rows: List<List<String>>, val headerRow: Int, val mapping: ColumnMap)
data class ColumnMap(val date: Int = -1, val concept: Int = -1, val amount: Int = -1, val debit: Int = -1, val credit: Int = -1, val valueDate: Int = -1, val reference: Int = -1, val balance: Int = -1, val description: Int = -1)
data class PreviewRow(val row: Int, val movement: Movement?, val error: String? = null, val duplicate: Boolean = false, val selected: Boolean = !duplicate && error == null)
data class ImportPreview(val table: ImportTable, val rows: List<PreviewRow>, val accountId: String)
object BankImporter {
    fun read(name: String, bytes: ByteArray): ImportTable {
        require(bytes.size <= MAX_FILE) { "Máximo 20 MB" }
        val prefix = bytes.take(1024).toByteArray().toString(Charsets.ISO_8859_1)
        require(!name.endsWith(".pdf", ignoreCase = true) && !prefix.contains("%PDF-")) {
            "PDF no compatible. Descarga los movimientos de Ibercaja en CSV o Excel (XLS/XLSX). Cambiar la extensión no convierte el archivo."
        }
        require(bytes.none { it == 0.toByte() } ||
            (bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) ||
            (bytes.size > 8 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte())) {
            "Formato no compatible. Selecciona un extracto CSV, XLS o XLSX."
        }
        val rows = when {
            bytes.size>4 && bytes[0]==0x50.toByte() && bytes[1]==0x4b.toByte() -> xlsx(bytes)
            bytes.size>8 && bytes[0]==0xD0.toByte() && bytes[1]==0xCF.toByte() -> xls(bytes)
            else -> csv(decode(bytes))
        }
        require(rows.isNotEmpty()) { "Archivo vacío" }
        require(rows.size <= MAX_ROWS) { "Máximo 50.000 filas por extracto" }
        val header = rows.take(40).indexOfFirst { row -> val m=detect(row); m.date>=0 && m.concept>=0 && (m.amount>=0 || m.debit>=0 || m.credit>=0) }.coerceAtLeast(0)
        return ImportTable(name,sha256(bytes),rows,header,detect(rows[header]))
    }
    private fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    } catch (_: Exception) { bytes.toString(charset("windows-1252")) }
    fun csv(text: String): List<List<String>> {
        require(!text.trimStart().startsWith("<")) { "El archivo parece HTML/XML. Exporta CSV, XLS o XLSX." }
        val candidates=listOf(';',',','\t')
        val delimiter=candidates.maxBy { d -> text.lineSequence().take(20).sumOf { line -> var quoted=false; var count=0; line.forEach { if(it=='"') quoted=!quoted else if(it==d&&!quoted) count++ }; count } }
        val rows=mutableListOf<List<String>>(); var row=mutableListOf<String>(); val field=StringBuilder(); var quote=false; var closed=false; var i=0
        fun endField() { require(field.length<=10_000){"Campo demasiado largo"}; row.add(field.toString()); field.setLength(0); closed=false; require(row.size<=100){"Demasiadas columnas"} }
        fun endRow() { endField(); if(row.any { it.isNotBlank() }) rows.add(row); row=mutableListOf(); require(rows.size<=MAX_ROWS){"Demasiadas filas"} }
        while(i<text.length) {
            val c=text[i]
            when {
                quote -> if(c=='"') { if(i+1<text.length && text[i+1]=='"') { field.append('"'); i++ } else { quote=false; closed=true } } else field.append(c)
                c=='"' -> { require(field.isEmpty() && !closed){"Comillas CSV inválidas"}; quote=true }
                c==delimiter -> endField()
                c=='\n' || c=='\r' -> { endRow(); if(c=='\r' && i+1<text.length && text[i+1]=='\n') i++ }
                closed -> require(c.isWhitespace()){ "Texto después del cierre de comillas" }
                else -> field.append(c)
            }; i++
        }
        require(!quote){"CSV con comillas sin cerrar"}
        if(field.isNotEmpty()||row.isNotEmpty()||closed) endRow()
        return rows
    }
    fun detect(row: List<String>): ColumnMap {
        val cells=row.map(::normalized)
        fun col(vararg names:String)=cells.indexOfFirst { it in names }
        return ColumnMap(
            date=col("FECHA","FECHA OPERACION","FECHA DE OPERACION","F. OPERACION","FECHA CONTABLE"),
            concept=col("CONCEPTO","DESCRIPCION","DETALLE","DESCRIPCION DEL MOVIMIENTO"),
            amount=col("IMPORTE","IMPORTE EUR","IMPORTE (€)","CANTIDAD"),
            debit=col("DEBE","CARGO","CARGOS"), credit=col("HABER","ABONO","ABONOS"),
            valueDate=col("FECHA VALOR","F. VALOR"),reference=col("REFERENCIA","ID OPERACION"),balance=col("SALDO","SALDO (€)"),
            description=if(col("CONCEPTO")>=0) col("DESCRIPCION","DETALLE","DESCRIPCION DEL MOVIMIENTO") else -1
        )
    }
    fun preview(table: ImportTable, mapping: ColumnMap, accountId: String, existing: List<Movement>, rules: List<Rule>): ImportPreview {
        require(mapping.date>=0 && mapping.concept>=0 && (mapping.amount>=0 || mapping.debit>=0 || mapping.credit>=0)){"Selecciona fecha, concepto e importe (o debe/haber)"}
        val chosen=listOf(mapping.date,mapping.concept,mapping.amount,mapping.debit,mapping.credit,mapping.valueDate,mapping.reference,mapping.balance,mapping.description).filter{it>=0}
        require(chosen.distinct().size==chosen.size){"Una columna está asignada a varios campos"}
        // Bank references may identify a card or contract, not a transaction.
        // Match occurrences against stored movements, preserving repeated purchases.
        fun key(fingerprint:String, balance:Long?) = "$fingerprint|${balance ?: ""}"
        val counts=existing.filter{it.accountId==accountId}.groupingBy{key(it.fingerprint,it.balance)}.eachCount().toMutableMap()
        val rows=table.rows.drop(table.headerRow+1).mapIndexed { index,row ->
            val sourceRow=table.headerRow+index+2
            try {
                fun cell(i:Int)=row.getOrNull(i)?.trim().orEmpty()
                val date=bankDate(cell(mapping.date)); val concept=listOf(cell(mapping.concept),cell(mapping.description)).filter{it.isNotBlank()}.distinct().joinToString(" · ")
                require(concept.isNotBlank()) { "Concepto vacío" }
                val amount=if(mapping.amount>=0) amountCents(cell(mapping.amount)) else {
                    val debit=cell(mapping.debit).takeIf{it.isNotBlank()}?.let(::amountCents) ?: 0L
                    val credit=cell(mapping.credit).takeIf{it.isNotBlank()}?.let(::amountCents) ?: 0L
                    require(debit>=0 && credit>=0 && !(debit>0 && credit>0)){"Debe/haber ambiguos"}
                    credit-debit
                }
                val ref=cell(mapping.reference).takeIf{it.isNotBlank()}
                val fingerprint=sha256("$accountId|$date|$amount|${normalized(concept)}".toByteArray())
                val balance=cell(mapping.balance).takeIf{it.isNotBlank()}?.let(::amountCents)
                val matchKey=key(fingerprint,balance)
                val duplicate=(counts[matchKey]?:0)>0
                if(duplicate) counts[matchKey]=counts.getValue(matchKey)-1
                val movement=Movement(accountId=accountId,date=date,amount=amount,original=concept,categoryId=categorize(concept,rules),reference=ref,
                    valueDate=cell(mapping.valueDate).takeIf{it.isNotBlank()}?.let(::bankDate), balance=balance,fingerprint=fingerprint,sourceRow=sourceRow)
                PreviewRow(sourceRow,movement,duplicate=duplicate)
            } catch(e: Exception) { PreviewRow(sourceRow,null,e.message ?: "Fila no válida",selected=false) }
        }
        return ImportPreview(table.copy(mapping=mapping),rows,accountId)
    }
    private fun xls(bytes: ByteArray): List<List<String>> {
        val settings=jxl.WorkbookSettings().apply { setSuppressWarnings(true); locale=java.util.Locale.forLanguageTag("es-ES") }
        val workbook=jxl.Workbook.getWorkbook(ByteArrayInputStream(bytes),settings)
        try {
            require(workbook.numberOfSheets==1){"El Excel tiene varias hojas: exporta solo la hoja de movimientos"}
            val sheet=workbook.getSheet(0); require(sheet.rows<=MAX_ROWS && sheet.columns<=100){"Excel fuera de límites"}
            return (0 until sheet.rows).map { r -> (0 until sheet.columns).map { c ->
                val cell=sheet.getCell(c,r)
                when(cell) {
                    is jxl.DateCell -> java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT).apply{timeZone=java.util.TimeZone.getTimeZone("UTC")}.format(cell.date)
                    is jxl.NumberCell -> java.math.BigDecimal.valueOf(cell.value).stripTrailingZeros().toPlainString()
                    else -> cell.contents
                }.also{require(it.length<=10_000){"Campo demasiado largo"}}
            } }.filter{it.any(String::isNotBlank)}
        } finally { workbook.close() }
    }
    private fun xml(bytes: ByteArray): XmlPullParser {
        require(!bytes.toString(Charsets.UTF_8).contains("<!DOCTYPE",true)){"XML con DTD no admitido"}
        return XmlPullParserFactory.newInstance().apply{isNamespaceAware=true}.newPullParser().apply{setInput(ByteArrayInputStream(bytes),"UTF-8")}
    }
    private fun xlsx(bytes: ByteArray): List<List<String>> {
        val entries=mutableMapOf<String,ByteArray>(); var total=0; var count=0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while(true) {
                val entry=zip.nextEntry ?: break; require(++count<=500){"Demasiadas entradas en Excel"}
                val data=zip.limitedBytes(40*1024*1024-total); total+=data.size
                if(entry.name.startsWith("xl/")) entries[entry.name]=data
            }
        }
        val sheets=entries.keys.filter{Regex("xl/worksheets/sheet\\d+\\.xml").matches(it)}
        require(sheets.size==1){"Selecciona un Excel con una sola hoja de movimientos"}
        require(entries.keys.none{it.endsWith("vbaProject.bin")}){"No se admiten macros"}
        val shared=mutableListOf<String>()
        entries["xl/sharedStrings.xml"]?.let { data ->
            val p=xml(data); var text=StringBuilder(); var inString=false
            while(p.eventType!=XmlPullParser.END_DOCUMENT) {
                if(p.eventType==XmlPullParser.START_TAG && p.name=="si") {text=StringBuilder();inString=true}
                if(p.eventType==XmlPullParser.START_TAG && p.name=="t" && inString) text.append(p.nextText())
                if(p.eventType==XmlPullParser.END_TAG && p.name=="si") { require(text.length<=10_000);shared.add(text.toString());inString=false }
                p.next()
            }
        }
        var date1904=false
        entries["xl/workbook.xml"]?.let { val p=xml(it); while(p.eventType!=XmlPullParser.END_DOCUMENT) { if(p.eventType==XmlPullParser.START_TAG && p.name=="workbookPr") date1904=p.getAttributeValue(null,"date1904") in listOf("1","true");p.next() } }
        val dateStyles=mutableSetOf<Int>()
        entries["xl/styles.xml"]?.let { data ->
            val p=xml(data);val formats=mutableMapOf<Int,String>();var inXfs=false;var idx=0
            while(p.eventType!=XmlPullParser.END_DOCUMENT) {
                if(p.eventType==XmlPullParser.START_TAG) when(p.name) {
                    "numFmt" -> formats[p.getAttributeValue(null,"numFmtId").toInt()]=p.getAttributeValue(null,"formatCode")
                    "cellXfs" -> inXfs=true
                    "xf" -> if(inXfs) {val id=p.getAttributeValue(null,"numFmtId")?.toIntOrNull() ?: 0; val fmt=formats[id].orEmpty().replace(Regex("\"[^\"]*\""),"");if(id in 14..22 || (fmt.contains('y',true)&&fmt.contains('d',true))) dateStyles.add(idx);idx++}
                }
                if(p.eventType==XmlPullParser.END_TAG && p.name=="cellXfs") inXfs=false
                p.next()
            }
        }
        val rows=mutableListOf<List<String>>(); val p=xml(entries.getValue(sheets.single())); var row=mutableListOf<String>();var type="";var style=-1;var column=0;var value="";var formula=false
        while(p.eventType!=XmlPullParser.END_DOCUMENT) {
            if(p.eventType==XmlPullParser.START_TAG) when(p.name) {
                "row" -> {row=mutableListOf();column=-1}
                "c" -> { type=p.getAttributeValue(null,"t").orEmpty();style=p.getAttributeValue(null,"s")?.toIntOrNull()?:-1;value="";formula=false;val ref=p.getAttributeValue(null,"r").orEmpty();column=if(ref.isBlank())column+1 else ref.takeWhile{it.isLetter()}.fold(0){acc,c->acc*26+(c.uppercaseChar()-'A'+1)}-1;require(column in 0..99){"Demasiadas columnas"} }
                "v","t" -> value+=p.nextText()
                "f" -> formula=true
            }
            if(p.eventType==XmlPullParser.END_TAG) when(p.name) {
                "c" -> {
                    if(formula && value.isBlank()) value="[Fórmula sin valor]"
                    else if(type=="s") value=shared.getOrNull(value.toIntOrNull()?:-1) ?: error("Referencia de texto inválida")
                    else if(style in dateStyles && value.toDoubleOrNull()!=null) {
                        val serial=value.toDouble().toLong();require(serial in 1..150000){"Fecha Excel inválida"}
                        value=(if(date1904) LocalDate.of(1904,1,1).plusDays(serial) else LocalDate.of(1899,12,31).plusDays(serial-if(serial>=60)1 else 0)).toString()
                    }
                    require(value.length<=10_000){"Campo demasiado largo"};while(row.size<=column)row.add("");row[column]=value
                }
                "row" -> { if(row.any{it.isNotBlank()})rows.add(row);require(rows.size<=MAX_ROWS){"Demasiadas filas"} }
            }
            p.next()
        }
        return rows
    }
}
