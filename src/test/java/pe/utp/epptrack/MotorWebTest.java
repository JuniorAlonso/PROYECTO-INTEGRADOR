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
import pe.utp.epptrack.repository.VersionMatrizRepository;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Pantallas del motor de decisiones: evaluación en el historial, publicación de la matriz y auditoría (PR-14 a PR-16). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MotorWebTest {

    @Autowired MockMvc mvc;
    @Autowired DatosPrueba datos;
    @Autowired VersionMatrizRepository versionRepository;

    @BeforeEach
    void preparar() {
        datos.cargar();
        datos.trabajador("40000001", LocalDate.now().minusDays(5));
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void pr14HistorialMuestraLaEvaluacionExplicada() throws Exception {
        String html = mvc.perform(get("/historial").param("dni", "40000001"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Evaluación automática de dotación").contains("No cumple la matriz")
                .contains("Dotación neta").contains("Guantes de nitrilo ×2");
    }

    @Test
    @WithMockUser(username = "admin-test", roles = "ADMIN")
    void pr15AdminPublicaUnaVersionDeLaMatriz() throws Exception {
        mvc.perform(post("/matriz/publicar").with(csrf()).param("codigo", "v1").param("descripcion", "Prueba"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("impacto"));
        assertThat(versionRepository.findFirstByVigenteTrueOrderByIdVersionDesc()).isPresent();
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void pr15SupervisorNoPuedePublicar() throws Exception {
        mvc.perform(post("/matriz/publicar").with(csrf()).param("codigo", "v9")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void pr16AuditoriaMuestraCadenaIntegra() throws Exception {
        String html = mvc.perform(get("/auditoria")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Cadena íntegra").contains("TRABAJADOR_REGISTRADO");
    }

    @Test
    @WithMockUser(roles = "RRHH")
    void pr16RrhhNoVeLaAuditoria() throws Exception {
        mvc.perform(get("/auditoria")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void pr17ReporteSeExportaAExcelNativo() throws Exception {
        byte[] xlsx = mvc.perform(get("/reportes/R01.xlsx")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".xlsx")))
                .andReturn().getResponse().getContentAsByteArray();
        try (var wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
            assertThat(wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).startsWith("R-01");
        }
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void pr18LosBotonesDeExportacionApuntanALaUrlCorrecta() throws Exception {
        String html = mvc.perform(get("/reportes/R03").param("dias", "30")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("/reportes/R03.xlsx?").contains("/reportes/R03.csv?").contains("dias=30")
                .doesNotContain("{c}");
    }
}
