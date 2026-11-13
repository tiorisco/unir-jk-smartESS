package com.mrsmanuel.monitorsolar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.RowScope
import com.mrsmanuel.monitorsolar.EstadoInversor
import com.mrsmanuel.monitorsolar.EstadoServidor
import com.mrsmanuel.monitorsolar.MuestraBms
import com.mrsmanuel.monitorsolar.PuntoDia
import com.mrsmanuel.monitorsolar.ResumenDia
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val es = Locale("es", "ES")

private fun fechaLarga(f: LocalDate): String =
    f.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", es)).replaceFirstChar { it.uppercase() }

private fun mesAnio(clave: String): String {
    val f = LocalDate.parse("$clave-01")
    return f.format(DateTimeFormatter.ofPattern("MMMM yyyy", es)).replaceFirstChar { it.uppercase() }
}

@Composable
private fun Leyenda(vararg items: Pair<String, androidx.compose.ui.graphics.Color>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((t, c) in items) Pastilla(t, c)
    }
}

@Composable
fun PantallaHoy(
    puntos: List<PuntoDia>,
    resumen: ResumenDia?,
    totalPvKwh: Float?,
    estado: EstadoInversor,
    bms: List<MuestraBms> = emptyList(),
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Hoy", color = Paleta.Texto, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(fechaLarga(LocalDate.now()), color = Paleta.TextoSuave, fontSize = 14.sp)
        if (estado is EstadoInversor.Error) {
            Text(estado.mensaje, color = Paleta.Naranja, fontSize = 12.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Tile("Producción", resumen?.pvKwh, Paleta.Sol)
            Tile("Consumo", resumen?.casaKwh, Paleta.Azul)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Tile("Batería cargada", resumen?.bateriaCargaKwh, Paleta.Verde)
            Tile("Batería descargada", resumen?.bateriaDescargaKwh, Paleta.Naranja)
        }

        Tarjeta(Modifier.fillMaxWidth(), titulo = "Potencia del día") {
            GraficaDia(
                puntos,
                listOf(
                    Serie(Paleta.Sol, true) { it.pvW },
                    Serie(Paleta.Azul, false) { it.casaW },
                ),
            )
            Leyenda("Placas" to Paleta.Sol, "Casa" to Paleta.Azul)
            if (resumen != null && resumen.picoPvW > 0f) {
                Dato("Pico de placas", "${num(resumen.picoPvW)} W" + (resumen.picoPvHora?.let { " a las $it" } ?: ""))
            }
        }

        if (bms.isNotEmpty()) {
            // SOC real del BMS guardado por el servidor (una muestra cada 5 min)
            val soc = bms.mapNotNull { m -> m.soc?.let { PuntoDia(m.hora, it, 0f, 0f, 0f, null, null) } }
            Tarjeta(Modifier.fillMaxWidth(), titulo = "Carga de la batería (BMS)") {
                GraficaDia(
                    soc,
                    listOf(Serie(colorSocTendencia(soc.lastOrNull()?.pvW), true) { it.pvW }),
                    formato = { num(it, 0) + " %" },
                    techoFijo = 100f,
                )
                val min = soc.minOfOrNull { it.pvW }
                val max = soc.maxOfOrNull { it.pvW }
                if (min != null && max != null) {
                    Dato("Hoy", "mín ${num(min)} % · máx ${num(max)} %")
                }
            }
        }

        Tarjeta(Modifier.fillMaxWidth(), titulo = "Batería del día") {
            GraficaDia(puntos, listOf(Serie(Paleta.Verde, true) { it.bateriaW }))
            Leyenda("Sobre 0: carga · bajo 0: descarga" to Paleta.Verde)
        }

        Tarjeta(Modifier.fillMaxWidth(), titulo = "Totales del inversor") {
            Dato("Producción total acumulada", if (totalPvKwh == null) "—" else "${num(totalPvKwh, 0)} kWh")
        }

        val primero = puntos.firstOrNull()?.hora
        if (primero != null && primero.hour >= 1) {
            Text(
                "SmartESS guarda los datos de hoy desde las %02d:%02d. El consumo y la batería de antes de esa hora no cuentan; la producción usa el contador del propio inversor."
                    .format(primero.hour, primero.minute),
                color = Paleta.TextoSuave.copy(alpha = 0.7f), fontSize = 11.sp,
            )
        }
    }
}

private fun colorSocTendencia(soc: Float?) = colorSoc(soc)

@Composable
private fun RowScope.Tile(titulo: String, kwh: Float?, color: androidx.compose.ui.graphics.Color) {
    ValorGrande(titulo, num(kwh, 1), "kWh", color, modifier = Modifier.weight(1f))
}

@Composable
fun PantallaHistorico(dias: List<ResumenDia>, servidor: EstadoServidor = EstadoServidor(false)) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Histórico", color = Paleta.Texto, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            if (dias.isEmpty()) "Todavía no hay días guardados."
            else "${dias.size} ${if (dias.size == 1) "día guardado" else "días guardados"}",
            color = Paleta.TextoSuave, fontSize = 14.sp,
        )
        when {
            !servidor.configurado ->
                Text("Solo histórico de este móvil (sin servidor configurado)", color = Paleta.Naranja, fontSize = 12.sp)
            !servidor.correcto && servidor.aviso != null ->
                Text("Servidor: ${servidor.aviso}", color = Paleta.Naranja, fontSize = 12.sp)
            servidor.correcto -> {
                val u = servidor.ultimaRecogida
                val min = u?.let { java.time.Duration.between(it, java.time.LocalDateTime.now()).toMinutes() }
                val (txt, col) = when {
                    min == null -> "Servidor conectado" to Paleta.Verde
                    min <= 15 -> "Servidor al día · última recogida hace ${maxOf(min, 0)} min" to Paleta.Verde
                    else -> "El servidor no recoge desde hace ${min / 60} h ${min % 60} min" to Paleta.Naranja
                }
                Text(txt, color = col, fontSize = 12.sp)
                servidor.aviso?.let { Text(it, color = Paleta.Naranja, fontSize = 12.sp) }
            }
        }

        Tarjeta(Modifier.fillMaxWidth(), titulo = "Producción diaria (kWh)") {
            BarrasDias(dias, { it.pvKwh }, Paleta.Sol)
        }
        Tarjeta(Modifier.fillMaxWidth(), titulo = "Consumo diario (kWh)") {
            BarrasDias(dias, { it.casaKwh }, Paleta.Azul)
        }

        // Totales por mes
        val porMes = dias.groupBy { it.fecha.substring(0, 7) }.toSortedMap(compareByDescending { it })
        if (porMes.isNotEmpty()) {
            Tarjeta(Modifier.fillMaxWidth(), titulo = "Por meses") {
                for ((mes, lista) in porMes) {
                    Text(mesAnio(mes) + " · ${lista.size} ${if (lista.size == 1) "día" else "días"}", color = Paleta.Texto, fontWeight = FontWeight.SemiBold)
                    Dato("Producción", "${num(lista.sumOf { it.pvKwh.toDouble() }.toFloat(), 1)} kWh", Paleta.Sol)
                    Dato("Consumo", "${num(lista.sumOf { it.casaKwh.toDouble() }.toFloat(), 1)} kWh", Paleta.Azul)
                }
            }
        }

        // Lista de los ultimos dias
        if (dias.isNotEmpty()) {
            Tarjeta(Modifier.fillMaxWidth(), titulo = "Últimos días") {
                for (d in dias.takeLast(14).reversed()) {
                    Dato(
                        d.fecha.substring(8, 10) + "/" + d.fecha.substring(5, 7),
                        "☀ ${num(d.pvKwh, 1)}   🏠 ${num(d.casaKwh, 1)} kWh" +
                            if (d.socMin != null && d.socMax != null) "   🔋 ${num(d.socMin)}–${num(d.socMax)} %" else "",
                    )
                }
            }
        }

        Text(
            if (servidor.configurado)
                "SmartESS solo guarda el día en curso. El servidor lo recoge cada 5 minutos y por eso el histórico empieza el día que se instaló, sin huecos aunque no abras la app."
            else
                "SmartESS no guarda días anteriores para este inversor, así que este histórico empieza el día que se instaló la app y solo se completa los días en que la abres.",
            color = Paleta.TextoSuave.copy(alpha = 0.7f), fontSize = 11.sp,
        )
    }
}
