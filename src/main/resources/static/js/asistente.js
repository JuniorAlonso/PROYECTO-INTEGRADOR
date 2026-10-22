// Panel flotante del asistente IA (RF-16 a RF-20). Las respuestas se insertan con textContent: nunca como HTML.
(function () {
    const panel = document.getElementById('asistente-panel');
    if (!panel) return;
    const abrir = document.getElementById('asistente-abrir');
    const chat = document.getElementById('asistente-chat');
    const form = document.getElementById('asistente-form');
    const texto = document.getElementById('asistente-texto');
    const csrf = document.querySelector('meta[name="_csrf"]').content;
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
    const SUGERENCIAS = [
        '¿Qué EPP están vencidos?',
        '¿Qué trabajadores tienen la dotación incompleta?',
        'Dame un resumen ejecutivo del cumplimiento por sede',
    ];
    let historial = [];

    function burbuja(clase, contenido) {
        const div = document.createElement('div');
        div.className = 'asistente-msg ' + clase;
        div.textContent = contenido;
        chat.appendChild(div);
        chat.scrollTop = chat.scrollHeight;
        return div;
    }

    function boton(etiqueta, icono, accion) {
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'btn btn-sm btn-outline-secondary';
        b.innerHTML = '<i class="bi bi-' + icono + '"></i> ' + etiqueta;
        b.addEventListener('click', accion);
        return b;
    }

    function acciones(burb, respuesta, pregunta) {
        const barra = document.createElement('div');
        barra.className = 'asistente-acciones';
        barra.append(
            boton('Copiar', 'clipboard', () => navigator.clipboard.writeText(respuesta.respuesta)),
            boton('Descargar', 'download', () => descargar(respuesta)),
            boton('Volver a consultar', 'arrow-repeat', () => enviar(pregunta)));
        burb.appendChild(barra);
    }

    function descargar(r) {
        const contenido = r.respuesta + '\n\nFuente: ' + r.fuente + '\nFecha de corte: ' + r.fechaCorte + '\n';
        const enlace = document.createElement('a');
        enlace.href = URL.createObjectURL(new Blob([contenido], { type: 'text/plain;charset=utf-8' }));
        enlace.download = 'consulta-epptrack.txt';
        enlace.click();
        URL.revokeObjectURL(enlace.href);
    }

    function inicio() {
        chat.replaceChildren();
        historial = [];
        burbuja('bot', 'Hola, consulto los datos de EPPTrack según tu perfil. Puedes preguntar, por ejemplo:');
        const lista = document.createElement('div');
        lista.className = 'asistente-sugerencias';
        SUGERENCIAS.forEach(s => {
            const b = document.createElement('button');
            b.type = 'button';
            b.className = 'btn btn-sm btn-outline-primary';
            b.textContent = s;
            b.addEventListener('click', () => enviar(s));
            lista.appendChild(b);
        });
        chat.appendChild(lista);
    }

    async function enviar(pregunta) {
        pregunta = pregunta.trim();
        if (!pregunta) return;
        burbuja('yo', pregunta);
        const espera = burbuja('bot', 'Consultando…');
        try {
            const resp = await fetch(panel.dataset.url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json', [csrfHeader]: csrf },
                body: JSON.stringify({ mensaje: pregunta, historial }),
            });
            const datos = await resp.json().catch(() => ({}));
            if (!resp.ok) throw new Error(datos.error || 'No se pudo completar la consulta.');
            espera.textContent = datos.respuesta;
            const pie = document.createElement('div');
            pie.className = 'asistente-fuente';
            pie.textContent = 'Fuente: ' + datos.fuente + ' · Corte: ' + datos.fechaCorte;
            espera.appendChild(pie);
            acciones(espera, datos, pregunta);
            historial.push({ rol: 'user', texto: pregunta }, { rol: 'assistant', texto: datos.respuesta });
        } catch (e) {
            espera.textContent = e.message;
            espera.classList.add('error');
        }
        chat.scrollTop = chat.scrollHeight;
    }

    abrir.addEventListener('click', () => {
        panel.hidden = !panel.hidden;
        if (!panel.hidden) {
            if (!chat.children.length) inicio();
            texto.focus();
        }
    });
    const cerrar = () => { panel.hidden = true; abrir.focus(); };
    document.getElementById('asistente-cerrar').addEventListener('click', cerrar);
    document.addEventListener('keydown', ev => { if (ev.key === 'Escape' && !panel.hidden) cerrar(); });
    document.getElementById('asistente-nuevo').addEventListener('click', inicio);
    form.addEventListener('submit', ev => {
        ev.preventDefault();
        const pregunta = texto.value;
        texto.value = '';
        enviar(pregunta);
    });
})();
