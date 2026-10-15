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
import pe.utp.epptrack.model.Trabajador;
import pe.utp.epptrack.repository.DetalleEntregaRepository;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Pantallas de entregas, alertas, historial y reportes (CU-05 a CU-08). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ModulosWebTest {

    @Autowired MockMvc mvc;
    @Autowired DatosPrueba datos;
    @Autowired DetalleEntregaRepository detalleRepository;

    Trabajador trabajador;

    @BeforeEach
    void preparar() {
        datos.cargar();
        trabajador = datos.trabajador("20000001", LocalDate.now().minusDays(10));
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void formularioMuestraLosEppDeLaMatrizPreseleccionados() throws Exception {
        String html = mvc.perform(get("/entregas/nueva").param("dni", "20000001"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Pérez, Juan").contains("Respirador").contains("Guantes de nitrilo").contains("Otro EPP");
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void dniInexistenteMuestraMensaje() throws Exception {
        mvc.perform(get("/entregas/nueva").param("dni", "99999999"))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"));
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void registrarEntregaRedirigeAlHistorialYGuarda() throws Exception {
        mvc.perform(post("/entregas").with(csrf()).params(entrega(false)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/historial?dni=20000001"));
        assertThat(detalleRepository.findAll()).hasSize(1);
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void entregaDuplicadaSinJustificacionVuelveAlFormularioYNoGuarda() throws Exception {
        mvc.perform(post("/entregas").with(csrf()).params(entrega(false))).andExpect(status().is3xxRedirection());
        mvc.perform(post("/entregas").with(csrf()).params(entrega(false)))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("duplicados", "error"));
        assertThat(detalleRepository.findAll()).hasSize(1);
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void entregaDuplicadaConJustificacionSeRegistra() throws Exception {
        mvc.perform(post("/entregas").with(csrf()).params(entrega(false))).andExpect(status().is3xxRedirection());
        mvc.perform(post("/entregas").with(csrf()).params(entrega(true))).andExpect(status().is3xxRedirection());
        assertThat(detalleRepository.findAll()).hasSize(2);
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void eppInexistenteEnElFormularioNoRompeLaPagina() throws Exception {
        var p = entrega(false);
        p.set("items[0].idEpp", "999999");
        mvc.perform(post("/entregas").with(csrf()).params(p))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/entregas/nueva?dni=20000001"));
        assertThat(detalleRepository.findAll()).isEmpty();
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void historialMuestraLaEntregaYPermiteAnular() throws Exception {
        mvc.perform(post("/entregas").with(csrf()).params(entrega(false))).andExpect(status().is3xxRedirection());
        String html = mvc.perform(get("/historial").param("dni", "20000001"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Respirador").contains("Campaña test").contains("Nueva entrega");

        Integer idDetalle = detalleRepository.findAll().getFirst().getIdDetalle();
        mvc.perform(post("/entregas/detalle/" + idDetalle + "/anular").with(csrf()).param("motivo", "Error de digitación"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/historial?dni=20000001"));
        assertThat(detalleRepository.findById(idDetalle).orElseThrow().getEstadoVigencia().name()).isEqualTo("ANULADO");
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void historialBuscaPorNombre() throws Exception {
        mvc.perform(get("/historial").param("q", "pérez"))
                .andExpect(status().isOk()).andExpect(model().attributeExists("trabajador"));
        mvc.perform(get("/historial").param("q", "zzzz"))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"));
    }

    @Test
    @WithMockUser(roles = "RRHH")
    void rrhhRegistraCeseYVuelveAlHistorial() throws Exception {
        mvc.perform(post("/trabajadores/" + trabajador.getIdTrabajador() + "/cese").with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/historial?dni=20000001"));
        String html = mvc.perform(get("/historial").param("dni", "20000001"))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Registrar reingreso").doesNotContain("Nueva entrega");
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void alertasMuestranLasTresPestanas() throws Exception {
        for (String estado : new String[]{"VENCIDO", "POR_VENCER", "VIGENTE", "ANULADO"}) {
            mvc.perform(get("/alertas").param("estado", estado)).andExpect(status().isOk());
        }
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void reportesSeGeneranEnPantallaYCsv() throws Exception {
        mvc.perform(post("/entregas").with(csrf()).params(entrega(false))).andExpect(status().is3xxRedirection());
        for (String r : new String[]{"R01", "R02", "R03", "R04"}) {
            mvc.perform(get("/reportes/" + r).param("dni", "20000001")).andExpect(status().isOk());
        }
        byte[] csv = mvc.perform(get("/reportes/R04.csv").param("dni", "20000001"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        String texto = new String(csv, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(texto).startsWith("﻿").contains("R-04").contains("Respirador").contains("no pueden eliminarse");
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void reporteR04ConDniInexistenteVuelveAlIndice() throws Exception {
        mvc.perform(get("/reportes/R04").param("dni", "11111111"))
                .andExpect(status().isOk()).andExpect(model().attributeExists("error"));
    }

    private org.springframework.util.LinkedMultiValueMap<String, String> entrega(boolean justificar) {
        var p = new org.springframework.util.LinkedMultiValueMap<String, String>();
        p.add("idTrabajador", String.valueOf(trabajador.getIdTrabajador()));
        p.add("fechaEntrega", LocalDate.now().toString());
        p.add("motivo", "INGRESO");
        p.add("items[0].idEpp", String.valueOf(datos.respirador.getIdEpp()));
        p.add("items[0].seleccionado", "true");
        p.add("items[0].cantidad", "1");
        if (justificar) p.add("items[0].justificacion", "Se rompió el empaque");
        return p;
    }
}
