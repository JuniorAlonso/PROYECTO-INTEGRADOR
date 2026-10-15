package pe.utp.epptrack.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.dto.CumplimientoSede;
import pe.utp.epptrack.dto.EstadoDotacion;
import pe.utp.epptrack.dto.Reporte;
import pe.utp.epptrack.model.DetalleEntrega;
import pe.utp.epptrack.model.Entrega;
import pe.utp.epptrack.model.Trabajador;
import pe.utp.epptrack.repository.EntregaRepository;
import pe.utp.epptrack.repository.SedeRepository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Reportes R-01 a R-04 (RF-11, RF-12, Anexo 22). */
@Service
@Transactional(readOnly = true)
public class ReporteService {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final DotacionService dotacionService;
    private final VigenciaService vigenciaService;
    private final TrabajadorService trabajadorService;
    private final EntregaRepository entregaRepository;
    private final SedeRepository sedeRepository;

    public ReporteService(DotacionService dotacionService, VigenciaService vigenciaService,
                          TrabajadorService trabajadorService, EntregaRepository entregaRepository,
                          SedeRepository sedeRepository) {
        this.dotacionService = dotacionService;
        this.vigenciaService = vigenciaService;
        this.trabajadorService = trabajadorService;
        this.entregaRepository = entregaRepository;
        this.sedeRepository = sedeRepository;
    }

    /** R-01: cumplimiento de dotación por trabajador. {@code estado}: COMPLETA, INCOMPLETA o null. */
    public Reporte r01(Integer idSede, String estado) {
        List<List<String>> filas = new ArrayList<>();
        for (EstadoDotacion e : dotacionService.estadoDotacionTodos(idSede)) {
            if ("COMPLETA".equals(estado) && !e.isCompleta()) continue;
            if ("INCOMPLETA".equals(estado) && e.isCompleta()) continue;
            Trabajador t = e.trabajador();
            filas.add(List.of(t.getDni(), t.getNombreCompleto(), t.getSede().getNombre(),
                    e.actividades().isEmpty() ? "Sin actividad"
                            : e.actividades().stream().map(a -> a.getNombre()).collect(Collectors.joining(", ")),
                    String.valueOf(e.getObligatorios()), String.valueOf(e.getCubiertos()), e.getPorcentaje() + "%",
                    e.getFaltantes().stream().map(i -> i.epp().getNombre()).collect(Collectors.joining(", ")),
                    e.isCompleta() ? "Completa" : "Incompleta"));
        }
        return new Reporte("R-01", "Cumplimiento de dotación por trabajador",
                List.of("Sede: " + nombreSede(idSede), "Estado: " + (estado == null ? "Todos" : estado.toLowerCase())),
                List.of("DNI", "Trabajador", "Sede", "Actividad", "Obligatorios", "Cubiertos", "Cumplimiento", "Faltantes", "Estado"),
                filas);
    }

    /** R-02: cumplimiento de dotación por sede. */
    public Reporte r02(Integer idSede) {
        List<CumplimientoSede> sedes = dotacionService.cumplimientoPorSede(dotacionService.estadoDotacionTodos(idSede));
        List<List<String>> filas = sedes.stream()
                .map(c -> List.of(c.sede().getNombre(), String.valueOf(c.trabajadores()), String.valueOf(c.completos()),
                        String.valueOf(c.getIncompletos()), c.getPorcentaje() + "%"))
                .toList();
        return new Reporte("R-02", "Cumplimiento de dotación por sede",
                List.of("Sede: " + nombreSede(idSede), "Corte al " + LocalDate.now().format(FECHA)),
                List.of("Sede", "Trabajadores", "Dotación completa", "Dotación incompleta", "Cumplimiento"), filas);
    }

    /** R-03: vigencias y renovaciones dentro de un horizonte de días. */
    public Reporte r03(int dias, Integer idSede, Integer idCategoria) {
        int horizonte = Math.max(0, Math.min(dias, 3650));
        List<List<String>> filas = vigenciaService.renovacionesHasta(horizonte, idSede, idCategoria).stream()
                .map(d -> {
                    Trabajador t = d.getEntrega().getTrabajador();
                    return List.of(t.getNombreCompleto(), t.getDni(), t.getSede().getNombre(), d.getEpp().getNombre(),
                            d.getEpp().getCategoria().getNombre(), d.getEntrega().getFechaEntrega().format(FECHA),
                            d.getFechaRenovacion().format(FECHA), String.valueOf(d.getDiasRestantes()),
                            d.getEstadoVigencia().getEtiqueta());
                }).toList();
        return new Reporte("R-03", "Vigencias y renovaciones de EPP",
                List.of("Sede: " + nombreSede(idSede), "Horizonte: próximos " + horizonte + " días"),
                List.of("Trabajador", "DNI", "Sede", "EPP", "Categoría", "Entrega", "Renovación", "Días", "Estado"), filas);
    }

    /** R-04: constancia de entrega de un trabajador (equivalente al formato FOR-SST 9.3.2). */
    public Reporte r04(String dni, LocalDate desde, LocalDate hasta) {
        Trabajador t = trabajadorService.buscarPorDni(dni)
                .orElseThrow(() -> new ReglaNegocioException("No existe un trabajador con el DNI " + (dni == null ? "" : dni.trim()) + "."));
        LocalDate d = desde != null ? desde : LocalDate.of(2000, 1, 1);
        LocalDate h = hasta != null ? hasta : LocalDate.now();
        List<List<String>> filas = new ArrayList<>();
        for (Entrega e : entregaRepository.findByTrabajadorIdTrabajadorAndFechaEntregaBetweenOrderByFechaEntregaDesc(
                t.getIdTrabajador(), d, h)) {
            for (DetalleEntrega det : e.getDetalles()) {
                filas.add(List.of(e.getFechaEntrega().format(FECHA), e.getMotivo().getEtiqueta(), e.getSede().getNombre(),
                        e.getUsuario().getNombreUsuario(), det.getEpp().getNombre(), String.valueOf(det.getCantidad()),
                        det.getFechaRenovacion().format(FECHA), det.getEstadoVigencia().getEtiqueta(),
                        det.getJustificacionDuplicado() == null ? "" : det.getJustificacionDuplicado()));
            }
        }
        return new Reporte("R-04", "Constancia de entrega de EPP",
                List.of("Trabajador: " + t.getNombreCompleto() + " (DNI " + t.getDni() + ")",
                        "Puesto: " + t.getPuesto().getNombre() + " · Sede: " + t.getSede().getNombre(),
                        "Periodo: " + (desde == null ? "inicio" : desde.format(FECHA)) + " al " + h.format(FECHA)),
                List.of("Fecha", "Motivo", "Sede", "Responsable", "EPP", "Cant.", "Renovación", "Estado", "Justificación"), filas);
    }

    private String nombreSede(Integer idSede) {
        return idSede == null ? "Todas" : sedeRepository.findById(idSede).map(s -> s.getNombre()).orElse("Todas");
    }
}
