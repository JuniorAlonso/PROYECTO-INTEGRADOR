package pe.utp.epptrack.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.dto.EstadoDotacion;
import pe.utp.epptrack.dto.EvaluacionDotacion;
import pe.utp.epptrack.dto.EvaluacionDotacion.*;
import pe.utp.epptrack.dto.ItemDotacion;
import pe.utp.epptrack.model.*;
import pe.utp.epptrack.repository.DetalleEntregaRepository;
import pe.utp.epptrack.repository.MatrizAsignacionRepository;
import pe.utp.epptrack.repository.PeriodoLaboralRepository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Motor de dotación (RF-22, RF-23, RF-24, RN-01, RN-06): decide qué le falta entregar a un trabajador y explica por qué.
 * <p>
 * Entradas: actividades vigentes del trabajador, reglas de la matriz para esas actividades, entregas en uso
 * y vencidas, periodo laboral actual, versión de la matriz y posibles fichas duplicadas.
 * <p>
 * Reglas que aplica, en orden:
 * <ol>
 *   <li>Sin periodo activo (cesado) o sin actividad → no se puede evaluar (bloqueo).</li>
 *   <li>Actividad sin EPP en la matriz → no se puede decidir; se avisa a SST (bloqueo).</li>
 *   <li>Por cada EPP obligatorio: en uso con cantidad suficiente = cubierto; cantidad menor = parcial
 *       (falta la diferencia); sin uso pero con una entrega vencida = vencido; sin registro = faltante.</li>
 *   <li>Dotación neta = suma de lo que falta de cada EPP obligatorio. Lo vigente NO se vuelve a entregar.</li>
 *   <li>Reincorporado con EPP vigente de una campaña anterior → se pide confirmar físicamente que lo conserva.</li>
 * </ol>
 * El resultado no autoriza el inicio de labores: los criterios de habilitación los debe aprobar SST.
 */
@Service
@Transactional(readOnly = true)
public class MotorDotacionService {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final DotacionService dotacionService;
    private final DetalleEntregaRepository detalleRepository;
    private final MatrizAsignacionRepository matrizRepository;
    private final PeriodoLaboralRepository periodoRepository;
    private final MatrizVersionService versionService;
    private final IdentidadService identidadService;

    public MotorDotacionService(DotacionService dotacionService, DetalleEntregaRepository detalleRepository,
                                MatrizAsignacionRepository matrizRepository, PeriodoLaboralRepository periodoRepository,
                                MatrizVersionService versionService, IdentidadService identidadService) {
        this.dotacionService = dotacionService;
        this.detalleRepository = detalleRepository;
        this.matrizRepository = matrizRepository;
        this.periodoRepository = periodoRepository;
        this.versionService = versionService;
        this.identidadService = identidadService;
    }

    public EvaluacionDotacion evaluar(Trabajador t) {
        EstadoDotacion dotacion = dotacionService.estadoDotacion(t);
        MatrizVersionService.EstadoMatriz matriz = versionService.estado();
        String version = matriz.getEtiqueta();
        List<Alerta> alertas = new ArrayList<>();
        List<Verificacion> verificaciones = new ArrayList<>();
        List<Pendiente> pendientes = new ArrayList<>();

        if (!t.getEstado().isLaborando()) {
            alertas.add(new Alerta(Nivel.BLOQUEO, "CESADO",
                    "El trabajador está cesado. Registre su reingreso antes de evaluar o entregar EPP (RN-04)."));
        }
        if (dotacion.actividades().isEmpty() && t.getEstado().isLaborando()) {
            alertas.add(new Alerta(Nivel.BLOQUEO, "SIN_ACTIVIDAD",
                    "No tiene actividad asignada: sin actividad no se puede saber qué EPP le corresponde (RN-01)."));
        }
        for (Actividad a : dotacion.actividades()) {
            if (matrizRepository.findByActividadIdActividadOrderByEppNombreAsc(a.getIdActividad()).isEmpty()) {
                alertas.add(new Alerta(Nivel.BLOQUEO, "ACTIVIDAD_SIN_MATRIZ", "La actividad \"" + a.getNombre()
                        + "\" no tiene EPP en la matriz. Solicite a SST que la complete antes de entregar."));
            }
        }
        if (matriz.vigente() == null) {
            alertas.add(new Alerta(Nivel.ADVERTENCIA, "MATRIZ_SIN_VERSION",
                    "La matriz no tiene una versión publicada: la evaluación usa reglas no oficializadas."));
        } else if (matriz.cambiosSinPublicar()) {
            alertas.add(new Alerta(Nivel.ADVERTENCIA, "MATRIZ_CAMBIOS",
                    "La matriz tiene cambios que aún no se publican sobre la versión " + matriz.vigente().getCodigo() + "."));
        }
        List<Trabajador> similares = identidadService.posiblesDuplicados(t);
        if (!similares.isEmpty()) {
            alertas.add(new Alerta(Nivel.ADVERTENCIA, "POSIBLE_DUPLICADO", "Hay otra ficha con datos muy parecidos (DNI "
                    + String.join(", ", similares.stream().map(Trabajador::getDni).toList())
                    + "). Si es la misma persona, su historial está partido: verifique el DNI antes de entregar."));
        }

        LocalDate inicioPeriodo = periodoRepository.findByTrabajadorIdTrabajadorAndFechaCeseIsNull(t.getIdTrabajador())
                .stream().map(PeriodoLaboral::getFechaIngreso).min(LocalDate::compareTo).orElse(null);
        Set<Integer> exigidos = new HashSet<>();

        for (ItemDotacion item : dotacion.items()) {
            Epp epp = item.epp();
            exigidos.add(epp.getIdEpp());
            int requerida = item.cantidadRequerida();
            DetalleEntrega uso = item.enUso();
            int enPoder = item.enPoder();
            String regla = "RN-01 · matriz " + version + ": " + (item.obligatorio() ? "obligatorio" : "opcional")
                    + ", cantidad " + requerida;

            if (!item.obligatorio()) {
                verificaciones.add(new Verificacion(epp, false, requerida, enPoder, EstadoItem.OPCIONAL, regla,
                        uso == null ? "Opcional según la matriz; no se exige para cumplir."
                                : "Opcional; tiene " + enPoder + " vigente(s) hasta " + F.format(uso.getFechaRenovacion()) + ".",
                        uso));
                continue;
            }
            if (uso != null && enPoder >= requerida) {
                EstadoItem estado = uso.getEstadoVigencia() == EstadoVigencia.POR_VENCER ? EstadoItem.POR_VENCER : EstadoItem.CUBIERTO;
                String texto = "Entregado el " + F.format(uso.getEntrega().getFechaEntrega()) + ", vigente hasta "
                        + F.format(uso.getFechaRenovacion()) + ". No se vuelve a entregar.";
                if (t.getEstado() == EstadoTrabajador.REINCORPORADO && inicioPeriodo != null
                        && uso.getEntrega().getFechaEntrega().isBefore(inicioPeriodo)) {
                    texto += " Se entregó en una campaña anterior: confirme físicamente que lo conserva.";
                    alertas.add(new Alerta(Nivel.ADVERTENCIA, "CONFIRMAR_CONSERVACION", epp.getNombre()
                            + " figura vigente desde una campaña anterior (" + F.format(uso.getEntrega().getFechaEntrega())
                            + "). Si el trabajador ya no lo tiene, entréguelo con motivo Pérdida y justificación."));
                }
                verificaciones.add(new Verificacion(epp, true, requerida, enPoder, estado, regla, texto, uso));
            } else if (uso != null) {
                int falta = requerida - enPoder;
                verificaciones.add(new Verificacion(epp, true, requerida, enPoder, EstadoItem.PARCIAL, regla,
                        "Tiene " + enPoder + " de " + requerida + " exigidos (entrega incompleta). Faltan " + falta + ".", uso));
                pendientes.add(new Pendiente(epp, falta, "Completar cantidad exigida"));
            } else {
                Optional<DetalleEntrega> vencida = detalleRepository
                        .findByEntregaTrabajadorIdTrabajadorAndEppIdEppAndEstadoVigenciaIn(t.getIdTrabajador(),
                                epp.getIdEpp(), EnumSet.of(EstadoVigencia.VENCIDO)).stream()
                        .max(Comparator.comparing(DetalleEntrega::getFechaRenovacion));
                if (vencida.isPresent()) {
                    verificaciones.add(new Verificacion(epp, true, requerida, 0, EstadoItem.VENCIDO, regla,
                            "La última entrega venció el " + F.format(vencida.get().getFechaRenovacion())
                                    + " (vida útil " + epp.getVidaUtilDias() + " días). Requiere renovación (RN-03).", vencida.get()));
                    pendientes.add(new Pendiente(epp, requerida, "Renovación por vencimiento"));
                } else {
                    verificaciones.add(new Verificacion(epp, true, requerida, 0, EstadoItem.FALTANTE, regla,
                            "No hay ninguna entrega vigente registrada de este EPP.", null));
                    pendientes.add(new Pendiente(epp, requerida, "Dotación inicial"));
                }
            }
        }

        // EPP en uso que la actividad actual ya no exige (por ejemplo, tras un cambio de actividad).
        detalleRepository.findByEntregaTrabajadorIdTrabajadorAndEstadoVigenciaIn(t.getIdTrabajador(),
                        EnumSet.of(EstadoVigencia.VIGENTE, EstadoVigencia.POR_VENCER)).stream()
                .filter(d -> !exigidos.contains(d.getEpp().getIdEpp()))
                .forEach(d -> verificaciones.add(new Verificacion(d.getEpp(), false, 0, d.getCantidad(),
                        EstadoItem.NO_EXIGIDO, "RN-06 · no figura en la matriz de su actividad actual",
                        "Lo tiene en uso desde el " + F.format(d.getEntrega().getFechaEntrega())
                                + "; la actividad actual no lo exige. No genera pendiente.", d)));

        Resultado resultado;
        if (alertas.stream().anyMatch(a -> a.nivel() == Nivel.BLOQUEO)) {
            resultado = Resultado.NO_EVALUABLE;
        } else {
            resultado = pendientes.isEmpty() ? Resultado.CUMPLE : Resultado.NO_CUMPLE;
        }
        return new EvaluacionDotacion(t, dotacion.actividades(), version, resultado, verificaciones, pendientes, alertas);
    }
}
