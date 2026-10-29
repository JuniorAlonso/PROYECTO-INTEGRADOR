package pe.utp.epptrack.model;

public enum TipoMovimientoUnidad {
    INGRESO("Ingreso a almacén"),
    ASIGNACION("Asignado en entrega"),
    DEVOLUCION("Devuelto por el trabajador"),
    ENVIO_MANTENIMIENTO("Enviado a mantenimiento"),
    REPARADO("Reparado, vuelve al almacén"),
    BAJA("Dado de baja"),
    LIBERADO("Liberado por anulación de entrega"),
    CONSUMO("Consumido por el trabajador (desechable)");

    private final String etiqueta;

    TipoMovimientoUnidad(String etiqueta) { this.etiqueta = etiqueta; }

    public String getEtiqueta() { return etiqueta; }
}
