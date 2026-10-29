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
import pe.utp.epptrack.model.MatrizAsignacion;
import pe.utp.epptrack.repository.MatrizAsignacionRepository;
import pe.utp.epptrack.repository.VersionMatrizRepository;
import pe.utp.epptrack.service.MatrizVersionService;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Vista previa del impacto de la matriz (RF-21, RN-07) y comparación de versiones: de solo lectura. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MatrizPreviaTest {

    @Autowired MockMvc mvc;
    @Autowired DatosPrueba datos;
    @Autowired MatrizVersionService versionService;
    @Autowired VersionMatrizRepository versionRepository;
    @Autowired MatrizAsignacionRepository matrizRepository;

    @BeforeEach
    void preparar() {
        datos.cargar();
        datos.trabajador("40000001", LocalDate.now().minusDays(5));
    }

    @Test
    void sinVersionPreviaDiceQueNoHayBaseDeComparacion() {
        var previa = versionService.vistaPrevia();
        assertThat(previa.vigente()).isNull();
        assertThat(previa.hayCambios()).isTrue();
        assertThat(previa.agregadas()).hasSize(2);
        assertThat(previa.noEvaluable()).anyMatch(t -> t.startsWith("Sin versión publicada previa"));
    }

    @Test
    void sinCambiosLaPreviaEstaVacia() {
        versionService.publicar("v1", null, "admin-test");
        var previa = versionService.vistaPrevia();
        assertThat(previa.hayCambios()).isFalse();
        assertThat(previa.getTotalCambios()).isZero();
        assertThat(previa.noEvaluable()).noneMatch(t -> t.startsWith("Sin versión publicada previa"));
    }

    @Test
    void detectaAgregadaRetiradaYModificada() {
        versionService.publicar("v1", null, "admin-test");
        matrizRepository.save(new MatrizAsignacion(datos.actividad, datos.casco, true, 1));
        MatrizAsignacion guantes = matrizRepository.findByActividadIdActividadOrderByEppNombreAsc(datos.actividad.getIdActividad())
                .stream().filter(m -> m.getEpp().getIdEpp().equals(datos.guantes.getIdEpp())).findFirst().orElseThrow();
        guantes.setCantidad(3);
        matrizRepository.save(guantes);
        MatrizAsignacion respirador = matrizRepository.findByActividadIdActividadOrderByEppNombreAsc(datos.actividad.getIdActividad())
                .stream().filter(m -> m.getEpp().getIdEpp().equals(datos.respirador.getIdEpp())).findFirst().orElseThrow();
        matrizRepository.delete(respirador);
        matrizRepository.flush();

        var previa = versionService.vistaPrevia();
        assertThat(previa.agregadas()).singleElement().asString().contains("Casco");
        assertThat(previa.retiradas()).singleElement().asString().contains("Respirador");
        assertThat(previa.modificadas()).singleElement().asString().contains("×2").contains("×3");
        assertThat(previa.actividadesAfectadas()).isEqualTo(1);
        assertThat(previa.trabajadoresAfectados()).hasSize(1);
        assertThat(previa.conDotacionIncompleta()).isEqualTo(1);
    }

    @Test
    void laPreviaNoCreaVersionesNiCambiaReglas() {
        versionService.publicar("v1", null, "admin-test");
        matrizRepository.save(new MatrizAsignacion(datos.actividad, datos.casco, true, 1));
        long versiones = versionRepository.count(), reglas = matrizRepository.count();
        versionService.vistaPrevia();
        assertThat(versionRepository.count()).isEqualTo(versiones);
        assertThat(matrizRepository.count()).isEqualTo(reglas);
        assertThat(versionService.vigente().orElseThrow().getCodigo()).isEqualTo("v1");
    }

    @Test
    @WithMockUser(roles = "RRHH")
    void cualquierAutenticadoVeLaPreviaYElMensajeDeSinCambios() throws Exception {
        versionService.publicar("v1", null, "admin-test");
        String html = mvc.perform(get("/matriz/previa")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("La matriz de trabajo es igual a la versión v1").contains("No evaluable")
                .contains("Cancelar y volver").doesNotContain("Publicar esta versión");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminVeElFormularioDePublicarConConfirmacion() throws Exception {
        String html = mvc.perform(get("/matriz/previa")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Publicar esta versión").contains("Confirmo la publicación")
                .contains("Agregada").contains("Sin versión publicada previa");
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void supervisorNoPublicaYAdminSi() throws Exception {
        mvc.perform(post("/matriz/publicar").with(csrf()).param("codigo", "v9")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminPublicaDesdeLaPrevia() throws Exception {
        mvc.perform(post("/matriz/publicar").with(csrf()).param("codigo", "v1").param("confirmo", "on"))
                .andExpect(status().is3xxRedirection());
        assertThat(versionRepository.findFirstByVigenteTrueOrderByIdVersionDesc()).isPresent();
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void compararVersionesMuestraLasDiferenciasYDa404SiNoExiste() throws Exception {
        versionService.publicar("v1", null, "admin-test");
        matrizRepository.save(new MatrizAsignacion(datos.actividad, datos.casco, false, 2));
        var v2 = versionService.publicar("v2", "con casco", "admin-test").version();
        String html = mvc.perform(get("/matriz/versiones/" + v2.getIdVersion())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Versión v2").contains("frente a la versión v1").contains("Casco");
        mvc.perform(get("/matriz/versiones/999999")).andExpect(status().isNotFound());
    }
}
