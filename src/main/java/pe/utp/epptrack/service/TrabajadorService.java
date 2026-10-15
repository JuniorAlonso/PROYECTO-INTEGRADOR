package pe.utp.epptrack.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.model.*;
import pe.utp.epptrack.repository.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Gestión de trabajadores, sus campañas (periodos laborales) y la actividad que desempeñan (RF-01, RN-04). */
@Service
public class TrabajadorService {

    private final TrabajadorRepository trabajadorRepository;
    private final PeriodoLaboralRepository periodoRepository;
    private final AsignacionActividadRepository asignacionRepository;
    private final ActividadRepository actividadRepository;
    private final SedeRepository sedeRepository;

    public TrabajadorService(TrabajadorRepository trabajadorRepository,
                             PeriodoLaboralRepository periodoRepository,
                             AsignacionActividadRepository asignacionRepository,
                             ActividadRepository actividadRepository,
                             SedeRepository sedeRepository) {
        this.trabajadorRepository = trabajadorRepository;
        this.periodoRepository = periodoRepository;
        this.asignacionRepository = asignacionRepository;
        this.actividadRepository = actividadRepository;
        this.sedeRepository = sedeRepository;
    }

    @Transactional(readOnly = true)
    public List<Trabajador> buscar(String texto, Integer idSede, EstadoTrabajador estado) {
        String filtro = texto == null || texto.isBlank() ? null : texto.trim();
        return trabajadorRepository.buscar(filtro, idSede, estado);
    }

    @Transactional(readOnly = true)
    public Trabajador obtener(Integer id) {
        return trabajadorRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException("Trabajador no encontrado."));
    }

    @Transactional(readOnly = true)
    public Optional<Trabajador> buscarPorDni(String dni) {
        return trabajadorRepository.findByDni(dni == null ? "" : dni.trim());
    }

    @Transactional(readOnly = true)
    public Optional<Actividad> actividadActual(Integer idTrabajador) {
        return asignacionRepository.findByTrabajadorIdTrabajadorAndFechaFinIsNull(idTrabajador).stream()
                .map(AsignacionActividad::getActividad)
                .findFirst();
    }

    /** Registra un nuevo trabajador con su primera campaña y actividad. */
    @Transactional
    public Trabajador registrar(Trabajador trabajador, Integer idActividad, String campana, LocalDate fechaIngreso) {
        if (trabajadorRepository.findByDni(trabajador.getDni()).isPresent()) {
            throw new ReglaNegocioException("Ya existe un trabajador con el DNI " + trabajador.getDni()
                    + ". Si vuelve a la empresa, registre su reingreso para conservar su historial.");
        }
        if (campana == null || campana.isBlank() || fechaIngreso == null) {
            throw new ReglaNegocioException("Indique la campaña y la fecha de ingreso.");
        }
        validarCampana(campana);
        if (fechaIngreso.isAfter(LocalDate.now())) {
            throw new ReglaNegocioException("La fecha de ingreso no puede ser futura.");
        }
        if (idActividad == null) {
            throw new ReglaNegocioException("Seleccione la actividad del trabajador: de ella depende su dotación de EPP.");
        }
        normalizar(trabajador);
        trabajador.setEstado(EstadoTrabajador.ACTIVO);
        Trabajador guardado = trabajadorRepository.save(trabajador);
        periodoRepository.save(new PeriodoLaboral(guardado, campana.trim(), fechaIngreso, null));
        asignarActividad(guardado, idActividad, fechaIngreso);
        return guardado;
    }

    /** Actualiza datos personales, puesto y sede; si cambia la actividad, cierra la anterior y abre la nueva. */
    @Transactional
    public Trabajador actualizar(Integer id, Trabajador datos, Integer idActividad) {
        Trabajador trabajador = obtener(id);
        if (trabajadorRepository.existsByDniAndIdTrabajadorNot(datos.getDni(), id)) {
            throw new ReglaNegocioException("El DNI " + datos.getDni() + " pertenece a otro trabajador.");
        }
        normalizar(datos);
        trabajador.setDni(datos.getDni());
        trabajador.setApellidos(datos.getApellidos());
        trabajador.setNombres(datos.getNombres());
        trabajador.setFechaNacimiento(datos.getFechaNacimiento());
        trabajador.setPuesto(datos.getPuesto());
        trabajador.setSede(datos.getSede());

        Integer actual = actividadActual(id).map(Actividad::getIdActividad).orElse(null);
        if (idActividad != null && !idActividad.equals(actual) && trabajador.getEstado().isLaborando()) {
            asignarActividad(trabajador, idActividad, LocalDate.now());
        }
        return trabajador;
    }

    /** Fin de campaña: cierra el periodo y la actividad. El historial de EPP se conserva. */
    @Transactional
    public void registrarCese(Integer id, LocalDate fechaCese) {
        Trabajador trabajador = obtener(id);
        if (!trabajador.getEstado().isLaborando()) {
            throw new ReglaNegocioException("El trabajador ya se encuentra cesado.");
        }
        LocalDate fecha = fechaCese != null ? fechaCese : LocalDate.now();
        if (fecha.isAfter(LocalDate.now())) {
            throw new ReglaNegocioException("La fecha de cese no puede ser futura.");
        }
        periodoRepository.findByTrabajadorIdTrabajadorAndFechaCeseIsNull(id).stream()
                .filter(p -> fecha.isBefore(p.getFechaIngreso())).findFirst().ifPresent(p -> {
                    throw new ReglaNegocioException("La fecha de cese no puede ser anterior al ingreso ("
                            + p.getFechaIngreso() + ").");
                });
        periodoRepository.findByTrabajadorIdTrabajadorAndFechaCeseIsNull(id).forEach(p -> p.setFechaCese(fecha));
        asignacionRepository.findByTrabajadorIdTrabajadorAndFechaFinIsNull(id).forEach(a -> a.setFechaFin(fecha));
        trabajador.setEstado(EstadoTrabajador.CESADO);
    }

    /** Reincorporación en una nueva campaña: mismo trabajador, nuevo periodo, historial intacto (RN-04). */
    @Transactional
    public void registrarReingreso(Integer id, String campana, LocalDate fechaIngreso, Integer idSede, Integer idActividad) {
        Trabajador trabajador = obtener(id);
        if (trabajador.getEstado().isLaborando()) {
            throw new ReglaNegocioException("El trabajador ya se encuentra laborando.");
        }
        if (campana == null || campana.isBlank() || fechaIngreso == null) {
            throw new ReglaNegocioException("Indique la campaña y la fecha de reingreso.");
        }
        validarCampana(campana);
        if (fechaIngreso.isAfter(LocalDate.now())) {
            throw new ReglaNegocioException("La fecha de reingreso no puede ser futura.");
        }
        periodoRepository.findByTrabajadorIdTrabajadorOrderByFechaIngresoDesc(id).stream()
                .map(p -> p.getFechaCese()).filter(java.util.Objects::nonNull).max(LocalDate::compareTo)
                .filter(ultimoCese -> fechaIngreso.isBefore(ultimoCese)).ifPresent(ultimoCese -> {
                    throw new ReglaNegocioException("El reingreso no puede ser anterior al último cese (" + ultimoCese + ").");
                });
        if (idSede != null) {
            trabajador.setSede(sedeRepository.findById(idSede)
                    .orElseThrow(() -> new ReglaNegocioException("Sede no encontrada.")));
        }
        periodoRepository.save(new PeriodoLaboral(trabajador, campana.trim(), fechaIngreso, null));
        trabajador.setEstado(EstadoTrabajador.REINCORPORADO);
        asignarActividad(trabajador, idActividad, fechaIngreso);
    }

    /** Quita espacios sobrantes (también los dobles internos) para que las búsquedas y reportes coincidan. */
    private void normalizar(Trabajador t) {
        t.setDni(t.getDni() == null ? null : t.getDni().trim());
        t.setApellidos(t.getApellidos() == null ? null : t.getApellidos().trim().replaceAll("\\s+", " "));
        t.setNombres(t.getNombres() == null ? null : t.getNombres().trim().replaceAll("\\s+", " "));
    }

    private void validarCampana(String campana) {
        if (campana.trim().length() > 100) {
            throw new ReglaNegocioException("El nombre de la campaña admite hasta 100 caracteres.");
        }
    }

    private void asignarActividad(Trabajador trabajador, Integer idActividad, LocalDate desde) {
        if (idActividad == null) {
            return;
        }
        Actividad actividad = actividadRepository.findById(idActividad)
                .orElseThrow(() -> new RecursoNoEncontradoException("Actividad no encontrada."));
        asignacionRepository.findByTrabajadorIdTrabajadorAndFechaFinIsNull(trabajador.getIdTrabajador())
                .forEach(a -> a.setFechaFin(desde));
        asignacionRepository.save(new AsignacionActividad(trabajador, actividad, desde));
    }
}
