// Comportamiento común de las vistas (sin scripts inline, para poder aplicar una CSP estricta).
document.querySelectorAll('[data-autosubmit]').forEach(el =>
    el.addEventListener('change', () => el.form.submit()));

document.querySelectorAll('[data-imprimir]').forEach(el =>
    el.addEventListener('click', () => window.print()));

const modalAnular = document.getElementById('modalAnular');
if (modalAnular) {
    modalAnular.addEventListener('show.bs.modal', ev => {
        const form = document.getElementById('formAnular');
        form.action = form.dataset.base + ev.relatedTarget.getAttribute('data-detalle') + '/anular';
    });
}

// Evita el doble envío de formularios (p. ej. una entrega registrada dos veces).
document.querySelectorAll('form[method="post"]:not([data-sin-bloqueo])').forEach(form =>
    form.addEventListener('submit', () => {
        const boton = form.querySelector('button:not([type]), button[type="submit"]');
        if (!boton || boton.disabled) return;
        setTimeout(() => {
            boton.disabled = true;
            boton.dataset.html = boton.innerHTML;
            boton.innerHTML = '<span class="spinner-border spinner-border-sm" aria-hidden="true"></span> Procesando…';
        }, 0);
    }));
// Al volver con "atrás" el navegador restaura la página tal cual: se reactivan los botones.
window.addEventListener('pageshow', ev => {
    if (!ev.persisted) return;
    document.querySelectorAll('button[data-html]').forEach(b => { b.innerHTML = b.dataset.html; b.disabled = false; });
});

// Los avisos de éxito se cierran solos.
document.querySelectorAll('[data-autocierre]').forEach(el =>
    setTimeout(() => bootstrap.Alert.getOrCreateInstance(el).close(), 6000));

// Mostrar u ocultar la contraseña.
document.querySelectorAll('[data-ver-clave]').forEach(btn =>
    btn.addEventListener('click', () => {
        const campo = document.getElementById(btn.dataset.verClave);
        const visible = campo.type === 'text';
        campo.type = visible ? 'password' : 'text';
        btn.setAttribute('aria-label', visible ? 'Mostrar contraseña' : 'Ocultar contraseña');
        btn.firstElementChild.className = visible ? 'bi bi-eye' : 'bi bi-eye-slash';
    }));

// Registro de entregas: resalta lo marcado, cuenta la selección y exige al menos un EPP.
document.querySelectorAll('form[data-seleccion]').forEach(form => {
    const marcas = [...form.querySelectorAll('input[type="checkbox"][name$=".seleccionado"]')];
    const todos = form.querySelector('#sel-todos');
    const contador = form.querySelector('#contador-seleccion');
    const registrar = form.querySelector('#btn-registrar');
    const actualizar = () => {
        const n = marcas.filter(m => m.checked).length;
        marcas.forEach(m => m.closest('tr').classList.toggle('fila-marcada', m.checked));
        contador.textContent = n;
        registrar.disabled = n === 0;
        todos.checked = n === marcas.length;
        todos.indeterminate = n > 0 && n < marcas.length;
    };
    marcas.forEach(m => m.addEventListener('change', actualizar));
    todos.addEventListener('change', () => { marcas.forEach(m => { m.checked = todos.checked; }); actualizar(); });
    actualizar();
    form.querySelector('.is-invalid')?.focus();
});

// Guía de inicio del dashboard: se puede ocultar y se recuerda la elección.
document.querySelectorAll('#guia').forEach(guia => {
    const cuerpo = guia.querySelector('[data-guia-cuerpo]');
    const boton = guia.querySelector('[data-guia-toggle]');
    const mostrar = visible => {
        cuerpo.hidden = !visible;
        boton.textContent = visible ? 'Ocultar' : 'Mostrar guía';
        boton.setAttribute('aria-expanded', visible);
    };
    try { mostrar(localStorage.getItem('guia-oculta') !== '1'); } catch (e) { mostrar(true); }
    boton.addEventListener('click', () => {
        mostrar(cuerpo.hidden);
        try { localStorage.setItem('guia-oculta', cuerpo.hidden ? '1' : '0'); } catch (e) { /* sin almacenamiento */ }
    });
});

// Buscador en vivo: el input indica con data-filtrar el selector de los elementos que se muestran u ocultan.
function normalizar(t) { return t.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, ''); }
document.querySelectorAll('input[data-filtrar]').forEach(campo =>
    campo.addEventListener('input', () => {
        const q = normalizar(campo.value.trim());
        document.querySelectorAll(campo.dataset.filtrar).forEach(el => {
            el.hidden = q !== '' && !normalizar(el.textContent).includes(q);
        });
    }));

// Accesibilidad: asocia cada etiqueta .form-label con su control cuando la plantilla no lo hizo.
let nCtl = 0;
document.querySelectorAll('label.form-label:not([for])').forEach(l => {
    const c = l.parentElement.querySelector('input:not([type=hidden]), select, textarea');
    if (!c) return;
    if (!c.id) c.id = 'ctl-' + (++nCtl);
    l.htmlFor = c.id;
});
