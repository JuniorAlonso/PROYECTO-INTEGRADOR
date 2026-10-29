# EPPTrack

Sistema web de trazabilidad y seguimiento de Equipos de Protección Personal (EPP) para Campos del Sur S.A.
Proyecto Integrador I — UTP, sección 29007. Alfaro Carpio, Richard Rodrigo · Canchari Bedon, Junior Alonso.

> **¿Continúas el proyecto con Claude Code?** Abre esta carpeta con Claude: lee automáticamente
> [CLAUDE.md](CLAUDE.md), que resume el informe, lo que ya está hecho y los pasos pendientes en orden.
> Puedes empezar diciéndole: *"Lee CLAUDE.md y continúa con el módulo de registro de entregas"*.

**Stack:** Java 21+ · Spring Boot 3.5 · Spring MVC + Thymeleaf · Spring Data JPA · Spring Security · MySQL 8 · Maven.

## Cómo ejecutar

Requisitos: JDK 21 o superior y MySQL corriendo en `localhost:3306` (la base `epptrack` se crea sola).

```bash
# con MySQL (usuario root sin clave por defecto; si tienes clave, define DB_PASSWORD)
./mvnw spring-boot:run

# sin MySQL, con base en memoria (se borra al cerrar)
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2
```

Si en Mac/Linux sale "permission denied": `chmod +x mvnw`. En Windows usa `mvnw.cmd` en lugar de `./mvnw`. Para la clave de MySQL en PowerShell: `$env:DB_PASSWORD="tu_clave"`.

Abrir http://localhost:8080. La primera vez se cargan datos simulados (`config/DataInitializer.java`).

Usuarios de prueba (solo datos de demostración):

| Usuario      | Contraseña | Rol                                   |
|--------------|------------|---------------------------------------|
| `admin`      | `admin123` | Administrador del sistema             |
| `supervisor` | `super123` | Supervisor de seguridad               |
| `rrhh`       | `rrhh123`  | Recursos Humanos                      |

## Arquitectura (MVC + DAO)

```
pe.utp.epptrack
├── model/        Entidades JPA = tablas del diccionario de datos (Anexo 17)
├── repository/   DAO con Spring Data JPA
├── service/      Reglas de negocio (RN-01 a RN-05)
├── dto/          Objetos para formularios y cálculos
├── controller/   Controladores MVC
└── config/       Seguridad, datos demo
templates/        Vistas Thymeleaf (layout común en fragments/layout.html)
```

## Estado del avance

### Hecho
- [x] Modelo completo de base de datos (16 tablas) — `model/`
- [x] Repositorios — `repository/`
- [x] Login con contraseñas BCrypt y permisos por rol (RF-14, RF-15, RNF-02) — `config/SecurityConfig.java`
- [x] Dashboard con KPIs, cumplimiento por sede, entregas por mes y alertas (Anexo 23)
- [x] Gestión de trabajadores: listar, filtrar, registrar, editar (RF-01)
- [x] Catálogo de EPP con categoría y vida útil (RF-02)
- [x] Matriz de asignación por actividad (RF-03)
- [x] **Lógica de negocio ya programada** (falta solo su pantalla):
  - `DotacionService`: EPP que corresponden a un trabajador según la matriz y faltantes (RF-04, RF-10)
  - `EntregaService.registrar(...)`: registra entrega, calcula fecha de renovación, bloquea duplicados sin justificación (RF-05, RF-06, RF-07)
  - `EntregaService.anularDetalle(...)`: corrección auditada sin borrar (RN-05, RF-13)
  - `VigenciaService`: estados vigente / por vencer / vencido y alertas (RF-09)
  - `TrabajadorService.registrarCese(...)` y `registrarReingreso(...)` (RN-04); los endpoints POST ya existen en `TrabajadorController`
- [x] Pruebas automáticas de servicios y seguridad (ver abajo)

- [x] **Registro de entregas** (CU-05, RF-04 a RF-07): buscar por DNI, EPP de la matriz preseleccionados, advertencia y justificación de duplicados — `EntregaController`, `templates/entregas/`
- [x] **Vigencia y alertas** (CU-07, RF-09): pestañas vencidos / por vencer / vigentes, filtro por sede y botón Renovar — `AlertaController`
- [x] **Historial por trabajador** (CU-06, RF-08, RN-04): campañas, entregas, bitácora, correcciones; botones de nueva entrega, cese, reingreso y anulación — `HistorialController`
- [x] **Reportes R-01 a R-04** (RF-11, RF-12): en pantalla, imprimir/PDF desde el navegador y exportación a Excel (CSV) — `ReporteController`, `ReporteService`
- [x] Menú lateral activo según el rol del usuario

- [x] **Asistente IA** (RF-16 a RF-20): botón flotante y panel de chat en todas las pantallas; responde solo con datos del sistema y según el rol — `AsistenteController`, `AsistenteService`, `ClaudeClient`
- [x] Respaldo de la base (RNF-08), Docker y CI (ver abajo)

### Motor de decisiones (APF3, rama `apf3-motor-decisiones`)
- [x] **Evaluación automática de dotación** (`MotorDotacionService`): por cada EPP que exige la matriz indica si está
  cubierto, incompleto (cantidad), vencido o nunca entregado, con la regla aplicada y el porqué; calcula la
  **dotación neta** (solo lo que falta) y avisa en reincorporaciones que confirme lo que el trabajador conserva.
  Se ve en Historial y en Registro de entregas.
- [x] **EPP no exigido por la matriz** (RF-23, RN-06): entregarlo exige justificación; queda guardada.
- [x] **Versionado de la matriz** (RF-21): Matriz → Publicar versión; cada entrega guarda la versión aplicada y la
  publicación muestra reglas agregadas/retiradas/modificadas y trabajadores afectados.
- [x] **Posibles fichas duplicadas** (RF-27): mismo nombre (sin tildes) o mismos apellidos y fecha de nacimiento.
- [x] **Bitácora de auditoría con huella SHA-256 encadenada** (RF-28): menú Auditoría; detecta alteraciones directas en la base.
- [x] **Exportación a Excel nativo (.xlsx) con Apache POI** (RF-12), con la huella de auditoría al pie.
- Pruebas: `MotorDecisionesTest` (PR-01 a PR-13) y `MotorWebTest` (PR-14 a PR-17). Total: 82 pruebas.

### Pendiente
- [ ] Antes de un despliegue real: quitar los usuarios de demo y poner `app.datos-demo=false`.

## Asistente IA

Usa la API de Claude. Definir la clave **solo** por variable de entorno (nunca en el repositorio):

```bash
export ANTHROPIC_API_KEY=...        # PowerShell: $env:ANTHROPIC_API_KEY="..."
```

Sin la clave el asistente responde que no está configurado. Modelo configurable con `ASISTENTE_MODELO`.
Cada consulta envía a la API un resumen de datos acotado a lo que el rol puede ver (RRHH no recibe vigencias ni
entregas), incluye nombres y DNI de trabajadores: revisar con la empresa la base legal de este envío (Ley N.° 29733)
antes de usarlo con datos reales. Límite de 10 consultas por minuto por usuario.

## Despliegue con Docker

```bash
cp .env.example .env     # completar contraseñas; DATOS_DEMO=false
docker compose up -d --build
```

La aplicación corre como usuario sin privilegios y se publica solo en `127.0.0.1:8080`; poner Nginx con HTTPS delante
y mantener `COOKIE_SECURE=true`. Las cabeceras de seguridad (CSP, HSTS, X-Frame-Options, etc.) las envía la aplicación.

## Respaldo de la base de datos (RNF-08)

`scripts/respaldo.sh` genera un volcado comprimido de MySQL en `./backups` y conserva los últimos 30:

```bash
sh scripts/respaldo.sh
# programado cada día a las 02:00 (crontab -e):
0 2 * * * cd /ruta/a/epptrack && sh scripts/respaldo.sh >> backups/respaldo.log 2>&1
```

Restaurar: `gunzip -c backups/epptrack-AAAAMMDD-HHMMSS.sql.gz | docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot epptrack'`.
Copiar los respaldos fuera del servidor (otra sede o almacenamiento en la nube) y probar la restauración periódicamente.

## Seguridad y configuración

- Contraseñas con BCrypt; cuenta bloqueada 15 min tras 5 intentos fallidos; sesión de 30 min sin actividad.
- Variables de entorno: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `DATOS_DEMO` (`false` en un despliegue real
  para no crear los usuarios de demostración) y `THYMELEAF_CACHE` (`true` en producción).
- Cabeceras: CSP restrictiva (sin scripts inline), HSTS, X-Frame-Options DENY, Referrer-Policy y Permissions-Policy.
  Cookie de sesión `HttpOnly`, `SameSite=Strict` y `Secure` con `COOKIE_SECURE=true`.
- Las fechas de vigencia usan la hora de Lima aunque el servidor esté en otra zona.

## Pruebas

```bash
./mvnw test
```

51 pruebas con base H2 en memoria (no necesitan MySQL):
- `EntregaServiceTest`: fecha de renovación, estados de vigencia, bloqueo de duplicados, anulación auditada.
- `DotacionYTrabajadorServiceTest`: asignación por matriz, dotación completa/incompleta, DNI repetido, reingreso con historial.
- `SeguridadTest`: login obligatorio y permisos por rol.
- `LoginBloqueoTest`: bloqueo de la cuenta tras 5 intentos fallidos.
- `ModulosWebTest`: entregas (con duplicados), historial, anulación, cese, alertas y reportes (pantalla y CSV).
