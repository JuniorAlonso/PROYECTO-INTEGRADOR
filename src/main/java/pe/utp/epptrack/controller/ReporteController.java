package pe.utp.epptrack.controller;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import pe.utp.epptrack.dto.Reporte;
import pe.utp.epptrack.repository.CategoriaEppRepository;
import pe.utp.epptrack.repository.SedeRepository;
import pe.utp.epptrack.service.ReglaNegocioException;
import pe.utp.epptrack.service.ReporteService;
import pe.utp.epptrack.service.ExcelReporteService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Reportes R-01 a R-04 con vista imprimible (PDF desde el navegador) y exportación a Excel (CSV) (CU-08). */
@Controller
@RequestMapping("/reportes")
public class ReporteController {

    private final ReporteService reporteService;
    private final SedeRepository sedeRepository;
    private final CategoriaEppRepository categoriaRepository;
    private final ExcelReporteService excelService;

    public ReporteController(ReporteService reporteService, SedeRepository sedeRepository,
                             CategoriaEppRepository categoriaRepository, ExcelReporteService excelService) {
        this.excelService = excelService;
        this.reporteService = reporteService;
        this.sedeRepository = sedeRepository;
        this.categoriaRepository = categoriaRepository;
    }

    @GetMapping
    public String indice(Model model) {
        model.addAttribute("sedes", sedeRepository.findAllByOrderByNombreAsc());
        model.addAttribute("categorias", categoriaRepository.findAllByOrderByNombreAsc());
        return "reportes/indice";
    }

    @GetMapping("/{codigo}")
    public String ver(@PathVariable String codigo, Filtros f, Authentication auth, Model model) {
        Reporte reporte;
        try {
            reporte = generar(codigo, f);
        } catch (ReglaNegocioException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("sedes", sedeRepository.findAllByOrderByNombreAsc());
            model.addAttribute("categorias", categoriaRepository.findAllByOrderByNombreAsc());
            return "reportes/indice";
        }
        model.addAttribute("reporte", reporte);
        model.addAttribute("usuario", auth.getName());
        model.addAttribute("generado", LocalDateTime.now());
        model.addAttribute("pie", Reporte.PIE);
        return "reportes/ver";
    }

    @GetMapping(value = "/{codigo}.csv", produces = "text/csv")
    public ResponseEntity<byte[]> csv(@PathVariable String codigo, Filtros f, Authentication auth) {
        Reporte reporte;
        try {
            reporte = generar(codigo, f);
        } catch (ReglaNegocioException e) {
            return ResponseEntity.badRequest().build();
        }
        String ahora = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(reporte.codigo() + "-" + LocalDate.now() + ".csv").build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(reporte.aCsv(auth.getName(), ahora).getBytes(StandardCharsets.UTF_8));
    }

    /** Excel nativo con Apache POI (RF-12). */
    @GetMapping(value = "/{codigo}.xlsx")
    public ResponseEntity<byte[]> xlsx(@PathVariable String codigo, Filtros f, Authentication auth) {
        Reporte reporte;
        try {
            reporte = generar(codigo, f);
        } catch (ReglaNegocioException e) {
            return ResponseEntity.badRequest().build();
        }
        String ahora = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(reporte.codigo() + "-" + LocalDate.now() + ".xlsx").build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excelService.exportar(reporte, auth.getName(), ahora));
    }

    private Reporte generar(String codigo, Filtros f) {
        return switch (codigo.toUpperCase()) {
            case "R01", "R-01" -> reporteService.r01(f.sede, f.estado);
            case "R02", "R-02" -> reporteService.r02(f.sede);
            case "R03", "R-03" -> reporteService.r03(f.dias == null ? 30 : f.dias, f.sede, f.categoria);
            case "R04", "R-04" -> reporteService.r04(f.dni, f.desde, f.hasta);
            default -> throw new ReglaNegocioException("Reporte desconocido: " + codigo);
        };
    }

    /** Filtros opcionales de los reportes (se enlazan desde la query string). */
    public static class Filtros {
        public Integer sede;
        public String estado;
        public Integer dias;
        public Integer categoria;
        public String dni;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) public LocalDate desde;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) public LocalDate hasta;

        public void setSede(Integer v) { sede = v; }
        public void setEstado(String v) { estado = v == null || v.isBlank() ? null : v; }
        public void setDias(Integer v) { dias = v; }
        public void setCategoria(Integer v) { categoria = v; }
        public void setDni(String v) { dni = v; }
        public void setDesde(LocalDate v) { desde = v; }
        public void setHasta(LocalDate v) { hasta = v; }
    }
}
