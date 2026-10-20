> **Documento histórico de la versión indicada.** Desde 0.3.0 hay INTERNET y un puente bancario opcional. No acredita compilación, seguridad o conexión live de 0.3.0; consulta `../README.md` y `open-banking/CONTRATO_APP.md`.

# Vidal Finanzas — arquitectura v0.1.0

APK privada para Android 11 o posterior. Kotlin, Jetpack Compose, Room, SQLCipher, Android Keystore y biometría/credencial del dispositivo. EUR exclusivamente.

## Organización
- `Models.kt`: entidades, importes exactos, normalización, reglas y cálculos.
- `Database.kt`: Room, DAO y esquema exportado.
- `Importer.kt`: lectura acotada CSV/XLS/XLSX, asignación de columnas, revisión y duplicados.
- `Security.kt`: clave aleatoria SQLCipher envuelta con AES-GCM de Keystore; copia portátil cifrada.
- `FinanceVm.kt`: estado observable, acceso a datos serializado, transacciones y operaciones de archivo en Dispatchers.IO.
- `Ui.kt`: Inicio, Historial, Límites, Objetivos y Ajustes; formularios de revisión.
- `MainActivity.kt`: autenticación del sistema, selectores de archivo, protección de pantalla.
- `Reminder.kt`: recordatorio local semanal sin leer datos financieros bloqueados.

Un solo módulo deliberadamente. No backend, APIs bancarias, acceso a SMS ni permiso INTERNET. WorkManager únicamente ejecuta un aviso genérico. Las operaciones de importación y restauración son transaccionales. Una generación de sesión impide repoblar el estado visual tras bloquear la aplicación durante una operación en curso.

## Semántica financiera
Importes Long en céntimos. Fechas LocalDate sin conversiones de zona horaria. Los filtros son inclusivos. Las transferencias propias se excluyen de ingresos, gastos y presupuestos; las devoluciones positivas reducen el gasto de su categoría. El usuario identifica estos tratamientos. La app no deduce automáticamente que toda transferencia es propia.

Los presupuestos son por cuenta/categoría/mes. Las aportaciones a objetivos son apuntes virtuales globales independientes, no movimientos bancarios. No garantizan dinero reservado y no se reconcilian con saldo. El balance mensual es ingresos menos gastos netos, no saldo de cuenta. El saldo del extracto se conserva como dato de origen pero no se presenta como saldo actual.

## Importación
- Límite de archivo: 20 MiB; 50.000 filas; 100 columnas; 10.000 caracteres/campo.
- CSV UTF-8 o Windows-1252; delimitadores coma, punto y coma o tabulación. Comillas y saltos internos.
- XLS por JExcelAPI; XLSX por ZIP/XML Pull. Una sola hoja por archivo en esta versión. XLSX: expansión máxima 40 MiB, 500 entradas, rechazo de DTD y macros. No evalúa fórmulas.
- Detecta cabecera dentro de las primeras 40 filas no vacías. Permite elegir cabecera y mapear columnas.
- Fecha, concepto e importe firmado (o debe/haber) necesarios. Fecha valor, referencia y saldo opcionales.
- Fechas DD/MM/AAAA, DD-MM-AAAA o ISO; importes con coma española o decimal de punto inequívoco. Rechaza 1.234 por ambiguo.
- Cada fila inválida se muestra y queda fuera; confirmar exige reconocer signos, duplicados y omisiones.
- Duplicados por referencia si está presente; alternativamente huella de cuenta/fecha/importe/concepto normalizado y multiplicidad. Revisión manual disponible. No presupone que una coincidencia es definitiva.
- Conserva nombre/huella del archivo, fecha y conteos del lote; no almacena el extracto completo.
- Deshacer lote elimina exclusivamente sus movimientos, incluidas ediciones posteriores. Confirmación explícita.

## Seguridad y recuperación
Base de datos en sandbox privado. Clave aleatoria de 32 bytes. Envoltura AES-256-GCM mediante clave Android Keystore no exportable, con autenticación del dispositivo reciente (60 segundos). Biometría fuerte o credencial del dispositivo. No exige StrongBox. Cierre y descarte del estado al pasar a segundo plano; FLAG_SECURE impide capturas/recientes.

La clave de base de datos está en memoria durante el uso. El cierre libera referencias, sin garantía de borrado completo del heap de JVM. No protege frente a un SO comprometido ni frente a alguien con la credencial del dispositivo.

Copias automáticas y extracción del sistema excluidas explícitamente. Copias manuales `.vfin`: cabecera VFIN001, sal aleatoria 16 bytes, IV 12 bytes, PBKDF2-HMAC-SHA256 600.000 iteraciones, AES-256-GCM; cabecera y sal autenticadas. Contraseña mínima 12 caracteres. Sin contraseña no hay recuperación. Validación del esquema y relaciones antes de reemplazar la base dentro de una transacción. La copia no depende del Keystore del móvil original.

El selector del sistema puede mostrar proveedores externos (Drive, etc.). La app no los integra; para el uso 100% local elegir almacenamiento interno o USB. Los extractos descargados y copias elegidas fuera del sandbox no están controlados por la app. Una copia local no protege frente a la pérdida del teléfono: conservar otra copia cifrada en un USB.

Si el Keystore se invalida, no se borra automáticamente la base. Recuperar una copia en una instalación nueva; la restauración de un vault inaccesible no se implementa como reparación destructiva automática.

## Límites explícitos de esta versión
- Adaptador genérico en español; no validado con un extracto real de Ibercaja.
- El mapeo de columnas se revisa en cada importación; no hay perfiles persistentes todavía.
- XLSX/XLS multihoja: hay que exportar la hoja de movimientos separada.
- Reglas locales por texto contenido, aplicadas a futuras importaciones. No hay IA ni recategorización histórica en lote.
- No hay entrada manual de movimientos, división de compras, divisas, conciliación de tarjetas ni sincronización.
- Recordatorio semanal aproximado; avisos de presupuesto dentro de Inicio. No alerta bancaria en tiempo real.
- Las entidades no usan claves foráneas SQL en esta primera versión; las operaciones permitidas y la restauración validan relaciones en la capa de aplicación. Antes de habilitar borrado de cuentas/categorías añadir FK y migración.
- No se ha implementado migración de versiones porque este es el esquema 1. No hay fallbackToDestructiveMigration.

## Próximos pasos
1. Validar extractos anonimizados de Ibercaja, incluido un solapamiento y movimientos iguales.
2. Validar autenticación/Keystore, selector y copia-restauración en el S25 Ultra real.
3. Perfiles de importación persistentes y selección de hojas.
4. Gestión de recurrentes, edición de objetivos y recategorización histórica con revisión.
5. Antes de ampliar el uso: migraciones, FK, tests instrumentados y revisión de dependencias (especialmente el lector XLS heredado).
