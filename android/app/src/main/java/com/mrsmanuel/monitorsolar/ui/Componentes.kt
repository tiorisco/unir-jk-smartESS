package com.mrsmanuel.monitorsolar.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mrsmanuel.monitorsolar.Muestra
import java.util.Locale
import kotlin.math.max

object Paleta {
    val FondoArriba = Color(0xFF0B1F44)
    val FondoAbajo = Color(0xFF05302C)
    val Tarjeta = Color(0x24FFFFFF)
    val Borde = Color(0x33FFFFFF)
    val Nodo = Color(0xFF0F2A52)
    val Texto = Color(0xFFFFFFFF)
    val TextoSuave = Color(0xB3FFFFFF)
    val Sol = Color(0xFFFFC107)
    val Verde = Color(0xFF69F0AE)
    val Azul = Color(0xFF4FC3F7)
    val Naranja = Color(0xFFFFA726)
    val Rojo = Color(0xFFFF5252)
}

private val esES = Locale("es", "ES")

fun num(v: Float?, decimales: Int = 0): String =
    if (v == null) "—" else String.format(esES, "%.${decimales}f", v)

fun numSigno(v: Float?, decimales: Int = 0): String =
    if (v == null) "—" else String.format(esES, "%+.${decimales}f", v)

fun colorSoc(soc: Float?): Color = when {
    soc == null -> Color.Gray
    soc < 20f -> Paleta.Rojo
    soc < 40f -> Paleta.Naranja
    else -> Paleta.Verde
}

/** Tarjeta translucida con borde fino. */
@Composable
fun Tarjeta(
    modifier: Modifier = Modifier,
    titulo: String? = null,
    contenido: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Paleta.Tarjeta,
        border = BorderStroke(1.dp, Paleta.Borde),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (titulo != null) {
                Text(titulo, color = Paleta.TextoSuave, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            contenido()
        }
    }
}

@Composable
fun Dato(etiqueta: String, valor: String, color: Color = Paleta.Texto) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(etiqueta, color = Paleta.TextoSuave, fontSize = 15.sp)
        Text(valor, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Valor grande con unidad y etiqueta, para las tarjetas pequenas (Placas / Casa). */
@Composable
fun ValorGrande(etiqueta: String, valor: String, unidad: String, color: Color, detalle: String? = null, modifier: Modifier = Modifier) {
    Tarjeta(modifier) {
        Text(etiqueta, color = Paleta.TextoSuave, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(valor, color = color, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(" $unidad", color = Paleta.TextoSuave, fontSize = 15.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (detalle != null) Text(detalle, color = Paleta.TextoSuave, fontSize = 12.sp)
    }
}

/** Pastilla de estado con punto de color. */
@Composable
fun Pastilla(texto: String, color: Color) {
    Row(
        Modifier
            .background(Color(0x22FFFFFF), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text("  $texto", color = Paleta.Texto, fontSize = 12.sp)
    }
}

/** Medidor circular del SOC (270 grados) con animacion al cambiar. */
@Composable
fun MedidorSoc(soc: Float?, color: Color, modifier: Modifier = Modifier) {
    val objetivo = ((soc ?: 0f) / 100f).coerceIn(0f, 1f)
    val animado by animateFloatAsState(objetivo, animationSpec = tween(900), label = "soc")
    Box(modifier.size(230.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val grosor = 24.dp.toPx()
            val tam = Size(size.width - grosor, size.height - grosor)
            val origen = Offset(grosor / 2, grosor / 2)
            // Pista
            drawArc(Color(0x26FFFFFF), 135f, 270f, false, origen, tam, style = Stroke(grosor, cap = StrokeCap.Round))
            // Resplandor suave detras del arco
            drawArc(color.copy(alpha = 0.18f), 135f, 270f * animado, false, origen, tam,
                style = Stroke(grosor * 1.6f, cap = StrokeCap.Round))
            // Arco de valor
            drawArc(color, 135f, 270f * animado, false, origen, tam, style = Stroke(grosor, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(num(soc), color = Paleta.Texto, fontSize = 64.sp, fontWeight = FontWeight.Bold)
                Text("%", color = Paleta.TextoSuave, fontSize = 24.sp, modifier = Modifier.padding(bottom = 12.dp))
            }
            Text("Batería", color = Paleta.TextoSuave, fontSize = 14.sp)
        }
    }
}

/** Nodo circular del diagrama de flujo. */
@Composable
private fun NodoFlujo(centro: androidx.compose.ui.unit.DpOffset, diametro: Dp, borde: Color, emoji: String, valor: String?, etiqueta: String) {
    Box(
        Modifier
            .offset(centro.x - diametro / 2, centro.y - diametro / 2)
            .size(diametro)
            .background(Paleta.Nodo, CircleShape)
            .border(2.dp, borde, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, fontSize = 22.sp)
            if (valor != null) Text(valor, color = Paleta.Texto, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(etiqueta, color = Paleta.TextoSuave, fontSize = 11.sp, textAlign = TextAlign.Center)
        }
    }
}

private fun DrawScope.lineaFlujo(desde: Offset, hasta: Offset, activa: Boolean, color: Color, t: Float) {
    drawLine(color.copy(alpha = if (activa) 0.45f else 0.12f), desde, hasta, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
    if (!activa) return
    for (k in 0 until 3) {
        val f = (t + k / 3f) % 1f
        val p = Offset(desde.x + (hasta.x - desde.x) * f, desde.y + (hasta.y - desde.y) * f)
        drawCircle(color, radius = 5.dp.toPx(), center = p)
    }
}

/**
 * Diagrama de flujo: placas arriba, red a la izquierda, casa a la derecha, bateria abajo,
 * inversor en el centro. Los puntos se mueven en el sentido real del flujo.
 * Potencia de bateria: positiva = cargando (confirmado con el hardware).
 */
@Composable
fun DiagramaFlujo(
    pvW: Float?,
    casaW: Float?,
    bateriaW: Float?,
    soc: Float?,
    redV: Float?,
    hayRed: Boolean,
    modifier: Modifier = Modifier,
) {
    val transicion = rememberInfiniteTransition(label = "flujo")
    val t by transicion.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "t")

    BoxWithConstraints(modifier.fillMaxWidth().height(380.dp)) {
        val w = maxWidth
        val h = maxHeight
        val centro = androidx.compose.ui.unit.DpOffset(w / 2, h / 2)
        val pv = androidx.compose.ui.unit.DpOffset(w / 2, 52.dp)
        val red = androidx.compose.ui.unit.DpOffset(52.dp, h / 2)
        val casa = androidx.compose.ui.unit.DpOffset(w - 52.dp, h / 2)
        val bat = androidx.compose.ui.unit.DpOffset(w / 2, h - 52.dp)

        val pvActivo = (pvW ?: 0f) > 20f
        val casaActiva = (casaW ?: 0f) > 5f
        val bat0 = bateriaW ?: 0f

        Canvas(Modifier.fillMaxSize()) {
            fun px(o: androidx.compose.ui.unit.DpOffset) = Offset(o.x.toPx(), o.y.toPx())
            lineaFlujo(px(pv), px(centro), pvActivo, Paleta.Sol, t)
            lineaFlujo(px(centro), px(casa), casaActiva, Paleta.Azul, t)
            when {
                bat0 > 20f -> lineaFlujo(px(centro), px(bat), true, Paleta.Verde, t)   // cargando
                bat0 < -20f -> lineaFlujo(px(bat), px(centro), true, Paleta.Naranja, t) // descargando
                else -> lineaFlujo(px(centro), px(bat), false, Paleta.Verde, t)
            }
            lineaFlujo(px(red), px(centro), hayRed, Paleta.Rojo, t)
        }

        NodoFlujo(pv, 92.dp, Paleta.Sol, "☀️", "${num(pvW)} W", "Placas")
        NodoFlujo(casa, 92.dp, Paleta.Azul, "🏠", "${num(casaW)} W", "Casa")
        NodoFlujo(bat, 92.dp, colorSoc(soc), "🔋", "${num(soc)} %", if (bat0 > 20f) "Cargando" else if (bat0 < -20f) "Descargando" else "Batería")
        NodoFlujo(red, 92.dp, if (hayRed) Paleta.Rojo else Color(0x55FFFFFF), "🔌", if (hayRed) "${num(redV)} V" else "Sin red", "Red")
        NodoFlujo(centro, 64.dp, Paleta.Borde, "⚡", null, "")
    }
}

/** Grafica de lineas con relleno: placas (sol) y consumo (azul) en los ultimos minutos. */
@Composable
fun GraficaHistorial(muestras: List<Muestra>, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(130.dp)) {
        // Lineas de guia horizontales
        for (i in 0..3) {
            val y = size.height * i / 3f
            drawLine(Color(0x1AFFFFFF), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        if (muestras.size < 2) return@Canvas
        val maximo = max(100f, muestras.maxOf { max(it.pv, it.casa) }) * 1.1f

        fun trazar(valor: (Muestra) -> Float, color: Color) {
            val linea = Path()
            val relleno = Path()
            muestras.forEachIndexed { i, m ->
                val x = size.width * i / (muestras.size - 1)
                val y = size.height * (1f - valor(m).coerceAtLeast(0f) / maximo)
                if (i == 0) {
                    linea.moveTo(x, y)
                    relleno.moveTo(x, size.height)
                    relleno.lineTo(x, y)
                } else {
                    linea.lineTo(x, y)
                    relleno.lineTo(x, y)
                }
            }
            relleno.lineTo(size.width, size.height)
            relleno.close()
            drawPath(relleno, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
            drawPath(linea, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        trazar({ it.pv }, Paleta.Sol)
        trazar({ it.casa }, Paleta.Azul)
    }
}

/** Barra que muestra el rango de tension de las celdas entre su minimo y maximo. */
@Composable
fun BarraCeldas(min: Float?, max: Float?, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        drawRoundRect(Color(0x26FFFFFF), size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        if (min == null || max == null) return@Canvas
        // Escala visual 3,0 V .. 3,65 V (rango util de LiFePO4)
        val desde = 3.0f
        val hasta = 3.65f
        val a = ((min - desde) / (hasta - desde)).coerceIn(0f, 1f)
        val b = ((max - desde) / (hasta - desde)).coerceIn(0f, 1f)
        val x0 = size.width * a
        val ancho = (size.width * (b - a)).coerceAtLeast(10.dp.toPx())
        drawRoundRect(
            Paleta.Verde,
            topLeft = Offset(x0, 0f),
            size = Size(ancho, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
        )
    }
}

@Composable
fun FondoDegradado(contenido: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Paleta.FondoArriba, Paleta.FondoAbajo))),
    ) { contenido() }
}
