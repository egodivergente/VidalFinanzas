# Ibercaja — 0.1.2

- XML de XLSX: soporte para prefijos de espacio de nombres y direcciones de celda omitidas.
- Concepto y Descripción se combinan para conservar comercio y tipo de operación; mapeo manual adicional disponible.
- Referencia bancaria no se trata como identificador único: puede identificar una tarjeta o contrato compartido por múltiples movimientos.
- Duplicados: coincidencia por cuenta, fecha, importe, texto normalizado y saldo posterior, consumiendo las ocurrencias existentes. Se preservan compras repetidas legítimas dentro del extracto. Si faltan saldos y se solapan compras idénticas en extractos parciales, revisar manualmente.
- Verificado localmente con el extracto facilitado: 761 filas válidas, total de importes coincide con lectura independiente, segunda importación sin altas. El archivo privado y sus datos no se incluyen en este proyecto ni en la APK.
- 26 pruebas JVM ejecutadas, sin fallos ni omisiones. Lint 0 errores y 2 advertencias. APK release firmada con certificado de 0.1.0/0.1.1, versionCode 3.
- Pendiente prueba de interfaz en dispositivo. PDF sigue sin soporte.
- Si se importó este extracto con una versión anterior y quedaron movimientos incompletos, guardar una copia cifrada y deshacer únicamente ese lote antes de reimportar. Deshacer elimina también las notas y categorías manuales de ese lote.

SHA256 APK: a929abdd3bc6ae92241324efdfc88cde82dfe929f95c7a9ffea3f67f95f9dc4d
