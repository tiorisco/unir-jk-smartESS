package com.mrsmanuel.monitorsolar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Muestra del BMS guardada por el colector (SOC real de la bateria). */
data class MuestraBms(val hora: LocalDateTime, val soc: Float?, val voltaje: Float?, val corriente: Float?)

data class HistoricoServidor(
    val dias: List<ResumenDia>,
    val pvTotalKwh: Float?,
    val ultimaRecogidaInversor: LocalDateTime?,
    val ultimaRecogidaBms: LocalDateTime?,
    val errorInversor: String?,
    val errorBms: String?,
)

data class DiaServidor(val inversor: List<PuntoDia>, val bms: List<MuestraBms>)

/**
 * Cliente SOLO LECTURA del colector de Terrenico (tu servidor).
 * Usa el token de lectura; con el no se puede disparar la recogida ni escribir nada.
 */
class ServidorCliente(private val urlBase: String, private val token: String) {

    private val formato = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun pedir(ruta: String): JSONObject {
        val c = URL(urlBase.trimEnd('/') + ruta).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 25_000
        c.setRequestProperty("X-Token", token)
        try {
            val codigo = c.responseCode
            if (codigo == 401) throw IllegalStateException("Servidor: token no válido")
            if (codigo != 200) throw IllegalStateException("Servidor: error HTTP $codigo")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun JSONObject.floatONull(clave: String): Float? =
        if (isNull(clave)) null else optDouble(clave).toFloat()

    private fun hora(texto: String?): LocalDateTime? =
        try { if (texto.isNullOrBlank()) null else LocalDateTime.parse(texto, formato) } catch (e: Exception) { null }

    suspend fun historico(): HistoricoServidor = withContext(Dispatchers.IO) {
        val r = pedir("/api/resumenes.php")
        val arr = r.getJSONArray("dias")
        val dias = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ResumenDia(
                fecha = o.getString("fecha"),
                pvKwh = o.optDouble("pv_kwh").toFloat(),
                casaKwh = o.optDouble("casa_kwh").toFloat(),
                bateriaCargaKwh = o.optDouble("bateria_carga_kwh").toFloat(),
                bateriaDescargaKwh = o.optDouble("bateria_descarga_kwh").toFloat(),
                picoPvW = o.optDouble("pico_pv_w").toFloat(),
                picoPvHora = if (o.isNull("pico_pv_hora")) null else o.optString("pico_pv_hora"),
                socMin = o.floatONull("soc_min"),
                socMax = o.floatONull("soc_max"),
            )
        }
        val estado = r.optJSONObject("estado")
        val inv = estado?.optJSONObject("inversor")
        val bms = estado?.optJSONObject("bms")
        HistoricoServidor(
            dias = dias,
            pvTotalKwh = r.floatONull("pv_total_kwh"),
            ultimaRecogidaInversor = hora(inv?.optString("ultima_ok")),
            ultimaRecogidaBms = hora(bms?.optString("ultima_ok")),
            errorInversor = inv?.takeUnless { it.isNull("ultimo_error") }?.optString("ultimo_error"),
            errorBms = bms?.takeUnless { it.isNull("ultimo_error") }?.optString("ultimo_error"),
        )
    }

    suspend fun dia(fecha: LocalDate): DiaServidor = withContext(Dispatchers.IO) {
        val r = pedir("/api/dia.php?fecha=$fecha")
        val inv = r.getJSONArray("inversor")
        val puntos = (0 until inv.length()).mapNotNull { i ->
            val o = inv.getJSONObject(i)
            val h = hora(o.optString("hora")) ?: return@mapNotNull null
            PuntoDia(
                hora = h,
                pvW = o.optDouble("pv_w").toFloat(),
                casaW = o.optDouble("casa_w").toFloat(),
                bateriaW = o.optDouble("bateria_w").toFloat(),
                redW = o.optDouble("red_w").toFloat(),
                energiaPvDia = o.floatONull("energia_pv_dia"),
                energiaPvTotal = o.floatONull("energia_pv_total"),
            )
        }
        val b = r.getJSONArray("bms")
        val muestras = (0 until b.length()).mapNotNull { i ->
            val o = b.getJSONObject(i)
            val h = hora(o.optString("hora")) ?: return@mapNotNull null
            MuestraBms(h, o.floatONull("soc"), o.floatONull("voltaje"), o.floatONull("corriente"))
        }
        DiaServidor(puntos, muestras)
    }
}
