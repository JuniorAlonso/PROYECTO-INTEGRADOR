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

    private List<TipoMovimientoUnidad> tipos(UnidadEpp u) {
        return inventario.movimientos(u.getIdUnidad()).stream().map(MovimientoUnidad::getTipo).toList();
    }

    @Test
    void desechableQuedaConsumidoYNoSePuedeDevolver() {
        UnidadEpp u = entregar(desechable, w1);

        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.CONSUMIDO);
        assertThat(tipos(u)).contains(TipoMovimientoUnidad.CONSUMO).doesNotContain(TipoMovimientoUnidad.ASIGNACION);
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
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.DISPONIBLE);
    }

    @Test
    void individualDevueltoEnBuenEstadoVaAInspeccionYNoVuelveAlAlmacenHastaCerrarElMantenimiento() {
        UnidadEpp u = entregar(individual, w1);
        inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, MotivoDevolucion.CESE_TRABAJADOR, null, "sup-test");

        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.POR_MANTENIMIENTO);
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
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.POR_MANTENIMIENTO);

        inventario.cerrarMantenimiento(u.getIdUnidad(), true, "Inspeccionado", "sup-test");
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.DISPONIBLE);
        assertThat(tipos(u)).contains(TipoMovimientoUnidad.REPARADO);
    }

    @Test
    void compartidoEnBuenEstadoYVigenteVuelveAlAlmacen() {
        UnidadEpp u = entregar(compartido, w1);
        inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, MotivoDevolucion.REEMPLAZO, null, "sup-test");

        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.DISPONIBLE);
        assertThat(u.getTrabajador()).isNull();
        assertThat(u.getDetalle()).isNull();
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
    void overrideSinInspeccionEnIndividualLoDejaDisponible() {
        UnidadEpp u = entregar(individual, w1);
        individual.setInspeccionAlDevolver(false);
        inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, MotivoDevolucion.REEMPLAZO, null, "sup-test");

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
    void anularLaEntregaDeUnDesechableConsumidoLiberaLaUnidad() {
        UnidadEpp u = entregar(desechable, w1);
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.CONSUMIDO);
        Integer idDetalle = u.getDetalle().getIdDetalle();

        entregaService.anularDetalle(idDetalle, "Entrega registrada por error", "sup-test");

        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.DISPONIBLE);
        assertThat(u.getTrabajador()).isNull();
        assertThat(tipos(u)).contains(TipoMovimientoUnidad.LIBERADO);
    }

    @Test
    void ahorroLogradoSoloCuentaLaReasignacionDeUnaUnidadYaUsada() {
        compartido.setCostoUnitario(new BigDecimal("10.50"));
        BigDecimal base = inventario.ahorroLogrado();

        UnidadEpp u = entregar(compartido, w1);
        assertThat(inventario.ahorroLogrado()).isEqualByComparingTo(base);
        inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, MotivoDevolucion.REEMPLAZO, null, "sup-test");
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
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.POR_MANTENIMIENTO);
        inventario.cerrarMantenimiento(u.getIdUnidad(), false, "No se pudo", "sup-test");
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
        inventario.cerrarMantenimiento(u.getIdUnidad(), true, "Reparado", "sup-test");
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
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.POR_MANTENIMIENTO);

        sinClase.setClaseValidada(true);
        assertThat(sinClase.isClasePendienteValidacion()).isFalse();
    }
}
