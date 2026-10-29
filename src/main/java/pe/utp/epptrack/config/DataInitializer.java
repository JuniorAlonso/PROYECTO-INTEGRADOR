package pe.utp.epptrack.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pe.utp.epptrack.dto.EntregaForm;
import pe.utp.epptrack.dto.ItemEntregaForm;
import pe.utp.epptrack.model.*;
import pe.utp.epptrack.repository.*;
import pe.utp.epptrack.service.EntregaService;
import pe.utp.epptrack.service.TrabajadorService;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Carga datos simulados la primera vez que arranca la aplicación (base vacía). Los trabajadores son ficticios
 * para resguardar los datos personales del personal de la empresa (punto 1.11 del informe).
 * Se desactiva con {@code app.datos-demo=false}.
 */
@Component
@org.springframework.core.annotation.Order(1)
@ConditionalOnProperty(name = "app.datos-demo", havingValue = "true", matchIfMissing = true)
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

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
    private final EntregaService entregaService;
    private final pe.utp.epptrack.service.MatrizVersionService versionService;
    private final PasswordEncoder passwordEncoder;

    private final Map<String, Epp> epps = new HashMap<>();
    private final Map<String, Actividad> actividades = new HashMap<>();

    public DataInitializer(RolRepository rolRepository, UsuarioRepository usuarioRepository,
                           SedeRepository sedeRepository, PuestoRepository puestoRepository,
                           ProcesoRepository procesoRepository, ActividadRepository actividadRepository,
                           CategoriaEppRepository categoriaRepository, EppRepository eppRepository,
                           MatrizAsignacionRepository matrizRepository, TrabajadorService trabajadorService,
                           EntregaService entregaService, PasswordEncoder passwordEncoder,
                           pe.utp.epptrack.service.MatrizVersionService versionService) {
        this.versionService = versionService;
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
        this.entregaService = entregaService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (rolRepository.count() > 0) {
            return;
        }
        log.warn("Base vacía: se cargan DATOS DE DEMOSTRACIÓN con usuarios de contraseña conocida. "
                + "No usar en producción: ponga DATOS_DEMO=false.");
        cargarUsuarios();
        cargarCatalogo();
        cargarMatriz();
        // Las entregas de demostración quedan asociadas a una versión publicada de la matriz (RF-21).
        versionService.publicar("demo-1", "Matriz de demostración con datos ficticios; no es la Matriz v6 real de CASURSA",
                "sistema");
        cargarTrabajadoresYEntregas();
    }

    /** Usuarios de prueba. Cambiar las contraseñas antes de usar el sistema con datos reales. */
    private void cargarUsuarios() {
        Rol admin = rolRepository.save(new Rol(Rol.ADMIN, "Configura datos maestros: trabajadores, catálogo de EPP y matriz"));
        Rol supervisor = rolRepository.save(new Rol(Rol.SUPERVISOR, "Registra entregas, controla vigencias y genera reportes"));
        Rol rrhh = rolRepository.save(new Rol(Rol.RRHH, "Registra ingresos, ceses y reingresos; consulta historiales"));
        usuarioRepository.save(new Usuario(admin, "admin", passwordEncoder.encode("admin123")));
        usuarioRepository.save(new Usuario(supervisor, "supervisor", passwordEncoder.encode("super123")));
        usuarioRepository.save(new Usuario(rrhh, "rrhh", passwordEncoder.encode("rrhh123")));
    }

    private void cargarCatalogo() {
        CategoriaEpp cabeza = categoriaRepository.save(new CategoriaEpp("Protección para cabeza"));
        CategoriaEpp auditiva = categoriaRepository.save(new CategoriaEpp("Protección auditiva"));
        CategoriaEpp respiratoria = categoriaRepository.save(new CategoriaEpp("Protección respiratoria"));
        CategoriaEpp ocular = categoriaRepository.save(new CategoriaEpp("Protección ocular"));
        CategoriaEpp ropa = categoriaRepository.save(new CategoriaEpp("Ropa de protección"));
        CategoriaEpp manos = categoriaRepository.save(new CategoriaEpp("Protección para manos"));
        CategoriaEpp pies = categoriaRepository.save(new CategoriaEpp("Protección para pies"));

        epp("sombrero", cabeza, "Sombrero con protector de nuca", "Protección contra radiación solar", 180);
        epp("casco", cabeza, "Casco de seguridad", "Clase E, con barbiquejo", 730);
        epp("tapones", auditiva, "Tapones auditivos", "Reutilizables, NRR 25 dB", 30);
        epp("orejeras", auditiva, "Orejeras", "Para operación de maquinaria", 365);
        epp("respirador", respiratoria, "Respirador media cara", "Con cartuchos para vapores orgánicos", 365);
        epp("cartuchos", respiratoria, "Cartuchos para vapores orgánicos", "Par de cartuchos de repuesto", 30);
        epp("n95", respiratoria, "Mascarilla N95", "Descartable, para polvo", 7);
        epp("lentes", ocular, "Lentes de seguridad claros", "Antiempañantes", 180);
        epp("lentesUv", ocular, "Lentes de seguridad oscuros", "Filtro UV", 180);
        epp("careta", ocular, "Careta facial", "Para preparación de mezclas", 365);
        epp("traje", ropa, "Traje impermeable para aplicación", "PVC, pantalón y casaca", 90);
        epp("mandil", ropa, "Mandil de PVC", "Para manipulación de químicos", 180);
        epp("poloUv", ropa, "Polo manga larga con protección UV", "Tela UPF 50+", 180);
        epp("chaleco", ropa, "Chaleco reflectivo", "Alta visibilidad", 365);
        epp("bloqueador", ropa, "Bloqueador solar", "FPS 50, frasco 120 ml", 30);
        epp("nitrilo", manos, "Guantes de nitrilo", "Resistentes a químicos", 30);
        epp("cuero", manos, "Guantes de cuero", "Para carga y herramientas", 90);
        epp("anticorte", manos, "Guantes anticorte", "Nivel 5, para tijeras de cosecha", 60);
        epp("hilo", manos, "Guantes de hilo", "Para selección y empaque", 15);
        epp("botasJebe", pies, "Botas de jebe", "Caña alta", 180);
        epp("zapatos", pies, "Zapatos de seguridad", "Con puntera de acero", 365);
    }

    private void cargarMatriz() {
        actividad("cosecha", "Recolección de uva de mesa", "Cosecha de racimos",
                "sombrero", "poloUv", "bloqueador", "anticorte", "lentesUv", "botasJebe");
        actividad("acarreo", "Recolección de uva de mesa", "Acarreo de jabas",
                "sombrero", "poloUv", "bloqueador", "cuero", "zapatos");
        actividad("seleccion", "Recolección de uva de mesa", "Selección en campo",
                "sombrero", "poloUv", "bloqueador", "hilo");
        actividad("mochila", "Sanidad y aplicaciones fitosanitarias", "Aplicación con mochila",
                "respirador", "cartuchos", "lentes", "traje", "nitrilo", "botasJebe", "sombrero");
        actividad("nebulizador", "Sanidad y aplicaciones fitosanitarias", "Aplicación con tractor nebulizador",
                "respirador", "cartuchos", "lentes", "traje", "nitrilo", "botasJebe", "orejeras");
        actividad("mezclas", "Sanidad y aplicaciones fitosanitarias", "Preparación de mezclas",
                "respirador", "cartuchos", "careta", "mandil", "nitrilo", "botasJebe");
        actividad("poda", "Mantenimiento de cultivo", "Poda",
                "sombrero", "poloUv", "bloqueador", "anticorte", "lentes", "botasJebe");
        actividad("raleo", "Mantenimiento de cultivo", "Raleo de bayas",
                "sombrero", "poloUv", "bloqueador", "anticorte", "lentesUv");
        actividad("riego", "Riego y fertilización", "Operación del cabezal de riego",
                "sombrero", "botasJebe", "cuero", "lentes");
        actividad("fertirriego", "Riego y fertilización", "Inyección de fertilizantes",
                "respirador", "nitrilo", "careta", "mandil", "botasJebe");
        actividad("almacen", "Almacenamiento", "Recepción y despacho de insumos",
                "casco", "chaleco", "cuero", "zapatos");
        actividad("agroquimicos", "Almacenamiento", "Almacén de agroquímicos",
                "respirador", "nitrilo", "lentes", "mandil", "zapatos");
        actividad("tractor", "Transporte interno", "Conducción de tractor",
                "orejeras", "lentesUv", "sombrero", "chaleco", "zapatos");
        actividad("empaque", "Empaque", "Embalaje de uva", "hilo", "n95", "zapatos");
        actividad("paletizado", "Empaque", "Paletizado y estiba", "casco", "cuero", "chaleco", "zapatos", "tapones");
    }

    private void cargarTrabajadoresYEntregas() {
        Sede norte = sedeRepository.save(new Sede("Fundo Norte", "Salas", "Fundo"));
        Sede sur = sedeRepository.save(new Sede("Fundo Sur", "Santiago", "Fundo"));
        Sede tinguina = sedeRepository.save(new Sede("Fundo La Tinguiña", "La Tinguiña", "Fundo"));
        Sede planta = sedeRepository.save(new Sede("Planta de empaque", "Pueblo Nuevo", "Planta"));
        sedeRepository.save(new Sede("Almacén central", "San Juan Bautista", "Depósito"));

        Puesto cosechador = puestoRepository.save(new Puesto("Cosechador", "Corte y acarreo de racimos", "Producción"));
        Puesto aplicador = puestoRepository.save(new Puesto("Aplicador fitosanitario", "Aplicación de productos fitosanitarios", "Sanidad"));
        Puesto podador = puestoRepository.save(new Puesto("Podador", "Poda y raleo", "Producción"));
        Puesto regador = puestoRepository.save(new Puesto("Regador", "Operación del sistema de riego", "Riego"));
        Puesto almacenero = puestoRepository.save(new Puesto("Almacenero", "Control de insumos y EPP", "Logística"));
        Puesto tractorista = puestoRepository.save(new Puesto("Operador de tractor", "Conducción de maquinaria agrícola", "Maquinaria"));
        Puesto empacador = puestoRepository.save(new Puesto("Empacador", "Embalaje y paletizado", "Planta de empaque"));

        LocalDate hoy = LocalDate.now();
        String campana = "Campaña uva " + hoy.getYear() + "-" + (hoy.getYear() + 1);

        // Dotación completa y reciente
        Trabajador t1 = trabajador("45872310", "Quispe Huamán", "Rosa Elena", cosechador, norte, "cosecha", campana, hoy.minusDays(40));
        entregar(t1, hoy.minusDays(40), MotivoEntrega.INGRESO, "sombrero", "poloUv", "bloqueador", "anticorte", "lentesUv", "botasJebe");
        // Entrega duplicada justificada: perdió los guantes cuando aún estaban vigentes (RF-07)
        EntregaForm perdida = formulario(t1, hoy.minusDays(6), MotivoEntrega.PERDIDA, "anticorte");
        perdida.getItems().getFirst().setJustificacion("Extravió los guantes en el lote 4; se verificó con el capataz");
        entregaService.registrar(perdida, "supervisor");

        // Sombrero vencido y bloqueador por vencer
        Trabajador t2 = trabajador("47120985", "Mendoza Pariona", "Luis Alberto", cosechador, norte, "cosecha", campana, hoy.minusDays(190));
        entregar(t2, hoy.minusDays(190), MotivoEntrega.INGRESO, "sombrero", "poloUv", "lentesUv", "botasJebe");
        entregar(t2, hoy.minusDays(80), MotivoEntrega.RENOVACION, "anticorte");
        entregar(t2, hoy.minusDays(20), MotivoEntrega.RENOVACION, "bloqueador");

        // Aplicador con dotación incompleta (le falta respirador y cartuchos)
        Trabajador t3 = trabajador("70345128", "Huarcaya Espinoza", "Jorge Luis", aplicador, sur, "mochila", campana, hoy.minusDays(25));
        entregar(t3, hoy.minusDays(25), MotivoEntrega.INGRESO, "lentes", "traje", "nitrilo", "botasJebe", "sombrero");

        // Aplicador completo con guantes por vencer
        Trabajador t4 = trabajador("72981534", "Pecho Cahua", "Miguel Ángel", aplicador, sur, "nebulizador", campana, hoy.minusDays(80));
        entregar(t4, hoy.minusDays(80), MotivoEntrega.INGRESO, "respirador", "lentes", "traje", "botasJebe", "orejeras");
        entregar(t4, hoy.minusDays(20), MotivoEntrega.RENOVACION, "cartuchos", "nitrilo");

        Trabajador t5 = trabajador("46210873", "Ramos Tataje", "Carmen Rosa", podador, tinguina, "poda", campana, hoy.minusDays(60));
        entregar(t5, hoy.minusDays(60), MotivoEntrega.INGRESO, "sombrero", "poloUv", "anticorte", "lentes", "botasJebe");
        entregar(t5, hoy.minusDays(10), MotivoEntrega.RENOVACION, "bloqueador");

        Trabajador t6 = trabajador("41538260", "Uchuya Martínez", "Pedro Pablo", regador, tinguina, "riego", campana, hoy.minusDays(175));
        entregar(t6, hoy.minusDays(175), MotivoEntrega.INGRESO, "sombrero", "botasJebe", "cuero", "lentes");

        Trabajador t7 = trabajador("43870125", "Cabrera Levano", "Julio César", almacenero, planta, "agroquimicos", campana, hoy.minusDays(100));
        entregar(t7, hoy.minusDays(100), MotivoEntrega.INGRESO, "respirador", "lentes", "mandil", "zapatos");
        entregar(t7, hoy.minusDays(5), MotivoEntrega.RENOVACION, "nitrilo");

        Trabajador t8 = trabajador("75610294", "Saravia Donayre", "Ana Lucía", empacador, planta, "empaque", campana, hoy.minusDays(12));
        entregar(t8, hoy.minusDays(12), MotivoEntrega.INGRESO, "hilo", "zapatos");

        Trabajador t9 = trabajador("44187356", "Gonzales Ccahua", "Wilmer", tractorista, norte, "tractor", campana, hoy.minusDays(200));
        entregar(t9, hoy.minusDays(200), MotivoEntrega.INGRESO, "orejeras", "lentesUv", "sombrero", "chaleco", "zapatos");

        // Sin ninguna entrega aún
        trabajador("76023418", "Yupanqui Soto", "Milagros", cosechador, sur, "seleccion", campana, hoy.minusDays(2));

        // Reincorporado: trabajó la campaña pasada, cesó y volvió. Conserva su historial (RN-04).
        String campanaAnterior = "Campaña uva " + (hoy.getYear() - 1) + "-" + hoy.getYear();
        Trabajador t11 = trabajador("42756019", "Ormeño Anyosa", "Teodoro", cosechador, norte, "cosecha", campanaAnterior, hoy.minusDays(330));
        entregar(t11, hoy.minusDays(330), MotivoEntrega.INGRESO, "sombrero", "poloUv", "bloqueador", "anticorte", "lentesUv", "botasJebe");
        entregar(t11, hoy.minusDays(150), MotivoEntrega.RENOVACION, "sombrero", "poloUv", "botasJebe");
        trabajadorService.registrarCese(t11.getIdTrabajador(), hoy.minusDays(120));
        trabajadorService.registrarReingreso(t11.getIdTrabajador(), campana, hoy.minusDays(15), norte.getIdSede(),
                actividades.get("cosecha").getIdActividad());
        entregar(t11, hoy.minusDays(15), MotivoEntrega.REINGRESO, "anticorte", "lentesUv", "bloqueador");

        // Cesado: su historial queda disponible para un futuro reingreso
        Trabajador t12 = trabajador("40981267", "Tasayco Hernández", "Felícita", podador, sur, "raleo", campanaAnterior, hoy.minusDays(300));
        entregar(t12, hoy.minusDays(300), MotivoEntrega.INGRESO, "sombrero", "poloUv", "anticorte", "lentesUv");
        trabajadorService.registrarCese(t12.getIdTrabajador(), hoy.minusDays(100));
    }

    private void epp(String clave, CategoriaEpp categoria, String nombre, String descripcion, int vidaUtilDias) {
        Epp epp = new Epp(categoria, nombre, descripcion, vidaUtilDias);
        // Clasificación de DEMOSTRACIÓN: ninguna está validada por SST (claseValidada = false) ni es política de CASURSA.
        epp.setClase(DESECHABLES_DEMO.contains(clave) ? ClaseEpp.DESECHABLE : ClaseEpp.REUTILIZABLE_INDIVIDUAL);
        epp.setClaseValidada(false);
        epps.put(clave, eppRepository.save(epp));
    }

    private static final java.util.Set<String> DESECHABLES_DEMO = java.util.Set.of("n95", "cartuchos", "bloqueador", "nitrilo");

    private void actividad(String clave, String nombreProceso, String nombreActividad, String... claveEpps) {
        Proceso proceso = procesoRepository.findByNombre(nombreProceso)
                .orElseGet(() -> procesoRepository.save(new Proceso(nombreProceso)));
        Actividad actividad = actividadRepository.save(new Actividad(proceso, nombreActividad));
        actividades.put(clave, actividad);
        for (String claveEpp : claveEpps) {
            int cantidad = claveEpp.equals("cartuchos") || claveEpp.equals("nitrilo") ? 2 : 1;
            matrizRepository.save(new MatrizAsignacion(actividad, epps.get(claveEpp), true, cantidad));
        }
    }

    private Trabajador trabajador(String dni, String apellidos, String nombres, Puesto puesto, Sede sede,
                                  String claveActividad, String campana, LocalDate ingreso) {
        Trabajador t = new Trabajador();
        t.setDni(dni);
        t.setApellidos(apellidos);
        t.setNombres(nombres);
        t.setFechaNacimiento(LocalDate.of(1985 + Integer.parseInt(dni.substring(6)) % 20, 1 + dni.charAt(2) % 12, 10));
        t.setPuesto(puesto);
        t.setSede(sede);
        return trabajadorService.registrar(t, actividades.get(claveActividad).getIdActividad(), campana, ingreso);
    }

    private void entregar(Trabajador trabajador, LocalDate fecha, MotivoEntrega motivo, String... claveEpps) {
        entregaService.registrar(formulario(trabajador, fecha, motivo, claveEpps), "supervisor");
    }

    private EntregaForm formulario(Trabajador trabajador, LocalDate fecha, MotivoEntrega motivo, String... claveEpps) {
        EntregaForm form = new EntregaForm();
        form.setIdTrabajador(trabajador.getIdTrabajador());
        form.setFechaEntrega(fecha);
        form.setMotivo(motivo);
        List<ItemEntregaForm> items = Arrays.stream(claveEpps)
                .map(c -> new ItemEntregaForm(epps.get(c).getIdEpp(), true, c.equals("cartuchos") || c.equals("nitrilo") ? 2 : 1))
                .toList();
        form.setItems(items);
        return form;
    }
}
