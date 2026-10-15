package pe.utp.epptrack.controller;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import pe.utp.epptrack.dto.EstadoDotacion;
import pe.utp.epptrack.model.Actividad;
import pe.utp.epptrack.model.EstadoTrabajador;
import pe.utp.epptrack.model.Trabajador;
import pe.utp.epptrack.repository.ActividadRepository;
import pe.utp.epptrack.repository.PuestoRepository;
import pe.utp.epptrack.repository.SedeRepository;
import pe.utp.epptrack.service.DotacionService;
import pe.utp.epptrack.service.ReglaNegocioException;
import pe.utp.epptrack.service.TrabajadorService;

import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Gestión de trabajadores (RF-01, CU-02). */
@Controller
@RequestMapping("/trabajadores")
public class TrabajadorController {

    private final TrabajadorService trabajadorService;
    private final DotacionService dotacionService;
    private final SedeRepository sedeRepository;
    private final PuestoRepository puestoRepository;
    private final ActividadRepository actividadRepository;

    public TrabajadorController(TrabajadorService trabajadorService, DotacionService dotacionService,
                                SedeRepository sedeRepository, PuestoRepository puestoRepository,
                                ActividadRepository actividadRepository) {
        this.trabajadorService = trabajadorService;
        this.dotacionService = dotacionService;
        this.sedeRepository = sedeRepository;
        this.puestoRepository = puestoRepository;
        this.actividadRepository = actividadRepository;
    }

    /** El id y el estado nunca vienen del formulario: los decide el servicio (evita sobrescribir a otro trabajador). */
    @InitBinder("trabajador")
    void protegerCampos(WebDataBinder binder) {
        binder.setDisallowedFields("idTrabajador", "estado");
    }

    @GetMapping
    public String listar(@RequestParam(required = false) String q,
                         @RequestParam(required = false) Integer sede,
                         @RequestParam(required = false) EstadoTrabajador estado,
                         Model model) {
        Map<Integer, EstadoDotacion> dotacion = dotacionService.estadoDotacionTodos(null).stream()
                .collect(Collectors.toMap(e -> e.trabajador().getIdTrabajador(), Function.identity()));
        model.addAttribute("trabajadores", trabajadorService.buscar(q, sede, estado));
        model.addAttribute("dotacion", dotacion);
        model.addAttribute("sedes", sedeRepository.findAllByOrderByNombreAsc());
        model.addAttribute("estados", EstadoTrabajador.values());
        model.addAttribute("q", q);
        model.addAttribute("sedeSeleccionada", sede);
        model.addAttribute("estadoSeleccionado", estado);
        return "trabajadores/lista";
    }

    @GetMapping("/nuevo")
    public String nuevo(Model model) {
        model.addAttribute("trabajador", new Trabajador());
        model.addAttribute("campana", "");
        model.addAttribute("fechaIngreso", LocalDate.now());
        return formulario(model, null);
    }

    @PostMapping
    public String registrar(@Valid @ModelAttribute("trabajador") Trabajador trabajador, BindingResult result,
                            @RequestParam(required = false) Integer idActividad,
                            @RequestParam(required = false) String campana,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaIngreso,
                            Model model, RedirectAttributes flash) {
        model.addAttribute("campana", campana);
        model.addAttribute("fechaIngreso", fechaIngreso);
        if (result.hasErrors()) {
            return formulario(model, idActividad);
        }
        try {
            Trabajador guardado = trabajadorService.registrar(trabajador, idActividad, campana, fechaIngreso);
            flash.addFlashAttribute("exito", "Trabajador registrado: " + guardado.getNombreCompleto());
            return "redirect:/trabajadores";
        } catch (ReglaNegocioException e) {
            model.addAttribute("error", e.getMessage());
            return formulario(model, idActividad);
        }
    }

    @GetMapping("/{id}/editar")
    public String editar(@PathVariable Integer id, Model model) {
        Trabajador trabajador = trabajadorService.obtener(id);
        model.addAttribute("trabajador", trabajador);
        return formulario(model, trabajadorService.actividadActual(id).map(Actividad::getIdActividad).orElse(null));
    }

    @PostMapping("/{id}")
    public String actualizar(@PathVariable Integer id,
                             @Valid @ModelAttribute("trabajador") Trabajador trabajador, BindingResult result,
                             @RequestParam(required = false) Integer idActividad,
                             Model model, RedirectAttributes flash) {
        trabajador.setIdTrabajador(id);
        if (result.hasErrors()) {
            return formulario(model, idActividad);
        }
        try {
            trabajadorService.actualizar(id, trabajador, idActividad);
            flash.addFlashAttribute("exito", "Datos del trabajador actualizados.");
            return "redirect:/trabajadores";
        } catch (ReglaNegocioException e) {
            model.addAttribute("error", e.getMessage());
            return formulario(model, idActividad);
        }
    }

    @PostMapping("/{id}/cese")
    public String cese(@PathVariable Integer id,
                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaCese,
                       RedirectAttributes flash) {
        try {
            trabajadorService.registrarCese(id, fechaCese);
            flash.addFlashAttribute("exito", "Cese registrado. El historial de EPP se conserva para un futuro reingreso.");
        } catch (ReglaNegocioException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return volverAlHistorial(id);
    }

    @PostMapping("/{id}/reingreso")
    public String reingreso(@PathVariable Integer id,
                            @RequestParam String campana,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaIngreso,
                            @RequestParam(required = false) Integer idSede,
                            @RequestParam(required = false) Integer idActividad,
                            RedirectAttributes flash) {
        try {
            trabajadorService.registrarReingreso(id, campana, fechaIngreso, idSede, idActividad);
            flash.addFlashAttribute("exito", "Reingreso registrado. Revise los EPP que aún tiene vigentes antes de entregar.");
        } catch (ReglaNegocioException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return volverAlHistorial(id);
    }

    private String volverAlHistorial(Integer id) {
        try {
            return "redirect:/historial?dni=" + trabajadorService.obtener(id).getDni();
        } catch (ReglaNegocioException e) {
            return "redirect:/trabajadores";
        }
    }

    private String formulario(Model model, Integer idActividad) {
        model.addAttribute("sedes", sedeRepository.findAllByOrderByNombreAsc());
        model.addAttribute("puestos", puestoRepository.findAllByOrderByNombreAsc());
        model.addAttribute("actividades", actividadRepository.findAllByOrderByProcesoNombreAscNombreAsc());
        model.addAttribute("idActividad", idActividad);
        return "trabajadores/formulario";
    }
}
