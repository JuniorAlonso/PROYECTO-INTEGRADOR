package pe.utp.epptrack.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.model.*;
import pe.utp.epptrack.repository.ConsumoLoteRepository;
import pe.utp.epptrack.repository.EppRepository;
import pe.utp.epptrack.repository.LoteConsumibleRepository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Control por cantidad y lotes de los EPP desechables. El stock es un saldo por lote; una unidad de consumible
 * nunca queda "asignada" ni se devuelve (no hay devolución individual ficticia). Las salidas siguen FEFO y se
 * trazan por detalle de entrega para poder revertirlas si la entrega se anula.
 */
@Service
@Transactional
public class LoteService {

    private static final int MAX_CANTIDAD = 100_000;

    private final LoteConsumibleRepository loteRepository;
    private final ConsumoLoteRepository consumoRepository;
    private final EppRepository eppRepository;
    private final AuditoriaService auditoria;

    public LoteService(LoteConsumibleRepository loteRepository, ConsumoLoteRepository consumoRepository,
                       EppRepository eppRepository, AuditoriaService auditoria) {
        this.loteRepository = loteRepository;
        this.consumoRepository = consumoRepository;
        this.eppRepository = eppRepository;
        this.auditoria = auditoria;
    }

    public LoteConsumible ingresarLote(Integer idEpp, int cantidad, String codigoLote, LocalDate vencimiento,
                                       String observacion, String usuario) {
        Epp epp = eppRepository.findByIdParaActualizar(idEpp)
                .orElseThrow(() -> new ReglaNegocioException("El EPP indicado no existe."));
        if (!epp.isActivo()) throw new ReglaNegocioException("El EPP está inactivo; no se pueden ingresar lotes.");
        if (!epp.isDesechable()) {
            throw new ReglaNegocioException("Solo los EPP desechables se controlan por lotes; los reutilizables se identifican por unidad");
        }
        if (cantidad < 1 || cantidad > MAX_CANTIDAD) {
            throw new ReglaNegocioException("La cantidad del lote debe estar entre 1 y " + MAX_CANTIDAD + ".");
        }
        if (vencimiento != null && vencimiento.isBefore(LocalDate.now())) {
            throw new ReglaNegocioException("La fecha de vencimiento del lote no puede ser anterior a hoy.");
        }
        String obs = observacion == null || observacion.isBlank() ? null : observacion.trim();
        if (obs != null && obs.length() > 255) throw new ReglaNegocioException("La observación admite hasta 255 caracteres.");
        String codigo = codigoLote == null || codigoLote.isBlank() ? generarCodigo(epp) : codigoLote.trim();
        if (codigo.length() > 40) throw new ReglaNegocioException("El código de lote admite hasta 40 caracteres.");
        if (loteRepository.existsByEppIdEppAndCodigoLote(idEpp, codigo)) {
            throw new ReglaNegocioException("Ya existe el lote " + codigo + " para este EPP.");
        }
        LoteConsumible lote = loteRepository.save(new LoteConsumible(epp, codigo, cantidad, vencimiento, obs, usuario));
        auditoria.registrar(usuario, "LOTE_INGRESO", "LoteConsumible", lote.getIdLote(),
                "EPP " + epp.getNombre() + "; lote " + codigo + "; cantidad " + cantidad
                        + (vencimiento == null ? "" : "; vence " + vencimiento));
        return lote;
    }

    /** Descuenta hasta {@code cantidad} unidades (FEFO, sin lotes vencidos); devuelve lo realmente descontado. */
    public int consumir(DetalleEntrega detalle, int cantidad, String usuario) {
        if (cantidad <= 0) return 0;
        Integer idEpp = detalle.getEpp().getIdEpp();
        eppRepository.findByIdParaActualizar(idEpp);
        int pendiente = cantidad;
        StringBuilder traza = new StringBuilder();
        for (LoteConsumible lote : loteRepository.disponiblesFefo(idEpp, LocalDate.now())) {
            if (pendiente == 0) break;
            int tomar = Math.min(pendiente, lote.getSaldo());
            if (tomar <= 0) continue;
            lote.setSaldo(lote.getSaldo() - tomar);
            consumoRepository.save(new ConsumoLote(lote, detalle, tomar, usuario));
            pendiente -= tomar;
            traza.append(" ").append(lote.getCodigoLote()).append("x").append(tomar);
        }
        int descontado = cantidad - pendiente;
        if (descontado > 0) {
            auditoria.registrar(usuario, "LOTE_CONSUMO", "DetalleEntrega", detalle.getIdDetalle(),
                    "Descontado " + descontado + " de " + cantidad + " solicitado(s):" + traza);
        }
        return descontado;
    }

    /** Devuelve al saldo lo consumido por el detalle (anulación de la entrega). Idempotente. */
    public void revertir(DetalleEntrega detalle, String usuario) {
        eppRepository.findByIdParaActualizar(detalle.getEpp().getIdEpp());
        List<ConsumoLote> consumos = consumoRepository.findByDetalleIdDetalleAndRevertidoFalse(detalle.getIdDetalle());
        if (consumos.isEmpty()) return;
        int total = 0;
        for (ConsumoLote c : consumos) {
            c.getLote().setSaldo(c.getLote().getSaldo() + c.getCantidad());
            c.setRevertido(true);
            total += c.getCantidad();
        }
        auditoria.registrar(usuario, "LOTE_REVERSION", "DetalleEntrega", detalle.getIdDetalle(),
                "Restituidas " + total + " unidad(es) a sus lotes por anulación");
    }

    @Transactional(readOnly = true)
    public long saldo(Integer idEpp) {
        return loteRepository.saldoUtilizable(idEpp, LocalDate.now());
    }

    @Transactional(readOnly = true)
    public Map<Integer, Long> saldosPorEpp() {
        Map<Integer, Long> mapa = new HashMap<>();
        for (Object[] fila : loteRepository.saldosPorEpp(LocalDate.now())) {
            mapa.put((Integer) fila[0], ((Number) fila[1]).longValue());
        }
        return mapa;
    }

    @Transactional(readOnly = true)
    public List<LoteConsumible> lotes(Integer idEpp) {
        return loteRepository.findByEppIdEppOrderByFechaIngresoDescIdLoteDesc(idEpp);
    }

    /** Todos los lotes (más recientes primero), para la pantalla de inventario. */
    @Transactional(readOnly = true)
    public List<LoteConsumible> todosLosLotes() {
        return loteRepository.findAll(org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Order.desc("fechaIngreso"),
                org.springframework.data.domain.Sort.Order.desc("idLote")));
    }

    /** Lotes con saldo que vencen en los próximos {@code dias} días (no incluye los ya vencidos). */
    @Transactional(readOnly = true)
    public long lotesPorVencer(int dias) {
        LocalDate hoy = LocalDate.now();
        return loteRepository.contarPorVencer(hoy, hoy.plusDays(dias));
    }

    private String generarCodigo(Epp epp) {
        long n = loteRepository.countByEppIdEpp(epp.getIdEpp()) + 1;
        String codigo;
        do {
            codigo = String.format("L-EPP%03d-%04d", epp.getIdEpp(), n++);
        } while (loteRepository.existsByEppIdEppAndCodigoLote(epp.getIdEpp(), codigo));
        return codigo;
    }
}
