package pe.utp.epptrack.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.utp.epptrack.model.RegistroAuditoria;

import java.util.List;
import java.util.Optional;

public interface RegistroAuditoriaRepository extends JpaRepository<RegistroAuditoria, Long> {

    Optional<RegistroAuditoria> findFirstByOrderByIdRegistroDesc();

    List<RegistroAuditoria> findAllByOrderByIdRegistroAsc();

    List<RegistroAuditoria> findTop200ByOrderByIdRegistroDesc();

    List<RegistroAuditoria> findByEntidadAndIdEntidadOrderByIdRegistroAsc(String entidad, String idEntidad);
}
