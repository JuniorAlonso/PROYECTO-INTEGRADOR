package pe.utp.epptrack.dto;

/** Una fila del formulario de entrega: un EPP que se puede marcar para entregar. */
public class ItemEntregaForm {

    private Integer idEpp;
    private boolean seleccionado;
    private Integer cantidad = 1;
    /** Obligatoria si el trabajador ya tiene este EPP vigente (RF-07). */
    private String justificacion;

    public ItemEntregaForm() {}

    public ItemEntregaForm(Integer idEpp, boolean seleccionado, Integer cantidad) {
        this.idEpp = idEpp;
        this.seleccionado = seleccionado;
        this.cantidad = cantidad;
    }

    public Integer getIdEpp() { return idEpp; }
    public void setIdEpp(Integer idEpp) { this.idEpp = idEpp; }
    public boolean isSeleccionado() { return seleccionado; }
    public void setSeleccionado(boolean seleccionado) { this.seleccionado = seleccionado; }
    public Integer getCantidad() { return cantidad; }
    public void setCantidad(Integer cantidad) { this.cantidad = cantidad; }
    public String getJustificacion() { return justificacion; }
    public void setJustificacion(String justificacion) { this.justificacion = justificacion; }
}
