<?php
declare(strict_types=1);

require_once __DIR__ . '/config.php';

/**
 * Cliente SOLO LECTURA de la nube de SmartESS (ShineMonitor/DESSMonitor). API no oficial.
 *   login: sign = SHA1(salt + SHA1(clave) + params)
 *   resto: sign = SHA1(salt + secret + token + params)
 * Nunca se llama a ctrlDevice ni a nada que cambie ajustes del inversor.
 */
class Smartess
{
    private const BASE = 'https://android.shinemonitor.com/public/';
    private const COMPANY_KEY = 'bnrl_frRFjEz8Mkn';
    private const SUFIJO = '&i18n=es_ES&lang=es_ES&source=1&_app_client_=android'
        . '&_app_id_=wifiapp.volfw.watchpower&_app_version_=1.0.6.3';

    private ?string $token = null;
    private ?string $secret = null;
    private ?array $dispositivo = null;

    public function __construct(private string $usuario, private string $clave)
    {
    }

    private static function sal(): string
    {
        return (string) (int) round(microtime(true) * 1000);
    }

    private function http(string $url): array
    {
        if (function_exists('curl_init')) {
            $c = curl_init($url);
            curl_setopt_array($c, [
                CURLOPT_RETURNTRANSFER => true,
                CURLOPT_TIMEOUT => 25,
                CURLOPT_CONNECTTIMEOUT => 10,
                CURLOPT_USERAGENT => 'unir-jk-smartess-colector',
            ]);
            $texto = curl_exec($c);
            $err = curl_error($c);
            curl_close($c);
            if ($texto === false) {
                throw new RuntimeException("SmartESS: error de red ($err)");
            }
        } else {
            $ctx = stream_context_create(['http' => ['timeout' => 25, 'ignore_errors' => true]]);
            $texto = @file_get_contents($url, false, $ctx);
            if ($texto === false) {
                throw new RuntimeException('SmartESS: error de red');
            }
        }
        $json = json_decode($texto, true);
        if (!is_array($json)) {
            throw new RuntimeException('SmartESS: respuesta no valida');
        }
        return $json;
    }

    private function iniciarSesion(): void
    {
        $salt = self::sal();
        $usr = str_replace('%2B', '+', rawurlencode(str_replace(' ', '+', $this->usuario)));
        $accion = "&action=auth&usr=$usr&company-key=" . self::COMPANY_KEY . self::SUFIJO;
        $sign = sha1($salt . sha1($this->clave) . $accion);
        $r = $this->http(self::BASE . "?sign=$sign&salt=$salt$accion");
        if (($r['err'] ?? -1) !== 0) {
            throw new RuntimeException('Login SmartESS: ' . ($r['desc'] ?? 'error'));
        }
        $this->token = (string) $r['dat']['token'];
        $this->secret = (string) $r['dat']['secret'];
    }

    /** Devuelve el JSON de la respuesta; $err == 12 (sin registros) se devuelve tal cual. */
    private function llamar(string $params): array
    {
        if ($this->token === null) {
            $this->iniciarSesion();
        }
        $salt = self::sal();
        $accion = "&action=$params" . self::SUFIJO;
        $sign = sha1($salt . $this->secret . $this->token . $accion);
        $r = $this->http(self::BASE . "?sign=$sign&salt=$salt&token={$this->token}$accion");
        $err = $r['err'] ?? -1;
        if ($err !== 0 && $err !== 12) {
            throw new RuntimeException('SmartESS: ' . ($r['desc'] ?? "error $err"));
        }
        return $r;
    }

    private function buscarDispositivo(): array
    {
        if ($this->dispositivo === null) {
            $r = $this->llamar('queryDevices&page=0&pagesize=50');
            $lista = $r['dat']['device'] ?? [];
            if (!$lista) {
                throw new RuntimeException('SmartESS: no hay dispositivos en la cuenta');
            }
            $d = $lista[0];
            $this->dispositivo = [
                'pn' => (string) $d['pn'],
                'sn' => (string) $d['sn'],
                'devcode' => (string) $d['devcode'],
                'devaddr' => (string) $d['devaddr'],
            ];
        }
        return $this->dispositivo;
    }

    /**
     * Contadores de produccion del inversor (kWh de hoy y totales). La serie del dia dejo
     * de incluirlos el 13-11-2026, pero queryDeviceParsEs si los da. Null si no estan.
     */
    public function contadores(): array
    {
        $d = $this->buscarDispositivo();
        $r = $this->llamar("queryDeviceParsEs&pn={$d['pn']}&devcode={$d['devcode']}&devaddr={$d['devaddr']}&sn={$d['sn']}");
        $out = ['dia' => null, 'total' => null];
        foreach ($r['dat']['parameter'] ?? [] as $p) {
            $v = $p['val'] ?? null;
            if (!is_numeric($v)) {
                continue;
            }
            if (($p['par'] ?? '') === 'energy_today') {
                $out['dia'] = (float) $v;
            } elseif (($p['par'] ?? '') === 'energy_total') {
                $out['total'] = (float) $v;
            }
        }
        return $out;
    }

    /**
     * Serie del dia (una lectura cada ~5 min), hora local.
     * Cada punto: hora (Y-m-d H:i:s), pv_w, casa_w, bateria_w, red_w, energia_pv_dia, energia_pv_total.
     */
    public function serieDelDia(string $fecha): array
    {
        $d = $this->buscarDispositivo();
        $q = "queryDeviceDataOneDay&pn={$d['pn']}&devcode={$d['devcode']}&devaddr={$d['devaddr']}&sn={$d['sn']}&date=$fecha";
        try {
            $r = $this->llamar($q);
        } catch (RuntimeException $e) {
            // Token caducado: se entra de nuevo una vez
            $this->token = null;
            $this->secret = null;
            $r = $this->llamar($q);
        }
        if (($r['err'] ?? -1) === 12) {
            return [];
        }
        // Sin distinguir mayusculas: SmartESS cambio "PV power" por "PV Power" (13-11-2026).
        // Si un nombre sale repetido, vale el primero (array_search).
        $titulos = array_map(static fn($t) => strtolower($t['title'] ?? ''), $r['dat']['title'] ?? []);
        $col = static fn(string $n) => array_search(strtolower($n), $titulos, true);
        $iHora = $col('Marca De Tiempo');
        $iPv = $col('PV power');
        $iCasa = $col('Output active power');
        $iBat = $col('Battery power');
        $iRed = $col('Grid power');
        $iDia = $col('Daily PV energy generation');
        $iTotal = $col('Total PV energy generation');
        if ($iHora === false || $iPv === false || $iCasa === false || $iBat === false) {
            throw new RuntimeException('SmartESS ha cambiado el formato de la serie del dia');
        }
        $num = static fn($v) => is_numeric($v) ? (float) $v : null;

        $puntos = [];
        foreach ($r['dat']['row'] ?? [] as $fila) {
            $f = $fila['field'] ?? [];
            $hora = $f[$iHora] ?? '';
            if (!preg_match('/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/', $hora)) {
                continue;
            }
            $puntos[] = [
                'hora' => $hora,
                'pv_w' => $num($f[$iPv] ?? null) ?? 0.0,
                'casa_w' => $num($f[$iCasa] ?? null) ?? 0.0,
                'bateria_w' => $num($f[$iBat] ?? null) ?? 0.0,
                'red_w' => $iRed === false ? 0.0 : ($num($f[$iRed] ?? null) ?? 0.0),
                'energia_pv_dia' => $iDia === false ? null : $num($f[$iDia] ?? null),
                'energia_pv_total' => $iTotal === false ? null : $num($f[$iTotal] ?? null),
            ];
        }
        usort($puntos, static fn($a, $b) => strcmp($a['hora'], $b['hora']));
        return $puntos;
    }
}
