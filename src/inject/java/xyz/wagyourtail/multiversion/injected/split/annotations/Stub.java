package xyz.wagyourtail.multiversion.injected.split.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.METHOD, ElementType.FIELD, ElementType.TYPE })
public @interface Stub {

    String[] versions();

    Ref ref() default @Ref("");

}
