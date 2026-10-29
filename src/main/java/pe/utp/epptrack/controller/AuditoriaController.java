package pe.utp.epptrack.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import pe.utp.epptrack.service.AuditoriaService;

/** Bitácora de auditoría con verificación de integridad de la cadena de huellas (RF-13, RF-28). */
@Controller
@RequestMapping("/auditoria")
public class AuditoriaController {

    private final AuditoriaService auditoriaService;

    public AuditoriaController(AuditoriaService auditoriaService) {
        this.auditoriaService = auditoriaService;
    }

    @GetMapping
    public String ver(Model model) {
        model.addAttribute("verificacion", auditoriaService.verificar());
        model.addAttribute("registros", auditoriaService.ultimos());
        return "auditoria/lista";
    }
}
