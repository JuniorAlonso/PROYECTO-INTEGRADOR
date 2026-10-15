package pe.utp.epptrack.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pe.utp.epptrack.model.DetalleEntrega;
import pe.utp.epptrack.model.EstadoVigencia;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface DetalleEntregaRepository extends JpaRepository<DetalleEntrega, Integer> {

    List<DetalleEntrega> findByEntregaTrabajadorIdTrabajadorAndEstadoVigenciaIn(Integer idTrabajador,
                                                                                Collection<EstadoVigencia> estados);

    List<DetalleEntrega> findByEntregaTrabajadorIdTrabajadorAndEppIdEppAndEstadoVigenciaIn(Integer idTrabajador,
                                                                                           Integer idEpp,
                                                                                           Collection<EstadoVigencia> estados);

    @EntityGraph(attributePaths = {"entrega.trabajador.sede", "epp.categoria"})
    List<DetalleEntrega> findByEstadoVigenciaInOrderByFechaRenovacionAsc(Collection<EstadoVigencia> estados);

    @EntityGraph(attributePaths = {"entrega.sede", "epp"})
    List<DetalleEntrega> findByEntregaFechaEntregaBetween(LocalDate desde, LocalDate hasta);

    long countByEstadoVigencia(EstadoVigencia estado);

    /** Pasa a VENCIDO todo lo que estaba en uso y cuya fecha de renovación ya pasó. */
    @Modifying
    @Query("""
            update DetalleEntrega d set d.estadoVigencia = pe.utp.epptrack.model.EstadoVigencia.VENCIDO
            where d.estadoVigencia in (pe.utp.epptrack.model.EstadoVigencia.VIGENTE, pe.utp.epptrack.model.EstadoVigencia.POR_VENCER)
              and d.fechaRenovacion < :hoy
            """)
    int marcarVencidos(@Param("hoy") LocalDate hoy);

    @Modifying
    @Query("""
            update DetalleEntrega d set d.estadoVigencia = pe.utp.epptrack.model.EstadoVigencia.POR_VENCER
            where d.estadoVigencia = pe.utp.epptrack.model.EstadoVigencia.VIGENTE
              and d.fechaRenovacion between :hoy and :limite
            """)
    int marcarPorVencer(@Param("hoy") LocalDate hoy, @Param("limite") LocalDate limite);
}
