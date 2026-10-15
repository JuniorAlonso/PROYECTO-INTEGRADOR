package pe.utp.epptrack.model;

import jakarta.persistence.*;

@Entity
@Table(name = "usuario")
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idUsuario;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_rol", nullable = false)
    private Rol rol;

    @Column(nullable = false, unique = true, length = 50)
    private String nombreUsuario;

    /** Hash BCrypt (RNF-02). */
    @Column(nullable = false, length = 255)
    private String contrasenaCifrada;

    @Column(nullable = false, length = 20)
    private String estado = "ACTIVO";

    public Usuario() {}

    public Usuario(Rol rol, String nombreUsuario, String contrasenaCifrada) {
        this.rol = rol;
        this.nombreUsuario = nombreUsuario;
        this.contrasenaCifrada = contrasenaCifrada;
    }

    public Integer getIdUsuario() { return idUsuario; }
    public void setIdUsuario(Integer idUsuario) { this.idUsuario = idUsuario; }
    public Rol getRol() { return rol; }
    public void setRol(Rol rol) { this.rol = rol; }
    public String getNombreUsuario() { return nombreUsuario; }
    public void setNombreUsuario(String nombreUsuario) { this.nombreUsuario = nombreUsuario; }
    public String getContrasenaCifrada() { return contrasenaCifrada; }
    public void setContrasenaCifrada(String contrasenaCifrada) { this.contrasenaCifrada = contrasenaCifrada; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
}
