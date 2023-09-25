package xyz.wagyourtail.multiversion.injected;

public final class CurrentVersion {

    private CurrentVersion() {
    }

    public static String getCurrentVersion() {
        throw new AssertionError("Should be automatically replaced at runtime!");
    }

}
