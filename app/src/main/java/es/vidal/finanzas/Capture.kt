package es.vidal.finanzas

import android.app.Notification
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import kotlin.math.abs

@Serializable data class CapturedNotice(
    val id:String, val source:String, val accountId:String, val postedAt:Long,
    val text:String, val amount:Long?, val status:String="pending", val linkedId:String?=null
) {
    val date:String get()=Instant.ofEpochMilli(postedAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}

object NoticeParser {
    private val sensitive=Regex("\\b(OTP|CLAVE|CODIGO|CONTRASENA|VERIFICACION|AUTENTICACION|AUTORIZAR|CONFIRMAR OPERACION|CODE|PASSWORD|VERIFICATION|AUTHENTICATION)\\b")
    private val financial=Regex("\\b(PAGO|PAGADO|PAGASTE|COMPRA|CARGO|COBRO|RECIBO|DOMICILIACION|TRANSFERENCIA|INGRESO|ABONO|NOMINA|DEVOLUCION|REEMBOLSO|PAID|PAYMENT|PURCHASE)\\b")
    private val positive=Regex("\\b(INGRESO|ABONO|NOMINA|DEVOLUCION|REEMBOLSO|RECIBIDA|RECIBIDO)\\b")
    private val negative=Regex("\\b(PAGO|PAGADO|PAGASTE|COMPRA|CARGO|COBRO|RECIBO|DOMICILIACION|ENVIADA|ENVIADO|PAID|PAYMENT|PURCHASE)\\b")
    private val uncertain=Regex("\\b(RECHAZAD[AO]|DENEGAD[AO]|CANCELAD[AO]|PENDIENTE|PREVIST[AO]|PROXIM[AO]|INTENTO|DECLINED|FAILED|MANANA)\\b")
    private val currency=Regex("(?i)(?<![\\d.,])([+-]?(?:\\d{1,3}(?:\\.\\d{3})+|\\d+)(?:[,.]\\d{1,2})?)\\s*(?:€|EUR\\b)")
    fun relevant(text:String):Boolean {val n=normalized(text);return !sensitive.containsMatchIn(n)&&(financial.containsMatchIn(n)||currency.containsMatchIn(text))}
    fun amount(text:String):Long? {
        if(!relevant(text)||uncertain.containsMatchIn(normalized(text)))return null
        val matches=currency.findAll(text).toList()
        if(matches.size!=1)return null // multiple amounts may include balances or fees
        val value=runCatching{amountCents(matches.single().groupValues[1])}.getOrNull()?:return null
        val n=normalized(text); val plus=positive.containsMatchIn(n);val minus=negative.containsMatchIn(n)
        if(plus==minus || value==0L)return null
        return if(plus)abs(value)else -abs(value)
    }
    fun candidateMovements(n:CapturedNotice,movements:List<Movement>):List<Movement> = movements.filter {
        it.accountId==n.accountId && n.amount!=null && it.amount==n.amount &&
        abs(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(n.date),LocalDate.parse(it.date)))<=3
    }
    fun possibleDuplicate(a:CapturedNotice,b:CapturedNotice):Boolean = a.id!=b.id && a.accountId==b.accountId &&
        a.amount!=null && a.amount==b.amount && abs(a.postedAt-b.postedAt)<=120_000
}

// Separate bounded inbox: background reception never opens the authenticated financial database.
object CaptureStore {
    val revision=MutableStateFlow(0L)
    private const val ALIAS="vidal.finanzas.notifications.v1"
    fun prefs(c:Context)=c.getSharedPreferences("capture-config",Context.MODE_PRIVATE)
    private fun file(c:Context)=AtomicFile(File(c.noBackupFilesDir,"notifications.enc"))
    private fun key(c:Context):SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        if(!ks.containsAlias(ALIAS)) {
            check(!file(c).baseFile.exists()){"No se puede abrir la bandeja cifrada"}
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
                init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            }.generateKey()
        }
        return ks.getKey(ALIAS,null) as SecretKey
    }
    @Synchronized fun read(c:Context):List<CapturedNotice> {
        if(!file(c).baseFile.exists())return emptyList()
        val bytes=file(c).readFully();check(bytes.size in 28..2_000_000)
        val plain=Cipher.getInstance("AES/GCM/NoPadding").run{
            init(Cipher.DECRYPT_MODE,key(c),GCMParameterSpec(128,bytes.copyOfRange(0,12)));doFinal(bytes.copyOfRange(12,bytes.size))
        }
        return try{Json.decodeFromString<List<CapturedNotice>>(plain.toString(Charsets.UTF_8))}finally{plain.fill(0)}
    }
    private fun write(c:Context,rows:List<CapturedNotice>) {
        val plain=Json.encodeToString(rows).toByteArray()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key(c))}
        val encrypted=try{cipher.iv+cipher.doFinal(plain)}finally{plain.fill(0)}
        val f=file(c);val out=f.startWrite()
        try{out.write(encrypted);f.finishWrite(out)}catch(e:Exception){f.failWrite(out);throw e}
        revision.value++
    }
    @Synchronized fun add(c:Context,n:CapturedNotice) {
        val rows=read(c)
        if(rows.any{it.id==n.id})return
        check(rows.size<500){"Bandeja llena (500 avisos). Elimina avisos revisados para seguir capturando."}
        write(c,rows+n)
    }
    @Synchronized fun status(c:Context,id:String,status:String,linkedId:String?=null) {
        write(c,read(c).map{if(it.id==id)it.copy(status=status,linkedId=linkedId)else it})
    }
    @Synchronized fun clearReviewed(c:Context){write(c,read(c).filter{it.status=="pending"})}
    @Synchronized fun correctAmount(c:Context,id:String,amount:Long){
        require(amount!=0L)
        write(c,read(c).map{if(it.id==id)it.copy(amount=amount,status="pending",linkedId=null)else it})
    }
    fun failure(c:Context){prefs(c).edit().putBoolean("captureError",true).apply();revision.value++}
}

class FinanceNotificationListener:NotificationListenerService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onNotificationPosted(sbn:StatusBarNotification) {
        val p=CaptureStore.prefs(this)
        if(!p.getBoolean("enabled",false)||sbn.packageName !in p.getStringSet("sources",emptySet()).orEmpty())return
        val account=p.getString("account",null)?:return
        if(sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY!=0)return
        val e=sbn.notification.extras
        val title=e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body=(e.getCharSequence(Notification.EXTRA_BIG_TEXT)?:e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val full="$title\n$body"
        if(!NoticeParser.relevant(full)||full.length>2000)return
        val text=full.take(700)
        val n=CapturedNotice(sha256("${sbn.packageName}|${sbn.key}|${sbn.postTime}|$text".toByteArray()),sbn.packageName,account,sbn.postTime,text,NoticeParser.amount(full))
        scope.launch {try{CaptureStore.add(this@FinanceNotificationListener,n)}catch(_:Exception){CaptureStore.failure(this@FinanceNotificationListener)}}
    }
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
