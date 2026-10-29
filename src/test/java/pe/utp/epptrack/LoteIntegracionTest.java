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
import pe.utp.epptrack.model.*;
import pe.utp.epptrack.repository.ConsumoLoteRepository;
import pe.utp.epptrack.repository.EppRepository;
import pe.utp.epptrack.repository.RegistroAuditoriaRepository;
import pe.utp.epptrack.repository.UnidadEppRepository;
import pe.utp.epptrack.service.*;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Integración del control por lotes de consumibles con entregas, inventario, necesidad de stock, pantallas y auditoría. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LoteIntegracionTest {

    private static final String SUP = "sup-test";

    @Autowired MockMvc mvc;
    @Autowired DatosPrueba datos;
    @Autowired InventarioService inventario;
    @Autowired LoteService lotes;
    @Autowired EntregaService entregaService;
    @Autowired NecesidadStockService necesidad;
    @Autowired EppRepository eppRepository;
    @Autowired UnidadEppRepository unidadRepository;
    @Autowired ConsumoLoteRepository consumoRepository;
    @Autowired RegistroAuditoriaRepository registros;
    @Autowired AuditoriaService auditoria;

    Trabajador w1;

    @BeforeEach
    void preparar() {
        datos.cargar();
        datos.guantes.setClase(ClaseEpp.DESECHABLE);
        eppRepository.save(datos.guantes);
        w1 = datos.trabajador("90000041", LocalDate.now().minusDays(60));
    }

    private Entrega entregarGuantes(int cantidad) {
        EntregaForm f = new EntregaForm();
        f.setIdTrabajador(w1.getIdTrabajador());
        f.setFechaEntrega(LocalDate.now().minusDays(1));
        f.setMotivo(MotivoEntrega.INGRESO);
        f.setItems(List.of(new ItemEntregaForm(datos.guantes.getIdEpp(), true, cantidad)));
        return entregaService.registrar(f, SUP);
    }

    private long unidadesDe(Epp epp) {
        return unidadRepository.countByEppIdEpp(epp.getIdEpp());
    }

    @Test
    void ingresarConsumibleCreaLoteYNoCreaUnidades() {
        List<UnidadEpp> nuevas = inventario.ingresar(datos.guantes.getIdEpp(), 10, SUP);

        assertThat(nuevas).isEmpty();
        assertThat(unidadesDe(datos.guantes)).isZero();
        assertThat(lotes.saldo(datos.guantes.getIdEpp())).isEqualTo(10);
        assertThat(lotes.lotes(datos.guantes.getIdEpp())).hasSize(1);

        LoteConsumible conCodigo = inventario.ingresarConsumible(datos.guantes.getIdEpp(), 5, "LOTE-FICT-1",
                LocalDate.now().plusDays(20), null, SUP);
        assertThat(conCodigo.getCodigoLote()).isEqualTo("LOTE-FICT-1");
        assertThat(lotes.saldo(datos.guantes.getIdEpp())).isEqualTo(15);
        assertThat(unidadesDe(datos.guantes)).isZero();
    }

    @Test
    void entregarDescuentaSaldoYRegistraConsumoVinculadoAlDetalleSinCodigos() {
        inventario.ingresar(datos.guantes.getIdEpp(), 5, SUP);

        Entrega e = entregarGuantes(2);

        assertThat(e.getCodigosAsignados()).isEmpty();
        assertThat(e.getSinStock()).isEmpty();
        assertThat(lotes.saldo(datos.guantes.getIdEpp())).isEqualTo(3);
        Integer idDetalle = e.getDetalles().getFirst().getIdDetalle();
        List<ConsumoLote> consumos = consumoRepository.findByDetalleIdDetalleAndRevertidoFalse(idDetalle);
        assertThat(consumos).hasSize(1);
        assertThat(consumos.getFirst().getCantidad()).isEqualTo(2);
        assertThat(unidadesDe(datos.guantes)).isZero();
    }

    @Test
    void entregaMayorAlSaldoInformaElFaltanteExactoYDejaSaldoCero() {
        inventario.ingresar(datos.guantes.getIdEpp(), 1, SUP);

        Entrega e = entregarGuantes(2);

        assertThat(e.getSinStock()).containsExactly("Guantes de nitrilo (1)");
        assertThat(lotes.saldo(datos.guantes.getIdEpp())).isZero();
    }

    @Test
    void anularLaEntregaRestituyeElSaldoExacto() {
        inventario.ingresar(datos.guantes.getIdEpp(), 1, SUP);
        Entrega e = entregarGuantes(2);   // consume 1 de 2 solicitados
        assertThat(lotes.saldo(datos.guantes.getIdEpp())).isZero();

        entregaService.anularDetalle(e.getDetalles().getFirst().getIdDetalle(), "Entrega registrada por error", SUP);

        assertThat(lotes.saldo(datos.guantes.getIdEpp())).isEqualTo(1);   // solo se restituye lo realmente descontado
    }

    @Test
    void unConsumibleNoSeDevuelveNiSeInspecciona() {
        inventario.ingresar(datos.guantes.getIdEpp(), 3, SUP);
        entregarGuantes(2);
        // Unidad heredada de datos anteriores al control por lotes.
        UnidadEpp heredada = new UnidadEpp("EPP-%03d-HEREDADA".formatted(datos.guantes.getIdEpp()), datos.guantes);
        heredada.setEstado(EstadoUnidad.CONSUMIDO);
        UnidadEpp u = unidadRepository.save(heredada);

        assertThatThrownBy(() -> inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, MotivoDevolucion.REEMPLAZO, null, SUP))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("no corresponde devolverlo");
        assertThatThrownBy(() -> inventario.registrarInspeccion(u.getIdUnidad(), ResultadoInspeccion.APTO_PARA_USO, null, SUP))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("no pasa por inspección");
        // Un consumible nuevo no tiene código de unidad que devolver.
        assertThatThrownBy(() -> inventario.devolver("EPP-%03d-0001".formatted(datos.guantes.getIdEpp()), CondicionEpp.BUENO,
                MotivoDevolucion.REEMPLAZO, null, SUP))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("No existe un EPP con el código");
    }

    @Test
    void losReutilizablesSiguenUsandoUnidadesConCodigo() {
        Epp casco = datos.respirador;
        casco.setClase(ClaseEpp.REUTILIZABLE_INDIVIDUAL);
        List<UnidadEpp> nuevas = inventario.ingresar(casco.getIdEpp(), 3, SUP);
        assertThat(nuevas).hasSize(3);
        assertThat(nuevas.getFirst().getCodigo()).startsWith("EPP-");
        assertThat(lotes.lotes(casco.getIdEpp())).isEmpty();

        EntregaForm f = new EntregaForm();
        f.setIdTrabajador(w1.getIdTrabajador());
        f.setFechaEntrega(LocalDate.now().minusDays(1));
        f.setMotivo(MotivoEntrega.INGRESO);
        f.setItems(List.of(new ItemEntregaForm(casco.getIdEpp(), true, 1)));
        Entrega e = entregaService.registrar(f, SUP);

        assertThat(e.getCodigosAsignados()).hasSize(1);
        assertThat(e.getSinStock()).isEmpty();
        assertThatThrownBy(() -> inventario.ingresarConsumible(casco.getIdEpp(), 2, null, null, null, SUP))
                .isInstanceOf(ReglaNegocioException.class);
    }

    @Test
    void resumenYNecesidadMuestranElSaldoDeLotes() {
        inventario.ingresar(datos.guantes.getIdEpp(), 8, SUP);
        inventario.ingresarConsumible(datos.guantes.getIdEpp(), 4, "LOTE-FICT-2", LocalDate.now().plusDays(10), null, SUP);

        InventarioService.FilaInventario fila = inventario.resumen().filas().stream()
                .filter(x -> x.epp().getIdEpp().equals(datos.guantes.getIdEpp())).findFirst().orElseThrow();
        assertThat(fila.disponibles()).isEqualTo(12);

        NecesidadStockService.FilaNecesidad n = necesidad.calcular(null).filas().stream()
                .filter(x -> x.epp().getIdEpp().equals(datos.guantes.getIdEpp())).findFirst().orElseThrow();
        assertThat(n.disponible()).isEqualTo(12);
        assertThat(n.stockPorLotes()).isEqualTo(12);
        assertThat(n.getControlLotes()).isEqualTo("Control por lotes: 12 unidades en 2 lotes (1 por vencer en 30 días)");
    }

    @Test
    void recuperablesIncluyenPorInspeccionYPorAutorizarPeroNoCuarentenaNiBaja() {
        var fila = new InventarioService.FilaInventario(datos.casco, 0, 0, 0, 0, 0, 0, 1, 2, 4, 8);
        assertThat(fila.getRecuperables()).isEqualTo(11);   // 8 por revisar + 1 por mantenimiento + 2 en taller
    }

    @Test
    @WithMockUser(username = "rrhh", roles = "RRHH")
    void rrhhRecibe403EnIngresoYNecesidad() throws Exception {
        mvc.perform(post("/inventario/ingreso").with(csrf()).param("idEpp", datos.guantes.getIdEpp().toString())
                .param("cantidad", "5")).andExpect(status().isForbidden());
        mvc.perform(get("/inventario/necesidad")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "sup-test", roles = "SUPERVISOR")
    void supervisorIngresaUnLoteYLoVeEnLaPantalla() throws Exception {
        mvc.perform(post("/inventario/ingreso").with(csrf()).param("idEpp", datos.guantes.getIdEpp().toString())
                        .param("cantidad", "5").param("codigoLote", "LOTE-WEB-1")
                        .param("vencimiento", LocalDate.now().plusDays(15).toString()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("exito", org.hamcrest.Matchers.containsString("Ingresó el lote LOTE-WEB-1 con 5 unidades")));
        mvc.perform(get("/inventario")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Lotes de consumibles")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("LOTE-WEB-1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Vence pronto")));
        mvc.perform(get("/inventario/necesidad")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Control por lotes: 5 unidades en 1 lotes")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Control por lotes pendiente"))));
    }

    @Test
    void laAuditoriaRegistraIngresoYConsumoDeLotesYSigueIntegra() {
        inventario.ingresar(datos.guantes.getIdEpp(), 5, SUP);
        entregarGuantes(2);

        List<String> acciones = registros.findAllByOrderByIdRegistroAsc().stream().map(RegistroAuditoria::getAccion).toList();
        assertThat(acciones).contains("LOTE_INGRESO", "LOTE_CONSUMO");
        assertThat(auditoria.verificar().integra()).isTrue();
    }
}
