// Paleta de comandos (Ctrl+K) y modo oscuro. Sin dependencias; los textos se insertan con textContent.
(function () {
    const raiz = document.documentElement;

    // ---- Tema: preferencia guardada, o la del sistema
    const boton = document.getElementById('tema-boton');
    const guardado = () => { try { return localStorage.getItem('tema'); } catch (e) { return null; } };
    const aplicar = tema => {
        raiz.setAttribute('data-bs-theme', tema);
        if (!boton) return;
        boton.firstElementChild.className = tema === 'dark' ? 'bi bi-sun' : 'bi bi-moon-stars';
        boton.setAttribute('aria-label', tema === 'dark' ? 'Cambiar a modo claro' : 'Cambiar a modo oscuro');
    };
    aplicar(guardado() || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'));
    if (boton) {
        boton.addEventListener('click', () => {
            const nuevo = raiz.getAttribute('data-bs-theme') === 'dark' ? 'light' : 'dark';
            aplicar(nuevo);
            try { localStorage.setItem('tema', nuevo); } catch (e) { /* sin almacenamiento */ }
        });
    }

    // ---- Paleta de comandos
    const dialogo = document.getElementById('paleta');
    if (!dialogo) return;
    const campo = document.getElementById('paleta-texto');
    const lista = document.getElementById('paleta-lista');
    const base = [...document.querySelectorAll('.sidebar .nav-link')].map(a => ({
        icono: a.querySelector('.bi')?.className || 'bi bi-arrow-right',
        texto: a.textContent.trim(), url: a.getAttribute('href'), pista: 'Ir a',
    }));
    let visibles = [];
    let activo = 0;

    const norm = t => t.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');

    function opciones(q) {
        const consulta = q.trim();
        const lista = base.filter(o => norm(o.texto).includes(norm(consulta)));
        if (consulta) {
            const esDni = /^\d{8}$/.test(consulta);
            lista.push({ icono: 'bi bi-clock-history', url: '/historial?q=' + encodeURIComponent(consulta),
                texto: esDni ? 'Ver historial del DNI ' + consulta : 'Buscar “' + consulta + '” en el historial', pista: 'Trabajador' });
            if (esDni) {
                lista.push({ icono: 'bi bi-box-seam', url: '/entregas/nueva?dni=' + consulta,
                    texto: 'Registrar entrega al DNI ' + consulta, pista: 'Entrega' });
            }
        }
        return lista;
    }

    function pintar() {
        lista.replaceChildren();
        visibles.forEach((o, i) => {
            const li = document.createElement('li');
            const a = document.createElement('a');
            a.href = o.url;
            a.setAttribute('role', 'option');
            a.setAttribute('aria-selected', i === activo);
            const ico = document.createElement('i'); ico.className = o.icono;
            const txt = document.createElement('span'); txt.textContent = o.texto;
            const pista = document.createElement('small'); pista.textContent = o.pista;
            a.append(ico, txt, pista);
            li.appendChild(a);
            lista.appendChild(li);
        });
        if (!visibles.length) {
            const li = document.createElement('li');
            li.className = 'vacio'; li.textContent = 'Sin resultados.';
            lista.appendChild(li);
        }
    }

    function actualizar() { visibles = opciones(campo.value); activo = 0; pintar(); }
    function abrir() { campo.value = ''; actualizar(); if (!dialogo.open) dialogo.showModal(); campo.focus(); }

    document.querySelectorAll('[data-paleta-abrir]').forEach(b => b.addEventListener('click', abrir));
    document.addEventListener('keydown', ev => {
        const escribiendo = /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement?.tagName || '');
        if ((ev.ctrlKey || ev.metaKey) && ev.key.toLowerCase() === 'k') { ev.preventDefault(); abrir(); }
        else if (ev.key === '/' && !escribiendo && !dialogo.open) { ev.preventDefault(); abrir(); }
    });
    campo.addEventListener('input', actualizar);
    campo.addEventListener('keydown', ev => {
        if (ev.key === 'ArrowDown' || ev.key === 'ArrowUp') {
            ev.preventDefault();
            activo = (activo + (ev.key === 'ArrowDown' ? 1 : -1) + visibles.length) % Math.max(visibles.length, 1);
            pintar();
            lista.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: 'nearest' });
        } else if (ev.key === 'Enter' && visibles[activo]) {
            ev.preventDefault();
            location.href = visibles[activo].url;
        }
    });
    dialogo.addEventListener('click', ev => { if (ev.target === dialogo) dialogo.close(); });
})();
