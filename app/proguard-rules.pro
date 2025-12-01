# ProGuard rules for AirPlayDLNA

# Keep JUpnP library classes
-keep class org.jupnp.** { *; }

# Keep model classes
-keep class com.airplay.dlna.model.** { *; }

# Keep service classes
-keep class com.airplay.dlna.service.** { *; }
