package xyz.wagyourtail.multiversion.injected.split.annotations;

import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.CLASS)
public @interface Ref {

    String value();

    String member() default "";

    String desc() default "";

}
