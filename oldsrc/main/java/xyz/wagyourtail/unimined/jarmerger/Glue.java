package xyz.wagyourtail.unimined.jarmerger;

import java.lang.annotation.*;

/**
 * links all versions together into one function.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface Glue {

    /**
     * if the wrapper here is for targeting a field,
     * this can be set in {@link #stick()} if it changes to a getter/setter
     * @return
     */
    boolean field() default false;

    /**
     * this field is used to specify different method/field names between versions that should
     * be merged together.
     */
    Stick[] stick() default {};

    @interface Stick {
        String value();
        String version();

        boolean field() default false;
    }
}
