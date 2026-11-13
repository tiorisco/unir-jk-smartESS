package com.mrsmanuel.monitorsolar

import android.content.Context
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Un punto de la serie del dia (SmartESS guarda uno cada ~5 min). Hora local. */
data class PuntoDia(
    val hora: LocalDateTime,
    val pvW: Float,
    val casaW: Float,          // potencia activa de salida del inversor
    val bateriaW: Float,       // + carga, - descarga
    val redW: Float,
    val energiaPvDia: Float?,  // kWh producidos hoy, contador propio del inversor
    val energiaPvTotal: Float?, // kWh producidos en toda su vida
)

/** Energias del dia, calculadas a partir de la serie. */
data class ResumenDia(
    val fecha: String,            // yyyy-MM-dd
    val pvKwh: Float,
    val casaKwh: Float,
    val bateriaCargaKwh: Float,
    val bateriaDescargaKwh: Float,
    val picoPvW: Float,
    val picoPvHora: String?,      // HH:mm
    val socMin: Float? = null,    // % medido por el BMS (solo lo conoce el servidor)
    val socMax: Float? = null,
)

private val FORMATO_HORA = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Integra las potencias (regla del trapecio). Los huecos de mas de 30 min no se integran,
 * asi que un dia con datos incompletos infravalora la energia. La produccion usa, si existe,
 * el contador diario del propio inversor, que es mas fiable.
 */
fun resumir(fecha: String, p: List<PuntoDia>): ResumenDia {
    var pv = 0.0
    var casa = 0.0
    var carga = 0.0
    var descarga = 0.0
    for (i in 1 until p.size) {
        val horas = Duration.between(p[i - 1].hora, p[i].hora).seconds / 3600.0
        if (horas <= 0.0 || horas > 0.5) continue
        pv += (p[i - 1].pvW + p[i].pvW) / 2.0 * horas / 1000.0
        casa += (p[i - 1].casaW + p[i].casaW) / 2.0 * horas / 1000.0
        val b = (p[i - 1].bateriaW + p[i].bateriaW) / 2.0 * horas / 1000.0
        if (b > 0) carga += b else descarga += -b
    }
    val contador = p.mapNotNull { it.energiaPvDia }.maxOrNull()
    val pico = p.maxByOrNull { it.pvW }
    return ResumenDia(
        fecha = fecha,
        pvKwh = contador ?: pv.toFloat(),
        casaKwh = casa.toFloat(),
        bateriaCargaKwh = carga.toFloat(),
        bateriaDescargaKwh = descarga.toFloat(),
        picoPvW = pico?.pvW ?: 0f,
        picoPvHora = pico?.takeIf { it.pvW > 0f }?.hora?.format(FORMATO_HORA),
    )
}

/**
 * Historico en el propio movil: un resumen por dia, que se actualiza cada vez que la app
 * descarga la serie de hoy. SmartESS no ofrece dias anteriores para este inversor, asi que
 * el historico solo crece los dias en que la app se abre.
 */
class HistoricoLocal(contexto: Context) {
    private val prefs = contexto.getSharedPreferences("terrenico_historico", Context.MODE_PRIVATE)

    fun guardar(r: ResumenDia) {
        val todo = JSONObject(prefs.getString(CLAVE, "{}") ?: "{}")
        todo.put(
            r.fecha,
            JSONObject()
                .put("pv", r.pvKwh.toDouble())
                .put("casa", r.casaKwh.toDouble())
                .put("carga", r.bateriaCargaKwh.toDouble())
                .put("descarga", r.bateriaDescargaKwh.toDouble())
                .put("pico", r.picoPvW.toDouble())
                .put("hora", r.picoPvHora ?: ""),
        )
        prefs.edit().putString(CLAVE, todo.toString()).apply()
    }

    fun todos(): List<ResumenDia> {
        val todo = JSONObject(prefs.getString(CLAVE, "{}") ?: "{}")
        return todo.keys().asSequence().sorted().map { f ->
            val o = todo.getJSONObject(f)
            ResumenDia(
                fecha = f,
                pvKwh = o.optDouble("pv").toFloat(),
                casaKwh = o.optDouble("casa").toFloat(),
                bateriaCargaKwh = o.optDouble("carga").toFloat(),
                bateriaDescargaKwh = o.optDouble("descarga").toFloat(),
                picoPvW = o.optDouble("pico").toFloat(),
                picoPvHora = o.optString("hora").ifBlank { null },
            )
        }.toList()
    }

    private companion object {
        const val CLAVE = "dias"
    }
}
