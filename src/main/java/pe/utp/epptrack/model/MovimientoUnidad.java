package pe.utp.epptrack.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Bitácora de una unidad de EPP: ingreso, asignación, devolución, mantenimiento y baja. */
@Entity
@Table(name = "movimiento_unidad")
public class MovimientoUnidad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idMovimiento;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_unidad", nullable = false)
    private UnidadEpp unidad;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TipoMovimientoUnidad tipo;

    /** Trabajador involucrado (en asignaciones y devoluciones). */
    @ManyToOne
    @JoinColumn(name = "id_trabajador")
    private Trabajador trabajador;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CondicionEpp condicion;

    @Column(length = 500)
    private String observacion;

    @Column(length = 50)
    private String usuario;

    @Column(nullable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    public MovimientoUnidad() {}

    public MovimientoUnidad(UnidadEpp unidad, TipoMovimientoUnidad tipo, Trabajador trabajador,
                            CondicionEpp condicion, String observacion, String usuario) {
        this.unidad = unidad;
        this.tipo = tipo;
        this.trabajador = trabajador;
        this.condicion = condicion;
        this.observacion = observacion;
        this.usuario = usuario;
    }

    public Integer getIdMovimiento() { return idMovimiento; }
    public UnidadEpp getUnidad() { return unidad; }
    public TipoMovimientoUnidad getTipo() { return tipo; }
    public Trabajador getTrabajador() { return trabajador; }
    public CondicionEpp getCondicion() { return condicion; }
    public String getObservacion() { return observacion; }
    public String getUsuario() { return usuario; }
    public LocalDateTime getFecha() { return fecha; }
    public void setFecha(LocalDateTime fecha) { this.fecha = fecha; }
}
