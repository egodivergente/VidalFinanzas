package es.vidal.finanzas

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class MainActivity:FragmentActivity(){
    private val vm:FinanceVm by viewModels()
    private val pickImport=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.pending.value="import" to uri}
    private val pickRestore=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)vm.pending.value="restore" to uri}
    private val createBackup=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->if(uri!=null)vm.pending.value="backup" to uri}
    private val notification=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)Reminder.set(this,true)else vm.notice("Permiso denegado. Puedes activarlo desde Ajustes de Android.")}
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { FinanceApp(vm,::authenticate,{pickImport.launch(arrayOf("*/*"))},{createBackup.launch("VidalFinanzas-${java.time.LocalDate.now()}.vfin")},{pickRestore.launch(arrayOf("*/*"))},{enabled->
            if(enabled&&Build.VERSION.SDK_INT>=33)notification.launch(Manifest.permission.POST_NOTIFICATIONS)else Reminder.set(this,enabled)
        }) }
    }
    private fun authenticate(){
        val allowed=BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if(BiometricManager.from(this).canAuthenticate(allowed)!=BiometricManager.BIOMETRIC_SUCCESS){vm.notice("Activa un PIN, patrón o contraseña de bloqueo en Android para proteger tus datos.");return}
        BiometricPrompt(this,ContextCompat.getMainExecutor(this),object:BiometricPrompt.AuthenticationCallback(){
            override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){vm.unlock()}
            override fun onAuthenticationError(code:Int,text:CharSequence){if(code!=BiometricPrompt.ERROR_USER_CANCELED&&code!=BiometricPrompt.ERROR_CANCELED)vm.notice(text.toString())}
        }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Desbloquear tus finanzas").setSubtitle("Los datos permanecen en este móvil").setAllowedAuthenticators(allowed).build())
    }
    override fun onStart(){super.onStart();vm.enterForeground()}
    override fun onStop(){
        super.onStop()
        if(!isChangingConfigurations){
            if(getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked)vm.lock()
            else vm.enterBackground()
        }
    }
}
