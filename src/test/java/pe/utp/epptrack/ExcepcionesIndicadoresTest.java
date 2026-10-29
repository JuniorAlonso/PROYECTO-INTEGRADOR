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
import pe.utp.epptrack.dto.EntregaForm;
import pe.utp.epptrack.dto.ItemEntregaForm;
import pe.utp.epptrack.model.Epp;
import pe.utp.epptrack.model.Trabajador;
import pe.utp.epptrack.service.EntregaService;
import pe.utp.epptrack.service.ExcepcionService;
import pe.utp.epptrack.service.ExcepcionService.Excepcion;
import pe.utp.epptrack.service.ExcepcionService.Tipo;
import pe.utp.epptrack.service.IndicadorService;
import pe.utp.epptrack.service.IndicadorService.Indicador;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Panel de excepciones e indicadores verificables: sin doble conteo, cifras con numerador/denominador y permisos. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ExcepcionesIndicadoresTest {

    @Autowired MockMvc mvc;
    @Autowired DatosPrueba datos;
    @Autowired EntregaService entregaService;
    @Autowired ExcepcionService excepcionService;
    @Autowired IndicadorService indicadorService;

    @BeforeEach
    void preparar() {
        datos.cargar();
    }

    private void entregar(Trabajador t, Epp epp, int cantidad, String justificacion) {
        EntregaForm f = datos.formulario(t, LocalDate.now(), epp);
        ItemEntregaForm item = f.getItems().getFirst();
        item.setCantidad(cantidad);
        item.setJustificacion(justificacion);
        entregaService.registrar(f, "sup-test");
    }

    /** T1: guantes 1 de 2 (parcial), respirador sin entregar, casco no exigido. T2: respirador + duplicado, guantes 2. */
    private Trabajador[] escenario() {
        Trabajador t1 = datos.trabajador("50000001", LocalDate.now().minusDays(5));
        Trabajador t2 = datos.trabajador("50000002", LocalDate.now().minusDays(5));
        entregar(t1, datos.guantes, 1, null);
        entregar(t1, datos.casco, 1, "Trabajo bajo estructura, indicado por SST");
        entregar(t2, datos.respirador, 1, null);
        entregar(t2, datos.respirador, 1, "Se rompió la correa");
        entregar(t2, datos.guantes, 2, null);
        return new Trabajador[]{t1, t2};
    }

    private Indicador indicador(String inicio) {
        return indicadorService.calcular(90).indicadores().stream()
                .filter(i -> i.nombre().startsWith(inicio)).findFirst().orElseThrow();
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void cadaCasoApareceUnaSolaVezYConTodosLosTipos() {
        escenario();
        List<Excepcion> todas = excepcionService.listar(null, null, 90);
        Set<String> claves = todas.stream().map(e -> e.tipo() + "|" + e.dni() + "|" + e.epp()).collect(Collectors.toSet());
        assertThat(claves).hasSameSizeAs(todas);

        assertThat(de(todas, Tipo.ENTREGA_INCOMPLETA)).singleElement().satisfies(e -> {
            assertThat(e.dni()).isEqualTo("50000001");
            assertThat(e.epp()).isEqualTo("Guantes de nitrilo");
            assertThat(e.detalle()).contains("Tiene 1 de 2");
        });
        assertThat(de(todas, Tipo.EPP_FALTANTE)).singleElement().satisfies(e -> {
            assertThat(e.dni()).isEqualTo("50000001");
            assertThat(e.epp()).isEqualTo("Respirador");
        });
        assertThat(de(todas, Tipo.NO_EXIGIDO_JUSTIFICADO)).singleElement()
                .satisfies(e -> assertThat(e.epp()).isEqualTo("Casco"));
        assertThat(de(todas, Tipo.DUPLICADO_JUSTIFICADO)).singleElement()
                .satisfies(e -> assertThat(e.dni()).isEqualTo("50000002"));
        assertThat(de(todas, Tipo.POSIBLE_FICHA_DUPLICADA)).hasSize(2);
        assertThat(de(todas, Tipo.CLASE_PENDIENTE_VALIDACION)).isNotEmpty();
        assertThat(de(todas, Tipo.INTEGRIDAD_AUDITORIA)).isEmpty();
    }

    private static List<Excepcion> de(List<Excepcion> todas, Tipo tipo) {
        return todas.stream().filter(e -> e.tipo() == tipo).toList();
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void laPantallaMuestraEnlacesDeOrigen() throws Exception {
        escenario();
        String html = mvc.perform(get("/excepciones")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("/entregas/nueva?dni=50000001").contains("/historial?dni=50000001")
                // El catálogo solo lo edita ADMIN: al supervisor no se le muestra un enlace que daría 403.
                .doesNotContain("/epp/" + datos.respirador.getIdEpp() + "/editar").contains("Resolver / Ver")
                .contains("Entrega incompleta");
        String filtrado = mvc.perform(get("/excepciones").param("tipo", "EPP_FALTANTE")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(filtrado).contains("Tiene 0 de 1 exigidos").doesNotContain("Tiene 1 de 2 exigidos");
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void estadoVacioCuandoNoHayCasosDelTipo() throws Exception {
        String html = mvc.perform(get("/excepciones").param("tipo", "INTEGRIDAD_AUDITORIA")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Sin excepciones en el periodo");
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void indicadoresConNumeradorYDenominadorEsperados() {
        escenario();
        Indicador dotacion = indicador("Dotación completa");
        assertThat(dotacion.numerador()).isEqualTo(1);
        assertThat(dotacion.denominador()).isEqualTo(2);
        assertThat(dotacion.valor()).isEqualTo(50.0);

        Indicador cobertura = indicador("Cobertura");
        assertThat(cobertura.numerador()).isEqualTo(4);
        assertThat(cobertura.denominador()).isEqualTo(6);

        Indicador conformes = indicador("Entregas conformes");
        assertThat(conformes.numerador()).isEqualTo(3);
        assertThat(conformes.denominador()).isEqualTo(5);

        Indicador distinta = indicador("Entregas con cantidad distinta");
        assertThat(distinta.numerador()).isEqualTo(1);
        assertThat(distinta.denominador()).isEqualTo(4);

        assertThat(indicador("Reposiciones").numerador()).isZero();
        assertThat(indicador("Excepciones pendientes").numerador())
                .isEqualTo(excepcionService.listar(null, null, 90).size());
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void sinEntregasSeMuestraDatosInsuficientes() throws Exception {
        for (String n : List.of("Dotación completa", "Cobertura", "Entregas conformes", "Entregas con cantidad distinta")) {
            Indicador i = indicador(n);
            assertThat(i.suficiente()).as(n).isFalse();
            assertThat(i.valor()).as(n).isNull();
        }
        assertThat(indicadorService.calcular(90).evolucionSuficiente()).isFalse();
        String html = mvc.perform(get("/indicadores")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Datos insuficientes").contains("Datos insuficientes para mostrar evolución")
                .contains("No medido").contains("no son una comparación con el pretest");
    }

    @Test
    @WithMockUser(roles = "RRHH")
    void rrhhNoAccede() throws Exception {
        mvc.perform(get("/excepciones")).andExpect(status().isForbidden());
        mvc.perform(get("/indicadores")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminAccede() throws Exception {
        mvc.perform(get("/excepciones")).andExpect(status().isOk());
        mvc.perform(get("/indicadores")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void parametrosInvalidosDan400ConPaginaHtml() throws Exception {
        String html = mvc.perform(get("/excepciones").param("tipo", "INVENTADO")).andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<html").contains("Tipo de excepción no válido");
        mvc.perform(get("/excepciones").param("dias", "0")).andExpect(status().isBadRequest());
        mvc.perform(get("/excepciones").param("dias", "abc")).andExpect(status().isBadRequest());
        mvc.perform(get("/indicadores").param("dias", "5000")).andExpect(status().isBadRequest());
    }

    /** Regresión: el supervisor no debe ver un botón que lleva al formulario del catálogo (solo ADMIN, daría 403). */
    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void supervisorNoVeEnlaceAlCatalogoEnClasePendiente() throws Exception {
        String html = mvc.perform(get("/excepciones").param("tipo", "CLASE_PENDIENTE_VALIDACION"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Clase").doesNotContain("/editar");
        assertThat(html).contains("Lo valida el administrador");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminSiVeEnlaceAlCatalogoEnClasePendiente() throws Exception {
        String html = mvc.perform(get("/excepciones").param("tipo", "CLASE_PENDIENTE_VALIDACION"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("/editar");
    }
}
