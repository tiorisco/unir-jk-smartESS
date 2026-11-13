package com.mrsmanuel.monitorsolar

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Se suscribe (solo lectura) a solar/bateria/# del broker MQTT por TLS y
 * mantiene el ultimo valor de cada sensor. No publica nada.
 */
class MqttRepositorio {

    private val _datos = MutableStateFlow(DatosBateria())
    val datos: StateFlow<DatosBateria> = _datos.asStateFlow()

    private val _conexion = MutableStateFlow(EstadoConexion.CONECTANDO)
    val conexion: StateFlow<EstadoConexion> = _conexion.asStateFlow()

    private var cliente: Mqtt3AsyncClient? = null

    fun iniciar() {
        if (cliente != null) return
        if (BuildConfig.MQTT_HOST.isBlank()) {
            _conexion.value = EstadoConexion.SIN_CONFIGURAR
            return
        }

        val c = MqttClient.builder()
            .useMqttVersion3()
            .identifier("monitor-solar-" + UUID.randomUUID().toString().take(8))
            .serverHost(BuildConfig.MQTT_HOST)
            .serverPort(8883)
            .sslWithDefaultConfig() // usa los certificados raiz del sistema Android
            .automaticReconnectWithDefaultConfig()
            .addConnectedListener {
                _conexion.value = EstadoConexion.CONECTADO
                suscribir()
            }
            .addDisconnectedListener { _conexion.value = EstadoConexion.DESCONECTADO }
            .buildAsync()
        cliente = c

        c.publishes(MqttGlobalPublishFilter.ALL) { publicacion -> procesar(publicacion) }

        _conexion.value = EstadoConexion.CONECTANDO
        c.connectWith()
            .simpleAuth()
            .username(BuildConfig.MQTT_USER)
            .password(BuildConfig.MQTT_PASSWORD.toByteArray())
            .applySimpleAuth()
            .keepAlive(30)
            .send()
    }

    private fun suscribir() {
        cliente?.subscribeWith()
            ?.topicFilter("solar/bateria/#")
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.send()
    }

    fun detener() {
        cliente?.disconnect()
        cliente = null
    }

    private fun procesar(p: Mqtt3Publish) {
        val topic = p.topic.toString().removePrefix("solar/bateria/")
        val texto = String(p.payloadAsBytes).trim()
        val ahora = System.currentTimeMillis()

        fun numero() = texto.toFloatOrNull()
        fun booleano() = when (texto.uppercase()) {
            "ON", "TRUE", "1" -> true
            "OFF", "FALSE", "0" -> false
            else -> null
        }

        _datos.update { d ->
            val nuevo = when (topic) {
                "sensor/soc/state" -> d.copy(soc = numero())
                "sensor/voltaje/state" -> d.copy(voltaje = numero())
                "sensor/corriente/state" -> d.copy(corriente = numero())
                "sensor/potencia/state" -> d.copy(potencia = numero())
                "sensor/temp1/state" -> d.copy(temp1 = numero())
                "sensor/temp2/state" -> d.copy(temp2 = numero())
                "sensor/temp_mosfet/state" -> d.copy(tempMosfet = numero())
                "sensor/celda_min/state" -> d.copy(celdaMin = numero())
                "sensor/celda_max/state" -> d.copy(celdaMax = numero())
                "sensor/celda_delta/state" -> d.copy(celdaDelta = numero())
                "sensor/capacidad_restante/state" -> d.copy(capacidadRestante = numero())
                "binary_sensor/carga_activada/state" -> d.copy(cargaActivada = booleano())
                "binary_sensor/descarga_activada/state" -> d.copy(descargaActivada = booleano())
                "binary_sensor/online/state" -> d.copy(bmsOnline = booleano())
                "status" -> d.copy(puenteOnline = texto.equals("online", ignoreCase = true))
                else -> return@update d
            }
            // "ultima actualizacion" solo avanza con datos de sensores, no con el estado
            if (topic.startsWith("sensor/")) nuevo.copy(ultimaActualizacion = ahora) else nuevo
        }
    }
}
