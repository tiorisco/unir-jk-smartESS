package com.mrsmanuel.monitorsolar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * Une las fuentes:
 *  - Bateria (JK BMS) por MQTT: es la fuente FIABLE del SOC.
 *  - Inversor (Easun) por la nube de SmartESS: lectura "ahora" cada 10 s y serie del dia
 *    cada 60 s, solo mientras la app esta visible.
 */
class BateriaViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MqttRepositorio()
    private val historicoLocal = HistoricoLocal(app)
    private val smartess: SmartessCliente? =
        if (BuildConfig.SMARTESS_USER.isBlank()) null
        else SmartessCliente(BuildConfig.SMARTESS_USER, BuildConfig.SMARTESS_PASSWORD)

    val datos = repo.datos
    val conexion = repo.conexion

    private val _inversor = MutableStateFlow(DatosInversor())
    val inversor: StateFlow<DatosInversor> = _inversor.asStateFlow()

    private val servidorModo: ServidorCliente? =
        if (BuildConfig.SERVIDOR_TOKEN.isBlank()) null
        else ServidorCliente(BuildConfig.SERVIDOR_URL, BuildConfig.SERVIDOR_TOKEN)

    // Sin cuenta de SmartESS (version para la familia) el inversor sale del servidor
    private val sinSmartess = smartess == null && servidorModo != null

    private val _estadoInversor = MutableStateFlow<EstadoInversor>(
        if (smartess == null && !sinSmartess) EstadoInversor.NoConfigurado else EstadoInversor.Cargando
    )
    val estadoInversor: StateFlow<EstadoInversor> = _estadoInversor.asStateFlow()

    private val _historial = MutableStateFlow<List<Muestra>>(emptyList())
    val historial: StateFlow<List<Muestra>> = _historial.asStateFlow()

    // ---- Estadisticas del dia ----
    private val _dia = MutableStateFlow<List<PuntoDia>>(emptyList())
    val dia: StateFlow<List<PuntoDia>> = _dia.asStateFlow()

    private val _resumenHoy = MutableStateFlow<ResumenDia?>(null)
    val resumenHoy: StateFlow<ResumenDia?> = _resumenHoy.asStateFlow()

    private val servidor: ServidorCliente? =
        if (BuildConfig.SERVIDOR_TOKEN.isBlank()) null
        else ServidorCliente(BuildConfig.SERVIDOR_URL, BuildConfig.SERVIDOR_TOKEN)

    // Historico del movil + el del servidor. Si un dia esta en los dos, manda el del servidor,
    // que recoge todo el dia aunque la app no este abierta.
    private var historicoLocalLista: List<ResumenDia> = historicoLocal.todos()
    private var historicoServidorLista: List<ResumenDia> = emptyList()

    private val _historico = MutableStateFlow(historicoLocalLista)
    val historico: StateFlow<List<ResumenDia>> = _historico.asStateFlow()

    private fun combinarHistorico() {
        val mapa = LinkedHashMap<String, ResumenDia>()
        historicoLocalLista.forEach { mapa[it.fecha] = it }
        historicoServidorLista.forEach { mapa[it.fecha] = it }
        _historico.value = mapa.values.sortedBy { it.fecha }
    }

    private val _estadoServidor = MutableStateFlow(EstadoServidor(configurado = servidor != null))
    val estadoServidor: StateFlow<EstadoServidor> = _estadoServidor.asStateFlow()

    private val _bmsDia = MutableStateFlow<List<MuestraBms>>(emptyList())
    val bmsDia: StateFlow<List<MuestraBms>> = _bmsDia.asStateFlow()

    private val _totalPvKwh = MutableStateFlow<Float?>(null)
    val totalPvKwh: StateFlow<Float?> = _totalPvKwh.asStateFlow()

    private val _estadoDia = MutableStateFlow<EstadoInversor>(
        if (smartess == null && !sinSmartess) EstadoInversor.NoConfigurado else EstadoInversor.Cargando
    )
    val estadoDia: StateFlow<EstadoInversor> = _estadoDia.asStateFlow()

    init {
        repo.iniciar()
    }

    /** Bucle de consulta del inversor. Se cancela solo cuando la app deja de estar visible. */
    suspend fun bucleInversor() {
        val cliente = smartess ?: return
        while (true) {
            try {
                val previo = _inversor.value
                val leido = cliente.leer()
                val cambio = if (previo.ultimoCambio == 0L || leido.sinMarcas() != previo.sinMarcas()) {
                    leido.recibido
                } else {
                    previo.ultimoCambio
                }
                val d = leido.copy(ultimoCambio = cambio)
                _inversor.value = d
                _estadoInversor.value = EstadoInversor.Correcto
                anadirMuestra(d)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _estadoInversor.value = EstadoInversor.Error(e.message ?: "Error de SmartESS")
            }
            delay(10_000)
        }
    }

    /** Descarga la serie del dia cada minuto y guarda el resumen en el historico local. */
    suspend fun bucleDia() {
        val cliente = smartess ?: return
        while (true) {
            try {
                val hoy = LocalDate.now()
                val puntos = cliente.leerDia(hoy)
                _dia.value = puntos
                _estadoDia.value = EstadoInversor.Correcto
                puntos.lastOrNull()?.energiaPvTotal?.let { _totalPvKwh.value = it }
                if (puntos.size >= 2) {
                    val r = resumir(hoy.toString(), puntos)
                    _resumenHoy.value = r
                    historicoLocal.guardar(r)
                    historicoLocalLista = historicoLocal.todos()
                    combinarHistorico()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _estadoDia.value = EstadoInversor.Error(e.message ?: "Error de SmartESS")
            }
            delay(60_000)
        }
    }

    /**
     * Version para la familia: sin cuenta de SmartESS en el movil. El inversor y la serie del
     * dia salen del colector del servidor (con hasta 5 minutos de retraso).
     */
    suspend fun bucleModoServidor() {
        if (!sinSmartess) return
        val cliente = servidorModo ?: return
        while (true) {
            try {
                val hoy = LocalDate.now()
                val d = cliente.dia(hoy)
                val puntos = d.inversor
                _dia.value = puntos
                _bmsDia.value = d.bms
                val ultimo = puntos.lastOrNull()
                if (ultimo == null) {
                    _estadoInversor.value = EstadoInversor.Error("Todavía no hay datos de hoy en el servidor")
                } else {
                    val horaMs = ultimo.hora.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    _inversor.value = DatosInversor(
                        pvPotencia = ultimo.pvW,
                        cargaPotencia = ultimo.casaW,
                        redPotencia = ultimo.redW,
                        recibido = System.currentTimeMillis(),
                        ultimoCambio = horaMs,
                    )
                    _estadoInversor.value = EstadoInversor.Correcto
                    ultimo.energiaPvTotal?.let { _totalPvKwh.value = it }
                    if (puntos.size >= 2) _resumenHoy.value = resumir(hoy.toString(), puntos)
                }
                _estadoDia.value = EstadoInversor.Correcto
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _estadoInversor.value = EstadoInversor.Error(e.message ?: "Error del servidor")
                _estadoDia.value = EstadoInversor.Error(e.message ?: "Error del servidor")
            }
            delay(60_000)
        }
    }

    /** Lee del colector el historico completo y el SOC de hoy, cada 2 minutos. */
    suspend fun bucleServidor() {
        val cliente = servidor ?: return
        while (true) {
            try {
                val h = cliente.historico()
                historicoServidorLista = h.dias
                combinarHistorico()
                h.pvTotalKwh?.let { _totalPvKwh.value = it }
                _bmsDia.value = cliente.dia(LocalDate.now()).bms
                // Si el colector lleva mucho sin recoger, se avisa
                val ultima = listOfNotNull(h.ultimaRecogidaInversor, h.ultimaRecogidaBms).maxOrNull()
                _estadoServidor.value = EstadoServidor(
                    configurado = true,
                    correcto = true,
                    ultimaRecogida = ultima,
                    aviso = listOfNotNull(
                        h.errorInversor?.let { "Inversor: $it" },
                        h.errorBms?.let { "BMS: $it" },
                    ).firstOrNull(),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _estadoServidor.value = EstadoServidor(
                    configurado = true,
                    correcto = false,
                    aviso = e.message ?: "Error del servidor",
                )
            }
            delay(120_000)
        }
    }

    private fun anadirMuestra(d: DatosInversor) {
        val bateriaW = datos.value.potencia ?: 0f
        val m = Muestra(
            hora = System.currentTimeMillis(),
            pv = d.pvPotencia ?: 0f,
            casa = d.cargaPotencia ?: 0f,
            bateria = bateriaW,
        )
        // 90 muestras a 10 s = 15 minutos
        _historial.value = (_historial.value + m).takeLast(90)
    }

    override fun onCleared() {
        repo.detener()
    }
}
