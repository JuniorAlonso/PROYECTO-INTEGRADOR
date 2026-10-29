package pe.utp.epptrack.model;

/**
 * Clasificación de un EPP según cómo se usa y qué ocurre con él después de entregarlo.
 * <p>
 * Los valores por defecto son solo el punto de partida: cada EPP puede sobrescribirlos en el catálogo
 * (ver {@link Epp#isPermiteDevolucion()}). Ninguna de estas reglas es una política aprobada por CASURSA:
 * mientras SST no valide la clase de un EPP, el catálogo lo muestra como «pendiente de validación».
 */
public enum ClaseEpp {

    DESECHABLE("Desechable o consumible",
            "Se consume con el uso. No se devuelve ni vuelve al almacén; si se pierde o se daña se registra una nueva entrega.",
            false, false),
    REUTILIZABLE_INDIVIDUAL("Reutilizable de uso individual",
            "Se asigna a una sola persona. Al devolverlo debe inspeccionarse antes de volver a asignarse.",
            true, true),
    REUTILIZABLE_COMPARTIDO("Reutilizable compartido (custodia)",
            "Pasa de mano en mano bajo custodia. Se devuelve con su estado y puede reasignarse si está en buen estado.",
            true, false);

    private final String etiqueta;
    private final String descripcion;
    private final boolean devolucionPorDefecto;
    private final boolean inspeccionPorDefecto;

    ClaseEpp(String etiqueta, String descripcion, boolean devolucionPorDefecto, boolean inspeccionPorDefecto) {
        this.etiqueta = etiqueta;
        this.descripcion = descripcion;
        this.devolucionPorDefecto = devolucionPorDefecto;
        this.inspeccionPorDefecto = inspeccionPorDefecto;
    }

    public String getEtiqueta() { return etiqueta; }
    public String getDescripcion() { return descripcion; }
    public boolean isDevolucionPorDefecto() { return devolucionPorDefecto; }
    public boolean isInspeccionPorDefecto() { return inspeccionPorDefecto; }
}
