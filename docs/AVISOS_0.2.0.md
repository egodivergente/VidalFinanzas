> **Documento histórico de la versión indicada.** Desde 0.3.0 hay INTERNET y un puente bancario opcional. No acredita compilación, seguridad o conexión live de 0.3.0; consulta `../README.md` y `open-banking/CONTRATO_APP.md`.

# Avisos locales — versión experimental 0.2.0

## Activar
1. Actualizar sin desinstalar.
2. Abrir Avisos automáticos > Configurar. Asignar la cuenta seleccionada.
3. Buscar y seleccionar las aplicaciones instaladas Ibercaja y/o Wallet. No se capturan fuentes sin seleccionar.
4. Abrir el acceso a notificaciones de Android, concederlo manualmente y volver. Comprobar permiso y activar captura.
5. Revisar una notificación real nueva. Si Android limita el acceso o no entrega su contenido, la app no puede capturarlo. No se recuperan notificaciones antiguas.

## Comportamiento
- Bandeja automática de posibles avisos financieros de las fuentes elegidas: pagos, recibos, ingresos, nóminas, transferencias y devoluciones.
- EUR únicamente. Importes múltiples, dirección desconocida, cargos rechazados o futuros se muestran para revisión sin inventar un importe firmado.
- Las plantillas son genéricas. Falta validar con notificaciones reales de Ibercaja y Wallet. Se puede corregir manualmente el importe y el signo.
- Categoría sugerida por reglas locales. Los avisos provisionales no alteran el saldo ni presupuestos confirmados.
- Reentregas exactas del mismo aviso no duplican la bandeja; avisos de importe igual y cercanos en tiempo se señalan como posibles repetidos. No se fusionan por importe solamente.
- Tras importar el banco, Conciliar ofrece movimientos de la misma cuenta e importe y fecha ±3 días. El usuario confirma el concepto. Conciliar vincula el aviso; no crea otro movimiento.
- Descartar es reversible; eliminar revisados requiere confirmación y no elimina movimientos bancarios.

## Privacidad y límites
- Sin INTERNET, sin claves bancarias, sin telemetría. Android concede acceso amplio a notificaciones; la app filtra las fuentes antes de leer contenido.
- Se excluyen avisos detectados como códigos, contraseñas o autenticación. La detección textual no garantiza reconocer toda plantilla futura: seleccionar solo las aplicaciones necesarias.
- Bandeja AES-GCM con clave Android Keystore separada de la base financiera. Esta clave no exige biometría para permitir capturar con la app bloqueada; el acceso a la interfaz sigue protegido por el desbloqueo de la app. No se debilita la clave de SQLCipher.
- Archivos internos noBackup, sin incluir la bandeja en las copias financieras. La bandeja admite 500 avisos; si se llena, se informa de error y no se sobrescriben avisos.
- Sin conexión bancaria directa ni sincronización periódica de cuentas. Recibos sin notificación requieren todavía extracto. No incluye predicción automática de domiciliaciones.
- Servicio sujeto a permisos y restricciones de Android. No se garantiza recepción inmediata ni completa.

## Validación
- Compilación release y firma verificadas: paquete es.vidal.finanzas, versionCode 4, misma firma que las versiones anteriores.
- 42 pruebas JVM correctas; 1 prueba opcional omitida por no disponer del extracto privado en esta ejecución. Las 17 pruebas nuevas cubren pagos, recibos, ingresos, transferencias, devoluciones, códigos de acceso, monedas, números ambiguos, coincidencias y posibles duplicados.
- Lint: 0 errores, 7 advertencias de icono y sugerencias KTX.
- No ejecutado en móvil/emulador. Pendiente verificar concesión de permiso, llegada con pantalla apagada, persistencia tras reinicio, pausa de captura, exclusión de fuentes, corrección manual, conciliación y límites de bandeja.

SHA256 APK: f10d41ae087100166c7a2144323d5d299c24c5cfe825ee1a346219f6bcdaaf13
