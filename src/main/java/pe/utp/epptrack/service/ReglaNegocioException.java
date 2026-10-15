package pe.utp.epptrack.service;

/** Violación de una regla de negocio; el mensaje se muestra tal cual al usuario. */
public class ReglaNegocioException extends RuntimeException {

    public ReglaNegocioException(String mensaje) {
        super(mensaje);
    }
}
