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
        trabajador = datos.trabajador("20000002", LocalDate.now().minusDays(30));
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

        entregaService.registrar(datos.formulario(trabajador, LocalDate.now(), datos.guantes), "sup-test");
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
        entregaService.registrar(datos.formulario(trabajador, LocalDate.now(), datos.respirador, datos.guantes), "sup-test");

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
}
