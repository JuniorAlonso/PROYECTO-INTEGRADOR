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
import pe.utp.epptrack.model.EstadoUnidad;
import pe.utp.epptrack.model.UnidadEpp;
import pe.utp.epptrack.service.InventarioService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Permisos y CSRF de las pantallas y acciones de inventario (solo ADMIN y SUPERVISOR; RRHH recibe 403). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InventarioPermisosTest {

    @Autowired MockMvc mvc;
    @Autowired DatosPrueba datos;
    @Autowired InventarioService inventario;

    UnidadEpp unidad;

    @BeforeEach
    void preparar() {
        datos.cargar();
        unidad = inventario.ingresar(datos.casco.getIdEpp(), 1, "sup-test").getFirst();
    }

    private String[] rutasGet() {
        return new String[]{"/inventario", "/inventario/unidades", "/inventario/devoluciones", "/inventario/mantenimiento",
                "/inventario/unidad/" + unidad.getIdUnidad(), "/inventario/unidad/" + unidad.getIdUnidad() + "/etiqueta"};
    }

    private String[] rutasPost() {
        String base = "/inventario/unidad/" + unidad.getIdUnidad();
        return new String[]{"/inventario/ingreso", "/inventario/devoluciones", base + "/inspeccion", base + "/autorizar",
                base + "/rechazar", base + "/cuarentena", base + "/baja", base + "/enviar", base + "/cerrar"};
    }

    @Test
    @WithMockUser(username = "rrhh", roles = "RRHH")
    void rrhhRecibe403EnTodoInventario() throws Exception {
        for (String ruta : rutasGet()) {
            mvc.perform(get(ruta)).andExpect(status().isForbidden());
        }
        for (String ruta : rutasPost()) {
            mvc.perform(post(ruta).with(csrf())).andExpect(status().isForbidden());
        }
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void supervisorVeLasPantallas() throws Exception {
        for (String ruta : rutasGet()) {
            mvc.perform(get(ruta)).andExpect(status().isOk());
        }
        mvc.perform(get("/inventario/unidad/" + unidad.getIdUnidad() + "/etiqueta"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("qr-lugar")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(unidad.getCodigo())));
    }

    @Test
    @WithMockUser(username = "admin-test", roles = "ADMIN")
    void adminVeLasPantallas() throws Exception {
        for (String ruta : rutasGet()) {
            mvc.perform(get(ruta)).andExpect(status().isOk());
        }
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void lasAccionesSinTokenCsrfSeRechazanConPost403() throws Exception {
        for (String ruta : rutasPost()) {
            mvc.perform(post(ruta)).andExpect(status().isForbidden());
        }
        assertThat(unidad.getEstado()).isEqualTo(EstadoUnidad.DISPONIBLE);
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void autorizarUnaUnidadNoPendienteMuestraElErrorYNoCambiaNada() throws Exception {
        mvc.perform(post("/inventario/unidad/" + unidad.getIdUnidad() + "/autorizar").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));
        assertThat(unidad.getEstado()).isEqualTo(EstadoUnidad.DISPONIBLE);
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void laFichaMuestraElAvisoDeAutorizacionHumanaCuandoCorresponde() throws Exception {
        unidad.setEstado(EstadoUnidad.POR_AUTORIZAR);
        mvc.perform(get("/inventario/unidad/" + unidad.getIdUnidad()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("La autorización la decide una persona")));
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void buscarPorCodigoSinDistinguirMayusculasRedirigeALaFicha() throws Exception {
        mvc.perform(get("/inventario/unidades").param("codigo", unidad.getCodigo().toLowerCase()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/inventario/unidad/" + unidad.getIdUnidad()));
    }
}
