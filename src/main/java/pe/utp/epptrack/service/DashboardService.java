package pe.utp.epptrack.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.dto.CumplimientoSede;
import pe.utp.epptrack.dto.EstadoDotacion;
import pe.utp.epptrack.model.DetalleEntrega;
import pe.utp.epptrack.model.EstadoVigencia;
import pe.utp.epptrack.repository.DetalleEntregaRepository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;

/** Indicadores del dashboard (Anexo 23). */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    private static final Locale ES = Locale.of("es", "PE");

    private final DotacionService dotacionService;
    private final VigenciaService vigenciaService;
    private final DetalleEntregaRepository detalleRepository;

    public DashboardService(DotacionService dotacionService, VigenciaService vigenciaService,
                            DetalleEntregaRepository detalleRepository) {
        this.dotacionService = dotacionService;
        this.vigenciaService = vigenciaService;
        this.detalleRepository = detalleRepository;
    }

    public Map<String, Object> indicadores(Integer idSede) {
        List<EstadoDotacion> estados = dotacionService.estadoDotacionTodos(idSede);
        long completos = estados.stream().filter(EstadoDotacion::isCompleta).count();
        List<DetalleEntrega> alertas = vigenciaService.alertas(idSede, null);
        long vencidos = alertas.stream().filter(d -> d.getEstadoVigencia() == EstadoVigencia.VENCIDO).count();
        long porVencer = alertas.size() - vencidos;
        List<CumplimientoSede> porSede = dotacionService.cumplimientoPorSede(estados);

        Map<String, Object> datos = new LinkedHashMap<>();
        datos.put("trabajadoresActivos", estados.size());
        datos.put("porcentajeDotacion", estados.isEmpty() ? 0 : Math.round(completos * 100.0 / estados.size()));
        datos.put("dotacionIncompleta", estados.size() - completos);
        datos.put("vencidos", vencidos);
        datos.put("porVencer", porVencer);
        datos.put("alertas", alertas.stream().limit(8).toList());
        datos.put("cumplimientoSedes", porSede);
        datos.putAll(entregasPorMes(idSede, 6));
        datos.put("diasPorVencer", vigenciaService.getDiasPorVencer());
        datos.put("vigentes", detalleRepository.countByEstadoVigencia(EstadoVigencia.VIGENTE));
        return datos;
    }

    /** Entregas (unidades de EPP) y entregas duplicadas justificadas de los últimos meses. */
    private Map<String, Object> entregasPorMes(Integer idSede, int meses) {
        YearMonth actual = YearMonth.now();
        YearMonth inicio = actual.minusMonths(meses - 1L);
        List<DetalleEntrega> detalles = detalleRepository.findByEntregaFechaEntregaBetween(
                inicio.atDay(1), actual.atEndOfMonth());

        List<String> etiquetas = new ArrayList<>();
        List<Long> totales = new ArrayList<>();
        List<Long> duplicadas = new ArrayList<>();
        for (int i = 0; i < meses; i++) {
            YearMonth mes = inicio.plusMonths(i);
            etiquetas.add(mes.getMonth().getDisplayName(TextStyle.SHORT, ES) + " " + mes.getYear() % 100);
            List<DetalleEntrega> delMes = detalles.stream()
                    .filter(d -> YearMonth.from(d.getEntrega().getFechaEntrega()).equals(mes))
                    .filter(d -> idSede == null || d.getEntrega().getSede().getIdSede().equals(idSede))
                    .filter(d -> d.getEstadoVigencia() != EstadoVigencia.ANULADO)
                    .toList();
            totales.add((long) delMes.size());
            duplicadas.add(delMes.stream().filter(DetalleEntrega::isDuplicadoJustificado).count());
        }
        long maximo = Math.max(1, totales.stream().mapToLong(Long::longValue).max().orElse(1));
        return Map.of("mesesEtiquetas", etiquetas, "mesesTotales", totales, "mesesDuplicadas", duplicadas,
                "mesesMaximo", maximo, "hoy", LocalDate.now());
    }
}
