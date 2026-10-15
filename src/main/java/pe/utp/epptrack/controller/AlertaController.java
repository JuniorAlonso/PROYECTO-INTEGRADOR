package pe.utp.epptrack.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import pe.utp.epptrack.model.DetalleEntrega;
import pe.utp.epptrack.model.EstadoVigencia;
import pe.utp.epptrack.repository.DetalleEntregaRepository;
import pe.utp.epptrack.repository.SedeRepository;
import pe.utp.epptrack.service.VigenciaService;

import java.util.EnumSet;
import java.util.List;

/** Control de vigencia y alertas de vencimiento (CU-07, RF-09). */
@Controller
@RequestMapping("/alertas")
public class AlertaController {

    private final VigenciaService vigenciaService;
    private final DetalleEntregaRepository detalleRepository;
    private final SedeRepository sedeRepository;

    public AlertaController(VigenciaService vigenciaService, DetalleEntregaRepository detalleRepository,
                            SedeRepository sedeRepository) {
        this.vigenciaService = vigenciaService;
        this.detalleRepository = detalleRepository;
        this.sedeRepository = sedeRepository;
    }

    @GetMapping
    public String alertas(@RequestParam(required = false) Integer sede,
                          @RequestParam(defaultValue = "VENCIDO") EstadoVigencia estado,
                          Model model) {
        if (!EnumSet.of(EstadoVigencia.VENCIDO, EstadoVigencia.POR_VENCER, EstadoVigencia.VIGENTE).contains(estado)) {
            estado = EstadoVigencia.VENCIDO;
        }
        List<DetalleEntrega> filas = estado == EstadoVigencia.VIGENTE
                ? detalleRepository.findByEstadoVigenciaInOrderByFechaRenovacionAsc(EnumSet.of(EstadoVigencia.VIGENTE)).stream()
                    .filter(d -> d.getEntrega().getTrabajador().getEstado().isLaborando())
                    .filter(d -> sede == null || d.getEntrega().getTrabajador().getSede().getIdSede().equals(sede))
                    .toList()
                : vigenciaService.alertas(sede, estado);
        List<DetalleEntrega> alertas = vigenciaService.alertas(sede, null);
        long vencidos = alertas.stream().filter(d -> d.getEstadoVigencia() == EstadoVigencia.VENCIDO).count();
        model.addAttribute("filas", filas);
        model.addAttribute("estado", estado);
        model.addAttribute("cantVencidos", vencidos);
        model.addAttribute("cantPorVencer", alertas.size() - vencidos);
        model.addAttribute("diasPorVencer", vigenciaService.getDiasPorVencer());
        model.addAttribute("sedes", sedeRepository.findAllByOrderByNombreAsc());
        model.addAttribute("sedeSeleccionada", sede);
        return "alertas/lista";
    }
}
