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

### Pendiente
- [ ] Asistente IA (RF-16 a RF-20): ver `CLAUDE.md` sección 6.5.
- [ ] Documentar el procedimiento de respaldo de la base (RNF-08).
- [ ] Antes de un despliegue real: quitar los usuarios de demo y poner `app.datos-demo=false`.

## Pruebas

```bash
./mvnw test
```

35 pruebas con base H2 en memoria (no necesitan MySQL):
- `EntregaServiceTest`: fecha de renovación, estados de vigencia, bloqueo de duplicados, anulación auditada.
- `DotacionYTrabajadorServiceTest`: asignación por matriz, dotación completa/incompleta, DNI repetido, reingreso con historial.
- `SeguridadTest`: login obligatorio y permisos por rol.
- `ModulosWebTest`: entregas (con duplicados), historial, anulación, cese, alertas y reportes (pantalla y CSV).
