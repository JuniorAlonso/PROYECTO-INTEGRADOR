package pe.utp.epptrack.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import pe.utp.epptrack.dto.Reporte;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Exporta los reportes R-01 a R-04 a Excel nativo (.xlsx) con Apache POI (RF-12).
 * A diferencia del CSV, conserva encabezados con formato, ancho de columnas y la huella de auditoría vigente al
 * generar el archivo: con ella se puede comprobar después que la bitácora no cambió desde esa fecha.
 */
@Service
public class ExcelReporteService {

    private final AuditoriaService auditoriaService;

    public ExcelReporteService(AuditoriaService auditoriaService) {
        this.auditoriaService = auditoriaService;
    }

    public byte[] exportar(Reporte reporte, String usuario, String fechaHora) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet hoja = wb.createSheet(reporte.codigo());
            Font negrita = wb.createFont();
            negrita.setBold(true);
            CellStyle titulo = wb.createCellStyle();
            titulo.setFont(negrita);
            CellStyle encabezado = wb.createCellStyle();
            encabezado.setFont(negrita);
            encabezado.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            encabezado.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            encabezado.setBorderBottom(BorderStyle.THIN);

            int r = 0;
            texto(hoja, r++, reporte.codigo() + " - " + reporte.titulo(), titulo);
            texto(hoja, r++, "Generado por " + usuario + " el " + fechaHora, null);
            for (String linea : reporte.subtitulo()) texto(hoja, r++, linea, null);
            r++;
            Row cab = hoja.createRow(r++);
            List<String> columnas = reporte.columnas();
            for (int c = 0; c < columnas.size(); c++) {
                Cell celda = cab.createCell(c);
                celda.setCellValue(columnas.get(c));
                celda.setCellStyle(encabezado);
            }
            for (List<String> fila : reporte.filas()) {
                Row row = hoja.createRow(r++);
                for (int c = 0; c < fila.size(); c++) {
                    // Siempre como texto: evita que un valor que empieza con "=" se interprete como fórmula.
                    row.createCell(c).setCellValue(fila.get(c) == null ? "" : fila.get(c));
                }
            }
            r++;
            texto(hoja, r++, Reporte.PIE, null);
            AuditoriaService.Verificacion v = auditoriaService.verificar();
            texto(hoja, r, v.integra()
                    ? "Bitácora de auditoría íntegra al generar (" + v.registros() + " registros). Última huella: " + v.ultimaHuella()
                    : "ATENCIÓN: la bitácora de auditoría presenta una alteración en el registro N.° " + v.primerRegistroAlterado(), null);
            for (int c = 0; c < columnas.size(); c++) {
                hoja.autoSizeColumn(c);
                hoja.setColumnWidth(c, Math.min(hoja.getColumnWidth(c) + 512, 60 * 256));
            }
            hoja.createFreezePane(0, 0);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar el Excel", e);
        }
    }

    private static void texto(Sheet hoja, int fila, String valor, CellStyle estilo) {
        Cell c = hoja.createRow(fila).createCell(0);
        c.setCellValue(valor);
        if (estilo != null) c.setCellStyle(estilo);
    }
}
