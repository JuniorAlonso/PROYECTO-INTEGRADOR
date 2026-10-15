package pe.utp.epptrack.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.model.DetalleEntrega;
import pe.utp.epptrack.model.EstadoVigencia;
import pe.utp.epptrack.repository.DetalleEntregaRepository;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/** Control de vigencia de los EPP entregados y alertas de vencimiento (RF-06, RF-09). */
@Service
public class VigenciaService {

    private static final Logger log = LoggerFactory.getLogger(VigenciaService.class);

    private final DetalleEntregaRepository detalleRepository;
    private final int diasPorVencer;

    public VigenciaService(DetalleEntregaRepository detalleRepository,
                           @Value("${app.alertas.dias-por-vencer:15}") int diasPorVencer) {
        this.detalleRepository = detalleRepository;
        this.diasPorVencer = diasPorVencer;
    }

    public int getDiasPorVencer() { return diasPorVencer; }

    /** Estado que corresponde a un EPP en uso según su fecha de renovación. */
    public EstadoVigencia calcular(LocalDate fechaRenovacion) {
        LocalDate hoy = LocalDate.now();
        if (fechaRenovacion.isBefore(hoy)) {
            return EstadoVigencia.VENCIDO;
        }
        return fechaRenovacion.isAfter(hoy.plusDays(diasPorVencer)) ? EstadoVigencia.VIGENTE : EstadoVigencia.POR_VENCER;
    }

    /** Recalcula los estados una vez al día (00:05) y al iniciar la aplicación. */
    @Scheduled(cron = "0 5 0 * * *")
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void actualizarEstados() {
        LocalDate hoy = LocalDate.now();
        int vencidos = detalleRepository.marcarVencidos(hoy);
        int porVencer = detalleRepository.marcarPorVencer(hoy, hoy.plusDays(diasPorVencer));
        log.info("Vigencias actualizadas: {} vencidos, {} por vencer", vencidos, porVencer);
    }

    /** EPP vencidos y por vencer de trabajadores que siguen laborando, ordenados por urgencia. */
    @Transactional(readOnly = true)
    public List<DetalleEntrega> alertas(Integer idSede, EstadoVigencia soloEstado) {
        var estados = soloEstado != null ? EnumSet.of(soloEstado)
                : EnumSet.of(EstadoVigencia.VENCIDO, EstadoVigencia.POR_VENCER);
        return detalleRepository.findByEstadoVigenciaInOrderByFechaRenovacionAsc(estados).stream()
                .filter(d -> d.getEntrega().getTrabajador().getEstado().isLaborando())
                .filter(d -> idSede == null || d.getEntrega().getTrabajador().getSede().getIdSede().equals(idSede))
                .sorted(Comparator.comparing(DetalleEntrega::getFechaRenovacion))
                .toList();
    }

    /** EPP en uso o vencidos cuya renovación cae dentro de los próximos {@code horizonteDias} días (reporte R-03). */
    @Transactional(readOnly = true)
    public List<DetalleEntrega> renovacionesHasta(int horizonteDias, Integer idSede, Integer idCategoria) {
        LocalDate limite = LocalDate.now().plusDays(horizonteDias);
        var estados = EnumSet.of(EstadoVigencia.VIGENTE, EstadoVigencia.POR_VENCER, EstadoVigencia.VENCIDO);
        return detalleRepository.findByEstadoVigenciaInOrderByFechaRenovacionAsc(estados).stream()
                .filter(d -> d.getEntrega().getTrabajador().getEstado().isLaborando())
                .filter(d -> !d.getFechaRenovacion().isAfter(limite))
                .filter(d -> idSede == null || d.getEntrega().getTrabajador().getSede().getIdSede().equals(idSede))
                .filter(d -> idCategoria == null || d.getEpp().getCategoria().getIdCategoria().equals(idCategoria))
                .toList();
    }
}
