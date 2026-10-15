package pe.utp.epptrack.model;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "detalle_entrega")
public class DetalleEntrega {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idDetalle;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_entrega", nullable = false)
    private Entrega entrega;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_epp", nullable = false)
    private Epp epp;

    @Column(nullable = false)
    private Integer cantidad;

    /** fecha_entrega + vida útil del EPP (RF-06, RN-03). */
    private LocalDate fechaRenovacion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EstadoVigencia estadoVigencia = EstadoVigencia.VIGENTE;

    /** Justificación obligatoria cuando se entrega un EPP que el trabajador aún tenía vigente (RF-07). */
    @Column(length = 500)
    private String justificacionDuplicado;

    public DetalleEntrega() {}

    public DetalleEntrega(Epp epp, int cantidad, LocalDate fechaRenovacion) {
        this.epp = epp;
        this.cantidad = cantidad;
        this.fechaRenovacion = fechaRenovacion;
    }

    /** Días que faltan para la renovación (negativo si ya venció). */
    public long getDiasRestantes() {
        return fechaRenovacion == null ? Long.MAX_VALUE : ChronoUnit.DAYS.between(LocalDate.now(), fechaRenovacion);
    }

    public boolean isDuplicadoJustificado() { return justificacionDuplicado != null && !justificacionDuplicado.isBlank(); }

    public Integer getIdDetalle() { return idDetalle; }
    public void setIdDetalle(Integer idDetalle) { this.idDetalle = idDetalle; }
    public Entrega getEntrega() { return entrega; }
    public void setEntrega(Entrega entrega) { this.entrega = entrega; }
    public Epp getEpp() { return epp; }
    public void setEpp(Epp epp) { this.epp = epp; }
    public Integer getCantidad() { return cantidad; }
    public void setCantidad(Integer cantidad) { this.cantidad = cantidad; }
    public LocalDate getFechaRenovacion() { return fechaRenovacion; }
    public void setFechaRenovacion(LocalDate fechaRenovacion) { this.fechaRenovacion = fechaRenovacion; }
    public EstadoVigencia getEstadoVigencia() { return estadoVigencia; }
    public void setEstadoVigencia(EstadoVigencia estadoVigencia) { this.estadoVigencia = estadoVigencia; }
    public String getJustificacionDuplicado() { return justificacionDuplicado; }
    public void setJustificacionDuplicado(String justificacionDuplicado) { this.justificacionDuplicado = justificacionDuplicado; }
}
