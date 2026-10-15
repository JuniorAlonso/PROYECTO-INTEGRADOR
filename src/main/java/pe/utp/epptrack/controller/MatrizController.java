package pe.utp.epptrack.controller;

import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import pe.utp.epptrack.model.Actividad;
import pe.utp.epptrack.model.Epp;
import pe.utp.epptrack.model.MatrizAsignacion;
import pe.utp.epptrack.model.Proceso;
import pe.utp.epptrack.service.RecursoNoEncontradoException;
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

    public MatrizController(MatrizAsignacionRepository matrizRepository, ActividadRepository actividadRepository,
                            ProcesoRepository procesoRepository, EppRepository eppRepository) {
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
        return "matriz/ver";
    }

    @PostMapping
    public String agregarRegla(@RequestParam Integer idActividad, @RequestParam Integer idEpp,
                               @RequestParam(defaultValue = "1") Integer cantidad,
                               @RequestParam(defaultValue = "false") boolean obligatorio,
                               RedirectAttributes flash) {
        Actividad actividad = actividadRepository.findById(idActividad)
                .orElseThrow(() -> new RecursoNoEncontradoException("Actividad no encontrada."));
        Epp epp = eppRepository.findById(idEpp)
                .orElseThrow(() -> new RecursoNoEncontradoException("EPP no encontrado."));
        if (matrizRepository.existsByActividadIdActividadAndEppIdEpp(idActividad, idEpp)) {
            flash.addFlashAttribute("error", "Ese EPP ya está en la matriz para esta actividad.");
        } else if (!epp.isActivo()) {
            flash.addFlashAttribute("error", "El EPP está desactivado en el catálogo; actívelo antes de asignarlo.");
        } else {
            matrizRepository.save(new MatrizAsignacion(actividad, epp, obligatorio, Math.min(99, Math.max(1, cantidad))));
            flash.addFlashAttribute("exito", epp.getNombre() + " agregado a " + actividad.getNombre() + ".");
        }
        return "redirect:/matriz?actividad=" + idActividad;
    }

    @PostMapping("/{id}/quitar")
    public String quitarRegla(@PathVariable Integer id, RedirectAttributes flash) {
        MatrizAsignacion regla = matrizRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("La regla de la matriz no existe."));
        Integer idActividad = regla.getActividad().getIdActividad();
        matrizRepository.delete(regla);
        flash.addFlashAttribute("exito", regla.getEpp().getNombre() + " retirado de la matriz.");
        return "redirect:/matriz?actividad=" + idActividad;
    }

    @PostMapping("/actividades")
    @Transactional
    public String agregarActividad(@RequestParam String proceso, @RequestParam String nombre, RedirectAttributes flash) {
        if (proceso.isBlank() || nombre.isBlank()) {
            flash.addFlashAttribute("error", "Indique el proceso y el nombre de la actividad.");
            return "redirect:/matriz";
        }
        if (proceso.trim().length() > 100 || nombre.trim().length() > 150) {
            flash.addFlashAttribute("error", "El proceso admite hasta 100 caracteres y la actividad hasta 150.");
            return "redirect:/matriz";
        }
        Proceso p = procesoRepository.findByNombre(proceso.trim())
                .orElseGet(() -> procesoRepository.save(new Proceso(proceso.trim())));
        Actividad actividad = actividadRepository.save(new Actividad(p, nombre.trim()));
        flash.addFlashAttribute("exito", "Actividad creada. Agregue los EPP que exige.");
        return "redirect:/matriz?actividad=" + actividad.getIdActividad();
    }
}
