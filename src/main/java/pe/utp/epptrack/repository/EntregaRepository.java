package pe.utp.epptrack.repository;

import pe.utp.epptrack.model.Entrega;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface EntregaRepository extends JpaRepository<Entrega, Integer> {

    List<Entrega> findByTrabajadorIdTrabajadorOrderByFechaEntregaDescIdEntregaDesc(Integer idTrabajador);

    List<Entrega> findByTrabajadorIdTrabajadorAndFechaEntregaBetweenOrderByFechaEntregaDesc(Integer idTrabajador, LocalDate desde, LocalDate hasta);
}
