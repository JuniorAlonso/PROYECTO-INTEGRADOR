package pe.utp.epptrack.repository;

import pe.utp.epptrack.model.MatrizAsignacion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Collection;

public interface MatrizAsignacionRepository extends JpaRepository<MatrizAsignacion, Integer> {

    List<MatrizAsignacion> findAllByOrderByActividadProcesoNombreAscActividadNombreAscEppNombreAsc();

    List<MatrizAsignacion> findByActividadIdActividadOrderByEppNombreAsc(Integer idActividad);

    List<MatrizAsignacion> findByActividadIdActividadIn(Collection<Integer> idsActividad);

    boolean existsByActividadIdActividadAndEppIdEpp(Integer idActividad, Integer idEpp);
}
