package xyz.wagyourtail.unimined.jarmerger;

import java.lang.annotation.*;

/**
 * generally, these functions shouldn't be used...
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface OnlyVersions {
    String[] value();
}
