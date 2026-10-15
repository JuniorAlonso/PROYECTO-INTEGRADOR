package pe.utp.epptrack.repository;

import pe.utp.epptrack.model.Sede;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SedeRepository extends JpaRepository<Sede, Integer> {

    List<Sede> findAllByOrderByNombreAsc();
}
