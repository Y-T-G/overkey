# Entry point of the root/adb-side injector, invoked by name from app_process.
-keep class dev.noblebits.overkey.Injector { public static void main(java.lang.String[]); }

# Loaded by name inside the Shizuku user-service process.
-keep class dev.noblebits.overkey.ShizukuInjector { <init>(); }

# Smaller output: let R8 widen access for inlining and merging, and drop package names.
-allowaccessmodification
-repackageclasses ''
