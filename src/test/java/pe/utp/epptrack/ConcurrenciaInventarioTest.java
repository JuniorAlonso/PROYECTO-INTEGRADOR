package pe.utp.epptrack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import pe.utp.epptrack.dto.EntregaForm;
import pe.utp.epptrack.dto.ItemEntregaForm;
import pe.utp.epptrack.model.*;
import pe.utp.epptrack.repository.DetalleEntregaRepository;
import pe.utp.epptrack.repository.EppRepository;
import pe.utp.epptrack.repository.MatrizAsignacionRepository;
import pe.utp.epptrack.repository.UnidadEppRepository;
import pe.utp.epptrack.service.EntregaService;
import pe.utp.epptrack.service.InventarioService;
import pe.utp.epptrack.service.ReglaNegocioException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrencia real (sin @Transactional de prueba) del inventario y de las entregas. Usa una base H2 propia
 * ({@code epptrack-concurrencia}) y cierra su contexto al terminar, de modo que no afecta a las demás pruebas
 * ni queda ningún dato confirmado en la base compartida.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:epptrack-concurrencia;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrenciaInventarioTest {

    private static final int HILOS = 8;

    @Autowired DatosPrueba datos;
    @Autowired InventarioService inventario;
    @Autowired EntregaService entregaService;
    @Autowired EppRepository eppRepository;
    @Autowired UnidadEppRepository unidadRepository;
    @Autowired DetalleEntregaRepository detalleRepository;
    @Autowired MatrizAsignacionRepository matrizRepository;

    @BeforeAll
    void cargarDatos() {
        datos.cargar();
    }

    private Epp nuevoEpp(String nombre) {
        Epp e = eppRepository.save(new Epp(datos.respirador.getCategoria(), nombre, null, 200));
        matrizRepository.save(new MatrizAsignacion(datos.actividad, e, true, 1));
        return e;
    }

    private EntregaForm form(Trabajador t, Epp epp) {
        EntregaForm f = new EntregaForm();
        f.setIdTrabajador(t.getIdTrabajador());
        f.setFechaEntrega(LocalDate.now().minusDays(1));
        f.setMotivo(MotivoEntrega.INGRESO);
        f.setItems(List.of(new ItemEntregaForm(epp.getIdEpp(), true, 1)));
        return f;
    }

    /** Ejecuta las tareas a la vez (arrancan juntas tras un cerrojo) y devuelve el resultado o la excepción de cada una. */
    private <T> List<Object> enParalelo(List<Callable<T>> tareas) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(HILOS);
        CountDownLatch listos = new CountDownLatch(tareas.size());
        CountDownLatch salida = new CountDownLatch(1);
        try {
            List<Future<Object>> futuros = new ArrayList<>();
            for (Callable<T> tarea : tareas) {
                futuros.add(pool.submit(() -> {
                    listos.countDown();
                    salida.await();
                    try {
                        return (Object) tarea.call();
                    } catch (Throwable e) {
                        return e;
                    }
                }));
            }
            assertThat(listos.await(30, TimeUnit.SECONDS)).isTrue();
            salida.countDown();
            List<Object> resultados = new ArrayList<>();
            for (Future<Object> f : futuros) {
                resultados.add(f.get(45, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(60)
    void ocho_ingresosSimultaneosGeneranCuarentaCodigosUnicos() throws Exception {
        Epp epp = nuevoEpp("Concurrencia ingreso");
        List<Callable<List<UnidadEpp>>> tareas = new ArrayList<>();
        for (int i = 0; i < HILOS; i++) {
            tareas.add(() -> inventario.ingresar(epp.getIdEpp(), 5, "sup-test"));
        }
        List<Object> resultados = enParalelo(tareas);

        assertThat(resultados).allSatisfy(r -> assertThat(r).as("sin errores: %s", r).isInstanceOf(List.class));
        Set<String> codigos = new HashSet<>();
        for (Object r : resultados) {
            @SuppressWarnings("unchecked") List<UnidadEpp> lista = (List<UnidadEpp>) r;
            lista.forEach(u -> codigos.add(u.getCodigo()));
        }
        assertThat(codigos).hasSize(40);
        assertThat(unidadRepository.countByEppIdEpp(epp.getIdEpp())).isEqualTo(40);
        assertThat(unidadRepository.findByEppIdEppAndEstadoOrderByIdUnidadAsc(epp.getIdEpp(), EstadoUnidad.DISPONIBLE))
                .extracting(UnidadEpp::getCodigo).doesNotHaveDuplicates().hasSize(40);
    }

    @Test
    @Timeout(60)
    void conUnaSolaUnidadDosEntregasSimultaneasNoLaAsignanDosVeces() throws Exception {
        Epp epp = nuevoEpp("Concurrencia stock unico");
        UnidadEpp unica = inventario.ingresar(epp.getIdEpp(), 1, "sup-test").getFirst();
        Trabajador a = datos.trabajador("90000101", LocalDate.now().minusDays(30));
        Trabajador b = datos.trabajador("90000102", LocalDate.now().minusDays(30));

        List<Object> resultados = enParalelo(List.of(
                () -> entregaService.registrar(form(a, epp), "sup-test"),
                () -> entregaService.registrar(form(b, epp), "sup-test")));

        assertThat(resultados).allSatisfy(r -> assertThat(r).as("sin errores: %s", r).isInstanceOf(Entrega.class));
        List<Entrega> entregas = resultados.stream().map(r -> (Entrega) r).toList();
        assertThat(entregas.stream().filter(e -> !e.getCodigosAsignados().isEmpty()).count()).isEqualTo(1);
        assertThat(entregas.stream().filter(e -> !e.getSinStock().isEmpty()).count()).isEqualTo(1);
        assertThat(entregas.stream().flatMap(e -> e.getCodigosAsignados().stream()).collect(Collectors.toList()))
                .containsExactly(unica.getCodigo());

        UnidadEpp despues = unidadRepository.findById(unica.getIdUnidad()).orElseThrow();
        assertThat(despues.getEstado()).isEqualTo(EstadoUnidad.ASIGNADO);
        assertThat(unidadRepository.findByEppIdEppAndEstadoOrderByIdUnidadAsc(epp.getIdEpp(), EstadoUnidad.ASIGNADO)).hasSize(1);
    }

    @Test
    @Timeout(60)
    void dosRegistrosSimultaneosDelMismoEppAlMismoTrabajadorSoloPersistenUno() throws Exception {
        Epp epp = nuevoEpp("Concurrencia duplicado");
        Trabajador t = datos.trabajador("90000103", LocalDate.now().minusDays(30));

        List<Object> resultados = enParalelo(List.of(
                () -> entregaService.registrar(form(t, epp), "sup-test"),
                () -> entregaService.registrar(form(t, epp), "sup-test")));

        long ok = resultados.stream().filter(r -> r instanceof Entrega).count();
        long rechazadas = resultados.stream().filter(r -> r instanceof ReglaNegocioException).count();
        assertThat(ok).as("resultados: %s", resultados).isEqualTo(1);
        assertThat(rechazadas).as("resultados: %s", resultados).isEqualTo(1);
        long vigentes = detalleRepository
                .findByEntregaTrabajadorIdTrabajadorAndEstadoVigenciaIn(t.getIdTrabajador(),
                        java.util.EnumSet.of(EstadoVigencia.VIGENTE, EstadoVigencia.POR_VENCER)).stream()
                .filter(d -> d.getEpp().getIdEpp().equals(epp.getIdEpp())).count();
        assertThat(vigentes).isEqualTo(1);
    }
}
