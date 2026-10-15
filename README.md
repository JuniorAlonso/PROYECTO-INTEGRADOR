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

En Windows usa `mvnw.cmd` en lugar de `./mvnw`. Para la clave de MySQL en PowerShell: `$env:DB_PASSWORD="tu_clave"`.

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

### Pendiente (para el compañero)
- [ ] **Registro de entregas** (CU-05): `EntregaController` + `templates/entregas/nueva.html`.
  Buscar trabajador por DNI → mostrar EPP de la matriz con `DotacionService.estadoDotacion(t)` →
  enviar `EntregaForm` a `EntregaService.registrar`. Si `duplicadosSinJustificar(form)` no está vacío,
  volver a mostrar el formulario pidiendo la justificación de esos EPP (modal del prototipo, Fig. 20.5).
- [ ] **Vigencia y alertas** (CU-07): `/alertas` usando `VigenciaService.alertas(idSede, estado)`, con pestañas vencidos / por vencer.
- [ ] **Historial por trabajador** (CU-06): `/historial?dni=` con periodos laborales (`PeriodoLaboralRepository`),
  entregas (`EntregaRepository`) y movimientos (`HistorialEppRepository`). Agregar ahí los botones de cese y reingreso.
- [ ] **Reportes** R-01 a R-04 con exportación (RF-11, RF-12, Anexo 22).
- [ ] Activar los enlaces del menú en `templates/fragments/layout.html` cuando cada módulo exista.
- [ ] Asistente IA (RF-16 a RF-20).
- [ ] Pruebas de los controladores nuevos (seguir el estilo de `src/test`).

## Pruebas

```bash
./mvnw test
```

19 pruebas con base H2 en memoria (no necesitan MySQL):
- `EntregaServiceTest`: fecha de renovación, estados de vigencia, bloqueo de duplicados, anulación auditada.
- `DotacionYTrabajadorServiceTest`: asignación por matriz, dotación completa/incompleta, DNI repetido, reingreso con historial.
- `SeguridadTest`: login obligatorio y permisos por rol.
