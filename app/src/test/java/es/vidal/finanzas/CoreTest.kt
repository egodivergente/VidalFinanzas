package es.vidal.finanzas

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CoreTest {
    @Test fun pdfIsRejectedBeforeCsvParsing() {
        listOf("extracto.pdf", "extracto.csv", "Extracto").forEach { name ->
            val error = runCatching { BankImporter.read(name, "%PDF-1.7\n1 0 obj << /Title (\"extracto\") >>".toByteArray()) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("PDF no compatible"))
        }
    }
    @Test fun pdfExtensionIsRejectedClearly() {
        val error = runCatching { BankImporter.read("EXTRACTO.PDF", "archivo no válido".toByteArray()) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("PDF no compatible"))
    }
    @Test fun binaryFileIsNotTreatedAsCsv() {
        val error = runCatching { BankImporter.read("imagen.png", byteArrayOf(1, 0, 2, 34)) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("Formato no compatible"))
    }

    @Test fun ibercajaDescriptionAndSharedCardReference() {
        val t=BankImporter.read("banco.csv", "Fecha Operacion;Concepto;Descripción;Referencia;Importe;Saldo\n01/10/2026;TARJETA VISA;SUPERMERCADO;CARD1;-2,50;97,50\n01/10/2026;TARJETA VISA;CAFETERIA;CARD1;-2,50;95,00".toByteArray())
        val p=BankImporter.preview(t,t.mapping,"a",emptyList(),emptyList())
        assertEquals(2,p.rows.count{it.selected})
        assertEquals("TARJETA VISA · SUPERMERCADO",p.rows[0].movement!!.original)
        assertEquals(0,BankImporter.preview(t,t.mapping,"a",p.rows.map{it.movement!!},emptyList()).rows.count{it.selected})
        assertEquals(1,BankImporter.preview(t,t.mapping,"a",listOf(p.rows[1].movement!!),emptyList()).rows.count{it.selected})
    }
    @Test fun balanceDistinguishesIdenticalPurchases() {
        val t=BankImporter.read("banco.csv", "Fecha;Concepto;Importe;Saldo\n01/10/2026;CAFE;-2,50;97,50\n01/10/2026;CAFE;-2,50;95,00".toByteArray())
        val p=BankImporter.preview(t,t.mapping,"a",emptyList(),emptyList())
        val q=BankImporter.preview(t,t.mapping,"a",listOf(p.rows[1].movement!!),emptyList())
        assertTrue(q.rows[0].selected);assertTrue(q.rows[1].duplicate)
    }
    @Test fun namespacePrefixedExcelWithImplicitCellAddressesIsRead() {
        val bytes=zip("xl/worksheets/sheet1.xml" to "<x:worksheet xmlns:x=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><x:sheetData><x:row><x:c t=\"inlineStr\"><x:is><x:t>Fecha</x:t></x:is></x:c><x:c t=\"inlineStr\"><x:is><x:t>Concepto</x:t></x:is></x:c><x:c r=\"C1\" t=\"inlineStr\"><x:is><x:t>Importe</x:t></x:is></x:c></x:row><x:row><x:c t=\"inlineStr\"><x:is><x:t>01/10/2026</x:t></x:is></x:c><x:c t=\"inlineStr\"><x:is><x:t>Compra</x:t></x:is></x:c><x:c><x:v>-2.50</x:v></x:c></x:row></x:sheetData></x:worksheet>")
        val t=BankImporter.read("banco.xlsx",bytes)
        assertEquals(-250L,BankImporter.preview(t,t.mapping,"a",emptyList(),emptyList()).rows.single().movement!!.amount)
    }
    @Test fun optionalLocalBankFileIntegration() {
        val path=System.getenv("BANK_TEST_FILE")
        org.junit.Assume.assumeTrue(path != null)
        val t=BankImporter.read("extracto.xlsx",java.io.File(path!!).readBytes())
        val p=BankImporter.preview(t,t.mapping,"private-test-account",emptyList(),initialRules())
        assertEquals(System.getenv("BANK_TEST_ROWS").toInt(),p.rows.size)
        assertTrue(p.rows.all{it.selected && it.error==null})
        assertEquals(System.getenv("BANK_TEST_TOTAL").toLong(),p.rows.sumOf{it.movement!!.amount})
        val again=BankImporter.preview(t,t.mapping,"private-test-account",p.rows.map{it.movement!!},emptyList())
        assertEquals(0,again.rows.count{it.selected})
    }
    private fun movement(amount:Long,nature:String="normal",category:String="food")=Movement(accountId="a",date="2026-10-01",amount=amount,original="Tienda",fingerprint="x",nature=nature,categoryId=category)
    @Test fun decimalMoneyIsExact(){assertEquals(123456L,amountCents("1.234,56 €"));assertEquals(-999L,amountCents("-9,99"));assertEquals(-1290L,amountCents("(12,90)"));assertEquals(10L,amountCents("0.10"));assertEquals(100000L,amountCents("1000"))}
    @Test fun ambiguousMoneyIsRejected(){listOf("1.234","1,234.56","12,345","NaN","").forEach{assertTrue(runCatching{amountCents(it)}.isFailure)}}
    @Test fun datesAreStrict(){assertEquals("2024-02-29",bankDate("29/02/2024"));assertTrue(runCatching{bankDate("29/02/2025")}.isFailure);assertTrue(runCatching{bankDate("01/13/2026")}.isFailure)}
    @Test fun csvPreservesQuotedSeparatorsAndNewlines(){val rows=BankImporter.csv("Fecha;Concepto;Importe\r\n01/10/2026;\"Tienda; con \'texto\'\nsegunda línea\";-12,30\r\n");assertEquals(2,rows.size);assertTrue(rows[1][1].contains("\n"));assertEquals("-12,30",rows[1][2])}
    @Test fun csvEscapedQuotes(){assertEquals("Un \"nombre\"",BankImporter.csv("A;B\n1;\"Un \"\"nombre\"\"\"")[1][1])}
    @Test fun csvMalformedRejected(){assertTrue(runCatching{BankImporter.csv("A;B\n1;\"hola")}.isFailure)}
    @Test fun overlappingImportKeepsLegitimateRepeatedPurchases(){
        val bytes="Fecha;Concepto;Importe\n01/10/2026;Café;-2,50\n01/10/2026;Café;-2,50\n02/10/2026;Compra;-10,00".toByteArray()
        val table=BankImporter.read("test.csv",bytes)
        val initial=BankImporter.preview(table,table.mapping,"a",emptyList(),emptyList())
        assertEquals(3,initial.rows.count{it.selected})
        val partial=BankImporter.preview(table,table.mapping,"a",listOf(initial.rows[0].movement!!),emptyList())
        assertEquals(1,partial.rows.count{it.duplicate});assertEquals(2,partial.rows.count{it.selected})
        val repeated=BankImporter.preview(table,table.mapping,"a",initial.rows.map{it.movement!!},emptyList())
        assertEquals(0,repeated.rows.count{it.selected})
    }
    @Test fun duplicatesAreScopedToAccount(){val t=BankImporter.read("a.csv","Fecha;Concepto;Importe\n01/10/2026;Tienda;-10,00".toByteArray());val p=BankImporter.preview(t,t.mapping,"a",emptyList(),emptyList());val q=BankImporter.preview(t,t.mapping,"b",p.rows.map{it.movement!!},emptyList());assertTrue(q.rows.single().selected)}
    @Test fun referenceAloneDoesNotDiscardDifferentDescriptions(){val t=BankImporter.read("a.csv","Fecha;Concepto;Importe;Referencia\n01/10/2026;Tienda;-10,00;REF1".toByteArray());val p=BankImporter.preview(t,t.mapping,"a",emptyList(),emptyList());val old=p.rows.single().movement!!.copy(original="Otro nombre",fingerprint="different");assertFalse(BankImporter.preview(t,t.mapping,"a",listOf(old),emptyList()).rows.single().duplicate)}
    @Test fun repeatedReferenceWithinFileKeepsSeparatePurchases(){val t=BankImporter.read("a.csv","Fecha;Concepto;Importe;Referencia\n01/10/2026;Tienda;-10,00;REF1\n01/10/2026;Tienda;-10,00;REF1".toByteArray());val p=BankImporter.preview(t,t.mapping,"a",emptyList(),emptyList());assertEquals(2,p.rows.count{it.selected})}
    @Test fun invalidRowsRemainVisible(){val t=BankImporter.read("x.csv","Fecha;Concepto;Importe\n01/10/2026;Compra;-1,00\n31/02/2026;Error;3,00\nTOTAL;;2,00".toByteArray());val p=BankImporter.preview(t,t.mapping,"a",emptyList(),emptyList());assertEquals(1,p.rows.count{it.selected});assertEquals(2,p.rows.count{it.error!=null})}
    @Test fun debitCreditColumns(){val t=BankImporter.read("x.csv","Fecha;Concepto;Debe;Haber\n01/10/2026;Compra;10,00;\n02/10/2026;Ingreso;;30,00".toByteArray());val p=BankImporter.preview(t,t.mapping,"a",emptyList(),emptyList());assertEquals(listOf(-1000L,3000L),p.rows.map{it.movement!!.amount})}
    @Test fun transferAndRefundAccounting(){val rows=listOf(movement(-5000),movement(1000,"refund"),movement(-10000,"transfer"),movement(200000,category="salary"));assertEquals(4000L,expensesFor(rows));assertEquals(200000L,incomeFor(rows));assertEquals(4000L,expenseFor(rows,"food"))}
    @Test fun rulesAreNormalizedAndPrioritized(){val rules=listOf(Rule(text="tienda",categoryId="other",priority=10),Rule(text="TIENDA CAFE",categoryId="food",priority=20));assertEquals("food",categorize("Tienda Café",rules))}
    @Test fun backupRoundtripAndTamperResistance(){val original="Datos privados con ñ".toByteArray();val password="una frase muy larga".toCharArray();val data=BackupCrypto.encrypt(original,password);assertArrayEquals(original,BackupCrypto.decrypt(data,password));assertTrue(runCatching{BackupCrypto.decrypt(data,"otra contraseña".toCharArray())}.isFailure);data[data.lastIndex]=(data.last().toInt() xor 1).toByte();assertTrue(runCatching{BackupCrypto.decrypt(data,password)}.isFailure)}
    @Test fun invalidBackupRelationsRejected(){val s=Snapshot(accounts=listOf(Account("a","Cuenta")),categories=initialCategories(),movements=listOf(movement(100).copy(accountId="missing")));assertTrue(runCatching{validateSnapshot(s)}.isFailure)}
    @Test fun validSnapshotAccepted(){validateSnapshot(Snapshot(accounts=listOf(Account("a","Cuenta")),categories=initialCategories(),movements=listOf(movement(-100))))}
    private fun zip(vararg files:Pair<String,String>):ByteArray { val out=ByteArrayOutputStream();ZipOutputStream(out).use{z->files.forEach{(name,text)->z.putNextEntry(ZipEntry(name));z.write(text.toByteArray());z.closeEntry()}};return out.toByteArray() }
    @Test fun xlsxReadsSharedStringsDatesAndNumericAmounts(){
        val bytes=zip("xl/sharedStrings.xml" to "<sst><si><t>Fecha</t></si><si><t>Concepto</t></si><si><t>Importe</t></si><si><t>Prueba</t></si></sst>","xl/styles.xml" to "<styleSheet><cellXfs><xf numFmtId=\"0\"/><xf numFmtId=\"14\"/></cellXfs></styleSheet>","xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"C1\" t=\"s\"><v>2</v></c></row><row><c r=\"A2\" s=\"1\"><v>46296</v></c><c r=\"B2\" t=\"s\"><v>3</v></c><c r=\"C2\"><v>-12.34</v></c></row></sheetData></worksheet>")
        val t=BankImporter.read("x.xlsx",bytes);val p=BankImporter.preview(t,t.mapping,"a",emptyList(),emptyList());assertEquals(-1234L,p.rows.single().movement!!.amount);assertEquals("Prueba",p.rows.single().movement!!.original)
    }
    @Test fun xlsBinaryReader(){val out=ByteArrayOutputStream();val book=jxl.Workbook.createWorkbook(out);val sheet=book.createSheet("Movimientos",0);listOf("Fecha","Concepto","Importe").forEachIndexed{i,v->sheet.addCell(jxl.write.Label(i,0,v))};sheet.addCell(jxl.write.Label(0,1,"01/10/2026"));sheet.addCell(jxl.write.Label(1,1,"Compra"));sheet.addCell(jxl.write.Number(2,1,-12.34));book.write();book.close();val t=BankImporter.read("test.xls",out.toByteArray());assertEquals(-1234L,BankImporter.preview(t,t.mapping,"a",emptyList(),emptyList()).rows.single().movement!!.amount)}
}
