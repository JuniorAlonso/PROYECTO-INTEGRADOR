package pe.utp.epptrack.repository;

import pe.utp.epptrack.model.HistorialEpp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HistorialEppRepository extends JpaRepository<HistorialEpp, Integer> {

    List<HistorialEpp> findByTrabajadorIdTrabajadorOrderByFechaRegistroDescIdHistorialDesc(Integer idTrabajador);
}
