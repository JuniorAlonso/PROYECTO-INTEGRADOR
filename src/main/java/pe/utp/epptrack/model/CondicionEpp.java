package pe.utp.epptrack.model;

/** Condición en la que se encuentra o se devuelve una unidad de EPP. */
public enum CondicionEpp {
    NUEVO("Nuevo"),
    BUENO("Buen estado, reutilizable"),
    REPARABLE("Dañado, reparable"),
    INSERVIBLE("Inservible");

    private final String etiqueta;

    CondicionEpp(String etiqueta) { this.etiqueta = etiqueta; }

    public String getEtiqueta() { return etiqueta; }
}
