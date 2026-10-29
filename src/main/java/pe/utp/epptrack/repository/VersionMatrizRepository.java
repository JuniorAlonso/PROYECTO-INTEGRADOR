package pe.utp.epptrack.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.utp.epptrack.model.VersionMatriz;

import java.util.List;
import java.util.Optional;

public interface VersionMatrizRepository extends JpaRepository<VersionMatriz, Integer> {

    Optional<VersionMatriz> findFirstByVigenteTrueOrderByIdVersionDesc();

    List<VersionMatriz> findAllByOrderByIdVersionDesc();

    boolean existsByCodigoIgnoreCase(String codigo);
}
