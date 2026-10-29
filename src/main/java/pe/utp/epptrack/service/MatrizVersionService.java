package pe.utp.epptrack.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.model.AsignacionActividad;
import pe.utp.epptrack.model.MatrizAsignacion;
import pe.utp.epptrack.model.Trabajador;
import pe.utp.epptrack.model.VersionMatriz;
import pe.utp.epptrack.repository.AsignacionActividadRepository;
import pe.utp.epptrack.repository.MatrizAsignacionRepository;
import pe.utp.epptrack.repository.VersionMatrizRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Versionado de la Matriz de EPP (RF-21, RN-07).
 * <p>
 * Problema que resuelve: la matriz se edita en el sistema y, sin versión, nadie puede probar qué reglas se
 * aplicaron a una entrega pasada ni saber a quién afecta un cambio. Aquí la matriz de trabajo puede modificarse
 * libremente, pero solo cuenta como oficial cuando el administrador la <b>publica</b>: se guarda una copia
 * con huella SHA-256, cada entrega anota el código de la versión vigente y la publicación calcula el impacto
 * (reglas agregadas, retiradas o modificadas, y trabajadores activos cuya dotación cambia).
 */
@Service
public class MatrizVersionService {

    private static final java.util.regex.Pattern CODIGO = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,19}");

    /** Situación de la matriz que se muestra en pantalla. */
    public record EstadoMatriz(VersionMatriz vigente, boolean cambiosSinPublicar, int reglasActuales) {
        public String getEtiqueta() {
            if (vigente == null) return "Sin versión publicada";
            return vigente.getCodigo() + (cambiosSinPublicar ? " + cambios sin publicar" : "");
        }
    }

    /** Resultado de publicar: qué cambió respecto de la versión anterior y a quién afecta. */
    public record Impacto(VersionMatriz version, String versionAnterior, List<String> agregadas, List<String> retiradas,
                          List<String> modificadas, int actividadesAfectadas, List<Trabajador> trabajadoresAfectados) {
        public int getTotalCambios() { return agregadas.size() + retiradas.size() + modificadas.size(); }
    }

    /** Qué cambiaría al publicar hoy la matriz de trabajo. */
    public record VistaPrevia(VersionMatriz vigente, int reglasActuales, boolean hayCambios, List<String> agregadas,
                              List<String> retiradas, List<String> modificadas, int actividadesAfectadas,
                              List<Trabajador> trabajadoresAfectados, long conDotacionIncompleta, List<String> noEvaluable) {
        public int getTotalCambios() { return agregadas.size() + retiradas.size() + modificadas.size(); }
    }

    /** Diferencias de una versión frente a la anterior. */
    public record Comparacion(VersionMatriz version, VersionMatriz anterior, List<String> agregadas,
                              List<String> retiradas, List<String> modificadas) {}

    private final DotacionService dotacionService;
    private final VersionMatrizRepository versionRepository;
    private final MatrizAsignacionRepository matrizRepository;
    private final AsignacionActividadRepository asignacionRepository;
    private final AuditoriaService auditoriaService;

    public MatrizVersionService(VersionMatrizRepository versionRepository, MatrizAsignacionRepository matrizRepository,
                                AsignacionActividadRepository asignacionRepository, AuditoriaService auditoriaService, DotacionService dotacionService) {
        this.dotacionService = dotacionService;
        this.versionRepository = versionRepository;
        this.matrizRepository = matrizRepository;
        this.asignacionRepository = asignacionRepository;
        this.auditoriaService = auditoriaService;
    }

    /** Al arrancar con una matriz cargada y sin versiones, publica la primera para que toda entrega tenga versión. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void publicarVersionInicialSiFalta() {
        if (versionRepository.count() == 0 && matrizRepository.count() > 0) {
            publicar("inicial", "Versión inicial generada al arrancar con la matriz cargada", "sistema");
        }
    }

    @Transactional(readOnly = true)
    public Optional<VersionMatriz> vigente() {
        return versionRepository.findFirstByVigenteTrueOrderByIdVersionDesc();
    }

    @Transactional(readOnly = true)
    public EstadoMatriz estado() {
        List<String> actual = lineasActuales();
        VersionMatriz v = vigente().orElse(null);
        boolean cambios = v == null ? !actual.isEmpty() : !v.getHuella().equals(huella(actual));
        return new EstadoMatriz(v, cambios, actual.size());
    }

    /** Texto que se guarda en cada entrega para saber con qué versión de la matriz se decidió. */
    @Transactional(readOnly = true)
    public String etiquetaParaEntrega() {
        EstadoMatriz e = estado();
        if (e.vigente() == null) return "sin versión";
        return e.vigente().getCodigo() + (e.cambiosSinPublicar() ? " (con cambios no publicados)" : "");
    }

    @Transactional(readOnly = true)
    public List<VersionMatriz> historial() {
        return versionRepository.findAllByOrderByIdVersionDesc();
    }

    @Transactional
    public Impacto publicar(String codigo, String descripcion, String usuario) {
        String cod = codigo == null ? "" : codigo.trim();
        if (!CODIGO.matcher(cod).matches()) {
            throw new ReglaNegocioException("El código de versión admite letras, números, punto y guion (máx. 20), p. ej. v7.");
        }
        if (versionRepository.existsByCodigoIgnoreCase(cod)) {
            throw new ReglaNegocioException("Ya existe una versión con el código " + cod + ".");
        }
        List<String> actual = lineasActuales();
        if (actual.isEmpty()) {
            throw new ReglaNegocioException("La matriz no tiene reglas; no hay nada que publicar.");
        }
        String huella = huella(actual);
        Optional<VersionMatriz> anterior = vigente();
        if (anterior.isPresent() && anterior.get().getHuella().equals(huella)) {
            throw new ReglaNegocioException("La matriz no cambió desde la versión " + anterior.get().getCodigo() + ".");
        }

        Diferencias dif = diferencias(anterior.map(VersionMatriz::getContenido).orElse(null), actual);
        List<String> agregadas = dif.agregadas(), retiradas = dif.retiradas(), modificadas = dif.modificadas();
        Set<Integer> actividades = dif.actividades();

        anterior.ifPresent(v -> v.setVigente(false));
        String desc = descripcion == null || descripcion.isBlank() ? null : descripcion.trim();
        if (desc != null && desc.length() > 300) desc = desc.substring(0, 300);
        VersionMatriz nueva = versionRepository.save(
                new VersionMatriz(cod, desc, usuario, actual.size(), huella, String.join("\n", actual)));

        List<Trabajador> afectados = anterior.isEmpty() ? List.of() : trabajadoresDe(actividades);

        auditoriaService.registrar(usuario, "MATRIZ_PUBLICADA", "version_matriz", nueva.getIdVersion(),
                "Versión " + cod + " (" + actual.size() + " reglas, huella " + nueva.getHuellaCorta() + ")"
                        + anterior.map(v -> " reemplaza a " + v.getCodigo()).orElse("")
                        + "; agregadas=" + agregadas.size() + ", retiradas=" + retiradas.size()
                        + ", modificadas=" + modificadas.size() + ", trabajadores afectados=" + afectados.size());
        return new Impacto(nueva, anterior.map(VersionMatriz::getCodigo).orElse(null), agregadas, retiradas,
                modificadas, anterior.isEmpty() ? 0 : actividades.size(), afectados);
    }

    /** Diferencias entre dos listas de reglas canónicas (compartido por publicar, vista previa y comparación). */
    private record Diferencias(List<String> agregadas, List<String> retiradas, List<String> modificadas,
                               Set<Integer> actividades) {
        boolean vacia() { return agregadas.isEmpty() && retiradas.isEmpty() && modificadas.isEmpty(); }
    }

    private static Diferencias diferencias(String contenidoAntes, List<String> ahoraLineas) {
        Map<String, String> antes = contenidoAntes == null ? Map.of() : porClave(Arrays.asList(contenidoAntes.split("\\n")));
        Map<String, String> ahora = porClave(ahoraLineas);
        List<String> agregadas = new ArrayList<>(), retiradas = new ArrayList<>(), modificadas = new ArrayList<>();
        Set<Integer> actividades = new HashSet<>();
        ahora.forEach((clave, linea) -> {
            String previa = antes.get(clave);
            if (previa == null) agregadas.add(legible(linea));
            else if (!previa.equals(linea)) modificadas.add(legible(previa) + " → " + legible(linea));
            else return;
            actividades.add(idActividad(clave));
        });
        antes.forEach((clave, linea) -> {
            if (!ahora.containsKey(clave)) {
                retiradas.add(legible(linea));
                actividades.add(idActividad(clave));
            }
        });
        return new Diferencias(agregadas, retiradas, modificadas, actividades);
    }

    /** Trabajadores que siguen laborando y tienen asignada alguna de las actividades indicadas. */
    private List<Trabajador> trabajadoresDe(Set<Integer> actividades) {
        return asignacionRepository.findByFechaFinIsNull().stream()
                .filter(a -> actividades.contains(a.getActividad().getIdActividad()))
                .map(AsignacionActividad::getTrabajador)
                .filter(t -> t.getEstado().isLaborando())
                .distinct()
                .sorted(Comparator.comparing(Trabajador::getNombreCompleto))
                .toList();
    }

    /**
     * Vista previa del impacto de publicar la matriz de trabajo (RF-21, RN-07). No crea versiones ni modifica datos.
     * Lo que no puede calcularse se declara en {@code noEvaluable} en lugar de inventarse.
     */
    @Transactional(readOnly = true)
    public VistaPrevia vistaPrevia() {
        VersionMatriz vigente = vigente().orElse(null);
        List<String> actual = lineasActuales();
        Diferencias dif = diferencias(vigente == null ? null : vigente.getContenido(), actual);
        List<Trabajador> afectados = vigente == null ? List.of() : trabajadoresDe(dif.actividades());
        Set<Integer> ids = afectados.stream().map(Trabajador::getIdTrabajador).collect(Collectors.toSet());
        long incompletos = ids.isEmpty() ? 0 : dotacionService.estadoDotacionTodos(null).stream()
                .filter(e -> ids.contains(e.trabajador().getIdTrabajador()) && !e.isCompleta()).count();
        List<String> noEvaluable = new ArrayList<>();
        if (vigente == null) noEvaluable.add("Sin versión publicada previa: no hay base de comparación; no se puede calcular qué trabajadores cambian.");
        noEvaluable.add("Las entregas históricas no se recalculan: conservan la cantidad exigida de su fecha.");
        noEvaluable.add("La dotación incompleta se mide con la matriz de trabajo actual, que ya es la que usa el motor en vivo.");
        noEvaluable.add("No se estima costo ni stock de la reposición.");
        boolean hayCambios = vigente == null ? !actual.isEmpty() : !dif.vacia() || !vigente.getHuella().equals(huella(actual));
        return new VistaPrevia(vigente, actual.size(), hayCambios, dif.agregadas(), dif.retiradas(), dif.modificadas(),
                vigente == null ? 0 : dif.actividades().size(), afectados, incompletos, noEvaluable);
    }

    /** Diferencias entre una versión y la inmediatamente anterior (null si es la primera). */
    @Transactional(readOnly = true)
    public Comparacion comparar(Integer idVersion) {
        VersionMatriz v = versionRepository.findById(idVersion)
                .orElseThrow(() -> new RecursoNoEncontradoException("La versión de la matriz no existe."));
        VersionMatriz previa = versionRepository.findAllByOrderByIdVersionDesc().stream()
                .filter(x -> x.getIdVersion() < v.getIdVersion()).findFirst().orElse(null);
        Diferencias dif = diferencias(previa == null ? null : previa.getContenido(),
                Arrays.stream(v.getContenido().split("\\n")).filter(l -> !l.isBlank()).toList());
        return new Comparacion(v, previa, dif.agregadas(), dif.retiradas(), dif.modificadas());
    }

    /** Reglas actuales en forma canónica: una línea por regla, ordenada, para comparar y calcular la huella. */
    List<String> lineasActuales() {
        return matrizRepository.findAll().stream()
                .sorted(Comparator.comparing((MatrizAsignacion m) -> m.getActividad().getIdActividad())
                        .thenComparing(m -> m.getEpp().getIdEpp()))
                .map(m -> m.getActividad().getIdActividad() + "|" + m.getEpp().getIdEpp() + "|"
                        + (m.isObligatorio() ? "obligatorio" : "opcional") + "|" + m.getCantidad() + "|"
                        + limpiar(m.getActividad().getNombre()) + "|" + limpiar(m.getEpp().getNombre()))
                .collect(Collectors.toList());
    }

    private static String limpiar(String s) {
        return s == null ? "" : s.replace("|", "/").replace("\n", " ");
    }

    private static Map<String, String> porClave(List<String> lineas) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String l : lineas) {
            if (l.isBlank()) continue;
            String[] p = l.split("\\|", -1);
            m.put(p[0] + "|" + p[1], l);
        }
        return m;
    }

    private static Integer idActividad(String clave) {
        return Integer.valueOf(clave.substring(0, clave.indexOf('|')));
    }

    /** "Aplicación con mochila: Guantes ×2 (obligatorio)". */
    private static String legible(String linea) {
        String[] p = linea.split("\\|", -1);
        return p[4] + ": " + p[5] + " ×" + p[3] + " (" + p[2] + ")";
    }

    static String huella(List<String> lineas) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(String.join("\n", lineas).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
