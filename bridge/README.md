# Puente privado Ibercaja, solo lectura

Esta implementación usa Enable Banking. No es un servicio desplegado ni una cuenta ya conectada. Solo se han usado respuestas simuladas en las pruebas. No hay endpoints de pagos.

## Activación mínima

1. En un equipo privado de Vidal con Python 3.11 o posterior, crear un entorno virtual e instalar `requirements.txt`. Esta tanda no instala ni publica servicios del sistema.
2. Preparar una carpeta privada **fuera del repositorio**: ejecutar `python init_private.py --directory RUTA_PRIVADA --generate-provider-key` solo si no tienes ya una clave de aplicación. El script no imprime secretos, no sobrescribe archivos y no contacta con nadie. En Linux aplica 0700/0600; en Windows hay que proteger ACL antes de usar datos reales.
3. Registrar o recuperar la aplicación en https://enablebanking.com/cp/applications. Subir **solo** `provider-public.pem`, nunca `provider-private.pem`. Confirmar condiciones y precio aplicable. Para uso propio, modo PRODUCTION restringido y «Activate by linking accounts»: enlazar expresamente tu cuenta Ibercaja. SANDBOX no consulta tu cuenta real.
4. Elegir el origen HTTPS del puente y registrar exactamente `ORIGEN_HTTPS/callback` como redirect URL. El mismo origen se introduce en Android. No basta con escribir una URL inventada.
5. Variables del servidor:
   - `VF_PUBLIC_URL`: origen HTTPS real, sin ruta.
   - `VF_PRIVATE_DIR`: carpeta privada con `pairing-token.txt`, `state.key` y futuro `session.enc`.
   - `VF_APPLICATION_ID`: ID real de la aplicación Enable Banking.
   - `VF_PROVIDER_KEY`: ruta a clave privada PEM de esa aplicación, solo servidor.
   - `VF_ENVIRONMENT`: SANDBOX o PRODUCTION según el registro real del proveedor. Es una etiqueta de configuración, no cambia la aplicación registrada.
   - `VF_BANK_NAME`: solo para SANDBOX, nombre exacto del catálogo español si la simulación no se llama Ibercaja. PRODUCTION descubre Ibercaja automáticamente.
6. Arrancar `python serve.py`. Solo escucha en **127.0.0.1:8766**, un proceso y un hilo. Configurar por separado un proxy HTTPS privado al que llegue el móvil y el navegador. No habilitar CORS, logs de access/query/header ni captura de cuerpos. El callback contiene un código de un uso: no registrarlo. Configurar límite de peticiones en el proxy y timeout al menos 210 s. No exponer este puerto HTTP directamente.
7. En Ajustes de la APK: URL real + token de `pairing-token.txt` mediante un canal privado (no chat). Probar, conectar y autorizar en el navegador oficial de Enable Banking/Ibercaja. La APK no pide contraseña del banco.
8. Volver a la APK, actualizar cuentas, aceptar crear cuenta local separada y sincronizar. Verificar la primera consulta con el extracto oficial. Se conservan los datos CSV por separado.
9. Revocar desde la app o https://enablebanking.com/data-sharing-consents/. La revocación no borra movimientos locales previamente importados.

## Límites deliberados

- Últimos 90 días, solo EUR y BOOK. No saldos bancarios ni pagos en esta tanda. El «balance del periodo» de la app sigue sin ser saldo de cuenta.
- Consulta manual, no sincronización permanente. La caducidad se toma de la respuesta del proveedor y puede anticiparse en el banco.
- Solo referencias `entry_reference` estables. Sin referencia, conflicto o paginación incompleta: se rechaza el conjunto, sin importar parcialmente.
- Sin caché ni persistencia de movimientos en servidor. Session ID y lista mínima de cuentas se guardan AES-GCM; la clave de almacenamiento está en la carpeta privada del mismo servidor. Esto no protege frente a un administrador o sistema comprometido.
- Los encabezados PSU no se inventan ni se toman de X-Forwarded-For sin confianza: esta versión no los envía, de modo que se aplican límites de consulta no interactiva del banco. El puente limita repetición a un minuto, pero el banco puede limitar más.
- Token único de propietario, no multiusuario. No arrancar múltiples workers: el candado y el límite de frecuencia son locales al proceso.
- No firma ni alta reales incluidas. Antes de sustituir producción: copia recuperable y APK con la firma original. La QA se instala aparte solo con permiso.

## Pruebas rápidas

`python tests/test_bridge.py` usa un proveedor ficticio, no toca Internet. Comprueba token, estado de un uso, RSA/JWT, cifrado del estado, errores sin fugas, páginas vacías con continuación, exactitud monetaria y rechazo de conjuntos incompletos. Ejecutar también `python -m compileall -q .`.
