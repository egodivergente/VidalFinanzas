# Dependencias directas

Versiones fijadas en app/build.gradle.kts. El código completo de la app se entrega para uso privado del propietario.

- Kotlin 2.1.20 y kotlinx.serialization-json 1.8.0: JetBrains, Apache-2.0.
- AndroidX Compose (BOM 2025.04.01), Room 2.7.1, Lifecycle 2.8.7, Activity 1.10.1, Fragment 1.8.6, Biometric 1.1.0, SQLite 2.5.0, WorkManager 2.10.1: Android Open Source Project, Apache-2.0.
- SQLCipher for Android 4.7.2: Zetetic. Licencia de la edición community: https://www.zetetic.net/sqlcipher/license/ . Código: https://github.com/sqlcipher/sqlcipher-android y https://github.com/sqlcipher/sqlcipher . Biblioteca sin modificaciones.
- JExcelAPI 2.6.12: GNU LGPL. Código y distribución originales: https://sourceforge.net/projects/jexcelapi/ . Biblioteca sin modificaciones; usada solo para lectura XLS heredada. Se puede reemplazar desde Gradle y recompilar todo el proyecto entregado.
- JUnit 4.13.2 y kxml2 2.3.0: solo pruebas JVM, no distribuidos como dependencias runtime de la APK.

Esta entrega privada no es una publicación en tiendas. Antes de redistribuir, revisar las licencias completas de las dependencias y sus componentes nativos.
