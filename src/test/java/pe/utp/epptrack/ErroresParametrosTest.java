package pe.utp.epptrack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Parámetros inválidos (400 con página HTML amable) y permisos/CSRF de inventario y catálogo. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ErroresParametrosTest {

    @Autowired MockMvc mvc;
    @Autowired DatosPrueba datos;

    @BeforeEach
    void preparar() {
        datos.cargar();
    }

    private void esPaginaDe400(MockHttpServletRequestBuilder peticion) throws Exception {
        var res = mvc.perform(peticion).andExpect(status().isBadRequest()).andReturn().getResponse();
        assertThat(res.getContentType()).startsWith("text/html");
        String cuerpo = res.getContentAsString();
        assertThat(cuerpo).contains("no es válido").doesNotContain("org.springframework").doesNotContain("Exception");
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void alertasConSedeNoNumericaDevuelve400Amable() throws Exception {
        esPaginaDe400(get("/alertas").param("sede", "abc"));
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void reporteConFechaInvalidaDevuelve400Amable() throws Exception {
        esPaginaDe400(get("/reportes/R04").param("desde", "xx"));
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void reporteR01ConFechaInvalidaDevuelve400Amable() throws Exception {
        esPaginaDe400(get("/reportes/R01").param("desde", "xx"));
    }

    @Test
    @WithMockUser(username = "rrhh-test", roles = "RRHH")
    void trabajadoresConEstadoInexistenteDevuelve400Amable() throws Exception {
        esPaginaDe400(get("/trabajadores").param("estado", "XXX"));
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void alertasConEstadoInexistenteDevuelve400Amable() throws Exception {
        esPaginaDe400(get("/alertas").param("estado", "XXX"));
    }

    @Test
    @WithMockUser(username = "rrhh-test", roles = "RRHH")
    void rrhhNoAccedeALasDevolucionesDeInventario() throws Exception {
        mvc.perform(get("/inventario/devoluciones")).andExpect(status().isForbidden());
        mvc.perform(post("/inventario/devoluciones").with(csrf())
                .param("codigo", "EPP-001-0001").param("condicion", "BUENO").param("motivo", "REEMPLAZO"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void supervisorVeLasDevoluciones() throws Exception {
        mvc.perform(get("/inventario/devoluciones")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void devolucionSinCsrfSeRechaza() throws Exception {
        mvc.perform(post("/inventario/devoluciones")
                .param("codigo", "EPP-001-0001").param("condicion", "BUENO").param("motivo", "REEMPLAZO"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void devolucionConCsrfDeUnCodigoInexistenteRedirigeConError() throws Exception {
        mvc.perform(post("/inventario/devoluciones").with(csrf())
                .param("codigo", "NO-EXISTE").param("condicion", "BUENO").param("motivo", "REEMPLAZO"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void supervisorNoPuedeCrearEppEnElCatalogo() throws Exception {
        mvc.perform(post("/epp").with(csrf()).param("nombre", "Casco QA").param("vidaUtilDias", "100")
                .param("categoria", String.valueOf(datos.respirador.getCategoria().getIdCategoria())))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin-test", roles = "ADMIN")
    void adminCreaEppConCsrfYRedirige() throws Exception {
        mvc.perform(post("/epp").with(csrf()).param("nombre", "Casco QA").param("vidaUtilDias", "100")
                .param("categoria", String.valueOf(datos.respirador.getCategoria().getIdCategoria()))
                .param("clase", "DESECHABLE"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/epp"));
    }

    @Test
    @WithMockUser(username = "admin-test", roles = "ADMIN")
    void adminSinCsrfNoCreaEpp() throws Exception {
        mvc.perform(post("/epp").param("nombre", "Casco QA").param("vidaUtilDias", "100")
                .param("categoria", String.valueOf(datos.respirador.getCategoria().getIdCategoria())))
                .andExpect(status().isForbidden());
    }
}
