# Corrección 0.1.1 — 15/10/2026

## Problemas localizados
- MainActivity.onStop bloqueaba y borraba la previsualización cada vez que se abría el selector de documentos.
- BankImporter.read trataba como CSV cualquier archivo que no reconociera como Excel, incluyendo PDF.

## Cambios
- Sesión conservada durante salidas breves, con bloqueo tras 60 segundos en segundo plano. Comprobación adicional del tiempo transcurrido al volver, para cubrir suspensión del proceso. Inicio en frío continúa exigiendo autenticación. Bloqueo manual conservado. Si Android informa que el dispositivo está bloqueado al detenerse la actividad, se bloquea inmediatamente.
- PDF identificado por extensión o cabecera antes de intentar parsear CSV; mensaje específico con formatos admitidos. No se implementa extracción PDF.
- Rechazo de otros binarios con bytes nulos que no son Excel.
- Interfaz indica CSV, XLS y XLSX; PDF no compatible.
- versionCode 2; versionName 0.1.1-preview. Sin migración de datos.
- Tres pruebas de regresión añadidas para PDF y binarios.

## Validación
- Compilación limpia: clean testDebugUnitTest assembleRelease lintRelease: BUILD SUCCESSFUL.
- 22 pruebas JVM, 0 fallos, 0 errores; Lint: 0 errores, 2 advertencias.
- Firma v3 verificada, mismo certificado que 0.1.0. Paquete es.vidal.finanzas; versionCode 2.
- PDF continúa sin soporte; no se ha probado con un extracto real de Ibercaja.
- No se ha ejecutado esta versión en móvil/emulador. Pendiente comprobar selector y retorno antes/después de 60 segundos, cambio de app, rotación, bloqueo manual, pantalla bloqueada y proceso terminado.
- Actualizar instalando encima de 0.1.0, sin desinstalar; no cambia el esquema de datos.

APK SHA256: 2fe956aa58d542d0cd7feb686b5af6a6d30e298bc427b158e09ae52820730ed4
