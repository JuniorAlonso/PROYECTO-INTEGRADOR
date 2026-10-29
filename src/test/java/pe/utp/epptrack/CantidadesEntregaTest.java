package pe.utp.epptrack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.dto.EntregaForm;
import pe.utp.epptrack.dto.EvaluacionDotacion;
import pe.utp.epptrack.dto.ItemDotacion;
import pe.utp.epptrack.dto.ItemEntregaForm;
import pe.utp.epptrack.model.*;
import pe.utp.epptrack.repository.DetalleEntregaRepository;
import pe.utp.epptrack.repository.EntregaRepository;
import pe.utp.epptrack.service.*;
import pe.utp.epptrack.service.EntregaService.Decision;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Cantidades de la entrega frente a la matriz (respirador x1, guantes x2): parcial, exacta, exceso, complemento (RN-08, RF-07, RN-06). */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CantidadesEntregaTest {

    @Autowired DatosPrueba datos;
    @Autowired EntregaService entregaService;
    @Autowired DotacionService dotacionService;
    @Autowired MotorDotacionService motor;
    @Autowired TrabajadorService trabajadorService;
    @Autowired DetalleEntregaRepository detalleRepository;
    @Autowired EntregaRepository entregaRepository;

    Trabajador t;

    @BeforeEach
    void preparar() {
        datos.cargar();
        t = datos.trabajador("90000001", LocalDate.now().minusDays(60));
    }

    private EntregaForm form(LocalDate fecha, Epp epp, int cantidad, String justificacion) {
        EntregaForm f = new EntregaForm();
        f.setIdTrabajador(t.getIdTrabajador());
        f.setFechaEntrega(fecha);
        f.setMotivo(MotivoEntrega.INGRESO);
        ItemEntregaForm item = new ItemEntregaForm(epp.getIdEpp(), true, cantidad);
        item.setJustificacion(justificacion);
        f.setItems(List.of(item));
        return f;
    }

    private EntregaForm form(Epp epp, int cantidad, String justificacion) {
        return form(LocalDate.now().minusDays(2), epp, cantidad, justificacion);
    }

    private ItemDotacion item(Epp epp) {
        return dotacionService.estadoDotacion(t).items().stream()
                .filter(i -> i.epp().getIdEpp().equals(epp.getIdEpp())).findFirst().orElseThrow();
    }

    private Decision decision(EntregaForm f) {
        return entregaService.evaluarLineas(f).getFirst().decision();
    }

    @Test
    void cantidadInsuficienteSeRegistraPeroDejaLaDotacionIncompleta() {
        long antes = entregaRepository.count();
        assertThat(decision(form(datos.guantes, 1, null))).isEqualTo(Decision.NORMAL);
        entregaService.registrar(form(datos.guantes, 1, null), "sup-test");

        assertThat(entregaRepository.count()).isEqualTo(antes + 1);
        ItemDotacion g = item(datos.guantes);
        assertThat(g.enPoder()).isEqualTo(1);
        assertThat(g.getPendiente()).isEqualTo(1);
        assertThat(g.isParcial()).isTrue();
        assertThat(g.isCubierto()).isFalse();
        assertThat(dotacionService.estadoDotacion(t).isCompleta()).isFalse();
        var v = motor.evaluar(t).verificaciones().stream()
                .filter(x -> x.epp().getIdEpp().equals(datos.guantes.getIdEpp())).findFirst().orElseThrow();
        assertThat(v.estado()).isEqualTo(EvaluacionDotacion.EstadoItem.PARCIAL);
    }

    @Test
    void cantidadExactaCompletaLaDotacion() {
        entregaService.registrar(form(datos.guantes, 2, null), "sup-test");
        entregaService.registrar(form(datos.respirador, 1, null), "sup-test");

        assertThat(item(datos.guantes).isCubierto()).isTrue();
        assertThat(item(datos.guantes).getPendiente()).isZero();
        assertThat(dotacionService.estadoDotacion(t).isCompleta()).isTrue();
    }

    @Test
    void cantidadExcesivaSinJustificarSeRechazaYNoPersisteNada() {
        EntregaForm f = form(datos.guantes, 3, null);
        assertThat(decision(f)).isEqualTo(Decision.EXCESO);
        assertThat(entregaService.excesosSinJustificar(f)).containsExactly(datos.guantes.getIdEpp());
        long entregas = entregaRepository.count();
        long detalles = detalleRepository.count();

        assertThatThrownBy(() -> entregaService.registrar(f, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("RN-08");
        assertThat(entregaRepository.count()).isEqualTo(entregas);
        assertThat(detalleRepository.count()).isEqualTo(detalles);
    }

    @Test
    void cantidadExcesivaJustificadaSeRegistraConLaJustificacionYLaCantidadExigida() {
        Entrega e = entregaService.registrar(form(datos.guantes, 3, "Trabaja en zona de alto desgaste"), "sup-test");
        DetalleEntrega d = e.getDetalles().getFirst();

        assertThat(d.getCantidad()).isEqualTo(3);
        assertThat(d.getCantidadExigida()).isEqualTo(2);
        assertThat(d.getJustificacionExceso()).isEqualTo("Trabaja en zona de alto desgaste");
        assertThat(d.isExcesoJustificado()).isTrue();
    }

    @Test
    void dosEntregasParcialesSeSumanSinReemplazarse() {
        Entrega primera = entregaService.registrar(form(datos.guantes, 1, null), "sup-test");
        EntregaForm segunda = form(datos.guantes, 1, null);
        assertThat(decision(segunda)).isEqualTo(Decision.COMPLEMENTO);
        assertThat(entregaService.duplicadosSinJustificar(segunda)).isEmpty();
        Entrega e2 = entregaService.registrar(segunda, "sup-test");

        assertThat(item(datos.guantes).enPoder()).isEqualTo(2);
        assertThat(item(datos.guantes).isCubierto()).isTrue();
        assertThat(primera.getDetalles().getFirst().getEstadoVigencia()).isNotEqualTo(EstadoVigencia.RENOVADO);
        assertThat(e2.getDetalles().getFirst().getJustificacionDuplicado()).isNull();
        assertThat(e2.getDetalles().getFirst().getJustificacionExceso()).isNull();
    }

    @Test
    void complementoMayorALoQueFaltaEsExceso() {
        entregaService.registrar(form(datos.guantes, 1, null), "sup-test");
        EntregaForm f = form(datos.guantes, 2, null);

        assertThat(decision(f)).isEqualTo(Decision.EXCESO);
        assertThatThrownBy(() -> entregaService.registrar(f, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("RN-08");
    }

    @Test
    void eppVigenteCompletoRequiereJustificacionYMarcaElAnteriorComoRenovado() {
        Entrega primera = entregaService.registrar(form(datos.respirador, 1, null), "sup-test");
        EntregaForm sin = form(datos.respirador, 1, null);
        assertThat(decision(sin)).isEqualTo(Decision.DUPLICADO);
        assertThat(entregaService.duplicadosSinJustificar(sin)).containsExactly(datos.respirador.getIdEpp());

        Entrega segunda = entregaService.registrar(form(datos.respirador, 1, "Se perdió en el campo"), "sup-test");
        assertThat(primera.getDetalles().getFirst().getEstadoVigencia()).isEqualTo(EstadoVigencia.RENOVADO);
        assertThat(segunda.getDetalles().getFirst().getJustificacionDuplicado()).isEqualTo("Se perdió en el campo");
        // el bloqueo sin justificar va al final: la excepción deja marcada la transacción de la prueba
        assertThatThrownBy(() -> entregaService.registrar(sin, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("justificaci");
    }

    @Test
    void eppVigenteCompletoSinJustificarSeBloqueaYNoPersiste() {
        entregaService.registrar(form(datos.respirador, 1, null), "sup-test");
        long entregas = entregaRepository.count();
        assertThatThrownBy(() -> entregaService.registrar(form(datos.respirador, 1, null), "sup-test"))
                .isInstanceOf(ReglaNegocioException.class);
        assertThat(entregaRepository.count()).isEqualTo(entregas);
    }

    @Test
    void eppVencidoSeReponeSinJustificacionYElVencidoPasaARenovado() {
        Entrega vieja = entregaService.registrar(form(LocalDate.now().minusDays(45), datos.guantes, 2, null), "sup-test");
        assertThat(vieja.getDetalles().getFirst().getEstadoVigencia()).isEqualTo(EstadoVigencia.VENCIDO);

        EntregaForm nueva = form(LocalDate.now().minusDays(1), datos.guantes, 2, null);
        assertThat(decision(nueva)).isEqualTo(Decision.NORMAL);
        assertThat(entregaService.duplicadosSinJustificar(nueva)).isEmpty();
        Entrega e = entregaService.registrar(nueva, "sup-test");

        assertThat(vieja.getDetalles().getFirst().getEstadoVigencia()).isEqualTo(EstadoVigencia.RENOVADO);
        assertThat(e.getDetalles().getFirst().getJustificacionDuplicado()).isNull();
        assertThat(item(datos.guantes).isCubierto()).isTrue();
    }

    @Test
    void eppNoExigidoRequiereJustificacion() {
        EntregaForm sin = form(datos.casco, 1, null);
        assertThat(decision(sin)).isEqualTo(Decision.NO_EXIGIDO);
        assertThat(entregaService.noExigidosSinJustificar(sin)).containsExactly(datos.casco.getIdEpp());

        Entrega e = entregaService.registrar(form(datos.casco, 1, "Tarea puntual en altura"), "sup-test");
        DetalleEntrega d = e.getDetalles().getFirst();
        assertThat(d.getJustificacionNoExigido()).isEqualTo("Tarea puntual en altura");
        assertThat(d.getCantidadExigida()).isEqualTo(0);
        assertThatThrownBy(() -> entregaService.registrar(sin, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class);
    }

    @Test
    void eppNoExigidoSinJustificarCitaLaReglaRn06() {
        long antes = entregaRepository.count();
        assertThatThrownBy(() -> entregaService.registrar(form(datos.casco, 1, null), "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("RN-06");
        assertThat(entregaRepository.count()).isEqualTo(antes);
    }

    @Test
    void noSeEntregaATrabajadorCesado() {
        trabajadorService.registrarCese(t.getIdTrabajador(), LocalDate.now());
        long antes = entregaRepository.count();
        assertThatThrownBy(() -> entregaService.registrar(form(datos.respirador, 1, null), "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("cesado");
        assertThat(entregaRepository.count()).isEqualTo(antes);
    }

    @Test
    void cantidadCeroOCienSeRechaza() {
        long antes = entregaRepository.count();
        assertThatThrownBy(() -> entregaService.registrar(form(datos.guantes, 0, null), "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("entre 1 y 99");
        assertThatThrownBy(() -> entregaService.registrar(form(datos.guantes, 100, "x"), "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("entre 1 y 99");
        assertThatThrownBy(() -> entregaService.registrar(form(datos.guantes, -1, null), "sup-test"))
                .isInstanceOf(ReglaNegocioException.class);
        assertThat(entregaRepository.count()).isEqualTo(antes);
    }

    @Test
    void evaluarLineasDevuelveLaDecisionEsperadaEnCadaCaso() {
        assertThat(decision(form(datos.respirador, 1, null))).isEqualTo(Decision.NORMAL);
        assertThat(decision(form(datos.guantes, 2, null))).isEqualTo(Decision.NORMAL);
        assertThat(decision(form(datos.guantes, 3, null))).isEqualTo(Decision.EXCESO);
        assertThat(decision(form(datos.casco, 1, null))).isEqualTo(Decision.NO_EXIGIDO);
        var l = entregaService.evaluarLineas(form(datos.guantes, 1, null)).getFirst();
        assertThat(l.requerida()).isEqualTo(2);
        assertThat(l.enPoder()).isZero();
        assertThat(l.pendiente()).isEqualTo(2);
        assertThat(l.enMatriz()).isTrue();
        assertThat(l.requiereJustificacion()).isFalse();

        entregaService.registrar(form(datos.guantes, 1, null), "sup-test");
        var c = entregaService.evaluarLineas(form(datos.guantes, 1, null)).getFirst();
        assertThat(c.decision()).isEqualTo(Decision.COMPLEMENTO);
        assertThat(c.enPoder()).isEqualTo(1);
        assertThat(c.pendiente()).isEqualTo(1);
        assertThat(c.requiereJustificacion()).isFalse();

        entregaService.registrar(form(datos.respirador, 1, null), "sup-test");
        var d = entregaService.evaluarLineas(form(datos.respirador, 1, null)).getFirst();
        assertThat(d.decision()).isEqualTo(Decision.DUPLICADO);
        assertThat(d.requiereJustificacion()).isTrue();

        // un EPP fuera de matriz que el trabajador ya tiene también es duplicado
        entregaService.registrar(form(datos.casco, 1, "Justificado"), "sup-test");
        assertThat(decision(form(datos.casco, 1, null))).isEqualTo(Decision.DUPLICADO);
    }
}
