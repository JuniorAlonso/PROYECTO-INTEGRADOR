package pe.utp.epptrack.model;

public enum TipoMovimientoUnidad {
    INGRESO("Ingreso a almacén"),
    ASIGNACION("Asignado en entrega"),
    DEVOLUCION("Devuelto por el trabajador"),
    INSPECCION("Inspeccionado"),
    AUTORIZADO_RETORNO("Retorno al almacén autorizado"),
    RETORNO_RECHAZADO("Retorno al almacén rechazado"),
    CUARENTENA_RESUELTA("Cuarentena resuelta"),
    ENVIO_MANTENIMIENTO("Enviado a mantenimiento"),
    REPARADO("Reparado, pendiente de autorización"),
    BAJA("Dado de baja"),
    LIBERADO("Liberado por anulación de entrega"),
    CONSUMO("Consumido por el trabajador (desechable)");

    private final String etiqueta;

    TipoMovimientoUnidad(String etiqueta) { this.etiqueta = etiqueta; }

    public String getEtiqueta() { return etiqueta; }
}
