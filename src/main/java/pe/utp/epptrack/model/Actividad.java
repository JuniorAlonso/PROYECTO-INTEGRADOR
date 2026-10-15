package pe.utp.epptrack.model;

import jakarta.persistence.*;

@Entity
@Table(name = "actividad")
public class Actividad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idActividad;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_proceso", nullable = false)
    private Proceso proceso;

    @Column(nullable = false, length = 150)
    private String nombre;

    public Actividad() {}

    public Actividad(Proceso proceso, String nombre) {
        this.proceso = proceso;
        this.nombre = nombre;
    }

    public Integer getIdActividad() { return idActividad; }
    public void setIdActividad(Integer idActividad) { this.idActividad = idActividad; }
    public Proceso getProceso() { return proceso; }
    public void setProceso(Proceso proceso) { this.proceso = proceso; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
}
