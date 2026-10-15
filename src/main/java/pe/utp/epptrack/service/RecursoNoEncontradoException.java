package pe.utp.epptrack.service;

/** El registro pedido no existe; se responde con 404 en lugar de un error genérico. */
public class RecursoNoEncontradoException extends ReglaNegocioException {
    public RecursoNoEncontradoException(String mensaje) {
        super(mensaje);
    }
}
