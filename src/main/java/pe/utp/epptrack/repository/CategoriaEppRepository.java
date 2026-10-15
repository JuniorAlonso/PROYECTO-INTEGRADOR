package pe.utp.epptrack.repository;

import pe.utp.epptrack.model.CategoriaEpp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoriaEppRepository extends JpaRepository<CategoriaEpp, Integer> {

    List<CategoriaEpp> findAllByOrderByNombreAsc();
}
