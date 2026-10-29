# EPPTrack APF3 — notas técnicas de la rama `apf3-mejora-final`

Documento técnico del repositorio (no es un documento académico). Todo lo que aparece como «probado» se ejecutó
con JDK 21 y H2 en memoria; **no se probó MySQL real, rendimiento, el asistente con API externa ni un despliegue**.
Los datos de demostración son ficticios.

## 1. Reglas de negocio (código → regla)

| Regla | Dónde | Qué hace |
|---|---|---|
| RN-01 dotación en unidades | `DotacionService`, `ItemDotacion.enPoder` | La dotación suma todas las unidades vigentes de un EPP; una entrega parcial no la completa. |
| RF-07 duplicado | `EntregaService.evaluarLineas` | Si ya tiene vigente todo lo exigido, entregar de nuevo exige justificación y el anterior pasa a RENOVADO. |
| RN-06 no exigido | idem | EPP que la matriz no exige para su actividad: justificación obligatoria. |
| RN-08 exceso | idem | Entregar más de lo que falta exige justificación. Un **complemento** (entregar lo que falta) se suma sin reemplazar lo vigente. |
| Regla congelada | `DetalleEntrega.cantidadExigida` | Cada línea guarda lo que la matriz exigía al entregar; editar la matriz no reescribe la historia. |
| Clasificación | `ClaseEpp`, `Epp` | Desechable / reutilizable individual / reutilizable compartido, con política configurable por EPP (`devolucionPermitida`, `inspeccionAlDevolver`). Sin clase = se trata como reutilizable individual. |
| Devoluciones | `InventarioService.devolver` | Motivo y condición obligatorios. Un desechable queda CONSUMIDO y **no se devuelve**. Un EPP individual pasa por inspección antes de reasignarse. |
| Concurrencia | `EppRepository.findByIdParaActualizar` | Bloqueo por EPP al ingresar y asignar unidades: sin códigos repetidos ni doble asignación. |

**Pendiente de validación por SST (no inventado):** la clase de cada EPP, qué se devuelve, qué requiere inspección y
los criterios de habilitación. Todo EPP nuevo queda «pendiente de validación» hasta marcar `claseValidada`. La clasificación
de los EPP de demostración (`DataInitializer`) es solo un ejemplo ficticio.

## 2. Funciones nuevas

- **Vista previa del impacto de la matriz** (`/matriz/previa`): reglas agregadas/retiradas/modificadas, actividades y trabajadores
  afectados, cuántos tienen hoy dotación incompleta, y una sección «No evaluable». No crea versión ni cambia la matriz.
  Publicar (solo ADMIN, `POST /matriz/publicar`) exige confirmar. Comparación de versiones: `/matriz/versiones/{id}`.
- **Panel de excepciones** (`/excepciones`): 10 tipos, sin doble conteo, con enlace al registro de origen.
- **Indicadores verificables** (`/indicadores`): cada uno con fórmula, fuente, periodo, numerador/denominador y tratamiento de datos
  faltantes. «Tiempo de operación» figura como **no medido**. No se compara con el pretest (procedencia por confirmar).
- **Auditoría ampliada:** catálogo de EPP (antes→después), inventario (ingreso, devolución, mantenimiento), ediciones de la matriz,
  y cambios de trabajadores (de DNI/nombres/nacimiento solo se anota que cambiaron).
- **Errores de parámetros:** página 400 clara en lugar de la respuesta técnica de Spring.
- **Asistente:** sin clave es una *consulta guiada por palabras clave* (no IA) y ahora puede explicar las reglas implementadas;
  con clave usa la API de Claude con la sección REGLAS como único origen de «políticas» (esta ruta no se probó con API real).
- **Interfaz:** identidad azul petróleo, migas de pan, dashboard con definiciones por indicador, badges con icono y texto,
  fichas en móvil, menú con los módulos nuevos.

## 3. Límites conocidos (honestos)

- La cadena SHA-256 de auditoría detecta alteraciones parciales; **no es inmutable** frente a quien controla la base de datos.
- Los usuarios de demostración tienen contraseña conocida: usar `DATOS_DEMO=false` fuera de la demo. La cookie de sesión solo es
  `Secure` con `COOKIE_SECURE=true`.
- El bloqueo de login es por usuario y en memoria: se puede bloquear una cuenta a propósito (decisión de seguridad pendiente).
- La evaluación de dotación y los indicadores recorren los datos en memoria: sin medición de rendimiento con volumen real.
- En la pantalla de entregas, la barra de acción es `sticky`; las capturas de página completa la pintan fuera de lugar (artefacto).
- Quedan sin rediseñar a fondo: formulario de trabajadores, radar y detalle de unidad (solo heredan los estilos globales).

## 4. Guion de demostración (≈7 min, datos ficticios)

Usuario `supervisor` (clave de demostración del README); `admin` para publicar la matriz.

| Min | Pantalla | Qué mostrar |
|---|---|---|
| 0:00 | Dashboard | KPIs con su definición, alertas con «Renovar», accesos rápidos según rol. |
| 1:00 | Historial de un trabajador | Tarjeta de evaluación: qué exige la matriz, qué tiene, qué falta y **por qué** (regla y versión de matriz). |
| 2:15 | Registro de entregas | Columnas Exigido / En poder / Falta. Entregar 1 de 2 → «parcial». Entregar más de lo que falta o un EPP no exigido → bloqueo con motivo; con justificación se registra. |
| 3:30 | Excepciones | Una excepción (p. ej. entrega incompleta) → «Resolver» lleva al registro de origen. |
| 4:15 | Matriz (admin) | Cambiar una cantidad → «Ver impacto antes de publicar»: diferencias y trabajadores afectados; cancelar sin cambios; (opcional) publicar. |
| 5:15 | Auditoría | Eventos de la matriz y de entregas; «cadena íntegra» y su alcance real. |
| 5:45 | Indicadores y reporte | Fórmula + numerador/denominador; «No medido»; exportar R-01 a Excel. |
| 6:30 | Devoluciones | Intentar devolver un desechable → «no corresponde devolverlo»; la política está pendiente de validación SST. |

## 5. Preguntas difíciles previstas

- *¿Quién validó las clases de EPP?* Nadie: están marcadas «pendiente de validación SST» y el sistema aplica la política conservadora.
- *¿El 97 % de conformidad prueba mejora?* No: son datos ficticios y no hay medición ni línea base validada.
- *¿Es inmutable la auditoría?* No; detecta alteraciones parciales, un administrador de la base podría recalcular la cadena.
- *¿Funciona con MySQL real / con 2 000 trabajadores?* No se ha comprobado.
