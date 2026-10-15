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
    private final VigenciaService vigenciaService;

    public EntregaService(EntregaRepository entregaRepository,
                          DetalleEntregaRepository detalleRepository,
                          HistorialEppRepository historialRepository,
                          CorreccionEntregaRepository correccionRepository,
                          TrabajadorRepository trabajadorRepository,
                          EppRepository eppRepository,
                          UsuarioRepository usuarioRepository,
                          VigenciaService vigenciaService) {
        this.entregaRepository = entregaRepository;
        this.detalleRepository = detalleRepository;
        this.historialRepository = historialRepository;
        this.correccionRepository = correccionRepository;
        this.trabajadorRepository = trabajadorRepository;
        this.eppRepository = eppRepository;
        this.usuarioRepository = usuarioRepository;
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

    /** Detalle de entrega por id, para anular o mostrar. */
    @Transactional(readOnly = true)
    public DetalleEntrega obtenerDetalle(Integer idDetalle) {
        return detalleRepository.findById(idDetalle)
                .orElseThrow(() -> new ReglaNegocioException("Registro de entrega no encontrado."));
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
        if (seleccionados.stream().anyMatch(i -> i.getCantidad() == null || i.getCantidad() < 1)) {
            throw new ReglaNegocioException("La cantidad de cada EPP debe ser al menos 1.");
        }
        if (!duplicadosSinJustificar(form).isEmpty()) {
            throw new ReglaNegocioException("Hay EPP vigentes sin justificación de entrega duplicada.");
        }
        Trabajador trabajador = trabajadorRepository.findById(form.getIdTrabajador())
                .orElseThrow(() -> new ReglaNegocioException("Trabajador no encontrado."));
        if (!trabajador.getEstado().isLaborando()) {
            throw new ReglaNegocioException("El trabajador está cesado; registre su reingreso antes de entregarle EPP.");
        }
        Usuario responsable = usuarioRepository.findByNombreUsuario(nombreUsuario)
                .orElseThrow(() -> new ReglaNegocioException("Usuario no encontrado."));

        Entrega entrega = new Entrega();
        entrega.setTrabajador(trabajador);
        entrega.setSede(trabajador.getSede());
        entrega.setUsuario(responsable);
        entrega.setFechaEntrega(form.getFechaEntrega());
        entrega.setMotivo(form.getMotivo());
        entregaRepository.save(entrega);

        for (ItemEntregaForm item : seleccionados) {
            Epp epp = eppRepository.findById(item.getIdEpp())
                    .orElseThrow(() -> new ReglaNegocioException("EPP no encontrado."));
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
            entrega.agregarDetalle(detalle);
            detalleRepository.save(detalle);
            historialRepository.save(new HistorialEpp(trabajador, detalle,
                    duplicado ? HistorialEpp.ENTREGA_DUPLICADA : HistorialEpp.ENTREGA,
                    duplicado ? "Justificación: " + detalle.getJustificacionDuplicado() : form.getMotivo().getEtiqueta()));
        }
        return entrega;
    }

    /** Anula un EPP entregado por error. No se borra: queda la corrección con el valor anterior (RN-05). */
    @Transactional
    public void anularDetalle(Integer idDetalle, String motivo, String nombreUsuario) {
        if (motivo == null || motivo.isBlank()) {
            throw new ReglaNegocioException("Indique el motivo de la anulación.");
        }
        DetalleEntrega detalle = detalleRepository.findById(idDetalle)
                .orElseThrow(() -> new ReglaNegocioException("Registro de entrega no encontrado."));
        if (detalle.getEstadoVigencia() == EstadoVigencia.ANULADO) {
            throw new ReglaNegocioException("El registro ya se encuentra anulado.");
        }
        Usuario usuario = usuarioRepository.findByNombreUsuario(nombreUsuario)
                .orElseThrow(() -> new ReglaNegocioException("Usuario no encontrado."));
        String valorAnterior = "estado=%s; cantidad=%d; fecha_renovacion=%s".formatted(
                detalle.getEstadoVigencia(), detalle.getCantidad(), detalle.getFechaRenovacion());
        correccionRepository.save(new CorreccionEntrega(detalle, usuario, valorAnterior, motivo.trim()));
        detalle.setEstadoVigencia(EstadoVigencia.ANULADO);
        historialRepository.save(new HistorialEpp(detalle.getEntrega().getTrabajador(), detalle,
                HistorialEpp.ANULACION, motivo.trim()));
    }
}
