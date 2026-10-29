package pe.utp.epptrack.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.DatosPrueba;
import pe.utp.epptrack.dto.EstadoDotacion;
import pe.utp.epptrack.dto.ItemDotacion;
import pe.utp.epptrack.model.EstadoTrabajador;
import pe.utp.epptrack.model.Trabajador;
import pe.utp.epptrack.repository.EntregaRepository;
import pe.utp.epptrack.repository.PeriodoLaboralRepository;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DotacionYTrabajadorServiceTest {

    @Autowired DatosPrueba datos;
    @Autowired DotacionService dotacionService;
    @Autowired TrabajadorService trabajadorService;
    @Autowired EntregaService entregaService;
    @Autowired EntregaRepository entregaRepository;
    @Autowired PeriodoLaboralRepository periodoRepository;

    Trabajador trabajador;

    @BeforeEach
    void preparar() {
        datos.cargar();
        trabajador = datos.trabajador("20000002", LocalDate.now().minusDays(60));
    }

    @Test
    void determinaEppSegunLaMatriz() {   // RF-04
        EstadoDotacion estado = dotacionService.estadoDotacion(trabajador);

        assertThat(estado.items()).extracting(i -> i.epp().getNombre())
                .containsExactlyInAnyOrder("Respirador", "Guantes de nitrilo");
        assertThat(estado.items()).filteredOn(i -> i.epp().getNombre().startsWith("Guantes"))
                .extracting(ItemDotacion::cantidadRequerida).containsExactly(2);
        assertThat(estado.isCompleta()).isFalse();
        assertThat(estado.getFaltantes()).hasSize(2);
    }

    @Test
    void dotacionCompletaCuandoTieneTodoVigente() {   // RF-10
        entregaService.registrar(datos.formulario(trabajador, LocalDate.now(), datos.respirador), "sup-test");
        assertThat(dotacionService.estadoDotacion(trabajador).getFaltantes())
                .extracting(i -> i.epp().getNombre()).containsExactly("Guantes de nitrilo");

        // La matriz exige 2 pares de guantes: una entrega de 1 todavía no cubre (entrega parcial).
        entregaService.registrar(datos.formulario(trabajador, LocalDate.now(), datos.guantes), "sup-test");
        assertThat(dotacionService.estadoDotacion(trabajador).isCompleta()).isFalse();

        var completar = datos.formulario(trabajador, LocalDate.now(), datos.guantes);
        completar.getItems().getFirst().setCantidad(2);
        completar.getItems().getFirst().setJustificacion("Se completa la cantidad exigida por la matriz");
        entregaService.registrar(completar, "sup-test");
        EstadoDotacion estado = dotacionService.estadoDotacion(trabajador);
        assertThat(estado.isCompleta()).isTrue();
        assertThat(estado.getPorcentaje()).isEqualTo(100);
    }

    @Test
    void unEppVencidoDejaLaDotacionIncompleta() {
        entregaService.registrar(datos.formulario(trabajador, LocalDate.now(), datos.respirador), "sup-test");
        entregaService.registrar(datos.formulario(trabajador, LocalDate.now().minusDays(31), datos.guantes), "sup-test");

        assertThat(dotacionService.estadoDotacion(trabajador).isCompleta()).isFalse();
    }

    @Test
    void calculoMasivoCoincideConElIndividual() {
        datos.trabajador("20000003", LocalDate.now());
        var form = datos.formulario(trabajador, LocalDate.now(), datos.respirador, datos.guantes);
        form.getItems().get(1).setCantidad(2);   // la matriz exige 2 pares de guantes
        entregaService.registrar(form, "sup-test");

        var todos = dotacionService.estadoDotacionTodos(null);
        assertThat(todos).hasSize(2);
        assertThat(todos).filteredOn(e -> e.trabajador().getDni().equals("20000002"))
                .allMatch(EstadoDotacion::isCompleta);
        assertThat(dotacionService.cumplimientoPorSede(todos).getFirst().getPorcentaje()).isEqualTo(50);
    }

    @Test
    void rechazaDniRepetido() {
        assertThatThrownBy(() -> datos.trabajador("20000002", LocalDate.now()))
                .isInstanceOf(ReglaNegocioException.class)
                .hasMessageContaining("reingreso");
    }

    @Test
    void reingresoConservaElHistorial() {   // RN-04
        entregaService.registrar(datos.formulario(trabajador, LocalDate.now().minusDays(20), datos.respirador), "sup-test");
        trabajadorService.registrarCese(trabajador.getIdTrabajador(), LocalDate.now().minusDays(10));
        assertThat(trabajador.getEstado()).isEqualTo(EstadoTrabajador.CESADO);
        assertThat(dotacionService.estadoDotacion(trabajador).actividades()).isEmpty();

        trabajadorService.registrarReingreso(trabajador.getIdTrabajador(), "Campaña nueva", LocalDate.now(),
                null, datos.actividad.getIdActividad());

        assertThat(trabajador.getEstado()).isEqualTo(EstadoTrabajador.REINCORPORADO);
        assertThat(periodoRepository.findByTrabajadorIdTrabajadorOrderByFechaIngresoDesc(trabajador.getIdTrabajador()))
                .hasSize(2);
        assertThat(entregaRepository.findByTrabajadorIdTrabajadorOrderByFechaEntregaDescIdEntregaDesc(trabajador.getIdTrabajador()))
                .hasSize(1);
        // El respirador entregado antes del cese sigue vigente, así que solo faltan los guantes
        assertThat(dotacionService.estadoDotacion(trabajador).getFaltantes())
                .extracting(i -> i.epp().getNombre()).containsExactly("Guantes de nitrilo");
    }

    @Test
    void ceseNoPuedeSerAnteriorAlIngresoNiFuturo() {
        Integer id = trabajador.getIdTrabajador();
        assertThatThrownBy(() -> trabajadorService.registrarCese(id, LocalDate.now().minusDays(61)))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("anterior al ingreso");
        assertThatThrownBy(() -> trabajadorService.registrarCese(id, LocalDate.now().plusDays(1)))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("futura");
        assertThat(trabajador.getEstado()).isEqualTo(EstadoTrabajador.ACTIVO);
    }

    @Test
    void reingresoNoPuedeSerAnteriorAlUltimoCese() {
        Integer id = trabajador.getIdTrabajador();
        trabajadorService.registrarCese(id, LocalDate.now().minusDays(5));
        assertThatThrownBy(() -> trabajadorService.registrarReingreso(id, "Otra", LocalDate.now().minusDays(6), null, null))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("último cese");
        assertThat(trabajador.getEstado()).isEqualTo(EstadoTrabajador.CESADO);
    }

    @Test
    void unEppDesactivadoYaNoSeExigeEnLaDotacion() {
        entregaService.registrar(datos.formulario(trabajador, LocalDate.now(), datos.respirador), "sup-test");
        assertThat(dotacionService.estadoDotacion(trabajador).isCompleta()).isFalse();   // faltan guantes

        datos.guantes.setEstado(pe.utp.epptrack.model.Epp.INACTIVO);

        assertThat(dotacionService.estadoDotacion(trabajador).isCompleta()).isTrue();
    }

    @Test
    void registrarExigeActividadYNormalizaLosNombres() {
        Trabajador t = new Trabajador();
        t.setDni(" 20000050 ");
        t.setApellidos("  de   la   Cruz ");
        t.setNombres(" Ana  María ");
        t.setPuesto(datos.puesto);
        t.setSede(datos.sede);
        assertThatThrownBy(() -> trabajadorService.registrar(t, null, "C1", LocalDate.now()))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("actividad");

        Trabajador guardado = trabajadorService.registrar(t, datos.actividad.getIdActividad(), "C1", LocalDate.now());
        assertThat(guardado.getDni()).isEqualTo("20000050");
        assertThat(guardado.getApellidos()).isEqualTo("de la Cruz");
        assertThat(guardado.getNombres()).isEqualTo("Ana María");
        assertThat(trabajadorService.buscarPorDni("20000050")).isPresent();
    }
}
