package pe.utp.epptrack.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pe.utp.epptrack.model.EstadoTrabajador;
import pe.utp.epptrack.model.Trabajador;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TrabajadorRepository extends JpaRepository<Trabajador, Integer> {

    Optional<Trabajador> findByDni(String dni);

    /** Bloquea la fila del trabajador hasta el fin de la transacción: serializa entregas simultáneas del mismo trabajador. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trabajador t where t.idTrabajador = :id")
    Optional<Trabajador> findByIdParaActualizar(@Param("id") Integer id);

    boolean existsByDniAndIdTrabajadorNot(String dni, Integer idTrabajador);

    List<Trabajador> findByEstadoInOrderByApellidosAscNombresAsc(Collection<EstadoTrabajador> estados);

    long countByEstadoIn(Collection<EstadoTrabajador> estados);

    /** Búsqueda por DNI o nombre, con filtros opcionales de sede y estado (RF-01). */
    @Query("""
            select t from Trabajador t
            where (:texto is null
                   or t.dni like concat('%', :texto, '%')
                   or lower(concat(t.apellidos, ' ', t.nombres)) like lower(concat('%', :texto, '%')))
              and (:idSede is null or t.sede.idSede = :idSede)
              and (:estado is null or t.estado = :estado)
            order by t.apellidos, t.nombres
            """)
    List<Trabajador> buscar(@Param("texto") String texto,
                            @Param("idSede") Integer idSede,
                            @Param("estado") EstadoTrabajador estado);
}
