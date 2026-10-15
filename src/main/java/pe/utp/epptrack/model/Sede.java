package pe.utp.epptrack.model;

import jakarta.persistence.*;

@Entity
@Table(name = "sede")
public class Sede {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idSede;

    @Column(nullable = false, length = 100)
    private String nombre;

    @Column(nullable = false, length = 100)
    private String distrito;

    /** Fundo, planta de empaque, depósito, oficina... */
    @Column(nullable = false, length = 50)
    private String tipo;

    public Sede() {}

    public Sede(String nombre, String distrito, String tipo) {
        this.nombre = nombre;
        this.distrito = distrito;
        this.tipo = tipo;
    }

    public Integer getIdSede() { return idSede; }
    public void setIdSede(Integer idSede) { this.idSede = idSede; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public String getDistrito() { return distrito; }
    public void setDistrito(String distrito) { this.distrito = distrito; }
    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }
}
