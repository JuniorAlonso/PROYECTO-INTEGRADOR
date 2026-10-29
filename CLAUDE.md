# Contexto del proyecto EPPTrack (para Claude Code)

> Este archivo lo lee Claude Code automáticamente al abrir el repositorio. Resume el informe del curso
> (Avance 1 + Avance 2), lo que ya está programado y lo que falta, para continuar sin perder contexto.
> **Responde siempre en español.**

## 1. Qué es

**EPPTrack**: sistema web de trazabilidad y seguimiento de Equipos de Protección Personal (EPP) para
**Campos del Sur S.A. (CASURSA)**, agroexportadora de uva de mesa en Ica (RUC 20324737171).
Proyecto del curso *Integrador I: Sistemas – Software* (UTP, sección 29007, docente Jean Pierre Pacheco).
Integrantes: Alfaro Carpio, Richard Rodrigo · Canchari Bedon, Junior Alonso. Ciclo 2026-2.

### Problema que resuelve
- El registro de entregas de EPP es en papel (formato **FOR-SST 9.3.2**), disperso en 19 sedes.
- La asignación se hace cruzando a mano una **Matriz de EPP** de 17 procesos, 115 actividades y 57 tipos de EPP
  en 7 categorías → errores de asignación.
- Hay mucha rotación (754 trabajadores en temporada baja, 2 050 en campaña): al reingresar un trabajador se le
  vuelve a entregar todo porque no hay historial → entregas duplicadas.
- Ante SUNAFIL o auditorías (GLOBALG.A.P., SMETA) cuesta acreditar las entregas.

### Indicadores del informe (pretest, para comparar con el postest)
| Objetivo | Indicador | Pretest |
|---|---|---|
| Reducir tiempo de búsqueda del historial | Tiempo promedio de búsqueda | 12,66 min |
| Reducir errores de asignación | % asignaciones conformes con la matriz | 70 % |
| Reducir entregas duplicadas por reingreso | N.º de entregas duplicadas | 5 de 12 reincorporados |
| Facilitar consultas (asistente IA) | Tiempo para obtener un resumen | 31,81 min |

## 2. Stack y cómo correr

Java 21+ · Spring Boot 3.5.7 · Spring MVC + **Thymeleaf** (renderizado en servidor, no SPA) · Spring Data JPA ·
Spring Security · MySQL 8 · Maven (usar el wrapper `mvnw` / `mvnw.cmd`). Bootstrap 5.3 + Bootstrap Icons por CDN.

```bash
./mvnw spring-boot:run                                   # MySQL localhost:3306, base "epptrack" (se crea sola)
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2     # sin MySQL, base en memoria
./mvnw test                                              # 82 pruebas, usan H2 (perfil "test")
```
Credenciales MySQL por variables de entorno `DB_USER` (default `root`) y `DB_PASSWORD` (default vacío).
Con base vacía se cargan datos simulados (`config/DataInitializer.java`). Usuarios demo: ver README.

## 3. Arquitectura y convenciones

```
src/main/java/pe/utp/epptrack
├── model/        Entidades JPA = tablas del diccionario de datos (Anexo 17). Getters/setters a mano, sin Lombok.
├── repository/   DAO con Spring Data JPA
├── service/      Lógica y reglas de negocio. Errores de negocio → ReglaNegocioException (mensaje para el usuario)
├── dto/          Formularios (EntregaForm, ItemEntregaForm) y resultados (EstadoDotacion, ItemDotacion, CumplimientoSede)
├── controller/   Controladores MVC. Patrón: try { servicio } catch (ReglaNegocioException e) { flash "error" }
└── config/       SecurityConfig (roles), GlobalModelAdvice (rutaActual para el menú), DataInitializer
src/main/resources/templates
├── fragments/layout.html   Layout común. Cada página: th:replace="~{fragments/layout :: layout(~{::title}, ~{::section})}"
└── <modulo>/*.html
```
Convenciones:
- Código, nombres y textos de la UI **en español**. Comentarios de clase indican el RF/RN que implementan.
- Mensajes al usuario con flash attributes `exito` / `error` (el layout ya los muestra).
- Inyección por constructor. `@Transactional` en servicios.
- Colores (Anexo 21): azul institucional `#16325C` (menú), azul acción `#2563B0` (botones), semáforo vigencia:
  verde `#1F8A5B`, ámbar `#B7740A`, rojo `#C0392B`. Ya están en `static/css/epptrack.css`;
  `EstadoVigencia.getColor()` devuelve la clase Bootstrap (`success`/`warning`/`danger`) → `badge text-bg-{color}`.
- Ninguna entrega se borra (RNF-03): las correcciones van por `EntregaService.anularDetalle` (queda en CORRECCION_ENTREGA).

### Roles (RF-15) — `config/SecurityConfig.java`
| Rol | Puede |
|---|---|
| ADMIN | Todo; único que edita catálogo de EPP y matriz |
| SUPERVISOR | Entregas, alertas, reportes, historial (`/entregas/**`, `/alertas/**`, `/reportes/**` ya están restringidos a ADMIN y SUPERVISOR) |
| RRHH | Trabajadores (ingreso, cese, reingreso) e historial |

## 4. Modelo de datos (resumen)

SEDE, PUESTO, TRABAJADOR (dni único, estado ACTIVO/REINCORPORADO/CESADO), PERIODO_LABORAL (una fila por campaña),
PROCESO → ACTIVIDAD, ASIGNACION_ACTIVIDAD (actividad vigente = `fecha_fin` null), CATEGORIA_EPP → EPP (`vida_util_dias`),
MATRIZ_ASIGNACION (actividad + EPP + obligatorio + cantidad), ROL → USUARIO (BCrypt),
ENTREGA (cabecera: trabajador, sede, usuario responsable, fecha, motivo) → DETALLE_ENTREGA (EPP, cantidad,
`fecha_renovacion`, `estado_vigencia`, `justificacion_duplicado`), HISTORIAL_EPP (bitácora de movimientos),
CORRECCION_ENTREGA (auditoría de anulaciones).

`EstadoVigencia`: VIGENTE, POR_VENCER (≤ 15 días, configurable `app.alertas.dias-por-vencer`), VENCIDO,
RENOVADO (reemplazado por una entrega nueva), ANULADO. Se recalcula a diario y al iniciar (`VigenciaService.actualizarEstados`).
`MotivoEntrega`: INGRESO, REINGRESO, RENOVACION, DESGASTE, PERDIDA, CAMBIO_ACTIVIDAD.

## 5. Ya hecho

- Login, roles, layout con menú lateral.
- **Dashboard** (`/`): KPIs (trabajadores activos, % dotación completa, por vencer, vencidos), cumplimiento por sede
  con meta 90 %, entregas y duplicados por mes, alertas prioritarias, filtro por sede.
- **Trabajadores** (`/trabajadores`): lista con filtros y badge de dotación, alta (con campaña, fecha de ingreso y actividad), edición.
  Endpoints POST listos sin pantalla: `/trabajadores/{id}/cese` (fechaCese) y `/trabajadores/{id}/reingreso`
  (campana, fechaIngreso, idSede, idActividad).
- **Catálogo de EPP** (`/epp`) y **Matriz de asignación** (`/matriz`).
- **Servicios listos para usar** (probados en `src/test`):
  - `DotacionService.estadoDotacion(trabajador)` → EPP que exige la matriz, cuáles tiene en uso y faltantes (RF-04, RF-10).
    `estadoDotacionTodos(idSede)` y `cumplimientoPorSede(...)` para listados y reportes.
  - `EntregaService.enUsoPorEpp(idTrabajador)`, `duplicadosSinJustificar(form)`, `registrar(form, usuario)`,
    `anularDetalle(idDetalle, motivo, usuario)` (RF-05, RF-06, RF-07, RF-13).
  - `VigenciaService.alertas(idSede, estado)` y `renovacionesHasta(dias, idSede, idCategoria)` (RF-09, R-03).
  - `TrabajadorService.registrarCese(...)`, `registrarReingreso(...)`, `buscarPorDni(...)` (RN-04).

## 6. Pendiente

> **Actualización:** 6.1 a 6.4 ya están implementados (`EntregaController`, `AlertaController`, `HistorialController`,
> `ReporteController`/`ReporteService`) con pruebas en `ModulosWebTest`. Se conservan abajo como especificación.
> Lo que sigue pendiente es 6.5 (Asistente IA) y 6.6.

Al terminar cada módulo: activar su enlace en `templates/fragments/layout.html` (hoy están como `nav-link disabled`),
agregar pruebas en `src/test` y correr `./mvnw test`.

### 6.1 Registro de entregas — CU-05, RF-04 a RF-07 (`/entregas/nueva`)
Flujo del prototipo (Anexo 20, figs. 20.4–20.5): buscar trabajador por DNI → ver sus datos, actividad y EPP sugeridos
→ marcar EPP y cantidades → registrar.
- `GET /entregas/nueva?dni=...`: con `TrabajadorService.buscarPorDni`, armar un `EntregaForm` con un `ItemEntregaForm`
  por cada EPP de `DotacionService.estadoDotacion(t).items()` (preseleccionar los faltantes, cantidad = requerida),
  más el resto del catálogo activo en una sección "Otros EPP" sin preseleccionar.
  Mostrar por cada EPP si ya lo tiene vigente y hasta cuándo (`EntregaService.enUsoPorEpp`).
  Mostrar la fecha de renovación que se calculará (fecha + vida útil).
- `POST /entregas`: si `duplicadosSinJustificar(form)` no está vacío → volver al formulario mostrando un **modal de
  advertencia** que exige justificación para esos EPP (campo `items[i].justificacion`). Si no → `registrar(form, principal.getName())`,
  flash `exito` y redirigir al historial del trabajador.
- Binding de la lista en Thymeleaf: `th:field="*{items[__${i.index}__].seleccionado}"`, idem `idEpp`, `cantidad`, `justificacion`.
- Rechazar trabajadores CESADOS (el servicio ya lanza la excepción; mostrar mensaje y enlace a reingreso).

### 6.2 Control de vigencia y alertas — CU-07, RF-09 (`/alertas`)
Pestañas **Vencidos / Próximos a vencer / Vigentes** (Anexo 19 fig. 19.5, Anexo 21 fig. 21.7), filtro por sede,
columnas: trabajador, DNI, sede, EPP, fecha de entrega, fecha de renovación, días restantes, estado (badge).
Acción "Renovar" → `/entregas/nueva?dni=...`. Usar `VigenciaService.alertas(idSede, estado)`.

### 6.3 Historial por trabajador — CU-06, RF-08, RN-04 (`/historial`)
Búsqueda por DNI o nombre (Anexo 20 fig. 20.6). Mostrar: ficha del trabajador; estado de dotación actual;
**periodos laborales por campaña** (`PeriodoLaboralRepository`); entregas con sus detalles y estado
(`EntregaRepository.findByTrabajador...`); bitácora (`HistorialEppRepository`) y correcciones.
Botones: Nueva entrega, Registrar cese (RRHH/ADMIN), Registrar reingreso (formulario con campaña, fecha, sede, actividad),
Anular un detalle (SUPERVISOR/ADMIN, pide motivo → `EntregaService.anularDetalle`; crear `POST /entregas/detalle/{id}/anular`).
Requisito RNF-01: responder en < 3 s. Al terminar, los controladores de trabajadores pueden redirigir aquí en vez de a `/trabajadores`.
Habilitar `/historial` para los tres roles (ya cae en `anyRequest().authenticated()`).

### 6.4 Reportes — CU-08, RF-11, RF-12 (`/reportes`) — Anexo 22
Todos con encabezado institucional (código de reporte, fecha y usuario que lo genera) y pie:
"Los registros de entrega no pueden eliminarse" (valor probatorio). Exportar a **PDF y Excel**
(sugerencia: OpenPDF o vista imprimible para PDF; Apache POI o CSV con `;` y BOM UTF-8 para Excel).
| Código | Reporte | Filtros | Fuente |
|---|---|---|---|
| R-01 | Cumplimiento de dotación por trabajador | sede, periodo, campaña, estado | `DotacionService.estadoDotacionTodos` |
| R-02 | Cumplimiento de dotación por sede y periodo | agrupación, rango de meses | `cumplimientoPorSede` |
| R-03 | Vigencias y renovaciones de EPP | sede, horizonte de días, categoría | `VigenciaService.renovacionesHasta` |
| R-04 | Constancia de entrega por trabajador (equivalente al FOR-SST 9.3.2) | trabajador, campañas, rango de fechas | `EntregaRepository` |

### 6.5 Asistente IA — RF-16 a RF-20, RNF-09 a RNF-12
- Botón flotante 58×58 px a 24 px de los bordes inferior y derecho, en **todas** las pantallas internas (va en el layout).
  Abre un panel de 400×600 px sobre la pantalla actual sin navegar: encabezado (título, rol del usuario, nueva conversación,
  cerrar), área de conversación con scroll y campo de consulta con botón enviar.
- Consultas en lenguaje natural sobre los datos de EPPTrack: EPP vencidos/por vencer, dotación incompleta, entregas,
  historial; respuestas como texto, lista, tabla o resumen ejecutivo. Mensaje inicial con sugerencias.
- **Solo debe responder con datos del sistema** (RNF-10) y **respetar el rol** del usuario (RNF-09): si pide algo fuera
  de su permiso, decirlo sin mostrar datos. Cada respuesta indica su fuente y fecha de corte.
  Botones en la respuesta: Copiar, Generar reporte, Descargar, Volver a consultar.
- Implementación sugerida: endpoint `POST /asistente` que arma un contexto con los datos que el rol puede ver
  (usando los servicios existentes) y llama a la API de Claude (Anthropic) con herramientas/funciones que consultan
  los servicios; la API key por variable de entorno, nunca en el repositorio.

### 6.6 Otros
- RNF-08: documentar procedimiento de respaldo (p. ej. `mysqldump` programado) en el README.
- Opcional: importar la Matriz de EPP desde Excel (riesgo identificado en el informe).
- Pruebas de los nuevos controladores con MockMvc (ver `SeguridadTest`).

## 7. Reglas de negocio (no romperlas)

- **RN-01** Todo trabajador debe contar con los EPP que la matriz establece para su actividad.
- **RN-02** Toda entrega registra obligatoriamente al responsable (usuario autenticado).
- **RN-03** Fecha de renovación = fecha de entrega + vida útil del EPP.
- **RN-04** Un trabajador que se reincorpora conserva su historial (mismo registro TRABAJADOR, nuevo PERIODO_LABORAL; nunca crear otro trabajador con el mismo DNI).
- **RN-05** Los registros de entrega no se eliminan; toda corrección queda registrada.
- **RF-07** Entregar un EPP que el trabajador aún tiene vigente exige justificación; el anterior pasa a RENOVADO.
- **RNF-02** Contraseñas con BCrypt. **RNF-03** Nada de `delete` sobre ENTREGA / DETALLE_ENTREGA / HISTORIAL_EPP.
- Datos de prueba siempre ficticios (protección de datos personales, Ley N.° 29733).
