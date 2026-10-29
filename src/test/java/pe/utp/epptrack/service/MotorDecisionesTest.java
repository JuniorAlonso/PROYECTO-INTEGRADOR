package pe.utp.epptrack.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.DatosPrueba;
import pe.utp.epptrack.dto.EntregaForm;
import pe.utp.epptrack.dto.EvaluacionDotacion;
import pe.utp.epptrack.dto.EvaluacionDotacion.EstadoItem;
import pe.utp.epptrack.dto.EvaluacionDotacion.Resultado;
import pe.utp.epptrack.model.*;
import pe.utp.epptrack.repository.*;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Casos de prueba PR-01 a PR-13 del motor de decisiones (catálogo maestro, Anexo de trazabilidad).
 * Datos ficticios: actividad "Aplicación con mochila" que exige respirador ×1 y guantes de nitrilo ×2 (DatosPrueba).
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MotorDecisionesTest {

    @Autowired DatosPrueba datos;
    @Autowired MotorDotacionService motor;
    @Autowired EntregaService entregaService;
    @Autowired TrabajadorService trabajadorService;
    @Autowired MatrizVersionService versionService;
    @Autowired AuditoriaService auditoriaService;
    @Autowired IdentidadService identidadService;
    @Autowired MatrizAsignacionRepository matrizRepository;
    @Autowired ActividadRepository actividadRepository;
    @Autowired ProcesoRepository procesoRepository;
    @Autowired RegistroAuditoriaRepository auditoriaRepository;
    @Autowired DetalleEntregaRepository detalleRepository;

    Trabajador trabajador;

    @BeforeEach
    void preparar() {
        datos.cargar();
        trabajador = datos.trabajador("30000001", LocalDate.now().minusDays(60));
    }

    private EntregaForm entrega(LocalDate fecha, Epp epp, int cantidad) {
        EntregaForm f = datos.formulario(trabajador, fecha, epp);
        f.getItems().getFirst().setCantidad(cantidad);
        return f;
    }

    private EvaluacionDotacion.Verificacion verificacion(EvaluacionDotacion ev, Epp epp) {
        return ev.verificaciones().stream().filter(v -> v.epp().getIdEpp().equals(epp.getIdEpp())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("PR-01 Sin entregas: la dotación neta es todo lo que exige la matriz, con su cantidad")
    void sinEntregasPendienteEsTodoLoExigido() {
        EvaluacionDotacion ev = motor.evaluar(trabajador);
        assertThat(ev.resultado()).isEqualTo(Resultado.NO_CUMPLE);
        assertThat(ev.pendientes()).extracting(p -> p.epp().getNombre() + "x" + p.cantidad())
                .containsExactlyInAnyOrder("Respiradorx1", "Guantes de nitrilox2");
        assertThat(verificacion(ev, datos.respirador).estado()).isEqualTo(EstadoItem.FALTANTE);
    }

    @Test
    @DisplayName("PR-02 Entrega incompleta: 1 de 2 guantes deja pendiente solo la diferencia")
    void entregaParcialDejaPendienteLaDiferencia() {
        entregaService.registrar(entrega(LocalDate.now(), datos.guantes, 1), "sup-test");
        EvaluacionDotacion ev = motor.evaluar(trabajador);
        EvaluacionDotacion.Verificacion v = verificacion(ev, datos.guantes);
        assertThat(v.estado()).isEqualTo(EstadoItem.PARCIAL);
        assertThat(v.explicacion()).contains("1 de 2");
        assertThat(ev.pendientes()).filteredOn(p -> p.epp().equals(datos.guantes))
                .singleElement().extracting(EvaluacionDotacion.Pendiente::cantidad).isEqualTo(1);
    }

    @Test
    @DisplayName("PR-03 EPP vencido: se explica la fecha de vencimiento y pasa a pendiente de renovación")
    void eppVencidoGeneraRenovacion() {
        entregaService.registrar(entrega(LocalDate.now().minusDays(40), datos.guantes, 2), "sup-test");
        EvaluacionDotacion.Verificacion v = verificacion(motor.evaluar(trabajador), datos.guantes);
        assertThat(v.estado()).isEqualTo(EstadoItem.VENCIDO);
        assertThat(v.explicacion()).contains("venció");
    }

    @Test
    @DisplayName("PR-04 Todo vigente y en cantidad: CUMPLE y no hay nada que volver a entregar")
    void todoVigenteCumple() {
        EntregaForm f = datos.formulario(trabajador, LocalDate.now(), datos.respirador, datos.guantes);
        f.getItems().get(1).setCantidad(2);
        entregaService.registrar(f, "sup-test");
        EvaluacionDotacion ev = motor.evaluar(trabajador);
        assertThat(ev.resultado()).isEqualTo(Resultado.CUMPLE);
        assertThat(ev.pendientes()).isEmpty();
    }

    @Test
    @DisplayName("PR-05 Reincorporado: lo vigente de la campaña anterior no se repite y se pide confirmar que lo conserva")
    void reincorporadoNoRecibeLoVigenteYSeLePideConfirmar() {
        entregaService.registrar(entrega(LocalDate.now().minusDays(50), datos.respirador, 1), "sup-test");
        trabajadorService.registrarCese(trabajador.getIdTrabajador(), LocalDate.now().minusDays(20));
        trabajadorService.registrarReingreso(trabajador.getIdTrabajador(), "Campaña 2", LocalDate.now().minusDays(1),
                null, datos.actividad.getIdActividad());

        EvaluacionDotacion ev = motor.evaluar(trabajador);
        assertThat(ev.pendientes()).noneMatch(p -> p.epp().equals(datos.respirador));
        assertThat(ev.alertas()).anyMatch(a -> a.codigo().equals("CONFIRMAR_CONSERVACION"));
        assertThat(verificacion(ev, datos.respirador).explicacion()).contains("campaña anterior");
    }

    @Test
    @DisplayName("PR-06 Cesado: no se evalúa ni se entrega hasta registrar el reingreso")
    void cesadoNoEsEvaluable() {
        trabajadorService.registrarCese(trabajador.getIdTrabajador(), LocalDate.now());
        EvaluacionDotacion ev = motor.evaluar(trabajador);
        assertThat(ev.resultado()).isEqualTo(Resultado.NO_EVALUABLE);
        assertThat(ev.alertas()).anyMatch(a -> a.codigo().equals("CESADO"));
    }

    @Test
    @DisplayName("PR-07 Actividad sin reglas en la matriz: el sistema no decide y pide completar la matriz")
    void actividadSinMatrizBloquea() {
        Actividad nueva = actividadRepository.save(new Actividad(procesoRepository.save(new Proceso("Cosecha")), "Cosecha de uva"));
        Trabajador t = new Trabajador();
        t.setDni("30000099"); t.setApellidos("Quispe"); t.setNombres("Rosa");
        t.setPuesto(datos.puesto); t.setSede(datos.sede);
        t = trabajadorService.registrar(t, nueva.getIdActividad(), "C1", LocalDate.now());
        EvaluacionDotacion ev = motor.evaluar(t);
        assertThat(ev.resultado()).isEqualTo(Resultado.NO_EVALUABLE);
        assertThat(ev.alertas()).anyMatch(a -> a.codigo().equals("ACTIVIDAD_SIN_MATRIZ"));
    }

    @Test
    @DisplayName("PR-08 EPP no exigido por la matriz: sin justificación se bloquea; con justificación queda registrada")
    void eppNoExigidoExigeJustificacion() {
        EntregaForm sinJustificar = datos.formulario(trabajador, LocalDate.now(), datos.casco);
        assertThat(entregaService.noExigidosSinJustificar(sinJustificar)).containsExactly(datos.casco.getIdEpp());
        assertThatThrownBy(() -> entregaService.registrar(sinJustificar, "sup-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("RN-06");

        EntregaForm justificado = datos.formulario(trabajador, LocalDate.now(), datos.casco);
        justificado.getItems().getFirst().setJustificacion("Trabajo puntual bajo estructura, indicado por SST");
        Entrega e = entregaService.registrar(justificado, "sup-test");
        assertThat(e.getDetalles().getFirst().getJustificacionNoExigido()).contains("estructura");
    }

    @Test
    @DisplayName("PR-09 Cambio de actividad: lo que ya tiene y la nueva actividad no exige no genera pendiente")
    void cambioDeActividadMuestraNoExigido() {
        entregaService.registrar(entrega(LocalDate.now(), datos.respirador, 1), "sup-test");
        matrizRepository.findAll().stream().filter(m -> m.getEpp().equals(datos.respirador)).forEach(matrizRepository::delete);
        EvaluacionDotacion ev = motor.evaluar(trabajador);
        assertThat(verificacion(ev, datos.respirador).estado()).isEqualTo(EstadoItem.NO_EXIGIDO);
        assertThat(ev.pendientes()).noneMatch(p -> p.epp().equals(datos.respirador));
    }

    @Test
    @DisplayName("PR-10 Versión de la matriz: cada entrega guarda la versión y publicar calcula el impacto")
    void versionDeMatrizEImpacto() {
        versionService.publicar("v1", "Prueba", "admin-test");
        Entrega e = entregaService.registrar(entrega(LocalDate.now(), datos.respirador, 1), "sup-test");
        assertThat(e.getVersionMatriz()).isEqualTo("v1");

        matrizRepository.save(new MatrizAsignacion(datos.actividad, datos.casco, true, 1));
        assertThat(versionService.estado().cambiosSinPublicar()).isTrue();
        assertThat(motor.evaluar(trabajador).alertas()).anyMatch(a -> a.codigo().equals("MATRIZ_CAMBIOS"));

        MatrizVersionService.Impacto impacto = versionService.publicar("v2", "Se agrega casco", "admin-test");
        assertThat(impacto.agregadas()).singleElement().asString().contains("Casco");
        assertThat(impacto.trabajadoresAfectados()).extracting(Trabajador::getDni).contains("30000001");
        assertThat(versionService.vigente()).get().extracting(VersionMatriz::getCodigo).isEqualTo("v2");

        assertThatThrownBy(() -> versionService.publicar("v3", null, "admin-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("no cambió");
        assertThatThrownBy(() -> versionService.publicar("V2", null, "admin-test"))
                .isInstanceOf(ReglaNegocioException.class).hasMessageContaining("Ya existe");
    }

    @Test
    @DisplayName("PR-11 Auditoría: las operaciones quedan encadenadas y una alteración directa se detecta")
    void auditoriaDetectaAlteracion() {
        Entrega e = entregaService.registrar(entrega(LocalDate.now(), datos.respirador, 1), "sup-test");
        entregaService.anularDetalle(e.getDetalles().getFirst().getIdDetalle(), "Error de digitación", "sup-test");

        AuditoriaService.Verificacion ok = auditoriaService.verificar();
        assertThat(ok.integra()).isTrue();
        assertThat(auditoriaRepository.findAllByOrderByIdRegistroAsc()).extracting(RegistroAuditoria::getAccion)
                .contains("TRABAJADOR_REGISTRADO", "ENTREGA_REGISTRADA", "ENTREGA_ANULADA");

        RegistroAuditoria entregaReg = auditoriaRepository.findAllByOrderByIdRegistroAsc().stream()
                .filter(r -> r.getAccion().equals("ENTREGA_REGISTRADA")).findFirst().orElseThrow();
        entregaReg.setDetalle(entregaReg.getDetalle().replace("x1", "x5"));   // alguien edita la base a mano
        auditoriaRepository.saveAndFlush(entregaReg);

        AuditoriaService.Verificacion alterada = auditoriaService.verificar();
        assertThat(alterada.integra()).isFalse();
        assertThat(alterada.primerRegistroAlterado()).isEqualTo(entregaReg.getIdRegistro());
    }

    @Test
    @DisplayName("PR-12 Identidad: una ficha con el mismo nombre (sin importar tildes) se marca como posible duplicado")
    void detectaPosibleFichaDuplicada() {
        Trabajador otro = new Trabajador();
        otro.setDni("30000002"); otro.setApellidos("PEREZ"); otro.setNombres("juan");
        otro.setPuesto(datos.puesto); otro.setSede(datos.sede);
        trabajadorService.registrar(otro, datos.actividad.getIdActividad(), "C1", LocalDate.now());

        assertThat(identidadService.posiblesDuplicados(trabajador)).extracting(Trabajador::getDni).containsExactly("30000002");
        assertThat(motor.evaluar(trabajador).alertas()).anyMatch(a -> a.codigo().equals("POSIBLE_DUPLICADO"));
    }

    @Test
    @DisplayName("PR-13 Duplicado vigente: sin justificación se bloquea y el anterior pasa a RENOVADO al justificar")
    void duplicadoVigenteRequiereJustificacion() {
        Entrega primera = entregaService.registrar(entrega(LocalDate.now(), datos.respirador, 1), "sup-test");
        EntregaForm otra = entrega(LocalDate.now(), datos.respirador, 1);
        assertThat(entregaService.duplicadosSinJustificar(otra)).containsExactly(datos.respirador.getIdEpp());
        otra.getItems().getFirst().setJustificacion("Se rompió el filtro");
        entregaService.registrar(otra, "sup-test");
        assertThat(detalleRepository.findById(primera.getDetalles().getFirst().getIdDetalle())).get()
                .extracting(DetalleEntrega::getEstadoVigencia).isEqualTo(EstadoVigencia.RENOVADO);
    }
}
