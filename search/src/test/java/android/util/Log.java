package android.util;

/** JVM-only logging shim: provider integration tests exercise HTTP, not Android logging. */
public final class Log {
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    private Log() {}

    // OkHttp's AndroidLog probes these even when the actual platform is a JVM.
    public static boolean isLoggable(String tag, int priority) { return false; }
    public static int println(int priority, String tag, String message) { return 0; }
    public static String getStackTraceString(Throwable error) {
        if (error == null) return "";
        java.io.StringWriter text = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(text));
        return text.toString();
    }

    public static int v(String tag, String message) { return 0; }
    public static int v(String tag, String message, Throwable error) { return 0; }
    public static int d(String tag, String message) { return 0; }
    public static int d(String tag, String message, Throwable error) { return 0; }
    public static int i(String tag, String message) { return 0; }
    public static int i(String tag, String message, Throwable error) { return 0; }
    public static int w(String tag, String message) { return 0; }
    public static int w(String tag, String message, Throwable error) { return 0; }
    public static int w(String tag, Throwable error) { return 0; }
    public static int e(String tag, String message) { return 0; }
    public static int e(String tag, String message, Throwable error) { return 0; }
    public static int wtf(String tag, String message) { return 0; }
    public static int wtf(String tag, String message, Throwable error) { return 0; }
    public static int wtf(String tag, Throwable error) { return 0; }
}
