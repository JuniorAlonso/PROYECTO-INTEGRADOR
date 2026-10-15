package pe.utp.epptrack.config;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import pe.utp.epptrack.service.RecursoNoEncontradoException;
import pe.utp.epptrack.service.ReglaNegocioException;

/** Convierte las reglas de negocio no capturadas por un controlador en una página de error clara. */
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ModelAndView noEncontrado(RecursoNoEncontradoException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ReglaNegocioException.class)
    public ModelAndView reglaDeNegocio(ReglaNegocioException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** Choque con una restricción única (p. ej. dos altas simultáneas con el mismo DNI). */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ModelAndView integridad(org.springframework.dao.DataIntegrityViolationException e) {
        return error(HttpStatus.CONFLICT, "El registro ya existe o está siendo modificado por otro usuario. Revise e intente de nuevo.");
    }

    private ModelAndView error(HttpStatus estado, String mensaje) {
        ModelAndView mv = new ModelAndView("error", estado);
        mv.addObject("status", estado.value());
        mv.addObject("mensaje", mensaje);
        return mv;
    }
}
