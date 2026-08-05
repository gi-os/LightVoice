# Keep rules for the release build, which runs R8 in full mode (see gradle.properties).
#
# Every rule below names the mechanism that reaches the thing being kept. There is deliberately
# no blanket `-keep class com.gios.lightvoice.**`: the point of turning minification on is that
# the ~90% of the app reached only by ordinary calls gets shrunk and renamed, and a package-wide
# keep would leave nothing to shrink.
#
# The one rule of full mode worth remembering while reading this file: a `-keep` on a class no
# longer implies keeping its members. Anything the framework constructs or calls has to say so.

# ---------------------------------------------------------------- manifest components
#
# Every class named as a string in AndroidManifest.xml is instantiated by the framework and
# called by nothing in this app, so full mode is free to remove or rename all of it. aapt2
# generates keep rules from the manifest already; these are written out anyway because the
# generated ones are only as good as the manifest parse, and because losing any one of them
# fails at runtime rather than at build time — a silent alarm, a dead push-to-talk key.

# The push-to-talk accessibility service. This is the class the whole global-mic feature is:
# bound by the system from the manifest entry, and its `onKeyEvent` is called by the framework,
# never from here. Members kept explicitly for the full-mode rule above.
-keep class com.gios.lightvoice.ptt.PttService { *; }

# The alarm stack. AlarmReceiver is named in a PendingIntent as well as in the manifest, and a
# PendingIntent stores the component name as a string that survives across reboots and across
# app upgrades — so a rename would silently orphan every alarm already scheduled on the phone.
-keep class com.gios.lightvoice.act.AlarmReceiver { <init>(); *; }

# Started on BOOT_COMPLETED and MY_PACKAGE_REPLACED, which is what re-arms the alarms after a
# reboot. Nothing in the app ever refers to it.
-keep class com.gios.lightvoice.act.BootReceiver { <init>(); *; }

# The ring foreground service and the two full-screen activities, all started by component name
# from the alarm path and from the accessibility service.
-keep class com.gios.lightvoice.act.RingService { <init>(); *; }
-keep class com.gios.lightvoice.act.RingActivity { <init>(); }
-keep class com.gios.lightvoice.ListenActivity { <init>(); }
-keep class com.gios.lightvoice.MainActivity { <init>(); }

# The LightSync provider. light-common's consumer rules already keep any subclass of
# LightSyncBackup, so nothing more is needed here — noted so nobody adds a duplicate.

# ---------------------------------------------------------------- component-name comparison
#
# `Grants.pttServiceEnabled` builds a ComponentName from PttService::class.java and compares it
# against what the platform wrote into Settings.Secure. That string was written by a different
# process, at a different time, from the *unobfuscated* name — so the class must keep its real
# name for the comparison to mean anything. Covered by the `-keep` on PttService above; stated
# here because it is a second, non-obvious reason that rule cannot be relaxed.

# ---------------------------------------------------------------- JSON by field name
#
# Alarms, notes and the BlueBubbles contact list are stored as JSON in SharedPreferences and
# parsed with org.json, which is key-by-string and reflection-free: the keys are string literals
# in Alarm.toJson/fromJson, so R8 renaming the fields around them changes nothing. No rule
# needed, and none should be added — written down because "JSON" usually does need one.

# ---------------------------------------------------------------- OkHttp / zxing
#
# OkHttp names an optional Conscrypt/BouncyCastle provider that is not on the classpath, and
# zxing's Android embedding references a couple of javax.annotation types the same way. Both are
# warnings about code that is never reached on this phone.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**

# ---------------------------------------------------------------- crash reports
#
# Shake-to-report puts a stack trace in the issue body. Without these the trace is a wall of
# `a.a.a` and the report is worth nothing, which is the whole feature. Costs a few KB.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
