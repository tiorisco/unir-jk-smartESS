-- Esquema del colector de Terrenico (base de datos solar_terrenico).
-- Solo esquema: no lleva datos. Todas las horas son hora local (Europe/Madrid), sin zona.

CREATE TABLE IF NOT EXISTS inversor_puntos (
    hora             DATETIME NOT NULL,
    pv_w             FLOAT NOT NULL DEFAULT 0,   -- potencia de placas (W)
    casa_w           FLOAT NOT NULL DEFAULT 0,   -- potencia activa de salida (W)
    bateria_w        FLOAT NOT NULL DEFAULT 0,   -- + carga, - descarga (W), segun el inversor
    red_w            FLOAT NOT NULL DEFAULT 0,   -- potencia de red (W)
    energia_pv_dia   FLOAT NULL,                 -- kWh del dia, contador del inversor
    energia_pv_total FLOAT NULL,                 -- kWh de toda su vida
    PRIMARY KEY (hora)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS bms_muestras (
    hora        DATETIME NOT NULL,
    soc         FLOAT NULL,                      -- % medido por el BMS (el fiable)
    voltaje     FLOAT NULL,                      -- V
    corriente   FLOAT NULL,                      -- A (+ carga, - descarga)
    potencia    FLOAT NULL,                      -- W
    celda_min   FLOAT NULL,                      -- V
    celda_max   FLOAT NULL,                      -- V
    temp_mosfet FLOAT NULL,                      -- C
    PRIMARY KEY (hora)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS resumen_dia (
    fecha                DATE NOT NULL,
    pv_kwh               FLOAT NOT NULL DEFAULT 0,
    casa_kwh             FLOAT NOT NULL DEFAULT 0,
    bateria_carga_kwh    FLOAT NOT NULL DEFAULT 0,
    bateria_descarga_kwh FLOAT NOT NULL DEFAULT 0,
    pico_pv_w            FLOAT NOT NULL DEFAULT 0,
    pico_pv_hora         CHAR(5) NULL,           -- HH:MM
    soc_min              FLOAT NULL,
    soc_max              FLOAT NULL,
    actualizado          DATETIME NOT NULL,
    PRIMARY KEY (fecha)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Avisos al movil: una fila por tipo (baja, critica, llena, sin_datos). activa = ya avisado
CREATE TABLE IF NOT EXISTS alertas_estado (
    tipo     VARCHAR(20) NOT NULL,
    activa   TINYINT(1) NOT NULL DEFAULT 0,
    cambiado DATETIME NOT NULL,
    PRIMARY KEY (tipo)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Estado de la ultima recogida de cada fuente (para saber si el colector esta vivo)
CREATE TABLE IF NOT EXISTS estado_recogida (
    fuente      VARCHAR(20) NOT NULL,            -- 'inversor' | 'bms'
    ultima_ok   DATETIME NULL,
    ultimo_error VARCHAR(255) NULL,
    PRIMARY KEY (fuente)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
