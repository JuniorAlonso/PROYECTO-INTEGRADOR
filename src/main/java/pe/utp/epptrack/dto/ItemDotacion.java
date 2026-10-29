package pe.utp.epptrack.dto;

import pe.utp.epptrack.model.DetalleEntrega;
import pe.utp.epptrack.model.Epp;

/**
 * Un EPP que la Matriz exige a un trabajador y, si lo tiene, la entrega que lo cubre.
 *
 * @param enUso entrega vigente o por vencer de ese EPP; {@code null} si el trabajador no lo tiene
 */
public record ItemDotacion(Epp epp, int cantidadRequerida, boolean obligatorio, DetalleEntrega enUso, int enPoder) {

    /** Cubierto solo si la suma de lo vigente alcanza lo que exige la matriz (una entrega parcial no cubre). */
    public boolean isCubierto() { return enPoder >= cantidadRequerida; }

    /** Unidades que aún faltan para cumplir la matriz (0 si ya está cubierto). */
    public int getPendiente() { return Math.max(0, cantidadRequerida - enPoder); }

    /** Tiene algo vigente pero menos de lo exigido. */
    public boolean isParcial() { return enPoder > 0 && enPoder < cantidadRequerida; }
}
