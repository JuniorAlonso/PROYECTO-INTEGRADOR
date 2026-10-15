package pe.utp.epptrack.repository;

import pe.utp.epptrack.model.Actividad;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActividadRepository extends JpaRepository<Actividad, Integer> {

    List<Actividad> findAllByOrderByProcesoNombreAscNombreAsc();
}
