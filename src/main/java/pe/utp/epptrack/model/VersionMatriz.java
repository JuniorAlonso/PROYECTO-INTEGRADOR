package pe.utp.epptrack.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Versión publicada de la Matriz de EPP (RF-21). Guarda una copia textual de todas las reglas vigentes al momento
 * de publicar y su huella SHA-256: así cada entrega puede citar con qué versión se decidió su dotación, y se puede
 * comparar una versión con la siguiente para saber qué actividades y trabajadores se ven afectados.
 */
@Entity
@Table(name = "version_matriz")
public class VersionMatriz {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idVersion;

    /** Código visible, por ejemplo "v6" o "v7". Único. */
    @Column(nullable = false, unique = true, length = 20)
    private String codigo;

    @Column(length = 300)
    private String descripcion;

    @Column(nullable = false)
    private LocalDateTime fechaPublicacion;

    @Column(nullable = false, length = 50)
    private String publicadaPor;

    /** Solo una versión es la vigente; las anteriores se conservan como historial. */
    @Column(nullable = false)
    private boolean vigente;

    @Column(nullable = false)
    private int totalReglas;

    @Column(nullable = false, length = 64)
    private String huella;

    /** Una línea por regla: idActividad|idEpp|obligatorio|cantidad|actividad|epp, ordenadas. */
    // Sin columnDefinition, Hibernate crea TINYTEXT en MySQL (255 caracteres) y la matriz no cabe.
    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String contenido;

    public VersionMatriz() {}

    public VersionMatriz(String codigo, String descripcion, String publicadaPor, int totalReglas, String huella,
                         String contenido) {
        this.codigo = codigo;
        this.descripcion = descripcion;
        this.publicadaPor = publicadaPor;
        this.totalReglas = totalReglas;
        this.huella = huella;
        this.contenido = contenido;
        this.fechaPublicacion = LocalDateTime.now();
        this.vigente = true;
    }

    public Integer getIdVersion() { return idVersion; }
    public String getCodigo() { return codigo; }
    public String getDescripcion() { return descripcion; }
    public LocalDateTime getFechaPublicacion() { return fechaPublicacion; }
    public String getPublicadaPor() { return publicadaPor; }
    public boolean isVigente() { return vigente; }
    public void setVigente(boolean vigente) { this.vigente = vigente; }
    public int getTotalReglas() { return totalReglas; }
    public String getHuella() { return huella; }
    public String getHuellaCorta() { return huella.substring(0, 12); }
    public String getContenido() { return contenido; }
}
