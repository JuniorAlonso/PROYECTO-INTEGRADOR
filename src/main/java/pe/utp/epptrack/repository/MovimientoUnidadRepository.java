package pe.utp.epptrack.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.utp.epptrack.model.MovimientoUnidad;
import pe.utp.epptrack.model.TipoMovimientoUnidad;

import java.util.List;

public interface MovimientoUnidadRepository extends JpaRepository<MovimientoUnidad, Integer> {

    List<MovimientoUnidad> findByUnidadIdUnidadOrderByFechaDescIdMovimientoDesc(Integer idUnidad);

    List<MovimientoUnidad> findTop50ByTipoOrderByFechaDescIdMovimientoDesc(TipoMovimientoUnidad tipo);

    List<MovimientoUnidad> findByTipo(TipoMovimientoUnidad tipo);
}
