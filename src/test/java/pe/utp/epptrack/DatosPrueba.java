package pe.utp.epptrack;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import pe.utp.epptrack.dto.EntregaForm;
import pe.utp.epptrack.dto.ItemEntregaForm;
import pe.utp.epptrack.model.*;
import pe.utp.epptrack.repository.*;
import pe.utp.epptrack.service.TrabajadorService;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * Escenario mínimo para las pruebas: una actividad "Aplicación con mochila" que exige respirador y guantes,
 * un supervisor y un trabajador asignado a esa actividad.
 */
@Component
public class DatosPrueba {

    public Usuario supervisor;
    public Sede sede;
    public Puesto puesto;
    public Actividad actividad;
    public Epp respirador;
    public Epp guantes;
    public Epp casco;

    private final RolRepository rolRepository;
    private final UsuarioRepository usuarioRepository;
    private final SedeRepository sedeRepository;
    private final PuestoRepository puestoRepository;
    private final ProcesoRepository procesoRepository;
    private final ActividadRepository actividadRepository;
    private final CategoriaEppRepository categoriaRepository;
    private final EppRepository eppRepository;
    private final MatrizAsignacionRepository matrizRepository;
    private final TrabajadorService trabajadorService;
    private final PasswordEncoder passwordEncoder;

    public DatosPrueba(RolRepository rolRepository, UsuarioRepository usuarioRepository, SedeRepository sedeRepository,
                       PuestoRepository puestoRepository, ProcesoRepository procesoRepository,
                       ActividadRepository actividadRepository, CategoriaEppRepository categoriaRepository,
                       EppRepository eppRepository, MatrizAsignacionRepository matrizRepository,
                       TrabajadorService trabajadorService, PasswordEncoder passwordEncoder) {
        this.rolRepository = rolRepository;
        this.usuarioRepository = usuarioRepository;
        this.sedeRepository = sedeRepository;
        this.puestoRepository = puestoRepository;
        this.procesoRepository = procesoRepository;
        this.actividadRepository = actividadRepository;
        this.categoriaRepository = categoriaRepository;
        this.eppRepository = eppRepository;
        this.matrizRepository = matrizRepository;
        this.trabajadorService = trabajadorService;
        this.passwordEncoder = passwordEncoder;
    }

    /** Debe llamarse dentro de una prueba @Transactional para que se revierta al terminar. */
    public void cargar() {
        Rol rol = rolRepository.save(new Rol(Rol.SUPERVISOR, null));
        supervisor = usuarioRepository.save(new Usuario(rol, "sup-test", passwordEncoder.encode("clave")));
        sede = sedeRepository.save(new Sede("Fundo Test", "Salas", "Fundo"));
        puesto = puestoRepository.save(new Puesto("Aplicador", null, "Sanidad"));
        actividad = actividadRepository.save(new Actividad(procesoRepository.save(new Proceso("Sanidad")), "Aplicación con mochila"));

        CategoriaEpp categoria = categoriaRepository.save(new CategoriaEpp("General"));
        respirador = eppRepository.save(new Epp(categoria, "Respirador", null, 365));
        guantes = eppRepository.save(new Epp(categoria, "Guantes de nitrilo", null, 30));
        casco = eppRepository.save(new Epp(categoria, "Casco", null, 730));
        matrizRepository.save(new MatrizAsignacion(actividad, respirador, true, 1));
        matrizRepository.save(new MatrizAsignacion(actividad, guantes, true, 2));
    }

    public Trabajador trabajador(String dni, LocalDate ingreso) {
        Trabajador t = new Trabajador();
        t.setDni(dni);
        t.setApellidos("Pérez");
        t.setNombres("Juan");
        t.setPuesto(puesto);
        t.setSede(sede);
        return trabajadorService.registrar(t, actividad.getIdActividad(), "Campaña test", ingreso);
    }

    public EntregaForm formulario(Trabajador t, LocalDate fecha, Epp... epps) {
        EntregaForm form = new EntregaForm();
        form.setIdTrabajador(t.getIdTrabajador());
        form.setFechaEntrega(fecha);
        form.setMotivo(MotivoEntrega.INGRESO);
        List<ItemEntregaForm> items = Arrays.stream(epps).map(e -> new ItemEntregaForm(e.getIdEpp(), true, 1)).toList();
        form.setItems(items);
        return form;
    }
}
