package pe.utp.epptrack.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.model.Trabajador;
import pe.utp.epptrack.repository.TrabajadorRepository;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Detección de posibles fichas duplicadas de una misma persona (RF-27).
 * <p>
 * El DNI ya es único en la base, pero un error de digitación crea una segunda ficha con otro DNI y el historial
 * queda partido: al reingresar, el sistema no ve lo que la persona ya recibió. Se consideran posibles duplicados
 * las fichas con los mismos apellidos y nombres (sin tildes ni mayúsculas) o con los mismos apellidos y la misma
 * fecha de nacimiento. El sistema solo advierte: fusionar fichas es una decisión humana que queda fuera de alcance.
 */
@Service
public class IdentidadService {

    private final TrabajadorRepository trabajadorRepository;

    public IdentidadService(TrabajadorRepository trabajadorRepository) {
        this.trabajadorRepository = trabajadorRepository;
    }

    @Transactional(readOnly = true)
    public List<Trabajador> posiblesDuplicados(Trabajador t) {
        return posiblesDuplicados(t.getApellidos(), t.getNombres(), t.getFechaNacimiento(), t.getIdTrabajador());
    }

    @Transactional(readOnly = true)
    public List<Trabajador> posiblesDuplicados(String apellidos, String nombres, java.time.LocalDate nacimiento,
                                               Integer excluirId) {
        String ap = normalizar(apellidos), no = normalizar(nombres);
        if (ap.isEmpty()) return List.of();
        return trabajadorRepository.findAll().stream()
                .filter(o -> !Objects.equals(o.getIdTrabajador(), excluirId))
                .filter(o -> {
                    boolean mismosApellidos = ap.equals(normalizar(o.getApellidos()));
                    boolean mismoNombre = mismosApellidos && no.equals(normalizar(o.getNombres()));
                    boolean mismaFecha = mismosApellidos && nacimiento != null && nacimiento.equals(o.getFechaNacimiento());
                    return mismoNombre || mismaFecha;
                })
                .toList();
    }

    /**
     * Grupos de fichas con los mismos apellidos y nombres normalizados (RF-27). Agrupa en un solo recorrido
     * (sin comparar todos contra todos) y devuelve solo los grupos de más de una ficha.
     */
    @Transactional(readOnly = true)
    public List<List<Trabajador>> gruposPosiblesDuplicados() {
        return trabajadorRepository.findAll().stream()
                .filter(t -> !normalizar(t.getApellidos()).isEmpty())
                .collect(java.util.stream.Collectors.groupingBy(
                        t -> normalizar(t.getApellidos()) + "|" + normalizar(t.getNombres()),
                        java.util.LinkedHashMap::new, java.util.stream.Collectors.toList()))
                .values().stream().filter(g -> g.size() > 1).toList();
    }

    static String normalizar(String s) {
        if (s == null) return "";
        String sinTildes = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
