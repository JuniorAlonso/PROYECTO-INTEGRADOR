package pe.utp.epptrack.controller;

import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import pe.utp.epptrack.model.Epp;
import pe.utp.epptrack.repository.CategoriaEppRepository;
import pe.utp.epptrack.repository.EppRepository;
import pe.utp.epptrack.service.RecursoNoEncontradoException;

/** Catálogo de EPP con categoría y vida útil (RF-02, CU-03). */
@Controller
@RequestMapping("/epp")
public class EppController {

    private final EppRepository eppRepository;
    private final CategoriaEppRepository categoriaRepository;
    private final pe.utp.epptrack.service.EppService eppService;

    public EppController(EppRepository eppRepository, CategoriaEppRepository categoriaRepository,
                         pe.utp.epptrack.service.EppService eppService) {
        this.eppRepository = eppRepository;
        this.categoriaRepository = categoriaRepository;
        this.eppService = eppService;
    }

    @GetMapping
    public String listar(@RequestParam(required = false) Integer categoria, Model model) {
        model.addAttribute("epps", eppRepository.findAllByOrderByCategoriaNombreAscNombreAsc().stream()
                .filter(e -> categoria == null || e.getCategoria().getIdCategoria().equals(categoria))
                .toList());
        model.addAttribute("categorias", categoriaRepository.findAllByOrderByNombreAsc());
        model.addAttribute("categoriaSeleccionada", categoria);
        return "epp/lista";
    }

    @GetMapping("/nuevo")
    public String nuevo(Model model) {
        model.addAttribute("epp", new Epp());
        model.addAttribute("clases", pe.utp.epptrack.model.ClaseEpp.values());
        model.addAttribute("categorias", categoriaRepository.findAllByOrderByNombreAsc());
        return "epp/formulario";
    }

    @GetMapping("/{id}/editar")
    public String editar(@PathVariable Integer id, Model model) {
        model.addAttribute("epp", eppRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("EPP no encontrado.")));
        model.addAttribute("clases", pe.utp.epptrack.model.ClaseEpp.values());
        model.addAttribute("categorias", categoriaRepository.findAllByOrderByNombreAsc());
        return "epp/formulario";
    }

    /** La vida útil modificada aplica a las entregas futuras; las ya registradas conservan su fecha de renovación. */
    @PostMapping
    public String guardar(@Valid @ModelAttribute("epp") Epp epp, BindingResult result,
                          Model model, RedirectAttributes flash, org.springframework.security.core.Authentication auth) {
        if (result.hasErrors()) {
            model.addAttribute("categorias", categoriaRepository.findAllByOrderByNombreAsc());
            model.addAttribute("clases", pe.utp.epptrack.model.ClaseEpp.values());
            return "epp/formulario";
        }
        eppService.guardar(epp, auth.getName());
        flash.addFlashAttribute("exito", "EPP guardado: " + epp.getNombre());
        return "redirect:/epp";
    }

    /** Los EPP no se eliminan (tienen entregas asociadas): se desactivan para que no se ofrezcan en nuevas entregas. */
    @PostMapping("/{id}/estado")
    public String cambiarEstado(@PathVariable Integer id, RedirectAttributes flash,
                                org.springframework.security.core.Authentication auth) {
        Epp epp = eppService.cambiarEstado(id, auth.getName());
        flash.addFlashAttribute("exito", epp.getNombre() + (epp.isActivo() ? " activado." : " desactivado."));
        return "redirect:/epp";
    }
}
