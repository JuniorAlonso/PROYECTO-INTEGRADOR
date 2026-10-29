package pe.utp.epptrack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Nombres accesibles de los controles que antes no los tenían (filtros, botones de solo icono y campos de la
 * entrega) y justificación sin texto cortado. Se comprueba el HTML generado; el aspecto en pantalla se revisó aparte.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AccesibilidadTest {

    @Autowired MockMvc mvc;
    @Autowired DatosPrueba datos;

    @BeforeEach
    void preparar() {
        datos.cargar();
        datos.trabajador("50000099", LocalDate.now().minusDays(5));
    }

    private String pagina(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void losFiltrosYBotonesDeIconoTienenNombre() throws Exception {
        assertThat(pagina("/alertas")).contains("aria-label=\"Filtrar por sede\"");
        assertThat(pagina("/")).contains("aria-label=\"Filtrar por sede\"");
        assertThat(pagina("/epp")).contains("aria-label=\"Filtrar por categoría\"");
        assertThat(pagina("/inventario/unidades")).contains("aria-label=\"Buscar por código\"");
        assertThat(pagina("/trabajadores")).contains("aria-label=\"Editar trabajador\"");
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void losCamposDeLaEntregaEstanAsociadosASuEtiqueta() throws Exception {
        String html = pagina("/entregas/nueva?dni=50000099");
        assertThat(html).contains("for=\"fechaEntrega\"").contains("for=\"motivo\"");
        assertThat(html).contains("id=\"fechaEntrega\"").contains("id=\"motivo\"");
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void laJustificacionUsaTextoCortoYLaRazonVaDebajo() throws Exception {
        String html = pagina("/entregas/nueva?dni=50000099");
        // Casco no está en la matriz de la actividad de prueba: pide justificación con la razón completa en una línea de ayuda.
        assertThat(html).contains("placeholder=\"Escriba la justificación\"")
                .contains("Obligatoria solo si lo entrega: la matriz no lo exige.")
                .doesNotContain("Justificación: la matriz no lo exige");
    }
}
