package com.mrsmanuel.monitorsolar

/** Ultima lectura de la bateria, tal como la publica el ESP32 (solar/bateria/...). */
data class DatosBateria(
    val soc: Float? = null,              // %
    val voltaje: Float? = null,          // V
    val corriente: Float? = null,        // A (+ carga, - descarga; confirmado)
    val potencia: Float? = null,         // W
    val temp1: Float? = null,            // C
    val temp2: Float? = null,            // C
    val tempMosfet: Float? = null,       // C
    val celdaMin: Float? = null,         // V
    val celdaMax: Float? = null,         // V
    val celdaDelta: Float? = null,       // V
    val capacidadRestante: Float? = null, // Ah
    val cargaActivada: Boolean? = null,
    val descargaActivada: Boolean? = null,
    val bmsOnline: Boolean? = null,      // el ESP32 esta conectado al BMS
    val puenteOnline: Boolean? = null,   // el ESP32 esta conectado al broker (status)
    val ultimaActualizacion: Long = 0L,  // System.currentTimeMillis() del ultimo mensaje
)

enum class EstadoConexion { CONECTANDO, CONECTADO, DESCONECTADO, SIN_CONFIGURAR }
