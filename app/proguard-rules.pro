# The root helper is started by name with `app_process` (see HelperScreen.open), never referenced
# from code, so R8 would strip it and every release session would fall back to the slow dump reader.
-keep class com.chalaiden.app.helper.UiHelper {
    public static void main(java.lang.String[]);
}
