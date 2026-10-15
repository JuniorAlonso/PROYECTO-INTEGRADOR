package pe.utp.epptrack.dto;

import pe.utp.epptrack.model.Actividad;
import pe.utp.epptrack.model.Trabajador;

import java.util.List;

/** Comparación entre lo que la Matriz de EPP exige a un trabajador y lo que tiene en uso (RF-04, RF-10). */
public record EstadoDotacion(Trabajador trabajador, List<Actividad> actividades, List<ItemDotacion> items) {

    public List<ItemDotacion> getFaltantes() {
        return items.stream().filter(i -> i.obligatorio() && !i.isCubierto()).toList();
    }

    public long getObligatorios() {
        return items.stream().filter(ItemDotacion::obligatorio).count();
    }

    public long getCubiertos() {
        return items.stream().filter(i -> i.obligatorio() && i.isCubierto()).count();
    }

    /** Sin actividad asignada no hay exigencia; se considera completa solo si tiene al menos una actividad. */
    public boolean isCompleta() {
        return !actividades.isEmpty() && getFaltantes().isEmpty();
    }

    public int getPorcentaje() {
        long obligatorios = getObligatorios();
        return obligatorios == 0 ? 0 : (int) Math.round(getCubiertos() * 100.0 / obligatorios);
    }
}
