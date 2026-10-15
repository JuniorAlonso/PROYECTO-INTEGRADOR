package pe.utp.epptrack.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Toda corrección sobre una entrega queda registrada; nunca se borra el dato original (RN-05). */
@Entity
@Table(name = "correccion_entrega")
public class CorreccionEntrega {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idCorreccion;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_detalle", nullable = false)
    private DetalleEntrega detalle;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_usuario", nullable = false)
    private Usuario usuario;

    @Column(nullable = false)
    private LocalDateTime fechaCorreccion = LocalDateTime.now();

    @Column(nullable = false, length = 500)
    private String valorAnterior;

    @Column(nullable = false, length = 500)
    private String motivo;

    public CorreccionEntrega() {}

    public CorreccionEntrega(DetalleEntrega detalle, Usuario usuario, String valorAnterior, String motivo) {
        this.detalle = detalle;
        this.usuario = usuario;
        this.valorAnterior = valorAnterior;
        this.motivo = motivo;
    }

    public Integer getIdCorreccion() { return idCorreccion; }
    public DetalleEntrega getDetalle() { return detalle; }
    public Usuario getUsuario() { return usuario; }
    public LocalDateTime getFechaCorreccion() { return fechaCorreccion; }
    public String getValorAnterior() { return valorAnterior; }
    public String getMotivo() { return motivo; }
}
