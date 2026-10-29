package pe.utp.epptrack.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Evento de la bitácora de auditoría (RF-13, RF-29). Cada registro guarda la huella SHA-256 del anterior,
 * de modo que modificar o borrar un evento ya grabado rompe la cadena y la verificación lo detecta.
 * Solo se insertan filas: no hay operaciones de actualización ni borrado sobre esta tabla (RNF-03).
 */
@Entity
@Table(name = "registro_auditoria")
public class RegistroAuditoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long idRegistro;

    @Column(nullable = false)
    private LocalDateTime fecha;

    @Column(nullable = false, length = 50)
    private String usuario;

    /** Qué ocurrió: ENTREGA_REGISTRADA, ENTREGA_ANULADA, TRABAJADOR_REGISTRADO, CESE, REINGRESO, MATRIZ_PUBLICADA… */
    @Column(nullable = false, length = 40)
    private String accion;

    /** Tabla o concepto afectado y su id, para ubicar el registro original. */
    @Column(nullable = false, length = 40)
    private String entidad;

    @Column(length = 40)
    private String idEntidad;

    /** Datos de la operación y la regla aplicada, en texto legible. */
    @Column(nullable = false, length = 2000)
    private String detalle;

    @Column(nullable = false, length = 64)
    private String huellaAnterior;

    @Column(nullable = false, length = 64, unique = true)
    private String huella;

    public RegistroAuditoria() {}

    public RegistroAuditoria(LocalDateTime fecha, String usuario, String accion, String entidad, String idEntidad,
                             String detalle, String huellaAnterior, String huella) {
        this.fecha = fecha;
        this.usuario = usuario;
        this.accion = accion;
        this.entidad = entidad;
        this.idEntidad = idEntidad;
        this.detalle = detalle;
        this.huellaAnterior = huellaAnterior;
        this.huella = huella;
    }

    public Long getIdRegistro() { return idRegistro; }
    public LocalDateTime getFecha() { return fecha; }
    public String getUsuario() { return usuario; }
    public String getAccion() { return accion; }
    public String getEntidad() { return entidad; }
    public String getIdEntidad() { return idEntidad; }
    public String getDetalle() { return detalle; }
    /** Solo para pruebas de manipulación: simula que alguien alteró la fila directamente en la base. */
    public void setDetalle(String detalle) { this.detalle = detalle; }
    public String getHuellaAnterior() { return huellaAnterior; }
    public String getHuella() { return huella; }
    public String getHuellaCorta() { return huella.substring(0, 12); }
}
