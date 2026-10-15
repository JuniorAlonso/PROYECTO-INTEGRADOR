package pe.utp.epptrack.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.dto.CumplimientoSede;
import pe.utp.epptrack.dto.EstadoDotacion;
import pe.utp.epptrack.dto.ItemDotacion;
import pe.utp.epptrack.model.*;
import pe.utp.epptrack.repository.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Determina qué EPP corresponde a cada trabajador según la Matriz (RF-04) y si su dotación está completa (RF-10).
 * Los cálculos masivos cargan asignaciones, matriz y entregas en tres consultas para no recorrer la base por trabajador.
 */
@Service
@Transactional(readOnly = true)
public class DotacionService {

    private static final EnumSet<EstadoVigencia> EN_USO = EnumSet.of(EstadoVigencia.VIGENTE, EstadoVigencia.POR_VENCER);
    private static final EnumSet<EstadoTrabajador> LABORANDO = EnumSet.of(EstadoTrabajador.ACTIVO, EstadoTrabajador.REINCORPORADO);

    private final TrabajadorRepository trabajadorRepository;
    private final AsignacionActividadRepository asignacionRepository;
    private final MatrizAsignacionRepository matrizRepository;
    private final DetalleEntregaRepository detalleRepository;
    private final SedeRepository sedeRepository;

    public DotacionService(TrabajadorRepository trabajadorRepository,
                           AsignacionActividadRepository asignacionRepository,
                           MatrizAsignacionRepository matrizRepository,
                           DetalleEntregaRepository detalleRepository,
                           SedeRepository sedeRepository) {
        this.trabajadorRepository = trabajadorRepository;
        this.asignacionRepository = asignacionRepository;
        this.matrizRepository = matrizRepository;
        this.detalleRepository = detalleRepository;
        this.sedeRepository = sedeRepository;
    }

    /** Estado de dotación de un trabajador. */
    public EstadoDotacion estadoDotacion(Trabajador trabajador) {
        List<Actividad> actividades = asignacionRepository
                .findByTrabajadorIdTrabajadorAndFechaFinIsNull(trabajador.getIdTrabajador()).stream()
                .map(AsignacionActividad::getActividad)
                .toList();
        List<MatrizAsignacion> reglas = actividades.isEmpty() ? List.of()
                : matrizRepository.findByActividadIdActividadIn(actividades.stream().map(Actividad::getIdActividad).toList());
        List<DetalleEntrega> enUso = detalleRepository
                .findByEntregaTrabajadorIdTrabajadorAndEstadoVigenciaIn(trabajador.getIdTrabajador(), EN_USO);
        return construir(trabajador, actividades, reglas, enUso);
    }

    /** Estado de dotación de todos los trabajadores que siguen laborando, opcionalmente filtrado por sede. */
    public List<EstadoDotacion> estadoDotacionTodos(Integer idSede) {
        List<Trabajador> trabajadores = trabajadorRepository.findByEstadoInOrderByApellidosAscNombresAsc(LABORANDO).stream()
                .filter(t -> idSede == null || t.getSede().getIdSede().equals(idSede))
                .toList();

        Map<Integer, List<Actividad>> actividadesPorTrabajador = asignacionRepository.findByFechaFinIsNull().stream()
                .collect(Collectors.groupingBy(a -> a.getTrabajador().getIdTrabajador(),
                        Collectors.mapping(AsignacionActividad::getActividad, Collectors.toList())));
        Map<Integer, List<MatrizAsignacion>> reglasPorActividad = matrizRepository.findAll().stream()
                .collect(Collectors.groupingBy(m -> m.getActividad().getIdActividad()));
        Map<Integer, List<DetalleEntrega>> enUsoPorTrabajador = detalleRepository
                .findByEstadoVigenciaInOrderByFechaRenovacionAsc(EN_USO).stream()
                .collect(Collectors.groupingBy(d -> d.getEntrega().getTrabajador().getIdTrabajador()));

        return trabajadores.stream().map(t -> {
            List<Actividad> actividades = actividadesPorTrabajador.getOrDefault(t.getIdTrabajador(), List.of());
            List<MatrizAsignacion> reglas = actividades.stream()
                    .flatMap(a -> reglasPorActividad.getOrDefault(a.getIdActividad(), List.of()).stream())
                    .toList();
            return construir(t, actividades, reglas, enUsoPorTrabajador.getOrDefault(t.getIdTrabajador(), List.of()));
        }).toList();
    }

    /** Porcentaje de trabajadores con dotación completa agrupado por sede (RF-11, dashboard). */
    public List<CumplimientoSede> cumplimientoPorSede(List<EstadoDotacion> estados) {
        Map<Integer, List<EstadoDotacion>> porSede = estados.stream()
                .collect(Collectors.groupingBy(e -> e.trabajador().getSede().getIdSede()));
        return sedeRepository.findAllByOrderByNombreAsc().stream()
                .filter(s -> porSede.containsKey(s.getIdSede()))
                .map(s -> {
                    List<EstadoDotacion> lista = porSede.get(s.getIdSede());
                    return new CumplimientoSede(s, lista.size(), lista.stream().filter(EstadoDotacion::isCompleta).count());
                })
                .toList();
    }

    /**
     * Une las reglas de todas las actividades del trabajador: si dos actividades piden el mismo EPP se exige
     * la mayor cantidad y basta con que una lo marque como obligatorio.
     */
    private EstadoDotacion construir(Trabajador trabajador, List<Actividad> actividades,
                                     List<MatrizAsignacion> reglas, List<DetalleEntrega> enUso) {
        Map<Integer, MatrizAsignacion> porEpp = new LinkedHashMap<>();
        Map<Integer, Boolean> obligatorio = new HashMap<>();
        Map<Integer, Integer> cantidad = new HashMap<>();
        for (MatrizAsignacion regla : reglas) {
            if (!regla.getEpp().isActivo()) {
                continue;   // un EPP dado de baja del catálogo ya no se exige
            }
            Integer idEpp = regla.getEpp().getIdEpp();
            porEpp.putIfAbsent(idEpp, regla);
            obligatorio.merge(idEpp, regla.isObligatorio(), Boolean::logicalOr);
            cantidad.merge(idEpp, regla.getCantidad(), Math::max);
        }
        // Si hay varias entregas en uso del mismo EPP, la que vence más tarde es la que cubre.
        Map<Integer, DetalleEntrega> enUsoPorEpp = enUso.stream()
                .collect(Collectors.toMap(d -> d.getEpp().getIdEpp(), d -> d,
                        (a, b) -> a.getFechaRenovacion().isAfter(b.getFechaRenovacion()) ? a : b));

        List<ItemDotacion> items = porEpp.values().stream()
                .map(r -> {
                    Integer idEpp = r.getEpp().getIdEpp();
                    return new ItemDotacion(r.getEpp(), cantidad.get(idEpp), obligatorio.get(idEpp), enUsoPorEpp.get(idEpp));
                })
                .sorted(Comparator.comparing((ItemDotacion i) -> i.epp().getCategoria().getNombre())
                        .thenComparing(i -> i.epp().getNombre()))
                .toList();
        return new EstadoDotacion(trabajador, actividades, items);
    }
}
