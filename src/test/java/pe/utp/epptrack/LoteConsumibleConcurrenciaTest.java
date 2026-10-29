package pe.utp.epptrack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import pe.utp.epptrack.model.*;
import pe.utp.epptrack.repository.EntregaRepository;
import pe.utp.epptrack.repository.EppRepository;
import pe.utp.epptrack.repository.LoteConsumibleRepository;
import pe.utp.epptrack.service.LoteService;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Consumo simultáneo de un mismo lote (sin @Transactional de prueba): nunca se descuenta más del saldo. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:epptrack-lotes;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoteConsumibleConcurrenciaTest {

    private static final int HILOS = 8;

    @Autowired DatosPrueba datos;
    @Autowired LoteService lotes;
    @Autowired EppRepository eppRepository;
    @Autowired EntregaRepository entregaRepository;
    @Autowired LoteConsumibleRepository loteRepository;

    @BeforeAll
    void cargarDatos() {
        datos.cargar();
    }

    @Test
    @Timeout(60)
    void ochoHilosPidiendoCincoDeUnLoteDeVeinteNuncaSuperanElSaldo() throws Exception {
        Epp epp = new Epp(datos.respirador.getCategoria(), "Guante desechable concurrencia", null, 30);
        epp.setClase(ClaseEpp.DESECHABLE);
        epp = eppRepository.save(epp);
        Integer idEpp = epp.getIdEpp();
        LoteConsumible lote = lotes.ingresarLote(idEpp, 20, "CONC-1", null, null, "sup-test");
        Trabajador t = datos.trabajador("90000201", LocalDate.now().minusDays(30));

        List<DetalleEntrega> detalles = new ArrayList<>();
        for (int i = 0; i < HILOS; i++) {
            Entrega e = new Entrega();
            e.setTrabajador(t);
            e.setSede(datos.sede);
            e.setUsuario(datos.supervisor);
            e.setFechaEntrega(LocalDate.now());
            e.setMotivo(MotivoEntrega.INGRESO);
            e.agregarDetalle(new DetalleEntrega(epp, 5, LocalDate.now().plusDays(30)));
            detalles.add(entregaRepository.save(e).getDetalles().getFirst());
        }

        ExecutorService pool = Executors.newFixedThreadPool(HILOS);
        CountDownLatch listos = new CountDownLatch(HILOS);
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Object>> futuros = new ArrayList<>();
        try {
            for (DetalleEntrega d : detalles) {
                futuros.add(pool.submit(() -> {
                    listos.countDown();
                    salida.await();
                    try {
                        return (Object) lotes.consumir(d, 5, "sup-test");
                    } catch (Throwable ex) {
                        return ex;
                    }
                }));
            }
            assertThat(listos.await(30, TimeUnit.SECONDS)).isTrue();
            salida.countDown();
            int total = 0;
            for (Future<Object> f : futuros) {
                Object r = f.get(45, TimeUnit.SECONDS);
                assertThat(r).as("sin errores: %s", r).isInstanceOf(Integer.class);
                total += (Integer) r;
            }
            assertThat(total).isLessThanOrEqualTo(20);
            int saldoFinal = loteRepository.findById(lote.getIdLote()).orElseThrow().getSaldo();
            assertThat(saldoFinal).isGreaterThanOrEqualTo(0).isEqualTo(20 - total);
            assertThat(lotes.saldo(idEpp)).isEqualTo(20 - total);
        } finally {
            pool.shutdownNow();
        }
    }
}
