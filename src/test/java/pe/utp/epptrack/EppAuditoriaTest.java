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
import pe.utp.epptrack.repository.RegistroAuditoriaRepository;
import pe.utp.epptrack.service.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Auditoría del catálogo de EPP y del inventario: cada cambio queda con el valor anterior y el nuevo. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EppAuditoriaTest {

    @Autowired DatosPrueba datos;
    @Autowired EppService eppService;
    @Autowired InventarioService inventario;
    @Autowired EntregaService entregaService;
    @Autowired AuditoriaService auditoria;
    @Autowired RegistroAuditoriaRepository registros;

    @BeforeEach
    void preparar() {
        datos.cargar();
    }

    private Epp nuevo(String nombre, int vida, ClaseEpp clase) {
        Epp e = new Epp(datos.respirador.getCategoria(), nombre, null, vida);
        e.setClase(clase);
        return e;
    }

    /** Copia desasociada del EPP, como la que llega del formulario web. */
    private Epp copia(Epp base) {
        Epp c = new Epp(base.getCategoria(), base.getNombre(), base.getDescripcion(), base.getVidaUtilDias());
        c.setIdEpp(base.getIdEpp());
        c.setClase(base.getClase());
        c.setCostoUnitario(base.getCostoUnitario());
        c.setClaseValidada(base.getClaseValidada());
        c.setDevolucionPermitida(base.getDevolucionPermitida());
        c.setInspeccionAlDevolver(base.getInspeccionAlDevolver());
        c.setEstado(base.getEstado());
        return c;
    }

    private List<RegistroAuditoria> de(String entidad, Object id) {
        return registros.findByEntidadAndIdEntidadOrderByIdRegistroAsc(entidad, String.valueOf(id));
    }

    private List<String> acciones(String entidad, Object id) {
        return de(entidad, id).stream().map(RegistroAuditoria::getAccion).toList();
    }

    @Test
    void crearUnEppRegistraEppCreado() {
        Epp e = eppService.guardar(nuevo("Arnés auditado", 100, ClaseEpp.REUTILIZABLE_INDIVIDUAL), "admin-test");

        List<RegistroAuditoria> r = de("epp", e.getIdEpp());
        assertThat(r).hasSize(1);
        assertThat(r.getFirst().getAccion()).isEqualTo("EPP_CREADO");
        assertThat(r.getFirst().getUsuario()).isEqualTo("admin-test");
        assertThat(r.getFirst().getDetalle()).contains("Arnés auditado").contains("REUTILIZABLE_INDIVIDUAL").contains("100");
    }

    @Test
    void modificarVidaUtilYClaseRegistraAntesYDespues() {
        Epp e = eppService.guardar(nuevo("Arnés modificable", 100, ClaseEpp.REUTILIZABLE_INDIVIDUAL), "admin-test");
        Epp cambios = copia(e);
        cambios.setVidaUtilDias(200);
        cambios.setClase(ClaseEpp.DESECHABLE);
        eppService.guardar(cambios, "admin-test");

        List<RegistroAuditoria> r = de("epp", e.getIdEpp());
        assertThat(r).extracting(RegistroAuditoria::getAccion).containsExactly("EPP_CREADO", "EPP_MODIFICADO");
        String detalle = r.getLast().getDetalle();
        assertThat(detalle).contains("vida útil (días): 100 → 200")
                .contains("clase: REUTILIZABLE_INDIVIDUAL → DESECHABLE")
                .contains("devolución permitida: true → false");
        assertThat(e.getVidaUtilDias()).isEqualTo(200);
        assertThat(e.getClase()).isEqualTo(ClaseEpp.DESECHABLE);
    }

    @Test
    void modificarCostoYNombreTambienQuedanRegistrados() {
        Epp e = eppService.guardar(nuevo("Detector A", 100, ClaseEpp.REUTILIZABLE_COMPARTIDO), "admin-test");
        Epp cambios = copia(e);
        cambios.setNombre("Detector B");
        cambios.setCostoUnitario(new BigDecimal("12.50"));
        eppService.guardar(cambios, "admin-test");

        assertThat(de("epp", e.getIdEpp()).getLast().getDetalle())
                .contains("nombre: Detector A → Detector B").contains("costo unitario: null → 12.50");
    }

    @Test
    void guardarSinCambiosNoRegistraNada() {
        Epp e = eppService.guardar(nuevo("Arnés sin cambios", 100, ClaseEpp.DESECHABLE), "admin-test");
        eppService.guardar(copia(e), "admin-test");
        eppService.guardar(copia(e), "admin-test");

        assertThat(acciones("epp", e.getIdEpp())).containsExactly("EPP_CREADO");
    }

    @Test
    void cambiarEstadoRegistraDesactivadoYActivado() {
        Epp e = eppService.guardar(nuevo("Arnés estado", 100, ClaseEpp.DESECHABLE), "admin-test");
        eppService.cambiarEstado(e.getIdEpp(), "admin-test");
        eppService.cambiarEstado(e.getIdEpp(), "admin-test");

        assertThat(acciones("epp", e.getIdEpp())).containsExactly("EPP_CREADO", "EPP_DESACTIVADO", "EPP_ACTIVADO");
        assertThat(e.isActivo()).isTrue();
    }

    @Test
    void laCadenaDeAuditoriaSigueIntegraTrasCambiosDeCatalogo() {
        Epp e = eppService.guardar(nuevo("Arnés cadena", 100, ClaseEpp.REUTILIZABLE_INDIVIDUAL), "admin-test");
        Epp cambios = copia(e);
        cambios.setVidaUtilDias(150);
        eppService.guardar(cambios, "admin-test");
        eppService.cambiarEstado(e.getIdEpp(), "admin-test");

        var v = auditoria.verificar();
        assertThat(v.integra()).as("primer registro alterado: %s", v.primerRegistroAlterado()).isTrue();
        assertThat(v.registros()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void inventarioRegistraIngresoDevolucionYMantenimiento() {
        Epp e = eppService.guardar(nuevo("Arnés inventario", 200, ClaseEpp.REUTILIZABLE_INDIVIDUAL), "admin-test");
        // el EPP debe estar en la matriz para entregarlo sin justificación
        UnidadEpp u = inventario.ingresar(e.getIdEpp(), 2, "sup-test").getFirst();
        assertThat(acciones("epp", e.getIdEpp())).contains("INVENTARIO_INGRESO");
        assertThat(de("epp", e.getIdEpp()).getLast().getDetalle()).contains("2 unidades");

        Trabajador t = datos.trabajador("90000021", LocalDate.now().minusDays(30));
        EntregaForm f = new EntregaForm();
        f.setIdTrabajador(t.getIdTrabajador());
        f.setFechaEntrega(LocalDate.now().minusDays(1));
        f.setMotivo(MotivoEntrega.INGRESO);
        ItemEntregaForm item = new ItemEntregaForm(e.getIdEpp(), true, 1);
        item.setJustificacion("Prueba de auditoría");
        f.setItems(List.of(item));
        Entrega entrega = entregaService.registrar(f, "sup-test");
        UnidadEpp asignada = inventario.buscarPorCodigo(entrega.getCodigosAsignados().getFirst()).orElseThrow();
        assertThat(asignada.getCodigo()).isEqualTo(u.getCodigo());

        inventario.devolver(asignada.getCodigo(), CondicionEpp.REPARABLE, MotivoDevolucion.DETERIORO, null, "sup-test");
        // Regla nueva (pedida por el profesor): lo devuelto pasa por inspección antes de ir a mantenimiento.
        inventario.registrarInspeccion(asignada.getIdUnidad(), ResultadoInspeccion.REQUIERE_MANTENIMIENTO, "Correa floja", "sup-test");
        inventario.enviarAMantenimiento(asignada.getIdUnidad(), "Correa floja", null, "sup-test");
        inventario.cerrarMantenimiento(asignada.getIdUnidad(), ResultadoMantenimiento.REPARADO, "Listo", null, null, "sup-test");

        assertThat(acciones("unidad_epp", asignada.getIdUnidad())).containsExactly("INVENTARIO_DEVOLUCION",
                "INVENTARIO_INSPECCION", "INVENTARIO_MANTENIMIENTO_ENVIO", "INVENTARIO_MANTENIMIENTO_CIERRE");
        List<RegistroAuditoria> r = de("unidad_epp", asignada.getIdUnidad());
        assertThat(r.getFirst().getDetalle()).contains(asignada.getCodigo()).contains("DETERIORO").contains("REPARABLE")
                .contains("POR_INSPECCION");
        assertThat(r.getLast().getDetalle()).contains("REPARADO").contains("POR_AUTORIZAR");
        assertThat(r).extracting(RegistroAuditoria::getUsuario).containsOnly("sup-test");
        assertThat(auditoria.verificar().integra()).isTrue();
    }

    @Test
    void ningunDetalleDeAuditoriaContieneContrasenas() {
        Epp e = eppService.guardar(nuevo("Arnés secretos", 100, ClaseEpp.DESECHABLE), "admin-test");
        eppService.cambiarEstado(e.getIdEpp(), "admin-test");
        eppService.cambiarEstado(e.getIdEpp(), "admin-test");
        inventario.ingresar(e.getIdEpp(), 1, "sup-test");
        datos.trabajador("90000022", LocalDate.now().minusDays(10));

        for (RegistroAuditoria r : registros.findAllByOrderByIdRegistroAsc()) {
            String texto = (r.getDetalle() + " " + r.getUsuario() + " " + r.getAccion()).toLowerCase();
            assertThat(texto).as("registro %s", r.getIdRegistro())
                    .doesNotContain("clave").doesNotContain("password").doesNotContain("contraseña")
                    .doesNotContain("$2a$").doesNotContain("$2b$");
        }
    }
}
