package ngo.xnet.aiope.core.terminal.backend;

import java.lang.reflect.Method;

/**
 * JNI bridge — delegates to com.termux.terminal.JNI via reflection
 * since that class is package-private.
 */
final class JNI {
    private static final Method sCreateSubprocess;
    private static final Method sSetPtyWindowSize;
    private static final Method sWaitFor;
    private static final Method sClose;

    static {
        try {
            Class<?> c = Class.forName("com.termux.terminal.JNI");
            sCreateSubprocess = c.getDeclaredMethod("createSubprocess", String.class, String.class, String[].class, String[].class, int[].class, int.class, int.class);
            sSetPtyWindowSize = c.getDeclaredMethod("setPtyWindowSize", int.class, int.class, int.class);
            sWaitFor = c.getDeclaredMethod("waitFor", int.class);
            sClose = c.getDeclaredMethod("close", int.class);
            sCreateSubprocess.setAccessible(true);
            sSetPtyWindowSize.setAccessible(true);
            sWaitFor.setAccessible(true);
            sClose.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException("Failed to init JNI bridge", e);
        }
    }

    static int createSubprocess(String cmd, String cwd, String[] args, String[] envVars, int[] processId, int rows, int columns) {
        try { return (int) sCreateSubprocess.invoke(null, cmd, cwd, args, envVars, processId, rows, columns); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static void setPtyWindowSize(int fd, int rows, int cols) {
        try { sSetPtyWindowSize.invoke(null, fd, rows, cols); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static int waitFor(int processId) {
        try { return (int) sWaitFor.invoke(null, processId); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    static void close(int fileDescriptor) {
        try { sClose.invoke(null, fileDescriptor); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
