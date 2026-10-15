package pe.utp.epptrack.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import pe.utp.epptrack.model.Trabajador;
import pe.utp.epptrack.repository.*;
import pe.utp.epptrack.service.DotacionService;
import pe.utp.epptrack.service.TrabajadorService;

import java.util.List;

/** Historial de EPP por trabajador a lo largo de sus campañas (CU-06, RF-08, RN-04). */
@Controller
@RequestMapping("/historial")
public class HistorialController {

    private final TrabajadorService trabajadorService;
    private final DotacionService dotacionService;
    private final PeriodoLaboralRepository periodoRepository;
    private final EntregaRepository entregaRepository;
    private final HistorialEppRepository historialRepository;
    private final CorreccionEntregaRepository correccionRepository;
    private final SedeRepository sedeRepository;
    private final ActividadRepository actividadRepository;

    public HistorialController(TrabajadorService trabajadorService, DotacionService dotacionService,
                               PeriodoLaboralRepository periodoRepository, EntregaRepository entregaRepository,
                               HistorialEppRepository historialRepository,
                               CorreccionEntregaRepository correccionRepository,
                               SedeRepository sedeRepository, ActividadRepository actividadRepository) {
        this.trabajadorService = trabajadorService;
        this.dotacionService = dotacionService;
        this.periodoRepository = periodoRepository;
        this.entregaRepository = entregaRepository;
        this.historialRepository = historialRepository;
        this.correccionRepository = correccionRepository;
        this.sedeRepository = sedeRepository;
        this.actividadRepository = actividadRepository;
    }

    /** {@code dni} llega desde los enlaces del sistema; {@code q} desde el buscador (DNI o nombre). */
    @GetMapping
    public String ver(@RequestParam(required = false) String dni,
                      @RequestParam(required = false) String q,
                      Model model) {
        String termino = dni != null && !dni.isBlank() ? dni : q;
        model.addAttribute("q", termino);
        if (termino == null || termino.isBlank()) {
            return "historial/ver";
        }
        List<Trabajador> encontrados = trabajadorService.buscar(termino, null, null);
        if (encontrados.isEmpty()) {
            model.addAttribute("error", "No se encontró ningún trabajador con \"" + termino.trim() + "\".");
            return "historial/ver";
        }
        if (encontrados.size() > 1) {
            model.addAttribute("coincidencias", encontrados);
            return "historial/ver";
        }
        Trabajador t = encontrados.get(0);
        Integer id = t.getIdTrabajador();
        model.addAttribute("trabajador", t);
        model.addAttribute("dotacion", dotacionService.estadoDotacion(t));
        model.addAttribute("periodos", periodoRepository.findByTrabajadorIdTrabajadorOrderByFechaIngresoDesc(id));
        model.addAttribute("entregas", entregaRepository.findByTrabajadorIdTrabajadorOrderByFechaEntregaDescIdEntregaDesc(id));
        model.addAttribute("bitacora", historialRepository.findByTrabajadorIdTrabajadorOrderByFechaRegistroDescIdHistorialDesc(id));
        model.addAttribute("correcciones", correccionRepository.findByDetalleEntregaTrabajadorIdTrabajadorOrderByFechaCorreccionDesc(id));
        model.addAttribute("sedes", sedeRepository.findAllByOrderByNombreAsc());
        model.addAttribute("actividades", actividadRepository.findAllByOrderByProcesoNombreAscNombreAsc());
        return "historial/ver";
    }
}
