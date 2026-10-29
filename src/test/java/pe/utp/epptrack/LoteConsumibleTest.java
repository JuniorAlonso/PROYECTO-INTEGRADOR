package pe.utp.epptrack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.model.*;
import pe.utp.epptrack.repository.EntregaRepository;
import pe.utp.epptrack.repository.EppRepository;
import pe.utp.epptrack.repository.LoteConsumibleRepository;
import pe.utp.epptrack.service.AuditoriaService;
import pe.utp.epptrack.service.LoteService;
import pe.utp.epptrack.service.ReglaNegocioException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Control por cantidad y lotes de EPP desechables: ingreso, consumo FEFO, reversión y auditoría. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LoteConsumibleTest {

    @Autowired DatosPrueba datos;
    @Autowired LoteService lotes;
    @Autowired EppRepository eppRepository;
    @Autowired EntregaRepository entregaRepository;
    @Autowired LoteConsumibleRepository loteRepository;
    @Autowired AuditoriaService auditoria;

    Epp mascarilla;
    Trabajador w1;

    @BeforeEach
    void preparar() {
        datos.cargar();
        w1 = datos.trabajador("90000021", LocalDate.now().minusDays(60));
        mascarilla = new Epp(datos.respirador.getCategoria(), "Mascarilla desechable", null, 30);
        mascarilla.setClase(ClaseEpp.DESECHABLE);
        mascarilla = eppRepository.save(mascarilla);
    }

    private DetalleEntrega detalle(int cantidad) {
        Entrega e = new Entrega();
        e.setTrabajador(w1);
        e.setSede(datos.sede);
        e.setUsuario(datos.supervisor);
        e.setFechaEntrega(LocalDate.now());
        e.setMotivo(MotivoEntrega.INGRESO);
        e.agregarDetalle(new DetalleEntrega(mascarilla, cantidad, LocalDate.now().plusDays(30)));
        return entregaRepository.save(e).getDetalles().getFirst();
    }

    @Test
    void ingresoGeneraCodigoYExigeUnicidadPorEpp() {
        LoteConsumible auto = lotes.ingresarLote(mascarilla.getIdEpp(), 50, null, null, "Compra ficticia", "sup-test");
        assertThat(auto.getCodigoLote()).startsWith("L-EPP").endsWith("-0001");
        assertThat(auto.getSaldo()).isEqualTo(50);

        lotes.ingresarLote(mascarilla.getIdEpp(), 10, "LOTE-A", null, null, "sup-test");
        assertThatThrownBy(() -> lotes.ingresarLote(mascarilla.getIdEpp(), 10, "LOTE-A", null, null, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("Ya existe el lote");
        assertThat(lotes.saldo(mascarilla.getIdEpp())).isEqualTo(60);
    }

    @Test
    void validacionesDelIngreso() {
        Integer id = mascarilla.getIdEpp();
        assertThatThrownBy(() -> lotes.ingresarLote(id, 0, null, null, null, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class);
        assertThatThrownBy(() -> lotes.ingresarLote(id, 100001, null, null, null, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class);
        assertThatThrownBy(() -> lotes.ingresarLote(id, 5, null, LocalDate.now().minusDays(1), null, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("anterior a hoy");
        assertThatThrownBy(() -> lotes.ingresarLote(9999999, 5, null, null, null, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class);
    }

    @Test
    void ingresoAUnReutilizableEsError() {
        Epp casco = datos.casco;
        casco.setClase(ClaseEpp.REUTILIZABLE_INDIVIDUAL);
        assertThatThrownBy(() -> lotes.ingresarLote(casco.getIdEpp(), 5, null, null, null, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class)
                .hasMessageContaining("Solo los EPP desechables se controlan por lotes");
    }

    @Test
    void consumoParcialTotalYNuncaBajaDeCero() {
        LoteConsumible lote = lotes.ingresarLote(mascarilla.getIdEpp(), 10, "L-1", null, null, "sup-test");

        assertThat(lotes.consumir(detalle(4), 4, "sup-test")).isEqualTo(4);
        assertThat(lote.getSaldo()).isEqualTo(6);
        assertThat(lotes.consumir(detalle(10), 10, "sup-test")).isEqualTo(6);   // solo quedaban 6
        assertThat(lote.getSaldo()).isZero();
        assertThat(lotes.consumir(detalle(3), 3, "sup-test")).isZero();
        assertThat(lote.getSaldo()).isZero();
        assertThat(lotes.saldo(mascarilla.getIdEpp())).isZero();
    }

    @Test
    void fefoVenceAntesSaleAntes() {
        Integer id = mascarilla.getIdEpp();
        LoteConsumible sinVenc = lotes.ingresarLote(id, 10, "SIN-VENC", null, null, "sup-test");
        LoteConsumible tarde = lotes.ingresarLote(id, 10, "TARDE", LocalDate.now().plusDays(90), null, "sup-test");
        LoteConsumible pronto = lotes.ingresarLote(id, 10, "PRONTO", LocalDate.now().plusDays(10), null, "sup-test");

        assertThat(lotes.consumir(detalle(12), 12, "sup-test")).isEqualTo(12);

        assertThat(pronto.getSaldo()).isZero();
        assertThat(tarde.getSaldo()).isEqualTo(8);
        assertThat(sinVenc.getSaldo()).isEqualTo(10);
        assertThat(lotes.lotesPorVencer(30)).isZero();   // PRONTO ya sin saldo; TARDE vence a 90 días
        assertThat(lotes.lotesPorVencer(100)).isEqualTo(1);
    }

    @Test
    void loteVencidoNoSeConsumeNiCuentaEnElSaldo() {
        Integer id = mascarilla.getIdEpp();
        LoteConsumible vencido = loteRepository.save(new LoteConsumible(mascarilla, "VENCIDO", 20,
                LocalDate.now().minusDays(1), null, "sup-test"));
        lotes.ingresarLote(id, 5, "VIGENTE", null, null, "sup-test");

        assertThat(lotes.saldo(id)).isEqualTo(5);
        assertThat(lotes.saldosPorEpp().get(id)).isEqualTo(5L);
        assertThat(lotes.consumir(detalle(10), 10, "sup-test")).isEqualTo(5);
        assertThat(vencido.getSaldo()).isEqualTo(20);
        assertThat(lotes.lotes(id)).hasSize(2);
    }

    @Test
    void revertirRestituyeExactamenteLoConsumidoYEsIdempotente() {
        Integer id = mascarilla.getIdEpp();
        LoteConsumible a = lotes.ingresarLote(id, 5, "A", LocalDate.now().plusDays(5), null, "sup-test");
        LoteConsumible b = lotes.ingresarLote(id, 10, "B", null, null, "sup-test");
        DetalleEntrega d = detalle(8);
        assertThat(lotes.consumir(d, 8, "sup-test")).isEqualTo(8);
        assertThat(a.getSaldo()).isZero();
        assertThat(b.getSaldo()).isEqualTo(7);

        lotes.revertir(d, "sup-test");
        assertThat(a.getSaldo()).isEqualTo(5);
        assertThat(b.getSaldo()).isEqualTo(10);

        lotes.revertir(d, "sup-test");   // segunda vez: no suma de nuevo
        assertThat(a.getSaldo()).isEqualTo(5);
        assertThat(b.getSaldo()).isEqualTo(10);
        assertThat(lotes.saldo(id)).isEqualTo(15);
    }

    @Test
    void registraAuditoriaYLaCadenaSigueIntegra() {
        Integer id = mascarilla.getIdEpp();
        lotes.ingresarLote(id, 10, "AUD-1", null, null, "sup-test");
        DetalleEntrega d = detalle(3);
        lotes.consumir(d, 3, "sup-test");
        lotes.revertir(d, "sup-test");

        assertThat(auditoria.ultimos()).extracting(RegistroAuditoria::getAccion)
                .contains("LOTE_INGRESO", "LOTE_CONSUMO", "LOTE_REVERSION");
        assertThat(auditoria.verificar().integra()).isTrue();
    }
}
