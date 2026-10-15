package pe.utp.epptrack.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Datos comunes a todas las vistas. */
@ControllerAdvice
public class GlobalModelAdvice {

    /** Ruta actual, para resaltar la opción activa del menú lateral. */
    @ModelAttribute("rutaActual")
    public String rutaActual(HttpServletRequest request) {
        return request.getRequestURI();
    }
}
