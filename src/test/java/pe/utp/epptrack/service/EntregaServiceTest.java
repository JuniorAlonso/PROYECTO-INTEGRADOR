package pe.utp.epptrack.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.DatosPrueba;
import pe.utp.epptrack.dto.EntregaForm;
import pe.utp.epptrack.model.*;
import pe.utp.epptrack.repository.CorreccionEntregaRepository;
import pe.utp.epptrack.repository.DetalleEntregaRepository;
import pe.utp.epptrack.repository.HistorialEppRepository;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EntregaServiceTest {

    @Autowired DatosPrueba datos;
    @Autowired EntregaService entregaService;
    @Autowired DetalleEntregaRepository detalleRepository;
    @Autowired HistorialEppRepository historialRepository;
    @Autowired CorreccionEntregaRepository correccionRepository;
    @Autowired TrabajadorService trabajadorService;

    Trabajador trabajador;

    @BeforeEach
    void preparar() {
        datos.cargar();
        trabajador = datos.trabajador("10000001", LocalDate.now().minusDays(60));
    }

    @Test
    void calculaFechaDeRenovacionSegunVidaUtil() {   // RF-06, RN-03
        LocalDate fecha = LocalDate.now().minusDays(5);
        Entrega entrega = entregaService.registrar(datos.formulario(trabajador, fecha, datos.respirador), "sup-test");

        DetalleEntrega detalle = entrega.getDetalles().getFirst();
        assertThat(detalle.getFechaRenovacion()).isEqualTo(fecha.plusDays(365));
        assertThat(detalle.getEstadoVigencia()).isEqualTo(EstadoVigencia.VIGENTE);
        assertThat(entrega.getUsuario().getNombreUsuario()).isEqualTo("sup-test");   // RN-02
    }

    @Test
    void entregaAntiguaQuedaVencidaOPorVencer() {
        Entrega entrega = entregaService.registrar(
                datos.formulario(trabajador, LocalDate.now().minusDays(40), datos.guantes, datos.respirador), "sup-test");

        assertThat(entrega.getDetalles()).extracting(DetalleEntrega::getEstadoVigencia)
                .containsExactly(EstadoVigencia.VENCIDO, EstadoVigencia.VIGENTE);

        Entrega reciente = entregaService.registrar(
                datos.formulario(trabajador, LocalDate.now().minusDays(20), datos.guantes), "sup-test");
        assertThat(reciente.getDetalles().getFirst().getEstadoVigencia()).isEqualTo(EstadoVigencia.POR_VENCER);
    }

    @Test
    void bloqueaEntregaDuplicadaSinJustificacion() {   // RF-07
        entregaService.registrar(datos.formulario(trabajador, LocalDate.now(), datos.respirador), "sup-test");

        EntregaForm otra = datos.formulario(trabajador, LocalDate.now(), datos.respirador, datos.casco);
        assertThat(entregaService.duplicadosSinJustificar(otra)).containsExactly(datos.respirador.getIdEpp());
        assertThatThrownBy(() -> entregaService.registrar(otra, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class);
    }

    @Test
    void duplicadoJustificadoReemplazaElAnterior() {   // RF-07, RN-05
        Entrega primera = entregaService.registrar(datos.formulario(trabajador, LocalDate.now(), datos.respirador), "sup-test");

        EntregaForm otra = datos.formulario(trabajador, LocalDate.now(), datos.respirador);
        otra.getItems().getFirst().setJustificacion("Se rompió la válvula");
        Entrega segunda = entregaService.registrar(otra, "sup-test");

        DetalleEntrega anterior = detalleRepository.findById(primera.getDetalles().getFirst().getIdDetalle()).orElseThrow();
        assertThat(anterior.getEstadoVigencia()).isEqualTo(EstadoVigencia.RENOVADO);
        assertThat(segunda.getDetalles().getFirst().getJustificacionDuplicado()).isEqualTo("Se rompió la válvula");
        assertThat(historialRepository.findByTrabajadorIdTrabajadorOrderByFechaRegistroDescIdHistorialDesc(trabajador.getIdTrabajador()))
                .extracting(HistorialEpp::getTipoMovimiento)
                .contains(HistorialEpp.ENTREGA, HistorialEpp.RENOVADO, HistorialEpp.ENTREGA_DUPLICADA);
    }

    @Test
    void renovarUnEppVencidoNoEsDuplicado() {
        entregaService.registrar(datos.formulario(trabajador, LocalDate.now().minusDays(40), datos.guantes), "sup-test");
        EntregaForm renovacion = datos.formulario(trabajador, LocalDate.now(), datos.guantes);

        assertThat(entregaService.duplicadosSinJustificar(renovacion)).isEmpty();
        Entrega entrega = entregaService.registrar(renovacion, "sup-test");
        assertThat(entrega.getDetalles().getFirst().isDuplicadoJustificado()).isFalse();
    }

    @Test
    void anularNoBorraYDejaCorreccion() {   // RNF-03, RN-05, RF-13
        Entrega entrega = entregaService.registrar(datos.formulario(trabajador, LocalDate.now(), datos.respirador), "sup-test");
        Integer idDetalle = entrega.getDetalles().getFirst().getIdDetalle();

        entregaService.anularDetalle(idDetalle, "Se registró al trabajador equivocado", "sup-test");

        assertThat(detalleRepository.findById(idDetalle)).get()
                .extracting(DetalleEntrega::getEstadoVigencia).isEqualTo(EstadoVigencia.ANULADO);
        List<CorreccionEntrega> correcciones = correccionRepository
                .findByDetalleEntregaTrabajadorIdTrabajadorOrderByFechaCorreccionDesc(trabajador.getIdTrabajador());
        assertThat(correcciones).hasSize(1);
        assertThat(correcciones.getFirst().getValorAnterior()).contains("estado=VIGENTE");
        assertThatThrownBy(() -> entregaService.anularDetalle(idDetalle, "otra vez", "sup-test"))
                .isInstanceOf(ReglaNegocioException.class);
    }

    @Test
    void noEntregaATrabajadorCesado() {
        trabajadorService.registrarCese(trabajador.getIdTrabajador(), LocalDate.now());
        assertThatThrownBy(() -> entregaService.registrar(datos.formulario(trabajador, LocalDate.now(), datos.casco), "sup-test"))
                .isInstanceOf(ReglaNegocioException.class)
                .hasMessageContaining("cesado");
    }

    @Test
    void exigeAlMenosUnEpp() {
        EntregaForm vacio = datos.formulario(trabajador, LocalDate.now());
        assertThatThrownBy(() -> entregaService.registrar(vacio, "sup-test")).isInstanceOf(ReglaNegocioException.class);
    }

    @Test
    void rechazaElMismoEppRepetidoEnUnaEntrega() {
        EntregaForm form = datos.formulario(trabajador, LocalDate.now(), datos.respirador, datos.respirador);
        assertThatThrownBy(() -> entregaService.registrar(form, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("repetirse");
    }

    @Test
    void rechazaFechaDeEntregaFutura() {
        EntregaForm form = datos.formulario(trabajador, LocalDate.now().plusDays(1), datos.respirador);
        assertThatThrownBy(() -> entregaService.registrar(form, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("futura");
    }

    @Test
    void cantidadInvalidaNoDejaNingunRegistroParcial() {
        EntregaForm form = datos.formulario(trabajador, LocalDate.now(), datos.respirador);
        form.getItems().getFirst().setCantidad(0);
        assertThatThrownBy(() -> entregaService.registrar(form, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class);
        assertThat(detalleRepository.findAll()).isEmpty();
    }

    @Test
    void anularLaEntregaQueReemplazoRestauraLaAnterior() {
        Trabajador antiguo = datos.trabajador("10000002", LocalDate.now().minusDays(200));
        entregaService.registrar(datos.formulario(antiguo, LocalDate.now().minusDays(100), datos.respirador), "sup-test");
        EntregaForm nueva = datos.formulario(antiguo, LocalDate.now(), datos.respirador);
        nueva.getItems().getFirst().setJustificacion("Se perdió");
        Entrega segunda = entregaService.registrar(nueva, "sup-test");
        DetalleEntrega anterior = detalleRepository.findAll().stream()
                .filter(d -> d.getEstadoVigencia() == EstadoVigencia.RENOVADO).findFirst().orElseThrow();

        entregaService.anularDetalle(segunda.getDetalles().getFirst().getIdDetalle(), "Error de registro", "sup-test");

        assertThat(anterior.getEstadoVigencia()).isEqualTo(EstadoVigencia.VIGENTE);
        assertThat(entregaService.enUsoPorEpp(antiguo.getIdTrabajador())).containsKey(datos.respirador.getIdEpp());
        assertThat(historialRepository.findAll()).extracting(HistorialEpp::getTipoMovimiento)
                .contains(HistorialEpp.RESTAURADO);
    }

    @Test
    void anularUnaEntregaSinAnteriorNoRestauraNada() {
        Entrega e = entregaService.registrar(datos.formulario(trabajador, LocalDate.now(), datos.respirador), "sup-test");
        entregaService.anularDetalle(e.getDetalles().getFirst().getIdDetalle(), "Error", "sup-test");
        assertThat(entregaService.enUsoPorEpp(trabajador.getIdTrabajador())).isEmpty();
    }

    @Test
    void rechazaJustificacionYMotivoDemasiadoLargos() {
        String largo = "x".repeat(451);
        EntregaForm form = datos.formulario(trabajador, LocalDate.now(), datos.respirador);
        form.getItems().getFirst().setJustificacion(largo);
        assertThatThrownBy(() -> entregaService.registrar(form, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("450");
        Entrega e = entregaService.registrar(datos.formulario(trabajador, LocalDate.now(), datos.respirador), "sup-test");
        Integer id = e.getDetalles().getFirst().getIdDetalle();
        assertThatThrownBy(() -> entregaService.anularDetalle(id, largo, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("450");
    }

    @Test
    void rechazaEntregaAnteriorAlIngresoDelTrabajador() {
        EntregaForm form = datos.formulario(trabajador, LocalDate.now().minusDays(61), datos.respirador);   // ingresó hace 60 días
        assertThatThrownBy(() -> entregaService.registrar(form, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("anterior al ingreso");
        assertThat(detalleRepository.findAll()).isEmpty();
    }

    @Test
    void rechazaUnEppDesactivado() {
        datos.casco.setEstado(Epp.INACTIVO);
        EntregaForm form = datos.formulario(trabajador, LocalDate.now(), datos.casco);
        assertThatThrownBy(() -> entregaService.registrar(form, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("desactivado");
    }
}
