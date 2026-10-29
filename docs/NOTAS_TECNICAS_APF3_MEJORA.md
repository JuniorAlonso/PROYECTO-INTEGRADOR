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
| Identificación | `UnidadEpp`, `InventarioService.ingresar` | Cada unidad física reutilizable tiene un código único (`EPP-<id del tipo>-<correlativo>`), distinto del tipo de EPP. Se busca por código (sin distinguir mayúsculas), tiene ficha con historial de movimientos y etiqueta imprimible lista para QR/código de barras (no se generan QR: no hay librería). |
| Devolución → inspección → autorización | `InventarioService.devolver / registrarInspeccion / autorizarRetorno` | Una unidad devuelta pasa a *pendiente de inspección* (no cuenta como disponible). Resultados: apta (→ pendiente de autorización), requiere mantenimiento, cuarentena o baja. Solo una autorización humana la devuelve a *disponible*; nunca se reasigna sola. Los criterios de SST para reutilizar EPP críticos están **pendientes de validación**. |
| Consumibles | `LoteService`, `LoteConsumible` | Los desechables se controlan por lotes (saldo, vencimiento, consumo por entrega, FEFO); no tienen unidades ni devolución. Anular la entrega restituye el saldo. |
| Mantenimiento | `RegistroMantenimiento` | Diagnóstico, responsable, fecha y costo. Reparado → pendiente de autorización; no reparable → baja. El ahorro **realizado** (compra evitada por reasignación) se separa del **potencial**, que figura «no calculable» mientras no exista costo estimado de reparación. |
| Inventario según el personal | `NecesidadStockService` (`/inventario/necesidad`) | Necesidad = pendiente de la matriz + renovaciones a 30 días (estimación); disponible = solo unidades en almacén o saldo de lotes. Mantenimiento, cuarentena, inspección y baja no cuentan. Tallas: dato faltante. |
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
- **Migración MySQL no probada:** `scripts/migracion_apf3_inventario.sql` (solo `CREATE`/`ALTER`, sin borrados) se escribió a mano y no se ejecutó en ningún motor. Una base MySQL existente la necesita para los estados y tablas nuevos; hay que ensayarla en una copia.
- El bloqueo de login es por usuario y en memoria: se puede bloquear una cuenta a propósito (decisión de seguridad pendiente).
- La evaluación de dotación y los indicadores recorren los datos en memoria: sin medición de rendimiento con volumen real.
- En la pantalla de entregas, la barra de acción es `sticky`; las capturas de página completa la pintan fuera de lugar (artefacto).
- Quedan sin rediseñar a fondo: formulario de trabajadores, radar y detalle de unidad (solo heredan los estilos globales).

## 4. Guion de demostración (≈7 min, datos ficticios)

Los pasos A–F se ejecutaron en vivo contra la app (H2, datos ficticios) el 29/10/2026; los resultados citados son los observados.
Reinicie la app antes de ensayar para volver a los datos limpios. Usuarios de demostración del README: `supervisor`, `admin`.
Trabajador ficticio usado: **Huarcaya Espinoza, Jorge Luis, DNI 70345128** (actividad «Aplicación con mochila»).

| Min | Pantalla (usuario) | Acción exacta | Qué debe verse |
|---|---|---|---|
| 0:00 | Dashboard (`supervisor`) | Abrir `/`. | KPIs con su definición, alertas con «Renovar», accesos rápidos. |
| 1:00 | Historial (`supervisor`) | Buscar DNI **70345128**. | Tarjeta «Evaluación automática»: matriz `demo-1`; Cartuchos 0 de 2 y Respirador 0 de 1 como **faltantes**, con la regla aplicada. |
| 2:00 | Registro de entregas (`supervisor`) | DNI 70345128 → marcar solo *Cartuchos para vapores orgánicos*, cantidad **1** → Registrar. | Se registra como **parcial**: el historial pasa a «1 de 2» y el panel de excepciones cuenta **Entrega incompleta = 1** (antes 0). |
| 2:45 | Registro de entregas | Marcar *Cartuchos* con cantidad **3** (falta 1) → Registrar. | **Bloqueo** «No se registró: algunas cantidades superan lo que falta según la matriz (RN-08)»; la fila queda resaltada para justificar. |
| 3:15 | Registro de entregas | Marcar *Cartuchos* con cantidad **1** → Registrar. | Se **suma** a lo anterior: «Cubierto 2/2» y Entrega incompleta vuelve a 0. |
| 3:45 | Excepciones (`supervisor`) | Abrir *Excepciones* y filtrar «EPP faltante». | Casos reales (Jorge: Respirador; otras personas ficticias) con «Resolver / Ver» hacia el origen. |
| 4:30 | Matriz (`admin`) | *Matriz de asignación* → «Aplicación con mochila» → agregar *Casco de seguridad* ×1 obligatorio → «Ver impacto antes de publicar». | Regla **agregada** (Casco ×1), trabajador afectado (Jorge) y «1 tienen hoy la dotación incompleta»; sección **No evaluable**; la versión publicada sigue siendo `demo-1`. Para volver atrás: «Cancelar y volver» y quitar la regla en la matriz (la edición de trabajo ya queda registrada). |
| 5:30 | Auditoría | Abrir *Auditoría*. | Eventos `ENTREGA_REGISTRADA` y `MATRIZ_REGLA_AGREGADA`; «Cadena íntegra» (130 registros en el ensayo) y la nota sobre su alcance real. |
| 6:00 | Indicadores | Abrir *Indicadores*. | Cada cifra con fórmula, numerador, denominador y periodo; «Tiempo de operación: No medido». |
| 6:30 | Reporte | *Reportes* → R-01 → Excel. | Archivo `.xlsx` con encabezado y la huella de auditoría al pie. |

Valores de referencia observados en el ensayo (datos ficticios, **no son resultados**): al iniciar, Entrega incompleta 0, EPP faltante 7,
EPP vencido 9, por vencer 11; dotación completa 4/11.

### Recorrido adicional: identificación, devoluciones, inventario y mantenimiento (≈3 min)

Verificado en vivo el 29/10/2026 con datos ficticios (supervisor):
1. *Códigos de EPP*: buscar `EPP-004-0001` (también en minúsculas) → ficha con quién la tiene, entrega de origen e historial.
2. *Devoluciones*: registrar la devolución (motivo «Reemplazo», condición «Buen estado») → queda **pendiente de inspección** y en *Necesidad de EPP* pasa de asignada a «por revisar»; no suma al disponible.
3. En la ficha: inspección «Apto para uso» → «pendiente de autorización» → *Autorizar retorno* (decisión humana, queda registrada) → «En almacén».
4. *Necesidad de EPP*: faltante y recomendación por tipo; tallas como dato faltante; consumibles por lotes.
5. *Mantenimiento*: unidad en taller con diagnóstico, cierre con costo, y la tarjeta de ahorro realizado frente a potencial («no calculable»).

## 5. Preguntas difíciles previstas

- *¿Quién validó las clases de EPP?* Nadie: están marcadas «pendiente de validación SST» y el sistema aplica la política conservadora.
- *¿El 97 % de conformidad prueba mejora?* No: son datos ficticios y no hay medición ni línea base validada.
- *¿Es inmutable la auditoría?* No; detecta alteraciones parciales, un administrador de la base podría recalcular la cadena.
- *¿Funciona con MySQL real / con 2 000 trabajadores?* No se ha comprobado.
