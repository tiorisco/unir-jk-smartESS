package com.mrsmanuel.monitorsolar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mrsmanuel.monitorsolar.PuntoDia
import com.mrsmanuel.monitorsolar.ResumenDia
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Serie(val color: Color, val relleno: Boolean, val valor: (PuntoDia) -> Float)

/** Sube v a un techo "bonito" (1, 1.5, 2, 2.5, 3, 4, 5, 6, 8 x potencia de 10). */
private fun techoBonito(v: Float): Float {
    if (v <= 0f) return 1f
    var p = 1f
    while (p * 10f < v) p *= 10f
    for (m in listOf(1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f, 8f, 10f)) {
        if (m * p >= v) return m * p
    }
    return 10f * p
}

private fun etiquetaW(v: Float): String =
    if (abs(v) >= 1000f) num(v / 1000f, 1) + " kW" else num(v, 0) + " W"

private fun minutosDelDia(p: PuntoDia) = p.hora.hour * 60 + p.hora.minute

/**
 * Curvas del dia (eje X: 0-24 h). Cada serie se dibuja como linea, con relleno opcional.
 * Los huecos de mas de 30 min en los datos cortan la linea en vez de unir los puntos.
 */
@Composable
fun GraficaDia(
    puntos: List<PuntoDia>,
    series: List<Serie>,
    modifier: Modifier = Modifier,
    alto: Dp = 190.dp,
    formato: (Float) -> String = ::etiquetaW,
    techoFijo: Float? = null,   // p. ej. 100 para un porcentaje: eje de 0 a techoFijo
) {
    val medidor = rememberTextMeasurer()
    val estiloEje = TextStyle(color = Paleta.TextoSuave, fontSize = 10.sp)

    Canvas(modifier.fillMaxWidth().height(alto)) {
        val izq = 46.dp.toPx()
        val abajo = 18.dp.toPx()
        val arriba = 6.dp.toPx()
        val ancho = size.width - izq - 4.dp.toPx()
        val utilAlto = size.height - abajo - arriba

        if (puntos.size < 2) {
            drawText(medidor, "Sin datos todavía", Offset(izq, size.height / 2 - 8.dp.toPx()), estiloEje)
            return@Canvas
        }

        val todos = series.flatMap { s -> puntos.map(s.valor) }
        val techo = techoFijo ?: techoBonito(max(100f, todos.maxOrNull() ?: 0f))
        val minimo = todos.minOrNull() ?: 0f
        val suelo = if (techoFijo == null && minimo < 0f) -techoBonito(-minimo) else 0f

        fun y(v: Float) = arriba + utilAlto * (1f - (v - suelo) / (techo - suelo))
        fun x(p: PuntoDia) = izq + ancho * minutosDelDia(p) / 1440f

        // Lineas de guia horizontales con su valor
        val niveles = if (suelo < 0f) listOf(suelo, 0f, techo) else listOf(0f, techo / 2f, techo)
        for (n in niveles) {
            drawLine(Color(0x22FFFFFF), Offset(izq, y(n)), Offset(izq + ancho, y(n)), strokeWidth = 1.dp.toPx())
            val t = medidor.measure(formato(n), estiloEje)
            drawText(medidor, formato(n), Offset(izq - t.size.width - 4.dp.toPx(), y(n) - t.size.height / 2f), estiloEje)
        }
        // Eje de horas
        for (h in listOf(0, 6, 12, 18, 24)) {
            val xh = izq + ancho * h / 24f
            val txt = "${h}h"
            val t = medidor.measure(txt, estiloEje)
            drawText(medidor, txt, Offset(min(max(xh - t.size.width / 2f, izq - 6.dp.toPx()), size.width - t.size.width), size.height - abajo + 3.dp.toPx()), estiloEje)
            drawLine(Color(0x14FFFFFF), Offset(xh, arriba), Offset(xh, arriba + utilAlto), strokeWidth = 1.dp.toPx())
        }

        for (s in series) {
            val linea = Path()
            val relleno = Path()
            var anterior: PuntoDia? = null
            var abierto = false
            for (p in puntos) {
                val px = x(p)
                val py = y(s.valor(p))
                val hueco = anterior != null && (minutosDelDia(p) - minutosDelDia(anterior)) > 30
                if (anterior == null || hueco) {
                    if (abierto && s.relleno) {
                        relleno.lineTo(x(anterior!!), y(0f))
                        relleno.close()
                    }
                    linea.moveTo(px, py)
                    relleno.moveTo(px, y(0f))
                    relleno.lineTo(px, py)
                    abierto = true
                } else {
                    linea.lineTo(px, py)
                    relleno.lineTo(px, py)
                }
                anterior = p
            }
            if (abierto && s.relleno && anterior != null) {
                relleno.lineTo(x(anterior), y(0f))
                relleno.close()
            }
            if (s.relleno) {
                drawPath(relleno, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.40f), s.color.copy(alpha = 0.02f))))
            }
            drawPath(linea, s.color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Barras por dia (los ultimos 30 dias guardados). */
@Composable
fun BarrasDias(
    dias: List<ResumenDia>,
    valor: (ResumenDia) -> Float,
    color: Color,
    modifier: Modifier = Modifier,
    alto: Dp = 150.dp,
) {
    val medidor = rememberTextMeasurer()
    val estiloEje = TextStyle(color = Paleta.TextoSuave, fontSize = 10.sp)
    val ultimos = dias.takeLast(30)

    Canvas(modifier.fillMaxWidth().height(alto)) {
        val izq = 40.dp.toPx()
        val abajo = 18.dp.toPx()
        val arriba = 6.dp.toPx()
        val ancho = size.width - izq - 4.dp.toPx()
        val utilAlto = size.height - abajo - arriba

        if (ultimos.isEmpty()) {
            drawText(medidor, "Sin datos todavía", Offset(izq, size.height / 2 - 8.dp.toPx()), estiloEje)
            return@Canvas
        }
        val techo = techoBonito(max(1f, ultimos.maxOf(valor)))
        for (n in listOf(0f, techo / 2f, techo)) {
            val yy = arriba + utilAlto * (1f - n / techo)
            drawLine(Color(0x22FFFFFF), Offset(izq, yy), Offset(izq + ancho, yy), strokeWidth = 1.dp.toPx())
            val txt = num(n, if (techo < 10f) 1 else 0)
            val t = medidor.measure(txt, estiloEje)
            drawText(medidor, txt, Offset(izq - t.size.width - 4.dp.toPx(), yy - t.size.height / 2f), estiloEje)
        }

        val hueco = ancho / ultimos.size
        val anchoBarra = min(hueco * 0.7f, 28.dp.toPx())
        ultimos.forEachIndexed { i, d ->
            val h = utilAlto * (valor(d) / techo).coerceIn(0f, 1f)
            val xb = izq + hueco * i + (hueco - anchoBarra) / 2f
            drawRoundRect(
                Brush.verticalGradient(listOf(color, color.copy(alpha = 0.45f)), startY = arriba + utilAlto - h, endY = arriba + utilAlto),
                topLeft = Offset(xb, arriba + utilAlto - h),
                size = Size(anchoBarra, max(h, 2.dp.toPx())),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
        // Fechas del primer y ultimo dia (dd/MM)
        fun corta(f: String) = f.substring(8, 10) + "/" + f.substring(5, 7)
        val primero = corta(ultimos.first().fecha)
        drawText(medidor, primero, Offset(izq, size.height - abajo + 3.dp.toPx()), estiloEje)
        if (ultimos.size > 1) {
            val ultimo = corta(ultimos.last().fecha)
            val t = medidor.measure(ultimo, estiloEje)
            drawText(medidor, ultimo, Offset(izq + ancho - t.size.width, size.height - abajo + 3.dp.toPx()), estiloEje)
        }
    }
}
