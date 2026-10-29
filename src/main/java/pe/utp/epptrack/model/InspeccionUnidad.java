package pe.utp.epptrack.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Inspección de una unidad devuelta. Registra quién inspeccionó, cuándo y con qué resultado. La autorización de
 * retorno al almacén es una decisión humana posterior (los criterios de SST están pendientes de validación);
 * mientras no se tome, {@code autorizacionPendiente} es verdadero.
 */
@Entity
@Table(name = "inspeccion_unidad")
public class InspeccionUnidad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idInspeccion;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_unidad", nullable = false)
    private UnidadEpp unidad;

    @Column(nullable = false)
    private LocalDateTime fecha = LocalDateTime.now();

    @Column(nullable = false, length = 50)
    private String inspector;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ResultadoInspeccion resultado;

    @Column(length = 450)
    private String observaciones;

    @Column(nullable = false)
    private boolean autorizacionPendiente;

    public InspeccionUnidad() {}

    public InspeccionUnidad(UnidadEpp unidad, String inspector, ResultadoInspeccion resultado, String observaciones) {
        this.unidad = unidad;
        this.inspector = inspector;
        this.resultado = resultado;
        this.observaciones = observaciones;
        this.autorizacionPendiente = resultado == ResultadoInspeccion.APTO_PARA_USO;
    }

    public Integer getIdInspeccion() { return idInspeccion; }
    public UnidadEpp getUnidad() { return unidad; }
    public LocalDateTime getFecha() { return fecha; }
    public String getInspector() { return inspector; }
    public ResultadoInspeccion getResultado() { return resultado; }
    public String getObservaciones() { return observaciones; }
    public boolean isAutorizacionPendiente() { return autorizacionPendiente; }
    public void setAutorizacionPendiente(boolean autorizacionPendiente) { this.autorizacionPendiente = autorizacionPendiente; }
}
