package xyz.wagyourtail.multiversion.injected.merge.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface MergedClass {

    String[] versions();

    Access[] access() default {};

    Inheritance[] inheritance() default {};

}
