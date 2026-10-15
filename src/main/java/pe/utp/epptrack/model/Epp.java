package pe.utp.epptrack.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Entity
@Table(name = "epp")
public class Epp {

    public static final String ACTIVO = "ACTIVO";
    public static final String INACTIVO = "INACTIVO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idEpp;

    @NotNull(message = "Seleccione una categoría")
    @ManyToOne(optional = false)
    @JoinColumn(name = "id_categoria", nullable = false)
    private CategoriaEpp categoria;

    @NotBlank
    @Size(max = 150, message = "Máximo 150 caracteres")
    @Column(nullable = false, length = 150)
    private String nombre;

    @Size(max = 255, message = "Máximo 255 caracteres")
    @Column(length = 255)
    private String descripcion;

    @NotNull
    @Min(value = 1, message = "La vida útil debe ser al menos 1 día")
    @Max(value = 3650, message = "La vida útil no puede superar 3650 días (10 años)")
    @Column(nullable = false)
    private Integer vidaUtilDias;

    @Column(nullable = false, length = 20)
    private String estado = ACTIVO;

    public Epp() {}

    public Epp(CategoriaEpp categoria, String nombre, String descripcion, int vidaUtilDias) {
        this.categoria = categoria;
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.vidaUtilDias = vidaUtilDias;
    }

    public boolean isActivo() { return ACTIVO.equals(estado); }

    public Integer getIdEpp() { return idEpp; }
    public void setIdEpp(Integer idEpp) { this.idEpp = idEpp; }
    public CategoriaEpp getCategoria() { return categoria; }
    public void setCategoria(CategoriaEpp categoria) { this.categoria = categoria; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }
    public Integer getVidaUtilDias() { return vidaUtilDias; }
    public void setVidaUtilDias(Integer vidaUtilDias) { this.vidaUtilDias = vidaUtilDias; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
}
