# Historial de Especificaciones — BBreApp

> Nota de archivo: las trazas históricas de compilación y Lint de `fix 0.0.1` y `fix 0.0.2` (resueltas tras restaurar `proguard-rules.pro`, limpiar actividades obsoletas `QRScannerActivity`/`TransferActivity`, migrar a `OnBackPressedDispatcher` y corregir `lint`) fueron resumidas para mantener este documento legible.

feature 0.0.2
necesito que quites el inicio de sesion (celular y la clave).
debe salir un registro sencillo (nombre de usuario y contraseña de 8 caracteres)
simplemente esa sería los datos de usuario (nombre de usuario y contraseña de 8 caracteres). registro sencillo que va a almacenar los datos en un archivo sencilo como puede ser un archivo de texto obviamente encriptado llamado usuarios.json
los datos deben estar administrado por otro archivo llamado llaves.json, el cual va a tener las llaves generadas por la aplicacion para cada usuario esto tambien debe estar protegido, ten cuidado con eso.

retira o elimina lo de saldo, no me interesa saber cuanto tengo de saldo ni conocer cuanto es el limite.
tampoco me interesa transferir, cambialo por otra cosa llamado Mis LLaves.
en el sentido de que me muestre las llaves que tiene el usuario.
elimina o retira la funcionalidad de pagos por qr o escaneame QR no me interesa.
elimina o retira la funcionalidad de historial de transacciones, no me interesa.
configuración, si me interesa solo para administrar mi perfil (nombre de usuario y contraseña) y mi llaves. 
por favor no me borres las llaves, son muy importantes para mi.
en la nueva pestaña que creas llamada: Mis LLaves necesito que me muestres las llaves que tiene el usuario. 
tambien necesito un boton de crear llave, al oprimirlo me sale una pantalla que me pregunta por el tipo de llave (Banco), Llave (cedula, celular, correo, codigo alfanumerico aleatorio que tiene que ser unico), o codigo alfanumerico.
la posibilidad de eliminar o modificar este dato.
entonces necesito solo necesito:
- el splash de inicio
-el formulario de registro (sencillo: nombre de usuario y contraseña de 8 caracteres)
-el formulario de login (sencillo: nombre de usuario y contraseña de 8 caracteres)usar la huella dactilar o el rostro (heredado de android) para ingresar.
la pantalla de mis llaves.
-el formulario de Mis llaves que me permita crear llaves, eliminarlas y modificarlas, consultarlas (por favor no borres las llaves).
la pantalla de configuración que me permita administrar mi perfil (nombre de usuario y contraseña)
-configuración, sería sencillo si necesita eliminar un usuario, modificar, eliminar o modificar un usuario registrado. si no le gusta el usuario registrado, puede eliminarlo.

fix 0.0.3
revisa en build.gradle.kts
Supplied proguard configuration does not exist: C:\Users\Usuario\Documents\GitHub\Nairu\LLave-B-Bre\BBreApp\app\proguard-rules.pro

fix 0.0.4
-revisa que aparezca en letra negra al escribir el nombre de usuario y contraseña y también en la parte de configuración.
-aumentar el tamaño de la letra y configurala mejor en el formulario de login la parte de: Ingresar
esta muy bien la parte de crear, en la parte de agregar nueva clave, es muy importante que me muestre la opcion de agregar nuevo banco. 
a parte de celular, cédula, correo electrónico y código alfanumerico sobran, deja solo el valor de la llave.
-mejora los colores, ese gris no me gusta tanto, trata de usar colores mas agradables a la vista y mas claros.
la parte de modificar también mejorala, que se parezca a la parte de crear nueva clave.
al eliminar tampoco me gusta,. modifca y toma los colres de modificar y crear llaves, unifica conceptos.
-al darle al frente de cada cual, tome como copiar el código de la llave.
-muestra la herramienta de configuración habilitada por qué aparece desahilitada.
deberia aparecer en mis llaves y configuración tendría que mostrarse una nueva 
función llamada: Información de de Llave, quien fue que la creo, como se llama, quien la creo, fecha de creación, fecha de modificación, y version más reciente.
versión 0.0.1.

fix 0.0.5
adiciona un campo llamado: descripción 
en esta queda debajo de cada banco, por ejemplo: 
Banco de la República
Descripción: 
Llave: 
tambiénn modificar en el formulario de crear llave el campo Banco de la República por el campo de: descripción

fix 0.0.6
necesito que se cierre sesion si queda abierta la sesion o 5 minutos inactivos, al igual que se cierre la sesión. 
-cuando se cierra sesion, se debe limpiar la sesion de usuario, para que no se pueda acceder sin iniciar sesion. osea que se borre todo. 
-el login siempre debe pedir el ingreso de usuario y contraseña, o huella dactilar o rostro.

feature 0.0.1
necesito que leas de este archivo
.agents\Gauntlet.md y revisalo, y hazlo por qué 
necesito modificar esta aplicación y recuerda que deseo que revises este archivo, 
y que pongas atención a este archivo:
.gitignore 
donde evito que .agents y Test sean incluidos.


feature 0.0.2
en el boton de configuración debe existir dos criterios para almacenar (guardar) y cargar la información contenida en la aplicación
la información es usuario, clave, la información almacenada, esta información debe estar encriptada para guardar y desencriptada para cargar 
debe tener criterios de seguiridad tipo AES-256.