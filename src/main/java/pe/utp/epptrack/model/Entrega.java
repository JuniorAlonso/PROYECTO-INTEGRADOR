package pe.utp.epptrack.model;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Cabecera de una dotación (equivalente digital al formato FOR-SST 9.3.2). No se elimina nunca (RNF-03). */
@Entity
@Table(name = "entrega")
public class Entrega {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idEntrega;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_trabajador", nullable = false)
    private Trabajador trabajador;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_sede", nullable = false)
    private Sede sede;

    /** Responsable que registra y autoriza la entrega (RN-02). */
    @ManyToOne(optional = false)
    @JoinColumn(name = "id_usuario", nullable = false)
    private Usuario usuario;

    @Column(nullable = false)
    private LocalDate fechaEntrega;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private MotivoEntrega motivo;

    /** Momento exacto del registro, para auditoría (RF-13). */
    @Column(nullable = false)
    private LocalDateTime fechaRegistro = LocalDateTime.now();

    /** Versión de la Matriz de EPP vigente al registrar la entrega (RF-21): con qué reglas se decidió. */
    @Column(length = 60)
    private String versionMatriz;

    @OneToMany(mappedBy = "entrega", cascade = CascadeType.PERSIST)
    private List<DetalleEntrega> detalles = new ArrayList<>();

    /** Códigos de las unidades asignadas al registrar (no se persiste: se informa al usuario). */
    @Transient
    private final List<String> codigosAsignados = new ArrayList<>();

    /** EPP que se entregaron sin unidad con código porque no había stock en el almacén. */
    @Transient
    private final List<String> sinStock = new ArrayList<>();

    public List<String> getCodigosAsignados() { return codigosAsignados; }
    public List<String> getSinStock() { return sinStock; }

    public void agregarDetalle(DetalleEntrega detalle) {
        detalle.setEntrega(this);
        detalles.add(detalle);
    }

    public Integer getIdEntrega() { return idEntrega; }
    public void setIdEntrega(Integer idEntrega) { this.idEntrega = idEntrega; }
    public Trabajador getTrabajador() { return trabajador; }
    public void setTrabajador(Trabajador trabajador) { this.trabajador = trabajador; }
    public Sede getSede() { return sede; }
    public void setSede(Sede sede) { this.sede = sede; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public LocalDate getFechaEntrega() { return fechaEntrega; }
    public void setFechaEntrega(LocalDate fechaEntrega) { this.fechaEntrega = fechaEntrega; }
    public MotivoEntrega getMotivo() { return motivo; }
    public void setMotivo(MotivoEntrega motivo) { this.motivo = motivo; }
    public LocalDateTime getFechaRegistro() { return fechaRegistro; }
    public void setFechaRegistro(LocalDateTime fechaRegistro) { this.fechaRegistro = fechaRegistro; }
    public List<DetalleEntrega> getDetalles() { return detalles; }
    public String getVersionMatriz() { return versionMatriz; }
    public void setVersionMatriz(String versionMatriz) { this.versionMatriz = versionMatriz; }
    public void setDetalles(List<DetalleEntrega> detalles) { this.detalles = detalles; }
}
