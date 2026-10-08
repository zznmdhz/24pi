-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }

# AMap official JNI keep rules. Do not remove: v0.16 native FindClass incident.
-keep class com.amap.api.maps.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.amap.api.trace.** { *; }
-keep class com.amap.api.location.** { *; }
-keep class com.amap.api.fence.** { *; }
-keep class com.amap.api.services.** { *; }
-keep class com.loc.** { *; }
# Optional RTK locator absent from the vendor bundle, referenced only by its unused
# location client. We never create that client; current position uses system fixes.
# Exact missing class from R8 missing_rules.txt, not a blanket warning suppression.
-dontwarn com.amap.ams.gnss.GnssSoftLocator
