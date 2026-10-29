package pe.utp.epptrack.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import pe.utp.epptrack.model.Usuario;
import pe.utp.epptrack.service.ReglaNegocioException;
import pe.utp.epptrack.service.UsuarioService;

import java.util.Map;
import java.util.stream.Collectors;

/** Gestión de usuarios y roles del sistema (RF-14, RF-15). Solo ADMIN. */
@Controller
@RequestMapping("/usuarios")
public class UsuarioController {

    private final UsuarioService usuarioService;

    public UsuarioController(UsuarioService usuarioService) {
        this.usuarioService = usuarioService;
    }

    @GetMapping
    public String lista(Model model, Authentication auth) {
        var usuarios = usuarioService.listar();
        Map<Integer, Boolean> bloqueados = usuarios.stream().collect(Collectors.toMap(Usuario::getIdUsuario,
                u -> usuarioService.estaBloqueado(u.getNombreUsuario())));
        model.addAttribute("usuarios", usuarios);
        model.addAttribute("bloqueados", bloqueados);
        model.addAttribute("roles", usuarioService.roles());
        model.addAttribute("yo", auth.getName());
        model.addAttribute("minContrasena", UsuarioService.MIN_CONTRASENA);
        return "usuarios/lista";
    }

    @PostMapping
    public String crear(@RequestParam(required = false) String nombreUsuario, @RequestParam(required = false) Integer idRol,
                        @RequestParam(required = false) String contrasena, @RequestParam(required = false) String confirmacion,
                        Authentication auth, RedirectAttributes flash) {
        try {
            Usuario u = usuarioService.crear(nombreUsuario, idRol, contrasena, confirmacion, auth.getName());
            flash.addFlashAttribute("exito", "Usuario \"" + u.getNombreUsuario() + "\" creado con rol "
                    + u.getRol().getNombre() + ". Ya puede iniciar sesión.");
        } catch (ReglaNegocioException e) {
            flash.addFlashAttribute("error", e.getMessage());
            flash.addFlashAttribute("nombreUsuario", nombreUsuario);
            flash.addFlashAttribute("idRol", idRol);
        }
        return "redirect:/usuarios";
    }

    @PostMapping("/{id}/rol")
    public String cambiarRol(@PathVariable Integer id, @RequestParam(required = false) Integer idRol,
                             Authentication auth, RedirectAttributes flash) {
        try {
            Usuario u = usuarioService.cambiarRol(id, idRol, auth.getName());
            flash.addFlashAttribute("exito", "Rol de \"" + u.getNombreUsuario() + "\": " + u.getRol().getNombre() + ".");
        } catch (ReglaNegocioException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/usuarios";
    }

    @PostMapping("/{id}/estado")
    public String cambiarEstado(@PathVariable Integer id, Authentication auth, RedirectAttributes flash) {
        try {
            Usuario u = usuarioService.cambiarEstado(id, auth.getName());
            flash.addFlashAttribute("exito", "Usuario \"" + u.getNombreUsuario() + "\" "
                    + (UsuarioService.ACTIVO.equals(u.getEstado()) ? "activado." : "desactivado: ya no puede iniciar sesión."));
        } catch (ReglaNegocioException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/usuarios";
    }

    @PostMapping("/{id}/contrasena")
    public String restablecer(@PathVariable Integer id, @RequestParam(required = false) String contrasena,
                              @RequestParam(required = false) String confirmacion, Authentication auth,
                              RedirectAttributes flash) {
        try {
            Usuario u = usuarioService.restablecerContrasena(id, contrasena, confirmacion, auth.getName());
            flash.addFlashAttribute("exito", "Contraseña de \"" + u.getNombreUsuario() + "\" restablecida.");
        } catch (ReglaNegocioException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/usuarios";
    }
}
