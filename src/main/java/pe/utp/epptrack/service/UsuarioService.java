package pe.utp.epptrack.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.config.LoginAttemptService;
import pe.utp.epptrack.model.Rol;
import pe.utp.epptrack.model.Usuario;
import pe.utp.epptrack.repository.RolRepository;
import pe.utp.epptrack.repository.UsuarioRepository;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Gestión de cuentas de acceso (RF-14, RF-15), solo para el Administrador. Las cuentas no se eliminan:
 * se desactivan, para conservar quién registró cada entrega (RN-02). Contraseñas con BCrypt (RNF-02).
 */
@Service
public class UsuarioService {

    public static final String ACTIVO = "ACTIVO";
    public static final String INACTIVO = "INACTIVO";
    public static final int MIN_CONTRASENA = 8;

    private static final Pattern NOMBRE_VALIDO = Pattern.compile("[a-zA-Z0-9._-]{3,50}");

    private final UsuarioRepository usuarioRepository;
    private final RolRepository rolRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService intentos;
    private final AuditoriaService auditoriaService;

    public UsuarioService(UsuarioRepository usuarioRepository, RolRepository rolRepository,
                          PasswordEncoder passwordEncoder, LoginAttemptService intentos,
                          AuditoriaService auditoriaService) {
        this.usuarioRepository = usuarioRepository;
        this.rolRepository = rolRepository;
        this.passwordEncoder = passwordEncoder;
        this.intentos = intentos;
        this.auditoriaService = auditoriaService;
    }

    @Transactional(readOnly = true)
    public List<Usuario> listar() {
        return usuarioRepository.findAll().stream()
                .sorted(Comparator.comparing(Usuario::getNombreUsuario, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Rol> roles() {
        return rolRepository.findAll().stream().sorted(Comparator.comparing(Rol::getIdRol)).toList();
    }

    @Transactional(readOnly = true)
    public boolean estaBloqueado(String nombreUsuario) {
        return intentos.estaBloqueado(nombreUsuario);
    }

    @Transactional
    public Usuario crear(String nombreUsuario, Integer idRol, String contrasena, String confirmacion, String responsable) {
        String nombre = nombreUsuario == null ? "" : nombreUsuario.trim();
        if (!NOMBRE_VALIDO.matcher(nombre).matches()) {
            throw new ReglaNegocioException("El usuario debe tener de 3 a 50 caracteres: letras, números, punto, guion o guion bajo, sin espacios.");
        }
        if (usuarioRepository.findAll().stream().anyMatch(u -> u.getNombreUsuario().equalsIgnoreCase(nombre))) {
            throw new ReglaNegocioException("Ya existe el usuario \"" + nombre + "\".");
        }
        Rol rol = obtenerRol(idRol);
        validarContrasena(contrasena, confirmacion);
        Usuario usuario = usuarioRepository.save(new Usuario(rol, nombre, passwordEncoder.encode(contrasena)));
        auditoriaService.registrar(responsable, "USUARIO_CREADO", "usuario", usuario.getIdUsuario(),
                "usuario=" + nombre + "; rol=" + rol.getNombre());
        return usuario;
    }

    @Transactional
    public Usuario cambiarRol(Integer idUsuario, Integer idRol, String responsable) {
        Usuario usuario = obtener(idUsuario);
        Rol nuevo = obtenerRol(idRol);
        if (nuevo.getIdRol().equals(usuario.getRol().getIdRol())) {
            return usuario;
        }
        if (usuario.getNombreUsuario().equalsIgnoreCase(responsable)) {
            throw new ReglaNegocioException("No puede cambiar su propio rol; pídaselo a otro administrador.");
        }
        validarQueQuedeAdmin(usuario);
        String anterior = usuario.getRol().getNombre();
        usuario.setRol(nuevo);
        auditoriaService.registrar(responsable, "USUARIO_ROL", "usuario", idUsuario,
                "usuario=" + usuario.getNombreUsuario() + "; rol " + anterior + " -> " + nuevo.getNombre());
        return usuario;
    }

    /** Activa o desactiva la cuenta. Una cuenta inactiva no puede iniciar sesión, pero su historial se conserva. */
    @Transactional
    public Usuario cambiarEstado(Integer idUsuario, String responsable) {
        Usuario usuario = obtener(idUsuario);
        boolean activo = ACTIVO.equals(usuario.getEstado());
        if (activo) {
            if (usuario.getNombreUsuario().equalsIgnoreCase(responsable)) {
                throw new ReglaNegocioException("No puede desactivar su propia cuenta.");
            }
            validarQueQuedeAdmin(usuario);
        }
        usuario.setEstado(activo ? INACTIVO : ACTIVO);
        auditoriaService.registrar(responsable, activo ? "USUARIO_DESACTIVADO" : "USUARIO_ACTIVADO", "usuario",
                idUsuario, "usuario=" + usuario.getNombreUsuario());
        return usuario;
    }

    /** Asigna una contraseña nueva y desbloquea la cuenta si estaba bloqueada por intentos fallidos. */
    @Transactional
    public Usuario restablecerContrasena(Integer idUsuario, String contrasena, String confirmacion, String responsable) {
        Usuario usuario = obtener(idUsuario);
        validarContrasena(contrasena, confirmacion);
        usuario.setContrasenaCifrada(passwordEncoder.encode(contrasena));
        intentos.desbloquear(usuario.getNombreUsuario());
        auditoriaService.registrar(responsable, "USUARIO_CONTRASENA", "usuario", idUsuario,
                "usuario=" + usuario.getNombreUsuario() + "; contraseña restablecida");
        return usuario;
    }

    /** El sistema nunca debe quedarse sin un administrador activo que pueda gestionarlo. */
    private void validarQueQuedeAdmin(Usuario usuario) {
        boolean esAdminActivo = Rol.ADMIN.equals(usuario.getRol().getNombre()) && ACTIVO.equals(usuario.getEstado());
        long adminsActivos = usuarioRepository.findAll().stream()
                .filter(u -> Rol.ADMIN.equals(u.getRol().getNombre()) && ACTIVO.equals(u.getEstado()))
                .count();
        if (esAdminActivo && adminsActivos <= 1) {
            throw new ReglaNegocioException("Debe quedar al menos un administrador activo.");
        }
    }

    private void validarContrasena(String contrasena, String confirmacion) {
        if (contrasena == null || contrasena.length() < MIN_CONTRASENA || contrasena.length() > 72) {
            throw new ReglaNegocioException("La contraseña debe tener entre " + MIN_CONTRASENA + " y 72 caracteres.");
        }
        if (!contrasena.matches(".*[A-Za-z].*") || !contrasena.matches(".*\\d.*")) {
            throw new ReglaNegocioException("La contraseña debe combinar letras y números.");
        }
        if (!contrasena.equals(confirmacion)) {
            throw new ReglaNegocioException("La confirmación no coincide con la contraseña.");
        }
    }

    private Usuario obtener(Integer idUsuario) {
        return usuarioRepository.findById(idUsuario)
                .orElseThrow(() -> new RecursoNoEncontradoException("Usuario no encontrado."));
    }

    private Rol obtenerRol(Integer idRol) {
        if (idRol == null) {
            throw new ReglaNegocioException("Seleccione el rol.");
        }
        return rolRepository.findById(idRol).orElseThrow(() -> new ReglaNegocioException("Rol no válido."));
    }
}
