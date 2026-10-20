package es.vidal.finanzas

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.room.Room
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object Vault {
    private const val ALIAS="vidal.finanzas.vault.v1"
    fun open(context: Context): FinanceDb {
        System.loadLibrary("sqlcipher")
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        val file=AtomicFile(File(context.noBackupFilesDir,"vault.key"))
        val exists=file.baseFile.exists()
        if(!store.containsAlias(ALIAS)) {
            require(!exists){"Clave del dispositivo no disponible. Recupera desde una copia cifrada en una instalación nueva."}
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setUserAuthenticationRequired(true)
                    .setUserAuthenticationParameters(60,KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    .setInvalidatedByBiometricEnrollment(false).build())
            }.generateKey()
        }
        val key=store.getKey(ALIAS,null) as SecretKey
        val pass=if(exists) {
            val data=file.readFully(); require(data.size>=28)
            Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,data.copyOfRange(0,12)));doFinal(data.copyOfRange(12,data.size)) }
        } else {
            val random=ByteArray(32).also{SecureRandom().nextBytes(it)}
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key)}
            val encrypted=cipher.iv+cipher.doFinal(random)
            val out=file.startWrite()
            try{out.write(encrypted);file.finishWrite(out)}catch(e:Exception){file.failWrite(out);random.fill(0);throw e}
            random
        }
        try {
            return Room.databaseBuilder(context,FinanceDb::class.java,"finanzas.db")
                .openHelperFactory(SupportOpenHelperFactory(pass))
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
                .build().also { db -> try { db.openHelper.writableDatabase } catch(e:Exception){db.close();throw e} }
        } finally { pass.fill(0) }
    }
}
object BackupCrypto {
    private val magic="VFIN001".toByteArray(Charsets.US_ASCII)
    private fun derive(password: CharArray,salt: ByteArray): SecretKeySpec {
        val spec=PBEKeySpec(password,salt,600_000,256)
        try { val bytes=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded;try{return SecretKeySpec(bytes,"AES")}finally{bytes.fill(0)} } finally {spec.clearPassword()}
    }
    fun encrypt(plain:ByteArray,password:CharArray): ByteArray {
        require(password.size>=12){"Usa una contraseña de al menos 12 caracteres"}
        val salt=ByteArray(16).also{SecureRandom().nextBytes(it)}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,derive(password,salt));updateAAD(magic+salt)}
        return magic+salt+cipher.iv+cipher.doFinal(plain)
    }
    fun decrypt(data:ByteArray,password:CharArray):ByteArray {
        require(data.size>=51 && data.copyOfRange(0,7).contentEquals(magic)){"No es una copia compatible"}
        val salt=data.copyOfRange(7,23);val iv=data.copyOfRange(23,35)
        return Cipher.getInstance("AES/GCM/NoPadding").run{init(Cipher.DECRYPT_MODE,derive(password,salt),GCMParameterSpec(128,iv));updateAAD(magic+salt);doFinal(data.copyOfRange(35,data.size))}
    }
}
