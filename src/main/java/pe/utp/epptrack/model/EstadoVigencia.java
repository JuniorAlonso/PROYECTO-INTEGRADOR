package pe.utp.epptrack.model;

/**
 * Estado de un EPP entregado. VIGENTE / POR_VENCER / VENCIDO se recalculan a partir de la fecha de renovación;
 * RENOVADO y ANULADO son estados finales (el equipo fue reemplazado o la entrega se corrigió).
 */
public enum EstadoVigencia {
    VIGENTE("Vigente", "success"),
    POR_VENCER("Por vencer", "warning"),
    VENCIDO("Vencido", "danger"),
    RENOVADO("Renovado", "secondary"),
    ANULADO("Anulado", "dark");

    private final String etiqueta;
    private final String color;

    EstadoVigencia(String etiqueta, String color) {
        this.etiqueta = etiqueta;
        this.color = color;
    }

    public String getEtiqueta() { return etiqueta; }
    public String getColor() { return color; }

    /** El trabajador todavía tiene el equipo en condiciones de uso. */
    public boolean isEnUso() { return this == VIGENTE || this == POR_VENCER; }

    public boolean isFinal() { return this == RENOVADO || this == ANULADO; }
}
