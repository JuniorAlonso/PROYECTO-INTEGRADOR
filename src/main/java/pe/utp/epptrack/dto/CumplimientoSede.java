package pe.utp.epptrack.dto;

import pe.utp.epptrack.model.Sede;

public record CumplimientoSede(Sede sede, long trabajadores, long completos) {

    public int getPorcentaje() {
        return trabajadores == 0 ? 0 : (int) Math.round(completos * 100.0 / trabajadores);
    }

    public long getIncompletos() { return trabajadores - completos; }
}
