# The root helper is started by name with `app_process` (see HelperScreen.open), never referenced
# from code, so R8 would strip it and every release session would fall back to the slow dump reader.
-keep class com.chalaiden.app.helper.UiHelper {
    public static void main(java.lang.String[]);
}

# Shizuku's process launcher is private in API 13 and reached by reflection (see Shizuku.kt).
-keepclassmembers class rikka.shizuku.Shizuku {
    private static rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
