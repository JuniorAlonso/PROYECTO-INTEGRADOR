package pe.utp.epptrack.dto;

import java.util.List;

/** Reporte tabular listo para mostrarse en pantalla, imprimirse (PDF) o exportarse a Excel (CSV). */
public record Reporte(String codigo, String titulo, List<String> subtitulo, List<String> columnas, List<List<String>> filas) {

    public static final String PIE = "Los registros de entrega no pueden eliminarse; toda corrección queda auditada.";

    /** CSV con ';' y BOM UTF-8 para que Excel lo abra con tildes; neutraliza celdas que parecerían fórmulas. */
    public String aCsv(String usuario, String fechaHora) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(celda(codigo + " - " + titulo)).append('\n');
        sb.append(celda("Generado por " + usuario + " el " + fechaHora)).append('\n');
        subtitulo.forEach(l -> sb.append(celda(l)).append('\n'));
        sb.append(String.join(";", columnas.stream().map(Reporte::celda).toList())).append('\n');
        for (List<String> fila : filas) {
            sb.append(String.join(";", fila.stream().map(Reporte::celda).toList())).append('\n');
        }
        sb.append(celda(PIE)).append('\n');
        return sb.toString();
    }

    private static String celda(String valor) {
        String v = valor == null ? "" : valor;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
