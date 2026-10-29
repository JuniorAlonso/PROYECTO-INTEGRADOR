package pe.utp.epptrack.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import pe.utp.epptrack.repository.SedeRepository;
import pe.utp.epptrack.service.ExcepcionService;
import pe.utp.epptrack.service.ExcepcionService.Tipo;
import pe.utp.epptrack.service.IndicadorService;

/** Panel de excepciones e indicadores verificables (ADMIN y SUPERVISOR; ver SecurityConfig). */
@Controller
public class ExcepcionController {

    private final ExcepcionService excepcionService;
    private final IndicadorService indicadorService;
    private final SedeRepository sedeRepository;

    public ExcepcionController(ExcepcionService excepcionService, IndicadorService indicadorService,
                               SedeRepository sedeRepository) {
        this.excepcionService = excepcionService;
        this.indicadorService = indicadorService;
        this.sedeRepository = sedeRepository;
    }

    @GetMapping("/excepciones")
    public String excepciones(@RequestParam(required = false) String tipo, @RequestParam(required = false) Integer sede,
                              @RequestParam(required = false) Integer dias, Model model) {
        Tipo filtro = ExcepcionService.parsearTipo(tipo);
        int periodo = ExcepcionService.validarDias(dias);
        model.addAttribute("excepciones", excepcionService.listar(filtro, sede, periodo));
        model.addAttribute("conteo", excepcionService.contarPorTipo(sede, periodo));
        model.addAttribute("tipos", Tipo.values());
        model.addAttribute("tipoSel", filtro);
        model.addAttribute("sedeSel", sede);
        model.addAttribute("dias", periodo);
        model.addAttribute("sedes", sedeRepository.findAll());
        return "excepciones/lista";
    }

    @GetMapping("/indicadores")
    public String indicadores(@RequestParam(required = false) Integer dias, Model model) {
        int periodo = ExcepcionService.validarDias(dias);
        model.addAttribute("panel", indicadorService.calcular(periodo));
        model.addAttribute("dias", periodo);
        return "indicadores/ver";
    }
}
