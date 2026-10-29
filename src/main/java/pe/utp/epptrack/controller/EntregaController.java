package pe.utp.epptrack.controller;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import pe.utp.epptrack.dto.EntregaForm;
import pe.utp.epptrack.dto.EstadoDotacion;
import pe.utp.epptrack.dto.ItemDotacion;
import pe.utp.epptrack.dto.ItemEntregaForm;
import pe.utp.epptrack.model.DetalleEntrega;
import pe.utp.epptrack.model.Epp;
import pe.utp.epptrack.model.MotivoEntrega;
import pe.utp.epptrack.model.Trabajador;
import pe.utp.epptrack.repository.EppRepository;
import pe.utp.epptrack.service.DotacionService;
import pe.utp.epptrack.service.EntregaService;
import pe.utp.epptrack.service.MotorDotacionService;
import pe.utp.epptrack.service.ReglaNegocioException;
import pe.utp.epptrack.service.TrabajadorService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Registro de entregas de EPP (CU-05, RF-04 a RF-07) y anulación auditada (RF-13). */
@Controller
@RequestMapping("/entregas")
public class EntregaController {

    /** Una fila del formulario: el EPP, si lo exige la matriz y la entrega que el trabajador ya tiene en uso. */
    public record FilaEntrega(Epp epp, boolean enMatriz, boolean obligatorio, DetalleEntrega enUso,
                              int requerida, int enPoder, int pendiente) {}

    private final TrabajadorService trabajadorService;
    private final DotacionService dotacionService;
    private final EntregaService entregaService;
    private final EppRepository eppRepository;
    private final MotorDotacionService motorDotacionService;

    public EntregaController(TrabajadorService trabajadorService, DotacionService dotacionService,
                             EntregaService entregaService, EppRepository eppRepository,
                             MotorDotacionService motorDotacionService) {
        this.motorDotacionService = motorDotacionService;
        this.trabajadorService = trabajadorService;
        this.dotacionService = dotacionService;
        this.entregaService = entregaService;
        this.eppRepository = eppRepository;
    }

    @GetMapping("/nueva")
    public String nueva(@RequestParam(required = false) String dni, Model model) {
        model.addAttribute("dni", dni);
        if (dni == null || dni.isBlank()) {
            return "entregas/nueva";
        }
        var encontrado = trabajadorService.buscarPorDni(dni);
        if (encontrado.isEmpty()) {
            model.addAttribute("error", "No existe un trabajador con el DNI " + dni.trim() + ".");
            return "entregas/nueva";
        }
        Trabajador trabajador = encontrado.get();
        EstadoDotacion dotacion = dotacionService.estadoDotacion(trabajador);

        EntregaForm form = new EntregaForm();
        form.setIdTrabajador(trabajador.getIdTrabajador());
        List<ItemEntregaForm> items = new ArrayList<>();
        Set<Integer> enMatriz = new java.util.HashSet<>();
        for (ItemDotacion i : dotacion.items()) {
            enMatriz.add(i.epp().getIdEpp());
            items.add(new ItemEntregaForm(i.epp().getIdEpp(), i.obligatorio() && !i.isCubierto(), i.cantidadRequerida()));
        }
        for (Epp e : eppRepository.findByEstadoOrderByCategoriaNombreAscNombreAsc(Epp.ACTIVO)) {
            if (!enMatriz.contains(e.getIdEpp())) {
                items.add(new ItemEntregaForm(e.getIdEpp(), false, 1));
            }
        }
        form.setItems(items);
        return formulario(model, trabajador, dotacion, form, Set.of());
    }

    @PostMapping
    public String registrar(@ModelAttribute("form") EntregaForm form, Authentication auth,
                            Model model, RedirectAttributes flash) {
        Trabajador trabajador;
        try {
            if (form.getIdTrabajador() == null) {
                throw new ReglaNegocioException("Busque primero al trabajador por su DNI.");
            }
            trabajador = trabajadorService.obtener(form.getIdTrabajador());
        } catch (ReglaNegocioException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/entregas/nueva";
        }
        boolean eppValidos = form.getItems().stream()
                .allMatch(i -> i.getIdEpp() != null && eppRepository.existsById(i.getIdEpp()));
        if (!eppValidos) {
            flash.addFlashAttribute("error", "El formulario contiene un EPP que no existe. Vuelva a cargarlo.");
            return "redirect:/entregas/nueva?dni=" + trabajador.getDni();
        }
        List<Integer> duplicados = entregaService.duplicadosSinJustificar(form);
        List<Integer> noExigidos = entregaService.noExigidosSinJustificar(form);
        List<Integer> excesos = entregaService.excesosSinJustificar(form);
        List<Integer> observados = new ArrayList<>(duplicados);
        noExigidos.stream().filter(id -> !observados.contains(id)).forEach(observados::add);
        excesos.stream().filter(id -> !observados.contains(id)).forEach(observados::add);
        try {
            if (!observados.isEmpty()) {
                List<String> razones = new ArrayList<>();
                if (!duplicados.isEmpty()) {
                    razones.add("ya tiene vigentes algunos EPP marcados (RF-07)");
                }
                if (!noExigidos.isEmpty()) {
                    razones.add("algunos EPP marcados no los exige la matriz para su actividad (RN-06)");
                }
                if (!excesos.isEmpty()) {
                    razones.add("algunas cantidades superan lo que falta según la matriz (RN-08)");
                }
                model.addAttribute("error", "No se registró: " + String.join(" y ", razones)
                        + ". Escriba la justificación en las filas resaltadas o desmárquelas.");
            } else {
                var entrega = entregaService.registrar(form, auth.getName());
                String codigos = entrega.getCodigosAsignados().isEmpty() ? ""
                        : " Códigos asignados: " + String.join(", ", entrega.getCodigosAsignados()) + ".";
                flash.addFlashAttribute("exito", "Entrega registrada para " + trabajador.getNombreCompleto() + "." + codigos);
                if (!entrega.getSinStock().isEmpty()) {
                    flash.addFlashAttribute("error", "Sin stock con código en almacén para: "
                            + String.join(", ", entrega.getSinStock()) + ". Registre el ingreso en Inventario.");
                }
                return "redirect:/historial?dni=" + trabajador.getDni();
            }
        } catch (ReglaNegocioException e) {
            model.addAttribute("error", e.getMessage());
        }
        model.addAttribute("dni", trabajador.getDni());
        return formulario(model, trabajador, dotacionService.estadoDotacion(trabajador), form, Set.copyOf(observados));
    }

    @PostMapping("/detalle/{id}/anular")
    public String anular(@PathVariable Integer id, @RequestParam String motivo, Authentication auth,
                         RedirectAttributes flash) {
        DetalleEntrega detalle;
        try {
            detalle = entregaService.obtenerDetalle(id);
        } catch (ReglaNegocioException e) {
            flash.addFlashAttribute("error", e.getMessage());
            return "redirect:/historial";
        }
        try {
            entregaService.anularDetalle(id, motivo, auth.getName());
            flash.addFlashAttribute("exito", "Registro anulado. Queda la corrección en el historial.");
        } catch (ReglaNegocioException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/historial?dni=" + detalle.getEntrega().getTrabajador().getDni();
    }

    private String formulario(Model model, Trabajador trabajador, EstadoDotacion dotacion,
                              EntregaForm form, Set<Integer> duplicados) {
        Map<Integer, ItemDotacion> deMatriz = new java.util.HashMap<>();
        dotacion.items().forEach(i -> deMatriz.put(i.epp().getIdEpp(), i));
        Map<Integer, DetalleEntrega> enUso = entregaService.enUsoPorEpp(trabajador.getIdTrabajador());

        List<FilaEntrega> filas = new ArrayList<>();
        for (ItemEntregaForm item : form.getItems()) {
            Epp epp = eppRepository.findById(item.getIdEpp()).orElseThrow();
            ItemDotacion regla = deMatriz.get(epp.getIdEpp());
            filas.add(new FilaEntrega(epp, regla != null, regla != null && regla.obligatorio(), enUso.get(epp.getIdEpp()),
                    regla == null ? 0 : regla.cantidadRequerida(), regla == null ? 0 : regla.enPoder(),
                    regla == null ? 0 : regla.getPendiente()));
        }
        model.addAttribute("form", form);
        model.addAttribute("trabajador", trabajador);
        model.addAttribute("dotacion", dotacion);
        model.addAttribute("filas", filas);
        model.addAttribute("duplicados", duplicados);
        model.addAttribute("evaluacion", motorDotacionService.evaluar(trabajador));
        model.addAttribute("motivos", MotivoEntrega.values());
        return "entregas/nueva";
    }
}
