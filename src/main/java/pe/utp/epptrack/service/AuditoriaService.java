package pe.utp.epptrack.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.model.RegistroAuditoria;
import pe.utp.epptrack.repository.RegistroAuditoriaRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;

/**
 * Bitácora de auditoría con huella encadenada (RF-13, RF-29, RN-05).
 * <p>
 * Cómo funciona: la huella de cada evento es SHA-256(huella anterior | fecha | usuario | acción | entidad | id | detalle).
 * Si alguien cambia o borra una fila directamente en la base de datos, al recalcular la cadena la huella
 * ya no coincide y {@link #verificar()} informa en qué registro se rompió. No se usa blockchain: basta una
 * cadena de huellas dentro de la propia base para detectar alteraciones, que es lo que se necesita como evidencia.
 * <p>
 * Limitación: quien tenga acceso de administrador a la base podría recalcular toda la cadena. Para evitarlo,
 * la huella del último registro se puede imprimir en los reportes o guardar fuera del servidor (trabajo futuro).
 */
@Service
public class AuditoriaService {

    public static final String INICIO = "0".repeat(64);

    /** Resultado de recorrer la cadena completa. */
    public record Verificacion(boolean integra, long registros, Long primerRegistroAlterado, String ultimaHuella) {}

    private final RegistroAuditoriaRepository repository;

    public AuditoriaService(RegistroAuditoriaRepository repository) {
        this.repository = repository;
    }

    /**
     * Agrega un evento al final de la cadena. Se sincroniza para que dos operaciones simultáneas no tomen
     * la misma huella anterior (la aplicación corre en una sola instancia).
     */
    @Transactional
    public synchronized RegistroAuditoria registrar(String usuario, String accion, String entidad, Object idEntidad,
                                                    String detalle) {
        String anterior = repository.findFirstByOrderByIdRegistroDesc().map(RegistroAuditoria::getHuella).orElse(INICIO);
        LocalDateTime fecha = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        String usr = usuario == null || usuario.isBlank() ? usuarioActual() : usuario;
        String id = idEntidad == null ? null : String.valueOf(idEntidad);
        String det = detalle == null ? "" : (detalle.length() > 2000 ? detalle.substring(0, 2000) : detalle);
        String huella = huella(anterior, fecha, usr, accion, entidad, id, det);
        return repository.save(new RegistroAuditoria(fecha, usr, accion, entidad, id, det, anterior, huella));
    }

    /** Recalcula todas las huellas desde el inicio y devuelve el primer registro que no coincide. */
    @Transactional(readOnly = true)
    public Verificacion verificar() {
        List<RegistroAuditoria> todos = repository.findAllByOrderByIdRegistroAsc();
        String esperada = INICIO;
        for (RegistroAuditoria r : todos) {
            String calculada = huella(esperada, r.getFecha(), r.getUsuario(), r.getAccion(), r.getEntidad(),
                    r.getIdEntidad(), r.getDetalle());
            if (!r.getHuellaAnterior().equals(esperada) || !r.getHuella().equals(calculada)) {
                return new Verificacion(false, todos.size(), r.getIdRegistro(), null);
            }
            esperada = r.getHuella();
        }
        return new Verificacion(true, todos.size(), null, esperada);
    }

    /** Usuario autenticado de la petición en curso; "sistema" para tareas programadas o al arrancar. */
    private static String usuarioActual() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName()) ? "sistema" : auth.getName();
    }

    @Transactional(readOnly = true)
    public List<RegistroAuditoria> ultimos() {
        return repository.findTop200ByOrderByIdRegistroDesc();
    }

    static String huella(String anterior, LocalDateTime fecha, String usuario, String accion, String entidad,
                         String idEntidad, String detalle) {
        String texto = String.join("|", anterior, fecha.truncatedTo(ChronoUnit.SECONDS).toString(), usuario, accion,
                entidad, idEntidad == null ? "" : idEntidad, detalle);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
