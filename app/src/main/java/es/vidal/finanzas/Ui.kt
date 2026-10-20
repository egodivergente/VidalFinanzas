@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package es.vidal.finanzas

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Mint=Color(0xFF9FE3CF)
private val Peach=Color(0xFFF0B89E)
@Composable fun FinanceApp(vm:FinanceVm,unlock:()->Unit,importFile:()->Unit,backup:()->Unit,restore:()->Unit,reminder:(Boolean)->Unit){
    val snapshot by vm.snapshot.collectAsStateWithLifecycle();val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle();val table by vm.table.collectAsStateWithLifecycle();val preview by vm.preview.collectAsStateWithLifecycle();val pending by vm.pending.collectAsStateWithLifecycle()
    val snack=remember{SnackbarHostState()}
    val context=LocalContext.current;val authUrl by vm.authorizationUrl.collectAsStateWithLifecycle()
    LaunchedEffect(authUrl){authUrl?.let{url->vm.authorizationUrl.value=null
        if(!isEnableBankingAuthUrl(url))vm.notice("Dirección de autorización no permitida")
        else try{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}catch(_:ActivityNotFoundException){vm.notice("No hay navegador disponible para autorizar")}
    }}
    LaunchedEffect(message){message?.let{snack.showSnackbar(it);vm.message.value=null}}
    LaunchedEffect(snapshot!=null,pending,busy){if(snapshot!=null&&pending?.first=="import"&&!busy){val uri=pending!!.second;vm.pending.value=null;vm.readImport(uri)}}
    MaterialTheme(colorScheme=darkColorScheme(primary=Mint,secondary=Peach,background=Color(0xFF10191D),surface=Color(0xFF19262C),surfaceVariant=Color(0xFF24353B))){
        Scaffold(snackbarHost={SnackbarHost(snack)}){padding->
            Box(Modifier.fillMaxSize().padding(padding)){
                if(snapshot==null){
                    Column(Modifier.fillMaxSize().padding(28.dp),verticalArrangement=Arrangement.Center){
                        Text("VIDAL",letterSpacing=5.sp,color=Mint,style=MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(18.dp));Text("Tu dinero.\nCon calma.",fontSize=42.sp,lineHeight=46.sp,fontWeight=FontWeight.SemiBold)
                        Spacer(Modifier.height(20.dp));Text("Gastos, ahorro y objetivos. Datos locales en tu móvil, con conexión bancaria opcional de solo lectura.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(32.dp));Button(onClick=unlock,enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(if(busy)"Abriendo…"else"Desbloquear")}
                        Spacer(Modifier.height(12.dp));Text("Necesitas el bloqueo de pantalla de Android. Se bloquea tras un minuto en segundo plano.",style=MaterialTheme.typography.bodySmall)
                    }
                }else{
                    key(snapshot!=null){MainContent(vm,snapshot!!,importFile,backup,restore,reminder)}
                    table?.let{MappingDialog(it,{vm.cancelImport()}){map,header->vm.mapImport(map,header)}}
                    preview?.let{PreviewDialog(it,busy,vm::toggleRow,vm::cancelImport,vm::commitImport)}
                    pending?.takeIf{it.first!="import"}?.let { p -> BackupDialog(p.first=="restore",busy,{vm.pending.value=null}){password->vm.pending.value=null;if(p.first=="restore")vm.restoreBackup(p.second,password)else vm.exportBackup(p.second,password)} }
                }
                if(busy)LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
    }
}
@Composable private fun MainContent(vm:FinanceVm,s:Snapshot,importFile:()->Unit,backup:()->Unit,restore:()->Unit,reminder:(Boolean)->Unit){
    var tab by rememberSaveable{mutableIntStateOf(0)};var month by rememberSaveable{mutableStateOf(YearMonth.now().toString())}
    val account by vm.selectedAccount.collectAsStateWithLifecycle();val busy by vm.busy.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){Text("VIDAL · FINANZAS",style=MaterialTheme.typography.labelMedium,color=Mint,letterSpacing=2.sp);Text("Privado · Local con conexión bancaria opcional",style=MaterialTheme.typography.bodySmall)}
            TextButton(onClick=vm::lock){Text("Bloquear")}
        }
        Row(Modifier.padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.weight(1f)){Choice("Cuenta",account,s.accounts.map{it.id to it.name}){vm.selectedAccount.value=it}}
            if(tab!=3&&tab!=4){TextButton(onClick={month=YearMonth.parse(month).minusMonths(1).toString()}){Text("‹")};Text(month,style=MaterialTheme.typography.labelLarge);TextButton(onClick={month=YearMonth.parse(month).plusMonths(1).toString()}){Text("›")}}
        }
        CaptureEntry(s,account)
        Box(Modifier.weight(1f)){
            when(tab){
                0->Dashboard(s,account,month,importFile)
                1->MovementsScreen(vm,s,account,month,importFile)
                2->BudgetsScreen(vm,s,account,month)
                3->GoalsScreen(vm,s)
                4->SettingsScreen(vm,s,backup,restore,reminder)
            }
            if(busy)Box(Modifier.fillMaxSize().clickable(enabled=true,onClick={}))
        }
        NavigationBar{listOf("Inicio","Historial","Límites","Objetivos","Ajustes").forEachIndexed{i,label->NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Text(listOf("⌂","≡","€","◎","⋯")[i],fontSize=23.sp)},label={Text(label,fontSize=10.sp)})}}
    }
}
@Composable private fun Section(title:String,subtitle:String?=null,content:@Composable ColumnScope.()->Unit){
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp)) {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text(title,style=MaterialTheme.typography.titleMedium);subtitle?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};content()}}
}
@Composable private fun Title(text:String){Text(text,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)}
@Composable private fun Dashboard(s:Snapshot,account:String,month:String,importFile:()->Unit){
    val all=s.movements.filter{it.accountId==account};val current=all.filter{it.date.startsWith(month)};val income=incomeFor(current);val expense=expensesFor(current)
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        item{Title("Una mirada al mes")}
        item{Section("Balance del periodo","No representa tu saldo bancario actual."){Text(money(income-expense),fontSize=36.sp,color=Mint,fontWeight=FontWeight.SemiBold);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Column{Text("Ingresos",style=MaterialTheme.typography.labelMedium);Text(money(income))};Column{Text("Gastos netos",style=MaterialTheme.typography.labelMedium);Text(money(expense),color=Peach)}}}}
        item{Button(onClick=importFile,modifier=Modifier.fillMaxWidth()){Text("Importar CSV o Excel")};Text("Formatos: CSV, XLS y XLSX. PDF no compatible.",style=MaterialTheme.typography.bodySmall);Text("Movimientos hasta: ${all.maxOfOrNull{it.date}?:"sin datos"}",style=MaterialTheme.typography.bodySmall);Text("Última importación: ${s.batches.firstOrNull{it.accountId==account}?.importedAt?.take(10)?:"ninguna"}",style=MaterialTheme.typography.bodySmall)}
        if(all.isEmpty())item{Section("Empieza con tu extracto"){Text("Descarga el archivo desde Ibercaja y selecciónalo aquí. Revisarás las columnas y los movimientos antes de guardarlos.")}}
        item{Section("Evolución · 6 meses","Ingresos en verde · gastos en melocotón. Sin movimientos importados no equivale a gasto cero."){
            val months=(5 downTo 0).map{YearMonth.parse(month).minusMonths(it.toLong()).toString()};val max=months.maxOf{m->val rows=all.filter{it.date.startsWith(m)};maxOf(incomeFor(rows),expensesFor(rows),1L)}
            months.forEach{m->val rows=all.filter{it.date.startsWith(m)};Text(m,style=MaterialTheme.typography.labelSmall);if(rows.isEmpty())Text("Sin datos",style=MaterialTheme.typography.bodySmall)else{Bar(incomeFor(rows),max,Mint);Bar(expensesFor(rows),max,Peach);Text("${money(incomeFor(rows))} / ${money(expensesFor(rows))}",style=MaterialTheme.typography.bodySmall)}}
        }}
        item{Section("Dónde se va tu dinero"){
            val expenses=s.categories.map{it to expenseFor(current,it.id)}.filter{it.second!=0L}.sortedByDescending{it.second};val max=expenses.maxOfOrNull{it.second}?.coerceAtLeast(1)?:1
            if(expenses.isEmpty())Text("No hay gastos en este periodo.")
            expenses.forEach{(c,amount)->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(c.name);Text(money(amount))};Bar(amount,max,Peach)}
        }}
        val alerts=s.budgets.filter{it.accountId==account&&it.month==month}.filter{expenseFor(current,it.categoryId)>=it.limit*0.8}
        items(alerts){b->Section("${if(expenseFor(current,b.categoryId)>=b.limit)"Límite alcanzado"else"Cerca del límite"} · ${s.categories.find{it.id==b.categoryId}?.name}"){Text("${money(expenseFor(current,b.categoryId))} de ${money(b.limit)}")}}
        val unclassified=current.count{it.categoryId=="uncategorized"};if(unclassified>0)item{Text("Tienes $unclassified movimientos sin categoría.",color=Peach)}
    }
}
@Composable private fun Bar(value:Long,max:Long,color:Color){LinearProgressIndicator(progress={ (value.toFloat()/max.coerceAtLeast(1)).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth().height(7.dp),color=color,trackColor=MaterialTheme.colorScheme.surfaceVariant)}
@Composable private fun MovementsScreen(vm:FinanceVm,s:Snapshot,account:String,month:String,importFile:()->Unit){
    var query by remember{mutableStateOf("")};var category by remember{mutableStateOf("all")};var from by remember(month){mutableStateOf(YearMonth.parse(month).atDay(1).toString())};var to by remember(month){mutableStateOf(YearMonth.parse(month).atEndOfMonth().toString())};var edited by remember{mutableStateOf<Movement?>(null)}
    val valid=runCatching{LocalDate.parse(from)<=LocalDate.parse(to)}.getOrDefault(false)
    val rows=remember(s.movements,account,query,category,from,to){s.movements.filter{it.accountId==account && (category=="all"||it.categoryId==category)&&normalized(it.original).contains(normalized(query))&&it.date>=from&&it.date<=to}}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Title("Movimientos");TextButton(onClick=importFile){Text("Importar")}}}
        item{Input("Buscar concepto",query,{query=it});Choice("Categoría",category,listOf("all" to "Todas")+s.categories.map{it.id to it.name}){category=it};Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Box(Modifier.weight(1f)){Input("Desde · AAAA-MM-DD",from,{from=it})};Box(Modifier.weight(1f)){Input("Hasta · AAAA-MM-DD",to,{to=it})}}}
        if(!valid)item{Text("Introduce un intervalo válido en formato AAAA-MM-DD",color=Peach)}
        else{
            item{Text("${rows.size} movimientos · neto ${money(rows.filter{it.nature!="transfer"}.sumOf{it.amount})}",style=MaterialTheme.typography.bodySmall)}
            items(rows,key={it.id}){m->Card(Modifier.fillMaxWidth().clickable{edited=m}){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(m.original,maxLines=2,fontWeight=FontWeight.Medium);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(m.date,style=MaterialTheme.typography.bodySmall);Text(money(m.amount),color=if(m.amount<0)Peach else Mint)};Text("${s.categories.find{it.id==m.categoryId}?.name} ${when(m.nature){"transfer"->"· Transferencia propia";"refund"->"· Devolución";else->""}}",style=MaterialTheme.typography.bodySmall)}}}
            if(rows.isEmpty())item{Text("No hay movimientos con estos filtros.")}
        }
    }
    edited?.let{m->MovementDialog(m,s.categories,{edited=null}){cat,nature,note,rule->vm.editMovement(m,cat,nature,note,rule);edited=null}}
}
@Composable private fun BudgetsScreen(vm:FinanceVm,s:Snapshot,account:String,month:String){
    var add by remember{mutableStateOf(false)};val rows=s.movements.filter{it.accountId==account&&it.date.startsWith(month)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        item{Title("Tus límites")};item{Text("Presupuestos mensuales para la cuenta seleccionada. Se actualizan al importar.")};item{Button(onClick={add=true}){Text("Crear o ajustar límite")}}
        val budgets=s.budgets.filter{it.accountId==account&&it.month==month}
        if(budgets.isEmpty())item{Text("Todavía no hay presupuestos para $month.")}
        items(budgets,key={it.id}){b->val spent=expenseFor(rows,b.categoryId);Section(s.categories.find{it.id==b.categoryId}?.name?:"Categoría") {Text("${money(spent)} / ${money(b.limit)}");Bar(spent,b.limit,if(spent>=b.limit)Peach else Mint);Text(if(spent>b.limit)"Exceso: ${money(spent-b.limit)}"else"Quedan ${money(b.limit-spent)}");TextButton(onClick={vm.deleteBudget(b.id)}){Text("Quitar límite")}}}
    }
    if(add){var category by remember{mutableStateOf("food")};var amount by remember{mutableStateOf("")};FormDialog("Presupuesto · $month",{add=false},{vm.saveBudget(category,month,amount);add=false},runCatching{amountCents(amount)>0}.getOrDefault(false)){Choice("Categoría",category,s.categories.filter{it.kind!="income"}.map{it.id to it.name}){category=it};Input("Límite en euros",amount,{amount=it},true)}}
}
@Composable private fun GoalsScreen(vm:FinanceVm,s:Snapshot){
    var add by remember{mutableStateOf(false)};var goal by remember{mutableStateOf<Goal?>(null)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        item{Title("Algo que merece ahorrar")};item{Text("Asigna ahorro a tus objetivos. Estas aportaciones son un registro personal: no mueven dinero ni reducen tus gastos.")};item{Button(onClick={add=true}){Text("Nuevo objetivo")}}
        if(s.goals.isEmpty())item{Text("Un colchón, un viaje, un proyecto. Empieza por uno.")}
        items(s.goals,key={it.id}){g->val saved=s.contributions.filter{it.goalId==g.id}.sumOf{it.amount};Section(g.name,g.due?.let{"Fecha objetivo: $it"}){Text("${money(saved)} de ${money(g.target)}",style=MaterialTheme.typography.titleLarge);Bar(saved,g.target,Mint);Text("${(saved.toDouble()/g.target*100).toInt()} % asignado");Button(onClick={goal=g}){Text("Añadir o retirar ahorro")};s.contributions.filter{it.goalId==g.id}.take(5).forEach{Text("${it.date} · ${money(it.amount)} ${it.note}",style=MaterialTheme.typography.bodySmall)}}}
    }
    if(add){var name by remember{mutableStateOf("")};var amount by remember{mutableStateOf("")};var due by remember{mutableStateOf("")};val valid=name.isNotBlank()&&runCatching{amountCents(amount)>0&&(due.isBlank()||bankDate(due).isNotBlank())}.getOrDefault(false)
        FormDialog("Nuevo objetivo",{add=false},{vm.addGoal(name,amount,due);add=false},valid){Input("Nombre",name,{name=it});Input("Objetivo en euros",amount,{amount=it},true);Input("Fecha opcional · DD/MM/AAAA",due,{due=it})}}
    goal?.let{g->var amount by remember{mutableStateOf("")};var note by remember{mutableStateOf("")};val saved=s.contributions.filter{it.goalId==g.id}.sumOf{it.amount};val valid=runCatching{val a=amountCents(amount);a!=0L&&saved+a>=0}.getOrDefault(false)
        FormDialog(g.name,{goal=null},{vm.contribute(g,amount,note);goal=null},valid){Text("Cantidad negativa para retirar. Asignado: ${money(saved)}");Input("Importe en euros",amount,{amount=it},true);Input("Nota opcional",note,{note=it})}}
}
@Composable private fun SettingsScreen(vm:FinanceVm,s:Snapshot,backup:()->Unit,restore:()->Unit,reminder:(Boolean)->Unit){
    var form by remember{mutableStateOf("")};var undo by remember{mutableStateOf<ImportBatch?>(null)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        item{Title("A tu manera")}
        item{Section("Copias cifradas","Guarda en este dispositivo o en un USB. Los proveedores de nube del selector son externos a esta app."){Button(onClick=backup){Text("Guardar copia cifrada")};OutlinedButton(onClick=restore){Text("Restaurar copia")};Text("Conserva tu contraseña: no podemos recuperarla. Restaurar reemplaza los datos actuales.",style=MaterialTheme.typography.bodySmall)}}
        item{BankSection(vm,s)}
        item{Section("Organización"){OutlinedButton(onClick={form="account"}){Text("Añadir cuenta")};OutlinedButton(onClick={form="category"}){Text("Añadir categoría")}}}
        item{Section("Recordatorio semanal","Aviso genérico, sin mostrar importes. Android puede retrasarlo."){Row{TextButton(onClick={reminder(true);vm.notice("Solicitud de recordatorio semanal enviada")}){Text("Activar")};TextButton(onClick={reminder(false);vm.notice("Recordatorio desactivado")}){Text("Desactivar")}}}}
        item{Section("Reglas de categoría","Se aplican a nuevas importaciones. Crea una regla al editar un movimiento."){s.rules.forEach{r->Row(verticalAlignment=Alignment.CenterVertically){Text("${r.text} → ${s.categories.find{it.id==r.categoryId}?.name}",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall);TextButton(onClick={vm.deleteRule(r.id)}){Text("Quitar")}}}}}
        item{Text("Importaciones",style=MaterialTheme.typography.titleMedium)}
        items(s.batches,key={it.id}){b->Section(b.filename,"${b.importedAt.take(10)} · ${s.accounts.find{it.id==b.accountId}?.name}"){Text("${b.accepted} guardados · ${b.skipped} omitidos");TextButton(onClick={undo=b}){Text("Deshacer importación")}}}
        item{Text("Versión 0.3.0 · Vista previa de conexión\nLocal con conexión bancaria opcional (solo lectura, a través de tu puente privado). Sin telemetría. Importador verificado con un extracto Excel de Ibercaja.",style=MaterialTheme.typography.bodySmall)}
    }
    if(form.isNotBlank()){var name by remember{mutableStateOf("")};var kind by remember{mutableStateOf("expense")};FormDialog(if(form=="account")"Nueva cuenta"else"Nueva categoría",{form=""},{if(form=="account")vm.addAccount(name)else vm.addCategory(name,kind);form=""},name.isNotBlank()){Input("Nombre",name,{name=it});if(form=="category")Choice("Tipo",kind,listOf("expense" to "Gasto","income" to "Ingreso","both" to "Ambos")){kind=it}}}
    undo?.let{b->AlertDialog(onDismissRequest={undo=null},title={Text("¿Deshacer esta importación?")},text={Text("Se eliminarán los movimientos de ${b.filename}, incluidas sus categorías y notas modificadas. Los objetivos se mantienen.")},confirmButton={TextButton(onClick={vm.undoBatch(b.id);undo=null}){Text("Deshacer")}},dismissButton={TextButton(onClick={undo=null}){Text("Cancelar")}})}
}
@Composable private fun BankSection(vm:FinanceVm,s:Snapshot){
    val url by vm.bridgeUrl.collectAsStateWithLifecycle();val status by vm.bridgeStatus.collectAsStateWithLifecycle();val accounts by vm.bridgeAccounts.collectAsStateWithLifecycle();val busy by vm.busy.collectAsStateWithLifecycle()
    var editing by remember{mutableStateOf(false)};var newAccount by remember{mutableStateOf<BridgeAccount?>(null)};var revoke by remember{mutableStateOf(false)};var forget by remember{mutableStateOf(false)}
    Section("Conexión bancaria · vista previa","Opcional y de solo lectura: Ibercaja vía Enable Banking, a través de tu puente privado. La app no guarda tu contraseña del banco ni claves del proveedor."){
        if(url==null||editing){
            var address by remember{mutableStateOf(url?:"")};var token by remember{mutableStateOf("")}
            val valid=runCatching{bridgeBaseUrl(address);bridgeToken(token)}.isSuccess
            Input("URL del puente · https://",address,{address=it})
            OutlinedTextField(token,{token=it},label={Text("Token de emparejamiento")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
            Text("Solo HTTPS. El token (mínimo 32 caracteres) lo genera tu puente; no es la contraseña del banco. Se guarda cifrado y fuera de las copias.",style=MaterialTheme.typography.bodySmall)
            Row{Button(onClick={vm.saveBridge(address,token);token="";editing=false},enabled=valid&&!busy){Text("Guardar")};if(url!=null)TextButton(onClick={editing=false}){Text("Cancelar")}}
        }else{
            Text("Puente: $url",style=MaterialTheme.typography.bodySmall)
            status?.let{st->
                Text("Modo ${if(st.environment=="PRODUCTION")"PRODUCCIÓN (banco real)" else if(st.environment=="SANDBOX")"SANDBOX (datos de prueba)" else st.environment}",fontWeight=FontWeight.SemiBold,color=if(st.environment=="PRODUCTION")Peach else Mint)
                Text(if(!st.configured)"Proveedor sin configurar en el puente" else if(st.connected)"Sesión autorizada en el puente${st.validUntil?.let{" hasta ${it.take(10)}"}?:""}" else "Sin sesión autorizada",style=MaterialTheme.typography.bodySmall)
            }
            Text("«Sesión autorizada» es el estado guardado en tu puente, no una comprobación en vivo con el banco.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick=vm::testBridge,enabled=!busy){Text("Probar puente")}
            Button(onClick=vm::connectBank,enabled=!busy){Text("Conectar Ibercaja")}
            Text("Se abrirá el navegador en auth.enablebanking.com. Al terminar, vuelve y pulsa «Actualizar conexión».",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick=vm::refreshBank,enabled=!busy){Text("Actualizar conexión")}
            accounts.forEach{a->val local=s.accounts.find{it.id==BankSync.accountId(a.key)}
                Row(verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){Text(a.label);Text(if(a.currency!="EUR")"Moneda ${a.currency} no admitida" else local?.let{"Cuenta local: ${it.name}"}?:"Aún sin cuenta local",style=MaterialTheme.typography.bodySmall)}
                    TextButton(onClick={if(local!=null)vm.syncBank(a.key)else newAccount=a},enabled=!busy&&a.currency=="EUR"&&status?.connected==true){Text("Sincronizar")}
                }
            }
            Row{TextButton(onClick={revoke=true},enabled=!busy){Text("Revocar conexión")};TextButton(onClick={editing=true},enabled=!busy){Text("Cambiar puente")};TextButton(onClick={forget=true},enabled=!busy){Text("Olvidar")}}
        }
    }
    newAccount?.let{a->var accepted by remember{mutableStateOf(false)};val env=status?.environment?:"desconocido"
        FormDialog("Primera sincronización",{newAccount=null},{vm.syncBank(a.key);newAccount=null},accepted&&!busy){
            Text("Modo: ${if(env=="PRODUCTION")"PRODUCCIÓN (banco real)" else if(env=="SANDBOX")"SANDBOX (datos de prueba, no son tus movimientos)" else env}",fontWeight=FontWeight.SemiBold,color=if(env=="PRODUCTION")Peach else Mint)
            Text("Se creará una cuenta local NUEVA «${a.label}», separada de tus cuentas manuales y de las importaciones CSV/Excel. No se fusionan automáticamente: si ya importaste esos meses por archivo, aparecerán también en esta cuenta y se contarán dos veces si las sumas.")
            Text("Se descargan solo movimientos contabilizados en EUR de los últimos 90 días. Las sincronizaciones posteriores añaden únicamente movimientos nuevos y conservan tus categorías y notas. Marca a mano las transferencias propias.",style=MaterialTheme.typography.bodySmall)
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(accepted,{accepted=it});Text("Entendido: crear cuenta nueva")}
        }}
    if(revoke)AlertDialog(onDismissRequest={revoke=false},title={Text("¿Revocar la conexión?")},text={Text("El puente revocará la sesión con el proveedor. Los movimientos ya importados se conservan. Si falla, podrás reintentarlo.")},confirmButton={TextButton(onClick={vm.revokeBank();revoke=false}){Text("Revocar")}},dismissButton={TextButton(onClick={revoke=false}){Text("Cancelar")}})
    if(forget)AlertDialog(onDismissRequest={forget=false},title={Text("¿Olvidar el puente?")},text={Text("Se borran la URL y el token de este móvil. No revoca la sesión: hazlo antes con «Revocar conexión» si quieres cortarla.")},confirmButton={TextButton(onClick={vm.forgetBridge();forget=false}){Text("Olvidar")}},dismissButton={TextButton(onClick={forget=false}){Text("Cancelar")}})
}
@Composable private fun MovementDialog(m:Movement,categories:List<Category>,close:()->Unit,save:(String,String,String,String)->Unit){
    var category by remember{mutableStateOf(m.categoryId)};var nature by remember{mutableStateOf(m.nature)};var note by remember{mutableStateOf(m.note)};var rule by remember{mutableStateOf("")}
    FormDialog("Editar movimiento",close,{save(category,nature,note,rule)},nature!="refund"||m.amount>0){Text(m.original);Text("${m.date} · ${money(m.amount)}");Choice("Categoría",category,categories.map{it.id to it.name}){category=it};Choice("Tratamiento",nature,listOf("normal" to "Ingreso o gasto","transfer" to "Transferencia propia","refund" to "Devolución (abono)")){nature=it};Input("Nota",note,{note=it});Input("Regla opcional: concepto contiene…",rule,{rule=it});Text("La regla clasificará futuras importaciones. Transferencias propias quedan fuera de ingresos y gastos.",style=MaterialTheme.typography.bodySmall)}
}
@Composable private fun MappingDialog(t:ImportTable,close:()->Unit,submit:(ColumnMap,Int)->Unit){
    var header by remember{mutableIntStateOf(t.headerRow)};var map by remember{mutableStateOf(t.mapping)}
    val columns=listOf("-1" to "No asignada")+t.rows[header].mapIndexed{i,name->i.toString() to "${i+1}. ${name.ifBlank{"(vacía)"}.take(50)}"}
    FormDialog("Revisar columnas",close,{submit(map,header)},map.date>=0&&map.concept>=0&&(map.amount>=0||map.debit>=0||map.credit>=0)){
        Text(t.name);Text("Comprueba especialmente el signo: cargos negativos, abonos positivos. XLS/XLSX: una hoja por archivo.",style=MaterialTheme.typography.bodySmall)
        Choice("Fila de cabecera",header.toString(),t.rows.take(40).mapIndexed{i,row->i.toString() to "Fila ${i+1}: ${row.joinToString(" · ").take(65)}"}){header=it.toInt();map=BankImporter.detect(t.rows[header])}
        listOf("Fecha" to map.date,"Concepto" to map.concept,"Importe firmado" to map.amount,"Debe (si no hay importe)" to map.debit,"Haber (si no hay importe)" to map.credit,"Fecha valor" to map.valueDate,"Referencia" to map.reference,"Saldo posterior" to map.balance,"Descripción adicional" to map.description).forEachIndexed{i,(label,value)->Choice(label,value.toString(),columns){raw->val v=raw.toInt();map=when(i){0->map.copy(date=v);1->map.copy(concept=v);2->map.copy(amount=v);3->map.copy(debit=v);4->map.copy(credit=v);5->map.copy(valueDate=v);6->map.copy(reference=v);7->map.copy(balance=v);else->map.copy(description=v)}}}
    }
}
@Composable private fun PreviewDialog(p:ImportPreview,busy:Boolean,toggle:(Int)->Unit,close:()->Unit,commit:()->Unit){
    var acknowledge by remember{mutableStateOf(false)}
    Dialog(onDismissRequest={if(!busy)close()}){Surface(shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(18.dp).fillMaxHeight(0.9f)){
        Text("Revisar importación",style=MaterialTheme.typography.titleLarge)
        Text("${p.rows.count{it.selected}} seleccionados · ${p.rows.count{it.duplicate}} posibles duplicados · ${p.rows.count{it.error!=null}} errores",style=MaterialTheme.typography.bodySmall)
        Text("Los duplicados están desmarcados. Puedes incluirlos si son operaciones distintas.",style=MaterialTheme.typography.bodySmall,color=Peach)
        LazyColumn(Modifier.weight(1f)){items(p.rows.size){i->val r=p.rows[i];Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Checkbox(r.selected,{toggle(i)},enabled=r.movement!=null&&!busy);Column(Modifier.weight(1f)){Text("Fila ${r.row}${if(r.duplicate)" · posible duplicado"else""}",style=MaterialTheme.typography.labelSmall);if(r.error!=null)Text(r.error,color=Peach)else r.movement?.let{Text(it.original,maxLines=2);Text("${it.date} · ${money(it.amount)}",color=if(it.amount<0)Peach else Mint)}}}}}
        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(acknowledge,{acknowledge=it});Text("He revisado signos, duplicados y filas omitidas.",style=MaterialTheme.typography.bodySmall)}
        Row{TextButton(onClick=close,enabled=!busy){Text("Cancelar")};Button(onClick=commit,enabled=acknowledge&&!busy&&p.rows.any{it.selected}){Text("Importar")}}
    }}}
}
@Composable private fun BackupDialog(restore:Boolean,busy:Boolean,close:()->Unit,submit:(CharArray)->Unit){
    var password by remember{mutableStateOf("")};var repeat by remember{mutableStateOf("")};var accepted by remember{mutableStateOf(false)}
    FormDialog(if(restore)"Restaurar copia"else"Proteger copia",close,{submit(password.toCharArray());password="";repeat=""},!busy&&password.length>=12&&(restore||password==repeat)&&(!restore||accepted)){
        Text(if(restore)"Los datos actuales se reemplazarán solo después de validar la copia. Esta operación no se puede deshacer sin otra copia."else"Contraseña de al menos 12 caracteres. La necesitarás para recuperar tus datos en otro móvil.")
        OutlinedTextField(password,{password=it},label={Text("Contraseña")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
        if(!restore)OutlinedTextField(repeat,{repeat=it},label={Text("Repetir contraseña")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())
        if(restore)Row(verticalAlignment=Alignment.CenterVertically){Checkbox(accepted,{accepted=it});Text("Reemplazar mis datos actuales")}
    }
}
@Composable private fun FormDialog(title:String,close:()->Unit,save:()->Unit,enabled:Boolean=true,content:@Composable ColumnScope.()->Unit){
    AlertDialog(onDismissRequest=close,title={Text(title)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)},confirmButton={TextButton(onClick=save,enabled=enabled){Text("Continuar")}},dismissButton={TextButton(onClick=close){Text("Cancelar")}})
}
@Composable private fun Input(label:String,value:String,change:(String)->Unit,numeric:Boolean=false){OutlinedTextField(value,change,label={Text(label)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=if(numeric)KeyboardType.Decimal else KeyboardType.Text),modifier=Modifier.fillMaxWidth())}
@Composable private fun Choice(label:String,value:String,options:List<Pair<String,String>>,change:(String)->Unit){
    var expanded by remember{mutableStateOf(false)}
    Box{OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Column(Modifier.fillMaxWidth()){Text(label,style=MaterialTheme.typography.labelSmall);Text(options.find{it.first==value}?.second?:"Seleccionar",maxLines=2)}};DropdownMenu(expanded,{expanded=false},modifier=Modifier.heightIn(max=350.dp)){options.forEach{(id,name)->DropdownMenuItem(text={Text(name)},onClick={change(id);expanded=false})}}}
}
