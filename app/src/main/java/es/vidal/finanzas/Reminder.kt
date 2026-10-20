package es.vidal.finanzas
import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class Reminder(context:Context,params:WorkerParameters):Worker(context,params){
    override fun doWork():Result {
        val context=applicationContext
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return Result.success()
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("review","Revisión de finanzas",NotificationManager.IMPORTANCE_DEFAULT))
        val intent=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(1,NotificationCompat.Builder(context,"review").setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Tu revisión semanal").setContentText("Importa tu extracto y revisa tus objetivos.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(intent).setAutoCancel(true).build())
        return Result.success()
    }
    companion object{
        fun set(context:Context,enabled:Boolean){
            val wm=WorkManager.getInstance(context)
            if(enabled)wm.enqueueUniquePeriodicWork("weekly-review",ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<Reminder>(7,TimeUnit.DAYS).setInitialDelay(7,TimeUnit.DAYS).build())
            else wm.cancelUniqueWork("weekly-review")
            context.getSharedPreferences("ui",Context.MODE_PRIVATE).edit().putBoolean("reminder",enabled).apply()
        }
    }
}
