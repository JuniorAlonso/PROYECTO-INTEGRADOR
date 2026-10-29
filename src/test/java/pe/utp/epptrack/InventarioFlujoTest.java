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
import pe.utp.epptrack.repository.RegistroAuditoriaRepository;
import pe.utp.epptrack.repository.UnidadEppRepository;
import pe.utp.epptrack.service.AuditoriaService;
import pe.utp.epptrack.service.EntregaService;
import pe.utp.epptrack.service.InventarioService;
import pe.utp.epptrack.service.ReglaNegocioException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flujo completo de trazabilidad por unidad: identificación → asignación → devolución → inspección →
 * (mantenimiento | cuarentena | baja | autorización de retorno) → disponible. La autorización siempre es humana.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InventarioFlujoTest {

    private static final String SUP = "sup-test";

    @Autowired DatosPrueba datos;
    @Autowired InventarioService inventario;
    @Autowired EntregaService entregaService;
    @Autowired EppRepository eppRepository;
    @Autowired MatrizAsignacionRepository matrizRepository;
    @Autowired RegistroAuditoriaRepository registros;
    @Autowired AuditoriaService auditoria;
    @Autowired UnidadEppRepository unidadRepository;

    int contadorDni;
    Trabajador w1;
    Trabajador w2;
    Epp arnes;

    @BeforeEach
    void preparar() {
        datos.cargar();
        w1 = datos.trabajador("90000031", LocalDate.now().minusDays(60));
        w2 = datos.trabajador("90000032", LocalDate.now().minusDays(60));
        arnes = epp("Arnés de flujo", ClaseEpp.REUTILIZABLE_INDIVIDUAL);
    }

    private Epp epp(String nombre, ClaseEpp clase) {
        Epp e = new Epp(datos.respirador.getCategoria(), nombre, null, 200);
        e.setClase(clase);
        e = eppRepository.save(e);
        matrizRepository.save(new MatrizAsignacion(datos.actividad, e, true, 1));
        return e;
    }

    private Entrega entregarA(Trabajador t, Epp epp) {
        EntregaForm f = new EntregaForm();
        f.setIdTrabajador(t.getIdTrabajador());
        f.setFechaEntrega(LocalDate.now().minusDays(2));
        f.setMotivo(MotivoEntrega.INGRESO);
        f.setItems(List.of(new ItemEntregaForm(epp.getIdEpp(), true, 1)));
        return entregaService.registrar(f, SUP);
    }

    /** Ingresa una unidad, la entrega y la devuelve en buen estado: queda POR_INSPECCION. */
    private UnidadEpp devuelta() {
        inventario.ingresar(arnes.getIdEpp(), 1, SUP);
        Entrega e = entregarA(w1, arnes);
        UnidadEpp u = inventario.buscarPorCodigo(e.getCodigosAsignados().getFirst()).orElseThrow();
        inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, MotivoDevolucion.REEMPLAZO, null, SUP);
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.POR_INSPECCION);
        return u;
    }

    private UnidadEpp enMantenimiento() {
        UnidadEpp u = devuelta();
        inventario.registrarInspeccion(u.getIdUnidad(), ResultadoInspeccion.REQUIERE_MANTENIMIENTO, "Correa floja", SUP);
        inventario.enviarAMantenimiento(u.getIdUnidad(), "Correa floja", "Cambiar correa", SUP);
        return u;
    }

    private List<String> acciones(UnidadEpp u) {
        return registros.findAllByOrderByIdRegistroAsc().stream()
                .filter(r -> "unidad_epp".equals(r.getEntidad()) && String.valueOf(u.getIdUnidad()).equals(r.getIdEntidad()))
                .map(RegistroAuditoria::getAccion).toList();
    }

    // ---- identificación ----

    @Test
    void ingresarNUnidadesGeneraNCodigosUnicosYConsultables() {
        List<UnidadEpp> nuevas = inventario.ingresar(arnes.getIdEpp(), 12, SUP);

        assertThat(nuevas).hasSize(12);
        assertThat(new HashSet<>(nuevas.stream().map(UnidadEpp::getCodigo).toList())).hasSize(12);
        for (UnidadEpp u : nuevas) {
            assertThat(inventario.buscarPorCodigo(u.getCodigo())).get().extracting(UnidadEpp::getIdUnidad).isEqualTo(u.getIdUnidad());
        }
        // la búsqueda no distingue mayúsculas ni espacios alrededor
        String c = nuevas.getFirst().getCodigo();
        assertThat(inventario.buscarPorCodigo("  " + c.toLowerCase() + " ")).isPresent();
        assertThat(inventario.buscarPorCodigo(c.substring(0, c.length() - 1))).isEmpty();   // un código parcial no coincide
    }

    // ---- devolución ----

    @Test
    void devolverDejaPorInspeccionYConservaElHistorialEnOrden() {
        UnidadEpp u = devuelta();

        assertThat(inventario.movimientos(u.getIdUnidad()).reversed()).extracting(MovimientoUnidad::getTipo)
                .containsExactly(TipoMovimientoUnidad.INGRESO, TipoMovimientoUnidad.ASIGNACION, TipoMovimientoUnidad.DEVOLUCION);
        assertThat(u.getTrabajador()).isNull();
    }

    @Test
    void laUnidadDevueltaNoEsElegidaPorAsignar() {
        UnidadEpp u = devuelta();

        Entrega nueva = entregarA(w2, arnes);

        assertThat(nueva.getSinStock()).isNotEmpty();
        assertThat(nueva.getCodigosAsignados()).isEmpty();
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.POR_INSPECCION);
    }

    @Test
    void soloLasUnidadesDisponiblesSeTomanAlAsignar() {
        // una unidad en cada estado no disponible: ninguna debe salir en una entrega
        UnidadEpp inspeccion = devuelta();
        UnidadEpp autorizar = devuelta2();
        inventario.registrarInspeccion(autorizar.getIdUnidad(), ResultadoInspeccion.APTO_PARA_USO, null, SUP);
        UnidadEpp cuarentena = devuelta2();
        inventario.registrarInspeccion(cuarentena.getIdUnidad(), ResultadoInspeccion.NO_APTO_CUARENTENA, "Dudas", SUP);
        UnidadEpp taller = devuelta2();
        inventario.registrarInspeccion(taller.getIdUnidad(), ResultadoInspeccion.REQUIERE_MANTENIMIENTO, "Desgaste", SUP);
        inventario.enviarAMantenimiento(taller.getIdUnidad(), "Desgaste", null, SUP);
        UnidadEpp baja = devuelta2();
        inventario.registrarInspeccion(baja.getIdUnidad(), ResultadoInspeccion.NO_APTO_BAJA, "Roto", SUP);

        assertThat(List.of(inspeccion, autorizar, cuarentena, taller, baja)).extracting(UnidadEpp::getEstado)
                .containsExactly(EstadoUnidad.POR_INSPECCION, EstadoUnidad.POR_AUTORIZAR, EstadoUnidad.EN_CUARENTENA,
                        EstadoUnidad.EN_MANTENIMIENTO, EstadoUnidad.DE_BAJA);
        Entrega nueva = entregarA(w2, arnes);
        assertThat(nueva.getCodigosAsignados()).isEmpty();
        assertThat(nueva.getSinStock()).isNotEmpty();

        inventario.autorizarRetorno(autorizar.getIdUnidad(), null, SUP);   // solo una disponible
        Entrega conStock = entregarA(datos.trabajador("90000033", LocalDate.now().minusDays(60)), arnes);
        assertThat(conStock.getCodigosAsignados()).containsExactly(autorizar.getCodigo());
    }

    /** Otra unidad devuelta del mismo EPP (w1 recibe una nueva y la devuelve). */
    private UnidadEpp devuelta2() {
        inventario.ingresar(arnes.getIdEpp(), 1, SUP);
        Trabajador t = datos.trabajador("9100%04d".formatted(++contadorDni), LocalDate.now().minusDays(60));
        Entrega e = entregarA(t, arnes);
        UnidadEpp u = inventario.buscarPorCodigo(e.getCodigosAsignados().getFirst()).orElseThrow();
        inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, MotivoDevolucion.REEMPLAZO, null, SUP);
        return u;
    }

    @Test
    void inservibleVaDirectoABajaConMovimientoBaja() {
        inventario.ingresar(arnes.getIdEpp(), 1, SUP);
        UnidadEpp u = inventario.buscarPorCodigo(entregarA(w1, arnes).getCodigosAsignados().getFirst()).orElseThrow();
        inventario.devolver(u.getCodigo(), CondicionEpp.INSERVIBLE, MotivoDevolucion.DETERIORO, "Roto", SUP);

        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.DE_BAJA);
        assertThat(inventario.movimientos(u.getIdUnidad())).extracting(MovimientoUnidad::getTipo).contains(TipoMovimientoUnidad.BAJA);
    }

    @Test
    void reparableConMaximoDeReparacionesVaABaja() {
        inventario.ingresar(arnes.getIdEpp(), 1, SUP);
        UnidadEpp u = inventario.buscarPorCodigo(entregarA(w1, arnes).getCodigosAsignados().getFirst()).orElseThrow();
        u.setVecesReparado(UnidadEpp.MAX_REPARACIONES);
        inventario.devolver(u.getCodigo(), CondicionEpp.REPARABLE, MotivoDevolucion.DETERIORO, null, SUP);

        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.DE_BAJA);
    }

    // ---- inspección ----

    @Test
    void cadaResultadoDeInspeccionLlevaASuDestino() {
        for (var par : List.of(
                java.util.Map.entry(ResultadoInspeccion.APTO_PARA_USO, EstadoUnidad.POR_AUTORIZAR),
                java.util.Map.entry(ResultadoInspeccion.REQUIERE_MANTENIMIENTO, EstadoUnidad.POR_MANTENIMIENTO),
                java.util.Map.entry(ResultadoInspeccion.NO_APTO_CUARENTENA, EstadoUnidad.EN_CUARENTENA),
                java.util.Map.entry(ResultadoInspeccion.NO_APTO_BAJA, EstadoUnidad.DE_BAJA))) {
            UnidadEpp u = devuelta2();
            inventario.registrarInspeccion(u.getIdUnidad(), par.getKey(), "Observación", SUP);
            assertThat(u.getEstado()).as(par.getKey().name()).isEqualTo(par.getValue());
            assertThat(inventario.inspecciones(u.getIdUnidad())).hasSize(1).first()
                    .satisfies(i -> {
                        assertThat(i.getInspector()).isEqualTo(SUP);
                        assertThat(i.getResultado()).isEqualTo(par.getKey());
                        assertThat(i.getFecha()).isNotNull();
                        assertThat(i.isAutorizacionPendiente()).isEqualTo(par.getKey() == ResultadoInspeccion.APTO_PARA_USO);
                    });
        }
    }

    @Test
    void lasObservacionesSonObligatoriasSalvoEnAptoParaUso() {
        UnidadEpp u = devuelta();
        for (ResultadoInspeccion r : List.of(ResultadoInspeccion.REQUIERE_MANTENIMIENTO, ResultadoInspeccion.NO_APTO_CUARENTENA,
                ResultadoInspeccion.NO_APTO_BAJA)) {
            assertThatThrownBy(() -> inventario.registrarInspeccion(u.getIdUnidad(), r, "  ", SUP))
                    .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("observaciones");
        }
        assertThatThrownBy(() -> inventario.registrarInspeccion(u.getIdUnidad(), ResultadoInspeccion.APTO_PARA_USO, "x".repeat(451), SUP))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("450");
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.POR_INSPECCION);   // nada cambió
        inventario.registrarInspeccion(u.getIdUnidad(), ResultadoInspeccion.APTO_PARA_USO, null, SUP);
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.POR_AUTORIZAR);
    }

    @Test
    void soloSePuedeInspeccionarDesdePorInspeccion() {
        inventario.ingresar(arnes.getIdEpp(), 1, SUP);
        UnidadEpp disponible = inventario.unidades(arnes.getIdEpp(), EstadoUnidad.DISPONIBLE).getFirst();
        assertThatThrownBy(() -> inventario.registrarInspeccion(disponible.getIdUnidad(), ResultadoInspeccion.APTO_PARA_USO, null, SUP))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("no puede inspeccionarse");
    }

    // ---- autorización ----

    @Test
    void autorizarVuelveADisponibleSoloDesdePorAutorizarYRegistraQuienYCuando() {
        UnidadEpp u = devuelta();
        assertThatThrownBy(() -> inventario.autorizarRetorno(u.getIdUnidad(), null, SUP))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("no puede autorizarse");
        inventario.registrarInspeccion(u.getIdUnidad(), ResultadoInspeccion.APTO_PARA_USO, null, SUP);
        assertThat(inventario.inspecciones(u.getIdUnidad()).getFirst().isAutorizacionPendiente()).isTrue();

        inventario.autorizarRetorno(u.getIdUnidad(), "Revisado por SST de campo", "otro-sup");

        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.DISPONIBLE);
        MovimientoUnidad m = inventario.movimientos(u.getIdUnidad()).stream()
                .filter(x -> x.getTipo() == TipoMovimientoUnidad.AUTORIZADO_RETORNO).findFirst().orElseThrow();
        assertThat(m.getUsuario()).isEqualTo("otro-sup");
        assertThat(m.getFecha()).isNotNull();
        assertThat(m.getObservacion()).isEqualTo("Revisado por SST de campo");
        assertThat(inventario.inspecciones(u.getIdUnidad()).getFirst().isAutorizacionPendiente()).isFalse();
        // y no se puede autorizar dos veces
        assertThatThrownBy(() -> inventario.autorizarRetorno(u.getIdUnidad(), null, SUP)).isInstanceOf(ReglaNegocioException.class);
    }

    @Test
    void rechazarRetornoExigeMotivoYDestinoValido() {
        UnidadEpp u = devuelta();
        inventario.registrarInspeccion(u.getIdUnidad(), ResultadoInspeccion.APTO_PARA_USO, null, SUP);

        assertThatThrownBy(() -> inventario.rechazarRetorno(u.getIdUnidad(), EstadoUnidad.EN_CUARENTENA, " ", SUP))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("motivo");
        assertThatThrownBy(() -> inventario.rechazarRetorno(u.getIdUnidad(), EstadoUnidad.DISPONIBLE, "No", SUP))
                .isInstanceOf(ReglaNegocioException.class);
        inventario.rechazarRetorno(u.getIdUnidad(), EstadoUnidad.EN_CUARENTENA, "Dudas sobre las costuras", SUP);
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.EN_CUARENTENA);
        assertThat(inventario.movimientos(u.getIdUnidad())).extracting(MovimientoUnidad::getTipo)
                .contains(TipoMovimientoUnidad.RETORNO_RECHAZADO);
    }

    @Test
    void resolverCuarentenaLlevaAMantenimientoBajaOAutorizacionConMotivo() {
        for (EstadoUnidad destino : List.of(EstadoUnidad.POR_MANTENIMIENTO, EstadoUnidad.DE_BAJA, EstadoUnidad.POR_AUTORIZAR)) {
            UnidadEpp u = devuelta2();
            inventario.registrarInspeccion(u.getIdUnidad(), ResultadoInspeccion.NO_APTO_CUARENTENA, "Dudas", SUP);
            assertThatThrownBy(() -> inventario.resolverCuarentena(u.getIdUnidad(), destino, "", SUP))
                    .isInstanceOf(ReglaNegocioException.class);
            inventario.resolverCuarentena(u.getIdUnidad(), destino, "Decisión del responsable", SUP);
            assertThat(u.getEstado()).isEqualTo(destino);
        }
        UnidadEpp u = devuelta2();
        inventario.registrarInspeccion(u.getIdUnidad(), ResultadoInspeccion.NO_APTO_CUARENTENA, "Dudas", SUP);
        assertThatThrownBy(() -> inventario.resolverCuarentena(u.getIdUnidad(), EstadoUnidad.DISPONIBLE, "Directo", SUP))
                .isInstanceOf(ReglaNegocioException.class);
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.EN_CUARENTENA);
    }

    // ---- mantenimiento ----

    @Test
    void mantenimientoRegistraDiagnosticoResponsableYCostoYLaReparadaQuedaPorAutorizar() {
        UnidadEpp u = enMantenimiento();
        RegistroMantenimiento abierto = inventario.mantenimientos(u.getIdUnidad()).getFirst();
        assertThat(abierto.getEstado()).isEqualTo(EstadoRegistroMantenimiento.ABIERTO);
        assertThat(abierto.getDiagnostico()).isEqualTo("Correa floja");
        assertThat(abierto.getResponsable()).isEqualTo(SUP);

        inventario.cerrarMantenimiento(u.getIdUnidad(), ResultadoMantenimiento.REPARADO, "Cambio de correa",
                new BigDecimal("12.345"), "Sin novedades", "taller-sup");

        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.POR_AUTORIZAR);   // reparada no es disponible
        assertThat(u.getVecesReparado()).isEqualTo(1);
        RegistroMantenimiento cerrado = inventario.mantenimientos(u.getIdUnidad()).getFirst();
        assertThat(cerrado.getEstado()).isEqualTo(EstadoRegistroMantenimiento.CERRADO);
        assertThat(cerrado.getCosto()).isEqualByComparingTo("12.35");
        assertThat(cerrado.getResultado()).isEqualTo(ResultadoMantenimiento.REPARADO);
        assertThat(cerrado.getFechaFin()).isNotNull();
        assertThat(cerrado.getCerradoPor()).isEqualTo("taller-sup");
    }

    @Test
    void noReparableVaABaja() {
        UnidadEpp u = enMantenimiento();
        inventario.cerrarMantenimiento(u.getIdUnidad(), ResultadoMantenimiento.NO_REPARABLE, null, null, null, SUP);

        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.DE_BAJA);
        assertThat(u.getVecesReparado()).isZero();
    }

    @Test
    void mantenimientoExigeDiagnosticoCostoNoNegativoYEstadoCorrecto() {
        UnidadEpp u = devuelta();
        inventario.registrarInspeccion(u.getIdUnidad(), ResultadoInspeccion.REQUIERE_MANTENIMIENTO, "Desgaste", SUP);
        assertThatThrownBy(() -> inventario.enviarAMantenimiento(u.getIdUnidad(), "  ", null, SUP))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("diagnóstico");
        assertThatThrownBy(() -> inventario.enviarAMantenimiento(u.getIdUnidad(), "x".repeat(451), null, SUP))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("450");
        // no se puede cerrar lo que aún no está en el taller
        assertThatThrownBy(() -> inventario.cerrarMantenimiento(u.getIdUnidad(), ResultadoMantenimiento.REPARADO, null, null, null, SUP))
                .isInstanceOf(ReglaNegocioException.class);
        inventario.enviarAMantenimiento(u.getIdUnidad(), "Desgaste", null, SUP);

        assertThatThrownBy(() -> inventario.cerrarMantenimiento(u.getIdUnidad(), ResultadoMantenimiento.REPARADO, null,
                new BigDecimal("-0.01"), null, SUP)).isInstanceOf(ReglaNegocioException.class).hasMessageContaining("negativo");
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.EN_MANTENIMIENTO);
        assertThat(inventario.mantenimientos(u.getIdUnidad()).getFirst().getEstado()).isEqualTo(EstadoRegistroMantenimiento.ABIERTO);
    }

    @Test
    void elIndicadorDeCostosSumaSoloLosCerradosConCosto() {
        BigDecimal base = inventario.indicadorMantenimiento().costoTotal();
        long conCostoBase = inventario.indicadorMantenimiento().cerradosConCosto();
        arnes.setCostoUnitario(new BigDecimal("100.00"));

        UnidadEpp a = enMantenimiento();
        UnidadEpp b = devuelta2();
        inventario.registrarInspeccion(b.getIdUnidad(), ResultadoInspeccion.REQUIERE_MANTENIMIENTO, "Desgaste", SUP);
        inventario.enviarAMantenimiento(b.getIdUnidad(), "Desgaste", null, SUP);
        UnidadEpp c = devuelta2();
        inventario.registrarInspeccion(c.getIdUnidad(), ResultadoInspeccion.REQUIERE_MANTENIMIENTO, "Desgaste", SUP);
        inventario.enviarAMantenimiento(c.getIdUnidad(), "Desgaste", null, SUP);   // queda abierto: no suma

        inventario.cerrarMantenimiento(a.getIdUnidad(), ResultadoMantenimiento.REPARADO, null, new BigDecimal("10.00"), null, SUP);
        inventario.cerrarMantenimiento(b.getIdUnidad(), ResultadoMantenimiento.REPARADO, null, null, null, SUP);   // sin costo: no suma

        var ind = inventario.indicadorMantenimiento();
        assertThat(ind.costoTotal()).isEqualByComparingTo(base.add(new BigDecimal("10.00")));
        assertThat(ind.cerradosConCosto()).isEqualTo(conCostoBase + 1);
        var fila = ind.comparacion().stream().filter(f -> f.epp().getIdEpp().equals(arnes.getIdEpp())).findFirst().orElseThrow();
        assertThat(fila.reparados()).isEqualTo(1);
        assertThat(fila.costoMantenimiento()).isEqualByComparingTo("10.00");
        assertThat(fila.costoReposicion()).isEqualByComparingTo("100.00");
        assertThat(fila.diferencia()).isEqualByComparingTo("90.00");
    }

    @Test
    void sinCostoUnitarioNoHayComparacionParaEseEpp() {
        UnidadEpp a = enMantenimiento();
        inventario.cerrarMantenimiento(a.getIdUnidad(), ResultadoMantenimiento.REPARADO, null, new BigDecimal("10.00"), null, SUP);

        assertThat(inventario.indicadorMantenimiento().comparacion())
                .noneMatch(f -> f.epp().getIdEpp().equals(arnes.getIdEpp()));
    }

    // ---- desechables ----

    @Test
    void elConsumibleNoAdmiteDevolucionNiInspeccion() {
        Epp desechable = epp("Mascarilla de flujo", ClaseEpp.DESECHABLE);
        // Cambio de regla pedido por el profesor: el consumible se controla por lotes y ya no crea unidades.
        // La unidad CONSUMIDO se crea a mano porque los datos antiguos pueden tenerlas.
        inventario.ingresar(desechable.getIdEpp(), 1, SUP);
        Entrega entrega = entregarA(w1, desechable);
        assertThat(entrega.getCodigosAsignados()).isEmpty();
        assertThat(entrega.getSinStock()).isEmpty();
        UnidadEpp nueva = new UnidadEpp("EPP-%03d-HEREDADA".formatted(desechable.getIdEpp()), desechable);
        nueva.setEstado(EstadoUnidad.CONSUMIDO);
        UnidadEpp u = unidadRepository.save(nueva);
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.CONSUMIDO);

        assertThatThrownBy(() -> inventario.devolver(u.getCodigo(), CondicionEpp.BUENO, MotivoDevolucion.REEMPLAZO, null, SUP))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("no corresponde devolverlo");
        assertThatThrownBy(() -> inventario.registrarInspeccion(u.getIdUnidad(), ResultadoInspeccion.APTO_PARA_USO, null, SUP))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("no pasa por inspección");
        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.CONSUMIDO);
    }

    // ---- auditoría ----

    @Test
    void cadaOperacionRegistraAuditoriaYLaCadenaSigueIntegra() {
        UnidadEpp u = devuelta();
        inventario.registrarInspeccion(u.getIdUnidad(), ResultadoInspeccion.NO_APTO_CUARENTENA, "Dudas", SUP);
        inventario.resolverCuarentena(u.getIdUnidad(), EstadoUnidad.POR_MANTENIMIENTO, "Se puede reparar", SUP);
        inventario.enviarAMantenimiento(u.getIdUnidad(), "Costura", null, SUP);
        inventario.cerrarMantenimiento(u.getIdUnidad(), ResultadoMantenimiento.REPARADO, null, null, null, SUP);
        inventario.rechazarRetorno(u.getIdUnidad(), EstadoUnidad.EN_CUARENTENA, "Más dudas", SUP);
        inventario.resolverCuarentena(u.getIdUnidad(), EstadoUnidad.POR_AUTORIZAR, "Revisado", SUP);
        inventario.autorizarRetorno(u.getIdUnidad(), null, SUP);

        assertThat(acciones(u)).containsExactly("INVENTARIO_DEVOLUCION", "INVENTARIO_INSPECCION",
                "INVENTARIO_CUARENTENA_RESUELTA", "INVENTARIO_MANTENIMIENTO_ENVIO", "INVENTARIO_MANTENIMIENTO_CIERRE",
                "INVENTARIO_RETORNO_RECHAZADO", "INVENTARIO_CUARENTENA_RESUELTA", "INVENTARIO_RETORNO_AUTORIZADO");
        // sin datos personales: solo el código de la unidad
        registros.findAllByOrderByIdRegistroAsc().stream().filter(r -> r.getAccion().startsWith("INVENTARIO_"))
                .forEach(r -> assertThat(r.getDetalle()).doesNotContain(w1.getNombres()).doesNotContain(w1.getDni()));
        assertThat(auditoria.verificar().integra()).isTrue();
    }

    @Test
    void laBajaDesdeMantenimientoPendienteTambienSeAudita() {
        UnidadEpp u = devuelta();
        inventario.registrarInspeccion(u.getIdUnidad(), ResultadoInspeccion.REQUIERE_MANTENIMIENTO, "No conviene", SUP);
        inventario.darDeBaja(u.getIdUnidad(), "El costo supera la reposición", SUP);

        assertThat(u.getEstado()).isEqualTo(EstadoUnidad.DE_BAJA);
        assertThat(acciones(u)).contains("INVENTARIO_BAJA");
        assertThat(auditoria.verificar().integra()).isTrue();
    }
}
