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

    /** Justificación obligatoria cuando se entrega un EPP que la matriz no exige para la actividad (RN-06). */
    @Column(length = 500)
    private String justificacionNoExigido;

    /** Justificación obligatoria cuando se entrega más de lo que falta según la matriz (RN-08). */
    @Column(length = 500)
    private String justificacionExceso;

    /**
     * Cantidad que la matriz exigía de este EPP al momento de entregar (0 = no lo exigía). {@code null} en
     * entregas anteriores a este registro. Congela la regla: editar la matriz después no reescribe la historia.
     */
    private Integer cantidadExigida;

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

    public boolean isExcesoJustificado() { return justificacionExceso != null && !justificacionExceso.isBlank(); }
    public boolean isNoExigidoJustificado() { return justificacionNoExigido != null && !justificacionNoExigido.isBlank(); }
    public String getJustificacionExceso() { return justificacionExceso; }
    public void setJustificacionExceso(String justificacionExceso) { this.justificacionExceso = justificacionExceso; }
    public Integer getCantidadExigida() { return cantidadExigida; }
    public void setCantidadExigida(Integer cantidadExigida) { this.cantidadExigida = cantidadExigida; }

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
    public String getJustificacionNoExigido() { return justificacionNoExigido; }
    public void setJustificacionNoExigido(String justificacionNoExigido) { this.justificacionNoExigido = justificacionNoExigido; }
    public void setJustificacionDuplicado(String justificacionDuplicado) { this.justificacionDuplicado = justificacionDuplicado; }
}
