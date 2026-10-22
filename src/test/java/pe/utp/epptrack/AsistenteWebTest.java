package pe.utp.epptrack;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Endpoint del asistente IA y cabeceras de seguridad HTTP. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AsistenteWebTest {

    private static final String CONSULTA = "{\"mensaje\":\"¿Qué EPP están vencidos?\",\"historial\":[]}";

    @Autowired MockMvc mvc;

    @Test
    void sinSesionNoSePuedeConsultar() throws Exception {
        mvc.perform(post("/asistente").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(CONSULTA))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void sinTokenCsrfSeRechaza() throws Exception {
        mvc.perform(post("/asistente").contentType(MediaType.APPLICATION_JSON).content(CONSULTA))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void sinClaveResponderEnModoBasicoSinIA() throws Exception {
        mvc.perform(post("/asistente").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(CONSULTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.respuesta", containsString("Modo básico")));
    }

    @Test
    @WithMockUser(roles = "RRHH")
    void rrhhNoPuedeConsultarVigenciasEnModoBasico() throws Exception {
        mvc.perform(post("/asistente").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(CONSULTA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.respuesta", containsString("no tiene permiso")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void laPantallaIncluyeElAsistente() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("asistente-abrir")));
    }

    @Test
    void cabecerasDeSeguridadPresentes() throws Exception {
        mvc.perform(get("/login")).andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists("Referrer-Policy"));
    }
}
