package pe.utp.epptrack.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.utp.epptrack.model.ConsumoLote;

import java.util.List;

public interface ConsumoLoteRepository extends JpaRepository<ConsumoLote, Integer> {

    List<ConsumoLote> findByDetalleIdDetalleAndRevertidoFalse(Integer idDetalle);

    List<ConsumoLote> findByDetalleIdDetalleOrderByIdConsumoAsc(Integer idDetalle);

    List<ConsumoLote> findByLoteIdLoteOrderByIdConsumoAsc(Integer idLote);
}
