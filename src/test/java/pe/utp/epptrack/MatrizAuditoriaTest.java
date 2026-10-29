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
import pe.utp.epptrack.model.RegistroAuditoria;
import pe.utp.epptrack.repository.MatrizAsignacionRepository;
import pe.utp.epptrack.repository.RegistroAuditoriaRepository;
import pe.utp.epptrack.service.AuditoriaService;
import pe.utp.epptrack.service.MatrizEdicionService;
import pe.utp.epptrack.service.ReglaNegocioException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Auditoría de las ediciones de la matriz de trabajo (RF-03, RF-13, RN-05). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MatrizAuditoriaTest {

    @Autowired MockMvc mvc;
    @Autowired DatosPrueba datos;
    @Autowired MatrizEdicionService edicion;
    @Autowired AuditoriaService auditoria;
    @Autowired RegistroAuditoriaRepository auditoriaRepository;
    @Autowired MatrizAsignacionRepository matrizRepository;

    @BeforeEach
    void preparar() {
        datos.cargar();
    }

    private List<RegistroAuditoria> acciones(String accion) {
        return auditoriaRepository.findAll().stream().filter(r -> r.getAccion().equals(accion)).toList();
    }

    @Test
    void agregarReglaQuedaAuditadaConCantidadAcotada() {
        edicion.agregarRegla(datos.actividad.getIdActividad(), datos.casco.getIdEpp(), 500, false, "admin-test");
        assertThat(acciones("MATRIZ_REGLA_AGREGADA")).singleElement().satisfies(r -> {
            assertThat(r.getUsuario()).isEqualTo("admin-test");
            assertThat(r.getDetalle()).contains("Casco").contains("×99").contains("opcional");
        });
        assertThat(auditoria.verificar().integra()).isTrue();
    }

    @Test
    void cantidadMenorAUnoSeAcotaAUno() {
        edicion.agregarRegla(datos.actividad.getIdActividad(), datos.casco.getIdEpp(), -4, true, "admin-test");
        assertThat(matrizRepository.findByActividadIdActividadOrderByEppNombreAsc(datos.actividad.getIdActividad()))
                .filteredOn(m -> m.getEpp().getIdEpp().equals(datos.casco.getIdEpp()))
                .singleElement().extracting(MatrizAsignacion::getCantidad).isEqualTo(1);
    }

    @Test
    void quitarReglaRegistraLoQueTenia() {
        MatrizAsignacion guantes = matrizRepository.findByActividadIdActividadOrderByEppNombreAsc(datos.actividad.getIdActividad())
                .stream().filter(m -> m.getEpp().getIdEpp().equals(datos.guantes.getIdEpp())).findFirst().orElseThrow();
        edicion.quitarRegla(guantes.getIdMatriz(), "admin-test");
        assertThat(matrizRepository.existsById(guantes.getIdMatriz())).isFalse();
        assertThat(acciones("MATRIZ_REGLA_RETIRADA")).singleElement().satisfies(r ->
                assertThat(r.getDetalle()).contains("Guantes de nitrilo").contains("×2").contains("obligatorio"));
        assertThat(auditoria.verificar().integra()).isTrue();
    }

    @Test
    void crearActividadQuedaAuditada() {
        Integer id = edicion.agregarActividad("Cosecha", "Corte de uva", "admin-test");
        assertThat(id).isNotNull();
        assertThat(acciones("MATRIZ_ACTIVIDAD_CREADA")).singleElement()
                .satisfies(r -> assertThat(r.getDetalle()).contains("Corte de uva").contains("Cosecha"));
        assertThat(auditoria.verificar().integra()).isTrue();
    }

    @Test
    void reglaRepetidaYLimitesSeRechazanSinAuditar() {
        long antes = auditoriaRepository.count();
        assertThatThrownBy(() -> edicion.agregarRegla(datos.actividad.getIdActividad(), datos.guantes.getIdEpp(), 1, true, "admin-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("ya está en la matriz");
        assertThatThrownBy(() -> edicion.agregarActividad("P", "x".repeat(151), "admin-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("150");
        assertThatThrownBy(() -> edicion.agregarActividad("  ", "algo", "admin-test"))
                .isInstanceOf(ReglaNegocioException.class);
        assertThat(auditoriaRepository.count()).isEqualTo(antes);
    }

    @Test
    @WithMockUser(username = "admin-test", roles = "ADMIN")
    void elControladorAuditaYMuestraElErrorDeReglaRepetida() throws Exception {
        mvc.perform(post("/matriz").with(csrf()).param("idActividad", datos.actividad.getIdActividad().toString())
                        .param("idEpp", datos.casco.getIdEpp().toString()).param("cantidad", "1").param("obligatorio", "true"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("exito"));
        assertThat(acciones("MATRIZ_REGLA_AGREGADA")).hasSize(1);
        mvc.perform(post("/matriz").with(csrf()).param("idActividad", datos.actividad.getIdActividad().toString())
                        .param("idEpp", datos.casco.getIdEpp().toString()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));
        assertThat(acciones("MATRIZ_REGLA_AGREGADA")).hasSize(1);
        assertThat(auditoria.verificar().integra()).isTrue();
    }
}
