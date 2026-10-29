package pe.utp.epptrack.model;

import jakarta.persistence.*;

import java.time.LocalDate;

/**
 * Lote de un EPP desechable (guantes, mascarillas). El consumible no se controla por unidad: se lleva un saldo
 * por lote y cada salida queda trazada en {@link ConsumoLote}. No existe devolución individual de consumibles.
 */
@Entity
@Table(name = "lote_consumible",
        uniqueConstraints = @UniqueConstraint(name = "uk_lote_epp_codigo", columnNames = {"id_epp", "codigo_lote"}))
public class LoteConsumible {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idLote;

    @ManyToOne(optional = false)
    @JoinColumn(name = "id_epp", nullable = false)
    private Epp epp;

    @Column(name = "codigo_lote", nullable = false, length = 40)
    private String codigoLote;

    @Column(nullable = false)
    private int cantidadInicial;

    @Column(nullable = false)
    private int saldo;

    @Column(nullable = false)
    private LocalDate fechaIngreso = LocalDate.now();

    private LocalDate fechaVencimiento;

    @Column(length = 255)
    private String observacion;

    @Column(length = 100)
    private String usuario;

    public LoteConsumible() {}

    public LoteConsumible(Epp epp, String codigoLote, int cantidadInicial, LocalDate fechaVencimiento,
                          String observacion, String usuario) {
        this.epp = epp;
        this.codigoLote = codigoLote;
        this.cantidadInicial = cantidadInicial;
        this.saldo = cantidadInicial;
        this.fechaVencimiento = fechaVencimiento;
        this.observacion = observacion;
        this.usuario = usuario;
    }

    public boolean isVencido() { return fechaVencimiento != null && fechaVencimiento.isBefore(LocalDate.now()); }

    public Integer getIdLote() { return idLote; }
    public void setIdLote(Integer idLote) { this.idLote = idLote; }
    public Epp getEpp() { return epp; }
    public void setEpp(Epp epp) { this.epp = epp; }
    public String getCodigoLote() { return codigoLote; }
    public void setCodigoLote(String codigoLote) { this.codigoLote = codigoLote; }
    public int getCantidadInicial() { return cantidadInicial; }
    public void setCantidadInicial(int cantidadInicial) { this.cantidadInicial = cantidadInicial; }
    public int getSaldo() { return saldo; }
    public void setSaldo(int saldo) { this.saldo = saldo; }
    public LocalDate getFechaIngreso() { return fechaIngreso; }
    public void setFechaIngreso(LocalDate fechaIngreso) { this.fechaIngreso = fechaIngreso; }
    public LocalDate getFechaVencimiento() { return fechaVencimiento; }
    public void setFechaVencimiento(LocalDate fechaVencimiento) { this.fechaVencimiento = fechaVencimiento; }
    public String getObservacion() { return observacion; }
    public void setObservacion(String observacion) { this.observacion = observacion; }
    public String getUsuario() { return usuario; }
    public void setUsuario(String usuario) { this.usuario = usuario; }
}
