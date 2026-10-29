package pe.utp.epptrack.model;

import jakarta.persistence.*;

import java.time.LocalDate;

/** Unidad física de un EPP del catálogo, identificada con un código único para seguir su ciclo de vida. */
@Entity
@Table(name = "unidad_epp")
public class UnidadEpp {

    /** Desde esta cantidad de reparaciones ya no conviene repararla: se da de baja. */
    public static final int MAX_REPARACIONES = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idUnidad;

    @Column(nullable = false, unique = true, length = 20)
    private String codigo;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_epp", nullable = false)
    private Epp epp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EstadoUnidad estado = EstadoUnidad.DISPONIBLE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CondicionEpp condicion = CondicionEpp.NUEVO;

    /** Trabajador que la tiene en su poder; {@code null} si no está asignada. */
    @ManyToOne
    @JoinColumn(name = "id_trabajador")
    private Trabajador trabajador;

    /** Entrega con la que se asignó; {@code null} si no está asignada. */
    @ManyToOne
    @JoinColumn(name = "id_detalle")
    private DetalleEntrega detalle;

    @Column(nullable = false)
    private LocalDate fechaIngreso = LocalDate.now();

    @Column(nullable = false)
    private int vecesReparado;

    public UnidadEpp() {}

    public UnidadEpp(String codigo, Epp epp) {
        this.codigo = codigo;
        this.epp = epp;
    }

    /** Un trabajador debe devolverla: su entrega ya fue renovada o venció, o el trabajador cesó. */
    public boolean isPendienteDevolucion() {
        return estado == EstadoUnidad.ASIGNADO && epp.isPermiteDevolucion() && ((detalle != null && !detalle.getEstadoVigencia().isEnUso())
                || (trabajador != null && !trabajador.getEstado().isLaborando()));
    }

    public Integer getIdUnidad() { return idUnidad; }
    public void setIdUnidad(Integer idUnidad) { this.idUnidad = idUnidad; }
    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }
    public Epp getEpp() { return epp; }
    public void setEpp(Epp epp) { this.epp = epp; }
    public EstadoUnidad getEstado() { return estado; }
    public void setEstado(EstadoUnidad estado) { this.estado = estado; }
    public CondicionEpp getCondicion() { return condicion; }
    public void setCondicion(CondicionEpp condicion) { this.condicion = condicion; }
    public Trabajador getTrabajador() { return trabajador; }
    public void setTrabajador(Trabajador trabajador) { this.trabajador = trabajador; }
    public DetalleEntrega getDetalle() { return detalle; }
    public void setDetalle(DetalleEntrega detalle) { this.detalle = detalle; }
    public LocalDate getFechaIngreso() { return fechaIngreso; }
    public void setFechaIngreso(LocalDate fechaIngreso) { this.fechaIngreso = fechaIngreso; }
    public int getVecesReparado() { return vecesReparado; }
    public void setVecesReparado(int vecesReparado) { this.vecesReparado = vecesReparado; }
}
