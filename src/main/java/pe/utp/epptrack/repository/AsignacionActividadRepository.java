package pe.utp.epptrack.repository;

import pe.utp.epptrack.model.AsignacionActividad;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AsignacionActividadRepository extends JpaRepository<AsignacionActividad, Integer> {

    List<AsignacionActividad> findByTrabajadorIdTrabajadorAndFechaFinIsNull(Integer idTrabajador);

    List<AsignacionActividad> findByTrabajadorIdTrabajadorOrderByFechaInicioDesc(Integer idTrabajador);

    List<AsignacionActividad> findByFechaFinIsNull();
}
