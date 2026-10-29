# Session format, version 1

Each recording is one folder named after its start time plus a random suffix, for example `2026-11-01_0912_ab12`. Export zips the folder as `pitchprint_<folder>.zip`, with the files inside a directory of the same name. `tools/check_session.py` reads either the folder or the zip.

Files are append-only and written while recording. A crash can leave a half-written last line. The app cuts it off before appending again (and logs a `repaired` event), but a reader should still drop a final line that has no newline.

## Clock and numbers

Every row starts with `t_ns`, elapsed realtime in nanoseconds: the phone's monotonic clock since boot, the same one for every file. Where Android gives a measurement time (location, sensor and GNSS clock timestamps) `t_ns` is that time, otherwise it is the time the app received the data. Elapsed realtime restarts at zero after a reboot, so each recorder run writes a `run_start` event with the boot count and a wall-clock time, and the clock is anchored per boot from those.

Timestamps are integers. Other numbers are written in their shortest exact form, so they read back to the identical value, with `.` as the decimal point whatever the phone's language. An empty field means the phone did not provide that value. Booleans in CSV files are `1` or `0`.

## Files

`meta.json` is written once at the start: format version, session name, start wall time, boot count, time zone, the requested sensor rates, and a device snapshot (phone model, Android version, app version, GNSS hardware and capabilities, every sensor).

`events.jsonl` holds one JSON object per line, described below.

`fix.csv` holds GNSS locations from the GPS provider (never the fused provider), one row per fix:
`t_ns, rx_ns, utc_ms, lat, lon, alt_m, msl_alt_m, h_acc_m, v_acc_m, speed_mps, speed_acc_mps, bearing_deg, bearing_acc_deg, t_unc_ns, mock`. `rx_ns` is when the app received the fix.

`status.csv` holds satellite status, one row per satellite per status callback:
`t_ns, epoch, constellation, svid, cn0_dbhz, baseband_cn0_dbhz, elev_deg, azim_deg, used, has_almanac, has_ephemeris, carrier_hz`. Rows from the same callback share `epoch`. `constellation` is Android's `GnssStatus` constellation type.

`raw.csv` holds raw GNSS measurements, one row per signal per epoch. After `t_ns` (the chipset's elapsed-realtime stamp when present) and `rx_ns`, the columns follow GnssLogger's Raw records, so existing GNSS tools can read them: the receiver clock fields (`TimeNanos` to `HardwareClockDiscontinuityCount`) repeated on every row, then the per-signal fields from `Svid` to `CodeType`, then `ChipsetElapsedRealtimeNanos`, `ChipsetElapsedRealtimeUncertaintyNanos` and `IsFullTracking`. GnssLogger's `utcTimeMillis` and the deprecated carrier phase fields are left out. `BiasNanos` and `TimeOffsetNanos` are sub-nanosecond doubles.

`nav.csv` holds navigation messages: `t_ns, svid, type, status, message_id, submessage_id, data_hex`. The constellation is part of `type`.

`nmea.csv` holds NMEA sentences: `t_ns, utc_ms, sentence`. The sentence is quoted because it contains commas.

`accel.csv`, `gyro.csv` and `mag.csv` hold the motion sensors: `t_ns, x, y, z` in m/s², rad/s and µT. Accelerometer and gyroscope are requested at 200 Hz, the magnetometer at its fastest rate.

`crash.txt` only exists if the app crashed, and holds the stack traces.

Files for data the phone never delivered (for example `raw.csv` indoors) are not created.

## Events

Every event has `t_ns`, `wall_ms` (wall clock, milliseconds since 1970) and `type`. The other fields depend on the type.

Session and runs:

| type | fields |
|---|---|
| `session_start` | |
| `run_start` | `reason` (`new`, `process_restart`, `user_resume`), `boot_count` |
| `stop` | `reason` (`user`, `finished_after_interruption`) |
| `service_destroyed` | the service went away without a stop; the session can be resumed |
| `crash` | `thread`, `error` |
| `repaired` | `stream`, `bytes` cut off a half-written last line |

Labels:

| type | fields |
|---|---|
| `split_start` | `n` (1, 2, ...), `layout` (`full_half`, `half_no_box`, `box_only`, `box_to_box`, `custom`), `half` (`N` or `S` for the three half-based layouts, else null), `position` (`GK`, `DEF`, `MID`, `FWD`, `ANY`), `attack` (`N`, `S`, `E`, `W`), `carry` (`hand`, `pocket`), `note` (custom splits only, may be null) |
| `split_end` | `n`, `reason` |
| `pause`, `resume` | `resume` has a `reason` |
| `corner_start` | `point` (`NW`, `NE`, `SE`, `SW`: corners of the full painted pitch; `HE`, `HW`: east and west ends of the halfway line), `hold_s` |
| `corner_end` | `point`; written when the hold time has passed |
| `corner_cancel` | `point`, `reason` |
| `test_start` | `test` (`standing_still`, `line_walk`, `flying_sprint`, `shuttle`, `scripted`), `carry`, `note` |
| `test_end` | `test`, `reason` |
| `marker` | `key` (`up`, `down`), `screen_on`: one per volume button press; holding a button gives one marker |
| `key` | every raw volume key callback: `source` `activity` with `key`, `action` (`down`, `up`) and `repeat`, or `source` `media` with `dir` (1 up, -1 down, 0 release) |

End and cancel reasons are `user`, `stop` (closed by stopping the session), `interrupted` (a corner hold cut short by a crash) or `finished_after_interruption`. A pause, split or test that is open when the app crashes stays open in the resumed run.

Health:

| type | fields |
|---|---|
| `status` | once a minute: `battery_pct`, `plugged`, `temp_c`, `power_save`, delivered rates `fix_hz`, `raw_hz`, `accel_hz`, `gyro_hz`, `mag_hz`, writer `queue`, `max_writer_stall_ms`, `bytes`, `writer_error` |
| `clock` | once a minute: `boot_count`, `sensor_offset_ns` (receipt minus timestamp of each sensor's latest sample) |
| `screen_on`, `screen_off` | |
| `gnss_registered` | which GNSS callbacks registered: `status`, `nav`, `nmea`, `measurements` |
| `gnss_started`, `gnss_stopped`, `gnss_first_fix` (`ttff_ms`) | |
| `gnss_measurement_status`, `gnss_nav_status` | `status` as reported by Android |
| `provider_enabled`, `provider_disabled` | `provider` |
| `sensor_accuracy` | `stream`, `accuracy` |
| `gnss_error`, `sensor_error` | what failed to register |
