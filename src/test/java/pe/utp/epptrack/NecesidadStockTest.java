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
import pe.utp.epptrack.model.ClaseEpp;
import pe.utp.epptrack.model.Epp;
import pe.utp.epptrack.model.EstadoUnidad;
import pe.utp.epptrack.model.UnidadEpp;
import pe.utp.epptrack.repository.UnidadEppRepository;
import pe.utp.epptrack.service.EntregaService;
import pe.utp.epptrack.service.InventarioService;
import pe.utp.epptrack.service.NecesidadStockService;
import pe.utp.epptrack.service.NecesidadStockService.FilaNecesidad;
import pe.utp.epptrack.service.NecesidadStockService.Recomendacion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Necesidad de EPP según el personal: cálculos, separación de estados de stock, consumibles y permisos. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NecesidadStockTest {

    @Autowired MockMvc mvc;
    @Autowired DatosPrueba datos;
    @Autowired NecesidadStockService servicio;
    @Autowired InventarioService inventario;
    @Autowired EntregaService entregaService;
    @Autowired UnidadEppRepository unidadRepository;
    @Autowired pe.utp.epptrack.repository.LoteConsumibleRepository loteRepository;

    @BeforeEach
    void preparar() {
        datos.cargar();
    }

    private FilaNecesidad fila(Epp epp) {
        return servicio.calcular(null).filas().stream()
                .filter(f -> f.epp().getIdEpp().equals(epp.getIdEpp())).findFirst().orElseThrow();
    }

    private void cambiarEstado(Epp epp, EstadoUnidad desde, EstadoUnidad hasta, int cuantas) {
        List<UnidadEpp> unidades = unidadRepository.findByEppIdEppAndEstadoOrderByIdUnidadAsc(epp.getIdEpp(), desde);
        unidades.subList(0, cuantas).forEach(u -> u.setEstado(hasta));
        unidadRepository.flush();
    }

    @Test
    void pendienteYFaltanteConLaMatrizDePrueba() {
        datos.trabajador("60000001", LocalDate.now().minusDays(5));
        datos.trabajador("60000002", LocalDate.now().minusDays(5));
        inventario.ingresar(datos.respirador.getIdEpp(), 1, "sup-test");
        inventario.ingresar(datos.guantes.getIdEpp(), 3, "sup-test");

        FilaNecesidad resp = fila(datos.respirador);
        assertThat(resp.personal()).isEqualTo(2);
        assertThat(resp.requerida()).isEqualTo(2);
        assertThat(resp.pendiente()).isEqualTo(2);
        assertThat(resp.getFaltante()).isEqualTo(1);
        assertThat(resp.getRecomendacion()).isEqualTo(Recomendacion.COMPRAR);
        assertThat(resp.getMotivo()).contains("Comprar 1 unidades");

        FilaNecesidad gua = fila(datos.guantes);
        assertThat(gua.requerida()).isEqualTo(4);
        assertThat(gua.pendiente()).isEqualTo(4);
        assertThat(gua.getFaltante()).isEqualTo(1);
        assertThat(servicio.calcular(null).considerados()).isEqualTo(2);
    }

    @Test
    void trabajadorConDotacionCompletaNoAportaPendiente() {
        var completo = datos.trabajador("60000003", LocalDate.now().minusDays(5));
        datos.trabajador("60000004", LocalDate.now().minusDays(5));
        EntregaForm form = datos.formulario(completo, LocalDate.now(), datos.respirador, datos.guantes);
        form.getItems().get(1).setCantidad(2);
        entregaService.registrar(form, "sup-test");

        FilaNecesidad resp = fila(datos.respirador);
        assertThat(resp.requerida()).isEqualTo(2);
        assertThat(resp.enPoder()).isEqualTo(1);
        assertThat(resp.pendiente()).isEqualTo(1);
        assertThat(fila(datos.guantes).pendiente()).isEqualTo(2);
    }

    @Test
    void mantenimientoCuarentenaInspeccionYBajaNoSumanADisponible() {
        datos.trabajador("60000005", LocalDate.now().minusDays(5));
        inventario.ingresar(datos.respirador.getIdEpp(), 6, "sup-test");
        Epp r = datos.respirador;
        cambiarEstado(r, EstadoUnidad.DISPONIBLE, EstadoUnidad.EN_MANTENIMIENTO, 1);
        cambiarEstado(r, EstadoUnidad.DISPONIBLE, EstadoUnidad.EN_CUARENTENA, 1);
        cambiarEstado(r, EstadoUnidad.DISPONIBLE, EstadoUnidad.POR_INSPECCION, 1);
        cambiarEstado(r, EstadoUnidad.DISPONIBLE, EstadoUnidad.POR_AUTORIZAR, 1);
        cambiarEstado(r, EstadoUnidad.DISPONIBLE, EstadoUnidad.DE_BAJA, 1);

        FilaNecesidad f = fila(r);
        assertThat(f.disponible()).isEqualTo(1);
        assertThat(f.enMantenimiento()).isEqualTo(1);
        assertThat(f.cuarentena()).isEqualTo(1);
        assertThat(f.porRevisar()).isEqualTo(2);
        assertThat(f.deBaja()).isEqualTo(1);
        assertThat(f.getRecuperables()).isEqualTo(3);
        assertThat(f.getFaltante()).isZero();   // 1 necesidad − 1 disponible; los recuperables no se restan
        var necesidad = servicio.calcular(null);
        assertThat(necesidad.alertas()).extracting(NecesidadStockService.Alerta::url)
                .contains("/inventario/devoluciones", "/inventario/unidades?estado=EN_CUARENTENA", "/inventario/mantenimiento");
    }

    @Test
    void unidadAsignadaNoEsDisponibleNiExcedente() {
        datos.trabajador("60000006", LocalDate.now().minusDays(5));
        inventario.ingresar(datos.respirador.getIdEpp(), 2, "sup-test");
        cambiarEstado(datos.respirador, EstadoUnidad.DISPONIBLE, EstadoUnidad.ASIGNADO, 2);

        FilaNecesidad f = fila(datos.respirador);
        assertThat(f.disponible()).isZero();
        assertThat(f.asignadas()).isEqualTo(2);
        assertThat(f.getExcedente()).isZero();
        assertThat(f.getFaltante()).isEqualTo(1);
    }

    @Test
    void sinTrabajadoresNoHayNecesidadYSinMatrizHayDatosInsuficientes() {
        FilaNecesidad resp = fila(datos.respirador);
        assertThat(resp.getNecesidad()).isZero();
        assertThat(resp.getRecomendacion()).isEqualTo(Recomendacion.SIN_NECESIDAD);
        assertThat(resp.getMotivo()).startsWith("Sin necesidad registrada");
        assertThat(fila(datos.casco).getRecomendacion()).isEqualTo(Recomendacion.DATOS_INSUFICIENTES);
    }

    @Test
    void costoSoloSiElEppTieneCostoUnitario() {
        datos.trabajador("60000007", LocalDate.now().minusDays(5));
        FilaNecesidad sinCosto = fila(datos.respirador);
        assertThat(sinCosto.getFaltante()).isEqualTo(1);
        assertThat(sinCosto.isSinCosto()).isTrue();
        assertThat(sinCosto.getCostoEstimado()).isNull();

        datos.guantes.setCostoUnitario(new BigDecimal("2.50"));
        FilaNecesidad conCosto = fila(datos.guantes);
        assertThat(conCosto.getCostoEstimado()).isEqualByComparingTo("5.00");
        assertThat(servicio.calcular(null).tiposSinCosto()).isEqualTo(1);
    }

    @Test
    void desechableSeMarcaComoConsumibleYSoloCuentaDisponibles() {
        datos.trabajador("60000008", LocalDate.now().minusDays(5));
        datos.respirador.setClase(ClaseEpp.DESECHABLE);
        // Cambio de regla pedido por el profesor: el consumible ingresa como lote (sin unidades) y su stock es el saldo.
        inventario.ingresar(datos.respirador.getIdEpp(), 3, "sup-test");
        loteRepository.findByEppIdEppOrderByFechaIngresoDescIdLoteDesc(datos.respirador.getIdEpp()).getFirst().setSaldo(2);
        loteRepository.flush();

        FilaNecesidad f = fila(datos.respirador);
        assertThat(f.isConsumible()).isTrue();
        assertThat(unidadRepository.findByEppIdEppAndEstadoOrderByIdUnidadAsc(datos.respirador.getIdEpp(), EstadoUnidad.DISPONIBLE))
                .isEmpty();
        assertThat(f.disponible()).isEqualTo(2);
        assertThat(f.stockPorLotes()).isEqualTo(2);
        assertThat(f.lotesActivos()).isEqualTo(1);
        assertThat(f.getControlLotes()).isEqualTo("Control por lotes: 2 unidades en 1 lotes (0 por vencer en 30 días)");
        assertThat(f.getRecuperables()).isZero();
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void sedeInvalidaDevuelve400() throws Exception {
        mvc.perform(get("/inventario/necesidad").param("sede", "999999")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "SUPERVISOR")
    void supervisorVeLaPaginaConLaNotaDeTallas() throws Exception {
        String html = mvc.perform(get("/inventario/necesidad").param("sede", String.valueOf(datos.sede.getIdSede())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Necesidad de EPP según el personal").contains("Tallas: dato faltante")
                .contains("no se calcula existencia por talla").contains("Cómo se calcula").contains("ficticios");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminAccede() throws Exception {
        mvc.perform(get("/inventario/necesidad")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "RRHH")
    void rrhhNoAccede() throws Exception {
        mvc.perform(get("/inventario/necesidad")).andExpect(status().isForbidden());
    }
}
