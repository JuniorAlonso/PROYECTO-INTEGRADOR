package pe.utp.epptrack.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.utp.epptrack.service.AsistenteService;
import pe.utp.epptrack.service.AsistenteService.Mensaje;
import pe.utp.epptrack.service.ReglaNegocioException;

import java.util.List;
import java.util.Map;

/** Endpoint del panel flotante del asistente IA (RF-16 a RF-20). Requiere sesión; CSRF activo. */
@RestController
@RequestMapping("/asistente")
public class AsistenteController {

    public record Consulta(String mensaje, List<Mensaje> historial) {}

    private final AsistenteService asistente;

    public AsistenteController(AsistenteService asistente) {
        this.asistente = asistente;
    }

    @PostMapping
    public ResponseEntity<?> consultar(@RequestBody Consulta consulta, Authentication auth) {
        String rol = auth.getAuthorities().stream().findFirst()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", "")).orElse("");
        try {
            return ResponseEntity.ok(asistente.consultar(auth.getName(), rol, consulta.mensaje(), consulta.historial()));
        } catch (ReglaNegocioException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
        }
    }
}
