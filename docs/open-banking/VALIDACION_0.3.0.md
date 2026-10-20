# Estado de la vista previa 0.3.0, 20/10/2026

## Comprobado

- Compilación Android QA final completada: `testDebugUnitTest` y `assembleDebug`.
- JUnit Android: **50 pruebas correctas, 1 omitida, 0 fallos y 0 errores**. Incluye los 8 casos nuevos de `BankConnectionTest`.
- Puente Python: **13 pruebas correctas** con proveedor y datos ficticios. No son pruebas contra cuentas reales.
- APK QA: `es.vidal.finanzas.connectqa`, versionCode 5, `0.3.0-connect-preview`.
- APK verificada por tamaño (50.082.876 bytes) y SHA256: `8564717818647c0edb04480f1709c80835ddb4abbace135d0c923f0a1d7b847d`.
- Esta publicación conserva el código de la entrega verificada; añade esta nota y su enlace en README. No incluye APK, claves privadas, token de emparejamiento ni extractos reales.

## Pendiente

- Instalación y prueba de UI en dispositivo; las pruebas JUnit no verifican biometría/Keystore ni el flujo de autorización real en Android.
- Registro y activación del proveedor, confirmar condiciones y coste del modo restringido y documentar habilitación AIS aplicable a España.
- Desplegar puente HTTPS en un entorno privado autorizado, con la clave del proveedor solo en el servidor.
- Consentimiento bancario del titular y primera consulta real contrastada con el extracto oficial. No hay ninguna cuenta real conectada acreditada en esta entrega.

El conector consulta movimientos contabilizados EUR de los últimos 90 días, sin pagos ni saldo bancario. La cuenta importada se mantiene separada de CSV; no sumar ambas como si fueran cuentas distintas cuando representan el mismo dinero.

Los documentos de versiones anteriores de `docs/` son históricos. Sus resultados no prueban la versión 0.3.0. Esta tanda no corrige los fallos ajenos a la conexión bancaria del parser CSV o del lector de notificaciones; se recomienda mantener la captura de notificaciones desactivada.
