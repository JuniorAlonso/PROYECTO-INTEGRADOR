package pe.utp.epptrack.repository;

import pe.utp.epptrack.model.PeriodoLaboral;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PeriodoLaboralRepository extends JpaRepository<PeriodoLaboral, Integer> {

    List<PeriodoLaboral> findByTrabajadorIdTrabajadorOrderByFechaIngresoDesc(Integer idTrabajador);

    List<PeriodoLaboral> findByTrabajadorIdTrabajadorAndFechaCeseIsNull(Integer idTrabajador);
}
