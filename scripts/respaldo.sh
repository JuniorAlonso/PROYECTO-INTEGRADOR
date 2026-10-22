#!/bin/sh
# Respaldo de la base de EPPTrack (RNF-08). Uso: scripts/respaldo.sh [directorio_destino]
# Con docker compose: ejecutar desde la carpeta del proyecto; el volcado queda en ./backups.
# Conserva los últimos 30 respaldos.
set -eu

DESTINO="${1:-./backups}"
mkdir -p "$DESTINO"
ARCHIVO="$DESTINO/epptrack-$(date +%Y%m%d-%H%M%S).sql.gz"

# La clave va por variable de entorno del contenedor, nunca en la línea de comandos del host.
docker compose exec -T db sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines epptrack' \
    | gzip > "$ARCHIVO"

ls -1t "$DESTINO"/epptrack-*.sql.gz | tail -n +31 | xargs -r rm --
echo "Respaldo creado: $ARCHIVO"
