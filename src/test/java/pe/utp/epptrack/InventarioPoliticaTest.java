package pe.utp.epptrack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.dto.EntregaForm;
import pe.utp.epptrack.dto.ItemEntregaForm;
import pe.utp.epptrack.model.*;
import pe.utp.epptrack.repository.EppRepository;
import pe.utp.epptrack.repository.MatrizAsignacionRepository;
import pe.utp.epptrack.service.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Política de devolución por clase de EPP: desechable, reutilizable individual y compartido (inventario por código). */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InventarioPoliticaTest {

    @Autowired DatosPrueba datos;
    @Autowired InventarioService inventario;
    @Autowired EntregaService entregaService;
    @Autowired TrabajadorService trabajadorService;
    @Autowired EppRepository eppRepository;
    @Autowired MatrizAsignacionRepository matrizRepository;
    @Autowired pe.utp.epptrack.repository.UnidadEppRepository unidadRepository;
    @Autowired pe.utp.epptrack.service.LoteService loteService;

    Trabajador w1;
    Trabajador w2;
    Epp desechable;
    Epp individual;
    Epp compartido;

    @BeforeEach
    void preparar() {
        datos.cargar();
        w1 = datos.trabajador("90000011", LocalDate.now().minusDays(60));
        w2 = datos.trabajador("90000012", LocalDate.now().minusDays(60));
        desechable = epp("Mascarilla desechable", ClaseEpp.DESECHABLE);
        individual = epp("Arnés individual", ClaseEpp.REUTILIZABLE_INDIVIDUAL);
        compartido = epp("Detector compartido", ClaseEpp.REUTILIZABLE_COMPARTIDO);
    }

    private Epp epp(String nombre, ClaseEpp clase) {
        Epp e = new Epp(datos.respirador.getCategoria(), nombre, null, 200);
        e.setClase(clase);
        e = eppRepository.save(e);
        matrizRepository.save(new MatrizAsignacion(datos.actividad, e, true, 1));
        return e;
    }

    /** Ingresa una unidad y la entrega al trabajador; devuelve la unidad asignada. */
    private UnidadEpp entregar(Epp epp, Trabajador t) {
        if (inventario.unidades(epp.getIdEpp(), EstadoUnidad.DISPONIBLE).isEmpty()) {
            inventario.ingresar(epp.getIdEpp(), 1, "sup-test");
        }
        EntregaForm f = new EntregaForm();
        f.setIdTrabajador(t.getIdTrabajador());
        f.setFechaEntrega(LocalDate.now().minusDays(3));
        f.setMotivo(MotivoEntrega.INGRESO);
        f.setItems(List.of(new ItemEntregaForm(epp.getIdEpp(), true, 1)));
        Entrega e = entregaService.registrar(f, "sup-test");
        assertThat(e.getSinStock()).isEmpty();
        return inventario.buscarPorCodigo(e.getCodigosAsignados().getFirst()).orElseThrow();
    }

    /** Ingresa un lote de 1 unidad del consumible y lo entrega al trabajador. */
    private Entrega entregarConsumible(Epp epp, Trabajador t) {
        inventario.ingresar(epp.getIdEpp(), 1, "sup-test");
        EntregaForm f = new EntregaForm();
        f.setIdTrabajador(t.getIdTrabajador());
        f.setFechaEntrega(LocalDate.now().minusDays(3));
        f.setMotivo(MotivoEntrega.INGRESO);
        f.setItems(List.of(new ItemEntregaForm(epp.getIdEpp(), true, 1)));
        Entrega e = entregaService.registrar(f, "sup-test");
        assertThat(e.getSinStock()).isEmpty();
        return e;
    }

    /** Unidad CONSUMIDO como las que pueden existir en datos anteriores al control por lotes. */
    private UnidadEpp unidadHeredada(Epp epp) {
        UnidadEpp u = new UnidadEpp("EPP-%03d-HEREDADA".formatted(epp.getIdEpp()), epp);
        u.setEstado(EstadoUnidad.CONSUMIDO);
        return unidadRepository.save(u);
    }

    /** Inspecciona una unidad devuelta como «requiere mantenimiento» y la envía al taller. */
    private void aMantenimiento(UnidadEpp u) {
        inventario.registrarInspeccion(u.getIdUnidad(), ResultadoInspeccion.REQUIERE_MANTENIMIENTO, "Desgaste", "sup-test");
        inventario.enviarAMantenimiento(u.getIdUnidad(), "Desgaste visible", null, "sup-test");
    }

    private List<TipoMovimientoUnidad> tipos(UnidadEpp u) {
        return inventario.movimientos(u.getIdUnidad()).stream().map(MovimientoUnidad::getTipo).toList();
    }

    @Test
    void desechableSeDescuentaDeLotesYNoSePuedeDevolver() {
        // Cambio de regla pedido por el profesor: el consumible se descuenta de un lote, sin unidad por código.
        Entrega entrega = entregarConsumible(desechable, w1);
        assertThat(entrega.getCodigosAsignados()).isEmpty();
        assertThat(unidadRepository.findByEppIdEppAndEstadoOrderByIdUnidadAsc(desechable.getIdEpp(), EstadoUnidad.CONSUMIDO))
                .isEmpty();
        assertThat(loteService.saldo(desechable.getIdEpp())).isZero();
        // Una unidad CONSUMIDO heredada de datos antiguos sigue sin devolverse.
        UnidadEpp u = unidadHeredada(desechable);
        assertThat(inventario.pendientesDevolucion()).extracting(UnidadEpp::getCodigo).doesNotContain(u.getCodigo());
        assertThat(u.isPendienteDevolucion()).isFalse();

        assertThatThrownBy(() -> inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, MotivoDevolucion.REEMPLAZO, null, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("no corresponde devolverlo");
    }

    @Test
    void devolverSinMotivoEsError() {
        UnidadEpp u = entregar(compartido, w1);
        assertThatThrownBy(() -> inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, null, null, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("motivo");
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.ASIGNADO);
    }

    @Test
    void motivoOtroExigeObservacion() {
        UnidadEpp u = entregar(compartido, w1);
        assertThatThrownBy(() -> inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, MotivoDevolucion.OTRO, "   ", "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("Otro");
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.ASIGNADO);
    }

    @Test
    void motivoOtroConObservacionSeAcepta() {
        UnidadEpp u = entregar(compartido, w1);
        inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, MotivoDevolucion.OTRO, "Se prestó a otra cuadrilla", "sup-test");
        // Cambio de regla pedido por el profesor: lo devuelto no vuelve solo a disponible. El compartido no exige
        // inspección por su política, pero igual queda pendiente de autorización humana.
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.POR_AUTORIZAR);
    }

    @Test
    void individualDevueltoEnBuenEstadoVaAInspeccionYNoVuelveAlAlmacenHastaInspeccionarYAutorizar() {
        UnidadEpp u = entregar(individual, w1);
        inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, MotivoDevolucion.CESE_TRABAJADOR, null, "sup-test");

        // Cambio de regla pedido por el profesor: toda devolución pasa por inspección (antes iba a POR_MANTENIMIENTO).
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.POR_INSPECCION);
        assertThat(u.getTrabajador()).isNull();
        // no se puede entregar a otro trabajador mientras no se inspeccione
        EntregaForm f = new EntregaForm();
        f.setIdTrabajador(w2.getIdTrabajador());
        f.setFechaEntrega(LocalDate.now().minusDays(1));
        f.setMotivo(MotivoEntrega.INGRESO);
        f.setItems(List.of(new ItemEntregaForm(individual.getIdEpp(), true, 1)));
        Entrega sinStock = entregaService.registrar(f, "sup-test");
        assertThat(sinStock.getSinStock()).isNotEmpty();
        assertThat(sinStock.getCodigosAsignados()).isEmpty();
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.POR_INSPECCION);

        inventario.registrarInspeccion(u.getIdUnidad(), ResultadoInspeccion.APTO_PARA_USO, null, "sup-test");
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.POR_AUTORIZAR);
        inventario.autorizarRetorno(u.getIdUnidad(), "Revisado", "sup-test");
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.DISPONIBLE);
        assertThat(tipos(u)).contains(TipoMovimientoUnidad.INSPECCION, TipoMovimientoUnidad.AUTORIZADO_RETORNO);
    }

    @Test
    void compartidoEnBuenEstadoYVigenteQuedaPorAutorizarYSoloEsDisponibleTrasAutorizar() {
        UnidadEpp u = entregar(compartido, w1);
        inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, MotivoDevolucion.REEMPLAZO, null, "sup-test");

        // Cambio de regla pedido por el profesor: antes un compartido en buen estado volvía directo al almacén.
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.POR_AUTORIZAR);
        assertThat(u.getTrabajador()).isNull();
        assertThat(u.getDetalle()).isNull();
        inventario.autorizarRetorno(u.getIdUnidad(), null, "sup-test");
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.DISPONIBLE);
    }

    @Test
    void inservibleSeDaDeBajaConMovimientosDevolucionYBaja() {
        UnidadEpp u = entregar(compartido, w1);
        inventario.devolver(u.getCodigo(), CondicionEpp.INSERVIBLE, MotivoDevolucion.DETERIORO, "Roto", "sup-test");

        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.DE_BAJA);
        assertThat(tipos(u)).contains(TipoMovimientoUnidad.DEVOLUCION, TipoMovimientoUnidad.BAJA);
    }

    @Test
    void unidadNoAsignadaNoSePuedeDevolver() {
        UnidadEpp u = inventario.ingresar(compartido.getIdEpp(), 1, "sup-test").getFirst();
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.DISPONIBLE);
        assertThatThrownBy(() -> inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, MotivoDevolucion.REEMPLAZO, null, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("no está asignado");
    }

    @Test
    void overrideDevolucionNoPermitidaBloqueaUnReutilizable() {
        UnidadEpp u = entregar(compartido, w1);
        compartido.setDevolucionPermitida(false);

        assertThat(u.isPendienteDevolucion()).isFalse();
        assertThatThrownBy(() -> inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, MotivoDevolucion.REEMPLAZO, null, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("no corresponde devolverlo");
    }

    @Test
    void overrideSinInspeccionEnIndividualLoDejaPorAutorizarYSoloDisponibleTrasAutorizar() {
        UnidadEpp u = entregar(individual, w1);
        individual.setInspeccionAlDevolver(false);
        inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, MotivoDevolucion.REEMPLAZO, null, "sup-test");

        // Cambio de regla pedido por el profesor: sin inspección exigida igual requiere autorización humana.
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.POR_AUTORIZAR);
        inventario.autorizarRetorno(u.getIdUnidad(), null, "sup-test");
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.DISPONIBLE);
    }

    @Test
    void laDevolucionGuardaElMotivoEnElMovimiento() {
        UnidadEpp u = entregar(compartido, w1);
        inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, MotivoDevolucion.DETERIORO, "Correa floja", "sup-test");

        MovimientoUnidad m = inventario.movimientos(u.getIdUnidad()).stream()
                .filter(x -> x.getTipo() == TipoMovimientoUnidad.DEVOLUCION).findFirst().orElseThrow();
        assertThat(m.getMotivo()).isEqualTo(MotivoDevolucion.DETERIORO);
        assertThat(m.getObservacion()).contains("Correa floja");
        assertThat(m.getUsuario()).isEqualTo("sup-test");
    }

    @Test
    void anularLaEntregaDeUnDesechableRestituyeElSaldoDelLote() {
        // Cambio de regla pedido por el profesor: se revierte el saldo del lote, no una unidad.
        Entrega entrega = entregarConsumible(desechable, w1);
        assertThat(loteService.saldo(desechable.getIdEpp())).isZero();
        Integer idDetalle = entrega.getDetalles().getFirst().getIdDetalle();

        entregaService.anularDetalle(idDetalle, "Entrega registrada por error", "sup-test");

        assertThat(loteService.saldo(desechable.getIdEpp())).isEqualTo(1);
    }

    @Test
    void ahorroLogradoSoloCuentaLaReasignacionDeUnaUnidadYaUsada() {
        compartido.setCostoUnitario(new BigDecimal("10.50"));
        BigDecimal base = inventario.ahorroLogrado();

        UnidadEpp u = entregar(compartido, w1);
        assertThat(inventario.ahorroLogrado()).isEqualByComparingTo(base);
        inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, MotivoDevolucion.REEMPLAZO, null, "sup-test");
        // Cambio de regla pedido por el profesor: la unidad debe autorizarse antes de estar disponible.
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.POR_AUTORIZAR);
        assertThat(inventario.ahorroLogrado()).isEqualByComparingTo(base);
        inventario.autorizarRetorno(u.getIdUnidad(), null, "sup-test");
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.DISPONIBLE);
        assertThat(inventario.ahorroLogrado()).isEqualByComparingTo(base);

        UnidadEpp otra = entregar(compartido, w2);
        assertThat(otra.getIdUnidad()).isEqualTo(u.getIdUnidad());
        assertThat(inventario.ahorroLogrado()).isEqualByComparingTo(base.add(new BigDecimal("10.50")));
    }

    @Test
    void unaDevolucionQueTerminaEnBajaNoCuentaComoAhorro() {
        compartido.setCostoUnitario(new BigDecimal("10.50"));
        BigDecimal base = inventario.ahorroLogrado();

        UnidadEpp u = entregar(compartido, w1);
        inventario.devolver(u.getCodigo(), CondicionEpp.INSERVIBLE, MotivoDevolucion.DETERIORO, null, "sup-test");
        UnidadEpp nueva = entregar(compartido, w2);   // sale una unidad nueva, no la dada de baja

        assertThat(nueva.getIdUnidad()).isNotEqualTo(u.getIdUnidad());
        assertThat(inventario.ahorroLogrado()).isEqualByComparingTo(base);
    }

    @Test
    void unaReparacionFallidaTampocoCuentaComoAhorro() {
        compartido.setCostoUnitario(new BigDecimal("10.50"));
        BigDecimal base = inventario.ahorroLogrado();

        UnidadEpp u = entregar(compartido, w1);
        inventario.devolver(u.getCodigo(), CondicionEpp.REPARABLE, MotivoDevolucion.DETERIORO, null, "sup-test");
        // Cambio de regla pedido por el profesor: el reparable también se inspecciona antes de ir a mantenimiento.
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.POR_INSPECCION);
        aMantenimiento(u);
        inventario.cerrarMantenimiento(u.getIdUnidad(), ResultadoMantenimiento.NO_REPARABLE, "No se pudo", null, null, "sup-test");
        entregar(compartido, w2);

        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.DE_BAJA);
        assertThat(inventario.ahorroLogrado()).isEqualByComparingTo(base);
    }

    @Test
    void unaUnidadReparadaYReasignadaSiCuentaComoAhorro() {
        compartido.setCostoUnitario(new BigDecimal("10.50"));
        BigDecimal base = inventario.ahorroLogrado();

        UnidadEpp u = entregar(compartido, w1);
        inventario.devolver(u.getCodigo(), CondicionEpp.REPARABLE, MotivoDevolucion.DETERIORO, null, "sup-test");
        aMantenimiento(u);
        inventario.cerrarMantenimiento(u.getIdUnidad(), ResultadoMantenimiento.REPARADO, "Reparado", null, null, "sup-test");
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.POR_AUTORIZAR);   // reparada no es autorizada
        inventario.autorizarRetorno(u.getIdUnidad(), null, "sup-test");
        UnidadEpp otra = entregar(compartido, w2);

        assertThat(otra.getIdUnidad()).isEqualTo(u.getIdUnidad());
        assertThat(inventario.ahorroLogrado()).isEqualByComparingTo(base.add(new BigDecimal("10.50")));
    }

    @Test
    void pendienteDeDevolucionRespetaLaPoliticaDelEpp() {
        UnidadEpp reutilizable = entregar(compartido, w1);
        UnidadEpp sinDevolucion = entregar(individual, w2);
        individual.setDevolucionPermitida(false);

        trabajadorService.registrarCese(w1.getIdTrabajador(), LocalDate.now());
        trabajadorService.registrarCese(w2.getIdTrabajador(), LocalDate.now());

        assertThat(reutilizable.isPendienteDevolucion()).isTrue();
        assertThat(sinDevolucion.isPendienteDevolucion()).isFalse();
        assertThat(inventario.pendientesDevolucion()).extracting(UnidadEpp::getCodigo)
                .contains(reutilizable.getCodigo()).doesNotContain(sinDevolucion.getCodigo());
    }

    @Test
    void eppSinClaseSeTrataComoReutilizableIndividualPendienteDeValidacion() {
        Epp sinClase = new Epp(datos.respirador.getCategoria(), "Equipo sin clasificar", null, 200);
        sinClase = eppRepository.save(sinClase);
        matrizRepository.save(new MatrizAsignacion(datos.actividad, sinClase, true, 1));

        assertThat(sinClase.getClase()).isNull();
        assertThat(sinClase.getClaseEfectiva()).isEqualTo(ClaseEpp.REUTILIZABLE_INDIVIDUAL);
        assertThat(sinClase.isClasePendienteValidacion()).isTrue();
        assertThat(sinClase.isDesechable()).isFalse();
        assertThat(sinClase.isPermiteDevolucion()).isTrue();
        assertThat(sinClase.isRequiereInspeccionAlDevolver()).isTrue();

        UnidadEpp u = entregar(sinClase, w1);
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.ASIGNADO);
        inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, MotivoDevolucion.REEMPLAZO, null, "sup-test");
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.POR_INSPECCION);   // regla nueva: inspección primero

        sinClase.setClaseValidada(true);
        assertThat(sinClase.isClasePendienteValidacion()).isFalse();
    }
}
