# EPPTrack — Ejecución y demostración del APF3

Todo con **datos ficticios** (perfil `h2`). Ninguna cifra de esta demostración es un dato real de CASURSA.

## 1. Ejecutar desde un clon limpio

Requisitos: JDK 21 o superior e internet la primera vez (Maven descarga las dependencias).

```bash
git clone https://github.com/JuniorAlonso/PROYECTO-INTEGRADOR.git
cd PROYECTO-INTEGRADOR
git checkout apf3-motor-decisiones        # mientras no esté fusionada en main

# Pruebas automáticas (H2 en memoria, no requieren MySQL)
./mvnw test                               # Windows: mvnw.cmd test
# Resultado esperado: Tests run: 83, Failures: 0, Errors: 0

# Aplicación con base en memoria y datos de demostración
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2
# Abrir http://localhost:8080  ·  usuario supervisor / super123  (admin / admin123 para publicar la matriz)
```

Con MySQL: `./mvnw spring-boot:run` (base `epptrack` en `localhost:3306`; ver README). Las tablas nuevas
(`version_matriz`, `registro_auditoria`) y las columnas nuevas se crean solas (`ddl-auto=update`).
**Pendiente:** esta rama no se ha probado contra un MySQL real; solo contra H2 en modo MySQL.

## 2. Guion de 7 minutos

| Min | Paso | Qué hacer | Qué debe verse (decisión del sistema) |
|-----|------|-----------|----------------------------------------|
| 0:00 | Problema | Explicar papel en 19 sedes, matriz 115 × 57 cruzada a mano, reingresos con kit completo. Decir que el pretest tiene procedencia por confirmar. | — |
| 0:45 | Reincorporado | Historial → DNI **42756019** | Tarjeta «Evaluación automática de dotación»: lo vigente de la campaña anterior **no se vuelve a entregar** y aparece la advertencia de confirmar que lo conserva. |
| 1:45 | Dotación automática | Historial → DNI **70345128** | Estado por EPP con la regla aplicada y **dotación neta**: cartuchos ×2 y respirador ×1. |
| 2:30 | Entrega parcial | Registro de entregas → 70345128 → marcar solo «Cartuchos» con cantidad **1** → Registrar | La evaluación pasa a **«Cantidad incompleta: 1 de 2»** y el pendiente queda en 1. |
| 3:15 | Entrega no permitida | Registro de entregas → marcar «Casco de seguridad» (No exigido) sin justificación → Registrar | **Bloqueo**: «…no los exige la matriz para su actividad (RN-06)». Fila resaltada. |
| 4:00 | Versión de matriz | Historial de 70345128 → tabla de entregas | Cada entrega muestra **«Matriz demo-1»**. Como admin: Matriz → Publicar versión → ver el impacto (reglas y trabajadores afectados). |
| 5:00 | Auditoría | Menú Auditoría | **«Cadena íntegra»** y los eventos de las operaciones recién hechas. Explicar que alterar una fila en la base rompe la cadena (prueba PR-11). |
| 5:45 | Reporte | Reportes → R-01 → **Excel (.xlsx)** | Archivo con encabezado, filas y la huella de auditoría al pie. |
| 6:15 | Pruebas | Mostrar la salida de `mvnw test` | 83 pruebas, 0 fallos; PR-01 a PR-18 cubren las reglas. |
| 6:45 | Cierre | Implementado / pendiente | Pendiente de SST: criterios de habilitación, entrega por etapas, carga de formatos históricos. |

## 3. Ensayo automático (opcional)

`scripts/ensayo_demo.js` recorre los pasos anteriores con Playwright y marca OK/FALLA en cada uno. Requiere Node.js
y `npm install playwright` en una carpeta aparte; la aplicación debe estar corriendo con el perfil `h2`.
Resultado del ensayo del 29/10/2026: 6 de 6 pasos OK.
