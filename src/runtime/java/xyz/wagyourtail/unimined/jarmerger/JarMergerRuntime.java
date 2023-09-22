package xyz.wagyourtail.unimined.jarmerger;

public class JarMergerRuntime {
    public static final String version;

    static {
        version = System.getProperty("jarMerger.version");
        if (version == null) {
            throw new RuntimeException("jarMerger.version not set");
        }
    }

}
