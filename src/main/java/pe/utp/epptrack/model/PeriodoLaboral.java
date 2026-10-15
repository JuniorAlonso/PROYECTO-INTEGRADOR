package pe.utp.epptrack.model;

import jakarta.persistence.*;

import java.time.LocalDate;

/** Cada campaña en la que labora un trabajador. Permite conservar el historial entre reingresos (RN-04). */
@Entity
@Table(name = "periodo_laboral")
public class PeriodoLaboral {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id_periodo")
    private Integer idPeriodo;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_trabajador", nullable = false)
    private Trabajador trabajador;

    @Column(nullable = false, length = 100)
    private String campana;

    @Column(nullable = false)
    private LocalDate fechaIngreso;

    private LocalDate fechaCese;

    public PeriodoLaboral() {}

    public PeriodoLaboral(Trabajador trabajador, String campana, LocalDate fechaIngreso, LocalDate fechaCese) {
        this.trabajador = trabajador;
        this.campana = campana;
        this.fechaIngreso = fechaIngreso;
        this.fechaCese = fechaCese;
    }

    public boolean isAbierto() { return fechaCese == null; }

    public Integer getIdPeriodo() { return idPeriodo; }
    public void setIdPeriodo(Integer idPeriodo) { this.idPeriodo = idPeriodo; }
    public Trabajador getTrabajador() { return trabajador; }
    public void setTrabajador(Trabajador trabajador) { this.trabajador = trabajador; }
    public String getCampana() { return campana; }
    public void setCampana(String campana) { this.campana = campana; }
    public LocalDate getFechaIngreso() { return fechaIngreso; }
    public void setFechaIngreso(LocalDate fechaIngreso) { this.fechaIngreso = fechaIngreso; }
    public LocalDate getFechaCese() { return fechaCese; }
    public void setFechaCese(LocalDate fechaCese) { this.fechaCese = fechaCese; }
}
