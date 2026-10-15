package pe.utp.epptrack.config;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frena la adivinación de contraseñas: tras {@value #MAX_INTENTOS} fallos seguidos la cuenta queda bloqueada
 * {@code BLOQUEO_MINUTOS} minutos. Se guarda en memoria, así que se reinicia con la aplicación.
 */
@Component
public class LoginAttemptService {

    static final int MAX_INTENTOS = 5;
    static final Duration BLOQUEO = Duration.ofMinutes(15);
    private static final int MAX_REGISTROS = 10_000;

    private record Intentos(int fallos, Instant ultimoFallo) {}

    private final Map<String, Intentos> intentos = new ConcurrentHashMap<>();

    @EventListener
    public void alFallar(AuthenticationFailureBadCredentialsEvent evento) {
        registrarFallo(String.valueOf(evento.getAuthentication().getName()));
    }

    @EventListener
    public void alIngresar(AuthenticationSuccessEvent evento) {
        intentos.remove(clave(evento.getAuthentication().getName()));
    }

    public void registrarFallo(String usuario) {
        if (intentos.size() > MAX_REGISTROS) {
            intentos.clear();
        }
        intentos.merge(clave(usuario), new Intentos(1, Instant.now()),
                (previo, nuevo) -> new Intentos(previo.fallos() + 1, nuevo.ultimoFallo()));
    }

    /** Levanta el bloqueo de una cuenta antes de que venza. */
    public void desbloquear(String usuario) {
        intentos.remove(clave(usuario));
    }

    public boolean estaBloqueado(String usuario) {
        Intentos i = intentos.get(clave(usuario));
        if (i == null || i.fallos() < MAX_INTENTOS) {
            return false;
        }
        if (Instant.now().isAfter(i.ultimoFallo().plus(BLOQUEO))) {
            intentos.remove(clave(usuario));
            return false;
        }
        return true;
    }

    private static String clave(String usuario) {
        return usuario == null ? "" : usuario.trim().toLowerCase(Locale.ROOT);
    }
}
