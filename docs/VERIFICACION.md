> **Documento histórico de la versión indicada.** Desde 0.3.0 hay INTERNET y un puente bancario opcional. No acredita compilación, seguridad o conexión live de 0.3.0; consulta `../README.md` y `open-banking/CONTRATO_APP.md`.

# Verificación 0.1.2

26 pruebas JVM correctas, 0 omitidas. Lint 0 errores y 2 advertencias. Certificado verificado, idéntico al anterior. Sin prueba en dispositivo.

SHA256 APK: a929abdd3bc6ae92241324efdfc88cde82dfe929f95c7a9ffea3f67f95f9dc4d

---
# Verificación 0.1.1

Compilación release limpia completada. 22 tests JVM correctos; Lint 0 errores y 2 advertencias. Firma v3 validada y certificado idéntico a 0.1.0. No probado en dispositivo.

SHA256 APK: 2fe956aa58d542d0cd7feb686b5af6a6d30e298bc427b158e09ae52820730ed4

---
## Histórico 0.1.0

# Verificación de entrega — 15/10/2026

## Resultado observado
- Compilación de release completada con JDK 17, Gradle 8.11.1 y SDK 35.
- Segunda compilación offline completada para verificar coherencia entre fuente final y APK.
- 19 pruebas JVM: 0 fallos, 0 errores, 0 omitidas.
- Android Lint release: 0 errores y 5 avisos no bloqueantes (3 versiones de dependencias, icono propio no definido y sugerencia KTX).
- APK release no depurable, firmada con clave propia RSA de 3072 bits. apksigner verifica firma v3.
- Manifiesto final: sin permiso INTERNET. Los permisos de estado de red, wakelock, boot y servicio proceden de WorkManager; no permiten conexión sin INTERNET.
- SDK mínimo 30 (Android 11), target/compile 35. ABI: arm64-v8a, armeabi-v7a, x86, x86_64.
- Las pruebas XLS/XLSX usan archivos sintéticos; CSV utiliza fixtures ficticios.

## Identidad del artefacto
- Archivo: VidalFinanzas-0.1.0.apk
- Application ID: es.vidal.finanzas
- Versión: 0.1.0-preview (versionCode 1)
- Tamaño: 43,323,788 bytes
- SHA-256 APK: 9bbadc0c36f620c14e9de89d0b2bf00ca48742a3157c4632b4cecefa09649c15
- SHA-256 certificado: 715839691f8ee86ff3b35170fac6614d40e05081a87b28e3f91307d2c6acac14

## Lo que no se ha comprobado
No se ha instalado ni ejecutado la APK en un dispositivo o emulador. No se afirma validación de interfaz, biometría/Keystore reales, apertura SQLCipher nativa en Android, ciclo de vida completo, restauración transaccional en dispositivo o recordatorio real. Los tests de cifrado verifican el formato portátil de copias, no el hardware Keystore. No existe todavía un extracto real de Ibercaja para validar el adaptador.

Consultar PRUEBA_EN_MOVIL.md para el primer recorrido de comprobación. Es una primera versión de prueba, no una app auditada en producción.

## Actualizaciones y firma
El código fuente no contiene la clave de firma. Se entrega aparte en VidalFinanzas-Firma-Privada.zip, junto con su contraseña de recuperación. Mantener ese kit privado y guardar contraseña y clave separadas después de descargarlo. No subirlo a repositorios. La clave de firma no abre la base de datos ni las copias financieras.
