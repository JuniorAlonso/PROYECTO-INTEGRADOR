package pe.utp.epptrack.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Mantenimiento de una unidad: diagnóstico al enviarla y, al cerrarlo, trabajo realizado, costo y resultado. */
@Entity
@Table(name = "registro_mantenimiento")
public class RegistroMantenimiento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idRegistro;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_unidad", nullable = false)
    private UnidadEpp unidad;

    @Column(nullable = false)
    private LocalDateTime fechaInicio = LocalDateTime.now();

    @Column(nullable = false, length = 450)
    private String diagnostico;

    @Column(length = 450)
    private String accionPrevista;

    @Column(nullable = false, length = 50)
    private String responsable;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private EstadoRegistroMantenimiento estado = EstadoRegistroMantenimiento.ABIERTO;

    private LocalDateTime fechaFin;

    @Column(length = 450)
    private String accionRealizada;

    @Column(precision = 10, scale = 2)
    private BigDecimal costo;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ResultadoMantenimiento resultado;

    @Column(length = 450)
    private String observacion;

    @Column(length = 50)
    private String cerradoPor;

    public RegistroMantenimiento() {}

    public RegistroMantenimiento(UnidadEpp unidad, String diagnostico, String accionPrevista, String responsable) {
        this.unidad = unidad;
        this.diagnostico = diagnostico;
        this.accionPrevista = accionPrevista;
        this.responsable = responsable;
    }

    public Integer getIdRegistro() { return idRegistro; }
    public UnidadEpp getUnidad() { return unidad; }
    public LocalDateTime getFechaInicio() { return fechaInicio; }
    public void setFechaInicio(LocalDateTime fechaInicio) { this.fechaInicio = fechaInicio; }
    public String getDiagnostico() { return diagnostico; }
    public String getAccionPrevista() { return accionPrevista; }
    public String getResponsable() { return responsable; }
    public EstadoRegistroMantenimiento getEstado() { return estado; }
    public void setEstado(EstadoRegistroMantenimiento estado) { this.estado = estado; }
    public LocalDateTime getFechaFin() { return fechaFin; }
    public void setFechaFin(LocalDateTime fechaFin) { this.fechaFin = fechaFin; }
    public String getAccionRealizada() { return accionRealizada; }
    public void setAccionRealizada(String accionRealizada) { this.accionRealizada = accionRealizada; }
    public BigDecimal getCosto() { return costo; }
    public void setCosto(BigDecimal costo) { this.costo = costo; }
    public ResultadoMantenimiento getResultado() { return resultado; }
    public void setResultado(ResultadoMantenimiento resultado) { this.resultado = resultado; }
    public String getObservacion() { return observacion; }
    public void setObservacion(String observacion) { this.observacion = observacion; }
    public String getCerradoPor() { return cerradoPor; }
    public void setCerradoPor(String cerradoPor) { this.cerradoPor = cerradoPor; }
}
