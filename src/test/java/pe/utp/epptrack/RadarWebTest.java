package pe.utp.epptrack;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Radar de reposición: pantalla, lista de compra y permisos. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RadarWebTest {

    @Autowired MockMvc mvc;

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void supervisorVeElRadar() throws Exception {
        mvc.perform(get("/radar")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Radar de reposición")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listaDeCompraSeDescargaComoCsvConEncabezado() throws Exception {
        mvc.perform(get("/radar/compras.csv")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("lista-de-compra-")))
                .andExpect(content().string(containsString("Total a reponer")));
    }

    @Test
    @WithMockUser(roles = "RRHH")
    void rrhhNoAccedeAlRadar() throws Exception {
        mvc.perform(get("/radar")).andExpect(status().isForbidden());
    }
}
