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

    /** Costo referencial de compra por unidad (S/), para estimar el ahorro al reparar en vez de comprar. */
    @jakarta.validation.constraints.DecimalMin(value = "0.0", message = "El costo no puede ser negativo")
    @Column(precision = 10, scale = 2)
    private java.math.BigDecimal costoUnitario;

    @Column(nullable = false, length = 20)
    private String estado = ACTIVO;

    /** Clase del EPP; {@code null} en registros anteriores a la clasificación (se trata como reutilizable individual). */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ClaseEpp clase;

    /** {@code true} solo cuando SST confirmó la clase y la política de devolución de este EPP. */
    private Boolean claseValidada;

    /** Sobrescribe la política de la clase: ¿se puede devolver? {@code null} = usar la de la clase. */
    private Boolean devolucionPermitida;

    /** Sobrescribe la política de la clase: ¿debe inspeccionarse al devolverlo? {@code null} = usar la de la clase. */
    private Boolean inspeccionAlDevolver;

    public Epp() {}

    public Epp(CategoriaEpp categoria, String nombre, String descripcion, int vidaUtilDias) {
        this.categoria = categoria;
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.vidaUtilDias = vidaUtilDias;
    }

    public boolean isActivo() { return ACTIVO.equals(estado); }

    /** Clase efectiva: la conservadora (reutilizable individual, con inspección) si nunca se clasificó. */
    public ClaseEpp getClaseEfectiva() { return clase == null ? ClaseEpp.REUTILIZABLE_INDIVIDUAL : clase; }

    public boolean isDesechable() { return getClaseEfectiva() == ClaseEpp.DESECHABLE; }

    public boolean isPermiteDevolucion() {
        return devolucionPermitida != null ? devolucionPermitida : getClaseEfectiva().isDevolucionPorDefecto();
    }

    public boolean isRequiereInspeccionAlDevolver() {
        return inspeccionAlDevolver != null ? inspeccionAlDevolver : getClaseEfectiva().isInspeccionPorDefecto();
    }

    /** La política de este EPP todavía no fue confirmada por SST: ninguna regla de devolución es oficial. */
    public boolean isClasePendienteValidacion() { return !Boolean.TRUE.equals(claseValidada); }

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
    public java.math.BigDecimal getCostoUnitario() { return costoUnitario; }
    public void setCostoUnitario(java.math.BigDecimal costoUnitario) { this.costoUnitario = costoUnitario; }
    public ClaseEpp getClase() { return clase; }
    public void setClase(ClaseEpp clase) { this.clase = clase; }
    public Boolean getClaseValidada() { return claseValidada; }
    public void setClaseValidada(Boolean claseValidada) { this.claseValidada = claseValidada; }
    public Boolean getDevolucionPermitida() { return devolucionPermitida; }
    public void setDevolucionPermitida(Boolean devolucionPermitida) { this.devolucionPermitida = devolucionPermitida; }
    public Boolean getInspeccionAlDevolver() { return inspeccionAlDevolver; }
    public void setInspeccionAlDevolver(Boolean inspeccionAlDevolver) { this.inspeccionAlDevolver = inspeccionAlDevolver; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
}
