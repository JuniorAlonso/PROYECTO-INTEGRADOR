package pe.utp.epptrack.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pe.utp.epptrack.model.Epp;

import java.util.List;
import java.util.Optional;

public interface EppRepository extends JpaRepository<Epp, Integer> {

    List<Epp> findAllByOrderByCategoriaNombreAscNombreAsc();

    List<Epp> findByEstadoOrderByCategoriaNombreAscNombreAsc(String estado);

    /**
     * Bloquea la fila del EPP hasta el fin de la transacción: serializa los ingresos (que generan códigos
     * correlativos) y las asignaciones de unidades del mismo EPP, para que dos operaciones simultáneas no
     * tomen la misma unidad ni repitan un código.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Epp e where e.idEpp = :id")
    Optional<Epp> findByIdParaActualizar(@Param("id") Integer id);
}
