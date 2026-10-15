package pe.utp.epptrack.repository;

import pe.utp.epptrack.model.Proceso;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProcesoRepository extends JpaRepository<Proceso, Integer> {

    List<Proceso> findAllByOrderByNombreAsc();

    Optional<Proceso> findByNombre(String nombre);
}
