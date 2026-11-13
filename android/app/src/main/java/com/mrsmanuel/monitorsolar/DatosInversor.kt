package com.mrsmanuel.monitorsolar

/** Ultima lectura del inversor Easun, via la nube de SmartESS (querySPDeviceLastData). */
data class DatosInversor(
    val pvPotencia: Float? = null,       // W (placas)
    val pvVoltaje: Float? = null,        // V
    val pvCorriente: Float? = null,      // A
    val cargaPotencia: Float? = null,    // W activos que consume la casa
    val cargaAparente: Float? = null,    // VA
    val cargaPorcentaje: Float? = null,  // % de carga del inversor
    val salidaVoltaje: Float? = null,    // V
    val salidaFrecuencia: Float? = null, // Hz
    val redVoltaje: Float? = null,       // V (0 = sin red)
    val redFrecuencia: Float? = null,    // Hz
    val redPotencia: Float? = null,      // W (solo lo conocemos en modo servidor)
    val modo: String? = null,            // "Off-grid mode", etc.
    val bateriaVoltaje: Float? = null,   // V segun el inversor
    val bateriaCorriente: Float? = null, // A segun el inversor
    // OJO: el SOC del inversor NO se usa: es una estimacion por voltaje y no es fiable.
    val tempDcdc: Float? = null,         // C
    val tempInversor: Float? = null,     // C
    val tempPv: Float? = null,           // C
    val marcaTiempo: Long = 0L,          // ms, hora del dato en la nube
    val recibido: Long = 0L,             // ms, cuando lo recibio la app
    // ms, ultima vez que los valores CAMBIARON. La nube de SmartESS repite el mismo dato
    // durante minutos, y su marca de hora (gts) no es fiable (reloj del datalogger).
    val ultimoCambio: Long = 0L,
) {
    fun sinMarcas() = copy(marcaTiempo = 0L, recibido = 0L, ultimoCambio = 0L)

    /** Hay red electrica conectada (tension de red razonable). */
    val hayRed: Boolean get() = (redVoltaje ?: 0f) > 100f || (redPotencia ?: 0f) > 10f
}

sealed interface EstadoInversor {
    data object NoConfigurado : EstadoInversor
    data object Cargando : EstadoInversor
    data object Correcto : EstadoInversor
    data class Error(val mensaje: String) : EstadoInversor
}

/** Estado de la conexion con el colector del servidor (historico). */
data class EstadoServidor(
    val configurado: Boolean,
    val correcto: Boolean = false,
    val ultimaRecogida: java.time.LocalDateTime? = null,
    val aviso: String? = null,
)

/** Una muestra para la grafica de historial reciente (solo en memoria). */
data class Muestra(
    val hora: Long,
    val pv: Float,
    val casa: Float,
    val bateria: Float,
)
