package pe.utp.epptrack.model;

/**
 * Resultado que registra quien inspecciona una unidad devuelta. Ninguno deja la unidad disponible por sí solo:
 * incluso «apto para uso» pasa a POR_AUTORIZAR y una persona debe autorizar el retorno al almacén.
 */
public enum ResultadoInspeccion {
    APTO_PARA_USO("Apto para uso (pendiente de autorización)", EstadoUnidad.POR_AUTORIZAR),
    REQUIERE_MANTENIMIENTO("Requiere mantenimiento", EstadoUnidad.POR_MANTENIMIENTO),
    NO_APTO_CUARENTENA("No apto: cuarentena", EstadoUnidad.EN_CUARENTENA),
    NO_APTO_BAJA("No apto: baja", EstadoUnidad.DE_BAJA);

    private final String etiqueta;
    private final EstadoUnidad destino;

    ResultadoInspeccion(String etiqueta, EstadoUnidad destino) {
        this.etiqueta = etiqueta;
        this.destino = destino;
    }

    public String getEtiqueta() { return etiqueta; }
    public EstadoUnidad getDestino() { return destino; }
}
