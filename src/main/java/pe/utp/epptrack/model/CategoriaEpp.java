package pe.utp.epptrack.model;

import jakarta.persistence.*;

@Entity
@Table(name = "categoria_epp")
public class CategoriaEpp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idCategoria;

    @Column(nullable = false, unique = true, length = 100)
    private String nombre;

    public CategoriaEpp() {}

    public CategoriaEpp(String nombre) { this.nombre = nombre; }

    public Integer getIdCategoria() { return idCategoria; }
    public void setIdCategoria(Integer idCategoria) { this.idCategoria = idCategoria; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
}
