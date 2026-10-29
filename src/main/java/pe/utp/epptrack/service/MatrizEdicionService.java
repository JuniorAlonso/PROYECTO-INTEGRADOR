package pe.utp.epptrack.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.model.Actividad;
import pe.utp.epptrack.model.Epp;
import pe.utp.epptrack.model.MatrizAsignacion;
import pe.utp.epptrack.model.Proceso;
import pe.utp.epptrack.repository.ActividadRepository;
import pe.utp.epptrack.repository.EppRepository;
import pe.utp.epptrack.repository.MatrizAsignacionRepository;
import pe.utp.epptrack.repository.ProcesoRepository;

/**
 * Edición de la matriz de trabajo con auditoría encadenada (RF-03, RF-13, RF-28, RN-05).
 * Cada alta o retiro de regla y cada actividad nueva queda en la bitácora con el usuario responsable;
 * el detalle solo incluye datos de la matriz, nunca datos personales de trabajadores.
 */
@Service
@Transactional
public class MatrizEdicionService {

    private final MatrizAsignacionRepository matrizRepository;
    private final ActividadRepository actividadRepository;
    private final ProcesoRepository procesoRepository;
    private final EppRepository eppRepository;
    private final AuditoriaService auditoriaService;

    public MatrizEdicionService(MatrizAsignacionRepository matrizRepository, ActividadRepository actividadRepository,
                                ProcesoRepository procesoRepository, EppRepository eppRepository,
                                AuditoriaService auditoriaService) {
        this.matrizRepository = matrizRepository;
        this.actividadRepository = actividadRepository;
        this.procesoRepository = procesoRepository;
        this.eppRepository = eppRepository;
        this.auditoriaService = auditoriaService;
    }

    /** Agrega un EPP a una actividad y devuelve el mensaje de éxito. */
    public String agregarRegla(Integer idActividad, Integer idEpp, Integer cantidad, boolean obligatorio, String usuario) {
        Actividad actividad = actividadRepository.findById(idActividad)
                .orElseThrow(() -> new RecursoNoEncontradoException("Actividad no encontrada."));
        Epp epp = eppRepository.findById(idEpp)
                .orElseThrow(() -> new RecursoNoEncontradoException("EPP no encontrado."));
        if (matrizRepository.existsByActividadIdActividadAndEppIdEpp(idActividad, idEpp)) {
            throw new ReglaNegocioException("Ese EPP ya está en la matriz para esta actividad.");
        }
        if (!epp.isActivo()) {
            throw new ReglaNegocioException("El EPP está desactivado en el catálogo; actívelo antes de asignarlo.");
        }
        int cant = Math.min(99, Math.max(1, cantidad == null ? 1 : cantidad));
        MatrizAsignacion regla = matrizRepository.save(new MatrizAsignacion(actividad, epp, obligatorio, cant));
        auditoriaService.registrar(usuario, "MATRIZ_REGLA_AGREGADA", "matriz_asignacion", regla.getIdMatriz(),
                descripcion(actividad, epp, cant, obligatorio));
        return epp.getNombre() + " agregado a " + actividad.getNombre() + ".";
    }

    /** Retira una regla de la matriz de trabajo y deja constancia de lo que tenía. */
    public RetiroRegla quitarRegla(Integer idMatriz, String usuario) {
        MatrizAsignacion regla = matrizRepository.findById(idMatriz)
                .orElseThrow(() -> new RecursoNoEncontradoException("La regla de la matriz no existe."));
        Integer idActividad = regla.getActividad().getIdActividad();
        auditoriaService.registrar(usuario, "MATRIZ_REGLA_RETIRADA", "matriz_asignacion", idMatriz,
                descripcion(regla.getActividad(), regla.getEpp(), regla.getCantidad(), regla.isObligatorio()));
        matrizRepository.delete(regla);
        return new RetiroRegla(idActividad, regla.getEpp().getNombre() + " retirado de la matriz.");
    }

    /** Crea una actividad (y su proceso si no existe) y devuelve su identificador. */
    public Integer agregarActividad(String proceso, String nombre, String usuario) {
        if (proceso == null || nombre == null || proceso.isBlank() || nombre.isBlank()) {
            throw new ReglaNegocioException("Indique el proceso y el nombre de la actividad.");
        }
        if (proceso.trim().length() > 100 || nombre.trim().length() > 150) {
            throw new ReglaNegocioException("El proceso admite hasta 100 caracteres y la actividad hasta 150.");
        }
        Proceso p = procesoRepository.findByNombre(proceso.trim())
                .orElseGet(() -> procesoRepository.save(new Proceso(proceso.trim())));
        Actividad actividad = actividadRepository.save(new Actividad(p, nombre.trim()));
        auditoriaService.registrar(usuario, "MATRIZ_ACTIVIDAD_CREADA", "actividad", actividad.getIdActividad(),
                "Actividad \"" + actividad.getNombre() + "\" en el proceso \"" + p.getNombre() + "\"");
        return actividad.getIdActividad();
    }

    public record RetiroRegla(Integer idActividad, String mensaje) {}

    private static String descripcion(Actividad a, Epp e, int cantidad, boolean obligatorio) {
        return "Actividad \"" + a.getNombre() + "\": " + e.getNombre() + " ×" + cantidad
                + (obligatorio ? " (obligatorio)" : " (opcional)");
    }
}
