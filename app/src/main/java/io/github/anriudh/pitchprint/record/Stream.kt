package io.github.anriudh.pitchprint.record

/** One file per stream inside a session folder. Format version 1, see docs/session-format.md. */
enum class Stream(val fileName: String, val header: String?) {
    EVENTS("events.jsonl", null),
    FIX(
        "fix.csv",
        "t_ns,rx_ns,utc_ms,lat,lon,alt_m,msl_alt_m,h_acc_m,v_acc_m,speed_mps,speed_acc_mps," +
            "bearing_deg,bearing_acc_deg,t_unc_ns,mock",
    ),
    STATUS(
        "status.csv",
        "t_ns,epoch,constellation,svid,cn0_dbhz,baseband_cn0_dbhz,elev_deg,azim_deg,used," +
            "has_almanac,has_ephemeris,carrier_hz",
    ),
    // Column names follow GnssLogger's Raw records so existing GNSS tools can read them.
    RAW(
        "raw.csv",
        "t_ns,rx_ns,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos,BiasNanos," +
            "BiasUncertaintyNanos,DriftNanosPerSecond,DriftUncertaintyNanosPerSecond," +
            "HardwareClockDiscontinuityCount,Svid,TimeOffsetNanos,State,ReceivedSvTimeNanos," +
            "ReceivedSvTimeUncertaintyNanos,Cn0DbHz,PseudorangeRateMetersPerSecond," +
            "PseudorangeRateUncertaintyMetersPerSecond,AccumulatedDeltaRangeState," +
            "AccumulatedDeltaRangeMeters,AccumulatedDeltaRangeUncertaintyMeters,CarrierFrequencyHz," +
            "MultipathIndicator,SnrInDb,ConstellationType,AgcDb,BasebandCn0DbHz," +
            "FullInterSignalBiasNanos,FullInterSignalBiasUncertaintyNanos," +
            "SatelliteInterSignalBiasNanos,SatelliteInterSignalBiasUncertaintyNanos,CodeType," +
            "ChipsetElapsedRealtimeNanos,ChipsetElapsedRealtimeUncertaintyNanos,IsFullTracking",
    ),
    NAV("nav.csv", "t_ns,svid,type,status,message_id,submessage_id,data_hex"),
    NMEA("nmea.csv", "t_ns,utc_ms,sentence"),
    ACCEL("accel.csv", "t_ns,x,y,z"),
    GYRO("gyro.csv", "t_ns,x,y,z"),
    MAG("mag.csv", "t_ns,x,y,z"),
}
