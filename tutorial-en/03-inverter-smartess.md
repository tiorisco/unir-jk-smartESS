# 3. The inverter: the SmartESS API

The battery is solved. Now the inverter: solar production, house consumption and grid state.

## 3.1 Where the data comes from

The Easun inverter has a **wifi dongle** that uploads its data to the **SmartESS** cloud, which is the white-label of the
**ShineMonitor / DESSMonitor** platform. That platform has a web API (with user and password and signed requests) that the
app itself uses. It is not official for third parties: it is documented by community projects such as
[`davidsmfreire/shinemonitor-api`](https://github.com/davidsmfreire/shinemonitor-api), from which the signature was taken.

> **Read-only.** The API has an action (`ctrlDevice`) that **changes inverter settings**. This project never calls it.
> Everything else is queries.

## 3.2 Authentication and signature

- Server: `https://android.shinemonitor.com/public/` (always use `https`).
- Login is with your **username**, not the e-mail you may use in the app. With the e-mail the API answers
  `ERR_NOT_FOUND_USR` (error 261).
- Requests carry a SHA-1 signature:

| Request | Signature |
|---|---|
| Login (`action=auth`) | `SHA1(salt + SHA1(password) + parameters)` |
| Everything else | `SHA1(salt + secret + token + parameters)` |

`salt` is the current time in milliseconds. `secret` and `token` are returned by the login. The parameters are signed as
they appear in the URL, starting with `&action=…`. The login also carries a `company-key` and a suffix that identify the app
(the default values are in `script-pc/smartess_probar.py`, taken from the community library).

Useful error codes: `0` OK, `16` wrong password (`ERR_PASSWORD_VERIF_FAIL`), `261` user not found, `12` no records
(`ERR_NO_RECORD`).

### A quick test

`script-pc/smartess_probar.py` logs in, lists your devices and asks for the latest data. Put your credentials in a `.env`
(do not commit it):

```
SMARTESS_USER=your_username
SMARTESS_PASS=your_password
```

and run it: `python smartess_probar.py`. It saves sample responses in `docs/` with identifiers replaced.

## 3.3 The queries that matter

| Action | What it gives |
|---|---|
| `queryDevices` | Your devices: `pn`, `sn`, `devcode`, `devaddr` (needed for everything else) |
| `querySPDeviceLastData` | Current grouped reading: PV, battery, output, grid |
| `queryDeviceDataOneDay&date=YYYY-MM-DD` | **The day's series**: a row every ~5 min with all columns |
| `queryDeviceCtrlField` / `queryDeviceCtrlValue` | **Reads** the inverter settings (read-only) |

The data that is really used comes from the day's series: columns `PV power`, `Output active power` (house consumption),
`Battery power` and `Grid power`. They are located **by column name, not by position** and **case-insensitively**: you
will need that (see 3.5).

## 3.4 What nobody tells you: limits of the cloud

1. **It only keeps the current day.** For earlier days it returns `ERR_NO_RECORD`, and the ready-made daily, monthly and
   yearly totals (`queryDeviceEnergy*`) do not exist for this inverter either. To have history you must store it yourself:
   chapter 4.
2. **The data arrives late.** The cloud repeats the same value for minutes: several consecutive queries return the same
   thing. It is not "live". If you want to know how long ago a value changed, compare values between queries instead of
   trusting its timestamp (the datalogger's clock was once off by 7 hours).
3. **The inverter does not know how much battery there is** (when it is not connected to the BMS over RS485/CAN). Its
   "SOC" is a voltage-based estimate. With battery type *USER* and 53.6 V it showed 90 % while the BMS said 58 %. On
   LiFePO4 the voltage curve is very flat and that calculation always overstates. **Always use the BMS SOC.**
4. That false SOC also affects the inverter settings expressed as percentages (discharge cut-off, return to grid…).
   Voltage-based settings are the reliable ones.
5. **The format can change.** During this build, from one day to the next the device's `devcode` went from 2507 to 2376,
   column names changed capitalisation (`PV power` → `PV Power`), the energy counters disappeared and the identifiers of
   the current reading became generic (`pv_eybond_read_32`…). We do not know whether it was an account change or a cloud
   change. Moral: compare columns case-insensitively, do not depend on positions, tolerate missing columns and compute
   what you can yourself.

## 3.5 Computing energy from the powers

With no reliable counters, energy (kWh) is computed by integrating power with the **trapezoid rule** between consecutive
samples. If there is a gap of more than 30 minutes between two samples it is not integrated, so as not to invent energy.
For the battery, charge (positive power) is separated from discharge (negative). In pseudocode:

```
for each pair of consecutive samples (a, b):
    hours = (time_b - time_a) in hours
    if hours <= 0 or hours > 0.5: continue
    pv_energy    += (a.pv    + b.pv)    / 2 * hours / 1000
    house_energy += (a.house + b.house) / 2 * hours / 1000
    p = (a.battery + b.battery) / 2 * hours / 1000
    if p > 0: charge += p  else: discharge += -p
```

It is implemented in PHP in `servidor/core/resumen.php` and in Kotlin in `android/.../DatosDia.kt`.

## 3.6 What you can see at a glance

With all this you can already have, in a script, the current production and consumption. But with nowhere to store it,
it is lost the next day. That is the next chapter.

Next: [4. The server](04-server-collector.md).
