package com.mrsmanuel.monitorsolar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.mrsmanuel.monitorsolar.ui.PantallaHistorico
import com.mrsmanuel.monitorsolar.ui.PantallaHoy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mrsmanuel.monitorsolar.ui.BarraCeldas
import com.mrsmanuel.monitorsolar.ui.Dato
import com.mrsmanuel.monitorsolar.ui.DiagramaFlujo
import com.mrsmanuel.monitorsolar.ui.FondoDegradado
import com.mrsmanuel.monitorsolar.ui.GraficaHistorial
import com.mrsmanuel.monitorsolar.ui.MedidorSoc
import com.mrsmanuel.monitorsolar.ui.Paleta
import com.mrsmanuel.monitorsolar.ui.Pastilla
import com.mrsmanuel.monitorsolar.ui.Tarjeta
import com.mrsmanuel.monitorsolar.ui.ValorGrande
import com.mrsmanuel.monitorsolar.ui.colorSoc
import com.mrsmanuel.monitorsolar.ui.num
import com.mrsmanuel.monitorsolar.ui.numSigno
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val vm: BateriaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // El inversor se consulta solo mientras la app esta visible
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.bucleInversor() }
                launch { vm.bucleDia() }
                launch { vm.bucleServidor() }
                launch { vm.bucleModoServidor() }
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                FondoDegradado { AppTerrenico(vm) }
            }
        }
    }
}

@Composable
fun AppTerrenico(vm: BateriaViewModel) {
    var pestana by rememberSaveable { mutableIntStateOf(0) }
    val dia by vm.dia.collectAsStateWithLifecycle()
    val resumen by vm.resumenHoy.collectAsStateWithLifecycle()
    val historico by vm.historico.collectAsStateWithLifecycle()
    val total by vm.totalPvKwh.collectAsStateWithLifecycle()
    val estadoDia by vm.estadoDia.collectAsStateWithLifecycle()
    val estadoServidor by vm.estadoServidor.collectAsStateWithLifecycle()
    val bmsDia by vm.bmsDia.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(containerColor = Color(0xCC081633)) {
                val colores = NavigationBarItemDefaults.colors(
                    indicatorColor = Color(0x33FFFFFF),
                    selectedTextColor = Paleta.Texto,
                    unselectedTextColor = Paleta.TextoSuave,
                )
                listOf("⚡" to "Ahora", "📈" to "Hoy", "📅" to "Histórico").forEachIndexed { i, (icono, texto) ->
                    NavigationBarItem(
                        selected = pestana == i,
                        onClick = { pestana = i },
                        icon = { Text(icono, fontSize = 20.sp) },
                        label = { Text(texto) },
                        colors = colores,
                    )
                }
            }
        },
    ) { margen ->
        Box(Modifier.padding(margen)) {
            when (pestana) {
                0 -> PanelTerrenico(vm)
                1 -> PantallaHoy(dia, resumen, total, estadoDia, bmsDia)
                else -> PantallaHistorico(historico, estadoServidor)
            }
        }
    }
}

@Composable
fun PanelTerrenico(vm: BateriaViewModel) {
    val bat by vm.datos.collectAsStateWithLifecycle()
    val conexion by vm.conexion.collectAsStateWithLifecycle()
    val inv by vm.inversor.collectAsStateWithLifecycle()
    val estadoInv by vm.estadoInversor.collectAsStateWithLifecycle()
    val historial by vm.historial.collectAsStateWithLifecycle()

    // Reloj para la antiguedad de los datos
    var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            ahora = System.currentTimeMillis()
            delay(1000)
        }
    }
    val segBat = if (bat.ultimaActualizacion == 0L) null else (ahora - bat.ultimaActualizacion) / 1000

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cabecera(conexion, bat, segBat, estadoInv, inv, ahora)

        // ---- Bateria: medidor + resumen ----
        Tarjeta(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                MedidorSoc(bat.soc, colorSoc(bat.soc))
                Text(sentido(bat.corriente), color = colorSentido(bat.corriente), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${numSigno(bat.potencia)} W  ·  ${numSigno(bat.corriente, 1)} A",
                    color = Paleta.TextoSuave, fontSize = 15.sp,
                )
                estimacion(bat)?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = Paleta.TextoSuave, fontSize = 14.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text("SOC medido por el BMS JK", color = Paleta.TextoSuave.copy(alpha = 0.6f), fontSize = 11.sp)
            }
        }

        // ---- Flujo de energia ----
        Tarjeta(Modifier.fillMaxWidth(), titulo = "Flujo de energía") {
            DiagramaFlujo(
                pvW = inv.pvPotencia,
                casaW = inv.cargaPotencia,
                bateriaW = bat.potencia,
                soc = bat.soc,
                redV = inv.redVoltaje,
                hayRed = inv.hayRed,
            )
            inv.modo?.let { Text("Modo: $it", color = Paleta.TextoSuave, fontSize = 13.sp) }
        }

        // ---- Placas y casa ----
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ValorGrande(
                "Placas", num(inv.pvPotencia), "W", Paleta.Sol,
                detalle = "${num(inv.pvVoltaje, 0)} V · ${num(inv.pvCorriente, 1)} A",
                modifier = Modifier.weight(1f),
            )
            ValorGrande(
                "Casa", num(inv.cargaPotencia), "W", Paleta.Azul,
                detalle = "${num(inv.cargaPorcentaje)} % de carga",
                modifier = Modifier.weight(1f),
            )
        }

        // ---- Historial ----
        Tarjeta(Modifier.fillMaxWidth(), titulo = "Últimos minutos") {
            GraficaHistorial(historial)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Pastilla("Placas", Paleta.Sol)
                Pastilla("Casa", Paleta.Azul)
            }
        }

        // ---- Detalle bateria ----
        Tarjeta(Modifier.fillMaxWidth(), titulo = "Batería (BMS)") {
            Dato("Voltaje", "${num(bat.voltaje, 2)} V")
            Dato("Capacidad restante", "${num(bat.capacidadRestante, 1)} Ah")
            Dato("Celda mínima", "${num(bat.celdaMin, 3)} V")
            Dato("Celda máxima", "${num(bat.celdaMax, 3)} V")
            BarraCeldas(bat.celdaMin, bat.celdaMax)
            Dato("Diferencia entre celdas", "${num(bat.celdaDelta?.times(1000), 0)} mV")
            Dato("Temperaturas", "${num(bat.temp1, 1)} / ${num(bat.temp2, 1)} °C")
            Dato("MOSFET", "${num(bat.tempMosfet, 1)} °C")
            Dato("Carga", activada(bat.cargaActivada), colorActivada(bat.cargaActivada))
            Dato("Descarga", activada(bat.descargaActivada), colorActivada(bat.descargaActivada))
        }

        // ---- Detalle inversor ----
        Tarjeta(Modifier.fillMaxWidth(), titulo = "Inversor") {
            Dato("Salida", "${num(inv.salidaVoltaje, 1)} V · ${num(inv.salidaFrecuencia, 1)} Hz")
            Dato("Potencia aparente", "${num(inv.cargaAparente)} VA")
            Dato("Red", if (inv.hayRed) "${num(inv.redVoltaje, 0)} V · ${num(inv.redFrecuencia, 1)} Hz" else "Sin red")
            Dato("Batería (según inversor)", "${num(inv.bateriaVoltaje, 1)} V · ${num(inv.bateriaCorriente, 1)} A")
            Dato("Temperaturas", "${num(inv.tempDcdc)} / ${num(inv.tempInversor)} / ${num(inv.tempPv)} °C")
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Cabecera(
    conexion: EstadoConexion,
    bat: DatosBateria,
    segBat: Long?,
    estadoInv: EstadoInversor,
    inv: DatosInversor,
    ahora: Long,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(R.drawable.logo_terrenico),
            contentDescription = "Terrenico",
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text("Terrenico", color = Paleta.Texto, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Energía solar", color = Paleta.TextoSuave, fontSize = 13.sp)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val (tb, cb) = estadoBateria(conexion, bat, segBat)
        Pastilla("Batería · $tb", cb)
        val (ti, ci) = estadoInversorTexto(estadoInv, inv, ahora)
        Pastilla("Inversor · $ti", ci)
    }
    if (estadoInv is EstadoInversor.Error) {
        Text(estadoInv.mensaje, color = Paleta.Naranja, fontSize = 12.sp)
    }
}

private fun estadoBateria(conexion: EstadoConexion, d: DatosBateria, seg: Long?): Pair<String, Color> = when {
    conexion == EstadoConexion.SIN_CONFIGURAR -> "sin configurar" to Paleta.Rojo
    conexion == EstadoConexion.CONECTANDO -> "conectando" to Paleta.Naranja
    conexion != EstadoConexion.CONECTADO -> "sin conexión" to Paleta.Naranja
    d.puenteOnline == false -> "ESP32 apagado" to Paleta.Rojo
    d.bmsOnline == false -> "sin BMS" to Paleta.Rojo
    seg == null -> "esperando" to Paleta.Naranja
    seg > 60 -> "hace ${seg / 60} min" to Paleta.Naranja
    else -> "en directo" to Paleta.Verde
}

private fun estadoInversorTexto(e: EstadoInversor, inv: DatosInversor, ahora: Long): Pair<String, Color> = when (e) {
    EstadoInversor.NoConfigurado -> "sin configurar" to Paleta.Rojo
    EstadoInversor.Cargando -> "conectando" to Paleta.Naranja
    EstadoInversor.Correcto -> {
        // La nube de SmartESS puede repetir el mismo dato durante minutos
        val seg = (ahora - inv.ultimoCambio) / 1000
        if (seg < 90) "en directo" to Paleta.Verde
        else "dato de hace ${seg / 60} min" to Paleta.Naranja
    }
    is EstadoInversor.Error -> "error" to Paleta.Rojo
}

/** Confirmado con el hardware (12-11-2026): corriente positiva = cargando, negativa = descargando. */
private fun sentido(i: Float?): String = when {
    i == null -> "—"
    i > 0.5f -> "Cargando"
    i < -0.5f -> "Descargando"
    else -> "En reposo"
}

private fun colorSentido(i: Float?): Color = when {
    i == null -> Paleta.TextoSuave
    i > 0.5f -> Paleta.Verde
    i < -0.5f -> Paleta.Naranja
    else -> Paleta.TextoSuave
}

private fun activada(b: Boolean?) = when (b) {
    true -> "Activada"
    false -> "Desactivada"
    null -> "—"
}

private fun colorActivada(b: Boolean?) = when (b) {
    true -> Paleta.Verde
    false -> Paleta.Rojo
    null -> Paleta.TextoSuave
}

/** Estimacion aproximada: la capacidad total sale de capacidad restante / SOC (el SOC es entero). */
private fun estimacion(d: DatosBateria): String? {
    val soc = d.soc ?: return null
    val cap = d.capacidadRestante ?: return null
    val i = d.corriente ?: return null
    if (soc < 3f) return null
    val total = cap / (soc / 100f)
    return when {
        i > 0.5f -> tiempo((total - cap) / i, "Llena")
        i < -0.5f -> tiempo(cap / -i, "Vacía")
        else -> null
    }
}

/** "Llena en ~2 h 10 min", o "más de 24 h" cuando la estimacion es demasiado larga para ser util. */
private fun tiempo(h: Float, verbo: String): String? {
    if (h.isNaN() || h <= 0f) return null
    if (h > 24f) return "$verbo en más de 24 h"
    val m = (h * 60).roundToInt()
    return "$verbo en ~" + if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}
