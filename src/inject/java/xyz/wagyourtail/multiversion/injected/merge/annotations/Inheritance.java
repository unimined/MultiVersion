package xyz.wagyourtail.multiversion.injected.merge.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target({  })
public @interface Inheritance {

    String[] versions();

    String superClass() default "";

    String[] interfaces() default {};

}
