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

    /** Qué hace el sistema con una línea de la entrega y por qué (RF-07, RN-06, RN-08). */
    public enum Decision {
        /** Falta ese EPP y se entrega como máximo lo que falta. */
        NORMAL,
        /** Ya tiene parte de lo exigido y se entrega, como máximo, la diferencia: se suma, no reemplaza. */
        COMPLEMENTO,
        /** Ya tiene vigente todo lo exigido (o algo que la matriz no exige): requiere justificación. */
        DUPLICADO,
        /** La matriz no exige este EPP para la actividad del trabajador: requiere justificación. */
        NO_EXIGIDO,
        /** Se entrega más de lo que falta según la matriz: requiere justificación. */
        EXCESO
    }

    /** Resultado de evaluar un EPP marcado en el formulario frente a la matriz y a lo que el trabajador ya tiene. */
    public record Linea(Integer idEpp, Decision decision, int requerida, int enPoder, int pendiente, int cantidad,
                        boolean enMatriz) {
        public boolean requiereJustificacion() { return decision != Decision.NORMAL && decision != Decision.COMPLEMENTO; }
    }

    /**
     * Evalúa cada EPP seleccionado: compara lo exigido por la matriz, lo que el trabajador tiene vigente (suma de
     * todas sus entregas vigentes) y lo que se quiere entregar. La interfaz solo muestra este resultado; la regla
     * se aplica aquí, en el servidor.
     */
    @Transactional(readOnly = true)
    public List<Linea> evaluarLineas(EntregaForm form) {
        if (form.getIdTrabajador() == null) return List.of();
        Trabajador t = trabajadorRepository.findById(form.getIdTrabajador()).orElse(null);
        if (t == null) return List.of();
        Map<Integer, pe.utp.epptrack.dto.ItemDotacion> deMatriz = new java.util.HashMap<>();
        dotacionService.estadoDotacion(t).items().forEach(i -> deMatriz.put(i.epp().getIdEpp(), i));
        Map<Integer, Integer> vigentes = detalleRepository
                .findByEntregaTrabajadorIdTrabajadorAndEstadoVigenciaIn(t.getIdTrabajador(), EN_USO).stream()
                .collect(Collectors.groupingBy(d -> d.getEpp().getIdEpp(), Collectors.summingInt(DetalleEntrega::getCantidad)));
        List<Linea> lineas = new java.util.ArrayList<>();
        for (ItemEntregaForm i : form.getSeleccionados()) {
            if (i.getIdEpp() == null) continue;
            var regla = deMatriz.get(i.getIdEpp());
            boolean enMatriz = regla != null;
            int requerida = enMatriz ? regla.cantidadRequerida() : 0;
            int enPoder = vigentes.getOrDefault(i.getIdEpp(), 0);
            int pendiente = Math.max(0, requerida - enPoder);
            int cantidad = i.getCantidad() == null ? 0 : i.getCantidad();
            Decision d;
            if (!enMatriz) {
                d = enPoder > 0 ? Decision.DUPLICADO : Decision.NO_EXIGIDO;
            } else if (enPoder >= requerida) {
                d = Decision.DUPLICADO;
            } else if (cantidad > pendiente) {
                d = Decision.EXCESO;
            } else {
                d = enPoder > 0 ? Decision.COMPLEMENTO : Decision.NORMAL;
            }
            lineas.add(new Linea(i.getIdEpp(), d, requerida, enPoder, pendiente, cantidad, enMatriz));
        }
        return lineas;
    }

    private List<Integer> sinJustificar(EntregaForm form, java.util.function.Predicate<Linea> filtro) {
        Map<Integer, ItemEntregaForm> items = form.getSeleccionados().stream()
                .filter(i -> i.getIdEpp() != null)
                .collect(Collectors.toMap(ItemEntregaForm::getIdEpp, Function.identity(), (x, y) -> x));
        return evaluarLineas(form).stream().filter(filtro)
                .filter(l -> items.get(l.idEpp()).getJustificacion() == null
                        || items.get(l.idEpp()).getJustificacion().isBlank())
                .map(Linea::idEpp).toList();
    }

    /** Ids de los EPP que el trabajador ya tiene cubiertos y se quieren entregar de nuevo sin justificar (RF-07). */
    @Transactional(readOnly = true)
    public List<Integer> duplicadosSinJustificar(EntregaForm form) {
        return sinJustificar(form, l -> l.enPoder() > 0 && l.decision() == Decision.DUPLICADO);
    }

    /**
     * Ids de los EPP seleccionados que la matriz NO exige para las actividades del trabajador y no traen
     * justificación (RF-23, RN-06). Un EPP desactivado se ignora aquí (se rechaza luego).
     */
    @Transactional(readOnly = true)
    public List<Integer> noExigidosSinJustificar(EntregaForm form) {
        return sinJustificar(form, l -> !l.enMatriz()).stream()
                .filter(id -> eppRepository.findById(id).map(Epp::isActivo).orElse(false)).toList();
    }

    /** Ids de los EPP cuya cantidad supera lo que falta según la matriz y no traen justificación (RN-08). */
    @Transactional(readOnly = true)
    public List<Integer> excesosSinJustificar(EntregaForm form) {
        return sinJustificar(form, l -> l.decision() == Decision.EXCESO);
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
        if (!excesosSinJustificar(form).isEmpty()) {
            throw new ReglaNegocioException("Hay EPP cuya cantidad supera lo que falta según la matriz; "
                    + "ajuste la cantidad o escriba la justificación del exceso (RN-08).");
        }
        // Cada línea queda evaluada antes de tocar nada: la decisión se toma con el estado previo a esta entrega.
        Map<Integer, Linea> lineas = evaluarLineas(form).stream()
                .collect(Collectors.toMap(Linea::idEpp, Function.identity(), (a, b) -> a));
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

        // Los bloqueos de inventario se toman por adelantado y siempre en orden de EPP (así dos entregas simultáneas
        // no se interbloquean); después se procesa en el orden del formulario.
        seleccionados.stream().map(ItemEntregaForm::getIdEpp).sorted().distinct()
                .forEach(id -> eppRepository.findByIdParaActualizar(id));
        for (ItemEntregaForm item : seleccionados) {
            Epp epp = eppRepository.findById(item.getIdEpp())
                    .orElseThrow(() -> new RecursoNoEncontradoException("EPP no encontrado."));
            if (!epp.isActivo()) {
                throw new ReglaNegocioException("El EPP \"" + epp.getNombre() + "\" está desactivado en el catálogo.");
            }
            DetalleEntrega detalle = new DetalleEntrega(epp, item.getCantidad(),
                    form.getFechaEntrega().plusDays(epp.getVidaUtilDias()));
            detalle.setEstadoVigencia(vigenciaService.calcular(detalle.getFechaRenovacion()));
            Linea linea = lineas.get(epp.getIdEpp());
            detalle.setCantidadExigida(linea == null ? null : linea.requerida());
            boolean complemento = linea != null && linea.decision() == Decision.COMPLEMENTO;
            boolean exceso = linea != null && linea.decision() == Decision.EXCESO;

            boolean duplicado = false;
            // El equipo anterior (vigente o vencido) queda reemplazado por el nuevo. Un complemento NO reemplaza lo
            // que sigue vigente: se suma a ello para llegar a la cantidad exigida.
            for (DetalleEntrega anterior : detalleRepository.findByEntregaTrabajadorIdTrabajadorAndEppIdEppAndEstadoVigenciaIn(
                    trabajador.getIdTrabajador(), epp.getIdEpp(), REEMPLAZABLES)) {
                if ((complemento || exceso) && anterior.getEstadoVigencia().isEnUso()) {
                    continue;
                }
                duplicado |= anterior.getEstadoVigencia().isEnUso();
                anterior.setEstadoVigencia(EstadoVigencia.RENOVADO);
                historialRepository.save(new HistorialEpp(trabajador, anterior, HistorialEpp.RENOVADO,
                        "Reemplazado por nueva entrega del " + form.getFechaEntrega()));
            }
            if (duplicado) {
                detalle.setJustificacionDuplicado(item.getJustificacion().trim());
            }
            boolean noExigido = linea != null && !linea.enMatriz();
            if (noExigido) {
                detalle.setJustificacionNoExigido(item.getJustificacion().trim());
            }
            if (exceso) {
                detalle.setJustificacionExceso(item.getJustificacion().trim());
            }
            resumen.append(" ").append(epp.getNombre()).append(" x").append(item.getCantidad())
                    .append(duplicado ? " [duplicado justificado: " + item.getJustificacion().trim() + "]" : "")
                    .append(noExigido ? " [no exigido por la matriz: " + item.getJustificacion().trim() + "]" : "")
                    .append(exceso ? " [exceso justificado: " + item.getJustificacion().trim() + "]" : "")
                    .append(complemento ? " [complemento de entrega parcial]" : "")
                    .append(";");
            entrega.agregarDetalle(detalle);
            detalleRepository.save(detalle);
            int cubierto;
            if (epp.isDesechable()) {
                // Consumible: se descuenta del saldo de lotes; no hay códigos por unidad.
                cubierto = inventarioService.consumirConsumible(detalle, trabajador, nombreUsuario);
            } else {
                List<String> codigos = inventarioService.asignar(detalle, trabajador, nombreUsuario);
                entrega.getCodigosAsignados().addAll(codigos);
                cubierto = codigos.size();
            }
            if (cubierto < detalle.getCantidad()) {
                entrega.getSinStock().add(epp.getNombre() + " (" + (detalle.getCantidad() - cubierto) + ")");
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
