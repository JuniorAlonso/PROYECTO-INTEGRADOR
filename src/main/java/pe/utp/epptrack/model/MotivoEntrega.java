package pe.utp.epptrack.model;

public enum MotivoEntrega {
    INGRESO("Nuevo ingreso"),
    REINGRESO("Reincorporación"),
    RENOVACION("Renovación por vida útil"),
    DESGASTE("Desgaste o ruptura"),
    PERDIDA("Pérdida"),
    CAMBIO_ACTIVIDAD("Cambio de actividad");

    private final String etiqueta;

    MotivoEntrega(String etiqueta) { this.etiqueta = etiqueta; }

    public String getEtiqueta() { return etiqueta; }
}
