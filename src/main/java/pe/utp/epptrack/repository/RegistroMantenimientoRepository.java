package pe.utp.epptrack.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import pe.utp.epptrack.model.EstadoRegistroMantenimiento;
import pe.utp.epptrack.model.RegistroMantenimiento;

import java.util.List;
import java.util.Optional;

public interface RegistroMantenimientoRepository extends JpaRepository<RegistroMantenimiento, Integer> {

    List<RegistroMantenimiento> findByUnidadIdUnidadOrderByFechaInicioDescIdRegistroDesc(Integer idUnidad);

    Optional<RegistroMantenimiento> findFirstByUnidadIdUnidadAndEstadoOrderByIdRegistroDesc(
            Integer idUnidad, EstadoRegistroMantenimiento estado);

    @EntityGraph(attributePaths = {"unidad.epp"})
    List<RegistroMantenimiento> findByEstadoOrderByIdRegistroDesc(EstadoRegistroMantenimiento estado);
}
