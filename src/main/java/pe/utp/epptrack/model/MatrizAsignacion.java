package pe.utp.epptrack.model;

import jakarta.persistence.*;

/** Regla de la Matriz de EPP: qué equipo (y cuántas unidades) exige una actividad. */
@Entity
@Table(name = "matriz_asignacion",
       uniqueConstraints = @UniqueConstraint(columnNames = {"id_actividad", "id_epp"}))
public class MatrizAsignacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idMatriz;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_actividad", nullable = false)
    private Actividad actividad;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_epp", nullable = false)
    private Epp epp;

    @Column(nullable = false)
    private boolean obligatorio = true;

    @Column(nullable = false)
    private Integer cantidad = 1;

    public MatrizAsignacion() {}

    public MatrizAsignacion(Actividad actividad, Epp epp, boolean obligatorio, int cantidad) {
        this.actividad = actividad;
        this.epp = epp;
        this.obligatorio = obligatorio;
        this.cantidad = cantidad;
    }

    public Integer getIdMatriz() { return idMatriz; }
    public void setIdMatriz(Integer idMatriz) { this.idMatriz = idMatriz; }
    public Actividad getActividad() { return actividad; }
    public void setActividad(Actividad actividad) { this.actividad = actividad; }
    public Epp getEpp() { return epp; }
    public void setEpp(Epp epp) { this.epp = epp; }
    public boolean isObligatorio() { return obligatorio; }
    public void setObligatorio(boolean obligatorio) { this.obligatorio = obligatorio; }
    public Integer getCantidad() { return cantidad; }
    public void setCantidad(Integer cantidad) { this.cantidad = cantidad; }
}
