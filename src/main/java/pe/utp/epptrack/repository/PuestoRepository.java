package pe.utp.epptrack.repository;

import pe.utp.epptrack.model.Puesto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PuestoRepository extends JpaRepository<Puesto, Integer> {

    List<Puesto> findAllByOrderByNombreAsc();
}
