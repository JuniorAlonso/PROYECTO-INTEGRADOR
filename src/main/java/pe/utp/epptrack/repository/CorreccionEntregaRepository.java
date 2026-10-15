package pe.utp.epptrack.repository;

import pe.utp.epptrack.model.CorreccionEntrega;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CorreccionEntregaRepository extends JpaRepository<CorreccionEntrega, Integer> {

    List<CorreccionEntrega> findByDetalleEntregaTrabajadorIdTrabajadorOrderByFechaCorreccionDesc(Integer idTrabajador);
}
