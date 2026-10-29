package pe.utp.epptrack;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import pe.utp.epptrack.model.EstadoRegistroMantenimiento;
import pe.utp.epptrack.model.EstadoUnidad;
import pe.utp.epptrack.model.UnidadEpp;
import pe.utp.epptrack.service.AuditoriaService;
import pe.utp.epptrack.service.InventarioService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La base de demostración (datos ficticios) muestra el flujo completo de trazabilidad por unidad. */
@SpringBootTest(properties = {"app.datos-demo=true", "spring.datasource.url=jdbc:h2:mem:epptrack-demo-flujo;MODE=MySQL;DATABASE_TO_LOWER=TRUE"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DemoInventarioTest {

    @Autowired InventarioService inventario;
    @Autowired AuditoriaService auditoria;
    @Autowired MockMvc mvc;

    @Test
    void laDemostracionTieneUnidadesEnCadaEtapaDelFlujo() {
        for (EstadoUnidad e : new EstadoUnidad[]{EstadoUnidad.POR_INSPECCION, EstadoUnidad.POR_AUTORIZAR,
                EstadoUnidad.EN_CUARENTENA, EstadoUnidad.EN_MANTENIMIENTO}) {
            assertThat(inventario.enEstado(e)).as(e.name()).isNotEmpty();
        }
        assertThat(inventario.abiertosPorUnidad()).isNotEmpty();
        assertThat(inventario.mantenimientosCerrados()).anyMatch(r ->
                r.getEstado() == EstadoRegistroMantenimiento.CERRADO && r.getCosto() != null);
        assertThat(inventario.indicadorMantenimiento().costoTotal().signum()).isPositive();
        assertThat(auditoria.verificar().integra()).isTrue();
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void laDemostracionSeVisualizaEnLasPantallas() throws Exception {
        mvc.perform(get("/inventario/mantenimiento")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Diagnóstico")));
        mvc.perform(get("/inventario/devoluciones")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("pendientes de inspección")));
        for (EstadoUnidad e : new EstadoUnidad[]{EstadoUnidad.POR_INSPECCION, EstadoUnidad.POR_AUTORIZAR,
                EstadoUnidad.EN_CUARENTENA, EstadoUnidad.EN_MANTENIMIENTO}) {
            UnidadEpp u = inventario.enEstado(e).getFirst();
            mvc.perform(get("/inventario/unidad/" + u.getIdUnidad())).andExpect(status().isOk());
        }
    }
}
