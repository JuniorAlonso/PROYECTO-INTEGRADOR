package pe.utp.epptrack.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.model.Epp;
import pe.utp.epptrack.repository.EppRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Altas y cambios del catálogo de EPP. Cada cambio relevante queda en la bitácora con el valor anterior y el nuevo. */
@Service
public class EppService {

    private final EppRepository eppRepository;
    private final AuditoriaService auditoriaService;

    public EppService(EppRepository eppRepository, AuditoriaService auditoriaService) {
        this.eppRepository = eppRepository;
        this.auditoriaService = auditoriaService;
    }

    @Transactional
    public Epp guardar(Epp datos, String usuario) {
        if (datos.getEstado() == null) {
            datos.setEstado(Epp.ACTIVO);
        }
        Epp anterior = datos.getIdEpp() == null ? null : eppRepository.findById(datos.getIdEpp()).orElse(null);
        List<String> cambios = anterior == null ? List.of() : diferencias(anterior, datos);
        Epp guardado;
        if (anterior == null) {
            guardado = eppRepository.save(datos);
            auditoriaService.registrar(usuario, "EPP_CREADO", "epp", guardado.getIdEpp(),
                    guardado.getNombre() + "; clase " + guardado.getClaseEfectiva().name()
                            + "; vida útil " + guardado.getVidaUtilDias() + " días");
        } else {
            copiar(datos, anterior);
            guardado = anterior;
            if (!cambios.isEmpty()) {
                auditoriaService.registrar(usuario, "EPP_MODIFICADO", "epp", guardado.getIdEpp(),
                        guardado.getNombre() + ": " + String.join("; ", cambios));
            }
        }
        return guardado;
    }

    @Transactional
    public Epp cambiarEstado(Integer id, String usuario) {
        Epp epp = eppRepository.findById(id).orElseThrow(() -> new RecursoNoEncontradoException("EPP no encontrado."));
        epp.setEstado(epp.isActivo() ? Epp.INACTIVO : Epp.ACTIVO);
        auditoriaService.registrar(usuario, epp.isActivo() ? "EPP_ACTIVADO" : "EPP_DESACTIVADO", "epp", id, epp.getNombre());
        return epp;
    }

    private static void copiar(Epp origen, Epp destino) {
        destino.setCategoria(origen.getCategoria());
        destino.setNombre(origen.getNombre());
        destino.setDescripcion(origen.getDescripcion());
        destino.setVidaUtilDias(origen.getVidaUtilDias());
        destino.setCostoUnitario(origen.getCostoUnitario());
        destino.setClase(origen.getClase());
        destino.setClaseValidada(origen.getClaseValidada());
        destino.setDevolucionPermitida(origen.getDevolucionPermitida());
        destino.setInspeccionAlDevolver(origen.getInspeccionAlDevolver());
    }

    private static List<String> diferencias(Epp a, Epp b) {
        List<String> d = new ArrayList<>();
        comparar(d, "nombre", a.getNombre(), b.getNombre());
        comparar(d, "categoría", a.getCategoria().getNombre(), b.getCategoria().getNombre());
        comparar(d, "vida útil (días)", a.getVidaUtilDias(), b.getVidaUtilDias());
        comparar(d, "costo unitario", a.getCostoUnitario(), b.getCostoUnitario());
        comparar(d, "clase", a.getClaseEfectiva(), b.getClaseEfectiva());
        comparar(d, "clase validada por SST", Boolean.TRUE.equals(a.getClaseValidada()), Boolean.TRUE.equals(b.getClaseValidada()));
        comparar(d, "devolución permitida", a.isPermiteDevolucion(), b.isPermiteDevolucion());
        comparar(d, "inspección al devolver", a.isRequiereInspeccionAlDevolver(), b.isRequiereInspeccionAlDevolver());
        return d;
    }

    private static void comparar(List<String> lista, String campo, Object antes, Object despues) {
        boolean igual = antes instanceof java.math.BigDecimal x && despues instanceof java.math.BigDecimal y
                ? x.compareTo(y) == 0 : Objects.equals(antes, despues);
        if (!igual) {
            lista.add(campo + ": " + antes + " → " + despues);
        }
    }
}
