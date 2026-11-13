package com.mrsmanuel.monitorsolar

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Cliente SOLO LECTURA de la nube de SmartESS (plataforma ShineMonitor/DESSMonitor).
 * API no oficial: puede cambiar sin aviso. Verificado con el hardware el 12-11-2026.
 *
 *   login:  sign = SHA1(salt + SHA1(clave) + params)
 *   resto:  sign = SHA1(salt + secret + token + params)
 *
 * Nunca se llama a ctrlDevice ni a nada que cambie ajustes del inversor.
 */
class SmartessCliente(private val usuario: String, private val clave: String) {

    private class SesionCaducada(mensaje: String = "Sesion de SmartESS caducada") : Exception(mensaje)
    private class Dispositivo(val pn: String, val sn: String, val devcode: String, val devaddr: String)

    /** El dia consultado no tiene registros en la nube (err 12). */
    private class SinRegistros : Exception("Sin registros")

    private var token: String? = null
    private var secret: String? = null
    private var dispositivo: Dispositivo? = null

    // Las consultas "ahora" y "dia" comparten sesion: una a una para no pisar el login
    private val candado = Mutex()

    suspend fun leer(): DatosInversor = withContext(Dispatchers.IO) {
        candado.withLock {
            try {
                leerInterno()
            } catch (e: SesionCaducada) {
                // Token caducado o invalido: se vuelve a entrar una vez
                token = null
                secret = null
                leerInterno()
            }
        }
    }

    /** Serie del dia completo (una lectura cada ~5 min). Lista vacia si no hay registros. */
    suspend fun leerDia(fecha: LocalDate): List<PuntoDia> = withContext(Dispatchers.IO) {
        candado.withLock {
            try {
                diaInterno(fecha)
            } catch (e: SesionCaducada) {
                token = null
                secret = null
                diaInterno(fecha)
            }
        }
    }

    private fun diaInterno(fecha: LocalDate): List<PuntoDia> {
        if (token == null) iniciarSesion()
        val disp = dispositivo ?: buscarDispositivo().also { dispositivo = it }
        val r = try {
            llamar("queryDeviceDataOneDay&pn=${disp.pn}&devcode=${disp.devcode}&devaddr=${disp.devaddr}&sn=${disp.sn}&date=$fecha")
        } catch (e: SinRegistros) {
            return emptyList()
        }
        val dat = r.optJSONObject("dat") ?: return emptyList()
        val titulos = dat.optJSONArray("title") ?: return emptyList()
        val filas = dat.optJSONArray("row") ?: return emptyList()

        // Las columnas se localizan por nombre, no por posicion
        val nombres = (0 until titulos.length()).map { titulos.getJSONObject(it).optString("title") }
        // Sin distinguir mayusculas: SmartESS cambio "PV power" por "PV Power" (13-11-2026).
        // Si un nombre sale repetido, vale el primero.
        fun col(nombre: String) = nombres.indexOfFirst { it.equals(nombre, ignoreCase = true) }
        val iHora = col("Marca De Tiempo")
        val iPv = col("PV power")
        val iCasa = col("Output active power")
        val iBat = col("Battery power")
        val iRed = col("Grid power")
        val iDia = col("Daily PV energy generation")
        val iTotal = col("Total PV energy generation")
        if (iHora < 0 || iPv < 0 || iCasa < 0 || iBat < 0) {
            throw IllegalStateException("SmartESS ha cambiado el formato de la serie del dia")
        }

        val formato = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val puntos = ArrayList<PuntoDia>(filas.length())
        for (i in 0 until filas.length()) {
            val campos = filas.getJSONObject(i).optJSONArray("field") ?: continue
            fun s(c: Int) = if (c in 0 until campos.length()) campos.optString(c) else ""
            fun f(c: Int) = s(c).toFloatOrNull()
            val hora = try {
                LocalDateTime.parse(s(iHora), formato)
            } catch (e: Exception) {
                continue
            }
            puntos.add(
                PuntoDia(
                    hora = hora,
                    pvW = f(iPv) ?: 0f,
                    casaW = f(iCasa) ?: 0f,
                    bateriaW = f(iBat) ?: 0f,
                    redW = f(iRed) ?: 0f,
                    energiaPvDia = f(iDia),
                    energiaPvTotal = f(iTotal),
                ),
            )
        }
        return puntos.sortedBy { it.hora }
    }

    private fun leerInterno(): DatosInversor {
        if (token == null) iniciarSesion()
        val disp = dispositivo ?: buscarDispositivo().also { dispositivo = it }

        val r = llamar("querySPDeviceLastData&pn=${disp.pn}&devcode=${disp.devcode}&devaddr=${disp.devaddr}&sn=${disp.sn}")
        val dat = r.optJSONObject("dat") ?: throw IllegalStateException("Respuesta sin datos")
        val grupos = dat.optJSONObject("pars") ?: throw IllegalStateException("Respuesta sin parametros")

        // Aplana todos los grupos (gd_, sy_, pv_, bt_, bc_) en id -> valor
        val v = HashMap<String, String>()
        for (g in grupos.keys()) {
            val arr = grupos.optJSONArray(g) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                v[o.optString("id")] = o.optString("val")
            }
        }
        fun f(id: String) = v[id]?.toFloatOrNull()

        return DatosInversor(
            pvPotencia = f("pv_output_power"),
            pvVoltaje = f("pv_voltage"),
            pvCorriente = f("pv_current"),
            cargaPotencia = f("bc_load_active_power"),
            cargaAparente = f("bc_output_apparent_power"),
            cargaPorcentaje = f("bc_load_percentage"),
            salidaVoltaje = f("bc_output_voltage"),
            salidaFrecuencia = f("bc_output_frequency"),
            redVoltaje = f("gd_mains_voltage"),
            redFrecuencia = f("gd_mains_frequency"),
            modo = v["sy_operating_mode"],
            bateriaVoltaje = f("bt_battery_voltage"),
            bateriaCorriente = f("bt_battery_current"),
            tempDcdc = f("sy_dcdc_temperature"),
            tempInversor = f("sy_inverter_temperature"),
            tempPv = f("sy_pv_temperature"),
            marcaTiempo = dat.optString("gts").toLongOrNull() ?: 0L,
            recibido = System.currentTimeMillis(),
        )
    }

    private fun iniciarSesion() {
        val salt = System.currentTimeMillis().toString()
        val usr = Uri.encode(usuario.replace(" ", "+"), "+")
        val accion = "&action=auth&usr=$usr&company-key=$COMPANY_KEY$SUFIJO"
        val sign = sha1(salt + sha1(clave) + accion)
        val r = pedir("$BASE?sign=$sign&salt=$salt$accion")
        if (r.optInt("err", -1) != 0) {
            throw IllegalStateException("Login SmartESS: ${r.optString("desc", "error")}")
        }
        val d = r.getJSONObject("dat")
        token = d.getString("token")
        secret = d.getString("secret")
    }

    private fun buscarDispositivo(): Dispositivo {
        val r = llamar("queryDevices&page=0&pagesize=50")
        val lista = r.optJSONObject("dat")?.optJSONArray("device")
        if (lista == null || lista.length() == 0) throw IllegalStateException("No hay dispositivos en la cuenta")
        val d = lista.getJSONObject(0)
        return Dispositivo(
            pn = d.getString("pn"),
            sn = d.getString("sn"),
            devcode = d.get("devcode").toString(),
            devaddr = d.get("devaddr").toString(),
        )
    }

    private fun llamar(accionParams: String): JSONObject {
        val t = token ?: throw SesionCaducada()
        val s = secret ?: throw SesionCaducada()
        val salt = System.currentTimeMillis().toString()
        val accion = "&action=$accionParams$SUFIJO"
        val sign = sha1(salt + s + t + accion)
        val r = pedir("$BASE?sign=$sign&salt=$salt&token=$t$accion")
        val err = r.optInt("err", -1)
        if (err == 12) throw SinRegistros() // ERR_NO_RECORD
        if (err != 0) {
            // Cualquier error de la API en una llamada autenticada: se reintenta con sesion nueva
            if (accionParams.startsWith("queryDevices") || accionParams.startsWith("querySP") ||
                accionParams.startsWith("queryDeviceDataOneDay")
            ) {
                throw SesionCaducada("SmartESS: ${r.optString("desc", "error $err")}")
            }
            throw IllegalStateException("SmartESS: ${r.optString("desc")}")
        }
        return r
    }

    private fun pedir(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.requestMethod = "GET"
        try {
            val texto = c.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(texto)
        } finally {
            c.disconnect()
        }
    }

    private fun sha1(texto: String): String =
        MessageDigest.getInstance("SHA-1").digest(texto.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        // Solo https (el script de prueba tambien probaba http: aqui no).
        const val BASE = "https://android.shinemonitor.com/public/"
        const val COMPANY_KEY = "bnrl_frRFjEz8Mkn"
        const val SUFIJO = "&i18n=es_ES&lang=es_ES&source=1&_app_client_=android" +
            "&_app_id_=wifiapp.volfw.watchpower&_app_version_=1.0.6.3"
    }
}
