# 3. El inversor: la API de SmartESS

La batería ya está resuelta. Ahora el inversor: producción de las placas, consumo de la casa y estado de la red.

## 3.1 De dónde salen los datos

El inversor Easun lleva un **dongle wifi** que sube sus datos a la nube de **SmartESS**, que es la marca blanca de la
plataforma **ShineMonitor / DESSMonitor**. Esa plataforma tiene una API web (con usuario y contraseña y peticiones
firmadas) que usa la propia app. No es oficial para terceros: está documentada por proyectos de la comunidad, como
[`davidsmfreire/shinemonitor-api`](https://github.com/davidsmfreire/shinemonitor-api), de los que se ha tomado la firma.

> **Solo lectura.** La API tiene una acción (`ctrlDevice`) que **cambia ajustes del inversor**. En este proyecto nunca se
> llama. Todo lo demás son consultas.

## 3.2 Autenticación y firma

- Servidor: `https://android.shinemonitor.com/public/` (usa siempre `https`).
- El login es con el **nombre de usuario**, no con el correo con el que quizá entras en la app. Si usas el correo la API
  responde `ERR_NOT_FOUND_USR` (error 261).
- Las peticiones llevan una firma SHA-1:

| Petición | Firma |
|---|---|
| Login (`action=auth`) | `SHA1(salt + SHA1(contraseña) + parámetros)` |
| Resto | `SHA1(salt + secret + token + parámetros)` |

`salt` es la hora actual en milisegundos. `secret` y `token` los devuelve el login. Los parámetros se firman tal como
van en la URL, empezando por `&action=…`. El login también lleva una `company-key` y un sufijo que identifican a la app
(los valores por defecto están en `script-pc/smartess_probar.py`, tomados de la librería de la comunidad).

Códigos de error útiles: `0` bien, `16` contraseña incorrecta (`ERR_PASSWORD_VERIF_FAIL`), `261` usuario no encontrado,
`12` sin registros (`ERR_NO_RECORD`).

### Una prueba rápida

`script-pc/smartess_probar.py` hace el login, lista tus dispositivos y pide los últimos datos. Pon tus credenciales en un
`.env` (no lo subas a git):

```
SMARTESS_USER=tu_usuario
SMARTESS_PASS=tu_contrasena
```

y ejecútalo: `python smartess_probar.py`. Guarda ejemplos de respuesta en `docs/` con los identificadores sustituidos.

## 3.3 Las consultas que importan

| Acción | Qué da |
|---|---|
| `queryDevices` | Tus dispositivos: `pn`, `sn`, `devcode`, `devaddr` (hacen falta para todo lo demás) |
| `querySPDeviceLastData` | Lectura actual agrupada: placas, batería, salida, red |
| `queryDeviceDataOneDay&date=AAAA-MM-DD` | **La serie del día**: una fila cada ~5 min con todas las columnas |
| `queryDeviceCtrlField` / `queryDeviceCtrlValue` | **Lee** los ajustes del inversor (solo lectura) |

De la serie del día salen los datos que de verdad se usan: columnas `PV power`, `Output active power` (consumo de la
casa), `Battery power` y `Grid power`. Se localizan **por nombre de columna, no por posición** y **sin distinguir
mayúsculas**: lo vas a necesitar (ver 3.5).

## 3.4 Lo que no te cuentan: limitaciones de la nube

1. **Solo guarda el día en curso.** Para los días anteriores devuelve `ERR_NO_RECORD`, y los totales diarios, mensuales
   y anuales listos para usar (`queryDeviceEnergy*`) tampoco existen para este inversor. Para tener histórico hay que
   guardarlo tú: capítulo 4.
2. **Los datos llegan con retraso.** La nube repite el mismo dato durante minutos: varias consultas seguidas devuelven
   lo mismo. No es «en directo». Si quieres saber cuánto hace que cambió un dato, compara los valores entre consultas
   en lugar de fiarte de su marca de hora (la del datalogger llegó a estar desfasada 7 horas).
3. **El inversor no sabe cuánta batería hay** (cuando no está conectado al BMS por RS485/CAN). Su «SOC» es una
   estimación por voltaje. Con el tipo de batería en *USER* y 53,6 V, dio un 90 % cuando el BMS decía un 58 %. En LiFePO4
   la curva de voltaje es muy plana y ese cálculo siempre exagera. **Usa siempre el SOC del BMS.**
4. Ese SOC falso también afecta a los ajustes del inversor que se expresan en porcentaje (corte de descarga, vuelta a
   red…). Los ajustes por voltaje son los fiables.
5. **El formato puede cambiar.** Durante este montaje, de un día para otro el `devcode` del dispositivo pasó de 2507 a
   2376, los nombres de columna cambiaron de mayúsculas (`PV power` → `PV Power`), desaparecieron los contadores de
   energía y los identificadores de la lectura actual pasaron a ser genéricos (`pv_eybond_read_32`…). No sabemos si fue
   un cambio de la cuenta o de la nube. Moraleja: comparar columnas sin distinguir mayúsculas, no depender de
   posiciones, tolerar columnas que faltan y calcular tú lo que puedas.

## 3.5 Calcular la energía a partir de las potencias

Como no hay contadores fiables, la energía (kWh) se calcula integrando la potencia con la **regla del trapecio** entre
muestras consecutivas. Si entre dos muestras hay un hueco de más de 30 minutos no se integra, para no inventar energía.
Para la batería, se separa la carga (potencia positiva) de la descarga (negativa). Esta es la función, en pseudocódigo:

```
para cada par de muestras consecutivas (a, b):
    horas = (hora_b - hora_a) en horas
    si horas <= 0 o horas > 0.5: seguir
    energia_pv    += (a.pv   + b.pv)   / 2 * horas / 1000
    energia_casa  += (a.casa + b.casa) / 2 * horas / 1000
    p = (a.bateria + b.bateria) / 2 * horas / 1000
    si p > 0: carga += p  si no: descarga += -p
```

Está implementada en PHP en `servidor/core/resumen.php` y en Kotlin en `android/.../DatosDia.kt`.

## 3.6 Qué se ve de un vistazo

Con todo esto ya puedes tener, en un script, la producción y el consumo del momento. Pero sin un sitio donde guardarlo,
al día siguiente se ha perdido. Ese es el siguiente capítulo.

Siguiente: [4. El servidor](04-servidor-colector.md).
