package pe.utp.epptrack.model;

/** Resultado del cierre de un mantenimiento. */
public enum ResultadoMantenimiento {
    REPARADO("Reparado (pasa a autorización de retorno)"),
    NO_REPARABLE("No reparable (se da de baja)");

    private final String etiqueta;

    ResultadoMantenimiento(String etiqueta) { this.etiqueta = etiqueta; }

    public String getEtiqueta() { return etiqueta; }
}
