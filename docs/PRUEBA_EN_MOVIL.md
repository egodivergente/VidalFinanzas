# Comprobación pendiente en el móvil

Esta entrega ha sido compilada y probada con tests JVM. No se ha ejecutado en el S25 Ultra ni en un emulador Android.

1. Instalar la APK en Android 11+, con bloqueo de pantalla activo.
2. Desbloquear con huella o credencial. Salir al inicio y volver: debe exigir desbloqueo.
3. Crear cuenta «Pruebas» e importar samples/extracto_ficticio.csv. Al volver del selector, desbloquear y revisar la previsualización. Debe haber siete movimientos.
4. Importar samples/extracto_solapado_ficticio.csv: debe marcar cuatro coincidencias y seleccionar una operación nueva. Reimportar el primer archivo no debe seleccionar nuevas filas.
5. Editar la devolución como abono de Alimentación y el traspaso como transferencia propia. Comprobar que no se cuentan como ingreso y gasto normal respectivamente.
6. Crear presupuesto de Alimentación y objetivo de ahorro; añadir y retirar una aportación. No se permite retirar más de lo asignado.
7. Guardar copia cifrada, añadir un objetivo de prueba y restaurar la copia. Confirmar reemplazo: el nuevo objetivo debe desaparecer y volver los datos de la copia. Con contraseña incorrecta no deben cambiar datos.
8. Cerrar y abrir la app, girar el teléfono, cancelar el selector y cancelar autenticación. No deben aparecer datos sin desbloqueo ni perderse movimientos guardados.
9. Comprobar que toda la revisión funciona en modo avión con archivos locales.
10. Activar el recordatorio y conceder notificaciones si se desea. Es semanal y aproximado; no está validado el disparo real en esta entrega.

Para salir de la prueba, deshacer ambos lotes desde Ajustes. Mantener una copia externa cifrada antes de introducir información importante.
