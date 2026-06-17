# BBre - Sistema de Pagos Inmediatos Colombia

**Desarrollado por NairuSoft**

## Descripción

BBre es el nuevo sistema de pagos inmediatos en Colombia, desarrollado por el Banco de la República. Esta aplicación permite enviar y recibir dinero al instante, todos los días y a cualquier hora, sin importar el banco y de forma gratuita.

## Características Principales

### 🔐 Seguridad Bancaria
- **Autenticación Biométrica**: Huella digital y reconocimiento facial
- **Encriptación AES-256-GCM**: Todos los datos sensibles están encriptados
- **Android Keystore**: Las claves se almacenan en el hardware seguro del dispositivo
- **PIN de Seguridad**: Autenticación de dos factores
- **EncryptedSharedPreferences**: Almacenamiento seguro de credenciales

### 💰 Funcionalidades BBre
- **Transferencias Inmediatas**: Envía dinero en segundos usando llaves
- **Llaves de Identificación**: 
  - Número de celular
  - Cédula de ciudadanía
  - Correo electrónico
  - Código alfanumérico aleatorio
- **Límite de Transferencia**: Hasta $12.110.000 COP por transacción
- **Códigos QR**: Paga escaneando códigos QR BBre
- **Historial de Transacciones**: Consulta todas tus operaciones

### 🎨 Interfaz Moderna
- Material Design 3
- Temas personalizados BBre y NairuSoft
- Animaciones suaves
- Experiencia de usuario intuitiva

## Estructura del Proyecto

```
BBreApp/
├── app/
│   ├── src/main/
│   │   ├── java/com/nairusoft/bbre/
│   │   │   ├── BBreApplication.kt      # Clase principal de la app
│   │   │   ├── security/
│   │   │   │   └── SecurityManager.kt  # Gestión de seguridad
│   │   │   ├── ui/
│   │   │   │   ├── SplashActivity.kt   # Pantalla de inicio
│   │   │   │   ├── LoginActivity.kt    # Login con biometría
│   │   │   │   ├── DashboardActivity.kt # Panel principal
│   │   │   │   ├── TransferActivity.kt # Transferencias
│   │   │   │   └── QRScannerActivity.kt # Escáner QR
│   │   │   ├── data/                   # Capa de datos
│   │   │   └── model/                  # Modelos de datos
│   │   └── res/
│   │       ├── layout/                 # Diseños XML
│   │       ├── drawable/               # Recursos gráficos
│   │       ├── values/                 # Colores, strings, temas
│   │       └── xml/                    # Configuraciones de seguridad
│   └── build.gradle.kts
├── build.gradle.kts
└── settings.gradle.kts
```

## Requisitos Técnicos

- **SDK Mínimo**: Android 8.0 (API 26)
- **SDK Objetivo**: Android 14 (API 34)
- **Lenguaje**: Kotlin 1.9.20
- **Arquitectura**: MVVM con Clean Architecture

## Dependencias Principales

- **AndroidX Core**: Componentes básicos de Android
- **Material Design**: Componentes UI modernos
- **Security Crypto**: Encriptación segura
- **Biometric**: Autenticación biométrica
- **Navigation**: Navegación entre pantallas
- **Coroutines**: Programación asíncrona
- **DataStore**: Almacenamiento seguro de preferencias

## Configuración de Seguridad

### Permisos Requeridos
```xml
<uses-permission android:name="android.permission.USE_BIOMETRIC" />
<uses-permission android:name="android.permission.USE_FINGERPRINT" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.CAMERA" />
```

### Network Security
- HTTPS obligatorio (cleartext traffic deshabilitado)
- Certificate pinning para APIs del Banco de la República
- Data extraction rules para prevenir backup de datos sensibles

### ProGuard Rules
- Ofuscación de código habilitada en release
- Reglas específicas para mantener clases de seguridad

## Cómo Compilar

1. Clonar el repositorio
2. Abrir en Android Studio Arctic Fox o superior
3. Sincronizar Gradle
4. Ejecutar en emulador o dispositivo físico

```bash
./gradlew assembleDebug
```

## Consideraciones de Seguridad

⚠️ **Importante**: Esta aplicación maneja información financiera sensible. Asegúrate de:

1. No hacer commit de claves API o certificados reales
2. Usar siempre conexiones HTTPS
3. Validar certificados del servidor
4. Implementar certificate pinning en producción
5. Realizar auditorías de seguridad periódicas
6. Cumplir con regulaciones bancarias colombianas

## Información Legal

- Desarrollado por **NairuSoft**
- Sistema BBre del **Banco de la República de Colombia**
- Sujeto a regulaciones financieras colombianas

## Contacto

Para más información sobre BBre, visita el sitio oficial del Banco de la República.

---

© 2024 NairuSoft. Todos los derechos reservados.
