package pe.utp.epptrack.model;

public enum EstadoTrabajador {
    ACTIVO("Activo"),
    REINCORPORADO("Reincorporado"),
    CESADO("Cesado");

    private final String etiqueta;

    EstadoTrabajador(String etiqueta) { this.etiqueta = etiqueta; }

    public String getEtiqueta() { return etiqueta; }

    public boolean isLaborando() { return this != CESADO; }
}
