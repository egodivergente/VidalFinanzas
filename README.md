# Vidal Finanzas 0.3.0-connect-preview

Aplicación privada Android con datos locales cifrados y **conexión bancaria opcional de solo lectura**. La integración nueva conecta la APK con un puente privado y este con Enable Banking. Ibercaja está documentado como banco soportado por el proveedor.

**Código implementado no significa cuenta conectada.** El alta/activación restricted, la URL HTTPS del puente, sus credenciales y el consentimiento bancario del titular son requisitos externos. No contiene claves reales, contraseña bancaria ni datos financieros. No se han realizado llamadas a cuentas reales.

## Nuevo en 0.3.0

- Ajustes → Conexión bancaria: guardar URL/token cifrados, probar puente, autorizar en navegador oficial, actualizar cuentas, sincronizar y revocar.
- Modo SANDBOX/PRODUCCIÓN visible; no se presenta una simulación como conexión real.
- Consulta manual de movimientos contabilizados EUR de últimos 90 días. No saldo bancario, pagos, transferencias ni automatización en segundo plano.
- Cuenta local independiente por cuenta bancaria, IDs estables para no reimportar movimientos, preservación de categorías/notas y rechazo de conflictos o respuestas incompletas.
- Puente Python en `bridge/`: JWT RS256, claves solo servidor, estado de un uso, sesiones cifradas y paginación íntegra. No se almacenan movimientos en el puente.

## Activar

Leer [bridge/README.md](bridge/README.md) y [contrato](docs/open-banking/CONTRATO_APP.md). No poner la clave privada del proveedor dentro de la APK. La app **sí solicita INTERNET** desde esta versión; `usesCleartextTraffic=false` y el cliente bancario solo acepta HTTPS. Se mantienen SQLCipher, biometría, FLAG_SECURE y exclusión de copia automática Android.

## Compilar una APK de prueba separada

JDK 17, SDK 35, Gradle 8.11.1:

```bash
./gradlew -PfinanceQa :app:testDebugUnitTest :app:assembleDebug
```

Paquete QA: `es.vidal.finanzas.connectqa`. Se instala aparte, sin sustituir producción ni usar sus datos. No ejecutar instalación sin permiso del titular. Build release conserva `es.vidal.finanzas` pero no incluye firma: para actualizar la app existente hace falta su clave original y copia recuperable.

## Uso previo conservado

Importación CSV/XLS/XLSX, categorías, presupuestos, metas y copias cifradas. Transferencias propias y devoluciones se clasifican manualmente. El balance del periodo **no es saldo bancario**. La captura de notificaciones es independiente de la conexión oficial; no la necesita y se recomienda mantenerla desactivada por los fallos documentados en la auditoría 0.2.0.

Las fuentes heredadas de `docs/` describen sus versiones históricas y hashes anteriores, no acreditan esta entrega. Referencia vigente: `docs/open-banking/` y los resultados de compilación entregados con 0.3.0. No se repitió una auditoría exhaustiva ni se corrigieron los fallos ajenos a esta conexión.

## Validación de esta entrega

[Resultados y límites de 0.3.0](docs/open-banking/VALIDACION_0.3.0.md): Android 50 pruebas correctas y 1 omitida; puente 13 correctas con datos ficticios. APK compilada, todavía sin instalación ni conexión bancaria real acreditadas.
