package pe.utp.epptrack.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Salida de un lote ligada al detalle de entrega que la originó; permite trazarla y revertirla si se anula. */
@Entity
@Table(name = "consumo_lote")
public class ConsumoLote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idConsumo;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_lote", nullable = false)
    private LoteConsumible lote;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_detalle", nullable = false)
    private DetalleEntrega detalle;

    @Column(nullable = false)
    private int cantidad;

    @Column(nullable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    @Column(length = 100)
    private String usuario;

    @Column(nullable = false)
    private boolean revertido;

    public ConsumoLote() {}

    public ConsumoLote(LoteConsumible lote, DetalleEntrega detalle, int cantidad, String usuario) {
        this.lote = lote;
        this.detalle = detalle;
        this.cantidad = cantidad;
        this.usuario = usuario;
    }

    public Integer getIdConsumo() { return idConsumo; }
    public LoteConsumible getLote() { return lote; }
    public void setLote(LoteConsumible lote) { this.lote = lote; }
    public DetalleEntrega getDetalle() { return detalle; }
    public void setDetalle(DetalleEntrega detalle) { this.detalle = detalle; }
    public int getCantidad() { return cantidad; }
    public void setCantidad(int cantidad) { this.cantidad = cantidad; }
    public LocalDateTime getFecha() { return fecha; }
    public void setFecha(LocalDateTime fecha) { this.fecha = fecha; }
    public String getUsuario() { return usuario; }
    public void setUsuario(String usuario) { this.usuario = usuario; }
    public boolean isRevertido() { return revertido; }
    public void setRevertido(boolean revertido) { this.revertido = revertido; }
}
