package pe.utp.epptrack.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.utp.epptrack.model.InspeccionUnidad;

import java.util.List;

public interface InspeccionUnidadRepository extends JpaRepository<InspeccionUnidad, Integer> {

    List<InspeccionUnidad> findByUnidadIdUnidadOrderByFechaDescIdInspeccionDesc(Integer idUnidad);
}
