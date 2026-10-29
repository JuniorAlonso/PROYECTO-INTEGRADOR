package pe.utp.epptrack.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.dto.EstadoDotacion;
import pe.utp.epptrack.dto.ItemDotacion;
import pe.utp.epptrack.model.*;
import pe.utp.epptrack.repository.DetalleEntregaRepository;
import pe.utp.epptrack.repository.EppRepository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Panel de excepciones (RF-09, RF-13, RF-22, RF-27, RN-01, RN-05, RN-06, RN-08): reúne en una sola lista lo que
 * requiere atención humana. Todo se calcula de datos reales; cada caso es una combinación (tipo, trabajador, EPP)
 * que aparece una sola vez, aunque varias fuentes lo detecten.
 */
@Service
@Transactional(readOnly = true)
public class ExcepcionService {

    public static final int DIAS_POR_DEFECTO = 90;
    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public enum Tipo {
        ENTREGA_INCOMPLETA("Entrega incompleta", "bi-exclamation-triangle", "danger"),
        EPP_FALTANTE("EPP faltante", "bi-x-octagon", "danger"),
        EPP_VENCIDO("EPP vencido", "bi-hourglass-bottom", "danger"),
        POR_VENCER("Por vencer", "bi-hourglass-split", "warning"),
        NO_EXIGIDO_JUSTIFICADO("No exigido (justificado)", "bi-patch-question", "primary"),
        DUPLICADO_JUSTIFICADO("Duplicado (justificado)", "bi-files", "primary"),
        EXCESO_JUSTIFICADO("Exceso (justificado)", "bi-plus-circle", "primary"),
        POSIBLE_FICHA_DUPLICADA("Posible ficha duplicada", "bi-people", "warning"),
        CLASE_PENDIENTE_VALIDACION("Clase de EPP sin validar", "bi-clipboard-check", "warning"),
        INTEGRIDAD_AUDITORIA("Integridad de auditoría", "bi-shield-exclamation", "danger");

        private final String etiqueta;
        private final String icono;
        private final String color;
        Tipo(String etiqueta, String icono, String color) { this.etiqueta = etiqueta; this.icono = icono; this.color = color; }
        public String getEtiqueta() { return etiqueta; }
        public String getIcono() { return icono; }
        public String getColor() { return color; }
    }

    /** Un caso: trabajador/sede/EPP pueden ser nulos si el caso no depende de ellos (p. ej. integridad). */
    public record Excepcion(Tipo tipo, String trabajador, String dni, Integer idSede, String sede, String epp,
                            String detalle, LocalDate fecha, String enlace) {}

    private final DotacionService dotacionService;
    private final VigenciaService vigenciaService;
    private final DetalleEntregaRepository detalleRepository;
    private final EppRepository eppRepository;
    private final IdentidadService identidadService;
    private final AuditoriaService auditoriaService;

    public ExcepcionService(DotacionService dotacionService, VigenciaService vigenciaService,
                            DetalleEntregaRepository detalleRepository, EppRepository eppRepository,
                            IdentidadService identidadService, AuditoriaService auditoriaService) {
        this.dotacionService = dotacionService;
        this.vigenciaService = vigenciaService;
        this.detalleRepository = detalleRepository;
        this.eppRepository = eppRepository;
        this.identidadService = identidadService;
        this.auditoriaService = auditoriaService;
    }

    /** Valida el periodo en días (1 a 3650); nulo = valor por defecto. */
    public static int validarDias(Integer dias) {
        if (dias == null) return DIAS_POR_DEFECTO;
        if (dias < 1 || dias > 3650) throw new ReglaNegocioException("El periodo debe estar entre 1 y 3650 días.");
        return dias;
    }

    /** Convierte el texto del filtro; vacío = todos. */
    public static Tipo parsearTipo(String texto) {
        if (texto == null || texto.isBlank()) return null;
        try {
            return Tipo.valueOf(texto.trim());
        } catch (IllegalArgumentException e) {
            throw new ReglaNegocioException("Tipo de excepción no válido.");
        }
    }

    /** Todas las excepciones del periodo, sin doble conteo, filtradas por tipo y sede (ambos opcionales). */
    public List<Excepcion> listar(Tipo tipo, Integer idSede, int dias) {
        Map<String, Excepcion> unicas = new LinkedHashMap<>();
        agregarDotacionYVigencia(unicas, idSede);
        agregarJustificadas(unicas, idSede, dias);
        agregarFichasDuplicadas(unicas, idSede);
        if (idSede == null) {
            agregarClasesPendientes(unicas);
            agregarIntegridad(unicas);
        }
        return unicas.values().stream().filter(e -> tipo == null || e.tipo() == tipo).toList();
    }

    /** Cantidad de casos por tipo (todos los tipos, con 0 si no hay). */
    public Map<Tipo, Long> contarPorTipo(Integer idSede, int dias) {
        Map<Tipo, Long> conteo = new EnumMap<>(Tipo.class);
        for (Tipo t : Tipo.values()) conteo.put(t, 0L);
        listar(null, idSede, dias).forEach(e -> conteo.merge(e.tipo(), 1L, Long::sum));
        return conteo;
    }

    private void poner(Map<String, Excepcion> mapa, Excepcion e, Integer idTrabajador, Integer idEpp) {
        mapa.putIfAbsent(e.tipo() + "|" + idTrabajador + "|" + idEpp, e);
    }

    private Excepcion deTrabajador(Tipo tipo, Trabajador t, Epp epp, String detalle, LocalDate fecha, String enlace) {
        return new Excepcion(tipo, t.getNombreCompleto(), t.getDni(), t.getSede().getIdSede(), t.getSede().getNombre(),
                epp == null ? "—" : epp.getNombre(), detalle, fecha, enlace);
    }

    private void agregarDotacionYVigencia(Map<String, Excepcion> mapa, Integer idSede) {
        // Vencidos primero (por trabajador+EPP, el más reciente) para no contarlos también como faltantes.
        Map<String, DetalleEntrega> vencidos = new LinkedHashMap<>();
        for (DetalleEntrega d : vigenciaService.alertas(idSede, EstadoVigencia.VENCIDO)) {
            vencidos.merge(d.getEntrega().getTrabajador().getIdTrabajador() + "|" + d.getEpp().getIdEpp(), d,
                    (a, b) -> a.getFechaRenovacion().isAfter(b.getFechaRenovacion()) ? a : b);
        }
        vencidos.values().forEach(d -> {
            Trabajador t = d.getEntrega().getTrabajador();
            poner(mapa, deTrabajador(Tipo.EPP_VENCIDO, t, d.getEpp(), "Venció el " + F.format(d.getFechaRenovacion())
                    + " (hace " + (-d.getDiasRestantes()) + " días)", d.getFechaRenovacion(),
                    "/entregas/nueva?dni=" + t.getDni()), t.getIdTrabajador(), d.getEpp().getIdEpp());
        });
        for (DetalleEntrega d : vigenciaService.alertas(idSede, EstadoVigencia.POR_VENCER)) {
            Trabajador t = d.getEntrega().getTrabajador();
            poner(mapa, deTrabajador(Tipo.POR_VENCER, t, d.getEpp(), "Vence el " + F.format(d.getFechaRenovacion())
                    + " (en " + d.getDiasRestantes() + " días)", d.getFechaRenovacion(),
                    "/entregas/nueva?dni=" + t.getDni()), t.getIdTrabajador(), d.getEpp().getIdEpp());
        }
        for (EstadoDotacion ed : dotacionService.estadoDotacionTodos(idSede)) {
            Trabajador t = ed.trabajador();
            for (ItemDotacion i : ed.items()) {
                if (!i.obligatorio()) continue;
                String enlace = "/entregas/nueva?dni=" + t.getDni();
                if (i.isParcial()) {
                    poner(mapa, deTrabajador(Tipo.ENTREGA_INCOMPLETA, t, i.epp(), "Tiene " + i.enPoder() + " de "
                            + i.cantidadRequerida() + " exigidos; faltan " + i.getPendiente(),
                            i.enUso() == null ? null : i.enUso().getEntrega().getFechaEntrega(), enlace),
                            t.getIdTrabajador(), i.epp().getIdEpp());
                } else if (i.enPoder() == 0 && !vencidos.containsKey(t.getIdTrabajador() + "|" + i.epp().getIdEpp())) {
                    poner(mapa, deTrabajador(Tipo.EPP_FALTANTE, t, i.epp(), "Tiene 0 de " + i.cantidadRequerida()
                            + " exigidos; sin entrega vigente ni vencida", null, enlace),
                            t.getIdTrabajador(), i.epp().getIdEpp());
                }
            }
        }
    }

    private void agregarJustificadas(Map<String, Excepcion> mapa, Integer idSede, int dias) {
        LocalDate hoy = LocalDate.now();
        List<DetalleEntrega> detalles = detalleRepository.findByEntregaFechaEntregaBetween(hoy.minusDays(dias), hoy).stream()
                .filter(d -> d.getEstadoVigencia() != EstadoVigencia.ANULADO)
                .filter(d -> idSede == null || d.getEntrega().getTrabajador().getSede().getIdSede().equals(idSede))
                .sorted(Comparator.comparing((DetalleEntrega d) -> d.getEntrega().getFechaEntrega()).reversed()
                        .thenComparing(Comparator.comparing(DetalleEntrega::getIdDetalle).reversed()))
                .toList();
        for (DetalleEntrega d : detalles) {
            Trabajador t = d.getEntrega().getTrabajador();
            LocalDate fecha = d.getEntrega().getFechaEntrega();
            String enlace = "/historial?dni=" + t.getDni();
            if (d.isNoExigidoJustificado()) {
                poner(mapa, deTrabajador(Tipo.NO_EXIGIDO_JUSTIFICADO, t, d.getEpp(),
                        "Entregó " + d.getCantidad() + " que la matriz no exigía. Motivo: " + d.getJustificacionNoExigido(),
                        fecha, enlace), t.getIdTrabajador(), d.getEpp().getIdEpp());
            }
            if (d.isDuplicadoJustificado()) {
                poner(mapa, deTrabajador(Tipo.DUPLICADO_JUSTIFICADO, t, d.getEpp(),
                        "Entregó " + d.getCantidad() + " teniendo uno vigente. Motivo: " + d.getJustificacionDuplicado(),
                        fecha, enlace), t.getIdTrabajador(), d.getEpp().getIdEpp());
            }
            if (d.isExcesoJustificado()) {
                String exigida = d.getCantidadExigida() == null ? "" : " (exigido " + d.getCantidadExigida() + ")";
                poner(mapa, deTrabajador(Tipo.EXCESO_JUSTIFICADO, t, d.getEpp(),
                        "Entregó " + d.getCantidad() + exigida + ", más de lo que faltaba. Motivo: " + d.getJustificacionExceso(),
                        fecha, enlace), t.getIdTrabajador(), d.getEpp().getIdEpp());
            }
        }
    }

    private void agregarFichasDuplicadas(Map<String, Excepcion> mapa, Integer idSede) {
        for (List<Trabajador> grupo : identidadService.gruposPosiblesDuplicados()) {
            for (Trabajador t : grupo) {
                if (idSede != null && !t.getSede().getIdSede().equals(idSede)) continue;
                String otros = String.join(", ", grupo.stream().filter(o -> o != t).map(Trabajador::getDni).toList());
                poner(mapa, deTrabajador(Tipo.POSIBLE_FICHA_DUPLICADA, t, null, "Mismo nombre que la ficha con DNI "
                        + otros + ". Verifique si es la misma persona", null, "/historial?dni=" + t.getDni()),
                        t.getIdTrabajador(), null);
            }
        }
    }

    private void agregarClasesPendientes(Map<String, Excepcion> mapa) {
        for (Epp e : eppRepository.findByEstadoOrderByCategoriaNombreAscNombreAsc(Epp.ACTIVO)) {
            if (!e.isClasePendienteValidacion()) continue;
            poner(mapa, new Excepcion(Tipo.CLASE_PENDIENTE_VALIDACION, null, null, null, null, e.getNombre(),
                    "La clase (reutilizable, personal, consumible) aún no fue validada por SST", null,
                    "/epp/" + e.getIdEpp() + "/editar"), null, e.getIdEpp());
        }
    }

    private void agregarIntegridad(Map<String, Excepcion> mapa) {
        AuditoriaService.Verificacion v = auditoriaService.verificar();
        if (v.integra()) return;
        poner(mapa, new Excepcion(Tipo.INTEGRIDAD_AUDITORIA, null, null, null, null, "—",
                "La cadena de auditoría no coincide; primer registro alterado: N.° " + v.primerRegistroAlterado(),
                null, "/auditoria"), null, null);
    }
}
