package es.vidal.finanzas
import androidx.room.*

@Dao
interface FinanceDao {
    @Query("SELECT * FROM accounts") suspend fun accounts(): List<Account>
    @Query("SELECT * FROM categories") suspend fun categories(): List<Category>
    @Query("SELECT * FROM movements ORDER BY date DESC, rowid DESC") suspend fun movements(): List<Movement>
    @Query("SELECT * FROM rules ORDER BY priority DESC") suspend fun rules(): List<Rule>
    @Query("SELECT * FROM budgets") suspend fun budgets(): List<Budget>
    @Query("SELECT * FROM goals") suspend fun goals(): List<Goal>
    @Query("SELECT * FROM contributions ORDER BY date DESC") suspend fun contributions(): List<Contribution>
    @Query("SELECT * FROM batches ORDER BY importedAt DESC") suspend fun batches(): List<ImportBatch>
    @Insert suspend fun insertAccounts(items: List<Account>)
    @Insert suspend fun insertCategories(items: List<Category>)
    @Insert suspend fun insertMovements(items: List<Movement>)
    @Insert suspend fun insertRules(items: List<Rule>)
    @Upsert suspend fun saveBudget(item: Budget)
    @Insert suspend fun insertBudgets(items: List<Budget>)
    @Insert suspend fun insertGoals(items: List<Goal>)
    @Insert suspend fun insertContributions(items: List<Contribution>)
    @Insert suspend fun insertBatches(items: List<ImportBatch>)
    @Update suspend fun updateMovement(item: Movement)
    @Query("DELETE FROM budgets WHERE id = :id") suspend fun deleteBudget(id: String)
    @Query("DELETE FROM rules WHERE id = :id") suspend fun deleteRule(id: String)
    @Query("DELETE FROM movements WHERE batchId = :id") suspend fun undoMovements(id: String)
    @Query("DELETE FROM batches WHERE id = :id") suspend fun undoBatch(id: String)
    @Query("DELETE FROM movements") suspend fun clearMovements()
    @Query("DELETE FROM contributions") suspend fun clearContributions()
    @Query("DELETE FROM goals") suspend fun clearGoals()
    @Query("DELETE FROM budgets") suspend fun clearBudgets()
    @Query("DELETE FROM rules") suspend fun clearRules()
    @Query("DELETE FROM batches") suspend fun clearBatches()
    @Query("DELETE FROM categories") suspend fun clearCategories()
    @Query("DELETE FROM accounts") suspend fun clearAccounts()
}
@Database(entities=[Account::class,Category::class,Movement::class,Rule::class,Budget::class,Goal::class,Contribution::class,ImportBatch::class],version=1,exportSchema=true)
abstract class FinanceDb: RoomDatabase() { abstract fun dao(): FinanceDao }
suspend fun FinanceDao.snapshot() = Snapshot(accounts=accounts(),categories=categories(),movements=movements(),rules=rules(),budgets=budgets(),goals=goals(),contributions=contributions(),batches=batches())
