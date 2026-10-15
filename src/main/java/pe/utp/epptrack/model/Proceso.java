package pe.utp.epptrack.model;

import jakarta.persistence.*;

@Entity
@Table(name = "proceso")
public class Proceso {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idProceso;

    @Column(nullable = false, unique = true, length = 100)
    private String nombre;

    public Proceso() {}

    public Proceso(String nombre) { this.nombre = nombre; }

    public Integer getIdProceso() { return idProceso; }
    public void setIdProceso(Integer idProceso) { this.idProceso = idProceso; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
}
