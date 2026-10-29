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
        for (DetalleEntrega d : entregados) {
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
            inventarioService.ingresar(epp.getIdEpp(), stock, USUARIO);
        }

        // Devoluciones de ejemplo de los EPP renovados que siguen en poder del trabajador.
        List<UnidadEpp> pendientes = inventarioService.pendientesDevolucion().stream()
                .filter(u -> !InventarioService.isDescartable(u.getEpp()))
                .toList();
        CondicionEpp[] condiciones = {CondicionEpp.REPARABLE, CondicionEpp.REPARABLE, CondicionEpp.BUENO,
                CondicionEpp.INSERVIBLE, CondicionEpp.REPARABLE};
        for (int i = 0; i < Math.min(condiciones.length, pendientes.size()); i++) {
            inventarioService.devolver(pendientes.get(i).getCodigo(), condiciones[i], "Devolución por renovación", USUARIO);
        }
        List<UnidadEpp> aptos = inventarioService.enEstado(EstadoUnidad.POR_MANTENIMIENTO);
        if (!aptos.isEmpty()) {
            inventarioService.enviarAMantenimiento(aptos.getFirst().getIdUnidad(), USUARIO);
            inventarioService.cerrarMantenimiento(aptos.getFirst().getIdUnidad(), true, "Cambio de correa y limpieza", USUARIO);
        }
        if (aptos.size() > 1) {
            inventarioService.enviarAMantenimiento(aptos.get(1).getIdUnidad(), USUARIO);
        }
    }
}
