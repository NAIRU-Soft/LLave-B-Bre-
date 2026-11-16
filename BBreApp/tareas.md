# Plan de Mejoras y Tareas — BBreApp

> Análisis realizado sobre todos los archivos del repositorio `BBreApp` (código Kotlin, layouts, recursos, configuración Gradle, manifiesto, reglas ProGuard y documentación).

## Resumen del Estado Actual

La aplicación actual implementa un gestor local cifrado de llaves BBre y cuentas de usuario (`SplashActivity` → `LoginActivity` → `DashboardActivity`), utilizando `EncryptedSharedPreferences`, `AndroidKeyStore` (`AES/GCM/NoPadding`), `BiometricPrompt`, `kotlinx.serialization` y almacenamiento local en `usuarios.json` y `llaves.json`.

Sin embargo, existen **riesgos críticos de seguridad**, **bugs funcionales** (como doble espera en el splash, cancelación del temporizador de inactividad al ir a segundo plano y posible pérdida silenciosa de datos), **configuración de build/Gradle inconsistente**, **problemas visuales en Modo Oscuro (`DayNight`)** y **desalineación entre la documentación (`README.md`, `spec.md`) y el código actual**.

---

## 1. Prioridad Crítica (Seguridad, Pérdida de Datos y Bugs Severos)

### - [ ] T-01: Corregir cierre de sesión por inactividad (5 min) y protección al pasar a segundo plano
- **Archivos:** `app/src/main/java/com/nairusoft/bbre/ui/DashboardActivity.kt`, `app/src/main/java/com/nairusoft/bbre/ui/LoginActivity.kt`
- **Problema actual:**
  - En `DashboardActivity.onPause()` (líneas 51-54) se ejecuta `logoutHandler.removeCallbacks(logoutRunnable)`, lo que **detiene el temporizador de 5 minutos** cada vez que el usuario minimiza la app o bloquea la pantalla. Al regresar (`onResume()`), el contador vuelve a empezar desde cero, permitiendo sesiones abiertas indefinidamente.
  - Si la app se cierra desde recientes sin pulsar "Cerrar Sesión", `"session_username"` permanece guardado en `EncryptedSharedPreferences`.
  - `btnLogout` (líneas 376-379) duplica la lógica de `performManualLogout()` en lugar de reutilizarla, y `navigateToLogin()` no limpia la pila de actividades ni los datos sensibles en memoria (`currentUser`, lista en `KeysAdapter`).
- **Mejora propuesta:**
  1. Persistir un timestamp de última interacción/pausa (`lastActiveTimestamp`) usando un reloj monotónico o `System.currentTimeMillis()`.
  2. En `onResume()` / `onStart()` (o mediante `DefaultLifecycleObserver` con `ProcessLifecycleOwner`), verificar si transcurrieron `>= 5 * 60 * 1000 ms` desde la última actividad y forzar el cierre de sesión inmediato antes de mostrar contenido.
  3. Cumplir estrictamente el `fix 0.0.6` de `spec.md`: limpiar la sesión al cerrar la app o tras inactividad, vaciar `currentUser` y la lista del adaptador al cerrar sesión, y usar `Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK` al volver a `LoginActivity`.
  4. En `LoginActivity.onCreate()`, limpiar cualquier `"session_username"` residual como defensa en profundidad.
- **Criterio de aceptación:** Si el usuario deja la app inactiva o en segundo plano durante 5 minutos, al volver se cierra la sesión obligatoriamente y se solicita autenticación nuevamente.

### - [ ] T-02: Evitar pérdida silenciosa de datos (`usuarios.json` / `llaves.json`), agregar escritura atómica y sincronización
- **Archivos:** `app/src/main/java/com/nairusoft/bbre/security/StorageManager.kt`, `app/src/main/java/com/nairusoft/bbre/security/SecurityManager.kt`
- **Problema actual:**
  - En `StorageManager.getUsers()` (líneas 56-67) y `getKeys()` (líneas 136-147), ante cualquier error de lectura, descifrado o deserialización JSON, se captura la excepción y se retorna `emptyList()`. Si inmediatamente después se llama a `addUser()`, `addKey()` o `updateKey()`, se sobrescribe el archivo con solo el elemento nuevo, **borrando irreversiblemente todos los usuarios o llaves existentes**.
  - En `SecurityManager.decrypt()` (líneas 202-203) no se valida que `encryptedData.size > GCM_IV_LENGTH` antes de usar `copyOfRange`.
  - Las operaciones en `StorageManager` escriben directo al archivo (`file.writeText(...)`) sin escritura atómica ni bloqueo entre hilos (`Mutex` / `synchronized`), y se ejecutan en el hilo principal (UI Thread).
- **Mejora propuesta:**
  1. Diferenciar explícitamente "archivo no existe" (lista vacía inicial) de "error de descifrado/corrupción" (retornar un `Result<T>` / estado de error para no sobrescribir archivos existentes).
  2. Validar `encryptedData.size > GCM_IV_LENGTH` en `SecurityManager.decrypt()`.
  3. Implementar escritura atómica (escribir a `archivo.tmp`, `flush`/`sync` y renombrar atómicamente sobre el archivo final) y proteger lecturas/escrituras con un lock/`Mutex`.
  4. Mover todas las operaciones de `StorageManager` a `Dispatchers.IO`.
- **Criterio de aceptación:** Un fallo de descifrado nunca sobrescribe `usuarios.json` ni `llaves.json`; un cierre inesperado durante el guardado no corrompe el archivo existente.

### - [ ] T-03: Reemplazar hashing SHA-256 sin salt por KDF seguro para contraseñas (`PBKDF2WithHmacSHA256` o Argon2)
- **Archivos:** `app/src/main/java/com/nairusoft/bbre/security/SecurityManager.kt`, `app/src/main/java/com/nairusoft/bbre/security/StorageManager.kt`, `app/src/main/java/com/nairusoft/bbre/ui/LoginActivity.kt`, `app/src/main/java/com/nairusoft/bbre/ui/DashboardActivity.kt`
- **Problema actual:**
  - `SecurityManager.hashData()` (líneas 248-256) calcula un `SHA-256` simple sin salt ni factor de costo computacional. Dos usuarios con la misma contraseña generan el mismo hash y son vulnerables a ataques de diccionario/fuerza bruta rápida.
  - La comparación usa `==` en lugar de comparación en tiempo constante (`MessageDigest.isEqual`).
- **Mejora propuesta:**
  1. Añadir un campo `salt` (Base64 de 16+ bytes generados con `SecureRandom`) y algoritmo/iteraciones al modelo `User` (manteniendo compatibilidad de migración al primer login exitoso para usuarios antiguos que no tengan `salt`).
  2. Implementar derivación de clave con `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")` (mínimo 120.000 iteraciones y clave de 256 bits) o Argon2id, y verificar con `MessageDigest.isEqual`.
- **Criterio de aceptación:** Cada usuario tiene un salt aleatorio único y sus credenciales se verifican mediante KDF resistente a fuerza bruta en tiempo constante.

### - [ ] T-04: Asegurar autenticación biométrica con `CryptoObject` y corregir regeneración destructiva de clave
- **Archivos:** `app/src/main/java/com/nairusoft/bbre/ui/LoginActivity.kt`, `app/src/main/java/com/nairusoft/bbre/security/SecurityManager.kt`
- **Problema actual:**
  - `LoginActivity.setupBiometricButton()` (línea 87) llama a `securityManager.generateBiometricKey(BIOMETRIC_KEY_ALIAS)` cada vez que se abre el login, y `SecurityManager.generateBiometricKey()` (líneas 82-84) **elimina la clave existente del Keystore y crea una nueva**.
  - `showBiometricPrompt()` (línea 236) llama `biometricPrompt.authenticate(promptInfo)` **sin `BiometricPrompt.CryptoObject`**, y admite `BIOMETRIC_WEAK` (líneas 71-72, 232-233). Tras autenticarse, confía ciegamente en `last_logged_in_user` guardado en preferencias, sin verificar siquiera si ese usuario sigue existiendo en `usuarios.json`.
- **Mejora propuesta:**
  1. Restringir a `BiometricManager.Authenticators.BIOMETRIC_STRONG` cuando se use `CryptoObject` (o manejar claramente fallback si el dispositivo solo tiene biometría débil/rostro clase 2, documentando la política).
  2. Generar la clave biométrica solo si no existe (no borrarla en cada `onCreate`), usarla dentro de un `BiometricPrompt.CryptoObject(cipher)` para cifrar/descifrar un token de sesión biométrica vinculado al usuario, y manejar `KeyPermanentlyInvalidatedException` (ej. cuando el usuario registra una nueva huella en el sistema).
  3. Validar que el usuario asociado siga existiendo en `StorageManager.getUsers()` antes de iniciar sesión.
- **Criterio de aceptación:** La biometría desbloquea una operación criptográfica real en Android Keystore y falla de forma controlada si cambia el enrolamiento biométrico o si la cuenta ya no existe.

### - [ ] T-05: Corregir doble espera de 6 segundos y fuga de `Handler` en `SplashActivity`
- **Archivos:** `app/src/main/java/com/nairusoft/bbre/ui/SplashActivity.kt`
- **Problema actual:**
  - En `navigateToLogin()` (líneas 62-74), `Handler.postDelayed(..., 3000)` espera 3 segundos y dentro lanza `lifecycleScope.launch { delay(3000) }` que espera otros 3 segundos, haciendo que el Splash dure **6 segundos** en lugar de `splashTimeOut = 3000`.
  - El `Handler` no se cancela en `onDestroy()`, pudiendo ejecutarse tras cerrar la Activity.
- **Mejora propuesta:** Eliminar el `Handler.postDelayed` y dejar únicamente `lifecycleScope.launch { delay(splashTimeOut); ... }` (o usar la API oficial `androidx.core:core-splashscreen`).
- **Criterio de aceptación:** El Splash navega a `LoginActivity` exactamente tras `splashTimeOut` (3 s) y no deja callbacks pendientes si el usuario sale antes.

---

## 2. Prioridad Alta (Hardening, Build/Gradle y Bugs Funcionales)

### - [ ] T-06: Añadir `FLAG_SECURE` en pantallas sensibles y marcar portapapeles como confidencial (Android 13+)
- **Archivos:** `app/src/main/java/com/nairusoft/bbre/ui/DashboardActivity.kt`, `app/src/main/java/com/nairusoft/bbre/ui/LoginActivity.kt`
- **Problema actual:**
  - Ninguna Activity establece `WindowManager.LayoutParams.FLAG_SECURE`, permitiendo capturas de pantalla y que el sistema muestre las llaves en la vista previa de "Apps Recientes".
  - `DashboardActivity.copyToClipboard()` (líneas 184-189) copia el valor de la llave sin `ClipDescription.EXTRA_IS_SENSITIVE`, exponiendo la llave en claro en el overlay del portapapeles en Android 13/14.
- **Mejora propuesta:**
  1. Configurar `window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)` en `LoginActivity` y `DashboardActivity`.
  2. En `copyToClipboard()`, añadir `PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }` a `ClipData.description.extras` en API 33+ (y opcionalmente limpiar el portapapeles tras 30-60 s).

### - [ ] T-07: Corregir configuración de Gradle, versiones de plugins, ProGuard/R8 y wrapper
- **Archivos:** `build.gradle.kts`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `gradle/wrapper/`
- **Problema actual:**
  - `app/build.gradle.kts` tiene `isDebuggable = false` e `isMinifyEnabled = true` en el buildType `debug` (impide depurar y ralentiza el desarrollo).
  - `build.gradle.kts` mezcla `org.jetbrains.kotlin.android` versión `2.2.10` con `org.jetbrains.kotlin.plugin.serialization` versión `1.9.20`.
  - `app/build.gradle.kts` usa `kotlinOptions` (deprecado en Kotlin 2.x), tiene `lint { abortOnError = false; checkReleaseBuilds = false }` y declara dependencias que **no se usan** (`navigation-fragment-ktx`, `navigation-ui-ktx`, `datastore-preferences`).
  - `androidx.security:security-crypto:1.1.0-alpha06` es versión alpha (y en vías de deprecación en Jetpack).
  - `app/proguard-rules.pro` no tiene reglas explícitas para modelos `@Serializable` (`User`, `UserKey`) ni `kotlinx.serialization` bajo R8.
  - Falta el wrapper ejecutable (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`) en el proyecto (solo está `gradle/wrapper/gradle-wrapper.properties`).
- **Mejora propuesta:**
  1. En `debug`: poner `isDebuggable = true` y `isMinifyEnabled = false`; en `release`: activar `isShrinkResources = true` junto a `isMinifyEnabled = true`.
  2. Centralizar dependencias en `gradle/libs.versions.toml` y alinear `kotlin.plugin.serialization` con la versión real de Kotlin (`2.2.10`), además de migrar a `compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }`.
  3. Eliminar dependencias no utilizadas (`navigation-*`, `datastore-preferences` si no se va a migrar a DataStore) y planificar migración de `security-crypto` alpha a una solución estable (DataStore + Tink o `security-crypto` estable si aplica).
  4. Añadir reglas ProGuard explícitas para `kotlinx.serialization` y las clases de modelo (`User`, `UserKey`), y reactivar `lint` en builds de release.
  5. Generar los archivos completos del Gradle Wrapper (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`).

### - [ ] T-08: Limpiar permisos innecesarios en `AndroidManifest.xml` y corregir `network_security_config.xml`
- **Archivos:** `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/network_security_config.xml`
- **Problema actual:**
  - `AndroidManifest.xml` solicita `CAMERA` (con `<uses-feature>` de cámara), `INTERNET`, `ACCESS_NETWORK_STATE`, `USE_FINGERPRINT` (deprecado desde API 28) y declara `<uses-library android:name="org.apache.http.legacy" />`, aunque la app ya no tiene escáner QR ni llamadas de red.
  - `LoginActivity` y `DashboardActivity` fuerzan `android:screenOrientation="portrait"`, perjudicando tablets/foldables y modo multiventana en Android 14.
  - `network_security_config.xml` tiene un `<pin-set expiration="2025-12-31">` **expirado y vacío**, y confía en `<certificates src="user" />`.
- **Mejora propuesta:**
  1. Eliminar `CAMERA`, `INTERNET`, `ACCESS_NETWORK_STATE`, `USE_FINGERPRINT` y `org.apache.http.legacy` mientras la app sea offline (solo dejar `USE_BIOMETRIC`).
  2. Quitar el bloqueo rígido `screenOrientation="portrait"` y asegurar que los layouts sean responsivos con `ScrollView` / `ConstraintLayout`.
  3. Eliminar el `<pin-set>` vacío/expirado y retirar `<certificates src="user" />` (o actualizar pins reales solo si se integra backend HTTPS en el futuro).

### - [ ] T-09: Reforzar seguridad y manejo de errores en "Configuración" de perfil
- **Archivos:** `app/src/main/java/com/nairusoft/bbre/ui/DashboardActivity.kt`
- **Problema actual:**
  - En `setupSettingsTab()` (líneas 348-373), si `foundUser == null` (por ejemplo tras un fallo de lectura), el botón "Guardar Cambios" no muestra ningún mensaje ni error.
  - Permite cambiar la contraseña o eliminar permanentemente la cuenta (`btnDeleteAccount`) sin solicitar verificación de la contraseña actual o biometría.
  - Si cambia el `username`, `UserKey.createdBy` sigue conservando el usuario anterior (aunque `UserKey.username` sí se actualiza en `StorageManager.updateUser`), lo cual conviene decidir si debe mantenerse histórico o actualizarse.
- **Mejora propuesta:**
  1. Pedir la contraseña actual (o confirmación biométrica) antes de actualizar contraseña o eliminar la cuenta.
  2. Mostrar un mensaje claro si no se encuentra el usuario al guardar cambios.
  3. Reutilizar `performManualLogout()` desde `btnLogout`.

### - [ ] T-10: Corregir errores de validación, versionado de llaves y bancos personalizados en el diálogo de llaves
- **Archivos:** `app/src/main/java/com/nairusoft/bbre/ui/DashboardActivity.kt`, `app/src/main/java/com/nairusoft/bbre/security/StorageManager.kt`, `app/src/main/res/layout/dialog_key.xml`
- **Problema actual:**
  - En `showKeyDialog()` (líneas 254-316), los errores previos (`tilCustomBank.error`, `tilDescription.error`, `tilKeyValue.error`) nunca se limpian (`= null`) cuando el usuario corrige un campo y falla otro, o al cambiar de banco en el `Spinner`.
  - Al modificar una llave (`existingKey != null`), se actualiza `modificationDate`, pero **`version` nunca cambia** (siempre queda en `"0.0.1"`), contradiciendo el campo `"Versión más reciente"` de `showKeyInfoDialog()`.
  - Los bancos personalizados agregados mediante `"+ Agregar nuevo banco..."` no se añaden a la lista disponible para futuras llaves del usuario.
  - En `StorageManager.addKey()` / `updateKey()`, la comparación `it.type == key.type && it.value == key.value` es sensible a mayúsculas/minúsculas y solo valida por usuario, aunque el error dice `"Esta llave ya existe en el sistema"`.
- **Mejora propuesta:**
  1. Limpiar los errores de todos los `TextInputLayout` al iniciar la validación o al escribir (`doAfterTextChanged`).
  2. Incrementar la versión automáticamente al modificar una llave (ej. `0.0.1` → `0.0.2`).
  3. Combinar la lista base de bancos con los bancos personalizados ya registrados por el usuario (ordenados y sin duplicados).
  4. Normalizar espacios/mayúsculas al validar duplicados de llaves y alinear el mensaje de error con el alcance real (por usuario o global).

---

## 3. Prioridad Media (UI/UX, Material 3, Accesibilidad y Funcionalidades)

### - [ ] T-11: Solucionar incompatibilidad con Modo Oscuro (`DayNight`) y mejorar el contraste visual
- **Archivos:** `app/src/main/res/values/themes.xml`, `app/src/main/res/values/colors.xml`, `app/src/main/res/layout/*.xml`
- **Problema actual:**
  - `Theme.BBreApp` usa `Theme.Material3.DayNight.NoActionBar`, pero no existe `values-night/colors.xml` ni `values-night/themes.xml`, y los XML fijan `@color/black`, `@color/white` y `@color/background_primary` (`#FAFAFA`). En teléfonos con Modo Oscuro activo, los componentes del sistema y `AlertDialog` adoptan fondo oscuro mientras los textos quedan en negro o con contraste deficiente.
  - En `dialog_key.xml` (línea 40), el `Spinner` usa `@drawable/security_badge_background` (`#40FFFFFF` pensado para el fondo oscuro del Splash), resultando casi invisible sobre `#FAFAFA`.
- **Mejora propuesta:** Usar atributos semánticos de Material 3 (`?attr/colorSurface`, `?attr/colorOnSurface`, `?attr/colorPrimary`, etc.), crear paleta `values-night` dedicada (o definir explícitamente soporte Light/Dark consistente) y reemplazar `AlertDialog.Builder` por `MaterialAlertDialogBuilder`.

### - [ ] T-12: Modernizar componentes UI y eliminar creación dinámica de vistas por índice
- **Archivos:** `app/src/main/res/layout/activity_dashboard.xml`, `app/src/main/res/layout/dialog_key.xml`, `app/src/main/res/layout/item_key.xml`, `app/src/main/java/com/nairusoft/bbre/ui/DashboardActivity.kt`
- **Problema actual:**
  - En `DashboardActivity.setupSettingsTab()` (líneas 385-408), el botón `"Información de Llave y Sistema"` se crea en Kotlin y se inserta con `addView(btnSystemInfo, 3)`, acoplado frágilmente al orden de hijos en `activity_dashboard.xml`.
  - La barra inferior de navegación es un `LinearLayout` manual en lugar de `BottomNavigationView` de Material 3.
  - `dialog_key.xml` usa `Spinner` clásico en vez de `TextInputLayout` con `ExposedDropdownMenuBox` (`AutoCompleteTextView`).
  - `item_key.xml` usa iconos obsoletos `@android:drawable/ic_menu_info_details`, `ic_menu_edit`, `ic_menu_delete`, y `activity_dashboard.xml` usa `@android:drawable/ic_input_add` en el FAB. Además los `ImageButton` miden `36dp x 36dp` (menor al mínimo táctil de `48dp x 48dp` de accesibilidad) y los `TextView` no definen `maxLines`/`ellipsize`.
- **Mejora propuesta:**
  1. Mover `btnSystemInfo` directamente a `activity_dashboard.xml` y evaluar qué información útil mostrar allí respecto a las llaves del usuario (ej. resumen de llaves creadas, última actualización, versión de la app) como pide `spec.md`.
  2. Reemplazar el `LinearLayout` inferior por `com.google.android.material.bottomnavigation.BottomNavigationView` y el `Spinner` por un `ExposedDropdownMenuBox` de Material 3.
  3. Sustituir los `@android:drawable/ic_menu_*` e `ic_input_add` por iconos vectoriales Material uniformes, ampliar áreas táctiles a `48dp` (o usar `touchDelegate`) y añadir `maxLines`/`ellipsize` en `item_key.xml`.

### - [ ] T-13: Mejorar layout, validaciones y usabilidad de `LoginActivity`
- **Archivos:** `app/src/main/res/layout/activity_login.xml`, `app/src/main/java/com/nairusoft/bbre/ui/LoginActivity.kt`
- **Problema actual:**
  - `activity_login.xml` no tiene `ScrollView`: en pantallas pequeñas, teclado abierto o texto grande, los botones inferiores (`btnAction`, `btnBiometric`) pueden quedar recortados.
  - El campo de "Nombre de usuario" en `activity_login.xml` (línea 57) y en `activity_dashboard.xml` (línea 130) usa `@drawable/ic_call` (icono de teléfono de la versión anterior).
  - En `activity_login.xml` (línea 102) falta tilde en `"Registrate"`, mientras Kotlin usa `"Regístrate"`.
  - En `LoginActivity.kt` (líneas 99, 124, 139, 166): aplica `.trim()` a la contraseña, solo valida `>= 8` caracteres sin política de complejidad ni límite claro respecto a `spec.md`, y mantiene `delay(1000)` artificial en login y registro.
  - El botón biométrico no indica a qué usuario corresponde la huella guardada.
- **Mejora propuesta:**
  1. Envolver el formulario de `activity_login.xml` en un `NestedScrollView`/`ScrollView` con `fillViewport="true"`.
  2. Crear e usar un vector `ic_person` para el campo usuario en login y configuración.
  3. Mostrar el usuario asociado en el botón/subtítulo biométrico (ej. `"Ingresar con huella (usuario)"`), eliminar el `delay(1000)` artificial, corregir ortografía y no recortar espacios silenciosamente en contraseñas (o prohibir espacios explícitamente en validación).
  4. Habilitar `enableEdgeToEdge()` con manejo correcto de `WindowInsets` (status bar, navigation bar e IME/teclado).

### - [ ] T-14: Migrar APIs deprecadas (`onBackPressed` y `overridePendingTransition`)
- **Archivos:** `app/src/main/java/com/nairusoft/bbre/ui/SplashActivity.kt`, `app/src/main/java/com/nairusoft/bbre/ui/LoginActivity.kt`
- **Problema actual:**
  - Ambas Activities sobrescriben `onBackPressed()` (deprecado en API 33 y reportado como warning en `spec.md`). Además en `SplashActivity` el comentario indica impedir volver atrás, pero termina llamando a `super.onBackPressed()`.
  - Se usa `overridePendingTransition()` (deprecado en API 34).
- **Mejora propuesta:** Reemplazar por `onBackPressedDispatcher.addCallback(this) { ... }` y `overrideActivityTransition()` para API 34+ (con fallback en versiones inferiores).

### - [ ] T-15: Limpiar `strings.xml` y eliminar textos hardcodeados en Kotlin/XML
- **Archivos:** `app/src/main/res/values/strings.xml`, layouts y Activities
- **Problema actual:**
  - `strings.xml` conserva ~30 strings obsoletos de transferencias, saldo, escáner QR, límite `$12.110.000`, historial y PIN (`dashboard_balance`, `transfer_*`, `qr_*`, `error_insufficient_funds`, `login_phone_hint`, etc.), y `login_subtitle` dice `"Ingresa con tu llave"`.
  - Casi todos los textos actuales de Login, Registro, Mis Llaves, Diálogos, Toasts y Configuración están escritos directamente en código Kotlin y XML.
- **Mejora propuesta:** Depurar `strings.xml` eliminando lo obsoleto, corregir `login_subtitle` y `"Eliminar cuenta permanentemente"`, y externalizar todos los textos visibles a `strings.xml`.

### - [ ] T-16: Añadir funcionalidades prácticas en "Mis Llaves" (búsqueda, generador aleatorio, copiar explícito y respaldo cifrado)
- **Archivos:** `app/src/main/java/com/nairusoft/bbre/ui/DashboardActivity.kt`, `app/src/main/res/layout/activity_dashboard.xml`, `app/src/main/res/layout/dialog_key.xml`, `app/src/main/res/layout/item_key.xml`
- **Mejora propuesta:**
  1. **Buscador/Filtro** en la barra superior o sobre el `RecyclerView` para filtrar por banco, descripción o llave.
  2. **Botón generador de código alfanumérico aleatorio único** dentro de `dialog_key.xml` (funcionalidad descrita en `spec.md` líneas 24 y 184).
  3. **Botón/icono explícito de copiar** en `item_key.xml` junto con confirmación visual clara (`Snackbar` o cambio de icono temporal) además del tap en la fila.
  4. **Exportar / Importar respaldo cifrado** (archivo protegido con contraseña elegida por el usuario usando AES-256-GCM + PBKDF2): dado que `allowBackup="false"` y las llaves de Android Keystore no se transfieren al cambiar de teléfono, esto evita que el usuario pierda sus llaves al cambiar o restaurar su dispositivo.

---

## 4. Prioridad Baja (Arquitectura, Mantenibilidad, Testing y Documentación)

### - [ ] T-17: Refactorizar hacia arquitectura MVVM con capa de Repositorio
- **Archivos:** `app/src/main/java/com/nairusoft/bbre/...`
- **Problema actual:** Aunque `README.md` declara "MVVM con Clean Architecture", toda la lógica de UI, validación, cifrado, sesión y almacenamiento reside en `LoginActivity` y `DashboardActivity`.
- **Mejora propuesta:** Separar responsabilidades en `model/` (`User`, `UserKey`, `UiState`), `data/` (`AuthRepository`, `KeysRepository` con `StateFlow` y `Dispatchers.IO`), y `ui/` (`LoginViewModel`, `DashboardViewModel` o `KeysFragment`/`SettingsFragment` con `Navigation` o `FragmentContainerView`).

### - [ ] T-18: Migrar `KeysAdapter` a `ListAdapter` con `DiffUtil`
- **Archivos:** `app/src/main/java/com/nairusoft/bbre/ui/KeysAdapter.kt`
- **Problema actual:** `KeysAdapter.updateData()` usa `notifyDataSetChanged()` (línea 42), re-vinculando todos los ítems sin animaciones ni cálculo diferencial.
- **Mejora propuesta:** Heredar de `androidx.recyclerview.widget.ListAdapter<UserKey, KeysAdapter.KeyViewHolder>` usando `DiffUtil.ItemCallback<UserKey>` comparando por `id` y contenido.

### - [ ] T-19: Limpiar código muerto, inicialización duplicada y manejo de fallos de Keystore
- **Archivos:** `app/src/main/java/com/nairusoft/bbre/BBreApplication.kt`, `app/src/main/java/com/nairusoft/bbre/security/SecurityManager.kt`
- **Problema actual:**
  - `BBreApplication.generateMasterKey()` crea `MasterKey` y `EncryptedSharedPreferences` pero descarta la referencia, y `SecurityManager` vuelve a crearlos en `init`.
  - `BBreApplication.generateSecureKey()` y `createNotificationChannel()` no se usan en ninguna parte.
  - `SecurityManager` tiene métodos sin uso (`verifyPin`, `validateTransferAmount`, `TransferValidationResult`, `isDebugMode`, `performSecurityCheck`, `secureWipe`) e import sin uso (`IvParameterSpec`).
  - Se usa `println(...)` en lugar de un logger desactivado en release.
  - No hay manejo de excepción si `EncryptedSharedPreferences.create` falla por corrupción de Keystore.
- **Mejora propuesta:** Unificar la inicialización de `EncryptedSharedPreferences` en un solo lugar con manejo seguro de errores, eliminar el código muerto heredado de transferencias y usar `Log` solo en builds debug.

### - [ ] T-20: Implementar pruebas unitarias y de instrumentación (JUnit + Espresso)
- **Archivos:** `app/src/test/...`, `app/src/androidTest/...` (carpetas actualmente inexistentes)
- **Problema actual:** `app/build.gradle.kts` declara `junit`, `androidx.test.ext:junit` y `espresso-core`, pero no existe ni un solo test en el proyecto.
- **Mejora propuesta:**
  - **Unit tests:** hashing/KDF y verificación de contraseñas, validaciones de formularios, lógica de unicidad/versionado de llaves, lógica de expiración de sesión de 5 minutos, serialización JSON de `User` y `UserKey`.
  - **Instrumentation tests:** flujos completos de registro, login, creación/edición/copiado/eliminación de llaves, actualización de perfil y borrado de cuenta.

### - [ ] T-21: Actualizar documentación, versionado y control de archivos en Git
- **Archivos:** `README.md`, `spec.md`, `app/build.gradle.kts`, `.gitignore`
- **Problema actual:**
  - `README.md` describe una app con transferencias de hasta `$12.110.000 COP`, QR, historial, `TransferActivity`, `QRScannerActivity`, Kotlin `1.9.20` y arquitectura MVVM que no corresponde al proyecto actual.
  - `app/build.gradle.kts` tiene `versionName = "1.0"`, mientras la app y `UserKey` muestran `"0.0.1"`.
  - `git status` muestra archivos locales/generados sin ignorar dentro de `BBreApp/` (`local.properties`, `.idea/`, `.gradle/`, `build/`, `app/build/`).
  - `logo_bbre.xml` y `logo_nairusoft.xml` contienen trazos vectoriales geométricos aproximados (las letras del vector `logo_bbre` no forman claramente "BBre").
- **Mejora propuesta:**
  1. Reescribir `README.md` reflejando la arquitectura, pantallas y propósito actual (gestor seguro local de llaves BBre), archivar o limpiar los logs de errores en `spec.md`.
  2. Sincronizar `versionName` / `BuildConfig.VERSION_NAME` con la información mostrada en la app.
  3. Añadir/ajustar `.gitignore` para excluir `local.properties`, `.gradle/`, `build/`, `app/build/` y archivos locales de `.idea/`.
  4. Revisar y pulir los logos vectoriales (`logo_bbre.xml`, `logo_nairusoft.xml`).
