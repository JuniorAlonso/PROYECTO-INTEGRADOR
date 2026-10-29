package pe.utp.epptrack.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import pe.utp.epptrack.repository.SedeRepository;
import pe.utp.epptrack.service.NecesidadStockService;

/** Necesidad de EPP según el personal (solo ADMIN y SUPERVISOR, por /inventario/** en SecurityConfig). */
@Controller
public class NecesidadController {

    private final NecesidadStockService necesidadService;
    private final SedeRepository sedeRepository;

    public NecesidadController(NecesidadStockService necesidadService, SedeRepository sedeRepository) {
        this.necesidadService = necesidadService;
        this.sedeRepository = sedeRepository;
    }

    @GetMapping("/inventario/necesidad")
    public String necesidad(@RequestParam(required = false) Integer sede, Model model) {
        model.addAttribute("necesidad", necesidadService.calcular(sede));
        model.addAttribute("sedes", sedeRepository.findAllByOrderByNombreAsc());
        model.addAttribute("sedeSeleccionada", sede);
        model.addAttribute("horizonte", NecesidadStockService.HORIZONTE_DIAS);
        return "inventario/necesidad";
    }
}
