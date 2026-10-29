-- =====================================================================================================
-- EPPTrack · Migración APF3 (inventario por unidad: inspección, autorización, mantenimiento)
--
-- *** NO PROBADO CONTRA UN MYSQL REAL ***  Se escribió a mano a partir de las entidades JPA y se debe
-- revisar y ensayar en una COPIA de la base antes de usarlo. Las pruebas automáticas usan H2 y crean el
-- esquema con Hibernate (create-drop); este script NO se ejecutó en ningún motor.
--
-- Para qué sirve: una base MySQL EXISTENTE (creada con una versión anterior, con ddl-auto=update) puede no
-- ampliar por sí sola los ENUM de MySQL. Hibernate 6 mapea los enumerados STRING a columnas ENUM(...); al
-- agregar valores nuevos (POR_INSPECCION, ...), MySQL rechazaría los INSERT hasta ampliar la lista.
-- En una base nueva no hace falta: Hibernate crea todo.
--
-- Antes de ejecutar: respaldo completo (mysqldump) y ejecutar primero en una copia.
-- Los ALTER ... ADD COLUMN no son idempotentes: si una columna ya existe (porque ddl-auto=update ya la creó),
-- MySQL responde error 1060 (Duplicate column) y esa línea se debe omitir.
-- =====================================================================================================

USE epptrack;

-- 1) Catálogo de EPP: clase y política de devolución -------------------------------------------------
ALTER TABLE epp ADD COLUMN clase ENUM('DESECHABLE','REUTILIZABLE_INDIVIDUAL','REUTILIZABLE_COMPARTIDO') NULL;
ALTER TABLE epp ADD COLUMN clase_validada BIT(1) NULL;
ALTER TABLE epp ADD COLUMN devolucion_permitida BIT(1) NULL;
ALTER TABLE epp ADD COLUMN inspeccion_al_devolver BIT(1) NULL;

-- 2) Detalle de entrega: justificación de exceso y cantidad exigida por la matriz --------------------
ALTER TABLE detalle_entrega ADD COLUMN justificacion_exceso VARCHAR(500) NULL;
ALTER TABLE detalle_entrega ADD COLUMN cantidad_exigida INT NULL;

-- 3) Bitácora de unidad: motivo de devolución y nuevos tipos de movimiento ---------------------------
ALTER TABLE movimiento_unidad ADD COLUMN motivo
    ENUM('CESE_TRABAJADOR','REEMPLAZO','CAMBIO_ACTIVIDAD','DETERIORO','OTRO') NULL;

ALTER TABLE movimiento_unidad MODIFY COLUMN tipo ENUM(
    'INGRESO','ASIGNACION','DEVOLUCION','INSPECCION','AUTORIZADO_RETORNO','RETORNO_RECHAZADO',
    'CUARENTENA_RESUELTA','ENVIO_MANTENIMIENTO','REPARADO','BAJA','LIBERADO','CONSUMO') NOT NULL;

-- 4) Estado de la unidad: nuevos estados del flujo ----------------------------------------------------
ALTER TABLE unidad_epp MODIFY COLUMN estado ENUM(
    'DISPONIBLE','ASIGNADO','POR_INSPECCION','POR_AUTORIZAR','EN_CUARENTENA','POR_MANTENIMIENTO',
    'EN_MANTENIMIENTO','DE_BAJA','CONSUMIDO') NOT NULL;

-- 5) Tablas nuevas -------------------------------------------------------------------------------------
CREATE TABLE inspeccion_unidad (
    id_inspeccion INT NOT NULL AUTO_INCREMENT,
    id_unidad INT NOT NULL,
    fecha DATETIME(6) NOT NULL,
    inspector VARCHAR(50) NOT NULL,
    resultado ENUM('APTO_PARA_USO','REQUIERE_MANTENIMIENTO','NO_APTO_CUARENTENA','NO_APTO_BAJA') NOT NULL,
    observaciones VARCHAR(450) NULL,
    autorizacion_pendiente BIT(1) NOT NULL,
    PRIMARY KEY (id_inspeccion),
    CONSTRAINT fk_inspeccion_unidad FOREIGN KEY (id_unidad) REFERENCES unidad_epp (id_unidad)
) ENGINE=InnoDB;

CREATE TABLE registro_mantenimiento (
    id_registro INT NOT NULL AUTO_INCREMENT,
    id_unidad INT NOT NULL,
    fecha_inicio DATETIME(6) NOT NULL,
    diagnostico VARCHAR(450) NOT NULL,
    accion_prevista VARCHAR(450) NULL,
    responsable VARCHAR(50) NOT NULL,
    estado ENUM('ABIERTO','CERRADO') NOT NULL,
    fecha_fin DATETIME(6) NULL,
    accion_realizada VARCHAR(450) NULL,
    costo DECIMAL(10,2) NULL,
    resultado ENUM('REPARADO','NO_REPARABLE') NULL,
    observacion VARCHAR(450) NULL,
    cerrado_por VARCHAR(50) NULL,
    PRIMARY KEY (id_registro),
    CONSTRAINT fk_mantenimiento_unidad FOREIGN KEY (id_unidad) REFERENCES unidad_epp (id_unidad)
) ENGINE=InnoDB;

-- 6) Control por lotes de EPP desechables (NO PROBADO contra MySQL) -----------------------------------
CREATE TABLE lote_consumible (
    id_lote INT NOT NULL AUTO_INCREMENT,
    id_epp INT NOT NULL,
    codigo_lote VARCHAR(40) NOT NULL,
    cantidad_inicial INT NOT NULL,
    saldo INT NOT NULL,
    fecha_ingreso DATE NOT NULL,
    fecha_vencimiento DATE NULL,
    observacion VARCHAR(255) NULL,
    usuario VARCHAR(100) NULL,
    PRIMARY KEY (id_lote),
    CONSTRAINT uk_lote_epp_codigo UNIQUE (id_epp, codigo_lote),
    CONSTRAINT fk_lote_epp FOREIGN KEY (id_epp) REFERENCES epp (id_epp)
) ENGINE=InnoDB;

CREATE TABLE consumo_lote (
    id_consumo INT NOT NULL AUTO_INCREMENT,
    id_lote INT NOT NULL,
    id_detalle INT NOT NULL,
    cantidad INT NOT NULL,
    fecha DATETIME(6) NOT NULL,
    usuario VARCHAR(100) NULL,
    revertido BIT(1) NOT NULL,
    PRIMARY KEY (id_consumo),
    CONSTRAINT fk_consumo_lote FOREIGN KEY (id_lote) REFERENCES lote_consumible (id_lote),
    CONSTRAINT fk_consumo_detalle FOREIGN KEY (id_detalle) REFERENCES detalle_entrega (id_detalle)
) ENGINE=InnoDB;

-- Pendiente de verificar en una copia: nombres reales de las columnas (naming strategy de Spring: snake_case),
-- el tipo de id_unidad (INT) y el motor/charset de las tablas existentes; ajustar si difieren.
