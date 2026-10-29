// Ensayo automático del guion de demostración APF3 (datos ficticios, perfil h2).
// Uso: con la app corriendo (perfil h2), en una carpeta con playwright instalado: node ensayo_demo.js
// Las capturas y el Excel se guardan en ./demo/. Si hay internet, la ruta de CDN no hace falta y se ignora.
const { chromium } = require('playwright'); const fs=require('fs'), path=require('path');
(async () => {
  const b = await chromium.launch(); const ctx = await b.newContext({viewport:{width:1366,height:900}, acceptDownloads:true}); const p = await ctx.newPage();
  await p.route('https://cdn.jsdelivr.net/npm/**', async r => {
    let u = new URL(r.request().url()).pathname.replace('/npm/',''); u = u.replace(/^(@[^/]+\/[^@/]+|[^@/]+)@[^/]+/, '$1');
    const f = path.join(process.cwd(),'node_modules',u);
    if (fs.existsSync(f)) await r.fulfill({body: fs.readFileSync(f), contentType: f.endsWith('.css')?'text/css':f.endsWith('.js')?'application/javascript':'font/woff2'}); else await r.fulfill({status:404,body:''});
  });
  const go = async u => { await p.goto('http://localhost:8080'+u); await p.waitForLoadState('networkidle'); };
  const ok = (paso, cond, det='') => console.log((cond?'OK   ':'FALLA')+' | '+paso+(det?' | '+det:''));
  const D='demo/'; fs.mkdirSync(D,{recursive:true});
  await go('/login'); await p.fill('input[name=username]','supervisor'); await p.fill('input[name=password]','super123'); await p.press('input[name=password]','Enter'); await p.waitForLoadState('networkidle');
  // 1. Reincorporado
  await go('/historial?dni=42756019'); let t = await p.locator('#evaluacion-dotacion').innerText();
  ok('1 Reincorporado: evaluación y confirmación de conservación', /Reincorporado/.test(await p.content()) && /campaña anterior/.test(t));
  await p.locator('#evaluacion-dotacion').screenshot({path:D+'1_reincorporado.png'});
  // 2. Dotación automática de un trabajador con faltantes
  await go('/historial?dni=70345128'); t = await p.locator('#evaluacion-dotacion').innerText();
  ok('2 Dotación neta calculada', /Dotación neta/.test(t), (t.match(/Dotación neta[^\n]*/)||[''])[0]);
  // 3a. Entrega parcial: cartuchos x1 de 2
  await go('/entregas/nueva?dni=70345128');
  for (const bx of await p.$$('input[type=checkbox][name$=".seleccionado"]')) if (await bx.isChecked()) await bx.uncheck();
  let fila = p.locator('form[data-seleccion] tr', {hasText:'Cartuchos para vapores'}).first();
  await fila.locator('input[type=checkbox]').check(); await fila.locator('input[name$=".cantidad"]').fill('1');
  await p.click('#btn-registrar'); await p.waitForLoadState('networkidle');
  t = await p.locator('#evaluacion-dotacion').innerText();
  ok('3a Entrega parcial registrada y marcada como incompleta', /Cantidad incompleta/.test(t) && /1 de 2/.test(t));
  await p.locator('#evaluacion-dotacion').screenshot({path:D+'3a_parcial.png'});
  // 3b. Entrega no permitida (no exigida, sin justificación)
  await go('/entregas/nueva?dni=70345128');
  for (const bx of await p.$$('input[type=checkbox][name$=".seleccionado"]')) if (await bx.isChecked()) await bx.uncheck();
  fila = p.locator('form[data-seleccion] tr', {hasText:'Casco de seguridad'}).first(); await fila.locator('input[type=checkbox]').check();
  await p.click('#btn-registrar'); await p.waitForLoadState('networkidle');
  const err = await p.locator('.alert-danger').first().innerText();
  ok('3b Entrega no exigida bloqueada', /RN-06/.test(err), err.trim().slice(0,90));
  await p.screenshot({path:D+'3b_bloqueo.png'});
  // 4. Versión de matriz usada en la entrega
  await go('/historial?dni=70345128'); const html = await p.content();
  ok('4 Versión de matriz visible en el historial', /Matriz demo-1/.test(html));
  await p.locator('table', {hasText:'Responsable'}).first().screenshot({path:D+'4_version_en_historial.png'});
  // 5. Auditoría
  await go('/auditoria'); t = await p.locator('#verificacion').innerText();
  ok('5 Bitácora íntegra', /Cadena íntegra/.test(t), t.slice(0,60));
  await p.screenshot({path:D+'5_auditoria.png'});
  // 6. Reporte exportado
  await go('/reportes/R01');
  const [dl] = await Promise.all([p.waitForEvent('download'), p.click('a[href*="R01.xlsx"]')]);
  await dl.saveAs(D+'R-01.xlsx');
  ok('6 Reporte R-01 exportado a .xlsx', fs.statSync(D+'R-01.xlsx').size > 3000, dl.suggestedFilename());
  await b.close();
})();
