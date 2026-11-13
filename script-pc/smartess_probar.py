"""Fase 3 (inversor): prueba de la API de SmartESS / ShineMonitor desde el PC.

Solo LECTURA: login, lista de dispositivos y ultimo dato en tiempo real.
No se llama nunca a ctrlDevice ni a nada que modifique ajustes del inversor.

Credenciales: archivo script-pc/.env (ignorado por git), con:
    SMARTESS_USER=tu_usuario
    SMARTESS_PASS=tu_contrasena
Opcionales (si el login falla con la configuracion por defecto):
    SMARTESS_COMPANY_KEY=...
    SMARTESS_BASE_URL=https://.../public/

Formulas de firma (de la libreria comunitaria shinemonitor-api, NO oficial):
    login:  sign = SHA1(salt + SHA1(password) + base_action)
    resto:  sign = SHA1(salt + secret + token + base_action)
"""
import hashlib
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

AQUI = Path(__file__).resolve().parent
DOCS = AQUI.parent / "docs"

COMPANY_KEY_DEFECTO = "bnrl_frRFjEz8Mkn"
SUFIJO = ("&i18n=es_ES&lang=es_ES&source=1&_app_client_=android"
          "&_app_id_=wifiapp.volfw.watchpower&_app_version_=1.0.6.3")
BASES_DEFECTO = [
    "https://android.shinemonitor.com/public/",
    "http://android.shinemonitor.com/public/",
]
SECRETOS = {"pn", "sn", "usr", "secret", "token", "devalias", "alias"}


def cargar_env():
    ruta = AQUI / ".env"
    if not ruta.exists():
        sys.exit(f"Falta {ruta}. Crea el archivo con SMARTESS_USER y SMARTESS_PASS.")
    for linea in ruta.read_text(encoding="utf-8").splitlines():
        linea = linea.strip()
        if linea and not linea.startswith("#") and "=" in linea:
            k, v = linea.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


def sha1(texto: str) -> str:
    return hashlib.sha1(texto.encode("utf-8")).hexdigest()


def pedir(url: str) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": "monitor-solar-prueba"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode("utf-8", errors="replace"))


def login(base: str, usuario: str, clave: str, company_key: str):
    salt = str(int(time.time() * 1000))
    usr = usuario.replace(" ", "+")
    accion = f"&action=auth&usr={urllib.parse.quote(usr, safe='+')}&company-key={company_key}{SUFIJO}"
    sign = sha1(salt + sha1(clave) + accion)
    return pedir(f"{base}?sign={sign}&salt={salt}{accion}")


def llamar(base: str, token: str, secret: str, accion_params: str) -> dict:
    salt = str(int(time.time() * 1000))
    accion = f"&action={accion_params}{SUFIJO}"
    sign = sha1(salt + secret + token + accion)
    return pedir(f"{base}?sign={sign}&salt={salt}&token={token}{accion}")


def limpiar(obj):
    """Copia del JSON sin identificadores personales (pn, sn, token...)."""
    if isinstance(obj, dict):
        return {k: ("XXXX" if k.lower() in SECRETOS else limpiar(v)) for k, v in obj.items()}
    if isinstance(obj, list):
        return [limpiar(x) for x in obj]
    return obj


def main():
    cargar_env()
    usuario, clave = os.environ.get("SMARTESS_USER"), os.environ.get("SMARTESS_PASS")
    if not usuario or not clave:
        sys.exit("Faltan SMARTESS_USER / SMARTESS_PASS en .env")
    company_key = os.environ.get("SMARTESS_COMPANY_KEY", COMPANY_KEY_DEFECTO)
    bases = [os.environ["SMARTESS_BASE_URL"]] if os.environ.get("SMARTESS_BASE_URL") else BASES_DEFECTO

    sesion = None
    for base in bases:
        print(f"Login en {base} ...")
        try:
            r = login(base, usuario, clave, company_key)
        except Exception as e:
            print("  error de red:", e)
            continue
        print("  respuesta: err =", r.get("err"), "desc =", r.get("desc"))
        if r.get("err") == 0:
            d = r["dat"]
            sesion = (base, d["token"], d["secret"])
            print("  login OK")
            break
    if not sesion:
        sys.exit("No se pudo iniciar sesion. Revisa usuario/contrasena o prueba otra company-key/base.")

    base, token, secret = sesion

    print("\n== Dispositivos ==")
    r = llamar(base, token, secret, "queryDevices&page=0&pagesize=50")
    print("err =", r.get("err"), "desc =", r.get("desc"))
    dispositivos = (r.get("dat") or {}).get("device", [])
    print("encontrados:", len(dispositivos))
    for d in dispositivos:
        print("  campos:", sorted(d.keys()))
    DOCS.mkdir(exist_ok=True)
    (DOCS / "smartess_dispositivos.json").write_text(
        json.dumps(limpiar(r), indent=2, ensure_ascii=False), encoding="utf-8")

    for i, d in enumerate(dispositivos):
        pn, sn = d.get("pn"), d.get("sn")
        devcode, devaddr = d.get("devcode"), d.get("devaddr")
        print(f"\n== Dispositivo {i + 1}: ultimo dato ==")
        for accion in ("querySPDeviceLastData", "queryDeviceLastData"):
            q = f"{accion}&pn={pn}&devcode={devcode}&devaddr={devaddr}&sn={sn}"
            try:
                rr = llamar(base, token, secret, q)
            except Exception as e:
                print(accion, "-> error:", e)
                continue
            print(accion, "-> err =", rr.get("err"), "desc =", rr.get("desc"))
            (DOCS / f"smartess_{accion}_{i + 1}.json").write_text(
                json.dumps(limpiar(rr), indent=2, ensure_ascii=False), encoding="utf-8")
    print("\nJSON de ejemplo (sin identificadores) guardados en", DOCS)


if __name__ == "__main__":
    main()
