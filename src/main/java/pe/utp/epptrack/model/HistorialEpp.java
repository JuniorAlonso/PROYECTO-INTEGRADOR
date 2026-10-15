package pe.utp.epptrack.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Bitácora de movimientos sobre los EPP de un trabajador: entregas, renovaciones, anulaciones. */
@Entity
@Table(name = "historial_epp")
public class HistorialEpp {

    public static final String ENTREGA = "ENTREGA";
    public static final String ENTREGA_DUPLICADA = "ENTREGA_DUPLICADA_JUSTIFICADA";
    public static final String RENOVADO = "RENOVADO";
    public static final String ANULACION = "ANULACION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idHistorial;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_trabajador", nullable = false)
    private Trabajador trabajador;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_detalle", nullable = false)
    private DetalleEntrega detalle;

    @Column(nullable = false)
    private LocalDateTime fechaRegistro = LocalDateTime.now();

    @Column(nullable = false, length = 50)
    private String tipoMovimiento;

    @Column(length = 500)
    private String observacion;

    public HistorialEpp() {}

    public HistorialEpp(Trabajador trabajador, DetalleEntrega detalle, String tipoMovimiento, String observacion) {
        this.trabajador = trabajador;
        this.detalle = detalle;
        this.tipoMovimiento = tipoMovimiento;
        this.observacion = observacion;
    }

    public Integer getIdHistorial() { return idHistorial; }
    public void setIdHistorial(Integer idHistorial) { this.idHistorial = idHistorial; }
    public Trabajador getTrabajador() { return trabajador; }
    public void setTrabajador(Trabajador trabajador) { this.trabajador = trabajador; }
    public DetalleEntrega getDetalle() { return detalle; }
    public void setDetalle(DetalleEntrega detalle) { this.detalle = detalle; }
    public LocalDateTime getFechaRegistro() { return fechaRegistro; }
    public void setFechaRegistro(LocalDateTime fechaRegistro) { this.fechaRegistro = fechaRegistro; }
    public String getTipoMovimiento() { return tipoMovimiento; }
    public void setTipoMovimiento(String tipoMovimiento) { this.tipoMovimiento = tipoMovimiento; }
    public String getObservacion() { return observacion; }
    public void setObservacion(String observacion) { this.observacion = observacion; }
}
