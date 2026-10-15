package pe.utp.epptrack;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Autenticación obligatoria (RF-14) y permisos por perfil (RF-15). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SeguridadTest {

    @Autowired MockMvc mvc;

    @Test
    void sinSesionRedirigeAlLogin() throws Exception {
        mvc.perform(get("/trabajadores")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void loginEsPublico() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminVeTodasLasPantallas() throws Exception {
        for (String ruta : new String[]{"/", "/trabajadores", "/trabajadores/nuevo", "/epp", "/epp/nuevo", "/matriz"}) {
            mvc.perform(get(ruta)).andExpect(status().isOk());
        }
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void supervisorNoAdministraCatalogoNiTrabajadores() throws Exception {
        mvc.perform(get("/epp")).andExpect(status().isOk());
        mvc.perform(get("/epp/nuevo")).andExpect(status().isForbidden());
        mvc.perform(get("/trabajadores/nuevo")).andExpect(status().isForbidden());
        mvc.perform(post("/matriz").with(csrf()).param("idActividad", "1").param("idEpp", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "RRHH")
    void rrhhGestionaTrabajadoresPeroNoEntregas() throws Exception {
        mvc.perform(get("/trabajadores/nuevo")).andExpect(status().isOk());
        mvc.perform(get("/entregas/nueva")).andExpect(status().isForbidden());
        mvc.perform(get("/epp/nuevo")).andExpect(status().isForbidden());
    }
}
