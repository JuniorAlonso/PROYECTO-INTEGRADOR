package pe.utp.epptrack.model;

import jakarta.persistence.*;

import java.time.LocalDate;

/** Actividad que desempeña un trabajador en un rango de fechas. La vigente es la que no tiene fecha_fin. */
@Entity
@Table(name = "asignacion_actividad")
public class AsignacionActividad {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idAsignacion;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_trabajador", nullable = false)
    private Trabajador trabajador;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_actividad", nullable = false)
    private Actividad actividad;

    @Column(nullable = false)
    private LocalDate fechaInicio;

    private LocalDate fechaFin;

    public AsignacionActividad() {}

    public AsignacionActividad(Trabajador trabajador, Actividad actividad, LocalDate fechaInicio) {
        this.trabajador = trabajador;
        this.actividad = actividad;
        this.fechaInicio = fechaInicio;
    }

    public Integer getIdAsignacion() { return idAsignacion; }
    public void setIdAsignacion(Integer idAsignacion) { this.idAsignacion = idAsignacion; }
    public Trabajador getTrabajador() { return trabajador; }
    public void setTrabajador(Trabajador trabajador) { this.trabajador = trabajador; }
    public Actividad getActividad() { return actividad; }
    public void setActividad(Actividad actividad) { this.actividad = actividad; }
    public LocalDate getFechaInicio() { return fechaInicio; }
    public void setFechaInicio(LocalDate fechaInicio) { this.fechaInicio = fechaInicio; }
    public LocalDate getFechaFin() { return fechaFin; }
    public void setFechaFin(LocalDate fechaFin) { this.fechaFin = fechaFin; }
}
