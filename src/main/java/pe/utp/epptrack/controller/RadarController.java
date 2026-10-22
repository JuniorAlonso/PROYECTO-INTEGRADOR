package pe.utp.epptrack.controller;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import pe.utp.epptrack.repository.SedeRepository;
import pe.utp.epptrack.service.RadarService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/** Radar de reposición: proyección de renovaciones a 90 días y lista de compra (solo ADMIN y SUPERVISOR). */
@Controller
@RequestMapping("/radar")
public class RadarController {

    private final RadarService radarService;
    private final SedeRepository sedeRepository;

    public RadarController(RadarService radarService, SedeRepository sedeRepository) {
        this.radarService = radarService;
        this.sedeRepository = sedeRepository;
    }

    @GetMapping
    public String ver(@RequestParam(required = false) Integer sede, Model model) {
        model.addAttribute("radar", radarService.calcular(sede));
        model.addAttribute("sedes", sedeRepository.findAllByOrderByNombreAsc());
        model.addAttribute("sedeSeleccionada", sede);
        model.addAttribute("horizonte", RadarService.HORIZONTE_DIAS);
        return "radar/ver";
    }

    @GetMapping(value = "/compras.csv", produces = "text/csv")
    public ResponseEntity<byte[]> listaDeCompra(@RequestParam(required = false) Integer sede) {
        String csv = radarService.listaDeCompra(radarService.calcular(sede));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("lista-de-compra-" + LocalDate.now() + ".csv").build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
