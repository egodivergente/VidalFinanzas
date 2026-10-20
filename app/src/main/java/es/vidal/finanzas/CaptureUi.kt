package es.vidal.finanzas

import android.content.Intent
import android.provider.Settings
import android.content.ComponentName
import android.app.NotificationManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable fun CaptureEntry(s:Snapshot,account:String) {
    val c=LocalContext.current;val revision by CaptureStore.revision.collectAsStateWithLifecycle()
    var open by remember{mutableStateOf(false)}
    val rows by produceState<List<CapturedNotice>>(emptyList(),revision){value=withContext(Dispatchers.IO){runCatching{CaptureStore.read(c)}.getOrDefault(emptyList())}}
    OutlinedButton(onClick={open=true},modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp)){
        Text("Avisos automáticos · ${rows.count{it.status=="pending"}} pendientes")
    }
    if(open)CaptureDialog(s,account,rows){open=false}
}

@Composable private fun CaptureDialog(s:Snapshot,account:String,rows:List<CapturedNotice>,close:()->Unit) {
    val c=LocalContext.current;val p=remember{CaptureStore.prefs(c)};val scope=rememberCoroutineScope()
    var setup by remember{mutableStateOf(!p.getBoolean("enabled",false))}
    var enabled by remember{mutableStateOf(p.getBoolean("enabled",false))}
    var sources by remember{mutableStateOf(p.getStringSet("sources",emptySet()).orEmpty().toSet())}
    var assigned by remember{mutableStateOf(p.getString("account",null))}
    var search by remember{mutableStateOf("")};var error by remember{mutableStateOf<String?>(null)}
    var selected by remember{mutableStateOf<CapturedNotice?>(null)}
    var editing by remember{mutableStateOf<CapturedNotice?>(null)}
    var amountText by remember{mutableStateOf("")}
    var clear by remember{mutableStateOf(false)}
    var permission by remember{mutableStateOf(c.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(c,FinanceNotificationListener::class.java)))}
    val apps=remember{
        c.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
            .map{it.activityInfo.packageName to it.loadLabel(c.packageManager).toString()}.distinctBy{it.first}
            .filter{it.first!=c.packageName}.sortedBy{it.second}
    }
    fun update(n:CapturedNotice,state:String,linked:String?=null){scope.launch{try{withContext(Dispatchers.IO){CaptureStore.status(c,n.id,state,linked)}}catch(_:Exception){error="No se pudo actualizar la bandeja"}}}
    Dialog(onDismissRequest=close){Surface(shape=MaterialTheme.shapes.large){Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(16.dp)){
        Text("Avisos automáticos",style=MaterialTheme.typography.titleLarge)
        Row{TextButton(onClick={setup=false}){Text("Bandeja")};TextButton(onClick={setup=true}){Text("Configurar")};TextButton(onClick=close){Text("Cerrar")}}
        if(error!=null)Text(error!!,color=MaterialTheme.colorScheme.error)
        if(p.getBoolean("captureError",false))Text("La captura encontró un error o la bandeja está llena. Revisa el espacio y los avisos guardados.",color=MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)){
            if(setup){
                item{Text("Android concede acceso a las notificaciones. Esta app filtra las fuentes que selecciones y guarda solo posibles avisos financieros, cifrados en el móvil. No envía información ni guarda códigos de acceso.")}
                item{Text("1. Cuenta destino: ${s.accounts.find{it.id==assigned}?.name?:"sin asignar"}");OutlinedButton(onClick={assigned=account;p.edit().putString("account",account).apply()}){Text("Usar ${s.accounts.find{it.id==account}?.name?:"cuenta seleccionada"}")}}
                item{Text("2. Selecciona Ibercaja y/o Wallet entre tus apps. Los avisos de recibos, nóminas y transferencias también se capturan si llegan.");OutlinedTextField(search,{search=it},label={Text("Buscar Ibercaja, Wallet…")},modifier=Modifier.fillMaxWidth())}
                items(apps.filter{it.first in sources || (search.isNotBlank() && (it.second.contains(search,true)||it.first.contains(search,true)))},key={it.first}){(pkg,label)->
                    Row{Checkbox(pkg in sources,{on->sources=if(on)sources+pkg else sources-pkg;p.edit().putStringSet("sources",sources).apply()});Column{Text(label);Text(pkg,style=MaterialTheme.typography.bodySmall)}}
                }
                item{Text("3. Acceso de Android: ${if(permission)"concedido" else "pendiente"}");Button(onClick={runCatching{c.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))}.onFailure{error="Abre Ajustes de Android > Acceso a notificaciones"}}){Text("Abrir permiso de Android")};TextButton(onClick={permission=c.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(c,FinanceNotificationListener::class.java))}){Text("Comprobar permiso")}}
                item{Row{Switch(enabled,{enabled=it;p.edit().putBoolean("enabled",it).apply()},enabled=assigned!=null&&s.accounts.any{it.id==assigned}&&sources.isNotEmpty());Text(if(enabled)"Captura activada"else"Captura pausada")};Text("Empieza con los avisos nuevos. El permiso de Android también debe estar concedido. Puede dejar de recibirlos si Android restringe el servicio.")}
                item{Text("Conexión bancaria: no configurada. Los movimientos sin notificación todavía se incorporan mediante Excel/CSV. No hay sincronización completa con Ibercaja en esta versión.")}
                item{Text("La bandeja contiene hasta 500 avisos. Está cifrada con una clave del dispositivo que permite capturar en segundo plano. No se incluye en la copia financiera; conserva tu extracto.")}
            }else{
                item{Text("Provisionales: no alteran el saldo ni los presupuestos confirmados. Al importar el banco, vincula cada aviso a su movimiento para conciliarlos.")}
                if(rows.isEmpty())item{Text("No hay avisos guardados. Configura las fuentes y espera una notificación nueva.")}
                items(rows.sortedByDescending{it.postedAt},key={it.id}){n->
                    Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=MaterialTheme.shapes.medium){Column(Modifier.padding(12.dp)){
                        Text("${n.date} · ${apps.find{it.first==n.source}?.second?:n.source}",style=MaterialTheme.typography.labelMedium)
                        Text(s.accounts.find{it.id==n.accountId}?.name?:"Cuenta no disponible")
                        Text(n.amount?.let(::money)?:"Importe o tipo por revisar",style=MaterialTheme.typography.titleMedium)
                        Text(n.text,style=MaterialTheme.typography.bodySmall)
                        Text(when(n.status){"linked"->if(s.movements.any{it.id==n.linkedId})"Conciliado con el banco"else"Vínculo no disponible: revisar";"dismissed"->"Descartado";else->"Provisional"})
                        if(n.status=="pending"){
                            if(rows.any{it.status=="pending"&&NoticeParser.possibleDuplicate(n,it)})Text("Posible aviso repetido: compara las fuentes antes de conciliar.")
                            Text("Categoría sugerida: ${s.categories.find{it.id==categorize(n.text,s.rules)}?.name?:"Sin categoría"}")
                            Row{TextButton(onClick={selected=n}){Text("Conciliar")};TextButton(onClick={update(n,"dismissed")}){Text("Descartar")}}
                            TextButton(onClick={editing=n;amountText=n.amount?.let{java.math.BigDecimal.valueOf(it,2).toPlainString()}.orEmpty()}){Text("Revisar importe y signo")}
                        }else TextButton(onClick={update(n,"pending")}){Text("Volver a revisar")}
                    }}
                }
                if(rows.any{it.status!="pending"})item{TextButton(onClick={clear=true}){Text("Eliminar avisos revisados…")}}
            }
        }
    }}}
    selected?.let{n->val matches=NoticeParser.candidateMovements(n,s.movements)
        AlertDialog(onDismissRequest={selected=null},title={Text("Selecciona el movimiento bancario")},text={Column{
            Text("Coincidencias por cuenta, importe y fecha ±3 días. Verifica el concepto: no se vinculan automáticamente.")
            if(matches.isEmpty())Text("No hay coincidencias. Importa el extracto de la cuenta o espera a que el movimiento esté contabilizado.")
            LazyColumn(Modifier.heightIn(max=300.dp)){items(matches){m->TextButton(onClick={update(n,"linked",m.id);selected=null}){Text("${m.date} · ${money(m.amount)} · ${m.original}")}}}
        }},confirmButton={TextButton(onClick={selected=null}){Text("Cerrar")}})
    }
    editing?.let{n->val cents=runCatching{amountCents(amountText)}.getOrNull()
        AlertDialog(onDismissRequest={editing=null},title={Text("Revisar importe")},text={Column{Text("Negativo para un cargo, positivo para un ingreso. Sigue siendo provisional.");OutlinedTextField(amountText,{amountText=it},label={Text("Importe en euros")})}},confirmButton={TextButton(enabled=cents!=null&&cents!=0L,onClick={val value=cents!!;editing=null;scope.launch{try{withContext(Dispatchers.IO){CaptureStore.correctAmount(c,n.id,value)}}catch(_:Exception){error="No se pudo guardar el importe"}}}){Text("Guardar")}},dismissButton={TextButton(onClick={editing=null}){Text("Cancelar")}})
    }
    if(clear)AlertDialog(onDismissRequest={clear=false},title={Text("¿Eliminar avisos revisados?")},text={Text("Se borrarán de la bandeja los avisos conciliados o descartados. Los movimientos bancarios y avisos pendientes se conservan.")},confirmButton={TextButton(onClick={clear=false;scope.launch{try{withContext(Dispatchers.IO){CaptureStore.clearReviewed(c)};p.edit().putBoolean("captureError",false).apply()}catch(_:Exception){error="No se pudo limpiar la bandeja"}}}){Text("Eliminar")}},dismissButton={TextButton(onClick={clear=false}){Text("Cancelar")}})
}
