package pe.utp.epptrack.model;

/** Dónde está físicamente una unidad de EPP identificada con código. */
public enum EstadoUnidad {
    DISPONIBLE("En almacén", "success"),
    ASIGNADO("Asignado a trabajador", "primary"),
    POR_INSPECCION("Devuelto, pendiente de inspección", "warning"),
    POR_AUTORIZAR("Inspeccionado, pendiente de autorización", "warning"),
    EN_CUARENTENA("En cuarentena", "danger"),
    POR_MANTENIMIENTO("Apto para mantenimiento", "warning"),
    EN_MANTENIMIENTO("En mantenimiento", "info"),
    DE_BAJA("De baja", "secondary"),
    CONSUMIDO("Consumido (desechable)", "secondary");

    private final String etiqueta;
    private final String color;

    EstadoUnidad(String etiqueta, String color) {
        this.etiqueta = etiqueta;
        this.color = color;
    }

    public String getEtiqueta() { return etiqueta; }
    public String getColor() { return color; }
}
