package pe.utp.epptrack.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import pe.utp.epptrack.model.CondicionEpp;
import pe.utp.epptrack.model.EstadoUnidad;
import pe.utp.epptrack.model.UnidadEpp;
import pe.utp.epptrack.repository.EppRepository;
import pe.utp.epptrack.service.InventarioService;
import pe.utp.epptrack.service.ReglaNegocioException;

import java.util.List;

/** Inventario de EPP por código: stock vs. personal, devoluciones y mantenimiento (solo ADMIN y SUPERVISOR). */
@Controller
@RequestMapping("/inventario")
public class InventarioController {

    private final InventarioService inventarioService;
    private final EppRepository eppRepository;

    public InventarioController(InventarioService inventarioService, EppRepository eppRepository) {
        this.inventarioService = inventarioService;
        this.eppRepository = eppRepository;
    }

    @GetMapping
    public String resumen(Model model) {
        model.addAttribute("resumen", inventarioService.resumen());
        model.addAttribute("epps", eppRepository.findByEstadoOrderByCategoriaNombreAscNombreAsc("ACTIVO"));
        model.addAttribute("horizonte", InventarioService.HORIZONTE_DIAS);
        return "inventario/resumen";
    }

    @PostMapping("/ingreso")
    public String ingresar(@RequestParam(required = false) Integer idEpp, @RequestParam(required = false) Integer cantidad,
                           Authentication auth, RedirectAttributes flash) {
        try {
            List<UnidadEpp> nuevas = inventarioService.ingresar(idEpp, cantidad, auth.getName());
            flash.addFlashAttribute("exito", "Ingresaron " + nuevas.size() + " unidades de " + nuevas.getFirst().getEpp().getNombre()
                    + ": códigos " + nuevas.getFirst().getCodigo()
                    + (nuevas.size() > 1 ? " a " + nuevas.getLast().getCodigo() : "") + ".");
        } catch (ReglaNegocioException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/inventario";
    }

    @GetMapping("/unidades")
    public String unidades(@RequestParam(required = false) Integer epp, @RequestParam(required = false) EstadoUnidad estado,
                           @RequestParam(required = false) String codigo, Model model, RedirectAttributes flash) {
        if (codigo != null && !codigo.isBlank()) {
            var unidad = inventarioService.buscarPorCodigo(codigo);
            if (unidad.isPresent()) {
                return "redirect:/inventario/unidad/" + unidad.get().getIdUnidad();
            }
            model.addAttribute("error", "No existe un EPP con el código " + codigo.trim() + ".");
        }
        model.addAttribute("unidades", inventarioService.unidades(epp, estado));
        model.addAttribute("epps", eppRepository.findAllByOrderByCategoriaNombreAscNombreAsc());
        model.addAttribute("estados", EstadoUnidad.values());
        model.addAttribute("eppSeleccionado", epp);
        model.addAttribute("estadoSeleccionado", estado);
        return "inventario/unidades";
    }

    @GetMapping("/unidad/{id}")
    public String unidad(@PathVariable Integer id, Model model) {
        model.addAttribute("unidad", inventarioService.obtener(id));
        model.addAttribute("movimientos", inventarioService.movimientos(id));
        return "inventario/unidad";
    }

    @GetMapping("/devoluciones")
    public String devoluciones(@RequestParam(required = false) String codigo, Model model) {
        model.addAttribute("codigo", codigo);
        model.addAttribute("condiciones", List.of(CondicionEpp.BUENO, CondicionEpp.REPARABLE, CondicionEpp.INSERVIBLE));
        model.addAttribute("motivos", pe.utp.epptrack.model.MotivoDevolucion.values());
        model.addAttribute("pendientes", inventarioService.pendientesDevolucion());
        model.addAttribute("devueltos", inventarioService.ultimasDevoluciones());
        return "inventario/devoluciones";
    }

    @PostMapping("/devoluciones")
    public String devolver(@RequestParam(required = false) String codigo, @RequestParam(required = false) CondicionEpp condicion,
                           @RequestParam(required = false) pe.utp.epptrack.model.MotivoDevolucion motivo,
                           @RequestParam(required = false) String observacion, Authentication auth, RedirectAttributes flash) {
        try {
            UnidadEpp unidad = inventarioService.devolver(codigo, condicion, motivo, observacion, auth.getName());
            flash.addFlashAttribute("exito", "Devolución registrada: " + unidad.getCodigo() + " → "
                    + unidad.getEstado().getEtiqueta() + ".");
        } catch (ReglaNegocioException e) {
            flash.addFlashAttribute("error", e.getMessage());
            flash.addAttribute("codigo", codigo);
        }
        return "redirect:/inventario/devoluciones";
    }

    @GetMapping("/mantenimiento")
    public String mantenimiento(Model model) {
        var resumen = inventarioService.resumen();
        model.addAttribute("aptos", inventarioService.enEstado(EstadoUnidad.POR_MANTENIMIENTO));
        model.addAttribute("enTaller", inventarioService.enEstado(EstadoUnidad.EN_MANTENIMIENTO));
        model.addAttribute("ahorroPotencial", resumen.ahorroPotencial());
        model.addAttribute("ahorroLogrado", resumen.ahorroLogrado());
        model.addAttribute("maxReparaciones", UnidadEpp.MAX_REPARACIONES);
        return "inventario/mantenimiento";
    }

    @PostMapping("/unidad/{id}/enviar")
    public String enviar(@PathVariable Integer id, Authentication auth, RedirectAttributes flash) {
        try {
            UnidadEpp unidad = inventarioService.enviarAMantenimiento(id, auth.getName());
            flash.addFlashAttribute("exito", unidad.getCodigo() + " enviado a mantenimiento.");
        } catch (ReglaNegocioException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/inventario/mantenimiento";
    }

    @PostMapping("/unidad/{id}/cerrar")
    public String cerrar(@PathVariable Integer id, @RequestParam boolean reparado,
                         @RequestParam(required = false) String observacion, Authentication auth, RedirectAttributes flash) {
        try {
            UnidadEpp unidad = inventarioService.cerrarMantenimiento(id, reparado, observacion, auth.getName());
            flash.addFlashAttribute("exito", reparado ? unidad.getCodigo() + " reparado: vuelve al almacén."
                    : unidad.getCodigo() + " dado de baja.");
        } catch (ReglaNegocioException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/inventario/mantenimiento";
    }
}
