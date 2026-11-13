"""Fase 1: lectura de prueba del BMS JK por Bluetooth (solo lectura).

Uso:
    python leer_bms.py                 # busca un dispositivo cuyo nombre empiece por "JK"
    python leer_bms.py AA:BB:CC:DD:EE:FF   # usa una direccion concreta
    python leer_bms.py --tramas 5      # numero de tramas de celdas a mostrar

IMPORTANTE: cierra la app oficial del JK en el movil (el BMS solo admite
una conexion Bluetooth a la vez).

Solo se envian los comandos 0x97 (info) y 0x96 (celdas). Nada de escritura.
"""
import argparse
import asyncio
import struct
from pathlib import Path

from bleak import BleakClient, BleakScanner

CHAR_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
CABECERA_RX = bytes([0x55, 0xAA, 0xEB, 0x90])
TAM_TRAMA = 300
DOCS = Path(__file__).resolve().parent.parent / "docs"


def comando(cmd: int) -> bytes:
    """Trama de 20 bytes: AA 55 90 EB, cmd, longitud 0, ceros, checksum."""
    t = bytearray(20)
    t[0:4] = bytes([0xAA, 0x55, 0x90, 0xEB])
    t[4] = cmd
    t[19] = sum(t[:19]) & 0xFF
    return bytes(t)


def decodificar(t: bytes, d: int):
    """Decodifica una trama de celdas con desplazamiento d (0 = 24S, 32 = 32S)."""
    celdas = [struct.unpack_from("<H", t, 6 + 2 * i)[0] / 1000 for i in range(32)]
    celdas = [c for c in celdas if c > 0]
    return {
        "celdas": celdas,
        "v_total": struct.unpack_from("<I", t, 118 + d)[0] / 1000,
        "corriente": struct.unpack_from("<i", t, 126 + d)[0] / 1000,
        "temp1": struct.unpack_from("<h", t, 130 + d)[0] / 10,
        "temp2": struct.unpack_from("<h", t, 132 + d)[0] / 10,
        "temp_mos": struct.unpack_from("<h", t, 134 if d == 0 else 112 + d)[0] / 10,
        "soc": t[141 + d],
        "cap_rest": struct.unpack_from("<I", t, 142 + d)[0] / 1000,
        "cap_total": struct.unpack_from("<I", t, 146 + d)[0] / 1000,
        "ciclos": struct.unpack_from("<I", t, 150 + d)[0],
        "soh": t[158 + d],
        "carga_on": t[166 + d],
        "descarga_on": t[167 + d],
    }


def elegir_variante(t: bytes):
    """Prueba D=0 y D=32; se queda con la que cuadre (suma celdas ~ total, SOC 0-100)."""
    mejor = None
    for nombre, d in (("JK02_24S", 0), ("JK02_32S", 32)):
        r = decodificar(t, d)
        dif = abs(sum(r["celdas"]) - r["v_total"])
        valida = 0 <= r["soc"] <= 100 and dif < 1.0 and r["celdas"]
        print(f"  [{nombre}] suma celdas={sum(r['celdas']):.3f} V, total={r['v_total']:.3f} V, "
              f"SOC={r['soc']}%  -> {'OK' if valida else 'no cuadra'}")
        if valida and mejor is None:
            mejor = (nombre, r)
    return mejor


def texto(t: bytes, pos: int) -> str:
    return t[pos:pos + 8].split(b"\x00")[0].decode("ascii", errors="replace")


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("direccion", nargs="?")
    ap.add_argument("--tramas", type=int, default=3)
    args = ap.parse_args()

    direccion = args.direccion
    if not direccion:
        print("Buscando BMS (8 s)...")
        encontrados = await BleakScanner.discover(timeout=8.0)
        for d in encontrados:
            print(f"  {d.address}  {d.name}")
        jk = [d for d in encontrados if d.name and d.name.upper().startswith("JK")]
        if not jk:
            print("No se ha visto ningun dispositivo 'JK...'. Cierra la app del movil y reintenta.")
            return
        direccion = jk[0].address
        print(f"Usando {jk[0].name} ({direccion})")

    buf = bytearray()
    cola: asyncio.Queue = asyncio.Queue()

    def al_recibir(_, datos: bytearray):
        nonlocal buf
        if bytes(datos[:4]) == CABECERA_RX:
            buf = bytearray()
        buf.extend(datos)
        if len(buf) >= TAM_TRAMA:
            trama = bytes(buf[:TAM_TRAMA])
            buf = bytearray()
            if sum(trama[:299]) & 0xFF == trama[299]:
                cola.put_nowait(trama)
            else:
                print("  (trama con checksum incorrecto, descartada)")

    async with BleakClient(direccion) as cli:
        print("Conectado.")
        await cli.start_notify(CHAR_UUID, al_recibir)
        await cli.write_gatt_char(CHAR_UUID, comando(0x97), response=False)
        await asyncio.sleep(0.5)
        await cli.write_gatt_char(CHAR_UUID, comando(0x96), response=False)

        vistas = 0
        guardada = False
        while vistas < args.tramas:
            trama = await asyncio.wait_for(cola.get(), timeout=15)
            tipo = trama[4]
            if tipo == 0x03:
                print(f"Hardware: {texto(trama, 22)}   Software: {texto(trama, 30)}")
            elif tipo == 0x02:
                vistas += 1
                print(f"\n--- Trama de celdas {vistas} ---")
                res = elegir_variante(trama)
                if not res:
                    print("Ninguna variante cuadra. Revisa docs/trama_ejemplo.hex.")
                    r = None
                else:
                    nombre, r = res
                    print(f"Variante: {nombre}")
                if not guardada:
                    DOCS.mkdir(exist_ok=True)
                    (DOCS / "trama_ejemplo.hex").write_text(trama.hex() + "\n")
                    guardada = True
                    print(f"(trama guardada en {DOCS / 'trama_ejemplo.hex'})")
                if r:
                    pot = r["v_total"] * r["corriente"]
                    print(f"SOC: {r['soc']} %   SOH: {r['soh']} %")
                    print(f"Voltaje: {r['v_total']:.3f} V   Corriente: {r['corriente']:.3f} A "
                          f"(signo por confirmar)   Potencia: {pot:.0f} W")
                    print(f"Capacidad: {r['cap_rest']:.1f} / {r['cap_total']:.1f} Ah   Ciclos: {r['ciclos']}")
                    print(f"Temps: T1={r['temp1']} T2={r['temp2']} MOS={r['temp_mos']} C")
                    print(f"Carga: {'ON' if r['carga_on'] else 'OFF'}   Descarga: {'ON' if r['descarga_on'] else 'OFF'}")
                    print(f"Celdas ({len(r['celdas'])}): " + " ".join(f"{c:.3f}" for c in r["celdas"]))
        await cli.stop_notify(CHAR_UUID)


if __name__ == "__main__":
    asyncio.run(main())
