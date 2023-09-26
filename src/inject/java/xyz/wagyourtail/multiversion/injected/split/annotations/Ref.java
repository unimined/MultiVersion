package xyz.wagyourtail.multiversion.injected.split.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target({  })
public @interface Ref {

    String value();

    String member() default "";

    String desc() default "";

}
