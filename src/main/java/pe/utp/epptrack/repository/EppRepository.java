package pe.utp.epptrack.repository;

import pe.utp.epptrack.model.Epp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EppRepository extends JpaRepository<Epp, Integer> {

    List<Epp> findAllByOrderByCategoriaNombreAscNombreAsc();

    List<Epp> findByEstadoOrderByCategoriaNombreAscNombreAsc(String estado);
}
