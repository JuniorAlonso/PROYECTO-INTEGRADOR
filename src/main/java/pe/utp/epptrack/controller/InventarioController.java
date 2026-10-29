package pe.utp.epptrack.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import pe.utp.epptrack.model.*;
import pe.utp.epptrack.repository.EppRepository;
import pe.utp.epptrack.service.InventarioService;
import pe.utp.epptrack.service.LoteService;
import pe.utp.epptrack.service.ReglaNegocioException;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;

/**
 * Inventario de EPP por código: stock vs. personal, devoluciones, inspección, autorización de retorno y mantenimiento
 * (solo ADMIN y SUPERVISOR, ver SecurityConfig). La autorización de retorno la decide una persona.
 */
@Controller
@RequestMapping("/inventario")
public class InventarioController {

    private final InventarioService inventarioService;
    private final EppRepository eppRepository;
    private final LoteService loteService;

    public InventarioController(InventarioService inventarioService, EppRepository eppRepository,
                                LoteService loteService) {
        this.loteService = loteService;
        this.inventarioService = inventarioService;
        this.eppRepository = eppRepository;
    }

    @GetMapping
    public String resumen(Model model) {
        model.addAttribute("resumen", inventarioService.resumen());
        model.addAttribute("epps", eppRepository.findByEstadoOrderByCategoriaNombreAscNombreAsc("ACTIVO"));
        model.addAttribute("horizonte", InventarioService.HORIZONTE_DIAS);
        model.addAttribute("lotes", loteService.todosLosLotes());
        model.addAttribute("limiteVencimiento", java.time.LocalDate.now().plusDays(30));
        return "inventario/resumen";
    }

    @PostMapping("/ingreso")
    public String ingresar(@RequestParam(required = false) Integer idEpp, @RequestParam(required = false) Integer cantidad,
                           @RequestParam(required = false) String codigoLote,
                           @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                                   iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate vencimiento,
                           Authentication auth, RedirectAttributes flash) {
        try {
            Epp epp = idEpp == null ? null : eppRepository.findById(idEpp).orElse(null);
            if (epp != null && epp.isDesechable()) {
                if (cantidad == null || cantidad < 1 || cantidad > 500) {
                    throw new ReglaNegocioException("La cantidad a ingresar debe estar entre 1 y 500.");
                }
                LoteConsumible lote = inventarioService.ingresarConsumible(idEpp, cantidad, codigoLote, vencimiento, null,
                        auth.getName());
                flash.addFlashAttribute("exito", "Ingresó el lote " + lote.getCodigoLote() + " con " + cantidad
                        + " unidades de " + epp.getNombre() + (vencimiento == null ? "" : ", vence " + vencimiento) + ".");
                return "redirect:/inventario";
            }
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
                           @RequestParam(required = false) String codigo, Model model) {
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
        model.addAttribute("inspecciones", inventarioService.inspecciones(id));
        model.addAttribute("mantenimientos", inventarioService.mantenimientos(id));
        model.addAttribute("resultadosInspeccion", ResultadoInspeccion.values());
        model.addAttribute("resultadosMantenimiento", ResultadoMantenimiento.values());
        return "inventario/unidad";
    }

    /** Página imprimible con el código de la unidad, lista para convertirse en QR o código de barras. */
    @GetMapping("/unidad/{id}/etiqueta")
    public String etiqueta(@PathVariable Integer id, Model model) {
        model.addAttribute("unidad", inventarioService.obtener(id));
        return "inventario/unidad_etiqueta";
    }

    @GetMapping("/devoluciones")
    public String devoluciones(@RequestParam(required = false) String codigo, Model model) {
        model.addAttribute("codigo", codigo);
        model.addAttribute("condiciones", List.of(CondicionEpp.BUENO, CondicionEpp.REPARABLE, CondicionEpp.INSERVIBLE));
        model.addAttribute("motivos", MotivoDevolucion.values());
        model.addAttribute("pendientes", inventarioService.pendientesDevolucion());
        model.addAttribute("devueltos", inventarioService.ultimasDevoluciones());
        model.addAttribute("porInspeccion", inventarioService.enEstado(EstadoUnidad.POR_INSPECCION));
        model.addAttribute("porAutorizar", inventarioService.enEstado(EstadoUnidad.POR_AUTORIZAR));
        model.addAttribute("enCuarentena", inventarioService.enEstado(EstadoUnidad.EN_CUARENTENA));
        return "inventario/devoluciones";
    }

    @PostMapping("/devoluciones")
    public String devolver(@RequestParam(required = false) String codigo, @RequestParam(required = false) CondicionEpp condicion,
                           @RequestParam(required = false) MotivoDevolucion motivo,
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
        model.addAttribute("aptos", inventarioService.enEstado(EstadoUnidad.POR_MANTENIMIENTO));
        model.addAttribute("enTaller", inventarioService.enEstado(EstadoUnidad.EN_MANTENIMIENTO));
        model.addAttribute("porAutorizar", inventarioService.enEstado(EstadoUnidad.POR_AUTORIZAR));
        model.addAttribute("abiertos", inventarioService.abiertosPorUnidad());
        model.addAttribute("cerrados", inventarioService.mantenimientosCerrados());
        model.addAttribute("indicador", inventarioService.indicadorMantenimiento());
        model.addAttribute("ahorroRealizado", inventarioService.ahorroLogrado());
        // Ahorro potencial: no hay costo estimado de reparación en el sistema, así que solo se informa la referencia
        // de reposición de los candidatos que tienen costo unitario y cuántos quedan sin costo.
        var candidatos = inventarioService.enEstado(EstadoUnidad.POR_MANTENIMIENTO);
        model.addAttribute("candidatosSinCosto", candidatos.stream().filter(u -> u.getEpp().getCostoUnitario() == null).count());
        model.addAttribute("referenciaReposicion", candidatos.stream().filter(u -> u.getEpp().getCostoUnitario() != null)
                .map(u -> u.getEpp().getCostoUnitario()).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
        model.addAttribute("resultadosMantenimiento", ResultadoMantenimiento.values());
        model.addAttribute("maxReparaciones", UnidadEpp.MAX_REPARACIONES);
        return "inventario/mantenimiento";
    }

    @PostMapping("/unidad/{id}/inspeccion")
    public String inspeccionar(@PathVariable Integer id, @RequestParam(required = false) ResultadoInspeccion resultado,
                               @RequestParam(required = false) String observaciones, Authentication auth, RedirectAttributes flash) {
        return operar(id, flash, "redirect:/inventario/unidad/" + id, () -> {
            UnidadEpp u = inventarioService.registrarInspeccion(id, resultado, observaciones, auth.getName());
            return u.getCodigo() + " inspeccionado: " + u.getEstado().getEtiqueta() + ".";
        });
    }

    @PostMapping("/unidad/{id}/autorizar")
    public String autorizar(@PathVariable Integer id, @RequestParam(required = false) String observacion,
                            Authentication auth, RedirectAttributes flash) {
        return operar(id, flash, "redirect:/inventario/unidad/" + id, () -> {
            UnidadEpp u = inventarioService.autorizarRetorno(id, observacion, auth.getName());
            return u.getCodigo() + ": retorno autorizado, ya está disponible en el almacén.";
        });
    }

    @PostMapping("/unidad/{id}/rechazar")
    public String rechazar(@PathVariable Integer id, @RequestParam(required = false) EstadoUnidad destino,
                           @RequestParam(required = false) String motivo, Authentication auth, RedirectAttributes flash) {
        return operar(id, flash, "redirect:/inventario/unidad/" + id, () -> {
            UnidadEpp u = inventarioService.rechazarRetorno(id, destino, motivo, auth.getName());
            return u.getCodigo() + ": retorno rechazado → " + u.getEstado().getEtiqueta() + ".";
        });
    }

    @PostMapping("/unidad/{id}/cuarentena")
    public String resolverCuarentena(@PathVariable Integer id, @RequestParam(required = false) EstadoUnidad destino,
                                     @RequestParam(required = false) String motivo, Authentication auth, RedirectAttributes flash) {
        return operar(id, flash, "redirect:/inventario/unidad/" + id, () -> {
            UnidadEpp u = inventarioService.resolverCuarentena(id, destino, motivo, auth.getName());
            return u.getCodigo() + ": cuarentena resuelta → " + u.getEstado().getEtiqueta() + ".";
        });
    }

    @PostMapping("/unidad/{id}/baja")
    public String baja(@PathVariable Integer id, @RequestParam(required = false) String motivo,
                       Authentication auth, RedirectAttributes flash) {
        return operar(id, flash, "redirect:/inventario/mantenimiento", () -> {
            UnidadEpp u = inventarioService.darDeBaja(id, motivo, auth.getName());
            return u.getCodigo() + " dado de baja.";
        });
    }

    @PostMapping("/unidad/{id}/enviar")
    public String enviar(@PathVariable Integer id, @RequestParam(required = false) String diagnostico,
                         @RequestParam(required = false) String accionPrevista, Authentication auth, RedirectAttributes flash) {
        return operar(id, flash, "redirect:/inventario/mantenimiento", () -> {
            UnidadEpp u = inventarioService.enviarAMantenimiento(id, diagnostico, accionPrevista, auth.getName());
            return u.getCodigo() + " enviado a mantenimiento.";
        });
    }

    /** Cierra el mantenimiento. Acepta {@code resultado} o, por compatibilidad con el formulario anterior, {@code reparado}. */
    @PostMapping("/unidad/{id}/cerrar")
    public String cerrar(@PathVariable Integer id, @RequestParam(required = false) ResultadoMantenimiento resultado,
                         @RequestParam(required = false) Boolean reparado,
                         @RequestParam(required = false) String accionRealizada, @RequestParam(required = false) BigDecimal costo,
                         @RequestParam(required = false) String observacion, Authentication auth, RedirectAttributes flash) {
        ResultadoMantenimiento r = resultado != null ? resultado
                : reparado == null ? null : reparado ? ResultadoMantenimiento.REPARADO : ResultadoMantenimiento.NO_REPARABLE;
        return operar(id, flash, "redirect:/inventario/mantenimiento", () -> {
            UnidadEpp u = inventarioService.cerrarMantenimiento(id, r, accionRealizada, costo, observacion, auth.getName());
            return u.getCodigo() + (u.getEstado() == EstadoUnidad.POR_AUTORIZAR
                    ? " reparado: queda pendiente de autorización de retorno." : " dado de baja.");
        });
    }

    private String operar(Integer id, RedirectAttributes flash, String destino, Supplier<String> operacion) {
        try {
            flash.addFlashAttribute("exito", operacion.get());
        } catch (ReglaNegocioException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return destino;
    }
}
