package pe.utp.epptrack.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.dto.EntregaForm;
import pe.utp.epptrack.dto.ItemEntregaForm;
import pe.utp.epptrack.model.*;
import pe.utp.epptrack.repository.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Registro de entregas de EPP (RF-05 a RF-07) y corrección auditada de las mismas (RF-13, RN-05). */
@Service
public class EntregaService {

    private static final EnumSet<EstadoVigencia> EN_USO = EnumSet.of(EstadoVigencia.VIGENTE, EstadoVigencia.POR_VENCER);
    private static final EnumSet<EstadoVigencia> REEMPLAZABLES =
            EnumSet.of(EstadoVigencia.VIGENTE, EstadoVigencia.POR_VENCER, EstadoVigencia.VENCIDO);

    private final EntregaRepository entregaRepository;
    private final DetalleEntregaRepository detalleRepository;
    private final HistorialEppRepository historialRepository;
    private final CorreccionEntregaRepository correccionRepository;
    private final TrabajadorRepository trabajadorRepository;
    private final EppRepository eppRepository;
    private final UsuarioRepository usuarioRepository;
    private final PeriodoLaboralRepository periodoRepository;
    private final VigenciaService vigenciaService;
    private final InventarioService inventarioService;
    private final DotacionService dotacionService;
    private final MatrizVersionService versionService;
    private final AuditoriaService auditoriaService;

    public EntregaService(EntregaRepository entregaRepository,
                          DetalleEntregaRepository detalleRepository,
                          HistorialEppRepository historialRepository,
                          CorreccionEntregaRepository correccionRepository,
                          TrabajadorRepository trabajadorRepository,
                          EppRepository eppRepository,
                          UsuarioRepository usuarioRepository,
                          PeriodoLaboralRepository periodoRepository,
                          VigenciaService vigenciaService,
                          InventarioService inventarioService,
                          DotacionService dotacionService,
                          MatrizVersionService versionService,
                          AuditoriaService auditoriaService) {
        this.inventarioService = inventarioService;
        this.dotacionService = dotacionService;
        this.versionService = versionService;
        this.auditoriaService = auditoriaService;
        this.entregaRepository = entregaRepository;
        this.detalleRepository = detalleRepository;
        this.historialRepository = historialRepository;
        this.correccionRepository = correccionRepository;
        this.trabajadorRepository = trabajadorRepository;
        this.eppRepository = eppRepository;
        this.usuarioRepository = usuarioRepository;
        this.periodoRepository = periodoRepository;
        this.vigenciaService = vigenciaService;
    }

    /** EPP que el trabajador tiene en uso, indexados por id de EPP. */
    @Transactional(readOnly = true)
    public Map<Integer, DetalleEntrega> enUsoPorEpp(Integer idTrabajador) {
        return detalleRepository.findByEntregaTrabajadorIdTrabajadorAndEstadoVigenciaIn(idTrabajador, EN_USO).stream()
                .collect(Collectors.toMap(d -> d.getEpp().getIdEpp(), Function.identity(),
                        (a, b) -> a.getFechaRenovacion().isAfter(b.getFechaRenovacion()) ? a : b));
    }

    /**
     * Ids de los EPP seleccionados que el trabajador ya tiene vigentes y no traen justificación.
     * Si la lista no está vacía la entrega no se registra hasta que el usuario justifique cada uno (RF-07).
     */
    @Transactional(readOnly = true)
    public List<Integer> duplicadosSinJustificar(EntregaForm form) {
        Map<Integer, DetalleEntrega> enUso = enUsoPorEpp(form.getIdTrabajador());
        return form.getSeleccionados().stream()
                .filter(i -> enUso.containsKey(i.getIdEpp()))
                .filter(i -> i.getJustificacion() == null || i.getJustificacion().isBlank())
                .map(ItemEntregaForm::getIdEpp)
                .toList();
    }

    /**
     * Ids de los EPP seleccionados que la matriz NO exige para las actividades del trabajador y no traen
     * justificación (RF-23, RN-06). Entregar algo no exigido no está prohibido, pero debe quedar el porqué: así se
     * detectan asignaciones equivocadas antes de confirmarlas. Un EPP desactivado se ignora aquí (se rechaza luego).
     */
    @Transactional(readOnly = true)
    public List<Integer> noExigidosSinJustificar(EntregaForm form) {
        if (form.getIdTrabajador() == null) return List.of();
        Trabajador t = trabajadorRepository.findById(form.getIdTrabajador()).orElse(null);
        if (t == null) return List.of();
        java.util.Set<Integer> exigidos = dotacionService.estadoDotacion(t).items().stream()
                .map(i -> i.epp().getIdEpp()).collect(Collectors.toSet());
        return form.getSeleccionados().stream()
                .filter(i -> !exigidos.contains(i.getIdEpp()))
                .filter(i -> eppRepository.findById(i.getIdEpp()).map(Epp::isActivo).orElse(false))
                .filter(i -> i.getJustificacion() == null || i.getJustificacion().isBlank())
                .map(ItemEntregaForm::getIdEpp)
                .toList();
    }

    /** Detalle de entrega por id, para anular o mostrar. */
    @Transactional(readOnly = true)
    public DetalleEntrega obtenerDetalle(Integer idDetalle) {
        return detalleRepository.findById(idDetalle)
                .orElseThrow(() -> new RecursoNoEncontradoException("Registro de entrega no encontrado."));
    }

    @Transactional
    public Entrega registrar(EntregaForm form, String nombreUsuario) {
        List<ItemEntregaForm> seleccionados = form.getSeleccionados();
        if (seleccionados.isEmpty()) {
            throw new ReglaNegocioException("Seleccione al menos un EPP para entregar.");
        }
        if (form.getFechaEntrega() == null || form.getMotivo() == null) {
            throw new ReglaNegocioException("Indique la fecha y el motivo de la entrega.");
        }
        if (form.getFechaEntrega().isAfter(java.time.LocalDate.now())) {
            throw new ReglaNegocioException("La fecha de entrega no puede ser futura.");
        }
        if (seleccionados.stream().map(ItemEntregaForm::getIdEpp).distinct().count() != seleccionados.size()) {
            throw new ReglaNegocioException("Un mismo EPP no puede repetirse en la misma entrega.");
        }
        if (seleccionados.stream().anyMatch(i -> i.getCantidad() == null || i.getCantidad() < 1 || i.getCantidad() > 99)) {
            throw new ReglaNegocioException("La cantidad de cada EPP debe estar entre 1 y 99.");
        }
        if (seleccionados.stream().anyMatch(i -> i.getJustificacion() != null && i.getJustificacion().trim().length() > 450)) {
            throw new ReglaNegocioException("La justificación admite hasta 450 caracteres.");
        }
        if (form.getIdTrabajador() == null) {
            throw new ReglaNegocioException("Seleccione al trabajador.");
        }
        // Se bloquea al trabajador antes de revisar duplicados: dos registros simultáneos no pueden dejar dos EPP vigentes.
        Trabajador trabajador = trabajadorRepository.findByIdParaActualizar(form.getIdTrabajador())
                .orElseThrow(() -> new RecursoNoEncontradoException("Trabajador no encontrado."));
        if (!duplicadosSinJustificar(form).isEmpty()) {
            throw new ReglaNegocioException("Hay EPP vigentes sin justificación de entrega duplicada.");
        }
        if (!trabajador.getEstado().isLaborando()) {
            throw new ReglaNegocioException("El trabajador está cesado; registre su reingreso antes de entregarle EPP.");
        }
        if (!noExigidosSinJustificar(form).isEmpty()) {
            throw new ReglaNegocioException("Hay EPP que la matriz no exige para la actividad del trabajador; "
                    + "escriba la justificación de cada uno (RN-06).");
        }
        java.util.Set<Integer> exigidos = dotacionService.estadoDotacion(trabajador).items().stream()
                .map(i -> i.epp().getIdEpp()).collect(Collectors.toSet());
        periodoRepository.findByTrabajadorIdTrabajadorAndFechaCeseIsNull(trabajador.getIdTrabajador()).stream()
                .map(PeriodoLaboral::getFechaIngreso).min(java.time.LocalDate::compareTo)
                .filter(ingreso -> form.getFechaEntrega().isBefore(ingreso)).ifPresent(ingreso -> {
                    throw new ReglaNegocioException("La fecha de entrega no puede ser anterior al ingreso del trabajador ("
                            + ingreso + ").");
                });
        Usuario responsable = usuarioRepository.findByNombreUsuario(nombreUsuario)
                .orElseThrow(() -> new ReglaNegocioException("Usuario no encontrado."));

        Entrega entrega = new Entrega();
        entrega.setTrabajador(trabajador);
        entrega.setSede(trabajador.getSede());
        entrega.setUsuario(responsable);
        entrega.setFechaEntrega(form.getFechaEntrega());
        entrega.setMotivo(form.getMotivo());
        entrega.setVersionMatriz(versionService.etiquetaParaEntrega());
        entregaRepository.save(entrega);
        StringBuilder resumen = new StringBuilder("DNI " + trabajador.getDni() + "; motivo " + form.getMotivo()
                + "; fecha " + form.getFechaEntrega() + "; matriz " + entrega.getVersionMatriz() + "; EPP:");

        for (ItemEntregaForm item : seleccionados) {
            Epp epp = eppRepository.findById(item.getIdEpp())
                    .orElseThrow(() -> new RecursoNoEncontradoException("EPP no encontrado."));
            if (!epp.isActivo()) {
                throw new ReglaNegocioException("El EPP \"" + epp.getNombre() + "\" está desactivado en el catálogo.");
            }
            DetalleEntrega detalle = new DetalleEntrega(epp, item.getCantidad(),
                    form.getFechaEntrega().plusDays(epp.getVidaUtilDias()));
            detalle.setEstadoVigencia(vigenciaService.calcular(detalle.getFechaRenovacion()));

            boolean duplicado = false;
            // El equipo anterior (vigente o vencido) queda reemplazado por el nuevo.
            for (DetalleEntrega anterior : detalleRepository.findByEntregaTrabajadorIdTrabajadorAndEppIdEppAndEstadoVigenciaIn(
                    trabajador.getIdTrabajador(), epp.getIdEpp(), REEMPLAZABLES)) {
                duplicado |= anterior.getEstadoVigencia().isEnUso();
                anterior.setEstadoVigencia(EstadoVigencia.RENOVADO);
                historialRepository.save(new HistorialEpp(trabajador, anterior, HistorialEpp.RENOVADO,
                        "Reemplazado por nueva entrega del " + form.getFechaEntrega()));
            }
            if (duplicado) {
                detalle.setJustificacionDuplicado(item.getJustificacion().trim());
            }
            boolean noExigido = !exigidos.contains(epp.getIdEpp());
            if (noExigido) {
                detalle.setJustificacionNoExigido(item.getJustificacion().trim());
            }
            resumen.append(" ").append(epp.getNombre()).append(" x").append(item.getCantidad())
                    .append(duplicado ? " [duplicado justificado: " + item.getJustificacion().trim() + "]" : "")
                    .append(noExigido ? " [no exigido por la matriz: " + item.getJustificacion().trim() + "]" : "")
                    .append(";");
            entrega.agregarDetalle(detalle);
            detalleRepository.save(detalle);
            List<String> codigos = inventarioService.asignar(detalle, trabajador, nombreUsuario);
            entrega.getCodigosAsignados().addAll(codigos);
            if (codigos.size() < detalle.getCantidad()) {
                entrega.getSinStock().add(epp.getNombre() + " (" + (detalle.getCantidad() - codigos.size()) + ")");
            }
            historialRepository.save(new HistorialEpp(trabajador, detalle,
                    duplicado ? HistorialEpp.ENTREGA_DUPLICADA : HistorialEpp.ENTREGA,
                    duplicado ? "Justificación: " + detalle.getJustificacionDuplicado()
                            : noExigido ? "No exigido por la matriz. Justificación: " + detalle.getJustificacionNoExigido()
                            : form.getMotivo().getEtiqueta()));
        }
        auditoriaService.registrar(nombreUsuario, "ENTREGA_REGISTRADA", "entrega", entrega.getIdEntrega(),
                resumen.toString());
        return entrega;
    }

    /** Anula un EPP entregado por error. No se borra: queda la corrección con el valor anterior (RN-05). */
    @Transactional
    public void anularDetalle(Integer idDetalle, String motivo, String nombreUsuario) {
        if (motivo == null || motivo.isBlank()) {
            throw new ReglaNegocioException("Indique el motivo de la anulación.");
        }
        if (motivo.trim().length() > 450) {
            throw new ReglaNegocioException("El motivo admite hasta 450 caracteres.");
        }
        DetalleEntrega detalle = obtenerDetalle(idDetalle);
        if (detalle.getEstadoVigencia() == EstadoVigencia.ANULADO) {
            throw new ReglaNegocioException("El registro ya se encuentra anulado.");
        }
        Usuario usuario = usuarioRepository.findByNombreUsuario(nombreUsuario)
                .orElseThrow(() -> new ReglaNegocioException("Usuario no encontrado."));
        String valorAnterior = "estado=%s; cantidad=%d; fecha_renovacion=%s".formatted(
                detalle.getEstadoVigencia(), detalle.getCantidad(), detalle.getFechaRenovacion());
        boolean estabaVigente = REEMPLAZABLES.contains(detalle.getEstadoVigencia());
        correccionRepository.save(new CorreccionEntrega(detalle, usuario, valorAnterior, motivo.trim()));
        detalle.setEstadoVigencia(EstadoVigencia.ANULADO);
        Trabajador trabajador = detalle.getEntrega().getTrabajador();
        historialRepository.save(new HistorialEpp(trabajador, detalle, HistorialEpp.ANULACION, motivo.trim()));
        inventarioService.liberar(detalle, nombreUsuario);
        auditoriaService.registrar(nombreUsuario, "ENTREGA_ANULADA", "detalle_entrega", idDetalle,
                "DNI " + trabajador.getDni() + "; " + detalle.getEpp().getNombre() + "; valor anterior: " + valorAnterior
                        + "; motivo: " + motivo.trim());
        if (estabaVigente) {
            restaurarReemplazado(trabajador, detalle);
        }
    }

    /**
     * Si la entrega anulada había reemplazado a otra, esa vuelve a estar en uso: de lo contrario el trabajador
     * quedaría sin cobertura por un error de registro. Solo se restaura la más reciente y si no hay otra vigente.
     */
    private void restaurarReemplazado(Trabajador trabajador, DetalleEntrega anulado) {
        Integer idTrabajador = trabajador.getIdTrabajador();
        Integer idEpp = anulado.getEpp().getIdEpp();
        boolean hayOtraEnUso = !detalleRepository
                .findByEntregaTrabajadorIdTrabajadorAndEppIdEppAndEstadoVigenciaIn(idTrabajador, idEpp, REEMPLAZABLES)
                .isEmpty();
        if (hayOtraEnUso) {
            return;
        }
        detalleRepository.findByEntregaTrabajadorIdTrabajadorAndEppIdEppAndEstadoVigenciaIn(
                        idTrabajador, idEpp, EnumSet.of(EstadoVigencia.RENOVADO)).stream()
                .max(java.util.Comparator.comparing((DetalleEntrega d) -> d.getEntrega().getFechaEntrega())
                        .thenComparing(DetalleEntrega::getIdDetalle))
                .ifPresent(previo -> {
                    previo.setEstadoVigencia(vigenciaService.calcular(previo.getFechaRenovacion()));
                    historialRepository.save(new HistorialEpp(trabajador, previo, HistorialEpp.RESTAURADO,
                            "Restaurado por anulación de la entrega posterior"));
                });
    }
}
