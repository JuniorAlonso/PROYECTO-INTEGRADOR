package pe.utp.epptrack.dto;

import org.springframework.format.annotation.DateTimeFormat;
import pe.utp.epptrack.model.MotivoEntrega;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class EntregaForm {

    private Integer idTrabajador;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate fechaEntrega = LocalDate.now();

    private MotivoEntrega motivo = MotivoEntrega.INGRESO;

    private List<ItemEntregaForm> items = new ArrayList<>();

    public List<ItemEntregaForm> getSeleccionados() {
        return items.stream().filter(ItemEntregaForm::isSeleccionado).toList();
    }

    public Integer getIdTrabajador() { return idTrabajador; }
    public void setIdTrabajador(Integer idTrabajador) { this.idTrabajador = idTrabajador; }
    public LocalDate getFechaEntrega() { return fechaEntrega; }
    public void setFechaEntrega(LocalDate fechaEntrega) { this.fechaEntrega = fechaEntrega; }
    public MotivoEntrega getMotivo() { return motivo; }
    public void setMotivo(MotivoEntrega motivo) { this.motivo = motivo; }
    public List<ItemEntregaForm> getItems() { return items; }
    public void setItems(List<ItemEntregaForm> items) { this.items = items; }
}
