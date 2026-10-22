package pe.utp.epptrack.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.model.DetalleEntrega;
import pe.utp.epptrack.model.Epp;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Radar de reposición: proyecta cuántas unidades de cada EPP habrá que reponer en los próximos 90 días
 * (R-03 mirando hacia adelante), para comprar con anticipación y repartir las entregas en el tiempo.
 */
@Service
@Transactional(readOnly = true)
public class RadarService {

    public static final int HORIZONTE_DIAS = 90;
    private static final int SEMANAS = 13;

    /** Unidades a reponer de un EPP, por ventana de tiempo. */
    public record FilaEpp(Epp epp, long vencidos, long hasta30, long hasta60, long hasta90) {
        public long getTotal() { return vencidos + hasta30 + hasta60 + hasta90; }
    }

    public record Semana(LocalDate inicio, long unidades) {}

    public record Radar(List<FilaEpp> filas, List<Semana> semanas, long vencidos, long proximos30, long total,
                        long maxSemana, long maxFila, Semana pico) {}

    private final VigenciaService vigenciaService;

    public RadarService(VigenciaService vigenciaService) {
        this.vigenciaService = vigenciaService;
    }

    public Radar calcular(Integer idSede) {
        LocalDate hoy = LocalDate.now();
        List<DetalleEntrega> detalles = vigenciaService.renovacionesHasta(HORIZONTE_DIAS, idSede, null);

        Map<Epp, long[]> porEpp = new LinkedHashMap<>();
        long[] porSemana = new long[SEMANAS];
        LocalDate lunes = hoy.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for (DetalleEntrega d : detalles) {
            long unidades = d.getCantidad();
            LocalDate fecha = d.getFechaRenovacion();
            long[] filas = porEpp.computeIfAbsent(d.getEpp(), e -> new long[4]);
            filas[ventana(hoy, fecha)] += unidades;
            if (!fecha.isBefore(hoy)) {
                int semana = (int) ChronoUnit.WEEKS.between(lunes, fecha.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
                if (semana >= 0 && semana < SEMANAS) {
                    porSemana[semana] += unidades;
                }
            }
        }
        return armar(porEpp, porSemana, lunes);
    }

    /** 0 = ya vencido, 1 = hasta 30 días, 2 = hasta 60, 3 = hasta 90. */
    private static int ventana(LocalDate hoy, LocalDate fecha) {
        if (fecha.isBefore(hoy)) return 0;
        long dias = ChronoUnit.DAYS.between(hoy, fecha);
        return dias <= 30 ? 1 : dias <= 60 ? 2 : 3;
    }

    private Radar armar(Map<Epp, long[]> porEpp, long[] porSemana, LocalDate lunes) {
        List<FilaEpp> filas = porEpp.entrySet().stream()
                .map(e -> new FilaEpp(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]))
                .sorted(Comparator.comparingLong(FilaEpp::getTotal).reversed()
                        .thenComparing(f -> f.epp().getNombre()))
                .toList();
        List<Semana> semanas = new ArrayList<>();
        for (int i = 0; i < SEMANAS; i++) {
            semanas.add(new Semana(lunes.plusWeeks(i), porSemana[i]));
        }
        Semana pico = semanas.stream().max(Comparator.comparingLong(Semana::unidades)).orElse(null);
        long maxSemana = Math.max(1, pico == null ? 1 : pico.unidades());
        long maxFila = filas.isEmpty() ? 1 : Math.max(1, filas.get(0).getTotal());
        return new Radar(filas, semanas,
                filas.stream().mapToLong(FilaEpp::vencidos).sum(),
                filas.stream().mapToLong(FilaEpp::hasta30).sum(),
                filas.stream().mapToLong(FilaEpp::getTotal).sum(),
                maxSemana, maxFila, pico != null && pico.unidades() > 0 ? pico : null);
    }

    /** Lista de compra en CSV con separador «;» y BOM UTF-8 para que Excel la abra bien. */
    public String listaDeCompra(Radar radar) {
        StringBuilder sb = new StringBuilder("﻿EPP;Categoría;Ya vencidos;Hasta 30 días;31 a 60 días;61 a 90 días;Total a reponer\r\n");
        radar.filas().forEach(f -> sb.append(celda(f.epp().getNombre())).append(';')
                .append(celda(f.epp().getCategoria().getNombre())).append(';')
                .append(f.vencidos()).append(';').append(f.hasta30()).append(';')
                .append(f.hasta60()).append(';').append(f.hasta90()).append(';').append(f.getTotal()).append("\r\n"));
        return sb.toString();
    }

    private static String celda(String valor) {
        String v = valor.replace("\"", "\"\"");
        return v.contains(";") || v.contains("\"") ? "\"" + v + "\"" : v;
    }
}
