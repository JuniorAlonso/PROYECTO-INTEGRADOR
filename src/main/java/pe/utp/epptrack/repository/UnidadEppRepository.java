package pe.utp.epptrack.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import pe.utp.epptrack.model.EstadoUnidad;
import pe.utp.epptrack.model.UnidadEpp;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UnidadEppRepository extends JpaRepository<UnidadEpp, Integer> {

    Optional<UnidadEpp> findByCodigoIgnoreCase(String codigo);

    /** Bloquea la unidad para que dos operaciones simultáneas no cambien su estado a la vez. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UnidadEpp u where u.idUnidad = :id")
    Optional<UnidadEpp> findByIdParaActualizar(@Param("id") Integer id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UnidadEpp u where upper(u.codigo) = upper(:codigo)")
    Optional<UnidadEpp> findByCodigoParaActualizar(@Param("codigo") String codigo);

    long countByEppIdEpp(Integer idEpp);

    List<UnidadEpp> findByEppIdEppAndEstadoOrderByIdUnidadAsc(Integer idEpp, EstadoUnidad estado);

    List<UnidadEpp> findByDetalleIdDetalle(Integer idDetalle);

    List<UnidadEpp> findByDetalleIdDetalleIn(List<Integer> idsDetalle);

    List<UnidadEpp> findByEstadoOrderByCodigoAsc(EstadoUnidad estado);

    List<UnidadEpp> findAllByOrderByCodigoAsc();

    /** Filas [idEpp, estado, cantidad] para armar el inventario en una sola consulta. */
    @Query("select u.epp.idEpp, u.estado, count(u) from UnidadEpp u group by u.epp.idEpp, u.estado")
    List<Object[]> contarPorEppYEstado();
}
