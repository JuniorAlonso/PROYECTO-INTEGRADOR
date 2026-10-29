package pe.utp.epptrack.dto;

import pe.utp.epptrack.model.Actividad;
import pe.utp.epptrack.model.DetalleEntrega;
import pe.utp.epptrack.model.Epp;
import pe.utp.epptrack.model.Trabajador;

import java.util.List;

/**
 * Resultado explicable del motor de dotación (RF-22, RF-24): qué exige la matriz, qué tiene el trabajador,
 * qué falta entregar (dotación neta) y por qué. Cada verificación cita la regla que la produjo.
 * <p>
 * Importante: el resultado CUMPLE / NO_CUMPLE indica conformidad con la Matriz de EPP registrada. No es una
 * autorización de inicio de labores: los criterios de habilitación deben ser aprobados por SST de la empresa.
 */
public record EvaluacionDotacion(Trabajador trabajador, List<Actividad> actividades, String versionMatriz,
                                 Resultado resultado, List<Verificacion> verificaciones, List<Pendiente> pendientes,
                                 List<Alerta> alertas) {

    public enum Resultado {
        CUMPLE("Cumple la matriz", "success"),
        NO_CUMPLE("No cumple la matriz", "danger"),
        NO_EVALUABLE("No se puede evaluar", "secondary");

        private final String etiqueta;
        private final String color;
        Resultado(String etiqueta, String color) { this.etiqueta = etiqueta; this.color = color; }
        public String getEtiqueta() { return etiqueta; }
        public String getColor() { return color; }
    }

    public enum EstadoItem {
        CUBIERTO("Cubierto", "success"),
        POR_VENCER("Cubierto, por vencer", "warning"),
        PARCIAL("Cantidad incompleta", "danger"),
        VENCIDO("Vencido", "danger"),
        FALTANTE("Nunca entregado", "danger"),
        OPCIONAL("Opcional", "secondary"),
        NO_EXIGIDO("En uso, no exigido hoy", "info");

        private final String etiqueta;
        private final String color;
        EstadoItem(String etiqueta, String color) { this.etiqueta = etiqueta; this.color = color; }
        public String getEtiqueta() { return etiqueta; }
        public String getColor() { return color; }
    }

    public enum Nivel {
        BLOQUEO("danger"), ADVERTENCIA("warning"), INFO("info");
        private final String color;
        Nivel(String color) { this.color = color; }
        public String getColor() { return color; }
    }

    /** Una línea de la explicación: un EPP, su estado, la regla aplicada y el porqué. */
    public record Verificacion(Epp epp, boolean obligatorio, int requerida, int enPoder, EstadoItem estado,
                               String regla, String explicacion, DetalleEntrega cubiertaPor) {}

    /** Lo que falta entregar para cumplir la matriz (dotación neta). */
    public record Pendiente(Epp epp, int cantidad, String motivo) {}

    public record Alerta(Nivel nivel, String codigo, String mensaje) {}

    public boolean isBloqueada() {
        return alertas.stream().anyMatch(a -> a.nivel() == Nivel.BLOQUEO);
    }

    public int getTotalPendiente() {
        return pendientes.stream().mapToInt(Pendiente::cantidad).sum();
    }
}
