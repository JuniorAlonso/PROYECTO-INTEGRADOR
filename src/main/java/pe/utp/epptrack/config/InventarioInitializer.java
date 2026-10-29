package pe.utp.epptrack.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.model.*;
import pe.utp.epptrack.repository.DetalleEntregaRepository;
import pe.utp.epptrack.repository.EppRepository;
import pe.utp.epptrack.repository.UnidadEppRepository;
import pe.utp.epptrack.service.InventarioService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Carga el inventario de demostración la primera vez que no hay unidades con código: costos referenciales,
 * unidades para los EPP ya entregados, stock en almacén y algunas devoluciones y mantenimientos de ejemplo.
 * Corre después de {@link DataInitializer}, también sobre una base que ya tenía entregas.
 */
@Component
@Order(2)
@ConditionalOnProperty(name = "app.datos-demo", havingValue = "true", matchIfMissing = true)
public class InventarioInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(InventarioInitializer.class);
    private static final String USUARIO = "admin";

    private static final Map<String, String> COSTOS = Map.ofEntries(
            Map.entry("Sombrero con protector de nuca", "18.00"), Map.entry("Casco de seguridad", "35.00"),
            Map.entry("Tapones auditivos", "2.50"), Map.entry("Orejeras", "45.00"),
            Map.entry("Respirador media cara", "85.00"), Map.entry("Cartuchos para vapores orgánicos", "40.00"),
            Map.entry("Mascarilla N95", "4.50"), Map.entry("Lentes de seguridad claros", "12.00"),
            Map.entry("Lentes de seguridad oscuros", "14.00"), Map.entry("Careta facial", "28.00"),
            Map.entry("Traje impermeable para aplicación", "65.00"), Map.entry("Mandil de PVC", "22.00"),
            Map.entry("Polo manga larga con protección UV", "25.00"), Map.entry("Chaleco reflectivo", "15.00"),
            Map.entry("Bloqueador solar", "20.00"), Map.entry("Guantes de nitrilo", "8.00"),
            Map.entry("Guantes de cuero", "16.00"), Map.entry("Guantes anticorte", "32.00"),
            Map.entry("Guantes de hilo", "3.00"), Map.entry("Botas de jebe", "38.00"),
            Map.entry("Zapatos de seguridad", "120.00"));

    private final UnidadEppRepository unidadRepository;
    private final EppRepository eppRepository;
    private final DetalleEntregaRepository detalleRepository;
    private final InventarioService inventarioService;

    public InventarioInitializer(UnidadEppRepository unidadRepository, EppRepository eppRepository,
                                 DetalleEntregaRepository detalleRepository, InventarioService inventarioService) {
        this.unidadRepository = unidadRepository;
        this.eppRepository = eppRepository;
        this.detalleRepository = detalleRepository;
        this.inventarioService = inventarioService;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (unidadRepository.count() > 0 || eppRepository.count() == 0) {
            return;
        }
        log.info("Sin unidades con código: cargando inventario de demostración");
        for (Epp epp : eppRepository.findAll()) {
            if (epp.getCostoUnitario() == null && COSTOS.containsKey(epp.getNombre())) {
                epp.setCostoUnitario(new BigDecimal(COSTOS.get(epp.getNombre())));
            }
        }

        // Cada EPP ya entregado (y no anulado) recibe sus unidades con código, en poder del trabajador.
        List<DetalleEntrega> entregados = detalleRepository.findAll().stream()
                .filter(d -> d.getEstadoVigencia() != EstadoVigencia.ANULADO)
                .sorted(Comparator.comparing(DetalleEntrega::getIdDetalle))
                .toList();
        // Desechables: un lote ficticio por EPP (con margen y vencimiento en 20 días) del que se consume lo ya entregado.
        Map<Integer, Integer> entregadoDesechable = new java.util.LinkedHashMap<>();
        for (DetalleEntrega d : entregados) {
            if (d.getEpp().isDesechable()) {
                entregadoDesechable.merge(d.getEpp().getIdEpp(), d.getCantidad(), Integer::sum);
            }
        }
        entregadoDesechable.forEach((idEpp, total) -> inventarioService.ingresarConsumible(idEpp, total + 3,
                "LOTE-DEMO-A%03d".formatted(idEpp), LocalDate.now().plusDays(20), "Lote de demostración (datos ficticios)",
                USUARIO));
        for (DetalleEntrega d : entregados) {
            if (d.getEpp().isDesechable()) {
                inventarioService.consumirConsumible(d, d.getEntrega().getTrabajador(), USUARIO);
                continue;
            }
            inventarioService.ingresar(d.getEpp().getIdEpp(), d.getCantidad(), USUARIO)
                    .forEach(u -> u.setFechaIngreso(d.getEntrega().getFechaEntrega()));
            inventarioService.asignar(d, d.getEntrega().getTrabajador(), USUARIO);
        }

        // Stock en almacén: algunos EPP alcanzan para el personal y otros no, para ver la compra sugerida.
        for (Epp epp : eppRepository.findByEstadoOrderByCategoriaNombreAscNombreAsc(Epp.ACTIVO)) {
            int stock = switch (epp.getIdEpp() % 4) {
                case 0 -> 1;
                case 1 -> 4;
                case 2 -> 8;
                default -> 12;
            };
            if (epp.isDesechable()) {
                // Segundo lote ficticio con vencimiento variado (3, 6 o 12 meses); sin vencimiento si el id es múltiplo de 5.
                LocalDate vence = epp.getIdEpp() % 5 == 0 ? null : LocalDate.now().plusDays(90L * (1 + epp.getIdEpp() % 3));
                inventarioService.ingresarConsumible(epp.getIdEpp(), stock * 5, "LOTE-DEMO-B%03d".formatted(epp.getIdEpp()),
                        vence, "Lote de demostración (datos ficticios)", USUARIO);
                continue;
            }
            inventarioService.ingresar(epp.getIdEpp(), stock, USUARIO);
        }

        cargarFlujoDeDemostracion();
    }

    /**
     * Devoluciones y recorrido de ejemplo (datos ficticios): una unidad queda por inspeccionar, una por autorizar
     * (la reparada con costo), una en cuarentena, una en taller con su registro y una dada de baja.
     */
    private void cargarFlujoDeDemostracion() {
        List<UnidadEpp> candidatas = new java.util.ArrayList<>(inventarioService.pendientesDevolucion());
        unidadRepository.findByEstadoOrderByCodigoAsc(EstadoUnidad.ASIGNADO).stream()
                .filter(u -> u.getEpp().isPermiteDevolucion() && !candidatas.contains(u))
                .forEach(candidatas::add);
        CondicionEpp[] condiciones = {CondicionEpp.REPARABLE, CondicionEpp.REPARABLE, CondicionEpp.BUENO,
                CondicionEpp.BUENO, CondicionEpp.BUENO, CondicionEpp.INSERVIBLE};
        List<UnidadEpp> devueltas = new java.util.ArrayList<>();
        for (int i = 0; i < Math.min(condiciones.length, candidatas.size()); i++) {
            devueltas.add(inventarioService.devolver(candidatas.get(i).getCodigo(), condiciones[i],
                    MotivoDevolucion.REEMPLAZO, "Devolución de demostración", USUARIO));
        }
        if (devueltas.size() > 0 && devueltas.get(0).getEstado() == EstadoUnidad.POR_INSPECCION) {   // mantenimiento cerrado con costo
            Integer id = devueltas.get(0).getIdUnidad();
            inventarioService.registrarInspeccion(id, ResultadoInspeccion.REQUIERE_MANTENIMIENTO, "Correa desgastada", USUARIO);
            inventarioService.enviarAMantenimiento(id, "Correa desgastada y suciedad", "Cambio de correa y limpieza", USUARIO);
            inventarioService.cerrarMantenimiento(id, ResultadoMantenimiento.REPARADO, "Cambio de correa y limpieza",
                    new BigDecimal("12.50"), null, USUARIO);
        }
        if (devueltas.size() > 1 && devueltas.get(1).getEstado() == EstadoUnidad.POR_INSPECCION) {   // en taller, registro abierto
            Integer id = devueltas.get(1).getIdUnidad();
            inventarioService.registrarInspeccion(id, ResultadoInspeccion.REQUIERE_MANTENIMIENTO, "Costura abierta", USUARIO);
            inventarioService.enviarAMantenimiento(id, "Costura abierta en el costado", "Recostura", USUARIO);
        }
        if (devueltas.size() > 2 && devueltas.get(2).getEstado() == EstadoUnidad.POR_INSPECCION) {   // en cuarentena
            inventarioService.registrarInspeccion(devueltas.get(2).getIdUnidad(), ResultadoInspeccion.NO_APTO_CUARENTENA,
                    "Se duda de su estado: queda apartada hasta que una persona decida", USUARIO);
        }
        if (devueltas.size() > 3 && devueltas.get(3).getEstado() == EstadoUnidad.POR_INSPECCION) {   // por autorizar
            inventarioService.registrarInspeccion(devueltas.get(3).getIdUnidad(), ResultadoInspeccion.APTO_PARA_USO,
                    null, USUARIO);
        }
        // devueltas.get(4) queda POR_INSPECCION y devueltas.get(5) (inservible) queda DE_BAJA.
    }
}
