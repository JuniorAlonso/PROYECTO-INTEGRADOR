package pe.utp.epptrack.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pe.utp.epptrack.model.LoteConsumible;

import java.time.LocalDate;
import java.util.List;

public interface LoteConsumibleRepository extends JpaRepository<LoteConsumible, Integer> {

    boolean existsByEppIdEppAndCodigoLote(Integer idEpp, String codigoLote);

    long countByEppIdEpp(Integer idEpp);

    List<LoteConsumible> findByEppIdEppOrderByFechaIngresoDescIdLoteDesc(Integer idEpp);

    /** Lotes utilizables (saldo > 0, no vencidos) en orden FEFO: vence primero sale primero; sin vencimiento al final, luego FIFO. */
    @Query("""
            select l from LoteConsumible l
            where l.epp.idEpp = :idEpp and l.saldo > 0
              and (l.fechaVencimiento is null or l.fechaVencimiento >= :hoy)
            order by case when l.fechaVencimiento is null then 1 else 0 end, l.fechaVencimiento, l.fechaIngreso, l.idLote""")
    List<LoteConsumible> disponiblesFefo(@Param("idEpp") Integer idEpp, @Param("hoy") LocalDate hoy);

    @Query("""
            select coalesce(sum(l.saldo), 0) from LoteConsumible l
            where l.epp.idEpp = :idEpp and (l.fechaVencimiento is null or l.fechaVencimiento >= :hoy)""")
    long saldoUtilizable(@Param("idEpp") Integer idEpp, @Param("hoy") LocalDate hoy);

    @Query("""
            select l.epp.idEpp, sum(l.saldo) from LoteConsumible l
            where l.fechaVencimiento is null or l.fechaVencimiento >= :hoy
            group by l.epp.idEpp""")
    List<Object[]> saldosPorEpp(@Param("hoy") LocalDate hoy);

    @Query("""
            select count(l) from LoteConsumible l
            where l.saldo > 0 and l.fechaVencimiento is not null
              and l.fechaVencimiento >= :hoy and l.fechaVencimiento <= :limite""")
    long contarPorVencer(@Param("hoy") LocalDate hoy, @Param("limite") LocalDate limite);
}
