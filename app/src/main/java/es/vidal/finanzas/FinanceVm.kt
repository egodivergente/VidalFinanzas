package es.vidal.finanzas

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.Instant
import java.time.YearMonth

class FinanceVm(app: Application): AndroidViewModel(app) {
    private val mutex=Mutex(); private var db:FinanceDb?=null;@Volatile private var epoch=0; @Volatile private var dbEpoch = -1
    private val _snapshot=MutableStateFlow<Snapshot?>(null);val snapshot=_snapshot.asStateFlow()
    val busy=MutableStateFlow(false);val message=MutableStateFlow<String?>(null)
    val table=MutableStateFlow<ImportTable?>(null);val preview=MutableStateFlow<ImportPreview?>(null)
    val selectedAccount=MutableStateFlow("")
    val pending=MutableStateFlow<Pair<String,Uri>?>(null)
    val bridgeUrl=MutableStateFlow<String?>(null);val bridgeStatus=MutableStateFlow<BridgeStatus?>(null)
    val bridgeAccounts=MutableStateFlow<List<BridgeAccount>>(emptyList());val authorizationUrl=MutableStateFlow<String?>(null)
    private val json=Json { encodeDefaults=true }
    fun notice(text:String){message.value=text}
    private fun task(block:suspend ()->Unit) {
        if(busy.value)return
        busy.value=true
        val generation=epoch
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { mutex.withLock { if(generation==epoch)block() } } }
            catch(e:Exception) { if(e is CancellationException)throw e;message.value=when(e){is javax.crypto.AEADBadTagException->"Contraseña incorrecta o copia dañada";else->e.message?.take(180) ?: "No se pudo completar la operación"} }
            finally{busy.value=false}
        }
    }
    fun unlock()=task {
        val generation=epoch
        val opened=Vault.open(getApplication())
        if(generation!=epoch){opened.close();return@task}
        db=opened; dbEpoch=generation
        bridgeUrl.value=runCatching{BridgeStore.load(getApplication())?.url}.getOrNull()
        opened.withTransaction {
            if(opened.dao().accounts().isEmpty()) {
                opened.dao().insertAccounts(listOf(Account(name="Ibercaja principal")))
                opened.dao().insertCategories(initialCategories());opened.dao().insertRules(initialRules())
            }
        }
        refresh()
    }
    private var backgroundLock: Job? = null
    private var backgroundSince: Long? = null
    fun enterBackground() {
        backgroundLock?.cancel()
        backgroundSince = android.os.SystemClock.elapsedRealtime()
        backgroundLock = viewModelScope.launch { delay(60_000); lock() }
    }
    fun enterForeground() {
        backgroundLock?.cancel(); backgroundLock = null
        val since = backgroundSince
        backgroundSince = null
        if(since != null && android.os.SystemClock.elapsedRealtime() - since >= 60_000) lock()
    }
    fun lock() {
        backgroundLock?.cancel(); backgroundLock = null; backgroundSince = null
        epoch++; dbEpoch=-1
        _snapshot.value=null;table.value=null;preview.value=null;message.value=null
        bridgeUrl.value=null;bridgeStatus.value=null;bridgeAccounts.value=emptyList();authorizationUrl.value=null
        viewModelScope.launch(Dispatchers.IO){mutex.withLock{db?.close();db=null}}
    }
    private suspend fun refresh() {
        val generation=epoch
        val s=checkNotNull(db){"Desbloquea la aplicación"}.dao().snapshot()
        if(generation==epoch && dbEpoch==epoch){_snapshot.value=s;if(s.accounts.none{it.id==selectedAccount.value})selectedAccount.value=s.accounts.first().id}
    }
    private fun ensureCurrent(generation:Int)=check(generation==epoch&&dbEpoch==epoch){"La app se bloqueó durante la operación; no se ha guardado nada"}
    private fun change(block:suspend (FinanceDb,FinanceDao)->Unit)=task {
        val generation=epoch
        val d=checkNotNull(db){"Desbloquea la aplicación"}
        d.withTransaction{ensureCurrent(generation);block(d,d.dao())};refresh()
    }
    fun readImport(uri:Uri)=task {
        val generation=epoch
        val resolver=getApplication<Application>().contentResolver
        val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else"Extracto"}?:"Extracto"
        val bytes=resolver.openInputStream(uri)?.use{it.limitedBytes()}?:error("No se pudo leer el archivo")
        try{val parsed=BankImporter.read(name,bytes);if(generation==epoch)table.value=parsed}finally{bytes.fill(0)}
    }
    fun mapImport(mapping:ColumnMap,headerRow:Int)=task {
        val generation=epoch;val s=checkNotNull(_snapshot.value);val t=checkNotNull(table.value).copy(headerRow=headerRow)
        val p=BankImporter.preview(t,mapping,selectedAccount.value,s.movements,s.rules)
        if(generation==epoch){preview.value=p;table.value=null}
    }
    fun toggleRow(index:Int){preview.value=preview.value?.let{p->p.copy(rows=p.rows.mapIndexed{i,r->if(i==index && r.movement!=null)r.copy(selected=!r.selected)else r})}}
    fun cancelImport(){table.value=null;preview.value=null}
    fun commitImport()=change { _,dao ->
        val p=checkNotNull(preview.value);val rows=p.rows.filter{it.selected&&it.movement!=null};require(rows.isNotEmpty()){ "Selecciona al menos un movimiento" }
        val batch=ImportBatch(accountId=p.accountId,filename=p.table.name,hash=p.table.hash,importedAt=Instant.now().toString(),accepted=rows.size,skipped=p.rows.size-rows.size)
        dao.insertBatches(listOf(batch));dao.insertMovements(rows.map{it.movement!!.copy(batchId=batch.id)})
        preview.value=null;message.value="Importados ${rows.size} movimientos. Revisa las categorías."
    }
    fun editMovement(m:Movement,category:String,nature:String,note:String,ruleText:String)=change{_,dao->
        require(nature!="refund"||m.amount>0){"Una devolución debe ser un abono"}
        dao.updateMovement(m.copy(categoryId=category,nature=nature,note=note,manualCategory=true))
        if(ruleText.isNotBlank())dao.insertRules(listOf(Rule(text=normalized(ruleText),categoryId=category,priority=(dao.rules().maxOfOrNull{it.priority}?:100)+1)))
    }
    fun addCategory(name:String,kind:String)=change{_,dao->require(name.isNotBlank());dao.insertCategories(listOf(Category(uid(),name.trim(),kind)))}
    fun addAccount(name:String)=change{_,dao->require(name.isNotBlank());val a=Account(name=name.trim());dao.insertAccounts(listOf(a));selectedAccount.value=a.id}
    fun saveBudget(category:String,month:String,amount:String)=change{_,dao->
        YearMonth.parse(month);val cents=amountCents(amount);require(cents>0){"El límite debe ser positivo"}
        val old=dao.budgets().firstOrNull{it.accountId==selectedAccount.value&&it.categoryId==category&&it.month==month}
        dao.saveBudget(Budget(old?.id?:uid(),selectedAccount.value,category,month,cents))
    }
    fun deleteBudget(id:String)=change{_,dao->dao.deleteBudget(id)}
    fun addGoal(name:String,target:String,due:String)=change{_,dao->
        val cents=amountCents(target);require(cents>0&&name.isNotBlank()){ "Indica un nombre y una cantidad positiva" }
        dao.insertGoals(listOf(Goal(name=name.trim(),target=cents,due=due.takeIf{it.isNotBlank()}?.let(::bankDate))))
    }
    fun contribute(goal:Goal,amount:String,note:String)=change{_,dao->
        val cents=amountCents(amount);require(cents!=0L)
        require(dao.contributions().filter{it.goalId==goal.id}.sumOf{it.amount}+cents>=0){"No puedes retirar más de lo asignado"}
        dao.insertContributions(listOf(Contribution(goalId=goal.id,amount=cents,date=LocalDate.now().toString(),note=note)))
    }
    fun deleteRule(id:String)=change{_,dao->dao.deleteRule(id)}
    fun undoBatch(id:String)=change{_,dao->dao.undoMovements(id);dao.undoBatch(id);message.value="Importación deshecha"}
    fun exportBackup(uri:Uri,password:CharArray)=task {
        var plain:ByteArray?=null
        try {
            plain=json.encodeToString(checkNotNull(db).dao().snapshot()).toByteArray()
            val encrypted=BackupCrypto.encrypt(plain,password)
            getApplication<Application>().contentResolver.openOutputStream(uri,"wt")?.use{it.write(encrypted)}?:error("No se pudo escribir la copia")
            message.value="Copia cifrada guardada. Conserva la contraseña fuera del móvil."
        } finally{plain?.fill(0);password.fill('\u0000')}
    }
    fun restoreBackup(uri:Uri,password:CharArray)=task {
        val generation=epoch
        var plain:ByteArray?=null
        try {
            val bytes=getApplication<Application>().contentResolver.openInputStream(uri)?.use{it.limitedBytes(40*1024*1024)}?:error("No se pudo leer la copia")
            plain=BackupCrypto.decrypt(bytes,password)
            val restored=json.decodeFromString<Snapshot>(plain.toString(Charsets.UTF_8));validateSnapshot(restored)
            val d=checkNotNull(db)
            d.withTransaction {
                ensureCurrent(generation)
                val dao=d.dao();dao.clearMovements();dao.clearContributions();dao.clearGoals();dao.clearBudgets();dao.clearRules();dao.clearBatches();dao.clearCategories();dao.clearAccounts()
                dao.insertAccounts(restored.accounts);dao.insertCategories(restored.categories);dao.insertBatches(restored.batches);dao.insertMovements(restored.movements);dao.insertRules(restored.rules);dao.insertBudgets(restored.budgets);dao.insertGoals(restored.goals);dao.insertContributions(restored.contributions)
            };refresh();message.value="Copia restaurada"
        }finally{plain?.fill(0);password.fill('\u0000')}
    }

    private fun bridge():BridgeClient {
        checkNotNull(db){"Desbloquea la aplicación"}
        return BridgeClient(BridgeStore.load(getApplication())?:throw BridgeException("Configura primero el puente"))
    }
    fun saveBridge(url:String,token:String)=task {
        checkNotNull(db){"Desbloquea la aplicación"}
        val config=BridgeConfig(bridgeBaseUrl(url),bridgeToken(token))
        BridgeStore.save(getApplication(),config)
        bridgeUrl.value=config.url;bridgeStatus.value=null;bridgeAccounts.value=emptyList()
        message.value="Puente guardado en este móvil. Pulsa «Probar puente»."
    }
    fun forgetBridge()=task {
        checkNotNull(db){"Desbloquea la aplicación"}
        BridgeStore.clear(getApplication())
        bridgeUrl.value=null;bridgeStatus.value=null;bridgeAccounts.value=emptyList()
        message.value="Configuración del puente borrada de este móvil. Esto no revoca la sesión en el puente."
    }
    fun testBridge()=task {
        val generation=epoch;val status=bridge().status()
        if(generation==epoch){bridgeStatus.value=status;message.value="Puente ${status.environment}: ${if(!status.configured)"proveedor sin configurar" else if(status.connected)"sesión autorizada" else "sin sesión autorizada"}"}
    }
    fun connectBank()=task {
        val generation=epoch;val url=bridge().connect()
        if(generation==epoch)authorizationUrl.value=url
    }
    fun refreshBank()=task {
        val generation=epoch;val client=bridge();val status=client.status()
        val accounts=if(status.connected)client.accounts().accounts else emptyList()
        if(generation==epoch){bridgeStatus.value=status;bridgeAccounts.value=accounts;message.value=if(status.connected)"${accounts.size} cuentas disponibles para sincronizar" else if(status.validUntil!=null)"La conexión ha caducado. Pulsa «Revocar conexión» antes de volver a conectar." else "Sin sesión autorizada. Pulsa «Conectar Ibercaja»."}
    }
    fun syncBank(key:String)=task {
        val generation=epoch
        val d=checkNotNull(db){"Desbloquea la aplicación"}
        val response=bridge().sync(key)
        ensureCurrent(generation)
        val batch=BankSync.normalize(response,key,d.dao().rules())
        val added=importBankBatch(d,generation,batch)
        refresh()
        if(generation==epoch){selectedAccount.value=batch.account.id;message.value="${batch.account.name}: $added nuevos, ${batch.movements.size-added} ya estaban (${batch.dateFrom} a ${batch.dateTo})."}
    }
    private suspend fun importBankBatch(d:FinanceDb,generation:Int,batch:BankBatch):Int=d.withTransaction {
        ensureCurrent(generation)
        val dao=d.dao()
        val fresh=BankSync.plan(batch,dao.movements())
        val missingAccount=dao.accounts().none{it.id==batch.account.id}
        ensureCurrent(generation)
        if(missingAccount)dao.insertAccounts(listOf(batch.account))
        if(fresh.isNotEmpty())dao.insertMovements(fresh)
        ensureCurrent(generation)
        fresh.size
    }
    fun revokeBank()=task {
        val generation=epoch;bridge().revoke()
        if(generation==epoch){bridgeStatus.value=null;bridgeAccounts.value=emptyList();message.value="Conexión revocada en el puente. Los movimientos ya importados se conservan."}
    }
}
