package pe.utp.epptrack.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import pe.utp.epptrack.repository.SedeRepository;
import pe.utp.epptrack.service.DashboardService;

@Controller
public class InicioController {

    private final DashboardService dashboardService;
    private final SedeRepository sedeRepository;

    public InicioController(DashboardService dashboardService, SedeRepository sedeRepository) {
        this.dashboardService = dashboardService;
        this.sedeRepository = sedeRepository;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String dashboard(@RequestParam(required = false) Integer sede, Model model) {
        model.addAllAttributes(dashboardService.indicadores(sede));
        model.addAttribute("sedes", sedeRepository.findAllByOrderByNombreAsc());
        model.addAttribute("sedeSeleccionada", sede);
        return "dashboard";
    }
}
