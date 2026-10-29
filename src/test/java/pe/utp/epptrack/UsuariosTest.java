package pe.utp.epptrack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.model.Rol;
import pe.utp.epptrack.model.Usuario;
import pe.utp.epptrack.repository.RolRepository;
import pe.utp.epptrack.repository.UsuarioRepository;
import pe.utp.epptrack.service.ReglaNegocioException;
import pe.utp.epptrack.service.UsuarioService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Gestión de usuarios y roles por el Administrador (RF-14, RF-15, RNF-02). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UsuariosTest {

    @Autowired MockMvc mvc;
    @Autowired UsuarioService usuarioService;
    @Autowired UsuarioRepository usuarioRepository;
    @Autowired RolRepository rolRepository;
    @Autowired PasswordEncoder passwordEncoder;

    Rol admin;
    Rol supervisor;

    @BeforeEach
    void roles() {
        admin = rolRepository.findByNombre(Rol.ADMIN).orElseGet(() -> rolRepository.save(new Rol(Rol.ADMIN, null)));
        supervisor = rolRepository.findByNombre(Rol.SUPERVISOR).orElseGet(() -> rolRepository.save(new Rol(Rol.SUPERVISOR, null)));
    }

    @Test
    void creaUsuarioConRolYContrasenaCifrada() {
        Usuario u = usuarioService.crear("jperez", supervisor.getIdRol(), "Clave2026", "Clave2026", "jefe");
        assertThat(u.getRol().getNombre()).isEqualTo(Rol.SUPERVISOR);
        assertThat(u.getContrasenaCifrada()).isNotEqualTo("Clave2026");
        assertThat(passwordEncoder.matches("Clave2026", u.getContrasenaCifrada())).isTrue();
    }

    @Test
    void rechazaContrasenaDebilConfirmacionDistintaYDuplicados() {
        assertThatThrownBy(() -> usuarioService.crear("ana", supervisor.getIdRol(), "corta1", "corta1", "jefe"))
                .isInstanceOf(ReglaNegocioException.class);
        assertThatThrownBy(() -> usuarioService.crear("ana", supervisor.getIdRol(), "solosletras", "solosletras", "jefe"))
                .isInstanceOf(ReglaNegocioException.class);
        assertThatThrownBy(() -> usuarioService.crear("ana", supervisor.getIdRol(), "Clave2026", "Clave2027", "jefe"))
                .isInstanceOf(ReglaNegocioException.class);
        assertThatThrownBy(() -> usuarioService.crear("con espacio", supervisor.getIdRol(), "Clave2026", "Clave2026", "jefe"))
                .isInstanceOf(ReglaNegocioException.class);
        usuarioService.crear("ana", supervisor.getIdRol(), "Clave2026", "Clave2026", "jefe");
        assertThatThrownBy(() -> usuarioService.crear("ANA", supervisor.getIdRol(), "Clave2026", "Clave2026", "jefe"))
                .hasMessageContaining("Ya existe");
    }

    @Test
    void noSeQuedaSinAdministradorNiSeDesactivaASiMismo() {
        long adminsActivos = usuarioRepository.findAll().stream()
                .filter(u -> Rol.ADMIN.equals(u.getRol().getNombre()) && "ACTIVO".equals(u.getEstado())).count();
        Usuario unico = usuarioService.crear("admin-unico", admin.getIdRol(), "Clave2026", "Clave2026", "jefe");
        if (adminsActivos == 0) {
            assertThatThrownBy(() -> usuarioService.cambiarEstado(unico.getIdUsuario(), "otro"))
                    .hasMessageContaining("al menos un administrador");
            assertThatThrownBy(() -> usuarioService.cambiarRol(unico.getIdUsuario(), supervisor.getIdRol(), "otro"))
                    .hasMessageContaining("al menos un administrador");
        }
        assertThatThrownBy(() -> usuarioService.cambiarEstado(unico.getIdUsuario(), "admin-unico"))
                .hasMessageContaining("propia cuenta");
    }

    @Test
    void desactivaReactivaYRestableceContrasena() {
        Usuario u = usuarioService.crear("rosa", supervisor.getIdRol(), "Clave2026", "Clave2026", "jefe");
        usuarioService.cambiarEstado(u.getIdUsuario(), "jefe");
        assertThat(u.getEstado()).isEqualTo("INACTIVO");
        usuarioService.cambiarEstado(u.getIdUsuario(), "jefe");
        assertThat(u.getEstado()).isEqualTo("ACTIVO");
        usuarioService.restablecerContrasena(u.getIdUsuario(), "Nueva2026", "Nueva2026", "jefe");
        assertThat(passwordEncoder.matches("Nueva2026", u.getContrasenaCifrada())).isTrue();
    }

    @Test
    @WithMockUser(username = "jefe", roles = "ADMIN")
    void adminVeLaPantallaDeUsuarios() throws Exception {
        mvc.perform(get("/usuarios")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Usuarios y roles")));
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void supervisorNoGestionaUsuarios() throws Exception {
        mvc.perform(get("/usuarios")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "RRHH")
    void rrhhNoGestionaUsuarios() throws Exception {
        mvc.perform(get("/usuarios")).andExpect(status().isForbidden());
    }
}
