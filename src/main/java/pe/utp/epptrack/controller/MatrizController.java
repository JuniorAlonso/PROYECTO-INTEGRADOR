package pe.utp.epptrack.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import pe.utp.epptrack.model.Actividad;
import pe.utp.epptrack.model.Epp;
import pe.utp.epptrack.service.MatrizEdicionService;
import pe.utp.epptrack.service.MatrizVersionService;
import pe.utp.epptrack.service.RecursoNoEncontradoException;
import pe.utp.epptrack.service.ReglaNegocioException;
import org.springframework.security.core.Authentication;
import pe.utp.epptrack.repository.ActividadRepository;
import pe.utp.epptrack.repository.EppRepository;
import pe.utp.epptrack.repository.MatrizAsignacionRepository;
import pe.utp.epptrack.repository.ProcesoRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Matriz de EPP: equipos exigidos por cada actividad laboral (RF-03, CU-04). */
@Controller
@RequestMapping("/matriz")
public class MatrizController {

    private final MatrizAsignacionRepository matrizRepository;
    private final ActividadRepository actividadRepository;
    private final ProcesoRepository procesoRepository;
    private final EppRepository eppRepository;
    private final MatrizVersionService versionService;
    private final MatrizEdicionService edicionService;

    public MatrizController(MatrizAsignacionRepository matrizRepository, ActividadRepository actividadRepository,
                            ProcesoRepository procesoRepository, EppRepository eppRepository,
                            MatrizVersionService versionService, MatrizEdicionService edicionService) {
        this.edicionService = edicionService;
        this.versionService = versionService;
        this.matrizRepository = matrizRepository;
        this.actividadRepository = actividadRepository;
        this.procesoRepository = procesoRepository;
        this.eppRepository = eppRepository;
    }

    @GetMapping
    public String ver(@RequestParam(required = false) Integer actividad, Model model) {
        List<Actividad> actividades = actividadRepository.findAllByOrderByProcesoNombreAscNombreAsc();
        Map<String, List<Actividad>> porProceso = actividades.stream()
                .collect(Collectors.groupingBy(a -> a.getProceso().getNombre(), LinkedHashMap::new, Collectors.toList()));
        Map<Integer, Long> reglasPorActividad = matrizRepository.findAll().stream()
                .collect(Collectors.groupingBy(m -> m.getActividad().getIdActividad(), Collectors.counting()));

        Actividad seleccionada = actividad == null ? (actividades.isEmpty() ? null : actividades.getFirst())
                : actividadRepository.findById(actividad).orElse(null);
        model.addAttribute("porProceso", porProceso);
        model.addAttribute("reglasPorActividad", reglasPorActividad);
        model.addAttribute("seleccionada", seleccionada);
        model.addAttribute("reglas", seleccionada == null ? List.of()
                : matrizRepository.findByActividadIdActividadOrderByEppNombreAsc(seleccionada.getIdActividad()));
        model.addAttribute("epps", eppRepository.findByEstadoOrderByCategoriaNombreAscNombreAsc(Epp.ACTIVO));
        model.addAttribute("procesos", procesoRepository.findAllByOrderByNombreAsc());
        model.addAttribute("estadoMatriz", versionService.estado());
        model.addAttribute("versiones", versionService.historial());
        return "matriz/ver";
    }

    @PostMapping
    public String agregarRegla(@RequestParam Integer idActividad, @RequestParam Integer idEpp,
                               @RequestParam(defaultValue = "1") Integer cantidad,
                               @RequestParam(defaultValue = "false") boolean obligatorio,
                               Authentication auth, RedirectAttributes flash) {
        try {
            flash.addFlashAttribute("exito", edicionService.agregarRegla(idActividad, idEpp, cantidad, obligatorio, auth.getName()));
        } catch (RecursoNoEncontradoException e) {
            throw e;
        } catch (ReglaNegocioException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/matriz?actividad=" + idActividad;
    }

    /** Vista previa del impacto de publicar, sin crear versiones ni modificar nada (RF-21). */
    @GetMapping("/previa")
    public String previa(Model model) {
        model.addAttribute("previa", versionService.vistaPrevia());
        return "matriz/previa";
    }

    /** Diferencias de una versión publicada frente a la anterior. */
    @GetMapping("/versiones/{id}")
    public String comparar(@PathVariable Integer id, Model model) {
        model.addAttribute("comparacion", versionService.comparar(id));
        return "matriz/comparar";
    }

    /** Publica la matriz de trabajo como versión oficial y muestra a quién afecta el cambio (RF-21). */
    @PostMapping("/publicar")
    public String publicar(@RequestParam String codigo, @RequestParam(required = false) String descripcion,
                           Authentication auth, RedirectAttributes flash) {
        try {
            MatrizVersionService.Impacto impacto = versionService.publicar(codigo, descripcion, auth.getName());
            flash.addFlashAttribute("impacto", impacto);
            flash.addFlashAttribute("exito", "Versión " + impacto.version().getCodigo() + " publicada con "
                    + impacto.version().getTotalReglas() + " reglas.");
        } catch (ReglaNegocioException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/matriz";
    }

    @PostMapping("/{id}/quitar")
    public String quitarRegla(@PathVariable Integer id, Authentication auth, RedirectAttributes flash) {
        MatrizEdicionService.RetiroRegla retiro = edicionService.quitarRegla(id, auth.getName());
        flash.addFlashAttribute("exito", retiro.mensaje());
        return "redirect:/matriz?actividad=" + retiro.idActividad();
    }

    @PostMapping("/actividades")
    public String agregarActividad(@RequestParam String proceso, @RequestParam String nombre,
                                   Authentication auth, RedirectAttributes flash) {
        try {
            Integer id = edicionService.agregarActividad(proceso, nombre, auth.getName());
            flash.addFlashAttribute("exito", "Actividad creada. Agregue los EPP que exige.");
            return "redirect:/matriz?actividad=" + id;
        } catch (ReglaNegocioException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/matriz";
        }
    }
}
