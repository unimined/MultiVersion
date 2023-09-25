package xyz.wagyourtail.multiversion.injected.merge.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target({  })
public @interface Inheritance {

    String[] versions();

    Class<?> superClass() default Void.class;

    Class<?>[] interfaces() default {};

}
