package pe.utp.epptrack.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.dto.EstadoDotacion;
import pe.utp.epptrack.dto.ItemDotacion;
import pe.utp.epptrack.model.*;
import pe.utp.epptrack.repository.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Inventario de EPP por unidad con código: ingreso al almacén, asignación en las entregas, devolución,
 * evaluación para mantenimiento y comparación del stock con la necesidad del personal.
 */
@Service
public class InventarioService {

    /** Horizonte de renovaciones que se suma a la necesidad de stock. */
    public static final int HORIZONTE_DIAS = 30;

    public record FilaInventario(Epp epp, long trabajadores, long requeridas, long porEntregar, long renovaciones,
                                 long disponibles, long asignadas, long porMantenimiento, long enMantenimiento,
                                 long deBaja, long porRevisar) {
        public long getNecesidad() { return porEntregar + renovaciones; }
        /**
         * Informativo: unidades que podrían volver (por inspeccionar, por autorizar, aptas para mantenimiento o en
         * taller). Cuarentena y baja no cuentan. Su retorno depende de decisiones humanas.
         */
        public long getRecuperables() { return porRevisar + porMantenimiento + enMantenimiento; }
        /** Lo que falta comprar después de usar el stock y lo que vuelve de mantenimiento. */
        public long getCompra() { return Math.max(0, getNecesidad() - disponibles - getRecuperables()); }
        public boolean isSuficiente() { return getCompra() == 0; }
        public BigDecimal getCostoCompra() { return costo(epp, getCompra()); }
    }

    public record Resumen(List<FilaInventario> filas, long trabajadores, long disponibles, long asignadas,
                          long recuperables, long porEntregar, long compra, BigDecimal costoCompra,
                          BigDecimal ahorroPotencial, BigDecimal ahorroLogrado, long pendientesDevolucion) {}

    private final UnidadEppRepository unidadRepository;
    private final MovimientoUnidadRepository movimientoRepository;
    private final EppRepository eppRepository;
    private final DotacionService dotacionService;
    private final VigenciaService vigenciaService;
    private final AuditoriaService auditoriaService;
    private final InspeccionUnidadRepository inspeccionRepository;
    private final RegistroMantenimientoRepository mantenimientoRepository;
    private final LoteService loteService;

    public InventarioService(UnidadEppRepository unidadRepository, MovimientoUnidadRepository movimientoRepository,
                             EppRepository eppRepository, DotacionService dotacionService,
                             VigenciaService vigenciaService, AuditoriaService auditoriaService,
                             InspeccionUnidadRepository inspeccionRepository,
                             RegistroMantenimientoRepository mantenimientoRepository,
                             LoteService loteService) {
        this.loteService = loteService;
        this.inspeccionRepository = inspeccionRepository;
        this.mantenimientoRepository = mantenimientoRepository;
        this.auditoriaService = auditoriaService;
        this.unidadRepository = unidadRepository;
        this.movimientoRepository = movimientoRepository;
        this.eppRepository = eppRepository;
        this.dotacionService = dotacionService;
        this.vigenciaService = vigenciaService;
    }

    private static BigDecimal costo(Epp epp, long unidades) {
        return epp.getCostoUnitario() == null ? BigDecimal.ZERO : epp.getCostoUnitario().multiply(BigDecimal.valueOf(unidades));
    }

    /**
     * Ingresa unidades nuevas al almacén; cada una recibe su código (EPP-003-0001: EPP 3, unidad 1).
     * Un EPP desechable NO crea unidades: ingresa como lote (código autogenerado) y devuelve una lista vacía.
     */
    @Transactional
    public List<UnidadEpp> ingresar(Integer idEpp, Integer cantidad, String usuario) {
        if (idEpp == null) {
            throw new ReglaNegocioException("Seleccione el EPP que ingresa al almacén.");
        }
        if (cantidad == null || cantidad < 1 || cantidad > 500) {
            throw new ReglaNegocioException("La cantidad a ingresar debe estar entre 1 y 500.");
        }
        // Se bloquea el EPP: los códigos son correlativos y dos ingresos simultáneos no pueden repetirlos.
        Epp epp = eppRepository.findByIdParaActualizar(idEpp)
                .orElseThrow(() -> new RecursoNoEncontradoException("EPP no encontrado."));
        if (!epp.isActivo()) {
            throw new ReglaNegocioException("El EPP \"" + epp.getNombre() + "\" está desactivado en el catálogo.");
        }
        if (epp.isDesechable()) {
            ingresarConsumible(idEpp, cantidad, null, null, null, usuario);
            return List.of();
        }
        long siguiente = unidadRepository.countByEppIdEpp(idEpp) + 1;
        List<UnidadEpp> nuevas = new ArrayList<>();
        for (int i = 0; i < cantidad; i++) {
            UnidadEpp unidad = unidadRepository.save(
                    new UnidadEpp("EPP-%03d-%04d".formatted(idEpp, siguiente + i), epp));
            movimientoRepository.save(new MovimientoUnidad(unidad, TipoMovimientoUnidad.INGRESO, null,
                    CondicionEpp.NUEVO, null, usuario));
            nuevas.add(unidad);
        }
        auditoriaService.registrar(usuario, "INVENTARIO_INGRESO", "epp", idEpp,
                cantidad + " unidades de " + epp.getNombre() + ": " + nuevas.getFirst().getCodigo()
                        + (cantidad > 1 ? " a " + nuevas.getLast().getCodigo() : ""));
        return nuevas;
    }

    /** Ingresa un lote de un EPP desechable (control por cantidad y lote, sin códigos por unidad). */
    @Transactional
    public LoteConsumible ingresarConsumible(Integer idEpp, int cantidad, String codigoLote, LocalDate vencimiento,
                                             String observacion, String usuario) {
        if (idEpp == null) {
            throw new ReglaNegocioException("Seleccione el EPP que ingresa al almacén.");
        }
        return loteService.ingresarLote(idEpp, cantidad, codigoLote, vencimiento, observacion, usuario);
    }

    /** Descuenta del saldo de lotes (FEFO) lo entregado de un desechable; devuelve lo realmente descontado. */
    @Transactional
    public int consumirConsumible(DetalleEntrega detalle, Trabajador trabajador, String usuario) {
        return loteService.consumir(detalle, detalle.getCantidad(), usuario);
    }

    /**
     * Asigna unidades del almacén a un EPP reutilizable entregado. Devuelve los códigos asignados (pueden faltar si no
     * hay stock). Un desechable no usa unidades: descuenta del saldo de lotes y devuelve una lista vacía (use
     * {@link #consumirConsumible} para saber cuánto se descontó). Se bloquea el EPP para que dos entregas
     * simultáneas no tomen la misma unidad.
     */
    @Transactional
    public List<String> asignar(DetalleEntrega detalle, Trabajador trabajador, String usuario) {
        Epp epp = eppRepository.findByIdParaActualizar(detalle.getEpp().getIdEpp()).orElse(detalle.getEpp());
        if (epp.isDesechable()) {
            consumirConsumible(detalle, trabajador, usuario);
            return new ArrayList<>();
        }
        List<UnidadEpp> disponibles = unidadRepository.findByEppIdEppAndEstadoOrderByIdUnidadAsc(
                epp.getIdEpp(), EstadoUnidad.DISPONIBLE);
        List<String> codigos = new ArrayList<>();
        for (UnidadEpp unidad : disponibles.subList(0, Math.min(detalle.getCantidad(), disponibles.size()))) {
            unidad.setEstado(EstadoUnidad.ASIGNADO);
            unidad.setTrabajador(trabajador);
            unidad.setDetalle(detalle);
            movimientoRepository.save(new MovimientoUnidad(unidad, TipoMovimientoUnidad.ASIGNACION, trabajador,
                    unidad.getCondicion(), "Entrega del " + detalle.getEntrega().getFechaEntrega(), usuario));
            codigos.add(unidad.getCodigo());
        }
        return codigos;
    }

    /** Una entrega anulada por error devuelve sus unidades al almacén tal como estaban. */
    @Transactional
    public void liberar(DetalleEntrega detalle, String usuario) {
        loteService.revertir(detalle, usuario);
        for (UnidadEpp unidad : unidadRepository.findByDetalleIdDetalle(detalle.getIdDetalle())) {
            if (unidad.getEstado() != EstadoUnidad.ASIGNADO && unidad.getEstado() != EstadoUnidad.CONSUMIDO) {
                continue;
            }
            Trabajador trabajador = unidad.getTrabajador();
            quitarAsignacion(unidad, EstadoUnidad.DISPONIBLE);
            movimientoRepository.save(new MovimientoUnidad(unidad, TipoMovimientoUnidad.LIBERADO, trabajador,
                    unidad.getCondicion(), "Anulación de la entrega", usuario));
        }
    }

    /**
     * Registra la devolución de una unidad. Una unidad devuelta NUNCA vuelve sola a DISPONIBLE (cambio de regla
     * pedido por el profesor: trazabilidad por unidad con inspección y autorización humana):
     * inservible → baja; reparable que ya se reparó {@value UnidadEpp#MAX_REPARACIONES} veces → baja;
     * buen estado cuya política no exige inspección y con vida útil vigente → POR_AUTORIZAR (igual la autoriza una persona);
     * cualquier otro caso (incluida toda unidad dañada o con vida útil cumplida) → POR_INSPECCION.
     */
    @Transactional
    public UnidadEpp devolver(String codigo, CondicionEpp condicion, MotivoDevolucion motivo, String observacion,
                              String usuario) {
        if (codigo == null || codigo.isBlank()) {
            throw new ReglaNegocioException("Ingrese el código del EPP devuelto.");
        }
        if (motivo == null) {
            throw new ReglaNegocioException("Indique el motivo de la devolución.");
        }
        if (condicion == null || condicion == CondicionEpp.NUEVO) {
            throw new ReglaNegocioException("Indique la condición en que se devuelve el EPP.");
        }
        String nota = texto(observacion, "la observación", false);
        nota = nota == null ? "" : nota;
        if (motivo == MotivoDevolucion.OTRO && nota.isEmpty()) {
            throw new ReglaNegocioException("Con el motivo «Otro» explique la razón en la observación.");
        }
        UnidadEpp unidad = unidadRepository.findByCodigoParaActualizar(codigo.trim())
                .orElseThrow(() -> new ReglaNegocioException("No existe un EPP con el código " + codigo.trim() + "."));
        Epp epp = unidad.getEpp();
        if (unidad.getEstado() == EstadoUnidad.CONSUMIDO || !epp.isPermiteDevolucion()) {
            throw new ReglaNegocioException("El EPP " + unidad.getCodigo() + " (" + epp.getNombre() + ") es "
                    + epp.getClaseEfectiva().getEtiqueta().toLowerCase() + ": no corresponde devolverlo. "
                    + "Si se perdió o se dañó, registre una nueva entrega con ese motivo.");
        }
        if (unidad.getEstado() != EstadoUnidad.ASIGNADO) {
            throw new ReglaNegocioException("El EPP " + unidad.getCodigo() + " no está asignado a ningún trabajador ("
                    + unidad.getEstado().getEtiqueta().toLowerCase() + ").");
        }
        boolean vidaCumplida = unidad.getDetalle() != null && unidad.getDetalle().getFechaRenovacion() != null
                && unidad.getDetalle().getFechaRenovacion().isBefore(LocalDate.now());
        Trabajador trabajador = unidad.getTrabajador();
        EstadoUnidad destino;
        String decision;
        if (condicion == CondicionEpp.INSERVIBLE) {
            destino = EstadoUnidad.DE_BAJA;
            decision = "Inservible: se da de baja";
        } else if (condicion == CondicionEpp.REPARABLE && unidad.getVecesReparado() >= UnidadEpp.MAX_REPARACIONES) {
            destino = EstadoUnidad.DE_BAJA;
            decision = "Ya se reparó " + unidad.getVecesReparado() + " veces: no conviene otra reparación";
        } else if (condicion == CondicionEpp.BUENO && !vidaCumplida && !epp.isRequiereInspeccionAlDevolver()) {
            destino = EstadoUnidad.POR_AUTORIZAR;
            decision = "Sin inspección exigida por la política del EPP: queda pendiente de autorización humana";
        } else {
            destino = EstadoUnidad.POR_INSPECCION;
            decision = vidaCumplida ? "Vida útil cumplida: debe inspeccionarse"
                    : "Devuelto: debe inspeccionarse antes de volver a asignarse";
        }
        unidad.setCondicion(condicion);
        quitarAsignacion(unidad, destino);
        MovimientoUnidad devolucion = new MovimientoUnidad(unidad, TipoMovimientoUnidad.DEVOLUCION, trabajador,
                condicion, corto(nota.isEmpty() ? decision : decision + ". " + nota), usuario);
        devolucion.setMotivo(motivo);
        movimientoRepository.save(devolucion);
        if (destino == EstadoUnidad.DE_BAJA) {
            movimientoRepository.save(new MovimientoUnidad(unidad, TipoMovimientoUnidad.BAJA, null, condicion, decision, usuario));
        }
        auditoriaService.registrar(usuario, "INVENTARIO_DEVOLUCION", "unidad_epp", unidad.getIdUnidad(),
                unidad.getCodigo() + "; motivo " + motivo.name() + "; condición " + condicion.name() + "; destino " + destino.name());
        return unidad;
    }

    /**
     * Inspección de una unidad devuelta (solo desde POR_INSPECCION). «Apto para uso» NO la deja disponible: pasa a
     * POR_AUTORIZAR y una persona debe decidir el retorno; los criterios de SST para reutilizar EPP críticos
     * están pendientes de validación y el sistema no los inventa.
     */
    @Transactional
    public UnidadEpp registrarInspeccion(Integer idUnidad, ResultadoInspeccion resultado, String observaciones, String usuario) {
        exigirUsuario(usuario);
        if (resultado == null) {
            throw new ReglaNegocioException("Seleccione el resultado de la inspección.");
        }
        String obs = texto(observaciones, "las observaciones de la inspección", resultado != ResultadoInspeccion.APTO_PARA_USO);
        UnidadEpp unidad = paraActualizar(idUnidad);
        exigirEstado(unidad, "inspeccionarse", EstadoUnidad.POR_INSPECCION);
        inspeccionRepository.save(new InspeccionUnidad(unidad, usuario, resultado, obs));
        EstadoUnidad destino = resultado.getDestino();
        unidad.setEstado(destino);
        switch (resultado) {
            case APTO_PARA_USO -> unidad.setCondicion(CondicionEpp.BUENO);
            case REQUIERE_MANTENIMIENTO -> unidad.setCondicion(CondicionEpp.REPARABLE);
            case NO_APTO_BAJA -> unidad.setCondicion(CondicionEpp.INSERVIBLE);
            default -> { }
        }
        movimientoRepository.save(new MovimientoUnidad(unidad, TipoMovimientoUnidad.INSPECCION, null, unidad.getCondicion(),
                corto("Resultado: " + resultado.getEtiqueta() + (obs == null ? "" : ". " + obs)), usuario));
        if (destino == EstadoUnidad.DE_BAJA) {
            movimientoRepository.save(new MovimientoUnidad(unidad, TipoMovimientoUnidad.BAJA, null, unidad.getCondicion(),
                    "No apto tras la inspección", usuario));
        }
        auditoriaService.registrar(usuario, "INVENTARIO_INSPECCION", "unidad_epp", unidad.getIdUnidad(),
                unidad.getCodigo() + "; resultado " + resultado.name() + "; destino " + destino.name());
        return unidad;
    }

    /** Decisión humana: la unidad inspeccionada (o reparada) vuelve al almacén. Solo desde POR_AUTORIZAR. */
    @Transactional
    public UnidadEpp autorizarRetorno(Integer idUnidad, String observacion, String usuario) {
        exigirUsuario(usuario);
        String obs = texto(observacion, "la observación", false);
        UnidadEpp unidad = paraActualizar(idUnidad);
        exigirEstado(unidad, "autorizarse", EstadoUnidad.POR_AUTORIZAR);
        unidad.setEstado(EstadoUnidad.DISPONIBLE);
        inspeccionRepository.findByUnidadIdUnidadOrderByFechaDescIdInspeccionDesc(idUnidad)
                .forEach(i -> i.setAutorizacionPendiente(false));
        movimientoRepository.save(new MovimientoUnidad(unidad, TipoMovimientoUnidad.AUTORIZADO_RETORNO, null,
                unidad.getCondicion(), obs == null ? "Retorno al almacén autorizado" : obs, usuario));
        auditoriaService.registrar(usuario, "INVENTARIO_RETORNO_AUTORIZADO", "unidad_epp", unidad.getIdUnidad(), unidad.getCodigo());
        return unidad;
    }

    /** Rechaza el retorno de una unidad en POR_AUTORIZAR: destino EN_CUARENTENA o DE_BAJA, con motivo obligatorio. */
    @Transactional
    public UnidadEpp rechazarRetorno(Integer idUnidad, EstadoUnidad destino, String motivo, String usuario) {
        exigirUsuario(usuario);
        if (destino != EstadoUnidad.EN_CUARENTENA && destino != EstadoUnidad.DE_BAJA) {
            throw new ReglaNegocioException("Al rechazar el retorno el destino debe ser cuarentena o baja.");
        }
        String razon = texto(motivo, "el motivo del rechazo", true);
        UnidadEpp unidad = paraActualizar(idUnidad);
        exigirEstado(unidad, "rechazarse", EstadoUnidad.POR_AUTORIZAR);
        inspeccionRepository.findByUnidadIdUnidadOrderByFechaDescIdInspeccionDesc(idUnidad)
                .forEach(i -> i.setAutorizacionPendiente(false));
        moverConMotivo(unidad, destino, TipoMovimientoUnidad.RETORNO_RECHAZADO, razon, usuario);
        auditoriaService.registrar(usuario, "INVENTARIO_RETORNO_RECHAZADO", "unidad_epp", unidad.getIdUnidad(),
                unidad.getCodigo() + "; destino " + destino.name());
        return unidad;
    }

    /** Sale de cuarentena con una decisión humana: mantenimiento, baja o autorización de retorno. */
    @Transactional
    public UnidadEpp resolverCuarentena(Integer idUnidad, EstadoUnidad destino, String motivo, String usuario) {
        exigirUsuario(usuario);
        if (destino != EstadoUnidad.POR_MANTENIMIENTO && destino != EstadoUnidad.DE_BAJA
                && destino != EstadoUnidad.POR_AUTORIZAR) {
            throw new ReglaNegocioException("Desde cuarentena el destino puede ser mantenimiento, baja o autorización.");
        }
        String razon = texto(motivo, "el motivo de la decisión", true);
        UnidadEpp unidad = paraActualizar(idUnidad);
        exigirEstado(unidad, "resolverse", EstadoUnidad.EN_CUARENTENA);
        moverConMotivo(unidad, destino, TipoMovimientoUnidad.CUARENTENA_RESUELTA, razon, usuario);
        auditoriaService.registrar(usuario, "INVENTARIO_CUARENTENA_RESUELTA", "unidad_epp", unidad.getIdUnidad(),
                unidad.getCodigo() + "; destino " + destino.name());
        return unidad;
    }

    /** Descarta una unidad apta para mantenimiento sin repararla (por ejemplo, si no conviene el gasto). */
    @Transactional
    public UnidadEpp darDeBaja(Integer idUnidad, String motivo, String usuario) {
        exigirUsuario(usuario);
        String razon = texto(motivo, "el motivo de la baja", true);
        UnidadEpp unidad = paraActualizar(idUnidad);
        exigirEstado(unidad, "darse de baja desde esta pantalla", EstadoUnidad.POR_MANTENIMIENTO);
        moverConMotivo(unidad, EstadoUnidad.DE_BAJA, TipoMovimientoUnidad.BAJA, razon, usuario);
        auditoriaService.registrar(usuario, "INVENTARIO_BAJA", "unidad_epp", unidad.getIdUnidad(), unidad.getCodigo());
        return unidad;
    }

    /** Envía a mantenimiento una unidad apta (POR_MANTENIMIENTO) y abre su registro con diagnóstico y responsable. */
    @Transactional
    public UnidadEpp enviarAMantenimiento(Integer idUnidad, String diagnostico, String accionPrevista, String usuario) {
        exigirUsuario(usuario);
        String diag = texto(diagnostico, "el diagnóstico", true);
        String accion = texto(accionPrevista, "la acción prevista", false);
        UnidadEpp unidad = paraActualizar(idUnidad);
        exigirEstado(unidad, "enviarse a mantenimiento", EstadoUnidad.POR_MANTENIMIENTO);
        unidad.setEstado(EstadoUnidad.EN_MANTENIMIENTO);
        mantenimientoRepository.save(new RegistroMantenimiento(unidad, diag, accion, usuario));
        movimientoRepository.save(new MovimientoUnidad(unidad, TipoMovimientoUnidad.ENVIO_MANTENIMIENTO, null,
                unidad.getCondicion(), corto("Diagnóstico: " + diag), usuario));
        auditoriaService.registrar(usuario, "INVENTARIO_MANTENIMIENTO_ENVIO", "unidad_epp", unidad.getIdUnidad(),
                unidad.getCodigo());
        return unidad;
    }

    /**
     * Cierra el mantenimiento (solo desde EN_MANTENIMIENTO). REPARADO suma una reparación y pasa a POR_AUTORIZAR:
     * reparada no significa autorizada. NO_REPARABLE → baja. El costo es opcional, no negativo y se guarda con 2 decimales.
     */
    @Transactional
    public UnidadEpp cerrarMantenimiento(Integer idUnidad, ResultadoMantenimiento resultado, String accionRealizada,
                                         BigDecimal costo, String observacion, String usuario) {
        exigirUsuario(usuario);
        if (resultado == null) {
            throw new ReglaNegocioException("Indique el resultado del mantenimiento.");
        }
        BigDecimal costoFinal = null;
        if (costo != null) {
            if (costo.signum() < 0) {
                throw new ReglaNegocioException("El costo del mantenimiento no puede ser negativo.");
            }
            costoFinal = costo.setScale(2, java.math.RoundingMode.HALF_UP);
            if (costoFinal.compareTo(new BigDecimal("99999999.99")) > 0) {
                throw new ReglaNegocioException("El costo del mantenimiento es demasiado alto.");
            }
        }
        String hecho = texto(accionRealizada, "la acción realizada", false);
        String nota = texto(observacion, "la observación", false);
        UnidadEpp unidad = paraActualizar(idUnidad);
        exigirEstado(unidad, "cerrar su mantenimiento", EstadoUnidad.EN_MANTENIMIENTO);
        RegistroMantenimiento registro = mantenimientoRepository
                .findFirstByUnidadIdUnidadAndEstadoOrderByIdRegistroDesc(idUnidad, EstadoRegistroMantenimiento.ABIERTO)
                .orElseGet(() -> mantenimientoRepository.save(new RegistroMantenimiento(unidad,
                        "Sin registro previo de diagnóstico (unidad anterior al registro de mantenimiento)", null, usuario)));
        registro.setEstado(EstadoRegistroMantenimiento.CERRADO);
        registro.setFechaFin(java.time.LocalDateTime.now());
        registro.setCosto(costoFinal);
        registro.setResultado(resultado);
        registro.setAccionRealizada(hecho);
        registro.setObservacion(nota);
        registro.setCerradoPor(usuario);
        String detalle = (hecho == null ? "" : hecho) + (nota == null ? "" : (hecho == null ? "" : ". ") + nota);
        if (resultado == ResultadoMantenimiento.REPARADO) {
            unidad.setEstado(EstadoUnidad.POR_AUTORIZAR);
            unidad.setCondicion(CondicionEpp.BUENO);
            unidad.setVecesReparado(unidad.getVecesReparado() + 1);
            movimientoRepository.save(new MovimientoUnidad(unidad, TipoMovimientoUnidad.REPARADO, null,
                    CondicionEpp.BUENO, corto(detalle.isEmpty() ? "Reparado" : detalle), usuario));
        } else {
            unidad.setEstado(EstadoUnidad.DE_BAJA);
            unidad.setCondicion(CondicionEpp.INSERVIBLE);
            movimientoRepository.save(new MovimientoUnidad(unidad, TipoMovimientoUnidad.BAJA, null,
                    CondicionEpp.INSERVIBLE, corto(detalle.isEmpty() ? "No se pudo reparar" : detalle), usuario));
        }
        auditoriaService.registrar(usuario, "INVENTARIO_MANTENIMIENTO_CIERRE", "unidad_epp", unidad.getIdUnidad(),
                unidad.getCodigo() + "; resultado " + resultado.name() + "; destino " + unidad.getEstado().name());
        return unidad;
    }

    private UnidadEpp paraActualizar(Integer idUnidad) {
        return unidadRepository.findByIdParaActualizar(idUnidad)
                .orElseThrow(() -> new RecursoNoEncontradoException("Unidad de EPP no encontrada."));
    }

    private void moverConMotivo(UnidadEpp unidad, EstadoUnidad destino, TipoMovimientoUnidad tipo, String razon, String usuario) {
        unidad.setEstado(destino);
        movimientoRepository.save(new MovimientoUnidad(unidad, tipo, null, unidad.getCondicion(), razon, usuario));
        if (destino == EstadoUnidad.DE_BAJA) {
            unidad.setCondicion(CondicionEpp.INSERVIBLE);
            if (tipo != TipoMovimientoUnidad.BAJA) {
                movimientoRepository.save(new MovimientoUnidad(unidad, TipoMovimientoUnidad.BAJA, null,
                        CondicionEpp.INSERVIBLE, razon, usuario));
            }
        }
    }

    private void exigirEstado(UnidadEpp unidad, String accion, EstadoUnidad... permitidos) {
        if (unidad.getEstado() == EstadoUnidad.CONSUMIDO) {
            throw new ReglaNegocioException("El EPP " + unidad.getCodigo() + " es "
                    + unidad.getEpp().getClaseEfectiva().getEtiqueta().toLowerCase() + ": no pasa por inspección ni mantenimiento.");
        }
        if (!Arrays.asList(permitidos).contains(unidad.getEstado())) {
            throw new ReglaNegocioException("El EPP " + unidad.getCodigo() + " no puede " + accion + ": está en estado «"
                    + unidad.getEstado().getEtiqueta() + "».");
        }
    }

    private static void exigirUsuario(String usuario) {
        if (usuario == null || usuario.isBlank()) {
            throw new ReglaNegocioException("Se requiere un usuario autenticado para registrar esta operación.");
        }
    }

    /** Texto opcional u obligatorio, recortado y limitado a 450 caracteres; {@code null} si está vacío y es opcional. */
    private static String texto(String valor, String campo, boolean obligatorio) {
        String t = valor == null ? "" : valor.trim();
        if (t.isEmpty()) {
            if (obligatorio) {
                throw new ReglaNegocioException("Indique " + campo + ".");
            }
            return null;
        }
        if (t.length() > 450) {
            throw new ReglaNegocioException("El texto de " + campo + " admite hasta 450 caracteres.");
        }
        return t;
    }

    /** La bitácora guarda hasta 500 caracteres. */
    private static String corto(String texto) {
        return texto.length() > 500 ? texto.substring(0, 500) : texto;
    }

    public record FilaComparacion(Epp epp, long reparados, BigDecimal costoMantenimiento, BigDecimal costoReposicion,
                                  BigDecimal diferencia) {}

    /**
     * Costos de mantenimiento registrados. {@code costoTotal} = suma del costo de los registros CERRADOS que tienen costo.
     * La comparación con reposición es solo referencial: por EPP con costo unitario cargado,
     * reposición = n × costo unitario (n = mantenimientos cerrados como reparados con costo registrado) y
     * diferencia = reposición − costo de mantenimiento. No es un ahorro demostrado.
     */
    public record IndicadorMantenimiento(BigDecimal costoTotal, long cerradosConCosto, long cerrados,
                                         List<FilaComparacion> comparacion) {}

    @Transactional(readOnly = true)
    public IndicadorMantenimiento indicadorMantenimiento() {
        List<RegistroMantenimiento> cerrados = mantenimientoRepository
                .findByEstadoOrderByIdRegistroDesc(EstadoRegistroMantenimiento.CERRADO);
        BigDecimal total = BigDecimal.ZERO;
        long conCosto = 0;
        Map<Epp, long[]> reparados = new LinkedHashMap<>();
        Map<Epp, BigDecimal> costos = new HashMap<>();
        for (RegistroMantenimiento r : cerrados) {
            if (r.getCosto() == null) {
                continue;
            }
            total = total.add(r.getCosto());
            conCosto++;
            Epp epp = r.getUnidad().getEpp();
            if (r.getResultado() == ResultadoMantenimiento.REPARADO && epp.getCostoUnitario() != null) {
                reparados.computeIfAbsent(epp, k -> new long[1])[0]++;
                costos.merge(epp, r.getCosto(), BigDecimal::add);
            }
        }
        List<FilaComparacion> filas = new ArrayList<>();
        reparados.forEach((epp, n) -> {
            BigDecimal reposicion = epp.getCostoUnitario().multiply(BigDecimal.valueOf(n[0]));
            filas.add(new FilaComparacion(epp, n[0], costos.get(epp), reposicion, reposicion.subtract(costos.get(epp))));
        });
        return new IndicadorMantenimiento(total, conCosto, cerrados.size(), filas);
    }

    @Transactional(readOnly = true)
    public List<InspeccionUnidad> inspecciones(Integer idUnidad) {
        return inspeccionRepository.findByUnidadIdUnidadOrderByFechaDescIdInspeccionDesc(idUnidad);
    }

    @Transactional(readOnly = true)
    public List<RegistroMantenimiento> mantenimientos(Integer idUnidad) {
        return mantenimientoRepository.findByUnidadIdUnidadOrderByFechaInicioDescIdRegistroDesc(idUnidad);
    }

    /** Registros abiertos indexados por id de unidad (para mostrar el diagnóstico de lo que está en taller). */
    @Transactional(readOnly = true)
    public Map<Integer, RegistroMantenimiento> abiertosPorUnidad() {
        Map<Integer, RegistroMantenimiento> mapa = new HashMap<>();
        for (RegistroMantenimiento r : mantenimientoRepository.findByEstadoOrderByIdRegistroDesc(EstadoRegistroMantenimiento.ABIERTO)) {
            mapa.putIfAbsent(r.getUnidad().getIdUnidad(), r);
        }
        return mapa;
    }

    @Transactional(readOnly = true)
    public List<RegistroMantenimiento> mantenimientosCerrados() {
        return mantenimientoRepository.findByEstadoOrderByIdRegistroDesc(EstadoRegistroMantenimiento.CERRADO);
    }

    @Transactional(readOnly = true)
    public UnidadEpp obtener(Integer idUnidad) {
        return unidadRepository.findById(idUnidad)
                .orElseThrow(() -> new RecursoNoEncontradoException("Unidad de EPP no encontrada."));
    }

    @Transactional(readOnly = true)
    public Optional<UnidadEpp> buscarPorCodigo(String codigo) {
        return codigo == null || codigo.isBlank() ? Optional.empty() : unidadRepository.findByCodigoIgnoreCase(codigo.trim());
    }

    @Transactional(readOnly = true)
    public List<MovimientoUnidad> movimientos(Integer idUnidad) {
        return movimientoRepository.findByUnidadIdUnidadOrderByFechaDescIdMovimientoDesc(idUnidad);
    }

    @Transactional(readOnly = true)
    public List<UnidadEpp> unidades(Integer idEpp, EstadoUnidad estado) {
        List<UnidadEpp> lista = estado != null ? unidadRepository.findByEstadoOrderByCodigoAsc(estado)
                : unidadRepository.findAllByOrderByCodigoAsc();
        return lista.stream().filter(u -> idEpp == null || u.getEpp().getIdEpp().equals(idEpp)).toList();
    }

    @Transactional(readOnly = true)
    public List<UnidadEpp> enEstado(EstadoUnidad estado) {
        return unidadRepository.findByEstadoOrderByCodigoAsc(estado);
    }

    /** Unidades que el trabajador debe devolver: su entrega fue renovada o venció, o el trabajador cesó. */
    @Transactional(readOnly = true)
    public List<UnidadEpp> pendientesDevolucion() {
        return unidadRepository.findByEstadoOrderByCodigoAsc(EstadoUnidad.ASIGNADO).stream()
                .filter(UnidadEpp::isPendienteDevolucion).toList();
    }

    @Transactional(readOnly = true)
    public List<MovimientoUnidad> ultimasDevoluciones() {
        return movimientoRepository.findTop50ByTipoOrderByFechaDescIdMovimientoDesc(TipoMovimientoUnidad.DEVOLUCION);
    }

    /** Códigos asignados por detalle de entrega, para mostrarlos en el historial. */
    @Transactional(readOnly = true)
    public Map<Integer, List<String>> codigosPorDetalle(List<Integer> idsDetalle) {
        if (idsDetalle.isEmpty()) {
            return Map.of();
        }
        Map<Integer, List<String>> mapa = new HashMap<>();
        for (UnidadEpp u : unidadRepository.findByDetalleIdDetalleIn(idsDetalle)) {
            mapa.computeIfAbsent(u.getDetalle().getIdDetalle(), k -> new ArrayList<>()).add(u.getCodigo());
        }
        mapa.values().forEach(Collections::sort);
        return mapa;
    }

    /**
     * Ahorro logrado: costo de compra evitado cada vez que una unidad ya usada (devuelta o reparada) se vuelve a
     * asignar a un trabajador. Se cuenta por la secuencia de movimientos de cada unidad, no por el texto de las
     * observaciones; una devolución que termina en baja no ahorra nada.
     */
    @Transactional(readOnly = true)
    public BigDecimal ahorroLogrado() {
        BigDecimal total = BigDecimal.ZERO;
        Set<Integer> usadasAntes = new HashSet<>();
        for (MovimientoUnidad m : movimientoRepository.findAllByOrderByIdMovimientoAsc()) {
            Integer unidad = m.getUnidad().getIdUnidad();
            switch (m.getTipo()) {
                case DEVOLUCION, REPARADO -> usadasAntes.add(unidad);
                case BAJA -> usadasAntes.remove(unidad);
                case ASIGNACION -> {
                    if (usadasAntes.contains(unidad)) {
                        total = total.add(costo(m.getUnidad().getEpp(), 1));
                        usadasAntes.remove(unidad);
                    }
                }
                default -> { }
            }
        }
        return total;
    }

    /** Stock del almacén frente a lo que necesita el personal que está laborando (matriz + renovaciones próximas). */
    @Transactional(readOnly = true)
    public Resumen resumen() {
        Map<Integer, Map<EstadoUnidad, Long>> conteo = new HashMap<>();
        for (Object[] fila : unidadRepository.contarPorEppYEstado()) {
            conteo.computeIfAbsent((Integer) fila[0], k -> new EnumMap<>(EstadoUnidad.class))
                    .put((EstadoUnidad) fila[1], (Long) fila[2]);
        }
        Map<Integer, long[]> demanda = new HashMap<>();   // [trabajadores, requeridas, porEntregar]
        List<EstadoDotacion> estados = dotacionService.estadoDotacionTodos(null);
        for (EstadoDotacion estado : estados) {
            for (ItemDotacion item : estado.items()) {
                if (!item.obligatorio()) {
                    continue;
                }
                long[] d = demanda.computeIfAbsent(item.epp().getIdEpp(), k -> new long[3]);
                d[0]++;
                d[1] += item.cantidadRequerida();
                if (!item.isCubierto()) {
                    d[2] += item.cantidadRequerida();
                }
            }
        }
        LocalDate hoy = LocalDate.now();
        Map<Integer, Long> renovaciones = new HashMap<>();
        for (DetalleEntrega d : vigenciaService.renovacionesHasta(HORIZONTE_DIAS, null, null)) {
            // Los vencidos ya cuentan como "por entregar" en la dotación; aquí solo los que vencerán.
            if (d.getEntrega().getTrabajador().getEstado().isLaborando() && !d.getFechaRenovacion().isBefore(hoy)) {
                renovaciones.merge(d.getEpp().getIdEpp(), (long) d.getCantidad(), Long::sum);
            }
        }

        Map<Integer, Long> saldos = loteService.saldosPorEpp();
        List<FilaInventario> filas = new ArrayList<>();
        for (Epp epp : eppRepository.findByEstadoOrderByCategoriaNombreAscNombreAsc(Epp.ACTIVO)) {
            Map<EstadoUnidad, Long> c = conteo.getOrDefault(epp.getIdEpp(), Map.of());
            long[] d = demanda.getOrDefault(epp.getIdEpp(), new long[3]);
            // Un consumible no tiene unidades: su almacén es el saldo de lotes utilizables.
            long almacen = epp.isDesechable() ? saldos.getOrDefault(epp.getIdEpp(), 0L)
                    : c.getOrDefault(EstadoUnidad.DISPONIBLE, 0L);
            filas.add(new FilaInventario(epp, d[0], d[1], d[2], renovaciones.getOrDefault(epp.getIdEpp(), 0L),
                    almacen, c.getOrDefault(EstadoUnidad.ASIGNADO, 0L),
                    c.getOrDefault(EstadoUnidad.POR_MANTENIMIENTO, 0L), c.getOrDefault(EstadoUnidad.EN_MANTENIMIENTO, 0L),
                    c.getOrDefault(EstadoUnidad.DE_BAJA, 0L),
                    c.getOrDefault(EstadoUnidad.POR_INSPECCION, 0L) + c.getOrDefault(EstadoUnidad.POR_AUTORIZAR, 0L)));
        }
        BigDecimal costoCompra = BigDecimal.ZERO;
        BigDecimal ahorroPotencial = BigDecimal.ZERO;
        for (FilaInventario f : filas) {
            costoCompra = costoCompra.add(f.getCostoCompra());
            ahorroPotencial = ahorroPotencial.add(costo(f.epp(), f.getRecuperables()));
        }
        return new Resumen(filas, estados.size(),
                filas.stream().mapToLong(FilaInventario::disponibles).sum(),
                filas.stream().mapToLong(FilaInventario::asignadas).sum(),
                filas.stream().mapToLong(FilaInventario::getRecuperables).sum(),
                filas.stream().mapToLong(FilaInventario::porEntregar).sum(),
                filas.stream().mapToLong(FilaInventario::getCompra).sum(),
                costoCompra, ahorroPotencial, ahorroLogrado(), pendientesDevolucion().size());
    }

    private void quitarAsignacion(UnidadEpp unidad, EstadoUnidad destino) {
        unidad.setEstado(destino);
        unidad.setTrabajador(null);
        unidad.setDetalle(null);
    }
}
