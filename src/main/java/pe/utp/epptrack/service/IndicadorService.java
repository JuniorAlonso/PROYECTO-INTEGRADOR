package pe.utp.epptrack.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.dto.EstadoDotacion;
import pe.utp.epptrack.dto.ItemDotacion;
import pe.utp.epptrack.model.DetalleEntrega;
import pe.utp.epptrack.model.EstadoVigencia;
import pe.utp.epptrack.model.MotivoEntrega;
import pe.utp.epptrack.repository.DetalleEntregaRepository;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Indicadores verificables (RF-11, RF-12, RN-01, RN-06, RN-08): cada cifra se calcula con datos reales y muestra su
 * numerador, denominador, fórmula, fuente y periodo. Si no hay datos suficientes el valor es nulo (no se inventa).
 * No se compara contra el pretest del informe.
 */
@Service
@Transactional(readOnly = true)
public class IndicadorService {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** valor nulo = no calculable. unidad: "%" o "casos". */
    public record Indicador(String nombre, Double valor, Long numerador, Long denominador, String unidad, String formula,
                            String fuente, String periodo, String nota, boolean suficiente) {}

    /** Una fila de la evolución mensual; porcentajeConforme nulo si el mes no tuvo entregas. */
    public record MesEvolucion(YearMonth mes, long entregas, long conExcepcion, Double porcentajeConforme) {}

    public record Panel(List<Indicador> indicadores, List<MesEvolucion> evolucion, boolean evolucionSuficiente,
                        LocalDate desde, LocalDate hasta) {}

    private final DotacionService dotacionService;
    private final VigenciaService vigenciaService;
    private final DetalleEntregaRepository detalleRepository;
    private final ExcepcionService excepcionService;

    public IndicadorService(DotacionService dotacionService, VigenciaService vigenciaService,
                            DetalleEntregaRepository detalleRepository, ExcepcionService excepcionService) {
        this.dotacionService = dotacionService;
        this.vigenciaService = vigenciaService;
        this.detalleRepository = detalleRepository;
        this.excepcionService = excepcionService;
    }

    public Panel calcular(int dias) {
        LocalDate hasta = LocalDate.now();
        LocalDate desde = hasta.minusDays(dias);
        String periodo = F.format(desde) + " al " + F.format(hasta) + " (" + dias + " días)";
        String hoy = "Al " + F.format(hasta);
        List<EstadoDotacion> dotaciones = dotacionService.estadoDotacionTodos(null);
        List<DetalleEntrega> delPeriodo = noAnulados(detalleRepository.findByEntregaFechaEntregaBetween(desde, hasta));

        List<Indicador> lista = new ArrayList<>();
        lista.add(dotacionCompleta(dotaciones, hoy));
        lista.add(coberturaUnidades(dotaciones, hoy));
        lista.add(entregasConformes(delPeriodo, periodo));
        lista.add(cantidadDistinta(delPeriodo, periodo));
        lista.add(vencidos(hoy));
        lista.add(porVencer(hoy));
        lista.add(reposiciones(delPeriodo, periodo));
        lista.add(excepcionesPendientes(dias, hoy));
        List<MesEvolucion> evolucion = evolucionMensual(hasta);
        long mesesConDatos = evolucion.stream().filter(m -> m.entregas() > 0).count();
        return new Panel(lista, evolucion, mesesConDatos >= 2, desde, hasta);
    }

    private static List<DetalleEntrega> noAnulados(List<DetalleEntrega> detalles) {
        return detalles.stream().filter(d -> d.getEstadoVigencia() != EstadoVigencia.ANULADO).toList();
    }

    private static boolean conExcepcion(DetalleEntrega d) {
        return d.isNoExigidoJustificado() || d.isDuplicadoJustificado() || d.isExcesoJustificado();
    }

    private static Indicador porcentaje(String nombre, long num, long den, String formula, String fuente, String periodo,
                                        String nota) {
        boolean ok = den > 0;
        return new Indicador(nombre, ok ? num * 100.0 / den : null, num, den, "%", formula, fuente, periodo,
                ok ? nota : "Datos insuficientes: el denominador es 0. " + nota, ok);
    }

    private Indicador dotacionCompleta(List<EstadoDotacion> dotaciones, String periodo) {
        long completas = dotaciones.stream().filter(EstadoDotacion::isCompleta).count();
        return porcentaje("Dotación completa hoy", completas, dotaciones.size(),
                "Trabajadores laborando con todos sus EPP obligatorios cubiertos / trabajadores laborando",
                "Matriz de EPP y entregas vigentes (DotacionService)", periodo,
                "Un trabajador sin actividad asignada se cuenta como no completo. Los cesados no entran.");
    }

    private Indicador coberturaUnidades(List<EstadoDotacion> dotaciones, String periodo) {
        long cubiertas = 0, exigidas = 0;
        for (EstadoDotacion ed : dotaciones) {
            for (ItemDotacion i : ed.items()) {
                if (!i.obligatorio()) continue;
                exigidas += i.cantidadRequerida();
                cubiertas += Math.min(i.enPoder(), i.cantidadRequerida());
            }
        }
        return porcentaje("Cobertura de unidades exigidas", cubiertas, exigidas,
                "Suma de min(unidades en poder, unidades exigidas) de los EPP obligatorios / suma de unidades exigidas",
                "Matriz de EPP y entregas vigentes (DotacionService)", periodo,
                "Solo cuenta EPP obligatorios de trabajadores laborando; el exceso no compensa faltantes de otro EPP.");
    }

    private Indicador entregasConformes(List<DetalleEntrega> detalles, String periodo) {
        long conformes = detalles.stream().filter(d -> !conExcepcion(d)).count();
        return porcentaje("Entregas conformes con la matriz (periodo)", conformes, detalles.size(),
                "Líneas de entrega no anuladas sin justificación de no exigido, duplicado ni exceso / total de líneas no anuladas",
                "Detalle de entregas (DETALLE_ENTREGA)", periodo,
                "Las entregas hechas antes del APF3 no tienen cantidad exigida registrada y se cuentan solo por las "
                        + "justificaciones registradas; una entrega parcial sin justificación se considera conforme.");
    }

    private Indicador cantidadDistinta(List<DetalleEntrega> detalles, String periodo) {
        List<DetalleEntrega> conExigida = detalles.stream()
                .filter(d -> d.getCantidadExigida() != null && d.getCantidadExigida() > 0).toList();
        long distinta = conExigida.stream().filter(d -> !d.getCantidad().equals(d.getCantidadExigida())).count();
        return porcentaje("Entregas con cantidad distinta de la exigida", distinta, conExigida.size(),
                "Líneas con cantidad entregada distinta de la exigida / líneas con cantidad exigida registrada (mayor a 0)",
                "Detalle de entregas (DETALLE_ENTREGA.cantidad_exigida)", periodo,
                "Se excluyen las líneas sin cantidad exigida (entregas anteriores o EPP que la matriz no exigía).");
    }

    private Indicador vencidos(String periodo) {
        long n = vigenciaService.alertas(null, EstadoVigencia.VENCIDO).size();
        return new Indicador("EPP vencidos hoy", (double) n, n, null, "casos",
                "Conteo de líneas de entrega en estado VENCIDO de trabajadores laborando",
                "Control de vigencia (VigenciaService)", periodo,
                "Es un conteo de líneas, sin denominador; un mismo EPP vencido en varias entregas viejas cuenta por cada línea.", true);
    }

    private Indicador porVencer(String periodo) {
        long n = vigenciaService.alertas(null, EstadoVigencia.POR_VENCER).size();
        return new Indicador("EPP por vencer (≤ " + vigenciaService.getDiasPorVencer() + " días)", (double) n, n, null,
                "casos", "Conteo de líneas de entrega en estado POR_VENCER de trabajadores laborando",
                "Control de vigencia (VigenciaService)", periodo,
                "El umbral de días es el configurado en app.alertas.dias-por-vencer.", true);
    }

    private Indicador reposiciones(List<DetalleEntrega> detalles, String periodo) {
        Set<Integer> entregas = new HashSet<>();
        for (DetalleEntrega d : detalles) {
            MotivoEntrega m = d.getEntrega().getMotivo();
            if (m == MotivoEntrega.RENOVACION || m == MotivoEntrega.DESGASTE || m == MotivoEntrega.PERDIDA) {
                entregas.add(d.getEntrega().getIdEntrega());
            }
        }
        long n = entregas.size();
        return new Indicador("Reposiciones registradas (periodo)", (double) n, n, null, "casos",
                "Entregas con motivo Renovación, Desgaste o Pérdida",
                "Cabecera de entregas (ENTREGA.motivo)", periodo,
                "Conteo de entregas sin denominador; no mide si la reposición fue necesaria.", true);
    }

    private Indicador excepcionesPendientes(int dias, String periodo) {
        long n = excepcionService.listar(null, null, dias).size();
        return new Indicador("Excepciones pendientes", (double) n, n, null, "casos",
                "Total de casos del panel de excepciones (cada tipo, trabajador y EPP una sola vez)",
                "Panel de excepciones (ExcepcionService)", periodo + "; justificaciones de los últimos " + dias + " días",
                "Misma cifra que el panel de excepciones.", true);
    }

    private List<MesEvolucion> evolucionMensual(LocalDate hoy) {
        YearMonth actual = YearMonth.from(hoy);
        YearMonth primero = actual.minusMonths(5);
        List<DetalleEntrega> detalles = noAnulados(detalleRepository.findByEntregaFechaEntregaBetween(primero.atDay(1), hoy));
        Map<YearMonth, List<DetalleEntrega>> porMes = new HashMap<>();
        for (DetalleEntrega d : detalles) {
            porMes.computeIfAbsent(YearMonth.from(d.getEntrega().getFechaEntrega()), k -> new ArrayList<>()).add(d);
        }
        List<MesEvolucion> filas = new ArrayList<>();
        for (YearMonth m = primero; !m.isAfter(actual); m = m.plusMonths(1)) {
            List<DetalleEntrega> del = porMes.getOrDefault(m, List.of());
            long con = del.stream().filter(IndicadorService::conExcepcion).count();
            filas.add(new MesEvolucion(m, del.size(), con,
                    del.isEmpty() ? null : (del.size() - con) * 100.0 / del.size()));
        }
        return filas;
    }
}
