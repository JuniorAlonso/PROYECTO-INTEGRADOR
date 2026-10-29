package pe.utp.epptrack.model;

/** Razón por la que un trabajador devuelve un EPP reutilizable. «Otro» exige explicarlo en la observación. */
public enum MotivoDevolucion {
    CESE_TRABAJADOR("Cese o fin de campaña del trabajador"),
    REEMPLAZO("Reemplazo por una entrega nueva o vencimiento"),
    CAMBIO_ACTIVIDAD("Cambio de actividad (ya no se exige)"),
    DETERIORO("Deterioro o daño detectado"),
    OTRO("Otro motivo (explicar en la observación)");

    private final String etiqueta;

    MotivoDevolucion(String etiqueta) { this.etiqueta = etiqueta; }

    public String getEtiqueta() { return etiqueta; }
}
