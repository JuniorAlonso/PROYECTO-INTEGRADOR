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

    /** EPP con vida útil igual o menor se consideran descartables: no se reparan ni se reutilizan. */
    public static final int DIAS_DESCARTABLE = 30;
    /** Horizonte de renovaciones que se suma a la necesidad de stock. */
    public static final int HORIZONTE_DIAS = 30;

    public record FilaInventario(Epp epp, long trabajadores, long requeridas, long porEntregar, long renovaciones,
                                 long disponibles, long asignadas, long porMantenimiento, long enMantenimiento,
                                 long deBaja) {
        public long getNecesidad() { return porEntregar + renovaciones; }
        public long getRecuperables() { return porMantenimiento + enMantenimiento; }
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

    public InventarioService(UnidadEppRepository unidadRepository, MovimientoUnidadRepository movimientoRepository,
                             EppRepository eppRepository, DotacionService dotacionService,
                             VigenciaService vigenciaService) {
        this.unidadRepository = unidadRepository;
        this.movimientoRepository = movimientoRepository;
        this.eppRepository = eppRepository;
        this.dotacionService = dotacionService;
        this.vigenciaService = vigenciaService;
    }

    public static boolean isDescartable(Epp epp) {
        return epp.getVidaUtilDias() <= DIAS_DESCARTABLE;
    }

    private static BigDecimal costo(Epp epp, long unidades) {
        return epp.getCostoUnitario() == null ? BigDecimal.ZERO : epp.getCostoUnitario().multiply(BigDecimal.valueOf(unidades));
    }

    /** Ingresa unidades nuevas al almacén; cada una recibe su código (EPP-003-0001: EPP 3, unidad 1). */
    @Transactional
    public List<UnidadEpp> ingresar(Integer idEpp, Integer cantidad, String usuario) {
        if (idEpp == null) {
            throw new ReglaNegocioException("Seleccione el EPP que ingresa al almacén.");
        }
        if (cantidad == null || cantidad < 1 || cantidad > 500) {
            throw new ReglaNegocioException("La cantidad a ingresar debe estar entre 1 y 500.");
        }
        Epp epp = eppRepository.findById(idEpp).orElseThrow(() -> new RecursoNoEncontradoException("EPP no encontrado."));
        if (!epp.isActivo()) {
            throw new ReglaNegocioException("El EPP \"" + epp.getNombre() + "\" está desactivado en el catálogo.");
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
        return nuevas;
    }

    /** Asigna unidades del almacén a un EPP entregado. Devuelve los códigos asignados (pueden faltar si no hay stock). */
    @Transactional
    public List<String> asignar(DetalleEntrega detalle, Trabajador trabajador, String usuario) {
        List<UnidadEpp> disponibles = unidadRepository.findByEppIdEppAndEstadoOrderByIdUnidadAsc(
                detalle.getEpp().getIdEpp(), EstadoUnidad.DISPONIBLE);
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
        for (UnidadEpp unidad : unidadRepository.findByDetalleIdDetalle(detalle.getIdDetalle())) {
            if (unidad.getEstado() != EstadoUnidad.ASIGNADO) {
                continue;
            }
            Trabajador trabajador = unidad.getTrabajador();
            quitarAsignacion(unidad, EstadoUnidad.DISPONIBLE);
            movimientoRepository.save(new MovimientoUnidad(unidad, TipoMovimientoUnidad.LIBERADO, trabajador,
                    unidad.getCondicion(), "Anulación de la entrega", usuario));
        }
    }

    /**
     * Registra la devolución de una unidad y decide su destino:
     * inservible o descartable → baja; reparable → apta para mantenimiento (salvo que ya se reparó
     * {@value UnidadEpp#MAX_REPARACIONES} veces); buen estado pero con vida útil cumplida → inspección en mantenimiento;
     * buen estado y vigente → vuelve al almacén para reutilizarse.
     */
    @Transactional
    public UnidadEpp devolver(String codigo, CondicionEpp condicion, String observacion, String usuario) {
        if (codigo == null || codigo.isBlank()) {
            throw new ReglaNegocioException("Ingrese el código del EPP devuelto.");
        }
        if (condicion == null || condicion == CondicionEpp.NUEVO) {
            throw new ReglaNegocioException("Indique la condición en que se devuelve el EPP.");
        }
        if (observacion != null && observacion.trim().length() > 450) {
            throw new ReglaNegocioException("La observación admite hasta 450 caracteres.");
        }
        UnidadEpp unidad = unidadRepository.findByCodigoIgnoreCase(codigo.trim())
                .orElseThrow(() -> new ReglaNegocioException("No existe un EPP con el código " + codigo.trim() + "."));
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
        } else if (isDescartable(unidad.getEpp())) {
            destino = EstadoUnidad.DE_BAJA;
            decision = "EPP descartable (vida útil de " + unidad.getEpp().getVidaUtilDias() + " días): no se reutiliza";
        } else if (condicion == CondicionEpp.REPARABLE && unidad.getVecesReparado() >= UnidadEpp.MAX_REPARACIONES) {
            destino = EstadoUnidad.DE_BAJA;
            decision = "Ya se reparó " + unidad.getVecesReparado() + " veces: no conviene otra reparación";
        } else if (condicion == CondicionEpp.REPARABLE) {
            destino = EstadoUnidad.POR_MANTENIMIENTO;
            decision = "Reparable: apto para mantenimiento";
        } else if (vidaCumplida) {
            destino = EstadoUnidad.POR_MANTENIMIENTO;
            decision = "Vida útil cumplida: requiere inspección en mantenimiento antes de reutilizarse";
        } else {
            destino = EstadoUnidad.DISPONIBLE;
            decision = "Buen estado: vuelve al almacén para reutilizarse";
        }
        unidad.setCondicion(condicion);
        quitarAsignacion(unidad, destino);
        String nota = observacion == null || observacion.isBlank() ? decision : decision + ". " + observacion.trim();
        movimientoRepository.save(new MovimientoUnidad(unidad, TipoMovimientoUnidad.DEVOLUCION, trabajador,
                condicion, nota, usuario));
        if (destino == EstadoUnidad.DE_BAJA) {
            movimientoRepository.save(new MovimientoUnidad(unidad, TipoMovimientoUnidad.BAJA, null, condicion, decision, usuario));
        }
        return unidad;
    }

    @Transactional
    public UnidadEpp enviarAMantenimiento(Integer idUnidad, String usuario) {
        UnidadEpp unidad = obtener(idUnidad);
        if (unidad.getEstado() != EstadoUnidad.POR_MANTENIMIENTO) {
            throw new ReglaNegocioException("El EPP " + unidad.getCodigo() + " no está marcado como apto para mantenimiento.");
        }
        unidad.setEstado(EstadoUnidad.EN_MANTENIMIENTO);
        movimientoRepository.save(new MovimientoUnidad(unidad, TipoMovimientoUnidad.ENVIO_MANTENIMIENTO, null,
                unidad.getCondicion(), null, usuario));
        return unidad;
    }

    /** Cierra el mantenimiento: si se reparó vuelve al almacén; si no, se da de baja. */
    @Transactional
    public UnidadEpp cerrarMantenimiento(Integer idUnidad, boolean reparado, String observacion, String usuario) {
        UnidadEpp unidad = obtener(idUnidad);
        if (unidad.getEstado() != EstadoUnidad.EN_MANTENIMIENTO && unidad.getEstado() != EstadoUnidad.POR_MANTENIMIENTO) {
            throw new ReglaNegocioException("El EPP " + unidad.getCodigo() + " no está en mantenimiento.");
        }
        String nota = observacion == null || observacion.isBlank() ? null : observacion.trim();
        if (nota != null && nota.length() > 450) {
            throw new ReglaNegocioException("La observación admite hasta 450 caracteres.");
        }
        if (reparado) {
            unidad.setEstado(EstadoUnidad.DISPONIBLE);
            unidad.setCondicion(CondicionEpp.BUENO);
            unidad.setVecesReparado(unidad.getVecesReparado() + 1);
            movimientoRepository.save(new MovimientoUnidad(unidad, TipoMovimientoUnidad.REPARADO, null,
                    CondicionEpp.BUENO, nota, usuario));
        } else {
            unidad.setEstado(EstadoUnidad.DE_BAJA);
            unidad.setCondicion(CondicionEpp.INSERVIBLE);
            movimientoRepository.save(new MovimientoUnidad(unidad, TipoMovimientoUnidad.BAJA, null,
                    CondicionEpp.INSERVIBLE, nota == null ? "No se pudo reparar" : nota, usuario));
        }
        return unidad;
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

    /** Ahorro logrado: unidades reutilizadas tras una devolución en buen estado o reparadas, valorizadas al costo de compra. */
    @Transactional(readOnly = true)
    public BigDecimal ahorroLogrado() {
        BigDecimal total = BigDecimal.ZERO;
        for (MovimientoUnidad m : movimientoRepository.findByTipo(TipoMovimientoUnidad.REPARADO)) {
            total = total.add(costo(m.getUnidad().getEpp(), 1));
        }
        for (MovimientoUnidad m : movimientoRepository.findByTipo(TipoMovimientoUnidad.DEVOLUCION)) {
            if (m.getCondicion() == CondicionEpp.BUENO && m.getObservacion() != null
                    && m.getObservacion().startsWith("Buen estado")) {
                total = total.add(costo(m.getUnidad().getEpp(), 1));
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

        List<FilaInventario> filas = new ArrayList<>();
        for (Epp epp : eppRepository.findByEstadoOrderByCategoriaNombreAscNombreAsc(Epp.ACTIVO)) {
            Map<EstadoUnidad, Long> c = conteo.getOrDefault(epp.getIdEpp(), Map.of());
            long[] d = demanda.getOrDefault(epp.getIdEpp(), new long[3]);
            filas.add(new FilaInventario(epp, d[0], d[1], d[2], renovaciones.getOrDefault(epp.getIdEpp(), 0L),
                    c.getOrDefault(EstadoUnidad.DISPONIBLE, 0L), c.getOrDefault(EstadoUnidad.ASIGNADO, 0L),
                    c.getOrDefault(EstadoUnidad.POR_MANTENIMIENTO, 0L), c.getOrDefault(EstadoUnidad.EN_MANTENIMIENTO, 0L),
                    c.getOrDefault(EstadoUnidad.DE_BAJA, 0L)));
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
