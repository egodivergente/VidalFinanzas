package es.vidal.finanzas

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class NoticeTest {
    @Test fun capturesCardPayment(){assertEquals(-1234L,NoticeParser.amount("Has realizado un pago de 12,34 EUR en TIENDA"))}
    @Test fun capturesDirectDebit(){assertEquals(-5200L,NoticeParser.amount("Recibo de electricidad cargado por 52,00 €"))}
    @Test fun capturesPayroll(){assertEquals(180050L,NoticeParser.amount("Ingreso de nómina: 1.800,50 €"))}
    @Test fun capturesIncomingTransfer(){assertEquals(4500L,NoticeParser.amount("Transferencia recibida de 45 EUR"))}
    @Test fun capturesOutgoingTransfer(){assertEquals(-4500L,NoticeParser.amount("Transferencia enviada de 45 EUR"))}
    @Test fun capturesRefund(){assertEquals(1290L,NoticeParser.amount("Devolución por 12,90 €"))}
    @Test fun refusesAmbiguousBalance(){assertNull(NoticeParser.amount("Pago de 12,34 EUR. Saldo 900,00 EUR"))}
    @Test fun neverStoresAuthenticationMessages(){listOf("Código para confirmar pago de 10 EUR: 123456","Tu clave para pago es 123456","OTP para compra 2 EUR").forEach{assertFalse(NoticeParser.relevant(it))}}
    @Test fun rejectedAndFuturePaymentsAreNotCounted(){listOf("Pago rechazado 10 EUR","Recibo previsto de 50 EUR","Pago pendiente 10 EUR").forEach{assertNull(NoticeParser.amount(it))}}
    @Test fun transferDirectionMustBeKnown(){assertNull(NoticeParser.amount("Transferencia de 30 EUR"))}
    @Test fun noAmountNoGuess(){assertNull(NoticeParser.amount("Compra realizada en TIENDA"))}
    @Test fun foreignCurrencyIsNotConverted(){assertNull(NoticeParser.amount("Pago de 30 USD"))}
    @Test fun unsupportedNumberFormatIsNotMisread(){assertNull(NoticeParser.amount("Pago 1,234.56 EUR"))}
    @Test fun amountOnlyNotificationNeedsReview(){assertTrue(NoticeParser.relevant("10,00 EUR en TIENDA"));assertNull(NoticeParser.amount("10,00 EUR en TIENDA"))}
    @Test fun ordinaryNotificationsAreIgnored(){assertFalse(NoticeParser.relevant("Bienvenido a tu aplicación"))}
    private fun notice()=CapturedNotice("n","bank","a",LocalDate.of(2026,10,15).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),"Pago 10 EUR",-1000)
    @Test fun reconciliationIsScopedToAccountAndDate(){
        val n=notice();val m=Movement(accountId="a",date="2026-10-16",amount=-1000,original="TIENDA",fingerprint="f")
        assertEquals(listOf(m),NoticeParser.candidateMovements(n,listOf(m,m.copy(accountId="b"),m.copy(date="2026-09-01"),m.copy(amount=1000))))
    }
    @Test fun sameAmountOnlySuggestsDuplicateInShortWindow(){val n=notice();assertTrue(NoticeParser.possibleDuplicate(n,n.copy(id="n2",source="wallet",postedAt=n.postedAt+5000)));assertFalse(NoticeParser.possibleDuplicate(n,n.copy(id="n2",postedAt=n.postedAt+300000)));assertFalse(NoticeParser.possibleDuplicate(n,n.copy(id="n2",accountId="b")))}
}
