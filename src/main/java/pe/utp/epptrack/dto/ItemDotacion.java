package pe.utp.epptrack.dto;

import pe.utp.epptrack.model.DetalleEntrega;
import pe.utp.epptrack.model.Epp;

/**
 * Un EPP que la Matriz exige a un trabajador y, si lo tiene, la entrega que lo cubre.
 *
 * @param enUso entrega vigente o por vencer de ese EPP; {@code null} si el trabajador no lo tiene
 */
public record ItemDotacion(Epp epp, int cantidadRequerida, boolean obligatorio, DetalleEntrega enUso) {

    public boolean isCubierto() { return enUso != null; }
}
