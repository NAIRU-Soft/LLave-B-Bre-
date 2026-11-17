# BBreApp — Gestor Seguro de Llaves BBre

**Desarrollado por NairuSoft · Versión 0.0.1**

## Descripción

**BBreApp** es una aplicación Android **100% offline** diseñada para registrar, consultar, copiar y administrar de forma cifrada las llaves de pago BBre asociadas a distintos bancos o billeteras (celular, cédula, correo electrónico o códigos alfanuméricos únicos).

Todos los registros se almacenan localmente en almacenamiento privado de la app utilizando cifrado **AES-256-GCM** respaldado por **Android Keystore**.

---

## Características Principales

### Seguridad y Control de Acceso
- **Registro e Inicio de Sesión Local**: Validación estricta de usuario y contraseña (mínimo 8 caracteres, sin espacios en blanco).
- **Derivación de Contraseñas**: Hash con `PBKDF2WithHmacSHA256` (120.000 iteraciones y salt aleatorio de 16 bytes por usuario, comparación en tiempo constante y migración automática desde hashes heredados).
- **Biometría Fuerte (`BIOMETRIC_STRONG` + `CryptoObject`)**: Desbloqueo opcional con huella o rostro de Clase 3 enlazado criptográficamente a una clave del Keystore invalidable si cambia la biometría del dispositivo.
- **Almacenamiento Cifrado Atómico**: `usuarios.json` y `llaves.json` cifrados con AES-256-GCM y escrituras atómicas mediante archivo temporal y bloqueo `Mutex` en `Dispatchers.IO`.
- **Protección de Sesión y Portapapeles**:
  - `FLAG_SECURE` en login y panel principal (bloqueo de capturas y miniatura en recientes).
  - Cierre automático de sesión tras **5 minutos de inactividad** medidos con reloj monotónico (`SystemClock.elapsedRealtime()`).
  - Copia rápida de llaves marcada como contenido sensible (`ClipDescription.EXTRA_IS_SENSITIVE` en Android 13+) con autolimpieza a los 45 segundos.
- **Reautenticación y Control de Cuenta**: Cambio de contraseña y eliminación definitiva de cuenta (con borrado íntegro de sus llaves) protegidos con contraseña actual y confirmación doble.

### Gestión de Llaves (`Mis Llaves`)
- Listado reactivo con `ListAdapter` + `DiffUtil` y estado vacío claro.
- Alta y edición con selector de bancos predefinidos + opción **Agregar nuevo banco** (con lista combinada y ordenada de bancos personalizados).
- Generación de **código alfanumérico aleatorio único** de 10 caracteres desde el formulario de llave.
- Prevención de llaves duplicadas por usuario (normalizando banco y valor de llave) y versionado semántico automático por parche en cada edición (`0.0.1` -> `0.0.2`).
- Cuadro de **Información de Llave** con autor histórico (`createdBy`), banco, descripción, valor, fechas de creación/modificación y versión.

### Configuración e Información del Sistema (`Configuración`)
- Actualización de nombre de usuario y/o contraseña (preservando `createdBy` histórico en las llaves existentes).
- Diálogo de **Información del sistema** con resumen del usuario activo, cantidad de llaves, versión de la app (`BuildConfig.VERSION_NAME`) y última llave modificada.
- Cierre manual de sesión con limpieza de estado en memoria y navegación limpia a Login.

---

## Arquitectura y Estructura del Proyecto

Arquitectura **MVVM con capa de Repositorio** (`model/`, `data/`, `security/`, `ui/`):

```text
BBreApp/
├── gradle/
│   ├── libs.versions.toml                  # Catálogo central de versiones (AGP 9.0.1, Kotlin 2.2.10)
│   └── wrapper/                            # Gradle Wrapper
├── app/
│   ├── proguard-rules.pro                  # Reglas ProGuard/R8 (incluye kotlinx.serialization)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml         # Solo USE_BIOMETRIC, allowBackup=false
│       │   ├── java/com/nairusoft/bbre/
│       │   │   ├── BBreApplication.kt      # Inicialización segura de SecurityManager
│       │   │   ├── model/                  # Modelos de dominio, resultados y UiState
│       │   │   │   ├── BbreModels.kt
│       │   │   │   └── UiState.kt
│       │   │   ├── data/                   # Repositorios y gateway testeable
│       │   │   │   ├── AuthRepository.kt
│       │   │   │   ├── KeysRepository.kt
│       │   │   │   └── SecureAuthGateway.kt
│       │   │   ├── security/               # Criptografía, hashing, sesión, validación y almacenamiento
│       │   │   │   ├── AuthValidator.kt
│       │   │   │   ├── KeyUtils.kt
│       │   │   │   ├── PasswordHasher.kt
│       │   │   │   ├── SecurityManager.kt
│       │   │   │   ├── SessionManager.kt
│       │   │   │   └── StorageManager.kt
│       │   │   └── ui/                     # Pantallas, ViewModels y adaptadores
│       │   │       ├── ActivityTransitions.kt
│       │   │       ├── SplashActivity.kt
│       │   │       ├── LoginActivity.kt
│       │   │       ├── LoginViewModel.kt
│       │   │       ├── DashboardActivity.kt
│       │   │       ├── DashboardViewModel.kt
│       │   │       └── KeysAdapter.kt
│       │   └── res/                        # Layouts Material 3, drawables vectoriales, values(-night), xml
│       ├── test/                           # Pruebas unitarias JVM (JUnit 4)
│       └── androidTest/                    # Pruebas instrumentadas (Keystore + Espresso)
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Requisitos Técnicos

- **Android mínimo**: Android 8.0 (API 26)
- **Android objetivo / compilación**: Android 14 (API 34)
- **JDK**: Java 17 / JDK 21 para compilación Gradle (`jvmTarget = JVM_17`)
- **AGP**: `9.0.1` · **Kotlin**: `2.2.10`
- **Dependencias clave**: AndroidX AppCompat/Core/Activity, Material 3, RecyclerView, Biometric, Security Crypto (`1.1.0-alpha06` — pendiente migración planificada a Google Tink + DataStore), Kotlinx Serialization, Kotlinx Coroutines.
- **Permisos declarados**: únicamente `android.permission.USE_BIOMETRIC`.
- **Seguridad de red**: `network_security_config.xml` con `cleartextTrafficPermitted="false"` y sin certificados de usuario confiables ni pines vacíos.

---

## Compilación y Pruebas

Desde el directorio `BBreApp/`:

```bash
# Ejecutar pruebas unitarias JVM
./gradlew testDebugUnitTest

# Ejecutar verificación Lint
./gradlew lint

# Compilar APK Debug
./gradlew assembleDebug

# Compilar APK Release con R8/ProGuard y resource shrinking
./gradlew assembleRelease

# Ejecutar pruebas instrumentadas en dispositivo/emulador conectado
./gradlew connectedDebugAndroidTest
```
---
© 2024–2026 NairuSoft. Todos los derechos reservados.
