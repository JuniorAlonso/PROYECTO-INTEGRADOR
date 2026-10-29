package pe.utp.epptrack.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.dto.EstadoDotacion;
import pe.utp.epptrack.dto.ItemDotacion;
import pe.utp.epptrack.model.DetalleEntrega;
import pe.utp.epptrack.model.Epp;
import pe.utp.epptrack.model.EstadoUnidad;
import pe.utp.epptrack.repository.EppRepository;
import pe.utp.epptrack.repository.MatrizAsignacionRepository;
import pe.utp.epptrack.repository.SedeRepository;
import pe.utp.epptrack.repository.UnidadEppRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Necesidad de EPP según el personal que labora frente al stock con código. Separa tres tipos de dato:
 * CALCULADO (sale de registros del sistema), FALTANTE (no existe en el sistema, p. ej. tallas) y
 * ESTIMACIÓN (con su supuesto escrito). No proyecta demanda futura ni afirma ahorros.
 */
@Service
public class NecesidadStockService {

    /** Ventana de renovaciones; es un supuesto de la estimación, no un dato registrado. */
    public static final int HORIZONTE_DIAS = 30;

    public enum Recomendacion {
        COMPRAR("Reponer", "estado-critico", "bi-cart-plus"),
        SUFICIENTE("Stock suficiente", "estado-ok", "bi-check-circle"),
        SIN_NECESIDAD("Sin necesidad registrada", "estado-neutro", "bi-dash-circle"),
        DATOS_INSUFICIENTES("Datos insuficientes", "estado-info", "bi-question-circle");

        private final String etiqueta;
        private final String clase;
        private final String icono;

        Recomendacion(String etiqueta, String clase, String icono) {
            this.etiqueta = etiqueta;
            this.clase = clase;
            this.icono = icono;
        }

        public String getEtiqueta() { return etiqueta; }
        public String getClase() { return clase; }
        public String getIcono() { return icono; }
    }

    /**
     * Una fila por tipo de EPP. Calculados: personal, requerida, enPoder, pendiente y los conteos de unidades.
     * Estimación: renovaciones (supone que se renuevan al vencer en {@value #HORIZONTE_DIAS} días) y todo lo derivado.
     *
     * @param opcionales      personal cuya matriz lo marca como opcional (no entra en la necesidad)
     * @param porRevisar      unidades devueltas en POR_INSPECCION o POR_AUTORIZAR
     * @param stockPorLotes   para consumibles: saldo utilizable (no vencido) de sus lotes; {@code null} en reutilizables
     * @param lotesActivos    para consumibles: lotes con saldo; {@code null} en reutilizables
     * @param lotesPorVencer  para consumibles: lotes con saldo que vencen en {@value #HORIZONTE_DIAS} días
     */
    public record FilaNecesidad(Epp epp, boolean enMatriz, long personal, long opcionales, long requerida,
                                long enPoder, long pendiente, long renovaciones, long disponible, long asignadas,
                                long porRevisar, long enMantenimiento, long cuarentena, long deBaja,
                                Long stockPorLotes, Long lotesActivos, Long lotesPorVencer) {

        public boolean isConsumible() { return epp.isDesechable(); }
        /** ESTIMACIÓN: pendiente (calculado) + renovaciones próximas (estimadas). */
        public long getNecesidad() { return pendiente + renovaciones; }
        public long getFaltante() { return Math.max(0, getNecesidad() - disponible); }
        public long getExcedente() { return Math.max(0, disponible - getNecesidad()); }
        /** Informativo: NO se resta del faltante porque su retorno depende de inspección y autorización humanas. */
        public long getRecuperables() { return isConsumible() ? 0 : porRevisar + enMantenimiento; }
        public long getEnRevision() { return porRevisar + enMantenimiento + cuarentena; }

        public Recomendacion getRecomendacion() {
            if (getNecesidad() == 0) {
                return personal == 0 && !enMatriz ? Recomendacion.DATOS_INSUFICIENTES : Recomendacion.SIN_NECESIDAD;
            }
            return getFaltante() > 0 ? Recomendacion.COMPRAR : Recomendacion.SUFICIENTE;
        }

        /** Costo solo si el EPP tiene costo unitario cargado y hay algo que comprar; si no, {@code null}. */
        public BigDecimal getCostoEstimado() {
            return epp.getCostoUnitario() == null || getFaltante() == 0 ? null
                    : epp.getCostoUnitario().multiply(BigDecimal.valueOf(getFaltante()));
        }

        /** Texto del control por lotes de un consumible. */
        public String getControlLotes() {
            if (stockPorLotes == null) return "";
            return "Control por lotes: " + stockPorLotes + " unidades en " + lotesActivos + " lotes ("
                    + lotesPorVencer + " por vencer en " + HORIZONTE_DIAS + " días)";
        }

        public boolean isSinCosto() { return epp.getCostoUnitario() == null; }

        public String getMotivo() {
            return switch (getRecomendacion()) {
                case COMPRAR -> (isConsumible() ? "Reponer " : "Comprar ") + getFaltante() + " unidades: necesidad estimada "
                        + getNecesidad() + " (pendiente " + pendiente + " + renovaciones " + renovaciones + ") menos "
                        + disponible + " disponibles utilizables."
                        + (getRecuperables() > 0 ? " De la necesidad, " + getRecuperables() + " unidades están en mantenimiento, "
                        + "inspección o por autorizar y podrían recuperarse, pero su retorno depende de autorización humana: "
                        + "no se restaron." : "");
                case SUFICIENTE -> "Stock suficiente: " + disponible + " disponibles para una necesidad estimada de "
                        + getNecesidad() + (getExcedente() > 0 ? " (excedente " + getExcedente() + ")." : " (justo, sin margen).");
                case SIN_NECESIDAD -> "Sin necesidad registrada: ningún trabajador que labora necesita este EPP hoy ni "
                        + "tiene renovaciones en la ventana de " + HORIZONTE_DIAS + " días.";
                case DATOS_INSUFICIENTES -> "Datos insuficientes: este EPP no figura en la matriz de ninguna actividad "
                        + "ni hay personal que lo requiera; no se puede estimar su necesidad.";
            };
        }
    }

    public record Alerta(String nivel, String icono, String texto, String url) {}

    public record Necesidad(List<FilaNecesidad> filas, long laborando, long considerados, long sinMatriz,
                            long tiposConFaltante, long unidadesReponer, long unidadesEnRevision, long unidadesBaja,
                            BigDecimal costoParcial, long tiposSinCosto, List<Alerta> alertas) {}

    private final DotacionService dotacionService;
    private final VigenciaService vigenciaService;
    private final EppRepository eppRepository;
    private final UnidadEppRepository unidadRepository;
    private final MatrizAsignacionRepository matrizRepository;
    private final SedeRepository sedeRepository;
    private final LoteService loteService;

    public NecesidadStockService(DotacionService dotacionService, VigenciaService vigenciaService,
                                 EppRepository eppRepository, UnidadEppRepository unidadRepository,
                                 MatrizAsignacionRepository matrizRepository, SedeRepository sedeRepository,
                                 LoteService loteService) {
        this.loteService = loteService;
        this.dotacionService = dotacionService;
        this.vigenciaService = vigenciaService;
        this.eppRepository = eppRepository;
        this.unidadRepository = unidadRepository;
        this.matrizRepository = matrizRepository;
        this.sedeRepository = sedeRepository;
    }

    @Transactional(readOnly = true)
    public Necesidad calcular(Integer idSede) {
        if (idSede != null && !sedeRepository.existsById(idSede)) {
            throw new ReglaNegocioException("Sede no válida");
        }
        Map<Integer, Map<EstadoUnidad, Long>> stock = new HashMap<>();
        for (Object[] f : unidadRepository.contarPorEppYEstado()) {
            stock.computeIfAbsent((Integer) f[0], k -> new EnumMap<>(EstadoUnidad.class)).put((EstadoUnidad) f[1], (Long) f[2]);
        }
        List<EstadoDotacion> estados = dotacionService.estadoDotacionTodos(idSede);
        // [personal, opcionales, requerida, enPoder, pendiente]
        Map<Integer, long[]> demanda = new HashMap<>();
        long considerados = 0;
        for (EstadoDotacion estado : estados) {
            boolean exige = false;
            for (ItemDotacion item : estado.items()) {
                long[] d = demanda.computeIfAbsent(item.epp().getIdEpp(), k -> new long[5]);
                if (!item.obligatorio()) {
                    d[1]++;
                    continue;
                }
                exige = true;
                d[0]++;
                d[2] += item.cantidadRequerida();
                d[3] += Math.min(item.enPoder(), item.cantidadRequerida());
                d[4] += item.getPendiente();
            }
            if (exige) {
                considerados++;
            }
        }
        Map<Integer, Long> renovaciones = renovacionesProximas(idSede);
        Set<Integer> enMatriz = new HashSet<>();
        matrizRepository.findAll().forEach(m -> enMatriz.add(m.getEpp().getIdEpp()));

        List<FilaNecesidad> filas = new ArrayList<>();
        for (Epp epp : eppRepository.findByEstadoOrderByCategoriaNombreAscNombreAsc(Epp.ACTIVO)) {
            Map<EstadoUnidad, Long> c = stock.getOrDefault(epp.getIdEpp(), Map.of());
            long[] d = demanda.getOrDefault(epp.getIdEpp(), new long[5]);
            Long saldoLotes = null;
            Long lotesActivos = null;
            Long lotesPorVencer = null;
            if (epp.isDesechable()) {
                saldoLotes = loteService.saldo(epp.getIdEpp());
                LocalDate hoy = LocalDate.now();
                var lotes = loteService.lotes(epp.getIdEpp()).stream().filter(l -> l.getSaldo() > 0).toList();
                lotesActivos = (long) lotes.size();
                lotesPorVencer = lotes.stream().filter(l -> l.getFechaVencimiento() != null
                        && !l.getFechaVencimiento().isBefore(hoy)
                        && !l.getFechaVencimiento().isAfter(hoy.plusDays(HORIZONTE_DIAS))).count();
            }
            // Un consumible no tiene unidades: lo utilizable es el saldo de sus lotes.
            long disponible = saldoLotes != null ? saldoLotes : c.getOrDefault(EstadoUnidad.DISPONIBLE, 0L);
            filas.add(new FilaNecesidad(epp, enMatriz.contains(epp.getIdEpp()), d[0], d[1], d[2], d[3], d[4],
                    renovaciones.getOrDefault(epp.getIdEpp(), 0L),
                    disponible, c.getOrDefault(EstadoUnidad.ASIGNADO, 0L),
                    c.getOrDefault(EstadoUnidad.POR_INSPECCION, 0L) + c.getOrDefault(EstadoUnidad.POR_AUTORIZAR, 0L),
                    c.getOrDefault(EstadoUnidad.POR_MANTENIMIENTO, 0L) + c.getOrDefault(EstadoUnidad.EN_MANTENIMIENTO, 0L),
                    c.getOrDefault(EstadoUnidad.EN_CUARENTENA, 0L), c.getOrDefault(EstadoUnidad.DE_BAJA, 0L),
                    saldoLotes, lotesActivos, lotesPorVencer));
        }
        return resumir(filas, estados.size(), considerados);
    }

    /** Vigentes (aún no vencidas) que vencen en la ventana; las vencidas ya figuran como pendiente en la dotación. */
    private Map<Integer, Long> renovacionesProximas(Integer idSede) {
        LocalDate hoy = LocalDate.now();
        Map<Integer, Long> mapa = new HashMap<>();
        for (DetalleEntrega d : vigenciaService.renovacionesHasta(HORIZONTE_DIAS, idSede, null)) {
            if (d.getEntrega().getTrabajador().getEstado().isLaborando() && !d.getFechaRenovacion().isBefore(hoy)) {
                mapa.merge(d.getEpp().getIdEpp(), (long) d.getCantidad(), Long::sum);
            }
        }
        return mapa;
    }

    private Necesidad resumir(List<FilaNecesidad> filas, long laborando, long considerados) {
        BigDecimal costo = BigDecimal.ZERO;
        long sinCosto = 0;
        for (FilaNecesidad f : filas) {
            if (f.getFaltante() > 0) {
                if (f.isSinCosto()) {
                    sinCosto++;
                } else {
                    costo = costo.add(f.getCostoEstimado());
                }
            }
        }
        long tiposConFaltante = filas.stream().filter(f -> f.getFaltante() > 0).count();
        long inspeccion = filas.stream().mapToLong(FilaNecesidad::porRevisar).sum();
        long cuarentena = filas.stream().mapToLong(FilaNecesidad::cuarentena).sum();
        long mantenimiento = filas.stream().mapToLong(FilaNecesidad::enMantenimiento).sum();
        List<Alerta> alertas = new ArrayList<>();
        if (tiposConFaltante > 0) {
            alertas.add(new Alerta("critico", "bi-cart-plus", tiposConFaltante + " tipo(s) de EPP con faltante de stock.", "/inventario"));
        }
        if (inspeccion > 0) {
            alertas.add(new Alerta("alerta", "bi-search", inspeccion + " unidad(es) devueltas pendientes de inspección o autorización.", "/inventario/devoluciones"));
        }
        if (cuarentena > 0) {
            alertas.add(new Alerta("critico", "bi-shield-exclamation", cuarentena + " unidad(es) en cuarentena.", "/inventario/unidades?estado=EN_CUARENTENA"));
        }
        if (mantenimiento > 0) {
            alertas.add(new Alerta("alerta", "bi-tools", mantenimiento + " unidad(es) en mantenimiento o aptas para él.", "/inventario/mantenimiento"));
        }
        return new Necesidad(filas, laborando, considerados, laborando - considerados, tiposConFaltante,
                filas.stream().mapToLong(FilaNecesidad::getFaltante).sum(), inspeccion + cuarentena + mantenimiento,
                filas.stream().mapToLong(FilaNecesidad::deBaja).sum(), costo, sinCosto, alertas);
    }
}
